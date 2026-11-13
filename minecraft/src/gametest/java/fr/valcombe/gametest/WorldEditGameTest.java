package fr.valcombe.gametest;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;

import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.ValcombeUi;
import fr.valcombe.mc.worlds.WorldKind;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * WorldEdit fait partie du jeu : il est chargé avec le mod Valcombe, ses commandes sont enregistrées, une vraie
 * construction passe dans un atelier (sélection, remplissage, annulation), et l'API avec laquelle le mod est compilé
 * (build.gradle, compileOnly) est bien celle du mod chargé. La baguette de WorldEdit est celle de Valcombe (réglage
 * wand-item) ; //wand en donne une qui prend son apparence ; ses clics posent les coins de WorldEdit. La boussole
 * « Carte : centre » de Valcombe garde la main : la navigation de WorldEdit lui est refusée.
 * <p>
 * Les limites (hub, partie, invités) sont vérifiées par BuildWorkshopGameTest.
 */
public class WorldEditGameTest implements FabricClientGameTest {

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
	}

	private static boolean waitFor(ClientGameTestContext ctx, TestSingleplayerContext world, int ticks, Predicate<MinecraftServer> cond) {
		for (int i = 0; i < ticks; i += 2) {
			if (world.getServer().computeOnServer(cond::test)) {
				return true;
			}
			ctx.waitTicks(2);
		}
		return world.getServer().computeOnServer(cond::test);
	}

	/** Combien de blocs de cette sorte dans le pavé (coins compris), dans ce monde. */
	private static int count(TestSingleplayerContext world, String id, BlockPos a, BlockPos b, Block block) {
		return world.getServer().computeOnServer(server -> {
			ServerLevel level = ValcombeMod.worlds().level(id);
			int n = 0;
			for (BlockPos pos : BlockPos.betweenClosed(a, b)) {
				if (level.getBlockState(pos).is(block)) {
					n++;
				}
			}
			return n;
		});
	}

	/** Une commande WorldEdit tapée par le joueur, comme dans le chat (sans la première barre). */
	private static void type(ClientGameTestContext ctx, String command) {
		ctx.runOnClient(mc -> mc.player.connection.sendCommand(command));
		ctx.waitTicks(10);
	}

	/** La sélection WorldEdit du joueur, lue par l'API de WorldEdit (null si elle est incomplète). */
	private static Region selection(TestSingleplayerContext world, String me) {
		return world.getServer().computeOnServer(server -> {
			LocalSession session = WorldEdit.getInstance().getSessionManager().findByName(me);
			try {
				return session == null ? null : session.getSelection();
			} catch (IncompleteRegionException e) {
				return null;
			}
		});
	}

	private static BlockVector3 vec(BlockPos p) {
		return BlockVector3.at(p.getX(), p.getY(), p.getZ());
	}

	/** Met en main (case choisie de la barre) le premier objet de l'inventaire qui convient ; faux s'il n'y en a pas. */
	private static boolean hold(ClientGameTestContext ctx, TestSingleplayerContext world, String me, Predicate<ItemStack> wanted) {
		boolean done = world.getServer().computeOnServer(server -> {
			var inv = server.getPlayerList().getPlayerByName(me).getInventory();
			int selected = inv.getSelectedSlot();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (wanted.test(inv.getItem(i))) {
					ItemStack found = inv.getItem(i);
					inv.setItem(i, inv.getItem(selected));
					inv.setItem(selected, found);
					return true;
				}
			}
			return false;
		});
		ctx.waitTicks(10);
		return done;
	}

	/** Réponse de l'API de permissions de Fabric pour une permission de WorldEdit, telle que WorldEdit la demande. */
	private static Boolean worldEditPermission(TestSingleplayerContext world, String me, String path) {
		return world.getServer().computeOnServer(server -> server.getPlayerList().getPlayerByName(me)
			.checkPermission(PermissionNode.of(Identifier.fromNamespaceAndPath("worldedit", path))));
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		// Le mod est chargé, côté client comme pour le serveur intégré.
		String version = ctx.computeOnClient(mc -> FabricLoader.getInstance().getModContainer("worldedit")
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(""));
		check(!version.isEmpty(), "WorldEdit est chargé");
		System.out.println("[valcombe-test] WorldEdit " + version + " (API : " + WorldEdit.getVersion() + ")");

		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			ctx.waitTicks(60);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			world.getServer().runCommand("op " + me);
			ctx.waitTicks(40);

			// Les commandes sont enregistrées sur le serveur (« //wand » s'écrit « /wand » dans l'arbre des commandes).
			check(world.getServer().computeOnServer(server -> {
				var root = server.getCommands().getDispatcher().getRoot();
				return root.getChild("/wand") != null && root.getChild("/pos1") != null && root.getChild("/set") != null;
			}), "commandes de WorldEdit enregistrées sur le serveur");
			// La baguette de WorldEdit est l'objet de la baguette de construction de Valcombe.
			check(ValcombeUi.WAND_ID.equals(world.getServer().computeOnServer(s -> WorldEdit.getInstance().getConfiguration().wandItem)),
				"baguette de WorldEdit réglée sur celle de Valcombe");

			// Un atelier, où WorldEdit sert.
			String id = world.getServer().computeOnServer(s -> ValcombeMod.worlds().create(WorldKind.ATELIER_CARTE,
				"Atelier WorldEdit " + (System.currentTimeMillis() % 100000)).id());
			world.getServer().runOnServer(s -> ValcombeMod.travel().go(s.getPlayerList().getPlayerByName(me), id, null));
			check(waitFor(ctx, world, 100, s -> s.getPlayerList().getPlayerByName(me).level().dimension().identifier().toString()
				.equals("valcombe:" + id)), "arrivée dans l'atelier");
			ctx.waitTicks(20);
			check(ctx.computeOnClient(mc -> mc.player.connection.getCommands().getRoot().getChild("/set") != null),
				"commandes de WorldEdit reçues par le joueur dans l'atelier");

			// Un pavé de 3 x 3 x 3 en l'air, rempli puis annulé.
			BlockPos a = new BlockPos(4, 75, 4);
			BlockPos b = a.offset(2, 2, 2);
			check(count(world, id, a, b, Blocks.GOLD_BLOCK) == 0, "pavé vide au départ");
			type(ctx, String.format(Locale.ROOT, "/pos1 %d,%d,%d", a.getX(), a.getY(), a.getZ()));
			type(ctx, String.format(Locale.ROOT, "/pos2 %d,%d,%d", b.getX(), b.getY(), b.getZ()));

			// La sélection se lit par l'API compilée avec le mod (session WorldEdit du joueur).
			Region sel = selection(world, me);
			check(sel != null && sel.getVolume() == 27, "sélection de 27 blocs lue par l'API de WorldEdit (lu : " + sel + ")");

			type(ctx, "/set gold_block");
			int filled = count(world, id, a, b, Blocks.GOLD_BLOCK);
			check(filled == 27, "//set remplit le pavé (" + filled + " blocs d'or sur 27)");

			type(ctx, "/undo");
			int left = count(world, id, a, b, Blocks.GOLD_BLOCK);
			check(left == 0, "//undo vide le pavé (" + left + " blocs d'or restants)");
			System.out.println("[valcombe-test] WorldEdit : sélection, remplissage et annulation vérifiés dans l'atelier");

			checkWand(ctx, world, me, id, a);

			AtomicReference<CompletableFuture<Void>> gone = new AtomicReference<>();
			world.getServer().runOnServer(s -> gone.set(ValcombeMod.worlds().delete(id)));
			check(waitFor(ctx, world, 600, s -> gone.get().isDone()), "atelier supprimé");
		}
	}

	/**
	 * La baguette de construction : //wand en donne une « nue », qui prend l'apparence de celle de Valcombe ; ses vrais
	 * clics posent les coins de WorldEdit sans casser le bloc. La boussole « Carte : centre » de Valcombe, elle, ne
	 * déclenche pas la navigation de WorldEdit.
	 */
	private static void checkWand(ClientGameTestContext ctx, TestSingleplayerContext world, String me, String id, BlockPos weCorner) {
		// Deux blocs de pierre devant le joueur, posés sur l'herbe (dessus du sol plat : y = 64).
		BlockPos first = new BlockPos(2, 65, -1);
		BlockPos second = new BlockPos(2, 65, 1);
		world.getServer().runOnServer(s -> {
			ServerLevel level = ValcombeMod.worlds().level(id);
			level.setBlockAndUpdate(first, Blocks.STONE.defaultBlockState());
			level.setBlockAndUpdate(second, Blocks.STONE.defaultBlockState());
			var p = s.getPlayerList().getPlayerByName(me);
			p.teleportTo(level, 0.5, 65, 0.5, java.util.Set.of(), -90f, 30f, false);
			// La baguette donnée à l'arrivée est rangée : //wand doit en donner une autre.
			var inv = p.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (ValcombeUi.isWand(inv.getItem(i))) {
					inv.setItem(i, ItemStack.EMPTY);
				}
			}
		});
		ctx.waitTicks(10);
		type(ctx, "/wand");
		ctx.waitTicks(30); // la baguette nue prend l'apparence de celle de Valcombe en moins d'une seconde
		check(hold(ctx, world, me, ValcombeUi::isWand), "//wand donne la baguette de construction de Valcombe");
		ctx.runOnClient(mc -> mc.gameMode.startDestroyBlock(first, Direction.UP));
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
			new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(second), Direction.UP, second, false)));
		ctx.waitTicks(10);
		Region wand = selection(world, me);
		check(wand != null && wand.contains(vec(first)) && wand.contains(vec(second)) && !wand.contains(vec(weCorner))
			&& world.getServer().computeOnServer(s -> ValcombeMod.worlds().level(id).getBlockState(first).is(Blocks.STONE)),
			"les clics de la baguette posent les coins de WorldEdit, sans casser le bloc (" + wand + ")");

		// La boussole de Valcombe en main : WorldEdit n'a pas le droit de téléporter (clic droit ou gauche).
		world.getServer().runOnServer(s -> ValcombeUi.mapTools(s.getPlayerList().getPlayerByName(me), true));
		check(hold(ctx, world, me, s -> ValcombeUi.mapTool(s) == ValcombeUi.MapTool.CENTER), "outil « Carte : centre » en main");
		check(Boolean.FALSE.equals(worldEditPermission(world, me, "navigation.jumpto.tool"))
			&& Boolean.FALSE.equals(worldEditPermission(world, me, "navigation.thru.tool")),
			"navigation de WorldEdit refusée avec la boussole de Valcombe");
		check(hold(ctx, world, me, ValcombeUi::isWand), "baguette de nouveau en main");
		check(!Boolean.FALSE.equals(worldEditPermission(world, me, "navigation.thru.tool")),
			"navigation de WorldEdit permise sans outil de Valcombe en main, dans l'atelier");
		world.getServer().runOnServer(s -> ValcombeUi.mapTools(s.getPlayerList().getPlayerByName(me), false));
		check(java.util.Arrays.stream(ValcombeUi.MapTool.values()).noneMatch(t -> t.name().equals("SELECT")),
			"plus d'outil « Carte : sélection » : la sélection est celle de WorldEdit");
		System.out.println("[valcombe-test] WorldEdit : la baguette de Valcombe pose ses coins, la boussole garde ses clics");
	}
}
