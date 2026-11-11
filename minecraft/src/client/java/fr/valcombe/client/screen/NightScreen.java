package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * Nuit pas à pas : l'ordre de la nuit à gauche, la carte de l'étape en cours à droite
 * (qui se réveille, ce qu'il faut faire et dire, les mises en garde), et les actions à la main.
 */
public class NightScreen extends BaseScreen {
	public NightScreen() {
		super("Nuit pas à pas");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {440, 290};
	}

	private static String stepPlayer() {
		JsonObject ns = ClientState.obj(view(), "nightStep");
		String who = ns == null ? null : ClientState.str(ns, "player");
		return who == null || who.isEmpty() ? null : who;
	}

	@Override
	protected void build() {
		button(px + pw - 100, py + ph - 26, 86, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
		if (!ClientState.phase().equals("NIGHT")) {
			return; // le jour s'est levé : il ne reste que le retour au pupitre
		}
		String who = stepPlayer();
		// Trois colonnes de boutons, de même largeur, sous la carte de l'étape.
		int x = px + 150;
		int bw = (pw - 164 - 8) / 3;
		int y = py + ph - 94;
		boolean paused = ClientState.bool(view(), "paused");
		button(x, y, bw, "◀ Précédente", () -> send("vc precedent"));
		button(x + bw + 4, y, bw, "Suivante ▶", () -> send("vc suivant"));
		button(x + 2 * (bw + 4), y, bw, paused ? "Reprendre" : "Pause", () -> send("vc pause"));
		y += 22;
		if (who != null) {
			boolean awake = strings(view(), "awake").contains(who);
			button(x, y, bw, awake ? "Rendormir" : "Réveiller", () -> send((awake ? "vc rendormir " : "vc reveiller ") + who));
			button(x + bw + 4, y, bw, "Demander", () -> send("vc demander " + who + " 1"));
			button(x + 2 * (bw + 4), y, bw, "Montrer…",
				() -> minecraft.gui.setScreen(new ChatScreen("/vc montrer " + who + " ", false)));
			y += 22;
			button(x, y, bw, "Rappels…", () -> minecraft.gui.setScreen(new TokenScreen(who, true)));
			button(x + bw + 4, y, bw, "Sa fiche", () -> minecraft.gui.setScreen(new SeatScreen(who)));
		}
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject v = view();
		if (!ClientState.phase().equals("NIGHT")) {
			Theme.ink(g, font, "Le jour s'est levé.", px + 14, py + 14, Theme.WINE);
			return;
		}
		// Ordre de la nuit
		int x = px + 14;
		int y = py + 14;
		Theme.ink(g, font, "Ordre de la nuit " + ClientState.num(v, "night", 0), x, y, Theme.WINE);
		y += 13;
		List<JsonObject> order = objects(v, "nightOrder");
		int max = (ph - 44) / 10;
		int current = 0;
		for (int i = 0; i < order.size(); i++) {
			if (ClientState.num(order.get(i), "state", 1) == 0) {
				current = i;
			}
		}
		int from = Math.max(0, Math.min(order.size() - max, current - max / 2));
		for (int i = from; i < order.size() && i < from + max; i++) {
			JsonObject e = order.get(i);
			int state = ClientState.num(e, "state", 1);
			String who = ClientState.str(e, "player");
			String line = (state == 0 ? "▶ " : state < 0 ? "✔ " : "· ") + ClientState.str(e, "title")
				+ (who == null || who.isEmpty() ? "" : " — " + who);
			if (font.width(line) > 128) {
				line = font.plainSubstrByWidth(line, 124) + "…";
			}
			if (state == 0) {
				g.fill(x - 2, y - 1, x + 130, y + font.lineHeight, 0x30B08D57);
			}
			Theme.ink(g, font, line, x, y, state == 0 ? Theme.WINE : state < 0 ? Theme.ASH : Theme.INK);
			y += 10;
		}
		if (order.isEmpty()) {
			Theme.ink(g, font, "Plus aucun réveil.", x, y, Theme.ASH);
		}

		// Carte de l'étape
		int cx = px + 150;
		int cw = pw - 164;
		int cy = py + 14;
		JsonObject ns = ClientState.obj(v, "nightStep");
		if (ns == null) {
			Theme.paragraph(g, font, "Aucune étape en cours : la nuit s'achève, ou le prochain réveil arrive.", cx, cy, cw, Theme.ASH);
			return;
		}
		String who = ClientState.str(ns, "player");
		Theme.ink(g, font, ClientState.str(ns, "title") + (who == null || who.isEmpty() ? "" : " — " + who), cx, cy, Theme.WINE);
		cy += 12;
		String wait = ClientState.str(ns, "waiting");
		if (wait != null && !wait.isEmpty()) {
			Theme.ink(g, font, "On attend : " + wait, cx, cy, Theme.BRONZE);
			cy += 12;
		}
		if (ClientState.bool(v, "paused")) {
			Theme.ink(g, font, "Partie en pause.", cx, cy, Theme.BLOOD);
			cy += 12;
		}
		for (String a : strings(ns, "alerts")) {
			cy += Theme.paragraph(g, font, "⚠ " + a, cx, cy, cw, Theme.BLOOD) + 2;
		}
		cy += 2;
		Theme.ink(g, font, "À faire et à dire", cx, cy, Theme.INK);
		cy += 11;
		int room = py + ph - 100 - cy;
		Theme.paragraph(g, font, ClientState.str(ns, "todo"), cx, cy, cw, Theme.INK, Math.max(10, room));
	}
}
