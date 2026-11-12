package fr.valcombe.client.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.client.ui.WaxButton;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * L'Atelier dans le jeu, pour un opérateur : bibliothèque de cartes, générateur de village, repères
 * du mode construction, catalogue de structures, outils de sélection, scénarios et rôles créés.
 * Chaque bouton lance la commande /vc que l'on taperait (celles qu'utilise aussi la page web) ;
 * ce qui demande du texte (un nom, un bloc) s'écrit dans le chat, déjà commencé. L'écriture d'un
 * rôle ou d'un scénario reste à la page web.
 */
public class AtelierScreen extends BaseScreen {
	private static final String[] TABS = {"Cartes", "Village", "Repères", "Structures", "Sélection", "Créations"};
	private static final String[] SIZES = {"petite", "normale", "grande"};
	private static final String[] SHELVES = {"maisons", "place", "decor", "perso"};
	private static final String[] SHELF_NAMES = {"Maisons", "Place", "Décor", "Les vôtres"};
	/** Hauteur d'une rangée de boutons, et nombre de cartes ou de scénarios montrés à la fois. */
	private static final int ROW = 19;
	private static final int PAGE = 5;
	/** Côté de l'aperçu d'une carte, en pixels d'interface. */
	private static final int PREVIEW = 96;

	private static int tab;
	/** Action à confirmer d'un second clic (la commande qu'elle lancera). */
	private static String armed;
	/** Carte choisie dans la bibliothèque ; chaîne vide : la carte actuelle. */
	private static String map = "";
	private static int mapPage;
	/** Aperçu déjà demandé au serveur (carte et état du chantier), pour ne pas le redemander à chaque image. */
	private static String asked;
	private static int seats = 16;
	private static int size = 1;
	private static int theme;
	private static boolean houses = true;
	/** Rasage de l'ancienne carte : tout ce qu'elle a bâti (false), ou un grand carré fixe (true). */
	private static boolean razeSquare;
	private static int shelf;
	private static String structure;
	private static int structurePage;
	private static boolean showRoles;
	private static String scenario;
	private static String role;
	private static int creationPage;
	/** Numéro de la dernière réponse du serveur à l'ouverture : on ne montre que celles reçues depuis. */
	private final int repliesBefore;

	public AtelierScreen() {
		super("Atelier de Valcombe");
		asked = null;
		armed = null;
		repliesBefore = ClientState.num(ClientState.obj(data(), "reponse"), "n", 0);
	}

	/** Ouvre l'écran sur un onglet précis (0 : cartes … 5 : créations). */
	public AtelierScreen(int openTab) {
		this();
		tab = Math.max(0, Math.min(TABS.length - 1, openTab));
	}

	@Override
	protected int[] panelSize() {
		return new int[] {410, 216};
	}

	/** Ce que le serveur envoie pour l'Atelier (opérateurs seulement), ou null. */
	private static JsonObject data() {
		return ClientState.obj(view(), "atelier");
	}

	private int row(int i) {
		return py + 42 + i * ROW;
	}

	private void go(String command) {
		armed = null;
		send(command);
	}

	private WaxButton action(int x, int y, int w, String label, String command) {
		return button(x, y, w, label, () -> go(command));
	}

	/** Bouton à confirmer : le premier clic l'arme (« Confirmer »), le second lance la commande. */
	private WaxButton confirm(int x, int y, int w, String label, String command) {
		boolean ready = command.equals(armed);
		return button(x, y, w, ready ? (w < 100 ? "Confirmer" : "Confirmer : " + lower(label)) : label, () -> {
			if (ready) {
				go(command);
			} else {
				armed = command;
			}
			rebuildWidgets();
		});
	}

	private static String lower(String label) {
		return label.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + label.substring(1);
	}

	/** Ce qui demande du texte s'écrit dans le chat, la commande déjà commencée (avec les suggestions du jeu). */
	private WaxButton chat(int x, int y, int w, String label, String prefix) {
		return button(x, y, w, label, () -> {
			armed = null;
			minecraft.gui.setScreen(new ChatScreen("/" + prefix, false));
		});
	}

	private String fit(String text, int width) {
		return font.width(text) <= width ? text : font.plainSubstrByWidth(text, width - font.width("…")) + "…";
	}

	/** Une rangée d'onglets sur toute la largeur : chacun a la place de son libellé, pour qu'aucun ne soit coupé. */
	private void tabs(int x, int y, int width, String[] labels, int current, java.util.function.IntConsumer choose) {
		int text = 0;
		for (String label : labels) {
			text += font.width(label);
		}
		int pad = (width - 2 * (labels.length - 1) - text) / labels.length;
		for (int i = 0; i < labels.length; i++) {
			int index = i;
			int w = font.width(labels[i]) + pad;
			var b = button(x, y, w, labels[i], () -> {
				choose.accept(index);
				armed = null;
				rebuildWidgets();
			});
			if (i == current) {
				b.disabled();
			}
			x += w + 2;
		}
	}

	/** Rangée « page précédente, page suivante » sous une liste trop longue ; renvoie la page corrigée. */
	private int pager(int x, int y, int w, int page, int count, int perPage, java.util.function.IntConsumer set) {
		int pages = Math.max(1, (count + perPage - 1) / perPage);
		int p = Math.max(0, Math.min(pages - 1, page));
		if (pages > 1) {
			var prev = button(x, y, 26, "◀", () -> {
				set.accept(p - 1);
				rebuildWidgets();
			});
			var next = button(x + w - 26, y, 26, "▶", () -> {
				set.accept(p + 1);
				rebuildWidgets();
			});
			if (p == 0) {
				prev.disabled();
			}
			if (p == pages - 1) {
				next.disabled();
			}
		}
		return p;
	}

	@Override
	protected void build() {
		JsonObject a = data();
		tabs(px + 12, py + 8, pw - 24, TABS, tab, i -> tab = i);
		button(px + 12, py + ph - 26, 130, "Page web (lien)", () -> {
			armed = null;
			send("vc atelier");
			onClose();
		});
		button(px + pw - 112, py + ph - 26, 100, "Retour", () -> {
			armed = null;
			minecraft.gui.setScreen(new MainScreen());
		});
		if (a == null) {
			return;
		}
		switch (tab) {
			case 0 -> buildMaps(a);
			case 1 -> buildVillage(a);
			case 2 -> buildMarkers(a);
			case 3 -> buildStructures(a);
			case 4 -> buildSelection(a);
			default -> buildCreations(a);
		}
	}

	// ------------------------------------------------------------------
	// Cartes
	// ------------------------------------------------------------------
	private void buildMaps(JsonObject a) {
		List<JsonObject> maps = objects(a, "cartes");
		JsonObject current = ClientState.obj(a, "carte");
		boolean busy = ClientState.bool(a, "chantier");
		if (!map.isEmpty() && maps.stream().noneMatch(m -> map.equals(ClientState.str(m, "id")))) {
			map = ""; // la carte choisie vient d'être supprimée ou renommée ailleurs
		}
		int x = px + 12;
		var here = button(x, row(0), 150, "Carte actuelle", () -> {
			map = "";
			armed = null;
			rebuildWidgets();
		});
		if (map.isEmpty()) {
			here.disabled();
		}
		mapPage = pager(x, row(6), 150, mapPage, maps.size(), PAGE, p -> mapPage = p);
		for (int i = 0; i < PAGE && mapPage * PAGE + i < maps.size(); i++) {
			JsonObject m = maps.get(mapPage * PAGE + i);
			String id = ClientState.str(m, "id");
			var b = button(x, row(i + 1), 150, ClientState.str(m, "nom"), () -> {
				map = id;
				armed = null;
				rebuildWidgets();
			});
			if (id.equals(map)) {
				b.disabled();
			}
		}
		// L'aperçu vu de dessus est demandé une fois par carte, et de nouveau quand un chantier se termine.
		String key = map + "|" + busy;
		if (!key.equals(asked) && (!map.isEmpty() || current != null)) {
			asked = key;
			send("vc carte apercu" + (map.isEmpty() ? "" : " " + map));
		}
		int bx = px + 274;
		int bw = pw - 274 - 12;
		if (map.isEmpty()) {
			int r = 0;
			if (current != null) {
				String id = ClientState.str(current, "id");
				if (id != null && !id.isEmpty()) {
					// Carte venue de la bibliothèque : un clic la range sous le même identifiant et le même nom.
					var save = action(bx, row(r++), bw, "Enregistrer", "vc carte enregistrer " + id + " " + ClientState.str(current, "nom"));
					if (busy) {
						save.disabled();
					}
				}
				var saveAs = chat(bx, row(r++), bw, "Enregistrer sous…", "vc carte enregistrer ");
				if (busy) {
					saveAs.disabled();
				}
			}
			var blank = confirm(bx, row(r++), bw, "Carte vide ici", "vc carte nouvelle");
			var file = chat(bx, row(r++), bw, "Importer un fichier…", "vc carte importer ");
			if (busy) {
				blank.disabled();
				file.disabled();
			}
			if (current != null) {
				confirm(bx, row(r), bw, "Oublier la carte", "vc carte oublier");
			}
		} else {
			var load = confirm(bx, row(0), bw, "Charger", "vc carte charger " + map);
			if (busy) {
				load.disabled();
			}
			chat(bx, row(1), bw, "Renommer…", "vc carte renommer " + map + " ");
			action(bx, row(2), bw, "Dupliquer", "vc carte copier " + map);
			confirm(bx, row(3), bw, "Supprimer", "vc carte supprimer " + map);
		}
	}

	private void drawMaps(GuiGraphicsExtractor g, JsonObject a) {
		int x = px + 170;
		int y = py + 44;
		g.fill(x - 1, y - 1, x + PREVIEW + 1, y + PREVIEW + 1, Theme.BRONZE);
		g.fill(x, y, x + PREVIEW, y + PREVIEW, 0xFF2A2024);
		JsonObject pv = ClientState.preview();
		String shown = pv == null ? null : ClientState.str(pv, "id");
		if (pv != null && map.equals(shown == null ? "" : shown)) {
			drawPreview(g, pv, x, y);
		} else {
			Theme.centered(g, font, map.isEmpty() && ClientState.obj(a, "carte") == null ? "Aucune carte" : "Aperçu…",
				x + PREVIEW / 2, y + PREVIEW / 2 - 4, Theme.PARCH);
		}
		String info;
		if (map.isEmpty()) {
			info = ClientState.str(a, "resume");
		} else {
			JsonObject m = objects(a, "cartes").stream().filter(c -> map.equals(ClientState.str(c, "id"))).findFirst().orElse(null);
			info = m == null ? "" : "« " + ClientState.str(m, "nom") + " » (" + map + "), " + ClientState.num(m, "sieges", 0) + " trônes, "
				+ ClientState.num(m, "maisons", 0) + " maisons, " + (ClientState.bool(m, "genere")
					? "village généré (" + ClientState.str(m, "theme") + ")." : "faite à la main.");
		}
		Theme.paragraph(g, font, info == null ? "" : info, x, y + PREVIEW + 4, pw - 170 - 12, Theme.INK, 32);
	}

	/** La carte vue de dessus (une couleur par case), puis ses repères : trônes dorés, maisons vertes, salle violette, centre blanc. */
	private static void drawPreview(GuiGraphicsExtractor g, JsonObject pv, int x, int y) {
		int side = ClientState.num(pv, "cote", 0);
		int[] colors = ClientState.previewColors();
		if (side > 0 && colors.length >= side * side) {
			int cell = PREVIEW / side;
			for (int cy = 0; cy < side; cy++) {
				// Les cases voisines de même couleur sont dessinées d'un trait.
				for (int cx = 0; cx < side; ) {
					int c = colors[cy * side + cx];
					int end = cx + 1;
					while (end < side && colors[cy * side + end] == c) {
						end++;
					}
					g.fill(x + cx * cell, y + cy * cell, x + end * cell, y + (cy + 1) * cell, c);
					cx = end;
				}
			}
		}
		double k = PREVIEW / (2.0 * Math.max(4, ClientState.num(pv, "rayon", 12)) + 1);
		int mx = x + PREVIEW / 2;
		int my = y + PREVIEW / 2;
		mark(g, pv.get("maisons"), mx, my, k, x, y, 0xFF6FB37A);
		JsonElement room = pv.get("salle");
		if (room != null && room.isJsonArray()) {
			dot(g, room, mx, my, k, x, y, 0xFFC58BE6);
		}
		g.fill(mx - 1, my - 1, mx + 2, my + 2, 0xFFFFFFFF);
		mark(g, pv.get("sieges"), mx, my, k, x, y, 0xFFD9B45A);
	}

	private static void mark(GuiGraphicsExtractor g, JsonElement list, int mx, int my, double k, int x, int y, int color) {
		if (list != null && list.isJsonArray()) {
			for (JsonElement e : list.getAsJsonArray()) {
				dot(g, e, mx, my, k, x, y, color);
			}
		}
	}

	private static void dot(GuiGraphicsExtractor g, JsonElement at, int mx, int my, double k, int x, int y, int color) {
		var a = at.getAsJsonArray();
		int dx = mx + (int) Math.round(a.get(0).getAsInt() * k);
		int dy = my + (int) Math.round(a.get(1).getAsInt() * k);
		if (dx >= x + 1 && dx <= x + PREVIEW - 2 && dy >= y + 1 && dy <= y + PREVIEW - 2) {
			g.fill(dx - 1, dy - 1, dx + 2, dy + 2, 0xFF140D10);
			g.fill(dx - 1, dy - 1, dx + 1, dy + 1, color);
		}
	}

	// ------------------------------------------------------------------
	// Village généré
	// ------------------------------------------------------------------
	private void buildVillage(JsonObject a) {
		List<String> themes = strings(a, "themes");
		int min = ClientState.num(a, "tronesMin", 5);
		int max = ClientState.num(a, "tronesMax", 20);
		seats = Math.max(min, Math.min(max, seats));
		theme = themes.isEmpty() ? 0 : theme % themes.size();
		int cx = px + pw / 2;
		var less = button(cx - 100, row(0), 26, "−", () -> {
			seats--;
			armed = null;
			rebuildWidgets();
		});
		var more = button(cx + 74, row(0), 26, "+", () -> {
			seats++;
			armed = null;
			rebuildWidgets();
		});
		if (seats <= min) {
			less.disabled();
		}
		if (seats >= max) {
			more.disabled();
		}
		button(cx - 100, row(1), 200, "Taille : " + SIZES[size], () -> {
			size = (size + 1) % SIZES.length;
			armed = null;
			rebuildWidgets();
		});
		String themeName = themes.isEmpty() ? "gothique" : themes.get(theme);
		button(cx - 100, row(2), 200, "Thème : " + themeName, () -> {
			theme++;
			armed = null;
			rebuildWidgets();
		});
		button(cx - 100, row(3), 200, "Maisons : " + (houses ? "oui" : "non"), () -> {
			houses = !houses;
			armed = null;
			rebuildWidgets();
		});
		button(cx - 100, row(4), 200, razeSquare ? "Raser : un carré de 200 blocs" : "Raser : tout ce que la carte a bâti", () -> {
			razeSquare = !razeSquare;
			armed = null;
			rebuildWidgets();
		});
		var gen = confirm(cx - 100, row(5) + 4, 200, "Générer le village",
			"vc carte generer " + seats + " " + SIZES[size] + " " + themeName + " " + (houses ? "oui" : "non")
				+ (razeSquare ? " carre" : " bati"));
		if (ClientState.bool(a, "chantier")) {
			gen.disabled();
		}
	}

	private void drawVillage(GuiGraphicsExtractor g) {
		Theme.centered(g, font, "Trônes : " + seats, px + pw / 2, row(0) + 5, Theme.INK);
		Theme.paragraph(g, font, "Le village généré remplace la carte actuelle, au même endroit (sans carte : là où vous êtes). "
			+ "Tout ce que l'ancienne carte a bâti est rasé d'abord. Une carte retouchée à la main est sauvegardée dans la bibliothèque.",
			px + 40, row(6) + 8, pw - 80, Theme.ASH, 32);
	}

	// ------------------------------------------------------------------
	// Repères (mode construction)
	// ------------------------------------------------------------------
	private void buildMarkers(JsonObject a) {
		boolean building = ClientState.bool(ClientState.obj(a, "editeur"), "construction");
		int x = px + pw - 12 - 156;
		button(x, row(0), 156, building ? "Quitter le mode construction" : "Entrer en mode construction", () -> {
			go("vc carte construction");
			if (!building) {
				onClose(); // on entre pour bâtir : l'écran laisse la place au monde
			}
		});
		if (ClientState.obj(a, "carte") == null) {
			return;
		}
		action(x, row(1), 77, "Trône ici", "vc carte trone");
		action(x + 79, row(1), 77, "Maison ici", "vc carte maison");
		action(x, row(2), 77, "Centre ici", "vc carte centre");
		action(x + 79, row(2), 77, "Mes outils", "vc carte outils");
		action(x, row(3), 156, "Renuméroter (sens horaire)", "vc carte ordonner");
		action(x, row(4), 156, "Annuler le dernier geste", "vc carte annuler");
		action(x, row(5), 156, "Rebâtir la salle d'attente", "vc carte salle");
	}

	private void drawMarkers(GuiGraphicsExtractor g, JsonObject a) {
		JsonObject current = ClientState.obj(a, "carte");
		int x = px + 14;
		int y = py + 44;
		int w = pw - 14 - 12 - 156 - 10;
		if (current == null) {
			Theme.paragraph(g, font, "Aucune carte sur le serveur : générez un village (onglet Village) ou partez d'une carte vide "
				+ "(onglet Cartes).", x, y, w, Theme.ASH);
			return;
		}
		Theme.ink(g, font, "Liste de contrôle", x, y, Theme.WINE);
		y += 12;
		for (JsonObject c : objects(current, "controle")) {
			boolean ok = ClientState.bool(c, "ok");
			y += Theme.paragraph(g, font, (ok ? "✔ " : "⚠ ") + ClientState.str(c, "texte"), x, y, w, ok ? Theme.INK : Theme.BLOOD, 20) + 2;
		}
	}

	// ------------------------------------------------------------------
	// Catalogue de structures
	// ------------------------------------------------------------------
	private void buildStructures(JsonObject a) {
		JsonObject ed = ClientState.obj(a, "editeur");
		boolean building = ClientState.bool(ed, "construction");
		String held = ClientState.str(ed, "structure");
		// À gauche, les rayons et la liste (un nom par ligne, jamais coupé) ; à droite, ce qu'on fait de la structure.
		int lw = 224;
		tabs(px + 12, row(0), lw, SHELF_NAMES, shelf, i -> {
			shelf = i;
			structurePage = 0;
		});
		List<JsonObject> list = new ArrayList<>();
		for (JsonObject s : objects(a, "structures")) {
			if (SHELVES[shelf].equals(ClientState.str(s, "categorie"))) {
				list.add(s);
			}
		}
		structurePage = pager(px + 12, row(6), lw, structurePage, list.size(), PAGE, p -> structurePage = p);
		JsonObject chosen = null;
		for (int i = 0; i < PAGE && structurePage * PAGE + i < list.size(); i++) {
			JsonObject s = list.get(structurePage * PAGE + i);
			String id = ClientState.str(s, "id");
			var b = button(px + 12, row(1 + i), lw, ClientState.str(s, "nom"), () -> {
				structure = id;
				armed = null;
				rebuildWidgets();
			});
			if (id.equals(structure)) {
				b.disabled();
				chosen = s;
			}
		}
		int x = px + 12 + lw + 6;
		int w = pw - 12 - x + px;
		if (chosen != null) {
			String id = structure;
			button(x, row(0), w, "Prendre en main", () -> {
				// La pose se fait en mode construction : on y entre au besoin, puis l'écran laisse la place au monde.
				if (!building) {
					send("vc carte construction");
				}
				go("vc structure poser " + id);
				onClose();
			});
			if (!ClientState.bool(chosen, "fournie")) {
				chat(x, row(1), w / 2 - 1, "Renommer…", "vc structure renommer " + id + " ");
				confirm(x + w / 2 + 1, row(1), w - w / 2 - 1, "Supprimer", "vc structure supprimer " + id);
			}
		}
		if (held != null) {
			action(x, row(2), w / 2 - 1, "Tourner", "vc structure tourner");
			action(x + w / 2 + 1, row(2), w - w / 2 - 1, "Retourner", "vc structure miroir");
			action(x, row(3), w, "Poser à mes pieds", "vc structure ici");
			action(x, row(4), w, "Ranger la structure", "vc structure fin");
		}
		chat(x, row(5), w, "Enregistrer la sélection…", "vc structure enregistrer ");
		chat(x, row(6), w, "Importer un fichier…", "vc structure importer ");
	}

	/** La ligne du haut de l'onglet Structures : la structure choisie (taille, repère) et celle qu'on a en main. */
	private static String structureLine(JsonObject a) {
		JsonObject ed = ClientState.obj(a, "editeur");
		String held = ClientState.str(ed, "structure");
		String info = "Choisissez une structure, puis « Prendre en main ».";
		for (JsonObject s : objects(a, "structures")) {
			String id = ClientState.str(s, "id");
			if (id.equals(structure) && SHELVES[shelf].equals(ClientState.str(s, "categorie"))) {
				var t = s.getAsJsonArray("taille");
				String mark = ClientState.str(s, "repere");
				info = t.get(0).getAsInt() + " × " + t.get(1).getAsInt() + " × " + t.get(2).getAsInt()
					+ ("maison".equals(mark) ? ", avec un repère de maison" : "salle".equals(mark) ? ", avec le repère de la salle" : "") + ".";
			}
			if (id.equals(held)) {
				held = ClientState.str(s, "nom");
			}
		}
		if (held != null) {
			String turn = ClientState.str(ed, "orientation");
			info += " En main : " + held + (turn == null || turn.isEmpty() ? "" : " (" + turn + ")") + ".";
		}
		return info;
	}

	private void drawStructures(GuiGraphicsExtractor g, JsonObject a) {
		boolean empty = objects(a, "structures").stream().noneMatch(s -> SHELVES[shelf].equals(ClientState.str(s, "categorie")));
		if (empty) {
			Theme.paragraph(g, font, "Aucune structure ici : enregistrez une sélection, ou importez un fichier .schem, .litematic ou .nbt.",
				px + 14, row(1) + 4, 220, Theme.ASH);
		}
	}

	// ------------------------------------------------------------------
	// Outils de sélection
	// ------------------------------------------------------------------
	private void buildSelection(JsonObject a) {
		JsonObject ed = ClientState.obj(a, "editeur");
		if (!ClientState.bool(ed, "construction")) {
			button(px + pw / 2 - 100, row(2), 200, "Entrer en mode construction", () -> go("vc carte construction"));
			return;
		}
		int w = (pw - 24 - 8) / 3;
		int top = py + 58;
		Object[][] grid = {
			{"Remplir…", "vc edit remplir ", true}, {"Remplacer…", "vc edit remplacer ", true}, {"Vider", "vc edit vider", false},
			{"Murs…", "vc edit murs ", true}, {"Contour…", "vc edit contour ", true}, {"Étendre…", "vc edit etendre ", true},
			{"Copier", "vc edit copier", false}, {"Couper", "vc edit couper", false}, {"Coller", "vc edit coller", false},
			{"Coller sans l'air", "vc edit coller sansair", false}, {"Tourner la copie", "vc edit tourner", false},
			{"Retourner la copie", "vc edit miroir", false},
			{"Déplacer…", "vc edit deplacer ", true}, {"Empiler…", "vc edit empiler ", true},
			{"Désélectionner", "vc edit deselectionner", false},
			{"Annuler (" + ClientState.num(ed, "annulables", 0) + ")", "vc edit annuler", false},
			{"Rétablir (" + ClientState.num(ed, "retablissables", 0) + ")", "vc edit retablir", false},
			{"Outil de sélection", "vc carte outils", false},
		};
		for (int i = 0; i < grid.length; i++) {
			int x = px + 12 + (i % 3) * (w + 4);
			int y = top + (i / 3) * ROW;
			String label = (String) grid[i][0];
			String command = (String) grid[i][1];
			if ((Boolean) grid[i][2]) {
				chat(x, y, w, label, command);
			} else {
				action(x, y, w, label, command);
			}
		}
	}

	private void drawSelection(GuiGraphicsExtractor g, JsonObject a) {
		JsonObject ed = ClientState.obj(a, "editeur");
		if (!ClientState.bool(ed, "construction")) {
			Theme.paragraph(g, font, "Les outils de sélection servent en mode construction : l'outil « Carte : sélection » pose deux "
				+ "coins (clic gauche, clic droit), puis ces boutons remplissent, vident, copient, collent, déplacent.",
				px + 40, py + 46, pw - 80, Theme.ASH);
			return;
		}
		var sel = ed.get("selection");
		String text = sel != null && sel.isJsonArray()
			? "Sélection : " + sel.getAsJsonArray().get(0).getAsInt() + " × " + sel.getAsJsonArray().get(1).getAsInt() + " × "
				+ sel.getAsJsonArray().get(2).getAsInt()
			: "Aucune sélection : clic gauche puis clic droit avec l'outil de sélection";
		Theme.ink(g, font, fit(text + (ClientState.bool(ed, "pressePapiers") ? " · une copie en mémoire" : "") + ".", pw - 28),
			px + 14, py + 46, Theme.INK);
	}

	// ------------------------------------------------------------------
	// Créations : scénarios et rôles
	// ------------------------------------------------------------------
	private void buildCreations(JsonObject a) {
		var scenarios = objects(view(), "scenarios");
		var roles = objects(a, "roles");
		int x = px + 12;
		var sc = button(x, row(0), 92, "Scénarios", () -> {
			showRoles = false;
			creationPage = 0;
			armed = null;
			rebuildWidgets();
		});
		var ro = button(x + 94, row(0), 92, "Rôles créés", () -> {
			showRoles = true;
			creationPage = 0;
			armed = null;
			rebuildWidgets();
		});
		(showRoles ? ro : sc).disabled();
		List<JsonObject> list = showRoles ? roles : scenarios;
		creationPage = pager(x, row(6), 186, creationPage, list.size(), PAGE, p -> creationPage = p);
		JsonObject chosen = null;
		String current = showRoles ? role : scenario;
		for (int i = 0; i < PAGE && creationPage * PAGE + i < list.size(); i++) {
			JsonObject o = list.get(creationPage * PAGE + i);
			String id = ClientState.str(o, "id");
			String label = ClientState.str(o, "nom") + (showRoles ? "" : "  (" + ClientState.str(o, "joueurs") + ")");
			var b = button(x, row(i + 1), 186, label, () -> {
				if (showRoles) {
					role = id;
				} else {
					scenario = id;
				}
				armed = null;
				rebuildWidgets();
			});
			if (id.equals(current)) {
				b.disabled();
				chosen = o;
			}
		}
		int bx = px + 212;
		int bw = pw - 212 - 12;
		if (showRoles) {
			if (chosen != null) {
				confirm(bx, row(1), bw, "Supprimer ce rôle", "vc atelier supprimer role " + role);
			}
			return;
		}
		if (chosen != null) {
			String carte = ClientState.str(chosen, "carte");
			// Un scénario lié à une autre carte que celle en place la charge : cela se confirme.
			boolean replaces = carte != null && !carte.isEmpty() && !carte.equals(ClientState.str(view(), "mapInPlace"));
			if (replaces) {
				confirm(bx, row(1), bw, "Choisir (charge sa carte)", "vc scenario " + scenario);
				confirm(bx, row(2), bw, "Partie d'essai (charge sa carte)", "vc atelier essai " + scenario);
			} else {
				action(bx, row(1), bw, "Choisir pour la prochaine partie", "vc scenario " + scenario);
				action(bx, row(2), bw, "Partie d'essai", "vc atelier essai " + scenario);
			}
			confirm(bx, row(3), bw, "Supprimer ce scénario", "vc atelier supprimer scenario " + scenario);
		}
		if (ClientState.obj(view(), "scenario") != null) {
			action(bx, row(4), bw, "Jouer sans scénario", "vc scenario aucun");
		}
	}

	private void drawCreations(GuiGraphicsExtractor g, JsonObject a) {
		int bx = px + 212;
		int bw = pw - 212 - 12;
		JsonObject current = ClientState.obj(view(), "scenario");
		String text = showRoles
			? "Un rôle se crée et se modifie sur la page web de l'Atelier (bouton « Page web »). Ici, on peut le supprimer."
			: (current == null ? "Prochaine partie : sans scénario." : "Prochaine partie : « " + ClientState.str(current, "nom") + " ».")
				+ (ClientState.bool(a, "essai") ? " Une partie d'essai attend la fin du chantier." : "")
				+ " Les scénarios s'écrivent sur la page web.";
		Theme.paragraph(g, font, text, bx, row(5) + 2, bw, Theme.ASH, 30);
		boolean empty = (showRoles ? objects(a, "roles") : objects(view(), "scenarios")).isEmpty();
		if (empty) {
			Theme.paragraph(g, font, showRoles ? "Aucun rôle créé." : "Aucun scénario créé.", px + 14, row(1) + 4, 186, Theme.ASH);
		}
	}

	// ------------------------------------------------------------------
	// Dessin
	// ------------------------------------------------------------------
	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject a = data();
		if (a == null) {
			Theme.paragraph(g, font, "L'Atelier est réservé aux opérateurs du serveur.", px + 14, py + 44, pw - 28, Theme.ASH);
			return;
		}
		boolean busy = ClientState.bool(a, "chantier");
		// En haut : la carte actuelle en bref (son détail est sous l'aperçu de l'onglet Cartes), ou la structure choisie.
		String status = (busy ? "Chantier en cours… " : "") + (tab == 0 ? "" : tab == 3 ? structureLine(a) : ClientState.str(a, "bref"));
		Theme.ink(g, font, fit(status, pw - 28), px + 14, py + 30, busy ? Theme.BLOOD : Theme.WINE);
		switch (tab) {
			case 0 -> drawMaps(g, a);
			case 1 -> drawVillage(g);
			case 2 -> drawMarkers(g, a);
			case 3 -> drawStructures(g, a);
			case 4 -> drawSelection(g, a);
			default -> drawCreations(g, a);
		}
		// La réponse du serveur à la dernière action : le chat, lui, est caché par l'écran.
		JsonObject reply = ClientState.obj(a, "reponse");
		if (reply != null && ClientState.num(reply, "n", 0) > repliesBefore) {
			Theme.ink(g, font, fit(ClientState.str(reply, "texte"), pw - 28), px + 14, py + ph - 38,
				ClientState.bool(reply, "erreur") ? Theme.BLOOD : Theme.GOOD);
		}
	}
}
