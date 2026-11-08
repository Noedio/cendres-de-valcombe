package fr.valcombe.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * Le village de Valcombe, généré autour d'un point : place pavée, cercle de 16 sièges,
 * puits et beffroi au centre, 16 maisons tout autour reliées par des ruelles bordées de haies.
 * Son centre est mémorisé dans le dossier du serveur pour placer les joueurs.
 */
public final class Village {
	public static final int SEATS = 16;
	private static final int SEAT_RADIUS = 9;
	private static final int PLAZA_RADIUS = 13;
	private static final int HOUSE_RADIUS = 27;
	private static final int CLEAR_RADIUS = 36;
	private static final int FLAGS = Block.UPDATE_CLIENTS;

	private final BlockPos center;

	public Village(BlockPos center) {
		this.center = center;
	}

	public BlockPos center() {
		return center;
	}

	// ------------------------------------------------------------------
	// Positions
	// ------------------------------------------------------------------
	private static double angle(int i, int n) {
		return 2 * Math.PI * i / n - Math.PI / 2;
	}

	/** Position du siège i (sur n joueurs) : on répartit les joueurs sur les 16 sièges. */
	public Vec3 seat(int i, int n) {
		int slot = (int) Math.round((double) i * SEATS / n) % SEATS;
		double a = angle(slot, SEATS);
		return new Vec3(center.getX() + 0.5 + Math.round(SEAT_RADIUS * Math.cos(a)),
			center.getY() + 1, center.getZ() + 0.5 + Math.round(SEAT_RADIUS * Math.sin(a)));
	}

	/** Intérieur de la maison du joueur i (sur n). */
	public Vec3 house(int i, int n) {
		int slot = (int) Math.round((double) i * SEATS / n) % SEATS;
		double a = angle(slot, SEATS);
		return new Vec3(center.getX() + 0.5 + Math.round((HOUSE_RADIUS + 1) * Math.cos(a)),
			center.getY() + 1, center.getZ() + 0.5 + Math.round((HOUSE_RADIUS + 1) * Math.sin(a)));
	}

	/** Lacet pour regarder le centre depuis une position. */
	public float yawToCenter(Vec3 from) {
		double dx = center.getX() + 0.5 - from.x;
		double dz = center.getZ() + 0.5 - from.z;
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------
	private void set(ServerLevel level, int x, int y, int z, BlockState s) {
		level.setBlock(new BlockPos(center.getX() + x, center.getY() + y, center.getZ() + z), s, FLAGS);
	}

	private static Direction away(double dx, double dz) {
		if (Math.abs(dx) > Math.abs(dz)) {
			return dx > 0 ? Direction.EAST : Direction.WEST;
		}
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	public void build(ServerLevel level) {
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState grass = Blocks.DIRT_PATH.defaultBlockState();
		BlockState hedge = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		long seed = center.asLong();
		java.util.Random rnd = new java.util.Random(seed);

		// Terrain : on dégage et on aplanit un disque
		for (int x = -CLEAR_RADIUS; x <= CLEAR_RADIUS; x++) {
			for (int z = -CLEAR_RADIUS; z <= CLEAR_RADIUS; z++) {
				double d = Math.hypot(x, z);
				if (d > CLEAR_RADIUS) {
					continue;
				}
				for (int y = -3; y < 0; y++) {
					set(level, x, y, z, Blocks.STONE.defaultBlockState());
				}
				BlockState floor;
				if (d <= PLAZA_RADIUS) {
					floor = rnd.nextInt(5) == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
						: rnd.nextInt(3) == 0 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
				} else {
					floor = rnd.nextInt(6) == 0 ? Blocks.GRAVEL.defaultBlockState() : grass;
				}
				set(level, x, 0, z, floor);
				for (int y = 1; y <= 14; y++) {
					set(level, x, y, z, air);
				}
			}
		}
		// Bordure de la place
		for (int k = 0; k < 360; k += 3) {
			double a = Math.toRadians(k);
			set(level, (int) Math.round((PLAZA_RADIUS + 0.5) * Math.cos(a)), 1, (int) Math.round((PLAZA_RADIUS + 0.5) * Math.sin(a)),
				Blocks.STONE_BRICK_WALL.defaultBlockState());
		}

		// Cercle des sièges, face au centre
		for (int i = 0; i < SEATS; i++) {
			double a = angle(i, SEATS);
			int x = (int) Math.round(SEAT_RADIUS * Math.cos(a));
			int z = (int) Math.round(SEAT_RADIUS * Math.sin(a));
			set(level, x, 0, z, Blocks.POLISHED_ANDESITE.defaultBlockState());
			int bx = (int) Math.round((SEAT_RADIUS + 1) * Math.cos(a));
			int bz = (int) Math.round((SEAT_RADIUS + 1) * Math.sin(a));
			set(level, bx, 1, bz, Blocks.DARK_OAK_STAIRS.defaultBlockState()
				.setValue(StairBlock.FACING, away(bx, bz)));
			if (i % 4 == 0) {
				int lx = (int) Math.round((SEAT_RADIUS + 2) * Math.cos(a + Math.PI / SEATS));
				int lz = (int) Math.round((SEAT_RADIUS + 2) * Math.sin(a + Math.PI / SEATS));
				set(level, lx, 1, lz, Blocks.SPRUCE_FENCE.defaultBlockState());
				set(level, lx, 2, lz, Blocks.SPRUCE_FENCE.defaultBlockState());
				set(level, lx, 3, lz, Blocks.LANTERN.defaultBlockState());
			}
		}

		// Puits et beffroi au centre
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				boolean rim = Math.abs(x) == 2 || Math.abs(z) == 2;
				set(level, x, 0, z, rim ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : Blocks.WATER.defaultBlockState());
				if (rim) {
					set(level, x, 1, z, Blocks.STONE_BRICK_WALL.defaultBlockState());
				}
			}
		}
		for (int[] c : new int[][] {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
			for (int y = 1; y <= 5; y++) {
				set(level, c[0], y, c[1], Blocks.DARK_OAK_LOG.defaultBlockState());
			}
		}
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				set(level, x, 6, z, Blocks.DARK_OAK_SLAB.defaultBlockState());
			}
		}
		set(level, 0, 5, 0, Blocks.BELL.defaultBlockState());
		set(level, 0, 7, 0, Blocks.SOUL_LANTERN.defaultBlockState());

		// Maisons et ruelles
		for (int i = 0; i < SEATS; i++) {
			double a = angle(i, SEATS);
			int hx = (int) Math.round(HOUSE_RADIUS * Math.cos(a));
			int hz = (int) Math.round(HOUSE_RADIUS * Math.sin(a));
			house(level, hx, hz, i);
			// Ruelle de la place à la porte, bordée de haies
			for (int r = PLAZA_RADIUS + 1; r < HOUSE_RADIUS - 2; r++) {
				int x = (int) Math.round(r * Math.cos(a));
				int z = (int) Math.round(r * Math.sin(a));
				set(level, x, 0, z, Blocks.COBBLESTONE.defaultBlockState());
			}
			// Haies entre deux maisons : recoins pour les discussions à l'écart
			double mid = a + Math.PI / SEATS;
			for (int r = PLAZA_RADIUS + 4; r < HOUSE_RADIUS + 3; r++) {
				int x = (int) Math.round(r * Math.cos(mid));
				int z = (int) Math.round(r * Math.sin(mid));
				set(level, x, 1, z, hedge);
				if (r % 3 != 0) {
					set(level, x, 2, z, hedge);
				}
			}
		}
	}

	/** Petite maison 7x7 (intérieur 5x5), porte tournée vers la place. */
	private void house(ServerLevel level, int hx, int hz, int index) {
		BlockState wall = (index % 2 == 0 ? Blocks.DEEPSLATE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState();
		BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
		BlockState floor = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState roof = Blocks.DEEPSLATE_TILES.defaultBlockState();
		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 3; z++) {
				boolean edge = Math.abs(x) == 3 || Math.abs(z) == 3;
				boolean corner = Math.abs(x) == 3 && Math.abs(z) == 3;
				set(level, hx + x, 0, hz + z, floor);
				for (int y = 1; y <= 4; y++) {
					if (corner) {
						set(level, hx + x, y, hz + z, log);
					} else if (edge) {
						boolean window = y == 2 && (x == 0 || z == 0);
						set(level, hx + x, y, hz + z, window ? Blocks.GLASS_PANE.defaultBlockState() : wall);
					}
				}
				set(level, hx + x, 5, hz + z, roof);
			}
		}
		set(level, hx, 6, hz, Blocks.DEEPSLATE_TILES.defaultBlockState());
		// Porte côté place
		double dx = -hx;
		double dz = -hz;
		Direction toPlaza = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST)
			: (dz > 0 ? Direction.SOUTH : Direction.NORTH);
		int ox = toPlaza.getStepX() * 3;
		int oz = toPlaza.getStepZ() * 3;
		BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, toPlaza);
		set(level, hx + ox, 1, hz + oz, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		set(level, hx + ox, 2, hz + oz, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		// Intérieur : une lanterne et une botte de paille en guise de lit
		set(level, hx - toPlaza.getStepX() * 2, 1, hz - toPlaza.getStepZ() * 2, Blocks.HAY_BLOCK.defaultBlockState());
		set(level, hx, 4, hz, Blocks.LANTERN.defaultBlockState());
	}

	// ------------------------------------------------------------------
	// Mémorisation du centre
	// ------------------------------------------------------------------
	private static Path file(MinecraftServer server) {
		return server.getServerDirectory().resolve("config").resolve("valcombe-village.txt");
	}

	public void save(MinecraftServer server) {
		try {
			Path f = file(server);
			Files.createDirectories(f.getParent());
			Files.writeString(f, center.getX() + " " + center.getY() + " " + center.getZ());
		} catch (IOException e) {
			ValcombeMod.LOG.warn("Impossible d'enregistrer le village", e);
		}
	}

	public static Village load(MinecraftServer server) {
		try {
			Path f = file(server);
			if (!Files.exists(f)) {
				return null;
			}
			String[] p = Files.readString(f).trim().split(" ");
			return new Village(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
		} catch (IOException | RuntimeException e) {
			ValcombeMod.LOG.warn("Village illisible", e);
			return null;
		}
	}

	public static void forget(MinecraftServer server) {
		try {
			Files.deleteIfExists(file(server));
		} catch (IOException e) {
			ValcombeMod.LOG.warn("Impossible d'oublier le village", e);
		}
	}

	/** Positions des sièges utilisées pour une partie à n joueurs. */
	public List<Vec3> seats(int n) {
		List<Vec3> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(seat(i, n));
		}
		return out;
	}
}
