package fr.valcombe.mc;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * Les repères d'une carte en coordonnées du monde : le centre de la place, les trônes (avec leur orientation),
 * les maisons et la salle de convocation. La bibliothèque, elle, les range relatifs au centre (voir MapStore.MapDef) ;
 * cette classe fait le passage de l'un à l'autre, sans rien toucher dans un monde (elle se vérifie sans Minecraft lancé).
 *
 * @param center      centre de la place : le bloc du sol (on marche un bloc au-dessus)
 * @param seats       trônes, dans l'ordre de la carte
 * @param houses      maisons, dans l'ordre de la carte
 * @param convocation salle de convocation, ou null
 */
public record MapMarks(BlockPos center, List<Seat> seats, List<BlockPos> houses, BlockPos convocation) {
	/**
	 * Un trône.
	 *
	 * @param pos    son bloc (les pieds du joueur assis sont au milieu de ce bloc)
	 * @param yaw    vers où il regarde, en degrés comme le regard d'un joueur (0 : le sud, 90 : l'ouest)
	 * @param turned tourné à la main (sinon il regarde le centre de la place)
	 */
	public record Seat(BlockPos pos, float yaw, boolean turned) {
	}

	public MapMarks {
		seats = List.copyOf(seats);
		houses = List.copyOf(houses);
	}

	/** Marge autour des repères dans le rayon d'une carte, comme pour une carte faite à la main (voir Village.farthest). */
	static final int MARGIN = 8;
	/** Plus petit rayon d'une carte faite à la main. */
	static final int MIN_REACH = 12;

	/**
	 * Les repères d'une fiche de la bibliothèque, la place posée en center. Un trône sans orientation enregistrée
	 * regarde le centre ; une orientation qui ne désigne aucun trône est ignorée (comme au chargement d'une carte).
	 */
	public static MapMarks of(MapStore.MapDef d, BlockPos center) {
		List<Seat> seats = new ArrayList<>();
		for (int[] a : d.sieges) {
			BlockPos p = center.offset(a[0], a[1], a[2]);
			Float turned = null;
			if (d.orientations != null) {
				for (double[] o : d.orientations) {
					// Même lecture que Village.setRelSeatYaws : les décalages sont des entiers.
					if (o != null && o.length == 4 && (int) o[0] == a[0] && (int) o[1] == a[1] && (int) o[2] == a[2]) {
						turned = (float) o[3];
					}
				}
			}
			seats.add(new Seat(p, turned != null ? turned : yawToCenter(center, p), turned != null));
		}
		List<BlockPos> houses = new ArrayList<>();
		for (int[] a : d.logis) {
			houses.add(center.offset(a[0], a[1], a[2]));
		}
		BlockPos room = d.convocation != null && d.convocation.length == 3
			? center.offset(d.convocation[0], d.convocation[1], d.convocation[2]) : null;
		return new MapMarks(center, seats, houses, room);
	}

	/**
	 * Range ces repères dans une fiche, relatifs au centre : trônes, maisons, salle de convocation et orientations
	 * (seulement celles des trônes tournés à la main ; null s'il n'y en a aucun, comme une carte d'avant ce champ).
	 */
	public void writeTo(MapStore.MapDef d) {
		d.sieges = new ArrayList<>();
		List<double[]> yaws = new ArrayList<>();
		for (Seat s : seats) {
			int[] rel = rel(s.pos());
			d.sieges.add(rel);
			if (s.turned()) {
				yaws.add(new double[] {rel[0], rel[1], rel[2], s.yaw()});
			}
		}
		d.orientations = yaws.isEmpty() ? null : yaws;
		d.logis = new ArrayList<>();
		for (BlockPos h : houses) {
			d.logis.add(rel(h));
		}
		d.convocation = convocation == null ? null : rel(convocation);
	}

	/** Une position relative au centre : {dx, dy, dz}. */
	public int[] rel(BlockPos p) {
		return new int[] {p.getX() - center.getX(), p.getY() - center.getY(), p.getZ() - center.getZ()};
	}

	/** Les mêmes repères décalés de (dx, dy, dz) : la carte posée ailleurs. Les orientations ne changent pas. */
	public MapMarks translated(int dx, int dy, int dz) {
		List<Seat> s = new ArrayList<>();
		for (Seat seat : seats) {
			s.add(new Seat(seat.pos().offset(dx, dy, dz), seat.yaw(), seat.turned()));
		}
		List<BlockPos> h = new ArrayList<>();
		for (BlockPos p : houses) {
			h.add(p.offset(dx, dy, dz));
		}
		return new MapMarks(center.offset(dx, dy, dz), s, h, convocation == null ? null : convocation.offset(dx, dy, dz));
	}

	/** Les mêmes repères, la place posée en newCenter. */
	public MapMarks movedTo(BlockPos newCenter) {
		return translated(newCenter.getX() - center.getX(), newCenter.getY() - center.getY(), newCenter.getZ() - center.getZ());
	}

	/** Les blocs des trônes, dans l'ordre. */
	public List<BlockPos> seatPositions() {
		return seats.stream().map(Seat::pos).toList();
	}

	/**
	 * Rayon qu'il faut à une carte pour contenir tous ces repères, marge comprise (même calcul que Village.farthest) :
	 * au moins MIN_REACH.
	 */
	public int reach() {
		int r = MIN_REACH;
		for (Seat s : seats) {
			r = Math.max(r, reach(s.pos()));
		}
		for (BlockPos h : houses) {
			r = Math.max(r, reach(h));
		}
		if (convocation != null) {
			r = Math.max(r, reach(convocation));
		}
		return r;
	}

	private int reach(BlockPos b) {
		return (int) Math.ceil(Math.hypot(b.getX() - center.getX(), b.getZ() - center.getZ())) + MARGIN;
	}

	/** Vers où regarde un trône posé en seat pour faire face au centre (même calcul que Village.yawToCenter). */
	static float yawToCenter(BlockPos center, BlockPos seat) {
		double dx = center.getX() - seat.getX();
		double dz = center.getZ() - seat.getZ();
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}
}
