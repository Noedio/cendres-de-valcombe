package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Réglages de la nuit et du vote, pour le Conteur. */
public class SettingsScreen extends BaseScreen {
	public SettingsScreen() {
		super("Réglages de la partie");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {300, 252};
	}

	private void toggle(int y, String label, String key, boolean on) {
		button(px + pw / 2 - 90, y, 180, label + " : " + (on ? "oui" : "non"),
			() -> send("vc reglage " + key + " " + (on ? "non" : "oui")));
	}

	@Override
	protected void build() {
		JsonObject st = ClientState.obj(view(), "settings");
		int y = py + 16;
		toggle(y, "Chacun chez soi la nuit", "nuit_maisons", ClientState.bool(st, "nightHouses"));
		toggle(y += 24, "Joueurs immobiles la nuit", "nuit_immobile", ClientState.bool(st, "nightFreeze"));
		toggle(y += 24, "Écran noir la nuit", "nuit_sombre", ClientState.bool(st, "nightDark"));
		toggle(y += 24, "Micros coupés la nuit", "nuit_muette", ClientState.bool(st, "nightMute"));
		int defense = ClientState.num(st, "defenseSeconds", 20);
		int clock = (int) Math.round(ClientState.dbl(st, "clockSeconds", 1.5) * 10);
		y += 30;
		button(px + pw / 2 - 90, y, 30, "−", () -> send("vc reglage temps_defense " + (defense - 5)));
		button(px + pw / 2 + 60, y, 30, "+", () -> send("vc reglage temps_defense " + (defense + 5)));
		y += 22;
		button(px + pw / 2 - 90, y, 30, "−", () -> send("vc reglage temps_horloge " + (clock - 5)));
		button(px + pw / 2 + 60, y, 30, "+", () -> send("vc reglage temps_horloge " + (clock + 5)));
		String hat = ClientState.str(st, "deadHat");
		String next = "chapeau".equals(hat) ? "crane" : "crane".equals(hat) ? "aucun" : "chapeau";
		String label = "chapeau".equals(hat) ? "chapeau" : "crane".equals(hat) ? "crâne" : "aucune";
		button(px + pw / 2 - 90, y + 26, 180, "Coiffe des morts : " + label, () -> send("vc reglage coiffe " + next));
		button(px + pw / 2 - 60, py + ph - 26, 120, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject st = ClientState.obj(view(), "settings");
		int y = py + 16 + 24 * 3 + 30 + 5;
		Theme.centered(g, font, "Défense de l'accusé : " + ClientState.num(st, "defenseSeconds", 20) + " s", px + pw / 2, y, Theme.INK);
		Theme.centered(g, font, "Aiguille : " + ClientState.dbl(st, "clockSeconds", 1.5) + " s par siège", px + pw / 2, y + 22, Theme.INK);
	}
}
