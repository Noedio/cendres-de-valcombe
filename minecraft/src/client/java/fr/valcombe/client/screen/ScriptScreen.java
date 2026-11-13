package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdToken;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.Team;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.screens.ChatScreen;

/**
 * Éditeur de script : choisir les rôles de la prochaine partie, enregistrer, recharger, appliquer un groupe de rôles.
 * En haute résolution : la grille de jetons se règle sur le nombre de rôles (rôles créés dans l'Atelier compris)
 * pour toujours tenir dans le panneau.
 */
public class ScriptScreen extends HdScreen {
	/** Groupe choisi : gardé d'une ouverture à l'autre ; ses jetons sont soulignés d'or. */
	private static String group;
	private static final int COL_W = 128;
	/** Haut de l'intertitre des groupes, sous les trois rangées de boutons des scripts (unités depuis py). */
	private static final int GROUPS_TITLE = 78;

	// Grille des rôles, calculée par build() et reprise par drawHd() (unités d'interface).
	private int gridX;
	private int gridY;
	private int cell;
	private int cols;
	private int tokenSize;

	public ScriptScreen() {
		super("Éditeur de script");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {420, 330};
	}

	private int colX() {
		return px + pw - COL_W - 14;
	}

	/** Problème du script (trop peu de rôles d'un type...), affiché sous la grille. */
	private static String problem(JsonObject script) {
		String p = ClientState.str(script, "problem");
		return p == null || p.isEmpty() ? null : p;
	}

	/** Centre du jeton i de la grille. */
	private int cellX(int i) {
		return gridX + (i % cols) * cell + cell / 2;
	}

	private int cellY(int i) {
		return gridY + (i / cols) * cell + cell / 2;
	}

	@Override
	protected void build() {
		JsonObject script = ClientState.obj(view(), "script");
		List<String> roles = strings(script, "roles");
		List<Role> all = Role.all();
		// Grille : les plus grands jetons (cases de 30 unités) qui tiennent entre l'en-tête et le problème éventuel.
		int areaX = px + 14;
		int areaW = colX() - 10 - areaX;
		int areaTop = py + 42;
		String problem = problem(script);
		int areaBottom = py + ph - 12 - (problem == null ? 0 : StorytellerKit.textHeight(HdStyle.TEXTE.size(10), problem, areaW, 3) + 4);
		int n = Math.max(1, all.size());
		for (cell = 30; cell > 18; cell--) {
			cols = Math.max(1, areaW / cell);
			int rows = (n + cols - 1) / cols;
			if (rows * cell <= areaBottom - areaTop) {
				break;
			}
		}
		cols = Math.max(1, areaW / cell);
		tokenSize = cell * 4 / 5;
		gridX = areaX + (areaW - cols * cell) / 2;
		gridY = areaTop;
		for (int i = 0; i < all.size(); i++) {
			Role r = all.get(i);
			boolean in = roles.contains(r.name());
			token(new HdToken(cellX(i), cellY(i), tokenSize, "", r.name(), in ? Team.of(r).name() : null, in, in,
				() -> send("vc script basculer " + r.name().toLowerCase()))
				.tooltip(r.displayName + (in ? " (dans le script)" : " (exclu)")));
		}
		int bx = colX();
		int y = py + 12;
		int half = (COL_W - 4) / 2;
		button(bx, y, half, "Cendres", () -> send("vc script complet"))
			.tooltip("Le script complet des Cendres de Valcombe.");
		button(bx + COL_W - half, y, half, "Brumes", () -> send("vc script brumes"))
			.tooltip("Le script des Brumes de Valcombe.");
		button(bx, y += 22, COL_W, "Enregistrer sous…", () -> minecraft.gui.setScreen(new ChatScreen("/vc script sauver ", false)));
		button(bx, y += 22, COL_W, "Scénarios…", () -> minecraft.gui.setScreen(new ScenarioScreen()));
		// Intertitre « Groupes de rôles » (dessiné par drawHd) entre les scripts et les groupes.
		y = buildGroups(script, bx, py + GROUPS_TITLE + 14) + 8;
		for (String name : strings(script, "saved")) {
			if (name.startsWith("Les Cendres de Valcombe") || name.startsWith("Les Brumes de Valcombe")) {
				continue; // scripts fournis : boutons Cendres et Brumes
			}
			if (y > py + ph - 52) {
				break;
			}
			button(bx, y, COL_W, "Charger : " + name, () -> send("vc script charger " + name));
			y += 22;
		}
		button(bx, py + ph - 26, COL_W, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
	}

	/**
	 * Groupes de l'Atelier, dans la colonne de droite : en choisir un (ses jetons sont soulignés d'or), l'ajouter au
	 * script, l'en retirer, ne garder que lui ; ranger le script dans un groupe. Renvoie le bas de la dernière ligne.
	 */
	private int buildGroups(JsonObject script, int bx, int y) {
		JsonObject all = ClientState.obj(script, "groups");
		List<String> names = all == null ? List.of() : new ArrayList<>(all.keySet());
		if (group != null && !names.contains(group)) {
			group = null;
		}
		if (group == null && !names.isEmpty()) {
			group = names.get(0);
		}
		Runnable ranger = () -> minecraft.gui.setScreen(new ChatScreen("/vc script groupe ranger ", false));
		if (group == null) {
			button(bx, y, COL_W, "Ranger dans un groupe…", ranger)
				.tooltip("Ranger le script en cours sous un nom, pour le retrouver ou l'ajouter à un autre script. "
					+ "Les groupes de l'Atelier apparaissent aussi ici.");
			return y + 18;
		}
		// Le nom du groupe est raccourci pour que son nombre de rôles reste lisible au bout du bouton.
		String count = " (" + strings(all, group).size() + ")";
		String name = group;
		while (name.length() > 1 && StorytellerKit.textWidth(HdStyle.BOUTON, name + "…" + count) > COL_W - 12) {
			name = name.substring(0, name.length() - 1);
		}
		button(bx, y, COL_W, (name.equals(group) ? name : name.strip() + "…") + count, () -> {
			group = names.get((names.indexOf(group) + 1) % names.size());
			rebuildSoon();
		}).tooltip("Groupe « " + group + " » : ses rôles sont soulignés d'or. "
			+ (names.size() > 1 ? "Cliquer pour passer au suivant (" + names.size() + " groupes)."
				: "Rangez le script dans un autre groupe pour en avoir plusieurs."));
		y += 22;
		int third = (COL_W - 8) / 3;
		button(bx, y, third, "+", () -> send("vc script groupe ajouter " + group))
			.tooltip("Ajouter au script les rôles du groupe.");
		button(bx + third + 4, y, third, "−", () -> send("vc script groupe retirer " + group))
			.tooltip("Retirer du script les rôles du groupe.");
		button(bx + COL_W - third, y, third, "Seul", () -> send("vc script groupe seulement " + group))
			.tooltip("Le script devient exactement le groupe.");
		y += 22;
		int half = (COL_W - 4) / 2;
		button(bx, y, half, "Ranger…", ranger)
			.tooltip("Ranger le script en cours dans un groupe (nouveau, ou du même nom).");
		button(bx + COL_W - half, y, half, "Effacer…",
			() -> minecraft.gui.setScreen(new ChatScreen("/vc script groupe supprimer " + group, false)))
			.tooltip("Supprimer le groupe « " + group + " » (Entrée dans le chat pour confirmer). Les rôles, eux, restent.");
		return y + 18;
	}

	@Override
	protected void drawHd(Hd hd) {
		JsonObject script = ClientState.obj(view(), "script");
		int x = px + 14;
		int w = colX() - 10 - x;
		String title = ClientState.str(script, "name") + " : " + strings(script, "roles").size() + " rôles";
		hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, title, hd.px(w)), hd.px(x), hd.px(py + 12), Hd.WINE);
		hd.text(HdStyle.PETIT, "Clique sur un jeton pour l'inclure ou l'exclure.", hd.px(x), hd.px(py + 27), Hd.INK_SOFT);
		// Intertitre de la colonne de droite, au-dessus des boutons des groupes.
		int bx = colX();
		hd.textCentered(HdStyle.SECTION, "Groupes de rôles", hd.px(bx + COL_W / 2), hd.px(py + GROUPS_TITLE), Hd.WINE);
		// Rôles du groupe choisi : un trait d'or sous leur jeton.
		List<Role> all = Role.all();
		JsonObject groups = ClientState.obj(script, "groups");
		if (group != null && groups != null && groups.has(group)) {
			List<String> in = strings(groups, group);
			int half = Math.max(hd.u(3), hd.u(tokenSize * 0.35));
			for (int i = 0; i < all.size(); i++) {
				if (in.contains(all.get(i).name())) {
					int cx = hd.px(cellX(i));
					int below = hd.px(cellY(i)) + hd.px(tokenSize) / 2 + hd.u(Math.max(1.5, tokenSize / 10.0) + 0.5);
					hd.fill(cx - half, below, cx + half, below + Math.max(1, hd.u(1.5)), Hd.GOLD);
				}
			}
		}
		String problem = problem(script);
		if (problem != null) {
			int th = StorytellerKit.textHeight(HdStyle.TEXTE.size(10), problem, w, 3);
			hd.paragraph(HdStyle.TEXTE.size(10), problem, hd.px(x), hd.px(py + ph - 12 - th), hd.px(w), Theme.BLOOD,
				3 * hd.lineHeight(HdStyle.TEXTE.size(10)));
		}
	}
}
