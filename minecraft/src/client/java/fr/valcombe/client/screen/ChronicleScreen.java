package fr.valcombe.client.screen;

import fr.valcombe.client.ui.Theme;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Chronique d'une partie : nuits, morts, nominations et votes (molette pour défiler).
 * Sert à relire la dernière partie (« chronicle ») et, pour le Conteur, de journal de la partie
 * en cours (« journal »). Les boutons du bas n'affichent qu'une nuit ou qu'un jour à la fois.
 */
public class ChronicleScreen extends BaseScreen {
	private record Line(FormattedCharSequence text, int color, boolean heading) {
	}

	private final String source;
	private final List<Line> lines = new ArrayList<>();
	private final List<String> sections = new ArrayList<>();
	private int scroll;
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
		lines.clear();
		sections.clear();
		int width = pw - 28;
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
				: raw.contains("vote") || raw.contains("voix") || raw.contains("accuse") || raw.contains("nomine") ? Theme.BRONZE
				: raw.startsWith("Le Conteur") ? Theme.ASH
				: Theme.INK;
			for (FormattedCharSequence part : font.split(Component.literal(raw), width)) {
				lines.add(new Line(part, color, heading));
			}
		}
		if (section >= sections.size()) {
			section = -1;
		}
		scroll = Math.max(0, Math.min(Math.max(0, lines.size() - visible()), scroll));
		int by = py + ph - 26;
		if (!sections.isEmpty()) {
			button(px + 14, by, 46, "Tout", () -> show(-1));
			button(px + 64, by, 22, "◀", () -> show(section <= 0 ? sections.size() - 1 : section - 1));
			button(px + 90, by, 22, "▶", () -> show(section + 1 >= sections.size() ? 0 : section + 1));
		}
		button(px + pw - 94, by, 80, "Retour",
			() -> minecraft.gui.setScreen(source.equals("journal") ? new StorytellerScreen() : new MainScreen()));
	}

	private void show(int which) {
		section = which;
		scroll = 0;
		rebuildWidgets();
	}

	private int visible() {
		return Math.max(1, (ph - 62) / (font.lineHeight + 2));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		scroll = Math.max(0, Math.min(Math.max(0, lines.size() - visible()), scroll - (int) Math.signum(dy) * 3));
		return true;
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (lines.isEmpty()) {
			Theme.ink(g, font, source.equals("journal") ? "Rien encore dans le journal." : "Aucune partie terminée pour l'instant.",
				px + 14, py + 18, Theme.ASH);
			return;
		}
		int y = py + 16;
		int end = Math.min(lines.size(), scroll + visible());
		for (int i = scroll; i < end; i++) {
			Line l = lines.get(i);
			if (l.heading()) {
				g.fill(px + 12, y - 1, px + pw - 12, y + font.lineHeight, 0x30B08D57);
			}
			g.text(font, l.text(), px + 14, y, l.color(), false);
			y += font.lineHeight + 2;
		}
		String where = section < 0 ? "Tout" : sections.get(section);
		String hint = where + (lines.size() > visible() ? ", lignes " + (scroll + 1) + " à " + end + " sur " + lines.size() + " (molette)" : "");
		Theme.ink(g, font, hint, px + 120, py + ph - 21, Theme.ASH);
	}
}
