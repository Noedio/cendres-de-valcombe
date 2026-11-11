package fr.valcombe.client.screen;

import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleType;
import fr.valcombe.rules.Team;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Fiches des 22 rôles : jetons rangés par type, détail au survol. */
public class SheetsScreen extends BaseScreen {
	private record Spot(Role role, int x, int y) {
	}

	private final List<Spot> spots = new ArrayList<>();
	private Role shown = Role.COMMERE;

	public SheetsScreen() {
		super("Les rôles de Valcombe");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {380, 236};
	}

	@Override
	protected void build() {
		// Citadins sur deux lignes, puis Étrangers et Sbires côte à côte, puis le Démon.
		spots.clear();
		int x0 = px + 24;
		int y = py + 30;
		List<Role> town = Role.ofType(RoleType.CITADIN);
		for (int i = 0; i < town.size(); i++) {
			spots.add(new Spot(town.get(i), x0 + (i % 8) * 28, y + (i / 8) * 28));
		}
		y += 2 * 28 + 14;
		List<Role> out = Role.ofType(RoleType.ETRANGER);
		for (int i = 0; i < out.size(); i++) {
			spots.add(new Spot(out.get(i), x0 + i * 26, y));
		}
		List<Role> minions = Role.ofType(RoleType.SBIRE);
		for (int i = 0; i < minions.size(); i++) {
			spots.add(new Spot(minions.get(i), x0 + 112 + i * 26, y));
		}
		y += 28 + 14;
		spots.add(new Spot(Role.GOULE, x0, y));
		button(px + pw - 92, py + ph - 26, 80, "Retour", () -> minecraft.gui.setScreen(new MainScreen()));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		RoleType last = null;
		for (Spot s : spots) {
			if (s.role().type != last) {
				last = s.role().type;
				String head = last == RoleType.DEMON ? "Démon" : last.displayName + "s";
				Theme.ink(g, font, head, s.x() - 12, s.y() - 23, Theme.WINE);
			}
			boolean hover = Math.abs(mouseX - s.x()) < 12 && Math.abs(mouseY - s.y()) < 12;
			if (hover) {
				shown = s.role();
			}
			Theme.token(g, s.x(), s.y(), 24, s.role().name(), Team.of(s.role()).name(), true, s.role() == shown, hover);
		}
		int dx = px + 236;
		int dy = py + 22;
		Theme.token(g, dx + 60, dy + 24, 44, shown.name(), Team.of(shown).name(), true, false, false);
		Theme.centered(g, font, shown.displayName, dx + 60, dy + 52, Theme.INK);
		Theme.centered(g, font, shown.type.displayName + " — " + Team.of(shown).displayName, dx + 60, dy + 64,
			shown.evil() ? Theme.EVIL : Theme.GOOD);
		Theme.paragraph(g, font, shown.text, dx, dy + 80, 124, Theme.INK);
	}
}
