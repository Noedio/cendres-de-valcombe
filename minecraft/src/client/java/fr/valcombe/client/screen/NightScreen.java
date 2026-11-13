package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdText;
import fr.valcombe.client.ui.Theme;

import java.util.List;

import net.minecraft.client.gui.screens.ChatScreen;

/**
 * Nuit pas à pas : l'ordre de la nuit à gauche, la carte de l'étape en cours à droite
 * (qui se réveille, ce qu'il faut faire et dire, les mises en garde), et les actions à la main.
 */
public class NightScreen extends HdScreen {
	/** Texte de l'ordre de la nuit : une ligne toutes les 10 unités, comme avant. */
	private static final HdStyle ORDER = HdStyle.TEXTE.size(9.5f);
	private static final int ORDER_PITCH = 10;

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

	/** Haut des boutons d'action (unités) : la carte de l'étape s'arrête au-dessus. */
	private int actionsTop;

	@Override
	protected void build() {
		String who = stepPlayer();
		boolean night = ClientState.phase().equals("NIGHT");
		// Trois colonnes de boutons, de même largeur, sous la carte de l'étape, posées au plus bas : « Retour » prend la
		// case libre de la dernière rangée, et ce qu'il faut faire et dire garde toute la place au-dessus.
		int x = px + 150;
		int bw = (pw - 164 - 8) / 3;
		int rows = night && who != null ? 3 : 2;
		int y = py + ph - 8 - rows * 22 + 4;
		actionsTop = y;
		if (night && who != null) {
			button(x + 2 * (bw + 4), y + 44, bw, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
		} else {
			button(px + pw - 100, py + ph - 26, 86, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
		}
		if (!night) {
			return; // le jour s'est levé : il ne reste que le retour au pupitre
		}
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
	protected void drawHd(Hd hd) {
		JsonObject v = view();
		if (!ClientState.phase().equals("NIGHT")) {
			hd.text(HdStyle.SECTION, "Le jour s'est levé.", hd.px(px + 14), hd.px(py + 12), Hd.WINE);
			return;
		}
		HdStyle dense = HdText.SERRE;
		int pitch = hd.u(HdText.PAS);
		// Ordre de la nuit
		int x = px + 14;
		int y = py + 14;
		hd.text(HdStyle.SECTION, "Ordre de la nuit " + ClientState.num(v, "night", 0), hd.px(x), hd.px(y - 3), Hd.WINE);
		y += 13;
		List<JsonObject> order = objects(v, "nightOrder");
		int max = (ph - 44) / ORDER_PITCH;
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
			// En cours : flèche et surlignage ; fait : coche (dessinée) ; à venir : point.
			String line = (state == 0 ? "▶ " : state < 0 ? "✔ " : "· ") + ClientState.str(e, "title")
				+ (who == null || who.isEmpty() ? "" : " · " + who);
			int ly = hd.px(y) - hd.u(1.5);
			if (state == 0) {
				hd.fill(hd.px(x - 2), ly, hd.px(x + 130), ly + hd.u(ORDER_PITCH), 0x30B08D57);
			}
			HdText.line(hd, ORDER, line, hd.px(x), ly + (hd.u(ORDER_PITCH) - hd.lineHeight(ORDER)) / 2, hd.px(128),
				state == 0 ? Theme.WINE : state < 0 ? Theme.ASH : Theme.INK);
			y += ORDER_PITCH;
		}
		if (order.isEmpty()) {
			hd.text(dense, "Plus aucun réveil.", hd.px(x), hd.px(y), Hd.INK_SOFT);
		}

		// Carte de l'étape, à droite d'un filet
		int cx = px + 150;
		int cw = pw - 164;
		hd.fill(hd.px(cx - 4), hd.px(py + 12), hd.px(cx - 4) + Math.max(1, hd.u(0.5)), hd.px(py + ph - 12), 0x60B08D57);
		int cy = hd.px(py + 11);
		JsonObject ns = ClientState.obj(v, "nightStep");
		int px0 = hd.px(cx);
		int pwPx = hd.px(cw);
		if (ns == null) {
			HdText.paragraph(hd, dense, "Aucune étape en cours : la nuit s'achève, ou le prochain réveil arrive.", px0, cy, pwPx,
				Hd.INK_SOFT, pitch);
			return;
		}
		String who = ClientState.str(ns, "player");
		HdText.line(hd, HdStyle.FORT, ClientState.str(ns, "title") + (who == null || who.isEmpty() ? "" : " · " + who), px0, cy, pwPx,
			Hd.WINE);
		cy += hd.u(14);
		String wait = ClientState.str(ns, "waiting");
		if (wait != null && !wait.isEmpty()) {
			HdText.line(hd, dense, "On attend : " + wait, px0, cy, pwPx, Hd.BRONZE_INK);
			cy += pitch + hd.u(1);
		}
		if (ClientState.bool(v, "paused")) {
			hd.text(dense, "Partie en pause.", px0, cy, Theme.BLOOD);
			cy += pitch + hd.u(1);
		}
		int limit = hd.px(actionsTop - 4);
		for (String a : strings(ns, "alerts")) {
			cy += HdText.paragraph(hd, dense, "⚠ " + a, px0, cy, pwPx, Theme.BLOOD, pitch, Math.max(pitch, limit - cy)) + hd.u(2);
		}
		cy += hd.u(2);
		hd.text(HdStyle.FORT.size(10), "À faire et à dire", px0, cy, Hd.INK);
		cy += hd.u(13);
		HdText.paragraph(hd, dense, ClientState.str(ns, "todo"), px0, cy, pwPx, Hd.INK, pitch, Math.max(hd.lineHeight(dense), limit - cy));
	}
}
