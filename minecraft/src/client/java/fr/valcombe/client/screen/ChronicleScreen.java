package fr.valcombe.client.screen;

import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdFonts;
import fr.valcombe.client.hd.HdList;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdText;
import fr.valcombe.client.ui.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Chronique d'une partie : nuits, morts, nominations et votes (molette pour défiler).
 * Sert à relire la dernière partie (« chronicle ») et, pour le Conteur, de journal de la partie
 * en cours (« journal »). Les boutons du bas n'affichent qu'une nuit ou qu'un jour à la fois.
 */
public class ChronicleScreen extends HdScreen {
	/** Une ligne affichée : un morceau de phrase déjà coupé à la largeur de la liste. */
	private record Line(String text, int color, boolean heading) {
	}

	/** Hauteur d'une ligne de la liste, en unités. */
	private static final int ROW = HdText.PAS;
	/** Marges de la liste (creux de parchemin) : intérieur, ascenseur, retrait du texte, en unités. */
	private static final int LIST_PAD = 3;
	private static final int LIST_BAR = 7;
	private static final int INDENT = 4;

	private final String source;
	private final List<Line> lines = new ArrayList<>();
	private final List<String> sections = new ArrayList<>();
	private HdList<Line> list;
	/** Défilement gardé d'une reconstruction à l'autre (le journal se reconstruit à chaque vue du serveur). */
	private double offset;
	/** Nuit ou jour affiché seul ; -1 : tout. */
	private int section = -1;

	public ChronicleScreen() {
		this("chronicle");
	}

	public ChronicleScreen(String source) {
		super(source.equals("journal") ? "Journal de la partie" : "Chronique de la partie");
		this.source = source;
	}

	@Override
	protected int[] panelSize() {
		return new int[] {420, 260};
	}

	private static boolean isHeading(String raw) {
		return raw.startsWith("La nuit ") || raw.startsWith("Le jour ") || raw.startsWith("Mise en place");
	}

	@Override
	protected void build() {
		if (list != null) {
			offset = list.scrollOffset();
		}
		lines.clear();
		sections.clear();
		// Les phrases sont coupées ici, en pixels réels, à la largeur du texte dans la liste (ascenseur compris).
		int scale = Hd.guiScale();
		int width = (pw - 24 - 2 * LIST_PAD - LIST_BAR - 2 * INDENT) * scale;
		int index = -1;
		for (String raw : strings(view(), source)) {
			boolean heading = isHeading(raw);
			if (heading) {
				index++;
				sections.add(raw.startsWith("Mise en place") ? "Mise en place" : raw.replace(" tombe.", "").replace(" se lève.", ""));
			}
			if (section >= 0 && index != section) {
				continue;
			}
			int color = heading ? Theme.WINE
				: raw.contains("meurt") || raw.contains("exécuté") ? Theme.BLOOD
				: raw.contains("vote") || raw.contains("voix") || raw.contains("accuse") || raw.contains("nomine") ? Hd.BRONZE_INK
				: raw.startsWith("Le Conteur") ? Theme.ASH
				: Theme.INK;
			for (String part : HdFonts.wrap(style(heading), scale, raw, width)) {
				lines.add(new Line(part, color, heading));
			}
		}
		if (section >= sections.size()) {
			section = -1;
		}
		list = addRenderableWidget(new HdList<Line>(px + 12, py + 10, pw - 24, ph - 44, ROW, Line::text)
			.empty(source.equals("journal") ? "Rien encore dans le journal." : "Aucune partie terminée pour l'instant.")
			.row(this::drawLine)
			.items(lines));
		list.scrollBy(offset);
		int by = py + ph - 26;
		if (!sections.isEmpty()) {
			button(px + 14, by, 46, "Tout", () -> show(-1));
			button(px + 64, by, 22, "◀", () -> show(section <= 0 ? sections.size() - 1 : section - 1));
			button(px + 90, by, 22, "▶", () -> show(section + 1 >= sections.size() ? 0 : section + 1));
		}
		button(px + pw - 94, by, 80, "Retour",
			() -> minecraft.gui.setScreen(source.equals("journal") ? new StorytellerScreen() : new MainScreen()));
	}

	private static HdStyle style(boolean heading) {
		return heading ? HdStyle.FORT.size(10) : HdText.SERRE;
	}

	/** Une ligne : un titre de nuit ou de jour sur un fond doré, sinon la phrase dans sa couleur. */
	private void drawLine(Hd hd, Line l, int index, int x, int y, int w, int h, boolean hovered, boolean selected) {
		if (l.heading()) {
			hd.fill(x, y, x + w, y + h, 0x30B08D57);
		}
		HdStyle st = style(l.heading());
		hd.textMiddle(st, l.text(), x + hd.u(INDENT), y, h, l.color());
	}

	private void show(int which) {
		section = which;
		offset = 0;
		list = null;
		rebuildSoon();
	}

	/** Lignes visibles à la fois dans la liste. */
	private int visible() {
		return Math.max(1, (ph - 44 - 2 * LIST_PAD) / ROW);
	}

	@Override
	protected void drawHd(Hd hd) {
		if (lines.isEmpty() || list == null) {
			return;
		}
		// Où l'on en est : la section affichée, et les lignes visibles quand tout ne tient pas.
		int first = (int) Math.floor(list.scrollOffset() / ROW);
		int end = Math.min(lines.size(), first + visible());
		String where = section < 0 ? "Tout" : sections.get(section);
		String hint = where + (lines.size() > visible() ? ", lignes " + (first + 1) + " à " + end + " sur " + lines.size() + " (molette)" : "");
		HdText.line(hd, HdText.SERRE, hint, hd.px(px + 120), hd.px(py + ph - 26) + (hd.u(18) - hd.lineHeight(HdText.SERRE)) / 2,
			hd.px(pw - 120 - 100), Hd.INK_SOFT);
	}
}
