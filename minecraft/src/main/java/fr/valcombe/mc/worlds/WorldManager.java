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

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
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
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

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
 * <b>Recréer et supprimer</b> prennent quelques ticks : le monde est d'abord déchargé et retiré du serveur (ses
 * joueurs en sont sortis par le {@linkplain #setFallback monde de repli}), puis fermé et ses fichiers effacés. Le résultat
 * arrive par un {@link CompletableFuture} complété sur le fil du serveur. Si le serveur s'arrête entre-temps,
 * l'effacement est terminé au démarrage suivant.
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
	 * Où envoyer un joueur qui se trouve dans un monde sur le point d'être recréé ou supprimé. Le hub remplacera
	 * le monde principal ({@link #MAIN_WORLD}) : {@link #setFallback(Fallback)}.
	 */
	@FunctionalInterface
	public interface Fallback {
		/** Sort ce joueur du monde {@code from} (il doit se retrouver dans un autre monde). */
		void evacuate(ServerPlayer player, WorldEntry from);
	}

	/** Repli par défaut : le point d'apparition du monde principal (la place du village quand une carte est posée). */
	public static final Fallback MAIN_WORLD = (player, from) -> toMainWorld(player);

	private final MinecraftServer server;
	private final WorldRegistry registry;
	/** Poignées Fantasy des mondes ouverts, par identifiant. */
	private final Map<String, RuntimeLevelHandle> handles = new HashMap<>();
	/** Recréations et suppressions en cours, par identifiant. */
	private final Map<String, Pending> pending = new LinkedHashMap<>();
	private volatile Fallback fallback = MAIN_WORLD;

	/** Une recréation ou une suppression qui attend que Fantasy ait retiré l'ancien monde du serveur. */
	private static final class Pending {
		final WorldEntry entry;
		final ServerLevel old;
		final boolean recreate;
		final CompletableFuture<ServerLevel> done = new CompletableFuture<>();
		int ticks;
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
	 * suppression en cours échoue ici ; ses fichiers seront effacés au démarrage suivant.
	 */
	public void stop() {
		for (Pending p : pending.values()) {
			p.done.completeExceptionally(new GameException("Le serveur s'arrête : le monde « " + p.entry.nom()
				+ " » sera remis à neuf au prochain démarrage."));
		}
		pending.clear();
		handles.clear();
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

	/** Une recréation ou une suppression de ce monde est-elle en cours ? */
	public boolean busy(String id) {
		return pending.containsKey(id);
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
	 *
	 * @return la fiche enregistrée
	 */
	public WorldEntry create(WorldKind kind, String name) {
		requireServerThread();
		WorldEntry e = registry.create(kind, name);
		try {
			// Un dossier laissé par un monde du même nom, effacé hors du jeu : le nouveau monde doit partir de rien.
			if (!deleteFolder(e.id())) {
				throw new GameException("Des fichiers d'un ancien monde « " + e.id() + " » n'ont pas pu être effacés.");
			}
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
		return e;
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
		return remove(id, false).thenApply(level -> null);
	}

	private CompletableFuture<ServerLevel> remove(String id, boolean recreate) {
		requireServerThread();
		WorldEntry e = registry.get(id);
		if (pending.containsKey(e.id())) {
			throw new GameException("Le monde « " + e.nom() + " » est déjà en cours de remise à neuf ou de suppression.");
		}
		// Noté d'abord : un arrêt du serveur pendant l'opération n'empêchera pas l'effacement.
		registry.markWipe(e.id());
		if (!recreate) {
			registry.remove(e.id());
		}
		RuntimeLevelHandle handle = handles.remove(e.id());
		ServerLevel old = server.getLevel(key(e.id()));
		if (old == null) {
			// Monde fermé : rien à attendre.
			Pending p = new Pending(e, null, recreate);
			finish(p);
			return p.done;
		}
		evacuate(old, e, recreate);
		Pending p = new Pending(e, old, recreate);
		pending.put(e.id(), p);
		if (handle == null) {
			// Ouvert sans poignée (par une autre voie) : Fantasy redonne une poignée sur le monde déjà ouvert.
			handle = Fantasy.get(server).getOrOpenPersistentLevel(key(e.id()).identifier(), config(e.sorte()));
		}
		// Fantasy décharge le monde (le retire du serveur une fois ses tronçons déchargés), puis on le ferme et on
		// efface ses fichiers nous-mêmes. Sa suppression à lui (delete) efface le dossier d'un monde encore ouvert :
		// sous Windows, les fichiers ouverts résistent et Fantasy demande alors leur effacement à l'arrêt de Java,
		// ce qui emporterait les fichiers du monde recréé au même endroit.
		handle.unload();
		return p.done;
	}

	/**
	 * À appeler à chaque tick du serveur : avance les recréations et suppressions en cours, une fois que Fantasy
	 * a retiré l'ancien monde du serveur.
	 */
	public void tick() {
		if (pending.isEmpty()) {
			return;
		}
		for (Pending p : new ArrayList<>(pending.values())) {
			p.ticks++;
			if (p.old != null && server.getLevel(key(p.entry.id())) == p.old) {
				if (p.ticks % 600 == 0) {
					LOG.warn("Monde {} : toujours pas déchargé après {} ticks (tronçons gardés chargés ?).", p.entry.id(), p.ticks);
				}
				continue;
			}
			finish(p);
		}
	}

	/** Ferme l'ancien monde, efface ses fichiers, puis le recrée (ou non) ; complète le résultat. */
	private void finish(Pending p) {
		String id = p.entry.id();
		if (p.old != null && !p.closed) {
			p.closed = true;
			// Fantasy retire le monde sans le fermer : ses fichiers resteraient ouverts (et donc impossibles à
			// effacer sous Windows). On le ferme ici, une fois qu'il ne tourne plus.
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
			p.done.complete(null);
			return;
		}
		try {
			WorldEntry e = registry.find(id) != null ? registry.touch(id) : p.entry;
			ServerLevel fresh = open(e);
			LOG.info("Monde de Valcombe recréé à neuf : {}.", id);
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
	 * Remplace le monde de repli, où vont les joueurs d'un monde recréé ou supprimé. Le hub s'y branchera ;
	 * null remet le monde principal.
	 */
	public void setFallback(Fallback fallback) {
		this.fallback = fallback == null ? MAIN_WORLD : fallback;
	}

	/** Envoie au monde de repli tous les joueurs de ce monde, avec un mot pour leur dire pourquoi. */
	private void evacuate(ServerLevel level, WorldEntry e, boolean recreate) {
		for (ServerPlayer p : new ArrayList<>(level.players())) {
			try {
				fallback.evacuate(p, e);
			} catch (RuntimeException ex) {
				LOG.error("Monde de repli en échec pour " + p.getScoreboardName(), ex);
			}
			if (p.level() == level) {
				toMainWorld(p); // le repli n'a rien fait : le monde principal, toujours là
			}
			p.sendSystemMessage(Component.literal("Le monde « " + e.nom() + " » " + (recreate ? "est remis à neuf" : "est supprimé")
				+ " : tu en as été sorti.").withStyle(ChatFormatting.GRAY));
		}
	}

	/** Téléporte ce joueur au point d'apparition du monde principal. */
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
		RuntimeLevelHandle handle = Fantasy.get(server).getOrOpenPersistentLevel(key(e.id()).identifier(), config(e.sorte()));
		handles.put(e.id(), handle);
		return handle.asLevel();
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
