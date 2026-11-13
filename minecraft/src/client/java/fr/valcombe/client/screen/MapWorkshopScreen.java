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
 * L'écran de l'atelier carte, pour qui se trouve dans un atelier : à gauche la fiche de la carte (son compte de repères,
 * ce qui lui manque pour se jouer, son état d'enregistrement) ; à droite ce qu'on en fait : Enregistrer, Enregistrer
 * sous, Versions (revenir à une version), Envoyer à la partie (choix du monde de partie), Fermer l'atelier, et les outils
 * de repères.
 * <p>
 * La vue vient du serveur ({@code atelierCarte}) ; chaque action est une commande {@code /vc atelier carte ...}, dont la
 * réponse s'affiche sous la fiche. Fermer l'atelier se confirme d'un second clic. Un invité de l'atelier y bâtit et pose
 * les repères : les actions d'enregistrement et d'envoi sont celles des opérateurs (grisées pour lui, avec la raison).
 */
public class MapWorkshopScreen extends HdScreen {
	private static final int LEFT_W = 196;
	private static final String COPY_KEY = "atelier-carte.copie";
	private static final int GREEN = 0xFF3F7A3A;

	/** Ce que montre la partie droite. */
	private enum Mode {
		ACTIONS, VERSIONS, ENVOYER, COPIE, FERMER
	}

	private Mode mode = Mode.ACTIONS;
	private String chosenVersion;
	private String chosenParty;
	/** Numéro du dernier message du serveur à l'ouverture : seuls les suivants s'affichent. */
	private final int seenMessage;
	private HdList<JsonObject> list;

	public MapWorkshopScreen() {
		super("Atelier de la carte");
		JsonObject m = message();
		seenMessage = m == null ? 0 : ClientState.num(m, "n", 0);
	}

	@Override
	protected int[] panelSize() {
		return new int[] {420, 222};
	}

	/** Ce que le serveur envoie quand on est dans un atelier carte, ou null. */
	public static JsonObject data() {
		return ClientState.obj(view(), "atelierCarte");
	}

	private static JsonObject message() {
		return ClientState.obj(data(), "message");
	}

	private static String str(JsonObject o, String key) {
		String s = o == null ? null : ClientState.str(o, key);
		return s == null ? "" : s;
	}

	/** Ce que montre l'écran (pour les tests en jeu) : « actions », « versions », « envoyer », « copie », « fermer ». */
	public String mode() {
		return mode.name().toLowerCase(java.util.Locale.ROOT);
	}

	private void show(Mode m) {
		mode = m;
		rebuildSoon();
	}

	private void act(String command) {
		send("vc atelier carte " + command);
		mode = Mode.ACTIONS;
		rebuildSoon();
	}

	@Override
	protected void build() {
		JsonObject d = data();
		if (d == null || !d.has("monde")) {
			button(px + pw - 110, py + ph - 26, 100, "Fermer", this::onClose);
			return;
		}
		int rx = px + LEFT_W + 20;
		int rw = pw - LEFT_W - 30;
		switch (mode) {
			case VERSIONS -> buildVersions(d, rx, rw);
			case ENVOYER -> buildSend(d, rx, rw);
			case COPIE -> buildCopy(d, rx, rw);
			case FERMER -> buildClose(rx, rw);
			default -> buildActions(d, rx, rw);
		}
		button(px + pw - 110, py + ph - 26, 100, "Fermer", this::onClose);
		button(px + 10, py + ph - 26, 120, "Mondes…", () -> minecraft.gui.setScreen(new WorldsScreen()))
			.tooltip("Le hub, les parties et les autres ateliers.");
	}

	/** Les actions de l'atelier, en colonne. */
	private void buildActions(JsonObject d, int rx, int rw) {
		boolean op = ClientState.bool(d, "operateur");
		boolean busy = ClientState.bool(d, "pose");
		String onlyOps = "Réservé aux opérateurs : l'enregistrement automatique garde votre travail.";
		int y = py + 14;
		int step = 20;
		HdButton save = button(rx, y, rw, "Enregistrer", () -> act("enregistrer"))
			.tooltip("Range la carte dans la bibliothèque, repères compris, et garde une version.");
		HdButton copy = button(rx, y += step, rw, "Enregistrer sous…", () -> show(Mode.COPIE))
			.tooltip("Range une copie sous un autre nom ; l'atelier continue sur la copie.");
		int n = d.has("versions") && d.get("versions").isJsonArray() ? d.getAsJsonArray("versions").size() : 0;
		HdButton versions = button(rx, y += step, rw, "Versions (" + n + ")…", () -> show(Mode.VERSIONS))
			.tooltip("Les enregistrements gardés : revenir à l'un d'eux.");
		HdButton sendTo = button(rx, y += step, rw, "Envoyer à la partie…", () -> show(Mode.ENVOYER))
			.tooltip("Enregistre la carte, puis la pose dans le monde de partie choisi (remis à neuf).");
		if (!ClientState.bool(d, "jouable")) {
			sendTo.tooltip(str(d, "verdict"));
		}
		HdButton tools = button(rx, y += step, rw, "Outils de repères", () -> act("outils"))
			.tooltip("Trône, maison, centre, salle de convocation, gomme, structure et main, dans votre inventaire.");
		if (!op) {
			tools.disabled().tooltip("Donnés à l'entrée de l'atelier : ils sont dans votre inventaire.");
		}
		button(rx, y += step, rw, "Annuler le dernier geste", () -> {
			send("vc annuler");
			rebuildSoon();
		}).tooltip("Défait le dernier geste : WorldEdit, structure posée ou repère.");
		HdButton close = button(rx, y + step + 4, rw, "Fermer l'atelier…", () -> show(Mode.FERMER))
			.tooltip("Enregistre la carte, puis supprime le monde de l'atelier.");
		for (HdButton b : List.of(save, copy, versions, sendTo, close)) {
			if (!op) {
				b.disabled().tooltip(onlyOps);
			} else if (busy) {
				b.disabled().tooltip("La carte est en train d'être posée : un instant.");
			}
		}
		if (op && !busy && !ClientState.bool(d, "jouable")) {
			sendTo.disabled();
		}
	}

	/** Les versions gardées : la liste, et « Revenir à cette version ». */
	private void buildVersions(JsonObject d, int rx, int rw) {
		List<JsonObject> all = objects(d, "versions");
		list = addRenderableWidget(new HdList<JsonObject>(rx, py + 14, rw, ph - 92, 24, o -> str(o, "date"))
			.items(all).key("atelier-carte.versions").empty("Aucune version gardée")
			.onSelect(o -> {
				chosenVersion = str(o, "id");
				rebuildSoon();
			}));
		list.row((hd, o, index, x, y, w, h, hovered, sel) -> {
			if (sel) {
				hd.fill(x, y, x + w, y + h, Hd.SELECTION);
			} else if (hovered) {
				hd.fill(x, y, x + w, y + h, Hd.HOVER);
			}
			int tx = x + hd.u(5);
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, "Version du " + str(o, "date"), w - hd.u(8)), tx, y + hd.u(2),
				sel ? Hd.LIGHT : Hd.INK);
			String sub = str(o, "sorte") + " · " + ClientState.num(o, "trones", 0) + " trône(s), " + ClientState.num(o, "maisons", 0)
				+ " maison(s)";
			hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, sub, w - hd.u(8)), tx, y + hd.u(13), sel ? Hd.GOLD : Hd.INK_SOFT);
		});
		int pick = -1;
		for (int i = 0; i < all.size(); i++) {
			if (str(all.get(i), "id").equals(chosenVersion)) {
				pick = i;
			}
		}
		list.select(pick);
		int half = (rw - 6) / 2;
		JsonObject v = list.selected();
		HdButton back = button(rx, py + ph - 72, half, "Revenir à cette version", () -> {
			if (v != null) {
				act("revenir " + str(v, "id"));
			}
		}).tooltip("L'état présent est d'abord gardé (version « avant un retour en arrière »), puis cette version revient dans la "
			+ "bibliothèque et dans l'atelier.");
		if (v == null || !ClientState.bool(d, "operateur") || ClientState.bool(d, "pose")) {
			back.disabled();
		}
		button(rx + half + 6, py + ph - 72, half, "Retour", () -> show(Mode.ACTIONS));
	}

	/** Envoyer à la partie : le monde de partie visé, puis « Envoyer ». */
	private void buildSend(JsonObject d, int rx, int rw) {
		List<JsonObject> all = objects(d, "parties");
		if (chosenParty == null) {
			for (JsonObject o : all) {
				if (ClientState.bool(o, "principale")) {
					chosenParty = str(o, "id");
				}
			}
		}
		list = addRenderableWidget(new HdList<JsonObject>(rx, py + 14, rw, ph - 92, 24, o -> str(o, "nom"))
			.items(all).key("atelier-carte.parties").empty("Aucun monde de partie")
			.onSelect(o -> {
				chosenParty = str(o, "id");
				rebuildSoon();
			}));
		list.row((hd, o, index, x, y, w, h, hovered, sel) -> {
			if (sel) {
				hd.fill(x, y, x + w, y + h, Hd.SELECTION);
			} else if (hovered) {
				hd.fill(x, y, x + w, y + h, Hd.HOVER);
			}
			int tx = x + hd.u(5);
			hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, str(o, "nom"), w - hd.u(8)), tx, y + hd.u(2), sel ? Hd.LIGHT : Hd.INK);
			String sub = (ClientState.bool(o, "principale") ? "la partie du serveur" : str(o, "sorte"))
				+ (str(o, "carte").isEmpty() ? "" : " · porte « " + str(o, "carte") + " »");
			hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, sub, w - hd.u(8)), tx, y + hd.u(13), sel ? Hd.GOLD : Hd.INK_SOFT);
		});
		int pick = -1;
		for (int i = 0; i < all.size(); i++) {
			if (str(all.get(i), "id").equals(chosenParty)) {
				pick = i;
			}
		}
		list.select(pick);
		int half = (rw - 6) / 2;
		JsonObject target = list.selected();
		HdButton go = button(rx, py + ph - 72, half, "Envoyer", () -> {
			if (target != null) {
				act("envoyer " + str(target, "id"));
			}
		}).tooltip("La carte est d'abord enregistrée ; le monde choisi est remis à neuf, plat et vide, puis elle y est posée. "
			+ "L'atelier reste ouvert.");
		if (target == null || !ClientState.bool(d, "jouable") || ClientState.bool(d, "pose")) {
			go.disabled();
			if (!ClientState.bool(d, "jouable")) {
				go.tooltip(str(d, "verdict"));
			}
		}
		button(rx + half + 6, py + ph - 72, half, "Retour", () -> show(Mode.ACTIONS));
	}

	/** Enregistrer sous : le nom de la copie. */
	private void buildCopy(JsonObject d, int rx, int rw) {
		HdTextField field = addRenderableWidget(new HdTextField(rx, py + 52, rw, 18, "Nom de la copie")
			.maxLength(50).hint("Nom de la copie…").key(COPY_KEY));
		if (field.value().isEmpty()) {
			field.value(str(d, "nom") + " (copie)");
		}
		int half = (rw - 6) / 2;
		Runnable ok = () -> {
			String nom = field.value().strip();
			if (!nom.isEmpty()) {
				HdTextField.forget(COPY_KEY);
				act("enregistrer-sous " + nom);
			}
		};
		field.onEnter(ok);
		button(rx, py + 80, half, "Enregistrer la copie", ok);
		button(rx + half + 6, py + 80, half, "Annuler", () -> show(Mode.ACTIONS));
	}

	/** Fermer l'atelier : la question, puis « Oui, fermer ». */
	private void buildClose(int rx, int rw) {
		int half = (rw - 6) / 2;
		button(rx, py + ph - 72, half, "Oui, fermer", () -> {
			act("fermer confirmer");
			onClose();
		});
		button(rx + half + 6, py + ph - 72, half, "Annuler", () -> show(Mode.ACTIONS));
	}

	@Override
	protected void drawHd(Hd hd) {
		JsonObject d = data();
		int lx = hd.px(px + 12);
		int lw = hd.px(LEFT_W - 4);
		if (d == null || !d.has("monde")) {
			hd.paragraph(HdStyle.TEXTE, "Vous n'êtes pas dans un atelier carte. Menu J › Mondes : « Ouvrir une carte… ».", lx, hd.px(py + 16),
				hd.px(pw - 24), Hd.INK_SOFT);
			return;
		}
		int y = hd.px(py + 12);
		hd.text(HdStyle.SECTION, hd.ellipsize(HdStyle.SECTION, str(d, "nom"), lw), lx, y, Hd.WINE);
		y += hd.lineHeight(HdStyle.SECTION) + hd.u(2);
		String kind = ClientState.bool(d, "genere") ? "village généré (" + str(d, "theme") + ")" : "carte faite à la main";
		hd.text(HdStyle.PETIT, hd.ellipsize(HdStyle.PETIT, str(d, "carte") + " · " + kind, lw), lx, y, Hd.INK_SOFT);
		y += hd.lineHeight(HdStyle.PETIT) + hd.u(3);
		hd.separator(lx, y, lw);
		y += hd.u(5);
		if (ClientState.bool(d, "pose")) {
			hd.paragraph(HdStyle.TEXTE, "La carte est en train d'être posée : une barre suit l'avancement.", lx, y, lw, Hd.INK_SOFT);
			drawRight(hd, d);
			return;
		}
		int line = hd.lineHeight(HdStyle.FORT) + hd.u(2);
		int seats = ClientState.num(d, "trones", 0);
		int places = ClientState.num(d, "places", 0);
		hd.text(HdStyle.FORT, "♛ " + seats + (seats > 1 ? " trônes" : " trône") + (seats < places ? " sur " + places : ""), lx, y,
			ClientState.bool(d, "jouable") ? Hd.INK : Hd.WINE);
		y += line;
		int houses = ClientState.num(d, "maisons", 0);
		hd.text(HdStyle.FORT, "⌂ " + houses + (houses > 1 ? " maisons" : " maison"), lx, y, Hd.INK);
		y += line;
		hd.text(HdStyle.FORT, "✉ Salle de convocation : " + (ClientState.bool(d, "salle") ? "oui" : "non"), lx, y, Hd.INK);
		y += line + hd.u(2);
		boolean playable = ClientState.bool(d, "jouable");
		hd.disc(lx + hd.u(4), y + hd.lineHeight(HdStyle.FORT) / 2, hd.u(3), playable ? GREEN : Hd.WINE);
		hd.text(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, playable ? "Jouable" : str(d, "verdict"), lw - hd.u(12)), lx + hd.u(11), y,
			playable ? GREEN : Hd.WINE);
		y += line;
		int bottom = hd.px(py + ph - 34);
		for (JsonObject l : objects(d, "manques")) {
			if (ClientState.bool(l, "bloquant") || y >= bottom) {
				continue; // ce qui empêche de jouer est déjà dit au-dessus
			}
			y += hd.paragraph(HdStyle.PETIT, "· " + str(l, "texte"), lx, y, lw, Hd.INK_SOFT, bottom - y) + hd.u(1);
		}
		y += hd.u(3);
		if (y < bottom) {
			String state = str(d, "etat");
			int auto = ClientState.num(d, "autoDans", -1);
			if (auto >= 0) {
				state += " Enregistrement automatique dans " + Math.max(1, auto) + " min.";
			}
			hd.paragraph(HdStyle.PETIT, state, lx, y, lw, ClientState.bool(d, "modifiee") ? Hd.WINE : Hd.INK_SOFT, bottom - y);
		}
		drawRight(hd, d);
	}

	/** La partie droite : l'aide du mode affiché, et la réponse du serveur à la dernière action. */
	private void drawRight(Hd hd, JsonObject d) {
		int rx = hd.px(px + LEFT_W + 20);
		int rw = hd.px(pw - LEFT_W - 30);
		int bottom = hd.px(py + ph - 32);
		int y;
		switch (mode) {
			case COPIE -> {
				hd.text(HdStyle.SECTION, "Enregistrer sous", rx, hd.px(py + 14), Hd.WINE);
				hd.paragraph(HdStyle.PETIT, "Une copie rejoint la bibliothèque sous ce nom ; l'atelier continue sur elle.", rx, hd.px(py + 30),
					rw, Hd.INK_SOFT);
				y = hd.px(py + 104);
			}
			case FERMER -> {
				hd.text(HdStyle.SECTION, "Fermer l'atelier ?", rx, hd.px(py + 14), Hd.WINE);
				hd.paragraph(HdStyle.TEXTE, "La carte est d'abord enregistrée dans la bibliothèque (avec une version), puis le monde de "
					+ "l'atelier est supprimé ; ceux qui s'y trouvent vont au hub. On la rouvre quand on veut depuis la bibliothèque.",
					rx, hd.px(py + 32), rw, Hd.INK, hd.px(py + ph - 78) - hd.px(py + 32));
				y = hd.px(py + ph - 50);
			}
			case VERSIONS, ENVOYER -> y = hd.px(py + ph - 50);
			default -> y = hd.px(py + 162);
		}
		JsonObject m = message();
		if (m != null && ClientState.num(m, "n", 0) > seenMessage && y < bottom) {
			hd.paragraph(HdStyle.PETIT, str(m, "texte"), rx, y, rw, ClientState.bool(m, "erreur") ? Hd.WINE : Hd.INK_SOFT, bottom - y);
		}
	}
}
