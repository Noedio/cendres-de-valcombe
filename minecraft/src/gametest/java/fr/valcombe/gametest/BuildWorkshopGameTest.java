package fr.valcombe.gametest;

import fr.valcombe.client.BuildSelection;
import fr.valcombe.client.ClientState;
import fr.valcombe.client.screen.WorldsScreen;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.ValcombeUi;
import fr.valcombe.mc.WorldEditGate;
import fr.valcombe.mc.worlds.BuildAccess;
import fr.valcombe.mc.worlds.WorldKind;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * La construction dans les ateliers, en jeu :
 * <ol>
 * <li>WorldEdit refusé au hub et dans un monde de partie, opérateur compris : permissions refusées, commandes absentes
 * de l'arbre du joueur, et une phrase claire pour une commande tapée quand même ;</li>
 * <li>permis dans un atelier pour un opérateur : mode créatif et baguette à l'arrivée, sélection dessinée en 3D avec ses
 * dimensions (capture), remplissage, « annuler » commun (/vc annuler, /vc retablir) ;</li>
 * <li>« Sélection → structure du catalogue » (/vc structures depuis-selection), qui voyage dans un pack .valcombe ;</li>
 * <li>un joueur qui n'est pas opérateur : l'atelier lui est caché ; invité, il le voit dans le menu « Mondes » (capture),
 * y va, y construit en créatif avec WorldEdit (sans les permissions qui touchent au serveur) ; son invitation
 * retirée, il revient au hub, dans son mode d'avant.</li>
 * </ol>
 */
public class BuildWorkshopGameTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
		System.out.println("[valcombe-test] ateliers : " + what);
	}

	private static void shot(ClientGameTestContext ctx, String name) {
		ctx.waitTicks(10);
		System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-" + name));
	}

	/** Une commande /vc envoyée par l'écran du mod (comme un bouton). */
	private static void send(ClientGameTestContext ctx, String cmd) {
		ctx.runOnClient(mc -> ClientState.send(cmd));
		ctx.waitTicks(10);
	}

	/** Une commande tapée dans le chat (sans la première barre : « /set stone » pour //set stone). */
	private static void type(ClientGameTestContext ctx, String command) {
		ctx.runOnClient(mc -> mc.player.connection.sendCommand(command));
		ctx.waitTicks(10);
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

	private static ServerPlayer player(MinecraftServer s, String name) {
		return s.getPlayerList().getPlayerByName(name);
	}

	private static String dimensionOf(MinecraftServer s, String name) {
		return player(s, name).level().dimension().identifier().toString();
	}

	/** Comme HubGameTest : en partie solo, être opérateur, c'est avoir les commandes permises dans le monde. */
	private static void operator(TestSingleplayerContext world, String me, boolean op) {
		world.getServer().runOnServer(s -> {
			s.getWorldData().setAllowCommands(op);
			s.getPlayerList().sendPlayerPermissionLevel(player(s, me));
		});
	}

	/** Réponse de l'API de permissions de Fabric à une permission de WorldEdit, telle que WorldEdit la demande. */
	private static Boolean permission(TestSingleplayerContext world, String me, String path) {
		return world.getServer().computeOnServer(s -> player(s, me)
			.checkPermission(PermissionNode.of(Identifier.fromNamespaceAndPath("worldedit", path))));
	}

	/** Le joueur a-t-il reçu la commande //set dans son arbre des commandes ? */
	private static boolean clientHasSet(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> mc.player.connection.getCommands().getRoot().getChild("/set") != null);
	}

	/** La phrase qu'un joueur recevrait pour cette commande tapée là où il est (null : elle passerait). */
	private static String refusal(TestSingleplayerContext world, String me, String command) {
		return world.getServer().computeOnServer(s -> {
			CommandSourceStack source = player(s, me).createCommandSourceStack();
			var c = WorldEditGate.refuseCommand(source, command);
			return c == null ? null : c.getString();
		});
	}

	private static int count(TestSingleplayerContext world, String id, BlockPos a, BlockPos b, Block block) {
		return world.getServer().computeOnServer(s -> {
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

	/** Combien de cet objet le joueur a dans son inventaire. */
	private static int count(ServerPlayer p, Item item) {
		var inv = p.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).is(item)) {
				n += inv.getItem(i).getCount();
			}
		}
		return n;
	}

	private static boolean hasWand(MinecraftServer s, String me) {
		var inv = player(s, me).getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (ValcombeUi.isWand(inv.getItem(i))) {
				return true;
			}
		}
		return false;
	}

	private static void go(ClientGameTestContext ctx, TestSingleplayerContext world, String me, String id) {
		world.getServer().runOnServer(s -> {
			if (!dimensionOf(s, me).equals("valcombe:" + id)) {
				ValcombeMod.travel().go(player(s, me), id, null);
			}
		});
		check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals("valcombe:" + id)), "arrivée dans " + id);
		ctx.waitTicks(20);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			ctx.waitTicks(60);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			var server = world.getServer();
			server.runCommand("op " + me);
			ctx.waitTicks(40);
			long n = System.currentTimeMillis() % 100000;
			String atelier = server.computeOnServer(s -> ValcombeMod.worlds().create(WorldKind.ATELIER_CARTE, "Atelier invite " + n).id());
			String partie = server.computeOnServer(s -> ValcombeMod.worlds().create(WorldKind.PARTIE, "Partie sans WorldEdit " + n).id());
			String hub = server.computeOnServer(s -> ValcombeMod.travel().hub().id());

			// 1. Au hub et dans une partie : WorldEdit refusé, même à un opérateur en créatif.
			for (String id : List.of(hub, partie)) {
				go(ctx, world, me, id);
				server.runOnServer(s -> player(s, me).setGameMode(GameType.CREATIVE));
				ctx.waitTicks(5);
				check(Boolean.FALSE.equals(permission(world, me, "region.set"))
					&& Boolean.FALSE.equals(permission(world, me, "selection.pos")), "permissions de WorldEdit refusées dans " + id);
				check(!clientHasSet(ctx), "//set absent de l'arbre des commandes du joueur dans " + id);
				String said = refusal(world, me, "/set stone");
				check(said != null && said.contains("ateliers"), "commande WorldEdit tapée dans " + id + " : « " + said + " »");
				check(refusal(world, me, "vc monde liste") == null, "les autres commandes passent");
				check(!server.computeOnServer(s -> BuildAccess.current().canUseWorldEdit(player(s, me))), "BuildAccess : pas de WorldEdit");
				check(server.computeOnServer(s -> BuildAccess.current().canBuild(player(s, me))), "l'opérateur en créatif construit à la main");
				server.runOnServer(s -> player(s, me).setGameMode(GameType.ADVENTURE));
			}
			// Une baguette en main au hub : le clic ne pose aucun coin.
			go(ctx, world, me, hub);
			server.runOnServer(s -> player(s, me).getInventory().add(ValcombeUi.wandItem()));
			check(server.computeOnServer(s -> WorldEditGate.wandRefused(player(s, me))), "clic de la baguette refusé au hub");

			// 2. Dans l'atelier, en opérateur : créatif, baguette, commandes de WorldEdit, sélection dessinée en 3D.
			go(ctx, world, me, atelier);
			check(server.computeOnServer(s -> player(s, me).gameMode.getGameModeForPlayer() == GameType.CREATIVE), "mode créatif dans l'atelier");
			check(server.computeOnServer(s -> hasWand(s, me)), "baguette de construction donnée");
			check(!Boolean.FALSE.equals(permission(world, me, "region.set")), "WorldEdit permis à l'opérateur dans l'atelier");
			check(clientHasSet(ctx), "//set dans l'arbre des commandes du joueur, dans l'atelier");
			check(refusal(world, me, "/set stone") == null, "pas de refus dans l'atelier");
			BlockPos a = new BlockPos(3, 65, 3);
			BlockPos b = a.offset(5, 3, 7); // L 6 × l 8 × h 4 = 192 blocs
			type(ctx, String.format(Locale.ROOT, "/pos1 %d,%d,%d", a.getX(), a.getY(), a.getZ()));
			type(ctx, String.format(Locale.ROOT, "/pos2 %d,%d,%d", b.getX(), b.getY(), b.getZ()));
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> BuildSelection.shown()), "sélection reçue par le client");
			String label = ctx.computeOnClient(mc -> BuildSelection.dimensions(BuildSelection.current().box()) + " · "
				+ BuildSelection.blocks(BuildSelection.current().shape(), BuildSelection.current().volume()));
			check(label.equals("L 6 × l 8 × h 4 · 192 blocs"), "étiquette de la sélection : " + label);
			type(ctx, "/walls stone_bricks");
			int walls = count(world, atelier, a, b, Blocks.STONE_BRICKS);
			// Le tour d'un rectangle de 6 × 8 (24 blocs), sur 4 de haut.
			check(walls == 96, "//walls a monté les murs (" + walls + " blocs sur 96)");
			server.runOnServer(s -> player(s, me).teleportTo(ValcombeMod.worlds().level(atelier), 14.5, 70, -6.5, java.util.Set.of(), 40f, 25f,
				false));
			ctx.waitTicks(30);
			shot(ctx, "atelier-selection-3d");

			// L'« annuler » commun : la dernière modification WorldEdit, puis rétablie.
			send(ctx, "vc annuler");
			ctx.waitTicks(10);
			check(count(world, atelier, a, b, Blocks.STONE_BRICKS) == 0, "/vc annuler défait //walls");
			send(ctx, "vc retablir");
			ctx.waitTicks(10);
			check(count(world, atelier, a, b, Blocks.STONE_BRICKS) == walls, "/vc retablir le refait");
			check(server.computeOnServer(s -> WorldEditGate.current().undo().undoable(player(s, me))) >= 1, "un geste à annuler");

			// 3. La sélection devient une structure du catalogue, qui voyage dans un pack .valcombe.
			send(ctx, "vc structures depuis-selection essai-atelier \"Murs d'essai\"");
			String saved = server.computeOnServer(s -> {
				var store = ValcombeMod.structures();
				var def = store.def("essai-atelier");
				String size = def.taille[0] + "×" + def.taille[1] + "×" + def.taille[2] + " " + def.blocs + " " + def.nom;
				String pack = ValcombeMod.creations().exportPack(List.of(), List.of(), List.of(), List.of("essai-atelier"));
				store.delete("essai-atelier");
				String back = ValcombeMod.creations().importPack(pack);
				var again = store.def("essai-atelier");
				String result = size + " | " + back + " | " + again.taille[0] + "×" + again.taille[1] + "×" + again.taille[2];
				store.delete("essai-atelier");
				return result;
			});
			check(saved.startsWith("6×4×8 192 Murs d'essai") && saved.contains("Importé : structure « Murs d'essai »")
				&& saved.endsWith("6×4×8"), "sélection enregistrée dans le catalogue puis reçue d'un pack : " + saved);
			ctx.setScreen(() -> new fr.valcombe.client.screen.AtelierScreen(4));
			shot(ctx, "atelier-onglet-worldedit");
			ctx.setScreen(() -> null);

			// 4. Un joueur qui n'est pas opérateur : l'atelier lui est caché, puis il y est invité.
			go(ctx, world, me, hub);
			operator(world, me, false);
			ctx.waitTicks(20);
			check(server.computeOnServer(s -> ValcombeMod.travel().destinations(player(s, me)).stream()
				.noneMatch(d -> d.id().equals(atelier))), "atelier caché à un joueur qui n'est pas opérateur");
			server.runCommand("vc atelier inviter " + me + " " + atelier);
			ctx.waitTicks(20);
			check(server.computeOnServer(s -> BuildAccess.current().invitations().guests(atelier).contains(me)), "invitation enregistrée");
			check(server.computeOnServer(s -> ValcombeMod.travel().destinations(player(s, me)).stream()
				.anyMatch(d -> d.id().equals(atelier))), "l'atelier apparaît dans les mondes de l'invité");
			ctx.setScreen(() -> new WorldsScreen(atelier));
			ctx.waitTicks(20);
			check(ctx.computeOnClient(mc -> WorldsScreen.destinations().stream()
				.anyMatch(d -> ClientState.str(d, "id").equals(atelier) && ClientState.bool(d, "invite"))), "menu « Mondes » : invité");
			shot(ctx, "mondes-atelier-invite");
			ctx.setScreen(() -> null);
			// Ce que l'invité porte en entrant : il le retrouvera en sortant.
			server.runOnServer(s -> player(s, me).getInventory().add(new ItemStack(Items.APPLE, 3)));
			send(ctx, "vc monde aller " + atelier);
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals("valcombe:" + atelier)), "l'invité va dans l'atelier");
			ctx.waitTicks(20);
			check(server.computeOnServer(s -> player(s, me).gameMode.getGameModeForPlayer() == GameType.CREATIVE), "l'invité est en créatif");
			check(server.computeOnServer(s -> hasWand(s, me)), "l'invité a la baguette");
			check(server.computeOnServer(s -> count(player(s, me), Items.APPLE) == 0), "inventaire de l'invité mis de côté à l'entrée");
			// Pris en créatif dans l'atelier : ne doit pas en sortir.
			server.runOnServer(s -> player(s, me).getInventory().add(new ItemStack(Items.ENDER_PEARL, 16)));
			check(Boolean.TRUE.equals(permission(world, me, "region.set")), "WorldEdit permis à l'invité");
			check(Boolean.FALSE.equals(permission(world, me, "reload")) && Boolean.FALSE.equals(permission(world, me, "world")),
				"pas ce qui touche au serveur");
			check(Boolean.FALSE.equals(permission(world, me, "history.undo.other"))
				&& Boolean.FALSE.equals(permission(world, me, "clipboard.save")), "ni le travail des autres, ni les fichiers de schémas");
			check(clientHasSet(ctx), "//set dans l'arbre des commandes de l'invité");
			BlockPos c = new BlockPos(-6, 70, -6);
			BlockPos d = c.offset(2, 2, 2);
			type(ctx, String.format(Locale.ROOT, "/pos1 %d,%d,%d", c.getX(), c.getY(), c.getZ()));
			type(ctx, String.format(Locale.ROOT, "/pos2 %d,%d,%d", d.getX(), d.getY(), d.getZ()));
			type(ctx, "/set oak_planks");
			check(count(world, atelier, c, d, Blocks.OAK_PLANKS) == 27, "l'invité construit avec WorldEdit");
			send(ctx, "vc annuler");
			ctx.waitTicks(10);
			check(count(world, atelier, c, d, Blocks.OAK_PLANKS) == 0, "/vc annuler pour l'invité");

			// Invitation retirée : retour au hub, dans son mode d'avant, sans baguette.
			server.runCommand("vc atelier retirer " + me);
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals("valcombe:" + hub)), "invitation retirée : retour au hub");
			ctx.waitTicks(10);
			check(server.computeOnServer(s -> player(s, me).gameMode.getGameModeForPlayer() != GameType.CREATIVE && !hasWand(s, me)),
				"hors de l'atelier : mode d'avant, baguette rendue");
			check(server.computeOnServer(s -> count(player(s, me), Items.APPLE) == 3 && count(player(s, me), Items.ENDER_PEARL) == 0),
				"hors de l'atelier : son inventaire rendu, rien de ce qu'il y a pris");
			check(server.computeOnServer(s -> BuildAccess.current().invitations().list().stream().noneMatch(i -> i.joueur().equals(me))),
				"plus d'invitation");

			// Ménage : opérateur de nouveau, mondes d'essai supprimés.
			operator(world, me, true);
			ctx.waitTicks(20);
			for (String id : List.of(atelier, partie)) {
				AtomicReference<CompletableFuture<Void>> gone = new AtomicReference<>();
				server.runOnServer(s -> gone.set(ValcombeMod.worlds().delete(id)));
				check(waitFor(ctx, world, 600, s -> gone.get().isDone()), "monde supprimé : " + id);
			}
		}
	}
}
