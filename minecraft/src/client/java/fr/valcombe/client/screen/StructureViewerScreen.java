package fr.valcombe.client.screen;

import fr.valcombe.client.Maquette;
import fr.valcombe.client.ui.Theme;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Une structure du catalogue en grand : sa maquette en 3D, que l'on tourne, avant de la prendre en main. */
public class StructureViewerScreen extends BaseScreen {
	private final String id;
	/** Quarts de tour choisis à la main ; la maquette tourne seule tant qu'on n'y touche pas. */
	private int quarters;
	private boolean manual;

	public StructureViewerScreen(String id) {
		super("Structure en 3D");
		this.id = id;
	}

	@Override
	protected int[] panelSize() {
		return new int[] {380, 216};
	}

	@Override
	protected void build() {
		Maquette.request(id);
		int y = py + ph - 26;
		button(px + 14, y, 40, "⟲", () -> {
			manual = true;
			quarters--;
		});
		button(px + 58, y, 40, "⟳", () -> {
			manual = true;
			quarters++;
		});
		button(px + pw / 2 - 60, y, 120, "Prendre en main", () -> {
			send("vc carte construction");
			send("vc structure poser " + id);
			onClose();
		});
		button(px + pw - 94, y, 80, "Retour", () -> minecraft.gui.setScreen(new AtelierScreen()));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Maquette m = Maquette.get(id);
		if (m == null) {
			Theme.centered(g, font, "Maquette en route…", px + pw / 2, py + ph / 2, Theme.ASH);
			return;
		}
		Theme.centered(g, font, (m.name == null ? id : m.name) + " · " + m.width + " × " + m.height + " × " + m.length,
			px + pw / 2, py + 10, Theme.WINE);
		if (m.tooBig) {
			Theme.centered(g, font, "Trop grande pour une maquette.", px + pw / 2, py + ph / 2, Theme.ASH);
			return;
		}
		int turn = manual ? quarters : (int) (System.currentTimeMillis() / 1800);
		m.draw(g, px + 14, py + 24, pw - 28, ph - 56, turn);
	}
}
