package fr.valcombe.gametest;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;

import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.ValcombeUi;

import java.util.Locale;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * WorldEdit fait partie du jeu : il est chargé avec le mod Valcombe, ses commandes arrivent jusqu'au joueur
 * opérateur, une vraie construction passe (sélection, remplissage, annulation), et l'API avec laquelle le mod
 * est compilé (build.gradle, compileOnly) est bien celle du mod chargé. Les outils de carte de Valcombe gardent
 * la main sur leurs clics (hache « Carte : sélection », boussole « Carte : centre »), et la hache de //wand
 * reste celle de WorldEdit.
 */
public class WorldEditGameTest implements FabricClientGameTest {

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
	}

	/** Combien de blocs de cette sorte dans le pavé (coins compris). */
	private static int count(TestSingleplayerContext world, BlockPos a, BlockPos b, Block block) {
		return world.getServer().computeOnServer(server -> {
			int n = 0;
			for (BlockPos pos : BlockPos.betweenClosed(a, b)) {
				if (server.overworld().getBlockState(pos).is(block)) {
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

			// Les commandes sont enregistrées sur le serveur et envoyées au joueur opérateur (« //wand » s'écrit
			// « /wand » dans l'arbre des commandes).
			check(world.getServer().computeOnServer(server -> {
				var root = server.getCommands().getDispatcher().getRoot();
				return root.getChild("/wand") != null && root.getChild("/pos1") != null && root.getChild("/set") != null;
			}), "commandes de WorldEdit enregistrées sur le serveur");
			check(ctx.computeOnClient(mc -> mc.player.connection.getCommands().getRoot().getChild("/set") != null),
				"commandes de WorldEdit reçues par le joueur opérateur");

			// Un pavé de 3 x 3 x 3 en l'air, au-dessus du joueur, rempli puis annulé.
			BlockPos feet = world.getServer().computeOnServer(server ->
				server.getPlayerList().getPlayerByName(me).blockPosition());
			BlockPos a = feet.offset(4, 40, 4);
			BlockPos b = a.offset(2, 2, 2);
			check(count(world, a, b, Blocks.GOLD_BLOCK) == 0, "pavé vide au départ");
			type(ctx, String.format(Locale.ROOT, "/pos1 %d,%d,%d", a.getX(), a.getY(), a.getZ()));
			type(ctx, String.format(Locale.ROOT, "/pos2 %d,%d,%d", b.getX(), b.getY(), b.getZ()));

			// La sélection se lit par l'API compilée avec le mod (session WorldEdit du joueur).
			Region sel = selection(world, me);
			check(sel != null && sel.getVolume() == 27, "sélection de 27 blocs lue par l'API de WorldEdit (lu : " + sel + ")");

			type(ctx, "/set gold_block");
			int filled = count(world, a, b, Blocks.GOLD_BLOCK);
			check(filled == 27, "//set remplit le pavé (" + filled + " blocs d'or sur 27)");

			type(ctx, "/undo");
			int left = count(world, a, b, Blocks.GOLD_BLOCK);
			check(left == 0, "//undo vide le pavé (" + left + " blocs d'or restants)");
			System.out.println("[valcombe-test] WorldEdit : sélection, remplissage et annulation vérifiés");

			checkToolPriority(ctx, world, me, feet, a);
		}
	}

	/**
	 * Les outils de carte de Valcombe passent avant WorldEdit : de vrais clics avec la hache « Carte : sélection »
	 * posent les coins de Valcombe sans toucher à la sélection de WorldEdit ; la boussole « Carte : centre » ne
	 * déclenche pas la navigation de WorldEdit ; la hache sans nom donnée par //wand pose, elle, le coin de WorldEdit.
	 */
	private static void checkToolPriority(ClientGameTestContext ctx, TestSingleplayerContext world, String me,
			BlockPos feet, BlockPos weCorner) {
		BlockPos first = feet.offset(2, 0, -1);
		BlockPos second = feet.offset(2, 0, 1);
		world.getServer().runCommand(String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", first.getX(), first.getY(), first.getZ()));
		world.getServer().runCommand(String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", second.getX(), second.getY(), second.getZ()));
		// L'éditeur de Valcombe ne répond qu'en mode construction : on y entre (il donne aussi les outils de carte).
		world.getServer().runOnServer(server -> ValcombeMod.host().toggleConstruction(server.getPlayerList().getPlayerByName(me)));
		ctx.waitTicks(10);
		check(world.getServer().computeOnServer(server -> (Boolean) ValcombeMod.editor().state(me).get("construction")), "mode construction");

		check(hold(ctx, world, me, s -> ValcombeUi.mapTool(s) == ValcombeUi.MapTool.SELECT), "outil « Carte : sélection » en main");
		ctx.runOnClient(mc -> mc.gameMode.startDestroyBlock(first, Direction.UP));
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
			new BlockHitResult(Vec3.atCenterOf(second), Direction.UP, second, false)));
		ctx.waitTicks(10);
		String clicks = world.getServer().computeOnServer(server -> {
			var p = server.getPlayerList().getPlayerByName(me);
			var ed = ValcombeMod.editor();
			return "coin 1 " + ed.isCorner(p, 1, first) + " | coin 2 " + ed.isCorner(p, 2, second) + " | bloc intact "
				+ server.overworld().getBlockState(first).is(Blocks.STONE);
		});
		check(clicks.equals("coin 1 true | coin 2 true | bloc intact true"), "clics de l'outil de sélection de Valcombe : " + clicks);
		Region sel = selection(world, me);
		check(sel != null && sel.getVolume() == 27 && sel.contains(vec(weCorner)) && !sel.contains(vec(first)),
			"la sélection de WorldEdit n'a pas bougé (" + sel + ")");

		// La boussole de Valcombe en main : WorldEdit n'a pas le droit de téléporter (clic droit ou gauche).
		check(hold(ctx, world, me, s -> ValcombeUi.mapTool(s) == ValcombeUi.MapTool.CENTER), "outil « Carte : centre » en main");
		check(Boolean.FALSE.equals(worldEditPermission(world, me, "navigation.jumpto.tool"))
			&& Boolean.FALSE.equals(worldEditPermission(world, me, "navigation.thru.tool")),
			"navigation de WorldEdit refusée avec la boussole de Valcombe");

		// La hache sans nom de //wand reste la baguette de WorldEdit, et la navigation redevient la sienne.
		type(ctx, "/wand");
		check(hold(ctx, world, me, s -> s.is(Items.WOODEN_AXE) && ValcombeUi.mapTool(s) == null), "hache de //wand en main");
		check(!Boolean.FALSE.equals(worldEditPermission(world, me, "navigation.thru.tool")),
			"navigation de WorldEdit permise sans outil de Valcombe en main");
		ctx.runOnClient(mc -> mc.gameMode.startDestroyBlock(second, Direction.UP));
		ctx.waitTicks(10);
		Region wand = selection(world, me);
		check(wand != null && wand.contains(vec(second)) && world.getServer().computeOnServer(server ->
			server.overworld().getBlockState(second).is(Blocks.STONE)), "la baguette de WorldEdit pose son premier coin (" + wand + ")");
		check(world.getServer().computeOnServer(server -> ValcombeMod.editor().isCorner(server.getPlayerList().getPlayerByName(me), 1, first)),
			"le coin de Valcombe n'a pas bougé");
		world.getServer().runOnServer(server -> ValcombeMod.host().toggleConstruction(server.getPlayerList().getPlayerByName(me)));
		System.out.println("[valcombe-test] WorldEdit : les outils de Valcombe gardent leurs clics, //wand garde les siens");
	}
}
