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

	/**
	 * Une commande de la console, lancée depuis le monde de la partie : une commande de la console part du monde
	 * principal, et un « tp » ou un « setblock » y viserait le monde principal au lieu du village de la partie.
	 */
	private static void inGame(TestSingleplayerContext world, String command) {
		String dim = world.getServer().computeOnServer(s -> fr.valcombe.mc.games.Games.main().host().level().dimension().identifier().toString());
		world.getServer().runCommand("execute in " + dim + " run " + command);
	}

	private static void send(ClientGameTestContext ctx, String cmd) {
		ctx.runOnClient(mc -> ClientState.send(cmd));
		ctx.waitTicks(10);
	}

	/** Attend (au plus {@code ticks}) qu'une condition soit vraie sur le client ; renvoie si elle l'est devenue. */
	private static boolean waitClient(ClientGameTestContext ctx, int ticks,
			java.util.function.Predicate<net.minecraft.client.Minecraft> cond) {
		for (int i = 0; i < ticks; i += 2) {
			if (ctx.computeOnClient(cond::test)) {
				return true;
			}
			ctx.waitTicks(2);
		}
		return ctx.computeOnClient(cond::test);
	}

	/** Attend la fin d'un chantier de carte : les blocs sont posés par petits lots, sur plusieurs ticks. */
	private static void waitBuild(ClientGameTestContext ctx, TestSingleplayerContext world) {
		for (int i = 0; i < 240 && world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().busy()); i++) {
			ctx.waitTicks(10);
		}
		check(!world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().busy()), "chantier terminé");
		ctx.waitTicks(20);
	}

	/**
	 * « Envoyer à la partie » sort les joueurs du monde remis à neuf (au hub) : on revient sur la place du village de
	 * la partie, comme par le lien « [Y aller] » du message de fin.
	 */
	private static void backToVillage(ClientGameTestContext ctx, TestSingleplayerContext world, String me) {
		var c = world.getServer().computeOnServer(server -> {
			var v = fr.valcombe.mc.games.Games.main().host().village();
			return v == null ? new net.minecraft.core.BlockPos(0, fr.valcombe.mc.worlds.WorldManager.SURFACE_Y, 0) : v.center();
		});
		inGame(world, String.format(java.util.Locale.ROOT, "tp %s %d %d %d", me, c.getX(), c.getY() + 1, c.getZ() + 4));
		ctx.waitTicks(20);
	}

	/** Une opération de l'éditeur de carte, puis l'attente de la fin de sa pose ; renvoie ce que l'éditeur a répondu. */
	private static String edit(ClientGameTestContext ctx, TestSingleplayerContext world, String me,
			java.util.function.BiFunction<fr.valcombe.mc.MapEditor, net.minecraft.server.level.ServerPlayer, String> op) {
		String msg = world.getServer().computeOnServer(server -> op.apply(fr.valcombe.mc.ValcombeMod.editor(),
			server.getPlayerList().getPlayerByName(me)));
		for (int i = 0; i < 600 && world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().busy()); i++) {
			ctx.waitTicks(2);
		}
		check(!world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().busy()), "opération de l'éditeur terminée : " + msg);
		System.out.println("[valcombe-test] éditeur : " + msg);
		return msg;
	}

	/** Combien de blocs de cette sorte dans le pavé (coins compris). */
	private static int count(TestSingleplayerContext world, net.minecraft.core.BlockPos a, net.minecraft.core.BlockPos b,
			net.minecraft.world.level.block.Block block) {
		return world.getServer().computeOnServer(server -> {
			int n = 0;
			for (var pos : net.minecraft.core.BlockPos.betweenClosed(a, b)) {
				if (fr.valcombe.mc.games.Games.main().host().level().getBlockState(pos).is(block)) {
					n++;
				}
			}
			return n;
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

	/** Les libellés des boutons de l'écran ouvert, pour dire ce qui manque quand un clic échoue. */
	private static String buttons(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			StringBuilder out = new StringBuilder();
			if (mc.gui.screen() != null) {
				for (var w : mc.gui.screen().children()) {
					if (w instanceof fr.valcombe.client.ui.WaxButton b) {
						out.append(b.active ? "[" : "(").append(b.getMessage().getString()).append(b.active ? "] " : ") ");
					}
				}
			}
			return out.toString();
		});
	}

	private static void press(ClientGameTestContext ctx, String label) {
		check(click(ctx, label), "bouton « " + label + " » parmi : " + buttons(ctx));
	}

	/** Ouvre la fiche de cette carte par sa case dans la bibliothèque de l'Atelier (Atelier › Cartes). */
	private static void openCard(ClientGameTestContext ctx, String name) {
		if (!ctx.computeOnClient(mc -> mc.gui.screen() instanceof fr.valcombe.client.screen.atelier.MapsPage)) {
			ctx.setScreen(() -> new fr.valcombe.client.screen.atelier.MapsPage(0));
			ctx.waitTicks(5);
		}
		boolean ok = ctx.computeOnClient(mc -> {
			fr.valcombe.client.screen.atelier.MapsPage.resetFilters();
			var p = (fr.valcombe.client.screen.atelier.MapsPage) mc.gui.screen();
			p.rebuildNow();
			return p.grid() != null && p.grid().open(name);
		});
		check(ok, "case « " + name + " » dans la bibliothèque");
		ctx.waitTicks(10);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof fr.valcombe.client.screen.atelier.MapSheetPage), "fiche de « " + name + " »");
	}

	/** Choisit l'onglet qui porte ce libellé dans l'écran ouvert. */
	private static void chooseTab(ClientGameTestContext ctx, String label) {
		boolean ok = ctx.computeOnClient(mc -> {
			for (var w : mc.gui.screen().children()) {
				if (w instanceof fr.valcombe.client.hd.HdTabs t && t.labels().contains(label)) {
					t.choose(t.labels().indexOf(label));
					return true;
				}
			}
			return false;
		});
		check(ok, "onglet « " + label + " »");
		ctx.waitTicks(12);
	}

	/** La réponse du serveur que l'écran de l'Atelier affiche sous ses boutons. */
	private static String reply(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			var r = ClientState.obj(ClientState.obj(ClientState.view(), "atelier"), "reponse");
			return r == null ? "" : (ClientState.bool(r, "erreur") ? "ERREUR " : "") + ClientState.str(r, "texte");
		});
	}

	/**
	 * L'Atelier en jeu, par ses vrais boutons : accueil, bibliothèque en vignettes, fiche d'une carte (copie, mise à la
	 * corbeille confirmée), village généré envoyé à la partie par « Nouvelle carte », carte renvoyée depuis sa fiche ; les
	 * repères depuis la fiche de la partie (« Retoucher sur place »), le catalogue de structures en vignettes (fiche, 3D,
	 * prise en main, barre de la structure en main), le volet Construire hors des ateliers, un scénario lié à une carte :
	 * partie d'essai, puis choisi pour la partie principale et supprimé.
	 * Au départ et à la fin, la carte « village-test » est en place.
	 */
	private void checkAtelierScreen(ClientGameTestContext ctx, TestSingleplayerContext world, String me) {
		var server = world.getServer();
		java.util.function.Supplier<fr.valcombe.mc.Village> village = () -> server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().host().village());
		// La commande ouvre l'accueil de l'Atelier ; le menu principal d'un opérateur a son bouton « Atelier ».
		send(ctx, "vc atelier jeu");
		ctx.waitTicks(10);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof fr.valcombe.client.screen.atelier.AtelierHome),
			"/vc atelier jeu ouvre l'accueil de l'Atelier");
		ctx.setScreen(MainScreen::new);
		shot(ctx, "28-menu-avec-atelier");
		press(ctx, "Atelier");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof fr.valcombe.client.screen.atelier.AtelierHome), "bouton Atelier du menu");

		// Cartes : la bibliothèque en vignettes ; celle de « village-test » arrive du serveur.
		press(ctx, "Cartes");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof fr.valcombe.client.screen.atelier.MapsPage), "entrée Cartes");
		check(waitClient(ctx, 200, mc -> {
			var m = fr.valcombe.client.screen.atelier.MapsPage.library().stream()
				.filter(o -> "village-test".equals(ClientState.str(o, "id"))).findFirst().orElse(null);
			return m != null && fr.valcombe.client.Vignettes.get("carte:village-test", ClientState.str(m, "vignette")) != null
				&& fr.valcombe.client.Vignettes.ready("carte:village-test", ClientState.str(m, "vignette"));
		}), "vignette de « village-test » reçue");
		shot(ctx, "28a-atelier-cartes");
		// Sa fiche : copie sous un identifiant libre, puis la copie mise à la corbeille après confirmation.
		openCard(ctx, "Village de test");
		shot(ctx, "28b-atelier-carte-de-la-bibliotheque");
		press(ctx, "Dupliquer");
		check(server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.maps().exists("village-test-copie")), "carte dupliquée : " + reply(ctx));
		check(reply(ctx).contains("Village de test (copie)"), "réponse du serveur montrée : " + reply(ctx));
		check(waitClient(ctx, 100, mc -> fr.valcombe.client.screen.atelier.MapsPage.library().stream()
			.anyMatch(o -> "village-test-copie".equals(ClientState.str(o, "id")))), "la copie paraît dans la bibliothèque");
		openCard(ctx, "Village de test (copie)");
		press(ctx, "Supprimer");
		check(server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.maps().exists("village-test-copie")), "un seul clic ne supprime rien");
		shot(ctx, "28c-atelier-suppression-a-confirmer");
		press(ctx, "Mettre à la corbeille");
		check(!server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.maps().exists("village-test-copie")), "carte mise à la corbeille");
		check(server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.maps().trashed().stream().anyMatch(e -> e.mapId().equals("village-test-copie"))),
			"la copie est à la corbeille");

		// Village : quinze trônes, envoyé à la partie par le parcours « Nouvelle carte » ; puis la carte de la bibliothèque
		// est renvoyée à la partie depuis sa fiche.
		ctx.setScreen(fr.valcombe.client.screen.atelier.NewMapPage::start);
		press(ctx, "Village généré");
		press(ctx, "Suivant");
		press(ctx, "−");
		shot(ctx, "28d-atelier-village");
		press(ctx, "Suivant");
		chooseTab(ctx, "Dans une partie");
		check(!server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().host().busy()), "rien n'est lancé avant le dernier bouton");
		press(ctx, "Générer dans la partie");
		// Le monde remis à neuf et un village de quinze trônes posé peuvent aller plus vite que la réponse affichée :
		// l'envoi est en cours, ou déjà fini, mais il a bien été lancé.
		check(server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().host().busy()) || reply(ctx).contains("lancé"),
			"village en chantier : " + reply(ctx));
		ctx.waitTicks(10);
		shot(ctx, "28e-atelier-chantier-en-cours");
		waitBuild(ctx, world);
		check(village.get().seatCount() == 15 && village.get().libraryId().isEmpty(), "village de quinze trônes généré");
		ctx.setScreen(() -> new fr.valcombe.client.screen.atelier.MapSheetPage("village-test"));
		ctx.waitTicks(10);
		press(ctx, "Envoyer à la partie…");
		check(!server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().host().busy()), "envoyer demande une confirmation");
		press(ctx, "Confirmer l'envoi");
		waitBuild(ctx, world);
		check(village.get().seatCount() == 16 && village.get().libraryId().equals("village-test") && !village.get().modified(),
			"carte envoyée à la partie depuis l'écran");
		backToVillage(ctx, world, me);

		// Repères, depuis la fiche de la partie principale (Atelier › Parties › Retoucher sur place) : liste de contrôle,
		// mode construction, un trône posé puis le geste annulé.
		String mainId = server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().id());
		ctx.setScreen(() -> fr.valcombe.client.screen.atelier.PartySheetPage.visit(mainId));
		ctx.waitTicks(20);
		shot(ctx, "28f0-atelier-fiche-partie");
		press(ctx, fr.valcombe.client.screen.atelier.PartySheetPage.TOUCH);
		shot(ctx, "28f-atelier-reperes");
		press(ctx, fr.valcombe.client.screen.atelier.PartySheetPage.BUILD_ON);
		check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "l'écran se ferme en entrant en mode construction");
		check(server.computeOnServer(s -> (Boolean) fr.valcombe.mc.ValcombeMod.editor().state(me).get("construction")), "mode construction");
		ctx.setScreen(() -> new fr.valcombe.client.screen.atelier.PartySheetPage(mainId));
		ctx.waitTicks(5);
		press(ctx, "Trône ici");
		check(village.get().seatCount() == 17, "trône posé depuis l'écran : " + reply(ctx));
		press(ctx, "Renuméroter");
		press(ctx, "Annuler le geste");
		press(ctx, "Annuler le geste");
		check(village.get().seatCount() == 16, "les deux derniers gestes annulés : " + reply(ctx));
		shot(ctx, "28g-atelier-reperes-en-construction");

		// Structures : Atelier › Structures, la maison gothique en vignette vue de trois quarts ; sa fiche, « Voir en 3D »,
		// puis prise en main.
		ctx.runOnClient(mc -> fr.valcombe.client.screen.atelier.StructuresPage.resetFilters());
		ctx.setScreen(fr.valcombe.client.screen.atelier.StructuresPage::new);
		check(waitClient(ctx, 300, mc -> fr.valcombe.client.screen.atelier.StructuresPage.catalog().stream()
			.filter(o -> "maison-gothique".equals(ClientState.str(o, "id")))
			.anyMatch(o -> fr.valcombe.client.Vignettes.get("structure:maison-gothique", ClientState.str(o, "vignette")) != null
				&& fr.valcombe.client.Vignettes.ready("structure:maison-gothique", ClientState.str(o, "vignette")))),
			"vignette de trois quarts de la maison gothique reçue");
		shot(ctx, "28h-atelier-structures");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof fr.valcombe.client.screen.atelier.StructuresPage p && p.grid() != null
			&& p.grid().open("Maison gothique")), "case « Maison gothique » du catalogue");
		ctx.waitTicks(10);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof fr.valcombe.client.screen.atelier.StructureSheetPage), "fiche de la structure");
		shot(ctx, "28h1-atelier-fiche-structure");
		press(ctx, fr.valcombe.client.screen.atelier.StructureSheetPage.VIEW_3D);
		check(waitClient(ctx, 100, mc -> fr.valcombe.client.Maquette.get("maison-gothique") != null
			&& !fr.valcombe.client.Maquette.get("maison-gothique").cells.isEmpty()), "maquette 3D de la structure reçue");
		shot(ctx, "28h2-structure-en-3d");
		press(ctx, "Retour");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof fr.valcombe.client.screen.atelier.StructureSheetPage),
			"« Retour » ramène à la fiche de la structure");
		press(ctx, fr.valcombe.client.screen.atelier.StructureSheetPage.TAKE);
		check("maison-gothique".equals(server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.editor().state(me).get("structure"))),
			"structure prise en main");
		// Le fantôme de la structure, là où l'on vise (le sol, devant soi)
		ctx.runOnClient(mc -> mc.player.setXRot(22));
		ctx.waitTicks(30);
		check(ctx.computeOnClient(mc -> fr.valcombe.client.Ghost.active()), "fantôme de la structure en main, là où l'on vise");
		shot(ctx, "28h3-fantome-de-la-structure");
		// La barre de la structure en main, en bas du catalogue.
		ctx.setScreen(() -> new fr.valcombe.client.screen.atelier.StructuresPage(0));
		ctx.waitTicks(5);
		press(ctx, "Tourner");
		shot(ctx, "28i-atelier-structure-en-main");
		// Posée à ses pieds, puis reprise avec la main : tournée, puis retirée (le terrain et le repère reviennent).
		int housesBefore = village.get().houseCount();
		press(ctx, "À mes pieds");
		ctx.waitTicks(80);
		check(village.get().houseCount() == housesBefore + 1, "maison posée avec son repère : " + reply(ctx));
		check(server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().host().objectAction(s.getPlayerList().getPlayerByName(me),
			"structure", 0, "tourner")).contains("Maison gothique"), "structure posée tournée avec la main");
		ctx.waitTicks(80);
		ctx.setScreen(() -> new fr.valcombe.client.screen.ObjectScreen("structure", 0, "Maison gothique"));
		shot(ctx, "28i2-menu-de-la-main");
		ctx.setScreen(() -> null);
		server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().host().objectAction(s.getPlayerList().getPlayerByName(me),
			"structure", 0, "retirer"));
		ctx.waitTicks(80);
		check(village.get().houseCount() == housesBefore, "structure retirée avec son repère");
		// Un trône tourné avec la main
		server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().host().objectAction(s.getPlayerList().getPlayerByName(me),
			"trone", 0, "droite"));
		check(village.get().seatTurned(0), "trône tourné d'un huitième de tour");
		server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().host().objectAction(s.getPlayerList().getPlayerByName(me),
			"trone", 0, "centre"));
		check(!village.get().seatTurned(0), "trône de nouveau tourné vers le centre");
		ctx.setScreen(() -> new fr.valcombe.client.screen.atelier.StructuresPage(0));
		ctx.waitTicks(5);
		press(ctx, "Ranger");
		check(server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.editor().state(me).get("structure")) == null, "structure rangée");
		chooseTab(ctx, "À vous");
		shot(ctx, "28j-atelier-structures-de-l-utilisateur");

		// Construire : hors des ateliers, le volet montre le chemin vers eux (la sélection, c'est WorldEdit, dans les ateliers).
		ctx.setScreen(() -> new fr.valcombe.client.screen.atelier.StructuresPage(1));
		ctx.waitTicks(5);
		check(ctx.computeOnClient(mc -> mc.gui.screen().children().stream().anyMatch(w -> w instanceof fr.valcombe.client.hd.HdButton b
			&& b.getMessage().getString().equals("Aller à l'atelier structures"))), "hors des ateliers : le chemin vers eux");
		shot(ctx, "28k-atelier-worldedit-hors-atelier");
		ctx.setScreen(() -> null);
		send(ctx, "vc carte construction"); // sortie du mode construction
		check(village.get().modified(), "carte tenue pour retouchée après le mode construction");
		// Un refus arrive à l'écran comme un refus (affiché en rouge) : hors des ateliers, pas de sélection vers structure.
		send(ctx, "vc structures depuis-selection essai-ecran Essai");
		ctx.waitTicks(12);
		check(reply(ctx).startsWith("ERREUR ") && reply(ctx).contains("ateliers"), "refus signalé à l'écran : " + reply(ctx));

		// Créations : un scénario lié à la carte « village-test ». Elle a été retouchée : le choisir la recharge, ce
		// qui se confirme ; la partie d'essai attend la fin du chantier, puis démarre d'elle-même.
		server.runOnServer(s -> {
			var d = new fr.valcombe.session.ScenarioDef();
			d.id = "essai-carte";
			d.nom = "Essai sur le village";
			d.carte = "village-test";
			for (var r : fr.valcombe.rules.Role.classic()) {
				d.roles.add(r.name().toLowerCase());
			}
			fr.valcombe.mc.ValcombeMod.creations().saveScenario(d);
		});
		boolean inLobby = server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().session().lobby().contains(me));
		long botsBefore = server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().session().lobby().stream().filter(n -> n.startsWith("Bot")).count());
		ctx.setScreen(() -> new fr.valcombe.client.screen.atelier.RolesPage(0));
		check(waitClient(ctx, 100, mc -> fr.valcombe.client.screen.atelier.RolesPage.scenarios().stream()
			.anyMatch(o -> "essai-carte".equals(ClientState.str(o, "id")))), "le scénario paraît dans l'Atelier");
		ctx.setScreen(() -> new fr.valcombe.client.screen.atelier.RolesPage(0));
		ctx.waitTicks(5);
		shot(ctx, "28l0-atelier-scenarios");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof fr.valcombe.client.screen.atelier.RolesPage p && p.grid() != null
			&& p.grid().open("Essai sur le village")), "case du scénario");
		ctx.waitTicks(10);
		shot(ctx, "28l-atelier-creations");
		press(ctx, fr.valcombe.client.screen.atelier.ScenarioSheetPage.TRIAL);
		check(server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().session().scenario()) == null, "l'essai se confirme");
		shot(ctx, "28l2-atelier-essai-a-confirmer");
		press(ctx, fr.valcombe.client.screen.atelier.ScenarioSheetPage.TRIAL_CONFIRM);
		String waiting = server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().session().trialPending() + " " + fr.valcombe.mc.games.Games.main().host().busy()
			+ " " + (fr.valcombe.mc.games.Games.main().session().game() == null));
		// Le chantier (monde remis à neuf, carte posée) peut déjà être fini quand on regarde : la partie d'essai attend
		// encore, elle n'a pas démarré, et l'envoi a bien été lancé.
		check(waiting.startsWith("true ") && waiting.endsWith(" true") && (waiting.equals("true true true") || reply(ctx).contains("lancé")),
			"partie d'essai en attente du chantier (" + waiting + ") : " + reply(ctx));
		shot(ctx, "28m-atelier-essai-en-attente");
		ctx.setScreen(() -> null);
		for (int i = 0; i < 400 && server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().session().game() == null); i++) {
			ctx.waitTicks(10);
		}
		String trial = server.computeOnServer(s -> {
			var ses = fr.valcombe.mc.games.Games.main().session();
			var v = fr.valcombe.mc.games.Games.main().host().village();
			long backups = fr.valcombe.mc.ValcombeMod.maps().list().stream().filter(d -> d.id.startsWith("sauvegarde-")).count();
			return (ses.game() == null ? "pas de partie" : ses.game().size() + " joueurs") + " | carte " + v.libraryId()
				+ " | scénario " + (ses.scenario() == null ? "aucun" : ses.scenario().id) + " | sauvegardes " + (backups > 0);
		});
		System.out.println("[valcombe-test] partie d'essai : " + trial);
		check(trial.equals("8 joueurs | carte village-test | scénario essai-carte | sauvegardes true"), "partie d'essai lancée après le chantier : " + trial);
		ctx.waitTicks(40);
		server.runCommand("vc arreter");
		waitBuild(ctx, world);
		// Même scénario, la carte intacte en place : plus rien à confirmer ni à recharger.
		ctx.setScreen(() -> fr.valcombe.client.screen.atelier.ScenarioSheetPage.visit("essai-carte"));
		ctx.waitTicks(30);
		press(ctx, fr.valcombe.client.screen.atelier.ScenarioSheetPage.CHOOSE);
		ctx.runOnClient(mc -> {
			for (var w : mc.gui.screen().children()) {
				if (w instanceof fr.valcombe.client.hd.HdList<?> l) {
					for (int i = 0; i < l.items().size(); i++) {
						if (l.items().get(i) instanceof com.google.gson.JsonObject o && mainId.equals(ClientState.str(o, "id"))) {
							l.choose(i);
						}
					}
				}
			}
		});
		ctx.waitTicks(5);
		shot(ctx, "28n-atelier-scenario-pour-une-partie");
		press(ctx, fr.valcombe.client.screen.atelier.ScenarioSheetPage.CHOOSE_CONFIRM);
		check(!server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().host().busy()), "carte déjà en place : pas de rechargement");
		check(server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().session().scenario() != null
			&& fr.valcombe.mc.games.Games.main().session().scenario().id.equals("essai-carte")), "scénario choisi pour la partie : " + reply(ctx));
		press(ctx, fr.valcombe.client.screen.atelier.ScenarioSheetPage.DELETE);
		press(ctx, fr.valcombe.client.screen.atelier.ScenarioSheetPage.DELETE_CONFIRM);
		check(server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().session().scenario() == null
			&& fr.valcombe.mc.games.Games.main().session().scenarios().stream().noneMatch(d -> d.id.equals("essai-carte"))),
			"scénario supprimé, et plus choisi : " + reply(ctx));
		ctx.setScreen(() -> null);
		// Le salon, le script et la bibliothèque retrouvent leur état d'avant.
		server.runCommand("vc bots retirer");
		if (botsBefore > 0) {
			server.runCommand("vc bots " + botsBefore);
		}
		server.runCommand("vc script complet");
		if (!inLobby) {
			send(ctx, "vc quitter");
		}
		server.runOnServer(s -> {
			var maps = fr.valcombe.mc.ValcombeMod.maps();
			for (var d : maps.list()) {
				if (d.id.startsWith("sauvegarde-")) {
					maps.delete(d.id);
				}
			}
		});
	}

	/**
	 * Éditeur du mode construction : le catalogue, une maison posée tournée (avec son repère), l'aperçu, deux structures
	 * posées puis défaites par l'« annuler » commun, les fichiers importés comme structures. Sélectionner, remplir,
	 * copier, coller : c'est WorldEdit, dans les ateliers (WorldEditGameTest, BuildWorkshopGameTest).
	 */
	private void checkEditor(ClientGameTestContext ctx, TestSingleplayerContext world, String me,
			net.minecraft.core.BlockPos port, net.minecraft.core.BlockPos spot) {
		var feet = spot.above();
		String catalog = world.getServer().computeOnServer(server -> {
			var list = fr.valcombe.mc.ValcombeMod.structures().list();
			return list.size() + " structures, " + list.stream().filter(d -> d.repere.equals("maison")).count() + " maisons, "
				+ list.stream().filter(d -> !d.fournie).count() + " de l'utilisateur";
		});
		System.out.println("[valcombe-test] catalogue : " + catalog);
		check(world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.structures().list().stream()
			.filter(d -> d.fournie && d.blocs > 0 && d.taille[0] > 0).count()) >= 12, "catalogue : douze structures fournies au moins");

		// Une maison du catalogue, tournée d'un quart de tour : sa porte, au nord dans le catalogue, regarde l'est.
		String placed = edit(ctx, world, me, (ed, p) -> {
			ed.choose(p, "maison-gothique");
			ed.rotate(p);
			return ed.place(p, spot);
		});
		String house = world.getServer().computeOnServer(server -> {
			var level = fr.valcombe.mc.games.Games.main().host().level();
			var door = level.getBlockState(feet.offset(4, 0, 0));
			boolean east = door.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
				&& door.getValue(net.minecraft.world.level.block.DoorBlock.FACING) == net.minecraft.core.Direction.EAST;
			int panes = 0;
			int joined = 0;
			for (var pos : net.minecraft.core.BlockPos.betweenClosed(feet.offset(-4, 0, -4), feet.offset(4, 3, 4))) {
				var s = level.getBlockState(pos);
				if (s.is(net.minecraft.world.level.block.Blocks.GLASS_PANE)) {
					panes++;
					if (s.getValue(net.minecraft.world.level.block.IronBarsBlock.NORTH) || s.getValue(net.minecraft.world.level.block.IronBarsBlock.SOUTH)
						|| s.getValue(net.minecraft.world.level.block.IronBarsBlock.EAST) || s.getValue(net.minecraft.world.level.block.IronBarsBlock.WEST)) {
						joined++;
					}
				}
			}
			return "porte à l'est " + east + " | toit " + !level.getBlockState(spot.above(5)).isAir() + " | vitres raccordées "
				+ (panes > 0 && joined == panes) + " | maisons " + fr.valcombe.mc.games.Games.main().host().village().houseCount();
		});
		System.out.println("[valcombe-test] structure posée : " + placed + " → " + house);
		check(house.equals("porte à l'est true | toit true | vitres raccordées true | maisons 17"), "maison du catalogue posée tournée : " + house);
		inGame(world, String.format(java.util.Locale.ROOT, "tp %s %d %d %d facing %d %d %d", me,
			port.getX() + 16, port.getY() + 5, port.getZ() + 14, spot.getX(), spot.getY() + 3, spot.getZ()));
		ctx.waitTicks(40);
		shot(ctx, "27-structure-du-catalogue");

		// Annuler retire la maison et son repère ; rétablir les remet.
		edit(ctx, world, me, (ed, p) -> ed.undo(p, 1));
		check(world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().level().getBlockState(spot.above(5)).isAir()
			&& fr.valcombe.mc.games.Games.main().host().village().houseCount() == 16), "pose annulée : maison et repère retirés");
		edit(ctx, world, me, (ed, p) -> ed.redo(p, 1));
		check(world.getServer().computeOnServer(server -> !fr.valcombe.mc.games.Games.main().host().level().getBlockState(spot.above(5)).isAir()
			&& fr.valcombe.mc.games.Games.main().host().village().houseCount() == 17), "pose rétablie");
		edit(ctx, world, me, (ed, p) -> ed.undo(p, 1));

		// Aperçu : le contour suit le bloc visé, l'outil « structure » en main.
		world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.editor().choose(server.getPlayerList().getPlayerByName(me), "puits"));
		inGame(world, String.format(java.util.Locale.ROOT, "tp %s %d %d %d facing %d %d %d", me,
			port.getX() + 5, port.getY() + 4, port.getZ() + 14, spot.getX(), spot.getY(), spot.getZ()));
		ctx.waitTicks(40);
		shot(ctx, "27b-structure-apercu");

		// Deux structures posées en l'air (un puits, un banc retourné) : elles entrent dans l'« annuler » commun.
		var a = spot.above(14);
		var low = a.offset(-9, -2, -9);
		var high = a.offset(20, 18, 9);
		int before = nonAir(world, low, high);
		edit(ctx, world, me, (ed, p) -> {
			ed.choose(p, "puits");
			return ed.place(p, a);
		});
		edit(ctx, world, me, (ed, p) -> {
			ed.choose(p, "banc");
			ed.mirror(p);
			return ed.place(p, a.offset(10, 0, 0));
		});
		int placedBlocks = nonAir(world, low, high) - before;
		check(placedBlocks > 0, "puits et banc posés en l'air : " + placedBlocks + " blocs");

		// Fichiers importés comme structures : une schématique passe, un fichier piégé est refusé, la carte n'est pas touchée.
		String files = world.getServer().computeOnServer(server -> {
			var store = fr.valcombe.mc.ValcombeMod.structures();
			var village = fr.valcombe.mc.games.Games.main().host().village();
			try {
				var schem = new net.minecraft.nbt.CompoundTag();
				schem.putShort("Width", (short) 2);
				schem.putShort("Height", (short) 1);
				schem.putShort("Length", (short) 2);
				var pal = new net.minecraft.nbt.CompoundTag();
				pal.putInt("minecraft:oak_planks", 0);
				schem.put("Palette", pal);
				schem.putByteArray("BlockData", new byte[4]);
				var out = new java.io.ByteArrayOutputStream();
				net.minecraft.nbt.NbtIo.writeCompressed(schem, out);
				String ok = store.importFile("Petite Dalle.schem", out.toByteArray(), "", "");
				var def = store.def("petite-dalle");
				// Structure qui annonce 1 × 1 × 1 et déclare un bloc à cent mille cases de là.
				var trap = new net.minecraft.nbt.CompoundTag();
				var size = new net.minecraft.nbt.ListTag();
				var far = new net.minecraft.nbt.ListTag();
				for (int i = 0; i < 3; i++) {
					size.add(net.minecraft.nbt.IntTag.valueOf(1));
					far.add(net.minecraft.nbt.IntTag.valueOf(i == 0 ? 100000 : 0));
				}
				trap.put("size", size);
				var palette = new net.minecraft.nbt.ListTag();
				var tnt = new net.minecraft.nbt.CompoundTag();
				tnt.putString("Name", "minecraft:tnt");
				palette.add(tnt);
				trap.put("palette", palette);
				var list = new net.minecraft.nbt.ListTag();
				var block = new net.minecraft.nbt.CompoundTag();
				block.put("pos", far);
				block.putInt("state", 0);
				list.add(block);
				trap.put("blocks", list);
				var out2 = new java.io.ByteArrayOutputStream();
				net.minecraft.nbt.NbtIo.writeCompressed(trap, out2);
				String refused;
				try {
					refused = "accepté : " + store.importFile("piege.nbt", out2.toByteArray(), "", "");
				} catch (fr.valcombe.rules.GameException e) {
					refused = e.getMessage();
				}
				String result = ok + " | " + def.taille[0] + "×" + def.taille[1] + "×" + def.taille[2] + " | " + refused + " | carte intacte "
					+ (fr.valcombe.mc.games.Games.main().host().village() == village && !fr.valcombe.mc.games.Games.main().host().busy());
				store.delete("petite-dalle");
				return result;
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
		System.out.println("[valcombe-test] fichiers importés comme structures : " + files);
		check(files.contains("2×1×2") && files.contains("hors de ses dimensions") && files.endsWith("carte intacte true"),
			"import de fichiers comme structures : " + files);

		// Tout annuler d'un coup, par l'« annuler » commun : il ne reste rien de ce que l'éditeur a posé.
		String journal = world.getServer().computeOnServer(server -> fr.valcombe.mc.WorldEditGate.current().undo().order().toString());
		check(journal.endsWith("STRUCTURES, STRUCTURES]"), "les deux poses sont les derniers gestes du journal : " + journal);
		edit(ctx, world, me, (ed, p) -> fr.valcombe.mc.WorldEditGate.current().undo().undo(p, 2));
		String undone = world.getServer().computeOnServer(server -> {
			var p = server.getPlayerList().getPlayerByName(me);
			var shared = fr.valcombe.mc.WorldEditGate.current().undo();
			return "à rétablir " + shared.redoable(p) + " | maisons " + fr.valcombe.mc.games.Games.main().host().village().houseCount();
		});
		int leftBlocks = nonAir(world, low, high) - before;
		System.out.println("[valcombe-test] annuler commun : " + undone + " | blocs restants " + leftBlocks);
		check(leftBlocks == 0 && undone.equals("à rétablir 2 | maisons 16"), "tout annuler : " + undone + ", blocs restants " + leftBlocks);
		edit(ctx, world, me, (ed, p) -> fr.valcombe.mc.WorldEditGate.current().undo().redo(p, 1));
		check(nonAir(world, low, high) - before > 0, "/vc retablir repose la dernière structure annulée");
		edit(ctx, world, me, (ed, p) -> fr.valcombe.mc.WorldEditGate.current().undo().undo(p, 1));
		check(nonAir(world, low, high) == before, "et /vc annuler la retire de nouveau");

		// Les mêmes gestes par les commandes : /vc structure et /vc annuler.
		inGame(world, String.format(java.util.Locale.ROOT, "tp %s %d %d %d", me, port.getX() + 2, port.getY() + 1, port.getZ() + 8));
		ctx.waitTicks(20);
		send(ctx, "vc structure liste");
		var foot = port.offset(2, 1, 8); // là où se tient le joueur, sur la place
		send(ctx, "vc structure poser banc ici");
		ctx.waitTicks(10);
		check(count(world, foot.offset(-2, -1, -2), foot.offset(2, 1, 2), net.minecraft.world.level.block.Blocks.SPRUCE_STAIRS) == 3, "/vc structure poser banc ici");
		send(ctx, "vc annuler");
		ctx.waitTicks(10);
		check(count(world, foot.offset(-2, -1, -2), foot.offset(2, 1, 2), net.minecraft.world.level.block.Blocks.SPRUCE_STAIRS) == 0, "banc annulé par /vc annuler");
	}

	/** Combien de blocs qui ne sont pas de l'air dans le pavé (coins compris), dans le monde de la partie. */
	private static int nonAir(TestSingleplayerContext world, net.minecraft.core.BlockPos a, net.minecraft.core.BlockPos b) {
		return world.getServer().computeOnServer(server -> {
			int n = 0;
			for (var pos : net.minecraft.core.BlockPos.betweenClosed(a, b)) {
				if (!fr.valcombe.mc.games.Games.main().host().level().getBlockState(pos).isAir()) {
					n++;
				}
			}
			return n;
		});
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

	/** Grimoire sur tablette : la page répond, refuse un mauvais jeton, et exécute une commande. */
	/** L'Atelier, par sa vraie page web : rôle et scénario créés, simulation, scénario choisi. */
	/** Lien de l'Atelier du joueur du test (chaque opérateur a son propre jeton). */
	/** Port de test à part : un serveur Valcombe déjà lancé sur ce PC garde le 25570. */
	private static void testPort() {
		if (fr.valcombe.mc.ValcombeMod.community().tabletPort() != 25591) {
			fr.valcombe.mc.ValcombeMod.community().setTabletPort(25591);
		}
	}

	private static String atelierLink(ClientGameTestContext ctx) {
		testPort();
		String me = ctx.computeOnClient(mc -> mc.player.getScoreboardName());
		try {
			return fr.valcombe.mc.ValcombeMod.tablet().openAtelier(me).get(0);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void checkAtelier(ClientGameTestContext ctx) {
		send(ctx, "vc atelier");
		ctx.waitTicks(10);
		String link = atelierLink(ctx);
		String token = link.substring(link.indexOf('#') + 1);
		String base = "http://127.0.0.1:" + fr.valcombe.mc.ValcombeMod.community().tabletPort();
		var http = java.net.http.HttpClient.newHttpClient();
		java.util.function.BiFunction<String, String, java.net.http.HttpResponse<String>> post = (path, body) -> fetch(ctx, http,
			java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/atelier/" + path))
				.header("X-Valcombe-Jeton", token).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
			java.net.http.HttpResponse.BodyHandlers.ofString());
		var page = fetch(ctx, http, java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/atelier")).build(),
			java.net.http.HttpResponse.BodyHandlers.ofString());
		check(page.statusCode() == 200 && page.body().contains("Atelier de Valcombe"), "page de l'Atelier");
		var img = fetch(ctx, http, java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/atelier/embleme/e_lune.png")).build(),
			java.net.http.HttpResponse.BodyHandlers.ofString());
		check(img.statusCode() == 200, "emblème servi");
		var role = post.apply("role", "{\"id\":\"guetteur\",\"nom\":\"Le Guetteur\",\"camp\":\"CITADIN\","
			+ "\"texte\":\"Chaque nuit, désigne deux joueurs : tu apprends combien sont mauvais.\",\"embleme\":\"e_oeil\","
			+ "\"premiereNuit\":\"jamais\",\"autresNuits\":\"apres:veilleuse\",\"pouvoir\":{\"type\":\"compte\",\"nombre\":2}}");
		check(role.statusCode() == 200, "rôle créé : " + role.body());
		var bad = post.apply("role", "{\"id\":\"tueur\",\"nom\":\"Le Tueur\",\"camp\":\"CITADIN\",\"texte\":\"x\","
			+ "\"autresNuits\":\"fin\",\"pouvoir\":{\"type\":\"mise_a_mort\"}}");
		check(bad.statusCode() == 409, "rôle fautif refusé");
		StringBuilder roles = new StringBuilder("\"guetteur\"");
		for (var r : fr.valcombe.rules.Role.classic()) {
			roles.append(",\"").append(r.name().toLowerCase()).append("\"");
		}
		var sc = post.apply("scenario", "{\"id\":\"bal-masque\",\"nom\":\"Le Bal masqué\",\"description\":\"Personne n'est "
			+ "ce qu'il prétend.\",\"ouverture\":\"Ce soir, Valcombe donne son bal d'hiver.\",\"roles\":[" + roles
			+ "],\"regles\":{\"pasAccusationPremierJour\":true}}");
		check(sc.statusCode() == 200, "scénario créé : " + sc.body());
		var sim = post.apply("simuler", "{\"roles\":[" + roles + "],\"joueurs\":8,\"parties\":50}");
		check(sim.statusCode() == 200 && sim.body().contains("pourcentBien"), "simulation : " + sim.body());
		System.out.println("[valcombe-test] simulation : " + sim.body());
		var data = fetch(ctx, http, java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/atelier/donnees"))
			.header("X-Valcombe-Jeton", token).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
		check(data.statusCode() == 200 && data.body().contains("guetteur"), "catalogue de l'Atelier");
		try {
			java.nio.file.Files.writeString(java.nio.file.Path.of("atelier-donnees.json"), data.body());
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		var exp = fetch(ctx, http, java.net.http.HttpRequest.newBuilder(java.net.URI.create(base
			+ "/api/atelier/exporter?scenarios=bal-masque")).header("X-Valcombe-Jeton", token).build(),
			java.net.http.HttpResponse.BodyHandlers.ofString());
		check(exp.statusCode() == 200 && exp.body().contains("valcombe-creations") && exp.body().contains("guetteur"), "export");
		var use = post.apply("jouer", "{\"scenario\":\"bal-masque\",\"essai\":false}");
		check(use.statusCode() == 200 && use.body().contains("« Le Bal masqué »"), "scénario choisi : " + use.body());
		check("bal-masque".equals(fr.valcombe.mc.games.Games.main().session().scenario().id), "scénario de la prochaine partie");
		// Ce que la page partage avec l'écran en jeu : carte actuelle, liste de contrôle, état de l'éditeur.
		check(data.body().contains("\"carteResume\"") && data.body().contains("\"controle\"") && data.body().contains("\"constructeur\":false")
			&& data.body().contains("\"structures\"") && data.body().contains("\"tronesMax\":20"), "catalogue partagé avec l'écran en jeu");
		// Un champ vide ou hors bornes est refusé en clair, sans rien lancer.
		var empty = post.apply("carte/generer", "{\"places\":null,\"taille\":1,\"theme\":\"port\",\"maisons\":true}");
		check(empty.statusCode() == 409 && empty.body().equals("Trônes : indiquez un nombre de 5 à 20."), "champ vide : " + empty.body());
		var many = post.apply("carte/generer", "{\"places\":30,\"taille\":1,\"theme\":\"port\",\"maisons\":true}");
		check(many.statusCode() == 409 && many.body().equals("Trônes : de 5 à 20."), "trente trônes refusés : " + many.body());
		var crowd = post.apply("jouer", "{\"scenario\":\"bal-masque\",\"essai\":true,\"joueurs\":17}");
		check(crowd.statusCode() == 409 && crowd.body().contains("de 5 à 16"), "essai à 17 joueurs refusé : " + crowd.body());
		check(!fr.valcombe.mc.games.Games.main().host().busy() && fr.valcombe.mc.games.Games.main().session().game() == null, "rien n'a été lancé");
		// Copie sans identifiant (le serveur en trouve un), puis suppression.
		var copy = post.apply("carte/dupliquer", "{\"id\":\"village-test\",\"nom\":\"Variante\"}");
		check(copy.statusCode() == 200 && fr.valcombe.mc.ValcombeMod.maps().exists("village-test-copie"), "copie depuis la page : " + copy.body());
		check(post.apply("carte/supprimer", "village-test-copie").statusCode() == 200, "copie supprimée");
		// Catalogue de structures : mode construction, structure en main, tournée, puis sortie du mode.
		var enter = post.apply("construction", "");
		check(enter.statusCode() == 200 && enter.body().startsWith("Mode construction :"), "mode construction depuis la page : " + enter.body());
		var take = post.apply("structure/prendre", "banc");
		var turn = post.apply("structure/tourner", "");
		check(take.statusCode() == 200 && turn.statusCode() == 200, "structure en main depuis la page : " + take.body() + " / " + turn.body());
		// Sur le plan : une maison posée, bâtie d'un coup, retirée avec sa construction ; puis le geste d'avant annulé.
		int housesBefore = fr.valcombe.mc.games.Games.main().host().village().houseCount();
		var add = post.apply("carte/repere", "{\"action\":\"ajouter\",\"type\":\"maison\",\"x\":6,\"z\":-52}");
		var built = post.apply("carte/batir", "{\"type\":\"maison\",\"index\":" + housesBefore + "}");
		check(add.statusCode() == 200 && built.statusCode() == 200, "maison bâtie depuis le plan : " + add.body() + " / " + built.body());
		var erase = post.apply("carte/repere", "{\"action\":\"retirer\",\"type\":\"maison\",\"index\":" + housesBefore + "}");
		check(erase.statusCode() == 200 && erase.body().contains("sa construction avec"), "retirer sur le plan retire la construction : " + erase.body());
		var move = post.apply("carte/repere", "{\"action\":\"deplacer\",\"type\":\"siege\",\"index\":0,\"x\":3,\"z\":3}");
		var seatMoved = fr.valcombe.mc.games.Games.main().host().village().relSeats().get(0);
		var undo = post.apply("carte/annuler", "");
		var seatBack = fr.valcombe.mc.games.Games.main().host().village().relSeats().get(0);
		check(move.statusCode() == 200 && undo.statusCode() == 200 && seatMoved[0] == 3 && seatMoved[2] == 3
			&& (seatBack[0] != 3 || seatBack[2] != 3) && fr.valcombe.mc.games.Games.main().host().village().houseCount() == housesBefore,
			"annuler défait le dernier geste (un déplacement), pas une pose plus ancienne : " + undo.body());
		check(post.apply("construction", "").body().startsWith("Mode construction terminé"), "sortie du mode construction");
		var journal = fetch(ctx, http, java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/atelier/donnees"))
			.header("X-Valcombe-Jeton", token).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
		check(journal.body().contains("Mode construction terminé") && journal.body().contains("déplacé"), "le journal note la carte et le mode construction");
		// Fermer la tablette du Conteur ne ferme pas l'Atelier ; /vc atelier fermer ferme le lien de cet opérateur.
		send(ctx, "vc tablette fermer");
		var still = fetch(ctx, http, java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/atelier/donnees"))
			.header("X-Valcombe-Jeton", token).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
		check(still.statusCode() == 200, "l'Atelier reste ouvert quand la tablette est fermée : " + still.statusCode());
		send(ctx, "vc atelier fermer");
		check(!fr.valcombe.mc.ValcombeMod.tablet().running(), "plus aucun lien : le serveur web s'arrête");
		System.out.println("[valcombe-test] Atelier OK");
	}

	/**
	 * Séance pilotée (développement) : le jeu reste ouvert ; les lignes ajoutées à seance-commandes.txt
	 * sont exécutées (« /… » par le joueur, « !… » par la console, « capture nom », « attendre n », « fin »).
	 * Le lien de l'Atelier est écrit dans seance-lien.txt.
	 */
	private void seance(ClientGameTestContext ctx, TestSingleplayerContext world, String me) {
		var cmds = java.nio.file.Path.of("seance-commandes.txt");
		send(ctx, "vc atelier");
		ctx.waitTicks(20);
		try {
			java.nio.file.Files.writeString(java.nio.file.Path.of("seance-lien.txt"),
				String.join("\n", fr.valcombe.mc.ValcombeMod.tablet().openAtelier(me)));
			java.nio.file.Files.writeString(cmds, "");
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		int done = 0;
		for (int t = 0; t < 20 * 60 * 40; t += 10) {
			ctx.waitTicks(10);
			java.util.List<String> lines;
			try {
				lines = java.nio.file.Files.readAllLines(cmds);
			} catch (java.io.IOException e) {
				continue;
			}
			while (done < lines.size()) {
				String line = lines.get(done++).trim();
				System.out.println("[valcombe-seance] " + line);
				if (line.equals("fin")) {
					return;
				} else if (line.startsWith("/")) {
					send(ctx, line.substring(1));
				} else if (line.startsWith("!")) {
					inGame(world, line.substring(1));
				} else if (line.startsWith("capture ")) {
					captured.remove(line.substring(8));
					shot(ctx, line.substring(8));
				} else if (line.startsWith("attendre ")) {
					ctx.waitTicks(Integer.parseInt(line.substring(9)));
				} else if (line.equals("ecran null")) {
					ctx.setScreen(() -> null);
				}
			}
		}
	}

	/** Texte de la barre d'action (message au-dessus de l'inventaire), lu par réflexion. */
	private static String overlayText(net.minecraft.client.Minecraft mc) {
		StringBuilder out = new StringBuilder();
		for (Object holder : new Object[] {mc.gui, mc.gui.hud}) {
			for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				for (var f : c.getDeclaredFields()) {
					if (net.minecraft.network.chat.Component.class.isAssignableFrom(f.getType())
						&& !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
						try {
							f.setAccessible(true);
							Object v = f.get(holder);
							if (v != null) {
								out.append(f.getName()).append('=').append(((net.minecraft.network.chat.Component) v).getString()).append(' ');
							}
						} catch (ReflectiveOperationException | RuntimeException e) {
							// champ inaccessible : ignoré
						}
					}
				}
			}
		}
		return out.toString();
	}

	private static void checkTablet(ClientGameTestContext ctx) {
		testPort();
		var tab = fr.valcombe.mc.ValcombeMod.tablet();
		String before = tab.owner() == null ? "" : tab.links().get(0);
		send(ctx, "vc tablette");
		String link = before;
		for (int i = 0; i < 100 && link.equals(before); i++) {
			ctx.waitTicks(2); // la commande passe par le serveur : on attend le nouveau jeton
			link = tab.owner() == null ? "" : tab.links().get(0);
		}
		String token = link.substring(link.indexOf('#') + 1);
		String base = "http://127.0.0.1:" + fr.valcombe.mc.ValcombeMod.community().tabletPort();
		try {
			var http = java.net.http.HttpClient.newHttpClient();
			var page = fetch(ctx, http, java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/")).build(),
				java.net.http.HttpResponse.BodyHandlers.ofString());
			check(page.statusCode() == 200 && page.body().contains("Grimoire de Valcombe"), "page de la tablette");
			var bad = fetch(ctx, http, java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/vue"))
				.header("X-Valcombe-Jeton", "faux").build(), java.net.http.HttpResponse.BodyHandlers.ofString());
			check(bad.statusCode() == 403, "jeton refusé");
			var vue = fetch(ctx, http, java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/vue"))
				.header("X-Valcombe-Jeton", token).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
			check(vue.statusCode() == 200 && vue.body().contains("\"grimoire\""), "vue du grimoire : " + vue.body());
			java.nio.file.Files.writeString(java.nio.file.Path.of("tablette-vue.json"), vue.body());
			var cmd = fetch(ctx, http, java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/commande"))
				.header("X-Valcombe-Jeton", token)
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString("vc rassembler")).build(),
				java.net.http.HttpResponse.BodyHandlers.ofString());
			check(cmd.statusCode() == 200, "commande depuis la tablette : " + cmd.statusCode() + " " + cmd.body());
			var refused = fetch(ctx, http, java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/commande"))
				.header("X-Valcombe-Jeton", token)
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString("op Bot1")).build(),
				java.net.http.HttpResponse.BodyHandlers.ofString());
			check(refused.statusCode() == 400, "commande hors /vc refusée");
			System.out.println("[valcombe-test] tablette OK : " + cmd.body().replace("\n", " "));
		} catch (java.io.IOException e) {
			throw new AssertionError("tablette injoignable", e);
		}
		send(ctx, "vc tablette fermer");
	}

	/** Requête HTTP pendant que le jeu continue de tourner (le serveur ne répond qu'entre deux ticks). */
	private static java.net.http.HttpResponse<String> fetch(ClientGameTestContext ctx, java.net.http.HttpClient http,
		java.net.http.HttpRequest req, java.net.http.HttpResponse.BodyHandler<String> body) {
		var future = http.sendAsync(req, body);
		for (int i = 0; i < 200 && !future.isDone(); i++) {
			ctx.waitTick();
		}
		return future.join();
	}

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			ctx.waitTicks(60);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			inGame(world, "op " + me);
			ctx.waitTicks(40);
			// On arrive au hub (voir HubGameTest) : ce test se passe au village de la partie, dans son monde.
			send(ctx, "vc monde aller principal");
			ctx.waitTicks(30);
			if ("1".equals(System.getenv("VALCOMBE_SEANCE"))) {
				seance(ctx, world, me);
				return;
			}
			shot(ctx, "0-salle-attente");
			ctx.runOnClient(mc -> mc.player.setYRot(0));
			ctx.waitTicks(10);
			shot(ctx, "0b-salle-dalles");

			// Salon
			send(ctx, "vc rejoindre");
			inGame(world, "vc bots 6");
			ctx.waitTicks(20);
			ctx.setScreen(MainScreen::new);
			shot(ctx, "1-salon");
			ctx.setScreen(SheetsScreen::new);
			shot(ctx, "2-fiches");
			ctx.setScreen(fr.valcombe.client.screen.NotebookScreen::new);
			shot(ctx, "2b-carnet");
			ctx.setScreen(StorytellerScreen::new);
			shot(ctx, "3-panneau-salon");
			inGame(world, "vc script basculer margrave");
			ctx.setScreen(fr.valcombe.client.screen.ScriptScreen::new);
			shot(ctx, "3b-script");
			// Groupes de rôles : ranger le script dans un groupe, le réduire au groupe, l'agrandir de nouveau.
			send(ctx, "vc script basculer goule");
			send(ctx, "vc script basculer margrave");
			send(ctx, "vc script groupe ranger Sans la Goule");
			send(ctx, "vc script complet");
			ctx.waitTicks(10);
			ctx.setScreen(fr.valcombe.client.screen.ScriptScreen::new);
			ctx.waitTicks(10);
			shot(ctx, "3b2-script-groupe");
			send(ctx, "vc script groupe seulement Sans la Goule");
			ctx.waitTicks(5);
			int inGroup = world.getServer().computeOnServer(s -> fr.valcombe.mc.ValcombeMod.creations().groups()
				.getOrDefault("Sans la Goule", java.util.List.of()).size());
			int scripted = world.getServer().computeOnServer(s -> fr.valcombe.mc.games.Games.main().session().script().size());
			check(inGroup > 10 && scripted == inGroup, "groupe rangé puis appliqué : " + inGroup + " / " + scripted);
			send(ctx, "vc script groupe supprimer Sans la Goule");
			ctx.waitTicks(5);
			check(world.getServer().computeOnServer(s -> fr.valcombe.mc.ValcombeMod.creations().groups().isEmpty()), "groupe supprimé");
			inGame(world, "vc script complet");
			ctx.setScreen(fr.valcombe.client.screen.SettingsScreen::new);
			shot(ctx, "3c-reglages");
			inGame(world, "vc figure sablier oui");
			ctx.setScreen(fr.valcombe.client.screen.FiguresScreen::new);
			shot(ctx, "3d-figures");
			inGame(world, "vc figure sablier non");

			// Partie avec Conteur automatique
			ctx.setScreen(() -> null);
			inGame(world, "vc lancer auto");
			ctx.waitTicks(20);
			ctx.setScreen(() -> null);
			ctx.runOnClient(mc -> mc.player.setXRot(10));
			ctx.waitTicks(30);
			shot(ctx, "4b-jetons-flottants");
			ctx.setScreen(MainScreen::new);
			shot(ctx, "4-partie");
			ctx.setScreen(() -> null);
			// Cycle de vie de la carte : pendant la partie, un bloc est cassé, un autre posé (sous la place, hors des
			// captures) et un objet lâché dans une maison ; tout doit être revenu comme avant après la partie.
			var scar = world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().village().center().offset(4, -2, -4));
			var scarBefore = world.getServer().computeOnServer(server -> java.util.List.of(
				fr.valcombe.mc.games.Games.main().host().level().getBlockState(scar), fr.valcombe.mc.games.Games.main().host().level().getBlockState(scar.above())));
			check(world.getServer().computeOnServer(server -> java.nio.file.Files.exists(
				fr.valcombe.mc.games.Games.main().host().snapshotFile())), "instantané de la carte pris au lancement");
			var litter = world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().village().houseFeet().get(3));
			inGame(world, String.format(java.util.Locale.ROOT, "setblock %d %d %d minecraft:air", scar.getX(), scar.getY(), scar.getZ()));
			inGame(world, String.format(java.util.Locale.ROOT, "setblock %d %d %d minecraft:gold_block",
				scar.getX(), scar.getY() + 1, scar.getZ()));
			inGame(world, String.format(java.util.Locale.ROOT,
				"summon minecraft:item %.1f %.1f %.1f {Item:{id:\"minecraft:stick\",count:1}}", litter.x, litter.y + 0.5, litter.z));
			inGame(world, "vc nuit");
			ctx.waitTicks(40);
			playNight(ctx);
			if (phase(ctx).equals("DAY")) {
				ctx.setScreen(MainScreen::new);
				shot(ctx, "5-jour");
				ctx.setScreen(fr.valcombe.client.screen.ClaimScreen::new);
				shot(ctx, "5d-annoncer-role");
				send(ctx, "vc annoncer greffier");
				ctx.setScreen(fr.valcombe.client.screen.SignScreen::new);
				shot(ctx, "5e-signes");
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
				ctx.waitTicks(10);
				send(ctx, "vc vote oui"); // Conseil de la table : refusé tant que les votes n'ont pas commencé
				ctx.waitTicks(20);
				shot(ctx, "5b-defense");
				inGame(world, "vc voter");
				// Le premier vote commence un court temps après la fin des accusations (2 × Settings.stepDelayTicks,
				// 60 ticks du serveur). On attend l'invite de vote et son écran, ouvert de lui-même : un nombre fixe de
				// ticks du client tombait tantôt juste avant (la capture ne montrait que le bandeau de jeu), tantôt après.
				check(waitClient(ctx, 200, mc -> {
					JsonObject vp = ClientState.obj(ClientState.view(), "prompt");
					return vp != null && "vote".equals(ClientState.str(vp, "type")) && mc.gui.screen() instanceof PromptScreen;
				}), "écran de vote ouvert de lui-même au début du vote");
				ctx.waitTicks(20);
				shot(ctx, "5c-vote-horloge-hud");
				ctx.waitTicks(50);
				JsonObject p = prompt(ctx);
				if (p != null) {
					answer(ctx, p);
					ctx.setScreen(fr.valcombe.client.screen.PromptScreen::new);
					ctx.waitTicks(20);
					shot(ctx, "invite-vote-main-levee");
				}
				ctx.setScreen(() -> null);
				inGame(world, "ride Player0 dismount");
				inGame(world, "gamemode spectator Player0");
				inGame(world, "execute at @e[tag=vc_hand,limit=1] run tp Player0 ~ ~9 ~19 facing entity @e[tag=vc_hand,limit=1] feet");
				ctx.waitTicks(40);
				shot(ctx, "14-horloge-vote");
				inGame(world, "gamemode adventure Player0");
				inGame(world, "vc rassembler");
				ctx.waitTicks(10);
				ctx.runOnClient(mc -> {
					mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
					mc.player.setXRot(-12);
				});
				ctx.waitTicks(10);
				shot(ctx, "14b-mains-gibet");
				ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
				send(ctx, "vc signe question");
				ctx.waitTicks(10);
				shot(ctx, "14c-assis");
				// Coiffe des morts : chapeau, puis crâne (seulement si le joueur est du bien : sa mort ne finit pas la partie)
				// Joueur du mal : il échange son rôle avec un joueur fictif du bien, pour que sa mort ne finisse pas la partie.
				String swap = world.getServer().computeOnServer(server -> {
					var g = fr.valcombe.mc.games.Games.main().session().game();
					var mine = g.player(g.seatOf(me));
					if (mine.team() == fr.valcombe.rules.Team.GOOD) {
						return "";
					}
					for (var q : g.players()) {
						if (q.alive() && q.team() == fr.valcombe.rules.Team.GOOD && !q.name.equals(me)) {
							return q.name + " " + q.role().name().toLowerCase() + " " + mine.role().name().toLowerCase();
						}
					}
					return null;
				});
				if (swap != null && !swap.isEmpty()) {
					String[] w = swap.split(" ");
					inGame(world, "vc changer " + me + " " + w[1]);
					inGame(world, "vc changer " + w[0] + " " + w[2]);
					ctx.waitTicks(5);
				}
				if (swap != null) {
					inGame(world, "vc tuer " + me);
					ctx.runOnClient(mc -> {
						if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
						mc.player.setXRot(10);
					});
					ctx.waitTicks(30);
					shot(ctx, "17-coiffe-chapeau");
					inGame(world, "vc reglage coiffe crane");
					ctx.waitTicks(15);
					shot(ctx, "17b-coiffe-crane");
					ctx.runOnClient(mc -> { if (mc.gui.hud.isHidden()) mc.gui.hud.toggle(); });
					inGame(world, "vc reglage coiffe chapeau");
					inGame(world, "vc ressusciter " + me);
					ctx.waitTicks(10);
				}
				ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
				ctx.waitTicks(500);
			}
			// Fin de partie : le Conteur tue les joueurs fictifs jusqu'à la victoire d'un camp
			ctx.setScreen(() -> null);
			for (int b = 1; b <= 6; b++) {
				inGame(world, "vc tuer Bot" + b);
				ctx.waitTicks(5);
			}
			ctx.waitTicks(100);
			ctx.runOnClient(mc -> mc.player.setXRot(-8));
			shot(ctx, "13-revelation");
			// Vue d'ensemble de la place, depuis les airs
			inGame(world, "gamemode spectator Player0");
			inGame(world, "execute at @e[tag=vc_hand,limit=1] run tp Player0 ~ ~9 ~19 facing entity @e[tag=vc_hand,limit=1] feet");
			ctx.waitTicks(30);
			shot(ctx, "13b-place-revelation");
			inGame(world, "gamemode creative Player0");
			ctx.setScreen(fr.valcombe.client.screen.ChronicleScreen::new);
			shot(ctx, "15-chronique");
			ctx.setScreen(() -> null);
			inGame(world, "vc arreter");
			ctx.waitTicks(20);
			shot(ctx, "15b-remise-en-etat");
			// Fin de partie : la carte est remise en état par petits lots, chacun attend dans la salle d'attente.
			waitBuild(ctx, world);
			String restored = world.getServer().computeOnServer(server -> {
				var level = fr.valcombe.mc.games.Games.main().host().level();
				var v = fr.valcombe.mc.games.Games.main().host().village();
				int r = v.extent();
				var box = new net.minecraft.world.phys.AABB(v.center().getX() - r, v.center().getY() - 8, v.center().getZ() - r,
					v.center().getX() + r, v.center().getY() + 50, v.center().getZ() + r);
				int items = level.getEntities((net.minecraft.world.entity.Entity) null, box,
					e -> e instanceof net.minecraft.world.entity.item.ItemEntity).size();
				int scene = 0;
				for (var e : level.getAllEntities()) {
					if (e.entityTags().contains("vc_scene")) {
						scene++;
					}
				}
				boolean lanterns = true;
				for (var pos : net.minecraft.core.BlockPos.betweenClosed(v.center().offset(-20, 1, -20), v.center().offset(20, 6, 20))) {
					lanterns &= !level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.SOUL_LANTERN);
				}
				return "cassé " + (level.getBlockState(scar) == scarBefore.get(0)) + " | posé "
					+ (level.getBlockState(scar.above()) == scarBefore.get(1)) + " | objets " + items + " | scène " + scene
					+ " | lanternes " + lanterns + " | instantané "
					+ java.nio.file.Files.exists(fr.valcombe.mc.games.Games.main().host().snapshotFile());
			});
			System.out.println("[valcombe-test] remise en état : " + restored);
			check(restored.equals("cassé true | posé true | objets 0 | scène 0 | lanternes true | instantané false"),
				"carte remise en état après la partie : " + restored);

			// Hors partie : clic droit sur un trône pour s'y asseoir
			ctx.setScreen(() -> null);
			inGame(world, "gamemode adventure " + me);
			var seat = world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().village().seatFeet(0));
			var center = world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().village().center());
			inGame(world, String.format(java.util.Locale.ROOT, "tp %s %.2f %.2f %.2f facing %.2f %.2f %.2f",
				me, seat.x + (center.getX() + 0.5 - seat.x) * 0.2, seat.y, seat.z + (center.getZ() + 0.5 - seat.z) * 0.2,
				seat.x, seat.y + 0.3, seat.z));
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> {
				var bp = net.minecraft.core.BlockPos.containing(seat);
				mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
					new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(bp),
						net.minecraft.core.Direction.UP, bp, false));
			});
			ctx.waitTicks(15);
			ctx.setScreen(() -> null);
			check(ctx.computeOnClient(mc -> mc.player.isPassenger()), "assis sur le trône d'un clic droit");
			// Assis : un vrai clic droit (souris), comme un joueur, ne doit afficher aucun message d'erreur.
			ctx.getInput().pressKey(o -> o.keyUse);
			ctx.waitTicks(10);
			String overlay = ctx.computeOnClient(mc -> overlayText(mc));
			System.out.println("[valcombe-test] assis, clic droit : position " + ctx.computeOnClient(mc -> mc.player.position().toString())
				+ " ; message « " + overlay + " »");
			check(!overlay.contains("height") && !overlay.contains("hauteur"), "pas de message de hauteur en cliquant assis : " + overlay);
			ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> { if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle(); });
			ctx.waitTicks(5);
			shot(ctx, "16-trone-libre");
			ctx.runOnClient(mc -> { if (mc.gui.hud.isHidden()) mc.gui.hud.toggle(); });
			ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
			inGame(world, "ride " + me + " dismount");
			// Vue aérienne de tout le village
			inGame(world, "gamemode spectator " + me);
			inGame(world, String.format(java.util.Locale.ROOT, "tp %s %d %d %d facing %d %d %d", me,
				center.getX(), center.getY() + 34, center.getZ() + 46, center.getX(), center.getY(), center.getZ() + 4));
			ctx.waitTicks(60);
			shot(ctx, "8a-village-vue-aerienne");
			inGame(world, "gamemode creative " + me);

			// Bibliothèque de cartes : enregistrer, envoyer à la partie un village de 8 trônes, puis la carte enregistrée
			send(ctx, "vc carte enregistrer village-test Village de test");
			ctx.waitTicks(40);
			check(world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.maps().exists("village-test")), "carte enregistrée");
			// Partage : l'aperçu vu de dessus voyage avec la carte et revient à l'import.
			check(world.getServer().computeOnServer(server -> {
				var maps = fr.valcombe.mc.ValcombeMod.maps();
				var shared = maps.exportMap("village-test");
				boolean sent = shared != null && shared.has("apercu");
				shared.addProperty("id", "village-partage");
				maps.importMap(shared);
				boolean back = maps.preview("village-partage") != null && maps.exists("village-partage");
				maps.delete("village-partage");
				return sent && back && maps.preview("village-partage") == null;
			}), "aperçu joint à une carte partagée");
			send(ctx, "vc carte generer 8 petite monastere oui");
			ctx.setScreen(() -> null);
			ctx.waitTicks(20);
			shot(ctx, "19a-chantier-en-cours");
			waitBuild(ctx, world);
			check(world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().village().seatCount()) == 8, "8 trônes");
			check(world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().village().center().equals(center)),
				"village envoyé au centre du monde de la partie");
			inGame(world, "gamemode spectator " + me);
			inGame(world, String.format(java.util.Locale.ROOT, "tp %s %d %d %d facing %d %d %d", me,
				center.getX(), center.getY() + 30, center.getZ() + 38, center.getX(), center.getY(), center.getZ() + 4));
			ctx.waitTicks(60);
			shot(ctx, "19-village-8-tronees");
			// « charger » : l'ancien nom de « envoyer », vers le monde de la partie.
			send(ctx, "vc carte charger village-test");
			waitBuild(ctx, world);
			check(world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().village().seatCount()) == 16, "carte rechargée");
			// Sortis au hub pendant l'envoi, on revient au-dessus de la place pour la voir.
			inGame(world, String.format(java.util.Locale.ROOT, "tp %s %d %d %d facing %d %d %d", me,
				center.getX(), center.getY() + 30, center.getZ() + 38, center.getX(), center.getY(), center.getZ() + 4));
			ctx.waitTicks(40);
			shot(ctx, "19b-carte-rechargee");
			inGame(world, "gamemode creative " + me);
			inGame(world, String.format(java.util.Locale.ROOT, "tp %s %d %d %d", me,
				center.getX(), center.getY() + 1, center.getZ() + 4));
			ctx.waitTicks(20);
			checkAtelierScreen(ctx, world, me);
			inGame(world, "gamemode creative " + me);
			inGame(world, String.format(java.util.Locale.ROOT, "tp %s %d %d %d", me,
				center.getX(), center.getY() + 1, center.getZ() + 4));

			// Import de constructions : une petite schématique WorldEdit (v2) et un fichier Litematica
			String imported = world.getServer().computeOnServer(server -> {
				var schem = new net.minecraft.nbt.CompoundTag();
				schem.putShort("Width", (short) 3);
				schem.putShort("Height", (short) 2);
				schem.putShort("Length", (short) 3);
				var pal = new net.minecraft.nbt.CompoundTag();
				pal.putInt("minecraft:air", 0);
				pal.putInt("minecraft:stone", 1);
				pal.putInt("minecraft:oak_planks", 2);
				schem.put("Palette", pal);
				byte[] data = new byte[18];
				for (int i = 0; i < 9; i++) {
					data[i] = 1; // couche du bas : pierre
					data[9 + i] = (byte) (i == 4 ? 2 : 0); // au-dessus : une planche au centre
				}
				schem.putByteArray("BlockData", data);
				var out = new java.io.ByteArrayOutputStream();
				try {
					net.minecraft.nbt.NbtIo.writeCompressed(schem, out);
				} catch (java.io.IOException e) {
					throw new AssertionError(e);
				}
				return fr.valcombe.mc.ValcombeMod.sender().sendImport("essai.schem", out.toByteArray(), null, null);
			});
			waitBuild(ctx, world); // le monde est remis à neuf, puis la construction est posée par petits lots
			boolean schemOk = world.getServer().computeOnServer(server -> {
				var c = fr.valcombe.mc.games.Games.main().host().village().center();
				var corner = c.offset(-1, 0, -1);
				var level = fr.valcombe.mc.games.Games.main().host().level();
				System.out.println("[valcombe-test] import schem : centre " + c + " ; coin " + level.getBlockState(corner)
					+ " ; dessus " + level.getBlockState(corner.offset(1, 1, 1)) + " ; sous " + level.getBlockState(corner.below()));
				return level.getBlockState(corner).is(net.minecraft.world.level.block.Blocks.STONE)
					&& level.getBlockState(corner.offset(1, 1, 1)).is(net.minecraft.world.level.block.Blocks.OAK_PLANKS);
			});
			world.getServer().computeOnServer(server -> {
				// Litematica : une région 2 × 1 × 1, palette [air, or], états compactés sur 2 bits
				var lit = new net.minecraft.nbt.CompoundTag();
				var regions = new net.minecraft.nbt.CompoundTag();
				var reg = new net.minecraft.nbt.CompoundTag();
				var size = new net.minecraft.nbt.CompoundTag();
				size.putInt("x", 2);
				size.putInt("y", 1);
				size.putInt("z", 1);
				reg.put("Size", size);
				var lp = new net.minecraft.nbt.ListTag();
				var air = new net.minecraft.nbt.CompoundTag();
				air.putString("Name", "minecraft:air");
				var gold = new net.minecraft.nbt.CompoundTag();
				gold.putString("Name", "minecraft:gold_block");
				lp.add(air);
				lp.add(gold);
				reg.put("BlockStatePalette", lp);
				reg.putLongArray("BlockStates", new long[] {0b0101L});
				regions.put("place", reg);
				lit.put("Regions", regions);
				var out2 = new java.io.ByteArrayOutputStream();
				try {
					net.minecraft.nbt.NbtIo.writeCompressed(lit, out2);
				} catch (java.io.IOException e) {
					throw new AssertionError(e);
				}
				return fr.valcombe.mc.ValcombeMod.sender().sendImport("essai.litematic", out2.toByteArray(), null, null);
			});
			waitBuild(ctx, world);
			boolean litOk = world.getServer().computeOnServer(server -> {
				var level = fr.valcombe.mc.games.Games.main().host().level();
				var c2 = fr.valcombe.mc.games.Games.main().host().village().center();
				System.out.println("[valcombe-test] import litematic : " + level.getBlockState(c2.offset(-1, 0, 0)) + " ; " + level.getBlockState(c2));
				return level.getBlockState(c2.offset(-1, 0, 0)).is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK)
					&& level.getBlockState(c2).is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK);
			});
			check(schemOk, "import d'une construction .schem");
			check(litOk, "import d'une construction .litematic");
			System.out.println("[valcombe-test] import : " + imported);

			// Envoyer à la partie : chaque carte arrive sur un monde remis à neuf. Sur la carte faite à la main, un bloc
			// très haut (la sauvegarde automatique doit le contenir) et un autre loin de la place, hors de toute emprise :
			// ni l'un ni l'autre ne reste dans le monde neuf.
			ctx.setScreen(() -> null);
			var stray = center.offset(170, 1, 0);
			world.getServer().runOnServer(server -> {
				var level = fr.valcombe.mc.games.Games.main().host().level();
				level.setBlockAndUpdate(center.above(40), net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK.defaultBlockState());
				level.setBlockAndUpdate(stray, net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
			});
			send(ctx, "vc carte construire");
			ctx.waitTicks(5);
			String hub = world.getServer().computeOnServer(server -> "valcombe:" + fr.valcombe.mc.ValcombeMod.hub().id());
			check(world.getServer().computeOnServer(server -> server.getPlayerList().getPlayerByName(me).level().dimension()
				.identifier().toString()).equals(hub), "le joueur attend au hub pendant l'envoi");
			waitBuild(ctx, world);
			String tall = world.getServer().computeOnServer(server -> {
				var level = fr.valcombe.mc.games.Games.main().host().level();
				int height = fr.valcombe.mc.ValcombeMod.maps().list().stream().filter(d -> d.id.startsWith("sauvegarde-"))
					.mapToInt(d -> d.hauteur).max().orElse(0);
				return "haut parti " + level.getBlockState(center.above(40)).isAir() + " | loin parti " + level.getBlockState(stray).isAir()
					+ " | sauvegardé jusque-là " + (height >= 45);
			});
			check(tall.equals("haut parti true | loin parti true | sauvegardé jusque-là true"),
				"carte haute sauvegardée en entier, monde neuf sans aucun reste : " + tall);
			var seat0 = world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().village()
				.markerPos(new fr.valcombe.mc.Village.Hit(fr.valcombe.mc.Village.Mark.SEAT, 0)));
			String first = world.getServer().computeOnServer(server -> {
				var level = fr.valcombe.mc.games.Games.main().host().level();
				var v = fr.valcombe.mc.games.Games.main().host().village();
				// La construction importée était faite à la main : elle a d'abord été sauvegardée dans la bibliothèque.
				long backups = fr.valcombe.mc.ValcombeMod.maps().list().stream().filter(d -> d.id.startsWith("sauvegarde-")).count();
				return "au centre " + v.center().equals(center) + " | sauvegardes " + (backups >= 1) + " | trône "
					+ level.getBlockState(seat0).is(net.minecraft.world.level.block.Blocks.BARRIER) + " | salle de convocation "
					+ (v.convocation() != null);
			});
			System.out.println("[valcombe-test] village envoyé : " + first);
			check(first.equals("au centre true | sauvegardes true | trône true | salle de convocation true"), "village envoyé : " + first);
			shot(ctx, "19c-envoi-termine-au-hub"); // le message de fin et son lien « [Y aller] », vus du hub
			backToVillage(ctx, world, me);

			// Un autre village : le monde est de nouveau remis à neuf, il ne reste rien du précédent (ni blocs ni décors).
			send(ctx, "vc carte construire port");
			waitBuild(ctx, world);
			// L'envoi a sorti le joueur au hub : les tronçons du monde de la partie ne sont plus chargés, et leurs entités
			// (les trônes sculptés) n'y sont pas comptées. On revient sur la place, puis on attend qu'elles soient chargées.
			backToVillage(ctx, world, me);
			String second = "";
			for (int i = 0; i < 20 && !second.equals("thème port | trônes sculptés 16 dont anciens 0"); i++) {
				ctx.waitTicks(10);
				second = world.getServer().computeOnServer(server -> {
				var level = fr.valcombe.mc.games.Games.main().host().level();
				var v = fr.valcombe.mc.games.Games.main().host().village();
				int thrones = 0;
				int stale = 0;
				for (var e : level.getAllEntities()) {
					if (e.entityTags().contains("vc_trone")) {
						thrones++;
						if (!e.entityTags().contains("vc_tr_" + v.decorGen())) {
							stale++;
						}
					}
				}
				return "thème " + v.theme() + " | trônes sculptés " + thrones + " dont anciens " + stale;
				});
			}
			System.out.println("[valcombe-test] village renvoyé : " + second);
			check(second.equals("thème port | trônes sculptés 16 dont anciens 0"), "un monde neuf pour chaque carte : " + second);
			// On revient sur la place pour la photo.
			var port = world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().village().center());
			inGame(world, String.format(java.util.Locale.ROOT, "tp %s %d %d %d 180 0", me,
				port.getX(), port.getY() + 1, port.getZ() + 12));
			ctx.waitTicks(40);
			shot(ctx, "8-village");
			ctx.runOnClient(mc -> mc.player.setXRot(35));
			ctx.waitTicks(20);
			shot(ctx, "9-village-place");

			// Mode construction : étiquettes des repères, maison bâtie d'un clic, gomme, déplacement, vue de dessus
			ctx.runOnClient(mc -> mc.player.setXRot(10));
			send(ctx, "vc carte construction");
			ctx.waitTicks(40);
			shot(ctx, "25-construction-etiquettes");
			var spot = port.offset(5, 0, 25);
			String built = world.getServer().computeOnServer(server -> {
				var host = fr.valcombe.mc.games.Games.main().host();
				var p = server.getPlayerList().getPlayerByName(me);
				String msg = host.useTool(p, fr.valcombe.mc.ValcombeUi.MapTool.HOUSE, spot, true);
				return msg + " | maisons " + host.village().houseCount() + " | toit "
					+ !fr.valcombe.mc.games.Games.main().host().level().getBlockState(spot.above(5)).isAir();
			});
			System.out.println("[valcombe-test] maison bâtie : " + built);
			check(built.contains("maisons 17") && built.endsWith("toit true"), "maison bâtie d'un clic");
			inGame(world, String.format(java.util.Locale.ROOT, "tp %s %d %d %d facing %d %d %d", me,
				port.getX() + 5, port.getY() + 4, port.getZ() + 12, spot.getX(), spot.getY() + 3, spot.getZ()));
			ctx.waitTicks(40);
			shot(ctx, "25b-maison-batie");
			boolean edited = world.getServer().computeOnServer(server -> {
				var host = fr.valcombe.mc.games.Games.main().host();
				var p = server.getPlayerList().getPlayerByName(me);
				var v = host.village();
				String erased = host.useTool(p, fr.valcombe.mc.ValcombeUi.MapTool.ERASE, spot, false);
				boolean gone = fr.valcombe.mc.games.Games.main().host().level().getBlockState(spot.above(5)).isAir();
				var throne = v.markerPos(new fr.valcombe.mc.Village.Hit(fr.valcombe.mc.Village.Mark.SEAT, 0));
				String taken = host.useTool(p, fr.valcombe.mc.ValcombeUi.MapTool.SEAT, throne.below(), false);
				String moved = host.useTool(p, fr.valcombe.mc.ValcombeUi.MapTool.SEAT, throne.offset(1, -1, 0), false);
				boolean there = v.markerPos(new fr.valcombe.mc.Village.Hit(fr.valcombe.mc.Village.Mark.SEAT, 0)).equals(throne.offset(1, 0, 0));
				host.useTool(p, fr.valcombe.mc.ValcombeUi.MapTool.SEAT, throne.offset(1, -1, 0), false);
				host.useTool(p, fr.valcombe.mc.ValcombeUi.MapTool.SEAT, throne.below(), false);
				boolean back = v.markerPos(new fr.valcombe.mc.Village.Hit(fr.valcombe.mc.Village.Mark.SEAT, 0)).equals(throne);
				String plan = host.editMarker("ajouter", "siege", 0, 3, 3) + " / " + host.editMarker("retirer", "siege", 16, 0, 0);
				byte[] png = host.topView();
				try {
					java.nio.file.Files.write(java.nio.file.Path.of("screenshots", "valcombe-vue-de-dessus.png"), png);
				} catch (java.io.IOException e) {
					throw new AssertionError(e);
				}
				System.out.println("[valcombe-test] repères : " + erased + " | " + taken + " | " + moved + " | " + plan
					+ " | vue " + png.length + " octets | salle " + v.convocation());
				return gone && there && back && v.houseCount() == 16 && v.seatCount() == 16 && png.length > 1000 && v.convocation() != null;
			});
			check(edited, "gomme, déplacement d'un trône, plan de l'Atelier et vue de dessus");
			// Éditeur du mode construction : catalogue de structures, pose tournée, « annuler » commun, fichiers
			checkEditor(ctx, world, me, port, spot);
			send(ctx, "vc carte construction");
			ctx.waitTicks(10);
			check(world.getServer().computeOnServer(server -> {
				try {
					fr.valcombe.mc.ValcombeMod.editor().undo(server.getPlayerList().getPlayerByName(me), 1);
					return false;
				} catch (fr.valcombe.rules.GameException e) {
					return e.getMessage().contains("mode construction");
				}
			}), "l'éditeur est refusé hors du mode construction");

			// Partie avec Conteur humain : le joueur devient Conteur
			send(ctx, "vc quitter");
			send(ctx, "vc conteur");
			inGame(world, "vc bots 2");
			inGame(world, "vc lancer humain");
			ctx.waitTicks(20);
			// Salle de convocation : le Conteur y reçoit un joueur à l'écart (ici, lui-même, faute d'autre joueur réel)
			check(world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().summon("personne", me)), "salle de convocation");
			ctx.runOnClient(mc -> mc.player.setXRot(5));
			ctx.waitTicks(40);
			shot(ctx, "26-salle-de-convocation");
			inGame(world, String.format(java.util.Locale.ROOT, "tp %s %d %d %d 180 0", me,
				port.getX(), port.getY() + 1, port.getZ() + 12));
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
			inGame(world, "vc nuit");
			ctx.waitTicks(40);
			ctx.setScreen(() -> null);
			shot(ctx, "6b-conteur-nuit");
			// Pupitre du Conteur : nuit pas à pas, jetons de rappel par rôle, pause.
			ctx.setScreen(fr.valcombe.client.screen.NightScreen::new);
			shot(ctx, "24-nuit-pas-a-pas");
			ctx.setScreen(() -> new fr.valcombe.client.screen.TokenScreen("Bot1", true));
			shot(ctx, "24b-jetons-de-rappel");
			send(ctx, "vc rappel Bot1 ivre");
			send(ctx, "vc pause");
			ctx.setScreen(StorytellerScreen::new);
			shot(ctx, "24c-pupitre-nuit-en-pause");
			send(ctx, "vc pause");
			ctx.setScreen(() -> null);
			playNight(ctx);
			ctx.setScreen(StorytellerScreen::new);
			shot(ctx, "7-panneau-jour");
			send(ctx, "vc parole tour 30");
			send(ctx, "vc note partie Bot2 parle beaucoup");
			ctx.setScreen(StorytellerScreen::new);
			shot(ctx, "7b-pupitre-pendant-la-parole");
			send(ctx, "vc parole stop");
			ctx.setScreen(() -> new fr.valcombe.client.screen.ChronicleScreen("journal"));
			shot(ctx, "7c-journal-du-conteur");
			ctx.setScreen(() -> null);
			// Le Conteur voit le rôle de chacun au-dessus de lui
			inGame(world, "gamemode spectator " + me);
			var ctr = world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().village().center());
			inGame(world, String.format(java.util.Locale.ROOT, "tp %s %d %d %d facing %d %d %d", me,
				ctr.getX(), ctr.getY() + 9, ctr.getZ() + 16, ctr.getX(), ctr.getY() + 1, ctr.getZ()));
			ctx.waitTicks(40);
			shot(ctx, "23-conteur-roles");
			inGame(world, "gamemode creative " + me);
			// Bâton du Conteur : viser un joueur montre son rôle ; clic droit ouvre sa ligne du grimoire
			ctx.runOnClient(mc -> {
				var inv = mc.player.getInventory();
				for (int i = 0; i < 9; i++) {
					if (inv.getItem(i).is(net.minecraft.world.item.Items.BREEZE_ROD)) {
						inv.setSelectedSlot(i);
					}
				}
			});
			inGame(world, "execute as " + me + " at @s facing entity @e[tag=vc_seat_1,limit=1] feet run tp @s ~ ~ ~ ~ ~");
			ctx.waitTicks(20);
			shot(ctx, "18-baton-conteur");
			ctx.runOnClient(mc -> mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND));
			ctx.waitTicks(15);
			shot(ctx, "18b-baton-grimoire");
			ctx.setScreen(() -> null);
			checkTablet(ctx);
			send(ctx, "vc stats");
			inGame(world, "vc arreter");
			waitBuild(ctx, world); // la carte est remise en état avant de passer à la suite
			// L'Atelier, puis le scénario du Bal masqué qu'il a choisi : écran Scénarios et rôle créé dans le script
			checkAtelier(ctx);
			ctx.setScreen(fr.valcombe.client.screen.ScenarioScreen::new);
			shot(ctx, "20-scenarios");
			ctx.setScreen(fr.valcombe.client.screen.ScriptScreen::new);
			shot(ctx, "20b-script-avec-role-cree");
			ctx.setScreen(() -> null);

			// Phase 12 : prochaine soirée (description du serveur), carnet pour le launcher, daltonien, présentoir
			inGame(world, "vc soiree vendredi 21 h");
			ctx.waitTicks(60);
			check(world.getServer().computeOnServer(server -> server.getMotd()).contains("vendredi 21 h"), "prochaine soirée annoncée");
			check(ctx.computeOnClient(mc -> java.nio.file.Files.exists(mc.gameDirectory.toPath().resolve("valcombe-carnet.json"))),
				"carnet écrit pour le launcher");
			ctx.runOnClient(mc -> fr.valcombe.client.ClientConfig.get().daltonien = true);
			ctx.setScreen(fr.valcombe.client.screen.ScriptScreen::new);
			shot(ctx, "21-daltonien");
			ctx.runOnClient(mc -> fr.valcombe.client.ClientConfig.get().daltonien = false);
			ctx.setScreen(() -> null);
			var hall = world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().village().hallSpot());
			if (hall != null) {
				inGame(world, String.format(java.util.Locale.ROOT, "tp %s %.2f %.2f %.2f facing %.2f %.2f %.2f",
					me, hall.x, hall.y, hall.z + 1.5, hall.x, hall.y + 3.2, hall.z - 6));
				ctx.waitTicks(60);
				shot(ctx, "22-salle-presentoir");
				inGame(world, String.format(java.util.Locale.ROOT, "tp %s %.2f %.2f %.2f facing %.2f %.2f %.2f",
					me, hall.x + 3, hall.y, hall.z, hall.x - 8, hall.y + 2.5, hall.z));
				ctx.waitTicks(20);
				shot(ctx, "22b-salle-panneau-mur");
			}

			// Arrêt du serveur en pleine partie, sans partie à reprendre : au démarrage, la carte est tout de même remise en état.
			var gap = world.getServer().computeOnServer(server -> {
				var host = fr.valcombe.mc.games.Games.main().host();
				var pos = host.village().center().offset(3, 0, 3);
				host.gameStarted(); // l'instantané d'une partie qui ne reprendra pas
				fr.valcombe.mc.games.Games.main().host().level().setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
				host.resumeRestore(); // ce que fait le démarrage du serveur
				return pos;
			});
			check(world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().busy()), "remise en état reprise au démarrage");
			waitBuild(ctx, world);
			check(world.getServer().computeOnServer(server -> !fr.valcombe.mc.games.Games.main().host().level().getBlockState(gap).isAir()), "bloc revenu après la reprise");

			// Vider le monde : remis à neuf, plat et vide, on y joue sans carte (et le démarrage n'y bâtira rien).
			var oldCenter = world.getServer().computeOnServer(server -> fr.valcombe.mc.games.Games.main().host().village().center());
			send(ctx, "vc carte vider");
			waitBuild(ctx, world);
			String empty = world.getServer().computeOnServer(server -> {
				var host = fr.valcombe.mc.games.Games.main().host();
				var level = host.level();
				return "sans carte " + (host.village() == null) + " | sans fiche " + !java.nio.file.Files.exists(host.villageFile())
					+ " | marqué " + java.nio.file.Files.exists(fr.valcombe.mc.ValcombeMod.worlds().folder(host.worldId())
						.resolve("valcombe-sans-carte.txt"))
					+ " | sol plat " + (level.getBlockState(oldCenter).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
						&& level.getBlockState(oldCenter.above()).isAir() && level.getBlockState(gap).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK));
			});
			System.out.println("[valcombe-test] monde vidé : " + empty);
			check(empty.equals("sans carte true | sans fiche true | marqué true | sol plat true"), "monde vidé : " + empty);
			// Nouvelle carte vide : un monde neuf, la place au centre, sans repères ni décors, à enregistrer.
			send(ctx, "vc carte nouvelle");
			waitBuild(ctx, world);
			String fresh = world.getServer().computeOnServer(server -> {
				var host = fr.valcombe.mc.games.Games.main().host();
				var v = host.village();
				int thrones = 0;
				for (var e : host.level().getAllEntities()) {
					if (e.entityTags().contains("vc_trone")) {
						thrones++;
					}
				}
				return "à la main " + !v.isGenerated() + " | au centre " + v.center().equals(oldCenter) + " | repères " + v.seatCount()
					+ " | trônes sculptés " + thrones + " | à protéger " + v.modified() + " | marque partie "
					+ !java.nio.file.Files.exists(fr.valcombe.mc.ValcombeMod.worlds().folder(host.worldId()).resolve("valcombe-sans-carte.txt"));
			});
			System.out.println("[valcombe-test] nouvelle carte : " + fresh);
			check(fresh.equals("à la main true | au centre true | repères 0 | trônes sculptés 0 | à protéger true | marque partie true"),
				"nouvelle carte vide sur un monde neuf : " + fresh);
		}
	}
}
