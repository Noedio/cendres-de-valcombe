package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.client.ui.TokenWidget;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.Team;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

/** Éditeur de script : choisir les rôles de la prochaine partie, enregistrer, recharger. */
public class ScriptScreen extends BaseScreen {
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
			t.setTooltip(Tooltip.create(Component.literal(r.displayName + (in ? " — dans le script" : " — exclu"))));
			addRenderableWidget(t);
		}
		int bx = px + pw - 142;
		int y = py + 14;
		button(bx, y, 62, "Cendres", () -> send("vc script complet"));
		button(bx + 66, y, 62, "Brumes", () -> send("vc script brumes"));
		button(bx, y += 22, 128, "Enregistrer sous…", () -> minecraft.gui.setScreen(new ChatScreen("/vc script sauver ", false)));
		button(bx, y += 22, 128, "Scénarios…", () -> minecraft.gui.setScreen(new ScenarioScreen()));
		y += 30;
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

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject script = ClientState.obj(view(), "script");
		Theme.ink(g, font, ClientState.str(script, "name") + " — " + strings(script, "roles").size() + " rôles",
			px + 14, py + 14, Theme.WINE);
		Theme.ink(g, font, "Clique sur un jeton pour l'inclure ou l'exclure.", px + 14, py + 24, Theme.ASH);
		String problem = ClientState.str(script, "problem");
		if (problem != null) {
			Theme.paragraph(g, font, problem, px + 14, py + ph - 40, pw - 170, Theme.BLOOD);
		}
	}
}
