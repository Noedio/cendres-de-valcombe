package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Distribution des rôles par le Conteur. Au salon : imposer le rôle de qui l'on veut, les autres seront tirés
 * au hasard au lancement. Après le lancement, avant la première nuit : le rôle réel de chacun, à corriger d'un clic.
 */
public class DistributionScreen extends BaseScreen {
	private static final int ROW = 20;
	private int scroll;

	public DistributionScreen() {
		super("Distribution des rôles");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {400, 250};
	}

	private static boolean lobby() {
		return ClientState.phase().equals("LOBBY");
	}

	/** {joueur, rôle affiché} dans l'ordre du cercle (ou des inscriptions au salon). */
	private static List<String[]> rows() {
		List<String[]> out = new ArrayList<>();
		JsonObject v = view();
		if (lobby()) {
			JsonObject pre = ClientState.obj(v, "presets");
			for (String name : strings(v, "lobby")) {
				String id = pre == null || !pre.has(name) ? null : pre.get(name).getAsString();
				out.add(new String[] {name, id == null ? "au hasard" : label(id)});
			}
		} else {
			for (JsonObject row : objects(v, "grimoire")) {
				JsonObject role = ClientState.obj(row, "role");
				JsonObject believed = ClientState.obj(row, "believed");
				String shown = ClientState.str(role, "name") + (believed == null ? "" : " (croit " + ClientState.str(believed, "name") + ")");
				out.add(new String[] {ClientState.str(row, "name"), shown});
			}
		}
		return out;
	}

	/** « SOMNAMBULE/COMMERE » → « Le Somnambule (croit La Commère) ». */
	private static String label(String id) {
		String[] parts = id.split("/");
		Role r = NotebookScreen.roleOf(parts[0]);
		Role b = parts.length > 1 ? NotebookScreen.roleOf(parts[1]) : null;
		return (r == null ? parts[0] : r.displayName) + (b == null ? "" : " (croit " + b.displayName + ")");
	}

	private int visible() {
		return Math.max(1, (ph - 80) / ROW);
	}

	@Override
	protected void build() {
		List<String[]> rows = rows();
		scroll = Math.max(0, Math.min(scroll, rows.size() - visible()));
		int y = py + 30;
		for (int i = scroll; i < rows.size() && i < scroll + visible(); i++) {
			String name = rows.get(i)[0];
			button(px + 14, y, pw - 120, name + " : " + rows.get(i)[1],
				() -> minecraft.gui.setScreen(new RolePickerScreen(name, null, true)));
			if (lobby()) {
				button(px + pw - 100, y, 86, "Au hasard", () -> send("vc imposer " + name + " aucun"));
			}
			y += ROW;
		}
		button(px + pw - 94, py + ph - 26, 80, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		scroll -= (int) Math.signum(dy);
		rebuildWidgets();
		return true;
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Theme.ink(g, font, lobby() ? "Choisis le rôle de qui tu veux : les autres seront tirés au lancement."
			: "Avant la première nuit, tu peux changer le rôle de chacun.", px + 14, py + 14, Theme.ASH);
		if (rows().isEmpty()) {
			Theme.paragraph(g, font, lobby() ? "Personne n'est encore inscrit." : "Seul le Conteur voit les rôles.",
				px + 14, py + 40, pw - 28, Theme.INK);
		}
		String warn = ClientState.str(view(), "distributionWarning");
		if (warn != null && !warn.isEmpty()) {
			Theme.paragraph(g, font, warn, px + 14, py + ph - 48, pw - 120, Theme.WINE);
		}
	}
}
