package fr.valcombe.mc.hub;

import fr.valcombe.mc.McHost;
import fr.valcombe.mc.Village;
import fr.valcombe.mc.games.GameCard;
import fr.valcombe.mc.games.GameRoom;
import fr.valcombe.mc.games.Games;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldManager;
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
 * <li>Tableau des parties : deux panneaux de part et d'autre de l'allée, une fiche par partie (nom, état, jour ou nuit,
 * joueurs et places, Conteur, spectateurs), mise à jour en direct. Un clic sur une fiche ouvre l'écran « Parties » sur
 * elle (sans le mod client : la fiche au chat, avec ses liens [Rejoindre] [Regarder]).</li>
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
		MUR,
		/** Tableau des parties : une fiche par partie, et la zone cliquable de chacune. */
		TABLEAU;

		String tag() {
			return "vc_hub_" + name().toLowerCase(Locale.ROOT);
		}
	}

	private final MinecraftServer server;
	private final WorldManager worlds;
	private final Travel travel;
	private final Map<Group, String> keys = new HashMap<>();
	private final Map<Group, Integer> gens = new HashMap<>();
	/** La porte où se trouve chaque joueur (pour ne la déclencher qu'en y entrant). */
	private final Map<String, Integer> inPortal = new HashMap<>();
	/** Les destinations des six portes, refaites avec les noms. */
	private final String[] doors = new String[HubHall.PORTALS.size()];
	private int tick;

	/**
	 * Le mur des rôles montre la partie principale ; chaque porte qui mène à un monde de partie dit ce qui s'y passe
	 * (partie trouvée par {@link Games#byWorld}). Le tableau des parties du hub les présente toutes.
	 */
	public Hub(MinecraftServer server, WorldManager worlds, Travel travel) {
		this.server = server;
		this.worlds = worlds;
		this.travel = travel;
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
		text(level, g, x, y, z, yaw, width, scale, text, -1072689136);
	}

	/** Un texte flottant, avec son fond (ARGB ; 0 : transparent, pour les fiches du tableau posées sur leur fond). */
	private void text(ServerLevel level, Group g, double x, double y, double z, Float yaw, int width, double scale, String text,
			int background) {
		run(level, String.format(Locale.ROOT,
			"summon minecraft:text_display %.3f %.3f %.3f {%s,%sbillboard:\"%s\",line_width:%d,background:%d,shadow:1b,"
				+ "brightness:{sky:15,block:15},transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
				+ "translation:[0f,0f,0f],scale:[%.2ff,%.2ff,%.2ff]},text:%s}",
			x, y, z, tags(g), yaw == null ? "" : String.format(Locale.ROOT, "Rotation:[%.1ff,0f],", yaw),
			yaw == null ? "center" : "fixed", width, background, scale, scale, scale, text));
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
		List<GameCard> cards = Games.all().stream().map(GameRoom::card).toList();
		update(level, Group.TABLEAU, cards.toString(), () -> board(level, cards));
		GameRoom main = Games.main();
		if (main != null) {
			Session session = main.session();
			List<Role> roles = McHost.hallRoles(session);
			String board = McHost.hallText(session);
			update(level, Group.MUR, board + "|" + roles + "|" + Role.customDefs().size(), () -> roleWall(level, board, roles));
		}
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
		text(level, Group.FIXE, HubHall.JOIN_X + 0.5, feet + 3.2, HubHall.JOIN_Z + 0.5, null, 200, 1.1,
			comp("Bienvenue à Valcombe", "gold", true,
				"\nChoisis ta partie au tableau des parties : rejoins-la, ou regarde-la.", "white", false,
				"\nLa dalle dorée inscrit à la partie principale. Menu J : « Parties », « Mondes ».", "gray", false));
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
		GameRoom room = Games.byWorld(dest);
		if (room != null) {
			// Le village d'une partie : ce qui s'y passe en ce moment, comme sur sa fiche du tableau.
			GameCard c = room.card();
			String state = c.status() == GameCard.Status.OUVERTE ? c.playersLine()
				: c.status().label + ", " + c.phaseLine().toLowerCase(Locale.ROOT);
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

	// ------------------------------------------------------------------
	// Tableau des parties
	// ------------------------------------------------------------------

	/** Les fiches que montre le tableau des parties en ce moment (pour les tests en jeu), ou null avant la première pose. */
	public String boardShown() {
		return keys.get(Group.TABLEAU);
	}

	/** L'étiquette qui porte l'identifiant de la partie d'une fiche du tableau (sa zone cliquable). */
	static final String CARD_TAG = "vc_partie_";

	/**
	 * La partie dont cette entité est la fiche cliquable sur le tableau des parties du hub, ou null (ce n'est pas une
	 * fiche du tableau).
	 */
	public static String boardCard(net.minecraft.world.entity.Entity e) {
		if (e == null || !e.entityTags().contains(Group.TABLEAU.tag())) {
			return null;
		}
		for (String t : e.entityTags()) {
			if (t.startsWith(CARD_TAG)) {
				return t.substring(CARD_TAG.length());
			}
		}
		return null;
	}

	/** Le fond d'une fiche, selon l'état de la partie : un bloc teint, mat, posé à plat sur le panneau. */
	private static String backdrop(GameCard.Status s) {
		return switch (s) {
			case OUVERTE -> "minecraft:green_terracotta";
			case EN_COURS -> "minecraft:red_terracotta";
			case EN_PAUSE -> "minecraft:orange_terracotta";
			case SUSPENDUE -> "minecraft:cyan_terracotta";
		};
	}

	/** La couleur de l'état, lisible sur son fond. */
	private static String ink(GameCard.Status s) {
		return switch (s) {
			case OUVERTE -> "#B7EF8F";
			case EN_COURS -> "#FFB4A2";
			case EN_PAUSE -> "#FFE08A";
			case SUSPENDUE -> "#CFE6EE";
		};
	}

	/** L'emblème d'une fiche : la cloche du salon, le soleil ou la lune de la partie, la lanterne de la pause, l'ancre du suspens. */
	private static String emblem(GameCard c) {
		return switch (c.status()) {
			case OUVERTE -> "icone_e_cloche";
			case EN_PAUSE -> "icone_e_lanterne";
			case SUSPENDUE -> "icone_e_ancre";
			case EN_COURS -> "nuit".equals(c.phaseCode()) ? "icone_e_lune" : "jour".equals(c.phaseCode()) ? "icone_e_soleil" : "icone_e_etoile";
		};
	}

	/** Un nom trop long pour une ligne de fiche, raccourci. */
	private static String shorten(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1).strip() + "…";
	}

	/** L'état et la phase, sur une ligne : « ● En cours · Nuit 2 », « ● Ouverte · salon ». */
	private static String statusLine(GameCard c) {
		String phase = c.status() == GameCard.Status.OUVERTE ? "salon" : c.phaseLine();
		return "● " + c.status().label + " · " + phase;
	}

	/** Les places du village, une pastille chacune : dorées pour les joueurs, sombres pour les places libres. */
	private static String dots(GameCard c) {
		int taken = Math.min(c.players(), c.places());
		return comp("●".repeat(taken), "#F2C14E", false, "●".repeat(Math.max(0, c.places() - taken)), "#5E5346", false);
	}

	/**
	 * Le tableau des parties : l'en-tête de chaque panneau, puis une fiche par partie (six au plus ; au-delà, l'en-tête
	 * renvoie au menu J), devant en entier, derrière en abrégé, chacune avec sa zone cliquable.
	 */
	private void board(ServerLevel level, List<GameCard> cards) {
		double feet = HubHall.FEET;
		double front = HubHall.BOARD_FRONT + 0.04;
		double back = HubHall.BOARD_BACK - 0.04;
		double westMid = -(HubHall.BOARD_INNER + HubHall.BOARD_OUTER) / 2.0 + 0.5;
		double eastMid = (HubHall.BOARD_INNER + HubHall.BOARD_OUTER) / 2.0 + 0.5;
		int more = cards.size() - HubHall.SLOTS;
		// En-têtes, sur les poutres : le titre à l'ouest, le mode d'emploi (ou les parties en plus) à l'est.
		text(level, Group.TABLEAU, westMid, feet + 4.2, front, 0f, 300, 0.75, comp("TABLEAU DES PARTIES", "#F2C14E", true), 0);
		String hint = more > 0 ? "Et " + more + (more > 1 ? " autres parties" : " autre partie") + " : menu J, « Parties »"
			: "Clique une fiche : rejoindre ou regarder";
		text(level, Group.TABLEAU, eastMid, feet + 4.25, front, 0f, 300, 0.6, comp(hint, "#F2E6C9", false), 0);
		text(level, Group.TABLEAU, westMid, feet + 4.2, back, 180f, 300, 0.75, comp("TABLEAU DES PARTIES", "#F2C14E", true), 0);
		text(level, Group.TABLEAU, eastMid, feet + 4.2, back, 180f, 300, 0.75, comp("TABLEAU DES PARTIES", "#F2C14E", true), 0);
		for (int slot = 0; slot < HubHall.SLOTS; slot++) {
			double cx = HubHall.cardX(slot);
			if (slot >= cards.size()) {
				text(level, Group.TABLEAU, cx, feet + 2.3, front, 0f, 120, 0.6, comp("Place libre", "#8C7F74", false,
					"\nune partie d'essai viendra ici", "#6E625A", false), 0);
				continue;
			}
			GameCard c = cards.get(slot);
			cardFront(level, c, cx, front);
			cardBack(level, c, cx, back);
			// La zone cliquable : toute la fiche, des deux côtés du panneau.
			run(level, String.format(Locale.ROOT,
				"summon minecraft:interaction %.3f %.3f %.3f {width:%.1ff,height:3.0f,response:1b,Tags:[\"vc_hub\",\"%s\",\"%s_%d\",\"%s\"]}",
				cx, feet + 1.0, HubHall.BOARD_Z + 0.5, HubHall.CARD_WIDTH - 0.1, Group.TABLEAU.tag(), Group.TABLEAU.tag(),
				gens.get(Group.TABLEAU), esc(CARD_TAG + c.id())));
		}
	}

	/** Le fond de couleur d'une fiche, plaqué sur une face du panneau ({@code z} : la face). */
	private void cardPlate(ServerLevel level, GameCard c, double cx, double z, boolean front) {
		double depth = 0.02;
		double at = front ? HubHall.BOARD_FRONT + 0.005 : HubHall.BOARD_BACK - 0.005 - depth;
		double w = HubHall.CARD_WIDTH - 0.2;
		run(level, String.format(Locale.ROOT,
			"summon minecraft:block_display %.3f %.3f %.3f {%s,brightness:{sky:15,block:13},block_state:{Name:\"%s\"},"
				+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],"
				+ "scale:[%.2ff,2.8f,%.3ff]}}",
			cx - w / 2, HubHall.FEET + 1.1, at, tags(Group.TABLEAU), backdrop(c.status()), w, depth));
		if (c.main()) {
			// La partie principale porte un liseré d'or en haut de sa fiche.
			run(level, String.format(Locale.ROOT,
				"summon minecraft:block_display %.3f %.3f %.3f {%s,brightness:{sky:15,block:15},block_state:{Name:\"minecraft:gold_block\"},"
					+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],"
					+ "scale:[%.2ff,0.07f,%.3ff]}}",
				cx - w / 2, HubHall.FEET + 3.83, front ? at + 0.002 : at - 0.002, tags(Group.TABLEAU), w, depth));
		}
	}

	/** L'emblème d'une fiche : un jeton de Valcombe qui tourne vers celui qui regarde. */
	private void cardEmblem(ServerLevel level, GameCard c, double cx, double y, double z) {
		run(level, String.format(Locale.ROOT,
			"summon minecraft:item_display %.3f %.3f %.3f {%s,billboard:\"vertical\",brightness:{sky:15,block:15},"
				+ "item:{id:\"minecraft:paper\",count:1,components:{\"minecraft:item_model\":\"valcombe:%s\"}},"
				+ "transformation:{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[0.7f,0.7f,0.7f]}}",
			cx, y, z, tags(Group.TABLEAU), emblem(c)));
	}

	/** La fiche entière, face à l'entrée : emblème, nom, état et phase, places, joueurs, Conteur, sorte et spectateurs. */
	private void cardFront(ServerLevel level, GameCard c, double cx, double z) {
		double feet = HubHall.FEET;
		cardPlate(level, c, cx, z, true);
		cardEmblem(level, c, cx, feet + 3.32, z + 0.12);
		text(level, Group.TABLEAU, cx, feet + 2.5, z, 0f, 112, 0.95, comp(shorten(c.name(), 18), "#F7EBD0", true), 0);
		text(level, Group.TABLEAU, cx, feet + 2.2, z, 0f, 150, 0.72, comp(statusLine(c), ink(c.status()), true), 0);
		String kind = c.kindLabel() + (c.watchers() > 0 ? " · " + c.watchersLine() : "");
		String body = "[" + dots(c) + ",{text:\"\\n" + esc(c.playersLine()) + "\",color:\"#F2E6C9\"},{text:\"\\n"
			+ esc(c.storytellerLine()) + "\",color:\"#F2E6C9\"},{text:\"\\n" + esc(kind) + "\",color:\"#D9CBB0\"}]";
		text(level, Group.TABLEAU, cx, feet + 1.42, z, 0f, 170, 0.62, body, 0);
		text(level, Group.TABLEAU, cx, feet + 1.2, z, 0f, 220, 0.5, comp("Clic : rejoindre ou regarder", "#CFC3AE", false), 0);
	}

	/** La fiche abrégée, au dos du panneau (vue des dalles) : emblème, nom, état et phase, joueurs. */
	private void cardBack(ServerLevel level, GameCard c, double cx, double z) {
		double feet = HubHall.FEET;
		cardPlate(level, c, cx, z, false);
		cardEmblem(level, c, cx, feet + 3.25, z - 0.12);
		text(level, Group.TABLEAU, cx, feet + 2.45, z, 180f, 112, 0.95, comp(shorten(c.name(), 18), "#F7EBD0", true), 0);
		text(level, Group.TABLEAU, cx, feet + 2.1, z, 180f, 150, 0.72, comp(statusLine(c), ink(c.status()), true), 0);
		text(level, Group.TABLEAU, cx, feet + 1.7, z, 180f, 170, 0.62, comp(c.playersLine(), "#F2E6C9", false), 0);
		text(level, Group.TABLEAU, cx, feet + 1.25, z, 180f, 220, 0.5, comp("Clic : la fiche de la partie", "#CFC3AE", false), 0);
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
