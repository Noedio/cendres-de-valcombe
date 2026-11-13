package fr.valcombe.client.screen.atelier;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.Vignettes;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTextField;
import fr.valcombe.client.screen.StructureViewerScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Atelier › Structures › (une structure) : sa fiche. À gauche, sa grande vignette vue de trois quarts ; à droite, son
 * rayon, son état, sa taille, son repère, et ses actions :
 * <ul>
 * <li><b>Prendre en main</b> : l'outil de pose (le fantôme suit le regard, clic droit pour la poser) ;</li>
 * <li><b>Voir en 3D</b> : la maquette qui tourne ({@link StructureViewerScreen}) ;</li>
 * <li><b>Aller sur sa parcelle</b> : dans l'atelier structures, devant son pupitre, pour la retoucher avec WorldEdit ;</li>
 * <li>une structure fournie modifiée : <b>Revenir à l'originale</b> (confirmé) ; une structure à vous : <b>Renommer…</b>
 * (sur place) et <b>Supprimer</b> (confirmé, elle va à la corbeille du serveur).</li>
 * </ul>
 */
public class StructureSheetPage extends AtelierPage {
	public static final String TAKE = "Prendre en main";
	public static final String VIEW_3D = "Voir en 3D";
	public static final String GO_PLOT = "Aller sur sa parcelle";
	public static final String REVERT = "Revenir à l'originale";
	public static final String REVERT_CONFIRM = "Confirmer le retour";
	public static final String RENAME = "Renommer…";
	public static final String RENAME_CONFIRM = "Renommer";
	public static final String DELETE = "Supprimer";
	public static final String DELETE_CONFIRM = "Oui, supprimer";
	public static final String CANCEL = "Annuler";
	private static final String NAME_KEY = "atelier.structure.nom";

	private enum Mode {
		ACTIONS, RENAME, REVERT, DELETE
	}

	private static String lastId;
	private static Mode mode = Mode.ACTIONS;
	/** La structure vient d'être supprimée : la page revient au catalogue dès qu'elle en disparaît. */
	private static String leaving;

	private final String id;
	private boolean leave;

	public StructureSheetPage(String id) {
		this.id = id;
		if (!id.equals(lastId)) {
			lastId = id;
			mode = Mode.ACTIONS;
		}
	}

	/** La fiche ouverte depuis le catalogue : elle commence sur ses actions (pas sur une confirmation laissée en route). */
	public static StructureSheetPage visit(String id) {
		lastId = null;
		return new StructureSheetPage(id);
	}

	public String structureId() {
		return id;
	}

	private JsonObject structure() {
		return StructuresPage.structure(id);
	}

	@Override
	protected List<Crumb> trail() {
		JsonObject s = structure();
		List<Crumb> t = new ArrayList<>(StructuresPage.crumbs());
		t.add(new Crumb(s == null ? id : str(s, "nom"), null));
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
			go(new StructuresPage(0));
		}
	}

	// ------------------------------------------------------------------
	// Mise en page : vignette à gauche, fiche et actions à droite
	// ------------------------------------------------------------------

	private int side() {
		int h = bottom() - top() - 4;
		return Math.max(60, Math.min(h, (right() - left()) * 45 / 100));
	}

	private int colX() {
		return left() + side() + 14;
	}

	private int colW() {
		return right() - colX();
	}

	private int actionsTop() {
		return bottom() - 4 - 3 * 18 - 2 * 4;
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	@Override
	protected void buildPage() {
		JsonObject s = structure();
		if (s == null) {
			if (id.equals(leaving)) {
				leave = true;
			} else {
				button(left(), top() + 40, 160, "Retour au catalogue", () -> go(new StructuresPage(0))).primary();
			}
			return;
		}
		int x = colX();
		int w = colW();
		int half = (w - 6) / 2;
		int by = bottom() - 4 - 18;
		switch (mode) {
			case RENAME -> buildRename(s, x, w, half);
			case REVERT -> {
				button(x, by, half, REVERT_CONFIRM, () -> {
					mode = Mode.ACTIONS;
					run("vc structure originale " + id, "Retour à l'originale");
					rebuildSoon();
				}).primary().tooltip("La version modifiée part à la corbeille du serveur ; l'originale sert de nouveau partout.");
				button(x + half + 6, by, w - half - 6, CANCEL, () -> {
					mode = Mode.ACTIONS;
					rebuildSoon();
				});
			}
			case DELETE -> {
				button(x, by, half, DELETE_CONFIRM, () -> {
					mode = Mode.ACTIONS;
					leaving = id;
					run("vc structure supprimer " + id, "Suppression de la structure");
				}).tooltip("Elle quitte le catalogue ; ses fichiers sont gardés dans la corbeille du serveur.");
				button(x + half + 6, by, w - half - 6, CANCEL, () -> {
					mode = Mode.ACTIONS;
					rebuildSoon();
				});
			}
			default -> buildActions(s, x, w, half);
		}
	}

	private void buildActions(JsonObject s, int x, int w, int half) {
		int top = actionsTop();
		boolean held = id.equals(StructuresPage.held());
		HdButton take = button(x, top, w, TAKE, () -> {
			StructuresPage.take(id);
			onClose();
		});
		take.primary().tooltip(held ? "Elle est déjà en main : l'écran se ferme, son fantôme suit votre regard."
			: "L'outil de pose : son fantôme suit votre regard, clic droit pour la poser. Dans une partie, le mode construction s'ouvre.");
		int y2 = top + 22;
		button(x, y2, half, VIEW_3D, () -> go(new StructureViewerScreen(id, () -> new StructureSheetPage(id))))
			.tooltip("Sa maquette en 3D, qui tourne ; les flèches la tournent à la main.");
		HdButton plot = button(x + half + 6, y2, w - half - 6, GO_PLOT, () -> {
			run("vc structures aller " + id, "Voyage vers sa parcelle");
			onClose();
		});
		if (!ClientState.bool(s, "parcelle") && ClientState.bool(data(), "atelierStructures")) {
			plot.disabled().tooltip("Elle n'a pas encore de parcelle : l'atelier structures lui en donne une à la prochaine visite.");
		} else {
			plot.tooltip("Dans l'atelier structures, devant son pupitre : on la retouche avec WorldEdit, puis « Enregistrer » au pupitre.");
		}
		int y3 = y2 + 22;
		if (StructuresPage.mine(s)) {
			button(x, y3, half, RENAME, () -> {
				mode = Mode.RENAME;
				rebuildSoon();
			}).tooltip("Changer le nom affiché ; l'identifiant (« " + id + " ») reste le même.");
			button(x + half + 6, y3, w - half - 6, DELETE, () -> {
				mode = Mode.DELETE;
				rebuildSoon();
			}).tooltip("La retirer du catalogue, après confirmation.");
		} else {
			HdButton revert = button(x, y3, w, REVERT, () -> {
				mode = Mode.REVERT;
				rebuildSoon();
			});
			if (ClientState.bool(s, "modifiee")) {
				revert.tooltip("Effacer vos changements : l'originale du mod revient partout (une confirmation suit).");
			} else {
				revert.disabled().tooltip("Elle n'a pas été modifiée : c'est l'originale. Une structure fournie ne se renomme ni ne se supprime.");
			}
		}
	}

	private void buildRename(JsonObject s, int x, int w, int half) {
		int fy = bottom() - 4 - 18 - 22;
		HdTextField field = addRenderableWidget(new HdTextField(x, fy, w, 18, "Nouveau nom de la structure")
			.maxLength(50).hint("Nouveau nom…").value(str(s, "nom")).key(NAME_KEY));
		Runnable apply = () -> {
			String nom = field.value().strip();
			if (nom.isEmpty() || nom.equals(str(s, "nom"))) {
				return;
			}
			HdTextField.forget(NAME_KEY);
			mode = Mode.ACTIONS;
			run("vc structure renommer " + id + " " + nom, "Renommage");
			rebuildSoon();
		};
		field.onEnter(apply);
		field.responder(t -> rebuildSoon());
		int by = bottom() - 4 - 18;
		String typed = field.value().strip();
		HdButton ok = button(x, by, half, RENAME_CONFIRM, apply);
		if (typed.isEmpty()) {
			ok.disabled().tooltip("Il manque un nom (1 à 50 caractères).");
		} else if (typed.equals(str(s, "nom"))) {
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

	// ------------------------------------------------------------------
	// Dessin
	// ------------------------------------------------------------------

	@Override
	protected void drawPage(Hd hd) {
		JsonObject s = structure();
		if (s == null) {
			hd.paragraph(HdStyle.TEXTE, id.equals(leaving) ? "Suppression…" : "Cette structure n'est plus au catalogue.",
				hd.px(left()), hd.px(top() + 6), hd.px(right() - left()), Hd.INK_SOFT);
			return;
		}
		// La grande vignette, encadrée d'or.
		int side = hd.px(side());
		int px0 = hd.px(left());
		int py0 = hd.px(top() + 2);
		hd.fill(px0 - hd.u(2), py0 - hd.u(2), px0 + side + hd.u(2), py0 + side + hd.u(2), 0xFF4A2E1C);
		hd.frame(px0 - hd.u(2), py0 - hd.u(2), side + hd.u(4), side + hd.u(4), Math.max(1, hd.u(0.5)), 0xFFD9B45A);
		Vignettes.draw(hd, "structure:" + id, str(s, "vignette"), px0, py0, side, side);
		if (id.equals(StructuresPage.held())) {
			AtelierArt.ribbon(hd, "En main", px0 + side - hd.u(3), py0 + hd.u(3), 0xE06E4F22);
		}
		int x = hd.px(colX());
		int w = hd.px(colW());
		int y = py0;
		HdStyle title = HdStyle.SECTION.size(13);
		hd.text(title, hd.ellipsize(title, str(s, "nom"), w), x, y, Hd.WINE);
		y += hd.lineHeight(title) + hd.u(1);
		HdStyle cs = HdStyle.PETIT.size(8.5f);
		int cx = x;
		cx += AtelierArt.chip(hd, cs, StructuresPage.state(s), cx, y, StructuresPage.stateColor(s)) + hd.u(3);
		String shelf = StructuresPage.shelfName(str(s, "categorie"));
		if (cx + AtelierArt.chipWidth(hd, cs, shelf) <= x + w) {
			AtelierArt.chip(hd, cs, shelf, cx, y, AtelierArt.NEUTRAL);
		}
		y += AtelierArt.chipHeight(hd, cs) + hd.u(4);
		int limit = hd.px(switch (mode) {
			case ACTIONS -> actionsTop();
			case RENAME -> bottom() - 4 - 18 - 22 - 12;
			default -> bottom() - 4 - 18;
		}) - hd.u(5);
		if (mode == Mode.REVERT || mode == Mode.DELETE) {
			String q = mode == Mode.REVERT
				? "Revenir à l'originale de « " + str(s, "nom") + " » ? Vos changements partent à la corbeille du serveur, et l'originale du mod "
					+ "sert de nouveau partout : villages générés, catalogue, atelier structures."
				: "Supprimer « " + str(s, "nom") + " » du catalogue ? Ses fichiers sont gardés dans la corbeille du serveur ; les cartes où elle "
					+ "est déjà posée la gardent.";
			hd.paragraph(HdStyle.TEXTE, q, x, y, w, AtelierKit.WARN, limit - y);
			return;
		}
		List<String[]> rows = new ArrayList<>();
		rows.add(new String[] {"Taille", StructuresPage.dims(s) + " blocs (largeur, hauteur, profondeur)"});
		rows.add(new String[] {"Blocs", ClientState.num(s, "blocs", 0) + " posés"});
		String mark = StructuresPage.markText(s);
		rows.add(new String[] {"Repère", mark.isEmpty() ? "aucun" : mark});
		if (!str(s, "modifieeLe").isEmpty()) {
			rows.add(new String[] {"Modifiée", "le " + str(s, "modifieeLe")});
		}
		rows.add(new String[] {"Identifiant", "« " + id + " »"});
		y = AtelierKit.details(hd, rows, x, y, w, limit) + hd.u(3);
		String tip = StructuresPage.mine(s)
			? "Créée sur ce serveur : elle se pose dans les cartes, se retouche sur sa parcelle, se renomme et se supprime."
			: ClientState.bool(s, "modifiee")
				? "Fournie par le mod, modifiée dans l'atelier structures : c'est votre version qui sert partout, villages générés compris."
				: "Fournie par le mod : elle se retouche sur sa parcelle de l'atelier structures (« Enregistrer » au pupitre), sans perdre l'originale.";
		AtelierKit.note(hd, tip, x, y, w, limit - y);
		if (mode == Mode.RENAME) {
			hd.text(HdStyle.FORT, "Nouveau nom", x, hd.px(bottom() - 4 - 18 - 22) - hd.lineHeight(HdStyle.FORT) - hd.u(1), Hd.INK);
		}
	}
}
