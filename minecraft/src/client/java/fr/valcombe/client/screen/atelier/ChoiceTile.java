package fr.valcombe.client.screen.atelier;

import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdGlyphs;
import fr.valcombe.client.hd.HdStyle;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Un point de départ d'un parcours guidé de l'Atelier (« Depuis la sélection », « Parcelle vide »...) : une grande case
 * avec son illustration, son nom, ce qu'il donne, et, en dessous, son état (« Sélection de 7 × 4 × 7 » en vert, « Aucune
 * sélection » en rouge). Dorée et cochée quand elle est choisie, comme les cases de « Nouvelle carte ».
 */
final class ChoiceTile extends HdButton {
	/** L'illustration de la case (pixels réels). */
	@FunctionalInterface
	interface Art {
		void paint(Hd hd, int x, int y, int w, int h);
	}

	private final String text;
	private final Art art;
	private final boolean chosen;
	private String state;
	private boolean stateBad;

	ChoiceTile(int x, int y, int w, int h, String title, String text, Art art, boolean chosen, Runnable action) {
		super(x, y, w, h, title, action);
		this.text = text;
		this.art = art;
		this.chosen = chosen;
	}

	/** L'état écrit sous le texte : en vert, ou en rouge s'il manque quelque chose. */
	ChoiceTile state(String s, boolean bad) {
		this.state = s;
		this.stateBad = bad;
		return this;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		try (Hd hd = Hd.begin(g)) {
			int x = hd.px(getX());
			int y = hd.px(getY());
			int w = hd.px(getWidth());
			int h = hd.px(getHeight());
			boolean hot = isHoveredOrFocused();
			hd.inset(x, y, w, h);
			if (chosen) {
				hd.fill(x, y, x + w, y + h, 0x30F2C14E);
			}
			int pad = hd.u(4);
			int aw = w - 2 * pad;
			int ah = Math.min(aw * 3 / 5, h * 9 / 20);
			int ax = x + pad;
			int ay = y + pad;
			hd.clip(ax, ay, ax + aw, ay + ah);
			art.paint(hd, ax, ay, aw, ah);
			hd.unclip();
			AtelierArt.frameArt(hd, ax, ay, aw, ah);
			HdStyle ts = HdStyle.SECTION.size(w < hd.u(120) ? 10 : 11.5f);
			int ty = ay + ah + hd.u(4);
			hd.text(ts, hd.ellipsize(ts, getMessage().getString(), aw - hd.u(10)), x + pad + hd.u(1), ty, chosen || hot ? Hd.WINE : Hd.INK);
			ty += hd.lineHeight(ts) + hd.u(1);
			int stateH = state == null ? 0 : hd.lineHeight(HdStyle.FORT.size(9)) + hd.u(2);
			hd.paragraph(HdStyle.PETIT, text, x + pad + hd.u(1), ty, aw - hd.u(2), Hd.INK_SOFT, y + h - pad - ty - stateH);
			if (state != null) {
				HdStyle ss = HdStyle.FORT.size(9);
				int sy = y + h - pad - hd.lineHeight(ss);
				int s = hd.u(6);
				int iy = sy + (hd.lineHeight(ss) - s) / 2;
				if (stateBad) {
					HdGlyphs.warning(hd, x + pad + hd.u(1), iy, s, AtelierArt.BAD);
				} else {
					HdGlyphs.check(hd, x + pad + hd.u(1), iy, s, AtelierArt.GOOD);
				}
				hd.text(ss, hd.ellipsize(ss, state, aw - s - hd.u(6)), x + pad + hd.u(1) + s + hd.u(4), sy,
					stateBad ? AtelierKit.WARN : AtelierArt.GOOD);
			}
			if (chosen) {
				hd.frame(x, y, w, h, Math.max(1, hd.u(1.25)), 0xFFD9B45A);
				int s = hd.u(11);
				int bx = x + w - s - hd.u(4);
				int by = y + hd.u(4);
				hd.disc(bx + s / 2, by + s / 2, s / 2, AtelierArt.GOOD);
				HdGlyphs.check(hd, bx + s / 4, by + s / 4, s / 2, Hd.LIGHT);
			} else if (hot) {
				hd.frame(x, y, w, h, Math.max(1, hd.u(1)), 0xA0D9B45A);
			}
		}
	}
}
