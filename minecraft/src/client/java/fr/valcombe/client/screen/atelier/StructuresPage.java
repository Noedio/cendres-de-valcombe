package fr.valcombe.client.screen.atelier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.Vignettes;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdGrid;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTabs;
import fr.valcombe.client.hd.HdTextField;
import fr.valcombe.client.screen.WorldsScreen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.util.Util;

/**
 * Atelier › Structures : deux volets.
 * <ul>
 * <li><b>Catalogue</b> : les structures en vignettes (chacune vue de trois quarts, peinte par le serveur), rangées par
 * rayon (maisons et salles, pour la place, décor, les vôtres), avec leur état : fournie, modifiée dans l'atelier
 * structures, ou créée. Recherche sans accents. Un clic ouvre sa fiche ({@link StructureSheetPage}). Une structure en main
 * (outil de pose) a sa barre en bas : tourner, retourner, poser à ses pieds, ranger.</li>
 * <li><b>Construire</b> : les gestes de WorldEdit de l'ancien Atelier (remplir, copier, coller, déplacer, annuler...),
 * là où WorldEdit sert : dans les ateliers. Ailleurs, le chemin vers eux.</li>
 * </ul>
 * « Nouvelle structure » (en haut à droite) ouvre le parcours guidé ({@link NewStructurePage}).
 */
public class StructuresPage extends AtelierPage {
	public static final String NEW = "Nouvelle structure";
	public static final List<String> SHELVES = List.of("Tout", "Maisons", "Place", "Décor", "À vous");
	/** Les rayons du catalogue, dans l'ordre des onglets (après « Tout »). */
	private static final List<String> CATEGORIES = List.of("maisons", "place", "decor");
	private static final String SEARCH_KEY = "atelier.structures.recherche";

	private static int tab;
	private static int shelf;
	private static String search = "";
	/** Les structures connues avant une création : la nouvelle venue est mise en avant quand elle paraît. */
	private static Set<String> before;
	private static String flash;
	private static long flashUntil;

	private HdGrid<JsonObject> grid;

	public StructuresPage() {
	}

	/** Ouvre la page sur un volet (0 catalogue, 1 construire). */
	public StructuresPage(int openTab) {
		tab = Math.max(0, Math.min(1, openTab));
	}

	static List<Crumb> crumbs() {
		return List.of(new Crumb("Atelier", AtelierHome::new), new Crumb(AtelierHome.STRUCTURES, StructuresPage::new));
	}

	@Override
	protected List<Crumb> trail() {
		return List.of(new Crumb("Atelier", AtelierHome::new), new Crumb(AtelierHome.STRUCTURES, null));
	}

	// ------------------------------------------------------------------
	// Ce que le serveur envoie
	// ------------------------------------------------------------------

	/** Le catalogue, tel que le serveur l'envoie (structures fournies, puis les vôtres). */
	public static List<JsonObject> catalog() {
		return list(data(), "structures");
	}

	/** Une structure du catalogue par son identifiant, ou null. */
	static JsonObject structure(String id) {
		for (JsonObject s : catalog()) {
			if (str(s, "id").equals(id)) {
				return s;
			}
		}
		return null;
	}

	/** L'état de l'éditeur de ce joueur : structure en main, sélection WorldEdit, gestes annulables. */
	static JsonObject editor() {
		return ClientState.obj(data(), "editeur");
	}

	/** L'identifiant de la structure en main, ou null. */
	static String held() {
		String s = ClientState.str(editor(), "structure");
		return s == null || s.isEmpty() ? null : s;
	}

	/** « 7 × 9 × 7 » : largeur, hauteur, profondeur. */
	static String dims(JsonObject s) {
		JsonArray t = s == null || !s.has("taille") || !s.get("taille").isJsonArray() ? null : s.getAsJsonArray("taille");
		if (t == null || t.size() != 3) {
			return "";
		}
		return t.get(0).getAsInt() + " × " + t.get(1).getAsInt() + " × " + t.get(2).getAsInt();
	}

	static boolean mine(JsonObject s) {
		return !ClientState.bool(s, "fournie");
	}

	/** « Fournie », « Modifiée », « Créée ». */
	static String state(JsonObject s) {
		return mine(s) ? "Créée" : ClientState.bool(s, "modifiee") ? "Modifiée" : "Fournie";
	}

	static int stateColor(JsonObject s) {
		return mine(s) ? AtelierArt.GOOD : ClientState.bool(s, "modifiee") ? AtelierKit.AMBER : AtelierArt.NEUTRAL;
	}

	/** Le nom d'un rayon du catalogue. */
	static String shelfName(String categorie) {
		return switch (categorie == null ? "" : categorie) {
			case "maisons" -> "Maisons et salles";
			case "place" -> "Pour la place";
			case "decor" -> "Décor";
			default -> "Vos structures";
		};
	}

	/** « avec un repère de maison », « avec le repère de la salle », ou rien. */
	static String markText(JsonObject s) {
		return switch (str(s, "repere")) {
			case "maison" -> "repère de maison";
			case "salle" -> "repère de la salle";
			default -> "";
		};
	}

	/** Le rayon ouvert (0 tout, 1 maisons, 2 place, 3 décor, 4 les vôtres), pour les tests en jeu. */
	public static int shelf() {
		return shelf;
	}

	/** Le catalogue sans recherche, sur « Tout », premier volet. */
	public static void resetFilters() {
		tab = 0;
		shelf = 0;
		search = "";
		HdTextField.forget(SEARCH_KEY);
	}

	/** La galerie (pour les tests en jeu), ou null sur l'autre volet. */
	public HdGrid<JsonObject> grid() {
		return grid;
	}

	/** Une nouvelle structure va paraître : elle sera mise en avant, sur le rayon « À vous ». */
	static void expectNew() {
		before = new HashSet<>();
		for (JsonObject s : catalog()) {
			before.add(str(s, "id"));
		}
		tab = 0;
		shelf = 4;
		search = "";
		HdTextField.forget(SEARCH_KEY);
	}

	/** Les structures de ce rayon qui répondent à la recherche : par nom, les vôtres après les fournies sur « Tout ». */
	static List<JsonObject> shown(List<JsonObject> all, int shelfIndex, String query) {
		String q = AtelierKit.fold(query).strip();
		List<JsonObject> out = new ArrayList<>();
		for (JsonObject s : all) {
			boolean in = switch (shelfIndex) {
				case 1, 2, 3 -> CATEGORIES.get(shelfIndex - 1).equals(str(s, "categorie"));
				case 4 -> mine(s);
				default -> true;
			};
			if (in && (q.isEmpty() || AtelierKit.fold(str(s, "nom")).contains(q) || AtelierKit.fold(str(s, "id")).contains(q))) {
				out.add(s);
			}
		}
		out.sort(Comparator.comparing((JsonObject s) -> mine(s)).thenComparing(s -> AtelierKit.fold(str(s, "nom"))));
		return out;
	}

	// ------------------------------------------------------------------
	// Prendre en main
	// ------------------------------------------------------------------

	/**
	 * Prend une structure en main (l'outil de pose : son fantôme suit le regard, clic droit pour la poser). Dans le
	 * monde d'une partie, le mode construction s'ouvre d'abord ; dans un atelier, on y bâtit déjà.
	 */
	public static void take(String id) {
		boolean building = ClientState.bool(editor(), "construction");
		boolean inWorkshop = ClientState.obj(view(), "atelierCarte") != null || ClientState.bool(data(), "dansAtelierStructures");
		if (!building && !inWorkshop) {
			ClientState.send("vc carte construction");
		}
		ClientState.send("vc structure poser " + id);
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	@Override
	protected void buildPage() {
		List<JsonObject> all = catalog();
		int x = left();
		int w = right() - left();
		int nw = Math.min(130, w / 3);
		addRenderableWidget(new HdTabs(x, top() + 2, w - nw - 8, 16, List.of("Catalogue (" + all.size() + ")", "Construire"), tab)
			.onChange(i -> {
				tab = i;
				rebuildSoon();
			}));
		HdButton create = button(right() - nw, top(), nw, NEW, () -> go(NewStructurePage.start()))
			.tooltip("Depuis votre sélection WorldEdit, ou une parcelle vide de l'atelier structures où la bâtir.");
		grid = null;
		if (tab == 1) {
			buildTools();
			return;
		}
		boolean noneMine = all.stream().noneMatch(StructuresPage::mine);
		if (shelf == 4 && noneMine) {
			create.primary(); // « À vous » est vide : la seule suite est d'en créer une
		}
		int y = top() + 24;
		int sw = Math.max(90, Math.min(150, w - 250));
		HdTextField field = addRenderableWidget(new HdTextField(x, y, sw, 18, "Rechercher une structure")
			.hint("Rechercher…").maxLength(40).value(search).key(SEARCH_KEY));
		field.responder(s -> {
			if (!s.equals(search)) {
				search = s;
				rebuildSoon();
			}
		});
		addRenderableWidget(new HdTabs(x + sw + 8, y + 1, w - sw - 8, 16, SHELVES, shelf).onChange(i -> {
			shelf = i;
			rebuildSoon();
		}));
		String held = held();
		int bar = held != null ? 22 : 0;
		int gy = y + 24;
		int gh = bottom() - 4 - gy - bar;
		boolean tall = gh >= 2 * 124 + 6 && w >= 3 * 100 + 30;
		List<JsonObject> shown = shown(all, shelf, search);
		String empty = !search.isBlank() ? "Aucune structure ne répond à « " + search.strip() + " »."
			: shelf == 4 ? "Aucune structure à vous : « " + NEW + " » pour en créer une, depuis une sélection WorldEdit ou une parcelle vide."
			: "Ce rayon est vide.";
		grid = addRenderableWidget(new HdGrid<JsonObject>(x, gy, w, gh, tall ? 100 : 168, tall ? 124 : 46, 6, s -> str(s, "nom"))
			.items(shown).key("atelier.structures.galerie." + shelf).empty(empty).openOnClick()
			.onActivate(s -> go(StructureSheetPage.visit(str(s, "id")))));
		grid.cell(tall ? StructuresPage::tallCell : StructuresPage::wideCell);
		if (before != null) {
			for (int i = 0; i < shown.size(); i++) {
				String id = str(shown.get(i), "id");
				if (!before.contains(id)) {
					flash = id;
					flashUntil = Util.getMillis() + 4000;
					before = null;
					grid.select(i);
					break;
				}
			}
		}
		if (held != null) {
			buildHeldBar();
		}
	}

	/** La barre de la structure en main, en bas : ce qu'on en fait avant de la poser. */
	private void buildHeldBar() {
		int by = bottom() - 4 - 18;
		int x = right();
		String[][] actions = {
			{"Ranger", "vc structure fin", "Rendre l'outil de pose : la structure n'est plus en main."},
			{"À mes pieds", "vc structure ici", "La poser là où vous vous tenez, comme un clic droit sur le sol."},
			{"Retourner", "vc structure miroir", "La retourner comme dans un miroir."},
			{"Tourner", "vc structure tourner", "Un quart de tour, dans le sens des aiguilles d'une montre."},
		};
		int[] widths = {58, 74, 66, 58};
		for (int i = 0; i < actions.length; i++) {
			x -= widths[i];
			String cmd = actions[i][1];
			String label = actions[i][0];
			button(x, by, widths[i], label, () -> run(cmd, label)).tooltip(actions[i][2]);
			x -= 4;
		}
	}

	// ------------------------------------------------------------------
	// Construire : les gestes de WorldEdit, dans les ateliers
	// ------------------------------------------------------------------

	/** Un geste qui demande du texte (un bloc, un nombre) : le chat s'ouvre, la commande déjà commencée, avec ses suggestions. */
	private void chat(String prefix) {
		minecraft.gui.setScreen(new ChatScreen("/" + prefix, false));
	}

	private void buildTools() {
		JsonObject ed = editor();
		int x = left();
		int w = right() - left();
		if (!ClientState.bool(ed, "worldedit")) {
			int by = bottom() - 4 - 18;
			int bw = Math.min(180, (w - 6) / 2);
			button(right() - bw, by, bw, "Mondes…", () -> go(new WorldsScreen()))
				.tooltip("Les mondes où aller, dont les ateliers carte ouverts.");
			button(right() - 2 * bw - 6, by, bw, "Aller à l'atelier structures", () -> {
				run("vc structures atelier", "Voyage vers l'atelier structures");
				onClose();
			}).primary().tooltip("Le monde des parcelles : une structure par parcelle, à retoucher avec WorldEdit.");
			return;
		}
		int cols = 3;
		int gap = 4;
		int bw = (w - (cols - 1) * gap) / cols;
		int top = top() + 40;
		int row = Math.max(19, Math.min(21, (bottom() - top - 18) / 5)); // six rangées, resserrées dans une petite fenêtre
		Object[][] tools = {
			{"Remplir…", "/set ", "chat", "Remplir la sélection d'un bloc (le chat s'ouvre : tapez le bloc)."},
			{"Remplacer…", "/replace ", "chat", "Remplacer un bloc par un autre dans la sélection."},
			{"Vider", "/set air", "we", "Remplir la sélection d'air."},
			{"Murs…", "/walls ", "chat", "Des murs autour de la sélection."},
			{"Étendre…", "/expand ", "chat", "Agrandir la sélection (par exemple « 5 up »)."},
			{"Désélectionner", "/sel", "we", "Oublier la sélection."},
			{"Copier", "/copy", "we", "La sélection dans le presse-papiers de WorldEdit."},
			{"Couper", "/cut", "we", "Copier, puis vider la sélection."},
			{"Coller", "/paste", "we", "Coller le presse-papiers là où vous êtes."},
			{"Tourner la copie", "/rotate 90", "we", "Un quart de tour au presse-papiers."},
			{"Retourner la copie", "/flip", "we", "Le presse-papiers en miroir, dans le sens où vous regardez."},
			{"Coller sans l'air", "/paste -a", "we", "Coller sans effacer ce qui est déjà là."},
			{"Déplacer…", "/move ", "chat", "Déplacer la sélection (par exemple « 3 north »)."},
			{"Empiler…", "/stack ", "chat", "Répéter la sélection (par exemple « 4 up »)."},
			{"En structure…", NewStructurePage.class, "page", "La sélection au catalogue des structures (parcours guidé)."},
			{"Annuler (" + ClientState.num(ed, "annulables", 0) + ")", "vc annuler", "vc", "Le dernier geste, WorldEdit ou repère."},
			{"Rétablir (" + ClientState.num(ed, "retablissables", 0) + ")", "vc retablir", "vc", "Le dernier geste annulé."},
			{"Baguette", "vc baguette", "vc", "La baguette de construction : clic gauche, puis clic droit, pour les deux coins."},
		};
		for (int i = 0; i < tools.length; i++) {
			int bx = x + (i % cols) * (bw + gap);
			int by = top + (i / cols) * row;
			String label = (String) tools[i][0];
			Object what = tools[i][1];
			String tip = (String) tools[i][3];
			HdButton b = switch ((String) tools[i][2]) {
				case "chat" -> button(bx, by, bw, label, () -> chat((String) what));
				case "we" -> button(bx, by, bw, label, () -> minecraft.player.connection.sendCommand((String) what));
				case "page" -> button(bx, by, bw, label, () -> go(NewStructurePage.fromSelection()));
				default -> button(bx, by, bw, label, () -> run((String) what, label.replaceAll(" \\(\\d+\\)", "")));
			};
			b.tooltip(tip);
		}
	}

	// ------------------------------------------------------------------
	// Les cases de la galerie
	// ------------------------------------------------------------------

	private static void flashOver(Hd hd, JsonObject s, int x, int y, int w, int h) {
		if (str(s, "id").equals(flash) && Util.getMillis() < flashUntil) {
			double t = (flashUntil - Util.getMillis()) / 4000.0;
			int alpha = (int) Math.round(0x90 * Math.max(0, Math.min(1, t)) * (0.6 + 0.4 * Math.sin(Util.getMillis() / 150.0)));
			hd.fill(x, y, x + w, y + h, alpha << 24 | 0xF2C14E);
		}
	}

	/** Grande case : la vignette au-dessus, le nom, la taille, l'état. */
	private static void tallCell(Hd hd, JsonObject s, int index, int x, int y, int w, int h, boolean hovered, boolean selected) {
		AtelierKit.cardBack(hd, x, y, w, h, hovered);
		flashOver(hd, s, x, y, w, h);
		int pad = hd.u(4);
		HdStyle cs = HdStyle.PETIT.size(8);
		int text = hd.lineHeight(HdStyle.FORT) + hd.lineHeight(HdStyle.PETIT) + AtelierArt.chipHeight(hd, cs) + hd.u(7);
		int side = Math.min(w - 2 * pad, h - 2 * pad - text);
		int vx = x + (w - side) / 2;
		int vy = y + pad;
		Vignettes.draw(hd, "structure:" + str(s, "id"), str(s, "vignette"), vx, vy, side, side);
		hd.frame(vx, vy, side, side, Math.max(1, hd.u(0.5)), 0xFF2A1E14);
		if (str(s, "id").equals(held())) {
			AtelierArt.ribbon(hd, "En main", vx + side - hd.u(2), vy + hd.u(2), 0xE06E4F22);
		}
		int ty = vy + side + hd.u(3);
		int tw = w - 2 * pad;
		hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(s, "nom"), tw), x + pad, ty, hovered ? Hd.WINE : Hd.INK);
		ty += hd.lineHeight(HdStyle.FORT);
		hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, dims(s), tw), x + pad, ty, Hd.INK_SOFT);
		ty += hd.lineHeight(HdStyle.PETIT) + hd.u(2);
		AtelierArt.chip(hd, cs, state(s), x + pad, ty, stateColor(s));
	}

	/** Case en longueur : la vignette à gauche, le reste à droite. */
	private static void wideCell(Hd hd, JsonObject s, int index, int x, int y, int w, int h, boolean hovered, boolean selected) {
		AtelierKit.cardBack(hd, x, y, w, h, hovered);
		flashOver(hd, s, x, y, w, h);
		int pad = hd.u(3);
		int side = h - 2 * pad;
		Vignettes.draw(hd, "structure:" + str(s, "id"), str(s, "vignette"), x + pad, y + pad, side, side);
		hd.frame(x + pad, y + pad, side, side, Math.max(1, hd.u(0.5)), 0xFF2A1E14);
		int tx = x + pad + side + hd.u(5);
		int tw = x + w - pad - tx;
		int ty = y + pad;
		hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(s, "nom"), tw), tx, ty, hovered ? Hd.WINE : Hd.INK);
		ty += hd.lineHeight(HdStyle.FORT);
		String sub = dims(s) + (markText(s).isEmpty() ? "" : " · " + markText(s));
		hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, sub, tw), tx, ty, Hd.INK_SOFT);
		HdStyle cs = HdStyle.PETIT.size(8);
		int cy = y + h - pad - AtelierArt.chipHeight(hd, cs);
		int cw = AtelierArt.chip(hd, cs, state(s), tx, cy, stateColor(s));
		if (str(s, "id").equals(held()) && cw + AtelierArt.chipWidth(hd, cs, "En main") + hd.u(3) <= tw) {
			AtelierArt.chip(hd, cs, "En main", tx + cw + hd.u(3), cy, 0xE06E4F22);
		}
	}

	// ------------------------------------------------------------------
	// Dessin
	// ------------------------------------------------------------------

	@Override
	protected void drawPage(Hd hd) {
		int x = hd.px(left());
		int w = hd.px(right() - left());
		if (tab == 1) {
			JsonObject ed = editor();
			int y = hd.px(top() + 24);
			if (!ClientState.bool(ed, "worldedit")) {
				hd.text(HdStyle.SECTION, "WorldEdit sert dans les ateliers", x, y, Hd.WINE);
				y += hd.lineHeight(HdStyle.SECTION) + hd.u(4);
				hd.paragraph(HdStyle.TEXTE, "Remplir, copier, coller, déplacer : ces gestes servent dans les ateliers (carte et structures), "
					+ "aux opérateurs et aux joueurs qu'ils y invitent (/vc atelier inviter <joueur>). Là-bas, la baguette de construction "
					+ "pose les deux coins de la sélection (clic gauche, puis clic droit) ; revenez ici pour la travailler.",
					x, y, w, Hd.INK_SOFT, hd.px(bottom() - 4 - 18) - hd.u(6) - y);
				return;
			}
			int[] sel = NewStructurePage.selection();
			HdStyle st = HdStyle.FORT.size(10);
			String text = sel != null ? "Sélection : " + sel[0] + " × " + sel[1] + " × " + sel[2] + " (largeur, hauteur, profondeur)"
				: "Aucune sélection : la baguette pose ses deux coins (clic gauche, puis clic droit)";
			int s = hd.u(7);
			int lh = hd.lineHeight(st);
			if (sel != null) {
				fr.valcombe.client.hd.HdGlyphs.check(hd, x, y + (lh - s) / 2, s, AtelierArt.GOOD);
			} else {
				fr.valcombe.client.hd.HdGlyphs.warning(hd, x, y + (lh - s) / 2, s, AtelierArt.BAD);
			}
			hd.text(st, hd.ellipsize(st, text, w - s - hd.u(4)), x + s + hd.u(4), y, sel != null ? Hd.INK : AtelierKit.WARN);
			return;
		}
		String held = held();
		if (held != null) {
			JsonObject s = structure(held);
			String turn = ClientState.str(editor(), "orientation");
			String text = "En main : " + (s == null ? held : str(s, "nom")) + (turn == null || turn.isEmpty() ? "" : " (" + turn + ")");
			int by = hd.px(bottom() - 4 - 18);
			int room = hd.px(right() - (58 + 74 + 66 + 58 + 12)) - hd.u(8) - x;
			hd.textMiddle(HdStyle.FORT.size(10), hd.ellipsize(HdStyle.FORT.size(10), text, room), x, by, hd.px(18), Hd.WINE);
		}
	}
}
