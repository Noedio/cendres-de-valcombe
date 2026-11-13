package fr.valcombe.client.hd;

/**
 * Interrupteur : un bouton dont le libellé est à gauche et l'état (un curseur doré ou éteint) à droite.
 * Son message reste « libellé : oui » ou « libellé : non », pour la narration et pour les tests qui cherchent
 * les boutons par leur libellé. L'action fait basculer l'état (souvent : envoyer la commande au serveur, qui
 * renvoie une vue et l'écran se reconstruit avec le nouvel état).
 */
public class HdToggle extends HdButton {
	private final String label;
	private final boolean on;

	public HdToggle(int x, int y, int w, int h, String label, boolean on, Runnable action) {
		super(x, y, w, h, label + " : " + (on ? "oui" : "non"), action);
		this.label = label;
		this.on = on;
	}

	public boolean on() {
		return on;
	}

	public String label() {
		return label;
	}

	@Override
	protected String baseLabel() {
		return label;
	}

	@Override
	protected void drawFace(Hd hd, int x, int y, int w, int faceH) {
		int tw = hd.u(20);
		int th = hd.u(10);
		int pad = hd.u(6);
		int sx = x + w - pad - tw;
		int sy = y + (faceH - th) / 2;
		HdStyle st = labelStyle();
		String text = hd.ellipsize(st, label, sx - x - pad - hd.u(4));
		hd.textShadow(st, text, x + pad, y + (faceH - hd.lineHeight(st)) / 2, active ? Hd.LIGHT : Hd.MUTED, 0x90000000);
		hd.sprite(on ? HdSprites.INTERRUPTEUR_OUI : HdSprites.INTERRUPTEUR_NON, sx, sy, tw, th, active ? -1 : 0xFFA09890);
		int knob = th;
		hd.image(HdSprites.PASTILLE, on ? sx + tw - knob : sx, sy, knob, knob, active ? -1 : 0xFFA09890);
	}
}
