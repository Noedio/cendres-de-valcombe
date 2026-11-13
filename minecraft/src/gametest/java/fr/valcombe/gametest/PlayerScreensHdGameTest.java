package fr.valcombe.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdTextField;
import fr.valcombe.client.screen.ChronicleScreen;
import fr.valcombe.client.screen.ClaimScreen;
import fr.valcombe.client.screen.FiguresScreen;
import fr.valcombe.client.screen.GuessScreen;
import fr.valcombe.client.screen.MainScreen;
import fr.valcombe.client.screen.NightScreen;
import fr.valcombe.client.screen.NotebookGuessScreen;
import fr.valcombe.client.screen.NotebookScreen;
import fr.valcombe.client.screen.PickerScreen;
import fr.valcombe.client.screen.PrefsScreen;
import fr.valcombe.client.screen.PromptScreen;
import fr.valcombe.client.screen.RolePickerScreen;
import fr.valcombe.client.screen.SheetsScreen;
import fr.valcombe.client.screen.SignScreen;
import fr.valcombe.client.screen.TokenScreen;
import fr.valcombe.client.ui.TokenWidget;
import fr.valcombe.client.ui.WaxButton;
import fr.valcombe.rules.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

/**
 * Les écrans des joueurs et l'affichage en jeu, passés à la boîte à outils haute résolution (ticket c7cff5, partie
 * joueurs). Chaque écran est ouvert sur une vue de partie fabriquée ici (salon, jour avec vote, nuit avec invites,
 * pupitre de nuit), dans une interface de 427 x 240 (fenêtre de 854 x 480 à l'échelle 2), et l'on vérifie :
 * <ul>
 * <li>que c'est un écran haute résolution, sans bouton, jeton ou champ de l'ancienne interface ;</li>
 * <li>que tous ses éléments tiennent dans la fenêtre et que ses boutons ne se chevauchent pas ;</li>
 * <li>pour le carnet : la note tapée est gardée, même quand l'écran se reconstruit sur une nouvelle vue.</li>
 * </ul>
 * Une capture par écran (valcombe-hd-joueurs-*.png), plus l'affichage en jeu : carte de rôle, vote et tableau du
 * jour, nuit. La vue réelle du serveur est remise à la fin.
 */
public class PlayerScreensHdGameTest implements FabricClientGameTest {
	private static final String[] NAMES = {"Aurore", "Bastien", "Clémence", "Dorian", "Éloïse", "Faustine", "Gaspard", "Hortense",
		"Isidore", "Joséphine", "Léandre"};

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
	}

	private static void shot(ClientGameTestContext ctx, String name) {
		ctx.waitTicks(10);
		System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-hd-joueurs-" + name));
	}

	// ------------------------------------------------------------------
	// Vues fabriquées
	// ------------------------------------------------------------------
	/** Un objet JSON à partir de paires clé, valeur (valeur : texte, nombre, booléen ou élément JSON). */
	private static JsonObject obj(Object... kv) {
		JsonObject o = new JsonObject();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			o.add((String) kv[i], json(kv[i + 1]));
		}
		return o;
	}

	private static JsonArray arr(Object... items) {
		JsonArray a = new JsonArray();
		for (Object it : items) {
			a.add(json(it));
		}
		return a;
	}

	private static JsonArray arr(List<?> items) {
		return arr(items.toArray());
	}

	private static JsonElement json(Object v) {
		if (v instanceof JsonElement e) {
			return e;
		}
		if (v instanceof Boolean b) {
			return new JsonPrimitive(b);
		}
		if (v instanceof Number n) {
			return new JsonPrimitive(n);
		}
		return new JsonPrimitive(String.valueOf(v));
	}

	/** Les sièges du cercle : moi au siège 0, puis les joueurs fictifs ; deux morts, dont un qui a gardé sa voix. */
	private static JsonArray seats(String me) {
		JsonArray a = new JsonArray();
		for (int i = 0; i <= NAMES.length; i++) {
			String name = i == 0 ? me : NAMES[i - 1];
			boolean alive = i != 3 && i != 7;
			a.add(obj("name", name, "seat", i, "alive", alive, "ghostVote", i == 3, "bot", i > 0, "floor", false,
				"claim", i == 2 ? "GREFFIER" : ""));
		}
		return a;
	}

	private static JsonArray scriptRoles() {
		List<String> ids = new ArrayList<>();
		for (Role r : Role.all()) {
			if (!r.slot) {
				ids.add(r.name());
			}
		}
		return arr(ids);
	}

	private static JsonArray figures() {
		return arr(obj("id", "sentinelle", "nom", "La Sentinelle", "active", true,
				"texte", "Il peut y avoir un Étranger de plus ou de moins."),
			obj("id", "doyen", "nom", "Le Doyen", "active", false,
				"texte", "Les deux premières minutes de chaque jour, les joueurs aguerris (3 parties ou plus) se taisent."),
			obj("id", "sablier", "nom", "Le Sablier", "active", true,
				"texte", "Chaque journée dure dix minutes au plus : la cloche la clôt."));
	}

	/** Une chronique assez longue pour défiler : mise en place, trois nuits, trois jours. */
	private static JsonArray chronicle() {
		List<String> lines = new ArrayList<>();
		lines.add("Mise en place : 12 joueurs, script complet, Conteur automatique.");
		for (int n = 1; n <= 3; n++) {
			lines.add("La nuit " + n + " tombe.");
			lines.add("La Goule choisit " + NAMES[n] + ". " + NAMES[n] + " meurt dans la nuit.");
			lines.add("Le Conteur réveille la Commère, qui apprend qu'un de ses deux voisins est du mauvais côté.");
			lines.add("Le jour " + n + " se lève.");
			lines.add(NAMES[n + 2] + " accuse " + NAMES[n + 4] + " : 5 voix sur 6 nécessaires, le vote échoue de peu.");
			lines.add(NAMES[n + 4] + " nomine " + NAMES[n + 5] + " ; " + NAMES[n + 5] + " est exécuté à la tombée du jour.");
		}
		return arr(lines);
	}

	private static JsonObject lobbyView(String me) {
		List<String> lobby = new ArrayList<>(List.of(NAMES));
		lobby.add(0, me);
		return obj("phase", "LOBBY", "me", me, "inLobby", true, "lobby", arr(lobby), "chronicle", chronicle(),
			"feed", arr("Bastien rejoint le salon.", "Le Conteur a choisi le script « Les Cendres » : vingt-deux rôles, dont la Goule."),
			"script", obj("roles", scriptRoles()), "figures", figures(), "settings", obj("notebookNightOnly", false));
	}

	/** Le jour 2, conseil des accusations : un vote en cours, ma main levée, l'aiguille au cinquième trône. */
	private static JsonObject dayView(String me) {
		JsonArray order = new JsonArray();
		for (int i = 0; i <= NAMES.length; i++) {
			order.add(i == 0 ? me : NAMES[i - 1]);
		}
		JsonObject vote = obj("stage", "VOTE", "nominee", "Gaspard", "count", 3, "threshold", 6, "clockSeat", 4, "clockPos", 4,
			"canVote", true, "myHand", true, "order", order, "locked", arr("1", "2", "3"),
			"lockedNames", arr(NAMES[0], NAMES[1], NAMES[2]), "hands", arr(me, NAMES[5], NAMES[8]));
		JsonObject today = obj("threshold", 6, "block", "Dorian", "blockVotes", 7, "canNominate", true,
			"nominations", arr(obj("by", "Aurore", "target", "Dorian", "votes", 7, "voters", arr("Aurore", "Bastien", "Clémence",
					"Éloïse", "Faustine", "Hortense", "Isidore")),
				obj("by", "Bastien", "target", "Gaspard", "votes", -1)));
		return obj("phase", "DAY", "day", 2, "night", 1, "me", me, "mySeat", 0, "seats", seats(me), "voice", "crier",
			"role", obj("id", "COMMERE", "name", "La Commère", "team", "GOOD",
				"text", "La première nuit, tu apprends que l'un de deux joueurs a un rôle de Citadin précis. "
					+ "Chaque nuit ensuite, tu apprends combien de tes voisins vivants sont du mauvais côté."),
			"feed", arr("Aurore accuse Dorian.", "Dorian est sur le billot avec 7 voix ; Bastien accuse maintenant Gaspard, "
				+ "le vote commence au trône de Clémence."),
			"vote", vote, "today", today, "nominationsOpen", true, "settings", obj("shoutRange", 96, "whisperRange", 4),
			"prompt", obj("type", "vote", "nominee", "Gaspard", "ghost", false), "script", obj("roles", scriptRoles()));
	}

	/** La nuit 2, avec une invite donnée (et, pour le pupitre du Conteur, l'ordre de la nuit et l'étape en cours). */
	private static JsonObject nightView(String me, JsonObject prompt) {
		JsonArray nightOrder = new JsonArray();
		String[] steps = {"Crépuscule", "Les Sbires", "Le Démon", "L'Alchimiste", "La Faussaire", "La Goule", "La Veilleuse",
			"L'Astrologue", "Le Fauconnier", "Le Sonneur", "La Fleuriste", "Le Jongleur", "L'Écuyer", "L'Embaumeur", "Aube"};
		for (int i = 0; i < steps.length; i++) {
			nightOrder.add(obj("title", steps[i], "player", i > 2 && i < 13 ? NAMES[i % NAMES.length] : "",
				"state", Integer.compare(i, 5))); // -1 : passé, 0 : en cours, 1 : à venir
		}
		JsonObject step = obj("title", "La Goule", "player", NAMES[5], "waiting", "son choix de victime",
			"alerts", arr("Faustine est empoisonnée : son information est fausse cette nuit.", "L'Écuyer protège Isidore."),
			"todo", "Réveille la Goule. Elle désigne un joueur, qui mourra. Si elle se désigne elle-même, un Sbire vivant "
				+ "devient la Goule : réveille-le pour le lui dire. Montre-lui ensuite le jeton « Tu es la Goule ».");
		JsonArray grimoire = new JsonArray();
		for (String n : NAMES) {
			grimoire.add(obj("name", n, "reminders", n.equals("Bastien") ? arr("Ivre", "Empoisonné") : new JsonArray(),
				"role", obj("id", "GOULE", "name", "La Goule", "team", "EVIL")));
		}
		JsonArray tokens = arr(
			obj("role", "La Goule", "evil", true, "items", arr("Mort")),
			obj("role", "L'Alchimiste", "evil", true, "items", arr("Empoisonné")),
			obj("role", "L'Écuyer", "evil", false, "items", arr("Maître")),
			obj("role", "La Veilleuse", "evil", false, "items", arr("Protégé", "Vu", "Inconnu")),
			obj("role", "Jetons communs", "evil", false, "items", arr("Ivre", "Empoisonné", "Bon", "Mauvais", "Fou", "Rien de plus")));
		JsonObject v = obj("phase", "NIGHT", "night", 2, "day", 1, "me", me, "mySeat", 0, "seats", seats(me), "isStoryteller", true,
			// Même rôle que le jour : la carte de rôle ne retombe pas sur l'affichage en jeu.
			"role", obj("id", "COMMERE", "name", "La Commère", "team", "GOOD",
				"text", "Chaque nuit, tu apprends combien de tes voisins vivants sont du mauvais côté."),
			"nightOrder", nightOrder, "nightStep", step, "awake", arr(NAMES[5]), "paused", true, "grimoire", grimoire,
			"tokens", tokens, "script", obj("roles", scriptRoles()));
		if (prompt != null) {
			v.add("prompt", prompt);
		}
		return v;
	}

	private static JsonObject choosePrompt() {
		JsonArray options = new JsonArray();
		for (int i = 0; i < NAMES.length; i++) {
			options.add(obj("name", NAMES[i], "alive", i != 2 && i != 6, "selected", i == 4));
		}
		return obj("type", "choose", "title", "La Fleuriste", "count", 2, "optional", true, "options", options);
	}

	// ------------------------------------------------------------------
	// Contrôles d'un écran
	// ------------------------------------------------------------------
	/** Ce qui ne va pas dans l'écran ouvert (null si tout va bien). */
	private static String layoutProblem(Minecraft mc) {
		Screen s = mc.gui.screen();
		if (!(s instanceof HdScreen)) {
			return "pas un écran haute résolution : " + (s == null ? "aucun écran" : s.getClass().getSimpleName());
		}
		if (s.width != 427 || s.height != 240) {
			return "interface de " + s.width + " x " + s.height + " au lieu de 427 x 240";
		}
		List<AbstractWidget> widgets = new ArrayList<>();
		for (GuiEventListener child : s.children()) {
			if (child instanceof AbstractWidget w && w.visible) {
				widgets.add(w);
			}
		}
		for (AbstractWidget w : widgets) {
			String name = w.getClass().getSimpleName() + " « " + w.getMessage().getString() + " »";
			if (w instanceof TokenWidget || w instanceof EditBox || w instanceof WaxButton && !(w instanceof HdButton)) {
				return "élément de l'ancienne interface : " + name;
			}
			if (w.getX() < 0 || w.getY() < 0 || w.getRight() > s.width || w.getBottom() > s.height) {
				return name + " sort de la fenêtre (" + w.getX() + ", " + w.getY() + ", " + w.getRight() + ", " + w.getBottom() + ")";
			}
		}
		for (int i = 0; i < widgets.size(); i++) {
			for (int j = i + 1; j < widgets.size(); j++) {
				AbstractWidget a = widgets.get(i);
				AbstractWidget b = widgets.get(j);
				if (a instanceof HdButton && b instanceof HdButton && a.getX() < b.getRight() && b.getX() < a.getRight()
						&& a.getY() < b.getBottom() && b.getY() < a.getBottom()) {
					return "boutons qui se chevauchent : « " + a.getMessage().getString() + " » et « " + b.getMessage().getString() + " »";
				}
			}
		}
		return null;
	}

	/** Applique une vue, ouvre l'écran, le contrôle et le capture. */
	private static void screen(ClientGameTestContext ctx, JsonObject view, Supplier<Screen> screen, String name) {
		ctx.runOnClient(mc -> ClientState.apply(view.toString()));
		ctx.setScreen(screen);
		ctx.waitTicks(5);
		String problem = ctx.computeOnClient(PlayerScreensHdGameTest::layoutProblem);
		check(problem == null, name + " : " + problem);
		shot(ctx, name);
	}

	/** Le champ de note du carnet pour ce joueur, dans l'écran ouvert. */
	private static HdTextField noteField(Minecraft mc, String player) {
		if (mc.gui.screen() == null) {
			return null;
		}
		for (GuiEventListener w : mc.gui.screen().children()) {
			if (w instanceof HdTextField f && ("carnet.note." + player).equals(f.key())) {
				return f;
			}
		}
		return null;
	}

	private static boolean press(Minecraft mc, String label) {
		if (mc.gui.screen() == null) {
			return false;
		}
		for (GuiEventListener w : mc.gui.screen().children()) {
			if (w instanceof HdButton b && b.active && b.getMessage().getString().equals(label)) {
				b.press();
				return true;
			}
		}
		return false;
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			ctx.waitTicks(60);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			world.getServer().runCommand("op " + me);
			ctx.waitTicks(40);
			// Interface de 427 x 240 : la taille de référence des écrans du mod.
			int initialScale = ctx.computeOnClient(mc -> mc.options.guiScale().get());
			ctx.getInput().resizeWindow(854, 480);
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(2);
				mc.resizeGui();
			});
			ctx.waitTicks(10);
			// La vue réelle n'est renvoyée par le serveur que si elle change : on la garde pour la remettre à la fin.
			String real = ctx.computeOnClient(mc -> ClientState.view().toString());

			// 1. Au salon
			JsonObject lobby = lobbyView(me);
			screen(ctx, lobby, MainScreen::new, "1-salon");
			screen(ctx, lobby, SheetsScreen::new, "2-fiches");
			screen(ctx, lobby, PrefsScreen::new, "3-preferences");
			screen(ctx, lobby, ChronicleScreen::new, "4-chronique");
			screen(ctx, lobby, FiguresScreen::new, "5-figures");
			screen(ctx, lobby, SignScreen::new, "6-signes");
			screen(ctx, lobby, ClaimScreen::new, "7-annoncer");
			screen(ctx, lobby, () -> new NotebookGuessScreen("Bastien"), "8-carnet-role-prete");

			// Le carnet : la note tapée est enregistrée, et survit à une nouvelle vue du serveur (écran reconstruit).
			screen(ctx, lobby, NotebookScreen::new, "9-carnet");
			check(ctx.computeOnClient(mc -> press(mc, "Tout effacer")), "bouton « Tout effacer » du carnet");
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> mc.gui.screen().setFocused(noteField(mc, "Bastien")));
			ctx.getInput().typeChars("Ment sur son rôle");
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> ClientState.apply(lobby.toString()));
			ctx.waitTicks(5);
			check(ctx.computeOnClient(mc -> {
				HdTextField f = noteField(mc, "Bastien");
				return f != null && "Ment sur son rôle".equals(f.value()) && mc.gui.screen().getFocused() == f;
			}), "note et focus gardés après une nouvelle vue");
			shot(ctx, "9b-carnet-note");
			ctx.setScreen(NotebookScreen::new);
			ctx.waitTicks(5);
			check(ctx.computeOnClient(mc -> {
				HdTextField f = noteField(mc, "Bastien");
				return f != null && "Ment sur son rôle".equals(f.value());
			}), "note retrouvée en rouvrant le carnet");
			check(ctx.computeOnClient(mc -> press(mc, "Tout effacer")), "carnet remis à zéro");
			ctx.waitTicks(5);

			// 2. Le jour, pendant un vote ; la carte de rôle tombe sur l'affichage en jeu (nouveau rôle).
			JsonObject day = dayView(me);
			ctx.setScreen(() -> null);
			ctx.runOnClient(mc -> ClientState.apply(day.toString()));
			ctx.setScreen(() -> null);
			ctx.waitTicks(26);
			shot(ctx, "10-hud-carte");
			ctx.waitTicks(130);
			shot(ctx, "11-hud-vote");
			screen(ctx, day, MainScreen::new, "12-menu-jour");
			screen(ctx, day, () -> new PickerScreen("Nominer qui ?", "vc nominer "), "13-nominer");
			screen(ctx, day, PromptScreen::new, "14-invite-vote");

			// 3. La nuit : invites du joueur, puis pupitre du Conteur.
			screen(ctx, nightView(me, choosePrompt()), PromptScreen::new, "15-invite-choix");
			screen(ctx, nightView(me, obj("type", "info", "title", "La Commère", "text",
				"Un de tes deux voisins vivants est du mauvais côté. Méfie-toi de Bastien et de Léandre, qui se taisent depuis l'aube.")),
				PromptScreen::new, "16-invite-info");
			List<String> options = List.of("Garder le pouvoir pour une autre nuit", "Empoisonner Aurore", "Empoisonner Bastien",
				"Empoisonner Clémence", "Empoisonner Dorian", "Empoisonner Éloïse", "Empoisonner Faustine", "Ne rien faire");
			screen(ctx, nightView(me, obj("type", "decide", "title", "L'Alchimiste", "question",
				"Quel breuvage verses-tu cette nuit ? Le poison rend fausse l'information de sa victime jusqu'au soir.",
				"options", arr(options))), PromptScreen::new, "17-invite-decision");
			screen(ctx, nightView(me, obj("type", "confirm", "title", "Relecture", "to", "Bastien",
				"text", "Tu apprends que Dorian ou Gaspard est l'Astrologue.", "truth", "Dorian est l'Astrologue ; Bastien est ivre.")),
				PromptScreen::new, "18-invite-relecture");
			screen(ctx, nightView(me, obj("type", "guess", "title", "Le Jongleur", "count", 5, "text",
				"Prête un rôle à cinq joueurs au plus. La nuit prochaine, tu apprendras combien de tes hypothèses sont justes.",
				"players", arr(List.of(NAMES)), "roles", scriptRoles())), GuessScreen::new, "19-jongleur");
			JsonObject night = nightView(me, null);
			screen(ctx, night, NightScreen::new, "20-nuit-pas-a-pas");
			screen(ctx, night, () -> new TokenScreen("Bastien", true), "21-rappels");
			screen(ctx, night, () -> new RolePickerScreen("Bastien", null), "22-choix-role");
			ctx.setScreen(() -> null);
			shot(ctx, "23-hud-nuit");

			// Retour à la vue du serveur et aux réglages du départ.
			ctx.runOnClient(mc -> ClientState.apply(real));
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(initialScale);
				mc.resizeGui();
			});
			ctx.setScreen(() -> null);
			ctx.waitTicks(10);
		}
	}
}
