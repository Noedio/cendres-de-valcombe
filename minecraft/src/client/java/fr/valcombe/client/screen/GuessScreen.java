package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Prêter un rôle à des joueurs : le Jongleur (cinq hypothèses, la deuxième nuit) et le Bavard (une hypothèse,
 * après le Conseil). On choisit un joueur, puis le rôle qu'on lui prête ; « Valider » envoie le tout.
 */
public class GuessScreen extends BaseScreen {
	/** Hypothèses en cours : joueur → rôle (gardées tant que l'invite est la même). */
	private static final Map<String, String> picks = new LinkedHashMap<>();
	private static String pickingFor;
	private static String forTitle;

	public GuessScreen() {
		super("Prêter un rôle");
		JsonObject p = prompt();
		String title = p == null ? null : ClientState.str(p, "title") + "|" + ClientState.num(p, "count", 1);
		if (title == null || !title.equals(forTitle)) {
			picks.clear();
			pickingFor = null;
			forTitle = title;
		}
	}

	private static JsonObject prompt() {
		return ClientState.obj(view(), "prompt");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {420, 250};
	}

	private static List<Role> roles(JsonObject p) {
		List<Role> out = new ArrayList<>();
		for (String id : strings(p, "roles")) {
			Role r = NotebookScreen.roleOf(id);
			if (r != null) {
				out.add(r);
			}
		}
		out.sort((a, b) -> a.type != b.type ? a.type.compareTo(b.type) : a.displayName.compareTo(b.displayName));
		return out;
	}

	@Override
	protected void build() {
		JsonObject p = prompt();
		if (p == null || !"guess".equals(ClientState.str(p, "type"))) {
			button(px + pw / 2 - 40, py + ph - 26, 80, "Fermer", this::onClose);
			return;
		}
		int count = ClientState.num(p, "count", 1);
		if (pickingFor != null) {
			// Choix du rôle prêté au joueur sélectionné
			List<Role> roles = roles(p);
			int cols = 4;
			int bw = (pw - 28 - (cols - 1) * 4) / cols;
			int rows = Math.max(1, (ph - 76) / 20);
			for (int i = 0; i < roles.size() && i < cols * rows; i++) {
				Role r = roles.get(i);
				button(px + 14 + (i % cols) * (bw + 4), py + 34 + (i / cols) * 20, bw, r.displayName, () -> {
					if (count == 1) {
						picks.clear();
					}
					picks.put(pickingFor, r.name());
					pickingFor = null;
					rebuildWidgets();
				});
			}
			button(px + 14, py + ph - 26, 120, "Aucun rôle", () -> {
				picks.remove(pickingFor);
				pickingFor = null;
				rebuildWidgets();
			});
			button(px + pw - 94, py + ph - 26, 80, "Retour", () -> {
				pickingFor = null;
				rebuildWidgets();
			});
			return;
		}
		List<String> players = strings(p, "players");
		int colW = (pw - 36) / 2;
		int rows = Math.max(1, (ph - 90) / 20);
		for (int i = 0; i < players.size() && i < rows * 2; i++) {
			String name = players.get(i);
			Role r = NotebookScreen.roleOf(picks.get(name));
			int x = px + 14 + (i / rows) * (colW + 8);
			int y = py + 44 + (i % rows) * 20;
			var b = button(x, y, colW, name + " : " + (r == null ? "—" : r.displayName), () -> {
				pickingFor = name;
				rebuildWidgets();
			});
			if (r == null && picks.size() >= count && count > 1) {
				b.disabled(); // déjà assez d'hypothèses : en retirer une d'abord
			}
		}
		var ok = button(px + pw / 2 - 70, py + ph - 26, 140, "Valider (" + picks.size() + "/" + count + ")", () -> {
			StringBuilder cmd = new StringBuilder("vc deviner");
			picks.forEach((k, v) -> cmd.append(' ').append(k).append('=').append(v));
			send(cmd.toString());
		});
		if (picks.size() != count) {
			ok.disabled();
		}
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject p = prompt();
		if (p == null) {
			return;
		}
		if (pickingFor != null) {
			Theme.ink(g, font, "Quel rôle prêtes-tu à " + pickingFor + " ?", px + 14, py + 14, Theme.ASH);
			return;
		}
		Theme.ink(g, font, ClientState.str(p, "title"), px + 14, py + 12, Theme.WINE);
		Theme.paragraph(g, font, ClientState.str(p, "text"), px + 14, py + 24, pw - 28, Theme.INK);
	}
}
