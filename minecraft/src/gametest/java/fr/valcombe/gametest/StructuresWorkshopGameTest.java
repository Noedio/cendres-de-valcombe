package fr.valcombe.gametest;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.screen.StructuresWorkshopScreen;
import fr.valcombe.client.screen.WorldsScreen;
import fr.valcombe.mc.StructureStore;
import fr.valcombe.mc.StructureWorkshop;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.Village;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldManager;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * L'atelier structures, en jeu : il s'ouvre par le menu « Mondes » (créé à la première visite) et pose toutes les
 * structures du catalogue sur leurs parcelles, en rangées par catégorie ; captures de l'ensemble, d'une parcelle et de
 * l'écran du pupitre. Puis le cœur du ticket : un bloc du puits (structure fournie) est changé, « Enregistrer » à
 * l'écran en fait une surcharge qui remplace le puits partout (un village généré la bâtit), « Revenir à l'originale »
 * la retire et repose l'original (le village retrouve sa cloche). Enfin une nouvelle structure : parcelle vide, bâtie,
 * enregistrée, renommée, supprimée (elle part à la corbeille).
 */
public class StructuresWorkshopGameTest implements FabricClientGameTest {
	private static final int S = WorldManager.SURFACE_Y;

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
		System.out.println("[valcombe-test] atelier structures : " + what);
	}

	private static void send(ClientGameTestContext ctx, String cmd) {
		ctx.runOnClient(mc -> ClientState.send(cmd));
		ctx.waitTicks(10);
	}

	private static void shot(ClientGameTestContext ctx, String name) {
		System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-atelier-structures-" + name));
	}

	private static boolean waitFor(ClientGameTestContext ctx, TestSingleplayerContext world, int ticks, Predicate<MinecraftServer> cond) {
		for (int i = 0; i < ticks; i += 5) {
			if (world.getServer().computeOnServer(cond::test)) {
				return true;
			}
			ctx.waitTicks(5);
		}
		return world.getServer().computeOnServer(cond::test);
	}

	/** Clique le bouton de l'écran ouvert qui porte ce libellé ; faux s'il n'y est pas (ou s'il est grisé). */
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
		ctx.waitTicks(15);
		return done;
	}

	private static StructureWorkshop workshop() {
		return ValcombeMod.structureWorkshop();
	}

	private static StructureStore store() {
		return ValcombeMod.structures();
	}

	private static ServerPlayer player(MinecraftServer s, String name) {
		return s.getPlayerList().getPlayerByName(name);
	}

	private static ServerLevel atelier() {
		WorldEntry e = workshop().entry();
		return ValcombeMod.worlds().level(e.id());
	}

	private static boolean inAtelier(MinecraftServer s, String me) {
		WorldEntry e = workshop().entry();
		return e != null && player(s, me).level().dimension().identifier().toString().equals(e.dimension());
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			ctx.waitTicks(60);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			var server = world.getServer();
			server.runCommand("op " + me);
			server.runCommand("gamemode creative " + me);
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> workshop() != null && store() != null), "atelier structures disponible");
			// Un essai précédent interrompu a pu laisser le puits modifié : on repart de l'original.
			server.runOnServer(s -> {
				if (store().isOverridden("puits")) {
					store().revert("puits");
				}
			});
			try {
				run(ctx, world, me);
			} finally {
				server.runOnServer(s -> {
					if (store().isOverridden("puits")) {
						store().revert("puits");
					}
				});
			}
		}
	}

	private void run(ClientGameTestContext ctx, TestSingleplayerContext world, String me) {
		var server = world.getServer();
		boolean existed = server.computeOnServer(s -> workshop().entry() != null);

		// 1. Le menu « Mondes » propose l'atelier structures, même avant sa création ; « Y aller » le crée et y conduit.
		ctx.waitTicks(20);
		List<String> sorts = ctx.computeOnClient(mc -> WorldsScreen.destinations().stream().map(d -> ClientState.str(d, "sorte")).toList());
		check(sorts.contains("atelier-structures"), "le menu « Mondes » propose l'atelier structures : " + sorts);
		String atelierId = server.computeOnServer(s -> {
			WorldEntry e = workshop().entry();
			return e == null ? WorldKind.ATELIER_STRUCTURES.code() : e.id();
		});
		ctx.setScreen(() -> new WorldsScreen(atelierId));
		ctx.waitTicks(10);
		check(click(ctx, "Y aller"), "« Y aller » vers l'atelier structures");
		check(waitFor(ctx, world, 200, s -> inAtelier(s, me)), "joueur dans l'atelier structures" + (existed ? "" : " (créé à l'instant)"));
		check(waitFor(ctx, world, 4000, s -> !workshop().busy() && !workshop().parcels().isEmpty()), "parcelles posées");

		// 2. Une parcelle par structure, en rangées par catégorie.
		String layout = server.computeOnServer(s -> {
			List<StructureWorkshop.ParcelInfo> parcels = workshop().parcels();
			List<String> missing = new ArrayList<>();
			for (StructureStore.Def d : store().list()) {
				if (parcels.stream().noneMatch(p -> p.id().equals(d.id))) {
					missing.add(d.id);
				}
			}
			Map<Integer, String> rows = new HashMap<>();
			String mixed = "";
			for (StructureWorkshop.ParcelInfo p : parcels) {
				String before = rows.putIfAbsent(p.lecternZ(), p.categorie());
				if (before != null && !before.equals(p.categorie())) {
					mixed = p.id();
				}
			}
			return parcels.size() + " parcelles / " + store().list().size() + " structures | manquantes " + missing
				+ " | rangées " + rows.size() + " | mélange " + (mixed.isEmpty() ? "aucun" : mixed);
		});
		System.out.println("[valcombe-test] atelier structures : " + layout);
		check(layout.contains("manquantes []") && layout.contains("mélange aucun"), "chaque structure a sa parcelle, une catégorie par rangée : " + layout);
		StructureWorkshop.ParcelInfo puits = server.computeOnServer(s -> workshop().parcel("puits"));
		check(puits != null && puits.etat().equals("fournie"), "le puits est sur sa parcelle, « fournie »");
		BlockPos bell = new BlockPos(puits.x(), S + 5, puits.z());
		check(server.computeOnServer(s -> atelier().getBlockState(bell).is(Blocks.BELL)
			&& atelier().getBlockState(new BlockPos(puits.x() + puits.rayon(), S, puits.z() + 1)).is(
				puits.rayon() % 4 == 0 ? Blocks.CONCRETE.white() : Blocks.CONCRETE.lightGray())
			&& atelier().getBlockState(new BlockPos(puits.x() + puits.rayon(), S, puits.z())).is(Blocks.CONCRETE.white())
			&& atelier().getBlockState(new BlockPos(puits.x(), S + 1, puits.lecternZ())).is(Blocks.LECTERN)),
			"puits posé : sa cloche, le quadrillage du sol, le pupitre");
		check(server.computeOnServer(s -> Boolean.FALSE.equals(workshop().changes("puits"))), "aucune modification juste après la pose");

		// 3. Captures : l'ensemble vu d'en haut, puis la parcelle du puits.
		server.runCommand("gamemode spectator " + me);
		server.runOnServer(s -> player(s, me).teleportTo(atelier(), 60, S + 75, -35, Set.of(), 0f, 45f, false));
		ctx.waitTicks(100);
		shot(ctx, "ensemble");
		server.runCommand("gamemode creative " + me);
		server.runOnServer(s -> player(s, me).teleportTo(atelier(), puits.x() + 0.5, S + 4, puits.lecternZ() - 4.5, Set.of(), 0f, 25f, false));
		ctx.waitTicks(60);
		shot(ctx, "parcelle");

		// 4. Le pupitre ouvre l'écran de l'atelier sur la parcelle.
		server.runOnServer(s -> workshop().onUse(player(s, me), atelier(), new BlockPos(puits.x(), S + 1, puits.lecternZ()), true));
		ctx.waitTicks(20);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof StructuresWorkshopScreen sc && "puits".equals(sc.chosenId())),
			"le pupitre ouvre l'écran sur le puits");
		ctx.waitTicks(20);
		shot(ctx, "ecran");

		// 5. Un bloc du puits change : la cloche devient un bloc d'or. L'écran le voit, « Enregistrer » en fait une surcharge.
		server.runOnServer(s -> atelier().setBlockAndUpdate(bell, Blocks.GOLD_BLOCK.defaultBlockState()));
		check(server.computeOnServer(s -> Boolean.TRUE.equals(workshop().changes("puits"))), "modification vue sur la parcelle");
		ctx.waitTicks(30);
		shot(ctx, "ecran-modifie");
		check(click(ctx, "Enregistrer"), "bouton « Enregistrer » de l'écran");
		check(waitFor(ctx, world, 100, s -> store().isOverridden("puits")), "le puits modifié est enregistré comme surcharge");
		check(server.computeOnServer(s -> store().def("puits").modifiee && store().def("puits").fournie
			&& "modifiee".equals(workshop().parcel("puits").etat()) && Boolean.FALSE.equals(workshop().changes("puits"))),
			"catalogue et parcelle : « modifiée », rien en attente");

		// 6. La surcharge remplace le puits partout : un village généré bâtit la version modifiée.
		String trial = server.computeOnServer(s -> ValcombeMod.worlds().create(WorldKind.ESSAI, "Village surcharge " + (System.currentTimeMillis() % 100000)).id());
		BlockPos center = new BlockPos(0, S, 0);
		server.runOnServer(s -> Village.generated(center, "gothique", 5, 0, false).build(ValcombeMod.worlds().level(trial)));
		check(server.computeOnServer(s -> ValcombeMod.worlds().level(trial).getBlockState(center.above(5)).is(Blocks.GOLD_BLOCK)),
			"le village généré pose le puits modifié (bloc d'or à la place de la cloche)");

		// 7. Revenir à l'originale, depuis l'écran (avec confirmation) : la surcharge part, l'original revient sur la parcelle.
		ctx.setScreen(() -> new StructuresWorkshopScreen("puits"));
		ctx.waitTicks(20);
		check(click(ctx, "Revenir à l'originale"), "bouton « Revenir à l'originale »");
		ctx.waitTicks(10);
		shot(ctx, "ecran-confirmation");
		check(click(ctx, "Oui, l'originale"), "confirmation du retour à l'originale");
		check(waitFor(ctx, world, 400, s -> !store().isOverridden("puits") && !workshop().busy()), "surcharge retirée, original reposé");
		check(server.computeOnServer(s -> atelier().getBlockState(bell).is(Blocks.BELL) && "fournie".equals(workshop().parcel("puits").etat())
			&& Boolean.FALSE.equals(workshop().changes("puits"))), "la cloche est revenue sur la parcelle, « fournie »");
		check(server.computeOnServer(s -> {
			try (var files = Files.list(store().trashDir())) {
				return files.anyMatch(f -> f.getFileName().toString().endsWith("-puits.nbt"));
			} catch (java.io.IOException e) {
				return false;
			}
		}), "la version modifiée est gardée dans la corbeille");
		server.runOnServer(s -> Village.generated(center, "gothique", 5, 0, false).build(ValcombeMod.worlds().level(trial)));
		check(server.computeOnServer(s -> ValcombeMod.worlds().level(trial).getBlockState(center.above(5)).is(Blocks.BELL)),
			"le village généré retrouve le puits d'origine");
		ctx.runOnClient(mc -> mc.gui.setScreen(null));

		// 8. Nouvelle structure : une parcelle vide, bâtie, enregistrée, renommée, supprimée.
		String name = "Tour essai " + (System.currentTimeMillis() % 100000);
		send(ctx, "vc structures nouvelle petite " + name);
		check(waitFor(ctx, world, 400, s -> workshop().parcels().stream().anyMatch(p -> p.nom().equals(name)) && !workshop().busy()),
			"parcelle neuve « " + name + " »");
		StructureWorkshop.ParcelInfo tower = server.computeOnServer(s -> workshop().parcels().stream().filter(p -> p.nom().equals(name))
			.findFirst().orElseThrow());
		check(tower.etat().equals("nouvelle") && tower.categorie().equals("perso") && tower.rayon() == 5, "« nouvelle », rangée « Vos structures », 11 × 11");
		check(server.computeOnServer(s -> player(s, me).level() == atelier()
			&& Math.abs(player(s, me).getX() - (tower.x() + 0.5)) < 1 && player(s, me).getZ() < tower.lecternZ()), "conduit devant son pupitre");
		server.runOnServer(s -> {
			for (int y = S + 1; y <= S + 4; y++) {
				atelier().setBlockAndUpdate(new BlockPos(tower.x(), y, tower.z()), Blocks.STONE_BRICKS.defaultBlockState());
			}
			atelier().setBlockAndUpdate(new BlockPos(tower.x(), S + 5, tower.z()), Blocks.LANTERN.defaultBlockState());
			atelier().setBlockAndUpdate(new BlockPos(tower.x() + 1, S, tower.z()), Blocks.AIR.defaultBlockState()); // un trou dans le sol
		});
		ctx.waitTicks(10);
		shot(ctx, "nouvelle");
		send(ctx, "vc structures enregistrer");
		check(waitFor(ctx, world, 60, s -> store().exists(tower.id())), "nouvelle structure enregistrée : " + tower.id());
		String def = server.computeOnServer(s -> {
			StructureStore.Def d = store().def(tower.id());
			return d.nom + " | " + d.taille[0] + "×" + d.taille[1] + "×" + d.taille[2] + " | ancre " + d.ancre[0] + "," + d.ancre[1] + ","
				+ d.ancre[2] + " | " + (d.fournie ? "fournie" : "créée") + " | " + workshop().parcel(tower.id()).etat();
		});
		System.out.println("[valcombe-test] atelier structures : " + def);
		check(def.equals(name + " | 2×6×1 | ancre 0,1,0 | créée | creee"),
			"la structure capturée : le sol laissé de côté, le trou gardé, l'origine pour ancre : " + def);
		send(ctx, "vc structures renommer Tour de garde");
		check(server.computeOnServer(s -> store().def(tower.id()).nom.equals("Tour de garde")
			&& workshop().parcel(tower.id()).nom().equals("Tour de garde")), "structure renommée");
		send(ctx, "vc structures supprimer");
		check(server.computeOnServer(s -> store().exists(tower.id())), "supprimer demande une confirmation");
		send(ctx, "vc structures supprimer confirmer");
		check(waitFor(ctx, world, 200, s -> !store().exists(tower.id()) && workshop().parcel(tower.id()) == null && !workshop().busy()),
			"structure supprimée, parcelle retirée");
		check(server.computeOnServer(s -> atelier().getBlockState(new BlockPos(tower.x(), S + 2, tower.z())).isAir()
			&& atelier().getBlockState(new BlockPos(tower.x(), S, tower.z())).is(Blocks.GRASS_BLOCK)), "sa place est rendue au sol plat");
		check(server.computeOnServer(s -> {
			try (var files = Files.list(store().trashDir())) {
				return files.anyMatch(f -> f.getFileName().toString().endsWith("-" + tower.id() + ".nbt"));
			} catch (java.io.IOException e) {
				return false;
			}
		}), "la structure supprimée est dans la corbeille");

		// Ménage : le monde du village d'essai.
		server.runOnServer(s -> ValcombeMod.worlds().delete(trial));
		check(waitFor(ctx, world, 600, s -> ValcombeMod.worlds().find(trial) == null && !ValcombeMod.worlds().busy(trial)), "village d'essai supprimé");
	}
}
