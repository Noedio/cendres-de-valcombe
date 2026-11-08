package fr.valcombe.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
 * Le lieu des parties : un centre (la place), des sièges et des maisons.
 * Soit généré (village de Valcombe, 16 sièges et 16 maisons), soit placé à la main
 * sur une carte personnalisée avec /vc carte siege et /vc carte maison.
 * Enregistré dans config/valcombe-village.json.
 */
public final class Village {
	public static final int SEATS = 16;
	private static final int SEAT_RADIUS = 9;
	private static final int PLAZA_RADIUS = 13;
	private static final int HOUSE_RADIUS = 27;
	private static final int CLEAR_RADIUS = 36;
	private static final int FLAGS = Block.UPDATE_CLIENTS;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Forme enregistrée. */
	private static final class Data {
		int[] center;
		boolean generated;
		List<int[]> seats = new ArrayList<>();
		List<int[]> houses = new ArrayList<>();
	}

	private final BlockPos center;
	private final boolean generated;
	private final List<BlockPos> seats = new ArrayList<>();
	private final List<BlockPos> houses = new ArrayList<>();

	private Village(BlockPos center, boolean generated) {
		this.center = center;
		this.generated = generated;
	}

	/** Village de Valcombe généré autour de center (le sol de la place). */
	public static Village generated(BlockPos center) {
		Village v = new Village(center, true);
		for (int i = 0; i < SEATS; i++) {
			double a = angle(i, SEATS);
			v.seats.add(center.offset((int) Math.round(SEAT_RADIUS * Math.cos(a)), 1, (int) Math.round(SEAT_RADIUS * Math.sin(a))));
			v.houses.add(center.offset((int) Math.round((HOUSE_RADIUS + 1) * Math.cos(a)), 1,
				(int) Math.round((HOUSE_RADIUS + 1) * Math.sin(a))));
		}
		return v;
	}

	/** Carte personnalisée vide : sièges et maisons s'ajoutent ensuite. */
	public static Village custom(BlockPos center) {
		return new Village(center, false);
	}

	public BlockPos center() {
		return center;
	}

	public boolean isGenerated() {
		return generated;
	}

	public int seatCount() {
		return seats.size();
	}

	public int houseCount() {
		return houses.size();
	}

	public void addSeat(BlockPos p) {
		seats.add(p);
	}

	public void addHouse(BlockPos p) {
		houses.add(p);
	}

	public boolean removeLastSeat() {
		return !seats.isEmpty() && seats.remove(seats.size() - 1) != null;
	}

	public boolean removeLastHouse() {
		return !houses.isEmpty() && houses.remove(houses.size() - 1) != null;
	}

	// ------------------------------------------------------------------
	// Positions
	// ------------------------------------------------------------------
	private static double angle(int i, int n) {
		return 2 * Math.PI * i / n - Math.PI / 2;
	}

	/** Répartit n joueurs sur k emplacements, le plus régulièrement possible. */
	private static int spread(int i, int n, int k) {
		return (int) Math.round((double) i * k / n) % k;
	}

	private static Vec3 feet(BlockPos p) {
		return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
	}

	/** Position du siège du joueur i (sur n), ou le centre s'il n'y a aucun siège. */
	public Vec3 seat(int i, int n) {
		return seats.isEmpty() ? feet(center.above()) : feet(seats.get(spread(i, n, seats.size())));
	}

	/** Maison du joueur i (sur n) ; sans maison, on reste à son siège. */
	public Vec3 house(int i, int n) {
		return houses.isEmpty() ? seat(i, n) : feet(houses.get(spread(i, n, houses.size())));
	}

	/** Point d'apparition : sur la place, un peu au sud du centre. */
	public BlockPos spawn() {
		return generated ? center.offset(0, 1, 4) : center.above();
	}

	public float yawToCenter(Vec3 from) {
		double dx = center.getX() + 0.5 - from.x;
		double dz = center.getZ() + 0.5 - from.z;
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}

	// ------------------------------------------------------------------
	// Construction du village généré
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
		BlockState path = Blocks.DIRT_PATH.defaultBlockState();
		BlockState hedge = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		java.util.Random rnd = new java.util.Random(center.asLong());

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
					floor = rnd.nextInt(6) == 0 ? Blocks.GRAVEL.defaultBlockState() : path;
				}
				set(level, x, 0, z, floor);
				for (int y = 1; y <= 14; y++) {
					set(level, x, y, z, air);
				}
			}
		}
		for (int k = 0; k < 360; k += 3) {
			double a = Math.toRadians(k);
			set(level, (int) Math.round((PLAZA_RADIUS + 0.5) * Math.cos(a)), 1,
				(int) Math.round((PLAZA_RADIUS + 0.5) * Math.sin(a)), Blocks.STONE_BRICK_WALL.defaultBlockState());
		}
		for (int i = 0; i < SEATS; i++) {
			double a = angle(i, SEATS);
			int x = (int) Math.round(SEAT_RADIUS * Math.cos(a));
			int z = (int) Math.round(SEAT_RADIUS * Math.sin(a));
			set(level, x, 0, z, Blocks.POLISHED_ANDESITE.defaultBlockState());
			int bx = (int) Math.round((SEAT_RADIUS + 1) * Math.cos(a));
			int bz = (int) Math.round((SEAT_RADIUS + 1) * Math.sin(a));
			set(level, bx, 1, bz, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, away(bx, bz)));
			if (i % 4 == 0) {
				int lx = (int) Math.round((SEAT_RADIUS + 2) * Math.cos(a + Math.PI / SEATS));
				int lz = (int) Math.round((SEAT_RADIUS + 2) * Math.sin(a + Math.PI / SEATS));
				set(level, lx, 1, lz, Blocks.SPRUCE_FENCE.defaultBlockState());
				set(level, lx, 2, lz, Blocks.SPRUCE_FENCE.defaultBlockState());
				set(level, lx, 3, lz, Blocks.LANTERN.defaultBlockState());
			}
		}
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
		for (int i = 0; i < SEATS; i++) {
			double a = angle(i, SEATS);
			int hx = (int) Math.round(HOUSE_RADIUS * Math.cos(a));
			int hz = (int) Math.round(HOUSE_RADIUS * Math.sin(a));
			house(level, hx, hz, i);
			for (int r = PLAZA_RADIUS + 1; r < HOUSE_RADIUS - 2; r++) {
				set(level, (int) Math.round(r * Math.cos(a)), 0, (int) Math.round(r * Math.sin(a)),
					Blocks.COBBLESTONE.defaultBlockState());
			}
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

	private void house(ServerLevel level, int hx, int hz, int index) {
		BlockState wall = (index % 2 == 0 ? Blocks.DEEPSLATE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState();
		BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 3; z++) {
				boolean edge = Math.abs(x) == 3 || Math.abs(z) == 3;
				boolean corner = Math.abs(x) == 3 && Math.abs(z) == 3;
				set(level, hx + x, 0, hz + z, Blocks.SPRUCE_PLANKS.defaultBlockState());
				for (int y = 1; y <= 4; y++) {
					if (corner) {
						set(level, hx + x, y, hz + z, log);
					} else if (edge) {
						boolean window = y == 2 && (x == 0 || z == 0);
						set(level, hx + x, y, hz + z, window ? Blocks.GLASS_PANE.defaultBlockState() : wall);
					}
				}
				set(level, hx + x, 5, hz + z, Blocks.DEEPSLATE_TILES.defaultBlockState());
			}
		}
		set(level, hx, 6, hz, Blocks.DEEPSLATE_TILES.defaultBlockState());
		Direction toPlaza = away(-hx, -hz);
		int ox = toPlaza.getStepX() * 3;
		int oz = toPlaza.getStepZ() * 3;
		BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, toPlaza);
		set(level, hx + ox, 1, hz + oz, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		set(level, hx + ox, 2, hz + oz, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		set(level, hx - toPlaza.getStepX() * 2, 1, hz - toPlaza.getStepZ() * 2, Blocks.HAY_BLOCK.defaultBlockState());
		set(level, hx, 4, hz, Blocks.LANTERN.defaultBlockState());
	}

	// ------------------------------------------------------------------
	// Enregistrement
	// ------------------------------------------------------------------
	private static Path file(MinecraftServer server) {
		return server.getServerDirectory().resolve("config").resolve("valcombe-village.json");
	}

	private static int[] arr(BlockPos p) {
		return new int[] {p.getX(), p.getY(), p.getZ()};
	}

	private static BlockPos pos(int[] a) {
		return new BlockPos(a[0], a[1], a[2]);
	}

	public void save(MinecraftServer server) {
		Data d = new Data();
		d.center = arr(center);
		d.generated = generated;
		seats.forEach(p -> d.seats.add(arr(p)));
		houses.forEach(p -> d.houses.add(arr(p)));
		try {
			Path f = file(server);
			Files.createDirectories(f.getParent());
			Files.writeString(f, GSON.toJson(d));
		} catch (IOException e) {
			ValcombeMod.LOG.warn("Impossible d'enregistrer le village", e);
		}
	}

	public static Village load(MinecraftServer server) {
		try {
			Path json = file(server);
			if (Files.exists(json)) {
				Data d = GSON.fromJson(Files.readString(json), Data.class);
				Village v = new Village(pos(d.center), d.generated);
				d.seats.forEach(a -> v.seats.add(pos(a)));
				d.houses.forEach(a -> v.houses.add(pos(a)));
				return v;
			}
			// Ancien format (version 0.1.0) : seulement le centre d'un village généré.
			Path old = json.resolveSibling("valcombe-village.txt");
			if (Files.exists(old)) {
				String[] p = Files.readString(old).trim().split(" ");
				return generated(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
			}
		} catch (IOException | RuntimeException e) {
			ValcombeMod.LOG.warn("Village illisible", e);
		}
		return null;
	}

	public static void forget(MinecraftServer server) {
		try {
			Files.deleteIfExists(file(server));
			Files.deleteIfExists(file(server).resolveSibling("valcombe-village.txt"));
		} catch (IOException e) {
			ValcombeMod.LOG.warn("Impossible d'oublier le village", e);
		}
	}
}
