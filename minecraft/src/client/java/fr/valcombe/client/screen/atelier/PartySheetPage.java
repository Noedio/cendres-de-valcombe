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
import fr.valcombe.client.screen.PartiesScreen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Atelier › Parties › (une partie) : sa fiche. À gauche, la carte qu'elle porte vue de dessus et le ciel de sa phase ; à
 * droite, son état, ses joueurs, son Conteur, sa carte, son scénario, et ses actions :
 * <ul>
 * <li><b>Y aller</b>, <b>Envoyer une carte…</b> (une carte de la bibliothèque, choisie en vignette, puis confirmée : son
 * monde est remis à neuf) ;</li>
 * <li><b>Suspendre</b> ou <b>Reprendre</b> une partie lancée ; <b>Supprimer</b> une partie d'essai (confirmé) ;</li>
 * <li><b>Carte en place…</b> : ce qui reprend l'onglet Cartes de l'ancien Atelier : enregistrer la carte dans la
 * bibliothèque (sous son nom, ou sous un autre), envoyer une carte vide, vider le monde (confirmés) ;</li>
 * <li><b>Retoucher sur place…</b> (dans son monde) : ce qui reprend l'onglet Repères : le mode construction, les trônes,
 * les maisons, le centre, la salle d'attente, la main, avec la liste de contrôle de la carte.</li>
 * </ul>
 */
public class PartySheetPage extends AtelierPage {
	public static final String GO = "Y aller";
	public static final String HERE = "Vous y êtes";
	public static final String SEND = "Envoyer une carte…";
	public static final String SEND_CONFIRM = "Confirmer l'envoi";
	public static final String SUSPEND = "Suspendre";
	public static final String RESUME = "Reprendre";
	public static final String DELETE = "Supprimer";
	public static final String DELETE_CONFIRM = "Supprimer la partie";
	public static final String MAP = "Carte en place…";
	public static final String TOUCH = "Retoucher sur place…";
	public static final String SAVE = "Enregistrer";
	public static final String SAVE_AS = "Enregistrer sous…";
	public static final String BLANK = "Carte vide…";
	public static final String BLANK_CONFIRM = "Envoyer une carte vide";
	public static final String CLEAR = "Vider le monde…";
	public static final String CLEAR_CONFIRM = "Vider le monde";
	public static final String BUILD_ON = "Entrer en construction";
	public static final String BUILD_OFF = "Quitter la construction";
	public static final String BACK = "Retour aux actions";
	public static final String CANCEL = "Annuler";
	private static final String NAME_KEY = "atelier.partie.carte.nom";

	private enum Mode {
		ACTIONS, SEND, DELETE, MAP, SAVE_AS, BLANK, CLEAR, TOUCH
	}

	private static String lastId;
	private static Mode mode = Mode.ACTIONS;
	private static String chosenMap;
	/** La partie vient d'être supprimée : la page revient à la liste dès qu'elle en disparaît. */
	private static String leaving;

	private final String id;
	private HdList<JsonObject> maps;
	private boolean leave;

	public PartySheetPage(String id) {
		this.id = id;
		if (!id.equals(lastId)) {
			lastId = id;
			mode = Mode.ACTIONS;
			chosenMap = null;
		}
	}

	/** La fiche ouverte depuis la liste : elle commence sur ses actions (pas sur une confirmation laissée en route). */
	public static PartySheetPage visit(String id) {
		lastId = null;
		return new PartySheetPage(id);
	}

	public String gameId() {
		return id;
	}

	private JsonObject game() {
		return PartiesPage.game(id);
	}

	@Override
	protected List<Crumb> trail() {
		JsonObject g = game();
		List<Crumb> t = new ArrayList<>(PartiesPage.crumbs());
		t.add(new Crumb(g == null ? id : str(g, "nom"), null));
		return t;
	}

	@Override
	protected boolean cancelPending() {
		if (mode == Mode.ACTIONS) {
			return false;
		}
		HdTextField.forget(NAME_KEY);
		mode = mode == Mode.SAVE_AS || mode == Mode.BLANK || mode == Mode.CLEAR ? Mode.MAP : Mode.ACTIONS;
		return true;
	}

	@Override
	public void tick() {
		super.tick();
		if (leave) {
			leave = false;
			leaving = null;
			go(new PartiesPage());
		}
	}

	// ------------------------------------------------------------------
	// Mise en page
	// ------------------------------------------------------------------

	private int side() {
		int h = bottom() - top() - 4 - 14;
		return Math.max(60, Math.min(h, (right() - left()) * 40 / 100));
	}

	private int colX() {
		return left() + side() + 14;
	}

	private int colW() {
		return right() - colX();
	}

	/** Haut de la rangée de boutons n (0 : la plus basse). */
	private int row(int n) {
		return bottom() - 4 - 18 - n * 22;
	}

	/** Le haut des boutons du mode en cours : la fiche s'écrit au-dessus. */
	private int buttonsTop() {
		return switch (mode) {
			case ACTIONS, MAP -> row(2);
			case TOUCH -> row(4);
			case SAVE_AS -> row(1);
			case SEND -> row(0) - 30;
			default -> row(0);
		};
	}

	// ------------------------------------------------------------------
	// État
	// ------------------------------------------------------------------

	private static JsonObject current(JsonObject g) {
		return ClientState.obj(g, "actuelle");
	}

	/** La carte du monde est-elle occupée (pose en cours, carte en route) ? */
	private static boolean busy(JsonObject g) {
		return ClientState.bool(g, "chantier") || !str(g, "envoi").isEmpty();
	}

	private static final String BUSY = "Une carte est en train d'être posée dans ce monde : attendez la fin de la barre.";

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	@Override
	protected void buildPage() {
		JsonObject g = game();
		if (g == null) {
			if (id.equals(leaving)) {
				leave = true;
			} else {
				button(left(), top() + 40, 160, "Retour aux parties", () -> go(new PartiesPage())).primary();
			}
			return;
		}
		int x = colX();
		int w = colW();
		int half = (w - 6) / 2;
		switch (mode) {
			case SEND -> buildSend(g, x, w, half);
			case MAP -> buildMap(g, x, w, half);
			case SAVE_AS -> buildSaveAs(g, x, w, half);
			case TOUCH -> buildTouch(g, x, w, half);
			case DELETE -> confirm(x, w, half, DELETE_CONFIRM, "vc partie supprimer " + id, "Suppression de la partie", true,
				"Son monde est supprimé ; ceux qui s'y trouvent vont au hub. On ne revient pas en arrière.");
			case BLANK -> confirm(x, w, half, BLANK_CONFIRM, "vc carte nouvelle " + id, "Envoi d'une carte vide", false,
				"Le monde est remis à neuf, plat et vide, avec une place au centre.");
			case CLEAR -> confirm(x, w, half, CLEAR_CONFIRM, "vc carte vider " + id, "Remise à neuf du monde", false,
				"Le monde est remis à neuf, plat et vide : plus de carte.");
			default -> buildActions(g, x, w, half);
		}
	}

	/** Les deux boutons d'une confirmation, en bas de la colonne. */
	private void confirm(int x, int w, int half, String label, String command, String what, boolean leaves, String tip) {
		int by = row(0);
		button(x, by, half, label, () -> {
			mode = Mode.ACTIONS;
			if (leaves) {
				leaving = id;
			}
			run(command, what);
			rebuildSoon();
		}).tooltip(tip);
		button(x + half + 6, by, w - half - 6, CANCEL, () -> {
			mode = Mode.ACTIONS;
			rebuildSoon();
		});
	}

	private void to(Mode m) {
		mode = m;
		rebuildSoon();
	}

	private void buildActions(JsonObject g, int x, int w, int half) {
		boolean here = ClientState.bool(g, "ici");
		HdButton goThere = button(x, row(2), half, here ? HERE : GO, () -> {
			run("vc partie aller " + id, "Voyage vers « " + str(g, "nom") + " »");
			onClose();
		});
		if (here) {
			goThere.disabled().tooltip("Vous êtes dans son monde.");
		} else {
			goThere.primary().tooltip("Dans son monde, à votre dernière position (la première fois, sur la place).");
		}
		HdButton send = button(x + half + 6, row(2), w - half - 6, SEND, () -> to(Mode.SEND));
		if (busy(g)) {
			send.disabled().tooltip(BUSY);
		} else if (MapsPage.library().isEmpty()) {
			send.disabled().tooltip("La bibliothèque est vide : Atelier › Cartes › « Nouvelle carte ».");
		} else {
			send.tooltip("Choisir une carte de la bibliothèque : son monde est remis à neuf, puis la carte y est posée.");
		}
		String statut = PartiesPage.status(g);
		boolean suspended = "suspendue".equals(statut);
		boolean running = "en-cours".equals(statut) || "en-pause".equals(statut);
		HdButton pause = button(x, row(1), half, suspended ? RESUME : SUSPEND, () -> run("vc partie " + (suspended ? "reprendre " : "suspendre ")
			+ id, suspended ? "Reprise de la partie" : "Mise en suspens"));
		if (!suspended && !running) {
			pause.disabled().tooltip("Seule une partie lancée se met en suspens.");
		} else {
			pause.tooltip(suspended ? "Ses joueurs sont rappelés et retrouvent leur place ; elle repart quand tous sont là."
				: "Horloge arrêtée, joueurs au hub : elle reprendra où elle en est, même après un redémarrage.");
		}
		HdButton del = button(x + half + 6, row(1), w - half - 6, DELETE, () -> to(Mode.DELETE));
		if (ClientState.bool(g, "principale")) {
			del.disabled().tooltip("La partie principale ne se supprime pas.");
		} else if (!"ouverte".equals(statut)) {
			del.disabled().tooltip("Une partie lancée ne se supprime pas, même en suspens : arrêtez-la d'abord (/vc arreter, dans son monde).");
		} else {
			del.tooltip("Supprimer cette partie d'essai et son monde (une confirmation suit).");
		}
		button(x, row(0), half, MAP, () -> to(Mode.MAP))
			.tooltip("Enregistrer la carte de cette partie dans la bibliothèque, lui envoyer une carte vide, vider son monde.");
		HdButton touch = button(x + half + 6, row(0), w - half - 6, TOUCH, () -> to(Mode.TOUCH));
		if (!here) {
			touch.disabled().tooltip("On retouche une carte dans son monde : « " + GO + " » d'abord.");
		} else if (current(g) == null) {
			touch.disabled().tooltip("Ce monde n'a pas de carte : envoyez-en une, ou une carte vide.");
		} else {
			touch.tooltip("Le mode construction, les trônes, les maisons, le centre, la salle d'attente, la main.");
		}
	}

	/** Les cartes de la bibliothèque où choisir celle à envoyer. */
	private void buildSend(JsonObject g, int x, int w, int half) {
		List<JsonObject> all = MapsPage.shown(MapsPage.library(), "", 0);
		int ly = top() + 2 + 16;
		int lh = row(0) - 6 - 30 - ly;
		maps = addRenderableWidget(new HdList<JsonObject>(x, ly, w, Math.max(30, lh), 30, m -> str(m, "nom"))
			.items(all).key("atelier.partie.cartes").empty("La bibliothèque est vide")
			.onSelect(m -> {
				chosenMap = str(m, "id");
				rebuildSoon();
			}));
		maps.row((hd, m, i, rx, ry, rw, rh, hovered, sel) -> {
			AtelierKit.rowBack(hd, rx, ry, rw, rh, hovered, sel);
			int pad = hd.u(3);
			int s = rh - 2 * pad;
			Vignettes.draw(hd, "carte:" + str(m, "id"), str(m, "vignette"), rx + pad, ry + pad, s, s);
			int tx = rx + pad + s + hd.u(5);
			int tw = rx + rw - pad - tx;
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(m, "nom"), tw), tx, ry + pad, sel ? Hd.LIGHT : Hd.INK);
			String miss = MapsPage.blocking(m);
			hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, MapsPage.counts(m) + (miss == null ? "" : " · " + miss), tw), tx,
				ry + pad + hd.lineHeight(HdStyle.FORT), sel ? Hd.GOLD : Hd.INK_SOFT);
		});
		int pick = all.isEmpty() ? -1 : 0;
		for (int i = 0; i < all.size(); i++) {
			if (str(all.get(i), "id").equals(chosenMap)) {
				pick = i;
			}
		}
		maps.select(pick);
		JsonObject m = maps.selected();
		HdButton ok = button(x, row(0), half, SEND_CONFIRM, () -> {
			JsonObject chosen = maps.selected();
			if (chosen == null) {
				return;
			}
			mode = Mode.ACTIONS;
			run("vc carte envoyer " + str(chosen, "id") + " " + id, "Envoi de « " + str(chosen, "nom") + " »");
			rebuildSoon();
		});
		if (m == null) {
			ok.disabled().tooltip("Il manque la carte à envoyer.");
		} else if (busy(g)) {
			ok.disabled().tooltip(BUSY);
		} else {
			ok.primary().tooltip("Le monde de « " + str(g, "nom") + " » est remis à neuf, plat et vide, puis « " + str(m, "nom")
				+ " » y est posée. Les joueurs présents attendent au hub.");
		}
		button(x + half + 6, row(0), w - half - 6, CANCEL, () -> to(Mode.ACTIONS));
	}

	private void buildMap(JsonObject g, int x, int w, int half) {
		JsonObject cur = current(g);
		boolean busy = busy(g);
		String libraryId = str(cur, "id");
		HdButton save = button(x, row(2), half, SAVE, () -> {
			JsonObject c = current(game());
			run("vc carte enregistrer-partie " + id + " " + str(c, "id") + " " + str(c, "nom"), "Enregistrement de la carte");
		});
		if (cur == null) {
			save.disabled().tooltip("Ce monde n'a pas de carte.");
		} else if (busy) {
			save.disabled().tooltip(BUSY);
		} else if (libraryId.isEmpty()) {
			save.disabled().tooltip("Elle n'est pas encore dans la bibliothèque : « " + SAVE_AS + " » lui donne un nom.");
		} else if (!ClientState.bool(cur, "modifiee")) {
			save.disabled().tooltip("Elle est à jour dans la bibliothèque (« " + str(cur, "nom") + " ») : rien à enregistrer.");
		} else {
			save.primary().tooltip("Ranger ses retouches dans la bibliothèque, sous « " + str(cur, "nom") + " » : la version rangée est remplacée.");
		}
		HdButton saveAs = button(x + half + 6, row(2), w - half - 6, SAVE_AS, () -> to(Mode.SAVE_AS));
		if (cur == null) {
			saveAs.disabled().tooltip("Ce monde n'a pas de carte.");
		} else if (busy) {
			saveAs.disabled().tooltip(BUSY);
		} else if (ClientState.num(cur, "sieges", 0) < 5) {
			saveAs.disabled().tooltip("Il lui faut au moins 5 trônes pour entrer dans la bibliothèque.");
		} else {
			saveAs.tooltip("Une nouvelle carte de la bibliothèque, sous le nom de votre choix.");
			if (libraryId.isEmpty()) {
				saveAs.primary();
			}
		}
		HdButton blank = button(x, row(1), half, BLANK, () -> to(Mode.BLANK));
		HdButton clear = button(x + half + 6, row(1), w - half - 6, CLEAR, () -> to(Mode.CLEAR));
		if (busy) {
			blank.disabled().tooltip(BUSY);
			clear.disabled().tooltip(BUSY);
		} else {
			blank.tooltip("Le monde remis à neuf, plat, avec une place au centre où bâtir (une confirmation suit).");
			clear.tooltip("Le monde remis à neuf, plat et vide, sans carte (une confirmation suit).");
		}
		button(x, row(0), w, BACK, () -> to(Mode.ACTIONS));
	}

	/** Les identifiants pris dans la bibliothèque. */
	private static Set<String> takenIds() {
		Set<String> out = new HashSet<>();
		for (JsonObject m : MapsPage.library()) {
			out.add(str(m, "id"));
		}
		return out;
	}

	private void buildSaveAs(JsonObject g, int x, int w, int half) {
		HdTextField field = addRenderableWidget(new HdTextField(x, row(1), w, 18, "Nom de la carte dans la bibliothèque")
			.maxLength(50).hint("Nom de la carte…").key(NAME_KEY));
		Runnable apply = () -> {
			String nom = field.value().strip();
			if (nom.isEmpty()) {
				return;
			}
			String newId = AtelierKit.slug(nom, takenIds()::contains);
			HdTextField.forget(NAME_KEY);
			mode = Mode.MAP;
			MapsPage.expectNew();
			run("vc carte enregistrer-partie " + id + " " + newId + " " + nom, "Enregistrement de la carte");
			rebuildSoon();
		};
		field.onEnter(apply);
		field.responder(s -> rebuildSoon());
		HdButton ok = button(x, row(0), half, SAVE, apply);
		if (field.value().strip().isEmpty()) {
			ok.disabled().tooltip("Il manque un nom (1 à 50 caractères).");
		} else {
			ok.primary().tooltip("Identifiant : « " + AtelierKit.slug(field.value().strip(), takenIds()::contains) + " ».");
		}
		button(x + half + 6, row(0), w - half - 6, CANCEL, () -> {
			HdTextField.forget(NAME_KEY);
			to(Mode.MAP);
		});
		setInitialFocus(field);
	}

	private void buildTouch(JsonObject g, int x, int w, int half) {
		boolean building = ClientState.bool(StructuresPage.editor(), "construction");
		String[][] grid = {
			{building ? BUILD_OFF : BUILD_ON, "vc carte construction",
				building ? "Sortir du mode construction : la carte est tenue pour retouchée." : "Bâtir dans le monde de la partie : l'écran se ferme."},
			{"Prendre la main", "main", "L'outil « main » : clic droit sur un trône, une maison, la salle ou une structure pour la "
				+ "déplacer, la tourner ou la retirer."},
			{"Trône ici", "vc carte trone", "Un trône là où vous vous tenez."},
			{"Maison ici", "vc carte maison", "Une maison (là où l'on dort la nuit) là où vous vous tenez."},
			{"Centre ici", "vc carte centre", "Le centre de la place, là où vous vous tenez."},
			{"Salle d'attente", "vc carte salle", "Bâtir la salle d'attente des joueurs, au bord de la place."},
			{"Renuméroter", "vc carte ordonner", "Renuméroter les trônes dans le sens des aiguilles d'une montre."},
			{"Annuler le geste", "vc carte annuler", "Revenir sur le dernier repère posé, déplacé ou retiré."},
			{"Mes outils", "vc carte outils", "Les outils de repères dans votre inventaire."},
		};
		for (int i = 0; i < grid.length; i++) {
			int r = 4 - i / 2;
			int bx = i % 2 == 0 ? x : x + half + 6;
			int bw = i % 2 == 0 ? half : w - half - 6;
			String label = grid[i][0];
			String cmd = grid[i][1];
			HdButton b = button(bx, row(r), bw, label, () -> {
				if (cmd.equals("main")) {
					if (!building) {
						ClientState.send("vc carte construction");
					}
					run("vc structure tenir", "La main");
					onClose();
				} else if (cmd.equals("vc carte construction") && !building) {
					run(cmd, "Mode construction");
					onClose(); // on entre pour bâtir : l'écran laisse la place au monde
				} else {
					run(cmd, label);
				}
			});
			b.tooltip(grid[i][2]);
			if (i == 0 && !building) {
				b.primary();
			}
		}
		button(x + half + 6, row(0), w - half - 6, BACK, () -> to(Mode.ACTIONS));
	}

	// ------------------------------------------------------------------
	// Dessin
	// ------------------------------------------------------------------

	@Override
	protected void drawPage(Hd hd) {
		JsonObject g = game();
		if (g == null) {
			hd.paragraph(HdStyle.TEXTE, id.equals(leaving) ? "Suppression de la partie…" : "Cette partie n'existe plus.",
				hd.px(left()), hd.px(top() + 6), hd.px(right() - left()), Hd.INK_SOFT);
			return;
		}
		if (mode == Mode.TOUCH) {
			drawChecks(hd, g); // pour retoucher, la liste de contrôle prend la place de l'image
		} else {
			drawPicture(hd, g);
		}
		int x = hd.px(colX());
		int w = hd.px(colW());
		int y = hd.px(top() + 2);
		int limit = hd.px(buttonsTop()) - hd.u(5);
		if (mode == Mode.SEND) {
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, "Quelle carte envoyer à « " + str(g, "nom") + " » ?", w), x, y + hd.u(1), Hd.INK);
			int ny = hd.px(row(0) - 6 - 30) + hd.u(3);
			JsonObject m = maps == null ? null : maps.selected();
			String warn = m == null ? "Choisissez la carte." : "Le monde de « " + str(g, "nom") + " » sera remis à neuf : tout ce qui s'y trouve "
				+ "est remplacé par « " + str(m, "nom") + " »." + (ClientState.bool(g, "enCours") ? " Une partie s'y joue." : "")
				+ (MapsPage.blocking(m) == null ? "" : " " + str(m, "verdict"));
			hd.paragraph(HdStyle.PETIT, warn, x, ny, w, AtelierKit.WARN, hd.px(row(0)) - ny - hd.u(2));
			return;
		}
		HdStyle title = HdStyle.SECTION.size(13);
		hd.text(title, hd.ellipsize(title, str(g, "nom"), w), x, y, Hd.WINE);
		y += hd.lineHeight(title) + hd.u(1);
		HdStyle cs = HdStyle.PETIT.size(8.5f);
		int cx = x;
		JsonObject c = PartiesPage.card(g);
		if (c != null) {
			cx += AtelierArt.chip(hd, cs, str(c, "statutTexte"), cx, y, PartiesScreen.statusColor(PartiesPage.status(g))) + hd.u(3);
		}
		String kind = PartiesPage.kind(g);
		cx += AtelierArt.chip(hd, cs, kind, cx, y, AtelierArt.NEUTRAL) + hd.u(3);
		if (ClientState.bool(g, "ici") && cx + AtelierArt.chipWidth(hd, cs, HERE) <= x + w) {
			AtelierArt.chip(hd, cs, HERE, cx, y, AtelierArt.GOOD);
		}
		y += AtelierArt.chipHeight(hd, cs) + hd.u(4);
		switch (mode) {
			case DELETE -> hd.paragraph(HdStyle.TEXTE, "Supprimer la partie d'essai « " + str(g, "nom") + " » et son monde ? Ceux qui s'y trouvent "
				+ "vont au hub. On ne revient pas en arrière.", x, y, w, AtelierKit.WARN, limit - y);
			case BLANK -> hd.paragraph(HdStyle.TEXTE, "Envoyer une carte vide à « " + str(g, "nom") + " » ? Son monde est remis à neuf, plat, avec "
				+ "une place au centre : la carte en place est remplacée" + (retouched(g) ? " (retouchée, elle est d'abord sauvegardée dans la "
					+ "bibliothèque)." : "."), x, y, w, AtelierKit.WARN, limit - y);
			case CLEAR -> hd.paragraph(HdStyle.TEXTE, "Vider le monde de « " + str(g, "nom") + " » ? Il est remis à neuf, plat et vide, sans "
				+ "carte" + (retouched(g) ? " ; la carte retouchée est d'abord sauvegardée dans la bibliothèque." : "."), x, y, w,
				AtelierKit.WARN, limit - y);
			case MAP, SAVE_AS -> drawMap(hd, g, x, y, w, limit);
			case TOUCH -> {
				boolean building = ClientState.bool(StructuresPage.editor(), "construction");
				AtelierKit.note(hd, building ? "Mode construction : vous bâtissez dans le monde de la partie. Les repères se posent là où vous "
					+ "vous tenez." : "Entrez en construction pour bâtir ; les repères se posent là où vous vous tenez.", x, y, w, limit - y);
			}
			default -> drawDetails(hd, g, x, y, w, limit);
		}
	}

	private static boolean retouched(JsonObject g) {
		JsonObject cur = current(g);
		return cur != null && ClientState.bool(cur, "modifiee");
	}

	/** À gauche : la carte vue de dessus, le ciel de sa phase en bandeau ; dessous, d'où vient l'image. */
	private void drawPicture(Hd hd, JsonObject g) {
		int s = hd.px(side());
		int x = hd.px(left());
		int y = hd.px(top() + 2);
		hd.fill(x - hd.u(2), y - hd.u(2), x + s + hd.u(2), y + s + hd.u(2), 0xFF4A2E1C);
		hd.frame(x - hd.u(2), y - hd.u(2), s + hd.u(4), s + hd.u(4), Math.max(1, hd.u(0.5)), 0xFFD9B45A);
		PartiesPage.picture(hd, g, x, y, s, s);
		HdStyle st = HdStyle.PETIT.size(8.5f);
		String legend = busy(g) ? "Pose en cours…" : current(g) != null ? "La carte en place, vue de dessus" : "Pas de carte dans ce monde";
		hd.text(st, hd.ellipsize(st, legend, s), x, y + s + hd.u(4), Hd.INK_SOFT);
	}

	private void drawDetails(Hd hd, JsonObject g, int x, int y, int w, int limit) {
		JsonObject c = PartiesPage.card(g);
		List<String[]> rows = new ArrayList<>();
		if (c != null) {
			rows.add(new String[] {"Phase", "ouverte".equals(str(c, "statut")) ? "salon, en attente des joueurs" : str(c, "phaseTexte")});
			rows.add(new String[] {"Joueurs", str(c, "joueursTexte")});
			String st = str(c, "conteur");
			rows.add(new String[] {"Conteur", st.isEmpty() ? "automatique" : st});
			int present = ClientState.num(c, "surPlace", 0);
			int watchers = ClientState.num(c, "spectateurs", 0);
			rows.add(new String[] {"Sur place", (present == 0 ? "personne" : AtelierKit.plural(present, "joueur", "joueurs"))
				+ (watchers > 0 ? ", " + AtelierKit.plural(watchers, "spectateur", "spectateurs") : "")});
		}
		rows.add(new String[] {"Carte", PartiesPage.mapLine(g)});
		rows.add(new String[] {"Scénario", str(g, "scenario").isEmpty() ? "aucun" : "« " + str(g, "scenario") + " »"});
		y = AtelierKit.details(hd, rows, x, y, w, limit) + hd.u(3);
		if ("suspendue".equals(PartiesPage.status(g))) {
			AtelierKit.note(hd, "En suspens : elle reprendra où elle en est, et ses joueurs seront rappelés.", x, y, w, limit - y);
		} else if (ClientState.bool(g, "visee") && !ClientState.bool(g, "ici")) {
			AtelierKit.note(hd, "C'est la partie que visent vos commandes de carte (/vc carte) : votre table d'attache.", x, y, w, limit - y);
		}
	}

	/** La carte en place : d'où elle vient, ses repères, et la liste de contrôle. */
	private void drawMap(Hd hd, JsonObject g, int x, int y, int w, int limit) {
		JsonObject cur = current(g);
		if (cur == null) {
			hd.paragraph(HdStyle.TEXTE, "Ce monde n'a pas de carte. « " + BLANK + " » y pose une place où bâtir ; Atelier › Cartes y envoie une "
				+ "carte de la bibliothèque.", x, y, w, Hd.INK_SOFT, limit - y);
			return;
		}
		List<String[]> rows = new ArrayList<>();
		String nom = str(cur, "nom");
		rows.add(new String[] {"Carte", nom.isEmpty() ? (ClientState.bool(cur, "genere") ? "village généré (" + str(cur, "theme") + ")"
			: "faite à la main") + ", pas dans la bibliothèque" : "« " + nom + " »" + (ClientState.bool(cur, "modifiee") ? ", retouchée depuis" : "")});
		rows.add(new String[] {"Repères", AtelierKit.plural(ClientState.num(cur, "sieges", 0), "trône", "trônes") + ", "
			+ AtelierKit.plural(ClientState.num(cur, "maisons", 0), "maison", "maisons") + (ClientState.bool(cur, "salle") ? ", salle" : "")});
		y = AtelierKit.details(hd, rows, x, y, w, limit) + hd.u(3);
		if (mode == Mode.SAVE_AS) {
			hd.text(HdStyle.FORT, "Nom dans la bibliothèque", x, hd.px(row(1)) - hd.lineHeight(HdStyle.FORT) - hd.u(1), Hd.INK);
			return;
		}
		drawCheckLines(hd, cur, x, y, w, limit);
	}

	/** À gauche, en retouche : la liste de contrôle de la carte dans un creux de parchemin, puis le conseil de la main. */
	private void drawChecks(Hd hd, JsonObject g) {
		int x = hd.px(left());
		int y = hd.px(top() + 2);
		int w = hd.px(side());
		int h = hd.px(bottom() - 4) - y;
		hd.inset(x, y, w, h);
		JsonObject cur = current(g);
		if (cur == null) {
			return;
		}
		int pad = hd.u(5);
		int ix = x + pad;
		int iw = w - 2 * pad;
		int iy = y + pad;
		int limit = y + h - pad;
		HdStyle ts = HdStyle.SECTION.size(10);
		hd.text(ts, hd.ellipsize(ts, "Liste de contrôle", iw), ix, iy, Hd.WINE);
		iy += hd.lineHeight(ts) + hd.u(3);
		iy = drawCheckLines(hd, cur, ix, iy, iw, limit);
		if (iy + hd.lineHeight(HdStyle.PETIT) * 2 <= limit) {
			AtelierKit.note(hd, "Avec la main, clic droit sur un trône, une maison, la salle ou une structure posée : la déplacer, la "
				+ "tourner ou la retirer.", ix, iy + hd.u(3), iw, limit - iy - hd.u(3));
		}
	}

	/** Les lignes de la liste de contrôle : une coche verte, ou un triangle rouge. */
	private static int drawCheckLines(Hd hd, JsonObject cur, int x, int y, int w, int limit) {
		for (JsonObject l : list(cur, "controle")) {
			if (y + hd.lineHeight(HdStyle.PETIT) > limit) {
				break;
			}
			boolean ok = ClientState.bool(l, "ok");
			int s = hd.u(6);
			int ty = y + (hd.lineHeight(HdStyle.PETIT) - s) / 2;
			if (ok) {
				HdGlyphs.check(hd, x, ty, s, AtelierArt.GOOD);
			} else {
				HdGlyphs.warning(hd, x, ty, s, AtelierArt.BAD);
			}
			y += hd.paragraph(HdStyle.PETIT, str(l, "texte"), x + s + hd.u(4), y, w - s - hd.u(4), ok ? Hd.INK_SOFT : AtelierKit.WARN, limit - y);
		}
		return y;
	}
}
