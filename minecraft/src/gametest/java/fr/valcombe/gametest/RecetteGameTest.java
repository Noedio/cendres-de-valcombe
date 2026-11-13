package fr.valcombe.gametest;

import static fr.valcombe.gametest.AdminHttp.json;
import static fr.valcombe.gametest.TestKit.buttons;
import static fr.valcombe.gametest.TestKit.chatCount;
import static fr.valcombe.gametest.TestKit.cleanMain;
import static fr.valcombe.gametest.TestKit.dimensionOf;
import static fr.valcombe.gametest.TestKit.grid;
import static fr.valcombe.gametest.TestKit.greyed;
import static fr.valcombe.gametest.TestKit.hdButton;
import static fr.valcombe.gametest.TestKit.hdButtonStarting;
import static fr.valcombe.gametest.TestKit.openInGrid;
import static fr.valcombe.gametest.TestKit.place;
import static fr.valcombe.gametest.TestKit.player;
import static fr.valcombe.gametest.TestKit.pressHd;
import static fr.valcombe.gametest.TestKit.primary;
import static fr.valcombe.gametest.TestKit.runIn;
import static fr.valcombe.gametest.TestKit.send;
import static fr.valcombe.gametest.TestKit.titleText;
import static fr.valcombe.gametest.TestKit.type;
import static fr.valcombe.gametest.TestKit.waitClient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fr.valcombe.backup.BackupContents;
import fr.valcombe.backup.BackupStore;
import fr.valcombe.backup.RestoreMarker;
import fr.valcombe.backup.Restorer;
import fr.valcombe.client.BuildSelection;
import fr.valcombe.client.ClientState;
import fr.valcombe.client.Vignettes;
import fr.valcombe.client.screen.ChronicleScreen;
import fr.valcombe.client.screen.MainScreen;
import fr.valcombe.client.screen.MapWorkshopScreen;
import fr.valcombe.client.screen.NightScreen;
import fr.valcombe.client.screen.PartiesScreen;
import fr.valcombe.client.screen.PromptScreen;
import fr.valcombe.client.screen.StorytellerScreen;
import fr.valcombe.client.screen.StructuresWorkshopScreen;
import fr.valcombe.client.screen.WorldsScreen;
import fr.valcombe.client.screen.atelier.AtelierHome;
import fr.valcombe.client.screen.atelier.MapSheetPage;
import fr.valcombe.client.screen.atelier.MapsPage;
import fr.valcombe.client.screen.atelier.NewMapPage;
import fr.valcombe.client.screen.atelier.NewStructurePage;
import fr.valcombe.client.screen.atelier.PartiesPage;
import fr.valcombe.client.screen.atelier.RolesPage;
import fr.valcombe.client.screen.atelier.ScenarioSheetPage;
import fr.valcombe.client.screen.atelier.StructureSheetPage;
import fr.valcombe.client.screen.atelier.StructuresPage;
import fr.valcombe.client.ui.WaxButton;
import fr.valcombe.mc.MapVersions;
import fr.valcombe.mc.MapWorkshop;
import fr.valcombe.mc.StructureStore;
import fr.valcombe.mc.StructureWorkshop;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.ValcombeUi;
import fr.valcombe.mc.Village;
import fr.valcombe.mc.games.GameRoom;
import fr.valcombe.mc.games.Games;
import fr.valcombe.mc.hub.HubHall;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.Game;
import fr.valcombe.rules.RoleType;
import fr.valcombe.rules.Team;
import fr.valcombe.session.Session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Le test de recette : le parcours complet d'un opérateur et de ses joueurs, tel que l'utilisateur le fera, une capture
 * nette de chaque écran et de chaque étape visible ({@code recette-NN-<étape>.png}, dans l'ordre), et une vérification
 * par une assertion à chaque étape.
 * <ol>
 * <li>Arrivée au hub, écran « Mondes », tableau des parties et écran « Parties ».</li>
 * <li>Atelier en jeu : l'accueil, Cartes (bibliothèque, « Nouvelle carte » en trois étapes : un village généré avec son
 * aperçu), la carte bâtie dans son atelier ; WorldEdit (baguette, sélection en 3D, //set), les outils de repères (un trône
 * ajouté), « Enregistrer », les versions (retour à une version) ; la fiche de la carte dans la bibliothèque.</li>
 * <li>« Envoyer à la partie » : le monde de la partie principale remis à neuf, plat, la carte posée ; le joueur attend au
 * hub, puis le lien le ramène au village.</li>
 * <li>Structures : le catalogue, une fiche, l'atelier structures (la parcelle du puits, retouchée avec WorldEdit,
 * enregistrée, puis revenue à l'originale), une nouvelle structure depuis une sélection.</li>
 * <li>Rôles et scénarios : les listes, un scénario choisi pour la partie d'essai.</li>
 * <li>Deux parties en même temps : la principale (six joueurs fictifs, Conteur automatique) et la partie d'essai (le joueur
 * est son Conteur, cinq joueurs fictifs) ; une nuit et un jour dans chacune, sans qu'aucune annonce, aucun titre ni aucune
 * vue ne passe de l'une à l'autre.</li>
 * <li>La partie d'essai mise en suspens puis reprise, par l'écran « Parties ».</li>
 * <li>Une sauvegarde par l'API d'administration, des changements après elle, puis la restauration : refusée par l'API en
 * partie solo (comme prévu), elle est appliquée comme au démarrage d'un serveur dédié (la demande posée, le monde fermé,
 * {@link Restorer} au démarrage, le monde rouvert) ; l'état revenu est vérifié, parties comprises.</li>
 * <li>La fin des deux parties : le démon de la partie d'essai meurt (victoire du bien, révélation, chronique), la
 * principale est déclarée gagnée par le mal ; aucune fin ne s'annonce dans l'autre partie.</li>
 * </ol>
 * Le test ne dépend pas des autres : il repart d'une partie principale libre, crée ce dont il a besoin (carte, structure,
 * scénario, partie d'essai) et le retire à la fin, même après un échec ; la partie principale retrouve un village généré.
 */
public class RecetteGameTest implements FabricClientGameTest {
	private static final String SCOPE = "recette";
	private static final int S = WorldManager.SURFACE_Y;

	/** Ce que le test crée ou relève, d'une vie du serveur à l'autre, et ce qu'il retire à la fin. */
	private static final class State {
		final long stamp = System.currentTimeMillis() % 100000;
		String me;
		String hubId;
		String mainId;
		String essai;
		String mapId;
		String mapName;
		String atelier;
		int seats;
		String structure;
		final String scenario = "recette-" + System.currentTimeMillis() % 100000;
		/** Un rôle créé pour la recette : sans lui, le volet « Rôles créés » serait vide sur un dossier de jeu propre. */
		final String role = "recette-role-" + stamp;
		String roleAfter;
		BlockPos hubMark;
		String backup;
		String safety;
		Path serverDir;
		String worldRel;
		boolean puitsTouched;
		final List<Path> files = new ArrayList<>();
	}

	private int shots;

	// ------------------------------------------------------------------
	// Aides propres au parcours
	// ------------------------------------------------------------------

	private static void check(boolean ok, String what) {
		TestKit.check(SCOPE, ok, what);
	}

	/** Une capture numérotée dans l'ordre du parcours : recette-NN-étape. */
	private void shot(ClientGameTestContext ctx, String step) {
		ctx.waitTicks(10);
		String name = String.format(Locale.ROOT, "recette-%02d-%s", ++shots, step);
		System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot(name));
	}

	private static boolean waitFor(ClientGameTestContext ctx, TestSingleplayerContext world, int ticks, Predicate<MinecraftServer> cond) {
		return TestKit.waitFor(ctx, world, ticks, 5, cond);
	}

	/** Clique le bouton haute résolution qui porte ce libellé (il doit être là et actif). */
	private static void press(ClientGameTestContext ctx, String label) {
		boolean done = pressHd(ctx, label);
		check(done, "bouton « " + label + " » parmi : " + buttons(ctx));
		ctx.waitTicks(12);
	}

	/** Clique le bouton dont le libellé commence ainsi (« Versions (3)… »). */
	private static void pressStarting(ClientGameTestContext ctx, String prefix) {
		boolean done = ctx.computeOnClient(mc -> {
			var b = hdButtonStarting(mc, prefix);
			if (b == null || !b.active) {
				return false;
			}
			b.press();
			return true;
		});
		check(done, "bouton « " + prefix + "… » parmi : " + buttons(ctx));
		ctx.waitTicks(12);
	}

	/** Clique un bouton d'un écran du mod (haute résolution ou non) ; faux s'il n'y est pas ou s'il est grisé. */
	private static boolean click(ClientGameTestContext ctx, String label) {
		boolean done = ctx.computeOnClient(mc -> {
			if (mc.gui.screen() == null) {
				return false;
			}
			for (var w : mc.gui.screen().children()) {
				if (w instanceof WaxButton b && b.visible && b.active && b.getMessage().getString().equals(label)) {
					b.press();
					return true;
				}
			}
			return false;
		});
		ctx.waitTicks(15);
		return done;
	}

	private static void chooseTab(ClientGameTestContext ctx, String label) {
		check(TestKit.chooseTab(ctx, label), "onglet « " + label + " »");
		ctx.waitTicks(12);
	}

	private static MapWorkshop mw() {
		return ValcombeMod.mapWorkshop();
	}

	private static StructureWorkshop sw() {
		return ValcombeMod.structureWorkshop();
	}

	private static StructureStore structures() {
		return ValcombeMod.structures();
	}

	private static ServerLevel level(String worldId) {
		return ValcombeMod.worlds().level(worldId);
	}

	private static boolean in(MinecraftServer s, String me, String worldId) {
		return worldId != null && dimensionOf(s, me).equals("valcombe:" + worldId);
	}

	/** Combien de blocs de cette sorte dans le pavé (coins compris), dans ce monde. */
	private static int count(ServerLevel level, BlockPos a, BlockPos b, Block block) {
		int n = 0;
		for (BlockPos p : BlockPos.betweenClosed(a, b)) {
			if (level.getBlockState(p).is(block)) {
				n++;
			}
		}
		return n;
	}

	private static boolean hasItem(MinecraftServer s, String me, Predicate<ItemStack> wanted) {
		var inv = player(s, me).getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (wanted.test(inv.getItem(i))) {
				return true;
			}
		}
		return false;
	}

	/** Met en main (case choisie de la barre) le premier objet de l'inventaire qui convient ; faux s'il n'y en a pas. */
	private static boolean hold(ClientGameTestContext ctx, TestSingleplayerContext world, String me, Predicate<ItemStack> wanted) {
		boolean done = world.getServer().computeOnServer(s -> {
			var inv = player(s, me).getInventory();
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

	/**
	 * Un carré de sol libre ({@code size} × {@code size}) autour de ce centre : un bloc plein au niveau du sol, cinq blocs
	 * d'air au-dessus, à trois blocs au moins de ces repères. Renvoie son coin au niveau du sol, ou null.
	 */
	private static BlockPos freeGround(ServerLevel level, BlockPos center, int size, int minR, int maxR, List<BlockPos> avoid) {
		for (int r = minR; r <= maxR; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					BlockPos g = new BlockPos(center.getX() + dx, S, center.getZ() + dz);
					if (fits(level, g, size, avoid)) {
						return g;
					}
				}
			}
		}
		return null;
	}

	private static boolean fits(ServerLevel level, BlockPos g, int size, List<BlockPos> avoid) {
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				BlockPos c = g.offset(x, 0, z);
				if (!level.getBlockState(c).isSolidRender()) {
					return false;
				}
				for (int y = 1; y <= 5; y++) {
					if (!level.getBlockState(c.above(y)).isAir()) {
						return false;
					}
				}
				for (BlockPos a : avoid) {
					if (Math.abs(a.getX() - c.getX()) < 3 && Math.abs(a.getZ() - c.getZ()) < 3) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/** Les trônes et le centre de la carte de cet atelier (on ne bâtit pas dessus). */
	private static List<BlockPos> markers(String atelier) {
		Village v = mw().marks(atelier);
		List<BlockPos> out = new ArrayList<>();
		for (int i = 0; i < v.seatCount(); i++) {
			out.add(v.markerPos(new Village.Hit(Village.Mark.SEAT, i)));
		}
		out.add(v.center());
		return out;
	}

	/** Place le joueur ici, dans ce monde, le regard tourné vers ce point (pour une capture). */
	private static void look(TestSingleplayerContext world, String worldId, String me, double x, double y, double z, double fx, double fy,
			double fz) {
		String cmd = String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f facing %.2f %.2f %.2f", me, x, y, z, fx + 0.5, fy + 0.5, fz + 0.5);
		world.getServer().runOnServer(s -> runIn(s, worldId, cmd));
	}

	private static String pos(BlockPos p) {
		return p.getX() + "," + p.getY() + "," + p.getZ();
	}

	/** Le tick serveur de la partie de ce monde : phase, nuit, jour. */
	private static String phaseOf(GameRoom r) {
		Game g = r == null ? null : r.session().game();
		return g == null ? "-" : g.phase() + " n" + g.night() + " j" + g.day() + (g.dayOver() ? " fin" : "");
	}

	/** Ce qu'on regarde d'une partie pour savoir si elle a bougé. */
	private static String snapshot(GameRoom r) {
		Session s = r.session();
		var g = s.game();
		return s.lobby() + "|" + s.storyteller() + "|" + s.paused() + "|" + s.suspended() + "|" + s.nominationsOpen() + "|"
			+ (g == null ? "-" : g.phase() + " n" + g.night() + " j" + g.day() + " " + g.dayOver() + " "
				+ g.players().stream().map(p -> p.name + (p.alive() ? "" : "+")).toList());
	}

	/** Un joueur fictif de cette partie a-t-il annoncé un rôle (les joueurs fictifs le font au lever du jour) ? */
	private static boolean someClaim(GameRoom r) {
		Game g = r.session().game();
		return g != null && g.players().stream().anyMatch(p -> r.session().isBot(p.name) && r.session().claimOf(p.name) != null);
	}

	/** Répond à l'invite en cours du joueur, comme par son écran d'invite. */
	private static void answer(ClientGameTestContext ctx, JsonObject p) {
		String type = ClientState.str(p, "type");
		switch (type) {
			case "choose" -> {
				int n = ClientState.num(p, "count", 1);
				var opts = p.getAsJsonArray("options");
				for (int i = 0; i < n && i < opts.size(); i++) {
					send(ctx, "vc choisir " + opts.get(opts.size() - 1 - i).getAsJsonObject().get("name").getAsString());
				}
				send(ctx, "vc valider");
			}
			case "info", "grimoire" -> send(ctx, "vc dormir");
			case "vote" -> send(ctx, "vc vote oui");
			case "confirm" -> send(ctx, "vc envoyer");
			// Décision en direct du Conteur (« ... trompe-t-il ... ? », Goule) : la première réponse proposée.
			case "decide" -> send(ctx, "vc decider 0");
			default -> {
				System.out.println("[valcombe-test] recette : invite « " + type + " » sans réponse, on attend");
				ctx.waitTicks(5);
			}
		}
	}

	// ------------------------------------------------------------------
	// Le test
	// ------------------------------------------------------------------

	@Override
	public void runTest(ClientGameTestContext ctx) {
		State st = new State();
		TestWorldSave save;
		int initialScale = ctx.computeOnClient(mc -> mc.options.guiScale().get());

		// Première vie du serveur : tout le parcours jusqu'à la sauvegarde et aux changements qui la suivent.
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			save = world.getWorldSave();
			boolean done = false;
			try {
				firstLife(ctx, world, st);
				done = true;
			} finally {
				if (!done) {
					cleanup(ctx, world, st, initialScale);
				}
			}
		}

		// Le monde est fermé : la restauration est appliquée comme au démarrage d'un serveur dédié.
		AssertionError restoreFailure = null;
		try {
			restoreOffline(st);
		} catch (AssertionError e) {
			restoreFailure = e;
		}

		// Seconde vie : l'état revenu, la fin des deux parties, le ménage.
		try (TestSingleplayerContext world = save.open()) {
			try {
				if (restoreFailure != null) {
					throw restoreFailure;
				}
				secondLife(ctx, world, st);
			} finally {
				cleanup(ctx, world, st, initialScale);
			}
		}
	}

	private void firstLife(ClientGameTestContext ctx, TestSingleplayerContext world, State st) {
		ctx.waitTicks(60);
		st.me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
		String me = st.me;
		var server = world.getServer();
		server.runCommand("op " + me);
		server.runCommand("gamemode creative " + me);
		ctx.waitTicks(40);
		check(server.computeOnServer(s -> ValcombeMod.hub() != null && Games.main() != null && mw() != null && sw() != null
			&& ValcombeMod.backups() != null), "hub, parties, ateliers et sauvegardes en service");
		cleanMain(world, me, true);
		server.runOnServer(s -> {
			// Un essai précédent interrompu a pu laisser le puits modifié : on repart de l'original.
			if (structures().isOverridden("puits")) {
				structures().revert("puits");
			}
		});
		st.hubId = server.computeOnServer(s -> ValcombeMod.hub().id());
		st.mainId = server.computeOnServer(s -> Games.main().id());
		check(waitFor(ctx, world, 2400, s -> !Games.main().host().busy()), "la partie principale est libre au départ");

		arrival(ctx, world, st);
		atelierMap(ctx, world, st);
		sendToGame(ctx, world, st);
		structuresPart(ctx, world, st);
		rolesAndScenario(ctx, world, st);
		twoGames(ctx, world, st);
		suspendAndResume(ctx, world, st);
		backup(ctx, world, st);
	}

	// ------------------------------------------------------------------
	// 1. Arrivée au hub, Mondes, tableau des parties
	// ------------------------------------------------------------------

	private void arrival(ClientGameTestContext ctx, TestSingleplayerContext world, State st) {
		var server = world.getServer();
		String me = st.me;
		String hubDim = "valcombe:" + st.hubId;
		check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(hubDim)), "arrivée au hub à la connexion");
		place(world, me, st.hubId, HubHall.ARRIVAL.x, HubHall.ARRIVAL.y, HubHall.ARRIVAL.z, HubHall.ARRIVAL_YAW, 4f);
		ctx.waitTicks(100); // les textes de la salle sont posés toutes les deux secondes
		check(server.computeOnServer(s -> !level(st.hubId).getEntities(net.minecraft.world.entity.EntityTypes.TEXT_DISPLAY,
			e -> e.entityTags().contains("vc_hub_fixe")).isEmpty()), "salle d'accueil : ses textes sont posés");
		WorldView.clearChat(ctx);
		shot(ctx, "hub-arrivee");

		// Menu J › Mondes : le hub d'abord, puis le monde de la partie principale.
		ctx.setScreen(MainScreen::new);
		ctx.waitTicks(10);
		check(click(ctx, "Mondes"), "entrée « Mondes » du menu J");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof WorldsScreen), "écran « Mondes » ouvert");
		check(waitClient(ctx, 60, mc -> {
			List<String> ids = WorldsScreen.destinations().stream().map(d -> ClientState.str(d, "id")).toList();
			return ids.indexOf(st.hubId) == 0 && ids.indexOf(st.mainId) == 1;
		}), "« Mondes » : le hub, puis la partie principale");
		shot(ctx, "ecran-mondes");
		ctx.setScreen(() -> null);

		// Le tableau des parties : la fiche de la partie principale.
		check(waitFor(ctx, world, 200, s -> {
			String shown = ValcombeMod.hub().boardShown();
			return shown != null && shown.contains("id=" + st.mainId);
		}), "le tableau du hub montre la partie principale");
		place(world, me, st.hubId, HubHall.ARRIVAL.x, HubHall.ARRIVAL.y, HubHall.ARRIVAL.z, HubHall.ARRIVAL_YAW, 8f);
		ctx.waitTicks(40);
		WorldView.clearChat(ctx);
		shot(ctx, "tableau-des-parties");
		ctx.setScreen(() -> new PartiesScreen(st.mainId));
		check(waitClient(ctx, 60, mc -> PartiesScreen.games().stream().anyMatch(g -> st.mainId.equals(ClientState.str(g, "id")))
			&& hdButton(mc, "Créer une partie d'essai") != null), "écran « Parties » : la principale, et la création d'une partie d'essai");
		String problem = ctx.computeOnClient(mc -> TestKit.layoutProblem(mc, true));
		check(problem == null, "mise en page de l'écran « Parties » : " + problem);
		shot(ctx, "ecran-parties");
		ctx.setScreen(() -> null);
	}

	// ------------------------------------------------------------------
	// 2. Atelier en jeu : Cartes, nouvelle carte, atelier carte, WorldEdit, repères, versions
	// ------------------------------------------------------------------

	private void atelierMap(ClientGameTestContext ctx, TestSingleplayerContext world, State st) {
		var server = world.getServer();
		String me = st.me;

		// Menu J › Atelier : l'accueil à quatre entrées.
		ctx.setScreen(MainScreen::new);
		ctx.waitTicks(10);
		press(ctx, "Atelier");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof AtelierHome), "le menu J ouvre l'accueil de l'Atelier");
		for (String tile : List.of(AtelierHome.CARTES, AtelierHome.STRUCTURES, AtelierHome.PARTIES, AtelierHome.ROLES)) {
			check(ctx.computeOnClient(mc -> hdButton(mc, tile) != null), "entrée « " + tile + " »");
		}
		shot(ctx, "atelier-accueil");

		// Cartes : la bibliothèque.
		press(ctx, AtelierHome.CARTES);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof MapsPage), "entrée Cartes : la bibliothèque");
		ctx.runOnClient(mc -> MapsPage.resetFilters());
		ctx.setScreen(() -> new MapsPage(0));
		ctx.waitTicks(30);
		List<String> shown = ctx.computeOnClient(mc -> MapsPage.library().stream().map(o -> ClientState.str(o, "id")).toList());
		check(server.computeOnServer(s -> shown.stream().allMatch(id -> ValcombeMod.maps().exists(id))),
			"chaque carte de l'écran est dans la bibliothèque du serveur : " + shown);
		shot(ctx, "cartes-bibliotheque");

		// Nouvelle carte : un village généré, dix trônes, petite taille, avec son aperçu fait par le serveur.
		press(ctx, MapsPage.NEW);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof NewMapPage) && NewMapPage.step() == 0, "parcours « Nouvelle carte »");
		check(greyed(ctx, NewMapPage.NEXT), "« Suivant » grisé tant qu'aucun départ n'est choisi");
		press(ctx, NewMapPage.VILLAGE);
		check(primary(ctx, NewMapPage.NEXT), "village choisi : « Suivant » mis en avant");
		shot(ctx, "nouvelle-carte-depart");
		press(ctx, NewMapPage.NEXT);
		check(NewMapPage.step() == 1, "étape des réglages");
		st.mapName = "Recette " + st.stamp;
		check(TestKit.typeKeys(ctx, st.mapName), "nom de la carte tapé");
		for (int i = 0; i < 6; i++) {
			press(ctx, "−");
		}
		chooseTab(ctx, "Petite");
		check(waitClient(ctx, 300, mc -> Vignettes.ready("village:gothique:10:0:oui", "v1")), "aperçu du village de dix trônes fait par le serveur");
		ctx.waitTicks(10);
		shot(ctx, "nouvelle-carte-village-apercu");
		press(ctx, NewMapPage.NEXT);
		check(NewMapPage.step() == 2 && primary(ctx, "Générer dans un atelier"), "dernière étape : « Générer dans un atelier »");
		shot(ctx, "nouvelle-carte-verifier");
		List<String> ateliersBefore = server.computeOnServer(s -> mw().ateliers().stream().map(WorldEntry::id).toList());
		// Vu dans le même instant que le clic : la page passe à « Ateliers ouverts » (la pose, puis le voyage, viennent après).
		check(ctx.computeOnClient(mc -> {
			var b = hdButton(mc, "Générer dans un atelier");
			if (b == null || !b.active) {
				return false;
			}
			b.press();
			return mc.gui.screen() instanceof MapsPage && MapsPage.tab() == 1;
		}), "« Générer dans un atelier » ouvre « Ateliers ouverts »");
		check(waitFor(ctx, world, 200, s -> mw().ateliers().stream().anyMatch(e -> !ateliersBefore.contains(e.id()))), "un atelier est ouvert pour le village");
		st.atelier = server.computeOnServer(s -> mw().ateliers().stream().map(WorldEntry::id).filter(id -> !ateliersBefore.contains(id))
			.findFirst().orElseThrow());
		st.mapId = server.computeOnServer(s -> ValcombeMod.worlds().find(st.atelier).carte());
		check(server.computeOnServer(s -> ValcombeMod.worlds().find(st.atelier).nom().equals(st.mapName)), "l'atelier porte le nom tapé");
		ctx.setScreen(() -> new MapsPage(1));
		ctx.waitTicks(20);
		shot(ctx, "ateliers-ouverts");
		String atelier = st.atelier;
		check(waitFor(ctx, world, 4000, s -> mw().idle(atelier) && in(s, me, atelier)), "village bâti dans son atelier, joueur conduit dedans");
		ctx.setScreen(() -> null);
		st.seats = server.computeOnServer(s -> mw().marks(atelier).seatCount());
		check(st.seats == 10, "dix trônes posés : " + st.seats);
		check(waitFor(ctx, world, 100, s -> hasItem(s, me, ValcombeUi::isWand)
			&& hasItem(s, me, i -> ValcombeUi.mapTool(i) == ValcombeUi.MapTool.SEAT)), "baguette de construction et outils de repères donnés à l'entrée");
		check(waitClient(ctx, 100, mc -> mc.player.connection.getCommands().getRoot().getChild("/set") != null),
			"WorldEdit permis dans l'atelier (//set dans l'arbre des commandes)");
		BlockPos center = server.computeOnServer(s -> mw().marks(atelier).center());
		look(world, atelier, me, (center.getX() + 0.5), (S + 14), (center.getZ() + 22.5), center.getX(), S, center.getZ());
		ctx.waitTicks(60);
		WorldView.clearChat(ctx);
		shot(ctx, "atelier-carte-village");

		// WorldEdit : la baguette en main, la sélection en 3D avec ses dimensions, puis //set.
		BlockPos ground = server.computeOnServer(s -> freeGround(level(atelier), center, 4, 6, 40, markers(atelier)));
		check(ground != null, "un coin de sol libre pour bâtir dans l'atelier");
		BlockPos a = ground.above();
		BlockPos b = a.offset(3, 2, 3);
		check(hold(ctx, world, me, ValcombeUi::isWand), "baguette en main");
		type(ctx, "/pos1 " + pos(a));
		type(ctx, "/pos2 " + pos(b));
		check(waitClient(ctx, 40, mc -> BuildSelection.shown()), "sélection reçue par le client, dessinée en 3D");
		String label = ctx.computeOnClient(mc -> BuildSelection.dimensions(BuildSelection.current().box()) + " · "
			+ BuildSelection.blocks(BuildSelection.current().shape(), BuildSelection.current().volume()));
		check(label.equals("L 4 × l 4 × h 3 · 48 blocs"), "étiquette de la sélection : " + label);
		look(world, atelier, me, (a.getX() - 5.5), (S + 6), (a.getZ() - 5.5), (a.getX() + 2), (S + 1), (a.getZ() + 2));
		ctx.waitTicks(30);
		WorldView.clearChat(ctx);
		shot(ctx, "worldedit-baguette-selection-3d");
		type(ctx, "/set stone_bricks");
		check(server.computeOnServer(s -> count(level(atelier), a, b, Blocks.STONE_BRICKS)) == 48, "//set remplit la sélection (48 blocs)");
		ctx.waitTicks(10);
		WorldView.clearChat(ctx);
		shot(ctx, "worldedit-set");

		// Outils de repères : un onzième trône, compté par l'atelier.
		check(hold(ctx, world, me, i -> ValcombeUi.mapTool(i) == ValcombeUi.MapTool.SEAT), "outil « Carte : trône » en main");
		BlockPos seatAt = server.computeOnServer(s -> {
			List<BlockPos> avoid = new ArrayList<>(markers(atelier));
			avoid.add(a);
			avoid.add(b);
			avoid.add(a.offset(3, 0, 0));
			avoid.add(a.offset(0, 0, 3));
			return freeGround(level(atelier), center, 1, 5, 40, avoid);
		});
		check(seatAt != null, "un sol libre pour le trône");
		String added = server.computeOnServer(s -> mw().useTool(player(s, me), ValcombeUi.MapTool.SEAT, seatAt, false));
		check(server.computeOnServer(s -> mw().marks(atelier).seatCount()) == st.seats + 1, "trône ajouté par l'outil : " + added);
		st.seats++;
		look(world, atelier, me, (seatAt.getX() + 0.5), (S + 5), (seatAt.getZ() + 7.5), seatAt.getX(), (S + 1), seatAt.getZ());
		ctx.waitTicks(40);
		WorldView.clearChat(ctx);
		shot(ctx, "outils-de-reperes-trone-ajoute");
		ctx.setScreen(MapWorkshopScreen::new);
		check(waitClient(ctx, 60, mc -> {
			JsonObject d = MapWorkshopScreen.data();
			return d != null && ClientState.str(d, "compteur").startsWith(st.seats + " trônes") && ClientState.bool(d, "modifiee");
		}), "l'écran de l'atelier compte " + st.seats + " trônes, carte modifiée : "
			+ ctx.computeOnClient(mc -> String.valueOf(MapWorkshopScreen.data())));
		shot(ctx, "atelier-carte-ecran");

		// Enregistrer : la carte entre dans la bibliothèque, avec une version.
		check(click(ctx, "Enregistrer"), "bouton « Enregistrer » parmi : " + buttons(ctx));
		String mapId = st.mapId;
		int seats = st.seats;
		check(waitFor(ctx, world, 200, s -> ValcombeMod.maps().list().stream().anyMatch(d -> d.id.equals(mapId) && d.sieges.size() == seats)
			&& ValcombeMod.maps().versions().list(mapId).stream().anyMatch(v -> v.kind() == MapVersions.Kind.MANUELLE)),
			"« Enregistrer » : la carte est dans la bibliothèque, " + seats + " trônes, avec une version");
		ctx.setScreen(MapWorkshopScreen::new);
		check(waitClient(ctx, 60, mc -> !ClientState.bool(MapWorkshopScreen.data(), "modifiee")), "l'écran le sait : plus rien à enregistrer");
		shot(ctx, "atelier-carte-enregistree");

		// Versions : un changement enregistré tout seul, puis le retour à la version enregistrée à la main.
		BlockPos extra = b.above(2);
		String auto = server.computeOnServer(s -> {
			level(atelier).setBlockAndUpdate(extra, Blocks.DIAMOND_BLOCK.defaultBlockState());
			return mw().autosave(atelier, MapVersions.Kind.AUTO);
		});
		check(auto.startsWith("Enregistrement automatique"), "enregistrement automatique : " + auto);
		String autoId = server.computeOnServer(s -> {
			MapVersions.Version last = ValcombeMod.maps().versions().last(mapId);
			return last != null && last.kind() == MapVersions.Kind.AUTO ? last.id() : "";
		});
		check(!autoId.isEmpty(), "l'enregistrement automatique a sa version");
		String manual = server.computeOnServer(s -> ValcombeMod.maps().versions().list(mapId).stream()
			.filter(v -> v.kind() == MapVersions.Kind.MANUELLE && v.seats() == seats).findFirst().orElseThrow().id());
		ctx.setScreen(MapWorkshopScreen::new);
		ctx.waitTicks(10);
		pressStarting(ctx, "Versions (");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof MapWorkshopScreen sc && sc.mode().equals("versions")), "liste des versions");
		check(TestKit.chooseRow(ctx, "id", manual), "la version enregistrée à la main est dans la liste");
		shot(ctx, "atelier-carte-versions");
		press(ctx, "Revenir à cette version");
		check(waitFor(ctx, world, 3000, s -> mw().idle(atelier) && level(atelier).getBlockState(extra).isAir()
			&& count(level(atelier), a, b, Blocks.STONE_BRICKS) == 48), "retour à la version : le bloc de diamant est parti, le socle est resté");
		// L'état d'avant le retour (le bloc de diamant) est gardé : par l'enregistrement automatique qui venait de le prendre
		// (rien n'a changé depuis, le retour ne le reprend pas une seconde fois), ou par une version « avant un retour ».
		check(server.computeOnServer(s -> ValcombeMod.maps().versions().list(mapId).stream()
			.anyMatch(v -> v.kind() == MapVersions.Kind.AVANT_RETOUR || v.id().equals(autoId))),
			"l'état d'avant le retour est gardé en version : " + server.computeOnServer(s -> ValcombeMod.maps().versions().list(mapId)
				.stream().map(v -> v.kind() + " " + v.id()).toList()));
		ctx.setScreen(() -> null);

		// La fiche de la carte dans la bibliothèque : sa vignette, faite par le serveur.
		ctx.setScreen(AtelierHome::new);
		ctx.waitTicks(5);
		press(ctx, AtelierHome.CARTES);
		ctx.runOnClient(mc -> MapsPage.resetFilters());
		ctx.setScreen(() -> new MapsPage(0));
		check(waitClient(ctx, 200, mc -> MapsPage.library().stream().anyMatch(o -> mapId.equals(ClientState.str(o, "id")))),
			"la carte est dans la bibliothèque de l'écran");
		String vstamp = ctx.computeOnClient(mc -> MapsPage.library().stream().filter(o -> mapId.equals(ClientState.str(o, "id")))
			.map(o -> ClientState.str(o, "vignette")).findFirst().orElse(""));
		check(waitClient(ctx, 300, mc -> Vignettes.ready("carte:" + mapId, vstamp)), "sa vignette reçue en texture");
		ctx.setScreen(() -> new MapsPage(0));
		ctx.waitTicks(10);
		shot(ctx, "cartes-bibliotheque-avec-la-carte");
		check(openInGrid(ctx, st.mapName), "case de la carte dans la galerie");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof MapSheetPage p && p.mapId().equals(mapId)), "fiche de la carte");
		check(ctx.computeOnClient(mc -> hdButton(mc, MapSheetPage.GO) != null), "la fiche propose « Aller à son atelier » (elle y est ouverte)");
		shot(ctx, "cartes-fiche");
	}

	// ------------------------------------------------------------------
	// 3. Envoyer à la partie
	// ------------------------------------------------------------------

	private void sendToGame(ClientGameTestContext ctx, TestSingleplayerContext world, State st) {
		var server = world.getServer();
		String me = st.me;
		String mainId = st.mainId;
		String mapId = st.mapId;
		String hubDim = "valcombe:" + st.hubId;

		// Une trace laissée loin dans le monde de la partie : un monde remis à neuf ne la garde pas.
		BlockPos far = new BlockPos(150, S + 4, -140);
		server.runOnServer(s -> Games.main().host().level().setBlockAndUpdate(far, Blocks.SPONGE.defaultBlockState()));
		send(ctx, "vc monde aller " + mainId);
		check(waitFor(ctx, world, 100, s -> in(s, me, mainId)), "dans le monde de la partie principale");
		int linksBefore = ctx.computeOnClient(mc -> chatCount(mc, "[Y aller]"));

		ctx.setScreen(() -> MapSheetPage.visit(mapId));
		ctx.waitTicks(20);
		press(ctx, MapSheetPage.SEND);
		check(ctx.computeOnClient(mc -> hdButton(mc, MapSheetPage.SEND_CONFIRM) != null), "le choix de la partie");
		check(TestKit.chooseRow(ctx, "id", mainId), "la partie principale dans la liste");
		shot(ctx, "envoyer-a-la-partie");
		press(ctx, MapSheetPage.SEND_CONFIRM);
		check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(hubDim)), "pendant la pose, le joueur attend au hub");
		ctx.setScreen(() -> null);
		ctx.waitTicks(20);
		shot(ctx, "envoi-en-cours-au-hub");
		check(waitFor(ctx, world, 4000, s -> !Games.main().host().busy() && !ValcombeMod.sender().busy(mainId)
			&& Games.main().host().village() != null && mapId.equals(Games.main().host().village().libraryId())), "la partie a reçu la carte");
		check(waitClient(ctx, 100, mc -> chatCount(mc, "[Y aller]") > linksBefore), "le message de fin propose « [Y aller] »");
		String fresh = server.computeOnServer(s -> {
			var host = Games.main().host();
			Village v = host.village();
			return "trônes " + v.seatCount() + " | loin " + host.level().getBlockState(far).isAir() + " | sol "
				+ host.level().getBlockState(far.atY(S)).is(Blocks.GRASS_BLOCK) + " | registre " + ValcombeMod.worlds().find(mainId).carte();
		});
		check(fresh.equals("trônes " + st.seats + " | loin true | sol true | registre " + mapId),
			"monde remis à neuf, plat, la carte posée avec ses trônes : " + fresh);

		// Le lien du message ramène au village de la partie.
		send(ctx, "vc monde aller " + mainId);
		check(waitFor(ctx, world, 100, s -> {
			BlockPos spawn = Games.main().host().villageSpawn();
			ServerPlayer p = player(s, me);
			return in(s, me, mainId) && spawn != null && Math.abs(p.getX() - (spawn.getX() + 0.5)) < 2 && Math.abs(p.getZ() - (spawn.getZ() + 0.5)) < 2;
		}), "le joueur est ramené au village de la carte envoyée");
		BlockPos c = server.computeOnServer(s -> Games.main().host().village().center());
		look(world, mainId, me, (c.getX() + 0.5), (S + 16), (c.getZ() + 24.5), c.getX(), S, c.getZ());
		ctx.waitTicks(60);
		WorldView.clearChat(ctx);
		shot(ctx, "partie-principale-carte-posee");

		// L'atelier de la carte se ferme : son monde est supprimé, la carte reste dans la bibliothèque.
		String atelier = st.atelier;
		send(ctx, "vc atelier carte fermer " + atelier + " confirmer");
		check(waitFor(ctx, world, 600, s -> ValcombeMod.worlds().find(atelier) == null && !ValcombeMod.worlds().busy(atelier)
			&& ValcombeMod.maps().exists(mapId)), "atelier de la carte fermé, la carte reste dans la bibliothèque");
		st.atelier = null;
	}

	// ------------------------------------------------------------------
	// 4. Structures
	// ------------------------------------------------------------------

	private void structuresPart(ClientGameTestContext ctx, TestSingleplayerContext world, State st) {
		var server = world.getServer();
		String me = st.me;

		// Le catalogue et la fiche du puits (structure fournie).
		ctx.setScreen(AtelierHome::new);
		ctx.waitTicks(5);
		ctx.runOnClient(mc -> StructuresPage.resetFilters());
		press(ctx, AtelierHome.STRUCTURES);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof StructuresPage), "entrée Structures : le catalogue");
		// La galerie ne demande que les vignettes des cases à l'écran, et le puits est plus bas (rangé par nom) : on la
		// demande comme le fera sa fiche, puis on attend qu'elle arrive.
		check(waitClient(ctx, 400, mc -> StructuresPage.catalog().stream().filter(o -> "puits".equals(ClientState.str(o, "id")))
			.anyMatch(o -> {
				String stamp = ClientState.str(o, "vignette");
				Vignettes.get("structure:puits", stamp);
				return Vignettes.ready("structure:puits", stamp);
			})), "vignette du puits reçue");
		ctx.waitTicks(40);
		shot(ctx, "structures-catalogue");
		String puitsName = server.computeOnServer(s -> structures().def("puits").nom);
		check(openInGrid(ctx, puitsName), "case du puits");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof StructureSheetPage p && p.structureId().equals("puits")), "fiche du puits");
		shot(ctx, "structures-fiche-puits");

		// L'atelier structures : « Aller sur sa parcelle ».
		press(ctx, StructureSheetPage.GO_PLOT);
		check(waitFor(ctx, world, 400, s -> sw().entry() != null && in(s, me, sw().entry().id())), "joueur dans l'atelier structures");
		check(waitFor(ctx, world, 4000, s -> !sw().busy() && sw().parcel("puits") != null), "parcelles posées, celle du puits comprise");
		String ws = server.computeOnServer(s -> sw().entry().id());
		StructureWorkshop.ParcelInfo puits = server.computeOnServer(s -> sw().parcel("puits"));
		BlockPos bell = new BlockPos(puits.x(), S + 5, puits.z());
		check(server.computeOnServer(s -> level(ws).getBlockState(bell).is(Blocks.BELL) && "fournie".equals(sw().parcel("puits").etat())),
			"le puits est sur sa parcelle, « fournie », sa cloche en place");
		ctx.setScreen(() -> null);
		place(world, me, ws, puits.x() + 0.5, S + 4, puits.lecternZ() - 4.5, 0f, 25f);
		ctx.waitTicks(60);
		WorldView.clearChat(ctx);
		shot(ctx, "atelier-structures-parcelle");

		// Retouche avec WorldEdit : la cloche devient un bloc d'or ; « Enregistrer » au pupitre en fait la version qui sert partout.
		st.puitsTouched = true;
		type(ctx, "/pos1 " + pos(bell));
		type(ctx, "/pos2 " + pos(bell));
		type(ctx, "/set gold_block");
		check(waitFor(ctx, world, 40, s -> level(ws).getBlockState(bell).is(Blocks.GOLD_BLOCK) && Boolean.TRUE.equals(sw().changes("puits"))),
			"//set a retouché le puits, l'atelier voit la modification");
		server.runOnServer(s -> sw().onUse(player(s, me), level(ws), new BlockPos(puits.x(), S + 1, puits.lecternZ()), true));
		check(waitClient(ctx, 40, mc -> mc.gui.screen() instanceof StructuresWorkshopScreen sc && "puits".equals(sc.chosenId())),
			"le pupitre ouvre l'écran de l'atelier sur le puits");
		ctx.waitTicks(20);
		shot(ctx, "atelier-structures-puits-retouche");
		check(click(ctx, "Enregistrer"), "bouton « Enregistrer » de l'atelier structures");
		check(waitFor(ctx, world, 200, s -> structures().isOverridden("puits") && "modifiee".equals(sw().parcel("puits").etat())),
			"le puits retouché remplace l'original partout");
		ctx.setScreen(() -> StructureSheetPage.visit("puits"));
		ctx.waitTicks(20);
		check(ctx.computeOnClient(mc -> hdButton(mc, StructureSheetPage.REVERT) != null), "la fiche propose « Revenir à l'originale »");
		shot(ctx, "structures-fiche-puits-modifie");

		// Revenir à l'originale, depuis le pupitre (avec confirmation).
		ctx.setScreen(() -> new StructuresWorkshopScreen("puits"));
		ctx.waitTicks(20);
		check(click(ctx, "Revenir à l'originale"), "bouton « Revenir à l'originale »");
		check(click(ctx, "Oui, l'originale"), "confirmation du retour à l'originale");
		check(waitFor(ctx, world, 400, s -> !structures().isOverridden("puits") && !sw().busy()
			&& level(ws).getBlockState(bell).is(Blocks.BELL) && "fournie".equals(sw().parcel("puits").etat())),
			"le puits est revenu à l'original, la cloche de nouveau sur sa parcelle");
		st.puitsTouched = false;
		ctx.setScreen(() -> null);
		ctx.waitTicks(20);
		WorldView.clearChat(ctx);
		shot(ctx, "atelier-structures-puits-original");

		// Nouvelle structure depuis une sélection : une petite tour bâtie à WorldEdit hors des parcelles, puis le parcours guidé.
		BlockPos spot = server.computeOnServer(s -> {
			int minX = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			for (StructureWorkshop.ParcelInfo p : sw().parcels()) {
				minX = Math.min(minX, p.x() - p.rayon());
				minZ = Math.min(minZ, Math.min(p.z() - p.rayon(), p.lecternZ()));
			}
			return freeGround(level(ws), new BlockPos(minX, S, minZ - 14), 3, 0, 20, List.of());
		});
		check(spot != null, "un coin libre de l'atelier structures, hors des parcelles");
		BlockPos ta = spot.above();
		BlockPos tb = ta.offset(2, 2, 2);
		place(world, me, ws, ta.getX() - 3.5, S + 4, ta.getZ() - 4.5, -30f, 30f);
		ctx.waitTicks(10);
		type(ctx, "/pos1 " + pos(ta));
		type(ctx, "/pos2 " + pos(tb));
		type(ctx, "/set stone_bricks");
		check(server.computeOnServer(s -> count(level(ws), ta, tb, Blocks.STONE_BRICKS)) == 27, "la tour d'essai est bâtie (27 blocs)");
		ctx.setScreen(() -> new StructuresPage(1));
		check(waitClient(ctx, 60, mc -> {
			var b = hdButton(mc, "En structure…");
			return b != null && b.active;
		}), "Structures › Construire : « En structure… » avec la sélection en cours");
		shot(ctx, "structures-construire-selection");
		press(ctx, "En structure…");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof NewStructurePage) && NewStructurePage.step() == 1,
			"« Nouvelle structure » depuis la sélection : directement aux réglages");
		String sname = "Tour de recette " + st.stamp;
		check(TestKit.typeIn(ctx, sname), "champ du nom de la structure");
		ctx.setScreen(NewStructurePage::new);
		ctx.waitTicks(8);
		check(primary(ctx, NewStructurePage.NEXT), "nommée : « Suivant » mis en avant");
		press(ctx, NewStructurePage.NEXT);
		check(primary(ctx, NewStructurePage.SAVE_SELECTION), "dernière étape : « Enregistrer la sélection »");
		shot(ctx, "nouvelle-structure-depuis-la-selection");
		press(ctx, NewStructurePage.SAVE_SELECTION);
		check(waitFor(ctx, world, 200, s -> structures().list().stream().anyMatch(d -> !d.fournie && d.nom.equals(sname))),
			"la sélection est entrée au catalogue");
		st.structure = server.computeOnServer(s -> structures().list().stream().filter(d -> !d.fournie && d.nom.equals(sname))
			.map(d -> d.id).findFirst().orElseThrow());
		String sid = st.structure;
		String size = server.computeOnServer(s -> {
			StructureStore.Def d = structures().def(sid);
			return d.taille[0] + "×" + d.taille[1] + "×" + d.taille[2] + " " + d.blocs;
		});
		check(size.equals("3×3×3 27"), "la structure a la taille de la sélection : " + size);
		check(waitClient(ctx, 100, mc -> StructuresPage.catalog().stream().anyMatch(o -> sid.equals(ClientState.str(o, "id")))),
			"elle paraît dans le catalogue de l'écran");
		ctx.setScreen(() -> StructureSheetPage.visit(sid));
		check(waitClient(ctx, 300, mc -> Vignettes.ready("structure:" + sid, "")), "sa vignette est faite");
		ctx.setScreen(() -> StructureSheetPage.visit(sid));
		ctx.waitTicks(10);
		shot(ctx, "structures-fiche-nouvelle");
		ctx.setScreen(() -> null);
		send(ctx, "vc hub");
		check(waitFor(ctx, world, 100, s -> in(s, me, st.hubId)), "retour au hub");
	}

	// ------------------------------------------------------------------
	// 5. Rôles et scénarios ; la partie d'essai
	// ------------------------------------------------------------------

	private void rolesAndScenario(ClientGameTestContext ctx, TestSingleplayerContext world, State st) {
		var server = world.getServer();
		String me = st.me;

		// Atelier › Parties : une partie d'essai créée par la page.
		ctx.setScreen(AtelierHome::new);
		ctx.waitTicks(5);
		press(ctx, AtelierHome.PARTIES);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof PartiesPage), "entrée Parties");
		press(ctx, PartiesPage.CREATE);
		check(greyed(ctx, PartiesPage.CREATE_CONFIRM), "sans nom, « Créer » grisé");
		String trialName = "Essai recette " + st.stamp;
		check(TestKit.typeIn(ctx, trialName), "champ du nom de la partie d'essai");
		ctx.setScreen(PartiesPage::new);
		ctx.waitTicks(8);
		press(ctx, PartiesPage.CREATE_CONFIRM);
		check(waitFor(ctx, world, 200, s -> ValcombeMod.worlds().list().stream().anyMatch(e -> e.nom().equals(trialName))), "partie d'essai créée");
		st.essai = server.computeOnServer(s -> ValcombeMod.worlds().list().stream().filter(e -> e.nom().equals(trialName))
			.map(WorldEntry::id).findFirst().orElseThrow());
		String essai = st.essai;
		check(waitFor(ctx, world, 3000, s -> Games.byWorld(essai) != null && !ValcombeMod.sender().busy(essai)
			&& Games.byWorld(essai).host().village() != null && !Games.byWorld(essai).host().busy()), "le village de la partie d'essai est posé");
		ctx.setScreen(PartiesPage::new);
		check(waitClient(ctx, 100, mc -> grid(mc) != null && grid(mc).labels().contains(trialName)), "elle paraît dans la liste des parties");
		ctx.waitTicks(20);
		shot(ctx, "atelier-parties");

		// Rôles et scénarios : un scénario de la table, choisi pour la partie d'essai.
		String scName = "Scénario de recette " + st.stamp;
		String roleName = "Sonneur de recette " + st.stamp;
		server.runOnServer(s -> {
			var d = new fr.valcombe.session.ScenarioDef();
			d.id = st.scenario;
			d.nom = scName;
			d.description = "Le scénario du test de recette : les rôles classiques, sur la carte en place.";
			for (var role : fr.valcombe.rules.Role.classic()) {
				d.roles.add(role.name().toLowerCase(Locale.ROOT));
			}
			ValcombeMod.creations().saveScenario(d);
			var r = new fr.valcombe.rules.RoleDef();
			r.id = st.role;
			r.nom = roleName;
			r.camp = "CITADIN";
			r.embleme = "e_cle";
			r.texte = "Le rôle du test de recette : chaque nuit, vous apprenez si la cloche du puits a sonné.";
			ValcombeMod.creations().saveRole(r);
		});
		ctx.setScreen(AtelierHome::new);
		ctx.waitTicks(5);
		press(ctx, AtelierHome.ROLES);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof RolesPage), "entrée Rôles et scénarios");
		check(waitClient(ctx, 100, mc -> RolesPage.scenarios().stream().anyMatch(o -> st.scenario.equals(ClientState.str(o, "id")))
			&& RolesPage.roles().stream().anyMatch(o -> st.role.equals(ClientState.str(o, "id")))), "le scénario et le rôle sont dans les listes");
		ctx.setScreen(() -> new RolesPage(0));
		ctx.waitTicks(10);
		shot(ctx, "scenarios-liste");
		ctx.setScreen(() -> new RolesPage(1));
		ctx.waitTicks(10);
		check(ctx.computeOnClient(mc -> grid(mc) != null && grid(mc).labels().contains(roleName)), "la liste des rôles créés, celui de la recette compris");
		shot(ctx, "roles-liste");
		ctx.setScreen(() -> new RolesPage(0));
		ctx.waitTicks(10);
		check(openInGrid(ctx, scName), "case du scénario");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof ScenarioSheetPage p && p.scenarioId().equals(st.scenario)), "fiche du scénario");
		shot(ctx, "scenario-fiche");
		press(ctx, ScenarioSheetPage.CHOOSE);
		check(TestKit.chooseRow(ctx, "id", essai), "la partie d'essai dans la liste");
		check(primary(ctx, ScenarioSheetPage.CHOOSE_CONFIRM), "« Choisir » mis en avant (la carte en place sert)");
		shot(ctx, "scenario-pour-la-partie-d-essai");
		press(ctx, ScenarioSheetPage.CHOOSE_CONFIRM);
		check(waitFor(ctx, world, 100, s -> Games.byWorld(essai).session().scenario() != null
			&& st.scenario.equals(Games.byWorld(essai).session().scenario().id)), "scénario choisi pour la partie d'essai");
		check(server.computeOnServer(s -> Games.main().session().scenario() == null
			|| !st.scenario.equals(Games.main().session().scenario().id)), "la partie principale garde le sien");
		check(!server.computeOnServer(s -> Games.byWorld(essai).host().busy()), "la carte en place sert : rien n'est reposé");
		ctx.setScreen(() -> null);
	}

	// ------------------------------------------------------------------
	// 6. Deux parties en même temps
	// ------------------------------------------------------------------

	private void twoGames(ClientGameTestContext ctx, TestSingleplayerContext world, State st) {
		var server = world.getServer();
		String me = st.me;
		String essai = st.essai;
		String mainId = st.mainId;

		// La partie d'essai : le joueur y va, en devient le Conteur, cinq joueurs fictifs, lancée en mode humain.
		send(ctx, "vc partie aller " + essai);
		check(waitFor(ctx, world, 200, s -> in(s, me, essai)), "arrivé dans la partie d'essai");
		send(ctx, "vc conteur");
		send(ctx, "vc bots 5");
		check(waitFor(ctx, world, 60, s -> me.equals(Games.byWorld(essai).session().storyteller())
			&& Games.byWorld(essai).session().lobby().size() == 5), "Conteur et cinq joueurs fictifs à la partie d'essai");
		send(ctx, "vc lancer humain");
		check(waitFor(ctx, world, 200, s -> Games.byWorld(essai).session().game() != null), "partie d'essai lancée, Conteur humain");
		check(server.computeOnServer(s -> Games.byWorld(essai).session().scenario().id.equals(st.scenario)), "elle se joue avec le scénario choisi");

		// La partie principale : six joueurs fictifs, Conteur automatique, lancée depuis son monde.
		server.runOnServer(s -> {
			runIn(s, mainId, "vc bots 6");
			runIn(s, mainId, "vc lancer auto");
		});
		check(waitFor(ctx, world, 200, s -> Games.main().session().game() != null), "partie principale lancée");
		check(server.computeOnServer(s -> Games.main().session().game().size() == 6 && Games.main().session().storyteller() == null
			&& Games.byWorld(essai).session().game().size() == 5 && Games.anyInProgress()), "deux parties en même temps, chacune ses joueurs");
		check(server.computeOnServer(s -> Games.main().session().game().players().stream().noneMatch(p -> p.name.equals(me))
			&& Games.byWorld(essai).session().game().players().stream().noneMatch(p -> p.name.equals(me))), "le Conteur n'est à aucune table");
		check(waitClient(ctx, 100, mc -> essai.equals(ClientState.str(ClientState.obj(ClientState.view(), "partie"), "id"))),
			"la vue du joueur est celle de la partie d'essai");

		// Une nuit et un jour dans la principale : rien n'en arrive au Conteur de la partie d'essai.
		String night = "La nuit 1 tombe";
		String dawn = "L'aube se lève";
		String noneExecuted = "Personne n'est exécuté aujourd'hui";
		int nightsBefore = ctx.computeOnClient(mc -> chatCount(mc, night));
		int dawnsBefore = ctx.computeOnClient(mc -> chatCount(mc, dawn));
		int endsBefore = ctx.computeOnClient(mc -> chatCount(mc, noneExecuted));
		String essaiBefore = server.computeOnServer(s -> snapshot(Games.byWorld(essai)));
		server.runOnServer(s -> runIn(s, mainId, "vc nuit"));
		check(waitFor(ctx, world, 3000, s -> Games.main().session().game().phase() == Game.Phase.DAY), "la principale a passé sa première nuit : "
			+ server.computeOnServer(s -> phaseOf(Games.main())));
		check(waitFor(ctx, world, 300, s -> someClaim(Games.main())), "au jour, les joueurs fictifs de la principale annoncent leur rôle");
		String mainDay = server.computeOnServer(s -> {
			runIn(s, mainId, "vc finjour");
			runIn(s, mainId, "vc pause"); // le Conteur automatique ne fait pas tomber la nuit suivante pendant le test
			Game g = Games.main().session().game();
			return (g != null && g.day() == 1 && g.dayOver() && Games.main().paused()) + " " + phaseOf(Games.main());
		});
		check(mainDay.startsWith("true"), "la principale a fini son premier jour, mise en pause : " + mainDay);
		ctx.waitTicks(40);
		check(server.computeOnServer(s -> snapshot(Games.byWorld(essai))).equals(essaiBefore), "la partie d'essai n'a pas bougé");
		check(ctx.computeOnClient(mc -> chatCount(mc, night) == nightsBefore && chatCount(mc, dawn) == dawnsBefore
			&& chatCount(mc, noneExecuted) == endsBefore), "aucune annonce de la principale n'arrive dans la partie d'essai");
		// Les titres : celui de la principale ne s'affiche pas chez le Conteur de la partie d'essai, celui de sa partie si.
		String titleMain = "P" + st.stamp;
		String titleEssai = "E" + st.stamp;
		server.runOnServer(s -> Games.main().host().title(List.of(me), titleMain, ""));
		ctx.waitTicks(10);
		check(!ctx.computeOnClient(mc -> titleText(mc).contains(titleMain)), "aucun titre de la principale n'arrive dans la partie d'essai");
		server.runOnServer(s -> Games.byWorld(essai).host().title(List.of(me), titleEssai, ""));
		check(waitClient(ctx, 40, mc -> titleText(mc).contains(titleEssai)), "le titre de sa partie s'affiche");
		String essaiPhase = server.computeOnServer(s -> Games.byWorld(essai).session().game().phase().name());
		check(waitClient(ctx, 40, mc -> essai.equals(ClientState.str(ClientState.obj(ClientState.view(), "partie"), "id"))
			&& essaiPhase.equals(ClientState.phase())), "la vue reste celle de la partie d'essai (" + essaiPhase + "), pas du jour de la principale");

		// Une nuit et un jour dans la partie d'essai, menés par le Conteur.
		String mainBefore = server.computeOnServer(s -> snapshot(Games.main()));
		send(ctx, "vc nuit");
		check(waitFor(ctx, world, 100, s -> Games.byWorld(essai).session().game().phase() == Game.Phase.NIGHT), "la nuit tombe sur la partie d'essai");
		check(waitClient(ctx, 60, mc -> chatCount(mc, night) > nightsBefore), "le Conteur reçoit l'annonce de sa nuit");
		ctx.setScreen(NightScreen::new);
		ctx.waitTicks(20);
		shot(ctx, "partie-essai-nuit-pupitre");
		ctx.setScreen(() -> null);
		for (int guard = 0; guard < 600 && server.computeOnServer(s -> Games.byWorld(essai).session().game() != null
			&& Games.byWorld(essai).session().game().phase() == Game.Phase.NIGHT); guard++) {
			JsonObject p = ctx.computeOnClient(mc -> ClientState.obj(ClientState.view(), "prompt"));
			if (p != null) {
				ctx.setScreen(PromptScreen::new);
				ctx.waitTicks(5);
				answer(ctx, p);
				ctx.setScreen(() -> null);
			} else {
				ctx.waitTicks(10);
			}
		}
		check(server.computeOnServer(s -> Games.byWorld(essai).session().game().phase() == Game.Phase.DAY), "la partie d'essai a passé sa première nuit : "
			+ server.computeOnServer(s -> phaseOf(Games.byWorld(essai))));
		check(waitClient(ctx, 60, mc -> chatCount(mc, dawn) > dawnsBefore), "le Conteur reçoit le lever du jour de sa partie");
		check(waitFor(ctx, world, 300, s -> someClaim(Games.byWorld(essai))), "au jour, les joueurs fictifs de la partie d'essai annoncent leur rôle");
		ctx.setScreen(StorytellerScreen::new);
		ctx.waitTicks(20);
		shot(ctx, "partie-essai-jour-pupitre");
		ctx.setScreen(() -> null);
		send(ctx, "vc finjour");
		check(waitFor(ctx, world, 60, s -> Games.byWorld(essai).session().game().dayOver()), "le Conteur clôt le premier jour");
		check(waitClient(ctx, 60, mc -> chatCount(mc, noneExecuted) > endsBefore), "la fin du jour s'annonce dans la partie d'essai");
		check(server.computeOnServer(s -> snapshot(Games.main())).equals(mainBefore), "la principale n'a pas bougé pendant ce temps");

		// Au hub : le tableau montre les deux parties en cours.
		send(ctx, "vc hub");
		check(waitFor(ctx, world, 100, s -> in(s, me, st.hubId)), "le Conteur passe au hub");
		check(waitFor(ctx, world, 200, s -> {
			String shown = ValcombeMod.hub().boardShown();
			return shown != null && shown.contains("id=" + mainId) && shown.contains("id=" + essai);
		}), "le tableau du hub montre les deux parties : " + server.computeOnServer(s -> ValcombeMod.hub().boardShown()));
		place(world, me, st.hubId, HubHall.ARRIVAL.x, HubHall.ARRIVAL.y, HubHall.ARRIVAL.z, HubHall.ARRIVAL_YAW, 8f);
		ctx.waitTicks(60);
		WorldView.clearChat(ctx);
		shot(ctx, "tableau-deux-parties");
	}

	// ------------------------------------------------------------------
	// 7. Suspens et reprise, par l'écran « Parties »
	// ------------------------------------------------------------------

	private void suspendAndResume(ClientGameTestContext ctx, TestSingleplayerContext world, State st) {
		var server = world.getServer();
		String me = st.me;
		String essai = st.essai;

		ctx.setScreen(() -> new PartiesScreen(essai));
		check(waitClient(ctx, 60, mc -> PartiesScreen.games().size() >= 2 && hdButton(mc, "Suspendre") != null), "écran « Parties » sur la partie d'essai");
		shot(ctx, "parties-deux-en-cours");
		String mainBefore = server.computeOnServer(s -> snapshot(Games.main()));
		check(click(ctx, "Suspendre"), "« Suspendre »");
		check(waitFor(ctx, world, 100, s -> Games.byWorld(essai).suspended() && Games.byWorld(essai).host().clockFrozen()),
			"la partie d'essai est en suspens, son horloge gelée");
		check(server.computeOnServer(s -> !Games.main().suspended() && snapshot(Games.main()).equals(mainBefore)), "la principale n'est pas touchée");
		check(waitClient(ctx, 100, mc -> PartiesScreen.games().stream().anyMatch(g -> essai.equals(ClientState.str(g, "id"))
			&& "suspendue".equals(ClientState.str(g, "statut")))), "la vue du tableau la montre en suspens");
		ctx.setScreen(() -> new PartiesScreen(essai));
		check(waitClient(ctx, 40, mc -> hdButton(mc, "Reprendre") != null), "l'écran propose « Reprendre »");
		shot(ctx, "partie-essai-en-suspens");
		check(click(ctx, "Reprendre"), "« Reprendre »");
		check(waitFor(ctx, world, 100, s -> !Games.byWorld(essai).suspended() && !Games.byWorld(essai).host().clockFrozen()),
			"la partie d'essai reprend, son horloge repart");
		ctx.setScreen(() -> null);
		send(ctx, "vc partie aller " + essai);
		check(waitFor(ctx, world, 200, s -> in(s, me, essai)), "le Conteur retourne à sa partie");
		check(waitFor(ctx, world, 400, s -> !Games.byWorld(essai).session().gathering() && !Games.byWorld(essai).paused()),
			"tout le monde est là : la partie repart");
		check(server.computeOnServer(s -> Games.byWorld(essai).session().game().day() == 1 && Games.byWorld(essai).session().game().dayOver()),
			"elle reprend où elle en était (fin du premier jour)");
		ctx.waitTicks(20);
		WorldView.clearChat(ctx);
		shot(ctx, "partie-essai-reprise");
	}

	// ------------------------------------------------------------------
	// 8. Sauvegarde par l'API, puis des changements à défaire
	// ------------------------------------------------------------------

	private void backup(ClientGameTestContext ctx, TestSingleplayerContext world, State st) {
		var server = world.getServer();
		AdminHttp api = AdminHttp.open(600);
		var made = api.post(ctx, "/admin/sauvegardes", "{}");
		check(made.statusCode() == 202, "POST /admin/sauvegardes : " + made.statusCode() + " " + made.body());
		st.backup = json(made).get("nom").getAsString();
		check(waitFor(ctx, world, 2400, s -> ValcombeMod.backups().running() == null), "sauvegarde terminée");
		check(server.computeOnServer(s -> ValcombeMod.backups().last() != null && ValcombeMod.backups().last().ok()
			&& st.backup.equals(ValcombeMod.backups().last().name())), "sauvegarde réussie : " + st.backup);
		boolean listed = false;
		for (JsonElement e : json(api.get(ctx, "/admin/sauvegardes")).getAsJsonArray("sauvegardes")) {
			listed |= e.getAsJsonObject().get("nom").getAsString().equals(st.backup);
		}
		check(listed, "la sauvegarde est dans la liste de l'API");

		// Après la sauvegarde : un bloc au hub (le monde), hors de la salle et en l'air, un rôle créé (la configuration).
		st.hubMark = new BlockPos(HubHall.HALF_X + 16, HubHall.FEET + 12, 24);
		st.roleAfter = "recette-apres-" + st.stamp;
		String placed = server.computeOnServer(s -> {
			boolean free = level(st.hubId).getBlockState(st.hubMark).isAir();
			level(st.hubId).setBlockAndUpdate(st.hubMark, Blocks.GOLD_BLOCK.defaultBlockState());
			var r = new fr.valcombe.rules.RoleDef();
			r.id = st.roleAfter;
			r.nom = "Rôle d'après la sauvegarde";
			r.camp = "CITADIN";
			r.embleme = "e_cle";
			r.texte = "Créé après la sauvegarde : la restauration doit le retirer.";
			ValcombeMod.creations().saveRole(r);
			return free + " " + level(st.hubId).getBlockState(st.hubMark).is(Blocks.GOLD_BLOCK) + " "
				+ ValcombeMod.creations().roles().stream().anyMatch(d -> d.id.equals(st.roleAfter));
		});
		check(placed.equals("true true true"), "changements faits après la sauvegarde (bloc au hub, rôle créé) : " + placed);

		// La restauration par l'API : refusée en partie solo, comme prévu (elle remplace le monde avant son ouverture).
		var refused = api.post(ctx, "/admin/sauvegardes/restaurer", "{\"nom\": \"" + st.backup + "\", \"redemarrer\": true}");
		check(refused.statusCode() == 409, "restauration par l'API refusée en solo (serveur dédié seulement) : " + refused.statusCode() + " "
			+ refused.body());

		// Ce que la restauration hors ligne doit savoir : le dossier du serveur et celui du monde.
		st.serverDir = server.computeOnServer(s -> s.getServerDirectory().toAbsolutePath().normalize());
		st.worldRel = server.computeOnServer(s -> {
			Path w = ValcombeMod.backups().worldDir();
			return w.startsWith(st.serverDir) ? st.serverDir.relativize(w).toString().replace('\\', '/') : null;
		});
		check(st.worldRel != null, "le monde est dans le dossier du jeu");
		server.runOnServer(s -> Games.saveAll());
		ctx.waitTicks(10);
	}

	/**
	 * Le monde fermé, la restauration est appliquée comme au démarrage d'un serveur dédié : la demande que pose
	 * {@code POST /admin/sauvegardes/restaurer} (BackupService.scheduleRestore), puis {@link Restorer#applyPending}, que le
	 * mod lance au démarrage (BackupService.restoreAtStartup) avant l'ouverture du monde.
	 */
	private static void restoreOffline(State st) {
		check(st.backup != null && st.serverDir != null, "une sauvegarde à restaurer");
		Path valcombeDir = st.serverDir.resolve("config").resolve(BackupContents.VALCOMBE);
		try {
			RestoreMarker.write(valcombeDir, new RestoreMarker.Request(st.backup, Instant.now(), "test de recette", st.worldRel));
		} catch (IOException e) {
			throw new AssertionError("Échec : demande de restauration impossible à poser : " + e.getMessage(), e);
		}
		String version = FabricLoader.getInstance().getModContainer("valcombe").map(m -> m.getMetadata().getVersion().getFriendlyString())
			.orElse("?");
		Optional<Restorer.Report> report = new Restorer(st.serverDir, new BackupStore(st.serverDir.resolve(BackupStore.DIR_NAME)),
			Clock.systemUTC(), ZoneId.systemDefault(), version).applyPending();
		check(report.isPresent(), "la demande de restauration est lue au démarrage");
		st.safety = report.get().securite();
		check(report.get().ok() && st.backup.equals(report.get().nom()), "restauration appliquée : " + report.get().message());
		check(st.safety != null, "l'état d'avant est gardé dans une sauvegarde de sécurité");
		check(!RestoreMarker.present(valcombeDir), "la demande est retirée (elle ne sera pas rejouée)");
	}

	// ------------------------------------------------------------------
	// 9. Seconde vie : l'état revenu, la fin des parties
	// ------------------------------------------------------------------

	private void secondLife(ClientGameTestContext ctx, TestSingleplayerContext world, State st) {
		ctx.waitTicks(80);
		var server = world.getServer();
		String me = st.me;
		String essai = st.essai;
		String mainId = st.mainId;
		server.runCommand("op " + me);
		ctx.waitTicks(20);

		// L'état revenu : le monde et la configuration d'au moment de la sauvegarde, les deux parties comprises.
		String back = server.computeOnServer(s -> "bloc " + level(st.hubId).getBlockState(st.hubMark).isAir()
			+ " | rôle " + ValcombeMod.creations().roles().stream().noneMatch(d -> d.id.equals(st.roleAfter))
			+ " | carte " + ValcombeMod.maps().exists(st.mapId)
			+ " | structure " + structures().exists(st.structure)
			+ " | scénario " + ValcombeMod.creations().scenarios().stream().anyMatch(d -> d.id.equals(st.scenario))
			+ " | rôle d'avant " + ValcombeMod.creations().roles().stream().anyMatch(d -> d.id.equals(st.role)));
		check(back.equals("bloc true | rôle true | carte true | structure true | scénario true | rôle d'avant true"),
			"après la restauration : le bloc posé au hub et le rôle créé après la sauvegarde ont disparu, le reste est là : " + back);
		String games = server.computeOnServer(s -> {
			GameRoom e = Games.byWorld(essai);
			GameRoom m = Games.main();
			return "essai " + (e != null && e.session().game() != null && me.equals(e.session().storyteller())
				&& e.session().game().day() == 1 && e.session().scenario() != null && st.scenario.equals(e.session().scenario().id))
				+ " | principale " + (m.session().game() != null && m.session().game().day() == 1 && m.paused());
		});
		check(games.equals("essai true | principale true"), "les deux parties reprennent telles que sauvegardées : " + games);
		AdminHttp api = AdminHttp.open(600);
		JsonObject list = json(api.get(ctx, "/admin/sauvegardes"));
		JsonObject last = list.has("derniereRestauration") && list.get("derniereRestauration").isJsonObject()
			? list.getAsJsonObject("derniereRestauration") : null;
		check(last != null && st.backup.equals(last.get("nom").getAsString()) && last.get("ok").getAsBoolean(),
			"l'API d'administration rend compte de la restauration : " + last);
		send(ctx, "vc hub");
		check(waitFor(ctx, world, 100, s -> in(s, me, st.hubId)), "au hub après le redémarrage");
		ctx.setScreen(() -> new PartiesScreen(essai));
		check(waitClient(ctx, 100, mc -> PartiesScreen.games().stream().filter(g -> essai.equals(ClientState.str(g, "id"))
			|| mainId.equals(ClientState.str(g, "id"))).count() == 2), "l'écran « Parties » retrouve les deux parties");
		ctx.waitTicks(10);
		shot(ctx, "apres-restauration-parties");
		ctx.setScreen(() -> null);

		// Fin de la partie d'essai : son démon meurt, le bien gagne ; la principale n'en sait rien.
		send(ctx, "vc partie aller " + essai);
		check(waitFor(ctx, world, 200, s -> in(s, me, essai)), "le Conteur retourne à sa partie");
		check(waitFor(ctx, world, 400, s -> !Games.byWorld(essai).session().gathering() && !Games.byWorld(essai).paused()),
			"la partie d'essai repart");
		String win = "Fin de la partie : victoire du";
		String mainBefore = server.computeOnServer(s -> snapshot(Games.main()));
		for (int i = 0; i < 4 && server.computeOnServer(s -> Games.byWorld(essai).session().game() != null); i++) {
			String demon = server.computeOnServer(s -> Games.byWorld(essai).session().game().players().stream()
				.filter(p -> p.alive() && p.role().type == RoleType.DEMON).map(p -> p.name).findFirst().orElse(null));
			check(demon != null, "un démon vivant à la partie d'essai");
			send(ctx, "vc tuer " + demon);
			ctx.waitTicks(10);
		}
		check(waitFor(ctx, world, 100, s -> Games.byWorld(essai).session().game() == null && Games.byWorld(essai).session().lastGame() != null
			&& Games.byWorld(essai).session().lastGame().winner() == Team.GOOD), "le démon mort, la partie d'essai finit : victoire du bien");
		check(waitClient(ctx, 60, mc -> chatCount(mc, win + " bien") > 0), "le Conteur reçoit l'annonce de la fin de sa partie");
		check(server.computeOnServer(s -> snapshot(Games.main())).equals(mainBefore), "la principale continue, sans rien voir");
		ctx.waitTicks(60);
		shot(ctx, "fin-partie-essai-revelation");
		ctx.setScreen(ChronicleScreen::new);
		ctx.waitTicks(20);
		shot(ctx, "fin-partie-essai-chronique");
		ctx.setScreen(() -> null);

		// Fin de la principale : déclarée gagnée par le mal ; son annonce n'arrive pas dans la partie d'essai.
		server.runOnServer(s -> runIn(s, mainId, "vc victoire mal"));
		check(waitFor(ctx, world, 100, s -> Games.main().session().game() == null && Games.main().session().lastGame() != null
			&& Games.main().session().lastGame().winner() == Team.EVIL), "la principale finit : victoire du mal");
		ctx.waitTicks(40);
		check(ctx.computeOnClient(mc -> chatCount(mc, win + " mal") == 0), "la fin de la principale ne s'annonce pas dans la partie d'essai");
		WorldView.clearChat(ctx);
		shot(ctx, "fin-des-deux-parties");
		// La remise en état des cartes attend la fin de la veillée (trente secondes au moins), puis se fait par petits lots.
		ctx.waitTicks(700);
		check(waitFor(ctx, world, 2400, s -> !Games.main().host().busy() && !Games.byWorld(essai).host().busy()),
			"les deux cartes sont remises en état après la fin");
	}

	// ------------------------------------------------------------------
	// Ménage
	// ------------------------------------------------------------------

	/** Retire ce que le test a créé, même après un échec ; la partie principale retrouve un village généré. */
	private static void cleanup(ClientGameTestContext ctx, TestSingleplayerContext world, State st, int initialScale) {
		var server = world.getServer();
		ctx.setScreen(() -> null);
		ctx.getInput().resizeWindow(854, 480);
		ctx.runOnClient(mc -> {
			mc.options.guiScale().set(initialScale);
			mc.resizeGui();
		});
		try {
			if (st.me != null) {
				server.runOnServer(s -> {
					GameRoom r = st.essai == null ? null : Games.byWorld(st.essai);
					if (r != null && r.session().game() != null) {
						r.session().stop();
					}
				});
				cleanMain(world, st.me, true);
			}
			TestKit.waitFor(ctx, world, 2400, 5, s -> Games.main() != null && !Games.main().host().busy()
				&& (st.essai == null || Games.byWorld(st.essai) == null || !Games.byWorld(st.essai).host().busy()));
			server.runOnServer(s -> {
				if (st.essai != null && Games.byWorld(st.essai) != null && !ValcombeMod.worlds().busy(st.essai)) {
					try {
						Games.current().delete(st.essai);
					} catch (RuntimeException e) {
						System.out.println("[valcombe-test] recette : partie d'essai restée : " + st.essai + " (" + e.getMessage() + ")");
					}
				}
				if (st.mapId != null) {
					WorldEntry e = mw().atelierOf(st.mapId);
					if (e != null && !ValcombeMod.worlds().busy(e.id())) {
						ValcombeMod.worlds().delete(e.id());
					}
				}
				if (structures().isOverridden("puits")) {
					structures().revert("puits");
				}
				if (st.structure != null && structures().exists(st.structure)) {
					structures().delete(st.structure);
				}
				var creations = ValcombeMod.creations();
				if (creations.scenarios().stream().anyMatch(d -> d.id.equals(st.scenario))) {
					creations.deleteScenario(st.scenario);
				}
				for (String role : new String[] {st.role, st.roleAfter}) {
					if (role != null && creations.roles().stream().anyMatch(d -> d.id.equals(role))) {
						creations.deleteRole(role);
					}
				}
			});
			TestKit.waitFor(ctx, world, 600, 5, s -> (st.essai == null || ValcombeMod.worlds().find(st.essai) == null)
				&& (st.mapId == null || mw().atelierOf(st.mapId) == null));
			server.runOnServer(s -> {
				if (st.mapId != null && ValcombeMod.maps().exists(st.mapId) && mw().atelierOf(st.mapId) == null) {
					ValcombeMod.maps().delete(st.mapId);
				}
				// Les archives du test (la sauvegarde et celle d'avant la restauration) et le compte rendu : backups/ reste propre.
				try {
					ValcombeMod.backups().cancelRestore(); // une demande restée après un échec ne doit gêner aucun autre test
				} catch (IOException e) {
					System.out.println("[valcombe-test] recette : demande de restauration restée (" + e.getMessage() + ")");
				}
				var store = ValcombeMod.backups().store();
				for (String name : new String[] {st.backup, st.safety}) {
					if (name != null) {
						try {
							store.delete(name);
						} catch (IOException e) {
							System.out.println("[valcombe-test] recette : sauvegarde restée : " + name + " (" + e.getMessage() + ")");
						}
					}
				}
				try {
					Files.deleteIfExists(store.dir().resolve(Restorer.REPORT));
				} catch (IOException e) {
					System.out.println("[valcombe-test] recette : compte rendu de restauration resté (" + e.getMessage() + ")");
				}
				// La partie principale retrouve un village généré, comme au premier démarrage.
				Village v = Games.main().host().village();
				if (st.mapId != null && v != null && st.mapId.equals(v.libraryId())) {
					runIn(s, st.mainId, "vc carte construire");
				}
			});
			TestKit.waitFor(ctx, world, 3000, 5, s -> !Games.main().host().busy());
			ctx.waitTicks(20);
		} catch (RuntimeException e) {
			System.out.println("[valcombe-test] recette : ménage incomplet (" + e + ")");
		}
	}
}
