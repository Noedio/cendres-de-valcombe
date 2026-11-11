package fr.valcombe.client.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Scénarios créés dans l'Atelier : en choisir un pour la prochaine partie (rôles, carte,
 * réglages et règles d'un coup), ou ouvrir l'Atelier pour en créer.
 */
public class ScenarioScreen extends BaseScreen {
	public ScenarioScreen() {
		super("Scénarios");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {320, 240};
	}

	@Override
	protected void build() {
		var list = view().getAsJsonArray("scenarios");
		int y = py + 40;
		if (list != null) {
			for (JsonElement e : list) {
				if (y > py + ph - 76) {
					break;
				}
				JsonObject sc = e.getAsJsonObject();
				String id = ClientState.str(sc, "id");
				String label = ClientState.str(sc, "nom") + "  (" + ClientState.str(sc, "joueurs") + " joueurs)";
				button(px + 14, y, pw - 28, label, () -> {
					send("vc scenario " + id);
					minecraft.gui.setScreen(new ScriptScreen());
				});
				y += 22;
			}
		}
		button(px + 14, py + ph - 50, 140, "Sans scénario", () -> {
			send("vc scenario aucun");
			minecraft.gui.setScreen(new ScriptScreen());
		});
		button(px + pw - 154, py + ph - 50, 140, "Ouvrir l'Atelier", () -> {
			send("vc atelier");
			onClose();
		});
		button(px + 14, py + ph - 26, 140, "Construire une carte", () -> {
			send("vc carte construction");
			onClose();
		});
		button(px + pw - 154, py + ph - 26, 140, "Retour", () -> minecraft.gui.setScreen(new ScriptScreen()));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject current = ClientState.obj(view(), "scenario");
		Theme.ink(g, font, current == null ? "Aucun scénario : script et réglages libres."
			: "Prochaine partie : « " + ClientState.str(current, "nom") + " »", px + 14, py + 14, Theme.WINE);
		var list = view().getAsJsonArray("scenarios");
		if (list == null || list.isEmpty()) {
			Theme.paragraph(g, font, "Aucun scénario créé. L'Atelier (page web) permet de créer des rôles, des scénarios "
				+ "et de gérer les cartes.", px + 14, py + 40, pw - 28, Theme.ASH);
		} else {
			Theme.ink(g, font, "Choisir pour la prochaine partie :", px + 14, py + 26, Theme.ASH);
		}
	}
}
