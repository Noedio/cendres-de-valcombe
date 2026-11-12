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
	/** Scénario dont le choix attend un second clic : il chargerait une autre carte que celle en place. */
	private static String armed;

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
				String map = ClientState.str(sc, "carte");
				// Le scénario charge sa carte à la place de celle du serveur : on le dit avant de le faire.
				boolean replaces = map != null && !map.isEmpty() && !map.equals(ClientState.str(view(), "mapInPlace"));
				boolean ready = id.equals(armed);
				button(px + 14, y, pw - 28, ready ? "Confirmer : la carte « " + map + " » remplace l'actuelle" : label, () -> {
					if (replaces && !ready) {
						armed = id;
						rebuildWidgets();
						return;
					}
					armed = null;
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
		boolean operator = ClientState.obj(view(), "atelier") != null;
		button(px + pw - 154, py + ph - 50, 140, operator ? "Atelier" : "Lien de l'Atelier web", () -> {
			armed = null;
			if (operator) {
				minecraft.gui.setScreen(new AtelierScreen());
			} else {
				send("vc atelier");
				onClose();
			}
		});
		button(px + 14, py + ph - 26, 140, "Mode construction", () -> {
			armed = null;
			send("vc carte construction");
			onClose();
		});
		button(px + pw - 154, py + ph - 26, 140, "Retour", () -> {
			armed = null;
			minecraft.gui.setScreen(new ScriptScreen());
		});
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject current = ClientState.obj(view(), "scenario");
		Theme.ink(g, font, current == null ? "Aucun scénario : script et réglages libres."
			: "Prochaine partie : « " + ClientState.str(current, "nom") + " »", px + 14, py + 14, Theme.WINE);
		var list = view().getAsJsonArray("scenarios");
		if (list == null || list.isEmpty()) {
			Theme.paragraph(g, font, "Aucun scénario créé. La page web de l'Atelier (/vc atelier) permet de créer des rôles "
				+ "et des scénarios ; l'Atelier en jeu gère les cartes.", px + 14, py + 40, pw - 28, Theme.ASH);
		} else {
			Theme.ink(g, font, "Choisir pour la prochaine partie :", px + 14, py + 26, Theme.ASH);
		}
	}
}
