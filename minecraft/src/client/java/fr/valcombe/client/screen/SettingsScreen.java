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
		return new int[] {380, 216};
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
		toggle(0, y += 21, "Carnet la nuit seulement", "carnet_nuit", ClientState.bool(st, "notebookNightOnly"));
		String hat = ClientState.str(st, "deadHat");
		String next = "chapeau".equals(hat) ? "crane" : "crane".equals(hat) ? "aucun" : "chapeau";
		String label = "chapeau".equals(hat) ? "chapeau" : "crane".equals(hat) ? "crâne" : "aucune";
		button(rx, y, w, "Coiffe des morts : " + label, () -> send("vc reglage coiffe " + next));
		boolean restore = ClientState.bool(st, "restoreMap");
		toggle(0, y += 21, "Carte remise en état", "remise_en_etat", restore);
		// Défense, aiguille et toutes les autres durées : écran « Durées et minuteurs ».
		button(rx, y, w, "Durées et minuteurs…", () -> minecraft.gui.setScreen(new DurationsScreen()));
		button(px + pw / 2 - 60, py + ph - 26, 120, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject st = ClientState.obj(view(), "settings");
		int council = ClientState.num(st, "councilSeconds", 0);
		int w = pw / 2 - 15;
		Theme.centered(g, font, council > 0 ? "Conseil : " + council / 60 + " min " + (council % 60 > 0 ? council % 60 + " s" : "")
			: "Conseil : à la main", px + 20 + w + w / 2, py + 12 + 21 * 3 + 6, Theme.INK);
	}
}
