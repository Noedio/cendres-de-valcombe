package fr.valcombe.client.hd;

/**
 * Symboles dessinés : les polices de la boîte à outils (Cinzel, Alegreya Sans) n'ont ni coche, ni croix, ni main
 * levée, ni panneau d'alerte. Un tel caractère viendrait de la police de Minecraft, en petit et en escalier ; ici il
 * est dessiné en formes nettes, à la taille du texte qui l'entoure.
 *
 * <p>{@link #text} et {@link #width} écrivent et mesurent une ligne où ces symboles sont remplacés par leur dessin :
 * les écrans gardent leurs libellés tels quels (« ✔ Oui », « ☠ Sur le billot »), pour la narration et pour les tests
 * qui cherchent les boutons par leur libellé, et l'affichage reste net.
 */
public final class HdGlyphs {
	/** Les caractères dessinés ici (absents des polices de la boîte à outils). */
	public static final String DRAWN = "✔✘✋⚠☠⏸•×";

	private HdGlyphs() {
	}

	/** Vrai si ce caractère est dessiné par cette classe. */
	public static boolean drawn(char c) {
		return DRAWN.indexOf(c) >= 0;
	}

	/** Côté du carré où se dessine un symbole, pour un style, en pixels réels (un peu plus haut que les capitales). */
	public static int size(Hd hd, HdStyle style) {
		return Math.max(4, Math.round(style.size() * hd.scale() * 0.78f));
	}

	/** Largeur réservée à un symbole dans une ligne : son carré et une espace fine de chaque côté. */
	private static int advance(Hd hd, HdStyle style) {
		return size(hd, style) + Math.max(1, Math.round(style.size() * hd.scale() * 0.12f));
	}

	/** Largeur d'une ligne en pixels réels, symboles dessinés compris. */
	public static int width(Hd hd, HdStyle style, String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		int w = 0;
		int from = 0;
		for (int i = 0; i < text.length(); i++) {
			if (drawn(text.charAt(i))) {
				w += hd.width(style, text.substring(from, i)) + advance(hd, style);
				from = i + 1;
			}
		}
		return w + hd.width(style, text.substring(from));
	}

	/** Écrit une ligne (haut de la ligne en y), symboles dessinés ; renvoie sa largeur. */
	public static int text(Hd hd, HdStyle style, String text, int x, int y, int color) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		int cx = x;
		int from = 0;
		int s = size(hd, style);
		int mid = y + hd.lineHeight(style) / 2;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (drawn(c)) {
				cx += hd.text(style, text.substring(from, i), cx, y, color);
				int a = advance(hd, style);
				draw(hd, c, cx + (a - s) / 2, mid - s / 2, s, color);
				cx += a;
				from = i + 1;
			}
		}
		cx += hd.text(style, text.substring(from), cx, y, color);
		return cx - x;
	}

	/** Écrit une ligne centrée sur cx. */
	public static void textCentered(Hd hd, HdStyle style, String text, int cx, int y, int color) {
		text(hd, style, text, cx - width(hd, style, text) / 2, y, color);
	}

	/** Écrit une ligne gravée (ombre décalée d'un demi-point) ; renvoie sa largeur. */
	public static int textShadow(Hd hd, HdStyle style, String text, int x, int y, int color, int shadow) {
		int d = Math.max(1, Math.round(hd.scale() * 0.5f));
		text(hd, style, text, x + d, y + d, shadow);
		return text(hd, style, text, x, y, color);
	}

	/** Le texte raccourci par « … » s'il dépasse maxWidth pixels réels (symboles dessinés compris). */
	public static String ellipsize(Hd hd, HdStyle style, String text, int maxWidth) {
		if (width(hd, style, text) <= maxWidth) {
			return text;
		}
		String t = text;
		while (t.length() > 1 && width(hd, style, t.stripTrailing() + "…") > maxWidth) {
			t = t.substring(0, t.length() - 1);
		}
		return t.stripTrailing() + "…";
	}

	/** Dessine un symbole dans le carré (x, y, s) en pixels réels. Un caractère inconnu ne dessine rien. */
	public static void draw(Hd hd, char c, int x, int y, int s, int color) {
		switch (c) {
			case '✔' -> check(hd, x, y, s, color);
			case '✘', '×' -> cross(hd, x, y, s, color, c == '×' ? 0.62f : 0.82f);
			case '✋' -> hand(hd, x, y, s, color);
			case '⚠' -> warning(hd, x, y, s, color);
			case '☠' -> skull(hd, x, y, s, color);
			case '⏸' -> pause(hd, x, y, s, color);
			case '•' -> hd.disc(x + s / 2, y + s / 2, Math.max(1, s / 5), color);
			default -> {
			}
		}
	}

	// ------------------------------------------------------------------
	// Formes (pixels réels)
	// ------------------------------------------------------------------
	/** Trait épais de (x0, y0) à (x1, y1) : une suite de carrés de côté t le long du segment. */
	public static void line(Hd hd, double x0, double y0, double x1, double y1, int t, int color) {
		double len = Math.hypot(x1 - x0, y1 - y0);
		int steps = Math.max(1, (int) Math.ceil(len));
		double h = t / 2.0;
		for (int i = 0; i <= steps; i++) {
			double x = x0 + (x1 - x0) * i / steps;
			double y = y0 + (y1 - y0) * i / steps;
			int l = (int) Math.round(x - h);
			int tp = (int) Math.round(y - h);
			hd.fill(l, tp, l + t, tp + t, color);
		}
	}

	private static int stroke(int s) {
		return Math.max(1, Math.round(s * 0.17f));
	}

	/** Coche : petit jambage descendant puis grand trait montant. */
	public static void check(Hd hd, int x, int y, int s, int color) {
		int t = stroke(s);
		line(hd, x + s * 0.12, y + s * 0.55, x + s * 0.4, y + s * 0.82, t, color);
		line(hd, x + s * 0.4, y + s * 0.82, x + s * 0.9, y + s * 0.18, t, color);
	}

	/** Croix en sautoir ; span : part du carré couverte par les branches. */
	public static void cross(Hd hd, int x, int y, int s, int color, float span) {
		int t = stroke(s);
		double m = s * (1 - span) / 2;
		line(hd, x + m, y + m, x + s - m, y + s - m, t, color);
		line(hd, x + s - m, y + m, x + m, y + s - m, t, color);
	}

	/** Main levée : paume, quatre doigts et pouce écarté. */
	public static void hand(Hd hd, int x, int y, int s, int color) {
		int palmTop = y + Math.round(s * 0.48f);
		int left = x + Math.round(s * 0.24f);
		int right = x + Math.round(s * 0.86f);
		hd.fill(left, palmTop, right, y + s, color);
		int fingers = 4;
		double fw = (right - left) / (double) fingers;
		int gap = Math.max(1, (int) Math.round(fw * 0.22));
		int[] tops = {Math.round(s * 0.16f), Math.round(s * 0.02f), Math.round(s * 0.06f), Math.round(s * 0.2f)};
		for (int i = 0; i < fingers; i++) {
			int fx0 = left + (int) Math.round(i * fw) + (i == 0 ? 0 : gap / 2);
			int fx1 = left + (int) Math.round((i + 1) * fw) - (i == fingers - 1 ? 0 : gap - gap / 2);
			hd.fill(fx0, y + tops[i], fx1, palmTop + 1, color);
		}
		// pouce, vers le bas et la gauche
		line(hd, left + s * 0.05, palmTop + s * 0.12, x + s * 0.06, y + s * 0.38, Math.max(1, (int) Math.round(fw * 0.85)), color);
	}

	/** Couleur qui tranche sur color : encre sur une teinte claire, parchemin sur une teinte sombre. */
	private static int contrast(int color) {
		int r = color >> 16 & 0xFF;
		int g = color >> 8 & 0xFF;
		int b = color & 0xFF;
		return 0.299 * r + 0.587 * g + 0.114 * b > 140 ? 0xFF1B1214 : 0xFFF2E6C9;
	}

	/** Panneau d'alerte : triangle plein, point d'exclamation d'une couleur qui tranche. */
	public static void warning(Hd hd, int x, int y, int s, int color) {
		hd.triangle(x + s / 2, y, s, s, color);
		int mark = contrast(color);
		int bw = Math.max(1, Math.round(s * 0.13f));
		int cx = x + s / 2;
		hd.fill(cx - bw / 2, y + Math.round(s * 0.34f), cx - bw / 2 + bw, y + Math.round(s * 0.7f), mark);
		hd.fill(cx - bw / 2, y + Math.round(s * 0.78f), cx - bw / 2 + bw, y + Math.round(s * 0.78f) + bw, mark);
	}

	/** Crâne : boîte crânienne ronde, mâchoire, orbites et dents d'une couleur qui tranche. */
	public static void skull(Hd hd, int x, int y, int s, int color) {
		int r = Math.round(s * 0.42f);
		int cx = x + s / 2;
		int cy = y + Math.round(s * 0.44f);
		int eye = Math.max(1, Math.round(s * 0.16f));
		int ey = cy - eye / 4;
		hd.disc(cx, cy, r, color);
		hd.fill(cx - Math.round(s * 0.24f), cy + r - Math.round(s * 0.1f), cx + Math.round(s * 0.24f), y + s, color);
		int shade = contrast(color);
		hd.fill(cx - Math.round(s * 0.24f), ey, cx - Math.round(s * 0.24f) + eye, ey + eye, shade);
		hd.fill(cx + Math.round(s * 0.24f) - eye, ey, cx + Math.round(s * 0.24f), ey + eye, shade);
		int tooth = Math.max(1, Math.round(s * 0.06f));
		for (int i = -1; i <= 1; i += 2) {
			hd.fill(cx + i * Math.round(s * 0.08f) - tooth / 2, y + s - Math.round(s * 0.14f), cx + i * Math.round(s * 0.08f) - tooth / 2 + tooth,
				y + s, shade);
		}
	}

	/** Pause : deux barres. */
	public static void pause(Hd hd, int x, int y, int s, int color) {
		int bw = Math.max(1, Math.round(s * 0.28f));
		int top = y + Math.round(s * 0.08f);
		int bottom = y + s - Math.round(s * 0.08f);
		hd.fill(x + Math.round(s * 0.14f), top, x + Math.round(s * 0.14f) + bw, bottom, color);
		hd.fill(x + s - Math.round(s * 0.14f) - bw, top, x + s - Math.round(s * 0.14f), bottom, color);
	}
}
