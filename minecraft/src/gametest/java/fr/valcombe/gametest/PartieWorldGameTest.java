package fr.valcombe.gametest;

import fr.valcombe.client.ClientState;
import fr.valcombe.mc.GameSave;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.GameException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;

/**
 * La partie dans son monde, en jeu : le monde de partie « Partie principale » existe dès le démarrage, plat, avec son
 * village bâti au centre ; on s'inscrit depuis le hub et l'on y est emmené ; la partie lancée depuis le hub y assoit
 * les joueurs et y monte sa scène (rien dans le hub ni dans le monde principal) ; on ne quitte pas la table, on ne
 * supprime pas ce monde et on ne le remet pas à neuf pendant la partie ; la nuit n'y tombe que là ; la sauvegarde de
 * partie porte ce monde, une sauvegarde d'avant les mondes est écartée ; à la fin, la carte est remise en état et
 * chacun reste au village ; remis à neuf, le monde ne garde rien de sa carte.
 */
public class PartieWorldGameTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
		System.out.println("[valcombe-test] partie : " + what);
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

	private static ServerPlayer player(MinecraftServer s, String name) {
		return s.getPlayerList().getPlayerByName(name);
	}

	private static String dimensionOf(MinecraftServer s, String name) {
		return player(s, name).level().dimension().identifier().toString();
	}

	/** Les entités de la partie (scène, présentoir, panneaux, trônes sculptés...) dans ce monde. */
	private static long decor(ServerLevel level) {
		long n = 0;
		for (Entity e : level.getAllEntities()) {
			if (e.entityTags().stream().anyMatch(t -> t.startsWith("vc_scene") || t.startsWith("vc_seat") || t.startsWith("vc_tok_"))) {
				n++;
			}
		}
		return n;
	}

	/** La session survit d'une classe de test à l'autre : on repart d'un salon vide, sans partie ni Conteur. */
	private static void cleanSession(TestSingleplayerContext world, String me) {
		world.getServer().runOnServer(s -> {
			var session = ValcombeMod.session();
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
			cleanSession(world, me);

			// 1. Le monde de la partie existe dès le démarrage : « Partie principale », plat, son village au centre.
			String gameId = server.computeOnServer(s -> ValcombeMod.host().worldId());
			check(WorldManager.MAIN_GAME_ID.equals(gameId), "la partie est liée au monde " + gameId);
			String gameDim = "valcombe:" + gameId;
			check(server.computeOnServer(s -> {
				WorldEntry e = ValcombeMod.worlds().find(gameId);
				return e != null && e.sorte() == WorldKind.PARTIE && e.nom().equals(WorldManager.MAIN_GAME_NAME);
			}), "monde de partie « Partie principale » dans le registre");
			check(server.computeOnServer(s -> ValcombeMod.host().level() == s.getLevel(WorldManager.key(gameId))),
				"le monde de la partie est un monde de Valcombe, pas le monde principal");
			check(server.computeOnServer(s -> ValcombeMod.host().village() != null
				&& ValcombeMod.host().village().center().equals(new BlockPos(0, WorldManager.SURFACE_Y, 0))),
				"village bâti au centre du sol plat");
			check(server.computeOnServer(s -> {
				Path fiche = ValcombeMod.host().villageFile();
				return fiche != null && Files.exists(fiche) && fiche.startsWith(ValcombeMod.worlds().folder(gameId));
			}), "fiche du village rangée dans le dossier du monde de la partie");
			check(server.computeOnServer(s -> !ValcombeMod.host().level().getBlockState(ValcombeMod.host().village().center())
				.is(Blocks.GRASS_BLOCK)), "la place est bâtie dans le monde de la partie");
			String hubId = server.computeOnServer(s -> ValcombeMod.hub().id());
			String hubDim = "valcombe:" + hubId;
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(hubDim)), "arrivée au hub, pas au village");

			// 2. Ce monde-là ne se supprime pas.
			check(server.computeOnServer(s -> {
				try {
					ValcombeMod.worlds().delete(gameId);
					return false;
				} catch (GameException e) {
					return e.getMessage().contains("ne se supprime pas");
				}
			}), "le monde de la partie ne se supprime pas");

			// 3. Inscription depuis le hub : on est emmené au village de la partie ; on peut encore repartir.
			send(ctx, "vc rejoindre");
			check(waitFor(ctx, world, 100, s -> ValcombeMod.session().lobby().contains(me) && dimensionOf(s, me).equals(gameDim)),
				"inscrit depuis le hub, emmené dans le monde de la partie");
			check(server.computeOnServer(s -> {
				BlockPos spawn = ValcombeMod.host().villageSpawn();
				ServerPlayer p = player(s, me);
				return spawn != null && Math.abs(p.getX() - (spawn.getX() + 0.5)) < 0.6 && Math.abs(p.getZ() - (spawn.getZ() + 0.5)) < 0.6;
			}), "à la salle d'attente du village");
			shot(ctx, "partie-monde-salle");
			send(ctx, "vc hub");
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(hubDim)), "inscrit mais pas encore assis : retour au hub permis");
			UUID uuid = server.computeOnServer(s -> player(s, me).getUUID());

			// 4. La partie lancée pendant qu'on attend au hub : chacun est assis au village, la scène y est montée.
			server.runCommand("vc bots 6");
			ctx.waitTicks(10);
			server.runCommand("vc lancer auto");
			check(waitFor(ctx, world, 100, s -> ValcombeMod.session().game() != null && dimensionOf(s, me).equals(gameDim)),
				"partie lancée : le joueur est amené dans le monde de la partie");
			check(waitFor(ctx, world, 60, s -> {
				double[] seat = ValcombeMod.host().seatPosition(me);
				ServerPlayer p = player(s, me);
				return seat != null && Math.abs(p.getX() - seat[0]) < 1.5 && Math.abs(p.getZ() - seat[2]) < 1.5;
			}), "assis sur son trône");
			check(server.computeOnServer(s -> ValcombeMod.travel().positions().get(uuid, hubId) != null),
				"sa place au hub est gardée pour son retour");
			check(waitFor(ctx, world, 60, s -> decor(ValcombeMod.host().level()) > 0), "scène montée dans le monde de la partie");
			check(server.computeOnServer(s -> decor(ValcombeMod.worlds().level(hubId)) == 0 && decor(s.overworld()) == 0),
				"rien dans le hub ni dans le monde principal");
			ctx.runOnClient(mc -> mc.player.setXRot(8));
			shot(ctx, "partie-monde-trones");

			// 5. Pendant la partie : on reste à la table, et son monde ne se remet pas à neuf.
			send(ctx, "vc hub");
			ctx.waitTicks(30);
			check(server.computeOnServer(s -> dimensionOf(s, me).equals(gameDim)), "pas de départ pour le hub pendant la partie");
			check(server.computeOnServer(s -> ValcombeMod.travel().blocked(player(s, me)) != null), "le menu « Mondes » dit pourquoi");
			check(server.computeOnServer(s -> {
				try {
					ValcombeMod.worlds().recreate(gameId);
					return false;
				} catch (GameException e) {
					return e.getMessage().contains("Une partie se joue");
				}
			}), "remise à neuf refusée pendant la partie");

			// 6. La nuit tombe dans le monde de la partie, et seulement là.
			long hubTime = server.computeOnServer(s -> ValcombeMod.worlds().level(hubId).getDefaultClockTime());
			server.runCommand("vc nuit");
			check(waitFor(ctx, world, 100, s -> {
				long t = ValcombeMod.host().level().getDefaultClockTime() % 24000;
				return t >= 13000 && t <= 23000;
			}), "nuit dans le monde de la partie (heure " + server.computeOnServer(s -> ValcombeMod.host().level().getDefaultClockTime() % 24000) + ")");
			check(server.computeOnServer(s -> ValcombeMod.worlds().level(hubId).getDefaultClockTime() == hubTime), "le hub garde son heure");

			// 7. La sauvegarde de partie porte le monde où elle se joue.
			server.runOnServer(s -> ValcombeMod.session().saveNow());
			Path save = server.computeOnServer(s -> s.getServerDirectory().resolve("config").resolve("valcombe-partie.json"));
			check(server.computeOnServer(s -> {
				try {
					return GameSave.read(Files.readString(save, StandardCharsets.UTF_8), gameId).verdict() == GameSave.Verdict.OK;
				} catch (java.io.IOException e) {
					return false;
				}
			}), "sauvegarde de partie liée au monde " + gameId);

			// 8. Fin de partie : la carte est remise en état, chacun reste au village de la partie, la scène est retirée.
			server.runCommand("vc arreter");
			check(waitFor(ctx, world, 1200, s -> ValcombeMod.session().game() == null && !ValcombeMod.host().busy()),
				"partie arrêtée, carte remise en état");
			check(server.computeOnServer(s -> dimensionOf(s, me).equals(gameDim)), "après la partie, au village de la partie");
			check(waitFor(ctx, world, 100, s -> decor(ValcombeMod.host().level()) == 0), "scène retirée");
			check(server.computeOnServer(s -> !Files.exists(save)), "plus de sauvegarde de partie");

			// 9. Une sauvegarde d'avant les mondes de Valcombe (sans monde) ne reprend pas : elle est mise de côté.
			Path old = save.resolveSibling(save.getFileName() + ".ancienne");
			check(server.computeOnServer(s -> {
				try {
					Files.writeString(save, "{\"mode\":\"AUTO\",\"lobby\":[\"Alice\"]}", StandardCharsets.UTF_8);
					boolean ignored = ValcombeMod.host().savedGame() == null;
					boolean aside = Files.exists(old) && !Files.exists(save);
					Files.deleteIfExists(old);
					return ignored && aside;
				} catch (java.io.IOException e) {
					return false;
				}
			}), "ancienne sauvegarde sans monde écartée");

			// 10. Remis à neuf après la partie : le monde est plat et vide, et la partie n'a plus de carte.
			AtomicReference<CompletableFuture<ServerLevel>> fresh = new AtomicReference<>();
			server.runOnServer(s -> fresh.set(ValcombeMod.worlds().recreate(gameId)));
			check(waitFor(ctx, world, 1200, s -> fresh.get().isDone()), "monde de la partie remis à neuf");
			check(!fresh.get().isCompletedExceptionally(), "remise à neuf sans erreur");
			check(server.computeOnServer(s -> ValcombeMod.host().village() == null
				&& !Files.exists(ValcombeMod.host().villageFile())), "plus de carte en service, plus de fiche");
			check(server.computeOnServer(s -> ValcombeMod.host().level().getBlockState(new BlockPos(0, WorldManager.SURFACE_Y, 0))
				.is(Blocks.GRASS_BLOCK) && decor(ValcombeMod.host().level()) == 0), "sol plat, aucun reste de l'ancienne carte");
			check(server.computeOnServer(s -> dimensionOf(s, me).equals(hubDim)), "les joueurs du monde remis à neuf vont au hub");

			cleanSession(world, me);
		}
	}
}
