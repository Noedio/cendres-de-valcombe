package fr.valcombe.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.client.BuildSelection.Layout;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Les plaques de la sélection 3D ne se chevauchent jamais à l'écran : rectangles vus du joueur, et masquage des moins
 * importantes (le cartouche d'abord, puis les coins, puis les côtés mesurés). Calcul pur, sans Minecraft.
 */
class BuildSelectionTest {
	/** Un joueur qui regarde vers le nord (z négatif), à hauteur d'œil y. */
	private static final double[] RIGHT = {1, 0, 0};
	private static final double[] UP = {0, 1, 0};
	private static final double[] NORTH = {0, 0, -1};

	/** Les plaques d'une boîte de 8 × 6 × 12 dont le coin bas est en (3, 65, 3), vues de cet œil, dans l'ordre d'importance. */
	private static List<double[]> plates(double[] eye) {
		double d = Math.hypot(Math.hypot(eye[0] - 7, eye[1] - 68), eye[2] - 9);
		double s = Math.max(0.4, Math.min(40, d * 0.085));
		double big = Math.max(0.6, Math.min(40, d * 0.085)) * 1.15;
		List<double[]> out = new ArrayList<>();
		// Cartouche : deux lignes, environ 90 points de large.
		out.add(Layout.rect(eye, RIGHT, UP, NORTH, new double[] {7, 71.6 + 10 * big / 16, 9}, 50 * big / 16, 12 * big / 16, 11 * big / 16));
		// Coins 1 et 2.
		out.add(Layout.rect(eye, RIGHT, UP, NORTH, new double[] {3.5, 66.6, 3.5}, 6 * s / 16, 7 * s / 16, 6 * s / 16));
		out.add(Layout.rect(eye, RIGHT, UP, NORTH, new double[] {10.5, 71.6, 14.5}, 6 * s / 16, 7 * s / 16, 6 * s / 16));
		// Côtés L, l, h, contre le coin bas.
		out.add(Layout.rect(eye, RIGHT, UP, NORTH, new double[] {7, 65.4, 2.55}, 12 * s / 16, 7 * s / 16, 6 * s / 16));
		out.add(Layout.rect(eye, RIGHT, UP, NORTH, new double[] {2.55, 65.4, 9}, 12 * s / 16, 7 * s / 16, 6 * s / 16));
		out.add(Layout.rect(eye, RIGHT, UP, NORTH, new double[] {2.55, 68, 2.55}, 12 * s / 16, 7 * s / 16, 6 * s / 16));
		return out;
	}

	private static void noOverlap(List<double[]> rects, boolean[] shown, String where) {
		assertTrue(shown[0], where + " : le cartouche est toujours montré");
		for (int i = 0; i < rects.size(); i++) {
			for (int j = i + 1; j < rects.size(); j++) {
				assertTrue(!(shown[i] && shown[j] && Layout.near(rects.get(i), rects.get(j), Layout.GAP - 1e-9)),
					where + " : les plaques " + i + " et " + j + " se touchent");
			}
		}
	}

	@Test
	void unRectangleVuDuJoueur() {
		// Une plaque de 1 × 0,5 bloc, à 10 blocs droit devant : 0,1 de large en tangente, centrée.
		double[] r = Layout.rect(new double[] {0, 0, 0}, RIGHT, UP, NORTH, new double[] {0, 0, -10}, 0.5, 0.25, 0.25);
		assertArrayEquals(new double[] {-0.05, -0.025, 0.05, 0.025}, r, 1e-9);
		// Derrière le joueur : rien.
		assertNull(Layout.rect(new double[] {0, 0, 0}, RIGHT, UP, NORTH, new double[] {0, 0, 10}, 0.5, 0.25, 0.25));
	}

	@Test
	void lesPlaquesNeSeChevauchentJamaisDePresCommeDeLoin() {
		for (double dist : new double[] {6, 12, 20, 30, 45, 60, 90}) {
			for (double height : new double[] {66, 75, 90}) {
				double[] eye = {7, height, 9 + dist};
				List<double[]> rects = plates(eye);
				boolean[] shown = Layout.visible(rects, Layout.GAP);
				noOverlap(rects, shown, "à " + dist + " blocs, œil à " + height);
			}
		}
	}

	@Test
	void deLoinLesCotesSeTaisentDePresIlsSeLisent() {
		boolean[] near = Layout.visible(plates(new double[] {7, 69, 9 + 14}), Layout.GAP);
		boolean[] far = Layout.visible(plates(new double[] {7, 90, 9 + 60}), Layout.GAP);
		int nearShown = 0;
		int farShown = 0;
		for (int i = 0; i < near.length; i++) {
			nearShown += near[i] ? 1 : 0;
			farShown += far[i] ? 1 : 0;
		}
		assertTrue(nearShown > farShown, "de près, plus de plaques se lisent : " + nearShown + " contre " + farShown);
		assertTrue(far[0], "de loin, le cartouche (qui dit les trois côtés) reste");
	}

	@Test
	void ordreDImportance() {
		// Trois plaques au même endroit : seule la première est montrée ; une quatrième à l'écart l'est aussi.
		double[] same = {0, 0, 1, 1};
		boolean[] v = Layout.visible(List.of(same, same, same, new double[] {2, 2, 3, 3}), 0.01);
		assertArrayEquals(new boolean[] {true, false, false, true}, v);
		assertEquals(1, Layout.visible(List.of(new double[] {0, 0, 1, 1}), 0).length);
	}
}
