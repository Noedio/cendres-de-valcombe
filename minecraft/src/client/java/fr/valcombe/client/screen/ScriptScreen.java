package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.client.ui.TokenWidget;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.Team;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

/** Éditeur de script : choisir les rôles de la prochaine partie, enregistrer, recharger, appliquer un groupe de rôles. */
public class ScriptScreen extends BaseScreen {
	/** Groupe choisi : gardé d'une ouverture à l'autre ; ses jetons sont entourés d'or. */
	private static String group;

	public ScriptScreen() {
		super("Éditeur de script");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {420, 330};
	}

	@Override
	protected void build() {
		JsonObject script = ClientState.obj(view(), "script");
		List<String> roles = strings(script, "roles");
		List<Role> all = Role.all();
		for (int i = 0; i < all.size(); i++) {
			Role r = all.get(i);
			boolean in = roles.contains(r.name());
			int x = px + 24 + (i % 9) * 29;
			int y = py + 52 + (i / 9) * 30;
			TokenWidget t = new TokenWidget(x, y, 26, "", r.name(), in ? Team.of(r).name() : null, in, in,
				() -> send("vc script basculer " + r.name().toLowerCase()));
			t.setTooltip(Tooltip.create(Component.literal(r.displayName + (in ? " (dans le script)" : " (exclu)"))));
			addRenderableWidget(t);
		}
		int bx = px + pw - 142;
		int y = py + 14;
		button(bx, y, 62, "Cendres", () -> send("vc script complet"));
		button(bx + 66, y, 62, "Brumes", () -> send("vc script brumes"));
		button(bx, y += 22, 128, "Enregistrer sous…", () -> minecraft.gui.setScreen(new ChatScreen("/vc script sauver ", false)));
		button(bx, y += 22, 128, "Scénarios…", () -> minecraft.gui.setScreen(new ScenarioScreen()));
		y = buildGroups(script, bx, y + 30) + 8;
		for (String name : strings(script, "saved")) {
			if (name.startsWith("Les Cendres de Valcombe") || name.startsWith("Les Brumes de Valcombe")) {
				continue; // scripts fournis : boutons Cendres et Brumes
			}
			if (y > py + ph - 52) {
				break;
			}
			button(bx, y, 128, "Charger : " + name, () -> send("vc script charger " + name));
			y += 22;
		}
		button(bx, py + ph - 26, 128, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
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
			button(bx, y, 128, "Ranger dans un groupe…", ranger)
				.setTooltip(Tooltip.create(Component.literal("Ranger le script en cours sous un nom, pour le retrouver ou "
					+ "l'ajouter à un autre script. Les groupes de l'Atelier apparaissent aussi ici.")));
			return y + 18;
		}
		String count = " (" + strings(all, group).size() + ")";
		String name = group;
		while (name.length() > 1 && font.width(name + "…" + count) > 116) {
			name = name.substring(0, name.length() - 1);
		}
		button(bx, y, 128, (name.equals(group) ? name : name.strip() + "…") + count, () -> {
			group = names.get((names.indexOf(group) + 1) % names.size());
			rebuildWidgets();
		}).setTooltip(Tooltip.create(Component.literal("Groupe « " + group + " » : ses rôles sont soulignés d'or. "
			+ (names.size() > 1 ? "Cliquer pour passer au suivant (" + names.size() + " groupes)."
				: "Rangez le script dans un autre groupe pour en avoir plusieurs."))));
		y += 22;
		button(bx, y, 40, "+", () -> send("vc script groupe ajouter " + group))
			.setTooltip(Tooltip.create(Component.literal("Ajouter au script les rôles du groupe.")));
		button(bx + 44, y, 40, "−", () -> send("vc script groupe retirer " + group))
			.setTooltip(Tooltip.create(Component.literal("Retirer du script les rôles du groupe.")));
		button(bx + 88, y, 40, "Seul", () -> send("vc script groupe seulement " + group))
			.setTooltip(Tooltip.create(Component.literal("Le script devient exactement le groupe.")));
		y += 22;
		button(bx, y, 62, "Ranger…", ranger)
			.setTooltip(Tooltip.create(Component.literal("Ranger le script en cours dans un groupe (nouveau, ou du même nom).")));
		button(bx + 66, y, 62, "Effacer…",
			() -> minecraft.gui.setScreen(new ChatScreen("/vc script groupe supprimer " + group, false)))
			.setTooltip(Tooltip.create(Component.literal("Supprimer le groupe « " + group + " » (Entrée dans le chat pour "
				+ "confirmer). Les rôles, eux, restent.")));
		return y + 18;
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject script = ClientState.obj(view(), "script");
		Theme.ink(g, font, ClientState.str(script, "name") + " : " + strings(script, "roles").size() + " rôles",
			px + 14, py + 14, Theme.WINE);
		Theme.ink(g, font, "Clique sur un jeton pour l'inclure ou l'exclure.", px + 14, py + 24, Theme.ASH);
		List<Role> all = Role.all();
		JsonObject groups = ClientState.obj(script, "groups");
		if (group != null && groups != null && groups.has(group)) {
			List<String> in = strings(groups, group);
			for (int i = 0; i < all.size(); i++) {
				if (in.contains(all.get(i).name())) {
					int x = px + 24 + (i % 9) * 29;
					int y = py + 52 + (i / 9) * 30;
					g.fill(x - 9, y + 13, x + 9, y + 16, Theme.GOLD); // (x, y) : centre du jeton

				}
			}
		}
		String problem = ClientState.str(script, "problem");
		if (problem != null) {
			Theme.paragraph(g, font, problem, px + 14, py + ph - 40, pw - 170, Theme.BLOOD);
		}
	}
}
