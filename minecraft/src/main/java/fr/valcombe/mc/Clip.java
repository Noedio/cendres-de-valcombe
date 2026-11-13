package fr.valcombe.mc;

import fr.valcombe.rules.GameException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;

/**
 * Un volume de blocs gardé en mémoire : une structure du catalogue, le presse-papiers d'un
 * constructeur, l'état d'une zone avant une opération (pour l'annuler), une construction importée.
 * Chaque case porte un rang dans la palette, ou EMPTY : « ne rien poser ici ». Le contenu des blocs
 * (coffres, pancartes…) suit. Le format de fichier est celui des structures de Minecraft (.nbt), mais
 * relu ici case par case : rien ne peut être posé hors du volume annoncé, et aucune entité n'est créée.
 */
final class Clip {
	static final int EMPTY = -1;
	/** Pose exacte : ni voisins réveillés, ni objets lâchés, ni coffre vidé au sol. */
	static final int EXACT = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
	/** Blocs dont on ne reprend jamais les données d'un fichier : ils exécutent des commandes ou font apparaître des créatures. */
	private static final Set<String> UNSAFE = Set.of("command_block", "chain_command_block", "repeating_command_block",
		"structure_block", "jigsaw", "spawner", "trial_spawner", "vault", "test_block", "test_instance_block", "beehive", "bee_nest");

	final int width;
	final int height;
	final int length;
	final List<BlockState> palette;
	/** Rang dans la palette de chaque case, rangées par x, puis z, puis y. */
	final int[] cells;
	/** Contenu des blocs qui en ont un : rang de la case → données. */
	final Map<Integer, CompoundTag> data;

	Clip(int width, int height, int length, List<BlockState> palette, int[] cells, Map<Integer, CompoundTag> data) {
		this.width = width;
		this.height = height;
		this.length = length;
		this.palette = palette;
		this.cells = cells;
		this.data = data;
	}

	long volume() {
		return cells.length;
	}

	/** Nombre de cases qui posent un bloc. */
	int count() {
		int n = 0;
		for (int c : cells) {
			if (c != EMPTY) {
				n++;
			}
		}
		return n;
	}

	String dims() {
		return width + " × " + height + " × " + length;
	}

	private int index(int x, int y, int z) {
		return (y * length + z) * width + x;
	}

	// ------------------------------------------------------------------
	// Lecture d'un fichier de structure
	// ------------------------------------------------------------------
	/** Structure lue et contrôlée, pas encore traduite en blocs : cela se vérifie sans Minecraft lancé. */
	record Raw(int width, int height, int length, int[] cells, ListTag palette, Map<Integer, CompoundTag> data) {
	}

	/**
	 * Lit une structure au format de Minecraft en la tenant dans les dimensions permises. Dans ce format,
	 * chaque bloc porte sa propre position : un fichier fabriqué pourrait annoncer 1 × 1 × 1 et poser des
	 * blocs à des milliers de cases de là. Un tel fichier est refusé en entier.
	 */
	static Raw parse(CompoundTag tag, int maxWidth, int maxHeight, int maxLength) {
		ListTag size = tag.getListOrEmpty("size");
		int w = size.getIntOr(0, 0);
		int h = size.getIntOr(1, 0);
		int l = size.getIntOr(2, 0);
		if (size.size() != 3 || w <= 0 || h <= 0 || l <= 0) {
			throw new GameException("Construction vide, ou sans dimensions : ce n'est pas une structure Minecraft.");
		}
		if (w > maxWidth || h > maxHeight || l > maxLength) {
			throw new GameException("Construction trop grande (" + w + " × " + h + " × " + l + ") : " + maxWidth + " × " + maxHeight
				+ " × " + maxLength + " au plus.");
		}
		ListTag palette = tag.getListOrEmpty("palette");
		if (palette.isEmpty()) {
			palette = tag.getListOrEmpty("palettes").getListOrEmpty(0); // structures à plusieurs palettes : la première
		}
		if (palette.isEmpty()) {
			throw new GameException("Construction sans palette de blocs.");
		}
		int[] cells = new int[w * h * l];
		Arrays.fill(cells, EMPTY);
		Map<Integer, CompoundTag> data = new HashMap<>();
		ListTag blocks = tag.getListOrEmpty("blocks");
		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag b = blocks.getCompoundOrEmpty(i);
			ListTag pos = b.getListOrEmpty("pos");
			int x = pos.getIntOr(0, -1);
			int y = pos.getIntOr(1, -1);
			int z = pos.getIntOr(2, -1);
			if (pos.size() != 3 || x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= l) {
				throw new GameException("Construction refusée : un bloc y est déclaré hors de ses dimensions (" + w + " × " + h
					+ " × " + l + ").");
			}
			int state = b.getIntOr("state", -1);
			if (state < 0 || state >= palette.size()) {
				throw new GameException("Construction refusée : un bloc désigne un état absent de la palette.");
			}
			int at = (y * l + z) * w + x;
			cells[at] = state;
			if (b.contains("nbt")) {
				data.put(at, b.getCompoundOrEmpty("nbt"));
			}
		}
		return new Raw(w, h, l, cells, palette, data);
	}

	/** Traduit une structure lue : blocs inconnus remplacés par de l'air, données dangereuses retirées. */
	static Clip of(Raw raw, HolderGetter<Block> lookup) {
		List<BlockState> palette = new ArrayList<>();
		boolean voids = false;
		for (int i = 0; i < raw.palette().size(); i++) {
			BlockState s = NbtUtils.readBlockState(lookup, raw.palette().getCompoundOrEmpty(i));
			voids |= s.is(Blocks.STRUCTURE_VOID);
			palette.add(s);
		}
		int[] cells = raw.cells();
		if (voids) {
			for (int i = 0; i < cells.length; i++) {
				if (cells[i] != EMPTY && palette.get(cells[i]).is(Blocks.STRUCTURE_VOID)) {
					cells[i] = EMPTY;
				}
			}
		}
		Map<Integer, CompoundTag> data = new HashMap<>();
		raw.data().forEach((at, tag) -> {
			CompoundTag safe = cells[at] == EMPTY ? null : safeData(palette.get(cells[at]), tag);
			if (safe != null) {
				data.put(at, safe);
			}
		});
		return new Clip(raw.width(), raw.height(), raw.length(), palette, cells, data);
	}

	/** Lit et traduit une structure Minecraft (voir parse). */
	static Clip read(CompoundTag tag, HolderGetter<Block> lookup, int maxWidth, int maxHeight, int maxLength) {
		return of(parse(tag, maxWidth, maxHeight, maxLength), lookup);
	}

	/**
	 * Données d'un bloc venues d'un fichier, sans ce qui pourrait nuire : rien pour les blocs de commande,
	 * générateurs et autres blocs techniques ; ailleurs (pancartes, livres d'un pupitre), les commandes
	 * lancées au clic sont retirées. Renvoie null s'il ne faut rien garder.
	 */
	static CompoundTag safeData(BlockState state, CompoundTag tag) {
		if (UNSAFE.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath())) {
			return null;
		}
		CompoundTag copy = tag.copy();
		stripClicks(copy);
		return copy;
	}

	/** Retire, à toute profondeur, les actions au clic d'un texte. */
	static void stripClicks(Tag tag) {
		if (tag instanceof CompoundTag c) {
			for (String key : List.copyOf(c.keySet())) {
				if (key.equals("click_event") || key.equals("clickEvent")) {
					c.remove(key);
				} else {
					stripClicks(c.get(key));
				}
			}
		} else if (tag instanceof ListTag list) {
			for (Tag t : list) {
				stripClicks(t);
			}
		}
	}

	/** La structure au format de Minecraft (lisible aussi par un bloc de structure). */
	CompoundTag toStructure() {
		CompoundTag tag = new CompoundTag();
		tag.put("size", ints(width, height, length));
		ListTag pal = new ListTag();
		for (BlockState s : palette) {
			pal.add(NbtUtils.writeBlockState(s));
		}
		tag.put("palette", pal);
		ListTag blocks = new ListTag();
		for (int i = 0; i < cells.length; i++) {
			if (cells[i] == EMPTY) {
				continue;
			}
			CompoundTag b = new CompoundTag();
			b.put("pos", ints(i % width, i / (width * length), i / width % length));
			b.putInt("state", cells[i]);
			CompoundTag d = data.get(i);
			if (d != null) {
				b.put("nbt", d);
			}
			blocks.add(b);
		}
		tag.put("blocks", blocks);
		tag.put("entities", new ListTag());
		return NbtUtils.addCurrentDataVersion(tag);
	}

	private static ListTag ints(int a, int b, int c) {
		ListTag l = new ListTag();
		l.add(IntTag.valueOf(a));
		l.add(IntTag.valueOf(b));
		l.add(IntTag.valueOf(c));
		return l;
	}

	// ------------------------------------------------------------------
	// Autres provenances
	// ------------------------------------------------------------------
	/** Copie un volume du monde, contenu des blocs compris ; min est son coin le plus bas. */
	static Clip capture(ServerLevel level, BlockPos min, int width, int height, int length) {
		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> ids = new IdentityHashMap<>();
		int[] cells = new int[width * height * length];
		Map<Integer, CompoundTag> data = new HashMap<>();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int i = 0;
		for (int y = 0; y < height; y++) {
			for (int z = 0; z < length; z++) {
				for (int x = 0; x < width; x++, i++) {
					BlockState s = level.getBlockState(m.set(min.getX() + x, min.getY() + y, min.getZ() + z));
					Integer id = ids.get(s);
					if (id == null) {
						id = palette.size();
						palette.add(s);
						ids.put(s, id);
					}
					cells[i] = id;
					if (s.hasBlockEntity()) {
						BlockEntity be = level.getBlockEntity(m);
						if (be != null) {
							data.put(i, be.saveWithoutMetadata(level.registryAccess()));
						}
					}
				}
			}
		}
		return new Clip(width, height, length, palette, cells, data);
	}

	/** Une construction lue dans une schématique (WorldEdit, Litematica) : des blocs seuls. */
	static Clip of(SchematicImporter.Shape s) {
		return new Clip(s.width(), s.height(), s.length(), s.palette(), s.blocks(), new HashMap<>());
	}

	/** Un volume découpé dans un plan de chantier, et son coin le plus bas dans les coordonnées du plan. */
	record Cut(Clip clip, BlockPos min) {
	}

	/** Les blocs d'un plan (une maison du village, par exemple) ; les cases que le plan ne touche pas restent vides. */
	static Cut of(BuildQueue.Plan plan) {
		if (plan.at.isEmpty()) {
			throw new IllegalArgumentException("plan vide");
		}
		int x0 = Integer.MAX_VALUE;
		int y0 = Integer.MAX_VALUE;
		int z0 = Integer.MAX_VALUE;
		int x1 = Integer.MIN_VALUE;
		int y1 = Integer.MIN_VALUE;
		int z1 = Integer.MIN_VALUE;
		for (BlockPos p : plan.at) {
			x0 = Math.min(x0, p.getX());
			y0 = Math.min(y0, p.getY());
			z0 = Math.min(z0, p.getZ());
			x1 = Math.max(x1, p.getX());
			y1 = Math.max(y1, p.getY());
			z1 = Math.max(z1, p.getZ());
		}
		int w = x1 - x0 + 1;
		int h = y1 - y0 + 1;
		int l = z1 - z0 + 1;
		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> ids = new IdentityHashMap<>();
		int[] cells = new int[w * h * l];
		Arrays.fill(cells, EMPTY);
		for (int i = 0; i < plan.at.size(); i++) {
			BlockPos p = plan.at.get(i);
			BlockState s = plan.states.get(i);
			Integer id = ids.get(s);
			if (id == null) {
				id = palette.size();
				palette.add(s);
				ids.put(s, id);
			}
			cells[((p.getY() - y0) * l + p.getZ() - z0) * w + p.getX() - x0] = id; // un bloc posé plus tard remplace le précédent
		}
		return new Cut(new Clip(w, h, l, palette, cells, new HashMap<>()), new BlockPos(x0, y0, z0));
	}

	/**
	 * Ce volume (coin en myCorner) avec, là où ils se recouvrent, les cases de patch (coin en patchCorner) :
	 * de quoi savoir ce que deviendra une zone une fois une ancienne pose effacée.
	 */
	Clip withPatch(BlockPos myCorner, Clip patch, BlockPos patchCorner) {
		List<BlockState> pal = new ArrayList<>(palette);
		Map<BlockState, Integer> rank = new HashMap<>();
		for (int i = 0; i < pal.size(); i++) {
			rank.putIfAbsent(pal.get(i), i);
		}
		int[] out = cells.clone();
		Map<Integer, CompoundTag> outData = new HashMap<>(data);
		for (int y = 0; y < patch.height; y++) {
			for (int z = 0; z < patch.length; z++) {
				for (int x = 0; x < patch.width; x++) {
					int mx = patchCorner.getX() + x - myCorner.getX();
					int my = patchCorner.getY() + y - myCorner.getY();
					int mz = patchCorner.getZ() + z - myCorner.getZ();
					if (mx < 0 || my < 0 || mz < 0 || mx >= width || my >= height || mz >= length) {
						continue;
					}
					int from = patch.index(x, y, z);
					int to = index(mx, my, mz);
					int c = patch.cells[from];
					if (c == EMPTY) {
						continue;
					}
					BlockState s = patch.palette.get(c);
					Integer r = rank.get(s);
					if (r == null) {
						r = pal.size();
						pal.add(s);
						rank.put(s, r);
					}
					out[to] = r;
					outData.remove(to);
					CompoundTag d = patch.data.get(from);
					if (d != null) {
						outData.put(to, d);
					}
				}
			}
		}
		return new Clip(width, height, length, pal, out, outData);
	}

	// ------------------------------------------------------------------
	// Orienter et poser
	// ------------------------------------------------------------------
	/** Le même volume, retourné et tourné : les blocs orientés (escaliers, portes…) suivent. */
	Clip turned(Turn turn) {
		if (turn.equals(Turn.NONE)) {
			return this;
		}
		Rotation rotation = Rotation.values()[turn.quarters()];
		List<BlockState> turnedPalette = new ArrayList<>();
		for (BlockState s : palette) {
			turnedPalette.add((turn.flipped() ? s.mirror(Mirror.FRONT_BACK) : s).rotate(rotation));
		}
		int w = turn.width(width, length);
		int l = turn.length(width, length);
		int[] out = new int[cells.length];
		Map<Integer, CompoundTag> outData = new HashMap<>();
		for (int y = 0; y < height; y++) {
			for (int z = 0; z < length; z++) {
				for (int x = 0; x < width; x++) {
					int from = index(x, y, z);
					int to = (y * l + turn.z(x, z, width, length)) * w + turn.x(x, z, width, length);
					out[to] = cells[from];
					CompoundTag d = data.get(from);
					if (d != null) {
						outData.put(to, d);
					}
				}
			}
		}
		return new Clip(w, height, l, turnedPalette, out, outData);
	}

	/** Blocs dont la forme dépend des voisins : barrières, vitres, barreaux, murets. */
	static boolean connects(BlockState s) {
		return s.getBlock() instanceof CrossCollisionBlock || s.getBlock() instanceof WallBlock;
	}

	/**
	 * Seconde passe sur un pavé déjà posé : barrières, vitres et murets prennent la forme que leur donnent
	 * leurs voisins. (La pose exacte ne réveille personne : sans cela, ils resteraient de simples poteaux.)
	 */
	static BuildQueue.Part settle(ServerLevel level, BlockPos corner, int width, int height, int length) {
		return new BuildQueue.Part() {
			@Override
			int size() {
				return width * height * length;
			}

			@Override
			BlockState at(int i, BlockPos.MutableBlockPos pos) {
				pos.set(corner.getX() + i % width, corner.getY() + i / (width * length), corner.getZ() + i / width % length);
				BlockState now = level.getBlockState(pos);
				return connects(now) ? Block.updateFromNeighbourShapes(now, level, pos) : null;
			}

			@Override
			int flags() {
				return EXACT;
			}
		};
	}

	/**
	 * Le plan qui pose ce volume, son coin le plus bas en corner : les blocs par petits lots, puis leur
	 * contenu. skipAir : l'air du volume n'efface rien (coller « sans air »). settle : barrières, vitres
	 * et murets se raccordent ensuite (structures bâties par le code, dont les blocs n'ont pas encore de forme).
	 */
	BuildQueue.Plan plan(ServerLevel level, BlockPos corner, boolean skipAir, boolean settle) {
		BuildQueue.Plan plan = new BuildQueue.Plan();
		plan.first(new BuildQueue.Part() {
			@Override
			int size() {
				return cells.length;
			}

			@Override
			BlockState at(int i, BlockPos.MutableBlockPos pos) {
				int c = cells[i];
				if (c == EMPTY) {
					return null;
				}
				BlockState s = palette.get(c);
				if (skipAir && s.isAir()) {
					return null;
				}
				pos.set(corner.getX() + i % width, corner.getY() + i / (width * length), corner.getZ() + i / width % length);
				return s;
			}

			@Override
			int flags() {
				return EXACT;
			}
		});
		if (settle && palette.stream().anyMatch(Clip::connects)) {
			plan.first(settle(level, corner, width, height, length));
		}
		if (!data.isEmpty()) {
			plan.after.add(() -> data.forEach((i, tag) -> {
				BlockPos pos = corner.offset(i % width, i / (width * length), i / width % length);
				BlockEntity be = level.getBlockEntity(pos);
				BlockState s = level.getBlockState(pos);
				if (be != null && s == palette.get(cells[i])) {
					be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
					be.setChanged();
					level.sendBlockUpdated(pos, s, s, Block.UPDATE_CLIENTS);
				}
			}));
		}
		return plan;
	}
}
