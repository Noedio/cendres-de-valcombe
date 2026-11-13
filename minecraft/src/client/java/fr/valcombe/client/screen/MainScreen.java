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
		return new int[] {380, 260};
	}

	private record Action(String label, Runnable run) {
	}

	@Override
	protected void build() {
		JsonObject v = view();
		boolean inGame = !ClientState.phase().equals("LOBBY");
		JsonObject role = ClientState.obj(v, "role");

		List<Action> actions = new ArrayList<>();
		if (ClientState.obj(v, "prompt") != null) {
			actions.add(new Action("» Action en attente", () -> minecraft.gui.setScreen(new PromptScreen())));
		}
		if (!inGame) {
			boolean inLobby = ClientState.bool(v, "inLobby");
			boolean spectator = ClientState.bool(v, "spectator");
			if (!spectator) {
				actions.add(new Action(inLobby ? "Se désinscrire" : "S'inscrire",
					() -> send(inLobby ? "vc quitter" : "vc rejoindre")));
				actions.add(new Action("S'entraîner", () -> {
					send("vc entrainement");
					onClose();
				}));
			}
			if (!inLobby) {
				actions.add(new Action(spectator ? "Quitter les gradins" : "Regarder", () -> send("vc spectateur")));
			}
			if (v.has("chronicle")) {
				actions.add(new Action("Chronique", () -> minecraft.gui.setScreen(new ChronicleScreen())));
			}
		} else if (role != null && ClientState.phase().equals("DAY") && !ClientState.bool(v, "dayOver")) {
			actions.add(new Action("Nominer", () -> minecraft.gui.setScreen(new PickerScreen("Nominer qui ?", "vc nominer "))));
			actions.add(new Action("Tirer", () -> minecraft.gui.setScreen(new PickerScreen("Tirer sur qui ?", "vc tirer "))));
			actions.add(new Action("Chuchoter", () -> minecraft.gui.setScreen(new PickerScreen("Chuchoter à qui ?", "vc chuchoter "))));
			JsonObject speech = ClientState.obj(v, "speech");
			boolean asked = speech != null && ClientState.bool(speech, "mine");
			actions.add(new Action(asked ? "Rendre la parole" : "Demander la parole", () -> {
				send("vc parole");
				onClose();
			}));
			String invite = ClientState.str(v, "talkInvite");
			if (invite != null && !invite.isEmpty()) {
				actions.add(new Action("Parler avec " + invite, () -> {
					send("vc parler accepter");
					onClose();
				}));
			}
			if (ClientState.str(v, "talkingWith") != null && !ClientState.str(v, "talkingWith").isEmpty()) {
				actions.add(new Action("Revenir au village", () -> {
					send("vc parler fin");
					onClose();
				}));
			} else {
				actions.add(new Action("Parler en privé", () -> minecraft.gui.setScreen(new PickerScreen("Parler en privé avec qui ?", "vc parler "))));
			}
		}
		if (role != null) {
			actions.add(new Action("Annoncer un rôle", () -> minecraft.gui.setScreen(new ClaimScreen())));
		}
		if (inGame && ClientState.num(v, "mySeat", -1) >= 0) {
			String voice = ClientState.str(v, "voice");
			String label = "chuchoter".equals(voice) ? "Chuchoter" : "crier".equals(voice) ? "Crier" : "Parler";
			actions.add(new Action("Voix : " + label + " (B)", () -> send("vc voix suivant")));
			if (ClientState.bool(v, "inCouncil") && ClientState.phase().equals("DAY")) {
				actions.add(new Action("Donner la parole…", () -> minecraft.gui.setScreen(
					new PickerScreen("Donner (ou reprendre) la parole à quel mort ?", "vc donnerparole "))));
			}
		}
		if (ClientState.bool(v, "canTravel") && !ClientState.phase().equals("LOBBY")) {
			actions.add(new Action("Rejoindre en voyageur", () -> {
				send("vc voyager");
				onClose();
			}));
		}
		if (ClientState.bool(v, "traveller")) {
			actions.add(new Action("Quitter Valcombe", () -> {
				send("vc voyageur partir");
				onClose();
			}));
		}
		actions.add(new Action("Fiches des rôles", () -> minecraft.gui.setScreen(new SheetsScreen())));
		actions.add(new Action("Préférences…", () -> minecraft.gui.setScreen(new PrefsScreen())));
		actions.add(new Action("Mon carnet", () -> minecraft.gui.setScreen(new NotebookScreen())));
		actions.add(new Action("Historique", () -> {
			send("vc historique");
			onClose();
		}));
		actions.add(new Action("Écrire au Conteur", () -> minecraft.gui.setScreen(new ChatScreen("/vc mp ", false))));
		if (role != null && ClientState.bool(v, "hasStoryteller") && !ClientState.bool(v, "isStoryteller")) {
			actions.add(new Action("Appeler le Conteur", () -> {
				send("vc appeler");
				onClose();
			}));
		}
		if (ClientState.bool(v, "leader")) {
			actions.add(new Action("Panneau du Conteur", () -> minecraft.gui.setScreen(new StorytellerScreen())));
		}
		if (ClientState.obj(v, "atelier") != null) {
			// Opérateur : cartes, village, structures et créations, sans passer par le navigateur.
			actions.add(new Action("Atelier", () -> minecraft.gui.setScreen(new AtelierScreen())));
		}
		int bw = (pw - 36) / 3;
		int rows = (actions.size() + 2) / 3;
		buttonsTop = py + ph - 28 - (rows - 1) * 22;
		for (int i = 0; i < actions.size(); i++) {
			int col = i % 3;
			int row = i / 3;
			Action a = actions.get(i);
			button(px + 12 + col * (bw + 6), buttonsTop + row * 22, bw, a.label(), a.run());
		}
		// Cercle des joueurs (jetons fermés : on ne voit pas les rôles des autres), à droite, dans la
		// place laissée au-dessus du journal et des boutons : une ellipse qui ne déborde jamais.
		if (inGame) {
			List<JsonObject> seats = objects(v, "seats");
			int me = ClientState.num(v, "mySeat", -1);
			// Noms vers l'extérieur (dessus, dessous, côtés) : ils ne se recouvrent plus.
			int top = py + 18;
			int bottom = buttonsTop - 4;
			int size = bottom - top < 110 ? 14 : 18;
			int ry = Math.max(8, (bottom - top - size - 22) / 2);
			int rx = Math.max(ry, (pw - 196) / 2 - 52);
			int cx = px + 196 + (pw - 196) / 2 - 4;
			int cy = top + 11 + size / 2 + ry;
			final int sz = size;
			final int n = seats.size();
			ellipse(n, cx, cy, rx, ry, (i, x, y) -> {
				JsonObject s = seats.get(i);
				TokenWidget t = new TokenWidget(x, y, sz, ClientState.str(s, "name"),
					i == me && role != null ? ClientState.str(role, "id") : null,
					i == me && role != null ? ClientState.str(role, "team") : null,
					ClientState.bool(s, "alive"), false, null);
				if (!ClientState.bool(s, "alive") && ClientState.bool(s, "ghostVote")) {
					t.marker("ghost");
				}
				t.labelOutward(-Math.PI / 2 + 2 * Math.PI * i / Math.max(1, n));
				token(t);
			});
		}
	}

	private static boolean inGameView() {
		return !ClientState.phase().equals("LOBBY");
	}

	/** Lignes de journal affichées : une seule si la fenêtre est basse. */
	private int feedLines() {
		return ph < 230 ? 1 : 2;
	}

	private int feedHeight() {
		return feedLines() * 10 + 4;
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject v = view();
		String phase = ClientState.phase();
		boolean night = phase.equals("NIGHT");
		Theme.image(g, Theme.tex(night ? "moon" : "sun"), px + 12, py + 12, 12, 12, Theme.HUD_TEX, Theme.HUD_TEX);
		Theme.ink(g, font, phaseLabel(), px + 28, py + 14, Theme.WINE);

		JsonObject role = ClientState.obj(v, "role");
		int x = px + 14;
		int y = py + 32;
		if (role != null) {
			Theme.token(g, x + 18, y + 18, 36, ClientState.str(role, "id"), ClientState.str(role, "team"), true, false, false);
			Theme.ink(g, font, ClientState.str(role, "name"), x + 42, y + 8, Theme.INK);
			boolean evil = "EVIL".equals(ClientState.str(role, "team"));
			Theme.ink(g, font, (evil ? "Camp du mal" : "Camp du bien"), x + 42, y + 20, evil ? Theme.EVIL : Theme.GOOD);
			Theme.paragraph(g, font, ClientState.str(role, "text"), x, y + 42, 172, Theme.INK,
				buttonsTop - feedHeight() - 6 - (y + 42));
		} else {
			Theme.ink(g, font, phase.equals("LOBBY") ? "Inscrits :" : "Tu observes la partie.", x, y, Theme.INK);
			List<String> lobby = strings(v, "lobby");
			for (int i = 0; i < Math.min(lobby.size(), 12); i++) {
				Theme.ink(g, font, "• " + lobby.get(i), x + 4 + (i / 6) * 86, y + 12 + (i % 6) * 10, Theme.WINE);
			}
			String st = ClientState.str(v, "storyteller");
			String teller = "Conteur : " + (st == null ? "automatique" : st);
			if (phase.equals("LOBBY")) {
				// Au salon, sur la ligne du haut : le journal, qui remonte quand les boutons prennent quatre rangées, ne le recouvre pas.
				Theme.ink(g, font, teller, px + pw - 14 - font.width(teller), py + 14, Theme.ASH);
			} else {
				Theme.ink(g, font, teller, x, y + 76, Theme.ASH);
			}
		}

		// Journal des dernières annonces
		List<String> feed = strings(v, "feed");
		int fy = buttonsTop - feedHeight();
		if (inGameView() && !feed.isEmpty()) {
			// En partie : la dernière annonce, repliée dans la colonne de gauche (le cercle est à droite).
			Theme.paragraph(g, font, feed.get(feed.size() - 1), px + 14, fy, 176, Theme.ASH, feedHeight() - 2);
		} else {
			for (int i = Math.max(0, feed.size() - feedLines()); i < feed.size(); i++) {
				String line = feed.get(i);
				if (font.width(line) > pw - 30) {
					line = font.plainSubstrByWidth(line, pw - 34) + "…";
				}
				Theme.ink(g, font, line, px + 14, fy, Theme.ASH);
				fy += 10;
			}
		}
	}
}
