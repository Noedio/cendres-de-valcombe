package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdList;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTabs;
import fr.valcombe.client.hd.HdTextField;

import java.util.ArrayList;
import java.util.List;

/**
 * « Ouvrir une carte dans un atelier » (opérateurs) : trois façons de commencer, en onglets.
 * <ul>
 * <li><b>Bibliothèque</b> : une carte rangée ; un monde neuf, la carte posée au centre. Déjà dans un atelier : on y va
 * (un seul atelier par carte).</li>
 * <li><b>Carte vierge</b> : un nom, et un monde plat et vide.</li>
 * <li><b>Village généré</b> : un nom, le nombre de trônes, la taille, le thème, les maisons ; le village de Valcombe est
 * bâti au centre d'un monde neuf.</li>
 * </ul>
 * La liste vient du serveur ({@code ateliersCarte}) ; chaque bouton lance {@code /vc atelier carte ouvrir|nouvelle|village}.
 */
public class MapWorkshopOpenScreen extends HdScreen {
	private static final List<String> TABS = List.of("Bibliothèque", "Carte vierge", "Village généré");
	private static final List<String> SIZES = List.of("petite", "normale", "grande");
	private static final List<String> SIZE_LABELS = List.of("Petite", "Normale", "Grande");
	private static final String BLANK_KEY = "atelier-carte.vierge";
	private static final String VILLAGE_KEY = "atelier-carte.village";
	private static final int LIST_W = 190;

	private static int tab;
	private static String chosen;
	private static int seats = 16;
	private static int size = 1;
	private static int theme;
	private static boolean houses = true;
	private HdList<JsonObject> list;

	public MapWorkshopOpenScreen() {
		super("Ouvrir une carte dans un atelier");
	}

	/** Ouvre l'écran sur cette carte de la bibliothèque (pour les raccourcis et les tests en jeu). */
	public MapWorkshopOpenScreen(String mapId) {
		this();
		tab = 0;
		chosen = mapId;
	}

	@Override
	protected int[] panelSize() {
		return new int[] {410, 216};
	}

	private static JsonObject data() {
		return ClientState.obj(view(), "ateliersCarte");
	}

	/** Les cartes de la bibliothèque reçues du serveur. */
	public static List<JsonObject> library() {
		JsonObject d = data();
		return d == null ? List.of() : objects(d, "cartes");
	}

	/** Les ateliers carte ouverts reçus du serveur. */
	public static List<JsonObject> ateliers() {
		JsonObject d = data();
		return d == null ? List.of() : objects(d, "ateliers");
	}

	private static String str(JsonObject o, String key) {
		String s = o == null ? null : ClientState.str(o, key);
		return s == null ? "" : s;
	}

	private void open(String command) {
		send(command);
		onClose();
	}

	@Override
	protected void build() {
		addRenderableWidget(new HdTabs(px + 10, py + 8, pw - 20, 16, TABS, tab).onChange(i -> {
			tab = i;
			rebuildSoon();
		}));
		JsonObject d = data();
		if (d != null) {
			switch (tab) {
				case 1 -> buildBlank();
				case 2 -> buildVillage(d);
				default -> buildLibrary();
			}
		}
		button(px + pw - 110, py + ph - 26, 100, "Retour", () -> minecraft.gui.setScreen(new WorldsScreen()));
	}

	private void buildLibrary() {
		List<JsonObject> all = library();
		list = addRenderableWidget(new HdList<JsonObject>(px + 10, py + 32, LIST_W, ph - 66, 26, o -> str(o, "nom"))
			.items(all).key("atelier-carte.bibliotheque").empty("Bibliothèque vide")
			.onSelect(o -> {
				chosen = str(o, "id");
				rebuildSoon();
			})
			.onActivate(o -> open("vc atelier carte ouvrir " + str(o, "id"))));
		list.row((hd, o, index, x, y, w, h, hovered, sel) -> {
			if (sel) {
				hd.fill(x, y, x + w, y + h, Hd.SELECTION);
			} else if (hovered) {
				hd.fill(x, y, x + w, y + h, Hd.HOVER);
			}
			int tx = x + hd.u(5);
			boolean open = !str(o, "atelier").isEmpty();
			String badge = open ? "en atelier" : "";
			int bw = open ? hd.width(HdStyle.PETIT, badge) + hd.u(6) : 0;
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(o, "nom"), w - hd.u(8) - bw), tx, y + hd.u(2), sel ? Hd.LIGHT : Hd.INK);
			String sub = ClientState.num(o, "sieges", 0) + " trône(s) · " + ClientState.num(o, "maisons", 0) + " maison(s) · "
				+ (ClientState.bool(o, "genere") ? "village " + str(o, "theme") : "faite à la main");
			hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, sub, w - hd.u(8)), tx, y + hd.u(14), sel ? Hd.GOLD : Hd.INK_SOFT);
			if (open) {
				hd.textRight(HdStyle.PETIT, badge, x + w - hd.u(5), y + hd.u(3), sel ? Hd.GOLD : Hd.WINE);
			}
		});
		int pick = all.isEmpty() ? -1 : 0;
		for (int i = 0; i < all.size(); i++) {
			if (str(all.get(i), "id").equals(chosen)) {
				pick = i;
			}
		}
		list.select(pick);
		JsonObject m = list.selected();
		int rx = px + LIST_W + 20;
		int rw = pw - LIST_W - 30;
		boolean open = m != null && !str(m, "atelier").isEmpty();
		HdButton go = button(rx, py + ph - 50, rw, open ? "Rouvrir son atelier" : "Ouvrir dans un atelier",
			() -> open("vc atelier carte ouvrir " + str(m, "id")))
			.tooltip(open ? "Cette carte est déjà dans son atelier : vous y retrouvez le travail en cours."
				: "Un monde neuf, la carte posée au centre ; vous y êtes conduit à la fin de la pose.");
		if (m == null) {
			go.disabled();
		}
	}

	private void buildBlank() {
		int x = px + 40;
		int w = pw - 80;
		HdTextField field = addRenderableWidget(new HdTextField(x, py + 62, w, 18, "Nom de la carte")
			.maxLength(50).hint("Nom de la carte…").key(BLANK_KEY));
		Runnable create = () -> {
			String nom = field.value().strip();
			if (!nom.isEmpty()) {
				HdTextField.forget(BLANK_KEY);
				open("vc atelier carte nouvelle " + nom);
			}
		};
		field.onEnter(create);
		button(x, py + 90, w, "Créer la carte vierge", create);
	}

	private void buildVillage(JsonObject d) {
		List<String> themes = new ArrayList<>(strings(d, "themes"));
		if (themes.isEmpty()) {
			themes.add("gothique");
		}
		int min = ClientState.num(d, "tronesMin", 5);
		int max = ClientState.num(d, "tronesMax", 20);
		seats = Math.max(min, Math.min(max, seats));
		theme = Math.floorMod(theme, themes.size());
		int x = px + 40;
		int w = pw - 80;
		HdTextField field = addRenderableWidget(new HdTextField(x, py + 34, w, 18, "Nom de la carte")
			.maxLength(50).hint("Nom de la carte (facultatif)…").key(VILLAGE_KEY));
		HdButton less = button(x, py + 58, 26, "−", () -> {
			seats--;
			rebuildSoon();
		});
		HdButton more = button(x + 130, py + 58, 26, "+", () -> {
			seats++;
			rebuildSoon();
		});
		if (seats <= min) {
			less.disabled();
		}
		if (seats >= max) {
			more.disabled();
		}
		addRenderableWidget(new HdTabs(x + 166, py + 59, w - 166, 16, SIZE_LABELS, size).onChange(i -> {
			size = i;
			rebuildSoon();
		}));
		List<String> labels = themes.stream().map(t -> t.equals("monastere") ? "Monastère" : t.substring(0, 1).toUpperCase() + t.substring(1)).toList();
		addRenderableWidget(new HdTabs(x, py + 84, w, 16, labels, theme).onChange(i -> {
			theme = i;
			rebuildSoon();
		}));
		toggle(x, py + 106, w, "Maisons", houses, () -> {
			houses = !houses;
			rebuildSoon();
		});
		String themeName = themes.get(theme);
		button(x, py + 134, w, "Générer dans un atelier", () -> {
			String nom = field.value().strip();
			HdTextField.forget(VILLAGE_KEY);
			open("vc atelier carte village " + seats + " " + SIZES.get(size) + " " + themeName + " " + (houses ? "oui" : "non")
				+ (nom.isEmpty() ? "" : " " + nom));
		}).tooltip("Le village de Valcombe est bâti au centre d'un monde neuf (salle de convocation et salle d'attente comprises) ; "
			+ "vous y êtes conduit à la fin.");
	}

	@Override
	protected void drawHd(Hd hd) {
		if (data() == null) {
			hd.paragraph(HdStyle.TEXTE, "Réservé aux opérateurs.", hd.px(px + 14), hd.px(py + 36), hd.px(pw - 28), Hd.INK_SOFT);
			return;
		}
		switch (tab) {
			case 1 -> {
				int x = hd.px(px + 40);
				int w = hd.px(pw - 80);
				hd.text(HdStyle.SECTION, "Une carte vierge", x, hd.px(py + 34), Hd.WINE);
				hd.paragraph(HdStyle.TEXTE, "Un monde plat et vide, à son nom. Bâtissez avec WorldEdit, posez les trônes, les maisons, le "
					+ "centre et la salle de convocation avec les outils de repères, puis « Enregistrer » : elle rejoindra la bibliothèque.",
					x, hd.px(py + 116), w, Hd.INK_SOFT);
			}
			case 2 -> {
				int x = hd.px(px + 40);
				hd.textMiddle(HdStyle.FORT, seats + " trônes", hd.px(px + 74), hd.px(py + 58), hd.px(18), Hd.INK);
				hd.paragraph(HdStyle.PETIT, "Les trônes, les maisons en face, la salle de convocation et la salle d'attente sont des repères "
					+ "comme les autres : retouchez-les à la main, puis enregistrez.", x, hd.px(py + 158), hd.px(pw - 80), Hd.INK_SOFT);
			}
			default -> {
				int rx = hd.px(px + LIST_W + 20);
				int rw = hd.px(pw - LIST_W - 30);
				JsonObject m = list == null ? null : list.selected();
				if (m == null) {
					hd.paragraph(HdStyle.TEXTE, library().isEmpty() ? "La bibliothèque est vide : partez d'une carte vierge ou d'un "
						+ "village généré." : "Choisissez une carte dans la liste.", rx, hd.px(py + 34), rw, Hd.INK_SOFT);
					return;
				}
				int y = hd.px(py + 32);
				hd.text(HdStyle.SECTION, hd.ellipsize(HdStyle.SECTION, str(m, "nom"), rw), rx, y, Hd.WINE);
				y += hd.lineHeight(HdStyle.SECTION) + hd.u(2);
				hd.text(HdStyle.PETIT, str(m, "id"), rx, y, Hd.INK_SOFT);
				y += hd.lineHeight(HdStyle.PETIT) + hd.u(3);
				hd.separator(rx, y, rw);
				y += hd.u(5);
				String what = ClientState.num(m, "sieges", 0) + " trône(s), " + ClientState.num(m, "maisons", 0) + " maison(s), "
					+ (ClientState.bool(m, "genere") ? "village généré (" + str(m, "theme") + ")." : "faite à la main.");
				y += hd.paragraph(HdStyle.TEXTE, what, rx, y, rw, Hd.INK) + hd.u(4);
				String how = str(m, "atelier").isEmpty()
					? "Elle est posée dans un monde neuf, à elle seule. On l'y retouche, puis elle s'enregistre dans la bibliothèque "
						+ "(toute seule toutes les 5 minutes, et à la fermeture de l'atelier)."
					: "Elle est déjà dans son atelier : un seul atelier par carte. « Rouvrir » vous y mène.";
				hd.paragraph(HdStyle.PETIT, how, rx, y, rw, Hd.INK_SOFT, hd.px(py + ph - 56) - y);
			}
		}
	}
}
