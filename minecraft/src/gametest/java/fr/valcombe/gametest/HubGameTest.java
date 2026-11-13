package fr.valcombe.gametest;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.screen.MainScreen;
import fr.valcombe.client.screen.WorldsScreen;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.hub.HubHall;
import fr.valcombe.mc.hub.LastPositions;
import fr.valcombe.mc.hub.Travel;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldManager;

import java.nio.file.Files;
import java.util.List;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Le hub et les voyages, en jeu : arrivée au hub à la connexion (salle bâtie, captures), dalles d'inscription,
 * voyage aller-retour avec la position et l'orientation retrouvées (commandes, porte du hub, menu « Mondes »),
 * ateliers cachés aux joueurs qui ne sont pas opérateurs, repli au hub quand un monde est supprimé, hub remis à
 * neuf avec sa salle rebâtie.
 */
public class HubGameTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
		System.out.println("[valcombe-test] hub : " + what);
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

	/** Le joueur est-il à cette position (au centimètre près), regardant dans cette direction (au degré près) ? */
	private static boolean at(ServerPlayer p, double x, double y, double z, float yaw, float pitch) {
		return Math.abs(p.getX() - x) < 0.05 && Math.abs(p.getY() - y) < 0.05 && Math.abs(p.getZ() - z) < 0.05
			&& Math.abs(net.minecraft.util.Mth.wrapDegrees(p.getYRot() - yaw)) < 1 && Math.abs(p.getXRot() - pitch) < 1;
	}

	private static String where(MinecraftServer s, String name) {
		ServerPlayer p = player(s, name);
		return String.format(java.util.Locale.ROOT, "%s %.2f %.2f %.2f lacet %.1f tangage %.1f", dimensionOf(s, name),
			p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
	}

	/** Place le joueur ici, dans ce monde, côté serveur (comme s'il y avait marché). */
	private static void place(TestSingleplayerContext world, String me, String id, double x, double y, double z, float yaw, float pitch) {
		world.getServer().runOnServer(s -> {
			ServerLevel level = Travel.MAIN.equals(id) ? s.overworld() : ValcombeMod.worlds().level(id);
			player(s, me).teleportTo(level, x, y, z, java.util.Set.of(), yaw, pitch, false);
		});
	}

	/** Clique sur le bouton de l'écran ouvert qui porte ce libellé ; faux s'il n'y est pas ou s'il est grisé. */
	private static boolean click(ClientGameTestContext ctx, String label) {
		boolean done = ctx.computeOnClient(mc -> {
			if (mc.gui.screen() == null) {
				return false;
			}
			for (var w : mc.gui.screen().children()) {
				if (w instanceof fr.valcombe.client.ui.WaxButton b && b.active && b.getMessage().getString().equals(label)) {
					b.press();
					return true;
				}
			}
			return false;
		});
		ctx.waitTicks(12);
		return done;
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			ctx.waitTicks(60);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			var server = world.getServer();
			server.runCommand("op " + me);
			ctx.waitTicks(40);

			// 1. Arrivée : le hub existe, sa salle est bâtie, le joueur est au point d'arrivée, face au mur des rôles.
			check(server.computeOnServer(s -> ValcombeMod.hub() != null && ValcombeMod.travel() != null), "hub et voyages démarrés");
			String hubId = server.computeOnServer(s -> ValcombeMod.hub().id());
			String hubDim = "valcombe:" + hubId;
			check(server.computeOnServer(s -> dimensionOf(s, me).equals(hubDim)), "arrivée au hub à la connexion (" + hubDim + ")");
			check(server.computeOnServer(s -> {
				ServerPlayer p = player(s, me);
				return Math.abs(p.getX() - HubHall.ARRIVAL.x) < 0.01 && Math.abs(p.getZ() - HubHall.ARRIVAL.z) < 0.01
					&& Math.abs(p.getY() - HubHall.ARRIVAL.y) < 0.01;
			}), "au point d'arrivée de la salle : " + server.computeOnServer(s -> where(s, me)));
			check(server.computeOnServer(s -> {
				ServerLevel hub = ValcombeMod.worlds().level(hubId);
				return hub.getBlockState(new BlockPos(0, HubHall.FLOOR, 0)).is(Blocks.GILDED_BLACKSTONE)
					&& hub.getBlockState(new BlockPos(HubHall.LEAVE_X, HubHall.FLOOR, 0)).is(Blocks.REDSTONE_BLOCK)
					&& hub.getBlockState(new BlockPos(HubHall.STORYTELLER_X, HubHall.FLOOR, 0)).is(Blocks.CRYING_OBSIDIAN)
					&& hub.getBlockState(new BlockPos(HubHall.HALF_X, HubHall.FEET + 2, 3)).is(Blocks.STRIPPED_DARK_OAK_LOG)
					&& hub.getBlockState(new BlockPos(-HubHall.HALF_X, HubHall.FEET + 1, 0)).isAir()
					&& hub.getBlockState(new BlockPos(-HubHall.HALF_X - 1, HubHall.FEET + 1, 0)).is(Blocks.OBSIDIAN);
			}), "salle d'accueil bâtie : dalles, murs, portes");
			check(server.computeOnServer(s -> Files.exists(ValcombeMod.worlds().folder(hubId).resolve("valcombe-accueil.txt"))),
				"marque de la salle posée dans le dossier du hub");
			check(server.computeOnServer(s -> {
				try {
					ValcombeMod.worlds().delete(hubId);
					return false;
				} catch (fr.valcombe.rules.GameException e) {
					return e.getMessage().contains("ne se supprime pas");
				}
			}), "le hub ne se supprime pas");

			// Captures : la salle vue de l'entrée, puis les portes du mur ouest (les affichages arrivent en deux secondes).
			ctx.runOnClient(mc -> {
				mc.player.setYRot(HubHall.ARRIVAL_YAW);
				mc.player.setXRot(4);
			});
			ctx.waitTicks(100);
			check(server.computeOnServer(s -> !ValcombeMod.worlds().level(hubId).getEntities(
				net.minecraft.world.entity.EntityTypes.TEXT_DISPLAY, e -> e.entityTags().contains("vc_hub_fixe")).isEmpty()),
				"textes de la salle posés");
			shot(ctx, "hub-accueil");
			ctx.runOnClient(mc -> {
				mc.player.setYRot(90);
				mc.player.setXRot(-6);
			});
			shot(ctx, "hub-portes");
			ctx.runOnClient(mc -> {
				mc.player.setYRot(180);
				mc.player.setXRot(-12);
			});
			place(world, me, hubId, 0.5, HubHall.FEET, -4.5, 180f, -12f);
			ctx.waitTicks(20);
			shot(ctx, "hub-mur-des-roles");

			// 2. Dalles : la dalle dorée inscrit, la rouge désinscrit.
			place(world, me, hubId, HubHall.JOIN_X + 0.5, HubHall.FEET, HubHall.JOIN_Z + 0.5, 180f, 0f);
			check(waitFor(ctx, world, 60, s -> ValcombeMod.session().lobby().contains(me)), "inscrit par la dalle dorée du hub");
			place(world, me, hubId, HubHall.LEAVE_X + 0.5, HubHall.FEET, 0.5, 180f, 0f);
			check(waitFor(ctx, world, 60, s -> !ValcombeMod.session().lobby().contains(me)), "désinscrit par la dalle rouge du hub");

			// 3. Aller-retour par les commandes : chacun retrouve sa position et son orientation dans chaque monde.
			String name = "Essai du hub " + (System.currentTimeMillis() % 100000);
			send(ctx, "vc monde creer essai " + name);
			String id = server.computeOnServer(s -> ValcombeMod.worlds().list().stream().filter(e -> e.nom().equals(name))
				.map(WorldEntry::id).findFirst().orElse(null));
			check(id != null, "monde d'essai créé : " + id);
			place(world, me, hubId, 6.5, HubHall.FEET, 6.5, 45f, 10f);
			ctx.waitTicks(10);
			send(ctx, "vc monde aller " + id);
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals("valcombe:" + id)), "arrivé dans l'essai");
			Vec3 spawn = WorldManager.spawn();
			check(server.computeOnServer(s -> Math.abs(player(s, me).getX() - spawn.x) < 0.01
				&& Math.abs(player(s, me).getZ() - spawn.z) < 0.01), "première visite : point d'arrivée du monde");
			place(world, me, id, 20.5, WorldManager.SURFACE_Y + 1, -7.5, -30f, 15f);
			ctx.waitTicks(10);
			send(ctx, "vc hub");
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(hubDim)), "/vc hub ramène au hub");
			check(server.computeOnServer(s -> at(player(s, me), 6.5, HubHall.FEET, 6.5, 45f, 10f)),
				"position et orientation retrouvées au hub : " + server.computeOnServer(s -> where(s, me)));
			send(ctx, "vc monde aller " + id);
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals("valcombe:" + id)), "de retour dans l'essai");
			check(server.computeOnServer(s -> at(player(s, me), 20.5, WorldManager.SURFACE_Y + 1, -7.5, -30f, 15f)),
				"position et orientation retrouvées dans l'essai : " + server.computeOnServer(s -> where(s, me)));
			UUID uuid = server.computeOnServer(s -> player(s, me).getUUID());
			check(server.computeOnServer(s -> {
				LastPositions reread = new LastPositions(ValcombeMod.travel().positions().file());
				reread.load();
				LastPositions.Spot hub = reread.get(uuid, hubId);
				return hub != null && Math.abs(hub.x() - 6.5) < 0.01 && Math.abs(hub.yaw() - 45f) < 0.01;
			}), "positions écrites sur le disque : " + server.computeOnServer(s -> ValcombeMod.travel().positions().file().toString()));

			// 4. Par une porte du hub : celle de l'essai y mène ; au retour, on est devant la porte, pas dedans.
			send(ctx, "vc hub");
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(hubDim)), "au hub avant les portes");
			ctx.waitTicks(50); // les portes sont rangées avec les noms, toutes les deux secondes
			int door = server.computeOnServer(s -> {
				for (HubHall.Portal p : HubHall.PORTALS) {
					if (id.equals(ValcombeMod.hub().door(p.index()))) {
						return p.index();
					}
				}
				return -1;
			});
			check(server.computeOnServer(s -> Travel.MAIN.equals(ValcombeMod.hub().door(0))), "première porte : le village actuel");
			if (door >= 0) {
				HubHall.Portal portal = HubHall.PORTALS.get(door);
				place(world, me, hubId, portal.wallX() + 0.5, HubHall.FEET, portal.z() + 0.5, portal.west() ? 90f : -90f, 0f);
				check(waitFor(ctx, world, 60, s -> dimensionOf(s, me).equals("valcombe:" + id)), "la porte " + door + " mène à l'essai");
				check(server.computeOnServer(s -> at(player(s, me), 20.5, WorldManager.SURFACE_Y + 1, -7.5, -30f, 15f)),
					"par la porte aussi, la position est retrouvée");
				send(ctx, "vc hub");
				Vec3 front = portal.front();
				check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(hubDim)
					&& at(player(s, me), front.x, front.y, front.z, portal.frontYaw(), 0f)),
					"retour devant la porte, dos à elle : " + server.computeOnServer(s -> where(s, me)));
				ctx.waitTicks(20);
				check(server.computeOnServer(s -> dimensionOf(s, me).equals(hubDim)), "devant la porte, on ne repart pas");
			} else {
				System.out.println("[valcombe-test] hub : plus de six mondes de partie, l'essai n'a pas de porte (vérifié par la commande)");
			}
			HubHall.Portal village = HubHall.PORTALS.get(0);
			place(world, me, hubId, village.wallX() + 0.5, HubHall.FEET, village.z() + 0.5, 90f, 0f);
			check(waitFor(ctx, world, 60, s -> dimensionOf(s, me).equals(Level.OVERWORLD.identifier().toString())),
				"la porte du village actuel mène au monde principal");
			send(ctx, "vc hub");
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(hubDim)), "retour au hub depuis le village");

			// 5. Menu J, « Mondes » : l'entrée existe, l'écran liste le hub, le village actuel et l'essai ; « Y aller ».
			ctx.setScreen(MainScreen::new);
			ctx.waitTicks(10);
			check(click(ctx, "Mondes"), "entrée « Mondes » du menu J");
			check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof WorldsScreen), "écran Mondes ouvert");
			ctx.setScreen(() -> new WorldsScreen(id));
			ctx.waitTicks(10);
			List<String> ids = ctx.computeOnClient(mc -> WorldsScreen.destinations().stream().map(d -> ClientState.str(d, "id")).toList());
			check(ids.contains(hubId) && ids.contains(Travel.MAIN) && ids.contains(id) && ids.indexOf(hubId) == 0,
				"destinations listées, hub d'abord : " + ids);
			shot(ctx, "hub-ecran-mondes");
			check(click(ctx, "Y aller"), "bouton « Y aller »");
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals("valcombe:" + id)), "« Y aller » mène à l'essai");

			// 6. Un joueur qui n'est pas opérateur ne voit pas les ateliers et ne peut pas y aller.
			send(ctx, "vc monde creer atelier-carte Atelier du hub " + (System.currentTimeMillis() % 100000));
			String atelier = server.computeOnServer(s -> ValcombeMod.worlds().list().stream()
				.filter(e -> e.sorte() == fr.valcombe.mc.worlds.WorldKind.ATELIER_CARTE && e.nom().startsWith("Atelier du hub"))
				.map(WorldEntry::id).findFirst().orElse(null));
			check(atelier != null, "atelier créé : " + atelier);
			server.runCommand("deop " + me);
			ctx.waitTicks(20);
			check(server.computeOnServer(s -> ValcombeMod.travel().destinations(player(s, me)).stream()
				.noneMatch(d -> d.id().equals(atelier))), "atelier caché à un joueur qui n'est pas opérateur");
			check(server.computeOnServer(s -> ValcombeMod.travel().destinations(player(s, me)).stream()
				.anyMatch(d -> d.id().equals(id))), "la partie d'essai reste visible");
			send(ctx, "vc monde aller " + atelier);
			ctx.waitTicks(40); // le temps qu'un voyage, s'il était accepté, ait lieu
			check(server.computeOnServer(s -> dimensionOf(s, me).equals("valcombe:" + id)), "aller dans l'atelier refusé");
			server.runCommand("op " + me);
			ctx.waitTicks(20);
			AtomicReference<CompletableFuture<Void>> gone = new AtomicReference<>();
			server.runOnServer(s -> gone.set(ValcombeMod.worlds().delete(atelier)));
			check(waitFor(ctx, world, 600, s -> gone.get().isDone()), "atelier supprimé");

			// 7. Repli : l'essai supprimé avec le joueur dedans, il se retrouve au point d'arrivée du hub.
			send(ctx, "vc monde supprimer " + id);
			check(waitFor(ctx, world, 600, s -> ValcombeMod.worlds().find(id) == null && !ValcombeMod.worlds().busy(id)),
				"essai supprimé");
			check(server.computeOnServer(s -> dimensionOf(s, me).equals(hubDim)
				&& Math.abs(player(s, me).getX() - HubHall.ARRIVAL.x) < 0.01 && Math.abs(player(s, me).getZ() - HubHall.ARRIVAL.z) < 0.01),
				"repli au point d'arrivée du hub : " + server.computeOnServer(s -> where(s, me)));
			check(server.computeOnServer(s -> ValcombeMod.travel().positions().get(uuid, id) == null),
				"positions oubliées dans le monde supprimé");

			// 8. Le hub remis à neuf : ses joueurs vont au village, la salle est rebâtie, on y revient au point d'arrivée.
			AtomicReference<CompletableFuture<ServerLevel>> fresh = new AtomicReference<>();
			server.runOnServer(s -> {
				ValcombeMod.worlds().level(hubId).setBlockAndUpdate(new BlockPos(0, HubHall.FLOOR, 0), Blocks.DIRT.defaultBlockState());
				fresh.set(ValcombeMod.worlds().recreate(hubId));
			});
			check(waitFor(ctx, world, 600, s -> fresh.get().isDone()), "hub remis à neuf");
			check(!fresh.get().isCompletedExceptionally(), "remise à neuf du hub sans erreur");
			check(server.computeOnServer(s -> dimensionOf(s, me).equals(Level.OVERWORLD.identifier().toString())),
				"les joueurs du hub remis à neuf vont au village");
			check(server.computeOnServer(s -> ValcombeMod.worlds().level(hubId).getBlockState(new BlockPos(0, HubHall.FLOOR, 0))
				.is(Blocks.GILDED_BLACKSTONE)), "salle d'accueil rebâtie");
			send(ctx, "vc hub");
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(hubDim)
				&& Math.abs(player(s, me).getZ() - HubHall.ARRIVAL.z) < 0.01), "retour au hub neuf, au point d'arrivée");
		}
	}
}
