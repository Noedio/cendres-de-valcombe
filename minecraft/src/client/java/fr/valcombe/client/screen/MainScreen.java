package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdFonts;
import fr.valcombe.client.hd.HdGlyphs;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdText;
import fr.valcombe.client.hd.HdToken;
import fr.valcombe.client.hd.RingLayout;
import fr.valcombe.client.hd.SeatRing;
import fr.valcombe.client.ui.Theme;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.screens.ChatScreen;

/**
 * Menu principal : salon, carte de rôle, cercle, actions du jour, journal.
 *
 * <p>En partie, le panneau se partage en trois : la carte de rôle et la dernière annonce dans une colonne à gauche,
 * le cercle des joueurs à droite (RingLayout : aucun nom n'en chevauche un autre, de 5 à 20 joueurs), les actions en
 * bas, sur trois colonnes ou quatre si cela fait une rangée de moins. Une action dont le libellé ne tient pas dans
 * sa case en prend deux plutôt que d'être coupée.
 */
public class MainScreen extends HdScreen {
	/** Largeur de la colonne de gauche en partie (carte de rôle, annonce), en unités. */
	private static final int LEFT_W = 140;
	/** Hauteur d'un bouton d'action et pas d'une rangée à l'autre. */
	private static final int ROW = 22;
	private int buttonsTop;
	/** Style des libellés des actions (un peu plus petit quand elles vont sur quatre colonnes). */
	private HdStyle actionStyle = HdStyle.BOUTON;

	public MainScreen() {
		super("Les Cendres de Valcombe");
	}

	@Override
	protected int[] panelSize() {
		// En partie, le cercle prend la droite du panneau : il est plus large.
		return inGameView() ? new int[] {460, 300} : new int[] {380, 260};
	}

	private record Action(String label, Runnable run) {
	}

	/** Une action placée : colonne, rangée et nombre de cases. */
	private record Cell(Action action, int col, int row, int span) {
	}

	@Override
	protected void build() {
		JsonObject v = view();
		boolean inGame = inGameView();
		JsonObject role = ClientState.obj(v, "role");
		List<Action> actions = actions(v, inGame, role);

		// Trois colonnes, ou quatre (libellés un peu plus petits) si elles font gagner une rangée au cercle.
		List<Cell> cells = flow(actions, 3, HdStyle.BOUTON);
		int cols = 3;
		actionStyle = HdStyle.BOUTON;
		if (inGame) {
			HdStyle small = HdStyle.BOUTON.size(10.5f);
			List<Cell> four = flow(actions, 4, small);
			if (rows(four) < rows(cells)) {
				cells = four;
				cols = 4;
				actionStyle = small;
			}
		}
		int margin = cols == 4 ? 10 : 12;
		int gap = cols == 4 ? 4 : 6;
		int cw = (pw - 2 * margin - (cols - 1) * gap) / cols;
		buttonsTop = py + ph - 28 - (rows(cells) - 1) * ROW;
		int scale = Hd.guiScale();
		for (Cell c : cells) {
			int w = c.span() * cw + (c.span() - 1) * gap;
			String label = c.action().label();
			// Un libellé raccourci (« … ») sur le bouton se lit en entier dans son infobulle.
			boolean cut = HdFonts.width(actionStyle, scale, label) > (w - 10) * scale;
			button(px + margin + c.col() * (cw + gap), buttonsTop + c.row() * ROW, w, label, c.action().run()).style(actionStyle)
				.tooltip(cut ? label : null);
		}

		if (inGame) {
			// Carte de rôle : le jeton porte le texte du rôle en infobulle (la colonne est étroite pour le lire en entier).
			if (role != null) {
				HdToken card = new HdToken(px + 14 + 16, py + 32 + 16, 32, "", ClientState.str(role, "id"), ClientState.str(role, "team"),
					true, false, null);
				card.setMessage(net.minecraft.network.chat.Component.literal(String.valueOf(ClientState.str(role, "name"))));
				token(card.tooltip(ClientState.str(role, "name") + "\n" + ClientState.str(role, "text")));
			}
			placeCircle(v, role);
		}
	}

	/** Les actions du menu, dans l'ordre. */
	private List<Action> actions(JsonObject v, boolean inGame, JsonObject role) {
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
		if (ClientState.obj(v, "parties") != null) {
			// Au hub (hors des mondes de partie) : le tableau des parties, pour en rejoindre ou en regarder une.
			actions.add(new Action("Parties", () -> minecraft.gui.setScreen(new PartiesScreen())));
		}
		if (ClientState.bool(ClientState.obj(v, "partie"), "regarde")) {
			// Dans les gradins d'une partie : en sortir, retour au hub.
			actions.add(new Action("Arrêter de regarder", () -> {
				send("vc partie sortir");
				onClose();
			}));
		}
		JsonObject worlds = ClientState.obj(v, "mondes");
		if (worlds != null && ClientState.str(worlds, "bloque") == null) {
			// Le hub, le village de la partie et les autres mondes de partie (tous les mondes pour un opérateur) ; pas pendant sa partie.
			actions.add(new Action("Mondes", () -> minecraft.gui.setScreen(new WorldsScreen())));
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
			// Opérateur : cartes, structures, parties, rôles et scénarios, sans passer par le navigateur.
			actions.add(new Action("Atelier", () -> minecraft.gui.setScreen(fr.valcombe.client.screen.atelier.AtelierPage.open())));
		}
		return actions;
	}

	/**
	 * Range les actions dans une grille de cols colonnes, dans l'ordre : une action dont le libellé ne tient pas dans
	 * une case en prend deux (elle passe à la rangée suivante s'il ne reste qu'une case).
	 */
	private List<Cell> flow(List<Action> actions, int cols, HdStyle style) {
		int margin = cols == 4 ? 10 : 12;
		int gap = cols == 4 ? 4 : 6;
		int cw = (pw - 2 * margin - (cols - 1) * gap) / cols;
		int scale = Hd.guiScale();
		List<Cell> out = new ArrayList<>();
		int col = 0;
		int row = 0;
		for (Action a : actions) {
			int span = HdFonts.width(style, scale, a.label()) > (cw - 10) * scale ? 2 : 1;
			if (col + span > cols) {
				col = 0;
				row++;
			}
			out.add(new Cell(a, col, row, span));
			col += span;
			if (col >= cols) {
				col = 0;
				row++;
			}
		}
		return out;
	}

	private static int rows(List<Cell> cells) {
		return cells.isEmpty() ? 1 : cells.getLast().row() + 1;
	}

	/**
	 * Cercle des joueurs (jetons fermés : on ne voit pas les rôles des autres, sauf le sien), à droite de la colonne
	 * de gauche, entre le bandeau du titre et les actions.
	 */
	private void placeCircle(JsonObject v, JsonObject role) {
		List<JsonObject> seats = objects(v, "seats");
		if (seats.isEmpty()) {
			return;
		}
		List<String> names = new ArrayList<>();
		for (JsonObject s : seats) {
			String name = ClientState.str(s, "name");
			names.add(name == null ? "" : name);
		}
		int me = ClientState.num(v, "mySeat", -1);
		int x = px + 14 + LEFT_W + 10;
		int y = py + 12;
		RingLayout.Result ring = SeatRing.layout(names,
			SeatRing.spec(x, y, px + pw - 10 - x, buttonsTop - 4 - y).tokens(22, 10).aspect(2.6, 1.25));
		for (int i = 0; i < seats.size(); i++) {
			JsonObject s = seats.get(i);
			RingLayout.Seat seat = ring.seats().get(i);
			boolean mine = i == me && role != null;
			HdToken t = new HdToken(seat.cx(), seat.cy(), ring.size(), names.get(i), mine ? ClientState.str(role, "id") : null,
				mine ? ClientState.str(role, "team") : null, ClientState.bool(s, "alive"), false, null).seat(seat);
			if (!ClientState.bool(s, "alive") && ClientState.bool(s, "ghostVote")) {
				t.marker("ghost");
			}
			token(t);
		}
	}

	private static boolean inGameView() {
		return !ClientState.phase().equals("LOBBY");
	}

	/** Lignes de journal affichées au salon : une seule si la fenêtre est basse. */
	private int feedLines() {
		return ph < 230 ? 1 : 2;
	}

	private int feedHeight() {
		return feedLines() * HdText.PAS + 4;
	}

	@Override
	protected void drawHd(Hd hd) {
		JsonObject v = view();
		String phase = ClientState.phase();
		boolean night = phase.equals("NIGHT");
		HdStyle dense = HdText.SERRE;
		int pitch = hd.u(HdText.PAS);
		// Phase : lune ou soleil, puis son nom, en intertitre.
		hd.image(Theme.tex(night ? "moon" : "sun"), hd.px(px + 12), hd.px(py + 11), hd.u(13), hd.u(13), -1);
		hd.textMiddle(HdStyle.SECTION, hd.ellipsize(HdStyle.SECTION, phaseLabel(), hd.px(LEFT_W - 18)), hd.px(px + 29), hd.px(py + 11),
			hd.u(13), Hd.WINE);
		if (inGameView()) {
			drawGameColumn(hd, v, dense, pitch);
		} else {
			drawLobby(hd, v, dense);
		}
	}

	/** En partie : carte de rôle, texte du rôle s'il a la place, puis la dernière annonce, dans la colonne de gauche. */
	private void drawGameColumn(Hd hd, JsonObject v, HdStyle dense, int pitch) {
		JsonObject role = ClientState.obj(v, "role");
		int x = px + 14;
		int y = py + 32;
		int bottom = buttonsTop - 4;
		// La dernière annonce, en bas de la colonne : deux lignes, trois si la place le permet.
		List<String> feed = strings(v, "feed");
		int feedLines = feed.isEmpty() ? 0 : bottom - y > 120 ? 3 : 2;
		int feedTop = bottom - feedLines * HdText.PAS;
		if (!feed.isEmpty()) {
			HdText.paragraph(hd, dense, feed.get(feed.size() - 1), hd.px(x), hd.px(feedTop), hd.px(LEFT_W), Hd.INK_SOFT, pitch,
				feedLines * pitch);
		}
		if (role != null) {
			// Le jeton (avec le texte du rôle en infobulle) est un widget ; ici, le nom et le camp à sa droite.
			int tx = x + 38;
			int nameW = LEFT_W - 38;
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, ClientState.str(role, "name"), hd.px(nameW)), hd.px(tx), hd.px(y + 3), Hd.INK);
			boolean evil = "EVIL".equals(ClientState.str(role, "team"));
			hd.text(dense, evil ? "Camp du mal" : "Camp du bien", hd.px(tx), hd.px(y + 18), evil ? Theme.EVIL : Theme.GOOD);
			// Le texte du rôle, s'il tient sur deux lignes au moins entre la carte et l'annonce (sinon : infobulle du jeton).
			int textTop = y + 38;
			int room = feedTop - 4 - textTop;
			if (room >= 2 * HdText.PAS) {
				HdText.paragraph(hd, dense, ClientState.str(role, "text"), hd.px(x), hd.px(textTop), hd.px(LEFT_W), Hd.INK, pitch,
					hd.px(room));
			}
		} else {
			hd.paragraph(HdStyle.FORT, "Tu observes la partie.", hd.px(x), hd.px(y - 2), hd.px(LEFT_W), Hd.INK);
			String st = ClientState.str(v, "storyteller");
			HdText.line(hd, dense, "Conteur : " + (st == null ? "automatique" : st), hd.px(x), hd.px(y + 14), hd.px(LEFT_W), Hd.INK_SOFT);
		}
	}

	/** Au salon : les inscrits, le Conteur, puis le journal au-dessus des actions. */
	private void drawLobby(Hd hd, JsonObject v, HdStyle dense) {
		int x = px + 14;
		int y = py + 32;
		hd.text(HdStyle.FORT, "Inscrits :", hd.px(x), hd.px(y - 2), Hd.INK);
		List<String> lobby = strings(v, "lobby");
		HdStyle names = HdStyle.TEXTE.size(9.5f);
		// Les inscrits en colonnes de six, autant de colonnes que la largeur en laisse ; le dernier dit combien il en reste.
		int rows = 6;
		int colW = 86;
		int cols = Math.max(1, (pw - 28) / colW);
		int capacity = rows * cols;
		for (int i = 0; i < Math.min(lobby.size(), capacity); i++) {
			boolean last = i == capacity - 1 && lobby.size() > capacity;
			String text = last ? "et " + (lobby.size() - capacity + 1) + " autres" : lobby.get(i);
			int lx = hd.px(x + 4 + (i / rows) * colW);
			int ly = hd.px(y + 12 + (i % rows) * 10);
			HdGlyphs.text(hd, names, "• " + hd.ellipsize(names, text, hd.u(colW - 10)), lx, ly, last ? Hd.INK_SOFT : Hd.WINE);
		}
		String st = ClientState.str(v, "storyteller");
		// Sur la ligne du haut : le journal, qui remonte quand les actions prennent quatre rangées, ne le recouvre pas.
		hd.textRight(dense, "Conteur : " + (st == null ? "automatique" : st), hd.px(px + pw - 14), hd.px(py + 13), Hd.INK_SOFT);
		List<String> feed = strings(v, "feed");
		int fy = buttonsTop - feedHeight();
		for (int i = Math.max(0, feed.size() - feedLines()); i < feed.size(); i++) {
			HdText.line(hd, dense, feed.get(i), hd.px(px + 14), hd.px(fy), hd.px(pw - 30), Hd.INK_SOFT);
			fy += HdText.PAS;
		}
	}
}
