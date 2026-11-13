package fr.valcombe.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdList;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTabs;
import fr.valcombe.client.hd.HdTextField;

import java.util.List;

/**
 * L'écran de l'atelier structures, ouvert par le pupitre d'une parcelle (ou celui de l'accueil) : à gauche toutes les
 * parcelles, rangée par rangée ; à droite la parcelle choisie, son état et ses actions (Enregistrer, Reposer, Revenir à
 * l'originale, Tourner, Renommer, Repère, Supprimer, Y aller), et « Nouvelle structure ».
 * <p>
 * La vue vient du serveur ({@code atelierStructures} : parcelles, parcelle choisie, modifications en cours, dernier
 * message) ; chaque action est une commande {@code /vc structures ...}, dont la réponse s'affiche sous la fiche.
 * Les actions qui perdent quelque chose (revenir à l'originale, supprimer) se confirment d'un second clic.
 */
public class StructuresWorkshopScreen extends HdScreen {
	private static final int LIST_W = 160;
	private static final String NAME_KEY = "atelier-structures.nom";
	private static final String NEW_KEY = "atelier-structures.nouvelle";
	/** Les tailles d'une parcelle neuve, telles que le serveur les comprend, leur libellé, leur côté et leur hauteur. */
	private static final List<String> SIZES = List.of("petite", "moyenne", "grande", "immense");
	private static final List<String> SIZE_LABELS = List.of("Petite", "Moyenne", "Grande", "Immense");
	private static final int[][] SIZE_DIMS = {{11, 16}, {17, 24}, {25, 32}, {41, 48}};
	private static final int GREEN = 0xFF3F7A3A;
	private static final int BLUE = 0xFF2F6A88;

	/** Ce que montre la partie droite. */
	private enum Mode {
		FICHE, RENOMMER, NOUVELLE, CONFIRMER_ORIGINALE, CONFIRMER_SUPPRESSION
	}

	private String chosen;
	private Mode mode = Mode.FICHE;
	private int size = 1;
	/** Numéro du dernier message du serveur à l'ouverture : seuls les suivants s'affichent. */
	private final int seenMessage;
	private HdList<JsonObject> list;

	/** Ouvre l'écran sur cette parcelle (null ou vide : celle que le serveur dit choisie). */
	public StructuresWorkshopScreen(String id) {
		super("Atelier des structures");
		chosen = id == null || id.isBlank() ? null : id;
		JsonObject m = message();
		seenMessage = m == null ? 0 : ClientState.num(m, "n", 0);
	}

	@Override
	protected int[] panelSize() {
		return new int[] {410, 216};
	}

	private static JsonObject data() {
		return ClientState.obj(view(), "atelierStructures");
	}

	/** Les parcelles reçues du serveur, dans l'ordre des rangées. */
	public static List<JsonObject> parcels() {
		JsonObject d = data();
		return d == null ? List.of() : objects(d, "parcelles");
	}

	private static JsonObject message() {
		return ClientState.obj(data(), "message");
	}

	/** La parcelle affichée, ou null. */
	public JsonObject selected() {
		return list == null ? null : list.selected();
	}

	/** La parcelle affichée (identifiant), pour les tests en jeu. */
	public String chosenId() {
		return chosen;
	}

	private static String str(JsonObject o, String key) {
		String s = ClientState.str(o, key);
		return s == null ? "" : s;
	}

	private static String stateLabel(String etat) {
		return switch (etat) {
			case "fournie" -> "fournie";
			case "modifiee" -> "modifiée";
			case "creee" -> "créée";
			default -> "nouvelle";
		};
	}

	private static int stateColor(String etat) {
		return switch (etat) {
			case "modifiee" -> Hd.GOLD;
			case "creee" -> GREEN;
			case "nouvelle" -> BLUE;
			default -> Hd.ASH;
		};
	}

	/** Envoie une action sur la parcelle affichée ; withChoice : la choisir d'abord (actions sans identifiant). */
	private void act(JsonObject p, String action, boolean withChoice) {
		if (p == null) {
			return;
		}
		String id = str(p, "id");
		if (withChoice) {
			send("vc structures choisir " + id);
			send("vc structures " + action);
		} else {
			send("vc structures " + action + " " + id);
		}
		mode = Mode.FICHE;
		rebuildSoon();
	}

	@Override
	protected void build() {
		List<JsonObject> all = parcels();
		JsonObject d = data();
		if (chosen == null && d != null) {
			chosen = ClientState.str(d, "choisie");
		}
		list = addRenderableWidget(new HdList<JsonObject>(px + 10, py + 14, LIST_W, ph - 48, 26, o -> str(o, "nom"))
			.items(all).key("atelier-structures.liste").empty("Aucune parcelle pour l'instant")
			.onSelect(o -> {
				chosen = str(o, "id");
				// Le serveur suit la parcelle affichée : il dit si elle a des modifications non enregistrées.
				send("vc structures choisir " + chosen);
				if (mode != Mode.NOUVELLE) {
					mode = Mode.FICHE;
				}
				rebuildSoon();
			})
			.onActivate(o -> {
				send("vc structures aller " + str(o, "id"));
				onClose();
			}));
		list.row((hd, o, index, x, y, w, h, hovered, sel) -> {
			if (sel) {
				hd.fill(x, y, x + w, y + h, Hd.SELECTION);
			} else if (hovered) {
				hd.fill(x, y, x + w, y + h, Hd.HOVER);
			}
			String etat = str(o, "etat");
			int dot = hd.u(3);
			hd.disc(x + hd.u(7), y + h / 2, dot, stateColor(etat));
			int tx = x + hd.u(14);
			String here = str(o, "id").equals(ClientState.str(d, "ici")) ? "ici" : "";
			int hw = here.isEmpty() ? 0 : hd.width(HdStyle.PETIT, here) + hd.u(6);
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(o, "nom"), w - (tx - x) - hw - hd.u(4)), tx, y + hd.u(2),
				sel ? Hd.LIGHT : Hd.INK);
			String sub = str(o, "rangee") + " · " + stateLabel(etat) + (ClientState.bool(o, "conflit") ? " · changée ailleurs" : "");
			hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, sub, w - (tx - x) - hd.u(4)), tx, y + hd.u(14), sel ? Hd.GOLD : Hd.INK_SOFT);
			if (!here.isEmpty()) {
				hd.textRight(HdStyle.PETIT, here, x + w - hd.u(5), y + hd.u(3), sel ? Hd.GOLD : Hd.WINE);
			}
		});
		int pick = -1;
		for (int i = 0; i < all.size(); i++) {
			if (str(all.get(i), "id").equals(chosen)) {
				pick = i;
			}
		}
		list.select(pick);

		int rx = px + LIST_W + 20;
		int rw = pw - LIST_W - 30;
		int half = (rw - 6) / 2;
		JsonObject p = selected();
		if (mode == Mode.NOUVELLE) {
			buildNew(rx, rw, half);
		} else if (p != null) {
			buildActions(p, rx, rw, half);
		}
		HdButton fresh = button(px + 10, py + ph - 26, LIST_W, "Nouvelle structure", () -> {
			mode = mode == Mode.NOUVELLE ? Mode.FICHE : Mode.NOUVELLE;
			rebuildSoon();
		}).tooltip("Une parcelle vide, nommée, au bout de la rangée « Vos structures » : bâtissez-y, puis « Enregistrer ».");
		if (mode == Mode.NOUVELLE) {
			fresh.disabled();
		}
		button(px + pw - 110, py + ph - 26, 100, "Fermer", this::onClose);
	}

	/** Les actions de la parcelle affichée, en deux colonnes au bas de la fiche. */
	private void buildActions(JsonObject p, int rx, int rw, int half) {
		String etat = str(p, "etat");
		boolean busy = ClientState.bool(p, "pose");
		boolean builtin = etat.equals("fournie") || etat.equals("modifiee");
		int y1 = py + ph - 92;
		int y2 = py + ph - 70;
		int y3 = py + ph - 48;
		if (mode == Mode.RENOMMER) {
			HdTextField field = addRenderableWidget(new HdTextField(rx, y2, rw, 18, "Nouveau nom")
				.maxLength(50).value(str(p, "nom")).hint("Nouveau nom…").key(NAME_KEY + str(p, "id")));
			Runnable ok = () -> {
				String nom = field.value().strip();
				if (!nom.isEmpty()) {
					HdTextField.forget(NAME_KEY + str(p, "id"));
					act(p, "renommer " + nom, true);
				}
			};
			field.onEnter(ok);
			button(rx, y3, half, "Renommer", ok);
			button(rx + half + 6, y3, half, "Annuler", () -> {
				mode = Mode.FICHE;
				rebuildSoon();
			});
			return;
		}
		if (mode == Mode.CONFIRMER_ORIGINALE || mode == Mode.CONFIRMER_SUPPRESSION) {
			boolean revert = mode == Mode.CONFIRMER_ORIGINALE;
			button(rx, y3, half, revert ? "Oui, l'originale" : "Oui, supprimer",
				() -> act(p, revert ? "originale" : "supprimer confirmer", !revert));
			button(rx + half + 6, y3, half, "Annuler", () -> {
				mode = Mode.FICHE;
				rebuildSoon();
			});
			return;
		}
		HdButton save = button(rx, y1, half, "Enregistrer", () -> act(p, "enregistrer", false))
			.tooltip(builtin ? "Capture la parcelle : cette version remplace l'originale partout (village généré, éditeur, Atelier web)."
				: "Capture la parcelle et la range dans le catalogue.");
		HdButton reset = button(rx + half + 6, y1, half, "Reposer", () -> act(p, "reposer", false))
			.tooltip("Abandonne les changements en cours : la version enregistrée revient sur la parcelle.");
		HdButton second;
		if (builtin) {
			second = button(rx, y2, half, "Revenir à l'originale", () -> {
				mode = Mode.CONFIRMER_ORIGINALE;
				rebuildSoon();
			}).tooltip("La version modifiée va à la corbeille, l'originale revient partout et sur la parcelle.");
			if (!etat.equals("modifiee")) {
				second.disabled().tooltip("C'est déjà l'originale.");
			}
		} else {
			second = button(rx, y2, half, "Renommer", () -> {
				mode = Mode.RENOMMER;
				rebuildSoon();
			});
		}
		HdButton turn = button(rx + half + 6, y2, half, "Tourner", () -> act(p, "tourner", false))
			.tooltip("Un quart de tour autour de l'origine, dans le sens des aiguilles d'une montre. « Enregistrer » garde l'orientation.");
		Runnable visit = () -> {
			send("vc structures aller " + str(p, "id"));
			onClose();
		};
		HdButton third;
		if (builtin) {
			third = button(rx, y3, rw, "Y aller", visit).tooltip("Devant son pupitre.");
		} else {
			third = button(rx, y3, half, "Supprimer", () -> {
				mode = Mode.CONFIRMER_SUPPRESSION;
				rebuildSoon();
			}).tooltip(etat.equals("nouvelle") ? "Abandonne la parcelle neuve." : "La structure va à la corbeille du catalogue.");
			if (etat.equals("creee")) {
				String r = str(p, "repere");
				String next = r.isEmpty() ? "maison" : r.equals("maison") ? "salle" : "aucun";
				button(rx + half + 6, y3, half, "Repère : " + (r.isEmpty() ? "aucun" : r), () -> act(p, "repere " + next, true))
					.tooltip("Le repère posé avec la structure sur une carte : aucun, maison, salle de convocation.");
			} else {
				button(rx + half + 6, y3, half, "Y aller", visit).tooltip("Devant son pupitre.");
			}
		}
		if (busy) {
			for (HdButton b : List.of(save, reset, second, turn, third)) {
				b.disabled().tooltip("Pose en cours : un instant.");
			}
		}
	}

	/** « Nouvelle structure » : un nom, une taille, puis la parcelle s'ouvre. */
	private void buildNew(int rx, int rw, int half) {
		HdTextField field = addRenderableWidget(new HdTextField(rx, py + 44, rw, 18, "Nom de la structure")
			.maxLength(50).hint("Nom de la structure…").key(NEW_KEY));
		addRenderableWidget(new HdTabs(rx, py + 74, rw, 16, SIZE_LABELS, size).onChange(i -> {
			size = i;
			rebuildSoon();
		}));
		Runnable create = () -> {
			String nom = field.value().strip();
			if (nom.isEmpty()) {
				return;
			}
			HdTextField.forget(NEW_KEY);
			send("vc structures nouvelle " + SIZES.get(size) + " " + nom);
			mode = Mode.FICHE;
			onClose();
		};
		field.onEnter(create);
		button(rx, py + ph - 48, half, "Créer la parcelle", create);
		button(rx + half + 6, py + ph - 48, half, "Annuler", () -> {
			mode = Mode.FICHE;
			rebuildSoon();
		});
	}

	@Override
	protected void drawHd(Hd hd) {
		int rx = hd.px(px + LIST_W + 20);
		int rw = hd.px(pw - LIST_W - 30);
		int bottom = hd.px(py + ph - 96);
		if (mode == Mode.NOUVELLE) {
			hd.text(HdStyle.SECTION, "Nouvelle structure", rx, hd.px(py + 14), Hd.WINE);
			hd.text(HdStyle.PETIT, "Son nom, puis la taille de sa parcelle.", rx, hd.px(py + 30), Hd.INK_SOFT);
			int[] dims = SIZE_DIMS[size];
			int y = hd.px(py + 96);
			hd.text(HdStyle.FORT, "Parcelle de " + dims[0] + " × " + dims[0] + " blocs, " + dims[1] + " de haut", rx, y, Hd.INK);
			y += hd.lineHeight(HdStyle.FORT) + hd.u(3);
			hd.paragraph(HdStyle.TEXTE, "Elle s'ouvre au bout de la rangée « Vos structures » et vous y conduit. Bâtissez dans "
				+ "le quadrillage (le sol ne compte pas), à la main ou avec WorldEdit, puis « Enregistrer » à son pupitre.",
				rx, y, rw, Hd.INK_SOFT, hd.px(py + ph - 54) - y);
			return;
		}
		JsonObject p = selected();
		if (p == null) {
			hd.paragraph(HdStyle.TEXTE, parcels().isEmpty() ? "Les parcelles se posent : un instant." : "Choisissez une parcelle dans la liste.",
				rx, hd.px(py + 16), rw, Hd.INK_SOFT);
			return;
		}
		String etat = str(p, "etat");
		hd.disc(rx + hd.u(5), hd.px(py + 14) + hd.lineHeight(HdStyle.SECTION) / 2, hd.u(4), stateColor(etat));
		int tx = rx + hd.u(14);
		hd.text(HdStyle.SECTION, hd.ellipsize(HdStyle.SECTION, str(p, "nom"), rw - hd.u(14)), tx, hd.px(py + 14), Hd.WINE);
		hd.text(HdStyle.PETIT, str(p, "rangee") + " · " + stateLabel(etat) + (etat.equals("modifiee") && p.has("modifieeLe")
			? " le " + str(p, "modifieeLe") : ""), tx, hd.px(py + 29), Hd.INK_SOFT);
		hd.separator(rx, hd.px(py + 41), rw);
		int y = hd.px(py + 46);
		int line = hd.lineHeight(HdStyle.TEXTE);
		if (mode == Mode.CONFIRMER_ORIGINALE || mode == Mode.CONFIRMER_SUPPRESSION) {
			String q = mode == Mode.CONFIRMER_ORIGINALE
				? "Revenir à l'originale ? La version modifiée va à la corbeille ; l'originale revient partout (village généré, éditeur, "
					+ "Atelier web) et sur la parcelle. Les changements non enregistrés de la parcelle sont perdus."
				: etat.equals("nouvelle") ? "Abandonner cette parcelle neuve ? Ce qui y est bâti est rasé."
				: "Supprimer « " + str(p, "nom") + " » ? La structure quitte le catalogue (elle est gardée dans sa corbeille) et sa parcelle "
					+ "est rasée.";
			hd.paragraph(HdStyle.TEXTE, q, rx, y, rw, Hd.WINE, hd.px(py + ph - 52) - y);
			return;
		}
		JsonArray t = p.has("taille") && p.get("taille").isJsonArray() ? p.getAsJsonArray("taille") : null;
		String dims = t == null || t.size() != 3 ? "pas encore enregistrée"
			: t.get(0).getAsInt() + " × " + t.get(1).getAsInt() + " × " + t.get(2).getAsInt() + " · " + ClientState.num(p, "blocs", 0) + " blocs";
		hd.text(HdStyle.TEXTE, "Structure : " + dims, rx, y, Hd.INK);
		y += line;
		int side = ClientState.num(p, "cote", 0);
		hd.text(HdStyle.TEXTE, "Parcelle : " + side + " × " + side + ", " + ClientState.num(p, "haut", 0) + " de haut", rx, y, Hd.INK);
		y += line;
		JsonObject d = data();
		boolean shown = str(p, "id").equals(ClientState.str(d, "choisie"));
		String status;
		int color;
		if (ClientState.bool(p, "pose")) {
			status = "Pose en cours…";
			color = Hd.INK_SOFT;
		} else if (ClientState.bool(p, "conflit")) {
			status = "Changée ailleurs : « Reposer » ou « Enregistrer »";
			color = Hd.WINE;
		} else if (shown && d.has("changements") && !d.get("changements").isJsonNull()) {
			boolean changed = ClientState.bool(d, "changements");
			status = changed ? "Modifications non enregistrées" : "Rien de changé depuis l'enregistrement";
			color = changed ? Hd.WINE : Hd.INK_SOFT;
		} else {
			status = "";
			color = Hd.INK_SOFT;
		}
		if (!status.isEmpty()) {
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, status, rw), rx, y, color);
			y += line;
		}
		JsonObject m = message();
		if (m != null && ClientState.num(m, "n", 0) > seenMessage) {
			hd.paragraph(HdStyle.PETIT, str(m, "texte"), rx, y + hd.u(2), rw, ClientState.bool(m, "erreur") ? Hd.WINE : Hd.INK_SOFT,
				bottom - y - hd.u(2));
		} else if (y < bottom) {
			String help = switch (etat) {
				case "fournie" -> "Fournie avec le mod. Modifiez-la sur sa parcelle, puis « Enregistrer » : elle remplacera l'originale partout.";
				case "modifiee" -> "Cette version remplace l'originale partout. « Revenir à l'originale » la retire.";
				case "nouvelle" -> "Bâtissez dans le quadrillage, puis « Enregistrer » : elle rejoindra le catalogue.";
				default -> "Votre structure : enregistrez ses changements, renommez-la, choisissez son repère.";
			};
			hd.paragraph(HdStyle.PETIT, help, rx, y + hd.u(2), rw, Hd.INK_SOFT, bottom - y - hd.u(2));
		}
	}
}
