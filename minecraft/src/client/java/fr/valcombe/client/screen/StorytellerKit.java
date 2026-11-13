package fr.valcombe.client.screen;

import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdFonts;
import fr.valcombe.client.hd.HdSprites;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;

import java.util.Locale;

import net.minecraft.resources.Identifier;

/**
 * Pièces communes aux écrans du Conteur passés en haute résolution (pupitre, fiche d'un siège, script, durées,
 * ordre de réveil, distribution, scénarios) : mesures de texte en unités d'interface pour placer les widgets dans
 * build(), bouton fléché dessiné, ascenseur des listes de boutons qui défilent à la molette.
 */
final class StorytellerKit {
	private StorytellerKit() {
	}

	// ------------------------------------------------------------------
	// Mesures en unités d'interface (build() place les widgets avant tout dessin)
	// ------------------------------------------------------------------
	/** Hauteur, en unités, d'un paragraphe coupé à widthUnits, borné à maxLines lignes. */
	static int textHeight(HdStyle style, String text, int widthUnits, int maxLines) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		int s = Hd.guiScale();
		int lines = Math.min(maxLines, HdFonts.wrap(style, s, text, widthUnits * s).size());
		return (int) Math.ceil(lines * HdFonts.lineHeight(style, s) / (double) s);
	}

	/** Hauteur d'une ligne, en unités. */
	static int lineHeight(HdStyle style) {
		int s = Hd.guiScale();
		return (int) Math.ceil(HdFonts.lineHeight(style, s) / (double) s);
	}

	/** Largeur d'une ligne, en unités. */
	static int textWidth(HdStyle style, String text) {
		int s = Hd.guiScale();
		return (int) Math.ceil(HdFonts.width(style, s, text) / (double) s);
	}

	/** Pictogramme d'un rôle (identifiant d'échange, nom intégré ou emplacement créé). */
	static Identifier roleIcon(String roleId) {
		Role r = Role.parse(roleId);
		return Theme.tex("roles/" + (r != null ? r.icon : roleId.toLowerCase(Locale.ROOT)));
	}

	// ------------------------------------------------------------------
	// Dessin
	// ------------------------------------------------------------------
	/** Triangle pointe en bas, de base w et de hauteur h, base en haut à (cx, y) ; pixels réels. */
	static void triangleDown(Hd hd, int cx, int y, int w, int h, int color) {
		for (int row = 0; row < h; row++) {
			int half = (int) Math.round((h - row - 0.5) * w / (2.0 * h));
			if (half > 0) {
				hd.fill(cx - half, y + row, cx + half, y + row + 1, color);
			}
		}
	}

	/**
	 * Ascenseur d'une liste de boutons qui défile ligne à ligne (molette) : rail sur toute la hauteur, curseur à la
	 * place de la fenêtre visible. x, y, h en unités d'interface ; 4 unités de large.
	 */
	static void rail(Hd hd, int x, int y, int h, int first, int visible, int total) {
		if (total <= visible || h <= 0) {
			return;
		}
		int rx = hd.px(x);
		int ry = hd.px(y);
		int rh = hd.px(h);
		int rw = hd.u(4);
		hd.sprite(HdSprites.ASCENSEUR_RAIL, rx, ry, rw, rh);
		int th = Math.max(hd.u(8), rh * visible / total);
		int ty = ry + (int) Math.round((rh - th) * first / (double) Math.max(1, total - visible));
		hd.sprite(HdSprites.ASCENSEUR, rx, ty, rw, th);
	}

	/** Ligne d'un tableau : un fond très léger une ligne sur deux, pour suivre la ligne de l'œil. */
	static void band(Hd hd, int x, int y, int w, int h, int index) {
		if (index % 2 == 0) {
			hd.fill(hd.px(x), hd.px(y), hd.px(x + w), hd.px(y + h), 0x161B1214);
		}
	}

	/**
	 * Bouton à flèche (monter, descendre) : le triangle est dessiné, net à toutes les échelles, au lieu d'un
	 * caractère absent des polices. Son libellé reste « ▲ » ou « ▼ » pour la narration et les tests.
	 */
	static final class ArrowButton extends HdButton {
		private final boolean up;

		ArrowButton(int x, int y, int w, int h, boolean up, Runnable action) {
			super(x, y, w, h, up ? "▲" : "▼", action);
			this.up = up;
			tooltip(up ? "Réveiller plus tôt" : "Réveiller plus tard");
		}

		@Override
		protected void drawFace(Hd hd, int x, int y, int w, int faceH) {
			int tw = hd.u(8);
			int th = hd.u(5);
			int cx = x + w / 2;
			int ty = y + (faceH - th) / 2;
			int color = active ? Hd.LIGHT : Hd.MUTED;
			int d = Math.max(1, hd.u(0.5));
			if (up) {
				hd.triangle(cx + d, ty + d, tw, th, 0x90000000);
				hd.triangle(cx, ty, tw, th, color);
			} else {
				triangleDown(hd, cx + d, ty + d, tw, th, 0x90000000);
				triangleDown(hd, cx, ty, tw, th, color);
			}
		}
	}
}
