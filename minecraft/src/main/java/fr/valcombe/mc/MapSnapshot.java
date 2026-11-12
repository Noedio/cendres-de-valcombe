package fr.valcombe.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;

/**
 * Instantané de la carte, pris au lancement d'une partie : tous les blocs de son emprise (avec le
 * contenu des coffres, pupitres, panneaux…) et la liste des objets qui traînaient déjà au sol.
 * À la fin de la partie, il sert à remettre la carte exactement comme elle était — qu'elle soit
 * générée, chargée de la bibliothèque ou bâtie à la main et jamais enregistrée.
 */
final class MapSnapshot {
	/** Hauteur gardée : 8 blocs sous la place, 56 au-dessus. */
	private static final int BELOW = 8;
	private static final int HEIGHT = 64;
	private static final long MAX_NBT = 256L * 1024 * 1024;
	/** Pose exacte : ni voisins réveillés, ni objets lâchés, ni coffre vidé au sol. */
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

	private record Kept(BlockPos pos, CompoundTag data) {
	}

	private final BlockPos center;
	private final int radius;
	private final int bottom;
	private final int height;
	private final List<BlockState> palette;
	private final Runs blocks;
	private final List<Kept> blockEntities;
	/** Objets au sol, orbes et projectiles déjà présents avant la partie : ceux-là ne sont pas retirés. */
	private final Set<UUID> loose;
	/** Graine du monde où l'instantané a été pris : il ne se remet pas dans un autre monde. */
	private long seed;

	private MapSnapshot(BlockPos center, int radius, int bottom, int height, List<BlockState> palette, Runs blocks,
			List<Kept> blockEntities, Set<UUID> loose) {
		this.center = center;
		this.radius = radius;
		this.bottom = bottom;
		this.height = height;
		this.palette = palette;
		this.blocks = blocks;
		this.blockEntities = blockEntities;
		this.loose = loose;
	}

	BlockPos center() {
		return center;
	}

	int radius() {
		return radius;
	}

	private AABB box() {
		return new AABB(center.getX() - radius, bottom, center.getZ() - radius,
			center.getX() + radius + 1, bottom + height, center.getZ() + radius + 1);
	}

	/** Ce qui traîne et qu'une partie peut laisser derrière elle. */
	private static boolean isLoose(Entity e) {
		return e instanceof ItemEntity || e instanceof ExperienceOrb || e instanceof Projectile;
	}

	// ------------------------------------------------------------------
	// Prise de l'instantané
	// ------------------------------------------------------------------
	static MapSnapshot capture(ServerLevel level, BlockPos center, int radius) {
		int bottom = Math.max(level.getMinY(), center.getY() - BELOW);
		int height = Math.min(level.getMaxY() + 1, bottom + HEIGHT) - bottom;
		int side = 2 * radius + 1;
		int x0 = center.getX() - radius;
		int z0 = center.getZ() - radius;
		int cx0 = x0 >> 4;
		int cz0 = z0 >> 4;
		int nx = (x0 + side - 1 >> 4) - cx0 + 1;
		int nz = (z0 + side - 1 >> 4) - cz0 + 1;
		LevelChunk[] chunks = new LevelChunk[nx * nz];
		for (int i = 0; i < nx; i++) {
			for (int k = 0; k < nz; k++) {
				chunks[i * nz + k] = level.getChunk(cx0 + i, cz0 + k);
			}
		}
		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> ids = new IdentityHashMap<>();
		Runs blocks = new Runs();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		BlockState last = null;
		int lastId = 0;
		for (int y = 0; y < height; y++) {
			for (int z = 0; z < side; z++) {
				LevelChunk chunk = null;
				for (int x = 0; x < side; x++) {
					int wx = x0 + x;
					if (chunk == null || (wx & 15) == 0) {
						chunk = chunks[((wx >> 4) - cx0) * nz + ((z0 + z >> 4) - cz0)];
					}
					BlockState s = chunk.getBlockState(m.set(wx, bottom + y, z0 + z));
					if (s != last) {
						Integer id = ids.get(s);
						if (id == null) {
							id = palette.size();
							palette.add(s);
							ids.put(s, id);
						}
						last = s;
						lastId = id;
					}
					blocks.add(lastId);
				}
			}
		}
		List<Kept> kept = new ArrayList<>();
		for (LevelChunk chunk : chunks) {
			for (BlockEntity be : chunk.getBlockEntities().values()) {
				BlockPos p = be.getBlockPos();
				if (p.getX() >= x0 && p.getX() < x0 + side && p.getZ() >= z0 && p.getZ() < z0 + side
					&& p.getY() >= bottom && p.getY() < bottom + height) {
					kept.add(new Kept(p.immutable(), be.saveWithoutMetadata(level.registryAccess())));
				}
			}
		}
		MapSnapshot snap = new MapSnapshot(center, radius, bottom, height, palette, blocks, kept, new HashSet<>());
		for (Entity e : level.getEntities((Entity) null, snap.box(), MapSnapshot::isLoose)) {
			snap.loose.add(e.getUUID());
		}
		snap.seed = level.getSeed();
		return snap;
	}

	// ------------------------------------------------------------------
	// Fichier
	// ------------------------------------------------------------------
	void write(Path f) throws IOException {
		CompoundTag tag = new CompoundTag();
		tag.putInt("version", 1);
		tag.putLong("graine", seed);
		tag.putIntArray("centre", new int[] {center.getX(), center.getY(), center.getZ()});
		tag.putInt("rayon", radius);
		tag.putInt("bas", bottom);
		tag.putInt("hauteur", height);
		ListTag pal = new ListTag();
		for (BlockState s : palette) {
			pal.add(NbtUtils.writeBlockState(s));
		}
		tag.put("palette", pal);
		tag.putIntArray("blocs", blocks.packed());
		ListTag bes = new ListTag();
		for (Kept k : blockEntities) {
			CompoundTag b = new CompoundTag();
			b.putIntArray("pos", new int[] {k.pos().getX(), k.pos().getY(), k.pos().getZ()});
			b.put("donnees", k.data());
			bes.add(b);
		}
		tag.put("contenus", bes);
		long[] ids = new long[loose.size() * 2];
		int i = 0;
		for (UUID u : loose) {
			ids[i++] = u.getMostSignificantBits();
			ids[i++] = u.getLeastSignificantBits();
		}
		tag.putLongArray("objets", ids);
		// Écrit à côté puis mis en place d'un coup : une coupure ne laisse jamais un instantané tronqué.
		Files.createDirectories(f.getParent());
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		NbtIo.writeCompressed(tag, tmp);
		try {
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException e) {
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Relit un instantané ; lève IOException s'il est illisible ou incohérent. */
	static MapSnapshot read(Path f, ServerLevel level) throws IOException {
		CompoundTag tag;
		try {
			tag = NbtIo.readCompressed(f, NbtAccounter.create(MAX_NBT));
		} catch (RuntimeException e) {
			throw new IOException(e);
		}
		int[] c = tag.getIntArray("centre").orElse(new int[0]);
		int radius = tag.getIntOr("rayon", -1);
		int height = tag.getIntOr("hauteur", 0);
		Runs blocks = Runs.unpack(tag.getIntArray("blocs").orElse(null));
		ListTag pal = tag.getListOrEmpty("palette");
		long side = 2L * radius + 1;
		if (c.length != 3 || radius < 0 || height <= 0 || blocks == null || blocks.size() != side * side * height
			|| blocks.max() >= pal.size()) {
			throw new IOException("instantané incohérent");
		}
		if (tag.getLongOr("graine", level.getSeed()) != level.getSeed()) {
			// En solo, le dossier de configuration sert à tous les mondes : celui-ci n'est pas le monde de la partie.
			throw new IOException("instantané pris dans un autre monde");
		}
		var lookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
		List<BlockState> palette = new ArrayList<>();
		for (int i = 0; i < pal.size(); i++) {
			palette.add(NbtUtils.readBlockState(lookup, pal.getCompoundOrEmpty(i)));
		}
		List<Kept> kept = new ArrayList<>();
		ListTag bes = tag.getListOrEmpty("contenus");
		for (int i = 0; i < bes.size(); i++) {
			CompoundTag b = bes.getCompoundOrEmpty(i);
			int[] p = b.getIntArray("pos").orElse(new int[0]);
			if (p.length == 3) {
				kept.add(new Kept(new BlockPos(p[0], p[1], p[2]), b.getCompoundOrEmpty("donnees")));
			}
		}
		Set<UUID> loose = new HashSet<>();
		long[] ids = tag.getLongArray("objets").orElse(new long[0]);
		for (int i = 0; i + 1 < ids.length; i += 2) {
			loose.add(new UUID(ids[i], ids[i + 1]));
		}
		return new MapSnapshot(new BlockPos(c[0], c[1], c[2]), radius, tag.getIntOr("bas", 0), height, palette, blocks, kept, loose);
	}

	// ------------------------------------------------------------------
	// Remise en place
	// ------------------------------------------------------------------
	/** Retire ce que la partie a laissé traîner dans l'emprise : objets au sol, orbes, projectiles. */
	int removeLoose(ServerLevel level) {
		List<Entity> gone = new ArrayList<>(level.getEntities((Entity) null, box(), e -> isLoose(e) && !loose.contains(e.getUUID())));
		gone.forEach(Entity::discard);
		return gone.size();
	}

	/** Le plan qui remet chaque bloc de l'instantané (seuls ceux qui ont changé sont reposés), puis le contenu des blocs. */
	BuildQueue.Plan restorePlan(ServerLevel level) {
		int side = 2 * radius + 1;
		int x0 = center.getX() - radius;
		int z0 = center.getZ() - radius;
		Runs.Reader reader = blocks.reader();
		BuildQueue.Plan plan = new BuildQueue.Plan();
		plan.first(new BuildQueue.Part() {
			@Override
			int size() {
				return blocks.size();
			}

			@Override
			BlockState at(int i, BlockPos.MutableBlockPos pos) {
				int inLayer = i % (side * side);
				pos.set(x0 + inLayer % side, bottom + i / (side * side), z0 + inLayer / side);
				return palette.get(reader.at(i));
			}

			@Override
			int flags() {
				return FLAGS;
			}
		});
		plan.after.add(() -> {
			for (Kept k : blockEntities) {
				BlockEntity be = level.getBlockEntity(k.pos());
				if (be != null) {
					be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), k.data()));
					be.setChanged();
					BlockState s = level.getBlockState(k.pos());
					level.sendBlockUpdated(k.pos(), s, s, Block.UPDATE_CLIENTS);
				}
			}
		});
		return plan;
	}
}
