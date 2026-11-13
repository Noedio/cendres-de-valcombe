package fr.valcombe.gametest;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.screen.MapWorkshopOpenScreen;
import fr.valcombe.client.screen.MapWorkshopScreen;
import fr.valcombe.mc.MapVersions;
import fr.valcombe.mc.MapWorkshop;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.ValcombeUi;
import fr.valcombe.mc.Village;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.GameException;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * L'atelier carte, en jeu, de bout en bout.
 * <ol>
 * <li>Un village généré dans son propre atelier ; « Enregistrer » le range dans la bibliothèque (avec une version) ;
 * « Fermer l'atelier » supprime son monde, le joueur attend au hub.</li>
 * <li>L'écran « Ouvrir une carte dans un atelier » rouvre cette carte de la bibliothèque : un monde neuf, la carte posée
 * au centre, le joueur conduit dedans ; un seul atelier par carte.</li>
 * <li>Un bloc posé et un trône ajouté avec l'outil « Carte : trône » ; captures de l'atelier (étiquettes des repères, panneau
 * de la place, compteur au-dessus de la barre d'objets) et de son écran.</li>
 * <li>« Enregistrer » depuis l'écran, « Fermer l'atelier » (confirmé), puis la carte rouverte : le bloc et le trône sont
 * là.</li>
 * <li>Enregistrement automatique (une version, rien de plus quand rien ne change), retour à une version.</li>
 * <li>« Envoyer à la partie » : la partie adopte la carte, trône ajouté compris ; l'atelier reste ouvert.</li>
 * <li>Poser suit la règle de casser : un opérateur en survie au hub ne pose rien (en créatif, si).</li>
 * </ol>
 */
public class AtelierCarteGameTest implements FabricClientGameTest {
	private static final int S = WorldManager.SURFACE_Y;
	private static final BlockPos CENTER = new BlockPos(0, S, 0);

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
		System.out.println("[valcombe-test] atelier carte : " + what);
	}

	private static void send(ClientGameTestContext ctx, String cmd) {
		ctx.runOnClient(mc -> ClientState.send(cmd));
		ctx.waitTicks(10);
	}

	private static void shot(ClientGameTestContext ctx, String name) {
		ctx.waitTicks(10);
		System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-atelier-carte-" + name));
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

	private static MapWorkshop mw() {
		return ValcombeMod.mapWorkshop();
	}

	private static ServerPlayer player(MinecraftServer s, String name) {
		return s.getPlayerList().getPlayerByName(name);
	}

	private static String dimensionOf(MinecraftServer s, String name) {
		return player(s, name).level().dimension().identifier().toString();
	}

	/** L'atelier qui porte cette carte, ou null. */
	private static String atelierOf(String mapId) {
		WorldEntry e = mw().atelierOf(mapId);
		return e == null ? null : e.id();
	}

	private static ServerLevel level(String worldId) {
		return ValcombeMod.worlds().level(worldId);
	}

	/** Le joueur est-il dans cet atelier, et l'atelier prêt (rien en cours de pose) ? */
	private static boolean inReadyAtelier(MinecraftServer s, String me, String worldId) {
		return worldId != null && ValcombeMod.worlds().find(worldId) != null && mw().idle(worldId)
			&& dimensionOf(s, me).equals("valcombe:" + worldId);
	}

	/** La session survit d'une classe de test à l'autre : on repart d'un salon vide, sans partie ni Conteur. */
	private static void cleanSession(TestSingleplayerContext world, String me) {
		world.getServer().runOnServer(s -> {
			var session = ValcombeMod.session();
			if (session.game() != null) {
				session.stop();
			}
			if (me.equals(session.storyteller())) {
				session.toggleStoryteller(me);
			}
			if (session.lobby().contains(me)) {
				session.leave(me);
			}
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
			server.runCommand("gamemode creative " + me);
			ctx.waitTicks(40);
			cleanSession(world, me);
			check(server.computeOnServer(s -> mw() != null && ValcombeMod.maps() != null), "atelier carte disponible");
			String[] mapId = {null};
			try {
				run(ctx, world, me, mapId);
			} finally {
				// Ménage : l'atelier de la carte d'essai et la carte elle-même (ses versions partent avec elle).
				server.runOnServer(s -> {
					if (mapId[0] == null) {
						return;
					}
					WorldEntry e = mw().atelierOf(mapId[0]);
					if (e != null && !ValcombeMod.worlds().busy(e.id())) {
						ValcombeMod.worlds().delete(e.id());
					}
					if (ValcombeMod.maps().exists(mapId[0])) {
						ValcombeMod.maps().delete(mapId[0]);
					}
				});
				ctx.waitTicks(40);
			}
		}
	}

	private void run(ClientGameTestContext ctx, TestSingleplayerContext world, String me, String[] mapIdOut) {
		var server = world.getServer();
		String hubDim = "valcombe:" + server.computeOnServer(s -> ValcombeMod.hub().id());
		String gameId = server.computeOnServer(s -> ValcombeMod.host().worldId());
		String name = "Atelier essai " + (System.currentTimeMillis() % 100000);

		// 1. Une carte pour la bibliothèque : un village généré dans son propre atelier, enregistré, puis l'atelier fermé.
		send(ctx, "vc atelier carte village 6 petite gothique oui " + name);
		check(waitFor(ctx, world, 200, s -> mw().ateliers().stream().anyMatch(e -> e.nom().equals(name))), "atelier créé pour le village");
		String first = server.computeOnServer(s -> mw().ateliers().stream().filter(e -> e.nom().equals(name)).findFirst().orElseThrow().id());
		check(waitFor(ctx, world, 3000, s -> inReadyAtelier(s, me, first)), "village bâti dans son atelier, joueur conduit dedans");
		String mapId = server.computeOnServer(s -> ValcombeMod.worlds().find(first).carte());
		mapIdOut[0] = mapId;
		check(mapId != null && server.computeOnServer(s -> !ValcombeMod.maps().exists(mapId)),
			"l'atelier porte la carte « " + mapId + " », pas encore dans la bibliothèque");
		String counted = server.computeOnServer(s -> mw().view(player(s, me)).get("compteur").toString());
		check(counted.equals("6 trônes, 6 maisons, salle de convocation : oui"), "compteur du village : " + counted);
		send(ctx, "vc atelier carte enregistrer");
		check(waitFor(ctx, world, 100, s -> ValcombeMod.maps().exists(mapId) && ValcombeMod.maps().versions().list(mapId).size() == 1),
			"« Enregistrer » : la carte est dans la bibliothèque, avec sa première version");
		send(ctx, "vc atelier carte fermer confirmer");
		check(waitFor(ctx, world, 600, s -> ValcombeMod.worlds().find(first) == null && !ValcombeMod.worlds().busy(first)
			&& dimensionOf(s, me).equals(hubDim)), "atelier fermé : son monde est supprimé, le joueur attend au hub");

		// 2. L'écran « Ouvrir une carte dans un atelier » rouvre la carte de la bibliothèque dans un monde neuf.
		ctx.setScreen(() -> new MapWorkshopOpenScreen(mapId));
		ctx.waitTicks(20);
		check(ctx.computeOnClient(mc -> MapWorkshopOpenScreen.library().stream().anyMatch(o -> mapId.equals(ClientState.str(o, "id")))),
			"la carte est dans la liste de l'écran");
		shot(ctx, "ouvrir");
		check(click(ctx, "Ouvrir dans un atelier"), "bouton « Ouvrir dans un atelier »");
		check(waitFor(ctx, world, 200, s -> atelierOf(mapId) != null), "un atelier porte la carte");
		String second = server.computeOnServer(s -> atelierOf(mapId));
		check(waitFor(ctx, world, 3000, s -> inReadyAtelier(s, me, second)), "carte posée dans l'atelier, joueur conduit dedans");
		String once = server.computeOnServer(s -> mw().openLibrary(mapId, null, false) + " | ateliers "
			+ mw().ateliers().stream().filter(e -> mapId.equals(e.carte())).count());
		check(once.contains("déjà dans son atelier") && once.endsWith("ateliers 1"), "un seul atelier par carte : " + once);
		check(waitFor(ctx, world, 40, s -> {
			var inv = player(s, me).getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (ValcombeUi.mapTool(inv.getItem(i)) == ValcombeUi.MapTool.SEAT) {
					return true;
				}
			}
			return false;
		}), "outils de repères donnés à l'entrée");

		// 3. Un bloc posé au-dessus de la place, et un trône ajouté avec l'outil « Carte : trône ».
		BlockPos block = CENTER.offset(0, 18, 0);
		BlockPos seatClicked = CENTER.offset(9, 0, 0);
		String added = server.computeOnServer(s -> {
			level(second).setBlockAndUpdate(block, Blocks.GOLD_BLOCK.defaultBlockState());
			return mw().useTool(player(s, me), ValcombeUi.MapTool.SEAT, seatClicked, false);
		});
		check(added.startsWith("Trône 7 posé.") && added.contains("7 trônes, 6 maisons"), "trône ajouté : " + added);
		String unsaved = server.computeOnServer(s -> mw().view(player(s, me)).get("modifiee").toString());
		check(unsaved.equals("true"), "l'atelier sait que la carte a changé depuis son enregistrement");

		// 4. Captures : les repères visibles (étiquettes, panneau de la place) et le compteur, puis l'écran de l'atelier.
		server.runOnServer(s -> player(s, me).teleportTo(level(second), 0.5, S + 9, 24.5, Set.of(), 180f, 22f, false));
		check(waitFor(ctx, world, 200, s -> {
			int n = 0;
			for (Entity e : level(second).getAllEntities()) {
				if (e.entityTags().contains(MapWorkshop.TAG)) {
					n++;
				}
			}
			return n >= 1 + 1 + 7 + 6 + 1; // panneau, centre, 7 trônes, 6 maisons, salle
		}), "étiquettes de tous les repères et panneau de la place");
		ctx.waitTicks(30);
		shot(ctx, "reperes");
		ctx.setScreen(MapWorkshopScreen::new);
		ctx.waitTicks(20);
		check(ctx.computeOnClient(mc -> {
			var d = MapWorkshopScreen.data();
			return d != null && "7 trônes, 6 maisons, salle de convocation : oui".equals(ClientState.str(d, "compteur"))
				&& ClientState.bool(d, "jouable");
		}), "l'écran de l'atelier compte les repères");
		shot(ctx, "ecran");

		// 5. « Enregistrer » depuis l'écran : la bibliothèque a le trône ajouté, une seconde version est gardée.
		check(click(ctx, "Enregistrer"), "bouton « Enregistrer »");
		check(waitFor(ctx, world, 100, s -> ValcombeMod.maps().versions().list(mapId).size() == 2
			&& ValcombeMod.maps().list().stream().anyMatch(d -> d.id.equals(mapId) && d.sieges.size() == 7)),
			"carte enregistrée : 7 trônes dans la bibliothèque, deux versions");
		String unchanged = server.computeOnServer(s -> mw().save(player(s, me)));
		check(unchanged.startsWith("Rien n'a changé"), "rien n'est réécrit quand rien n'a changé : " + unchanged);

		// 6. « Fermer l'atelier », confirmé dans l'écran.
		ctx.setScreen(MapWorkshopScreen::new);
		ctx.waitTicks(15);
		check(click(ctx, "Fermer l'atelier…"), "bouton « Fermer l'atelier… »");
		shot(ctx, "fermer");
		check(click(ctx, "Oui, fermer"), "confirmation de la fermeture");
		check(waitFor(ctx, world, 600, s -> ValcombeMod.worlds().find(second) == null && !ValcombeMod.worlds().busy(second)
			&& dimensionOf(s, me).equals(hubDim)), "atelier fermé, joueur au hub");

		// 7. Rouvrir : le bloc et le trône sont là.
		send(ctx, "vc atelier carte ouvrir " + mapId);
		check(waitFor(ctx, world, 200, s -> atelierOf(mapId) != null), "carte rouverte dans un atelier");
		String third = server.computeOnServer(s -> atelierOf(mapId));
		check(waitFor(ctx, world, 3000, s -> inReadyAtelier(s, me, third)), "carte reposée, joueur dans l'atelier");
		String back = server.computeOnServer(s -> {
			Village v = mw().marks(third);
			BlockPos seven = v.seatCount() >= 7 ? v.markerPos(new Village.Hit(Village.Mark.SEAT, 6)) : null;
			return "bloc " + level(third).getBlockState(block).is(Blocks.GOLD_BLOCK) + " | trônes " + v.seatCount() + " | septième "
				+ seatClicked.above().equals(seven) + " | maisons " + v.houseCount() + " | salle " + (v.convocation() != null);
		});
		check(back.equals("bloc true | trônes 7 | septième true | maisons 6 | salle true"), "on retrouve le bloc et le trône : " + back);

		// 8. Enregistrement automatique (une version, et rien de plus quand rien ne change), puis retour à une version.
		BlockPos extra = CENTER.offset(0, 19, 0);
		String auto = server.computeOnServer(s -> {
			level(third).setBlockAndUpdate(extra, Blocks.DIAMOND_BLOCK.defaultBlockState());
			return mw().autosave(third, MapVersions.Kind.AUTO);
		});
		check(auto.startsWith("Enregistrement automatique"), "enregistrement automatique : " + auto);
		check(server.computeOnServer(s -> mw().autosave(third, MapVersions.Kind.AUTO).isEmpty()
			&& ValcombeMod.maps().versions().last(mapId).kind() == MapVersions.Kind.AUTO
			&& ValcombeMod.maps().versions().list(mapId).size() == 3), "rien de changé : pas de nouvelle version");
		String manual = server.computeOnServer(s -> ValcombeMod.maps().versions().list(mapId).stream()
			.filter(v -> v.kind() == MapVersions.Kind.MANUELLE && v.seats() == 7).findFirst().orElseThrow().id());
		send(ctx, "vc atelier carte revenir " + manual);
		check(waitFor(ctx, world, 3000, s -> mw().idle(third) && level(third).getBlockState(extra).isAir()
			&& level(third).getBlockState(block).is(Blocks.GOLD_BLOCK)), "retour à la version enregistrée : le bloc de diamant est parti");

		// 9. « Envoyer à la partie » : la partie adopte la carte (trône ajouté compris), l'atelier reste ouvert.
		check(server.computeOnServer(s -> mw().parties().stream().anyMatch(e -> e.id().equals(gameId))), "la partie principale est proposée");
		send(ctx, "vc atelier carte envoyer " + gameId);
		check(waitFor(ctx, world, 3000, s -> !ValcombeMod.host().busy() && ValcombeMod.host().village() != null
			&& mapId.equals(ValcombeMod.host().village().libraryId())), "la partie a reçu la carte");
		String sent = server.computeOnServer(s -> {
			Village v = ValcombeMod.host().village();
			return "trônes " + v.seatCount() + " | atelier " + (mw().atelierOf(mapId) != null) + " | bloc "
				+ ValcombeMod.host().level().getBlockState(block).is(Blocks.GOLD_BLOCK);
		});
		check(sent.equals("trônes 7 | atelier true | bloc true"), "envoyée telle qu'enregistrée : " + sent);
		String refused = server.computeOnServer(s -> {
			try {
				mw().openBlank("", player(s, me));
				return "acceptée";
			} catch (GameException e) {
				return e.getMessage();
			}
		});
		check(refused.startsWith("Nom de carte"), "une carte vierge sans nom est refusée : " + refused);
		// Ouverte dans son atelier, la carte ne quitte pas la bibliothèque (son enregistrement automatique l'y remettrait).
		String kept = server.computeOnServer(s -> {
			int versions = ValcombeMod.maps().versions().list(mapId).size();
			try {
				ValcombeMod.maps().delete(mapId);
				return "supprimée";
			} catch (GameException e) {
				return e.getMessage() + " | " + ValcombeMod.maps().exists(mapId) + " | "
					+ (versions > 0 && ValcombeMod.maps().versions().list(mapId).size() == versions);
			}
		});
		check(kept.contains("fermez d'abord l'atelier") && kept.endsWith(" | true | true"),
			"une carte ouverte dans un atelier ne se supprime pas, ses versions restent : " + kept);

		// 10. Poser suit la règle de casser : au hub, un opérateur en créatif pose, en survie non.
		send(ctx, "vc hub");
		check(waitFor(ctx, world, 100, s -> dimensionOf(s, me).equals(hubDim)), "au hub");
		String rule = server.computeOnServer(s -> {
			ServerPlayer p = player(s, me);
			// Un bout de sol libre près du joueur (pas sous ses pieds) : un bloc plein, de l'air au-dessus.
			BlockPos floor = p.blockPosition().below().east(2);
			for (int dx = 2; dx <= 6; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					BlockPos f = p.blockPosition().below().offset(dx, 0, dz);
					if (p.level().getBlockState(f).isSolidRender() && p.level().getBlockState(f.above()).isAir()
						&& p.level().getBlockState(f.above(2)).isAir()) {
						floor = f;
						dx = 99;
						break;
					}
				}
			}
			BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false);
			BlockPos target = floor.above();
			boolean free = p.level().getBlockState(target).isAir();
			p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SPONGE, 4));
			InteractionResult creative = p.getItemInHand(InteractionHand.MAIN_HAND).useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, hit));
			boolean placedCreative = p.level().getBlockState(target).is(Blocks.SPONGE);
			p.level().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
			p.setGameMode(GameType.SURVIVAL);
			InteractionResult survival = p.getItemInHand(InteractionHand.MAIN_HAND).useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, hit));
			boolean placedSurvival = p.level().getBlockState(target).is(Blocks.SPONGE);
			int left = p.getItemInHand(InteractionHand.MAIN_HAND).getCount();
			p.setGameMode(GameType.CREATIVE);
			p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			p.level().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
			return "libre " + free + " | créatif " + placedCreative + " (" + creative.consumesAction() + ") | survie " + placedSurvival
				+ " (" + survival.consumesAction() + ") | éponges " + left;
		});
		System.out.println("[valcombe-test] atelier carte : pose au hub : " + rule);
		check(rule.equals("libre true | créatif true (true) | survie false (false) | éponges 4"),
			"au hub, l'opérateur en survie ne pose rien (la même règle que pour casser) : " + rule);
		List<String> worldsLeft = server.computeOnServer(s -> mw().ateliers().stream().map(WorldEntry::id).toList());
		System.out.println("[valcombe-test] atelier carte : ateliers ouverts à la fin : " + worldsLeft);
	}
}
