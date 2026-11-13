package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.rules.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * Distribution des rôles par le Conteur. Au salon : imposer le rôle de qui l'on veut, les autres seront tirés
 * au hasard au lancement. Après le lancement, avant la première nuit : le rôle réel de chacun, à corriger d'un clic.
 * Chaque ligne montre le pictogramme du rôle ; la liste défile à la molette.
 */
public class DistributionScreen extends HdScreen {
	/** Une ligne : le joueur, le rôle affiché, et l'identifiant du rôle (null : tiré au hasard). */
	private record Line(String name, String shown, String roleId) {
	}

	private static final int ROW = 20;
	private static final HdStyle NOTE = HdStyle.TEXTE.size(10);
	/** Haut de la liste (unités depuis py), sous la consigne. */
	private static final int LIST_TOP = 30;
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

	/** Les lignes, dans l'ordre du cercle (ou des inscriptions au salon). */
	private static List<Line> rows() {
		List<Line> out = new ArrayList<>();
		JsonObject v = view();
		if (lobby()) {
			JsonObject pre = ClientState.obj(v, "presets");
			for (String name : strings(v, "lobby")) {
				String id = pre == null || !pre.has(name) ? null : pre.get(name).getAsString();
				out.add(new Line(name, id == null ? "au hasard" : label(id), id == null ? null : id.split("/")[0]));
			}
		} else {
			for (JsonObject row : objects(v, "grimoire")) {
				JsonObject role = ClientState.obj(row, "role");
				JsonObject believed = ClientState.obj(row, "believed");
				String shown = ClientState.str(role, "name") + (believed == null ? "" : " (croit " + ClientState.str(believed, "name") + ")");
				out.add(new Line(ClientState.str(row, "name"), shown, ClientState.str(role, "id")));
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

	/** Avertissement de répartition (avant la première nuit), au-dessus de « Retour ». */
	private static String warning() {
		String warn = ClientState.str(view(), "distributionWarning");
		return warn == null || warn.isEmpty() ? null : warn;
	}

	private int warnHeight() {
		String warn = warning();
		return warn == null ? 0 : StorytellerKit.textHeight(NOTE, warn, pw - 120, 3) + 4;
	}

	private int visible() {
		return Math.max(1, (py + ph - 30 - warnHeight() - (py + LIST_TOP)) / ROW);
	}

	@Override
	protected void build() {
		List<Line> rows = rows();
		scroll = Math.max(0, Math.min(scroll, rows.size() - visible()));
		boolean rail = rows.size() > visible();
		int right = px + pw - 14 - (rail ? 7 : 0);
		int y = py + LIST_TOP;
		for (int i = scroll; i < rows.size() && i < scroll + visible(); i++) {
			Line l = rows.get(i);
			int bx = px + 36;
			int bw = (lobby() ? right - 90 : right) - bx;
			button(bx, y, bw, l.name() + " : " + l.shown(),
				() -> minecraft.gui.setScreen(new RolePickerScreen(l.name(), null, true)));
			if (lobby()) {
				button(right - 86, y, 86, "Au hasard", () -> send("vc imposer " + l.name() + " aucun"));
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
	protected void drawHd(Hd hd) {
		int x = px + 14;
		int w = pw - 28;
		hd.text(NOTE, hd.ellipsize(NOTE, lobby() ? "Choisis le rôle de qui tu veux : les autres seront tirés au lancement."
			: "Avant la première nuit, tu peux changer le rôle de chacun.", hd.px(w)), hd.px(x), hd.px(py + 12), Hd.INK_SOFT);
		List<Line> rows = rows();
		if (rows.isEmpty()) {
			hd.paragraph(HdStyle.TEXTE, lobby() ? "Personne n'est encore inscrit." : "Seul le Conteur voit les rôles.",
				hd.px(x), hd.px(py + 40), hd.px(w), Hd.INK);
		}
		// Pictogramme du rôle de chaque ligne (un point d'interrogation pâle pour un rôle tiré au hasard).
		int y = py + LIST_TOP;
		int size = 18;
		for (int i = scroll; i < rows.size() && i < scroll + visible(); i++) {
			Line l = rows.get(i);
			int ix = hd.px(x + 2);
			int iy = hd.px(y);
			if (l.roleId() != null) {
				hd.image(StorytellerKit.roleIcon(l.roleId()), ix, iy, hd.px(size), hd.px(size), -1);
			} else {
				hd.disc(ix + hd.px(size) / 2, iy + hd.px(size) / 2, hd.u(7), 0x301B1214);
				hd.textCentered(HdStyle.FORT, "?", ix + hd.px(size) / 2, iy + (hd.px(size) - hd.lineHeight(HdStyle.FORT)) / 2,
					Hd.INK_SOFT);
			}
			y += ROW;
		}
		boolean rail = rows.size() > visible();
		if (rail) {
			StorytellerKit.rail(hd, px + pw - 18, py + LIST_TOP, visible() * ROW - 2, scroll, visible(), rows.size());
		}
		String warn = warning();
		if (warn != null) {
			int th = warnHeight() - 4;
			hd.paragraph(NOTE, warn, hd.px(x), hd.px(py + ph - 30) - hd.px(th), hd.px(pw - 120), Hd.WINE,
				3 * hd.lineHeight(NOTE));
		}
	}
}
