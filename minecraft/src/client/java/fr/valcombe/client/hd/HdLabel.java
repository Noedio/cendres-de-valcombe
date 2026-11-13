package fr.valcombe.client.hd;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;

/**
 * Libellé ou paragraphe posé dans un écran (addRenderableOnly) : une ligne alignée à gauche, au centre ou à
 * droite, ou un paragraphe coupé à la largeur et borné en lignes. Position et largeur en unités d'interface.
 * Pour un texte qui change à chaque image, écrire plutôt directement avec Hd dans drawHd.
 */
public class HdLabel implements Renderable {
	public enum Align {
		LEFT, CENTER, RIGHT
	}

	private final int x;
	private final int y;
	private final int width;
	private String text;
	private HdStyle style = HdStyle.TEXTE;
	private int color = Hd.INK;
	private Align align = Align.LEFT;
	private boolean wrap;
	private int maxLines = Integer.MAX_VALUE;

	/** Un libellé d'une ligne (raccourci par « … » s'il dépasse la largeur). */
	public HdLabel(int x, int y, int width, String text) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.text = text;
	}

	public HdLabel text(String t) {
		this.text = t;
		return this;
	}

	public HdLabel style(HdStyle s) {
		this.style = s;
		return this;
	}

	public HdLabel color(int argb) {
		this.color = argb;
		return this;
	}

	public HdLabel align(Align a) {
		this.align = a;
		return this;
	}

	/** En paragraphe : coupé à la largeur, au plus maxLines lignes (la dernière finit par « … » si besoin). */
	public HdLabel paragraph(int maxLines) {
		this.wrap = true;
		this.maxLines = Math.max(1, maxLines);
		return this;
	}

	public String text() {
		return text;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (text == null || text.isEmpty()) {
			return;
		}
		try (Hd hd = Hd.begin(g)) {
			int px = hd.px(x);
			int py = hd.px(y);
			int w = hd.px(width);
			if (wrap) {
				int lh = hd.lineHeight(style);
				int maxH = maxLines == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxLines * lh;
				if (align == Align.LEFT) {
					hd.paragraph(style, text, px, py, w, color, maxH);
				} else {
					var lines = hd.wrap(style, text, w);
					for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
						line(hd, lines.get(i), px, py + i * lh, w);
					}
				}
			} else {
				line(hd, hd.ellipsize(style, text, w), px, py, w);
			}
		}
	}

	private void line(Hd hd, String s, int px, int py, int w) {
		switch (align) {
			case CENTER -> hd.textCentered(style, s, px + w / 2, py, color);
			case RIGHT -> hd.textRight(style, s, px + w, py, color);
			default -> hd.text(style, s, px, py, color);
		}
	}
}
