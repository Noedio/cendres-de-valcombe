package fr.valcombe.client.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Scénarios créés dans l'Atelier : en choisir un pour la prochaine partie (rôles, carte,
 * réglages et règles d'un coup), ou ouvrir l'Atelier pour en créer. La liste défile à la molette
 * quand elle dépasse le panneau.
 */
public class ScenarioScreen extends HdScreen {
	private static final int ROW = 22;
	/** Scénario dont le choix attend un second clic : il chargerait une autre carte que celle en place. */
	private static String armed;
	/** Premier scénario montré (molette). */
	private int first;
	private int listTop;
	private int shown;
	private int total;

	public ScenarioScreen() {
		super("Scénarios");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {320, 240};
	}

	private static List<JsonObject> scenarios() {
		List<JsonObject> out = new ArrayList<>();
		var list = view().getAsJsonArray("scenarios");
		if (list != null) {
			for (JsonElement e : list) {
				out.add(e.getAsJsonObject());
			}
		}
		return out;
	}

	@Override
	protected void build() {
		List<JsonObject> list = scenarios();
		listTop = py + 42;
		// Les scénarios tiennent au-dessus des deux rangées du bas ; au-delà, la liste défile.
		shown = Math.max(1, (py + ph - 76 - listTop) / ROW + 1);
		total = list.size();
		first = Math.max(0, Math.min(first, total - shown));
		boolean scroll = total > shown;
		int w = pw - 28 - (scroll ? 7 : 0);
		int y = listTop;
		for (int i = first; i < total && i < first + shown; i++) {
			JsonObject sc = list.get(i);
			String id = ClientState.str(sc, "id");
			String label = ClientState.str(sc, "nom") + "  (" + ClientState.str(sc, "joueurs") + " joueurs)";
			String map = ClientState.str(sc, "carte");
			// Le scénario charge sa carte à la place de celle du serveur : on le dit avant de le faire.
			boolean replaces = map != null && !map.isEmpty() && !map.equals(ClientState.str(view(), "mapInPlace"));
			boolean ready = id.equals(armed);
			HdButton b = button(px + 14, y, w, ready ? "Confirmer : la carte « " + map + " » remplace l'actuelle" : label, () -> {
				if (replaces && !ready) {
					armed = id;
					rebuildSoon();
					return;
				}
				armed = null;
				send("vc scenario " + id);
				minecraft.gui.setScreen(new ScriptScreen());
			});
			if (map != null && !map.isEmpty()) {
				b.tooltip("Carte : " + map + (replaces ? " (remplace la carte en place, second clic pour confirmer)" : ""));
			}
			y += ROW;
		}
		button(px + 14, py + ph - 50, 140, "Sans scénario", () -> {
			send("vc scenario aucun");
			minecraft.gui.setScreen(new ScriptScreen());
		});
		boolean operator = ClientState.obj(view(), "atelier") != null;
		button(px + pw - 154, py + ph - 50, 140, operator ? "Atelier" : "Lien de l'Atelier web", () -> {
			armed = null;
			if (operator) {
				fr.valcombe.client.screen.atelier.AtelierPage.opening();
				minecraft.gui.setScreen(fr.valcombe.client.screen.atelier.AtelierHome.roles());
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
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		if (total > shown && dy != 0) {
			first = Math.max(0, Math.min(total - shown, first - (int) Math.signum(dy)));
			rebuildWidgets();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, dx, dy);
	}

	@Override
	protected void drawHd(Hd hd) {
		JsonObject current = ClientState.obj(view(), "scenario");
		int x = px + 14;
		int w = pw - 28;
		String head = current == null ? "Aucun scénario : script et réglages libres."
			: "Prochaine partie : « " + ClientState.str(current, "nom") + " »";
		hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, head, hd.px(w)), hd.px(x), hd.px(py + 12), Hd.WINE);
		if (total == 0) {
			hd.paragraph(HdStyle.TEXTE, "Aucun scénario créé. La page web de l'Atelier (/vc atelier) permet de créer des rôles "
				+ "et des scénarios ; l'Atelier en jeu gère les cartes.", hd.px(x), hd.px(py + 40), hd.px(w), Hd.INK_SOFT);
		} else {
			hd.text(HdStyle.PETIT, "Choisir pour la prochaine partie :", hd.px(x), hd.px(py + 27), Hd.INK_SOFT);
			int h = (Math.min(shown, total) - 1) * ROW + 18;
			StorytellerKit.rail(hd, px + pw - 18, listTop, h, first, shown, total);
		}
	}
}
