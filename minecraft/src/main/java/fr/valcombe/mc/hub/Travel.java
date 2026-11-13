package fr.valcombe.mc.hub;

import fr.valcombe.mc.McHost;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.session.Session;

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
 * Les voyages entre les mondes de Valcombe : le hub, le village actuel (monde principal) et les mondes du
 * {@link WorldManager}.
 * <ul>
 * <li>Chaque joueur retrouve sa dernière position et son orientation dans chaque monde ({@link LastPositions},
 * gardées d'un démarrage à l'autre). La première fois, il arrive au point d'arrivée du monde : la salle
 * d'accueil au hub, la salle d'attente (ou la place) au village, le centre ailleurs.</li>
 * <li>À la connexion, tout le monde arrive au hub, sauf un joueur que la partie en cours retient au village.</li>
 * <li>Les opérateurs voient tous les mondes ; les autres joueurs le hub, le village actuel et les mondes de partie
 * ({@link WorldKind#openToAll()}).</li>
 * <li>Un joueur assis à la partie en cours ne quitte pas le village avant la fin.</li>
 * <li>C'est aussi le repli du gestionnaire de mondes : les joueurs d'un monde remis à neuf ou supprimé vont au hub
 * (ceux du hub lui-même, au village).</li>
 * </ul>
 * Tout se fait sur le fil du serveur.
 */
public final class Travel implements WorldManager.Fallback, WorldManager.Listener {
	/** La destination « village actuel » : le monde principal, où se joue la partie en attendant les mondes de partie. */
	public static final String MAIN = LastPositions.MAIN;
	/** Le nom affiché du monde principal. */
	public static final String MAIN_NAME = "Village actuel";

	/**
	 * Une destination telle que la montrent le menu « Mondes » et les portes du hub.
	 *
	 * @param id      {@link #MAIN} ou l'identifiant du monde de Valcombe
	 * @param nom     nom affiché
	 * @param sorte   {@code village} pour le monde principal, sinon le code de la sorte ({@code hub}, {@code partie}...)
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
	private final McHost host;
	private final Session session;
	private int tick;

	public Travel(MinecraftServer server, WorldManager worlds, LastPositions positions, McHost host, Session session) {
		this.server = server;
		this.worlds = worlds;
		this.positions = positions;
		this.host = host;
		this.session = session;
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

	/** La clé du monde où se trouve ce niveau : {@link #MAIN}, l'identifiant d'un monde de Valcombe, ou null (Nether, End). */
	public String keyOf(Level level) {
		if (level.dimension() == Level.OVERWORLD) {
			return MAIN;
		}
		WorldEntry e = worlds.entryOf(level);
		return e == null ? null : e.id();
	}

	public String keyOf(ServerPlayer p) {
		return keyOf(p.level());
	}

	private static boolean operator(ServerPlayer p) {
		return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	/** Ce joueur peut-il voir ce monde (et y aller) ? Les opérateurs, tous ; les autres, le hub et les mondes de partie. */
	public boolean canSee(ServerPlayer p, WorldEntry e) {
		return e != null && (operator(p) || e.sorte().openToAll());
	}

	/** Pourquoi ce joueur ne peut pas quitter le monde où il est, ou null s'il le peut. */
	public String blocked(ServerPlayer p) {
		Game g = session.game();
		if (g != null && g.winner() == null && g.seatOf(p.getScoreboardName()) >= 0 && MAIN.equals(keyOf(p))) {
			return "Tu joues la partie en cours : tu restes au village jusqu'à la fin.";
		}
		return null;
	}

	/** Le nom affiché d'une destination. */
	public String name(String key) {
		if (MAIN.equals(key)) {
			return MAIN_NAME;
		}
		WorldEntry e = worlds.find(key);
		return e == null ? key : e.nom();
	}

	/**
	 * Les destinations que ce joueur voit, dans l'ordre du menu : le hub, le village actuel, puis les autres mondes
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
		out.add(new Destination(MAIN, MAIN_NAME, "village", "partie en cours au village", present.getOrDefault(MAIN, 0), MAIN.equals(here)));
		for (WorldEntry e : worlds.list()) {
			if (e.sorte() != WorldKind.HUB && canSee(p, e)) {
				out.add(destination(e, present, here));
			}
		}
		return out;
	}

	private static Destination destination(WorldEntry e, Map<String, Integer> present, String here) {
		return new Destination(e.id(), e.nom(), e.sorte().code(), e.sorte().label(), present.getOrDefault(e.id(), 0), e.id().equals(here));
	}

	/** Le menu « Mondes » de ce joueur : les destinations, où il est, et pourquoi il ne peut pas partir (ou null). */
	public Map<String, Object> view(ServerPlayer p) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("liste", destinations(p).stream().map(Destination::view).toList());
		m.put("ici", keyOf(p));
		m.put("bloque", blocked(p));
		return m;
	}

	/**
	 * La clé de destination désignée par ce texte : un identifiant de monde, {@code principal} (ou {@code village})
	 * pour le village actuel, {@code hub} pour le hub. Une {@link GameException} si rien ne correspond.
	 */
	public String resolve(String text) {
		String t = text == null ? "" : text.strip().toLowerCase(java.util.Locale.ROOT);
		if (t.equals(MAIN) || t.equals("village")) {
			return MAIN;
		}
		WorldEntry e = worlds.find(t);
		if (e != null) {
			return e.id();
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

	/** Le monde Minecraft d'une destination (ouvert si besoin). */
	private ServerLevel level(String key) {
		return MAIN.equals(key) ? server.overworld() : worlds.level(key);
	}

	/** Un joueur tient-il debout à cette position, sans rien dans les jambes ni dans la tête ? */
	private static boolean free(ServerPlayer p, ServerLevel level, LastPositions.Spot s) {
		if (s.y() < level.getMinY() || s.y() > level.getMaxY()) {
			return false;
		}
		AABB box = p.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(s.x(), s.y(), s.z());
		return level.noCollision(p, box);
	}

	/** Le point d'arrivée d'une destination, pour une première visite (ou une position gardée devenue inutilisable). */
	private LastPositions.Spot arrival(ServerPlayer p, String key) {
		if (MAIN.equals(key)) {
			BlockPos s = host.villageSpawn();
			if (s == null) {
				var spawn = server.getRespawnData();
				s = p.adjustSpawnLocation(server.overworld(), spawn.pos());
				return new LastPositions.Spot(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, spawn.yaw(), spawn.pitch());
			}
			return new LastPositions.Spot(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 180f, 0f);
		}
		WorldEntry e = worlds.find(key);
		if (e != null && e.sorte() == WorldKind.HUB) {
			return new LastPositions.Spot(HubHall.ARRIVAL.x, HubHall.ARRIVAL.y, HubHall.ARRIVAL.z, HubHall.ARRIVAL_YAW, 0f);
		}
		Vec3 at = WorldManager.spawn();
		return new LastPositions.Spot(at.x, at.y, at.z, p.getYRot(), 0f);
	}

	private static void teleport(ServerPlayer p, ServerLevel level, LastPositions.Spot s) {
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
		WorldEntry target = MAIN.equals(key) ? null : worlds.get(key);
		if (target != null && !canSee(p, target)) {
			throw new GameException("« " + target.nom() + " » est réservé aux opérateurs.");
		}
		String from = keyOf(p);
		if (key.equals(from)) {
			throw new GameException("Tu es déjà dans « " + name(key) + " ».");
		}
		String why = blocked(p);
		if (why != null) {
			throw new GameException(why);
		}
		ServerLevel level = level(key); // ouvre le monde au besoin ; refuse un monde en cours de remise à neuf
		if (from != null && !worlds.busy(from)) {
			positions.put(p.getUUID(), p.getScoreboardName(), from, leaveAt != null ? leaveAt : spotOf(p));
		}
		LastPositions.Spot saved = positions.get(p.getUUID(), key);
		LastPositions.Spot to = saved != null && free(p, level, saved) ? saved : arrival(p, key);
		teleport(p, level, to);
		positions.save();
		if (MAIN.equals(key)) {
			return "Te voilà au village actuel.";
		}
		return target.sorte() == WorldKind.HUB ? "Te voilà au hub." : "Te voilà dans « " + target.nom() + " » (" + target.sorte().label() + ").";
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

	/** Repli du gestionnaire de mondes : au hub, ou au village pour les joueurs du hub lui-même. */
	@Override
	public void evacuate(ServerPlayer p, WorldEntry from) {
		if (from.sorte() != WorldKind.HUB && toHubArrival(p)) {
			return;
		}
		teleport(p, server.overworld(), arrival(p, MAIN));
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
		if (k == null || MAIN.equals(k) || worlds.find(k) == null || worlds.busy(k)) {
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
