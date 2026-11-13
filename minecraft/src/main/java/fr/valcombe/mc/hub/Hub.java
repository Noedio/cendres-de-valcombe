package fr.valcombe.mc.hub;

import fr.valcombe.mc.McHost;
import fr.valcombe.mc.Village;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Role;
import fr.valcombe.session.Session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Le hub : l'accueil commun où arrivent tous les joueurs, et d'où l'on part vers les mondes de Valcombe.
 * <ul>
 * <li>Au premier démarrage, le monde du hub (sorte {@link WorldKind#HUB}) est créé s'il n'existe pas, et sa salle
 * d'accueil ({@link HubHall}) bâtie. Elle est rebâtie quand le hub est remis à neuf, quand son plan change
 * ({@link HubHall#LAYOUT}) ou si ses fichiers ont disparu (une marque {@code valcombe-accueil.txt} est posée dans le
 * dossier du monde).</li>
 * <li>Dalles d'inscription : les mêmes que dans la salle d'attente du village (branchées sur {@link McHost}).</li>
 * <li>Mur des rôles : le script de la prochaine partie, ses rôles en jetons, les inscrits et le Conteur.</li>
 * <li>Portes : six portes portent le nom d'un monde (le monde de la partie, puis les autres mondes de partie) ; on y entre
 * pour y aller. Le menu J, entrée « Mondes », donne la liste complète.</li>
 * </ul>
 * Les textes et jetons sont des entités d'affichage, rangées en groupes (étiquette {@code vc_hub_<groupe>}) refaits
 * seulement quand leur contenu change, et seulement quand quelqu'un est au hub (ses tronçons sont alors chargés).
 * Chaque pose porte une génération : celles d'avant un redémarrage, restées dans des tronçons qui n'étaient pas
 * chargés, sont retirées dès qu'elles réapparaissent. Fil du serveur.
 */
public final class Hub implements WorldManager.Listener {
	private static final Logger LOG = LoggerFactory.getLogger("valcombe");
	/** Le nom du hub créé au premier démarrage (son identifiant en est tiré : {@code hub}). */
	public static final String NAME = "Hub";
	/** La marque posée dans le dossier du monde du hub : la version du plan de la salle bâtie. */
	static final String MARKER = "valcombe-accueil.txt";

	/** Les groupes d'entités d'affichage du hub. */
	private enum Group {
		/** Accueil, panneaux des dalles et des règles : ne changent qu'avec le plan. */
		FIXE,
		/** Noms des mondes au-dessus des portes. */
		NOMS,
		/** Le voile tourbillonnant des portes ouvertes. */
		VOILES,
		/** Mur des rôles : panneau de la prochaine partie et jetons. */
		MUR;

		String tag() {
			return "vc_hub_" + name().toLowerCase(Locale.ROOT);
		}
	}

	private final MinecraftServer server;
	private final WorldManager worlds;
	private final Travel travel;
	private final Session session;
	private final Map<Group, String> keys = new HashMap<>();
	private final Map<Group, Integer> gens = new HashMap<>();
	/** La porte où se trouve chaque joueur (pour ne la déclencher qu'en y entrant). */
	private final Map<String, Integer> inPortal = new HashMap<>();
	/** Les destinations des six portes, refaites avec les noms. */
	private final String[] doors = new String[HubHall.PORTALS.size()];
	private int tick;

	public Hub(MinecraftServer server, WorldManager worlds, Travel travel, Session session) {
		this.server = server;
		this.worlds = worlds;
		this.travel = travel;
		this.session = session;
		int seed = (int) (System.nanoTime() & 0xFFFFF);
		for (Group g : Group.values()) {
			gens.put(g, seed + g.ordinal() * 7);
		}
	}

	// ------------------------------------------------------------------
	// Démarrage
	// ------------------------------------------------------------------

	/** Au démarrage du serveur : le hub est créé s'il n'existe pas, et sa salle bâtie si elle manque. */
	public void start() {
		WorldEntry hub = travel.hub();
		if (hub == null) {
			hub = worlds.create(WorldKind.HUB, NAME);
			LOG.info("Hub de Valcombe créé : {}.", hub.id());
		}
		ensureHall(hub, worlds.level(hub.id()));
	}

	/** L'identifiant du hub, ou null s'il n'existe pas. */
	public String id() {
		WorldEntry hub = travel.hub();
		return hub == null ? null : hub.id();
	}

	/** Le monde du hub s'il est ouvert, sinon null (jamais d'ouverture ici : ce sont des lectures de chaque tick). */
	private ServerLevel levelIfOpen() {
		String id = id();
		return id == null || !worlds.isOpen(id) ? null : server.getLevel(WorldManager.key(id));
	}

	private Path marker(WorldEntry hub) {
		return worlds.folder(hub.id()).resolve(MARKER);
	}

	/** Bâtit la salle si la marque manque ou porte un plan plus ancien. */
	private void ensureHall(WorldEntry hub, ServerLevel level) {
		int built = -1;
		try {
			Path m = marker(hub);
			if (Files.exists(m)) {
				built = Integer.parseInt(Files.readString(m, StandardCharsets.UTF_8).strip());
			}
		} catch (IOException | NumberFormatException e) {
			LOG.warn("Marque de la salle d'accueil illisible : la salle est rebâtie.", e);
		}
		if (built < HubHall.LAYOUT) {
			buildHall(hub, level);
		}
	}

	/** Bâtit (ou rebâtit) la salle d'accueil et note la version du plan dans le dossier du monde. */
	public void buildHall(WorldEntry hub, ServerLevel level) {
		long t0 = System.nanoTime();
		HubHall.build(level);
		keys.clear(); // tous les affichages seront reposés
		try {
			Path m = marker(hub);
			Files.createDirectories(m.getParent());
			Files.writeString(m, Integer.toString(HubHall.LAYOUT), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.warn("Marque de la salle d'accueil impossible à écrire : elle sera rebâtie au prochain démarrage.", e);
		}
		LOG.info("Salle d'accueil du hub bâtie (plan {}) en {} ms.", HubHall.LAYOUT, (System.nanoTime() - t0) / 1_000_000);
	}

	/** Le hub remis à neuf : sa salle est rebâtie aussitôt. */
	@Override
	public void recreated(WorldEntry entry, ServerLevel level) {
		if (entry.sorte() == WorldKind.HUB) {
			buildHall(entry, level);
		}
	}

	// ------------------------------------------------------------------
	// Dalles et portes
	// ------------------------------------------------------------------

	/** La dalle sous les pieds de ce joueur s'il est dans la salle d'accueil, sinon null. */
	public Village.Pad padAt(ServerPlayer p) {
		ServerLevel level = levelIfOpen();
		return level == null || p.level() != level ? null : HubHall.padAt(level, p.blockPosition());
	}

	/** La destination de cette porte (clé de {@link Travel}), ou null si elle ne mène nulle part pour l'instant. */
	public String door(int index) {
		return index >= 0 && index < doors.length ? doors[index] : null;
	}

	/**
	 * Les destinations des portes : le monde de la partie (son village, première porte, au milieu du mur ouest), puis
	 * les autres mondes de partie (vraies parties d'abord, puis parties d'essai) dans l'ordre du registre, tant qu'il
	 * reste des portes. Sans monde de partie, la première porte reste libre.
	 */
	private void assignDoors() {
		List<String> list = new ArrayList<>();
		String game = travel.game();
		list.add(game);
		for (WorldEntry e : worlds.list()) {
			if ((e.sorte() == WorldKind.PARTIE || e.sorte() == WorldKind.ESSAI) && !e.id().equals(game)) {
				list.add(e.id());
			}
		}
		for (int i = 0; i < doors.length; i++) {
			doors[i] = i < list.size() ? list.get(i) : null;
		}
	}

	/** Un joueur entre dans une porte : en route vers son monde, ou repoussé avec la raison. */
	private void enter(ServerPlayer p, HubHall.Portal portal) {
		assignDoors(); // les noms au-dessus des portes peuvent avoir deux secondes de retard, pas leur destination
		String dest = door(portal.index());
		Vec3 f = portal.front();
		LastPositions.Spot front = new LastPositions.Spot(f.x, f.y, f.z, portal.frontYaw(), 0f);
		if (dest == null) {
			pushBack(p, front, "Cette porte ne mène encore nulle part : aucune partie ne l'occupe.");
			return;
		}
		try {
			String msg = travel.go(p, dest, front);
			p.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GREEN));
		} catch (GameException e) {
			pushBack(p, front, e.getMessage());
		}
	}

	private static void pushBack(ServerPlayer p, LastPositions.Spot front, String why) {
		p.teleportTo((ServerLevel) p.level(), front.x(), front.y(), front.z(), java.util.Set.of(), front.yaw(), p.getXRot(), false);
		p.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.RED));
	}

	// ------------------------------------------------------------------
	// Chaque tick
	// ------------------------------------------------------------------

	/** À chaque tick du serveur : portes (tous les 2 ticks), affichages (toutes les 2 secondes, si quelqu'un est là). */
	public void tick() {
		tick++;
		ServerLevel level = levelIfOpen();
		if (level == null) {
			inPortal.clear();
			return;
		}
		if (tick % 2 == 0) {
			watchPortals(level);
		}
		if (tick % 40 == 7 && !level.players().isEmpty()) {
			refresh(level);
		}
		if (tick % 200 == 13 && !level.players().isEmpty()) {
			// Affichages d'une ancienne génération, réapparus avec leurs tronçons : retirés.
			for (Group g : Group.values()) {
				run(level, "kill @e[tag=" + g.tag() + ",tag=!" + g.tag() + "_" + gens.get(g) + ",x=0,y=" + HubHall.FEET
					+ ",z=0,distance=..96]");
			}
		}
	}

	private void watchPortals(ServerLevel level) {
		Map<String, Integer> seen = new HashMap<>();
		for (ServerPlayer p : new ArrayList<>(level.players())) {
			HubHall.Portal portal = HubHall.portalAt(p.blockPosition());
			if (portal == null || p.isSpectator()) {
				continue;
			}
			String name = p.getScoreboardName();
			seen.put(name, portal.index());
			Integer before = inPortal.get(name);
			if (before == null || before != portal.index()) {
				enter(p, portal);
			}
		}
		inPortal.clear();
		inPortal.putAll(seen);
	}

	// ------------------------------------------------------------------
	// Affichages
	// ------------------------------------------------------------------

	/** Lance une commande dans le monde du hub, depuis le centre de la salle, sans rien dire à personne. */
	private void run(ServerLevel level, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput().withLevel(level)
			.withPosition(new Vec3(0.5, HubHall.FEET, 0.5)), command);
	}

	/** Un texte pour SNBT, entre guillemets doubles. */
	private static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
	}

	/** Les étiquettes d'une pose : le groupe et sa génération. */
	private String tags(Group g) {
		return "Tags:[\"vc_hub\",\"" + g.tag() + "\",\"" + g.tag() + "_" + gens.get(g) + "\"]";
	}

	/**
	 * Un texte flottant. {@code yaw} null : il suit le regard (billboard), sinon il est plaqué, tourné vers ce lacet.
	 * {@code text} est un composant de texte en SNBT (liste ou objet).
	 */
	private void text(ServerLevel level, Group g, double x, double y, double z, Float yaw, int width, double scale, String text) {
		run(level, String.format(Locale.ROOT,
			"summon minecraft:text_display %.3f %.3f %.3f {%s,%sbillboard:\"%s\",line_width:%d,background:-1072689136,shadow:1b,"
				+ "brightness:{sky:15,block:15},transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
				+ "translation:[0f,0f,0f],scale:[%.2ff,%.2ff,%.2ff]},text:%s}",
			x, y, z, tags(g), yaw == null ? "" : String.format(Locale.ROOT, "Rotation:[%.1ff,0f],", yaw),
			yaw == null ? "center" : "fixed", width, scale, scale, scale, text));
	}

	/** Un composant de texte : morceaux (texte, couleur, gras) mis bout à bout. */
	private static String comp(Object... parts) {
		StringBuilder s = new StringBuilder("[\"\"");
		for (int i = 0; i + 2 < parts.length; i += 3) {
			s.append(",{text:\"").append(esc((String) parts[i])).append("\",color:\"").append(parts[i + 1]).append('"');
			if (Boolean.TRUE.equals(parts[i + 2])) {
				s.append(",bold:1b");
			}
			s.append('}');
		}
		return s.append(']').toString();
	}

	/** Refait les groupes dont le contenu a changé. */
	private void refresh(ServerLevel level) {
		assignDoors();
		update(level, Group.FIXE, "plan " + HubHall.LAYOUT, () -> fixed(level));
		StringBuilder names = new StringBuilder();
		StringBuilder open = new StringBuilder();
		List<String[]> labels = new ArrayList<>();
		for (HubHall.Portal portal : HubHall.PORTALS) {
			String[] l = doorLabel(door(portal.index()));
			labels.add(l);
			names.append(l[0]).append('|').append(l[1]).append('#');
			open.append(door(portal.index()) != null ? '1' : '0');
		}
		update(level, Group.NOMS, names.toString(), () -> doorNames(level, labels));
		update(level, Group.VOILES, open.toString(), () -> veils(level));
		List<Role> roles = McHost.hallRoles(session);
		String board = McHost.hallText(session);
		update(level, Group.MUR, board + "|" + roles + "|" + Role.customDefs().size(), () -> roleWall(level, board, roles));
	}

	private void update(ServerLevel level, Group g, String key, Runnable pose) {
		if (key.equals(keys.get(g))) {
			return;
		}
		keys.put(g, key);
		gens.merge(g, 1, Integer::sum);
		run(level, "kill @e[tag=" + g.tag() + ",x=0,y=" + HubHall.FEET + ",z=0,distance=..96]");
		pose.run();
	}

	/** Accueil au-dessus de la dalle dorée, nom de chaque dalle, règles sur le mur sud. */
	private void fixed(ServerLevel level) {
		double feet = HubHall.FEET;
		text(level, Group.FIXE, HubHall.JOIN_X + 0.5, feet + 3.2, HubHall.JOIN_Z + 0.5, null, 260, 1.1,
			comp("Bienvenue à Valcombe", "gold", true,
				"\nInscris-toi sur la dalle dorée : tu rejoins aussitôt le village de la partie.", "white", false,
				"\nPasse une porte pour rejoindre un monde, ou ouvre le menu J, « Mondes ».", "gray", false));
		text(level, Group.FIXE, HubHall.JOIN_X + 0.5, feet + 1.9, HubHall.JOIN_Z + 0.5, null, 120, 0.7,
			comp("S'inscrire", "gold", true));
		text(level, Group.FIXE, HubHall.LEAVE_X + 0.5, feet + 1.9, 0.5, null, 120, 0.7,
			comp("Se désinscrire", "red", true));
		text(level, Group.FIXE, HubHall.STORYTELLER_X + 0.5, feet + 1.9, 0.5, null, 140, 0.7,
			comp("Devenir Conteur", "light_purple", true, "\nréservé aux opérateurs", "gray", false));
		double z = HubHall.HALF_Z - 0.08;
		text(level, Group.FIXE, -6.5, feet + 2.35, z, 180f, 200, 0.9,
			comp("Le bien gagne quand le Démon meurt.", "white", false,
				"\nLe mal gagne quand il ne reste que deux vivants.", "white", false));
		text(level, Group.FIXE, 7.5, feet + 2.35, z, 180f, 200, 0.9,
			comp("La nuit, chacun dort chez soi.", "white", false,
				"\nLe jour, on débat, on chuchote, puis on vote à l'horloge.", "white", false));
	}

	/** Le nom d'une porte (deux lignes) : le monde, puis ce qui s'y passe. */
	private String[] doorLabel(String dest) {
		if (dest == null) {
			return new String[] {"Porte libre", "Une partie viendra s'ouvrir ici"};
		}
		int n = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (dest.equals(travel.keyOf(p))) {
				n++;
			}
		}
		String count = n == 0 ? "personne" : n == 1 ? "1 joueur" : n + " joueurs";
		WorldEntry e = worlds.find(dest);
		if (e == null) {
			return new String[] {"Porte libre", "Une partie viendra s'ouvrir ici"};
		}
		if (dest.equals(travel.game())) {
			// Le village de la partie : ce qui s'y passe en ce moment.
			Game g = session.game();
			String state = g != null && g.winner() == null
				? "partie en cours, " + (g.phase() == Game.Phase.NIGHT ? "nuit " + g.night() : "jour " + g.day())
				: session.lobby().isEmpty() ? "salon ouvert" : session.lobby().size() + " inscrit(s)";
			return new String[] {e.nom(), state + " · " + count};
		}
		String label = e.sorte().label();
		return new String[] {e.nom(), Character.toUpperCase(label.charAt(0)) + label.substring(1) + " · " + count};
	}

	private void doorNames(ServerLevel level, List<String[]> labels) {
		for (HubHall.Portal portal : HubHall.PORTALS) {
			String[] l = labels.get(portal.index());
			boolean open = door(portal.index()) != null;
			Vec3 at = portal.label();
			text(level, Group.NOMS, at.x, at.y, at.z, portal.boardYaw(), 160, 0.85,
				comp(l[0], open ? "gold" : "gray", open, "\n" + l[1], open ? "white" : "dark_gray", false));
		}
	}

	/** Le voile violet des portes ouvertes : l'image d'un portail, sans son effet (un simple affichage de bloc). */
	private void veils(ServerLevel level) {
		for (HubHall.Portal portal : HubHall.PORTALS) {
			if (door(portal.index()) == null) {
				continue;
			}
			for (int dz = -1; dz <= 1; dz++) {
				for (int y = HubHall.FEET; y <= HubHall.FEET + 3; y++) {
					run(level, String.format(Locale.ROOT,
						"summon minecraft:block_display %d %d %d {%s,brightness:{sky:15,block:15},"
							+ "block_state:{Name:\"minecraft:nether_portal\",Properties:{axis:\"z\"}}}",
						portal.wallX(), y, portal.z() + dz, tags(Group.VOILES)));
				}
			}
		}
	}

	/** Le mur des rôles : le panneau de la prochaine partie, puis les jetons des rôles du script en rangées. */
	private void roleWall(ServerLevel level, String board, List<Role> roles) {
		double z = HubHall.ROLE_WALL_Z;
		double feet = HubHall.FEET;
		text(level, Group.MUR, 0.5, feet + 4.25, z, 0f, 300, 0.75, comp(board, "gold", false));
		int perRow = 13;
		for (int i = 0; i < roles.size(); i++) {
			Role r = roles.get(i);
			int row = i / perRow;
			int inRow = Math.min(perRow, roles.size() - row * perRow);
			double x = 0.5 + (i % perRow - (inRow - 1) / 2.0) * 1.2;
			double y = feet + 3.55 - row * 0.8;
			run(level, String.format(Locale.ROOT,
				"summon minecraft:item_display %.2f %.2f %.2f {%s,billboard:\"vertical\","
					+ "brightness:{sky:15,block:15},item:{id:\"minecraft:paper\",count:1,components:{\"minecraft:item_model\":"
					+ "\"valcombe:jeton_%s\",\"minecraft:custom_name\":\"%s\"}},transformation:{translation:[0f,0f,0f],"
					+ "left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[0.7f,0.7f,0.7f]}}",
				x, y, z + 0.1, tags(Group.MUR), r.modelSuffix(), esc(r.displayName)));
		}
	}
}
