package fr.valcombe.client.screen.atelier;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.Vignettes;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdGrid;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTextField;
import fr.valcombe.client.screen.PartiesScreen;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.util.Util;

/**
 * Atelier › Parties : les parties du serveur (la partie principale, puis les parties d'essai), chacune avec la carte
 * qu'elle porte vue de dessus, son état (ouverte, en cours, en pause, en suspens), ses joueurs et son scénario. Un clic
 * ouvre sa fiche ({@link PartySheetPage}) : y aller, lui envoyer une carte, la suspendre, la reprendre, la supprimer,
 * enregistrer sa carte, la retoucher sur place.
 * <p>
 * En haut : « Créer une partie d'essai » (le nom s'écrit sur place, la nouvelle partie est mise en avant) et « Rejoindre
 * ou regarder… », qui mène à l'écran Parties du menu J plutôt que de le refaire ici.
 */
public class PartiesPage extends AtelierPage {
	public static final String CREATE = "Créer une partie d'essai";
	public static final String CREATE_CONFIRM = "Créer";
	public static final String JOIN = "Rejoindre ou regarder…";
	private static final String NAME_KEY = "atelier.parties.nom";

	/** Le nom de la partie d'essai s'écrit en haut de la page. */
	private static boolean creating;
	private static Set<String> before;
	private static String flash;
	private static long flashUntil;

	private HdGrid<JsonObject> grid;

	static List<Crumb> crumbs() {
		return List.of(new Crumb("Atelier", AtelierHome::new), new Crumb(AtelierHome.PARTIES, PartiesPage::new));
	}

	@Override
	protected List<Crumb> trail() {
		return List.of(new Crumb("Atelier", AtelierHome::new), new Crumb(AtelierHome.PARTIES, null));
	}

	@Override
	protected boolean cancelPending() {
		boolean was = creating;
		creating = false;
		HdTextField.forget(NAME_KEY);
		return was;
	}

	/** Les parties du serveur, la principale d'abord. */
	public static List<JsonObject> games() {
		return list(data(), "parties");
	}

	/** Une partie par son identifiant (celui de son monde), ou null. */
	static JsonObject game(String id) {
		for (JsonObject g : games()) {
			if (str(g, "id").equals(id)) {
				return g;
			}
		}
		return null;
	}

	/** La galerie (pour les tests en jeu). */
	public HdGrid<JsonObject> grid() {
		return grid;
	}

	// ------------------------------------------------------------------
	// Ce qu'on dit d'une partie
	// ------------------------------------------------------------------

	static JsonObject card(JsonObject g) {
		return ClientState.obj(g, "fiche");
	}

	static String status(JsonObject g) {
		return str(card(g), "statut");
	}

	/** « En cours · Nuit 2 », « Ouverte · salon ». */
	static String statusLine(JsonObject g) {
		JsonObject c = card(g);
		if (c == null) {
			return "Monde de partie";
		}
		return str(c, "statutTexte") + " · " + ("ouverte".equals(str(c, "statut")) ? "salon" : str(c, "phaseTexte"));
	}

	/** « Partie principale », « Partie d'essai ». */
	static String kind(JsonObject g) {
		return ClientState.bool(g, "principale") ? "Partie principale" : "essai".equals(str(card(g), "sorte")) ? "Partie d'essai" : "Partie";
	}

	/** La carte qu'elle porte, en clair. */
	static String mapLine(JsonObject g) {
		if (!str(g, "envoi").isEmpty()) {
			return "Reçoit « " + str(g, "envoi") + " »";
		}
		JsonObject cur = ClientState.obj(g, "actuelle");
		String nom = str(g, "carteNom");
		if (nom.isEmpty() && cur != null) {
			nom = str(cur, "nom");
		}
		if (!nom.isEmpty()) {
			return "Carte « " + nom + " »" + (cur != null && ClientState.bool(cur, "modifiee") ? ", retouchée" : "");
		}
		if (cur != null) {
			return ClientState.bool(cur, "genere") ? "Village généré, pas dans la bibliothèque" : "Carte pas encore enregistrée";
		}
		return "Sans carte";
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	@Override
	protected void buildPage() {
		List<JsonObject> all = games();
		int x = left();
		int w = right() - left();
		int y = top() + 2;
		int jw = Math.min(150, w / 3);
		HdButton join = button(right() - jw, top(), jw, JOIN, () -> go(new PartiesScreen()));
		if (ClientState.obj(view(), "parties") == null) {
			join.disabled().tooltip("Depuis le monde d'une partie, on ne voit qu'elle : au hub ou dans un atelier, cet écran montre toutes les parties.");
		} else {
			join.tooltip("L'écran Parties du menu J : s'inscrire au salon d'une partie, ou la regarder en spectateur.");
		}
		if (creating) {
			int cw = 70;
			int aw = 70;
			int fw = w - jw - 8 - cw - aw - 12;
			HdTextField field = addRenderableWidget(new HdTextField(x, top(), fw, 18, "Nom de la partie d'essai")
				.hint("Nom de la partie d'essai…").maxLength(40).key(NAME_KEY));
			Runnable create = () -> {
				String name = field.value().strip();
				if (name.isEmpty()) {
					return;
				}
				before = new HashSet<>();
				for (JsonObject g : games()) {
					before.add(str(g, "id"));
				}
				creating = false;
				HdTextField.forget(NAME_KEY);
				run("vc partie creer " + name, "Création de « " + name + " »");
				rebuildSoon();
			};
			field.onEnter(create);
			field.responder(s -> rebuildSoon());
			HdButton ok = button(x + fw + 6, top(), cw, CREATE_CONFIRM, create);
			if (field.value().strip().isEmpty()) {
				ok.disabled().tooltip("Il manque un nom (40 caractères au plus).");
			} else {
				ok.primary().tooltip("Un monde plat neuf, où le village de Valcombe se pose par petits lots.");
			}
			button(x + fw + 6 + cw + 6, top(), aw, "Annuler", () -> {
				creating = false;
				HdTextField.forget(NAME_KEY);
				rebuildSoon();
			});
			setInitialFocus(field);
		} else {
			HdButton create = button(x, top(), Math.min(170, w - jw - 8), CREATE, () -> {
				creating = true;
				rebuildSoon();
			});
			create.tooltip("Une partie à part, dans son monde, pour essayer une carte ou un scénario sans toucher à la partie principale.");
			if (all.size() <= 1) {
				create.primary();
			}
		}
		int gy = y + 22;
		int gh = bottom() - 4 - gy;
		grid = addRenderableWidget(new HdGrid<JsonObject>(x, gy, w, gh, 196, 68, 6, g -> str(g, "nom"))
			.items(all).key("atelier.parties.galerie").openOnClick()
			.empty("Aucun monde de partie sur ce serveur : les mondes de Valcombe manquent (voir son journal).")
			.onActivate(g -> go(PartySheetPage.visit(str(g, "id")))));
		grid.cell(PartiesPage::cell);
		if (before != null) {
			for (int i = 0; i < all.size(); i++) {
				String id = str(all.get(i), "id");
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

	/** Une case : la carte vue de dessus à gauche (ou la scène peinte de la partie), son nom, son état, sa carte. */
	private static void cell(Hd hd, JsonObject g, int index, int x, int y, int w, int h, boolean hovered, boolean selected) {
		AtelierKit.cardBack(hd, x, y, w, h, hovered);
		if (str(g, "id").equals(flash) && Util.getMillis() < flashUntil) {
			double t = (flashUntil - Util.getMillis()) / 4000.0;
			int alpha = (int) Math.round(0x90 * Math.max(0, Math.min(1, t)) * (0.6 + 0.4 * Math.sin(Util.getMillis() / 150.0)));
			hd.fill(x, y, x + w, y + h, alpha << 24 | 0xF2C14E);
		}
		int pad = hd.u(3);
		int side = h - 2 * pad;
		picture(hd, g, x + pad, y + pad, side, side);
		int tx = x + pad + side + hd.u(5);
		int tw = x + w - pad - tx;
		int ty = y + pad;
		HdStyle bs = HdStyle.PETIT.size(8);
		String badge = ClientState.bool(g, "principale") ? "Principale" : "Essai";
		int bw = AtelierArt.chipWidth(hd, bs, badge);
		hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(g, "nom"), tw - bw - hd.u(3)), tx, ty, hovered ? Hd.WINE : Hd.INK);
		AtelierArt.chip(hd, bs, badge, x + w - pad - bw, ty + hd.u(1), ClientState.bool(g, "principale") ? 0xE08A6A1E : 0xE03F5866);
		ty += hd.lineHeight(HdStyle.FORT);
		hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, "● " + statusLine(g), tw), tx, ty, PartiesScreen.statusColor(status(g)));
		ty += hd.lineHeight(HdStyle.PETIT);
		hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, mapLine(g), tw), tx, ty, Hd.INK_SOFT);
		ty += hd.lineHeight(HdStyle.PETIT);
		String who = str(card(g), "joueursTexte");
		String sc = str(g, "scenario");
		String line = (who.isEmpty() ? "" : who) + (sc.isEmpty() ? "" : (who.isEmpty() ? "" : " · ") + "scénario « " + sc + " »");
		if (ClientState.bool(g, "ici")) {
			line = "Vous y êtes" + (line.isEmpty() ? "" : " · " + line);
		}
		if (ty + hd.lineHeight(HdStyle.PETIT) <= y + h - pad + hd.u(1)) {
			hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, line, tw), tx, ty, Hd.INK_SOFT);
		}
	}

	/**
	 * L'image d'une partie : sa carte vue de dessus (faite par le serveur), avec, en bandeau, le ciel de sa phase ; tant
	 * qu'il n'y a pas de carte (ou pendant une pose), la scène peinte de l'écran Parties.
	 */
	static void picture(Hd hd, JsonObject g, int x, int y, int w, int h) {
		JsonObject c = card(g);
		boolean map = ClientState.obj(g, "actuelle") != null && !ClientState.bool(g, "chantier");
		if (map) {
			Vignettes.draw(hd, "partie:" + str(g, "id"), str(g, "vignette"), x, y, w, h);
			if (c != null) {
				int band = Math.max(hd.u(6), h / 6);
				PartiesScreen.vignette(hd, c, x, y + h - band, w, band);
			}
		} else if (c != null) {
			PartiesScreen.vignette(hd, c, x, y, w, h);
		} else {
			Vignettes.placeholder(hd, x, y, w, h, true);
		}
		if (ClientState.bool(g, "chantier") || !str(g, "envoi").isEmpty()) {
			HdStyle st = HdStyle.PETIT.size(8);
			if (w > AtelierArt.chipWidth(hd, st, "Pose") + hd.u(4)) {
				AtelierArt.ribbon(hd, "Pose", x + w - hd.u(2), y + hd.u(2), 0xE08A5A0E);
			}
		}
		hd.frame(x, y, w, h, Math.max(1, hd.u(0.5)), 0xFF2A1E14);
	}
}
