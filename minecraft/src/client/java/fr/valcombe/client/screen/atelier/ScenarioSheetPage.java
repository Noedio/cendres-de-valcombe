package fr.valcombe.client.screen.atelier;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.Vignettes;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdList;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTokenArt;

import java.util.ArrayList;
import java.util.List;

/**
 * Atelier › Rôles et scénarios › (un scénario) : sa fiche. À gauche, tous ses rôles en jetons sur le velours ; à droite,
 * sa description, sa carte, ses nombres de joueurs, les parties où il est choisi, et ses actions :
 * <ul>
 * <li><b>Choisir pour une partie…</b> : la partie choisie en vignette ; s'il demande une autre carte que celle en place,
 * cela est dit avant de confirmer (son monde sera remis à neuf). Une partie où il est déjà choisi : « Ne plus le
 * choisir ».</li>
 * <li><b>Partie d'essai…</b> : avec des bots, dans la partie que vous visez, au nombre de joueurs choisi.</li>
 * <li><b>Page web</b> (pour le modifier), <b>Supprimer</b> (confirmé).</li>
 * </ul>
 */
public class ScenarioSheetPage extends AtelierPage {
	public static final String CHOOSE = "Choisir pour une partie…";
	public static final String CHOOSE_CONFIRM = "Choisir";
	public static final String UNCHOOSE = "Ne plus le choisir";
	public static final String TRIAL = "Partie d'essai…";
	public static final String TRIAL_CONFIRM = "Lancer l'essai";
	public static final String DELETE = "Supprimer";
	public static final String DELETE_CONFIRM = "Oui, supprimer";
	public static final String CANCEL = "Annuler";

	private enum Mode {
		ACTIONS, CHOOSE, TRIAL, DELETE
	}

	private static String lastId;
	private static Mode mode = Mode.ACTIONS;
	private static String target;
	private static int players = 8;
	private static String leaving;

	private final String id;
	private HdList<JsonObject> games;
	private boolean leave;

	public ScenarioSheetPage(String id) {
		this.id = id;
		if (!id.equals(lastId)) {
			lastId = id;
			mode = Mode.ACTIONS;
			target = null;
			players = 8;
		}
	}

	/** La fiche ouverte depuis la liste : elle commence sur ses actions (pas sur une confirmation laissée en route). */
	public static ScenarioSheetPage visit(String id) {
		lastId = null;
		return new ScenarioSheetPage(id);
	}

	public String scenarioId() {
		return id;
	}

	private JsonObject scenario() {
		return RolesPage.scenario(id);
	}

	@Override
	protected List<Crumb> trail() {
		JsonObject s = scenario();
		List<Crumb> t = new ArrayList<>(RolesPage.crumbs());
		t.add(new Crumb(s == null ? id : str(s, "nom"), null));
		return t;
	}

	@Override
	protected boolean cancelPending() {
		if (mode == Mode.ACTIONS) {
			return false;
		}
		mode = Mode.ACTIONS;
		return true;
	}

	@Override
	public void tick() {
		super.tick();
		if (leave) {
			leave = false;
			leaving = null;
			go(new RolesPage(0));
		}
	}

	// ------------------------------------------------------------------
	// Mise en page
	// ------------------------------------------------------------------

	private int artW() {
		return Math.max(80, (right() - left()) * 42 / 100);
	}

	private int colX() {
		return left() + artW() + 14;
	}

	private int colW() {
		return right() - colX();
	}

	private int row(int n) {
		return bottom() - 4 - 18 - n * 22;
	}

	/** Le plus petit et le plus grand nombre de joueurs (« 5 à 15 »), ou 5 et 20 sans renseignement. */
	static int[] range(JsonObject s) {
		String n = str(s, "joueurs");
		String[] p = n.split(" à ");
		try {
			return p.length == 2 ? new int[] {Integer.parseInt(p[0].strip()), Integer.parseInt(p[1].strip())} : new int[] {5, 20};
		} catch (NumberFormatException e) {
			return new int[] {5, 20};
		}
	}

	/** Choisir ce scénario pour cette partie change-t-il sa carte ? (Elle en demande une autre, ou la sienne a été retouchée.) */
	static boolean changesMap(JsonObject s, JsonObject game) {
		String carte = str(s, "carte");
		if (carte.isEmpty() || game == null) {
			return false;
		}
		JsonObject cur = ClientState.obj(game, "actuelle");
		return cur == null || !carte.equals(str(cur, "id")) || ClientState.bool(cur, "modifiee");
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	@Override
	protected void buildPage() {
		JsonObject s = scenario();
		if (s == null) {
			if (id.equals(leaving)) {
				leave = true;
			} else {
				button(left(), top() + 40, 170, "Retour aux scénarios", () -> go(new RolesPage(0))).primary();
			}
			return;
		}
		int x = colX();
		int w = colW();
		int half = (w - 6) / 2;
		switch (mode) {
			case CHOOSE -> buildChoose(s, x, w, half);
			case TRIAL -> buildTrial(s, x, w, half);
			case DELETE -> {
				button(x, row(0), half, DELETE_CONFIRM, () -> {
					mode = Mode.ACTIONS;
					leaving = id;
					run("vc atelier supprimer scenario " + id, "Suppression du scénario");
				}).tooltip("Il disparaît du serveur ; une partie où il était choisi se joue sans scénario.");
				button(x + half + 6, row(0), w - half - 6, CANCEL, () -> to(Mode.ACTIONS));
			}
			default -> {
				HdButton choose = button(x, row(2), w, CHOOSE, () -> to(Mode.CHOOSE));
				choose.primary().tooltip("Le scénario de la prochaine partie, dans la partie de votre choix.");
				if (str(s, "joueurs").isEmpty()) {
					choose.disabled().tooltip("Il n'a pas assez de rôles pour une partie : complétez-le sur la page web.");
				}
				HdButton trial = button(x, row(1), half, TRIAL, () -> to(Mode.TRIAL));
				if (str(s, "joueurs").isEmpty()) {
					trial.disabled().tooltip("Il n'a pas assez de rôles pour une partie.");
				} else {
					trial.tooltip("Une partie avec des bots pour voir le scénario tourner, dans « " + str(data(), "partie") + " ».");
				}
				button(x + half + 6, row(1), w - half - 6, RolesPage.WEB, () -> {
					run("vc atelier", "Lien de la page web");
					onClose();
				}).tooltip("Le modifier : le lien de l'Atelier web arrive dans le chat.");
				button(x, row(0), w, DELETE, () -> to(Mode.DELETE)).tooltip("Le supprimer du serveur, après confirmation.");
			}
		}
	}

	private void to(Mode m) {
		mode = m;
		rebuildSoon();
	}

	private void buildChoose(JsonObject s, int x, int w, int half) {
		List<JsonObject> all = PartiesPage.games();
		int ly = top() + 2 + 16;
		int lh = row(0) - 6 - 30 - ly;
		games = addRenderableWidget(new HdList<JsonObject>(x, ly, w, Math.max(30, lh), 30, g -> str(g, "nom"))
			.items(all).key("atelier.scenario.parties").empty("Aucune partie")
			.onSelect(g -> {
				target = str(g, "id");
				rebuildSoon();
			}));
		games.row((hd, g, i, rx, ry, rw, rh, hovered, sel) -> {
			AtelierKit.rowBack(hd, rx, ry, rw, rh, hovered, sel);
			int pad = hd.u(3);
			int side = rh - 2 * pad;
			PartiesPage.picture(hd, g, rx + pad, ry + pad, side, side);
			int tx = rx + pad + side + hd.u(5);
			int tw = rx + rw - pad - tx;
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(g, "nom"), tw), tx, ry + pad, sel ? Hd.LIGHT : Hd.INK);
			String sc = str(g, "scenario");
			hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, (sc.isEmpty() ? "sans scénario" : "scénario « " + sc + " »") + " · "
				+ PartiesPage.statusLine(g), tw), tx, ry + pad + hd.lineHeight(HdStyle.FORT), sel ? Hd.GOLD : Hd.INK_SOFT);
		});
		int pick = all.isEmpty() ? -1 : 0;
		for (int i = 0; i < all.size(); i++) {
			if (str(all.get(i), "id").equals(target)) {
				pick = i;
			}
		}
		games.select(pick);
		JsonObject g = games.selected();
		boolean already = g != null && id.equals(str(g, "scenarioId"));
		HdButton ok = button(x, row(0), half, already ? UNCHOOSE : CHOOSE_CONFIRM, () -> {
			JsonObject chosen = games.selected();
			if (chosen == null) {
				return;
			}
			mode = Mode.ACTIONS;
			boolean was = id.equals(str(chosen, "scenarioId"));
			run("vc scenario " + (was ? "aucun" : id) + " " + str(chosen, "id"), was ? "La partie se jouera sans scénario"
				: "Choix du scénario pour « " + str(chosen, "nom") + " »");
			rebuildSoon();
		});
		if (g == null) {
			ok.disabled().tooltip("Il manque la partie.");
		} else if (already) {
			ok.tooltip("« " + str(g, "nom") + " » se jouera sans scénario.");
		} else if (changesMap(s, g) && (ClientState.bool(g, "chantier") || !str(g, "envoi").isEmpty())) {
			ok.disabled().tooltip("Une carte est en train d'être posée dans ce monde : attendez la fin de la barre.");
		} else {
			ok.primary().tooltip("Le scénario de la prochaine partie de « " + str(g, "nom") + " ».");
		}
		button(x + half + 6, row(0), w - half - 6, CANCEL, () -> to(Mode.ACTIONS));
	}

	private void buildTrial(JsonObject s, int x, int w, int half) {
		int[] r = range(s);
		players = Math.max(r[0], Math.min(r[1], players));
		int y = row(1);
		HdButton less = button(x, y, 22, "−", () -> {
			players--;
			rebuildSoon();
		});
		HdButton more = button(x + 22 + 72, y, 22, "+", () -> {
			players++;
			rebuildSoon();
		});
		if (players <= r[0]) {
			less.disabled().tooltip(r[0] + " joueurs au moins pour ce scénario.");
		}
		if (players >= r[1]) {
			more.disabled().tooltip(r[1] + " joueurs au plus pour ce scénario.");
		}
		button(x, row(0), half, TRIAL_CONFIRM, () -> {
			mode = Mode.ACTIONS;
			run("vc atelier essai " + id + " " + players, "Partie d'essai");
			rebuildSoon();
		}).primary().tooltip("Vous et des bots, dans « " + str(data(), "partie") + " » : la partie démarre seule (après la pose de sa carte s'il "
			+ "en faut une).");
		button(x + half + 6, row(0), w - half - 6, CANCEL, () -> to(Mode.ACTIONS));
	}

	// ------------------------------------------------------------------
	// Dessin
	// ------------------------------------------------------------------

	@Override
	protected void drawPage(Hd hd) {
		JsonObject s = scenario();
		if (s == null) {
			hd.paragraph(HdStyle.TEXTE, id.equals(leaving) ? "Suppression…" : "Ce scénario n'est plus sur le serveur.",
				hd.px(left()), hd.px(top() + 6), hd.px(right() - left()), Hd.INK_SOFT);
			return;
		}
		drawTokens(hd, s);
		int x = hd.px(colX());
		int w = hd.px(colW());
		int y = hd.px(top() + 2);
		int buttonsTop = switch (mode) {
			case ACTIONS -> row(2);
			case TRIAL -> row(1);
			case CHOOSE -> row(0) - 30;
			default -> row(0);
		};
		int limit = hd.px(buttonsTop) - hd.u(5);
		if (mode == Mode.CHOOSE) {
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, "Pour quelle partie ?", w), x, y + hd.u(1), Hd.INK);
			JsonObject g = games == null ? null : games.selected();
			int ny = hd.px(row(0) - 6 - 30) + hd.u(3);
			String text;
			int color = Hd.INK_SOFT;
			if (g == null) {
				text = "Choisissez la partie.";
			} else if (id.equals(str(g, "scenarioId"))) {
				text = "Il y est déjà choisi : « " + UNCHOOSE + " » la fera jouer sans scénario.";
			} else if (changesMap(s, g)) {
				String carte = str(s, "carteNom").isEmpty() ? str(s, "carte") : str(s, "carteNom");
				text = "Il se joue sur « " + carte + " » : le monde de « " + str(g, "nom") + " » sera remis à neuf et cette carte y sera posée"
					+ (ClientState.bool(ClientState.obj(g, "actuelle"), "modifiee") ? " (la carte retouchée est d'abord sauvegardée)." : ".");
				color = AtelierKit.WARN;
			} else {
				text = "Sa carte est déjà en place : rien n'est reposé.";
			}
			hd.paragraph(HdStyle.PETIT, text, x, ny, w, color, hd.px(row(0)) - ny - hd.u(2));
			return;
		}
		HdStyle title = HdStyle.SECTION.size(13);
		hd.text(title, hd.ellipsize(title, str(s, "nom"), w), x, y, Hd.WINE);
		y += hd.lineHeight(title) + hd.u(1);
		HdStyle cs = HdStyle.PETIT.size(8.5f);
		String n = str(s, "joueurs");
		int cx = x + AtelierArt.chip(hd, cs, n.isEmpty() ? "Rôles insuffisants" : n + " joueurs", x, y, n.isEmpty() ? AtelierArt.BAD
			: AtelierArt.NEUTRAL) + hd.u(3);
		List<String> in = words(s, "choisiDans");
		if (!in.isEmpty()) {
			String chip = hd.ellipsize(cs, "Choisi : " + String.join(", ", in), x + w - cx - hd.u(7));
			AtelierArt.chip(hd, cs, chip, cx, y, AtelierArt.GOOD);
		}
		y += AtelierArt.chipHeight(hd, cs) + hd.u(4);
		if (mode == Mode.DELETE) {
			hd.paragraph(HdStyle.TEXTE, "Supprimer le scénario « " + str(s, "nom") + " » ? Il disparaît du serveur" + (in.isEmpty() ? "."
				: " ; « " + String.join(" », « ", in) + " » se jouera sans scénario."), x, y, w, AtelierKit.WARN, limit - y);
			return;
		}
		if (mode == Mode.TRIAL) {
			int[] r = range(s);
			hd.textMiddle(HdStyle.FORT, players + " joueurs", x + hd.u(22 + 6), hd.px(row(1)), hd.px(18), Hd.INK);
			int ty = y;
			ty += hd.paragraph(HdStyle.TEXTE, "Une partie d'essai de « " + str(s, "nom") + " », de " + r[0] + " à " + r[1] + " joueurs : vous, et des "
				+ "bots pour les autres places.", x, ty, w, Hd.INK, limit - ty) + hd.u(3);
			String where = "Elle se joue dans « " + str(data(), "partie") + " »"
				+ (changesMap(s, myGame()) ? ", après la pose de sa carte (son monde est remis à neuf)." : ".");
			AtelierKit.note(hd, where, x, ty, w, limit - ty);
			return;
		}
		String desc = str(s, "description");
		if (!desc.isEmpty()) {
			y += hd.paragraph(HdStyle.TEXTE, desc, x, y, w, Hd.INK, Math.min(limit - y, hd.lineHeight(HdStyle.TEXTE) * 3)) + hd.u(3);
		}
		// La carte : une petite vignette et son nom.
		String carte = str(s, "carte");
		int vs = hd.u(26);
		if (y + vs <= limit) {
			if (carte.isEmpty()) {
				NewMapPage.blankArt(hd, x, y, vs, vs);
			} else {
				JsonObject m = MapsPage.map(carte);
				Vignettes.draw(hd, "carte:" + carte, m == null ? "" : str(m, "vignette"), x, y, vs, vs);
			}
			hd.frame(x, y, vs, vs, Math.max(1, hd.u(0.5)), 0xFF2A1E14);
			int tx = x + vs + hd.u(5);
			String line1 = carte.isEmpty() ? "La carte de la partie" : "« " + (str(s, "carteNom").isEmpty() ? carte : str(s, "carteNom")) + " »";
			String line2 = carte.isEmpty() ? "il se joue sur la carte en place" : ClientState.bool(s, "carteManque")
				? "introuvable dans la bibliothèque" : "envoyée à la partie qui le choisit";
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, line1, x + w - tx), tx, y, Hd.INK);
			hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, line2, x + w - tx), tx, y + hd.lineHeight(HdStyle.FORT),
				ClientState.bool(s, "carteManque") ? AtelierKit.WARN : Hd.INK_SOFT);
			y += vs + hd.u(4);
		}
		List<String> missing = words(s, "manquants");
		if (!missing.isEmpty()) {
			AtelierKit.warning(hd, "Rôles introuvables (supprimés ?) : " + String.join(", ", missing) + ".", x, y, w, limit - y);
		}
	}

	/** La partie que vise ce joueur (celle de la partie d'essai), ou null. */
	private static JsonObject myGame() {
		for (JsonObject g : PartiesPage.games()) {
			if (ClientState.bool(g, "visee")) {
				return g;
			}
		}
		return null;
	}

	/** Tous les rôles du scénario en jetons sur le velours, chacun son nom dessous quand la place le permet. */
	private void drawTokens(Hd hd, JsonObject s) {
		int x = hd.px(left());
		int y = hd.px(top() + 2);
		int w = hd.px(artW());
		int h = hd.px(bottom() - 4) - y;
		AtelierKit.velvet(hd, x, y, w, h, id.hashCode());
		hd.frame(x - hd.u(2), y - hd.u(2), w + hd.u(4), h + hd.u(4), Math.max(1, hd.u(0.5)), 0xFFD9B45A);
		List<JsonObject> roles = list(s, "roles");
		int n = roles.size();
		if (n == 0) {
			hd.textCentered(HdStyle.TEXTE, "Aucun rôle", x + w / 2, y + h / 2 - hd.lineHeight(HdStyle.TEXTE) / 2, Hd.MUTED);
			return;
		}
		int pad = hd.u(4);
		int aw = w - 2 * pad;
		int ah = h - 2 * pad;
		// Le plus grand jeton qui laisse tenir tous les rôles.
		int best = 0;
		int bestCols = 1;
		for (int cols = 1; cols <= n; cols++) {
			int rows = (n + cols - 1) / cols;
			int cell = Math.min(aw / cols, ah / rows);
			if (cell > best) {
				best = cell;
				bestCols = cols;
			}
		}
		int cols = bestCols;
		int rows = (n + cols - 1) / cols;
		int cw = aw / cols;
		int ch = ah / rows;
		HdStyle ns = HdStyle.PETIT.size(7.5f);
		boolean names = Math.min(cw, ch) >= hd.u(30);
		int size = Math.min(cw, ch - (names ? hd.lineHeight(ns) : 0)) * 8 / 10;
		int oy = y + pad + (ah - rows * ch) / 2;
		for (int i = 0; i < n; i++) {
			JsonObject r = roles.get(i);
			int c = i % cols;
			int rr = i / cols;
			int cx = x + pad + c * cw;
			int cy = oy + rr * ch;
			int tx = cx + (cw - size) / 2;
			int ty = cy + (ch - size - (names ? hd.lineHeight(ns) : 0)) / 2;
			HdTokenArt.draw(hd, tx, ty, size, str(r, "embleme"), AtelierKit.team(r), true, false, hd.hovered(tx, ty, size, size));
			if (names) {
				String name = hd.ellipsize(ns, str(r, "nom"), cw - hd.u(2));
				hd.textCentered(ns, name, cx + cw / 2, ty + size + hd.u(1), ClientState.bool(r, "inconnu") ? 0xFFE08080 : Hd.LIGHT);
			}
		}
	}
}
