package fr.valcombe.client.screen.atelier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdGlyphs;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTabs;
import fr.valcombe.client.hd.HdTextField;

import java.util.ArrayList;
import java.util.List;

/**
 * Atelier › Structures › Nouvelle structure : un parcours en trois étapes, une à la fois.
 * <ol>
 * <li><b>Départ</b> : depuis la sélection WorldEdit (ce qu'on vient de bâtir dans un atelier), ou une parcelle vide de
 * l'atelier structures (on y bâtit, puis « Enregistrer » à son pupitre). Ce qui manque est dit sur la case : pas de
 * sélection, ou WorldEdit hors d'un atelier.</li>
 * <li><b>Réglages</b> : le nom ; le rayon du catalogue (sélection) ou la taille de la parcelle.</li>
 * <li><b>Vérifier</b> : le récapitulatif, puis « Enregistrer la sélection » (la structure paraît, mise en avant, dans « À
 * vous ») ou « Ouvrir la parcelle » (vous y êtes conduit).</li>
 * </ol>
 */
public class NewStructurePage extends AtelierPage {
	public static final String FROM_SELECTION = "Depuis la sélection";
	public static final String EMPTY_PLOT = "Parcelle vide";
	public static final String NEXT = "Suivant";
	public static final String PREVIOUS = "Précédent";
	public static final String SAVE_SELECTION = "Enregistrer la sélection";
	public static final String OPEN_PLOT = "Ouvrir la parcelle";
	private static final List<String> STEPS = List.of("Départ", "Réglages", "Vérifier");
	/** Les rayons où ranger une sélection : le code du serveur, et le nom de l'onglet. */
	private static final List<String> SHELF_CODES = List.of("perso", "maisons", "place", "decor");
	private static final List<String> SHELF_LABELS = List.of("Vos structures", "Maisons", "Place", "Décor");
	private static final List<String> SIZE_LABELS = List.of("Petite", "Moyenne", "Grande", "Immense");
	private static final String[] SIZE_TEXTS = {"11 × 11 blocs, 16 de haut", "17 × 17 blocs, 24 de haut", "25 × 25 blocs, 32 de haut",
		"41 × 41 blocs, 48 de haut"};
	private static final String NAME_KEY = "atelier.structure.nouvelle.nom";

	private enum Kind {
		SELECTION, PLOT
	}

	private static int step;
	private static Kind kind;
	private static int shelf;
	private static int size = 1;
	private static String name = "";

	/** Le parcours depuis le début. */
	public static NewStructurePage start() {
		step = 0;
		kind = null;
		shelf = 0;
		size = 1;
		name = "";
		HdTextField.forget(NAME_KEY);
		return new NewStructurePage();
	}

	/** Le parcours, sélection déjà choisie (Structures › Construire › « En structure… ») : on passe aux réglages s'il y en a une. */
	public static NewStructurePage fromSelection() {
		start();
		kind = Kind.SELECTION;
		step = selection() != null ? 1 : 0;
		return new NewStructurePage();
	}

	@Override
	protected boolean cancelPending() {
		if (step == 0) {
			return false;
		}
		step--; // Échap revient d'une étape, comme « Précédent »
		return true;
	}

	/** L'étape en cours (0 à 2), pour les tests en jeu. */
	public static int step() {
		return step;
	}

	@Override
	protected List<Crumb> trail() {
		List<Crumb> t = new ArrayList<>(StructuresPage.crumbs());
		t.add(new Crumb(StructuresPage.NEW, null));
		return t;
	}

	// ------------------------------------------------------------------
	// Ce qui manque
	// ------------------------------------------------------------------

	/** La sélection WorldEdit complète du joueur (largeur, hauteur, profondeur), ou null. */
	static int[] selection() {
		JsonObject ed = StructuresPage.editor();
		if (ed == null || !ed.has("selection") || !ed.get("selection").isJsonArray()) {
			return null;
		}
		JsonArray a = ed.getAsJsonArray("selection");
		return a.size() == 3 ? new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()} : null;
	}

	private static boolean worldEdit() {
		return ClientState.bool(StructuresPage.editor(), "worldedit");
	}

	/** Ce qui manque à la sélection pour devenir une structure, ou null. */
	private static String selectionProblem() {
		if (!worldEdit()) {
			return "WorldEdit ne sert que dans les ateliers : allez-y pour sélectionner (Structures › Construire).";
		}
		int[] s = selection();
		if (s == null) {
			return "Il manque une sélection : la baguette de construction pose ses deux coins (clic gauche, puis clic droit).";
		}
		if (s[0] > 128 || s[2] > 128 || s[1] > 128 || (long) s[0] * s[1] * s[2] > 400_000) {
			return "Sélection trop grande (" + s[0] + " × " + s[1] + " × " + s[2] + ") : 128 × 128 × 128 et 400 000 blocs au plus.";
		}
		return null;
	}

	/** Ce qui manque pour passer l'étape en cours, ou null. */
	private static String missing() {
		if (step == 0 && kind == null) {
			return "Choisissez un point de départ.";
		}
		if (kind == Kind.SELECTION && selectionProblem() != null) {
			return selectionProblem();
		}
		if (step >= 1 && name.strip().isEmpty()) {
			return "Il manque un nom pour la structure.";
		}
		return null;
	}

	private void finish() {
		String nom = name.strip();
		HdTextField.forget(NAME_KEY);
		if (kind == Kind.SELECTION) {
			StructuresPage.expectNew();
			run("vc structure nouvelle-selection " + SHELF_CODES.get(shelf) + " " + nom, "Enregistrement de la sélection");
			step = 0;
			kind = null;
			name = "";
			go(new StructuresPage(0));
		} else {
			run("vc structures nouvelle " + words(data(), "tailles").stream().skip(size).findFirst().orElse("moyenne") + " " + nom,
				"Ouverture de la parcelle");
			step = 0;
			kind = null;
			name = "";
			onClose(); // vous êtes conduit devant la parcelle neuve
		}
	}

	// ------------------------------------------------------------------
	// Mise en page
	// ------------------------------------------------------------------

	private int stageTop() {
		return top() + 22;
	}

	private int navY() {
		return bottom() - 4 - 18;
	}

	private int stageBottom() {
		return navY() - 6;
	}

	private int previewSide() {
		return Math.max(50, Math.min(stageBottom() - stageTop(), (right() - left()) * 2 / 5));
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	@Override
	protected void buildPage() {
		if (step > 0 && kind == null) {
			step = 0;
		}
		switch (step) {
			case 1 -> buildSettings();
			case 2 -> {
			}
			default -> buildStart();
		}
		int nw = 132;
		String miss = missing();
		HdButton next;
		if (step < 2) {
			next = button(right() - nw, navY(), nw, NEXT, () -> {
				if (missing() == null) {
					step++;
					rebuildSoon();
				}
			});
		} else {
			next = button(right() - nw, navY(), nw, kind == Kind.SELECTION ? SAVE_SELECTION : OPEN_PLOT, () -> {
				if (missing() == null) {
					finish();
				}
			});
		}
		if (miss != null) {
			next.disabled().tooltip(miss);
		} else {
			next.primary();
		}
		if (step > 0) {
			button(right() - nw - 6 - 92, navY(), 92, PREVIOUS, () -> {
				step--;
				rebuildSoon();
			});
		}
	}

	private void buildStart() {
		int x = left();
		int w = right() - left();
		int gap = 8;
		int tw = (w - gap) / 2;
		int h = stageBottom() - stageTop();
		String problem = selectionProblem();
		int[] sel = selection();
		ChoiceTile a = new ChoiceTile(x, stageTop(), tw, h, FROM_SELECTION, "Ce que vous avez bâti dans un atelier, pris à la baguette, "
			+ "entre au catalogue.", (hd, ax, ay, aw, ah) -> selectionArt(hd, ax, ay, aw, ah, sel), kind == Kind.SELECTION, () -> {
				kind = Kind.SELECTION;
				rebuildSoon();
			});
		a.state(problem == null ? "Sélection de " + sel[0] + " × " + sel[1] + " × " + sel[2] : worldEdit() ? "Aucune sélection"
			: "Hors d'un atelier", problem != null);
		a.tooltip(problem != null ? problem : "Votre sélection WorldEdit entre au catalogue telle quelle, prête à poser dans les cartes.");
		addRenderableWidget(a);
		addRenderableWidget(new ChoiceTile(x + tw + gap, stageTop(), w - tw - gap, h, EMPTY_PLOT, "Une parcelle neuve, à son nom : vous y "
			+ "bâtissez, puis vous l'enregistrez.", (hd, ax, ay, aw, ah) -> plotArt(hd, ax, ay, aw, ah), kind == Kind.PLOT, () -> {
				kind = Kind.PLOT;
				rebuildSoon();
			}).state("Vous y êtes conduit", false).tooltip("Au bout de la rangée « Vos structures » de l'atelier structures : vous y bâtissez "
				+ "avec WorldEdit, puis « Enregistrer » à son pupitre."));
	}

	private void buildSettings() {
		int side = previewSide();
		int x = left() + side + 14;
		int w = right() - x;
		int y = stageTop();
		HdTextField field = addRenderableWidget(new HdTextField(x, y + 14, w, 18, "Nom de la structure")
			.maxLength(50).value(name).hint("Nom de la structure…").key(NAME_KEY));
		name = field.value(); // la saisie gardée sous la clé (reconstruction de l'écran) fait foi
		field.responder(s -> {
			if (!s.equals(name)) {
				name = s;
				rebuildSoon();
			}
		});
		field.onEnter(() -> {
			if (missing() == null) {
				step++;
				rebuildSoon();
			}
		});
		setInitialFocus(field);
		y += 14 + 18 + 8 + 14;
		if (kind == Kind.SELECTION) {
			addRenderableWidget(new HdTabs(x, y, w, 16, SHELF_LABELS, shelf).onChange(i -> {
				shelf = i;
				rebuildSoon();
			}));
		} else {
			addRenderableWidget(new HdTabs(x, y, w, 16, SIZE_LABELS, size).onChange(i -> {
				size = i;
				rebuildSoon();
			}));
		}
	}

	// ------------------------------------------------------------------
	// Dessin
	// ------------------------------------------------------------------

	@Override
	protected void drawPage(Hd hd) {
		AtelierArt.steps(hd, STEPS, step, hd.px(left()), hd.px(top() + 2), hd.px(right() - left()));
		String miss = missing();
		int navLeft = hd.px(right() - 132 - (step > 0 ? 6 + 92 : 0)) - hd.u(8);
		if (miss != null) {
			int x = hd.px(left());
			int s = hd.u(7);
			int y = hd.px(navY());
			int h = hd.px(18);
			HdGlyphs.warning(hd, x, y + (h - s) / 2, s, AtelierArt.BAD);
			HdStyle st = HdStyle.PETIT.size(9.5f);
			hd.textMiddle(st, hd.ellipsize(st, miss, navLeft - x - s - hd.u(4)), x + s + hd.u(4), y, h, AtelierKit.WARN);
		}
		if (step == 0 || kind == null) {
			return;
		}
		int side = hd.px(previewSide());
		int px0 = hd.px(left());
		int py0 = hd.px(stageTop());
		hd.fill(px0 - hd.u(2), py0 - hd.u(2), px0 + side + hd.u(2), py0 + side + hd.u(2), 0xFF4A2E1C);
		if (kind == Kind.SELECTION) {
			selectionArt(hd, px0, py0, side, side, selection());
		} else {
			plotArt(hd, px0, py0, side, side);
		}
		hd.frame(px0 - hd.u(2), py0 - hd.u(2), side + hd.u(4), side + hd.u(4), Math.max(1, hd.u(0.5)), 0xFFD9B45A);
		int x = hd.px(left() + previewSide() + 14);
		int w = hd.px(right()) - x;
		int y = py0;
		HdStyle ls = HdStyle.FORT.size(10);
		int limit = hd.px(stageBottom());
		if (step == 1) {
			hd.text(ls, "Nom de la structure", x, y, Hd.INK);
			int ty = y + hd.u(14 + 18 + 8);
			hd.text(ls, kind == Kind.SELECTION ? "Rayon du catalogue" : "Taille de la parcelle", x, ty, Hd.INK);
			int py = ty + hd.u(14 + 16 + 6);
			String text = kind == Kind.SELECTION
				? "Elle reste à vous dans tout rayon (renommable, supprimable) : le rayon dit où la chercher."
				: SIZE_TEXTS[size] + ". La parcelle est quadrillée au sol ; ce qui dépasse n'est pas gardé.";
			hd.paragraph(HdStyle.PETIT, text, x, py, w, Hd.INK_SOFT, limit - py);
			return;
		}
		// Vérifier : ce qui va se passer.
		String nom = name.strip();
		HdStyle title = HdStyle.SECTION.size(12);
		hd.text(title, hd.ellipsize(title, "« " + nom + " »", w), x, y, Hd.WINE);
		y += hd.lineHeight(title) + hd.u(4);
		List<String[]> rows = new ArrayList<>();
		if (kind == Kind.SELECTION) {
			int[] s = selection();
			rows.add(new String[] {"Depuis", "votre sélection WorldEdit"});
			if (s != null) {
				rows.add(new String[] {"Taille", s[0] + " × " + s[1] + " × " + s[2] + " blocs"});
			}
			rows.add(new String[] {"Rayon", SHELF_LABELS.get(shelf)});
		} else {
			rows.add(new String[] {"Où", "atelier structures, rangée « Vos structures »"});
			rows.add(new String[] {"Taille", SIZE_LABELS.get(size).toLowerCase(java.util.Locale.ROOT) + ", " + SIZE_TEXTS[size]});
		}
		y = AtelierKit.details(hd, rows, x, y, w, limit) + hd.u(4);
		String how = kind == Kind.SELECTION
			? "La sélection entre au catalogue telle quelle. Si vous vous tenez dedans, elle se posera par rapport à l'endroit où vous êtes ; "
				+ "sinon par le milieu de sa base. Elle paraît ensuite, mise en avant, dans « À vous »."
			: "Une parcelle neuve est ouverte et vous y êtes conduit, devant son pupitre. Bâtissez avec WorldEdit dans le quadrillage, puis "
				+ "« Enregistrer » au pupitre : la structure entre au catalogue.";
		hd.paragraph(HdStyle.PETIT, how, x, y, w, Hd.INK_SOFT, limit - y);
	}

	// ------------------------------------------------------------------
	// Illustrations
	// ------------------------------------------------------------------

	/** Une sélection WorldEdit : un pavé en fil de fer doré sur l'herbe, ses deux coins marqués, sa taille s'il y en a une. */
	static void selectionArt(Hd hd, int x, int y, int w, int h, int[] sel) {
		hd.gradient(x, y, x + w, y + h, 0xFF3E4F6E, 0xFF6B8E46);
		int ground = y + h * 3 / 5;
		hd.fill(x, ground, x + w, y + h, 0xFF557A36);
		int bw = Math.min(w, h) * 9 / 20;
		int bh = bw * 2 / 3;
		int d = bw / 3;
		int bx = x + (w - bw - d) / 2;
		int by = ground - bh / 2;
		int t = Math.max(1, hd.u(0.9));
		int gold = 0xFFF2C14E;
		// Face avant, face arrière décalée, et les arêtes qui les joignent.
		hd.frame(bx, by, bw, bh, t, gold);
		hd.frame(bx + d, by - d, bw, bh, t, 0xA0F2C14E);
		HdGlyphs.line(hd, bx, by, bx + d, by - d, t, 0xC0F2C14E);
		HdGlyphs.line(hd, bx + bw, by, bx + bw + d, by - d, t, 0xC0F2C14E);
		HdGlyphs.line(hd, bx, by + bh, bx + d, by + bh - d, t, 0xC0F2C14E);
		HdGlyphs.line(hd, bx + bw, by + bh, bx + bw + d, by + bh - d, t, 0xC0F2C14E);
		hd.fill(bx, by, bx + bw, by + bh, 0x30F2C14E);
		int c = Math.max(2, hd.u(2.5));
		hd.fill(bx - c, by + bh - c, bx + c, by + bh + c, 0xFFE24A3A); // premier coin, clic gauche
		hd.fill(bx + bw + d - c, by - d - c, bx + bw + d + c, by - d + c, 0xFF4A8AE2); // second coin, clic droit
		if (sel != null && h > hd.u(40)) {
			HdStyle st = HdStyle.FORT.size(9);
			String label = sel[0] + " × " + sel[1] + " × " + sel[2];
			int tw = AtelierArt.chipWidth(hd, st, label);
			AtelierArt.chip(hd, st, label, x + (w - tw) / 2, y + h - AtelierArt.chipHeight(hd, st) - hd.u(4), 0xE0301E14);
		}
	}

	/** Une parcelle de l'atelier structures : un sol quadrillé, sa bordure, son pupitre. */
	static void plotArt(Hd hd, int x, int y, int w, int h) {
		hd.gradient(x, y, x + w, y + h, 0xFF2E3A52, 0xFF4B5E34);
		int s = Math.min(w, h) * 3 / 5;
		int px0 = x + (w - s) / 2;
		int py0 = y + (h - s) / 2 + hd.u(2);
		hd.fill(px0, py0, px0 + s, py0 + s, 0xFF5E7A3E);
		int cell = Math.max(hd.u(4), s / 9);
		for (int gx = px0 + cell; gx < px0 + s; gx += cell) {
			hd.fill(gx, py0, gx + 1, py0 + s, 0x40F2E6C9);
		}
		for (int gy = py0 + cell; gy < py0 + s; gy += cell) {
			hd.fill(px0, gy, px0 + s, gy + 1, 0x40F2E6C9);
		}
		hd.frame(px0, py0, s, s, Math.max(1, hd.u(1.2)), 0xFFB8A27A);
		// Le pupitre, devant la parcelle.
		int lw = Math.max(3, s / 7);
		int lx = px0 + s / 2 - lw / 2;
		int ly = py0 + s + hd.u(1);
		hd.fill(lx, ly, lx + lw, ly + lw * 2 / 3, 0xFF6E4A2A);
		hd.fill(lx - hd.u(1), ly - hd.u(1), lx + lw + hd.u(1), ly + hd.u(1), 0xFF8A6238);
	}
}
