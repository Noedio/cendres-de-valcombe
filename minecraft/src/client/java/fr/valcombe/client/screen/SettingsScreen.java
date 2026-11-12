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
		return new int[] {380, 252};
	}

	/** Interrupteur dans la colonne de gauche (col 0) ou de droite (col 1). */
	private void toggle(int col, int y, String label, String key, boolean on) {
		int w = pw / 2 - 15;
		button(px + 10 + col * (w + 10), y, w, label + " : " + (on ? "oui" : "non"),
			() -> send("vc reglage " + key + " " + (on ? "non" : "oui")));
	}

	@Override
	protected void build() {
		JsonObject st = ClientState.obj(view(), "settings");
		int y = py + 12;
		// À gauche la nuit, à droite le Conseil et les règles de la table.
		toggle(0, y, "Chacun chez soi la nuit", "nuit_maisons", ClientState.bool(st, "nightHouses"));
		toggle(1, y, "Accuser puis voter", "conseil", ClientState.bool(st, "council"));
		toggle(0, y += 21, "Immobiles la nuit", "nuit_immobile", ClientState.bool(st, "nightFreeze"));
		toggle(1, y, "Majorité absolue", "majorite_absolue", ClientState.bool(st, "absoluteMajority"));
		toggle(0, y += 21, "Écran noir la nuit", "nuit_sombre", ClientState.bool(st, "nightDark"));
		toggle(1, y, "Mal majoritaire gagne", "victoire_majorite", ClientState.bool(st, "evilMajorityWins"));
		toggle(0, y += 21, "Micros coupés la nuit", "nuit_muette", ClientState.bool(st, "nightMute"));
		int council = ClientState.num(st, "councilSeconds", 0);
		int w = pw / 2 - 15;
		int rx = px + 20 + w;
		button(rx, y, 24, "−", () -> send("vc reglage duree_conseil " + Math.max(0, council - 30)));
		button(rx + w - 24, y, 24, "+", () -> send("vc reglage duree_conseil " + (council + 30)));
		toggle(0, y += 21, "Rôles remis à l'écart", "remise_convocation", ClientState.bool(st, "roleRoom"));
		toggle(1, y, "Noms au-dessus des têtes", "noms", ClientState.bool(st, "nameTags"));
		int defense = ClientState.num(st, "defenseSeconds", 20);
		int clock = (int) Math.round(ClientState.dbl(st, "clockSeconds", 1.5) * 10);
		y += 25;
		button(px + pw / 2 - 128, y, 26, "−", () -> send("vc reglage temps_defense " + (defense - 5)));
		button(px + pw / 2 + 102, y, 26, "+", () -> send("vc reglage temps_defense " + (defense + 5)));
		y += 22;
		button(px + pw / 2 - 128, y, 26, "−", () -> send("vc reglage temps_horloge " + (clock - 5)));
		button(px + pw / 2 + 102, y, 26, "+", () -> send("vc reglage temps_horloge " + (clock + 5)));
		String hat = ClientState.str(st, "deadHat");
		String next = "chapeau".equals(hat) ? "crane" : "crane".equals(hat) ? "aucun" : "chapeau";
		String label = "chapeau".equals(hat) ? "chapeau" : "crane".equals(hat) ? "crâne" : "aucune";
		// Dernière rangée, à deux : la coiffe des morts, et la carte remise en état après la partie.
		button(px + pw / 2 - 137, y + 23, 104, "Coiffe : " + label, () -> send("vc reglage coiffe " + next));
		boolean restore = ClientState.bool(st, "restoreMap");
		button(px + pw / 2 - 31, y + 23, 168, "Carte remise en état : " + (restore ? "oui" : "non"),
			() -> send("vc reglage remise_en_etat " + (restore ? "non" : "oui")));
		button(px + pw / 2 - 60, py + ph - 26, 120, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject st = ClientState.obj(view(), "settings");
		int council = ClientState.num(st, "councilSeconds", 0);
		int w = pw / 2 - 15;
		Theme.centered(g, font, council > 0 ? "Conseil : " + council / 60 + " min " + (council % 60 > 0 ? council % 60 + " s" : "")
			: "Conseil : à la main", px + 20 + w + w / 2, py + 12 + 21 * 3 + 6, Theme.INK);
		int y = py + 12 + 21 * 4 + 25 + 5;
		Theme.centered(g, font, "Défense de l'accusé : " + ClientState.num(st, "defenseSeconds", 20) + " s", px + pw / 2, y, Theme.INK);
		Theme.centered(g, font, "Aiguille : " + ClientState.dbl(st, "clockSeconds", 1.5) + " s par trône", px + pw / 2, y + 22, Theme.INK);
	}
}
