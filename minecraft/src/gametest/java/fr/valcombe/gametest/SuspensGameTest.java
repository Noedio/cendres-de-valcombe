package fr.valcombe.gametest;

import static fr.valcombe.gametest.TestKit.chatText;
import static fr.valcombe.gametest.TestKit.cleanMain;
import static fr.valcombe.gametest.TestKit.run;
import static fr.valcombe.gametest.TestKit.waitClient;
import static fr.valcombe.gametest.TestKit.waitFor;

import fr.valcombe.client.ClientState;
import fr.valcombe.mc.GameSave;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.VoiceRouter;
import fr.valcombe.mc.games.GameRoom;
import fr.valcombe.mc.games.GameTeams;
import fr.valcombe.mc.games.Games;
import fr.valcombe.mc.games.VoiceZones;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.session.Session;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

/**
 * Plusieurs parties, chacune la sienne, en jeu : noms et équipes du tableau des scores propres à chaque partie, zones du
 * vocal, puis une partie d'essai mise en suspens pendant que la principale continue (horloge gelée, joueur au hub sans
 * nom ni équipe de partie, fiche « en suspens »), un vrai redémarrage (le monde est refermé puis rouvert : chaque partie
 * reprend dans son monde, la suspendue toujours en suspens), et la reprise (lien de rappel, retour à sa place, horloge
 * qui repart). Les vérifications d'une commande se font dans le tick serveur de la commande, ou en attendant l'état visé.
 */
public class SuspensGameTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
		System.out.println("[valcombe-test] suspens : " + what);
	}

	private static void send(ClientGameTestContext ctx, String cmd) {
		ctx.runOnClient(mc -> ClientState.send(cmd));
		ctx.waitTicks(10);
	}

	private static void shot(ClientGameTestContext ctx, String name) {
		ctx.waitTicks(10);
		System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-" + name));
	}

	private static ServerPlayer player(MinecraftServer s, String name) {
		return s.getPlayerList().getPlayerByName(name);
	}

	private static String dimensionOf(MinecraftServer s, String name) {
		ServerPlayer p = player(s, name);
		return p == null ? "" : p.level().dimension().identifier().toString();
	}

	private static String teamOf(MinecraftServer s, String name) {
		PlayerTeam t = s.getScoreboard().getPlayersTeam(name);
		return t == null ? null : t.getName();
	}

	/** Ce qu'on regarde d'une partie pour savoir si elle a bougé. */
	private static String snapshot(GameRoom r) {
		Session s = r.session();
		var g = s.game();
		return s.lobby() + "|" + s.storyteller() + "|" + s.paused() + "|" + s.suspended() + "|" + s.nominationsOpen() + "|"
			+ (g == null ? "-" : g.phase() + " n" + g.night() + " j" + g.day() + " " + g.players().stream().map(p -> p.name).toList());
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		TestWorldSave save;
		String me;
		String essai;
		String mainId;
		String hubDim;
		long stamp = System.currentTimeMillis() % 100000;
		String name = "Essai suspens " + stamp;

		// ------------------------------------------------------------------
		// Première vie du serveur : deux parties, noms et vocal séparés, puis la mise en suspens.
		// ------------------------------------------------------------------
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			save = world.getWorldSave();
			ctx.waitTicks(60);
			me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			var server = world.getServer();
			server.runCommand("op " + me);
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> Games.main() != null), "les parties sont en service");
			cleanMain(world, me);
			mainId = server.computeOnServer(s -> Games.main().id());
			String mainDim = "valcombe:" + mainId;
			hubDim = "valcombe:" + server.computeOnServer(s -> ValcombeMod.travel().hub().id());
			check(server.computeOnServer(s -> s.getScoreboard().getPlayerTeams().stream().noneMatch(t -> GameTeams.LEGACY.contains(t.getName()))),
				"plus aucune équipe commune d'avant les parties multiples");

			// 1. Une partie d'essai, avec son village.
			send(ctx, "vc partie creer " + name);
			essai = server.computeOnServer(s -> ValcombeMod.worlds().list().stream().filter(e -> e.nom().equals(name))
				.map(WorldEntry::id).findFirst().orElse(null));
			check(essai != null, "partie d'essai créée : " + essai);
			String id = essai;
			String essaiDim = "valcombe:" + id;
			check(waitFor(ctx, world, 2400, s -> !ValcombeMod.sender().busy(id) && Games.byWorld(id).host().village() != null),
				"village de la partie d'essai posé");
			String mainKey = server.computeOnServer(s -> Games.main().host().teamKey());
			String essaiKey = server.computeOnServer(s -> Games.byWorld(id).host().teamKey());
			check(!mainKey.equals(essaiKey), "chaque partie a son préfixe d'équipes : " + mainKey + " / " + essaiKey);

			// 2. La principale se joue avec ses joueurs fictifs ; le joueur s'assied à la partie d'essai.
			server.runCommand("execute in " + mainDim + " run vc bots 6");
			server.runCommand("execute in " + mainDim + " run vc lancer auto");
			check(waitFor(ctx, world, 200, s -> Games.main().session().game() != null), "partie principale lancée");
			// En pause : elle ne se termine pas d'elle-même pendant le test, et sa pause doit survivre au redémarrage.
			check(server.computeOnServer(s -> {
				s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "execute in " + mainDim + " run vc pause");
				return Games.main().paused();
			}), "partie principale en pause");
			send(ctx, "vc partie aller " + id);
			check(waitFor(ctx, world, 200, s -> dimensionOf(s, me).equals(essaiDim)), "arrivé dans la partie d'essai");
			server.runOnServer(s -> {
				Session es = Games.byWorld(id).session();
				es.join(me);
				es.addBots(5);
				es.start(me, Session.Mode.AUTO);
			});
			check(waitFor(ctx, world, 200, s -> Games.byWorld(id).session().game() != null
				&& Games.byWorld(id).session().game().seatOf(me) >= 0), "assis à la partie d'essai");
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(essaiDim)), "toujours dans le monde de la partie d'essai");

			// 3. Noms au-dessus des têtes : cachés dans la partie d'essai, chacun dans les équipes de sa partie.
			String teams = server.computeOnServer(s -> {
				Games.byWorld(id).host().nameTags(false);
				Games.main().host().nameTags(false);
				String mine = teamOf(s, me);
				var essaiTeams = Games.byWorld(id).host().teams();
				var mainTeams = Games.main().host().teams();
				boolean ok = mine != null && GameTeams.of(mine, essaiKey) && mine.length() <= GameTeams.MAX_NAME
					&& essaiTeams.values().stream().anyMatch(l -> l.contains(me))
					&& mainTeams.values().stream().noneMatch(l -> l.contains(me))
					&& mainTeams.keySet().stream().allMatch(t -> GameTeams.of(t, mainKey))
					&& java.util.Collections.disjoint(essaiTeams.keySet(), mainTeams.keySet());
				return ok + " " + mine + " " + essaiTeams + " " + mainTeams;
			});
			check(teams.startsWith("true"), "équipes séparées par partie : " + teams);
			check(server.computeOnServer(s -> {
				Games.byWorld(id).host().nameTags(true);
				Games.main().host().nameTags(true);
				return teamOf(s, me) == null;
			}), "noms visibles : plus d'équipe des vivants");

			// 4. Le vocal : le joueur est dans la zone de sa partie, jamais dans celle de la principale.
			check(waitFor(ctx, world, 40, s -> id.equals(VoiceRouter.zones().zone(player(s, me).getUUID()))),
				"zone du vocal : la partie d'essai");

			// 5. Mise en suspens par la commande, dans le tick : la partie d'essai seule.
			AtomicBoolean essaiTask = new AtomicBoolean();
			AtomicBoolean mainTask = new AtomicBoolean();
			String suspended = server.computeOnServer(s -> {
				String mainBefore = snapshot(Games.main());
				String reply = run(s, player(s, me), "vc partie suspendre");
				GameRoom r = Games.byWorld(id);
				boolean ok = r.suspended() && r.paused() && r.host().clockFrozen() && !Games.main().suspended()
					&& !Games.main().host().clockFrozen() && snapshot(Games.main()).equals(mainBefore);
				// Une tâche sur chaque horloge : celle de la partie en suspens ne doit pas tourner.
				r.host().schedule(5, () -> essaiTask.set(true));
				Games.main().host().schedule(5, () -> mainTask.set(true));
				return ok + " " + reply;
			});
			check(suspended.startsWith("true") && suspended.contains("suspens"), "partie d'essai en suspens, la principale non : " + suspended);
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(hubDim)), "le joueur est renvoyé au hub");
			ctx.waitTicks(40);
			check(mainTask.get(), "l'horloge de la partie principale tourne");
			check(!essaiTask.get(), "celle de la partie en suspens est gelée");
			check(server.computeOnServer(s -> teamOf(s, me) == null && Games.byWorld(id).host().teams().isEmpty()),
				"au hub : ni nom ni équipe de partie");
			check(waitFor(ctx, world, 40, s -> VoiceZones.OUTSIDE.equals(VoiceRouter.zones().zone(player(s, me).getUUID()))),
				"zone du vocal : hors des parties");
			check(server.computeOnServer(s -> Games.byWorld(id).state().startsWith("en suspens")
				&& Games.main().session().game() != null && !Games.main().state().contains("suspens")),
				"l'état le dit : " + server.computeOnServer(s -> Games.byWorld(id).state() + " / " + Games.main().state()));
			check(server.computeOnServer(s -> ValcombeMod.travel().blocked(player(s, me)) == null), "rien ne le retient au hub");
			String fiche = server.computeOnServer(s -> {
				try {
					Path f = Games.byWorld(id).host().saveFile();
					GameSave.Read read = GameSave.read(Files.readString(f, StandardCharsets.UTF_8), id);
					return (read.verdict() == GameSave.Verdict.OK && read.json().contains("\"suspended\":true")
						&& f.getParent().getFileName().toString().equals("parties")) + " " + f;
				} catch (java.io.IOException e) {
					return "illisible " + e.getMessage();
				}
			});
			check(fiche.startsWith("true"), "fiche de la partie d'essai, en suspens : " + fiche);
			check(waitClient(ctx, 100, mc -> ClientState.bool(ClientState.view(), "suspended")
				&& id.equals(ClientState.str(ClientState.obj(ClientState.view(), "partie"), "id"))),
				"au hub, sa vue est celle de sa partie, en suspens");
			shot(ctx, "suspens-au-hub");
			String list = server.computeOnServer(s -> run(s, player(s, me), "vc partie liste"));
			check(list.contains("en suspens"), "la liste des parties le dit : " + list);
			check(server.computeOnServer(s -> {
				String before = snapshot(Games.byWorld(id));
				String reply = run(s, player(s, me), "vc pause");
				return snapshot(Games.byWorld(id)).equals(before) && reply.contains("suspens");
			}), "on ne commande pas une partie en suspens");
			server.runOnServer(s -> Games.saveAll());
		}

		// ------------------------------------------------------------------
		// Redémarrage : le monde est refermé puis rouvert. Chaque partie reprend dans son monde.
		// ------------------------------------------------------------------
		try (TestSingleplayerContext world = save.open()) {
			ctx.waitTicks(80);
			var server = world.getServer();
			server.runCommand("op " + me);
			ctx.waitTicks(20);
			String id = essai;
			String essaiDim = "valcombe:" + id;
			check(server.computeOnServer(s -> Games.byWorld(id) != null && Games.byWorld(id).suspended()
				&& Games.byWorld(id).host().clockFrozen() && Games.byWorld(id).session().game() != null
				&& Games.byWorld(id).session().game().seatOf(me) >= 0), "la partie d'essai reprend en suspens, à sa place");
			check(server.computeOnServer(s -> Games.main().session().game() != null && !Games.main().suspended()
				&& Games.main().paused()), "la partie principale reprend, elle, en cours, et toujours en pause");
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(hubDim)), "le joueur arrive au hub");
			check(server.computeOnServer(s -> teamOf(s, me) == null), "sans équipe de partie");

			// Reprise par la commande : horloge remise en marche, rappel, la partie attend son joueur.
			String resumed = server.computeOnServer(s -> {
				String reply = run(s, player(s, me), "vc partie reprendre");
				GameRoom r = Games.byWorld(id);
				return (!r.suspended() && !r.host().clockFrozen() && r.session().gathering() && r.paused()) + " " + reply;
			});
			check(resumed.startsWith("true"), "partie reprise, en attente de ses joueurs : " + resumed);
			check(waitClient(ctx, 60, mc -> chatText(mc).contains("[Y aller]")), "le lien de rappel est arrivé");
			shot(ctx, "suspens-rappel");
			send(ctx, "vc partie aller " + id);
			check(waitFor(ctx, world, 200, s -> dimensionOf(s, me).equals(essaiDim)), "retour dans la partie d'essai");
			check(waitFor(ctx, world, 100, s -> !Games.byWorld(id).host().recalled().contains(me)), "accueilli à la table");
			check(waitFor(ctx, world, 100, s -> {
				double[] seat = Games.byWorld(id).host().seatPosition(me);
				ServerPlayer p = player(s, me);
				return seat != null && p.position().distanceToSqr(seat[0], seat[1], seat[2]) < 4;
			}), "retour à son siège");
			check(waitFor(ctx, world, 200, s -> !Games.byWorld(id).session().gathering() && !Games.byWorld(id).paused()),
				"tout le monde est là : la partie repart");
			AtomicBoolean task = new AtomicBoolean();
			server.runOnServer(s -> Games.byWorld(id).host().schedule(5, () -> task.set(true)));
			ctx.waitTicks(30);
			check(task.get(), "son horloge tourne de nouveau");
			check(waitFor(ctx, world, 40, s -> id.equals(VoiceRouter.zones().zone(player(s, me).getUUID()))),
				"zone du vocal : de nouveau la partie d'essai");
			check(waitClient(ctx, 100, mc -> !ClientState.bool(ClientState.view(), "suspended")), "la vue n'est plus en suspens");
			shot(ctx, "suspens-reprise");

			// Ménage : la partie d'essai s'arrête et disparaît, la principale aussi s'arrête.
			send(ctx, "vc arreter");
			check(waitFor(ctx, world, 1200, s -> Games.byWorld(id).session().game() == null && !Games.byWorld(id).host().busy()),
				"partie d'essai arrêtée");
			check(server.computeOnServer(s -> Games.byWorld(id).host().teams().isEmpty()), "ses équipes sont retirées");
			Path f = server.computeOnServer(s -> Games.byWorld(id).host().saveFile());
			check(!Files.exists(f), "sa fiche est effacée");
			send(ctx, "vc partie supprimer " + id);
			check(waitFor(ctx, world, 600, s -> Games.byWorld(id) == null && !ValcombeMod.worlds().busy(id)), "partie d'essai supprimée");
			server.runCommand("execute in valcombe:" + mainId + " run vc arreter");
			check(waitFor(ctx, world, 1200, s -> Games.main().session().game() == null && !Games.main().host().busy()),
				"partie principale arrêtée");
			cleanMain(world, me);
		}
	}
}
