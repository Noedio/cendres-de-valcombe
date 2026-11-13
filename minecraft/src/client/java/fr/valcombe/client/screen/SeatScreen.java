package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdToken;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.screens.ChatScreen;

/**
 * Fiche d'un siège pour le Conteur : rôle, note, et retouches possibles. En haute résolution : grand jeton net,
 * texte du rôle lisible, boutons resserrés au besoin pour que toutes les retouches tiennent dans le panneau.
 */
public class SeatScreen extends HdScreen {
	/** Un bouton de la colonne ; une rangée en a un ou deux. */
	private record Act(String label, Runnable run) {
	}

	/** Texte du rôle, note, rappels : un peu plus petit que le texte courant, pour tenir dans la fiche. */
	private static final HdStyle BODY = HdStyle.TEXTE.size(10);
	private static final int COL_W = 136;
	private final String name;

	public SeatScreen(String name) {
		super(name);
		this.name = name;
	}

	private JsonObject row() {
		List<JsonObject> seats = objects(view(), "seats");
		List<JsonObject> grim = objects(view(), "grimoire");
		for (int i = 0; i < seats.size() && i < grim.size(); i++) {
			if (name.equals(ClientState.str(seats.get(i), "name"))) {
				return grim.get(i);
			}
		}
		return null;
	}

	private boolean isStandIn() {
		for (JsonObject s : objects(view(), "seats")) {
			if (name.equals(ClientState.str(s, "name"))) {
				return ClientState.bool(s, "standIn");
			}
		}
		return false;
	}

	private boolean isAlive() {
		for (JsonObject s : objects(view(), "seats")) {
			if (name.equals(ClientState.str(s, "name"))) {
				return ClientState.bool(s, "alive");
			}
		}
		return true;
	}

	@Override
	protected int[] panelSize() {
		return new int[] {340, 280};
	}

	/** Abscisse de la colonne de boutons. */
	private int colX() {
		return px + pw - COL_W - 14;
	}

	@Override
	protected void build() {
		JsonObject row = row();
		boolean ready = ClientState.phase().equals("READY");
		Role role = row == null ? null : Role.valueOf(ClientState.str(ClientState.obj(row, "role"), "id"));
		List<List<Act>> rows = new ArrayList<>();
		if (ready) {
			rows.add(List.of(new Act("Changer le rôle", () -> minecraft.gui.setScreen(new RolePickerScreen(name, null)))));
		}
		if (role != null && !role.evil()) {
			rows.add(List.of(new Act("Faux présage", () -> send("vc presage " + name))));
			rows.add(List.of(new Act("Meurt pour le Châtelain", () -> send("vc consigne chatelain " + name))));
		}
		if (role != null && role.type == fr.valcombe.rules.RoleType.SBIRE) {
			rows.add(List.of(new Act("Successeur de la Goule", () -> send("vc consigne successeur " + name))));
		}
		rows.add(List.of(new Act("Note", () -> minecraft.gui.setScreen(new ChatScreen("/vc note " + name + " ", false))),
			new Act("Rappels…", () -> minecraft.gui.setScreen(new TokenScreen(name, false)))));
		String phase = ClientState.phase();
		if (phase.equals("READY") || phase.equals("DAY")) {
			boolean here = name.equals(ClientState.str(view(), "summoned"));
			rows.add(List.of(new Act(here ? "Renvoyer au village" : "Convoquer à l'écart",
				() -> send(here ? "vc convoquer fin" : "vc convoquer " + name))));
		}
		if (phase.equals("DAY") || phase.equals("NIGHT")) {
			boolean alive = isAlive();
			rows.add(List.of(new Act(alive ? "Tuer" : "Ressusciter", () -> send((alive ? "vc tuer " : "vc ressusciter ") + name)),
				new Act("Empoisonner", () -> send("vc empoisonner " + name))));
			rows.add(List.of(new Act("Changer le rôle", () -> minecraft.gui.setScreen(new RolePickerScreen(name, null)))));
			// À la main : lui demander un choix, lui montrer une information, le réveiller la nuit.
			rows.add(List.of(new Act("Demander", () -> send("vc demander " + name + " 1")),
				new Act("Montrer…", () -> minecraft.gui.setScreen(new ChatScreen("/vc montrer " + name + " ", false)))));
			if (phase.equals("NIGHT")) {
				boolean awake = strings(view(), "awake").contains(name);
				rows.add(List.of(new Act(awake ? "Rendormir" : "Réveiller", () -> send((awake ? "vc rendormir " : "vc reveiller ") + name))));
			} else {
				rows.add(List.of(new Act("Lui donner la parole", () -> send("vc parole donner " + name))));
			}
			if (strings(view(), "absents").contains(name) && !isStandIn()) {
				rows.add(List.of(new Act("Le faire relayer", () -> send("vc relayer " + name))));
			}
		}
		// Toutes les rangées tiennent au-dessus de « Retour » : 22 unités entre deux rangées si la place le permet,
		// 17 au plus serré (boutons de 16).
		int bx = colX();
		int top = py + 14;
		int avail = py + ph - 30 - top;
		int n = Math.max(1, rows.size());
		int step = Math.max(17, Math.min(22, avail / n));
		int h = Math.min(18, step - 1);
		int y = top;
		for (List<Act> r : rows) {
			if (r.size() == 1) {
				addRenderableWidget(new HdButton(bx, y, COL_W, h, r.get(0).label(), r.get(0).run()));
			} else {
				int half = (COL_W - 4) / 2;
				addRenderableWidget(new HdButton(bx, y, half, h, r.get(0).label(), r.get(0).run()));
				addRenderableWidget(new HdButton(bx + COL_W - half, y, half, h, r.get(1).label(), r.get(1).run()));
			}
			y += step;
		}
		button(bx, py + ph - 26, COL_W, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
		if (row != null) {
			// Grand jeton du rôle, en haut à gauche (dessiné seulement : la fiche n'a pas à se cliquer).
			JsonObject r = ClientState.obj(row, "role");
			addRenderableOnly(new HdToken(px + 16 + 22, py + 36, 44, "", ClientState.str(r, "id"), ClientState.str(r, "team"),
				isAlive(), false, null));
		}
	}

	@Override
	protected void drawHd(Hd hd) {
		JsonObject row = row();
		int x = px + 16;
		if (row == null) {
			hd.text(HdStyle.TEXTE, "Grimoire indisponible.", hd.px(x), hd.px(py + 20), Hd.INK_SOFT);
			return;
		}
		JsonObject role = ClientState.obj(row, "role");
		JsonObject believed = ClientState.obj(row, "believed");
		int textW = colX() - 12 - x;
		int headX = x + 52;
		int headW = colX() - 12 - headX;
		// Nom du rôle à droite du jeton, et ce que le joueur croit être (Somnambule, rôle prêté...).
		hd.text(HdStyle.SECTION.size(12), hd.ellipsize(HdStyle.SECTION.size(12), ClientState.str(role, "name"), hd.px(headW)),
			hd.px(headX), hd.px(py + 20), Hd.INK);
		if (believed != null) {
			hd.paragraph(BODY, "croit être " + ClientState.str(believed, "name"), hd.px(headX), hd.px(py + 38), hd.px(headW),
				Hd.WINE, 2 * hd.lineHeight(BODY));
		}
		hd.separator(hd.px(x), hd.px(py + 64), hd.px(textW));
		// Le texte de la fiche s'arrête au-dessus des deux mentions du bas (Châtelain, Goule).
		JsonObject pol = ClientState.obj(view(), "policies");
		boolean mayor = name.equals(ClientState.str(pol, "mayorTarget"));
		boolean successor = name.equals(ClientState.str(pol, "successor"));
		int limit = hd.px(py + ph - 10) - (mayor || successor ? 2 * hd.lineHeight(HdStyle.PETIT) + hd.u(4) : 0);
		int y = hd.px(py + 70);
		int gap = hd.u(4);
		y += hd.paragraph(BODY, ClientState.str(role, "text"), hd.px(x), y, hd.px(textW), Hd.INK, Math.max(0, limit - y)) + gap;
		String note = ClientState.str(row, "note");
		if (note != null && limit - y >= hd.lineHeight(BODY)) {
			y += hd.paragraph(BODY, "Note : " + note, hd.px(x), y, hd.px(textW), Theme.BLOOD, limit - y) + gap;
		}
		List<String> marks = strings(row, "reminders");
		if (!marks.isEmpty() && limit - y >= hd.lineHeight(BODY)) {
			y += hd.paragraph(BODY, "Rappels : " + String.join(" · ", marks), hd.px(x), y, hd.px(textW), Hd.BRONZE_INK, limit - y) + gap;
		}
		JsonObject answers = ClientState.obj(view(), "answers");
		List<String> said = strings(answers, name);
		if (!said.isEmpty() && limit - y >= hd.lineHeight(BODY)) {
			y += hd.paragraph(BODY, "A désigné pour toi : " + String.join(", ", said), hd.px(x), y, hd.px(textW), Hd.WINE, limit - y) + gap;
		}
		if (isStandIn() && limit - y >= hd.lineHeight(BODY)) {
			hd.paragraph(BODY, "Absent : un joueur fictif tient sa place.", hd.px(x), y, hd.px(textW), Hd.INK_SOFT, limit - y);
		}
		int lh = hd.lineHeight(HdStyle.PETIT);
		int by = hd.px(py + ph - 10) - lh;
		if (successor) {
			hd.text(HdStyle.PETIT, "Succédera à la Goule.", hd.px(x), by, Hd.INK_SOFT);
			by -= lh;
		}
		if (mayor) {
			hd.text(HdStyle.PETIT, "Mourra à la place du Châtelain.", hd.px(x), by, Hd.INK_SOFT);
		}
	}
}
