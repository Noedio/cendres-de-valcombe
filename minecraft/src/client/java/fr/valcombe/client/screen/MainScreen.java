package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.client.ui.TokenWidget;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;

/** Menu principal : salon, carte de rôle, cercle, actions du jour, journal. */
public class MainScreen extends BaseScreen {
	private int buttonsTop;

	public MainScreen() {
		super("Les Cendres de Valcombe");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {360, 236};
	}

	private record Action(String label, Runnable run) {
	}

	@Override
	protected void build() {
		JsonObject v = view();
		boolean inGame = !ClientState.phase().equals("LOBBY");
		JsonObject role = ClientState.obj(v, "role");

		// Cercle des joueurs (jetons fermés : on ne voit pas les rôles des autres)
		if (inGame) {
			List<JsonObject> seats = objects(v, "seats");
			int me = ClientState.num(v, "mySeat", -1);
			circle(seats.size(), px + 262, py + 78, 52, (i, x, y) -> {
				JsonObject s = seats.get(i);
				TokenWidget t = new TokenWidget(x, y, 18, ClientState.str(s, "name"),
					i == me && role != null ? ClientState.str(role, "id") : null,
					i == me && role != null ? ClientState.str(role, "team") : null,
					ClientState.bool(s, "alive"), false, null);
				if (!ClientState.bool(s, "alive") && ClientState.bool(s, "ghostVote")) {
					t.marker("ghost");
				}
				token(t);
			});
		}

		List<Action> actions = new ArrayList<>();
		if (ClientState.obj(v, "prompt") != null) {
			actions.add(new Action("» Action en attente", () -> minecraft.gui.setScreen(new PromptScreen())));
		}
		if (!inGame) {
			boolean inLobby = ClientState.bool(v, "inLobby");
			actions.add(new Action(inLobby ? "Se désinscrire" : "S'inscrire",
				() -> send(inLobby ? "vc quitter" : "vc rejoindre")));
		} else if (role != null && ClientState.phase().equals("DAY") && !ClientState.bool(v, "dayOver")) {
			actions.add(new Action("Nominer", () -> minecraft.gui.setScreen(new PickerScreen("Nominer qui ?", "vc nominer "))));
			actions.add(new Action("Tirer", () -> minecraft.gui.setScreen(new PickerScreen("Tirer sur qui ?", "vc tirer "))));
		}
		actions.add(new Action("Fiches des rôles", () -> minecraft.gui.setScreen(new SheetsScreen())));
		actions.add(new Action("Historique", () -> {
			send("vc historique");
			onClose();
		}));
		actions.add(new Action("Écrire au Conteur", () -> minecraft.gui.setScreen(new ChatScreen("/vc mp ", false))));
		if (ClientState.bool(v, "leader")) {
			actions.add(new Action("Panneau du Conteur", () -> minecraft.gui.setScreen(new StorytellerScreen())));
		}
		int bw = 108;
		int rows = (actions.size() + 2) / 3;
		buttonsTop = py + ph - 28 - (rows - 1) * 22;
		for (int i = 0; i < actions.size(); i++) {
			int col = i % 3;
			int row = i / 3;
			Action a = actions.get(i);
			button(px + 12 + col * (bw + 6), buttonsTop + row * 22, bw, a.label(), a.run());
		}
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject v = view();
		String phase = ClientState.phase();
		boolean night = phase.equals("NIGHT");
		Theme.image(g, Theme.tex(night ? "moon" : "sun"), px + 12, py + 12, 12, 12, 16, 16);
		Theme.ink(g, font, phaseLabel(), px + 28, py + 14, Theme.WINE);

		JsonObject role = ClientState.obj(v, "role");
		int x = px + 14;
		int y = py + 32;
		if (role != null) {
			Theme.token(g, x + 18, y + 18, 36, ClientState.str(role, "id"), ClientState.str(role, "team"), true, false, false);
			Theme.ink(g, font, ClientState.str(role, "name"), x + 42, y + 8, Theme.INK);
			boolean evil = "EVIL".equals(ClientState.str(role, "team"));
			Theme.ink(g, font, (evil ? "Camp du mal" : "Camp du bien"), x + 42, y + 20, evil ? Theme.EVIL : Theme.GOOD);
			Theme.paragraph(g, font, ClientState.str(role, "text"), x, y + 42, 172, Theme.INK);
		} else {
			Theme.ink(g, font, phase.equals("LOBBY") ? "Inscrits :" : "Tu observes la partie.", x, y, Theme.INK);
			List<String> lobby = strings(v, "lobby");
			for (int i = 0; i < Math.min(lobby.size(), 12); i++) {
				Theme.ink(g, font, "• " + lobby.get(i), x + 4 + (i / 6) * 86, y + 12 + (i % 6) * 10, Theme.WINE);
			}
			String st = ClientState.str(v, "storyteller");
			Theme.ink(g, font, "Conteur : " + (st == null ? "automatique" : st), x, y + 76, Theme.ASH);
		}

		// Journal des dernières annonces
		List<String> feed = strings(v, "feed");
		int fy = buttonsTop - 24;
		for (int i = Math.max(0, feed.size() - 2); i < feed.size(); i++) {
			String line = feed.get(i);
			if (font.width(line) > pw - 30) {
				line = font.plainSubstrByWidth(line, pw - 34) + "…";
			}
			Theme.ink(g, font, line, px + 14, fy, Theme.ASH);
			fy += 10;
		}
	}
}
