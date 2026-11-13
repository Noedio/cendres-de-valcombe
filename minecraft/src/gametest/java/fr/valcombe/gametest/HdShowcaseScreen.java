package fr.valcombe.gametest;

import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdIcon;
import fr.valcombe.client.hd.HdLabel;
import fr.valcombe.client.hd.HdList;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTabs;
import fr.valcombe.client.hd.HdTextField;
import fr.valcombe.client.hd.HdToken;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.Team;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Vitrine de la boîte à outils haute résolution, pour le test en jeu (elle n'est pas dans le mod) : chaque
 * composant y figure dans ses états principaux, pour les captures et pour vérifier les interactions.
 * À gauche : onglets, liste avec défilement, champ de saisie, boutons ; à droite : jetons, icônes, paragraphe,
 * interrupteur, bouton à infobulle.
 */
public class HdShowcaseScreen extends HdScreen {
	static final String TAB_ROLES = "Rôles";
	static final String TAB_PLAYERS = "Joueurs";
	static final String TAB_NOTES = "Notes";

	HdTabs tabs;
	HdList<String> list;
	HdTextField field;
	private int tab;
	private int selectedRow = -1;
	private boolean dark = true;

	public HdShowcaseScreen() {
		super("Boîte à outils HD");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {400, 216};
	}

	/** Reconstruit l'écran comme le fait une nouvelle vue du serveur (pour vérifier ce qui survit). */
	void rebuildForTest() {
		rebuildWidgets();
	}

	private List<String> rows() {
		return switch (tab) {
			case 0 -> Arrays.stream(Role.values()).filter(r -> r.active && !r.slot).map(r -> r.displayName).toList();
			case 1 -> List.of("Aurore", "Bastien", "Clémence", "Dorian", "Éloïse", "Faustine", "Gaspard", "Hortense",
				"Isidore", "Joséphine", "Léandre", "Mahaut");
			default -> List.of("Nuit 1 : la Commère m'a parlé de Bastien.", "Jour 2 : Dorian se dit Greffier.",
				"Nuit 2 : rien appris.");
		};
	}

	@Override
	protected void build() {
		int lx = px + 12;
		int lw = 186;
		tabs = addRenderableWidget(new HdTabs(lx, py + 12, lw, 16, List.of(TAB_ROLES, TAB_PLAYERS, TAB_NOTES), tab)
			.onChange(i -> {
				tab = i;
				selectedRow = -1;
				rebuildSoon();
			}));
		list = addRenderableWidget(new HdList<String>(lx, py + 28, lw, 110, 16, s -> s)
			.items(rows()).key("vitrine.liste." + tab).empty("Aucune ligne")
			.onSelect(item -> selectedRow = list.selectedIndex()));
		if (tab == 0) {
			List<Role> roles = Arrays.stream(Role.values()).filter(r -> r.active && !r.slot).toList();
			list.row((hd, item, index, x, y, w, h, hovered, selected) -> {
				if (selected) {
					hd.fill(x, y, x + w, y + h, Hd.SELECTION);
				} else if (hovered) {
					hd.fill(x, y, x + w, y + h, Hd.HOVER);
				}
				Role r = roles.get(index);
				int is = h - hd.u(3);
				hd.image(Theme.tex("roles/" + r.icon), x + hd.u(3), y + (h - is) / 2, is, is, -1);
				hd.textMiddle(HdStyle.TEXTE, hd.ellipsize(HdStyle.TEXTE, item, w - is - hd.u(40)), x + is + hd.u(7), y, h,
					selected ? Hd.LIGHT : Hd.INK);
				String team = Team.of(r) == Team.EVIL ? "mal" : "bien";
				hd.textRight(HdStyle.PETIT, team, x + w - hd.u(5), y + (h - hd.lineHeight(HdStyle.PETIT)) / 2,
					selected ? Hd.GOLD : Hd.INK_SOFT);
			});
		}
		list.select(selectedRow);
		field = addRenderableWidget(new HdTextField(lx, py + 144, lw, 18, "Note").hint("Écrire une note…")
			.maxLength(80).key("vitrine.champ"));
		button(lx, py + 168, lw / 2 - 2, "Valider", () -> { });
		button(lx + lw / 2 + 2, py + 168, lw / 2 - 2, "Inactif", () -> { }).disabled();

		int rx = px + 206;
		int rw = 182;
		int ty = py + 50;
		token(new HdToken(rx + 26, ty, 26, "Aurore", "COMMERE", "GOOD", true, true, () -> { }).marker("protect"));
		token(new HdToken(rx + 90, ty, 26, "Bastien", "GOULE", "EVIL", false, false, () -> { }));
		token(new HdToken(rx + 154, ty, 26, "Clémence", null, null, true, false, () -> { }).tooltip("Rôle inconnu"));
		String[] icons = {"e_lune", "e_soleil", "e_couronne", "e_cloche", "e_oeil", "e_rose", "e_lanterne"};
		for (int i = 0; i < icons.length; i++) {
			addRenderableWidget(new HdIcon(rx + 6 + i * 25, py + 82, 16, HdIcon.gui("roles/" + icons[i]), icons[i], null)
				.tooltip("Emblème « " + icons[i].substring(2) + " »"));
		}
		addRenderableOnly(new HdLabel(rx, py + 104, rw, "Le village de Valcombe s'endort. Chacun regagne sa maison ; "
			+ "le Démon rôde, les Sbires veillent, et le Conteur tient le grimoire. Au matin, on compte les morts.")
			.paragraph(3).color(Hd.INK));
		toggle(rx, py + 150, rw, "Écran noir la nuit", dark, () -> {
			dark = !dark;
			rebuildSoon();
		});
		HdButton help = button(rx, py + 172, rw, "Survolez-moi", () -> { });
		help.tooltip("Une infobulle haute résolution : texte lisse, cadre net, coupée à la bonne largeur.");
	}

	@Override
	protected void drawHd(Hd hd) {
		int rx = hd.px(px + 206);
		int rw = hd.px(182);
		hd.text(HdStyle.SECTION, "Jetons et icônes", rx, hd.px(py + 12), Hd.WINE);
		hd.separator(rx, hd.px(py + 27), rw);
	}

	/** Les libellés des lignes de l'onglet courant (pour le test). */
	List<String> currentRows() {
		return new ArrayList<>(list.items());
	}
}
