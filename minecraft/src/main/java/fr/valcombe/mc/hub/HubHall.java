package fr.valcombe.mc.hub;

import fr.valcombe.mc.Village;
import fr.valcombe.mc.worlds.WorldManager;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;

/**
 * La salle d'accueil du hub, posée sur le sol plat autour de l'origine du monde : son plan (dalles, portes des
 * mondes, mur des rôles, point d'arrivée) et sa construction.
 * <p>
 * Le plan, vu du dessus (le nord en haut, l'entrée au sud) :
 * <pre>
 *   +--------- mur des rôles ---------+
 *   |                                 |
 *  [P]                               [P]     P : porte vers un monde (trois de chaque côté)
 *   |     R        [ O ]        C     |      O : dalle dorée (s'inscrire), R : rouge (se désinscrire),
 *  [P]          coupole vitrée       [P]         C : obsidienne (devenir Conteur)
 *   |                                 |
 *   |  [TTTTTTTTT]       [TTTTTTTTT]  |      T : tableau des parties (deux panneaux, trois fiches chacun)
 *  [P]              A                [P]     A : point d'arrivée, face au mur des rôles
 *   +------------- porte -------------+
 *                    |  allée et lanternes
 * </pre>
 * Les dalles sont les mêmes que dans la salle d'attente du village ({@link Village.Pad}) : mêmes blocs, mêmes
 * effets. Les textes flottants, les jetons du mur, les fiches du tableau et l'effet des portes sont des entités
 * d'affichage posées par {@link Hub} : ici, seulement les blocs.
 */
public final class HubHall {
	/** Version du plan : la monter quand la salle change, elle sera rebâtie au prochain démarrage. 2 : le tableau des parties. */
	public static final int LAYOUT = 2;

	/** Le sol (dernier bloc d'herbe des mondes plats) : on marche sur {@code FLOOR + 1}. */
	public static final int FLOOR = WorldManager.SURFACE_Y;
	/** Les pieds d'un joueur debout dans la salle. */
	public static final int FEET = FLOOR + 1;
	/** Murs en x = ±HALF_X et z = ±HALF_Z ; l'intérieur est entre les deux. */
	public static final int HALF_X = 14;
	public static final int HALF_Z = 12;
	/** Dernière rangée des murs ; le plafond est juste au-dessus. */
	public static final int WALL_TOP = FLOOR + 7;
	public static final int CEILING = WALL_TOP + 1;

	/** Le point d'arrivée : près de l'entrée, face au mur des rôles (lacet 180 : vers le nord). */
	public static final Vec3 ARRIVAL = new Vec3(0.5, FEET, 8.5);
	public static final float ARRIVAL_YAW = 180f;

	/** Le centre de la dalle dorée (3 × 3) et la place des deux autres dalles. */
	public static final int JOIN_X = 0;
	public static final int JOIN_Z = 0;
	public static final int LEAVE_X = -5;
	public static final int STORYTELLER_X = 5;

	/** Le mur des rôles : la face intérieure du mur nord. */
	public static final double ROLE_WALL_Z = -HALF_Z + 1.15;

	/**
	 * Le tableau des parties : deux panneaux d'affichage couverts, de part et d'autre de l'allée, entre les dalles et le
	 * point d'arrivée. Leur face sud (vers l'entrée) porte une fiche par partie ; leur dos, une fiche abrégée, lisible
	 * depuis les dalles. Chaque panneau va de {@code ±BOARD_INNER} à {@code ±BOARD_OUTER} (les poteaux), sur la rangée
	 * {@code BOARD_Z}.
	 */
	public static final int BOARD_Z = 3;
	public static final int BOARD_INNER = 2;
	public static final int BOARD_OUTER = 12;
	/** La largeur d'une fiche, en blocs : trois fiches par panneau. */
	public static final int CARD_WIDTH = 3;
	/** Les fiches d'une page du tableau : trois par panneau. Au-delà, le tableau a des pages (flèches aux bouts, voir Hub). */
	public static final int SLOTS = 6;

	/**
	 * Le milieu (x) de la fiche {@code slot}, de gauche à droite pour qui regarde le nord depuis l'entrée : les trois du
	 * panneau ouest, puis les trois du panneau est.
	 */
	public static double cardX(int slot) {
		int perBoard = SLOTS / 2;
		int inBoard = slot % perBoard;
		double west = -BOARD_OUTER + 1 + CARD_WIDTH * (inBoard + 0.5);
		double east = BOARD_INNER + 1 + CARD_WIDTH * (inBoard + 0.5);
		return slot < perBoard ? west : east;
	}

	/** La face sud des panneaux (où l'on lit les fiches en venant de l'entrée). */
	public static final double BOARD_FRONT = BOARD_Z + 1;
	/** La face nord des panneaux (vue depuis les dalles). */
	public static final double BOARD_BACK = BOARD_Z;

	/**
	 * Une porte vers un monde, dans le mur ouest ({@code west}) ou est, centrée en {@code z}. L'ouverture fait 3 blocs
	 * de large et 4 de haut ; on la traverse en y entrant.
	 *
	 * @param index rang de la porte (0 : le village de la partie)
	 */
	public record Portal(int index, boolean west, int z) {
		/** Le plan du mur où s'ouvre la porte. */
		public int wallX() {
			return west ? -HALF_X : HALF_X;
		}

		/** Ce bloc (les pieds d'un joueur) est-il dans l'ouverture de la porte ? */
		public boolean contains(BlockPos feet) {
			return feet.getX() == wallX() && Math.abs(feet.getZ() - z) <= 1 && feet.getY() >= FEET && feet.getY() <= FEET + 3;
		}

		/** Où l'on revient dans le hub après être passé par cette porte : deux pas devant, dos à la porte. */
		public Vec3 front() {
			return new Vec3(west ? -HALF_X + 3.5 : HALF_X - 2.5, FEET, z + 0.5);
		}

		/** Le lacet d'un joueur qui tourne le dos à la porte (ouest : regarde vers l'est, x croissants). */
		public float frontYaw() {
			return west ? -90f : 90f;
		}

		/** Le lacet d'un panneau plaqué sur ce mur, lisible depuis la salle. */
		public float boardYaw() {
			return west ? -90f : 90f;
		}

		/** Le panneau du nom du monde, au-dessus de l'ouverture, contre le mur. */
		public Vec3 label() {
			return new Vec3(west ? -HALF_X + 1.08 : HALF_X - 0.08, FEET + 5.05, z + 0.5);
		}
	}

	/**
	 * Les six portes, dans l'ordre où les mondes y sont rangés : le village de la partie au milieu du mur ouest, puis
	 * les autres mondes de partie en face, puis au nord et au sud de chaque mur.
	 */
	public static final List<Portal> PORTALS = List.of(
		new Portal(0, true, 0), new Portal(1, false, 0),
		new Portal(2, true, -6), new Portal(3, false, -6),
		new Portal(4, true, 6), new Portal(5, false, 6));

	private HubHall() {
	}

	/** La porte où se trouve ce joueur (ses pieds), ou null. */
	public static Portal portalAt(BlockPos feet) {
		for (Portal p : PORTALS) {
			if (p.contains(feet)) {
				return p;
			}
		}
		return null;
	}

	/** Ces pieds sont-ils dans la salle (entre les murs) ? */
	public static boolean inside(BlockPos feet) {
		return Math.abs(feet.getX()) < HALF_X && Math.abs(feet.getZ()) < HALF_Z && feet.getY() >= FEET && feet.getY() < CEILING;
	}

	/** La dalle sous ces pieds, dans la salle : inscription (or), désinscription (rouge), Conteur (obsidienne). */
	public static Village.Pad padAt(ServerLevel level, BlockPos feet) {
		if (!inside(feet)) {
			return null;
		}
		Block b = level.getBlockState(feet.below()).getBlock();
		if (b == Blocks.GILDED_BLACKSTONE) {
			return Village.Pad.JOIN;
		}
		if (b == Blocks.REDSTONE_BLOCK) {
			return Village.Pad.LEAVE;
		}
		if (b == Blocks.CRYING_OBSIDIAN) {
			return Village.Pad.STORYTELLER;
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	/** Les blocs posés, pour la passe qui raccorde vitres, murets et barrières à leurs voisins. */
	private final List<BlockPos> placed = new ArrayList<>();
	private ServerLevel level;

	private void set(int x, int y, int z, BlockState s) {
		BlockPos pos = new BlockPos(x, y, z);
		level.setBlock(pos, s, Block.UPDATE_CLIENTS);
		if (!s.isAir()) {
			placed.add(pos);
		}
	}

	private void set(int x, int y, int z, Block b) {
		set(x, y, z, b.defaultBlockState());
	}

	private static BlockState log(Block b, Direction.Axis axis) {
		return b.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
	}

	private static BlockState stairs(Block b, Direction facing, boolean top) {
		return b.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, top ? Half.TOP : Half.BOTTOM);
	}

	private static BlockState hanging() {
		return Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
	}

	/**
	 * Bâtit la salle d'accueil dans ce monde (fil du serveur). Tout le volume de la salle, de son allée et de ses
	 * abords est d'abord rendu au sol plat : la salle peut être rebâtie sans laisser de restes.
	 */
	public static void build(ServerLevel level) {
		HubHall h = new HubHall();
		h.level = level;
		h.clear();
		h.floor();
		h.walls();
		h.forecourt();
		h.portals();
		h.roleWall();
		h.ceiling();
		h.furniture();
		h.boards();
		h.connect();
	}

	/** Rend au sol plat tout le volume de la salle et de ses abords. */
	private void clear() {
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = -HALF_X - 5; x <= HALF_X + 5; x++) {
			for (int z = -HALF_Z - 5; z <= HALF_Z + 22; z++) {
				set(x, FLOOR, z, Blocks.GRASS_BLOCK);
				for (int y = FEET; y <= CEILING + 10; y++) {
					set(x, y, z, air);
				}
			}
		}
	}

	/** Dallage : andésite polie, bordure et allée centrale d'ardoise polie, rosace de pierre lisse et les trois dalles. */
	private void floor() {
		for (int x = -HALF_X; x <= HALF_X; x++) {
			for (int z = -HALF_Z; z <= HALF_Z; z++) {
				boolean wall = Math.abs(x) == HALF_X || Math.abs(z) == HALF_Z;
				boolean border = Math.abs(x) == HALF_X - 1 || Math.abs(z) == HALF_Z - 1;
				boolean aisle = Math.abs(x) <= 1 && z >= 3;
				double r = Math.hypot(x, z);
				Block b = wall ? Blocks.STONE_BRICKS
					: border || aisle ? Blocks.POLISHED_DEEPSLATE
					: r <= 3.6 ? Blocks.SMOOTH_STONE
					: r <= 4.6 ? Blocks.POLISHED_DEEPSLATE
					: Blocks.POLISHED_ANDESITE;
				set(x, FLOOR, z, b);
			}
		}
		// Dalle dorée au centre (3 × 3), rouge à l'ouest, obsidienne à l'est, chacune dans son cadre.
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				set(JOIN_X + x, FLOOR, JOIN_Z + z, Blocks.GILDED_BLACKSTONE);
			}
		}
		for (int[] pad : new int[][] {{LEAVE_X, 0}, {STORYTELLER_X, 0}}) {
			for (int x = -1; x <= 1; x++) {
				for (int z = -1; z <= 1; z++) {
					set(pad[0] + x, FLOOR, pad[1] + z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				}
			}
		}
		set(LEAVE_X, FLOOR, 0, Blocks.REDSTONE_BLOCK);
		set(STORYTELLER_X, FLOOR, 0, Blocks.CRYING_OBSIDIAN);
	}

	/** Murs de briques de pierre, poteaux de chêne noir, fenêtres, porte d'entrée au sud. */
	private void walls() {
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = -HALF_X; x <= HALF_X; x++) {
			for (int z = -HALF_Z; z <= HALF_Z; z++) {
				boolean sideWall = Math.abs(x) == HALF_X;
				boolean endWall = Math.abs(z) == HALF_Z;
				if (!sideWall && !endWall) {
					continue;
				}
				boolean corner = sideWall && endWall;
				// Poteaux : aux coins, entre les portes (z = ±3) et le long des murs nord et sud.
				boolean post = corner || (sideWall && Math.abs(z) == 3) || (endWall && (Math.abs(x) == 6 || Math.abs(x) == 10));
				for (int y = FEET; y <= WALL_TOP; y++) {
					int h = y - FLOOR;
					boolean window = h >= 3 && h <= 5 && !post
						&& ((sideWall && Math.abs(z) == 10) || (z == HALF_Z && (Math.abs(x) == 3 || Math.abs(x) == 12))
							|| (z == -HALF_Z && Math.abs(x) == 12));
					BlockState s = post ? log(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y)
						: h == 1 ? Blocks.POLISHED_ANDESITE.defaultBlockState()
						: window ? Blocks.GLASS_PANE.defaultBlockState()
						: Blocks.STONE_BRICKS.defaultBlockState();
					set(x, y, z, s);
				}
			}
		}
		// Entrée : trois blocs de large, trois de haut, linteau sculpté et lanternes de part et d'autre.
		for (int x = -1; x <= 1; x++) {
			for (int y = FEET; y <= FEET + 2; y++) {
				set(x, y, HALF_Z, air);
			}
			set(x, FLOOR, HALF_Z, Blocks.POLISHED_DEEPSLATE);
			set(x, FEET + 3, HALF_Z, Blocks.CHISELED_STONE_BRICKS);
		}
		set(-2, FEET + 3, HALF_Z + 1, Blocks.LANTERN);
		set(2, FEET + 3, HALF_Z + 1, Blocks.LANTERN);
		set(-2, FEET + 2, HALF_Z + 1, Blocks.STONE_BRICK_WALL);
		set(2, FEET + 2, HALF_Z + 1, Blocks.STONE_BRICK_WALL);
		set(-2, FEET + 1, HALF_Z + 1, Blocks.STONE_BRICK_WALL);
		set(2, FEET + 1, HALF_Z + 1, Blocks.STONE_BRICK_WALL);
		set(-2, FEET, HALF_Z + 1, Blocks.STONE_BRICK_WALL);
		set(2, FEET, HALF_Z + 1, Blocks.STONE_BRICK_WALL);
	}

	/** Les portes des mondes : ouverture dans le mur, fond d'obsidienne, cadre de pierre noire et lanternes. */
	private void portals() {
		BlockState air = Blocks.AIR.defaultBlockState();
		for (Portal p : PORTALS) {
			int wx = p.wallX();
			int back = p.west() ? wx - 1 : wx + 1;
			int front = p.west() ? wx + 1 : wx - 1;
			for (int dz = -2; dz <= 2; dz++) {
				int z = p.z() + dz;
				for (int y = FLOOR; y <= FEET + 4; y++) {
					set(back, y, z, Blocks.OBSIDIAN); // le fond, en saillie hors de la salle
				}
				if (Math.abs(dz) == 2) {
					// Montants dans le mur, et devant eux deux colonnettes coiffées d'une lanterne (sous le nom du monde).
					for (int y = FEET; y <= FEET + 4; y++) {
						set(wx, y, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
					}
					for (int y = FEET; y <= FEET + 3; y++) {
						set(front, y, z, y == FEET ? Blocks.POLISHED_BLACKSTONE.defaultBlockState()
							: Blocks.POLISHED_BLACKSTONE_BRICK_WALL.defaultBlockState());
					}
					set(front, FEET + 4, z, Blocks.LANTERN);
				} else {
					set(wx, FLOOR, z, Blocks.POLISHED_BLACKSTONE);
					for (int y = FEET; y <= FEET + 3; y++) {
						set(wx, y, z, air); // l'ouverture
					}
					set(wx, FEET + 4, z, dz == 0 ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS);
				}
			}
			// Seuil devant la porte.
			for (int dz = -1; dz <= 1; dz++) {
				set(front, FLOOR, p.z() + dz, Blocks.POLISHED_BLACKSTONE);
			}
		}
	}

	/** Le mur des rôles : un grand panneau de chêne noir encadré, au nord, face à l'entrée. */
	private void roleWall() {
		int z = -HALF_Z;
		for (int x = -9; x <= 9; x++) {
			for (int y = FEET + 1; y <= FEET + 5; y++) {
				boolean frame = Math.abs(x) == 9 || y == FEET + 1 || y == FEET + 5;
				set(x, y, z, frame ? log(Blocks.STRIPPED_DARK_OAK_LOG, Math.abs(x) == 9 ? Direction.Axis.Y : Direction.Axis.X)
					: Blocks.DARK_OAK_PLANKS.defaultBlockState());
			}
		}
		// Banquette au pied du mur, entre deux bibliothèques.
		for (int x = -7; x <= 7; x++) {
			boolean shelf = Math.abs(x) == 7;
			set(x, FEET, z + 1, shelf ? Blocks.BOOKSHELF.defaultBlockState()
				: stairs(Blocks.DARK_OAK_STAIRS, Direction.NORTH, false));
		}
	}

	/** Plafond de chêne noir, poutres, lustres, coupole vitrée au-dessus des dalles, parapet sur le toit. */
	private void ceiling() {
		for (int x = -HALF_X; x <= HALF_X; x++) {
			for (int z = -HALF_Z; z <= HALF_Z; z++) {
				boolean edge = Math.abs(x) == HALF_X || Math.abs(z) == HALF_Z;
				boolean dome = Math.abs(x) <= 3 && Math.abs(z) <= 3;
				if (!dome) {
					set(x, CEILING, z, edge ? Blocks.STONE_BRICKS : Blocks.DARK_OAK_PLANKS);
				}
				if (edge) {
					boolean corner = Math.abs(x) == HALF_X && Math.abs(z) == HALF_Z;
					set(x, CEILING + 1, z, corner ? Blocks.CHISELED_STONE_BRICKS : Blocks.STONE_BRICK_WALL);
				}
			}
		}
		// Poutres d'est en ouest, et lustres qui y pendent.
		for (int z : new int[] {-7, 7}) {
			for (int x = -HALF_X + 1; x <= HALF_X - 1; x++) {
				set(x, WALL_TOP, z, log(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.X));
			}
			for (int x : new int[] {-9, 0, 9}) {
				set(x, WALL_TOP - 1, z, Blocks.IRON_CHAIN);
				set(x, WALL_TOP - 2, z, hanging());
			}
		}
		// Lumière douce et invisible dans les coins : la salle n'est jamais sombre.
		for (int x : new int[] {-10, 10}) {
			for (int z : new int[] {-9, 0, 9}) {
				set(x, WALL_TOP - 1, z, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 12));
			}
		}
		// Coupole : tambour vitré sur quatre poteaux, toit d'ardoise en gradins, lanterne au sommet.
		for (int x = -4; x <= 4; x++) {
			for (int z = -4; z <= 4; z++) {
				boolean ring = Math.abs(x) == 4 || Math.abs(z) == 4;
				if (!ring) {
					continue;
				}
				boolean corner = Math.abs(x) == 4 && Math.abs(z) == 4;
				for (int y = CEILING; y <= CEILING + 3; y++) {
					set(x, y, z, corner ? log(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y)
						: y == CEILING ? Blocks.DARK_OAK_PLANKS.defaultBlockState() : Blocks.GLASS_PANE.defaultBlockState());
				}
			}
		}
		for (int step = 0; step <= 3; step++) {
			int r = 4 - step;
			int y = CEILING + 4 + step;
			for (int x = -r; x <= r; x++) {
				for (int z = -r; z <= r; z++) {
					if (Math.abs(x) == r || Math.abs(z) == r || step == 3) {
						set(x, y, z, Blocks.DEEPSLATE_TILES);
					}
				}
			}
		}
		set(0, CEILING + 8, 0, Blocks.LANTERN);
		// Sous la coupole, une lanterne pend au-dessus de la dalle dorée.
		for (int y = CEILING + 6; y >= CEILING; y--) {
			set(0, y, 0, Blocks.IRON_CHAIN);
		}
		set(0, CEILING - 1, 0, hanging());
	}

	/** Bancs, plantes et pupitre : la salle se lit d'un coup d'œil, on peut s'y asseoir. */
	private void furniture() {
		// Bancs le long du mur sud, de part et d'autre de l'entrée, dos au mur.
		for (int x = 5; x <= 9; x++) {
			set(x, FEET, HALF_Z - 1, stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, false));
			set(-x, FEET, HALF_Z - 1, stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, false));
		}
		// Azalées en pot dans les coins.
		for (int x : new int[] {-HALF_X + 1, HALF_X - 1}) {
			for (int z : new int[] {-HALF_Z + 1, HALF_Z - 1}) {
				set(x, FEET, z, Blocks.POTTED_FLOWERING_AZALEA);
			}
		}
		// Pupitre du Conteur derrière son obsidienne.
		set(STORYTELLER_X + 2, FEET, 0, Blocks.LECTERN.defaultBlockState()
			.setValue(net.minecraft.world.level.block.LecternBlock.FACING, Direction.WEST));
	}

	/**
	 * Le tableau des parties : deux panneaux d'affichage de part et d'autre de l'allée. Chacun a un socle et une poutre de
	 * chêne noir, deux poteaux, un fond d'épicéa où se plaquent les fiches, un petit toit d'ardoise et quatre lanternes
	 * pendues sous l'avant-toit, devant et derrière.
	 */
	private void boards() {
		int z = BOARD_Z;
		for (int side : new int[] {-1, 1}) {
			int inner = side * BOARD_INNER;
			int outer = side * BOARD_OUTER;
			for (int x = Math.min(inner, outer); x <= Math.max(inner, outer); x++) {
				boolean post = x == inner || x == outer;
				set(x, FEET, z, post ? log(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y) : log(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.X));
				for (int y = FEET + 1; y <= FEET + 3; y++) {
					set(x, y, z, post ? log(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y) : Blocks.SPRUCE_PLANKS.defaultBlockState());
				}
				set(x, FEET + 4, z, log(Blocks.DARK_OAK_LOG, Direction.Axis.X));
				// Le toit : faîte en dalle, pans en escaliers qui débordent devant et derrière.
				set(x, FEET + 5, z, Blocks.DEEPSLATE_TILE_SLAB);
				set(x, FEET + 5, z - 1, stairs(Blocks.DEEPSLATE_TILE_STAIRS, Direction.SOUTH, false));
				set(x, FEET + 5, z + 1, stairs(Blocks.DEEPSLATE_TILE_STAIRS, Direction.NORTH, false));
			}
			for (int x : new int[] {inner, outer}) {
				set(x, FEET + 4, z - 1, hanging());
				set(x, FEET + 4, z + 1, hanging());
			}
			// Le seuil devant chaque panneau : une bande d'ardoise polie, comme une estrade où l'on s'arrête pour lire.
			for (int x = Math.min(inner, outer) + 1; x < Math.max(inner, outer); x++) {
				set(x, FLOOR, z + 1, Blocks.POLISHED_DEEPSLATE);
				set(x, FLOOR, z - 1, Blocks.POLISHED_DEEPSLATE);
			}
		}
	}

	/** Dehors : une allée vers le sud bordée de lanternes, et le pourtour de la salle dallé. */
	private void forecourt() {
		for (int z = HALF_Z + 1; z <= HALF_Z + 20; z++) {
			for (int x = -2; x <= 2; x++) {
				set(x, FLOOR, z, Math.abs(x) == 2 ? Blocks.POLISHED_ANDESITE : Blocks.DIRT_PATH);
			}
		}
		for (int z = HALF_Z + 5; z <= HALF_Z + 20; z += 5) {
			for (int x : new int[] {-3, 3}) {
				set(x, FEET, z, Blocks.DARK_OAK_FENCE);
				set(x, FEET + 1, z, Blocks.DARK_OAK_FENCE);
				set(x, FEET + 2, z, Blocks.LANTERN);
			}
		}
		// Un liseré de pierre autour des murs.
		for (int x = -HALF_X - 1; x <= HALF_X + 1; x++) {
			for (int z = -HALF_Z - 1; z <= HALF_Z + 1; z++) {
				boolean ring = Math.abs(x) == HALF_X + 1 || Math.abs(z) == HALF_Z + 1;
				if (ring && !(Math.abs(x) <= 2 && z == HALF_Z + 1)) {
					set(x, FLOOR, z, Blocks.COBBLESTONE);
				}
			}
		}
	}

	/** Raccorde vitres, murets et barrières à leurs voisins, maintenant que tout est posé. */
	private void connect() {
		for (BlockPos pos : placed) {
			BlockState s = level.getBlockState(pos);
			BlockState fixed = Block.updateFromNeighbourShapes(s, level, pos);
			if (fixed != s) {
				level.setBlock(pos, fixed, Block.UPDATE_CLIENTS);
			}
		}
	}
}
