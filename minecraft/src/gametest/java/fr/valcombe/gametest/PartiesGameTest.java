package fr.valcombe.gametest;

import static fr.valcombe.gametest.AdminHttp.json;
import static fr.valcombe.gametest.TestKit.clickHd;
import static fr.valcombe.gametest.TestKit.hdButton;
import static fr.valcombe.gametest.TestKit.layoutProblem;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.screen.MainScreen;
import fr.valcombe.client.screen.PartiesScreen;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.VoiceRouter;
import fr.valcombe.mc.games.GameCard;
import fr.valcombe.mc.games.Games;
import fr.valcombe.mc.games.VoiceZones;
import fr.valcombe.mc.hub.Hub;
import fr.valcombe.mc.hub.HubHall;
import fr.valcombe.mc.worlds.WorldEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Le tableau des parties du hub, en jeu : les deux panneaux bâtis dans la salle, une fiche par partie mise à jour en
 * direct (captures de face, de près et de dos) ; un clic sur une fiche ouvre l'écran « Parties » sur elle ; l'écran HD
 * (mise en page vérifiée à 427 × 240, captures, vignettes de tous les états sur une vue fabriquée) ; « Rejoindre »
 * inscrit au salon d'une partie d'essai et y emmène ; « Regarder » emmène dans la partie en cours en mode spectateur,
 * dans les gradins du vocal, sans le grimoire, puis « Arrêter de regarder » ramène au hub avec son mode de jeu ; quitter
 * le monde par une commande fait de même ; l'API d'administration liste, crée et supprime les parties.
 */
public class PartiesGameTest implements FabricClientGameTest {
	/** L'API d'administration (réponse attendue au plus 600 ticks), ouverte à l'étape 8. */
	private AdminHttp api;

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
		System.out.println("[valcombe-test] tableau : " + what);
	}

	private static void send(ClientGameTestContext ctx, String cmd) {
		ctx.runOnClient(mc -> ClientState.send(cmd));
		ctx.waitTicks(10);
	}

	private static void shot(ClientGameTestContext ctx, String name) {
		ctx.waitTicks(10);
		System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-" + name));
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

	private static boolean waitClient(ClientGameTestContext ctx, int ticks, Predicate<Minecraft> cond) {
		for (int i = 0; i < ticks; i += 2) {
			if (ctx.computeOnClient(cond::test)) {
				return true;
			}
			ctx.waitTicks(2);
		}
		return ctx.computeOnClient(cond::test);
	}

	private static ServerPlayer player(MinecraftServer s, String name) {
		return s.getPlayerList().getPlayerByName(name);
	}

	private static String dimensionOf(MinecraftServer s, String name) {
		return player(s, name).level().dimension().identifier().toString();
	}

	private static GameType mode(MinecraftServer s, String name) {
		return player(s, name).gameMode.getGameModeForPlayer();
	}

	/** Place le joueur ici, dans ce monde, côté serveur, regard compris. */
	private static void place(TestSingleplayerContext world, String me, String id, double x, double y, double z, float yaw, float pitch) {
		world.getServer().runOnServer(s -> {
			ServerLevel level = ValcombeMod.worlds().level(id);
			player(s, me).teleportTo(level, x, y, z, java.util.Set.of(), yaw, pitch, false);
		});
	}

	/** La zone cliquable de la fiche de cette partie sur le tableau du hub, ou null. */
	private static Entity cardOf(MinecraftServer s, String hubId, String gameId) {
		ServerLevel hub = ValcombeMod.worlds().level(hubId);
		for (Entity e : hub.getEntities(net.minecraft.world.entity.EntityTypes.INTERACTION, e -> gameId.equals(Hub.boardCard(e)))) {
			return e;
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Écran
	// ------------------------------------------------------------------

	private static void scale(ClientGameTestContext ctx, int guiScale, int windowW, int windowH) {
		ctx.getInput().resizeWindow(windowW, windowH);
		ctx.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			mc.resizeGui();
		});
		ctx.waitTicks(10);
	}

	/** Une partie de la vue fabriquée, de la forme qu'envoie le serveur (Games.board). */
	private static JsonObject fake(String id, String nom, boolean essai, String statut, String statutTexte, String phase, String phaseTexte,
			int joueurs, int places, String conteur, int surPlace, int spectateurs, boolean rejoindre, boolean regarder, String place) {
		JsonObject g = new JsonObject();
		g.addProperty("id", id);
		g.addProperty("nom", nom);
		g.addProperty("sorte", essai ? "essai" : "partie");
		g.addProperty("principale", !essai);
		g.addProperty("statut", statut);
		g.addProperty("statutTexte", statutTexte);
		g.addProperty("phase", phase);
		g.addProperty("phaseTexte", phaseTexte);
		g.addProperty("joueurs", joueurs);
		g.addProperty("places", places);
		g.addProperty("joueursTexte", "salon".equals(phase) ? joueurs + " inscrits sur " + places + " places"
			: joueurs + " joueurs sur " + places + " places");
		if (conteur != null) {
			g.addProperty("conteur", conteur);
		}
		g.addProperty("surPlace", surPlace);
		g.addProperty("spectateurs", spectateurs);
		g.addProperty("ici", false);
		if (place != null) {
			g.addProperty("place", place);
		}
		g.addProperty("rejoindre", rejoindre);
		g.addProperty("rejoindreTexte", place != null ? "Y retourner" : "Rejoindre");
		if (!rejoindre) {
			g.addProperty("rejoindreRaison", "suspendue".equals(statut) ? "La partie est en suspens : elle reprendra où elle en est."
				: "La partie a commencé : tu peux la regarder.");
		}
		g.addProperty("regarder", regarder);
		if (!regarder) {
			g.addProperty("regarderRaison", "La partie est en suspens : elle reprendra où elle en est.");
		}
		g.addProperty("mener", true);
		return g;
	}

	// ------------------------------------------------------------------
	// API d'administration
	// ------------------------------------------------------------------

	private static JsonObject gameIn(JsonArray list, String id) {
		for (var e : list) {
			if (e.getAsJsonObject().get("id").getAsString().equals(id)) {
				return e.getAsJsonObject();
			}
		}
		return null;
	}

	/** On repart d'une partie principale libre, et de ce joueur dans aucune partie. */
	private static void cleanMain(TestSingleplayerContext world, String me) {
		world.getServer().runOnServer(s -> {
			var session = Games.main().session();
			if (session.game() != null) {
				session.stop();
			}
			if (me.equals(session.storyteller())) {
				session.toggleStoryteller(me);
			}
			if (session.lobby().contains(me)) {
				session.leave(me);
			}
			session.stopWatching(me, true);
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
			cleanMain(world, me);
			check(server.computeOnServer(s -> ValcombeMod.hub() != null && Games.main() != null), "hub et parties en service");
			String hubId = server.computeOnServer(s -> ValcombeMod.hub().id());
			String hubDim = "valcombe:" + hubId;
			String mainId = server.computeOnServer(s -> Games.main().id());
			String mainDim = "valcombe:" + mainId;
			long stamp = System.currentTimeMillis() % 100000;
			int initialScale = ctx.computeOnClient(mc -> mc.options.guiScale().get());

			// 1. Une partie d'essai, avec son village ; la partie principale se joue avec ses joueurs fictifs.
			String name = "Tableau " + stamp;
			send(ctx, "vc partie creer " + name);
			String essai = server.computeOnServer(s -> ValcombeMod.worlds().list().stream().filter(e -> e.nom().equals(name))
				.map(WorldEntry::id).findFirst().orElse(null));
			check(essai != null && server.computeOnServer(s -> Games.byWorld(essai) != null), "partie d'essai créée : " + essai);
			String essaiDim = "valcombe:" + essai;
			check(waitFor(ctx, world, 2400, s -> !ValcombeMod.sender().busy(essai) && Games.byWorld(essai).host().village() != null),
				"village de la partie d'essai posé");
			server.runCommand("execute in " + mainDim + " run vc bots 7");
			server.runCommand("execute in " + mainDim + " run vc lancer auto");
			check(waitFor(ctx, world, 200, s -> Games.main().session().game() != null), "partie principale lancée");

			// 2. Au hub : les deux panneaux du tableau sont bâtis, une fiche cliquable par partie, à jour.
			send(ctx, "vc hub");
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(hubDim)), "au hub");
			check(server.computeOnServer(s -> {
				ServerLevel hub = ValcombeMod.worlds().level(hubId);
				int z = HubHall.BOARD_Z;
				return hub.getBlockState(new BlockPos(HubHall.BOARD_INNER, HubHall.FEET + 2, z)).is(Blocks.STRIPPED_DARK_OAK_LOG)
					&& hub.getBlockState(new BlockPos(-HubHall.BOARD_OUTER, HubHall.FEET + 2, z)).is(Blocks.STRIPPED_DARK_OAK_LOG)
					&& hub.getBlockState(new BlockPos((int) Math.floor(HubHall.cardX(0)), HubHall.FEET + 2, z)).is(Blocks.SPRUCE_PLANKS)
					&& hub.getBlockState(new BlockPos((int) Math.floor(HubHall.cardX(5)), HubHall.FEET + 2, z)).is(Blocks.SPRUCE_PLANKS)
					&& hub.getBlockState(new BlockPos(0, HubHall.FEET, z)).isAir();
			}), "les deux panneaux du tableau, l'allée libre entre eux");
			check(waitFor(ctx, world, 200, s -> cardOf(s, hubId, mainId) != null && cardOf(s, hubId, essai) != null),
				"une fiche cliquable par partie");
			check(waitFor(ctx, world, 200, s -> {
				String shown = ValcombeMod.hub().boardShown();
				return shown != null && shown.contains("id=" + mainId) && shown.contains("status=EN_COURS")
					&& shown.contains("id=" + essai) && shown.contains("status=OUVERTE");
			}), "le tableau montre la principale en cours et l'essai ouvert : "
				+ server.computeOnServer(s -> ValcombeMod.hub().boardShown()));

			// La vignette de la partie d'essai, demandée du hub : faite sans charger un seul tronçon de son monde (ce qui y est
			// déjà chargé, ou la carte de la bibliothèque qu'elle porte).
			int chunksBefore = server.computeOnServer(s -> ValcombeMod.worlds().level(essai).getChunkSource().getLoadedChunksCount());
			String essaiStamp = server.computeOnServer(s -> fr.valcombe.mc.Vignettes.stampOf("partie:" + essai));
			check(!essaiStamp.isEmpty(), "la partie d'essai a une vignette");
			check(waitClient(ctx, 200, mc -> {
				fr.valcombe.client.Vignettes.get("partie:" + essai, essaiStamp);
				return fr.valcombe.client.Vignettes.ready("partie:" + essai, "");
			}), "vignette de la partie d'essai reçue");
			int chunksAfter = server.computeOnServer(s -> ValcombeMod.worlds().level(essai).getChunkSource().getLoadedChunksCount());
			check(chunksAfter <= chunksBefore, "la vignette n'a chargé aucun tronçon : " + chunksBefore + " puis " + chunksAfter);

			// Captures : de l'entrée, de près à l'ouest et à l'est, et de dos depuis les dalles.
			place(world, me, hubId, HubHall.ARRIVAL.x, HubHall.ARRIVAL.y, HubHall.ARRIVAL.z, HubHall.ARRIVAL_YAW, 8f);
			ctx.waitTicks(60);
			WorldView.clearChat(ctx);
			shot(ctx, "tableau-des-parties");
			place(world, me, hubId, -6.5, HubHall.FEET, HubHall.BOARD_FRONT + 3.2, 180f, 4f);
			ctx.waitTicks(30);
			WorldView.clearChat(ctx);
			shot(ctx, "tableau-des-parties-ouest");
			place(world, me, hubId, 7.5, HubHall.FEET, HubHall.BOARD_FRONT + 3.2, 180f, 4f);
			ctx.waitTicks(30);
			WorldView.clearChat(ctx);
			shot(ctx, "tableau-des-parties-est");
			place(world, me, hubId, 0.5, HubHall.FEET, -3.5, 0f, 6f);
			ctx.waitTicks(30);
			WorldView.clearChat(ctx);
			shot(ctx, "tableau-des-parties-dos");

			// 3. En direct : trois inscrits arrivent au salon de la partie d'essai, sa fiche les compte au passage suivant.
			server.runOnServer(s -> Games.byWorld(essai).session().addBots(3));
			check(waitFor(ctx, world, 120, s -> {
				String shown = ValcombeMod.hub().boardShown();
				return shown != null && shown.contains("lobby=3");
			}), "la fiche de l'essai suit ses inscrits");

			// 4. Un clic sur la fiche de l'essai ouvre l'écran « Parties » sur elle.
			place(world, me, hubId, HubHall.cardX(1) + 0.0, HubHall.FEET, HubHall.BOARD_FRONT + 1.5, 180f, 10f);
			ctx.waitTicks(10);
			boolean clicked = server.computeOnServer(s -> {
				Entity card = cardOf(s, hubId, essai);
				ServerPlayer p = player(s, me);
				var r = net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker()
					.interact(p, p.level(), net.minecraft.world.InteractionHand.MAIN_HAND, card, null);
				return r.consumesAction();
			});
			check(clicked, "le clic sur une fiche est pris par le tableau");
			check(waitClient(ctx, 60, mc -> mc.gui.screen() instanceof PartiesScreen && essai.equals(PartiesScreen.chosenId())),
				"l'écran « Parties » s'ouvre sur la partie cliquée");

			// 5. L'écran, à 427 × 240 : mise en page, boutons, captures sur la partie d'essai et sur la principale en cours.
			scale(ctx, 2, 854, 480);
			ctx.setScreen(() -> new PartiesScreen(essai));
			check(waitClient(ctx, 60, mc -> PartiesScreen.games().size() >= 2 && hdButton(mc, "Rejoindre") != null), "écran rempli");
			String problem = ctx.computeOnClient(mc -> layoutProblem(mc, false));
			check(problem == null, "mise en page de l'écran Parties : " + problem);
			check(ctx.computeOnClient(mc -> hdButton(mc, "Regarder") != null && hdButton(mc, "Créer une partie d'essai") != null
				&& hdButton(mc, "Retour") != null && hdButton(mc, "Supprimer") != null), "boutons de l'écran, opérateur compris");
			shot(ctx, "parties-ecran-essai");
			ctx.setScreen(() -> new PartiesScreen(mainId));
			check(waitClient(ctx, 40, mc -> hdButton(mc, "Rejoindre") != null), "fiche de la principale");
			check(ctx.computeOnClient(mc -> !hdButton(mc, "Rejoindre").active && hdButton(mc, "Regarder").active
				&& hdButton(mc, "Suspendre") != null && hdButton(mc, "Supprimer") == null),
				"partie en cours : on la regarde, on ne la rejoint pas ; elle se met en suspens, elle ne se supprime pas");
			problem = ctx.computeOnClient(mc -> layoutProblem(mc, false));
			check(problem == null, "mise en page, fiche de la principale : " + problem);
			shot(ctx, "parties-ecran-en-cours");
			// Supprimer demande une confirmation.
			ctx.setScreen(() -> new PartiesScreen(essai));
			ctx.waitTicks(10);
			check(clickHd(ctx, "Supprimer"), "« Supprimer »");
			check(waitClient(ctx, 20, mc -> hdButton(mc, "Confirmer") != null && hdButton(mc, "Annuler") != null), "confirmation demandée");
			shot(ctx, "parties-ecran-confirmer");
			check(clickHd(ctx, "Annuler"), "« Annuler »");
			check(server.computeOnServer(s -> Games.byWorld(essai) != null), "rien de supprimé sans confirmation");

			// 6. Rejoindre : inscrit au salon de la partie d'essai, et emmené dans son monde.
			ctx.setScreen(() -> new PartiesScreen(essai));
			ctx.waitTicks(10);
			check(clickHd(ctx, "Rejoindre"), "« Rejoindre »");
			check(waitFor(ctx, world, 200, s -> Games.byWorld(essai).session().lobby().contains(me) && dimensionOf(s, me).equals(essaiDim)),
				"inscrit à la partie d'essai, dans son monde");
			check(server.computeOnServer(s -> !Games.main().session().belongs(me)), "et à aucune autre table");
			send(ctx, "vc quitter");
			check(waitFor(ctx, world, 60, s -> !Games.byWorld(essai).session().lobby().contains(me)), "désinscrit");
			send(ctx, "vc hub");
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(hubDim)), "retour au hub");

			// 7. Regarder la partie en cours : mode spectateur, gradins au vocal, pas de grimoire ; puis en sortir.
			GameType before = server.computeOnServer(s -> mode(s, me));
			ctx.setScreen(() -> new PartiesScreen(mainId));
			ctx.waitTicks(10);
			check(clickHd(ctx, "Regarder"), "« Regarder »");
			check(waitFor(ctx, world, 200, s -> Games.main().session().isWatcher(me) && dimensionOf(s, me).equals(mainDim)),
				"dans le monde de la partie principale, dans ses gradins");
			check(server.computeOnServer(s -> mode(s, me) == GameType.SPECTATOR), "en mode spectateur");
			check(server.computeOnServer(s -> Games.main().card().watchers() == 1 && "regarde".equals(GameCard.seat(Games.main().session(), me))),
				"la fiche compte un spectateur");
			check(waitFor(ctx, world, 40, s -> VoiceRouter.zones().zone(player(s, me).getUUID()).equals(VoiceZones.gallery(mainId))),
				"au vocal, dans les gradins : il n'entend pas les joueurs, ils ne l'entendent pas");
			server.runOnServer(s -> Games.main().session().setting("spectateurs_grimoire", "oui"));
			check(waitClient(ctx, 100, mc -> ClientState.bool(ClientState.obj(ClientState.view(), "partie"), "regarde")
				&& ClientState.bool(ClientState.view(), "watching")), "sa vue le sait");
			ctx.waitTicks(20);
			check(ctx.computeOnClient(mc -> !ClientState.view().has("grimoire") || ClientState.view().get("grimoire").isJsonNull()),
				"pas de grimoire, même quand le Conteur l'ouvre à ses spectateurs");
			check(ctx.computeOnClient(mc -> !ClientState.view().has("plan") && !ClientState.bool(ClientState.view(), "leader")),
				"pas de vue de meneur non plus (l'ordre de réveil dirait les rôles), même opérateur");
			check(ctx.computeOnClient(mc -> !ClientState.view().has("parties")), "dans une partie, pas de tableau des autres");
			ctx.setScreen(() -> null);
			BlockPos center = server.computeOnServer(s -> Games.main().host().village().center());
			place(world, me, mainId, center.getX() + 0.5, center.getY() + 18, center.getZ() + 16.5, 180f, 40f);
			shot(ctx, "parties-regarder");
			ctx.setScreen(MainScreen::new);
			check(waitClient(ctx, 40, mc -> hdButton(mc, "Arrêter de regarder") != null), "menu J : « Arrêter de regarder »");
			check(clickHd(ctx, "Arrêter de regarder"), "« Arrêter de regarder »");
			check(waitFor(ctx, world, 200, s -> dimensionOf(s, me).equals(hubDim) && !Games.main().session().isWatcher(me)
				&& !Games.main().session().isSpectator(me)), "retour au hub, sorti des gradins");
			check(server.computeOnServer(s -> mode(s, me) == before), "son mode de jeu lui est rendu : " + before);
			// Sortir par une commande de voyage fait de même.
			send(ctx, "vc partie regarder " + mainId);
			check(waitFor(ctx, world, 200, s -> Games.main().session().isWatcher(me) && mode(s, me) == GameType.SPECTATOR), "de nouveau dans les gradins");
			send(ctx, "vc hub");
			check(waitFor(ctx, world, 200, s -> dimensionOf(s, me).equals(hubDim) && !Games.main().session().isWatcher(me)
				&& mode(s, me) == before), "parti par /vc hub : plus spectateur, mode rendu");
			check(waitClient(ctx, 100, mc -> ClientState.obj(ClientState.view(), "parties") != null), "au hub, le tableau revient dans sa vue");

			// 8. L'API d'administration : la liste, l'état des mondes, la création et la suppression.
			api = AdminHttp.open(600);
			var list = api.get(ctx, "/admin/parties");
			check(list.statusCode() == 200, "GET /admin/parties : " + list.statusCode() + " " + list.body());
			JsonObject board = json(list);
			JsonObject mainCard = gameIn(board.getAsJsonArray("parties"), mainId);
			JsonObject essaiCard = gameIn(board.getAsJsonArray("parties"), essai);
			check(board.get("disponible").getAsBoolean() && mainId.equals(board.get("principale").getAsString())
				&& board.get("enCours").getAsInt() >= 1, "le tableau de l'API : " + list.body());
			check(mainCard != null && "en-cours".equals(mainCard.get("statut").getAsString()) && mainCard.get("places").getAsInt() > 0
				&& essaiCard != null && "ouverte".equals(essaiCard.get("statut").getAsString()) && essaiCard.get("participants").getAsInt() == 3,
				"chaque partie avec son statut, ses places et ses participants");
			JsonObject etat = json(api.get(ctx, "/admin/etat"));
			boolean worldState = false;
			for (var w : etat.getAsJsonArray("mondes")) {
				JsonObject o = w.getAsJsonObject();
				if (o.get("id").getAsString().equals(mainId) && o.has("partie")) {
					worldState = "en-cours".equals(o.getAsJsonObject("partie").get("statut").getAsString());
				}
			}
			check(worldState, "l'état donne la partie de chaque monde de partie");
			check(api.post(ctx, "/admin/parties", "{}").statusCode() == 400, "créer sans nom : 400");
			var mainDelete = api.post(ctx, "/admin/parties/supprimer", "{\"partie\": \"" + mainId + "\"}");
			check(mainDelete.statusCode() == 409, "la partie principale ne se supprime pas : " + mainDelete.statusCode());
			check(api.post(ctx, "/admin/parties/supprimer", "{\"partie\": \"aucune-" + stamp + "\"}").statusCode() == 404, "partie inconnue : 404");
			String apiName = "Essai API " + stamp;
			var created = api.post(ctx, "/admin/parties", "{\"nom\": \"" + apiName + "\"}");
			check(created.statusCode() == 200, "POST /admin/parties : " + created.statusCode() + " " + created.body());
			String apiId = json(created).getAsJsonObject("partie").get("id").getAsString();
			check(server.computeOnServer(s -> Games.byWorld(apiId) != null), "partie d'essai créée par l'API : " + apiId);
			check(waitFor(ctx, world, 2400, s -> !ValcombeMod.sender().busy(apiId)), "son village posé");
			check(waitFor(ctx, world, 200, s -> cardOf(s, hubId, apiId) != null), "sa fiche paraît au tableau du hub");
			var deleted = api.post(ctx, "/admin/parties/supprimer", "{\"partie\": \"" + apiId + "\"}");
			check(deleted.statusCode() == 200 && json(deleted).get("ok").getAsBoolean(), "POST /admin/parties/supprimer : " + deleted.body());
			check(waitFor(ctx, world, 600, s -> Games.byWorld(apiId) == null && ValcombeMod.worlds().find(apiId) == null
				&& !ValcombeMod.worlds().busy(apiId)), "partie et monde supprimés");
			check(waitFor(ctx, world, 200, s -> cardOf(s, hubId, apiId) == null), "sa fiche quitte le tableau");

			// 9. Les vignettes de tous les états, sur une vue fabriquée (sept parties : le tableau défile).
			JsonArray fakeList = new JsonArray();
			fakeList.add(fake("partie-principale", "Partie principale", false, "en-cours", "En cours", "nuit", "Nuit 2", 12, 12, "Yoann", 13, 2, false, true, null));
			fakeList.add(fake("essai-du-mardi", "Essai du mardi", true, "ouverte", "Ouverte", "salon", "Salon ouvert", 5, 16, null, 5, 0, true, true, "inscrit"));
			fakeList.add(fake("essai-moulin", "Le Moulin", true, "en-cours", "En cours", "jour", "Jour 3", 9, 10, "Lea", 9, 1, false, true, null));
			fakeList.add(fake("essai-bal", "Le Bal masqué", true, "en-pause", "En pause", "jour", "Jour 1", 7, 8, "Hugo", 8, 0, false, true, null));
			fakeList.add(fake("essai-gel", "Partie du gel", true, "suspendue", "En suspens", "nuit", "Nuit 4", 11, 12, "Ines", 0, 0, false, false, null));
			fakeList.add(fake("essai-aube", "Répétition de l'aube", true, "en-cours", "En cours", "mise-en-place", "Mise en place", 6, 16, null, 6, 0, false, true, null));
			fakeList.add(fake("essai-vide", "Essai sans nom", true, "ouverte", "Ouverte", "salon", "Salon ouvert", 0, 16, null, 0, 0, true, true, null));
			JsonObject fakeBoard = new JsonObject();
			fakeBoard.add("liste", fakeList);
			fakeBoard.addProperty("operateur", true);
			for (String pick : List.of("partie-principale", "essai-gel", "essai-moulin")) {
				String json = ctx.computeOnClient(mc -> {
					JsonObject v = ClientState.view().deepCopy();
					v.add("parties", fakeBoard);
					return v.toString();
				});
				ctx.runOnClient(mc -> {
					ClientState.apply(json);
					mc.gui.setScreen(new PartiesScreen(pick));
				});
				ctx.waitTicks(3);
				problem = ctx.computeOnClient(mc -> layoutProblem(mc, false));
				check(problem == null, "mise en page, vue fabriquée (" + pick + ") : " + problem);
				System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-parties-vignettes-" + pick));
			}
			// Et à l'échelle 3, dans une fenêtre plus grande : les vignettes restent nettes.
			scale(ctx, 3, 1600, 900);
			ctx.setScreen(() -> new PartiesScreen(mainId));
			ctx.waitTicks(10);
			problem = ctx.computeOnClient(mc -> layoutProblem(mc, false));
			check(problem == null, "mise en page à l'échelle 3 : " + problem);
			shot(ctx, "parties-ecran-echelle-3");
			ctx.setScreen(() -> null);
			ctx.getInput().resizeWindow(854, 480);
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(initialScale);
				mc.resizeGui();
			});

			// 9 bis. Huit parties : le tableau du hub en montre six par page ; les flèches aux bouts tournent les pages.
			send(ctx, "vc hub");
			check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(hubDim)), "au hub pour le tableau à pages");
			List<String> extra = new ArrayList<>();
			int target = 8;
			int have = server.computeOnServer(s -> Games.all().size());
			for (int i = have; i < target; i++) {
				int n = i;
				extra.add(server.computeOnServer(s -> ValcombeMod.worlds().create(fr.valcombe.mc.worlds.WorldKind.ESSAI,
					"Page " + stamp + " " + n).id()));
			}
			int total = server.computeOnServer(s -> Games.all().size());
			check(total >= 8, "au moins huit parties : " + total);
			check(waitFor(ctx, world, 200, s -> {
				String shown = ValcombeMod.hub().boardShown();
				return shown != null && shown.startsWith("page 1/" + Hub.pages(total) + " ");
			}), "le tableau est en pages : " + server.computeOnServer(s -> ValcombeMod.hub().boardShown()));
			List<String> firstPage = server.computeOnServer(s -> Games.all().stream().limit(HubHall.SLOTS).map(r -> r.id()).toList());
			List<String> secondPage = server.computeOnServer(s -> Games.all().stream().skip(HubHall.SLOTS).limit(HubHall.SLOTS)
				.map(r -> r.id()).toList());
			check(server.computeOnServer(s -> firstPage.stream().allMatch(id -> cardOf(s, hubId, id) != null)
				&& secondPage.stream().noneMatch(id -> cardOf(s, hubId, id) != null)), "page 1 : les six premières fiches seulement");
			place(world, me, hubId, 0.5, HubHall.FEET, HubHall.BOARD_FRONT + 9.5, 180f, 8f);
			ctx.waitTicks(40);
			WorldView.clearChat(ctx);
			shot(ctx, "tableau-des-parties-page-1");
			// Un clic sur la flèche de droite (vue de l'entrée) : la page suivante, aussitôt.
			boolean turned = server.computeOnServer(s -> {
				ServerLevel hub = ValcombeMod.worlds().level(hubId);
				ServerPlayer p = player(s, me);
				for (Entity e : hub.getEntities(net.minecraft.world.entity.EntityTypes.INTERACTION,
					e -> Hub.boardArrow(e) == 1 && e.getZ() > HubHall.BOARD_FRONT)) {
					var r = net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker()
						.interact(p, p.level(), net.minecraft.world.InteractionHand.MAIN_HAND, e, null);
					return r.consumesAction() && ValcombeMod.hub().page() == 1;
				}
				return false;
			});
			check(turned, "la flèche de droite tourne la page");
			check(waitFor(ctx, world, 60, s -> secondPage.stream().allMatch(id -> cardOf(s, hubId, id) != null)
				&& firstPage.stream().noneMatch(id -> cardOf(s, hubId, id) != null)), "page 2 : les fiches suivantes");
			check(server.computeOnServer(s -> ValcombeMod.hub().boardShown().startsWith("page 2/")), "le tableau dit sa page");
			ctx.waitTicks(30);
			WorldView.clearChat(ctx);
			shot(ctx, "tableau-des-parties-page-2");
			// La flèche de gauche, frappée (clic gauche) : retour à la première page.
			boolean back = server.computeOnServer(s -> {
				ServerLevel hub = ValcombeMod.worlds().level(hubId);
				ServerPlayer p = player(s, me);
				for (Entity e : hub.getEntities(net.minecraft.world.entity.EntityTypes.INTERACTION,
					e -> Hub.boardArrow(e) == -1 && e.getZ() > HubHall.BOARD_FRONT)) {
					var r = net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
						.interact(p, p.level(), net.minecraft.world.InteractionHand.MAIN_HAND, e, null);
					return r.consumesAction() && ValcombeMod.hub().page() == 0;
				}
				return false;
			});
			check(back, "la flèche de gauche revient à la page d'avant");
			server.runOnServer(s -> extra.forEach(id -> ValcombeMod.worlds().delete(id)));
			check(waitFor(ctx, world, 600, s -> extra.stream().allMatch(id -> ValcombeMod.worlds().find(id) == null)),
				"parties du tableau à pages supprimées");
			check(waitFor(ctx, world, 200, s -> {
				String shown = ValcombeMod.hub().boardShown();
				return shown != null && !shown.startsWith("page ");
			}), "une seule page de nouveau, sans flèches");

			// 10. Ménage : la principale arrêtée, la partie d'essai supprimée.
			server.runCommand("execute in " + mainDim + " run vc arreter");
			check(waitFor(ctx, world, 1200, s -> Games.main().session().game() == null && !Games.main().host().busy()),
				"partie principale arrêtée");
			send(ctx, "vc partie supprimer " + essai);
			check(waitFor(ctx, world, 600, s -> Games.byWorld(essai) == null && ValcombeMod.worlds().find(essai) == null
				&& !ValcombeMod.worlds().busy(essai)), "partie d'essai supprimée");
			cleanMain(world, me);
		}
	}
}
