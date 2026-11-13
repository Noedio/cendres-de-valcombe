package fr.valcombe.client.hd;

import fr.valcombe.client.screen.BaseScreen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.util.Util;

/**
 * Écran de Valcombe en haute résolution. Il garde tout ce que fait BaseScreen (taille du panneau, build(),
 * reconstruction à chaque vue du serveur, outils view(), send(), objects()...) et dessine net :
 * <ul>
 * <li>voile, panneau de parchemin encadré et bandeau de titre à la résolution de l'écran ;</li>
 * <li>drawHd(hd) pour le contenu dessiné à la main, en pixels réels ;</li>
 * <li>button() rend un HdButton, toggle() un HdToggle ;</li>
 * <li>infobulles haute résolution (HdTooltipped) après un court survol, ou au focus du clavier ;</li>
 * <li>le focus du clavier, le texte tapé et le défilement survivent aux reconstructions (HdKeyed).</li>
 * </ul>
 * Passer un écran de BaseScreen à HdScreen : changer la classe mère, remplacer drawContent par drawHd (ou garder
 * drawContent : il est encore appelé, après drawHd), et remplacer les WaxButton créés à la main par des HdButton.
 */
public abstract class HdScreen extends BaseScreen {
	/** Survol avant l'apparition d'une infobulle, en millisecondes. */
	private static final long TOOLTIP_DELAY = 350;
	private Object tipTarget;
	private long tipSince;
	private boolean rebuildPending;

	protected HdScreen(String title) {
		super(title);
	}

	/** Dessine le contenu sous les widgets, en pixels réels (hd.px() convertit les coordonnées d'interface). */
	protected void drawHd(Hd hd) {
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		try (Hd hd = Hd.begin(g)) {
			hd.shade();
			hd.panel(hd.px(px), hd.px(py), hd.px(pw), hd.px(ph));
			hd.banner(getTitle().getString(), hd.px(px + pw / 2), hd.px(py) - hd.u(9));
			drawHd(hd);
		}
		drawContent(g, mouseX, mouseY);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		extractTooltip(g);
	}

	/** Infobulle de l'élément survolé (ou qui a le focus du clavier), dans une strate au-dessus de tout. */
	private void extractTooltip(GuiGraphicsExtractor g) {
		AbstractWidget target = null;
		boolean byKeyboard = false;
		for (GuiEventListener child : children()) {
			if (child instanceof AbstractWidget w && w.visible && w.isHovered() && child instanceof HdTooltipped t
					&& t.hdTooltip() != null) {
				target = w;
			}
		}
		if (target == null && getFocused() instanceof AbstractWidget w && w.isFocused() && w instanceof HdTooltipped t
				&& t.hdTooltip() != null && minecraft.getLastInputType().isKeyboard()) {
			target = w;
			byKeyboard = true;
		}
		long now = Util.getMillis();
		if (target != tipTarget) {
			tipTarget = target;
			tipSince = now;
		}
		if (target == null || now - tipSince < TOOLTIP_DELAY) {
			return;
		}
		g.nextStratum();
		try (Hd hd = Hd.begin(g)) {
			String text = ((HdTooltipped) target).hdTooltip();
			if (byKeyboard) {
				hd.tooltip(text, hd.px(target.getX()) - hd.u(10), hd.px(target.getBottom()) - hd.u(8));
			} else {
				hd.tooltip(text, hd.mouseX(), hd.mouseY());
			}
		}
	}

	/**
	 * Reconstruit l'écran au prochain tick. À utiliser depuis l'action d'un élément (onglet, interrupteur local) :
	 * reconstruire pendant le clic retirerait l'élément que Minecraft s'apprête à marquer comme ayant le focus.
	 */
	protected void rebuildSoon() {
		rebuildPending = true;
	}

	@Override
	public void tick() {
		super.tick();
		if (rebuildPending) {
			rebuildPending = false;
			rebuildWidgets();
		}
	}

	@Override
	protected void rebuildWidgets() {
		String focusKey = getFocused() instanceof HdKeyed k ? k.key() : null;
		super.rebuildWidgets();
		if (focusKey != null) {
			for (GuiEventListener child : children()) {
				if (child instanceof HdKeyed k && focusKey.equals(k.key())) {
					setFocused(child);
					break;
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Outils
	// ------------------------------------------------------------------
	@Override
	protected HdButton button(int x, int y, int w, String label, Runnable action) {
		return addRenderableWidget(new HdButton(x, y, w, 18, label, action));
	}

	/** Interrupteur « libellé : oui/non » de 18 unités de haut. */
	protected HdToggle toggle(int x, int y, int w, String label, boolean on, Runnable action) {
		return addRenderableWidget(new HdToggle(x, y, w, 18, label, on, action));
	}

	protected HdToken token(HdToken t) {
		return addRenderableWidget(t);
	}
}
