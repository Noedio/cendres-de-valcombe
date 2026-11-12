package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Ordre de réveil, pour le Conteur : les rôles en jeu dans l'ordre où ils seront appelés (la nuit en cours,
 * ou la prochaine). Les flèches avancent ou reculent un rôle ; cela vaut pour les réveils à venir.
 */
public class OrderScreen extends BaseScreen {
	private static final int ROW = 18;
	/** Première ligne affichée (molette). */
	private int scroll;

	public OrderScreen() {
		super("Ordre de réveil");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {300, 250};
	}

	private int visible() {
		return Math.max(1, (ph - 64) / ROW);
	}

	@Override
	protected void build() {
		List<JsonObject> plan = objects(view(), "plan");
		scroll = Math.max(0, Math.min(scroll, plan.size() - visible()));
		int y = py + 26;
		for (int i = scroll; i < plan.size() && i < scroll + visible(); i++) {
			String role = ClientState.str(plan.get(i), "role");
			var up = button(px + pw - 62, y, 22, "▲", () -> send("vc ordre " + role + " haut"));
			var down = button(px + pw - 36, y, 22, "▼", () -> send("vc ordre " + role + " bas"));
			if (i == 0) {
				up.disabled();
			}
			if (i == plan.size() - 1) {
				down.disabled();
			}
			y += ROW;
		}
		button(px + pw / 2 - 60, py + ph - 26, 120, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		scroll -= (int) Math.signum(dy);
		rebuildWidgets();
		return true;
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		List<JsonObject> plan = objects(view(), "plan");
		String when = ClientState.phase().equals("NIGHT") ? "Cette nuit et les suivantes" : "Prochaine nuit";
		Theme.centered(g, font, when, px + pw / 2, py + 12, Theme.INK);
		if (plan.isEmpty()) {
			Theme.centered(g, font, "Aucun rôle ne se réveille.", px + pw / 2, py + 40, Theme.INK);
			return;
		}
		int y = py + 26 + 5;
		for (int i = scroll; i < plan.size() && i < scroll + visible(); i++) {
			JsonObject e = plan.get(i);
			String player = ClientState.str(e, "player");
			String text = (i + 1) + ". " + ClientState.str(e, "title") + (player == null || player.isEmpty() ? "" : " · " + player);
			g.text(font, text, px + 14, y, Theme.INK, false);
			y += ROW;
		}
	}
}
