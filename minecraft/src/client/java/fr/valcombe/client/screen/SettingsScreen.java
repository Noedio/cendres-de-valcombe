package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdToggle;

/**
 * Réglages de la nuit et du vote, pour le Conteur. Premier écran passé à la boîte à outils haute résolution
 * (voir docs/UI-HD.md) : mêmes boutons, mêmes commandes, dessin net à la résolution de l'écran.
 */
public class SettingsScreen extends HdScreen {
	public SettingsScreen() {
		super("Réglages de la partie");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {380, 216};
	}

	/** Interrupteur dans la colonne de gauche (col 0) ou de droite (col 1) ; son libellé reste « X : oui/non ». */
	private HdToggle toggle(int col, int y, String label, String key, boolean on) {
		int w = pw / 2 - 15;
		return toggle(px + 10 + col * (w + 10), y, w, label, on,
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
		// Règles de notre feuille, là où elle s'écarte de la règle officielle.
		toggle(0, y += 21, "Héritière sans seuil", "heritiere_sans_seuil", ClientState.bool(st, "heirAlways"))
			.tooltip("Oui : l'Héritière (Femme écarlate) devient le Démon même s'il reste moins de 5 vivants. "
				+ "Non : règle officielle, 5 vivants au moins.");
		toggle(1, y, "Infos du mal dès 5", "infos_mal_des_5", ClientState.bool(st, "evilInfoAlways"))
			.tooltip("Oui : dès 5 joueurs, les Sbires apprennent le Démon et le Démon ses Sbires et ses bluffs. "
				+ "Non : règle officielle, à partir de 7 joueurs.");
		button(px + pw / 2 - 60, py + ph - 26, 120, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
	}

	@Override
	protected void drawHd(Hd hd) {
		JsonObject st = ClientState.obj(view(), "settings");
		int council = ClientState.num(st, "councilSeconds", 0);
		int w = pw / 2 - 15;
		// Durée du Conseil, entre les boutons « − » et « + » de la quatrième rangée.
		String text = council > 0 ? "Conseil : " + council / 60 + " min " + (council % 60 > 0 ? council % 60 + " s" : "")
			: "Conseil : à la main";
		int rowTop = hd.px(py + 12 + 21 * 3);
		hd.textCentered(HdStyle.FORT, text.strip(), hd.px(px + 20 + w + w / 2),
			rowTop + (hd.px(18) - hd.lineHeight(HdStyle.FORT)) / 2, Hd.INK);
	}
}
