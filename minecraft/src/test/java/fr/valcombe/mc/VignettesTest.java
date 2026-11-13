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
