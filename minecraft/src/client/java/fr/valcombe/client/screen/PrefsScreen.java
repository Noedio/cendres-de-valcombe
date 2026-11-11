package fr.valcombe.client.screen;

import fr.valcombe.client.Ambience;
import fr.valcombe.client.ClientConfig;
import fr.valcombe.client.ui.Theme;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Préférences du joueur (gardées sur son PC et par le launcher) : affichage et son. */
public class PrefsScreen extends BaseScreen {
	public PrefsScreen() {
		super("Préférences");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {300, 176};
	}

	@Override
	protected void build() {
		ClientConfig c = ClientConfig.get();
		int bw = pw - 40;
		int x = px + 20;
		int y = py + 30;
		toggle(x, y, bw, "Mode daltonien : formes en plus des couleurs", c.daltonien, () -> c.daltonien = !c.daltonien);
		toggle(x, y + 30, bw, "Fond sonore de la nuit", c.ambiance, () -> {
			c.ambiance = !c.ambiance;
			Ambience.refresh();
		});
		toggle(x, y + 60, bw, "Conteur : rôles au-dessus des joueurs", c.rolesAuDessus, () -> c.rolesAuDessus = !c.rolesAuDessus);
		button(px + pw / 2 - 50, py + ph - 28, 100, "Retour", () -> minecraft.gui.setScreen(new MainScreen()));
	}

	private void toggle(int x, int y, int w, String label, boolean on, Runnable flip) {
		String text = label + " : " + (on ? "oui" : "non");
		if (font.width(text) > w - 10) {
			text = font.plainSubstrByWidth(label, w - 10 - font.width(" : non…")) + "… : " + (on ? "oui" : "non");
		}
		button(x, y, w, text, () -> {
			flip.run();
			ClientConfig.get().save();
			rebuildWidgets();
		});
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Theme.ink(g, font, "Gardées sur ce PC, et retrouvées par le launcher.", px + 20, py + ph - 46, Theme.ASH);
	}
}
