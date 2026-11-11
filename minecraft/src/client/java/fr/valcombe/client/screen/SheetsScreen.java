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
		// Jetons rangés par type, 7 par ligne au plus ; Étrangers et Sbires côte à côte s'ils tiennent.
		// La colonne de détail, à droite, n'est jamais recouverte.
		spots.clear();
		int x0 = px + 24;
		int y = py + 34;
		y = row(Role.ofType(RoleType.CITADIN), x0, y);
		List<Role> out = Role.ofType(RoleType.ETRANGER);
		List<Role> minions = Role.ofType(RoleType.SBIRE);
		if (out.size() + minions.size() <= PER_ROW - 1) {
			row(out, x0, y);
			y = row(minions, x0 + (out.size() + 1) * STEP, y);
		} else {
			y = row(out, x0, y);
			y = row(minions, x0, y);
		}
		row(Role.ofType(RoleType.DEMON), x0, y);
		button(px + pw - 92, py + ph - 26, 80, "Retour", () -> minecraft.gui.setScreen(new MainScreen()));
	}

	private static final int PER_ROW = 7;
	private static final int STEP = 26;

	/** Une rangée (ou plusieurs) de jetons ; renvoie le haut de la rangée suivante. */
	private int row(List<Role> roles, int x0, int y) {
		for (int i = 0; i < roles.size(); i++) {
			spots.add(new Spot(roles.get(i), x0 + (i % PER_ROW) * STEP, y + (i / PER_ROW) * STEP));
		}
		int rows = Math.max(1, (roles.size() + PER_ROW - 1) / PER_ROW);
		return y + rows * STEP + 14;
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		RoleType last = null;
		for (Spot s : spots) {
			if (s.role().type != last) {
				last = s.role().type;
				String head = last == RoleType.DEMON ? "Démon" : last.displayName + "s";
				Theme.ink(g, font, head, s.x() - 12, s.y() - 24, Theme.WINE);
			}
			boolean hover = Math.abs(mouseX - s.x()) < 12 && Math.abs(mouseY - s.y()) < 12;
			if (hover) {
				shown = s.role();
			}
			Theme.token(g, s.x(), s.y(), 24, s.role().name(), Team.of(s.role()).name(), true, s.role() == shown, hover);
		}
		int dx = px + 24 + PER_ROW * STEP + 6;
		int dw = px + pw - 12 - dx;
		int dy = py + 22;
		Theme.token(g, dx + dw / 2, dy + 24, 44, shown.name(), Team.of(shown).name(), true, false, false);
		Theme.centered(g, font, shown.displayName, dx + dw / 2, dy + 52, Theme.INK);
		Theme.centered(g, font, shown.type.displayName + " — " + Team.of(shown).displayName, dx + dw / 2, dy + 64,
			shown.evil() ? Theme.EVIL : Theme.GOOD);
		Theme.paragraph(g, font, shown.text, dx, dy + 80, dw, Theme.INK, py + ph - 32 - (dy + 80));
	}
}
