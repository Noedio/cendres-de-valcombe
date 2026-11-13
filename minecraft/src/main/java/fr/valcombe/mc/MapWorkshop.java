package fr.valcombe.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import fr.valcombe.mc.worlds.BuildAccess;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldLookup;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.GameException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * <b>L'atelier carte</b> : l'endroit, propre et sûr, où l'on prépare une carte de jeu. Chaque carte en cours de travail a
 * son propre monde ({@link WorldKind#ATELIER_CARTE} : plat, paisible, midi fixe), qui garde le lien vers la carte de la
 * bibliothèque qu'il porte ({@link WorldEntry#carte()}, voir {@link AtelierLinks}). Un seul atelier par carte.
 *
 * <h2>Ouvrir</h2>
 * <ul>
 * <li>une carte de la bibliothèque : un monde neuf, la carte posée au centre ({@link MapPlacer#place}) ;</li>
 * <li>une carte vierge : un monde neuf, rien dessus ; on bâtit et on pose les repères ;</li>
 * <li>un village généré : un monde neuf, le village de Valcombe bâti au centre (salle de convocation et salle d'attente
 * comprises).</li>
 * </ul>
 * Rouvrir une carte déjà en atelier mène à cet atelier.
 *
 * <h2>Travailler</h2>
 * On bâtit avec WorldEdit (permis dans les ateliers, voir {@link BuildAccess}) et à la main ; les repères (trônes,
 * maisons, centre de la place, salle de convocation) se posent avec les outils « Carte : ... », donnés à l'entrée, et
 * les structures du catalogue avec l'outil « Carte : structure » ({@link MapEditor}). Les repères sont visibles :
 * étiquettes flottantes, contours au sol, et un panneau au-dessus de la place qui les compte (« 12 trônes sur 16, 12
 * maisons, salle de convocation : oui ») et dit ce qui manque pour jouer ({@link MapTally}). Le même compte s'affiche
 * au-dessus de la barre d'objets. {@code /vc annuler} défait aussi les repères.
 *
 * <h2>Enregistrer</h2>
 * « Enregistrer » capture le monde (zone bâtie et repères, {@link MapPlacer#shoot}) et le range dans la bibliothèque,
 * avec une version gardée à part ({@link MapVersions}). L'enregistrement se fait aussi tout seul : toutes les
 * {@value #AUTOSAVE_MINUTES} minutes s'il y a eu du changement, à l'arrêt du serveur et à la fermeture de l'atelier.
 * Rien n'est écrit quand rien n'a changé (empreinte de la capture, {@link MapPlacer#signature}). Les versions automatiques
 * sont en nombre borné ; « Revenir à la version du ... » remet une version dans la bibliothèque et dans l'atelier (l'état
 * présent est d'abord gardé, lui aussi). « Enregistrer sous » range une copie : l'atelier continue sur la copie.
 *
 * <h2>Envoyer, fermer</h2>
 * « Envoyer à la partie » enregistre d'abord, puis envoie la carte au monde de partie choisi ({@link MapSender}) ;
 * l'atelier reste ouvert. « Fermer l'atelier » enregistre, puis le monde est déchargé et supprimé (les joueurs
 * présents vont au hub). Les invités d'un atelier y bâtissent et posent les repères ; ouvrir, enregistrer, envoyer et
 * fermer sont l'affaire des opérateurs (l'enregistrement automatique garde le travail de tous).
 * <p>
 * Ce que l'atelier retient d'une carte est rangé dans le dossier de son monde : les repères ({@value #DRAFT}, la fiche
 * d'un village) et l'état ({@value #STATE} : empreinte du dernier enregistrement, pose en cours). Une pose coupée par
 * un arrêt du serveur reprend au démarrage. Tout se fait sur le fil du serveur.
 */
public final class MapWorkshop implements WorldManager.Listener, WorldManager.Guard {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/** Étiquette des affichages de l'atelier carte (repères, panneau de la place). */
	public static final String TAG = "vc_ac";
	/** Les repères de la carte en travail, dans le dossier du monde de l'atelier. */
	static final String DRAFT = "valcombe-atelier.json";
	/** L'état de l'atelier (empreinte, date du dernier enregistrement, pose en cours), dans le dossier de son monde. */
	static final String STATE = "valcombe-atelier-etat.json";
	/** Intervalle de l'enregistrement automatique, en minutes. */
	public static final int AUTOSAVE_MINUTES = 5;
	/** Le sol plat : la place d'une carte ouverte en atelier est au centre, sur l'herbe. */
	static final int S = WorldManager.SURFACE_Y;
	public static final BlockPos CENTER = new BlockPos(0, S, 0);
	/** Au-delà de l'emprise de la carte, jusqu'où l'on cherche ce qui a été bâti (voir aussi la portée des constructeurs). */
	static final int SCAN_MARGIN = MapStore.SCAN_MARGIN;
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM 'à' HH'h'mm", Locale.FRENCH)
		.withZone(ZoneId.systemDefault());
	private static final DustParticleOptions GOLD = new DustParticleOptions(0xFFC83D, 1.3f);
	private static final DustParticleOptions GREEN = new DustParticleOptions(0x55D46A, 1.3f);
	private static final DustParticleOptions PURPLE = new DustParticleOptions(0xC27BFF, 1.3f);
	private static final DustParticleOptions BORDER = new DustParticleOptions(0xE8E2D0, 1.0f);

	/** Ce que l'atelier retient d'une carte en travail. */
	private static final class Draft {
		final String world;
		/** Les repères (et réglages) de la carte ; null tant qu'une pose est en cours. */
		Village v;
		/** Pose en cours : « carte » (de la bibliothèque), « village » (généré) ou « import » (fichier) ; null sinon. */
		String pose;
		/** Fichier du dossier d'import en cours de pose (pour la reprendre après un arrêt). */
		String fichier;
		/** Réglages du village généré en cours de pose (pour la reprendre après un arrêt). */
		int places = Village.SEATS;
		int taille = 1;
		String theme = "gothique";
		boolean maisons = true;
		/** Empreinte de la dernière capture rangée (ou de l'état d'ouverture) : rien n'est réécrit tant qu'elle ne change pas. */
		String signature;
		/** Des changements vus depuis le dernier enregistrement (blocs, repères, WorldEdit). */
		boolean changed;
		/** Quelqu'un est passé dans l'atelier depuis le dernier enregistrement (l'empreinte dira s'il a changé quelque chose). */
		boolean visited;
		/** Tick du dernier enregistrement (ou de l'ouverture), pour l'enregistrement automatique. */
		int lastSave;
		/** Date du dernier enregistrement dans la bibliothèque, ou null. */
		Instant savedAt;
		/** Jusqu'où les constructeurs sont allés depuis le centre (pour chercher ce qu'ils ont bâti). */
		int reach;
		/** Repères d'avant chaque modification, pour l'« annuler » commun. */
		final Deque<Village.Marks> undo = new ArrayDeque<>();
		/** Maisons et salles bâties d'un clic (accroupi) : repère → blocs d'avant, pour les retirer à la gomme. */
		final Map<BlockPos, Map<BlockPos, BlockState>> prefabs = new HashMap<>();
		/** Dernière fois qu'une pose a échoué (ticks), pour ne pas la relancer en boucle. */
		int failedAt = -1_000_000;

		Draft(String world) {
			this.world = world;
		}
	}

	/** Ce que l'atelier retient d'une carte sur le disque, à côté de ses repères. */
	private static final class State {
		String signature;
		int portee;
		String enregistreLe;
		String pose;
		String fichier;
		int places = Village.SEATS;
		int taille = 1;
		String theme = "gothique";
		boolean maisons = true;
	}

	/** Un repère pris en main pour le déplacer : son atelier et lequel. */
	private record Carry(String world, Village.Hit hit, boolean hand) {
	}

	/** Le dernier message d'une action de l'atelier, pour l'écran (le chat est caché derrière). */
	private record Reply(int n, String text, boolean error) {
	}

	private final MinecraftServer server;
	private final WorldManager worlds;
	private final MapPlacer placer;
	private final MapStore maps;
	private final MapSender sender;
	/** L'identifiant du monde de la partie principale (proposée d'abord pour un envoi). */
	private final java.util.function.Supplier<String> mainGame;
	private final MapEditor editor;
	private final Map<String, Draft> drafts = new HashMap<>();
	private final Map<UUID, Carry> carrying = new HashMap<>();
	/** Les joueurs qui sont dans un atelier carte (pour leur donner les outils en entrant, et les reprendre en sortant). */
	private final Map<UUID, String> inside = new HashMap<>();
	/** La dernière modification WorldEdit vue de chaque joueur (pour savoir qu'il vient d'en faire une). */
	private final Map<UUID, Integer> worldEditSeen = new HashMap<>();
	private final Map<UUID, Reply> replies = new HashMap<>();
	/** Les ateliers dont l'état a été regardé une fois au démarrage (pose coupée par un arrêt à reprendre). */
	private final Set<String> probed = new HashSet<>();
	private int replyCount;
	private int ticks;
	private int autosaveTicks = AUTOSAVE_MINUTES * 60 * 20;

	MapWorkshop(MinecraftServer server, WorldManager worlds, MapPlacer placer, MapStore maps, MapSender sender,
			java.util.function.Supplier<String> mainGame,
			MapEditor editor) {
		this.server = server;
		this.worlds = worlds;
		this.placer = placer;
		this.maps = maps;
		this.sender = sender;
		this.mainGame = mainGame;
		this.editor = editor;
	}

	// ------------------------------------------------------------------
	// Les mondes des ateliers
	// ------------------------------------------------------------------

	/** La fiche de l'atelier carte qu'est ce niveau, ou null. */
	public WorldEntry entry(Level level) {
		WorldEntry e = level == null ? null : worlds.entryOf(level);
		return e != null && e.sorte() == WorldKind.ATELIER_CARTE ? e : null;
	}

	/** Ce niveau est-il un atelier carte ? */
	public boolean isAtelier(Level level) {
		return entry(level) != null;
	}

	/** Les ateliers carte ouverts, du plus ancien au plus récent. */
	public List<WorldEntry> ateliers() {
		return AtelierLinks.ateliers(worlds.list());
	}

	/** L'atelier qui porte cette carte de la bibliothèque, ou null. */
	public WorldEntry atelierOf(String mapId) {
		return AtelierLinks.atelierOf(worlds.list(), mapId);
	}

	/** La carte que porte un atelier (identifiant de la bibliothèque, réservé même avant le premier enregistrement). */
	private String mapId(WorldEntry e) {
		String id = e.carte();
		if (id == null) {
			// Atelier créé à la main (/vc monde creer atelier-carte) : il reçoit une carte neuve, à son nom.
			id = freeMapId(e.nom());
			worlds.setCarte(e.id(), id);
		}
		return id;
	}

	/** Un identifiant de carte libre : ni dans la bibliothèque, ni porté par un autre atelier. */
	private String freeMapId(String name) {
		return AtelierLinks.freeMapId(name, id -> maps.exists(id) || atelierOf(id) != null);
	}

	private Path file(WorldEntry e, String name) {
		return worlds.folder(e.id()).resolve(name);
	}

	/** Le nom de la carte d'un atelier : celui de la bibliothèque s'il y est (renommée ailleurs), sinon celui de ses repères. */
	private String mapName(WorldEntry e, Draft d) {
		MapStore.MapDef def = libraryDef(mapId(e));
		if (def != null) {
			return def.nom;
		}
		String n = d.v == null ? "" : d.v.libraryName();
		return n.isEmpty() ? e.nom() : n;
	}

	/** Une carte de la bibliothèque, ou null. */
	private MapStore.MapDef libraryDef(String id) {
		for (MapStore.MapDef def : maps.list()) {
			if (def.id.equals(id)) {
				return def;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Ce que l'atelier retient (repères et état), relu à la première demande
	// ------------------------------------------------------------------

	/** Ce que l'atelier retient de ce monde, relu au besoin ; relance une pose coupée par un arrêt. */
	private Draft draft(WorldEntry e) {
		Draft d = drafts.get(e.id());
		if (d != null) {
			if (d.v == null && d.pose != null && !placing(e) && ticks - d.failedAt > 1200) {
				relaunch(e, d); // une pose qui a échoué : nouvel essai, au plus une fois par minute
			}
			return d;
		}
		ServerLevel level = worlds.level(e.id());
		d = new Draft(e.id());
		drafts.put(e.id(), d);
		d.lastSave = ticks;
		readState(e, d);
		if (d.pose != null) {
			relaunch(e, d);
			return d;
		}
		Village.Loaded loaded = Village.load(file(e, DRAFT));
		if (loaded.state() == Village.Fiche.READY) {
			d.v = loaded.village();
			return d;
		}
		String id = mapId(e);
		PlacedMap placed = placer.placed(level);
		if (placed != null && placed.mapId().equals(id)) {
			d.v = placed.toVillage();
			saveDraft(e, d);
		} else if (maps.exists(id)) {
			// Monde neuf (ou remis à neuf) : la carte de la bibliothèque y est posée.
			startPlace(e, level, d, id, null, null);
		} else {
			d.v = Village.custom(CENTER);
			d.v.setLibrary(id, e.nom());
			saveDraft(e, d);
			baseline(e, d);
		}
		return d;
	}

	/** Le monde reçoit-il une carte en ce moment ? */
	private boolean placing(WorldEntry e) {
		ServerLevel level = server.getLevel(WorldManager.key(e.id()));
		return level != null && placer.busy(level);
	}

	private void readState(WorldEntry e, Draft d) {
		Path f = file(e, STATE);
		if (!Files.exists(f)) {
			return;
		}
		try {
			State s = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), State.class);
			if (s == null) {
				return;
			}
			d.signature = s.signature;
			d.reach = Math.max(0, s.portee);
			d.savedAt = s.enregistreLe == null ? null : Instant.parse(s.enregistreLe);
			d.pose = "carte".equals(s.pose) || "village".equals(s.pose) || "import".equals(s.pose) && s.fichier != null ? s.pose : null;
			d.fichier = s.fichier;
			d.places = s.places;
			d.taille = s.taille;
			d.theme = s.theme == null ? "gothique" : s.theme;
			d.maisons = s.maisons;
		} catch (IOException | RuntimeException ex) {
			ValcombeMod.LOG.warn("État de l'atelier {} illisible : {}", e.id(), ex.getMessage());
		}
	}

	private void writeState(WorldEntry e, Draft d) {
		State s = new State();
		s.signature = d.signature;
		s.portee = d.reach;
		s.enregistreLe = d.savedAt == null ? null : d.savedAt.toString();
		s.pose = d.pose;
		s.fichier = d.fichier;
		s.places = d.places;
		s.taille = d.taille;
		s.theme = d.theme;
		s.maisons = d.maisons;
		try {
			Path f = file(e, STATE);
			Files.createDirectories(f.getParent());
			SafeFile.write(f, GSON.toJson(s));
		} catch (IOException ex) {
			ValcombeMod.LOG.warn("État de l'atelier {} non enregistré : {}", e.id(), ex.getMessage());
		}
	}

	private void saveDraft(WorldEntry e, Draft d) {
		if (d.v != null) {
			d.v.save(file(e, DRAFT));
		}
		writeState(e, d);
	}

	/** L'empreinte de l'état présent devient la référence : rien n'a changé depuis. */
	private void baseline(WorldEntry e, Draft d) {
		try {
			ServerLevel level = worlds.level(e.id());
			d.signature = MapPlacer.signature(shoot(e, d, level, mapId(e), mapName(e, d)));
		} catch (GameException | IllegalStateException ex) {
			d.signature = null;
		}
		d.changed = false;
		d.visited = false;
		d.lastSave = ticks;
		writeState(e, d);
	}

	/** Relance la pose interrompue (arrêt du serveur, monde remis à neuf) de la carte ou du village de cet atelier. */
	private void relaunch(WorldEntry e, Draft d) {
		ServerLevel level = worlds.level(e.id());
		try {
			if ("village".equals(d.pose)) {
				startVillage(e, level, d, MapSender.village(d.places, d.taille, d.theme, d.maisons), null);
			} else if ("import".equals(d.pose)) {
				startImport(e, level, d, d.fichier, importClip(d.fichier), null);
			} else {
				String id = mapId(e);
				if (!maps.exists(id)) {
					d.pose = null;
					d.v = Village.custom(CENTER);
					d.v.setLibrary(id, e.nom());
					saveDraft(e, d);
					return;
				}
				startPlace(e, level, d, id, null, null);
			}
			ValcombeMod.LOG.info("Atelier carte « {} » : pose reprise.", e.nom());
		} catch (RuntimeException ex) {
			d.failedAt = ticks;
			ValcombeMod.LOG.warn("Atelier carte « {} » : pose impossible à reprendre ({}).", e.nom(), ex.getMessage());
		}
	}

	/** L'état sur le disque de cet atelier note-t-il une pose inachevée ? */
	private boolean pendingPose(WorldEntry e) {
		Draft probe = new Draft(e.id());
		readState(e, probe);
		return probe.pose != null;
	}

	/** L'atelier est-il prêt (ni pose de carte, ni construction de village en cours) ? Pour les écrans et les tests en jeu. */
	public boolean idle(String worldId) {
		WorldEntry e = worlds.find(worldId);
		if (e == null || e.sorte() != WorldKind.ATELIER_CARTE || worlds.busy(e.id())) {
			return false;
		}
		Draft d = draft(e);
		return d.v != null && !placing(e);
	}

	/** Les repères de la carte de cet atelier tels qu'ils sont maintenant (null pendant une pose), pour les tests en jeu. */
	public Village marks(String worldId) {
		WorldEntry e = worlds.find(worldId);
		return e == null || e.sorte() != WorldKind.ATELIER_CARTE ? null : draft(e).v;
	}

	/** Les repères de la carte de cet atelier, ou null (pas un atelier carte, ou pose en cours) : pour l'éditeur. */
	Village village(Level level) {
		WorldEntry e = entry(level);
		return e == null ? null : draft(e).v;
	}

	// ------------------------------------------------------------------
	// Poser une carte ou un village dans un atelier
	// ------------------------------------------------------------------

	/**
	 * Pose la carte mapId de la bibliothèque au centre de cet atelier. Quand tout est posé, ses repères deviennent ceux de
	 * l'atelier, l'empreinte de référence est prise, et then (s'il y en a) est appelé sur le fil du serveur.
	 */
	private void startPlace(WorldEntry e, ServerLevel level, Draft d, String mapId, ServerPlayer watcher, Runnable then) {
		d.pose = "carte";
		d.v = null;
		writeState(e, d);
		MapPlacer.Placement pl = placer.place(mapId, level, CENTER, watcher);
		pl.done().whenComplete((map, error) -> {
			if (drafts.get(e.id()) != d) {
				return; // atelier fermé entre-temps
			}
			if (error != null) {
				// La pose reprendra (au prochain démarrage, ou dans une minute) : rien n'est effacé ni écrit d'ici là.
				d.failedAt = ticks;
				tellWorld(level, Component.literal("[Valcombe] La pose de la carte dans l'atelier « " + e.nom() + " » a échoué : "
					+ failure(error)).withStyle(ChatFormatting.RED));
				return;
			}
			Village v = pl.village();
			v.setLibrary(map.mapId(), map.nom());
			d.v = v;
			d.pose = null;
			d.undo.clear();
			d.prefabs.clear();
			if (editor != null && editor.worksIn(level)) {
				editor.forgetHistory();
			}
			saveDraft(e, d);
			baseline(e, d);
			if (then != null) {
				try {
					then.run();
				} catch (RuntimeException ex) {
					ValcombeMod.LOG.warn("Atelier carte « {} » : suite de la pose en échec ({}).", e.nom(), ex.getMessage());
				}
			}
		});
	}

	/** Bâtit ce village généré au centre de cet atelier ; ses repères deviennent ceux de l'atelier. */
	private void startVillage(WorldEntry e, ServerLevel level, Draft d, MapSender.Generated g, ServerPlayer watcher) {
		d.pose = "village";
		d.places = g.seats();
		d.taille = g.size();
		d.theme = g.theme();
		d.maisons = g.houses();
		d.v = null;
		writeState(e, d);
		Village v = Village.generated(CENTER, g.theme(), g.seats(), g.size(), g.houses());
		UUID viewer = watcher == null ? null : watcher.getUUID();
		placer.build("Construction du village dans l'atelier « " + e.nom() + " »", level, v.planBuild(level), CENTER, v.extent(),
			p -> p.getUUID().equals(viewer)).whenComplete((ok, error) -> {
				if (drafts.get(e.id()) != d) {
					return;
				}
				if (error != null) {
					d.failedAt = ticks;
					tellWorld(level, Component.literal("[Valcombe] La construction du village dans l'atelier « " + e.nom()
						+ " » a échoué : " + failure(error)).withStyle(ChatFormatting.RED));
					return;
				}
				// Comme un village envoyé à une partie : sa salle de convocation et sa salle d'attente font partie de la carte.
				v.buildConvocation(level);
				v.buildHall(level);
				v.setLibrary(mapId(e), e.nom());
				d.v = v;
				d.pose = null;
				d.undo.clear();
				d.prefabs.clear();
				saveDraft(e, d);
				// Un village tout juste généré n'existe encore nulle part : il est à enregistrer (l'empreinte de référence reste vide).
				d.signature = null;
				d.changed = true;
				d.lastSave = ticks;
				writeState(e, d);
				ServerPlayer p = viewer == null ? null : server.getPlayerList().getPlayer(viewer);
				if (p != null) {
					try {
						arrive(p, e, d);
					} catch (GameException ex) {
						p.sendSystemMessage(Component.literal("[Valcombe] Le village est prêt dans son atelier, mais " + ex.getMessage() + " ")
							.withStyle(ChatFormatting.GOLD).append(link("[Y aller]", "/vc atelier carte aller " + e.id(), "Aller dans l'atelier")));
					}
				}
			});
	}

	/** Les blocs d'un fichier du dossier d'import (.schem, .litematic, .nbt), lus et contrôlés ; une GameException sinon. */
	private Clip importClip(String fileName) {
		StructureStore store = ValcombeMod.structures();
		if (store == null) {
			throw new GameException("Le catalogue de structures n'est pas prêt : réessayez dans un instant.");
		}
		return SchematicImporter.read(fileName, store.inboxFile(fileName),
			server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK));
	}

	/**
	 * Pose une construction importée au centre de cet atelier, comme un envoi à la partie le ferait : elle arrive sans
	 * repères (on les pose ensuite avec les outils), et la carte est à enregistrer.
	 */
	private void startImport(WorldEntry e, ServerLevel level, Draft d, String fileName, Clip clip, ServerPlayer watcher) {
		d.pose = "import";
		d.fichier = fileName;
		d.v = null;
		writeState(e, d);
		BlockPos corner = CENTER.offset(-clip.width / 2, 0, -clip.length / 2);
		int radius = Math.min(MapStore.MAX_RADIUS, Math.max(clip.width, clip.length) / 2 + 2);
		UUID viewer = watcher == null ? null : watcher.getUUID();
		placer.build("Import de « " + fileName + " » dans l'atelier « " + e.nom() + " »", level, clip.plan(level, corner, false, false),
			CENTER, radius, p -> p.getUUID().equals(viewer)).whenComplete((ok, error) -> {
				if (drafts.get(e.id()) != d) {
					return;
				}
				if (error != null) {
					d.failedAt = ticks;
					tellWorld(level, Component.literal("[Valcombe] L'import de « " + fileName + " » dans l'atelier « " + e.nom()
						+ " » a échoué : " + failure(error)).withStyle(ChatFormatting.RED));
					return;
				}
				Village v = Village.custom(CENTER);
				v.setLibrary(mapId(e), e.nom());
				d.v = v;
				d.pose = null;
				d.fichier = null;
				d.undo.clear();
				d.prefabs.clear();
				// Tout ce qui vient d'être posé est à enregistrer, jusqu'au bord de la construction.
				d.reach = Math.max(d.reach, radius);
				saveDraft(e, d);
				d.signature = null;
				d.changed = true;
				d.lastSave = ticks;
				writeState(e, d);
				ServerPlayer p = viewer == null ? null : server.getPlayerList().getPlayer(viewer);
				if (p != null) {
					try {
						arrive(p, e, d);
					} catch (GameException ex) {
						p.sendSystemMessage(Component.literal("[Valcombe] La construction est posée dans son atelier, mais " + ex.getMessage()
							+ " ").withStyle(ChatFormatting.GOLD).append(link("[Y aller]", "/vc atelier carte aller " + e.id(), "Aller dans l'atelier")));
					}
				}
			});
	}

	/**
	 * Ouvre un atelier sur une construction importée : un fichier .schem, .litematic ou .nbt déposé dans le dossier
	 * d'import du serveur, posé au centre d'un monde neuf. On y pose ensuite les repères, puis on l'enregistre.
	 */
	public String openImport(String fileName, String name, ServerPlayer by) {
		requireOperator(by);
		Clip clip = importClip(fileName); // lu et contrôlé avant de créer le monde : un fichier abîmé ne laisse pas d'atelier vide
		String base = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
		String nom = AtelierLinks.cleanName(name == null || name.isBlank() ? base.replace('_', ' ') : name);
		if (nom == null) {
			throw new GameException("Nom de carte : 1 à 50 caractères, sans code de mise en forme.");
		}
		String id = freeMapId(nom);
		WorldEntry e = worlds.create(WorldKind.ATELIER_CARTE, nom);
		e = worlds.setCarte(e.id(), id);
		Draft d = new Draft(e.id());
		d.lastSave = ticks;
		drafts.put(e.id(), d);
		startImport(e, worlds.level(e.id()), d, fileName, clip, by);
		return "Import de « " + fileName + " » (" + clip.dims() + ") dans l'atelier « " + nom + " » : vous y serez conduit à la fin. "
			+ "Posez ensuite les trônes et les maisons, puis enregistrez." + McHost.PROGRESS;
	}

	private static String failure(Throwable t) {
		Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
		return c instanceof GameException ? c.getMessage() : "erreur interne : " + c.getMessage();
	}

	// ------------------------------------------------------------------
	// Ouvrir un atelier
	// ------------------------------------------------------------------

	private static void requireOperator(ServerPlayer p) {
		if (p != null && !BuildAccess.operator(p)) {
			throw new GameException("Ouvrir, enregistrer, envoyer et fermer un atelier carte est réservé aux opérateurs. Les invités y "
				+ "bâtissent et y posent les repères : l'enregistrement automatique garde leur travail.");
		}
	}

	/**
	 * Ouvre la carte mapId de la bibliothèque dans un atelier : un monde neuf, la carte posée au centre. Si elle est déjà
	 * en atelier, c'est cet atelier qui est rouvert. by (ou null) suit la pose ; go : il y est conduit à la fin.
	 */
	public String openLibrary(String mapId, ServerPlayer by, boolean go) {
		requireOperator(by);
		MapStore.MapDef def = maps.checkedDef(mapId);
		WorldEntry open = atelierOf(def.id);
		if (open != null) {
			return reopen(open, by, go);
		}
		maps.readClip(def); // les blocs sont relus avant de créer le monde : une carte abîmée ne laisse pas d'atelier vide
		WorldEntry e = worlds.create(WorldKind.ATELIER_CARTE, def.nom);
		e = worlds.setCarte(e.id(), def.id);
		ServerLevel level = worlds.level(e.id());
		Draft d = new Draft(e.id());
		d.lastSave = ticks;
		drafts.put(e.id(), d);
		WorldEntry fe = e;
		UUID viewer = by == null ? null : by.getUUID();
		startPlace(e, level, d, def.id, by, () -> {
			ServerPlayer p = viewer == null ? null : server.getPlayerList().getPlayer(viewer);
			if (p != null && go) {
				arrive(p, fe, d);
			} else if (p != null) {
				p.sendSystemMessage(Component.literal("[Valcombe] « " + def.nom + " » est prête dans son atelier. ")
					.withStyle(ChatFormatting.GREEN).append(link("[Y aller]", "/vc atelier carte aller " + def.id, "Aller dans l'atelier")));
			}
		});
		ValcombeMod.LOG.info("Atelier carte ouvert : « {} » ({}) dans {}.", def.nom, def.id, e.id());
		return "Atelier ouvert pour « " + def.nom + " » : la carte est posée dans un monde neuf" + (go ? ", puis vous y serez conduit." : ".")
			+ McHost.PROGRESS;
	}

	/** Rouvre un atelier déjà ouvert (un seul atelier par carte) : on y est conduit. */
	private String reopen(WorldEntry e, ServerPlayer by, boolean go) {
		Draft d = draft(e);
		if (by == null || !go) {
			return "La carte « " + mapName(e, d) + " » est déjà dans son atelier (« " + e.nom() + " ») : on y retrouve le travail en cours. "
				+ "Pour y aller : /vc atelier carte aller " + mapId(e) + ".";
		}
		if (by.level() == worlds.level(e.id())) {
			return "Vous êtes déjà dans l'atelier de « " + mapName(e, d) + " ».";
		}
		arrive(by, e, d);
		return "";
	}

	/** Ouvre un atelier sur une carte vierge, nommée : un monde neuf et plat, sans rien dessus. */
	public String openBlank(String name, ServerPlayer by) {
		requireOperator(by);
		String nom = AtelierLinks.cleanName(name);
		if (nom == null) {
			throw new GameException("Nom de carte : 1 à 50 caractères, sans code de mise en forme.");
		}
		String id = freeMapId(nom);
		WorldEntry e = worlds.create(WorldKind.ATELIER_CARTE, nom);
		e = worlds.setCarte(e.id(), id);
		Draft d = new Draft(e.id());
		drafts.put(e.id(), d);
		d.v = Village.custom(CENTER);
		d.v.setLibrary(id, nom);
		saveDraft(e, d);
		baseline(e, d);
		if (by != null) {
			arrive(by, e, d);
		}
		return "Carte vierge « " + nom + " » ouverte dans son atelier (identifiant « " + id + " »). Bâtissez, posez les repères, "
			+ "puis « Enregistrer » : elle rejoindra la bibliothèque.";
	}

	/** Ouvre un atelier sur un village de Valcombe généré (mêmes réglages que l'envoi d'un village à la partie). */
	public String openVillage(int seats, int size, String theme, boolean houses, String name, ServerPlayer by) {
		requireOperator(by);
		MapSender.Generated g = MapSender.village(seats, size, theme, houses);
		String nom = AtelierLinks.cleanName(name == null || name.isBlank() ? "Village " + g.theme() + " " + g.seats() : name);
		if (nom == null) {
			throw new GameException("Nom de carte : 1 à 50 caractères, sans code de mise en forme.");
		}
		String id = freeMapId(nom);
		WorldEntry e = worlds.create(WorldKind.ATELIER_CARTE, nom);
		e = worlds.setCarte(e.id(), id);
		Draft d = new Draft(e.id());
		d.lastSave = ticks;
		drafts.put(e.id(), d);
		startVillage(e, worlds.level(e.id()), d, g, by);
		return "Village généré en construction dans l'atelier « " + nom + " » (" + g.label() + ") : vous y serez conduit à la fin."
			+ McHost.PROGRESS;
	}

	/** Conduit ce joueur dans l'atelier de cette carte (identifiant de carte, ou nom ou identifiant d'atelier). */
	public String go(ServerPlayer p, String text) {
		WorldEntry e = atelierOf(text == null ? null : text.strip());
		if (e == null) {
			e = WorldLookup.resolve(ateliers(), text);
		}
		Draft d = draft(e);
		if (p.level() == worlds.level(e.id())) {
			return "Vous êtes déjà dans l'atelier de « " + mapName(e, d) + " ».";
		}
		BuildAccess.requireBuildIn(p, e);
		arrive(p, e, d);
		return "";
	}

	/** Conduit ce joueur dans l'atelier (à sa dernière position, la première fois sur la place), puis l'accueille. */
	private void arrive(ServerPlayer p, WorldEntry e, Draft d) {
		ServerLevel level = worlds.level(e.id());
		var travel = ValcombeMod.travel();
		boolean first = travel == null || travel.positions().get(p.getUUID(), e.id()) == null;
		if (p.level() != level) {
			if (travel != null) {
				travel.go(p, e.id(), null);
			} else {
				worlds.teleport(p, e.id());
			}
		}
		if (first && p.level() == level && d.v != null) {
			BlockPos s = d.v.spawn();
			p.teleportTo(level, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, Set.of(), 180f, 10f, false);
		}
	}

	// ------------------------------------------------------------------
	// Enregistrer
	// ------------------------------------------------------------------

	/** L'emprise de la carte telle qu'elle serait enregistrée maintenant : ses repères, ses réglages, sa taille actuelle. */
	private PlacedMap layout(WorldEntry e, Draft d, ServerLevel level, String id, String nom) {
		Village v = d.v;
		PlacedMap placed = placer.placed(level);
		MapStore.MapDef def = new MapStore.MapDef();
		def.id = id;
		def.nom = nom;
		def.genere = v.isGenerated();
		def.theme = v.theme();
		def.places = v.seatTotal();
		def.taille = v.size();
		def.maisons = v.withHouses();
		def.salle = v.hasHall();
		int extent = Math.min(MapStore.MAX_RADIUS, Math.max(4, v.extent()));
		def.rayon = placed != null ? Math.max(extent, Math.min(MapStore.MAX_RADIUS, placed.rayon())) : extent;
		def.hauteur = placed != null ? placed.hauteur() : 0;
		def.sieges = new ArrayList<>(v.relSeats());
		def.logis = new ArrayList<>(v.relHouses());
		List<double[]> yaws = v.relSeatYaws();
		def.orientations = yaws.isEmpty() ? null : yaws;
		def.convocation = v.relConvocation();
		return PlacedMap.of(def, level.dimension(), v.center());
	}

	/** Jusqu'où chercher ce qui a été bâti : l'emprise et une marge, et aussi loin que les constructeurs sont allés. */
	private static int scan(Draft d, PlacedMap layout) {
		return Math.min(MapStore.MAX_RADIUS, Math.max(layout.rayon() + SCAN_MARGIN, d.reach + 24));
	}

	private MapPlacer.Shot shoot(WorldEntry e, Draft d, ServerLevel level, String id, String nom) {
		PlacedMap layout = layout(e, d, level, id, nom);
		return placer.shoot(level, layout, id, nom, scan(d, layout));
	}

	/** Une carte prête à être enregistrée : ni pose en cours, ni atelier inconnu. */
	private Draft ready(WorldEntry e) {
		Draft d = draft(e);
		if (d.v == null || placing(e)) {
			throw new GameException("La carte est en train d'être posée dans l'atelier « " + e.nom() + " » : attendez la fin de la barre.");
		}
		return d;
	}

	/** Ce qu'a donné un enregistrement : la capture rangée (null : rien n'avait changé) et la version gardée. */
	private record Saved(MapPlacer.Capture capture, MapVersions.Version version) {
	}

	/**
	 * Enregistre la carte de cet atelier dans la bibliothèque (sous asId et asName pour une copie, sinon sous la sienne) et
	 * garde une version de sorte kind. Rien n'est écrit si rien n'a changé depuis le dernier enregistrement, ou depuis
	 * l'ouverture (une carte vierge où rien n'a été fait n'est pas rangée) ; force : on enregistre quand même (une copie).
	 */
	private Saved store(WorldEntry e, Draft d, MapVersions.Kind kind, String asId, String asName, boolean force) {
		ServerLevel level = worlds.level(e.id());
		String id = asId != null ? asId : mapId(e);
		String nom = asName != null ? asName : mapName(e, d);
		MapPlacer.Shot shot = shoot(e, d, level, id, nom);
		String sig = MapPlacer.signature(shot);
		if (!force && sig.equals(d.signature)) {
			d.changed = false;
			d.visited = false;
			d.lastSave = ticks;
			return new Saved(null, null);
		}
		MapPlacer.Capture c = placer.keep(level, shot);
		d.signature = sig;
		d.changed = false;
		d.visited = false;
		d.lastSave = ticks;
		d.savedAt = Instant.now();
		d.v.setLibrary(c.map().mapId(), c.map().nom());
		saveDraft(e, d);
		MapVersions.Version v = null;
		try {
			v = maps.versions().record(c.map().mapId(), kind);
		} catch (GameException ex) {
			ValcombeMod.LOG.warn("Atelier carte « {} » : version non gardée ({}).", e.nom(), ex.getMessage());
		}
		return new Saved(c, v);
	}

	/** « Carte « Port » enregistrée : 12 trônes sur 16, ... Version du 13/11 à 14h05 gardée. Pas encore jouable : ... » */
	private String savedText(Saved s, Draft d) {
		PlacedMap map = s.capture().map();
		MapTally t = MapTally.of(d.v);
		int side = 2 * map.rayon() + 1;
		return "Carte « " + map.nom() + " » enregistrée dans la bibliothèque : " + t.counter() + " ; zone de " + side + " × "
			+ map.hauteur() + " × " + side + " blocs."
			+ (s.version() != null ? " Version du " + s.version().shown() + " gardée." : "")
			+ (s.capture().cutTop() ? " Des blocs montaient plus haut que " + map.hauteur() + " blocs : ils ne sont pas rangés." : "")
			+ " " + t.verdict();
	}

	/** L'atelier carte où se tient ce joueur (il doit pouvoir y construire), ou une GameException. */
	private WorldEntry here(ServerPlayer p) {
		WorldEntry e = entry(p.level());
		if (e == null) {
			throw new GameException("Allez d'abord dans un atelier carte : Menu J › Mondes, ou /vc atelier carte liste.");
		}
		BuildAccess.requireBuildIn(p, e);
		return e;
	}

	/**
	 * L'atelier carte que désigne text (identifiant de sa carte, identifiant ou nom de l'atelier), pour agir dessus sans
	 * y être (Atelier en jeu, liste des ateliers ouverts) ; une GameException s'il n'y en a pas.
	 */
	private WorldEntry named(String text) {
		WorldEntry e = atelierOf(text == null ? null : text.strip());
		if (e == null) {
			if (ateliers().isEmpty()) {
				throw new GameException("Aucun atelier carte ouvert.");
			}
			e = WorldLookup.resolve(ateliers(), text);
		}
		return e;
	}

	/** « Enregistrer » : la carte de l'atelier où se tient ce joueur va dans la bibliothèque, avec une version. */
	public String save(ServerPlayer p) {
		requireOperator(p);
		return save(here(p));
	}

	/** « Enregistrer » un atelier désigné (voir named), d'où que l'on soit : réservé aux opérateurs. */
	public String save(ServerPlayer p, String atelier) {
		requireOperator(p);
		return save(named(atelier));
	}

	private String save(WorldEntry e) {
		Draft d = ready(e);
		Saved s = store(e, d, MapVersions.Kind.MANUELLE, null, null, false);
		if (s.capture() == null) {
			if (!maps.exists(mapId(e))) {
				return "Rien n'a encore été fait sur « " + mapName(e, d) + " » : il n'y a rien à ranger dans la bibliothèque. Bâtissez, posez "
					+ "les repères, puis enregistrez.";
			}
			MapVersions.Version last = maps.versions().last(mapId(e));
			return "Rien n'a changé depuis le dernier enregistrement" + (last != null ? " (" + last.shown() + ")" : "")
				+ " : la carte « " + mapName(e, d) + " » de la bibliothèque est à jour.";
		}
		return savedText(s, d);
	}

	/** « Enregistrer sous » : une copie, sous un nouveau nom ; l'atelier continue sur la copie. */
	public String saveAs(ServerPlayer p, String name) {
		requireOperator(p);
		WorldEntry e = here(p);
		Draft d = ready(e);
		String nom = AtelierLinks.cleanName(name);
		if (nom == null) {
			throw new GameException("Nom de la copie : 1 à 50 caractères, sans code de mise en forme.");
		}
		String before = mapName(e, d);
		String id = freeMapId(nom);
		Saved s = store(e, d, MapVersions.Kind.MANUELLE, id, nom, true);
		worlds.setCarte(e.id(), id);
		worlds.rename(e.id(), nom);
		return savedText(s, d) + " C'est une copie de « " + before + " » (identifiant « " + id + " ») : l'atelier continue sur elle, "
			+ "l'originale reste telle qu'enregistrée.";
	}

	/**
	 * Enregistrement automatique de cet atelier, s'il y a du nouveau : renvoie ce qui a été fait (pour les tests en jeu),
	 * ou une chaîne vide si rien n'avait changé.
	 */
	public String autosave(String worldId, MapVersions.Kind kind) {
		WorldEntry e = worlds.get(worldId);
		Draft d = drafts.get(e.id());
		if (d == null || d.v == null || placing(e)) {
			return "";
		}
		Saved s = store(e, d, kind, null, null, false);
		if (s.capture() == null) {
			return "";
		}
		String text = "Enregistrement " + (kind == MapVersions.Kind.AUTO ? "automatique" : kind.label()) + " de « " + s.capture().map().nom()
			+ " »" + (s.version() != null ? " (version du " + s.version().shown() + ")" : "") + ".";
		ServerLevel level = server.getLevel(WorldManager.key(e.id()));
		if (level != null) {
			for (ServerPlayer pl : level.players()) {
				pl.sendSystemMessage(Gui.plain(text, ChatFormatting.GRAY));
			}
		}
		ValcombeMod.LOG.info("Atelier carte « {} » : {}", e.nom(), text);
		return text;
	}

	/** Pour les tests en jeu : l'intervalle de l'enregistrement automatique, en ticks. */
	public void setAutosaveTicks(int t) {
		autosaveTicks = Math.max(20, t);
	}

	// ------------------------------------------------------------------
	// Versions
	// ------------------------------------------------------------------

	/** Les versions gardées de la carte de cet atelier, dans le chat, chacune avec un lien pour y revenir. */
	public List<Component> versions(ServerPlayer p) {
		WorldEntry e = here(p);
		String id = mapId(e);
		List<MapVersions.Version> list = maps.versions().list(id);
		List<Component> out = new ArrayList<>();
		if (list.isEmpty()) {
			out.add(Gui.plain("Aucune version gardée de « " + mapName(e, draft(e)) + " » : elles arrivent avec les enregistrements.",
				ChatFormatting.GRAY));
			return out;
		}
		out.add(Component.literal("Versions de « " + mapName(e, draft(e)) + " » (la plus récente d'abord) :").withStyle(ChatFormatting.GOLD));
		for (MapVersions.Version v : list) {
			out.add(Component.literal("  " + v.shown() + " · " + v.kind().label() + " · " + MapTally.plural(v.seats(), "trône", "trônes")
				+ ", " + MapTally.plural(v.houses(), "maison", "maisons") + " ").withStyle(ChatFormatting.WHITE)
				.append(link("[Revenir]", "/vc atelier carte revenir " + v.id(), "Revenir à la " + v.describe()
					+ " (l'état présent est gardé avant)")));
		}
		return out;
	}

	/**
	 * « Revenir à la version du ... » : l'état présent est d'abord gardé (version « avant un retour en arrière »), puis la
	 * version choisie revient dans la bibliothèque et dans l'atelier, reposée au centre.
	 */
	public String revert(ServerPlayer p, String versionId) {
		requireOperator(p);
		WorldEntry e = here(p);
		Draft d = ready(e);
		String id = mapId(e);
		MapVersions.Version v = maps.versions().find(id, versionId);
		if (v == null) {
			throw new GameException("Aucune version « " + versionId + " » de cette carte. La liste : /vc atelier carte versions.");
		}
		Saved before = store(e, d, MapVersions.Kind.AVANT_RETOUR, null, null, false);
		maps.restoreVersion(id, v.id());
		ServerLevel level = worlds.level(e.id());
		startPlace(e, level, d, id, p, null);
		return "Retour à la " + v.describe() + " : elle est de nouveau dans la bibliothèque, et reposée dans l'atelier."
			+ (before.capture() != null ? " L'état d'avant est gardé (version du " + (before.version() == null ? "moment"
				: before.version().shown()) + ")." : "") + McHost.PROGRESS;
	}

	// ------------------------------------------------------------------
	// Envoyer à la partie, fermer
	// ------------------------------------------------------------------

	/** Les mondes de partie où l'on peut envoyer une carte (la partie principale d'abord). */
	public List<WorldEntry> parties() {
		List<WorldEntry> out = new ArrayList<>();
		String main = mainGame.get();
		for (WorldEntry e : worlds.list()) {
			if (MapSender.accepts(e.sorte())) {
				if (e.id().equals(main)) {
					out.add(0, e);
				} else {
					out.add(e);
				}
			}
		}
		return out;
	}

	/**
	 * « Envoyer à la partie » : la carte est d'abord enregistrée, puis envoyée au monde de partie choisi (null : celui de
	 * la partie principale), remis à neuf. L'atelier reste ouvert. Refusé si la carte n'est pas jouable.
	 */
	public String send(ServerPlayer p, String target) {
		requireOperator(p);
		WorldEntry e = here(p);
		Draft d = ready(e);
		MapTally t = MapTally.of(d.v);
		if (!t.playable()) {
			throw new GameException("« " + mapName(e, d) + " » ne se joue pas encore : " + t.verdict().substring(t.verdict().indexOf(':') + 2)
				+ " Posez les trônes avec l'outil « Carte : trône », puis envoyez-la.");
		}
		WorldEntry to = sender.target(target); // le monde visé est contrôlé avant tout enregistrement
		Saved s = store(e, d, MapVersions.Kind.ENVOI, null, null, false);
		String said = sender.sendMap(mapId(e), to.id(), p);
		return (s.capture() != null ? "Carte enregistrée (" + t.counter() + "). " : "") + said;
	}

	/**
	 * « Fermer l'atelier » : la carte est enregistrée (si elle a changé, ou si elle n'est pas encore dans la bibliothèque et
	 * porte quelque chose), puis le monde est déchargé et supprimé ; ceux qui s'y trouvent vont au hub. Sans confirm, la
	 * question seulement.
	 */
	public String close(ServerPlayer p, boolean confirm) {
		requireOperator(p);
		return close(p, here(p), confirm, "/vc atelier carte fermer confirmer");
	}

	/** « Fermer l'atelier » désigné (voir named), d'où que l'on soit : réservé aux opérateurs. */
	public String close(ServerPlayer p, String atelier, boolean confirm) {
		requireOperator(p);
		WorldEntry e = named(atelier);
		return close(p, e, confirm, "/vc atelier carte fermer " + e.id() + " confirmer");
	}

	private String close(ServerPlayer p, WorldEntry e, boolean confirm, String confirmCommand) {
		Draft d = draft(e);
		if (d.v == null && !placing(e) && confirm) {
			// La pose de la carte a échoué : rien à enregistrer, l'atelier est fermé tel quel.
			drafts.remove(e.id());
			worlds.delete(e.id());
			return "Atelier fermé sans rien enregistrer : la carte n'avait pas pu y être posée. La bibliothèque est inchangée.";
		}
		d = ready(e);
		String nom = mapName(e, d);
		if (!confirm) {
			p.sendSystemMessage(Component.literal("Fermer l'atelier de « " + nom + " » ? La carte est d'abord enregistrée, puis le monde "
				+ "de l'atelier est supprimé (ceux qui s'y trouvent vont au hub). ").withStyle(ChatFormatting.GOLD)
				.append(link("[Fermer l'atelier]", confirmCommand, "Enregistrer, puis fermer l'atelier")));
			return "";
		}
		String id = mapId(e);
		// Rien n'est réécrit si rien n'a changé ; une carte vierge où rien n'a été fait n'est pas rangée.
		Saved s = store(e, d, MapVersions.Kind.FERMETURE, null, null, false);
		drafts.remove(e.id());
		carrying.values().removeIf(c -> c.world().equals(e.id()));
		worlds.delete(e.id());
		ValcombeMod.LOG.info("Atelier carte fermé : « {} » ({}).", nom, e.id());
		String saved = s.capture() != null ? "enregistrée dans la bibliothèque" + (s.version() != null ? " (version du "
			+ s.version().shown() + ")" : "") : maps.exists(id) ? "déjà à jour dans la bibliothèque" : "vierge, elle n'est pas rangée";
		return "Atelier fermé : « " + nom + " » est " + saved + ". Son monde est supprimé."
			+ (maps.exists(id) ? " Pour la rouvrir : /vc atelier carte ouvrir " + id + "." : "");
	}

	// ------------------------------------------------------------------
	// Les repères : outils, commandes, menu de la main
	// ------------------------------------------------------------------

	/** Les repères de l'atelier où se tient ce joueur, modifiables (pas de pose en cours), après contrôle des droits. */
	private Draft editable(ServerPlayer p, WorldEntry e) {
		BuildAccess.requireBuildIn(p, e);
		return ready(e);
	}

	/** Les repères ont changé : enregistrés avec l'atelier, étiquettes et trônes sculptés à jour au prochain passage. */
	private void markersChanged(WorldEntry e, Draft d, Village.Marks before) {
		if (before != null) {
			d.undo.push(before);
			while (d.undo.size() > 40) {
				d.undo.removeLast();
			}
		}
		d.changed = true;
		saveDraft(e, d);
	}

	/** Les trônes sculptés d'un village généré suivent ses trônes (déplacés, tournés, ajoutés, retirés). */
	private void refreshThrones(WorldEntry e, Draft d) {
		if (d.v != null && d.v.isGenerated()) {
			ServerLevel level = worlds.level(e.id());
			WorldSetup.kill(level, "type=minecraft:item_display,tag=vc_trone");
			d.v.placeThrones(level);
		}
	}

	private static void requireRoom(Village v, boolean seat) {
		if ((seat ? v.seatCount() : v.houseCount()) >= McHost.MAX_MARKERS) {
			throw new GameException("Une carte compte " + McHost.MAX_MARKERS + (seat ? " trônes" : " maisons") + " au plus.");
		}
	}

	private static Village.Mark markOf(ValcombeUi.MapTool tool) {
		return switch (tool) {
			case SEAT -> Village.Mark.SEAT;
			case HOUSE -> Village.Mark.HOUSE;
			case ROOM -> Village.Mark.ROOM;
			default -> Village.Mark.CENTER;
		};
	}

	private static Village.Mark markOf(String type) {
		return switch (type) {
			case "trone", "siege" -> Village.Mark.SEAT;
			case "maison" -> Village.Mark.HOUSE;
			case "salle" -> Village.Mark.ROOM;
			case "centre" -> Village.Mark.CENTER;
			default -> throw new GameException("Sorte d'objet inconnue : " + type + ".");
		};
	}

	private static String typeName(Village.Mark m) {
		return switch (m) {
			case SEAT -> "trone";
			case HOUSE -> "maison";
			case ROOM -> "salle";
			case CENTER -> "centre";
		};
	}

	/** Accord du participe : « Trône 3 déplacé », « Maison 3 déplacée ». */
	private static String done(Village.Hit h, String participle) {
		return participle + (h.type() == Village.Mark.HOUSE || h.type() == Village.Mark.ROOM ? "e" : "");
	}

	/** Déplace le centre d'une carte faite à la main ; refusé si un repère se retrouvait hors de portée. */
	private static void moveCenter(Village v, BlockPos to) {
		BlockPos before = v.center();
		v.setCenter(to);
		if (v.farthest() > Village.MAX_EXTENT) {
			v.setCenter(before);
			throw new GameException("Ce centre laisserait un repère à plus de " + (Village.MAX_EXTENT - 8)
				+ " blocs de la place : déplacez d'abord les repères.");
		}
	}

	private void moveMarker(Draft d, Village.Hit h, BlockPos to) {
		if (h.type() == Village.Mark.CENTER) {
			moveCenter(d.v, to.below());
		} else {
			var built = d.prefabs.remove(d.v.markerPos(h));
			d.v.moveMarker(h, to);
			if (built != null) {
				d.prefabs.put(to, built);
			}
		}
	}

	/** Un clic avec un outil de carte, dans un atelier carte ; renvoie ce qui s'est passé (vide : un menu s'est ouvert). */
	public String useTool(ServerPlayer p, ValcombeUi.MapTool tool, BlockPos clicked, boolean sneak) {
		WorldEntry e = entry(p.level());
		if (e == null) {
			throw new GameException("Cet outil sert dans un atelier carte.");
		}
		Draft d = editable(p, e);
		Village v = d.v;
		UUID who = p.getUUID();
		BlockPos at = clicked.above();
		if (tool == ValcombeUi.MapTool.HAND) {
			return handClick(p, e, d, clicked);
		}
		Carry held = carrying.get(who);
		if (held != null && (!held.world().equals(e.id()) || held.hand())) {
			carrying.remove(who);
			held = null;
		}
		Village.Marks before = v.marks();
		String msg;
		boolean seats = false;
		if (tool == ValcombeUi.MapTool.ERASE) {
			carrying.remove(who);
			Village.Hit h = v.markerAt(at, null, 1);
			if (h == null) {
				throw new GameException("Aucun repère ici : cliquez sur le bloc sous une étiquette.");
			}
			BlockPos pos = v.markerPos(h);
			v.removeMarker(h);
			var built = d.prefabs.remove(pos);
			if (built != null) {
				built.forEach((b, s) -> p.level().setBlock(b, s, Clip.EXACT));
			}
			seats = h.type() == Village.Mark.SEAT;
			msg = h.label() + done(h, " retiré") + (built != null ? ", et sa construction avec." : ".")
				+ (h.type() == Village.Mark.SEAT || h.type() == Village.Mark.HOUSE ? " Les suivants reculent d'un numéro." : "");
		} else if (tool == ValcombeUi.MapTool.CENTER) {
			moveCenter(v, clicked);
			msg = "Centre de la place posé ici.";
		} else {
			Village.Mark type = markOf(tool);
			Village.Hit here = v.markerAt(at, type, 0);
			boolean moving = held != null && held.hit().type() == type && (type == Village.Mark.ROOM
				|| held.hit().index() < (type == Village.Mark.SEAT ? v.seatCount() : v.houseCount()));
			if (moving || here == null || sneak) {
				McHost.requireNear(v, at);
			}
			if (moving) {
				moveMarker(d, held.hit(), at);
				carrying.remove(who);
				seats = type == Village.Mark.SEAT;
				msg = held.hit().label() + done(held.hit(), " déplacé") + " ici.";
			} else if (here != null && !sneak) {
				carrying.put(who, new Carry(e.id(), here, false));
				return here.label() + " en main : cliquez là où le reposer. Pour le retirer, prenez la gomme.";
			} else if (type == Village.Mark.ROOM) {
				if (sneak) {
					d.prefabs.put(at, v.buildRoomAt(p.level(), at));
				}
				v.setConvocation(at);
				msg = sneak ? "Salle de convocation bâtie ici : le Conteur y recevra chaque joueur à l'écart."
					: "Salle de convocation posée ici : le joueur convoqué arrivera à cet endroit.";
			} else if (type == Village.Mark.SEAT) {
				requireRoom(v, true);
				v.addSeat(at);
				seats = true;
				msg = "Trône " + v.seatCount() + " posé.";
			} else {
				requireRoom(v, false);
				if (sneak) {
					d.prefabs.put(at, v.buildHouseAt(p.level(), at));
				}
				v.addHouse(at);
				msg = "Maison " + v.houseCount() + (sneak ? " bâtie." : " posée.");
			}
		}
		markersChanged(e, d, before);
		if (seats) {
			refreshThrones(e, d);
		}
		return msg + " " + MapTally.of(v).counter() + ".";
	}

	/** Clic droit avec l'outil « Carte : main » : reposer ce qu'on porte, ou ouvrir le menu de ce qu'on vise. */
	private String handClick(ServerPlayer p, WorldEntry e, Draft d, BlockPos clicked) {
		Village v = d.v;
		UUID who = p.getUUID();
		BlockPos at = clicked.above();
		Carry held = carrying.get(who);
		if (held != null && held.hand() && held.world().equals(e.id())) {
			Village.Marks before = v.marks();
			if (held.hit().type() != Village.Mark.CENTER) {
				McHost.requireNear(v, at);
			}
			if (held.hit().type() == Village.Mark.CENTER) {
				moveCenter(v, clicked);
			} else {
				moveMarker(d, held.hit(), at);
			}
			carrying.remove(who);
			markersChanged(e, d, before);
			if (held.hit().type() == Village.Mark.SEAT) {
				refreshThrones(e, d);
			}
			return held.hit().label() + done(held.hit(), " déplacé") + " ici.";
		}
		Village.Hit h = v.markerAt(at, null, 1);
		if (h == null && Math.abs(v.center().getX() - clicked.getX()) <= 1 && Math.abs(v.center().getZ() - clicked.getZ()) <= 1
			&& Math.abs(v.center().getY() - clicked.getY()) <= 2) {
			h = new Village.Hit(Village.Mark.CENTER, 0);
		}
		if (h != null) {
			String extra = h.type() == Village.Mark.SEAT ? " (regarde vers le " + Village.compass(v.seatYaw(h.index())) + ")" : "";
			openObject(p, typeName(h.type()), h.index(), h.label() + extra);
			return "";
		}
		int s = editor == null ? -1 : editor.placedAt(clicked);
		if (s >= 0) {
			openObject(p, "structure", s, editor.placedName(s));
			return "";
		}
		throw new GameException("Rien à prendre ici : visez un trône, une maison, la salle, le centre, "
			+ "ou une structure posée depuis le catalogue.");
	}

	/** Le menu d'un objet : l'écran du mod, ou à défaut des liens dans le chat (mêmes commandes que dans le monde de la partie). */
	private static void openObject(ServerPlayer p, String type, int index, String label) {
		if (ViewSync.hasClientMod(p)) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p,
				new fr.valcombe.net.ValcombeNet.OpenPayload("objet:" + type + ":" + index + ":" + label));
			return;
		}
		List<String[]> actions = new ArrayList<>();
		actions.add(new String[] {"Déplacer", "deplacer"});
		if (type.equals("trone")) {
			actions.add(new String[] {"⟲", "gauche"});
			actions.add(new String[] {"⟳", "droite"});
			actions.add(new String[] {"Vers le centre", "centre"});
		}
		if (type.equals("structure")) {
			actions.add(new String[] {"Tourner", "tourner"});
			actions.add(new String[] {"Retourner", "retourner"});
		}
		if (!type.equals("centre")) {
			actions.add(new String[] {"Retirer", "retirer"});
		}
		MutableComponent c = Component.literal(label + " : ").withStyle(ChatFormatting.GOLD);
		for (String[] a : actions) {
			c.append(link("[" + a[0] + "] ", "/vc objet " + type + " " + index + " " + a[1], a[0]));
		}
		p.sendSystemMessage(c);
	}

	/** Une action du menu d'un objet (repère ou structure posée), dans un atelier carte. */
	public String objectAction(ServerPlayer p, String type, int index, String action) {
		WorldEntry e = entry(p.level());
		if (e == null) {
			throw new GameException("Ce menu sert dans un atelier carte.");
		}
		Draft d = editable(p, e);
		if (type.equals("structure")) {
			if (editor == null) {
				throw new GameException("L'éditeur de carte n'est pas prêt.");
			}
			return editor.actOnPlaced(p, index, action);
		}
		Village v = d.v;
		Village.Mark mark = markOf(type);
		boolean numbered = mark == Village.Mark.SEAT || mark == Village.Mark.HOUSE;
		if (numbered ? index < 0 || index >= (mark == Village.Mark.SEAT ? v.seatCount() : v.houseCount())
			: mark == Village.Mark.ROOM && v.convocation() == null) {
			throw new GameException("Ce repère n'existe plus : visez-le de nouveau.");
		}
		Village.Hit h = new Village.Hit(mark, numbered ? index : 0);
		switch (action) {
			case "deplacer" -> {
				carrying.put(p.getUUID(), new Carry(e.id(), h, true));
				ValcombeUi.holdMapTool(p, ValcombeUi.MapTool.HAND);
				return h.label() + " en main : clic droit (outil « Carte : main ») là où le reposer.";
			}
			case "gauche", "droite", "centre" -> {
				if (mark != Village.Mark.SEAT) {
					throw new GameException("Seuls les trônes se tournent.");
				}
				if (action.equals("centre")) {
					v.faceCenter(h.index());
				} else {
					v.turnSeat(h.index(), action.equals("droite") ? 1 : -1);
				}
				markersChanged(e, d, null);
				refreshThrones(e, d);
				return h.label() + " regarde vers le " + Village.compass(v.seatYaw(h.index()))
					+ (action.equals("centre") ? " (le centre de la place)." : ".");
			}
			case "retirer" -> {
				if (mark == Village.Mark.CENTER) {
					throw new GameException("Le centre de la place ne se retire pas : déplacez-le.");
				}
				Village.Marks before = v.marks();
				BlockPos pos = v.markerPos(h);
				v.removeMarker(h);
				var built = d.prefabs.remove(pos);
				if (built != null) {
					built.forEach((b, s) -> p.level().setBlock(b, s, Clip.EXACT));
				}
				markersChanged(e, d, before);
				if (mark == Village.Mark.SEAT) {
					refreshThrones(e, d);
				}
				return h.label() + done(h, " retiré") + (built != null ? ", et sa construction avec." : ".");
			}
			default -> throw new GameException("Action inconnue : " + action + ".");
		}
	}

	/** {@code /vc carte trone} (ou {@code maison}, {@code centre}) dans un atelier : le repère là où l'on se tient. */
	public String markHere(ServerPlayer p, String type) {
		WorldEntry e = here(p);
		Draft d = editable(p, e);
		Village v = d.v;
		Village.Marks before = v.marks();
		BlockPos at = p.blockPosition();
		String msg;
		switch (type) {
			case "trone" -> {
				requireRoom(v, true);
				McHost.requireNear(v, at);
				v.addSeat(at);
				msg = "Trône " + v.seatCount() + " posé.";
			}
			case "maison" -> {
				requireRoom(v, false);
				McHost.requireNear(v, at);
				v.addHouse(at);
				msg = "Maison " + v.houseCount() + " posée.";
			}
			case "salle" -> {
				McHost.requireNear(v, at);
				v.setConvocation(at);
				msg = "Salle de convocation posée ici.";
			}
			default -> {
				moveCenter(v, at.below());
				msg = "Centre de la place posé ici.";
			}
		}
		markersChanged(e, d, before);
		if (type.equals("trone")) {
			refreshThrones(e, d);
		}
		return msg + " " + MapTally.of(v).counter() + ".";
	}

	/** {@code /vc carte retirer trone|maison} dans un atelier : le dernier posé. */
	public String removeLast(ServerPlayer p, boolean seat) {
		WorldEntry e = here(p);
		Draft d = editable(p, e);
		Village.Marks before = d.v.marks();
		if (!(seat ? d.v.removeLastSeat() : d.v.removeLastHouse())) {
			return seat ? "Aucun trône à retirer." : "Aucune maison à retirer.";
		}
		markersChanged(e, d, before);
		if (seat) {
			refreshThrones(e, d);
		}
		return (seat ? "Dernier trône retiré (" : "Dernière maison retirée (") + MapTally.of(d.v).counter() + ").";
	}

	/** Renumérote trônes et maisons dans le sens des aiguilles d'une montre, en partant du nord. */
	public String sortMarkers(ServerPlayer p) {
		WorldEntry e = here(p);
		Draft d = editable(p, e);
		Village.Marks before = d.v.marks();
		d.v.sortClockwise();
		markersChanged(e, d, before);
		refreshThrones(e, d);
		return "Trônes et maisons renumérotés dans le sens des aiguilles d'une montre, en partant du nord.";
	}

	/** {@code /vc carte infos} dans un atelier. */
	public String info(ServerPlayer p) {
		WorldEntry e = here(p);
		Draft d = draft(e);
		if (d.v == null) {
			return "La carte est en train d'être posée dans l'atelier « " + e.nom() + " ».";
		}
		MapTally t = MapTally.of(d.v);
		StringBuilder s = new StringBuilder("« " + mapName(e, d) + " » (identifiant « " + mapId(e) + " »), "
			+ (d.v.isGenerated() ? "village généré" : "carte faite à la main") + ", place en " + d.v.center().toShortString() + " : "
			+ t.counter() + ". " + t.verdict());
		for (MapTally.Line l : t.missing()) {
			if (!l.blocking()) {
				s.append(" ").append(l.text());
			}
		}
		s.append(" ").append(savedLine(e, d));
		return s.toString();
	}

	/** « Enregistrée le 13/11 à 14h05, rien de changé depuis. » / « Jamais enregistrée. » */
	private String savedLine(WorldEntry e, Draft d) {
		if (!maps.exists(mapId(e))) {
			return "Pas encore dans la bibliothèque : « Enregistrer » l'y range.";
		}
		MapVersions.Version last = maps.versions().last(mapId(e));
		String when = d.savedAt != null ? WHEN.format(d.savedAt) : last != null ? last.shown() : null;
		return (when == null ? "Dans la bibliothèque" : "Enregistrée le " + when) + (d.changed ? ", modifiée depuis." : ".");
	}

	/** Combien de modifications des repères on peut annuler, tous ateliers confondus (pour l'« annuler » commun). */
	int markerUndoDepth() {
		int n = 0;
		for (Draft d : drafts.values()) {
			n += d.undo.size();
		}
		return n;
	}

	/** Combien de modifications des repères on peut annuler dans l'atelier de ce niveau. */
	int markerUndoDepth(Level level) {
		WorldEntry e = entry(level);
		Draft d = e == null ? null : drafts.get(e.id());
		return d == null ? 0 : d.undo.size();
	}

	/** Annule la dernière modification des repères de l'atelier où se tient ce joueur. */
	public String undoMarker(ServerPlayer p) {
		WorldEntry e = here(p);
		Draft d = editable(p, e);
		if (d.undo.isEmpty()) {
			return "Rien à annuler : aucun repère modifié depuis l'ouverture de cet atelier.";
		}
		d.v.restore(d.undo.pop());
		Village.Marks now = d.v.marks();
		ServerLevel level = worlds.level(e.id());
		d.prefabs.entrySet().removeIf(en -> {
			BlockPos at = en.getKey();
			if (now.seats().contains(at) || now.houses().contains(at) || at.equals(now.convocation())) {
				return false;
			}
			en.getValue().forEach((b, s) -> level.setBlock(b, s, Clip.EXACT));
			return true;
		});
		carrying.values().removeIf(c -> c.world().equals(e.id()));
		markersChanged(e, d, null);
		refreshThrones(e, d);
		return "Dernière modification des repères annulée (" + MapTally.of(d.v).counter() + ").";
	}

	/** L'éditeur a posé ou retiré un repère avec une structure (maison, salle) : il a sa propre annulation. */
	void editorMarkersChanged(Level level) {
		WorldEntry e = entry(level);
		Draft d = e == null ? null : drafts.get(e.id());
		if (d != null && d.v != null) {
			d.undo.clear();
			markersChanged(e, d, null);
		}
	}

	/** Un bloc a été posé ou cassé (à la main, par l'éditeur) dans un atelier carte : la carte a changé. */
	void noteEdit(Level level) {
		WorldEntry e = entry(level);
		Draft d = e == null ? null : drafts.get(e.id());
		if (d != null) {
			d.changed = true;
		}
	}

	/** Pour les commandes : dans un atelier carte, l'atelier ; ailleurs, ce qu'on faisait jusque-là. */
	static String inAtelierOr(ServerPlayer p, Function<MapWorkshop, String> atelier, Supplier<String> otherwise) {
		MapWorkshop w = ValcombeMod.mapWorkshop();
		if (p != null && w != null && w.isAtelier(p.level())) {
			return atelier.apply(w);
		}
		return otherwise.get();
	}

	/** Pour les commandes : la source est un joueur invité dans l'atelier carte où il se tient ? */
	static boolean guestHere(net.minecraft.commands.CommandSourceStack s) {
		MapWorkshop w = ValcombeMod.mapWorkshop();
		ServerPlayer p = s.getPlayer();
		return w != null && p != null && w.buildsHere(p);
	}

	/** Ce joueur est-il dans un atelier carte où il peut construire ? */
	public boolean buildsHere(ServerPlayer p) {
		WorldEntry e = entry(p.level());
		return e != null && BuildAccess.mayBuildIn(p, e);
	}

	// ------------------------------------------------------------------
	// Chaque tick : arrivées, enregistrement automatique, ce qu'on voit
	// ------------------------------------------------------------------

	public void tick() {
		ticks++;
		if (ticks % 10 == 0) {
			watchPlayers();
		}
		if (ticks % 20 == 5) {
			for (WorldEntry e : ateliers()) {
				ServerLevel level = server.getLevel(WorldManager.key(e.id()));
				if (level == null || worlds.busy(e.id())) {
					continue;
				}
				Draft d = drafts.get(e.id());
				if (d == null && level.players().isEmpty()) {
					// Un atelier où personne ne va n'est pas relu ; sauf une pose coupée par un arrêt du serveur, qui reprend dès
					// le démarrage (une seule lecture de son état par atelier).
					if (probed.add(e.id()) && pendingPose(e)) {
						draft(e);
					}
					continue;
				}
				d = draft(e);
				if (!level.players().isEmpty()) {
					d.visited = true;
					trackReach(d, level);
					counter(e, d, level);
				}
				// À intervalle : seulement après un changement vu (blocs, WorldEdit, repères, structures). L'arrêt du serveur et la
				// fermeture comparent aussi l'empreinte après une simple visite (un seau vidé ne se voit pas autrement).
				if (d.changed && ticks - d.lastSave >= autosaveTicks && d.v != null && !placing(e)) {
					try {
						autosave(e.id(), MapVersions.Kind.AUTO);
					} catch (RuntimeException ex) {
						// Un nouvel essai au prochain intervalle (et non à chaque seconde, journal compris).
						d.lastSave = ticks;
						if (!(ex instanceof GameException)) {
							ValcombeMod.LOG.error("Enregistrement automatique de l'atelier carte « " + e.nom() + " » en échec", ex);
						}
						tellWorld(level, Gui.plain("Enregistrement automatique impossible : " + ex.getMessage(), ChatFormatting.RED));
					}
				}
			}
		}
		if (ticks % 20 == 3) {
			for (WorldEntry e : ateliers()) {
				ServerLevel level = server.getLevel(WorldManager.key(e.id()));
				Draft d = drafts.get(e.id());
				if (level != null && d != null && d.v != null && !level.players().isEmpty()) {
					outlines(level, d);
				}
			}
		}
		if (ticks % 40 == 7) {
			for (WorldEntry e : ateliers()) {
				ServerLevel level = server.getLevel(WorldManager.key(e.id()));
				Draft d = drafts.get(e.id());
				if (level != null && d != null && !level.players().isEmpty()) {
					labels(e, level, d);
				}
			}
		}
	}

	/**
	 * Les joueurs entrés dans un atelier carte reçoivent les outils de repères et un mot d'accueil ; ceux qui en sortent
	 * les rendent (sauf s'ils sont en mode construction). Une modification WorldEdit faite dans un atelier compte comme un
	 * changement de la carte.
	 */
	private void watchPlayers() {
		Set<UUID> seen = new HashSet<>();
		WorldEditGate gate = WorldEditGate.current();
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
			WorldEntry e = entry(p.level());
			UUID id = p.getUUID();
			Integer we = null;
			if (gate != null) {
				List<Object> h = gate.link().history(p);
				we = h.isEmpty() ? 0 : 31 * System.identityHashCode(h.get(h.size() - 1)) + h.size();
			}
			Integer before = we == null ? null : worldEditSeen.put(id, we);
			if (e == null || !BuildAccess.mayBuildIn(p, e)) {
				continue;
			}
			seen.add(id);
			Draft d = drafts.get(e.id());
			if (d != null && before != null && !before.equals(we) && e.id().equals(inside.get(id))) {
				d.changed = true;
			}
			String was = inside.put(id, e.id());
			if (!e.id().equals(was)) {
				welcome(p, e);
			}
		}
		for (var it = inside.entrySet().iterator(); it.hasNext(); ) {
			var en = it.next();
			if (seen.contains(en.getKey())) {
				continue;
			}
			it.remove();
			carrying.remove(en.getKey());
			ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
			if (p != null && !McHost.isBuilder(p.getScoreboardName())) {
				ValcombeUi.mapTools(p, false);
			}
		}
	}

	/** Le mot d'accueil d'un atelier carte, et ses outils de repères. */
	private void welcome(ServerPlayer p, WorldEntry e) {
		boolean has = false;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize() && !has; i++) {
			has = ValcombeUi.mapTool(inv.getItem(i)) != null;
		}
		if (!has) {
			ValcombeUi.mapTools(p, true);
		}
		Draft d = draft(e);
		String nom = mapName(e, d);
		MutableComponent c = Component.literal("Atelier de la carte « " + nom + " » : bâtissez avec WorldEdit et à la main ; les outils "
			+ "« Carte : ... » posent les trônes, les maisons, le centre et la salle de convocation. Enregistrement automatique toutes les "
			+ AUTOSAVE_MINUTES + " minutes. ").withStyle(ChatFormatting.GREEN);
		if (BuildAccess.operator(p)) {
			c.append(link("[Atelier de la carte]", "/vc atelier carte", "L'écran de l'atelier : enregistrer, versions, envoyer, fermer"));
			c.append(Component.literal(" ")).append(link("[Enregistrer]", "/vc atelier carte enregistrer", "Ranger la carte dans la bibliothèque"));
		} else {
			// Les commandes /vc atelier sont aux opérateurs : un invité ouvre l'écran de l'atelier par le menu.
			c.append(Component.literal("Le compte des repères : Menu J › Mondes › « Atelier de la carte ».").withStyle(ChatFormatting.GRAY));
		}
		p.sendSystemMessage(c);
	}

	/** Jusqu'où les constructeurs vont depuis le centre : c'est là qu'on cherchera ce qu'ils ont bâti. */
	private void trackReach(Draft d, ServerLevel level) {
		if (d.v == null) {
			return;
		}
		BlockPos c = d.v.center();
		for (ServerPlayer p : level.players()) {
			int r = Math.max(Math.abs(p.getBlockX() - c.getX()), Math.abs(p.getBlockZ() - c.getZ()));
			if (r > d.reach && r <= MapStore.MAX_RADIUS) {
				d.reach = r;
			}
		}
	}

	/** Le compte des repères au-dessus de la barre d'objets de ceux qui sont dans l'atelier. */
	private void counter(WorldEntry e, Draft d, ServerLevel level) {
		for (ServerPlayer p : level.players()) {
			String status = editor == null ? null : editor.status(p);
			if (status != null) {
				p.sendSystemMessage(Component.literal(status).withStyle(ChatFormatting.AQUA), true);
				continue;
			}
			if (d.v == null) {
				p.sendSystemMessage(Component.literal("Pose de la carte en cours…").withStyle(ChatFormatting.GRAY), true);
				continue;
			}
			MapTally t = MapTally.of(d.v);
			Carry held = carrying.get(p.getUUID());
			String prefix = held != null && held.world().equals(e.id()) ? held.hit().label() + " en main · " : "";
			String tail = t.playable() ? " · jouable ✔" : " · il manque " + MapTally.plural(t.missingSeats(), "trône", "trônes");
			p.sendSystemMessage(Component.literal(prefix + t.counter() + tail)
				.withStyle(t.playable() ? ChatFormatting.GREEN : ChatFormatting.GOLD), true);
		}
	}

	/** Contours au sol des repères (et de la zone de la carte), en particules visibles de ceux qui sont tout près. */
	private void outlines(ServerLevel level, Draft d) {
		Village v = d.v;
		for (ServerPlayer p : level.players()) {
			for (int i = 0; i < v.seatCount(); i++) {
				square(p, v.markerPos(new Village.Hit(Village.Mark.SEAT, i)), GOLD, 0.5);
			}
			for (int i = 0; i < v.houseCount(); i++) {
				square(p, v.markerPos(new Village.Hit(Village.Mark.HOUSE, i)), GREEN, 0.5);
			}
			if (v.convocation() != null) {
				square(p, v.convocation(), PURPLE, 0.7);
			}
			BlockPos c = v.center();
			if (near(p, c, 64)) {
				for (int k = 0; k < 6; k++) {
					level.sendParticles(p, net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME, true, true,
						c.getX() + 0.5, c.getY() + 1.4 + k * 0.6, c.getZ() + 0.5, 1, 0.03, 0.08, 0.03, 0);
				}
			}
			if (ticks % 40 == 3) {
				border(p, c, Math.min(MapStore.MAX_RADIUS, Math.max(4, v.extent())));
			}
		}
	}

	private static boolean near(ServerPlayer p, BlockPos b, int reach) {
		return Math.abs(p.getX() - b.getX()) <= reach && Math.abs(p.getZ() - b.getZ()) <= reach && Math.abs(p.getY() - b.getY()) <= 48;
	}

	/** Un carré de particules autour du bloc où l'on se tient sur ce repère. */
	private static void square(ServerPlayer p, BlockPos at, DustParticleOptions dust, double half) {
		if (!near(p, at, 32)) {
			return; // plus loin, l'étiquette suffit (et le joueur ne reçoit pas des centaines de particules)
		}
		ServerLevel level = p.level();
		double x = at.getX() + 0.5;
		double y = at.getY() + 0.08;
		double z = at.getZ() + 0.5;
		for (double t = -half; t <= half + 1e-6; t += half) {
			level.sendParticles(p, dust, true, true, x + t, y, z - half, 1, 0, 0, 0, 0);
			level.sendParticles(p, dust, true, true, x + t, y, z + half, 1, 0, 0, 0, 0);
			level.sendParticles(p, dust, true, true, x - half, y, z + t, 1, 0, 0, 0, 0);
			level.sendParticles(p, dust, true, true, x + half, y, z + t, 1, 0, 0, 0, 0);
		}
	}

	/** Le bord de la zone de la carte, au sol, sur les côtés proches du joueur (au plus 40 blocs de part et d'autre). */
	private static void border(ServerPlayer p, BlockPos c, int r) {
		ServerLevel level = p.level();
		double y = c.getY() + 1.1;
		int x0 = c.getX() - r;
		int x1 = c.getX() + r + 1;
		int z0 = c.getZ() - r;
		int z1 = c.getZ() + r + 1;
		int px = p.getBlockX();
		int pz = p.getBlockZ();
		for (int t = Math.max(x0, px - 40); t <= Math.min(x1, px + 40); t += 2) {
			for (int z : new int[] {z0, z1}) {
				if (Math.abs(z - pz) <= 40) {
					level.sendParticles(p, BORDER, true, true, t, y, z, 1, 0, 0, 0, 0);
				}
			}
		}
		for (int t = Math.max(z0, pz - 40); t <= Math.min(z1, pz + 40); t += 2) {
			for (int x : new int[] {x0, x1}) {
				if (Math.abs(x - px) <= 40) {
					level.sendParticles(p, BORDER, true, true, x, y, t, 1, 0, 0, 0, 0);
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Étiquettes flottantes : chaque repère, et le panneau de la place
	// ------------------------------------------------------------------

	private static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
	}

	/** Un texte en plusieurs morceaux : (texte, couleur, gras) répétés. */
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

	/** Une étiquette voulue : son groupe (une par repère), sa place et son texte. */
	private record Label(String group, double x, double y, double z, double scale, boolean through, String text) {
	}

	/** Les étiquettes de la carte de cet atelier : le panneau de la place, puis chaque repère. */
	private List<Label> wanted(WorldEntry e, Draft d) {
		List<Label> out = new ArrayList<>();
		Village v = d.v;
		if (v == null) {
			return out;
		}
		MapTally t = MapTally.of(v);
		BlockPos c = v.center();
		String verdict = t.playable() ? "Jouable" : "Il manque " + MapTally.plural(t.missingSeats(), "trône", "trônes") + " pour jouer";
		out.add(new Label(TAG + "_place", c.getX() + 0.5, c.getY() + 4.6, c.getZ() + 0.5, 1.3, false,
			comp(mapName(e, d), "gold", true, "\n" + t.counter(), "white", false, "\n" + verdict, t.playable() ? "green" : "red", true,
				"\n" + (d.changed ? "Modifiée depuis le dernier enregistrement" : maps.exists(mapId(e)) ? "Enregistrée"
					: "Pas encore dans la bibliothèque"), "gray", false)));
		out.add(new Label(TAG + "_c", c.getX() + 0.5, c.getY() + 2.3, c.getZ() + 0.5, 1.0, true, comp("✚ Centre de la place", "aqua", true)));
		for (int i = 0; i < v.seatCount(); i++) {
			BlockPos b = v.markerPos(new Village.Hit(Village.Mark.SEAT, i));
			out.add(new Label(TAG + "_s" + i, b.getX() + 0.5, b.getY() + 2.3, b.getZ() + 0.5, 1.0, true,
				comp("♛ Trône " + (i + 1), "gold", true, i < v.houseCount() ? "" : "\nsans maison", "gray", false)));
		}
		for (int i = 0; i < v.houseCount(); i++) {
			BlockPos b = v.markerPos(new Village.Hit(Village.Mark.HOUSE, i));
			out.add(new Label(TAG + "_h" + i, b.getX() + 0.5, b.getY() + 2.3, b.getZ() + 0.5, 1.0, true, comp("⌂ Maison " + (i + 1), "green", true)));
		}
		if (v.convocation() != null) {
			BlockPos b = v.convocation();
			out.add(new Label(TAG + "_r", b.getX() + 0.5, b.getY() + 2.3, b.getZ() + 0.5, 1.0, true,
				comp("✉ Salle de convocation", "light_purple", true)));
		}
		return out;
	}

	/** Met les étiquettes de l'atelier d'accord avec ses repères, près de ceux qui s'y trouvent. */
	private void labels(WorldEntry e, ServerLevel level, Draft d) {
		List<ServerPlayer> players = level.players();
		Map<String, Label> want = new HashMap<>();
		for (Label l : wanted(e, d)) {
			want.put(l.group(), l);
		}
		// Les étiquettes en trop (repère retiré, déplacé, texte changé) partent ; les manquantes sont posées.
		Set<Entity> looked = new HashSet<>();
		Set<String> present = new HashSet<>();
		for (ServerPlayer pl : players) {
			for (Entity en : level.getEntities((Entity) null, pl.getBoundingBox().inflate(128, 96, 128), x -> x.entityTags().contains(TAG))) {
				if (!looked.add(en)) {
					continue; // déjà vue autour d'un autre joueur
				}
				Label l = want.get(groupOf(en));
				// La première étiquette juste d'un groupe reste ; un double, un texte périmé ou un repère déplacé part.
				boolean ok = l != null && en.entityTags().contains(key(l)) && Math.abs(en.getX() - l.x()) < 0.3
					&& Math.abs(en.getY() - l.y()) < 0.3 && Math.abs(en.getZ() - l.z()) < 0.3 && present.add(l.group());
				if (!ok) {
					en.discard();
				}
			}
		}
		for (Label l : want.values()) {
			BlockPos at = BlockPos.containing(l.x(), l.y(), l.z());
			boolean seen = false;
			for (ServerPlayer pl : players) {
				seen |= Math.abs(pl.getX() - l.x()) <= 112 && Math.abs(pl.getZ() - l.z()) <= 112;
			}
			if (seen && !present.contains(l.group()) && level.isLoaded(at)) {
				WorldSetup.run(level, String.format(Locale.ROOT,
					"summon minecraft:text_display %.3f %.3f %.3f {Tags:[\"%s\",\"%s\",\"%s\"],billboard:\"center\",line_width:240,"
						+ "see_through:%s,view_range:%s,background:-1072689136,shadow:1b,brightness:{sky:15,block:15},"
						+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],"
						+ "scale:[%.2ff,%.2ff,%.2ff]},text:%s}",
					l.x(), l.y(), l.z(), TAG, l.group(), key(l), l.through() ? "1b" : "0b", l.group().endsWith("_place") ? "3.0f" : "1.2f",
					l.scale(), l.scale(), l.scale(), l.text()));
			}
		}
	}

	private static String key(Label l) {
		return l == null ? "" : TAG + "k_" + Integer.toHexString((l.text() + "|" + l.scale()).hashCode());
	}

	private static String groupOf(Entity en) {
		for (String t : en.entityTags()) {
			if (t.startsWith(TAG + "_")) {
				return t;
			}
		}
		return "";
	}

	// ------------------------------------------------------------------
	// Ce que voient les écrans
	// ------------------------------------------------------------------

	/** Note le résultat d'une action, pour l'écran de l'atelier. */
	void note(ServerPlayer p, String text, boolean error) {
		if (text != null && !text.isBlank()) {
			replies.put(p.getUUID(), new Reply(++replyCount, text.length() > 400 ? text.substring(0, 400) + "…" : text, error));
		}
	}

	/** Ce que l'écran de l'atelier reçoit, pour un joueur qui s'y trouve (joint à sa vue, sous « atelierCarte »). */
	public Map<String, Object> view(ServerPlayer p) {
		Map<String, Object> m = new LinkedHashMap<>();
		WorldEntry e = entry(p.level());
		if (e == null) {
			return m;
		}
		Draft d = draft(e);
		String id = mapId(e);
		m.put("monde", e.id());
		m.put("nom", mapName(e, d));
		m.put("carte", id);
		m.put("operateur", BuildAccess.operator(p));
		boolean busy = d.v == null || placing(e);
		m.put("pose", busy);
		boolean stored = maps.exists(id);
		m.put("enregistree", stored);
		m.put("modifiee", d.changed);
		m.put("etat", savedLine(e, d));
		int left = Math.max(0, autosaveTicks - (ticks - d.lastSave));
		m.put("autoDans", d.changed ? (left + 1199) / 1200 : -1);
		if (d.v != null) {
			MapTally t = MapTally.of(d.v);
			m.put("compteur", t.counter());
			m.put("trones", t.seats());
			m.put("places", t.places());
			m.put("maisons", t.houses());
			m.put("salle", t.room());
			m.put("jouable", t.playable());
			m.put("verdict", t.verdict());
			List<Map<String, Object>> lines = new ArrayList<>();
			for (MapTally.Line l : t.missing()) {
				lines.add(Map.of("bloquant", l.blocking(), "texte", l.text()));
			}
			m.put("manques", lines);
			m.put("genere", d.v.isGenerated());
			m.put("theme", d.v.theme());
		}
		List<Map<String, Object>> versions = new ArrayList<>();
		for (MapVersions.Version v : maps.versions().list(id)) {
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("id", v.id());
			o.put("date", v.shown());
			o.put("sorte", v.kind().label());
			o.put("auto", v.kind().automatic());
			o.put("trones", v.seats());
			o.put("maisons", v.houses());
			versions.add(o);
		}
		m.put("versions", versions);
		List<Map<String, Object>> parties = new ArrayList<>();
		for (WorldEntry w : parties()) {
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("id", w.id());
			o.put("nom", w.nom());
			o.put("sorte", w.sorte().label());
			o.put("principale", w.id().equals(mainGame.get()));
			o.put("carte", w.carte());
			parties.add(o);
		}
		m.put("parties", parties);
		Reply r = replies.get(p.getUUID());
		if (r != null) {
			m.put("message", Map.of("n", r.n(), "texte", r.text(), "erreur", r.error()));
		}
		return m;
	}

	/**
	 * Pour le menu « Mondes » et l'écran « Ouvrir une carte dans un atelier » d'un opérateur (joint à sa vue, sous
	 * « ateliersCarte ») : les ateliers ouverts, les cartes de la bibliothèque, les réglages d'un village généré. Null
	 * pour qui n'est pas opérateur.
	 */
	public Map<String, Object> listView(ServerPlayer p) {
		if (!BuildAccess.operator(p)) {
			return null;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		List<Map<String, Object>> list = new ArrayList<>();
		WorldEntry here = entry(p.level());
		for (WorldEntry e : ateliers()) {
			Map<String, Object> o = new LinkedHashMap<>();
			Draft d = drafts.get(e.id());
			o.put("monde", e.id());
			o.put("nom", e.nom());
			o.put("carte", e.carte());
			o.put("enregistree", e.carte() != null && maps.exists(e.carte()));
			o.put("modifiee", d != null && d.changed);
			ServerLevel level = server.getLevel(WorldManager.key(e.id()));
			o.put("joueurs", level == null ? 0 : level.players().size());
			o.put("ici", here != null && here.id().equals(e.id()));
			// Pose ou construction en cours (carte, village, import) : rien ne s'y enregistre encore.
			o.put("pose", d == null ? pendingPose(e) : d.v == null || placing(e));
			list.add(o);
		}
		m.put("ateliers", list);
		List<Map<String, Object>> cards = new ArrayList<>();
		for (MapStore.MapDef def : maps.list()) {
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("id", def.id);
			o.put("nom", def.nom);
			o.put("sieges", def.sieges.size());
			o.put("maisons", def.logis.size());
			o.put("genere", def.genere);
			o.put("theme", def.theme);
			WorldEntry a = atelierOf(def.id);
			o.put("atelier", a == null ? null : a.id());
			cards.add(o);
		}
		m.put("cartes", cards);
		m.put("themes", Village.THEMES);
		m.put("tronesMin", Village.MIN_SEATS);
		m.put("tronesMax", Village.MAX_SEATS);
		m.put("ici", here == null ? null : here.id());
		return m;
	}

	/** Pour la bibliothèque (Atelier web, écran en jeu) : l'atelier qui porte cette carte, ou null. */
	public String atelierIdOf(String mapId) {
		WorldEntry a = atelierOf(mapId);
		return a == null ? null : a.id();
	}

	// ------------------------------------------------------------------
	// Liens, messages
	// ------------------------------------------------------------------

	static MutableComponent link(String text, String cmd, String hover) {
		return Component.literal(text).withStyle(st -> st.withColor(ChatFormatting.AQUA).withUnderlined(true)
			.withClickEvent(new ClickEvent.RunCommand(cmd)).withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	private static void tellWorld(ServerLevel level, Component text) {
		for (ServerPlayer p : level.players()) {
			p.sendSystemMessage(text);
		}
	}

	/** La liste des ateliers carte dans le chat, chacun avec un lien pour y aller. */
	public List<Component> list(ServerPlayer p) {
		List<Component> out = new ArrayList<>();
		List<WorldEntry> all = ateliers();
		if (all.isEmpty()) {
			out.add(Gui.plain("Aucun atelier carte ouvert. Ouvrir une carte : /vc atelier carte ouvrir <id>, nouvelle <nom> ou village "
				+ "<trônes> <taille> <thème> <maisons> <nom>.", ChatFormatting.GRAY));
			return out;
		}
		out.add(Component.literal("Ateliers carte (" + all.size() + ") :").withStyle(ChatFormatting.GOLD));
		for (WorldEntry e : all) {
			ServerLevel level = server.getLevel(WorldManager.key(e.id()));
			int n = level == null ? 0 : level.players().size();
			Draft d = drafts.get(e.id());
			out.add(Component.literal("  « " + e.nom() + " » · carte " + e.carte() + (e.carte() != null && maps.exists(e.carte()) ? "" : " (jamais enregistrée)")
				+ (d != null && d.changed ? " · modifiée" : "") + (n > 0 ? " · " + MapTally.plural(n, "joueur", "joueurs") : "") + " ")
				.withStyle(ChatFormatting.WHITE).append(link("[Y aller]", "/vc atelier carte aller " + e.id(), "Aller dans cet atelier")));
		}
		return out;
	}

	// ------------------------------------------------------------------
	// Mondes remis à neuf ou supprimés, arrêt du serveur
	// ------------------------------------------------------------------

	/**
	 * Un atelier carte remis à neuf ou supprimé autrement que par « Fermer l'atelier » ({@code /vc monde supprimer},
	 * l'API d'administration) : son travail est d'abord enregistré s'il a changé. Si c'est impossible, on refuse en le disant
	 * (rien ne se perd sans qu'on l'ait voulu).
	 */
	@Override
	public String refuse(WorldEntry e, boolean recreate) {
		if (e.sorte() != WorldKind.ATELIER_CARTE) {
			return null;
		}
		Draft d = drafts.get(e.id());
		if (d == null || d.v == null || placing(e) || !(d.changed || d.visited)) {
			return null;
		}
		try {
			autosave(e.id(), MapVersions.Kind.FERMETURE);
			return null;
		} catch (RuntimeException ex) {
			return "L'atelier « " + e.nom() + " » a des changements qui n'ont pas pu être enregistrés (" + ex.getMessage() + ") : "
				+ "enregistrez-les d'abord (/vc atelier carte enregistrer), ou fermez l'atelier depuis son écran.";
		}
	}

	@Override
	public void recreated(WorldEntry entry, ServerLevel level) {
		if (entry.sorte() == WorldKind.ATELIER_CARTE) {
			// Remis à neuf (/vc monde recreer) : ses fichiers sont partis ; la carte de la bibliothèque y sera reposée.
			drafts.remove(entry.id());
			carrying.values().removeIf(c -> c.world().equals(entry.id()));
		}
	}

	@Override
	public void deleted(WorldEntry entry) {
		drafts.remove(entry.id());
		carrying.values().removeIf(c -> c.world().equals(entry.id()));
	}

	/**
	 * À l'arrêt du serveur, avant que les mondes ne se ferment : chaque atelier où il s'est passé quelque chose depuis son
	 * dernier enregistrement est enregistré (version « à l'arrêt du serveur »).
	 */
	public void stop() {
		for (WorldEntry e : ateliers()) {
			Draft d = drafts.get(e.id());
			if (d == null || d.v == null || !(d.changed || d.visited) || placing(e) || worlds.busy(e.id())) {
				continue;
			}
			try {
				String said = autosave(e.id(), MapVersions.Kind.ARRET);
				if (!said.isEmpty()) {
					ValcombeMod.LOG.info("Atelier carte « {} » enregistré à l'arrêt du serveur.", e.nom());
				}
			} catch (RuntimeException ex) {
				ValcombeMod.LOG.error("Atelier carte « " + e.nom() + " » non enregistré à l'arrêt du serveur", ex);
			}
		}
		drafts.clear();
		carrying.clear();
		inside.clear();
	}
}
