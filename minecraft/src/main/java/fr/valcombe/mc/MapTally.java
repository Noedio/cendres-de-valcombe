package fr.valcombe.mc;

import java.util.ArrayList;
import java.util.List;

/**
 * Le compte des repères d'une carte en atelier, et ce qui lui manque pour se jouer : « 12 trônes sur 16, 12 maisons,
 * salle de convocation : oui ». C'est ce que montrent le compteur de l'atelier carte (au-dessus de la barre d'objets),
 * le panneau flottant de la place et l'écran de l'atelier.
 * <p>
 * <b>Jouable</b> : au moins {@value MapPlacer#MIN_SEATS} trônes (une partie compte 5 joueurs au moins, chacun sur
 * son trône). Le reste n'empêche pas de jouer mais se dit : des trônes sans maison, pas de salle de convocation.
 * Sans Minecraft : vérifié par les tests JUnit.
 *
 * @param seats  trônes posés
 * @param places trônes prévus (le nombre de places de la carte : 16 pour une carte faite à la main, le nombre
 *               choisi pour un village généré)
 * @param houses maisons posées
 * @param room   salle de convocation posée
 */
public record MapTally(int seats, int places, int houses, boolean room) {
	/** Une ligne de ce qui manque : bloquante (la carte ne se joue pas sans) ou simple conseil. */
	public record Line(boolean blocking, String text) {
	}

	/** Le compte des repères de cette carte. */
	public static MapTally of(Village v) {
		return new MapTally(v.seatCount(), v.seatTotal(), v.houseCount(), v.convocation() != null);
	}

	/** « 1 trône », « 0 trône », « 12 trônes » (zéro et un au singulier, comme en français). */
	static String plural(int n, String one, String many) {
		return n + " " + (Math.abs(n) > 1 ? many : one);
	}

	/** « 12 trônes sur 16 » (moins que prévu), sinon « 16 trônes ». */
	public String seatsText() {
		return seats < places ? plural(seats, "trône", "trônes") + " sur " + places : plural(seats, "trône", "trônes");
	}

	/** Le compteur : « 12 trônes sur 16, 12 maisons, salle de convocation : oui ». */
	public String counter() {
		return seatsText() + ", " + plural(houses, "maison", "maisons") + ", salle de convocation : " + (room ? "oui" : "non");
	}

	/** La carte se joue-t-elle ? (Assez de trônes.) */
	public boolean playable() {
		return seats >= MapPlacer.MIN_SEATS;
	}

	/** Combien de trônes manquent pour jouer (0 si elle se joue). */
	public int missingSeats() {
		return Math.max(0, MapPlacer.MIN_SEATS - seats);
	}

	/** Ce qui manque, dans l'ordre : d'abord ce qui empêche de jouer, puis les conseils. Vide : rien à redire. */
	public List<Line> missing() {
		List<Line> out = new ArrayList<>();
		int m = missingSeats();
		if (m > 0) {
			out.add(new Line(true, "Il manque " + plural(m, "trône", "trônes") + " pour jouer (" + MapPlacer.MIN_SEATS
				+ " au moins, un par joueur)."));
		}
		if (houses == 0) {
			out.add(new Line(false, "Aucune maison : la nuit, chacun reste sur son trône."));
		} else if (houses < seats) {
			int alone = seats - houses;
			out.add(new Line(false, plural(alone, "trône n'a", "trônes n'ont") + " pas de maison : certains joueurs dormiront à "
				+ "plusieurs."));
		}
		if (!room) {
			out.add(new Line(false, "Pas de salle de convocation : le Conteur remettra les rôles sur place, en vocal privé."));
		}
		return out;
	}

	/** En une phrase : « Jouable. » ou « Pas encore jouable : il manque 3 trônes (5 au moins). » */
	public String verdict() {
		if (playable()) {
			return "Jouable.";
		}
		return "Pas encore jouable : il manque " + plural(missingSeats(), "trône", "trônes") + " (" + MapPlacer.MIN_SEATS + " au moins).";
	}
}
