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
		boolean hall;
		String theme;
		List<int[]> seats = new ArrayList<>();
		List<int[]> houses = new ArrayList<>();
	}

	private final BlockPos center;
	private final boolean generated;
	private boolean hall;
	private String theme = "gothique";
	private final List<BlockPos> seats = new ArrayList<>();
	private final List<BlockPos> houses = new ArrayList<>();

	private Village(BlockPos center, boolean generated) {
		this.center = center;
		this.generated = generated;
	}

	/** Village de Valcombe généré autour de center (le sol de la place). */
	public static Village generated(BlockPos center) {
		return generated(center, "gothique");
	}

	public static Village generated(BlockPos center, String theme) {
		Village v = new Village(center, true);
		v.theme = THEMES.contains(theme) ? theme : "gothique";
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

	/** Numéro de la maison où se trouve cette position (à 4 blocs près), ou -1. */
	public int houseAt(Vec3 pos) {
		for (int i = 0; i < houses.size(); i++) {
			Vec3 h = feet(houses.get(i));
			if (Math.abs(pos.y - h.y) < 4 && Math.hypot(pos.x - h.x, pos.z - h.z) < 4.5) {
				return i;
			}
		}
		return -1;
	}

	/** Point d'apparition : sur la place, un peu au sud du centre. */
	public BlockPos spawn() {
		if (hall) {
			int[] h = hallCenter();
			return center.offset(h[0], 1, h[1] + 4);
		}
		return generated ? center.offset(0, 1, 4) : center.above();
	}

	public boolean hasHall() {
		return hall;
	}

	// ------------------------------------------------------------------
	// Salle d'attente
	// ------------------------------------------------------------------
	private static final double HALL_ANGLE = angle(0, SEATS) + Math.PI / SEATS;
	private static final int HALL_DISTANCE = 47;

	/** Centre de la salle, relatif au centre de la place. */
	private static int[] hallCenter() {
		return new int[] {(int) Math.round(HALL_DISTANCE * Math.cos(HALL_ANGLE)), (int) Math.round(HALL_DISTANCE * Math.sin(HALL_ANGLE))};
	}

	/** Dalles de la salle : inscription (or), désinscription (rouge), Conteur (obsidienne). */
	public enum Pad { JOIN, LEAVE, STORYTELLER }

	public Pad padAt(BlockPos feet, ServerLevel level) {
		if (!hall) {
			return null;
		}
		var b = level.getBlockState(feet.below()).getBlock();
		if (b == Blocks.GILDED_BLACKSTONE) {
			return Pad.JOIN;
		}
		if (b == Blocks.REDSTONE_BLOCK) {
			return Pad.LEAVE;
		}
		if (b == Blocks.CRYING_OBSIDIAN) {
			return Pad.STORYTELLER;
		}
		return null;
	}

	/** Pavillon de 19 x 15 relié à la place par une allée pavée. */
	public void buildHall(ServerLevel level) {
		int[] h = hallCenter();
		int hx = h[0];
		int hz = h[1];
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = -11; x <= 11; x++) {
			for (int z = -9; z <= 9; z++) {
				for (int y = -3; y < 0; y++) {
					set(level, hx + x, y, hz + z, Blocks.STONE.defaultBlockState());
				}
				set(level, hx + x, 0, hz + z, Blocks.DIRT_PATH.defaultBlockState());
				for (int y = 1; y <= 9; y++) {
					set(level, hx + x, y, hz + z, air);
				}
			}
		}
		for (int x = -9; x <= 9; x++) {
			for (int z = -7; z <= 7; z++) {
				boolean edge = Math.abs(x) == 9 || Math.abs(z) == 7;
				boolean corner = Math.abs(x) == 9 && Math.abs(z) == 7;
				set(level, hx + x, 0, hz + z, Blocks.POLISHED_ANDESITE.defaultBlockState());
				for (int y = 1; y <= 5; y++) {
					if (corner) {
						set(level, hx + x, y, hz + z, Blocks.DARK_OAK_LOG.defaultBlockState());
					} else if (edge) {
						boolean door = z == 7 && Math.abs(x) <= 1 && y <= 3;
						boolean window = y == 3 && (Math.abs(x) % 3 == 0 || Math.abs(z) % 3 == 0) && !door;
						set(level, hx + x, y, hz + z, door ? air
							: window ? Blocks.GLASS_PANE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
					}
				}
				set(level, hx + x, 6, hz + z, Blocks.DARK_OAK_PLANKS.defaultBlockState());
			}
		}
		for (int[] l : new int[][] {{-5, -3}, {5, -3}, {-5, 3}, {5, 3}}) {
			set(level, hx + l[0], 5, hz + l[1], Blocks.LANTERN.defaultBlockState());
		}
		// Dalles : inscription au centre, désinscription à gauche, Conteur au fond
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				set(level, hx + x, 0, hz + z, Blocks.GILDED_BLACKSTONE.defaultBlockState());
			}
		}
		set(level, hx - 6, 0, hz, Blocks.REDSTONE_BLOCK.defaultBlockState());
		set(level, hx, 0, hz - 5, Blocks.CRYING_OBSIDIAN.defaultBlockState());
		// Allée vers la place
		for (int r = PLAZA_RADIUS + 1; r < HALL_DISTANCE - 6; r++) {
			int x = (int) Math.round(r * Math.cos(HALL_ANGLE));
			int z = (int) Math.round(r * Math.sin(HALL_ANGLE));
			for (int w = -1; w <= 1; w++) {
				set(level, x + w, 0, z, Blocks.COBBLESTONE.defaultBlockState());
				for (int y = 1; y <= 3; y++) {
					set(level, x + w, y, z, air);
				}
			}
		}
		hall = true;
	}

	/** Positions (absolues) des panneaux flottants de la salle : texte → position. */
	public java.util.Map<String, Vec3> hallBoards() {
		int[] h = hallCenter();
		var m = new java.util.LinkedHashMap<String, Vec3>();
		m.put("Dalle dorée : s'inscrire\nDalle rouge : se désinscrire\nObsidienne : devenir Conteur",
			new Vec3(center.getX() + h[0] + 0.5, center.getY() + 3.2, center.getZ() + h[1] + 0.5));
		m.put("Le bien gagne quand le Démon meurt.\nLe mal gagne quand il ne reste que deux vivants.",
			new Vec3(center.getX() + h[0] - 8.4, center.getY() + 2.5, center.getZ() + h[1] + 0.5));
		m.put("La nuit, chacun dort chez soi.\nLe jour, on débat, on chuchote,\npuis on vote à l'horloge.",
			new Vec3(center.getX() + h[0] + 8.4, center.getY() + 2.5, center.getZ() + h[1] + 0.5));
		m.put("Menu du jeu : touche J\nou l'horloge en main (clic droit)",
			new Vec3(center.getX() + h[0] + 0.5, center.getY() + 2.5, center.getZ() + h[1] - 6.4));
		return m;
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

	/** Matériaux d'un thème de village. */
	private record Palette(Block plaza, Block plazaAlt1, Block plazaAlt2, Block ground, Block ground2, Block rim,
		Block seatFloor, Block post, Block light, Block well, Block pillar, Block wallA, Block wallB, Block roof,
		Block houseFloor, Block alley, Block hedge) {
	}

	public static final java.util.List<String> THEMES = java.util.List.of("gothique", "monastere", "port");

	private static Palette palette(String theme) {
		return switch (theme == null ? "gothique" : theme) {
			case "monastere" -> new Palette(Blocks.POLISHED_DIORITE, Blocks.CALCITE, Blocks.DIORITE, Blocks.DIRT_PATH,
				Blocks.GRAVEL, Blocks.TUFF_BRICK_WALL, Blocks.POLISHED_TUFF, Blocks.SPRUCE_FENCE, Blocks.SOUL_LANTERN,
				Blocks.TUFF_BRICKS, Blocks.STRIPPED_SPRUCE_LOG, Blocks.CALCITE, Blocks.TUFF_BRICKS, Blocks.DEEPSLATE_TILES,
				Blocks.SPRUCE_PLANKS, Blocks.SMOOTH_STONE, Blocks.BIRCH_LEAVES);
			case "port" -> new Palette(Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SANDSTONE, Blocks.SAND,
				Blocks.GRAVEL, Blocks.SANDSTONE_WALL, Blocks.PRISMARINE_BRICKS, Blocks.OAK_FENCE, Blocks.LANTERN,
				Blocks.PRISMARINE_BRICKS, Blocks.OAK_LOG, Blocks.OAK_PLANKS, Blocks.BIRCH_PLANKS, Blocks.DARK_PRISMARINE,
				Blocks.OAK_PLANKS, Blocks.PACKED_MUD, Blocks.OAK_LEAVES);
			default -> new Palette(Blocks.STONE_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE, Blocks.DIRT_PATH,
				Blocks.GRAVEL, Blocks.STONE_BRICK_WALL, Blocks.POLISHED_ANDESITE, Blocks.SPRUCE_FENCE, Blocks.LANTERN,
				Blocks.CHISELED_STONE_BRICKS, Blocks.DARK_OAK_LOG, Blocks.DEEPSLATE_BRICKS, Blocks.STONE_BRICKS,
				Blocks.DEEPSLATE_TILES, Blocks.SPRUCE_PLANKS, Blocks.COBBLESTONE, Blocks.SPRUCE_LEAVES);
		};
	}

	public String theme() {
		return theme;
	}

	public void build(ServerLevel level) {
		Palette p = palette(theme);
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState hedge = p.hedge().defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
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
				Block floor;
				if (d <= PLAZA_RADIUS) {
					floor = rnd.nextInt(5) == 0 ? p.plazaAlt1() : rnd.nextInt(3) == 0 ? p.plazaAlt2() : p.plaza();
				} else {
					floor = rnd.nextInt(6) == 0 ? p.ground2() : p.ground();
				}
				set(level, x, 0, z, floor.defaultBlockState());
				for (int y = 1; y <= 14; y++) {
					set(level, x, y, z, air);
				}
			}
		}
		for (int k = 0; k < 360; k += 3) {
			double a = Math.toRadians(k);
			set(level, (int) Math.round((PLAZA_RADIUS + 0.5) * Math.cos(a)), 1,
				(int) Math.round((PLAZA_RADIUS + 0.5) * Math.sin(a)), p.rim().defaultBlockState());
		}
		for (int i = 0; i < SEATS; i++) {
			double a = angle(i, SEATS);
			int x = (int) Math.round(SEAT_RADIUS * Math.cos(a));
			int z = (int) Math.round(SEAT_RADIUS * Math.sin(a));
			set(level, x, 0, z, p.seatFloor().defaultBlockState());
			int bx = (int) Math.round((SEAT_RADIUS + 1) * Math.cos(a));
			int bz = (int) Math.round((SEAT_RADIUS + 1) * Math.sin(a));
			set(level, bx, 1, bz, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, away(bx, bz)));
			if (i % 4 == 0) {
				int lx = (int) Math.round((SEAT_RADIUS + 2) * Math.cos(a + Math.PI / SEATS));
				int lz = (int) Math.round((SEAT_RADIUS + 2) * Math.sin(a + Math.PI / SEATS));
				set(level, lx, 1, lz, p.post().defaultBlockState());
				set(level, lx, 2, lz, p.post().defaultBlockState());
				set(level, lx, 3, lz, p.light().defaultBlockState());
			}
		}
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				boolean rim = Math.abs(x) == 2 || Math.abs(z) == 2;
				set(level, x, 0, z, rim ? p.well().defaultBlockState() : Blocks.WATER.defaultBlockState());
				if (rim) {
					set(level, x, 1, z, p.rim().defaultBlockState());
				}
			}
		}
		for (int[] c : new int[][] {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
			for (int y = 1; y <= 5; y++) {
				set(level, c[0], y, c[1], p.pillar().defaultBlockState());
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
			house(level, hx, hz, i, p);
			for (int r = PLAZA_RADIUS + 1; r < HOUSE_RADIUS - 2; r++) {
				set(level, (int) Math.round(r * Math.cos(a)), 0, (int) Math.round(r * Math.sin(a)), p.alley().defaultBlockState());
			}
			double mid = a + Math.PI / SEATS;
			for (int r = PLAZA_RADIUS + 4; r < HOUSE_RADIUS + 3 && i != 0; r++) {
				int x = (int) Math.round(r * Math.cos(mid));
				int z = (int) Math.round(r * Math.sin(mid));
				set(level, x, 1, z, hedge);
				if (r % 3 != 0) {
					set(level, x, 2, z, hedge);
				}
			}
		}
	}

	private void house(ServerLevel level, int hx, int hz, int index, Palette p) {
		BlockState wall = (index % 2 == 0 ? p.wallA() : p.wallB()).defaultBlockState();
		BlockState log = p.pillar().defaultBlockState();
		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 3; z++) {
				boolean edge = Math.abs(x) == 3 || Math.abs(z) == 3;
				boolean corner = Math.abs(x) == 3 && Math.abs(z) == 3;
				set(level, hx + x, 0, hz + z, p.houseFloor().defaultBlockState());
				for (int y = 1; y <= 4; y++) {
					if (corner) {
						set(level, hx + x, y, hz + z, log);
					} else if (edge) {
						boolean window = y == 2 && (x == 0 || z == 0);
						set(level, hx + x, y, hz + z, window ? Blocks.GLASS_PANE.defaultBlockState() : wall);
					}
				}
				set(level, hx + x, 5, hz + z, p.roof().defaultBlockState());
			}
		}
		set(level, hx, 6, hz, p.roof().defaultBlockState());
		Direction toPlaza = away(-hx, -hz);
		int ox = toPlaza.getStepX() * 3;
		int oz = toPlaza.getStepZ() * 3;
		BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, toPlaza);
		set(level, hx + ox, 1, hz + oz, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		set(level, hx + ox, 2, hz + oz, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		set(level, hx - toPlaza.getStepX() * 2, 1, hz - toPlaza.getStepZ() * 2, Blocks.HAY_BLOCK.defaultBlockState());
		set(level, hx, 4, hz, p.light().defaultBlockState());
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
		d.hall = hall;
		d.theme = theme;
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
				v.hall = d.hall;
				v.theme = d.theme == null ? "gothique" : d.theme;
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
