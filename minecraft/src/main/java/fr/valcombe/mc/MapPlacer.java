package fr.valcombe.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.GameException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Pose une carte de la bibliothèque (structure et repères, voir MapStore) dans n'importe quel monde de Valcombe, et
 * fait l'inverse : capture la carte d'un monde (zone bâtie et repères) dans la bibliothèque. C'est la base de
 * « Envoyer à la partie » et de l'atelier carte.
 * <p>
 * <b>Poser</b> ({@link #place}) : l'ancienne carte du monde (s'il en porte une) et l'emprise de la nouvelle sont
 * remises à plat (le sol plat des mondes de Valcombe), puis la structure est posée, le centre de la place sur le bloc
 * demandé, le plus souvent sur le sol plat ({@link WorldManager#SURFACE_Y}). Les blocs partent par petits lots à
 * chaque tick (BuildQueue) : le serveur reste fluide, une barre suit l'avancement pour les joueurs du monde. Les
 * repères posés, en coordonnées du monde, sont rendus aussitôt ({@link Placement#map()}) et le résultat complété
 * quand tout est en place. Rien n'est changé à la partie en cours : le jeu s'en servira quand il aura son monde.
 * <p>
 * <b>Mémoire</b> : chaque monde retient la carte qu'il porte (fichier {@value #FILE} dans son dossier). Il disparaît
 * avec le monde quand celui-ci est remis à neuf : un monde neuf ne porte rien.
 * <p>
 * <b>Capturer</b> ({@link #capture}) : la zone bâtie est retrouvée en comparant le monde à son sol plat d'origine,
 * jusqu'à MapStore.MAX_RADIUS blocs du centre ; les repères sont ceux de la carte que porte le monde (ou ceux que
 * donne l'appelant, pour l'atelier). Le tout est rangé dans la bibliothèque comme une carte enregistrée.
 * <p>
 * Tout se fait sur le fil du serveur.
 */
public final class MapPlacer {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/** Fichier de la carte que porte un monde, dans le dossier de ce monde. */
	static final String FILE = "valcombe-carte.json";
	/** Il faut au moins ce nombre de trônes pour qu'une carte se joue (comme à l'enregistrement de la carte actuelle). */
	static final int MIN_SEATS = 5;
	/** Remise à plat sans rien faire tomber (comme le rasage de McHost). */
	private static final int FLAT_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
	/** Décors en entités d'une carte (trônes sculptés, scène, panneaux...) : retirés avec elle. */
	private static final Set<String> DECOR = Set.of("vc_trone", "vc_scene", "valcombe_salle", "vc_presentoir", "vc_repere", "vc_chair");

	/**
	 * Une pose lancée.
	 *
	 * @param map  la carte et ses repères, en coordonnées du monde (connus dès le lancement)
	 * @param done complété sur le fil du serveur quand tout est posé (ou en échec si le monde est remis à neuf,
	 *             supprimé, ou si le serveur s'arrête entre-temps)
	 */
	public record Placement(PlacedMap map, CompletableFuture<PlacedMap> done) {
	}

	/**
	 * Une capture rangée dans la bibliothèque.
	 *
	 * @param map     la carte capturée, telle qu'elle est maintenant dans la bibliothèque (et dans le monde)
	 * @param cutTop  des blocs montaient plus haut que la plus grande hauteur enregistrée : ils ne sont pas rangés
	 * @param replaced une carte de la bibliothèque portait déjà cet identifiant : elle est remplacée
	 */
	public record Capture(PlacedMap map, boolean cutTop, boolean replaced) {
	}

	/** Une zone tenue chargée pendant une pose : son monde, son tronçon central et son rayon en tronçons. */
	private record Hold(ServerLevel level, ChunkPos at, int radius) {
	}

	private final MapStore maps;
	private final WorldManager worlds;
	private final MinecraftServer server;
	/** Les poses, l'une après l'autre (un seul chantier à la fois sur le serveur pour ces mondes). */
	private final BuildQueue queue = new BuildQueue();
	/** Zones tenues chargées, par pose en cours. */
	private final Map<Placement, List<Hold>> holds = new LinkedHashMap<>();
	private int ticks;

	public MapPlacer(MapStore maps, WorldManager worlds, MinecraftServer server) {
		this.maps = maps;
		this.worlds = worlds;
		this.server = server;
	}

	private void requireServerThread() {
		if (!server.isSameThread()) {
			throw new IllegalStateException("Les cartes se posent sur le fil du serveur.");
		}
	}

	/** La fiche du monde de Valcombe qu'est ce niveau ; une GameException si ce n'en est pas un, ou s'il est en cours de remise à neuf. */
	private WorldEntry entry(ServerLevel level) {
		WorldEntry e = worlds.entryOf(level);
		if (e == null) {
			throw new GameException("Les cartes de la bibliothèque se posent dans les mondes de Valcombe (/vc monde liste). "
				+ "Dans le monde principal : /vc carte charger <id>.");
		}
		if (worlds.busy(e.id()) || server.getLevel(level.dimension()) != level) {
			throw new GameException("Le monde « " + e.nom() + " » est en cours de remise à neuf : réessayez dans un instant.");
		}
		return e;
	}

	/** Une pose est-elle en cours (ou en attente) dans ce monde ? */
	public boolean busy(ServerLevel level) {
		return queue.busy(level);
	}

	// ------------------------------------------------------------------
	// Poser
	// ------------------------------------------------------------------

	/** Pose la carte mapId dans ce monde, la place au centre du sol plat (0, SURFACE_Y, 0). */
	public Placement place(String mapId, ServerLevel level) {
		return place(mapId, level, new BlockPos(0, WorldManager.SURFACE_Y, 0), null);
	}

	/**
	 * Pose la carte mapId dans ce monde de Valcombe, le centre de sa place en center (le bloc du sol : on marche un bloc
	 * au-dessus). L'ancienne carte du monde est retirée (son emprise remise à plat, ses décors en entités enlevés).
	 * Le joueur watcher (ou null) voit la barre d'avancement même s'il est dans un autre monde.
	 *
	 * @return la pose : les repères en coordonnées du monde tout de suite, et le résultat quand tout est posé
	 */
	public Placement place(String mapId, ServerLevel level, BlockPos center, ServerPlayer watcher) {
		requireServerThread();
		WorldEntry e = entry(level);
		if (e.sorte() == fr.valcombe.mc.worlds.WorldKind.HUB) {
			// Le hub porte la salle d'accueil (portes, dalles, mur des rôles) que le hub rebâtit lui-même : une carte
			// posée par-dessus l'écraserait. Les cartes vont dans les mondes de partie et les ateliers.
			throw new GameException("Le hub garde sa salle d'accueil : posez la carte dans un monde de partie ou un atelier.");
		}
		if (queue.busy(level)) {
			throw new GameException("Une carte est déjà en train d'être posée dans « " + e.nom() + " » : attendez la fin de la barre.");
		}
		MapStore.MapDef d = maps.checkedDef(mapId);
		PlacedMap map = PlacedMap.of(d, level.dimension(), center);
		checkInside(level, map);
		Clip clip = maps.readClip(d);
		PlacedMap old = placed(level);

		// Remise à plat de l'ancienne carte, puis de l'emprise de la nouvelle au-dessus de la place (le sous-sol et la
		// place, eux, sont entièrement dans la structure), puis la structure elle-même.
		BuildQueue.Plan plan = new BuildQueue.Plan();
		if (old != null) {
			plan.first(flatPart(old.min(), old.max()));
		}
		BlockPos above = center.above();
		if (above.getY() <= map.max().getY()) {
			plan.first(flatPart(new BlockPos(map.min().getX(), above.getY(), map.min().getZ()), map.max()));
		}
		plan.then(clip.plan(level, map.min(), false, false));

		CompletableFuture<PlacedMap> done = new CompletableFuture<>();
		Placement placement = new Placement(map, done);
		UUID viewer = watcher == null ? null : watcher.getUUID();
		String label = "Pose de « " + d.nom + " » dans « " + e.nom() + " »";
		queue.add(label, level, plan, () -> {
			holds.remove(placement);
			try {
				removeDecor(level, old, map);
				Village v = map.toVillage();
				v.placeThrones(level); // les trônes sculptés d'un village généré sont des entités : reposés ici
				liftPlayers(level, map);
				remember(level, e, map);
				done.complete(map);
			} catch (RuntimeException ex) {
				ValcombeMod.LOG.error("Fin de la pose de « " + d.id + " » dans " + e.id(), ex);
				done.completeExceptionally(ex instanceof GameException ? ex : new GameException("Pose inachevée : " + ex.getMessage()));
			}
		}, p -> p.level() == level || p.getUUID().equals(viewer), () -> {
			holds.remove(placement);
			done.completeExceptionally(new GameException("Pose de « " + d.nom + " » interrompue : le monde « " + e.nom()
				+ " » a été remis à neuf ou supprimé, ou le serveur s'arrête."));
		});
		// Les tronçons de l'emprise restent chargés pendant la pose : sans cela, chaque rangée les rechargerait tous.
		List<Hold> h = new ArrayList<>();
		h.add(hold(level, map));
		if (old != null) {
			h.add(hold(level, old));
		}
		holds.put(placement, h);
		h.forEach(MapPlacer::refresh);
		return placement;
	}

	/** Le monde doit contenir toute l'emprise de la carte (hauteur et bordure). */
	private static void checkInside(ServerLevel level, PlacedMap map) {
		if (level.isOutsideBuildHeight(map.min().getY()) || level.isOutsideBuildHeight(map.max().getY())) {
			throw new GameException("La carte « " + map.nom() + " » (" + map.hauteur() + " blocs de haut) ne tient pas dans la hauteur du monde à "
				+ "y = " + map.center().getY() + " : posez-la sur le sol plat (y = " + WorldManager.SURFACE_Y + ").");
		}
		if (!level.getWorldBorder().isWithinBounds(map.min()) || !level.getWorldBorder().isWithinBounds(map.max())) {
			throw new GameException("La carte « " + map.nom() + " » dépasse la bordure du monde.");
		}
	}

	private static Hold hold(ServerLevel level, PlacedMap map) {
		return new Hold(level, ChunkPos.containing(map.center()), map.rayon() / 16 + 2);
	}

	private static void refresh(Hold h) {
		h.level().getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, h.at(), h.radius());
	}

	/** Le sol plat d'un monde de Valcombe à cette hauteur : bedrock, pierre, terre, herbe, puis de l'air. */
	static BlockState flatState(int y) {
		int bottom = WorldManager.MIN_Y;
		for (WorldManager.Layer l : WorldManager.LAYERS) {
			if (y >= bottom && y < bottom + l.height()) {
				return l.block().defaultBlockState();
			}
			bottom += l.height();
		}
		return Blocks.AIR.defaultBlockState();
	}

	/** La suite de blocs qui remet à plat la boîte [a, b] (bornes comprises), rangée par rangée en partant du bas. */
	private static BuildQueue.Part flatPart(BlockPos a, BlockPos b) {
		int x0 = a.getX();
		int y0 = a.getY();
		int z0 = a.getZ();
		int sx = b.getX() - x0 + 1;
		int sz = b.getZ() - z0 + 1;
		int sy = b.getY() - y0 + 1;
		BlockState[] layer = new BlockState[sy];
		for (int y = 0; y < sy; y++) {
			layer[y] = flatState(y0 + y);
		}
		return new BuildQueue.Part() {
			@Override
			int size() {
				return sx * sz * sy;
			}

			@Override
			BlockState at(int i, BlockPos.MutableBlockPos pos) {
				int y = i / (sx * sz);
				int in = i % (sx * sz);
				pos.set(x0 + in / sz, y0 + y, z0 + in % sz);
				return layer[y];
			}

			@Override
			int flags() {
				return FLAT_FLAGS;
			}
		};
	}

	/** Retire les décors en entités (trônes sculptés, panneaux...) de l'ancienne carte et de l'emprise de la nouvelle. */
	private static void removeDecor(ServerLevel level, PlacedMap old, PlacedMap map) {
		List<Entity> gone = new ArrayList<>();
		for (Entity en : level.getAllEntities()) {
			BlockPos p = en.blockPosition();
			if (en.entityTags().stream().anyMatch(DECOR::contains) && (map.contains(p) || old != null && old.contains(p))) {
				gone.add(en);
			}
		}
		gone.forEach(Entity::discard);
	}

	/** Un joueur pris dans les blocs de la carte qu'on vient de poser est remonté au-dessus, dans la même colonne. */
	private static void liftPlayers(ServerLevel level, PlacedMap map) {
		for (ServerPlayer p : new ArrayList<>(level.players())) {
			if (map.contains(p.blockPosition()) && !level.noCollision(p)) {
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
				p.teleportTo(level, p.getX(), y, p.getZ(), Set.of(), p.getYRot(), p.getXRot(), false);
			}
		}
	}

	/** À chaque tick du serveur : pose un lot de blocs, garde chargées les zones des poses en cours. */
	public void tick() {
		ticks++;
		// Un monde dont la remise à neuf commence lâche ses poses : Fantasy attend que ses tronçons se déchargent.
		for (Placement p : new ArrayList<>(holds.keySet())) {
			List<Hold> h = holds.get(p);
			ServerLevel level = h.isEmpty() ? null : h.get(0).level();
			WorldEntry e = level == null ? null : worlds.entryOf(level);
			if (level != null && (e == null || worlds.busy(e.id()) || server.getLevel(level.dimension()) != level)) {
				queue.cancel(level);
				holds.remove(p);
			}
		}
		if (ticks % 20 == 0) {
			holds.values().forEach(list -> list.forEach(MapPlacer::refresh));
		}
		queue.tick(server);
	}

	/** À l'arrêt du serveur : les poses en cours sont abandonnées (leur résultat passe en échec). */
	public void stop() {
		queue.clear();
		holds.clear();
	}

	// ------------------------------------------------------------------
	// La carte que porte un monde
	// ------------------------------------------------------------------

	/** Ce qu'un monde retient de sa carte : la fiche (repères relatifs) et où se trouve la place. */
	private static final class Stored {
		int[] centre;
		MapStore.MapDef carte;
	}

	private Path file(WorldEntry e) {
		return worlds.folder(e.id()).resolve(FILE);
	}

	/**
	 * La carte que porte ce monde de Valcombe (posée ou capturée en dernier), repères en coordonnées du monde ; null
	 * s'il n'en porte aucune (monde neuf ou remis à neuf) ou si ce n'est pas un monde de Valcombe.
	 */
	public PlacedMap placed(ServerLevel level) {
		WorldEntry e = worlds.entryOf(level);
		if (e == null) {
			return null;
		}
		Path f = file(e);
		if (!Files.exists(f)) {
			return null;
		}
		try {
			Stored s = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Stored.class);
			if (s == null || s.carte == null || s.centre == null || s.centre.length != 3) {
				return null;
			}
			MapStore.checkDef(s.carte);
			return PlacedMap.of(s.carte, level.dimension(), new BlockPos(s.centre[0], s.centre[1], s.centre[2]));
		} catch (IOException | RuntimeException ex) {
			ValcombeMod.LOG.warn("Carte du monde {} illisible : {}", e.id(), ex.getMessage());
			return null;
		}
	}

	/** Note que ce monde porte maintenant cette carte (fichier du monde, et carte de sa fiche dans le registre). */
	private void remember(ServerLevel level, WorldEntry e, PlacedMap map) {
		Stored s = new Stored();
		s.centre = new int[] {map.center().getX(), map.center().getY(), map.center().getZ()};
		s.carte = map.toDef();
		try {
			Path f = file(e);
			Files.createDirectories(f.getParent());
			SafeFile.write(f, GSON.toJson(s));
		} catch (IOException ex) {
			throw new GameException("Carte posée, mais pas notée dans le monde : " + ex.getMessage());
		}
		worlds.setCarte(e.id(), map.mapId());
	}

	// ------------------------------------------------------------------
	// Capturer
	// ------------------------------------------------------------------

	/**
	 * Range dans la bibliothèque la carte que porte ce monde, telle qu'elle est maintenant : la zone bâtie autour de
	 * sa place et ses repères. Une GameException si le monde ne porte aucune carte (ses repères seraient inconnus).
	 */
	public Capture capture(ServerLevel level, String id, String nom) {
		WorldEntry e = entry(level);
		PlacedMap layout = placed(level);
		if (layout == null) {
			throw new GameException("Le monde « " + e.nom() + " » ne porte aucune carte : ses repères (trônes, maisons, centre) sont "
				+ "inconnus. Posez-y d'abord une carte : /vc carte poser <id> " + e.id() + ".");
		}
		return capture(level, layout, id, nom);
	}

	/**
	 * Range dans la bibliothèque, sous l'identifiant id et le nom nom, la carte bâtie dans ce monde autour des repères de
	 * layout (centre, trônes, maisons, salle de convocation, réglages). L'emprise de layout est un minimum : elle
	 * s'agrandit à toute la zone bâtie (comparée au sol plat du monde, jusqu'à MapStore.MAX_RADIUS blocs du centre)
	 * et à tous les repères. Le monde note ensuite qu'il porte cette carte.
	 */
	public Capture capture(ServerLevel level, PlacedMap layout, String id, String nom) {
		requireServerThread();
		WorldEntry e = entry(level);
		MapStore.checkId(id);
		if (queue.busy(level)) {
			throw new GameException("Une carte est en train d'être posée dans « " + e.nom() + " » : capturez-la à la fin de la barre.");
		}
		MapMarks marks = layout.marks();
		if (marks.seats().size() < MIN_SEATS) {
			throw new GameException("La carte n'a que " + marks.seats().size() + " trône(s) : il en faut au moins " + MIN_SEATS + ".");
		}
		if (marks.reach() > MapStore.MAX_RADIUS) {
			throw new GameException("Un repère est à plus de " + (MapStore.MAX_RADIUS - MapMarks.MARGIN) + " blocs de la place : "
				+ "rapprochez-le ou retirez-le avant de capturer la carte.");
		}
		BlockPos c = marks.center();
		int[] built = builtZone(level, c, MapStore.MAX_RADIUS);
		int rayon = captureRadius(layout.rayon(), marks.reach(), built[0]);
		int wanted = captureHeight(layout.hauteur(), c.getY(), built[1], Integer.MAX_VALUE);
		// Jamais plus haut que le monde (ni que la plus grande hauteur enregistrée).
		int roof = level.getMaxY() - (c.getY() - MapStore.BELOW) + 1;
		int hauteur = Math.min(Math.min(wanted, MapStore.MAX_HEIGHT), roof);
		String name = nom == null || nom.isBlank() ? id : nom.strip();
		if (name.length() > 50) {
			throw new GameException("Nom de carte : 50 caractères au plus.");
		}
		PlacedMap map = new PlacedMap(id, name, level.dimension(), marks, layout.genere(), layout.theme(), layout.places(),
			layout.taille(), layout.maisons(), layout.salle(), rayon, hauteur);
		checkInside(level, map);
		boolean replaced = maps.exists(id);
		int side = 2 * rayon + 1;
		Clip clip = Clip.capture(level, map.min(), side, hauteur, side);
		maps.writeMap(map.toDef(), clip, McHost.topView(level, c, rayon));
		remember(level, e, map);
		return new Capture(map, wanted > hauteur, replaced);
	}

	/**
	 * La zone bâtie autour de c dans un monde plat de Valcombe : {rayon, plus haut bloc}. Une colonne est bâtie si elle
	 * diffère du sol plat d'origine entre le sous-sol de la carte et le ciel (un bloc posé, un trou creusé). Le rayon est
	 * la plus grande distance (en x ou en z) d'une colonne bâtie au centre, 0 s'il n'y a rien ; le plus haut bloc est
	 * Integer.MIN_VALUE s'il n'y a rien au-dessus du sol.
	 */
	static int[] builtZone(ServerLevel level, BlockPos c, int maxRadius) {
		int lo = Math.max(level.getMinY(), Math.min(c.getY() - MapStore.BELOW, WorldManager.SURFACE_Y));
		int radius = 0;
		int top = Integer.MIN_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int cx = c.getX() - maxRadius >> 4; cx <= c.getX() + maxRadius >> 4; cx++) {
			for (int cz = c.getZ() - maxRadius >> 4; cz <= c.getZ() + maxRadius >> 4; cz++) {
				LevelChunk chunk = level.getChunk(cx, cz);
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						int wx = (cx << 4) + x;
						int wz = (cz << 4) + z;
						int d = Math.max(Math.abs(wx - c.getX()), Math.abs(wz - c.getZ()));
						if (d > maxRadius) {
							continue;
						}
						int high = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z); // le plus haut bloc de la colonne
						boolean built = high != WorldManager.SURFACE_Y;
						for (int y = lo; !built && y <= WorldManager.SURFACE_Y; y++) {
							built = !chunk.getBlockState(m.set(wx, y, wz)).is(flatState(y).getBlock());
						}
						if (built) {
							radius = Math.max(radius, d);
							if (high > WorldManager.SURFACE_Y) {
								top = Math.max(top, high);
							}
						}
					}
				}
			}
		}
		return new int[] {radius, top};
	}

	/** Rayon d'une capture : l'emprise prévue, les repères et la zone bâtie, au plus MapStore.MAX_RADIUS. */
	static int captureRadius(int layoutRadius, int marksReach, int builtRadius) {
		return Math.min(MapStore.MAX_RADIUS, Math.max(Math.max(layoutRadius, marksReach), builtRadius));
	}

	/**
	 * Hauteur d'une capture (les BELOW rangées sous la place comprises) : l'emprise prévue, au moins MapStore.HEIGHT,
	 * et assez pour le plus haut bloc bâti (builtTop, ou Integer.MIN_VALUE s'il n'y a rien), un bloc de marge compris ;
	 * au plus max.
	 */
	static int captureHeight(int layoutHeight, int centerY, int builtTop, int max) {
		int h = Math.max(MapStore.HEIGHT, layoutHeight);
		if (builtTop != Integer.MIN_VALUE) {
			h = Math.max(h, builtTop - (centerY - MapStore.BELOW) + 2);
		}
		return Math.min(h, max);
	}
}
