package fr.valcombe.gametest;

import fr.valcombe.client.ClientState;
import fr.valcombe.mc.GameSave;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.games.GameRoom;
import fr.valcombe.mc.games.Games;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.session.Session;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Plusieurs parties en même temps, en jeu : une partie d'essai créée par la commande reçoit son monde, sa partie et
 * son village ; la partie principale se joue avec ses joueurs fictifs pendant que le joueur conte la partie d'essai ;
 * chacune a ses joueurs, son Conteur et sa sauvegarde ; une commande vise toujours la partie du monde où l'on se trouve
 * (vérifié dans le même tick serveur, sans course) ; les annonces et les barres d'une partie ne vont qu'aux joueurs
 * de son monde ; la vue du joueur suit son monde ; on ne s'inscrit pas ailleurs pendant qu'on conte une partie en
 * cours ; la partie d'essai supprimée emporte sa partie et sa sauvegarde, la principale continue.
 */
public class MultiPartiesGameTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
		System.out.println("[valcombe-test] parties : " + what);
	}

	private static void send(ClientGameTestContext ctx, String cmd) {
		ctx.runOnClient(mc -> ClientState.send(cmd));
		ctx.waitTicks(10);
	}

	private static void shot(ClientGameTestContext ctx, String name) {
		ctx.waitTicks(10);
		System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-" + name));
	}

	/** Attend (au plus {@code ticks}) qu'une condition soit vraie sur le serveur ; renvoie si elle l'est devenue. */
	private static boolean waitFor(ClientGameTestContext ctx, TestSingleplayerContext world, int ticks, Predicate<MinecraftServer> cond) {
		for (int i = 0; i < ticks; i += 2) {
			if (world.getServer().computeOnServer(cond::test)) {
				return true;
			}
			ctx.waitTicks(2);
		}
		return world.getServer().computeOnServer(cond::test);
	}

	/** Attend (au plus {@code ticks}) qu'une condition soit vraie sur le client. */
	private static boolean waitClient(ClientGameTestContext ctx, int ticks, Predicate<net.minecraft.client.Minecraft> cond) {
		for (int i = 0; i < ticks; i += 2) {
			if (ctx.computeOnClient(cond::test)) {
				return true;
			}
			ctx.waitTicks(2);
		}
		return ctx.computeOnClient(cond::test);
	}

	private static ServerPlayer player(MinecraftServer s, String name) {
		return s.getPlayerList().getPlayerByName(name);
	}

	private static String dimensionOf(MinecraftServer s, String name) {
		return player(s, name).level().dimension().identifier().toString();
	}

	/** Ce qu'on regarde d'une partie pour savoir si une commande l'a touchée. */
	private static String snapshot(GameRoom r) {
		Session s = r.session();
		var g = s.game();
		return s.lobby() + "|" + s.storyteller() + "|" + s.paused() + "|" + s.nominationsOpen() + "|"
			+ (g == null ? "-" : g.phase() + " n" + g.night() + " j" + g.day() + " " + g.players().stream().map(p -> p.name).toList());
	}

	/**
	 * Lance une commande au nom de ce joueur, là où il se trouve, et renvoie ce qu'elle a répondu : sur le fil du serveur,
	 * dans le tick où l'on regarde ensuite son effet (pas de course avec les ticks des parties).
	 */
	private static String run(MinecraftServer s, ServerPlayer p, String cmd) {
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

	/** Le texte du chat du client (tous les messages reçus) : ses messages sont lus par réflexion. */
	private static String chatText(net.minecraft.client.Minecraft mc) {
		StringBuilder out = new StringBuilder();
		try {
			appendMessages(mc.gui.hud.getChat(), out);
		} catch (ReflectiveOperationException | RuntimeException e) {
			out.append("(chat illisible : ").append(e).append(')');
		}
		return out.toString();
	}

	/** Le titre et le sous-titre affichés en ce moment sur le client, lus par réflexion. */
	private static String titleText(net.minecraft.client.Minecraft mc) {
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

	/** Les parties survivent d'une classe de test à l'autre (sauvegardes) : on repart d'une partie principale libre. */
	private static void cleanMain(TestSingleplayerContext world, String me) {
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
			session.removeBots();
		});
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			ctx.waitTicks(60);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			var server = world.getServer();
			server.runCommand("op " + me);
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> Games.current() != null && Games.main() != null && Games.main().main()),
				"les parties sont en service, la principale d'abord");
			cleanMain(world, me);
			String mainId = server.computeOnServer(s -> Games.main().id());
			String mainDim = "valcombe:" + mainId;
			long stamp = System.currentTimeMillis() % 100000;

			// 1. Une partie d'essai créée par la commande : son monde, sa partie, son village posé par « Envoyer à la partie ».
			String name = "Essai multi " + stamp;
			send(ctx, "vc partie creer " + name);
			String essai = server.computeOnServer(s -> ValcombeMod.worlds().list().stream().filter(e -> e.nom().equals(name))
				.map(WorldEntry::id).findFirst().orElse(null));
			check(essai != null, "monde de la partie d'essai créé : " + essai);
			String essaiDim = "valcombe:" + essai;
			check(server.computeOnServer(s -> Games.byWorld(essai) != null && !Games.byWorld(essai).main()
				&& Games.all().size() >= 2), "sa partie est ouverte, à côté de la principale");
			check(waitFor(ctx, world, 2400, s -> !ValcombeMod.sender().busy(essai) && Games.byWorld(essai).host().village() != null),
				"village de la partie d'essai posé");
			check(server.computeOnServer(s -> Games.byWorld(essai).host().village().seatCount() >= 5
				&& Games.main().host().village() != null && Games.byWorld(essai).host().village() != Games.main().host().village()),
				"chaque partie a son village");

			// 2. La partie principale se joue avec ses joueurs fictifs (commandes lancées depuis son monde).
			server.runCommand("execute in " + mainDim + " run vc bots 6");
			server.runCommand("execute in " + mainDim + " run vc lancer auto");
			check(waitFor(ctx, world, 200, s -> Games.main().session().game() != null), "partie principale lancée");
			check(server.computeOnServer(s -> Games.main().session().game().players().stream().noneMatch(p -> p.name.equals(me))),
				"le joueur n'est pas à la table principale");

			// 3. Le joueur va dans la partie d'essai, en devient le Conteur et la lance : ses commandes visent son monde.
			send(ctx, "vc partie aller " + essai);
			check(waitFor(ctx, world, 200, s -> dimensionOf(s, me).equals(essaiDim)), "arrivé dans la partie d'essai");
			check(server.computeOnServer(s -> Games.of(player(s, me)) == Games.byWorld(essai)), "Games.of : la partie de son monde");
			send(ctx, "vc conteur");
			send(ctx, "vc bots 5");
			check(waitFor(ctx, world, 60, s -> me.equals(Games.byWorld(essai).session().storyteller())
				&& Games.byWorld(essai).session().lobby().size() == 5), "Conteur et joueurs fictifs de la partie d'essai");
			check(server.computeOnServer(s -> Games.main().session().storyteller() == null
				&& Games.main().session().game().size() == 6), "la partie principale n'a ni ce Conteur ni ces joueurs");
			send(ctx, "vc lancer humain");
			check(waitFor(ctx, world, 200, s -> Games.byWorld(essai).session().game() != null), "partie d'essai lancée");
			check(server.computeOnServer(s -> Games.main().session().game() != null && Games.byWorld(essai).session().game() != null
				&& Games.anyInProgress()), "deux parties en cours en même temps");
			shot(ctx, "parties-essai-en-cours");

			// 4. Sa vue est celle de la partie d'essai.
			check(waitClient(ctx, 100, mc -> essai.equals(ClientState.str(ClientState.obj(ClientState.view(), "partie"), "id"))),
				"la vue du joueur est celle de la partie de son monde");

			// 5. Une commande vise la partie du monde d'où elle part, et elle seule (même tick : pas de course).
			String mine = server.computeOnServer(s -> {
				String mainBefore = snapshot(Games.main());
				boolean pausedBefore = Games.byWorld(essai).session().paused();
				String reply = run(s, player(s, me), "vc pause");
				return (snapshot(Games.main()).equals(mainBefore) && Games.byWorld(essai).session().paused() != pausedBefore) + " " + reply;
			});
			check(mine.startsWith("true"), "« /vc pause » du Conteur : la partie d'essai seule (" + mine + ")");
			boolean theirs = server.computeOnServer(s -> {
				String essaiBefore = snapshot(Games.byWorld(essai));
				boolean pausedBefore = Games.main().session().paused();
				s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "execute in " + mainDim + " run vc pause");
				return snapshot(Games.byWorld(essai)).equals(essaiBefore) && Games.main().session().paused() != pausedBefore;
			});
			check(theirs, "« /vc pause » depuis le monde principal de la partie : la principale seule");
			server.runOnServer(s -> {
				run(s, player(s, me), "vc pause");
				s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "execute in " + mainDim + " run vc pause");
			});
			check(server.computeOnServer(s -> !Games.main().session().paused() && !Games.byWorld(essai).session().paused()),
				"les deux parties reprennent");
			String players = server.computeOnServer(s -> run(s, player(s, me), "vc joueurs"));
			check(players.contains("Bot") && players.contains("Conteur : " + me), "/vc joueurs : les joueurs de sa partie (" + players + ")");

			// 6. Annonces et barres : seulement aux joueurs du monde de la partie.
			String markEssai = "Marqueur essai " + stamp;
			String markMain = "Marqueur principal " + stamp;
			server.runOnServer(s -> {
				Games.main().host().announce(markMain);
				Games.byWorld(essai).host().announce(markEssai);
				Games.main().host().timer(List.of(me), "Minuteur principal", 30);
				Games.byWorld(essai).host().timer(List.of(me), "Minuteur essai", 30);
			});
			check(server.computeOnServer(s -> !Games.main().host().barViewers().contains(me)
				&& Games.byWorld(essai).host().barViewers().contains(me)), "barre de minuteur : celle de sa partie seulement");
			check(server.computeOnServer(s -> Games.byWorld(essai).host().here().stream().anyMatch(p -> p.getScoreboardName().equals(me))
				&& Games.main().host().here().stream().noneMatch(p -> p.getScoreboardName().equals(me))),
				"les joueurs d'une partie sont ceux de son monde");
			check(waitClient(ctx, 60, mc -> chatText(mc).contains(markEssai)), "annonce de sa partie reçue");
			check(!ctx.computeOnClient(mc -> chatText(mc).contains(markMain)), "annonce de l'autre partie jamais reçue");
			server.runOnServer(s -> {
				Games.main().host().timer(List.of(me), "", 0);
				Games.byWorld(essai).host().timer(List.of(me), "", 0);
			});
			// Les titres : celui de l'autre partie n'arrive pas, celui de la sienne s'affiche.
			String titleMain = "P" + stamp;
			String titleEssai = "E" + stamp;
			server.runOnServer(s -> Games.main().host().title(List.of(me), titleMain, ""));
			ctx.waitTicks(10);
			check(!ctx.computeOnClient(mc -> titleText(mc).contains(titleMain)), "titre de l'autre partie jamais affiché");
			server.runOnServer(s -> Games.byWorld(essai).host().title(List.of(me), titleEssai, ""));
			check(waitClient(ctx, 40, mc -> titleText(mc).contains(titleEssai)), "titre de sa partie affiché");

			// 7. Il conte une partie en cours : pas d'inscription ailleurs. Il passe dans la principale : ses commandes et sa vue
			// la suivent, sans jamais toucher la partie d'essai.
			send(ctx, "vc partie aller " + mainId);
			check(waitFor(ctx, world, 200, s -> dimensionOf(s, me).equals(mainDim)), "passé dans le monde de la partie principale");
			String refused = server.computeOnServer(s -> {
				String essaiBefore = snapshot(Games.byWorld(essai));
				String reply = run(s, player(s, me), "vc spectateur");
				return (snapshot(Games.byWorld(essai)).equals(essaiBefore) && !Games.main().session().isSpectator(me)) + " " + reply;
			});
			check(refused.startsWith("true") && refused.contains(name),
				"pas de place dans la principale tant qu'il conte la partie d'essai : " + refused);
			check(waitClient(ctx, 100, mc -> mainId.equals(ClientState.str(ClientState.obj(ClientState.view(), "partie"), "id"))),
				"sa vue suit son monde : la partie principale");
			String fromMain = server.computeOnServer(s -> {
				String essaiBefore = snapshot(Games.byWorld(essai));
				boolean pausedBefore = Games.main().session().paused();
				run(s, player(s, me), "vc pause");
				boolean ok = snapshot(Games.byWorld(essai)).equals(essaiBefore) && Games.main().session().paused() != pausedBefore;
				run(s, player(s, me), "vc pause");
				return String.valueOf(ok);
			});
			check(fromMain.equals("true"), "depuis le monde principal, ses commandes visent la principale");
			shot(ctx, "parties-principale-vue-par-le-conteur-de-l-essai");

			// 8. Chaque partie a sa sauvegarde, dans son fichier, liée à son monde.
			String saves = server.computeOnServer(s -> {
				Games.saveAll();
				Path mainSave = Games.main().host().saveFile();
				Path essaiSave = Games.byWorld(essai).host().saveFile();
				try {
					return !mainSave.equals(essaiSave) + " " + (GameSave.read(Files.readString(mainSave, StandardCharsets.UTF_8), mainId)
						.verdict() == GameSave.Verdict.OK) + " " + (GameSave.read(Files.readString(essaiSave, StandardCharsets.UTF_8), essai)
						.verdict() == GameSave.Verdict.OK) + " " + essaiSave.getFileName();
				} catch (java.io.IOException e) {
					return "illisible " + e.getMessage();
				}
			});
			check(saves.startsWith("true true true"), "deux sauvegardes séparées, chacune de son monde : " + saves);

			// 9. La liste des parties, puis le ménage : la partie d'essai ne se supprime pas pendant qu'elle se joue.
			send(ctx, "vc partie liste");
			shot(ctx, "parties-liste");
			String during = server.computeOnServer(s -> run(s, player(s, me), "vc partie supprimer " + essai));
			check(server.computeOnServer(s -> Games.byWorld(essai) != null) && during.contains("arrêtez-la"),
				"pas de suppression pendant la partie : " + during);
			send(ctx, "vc partie aller " + essai);
			check(waitFor(ctx, world, 200, s -> dimensionOf(s, me).equals(essaiDim)), "retour dans la partie d'essai");
			send(ctx, "vc arreter");
			check(waitFor(ctx, world, 1200, s -> Games.byWorld(essai).session().game() == null && !Games.byWorld(essai).host().busy()),
				"partie d'essai arrêtée");
			check(server.computeOnServer(s -> Games.main().session().game() != null), "la partie principale continue");
			Path essaiSave = server.computeOnServer(s -> Games.byWorld(essai).host().saveFile());
			send(ctx, "vc partie supprimer " + essai);
			check(waitFor(ctx, world, 600, s -> ValcombeMod.worlds().find(essai) == null && Games.byWorld(essai) == null
				&& !ValcombeMod.worlds().busy(essai)), "partie d'essai supprimée, sa partie fermée");
			check(!Files.exists(essaiSave), "sa sauvegarde est effacée");
			check(server.computeOnServer(s -> !dimensionOf(s, me).equals(essaiDim)), "le joueur est sorti de son monde");
			check(server.computeOnServer(s -> Games.main() != null && Games.main().session().game() != null),
				"la partie principale n'a rien vu");
			String mainOnly = server.computeOnServer(s -> run(s, player(s, me), "vc partie supprimer " + mainId));
			check(server.computeOnServer(s -> Games.byWorld(mainId) != null), "la partie principale ne se supprime pas : " + mainOnly);

			server.runCommand("execute in " + mainDim + " run vc arreter");
			check(waitFor(ctx, world, 1200, s -> Games.main().session().game() == null && !Games.main().host().busy()),
				"partie principale arrêtée, carte remise en état");
			cleanMain(world, me);
		}
	}
}
