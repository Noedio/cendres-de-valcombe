package fr.valcombe.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import fr.valcombe.mc.worlds.BuildRights;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.GameException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * L'atelier structures : un monde (sorte {@link WorldKind#ATELIER_STRUCTURES}, un seul) où toutes les structures de
 * Valcombe, fournies et créées, sont posées chacune sur sa parcelle, et où ce qu'on y change est gardé.
 * <ul>
 * <li><b>Disposition</b> ({@link StructurePlots}) : une parcelle carrée par structure, au sol quadrillé (béton gris
 * clair, une ligne blanche tous les 4 blocs depuis l'origine), cerclée d'une bordure d'ardoise polie ; les parcelles en
 * rangées par catégorie, une allée au nord de chaque rangée, un pupitre (lutrin) et un panneau flottant devant chaque
 * parcelle : son nom, sa catégorie et son état (fournie, modifiée, créée, nouvelle). La disposition est enregistrée
 * avec le monde ({@value #FILE}) : une structure ajoutée au catalogue (éditeur, import, pack, sélection WorldEdit)
 * reçoit sa parcelle au prochain passage, sans que les autres bougent.</li>
 * <li><b>Modifier</b> : sur place, à la main ou avec WorldEdit, puis « Enregistrer » : l'intérieur de la parcelle est
 * capturé et rangé dans le catalogue. Une structure fournie modifiée y devient une surcharge qui la remplace partout
 * ({@link StructureStore#overrideFromWorld}) ; « Revenir à l'originale » efface la surcharge et repose l'originale.
 * « Nouvelle structure » ouvre une parcelle vide nommée ; « Supprimer » envoie une structure créée à la corbeille.</li>
 * <li><b>Le sol ne compte pas</b> : à l'enregistrement, le quadrillage (au niveau du sol plat et dessous) est laissé de
 * côté ; un trou creusé dedans, lui, fait partie de la structure. Seul l'intérieur de la bordure est enregistré.</li>
 * <li><b>Rien n'est écrasé</b> : chaque parcelle garde la version qui y a été posée ou enregistrée en dernier (à côté
 * de la disposition, dans le dossier du monde). Une structure changée ailleurs n'est reposée que si sa parcelle n'a pas
 * de modifications en cours ; sinon la parcelle est marquée « changée ailleurs » et rien n'est touché.</li>
 * <li><b>Droits</b> : tout passe par {@link BuildRights} (opérateurs, et demain leurs invités).</li>
 * </ul>
 * Les poses se font par petits lots à chaque tick (BuildQueue), avec une barre d'avancement. Fil du serveur.
 */
public final class StructureWorkshop implements WorldManager.Listener {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/** Le nom du monde, créé à la première demande (son identifiant en est tiré : {@code atelier-structures}). */
	public static final String NAME = "Atelier structures";
	/** La disposition, dans le dossier du monde ; et le dossier des versions posées (une par parcelle). */
	static final String FILE = "valcombe-parcelles.json";
	static final String SNAPS = "valcombe-parcelles";
	/** Le sol plat : les structures se posent les pieds un bloc au-dessus. */
	static final int S = WorldManager.SURFACE_Y;
	/** Le pupitre d'accueil, devant le point d'arrivée. */
	static final BlockPos WELCOME = new BlockPos(0, S + 1, 3);
	/** Tailles d'une parcelle neuve : demi-côté de l'intérieur et hauteur au-dessus du sol. */
	public static final List<String> SIZES = List.of("petite", "moyenne", "grande", "immense");
	private static final int[][] SIZE_VALUES = {{5, 16}, {8, 24}, {12, 32}, {20, 48}};
	/** Pose sans rien faire tomber ni réveiller (comme la remise à plat des cartes). */
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
	/** Étiquette des affichages de l'atelier (panneaux des parcelles, des rangées et de l'accueil). */
	static final String TAG = "vc_ap";

	/**
	 * Une parcelle, telle que la voient les autres parties du mod (écran, tests en jeu).
	 *
	 * @param etat     « fournie », « modifiee » (fournie modifiée), « creee » (structure de l'utilisateur) ou
	 *                 « nouvelle » (parcelle neuve pas encore enregistrée)
	 * @param x        origine (centre), sur le sol plat
	 * @param lecternZ le pupitre est en (x, sol + 1, lecternZ)
	 * @param pose     une pose est en cours sur la parcelle
	 */
	public record ParcelInfo(String id, String nom, String categorie, String etat, int x, int z, int rayon, int bas, int haut,
			int lecternZ, boolean conflit, boolean pose) {
	}

	/** Une version de structure à poser : ses blocs et la case de ses pieds ; settle : raccorder barrières et vitres. */
	private record Version(Clip clip, int[] ancre, boolean settle) {
	}

	/** La version posée ou enregistrée en dernier sur une parcelle (clip null : parcelle vide). */
	private record Snapshot(Clip clip, int[] ancre) {
	}

	/** Le dernier message d'une action de l'atelier, pour l'écran (le chat est caché derrière). */
	private record Reply(int n, String text, boolean error) {
	}

	/** Une parcelle capturée : ses blocs recadrés et la case de ses pieds. */
	record Captured(Clip clip, BlockPos ancre) {
	}

	private final MinecraftServer server;
	private final WorldManager worlds;
	private final StructureStore store;
	private final BuildQueue queue = new BuildQueue();
	private StructurePlots.Layout layout;
	/** Le monde dont layout est la disposition. */
	private String layoutFor;
	private final Map<String, Snapshot> snaps = new HashMap<>();
	/** Parcelles en cours de pose : on n'y enregistre rien, on n'y repose rien tant que la barre n'est pas finie. */
	private final Set<String> pending = new HashSet<>();
	/** Le centre des parcelles en cours de pose, tenues chargées. */
	private final Map<String, BlockPos> holds = new LinkedHashMap<>();
	/** La parcelle choisie par chaque joueur (dernier pupitre, ou l'écran). */
	private final Map<UUID, String> focus = new HashMap<>();
	private final Map<UUID, Reply> replies = new HashMap<>();
	/** « Modifications non enregistrées », calculées au plus une fois par seconde par parcelle. */
	private final Map<String, long[]> dirtyCache = new HashMap<>();
	private int replyCount;
	private int ticks;

	public StructureWorkshop(MinecraftServer server, WorldManager worlds, StructureStore store) {
		this.server = server;
		this.worlds = worlds;
		this.store = store;
	}

	// ------------------------------------------------------------------
	// Le monde
	// ------------------------------------------------------------------

	/** La fiche de l'atelier structures, ou null s'il n'a pas encore été créé. */
	public WorldEntry entry() {
		List<WorldEntry> list = worlds.registry().list(WorldKind.ATELIER_STRUCTURES);
		return list.isEmpty() ? null : list.get(0);
	}

	/** L'atelier, créé s'il n'existe pas encore (fil du serveur). */
	public WorldEntry ensure() {
		WorldEntry e = entry();
		if (e == null) {
			e = worlds.create(WorldKind.ATELIER_STRUCTURES, NAME);
			ValcombeMod.LOG.info("Atelier structures créé : {}.", e.id());
		}
		return e;
	}

	/** Le monde de l'atelier s'il est ouvert, sinon null (jamais d'ouverture ici). */
	private ServerLevel levelIfOpen() {
		WorldEntry e = entry();
		return e == null || !worlds.isOpen(e.id()) ? null : server.getLevel(WorldManager.key(e.id()));
	}

	/** Ce niveau est-il l'atelier structures ? */
	public boolean isWorkshop(Level level) {
		WorldEntry e = level == null ? null : worlds.entryOf(level);
		return e != null && e.sorte() == WorldKind.ATELIER_STRUCTURES;
	}

	/** Une pose est-elle en cours ou en attente dans l'atelier ? */
	public boolean busy() {
		return queue.busy();
	}

	@Override
	public void recreated(WorldEntry entry, ServerLevel level) {
		forgetWorld(entry);
	}

	@Override
	public void deleted(WorldEntry entry) {
		forgetWorld(entry);
	}

	/** L'atelier remis à neuf ou supprimé : sa disposition est partie avec ses fichiers. */
	private void forgetWorld(WorldEntry entry) {
		if (entry.sorte() == WorldKind.ATELIER_STRUCTURES || entry.id().equals(layoutFor)) {
			layout = null;
			layoutFor = null;
			snaps.clear();
			pending.clear();
			holds.clear();
			dirtyCache.clear();
		}
	}

	/** À l'arrêt du serveur : les poses en cours sont abandonnées. */
	public void stop() {
		queue.clear();
		holds.clear();
		pending.clear();
	}

	// ------------------------------------------------------------------
	// La disposition, enregistrée avec le monde
	// ------------------------------------------------------------------

	private Path folder(String id) {
		return worlds.folder(id);
	}

	private StructurePlots.Layout layout(ServerLevel level) {
		WorldEntry e = worlds.entryOf(level);
		if (e == null || e.sorte() != WorldKind.ATELIER_STRUCTURES) {
			throw new GameException("Ce n'est pas l'atelier structures.");
		}
		if (layout == null || !e.id().equals(layoutFor)) {
			StructurePlots.Layout l = null;
			Path f = folder(e.id()).resolve(FILE);
			if (Files.exists(f)) {
				try {
					l = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), StructurePlots.Layout.class);
				} catch (IOException | RuntimeException ex) {
					ValcombeMod.LOG.warn("Disposition de l'atelier structures illisible : elle est refaite", ex);
				}
			}
			if (l == null || l.bandes == null || l.parcelles == null) {
				l = new StructurePlots.Layout();
			}
			l.parcelles.removeIf(p -> p == null || p.id == null || !p.id.matches("[a-z0-9-]{2,30}"));
			layout = l;
			layoutFor = e.id();
			snaps.clear();
			dirtyCache.clear();
		}
		return layout;
	}

	private void saveLayout() {
		if (layout == null || layoutFor == null) {
			return;
		}
		try {
			Path f = folder(layoutFor).resolve(FILE);
			Files.createDirectories(f.getParent());
			Files.writeString(f, GSON.toJson(layout), StandardCharsets.UTF_8);
		} catch (IOException e) {
			ValcombeMod.LOG.warn("Disposition de l'atelier structures non enregistrée", e);
		}
	}

	private Path snapFile(String id) {
		return folder(layoutFor).resolve(SNAPS).resolve(id + ".nbt");
	}

	/** Retient la version posée ou enregistrée sur cette parcelle (en mémoire et sur le disque). */
	private void remember(StructurePlots.Plot p, Clip clip, int[] ancre) {
		p.ancre = clip == null ? null : ancre.clone();
		snaps.put(p.id, new Snapshot(clip, p.ancre));
		dirtyCache.remove(p.id);
		try {
			Path f = snapFile(p.id);
			if (clip == null) {
				Files.deleteIfExists(f);
			} else {
				Files.createDirectories(f.getParent());
				NbtIo.writeCompressed(clip.toStructure(), f);
			}
		} catch (IOException e) {
			ValcombeMod.LOG.warn("Version posée de la parcelle {} non gardée", p.id, e);
		}
	}

	private Snapshot snapshot(StructurePlots.Plot p) {
		Snapshot s = snaps.get(p.id);
		if (s != null) {
			return s;
		}
		Clip clip = null;
		if (p.ancre != null) {
			try {
				byte[] bytes = Files.readAllBytes(snapFile(p.id));
				CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(64L * 1024 * 1024));
				clip = Clip.read(tag, server.registryAccess().lookupOrThrow(Registries.BLOCK), StructureStore.MAX_SIDE,
					StructureStore.MAX_HEIGHT, StructureStore.MAX_SIDE);
			} catch (IOException | RuntimeException e) {
				ValcombeMod.LOG.warn("Version posée de la parcelle {} illisible", p.id, e);
			}
		}
		s = new Snapshot(clip, clip == null ? null : p.ancre);
		snaps.put(p.id, s);
		return s;
	}

	private void forgetSnapshot(String id) {
		snaps.remove(id);
		dirtyCache.remove(id);
		if (layoutFor != null) {
			try {
				Files.deleteIfExists(snapFile(id));
			} catch (IOException e) {
				ValcombeMod.LOG.debug("Version posée de la parcelle {} non effacée", id, e);
			}
		}
	}

	// ------------------------------------------------------------------
	// Ce que demande une structure, et ses versions
	// ------------------------------------------------------------------

	/** Profondeur sous le sol et hauteur au-dessus d'une structure (taille, ancre). */
	private static int below(StructureStore.Def d) {
		return Math.max(0, d.ancre[1] - 1);
	}

	private static int above(StructureStore.Def d) {
		return Math.max(0, d.taille[1] - d.ancre[1]);
	}

	/**
	 * La parcelle d'une structure : assez grande pour sa version actuelle et, pour une structure fournie, pour son
	 * originale (« Revenir à l'originale » la repose au même endroit).
	 */
	private StructurePlots.Need need(StructureStore.Def d) {
		List<StructureStore.Def> versions = new ArrayList<>(List.of(d));
		if (d.fournie) {
			versions.add(store.originalDef(d.id));
		}
		int r = StructurePlots.MIN_RAYON;
		int bas = 2;
		int haut = 12;
		for (StructureStore.Def v : versions) {
			r = Math.max(r, StructurePlots.rayonFor(v.taille[0], v.taille[2], v.ancre[0], v.ancre[2]));
			bas = Math.max(bas, below(v) + 1);
			haut = Math.max(haut, above(v) + 6);
		}
		return sized(d.id, d.categorie, r, bas, haut);
	}

	/** Une demande ramenée dans les bornes du catalogue (hauteur, nombre de cases). */
	private static StructurePlots.Need sized(String id, String categorie, int r, int bas, int haut) {
		int side = 2 * r + 1;
		int maxH = Math.min(StructureStore.MAX_HEIGHT, StructureStore.MAX_VOLUME / (side * side));
		int b = Math.min(bas, maxH / 4);
		int h = Math.max(1, Math.min(haut, maxH - b - 1));
		return new StructurePlots.Need(id, categorie, r, b, h);
	}

	/** La structure tient-elle dans cette parcelle ? */
	private static boolean fits(StructurePlots.Plot p, StructureStore.Def d) {
		return StructurePlots.rayonFor(d.taille[0], d.taille[2], d.ancre[0], d.ancre[2]) <= p.rayon && below(d) <= p.bas
			&& above(d) <= p.haut;
	}

	/** La version du catalogue (modifiée si elle l'est). */
	private Version library(String id) {
		StructureStore.Def d = store.def(id);
		return new Version(store.clip(id), d.ancre.clone(), d.fournie && !d.modifiee);
	}

	/** L'originale d'une structure fournie. */
	private Version original(String id) {
		return new Version(store.originalClip(id), store.originalDef(id).ancre.clone(), true);
	}

	/**
	 * La version d'une parcelle dont la pose a été interrompue : celle qui lui était destinée (retenue à côté de la
	 * disposition), sinon celle du catalogue ; null pour une parcelle neuve (posée vide).
	 */
	private Version resumed(StructurePlots.Plot p) {
		if (p.brouillon) {
			return null;
		}
		Snapshot s = snapshot(p);
		if (s.clip() != null) {
			return new Version(s.clip(), s.ancre().clone(), StructureStore.isBuiltin(p.id) && !store.isOverridden(p.id));
		}
		return store.exists(p.id) ? library(p.id) : null;
	}

	/** L'état d'une parcelle, tel que l'affichent le panneau et l'écran. */
	private String state(StructurePlots.Plot p) {
		if (p.brouillon) {
			return "nouvelle";
		}
		if (StructureStore.isBuiltin(p.id)) {
			return store.isOverridden(p.id) ? "modifiee" : "fournie";
		}
		return "creee";
	}

	private String name(StructurePlots.Plot p) {
		if (p.brouillon || !store.exists(p.id)) {
			return p.nom.isEmpty() ? p.id : p.nom;
		}
		return store.def(p.id).nom;
	}

	static String categoryName(String c) {
		return switch (c == null ? "" : c) {
			case "maisons" -> "Maisons et salles";
			case "place" -> "Pour la place";
			case "decor" -> "Décor";
			default -> "Vos structures";
		};
	}

	private static String stateName(String state) {
		return switch (state) {
			case "fournie" -> "fournie";
			case "modifiee" -> "modifiée";
			case "creee" -> "créée";
			default -> "nouvelle, pas encore enregistrée";
		};
	}

	// ------------------------------------------------------------------
	// Le sol des parcelles
	// ------------------------------------------------------------------

	/** Le quadrillage du sol : une ligne blanche tous les 4 blocs depuis l'origine, du béton gris clair entre. */
	static BlockState soil(int dx, int dz) {
		return Math.floorMod(dx, 4) == 0 || Math.floorMod(dz, 4) == 0 ? Blocks.CONCRETE.white().defaultBlockState()
			: Blocks.CONCRETE.lightGray().defaultBlockState();
	}

	static boolean isSoil(BlockState s) {
		return s.is(Blocks.CONCRETE.white()) || s.is(Blocks.CONCRETE.lightGray());
	}

	private static BlockState border() {
		return Blocks.POLISHED_DEEPSLATE.defaultBlockState();
	}

	/** La boîte de la parcelle (bordure comprise) remise à neuf : sol quadrillé dedans, bordure, air au-dessus. */
	private static BuildQueue.Part groundPart(StructurePlots.Plot p) {
		int r = p.rayon;
		int x0 = p.x - r - 1;
		int z0 = p.z - r - 1;
		int y0 = S - p.bas;
		int side = 2 * r + 3;
		int sy = p.bas + p.haut + 1;
		return new BuildQueue.Part() {
			@Override
			int size() {
				return side * side * sy;
			}

			@Override
			BlockState at(int i, BlockPos.MutableBlockPos pos) {
				int y = y0 + i / (side * side);
				int in = i % (side * side);
				int x = x0 + in % side;
				int z = z0 + in / side;
				pos.set(x, y, z);
				boolean inside = Math.abs(x - p.x) <= r && Math.abs(z - p.z) <= r;
				if (y > S) {
					return Blocks.AIR.defaultBlockState();
				}
				if (inside) {
					return soil(x - p.x, z - p.z);
				}
				return y == S ? border() : MapPlacer.flatState(y);
			}

			@Override
			int flags() {
				return FLAGS;
			}
		};
	}

	/** L'allée devant la parcelle (4 blocs de large, au nord) et son pupitre. */
	private static BuildQueue.Part frontPart(StructurePlots.Plot p) {
		int x0 = p.west() - 1;
		int x1 = p.east() + 2;
		int z0 = p.bande - 4;
		int sx = x1 - x0 + 1;
		int sz = 4;
		int sy = 4;
		BlockState lectern = Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.NORTH);
		return new BuildQueue.Part() {
			@Override
			int size() {
				return sx * sz * sy;
			}

			@Override
			BlockState at(int i, BlockPos.MutableBlockPos pos) {
				int y = S + i / (sx * sz);
				int in = i % (sx * sz);
				int x = x0 + in % sx;
				int z = z0 + in / sx;
				pos.set(x, y, z);
				if (y == S) {
					return Blocks.DIRT_PATH.defaultBlockState();
				}
				return x == p.x && z == p.lecternZ() && y == S + 1 ? lectern : Blocks.AIR.defaultBlockState();
			}

			@Override
			int flags() {
				return FLAGS;
			}
		};
	}

	/** Une boîte rendue au sol plat des mondes de Valcombe (parcelle supprimée). */
	private static BuildQueue.Part flatPart(int x0, int y0, int z0, int x1, int y1, int z1) {
		int sx = x1 - x0 + 1;
		int sz = z1 - z0 + 1;
		int sy = y1 - y0 + 1;
		return new BuildQueue.Part() {
			@Override
			int size() {
				return sx * sz * sy;
			}

			@Override
			BlockState at(int i, BlockPos.MutableBlockPos pos) {
				int y = y0 + i / (sx * sz);
				int in = i % (sx * sz);
				pos.set(x0 + in % sx, y, z0 + in / sx);
				return MapPlacer.flatState(y);
			}

			@Override
			int flags() {
				return FLAGS;
			}
		};
	}

	/** Le coin le plus bas d'une version posée sur cette parcelle : ses pieds sur l'origine, un bloc au-dessus du sol. */
	private static BlockPos corner(StructurePlots.Plot p, int[] ancre) {
		return new BlockPos(p.x - ancre[0], S + 1 - ancre[1], p.z - ancre[2]);
	}

	/** Le plan qui remet la parcelle à neuf et y pose cette version (null : parcelle vide). */
	private static BuildQueue.Plan placePlan(ServerLevel level, StructurePlots.Plot p, Version v) {
		BuildQueue.Plan plan = new BuildQueue.Plan();
		plan.first(groundPart(p));
		plan.first(frontPart(p));
		if (v != null && v.clip() != null) {
			plan.then(v.clip().plan(level, corner(p, v.ancre()), false, v.settle()));
		}
		return plan;
	}

	// ------------------------------------------------------------------
	// Poser
	// ------------------------------------------------------------------

	/**
	 * Met en file la pose de ces parcelles (chacune avec sa version, null : vide), en un seul chantier. La version posée
	 * devient la référence de chaque parcelle ; then est lancé une fois tout en place.
	 */
	private void place(ServerLevel level, Map<StructurePlots.Plot, Version> what, String label, Runnable then) {
		BuildQueue.Plan plan = new BuildQueue.Plan();
		List<String> ids = new ArrayList<>();
		for (Map.Entry<StructurePlots.Plot, Version> e : what.entrySet()) {
			StructurePlots.Plot p = e.getKey();
			Version v = e.getValue();
			plan.then(placePlan(level, p, v));
			remember(p, v == null ? null : v.clip(), v == null ? null : v.ancre());
			p.aPoser = true; // enregistré avec la disposition : une pose interrompue par un arrêt reprend au retour
			pending.add(p.id);
			holds.put(p.id, new BlockPos(p.x, S, p.z));
			ids.add(p.id);
		}
		saveLayout();
		refreshHolds(level);
		queue.add(label, level, plan, () -> {
			ids.forEach(id -> {
				pending.remove(id);
				holds.remove(id);
				dirtyCache.remove(id);
			});
			boolean finished = false;
			for (String id : ids) {
				StructurePlots.Plot p = layout == null ? null : layout.plot(id);
				if (p != null) {
					finished |= p.aPoser;
					p.aPoser = false;
					label(level, p);
				}
			}
			if (finished) {
				saveLayout();
			}
			if (then != null) {
				then.run();
			}
		}, pl -> pl.level() == level, () -> ids.forEach(id -> {
			pending.remove(id);
			holds.remove(id);
		}));
	}

	private void place(ServerLevel level, StructurePlots.Plot p, Version v, String label) {
		Map<StructurePlots.Plot, Version> one = new LinkedHashMap<>();
		one.put(p, v);
		place(level, one, label, null);
	}

	/** Les parcelles en cours de pose restent chargées (une rangée de pose ne recharge pas tout à chaque lot). */
	private void refreshHolds(ServerLevel level) {
		for (Map.Entry<String, BlockPos> h : holds.entrySet()) {
			StructurePlots.Plot p = layout == null ? null : layout.plot(h.getKey());
			int radius = p == null ? 2 : (p.rayon + 6) / 16 + 1;
			level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, ChunkPos.containing(h.getValue()), radius);
		}
	}

	/** Le décor de l'atelier : allée d'accueil et pupitre d'accueil, et l'allée qui longe l'ouest des rangées. */
	private void placeGrounds(ServerLevel level, StructurePlots.Layout l) {
		int south = StructurePlots.FIRST_Z;
		for (StructurePlots.Band b : l.bandes) {
			south = Math.max(south, b.southZ() + 2);
		}
		int spineX = StructurePlots.X0 - 4;
		BuildQueue.Plan plan = new BuildQueue.Plan();
		BlockState path = Blocks.DIRT_PATH.defaultBlockState();
		BlockState lectern = Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.NORTH);
		for (int x = spineX - 1; x <= 2; x++) {
			for (int z = -1; z <= 5; z++) {
				plan.set(new BlockPos(x, S, z), path);
			}
		}
		for (int z = 5; z <= south; z++) {
			for (int x = spineX - 1; x <= spineX + 1; x++) {
				plan.set(new BlockPos(x, S, z), path);
			}
		}
		// L'allée de chaque rangée rejoint celle de l'ouest.
		for (StructurePlots.Band b : l.bandes) {
			for (int z = b.z0 - 4; z <= b.z0 - 1; z++) {
				for (int x = spineX + 2; x < StructurePlots.X0 - 1; x++) {
					plan.set(new BlockPos(x, S, z), path);
				}
			}
		}
		plan.set(WELCOME, lectern);
		// Retenu avec la disposition : des allées interrompues par un arrêt du serveur sont reprises au retour.
		l.allees = false;
		saveLayout();
		queue.add("Atelier structures : allées", level, plan, () -> {
			if (layout == l) {
				l.allees = true;
				saveLayout();
			}
		}, pl -> pl.level() == level, null);
	}

	// ------------------------------------------------------------------
	// Les structures et leurs parcelles
	// ------------------------------------------------------------------

	/**
	 * Accorde les parcelles au catalogue : une parcelle pour chaque structure qui n'en a pas (sans bouger les autres),
	 * plus de parcelle pour une structure disparue, et une structure changée ailleurs reposée si sa parcelle n'a pas
	 * de modifications en cours (sinon la parcelle est marquée « changée ailleurs »). Fil du serveur.
	 */
	public void sync(ServerLevel level) {
		StructurePlots.Layout l = layout(level);
		boolean changed = false;
		int bands = l.bandes.size();
		boolean fresh = l.parcelles.isEmpty() && l.bandes.isEmpty();
		// Structures disparues du catalogue (supprimées ailleurs) : leur parcelle s'en va, sauf si elle a des
		// modifications non enregistrées. Celle-là reste, en parcelle neuve : « Enregistrer » remet la structure au
		// catalogue, « Supprimer » l'abandonne.
		for (StructurePlots.Plot p : new ArrayList<>(l.parcelles)) {
			if (!p.brouillon && !store.exists(p.id) && !pending.contains(p.id)) {
				if (!p.aPoser && dirty(level, p)) {
					p.brouillon = true;
					p.nom = p.id;
					p.conflit = true;
					label(level, p);
				} else {
					clear(level, p);
				}
				changed = true;
			} else if (p.brouillon && store.exists(p.id)) {
				p.brouillon = false; // rangée sous le même identifiant ailleurs : la parcelle la suit désormais
				p.signature = "";
				changed = true;
			}
		}
		// Structures sans parcelle.
		List<StructurePlots.Need> needs = new ArrayList<>();
		for (StructureStore.Def d : store.list()) {
			if (l.plot(d.id) == null) {
				needs.add(need(d));
			}
		}
		Map<StructurePlots.Plot, Version> toPlace = new LinkedHashMap<>();
		// Poses interrompues (serveur arrêté, partie quittée pendant la barre) : la parcelle est reposée en entier, avec
		// la version qui lui était destinée. Sans cela elle resterait à moitié posée, vue comme « modifiée ».
		for (StructurePlots.Plot p : l.parcelles) {
			if (p.aPoser && !pending.contains(p.id)) {
				toPlace.put(p, resumed(p));
			}
		}
		if (!needs.isEmpty()) {
			for (StructurePlots.Plot p : StructurePlots.allocate(l, needs)) {
				p.signature = store.signature(p.id);
				toPlace.put(p, library(p.id));
			}
			changed = true;
		}
		// Structures changées ailleurs.
		for (StructurePlots.Plot p : new ArrayList<>(l.parcelles)) {
			if (p.brouillon || pending.contains(p.id) || toPlace.containsKey(p) || !store.exists(p.id)) {
				continue;
			}
			String sig = store.signature(p.id);
			if (sig.equals(p.signature)) {
				continue;
			}
			if (dirty(level, p)) {
				if (!p.conflit) {
					p.conflit = true;
					changed = true;
					label(level, p);
				}
				continue;
			}
			StructureStore.Def d = store.def(p.id);
			if (fits(p, d)) {
				p.signature = sig;
				p.conflit = false;
				toPlace.put(p, library(p.id));
			} else {
				// Trop grande pour sa parcelle : elle déménage au bout de sa rangée (ou dans une rangée neuve).
				clear(level, p);
				StructurePlots.Plot moved = StructurePlots.allocate(l, List.of(need(d))).get(0);
				moved.signature = sig;
				toPlace.put(moved, library(moved.id));
			}
			changed = true;
		}
		if (fresh || l.bandes.size() != bands || !l.allees && !queue.busy(level)) {
			placeGrounds(level, l);
		}
		if (!toPlace.isEmpty()) {
			place(level, toPlace, toPlace.size() == 1 ? "Atelier structures : pose de « " + name(toPlace.keySet().iterator().next()) + " »"
				: "Atelier structures : " + toPlace.size() + " parcelles", () -> refreshLabels(level));
		}
		if (changed) {
			saveLayout();
		}
	}

	/** Rend la place d'une parcelle au sol plat, retire son panneau et l'enlève de la disposition. */
	private void clear(ServerLevel level, StructurePlots.Plot p) {
		BuildQueue.Plan plan = new BuildQueue.Plan();
		plan.first(flatPart(p.west(), S - p.bas, p.z - p.rayon - 1, p.east(), S + p.haut, p.z + p.rayon + 1));
		plan.set(new BlockPos(p.x, S + 1, p.lecternZ()), Blocks.AIR.defaultBlockState());
		queue.add("Atelier structures : parcelle retirée", level, plan, null, pl -> pl.level() == level, null);
		removeLabel(level, p.id);
		StructurePlots.remove(layout, p.id);
		forgetSnapshot(p.id);
	}

	// ------------------------------------------------------------------
	// Capturer, comparer
	// ------------------------------------------------------------------

	/**
	 * L'intérieur de la parcelle tel qu'il est : le quadrillage du sol (au niveau du sol plat et dessous) laissé de côté,
	 * recadré sur ce qui a été bâti (un trou creusé compte), l'origine toujours comprise. Une GameException si la
	 * parcelle est vide.
	 */
	static Captured capture(ServerLevel level, StructurePlots.Plot p) {
		int r = p.rayon;
		int side = 2 * r + 1;
		int h = p.bas + p.haut + 1;
		BlockPos min = new BlockPos(p.x - r, S - p.bas, p.z - r);
		Clip raw = Clip.capture(level, min, side, h, side);
		int[] cells = raw.cells.clone();
		int fx = r;
		int fy = p.bas + 1;
		int fz = r;
		int x0 = Integer.MAX_VALUE;
		int y0 = Integer.MAX_VALUE;
		int z0 = Integer.MAX_VALUE;
		int x1 = Integer.MIN_VALUE;
		int y1 = Integer.MIN_VALUE;
		int z1 = Integer.MIN_VALUE;
		boolean any = false;
		for (int i = 0; i < cells.length; i++) {
			int x = i % side;
			int y = i / (side * side);
			int z = i / side % side;
			BlockState s = raw.palette.get(cells[i]);
			boolean ground = y <= p.bas;
			if (ground && isSoil(s)) {
				cells[i] = Clip.EMPTY;
				continue;
			}
			if (s.isAir() && !ground) {
				continue; // l'air au-dessus du sol ne fixe pas les bornes (il est gardé s'il est dedans)
			}
			any = true;
			x0 = Math.min(x0, x);
			y0 = Math.min(y0, y);
			z0 = Math.min(z0, z);
			x1 = Math.max(x1, x);
			y1 = Math.max(y1, y);
			z1 = Math.max(z1, z);
		}
		if (!any) {
			throw new GameException("La parcelle est vide : rien à enregistrer. Bâtissez dans le quadrillage, puis « Enregistrer ».");
		}
		x0 = Math.min(x0, fx);
		y0 = Math.min(y0, fy);
		z0 = Math.min(z0, fz);
		x1 = Math.max(x1, fx);
		y1 = Math.max(y1, fy);
		z1 = Math.max(z1, fz);
		int w = x1 - x0 + 1;
		int ch = y1 - y0 + 1;
		int l = z1 - z0 + 1;
		int[] out = new int[w * ch * l];
		Map<Integer, CompoundTag> data = new HashMap<>();
		for (int y = 0; y < ch; y++) {
			for (int z = 0; z < l; z++) {
				for (int x = 0; x < w; x++) {
					int from = ((y + y0) * side + z + z0) * side + x + x0;
					int to = (y * l + z) * w + x;
					out[to] = cells[from];
					CompoundTag d = raw.data.get(from);
					if (d != null && cells[from] != Clip.EMPTY) {
						data.put(to, d);
					}
				}
			}
		}
		Clip clip = StructureStore.safe(new Clip(w, ch, l, raw.palette, out, data));
		return new Captured(clip, new BlockPos(fx - x0, fy - y0, fz - z0));
	}

	/**
	 * La parcelle a-t-elle changé depuis la dernière pose ou le dernier enregistrement ? Chaque case de l'intérieur est
	 * comparée à ce qu'y a mis la version de référence (le sol quadrillé et l'air là où elle ne met rien). Barrières,
	 * vitres et murets sont comparés sans leur forme (elle suit les voisins).
	 */
	private boolean dirty(ServerLevel level, StructurePlots.Plot p) {
		Snapshot s = snapshot(p);
		int r = p.rayon;
		BlockPos c = s.clip() == null ? null : corner(p, s.ancre());
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int y = S - p.bas; y <= S + p.haut; y++) {
			for (int z = p.z - r; z <= p.z + r; z++) {
				for (int x = p.x - r; x <= p.x + r; x++) {
					BlockState expected = null;
					if (c != null) {
						int cx = x - c.getX();
						int cy = y - c.getY();
						int cz = z - c.getZ();
						Clip k = s.clip();
						if (cx >= 0 && cy >= 0 && cz >= 0 && cx < k.width && cy < k.height && cz < k.length) {
							int cell = k.cells[(cy * k.length + cz) * k.width + cx];
							if (cell != Clip.EMPTY) {
								expected = k.palette.get(cell);
							}
						}
					}
					if (expected == null) {
						expected = y <= S ? soil(x - p.x, z - p.z) : Blocks.AIR.defaultBlockState();
					}
					BlockState now = level.getBlockState(m.set(x, y, z));
					if (now != expected && !(shapeFollowsNeighbours(expected) && now.is(expected.getBlock()))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Blocs dont l'état suit les voisins sans qu'on y touche : barrières, vitres, murets (leur forme) et feuillages
	 * (leur distance au tronc). Ils sont comparés sans cet état.
	 */
	private static boolean shapeFollowsNeighbours(BlockState s) {
		return Clip.connects(s) || s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock;
	}

	/** « Modifications non enregistrées » de cette parcelle, gardées une seconde ; null pendant une pose. */
	private Boolean changes(ServerLevel level, StructurePlots.Plot p) {
		if (pending.contains(p.id)) {
			return null;
		}
		long[] c = dirtyCache.get(p.id);
		if (c == null || ticks - c[0] >= 20) {
			c = new long[] {ticks, dirty(level, p) ? 1 : 0};
			dirtyCache.put(p.id, c);
		}
		return c[1] == 1;
	}

	// ------------------------------------------------------------------
	// Panneaux flottants
	// ------------------------------------------------------------------

	private static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
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

	/** Pose un texte flottant (face au regard) avec ces étiquettes, s'il n'y est pas déjà tel quel. */
	private static void text(ServerLevel level, String group, double x, double y, double z, double scale, int width, String text) {
		String key = TAG + "k_" + Integer.toHexString((text + "|" + scale + "|" + width).hashCode());
		AABB box = new AABB(x - 0.6, y - 0.6, z - 0.6, x + 0.6, y + 0.6, z + 0.6);
		List<Entity> here = level.getEntities((Entity) null, box, e -> e.entityTags().contains(group));
		if (here.size() == 1 && here.get(0).entityTags().contains(key)) {
			return;
		}
		here.forEach(Entity::discard);
		WorldSetup.run(level, String.format(Locale.ROOT,
			"summon minecraft:text_display %.3f %.3f %.3f {Tags:[\"%s\",\"%s\",\"%s\"],billboard:\"center\",line_width:%d,"
				+ "background:-1072689136,shadow:1b,brightness:{sky:15,block:15},transformation:{left_rotation:[0f,0f,0f,1f],"
				+ "right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[%.2ff,%.2ff,%.2ff]},text:%s}",
			x, y, z, TAG, group, key, width, scale, scale, scale, text));
	}

	private static String labelGroup(String id) {
		return TAG + "p_" + id;
	}

	/** Le panneau d'une parcelle, au-dessus de son pupitre : nom, catégorie et état. */
	private void label(ServerLevel level, StructurePlots.Plot p) {
		BlockPos at = new BlockPos(p.x, S + 1, p.lecternZ());
		if (!level.isLoaded(at)) {
			return;
		}
		String st = state(p);
		String color = switch (st) {
			case "modifiee" -> "gold";
			case "creee" -> "green";
			case "nouvelle" -> "aqua";
			default -> "white";
		};
		String line2 = categoryName(p.categorie) + " · " + stateName(st);
		String text = p.conflit
			? comp(name(p), color, true, "\n" + line2, "gray", false, "\nchangée ailleurs : « Reposer » ou « Enregistrer »", "red", false)
			: comp(name(p), color, true, "\n" + line2, "gray", false);
		text(level, labelGroup(p.id), p.x + 0.5, S + 2.45, p.lecternZ() + 0.5, 0.8, 170, text);
	}

	private static void removeLabel(ServerLevel level, String id) {
		WorldSetup.run(level, "kill @e[type=minecraft:text_display,tag=" + labelGroup(id) + "]");
	}

	/** Panneaux des parcelles et des rangées près des joueurs de l'atelier, et l'accueil ; les panneaux orphelins partent. */
	private void refreshLabels(ServerLevel level) {
		if (layout == null) {
			return;
		}
		List<ServerPlayer> players = level.players();
		if (players.isEmpty()) {
			return;
		}
		for (StructurePlots.Plot p : layout.parcelles) {
			if (near(players, p.x, p.lecternZ(), 112)) {
				label(level, p);
			}
		}
		for (StructurePlots.Band b : layout.bandes) {
			double x = StructurePlots.X0 - 4 + 0.5;
			if (near(players, (int) x, b.centerZ(), 112) && level.isLoaded(new BlockPos((int) x, S, b.centerZ()))) {
				text(level, TAG + "b_" + b.z0, x, S + 3.2, b.centerZ() + 0.5, 1.6, 200,
					comp(categoryName(b.categorie), "gold", true));
			}
		}
		if (near(players, WELCOME.getX(), WELCOME.getZ(), 112) && level.isLoaded(WELCOME)) {
			text(level, TAG + "w", WELCOME.getX() + 0.5, S + 3.0, WELCOME.getZ() + 0.5, 1.0, 260,
				comp("Atelier des structures", "gold", true,
					"\nToutes les structures de Valcombe, chacune sur sa parcelle.", "white", false,
					"\nModifiez-en une sur place, puis « Enregistrer » à son pupitre.", "gray", false,
					"\nCe pupitre : la liste, et « Nouvelle structure ».", "gray", false));
		}
		// Panneaux d'une parcelle disparue (ou déplacée) : retirés.
		Set<String> groups = new HashSet<>();
		for (StructurePlots.Plot p : layout.parcelles) {
			groups.add(labelGroup(p.id));
		}
		for (StructurePlots.Band b : layout.bandes) {
			groups.add(TAG + "b_" + b.z0);
		}
		groups.add(TAG + "w");
		for (ServerPlayer pl : players) {
			for (Entity e : level.getEntities((Entity) null, pl.getBoundingBox().inflate(112, 64, 112),
					en -> en.entityTags().contains(TAG))) {
				boolean known = e.entityTags().stream().anyMatch(t -> groups.contains(t));
				StructurePlots.Plot p = null;
				for (String t : e.entityTags()) {
					if (t.startsWith(TAG + "p_") && layout.plot(t.substring(TAG.length() + 2)) != null) {
						p = layout.plot(t.substring(TAG.length() + 2));
					}
				}
				boolean moved = p != null && (Math.abs(e.getX() - (p.x + 0.5)) > 0.7 || Math.abs(e.getZ() - (p.lecternZ() + 0.5)) > 0.7);
				if (!known || moved) {
					e.discard();
				}
			}
		}
	}

	private static boolean near(List<ServerPlayer> players, int x, int z, int reach) {
		for (ServerPlayer p : players) {
			if (Math.abs(p.getX() - x) <= reach && Math.abs(p.getZ() - z) <= reach) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------
	// Chaque tick
	// ------------------------------------------------------------------

	/** À chaque tick : poses en cours, et, quand quelqu'un est dans l'atelier, catalogue suivi (5 s) et panneaux (2 s). */
	public void tick() {
		ticks++;
		ServerLevel level = levelIfOpen();
		if (level != null && ticks % 20 == 0 && !holds.isEmpty()) {
			refreshHolds(level);
		}
		queue.tick(server);
		if (level == null || level.players().isEmpty()) {
			return;
		}
		if (ticks % 100 == 50 && !queue.busy()) {
			sync(level);
		}
		if (ticks % 40 == 10) {
			refreshLabels(level);
		}
	}

	// ------------------------------------------------------------------
	// Les actions (commandes /vc structures, pupitres, écran)
	// ------------------------------------------------------------------

	/** Le monde de l'atelier où se trouve ce joueur, s'il peut y construire ; sinon une GameException. */
	private ServerLevel here(ServerPlayer p) {
		if (!(p.level() instanceof ServerLevel level) || !isWorkshop(level)) {
			throw new GameException("Allez d'abord dans l'atelier des structures : /vc structures atelier.");
		}
		BuildRights.require(p, worlds.entryOf(level));
		return level;
	}

	/**
	 * La parcelle visée : celle qu'on nomme, sinon celle où l'on se tient (bordure et bout d'allée compris), sinon
	 * la dernière choisie (pupitre, écran) si l'on n'en est pas loin.
	 */
	private StructurePlots.Plot current(ServerPlayer p, ServerLevel level, String id) {
		StructurePlots.Layout l = layout(level);
		if (id != null && !id.isBlank()) {
			StructurePlots.Plot named = l.plot(id.strip());
			if (named == null) {
				throw new GameException("Aucune parcelle « " + id.strip() + " » dans l'atelier. La liste : /vc structures liste.");
			}
			return named;
		}
		StructurePlots.Plot at = l.at(p.getBlockX(), p.getBlockZ());
		if (at != null) {
			return at;
		}
		String f = focus.get(p.getUUID());
		StructurePlots.Plot chosen = f == null ? null : l.plot(f);
		if (chosen != null && Math.abs(p.getX() - chosen.x) <= chosen.rayon + 40 && Math.abs(p.getZ() - chosen.z) <= chosen.rayon + 40) {
			return chosen;
		}
		throw new GameException("Placez-vous sur une parcelle (ou devant son pupitre), ou nommez-la : /vc structures liste.");
	}

	private void requireIdle(StructurePlots.Plot p) {
		if (pending.contains(p.id)) {
			throw new GameException("La parcelle « " + name(p) + " » est en train d'être posée : attendez la fin de la barre.");
		}
	}

	/** Note le résultat d'une action, pour l'écran de l'atelier. */
	void note(ServerPlayer p, String text, boolean error) {
		if (text != null && !text.isBlank()) {
			replies.put(p.getUUID(), new Reply(++replyCount, text, error));
		}
	}

	/** /vc structures atelier : l'atelier (créé et disposé au besoin), à la dernière position du joueur. */
	public String go(ServerPlayer p) {
		WorldEntry e = entry();
		BuildRights.require(p, e);
		boolean created = e == null;
		e = ensure();
		ServerLevel level = worlds.level(e.id());
		sync(level);
		var travel = ValcombeMod.travel();
		boolean first = travel == null || travel.positions().get(p.getUUID(), e.id()) == null;
		if (p.level() != level) {
			if (travel != null) {
				travel.go(p, e.id(), null);
			} else {
				worlds.teleport(p, e.id());
			}
		}
		if (first && p.level() == level) {
			// Première visite : face aux rangées, le pupitre d'accueil devant soi.
			p.teleportTo(level, 0.5, S + 1, 0.5, Set.of(), 0f, 10f, false);
		}
		int n = layout(level).parcelles.size();
		return (created ? "Atelier des structures créé. " : "") + "Te voilà dans l'atelier des structures : " + n + " parcelles"
			+ (queue.busy() ? " (la pose est en cours, une barre suit l'avancement)" : "")
			+ ". Le pupitre de chaque parcelle ouvre ses actions ; celui de l'accueil, la liste et « Nouvelle structure ».";
	}

	/** Marque cette parcelle comme choisie par le joueur (l'écran, avant une action) ; renvoie son nom. */
	public String choose(ServerPlayer p, String id) {
		ServerLevel level = here(p);
		StructurePlots.Plot plot = current(p, level, id);
		focus.put(p.getUUID(), plot.id);
		return "";
	}

	/** Enregistrer : l'intérieur de la parcelle va au catalogue (une structure fournie devient une surcharge). */
	public String save(ServerPlayer p, String id) {
		ServerLevel level = here(p);
		StructurePlots.Plot plot = current(p, level, id);
		requireIdle(plot);
		Captured cap = capture(level, plot);
		// « Changée ailleurs » : la version venue d'ailleurs (éditeur, Atelier web, pack) n'est pas perdue, une copie
		// part à la corbeille avant d'être remplacée par celle de la parcelle.
		boolean keptOther = plot.conflit && !plot.brouillon && store.backup(plot.id);
		String msg;
		if (plot.brouillon) {
			msg = store.save(plot.id, plot.nom, cap.clip(), cap.ancre(), "");
			plot.brouillon = false;
		} else if (StructureStore.isBuiltin(plot.id)) {
			msg = store.saveOverride(plot.id, cap.clip(), cap.ancre());
		} else {
			StructureStore.Def d = store.def(plot.id);
			msg = store.save(plot.id, d.nom, cap.clip(), cap.ancre(), d.repere);
		}
		plot.signature = store.signature(plot.id);
		plot.conflit = false;
		remember(plot, cap.clip(), new int[] {cap.ancre().getX(), cap.ancre().getY(), cap.ancre().getZ()});
		focus.put(p.getUUID(), plot.id);
		saveLayout();
		label(level, plot);
		return keptOther ? msg + " La version changée ailleurs est gardée dans la corbeille." : msg;
	}

	/** Revenir à l'originale : la surcharge d'une structure fournie part à la corbeille, l'originale est reposée. */
	public String revert(ServerPlayer p, String id) {
		ServerLevel level = here(p);
		StructurePlots.Plot plot = current(p, level, id);
		requireIdle(plot);
		if (!StructureStore.isBuiltin(plot.id)) {
			throw new GameException("« " + name(plot) + " » n'est pas une structure fournie : il n'y a pas d'originale. "
				+ "« Reposer » remet la version enregistrée.");
		}
		String msg = store.isOverridden(plot.id) ? store.revert(plot.id) : "« " + name(plot) + " » était déjà l'originale : elle est reposée.";
		plot.signature = store.signature(plot.id);
		plot.conflit = false;
		place(level, plot, original(plot.id), "Atelier structures : « " + name(plot) + " » revient à l'originale");
		focus.put(p.getUUID(), plot.id);
		label(level, plot);
		return msg;
	}

	/** Reposer : les changements en cours sont abandonnés, la version du catalogue revient sur la parcelle. */
	public String reset(ServerPlayer p, String id) {
		ServerLevel level = here(p);
		StructurePlots.Plot plot = current(p, level, id);
		requireIdle(plot);
		plot.conflit = false;
		if (plot.brouillon) {
			place(level, plot, null, "Atelier structures : parcelle « " + name(plot) + " » vidée");
			return "Parcelle « " + name(plot) + " » remise à neuf, vide.";
		}
		StructureStore.Def d = store.def(plot.id);
		if (!fits(plot, d)) {
			throw new GameException("« " + d.nom + " » ne tient plus dans sa parcelle : elle sera déplacée au prochain passage.");
		}
		plot.signature = store.signature(plot.id);
		place(level, plot, library(plot.id), "Atelier structures : « " + d.nom + " » reposée");
		focus.put(p.getUUID(), plot.id);
		return "« " + d.nom + " » reposée telle qu'enregistrée : les changements en cours sont abandonnés.";
	}

	/** Tourner : le contenu de la parcelle fait un quart de tour (sens des aiguilles d'une montre) autour de l'origine. */
	public String rotate(ServerPlayer p, String id) {
		ServerLevel level = here(p);
		StructurePlots.Plot plot = current(p, level, id);
		requireIdle(plot);
		int side = 2 * plot.rayon + 1;
		BlockPos min = new BlockPos(plot.x - plot.rayon, S - plot.bas, plot.z - plot.rayon);
		Clip turned = Clip.capture(level, min, side, plot.bas + plot.haut + 1, side).turned(new Turn(1, false));
		BuildQueue.Plan plan = turned.plan(level, min, false, false);
		pending.add(plot.id);
		dirtyCache.remove(plot.id);
		queue.add("Atelier structures : « " + name(plot) + " » tournée", level, plan, () -> pending.remove(plot.id),
			pl -> pl.level() == level, () -> pending.remove(plot.id));
		focus.put(p.getUUID(), plot.id);
		String house = StructureStore.isBuiltin(plot.id) && "maisons".equals(plot.categorie)
			? " Pour une maison fournie, la porte doit regarder le nord (vers le pupitre) : le village la tourne vers la place." : "";
		return "« " + name(plot) + " » tournée d'un quart de tour. « Enregistrer » garde cette orientation." + house;
	}

	/** Renommer une structure créée (ou une parcelle neuve). */
	public String rename(ServerPlayer p, String id, String nom) {
		ServerLevel level = here(p);
		StructurePlots.Plot plot = current(p, level, id);
		if (StructureStore.isBuiltin(plot.id)) {
			throw new GameException("Une structure fournie garde son nom.");
		}
		String n = nom == null ? "" : nom.strip();
		if (n.isEmpty() || n.length() > 50) {
			throw new GameException("Nom de structure : 1 à 50 caractères.");
		}
		String msg;
		if (plot.brouillon) {
			String old = name(plot);
			plot.nom = n;
			saveLayout();
			msg = "Parcelle « " + old + " » renommée « " + n + " ».";
		} else {
			msg = store.rename(plot.id, n);
			plot.signature = store.signature(plot.id);
			saveLayout();
		}
		label(level, plot);
		return msg;
	}

	/** Le repère posé avec une structure créée : maison, salle ou aucun. */
	public String mark(ServerPlayer p, String id, String repere) {
		ServerLevel level = here(p);
		StructurePlots.Plot plot = current(p, level, id);
		if (plot.brouillon) {
			throw new GameException("Enregistrez d'abord la structure, puis choisissez son repère.");
		}
		if (StructureStore.isBuiltin(plot.id)) {
			throw new GameException("Une structure fournie garde son repère.");
		}
		String msg = store.setMark(plot.id, repere);
		plot.signature = store.signature(plot.id);
		saveLayout();
		return msg;
	}

	/** Supprimer une structure créée (elle va à la corbeille) ou abandonner une parcelle neuve ; confirm requis. */
	public String delete(ServerPlayer p, String id, boolean confirm) {
		ServerLevel level = here(p);
		StructurePlots.Plot plot = current(p, level, id);
		requireIdle(plot);
		if (StructureStore.isBuiltin(plot.id)) {
			throw new GameException("Une structure fournie ne se supprime pas. « Revenir à l'originale » efface vos changements.");
		}
		if (!confirm) {
			return "Supprimer « " + name(plot) + " » ? " + (plot.brouillon ? "La parcelle neuve sera rasée."
				: "La structure ira à la corbeille (" + store.trashDir() + ").") + " Confirmez : /vc structures supprimer confirmer";
		}
		String msg = plot.brouillon ? "Parcelle « " + name(plot) + " » abandonnée." : store.delete(plot.id);
		clear(level, plot);
		focus.remove(p.getUUID());
		saveLayout();
		return msg;
	}

	/** Nouvelle structure : une parcelle vide, nommée, au bout de la rangée « Vos structures » ; le joueur y est conduit. */
	public String create(ServerPlayer p, String size, String nom) {
		ServerLevel level = here(p);
		int k = SIZES.indexOf(size == null ? "" : size.strip().toLowerCase(Locale.ROOT));
		if (k < 0) {
			throw new GameException("Taille : petite, moyenne, grande ou immense.");
		}
		String n = nom == null ? "" : nom.strip();
		if (n.isEmpty() || n.length() > 50) {
			throw new GameException("Nom de structure : 1 à 50 caractères.");
		}
		StructurePlots.Layout l = layout(level);
		String id = store.freeId(n, x -> l.plot(x) != null);
		StructurePlots.Need need = sized(id, "perso", SIZE_VALUES[k][0], 3, SIZE_VALUES[k][1]);
		int bands = l.bandes.size();
		StructurePlots.Plot plot = StructurePlots.allocate(l, List.of(need)).get(0);
		plot.brouillon = true;
		plot.nom = n;
		if (l.bandes.size() != bands) {
			placeGrounds(level, l);
		}
		place(level, plot, null, "Atelier structures : parcelle « " + n + " »");
		focus.put(p.getUUID(), plot.id);
		p.teleportTo(level, plot.x + 0.5, S + 1, plot.lecternZ() - 1.5, Set.of(), 0f, 20f, false);
		int dim = 2 * plot.rayon + 1;
		return "Parcelle « " + n + " » ouverte (" + dim + " × " + dim + ", " + plot.haut + " de haut) : bâtissez dans le quadrillage, "
			+ "puis « Enregistrer » à son pupitre. Identifiant : " + id + ".";
	}

	/** Conduit le joueur devant le pupitre d'une parcelle. */
	public String visit(ServerPlayer p, String id) {
		ServerLevel level = here(p);
		StructurePlots.Plot plot = current(p, level, id);
		focus.put(p.getUUID(), plot.id);
		p.teleportTo(level, plot.x + 0.5, S + 1, plot.lecternZ() - 1.5, Set.of(), 0f, 20f, false);
		return "Devant « " + name(plot) + " ».";
	}

	/** La liste des parcelles dans le chat, par rangée, chacune cliquable pour y aller. */
	public List<Component> list(ServerPlayer p) {
		ServerLevel level = here(p);
		List<Component> out = new ArrayList<>();
		String cat = null;
		MutableComponent line = null;
		for (StructurePlots.Plot plot : layout(level).ordered()) {
			if (!plot.categorie.equals(cat)) {
				if (line != null) {
					out.add(line);
				}
				cat = plot.categorie;
				line = Component.literal(categoryName(cat) + " : ").withStyle(ChatFormatting.GOLD);
			} else {
				line.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
			}
			String st = state(plot);
			line.append(Component.literal(name(plot) + (st.equals("fournie") ? "" : " (" + stateName(st) + ")"))
				.withStyle(s -> s.withColor(st.equals("modifiee") ? ChatFormatting.YELLOW : st.equals("creee") ? ChatFormatting.GREEN
					: ChatFormatting.WHITE).withClickEvent(new ClickEvent.RunCommand("/vc structures aller " + plot.id))));
		}
		if (line != null) {
			out.add(line);
		}
		if (out.isEmpty()) {
			out.add(Component.literal("Aucune parcelle pour l'instant.").withStyle(ChatFormatting.GRAY));
		}
		return out;
	}

	/**
	 * Un clic droit sur un pupitre de l'atelier : l'écran de l'atelier s'ouvre sur cette parcelle (le pupitre
	 * d'accueil : sur la liste). Renvoie vrai si le clic est pour l'atelier.
	 */
	public boolean onUse(ServerPlayer p, ServerLevel level, BlockPos pos, boolean act) {
		if (!isWorkshop(level) || pos.getY() != S + 1 || !level.getBlockState(pos).is(Blocks.LECTERN)) {
			return false;
		}
		StructurePlots.Plot plot = layout(level).lecternAt(pos.getX(), pos.getZ());
		if (plot == null && !pos.equals(WELCOME)) {
			return false;
		}
		if (!act) {
			return true; // l'autre main : le clic est déjà pris
		}
		if (!BuildRights.canBuild(p, worlds.entryOf(level))) {
			p.sendSystemMessage(Gui.plain("Ce pupitre est réservé aux opérateurs et à leurs invités.", ChatFormatting.GRAY));
			return true;
		}
		if (plot != null) {
			focus.put(p.getUUID(), plot.id);
		}
		if (ViewSync.hasClientMod(p)) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p,
				new fr.valcombe.net.ValcombeNet.OpenPayload("structures:" + (plot == null ? "" : plot.id)));
		} else if (plot != null) {
			p.sendSystemMessage(Gui.plain("« " + name(plot) + " » (" + stateName(state(plot)) + ") : /vc structures enregistrer, "
				+ "originale, reposer, tourner, renommer <nom>, supprimer.", ChatFormatting.GOLD));
		} else {
			list(p).forEach(p::sendSystemMessage);
		}
		return true;
	}

	// ------------------------------------------------------------------
	// Ce que voient l'écran et les tests
	// ------------------------------------------------------------------

	private ParcelInfo info(StructurePlots.Plot p) {
		return new ParcelInfo(p.id, name(p), p.categorie, state(p), p.x, p.z, p.rayon, p.bas, p.haut, p.lecternZ(), p.conflit,
			pending.contains(p.id));
	}

	/** Les parcelles de l'atelier (vide s'il n'a pas encore été disposé), rangée par rangée. Fil du serveur. */
	public List<ParcelInfo> parcels() {
		ServerLevel level = levelIfOpen();
		if (level == null) {
			return List.of();
		}
		return layout(level).ordered().stream().map(this::info).toList();
	}

	/** La parcelle de cette structure, ou null. */
	public ParcelInfo parcel(String id) {
		return parcels().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
	}

	/** La parcelle a-t-elle des modifications non enregistrées ? null si elle est en cours de pose ou n'existe pas. */
	public Boolean changes(String id) {
		ServerLevel level = levelIfOpen();
		StructurePlots.Plot p = level == null ? null : layout(level).plot(id);
		if (p == null || pending.contains(id)) {
			return null;
		}
		dirtyCache.remove(id);
		return changes(level, p);
	}

	/** Ce que l'écran de l'atelier reçoit, pour un joueur qui s'y trouve (joint à sa vue, sous « atelierStructures »). */
	public Map<String, Object> view(ServerPlayer p) {
		Map<String, Object> m = new LinkedHashMap<>();
		if (!(p.level() instanceof ServerLevel level) || !isWorkshop(level)) {
			return m;
		}
		StructurePlots.Layout l = layout(level);
		List<Map<String, Object>> list = new ArrayList<>();
		for (StructurePlots.Plot plot : l.ordered()) {
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("id", plot.id);
			o.put("nom", name(plot));
			o.put("categorie", plot.categorie);
			o.put("rangee", categoryName(plot.categorie));
			o.put("etat", state(plot));
			o.put("conflit", plot.conflit);
			o.put("pose", pending.contains(plot.id));
			o.put("cote", 2 * plot.rayon + 1);
			o.put("haut", plot.haut);
			if (!plot.brouillon && store.exists(plot.id)) {
				StructureStore.Def d = store.def(plot.id);
				o.put("taille", d.taille);
				o.put("blocs", d.blocs);
				o.put("repere", d.repere);
				if (d.modifieeLe != null) {
					o.put("modifieeLe", d.modifieeLe);
				}
			}
			list.add(o);
		}
		m.put("parcelles", list);
		// La parcelle choisie (pupitre, écran, dernière action), sinon celle où le joueur se tient.
		String f = focus.get(p.getUUID());
		StructurePlots.Plot chosen = f == null ? null : l.plot(f);
		if (chosen == null) {
			chosen = l.at(p.getBlockX(), p.getBlockZ());
		}
		m.put("choisie", chosen == null ? null : chosen.id);
		m.put("ici", l.at(p.getBlockX(), p.getBlockZ()) == null ? null : l.at(p.getBlockX(), p.getBlockZ()).id);
		m.put("changements", chosen == null ? null : changes(level, chosen));
		m.put("pose", queue.busy());
		m.put("tailles", SIZES);
		Reply r = replies.get(p.getUUID());
		if (r != null) {
			m.put("message", Map.of("n", r.n(), "texte", r.text(), "erreur", r.error()));
		}
		return m;
	}
}
