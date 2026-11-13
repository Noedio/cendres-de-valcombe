package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdFonts;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdText;
import fr.valcombe.rules.Role;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prêter un rôle à des joueurs : le Jongleur (cinq hypothèses, la deuxième nuit) et le Bavard (une hypothèse,
 * après le Conseil). On choisit un joueur, puis le rôle qu'on lui prête ; « Valider » envoie le tout.
 */
public class GuessScreen extends HdScreen {
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
					rebuildSoon();
				});
			}
			button(px + 14, py + ph - 26, 120, "Aucun rôle", () -> {
				picks.remove(pickingFor);
				pickingFor = null;
				rebuildSoon();
			});
			button(px + pw - 94, py + ph - 26, 80, "Retour", () -> {
				pickingFor = null;
				rebuildSoon();
			});
			return;
		}
		List<String> players = strings(p, "players");
		// Les joueurs commencent sous la consigne (une ou deux lignes), avec un écart : elle ne touche jamais les boutons.
		int top = py + 20 + textLines(p) * HdText.PAS + 6;
		int rows = Math.max(1, (py + ph - 32 - top) / 20);
		// Deux colonnes, trois si la table est trop grande pour deux, quatre en dernier recours (consigne sur deux lignes
		// et 20 joueurs dans 427 x 240 : trois colonnes n'en montraient que 18) : chaque joueur a son bouton.
		int cols = Math.min(4, Math.max(2, (players.size() + rows - 1) / rows));
		int colW = (pw - 28 - (cols - 1) * 8) / cols;
		int scale = Hd.guiScale();
		for (int i = 0; i < players.size() && i < rows * cols; i++) {
			String name = players.get(i);
			Role r = NotebookScreen.roleOf(picks.get(name));
			int x = px + 14 + (i / rows) * (colW + 8);
			int y = top + (i % rows) * 20;
			String label = name + " : " + (r == null ? "?" : r.displayName);
			var b = button(x, y, colW, label, () -> {
				pickingFor = name;
				rebuildSoon();
			}).tooltip(HdFonts.width(HdStyle.BOUTON, scale, label) > (colW - 10) * scale ? label : null);
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

	/** Lignes de la consigne (deux au plus) à la largeur du panneau. */
	private int textLines(JsonObject p) {
		String text = ClientState.str(p, "text");
		if (text == null || text.isEmpty()) {
			return 0;
		}
		int s = Hd.guiScale();
		return Math.min(2, HdFonts.wrap(HdText.SERRE, s, text, (pw - 28) * s).size());
	}

	@Override
	protected void drawHd(Hd hd) {
		JsonObject p = prompt();
		if (p == null) {
			return;
		}
		if (pickingFor != null) {
			HdText.line(hd, HdText.SERRE, "Quel rôle prêtes-tu à " + pickingFor + " ?", hd.px(px + 14), hd.px(py + 13), hd.px(pw - 28),
				Hd.INK_SOFT);
			return;
		}
		// Titre de l'invite, puis sa consigne (deux lignes au plus : les joueurs commencent dessous).
		HdText.line(hd, HdStyle.FORT, ClientState.str(p, "title"), hd.px(px + 14), hd.px(py + 7), hd.px(pw - 28), Hd.WINE);
		HdText.paragraph(hd, HdText.SERRE, ClientState.str(p, "text"), hd.px(px + 14), hd.px(py + 20), hd.px(pw - 28), Hd.INK,
			hd.u(HdText.PAS), hd.px(24));
	}
}
