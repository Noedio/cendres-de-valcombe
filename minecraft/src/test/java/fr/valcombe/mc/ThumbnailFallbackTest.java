package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/** Les vignettes de repli de l'Atelier : images PNG nettes, à la bonne taille, fidèles aux repères et aux proportions. */
class ThumbnailFallbackTest {
	private static BufferedImage read(byte[] png) throws IOException {
		assertNotNull(png);
		assertTrue(png.length > 8 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G', "un PNG");
		BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
		assertNotNull(img);
		assertEquals(ThumbnailFallback.WIDTH, img.getWidth());
		assertEquals(ThumbnailFallback.HEIGHT, img.getHeight());
		return img;
	}

	private static int alpha(BufferedImage img, int x, int y) {
		return img.getRGB(x, y) >>> 24;
	}

	/** Le pixel le plus proche de (x, y) dans un petit carré, de la teinte voulue (trône doré, maison verte). */
	private static boolean near(BufferedImage img, int x, int y, java.util.function.IntPredicate color) {
		for (int dy = -3; dy <= 3; dy++) {
			for (int dx = -3; dx <= 3; dx++) {
				int px = x + dx;
				int py = y + dy;
				if (px >= 0 && py >= 0 && px < img.getWidth() && py < img.getHeight() && color.test(img.getRGB(px, py))) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean gold(int c) {
		int r = c >> 16 & 255;
		int g = c >> 8 & 255;
		int b = c & 255;
		return (c >>> 24) > 200 && r > 190 && g > 150 && g < 200 && b < 120;
	}

	@Test
	void planDUneCarte() throws IOException {
		List<int[]> seats = new ArrayList<>();
		List<int[]> houses = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			double a = 2 * Math.PI * i / 8;
			seats.add(new int[] {(int) Math.round(8 * Math.cos(a)), 1, (int) Math.round(8 * Math.sin(a))});
			houses.add(new int[] {(int) Math.round(18 * Math.cos(a)), 1, (int) Math.round(18 * Math.sin(a))});
		}
		BufferedImage img = read(ThumbnailFallback.plan(seats, houses, new int[] {0, 1, -20}, 40));
		// Fond transparent sur les bords (la page met son propre fond), repères dessinés au milieu.
		assertEquals(0, alpha(img, 2, 2));
		assertEquals(0, alpha(img, img.getWidth() - 3, img.getHeight() - 3));
		// Le plan est cadré sur les repères : le trône le plus à l'est (8, 0) tombe là où on l'attend.
		int reach = 20 + 4;
		double k = (ThumbnailFallback.HEIGHT - 40) / (2.0 * reach + 1);
		int cx = ThumbnailFallback.WIDTH / 2;
		int cy = ThumbnailFallback.HEIGHT / 2;
		assertTrue(near(img, (int) Math.round(cx + 8 * k), cy, ThumbnailFallbackTest::gold), "trône doré à l'est du centre");
		assertTrue(near(img, cx, (int) Math.round(cy - 8 * k), ThumbnailFallbackTest::gold), "trône doré au nord du centre");
	}

	@Test
	void planSansRepere() throws IOException {
		// Une carte toute neuve : la zone et le centre seulement, sans erreur.
		BufferedImage img = read(ThumbnailFallback.plan(List.of(), List.of(), null, 12));
		assertTrue(alpha(img, ThumbnailFallback.WIDTH / 2, ThumbnailFallback.HEIGHT / 2) > 200, "le centre de la place");
	}

	/** Largeur et hauteur de ce qui est dessiné (pixels non transparents, ombre comprise). */
	private static int[] extent(BufferedImage img) {
		int minX = img.getWidth();
		int maxX = -1;
		int minY = img.getHeight();
		int maxY = -1;
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				if (alpha(img, x, y) > 150) {
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
					minY = Math.min(minY, y);
					maxY = Math.max(maxY, y);
				}
			}
		}
		return new int[] {maxX - minX + 1, maxY - minY + 1, minX, minY};
	}

	@Test
	void volumeDUneStructure() throws IOException {
		BufferedImage tower = read(ThumbnailFallback.volume(5, 20, 5, "perso"));
		BufferedImage wall = read(ThumbnailFallback.volume(30, 3, 2, "decor"));
		int[] t = extent(tower);
		int[] w = extent(wall);
		// Une tour est bien plus haute que large ; un long muret, vu en perspective, plus large que haut.
		assertTrue(t[1] > 2 * t[0], "tour : " + t[0] + " × " + t[1]);
		assertTrue(w[0] > 1.3 * w[1], "muret : " + w[0] + " × " + w[1]);
		// Toujours dans le cadre, avec une marge.
		for (int[] e : new int[][] {t, w}) {
			assertTrue(e[2] > 4 && e[3] > 4 && e[2] + e[0] < ThumbnailFallback.WIDTH - 4 && e[3] + e[1] < ThumbnailFallback.HEIGHT - 4,
				"dans le cadre");
		}
		// La teinte suit le rayon du catalogue.
		assertTrue(!ThumbnailFallback.tint("maisons").equals(ThumbnailFallback.tint("decor")));
		// Un volume dégénéré (taille inconnue) reste dessinable.
		read(ThumbnailFallback.volume(0, 0, 0, null));
	}
}
