package fr.valcombe.gametest;

import fr.valcombe.client.ClientState;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.Village;
import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.GameException;

import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
 * « Envoyer à la partie », en jeu : une carte de la bibliothèque arrive sur le monde de la partie remis à neuf (un bloc
 * posé avant, près de la place comme très loin, a disparu), avec ses trônes, ses maisons et sa salle de convocation ;
 * les joueurs attendent au hub pendant la pose, qui occupe la partie ; une seconde carte ne laisse rien de la première
 * (ni blocs ni trônes sculptés) ; une partie se lance sur la carte envoyée, et l'on n'envoie rien pendant qu'elle se
 * joue ; une partie d'essai reçoit sa propre carte sans toucher à celle de la partie ; le hub, les ateliers et la pose
 * directe dans le monde de la partie sont refusés.
 */
public class EnvoiPartieGameTest implements FabricClientGameTest {
	private static final String MAP_A = "envoi-test-a";
	private static final String MAP_B = "envoi-test-b";

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
		System.out.println("[valcombe-test] envoi : " + what);
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

	/** Attend la fin de l'envoi en cours vers le monde de la partie (remise à neuf, pose par petits lots, adoption). */
	private static void waitSent(ClientGameTestContext ctx, TestSingleplayerContext world, String what) {
		check(waitFor(ctx, world, 2400, s -> !fr.valcombe.mc.games.Games.main().host().busy()), what);
		ctx.waitTicks(10);
	}

	private static ServerPlayer player(MinecraftServer s, String name) {
		return s.getPlayerList().getPlayerByName(name);
	}

	private static String dimensionOf(MinecraftServer s, String name) {
		return player(s, name).level().dimension().identifier().toString();
	}

	/** Les trônes sculptés de ce monde : {tous, ceux d'une autre génération que celle du village}. */
	private static int[] thrones(ServerLevel level, Village v) {
		int all = 0;
		int stale = 0;
		for (Entity e : level.getAllEntities()) {
			if (e.entityTags().contains("vc_trone")) {
				all++;
				if (v == null || !e.entityTags().contains("vc_tr_" + v.decorGen())) {
					stale++;
				}
			}
		}
		return new int[] {all, stale};
	}

	/** Blocs bâtis (autres que de l'air) au-dessus du sol plat, dans l'anneau entre les rayons inner et outer autour de c. */
	private static int builtInRing(ServerLevel level, BlockPos c, int inner, int outer) {
		int n = 0;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int x = -outer; x <= outer; x++) {
			for (int z = -outer; z <= outer; z++) {
				if (Math.max(Math.abs(x), Math.abs(z)) <= inner) {
					continue;
				}
				for (int y = WorldManager.SURFACE_Y + 1; y <= WorldManager.SURFACE_Y + 24; y++) {
					if (!level.getBlockState(m.set(c.getX() + x, y, c.getZ() + z)).isAir()) {
						n++;
					}
				}
			}
		}
		return n;
	}

	/** Le refus (message) de cette opération, ou null si elle passe. */
	private static String refusal(TestSingleplayerContext world, Supplier<String> op) {
		return world.getServer().computeOnServer(s -> {
			try {
				op.get();
				return null;
			} catch (GameException e) {
				return e.getMessage();
			}
		});
	}

	/** La session survit d'une classe de test à l'autre : on repart d'un salon vide, sans partie ni Conteur. */
	private static void cleanSession(TestSingleplayerContext world, String me) {
		world.getServer().runOnServer(s -> {
			var session = fr.valcombe.mc.games.Games.main().session();
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
			waitSent(ctx, world, "la partie est libre au départ");
			String gameId = server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().host().worldId());
			String gameDim = "valcombe:" + gameId;
			String hubId = server.computeOnServer(s -> ValcombeMod.hub().id());
			String hubDim = "valcombe:" + hubId;
			BlockPos center = new BlockPos(0, WorldManager.SURFACE_Y, 0);

			// 1. Deux cartes pour la bibliothèque. A : un village de 8 trônes et une tour bâtie à la main loin de la place
			// (l'enregistrement prend toute la zone bâtie). B : un petit village de 5 trônes, sans maisons.
			send(ctx, "vc carte generer 8 normale gothique oui");
			waitSent(ctx, world, "village envoyé à la partie");
			BlockPos tower = center.offset(110, 1, 0);
			server.runOnServer(s -> {
				for (int y = 0; y < 15; y++) {
					fr.valcombe.mc.games.Games.main().host().level().setBlockAndUpdate(tower.above(y), Blocks.STONE_BRICKS.defaultBlockState());
				}
			});
			send(ctx, "vc carte enregistrer " + MAP_A + " Envoi A");
			int rayonA = server.computeOnServer(s -> ValcombeMod.maps().list().stream().filter(d -> d.id.equals(MAP_A))
				.mapToInt(d -> d.rayon).findFirst().orElse(-1));
			check(rayonA >= 110, "carte A rangée dans la bibliothèque, tour comprise (rayon " + rayonA + ")");
			send(ctx, "vc carte generer 5 petite port non");
			waitSent(ctx, world, "petit village envoyé à la partie");
			send(ctx, "vc carte enregistrer " + MAP_B + " Envoi B");
			int rayonB = server.computeOnServer(s -> ValcombeMod.maps().list().stream().filter(d -> d.id.equals(MAP_B))
				.mapToInt(d -> d.rayon).findFirst().orElse(-1));
			check(rayonB > 0 && rayonB + 10 < 110, "carte B rangée dans la bibliothèque (rayon " + rayonB + ")");

			// 2. On revient dans le monde de la partie, et l'on y laisse deux traces : près de la place et très loin.
			send(ctx, "vc monde aller " + gameId);
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(gameDim)), "dans le monde de la partie");
			BlockPos near = center.offset(9, 1, 9);
			BlockPos far = center.offset(180, 6, -170);
			server.runOnServer(s -> {
				ServerLevel level = fr.valcombe.mc.games.Games.main().host().level();
				level.setBlockAndUpdate(near, Blocks.SPONGE.defaultBlockState()); // aucun village n'en a
				level.setBlockAndUpdate(far, Blocks.DIAMOND_BLOCK.defaultBlockState());
			});

			// 3. Envoyer la carte A : les joueurs sortent au hub, la partie est occupée, une seconde carte attend son tour.
			// La commande du joueur, puis les deux vérifications, dans le même tick du serveur : l'envoi ne peut pas être
			// déjà fini quand on regarde (une carte posée vite irait plus vite que l'aller-retour du client).
			String sending = server.computeOnServer(s -> {
				var source = s.getPlayerList().getPlayerByName(me).createCommandSourceStack();
				s.getCommands().performPrefixedCommand(source, "vc carte envoyer " + MAP_A);
				String twice;
				try {
					ValcombeMod.sender().sendMap(MAP_B, null, null);
					twice = "acceptée";
				} catch (GameException e) {
					twice = e.getMessage();
				}
				return "occupée " + fr.valcombe.mc.games.Games.main().host().busy() + " | seconde carte : " + twice;
			});
			check(sending.startsWith("occupée true"), "la partie est occupée pendant l'envoi : " + sending);
			check(sending.contains("reçoit déjà"), "une seconde carte attend la fin de la première : " + sending);
			check(waitFor(ctx, world, 60, s -> dimensionOf(s, me).equals(hubDim)), "le joueur attend au hub pendant l'envoi");
			ctx.waitTicks(20);
			shot(ctx, "envoi-barre-au-hub");
			waitSent(ctx, world, "carte A posée");
			check(server.computeOnServer(s -> dimensionOf(s, me).equals(hubDim)), "personne n'est ramené de force : le lien le propose");
			shot(ctx, "envoi-message-de-fin");

			// 4. Un monde neuf : les deux traces ont disparu ; la carte A est en place avec ses trônes et ses repères.
			String fresh = server.computeOnServer(s -> {
				ServerLevel level = fr.valcombe.mc.games.Games.main().host().level();
				return "près " + !level.getBlockState(near).is(Blocks.SPONGE) + " | loin " + level.getBlockState(far).isAir()
					+ " | sol " + level.getBlockState(far.atY(WorldManager.SURFACE_Y)).is(Blocks.GRASS_BLOCK);
			});
			check(fresh.equals("près true | loin true | sol true"), "monde remis à neuf, aucune trace d'avant : " + fresh);
			String placedA = server.computeOnServer(s -> {
				var host = fr.valcombe.mc.games.Games.main().host();
				ServerLevel level = host.level();
				Village v = host.village();
				boolean seats = true;
				for (int i = 0; i < v.seatCount(); i++) {
					seats &= level.getBlockState(v.markerPos(new Village.Hit(Village.Mark.SEAT, i))).is(Blocks.BARRIER);
				}
				int[] t = thrones(level, v);
				var folder = ValcombeMod.worlds().folder(gameId);
				return v.libraryId() + " | centre " + v.center().equals(center) + " | tour " + level.getBlockState(tower.above(14))
					.is(Blocks.STONE_BRICKS) + " | trônes " + v.seatCount() + " posés " + seats
					+ " | sculptés " + t[0] + " anciens " + t[1] + " | maisons " + v.houseCount() + " | convocation " + (v.convocation() != null)
					+ " | fiche " + Files.exists(host.villageFile()) + " | carte du monde " + Files.exists(folder.resolve("valcombe-carte.json"))
					+ " | registre " + ValcombeMod.worlds().find(gameId).carte()
					+ " | marque " + Files.exists(folder.resolve("valcombe-chantier.txt"));
			});
			System.out.println("[valcombe-test] carte A : " + placedA);
			check(placedA.equals(MAP_A + " | centre true | tour true | trônes 8 posés true | sculptés 8 anciens 0 | maisons 8 | convocation true"
				+ " | fiche true | carte du monde true | registre " + MAP_A + " | marque false"), "carte A en place : " + placedA);

			// 5. Le lien du message ramène au village de la partie.
			send(ctx, "vc monde aller " + gameId);
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(gameDim)), "retour au village de la partie");
			check(server.computeOnServer(s -> {
				BlockPos spawn = fr.valcombe.mc.games.Games.main().host().villageSpawn();
				ServerPlayer p = player(s, me);
				return spawn != null && Math.abs(p.getX() - (spawn.getX() + 0.5)) < 2 && Math.abs(p.getZ() - (spawn.getZ() + 0.5)) < 2;
			}), "à la salle d'attente de la carte envoyée");
			shot(ctx, "envoi-carte-a");

			// 6. Envoyer la carte B, plus petite : rien ne reste de la carte A, ni sa tour au loin, ni ses trônes sculptés.
			int beforeB = server.computeOnServer(s -> builtInRing(fr.valcombe.mc.games.Games.main().host().level(), center, rayonB + 1, rayonA));
			check(beforeB >= 15, "la carte A a bâti au-delà de l'emprise de B (" + beforeB + " blocs)");
			send(ctx, "vc carte envoyer " + MAP_B);
			waitSent(ctx, world, "carte B posée");
			String placedB = server.computeOnServer(s -> {
				var host = fr.valcombe.mc.games.Games.main().host();
				ServerLevel level = host.level();
				Village v = host.village();
				int[] t = thrones(level, v);
				return v.libraryId() + " | trônes " + v.seatCount() + " | sculptés " + t[0] + " anciens " + t[1]
					+ " | restes de A " + builtInRing(level, center, rayonB + 1, rayonA)
					+ " | registre " + ValcombeMod.worlds().find(gameId).carte() + " | A gardée " + ValcombeMod.maps().exists(MAP_A);
			});
			System.out.println("[valcombe-test] carte B : " + placedB);
			check(placedB.equals(MAP_B + " | trônes 5 | sculptés 5 anciens 0 | restes de A 0 | registre " + MAP_B + " | A gardée true"),
				"il ne reste rien de la carte A : " + placedB);

			// 7. Une partie se lance sur la carte envoyée ; pendant qu'elle se joue, on n'envoie rien.
			send(ctx, "vc rejoindre");
			server.runCommand("vc bots 4");
			ctx.waitTicks(10);
			server.runCommand("vc lancer auto");
			check(waitFor(ctx, world, 200, s -> fr.valcombe.mc.games.Games.main().session().game() != null && dimensionOf(s, me).equals(gameDim)),
				"partie lancée dans le monde de la partie");
			check(waitFor(ctx, world, 100, s -> {
				double[] seat = fr.valcombe.mc.games.Games.main().host().seatPosition(me);
				ServerPlayer p = player(s, me);
				Village v = fr.valcombe.mc.games.Games.main().host().village();
				boolean onB = false;
				for (int i = 0; seat != null && i < v.seatCount(); i++) {
					var f = v.seatFeet(i);
					onB |= Math.abs(f.x - seat[0]) < 1.5 && Math.abs(f.z - seat[2]) < 1.5;
				}
				return onB && Math.abs(p.getX() - seat[0]) < 1.5 && Math.abs(p.getZ() - seat[2]) < 1.5;
			}), "assis sur un trône de la carte B");
			ctx.runOnClient(mc -> mc.player.setXRot(8));
			shot(ctx, "envoi-partie-sur-la-carte-b");
			String during = refusal(world, () -> ValcombeMod.sender().sendMap(MAP_A, null, null));
			check(during != null && during.contains("Une partie se joue"), "rien n'est envoyé pendant la partie : " + during);
			server.runCommand("vc arreter");
			check(waitFor(ctx, world, 1200, s -> fr.valcombe.mc.games.Games.main().session().game() == null && !fr.valcombe.mc.games.Games.main().host().busy()),
				"partie arrêtée, carte remise en état");
			cleanSession(world, me);

			// 8. Ni le hub ni un atelier ne reçoivent de carte ; dans le monde de la partie, on ne la pose pas en direct.
			String toHub = refusal(world, () -> ValcombeMod.sender().sendMap(MAP_A, hubId, null));
			check(toHub != null && toHub.contains("monde de partie"), "le hub ne reçoit pas de carte : " + toHub);
			server.runCommand("vc carte poser " + MAP_A + " " + gameId);
			ctx.waitTicks(5);
			check(server.computeOnServer(s -> !ValcombeMod.placer().busy(fr.valcombe.mc.games.Games.main().host().level())
				&& MAP_B.equals(fr.valcombe.mc.games.Games.main().host().village().libraryId())), "pose directe dans le monde de la partie refusée");

			// 9. Une partie d'essai reçoit sa propre carte : la fiche de son village l'attend, la partie garde la sienne.
			String essai = server.computeOnServer(s -> ValcombeMod.worlds().create(WorldKind.ESSAI, "Essai envoi "
				+ System.currentTimeMillis() % 100000).id());
			String sent = server.computeOnServer(s -> ValcombeMod.sender().sendMap(MAP_A, essai, null));
			check(sent.contains("lancé"), "envoi à la partie d'essai lancé : " + sent);
			check(waitFor(ctx, world, 2400, s -> !ValcombeMod.sender().busy(essai)), "carte A posée dans la partie d'essai");
			String trial = server.computeOnServer(s -> {
				var folder = ValcombeMod.worlds().folder(essai);
				ServerLevel level = ValcombeMod.worlds().level(essai);
				Village v = Village.load(folder.resolve(Village.FILE)).village();
				var placed = ValcombeMod.placer().placed(level);
				return "fiche " + (v != null && v.seatCount() == 8 && MAP_A.equals(v.libraryId())) + " | carte " + (placed != null
					&& placed.mapId().equals(MAP_A)) + " | partie " + fr.valcombe.mc.games.Games.main().host().village().libraryId();
			});
			check(trial.equals("fiche true | carte true | partie " + MAP_B), "partie d'essai : " + trial);
			AtomicReference<CompletableFuture<Void>> gone = new AtomicReference<>();
			server.runOnServer(s -> gone.set(ValcombeMod.worlds().delete(essai)));
			check(waitFor(ctx, world, 600, s -> gone.get().isDone()), "partie d'essai supprimée");

			// 10. Un nouveau village : monde remis à neuf, village généré dessus, tel qu'au premier démarrage.
			send(ctx, "vc carte construire");
			waitSent(ctx, world, "nouveau village");
			check(server.computeOnServer(s -> {
				Village v = fr.valcombe.mc.games.Games.main().host().village();
				return v.isGenerated() && v.libraryId().isEmpty() && v.seatCount() == Village.SEATS && v.hasHall()
					&& v.convocation() != null && thrones(fr.valcombe.mc.games.Games.main().host().level(), v)[1] == 0;
			}), "nouveau village généré sur un monde neuf");

			// Ménage : la bibliothèque est commune aux tests.
			server.runOnServer(s -> {
				ValcombeMod.maps().delete(MAP_A);
				ValcombeMod.maps().delete(MAP_B);
			});
			cleanSession(world, me);
		}
	}
}
