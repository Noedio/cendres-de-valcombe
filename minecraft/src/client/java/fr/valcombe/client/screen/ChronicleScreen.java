package fr.valcombe.client.screen;

import fr.valcombe.client.ui.Theme;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Chronique de la dernière partie : nuits, morts, nominations et votes, à relire (molette pour défiler). */
public class ChronicleScreen extends BaseScreen {
	private record Line(FormattedCharSequence text, int color, boolean heading) {
	}

	private final List<Line> lines = new ArrayList<>();
	private int scroll;

	public ChronicleScreen() {
		super("Chronique de la partie");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {400, 250};
	}

	@Override
	protected void build() {
		lines.clear();
		int width = pw - 28;
		for (String raw : strings(view(), "chronicle")) {
			boolean heading = raw.startsWith("La nuit ") || raw.startsWith("Le jour ") || raw.startsWith("Mise en place");
			int color = heading ? Theme.WINE
				: raw.contains("meurt") || raw.contains("exécuté") ? Theme.BLOOD
				: raw.contains("vote") || raw.contains("voix") || raw.contains("accuse") ? Theme.BRONZE
				: Theme.INK;
			for (FormattedCharSequence part : font.split(Component.literal(raw), width)) {
				lines.add(new Line(part, color, heading));
			}
		}
		button(px + pw - 94, py + ph - 26, 80, "Retour", () -> minecraft.gui.setScreen(new MainScreen()));
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
			Theme.ink(g, font, "Aucune partie terminée pour l'instant.", px + 14, py + 18, Theme.ASH);
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
		if (lines.size() > visible()) {
			String hint = (scroll + 1) + "–" + end + " / " + lines.size() + " (molette)";
			Theme.ink(g, font, hint, px + 14, py + ph - 22, Theme.ASH);
		}
	}
}
