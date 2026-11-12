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
	/** Trônes d'un village généré par défaut. */
	public static final int SEATS = 16;
	/** Nombre de trônes d'un village généré : les mêmes bornes pour la commande, l'écran en jeu et la page web. */
	public static final int MIN_SEATS = 5;
	public static final int MAX_SEATS = 20;
	/** Version du plan du village généré : un village plus ancien est reconstruit au démarrage. */
	public static final int LAYOUT = 4;
	private static final int ROAD_WIDTH = 3;
	private static final int HOUSE_HALF = 4;
	private static final int CLEAR_HEIGHT = 16;
	/** Écart entre deux trônes selon la taille : petite, normale, grande. */
	private static final double[] SPACING = {3.6, 4.3, 5.2};
	/** Ancienne salle d'attente (plan 1), pour l'effacer. */
	private static final double LEGACY_HALL_ANGLE = angle(0, 16) + Math.PI / 16;

	// Plan du village généré (calculé d'après le nombre de trônes et la taille)
	private int seatTotal = SEATS;
	private int size = 1;
	private boolean withHouses = true;
	private int seatRadius;
	private int plazaRadius;
	private int houseRadius;
	private int clearRadius;
	private int hallDistance;

	private void layout() {
		seatTotal = Math.max(5, Math.min(20, seatTotal));
		size = Math.max(0, Math.min(2, size));
		seatRadius = Math.max(6, (int) Math.round(seatTotal * SPACING[size] / (2 * Math.PI)));
		plazaRadius = seatRadius + 6;
		houseRadius = plazaRadius + 20;
		clearRadius = houseRadius + 13;
		hallDistance = clearRadius + 14;
	}

	private double hallAngle() {
		return angle(0, seatTotal) + Math.PI / seatTotal;
	}

	/** Plus grande étendue d'une carte (voir MapStore) : au-delà, on ne saurait ni l'enregistrer ni la raser sans figer le serveur. */
	public static final int MAX_EXTENT = 200;

	/** Rayon qu'il faut à la carte pour contenir ce repère (8 blocs de marge autour). */
	private int reach(BlockPos b) {
		return (int) Math.ceil(Math.hypot(b.getX() - center.getX(), b.getZ() - center.getZ())) + 8;
	}

	/** Ce point est-il assez près de la place pour porter un repère ? */
	public boolean reachable(BlockPos b) {
		return reach(b) <= MAX_EXTENT;
	}

	/** Rayon que demandent les repères d'une carte faite à la main, sans plafond. */
	public int farthest() {
		int r = 12;
		for (BlockPos b : seats) {
			r = Math.max(r, reach(b));
		}
		for (BlockPos b : houses) {
			r = Math.max(r, reach(b));
		}
		if (convocation != null) {
			r = Math.max(r, reach(convocation));
		}
		return r;
	}

	/** Ce que l'éditeur a bâti sur la carte, au-delà des repères : {x0, y0, z0, x1, y1, z1}, ou null. */
	private int[] built;

	/** L'éditeur vient de poser des blocs dans cette boîte : la carte la retient, pour tout raser plus tard. */
	public void include(int x0, int y0, int z0, int x1, int y1, int z1) {
		if (built == null) {
			built = new int[] {x0, y0, z0, x1, y1, z1};
			return;
		}
		built = new int[] {Math.min(built[0], x0), Math.min(built[1], y0), Math.min(built[2], z0),
			Math.max(built[3], x1), Math.max(built[4], y1), Math.max(built[5], z1)};
	}

	public int[] built() {
		return built;
	}

	/** Rayon de tout ce que le village occupe (salle d'attente comprise), pour l'enregistrer ou le retirer. */
	public int extent() {
		if (!generated) {
			return Math.min(MAX_EXTENT, farthest());
		}
		return Math.max(hall ? hallDistance + 12 : clearRadius, convocation != null ? clearRadius + ROOM_GAP + 8 : 0);
	}

	public int seatTotal() {
		return seatTotal;
	}

	public int size() {
		return size;
	}

	public boolean withHouses() {
		return withHouses;
	}
	private static final int FLAGS = Block.UPDATE_CLIENTS;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Forme enregistrée. */
	private static final class Data {
		int[] center;
		boolean generated;
		int layout;
		int decorGen;
		int seatTotal;
		int size = 1;
		Boolean withHouses;
		boolean hall;
		String theme;
		List<int[]> seats = new ArrayList<>();
		List<int[]> houses = new ArrayList<>();
		int[] convocation;
		/** Retouchée à la main depuis son dernier enregistrement dans la bibliothèque (absent : fiche d'avant ce suivi). */
		Boolean modified;
		/** Carte oubliée (/vc carte oublier) : ses blocs sont encore dans le monde, mais on ne joue plus dessus. */
		boolean forgotten;
		/** Carte de la bibliothèque dont celle-ci vient : identifiant et nom (absents : aucune). */
		String carte;
		String carteNom;
		/** Tout ce que l'éditeur a bâti sur cette carte : {x0, y0, z0, x1, y1, z1} absolus (absent : rien hors des repères). */
		int[] built;
	}

	/**
	 * Numéro de la prochaine génération de décors : jamais deux villages avec la même, même après un
	 * redémarrage, sinon les trônes sculptés d'un ancien village échapperaient à la purge.
	 */
	private static final java.util.concurrent.atomic.AtomicInteger NEXT_GEN =
		new java.util.concurrent.atomic.AtomicInteger((int) (System.currentTimeMillis() / 1000 & 0x3FFFFFFF));

	private BlockPos center;
	private final boolean generated;
	private int layout = LAYOUT;
	/** Génération des décors en entités (trônes 3D) : les anciennes générations sont retirées. */
	private int decorGen = NEXT_GEN.addAndGet(4);
	/** La carte a été retouchée à la main et pas enregistrée depuis : on ne la remplace pas sans la sauvegarder. */
	private boolean modified;
	private String libraryId = "";
	private String libraryName = "";
	private boolean hall;
	private String theme = "gothique";
	private final List<BlockPos> seats = new ArrayList<>();
	private final List<BlockPos> houses = new ArrayList<>();
	/** Salle de convocation : là où se tient le joueur que le Conteur reçoit à l'écart ; null s'il n'y en a pas. */
	private BlockPos convocation;
	/** Distance entre le bord du village généré et sa salle de convocation. */
	private static final int ROOM_GAP = 9;

	private Village(BlockPos center, boolean generated) {
		this.center = center;
		this.generated = generated;
		layout();
	}

	/** Village de Valcombe généré autour de center (le sol de la place). */
	public static Village generated(BlockPos center) {
		return generated(center, "gothique");
	}

	public static Village generated(BlockPos center, String theme) {
		return generated(center, theme, SEATS, 1, true);
	}

	/** Village généré réglable : trônes (5 à 20), taille (0 petite, 1 normale, 2 grande), maisons ou non. */
	public static Village generated(BlockPos center, String theme, int seatCount, int size, boolean houses) {
		Village v = new Village(center, true);
		v.theme = THEMES.contains(theme) ? theme : "gothique";
		v.seatTotal = seatCount;
		v.size = size;
		v.withHouses = houses;
		v.layout();
		v.placeMarkers();
		return v;
	}

	private void placeMarkers() {
		seats.clear();
		houses.clear();
		for (int i = 0; i < seatTotal; i++) {
			double a = angle(i, seatTotal);
			seats.add(center.offset((int) Math.round(seatRadius * Math.cos(a)), 1, (int) Math.round(seatRadius * Math.sin(a))));
			if (withHouses) {
				houses.add(center.offset((int) Math.round(houseRadius * Math.cos(a)), 1,
					(int) Math.round(houseRadius * Math.sin(a))));
			}
		}
	}

	/** Carte personnalisée vide : sièges et maisons s'ajoutent ensuite. */
	/**
	 * Village rechargé depuis la bibliothèque de cartes, au centre donné : repères relatifs
	 * (dx, dy, dz) et réglages de génération.
	 */
	public static Village restored(BlockPos center, boolean generated, String theme, int seatCount, int size,
			boolean houses, boolean hall, List<int[]> relSeats, List<int[]> relHouses, int[] relConvocation) {
		Village v = new Village(center, generated);
		v.theme = THEMES.contains(theme) ? theme : "gothique";
		v.seatTotal = seatCount;
		v.size = size;
		v.withHouses = houses;
		v.layout();
		v.hall = hall;
		relSeats.forEach(a -> v.seats.add(center.offset(a[0], a[1], a[2])));
		relHouses.forEach(a -> v.houses.add(center.offset(a[0], a[1], a[2])));
		if (relConvocation != null && relConvocation.length == 3) {
			v.convocation = center.offset(relConvocation[0], relConvocation[1], relConvocation[2]);
		}
		return v;
	}

	/** Salle de convocation relative au centre, ou null. */
	public int[] relConvocation() {
		return convocation == null ? null
			: new int[] {convocation.getX() - center.getX(), convocation.getY() - center.getY(), convocation.getZ() - center.getZ()};
	}

	public BlockPos convocation() {
		return convocation;
	}

	public void setConvocation(BlockPos p) {
		convocation = p;
	}

	/** Repères relatifs au centre (pour la bibliothèque de cartes). */
	public List<int[]> relSeats() {
		return seats.stream().map(b -> new int[] {b.getX() - center.getX(), b.getY() - center.getY(), b.getZ() - center.getZ()}).toList();
	}

	public List<int[]> relHouses() {
		return houses.stream().map(b -> new int[] {b.getX() - center.getX(), b.getY() - center.getY(), b.getZ() - center.getZ()}).toList();
	}

	/** Carte construite à la main : déplace le centre de la place (les repères restent où ils sont). */
	public void setCenter(BlockPos c) {
		if (generated) {
			throw new fr.valcombe.rules.GameException("Le centre d'un village généré ne se déplace pas : régénérez-le ailleurs.");
		}
		center = c;
	}

	public static Village custom(BlockPos center) {
		Village v = new Village(center, false);
		v.modified = true; // une carte faite à la main n'existe nulle part ailleurs tant qu'elle n'est pas enregistrée
		return v;
	}

	public boolean modified() {
		return modified;
	}

	/** La carte vient d'être retouchée à la main (true) ou enregistrée dans la bibliothèque (false). */
	public void setModified(boolean modified) {
		this.modified = modified;
	}

	/** Identifiant de la carte de la bibliothèque dont celle-ci vient (chargée ou enregistrée), ou chaîne vide. */
	public String libraryId() {
		return libraryId;
	}

	/** Nom de cette carte dans la bibliothèque, ou chaîne vide. */
	public String libraryName() {
		return libraryName;
	}

	/** La carte vient d'être chargée de la bibliothèque, ou d'y être enregistrée, sous cet identifiant. */
	public void setLibrary(String id, String name) {
		libraryId = id == null ? "" : id;
		libraryName = name == null ? "" : name;
	}

	/** Les repères d'une carte à un instant donné, pour revenir sur la dernière modification. */
	public record Marks(BlockPos center, List<BlockPos> seats, List<BlockPos> houses, BlockPos convocation) {
	}

	public Marks marks() {
		return new Marks(center, List.copyOf(seats), List.copyOf(houses), convocation);
	}

	/** Remet les repères tels qu'ils étaient (le centre d'un village généré, lui, ne bouge jamais). */
	public void restore(Marks m) {
		if (!generated) {
			center = m.center();
		}
		seats.clear();
		seats.addAll(m.seats());
		houses.clear();
		houses.addAll(m.houses());
		convocation = m.convocation();
	}

	public BlockPos center() {
		return center;
	}

	public boolean isGenerated() {
		return generated;
	}

	/** Village généré selon un plan plus ancien (plus petit) : à reconstruire. */
	public boolean outdated() {
		return generated && layout < 2;
	}

	/** Village du plan 2 : seuls les trônes changent (trônes en blocs → trônes sculptés en 3D). */
	public boolean oldThrones() {
		return generated && layout == 2;
	}

	public int decorGen() {
		return decorGen;
	}

	/** Remplace les trônes en blocs du plan 2 par les trônes sculptés, sans toucher au reste. */
	public void upgradeThrones(ServerLevel level) {
		Palette p = palette(theme);
		decorGen++;
		for (int i = 0; i < seatTotal; i++) {
			double a = angle(i, seatTotal);
			int x = (int) Math.round(seatRadius * Math.cos(a));
			int z = (int) Math.round(seatRadius * Math.sin(a));
			Direction back = away(x, z);
			Direction side = back.getClockWise();
			BlockState air = Blocks.AIR.defaultBlockState();
			int bx = x + back.getStepX();
			int bz = z + back.getStepZ();
			for (int y = 1; y <= 3; y++) {
				set(level, bx, y, bz, air);
			}
			set(level, bx, 0, bz, p.seatFloor().defaultBlockState());
			for (int s : new int[] {-1, 1}) {
				set(level, x + side.getStepX() * s, 1, z + side.getStepZ() * s, air);
			}
			throne(level, x, z, p);
		}
		layout = LAYOUT;
	}

	/** Le même village (centre et thème), selon le plan actuel. */
	public Village regenerated() {
		return generated(center, theme, seatTotal, size, withHouses);
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
	// Repères : les trouver, les déplacer, les retirer, les renuméroter
	// ------------------------------------------------------------------
	/** Sorte de repère d'une carte. */
	public enum Mark {
		SEAT("Trône"), HOUSE("Maison"), CENTER("Centre de la place"), ROOM("Salle de convocation");

		public final String label;

		Mark(String label) {
			this.label = label;
		}
	}

	/** Un repère précis : sa sorte et, pour un trône ou une maison, son rang (à partir de 0). */
	public record Hit(Mark type, int index) {
		/** « Trône 3 », « Maison 3 », « Centre de la place »… comme sur les étiquettes. */
		public String label() {
			return type == Mark.SEAT || type == Mark.HOUSE ? type.label + " " + (index + 1) : type.label;
		}
	}

	/** Le repère de cette sorte (ou de n'importe laquelle si only est null) posé à `reach` blocs au plus de p, ou null. */
	public Hit markerAt(BlockPos p, Mark only, int reach) {
		Hit best = null;
		double bestD = Double.MAX_VALUE;
		List<Hit> all = new ArrayList<>();
		for (int i = 0; i < seats.size(); i++) {
			all.add(new Hit(Mark.SEAT, i));
		}
		for (int i = 0; i < houses.size(); i++) {
			all.add(new Hit(Mark.HOUSE, i));
		}
		if (convocation != null) {
			all.add(new Hit(Mark.ROOM, 0));
		}
		for (Hit h : all) {
			BlockPos m = markerPos(h);
			if ((only == null || only == h.type()) && Math.abs(m.getX() - p.getX()) <= reach && Math.abs(m.getZ() - p.getZ()) <= reach
				&& Math.abs(m.getY() - p.getY()) <= 2) {
				double d = m.distSqr(p);
				if (d < bestD) {
					bestD = d;
					best = h;
				}
			}
		}
		return best;
	}

	/** Position d'un repère (les pieds de celui qui s'y tient). */
	public BlockPos markerPos(Hit h) {
		return switch (h.type()) {
			case SEAT -> seats.get(h.index());
			case HOUSE -> houses.get(h.index());
			case ROOM -> convocation;
			case CENTER -> center.above();
		};
	}

	/** Déplace un repère sans changer son numéro. */
	public void moveMarker(Hit h, BlockPos to) {
		switch (h.type()) {
			case SEAT -> seats.set(h.index(), to);
			case HOUSE -> houses.set(h.index(), to);
			case ROOM -> convocation = to;
			case CENTER -> setCenter(to.below());
		}
	}

	/** Retire un repère ; les suivants de la même sorte reculent d'un numéro. */
	public void removeMarker(Hit h) {
		switch (h.type()) {
			case SEAT -> seats.remove(h.index());
			case HOUSE -> houses.remove(h.index());
			case ROOM -> convocation = null;
			case CENTER -> throw new fr.valcombe.rules.GameException("Le centre de la place ne se retire pas : déplacez-le.");
		}
	}

	/**
	 * Renumérote les trônes et les maisons dans le sens des aiguilles d'une montre en partant du nord :
	 * le trône 1 est le plus au nord, et la maison 1 est celle qui lui fait face.
	 */
	public void sortClockwise() {
		java.util.Comparator<BlockPos> turn = java.util.Comparator.comparingDouble(b -> {
			double a = Math.atan2(b.getX() - center.getX(), -(b.getZ() - center.getZ()));
			return a < -1e-9 ? a + 2 * Math.PI : a;
		});
		seats.sort(turn);
		houses.sort(turn);
	}

	/** Un endroit où tenir debout, deux blocs plus loin que p (au nord d'abord), ou p lui-même. */
	public static BlockPos standNear(ServerLevel level, BlockPos p) {
		for (int d = 2; d >= 1; d--) {
			for (Direction dir : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
				BlockPos q = p.relative(dir, d);
				if (standable(level, q) && (d == 1 || !blocks(level, p.relative(dir, 1)))) {
					return q;
				}
			}
		}
		return p;
	}

	private static boolean standable(ServerLevel level, BlockPos feet) {
		return !blocks(level, feet) && !blocks(level, feet.above()) && blocks(level, feet.below());
	}

	/** Ce bloc arrête-t-il un joueur ? (Un tapis ou une plaque ne compte pas.) */
	private static boolean blocks(ServerLevel level, BlockPos pos) {
		var shape = level.getBlockState(pos).getCollisionShape(level, pos);
		return !shape.isEmpty() && shape.max(Direction.Axis.Y) > 0.2;
	}

	/**
	 * Le sol à cette colonne : le premier endroit où tenir debout en montant depuis le dessous de la place
	 * (on trouve ainsi le plancher d'une maison, pas son toit) ; à défaut, le niveau de la place.
	 */
	public BlockPos groundAt(ServerLevel level, int x, int z) {
		for (int y = center.getY() - 3; y <= center.getY() + 22; y++) {
			BlockPos feet = new BlockPos(x, y, z);
			if (standable(level, feet)) {
				return feet;
			}
		}
		return new BlockPos(x, center.getY() + 1, z);
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

	/**
	 * Siège correspondant au bloc cliqué : le bloc du siège lui-même (le trône)
	 * ou celui juste en dessous ; -1 sinon.
	 */
	public int seatIndexAt(BlockPos clicked) {
		for (int i = 0; i < seats.size(); i++) {
			BlockPos s = seats.get(i);
			if (s.equals(clicked) || s.equals(clicked.above())) {
				return i;
			}
		}
		return -1;
	}

	/** Pieds d'un joueur dans chaque maison. */
	public List<Vec3> houseFeet() {
		return houses.stream().map(Village::feet).toList();
	}

	/** Pieds du joueur assis sur le siège k (numéro dans la carte). */
	public Vec3 seatFeet(int k) {
		return feet(seats.get(k));
	}

	/** Numéro de la maison où se trouve cette position (à 4 blocs près), ou -1. */
	public int houseAt(Vec3 pos) {
		for (int i = 0; i < houses.size(); i++) {
			Vec3 h = feet(houses.get(i));
			if (Math.abs(pos.y - h.y) < 4 && Math.hypot(pos.x - h.x, pos.z - h.z) < HOUSE_HALF + 0.5) {
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
	/** Centre de la salle, relatif au centre de la place. */
	private int[] hallCenter() {
		return new int[] {(int) Math.round(hallDistance * Math.cos(hallAngle())), (int) Math.round(hallDistance * Math.sin(hallAngle()))};
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
		pavilion(level, h[0], h[1]);
		BlockState air = Blocks.AIR.defaultBlockState();
		// Allée vers la place
		for (int r = plazaRadius + ROAD_WIDTH; r < hallDistance - 6; r++) {
			int x = (int) Math.round(r * Math.cos(hallAngle()));
			int z = (int) Math.round(r * Math.sin(hallAngle()));
			for (int w = -1; w <= 1; w++) {
				set(level, x + w, 0, z, Blocks.COBBLESTONE.defaultBlockState());
				for (int y = 1; y <= 3; y++) {
					set(level, x + w, y, z, air);
				}
			}
		}
		hall = true;
	}

	/** Le pavillon de la salle d'attente, centré en (hx, hz) : murs, toit, lanternes et dalles. */
	private void pavilion(ServerLevel level, int hx, int hz) {
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
	}

	/** Efface la salle d'attente de l'ancien plan (plus proche de la place), avant reconstruction. */
	public void clearLegacyHall(ServerLevel level) {
		if (!hall) {
			return;
		}
		int hx = (int) Math.round(47 * Math.cos(LEGACY_HALL_ANGLE));
		int hz = (int) Math.round(47 * Math.sin(LEGACY_HALL_ANGLE));
		for (int x = -12; x <= 12; x++) {
			for (int z = -10; z <= 10; z++) {
				set(level, hx + x, 0, hz + z, Blocks.GRASS_BLOCK.defaultBlockState());
				for (int y = 1; y <= 9; y++) {
					set(level, hx + x, y, hz + z, Blocks.AIR.defaultBlockState());
				}
			}
		}
	}

	/** Centre de la salle d'attente (pieds), ou null s'il n'y en a pas. */
	public Vec3 hallSpot() {
		if (!hall) {
			return null;
		}
		int[] h = hallCenter();
		return new Vec3(center.getX() + h[0] + 0.5, center.getY() + 1, center.getZ() + h[1] + 0.5);
	}

	/** Positions (absolues) des panneaux flottants de la salle : texte → position. */
	public java.util.Map<String, Vec3> hallBoards() {
		int[] h = hallCenter();
		var m = new java.util.LinkedHashMap<String, Vec3>();
		m.put("Dalle dorée : s'inscrire\nDalle rouge : se désinscrire\nObsidienne : devenir Conteur",
			new Vec3(center.getX() + h[0] + 0.5, center.getY() + 2.3, center.getZ() + h[1] + 0.5));
		m.put("Le bien gagne quand le Démon meurt.\nLe mal gagne quand il ne reste que deux vivants.",
			new Vec3(center.getX() + h[0] - 7.85, center.getY() + 2.5, center.getZ() + h[1] + 0.5));
		m.put("La nuit, chacun dort chez soi.\nLe jour, on débat, on chuchote,\npuis on vote à l'horloge.",
			new Vec3(center.getX() + h[0] + 8.85, center.getY() + 2.5, center.getZ() + h[1] + 0.5));
		m.put("Menu du jeu : touche J\nou le grimoire en main (clic droit)",
			new Vec3(center.getX() + h[0] + 0.5, center.getY() + 2.5, center.getZ() + h[1] - 5.85));
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
	/** Quand un plan est ouvert, les blocs y sont rangés au lieu d'être posés tout de suite. */
	private BuildQueue.Plan plan;

	/** Décalage en hauteur des blocs posés (une maison bâtie ailleurs qu'au niveau de la place). */
	private int lift;

	/**
	 * Bâtit tout de suite ce que pose work, à la hauteur voulue, et renvoie les blocs qui s'y trouvaient
	 * avant : de quoi tout remettre si l'on retire la construction.
	 */
	private java.util.Map<BlockPos, BlockState> buildNow(ServerLevel level, int liftY, Runnable work) {
		BuildQueue.Plan p = new BuildQueue.Plan();
		plan = p;
		lift = liftY;
		try {
			work.run();
		} finally {
			plan = null;
			lift = 0;
		}
		java.util.Map<BlockPos, BlockState> before = new java.util.LinkedHashMap<>();
		for (int i = 0; i < p.size(); i++) {
			before.putIfAbsent(p.at.get(i), level.getBlockState(p.at.get(i)));
			level.setBlock(p.at.get(i), p.states.get(i), FLAGS);
		}
		p.after.forEach(Runnable::run);
		return before;
	}

	/** Bâtit une maison du thème autour de ces pieds (porte vers la place) ; renvoie les blocs d'avant. */
	public java.util.Map<BlockPos, BlockState> buildHouseAt(ServerLevel level, BlockPos feet) {
		int hx = feet.getX() - center.getX();
		int hz = feet.getZ() - center.getZ();
		int index = houses.size();
		return buildNow(level, feet.getY() - 1 - center.getY(), () -> {
			houseAir(level, hx, hz);
			house(level, hx, hz, index, palette(theme));
		});
	}

	/** Dégage le volume d'une maison (toit qui déborde compris) avant de la bâtir. */
	private void houseAir(ServerLevel level, int hx, int hz) {
		for (int x = -HOUSE_HALF - 1; x <= HOUSE_HALF + 1; x++) {
			for (int z = -HOUSE_HALF - 1; z <= HOUSE_HALF + 1; z++) {
				for (int y = 1; y <= 10; y++) {
					set(level, hx + x, y, hz + z, Blocks.AIR.defaultBlockState());
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Constructions du catalogue (voir StructureStore)
	// ------------------------------------------------------------------
	/**
	 * Une construction du catalogue, faite des morceaux du village généré : ses blocs sont rangés dans un
	 * plan autour de l'origine (le bloc visé est en y = 0, les pieds en y = 1). Rien n'est posé ici.
	 */
	static BuildQueue.Plan prefab(String kind, String theme, int variant) {
		Village v = new Village(BlockPos.ZERO, false);
		v.theme = theme;
		Palette p = palette(theme);
		BuildQueue.Plan plan = new BuildQueue.Plan();
		v.plan = plan;
		switch (kind) {
			case "maison" -> {
				v.houseAir(null, 0, 0);
				v.house(null, 0, 0, variant, p);
			}
			case "salle" -> v.room(null, 0, 0, p);
			case "pavillon" -> v.pavilion(null, 0, 0);
			case "monument" -> v.centerpiece(null, p);
			case "etal" -> v.stall(null, 0, 0, variant);
			case "arbre" -> v.tree(null, 0, 0, p, p.hedge().defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
			case "simples" -> v.herbBed(null, 0, 0);
			case "tonneaux" -> v.cargo(null, 0, 0, p);
			case "lampadaire" -> v.lamp(p);
			case "estrade" -> v.dais(p);
			case "fontaine" -> v.fountain(p);
			case "banc" -> v.bench();
			case "arche" -> v.arch(p);
			default -> throw new IllegalArgumentException("Construction inconnue : " + kind);
		}
		return plan;
	}

	/** Lampadaire : une lanterne sur son poteau. */
	private void lamp(Palette p) {
		set(null, 0, 1, 0, p.post().defaultBlockState());
		set(null, 0, 2, 0, p.post().defaultBlockState());
		set(null, 0, 3, 0, p.light().defaultBlockState());
	}

	/** Estrade : un plancher surélevé de 7 × 4, trois marches devant, un pupitre, deux lanternes au fond. */
	private void dais(Palette p) {
		Block steps = switch (theme) {
			case "monastere" -> Blocks.TUFF_BRICK_STAIRS;
			case "port" -> Blocks.PRISMARINE_BRICK_STAIRS;
			default -> Blocks.STONE_BRICK_STAIRS;
		};
		for (int x = -3; x <= 3; x++) {
			for (int z = -2; z <= 1; z++) {
				set(null, x, 1, z, p.seatFloor().defaultBlockState());
				for (int y = 2; y <= 5; y++) {
					set(null, x, y, z, Blocks.AIR.defaultBlockState());
				}
			}
		}
		for (int x = -1; x <= 1; x++) {
			set(null, x, 1, 2, steps.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
		}
		set(null, 0, 2, -1, Blocks.LECTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LecternBlock.FACING, Direction.SOUTH));
		for (int x : new int[] {-3, 3}) {
			set(null, x, 2, -2, p.post().defaultBlockState());
			set(null, x, 3, -2, p.post().defaultBlockState());
			set(null, x, 4, -2, p.light().defaultBlockState());
		}
	}

	/** Fontaine : un bassin de 5 × 5 et, au milieu, une lanterne sur sa colonne. */
	private void fountain(Palette p) {
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				boolean rim = Math.abs(x) == 2 || Math.abs(z) == 2;
				set(null, x, 0, z, p.well().defaultBlockState());
				set(null, x, 1, z, rim ? p.well().defaultBlockState() : Blocks.WATER.defaultBlockState());
			}
		}
		set(null, 0, 1, 0, p.well().defaultBlockState());
		set(null, 0, 2, 0, p.rim().defaultBlockState());
		set(null, 0, 3, 0, p.light().defaultBlockState());
	}

	/** Banc : trois marches de bois côte à côte. */
	private void bench() {
		for (int x = -1; x <= 1; x++) {
			set(null, x, 1, 0, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
		}
	}

	/** Arche : deux piliers, un linteau, une lanterne suspendue. */
	private void arch(Palette p) {
		for (int x : new int[] {-2, 2}) {
			for (int y = 1; y <= 4; y++) {
				set(null, x, y, 0, p.pillar().defaultBlockState());
			}
		}
		for (int x = -2; x <= 2; x++) {
			set(null, x, 5, 0, p.roof().defaultBlockState());
		}
		for (int x = -1; x <= 1; x++) {
			for (int y = 1; y <= 4; y++) {
				set(null, x, y, 0, Blocks.AIR.defaultBlockState());
			}
		}
		BlockState light = p.light().defaultBlockState();
		if (light.hasProperty(net.minecraft.world.level.block.LanternBlock.HANGING)) {
			light = light.setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true);
		}
		set(null, 0, 4, 0, light);
	}

	/** Bâtit une salle de convocation close autour de ces pieds ; renvoie les blocs d'avant. */
	public java.util.Map<BlockPos, BlockState> buildRoomAt(ServerLevel level, BlockPos feet) {
		int rx = feet.getX() - center.getX();
		int rz = feet.getZ() - center.getZ();
		return buildNow(level, feet.getY() - 1 - center.getY(), () -> room(level, rx, rz, palette(theme)));
	}

	/** Village généré : bâtit la salle de convocation à l'écart, à l'opposé de la salle d'attente. */
	public void buildConvocation(ServerLevel level) {
		double a = hallAngle() + Math.PI;
		int rx = (int) Math.round((clearRadius + ROOM_GAP) * Math.cos(a));
		int rz = (int) Math.round((clearRadius + ROOM_GAP) * Math.sin(a));
		Palette p = palette(theme);
		for (int x = -6; x <= 6; x++) {
			for (int z = -6; z <= 6; z++) {
				for (int y = -3; y < 0; y++) {
					set(level, rx + x, y, rz + z, Blocks.STONE.defaultBlockState());
				}
				set(level, rx + x, 0, rz + z, p.grass().defaultBlockState());
				for (int y = 1; y <= 8; y++) {
					set(level, rx + x, y, rz + z, Blocks.AIR.defaultBlockState());
				}
			}
		}
		room(level, rx, rz, p);
		convocation = center.offset(rx, 1, rz);
	}

	/**
	 * Salle de convocation : une pièce close de 9 × 9, sans porte ni fenêtre (on y entre et on en sort
	 * par le Conteur). Le joueur se tient au milieu, le Conteur devant la bibliothèque du fond.
	 */
	private void room(ServerLevel level, int rx, int rz, Palette p) {
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = -4; x <= 4; x++) {
			for (int z = -4; z <= 4; z++) {
				boolean edge = Math.abs(x) == 4 || Math.abs(z) == 4;
				boolean corner = Math.abs(x) == 4 && Math.abs(z) == 4;
				boolean inlay = Math.abs(x) <= 1 && Math.abs(z) <= 1;
				set(level, rx + x, 0, rz + z, (inlay ? p.seatFloor() : p.houseFloor()).defaultBlockState());
				for (int y = 1; y <= 4; y++) {
					set(level, rx + x, y, rz + z, corner ? p.pillar().defaultBlockState() : edge ? p.wallB().defaultBlockState() : air);
				}
				set(level, rx + x, 5, rz + z, p.roof().defaultBlockState());
			}
		}
		for (int x = -3; x <= 3; x++) {
			set(level, rx + x, 1, rz - 3, Blocks.BOOKSHELF.defaultBlockState());
			set(level, rx + x, 2, rz - 3, Blocks.BOOKSHELF.defaultBlockState());
		}
		set(level, rx - 3, 1, rz + 3, Blocks.BARREL.defaultBlockState());
		set(level, rx + 3, 1, rz + 3, Blocks.LECTERN.defaultBlockState());
		BlockState light = p.light().defaultBlockState();
		if (light.hasProperty(net.minecraft.world.level.block.LanternBlock.HANGING)) {
			light = light.setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true);
		}
		for (int[] l : new int[][] {{-3, -2}, {3, -2}, {-3, 2}, {3, 2}}) {
			set(level, rx + l[0], 4, rz + l[1], light);
		}
	}

	private void set(ServerLevel level, int x, int y, int z, BlockState s) {
		BlockPos pos = new BlockPos(center.getX() + x, center.getY() + y + lift, center.getZ() + z);
		if (plan != null) {
			plan.set(pos, s);
		} else {
			level.setBlock(pos, s, FLAGS);
		}
	}

	/**
	 * Le plan de construction du village : tous ses blocs dans l'ordre, à poser petit à petit
	 * (voir BuildQueue), puis les trônes sculptés une fois les blocs en place.
	 */
	BuildQueue.Plan planBuild(ServerLevel level) {
		BuildQueue.Plan p = new BuildQueue.Plan();
		plan = p;
		try {
			build(level);
		} finally {
			plan = null;
		}
		return p;
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
		Block houseFloor, Block alley, Block hedge, Block grass, Block log) {
	}

	public static final java.util.List<String> THEMES = java.util.List.of("gothique", "monastere", "port");

	private static Palette palette(String theme) {
		return switch (theme == null ? "gothique" : theme) {
			case "monastere" -> new Palette(Blocks.POLISHED_DIORITE, Blocks.CALCITE, Blocks.DIORITE, Blocks.DIRT_PATH,
				Blocks.GRAVEL, Blocks.TUFF_BRICK_WALL, Blocks.POLISHED_TUFF, Blocks.SPRUCE_FENCE, Blocks.SOUL_LANTERN,
				Blocks.TUFF_BRICKS, Blocks.STRIPPED_SPRUCE_LOG, Blocks.CALCITE, Blocks.TUFF_BRICKS, Blocks.DEEPSLATE_TILES,
				Blocks.SPRUCE_PLANKS, Blocks.SMOOTH_STONE, Blocks.BIRCH_LEAVES, Blocks.GRASS_BLOCK, Blocks.BIRCH_LOG);
			case "port" -> new Palette(Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SANDSTONE, Blocks.SAND,
				Blocks.GRAVEL, Blocks.SANDSTONE_WALL, Blocks.PRISMARINE_BRICKS, Blocks.OAK_FENCE, Blocks.LANTERN,
				Blocks.PRISMARINE_BRICKS, Blocks.OAK_LOG, Blocks.OAK_PLANKS, Blocks.BIRCH_PLANKS, Blocks.DARK_PRISMARINE,
				Blocks.OAK_PLANKS, Blocks.PACKED_MUD, Blocks.OAK_LEAVES, Blocks.SAND, Blocks.OAK_LOG);
			default -> new Palette(Blocks.STONE_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE, Blocks.DIRT_PATH,
				Blocks.GRAVEL, Blocks.STONE_BRICK_WALL, Blocks.POLISHED_ANDESITE, Blocks.SPRUCE_FENCE, Blocks.LANTERN,
				Blocks.CHISELED_STONE_BRICKS, Blocks.DARK_OAK_LOG, Blocks.DEEPSLATE_BRICKS, Blocks.STONE_BRICKS,
				Blocks.DEEPSLATE_TILES, Blocks.SPRUCE_PLANKS, Blocks.COBBLESTONE, Blocks.SPRUCE_LEAVES, Blocks.GRASS_BLOCK,
				Blocks.SPRUCE_LOG);
		};
	}

	public String theme() {
		return theme;
	}

	/**
	 * Construit le village : la place pavée (rayon 17) et son cercle de 16 trônes, le puits
	 * et la cloche au centre, une route circulaire, 16 allées vers 16 maisons meublées,
	 * des étals de marché et des arbres entre les allées.
	 */
	public void build(ServerLevel level) {
		Palette p = palette(theme);
		decorGen++;
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState hedge = p.hedge().defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		java.util.Random rnd = new java.util.Random(center.asLong());

		// Terrain : socle de pierre, sols, et tout l'air au-dessus
		for (int x = -clearRadius; x <= clearRadius; x++) {
			for (int z = -clearRadius; z <= clearRadius; z++) {
				double d = Math.hypot(x, z);
				if (d > clearRadius) {
					continue;
				}
				for (int y = -3; y < 0; y++) {
					set(level, x, y, z, Blocks.STONE.defaultBlockState());
				}
				Block floor;
				if (d <= plazaRadius) {
					floor = rnd.nextInt(5) == 0 ? p.plazaAlt1() : rnd.nextInt(3) == 0 ? p.plazaAlt2() : p.plaza();
				} else if (d <= plazaRadius + ROAD_WIDTH) {
					floor = rnd.nextInt(4) == 0 ? p.ground2() : p.alley();
				} else if (theme.equals("port") && withHouses && d > houseRadius + 9.5 && d <= clearRadius - 1.5) {
					floor = Blocks.WATER; // le port : un chenal fait le tour du village
				} else {
					floor = rnd.nextInt(9) == 0 ? p.ground() : p.grass();
				}
				set(level, x, 0, z, floor.defaultBlockState());
				for (int y = 1; y <= CLEAR_HEIGHT; y++) {
					set(level, x, y, z, air);
				}
			}
		}
		// Muret de la place, ouvert en face de chaque allée
		for (int k = 0; k < 720; k++) {
			double a = Math.toRadians(k / 2.0);
			double gap = Double.MAX_VALUE;
			for (int i = 0; i < seatTotal; i++) {
				double da = Math.atan2(Math.sin(a - angle(i, seatTotal)), Math.cos(a - angle(i, seatTotal)));
				gap = Math.min(gap, Math.abs(da) * plazaRadius);
			}
			if (gap > 2.2) {
				set(level, (int) Math.round((plazaRadius + 0.5) * Math.cos(a)), 1,
					(int) Math.round((plazaRadius + 0.5) * Math.sin(a)), p.rim().defaultBlockState());
			}
		}
		// Trônes : une marche tournée vers le centre, un dossier, deux accoudoirs
		for (int i = 0; i < seatTotal; i++) {
			double a = angle(i, seatTotal);
			int x = (int) Math.round(seatRadius * Math.cos(a));
			int z = (int) Math.round(seatRadius * Math.sin(a));
			throne(level, x, z, p);
			if (i % 2 == 0) {
				int lx = (int) Math.round((seatRadius + 3) * Math.cos(a + Math.PI / seatTotal));
				int lz = (int) Math.round((seatRadius + 3) * Math.sin(a + Math.PI / seatTotal));
				set(level, lx, 1, lz, p.post().defaultBlockState());
				set(level, lx, 2, lz, p.post().defaultBlockState());
				set(level, lx, 3, lz, p.light().defaultBlockState());
			}
		}
		centerpiece(level, p);
		if (theme.equals("monastere")) {
			cloisterWall(level, p);
		}

		// Allées, maisons, étals, arbres et haies
		for (int i = 0; i < seatTotal; i++) {
			double a = angle(i, seatTotal);
			for (int r = plazaRadius + ROAD_WIDTH; withHouses && r <= houseRadius - HOUSE_HALF; r++) {
				for (int w = -1; w <= 1; w++) {
					set(level, (int) Math.round(r * Math.cos(a) - w * Math.sin(a)), 0,
						(int) Math.round(r * Math.sin(a) + w * Math.cos(a)), p.alley().defaultBlockState());
				}
			}
			if (withHouses) {
				house(level, (int) Math.round(houseRadius * Math.cos(a)), (int) Math.round(houseRadius * Math.sin(a)), i, p);
			}
			if (i == 0) {
				continue; // le chemin de la salle d'attente passe ici
			}
			double mid = a + Math.PI / seatTotal;
			int midR = (plazaRadius + houseRadius) / 2;
			int mx = (int) Math.round(midR * Math.cos(mid));
			int mz = (int) Math.round(midR * Math.sin(mid));
			if (i % 2 == 0) {
				if (theme.equals("monastere")) {
					herbBed(level, mx, mz);
				} else {
					stall(level, mx, mz, i);
				}
			} else if (nookSlot(i)) {
				nook(level, mx, mz, p);
			} else if (theme.equals("port")) {
				cargo(level, mx, mz, p);
			} else {
				tree(level, mx, mz, p, hedge);
			}
			for (int r = houseRadius - 5; withHouses && r <= houseRadius + 8; r++) {
				int x = (int) Math.round(r * Math.cos(mid));
				int z = (int) Math.round(r * Math.sin(mid));
				set(level, x, 1, z, hedge);
				if (r % 4 != 0) {
					set(level, x, 2, z, hedge);
				}
			}
		}
	}

	/**
	 * Le monument du centre, propre à chaque thème (cinq blocs de côté, la cloche au milieu) :
	 * un puits couvert (gothique), le jardin d'un cloître sous ses arcades (monastère), un fanal de port.
	 */
	private void centerpiece(ServerLevel level, Palette p) {
		BlockState pillar = p.pillar().defaultBlockState();
		switch (theme) {
			case "monastere" -> {
				// Jardin de cloître : carré de mousse et de fougères, arcades de pierre, cloche sous la croisée.
				for (int x = -2; x <= 2; x++) {
					for (int z = -2; z <= 2; z++) {
						boolean rim = Math.abs(x) == 2 || Math.abs(z) == 2;
						set(level, x, 0, z, rim ? p.well().defaultBlockState() : Blocks.MOSS_BLOCK.defaultBlockState());
						if (!rim && (x + z) % 2 != 0) {
							set(level, x, 1, z, Blocks.FERN.defaultBlockState());
						}
						if (rim) {
							set(level, x, 5, z, p.well().defaultBlockState());
						}
					}
				}
				for (int[] c : new int[][] {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
					for (int y = 1; y <= 4; y++) {
						set(level, c[0], y, c[1], pillar);
					}
				}
				for (int k = -2; k <= 2; k++) {
					set(level, k, 6, 0, p.well().defaultBlockState());
					set(level, 0, 6, k, p.well().defaultBlockState());
				}
				set(level, 0, 5, 0, Blocks.BELL.defaultBlockState());
				set(level, 0, 7, 0, Blocks.SOUL_LANTERN.defaultBlockState());
			}
			case "port" -> {
				// Fanal : socle de pierre marine, fût rayé rouge et blanc, cloche sous la lanterne.
				for (int x = -2; x <= 2; x++) {
					for (int z = -2; z <= 2; z++) {
						set(level, x, 0, z, p.well().defaultBlockState());
						boolean core = Math.abs(x) <= 1 && Math.abs(z) <= 1;
						boolean corner = Math.abs(x) == 1 && Math.abs(z) == 1;
						if (core) {
							for (int y = 1; y <= 3; y++) {
								set(level, x, y, z, (y % 2 == 1 ? Blocks.WOOL.red() : Blocks.WOOL.white()).defaultBlockState());
							}
							set(level, x, 4, z, corner ? Blocks.OAK_FENCE.defaultBlockState() : Blocks.AIR.defaultBlockState());
							set(level, x, 6, z, Blocks.DARK_OAK_SLAB.defaultBlockState());
						} else if (Math.abs(x) == 2 && Math.abs(z) == 2) {
							set(level, x, 1, z, p.rim().defaultBlockState());
						}
					}
				}
				for (int[] c : new int[][] {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
					set(level, c[0], 5, c[1], Blocks.OAK_FENCE.defaultBlockState());
				}
				set(level, 0, 5, 0, Blocks.BELL.defaultBlockState());
				set(level, 0, 7, 0, Blocks.SEA_LANTERN.defaultBlockState());
			}
			default -> {
				// Puits couvert et cloche
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
						set(level, c[0], y, c[1], pillar);
					}
				}
				for (int x = -2; x <= 2; x++) {
					for (int z = -2; z <= 2; z++) {
						set(level, x, 6, z, Blocks.DARK_OAK_SLAB.defaultBlockState());
					}
				}
				set(level, 0, 5, 0, Blocks.BELL.defaultBlockState());
				set(level, 0, 7, 0, Blocks.SOUL_LANTERN.defaultBlockState());
			}
		}
	}

	/** Monastère : un mur d'enceinte bas fait le tour du village, ouvert devant le chemin de la salle d'attente. */
	private void cloisterWall(ServerLevel level, Palette p) {
		double r = clearRadius - 1.5;
		int steps = (int) Math.ceil(2 * Math.PI * r * 2);
		for (int k = 0; k < steps; k++) {
			double a = 2 * Math.PI * k / steps;
			double da = Math.atan2(Math.sin(a - hallAngle()), Math.cos(a - hallAngle()));
			if (Math.abs(da) * r < 3) {
				continue;
			}
			int x = (int) Math.round(r * Math.cos(a));
			int z = (int) Math.round(r * Math.sin(a));
			set(level, x, 1, z, p.wallA().defaultBlockState());
			set(level, x, 2, z, p.wallB().defaultBlockState());
			if (k % 12 == 0) {
				set(level, x, 3, z, p.rim().defaultBlockState());
			}
		}
	}

	/** Monastère : un carré de simples, bordé de pierre, avec son composteur. */
	private void herbBed(ServerLevel level, int cx, int cz) {
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				boolean rim = Math.abs(x) == 2 || Math.abs(z) == 2;
				set(level, cx + x, 0, cz + z, (rim ? Blocks.TUFF_BRICKS : Blocks.COARSE_DIRT).defaultBlockState());
				if (!rim && (x != 0 || z != 0)) {
					set(level, cx + x, 1, cz + z, ((x + z) % 2 == 0 ? Blocks.FERN : Blocks.SWEET_BERRY_BUSH).defaultBlockState());
				}
			}
		}
		set(level, cx, 1, cz, Blocks.COMPOSTER.defaultBlockState());
	}

	/** Port : une pile de tonneaux et un poteau d'amarrage coiffé d'une lanterne. */
	private void cargo(ServerLevel level, int cx, int cz, Palette p) {
		for (int x = -1; x <= 1; x++) {
			set(level, cx + x, 1, cz, Blocks.BARREL.defaultBlockState());
		}
		set(level, cx, 1, cz + 1, Blocks.BARREL.defaultBlockState());
		set(level, cx, 2, cz, Blocks.BARREL.defaultBlockState());
		set(level, cx + 2, 1, cz - 1, p.post().defaultBlockState());
		set(level, cx + 2, 2, cz - 1, p.post().defaultBlockState());
		set(level, cx + 2, 3, cz - 1, p.light().defaultBlockState());
	}

	/** Trône de bois : on s'y assoit d'un clic droit. */
	/**
	 * Trône sculpté (modèle 3D du mod, entité d'affichage) posé sur une estrade. Un bloc invisible
	 * (barrière) lui donne du volume et reçoit le clic droit pour s'asseoir.
	 */
	private void throne(ServerLevel level, int x, int z, Palette p) {
		Direction back = away(x, z);
		set(level, x, 0, z, p.seatFloor().defaultBlockState());
		set(level, x + back.getStepX(), 0, z + back.getStepZ(), p.seatFloor().defaultBlockState());
		set(level, x, 1, z, Blocks.BARRIER.defaultBlockState());
		if (level.getServer() == null) {
			return;
		}
		String summon = throneDisplay(new Vec3(center.getX() + x + 0.5, center.getY() + 1, center.getZ() + z + 0.5));
		MinecraftServer server = level.getServer();
		if (plan != null) {
			plan.after.add(() -> WorldSetup.run(server, summon)); // le trône sculpté attend que son estrade soit posée
		} else {
			WorldSetup.run(server, summon);
		}
	}

	/** La commande qui pose un trône sculpté de la génération en cours, les pieds du joueur en f. */
	private String throneDisplay(Vec3 f) {
		return String.format(java.util.Locale.ROOT,
			"summon minecraft:item_display %.3f %.3f %.3f {Tags:[\"vc_trone\",\"vc_tr_%d\"],Rotation:[%.1ff,0f],"
				+ "item:{id:\"minecraft:paper\",count:1,components:{\"minecraft:item_model\":\"valcombe:trone\"}},"
				+ "view_range:2f,transformation:{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],"
				+ "right_rotation:[0f,0f,0f,1f],scale:[1.15f,1.15f,1.15f]}}",
			f.x, f.y + 0.5, f.z, decorGen, yawToCenter(f));
	}

	/**
	 * Village généré rechargé de la bibliothèque : la structure enregistrée ne contient que des blocs,
	 * les trônes sculptés (des entités) sont donc reposés sur chaque estrade.
	 */
	public void placeThrones(MinecraftServer server) {
		if (!generated) {
			return;
		}
		decorGen++;
		for (BlockPos s : seats) {
			WorldSetup.run(server, throneDisplay(feet(s)));
		}
	}

	// ------------------------------------------------------------------
	// Recoins : niches murées entre deux maisons, ouvertes vers les champs, à l'abri des regards de la place
	// ------------------------------------------------------------------
	/** Une place sur quatre entre deux maisons reçoit un recoin (à la place d'un arbre ou d'une cargaison). */
	private boolean nookSlot(int i) {
		return i != 0 && i % 4 == 3;
	}

	/** Pieds d'un joueur au milieu de chaque recoin (villages générés selon le plan 4 et plus). */
	public List<BlockPos> nooks() {
		List<BlockPos> out = new ArrayList<>();
		if (!generated || layout < 4) {
			return out;
		}
		int midR = (plazaRadius + houseRadius) / 2;
		for (int i = 0; i < seatTotal; i++) {
			if (nookSlot(i)) {
				double mid = angle(i, seatTotal) + Math.PI / seatTotal;
				out.add(center.offset((int) Math.round(midR * Math.cos(mid)), 1, (int) Math.round(midR * Math.sin(mid))));
			}
		}
		return out;
	}

	/**
	 * Numéro du recoin où se trouve cette position, ou -1. Le toit du recoin doit être là : une carte d'un
	 * plan plus ancien (un arbre à cette place) n'a pas de recoin.
	 */
	public int nookAt(Vec3 pos, ServerLevel level) {
		List<BlockPos> list = nooks();
		for (int i = 0; i < list.size(); i++) {
			BlockPos n = list.get(i);
			if (Math.abs(pos.x - (n.getX() + 0.5)) <= 1.6 && Math.abs(pos.z - (n.getZ() + 0.5)) <= 1.6
				&& pos.y >= n.getY() - 1 && pos.y <= n.getY() + 2.5
				&& level.getBlockState(n.above(3)).is(palette(theme).roof())) {
				return i;
			}
		}
		return -1;
	}

	/** Recoin de 5 × 5 : murs sur trois côtés (celui de la place compris), toit, lanterne et banc. */
	private void nook(ServerLevel level, int cx, int cz, Palette p) {
		// L'ouverture regarde vers l'extérieur du village (à l'opposé de la place).
		boolean alongX = Math.abs(cx) >= Math.abs(cz);
		int out = alongX ? Integer.signum(cx) : Integer.signum(cz);
		BlockState wall = p.wallA().defaultBlockState();
		BlockState wall2 = p.wallB().defaultBlockState();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				int along = alongX ? dx : dz;
				boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				boolean opening = along == 2 * out && Math.abs(alongX ? dz : dx) <= 1;
				set(level, cx + dx, 0, cz + dz, p.houseFloor().defaultBlockState());
				for (int y = 1; y <= 3; y++) {
					set(level, cx + dx, y, cz + dz, edge && !opening ? (y == 2 ? wall2 : wall) : Blocks.AIR.defaultBlockState());
				}
				set(level, cx + dx, 4, cz + dz, p.roof().defaultBlockState());
			}
		}
		set(level, cx, 3, cz, Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
		// Banc contre le mur du fond (côté place), tourné vers l'ouverture
		net.minecraft.core.Direction facing = alongX ? (out > 0 ? net.minecraft.core.Direction.WEST : net.minecraft.core.Direction.EAST)
			: (out > 0 ? net.minecraft.core.Direction.NORTH : net.minecraft.core.Direction.SOUTH);
		for (int s = -1; s <= 1; s++) {
			int bx = alongX ? cx - out : cx + s;
			int bz = alongX ? cz + s : cz - out;
			set(level, bx, 1, bz, Blocks.SPRUCE_STAIRS.defaultBlockState()
				.setValue(net.minecraft.world.level.block.StairBlock.FACING, facing.getOpposite()));
		}
	}

	/** Étal de marché : quatre poteaux, un auvent rayé, un comptoir. */
	private void stall(ServerLevel level, int cx, int cz, int index) {
		Block stripe = switch (index % 6) {
			case 0 -> Blocks.WOOL.red();
			case 2 -> Blocks.WOOL.blue();
			default -> Blocks.WOOL.green();
		};
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				boolean corner = Math.abs(x) == 1 && Math.abs(z) == 1;
				if (corner) {
					set(level, cx + x, 1, cz + z, Blocks.SPRUCE_FENCE.defaultBlockState());
					set(level, cx + x, 2, cz + z, Blocks.SPRUCE_FENCE.defaultBlockState());
				}
				set(level, cx + x, 3, cz + z, ((x + 1) % 2 == 0 ? stripe : Blocks.WOOL.white()).defaultBlockState());
			}
		}
		set(level, cx, 1, cz, Blocks.BARREL.defaultBlockState());
		set(level, cx + 1, 1, cz, Blocks.SPRUCE_SLAB.defaultBlockState()
			.setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP));
		set(level, cx - 1, 1, cz, Blocks.SPRUCE_SLAB.defaultBlockState()
			.setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP));
	}

	/** Arbre simple : un tronc et une couronne de feuilles. */
	private void tree(ServerLevel level, int cx, int cz, Palette p, BlockState leaves) {
		for (int y = 1; y <= 5; y++) {
			set(level, cx, y, cz, p.log().defaultBlockState());
		}
		for (int y = 4; y <= 7; y++) {
			int r = y <= 5 ? 2 : 1;
			for (int x = -r; x <= r; x++) {
				for (int z = -r; z <= r; z++) {
					boolean corner = Math.abs(x) == r && Math.abs(z) == r;
					if ((x != 0 || z != 0 || y > 5) && !(corner && (y == 5 || y == 7))) {
						set(level, cx + x, y, cz + z, leaves);
					}
				}
			}
		}
	}

	/** Maison de 9 × 9 : toit en pyramide, fenêtres, lit de paille, tonneaux et lanterne. */
	private void house(ServerLevel level, int hx, int hz, int index, Palette p) {
		BlockState wall = (index % 2 == 0 ? p.wallA() : p.wallB()).defaultBlockState();
		BlockState log = p.pillar().defaultBlockState();
		BlockState roof = p.roof().defaultBlockState();
		int h = HOUSE_HALF;
		for (int x = -h; x <= h; x++) {
			for (int z = -h; z <= h; z++) {
				boolean edge = Math.abs(x) == h || Math.abs(z) == h;
				boolean corner = Math.abs(x) == h && Math.abs(z) == h;
				set(level, hx + x, 0, hz + z, p.houseFloor().defaultBlockState());
				for (int y = 1; y <= 4; y++) {
					if (corner) {
						set(level, hx + x, y, hz + z, log);
					} else if (edge) {
						boolean window = (y == 2 || y == 3) && (Math.abs(x) == 2 || Math.abs(z) == 2);
						set(level, hx + x, y, hz + z, window ? Blocks.GLASS_PANE.defaultBlockState() : wall);
					}
				}
			}
		}
		// Toit en pyramide, qui déborde d'un bloc
		for (int y = 5, r = h + 1; r >= 0; y++, r--) {
			for (int x = -r; x <= r; x++) {
				for (int z = -r; z <= r; z++) {
					if (r == 0 || Math.abs(x) == r || Math.abs(z) == r || y == 5) {
						set(level, hx + x, y, hz + z, roof);
					}
				}
			}
		}
		Direction toPlaza = away(-hx, -hz);
		int ox = toPlaza.getStepX() * h;
		int oz = toPlaza.getStepZ() * h;
		BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, toPlaza);
		set(level, hx + ox, 1, hz + oz, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		set(level, hx + ox, 2, hz + oz, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		set(level, hx + ox, 3, hz + oz, wall);
		// Lit de paille au fond, sous une couverture
		Direction side = toPlaza.getClockWise();
		for (int k = 2; k <= 3; k++) {
			int bx = hx - toPlaza.getStepX() * k + side.getStepX() * 2;
			int bz = hz - toPlaza.getStepZ() * k + side.getStepZ() * 2;
			set(level, bx, 1, bz, Blocks.HAY_BLOCK.defaultBlockState());
			set(level, bx, 2, bz, Blocks.CARPET.white().defaultBlockState());
		}
		set(level, hx - toPlaza.getStepX() * 3 - side.getStepX() * 2, 1, hz - toPlaza.getStepZ() * 3 - side.getStepZ() * 2,
			Blocks.BARREL.defaultBlockState());
		set(level, hx - toPlaza.getStepX() * 3 - side.getStepX() * 3, 1, hz - toPlaza.getStepZ() * 3 - side.getStepZ() * 3,
			Blocks.BARREL.defaultBlockState());
		// Tapis au centre (là où l'on dort la nuit), lanterne suspendue au-dessus
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				set(level, hx + x, 1, hz + z, Blocks.CARPET.brown().defaultBlockState());
			}
		}
		BlockState light = p.light().defaultBlockState();
		if (light.hasProperty(net.minecraft.world.level.block.LanternBlock.HANGING)) {
			light = light.setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true);
		}
		set(level, hx, 4, hz, light);
		// Lanterne sur poteau devant la porte
		int px = hx + toPlaza.getStepX() * (h + 1) + side.getStepX() * 2;
		int pz = hz + toPlaza.getStepZ() * (h + 1) + side.getStepZ() * 2;
		set(level, px, 1, pz, p.post().defaultBlockState());
		set(level, px, 2, pz, p.light().defaultBlockState());
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
		save(file(server), false);
	}

	/** Carte oubliée : la fiche garde son emprise, pour que la prochaine carte bâtie la retire du monde. */
	public void saveForgotten(MinecraftServer server) {
		save(file(server), true);
	}

	void save(Path f, boolean forgotten) {
		Data d = new Data();
		d.modified = modified;
		d.built = built;
		d.forgotten = forgotten;
		d.carte = libraryId.isEmpty() ? null : libraryId;
		d.carteNom = libraryName.isEmpty() ? null : libraryName;
		d.center = arr(center);
		d.generated = generated;
		d.layout = layout;
		d.decorGen = decorGen;
		d.seatTotal = seatTotal;
		d.size = size;
		d.withHouses = withHouses;
		d.hall = hall;
		d.theme = theme;
		seats.forEach(p -> d.seats.add(arr(p)));
		houses.forEach(p -> d.houses.add(arr(p)));
		d.convocation = convocation == null ? null : arr(convocation);
		try {
			// Écrite à côté puis mise en place d'un coup, avec une copie de secours : une coupure pendant
			// l'écriture ne doit pas faire croire au démarrage suivant qu'il n'y a jamais eu de village.
			SafeFile.write(f, GSON.toJson(d));
		} catch (IOException e) {
			ValcombeMod.LOG.warn("Impossible d'enregistrer le village", e);
		}
	}

	/** Ce que la fiche du village apprend au démarrage. */
	public enum Fiche {
		/** Aucune fiche n'a jamais existé : premier démarrage. */
		ABSENT,
		/** La carte en service. */
		READY,
		/** Carte oubliée : plus de carte en service, mais ses blocs sont encore dans le monde. */
		FORGOTTEN,
		/** Une fiche existe mais ne se lit plus (ni elle, ni sa copie de secours). */
		UNREADABLE
	}

	/** La fiche relue : son état et, s'il y en a une, la carte (en service ou oubliée). */
	public record Loaded(Fiche state, Village village) {
	}

	private static Data parse(String json) {
		Data d = GSON.fromJson(json, Data.class);
		return d != null && d.center != null && d.center.length == 3 && d.seats != null && d.houses != null ? d : null;
	}

	private static boolean valid(String json) {
		try {
			return parse(json) != null;
		} catch (RuntimeException e) {
			return false;
		}
	}

	public static Loaded load(MinecraftServer server) {
		return load(file(server));
	}

	static Loaded load(Path json) {
		try {
			if (SafeFile.exists(json)) {
				String text = SafeFile.read(json, Village::valid);
				if (text == null) {
					ValcombeMod.LOG.error("Fiche du village illisible ({}), copie de secours comprise", json);
					return new Loaded(Fiche.UNREADABLE, null);
				}
				Data d = parse(text);
				Village v = new Village(pos(d.center), d.generated);
				v.layout = d.layout;
				v.decorGen = Math.max(1, d.decorGen);
				if (d.seatTotal > 0) {
					v.seatTotal = d.seatTotal;
					v.size = d.size;
				}
				v.withHouses = d.withHouses == null || d.withHouses;
				v.layout();
				v.hall = d.hall;
				v.theme = d.theme == null ? "gothique" : d.theme;
				d.seats.forEach(a -> v.seats.add(pos(a)));
				d.houses.forEach(a -> v.houses.add(pos(a)));
				v.convocation = d.convocation == null ? null : pos(d.convocation);
				// Fiche d'avant ce suivi : on ne sait pas ce qui a été bâti dessus à la main, la carte est donc à protéger.
				v.modified = d.modified == null || d.modified;
				v.setLibrary(d.carte, d.carteNom);
				v.built = d.built != null && d.built.length == 6 ? d.built : null;
				return new Loaded(d.forgotten ? Fiche.FORGOTTEN : Fiche.READY, v);
			}
			// Ancien format (version 0.1.0) : seulement le centre d'un village généré.
			Path old = json.resolveSibling("valcombe-village.txt");
			if (Files.exists(old)) {
				String[] p = Files.readString(old).trim().split(" ");
				Village v = generated(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
				v.layout = 1;
				return new Loaded(Fiche.READY, v);
			}
		} catch (IOException | RuntimeException e) {
			ValcombeMod.LOG.error("Fiche du village illisible", e);
			return new Loaded(Fiche.UNREADABLE, null);
		}
		return new Loaded(Fiche.ABSENT, null);
	}
}
