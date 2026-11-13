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
 * Galerie : des cases de taille égale rangées en colonnes (autant qu'il en tient), dans un creux de parchemin, avec
 * un ascenseur quand elles dépassent. C'est la liste à vignettes de l'Atelier (cartes, structures...). Les cases
 * s'élargissent pour remplir la largeur ; molette, glisser du curseur, clic sur le rail, flèches dans les quatre
 * sens, Début, Fin, Page préc. et suiv. ; un clic choisit une case (et l'ouvre avec {@link #openOnClick()}), un
 * double-clic ou Entrée l'ouvre. Avec une clé, la position de défilement survit aux reconstructions de l'écran.
 *
 * <pre>
 * addRenderableWidget(new HdGrid&lt;Carte&gt;(x, y, w, h, 120, 150, 6, Carte::nom)
 *     .items(cartes).key("atelier.cartes").cell((hd, c, i, cx, cy, cw, ch, survol, choisie) -&gt; ...)
 *     .openOnClick().onActivate(c -&gt; ouvrir(c)));
 * </pre>
 *
 * @param <T> le type des éléments
 */
public class HdGrid<T> extends AbstractWidget implements HdKeyed {
	/** Dessin d'une case (pixels réels). */
	@FunctionalInterface
	public interface Cell<T> {
		void draw(Hd hd, T item, int index, int x, int y, int w, int h, boolean hovered, boolean selected);
	}

	/** Marge intérieure du creux, en unités. */
	private static final int PAD = 4;
	/** Largeur réservée à l'ascenseur, en unités. */
	private static final int BAR = 7;
	private static final Map<String, Double> OFFSETS = new HashMap<>();

	private final List<T> items = new ArrayList<>();
	private final int minCellW;
	private final int cellH;
	private final int gap;
	private final Function<T, String> label;
	private final Scroll scroll = new Scroll();
	private Cell<T> cell;
	private Consumer<T> onSelect = t -> { };
	private Consumer<T> onActivate = t -> { };
	private boolean openOnClick;
	private int selected = -1;
	private String key;
	private String empty = "Rien à afficher";
	private boolean draggingThumb;
	private double grab;

	/**
	 * Une galerie de cases d'au moins minCellW unités de large et de cellH unités de haut, séparées de gap unités ;
	 * label donne le nom d'une case (narration, tests, dessin par défaut).
	 */
	public HdGrid(int x, int y, int w, int h, int minCellW, int cellH, int gap, Function<T, String> label) {
		super(x, y, w, h, Component.empty());
		this.minCellW = Math.max(8, minCellW);
		this.cellH = Math.max(8, cellH);
		this.gap = Math.max(0, gap);
		this.label = label;
		this.cell = this::defaultCell;
	}

	public HdGrid<T> items(List<T> list) {
		items.clear();
		items.addAll(list);
		if (selected >= items.size()) {
			selected = -1;
		}
		layout();
		if (key != null && OFFSETS.containsKey(key)) {
			scroll.setOffset(OFFSETS.get(key));
		}
		return this;
	}

	public List<T> items() {
		return items;
	}

	/** Dessin des cases. */
	public HdGrid<T> cell(Cell<T> c) {
		this.cell = c;
		return this;
	}

	public HdGrid<T> onSelect(Consumer<T> c) {
		this.onSelect = c;
		return this;
	}

	/** Appelé quand une case est ouverte (double-clic, Entrée, Espace ; un simple clic avec openOnClick). */
	public HdGrid<T> onActivate(Consumer<T> c) {
		this.onActivate = c;
		return this;
	}

	/** Un simple clic ouvre la case (galerie où choisir, c'est ouvrir). */
	public HdGrid<T> openOnClick() {
		this.openOnClick = true;
		return this;
	}

	public HdGrid<T> empty(String text) {
		this.empty = text;
		return this;
	}

	public HdGrid<T> key(String k) {
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

	/** Choisit une case sans prévenir (état venu du serveur) et l'amène dans la fenêtre. */
	public HdGrid<T> select(int index) {
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

	/** Ouvre la case qui porte ce nom, comme un clic (pour les tests en jeu) ; faux s'il n'y en a pas. */
	public boolean open(String name) {
		for (int i = 0; i < items.size(); i++) {
			if (label.apply(items.get(i)).equals(name)) {
				choose(i, true);
				return true;
			}
		}
		return false;
	}

	/** Les noms des cases, dans l'ordre (narration, tests). */
	public List<String> labels() {
		return items.stream().map(label).toList();
	}

	public double scrollOffset() {
		return scroll.offset();
	}

	public void scrollBy(double delta) {
		scroll.scrollBy(delta);
		remember();
	}

	// ------------------------------------------------------------------
	// Géométrie (unités d'interface)
	// ------------------------------------------------------------------

	/** Nombre de colonnes qui tiennent dans la largeur (au moins une). */
	public int columns() {
		int inner = getWidth() - 2 * PAD - BAR;
		return Math.max(1, (inner + gap) / (minCellW + gap));
	}

	/** Largeur d'une case, élargie pour remplir la ligne (unités). */
	public double cellWidth() {
		int cols = columns();
		double inner = getWidth() - 2.0 * PAD - (barShown() ? BAR : 0);
		return Math.max(1, (inner - (cols - 1) * gap) / cols);
	}

	public int cellHeight() {
		return cellH;
	}

	private int rows() {
		return (items.size() + columns() - 1) / columns();
	}

	private double rowStep() {
		return cellH + gap;
	}

	private void layout() {
		int r = rows();
		scroll.resize(r == 0 ? 0 : r * rowStep() - gap, getHeight() - 2.0 * PAD);
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

	private double track() {
		return getHeight() - 2.0 * PAD;
	}

	private int indexAt(double mx, double my) {
		double relY = my - innerTop() + scroll.offset();
		double relX = mx - innerLeft();
		if (relY < 0 || relX < 0) {
			return -1;
		}
		int row = (int) Math.floor(relY / rowStep());
		if (relY - row * rowStep() > cellH) {
			return -1; // dans l'espace entre deux rangées
		}
		double cw = cellWidth();
		int col = (int) Math.floor(relX / (cw + gap));
		if (col >= columns() || relX - col * (cw + gap) > cw) {
			return -1;
		}
		int i = row * columns() + col;
		return i < items.size() ? i : -1;
	}

	private boolean onBar(double mx) {
		return barShown() && mx >= getX() + getWidth() - PAD - BAR;
	}

	private void reveal(int index) {
		if (index >= 0) {
			int row = index / columns();
			scroll.reveal(row * rowStep(), row * rowStep() + cellH);
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
				scrollBy(event.y() < top ? -track() : track());
			}
			return true;
		}
		int i = indexAt(event.x(), event.y());
		if (i >= 0) {
			choose(i, doubleClick || openOnClick);
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
		scrollBy(-scrollY * Math.min(rowStep(), 40));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!active || !isFocused() || items.isEmpty()) {
			return false;
		}
		int cols = columns();
		int pageRows = Math.max(1, (int) (track() / rowStep()));
		int at = Math.max(0, selected);
		int code = event.input();
		if (event.isLeft()) {
			choose(selected <= 0 ? 0 : selected - 1, false);
		} else if (event.isRight()) {
			choose(Math.min(items.size() - 1, selected + 1), false);
		} else if (event.isUp()) {
			choose(selected < 0 ? 0 : Math.max(at % cols, at - cols), false);
		} else if (event.isDown()) {
			choose(selected < 0 ? 0 : Math.min(items.size() - 1, at + cols), false);
		} else if (code == InputConstants.KEY_PAGEUP) {
			choose(Math.max(0, at - cols * pageRows), false);
		} else if (code == InputConstants.KEY_PAGEDOWN) {
			choose(Math.min(items.size() - 1, at + cols * pageRows), false);
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
			int x1 = hd.px(getX() + getWidth() - PAD - (barShown() ? BAR : 0));
			int top = hd.px(innerTop());
			int bottom = hd.px(getY() + getHeight() - PAD);
			if (items.isEmpty()) {
				hd.paragraph(HdStyle.TEXTE, empty, x0 + hd.u(8), (top + bottom) / 2 - hd.lineHeight(HdStyle.TEXTE), x1 - x0 - hd.u(16),
					Hd.INK_SOFT);
			} else {
				hd.clip(x0, top, x1, bottom);
				double offset = scroll.offset();
				int cols = columns();
				double cw = cellWidth();
				int firstRow = Math.max(0, (int) Math.floor(offset / rowStep()));
				int lastRow = (int) Math.floor((offset + track()) / rowStep());
				double mx = hd.mouseX() / hd.scale();
				double my = hd.mouseY() / hd.scale();
				int hoveredIndex = isHovered() && !onBar(mx) ? indexAt(mx, my) : -1;
				for (int row = firstRow; row <= lastRow; row++) {
					for (int col = 0; col < cols; col++) {
						int i = row * cols + col;
						if (i >= items.size()) {
							break;
						}
						int cx = x0 + (int) Math.round(col * (cw + gap) * hd.scale());
						int cxe = x0 + (int) Math.round((col * (cw + gap) + cw) * hd.scale());
						int cy = top + (int) Math.round((row * rowStep() - offset) * hd.scale());
						cell.draw(hd, items.get(i), i, cx, cy, cxe - cx, hd.u(cellH), i == hoveredIndex, i == selected);
					}
				}
				hd.unclip();
			}
			if (barShown()) {
				int bx = hd.px(getX() + getWidth() - PAD - BAR) + hd.u(2);
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

	/** Case par défaut : fond survolé ou choisi, nom centré. */
	private void defaultCell(Hd hd, T item, int index, int x, int y, int w, int h, boolean hovered, boolean sel) {
		hd.fill(x, y, x + w, y + h, sel ? Hd.SELECTION : hovered ? Hd.HOVER : 0x20000000);
		String text = hd.ellipsize(HdStyle.TEXTE, label.apply(item), w - hd.u(8));
		hd.textCentered(HdStyle.TEXTE, text, x + w / 2, y + (h - hd.lineHeight(HdStyle.TEXTE)) / 2, sel ? Hd.LIGHT : Hd.INK);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		T s = selected();
		String text = items.isEmpty() ? empty
			: (s != null ? label.apply(s) + ", " : "") + items.size() + (items.size() > 1 ? " éléments" : " élément");
		output.add(NarratedElementType.TITLE, Component.literal(text));
	}
}
