package fr.valcombe.client.hd;

/**
 * Bouton dont le libellé contient un symbole absent des polices (« ✔ Oui », « ✋ D'accord », « × ») : le symbole est
 * dessiné net par HdGlyphs, le reste du libellé est écrit comme sur un HdButton. Le message du bouton reste le
 * libellé exact (narration, tests qui cherchent les boutons par leur libellé).
 */
public class HdGlyphButton extends HdButton {
	public HdGlyphButton(int x, int y, int w, int h, String label, Runnable action) {
		super(x, y, w, h, label, action);
	}

	@Override
	protected void drawFace(Hd hd, int x, int y, int w, int faceH) {
		HdStyle st = labelStyle();
		String label = HdGlyphs.ellipsize(hd, st, getMessage().getString(), w - hd.u(10));
		int lw = HdGlyphs.width(hd, st, label);
		HdGlyphs.textShadow(hd, st, label, x + (w - lw) / 2, y + (faceH - hd.lineHeight(st)) / 2,
			active ? Hd.LIGHT : Hd.MUTED, 0x90000000);
	}
}
