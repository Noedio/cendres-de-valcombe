package fr.valcombe.client.screen.atelier;

import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdGlyphs;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTokenArt;

import java.util.List;

/**
 * Ce que l'Atelier en jeu peint lui-même, en pixels réels, sans texture : les illustrations des entrées de l'accueil,
 * les pastilles d'état (« Jouable », « En atelier »), les étapes d'un parcours guidé. Partagé par toutes ses pages.
 */
public final class AtelierArt {
	/** Vert d'une réussite, rouge d'un refus ou d'un manque, sur parchemin. */
	public static final int GOOD = 0xFF2F6B2A;
	public static final int BAD = 0xFF9A2830;
	/** Bronze sombre d'une mention neutre. */
	public static final int NEUTRAL = 0xFF6E4F22;

	private AtelierArt() {
	}

	// ------------------------------------------------------------------
	// Pastilles
	// ------------------------------------------------------------------

	/** Hauteur d'une pastille de ce style (pixels réels). */
	public static int chipHeight(Hd hd, HdStyle st) {
		return hd.lineHeight(st) + hd.u(1);
	}

	/** Largeur qu'aurait une pastille (pixels réels). */
	public static int chipWidth(Hd hd, HdStyle st, String text) {
		return hd.width(st, text) + hd.u(7);
	}

	/** Une pastille arrondie : texte clair sur fond de couleur ; renvoie sa largeur (pixels réels). */
	public static int chip(Hd hd, HdStyle st, String text, int x, int y, int bg) {
		int w = chipWidth(hd, st, text);
		int h = chipHeight(hd, st);
		int r = h / 2;
		hd.fill(x + r, y, x + w - r, y + h, bg);
		hd.disc(x + r, y + r, r, bg);
		hd.disc(x + w - r, y + r, r, bg);
		hd.textMiddle(st, text, x + hd.u(3.5), y, h, Hd.LIGHT);
		return w;
	}

	/** Une pastille dans un coin d'image (bandeau sombre translucide), alignée à droite sur right. */
	public static void ribbon(Hd hd, String text, int right, int y, int bg) {
		HdStyle st = HdStyle.PETIT.size(8);
		int w = chipWidth(hd, st, text);
		chip(hd, st, text, right - w, y, bg);
	}

	// ------------------------------------------------------------------
	// Étapes d'un parcours guidé
	// ------------------------------------------------------------------

	/**
	 * Les étapes d'un parcours, sur une ligne : un disque numéroté par étape (coché si elle est faite, doré pour celle
	 * en cours), son nom, un filet entre deux étapes. x, y, w : la ligne, en pixels réels ; renvoie sa hauteur.
	 */
	public static int steps(Hd hd, List<String> names, int current, int x, int y, int w) {
		HdStyle st = HdStyle.FORT.size(10);
		int d = hd.u(13);
		int n = names.size();
		int slot = w / Math.max(1, n);
		for (int i = 0; i < n; i++) {
			int sx = x + i * slot;
			int cy = y + d / 2;
			boolean done = i < current;
			boolean here = i == current;
			if (i > 0) {
				int lx0 = sx - slot + d + hd.u(4) + Math.min(slot - d - hd.u(12), hd.width(st, names.get(i - 1)) + hd.u(6));
				int lx1 = sx - hd.u(4);
				if (lx1 > lx0) {
					hd.fill(lx0, cy, lx1, cy + Math.max(1, hd.u(0.75)), done || here ? 0xFFB08A3E : 0x60301E14);
				}
			}
			int bg = here ? 0xFFD9B45A : done ? GOOD : 0x40301E14;
			hd.disc(sx + d / 2, cy, d / 2, here ? 0xFF6E4F22 : bg);
			hd.disc(sx + d / 2, cy, d / 2 - Math.max(1, hd.u(1)), bg);
			if (done) {
				int s = hd.u(6);
				HdGlyphs.check(hd, sx + (d - s) / 2, cy - s / 2, s, Hd.LIGHT);
			} else {
				String num = String.valueOf(i + 1);
				HdStyle ns = HdStyle.FORT.size(9);
				hd.textCentered(ns, num, sx + d / 2, cy - hd.lineHeight(ns) / 2, here ? 0xFF3A2410 : Hd.INK_SOFT);
			}
			String name = hd.ellipsize(st, names.get(i), slot - d - hd.u(10));
			hd.textMiddle(st, name, sx + d + hd.u(4), y, d, here ? Hd.WINE : done ? Hd.INK : Hd.INK_SOFT);
		}
		return d;
	}

	// ------------------------------------------------------------------
	// Illustrations des entrées de l'accueil
	// ------------------------------------------------------------------

	/** Un parchemin de carte au trésor : bord brûlé, chemins en pointillé, rose des vents (quand la bibliothèque est vide). */
	public static void mapScroll(Hd hd, int x, int y, int w, int h) {
		hd.gradient(x, y, x + w, y + h, 0xFFE8D6AE, 0xFFD2B47E);
		hd.frame(x, y, w, h, Math.max(1, hd.u(1.5)), 0x705A3A1A);
		// Une rivière, des bosquets, des chemins.
		int steps = 24;
		for (int i = 0; i < steps; i++) {
			double t = i / (double) steps;
			int rx = x + (int) (w * t);
			int ry = y + h / 3 + (int) (Math.sin(t * Math.PI * 2.2) * h / 8);
			hd.disc(rx, ry, Math.max(1, hd.u(1.6)), 0xC06A8FA8);
		}
		for (int i = 0; i < 7; i++) {
			int tx = x + w / 8 + (i * 37 % 7) * w / 9;
			int ty = y + h / 2 + (i * 53 % 5) * h / 12;
			hd.disc(tx, ty, Math.max(2, hd.u(2.4)), 0xC0537A3A);
		}
		for (int i = 0; i < 16; i += 2) {
			int dx = x + w / 6 + i * w / 26;
			int dy = y + h * 3 / 4 - i * h / 60;
			hd.fill(dx, dy, dx + hd.u(2), dy + Math.max(1, hd.u(0.8)), 0xB07A3A2A);
		}
		// La croix du village et la rose des vents.
		int cx = x + w * 2 / 3;
		int cy = y + h * 2 / 3;
		int s = hd.u(5);
		HdGlyphs.cross(hd, cx - s / 2, cy - s / 2, s, 0xFF8A1E24, 1f);
		compass(hd, x + w - hd.u(14), y + hd.u(14), hd.u(9));
	}

	/** Rose des vents de rayon r (pixels réels). */
	public static void compass(Hd hd, int cx, int cy, int r) {
		hd.disc(cx, cy, r, 0x40301E14);
		int t = Math.max(1, hd.u(0.8));
		HdGlyphs.line(hd, cx, cy - r, cx, cy + r, t, 0xFF5A3A1A);
		HdGlyphs.line(hd, cx - r, cy, cx + r, cy, t, 0xFF5A3A1A);
		hd.triangle(cx, cy - r - hd.u(1), hd.u(4), hd.u(5), 0xFF8A1E24);
	}

	/** Une maison à colombages et sa tour, sur une parcelle quadrillée (entrée Structures). */
	public static void structures(Hd hd, int x, int y, int w, int h, boolean hover) {
		hd.gradient(x, y, x + w, y + h, 0xFF3E4F6E, 0xFFCF9A6A);
		int ground = y + h * 3 / 4;
		hd.fill(x, ground, x + w, y + h, 0xFF4B5E34);
		// Les parcelles de l'atelier structures : un quadrillage clair au sol.
		int cell = Math.max(hd.u(6), w / 10);
		for (int gx = x; gx < x + w; gx += cell) {
			hd.fill(gx, ground, gx + 1, y + h, 0x30F2E6C9);
		}
		hd.fill(x, ground + (y + h - ground) / 2, x + w, ground + (y + h - ground) / 2 + 1, 0x30F2E6C9);
		int hw = Math.min(w * 2 / 5, h * 3 / 5);
		int hh = hw * 3 / 4;
		int hx = x + w / 2 - hw / 2 - hw / 6;
		int hy = ground - hh;
		// La maison : murs crème, poutres sombres, toit lie-de-vin, porte et fenêtres.
		hd.fill(hx, hy, hx + hw, ground, 0xFFE9DCC0);
		int beam = Math.max(1, hd.u(1.2));
		hd.frame(hx, hy, hw, hh, beam, 0xFF4A2E1C);
		hd.fill(hx + hw / 2 - beam / 2, hy, hx + hw / 2 + beam / 2 + 1, ground, 0xFF4A2E1C);
		HdGlyphs.line(hd, hx, hy, hx + hw / 2.0, ground, beam, 0xFF4A2E1C);
		HdGlyphs.line(hd, hx + hw, hy, hx + hw / 2.0, ground, beam, 0xFF4A2E1C);
		hd.triangle(hx + hw / 2, hy - hh * 2 / 3, hw + hd.u(4), hh * 2 / 3, hover ? 0xFF8A2A34 : 0xFF6E1E28);
		int dw = Math.max(2, hw / 6);
		hd.fill(hx + hw / 4 - dw / 2, ground - hh / 2, hx + hw / 4 + dw / 2, ground, 0xFF3A2416);
		hd.fill(hx + hw * 3 / 4 - dw / 2, hy + hh / 4, hx + hw * 3 / 4 + dw / 2, hy + hh / 4 + dw, 0xFFF2C14E);
		// La tour, à droite.
		int tw = Math.max(3, hw / 3);
		int tx = hx + hw + hw / 6;
		int th = hh + hh / 2;
		hd.fill(tx, ground - th, tx + tw, ground, 0xFFA89A88);
		for (int i = 0; i < 3; i++) {
			int cw = tw / 5;
			hd.fill(tx + i * 2 * cw, ground - th - cw, tx + i * 2 * cw + cw, ground - th, 0xFFA89A88);
		}
		hd.fill(tx + tw / 2 - 1, ground - th * 2 / 3, tx + tw / 2 + 1, ground - th * 2 / 3 + tw / 3, 0xFF2A1E14);
	}

	/** Trois jetons de rôle en éventail sur un velours lie-de-vin (entrée Rôles et scénarios). */
	public static void roles(Hd hd, int x, int y, int w, int h, boolean hover) {
		hd.gradient(x, y, x + w, y + h, 0xFF3A1420, 0xFF1C0A10);
		// Quelques étoiles dorées.
		for (int i = 0; i < 10; i++) {
			int sx = x + (i * 97 % 89) * w / 89;
			int sy = y + (i * 61 % 47) * h / 47;
			hd.fill(sx, sy, sx + Math.max(1, hd.u(0.8)), sy + Math.max(1, hd.u(0.8)), 0x90F2C14E);
		}
		int size = Math.min(h * 11 / 20, w * 3 / 10);
		int cy = y + (h - size) / 2;
		int cx = x + w / 2;
		int spread = size * 3 / 5 + (hover ? hd.u(3) : 0);
		HdTokenArt.draw(hd, cx - size / 2 - spread, cy + size / 8, size, "astrologue", "GOOD", true, false, false);
		HdTokenArt.draw(hd, cx - size / 2 + spread, cy + size / 8, size, "goule", "EVIL", true, false, false);
		HdTokenArt.draw(hd, cx - size / 2, cy - size / 10, size, "templier", "GOOD", true, false, hover);
	}

	/** Le cadre d'une illustration : filet sombre, coins ombrés. */
	public static void frameArt(Hd hd, int x, int y, int w, int h) {
		hd.frame(x, y, w, h, Math.max(1, hd.u(0.75)), 0xFF2A1E14);
		hd.gradient(x, y + h - hd.u(10), x + w, y + h, 0x00000000, 0x50000000);
	}
}
