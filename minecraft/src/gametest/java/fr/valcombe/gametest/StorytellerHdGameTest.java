package fr.valcombe.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdTabs;
import fr.valcombe.client.hd.HdToggle;
import fr.valcombe.client.hd.HdToken;
import fr.valcombe.client.screen.BaseScreen;
import fr.valcombe.client.screen.DistributionScreen;
import fr.valcombe.client.screen.DurationsScreen;
import fr.valcombe.client.screen.OrderScreen;
import fr.valcombe.client.screen.ScenarioScreen;
import fr.valcombe.client.screen.ScriptScreen;
import fr.valcombe.client.screen.SeatScreen;
import fr.valcombe.client.screen.StorytellerScreen;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

/**
 * Les écrans du Conteur en haute résolution (pupitre, grimoire, fiche d'un siège, script, scénarios, durées, ordre
 * de réveil, distribution), dans la plus petite interface prévue : 427 x 240 (fenêtre de 854 x 480 à l'échelle 2).
 * Chaque écran est ouvert sur une vue de partie fabriquée ici (salon, lancement, journée chargée, nuit, 20 joueurs)
 * pour couvrir les cas les plus remplis ; on vérifie que tout widget visible tient dans le panneau sans en
 * chevaucher un autre, que les libellés attendus sont là, et l'on prend une capture de chacun. Le grimoire se
 * clique (fiche du siège), les onglets du pupitre changent la colonne d'actions, la colonne trop longue défile.
 */
public class StorytellerHdGameTest implements FabricClientGameTest {
	/** Noms de joueurs de longueurs variées (le plus long : 16 caractères, le maximum de Minecraft). */
	private static final String[] NAMES = {"Aubépine", "Bastien", "Clémence", "Dorian", "Éloïse", "Faustin",
		"MaximilienDeTour", "Gaspard", "Héloïse", "Isidore", "Joséphine", "Kilian", "Léontine", "Martin", "Noémie",
		"Octave", "Pernelle", "Quentin", "Rosalie", "Sidonie"};
	/** Rôles des sièges, bien et mal mêlés. */
	private static final String[] ROLES = {"COMMERE", "GOULE", "GREFFIER", "ALCHIMISTE", "INQUISITEUR", "SOMNAMBULE",
		"VEILLEUSE", "CHAPELAIN", "MOUCHARD", "TEMPLIER", "ECUYER", "CHATELAIN", "SONNEUR", "MARTYR"};

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
	}

	private static void shot(ClientGameTestContext ctx, String name) {
		ctx.waitTicks(10);
		System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-conteur-hd-" + name));
	}

	private static void scale(ClientGameTestContext ctx, int guiScale, int windowW, int windowH) {
		ctx.getInput().resizeWindow(windowW, windowH);
		ctx.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			mc.resizeGui();
		});
		ctx.waitTicks(10);
		int got = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
		check(got == guiScale, "échelle d'interface " + guiScale + " (obtenu " + got + ")");
	}

	// ------------------------------------------------------------------
	// Vues fabriquées (même forme que SessionView côté serveur)
	// ------------------------------------------------------------------
	private static JsonArray array(List<String> values) {
		JsonArray a = new JsonArray();
		values.forEach(a::add);
		return a;
	}

	private static List<String> names(int n) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(NAMES[i % NAMES.length]);
		}
		return out;
	}

	private static JsonObject roleView(String id) {
		Role r = Role.valueOf(id);
		JsonObject o = new JsonObject();
		o.addProperty("id", r.name());
		o.addProperty("name", r.displayName);
		o.addProperty("type", r.type.name());
		o.addProperty("team", Team.of(r).name());
		o.addProperty("text", r.text);
		return o;
	}

	/** Ce que toute vue d'un Conteur contient : script, réglages, scénarios, rôles créés (repris de la vraie vue). */
	private static JsonObject base(String me, String phase, int lobby) {
		JsonObject v = new JsonObject();
		v.addProperty("me", me);
		v.addProperty("leader", true);
		v.addProperty("storyteller", me);
		v.addProperty("isStoryteller", true);
		v.addProperty("mode", "HUMAIN");
		v.addProperty("phase", phase);
		v.addProperty("paused", false);
		v.add("lobby", array(names(lobby)));
		JsonElement custom = ClientState.view().get("customRoles");
		v.add("customRoles", custom != null ? custom.deepCopy() : new JsonArray());
		JsonObject script = new JsonObject();
		script.addProperty("name", "Les Cendres de Valcombe");
		List<String> all = Role.all().stream().map(Role::name).toList();
		script.add("roles", array(all.subList(0, Math.min(all.size(), 24))));
		script.add("saved", array(List.of("Les Cendres de Valcombe", "Soirée du vendredi", "Débutants")));
		JsonObject groups = new JsonObject();
		groups.add("Sans la Goule", array(all.stream().filter(r -> !r.equals("GOULE")).toList()));
		groups.add("Nuit calme", array(List.of("COMMERE", "GREFFIER", "VEILLEUSE", "GOULE")));
		script.add("groups", groups);
		script.addProperty("problem", "Il manque un Étranger pour 12 joueurs : ajoutez-en un au script.");
		v.add("script", script);
		JsonObject st = new JsonObject();
		st.addProperty("manual", false);
		st.addProperty("autoNight", true);
		st.addProperty("discussionMinutes", 5);
		st.addProperty("councilSeconds", 120);
		st.addProperty("defenseSeconds", 20);
		st.addProperty("clockSeconds", 1.5);
		st.addProperty("stepDelaySeconds", 1.5);
		st.addProperty("speechSeconds", 45);
		st.addProperty("duskSeconds", 10);
		v.add("settings", st);
		JsonArray scenarios = new JsonArray();
		for (int i = 1; i <= 8; i++) {
			JsonObject sc = new JsonObject();
			sc.addProperty("id", "scenario-" + i);
			sc.addProperty("nom", i == 1 ? "Le Bal masqué de la Saint-Jean" : "Soirée " + i);
			sc.addProperty("joueurs", "5 à " + (8 + i));
			sc.addProperty("carte", i % 2 == 0 ? "village-test" : "");
			scenarios.add(sc);
		}
		v.add("scenarios", scenarios);
		v.addProperty("mapInPlace", "village");
		JsonObject presets = new JsonObject();
		presets.addProperty(NAMES[1], "SOMNAMBULE/COMMERE");
		presets.addProperty(NAMES[2], "GOULE");
		v.add("presets", presets);
		return v;
	}

	private static JsonObject lobbyView(String me) {
		JsonObject v = base(me, "LOBBY", 12);
		v.addProperty("lobbyStatus", "Prêt à lancer : 12 joueurs, dont 2 Étrangers, 2 Sbires et le Démon.");
		v.addProperty("lobbyReady", true);
		return v;
	}

	/** Une partie en cours de n joueurs ; busy : journée chargée (vote, minuteur, billot, convocation). */
	private static JsonObject gameView(String me, String phase, int n, boolean busy) {
		JsonObject v = base(me, phase, n);
		v.addProperty("night", 2);
		v.addProperty("day", 2);
		v.addProperty("dayOver", false);
		List<String> names = names(n);
		JsonArray seats = new JsonArray();
		JsonArray grim = new JsonArray();
		for (int i = 0; i < n; i++) {
			String name = names.get(i);
			boolean alive = i % 5 != 3;
			JsonObject s = new JsonObject();
			s.addProperty("seat", i);
			s.addProperty("name", name);
			s.addProperty("alive", alive);
			s.addProperty("ghostVote", true);
			s.addProperty("standIn", false);
			s.addProperty("traveller", false);
			seats.add(s);
			JsonObject role = roleView(ROLES[i % ROLES.length]);
			JsonObject row = new JsonObject();
			row.addProperty("seat", i);
			row.addProperty("name", name);
			row.add("role", role);
			row.addProperty("alive", alive);
			row.add("believed", i == 5 ? roleView("COMMERE") : null);
			row.addProperty("line", name + " : " + role.get("name").getAsString() + (alive ? "" : " (mort)"));
			if (i == 7) {
				row.addProperty("note", "Surveiller ses votes, il a nommé deux fois la même personne hier.");
				row.add("reminders", array(List.of("ivre", "protégé", "a voté")));
			} else {
				row.add("reminders", new JsonArray());
			}
			row.addProperty("poisoned", i == 4);
			row.addProperty("protected", i == 6);
			row.addProperty("redHerring", i == 2);
			row.addProperty("master", false);
			grim.add(row);
		}
		v.add("seats", seats);
		v.add("grimoire", grim);
		v.add("bluffs", array(List.of("GREFFIER", "VEILLEUSE", "TEMPLIER")));
		JsonObject pol = new JsonObject();
		pol.addProperty("misregister", "HASARD");
		pol.addProperty("mayorTarget", names.get(7));
		pol.addProperty("successor", (String) null);
		v.add("policies", pol);
		v.addProperty("gameNote", "Bastien parle beaucoup, Clémence se tait.");
		JsonArray plan = new JsonArray();
		for (int i = 0; i < 14; i++) {
			JsonObject p = new JsonObject();
			Role r = Role.valueOf(ROLES[i]);
			p.addProperty("role", r.name());
			p.addProperty("title", r.displayName);
			p.addProperty("player", i % 4 == 3 ? "" : names.get(i % n));
			plan.add(p);
		}
		v.add("plan", plan);
		v.add("absents", array(List.of(names.get(7))));
		v.add("awake", new JsonArray());
		v.add("answers", new JsonObject());
		v.addProperty("waitingNext", false);
		v.addProperty("waitingNextVote", false);
		v.addProperty("council", false);
		v.addProperty("councilVoting", false);
		v.addProperty("broadcast", true);
		if (phase.equals("READY")) {
			v.add("unbriefed", array(names.subList(2, n)));
			v.addProperty("distWarning", "Attention : la Goule n'a pas de Sbire capable de lui succéder.");
			v.addProperty("distributionWarning", "Attention : la Goule n'a pas de Sbire capable de lui succéder.");
		}
		if (busy) {
			v.addProperty("summoned", names.get(1));
			v.addProperty("nominationsOpen", true);
			JsonObject vote = new JsonObject();
			vote.addProperty("stage", "DEFENSE");
			vote.addProperty("nominator", names.get(0));
			vote.addProperty("nominee", names.get(2));
			v.add("vote", vote);
			JsonObject timer = new JsonObject();
			timer.addProperty("kind", "Défense");
			timer.addProperty("left", 15);
			timer.addProperty("held", false);
			v.add("timer", timer);
			JsonObject today = new JsonObject();
			today.addProperty("block", names.get(2));
			v.add("today", today);
		}
		return v;
	}

	// ------------------------------------------------------------------
	// Vérifications de l'écran ouvert
	// ------------------------------------------------------------------
	private static int field(Screen s, String name) {
		try {
			var f = BaseScreen.class.getDeclaredField(name);
			f.setAccessible(true);
			return f.getInt(s);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static List<AbstractWidget> shownWidgets(Screen s) {
		List<AbstractWidget> out = new ArrayList<>();
		for (GuiEventListener child : s.children()) {
			if (child instanceof AbstractWidget w && w.visible) {
				out.add(w);
			}
		}
		return out;
	}

	/**
	 * Ce qui ne va pas dans la mise en page de l'écran ouvert (null si rien) : un widget visible hors du panneau, ou
	 * deux qui se chevauchent (deux jetons du grimoire peuvent se frôler d'une unité).
	 */
	private static String layoutProblem(Minecraft mc) {
		Screen s = mc.gui.screen();
		if (!(s instanceof HdScreen)) {
			return "pas un écran haute résolution : " + s;
		}
		int px = field(s, "px");
		int py = field(s, "py");
		int pw = field(s, "pw");
		int ph = field(s, "ph");
		if (px < 0 || py - 9 < 0 || px + pw > s.width || py + ph > s.height) {
			return "panneau hors de l'écran (" + px + ", " + py + ", " + pw + " x " + ph + ") dans " + s.width + " x " + s.height;
		}
		List<AbstractWidget> ws = shownWidgets(s);
		for (AbstractWidget w : ws) {
			if (w.getX() < px + 4 || w.getY() < py + 4 || w.getRight() > px + pw - 4 || w.getBottom() > py + ph - 4) {
				return "« " + w.getMessage().getString() + " » sort du panneau (" + w.getX() + ", " + w.getY() + ", "
					+ w.getWidth() + " x " + w.getHeight() + ")";
			}
		}
		for (int i = 0; i < ws.size(); i++) {
			for (int j = i + 1; j < ws.size(); j++) {
				AbstractWidget a = ws.get(i);
				AbstractWidget b = ws.get(j);
				int slack = a instanceof HdToken && b instanceof HdToken ? 1 : 0;
				boolean overlap = a.getX() + slack < b.getRight() - slack && b.getX() + slack < a.getRight() - slack
					&& a.getY() + slack < b.getBottom() - slack && b.getY() + slack < a.getBottom() - slack;
				if (overlap) {
					return "« " + a.getMessage().getString() + " » chevauche « " + b.getMessage().getString() + " »";
				}
			}
		}
		return null;
	}

	/** Libellés des boutons de l'écran (cachés entre parenthèses), pour dire ce qui manque. */
	private static String buttons(Minecraft mc) {
		StringBuilder out = new StringBuilder();
		for (GuiEventListener w : mc.gui.screen().children()) {
			if (w instanceof HdButton b) {
				out.append(b.visible ? "[" : "(").append(b.getMessage().getString()).append(b.visible ? "] " : ") ");
			}
		}
		return out.toString();
	}

	private static HdButton find(Minecraft mc, String label) {
		if (mc.gui.screen() == null) {
			return null;
		}
		for (GuiEventListener w : mc.gui.screen().children()) {
			if (w instanceof HdButton b && b.getMessage().getString().equals(label)) {
				return b;
			}
		}
		return null;
	}

	private static long count(Minecraft mc, Class<?> type) {
		return mc.gui.screen().children().stream().filter(type::isInstance).count();
	}

	/** Vérifie la mise en page de l'écran ouvert et la présence des libellés, puis le capture. */
	private static void verify(ClientGameTestContext ctx, String name, String... labels) {
		ctx.waitTicks(3);
		String problem = ctx.computeOnClient(StorytellerHdGameTest::layoutProblem);
		check(problem == null, name + " : " + problem);
		for (String label : labels) {
			check(ctx.computeOnClient(mc -> find(mc, label) != null),
				name + " : bouton « " + label + " » parmi " + ctx.computeOnClient(StorytellerHdGameTest::buttons));
		}
		shot(ctx, name);
	}

	/** Applique la vue (comme si le serveur l'envoyait), ouvre l'écran, le vérifie et le capture. */
	private static void show(ClientGameTestContext ctx, JsonObject view, Supplier<Screen> screen, String name,
			String... labels) {
		String json = view.toString();
		ctx.runOnClient(mc -> {
			ClientState.apply(json);
			mc.gui.setScreen(screen.get());
		});
		verify(ctx, name, labels);
	}

	/** Remet le pupitre sur l'onglet du moment de la partie (l'onglet choisi est gardé d'une ouverture à l'autre). */
	private static void resetTab(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			try {
				var tab = StorytellerScreen.class.getDeclaredField("tab");
				tab.setAccessible(true);
				tab.setInt(null, -1);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			// Pas d'opérateur : sa vue (Atelier compris) changerait plus souvent et viendrait remplacer celles du test.
			// Hors partie, la vue d'un simple joueur ne change pas : le serveur ne renvoie rien tant qu'on n'agit pas.
			ctx.waitTicks(100);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			int initialScale = ctx.computeOnClient(mc -> mc.options.guiScale().get());
			scale(ctx, 2, 854, 480);
			int[] size = ctx.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
			check(size[0] == 427 && size[1] == 240, "interface de 427 x 240 (obtenu " + size[0] + " x " + size[1] + ")");

			// 1. Salon : pupitre (inscrits, actions), script, scénarios, durées, distribution.
			resetTab(ctx);
			JsonObject lobby = lobbyView(me);
			show(ctx, lobby, StorytellerScreen::new, "1-pupitre-salon",
				"Éditer le script", "Bâtir le village", "Lancer (automatique)", "Durées et minuteurs…", "Retour");
			show(ctx, lobby, ScriptScreen::new, "2-script", "Cendres", "Brumes", "Enregistrer sous…", "Scénarios…",
				"Seul", "Retour");
			check(ctx.computeOnClient(mc -> count(mc, HdToken.class)) == Role.all().size(), "un jeton par rôle du script");
			show(ctx, lobby, ScenarioScreen::new, "3-scenarios", "Sans scénario", "Mode construction", "Retour");
			// Huit scénarios : la liste défile à la molette jusqu'au dernier.
			ctx.runOnClient(mc -> {
				for (int i = 0; i < 10; i++) {
					mc.gui.screen().mouseScrolled(200, 100, 0, -1);
				}
			});
			check(ctx.computeOnClient(mc -> find(mc, "Soirée 8  (5 à 16 joueurs)") != null), "dernier scénario atteint à la molette");
			verify(ctx, "3b-scenarios-fin-de-liste");
			show(ctx, lobby, DurationsScreen::new, "4-durees", "Mode manuel : non", "Nuit auto (sans Conteur) : oui", "−", "+",
				"Retour");
			check(ctx.computeOnClient(mc -> find(mc, "Mode manuel : non") instanceof HdToggle), "interrupteur du mode manuel");
			show(ctx, lobby, DistributionScreen::new, "5-distribution-salon", "Au hasard", "Retour");

			// 2. Lancement (Conteur humain) : pupitre à l'onglet Nuit avec l'avertissement, distribution, fiche.
			JsonObject ready = gameView(me, "READY", 12, false);
			show(ctx, ready, StorytellerScreen::new, "6-pupitre-lancement", "Distribution des rôles…", "Faire tomber la nuit",
				"Retour");
			check(ctx.computeOnClient(mc -> count(mc, HdToken.class)) == 12, "douze jetons au grimoire");
			show(ctx, ready, DistributionScreen::new, "7-distribution-lancement", "Retour");
			show(ctx, ready, () -> new SeatScreen(NAMES[5]), "8-siege-lancement", "Changer le rôle", "Note", "Retour");

			// 3. Journée chargée : la colonne d'actions ne tient pas à 427 x 240 et défile ; aucune ne se perd.
			JsonObject day = gameView(me, "DAY", 12, true);
			show(ctx, day, StorytellerScreen::new, "9-pupitre-jour", "Rassembler le village", "Retour");
			check(ctx.computeOnClient(mc -> find(mc, "Mettre en pause") != null && !find(mc, "Mettre en pause").visible),
				"journée chargée : « Mettre en pause » est plus bas dans la colonne");
			ctx.runOnClient(mc -> {
				HdButton first = find(mc, "Rassembler le village");
				double x = first.getX() + 10;
				double y = first.getY() + 5;
				for (int i = 0; i < 20; i++) {
					mc.gui.screen().mouseScrolled(x, y, 0, -1);
				}
			});
			check(ctx.computeOnClient(mc -> find(mc, "Mettre en pause").visible && !find(mc, "Rassembler le village").visible),
				"la molette fait défiler la colonne d'actions jusqu'en bas");
			verify(ctx, "9b-pupitre-jour-defile", "Ma voix à tous : oui");
			// Onglets : Partie (interrupteur « Rôles affichés »), Parole, Fin.
			for (int t : new int[] {0, 3, 4}) {
				ctx.runOnClient(mc -> mc.gui.screen().children().stream().filter(HdTabs.class::isInstance)
					.map(HdTabs.class::cast).findFirst().orElseThrow().choose(t));
				ctx.waitTicks(3);
				verify(ctx, "10-pupitre-onglet-" + t);
			}
			check(ctx.computeOnClient(mc -> find(mc, "Victoire du bien") != null), "onglet Fin");
			// Un clic sur un jeton du grimoire ouvre la fiche du joueur.
			ctx.runOnClient(mc -> mc.gui.screen().children().stream().filter(HdToken.class::isInstance)
				.map(HdToken.class::cast).findFirst().orElseThrow().press());
			ctx.waitTicks(3);
			check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof SeatScreen), "le jeton ouvre la fiche du siège");
			// Fiche la plus remplie : rôle du bien, absent, note, rappels, mourra pour le Châtelain.
			show(ctx, day, () -> new SeatScreen(NAMES[7]), "11-siege-jour", "Faux présage", "Tuer", "Empoisonner",
				"Lui donner la parole", "Le faire relayer", "Retour");
			show(ctx, day, DurationsScreen::new, "12-durees-minuteur", "Passer", "+30 s", "Arrêter", "Retour");

			// 4. Nuit : ordre de réveil (quatorze rôles, la liste défile) ; le pupitre revient à l'onglet du moment.
			resetTab(ctx);
			JsonObject night = gameView(me, "NIGHT", 12, false);
			show(ctx, night, OrderScreen::new, "13-ordre-reveil", "▲", "▼", "Retour");
			show(ctx, night, StorytellerScreen::new, "14-pupitre-nuit", "Nuit pas à pas…", "Étape précédente", "Retour");

			// 5. Vingt joueurs : le grimoire tient encore, jetons distincts.
			JsonObject crowd = gameView(me, "DAY", 20, false);
			show(ctx, crowd, StorytellerScreen::new, "15-pupitre-20-joueurs", "Retour");
			check(ctx.computeOnClient(mc -> count(mc, HdToken.class)) == 20, "vingt jetons au grimoire");

			// 6. Même interface de 427 x 240 en plus de pixels (échelle 3, fenêtre de 1281 x 720) : tout est plus net.
			scale(ctx, 3, 1281, 720);
			show(ctx, day, StorytellerScreen::new, "16-pupitre-echelle-3", "Retour");
			show(ctx, day, () -> new SeatScreen(NAMES[7]), "17-siege-echelle-3", "Retour");
			// Interface plus grande (640 x 360 : échelle 2 dans la même fenêtre) : panneaux à leur taille, la journée
			// chargée tient sans défiler, le grimoire grandit.
			scale(ctx, 2, 1281, 720);
			show(ctx, day, StorytellerScreen::new, "18-pupitre-grande-interface", "Retour");
			check(ctx.computeOnClient(mc -> find(mc, "Mettre en pause").visible), "grande interface : toute la colonne se voit");
			show(ctx, lobby, ScriptScreen::new, "19-script-grande-interface", "Retour");

			// Retour à l'état du départ : onglet du moment de la partie, réglages de la fenêtre.
			resetTab(ctx);
			ctx.setScreen(() -> null);
			ctx.getInput().resizeWindow(854, 480);
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(initialScale);
				mc.resizeGui();
			});
		}
	}
}
