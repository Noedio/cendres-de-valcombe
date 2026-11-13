package fr.valcombe.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Géométrie du grimoire du pupitre (Grimoire.ring, Grimoire.spread) : sièges également espacés, jetons qui ne se
 * touchent pas, jetons et noms qui restent dans la zone, de 5 à 20 joueurs, dans la plus petite interface prévue
 * (427 x 240) comme dans une grande.
 */
class GrimoireRingTest {
	/** Zone du grimoire dans le pupitre à 427 x 240 (unités), plaque de nom de 12 unités. */
	private static final int X = 20;
	private static final int Y = 82;
	private static final int W = 227;
	private static final int H = 130;
	private static final int LABEL_H = 12;

	private static double chord(Grimoire.Ring r, int i, int j) {
		return Math.hypot(r.x(i) - r.x(j), r.y(i) - r.y(j));
	}

	/** Longueur de l'arc de l'ellipse (rx, ry) entre deux angles. */
	private static double arc(double rx, double ry, double a0, double a1) {
		double len = 0;
		int steps = 400;
		for (int k = 0; k < steps; k++) {
			double t0 = a0 + (a1 - a0) * k / steps;
			double t1 = a0 + (a1 - a0) * (k + 1) / steps;
			len += Math.hypot(rx * (Math.cos(t1) - Math.cos(t0)), ry * (Math.sin(t1) - Math.sin(t0)));
		}
		return len;
	}

	/** Plus petit écart entre deux sièges voisins placés à ces angles. */
	private static double minGap(double rx, double ry, double[] a) {
		double min = Double.MAX_VALUE;
		for (int i = 0; i < a.length; i++) {
			int j = (i + 1) % a.length;
			min = Math.min(min, Math.hypot(rx * (Math.cos(a[i]) - Math.cos(a[j])), ry * (Math.sin(a[i]) - Math.sin(a[j]))));
		}
		return min;
	}

	@Test
	void siegesEgalementEspacesLeLongDuBord() {
		for (int n = 5; n <= 20; n++) {
			double[] a = Grimoire.spread(n, 90, 45);
			assertEquals(n, a.length);
			assertEquals(-Math.PI / 2, a[0], 1e-9, "le premier siège est en haut");
			double min = Double.MAX_VALUE;
			double max = 0;
			for (int i = 0; i < n; i++) {
				double end = i + 1 < n ? a[i + 1] : a[0] + 2 * Math.PI;
				assertTrue(end > a[i], "sens horaire, sans retour en arrière");
				double len = arc(90, 45, a[i], end);
				min = Math.min(min, len);
				max = Math.max(max, len);
			}
			assertTrue(max / min < 1.02, n + " sièges : arcs de " + min + " à " + max);
			Grimoire.Ring r = new Grimoire.Ring(0, 0, 90, 45, 20, 70, 40, a);
			assertTrue(r.x(n / 4) > 0, "le deuxième quart est à droite (sens horaire à l'écran)");
			// Le but : à pas d'angle égal, les sièges des côtés se serreraient davantage.
			double[] even = new double[n];
			for (int i = 0; i < n; i++) {
				even[i] = -Math.PI / 2 + 2 * Math.PI * i / n;
			}
			assertTrue(minGap(90, 45, a) > minGap(90, 45, even), n + " sièges : écart minimal plus grand qu'à angle égal");
		}
	}

	@Test
	void jetonsEtNomsDansLaZoneSansSeToucher() {
		for (int widest : new int[] {20, 40, 70}) {
			for (int n = 5; n <= 20; n++) {
				Grimoire.Ring r = Grimoire.ring(n, widest, LABEL_H, X, Y, W, H);
				int half = r.size() / 2;
				for (int i = 0; i < n; i++) {
					String where = n + " joueurs, noms de " + widest + ", siège " + i;
					assertTrue(chord(r, i, (i + 1) % n) >= r.size(), where + " : touche son voisin");
					double c = Math.cos(r.angle(i));
					double s = Math.sin(r.angle(i));
					int left = r.x(i) - half;
					int right = r.x(i) + half;
					int top = r.y(i) - half;
					int bottom = r.y(i) + half;
					if (Math.abs(c) > 0.8) {
						// Nom à côté du jeton, vers l'extérieur : plaque de sideMax + 6 unités, à 3 du jeton.
						int plate = Math.min(widest, r.sideMax()) + 9;
						if (c < 0) {
							left -= plate;
						} else {
							right += plate;
						}
					} else if (s < 0) {
						top -= LABEL_H + 1;
					} else {
						bottom += LABEL_H + 1;
					}
					assertTrue(left >= X && right <= X + W && top >= Y && bottom <= Y + H,
						where + " : sort de la zone (" + left + ", " + top + ", " + right + ", " + bottom + ")");
				}
			}
		}
	}

	@Test
	void jetonsLisiblesALaPlusPetiteEchelle() {
		assertTrue(Grimoire.ring(8, 30, LABEL_H, X, Y, W, H).size() >= 22, "8 joueurs : jetons d'au moins 22 unités");
		assertTrue(Grimoire.ring(12, 40, LABEL_H, X, Y, W, H).size() >= 18, "12 joueurs : jetons d'au moins 18 unités");
		assertTrue(Grimoire.ring(20, 40, LABEL_H, X, Y, W, H).size() >= 14, "20 joueurs : jetons d'au moins 14 unités");
		// Interface plus grande (480 x 270, l'échelle 4 d'un écran 1080p) : la place gagnée grandit les jetons.
		Grimoire.Ring small = Grimoire.ring(12, 40, LABEL_H, X, Y, W, H);
		Grimoire.Ring large = Grimoire.ring(12, 40, LABEL_H, X, Y, 246, 170);
		assertTrue(large.size() > small.size(), "plus de place, jetons plus grands");
	}
}
