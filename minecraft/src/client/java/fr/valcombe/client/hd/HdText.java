package fr.valcombe.client.hd;

import java.util.List;

/**
 * Texte serré pour les écrans chargés (journal, ordre de la nuit, affichage en jeu) : un paragraphe dont on choisit
 * l'interligne, au lieu des 1,3 × la taille de Hd.paragraph. Les symboles absents des polices (coche, alerte, main)
 * y sont dessinés par HdGlyphs. Positions et tailles en pixels réels, comme dans tout contexte Hd.
 */
public final class HdText {
	/** Texte courant serré : un peu plus petit que HdStyle.TEXTE, lisible à 11 unités d'interligne. */
	public static final HdStyle SERRE = HdStyle.TEXTE.size(10);
	/** Interligne de SERRE, en unités d'interface. */
	public static final int PAS = 11;

	private HdText() {
	}

	/**
	 * Paragraphe coupé à la largeur, une ligne tous les pitch pixels, au plus maxHeight pixels de haut (la dernière
	 * ligne visible finit par « … » si le texte dépasse). Renvoie la hauteur utilisée.
	 */
	public static int paragraph(Hd hd, HdStyle style, String text, int x, int y, int width, int color, int pitch, int maxHeight) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		List<String> lines = hd.wrap(style, text, width);
		int max = Math.max(1, (maxHeight + pitch - hd.lineHeight(style)) / Math.max(1, pitch));
		if (maxHeight < hd.lineHeight(style)) {
			max = 1;
		}
		int shown = Math.min(max, lines.size());
		for (int i = 0; i < shown; i++) {
			String line = lines.get(i);
			if (i == shown - 1 && lines.size() > shown) {
				line = HdGlyphs.ellipsize(hd, style, line + "…", width);
			}
			HdGlyphs.text(hd, style, line, x, y + i * pitch, color);
		}
		return shown * pitch;
	}

	/** Paragraphe sans limite de hauteur ; renvoie la hauteur utilisée. */
	public static int paragraph(Hd hd, HdStyle style, String text, int x, int y, int width, int color, int pitch) {
		return paragraph(hd, style, text, x, y, width, color, pitch, Integer.MAX_VALUE / 2);
	}

	/** Nombre de lignes d'un paragraphe à cette largeur. */
	public static int lines(Hd hd, HdStyle style, String text, int width) {
		return text == null || text.isEmpty() ? 0 : hd.wrap(style, text, width).size();
	}

	/** Une ligne raccourcie par « … » à la largeur, symboles dessinés ; renvoie sa largeur. */
	public static int line(Hd hd, HdStyle style, String text, int x, int y, int width, int color) {
		return HdGlyphs.text(hd, style, HdGlyphs.ellipsize(hd, style, text, width), x, y, color);
	}
}
