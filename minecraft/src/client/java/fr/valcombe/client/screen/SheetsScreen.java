package fr.valcombe.client.screen;

import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdText;
import fr.valcombe.client.hd.HdTokenArt;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleType;
import fr.valcombe.rules.Team;

import java.util.ArrayList;
import java.util.List;

/** Fiches des 22 rôles : jetons rangés par type, détail au survol. */
public class SheetsScreen extends HdScreen {
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
	/** Taille d'un jeton de la grille, en unités. */
	private static final int SIZE = 24;

	/** Une rangée (ou plusieurs) de jetons ; renvoie le haut de la rangée suivante. */
	private int row(List<Role> roles, int x0, int y) {
		for (int i = 0; i < roles.size(); i++) {
			spots.add(new Spot(roles.get(i), x0 + (i % PER_ROW) * STEP, y + (i / PER_ROW) * STEP));
		}
		int rows = Math.max(1, (roles.size() + PER_ROW - 1) / PER_ROW);
		return y + rows * STEP + 14;
	}

	@Override
	protected void drawHd(Hd hd) {
		// Le jeton survolé devient la fiche affichée (au pixel près : la souris est lue en pixels réels).
		for (Spot s : spots) {
			if (Math.abs(hd.mouseX() - hd.px(s.x())) < hd.u(SIZE / 2.0) && Math.abs(hd.mouseY() - hd.px(s.y())) < hd.u(SIZE / 2.0)) {
				shown = s.role();
			}
		}
		RoleType last = null;
		int half = hd.u(SIZE / 2.0);
		for (Spot s : spots) {
			if (s.role().type != last) {
				last = s.role().type;
				String head = last == RoleType.DEMON ? "Démon" : last.displayName + "s";
				hd.text(HdStyle.SECTION.size(9), head, hd.px(s.x() - 12), hd.px(s.y() - 25), Hd.WINE);
			}
			boolean hover = hd.hovered(hd.px(s.x()) - half, hd.px(s.y()) - half, 2 * half, 2 * half);
			HdTokenArt.draw(hd, hd.px(s.x()) - half, hd.px(s.y()) - half, 2 * half, s.role().name(), Team.of(s.role()).name(),
				true, s.role() == shown, hover);
		}
		// Fiche du rôle, à droite : grand jeton, nom, type et camp, texte.
		int dx = px + 24 + PER_ROW * STEP + 6;
		int dw = px + pw - 12 - dx;
		int dy = py + 22;
		int big = hd.u(44);
		HdTokenArt.draw(hd, hd.px(dx + dw / 2) - big / 2, hd.px(dy + 24) - big / 2, big, shown.name(), Team.of(shown).name(), true,
			false, false);
		hd.textCentered(HdStyle.FORT, shown.displayName, hd.px(dx + dw / 2), hd.px(dy + 50), Hd.INK);
		hd.textCentered(HdText.SERRE, shown.type.displayName + ", " + Team.of(shown).displayName, hd.px(dx + dw / 2), hd.px(dy + 64),
			shown.evil() ? Theme.EVIL : Theme.GOOD);
		hd.separator(hd.px(dx + 8), hd.px(dy + 79), hd.px(dw - 16));
		HdText.paragraph(hd, HdText.SERRE, shown.text, hd.px(dx), hd.px(dy + 84), hd.px(dw), Hd.INK, hd.u(HdText.PAS),
			hd.px(py + ph - 32 - (dy + 84)));
	}
}
