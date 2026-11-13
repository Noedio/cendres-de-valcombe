package fr.valcombe.mc.worlds;

import fr.valcombe.rules.GameException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RemoveFromRegistry;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;

/**
 * Gestionnaire des mondes de Valcombe : hub, parties, parties d'essai, ateliers. C'est la base des voyages
 * entre mondes, des parties par monde et des ateliers.
 * <p>
 * Chaque monde a une fiche dans le {@linkplain WorldRegistry registre} ({@code config/valcombe/mondes.json})
 * et un monde Minecraft persistant {@code valcombe:<id>}, créé et ouvert par la bibliothèque Fantasy pendant
 * que le serveur tourne. Ses fichiers sont dans le dossier de la sauvegarde :
 * {@code <monde>/dimensions/valcombe/<id>}.
 * <p>
 * <b>Tous les mondes sont plats</b> : bedrock, pierre, terre, et une couche d'herbe dont le dessus est à
 * {@link #SURFACE_Y}. Ils sont paisibles (difficulté paisible, aucune créature ne naît), sans dégâts du décor ni
 * cycle jour/nuit naturel : ils commencent à midi et l'heure n'avance que si le jeu la change. Dans les ateliers,
 * l'horloge est arrêtée à midi et il ne pleut jamais. Les règles de jeu (gamerules) sont propres à chaque monde ;
 * elles sont remises à leur valeur de départ à chaque ouverture.
 * <p>
 * <b>Fil d'exécution</b> : tout ce qui touche aux mondes (créer, ouvrir, recréer, supprimer, téléporter) se fait
 * sur le fil du serveur. Les lectures du registre ({@link #list()}, {@link #find(String)}...) se font de partout.
 * <p>
 * <b>Recréer et supprimer</b> prennent quelques ticks : les joueurs sont aussitôt sortis du monde par le
 * {@linkplain #setFallback monde de repli} ; au tick suivant, le monde est retiré du serveur (il ne tourne plus), ses
 * tronçons sont déchargés comme à l'arrêt du serveur, puis il est fermé et ses fichiers effacés. On n'attend pas, comme
 * le ferait Fantasy, que le serveur décharge de lui-même tous les tronçons : un monde où un joueur vient d'arriver
 * en garde parfois pendant plus d'une minute. Le résultat arrive par un {@link CompletableFuture} complété sur le fil
 * du serveur. Si le serveur s'arrête entre-temps, l'effacement est terminé au démarrage suivant.
 */
public final class WorldManager {
	private static final Logger LOG = LoggerFactory.getLogger("valcombe");

	/** Une couche du sol plat, de bas en haut. */
	public record Layer(Block block, int height) {
	}

	/** Le sol de tous les mondes de Valcombe, du bas (y = 0) vers le haut. */
	public static final List<Layer> LAYERS = List.of(
		new Layer(Blocks.BEDROCK, 1),
		new Layer(Blocks.STONE, 60),
		new Layer(Blocks.DIRT, 3),
		new Layer(Blocks.GRASS_BLOCK, 1));
	/** Plus bas niveau des mondes de Valcombe (type de dimension par défaut de Fantasy : de 0 à 255). */
	public static final int MIN_Y = 0;
	/** Hauteur du sol plat : le dernier bloc d'herbe. On marche sur {@code SURFACE_Y + 1}. */
	public static final int SURFACE_Y = 64;
	/** Midi sur l'horloge d'un monde. */
	public static final int NOON = 6000;

	static {
		int top = MIN_Y + LAYERS.stream().mapToInt(Layer::height).sum() - 1;
		if (top != SURFACE_Y) {
			throw new IllegalStateException("Couches du sol plat : le dessus est à " + top + ", pas à " + SURFACE_Y);
		}
	}

	/**
	 * Où envoyer un joueur qui se trouve dans un monde sur le point d'être recréé ou supprimé. Par défaut le monde
	 * principal ({@link #MAIN_WORLD}) ; le hub prend sa place au démarrage : {@link #setFallback(Fallback)}.
	 */
	@FunctionalInterface
	public interface Fallback {
		/** Sort ce joueur du monde {@code from} (il doit se retrouver dans un autre monde). */
		void evacuate(ServerPlayer player, WorldEntry from);
	}

	/** Repli par défaut : le point d'apparition du monde principal (la place du village quand une carte est posée). */
	public static final Fallback MAIN_WORLD = (player, from) -> toMainWorld(player);

	/**
	 * Au plus combien de ticks (5 s) on laisse les tronçons d'un monde retiré du serveur se décharger avant de le
	 * fermer quand même : la fermeture enregistre et libère tout ce qui reste, elle bloque seulement un peu plus longtemps.
	 */
	static final int DRAIN_TICKS = 100;
	/** Au plus combien de tâches de tronçons on traite par tick pour un monde retiré du serveur. */
	private static final int DRAIN_TASKS = 10_000;

	/**
	 * Ce qui veut savoir qu'un monde a été remis à neuf ou supprimé (fil du serveur, une fois l'opération
	 * terminée) : le hub y rebâtit sa salle d'accueil, les voyages y oublient les positions gardées dans ce monde.
	 */
	public interface Listener {
		/**
		 * Ce monde vient d'être créé, plat et vide, et ouvert ({@link #create}) : la liste des parties y ouvre une partie
		 * quand c'est un monde de partie (voir fr.valcombe.mc.games.Games).
		 */
		default void created(WorldEntry entry, ServerLevel level) {
		}

		/** Ce monde vient d'être recréé, plat et vide ; {@code level} est le nouveau monde, déjà ouvert. */
		default void recreated(WorldEntry entry, ServerLevel level) {
		}

		/** Ce monde vient d'être supprimé, fichiers compris. */
		default void deleted(WorldEntry entry) {
		}
	}

	/**
	 * Ce qui peut refuser qu'un monde soit remis à neuf ou supprimé : la partie qui s'y joue (voir McHost). Appelé sur
	 * le fil du serveur, avant que rien ne bouge.
	 */
	@FunctionalInterface
	public interface Guard {
		/** Pourquoi ce monde ne peut pas être recréé ({@code recreate}) ou supprimé maintenant, ou null s'il le peut. */
		String refuse(WorldEntry entry, boolean recreate);
	}

	/**
	 * Identifiant du monde de la partie principale : le monde de partie que le serveur crée au premier démarrage, où
	 * se joue la partie du serveur (voir {@link #ensureMainGame()}).
	 */
	public static final String MAIN_GAME_ID = WorldRegistry.MAIN_GAME_ID;
	/** Nom donné au monde de la partie principale quand il est créé (on peut le renommer ; son identifiant reste). */
	public static final String MAIN_GAME_NAME = WorldRegistry.MAIN_GAME_NAME;

	/** Le monde de la partie principale, et s'il vient d'être créé (il est alors plat et vide). */
	public record MainGame(WorldEntry entry, boolean created) {
	}

	private final MinecraftServer server;
	private final WorldRegistry registry;
	/** Recréations et suppressions en cours, par identifiant. */
	private final Map<String, Pending> pending = new LinkedHashMap<>();
	private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
	private final List<Guard> guards = new java.util.concurrent.CopyOnWriteArrayList<>();
	private volatile Fallback fallback = MAIN_WORLD;

	/**
	 * Une recréation ou une suppression en cours : l'ancien monde est retiré du serveur au premier tick, ses
	 * tronçons déchargés pendant les suivants, puis il est fermé et ses fichiers effacés.
	 */
	private static final class Pending {
		final WorldEntry entry;
		final ServerLevel old;
		final boolean recreate;
		final CompletableFuture<ServerLevel> done = new CompletableFuture<>();
		int ticks;
		/** Retiré de la table des mondes du serveur (le serveur ne le fait plus tourner). */
		boolean detached;
		boolean closed;
		int deleteTries;

		Pending(WorldEntry entry, ServerLevel old, boolean recreate) {
			this.entry = entry;
			this.old = old;
			this.recreate = recreate;
		}
	}

	/** Gestionnaire des mondes de ce serveur, dont le registre est dans ce fichier. Rien n'est ouvert avant {@link #start()}. */
	public WorldManager(MinecraftServer server, Path registryFile) {
		this.server = server;
		this.registry = new WorldRegistry(registryFile);
	}

	/** Le registre de ce serveur : {@code config/valcombe/mondes.json} dans le dossier du serveur. */
	public static Path defaultFile(MinecraftServer server) {
		return server.getServerDirectory().resolve("config").resolve("valcombe").resolve("mondes.json");
	}

	/** Le registre des fiches (lecture seule pour les autres parties du mod : passer par ce gestionnaire pour modifier). */
	public WorldRegistry registry() {
		return registry;
	}

	private void requireServerThread() {
		if (!server.isSameThread()) {
			throw new IllegalStateException("Les mondes de Valcombe se gèrent sur le fil du serveur.");
		}
	}

	// ------------------------------------------------------------------
	// Démarrage et arrêt
	// ------------------------------------------------------------------

	/**
	 * Au démarrage du serveur : relit le registre, termine les effacements interrompus par un arrêt, puis rouvre
	 * tous les mondes enregistrés (Fantasy ne rouvre pas seul un monde persistant). Un monde qui ne s'ouvre pas est
	 * signalé dans le journal sans empêcher les autres.
	 */
	public void start() {
		requireServerThread();
		registry.load();
		for (String id : registry.wipes()) {
			if (server.getLevel(key(id)) == null && deleteFolder(id)) {
				registry.clearWipe(id);
			} else {
				LOG.warn("Fichiers du monde {} toujours pas effacés : nouvel essai au prochain démarrage.", id);
			}
		}
		int opened = 0;
		for (WorldEntry e : registry.list()) {
			if (registry.wipes().contains(e.id())) {
				continue; // ses anciens fichiers sont toujours là : il s'ouvrira à la demande, une fois effacés
			}
			try {
				open(e);
				opened++;
			} catch (RuntimeException ex) {
				LOG.error("Monde de Valcombe « " + e.id() + " » impossible à ouvrir", ex);
			}
		}
		if (opened > 0) {
			LOG.info("Mondes de Valcombe : {} ouvert(s).", opened);
		}
	}

	/**
	 * À l'arrêt du serveur : les mondes ouverts sont enregistrés par le serveur lui-même. Une recréation ou une
	 * suppression en cours échoue ici ; ses fichiers seront effacés au démarrage suivant. Un ancien monde déjà retiré
	 * du serveur mais pas encore fermé l'est ici : le serveur ne le fermera plus, et ses fichiers restés ouverts (en
	 * solo, Java continue de tourner) empêcheraient leur effacement au prochain lancement du monde.
	 */
	public void stop() {
		for (Pending p : pending.values()) {
			if (p.old != null && !p.closed && p.detached) {
				p.closed = true;
				try {
					p.old.close();
				} catch (IOException | RuntimeException ex) {
					LOG.warn("Fermeture de l'ancien monde " + p.entry.id() + " incomplète", ex);
				}
			}
			p.done.completeExceptionally(new GameException("Le serveur s'arrête : le monde « " + p.entry.nom()
				+ " » sera remis à neuf au prochain démarrage."));
		}
		pending.clear();
	}

	// ------------------------------------------------------------------
	// Lecture
	// ------------------------------------------------------------------

	/** Toutes les fiches, hub d'abord (voir {@link WorldRegistry#list()}). */
	public List<WorldEntry> list() {
		return registry.list();
	}

	/** La fiche de ce monde, ou null. */
	public WorldEntry find(String id) {
		return registry.find(id);
	}

	/** La fiche de ce monde ; une {@link GameException} (message pour le joueur) s'il n'existe pas. */
	public WorldEntry get(String id) {
		return registry.get(id);
	}

	/** La clé Minecraft du monde : {@code valcombe:<id>}. */
	public static ResourceKey<Level> key(String id) {
		return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(WorldRegistry.NAMESPACE, id));
	}

	/** La fiche du monde Valcombe où se trouve ce niveau, ou null (monde principal, Nether...). */
	public WorldEntry entryOf(Level level) {
		Identifier id = level.dimension().identifier();
		return WorldRegistry.NAMESPACE.equals(id.getNamespace()) ? registry.find(id.getPath()) : null;
	}

	/** Ce monde est-il ouvert sur le serveur en ce moment ? */
	public boolean isOpen(String id) {
		return server.getLevel(key(id)) != null && !pending.containsKey(id);
	}

	/** Une recréation ou une suppression est-elle en cours, dans un monde quel qu'il soit ? */
	public boolean working() {
		return !pending.isEmpty();
	}

	/** Une recréation ou une suppression de ce monde est-elle en cours ? */
	public boolean busy(String id) {
		return pending.containsKey(id);
	}

	/**
	 * Les mondes tels que l'API d'administration les montre (GET /admin/etat, champ « mondes »), dans l'ordre du
	 * registre (hub d'abord). Fil du serveur. État : « charge » (ouvert sur le serveur), « remise-a-neuf »
	 * (recréation ou suppression en cours) ou « decharge » (fermé : ses anciens fichiers attendent d'être effacés,
	 * il s'ouvrira à la demande). Les joueurs comptés sont ceux qui s'y trouvent en ce moment.
	 */
	public List<fr.valcombe.admin.WorldsView.World> adminView() {
		requireServerThread();
		Map<String, Integer> present = new HashMap<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			WorldEntry e = entryOf(p.level());
			if (e != null) {
				present.merge(e.id(), 1, Integer::sum);
			}
		}
		List<fr.valcombe.admin.WorldsView.World> out = new ArrayList<>();
		for (WorldEntry e : registry.list()) {
			String state = busy(e.id()) ? "remise-a-neuf" : isOpen(e.id()) ? "charge" : "decharge";
			out.add(new fr.valcombe.admin.WorldsView.World(e.id(), e.nom(), e.sorte().code(), state,
				present.getOrDefault(e.id(), 0)));
		}
		return out;
	}

	/** Le point d'apparition d'un monde de Valcombe : au centre, debout sur l'herbe. */
	public static Vec3 spawn() {
		return new Vec3(0.5, SURFACE_Y + 1, 0.5);
	}

	/** Le dossier des fichiers de ce monde dans la sauvegarde : {@code <monde>/dimensions/valcombe/<id>}. */
	public Path folder(String id) {
		return DimensionType.getStorageFolder(key(id), server.getWorldPath(LevelResource.ROOT)).normalize();
	}

	/**
	 * Le monde Minecraft de cette fiche, ouvert s'il ne l'est pas encore (fil du serveur). Une
	 * {@link GameException} si la fiche n'existe pas ou si le monde est en train d'être recréé ou supprimé.
	 */
	public ServerLevel level(String id) {
		requireServerThread();
		WorldEntry e = registry.get(id);
		if (pending.containsKey(e.id())) {
			throw new GameException("Le monde « " + e.nom() + " » est en cours de remise à neuf : réessayez dans un instant.");
		}
		ServerLevel level = server.getLevel(key(e.id()));
		if (level != null) {
			return level;
		}
		if (registry.wipes().contains(e.id())) {
			if (!deleteFolder(e.id())) {
				throw new GameException("Les anciens fichiers du monde « " + e.nom() + " » n'ont pas pu être effacés.");
			}
			registry.clearWipe(e.id());
		}
		return open(e);
	}

	// ------------------------------------------------------------------
	// Création et modification
	// ------------------------------------------------------------------

	/**
	 * Crée un monde neuf, plat et vide, et l'ouvre (fil du serveur). L'identifiant est tiré du nom
	 * ({@link WorldRegistry#slug}) et rendu unique. Il n'existe qu'un hub et qu'un atelier structures.
	 * <p>
	 * Un identifiant dont le dossier existe déjà sur le disque sans fiche (registre illisible mis de côté, fiche
	 * retirée à la main) n'est jamais repris : ce monde inconnu reste intact et le nouveau prend {@code nom-2}.
	 *
	 * @return la fiche enregistrée
	 */
	public WorldEntry create(WorldKind kind, String name) {
		requireServerThread();
		WorldEntry e = registry.create(kind, name, id -> Files.exists(folder(id)));
		try {
			open(e);
		} catch (RuntimeException ex) {
			registry.remove(e.id());
			if (ex instanceof GameException) {
				throw ex;
			}
			LOG.error("Création du monde « " + e.id() + " » impossible", ex);
			throw new GameException("Le monde n'a pas pu être créé : " + ex.getMessage());
		}
		LOG.info("Monde de Valcombe créé : {} ({}, « {} »).", e.id(), kind.label(), e.nom());
		ServerLevel level = server.getLevel(key(e.id()));
		for (Listener l : listeners) {
			try {
				l.created(e, level);
			} catch (RuntimeException ex) {
				LOG.error("Après la création du monde « " + e.id() + " »", ex);
			}
		}
		return e;
	}

	/**
	 * Le monde de la partie principale, créé (plat et vide) s'il n'existe pas : au démarrage, le jeu s'y lie. C'est le
	 * monde de partie {@value #MAIN_GAME_ID} ; si ce nom est pris par un dossier sans fiche (registre illisible mis de
	 * côté), le monde créé à sa place s'appelle {@code partie-principale-2} et il est retrouvé aux démarrages suivants
	 * (le premier monde de partie dont l'identifiant commence ainsi). Fil du serveur.
	 */
	public MainGame ensureMainGame() {
		requireServerThread();
		WorldEntry e = WorldRegistry.mainGame(registry.list());
		if (e != null) {
			return new MainGame(e, false);
		}
		WorldEntry created = create(WorldKind.PARTIE, MAIN_GAME_NAME);
		if (!created.id().equals(MAIN_GAME_ID)) {
			LOG.warn("Monde de la partie principale créé sous l'identifiant {} : un dossier {} sans fiche occupe déjà la place.",
				created.id(), MAIN_GAME_ID);
		}
		return new MainGame(created, true);
	}

	/** Ajoute un {@link Guard}, consulté avant chaque remise à neuf et chaque suppression. */
	public void addGuard(Guard guard) {
		guards.add(guard);
	}

	/** Retire un {@link Guard} (la partie d'un monde supprimé). */
	public void removeGuard(Guard guard) {
		guards.remove(guard);
	}

	/** Retire un {@link Listener} (la partie d'un monde supprimé). */
	public void removeListener(Listener listener) {
		listeners.remove(listener);
	}

	/** Change le nom affiché du monde ; son identifiant, son monde Minecraft et ses fichiers restent les mêmes. */
	public WorldEntry rename(String id, String name) {
		return registry.rename(id, name);
	}

	/** Note la carte de la bibliothèque que porte ce monde (null : aucune). Rien n'est posé dans le monde. */
	public WorldEntry setCarte(String id, String carte) {
		return registry.setCarte(id, carte);
	}

	/** Change l'état libre du monde. */
	public WorldEntry setEtat(String id, String etat) {
		return registry.setEtat(id, etat);
	}

	/**
	 * Remet ce monde à neuf : ses fichiers sont réellement effacés, puis il est recréé plat et vide, avec la
	 * même fiche (nom, sorte, carte et état gardés ; date de modification mise à jour). Les joueurs qui s'y
	 * trouvent sont d'abord envoyés au monde de repli. Fil du serveur.
	 *
	 * @return le nouveau monde, une fois prêt (complété sur le fil du serveur, quelques ticks plus tard)
	 */
	public CompletableFuture<ServerLevel> recreate(String id) {
		return remove(id, true);
	}

	/**
	 * Supprime ce monde : sa fiche disparaît aussitôt du registre, ses joueurs sont envoyés au monde de repli,
	 * puis le monde est fermé et ses fichiers effacés. Fil du serveur.
	 *
	 * @return complété (sur le fil du serveur) quand les fichiers sont effacés
	 */
	public CompletableFuture<Void> delete(String id) {
		WorldEntry e = registry.get(id);
		if (e.sorte() == WorldKind.HUB) {
			// Le hub est le point d'arrivée de tous les joueurs et le repli des autres mondes : il ne disparaît pas.
			throw new GameException("Le hub ne se supprime pas : /vc monde recreer " + e.id()
				+ " le remet à neuf, avec une salle d'accueil rebâtie.");
		}
		return remove(id, false).thenApply(level -> null);
	}

	private CompletableFuture<ServerLevel> remove(String id, boolean recreate) {
		requireServerThread();
		WorldEntry e = registry.get(id);
		if (pending.containsKey(e.id())) {
			throw new GameException("Le monde « " + e.nom() + " » est déjà en cours de remise à neuf ou de suppression.");
		}
		for (Guard g : guards) {
			String why = g.refuse(e, recreate);
			if (why != null) {
				throw new GameException(why);
			}
		}
		// Noté d'abord : un arrêt du serveur pendant l'opération n'empêchera pas l'effacement.
		registry.markWipe(e.id());
		if (!recreate) {
			registry.remove(e.id());
		}
		ServerLevel old = server.getLevel(key(e.id()));
		if (old == null) {
			// Monde fermé : rien à attendre.
			Pending p = new Pending(e, null, recreate);
			finish(p);
			return p.done;
		}
		// Les joueurs sortent tout de suite ; le monde est retiré du serveur au prochain tick (voir tick()). Ni
		// le déchargement de Fantasy (handle.unload), qui attend que le serveur ait déchargé de lui-même tous les
		// tronçons, ni sa suppression (handle.delete), qui efface le dossier d'un monde encore ouvert : sous Windows
		// les fichiers ouverts résistent, et Fantasy demande alors leur effacement à l'arrêt de Java, ce qui
		// emporterait les fichiers du monde recréé au même endroit.
		evacuate(old, e, recreate);
		Pending p = new Pending(e, old, recreate);
		pending.put(e.id(), p);
		return p.done;
	}

	/**
	 * À appeler à chaque tick du serveur, en fin de tick (hors de la boucle où le serveur fait tourner ses mondes) :
	 * avance les recréations et suppressions en cours.
	 */
	public void tick() {
		if (pending.isEmpty()) {
			return;
		}
		for (Pending p : new ArrayList<>(pending.values())) {
			p.ticks++;
			if (p.old != null && !p.closed) {
				if (!p.detached) {
					// Un joueur arrivé depuis par une autre voie (commande, portail) en sort lui aussi.
					evacuate(p.old, p.entry, p.recreate);
					p.detached = true; // une seule fois, même si une étape échoue : la suite ferme le monde quand même
					detach(p.old);
				}
				if (!drain(p)) {
					continue;
				}
			}
			finish(p);
		}
	}

	/**
	 * Retire ce monde du serveur, qui cesse aussitôt de le faire tourner, comme Fantasy le fait au déchargement :
	 * plus aucun ticket ne garde ses tronçons, il quitte la table des mondes et celle des dimensions, et les mods
	 * en sont avertis (événement de déchargement de Fabric). Il reste à décharger ses tronçons et à le fermer.
	 */
	private void detach(ServerLevel level) {
		// Comme à l'arrêt du serveur : les tickets (joueurs, chargements forcés, portails...) ne comptent plus.
		level.getChunkSource().deactivateTicketsOnClosing();
		if (!((fr.valcombe.mixin.MinecraftServerLevelsAccessor) server).valcombe$levels().remove(level.dimension(), level)) {
			return; // déjà retiré par une autre voie : plus rien à annoncer
		}
		try {
			ServerLevelEvents.UNLOAD.invoker().onLevelUnload(server, level);
		} catch (RuntimeException ex) {
			LOG.error("Déchargement du monde " + level.dimension().identifier() + " : un mod a échoué", ex);
		}
		// Retiré aussi des dimensions déclarées, comme au déchargement par Fantasy : une recréation l'y remet, et un
		// monde supprimé n'est plus enregistré dans level.dat.
		@SuppressWarnings("unchecked")
		MappedRegistry<LevelStem> stems = (MappedRegistry<LevelStem>) server.registries().compositeAccess()
			.lookupOrThrow(Registries.LEVEL_STEM);
		RemoveFromRegistry.remove(stems, level.dimension().identifier());
	}

	/**
	 * Fait avancer le déchargement des tronçons d'un monde retiré du serveur, comme le serveur le fait pour tous ses
	 * mondes à l'arrêt ; vrai quand il n'y a plus rien à faire, ou quand on a assez attendu ({@link #DRAIN_TICKS}).
	 */
	private boolean drain(Pending p) {
		ServerChunkCache chunks = p.old.getChunkSource();
		chunks.deactivateTicketsOnClosing();
		chunks.tick(() -> true, false);
		// Les tâches de tronçons destinées au fil du serveur (fin de chargement, de génération) : le serveur ne les
		// traite plus pour un monde qu'il ne connaît plus.
		for (int i = 0; i < DRAIN_TASKS && chunks.pollTask(); i++) {
			// rien d'autre : pollTask() exécute une tâche
		}
		if (!chunks.chunkMap.hasWork()) {
			return true;
		}
		if (p.ticks >= DRAIN_TICKS) {
			LOG.warn("Monde {} : tronçons toujours en cours de déchargement après {} ticks, fermé quand même ({} tronçons).",
				p.entry.id(), p.ticks, chunks.getLoadedChunksCount());
			return true;
		}
		return false;
	}

	/** Ferme l'ancien monde, efface ses fichiers, puis le recrée (ou non) ; complète le résultat. */
	private void finish(Pending p) {
		String id = p.entry.id();
		if (p.old != null && !p.closed) {
			p.closed = true;
			// La fermeture enregistre ce qui reste, attend les écritures en cours et ferme les fichiers du monde
			// (sinon impossibles à effacer sous Windows), comme à l'arrêt du serveur.
			try {
				p.old.close();
			} catch (IOException | RuntimeException ex) {
				LOG.warn("Fermeture de l'ancien monde " + id + " incomplète", ex);
			}
		}
		if (!deleteFolder(id)) {
			if (++p.deleteTries < 100) {
				pending.put(id, p); // nouvel essai au prochain tick
				return;
			}
			pending.remove(id);
			LOG.error("Fichiers du monde {} impossibles à effacer : nouvel essai au prochain démarrage.", id);
			p.done.completeExceptionally(new GameException("Les fichiers du monde « " + p.entry.nom()
				+ " » n'ont pas pu être effacés ; ils le seront au prochain démarrage du serveur."));
			return;
		}
		pending.remove(id);
		registry.clearWipe(id);
		if (!p.recreate) {
			LOG.info("Monde de Valcombe supprimé : {}.", id);
			for (Listener l : listeners) {
				try {
					l.deleted(p.entry);
				} catch (RuntimeException ex) {
					LOG.error("Suite de la suppression du monde " + id + " en échec", ex);
				}
			}
			p.done.complete(null);
			return;
		}
		try {
			WorldEntry e = registry.find(id) != null ? registry.touch(id) : p.entry;
			ServerLevel fresh = open(e);
			LOG.info("Monde de Valcombe recréé à neuf : {}.", id);
			for (Listener l : listeners) {
				try {
					l.recreated(e, fresh);
				} catch (RuntimeException ex) {
					LOG.error("Suite de la recréation du monde " + id + " en échec", ex);
				}
			}
			p.done.complete(fresh);
		} catch (RuntimeException ex) {
			LOG.error("Recréation du monde " + id + " impossible", ex);
			p.done.completeExceptionally(ex instanceof GameException ? ex
				: new GameException("Le monde n'a pas pu être recréé : " + ex.getMessage()));
		}
	}

	// ------------------------------------------------------------------
	// Joueurs
	// ------------------------------------------------------------------

	/**
	 * Remplace le monde de repli, où vont les joueurs d'un monde recréé ou supprimé. Le hub s'y branche au
	 * démarrage (voir {@code fr.valcombe.mc.hub.Travel}) ; null remet le monde principal.
	 */
	public void setFallback(Fallback fallback) {
		this.fallback = fallback == null ? MAIN_WORLD : fallback;
	}

	/** Ajoute un {@link Listener} prévenu des recréations et suppressions terminées. */
	public void addListener(Listener listener) {
		listeners.add(listener);
	}

	/**
	 * Envoie au monde de repli tous les joueurs de ce monde, avec un mot pour leur dire pourquoi. Un joueur que ni
	 * le repli ni le monde principal n'ont pu sortir est déconnecté : il ne doit pas rester dans un monde que le
	 * serveur ne fait plus tourner (à sa reconnexion, Minecraft le place dans le monde principal).
	 */
	private void evacuate(ServerLevel level, WorldEntry e, boolean recreate) {
		Component why = Component.literal("Le monde « " + e.nom() + " » " + (recreate ? "est remis à neuf" : "est supprimé")
			+ " : tu en as été sorti.");
		for (ServerPlayer p : new ArrayList<>(level.players())) {
			try {
				fallback.evacuate(p, e);
			} catch (RuntimeException ex) {
				LOG.error("Monde de repli en échec pour " + p.getScoreboardName(), ex);
			}
			if (p.level() == level) {
				try {
					toMainWorld(p); // le repli n'a rien fait : le monde principal, toujours là
				} catch (RuntimeException ex) {
					LOG.error("Retour au monde principal en échec pour " + p.getScoreboardName(), ex);
				}
			}
			if (p.level() == level) {
				LOG.warn("{} n'a pas pu sortir du monde {} : déconnecté.", p.getScoreboardName(), e.id());
				p.connection.disconnect(why);
				continue;
			}
			p.sendSystemMessage(why.copy().withStyle(ChatFormatting.GRAY));
		}
	}

	/**
	 * Téléporte ce joueur au point d'apparition du monde principal. Le monde principal ne sert plus au jeu : c'est le
	 * dernier recours d'un joueur qu'il faut sortir d'un monde remis à neuf quand ni le hub ni le monde de la partie ne
	 * peuvent l'accueillir (le seul usage légitime du monde principal, avec le passage d'un tick à la connexion).
	 */
	public static void toMainWorld(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		ServerLevel level = server.findRespawnDimension();
		LevelData.RespawnData spawn = server.getRespawnData();
		BlockPos pos = player.adjustSpawnLocation(level, spawn.pos());
		player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Set.of(), spawn.yaw(), spawn.pitch(), false);
	}

	/** Téléporte ce joueur au point d'apparition de ce monde (ouvert si besoin), au-dessus du sol plat. */
	public void teleport(ServerPlayer player, String id) {
		ServerLevel level = level(id);
		Vec3 at = spawn();
		player.teleportTo(level, at.x, at.y, at.z, Set.of(), player.getYRot(), player.getXRot(), false);
	}

	// ------------------------------------------------------------------
	// Fantasy et fichiers
	// ------------------------------------------------------------------

	/** Ouvre (ou crée) le monde Fantasy de cette fiche. */
	private ServerLevel open(WorldEntry e) {
		ServerLevel level = Fantasy.get(server).getOrOpenPersistentLevel(key(e.id()).identifier(), config(e.sorte())).asLevel();
		// Pas d'annonce de progrès Minecraft dans un monde de Valcombe, même si la règle a été changée à la main.
		fr.valcombe.mc.WorldSetup.quiet(level);
		return level;
	}

	/** Les règles de jeu d'un monde de Valcombe : paisible et sans dégâts, comme le monde de la partie. */
	private static final List<GameRule<Boolean>> RULES_OFF = List.of(
		GameRules.SPAWN_MOBS, GameRules.SPAWN_MONSTERS, GameRules.SPAWN_PATROLS, GameRules.SPAWN_PHANTOMS,
		GameRules.SPAWN_WANDERING_TRADERS, GameRules.SPAWN_WARDENS, GameRules.RAIDS,
		GameRules.ADVANCE_WEATHER,
		GameRules.FALL_DAMAGE, GameRules.FIRE_DAMAGE, GameRules.DROWNING_DAMAGE, GameRules.FREEZE_DAMAGE,
		GameRules.MOB_GRIEFING, GameRules.TNT_EXPLODES, GameRules.PVP,
		GameRules.SHOW_ADVANCEMENT_MESSAGES, GameRules.SHOW_DEATH_MESSAGES, GameRules.LOCATOR_BAR);

	/** La configuration Fantasy d'un monde de cette sorte : sol plat, paisible, midi, règles de Valcombe. */
	private RuntimeLevelConfig config(WorldKind kind) {
		HolderGetter<Biome> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
		var plains = biomes.getOrThrow(Biomes.PLAINS);
		List<FlatLayerInfo> layers = LAYERS.stream().map(l -> new FlatLayerInfo(l.height(), l.block())).toList();
		// Ni structures (villages, avant-postes), ni décorations, ni lacs : le sol nu.
		FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(Optional.of(HolderSet.empty()), plains, List.of())
			.withBiomeAndLayers(layers, Optional.of(HolderSet.empty()), plains);
		boolean workshop = kind.workshop();
		RuntimeLevelConfig config = new RuntimeLevelConfig()
			.setDimensionType(Fantasy.DEFAULT_DIM_TYPE)
			.setGenerator(new FlatLevelSource(flat))
			.setFlat(true)
			.setSeed(0L)
			.setDifficulty(Difficulty.PEACEFUL)
			// L'heure n'avance pas d'elle-même : le jeu la règle. Dans un atelier, l'horloge est même arrêtée à midi.
			.setShouldTickTime(false)
			.setClockTime(Fantasy.DEFAULT_CLOCK, NOON, workshop)
			.setLevelConstructor((s, key, c, style) -> new ValcombeLevel(s, key, c, style, !workshop));
		for (GameRule<Boolean> rule : RULES_OFF) {
			config.setGameRule(rule, false);
		}
		config.setGameRule(GameRules.KEEP_INVENTORY, true);
		config.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
		config.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
		return config;
	}

	/**
	 * Efface le dossier de ce monde s'il existe ; faux si un fichier résiste (encore ouvert, par exemple).
	 * Ne touche jamais qu'à un dossier de {@code dimensions/valcombe}.
	 */
	private boolean deleteFolder(String id) {
		if (id == null || !id.matches(WorldRegistry.ID)) {
			return false; // jamais de chemin fabriqué avec un identifiant libre
		}
		Path dir = folder(id);
		Path parent = dir.getParent();
		if (parent == null || !parent.getFileName().toString().equals(WorldRegistry.NAMESPACE)) {
			LOG.error("Dossier de monde inattendu, rien n'est effacé : {}", dir);
			return false;
		}
		if (!Files.exists(dir)) {
			return true;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(f);
			}
			return true;
		} catch (IOException | RuntimeException ex) {
			LOG.debug("Effacement du dossier " + dir + " incomplet", ex);
			return false;
		}
	}
}
