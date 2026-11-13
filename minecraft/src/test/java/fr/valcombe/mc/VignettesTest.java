package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Les vignettes de l'Atelier en jeu : toujours 512 × 512 pixels (une petite carte agrandie en blocs nets, une grande
 * réduite), et les repères dessinés à leur place : trônes dorés, maisons vertes, salle violette.
 */
class VignettesTest {
	/** Une vue de dessus unie, de side × side blocs (un pixel par bloc). */
	private static BufferedImage plain(int side, int rgb) {
		BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < side; y++) {
			for (int x = 0; x < side; x++) {
				img.setRGB(x, y, rgb);
			}
		}
		return img;
	}

	private static int[] rgb(BufferedImage img, double x, double y) {
		int c = img.getRGB((int) Math.round(x), (int) Math.round(y));
		return new int[] {c >> 16 & 255, c >> 8 & 255, c & 255};
	}

	@Test
	void aSmallMapIsEnlargedAndItsMarkersArePainted() {
		int r = 30;
		int side = 2 * r + 1;
		BufferedImage out = Vignettes.compose(plain(side, 0x406020), List.of(new int[] {10, 1, 0}),
			List.of(new int[] {-12, 1, 6}), new int[] {0, 1, -20}, r);
		assertEquals(Vignettes.SIZE, out.getWidth());
		assertEquals(Vignettes.SIZE, out.getHeight());
		double cell = Vignettes.SIZE / (double) side;
		double c = Vignettes.SIZE / 2.0;
		int[] seat = rgb(out, c + 10.5 * cell, c + 0.5 * cell);
		assertTrue(seat[0] > 200 && seat[1] > 150 && seat[2] < 140, "trône doré : " + seat[0] + "," + seat[1] + "," + seat[2]);
		int[] house = rgb(out, c - 11.5 * cell, c + 6.5 * cell);
		assertTrue(house[1] > house[0] && house[1] > 140, "maison verte : " + house[0] + "," + house[1] + "," + house[2]);
		int[] room = rgb(out, c + 0.5 * cell, c - 19.5 * cell);
		assertTrue(room[2] > 180 && room[0] > 150 && room[1] < 170, "salle violette : " + room[0] + "," + room[1] + "," + room[2]);
		int[] ground = rgb(out, 5, 5);
		assertTrue(ground[1] > ground[0] && ground[1] < 0x60, "le sol garde sa couleur (sous un voile léger)");
	}

	@Test
	void aStructureIsSeenFromThreeQuartersWithLitFaces() {
		// Un seul bloc gris : son dessus au milieu en haut, sa face sud à gauche, sa face est à droite, chacune plus sombre.
		BufferedImage out = Vignettes.isometric(1, 1, 1, new int[] {0x808080});
		assertEquals(Vignettes.SIZE, out.getWidth());
		assertEquals(Vignettes.SIZE, out.getHeight());
		int top = rgb(out, 256, 146)[0];
		int south = rgb(out, 146, 311)[0];
		int east = rgb(out, 366, 311)[0];
		assertEquals(Math.round(0x80 * Vignettes.TOP), top, 2.0, "dessus en plein jour");
		assertEquals(Math.round(0x80 * Vignettes.SOUTH), south, 2.0, "face sud");
		assertEquals(Math.round(0x80 * Vignettes.EAST), east, 2.0, "face est, dans l'ombre");
		assertTrue(top > south && south > east);
	}

	@Test
	void hiddenBlocksAndEmptyCellsAreSkipped() {
		// Une colonne de deux blocs : le dessus du bloc du bas est caché par celui du haut, donc jamais peint en rouge.
		int[] column = {0xFF0000, 0x00FF00}; // y = 0 rouge, y = 1 vert
		BufferedImage out = Vignettes.isometric(1, 2, 1, column);
		boolean redTopSeen = false;
		for (int x = 0; x < Vignettes.SIZE; x += 4) {
			for (int y = 0; y < Vignettes.SIZE; y += 4) {
				int[] c = rgb(out, x, y);
				redTopSeen |= c[0] == 0xFF && c[1] == 0 && c[2] == 0;
			}
		}
		assertTrue(!redTopSeen, "le dessus du bloc du bas n'est pas peint (seules ses faces sud et est, plus sombres)");
		// Rien que du vide : la scène seule, sans erreur.
		BufferedImage none = Vignettes.isometric(2, 1, 2, new int[] {-1, -1, -1, -1});
		int[] mid = rgb(none, 256, 256);
		assertTrue(mid[0] < 0x60 && mid[1] < 0x60 && mid[2] < 0x60, "le fond sombre de la scène");
	}

	@Test
	void aLargeMapIsReducedToTheSameSize() {
		BufferedImage out = Vignettes.compose(plain(601, 0x808080), List.of(), List.of(), null, 300);
		assertEquals(Vignettes.SIZE, out.getWidth());
		assertEquals(Vignettes.SIZE, out.getHeight());
		BufferedImage small = Vignettes.shrink(plain(600, 0xFF2040), 512);
		int[] px = rgb(small, 100, 100);
		assertEquals(0xFF, px[0]);
		assertEquals(0x20, px[1]);
		assertEquals(0x40, px[2]);
	}
}
