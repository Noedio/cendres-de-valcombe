package fr.valcombe.client.screen;

import fr.valcombe.client.Maquette;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.screen.atelier.StructureSheetPage;

import java.util.function.Supplier;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/**
 * Une structure du catalogue en grand : sa maquette en 3D, qui tourne d'elle-même ; « Tourner à gauche » et « Tourner à
 * droite » la tournent à la main d'un quart de tour. « Prendre en main » donne l'outil de pose ; « Retour » ramène à la
 * page d'où l'on vient (la fiche de la structure dans l'Atelier).
 */
public class StructureViewerScreen extends fr.valcombe.client.hd.HdScreen {
	public static final String LEFT = "Tourner à gauche";
	public static final String RIGHT = "Tourner à droite";

	private final String id;
	private final Supplier<Screen> back;
	/** Quarts de tour choisis à la main ; la maquette tourne seule tant qu'on n'y touche pas. */
	private int quarters;
	private boolean manual;

	public StructureViewerScreen(String id, Supplier<Screen> back) {
		super("Structure en 3D");
		this.id = id;
		this.back = back;
	}

	/** Depuis l'Atelier : « Retour » ramène à la fiche de la structure. */
	public StructureViewerScreen(String id) {
		this(id, () -> new StructureSheetPage(id));
	}

	public String structureId() {
		return id;
	}

	@Override
	protected int[] panelSize() {
		return new int[] {460, 300};
	}

	@Override
	protected void build() {
		Maquette.request(id);
		int y = py + ph - 26;
		int tw = 104;
		button(px + 12, y, tw, LEFT, () -> {
			manual = true;
			quarters--;
		}).tooltip("Un quart de tour vers la gauche.");
		button(px + 12 + tw + 6, y, tw, RIGHT, () -> {
			manual = true;
			quarters++;
		}).tooltip("Un quart de tour vers la droite.");
		button(px + pw - 12 - 74, y, 74, "Retour", () -> minecraft.gui.setScreen(back.get()));
		int takeX = px + 12 + 2 * tw + 12;
		int takeW = px + pw - 12 - 74 - 6 - takeX;
		button(takeX, y, takeW, StructureSheetPage.TAKE, () -> {
			fr.valcombe.client.screen.atelier.StructuresPage.take(id);
			onClose();
		}).primary().tooltip("L'outil de pose : son fantôme suit votre regard, clic droit pour la poser.");
	}

	@Override
	protected void drawHd(Hd hd) {
		Maquette m = Maquette.get(id);
		int x = hd.px(px + 12);
		int w = hd.px(pw - 24);
		int top = hd.px(py + 10);
		if (m == null) {
			hd.textCentered(HdStyle.TEXTE, "Maquette en route…", x + w / 2, hd.px(py + ph / 2), Hd.INK_SOFT);
			return;
		}
		HdStyle st = HdStyle.SECTION.size(12);
		String title = (m.name == null ? id : m.name) + " · " + m.width + " × " + m.height + " × " + m.length;
		hd.textCentered(st, hd.ellipsize(st, title, w), x + w / 2, top, Hd.WINE);
		int sy = top + hd.lineHeight(st) + hd.u(4);
		int sh = hd.px(py + ph - 26) - hd.u(6) - sy;
		// La scène : un fond sombre et doux, comme les vignettes du catalogue.
		hd.gradient(x, sy, x + w, sy + sh, 0xFF4A3E4C, 0xFF1E1820);
		hd.frame(x, sy, w, sh, Math.max(1, hd.u(0.75)), 0xFF2A1E14);
		if (m.tooBig) {
			hd.textCentered(HdStyle.TEXTE, "Trop grande pour une maquette : sa vignette, dans l'Atelier, la montre en entier.",
				x + w / 2, sy + sh / 2 - hd.lineHeight(HdStyle.TEXTE) / 2, Hd.MUTED);
		}
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Maquette m = Maquette.get(id);
		if (m == null || m.tooBig) {
			return;
		}
		int turn = manual ? quarters : (int) (System.currentTimeMillis() / 1800);
		// Le cadre de la scène, en unités d'interface (la maquette dessine des objets, à l'échelle de l'interface).
		int top = py + 10 + 20;
		m.draw(g, px + 16, top, pw - 32, py + ph - 26 - 8 - top, turn);
	}
}
