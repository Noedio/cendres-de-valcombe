package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdIcon;
import fr.valcombe.client.hd.HdList;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;

import java.util.List;

/**
 * Menu J, « Mondes » : les mondes où le joueur peut aller (le hub, le village actuel, les mondes de partie, et
 * les ateliers pour un opérateur ou un invité), avec le nombre de joueurs dans chacun. « Y aller » (ou un double-clic)
 * l'y emmène : il y retrouve sa dernière position. Un opérateur voit qui est invité à construire dans chaque atelier ;
 * un invité voit les ateliers où il construit.
 * <p>
 * La liste vient du serveur (vue {@code mondes} : {@code liste}, {@code ici}, {@code bloque}) ; une destination est
 * une ligne générique (identifiant, nom, sorte, joueurs), pour que le tableau des parties puisse s'y ajouter sans
 * tout refaire.
 */
public class WorldsScreen extends HdScreen {
	/** La destination choisie, gardée d'une reconstruction (nouvelle vue du serveur) à l'autre. */
	private static String chosen;
	private HdList<JsonObject> list;

	public WorldsScreen() {
		super("Mondes");
	}

	/** Ouvre le menu sur cette destination (pour le test en jeu et les raccourcis). */
	public WorldsScreen(String select) {
		this();
		chosen = select;
	}

	@Override
	protected int[] panelSize() {
		return new int[] {360, 210};
	}

	private static JsonObject worlds() {
		return ClientState.obj(view(), "mondes");
	}

	/** Les destinations reçues du serveur. */
	public static List<JsonObject> destinations() {
		JsonObject w = worlds();
		return w == null ? List.of() : objects(w, "liste");
	}

	/** Pourquoi le joueur ne peut pas partir (il joue la partie en cours), ou null. */
	private static String blocked() {
		JsonObject w = worlds();
		String b = w == null ? null : ClientState.str(w, "bloque");
		return b == null || b.isEmpty() ? null : b;
	}

	/** L'emblème d'une sorte de monde. */
	private static String emblem(String sorte) {
		return switch (sorte == null ? "" : sorte) {
			case "hub" -> "roles/e_lanterne";
			case "village" -> "roles/e_cloche";
			case "partie" -> "roles/e_couronne";
			case "essai" -> "roles/e_masque";
			case "atelier-carte" -> "roles/e_plume";
			case "atelier-structures" -> "roles/e_cle";
			default -> "roles/e_etoile";
		};
	}

	/** Ce qu'on trouve dans un monde de cette sorte. */
	private static String description(String sorte) {
		return switch (sorte == null ? "" : sorte) {
			case "hub" -> "L'accueil commun : dalles d'inscription, mur des rôles du prochain script et portes vers les mondes.";
			case "village" -> "Le village où se joue la partie en ce moment : salle d'attente, place et maisons.";
			case "partie" -> "Un monde de partie, avec sa propre carte.";
			case "essai" -> "Une partie d'essai, pour répéter une carte ou un scénario sans toucher à la vraie.";
			case "atelier-carte" -> "Un atelier de carte, pour les opérateurs et leurs invités : on y construit une carte de la "
				+ "bibliothèque, en mode créatif et avec WorldEdit.";
			case "atelier-structures" -> "L'atelier des structures, pour les opérateurs et leurs invités : on y retouche les "
				+ "structures du catalogue, avec WorldEdit.";
			default -> "";
		};
	}

	/** « personne », « 1 joueur », « 3 joueurs ». */
	private static String count(int n) {
		return n == 0 ? "personne" : n == 1 ? "1 joueur" : n + " joueurs";
	}

	/** « Alice », « Alice et Bob », « Alice, Bob et Chloé ». */
	private static String names(List<String> names) {
		if (names.size() <= 1) {
			return names.isEmpty() ? "" : names.get(0);
		}
		return String.join(", ", names.subList(0, names.size() - 1)) + " et " + names.get(names.size() - 1);
	}

	/** Les invités d'un atelier (vue d'un opérateur), ou null si la destination n'en dit rien. */
	private static List<String> guests(JsonObject d) {
		if (d == null || !d.has("invites") || !d.get("invites").isJsonArray()) {
			return null;
		}
		List<String> out = new java.util.ArrayList<>();
		d.getAsJsonArray("invites").forEach(e -> out.add(e.getAsString()));
		return out;
	}

	/** Ce que la ligne d'un monde dit sous son nom : sa sorte, ses joueurs, et pour un invité qu'il y construit. */
	private static String subtitle(JsonObject d) {
		String sub = ClientState.str(d, "label") + " · " + count(ClientState.num(d, "joueurs", 0));
		if (ClientState.bool(d, "invite")) {
			sub += " · invité à construire";
		}
		List<String> g = guests(d);
		if (g != null && !g.isEmpty()) {
			sub += " · " + (g.size() == 1 ? "1 invité" : g.size() + " invités");
		}
		return sub;
	}

	/** La destination choisie, ou null. */
	private JsonObject selected() {
		return list == null ? null : list.selected();
	}

	private void go(JsonObject d) {
		if (d == null || ClientState.bool(d, "ici") || blocked() != null) {
			return;
		}
		send("vc monde aller " + ClientState.str(d, "id"));
		onClose();
	}

	@Override
	protected void build() {
		List<JsonObject> all = destinations();
		int lw = 196;
		list = addRenderableWidget(new HdList<JsonObject>(px + 10, py + 14, lw, ph - 48, 26,
			d -> ClientState.str(d, "nom") + (ClientState.bool(d, "ici") ? " (tu es ici)" : ""))
			.items(all).key("mondes.liste").empty("Aucun monde")
			.onSelect(d -> {
				chosen = ClientState.str(d, "id");
				rebuildSoon();
			})
			.onActivate(this::go));
		list.row((hd, d, index, x, y, w, h, hovered, sel) -> {
			if (sel) {
				hd.fill(x, y, x + w, y + h, Hd.SELECTION);
			} else if (hovered) {
				hd.fill(x, y, x + w, y + h, Hd.HOVER);
			}
			int is = h - hd.u(8);
			hd.image(fr.valcombe.client.ui.Theme.tex(emblem(ClientState.str(d, "sorte"))), x + hd.u(4), y + (h - is) / 2, is, is, -1);
			int tx = x + is + hd.u(9);
			boolean here = ClientState.bool(d, "ici");
			String badge = here ? "tu es ici" : "";
			int bw = here ? hd.width(HdStyle.PETIT, badge) + hd.u(6) : 0;
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, ClientState.str(d, "nom"), w - (tx - x) - bw - hd.u(4)), tx, y + hd.u(2),
				sel ? Hd.LIGHT : Hd.INK);
			String sub = subtitle(d);
			hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, sub, w - (tx - x) - hd.u(4)), tx, y + hd.u(14), sel ? Hd.GOLD : Hd.INK_SOFT);
			if (here) {
				hd.textRight(HdStyle.PETIT, badge, x + w - hd.u(5), y + hd.u(3), sel ? Hd.GOLD : Hd.WINE);
			}
		});
		// La destination choisie, sinon la première où le joueur n'est pas.
		int pick = -1;
		for (int i = 0; i < all.size(); i++) {
			if (ClientState.str(all.get(i), "id").equals(chosen)) {
				pick = i;
			}
		}
		for (int i = 0; i < all.size() && pick < 0; i++) {
			if (!ClientState.bool(all.get(i), "ici")) {
				pick = i;
			}
		}
		list.select(pick);

		int rx = px + lw + 20;
		int rw = pw - lw - 30;
		JsonObject d = selected();
		HdButton goButton = button(rx, py + ph - 52, rw, "Y aller", () -> go(selected()));
		if (d == null || ClientState.bool(d, "ici") || blocked() != null) {
			goButton.disabled();
		}
		if (d != null) {
			goButton.tooltip(ClientState.bool(d, "ici") ? "Tu es déjà dans ce monde."
				: blocked() != null ? blocked() : "Tu y retrouves ta dernière position (la première fois : son point d'arrivée).");
			addRenderableOnly(new HdIcon(rx, py + 14, 22, HdIcon.gui(emblem(ClientState.str(d, "sorte"))),
				ClientState.str(d, "label"), null));
		}
		button(px + pw / 2 - 50, py + ph - 26, 100, "Retour", () -> minecraft.gui.setScreen(new MainScreen()));
	}

	@Override
	protected void drawHd(Hd hd) {
		int lw = 196;
		int rx = hd.px(px + lw + 20);
		int rw = hd.px(pw - lw - 30);
		JsonObject d = selected();
		if (d == null) {
			hd.paragraph(HdStyle.TEXTE, "Choisis un monde dans la liste.", rx, hd.px(py + 16), rw, Hd.INK_SOFT);
			return;
		}
		int tx = rx + hd.u(28); // à droite de l'emblème
		hd.text(HdStyle.SECTION, hd.ellipsize(HdStyle.SECTION, ClientState.str(d, "nom"), rw - hd.u(28)), tx, hd.px(py + 14), Hd.WINE);
		hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, subtitle(d), rw - hd.u(28)), tx, hd.px(py + 28), Hd.INK_SOFT);
		hd.separator(rx, hd.px(py + 42), rw);
		int y = hd.px(py + 48);
		y += hd.paragraph(HdStyle.TEXTE, description(ClientState.str(d, "sorte")), rx, y, rw, Hd.INK, hd.px(py + ph - 58) - y);
		// Les ateliers : qui y est invité (pour un opérateur), ou que l'on y est invité.
		String build = null;
		List<String> g = guests(d);
		if (g != null) {
			build = g.isEmpty() ? "Aucun invité : /vc atelier inviter <joueur> pour ouvrir cet atelier à un joueur."
				: "Invités à construire : " + names(g) + ".";
		} else if (ClientState.bool(d, "invite")) {
			build = "Tu es invité à y construire : mode créatif, baguette de construction et WorldEdit.";
		}
		if (build != null && hd.px(py + ph - 58) - y > hd.u(10)) {
			y += hd.u(4);
			y += hd.paragraph(HdStyle.TEXTE, build, rx, y, rw, Hd.WINE, hd.px(py + ph - 58) - y);
		}
		String why = blocked();
		if (why != null) {
			hd.paragraph(HdStyle.TEXTE, why, rx, y + hd.u(6), rw, Hd.WINE, hd.px(py + ph - 58) - y - hd.u(6));
		}
	}
}
