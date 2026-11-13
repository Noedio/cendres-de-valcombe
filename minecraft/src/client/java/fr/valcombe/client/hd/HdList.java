package fr.valcombe.client.hd;

import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Liste avec défilement : lignes de hauteur fixe dans un creux de parchemin, ascenseur à droite quand elle
 * dépasse. Molette, glisser du curseur, clic sur le rail (page), flèches, Début, Fin, Page préc. et suiv. ;
 * un clic choisit une ligne, un double-clic ou Entrée l'active. Avec une clé (key), la position de défilement
 * survit aux reconstructions de l'écran.
 *
 * @param <T> le type des éléments
 */
public class HdList<T> extends AbstractWidget implements HdKeyed {
	/** Dessin d'une ligne (pixels réels) ; par défaut, le libellé de l'élément. */
	@FunctionalInterface
	public interface Row<T> {
		void draw(Hd hd, T item, int index, int x, int y, int w, int h, boolean hovered, boolean selected);
	}

	/** Marge intérieure du creux, en unités. */
	private static final int PAD = 3;
	/** Largeur réservée à l'ascenseur, en unités. */
	private static final int BAR = 7;
	private static final Map<String, Double> OFFSETS = new HashMap<>();

	private final List<T> items = new ArrayList<>();
	private final int rowHeight;
	private final Function<T, String> label;
	private final Scroll scroll = new Scroll();
	private Row<T> row;
	private Consumer<T> onSelect = t -> { };
	private Consumer<T> onActivate = t -> { };
	private int selected = -1;
	private String key;
	private String empty = "Rien à afficher";
	private boolean draggingThumb;
	private double grab;

	/** Une liste de lignes de rowHeight unités ; label donne le texte d'une ligne (dessin et narration). */
	public HdList(int x, int y, int w, int h, int rowHeight, Function<T, String> label) {
		super(x, y, w, h, Component.empty());
		this.rowHeight = Math.max(4, rowHeight);
		this.label = label;
		this.row = this::defaultRow;
	}

	public HdList<T> items(List<T> list) {
		items.clear();
		items.addAll(list);
		if (selected >= items.size()) {
			selected = -1;
		}
		layout();
		// Avec une clé posée avant les éléments, la position gardée n'a pas pu être reprise (liste vide) : on la
		// reprend ici, pour que l'ordre de key() et items() n'ait pas d'importance.
		if (key != null && OFFSETS.containsKey(key)) {
			scroll.setOffset(OFFSETS.get(key));
		}
		return this;
	}

	public List<T> items() {
		return items;
	}

	/** Dessin personnalisé des lignes. */
	public HdList<T> row(Row<T> r) {
		this.row = r;
		return this;
	}

	/** Appelé quand une ligne est choisie (clic, flèches). */
	public HdList<T> onSelect(Consumer<T> c) {
		this.onSelect = c;
		return this;
	}

	/** Appelé quand une ligne est activée (double-clic, Entrée, Espace). */
	public HdList<T> onActivate(Consumer<T> c) {
		this.onActivate = c;
		return this;
	}

	/** Texte affiché quand la liste est vide. */
	public HdList<T> empty(String text) {
		this.empty = text;
		return this;
	}

	/** Garde la position de défilement sous cette clé, d'une reconstruction de l'écran à l'autre. */
	public HdList<T> key(String k) {
		this.key = k;
		layout();
		if (k != null && OFFSETS.containsKey(k)) {
			scroll.setOffset(OFFSETS.get(k));
		}
		return this;
	}

	@Override
	public String key() {
		return key;
	}

	/** Choisit une ligne sans prévenir (état venu du serveur) et l'amène dans la fenêtre. */
	public HdList<T> select(int index) {
		selected = index >= 0 && index < items.size() ? index : -1;
		reveal(selected);
		return this;
	}

	public int selectedIndex() {
		return selected;
	}

	public T selected() {
		return selected >= 0 ? items.get(selected) : null;
	}

	public double scrollOffset() {
		return scroll.offset();
	}

	/** Fait défiler de delta unités (positif : vers le bas). */
	public void scrollBy(double delta) {
		scroll.scrollBy(delta);
		remember();
	}

	// ------------------------------------------------------------------
	// Géométrie (unités d'interface)
	// ------------------------------------------------------------------
	private void layout() {
		scroll.resize(items.size() * (double) rowHeight, getHeight() - 2.0 * PAD);
	}

	private boolean barShown() {
		return scroll.scrollable();
	}

	private int innerLeft() {
		return getX() + PAD;
	}

	private int innerTop() {
		return getY() + PAD;
	}

	private int innerRight() {
		return getX() + getWidth() - PAD - (barShown() ? BAR : 0);
	}

	private int innerBottom() {
		return getY() + getHeight() - PAD;
	}

	private double track() {
		return getHeight() - 2.0 * PAD;
	}

	private int indexAt(double mouseY) {
		double rel = mouseY - innerTop() + scroll.offset();
		int i = (int) Math.floor(rel / rowHeight);
		return rel >= 0 && i < items.size() ? i : -1;
	}

	private boolean onBar(double mx) {
		return barShown() && mx >= getX() + getWidth() - PAD - BAR;
	}

	private void reveal(int index) {
		if (index >= 0) {
			scroll.reveal(index * (double) rowHeight, (index + 1) * (double) rowHeight);
			remember();
		}
	}

	private void remember() {
		if (key != null) {
			OFFSETS.put(key, scroll.offset());
		}
	}

	private void choose(int index, boolean activate) {
		if (index < 0 || index >= items.size()) {
			return;
		}
		selected = index;
		reveal(index);
		onSelect.accept(items.get(index));
		if (activate) {
			onActivate.accept(items.get(index));
		}
	}

	// ------------------------------------------------------------------
	// Souris et clavier
	// ------------------------------------------------------------------
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!active || !visible || !isValidClickButton(event.buttonInfo()) || !isMouseOver(event.x(), event.y())) {
			return false;
		}
		layout();
		if (onBar(event.x())) {
			double t = track();
			double top = innerTop() + scroll.thumbStart(t);
			double len = scroll.thumbLength(t);
			if (event.y() >= top && event.y() < top + len) {
				draggingThumb = true;
				grab = event.y() - top;
			} else {
				// Clic sur le rail : une page vers le curseur.
				scrollBy(event.y() < top ? -track() : track());
			}
			return true;
		}
		int i = indexAt(event.y());
		if (i >= 0) {
			choose(i, doubleClick);
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingThumb) {
			scroll.dragThumbTo(event.y() - innerTop() - grab, track());
			remember();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean was = draggingThumb;
		draggingThumb = false;
		return was;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (!visible || !isMouseOver(x, y) || !scroll.scrollable()) {
			return false;
		}
		scrollBy(-scrollY * rowHeight * 1.5);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!active || !isFocused() || items.isEmpty()) {
			return false;
		}
		int page = Math.max(1, (int) (track() / rowHeight) - 1);
		int code = event.input();
		if (event.isUp()) {
			choose(selected <= 0 ? 0 : selected - 1, false);
		} else if (event.isDown()) {
			choose(Math.min(items.size() - 1, selected + 1), false);
		} else if (code == InputConstants.KEY_PAGEUP) {
			choose(Math.max(0, selected - page), false);
		} else if (code == InputConstants.KEY_PAGEDOWN) {
			choose(Math.min(items.size() - 1, Math.max(0, selected) + page), false);
		} else if (code == InputConstants.KEY_HOME) {
			choose(0, false);
		} else if (code == InputConstants.KEY_END) {
			choose(items.size() - 1, false);
		} else if (event.isSelection() && selected >= 0) {
			onActivate.accept(items.get(selected));
		} else {
			return false;
		}
		return true;
	}

	// ------------------------------------------------------------------
	// Dessin
	// ------------------------------------------------------------------
	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		layout();
		try (Hd hd = Hd.begin(g)) {
			hd.inset(hd.px(getX()), hd.px(getY()), hd.px(getWidth()), hd.px(getHeight()));
			if (isFocused()) {
				hd.frame(hd.px(getX()), hd.px(getY()), hd.px(getWidth()), hd.px(getHeight()), Math.max(1, hd.u(0.5)), 0xA0D9B45A);
			}
			int x0 = hd.px(innerLeft());
			int x1 = hd.px(innerRight());
			int top = hd.px(innerTop());
			int bottom = hd.px(innerBottom());
			if (items.isEmpty()) {
				hd.textCentered(HdStyle.TEXTE, empty, (x0 + x1) / 2, (top + bottom - hd.lineHeight(HdStyle.TEXTE)) / 2, Hd.INK_SOFT);
			} else {
				hd.clip(x0, top, x1, bottom);
				double offset = scroll.offset();
				int first = Math.max(0, (int) Math.floor(offset / rowHeight));
				int last = Math.min(items.size() - 1, (int) Math.floor((offset + track()) / rowHeight));
				int hoveredIndex = isHovered() && !onBar(hd.mouseX() / hd.scale()) ? indexAt(hd.mouseY() / hd.scale()) : -1;
				for (int i = first; i <= last; i++) {
					int ry = top + (int) Math.round((i * (double) rowHeight - offset) * hd.scale());
					int rh = hd.u(rowHeight);
					row.draw(hd, items.get(i), i, x0, ry, x1 - x0, rh, i == hoveredIndex, i == selected);
				}
				hd.unclip();
			}
			if (barShown()) {
				int bx = hd.px(getX() + getWidth() - PAD - BAR) + hd.u(1);
				int bw = hd.u(BAR - 1);
				double t = track();
				hd.sprite(HdSprites.ASCENSEUR_RAIL, bx, top, bw, bottom - top);
				int ty = top + (int) Math.round(scroll.thumbStart(t) * hd.scale());
				int th = (int) Math.round(scroll.thumbLength(t) * hd.scale());
				boolean overThumb = draggingThumb || isHovered() && hd.mouseX() >= bx && hd.mouseY() >= ty && hd.mouseY() < ty + th;
				hd.sprite(overThumb ? HdSprites.ASCENSEUR_SURVOL : HdSprites.ASCENSEUR, bx, ty, bw, th);
			}
		}
	}

	/** Ligne par défaut : fond survolé ou choisi, libellé à gauche. */
	private void defaultRow(Hd hd, T item, int index, int x, int y, int w, int h, boolean hovered, boolean selectedRow) {
		if (selectedRow) {
			hd.fill(x, y, x + w, y + h, Hd.SELECTION);
			hd.fill(x, y, x + hd.u(1.5), y + h, Hd.GOLD);
		} else if (hovered) {
			hd.fill(x, y, x + w, y + h, Hd.HOVER);
		}
		String text = hd.ellipsize(HdStyle.TEXTE, label.apply(item), w - hd.u(10));
		hd.textMiddle(HdStyle.TEXTE, text, x + hd.u(6), y, h, selectedRow ? Hd.LIGHT : Hd.INK);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		T s = selected();
		String text = items.isEmpty() ? empty
			: (s != null ? label.apply(s) + ", " : "") + items.size() + (items.size() > 1 ? " lignes" : " ligne");
		output.add(NarratedElementType.TITLE, Component.literal(text));
	}
}
