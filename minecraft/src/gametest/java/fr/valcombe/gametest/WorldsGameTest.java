package fr.valcombe.gametest;

import fr.valcombe.client.ClientState;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.mc.worlds.WorldRegistry;

import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * Gestionnaire de mondes, en jeu : créer un monde plat, y aller, y poser un bloc, le remettre à neuf (le bloc
 * disparaît, le sol plat est de nouveau à {@link WorldManager#SURFACE_Y}), relire le registre, le renommer et le
 * supprimer avec un joueur dedans (il est renvoyé au monde principal, les fichiers sont effacés).
 */
public class WorldsGameTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
		System.out.println("[valcombe-test] mondes : " + what);
	}

	private static void send(ClientGameTestContext ctx, String cmd) {
		ctx.runOnClient(mc -> ClientState.send(cmd));
		ctx.waitTicks(10);
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

	private static WorldManager worlds() {
		return ValcombeMod.worlds();
	}

	/** Le sol est-il plat et complet dans cette colonne : bedrock, pierre, terre, herbe à SURFACE_Y, de l'air au-dessus ? */
	private static boolean flatColumn(ServerLevel level, int x, int z) {
		int surface = WorldManager.SURFACE_Y;
		return level.getBlockState(new BlockPos(x, WorldManager.MIN_Y, z)).is(Blocks.BEDROCK)
			&& level.getBlockState(new BlockPos(x, 30, z)).is(Blocks.STONE)
			&& level.getBlockState(new BlockPos(x, surface - 1, z)).is(Blocks.DIRT)
			&& level.getBlockState(new BlockPos(x, surface, z)).is(Blocks.GRASS_BLOCK)
			&& level.getBlockState(new BlockPos(x, surface + 1, z)).isAir()
			&& level.getBlockState(new BlockPos(x, surface + 20, z)).isAir();
	}

	/** Le monde où se trouve ce joueur, côté serveur : « valcombe:essai », « minecraft:overworld »... */
	private static String dimensionOf(MinecraftServer server, String player) {
		return server.getPlayerList().getPlayerByName(player).level().dimension().identifier().toString();
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			ctx.waitTicks(60);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			var server = world.getServer();
			server.runCommand("op " + me);
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> worlds() != null), "gestionnaire de mondes démarré");

			// Créer, par la commande : la fiche est enregistrée et le monde ouvert, plat.
			String name = "Essai des tests " + (System.currentTimeMillis() % 100000);
			send(ctx, "vc monde creer essai " + name);
			String id = server.computeOnServer(s -> worlds().list().stream().filter(e -> e.nom().equals(name))
				.map(WorldEntry::id).findFirst().orElse(null));
			check(id != null && id.startsWith("essai-des-tests"), "monde créé : " + id);
			check(server.computeOnServer(s -> worlds().get(id).sorte() == WorldKind.ESSAI && worlds().isOpen(id)), "monde d'essai ouvert");
			check(server.computeOnServer(s -> flatColumn(worlds().level(id), 0, 0) && flatColumn(worlds().level(id), 75, -130)),
				"sol plat, herbe à y = " + WorldManager.SURFACE_Y);
			check(server.computeOnServer(s -> worlds().level(id).getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL),
				"monde paisible");

			// Y aller : le joueur arrive au point d'apparition, debout sur l'herbe.
			send(ctx, "vc monde aller " + id);
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals("valcombe:" + id)), "joueur arrivé dans le monde (serveur)");
			check(waitFor(ctx, world, 100, s -> {
				var p = s.getPlayerList().getPlayerByName(me);
				return Math.abs(p.getY() - (WorldManager.SURFACE_Y + 1)) < 0.01 && Math.abs(p.getX() - 0.5) < 0.01;
			}), "joueur au point d'apparition, sur le sol");
			ctx.waitTicks(40);
			check(ctx.computeOnClient(mc -> mc.level.dimension().identifier().toString()).equals("valcombe:" + id),
				"joueur arrivé dans le monde (client)");
			ctx.runOnClient(mc -> {
				mc.player.setYRot(0);
				mc.player.setXRot(20);
			});
			ctx.waitTicks(20);
			System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-monde-plat"));

			// Un bloc posé au-dessus du sol, enregistré sur le disque.
			BlockPos marked = new BlockPos(2, WorldManager.SURFACE_Y + 1, 3);
			server.runOnServer(s -> worlds().level(id).setBlockAndUpdate(marked, Blocks.GOLD_BLOCK.defaultBlockState()));
			// Comme « save-all flush », qui n'existe que sur un serveur dédié (inconnue en solo).
			server.runOnServer(s -> s.saveEverything(true, true, true));
			ctx.waitTicks(20);
			check(server.computeOnServer(s -> worlds().level(id).getBlockState(marked).is(Blocks.GOLD_BLOCK)), "bloc d'or posé");
			check(server.computeOnServer(s -> Files.isDirectory(worlds().folder(id).resolve("region"))), "monde enregistré sur le disque : "
				+ server.computeOnServer(s -> worlds().folder(id).toString()));

			// Remettre à neuf : le joueur est sorti, l'ancien monde fermé et effacé, un monde plat et vide le remplace.
			AtomicReference<ServerLevel> old = new AtomicReference<>();
			AtomicReference<CompletableFuture<ServerLevel>> recreated = new AtomicReference<>();
			server.runOnServer(s -> {
				old.set(worlds().level(id));
				recreated.set(worlds().recreate(id));
			});
			// Fantasy décharge d'abord le monde (tronçons déchargés un à un) : quelques secondes au plus.
			check(waitFor(ctx, world, 600, s -> recreated.get().isDone()), "remise à neuf terminée");
			check(!recreated.get().isCompletedExceptionally(), "remise à neuf sans erreur");
			check(server.computeOnServer(s -> dimensionOf(s, me).equals(Level.OVERWORLD.identifier().toString())),
				"joueur renvoyé au monde principal");
			check(server.computeOnServer(s -> {
				ServerLevel fresh = worlds().level(id);
				return fresh != old.get() && fresh == recreated.get().join() && s.getLevel(WorldManager.key(id)) == fresh;
			}), "nouveau monde ouvert à la place de l'ancien");
			check(server.computeOnServer(s -> worlds().level(id).getBlockState(marked).isAir()), "bloc d'or disparu");
			check(server.computeOnServer(s -> flatColumn(worlds().level(id), marked.getX(), marked.getZ())
				&& flatColumn(worlds().level(id), -40, 90)), "sol plat de nouveau à y = " + WorldManager.SURFACE_Y);

			// Le registre relu depuis le disque : la fiche est là, rien ne reste à effacer.
			check(server.computeOnServer(s -> {
				WorldRegistry reread = new WorldRegistry(WorldManager.defaultFile(s));
				reread.load();
				WorldEntry e = reread.find(id);
				return e != null && e.nom().equals(name) && e.sorte() == WorldKind.ESSAI && reread.wipes().isEmpty()
					&& !e.modifie().isBefore(e.cree());
			}), "registre relu");

			// Renommer : le nom change, pas l'identifiant.
			send(ctx, "vc monde renommer " + id + " Essai renommé");
			check(server.computeOnServer(s -> worlds().get(id).nom().equals("Essai renommé")), "monde renommé");
			send(ctx, "vc monde liste");

			// Retour dans le monde puis au monde principal par la commande.
			send(ctx, "vc monde aller " + id);
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals("valcombe:" + id)), "de retour dans le monde");
			send(ctx, "vc monde aller principal");
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(Level.OVERWORLD.identifier().toString())),
				"/vc monde aller principal");

			// Supprimer, avec le joueur dedans : il est sorti, la fiche et les fichiers disparaissent.
			send(ctx, "vc monde aller " + id);
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals("valcombe:" + id)), "dans le monde avant sa suppression");
			java.nio.file.Path folder = server.computeOnServer(s -> worlds().folder(id));
			send(ctx, "vc monde supprimer " + id);
			check(waitFor(ctx, world, 600, s -> worlds().find(id) == null && !worlds().busy(id)
				&& s.getLevel(WorldManager.key(id)) == null), "monde supprimé du serveur et du registre");
			check(server.computeOnServer(s -> dimensionOf(s, me).equals(Level.OVERWORLD.identifier().toString())),
				"joueur renvoyé au monde principal");
			check(!Files.exists(folder), "fichiers du monde effacés : " + folder);
			check(server.computeOnServer(s -> {
				WorldRegistry reread = new WorldRegistry(WorldManager.defaultFile(s));
				reread.load();
				return reread.find(id) == null && reread.wipes().isEmpty();
			}), "registre relu sans le monde supprimé");
		}
	}
}
