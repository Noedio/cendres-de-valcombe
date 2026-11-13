package fr.valcombe.client.hd;

import fr.valcombe.client.ui.WaxButton;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Bouton à la cire haute résolution : même comportement que WaxButton (action, press(), état inactif, focus au
 * clavier, narration), dessin net à la résolution de l'écran. Il hérite de WaxButton pour que tout ce qui cherche
 * les boutons d'un écran (le test en jeu, notamment) les trouve sans changement.
 */
public class HdButton extends WaxButton implements HdTooltipped, HdKeyed {
	/** Épaisseur de la tranche sous le bouton, en unités : le libellé se centre sur la face, au-dessus. */
	protected static final float LIP = 0.75f;
	private String tooltip;
	private String key;
	private HdStyle style = HdStyle.BOUTON;

	public HdButton(int x, int y, int w, int h, String label, Runnable action) {
		super(x, y, w, h, label, action);
	}

	@Override
	public HdButton disabled() {
		super.disabled();
		return this;
	}

	/** Infobulle affichée au survol. */
	public HdButton tooltip(String text) {
		this.tooltip = text;
		return this;
	}

	@Override
	public String hdTooltip() {
		return tooltip;
	}

	/** Clé pour retrouver le focus après une reconstruction (par défaut : le libellé). */
	public HdButton key(String k) {
		this.key = k;
		return this;
	}

	@Override
	public String key() {
		return key != null ? key : "bouton:" + baseLabel();
	}

	/** Libellé sans l'état variable (un interrupteur garde la même clé qu'il soit à oui ou à non). */
	protected String baseLabel() {
		return getMessage().getString();
	}

	/** Style du libellé (par défaut HdStyle.BOUTON). */
	public HdButton style(HdStyle s) {
		this.style = s;
		return this;
	}

	protected HdStyle labelStyle() {
		return style;
	}

	/**
	 * Action principale de l'écran (« la suite », celle qu'on attend de l'utilisateur) : un halo doré qui respire
	 * l'entoure tant qu'elle est active. Une seule par écran.
	 */
	public HdButton primary() {
		this.primary = true;
		return this;
	}

	public boolean isPrimary() {
		return primary;
	}

	private boolean primary;

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		try (Hd hd = Hd.begin(g)) {
			int x = hd.px(getX());
			int y = hd.px(getY());
			int w = hd.px(getWidth());
			int h = hd.px(getHeight());
			if (primary && active) {
				halo(hd, x, y, w, h);
			}
			HdSprites.Sprite sp = !active ? HdSprites.BOUTON_INACTIF
				: isHoveredOrFocused() ? HdSprites.BOUTON_SURVOL : HdSprites.BOUTON;
			hd.sprite(sp, x, y, w, h);
			if (primary && active) {
				hd.frame(x + hd.u(1), y + hd.u(1), w - 2 * hd.u(1), h - hd.u(LIP) - 2 * hd.u(1), Math.max(1, hd.u(0.5)), 0xA0F2C14E);
			}
			drawFace(hd, x, y, w, h - hd.u(LIP));
		}
	}

	/** Le halo d'une action principale : trois contours dorés de plus en plus pâles, qui respirent lentement. */
	private static void halo(Hd hd, int x, int y, int w, int h) {
		double breath = 0.65 + 0.35 * Math.sin(net.minecraft.util.Util.getMillis() / 420.0);
		for (int i = 1; i <= 3; i++) {
			int d = hd.u(i * 0.75);
			int alpha = (int) Math.round(breath * (0x70 - i * 0x18));
			if (alpha > 0) {
				hd.frame(x - d, y - d, w + 2 * d, h + 2 * d, Math.max(1, hd.u(0.75)), alpha << 24 | 0xF2C14E);
			}
		}
	}

	/** Dessine le dessus du bouton (libellé centré) ; faceH exclut la tranche. */
	protected void drawFace(Hd hd, int x, int y, int w, int faceH) {
		String label = hd.ellipsize(style, getMessage().getString(), w - hd.u(10));
		int lw = hd.width(style, label);
		hd.textShadow(style, label, x + (w - lw) / 2, y + (faceH - hd.lineHeight(style)) / 2,
			active ? Hd.LIGHT : Hd.MUTED, 0x90000000);
	}
}
