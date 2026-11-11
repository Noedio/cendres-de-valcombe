package fr.valcombe.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Signes rapides (touche G) : un symbole s'affiche quelques secondes au-dessus de la tête. */
public class SignScreen extends BaseScreen {
	private static final String[][] SIGNS = {
		{"✔ Oui", "oui"}, {"✘ Non", "non"}, {"? Question", "question"},
		{"! Attention", "attention"}, {"… Je réfléchis", "reflexion"}, {"✋ D'accord", "accord"},
	};

	public SignScreen() {
		super("Faire un signe");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {240, 120};
	}

	@Override
	protected void build() {
		for (int i = 0; i < SIGNS.length; i++) {
			String id = SIGNS[i][1];
			button(px + 12 + (i % 2) * 112, py + 22 + (i / 2) * 24, 104, SIGNS[i][0], () -> {
				send("vc signe " + id);
				onClose();
			});
		}
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
	}
}
