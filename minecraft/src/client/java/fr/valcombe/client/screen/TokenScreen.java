package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;

/** Jetons de rappel à poser sur un joueur : ceux des rôles en jeu, puis les jetons communs. Un clic pose ou retire. */
public class TokenScreen extends BaseScreen {
	private record Head(int x, int y, String text, boolean evil) {
	}

	private final String name;
	private final boolean fromNight;
	private final List<Head> heads = new ArrayList<>();

	public TokenScreen(String name, boolean fromNight) {
		super("Rappels : " + name);
		this.name = name;
		this.fromNight = fromNight;
	}

	@Override
	protected int[] panelSize() {
		return new int[] {430, 290};
	}

	private List<String> worn() {
		for (JsonObject row : objects(view(), "grimoire")) {
			if (name.equals(ClientState.str(row, "name"))) {
				return strings(row, "reminders");
			}
		}
		return List.of();
	}

	@Override
	protected void build() {
		heads.clear();
		List<String> worn = worn();
		// Une ligne par rôle : son nom à gauche, ses jetons à droite (trois par ligne).
		int cols = 3;
		int left = px + 118;
		int bw = (px + pw - 14 - left - (cols - 1) * 4) / cols;
		int y = py + 28;
		int limit = py + ph - 30;
		// Les jetons communs d'abord (ils servent toujours), puis ceux des rôles en jeu, tant qu'il reste de la place.
		List<JsonObject> groups = new ArrayList<>(objects(view(), "tokens"));
		if (groups.size() > 1) {
			groups.add(0, groups.remove(groups.size() - 1));
		}
		for (JsonObject group : groups) {
			List<String> items = strings(group, "items");
			int rows = (items.size() + cols - 1) / cols;
			if (y + rows * 20 > limit) {
				break;
			}
			heads.add(new Head(px + 14, y + 5, ClientState.str(group, "role"), ClientState.bool(group, "evil")));
			for (int i = 0; i < items.size(); i++) {
				String item = items.get(i);
				boolean on = worn.contains(item);
				button(left + (i % cols) * (bw + 4), y + (i / cols) * 20, bw, (on ? "✔ " : "") + item,
					() -> send("vc rappel " + name + " " + (on ? "retirer " : "") + item));
			}
			y += rows * 20 + 2;
		}
		int by = py + ph - 26;
		button(px + 14, by, 110, "Autre rappel…", () -> minecraft.gui.setScreen(new ChatScreen("/vc rappel " + name + " ", false)));
		button(px + 128, by, 110, "Tout effacer", () -> send("vc rappel " + name + " effacer"));
		button(px + pw - 100, by, 86, "Retour",
			() -> minecraft.gui.setScreen(fromNight ? new NightScreen() : new SeatScreen(name)));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		List<String> worn = worn();
		Theme.ink(g, font, worn.isEmpty() ? "Aucun rappel posé sur " + name + "." : "Posés : " + String.join(" · ", worn),
			px + 14, py + 12, Theme.BRONZE);
		for (Head h : heads) {
			String label = h.text();
			if (font.width(label) > 100) {
				label = font.plainSubstrByWidth(label, 96) + "…";
			}
			Theme.ink(g, font, label, h.x(), h.y(), h.evil() ? Theme.BLOOD : Theme.WINE);
		}
	}
}
