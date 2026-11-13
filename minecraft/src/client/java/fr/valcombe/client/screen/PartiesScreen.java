package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdList;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTextField;

import java.util.List;

/**
 * Menu J (au hub), « Parties », et le tableau des parties du hub cliqué : toutes les parties du serveur, chacune avec
 * sa vignette peinte (le ciel de sa phase, son village, ses places), son état et sa fiche.
 * <ul>
 * <li>« Rejoindre » s'inscrit au salon d'une partie ouverte (ou ramène à sa place qui l'a déjà), et y emmène.</li>
 * <li>« Regarder » emmène dans son monde en spectateur : on va où l'on veut, sans voir les rôles ni entendre les
 * joueurs ; on en sort par le menu J, « Arrêter de regarder ».</li>
 * <li>Qui mène la partie (son Conteur, un opérateur) la met en suspens ou la reprend ; un opérateur crée des parties
 * d'essai et les supprime (avec confirmation).</li>
 * </ul>
 * Tout vient du serveur (vue {@code parties} : {@code liste}, {@code operateur}) ; chaque bouton est une commande
 * {@code /vc partie ...}, vérifiée par le serveur comme si elle était tapée.
 */
public class PartiesScreen extends HdScreen {
	/** La partie choisie, gardée d'une reconstruction (nouvelle vue du serveur) à l'autre. */
	private static String chosen;
	/** La partie dont la suppression attend d'être confirmée (sur la fiche choisie seulement). */
	private static String confirmDelete;
	/** Le nom de la partie d'essai demandée : choisie dès qu'elle paraît dans la liste. */
	private static String pendingCreate;
	private static final String NAME_KEY = "parties.nom";

	private static final int LIST_W = 188;
	private static final int ROW_H = 38;

	private HdList<JsonObject> list;

	public PartiesScreen() {
		super("Parties");
	}

	/** Ouvre l'écran sur cette partie (fiche cliquée sur le tableau du hub, tests). */
	public PartiesScreen(String select) {
		this();
		chosen = select;
		confirmDelete = null;
	}

	@Override
	protected int[] panelSize() {
		return new int[] {404, 226};
	}

	private static JsonObject board() {
		return ClientState.obj(view(), "parties");
	}

	/** Les parties reçues du serveur, la principale d'abord. */
	public static List<JsonObject> games() {
		JsonObject b = board();
		return b == null ? List.of() : objects(b, "liste");
	}

	/** La partie choisie dans l'écran (pour les tests en jeu). */
	public static String chosenId() {
		return chosen;
	}

	private static boolean operator() {
		return ClientState.bool(board(), "operateur");
	}

	private JsonObject selected() {
		return list == null ? null : list.selected();
	}

	// ------------------------------------------------------------------
	// Couleurs et mots
	// ------------------------------------------------------------------

	/** La couleur de l'état, sur le parchemin. */
	static int statusColor(String statut) {
		return switch (statut == null ? "" : statut) {
			case "ouverte" -> 0xFF2F6B2A;
			case "en-cours" -> Hd.WINE;
			case "en-pause" -> 0xFF8A5A0E;
			case "suspendue" -> 0xFF3F5866;
			default -> Hd.INK_SOFT;
		};
	}

	/** « Ouverte · salon », « En cours · Nuit 2 ». */
	static String statusLine(JsonObject g) {
		String statut = ClientState.str(g, "statut");
		String phase = "ouverte".equals(statut) ? "salon" : ClientState.str(g, "phaseTexte");
		return ClientState.str(g, "statutTexte") + " · " + phase;
	}

	/** Ce que ce joueur est à cette table, en clair, ou null. */
	private static String seatText(String place) {
		return switch (place == null ? "" : place) {
			case "joueur" -> "Tu y joues.";
			case "conteur" -> "Tu la contes.";
			case "inscrit" -> "Tu y es inscrit.";
			case "regarde" -> "Tu la regardes.";
			case "spectateur" -> "Tu y es spectateur.";
			default -> null;
		};
	}

	// ------------------------------------------------------------------
	// La vignette : le ciel de la partie, son village, ses places
	// ------------------------------------------------------------------

	/** Un nombre tiré de l'identifiant de la partie : chaque village a sa silhouette, toujours la même. */
	private static int seed(String id, int salt) {
		int h = (id == null ? 0 : id.hashCode()) * 31 + salt * 0x9E3779B1;
		h ^= h >>> 15;
		h *= 0x2C1B3C6D;
		h ^= h >>> 12;
		return h & 0x7FFFFFFF;
	}

	/**
	 * Peint la vignette d'une partie dans ce rectangle (pixels réels) : le ciel de sa phase (crépuscule du salon, soleil
	 * du jour, lune et étoiles de la nuit), la silhouette de son village avec son clocher, les fenêtres allumées la nuit ;
	 * voilée et marquée du signe pause quand elle est en pause ou en suspens ; « ESSAI » dans un coin pour une partie
	 * d'essai, un liseré d'or pour la partie principale.
	 */
	public static void vignette(Hd hd, JsonObject g, int x, int y, int w, int h) {
		String statut = ClientState.str(g, "statut");
		String phase = ClientState.str(g, "phase");
		String id = ClientState.str(g, "id");
		boolean night = "nuit".equals(phase);
		boolean day = "jour".equals(phase);
		boolean open = "ouverte".equals(statut);
		int top;
		int bottom;
		if (night) {
			top = 0xFF0B1230;
			bottom = 0xFF2C3D73;
		} else if (day) {
			top = 0xFF4F8FD0;
			bottom = 0xFFEAD7A2;
		} else if (open) {
			top = 0xFF323A66;
			bottom = 0xFFD9894B;
		} else {
			top = 0xFF3A2A58;
			bottom = 0xFF9A5E7E; // mise en place : l'heure violette
		}
		hd.gradient(x, y, x + w, y + h, top, bottom);
		int ground = y + h - Math.max(2, h * 22 / 100);
		// L'astre.
		if (night) {
			for (int i = 0; i < 9; i++) {
				int sx = x + seed(id, 40 + i) % Math.max(1, w);
				int sy = y + seed(id, 60 + i) % Math.max(1, (ground - y) * 2 / 3);
				int s = Math.max(1, hd.u(i % 3 == 0 ? 1 : 0.6));
				hd.fill(sx, sy, sx + s, sy + s, 0xCCFFF6D8);
			}
			int r = Math.max(2, h / 6);
			int mx = x + w * 3 / 4;
			int my = y + h / 4 + r / 2;
			hd.disc(mx, my, r + Math.max(1, r / 3), 0x30F3E8C0);
			hd.disc(mx, my, r, 0xFFF3E8C0);
			hd.disc(mx + r * 2 / 5, my - r / 4, r * 4 / 5, top); // le croissant
		} else if (day) {
			int r = Math.max(2, h / 6);
			int sx = x + w * 3 / 4;
			int sy = y + h / 4 + r / 2;
			hd.disc(sx, sy, r * 2, 0x30FFF1B0);
			hd.disc(sx, sy, r, 0xFFFFE89A);
		} else if (open) {
			int r = Math.max(3, h / 4);
			hd.disc(x + w * 2 / 3, ground, r, 0xFFFFB45E); // le soleil se couche derrière le village
		}
		// Le village : sol, maisons aux toits pointus, clocher au milieu.
		int soil = night ? 0xFF14121C : day ? 0xFF4A3B2A : 0xFF241A22;
		int wall = night ? 0xFF1E1B28 : day ? 0xFF5E4A36 : 0xFF2E2230;
		int roof = night ? 0xFF2A2334 : day ? 0xFF7A3A2A : 0xFF3B2A3A;
		hd.fill(x, ground, x + w, y + h, soil);
		int houses = Math.max(3, w / Math.max(1, hd.u(9)));
		int step = w / houses;
		for (int i = 0; i < houses; i++) {
			int hw = step * 3 / 4;
			int hx = x + i * step + (step - hw) / 2;
			int hh = Math.max(2, (h / 5) + seed(id, i) % Math.max(1, h / 7));
			boolean tower = i == houses / 2;
			if (tower) {
				int tw = Math.max(2, hw * 2 / 3);
				int tx = hx + (hw - tw) / 2;
				int th = hh + h / 4;
				hd.fill(tx, ground - th, tx + tw, ground, wall);
				hd.triangle(tx + tw / 2, ground - th - h / 5, tw + 2, h / 5, roof);
				if (night) {
					hd.fill(tx + tw / 2 - 1, ground - th + hd.u(2), tx + tw / 2 + 1, ground - th + hd.u(4), 0xFFFFD27A);
				}
				continue;
			}
			hd.fill(hx, ground - hh, hx + hw, ground, wall);
			hd.triangle(hx + hw / 2, ground - hh - hh * 2 / 3, hw + 2, hh * 2 / 3, roof);
			if (night && seed(id, 20 + i) % 3 != 0) {
				int wx = hx + hw / 3;
				int ws = Math.max(1, hd.u(1.2));
				hd.fill(wx, ground - hh / 2 - ws, wx + ws, ground - hh / 2, 0xFFFFD27A);
			}
		}
		// Les places : une pastille par place, dorée si quelqu'un l'occupe.
		int places = ClientState.num(g, "places", 0);
		int taken = Math.min(places, ClientState.num(g, "joueurs", 0));
		if (places > 0) {
			int r = Math.max(1, Math.min(hd.u(1.6), w / (places * 3)));
			int gap = Math.max(r * 2 + 1, Math.min(hd.u(5), (w - hd.u(6)) / places));
			int sx = x + (w - gap * (places - 1)) / 2;
			int sy = y + h - Math.max(r + 1, (y + h - ground) / 2);
			for (int i = 0; i < places; i++) {
				hd.disc(sx + i * gap, sy, r, i < taken ? 0xFFF2C14E : 0x806E6050);
			}
		}
		// Pause et suspens : la scène voilée, et le signe pause.
		if ("en-pause".equals(statut) || "suspendue".equals(statut)) {
			hd.fill(x, y, x + w, y + h, "suspendue".equals(statut) ? 0x9A2A3540 : 0x70301E10);
			int bh = h / 3;
			int bw = Math.max(2, h / 10);
			int cx = x + w / 2;
			int cy = y + (ground - y) / 2;
			hd.fill(cx - bw * 2, cy - bh / 2, cx - bw, cy + bh / 2, 0xE0F2E6C9);
			hd.fill(cx + bw, cy - bh / 2, cx + bw * 2, cy + bh / 2, 0xE0F2E6C9);
		}
		if (ClientState.bool(g, "principale")) {
			hd.fill(x, y, x + w, y + Math.max(1, hd.u(1)), 0xFFD9B45A);
		}
		hd.frame(x, y, w, h, Math.max(1, hd.u(0.5)), 0xFF2A1E14);
		if ("essai".equals(ClientState.str(g, "sorte")) && h >= hd.u(20)) {
			String tag = "ESSAI";
			int tw = hd.width(HdStyle.PETIT.size(7), tag) + hd.u(4);
			int th = hd.lineHeight(HdStyle.PETIT.size(7));
			hd.fill(x + w - tw - hd.u(1), y + hd.u(1), x + w - hd.u(1), y + hd.u(1) + th, 0xD0301E2A);
			hd.textCentered(HdStyle.PETIT.size(7), tag, x + w - hd.u(1) - tw / 2, y + hd.u(1), 0xFFE9D6A8);
		}
	}

	// ------------------------------------------------------------------
	// Gestes
	// ------------------------------------------------------------------

	private void act(String verb, JsonObject g, boolean close) {
		if (g == null) {
			return;
		}
		send("vc partie " + verb + " " + ClientState.str(g, "id"));
		if (close) {
			onClose();
		}
	}

	private void create(HdTextField field) {
		String name = field.value().strip();
		if (name.isEmpty()) {
			return;
		}
		pendingCreate = name;
		HdTextField.forget(NAME_KEY);
		send("vc partie creer " + name);
		rebuildSoon();
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	@Override
	protected void build() {
		List<JsonObject> all = games();
		if (pendingCreate != null) {
			for (JsonObject g : all) {
				if (pendingCreate.equals(ClientState.str(g, "nom"))) {
					chosen = ClientState.str(g, "id");
					pendingCreate = null;
				}
			}
		}
		list = addRenderableWidget(new HdList<JsonObject>(px + 10, py + 14, LIST_W, ph - 46, ROW_H,
			g -> ClientState.str(g, "nom") + " (" + ClientState.str(g, "statutTexte") + ")")
			.items(all).key("parties.liste").empty("Aucune partie sur ce serveur")
			.onSelect(g -> {
				String id = ClientState.str(g, "id");
				if (!id.equals(chosen)) {
					confirmDelete = null;
				}
				chosen = id;
				rebuildSoon();
			}));
		list.row((hd, g, index, x, y, w, h, hovered, sel) -> {
			if (sel) {
				hd.fill(x, y, x + w, y + h, Hd.SELECTION);
			} else if (hovered) {
				hd.fill(x, y, x + w, y + h, Hd.HOVER);
			}
			int vh = h - hd.u(6);
			int vw = vh * 16 / 10;
			vignette(hd, g, x + hd.u(3), y + hd.u(3), vw, vh);
			int tx = x + vw + hd.u(8);
			int tw = w - (tx - x) - hd.u(4);
			String place = ClientState.str(g, "place");
			String badge = "joueur".equals(place) ? "ta partie" : "conteur".equals(place) ? "Conteur"
				: "inscrit".equals(place) ? "inscrit" : null;
			int bw = badge == null ? 0 : hd.width(HdStyle.PETIT, badge) + hd.u(5);
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, ClientState.str(g, "nom"), tw - bw), tx, y + hd.u(3), sel ? Hd.LIGHT : Hd.INK);
			if (badge != null) {
				hd.textRight(HdStyle.PETIT, badge, x + w - hd.u(4), y + hd.u(5), sel ? Hd.GOLD : Hd.BRONZE);
			}
			int sc = sel ? Hd.GOLD : statusColor(ClientState.str(g, "statut"));
			hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, "● " + statusLine(g), tw), tx, y + hd.u(16), sc);
			hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, ClientState.str(g, "joueursTexte"), tw), tx, y + hd.u(26),
				sel ? Hd.MUTED : Hd.INK_SOFT);
		});
		int pick = -1;
		for (int i = 0; i < all.size(); i++) {
			if (ClientState.str(all.get(i), "id").equals(chosen)) {
				pick = i;
			}
		}
		list.select(pick < 0 && !all.isEmpty() ? 0 : pick);

		int rx = px + LIST_W + 20;
		int rw = pw - LIST_W - 30;
		JsonObject g = selected();
		if (g != null) {
			int half = (rw - 6) / 2;
			int rowA = py + ph - 74;
			int rowB = py + ph - 52;
			// Rejoindre et regarder.
			HdButton join = button(rx, rowA, half, ClientState.str(g, "rejoindreTexte"), () -> act("rejoindre", selected(), true));
			if (!ClientState.bool(g, "rejoindre")) {
				join.disabled().tooltip(ClientState.str(g, "rejoindreRaison"));
			} else {
				join.tooltip("joueur".equals(ClientState.str(g, "place")) || "conteur".equals(ClientState.str(g, "place"))
					|| "inscrit".equals(ClientState.str(g, "place")) ? "Retrouver ta place dans cette partie."
					: "T'inscrire au salon de cette partie, et y aller.");
			}
			HdButton watch = button(rx + half + 6, rowA, half, "Regarder", () -> act("regarder", selected(), true));
			if (!ClientState.bool(g, "regarder")) {
				watch.disabled().tooltip(ClientState.str(g, "regarderRaison"));
			} else {
				watch.tooltip("En spectateur : tu vas où tu veux, sans voir les rôles ni entendre les joueurs.");
			}
			// Mener la partie : suspendre, reprendre ; supprimer une partie d'essai (opérateurs).
			String statut = ClientState.str(g, "statut");
			String id = ClientState.str(g, "id");
			boolean lead = ClientState.bool(g, "mener");
			boolean canDelete = operator() && !ClientState.bool(g, "principale");
			if (id.equals(confirmDelete) && canDelete) {
				button(rx, rowB, half, "Confirmer", () -> {
					confirmDelete = null;
					act("supprimer", selected(), false);
				}).tooltip("Supprimer « " + ClientState.str(g, "nom") + " » et son monde. Ses joueurs vont au hub. On ne revient pas en arrière.");
				button(rx + half + 6, rowB, half, "Annuler", () -> {
					confirmDelete = null;
					rebuildSoon();
				});
			} else if (lead || canDelete) {
				int w = lead && canDelete ? half : rw;
				int x = rx;
				if (lead) {
					boolean suspended = "suspendue".equals(statut);
					HdButton b = button(x, rowB, w, suspended ? "Reprendre" : "Suspendre",
						() -> act(suspended ? "reprendre" : "suspendre", selected(), false));
					boolean running = "en-cours".equals(statut) || "en-pause".equals(statut);
					if (!suspended && !running) {
						b.disabled().tooltip("Seule une partie lancée se met en suspens.");
					} else {
						b.tooltip(suspended ? "Ses joueurs sont rappelés et retrouvent leur place ; elle repart quand tous sont là."
							: "Horloge arrêtée, joueurs au hub : elle reprendra où elle en est, même après un redémarrage.");
					}
					x += w + 6;
				}
				if (canDelete) {
					HdButton del = button(x, rowB, w, "Supprimer", () -> {
						confirmDelete = ClientState.str(selected(), "id");
						rebuildSoon();
					});
					if (!"ouverte".equals(statut)) {
						del.disabled().tooltip("Une partie lancée ne se supprime pas, même en suspens : arrêtez-la d'abord (/vc arreter, dans son monde).");
					} else {
						del.tooltip("Supprimer cette partie d'essai et son monde (une confirmation suit).");
					}
				}
			}
		}
		// Bas de l'écran : retour, et pour un opérateur, la création d'une partie d'essai.
		int by = py + ph - 26;
		button(px + 10, by, 64, "Retour", () -> minecraft.gui.setScreen(new MainScreen()));
		if (operator()) {
			int fw = pw - 10 - 64 - 6 - 6 - 132 - 10;
			HdTextField field = addRenderableWidget(new HdTextField(px + 80, by, fw, 18, "Nom de la partie d'essai")
				.hint("Nom de la nouvelle partie d'essai").maxLength(40).key(NAME_KEY));
			field.onEnter(() -> create(field));
			button(px + 80 + fw + 6, by, 132, "Créer une partie d'essai", () -> create(field))
				.tooltip("Un monde plat neuf, où le village de Valcombe se pose par petits lots.");
		}
	}

	@Override
	protected void drawHd(Hd hd) {
		int rx = hd.px(px + LIST_W + 20);
		int rw = hd.px(pw - LIST_W - 30);
		JsonObject g = selected();
		if (g == null) {
			hd.paragraph(HdStyle.TEXTE, games().isEmpty() ? "Aucune partie sur ce serveur pour l'instant."
				: "Choisis une partie dans la liste.", rx, hd.px(py + 16), rw, Hd.INK_SOFT);
			return;
		}
		// La vignette en grand, en bandeau.
		int vh = hd.u(50);
		vignette(hd, g, rx, hd.px(py + 14), rw, vh);
		int y = hd.px(py + 14) + vh + hd.u(4);
		hd.text(HdStyle.SECTION, hd.ellipsize(HdStyle.SECTION, ClientState.str(g, "nom"), rw), rx, y, Hd.WINE);
		y += hd.lineHeight(HdStyle.SECTION);
		String kind = ClientState.bool(g, "principale") ? "Partie principale" : "essai".equals(ClientState.str(g, "sorte"))
			? "Partie d'essai" : "Partie";
		String statusText = "● " + statusLine(g);
		hd.text(HdStyle.FORT, statusText, rx, y, statusColor(ClientState.str(g, "statut")));
		hd.textRight(HdStyle.PETIT, kind, rx + rw, y + hd.u(1), Hd.INK_SOFT);
		y += hd.lineHeight(HdStyle.FORT);
		hd.separator(rx, y + hd.u(1), rw);
		y += hd.u(5);
		int bottom = hd.px(py + ph - 78);
		// La fiche : joueurs et places, Conteur, monde, spectateurs ; puis ce que ce joueur y est, ou pourquoi il ne peut rien.
		StringBuilder lines = new StringBuilder();
		lines.append(ClientState.str(g, "joueursTexte")).append('.');
		String st = ClientState.str(g, "conteur");
		lines.append(' ').append(st == null || st.isEmpty() ? "Conteur automatique." : "Conteur : " + st + ".");
		int present = ClientState.num(g, "surPlace", 0);
		int watchers = ClientState.num(g, "spectateurs", 0);
		lines.append(' ').append(present == 0 ? "Personne dans son monde." : present == 1 ? "1 joueur dans son monde." : present + " joueurs dans son monde.");
		if (watchers > 0) {
			lines.append(' ').append(watchers == 1 ? "1 spectateur la regarde." : watchers + " spectateurs la regardent.");
		}
		y += hd.paragraph(HdStyle.TEXTE, lines.toString(), rx, y, rw, Hd.INK, bottom - y);
		String mine = seatText(ClientState.str(g, "place"));
		String note = mine;
		if ("suspendue".equals(ClientState.str(g, "statut"))) {
			note = (mine == null ? "" : mine + " ") + "En suspens : elle reprendra où elle en est, et ses joueurs seront rappelés.";
		} else if (!ClientState.bool(g, "rejoindre") && !ClientState.bool(g, "regarder")) {
			note = ClientState.str(g, "rejoindreRaison");
		}
		if (note != null && bottom - y > hd.u(10)) {
			hd.paragraph(HdStyle.TEXTE, note, rx, y + hd.u(3), rw, Hd.BRONZE, bottom - y - hd.u(3));
		}
	}
}
