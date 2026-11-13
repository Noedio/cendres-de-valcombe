package fr.valcombe.gametest;

import fr.valcombe.client.BuildSelection;
import fr.valcombe.client.ClientState;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.worlds.WorldKind;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Finitions des ateliers, en jeu :
 * <ol>
 * <li>plus aucune notification de progrès Minecraft dans un monde de Valcombe : la règle announceAdvancements est à
 * faux (monde et serveur), et un progrès obtenu dans un atelier ne fait apparaître aucune bulle ;</li>
 * <li>les étiquettes de la sélection 3D (plaques à fond sombre, lettres cernées) lisibles de près comme de loin :
 * captures à 6 et à 45 blocs.</li>
 * </ol>
 */
public class AteliersFinitionsGameTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
		System.out.println("[valcombe-test] finitions des ateliers : " + what);
	}

	private static void shot(ClientGameTestContext ctx, String name) {
		ctx.waitTicks(10);
		System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-" + name));
	}

	private static ServerPlayer player(MinecraftServer s, String name) {
		return s.getPlayerList().getPlayerByName(name);
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

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			ctx.waitTicks(60);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			var server = world.getServer();
			server.runCommand("op " + me);
			ctx.waitTicks(40);
			long n = System.currentTimeMillis() % 100000;
			String atelier = server.computeOnServer(s -> ValcombeMod.worlds().create(WorldKind.ATELIER_CARTE, "Finitions " + n).id());
			server.runOnServer(s -> ValcombeMod.travel().go(player(s, me), atelier, null));
			check(waitFor(ctx, world, 200, s -> player(s, me).level() == ValcombeMod.worlds().level(atelier)), "arrivée dans l'atelier");
			ctx.waitTicks(40);

			// 1. Pas d'annonce de progrès : la règle, dans le monde de l'atelier et pour le serveur.
			check(server.computeOnServer(s -> !ValcombeMod.worlds().level(atelier).getGameRules().get(GameRules.SHOW_ADVANCEMENT_MESSAGES)
				&& !s.getGameRules().get(GameRules.SHOW_ADVANCEMENT_MESSAGES)), "announceAdvancements à faux dans l'atelier et sur le serveur");
			check(ctx.computeOnClient(mc -> ClientState.onValcombeServer()), "le client se sait sur un serveur de Valcombe");
			// Un progrès obtenu dans l'atelier (comme toucher un feu en bâtissant) : aucune bulle.
			ctx.runOnClient(mc -> mc.gui.toastManager().clear());
			server.runCommand("advancement grant " + me + " only minecraft:story/mine_stone");
			check(waitFor(ctx, world, 60, s -> {
				var adv = s.getAdvancements().get(net.minecraft.resources.Identifier.withDefaultNamespace("story/mine_stone"));
				return adv != null && player(s, me).getAdvancements().getOrStartProgress(adv).isDone();
			}), "le progrès est bien obtenu (côté serveur)");
			ctx.waitTicks(40);
			check(ctx.computeOnClient(mc -> mc.gui.toastManager().getToast(AdvancementToast.class, Toast.NO_TOKEN) == null),
				"aucune bulle « Progrès réalisé ! » à l'écran");
			shot(ctx, "finitions-sans-bulle-de-progres");

			// 2. Les étiquettes de la sélection 3D, de près puis de loin.
			type(ctx, String.format(Locale.ROOT, "/pos1 %d,%d,%d", 3, 65, 3));
			type(ctx, String.format(Locale.ROOT, "/pos2 %d,%d,%d", 10, 70, 14));
			for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> BuildSelection.shown()); i += 5) {
				ctx.waitTicks(5);
			}
			check(ctx.computeOnClient(mc -> BuildSelection.shown()), "sélection reçue par le client");
			server.runOnServer(s -> {
				ServerPlayer p = player(s, me);
				p.getAbilities().flying = true;
				p.onUpdateAbilities();
				p.teleportTo(ValcombeMod.worlds().level(atelier), 6.5, 69, -3.5, Set.of(), -15f, 25f, false);
			});
			ctx.waitTicks(30);
			shot(ctx, "finitions-selection-de-pres");
			server.runOnServer(s -> player(s, me).teleportTo(ValcombeMod.worlds().level(atelier), 38.5, 88, -26.5, Set.of(), 45f, 28f, false));
			ctx.waitTicks(30);
			shot(ctx, "finitions-selection-de-loin");

			// Ménage : l'atelier d'essai ne reste pas dans « Ateliers ouverts » pour les tests suivants.
			server.runOnServer(s -> ValcombeMod.worlds().delete(atelier));
			check(waitFor(ctx, world, 600, s -> ValcombeMod.worlds().find(atelier) == null), "atelier d'essai supprimé");
		}
	}

	/** Une commande tapée dans le chat (sans la première barre : « /pos1 » pour //pos1). */
	private static void type(ClientGameTestContext ctx, String command) {
		ctx.runOnClient(mc -> mc.player.connection.sendCommand(command));
		ctx.waitTicks(10);
	}
}
