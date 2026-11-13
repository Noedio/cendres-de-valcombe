package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;

import java.util.List;

/**
 * Ordre de réveil, pour le Conteur : les rôles en jeu dans l'ordre où ils seront appelés (la nuit en cours,
 * ou la prochaine). Les flèches avancent ou reculent un rôle ; cela vaut pour les réveils à venir.
 * La liste défile à la molette quand elle dépasse le panneau.
 */
public class OrderScreen extends HdScreen {
	private static final int ROW = 18;
	/** Haut de la liste (unités depuis py) et marge intérieure de son creux. */
	private static final int LIST_TOP = 28;
	private static final int PAD = 3;
	/** Première ligne affichée (molette). */
	private int scroll;

	public OrderScreen() {
		super("Ordre de réveil");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {300, 250};
	}

	/** Hauteur du creux de la liste, au-dessus de « Retour ». */
	private int listHeight() {
		return py + ph - 32 - (py + LIST_TOP);
	}

	private int visible() {
		return Math.max(1, (listHeight() - 2 * PAD) / ROW);
	}

	@Override
	protected void build() {
		List<JsonObject> plan = objects(view(), "plan");
		scroll = Math.max(0, Math.min(scroll, plan.size() - visible()));
		int y = py + LIST_TOP + PAD;
		boolean rail = plan.size() > visible();
		int right = px + pw - 14 - PAD - (rail ? 6 : 0);
		for (int i = scroll; i < plan.size() && i < scroll + visible(); i++) {
			String role = ClientState.str(plan.get(i), "role");
			var up = addRenderableWidget(new StorytellerKit.ArrowButton(right - 50, y + 1, 24, ROW - 2, true,
				() -> send("vc ordre " + role + " haut")));
			var down = addRenderableWidget(new StorytellerKit.ArrowButton(right - 24, y + 1, 24, ROW - 2, false,
				() -> send("vc ordre " + role + " bas")));
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
	protected void drawHd(Hd hd) {
		List<JsonObject> plan = objects(view(), "plan");
		String when = ClientState.phase().equals("NIGHT") ? "Cette nuit et les suivantes" : "Prochaine nuit";
		hd.textCentered(HdStyle.FORT, when, hd.px(px + pw / 2), hd.px(py + 10), Hd.WINE);
		int x = px + 14;
		int w = pw - 28;
		int top = py + LIST_TOP;
		hd.inset(hd.px(x), hd.px(top), hd.px(w), hd.px(listHeight()));
		if (plan.isEmpty()) {
			hd.textCentered(HdStyle.TEXTE, "Aucun rôle ne se réveille.", hd.px(x + w / 2),
				hd.px(top + listHeight() / 2) - hd.lineHeight(HdStyle.TEXTE) / 2, Hd.INK_SOFT);
			return;
		}
		boolean rail = plan.size() > visible();
		int rowX = x + PAD;
		int rowW = w - 2 * PAD - (rail ? 6 : 0);
		int textRight = px + pw - 14 - PAD - (rail ? 6 : 0) - 54;
		int y = top + PAD;
		for (int i = scroll; i < plan.size() && i < scroll + visible(); i++) {
			JsonObject e = plan.get(i);
			StorytellerKit.band(hd, rowX, y, rowW, ROW, i);
			// Rang dans la nuit : une pastille numérotée, puis le rôle et le joueur qui se réveille.
			int cy = hd.px(y) + hd.px(ROW) / 2;
			int r = hd.u(6.5);
			int cx = hd.px(rowX + 9);
			hd.disc(cx, cy, r, Hd.WINE);
			String num = String.valueOf(i + 1);
			hd.textCentered(HdStyle.PETIT, num, cx, cy - hd.lineHeight(HdStyle.PETIT) / 2, Hd.PARCH);
			String title = ClientState.str(e, "title");
			String player = ClientState.str(e, "player");
			int tx = hd.px(rowX + 20);
			int room = hd.px(textRight) - tx;
			String shownTitle = hd.ellipsize(HdStyle.FORT, title, room);
			int tw = hd.textMiddle(HdStyle.FORT, shownTitle, tx, hd.px(y), hd.px(ROW), Hd.INK);
			if (player != null && !player.isEmpty() && tw + hd.u(14) < room) {
				String rest = hd.ellipsize(HdStyle.TEXTE, " · " + player, room - tw);
				hd.textMiddle(HdStyle.TEXTE, rest, tx + tw, hd.px(y), hd.px(ROW), Hd.INK_SOFT);
			}
			y += ROW;
		}
		if (rail) {
			StorytellerKit.rail(hd, px + pw - 14 - PAD - 4, top + PAD, listHeight() - 2 * PAD, scroll, visible(), plan.size());
		}
	}
}
