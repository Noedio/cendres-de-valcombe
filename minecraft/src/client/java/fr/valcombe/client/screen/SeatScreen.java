package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;

/** Fiche d'un siège pour le Conteur : rôle, note, et retouches possibles. */
public class SeatScreen extends BaseScreen {
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

	@Override
	protected void build() {
		JsonObject row = row();
		boolean ready = ClientState.phase().equals("READY");
		Role role = row == null ? null : Role.valueOf(ClientState.str(ClientState.obj(row, "role"), "id"));
		int bx = px + pw - 150;
		int y = py + 14;
		if (ready) {
			button(bx, y, 136, "Changer le rôle", () -> minecraft.gui.setScreen(new RolePickerScreen(name, null)));
			y += 22;
		}
		if (role != null && !role.evil()) {
			button(bx, y, 136, "Faux présage", () -> send("vc presage " + name));
			y += 22;
			button(bx, y, 136, "Meurt pour le Châtelain", () -> send("vc consigne chatelain " + name));
			y += 22;
		}
		if (role != null && role.type == fr.valcombe.rules.RoleType.SBIRE) {
			button(bx, y, 136, "Successeur de la Goule", () -> send("vc consigne successeur " + name));
			y += 22;
		}
		button(bx, y, 66, "Note", () -> minecraft.gui.setScreen(new ChatScreen("/vc note " + name + " ", false)));
		button(bx + 70, y, 66, "Rappels…", () -> minecraft.gui.setScreen(new TokenScreen(name, false)));
		y += 22;
		String phase = ClientState.phase();
		if (phase.equals("DAY") || phase.equals("NIGHT")) {
			boolean alive = isAlive();
			button(bx, y, 66, alive ? "Tuer" : "Ressusciter",
				() -> send((alive ? "vc tuer " : "vc ressusciter ") + name));
			button(bx + 70, y, 66, "Empoisonner", () -> send("vc empoisonner " + name));
			y += 22;
			button(bx, y, 136, "Changer le rôle", () -> minecraft.gui.setScreen(new RolePickerScreen(name, null)));
			y += 22;
			// À la main : lui demander un choix, lui montrer une information, le réveiller la nuit.
			button(bx, y, 66, "Demander", () -> send("vc demander " + name + " 1"));
			button(bx + 70, y, 66, "Montrer…", () -> minecraft.gui.setScreen(new ChatScreen("/vc montrer " + name + " ", false)));
			y += 22;
			if (phase.equals("NIGHT")) {
				boolean awake = strings(view(), "awake").contains(name);
				button(bx, y, 136, awake ? "Rendormir" : "Réveiller", () -> send((awake ? "vc rendormir " : "vc reveiller ") + name));
				y += 22;
			} else {
				button(bx, y, 136, "Lui donner la parole", () -> send("vc parole donner " + name));
				y += 22;
			}
			if (strings(view(), "absents").contains(name) && !isStandIn()) {
				button(bx, y, 136, "Le faire relayer", () -> send("vc relayer " + name));
			}
		}
		button(bx, py + ph - 26, 136, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject row = row();
		if (row == null) {
			Theme.ink(g, font, "Grimoire indisponible.", px + 16, py + 20, Theme.ASH);
			return;
		}
		JsonObject role = ClientState.obj(row, "role");
		JsonObject believed = ClientState.obj(row, "believed");
		int x = px + 16;
		Theme.token(g, x + 22, py + 36, 44, ClientState.str(role, "id"), ClientState.str(role, "team"), true, false, false);
		Theme.ink(g, font, ClientState.str(role, "name"), x + 50, py + 26, Theme.INK);
		if (believed != null) {
			Theme.paragraph(g, font, "croit être " + ClientState.str(believed, "name"), x + 50, py + 38, pw - 230, Theme.WINE);
		}
		int y = py + 66;
		y += Theme.paragraph(g, font, ClientState.str(role, "text"), x, y, pw - 180, Theme.INK) + 6;
		String note = ClientState.str(row, "note");
		if (note != null) {
			y += Theme.paragraph(g, font, "Note : " + note, x, y, pw - 180, Theme.BLOOD) + 4;
		}
		List<String> marks = strings(row, "reminders");
		if (!marks.isEmpty()) {
			y += Theme.paragraph(g, font, "Rappels : " + String.join(" · ", marks), x, y, pw - 180, Theme.BRONZE) + 4;
		}
		JsonObject answers = ClientState.obj(view(), "answers");
		List<String> said = strings(answers, name);
		if (!said.isEmpty()) {
			y += Theme.paragraph(g, font, "A désigné pour toi : " + String.join(", ", said), x, y, pw - 180, Theme.WINE) + 4;
		}
		if (isStandIn()) {
			Theme.paragraph(g, font, "Absent : un joueur fictif tient sa place.", x, y, pw - 180, Theme.ASH);
		}
		JsonObject pol = ClientState.obj(view(), "policies");
		if (name.equals(ClientState.str(pol, "mayorTarget"))) {
			Theme.ink(g, font, "Mourra à la place du Châtelain.", x, py + ph - 30, Theme.ASH);
		}
		if (name.equals(ClientState.str(pol, "successor"))) {
			Theme.ink(g, font, "Succédera à la Goule.", x, py + ph - 20, Theme.ASH);
		}
	}
}
