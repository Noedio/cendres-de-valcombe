package fr.valcombe.client.screen.atelier;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.Vignettes;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdGlyphs;
import fr.valcombe.client.hd.HdList;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTabs;
import fr.valcombe.client.hd.HdTextField;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Atelier › Cartes › Nouvelle carte : un parcours en trois étapes, une à la fois.
 * <ol>
 * <li><b>Départ</b> : un village généré, une carte vierge, ou un fichier importé (.schem, .litematic, .nbt déjà déposé
 * dans le dossier d'import du serveur).</li>
 * <li><b>Réglages</b> : le nom (toujours facultatif : une carte vierge sans nom prend « Carte vierge » dans un atelier, et
 * n'en a pas besoin quand elle part directement dans une partie) ; pour un village, le thème, les trônes, la taille et les
 * maisons, avec l'aperçu du plan fait par le serveur ; pour un import, le fichier.</li>
 * <li><b>Vérifier</b> : le récapitulatif et où la carte naît, dans un nouvel atelier (on la retouche, puis on l'enregistre
 * dans la bibliothèque) ou directement dans une partie (son monde est remis à neuf).</li>
 * </ol>
 * Ce qui manque pour passer à l'étape suivante est écrit en rouge à côté de « Suivant », grisé tant qu'il manque.
 */
public class NewMapPage extends AtelierPage {
	public static final String VILLAGE = "Village généré";
	public static final String BLANK = "Carte vierge";
	public static final String IMPORT = "Importer un fichier";
	public static final String NEXT = "Suivant";
	public static final String PREVIOUS = "Précédent";
	public static final String IN_WORKSHOP = "Dans un atelier";
	public static final String IN_GAME = "Dans une partie";
	private static final List<String> STEPS = List.of("Départ", "Réglages", "Vérifier");
	private static final List<String> SIZES = List.of("petite", "normale", "grande");
	private static final List<String> SIZE_LABELS = List.of("Petite", "Normale", "Grande");
	private static final String NAME_KEY = "atelier.nouvelle.nom";

	private enum Kind {
		VILLAGE, BLANK, IMPORT
	}

	private static int step;
	private static Kind kind;
	private static int seats = 16;
	private static int size = 1;
	private static int theme;
	private static boolean houses = true;
	private static String file;
	private static boolean inGame;
	private static String game;
	/** Le nom tapé (gardé d'une étape à l'autre : le champ, lui, n'existe qu'à l'étape des réglages). */
	private static String name = "";

	private HdTextField nameField;
	private HdList<JsonObject> games;

	/** Le parcours depuis le début. */
	public static NewMapPage start() {
		step = 0;
		kind = null;
		file = null;
		inGame = false;
		game = null;
		name = "";
		seats = 16;
		size = 1;
		theme = 0;
		houses = true;
		HdTextField.forget(NAME_KEY);
		return new NewMapPage();
	}

	@Override
	protected List<Crumb> trail() {
		List<Crumb> t = new ArrayList<>(MapsPage.crumbs());
		t.add(new Crumb(MapsPage.NEW, null));
		return t;
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

	// ------------------------------------------------------------------
	// Réglages
	// ------------------------------------------------------------------

	private static List<String> themes() {
		List<String> t = new ArrayList<>(words(data(), "themes"));
		if (t.isEmpty()) {
			t.add("gothique");
		}
		return t;
	}

	private static String themeName() {
		List<String> t = themes();
		return t.get(Math.floorMod(theme, t.size()));
	}

	private static String themeLabel(String t) {
		return t.equals("monastere") ? "Monastère" : t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
	}

	/** La clé de l'aperçu du village réglé (voir fr.valcombe.mc.Vignettes). */
	private static String villageKey() {
		return "village:" + themeName() + ":" + seats + ":" + size + ":" + (houses ? "oui" : "non");
	}

	private String typedName() {
		return name.strip();
	}

	/** Nom d'une carte vierge sans nom tapé : « Carte vierge », numéroté s'il est déjà pris (bibliothèque, ateliers). */
	static String blankName() {
		java.util.Set<String> taken = new java.util.HashSet<>();
		for (JsonObject o : MapsPage.library()) {
			taken.add(str(o, "nom").toLowerCase(Locale.ROOT));
		}
		for (JsonObject o : list(workshops(), "ateliers")) {
			taken.add(str(o, "nom").toLowerCase(Locale.ROOT));
		}
		String base = "Carte vierge";
		if (!taken.contains(base.toLowerCase(Locale.ROOT))) {
			return base;
		}
		for (int i = 2;; i++) {
			if (!taken.contains((base + " " + i).toLowerCase(Locale.ROOT))) {
				return base + " " + i;
			}
		}
	}

	/** Le nom que prendra la carte vierge dans un atelier. */
	private String blankFinalName() {
		return typedName().isEmpty() ? blankName() : typedName();
	}

	/** Ce qui manque pour passer l'étape en cours, ou null. */
	private String missing() {
		if (step == 0 && kind == null) {
			return "Choisissez un point de départ.";
		}
		if (step == 1 && kind == Kind.IMPORT && (file == null || !words(data(), "fichiers").contains(file))) {
			return words(data(), "fichiers").isEmpty() ? "Il manque un fichier : déposez-le dans le dossier d'import." : "Choisissez le fichier.";
		}
		if (step == 2 && inGame && game == null) {
			return "Choisissez la partie.";
		}
		return null;
	}

	private static String quoted(String s) {
		return s.matches("[A-Za-z0-9_.+-]+") ? s : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	/** La commande qui crée la carte telle que réglée. */
	private String command() {
		String nom = typedName();
		if (inGame && kind != Kind.IMPORT) {
			return kind == Kind.BLANK ? "vc carte nouvelle " + game
				: "vc carte generer " + seats + " " + SIZES.get(size) + " " + themeName() + " " + (houses ? "oui" : "non") + " " + game;
		}
		return switch (kind) {
			case BLANK -> "vc atelier carte nouvelle " + blankFinalName();
			case IMPORT -> "vc atelier carte importer " + quoted(file) + (nom.isEmpty() ? "" : " " + nom);
			default -> "vc atelier carte village " + seats + " " + SIZES.get(size) + " " + themeName() + " " + (houses ? "oui" : "non")
				+ (nom.isEmpty() ? "" : " " + nom);
		};
	}

	/** Le libellé du dernier bouton, selon ce qui va se passer. */
	private String finalLabel() {
		if (inGame && kind != Kind.IMPORT) {
			return kind == Kind.BLANK ? "Envoyer une carte vierge" : "Générer dans la partie";
		}
		return switch (kind) {
			case BLANK -> "Créer dans un atelier";
			case IMPORT -> "Importer dans un atelier";
			default -> "Générer dans un atelier";
		};
	}

	private void finish() {
		String cmd = command();
		boolean blankWorkshop = kind == Kind.BLANK && !inGame;
		boolean toGame = inGame && kind != Kind.IMPORT;
		run(cmd, toGame ? "Envoi à la partie" : blankWorkshop ? "Ouverture de l'atelier" : "Préparation de l'atelier");
		HdTextField.forget(NAME_KEY);
		name = "";
		step = 0;
		kind = null;
		if (blankWorkshop) {
			onClose(); // vous êtes conduit tout de suite dans le monde neuf
		} else {
			go(new MapsPage(toGame ? 0 : 1)); // la barre en haut suit la pose ; l'atelier paraît dans « Ateliers ouverts »
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

	/** Côté de l'aperçu, à gauche des réglages. */
	private int previewSide() {
		return Math.max(50, Math.min(stageBottom() - stageTop(), (right() - left()) * 2 / 5));
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	@Override
	protected void buildPage() {
		nameField = null;
		games = null;
		if (step > 0 && kind == null) {
			step = 0;
		}
		switch (step) {
			case 1 -> buildSettings();
			case 2 -> buildReview();
			default -> buildStart();
		}
		// En bas à droite : « Précédent » et l'action suivante, mise en avant.
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
			next = button(right() - nw, navY(), nw, finalLabel(), () -> {
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
		int tw = (w - 2 * gap) / 3;
		int h = stageBottom() - stageTop();
		String[][] choices = {
			{VILLAGE, "Le village de Valcombe, bâti pour vous : trônes en cercle, maisons, salle de convocation. Thème, trônes et taille au choix."},
			{BLANK, "Un monde plat et vide, à son nom : vous y bâtissez tout avec WorldEdit, puis vous posez les repères."},
			{IMPORT, "Une construction .schem, .litematic ou .nbt déjà déposée sur le serveur, posée au centre d'un monde neuf."},
		};
		for (int i = 0; i < 3; i++) {
			Kind k = Kind.values()[i];
			Choice c = new Choice(x + i * (tw + gap), stageTop(), tw, h, choices[i][0], choices[i][1], k, kind == k, () -> {
				kind = k;
				if (k == Kind.IMPORT) {
					inGame = false;
				}
				rebuildSoon();
			});
			addRenderableWidget(c);
		}
	}

	private void buildSettings() {
		int side = previewSide();
		int x = left() + side + 14;
		int w = right() - x;
		int y = stageTop();
		int label = 52;
		switch (kind) {
			case VILLAGE -> {
				nameField = addRenderableWidget(new HdTextField(x + label, y, w - label, 18, "Nom de la carte")
					.maxLength(50).value(name).hint("Village " + themeName() + " " + seats + " (facultatif)").key(NAME_KEY));
				y += 22;
				int min = ClientState.num(data(), "tronesMin", 5);
				int max = ClientState.num(data(), "tronesMax", 20);
				seats = Math.max(min, Math.min(max, seats));
				HdButton less = button(x + label, y, 22, "−", () -> {
					seats--;
					rebuildSoon();
				});
				HdButton more = button(x + label + 22 + 40, y, 22, "+", () -> {
					seats++;
					rebuildSoon();
				});
				if (seats <= min) {
					less.disabled().tooltip(min + " trônes au moins : une partie compte " + min + " joueurs au moins.");
				}
				if (seats >= max) {
					more.disabled().tooltip(max + " trônes au plus.");
				}
				y += 22;
				addRenderableWidget(new HdTabs(x + label, y + 1, w - label, 16, SIZE_LABELS, size).onChange(i -> {
					size = i;
					rebuildSoon();
				}));
				y += 22;
				List<String> labels = themes().stream().map(NewMapPage::themeLabel).toList();
				addRenderableWidget(new HdTabs(x + label, y + 1, w - label, 16, labels, Math.floorMod(theme, labels.size())).onChange(i -> {
					theme = i;
					rebuildSoon();
				}));
				y += 22;
				toggle(x, y, w, "Maisons en face des trônes", houses, () -> {
					houses = !houses;
					rebuildSoon();
				}).tooltip("Oui : une maison par trône, où dormir la nuit. Non : chacun reste sur son trône.");
			}
			case BLANK -> {
				nameField = addRenderableWidget(new HdTextField(x, y + 14, w, 18, "Nom de la carte")
					.maxLength(50).value(name).hint(blankName() + " (facultatif)").key(NAME_KEY));
				nameField.onEnter(() -> {
					if (missing() == null) {
						step++;
						rebuildSoon();
					}
				});
				setInitialFocus(nameField);
			}
			default -> {
				List<String> files = words(data(), "fichiers");
				List<JsonObject> rows = new ArrayList<>();
				for (String f : files) {
					JsonObject o = new JsonObject();
					o.addProperty("nom", f);
					rows.add(o);
				}
				// La liste des fichiers prend la place de l'aperçu ; le nom est à droite.
				HdList<JsonObject> list = addRenderableWidget(new HdList<JsonObject>(left(), y, side, stageBottom() - y, 20, o -> str(o, "nom"))
					.items(rows).key("atelier.nouvelle.fichiers").empty("Aucun fichier")
					.onSelect(o -> {
						file = str(o, "nom");
						rebuildSoon();
					}));
				int pick = files.indexOf(file);
				list.select(pick);
				nameField = addRenderableWidget(new HdTextField(x, y + 14, w, 18, "Nom de la carte")
					.maxLength(50).value(name).hint(file == null ? "Nom de la carte (facultatif)" : baseName(file)).key(NAME_KEY));
			}
		}
		if (nameField != null) {
			nameField.responder(s -> {
				if (!s.equals(name)) {
					name = s;
					rebuildSoon();
				}
			});
		}
	}

	private static String baseName(String f) {
		return (f.contains(".") ? f.substring(0, f.lastIndexOf('.')) : f).replace('_', ' ');
	}

	private void buildReview() {
		int side = previewSide();
		int x = left() + side + 14;
		int w = right() - x;
		int y = stageTop();
		boolean canGame = kind != Kind.IMPORT;
		if (!canGame) {
			inGame = false;
		}
		List<String> where = canGame ? List.of(IN_WORKSHOP, IN_GAME) : List.of(IN_WORKSHOP);
		addRenderableWidget(new HdTabs(x, y, w, 16, where, inGame ? 1 : 0).onChange(i -> {
			inGame = i == 1;
			rebuildSoon();
		}));
		if (inGame) {
			List<JsonObject> all = MapSheetPage.games();
			int ly = y + 22;
			int lh = stageBottom() - ly - 26;
			games = addRenderableWidget(new HdList<JsonObject>(x, ly, w, Math.max(28, lh), 28, g -> str(g, "nom"))
				.items(all).key("atelier.nouvelle.parties").empty("Aucun monde de partie")
				.onSelect(g -> {
					game = str(g, "id");
					rebuildSoon();
				}));
			games.row((hd, g, i, rx, ry, rw, rh, hovered, sel) -> {
				if (sel) {
					hd.fill(rx, ry, rx + rw, ry + rh, Hd.SELECTION);
				} else if (hovered) {
					hd.fill(rx, ry, rx + rw, ry + rh, Hd.HOVER);
				}
				int pad = hd.u(3);
				int s = rh - 2 * pad;
				Vignettes.draw(hd, "partie:" + str(g, "id"), str(g, "vignette"), rx + pad, ry + pad, s, s);
				int tx = rx + pad + s + hd.u(5);
				int tw = rx + rw - pad - tx;
				String name = str(g, "nom") + (ClientState.bool(g, "principale") ? " (principale)" : "");
				hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, name, tw), tx, ry + pad - hd.u(1), sel ? Hd.LIGHT : Hd.INK);
				hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, MapSheetPage.partyLine(g), tw), tx, ry + pad - hd.u(1)
					+ hd.lineHeight(HdStyle.FORT), sel ? Hd.GOLD : Hd.INK_SOFT);
			});
			int pick = all.isEmpty() ? -1 : 0;
			for (int i = 0; i < all.size(); i++) {
				if (str(all.get(i), "id").equals(game)) {
					pick = i;
				}
			}
			games.select(pick);
			game = games.selected() == null ? null : str(games.selected(), "id");
		}
	}

	// ------------------------------------------------------------------
	// Dessin
	// ------------------------------------------------------------------

	@Override
	protected void drawPage(Hd hd) {
		AtelierArt.steps(hd, STEPS, step, hd.px(left()), hd.px(top() + 2), hd.px(right() - left()));
		// Ce qui manque, en rouge, à gauche de la navigation.
		String miss = missing();
		int navLeft = hd.px(right() - 132 - (step > 0 ? 6 + 92 : 0)) - hd.u(8);
		if (miss != null) {
			int x = hd.px(left());
			int s = hd.u(7);
			int y = hd.px(navY());
			int h = hd.px(18);
			HdGlyphs.warning(hd, x, y + (h - s) / 2, s, AtelierArt.BAD);
			HdStyle st = HdStyle.PETIT.size(9.5f);
			hd.textMiddle(st, hd.ellipsize(st, miss, navLeft - x - s - hd.u(4)), x + s + hd.u(4), y, h, 0xFF7A1E24);
		}
		if (step == 0) {
			return;
		}
		int side = hd.px(previewSide());
		int px0 = hd.px(left());
		int py0 = hd.px(stageTop());
		int x = hd.px(left() + previewSide() + 14);
		int w = hd.px(right()) - x;
		int y = py0;
		if (!(step == 1 && kind == Kind.IMPORT)) {
			drawPreview(hd, px0, py0, side);
		}
		if (step == 1) {
			drawSettings(hd, x, y, w);
		} else {
			drawReview(hd, x, y, w);
		}
	}

	/** L'aperçu à gauche : le plan du village réglé, ou l'illustration du point de départ. */
	private void drawPreview(Hd hd, int x, int y, int s) {
		hd.fill(x - hd.u(2), y - hd.u(2), x + s + hd.u(2), y + s + hd.u(2), 0xFF4A2E1C);
		switch (kind) {
			case VILLAGE -> Vignettes.draw(hd, villageKey(), "v1", x, y, s, s);
			case BLANK -> blankArt(hd, x, y, s, s);
			default -> fileArt(hd, x, y, s, s, file);
		}
		hd.frame(x - hd.u(2), y - hd.u(2), s + hd.u(4), s + hd.u(4), Math.max(1, hd.u(0.5)), 0xFFD9B45A);
	}

	private void drawSettings(Hd hd, int x, int y, int w) {
		HdStyle ls = HdStyle.FORT.size(10);
		int row = hd.u(22);
		int h18 = hd.u(18);
		switch (kind) {
			case VILLAGE -> {
				String[] labels = {"Nom", "Trônes", "Taille", "Thème"};
				for (int i = 0; i < labels.length; i++) {
					hd.textMiddle(ls, labels[i], x, y + i * row, h18, Hd.INK);
				}
				// Le nombre au milieu de la case entre « − » et « + » (40 de large).
				hd.textCentered(HdStyle.FORT, String.valueOf(seats), x + hd.u(52 + 22 + 20), y + row + (h18 - hd.lineHeight(HdStyle.FORT)) / 2, Hd.INK);
				int tx = x + hd.u(52 + 22 + 40 + 22 + 8);
				int room = x + w - tx;
				if (room > hd.u(40)) {
					hd.textMiddle(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, "jusqu'à " + seats + " joueurs", room), tx, y + row, h18, Hd.INK_SOFT);
				}
			}
			case BLANK -> {
				hd.text(ls, "Nom de la carte (facultatif)", x, y, Hd.INK);
				int py = y + hd.u(14 + 18 + 8);
				hd.paragraph(HdStyle.TEXTE, "Un monde plat et vide. Bâtissez avec WorldEdit, posez les trônes, les maisons, le centre "
					+ "et la salle de convocation avec les outils de repères, puis « Enregistrer » : elle rejoindra la bibliothèque. "
					+ "Envoyée directement à une partie, elle n'a pas besoin de nom.",
					x, py, w, Hd.INK_SOFT, hd.px(stageBottom()) - py);
			}
			default -> {
				hd.text(ls, "Nom de la carte", x, y, Hd.INK);
				int py = y + hd.u(14 + 18 + 8);
				String dir = str(data(), "dossierImport");
				String text = words(data(), "fichiers").isEmpty()
					? "Aucun fichier dans le dossier d'import. Déposez-y un fichier .schem (WorldEdit), .litematic (Litematica) ou .nbt "
						+ "(structure Minecraft) : il paraît ici tout seul. Dossier : " + dir
					: "La construction est posée au centre d'un monde neuf, sans repères : posez ensuite les trônes et les maisons avec les "
						+ "outils de repères, puis enregistrez. Dossier d'import : " + dir;
				hd.paragraph(HdStyle.PETIT, text, x, py, w, Hd.INK_SOFT, hd.px(stageBottom()) - py);
			}
		}
	}

	private void drawReview(Hd hd, int x, int y, int w) {
		if (inGame) {
			int ny = hd.px(stageBottom() - 24);
			JsonObject g = games == null ? null : games.selected();
			String warn = g == null ? "Choisissez la partie." : "Le monde de « " + str(g, "nom") + " » sera remis à neuf : tout ce qui s'y "
				+ "trouve est remplacé." + (ClientState.bool(g, "enCours") ? " Une partie s'y joue." : "") + " La carte n'entre pas dans la "
				+ "bibliothèque tant qu'on ne l'y enregistre pas.";
			hd.paragraph(HdStyle.PETIT, warn, x, ny, w, 0xFF7A1E24, hd.px(stageBottom()) - ny);
			return;
		}
		int ty = y + hd.u(22);
		String nom = typedName();
		String what = switch (kind) {
			case VILLAGE -> "Un village " + themeName() + " de " + seats + " trônes, taille " + SIZES.get(size) + ", "
				+ (houses ? "avec ses maisons" : "sans maisons") + (nom.isEmpty() ? "." : ", nommé « " + nom + " ».");
			case BLANK -> "Une carte vierge, « " + blankFinalName() + " ».";
			default -> "La construction « " + file + " »" + (nom.isEmpty() ? "." : ", nommée « " + nom + " ».");
		};
		ty += hd.paragraph(HdStyle.TEXTE, what, x, ty, w, Hd.INK, hd.u(40)) + hd.u(4);
		String how = (kind == Kind.BLANK ? "Un monde neuf est créé, et vous y êtes conduit tout de suite. "
			: "Un monde neuf est créé et la carte y est posée par petits lots (la barre en haut suit la pose) ; vous y êtes conduit à la fin. ")
			+ "On y bâtit avec WorldEdit, on y pose les repères, puis « Enregistrer » la range dans la bibliothèque (elle s'enregistre aussi "
			+ "toute seule toutes les 5 minutes).";
		hd.paragraph(HdStyle.PETIT, how, x, ty, w, Hd.INK_SOFT, hd.px(stageBottom()) - ty);
	}

	// ------------------------------------------------------------------
	// Illustrations
	// ------------------------------------------------------------------

	/** Une carte vierge : l'herbe du monde plat, quadrillée, et la croix de la place. */
	static void blankArt(Hd hd, int x, int y, int w, int h) {
		hd.gradient(x, y, x + w, y + h, 0xFF6B8E46, 0xFF557A36);
		int cell = Math.max(hd.u(5), w / 12);
		for (int gx = x + cell; gx < x + w; gx += cell) {
			hd.fill(gx, y, gx + 1, y + h, 0x20000000);
		}
		for (int gy = y + cell; gy < y + h; gy += cell) {
			hd.fill(x, gy, x + w, gy + 1, 0x20000000);
		}
		int s = Math.max(hd.u(5), w / 10);
		HdGlyphs.cross(hd, x + (w - s) / 2, y + (h - s) / 2, s, 0xE0F2E6C9, 1f);
		AtelierArt.compass(hd, x + w - hd.u(12), y + hd.u(12), hd.u(7));
	}

	/** Un fichier : une feuille cornée, son extension en grand. */
	static void fileArt(Hd hd, int x, int y, int w, int h, String name) {
		hd.gradient(x, y, x + w, y + h, 0xFF3A3040, 0xFF241C28);
		int pw = Math.min(w, h) * 5 / 9;
		int ph = pw * 5 / 4;
		int fx = x + (w - pw) / 2;
		int fy = y + (h - ph) / 2;
		int corner = pw / 4;
		hd.fill(fx, fy + corner, fx + pw, fy + ph, 0xFFE9DCC0);
		hd.fill(fx, fy, fx + pw - corner, fy + corner, 0xFFE9DCC0);
		hd.triangle(fx + pw - corner / 2, fy + corner / 3, corner, corner * 2 / 3, 0xFFC8B48E);
		String ext = name == null || !name.contains(".") ? "SCHEM" : name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
		HdStyle st = HdStyle.SECTION.size(Math.max(7, pw / (float) hd.scale() / 5));
		hd.textCentered(st, hd.ellipsize(st, ext, pw - hd.u(4)), fx + pw / 2, fy + ph / 2 - hd.lineHeight(st) / 2, 0xFF6E1E28);
		for (int i = 0; i < 3; i++) {
			int ly = fy + ph * 3 / 4 + i * hd.u(3);
			if (ly < fy + ph - hd.u(2)) {
				hd.fill(fx + pw / 6, ly, fx + pw * 5 / 6, ly + Math.max(1, hd.u(0.6)), 0x60301E14);
			}
		}
	}

	// ------------------------------------------------------------------
	// Un point de départ
	// ------------------------------------------------------------------

	/** Un point de départ de la première étape : illustration, nom, ce qu'il donne ; doré quand il est choisi. */
	final class Choice extends HdButton {
		private final String text;
		private final Kind what;
		private final boolean chosen;

		Choice(int x, int y, int w, int h, String title, String text, Kind what, boolean chosen, Runnable action) {
			super(x, y, w, h, title, action);
			this.text = text;
			this.what = what;
			this.chosen = chosen;
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
			try (Hd hd = Hd.begin(g)) {
				int x = hd.px(getX());
				int y = hd.px(getY());
				int w = hd.px(getWidth());
				int h = hd.px(getHeight());
				boolean hot = isHoveredOrFocused();
				hd.inset(x, y, w, h);
				if (chosen) {
					hd.fill(x, y, x + w, y + h, 0x30F2C14E);
				}
				int pad = hd.u(4);
				int aw = w - 2 * pad;
				int ah = Math.min(aw * 3 / 5, h * 9 / 20);
				int ax = x + pad;
				int ay = y + pad;
				hd.clip(ax, ay, ax + aw, ay + ah);
				switch (what) {
					case VILLAGE -> {
						hd.fill(ax, ay, ax + aw, ay + ah, 0xFF4F6E3A);
						int s = Math.max(aw, ah);
						Vignettes.draw(hd, "village:gothique:12:1:oui", "v1", ax + (aw - s) / 2, ay + (ah - s) / 2, s, s);
					}
					case BLANK -> blankArt(hd, ax, ay, aw, ah);
					default -> fileArt(hd, ax, ay, aw, ah, null);
				}
				hd.unclip();
				AtelierArt.frameArt(hd, ax, ay, aw, ah);
				HdStyle ts = HdStyle.SECTION.size(w < hd.u(120) ? 10 : 11.5f);
				int ty = ay + ah + hd.u(4);
				hd.text(ts, hd.ellipsize(ts, getMessage().getString(), aw - hd.u(10)), x + pad + hd.u(1), ty, chosen || hot ? Hd.WINE : Hd.INK);
				ty += hd.lineHeight(ts) + hd.u(1);
				hd.paragraph(HdStyle.PETIT, text, x + pad + hd.u(1), ty, aw - hd.u(2), Hd.INK_SOFT, y + h - pad - ty);
				if (chosen) {
					hd.frame(x, y, w, h, Math.max(1, hd.u(1.25)), 0xFFD9B45A);
					int s = hd.u(11);
					int bx = x + w - s - hd.u(4);
					int by = y + hd.u(4);
					hd.disc(bx + s / 2, by + s / 2, s / 2, AtelierArt.GOOD);
					HdGlyphs.check(hd, bx + s / 4, by + s / 4, s / 2, Hd.LIGHT);
				} else if (hot) {
					hd.frame(x, y, w, h, Math.max(1, hd.u(1)), 0xA0D9B45A);
				}
			}
		}
	}
}
