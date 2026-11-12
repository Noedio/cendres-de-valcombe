package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Carnet : choisir le rôle que l'on prête à un joueur, parmi les rôles du script en cours. */
public class NotebookGuessScreen extends BaseScreen {
	private final String name;

	public NotebookGuessScreen(String name) {
		super("Rôle prêté à " + name);
		this.name = name;
	}

	@Override
	protected int[] panelSize() {
		return new int[] {420, 250};
	}

	private static List<Role> roles() {
		List<Role> out = new ArrayList<>();
		JsonObject script = ClientState.obj(view(), "script");
		for (String id : strings(script, "roles")) {
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
		List<Role> roles = roles();
		int cols = 4;
		int bw = (pw - 28 - (cols - 1) * 4) / cols;
		int rows = Math.max(1, (ph - 70) / 20);
		for (int i = 0; i < roles.size() && i < cols * rows; i++) {
			Role r = roles.get(i);
			int x = px + 14 + (i % cols) * (bw + 4);
			int y = py + 28 + (i / cols) * 20;
			var b = button(x, y, bw, r.displayName, () -> pick(r.name()));
			if (r.name().equals(NotebookScreen.page(name).guess)) {
				b.disabled();
			}
		}
		button(px + 14, py + ph - 26, 120, "Aucun", () -> pick(null));
		button(px + pw - 94, py + ph - 26, 80, "Retour", () -> minecraft.gui.setScreen(new NotebookScreen()));
	}

	private void pick(String role) {
		NotebookScreen.page(name).guess = role;
		NotebookScreen.save();
		minecraft.gui.setScreen(new NotebookScreen());
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Theme.ink(g, font, "Quel rôle penses-tu que " + name + " a vraiment ?", px + 14, py + 12, Theme.ASH);
	}
}
