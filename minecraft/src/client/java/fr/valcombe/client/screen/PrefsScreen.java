package fr.valcombe.client.screen;

import fr.valcombe.client.Ambience;
import fr.valcombe.client.ClientConfig;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdToggle;

/** Préférences du joueur (gardées sur son PC et par le launcher) : affichage et son. */
public class PrefsScreen extends HdScreen {
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
		pref(x, y, bw, "Mode daltonien : formes en plus des couleurs", c.daltonien, () -> c.daltonien = !c.daltonien)
			.tooltip("Un triangle sur les jetons du mal, un rond sur ceux du bien, en plus de leur couleur.");
		pref(x, y + 30, bw, "Fond sonore de la nuit", c.ambiance, () -> {
			c.ambiance = !c.ambiance;
			Ambience.refresh();
		});
		pref(x, y + 60, bw, "Conteur : rôles au-dessus des joueurs", c.rolesAuDessus, () -> c.rolesAuDessus = !c.rolesAuDessus);
		button(px + pw / 2 - 50, py + ph - 28, 100, "Retour", () -> minecraft.gui.setScreen(new MainScreen()));
	}

	/** Interrupteur « libellé : oui/non » d'une préférence locale : bascule, enregistre, puis redessine l'écran. */
	private HdToggle pref(int x, int y, int w, String label, boolean on, Runnable flip) {
		return toggle(x, y, w, label, on, () -> {
			flip.run();
			ClientConfig.get().save();
			rebuildSoon();
		});
	}

	@Override
	protected void drawHd(Hd hd) {
		hd.text(HdStyle.TEXTE.size(10), "Gardées sur ce PC, et retrouvées par le launcher.", hd.px(px + 20), hd.px(py + ph - 48),
			Hd.INK_SOFT);
	}
}
