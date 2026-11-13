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

	/** Attend la fin d'un chantier de carte : les blocs sont posés par petits lots, sur plusieurs ticks. */
	private static void waitBuild(ClientGameTestContext ctx, TestSingleplayerContext world) {
		for (int i = 0; i < 240 && world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().busy()); i++) {
			ctx.waitTicks(10);
		}
		check(!world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().busy()), "chantier terminé");
		ctx.waitTicks(20);
	}

	/** Une opération de l'éditeur de carte, puis l'attente de la fin de sa pose ; renvoie ce que l'éditeur a répondu. */
	private static String edit(ClientGameTestContext ctx, TestSingleplayerContext world, String me,
			java.util.function.BiFunction<fr.valcombe.mc.MapEditor, net.minecraft.server.level.ServerPlayer, String> op) {
		String msg = world.getServer().computeOnServer(server -> op.apply(fr.valcombe.mc.ValcombeMod.editor(),
			server.getPlayerList().getPlayerByName(me)));
		for (int i = 0; i < 600 && world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().busy()); i++) {
			ctx.waitTicks(2);
		}
		check(!world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().busy()), "opération de l'éditeur terminée : " + msg);
		System.out.println("[valcombe-test] éditeur : " + msg);
		return msg;
	}

	/** Combien de blocs de cette sorte dans le pavé (coins compris). */
	private static int count(TestSingleplayerContext world, net.minecraft.core.BlockPos a, net.minecraft.core.BlockPos b,
			net.minecraft.world.level.block.Block block) {
		return world.getServer().computeOnServer(server -> {
			int n = 0;
			for (var pos : net.minecraft.core.BlockPos.betweenClosed(a, b)) {
				if (server.overworld().getBlockState(pos).is(block)) {
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

	/** La réponse du serveur que l'écran de l'Atelier affiche sous ses boutons. */
	private static String reply(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			var r = ClientState.obj(ClientState.obj(ClientState.view(), "atelier"), "reponse");
			return r == null ? "" : (ClientState.bool(r, "erreur") ? "ERREUR " : "") + ClientState.str(r, "texte");
		});
	}

	/**
	 * L'Atelier en jeu, par ses vrais boutons : bibliothèque de cartes (aperçu, copie, suppression confirmée), village
	 * généré puis carte rechargée, repères, catalogue de structures, sélection, scénario lié à une carte et partie d'essai.
	 * Au départ et à la fin, la carte « village-test » est en place.
	 */
	private void checkAtelierScreen(ClientGameTestContext ctx, TestSingleplayerContext world, String me) {
		var server = world.getServer();
		java.util.function.Supplier<fr.valcombe.mc.Village> village = () -> server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.host().village());
		// La commande ouvre l'écran ; le menu principal d'un opérateur a son bouton « Atelier ».
		send(ctx, "vc atelier jeu");
		ctx.waitTicks(10);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof fr.valcombe.client.screen.AtelierScreen), "/vc atelier jeu ouvre l'écran");
		ctx.setScreen(MainScreen::new);
		shot(ctx, "28-menu-avec-atelier");
		press(ctx, "Atelier");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof fr.valcombe.client.screen.AtelierScreen), "bouton Atelier du menu");

		// Cartes : la carte actuelle vient de la bibliothèque, et son aperçu vu de dessus arrive tout seul.
		ctx.setScreen(() -> new fr.valcombe.client.screen.AtelierScreen(0));
		ctx.waitTicks(30);
		String here = ctx.computeOnClient(mc -> {
			var a = ClientState.obj(ClientState.view(), "atelier");
			var pv = ClientState.preview();
			return ClientState.str(ClientState.obj(a, "carte"), "id") + " | " + ClientState.str(a, "resume") + " | aperçu "
				+ (pv == null ? "absent" : ClientState.num(pv, "cote", 0) + " " + ClientState.previewColors().length + " "
					+ pv.getAsJsonArray("sieges").size());
		});
		System.out.println("[valcombe-test] atelier en jeu, carte actuelle : " + here);
		check(here.equals("village-test | Carte actuelle : « Village de test ». 16 trônes, 16 maisons. | aperçu 48 2304 16"),
			"carte actuelle et aperçu : " + here);
		shot(ctx, "28a-atelier-cartes");
		// Une carte de la bibliothèque : aperçu, copie sous un identifiant libre, suppression en deux clics.
		press(ctx, "Village de test");
		ctx.waitTicks(20);
		check("village-test".equals(ctx.computeOnClient(mc -> ClientState.str(ClientState.preview(), "id"))), "aperçu de la carte choisie");
		shot(ctx, "28b-atelier-carte-de-la-bibliotheque");
		press(ctx, "Dupliquer");
		check(server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.maps().exists("village-test-copie")), "carte dupliquée : " + reply(ctx));
		check(reply(ctx).contains("Village de test (copie)"), "réponse du serveur montrée : " + reply(ctx));
		ctx.waitTicks(70); // la liste des cartes est relue toutes les trois secondes au plus
		press(ctx, "Village de test (copie)");
		press(ctx, "Supprimer");
		check(server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.maps().exists("village-test-copie")), "un seul clic ne supprime rien");
		shot(ctx, "28c-atelier-suppression-a-confirmer");
		press(ctx, "Confirmer : supprimer");
		check(!server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.maps().exists("village-test-copie")), "carte supprimée au second clic");

		// Village : quinze trônes, à confirmer ; puis la carte de la bibliothèque est rechargée depuis l'écran.
		ctx.setScreen(() -> new fr.valcombe.client.screen.AtelierScreen(1));
		press(ctx, "−");
		shot(ctx, "28d-atelier-village");
		press(ctx, "Générer le village");
		check(!server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.host().busy()), "générer demande une confirmation");
		press(ctx, "Confirmer : générer le village");
		check(server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.host().busy()), "village en chantier : " + reply(ctx));
		ctx.waitTicks(10);
		shot(ctx, "28e-atelier-chantier-en-cours");
		waitBuild(ctx, world);
		check(village.get().seatCount() == 15 && village.get().libraryId().isEmpty(), "village de quinze trônes généré");
		ctx.setScreen(() -> new fr.valcombe.client.screen.AtelierScreen(0));
		ctx.waitTicks(70);
		press(ctx, "Village de test");
		press(ctx, "Charger");
		press(ctx, "Confirmer : charger");
		waitBuild(ctx, world);
		check(village.get().seatCount() == 16 && village.get().libraryId().equals("village-test") && !village.get().modified(),
			"carte rechargée depuis l'écran");

		// Repères : liste de contrôle, mode construction, un trône posé puis le geste annulé.
		ctx.setScreen(() -> new fr.valcombe.client.screen.AtelierScreen(2));
		shot(ctx, "28f-atelier-reperes");
		press(ctx, "Entrer en mode construction");
		check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "l'écran se ferme en entrant en mode construction");
		check(server.computeOnServer(s -> (Boolean) fr.valcombe.mc.ValcombeMod.editor().state(me).get("construction")), "mode construction");
		ctx.setScreen(() -> new fr.valcombe.client.screen.AtelierScreen(2));
		press(ctx, "Trône ici");
		check(village.get().seatCount() == 17, "trône posé depuis l'écran : " + reply(ctx));
		press(ctx, "Renuméroter (sens horaire)");
		press(ctx, "Annuler");
		press(ctx, "Annuler");
		check(village.get().seatCount() == 16, "les deux derniers gestes annulés : " + reply(ctx));
		shot(ctx, "28g-atelier-reperes-en-construction");

		// Structures : une maison du catalogue prise en main depuis l'écran, puis rangée.
		ctx.setScreen(() -> new fr.valcombe.client.screen.AtelierScreen(3));
		press(ctx, "Maison gothique");
		ctx.waitTicks(20); // la maquette arrive du serveur
		check(ctx.computeOnClient(mc -> fr.valcombe.client.Maquette.get("maison-gothique") != null
			&& !fr.valcombe.client.Maquette.get("maison-gothique").cells.isEmpty()), "maquette 3D de la structure reçue");
		shot(ctx, "28h-atelier-structures");
		press(ctx, "Voir en grand");
		ctx.waitTicks(5);
		shot(ctx, "28h2-structure-en-3d");
		ctx.setScreen(() -> new fr.valcombe.client.screen.AtelierScreen(3));
		press(ctx, "Prendre en main");
		check("maison-gothique".equals(server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.editor().state(me).get("structure"))),
			"structure prise en main");
		// Le fantôme de la structure, là où l'on vise (le sol, devant soi)
		ctx.runOnClient(mc -> mc.player.setXRot(22));
		ctx.waitTicks(30);
		check(ctx.computeOnClient(mc -> fr.valcombe.client.Ghost.active()), "fantôme de la structure en main, là où l'on vise");
		shot(ctx, "28h3-fantome-de-la-structure");
		ctx.setScreen(() -> new fr.valcombe.client.screen.AtelierScreen(3));
		press(ctx, "Tourner");
		shot(ctx, "28i-atelier-structure-en-main");
		// Posée à ses pieds, puis reprise avec la main : tournée, puis retirée (le terrain et le repère reviennent).
		int housesBefore = village.get().houseCount();
		press(ctx, "À mes pieds");
		ctx.waitTicks(80);
		check(village.get().houseCount() == housesBefore + 1, "maison posée avec son repère : " + reply(ctx));
		check(server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.host().objectAction(s.getPlayerList().getPlayerByName(me),
			"structure", 0, "tourner")).contains("Maison gothique"), "structure posée tournée avec la main");
		ctx.waitTicks(80);
		ctx.setScreen(() -> new fr.valcombe.client.screen.ObjectScreen("structure", 0, "Maison gothique"));
		shot(ctx, "28i2-menu-de-la-main");
		ctx.setScreen(() -> null);
		server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.host().objectAction(s.getPlayerList().getPlayerByName(me),
			"structure", 0, "retirer"));
		ctx.waitTicks(80);
		check(village.get().houseCount() == housesBefore, "structure retirée avec son repère");
		// Un trône tourné avec la main
		server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.host().objectAction(s.getPlayerList().getPlayerByName(me),
			"trone", 0, "droite"));
		check(village.get().seatTurned(0), "trône tourné d'un huitième de tour");
		server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.host().objectAction(s.getPlayerList().getPlayerByName(me),
			"trone", 0, "centre"));
		check(!village.get().seatTurned(0), "trône de nouveau tourné vers le centre");
		ctx.setScreen(() -> new fr.valcombe.client.screen.AtelierScreen(3));
		press(ctx, "Ranger");
		check(server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.editor().state(me).get("structure")) == null, "structure rangée");
		press(ctx, "Les vôtres");
		shot(ctx, "28j-atelier-structures-de-l-utilisateur");

		// Sélection : deux coins, puis copier depuis l'écran.
		var corner = village.get().center().offset(0, 20, 0);
		server.runOnServer(s -> {
			var p = s.getPlayerList().getPlayerByName(me);
			fr.valcombe.mc.ValcombeMod.editor().setCorner(p, 1, corner);
			fr.valcombe.mc.ValcombeMod.editor().setCorner(p, 2, corner.offset(2, 1, 3));
		});
		ctx.setScreen(() -> new fr.valcombe.client.screen.AtelierScreen(4));
		press(ctx, "Copier");
		check(server.computeOnServer(s -> (Boolean) fr.valcombe.mc.ValcombeMod.editor().state(me).get("pressePapiers")),
			"sélection copiée depuis l'écran : " + reply(ctx));
		shot(ctx, "28k-atelier-selection");
		press(ctx, "Désélectionner");
		ctx.setScreen(() -> null);
		send(ctx, "vc carte construction"); // sortie du mode construction
		check(village.get().modified(), "carte tenue pour retouchée après le mode construction");
		// Un refus arrive à l'écran comme un refus (affiché en rouge) : hors du mode construction, l'éditeur ne copie rien.
		send(ctx, "vc edit copier");
		ctx.waitTicks(12);
		check(reply(ctx).startsWith("ERREUR ") && reply(ctx).contains("mode construction"), "refus signalé à l'écran : " + reply(ctx));

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
		boolean inLobby = server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.session().lobby().contains(me));
		long botsBefore = server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.session().lobby().stream().filter(n -> n.startsWith("Bot")).count());
		ctx.setScreen(() -> new fr.valcombe.client.screen.AtelierScreen(5));
		ctx.waitTicks(10);
		press(ctx, "Essai sur le village  (5 à 16)");
		shot(ctx, "28l-atelier-creations");
		press(ctx, "Partie d'essai (charge sa carte)");
		check(server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.session().scenario()) == null, "l'essai qui charge une carte se confirme");
		press(ctx, "Confirmer : partie d'essai (charge sa carte)");
		String waiting = server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.session().trialPending() + " " + fr.valcombe.mc.ValcombeMod.host().busy()
			+ " " + (fr.valcombe.mc.ValcombeMod.session().game() == null));
		check(waiting.equals("true true true"), "partie d'essai en attente du chantier (" + waiting + ") : " + reply(ctx));
		shot(ctx, "28m-atelier-essai-en-attente");
		ctx.setScreen(() -> null);
		for (int i = 0; i < 400 && server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.session().game() == null); i++) {
			ctx.waitTicks(10);
		}
		String trial = server.computeOnServer(s -> {
			var ses = fr.valcombe.mc.ValcombeMod.session();
			var v = fr.valcombe.mc.ValcombeMod.host().village();
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
		ctx.setScreen(() -> new fr.valcombe.client.screen.AtelierScreen(5));
		ctx.waitTicks(10);
		press(ctx, "Choisir pour la prochaine partie");
		check(!server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.host().busy()), "carte déjà en place : pas de rechargement");
		press(ctx, "Supprimer ce scénario");
		press(ctx, "Confirmer : supprimer ce scénario");
		check(server.computeOnServer(s -> fr.valcombe.mc.ValcombeMod.session().scenario() == null
			&& fr.valcombe.mc.ValcombeMod.session().scenarios().stream().noneMatch(d -> d.id.equals("essai-carte"))),
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
	 * Éditeur du mode construction : le catalogue, une maison posée tournée (avec son repère), l'aperçu, puis une
	 * sélection remplie, remplacée, murée, copiée, collée, déplacée, empilée — et tout cela annulé, puis les fichiers.
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
			var level = server.overworld();
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
				+ (panes > 0 && joined == panes) + " | maisons " + fr.valcombe.mc.ValcombeMod.host().village().houseCount();
		});
		System.out.println("[valcombe-test] structure posée : " + placed + " → " + house);
		check(house.equals("porte à l'est true | toit true | vitres raccordées true | maisons 17"), "maison du catalogue posée tournée : " + house);
		world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d facing %d %d %d", me,
			port.getX() + 16, port.getY() + 5, port.getZ() + 14, spot.getX(), spot.getY() + 3, spot.getZ()));
		ctx.waitTicks(40);
		shot(ctx, "27-structure-du-catalogue");

		// Annuler retire la maison et son repère ; rétablir les remet.
		edit(ctx, world, me, (ed, p) -> ed.undo(p, 1));
		check(world.getServer().computeOnServer(server -> server.overworld().getBlockState(spot.above(5)).isAir()
			&& fr.valcombe.mc.ValcombeMod.host().village().houseCount() == 16), "pose annulée : maison et repère retirés");
		edit(ctx, world, me, (ed, p) -> ed.redo(p, 1));
		check(world.getServer().computeOnServer(server -> !server.overworld().getBlockState(spot.above(5)).isAir()
			&& fr.valcombe.mc.ValcombeMod.host().village().houseCount() == 17), "pose rétablie");
		edit(ctx, world, me, (ed, p) -> ed.undo(p, 1));

		// Aperçu : le contour suit le bloc visé, l'outil « structure » en main.
		world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.editor().choose(server.getPlayerList().getPlayerByName(me), "puits"));
		world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d facing %d %d %d", me,
			port.getX() + 5, port.getY() + 4, port.getZ() + 14, spot.getX(), spot.getY(), spot.getZ()));
		ctx.waitTicks(40);
		shot(ctx, "27b-structure-apercu");

		// Sélection de 5 × 3 × 4 en l'air, puis les opérations une à une.
		var a = spot.above(12);
		var b = a.offset(4, 2, 3);
		String selected = world.getServer().computeOnServer(server -> {
			var p = server.getPlayerList().getPlayerByName(me);
			fr.valcombe.mc.ValcombeMod.editor().setCorner(p, 1, b);
			return fr.valcombe.mc.ValcombeMod.editor().setCorner(p, 2, a);
		});
		check(selected.contains("5 × 3 × 4") && selected.contains("60 blocs"), "sélection : " + selected);
		edit(ctx, world, me, (ed, p) -> ed.fill(p, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()));
		check(count(world, a, b, net.minecraft.world.level.block.Blocks.STONE) == 60, "sélection remplie");
		world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d facing %d %d %d", me,
			a.getX() + 2, spot.getY() + 1, a.getZ() - 14, a.getX() + 2, a.getY() + 1, a.getZ() + 2));
		ctx.runOnClient(mc -> {
			var inv = mc.player.getInventory();
			for (int i = 0; i < 9; i++) {
				if (inv.getItem(i).is(net.minecraft.world.item.Items.WOODEN_AXE)) {
					inv.setSelectedSlot(i);
				}
			}
		});
		ctx.waitTicks(40);
		shot(ctx, "27c-selection-remplie");
		edit(ctx, world, me, (ed, p) -> ed.replace(p, s -> s.is(net.minecraft.world.level.block.Blocks.STONE), "stone",
			net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState()));
		check(count(world, a, b, net.minecraft.world.level.block.Blocks.GLASS) == 60, "pierre remplacée par du verre");
		edit(ctx, world, me, (ed, p) -> ed.walls(p, net.minecraft.world.level.block.Blocks.BRICKS.defaultBlockState(), false));
		check(count(world, a, b, net.minecraft.world.level.block.Blocks.GLASS) == 3 * 2 * 3
			&& count(world, a, b, net.minecraft.world.level.block.Blocks.BRICKS) == 60 - 18, "murs de la sélection");
		// Copier, se déplacer de huit blocs, coller : la copie arrive huit blocs plus loin.
		edit(ctx, world, me, (ed, p) -> {
			ed.copy(p);
			p.teleportTo(p.level(), p.getX() + 8, p.getY(), p.getZ(), java.util.Set.of(), p.getYRot(), p.getXRot(), false);
			return ed.paste(p, false);
		});
		check(count(world, a.offset(8, 0, 0), b.offset(8, 0, 0), net.minecraft.world.level.block.Blocks.BRICKS) == 42
			&& count(world, a, b, net.minecraft.world.level.block.Blocks.BRICKS) == 42, "copier puis coller");
		String turned = world.getServer().computeOnServer(server -> {
			var p = server.getPlayerList().getPlayerByName(me);
			return fr.valcombe.mc.ValcombeMod.editor().rotateClipboard(p, 1) + " " + fr.valcombe.mc.ValcombeMod.editor().flipClipboard(p);
		});
		check(turned.contains("4 × 3 × 5"), "presse-papiers tourné : " + turned);
		edit(ctx, world, me, (ed, p) -> ed.move(p, 4, "haut"));
		check(count(world, a, b, net.minecraft.world.level.block.Blocks.BRICKS) == 0
			&& count(world, a.above(4), b.above(4), net.minecraft.world.level.block.Blocks.BRICKS) == 42, "sélection déplacée");
		edit(ctx, world, me, (ed, p) -> ed.stack(p, 2, "est"));
		check(count(world, a.offset(5, 4, 0), b.offset(10, 4, 0), net.minecraft.world.level.block.Blocks.BRICKS) == 84, "sélection empilée");

		// La sélection devient une structure du catalogue ; elle voyage dans un pack .valcombe.
		String shared = world.getServer().computeOnServer(server -> {
			var p = server.getPlayerList().getPlayerByName(me);
			var store = fr.valcombe.mc.ValcombeMod.structures();
			String saved = fr.valcombe.mc.ValcombeMod.editor().saveSelection(p, "essai-mur", "Mur d'essai", "");
			var def = store.def("essai-mur");
			String pack = fr.valcombe.mc.ValcombeMod.creations().exportPack(java.util.List.of(), java.util.List.of(), java.util.List.of(),
				java.util.List.of("essai-mur", "puits"));
			store.delete("essai-mur");
			String back = fr.valcombe.mc.ValcombeMod.creations().importPack(pack);
			var again = store.def("essai-mur");
			return saved + " | " + def.taille[0] + "×" + def.taille[1] + "×" + def.taille[2] + " | " + back + " | " + again.blocs;
		});
		System.out.println("[valcombe-test] structure enregistrée et partagée : " + shared);
		check(shared.contains("5×3×4") && shared.contains("Importé : structure « Mur d'essai »") && shared.endsWith("| 60"),
			"structure enregistrée puis reçue d'un pack : " + shared);
		// La structure reçue se pose comme les autres, retournée.
		edit(ctx, world, me, (ed, p) -> {
			ed.choose(p, "essai-mur");
			ed.mirror(p);
			return ed.place(p, a.offset(0, 12, 0));
		});
		check(count(world, a.offset(-4, 13, -3), a.offset(4, 15, 3), net.minecraft.world.level.block.Blocks.BRICKS) == 42, "structure de l'utilisateur posée");

		// Fichiers importés comme structures : une schématique passe, un fichier piégé est refusé, la carte n'est pas touchée.
		String files = world.getServer().computeOnServer(server -> {
			var store = fr.valcombe.mc.ValcombeMod.structures();
			var village = fr.valcombe.mc.ValcombeMod.host().village();
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
					+ (fr.valcombe.mc.ValcombeMod.host().village() == village && !fr.valcombe.mc.ValcombeMod.host().busy());
				store.delete("petite-dalle");
				store.delete("essai-mur");
				return result;
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
		System.out.println("[valcombe-test] fichiers importés comme structures : " + files);
		check(files.contains("2×1×2") && files.contains("hors de ses dimensions") && files.endsWith("carte intacte true"),
			"import de fichiers comme structures : " + files);

		// Tout annuler d'un coup : il ne reste rien de ce que l'éditeur a posé.
		int steps = world.getServer().computeOnServer(server -> (Integer) fr.valcombe.mc.ValcombeMod.editor().state(me).get("annulables"));
		edit(ctx, world, me, (ed, p) -> ed.undo(p, 40));
		String undone = world.getServer().computeOnServer(server -> {
			int left = 0;
			for (var pos : net.minecraft.core.BlockPos.betweenClosed(a.offset(-6, 0, -6), a.offset(20, 20, 8))) {
				if (!server.overworld().getBlockState(pos).isAir()) {
					left++;
				}
			}
			var state = fr.valcombe.mc.ValcombeMod.editor().state(me);
			return "blocs restants " + left + " | à annuler " + state.get("annulables") + " | à rétablir " + state.get("retablissables")
				+ " | maisons " + fr.valcombe.mc.ValcombeMod.host().village().houseCount();
		});
		System.out.println("[valcombe-test] tout annulé (" + steps + " opérations) : " + undone);
		check(steps >= 7 && undone.equals("blocs restants 0 | à annuler 0 | à rétablir " + steps + " | maisons 16"), "tout annuler : " + undone);

		// De vrais clics avec l'outil de sélection : le clic gauche pose le premier coin sans casser le bloc, le clic droit le second.
		world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d", me, port.getX() + 2, port.getY() + 1, port.getZ() + 8));
		ctx.waitTicks(20);
		var left = port.offset(2, 0, 9);
		var right = port.offset(4, 0, 10);
		var ground = world.getServer().computeOnServer(server -> server.overworld().getBlockState(left));
		ctx.runOnClient(mc -> {
			var inv = mc.player.getInventory();
			for (int i = 0; i < 9; i++) {
				if (inv.getItem(i).is(net.minecraft.world.item.Items.WOODEN_AXE)) {
					inv.setSelectedSlot(i);
				}
			}
		});
		ctx.waitTicks(5);
		ctx.runOnClient(mc -> mc.gameMode.startDestroyBlock(left, net.minecraft.core.Direction.UP));
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
			new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(right), net.minecraft.core.Direction.UP, right, false)));
		ctx.waitTicks(10);
		String clicks = world.getServer().computeOnServer(server -> {
			var p = server.getPlayerList().getPlayerByName(me);
			var ed = fr.valcombe.mc.ValcombeMod.editor();
			return "coin 1 " + ed.isCorner(p, 1, left) + " | coin 2 " + ed.isCorner(p, 2, right) + " | bloc intact "
				+ (server.overworld().getBlockState(left) == ground);
		});
		check(clicks.equals("coin 1 true | coin 2 true | bloc intact true"), "clics de l'outil de sélection : " + clicks);

		// Les mêmes opérations par les commandes : /vc edit et /vc structure.
		send(ctx, "vc edit deselectionner");
		send(ctx, "vc structure liste");
		var high = port.offset(2, 1, 8); // là où se tient le joueur, sur la place
		send(ctx, "vc edit coin1");
		send(ctx, "vc edit coin2");
		send(ctx, "vc edit etendre 2 est");
		send(ctx, "vc edit remplir minecraft:glass");
		ctx.waitTicks(10);
		check(count(world, high, high.offset(2, 0, 0), net.minecraft.world.level.block.Blocks.GLASS) == 3, "/vc edit remplir");
		send(ctx, "vc edit remplacer glass oak_fence");
		ctx.waitTicks(10);
		check(world.getServer().computeOnServer(server -> {
			var fence = server.overworld().getBlockState(high.offset(1, 0, 0));
			// Une barrière posée par l'éditeur se raccorde à ses voisines.
			return fence.is(net.minecraft.world.level.block.Blocks.OAK_FENCE) && fence.getValue(net.minecraft.world.level.block.FenceBlock.EAST)
				&& fence.getValue(net.minecraft.world.level.block.FenceBlock.WEST);
		}), "/vc edit remplacer, barrières raccordées");
		send(ctx, "vc edit annuler 2");
		ctx.waitTicks(20);
		check(count(world, high, high.offset(2, 0, 0), net.minecraft.world.level.block.Blocks.AIR) == 3, "/vc edit annuler 2");
		send(ctx, "vc structure poser banc ici");
		ctx.waitTicks(10);
		check(count(world, high.offset(-2, -1, -2), high.offset(2, 1, 2), net.minecraft.world.level.block.Blocks.SPRUCE_STAIRS) == 3, "/vc structure poser banc ici");
		send(ctx, "vc edit annuler");
		ctx.waitTicks(10);
		check(count(world, high.offset(-2, -1, -2), high.offset(2, 1, 2), net.minecraft.world.level.block.Blocks.SPRUCE_STAIRS) == 0, "banc annulé");
		send(ctx, "vc edit deselectionner");
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
		check("bal-masque".equals(fr.valcombe.mc.ValcombeMod.session().scenario().id), "scénario de la prochaine partie");
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
		check(!fr.valcombe.mc.ValcombeMod.host().busy() && fr.valcombe.mc.ValcombeMod.session().game() == null, "rien n'a été lancé");
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
		int housesBefore = fr.valcombe.mc.ValcombeMod.host().village().houseCount();
		var add = post.apply("carte/repere", "{\"action\":\"ajouter\",\"type\":\"maison\",\"x\":6,\"z\":-52}");
		var built = post.apply("carte/batir", "{\"type\":\"maison\",\"index\":" + housesBefore + "}");
		check(add.statusCode() == 200 && built.statusCode() == 200, "maison bâtie depuis le plan : " + add.body() + " / " + built.body());
		var erase = post.apply("carte/repere", "{\"action\":\"retirer\",\"type\":\"maison\",\"index\":" + housesBefore + "}");
		check(erase.statusCode() == 200 && erase.body().contains("sa construction avec"), "retirer sur le plan retire la construction : " + erase.body());
		var move = post.apply("carte/repere", "{\"action\":\"deplacer\",\"type\":\"siege\",\"index\":0,\"x\":3,\"z\":3}");
		var seatMoved = fr.valcombe.mc.ValcombeMod.host().village().relSeats().get(0);
		var undo = post.apply("carte/annuler", "");
		var seatBack = fr.valcombe.mc.ValcombeMod.host().village().relSeats().get(0);
		check(move.statusCode() == 200 && undo.statusCode() == 200 && seatMoved[0] == 3 && seatMoved[2] == 3
			&& (seatBack[0] != 3 || seatBack[2] != 3) && fr.valcombe.mc.ValcombeMod.host().village().houseCount() == housesBefore,
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
					world.getServer().runCommand(line.substring(1));
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
			world.getServer().runCommand("op " + me);
			ctx.waitTicks(40);
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
			world.getServer().runCommand("vc bots 6");
			ctx.waitTicks(20);
			ctx.setScreen(MainScreen::new);
			shot(ctx, "1-salon");
			ctx.setScreen(SheetsScreen::new);
			shot(ctx, "2-fiches");
			ctx.setScreen(fr.valcombe.client.screen.NotebookScreen::new);
			shot(ctx, "2b-carnet");
			ctx.setScreen(StorytellerScreen::new);
			shot(ctx, "3-panneau-salon");
			world.getServer().runCommand("vc script basculer margrave");
			ctx.setScreen(fr.valcombe.client.screen.ScriptScreen::new);
			shot(ctx, "3b-script");
			world.getServer().runCommand("vc script complet");
			ctx.setScreen(fr.valcombe.client.screen.SettingsScreen::new);
			shot(ctx, "3c-reglages");
			world.getServer().runCommand("vc figure sablier oui");
			ctx.setScreen(fr.valcombe.client.screen.FiguresScreen::new);
			shot(ctx, "3d-figures");
			world.getServer().runCommand("vc figure sablier non");

			// Partie avec Conteur automatique
			ctx.setScreen(() -> null);
			world.getServer().runCommand("vc lancer auto");
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
			var scar = world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().village().center().offset(4, -2, -4));
			var scarBefore = world.getServer().computeOnServer(server -> java.util.List.of(
				server.overworld().getBlockState(scar), server.overworld().getBlockState(scar.above())));
			check(world.getServer().computeOnServer(server -> java.nio.file.Files.exists(
				server.getServerDirectory().resolve("config").resolve("valcombe-instantane.nbt"))), "instantané de la carte pris au lancement");
			var litter = world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().village().houseFeet().get(3));
			world.getServer().runCommand(String.format(java.util.Locale.ROOT, "setblock %d %d %d minecraft:air", scar.getX(), scar.getY(), scar.getZ()));
			world.getServer().runCommand(String.format(java.util.Locale.ROOT, "setblock %d %d %d minecraft:gold_block",
				scar.getX(), scar.getY() + 1, scar.getZ()));
			world.getServer().runCommand(String.format(java.util.Locale.ROOT,
				"summon minecraft:item %.1f %.1f %.1f {Item:{id:\"minecraft:stick\",count:1}}", litter.x, litter.y + 0.5, litter.z));
			world.getServer().runCommand("vc nuit");
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
				world.getServer().runCommand("vc voter");
				ctx.waitTicks(50);
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
				world.getServer().runCommand("ride Player0 dismount");
				world.getServer().runCommand("gamemode spectator Player0");
				world.getServer().runCommand("execute at @e[tag=vc_hand,limit=1] run tp Player0 ~ ~9 ~19 facing entity @e[tag=vc_hand,limit=1] feet");
				ctx.waitTicks(40);
				shot(ctx, "14-horloge-vote");
				world.getServer().runCommand("gamemode adventure Player0");
				world.getServer().runCommand("vc rassembler");
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
					var g = fr.valcombe.mc.ValcombeMod.session().game();
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
					world.getServer().runCommand("vc changer " + me + " " + w[1]);
					world.getServer().runCommand("vc changer " + w[0] + " " + w[2]);
					ctx.waitTicks(5);
				}
				if (swap != null) {
					world.getServer().runCommand("vc tuer " + me);
					ctx.runOnClient(mc -> {
						if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
						mc.player.setXRot(10);
					});
					ctx.waitTicks(30);
					shot(ctx, "17-coiffe-chapeau");
					world.getServer().runCommand("vc reglage coiffe crane");
					ctx.waitTicks(15);
					shot(ctx, "17b-coiffe-crane");
					ctx.runOnClient(mc -> { if (mc.gui.hud.isHidden()) mc.gui.hud.toggle(); });
					world.getServer().runCommand("vc reglage coiffe chapeau");
					world.getServer().runCommand("vc ressusciter " + me);
					ctx.waitTicks(10);
				}
				ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
				ctx.waitTicks(500);
			}
			// Fin de partie : le Conteur tue les joueurs fictifs jusqu'à la victoire d'un camp
			ctx.setScreen(() -> null);
			for (int b = 1; b <= 6; b++) {
				world.getServer().runCommand("vc tuer Bot" + b);
				ctx.waitTicks(5);
			}
			ctx.waitTicks(100);
			ctx.runOnClient(mc -> mc.player.setXRot(-8));
			shot(ctx, "13-revelation");
			// Vue d'ensemble de la place, depuis les airs
			world.getServer().runCommand("gamemode spectator Player0");
			world.getServer().runCommand("execute at @e[tag=vc_hand,limit=1] run tp Player0 ~ ~9 ~19 facing entity @e[tag=vc_hand,limit=1] feet");
			ctx.waitTicks(30);
			shot(ctx, "13b-place-revelation");
			world.getServer().runCommand("gamemode creative Player0");
			ctx.setScreen(fr.valcombe.client.screen.ChronicleScreen::new);
			shot(ctx, "15-chronique");
			ctx.setScreen(() -> null);
			world.getServer().runCommand("vc arreter");
			ctx.waitTicks(20);
			shot(ctx, "15b-remise-en-etat");
			// Fin de partie : la carte est remise en état par petits lots, chacun attend dans la salle d'attente.
			waitBuild(ctx, world);
			String restored = world.getServer().computeOnServer(server -> {
				var level = server.overworld();
				var v = fr.valcombe.mc.ValcombeMod.host().village();
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
					+ java.nio.file.Files.exists(server.getServerDirectory().resolve("config").resolve("valcombe-instantane.nbt"));
			});
			System.out.println("[valcombe-test] remise en état : " + restored);
			check(restored.equals("cassé true | posé true | objets 0 | scène 0 | lanternes true | instantané false"),
				"carte remise en état après la partie : " + restored);

			// Hors partie : clic droit sur un trône pour s'y asseoir
			ctx.setScreen(() -> null);
			world.getServer().runCommand("gamemode adventure " + me);
			var seat = world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().village().seatFeet(0));
			var center = world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().village().center());
			world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %.2f %.2f %.2f facing %.2f %.2f %.2f",
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
			world.getServer().runCommand("ride " + me + " dismount");
			// Vue aérienne de tout le village
			world.getServer().runCommand("gamemode spectator " + me);
			world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d facing %d %d %d", me,
				center.getX(), center.getY() + 34, center.getZ() + 46, center.getX(), center.getY(), center.getZ() + 4));
			ctx.waitTicks(60);
			shot(ctx, "8a-village-vue-aerienne");
			world.getServer().runCommand("gamemode creative " + me);

			// Bibliothèque de cartes : enregistrer, générer un village de 8 trônes, recharger
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
			check(world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().village().seatCount()) == 8, "8 trônes");
			world.getServer().runCommand("gamemode spectator " + me);
			world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d facing %d %d %d", me,
				center.getX(), center.getY() + 30, center.getZ() + 38, center.getX(), center.getY(), center.getZ() + 4));
			ctx.waitTicks(60);
			shot(ctx, "19-village-8-tronees");
			send(ctx, "vc carte charger village-test");
			waitBuild(ctx, world);
			check(world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().village().seatCount()) == 16, "carte rechargée");
			ctx.waitTicks(40);
			shot(ctx, "19b-carte-rechargee");
			world.getServer().runCommand("gamemode creative " + me);
			world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d", me,
				center.getX(), center.getY() + 1, center.getZ() + 4));
			ctx.waitTicks(20);
			checkAtelierScreen(ctx, world, me);
			world.getServer().runCommand("gamemode creative " + me);
			world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d", me,
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
				return fr.valcombe.mc.ValcombeMod.host().importSchematic("essai.schem", out.toByteArray());
			});
			waitBuild(ctx, world); // le terrain est rasé par petits lots, puis la construction est posée
			boolean schemOk = world.getServer().computeOnServer(server -> {
				var c = fr.valcombe.mc.ValcombeMod.host().village().center();
				var corner = c.offset(-1, 0, -1);
				var level = server.overworld();
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
				return fr.valcombe.mc.ValcombeMod.host().importSchematic("essai.litematic", out2.toByteArray());
			});
			waitBuild(ctx, world);
			boolean litOk = world.getServer().computeOnServer(server -> {
				var level = server.overworld();
				var c2 = fr.valcombe.mc.ValcombeMod.host().village().center();
				System.out.println("[valcombe-test] import litematic : " + level.getBlockState(c2.offset(-1, 0, 0)) + " ; " + level.getBlockState(c2));
				return level.getBlockState(c2.offset(-1, 0, 0)).is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK)
					&& level.getBlockState(c2).is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK);
			});
			check(schemOk, "import d'une construction .schem");
			check(litOk, "import d'une construction .litematic");
			System.out.println("[valcombe-test] import : " + imported);

			// Une seule carte à la fois : un village bâti ailleurs retire l'ancienne carte du monde, où qu'elle soit.
			ctx.setScreen(() -> null);
			world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d", me,
				center.getX() + 170, center.getY() + 1, center.getZ()));
			ctx.waitTicks(60);
			// Un bloc posé très haut sur la carte faite à la main : la sauvegarde doit le contenir, puisqu'il sera rasé.
			world.getServer().runOnServer(server -> server.overworld().setBlockAndUpdate(center.above(40),
				net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK.defaultBlockState()));
			send(ctx, "vc carte construire");
			waitBuild(ctx, world);
			String tall = world.getServer().computeOnServer(server -> {
				int height = fr.valcombe.mc.ValcombeMod.maps().list().stream().filter(d -> d.id.startsWith("sauvegarde-"))
					.mapToInt(d -> d.hauteur).max().orElse(0);
				return "rasé " + server.overworld().getBlockState(center.above(40)).isAir() + " | sauvegardé jusque-là " + (height >= 45);
			});
			check(tall.equals("rasé true | sauvegardé jusque-là true"), "carte haute sauvegardée en entier avant d'être rasée : " + tall);
			var far = world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().village().center());
			var farSeat = world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().village()
				.markerPos(new fr.valcombe.mc.Village.Hit(fr.valcombe.mc.Village.Mark.SEAT, 0)));
			String first = world.getServer().computeOnServer(server -> {
				var level = server.overworld();
				// La construction importée (bloc d'or au centre) a disparu, et comme elle était faite à la main,
				// elle a d'abord été sauvegardée dans la bibliothèque.
				long backups = fr.valcombe.mc.ValcombeMod.maps().list().stream().filter(d -> d.id.startsWith("sauvegarde-")).count();
				return "ailleurs " + (far.getX() == center.getX() + 170) + " | ancienne retirée "
					+ (level.getBlockState(center).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) && level.getBlockState(center.above()).isAir())
					+ " | sauvegardes " + (backups >= 1) + " | trône " + level.getBlockState(farSeat).is(net.minecraft.world.level.block.Blocks.BARRIER);
			});
			System.out.println("[valcombe-test] second village : " + first);
			check(first.equals("ailleurs true | ancienne retirée true | sauvegardes true | trône true"), "second village : " + first);
			world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d", me,
				center.getX(), center.getY() + 1, center.getZ() + 4));
			ctx.waitTicks(60);

			// Le village de Valcombe : rebâti à l'ancien endroit, il ne laisse rien du village précédent.
			send(ctx, "vc carte construire port");
			waitBuild(ctx, world);
			ctx.waitTicks(20);
			String second = world.getServer().computeOnServer(server -> {
				var level = server.overworld();
				var v = fr.valcombe.mc.ValcombeMod.host().village();
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
				boolean flat = true;
				for (var pos : net.minecraft.core.BlockPos.betweenClosed(far.offset(-60, 1, -60), far.offset(60, 12, 60))) {
					flat &= level.getBlockState(pos).isAir();
				}
				return "place rasée " + (level.getBlockState(far).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) && flat)
					+ " | trône " + level.getBlockState(farSeat).isAir() + " | trônes sculptés " + thrones + " dont anciens " + stale;
			});
			System.out.println("[valcombe-test] village rebâti : " + second);
			check(second.equals("place rasée true | trône true | trônes sculptés 16 dont anciens 0"), "un seul village à la fois : " + second);
			// À la fin du chantier, les joueurs sont ramenés à la salle d'attente : on revient sur la place pour la photo.
			var port = world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().village().center());
			world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d 180 0", me,
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
				var host = fr.valcombe.mc.ValcombeMod.host();
				var p = server.getPlayerList().getPlayerByName(me);
				String msg = host.useTool(p, fr.valcombe.mc.ValcombeUi.MapTool.HOUSE, spot, true);
				return msg + " | maisons " + host.village().houseCount() + " | toit "
					+ !server.overworld().getBlockState(spot.above(5)).isAir();
			});
			System.out.println("[valcombe-test] maison bâtie : " + built);
			check(built.contains("maisons 17") && built.endsWith("toit true"), "maison bâtie d'un clic");
			world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d facing %d %d %d", me,
				port.getX() + 5, port.getY() + 4, port.getZ() + 12, spot.getX(), spot.getY() + 3, spot.getZ()));
			ctx.waitTicks(40);
			shot(ctx, "25b-maison-batie");
			boolean edited = world.getServer().computeOnServer(server -> {
				var host = fr.valcombe.mc.ValcombeMod.host();
				var p = server.getPlayerList().getPlayerByName(me);
				var v = host.village();
				String erased = host.useTool(p, fr.valcombe.mc.ValcombeUi.MapTool.ERASE, spot, false);
				boolean gone = server.overworld().getBlockState(spot.above(5)).isAir();
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
			// Éditeur du mode construction : catalogue de structures, pose tournée, sélection, opérations, annuler et rétablir
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
			world.getServer().runCommand("vc bots 2");
			world.getServer().runCommand("vc lancer humain");
			ctx.waitTicks(20);
			// Salle de convocation : le Conteur y reçoit un joueur à l'écart (ici, lui-même, faute d'autre joueur réel)
			check(world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().summon("personne", me)), "salle de convocation");
			ctx.runOnClient(mc -> mc.player.setXRot(5));
			ctx.waitTicks(40);
			shot(ctx, "26-salle-de-convocation");
			world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d 180 0", me,
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
			world.getServer().runCommand("vc nuit");
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
			world.getServer().runCommand("gamemode spectator " + me);
			var ctr = world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().village().center());
			world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d facing %d %d %d", me,
				ctr.getX(), ctr.getY() + 9, ctr.getZ() + 16, ctr.getX(), ctr.getY() + 1, ctr.getZ()));
			ctx.waitTicks(40);
			shot(ctx, "23-conteur-roles");
			world.getServer().runCommand("gamemode creative " + me);
			// Bâton du Conteur : viser un joueur montre son rôle ; clic droit ouvre sa ligne du grimoire
			ctx.runOnClient(mc -> {
				var inv = mc.player.getInventory();
				for (int i = 0; i < 9; i++) {
					if (inv.getItem(i).is(net.minecraft.world.item.Items.BREEZE_ROD)) {
						inv.setSelectedSlot(i);
					}
				}
			});
			world.getServer().runCommand("execute as " + me + " at @s facing entity @e[tag=vc_seat_1,limit=1] feet run tp @s ~ ~ ~ ~ ~");
			ctx.waitTicks(20);
			shot(ctx, "18-baton-conteur");
			ctx.runOnClient(mc -> mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND));
			ctx.waitTicks(15);
			shot(ctx, "18b-baton-grimoire");
			ctx.setScreen(() -> null);
			checkTablet(ctx);
			send(ctx, "vc stats");
			world.getServer().runCommand("vc arreter");
			waitBuild(ctx, world); // la carte est remise en état avant de passer à la suite
			// L'Atelier, puis le scénario du Bal masqué qu'il a choisi : écran Scénarios et rôle créé dans le script
			checkAtelier(ctx);
			ctx.setScreen(fr.valcombe.client.screen.ScenarioScreen::new);
			shot(ctx, "20-scenarios");
			ctx.setScreen(fr.valcombe.client.screen.ScriptScreen::new);
			shot(ctx, "20b-script-avec-role-cree");
			ctx.setScreen(() -> null);

			// Phase 12 : prochaine soirée (description du serveur), carnet pour le launcher, daltonien, présentoir
			world.getServer().runCommand("vc soiree vendredi 21 h");
			ctx.waitTicks(60);
			check(world.getServer().computeOnServer(server -> server.getMotd()).contains("vendredi 21 h"), "prochaine soirée annoncée");
			check(ctx.computeOnClient(mc -> java.nio.file.Files.exists(mc.gameDirectory.toPath().resolve("valcombe-carnet.json"))),
				"carnet écrit pour le launcher");
			ctx.runOnClient(mc -> fr.valcombe.client.ClientConfig.get().daltonien = true);
			ctx.setScreen(fr.valcombe.client.screen.ScriptScreen::new);
			shot(ctx, "21-daltonien");
			ctx.runOnClient(mc -> fr.valcombe.client.ClientConfig.get().daltonien = false);
			ctx.setScreen(() -> null);
			var hall = world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().village().hallSpot());
			if (hall != null) {
				world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %.2f %.2f %.2f facing %.2f %.2f %.2f",
					me, hall.x, hall.y, hall.z + 1.5, hall.x, hall.y + 3.2, hall.z - 6));
				ctx.waitTicks(60);
				shot(ctx, "22-salle-presentoir");
				world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %.2f %.2f %.2f facing %.2f %.2f %.2f",
					me, hall.x + 3, hall.y, hall.z, hall.x - 8, hall.y + 2.5, hall.z));
				ctx.waitTicks(20);
				shot(ctx, "22b-salle-panneau-mur");
			}

			// Arrêt du serveur en pleine partie, sans partie à reprendre : au démarrage, la carte est tout de même remise en état.
			var gap = world.getServer().computeOnServer(server -> {
				var host = fr.valcombe.mc.ValcombeMod.host();
				var pos = host.village().center().offset(3, 0, 3);
				host.gameStarted(); // l'instantané d'une partie qui ne reprendra pas
				server.overworld().setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
				host.resumeRestore(); // ce que fait le démarrage du serveur
				return pos;
			});
			check(world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().busy()), "remise en état reprise au démarrage");
			waitBuild(ctx, world);
			check(world.getServer().computeOnServer(server -> !server.overworld().getBlockState(gap).isAir()), "bloc revenu après la reprise");

			// Carte oubliée : rien n'est détruit, mais la prochaine carte bâtie — même ailleurs — la retire du monde.
			var forgotten = world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().village().center());
			send(ctx, "vc carte oublier");
			check(world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().village() == null
				&& fr.valcombe.mc.Village.load(server).state() == fr.valcombe.mc.Village.Fiche.FORGOTTEN
				&& !server.overworld().getBlockState(gap).isAir()), "carte oubliée, blocs en place");
			world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d", me,
				forgotten.getX() - 170, forgotten.getY() + 1, forgotten.getZ()));
			ctx.waitTicks(60);
			send(ctx, "vc carte construire monastere");
			waitBuild(ctx, world);
			var cloister = world.getServer().computeOnServer(server -> fr.valcombe.mc.ValcombeMod.host().village());
			var cloisterSeat = cloister.markerPos(new fr.valcombe.mc.Village.Hit(fr.valcombe.mc.Village.Mark.SEAT, 0));
			check(world.getServer().computeOnServer(server -> server.overworld().getBlockState(forgotten).is(
				net.minecraft.world.level.block.Blocks.GRASS_BLOCK) && server.overworld().getBlockState(forgotten.above()).isAir()
				&& server.overworld().getBlockState(gap).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)),
				"la carte oubliée est retirée quand une autre est bâtie");
			// /vc carte nouvelle sur la carte actuelle : ses blocs restent (on y a peut-être bâti à la main), seuls les repères repartent de zéro.
			world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp %s %d %d %d", me,
				cloister.center().getX(), cloister.center().getY() + 1, cloister.center().getZ() + 6));
			ctx.waitTicks(40);
			send(ctx, "vc carte nouvelle");
			waitBuild(ctx, world);
			String fresh = world.getServer().computeOnServer(server -> {
				var v = fr.valcombe.mc.ValcombeMod.host().village();
				int thrones = 0;
				for (var e : server.overworld().getAllEntities()) {
					if (e.entityTags().contains("vc_trone")) {
						thrones++;
					}
				}
				return "à la main " + !v.isGenerated() + " | repères " + v.seatCount() + " | blocs gardés "
					+ server.overworld().getBlockState(cloisterSeat).is(net.minecraft.world.level.block.Blocks.BARRIER)
					+ " | trônes sculptés " + thrones + " | à protéger " + v.modified();
			});
			System.out.println("[valcombe-test] nouvelle carte : " + fresh);
			check(fresh.equals("à la main true | repères 0 | blocs gardés true | trônes sculptés 0 | à protéger true"), "nouvelle carte sur place : " + fresh);
		}
	}
}
