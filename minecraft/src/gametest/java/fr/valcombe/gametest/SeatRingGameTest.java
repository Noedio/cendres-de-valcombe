package fr.valcombe.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdToken;
import fr.valcombe.client.hd.RingLayout;
import fr.valcombe.client.screen.BaseScreen;
import fr.valcombe.client.screen.MainScreen;
import fr.valcombe.client.screen.PickerScreen;
import fr.valcombe.client.screen.PromptScreen;
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
 * Le cercle des sièges (ticket ba331d) : menu J des joueurs, pupitre du Conteur, désignation d'un joueur, vote et
 * grimoire du Mouchard, avec 5, 13 et 20 joueurs aux noms longs (16 caractères, le maximum de Minecraft), dans
 * plusieurs tailles d'interface : 427 x 240 à l'échelle 2 (la référence), à l'échelle 3, à l'échelle 4, 640 x 360,
 * et 854 x 480 à l'échelle 1. Pour chaque écran, on vérifie :
 * <ul>
 * <li>que chaque joueur a son jeton posé par le cercle (RingLayout) ;</li>
 * <li>qu'aucune plaque de nom ne sort du panneau, n'en recouvre une autre, ne mord sur un autre jeton ni sur un
 * bouton ;</li>
 * <li>qu'un nom raccourci se lit en entier dans l'infobulle de son jeton ;</li>
 * <li>qu'aucun widget ne sort du panneau ni n'en chevauche un autre.</li>
 * </ul>
 * Captures valcombe-cercle-*.png. Les tailles de fenêtre que l'écran ne peut pas offrir (échelle refusée) sont
 * sautées et dites dans le journal du test.
 */
public class SeatRingGameTest implements FabricClientGameTest {
	/** Noms longs et ordinaires mêlés : la moitié fait 16 caractères. */
	private static final String[] NAMES = {"MaximilienDeTour", "Aurore", "xX_Dragon_Xx1234", "Bastien", "Bartholomew_1987",
		"Clémence", "AuroreDesCendres", "Dorian", "Le_Vieux_Grimoir", "Éloïse", "SuperMinecrafter", "Faustin",
		"Clementine_Lucas", "Gaspard", "HortenseDeLaTour", "Héloïse", "JosephineDuPuits", "Isidore", "WWWWWWWWWWWWWWWW",
		"Joséphine"};
	private static final String[] ROLES = {"COMMERE", "GOULE", "GREFFIER", "ALCHIMISTE", "INQUISITEUR", "SOMNAMBULE",
		"VEILLEUSE", "CHAPELAIN", "MOUCHARD", "TEMPLIER", "ECUYER", "CHATELAIN", "SONNEUR", "MARTYR"};

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
	}

	private static void shot(ClientGameTestContext ctx, String name) {
		ctx.waitTicks(10);
		System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-cercle-" + name));
	}

	/** Met la fenêtre et l'échelle ; faux (et rien de changé à vérifier) si l'écran ne peut pas les offrir. */
	private static boolean scale(ClientGameTestContext ctx, int guiScale, int windowW, int windowH) {
		ctx.getInput().resizeWindow(windowW, windowH);
		ctx.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			mc.resizeGui();
		});
		ctx.waitTicks(10);
		int got = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
		if (got != guiScale) {
			System.out.println("[valcombe-test] échelle " + guiScale + " dans " + windowW + " x " + windowH + " refusée (obtenu " + got
				+ ") : configuration sautée");
			return false;
		}
		return true;
	}

	// ------------------------------------------------------------------
	// Vues fabriquées
	// ------------------------------------------------------------------
	private static JsonArray array(List<String> values) {
		JsonArray a = new JsonArray();
		values.forEach(a::add);
		return a;
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

	private static List<String> names(String me, int n) {
		List<String> out = new ArrayList<>();
		out.add(me);
		for (int i = 0; out.size() < n; i++) {
			out.add(NAMES[i % NAMES.length]);
		}
		return out;
	}

	/**
	 * Le jour 2 d'une partie de n joueurs (moi au siège 0), vue d'un Conteur qui joue aussi (menu J et pupitre) :
	 * sièges, grimoire complet, invite donnée (ou aucune).
	 */
	private static JsonObject dayView(String me, int n, JsonObject prompt) {
		List<String> names = names(me, n);
		JsonObject v = new JsonObject();
		v.addProperty("me", me);
		v.addProperty("phase", "DAY");
		v.addProperty("day", 2);
		v.addProperty("night", 1);
		v.addProperty("dayOver", false);
		v.addProperty("mySeat", 0);
		v.addProperty("leader", true);
		v.addProperty("storyteller", me);
		v.addProperty("isStoryteller", true);
		v.addProperty("mode", "HUMAIN");
		v.addProperty("paused", false);
		v.addProperty("voice", "parler");
		v.addProperty("nominationsOpen", true);
		v.add("lobby", array(names));
		JsonElement custom = ClientState.view().get("customRoles");
		v.add("customRoles", custom != null ? custom.deepCopy() : new JsonArray());
		v.add("role", roleView("COMMERE"));
		JsonObject script = new JsonObject();
		script.addProperty("name", "Les Cendres de Valcombe");
		script.add("roles", array(Role.all().stream().map(Role::name).toList()));
		v.add("script", script);
		v.add("settings", new JsonObject());
		v.add("feed", array(List.of("MaximilienDeTour accuse xX_Dragon_Xx1234 ; le vote commence au trône de Bastien.")));
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
			seats.add(s);
			JsonObject role = roleView(i == 0 ? "COMMERE" : ROLES[i % ROLES.length]);
			JsonObject row = new JsonObject();
			row.addProperty("seat", i);
			row.addProperty("name", name);
			row.add("role", role);
			row.addProperty("alive", alive);
			row.addProperty("line", name + " : " + role.get("name").getAsString() + (alive ? "" : " (mort)"));
			row.add("reminders", new JsonArray());
			row.addProperty("poisoned", i == 4);
			row.addProperty("protected", i == 6);
			row.addProperty("redHerring", i == 2);
			grim.add(row);
		}
		v.add("seats", seats);
		v.add("grimoire", grim);
		v.add("bluffs", array(List.of("GREFFIER", "VEILLEUSE", "TEMPLIER")));
		JsonObject vote = new JsonObject();
		vote.addProperty("stage", "VOTE");
		vote.addProperty("nominee", names.get(2 % n));
		vote.addProperty("count", 3);
		vote.addProperty("threshold", n / 2 + 1);
		vote.addProperty("clockSeat", Math.min(n - 1, 4));
		vote.addProperty("clockPos", 4);
		vote.addProperty("canVote", true);
		vote.addProperty("myHand", true);
		vote.add("locked", array(List.of("1", "2", "3")));
		if (prompt != null) {
			v.add("vote", vote);
			v.add("prompt", prompt);
		}
		return v;
	}

	private static JsonObject votePrompt(String nominee) {
		JsonObject p = new JsonObject();
		p.addProperty("type", "vote");
		p.addProperty("nominee", nominee);
		p.addProperty("ghost", false);
		return p;
	}

	private static JsonObject grimoirePrompt() {
		JsonObject p = new JsonObject();
		p.addProperty("type", "grimoire");
		return p;
	}

	// ------------------------------------------------------------------
	// Vérifications
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

	private static boolean overlap(double ax, double ay, double aw, double ah, double bx, double by, double bw, double bh) {
		return ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah;
	}

	/** Ce qui ne va pas dans le cercle de l'écran ouvert (null si rien) ; seats : jetons du cercle attendus. */
	private static String ringProblem(Minecraft mc, int seats) {
		Screen s = mc.gui.screen();
		if (!(s instanceof HdScreen)) {
			return "pas un écran haute résolution : " + s;
		}
		int px = field(s, "px");
		int py = field(s, "py");
		int pw = field(s, "pw");
		int ph = field(s, "ph");
		List<HdToken> ring = new ArrayList<>();
		List<AbstractWidget> widgets = new ArrayList<>();
		for (GuiEventListener child : s.children()) {
			if (child instanceof AbstractWidget w && w.visible) {
				widgets.add(w);
				if (w instanceof HdToken t && t.onRing()) {
					ring.add(t);
				}
			}
		}
		if (ring.size() != seats) {
			return ring.size() + " jetons au cercle au lieu de " + seats;
		}
		for (AbstractWidget w : widgets) {
			if (w.getX() < px + 4 || w.getY() < py + 4 || w.getRight() > px + pw - 4 || w.getBottom() > py + ph - 4) {
				return "« " + w.getMessage().getString() + " » sort du panneau";
			}
		}
		for (int i = 0; i < widgets.size(); i++) {
			for (int j = i + 1; j < widgets.size(); j++) {
				AbstractWidget a = widgets.get(i);
				AbstractWidget b = widgets.get(j);
				if (a instanceof HdToken ta && b instanceof HdToken tb) {
					// Deux jetons sont des disques (leur survol aussi, voir HdToken.isMouseOver) : sur un cercle serré, leurs
					// carrés se croisent en biais sans que les disques se touchent.
					double ra = ta.getWidth() / 2.0;
					double rb = tb.getWidth() / 2.0;
					if (Math.hypot(ta.getX() + ra - tb.getX() - rb, ta.getY() + ra - tb.getY() - rb) < ra + rb) {
						return "les jetons de « " + a.getMessage().getString() + " » et de « " + b.getMessage().getString()
							+ " » se touchent";
					}
					continue;
				}
				if (overlap(a.getX(), a.getY(), a.getWidth(), a.getHeight(), b.getX(), b.getY(), b.getWidth(), b.getHeight())) {
					return "« " + a.getMessage().getString() + " » chevauche « " + b.getMessage().getString() + " »";
				}
			}
		}
		for (HdToken t : ring) {
			String name = t.getMessage().getString();
			RingLayout.Box p = t.plateBounds();
			if (!t.shownName().equals(name) && (t.hdTooltip() == null || !t.hdTooltip().contains(name))) {
				return "« " + t.shownName() + " » raccourci sans le nom entier en infobulle (" + name + ")";
			}
			if (p == null) {
				continue;
			}
			if (p.x() < px + 2 || p.y() < py + 2 || p.right() > px + pw - 2 || p.bottom() > py + ph - 2) {
				return "la plaque de " + name + " sort du panneau " + p;
			}
			for (AbstractWidget w : widgets) {
				if (w == t) {
					continue;
				}
				if (w instanceof HdToken o) {
					double r = o.getWidth() / 2.0;
					if (p.distanceTo(o.getX() + r, o.getY() + r) < r) {
						return "la plaque de " + name + " mord sur le jeton de " + o.getMessage().getString();
					}
					RingLayout.Box q = o.plateBounds();
					if (q != null && overlap(p.x(), p.y(), p.w(), p.h(), q.x(), q.y(), q.w(), q.h())) {
						return "les plaques de " + name + " et de " + o.getMessage().getString() + " se recouvrent";
					}
				} else if (overlap(p.x(), p.y(), p.w(), p.h(), w.getX(), w.getY(), w.getWidth(), w.getHeight())) {
					return "la plaque de " + name + " recouvre « " + w.getMessage().getString() + " »";
				}
			}
		}
		return null;
	}

	/** Applique la vue, ouvre l'écran, le vérifie et le capture. */
	private static void show(ClientGameTestContext ctx, JsonObject view, Supplier<Screen> screen, int seats, String name) {
		String json = view.toString();
		ctx.runOnClient(mc -> {
			ClientState.apply(json);
			mc.gui.setScreen(screen.get());
		});
		ctx.waitTicks(3);
		String problem = ctx.computeOnClient(mc -> ringProblem(mc, seats));
		check(problem == null, name + " : " + problem);
		shot(ctx, name);
	}

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
			// Pas d'opérateur : hors partie, la vue d'un simple joueur ne change pas et ne remplace pas celles du test.
			ctx.waitTicks(100);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			int initialScale = ctx.computeOnClient(mc -> mc.options.guiScale().get());
			String real = ctx.computeOnClient(mc -> ClientState.view().toString());
			resetTab(ctx);

			// Taille de référence (427 x 240) : tous les écrans à cercle, avec 5, 13 et 20 joueurs.
			check(scale(ctx, 2, 854, 480), "interface de référence (échelle 2 dans 854 x 480)");
			for (int n : new int[] {5, 13, 20}) {
				JsonObject day = dayView(me, n, null);
				show(ctx, day, MainScreen::new, n, "menu-j-" + n);
				show(ctx, day, StorytellerScreen::new, n, "pupitre-" + n);
				show(ctx, day, () -> new PickerScreen("Nominer qui ?", "vc nominer "), n, "nominer-" + n);
				show(ctx, dayView(me, n, votePrompt(names(me, n).get(2 % n))), PromptScreen::new, n, "vote-" + n);
				show(ctx, dayView(me, n, grimoirePrompt()), PromptScreen::new, n, "grimoire-" + n);
			}
			// Un nom raccourci se lit en entier : l'infobulle du jeton (ou de sa plaque) le donne.
			ctx.runOnClient(mc -> ClientState.apply(dayView(me, 20, null).toString()));
			ctx.setScreen(MainScreen::new);
			ctx.waitTicks(3);
			check(ctx.computeOnClient(mc -> mc.gui.screen().children().stream().filter(w -> w instanceof HdToken t && t.onRing())
				.map(w -> (HdToken) w).anyMatch(t -> !t.shownName().equals(t.getMessage().getString()))),
				"menu J à 20 joueurs aux noms longs : au moins un nom raccourci");

			// Les autres tailles : menu J et pupitre, avec 5, 13 et 20 joueurs.
			int[][] sizes = {{3, 1281, 720}, {4, 1708, 960}, {2, 1281, 720}, {1, 854, 480}};
			String[] tags = {"echelle-3", "echelle-4", "640x360", "echelle-1"};
			for (int k = 0; k < sizes.length; k++) {
				if (!scale(ctx, sizes[k][0], sizes[k][1], sizes[k][2])) {
					continue;
				}
				for (int n : new int[] {5, 13, 20}) {
					JsonObject day = dayView(me, n, null);
					show(ctx, day, MainScreen::new, n, "menu-j-" + n + "-" + tags[k]);
					show(ctx, day, StorytellerScreen::new, n, "pupitre-" + n + "-" + tags[k]);
				}
			}

			// Retour à l'état du départ.
			resetTab(ctx);
			ctx.setScreen(() -> null);
			ctx.runOnClient(mc -> ClientState.apply(real));
			ctx.getInput().resizeWindow(854, 480);
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(initialScale);
				mc.resizeGui();
			});
			ctx.waitTicks(10);
		}
	}
}
