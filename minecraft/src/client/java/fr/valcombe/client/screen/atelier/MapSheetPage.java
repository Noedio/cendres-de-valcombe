package fr.valcombe.client.screen.atelier;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.Vignettes;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdGlyphs;
import fr.valcombe.client.hd.HdList;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTextField;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Atelier › Cartes › (une carte) : sa fiche. À gauche, sa grande vignette et la légende des repères ; à droite, ses
 * détails (taille, repères, date, versions, fichier), ce qui lui manque pour se jouer, et ses actions :
 * <ul>
 * <li><b>Ouvrir dans un atelier</b> (ou « Aller à son atelier » s'il est ouvert) : l'atelier carte, un monde à elle ;</li>
 * <li><b>Envoyer à la partie…</b> : on choisit la partie (sa carte actuelle en vignette), puis on confirme : son monde
 * est remis à neuf et la carte y est posée ;</li>
 * <li><b>Dupliquer</b> (la copie est mise en avant dans la bibliothèque), <b>Renommer…</b> (sur place),
 * <b>Exporter</b> (un fichier .valcombe sur le serveur), <b>Supprimer</b> (à la corbeille, après confirmation).</li>
 * </ul>
 */
public class MapSheetPage extends AtelierPage {
	public static final String OPEN = "Ouvrir dans un atelier";
	public static final String GO = "Aller à son atelier";
	public static final String SEND = "Envoyer à la partie…";
	public static final String SEND_CONFIRM = "Confirmer l'envoi";
	public static final String DUPLICATE = "Dupliquer";
	public static final String RENAME = "Renommer…";
	public static final String RENAME_CONFIRM = "Renommer";
	public static final String EXPORT = "Exporter";
	public static final String DELETE = "Supprimer";
	public static final String DELETE_CONFIRM = "Mettre à la corbeille";
	public static final String CANCEL = "Annuler";
	private static final String NAME_KEY = "atelier.carte.nom";

	/** Ce que montre la colonne des actions : les actions, ou une action en cours de confirmation. */
	private enum Mode {
		ACTIONS, RENAME, SEND, DELETE
	}

	private static String lastId;
	private static Mode mode = Mode.ACTIONS;
	/** La partie choisie pour l'envoi. */
	private static String target;
	/** La carte vient d'être mise à la corbeille : la page retourne à la bibliothèque dès qu'elle en disparaît. */
	private static String leaving;

	private final String id;
	private HdList<JsonObject> parties;
	private boolean leave;

	public MapSheetPage(String id) {
		this.id = id;
		if (!id.equals(lastId)) {
			lastId = id;
			mode = Mode.ACTIONS;
			target = null;
		}
	}

	/** La fiche ouverte depuis la bibliothèque : elle commence sur ses actions (pas sur une confirmation laissée en route). */
	public static MapSheetPage visit(String id) {
		lastId = null;
		return new MapSheetPage(id);
	}

	public String mapId() {
		return id;
	}

	private JsonObject map() {
		return MapsPage.map(id);
	}

	@Override
	protected List<Crumb> trail() {
		JsonObject m = map();
		List<Crumb> t = new ArrayList<>(MapsPage.crumbs());
		t.add(new Crumb(m == null ? id : str(m, "nom"), null));
		return t;
	}

	@Override
	protected boolean cancelPending() {
		if (mode == Mode.ACTIONS) {
			return false;
		}
		HdTextField.forget(NAME_KEY);
		mode = Mode.ACTIONS;
		return true;
	}

	@Override
	public void tick() {
		super.tick();
		if (leave) {
			leave = false;
			leaving = null;
			go(new MapsPage(0));
		}
	}

	// ------------------------------------------------------------------
	// Mise en page : vignette à gauche, fiche et actions à droite
	// ------------------------------------------------------------------

	/** Côté de la grande vignette (unités). */
	private int side() {
		int h = bottom() - top() - 4 - 14; // la légende des repères sous la vignette
		return Math.max(60, Math.min(h, (right() - left()) * 45 / 100));
	}

	private int colX() {
		return left() + side() + 14;
	}

	private int colW() {
		return right() - colX();
	}

	/** Haut de la zone des actions (unités) : trois rangées de boutons en bas de la colonne. */
	private int actionsTop() {
		return bottom() - 4 - 3 * 18 - 2 * 4;
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	@Override
	protected void buildPage() {
		JsonObject m = map();
		if (m == null) {
			if (id.equals(leaving)) {
				leave = true;
			} else {
				button(left(), top() + 40, 160, "Retour à la bibliothèque", () -> go(new MapsPage(0))).primary();
			}
			return;
		}
		int x = colX();
		int w = colW();
		int half = (w - 6) / 2;
		int top = actionsTop();
		switch (mode) {
			case RENAME -> buildRename(m, x, w, half, top);
			case SEND -> buildSend(m, x, w, half);
			case DELETE -> {
				int by = bottom() - 4 - 18;
				button(x, by, half, DELETE_CONFIRM, () -> {
					mode = Mode.ACTIONS;
					leaving = id;
					run("vc carte supprimer " + id, "Mise à la corbeille");
				}).tooltip("Elle reste 30 jours à la corbeille, avec ses versions : « Restaurer » la remet telle quelle.");
				button(x + half + 6, by, w - half - 6, CANCEL, () -> {
					mode = Mode.ACTIONS;
					rebuildSoon();
				});
			}
			default -> buildActions(m, x, w, half, top);
		}
	}

	private void buildActions(JsonObject m, int x, int w, int half, int top) {
		boolean open = !str(m, "atelier").isEmpty();
		HdButton first = button(x, top, w, open ? GO : OPEN, () -> {
			run("vc atelier carte ouvrir " + id, open ? "Voyage vers l'atelier" : "Ouverture de l'atelier");
			onClose();
		});
		first.primary().tooltip(open ? "Elle est déjà dans son atelier (« " + str(m, "atelierNom") + " ») : vous y retrouvez le travail en cours."
			: "Un monde neuf, la carte posée au centre : on y bâtit avec WorldEdit et on y pose les repères. Vous y êtes conduit à la fin.");
		int y2 = top + 22;
		HdButton send = button(x, y2, half, SEND, () -> {
			mode = Mode.SEND;
			rebuildSoon();
		});
		send.tooltip("Choisir la partie : son monde est remis à neuf, plat, puis la carte y est posée.");
		if (list(data(), "parties").isEmpty()) {
			send.disabled().tooltip("Aucun monde de partie sur ce serveur.");
		}
		button(x + half + 6, y2, w - half - 6, DUPLICATE, () -> {
			MapsPage.expectNew();
			run("vc carte copier " + id, "Copie de la carte");
			go(new MapsPage(0));
		}).tooltip("Une copie, « " + str(m, "nom") + " (copie) », sous un identifiant libre : une variante à retoucher.");
		int y3 = y2 + 22;
		int third = (w - 12) / 3;
		button(x, y3, third, RENAME, () -> {
			mode = Mode.RENAME;
			rebuildSoon();
		}).tooltip("Changer le nom affiché ; l'identifiant (« " + id + " »), auquel les scénarios se réfèrent, reste le même.");
		button(x + third + 6, y3, third, EXPORT, () -> run("vc carte exporter " + id, "Export de la carte"))
			.tooltip("Un fichier .valcombe dans le dossier des partages du serveur : il s'importe sur un autre serveur par l'Atelier web.");
		HdButton del = button(x + 2 * (third + 6), y3, w - 2 * (third + 6), DELETE, () -> {
			mode = Mode.DELETE;
			rebuildSoon();
		});
		if (open) {
			del.disabled().tooltip("Elle est ouverte dans l'atelier « " + str(m, "atelierNom") + " » : fermez d'abord l'atelier (Cartes › "
				+ "Ateliers ouverts).");
		} else {
			del.tooltip("À la corbeille, après confirmation (elle y reste 30 jours).");
		}
	}

	private void buildRename(JsonObject m, int x, int w, int half, int top) {
		int fy = bottom() - 4 - 18 - 22;
		HdTextField field = addRenderableWidget(new HdTextField(x, fy, w, 18, "Nouveau nom de la carte")
			.maxLength(50).hint("Nouveau nom…").value(str(m, "nom")).key(NAME_KEY));
		Runnable apply = () -> {
			String nom = field.value().strip();
			if (nom.isEmpty() || nom.equals(str(m, "nom"))) {
				return;
			}
			HdTextField.forget(NAME_KEY);
			mode = Mode.ACTIONS;
			run("vc carte renommer " + id + " " + nom, "Renommage");
			rebuildSoon();
		};
		field.onEnter(apply);
		field.responder(s -> rebuildSoon());
		int by = bottom() - 4 - 18;
		String typed = field.value().strip();
		HdButton ok = button(x, by, half, RENAME_CONFIRM, apply);
		if (typed.isEmpty()) {
			ok.disabled().tooltip("Il manque un nom (1 à 50 caractères).");
		} else if (typed.equals(str(m, "nom"))) {
			ok.disabled().tooltip("C'est déjà son nom.");
		} else {
			ok.primary();
		}
		button(x + half + 6, by, w - half - 6, CANCEL, () -> {
			HdTextField.forget(NAME_KEY);
			mode = Mode.ACTIONS;
			rebuildSoon();
		});
		setInitialFocus(field);
	}

	/** Les parties où l'envoyer, la partie principale d'abord. */
	static List<JsonObject> games() {
		return list(data(), "parties");
	}

	private void buildSend(JsonObject m, int x, int w, int half) {
		List<JsonObject> all = games();
		int ly = top() + 2 + 16;
		int lh = bottom() - 4 - 18 - 6 - 30 - ly;
		parties = addRenderableWidget(new HdList<JsonObject>(x, ly, w, Math.max(30, lh), 30, g -> str(g, "nom"))
			.items(all).key("atelier.carte.parties").empty("Aucun monde de partie")
			.onSelect(g -> {
				target = str(g, "id");
				rebuildSoon();
			}));
		parties.row((hd, g, i, rx, ry, rw, rh, hovered, sel) -> {
			if (sel) {
				hd.fill(rx, ry, rx + rw, ry + rh, Hd.SELECTION);
			} else if (hovered) {
				hd.fill(rx, ry, rx + rw, ry + rh, Hd.HOVER);
			}
			int pad = hd.u(3);
			int side = rh - 2 * pad;
			Vignettes.draw(hd, "partie:" + str(g, "id"), str(g, "vignette"), rx + pad, ry + pad, side, side);
			int tx = rx + pad + side + hd.u(5);
			int tw = rx + rw - pad - tx;
			String name = str(g, "nom") + (ClientState.bool(g, "principale") ? " (principale)" : "");
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, name, tw), tx, ry + pad, sel ? Hd.LIGHT : Hd.INK);
			hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, partyLine(g), tw), tx, ry + pad + hd.lineHeight(HdStyle.FORT),
				sel ? Hd.GOLD : Hd.INK_SOFT);
		});
		int pick = all.isEmpty() ? -1 : 0;
		for (int i = 0; i < all.size(); i++) {
			if (str(all.get(i), "id").equals(target)) {
				pick = i;
			}
		}
		parties.select(pick);
		JsonObject g = parties.selected();
		int by = bottom() - 4 - 18;
		HdButton ok = button(x, by, half, SEND_CONFIRM, () -> {
			JsonObject chosen = parties.selected();
			if (chosen == null) {
				return;
			}
			mode = Mode.ACTIONS;
			run("vc carte envoyer " + id + " " + str(chosen, "id"), "Envoi à « " + str(chosen, "nom") + " »");
			rebuildSoon();
		});
		if (g == null) {
			ok.disabled().tooltip("Il manque la partie où l'envoyer.");
		} else if (!str(g, "envoi").isEmpty()) {
			ok.disabled().tooltip("Une carte est déjà en route vers « " + str(g, "nom") + " » : attendez la fin de la barre.");
		} else {
			ok.primary().tooltip("Le monde de « " + str(g, "nom") + " » est remis à neuf, plat et vide, puis « " + str(m, "nom")
				+ " » y est posée. Les joueurs présents attendent au hub.");
		}
		button(x + half + 6, by, w - half - 6, CANCEL, () -> {
			mode = Mode.ACTIONS;
			rebuildSoon();
		});
	}

	/** Ce que porte une partie, en une ligne : sa carte, un envoi en cours, une partie qui se joue. */
	static String partyLine(JsonObject g) {
		if (!str(g, "envoi").isEmpty()) {
			return "Reçoit " + str(g, "envoi");
		}
		String carte = str(g, "carteNom").isEmpty() ? (str(g, "carte").isEmpty() ? "sans carte de la bibliothèque" : "carte « "
			+ str(g, "carte") + " »") : "carte « " + str(g, "carteNom") + " »";
		int n = ClientState.num(g, "joueurs", 0);
		return (ClientState.bool(g, "enCours") ? "Partie en cours, " : "") + carte + (n > 0 ? " · " + n + (n > 1 ? " joueurs" : " joueur") : "");
	}

	// ------------------------------------------------------------------
	// Dessin
	// ------------------------------------------------------------------

	@Override
	protected void drawPage(Hd hd) {
		JsonObject m = map();
		if (m == null) {
			hd.paragraph(HdStyle.TEXTE, id.equals(leaving) ? "Mise à la corbeille…" : "Cette carte n'est plus dans la bibliothèque "
				+ "(supprimée, ou renommée sous un autre identifiant).", hd.px(left()), hd.px(top() + 6), hd.px(right() - left()), Hd.INK_SOFT);
			return;
		}
		drawPicture(hd, m);
		int x = hd.px(colX());
		int w = hd.px(colW());
		int y = hd.px(top() + 2);
		if (mode == Mode.SEND) {
			hd.text(HdStyle.FORT, "Envoyer « " + hd.ellipsize(HdStyle.FORT, str(m, "nom"), w / 2) + " » à quelle partie ?", x, y + hd.u(1), Hd.INK);
			int ny = hd.px(bottom() - 4 - 18 - 6 - 30) + hd.u(3);
			JsonObject g = parties == null ? null : parties.selected();
			String warn = g == null ? "Choisissez la partie." : "Le monde de « " + str(g, "nom") + " » sera remis à neuf : tout ce qui s'y "
				+ "trouve est remplacé par « " + str(m, "nom") + " »." + (ClientState.bool(g, "enCours") ? " Une partie s'y joue." : "")
				+ (ClientState.bool(m, "jouable") ? "" : " " + str(m, "verdict"));
			hd.paragraph(HdStyle.PETIT, warn, x, ny, w, 0xFF7A1E24, hd.px(bottom() - 4 - 18) - ny - hd.u(2));
			return;
		}
		// Le nom, puis les pastilles.
		HdStyle title = HdStyle.SECTION.size(13);
		hd.text(title, hd.ellipsize(title, str(m, "nom"), w), x, y, Hd.WINE);
		y += hd.lineHeight(title) + hd.u(1);
		HdStyle cs = HdStyle.PETIT.size(8.5f);
		String miss = MapsPage.blocking(m);
		int cx = x;
		cx += AtelierArt.chip(hd, cs, miss == null ? "Jouable" : miss, cx, y, miss == null ? AtelierArt.GOOD : AtelierArt.BAD) + hd.u(3);
		String origin = ClientState.bool(m, "genere") ? "Village " + str(m, "theme") : "Faite à la main";
		cx += AtelierArt.chip(hd, cs, origin, cx, y, AtelierArt.NEUTRAL) + hd.u(3);
		if (!str(m, "atelier").isEmpty() && cx + AtelierArt.chipWidth(hd, cs, "En atelier") <= x + w) {
			AtelierArt.chip(hd, cs, "En atelier", cx, y, 0xFF8A5A0E);
		}
		y += AtelierArt.chipHeight(hd, cs) + hd.u(4);
		int limit = hd.px(mode == Mode.ACTIONS ? actionsTop() : bottom() - 4 - 18 - (mode == Mode.RENAME ? 22 + 12 : 0)) - hd.u(5);
		if (mode == Mode.DELETE) {
			int versions = ClientState.num(m, "versions", 0);
			String kept = versions == 0 ? "" : versions == 1 ? " avec sa version gardée" : " avec ses " + versions + " versions gardées";
			y += hd.paragraph(HdStyle.TEXTE, "Mettre « " + str(m, "nom") + " » à la corbeille ? Elle y reste 30 jours" + kept
				+ " ; « Restaurer » la remet telle quelle.", x, y, w, 0xFF7A1E24, limit - y);
			return;
		}
		// Les détails : une ligne « libellé  valeur » par sujet.
		int cote = ClientState.num(m, "cote", 0);
		List<String[]> rows = new ArrayList<>();
		rows.add(new String[] {"Taille", cote + " × " + cote + " blocs, " + ClientState.num(m, "hauteur", 0) + " de haut"});
		rows.add(new String[] {"Repères", MapsPage.counts(m).replace(" · ", ", ") + (ClientState.bool(m, "salle") ? ", salle" : "")});
		if (!str(m, "modifieeTexte").isEmpty()) {
			rows.add(new String[] {"Modifiée", "le " + str(m, "modifieeTexte")});
		}
		int versions = ClientState.num(m, "versions", 0);
		rows.add(new String[] {"Versions", versions == 0 ? "aucune gardée" : versions + (versions > 1 ? " gardées" : " gardée")});
		rows.add(new String[] {"Fichier", size(lng(m, "octets")) + " · « " + id + " »"});
		HdStyle ls = HdStyle.PETIT.size(9.5f);
		HdStyle vs = HdStyle.TEXTE.size(10);
		int labelW = 0;
		for (String[] r : rows) {
			labelW = Math.max(labelW, hd.width(ls, r[0]));
		}
		labelW += hd.u(6);
		int lh = hd.lineHeight(vs);
		for (String[] r : rows) {
			if (y + lh > limit) {
				break;
			}
			hd.text(ls, r[0], x, y + (lh - hd.lineHeight(ls)) / 2, Hd.BRONZE_INK);
			hd.text(vs, hd.ellipsize(vs, r[1], w - labelW), x + labelW, y, Hd.INK);
			y += lh;
		}
		// Ce qui manque pour jouer, puis les conseils.
		y += hd.u(3);
		for (JsonObject l : list(m, "manques")) {
			if (y + hd.lineHeight(HdStyle.PETIT) > limit) {
				break;
			}
			boolean blocking = ClientState.bool(l, "bloquant");
			int s = hd.u(6);
			int ty = y + (hd.lineHeight(HdStyle.PETIT) - s) / 2;
			if (blocking) {
				HdGlyphs.warning(hd, x, ty, s, AtelierArt.BAD);
			} else {
				hd.disc(x + s / 2, ty + s / 2, Math.max(1, hd.u(1.2)), Hd.BRONZE_INK);
			}
			y += hd.paragraph(HdStyle.PETIT, str(l, "texte"), x + s + hd.u(4), y, w - s - hd.u(4), blocking ? 0xFF7A1E24 : Hd.INK_SOFT,
				limit - y);
		}
		if (mode == Mode.RENAME) {
			hd.text(HdStyle.FORT, "Nouveau nom", x, hd.px(bottom() - 4 - 18 - 22) - hd.lineHeight(HdStyle.FORT) - hd.u(1), Hd.INK);
		}
	}

	/** La grande vignette, encadrée, et la légende de ses repères dessous. */
	private void drawPicture(Hd hd, JsonObject m) {
		int s = hd.px(side());
		int x = hd.px(left());
		int y = hd.px(top() + 2);
		hd.fill(x - hd.u(2), y - hd.u(2), x + s + hd.u(2), y + s + hd.u(2), 0xFF4A2E1C);
		hd.frame(x - hd.u(2), y - hd.u(2), s + hd.u(4), s + hd.u(4), Math.max(1, hd.u(0.5)), 0xFFD9B45A);
		Vignettes.draw(hd, "carte:" + id, str(m, "vignette"), x, y, s, s);
		// La légende : les couleurs des repères.
		HdStyle st = HdStyle.PETIT.size(8.5f);
		int ly = y + s + hd.u(4);
		int lx = x;
		String[] names = {"trône", "maison", "salle", "centre"};
		int[] colors = {0xFFF2C14E, 0xFF6FB37A, 0xFFC58BE6, 0xFFFFFAEB};
		for (int i = 0; i < names.length; i++) {
			int r = hd.u(2.5);
			if (lx + 2 * r + hd.u(3) + hd.width(st, names[i]) > x + s) {
				break;
			}
			hd.disc(lx + r, ly + hd.lineHeight(st) / 2, r + Math.max(1, hd.u(0.5)), 0xFF2A1E14);
			hd.disc(lx + r, ly + hd.lineHeight(st) / 2, r, colors[i]);
			lx += 2 * r + hd.u(3);
			lx += hd.text(st, names[i], lx, ly, Hd.INK_SOFT) + hd.u(8);
		}
	}

	/** « 1,2 Mo », « 340 Ko ». */
	static String size(long bytes) {
		if (bytes >= 1024 * 1024) {
			return String.format(Locale.FRENCH, "%.1f Mo", bytes / (1024.0 * 1024.0));
		}
		return Math.max(1, bytes / 1024) + " Ko";
	}
}
