package fr.valcombe.mc.hub;

import fr.valcombe.mc.McHost;
import fr.valcombe.mc.games.GameRoom;
import fr.valcombe.mc.games.Games;
import fr.valcombe.mc.worlds.BuildAccess;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Les voyages entre les mondes de Valcombe : le hub, le monde de la partie (son village) et les autres mondes du
 * {@link WorldManager}. Le monde principal de Minecraft n'est pas une destination : la partie se joue dans son monde
 * de partie ({@link McHost#worldId()}).
 * <ul>
 * <li>Chaque joueur retrouve sa dernière position et son orientation dans chaque monde ({@link LastPositions},
 * gardées d'un démarrage à l'autre). La première fois, il arrive au point d'arrivée du monde : la salle
 * d'accueil au hub, la salle d'attente (ou la place) au village de la partie, le centre ailleurs.</li>
 * <li>À la connexion, tout le monde arrive au hub, sauf un joueur que la partie en cours retient à sa table.</li>
 * <li>Les opérateurs voient tous les mondes ; les autres joueurs le hub et les mondes de partie
 * ({@link WorldKind#openToAll()}), plus les ateliers où un opérateur les a invités à construire ({@link BuildAccess}).</li>
 * <li>Un joueur assis à la partie en cours ne quitte pas son monde avant la fin.</li>
 * <li>Une partie en suspens renvoie tout son monde au hub ({@link #toHub}) ; on n'y entre plus, sauf pour la mener
 * (opérateur, son Conteur), jusqu'à sa reprise.</li>
 * <li>C'est aussi le repli du gestionnaire de mondes : les joueurs d'un monde remis à neuf ou supprimé vont au hub
 * (ceux du hub lui-même, au village de la partie).</li>
 * </ul>
 * Tout se fait sur le fil du serveur.
 */
public final class Travel implements WorldManager.Fallback, WorldManager.Listener {
	/**
	 * Le mot qui désigne le monde de la partie dans les commandes : {@code /vc monde aller principal} (ou
	 * {@code village}, ou {@code partie}). C'était la clé du monde principal avant les mondes de partie ; les positions
	 * gardées sous cette clé sont oubliées.
	 */
	public static final String MAIN = LastPositions.MAIN;
	/** La sorte d'une destination « monde de la partie » dans le menu « Mondes » (son emblème : la cloche du village). */
	public static final String GAME_KIND = "village";

	/**
	 * Une destination telle que la montrent le menu « Mondes » et les portes du hub.
	 *
	 * @param id      l'identifiant du monde de Valcombe
	 * @param nom     nom affiché
	 * @param sorte   {@link #GAME_KIND} pour le monde de la partie, sinon le code de la sorte ({@code hub}, {@code partie}...)
	 * @param label   la sorte en clair, pour les joueurs (« partie d'essai »)
	 * @param joueurs joueurs qui s'y trouvent en ce moment
	 * @param ici     le joueur pour qui la liste est faite s'y trouve
	 */
	public record Destination(String id, String nom, String sorte, String label, int joueurs, boolean ici) {
		/** Ce que le menu « Mondes » reçoit pour cette destination. */
		public Map<String, Object> view() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", id);
			m.put("nom", nom);
			m.put("sorte", sorte);
			m.put("label", label);
			m.put("joueurs", joueurs);
			m.put("ici", ici);
			return m;
		}
	}

	private final MinecraftServer server;
	private final WorldManager worlds;
	private final LastPositions positions;
	/** L'identifiant du monde de la partie principale (« principal » dans les commandes), ou null. */
	private final java.util.function.Supplier<String> mainGame;
	private int tick;

	/**
	 * @param mainGame le monde de la partie principale ; les autres parties (parties d'essai) sont trouvées par
	 *                 {@link Games#byWorld}, chacune dans son monde
	 */
	public Travel(MinecraftServer server, WorldManager worlds, LastPositions positions, java.util.function.Supplier<String> mainGame) {
		this.server = server;
		this.worlds = worlds;
		this.positions = positions;
		this.mainGame = mainGame;
		// Positions gardées dans le monde principal, du temps où la partie s'y jouait : elles ne désignent plus rien.
		positions.forgetWorld(MAIN);
	}

	/** L'hôte de la partie qui se joue dans ce monde, ou null (hub, atelier, ou parties pas encore ouvertes). */
	private static McHost hostOf(String key) {
		GameRoom r = Games.byWorld(key);
		return r == null ? null : r.host();
	}

	/** Les positions gardées (pour les tests et le hub). */
	public LastPositions positions() {
		return positions;
	}

	// ------------------------------------------------------------------
	// Mondes et droits
	// ------------------------------------------------------------------

	/** La fiche du hub, ou null s'il n'existe pas (encore). */
	public WorldEntry hub() {
		List<WorldEntry> hubs = worlds.registry().list(WorldKind.HUB);
		return hubs.isEmpty() ? null : hubs.get(0);
	}

	/** L'identifiant du monde de la partie principale (son village), ou null s'il n'y en a pas. */
	public String game() {
		String id = mainGame.get();
		return id != null && worlds.find(id) != null ? id : null;
	}

	/** La clé du monde où se trouve ce niveau : l'identifiant d'un monde de Valcombe, ou null (monde principal, Nether, End). */
	public String keyOf(Level level) {
		WorldEntry e = worlds.entryOf(level);
		return e == null ? null : e.id();
	}

	public String keyOf(ServerPlayer p) {
		return keyOf(p.level());
	}

	private static boolean operator(ServerPlayer p) {
		return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	/**
	 * Ce joueur peut-il voir ce monde (et y aller) ? Les opérateurs, tous ; les autres, le hub et les mondes de partie,
	 * plus les ateliers où ils sont invités à construire (voir {@link BuildAccess#canSee}).
	 */
	public boolean canSee(ServerPlayer p, WorldEntry e) {
		BuildAccess access = BuildAccess.current();
		if (access != null) {
			return access.canSee(p, e);
		}
		return e != null && (operator(p) || e.sorte().openToAll());
	}

	/** Pourquoi ce joueur ne peut pas quitter le monde où il est, ou null s'il le peut. */
	public String blocked(ServerPlayer p) {
		GameRoom room = Games.of(p); // la partie du monde où il est, s'il y en a une
		if (room != null && room.suspended()) {
			return null; // partie en suspens : rien ne retient personne
		}
		Game g = room == null ? null : room.session().game();
		if (g != null && g.winner() == null && g.seatOf(p.getScoreboardName()) >= 0) {
			return "Tu joues la partie en cours : tu restes au village jusqu'à la fin.";
		}
		return null;
	}

	/** Le nom affiché d'une destination. */
	public String name(String key) {
		WorldEntry e = key == null ? null : worlds.find(key);
		return e == null ? String.valueOf(key) : e.nom();
	}

	/**
	 * Les destinations que ce joueur voit, dans l'ordre du menu : le hub, le monde de la partie, puis les autres mondes
	 * dans l'ordre du registre (parties, parties d'essai, puis les ateliers pour un opérateur).
	 */
	public List<Destination> destinations(ServerPlayer p) {
		Map<String, Integer> present = new HashMap<>();
		for (ServerPlayer o : server.getPlayerList().getPlayers()) {
			String k = keyOf(o);
			if (k != null) {
				present.merge(k, 1, Integer::sum);
			}
		}
		String here = keyOf(p);
		List<Destination> out = new ArrayList<>();
		WorldEntry hub = hub();
		if (hub != null) {
			out.add(destination(hub, present, here));
		}
		String game = game();
		if (game != null) {
			WorldEntry e = worlds.find(game);
			out.add(new Destination(e.id(), e.nom(), GAME_KIND, "le village de la partie", present.getOrDefault(e.id(), 0),
				e.id().equals(here)));
		}
		for (WorldEntry e : worlds.list()) {
			if (e.sorte() != WorldKind.HUB && !e.id().equals(game) && canSee(p, e)) {
				out.add(destination(e, present, here));
			}
		}
		if (operator(p) && worlds.registry().list(WorldKind.ATELIER_STRUCTURES).isEmpty()) {
			// L'atelier structures n'existe pas encore : le menu le propose quand même (« Y aller » le crée et le dispose).
			WorldKind k = WorldKind.ATELIER_STRUCTURES;
			out.add(new Destination(k.code(), fr.valcombe.mc.StructureWorkshop.NAME, k.code(), k.label(), 0, false));
		}
		return out;
	}

	private static Destination destination(WorldEntry e, Map<String, Integer> present, String here) {
		return new Destination(e.id(), e.nom(), e.sorte().code(), e.sorte().label(), present.getOrDefault(e.id(), 0), e.id().equals(here));
	}

	/**
	 * Le menu « Mondes » de ce joueur : les destinations, où il est, et pourquoi il ne peut pas partir (ou null). Pour un
	 * atelier, chaque destination dit aussi si ce joueur y construit ({@code construire}) et, pour un opérateur, qui y
	 * est invité ({@code invites}).
	 */
	public Map<String, Object> view(ServerPlayer p) {
		Map<String, Object> m = new LinkedHashMap<>();
		BuildAccess access = BuildAccess.current();
		boolean op = operator(p);
		m.put("liste", destinations(p).stream().map(d -> {
			Map<String, Object> v = d.view();
			WorldEntry e = worlds.find(d.id());
			if (access != null && e != null && e.sorte().workshop()) {
				v.put("construire", op || access.invited(p, e));
				v.put("invite", !op && access.invited(p, e));
				if (op) {
					v.put("invites", access.invitations().guests(e.id()));
				}
			}
			return v;
		}).toList());
		m.put("ici", keyOf(p));
		m.put("bloque", blocked(p));
		return m;
	}

	/**
	 * La clé de destination désignée par ce texte : un identifiant de monde, {@code principal} (ou {@code village},
	 * {@code partie}) pour le monde de la partie, {@code hub} pour le hub. Une {@link GameException} si rien ne
	 * correspond.
	 */
	public String resolve(String text) {
		String t = text == null ? "" : text.strip().toLowerCase(java.util.Locale.ROOT);
		WorldEntry e = worlds.find(t);
		if (e != null) {
			return e.id();
		}
		if (t.equals(MAIN) || t.equals("village") || t.equals("partie")) {
			String game = game();
			if (game != null) {
				return game;
			}
			throw new GameException("La partie n'a pas de monde sur ce serveur (voir son journal).");
		}
		if (t.equals("hub") || t.equals("accueil")) {
			WorldEntry hub = hub();
			if (hub != null) {
				return hub.id();
			}
			throw new GameException("Le hub n'est pas disponible sur ce serveur.");
		}
		return worlds.get(t).id(); // message « Aucun monde ... »
	}

	// ------------------------------------------------------------------
	// Positions
	// ------------------------------------------------------------------

	/**
	 * La position à garder pour ce joueur là où il est. Dans l'ouverture d'une porte du hub, c'est devant la porte :
	 * revenir dedans le renverrait aussitôt de l'autre côté.
	 */
	private LastPositions.Spot spotOf(ServerPlayer p) {
		WorldEntry hub = hub();
		if (hub != null && hub.id().equals(keyOf(p))) {
			HubHall.Portal portal = HubHall.portalAt(p.blockPosition());
			if (portal != null) {
				Vec3 f = portal.front();
				return new LastPositions.Spot(f.x, f.y, f.z, portal.frontYaw(), 0f);
			}
		}
		return new LastPositions.Spot(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
	}

	/** Note la position de ce joueur dans le monde où il est (en mémoire ; écrite au prochain enregistrement). */
	public void remember(ServerPlayer p) {
		String k = keyOf(p);
		if (k != null && !p.isDeadOrDying() && !worlds.busy(k)) {
			positions.put(p.getUUID(), p.getScoreboardName(), k, spotOf(p));
		}
	}

	/** Un joueur tient-il debout à cette position, sans rien dans les jambes ni dans la tête ? */
	private static boolean free(ServerPlayer p, ServerLevel level, LastPositions.Spot s) {
		if (s.y() < level.getMinY() || s.y() > level.getMaxY()) {
			return false;
		}
		AABB box = p.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(s.x(), s.y(), s.z());
		return level.noCollision(p, box);
	}

	/**
	 * Une position gardée dans le monde de la partie vaut-elle encore ? Sa carte peut avoir été remplacée (ailleurs,
	 * ou plus petite) : une position hors du village en service renverrait le joueur dans un champ vide, loin de la
	 * partie. Il arrive alors au point d'arrivée du village. Les mondes oublient leurs positions quand ils sont remis
	 * à neuf ({@link #recreated}).
	 */
	private boolean nearVillage(String key, LastPositions.Spot s) {
		McHost host = hostOf(key);
		fr.valcombe.mc.Village v = host == null ? null : host.village();
		if (v == null) {
			return true;
		}
		BlockPos c = v.center();
		int reach = v.extent() + 8;
		return Math.abs(s.x() - c.getX()) <= reach && Math.abs(s.z() - c.getZ()) <= reach;
	}

	/** Le point d'arrivée d'une destination, pour une première visite (ou une position gardée devenue inutilisable). */
	private LastPositions.Spot arrival(ServerPlayer p, String key) {
		McHost host = hostOf(key);
		if (host != null) {
			BlockPos s = host.villageSpawn();
			if (s != null) {
				return new LastPositions.Spot(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 180f, 0f);
			}
		}
		WorldEntry e = worlds.find(key);
		if (e != null && e.sorte() == WorldKind.HUB) {
			return new LastPositions.Spot(HubHall.ARRIVAL.x, HubHall.ARRIVAL.y, HubHall.ARRIVAL.z, HubHall.ARRIVAL_YAW, 0f);
		}
		Vec3 at = WorldManager.spawn();
		return new LastPositions.Spot(at.x, at.y, at.z, p.getYRot(), 0f);
	}

	private static void teleport(ServerPlayer p, ServerLevel level, LastPositions.Spot s) {
		if (p.isPassenger()) {
			p.stopRiding(); // un siège de l'autre monde ne suit pas
		}
		p.teleportTo(level, s.x(), s.y(), s.z(), Set.of(), s.yaw(), s.pitch(), false);
	}

	// ------------------------------------------------------------------
	// Voyages
	// ------------------------------------------------------------------

	/** Envoie ce joueur vers la destination écrite (commande, menu) ; renvoie le message à lui dire. */
	public String go(ServerPlayer p, String text) {
		return go(p, resolve(text), null);
	}

	/**
	 * Envoie ce joueur vers cette destination (clé résolue), à sa dernière position s'il en a une utilisable.
	 *
	 * @param leaveAt où le noter dans le monde qu'il quitte, au lieu de sa position actuelle (devant une porte du
	 *                hub, pour ne pas revenir dedans) ; null : là où il est
	 * @return le message à lui dire
	 */
	public String go(ServerPlayer p, String key, LastPositions.Spot leaveAt) {
		WorldEntry target = worlds.get(key);
		if (!canSee(p, target)) {
			throw new GameException("« " + target.nom() + " » est réservé aux opérateurs"
				+ (target.sorte().workshop() ? " et à leurs invités : un opérateur peut t'y inviter (/vc atelier inviter)." : "."));
		}
		String from = keyOf(p);
		if (key.equals(from)) {
			throw new GameException("Tu es déjà dans « " + name(key) + " ».");
		}
		String why = blocked(p);
		if (why != null) {
			throw new GameException(why);
		}
		GameRoom room = Games.byWorld(key);
		if (room != null && room.suspended() && !room.isLeader(p)) {
			throw new GameException("La partie « " + target.nom() + " » est en suspens : elle reprendra où elle en est, et "
				+ "ses joueurs seront rappelés.");
		}
		ServerLevel level = worlds.level(key); // ouvre le monde au besoin ; refuse un monde en cours de remise à neuf
		if (from != null && !worlds.busy(from)) {
			positions.put(p.getUUID(), p.getScoreboardName(), from, leaveAt != null ? leaveAt : spotOf(p));
		}
		LastPositions.Spot saved = positions.get(p.getUUID(), key);
		LastPositions.Spot to = saved != null && free(p, level, saved) && nearVillage(key, saved) ? saved : arrival(p, key);
		teleport(p, level, to);
		positions.save();
		if (key.equals(game())) {
			return "Te voilà au village de la partie (« " + target.nom() + " »).";
		}
		if (Games.byWorld(key) != null) {
			return "Te voilà au village de la partie d'essai « " + target.nom() + " ».";
		}
		return target.sorte() == WorldKind.HUB ? "Te voilà au hub." : "Te voilà dans « " + target.nom() + " » (" + target.sorte().label() + ").";
	}

	/**
	 * Amène ce joueur au village de la partie (il s'inscrit, il devient Conteur) ; renvoie le message à lui dire. Une
	 * {@link GameException} si la partie n'a pas de monde, ou s'il ne peut pas quitter le sien.
	 */
	public String toGame(ServerPlayer p) {
		return toGame(p, game());
	}

	/** Amène ce joueur au village de la partie de ce monde (celle qu'il rejoint) ; renvoie le message à lui dire. */
	public String toGame(ServerPlayer p, String worldId) {
		if (worldId == null || worlds.find(worldId) == null) {
			throw new GameException("La partie n'a pas de monde sur ce serveur (voir son journal).");
		}
		return go(p, worldId, null);
	}

	/**
	 * Envoie ce joueur au hub, d'où qu'il soit et même s'il est assis à une partie (c'est elle qui l'y envoie : mise en
	 * suspens) : sa position dans le monde qu'il quitte est gardée, il retrouve au hub sa dernière position (sinon le point
	 * d'arrivée). Renvoie le message à lui dire ; une {@link GameException} si le hub n'est pas disponible.
	 */
	public String toHub(ServerPlayer p) {
		WorldEntry hub = hub();
		if (hub == null || worlds.busy(hub.id())) {
			throw new GameException("Le hub n'est pas disponible sur ce serveur.");
		}
		String from = keyOf(p);
		if (hub.id().equals(from)) {
			return "Te voilà au hub.";
		}
		ServerLevel level = worlds.level(hub.id());
		if (from != null && !worlds.busy(from)) {
			positions.put(p.getUUID(), p.getScoreboardName(), from, spotOf(p));
		}
		LastPositions.Spot saved = positions.get(p.getUUID(), hub.id());
		teleport(p, level, saved != null && free(p, level, saved) ? saved : arrival(p, hub.id()));
		positions.save();
		return "Te voilà au hub.";
	}

	/** Envoie ce joueur au point d'arrivée du hub (connexion) ; faux si le hub n'est pas disponible. */
	public boolean toHubArrival(ServerPlayer p) {
		WorldEntry hub = hub();
		if (hub == null || worlds.busy(hub.id())) {
			return false;
		}
		try {
			teleport(p, worlds.level(hub.id()), arrival(p, hub.id()));
			return true;
		} catch (RuntimeException e) {
			fr.valcombe.mc.ValcombeMod.LOG.error("Arrivée au hub impossible pour " + p.getScoreboardName(), e);
			return false;
		}
	}

	/**
	 * Repli du gestionnaire de mondes : au hub ; pour les joueurs du hub lui-même, au village de la partie. Si ni
	 * l'un ni l'autre n'est disponible, le gestionnaire se rabat sur le monde principal (dernier recours).
	 */
	@Override
	public void evacuate(ServerPlayer p, WorldEntry from) {
		if (from.sorte() != WorldKind.HUB && toHubArrival(p)) {
			return;
		}
		String game = game();
		if (game != null && !game.equals(from.id()) && !worlds.busy(game)) {
			teleport(p, worlds.level(game), arrival(p, game));
		}
	}

	/** Un monde remis à neuf : les positions qu'on y gardait ne veulent plus rien dire. */
	@Override
	public void recreated(WorldEntry entry, ServerLevel level) {
		positions.forgetWorld(entry.id());
		positions.save();
	}

	@Override
	public void deleted(WorldEntry entry) {
		positions.forgetWorld(entry.id());
		positions.save();
	}

	/**
	 * Après une mort dans un monde de Valcombe : le joueur réapparaît dans ce monde (à son point d'arrivée), et non
	 * au point d'apparition du monde principal.
	 */
	public void afterRespawn(ServerPlayer before, ServerPlayer after) {
		String k = keyOf(before);
		if (k == null || worlds.find(k) == null || worlds.busy(k)) {
			return;
		}
		try {
			teleport(after, worlds.level(k), arrival(after, k));
		} catch (RuntimeException e) {
			fr.valcombe.mc.ValcombeMod.LOG.warn("Retour dans le monde " + k + " après une mort impossible", e);
		}
	}

	/** Un joueur s'en va : sa position est notée et écrite. */
	public void onDisconnect(ServerPlayer p) {
		remember(p);
		positions.save();
	}

	/** À chaque tick : les positions sont notées chaque seconde, et écrites chaque minute s'il y a du nouveau. */
	public void tick() {
		tick++;
		if (tick % 20 == 0) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				remember(p);
			}
		}
		if (tick % 1200 == 0) {
			positions.save();
		}
	}

	/** À l'arrêt du serveur : les positions de tous les joueurs encore là sont notées et écrites. */
	public void stop() {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			remember(p);
		}
		positions.save();
	}
}
