package fr.valcombe.client.screen.atelier;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdGrid;
import fr.valcombe.client.hd.HdList;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTabs;
import fr.valcombe.client.hd.HdTokenArt;

import java.util.List;

/**
 * Atelier › Rôles et scénarios : trois volets.
 * <ul>
 * <li><b>Scénarios</b> : les scénarios créés, chacun avec ses rôles en jetons (en éventail), ses nombres de joueurs, sa
 * carte et les parties où il est choisi. Un clic ouvre sa fiche ({@link ScenarioSheetPage}) : le choisir pour une
 * partie, lancer une partie d'essai, le supprimer.</li>
 * <li><b>Rôles créés</b> : les rôles personnalisés, chacun son jeton (emblème et camp). Fiche : {@link RoleSheetPage}.</li>
 * <li><b>Packs</b> : les fichiers .valcombe déposés sur le serveur (dossier d'import, partages) : « Importer » en fait des
 * rôles, des scénarios, des cartes et des structures du serveur.</li>
 * </ul>
 * Rôles et scénarios s'écrivent sur la page web de l'Atelier (« Page web », en haut à droite : le lien arrive dans le chat).
 */
public class RolesPage extends AtelierPage {
	public static final String WEB = "Page web";
	public static final String IMPORT = "Importer";

	private static int tab;
	private static String chosenPack;

	private HdGrid<JsonObject> grid;
	private HdList<JsonObject> packs;

	public RolesPage() {
	}

	/** Ouvre la page sur un volet (0 scénarios, 1 rôles créés, 2 packs). */
	public RolesPage(int openTab) {
		tab = Math.max(0, Math.min(2, openTab));
	}

	static List<Crumb> crumbs() {
		return List.of(new Crumb("Atelier", AtelierHome::new), new Crumb(AtelierHome.ROLES, RolesPage::new));
	}

	@Override
	protected List<Crumb> trail() {
		return List.of(new Crumb("Atelier", AtelierHome::new), new Crumb(AtelierHome.ROLES, null));
	}

	public static List<JsonObject> scenarios() {
		return list(data(), "scenarios");
	}

	public static List<JsonObject> roles() {
		return list(data(), "roles");
	}

	public static List<JsonObject> packList() {
		return list(data(), "packs");
	}

	static JsonObject scenario(String id) {
		return scenarios().stream().filter(s -> str(s, "id").equals(id)).findFirst().orElse(null);
	}

	static JsonObject role(String id) {
		return roles().stream().filter(r -> str(r, "id").equals(id)).findFirst().orElse(null);
	}

	/** Le volet ouvert (pour les tests en jeu). */
	public static int tab() {
		return tab;
	}

	/** La galerie (pour les tests en jeu), ou null sur le volet des packs. */
	public HdGrid<JsonObject> grid() {
		return grid;
	}

	/** Le lien de la page web de l'Atelier, dans le chat (l'écran se ferme pour le montrer). */
	void web() {
		run("vc atelier", "Lien de la page web");
		onClose();
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	@Override
	protected void buildPage() {
		List<JsonObject> sc = scenarios();
		List<JsonObject> ro = roles();
		List<JsonObject> pk = packList();
		int x = left();
		int w = right() - left();
		int nw = Math.min(110, w / 4);
		boolean narrow = w - nw - 8 < 330;
		List<String> labels = List.of("Scénarios (" + sc.size() + ")", (narrow ? "Rôles (" : "Rôles créés (") + ro.size() + ")",
			"Packs (" + pk.size() + ")");
		addRenderableWidget(new HdTabs(x, top() + 2, w - nw - 8, 16, labels, tab).onChange(i -> {
			tab = i;
			rebuildSoon();
		}));
		HdButton web = button(right() - nw, top(), nw, WEB, this::web)
			.tooltip("Le lien de l'Atelier web, dans le chat : rôles et scénarios s'y écrivent en entier (pouvoirs, réveils, règles).");
		grid = null;
		packs = null;
		int gy = top() + 24;
		int gh = bottom() - 4 - gy;
		switch (tab) {
			case 1 -> {
				if (ro.isEmpty()) {
					web.primary();
				}
				boolean tall = gh >= 2 * 112 + 6 && w >= 4 * 100 + 30;
				grid = addRenderableWidget(new HdGrid<JsonObject>(x, gy, w, gh, tall ? 100 : 168, tall ? 112 : 46, 6, r -> str(r, "nom"))
					.items(ro).key("atelier.roles.galerie").openOnClick()
					.empty("Aucun rôle créé. Un rôle (son nom, son camp, son emblème, son pouvoir, ses réveils) s'écrit sur la page web de "
						+ "l'Atelier : « " + WEB + " », en haut à droite.")
					.onActivate(r -> go(RoleSheetPage.visit(str(r, "id")))));
				grid.cell(tall ? RolesPage::tallRole : RolesPage::wideRole);
			}
			case 2 -> buildPacks(pk);
			default -> {
				if (sc.isEmpty()) {
					web.primary();
				}
				boolean tall = gh >= 2 * 126 + 6 && w >= 3 * 150 + 30;
				grid = addRenderableWidget(new HdGrid<JsonObject>(x, gy, w, gh, tall ? 150 : 200, tall ? 126 : 56, 6, s -> str(s, "nom"))
					.items(sc).key("atelier.scenarios.galerie").openOnClick()
					.empty("Aucun scénario. Un scénario (ses rôles, sa carte, ses règles, son texte d'ouverture) s'écrit sur la page web de "
						+ "l'Atelier : « " + WEB + " », en haut à droite.")
					.onActivate(s -> go(ScenarioSheetPage.visit(str(s, "id")))));
				grid.cell(tall ? RolesPage::tallScenario : RolesPage::wideScenario);
			}
		}
	}

	private void buildPacks(List<JsonObject> pk) {
		int x = left();
		int w = right() - left();
		int lw = Math.min(260, w * 3 / 5);
		int y = top() + 24;
		packs = addRenderableWidget(new HdList<JsonObject>(x, y, lw, bottom() - 4 - y, 30, p -> str(p, "fichier"))
			.items(pk).key("atelier.packs").empty("Aucun pack déposé")
			.onSelect(p -> {
				chosenPack = str(p, "fichier");
				rebuildSoon();
			}));
		packs.row((hd, p, i, rx, ry, rw, rh, hovered, sel) -> {
			AtelierKit.rowBack(hd, rx, ry, rw, rh, hovered, sel);
			int pad = hd.u(4);
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(p, "fichier"), rw - 2 * pad), rx + pad, ry + hd.u(2), sel ? Hd.LIGHT : Hd.INK);
			String sub = ("partages".equals(str(p, "dossier")) ? "partages" : "import") + " · " + str(p, "dateTexte") + " · "
				+ MapSheetPage.size(lng(p, "octets"));
			hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, sub, rw - 2 * pad), rx + pad, ry + hd.u(2) + hd.lineHeight(HdStyle.FORT),
				sel ? Hd.GOLD : Hd.INK_SOFT);
		});
		int pick = pk.isEmpty() ? -1 : 0;
		for (int i = 0; i < pk.size(); i++) {
			if (str(pk.get(i), "fichier").equals(chosenPack)) {
				pick = i;
			}
		}
		packs.select(pick);
		JsonObject p = packs.selected();
		if (p != null) {
			int rx = x + lw + 12;
			button(rx, bottom() - 4 - 18, right() - rx, IMPORT, () -> run("vc atelier pack importer " + str(p, "fichier"),
				"Import de « " + str(p, "fichier") + " »")).primary()
				.tooltip("Ses rôles, scénarios, cartes et structures entrent sur ce serveur ; ce qui porte le même identifiant est remplacé.");
		}
	}

	// ------------------------------------------------------------------
	// Les cases
	// ------------------------------------------------------------------

	/** « 5 à 15 joueurs · carte « Port » ». */
	static String scenarioLine(JsonObject s) {
		String n = str(s, "joueurs");
		String carte = str(s, "carteNom").isEmpty() ? str(s, "carte") : str(s, "carteNom");
		return (n.isEmpty() ? "pas assez de rôles" : n + " joueurs") + (carte.isEmpty() ? " · carte de la partie" : " · carte « " + carte + " »");
	}

	private static void chosenChip(Hd hd, JsonObject s, int x, int y, int w) {
		List<String> in = words(s, "choisiDans");
		if (in.isEmpty()) {
			return;
		}
		HdStyle cs = HdStyle.PETIT.size(8);
		String text = "Choisi : " + String.join(", ", in);
		int max = w;
		String shown = hd.ellipsize(cs, text, max - hd.u(7));
		AtelierArt.chip(hd, cs, shown, x, y, AtelierArt.GOOD);
	}

	private static void tallScenario(Hd hd, JsonObject s, int index, int x, int y, int w, int h, boolean hovered, boolean selected) {
		AtelierKit.cardBack(hd, x, y, w, h, hovered);
		int pad = hd.u(4);
		HdStyle cs = HdStyle.PETIT.size(8);
		int text = hd.lineHeight(HdStyle.FORT) + hd.lineHeight(HdStyle.PETIT) + AtelierArt.chipHeight(hd, cs) + hd.u(8);
		int ah = h - 2 * pad - text;
		AtelierKit.velvet(hd, x + pad, y + pad, w - 2 * pad, ah, index);
		AtelierKit.fan(hd, list(s, "roles"), x + pad, y + pad, w - 2 * pad, ah);
		hd.frame(x + pad, y + pad, w - 2 * pad, ah, Math.max(1, hd.u(0.5)), 0xFF2A1E14);
		int ty = y + pad + ah + hd.u(3);
		int tw = w - 2 * pad;
		hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(s, "nom"), tw), x + pad, ty, hovered ? Hd.WINE : Hd.INK);
		ty += hd.lineHeight(HdStyle.FORT);
		hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, scenarioLine(s), tw), x + pad, ty, Hd.INK_SOFT);
		ty += hd.lineHeight(HdStyle.PETIT) + hd.u(2);
		chosenChip(hd, s, x + pad, ty, tw);
	}

	private static void wideScenario(Hd hd, JsonObject s, int index, int x, int y, int w, int h, boolean hovered, boolean selected) {
		AtelierKit.cardBack(hd, x, y, w, h, hovered);
		int pad = hd.u(3);
		int ah = h - 2 * pad;
		int aw = Math.min(w / 3, ah * 8 / 5);
		AtelierKit.velvet(hd, x + pad, y + pad, aw, ah, index);
		AtelierKit.fan(hd, list(s, "roles"), x + pad, y + pad, aw, ah);
		hd.frame(x + pad, y + pad, aw, ah, Math.max(1, hd.u(0.5)), 0xFF2A1E14);
		int tx = x + pad + aw + hd.u(5);
		int tw = x + w - pad - tx;
		int ty = y + pad;
		hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(s, "nom"), tw), tx, ty, hovered ? Hd.WINE : Hd.INK);
		ty += hd.lineHeight(HdStyle.FORT);
		hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, scenarioLine(s), tw), tx, ty, Hd.INK_SOFT);
		HdStyle cs = HdStyle.PETIT.size(8);
		chosenChip(hd, s, tx, y + h - pad - AtelierArt.chipHeight(hd, cs), tw);
	}

	static String campLine(JsonObject r) {
		return str(r, "campTexte");
	}

	private static void tallRole(Hd hd, JsonObject r, int index, int x, int y, int w, int h, boolean hovered, boolean selected) {
		AtelierKit.cardBack(hd, x, y, w, h, hovered);
		int pad = hd.u(4);
		HdStyle cs = HdStyle.PETIT.size(8);
		int text = hd.lineHeight(HdStyle.FORT) + AtelierArt.chipHeight(hd, cs) + hd.u(7);
		int ah = h - 2 * pad - text;
		AtelierKit.velvet(hd, x + pad, y + pad, w - 2 * pad, ah, index + 7);
		int size = Math.min(ah - hd.u(6), w - 2 * pad - hd.u(10));
		HdTokenArt.draw(hd, x + (w - size) / 2, y + pad + (ah - size) / 2, size, str(r, "embleme"), AtelierKit.team(r), true, false, hovered);
		hd.frame(x + pad, y + pad, w - 2 * pad, ah, Math.max(1, hd.u(0.5)), 0xFF2A1E14);
		int ty = y + pad + ah + hd.u(3);
		hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(r, "nom"), w - 2 * pad), x + pad, ty, hovered ? Hd.WINE : Hd.INK);
		ty += hd.lineHeight(HdStyle.FORT) + hd.u(1);
		AtelierArt.chip(hd, cs, campLine(r), x + pad, ty, AtelierKit.campColor(str(r, "camp")));
	}

	private static void wideRole(Hd hd, JsonObject r, int index, int x, int y, int w, int h, boolean hovered, boolean selected) {
		AtelierKit.cardBack(hd, x, y, w, h, hovered);
		int pad = hd.u(3);
		int side = h - 2 * pad;
		AtelierKit.velvet(hd, x + pad, y + pad, side, side, index + 7);
		int size = side - hd.u(4);
		HdTokenArt.draw(hd, x + pad + hd.u(2), y + pad + hd.u(2), size, str(r, "embleme"), AtelierKit.team(r), true, false, hovered);
		int tx = x + pad + side + hd.u(5);
		int tw = x + w - pad - tx;
		int ty = y + pad;
		hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(r, "nom"), tw), tx, ty, hovered ? Hd.WINE : Hd.INK);
		ty += hd.lineHeight(HdStyle.FORT);
		hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, "Première nuit : " + str(r, "premiereNuit"), tw), tx, ty, Hd.INK_SOFT);
		HdStyle cs = HdStyle.PETIT.size(8);
		AtelierArt.chip(hd, cs, campLine(r), tx, y + h - pad - AtelierArt.chipHeight(hd, cs), AtelierKit.campColor(str(r, "camp")));
	}

	// ------------------------------------------------------------------
	// Dessin
	// ------------------------------------------------------------------

	@Override
	protected void drawPage(Hd hd) {
		if (tab != 2) {
			return;
		}
		int lw = Math.min(260, (right() - left()) * 3 / 5);
		int rx = hd.px(left() + lw + 12);
		int rw = hd.px(right()) - rx;
		int y = hd.px(top() + 24);
		int limit = hd.px(bottom() - 4 - 18) - hd.u(6);
		JsonObject p = packs == null ? null : packs.selected();
		String dir = str(data(), "dossierImport");
		if (p == null) {
			hd.text(HdStyle.SECTION, "Les packs .valcombe", rx, y, Hd.WINE);
			y += hd.lineHeight(HdStyle.SECTION) + hd.u(3);
			hd.paragraph(HdStyle.TEXTE, "Un pack réunit des rôles, des scénarios, des cartes et des structures (l'Atelier web en exporte). "
				+ "Déposez un fichier .valcombe dans le dossier d'import du serveur : il paraît ici tout seul. Dossier : " + dir,
				rx, y, rw, Hd.INK_SOFT, limit - y);
			return;
		}
		hd.text(HdStyle.SECTION, hd.ellipsize(HdStyle.SECTION, str(p, "fichier"), rw), rx, y, Hd.WINE);
		y += hd.lineHeight(HdStyle.SECTION) + hd.u(3);
		String where = "partages".equals(str(p, "dossier")) ? "Dans le dossier des partages (là où vont les exports de ce serveur)."
			: "Dans le dossier d'import du serveur.";
		y += hd.paragraph(HdStyle.TEXTE, where + " Déposé le " + str(p, "dateTexte") + ", " + MapSheetPage.size(lng(p, "octets")) + ".",
			rx, y, rw, Hd.INK, limit - y) + hd.u(4);
		AtelierKit.note(hd, "« " + IMPORT + " » en fait des rôles, des scénarios, des cartes et des structures de ce serveur. Ce qui porte "
			+ "le même identifiant est remplacé ; la réponse en bas de la page dit ce qui est entré et ce qui est refusé.", rx, y, rw, limit - y);
	}
}
