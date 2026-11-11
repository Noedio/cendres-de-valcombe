package fr.valcombe.gametest;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.screen.MainScreen;
import fr.valcombe.client.screen.PromptScreen;
import fr.valcombe.client.screen.SheetsScreen;
import fr.valcombe.client.screen.StorytellerScreen;

import java.util.HashSet;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Joue une partie en solo avec des joueurs fictifs, en passant par les écrans du mod,
 * et prend une capture de chaque écran rencontré.
 */
public class ScreensGameTest implements FabricClientGameTest {
	private final Set<String> captured = new HashSet<>();

	private static JsonObject prompt(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> ClientState.obj(ClientState.view(), "prompt"));
	}

	private static String phase(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> ClientState.phase());
	}

	private void shot(ClientGameTestContext ctx, String name) {
		if (captured.add(name)) {
			ctx.waitTicks(10);
			System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-" + name));
		}
	}

	private static void send(ClientGameTestContext ctx, String cmd) {
		ctx.runOnClient(mc -> ClientState.send(cmd));
		ctx.waitTicks(10);
	}

	/** Répond à l'invite en cours en passant par l'écran d'invite (capture au premier passage de chaque type). */
	private void answer(ClientGameTestContext ctx, JsonObject p) {
		String type = ClientState.str(p, "type");
		ctx.setScreen(PromptScreen::new);
		shot(ctx, "invite-" + type);
		switch (type) {
			case "choose" -> {
				int count = ClientState.num(p, "count", 1);
				var opts = p.getAsJsonArray("options");
				for (int i = 0; i < count && i < opts.size(); i++) {
					send(ctx, "vc choisir " + opts.get(opts.size() - 1 - i).getAsJsonObject().get("name").getAsString());
				}
				shot(ctx, "invite-choose-selection");
				send(ctx, "vc valider");
			}
			case "info", "grimoire" -> send(ctx, "vc dormir");
			case "vote" -> send(ctx, "vc vote oui");
			case "confirm" -> send(ctx, "vc envoyer");
			default -> ctx.waitTicks(5);
		}
	}

	/** Joue jusqu'à la fin de la nuit en cours. */
	private void playNight(ClientGameTestContext ctx) {
		for (int guard = 0; guard < 400 && phase(ctx).equals("NIGHT"); guard++) {
			JsonObject p = prompt(ctx);
			if (p != null) {
				answer(ctx, p);
			} else {
				ctx.waitTicks(10);
			}
		}
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			ctx.waitTicks(60);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			world.getServer().runCommand("op " + me);

			// Salon
			send(ctx, "vc rejoindre");
			world.getServer().runCommand("vc bots 6");
			ctx.waitTicks(20);
			ctx.setScreen(MainScreen::new);
			shot(ctx, "1-salon");
			ctx.setScreen(SheetsScreen::new);
			shot(ctx, "2-fiches");
			ctx.setScreen(StorytellerScreen::new);
			shot(ctx, "3-panneau-salon");
			world.getServer().runCommand("vc script basculer margrave");
			ctx.setScreen(fr.valcombe.client.screen.ScriptScreen::new);
			shot(ctx, "3b-script");
			world.getServer().runCommand("vc script complet");
			ctx.setScreen(fr.valcombe.client.screen.SettingsScreen::new);
			shot(ctx, "3c-reglages");

			// Partie avec Conteur automatique
			ctx.setScreen(() -> null);
			world.getServer().runCommand("vc lancer auto");
			ctx.waitTicks(20);
			ctx.setScreen(MainScreen::new);
			shot(ctx, "4-partie");
			ctx.setScreen(() -> null);
			world.getServer().runCommand("vc nuit");
			ctx.waitTicks(40);
			playNight(ctx);
			if (phase(ctx).equals("DAY")) {
				ctx.setScreen(MainScreen::new);
				shot(ctx, "5-jour");
				ctx.setScreen(() -> null);
				String target = ctx.computeOnClient(mc -> {
					for (var e : ClientState.view().getAsJsonArray("seats")) {
						var s = e.getAsJsonObject();
						if (s.get("alive").getAsBoolean() && s.get("bot").getAsBoolean()) {
							return s.get("name").getAsString();
						}
					}
					return null;
				});
				send(ctx, "vc nominer " + target);
				ctx.waitTicks(30);
				shot(ctx, "5b-defense");
				world.getServer().runCommand("vc voter");
				ctx.waitTicks(100);
				JsonObject p = prompt(ctx);
				if (p != null) {
					answer(ctx, p);
					ctx.setScreen(fr.valcombe.client.screen.PromptScreen::new);
					ctx.waitTicks(20);
					shot(ctx, "invite-vote-main-levee");
				}
				ctx.waitTicks(500);
			}
			world.getServer().runCommand("vc arreter");
			ctx.waitTicks(20);

			// Le village de Valcombe
			ctx.setScreen(() -> null);
			send(ctx, "vc carte construire");
			ctx.waitTicks(120);
			shot(ctx, "8-village");
			ctx.runOnClient(mc -> mc.player.setXRot(35));
			ctx.waitTicks(20);
			shot(ctx, "9-village-place");

			// Partie avec Conteur humain : le joueur devient Conteur
			send(ctx, "vc quitter");
			send(ctx, "vc conteur");
			world.getServer().runCommand("vc bots 2");
			world.getServer().runCommand("vc lancer humain");
			ctx.waitTicks(20);
			ctx.setScreen(StorytellerScreen::new);
			shot(ctx, "6-panneau-grimoire");
			ctx.setScreen(() -> new fr.valcombe.client.screen.SeatScreen("Bot1"));
			shot(ctx, "10-siege");
			ctx.setScreen(() -> new fr.valcombe.client.screen.RolePickerScreen("Bot1", null));
			shot(ctx, "11-choix-role");
			send(ctx, "vc attribuer Bot1 somnambule astrologue");
			send(ctx, "vc note Bot1 Surveiller ses votes");
			ctx.setScreen(() -> new fr.valcombe.client.screen.SeatScreen("Bot1"));
			shot(ctx, "12-siege-retouche");
			ctx.setScreen(() -> null);
			world.getServer().runCommand("vc nuit");
			ctx.waitTicks(40);
			playNight(ctx);
			ctx.setScreen(StorytellerScreen::new);
			shot(ctx, "7-panneau-jour");
			world.getServer().runCommand("vc arreter");
		}
	}
}
