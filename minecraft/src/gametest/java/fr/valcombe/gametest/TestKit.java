package fr.valcombe.gametest;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdGrid;
import fr.valcombe.client.hd.HdList;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdTabs;
import fr.valcombe.client.hd.HdTextField;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.games.Games;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Les aides communes des tests en jeu : commandes lancées comme un joueur (réponse comprise), attentes d'un état du
 * serveur ou du client, lecture du chat et des titres, partie principale remise à zéro, et la conduite des écrans haute
 * résolution par leurs vrais widgets (boutons, onglets, grilles, listes, champs de saisie, contrôle de mise en page).
 * <p>
 * Règles des tests en jeu, que ces aides suivent : rien n'attend le serveur depuis le fil du test autrement que par
 * {@code computeOnServer} / {@code runOnServer} ; une commande et la vérification de son effet se font dans le même tick
 * serveur ({@link #run}), ou en attendant l'état visé ({@link #waitFor}).
 */
final class TestKit {
	private TestKit() {
	}

	// ------------------------------------------------------------------
	// Vérifications
	// ------------------------------------------------------------------

	/** Échoue avec un message clair en français, ou écrit ce qui est vérifié dans le journal, sous ce nom de test. */
	static void check(String scope, boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
		System.out.println("[valcombe-test] " + scope + " : " + what);
	}

	// ------------------------------------------------------------------
	// Commandes
	// ------------------------------------------------------------------

	/** Une commande /vc envoyée par l'écran du mod (comme un bouton), puis dix ticks. */
	static void send(ClientGameTestContext ctx, String cmd) {
		ctx.runOnClient(mc -> ClientState.send(cmd));
		ctx.waitTicks(10);
	}

	/** Une commande tapée dans le chat (sans la première barre : « /set stone » pour //set stone), puis dix ticks. */
	static void type(ClientGameTestContext ctx, String command) {
		ctx.runOnClient(mc -> mc.player.connection.sendCommand(command));
		ctx.waitTicks(10);
	}

	/**
	 * Lance une commande au nom de ce joueur, là où il se trouve, et renvoie ce qu'elle a répondu : sur le fil du serveur,
	 * dans le tick où l'on regarde ensuite son effet (pas de course avec les ticks des parties).
	 */
	static String run(MinecraftServer s, ServerPlayer p, String cmd) {
		StringBuilder out = new StringBuilder();
		CommandSource capture = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				out.append(message.getString()).append(' ');
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		s.getCommands().performPrefixedCommand(p.createCommandSourceStack().withSource(capture), cmd);
		return out.toString().trim();
	}

	/** Une commande de la console, lancée depuis ce monde de Valcombe (dans ce tick serveur). */
	static void runIn(MinecraftServer s, String worldId, String cmd) {
		s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "execute in valcombe:" + worldId + " run " + cmd);
	}

	// ------------------------------------------------------------------
	// Attentes
	// ------------------------------------------------------------------

	/** Attend (au plus {@code ticks}) qu'une condition soit vraie sur le serveur, regardée tous les deux ticks. */
	static boolean waitFor(ClientGameTestContext ctx, TestSingleplayerContext world, int ticks, Predicate<MinecraftServer> cond) {
		return waitFor(ctx, world, ticks, 2, cond);
	}

	/** Attend (au plus {@code ticks}) qu'une condition soit vraie sur le serveur, regardée tous les {@code step} ticks. */
	static boolean waitFor(ClientGameTestContext ctx, TestSingleplayerContext world, int ticks, int step, Predicate<MinecraftServer> cond) {
		for (int i = 0; i < ticks; i += step) {
			if (world.getServer().computeOnServer(cond::test)) {
				return true;
			}
			ctx.waitTicks(step);
		}
		return world.getServer().computeOnServer(cond::test);
	}

	/** Attend (au plus {@code ticks}) qu'une condition soit vraie sur le client. */
	static boolean waitClient(ClientGameTestContext ctx, int ticks, Predicate<Minecraft> cond) {
		for (int i = 0; i < ticks; i += 2) {
			if (ctx.computeOnClient(cond::test)) {
				return true;
			}
			ctx.waitTicks(2);
		}
		return ctx.computeOnClient(cond::test);
	}

	// ------------------------------------------------------------------
	// Joueurs
	// ------------------------------------------------------------------

	static ServerPlayer player(MinecraftServer s, String name) {
		return s.getPlayerList().getPlayerByName(name);
	}

	/** Le monde où se trouve ce joueur (« valcombe:… »), ou une chaîne vide s'il n'est pas connecté. */
	static String dimensionOf(MinecraftServer s, String name) {
		ServerPlayer p = player(s, name);
		return p == null ? "" : p.level().dimension().identifier().toString();
	}

	/** Place le joueur ici, dans ce monde de Valcombe, côté serveur, regard compris. */
	static void place(TestSingleplayerContext world, String me, String worldId, double x, double y, double z, float yaw, float pitch) {
		world.getServer().runOnServer(s -> {
			ServerLevel level = ValcombeMod.worlds().level(worldId);
			player(s, me).teleportTo(level, x, y, z, java.util.Set.of(), yaw, pitch, false);
		});
	}

	// ------------------------------------------------------------------
	// Chat et titres
	// ------------------------------------------------------------------

	/** Le texte du chat du client (tous les messages reçus) : ses messages sont lus par réflexion. */
	static String chatText(Minecraft mc) {
		StringBuilder out = new StringBuilder();
		try {
			appendMessages(mc.gui.hud.getChat(), out);
		} catch (ReflectiveOperationException | RuntimeException e) {
			out.append("(chat illisible : ").append(e).append(')');
		}
		return out.toString();
	}

	/** Le titre et le sous-titre affichés en ce moment sur le client, lus par réflexion. */
	static String titleText(Minecraft mc) {
		StringBuilder out = new StringBuilder();
		for (Class<?> c = mc.gui.hud.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (var f : c.getDeclaredFields()) {
				if ((f.getName().equals("title") || f.getName().equals("subtitle")) && Component.class.isAssignableFrom(f.getType())) {
					try {
						f.setAccessible(true);
						Object v = f.get(mc.gui.hud);
						if (v instanceof Component comp) {
							out.append(comp.getString()).append(' ');
						}
					} catch (ReflectiveOperationException | RuntimeException e) {
						// champ inaccessible : ignoré
					}
				}
			}
		}
		return out.toString();
	}

	/** Combien de fois ce texte paraît dans le chat du client (chaque message est compté une fois par liste du chat). */
	static int chatCount(Minecraft mc, String part) {
		String text = chatText(mc);
		int n = 0;
		for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
			n++;
		}
		return n;
	}

	/** Les messages des listes du chat : chaque élément porte son texte dans un champ Component. */
	private static void appendMessages(Object chat, StringBuilder out) throws ReflectiveOperationException {
		for (Class<?> c = chat.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (var f : c.getDeclaredFields()) {
				if (!List.class.isAssignableFrom(f.getType()) || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
					continue;
				}
				f.setAccessible(true);
				Object list = f.get(chat);
				if (!(list instanceof List<?> items)) {
					continue;
				}
				for (Object item : List.copyOf(items)) {
					if (item == null) {
						continue;
					}
					for (var g : item.getClass().getDeclaredFields()) {
						if (Component.class.isAssignableFrom(g.getType()) && !java.lang.reflect.Modifier.isStatic(g.getModifiers())) {
							g.setAccessible(true);
							Object v = g.get(item);
							if (v instanceof Component comp) {
								out.append(comp.getString()).append('\n');
							}
						}
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Parties
	// ------------------------------------------------------------------

	/** Les parties survivent d'une classe de test à l'autre (sauvegardes) : on repart d'une partie principale libre. */
	static void cleanMain(TestSingleplayerContext world, String me) {
		cleanMain(world, me, false);
	}

	/** Comme {@link #cleanMain(TestSingleplayerContext, String)} ; {@code watching} : le joueur quitte aussi ses gradins. */
	static void cleanMain(TestSingleplayerContext world, String me, boolean watching) {
		world.getServer().runOnServer(s -> {
			var session = Games.main().session();
			if (session.game() != null) {
				session.stop();
			}
			if (me.equals(session.storyteller())) {
				session.toggleStoryteller(me);
			}
			if (session.lobby().contains(me)) {
				session.leave(me);
			}
			if (watching) {
				session.stopWatching(me, true);
			}
			session.removeBots();
		});
	}

	// ------------------------------------------------------------------
	// Écrans haute résolution
	// ------------------------------------------------------------------

	/** Le bouton visible de l'écran ouvert qui porte exactement ce libellé, ou null. */
	static HdButton hdButton(Minecraft mc, String label) {
		if (mc.gui.screen() == null) {
			return null;
		}
		for (GuiEventListener w : mc.gui.screen().children()) {
			if (w instanceof HdButton b && b.visible && b.getMessage().getString().equals(label)) {
				return b;
			}
		}
		return null;
	}

	/** Le bouton visible de l'écran ouvert dont le libellé commence ainsi (« Versions (3)… »), ou null. */
	static HdButton hdButtonStarting(Minecraft mc, String prefix) {
		if (mc.gui.screen() == null) {
			return null;
		}
		for (GuiEventListener w : mc.gui.screen().children()) {
			if (w instanceof HdButton b && b.visible && b.getMessage().getString().startsWith(prefix)) {
				return b;
			}
		}
		return null;
	}

	/** Les libellés des boutons de l'écran ouvert ([actif], (grisé)), pour dire ce qui manque quand un clic échoue. */
	static String buttons(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			StringBuilder out = new StringBuilder();
			if (mc.gui.screen() != null) {
				for (var w : mc.gui.screen().children()) {
					if (w instanceof HdButton b) {
						out.append(b.active ? "[" : "(").append(b.getMessage().getString()).append(b.active ? "] " : ") ");
					}
				}
			}
			return out.toString();
		});
	}

	/** Clique le bouton qui porte ce libellé, s'il est là et actif ; faux sinon. N'attend pas. */
	static boolean pressHd(ClientGameTestContext ctx, String label) {
		return ctx.computeOnClient(mc -> {
			HdButton b = hdButton(mc, label);
			if (b == null || !b.active) {
				return false;
			}
			b.press();
			return true;
		});
	}

	/** Clique le bouton qui porte ce libellé puis laisse douze ticks à l'écran ; faux s'il n'y est pas ou s'il est grisé. */
	static boolean clickHd(ClientGameTestContext ctx, String label) {
		boolean done = pressHd(ctx, label);
		ctx.waitTicks(12);
		return done;
	}

	/** Le bouton est là, grisé. */
	static boolean greyed(ClientGameTestContext ctx, String label) {
		return ctx.computeOnClient(mc -> {
			HdButton b = hdButton(mc, label);
			return b != null && !b.active;
		});
	}

	/** Le bouton est là, actif et mis en avant (l'action suivante). */
	static boolean primary(ClientGameTestContext ctx, String label) {
		return ctx.computeOnClient(mc -> {
			HdButton b = hdButton(mc, label);
			return b != null && b.active && b.isPrimary();
		});
	}

	/** Choisit le premier onglet dont le libellé commence ainsi ; faux s'il n'y en a pas. N'attend pas. */
	static boolean chooseTab(ClientGameTestContext ctx, String label) {
		return ctx.computeOnClient(mc -> {
			for (var w : mc.gui.screen().children()) {
				if (w instanceof HdTabs t) {
					for (int i = 0; i < t.labels().size(); i++) {
						if (t.labels().get(i).startsWith(label)) {
							t.choose(i);
							return true;
						}
					}
				}
			}
			return false;
		});
	}

	/** La grille de vignettes de l'écran ouvert, ou null. */
	@SuppressWarnings("unchecked")
	static HdGrid<JsonObject> grid(Minecraft mc) {
		if (mc.gui.screen() == null) {
			return null;
		}
		for (var w : mc.gui.screen().children()) {
			if (w instanceof HdGrid<?> g) {
				return (HdGrid<JsonObject>) g;
			}
		}
		return null;
	}

	/** Ouvre la case de la grille qui porte ce nom (comme un clic), puis dix ticks ; faux si elle n'y est pas. */
	static boolean openInGrid(ClientGameTestContext ctx, String name) {
		boolean ok = ctx.computeOnClient(mc -> {
			HdGrid<JsonObject> g = grid(mc);
			return g != null && g.open(name);
		});
		ctx.waitTicks(10);
		return ok;
	}

	/** Donne ce texte au premier champ de saisie de l'écran (sans passer par le clavier) ; faux s'il n'y en a pas. */
	static boolean typeIn(ClientGameTestContext ctx, String text) {
		return ctx.computeOnClient(mc -> {
			for (var w : mc.gui.screen().children()) {
				if (w instanceof HdTextField f) {
					f.value(text);
					return true;
				}
			}
			return false;
		});
	}

	/** Tape ce texte au clavier dans le premier champ de saisie de l'écran (il prend le focus) ; faux s'il n'y en a pas. */
	static boolean typeKeys(ClientGameTestContext ctx, String text) {
		boolean found = ctx.computeOnClient(mc -> {
			for (var w : mc.gui.screen().children()) {
				if (w instanceof HdTextField f) {
					mc.gui.screen().setFocused(f);
					return true;
				}
			}
			return false;
		});
		if (found) {
			ctx.getInput().typeChars(text);
			ctx.waitTicks(6);
		}
		return found;
	}

	/** Choisit dans la liste de l'écran la ligne dont ce champ vaut ce texte, puis huit ticks ; faux si elle n'y est pas. */
	static boolean chooseRow(ClientGameTestContext ctx, String key, String value) {
		boolean ok = ctx.computeOnClient(mc -> {
			for (var w : mc.gui.screen().children()) {
				if (w instanceof HdList<?> l) {
					for (int i = 0; i < l.items().size(); i++) {
						if (l.items().get(i) instanceof JsonObject o && value.equals(ClientState.str(o, key))) {
							l.choose(i);
							return true;
						}
					}
				}
			}
			return false;
		});
		ctx.waitTicks(8);
		return ok;
	}

	private static int field(Screen s, String name) {
		try {
			var f = fr.valcombe.client.screen.BaseScreen.class.getDeclaredField(name);
			f.setAccessible(true);
			return f.getInt(s);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Un widget visible hors du panneau, ou deux qui se chevauchent ; null si la mise en page est bonne. {@code detailed} :
	 * la position et la taille du widget qui sort sont dites.
	 */
	static String layoutProblem(Minecraft mc, boolean detailed) {
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
		List<AbstractWidget> ws = new ArrayList<>();
		for (GuiEventListener child : s.children()) {
			if (child instanceof AbstractWidget w && w.visible) {
				ws.add(w);
			}
		}
		for (AbstractWidget w : ws) {
			if (w.getX() < px + 4 || w.getY() < py + 4 || w.getRight() > px + pw - 4 || w.getBottom() > py + ph - 4) {
				return "« " + w.getMessage().getString() + " » sort du panneau" + (detailed ? " (" + w.getX() + ", " + w.getY() + ", "
					+ w.getWidth() + " x " + w.getHeight() + ")" : "");
			}
		}
		for (int i = 0; i < ws.size(); i++) {
			for (int j = i + 1; j < ws.size(); j++) {
				AbstractWidget a = ws.get(i);
				AbstractWidget b = ws.get(j);
				if (a instanceof fr.valcombe.client.hd.HdToken && b instanceof fr.valcombe.client.hd.HdToken) {
					// Deux jetons sont des disques (leur survol aussi, voir HdToken.isMouseOver) : sur un cercle serré, leurs
					// carrés se croisent en biais sans que les disques se touchent.
					double ra = a.getWidth() / 2.0;
					double rb = b.getWidth() / 2.0;
					if (Math.hypot(a.getX() + ra - b.getX() - rb, a.getY() + ra - b.getY() - rb) < ra + rb) {
						return "les jetons de « " + a.getMessage().getString() + " » et de « " + b.getMessage().getString()
							+ " » se touchent";
					}
					continue;
				}
				if (a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom()) {
					return "« " + a.getMessage().getString() + " » chevauche « " + b.getMessage().getString() + " »";
				}
			}
		}
		return null;
	}
}
