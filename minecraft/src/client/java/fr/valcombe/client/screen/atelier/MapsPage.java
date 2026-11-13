package fr.valcombe.client.screen.atelier;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.Vignettes;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdGrid;
import fr.valcombe.client.hd.HdList;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTabs;
import fr.valcombe.client.hd.HdTextField;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.util.Util;

/**
 * Atelier › Cartes : trois volets.
 * <ul>
 * <li><b>Bibliothèque</b> : les cartes en vignettes (vue de dessus et repères, faite par le serveur), chacune avec son
 * nom, ses trônes et ses maisons, « Jouable » ou ce qui lui manque, sa date, « En atelier » si un atelier carte l'a
 * ouverte. Recherche et tri (nom, plus récentes, trônes). Un clic ouvre sa fiche ({@link MapSheetPage}).</li>
 * <li><b>Ateliers ouverts</b> : les ateliers carte, avec « Y aller », « Enregistrer », « Fermer l'atelier ».</li>
 * <li><b>Corbeille</b> : les cartes supprimées (30 jours), avec « Restaurer ».</li>
 * </ul>
 * « Nouvelle carte » (en haut à droite) ouvre le parcours guidé ({@link NewMapPage}).
 */
public class MapsPage extends AtelierPage {
	public static final String NEW = "Nouvelle carte";
	private static final String SEARCH_KEY = "atelier.cartes.recherche";
	private static final List<String> SORTS = List.of("Nom", "Récentes", "Trônes");

	/** Volet ouvert (0 bibliothèque, 1 ateliers ouverts, 2 corbeille), tri, recherche : gardés d'une visite à l'autre. */
	private static int tab;
	private static int sort;
	private static String search = "";
	/** Atelier ouvert choisi, et celui dont la fermeture attend d'être confirmée. */
	private static String chosenWorkshop;
	private static String confirmClose;
	private static String chosenTrash;
	/** Les cartes connues avant une copie : la nouvelle venue est mise en avant quand elle paraît. */
	private static Set<String> before;
	private static String flash;
	private static long flashUntil;

	private HdGrid<JsonObject> grid;
	private HdList<JsonObject> list;

	public MapsPage() {
	}

	/** Ouvre la page sur un volet (0 bibliothèque, 1 ateliers ouverts, 2 corbeille). */
	public MapsPage(int openTab) {
		tab = Math.max(0, Math.min(2, openTab));
	}

	/** Une copie (ou une nouvelle carte) va paraître : elle sera mise en avant dans la bibliothèque. */
	static void expectNew() {
		before = new HashSet<>();
		for (JsonObject m : list(data(), "cartes")) {
			before.add(str(m, "id"));
		}
		tab = 0;
	}

	static List<Crumb> crumbs() {
		return List.of(new Crumb("Atelier", AtelierHome::new), new Crumb(AtelierHome.CARTES, MapsPage::new));
	}

	@Override
	protected List<Crumb> trail() {
		return List.of(new Crumb("Atelier", AtelierHome::new), new Crumb(AtelierHome.CARTES, null));
	}

	@Override
	protected boolean cancelPending() {
		boolean was = confirmClose != null;
		confirmClose = null;
		return was;
	}

	/** Les cartes de la bibliothèque, telles que le serveur les envoie. */
	public static List<JsonObject> library() {
		return list(data(), "cartes");
	}

	/** Une carte de la bibliothèque par son identifiant, ou null. */
	static JsonObject map(String id) {
		for (JsonObject m : library()) {
			if (str(m, "id").equals(id)) {
				return m;
			}
		}
		return null;
	}

	/** Le volet ouvert (pour les tests en jeu). */
	public static int tab() {
		return tab;
	}

	/** La bibliothèque sans recherche ni tri (nom), sur son premier volet. */
	public static void resetFilters() {
		tab = 0;
		sort = 0;
		search = "";
		HdTextField.forget(SEARCH_KEY);
	}

	/** Reconstruit la page tout de suite (après un changement de filtre fait hors d'elle : tests en jeu). */
	public void rebuildNow() {
		rebuildWidgets();
	}

	/** La galerie de la bibliothèque (pour les tests en jeu), ou null sur un autre volet. */
	public HdGrid<JsonObject> grid() {
		return grid;
	}

	// ------------------------------------------------------------------
	// Recherche et tri
	// ------------------------------------------------------------------

	/** Sans accents ni majuscules : « Château » se trouve en tapant « chateau ». */
	static String fold(String s) {
		return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
	}

	/** Les cartes qui répondent à la recherche, dans l'ordre choisi. */
	static List<JsonObject> shown(List<JsonObject> all, String query, int order) {
		String q = fold(query).strip();
		List<JsonObject> out = new ArrayList<>();
		for (JsonObject m : all) {
			if (q.isEmpty() || fold(str(m, "nom")).contains(q) || fold(str(m, "id")).contains(q)
				|| fold(str(m, "theme")).contains(q) && ClientState.bool(m, "genere")) {
				out.add(m);
			}
		}
		Comparator<JsonObject> byName = Comparator.comparing(m -> fold(str(m, "nom")));
		switch (order) {
			case 1 -> out.sort(Comparator.comparingLong((JsonObject m) -> lng(m, "modifieeLe")).reversed().thenComparing(byName));
			case 2 -> out.sort(Comparator.comparingInt((JsonObject m) -> ClientState.num(m, "sieges", 0)).reversed().thenComparing(byName));
			default -> out.sort(byName);
		}
		return out;
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	@Override
	protected void buildPage() {
		JsonObject a = data();
		List<JsonObject> maps = library();
		List<JsonObject> open = list(workshops(), "ateliers");
		List<JsonObject> trash = list(a, "corbeille");
		int x = left();
		int w = right() - left();
		int nw = Math.min(124, w / 3);
		boolean narrow = w - nw - 8 < 330; // « Ateliers ouverts » ne tiendrait pas en entier
		List<String> labels = List.of("Bibliothèque (" + maps.size() + ")", (narrow ? "Ateliers (" : "Ateliers ouverts (") + open.size() + ")",
			"Corbeille (" + trash.size() + ")");
		addRenderableWidget(new HdTabs(x, top() + 2, w - nw - 8, 16, labels, tab).onChange(i -> {
			tab = i;
			confirmClose = null;
			rebuildSoon();
		}));
		HdButton create = button(right() - nw, top(), nw, NEW, () -> go(NewMapPage.start()))
			.tooltip("Un village généré, une carte vierge, ou un fichier .schem, .litematic ou .nbt déposé sur le serveur.");
		if (maps.isEmpty()) {
			create.primary(); // une bibliothèque vide n'a qu'une suite : la première carte
		}
		grid = null;
		list = null;
		switch (tab) {
			case 1 -> buildWorkshops(open);
			case 2 -> buildTrash(trash);
			default -> buildLibrary(maps);
		}
	}

	private void buildLibrary(List<JsonObject> maps) {
		int x = left();
		int w = right() - left();
		int y = top() + 24;
		int sw = Math.min(200, w - 150);
		HdTextField field = addRenderableWidget(new HdTextField(x, y, sw, 18, "Rechercher une carte")
			.hint("Rechercher une carte…").maxLength(40).value(search).key(SEARCH_KEY));
		field.responder(s -> {
			if (!s.equals(search)) {
				search = s;
				rebuildSoon();
			}
		});
		int tw = Math.min(180, w - sw - 8);
		addRenderableWidget(new HdTabs(right() - tw, y + 1, tw, 16, SORTS, sort).onChange(i -> {
			sort = i;
			rebuildSoon();
		}));
		int gy = y + 24;
		int gh = bottom() - 4 - gy;
		// De grandes cases (vignette au-dessus) quand il en tient deux rangées de trois, sinon des cases en longueur.
		boolean tall = gh >= 2 * 150 + 6 && w >= 3 * 118 + 30;
		List<JsonObject> shown = shown(maps, search, sort);
		String empty = maps.isEmpty()
			? "La bibliothèque est vide. « Nouvelle carte » pour commencer : un village généré, une carte vierge ou un fichier importé."
			: "Aucune carte ne répond à « " + search.strip() + " ».";
		grid = addRenderableWidget(new HdGrid<JsonObject>(x, gy, w, gh, tall ? 118 : 176, tall ? 150 : 52, 6, m -> str(m, "nom"))
			.items(shown).key("atelier.cartes.galerie").empty(empty).openOnClick()
			.onActivate(m -> go(MapSheetPage.visit(str(m, "id")))));
		grid.cell(tall ? MapsPage::tallCard : MapsPage::wideCard);
		// La carte qui vient de paraître (copie, nouvelle carte) est mise en avant et montrée.
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
	}

	// ------------------------------------------------------------------
	// Les cases de la bibliothèque
	// ------------------------------------------------------------------

	/** Ce qui manque en premier pour jouer, ou null si la carte est jouable. */
	static String blocking(JsonObject m) {
		if (ClientState.bool(m, "jouable")) {
			return null;
		}
		int missing = Math.max(0, 5 - ClientState.num(m, "sieges", 0));
		return missing > 0 ? "Manque " + missing + (missing > 1 ? " trônes" : " trône") : "Pas jouable";
	}

	static String counts(JsonObject m) {
		int s = ClientState.num(m, "sieges", 0);
		int h = ClientState.num(m, "maisons", 0);
		return s + (s > 1 ? " trônes" : " trône") + " · " + h + (h > 1 ? " maisons" : " maison");
	}

	/** « 13/11/2026 » : le jour de la dernière modification. */
	static String day(JsonObject m) {
		String t = str(m, "modifieeTexte");
		int at = t.indexOf(" à ");
		return at > 0 ? t.substring(0, at) : t;
	}

	private static void cardBack(Hd hd, JsonObject m, int x, int y, int w, int h, boolean hovered) {
		boolean lit = str(m, "id").equals(flash) && Util.getMillis() < flashUntil;
		hd.fill(x, y, x + w, y + h, hovered ? 0x40F2E6C9 : 0x22301E14);
		if (lit) {
			double t = (flashUntil - Util.getMillis()) / 4000.0;
			int alpha = (int) Math.round(0x90 * Math.max(0, Math.min(1, t)) * (0.6 + 0.4 * Math.sin(Util.getMillis() / 150.0)));
			hd.fill(x, y, x + w, y + h, alpha << 24 | 0xF2C14E);
		}
		hd.frame(x, y, w, h, Math.max(1, hd.u(hovered ? 1 : 0.5)), hovered ? 0xFFD9B45A : 0x806E4F22);
	}

	/** Le pied d'une case : « Jouable » ou ce qui manque, à gauche ; la date, à droite. */
	private static void cardStatus(Hd hd, JsonObject m, int x, int y, int w) {
		HdStyle cs = HdStyle.PETIT.size(8);
		String miss = blocking(m);
		int cw = AtelierArt.chip(hd, cs, miss == null ? "Jouable" : miss, x, y, miss == null ? AtelierArt.GOOD : AtelierArt.BAD);
		String d = day(m);
		if (!d.isEmpty() && hd.width(HdStyle.PETIT, d) < w - cw - hd.u(4)) {
			hd.textRight(HdStyle.PETIT, d, x + w, y + (AtelierArt.chipHeight(hd, cs) - hd.lineHeight(HdStyle.PETIT)) / 2, Hd.INK_SOFT);
		}
	}

	/** Grande case : la vignette au-dessus, le nom, les repères, l'état. */
	private static void tallCard(Hd hd, JsonObject m, int index, int x, int y, int w, int h, boolean hovered, boolean selected) {
		cardBack(hd, m, x, y, w, h, hovered);
		int pad = hd.u(4);
		int text = hd.lineHeight(HdStyle.FORT) + hd.lineHeight(HdStyle.PETIT) + AtelierArt.chipHeight(hd, HdStyle.PETIT.size(8)) + hd.u(8);
		int side = Math.min(w - 2 * pad, h - 2 * pad - text);
		int vx = x + (w - side) / 2;
		int vy = y + pad;
		Vignettes.draw(hd, "carte:" + str(m, "id"), str(m, "vignette"), vx, vy, side, side);
		hd.frame(vx, vy, side, side, Math.max(1, hd.u(0.5)), 0xFF2A1E14);
		if (!str(m, "atelier").isEmpty()) {
			AtelierArt.ribbon(hd, "En atelier", vx + side - hd.u(2), vy + hd.u(2), 0xE06E4F22);
		}
		int ty = vy + side + hd.u(3);
		int tw = w - 2 * pad;
		hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(m, "nom"), tw), x + pad, ty, hovered ? Hd.WINE : Hd.INK);
		ty += hd.lineHeight(HdStyle.FORT);
		hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, counts(m), tw), x + pad, ty, Hd.INK_SOFT);
		ty += hd.lineHeight(HdStyle.PETIT) + hd.u(2);
		cardStatus(hd, m, x + pad, ty, tw);
	}

	/** Case en longueur : la vignette à gauche, le reste à droite. */
	private static void wideCard(Hd hd, JsonObject m, int index, int x, int y, int w, int h, boolean hovered, boolean selected) {
		cardBack(hd, m, x, y, w, h, hovered);
		int pad = hd.u(3);
		int side = h - 2 * pad;
		Vignettes.draw(hd, "carte:" + str(m, "id"), str(m, "vignette"), x + pad, y + pad, side, side);
		hd.frame(x + pad, y + pad, side, side, Math.max(1, hd.u(0.5)), 0xFF2A1E14);
		int tx = x + pad + side + hd.u(5);
		int tw = x + w - pad - tx;
		int ty = y + pad;
		String badge = str(m, "atelier").isEmpty() ? null : "En atelier";
		HdStyle bs = HdStyle.PETIT.size(8);
		int bw = badge == null ? 0 : AtelierArt.chipWidth(hd, bs, badge) + hd.u(3);
		hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(m, "nom"), tw - bw), tx, ty, hovered ? Hd.WINE : Hd.INK);
		if (badge != null) {
			AtelierArt.chip(hd, bs, badge, x + w - pad - bw + hd.u(3), ty + hd.u(1), 0xE06E4F22);
		}
		ty += hd.lineHeight(HdStyle.FORT);
		hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, counts(m), tw), tx, ty, Hd.INK_SOFT);
		ty = y + h - pad - AtelierArt.chipHeight(hd, HdStyle.PETIT.size(8));
		cardStatus(hd, m, tx, ty, tw);
	}

	// ------------------------------------------------------------------
	// Ateliers ouverts
	// ------------------------------------------------------------------

	private static String workshopState(JsonObject o) {
		if (ClientState.bool(o, "pose")) {
			return "Pose en cours";
		}
		if (!ClientState.bool(o, "enregistree")) {
			return "Jamais enregistrée";
		}
		return ClientState.bool(o, "modifiee") ? "Modifiée, pas encore enregistrée" : "À jour dans la bibliothèque";
	}

	private void buildWorkshops(List<JsonObject> open) {
		int x = left();
		int w = right() - left();
		int lw = Math.min(260, w * 3 / 5);
		int y = top() + 24;
		list = addRenderableWidget(new HdList<JsonObject>(x, y, lw, bottom() - 4 - y, 34, o -> str(o, "nom"))
			.items(open).key("atelier.cartes.ateliers")
			.empty("Aucun atelier carte ouvert")
			.onSelect(o -> {
				if (!str(o, "monde").equals(chosenWorkshop)) {
					confirmClose = null;
				}
				chosenWorkshop = str(o, "monde");
				rebuildSoon();
			})
			.onActivate(o -> {
				run("vc atelier carte aller " + str(o, "monde"), "Voyage vers l'atelier");
				onClose();
			}));
		list.row((hd, o, i, rx, ry, rw, rh, hovered, sel) -> {
			if (sel) {
				hd.fill(rx, ry, rx + rw, ry + rh, Hd.SELECTION);
			} else if (hovered) {
				hd.fill(rx, ry, rx + rw, ry + rh, Hd.HOVER);
			}
			int pad = hd.u(3);
			int side = rh - 2 * pad;
			JsonObject m = map(str(o, "carte"));
			if (m != null) {
				Vignettes.draw(hd, "carte:" + str(m, "id"), str(m, "vignette"), rx + pad, ry + pad, side, side);
			} else {
				AtelierArt.mapScroll(hd, rx + pad, ry + pad, side, side);
			}
			int tx = rx + pad + side + hd.u(5);
			int tw = rx + rw - pad - tx;
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(o, "nom"), tw), tx, ry + pad, sel ? Hd.LIGHT : Hd.INK);
			int players = ClientState.num(o, "joueurs", 0);
			String sub = workshopState(o) + (players > 0 ? " · " + players + (players > 1 ? " joueurs" : " joueur") : "")
				+ (ClientState.bool(o, "ici") ? " · vous y êtes" : "");
			hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, sub, tw), tx, ry + pad + hd.lineHeight(HdStyle.FORT), sel ? Hd.GOLD : Hd.INK_SOFT);
		});
		int pick = open.isEmpty() ? -1 : 0;
		for (int i = 0; i < open.size(); i++) {
			if (str(open.get(i), "monde").equals(chosenWorkshop)) {
				pick = i;
			}
		}
		list.select(pick);
		JsonObject o = list.selected();
		int rx = x + lw + 12;
		int rw = right() - rx;
		if (o == null) {
			return;
		}
		String monde = str(o, "monde");
		int by = bottom() - 4 - 18;
		if (monde.equals(confirmClose)) {
			int half = (rw - 6) / 2;
			button(rx, by, half, "Confirmer la fermeture", () -> {
				confirmClose = null;
				run("vc atelier carte fermer " + monde + " confirmer", "Fermeture de l'atelier");
			}).tooltip("La carte est d'abord enregistrée si elle a changé, puis le monde de l'atelier est supprimé ; ceux qui s'y "
				+ "trouvent vont au hub.");
			button(rx + half + 6, by, rw - half - 6, "Annuler", () -> {
				confirmClose = null;
				rebuildSoon();
			});
		} else {
			HdButton close = button(rx, by, rw, "Fermer l'atelier", () -> {
				confirmClose = monde;
				rebuildSoon();
			}).tooltip("Enregistrer la carte, puis supprimer le monde de l'atelier (une confirmation suit).");
			if (ClientState.bool(o, "pose")) {
				close.disabled().tooltip("Une pose est en cours dans cet atelier : attendez la fin de la barre.");
			}
		}
		HdButton save = button(rx, by - 22, rw, "Enregistrer", () -> run("vc atelier carte enregistrer " + monde, "Enregistrement"))
			.tooltip("La carte de cet atelier va dans la bibliothèque, avec une version gardée.");
		if (ClientState.bool(o, "pose")) {
			save.disabled().tooltip("Une pose est en cours dans cet atelier : attendez la fin de la barre.");
		}
		HdButton goThere = button(rx, by - 44, rw, ClientState.bool(o, "ici") ? "Vous y êtes" : "Y aller", () -> {
			run("vc atelier carte aller " + monde, "Voyage vers l'atelier");
			onClose();
		}).tooltip("Aller dans cet atelier (à votre dernière position, la première fois sur la place).");
		if (ClientState.bool(o, "ici")) {
			goThere.disabled();
		} else {
			goThere.primary();
		}
	}

	// ------------------------------------------------------------------
	// Corbeille
	// ------------------------------------------------------------------

	private void buildTrash(List<JsonObject> trash) {
		int x = left();
		int w = right() - left();
		int lw = Math.min(260, w * 3 / 5);
		int y = top() + 24;
		list = addRenderableWidget(new HdList<JsonObject>(x, y, lw, bottom() - 4 - y, 30, o -> str(o, "nom"))
			.items(trash).key("atelier.cartes.corbeille").empty("La corbeille est vide")
			.onSelect(o -> {
				chosenTrash = str(o, "id");
				rebuildSoon();
			}));
		list.row((hd, o, i, rx, ry, rw, rh, hovered, sel) -> {
			if (sel) {
				hd.fill(rx, ry, rx + rw, ry + rh, Hd.SELECTION);
			} else if (hovered) {
				hd.fill(rx, ry, rx + rw, ry + rh, Hd.HOVER);
			}
			int pad = hd.u(4);
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(o, "nom"), rw - 2 * pad), rx + pad, ry + hd.u(2), sel ? Hd.LIGHT : Hd.INK);
			int s = ClientState.num(o, "trones", 0);
			String sub = "Supprimée le " + str(o, "date") + " · " + s + (s > 1 ? " trônes" : " trône");
			hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, sub, rw - 2 * pad), rx + pad, ry + hd.u(2) + hd.lineHeight(HdStyle.FORT),
				sel ? Hd.GOLD : Hd.INK_SOFT);
		});
		int pick = trash.isEmpty() ? -1 : 0;
		for (int i = 0; i < trash.size(); i++) {
			if (str(trash.get(i), "id").equals(chosenTrash)) {
				pick = i;
			}
		}
		list.select(pick);
		JsonObject o = list.selected();
		if (o != null) {
			int rx = x + lw + 12;
			button(rx, bottom() - 4 - 18, right() - rx, "Restaurer", () -> {
				expectNew();
				run("vc carte restaurer " + str(o, "id"), "Restauration de la carte");
			}).primary().tooltip("Remettre cette carte dans la bibliothèque, avec ses versions.");
		}
	}

	// ------------------------------------------------------------------
	// Dessin
	// ------------------------------------------------------------------

	@Override
	protected void drawPage(Hd hd) {
		if (tab == 0) {
			return;
		}
		int lw = Math.min(260, (right() - left()) * 3 / 5);
		int rx = hd.px(left() + lw + 12);
		int rw = hd.px(right()) - rx;
		int y = hd.px(top() + 24);
		int bottom = hd.px(bottom() - 4 - 18 - (tab == 1 ? 44 : 0)) - hd.u(6);
		if (tab == 1) {
			JsonObject o = list == null ? null : list.selected();
			if (o == null) {
				hd.paragraph(HdStyle.TEXTE, "Un atelier carte est un monde à part où l'on retouche une carte : ouvrez-en un depuis la fiche "
					+ "d'une carte, ou avec « Nouvelle carte ».", rx, y, rw, Hd.INK_SOFT, bottom - y);
				return;
			}
			hd.text(HdStyle.SECTION, hd.ellipsize(HdStyle.SECTION, str(o, "nom"), rw), rx, y, Hd.WINE);
			y += hd.lineHeight(HdStyle.SECTION) + hd.u(2);
			JsonObject m = map(str(o, "carte"));
			String text = workshopState(o) + ". " + (m != null ? counts(m).replace(" · ", ", ") + " dans la bibliothèque. " : "")
				+ "Il s'enregistre tout seul toutes les 5 minutes quand il change, et à sa fermeture.";
			if (str(o, "monde").equals(confirmClose)) {
				text = "Fermer l'atelier « " + str(o, "nom") + " » ? La carte est d'abord enregistrée, puis son monde est supprimé ; "
					+ "ceux qui s'y trouvent vont au hub.";
			}
			hd.paragraph(HdStyle.TEXTE, text, rx, y, rw, str(o, "monde").equals(confirmClose) ? 0xFF7A1E24 : Hd.INK, bottom - y);
		} else {
			JsonObject o = list == null ? null : list.selected();
			String text = "Une carte supprimée reste ici 30 jours, avec ses versions gardées, puis elle est effacée pour de bon.";
			if (o != null) {
				text = "« " + str(o, "nom") + " » (identifiant « " + str(o, "carte") + " ») revient dans la bibliothèque sous son identifiant, "
					+ "ou sous un autre s'il a été repris entre-temps. " + text;
			}
			hd.paragraph(HdStyle.TEXTE, text, rx, y, rw, Hd.INK_SOFT, hd.px(bottom() - 4 - 18) - hd.u(6) - y);
		}
	}
}
