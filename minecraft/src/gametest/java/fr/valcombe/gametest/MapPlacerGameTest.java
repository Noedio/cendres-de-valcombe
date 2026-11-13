package fr.valcombe.gametest;

import fr.valcombe.client.ClientState;
import fr.valcombe.mc.MapMarks;
import fr.valcombe.mc.MapPlacer;
import fr.valcombe.mc.MapStore;
import fr.valcombe.mc.PlacedMap;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.GameException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Cartes de la bibliothèque dans les mondes de Valcombe, en jeu : une carte bâtie dans un monde plat est capturée
 * (zone bâtie retrouvée, tour haute, bloc lointain, trou creusé, coffre rempli, barrières, escalier), posée par
 * {@code /vc carte poser} dans un monde neuf (désigné par son nom), comparée bloc à bloc et repère à repère, retouchée,
 * capturée par {@code /vc carte capturer}, reposée ailleurs dans un troisième monde et comparée de nouveau. Puis :
 * une nouvelle pose retire l'ancienne carte du monde, une pose interrompue par la remise à neuf du monde échoue
 * proprement, et le monde principal est refusé.
 */
public class MapPlacerGameTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
		System.out.println("[valcombe-test] cartes des mondes : " + what);
	}

	private static void send(ClientGameTestContext ctx, String cmd) {
		ctx.runOnClient(mc -> ClientState.send(cmd));
		ctx.waitTicks(10);
	}

	/** Attend (au plus {@code ticks}) qu'une condition soit vraie sur le serveur ; renvoie si elle l'est devenue. */
	private static boolean waitFor(ClientGameTestContext ctx, TestSingleplayerContext world, int ticks, Predicate<MinecraftServer> cond) {
		for (int i = 0; i < ticks; i += 5) {
			if (world.getServer().computeOnServer(cond::test)) {
				return true;
			}
			ctx.waitTicks(5);
		}
		return world.getServer().computeOnServer(cond::test);
	}

	private static WorldManager worlds() {
		return ValcombeMod.worlds();
	}

	private static MapPlacer placer() {
		return ValcombeMod.placer();
	}

	private static ServerLevel level(String id) {
		return worlds().level(id);
	}

	/** La fiche de la bibliothèque qui porte cet identifiant, ou null. */
	private static MapStore.MapDef libraryDef(String id) {
		return ValcombeMod.maps().list().stream().filter(d -> d.id.equals(id)).findFirst().orElse(null);
	}

	private static final BlockPos CENTER = new BlockPos(0, WorldManager.SURFACE_Y, 0);
	private static final BlockPos TOWER = new BlockPos(5, WorldManager.SURFACE_Y + 1, 5);
	private static final int TOWER_TOP = 110;
	private static final BlockPos FAR = new BlockPos(60, WorldManager.SURFACE_Y + 1, -45);
	private static final BlockPos HOLE = new BlockPos(-30, WorldManager.SURFACE_Y, 30);
	private static final BlockPos CHEST = new BlockPos(-2, WorldManager.SURFACE_Y + 1, -2);
	private static final BlockPos STAIRS = new BlockPos(-3, WorldManager.SURFACE_Y + 1, 2);
	private static final BlockPos FENCE = new BlockPos(2, WorldManager.SURFACE_Y + 1, -3);

	/** Bâtit la carte d'essai dans ce monde plat, autour de CENTER. */
	private static void build(ServerLevel level) {
		for (int x = -4; x <= 4; x++) {
			for (int z = -4; z <= 4; z++) {
				level.setBlockAndUpdate(CENTER.offset(x, 0, z), Blocks.STONE_BRICKS.defaultBlockState()); // la place pavée
			}
		}
		for (int y = TOWER.getY(); y <= TOWER_TOP; y++) {
			level.setBlockAndUpdate(new BlockPos(TOWER.getX(), y, TOWER.getZ()), Blocks.OAK_PLANKS.defaultBlockState());
		}
		level.setBlockAndUpdate(FAR, Blocks.GOLD_BLOCK.defaultBlockState());
		level.setBlockAndUpdate(HOLE, Blocks.AIR.defaultBlockState());
		level.setBlockAndUpdate(STAIRS, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
		level.setBlockAndUpdate(FENCE, Blocks.OAK_FENCE.defaultBlockState());
		level.setBlockAndUpdate(FENCE.east(), Blocks.OAK_FENCE.defaultBlockState()); // les deux poteaux se raccordent
		level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
		if (level.getBlockEntity(CHEST) instanceof ChestBlockEntity chest) {
			chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
		}
	}

	/** Les repères de la carte d'essai : 5 trônes autour de la place (le dernier tourné), 2 maisons, une salle de convocation. */
	private static MapStore.MapDef layout() {
		MapStore.MapDef d = new MapStore.MapDef();
		d.id = "brouillon";
		d.nom = "Brouillon";
		d.genere = true; // ses trônes sculptés sont des entités : la pose doit les remettre
		d.theme = "gothique";
		d.places = 5;
		d.taille = 0;
		d.maisons = false;
		d.rayon = 20;
		d.hauteur = 28;
		d.sieges = new ArrayList<>(List.of(new int[] {6, 1, 0}, new int[] {0, 1, 6}, new int[] {-6, 1, 0}, new int[] {0, 1, -6},
			new int[] {4, 1, -4}));
		d.logis = new ArrayList<>(List.of(new int[] {12, 1, 3}, new int[] {-12, 1, -3}));
		d.convocation = new int[] {0, 1, 15};
		d.orientations = new ArrayList<>(List.of(new double[] {4, 1, -4, 45}));
		return d;
	}

	/**
	 * Compare deux poses bloc à bloc : la boîte [min, max] du monde a contre la même boîte décalée de offset dans le monde
	 * b. Renvoie null si tout est pareil, sinon la première différence et leur nombre.
	 */
	private static String differences(ServerLevel a, ServerLevel b, BlockPos min, BlockPos max, BlockPos offset) {
		int n = 0;
		String first = null;
		for (BlockPos p : BlockPos.betweenClosed(min, max)) {
			var sa = a.getBlockState(p);
			var sb = b.getBlockState(p.offset(offset));
			if (sa != sb) {
				n++;
				if (first == null) {
					first = p.toShortString() + " : " + sa + " / " + sb;
				}
			}
		}
		return n == 0 ? null : n + " différence(s), la première en " + first;
	}

	/** Les mêmes repères (relatifs au centre) et les mêmes orientations de trônes. */
	private static boolean sameMarks(PlacedMap a, PlacedMap b) {
		List<int[]> ra = a.relativeMarks();
		List<int[]> rb = b.relativeMarks();
		if (ra.size() != rb.size() || a.marks().seats().size() != b.marks().seats().size()) {
			return false;
		}
		for (int i = 0; i < ra.size(); i++) {
			if (!java.util.Arrays.equals(ra.get(i), rb.get(i))) {
				return false;
			}
		}
		for (int i = 0; i < a.marks().seats().size(); i++) {
			MapMarks.Seat sa = a.marks().seats().get(i);
			MapMarks.Seat sb = b.marks().seats().get(i);
			if (Math.abs(sa.yaw() - sb.yaw()) > 1e-3 || sa.turned() != sb.turned()) {
				return false;
			}
		}
		return true;
	}

	/** Les trônes sculptés (entités) de ce monde. */
	private static List<Entity> thrones(ServerLevel level) {
		List<Entity> out = new ArrayList<>();
		for (Entity e : level.getAllEntities()) {
			if (e.entityTags().contains("vc_trone")) {
				out.add(e);
			}
		}
		return out;
	}

	/** Le coffre de la carte est-il là, avec ses 3 diamants ? */
	private static boolean chestFilled(ServerLevel level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof ChestBlockEntity chest && chest.getItem(0).is(Items.DIAMOND)
			&& chest.getItem(0).getCount() == 3;
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			ctx.waitTicks(60);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			var server = world.getServer();
			server.runCommand("op " + me);
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> worlds() != null && placer() != null), "mondes et pose des cartes prêts");

			long stamp = System.currentTimeMillis() % 100000;
			String mapA = "test-pose-" + stamp;
			String mapB = "test-pose-" + stamp + "-b";
			String nameB = "Carte posee " + stamp;

			// Monde A : la carte d'essai bâtie à la main, puis capturée avec ses repères.
			String a = server.computeOnServer(s -> worlds().create(WorldKind.ATELIER_CARTE, "Atelier pose " + stamp).id());
			server.runOnServer(s -> build(level(a)));
			check(server.computeOnServer(s -> level(a).getBlockState(TOWER.atY(TOWER_TOP)).is(Blocks.OAK_PLANKS)), "carte d'essai bâtie");
			MapPlacer.Capture first = server.computeOnServer(s -> placer().capture(level(a),
				PlacedMap.of(layout(), level(a).dimension(), CENTER), mapA, "Essai de pose"));
			check(first.map().rayon() == FAR.getX(), "zone bâtie retrouvée jusqu'au bloc lointain : rayon " + first.map().rayon());
			check(first.map().hauteur() == TOWER_TOP - (CENTER.getY() - 4) + 2, "hauteur jusqu'en haut de la tour : " + first.map().hauteur());
			check(!first.cutTop() && !first.replaced(), "rien de coupé, aucune carte remplacée");
			check(server.computeOnServer(s -> {
				MapStore.MapDef d = libraryDef(mapA);
				return d != null && d.sieges.size() == 5 && d.logis.size() == 2 && d.convocation != null && d.orientations.size() == 1
					&& ValcombeMod.maps().preview(mapA) != null;
			}), "carte rangée dans la bibliothèque, aperçu compris");
			check(server.computeOnServer(s -> mapA.equals(worlds().get(a).carte())), "le monde A porte la carte capturée");

			// Monde B, neuf : la carte posée par la commande, le monde désigné par son nom.
			String b = server.computeOnServer(s -> worlds().create(WorldKind.ESSAI, nameB).id());
			check(server.computeOnServer(s -> placer().placed(level(b)) == null), "un monde neuf ne porte aucune carte");
			send(ctx, "vc carte poser " + mapA + " " + nameB.toLowerCase());
			check(waitFor(ctx, world, 1200, s -> placer().placed(level(b)) != null && !placer().busy(level(b))), "carte posée dans B");
			PlacedMap inA = first.map();
			check(server.computeOnServer(s -> {
				String diff = differences(level(a), level(b), inA.min(), inA.max(), BlockPos.ZERO);
				if (diff != null) {
					System.out.println("[valcombe-test] A / B : " + diff);
				}
				return diff == null;
			}), "B est bloc pour bloc la carte de A (" + (2 * inA.rayon() + 1) + " × " + inA.hauteur() + " × " + (2 * inA.rayon() + 1) + ")");
			check(server.computeOnServer(s -> level(b).getBlockState(TOWER.atY(TOWER_TOP)).is(Blocks.OAK_PLANKS)
				&& level(b).getBlockState(HOLE).isAir() && level(b).getBlockState(FENCE).getValue(CrossCollisionBlock.EAST)
				&& level(b).getBlockState(STAIRS).getValue(StairBlock.FACING) == Direction.EAST), "tour, trou, barrières raccordées, escalier");
			check(server.computeOnServer(s -> chestFilled(level(b), CHEST)), "coffre posé avec son contenu");
			check(server.computeOnServer(s -> sameMarks(inA, placer().placed(level(b)))
				&& placer().placed(level(b)).center().equals(CENTER)), "repères posés dans B");
			check(server.computeOnServer(s -> {
				List<Entity> t = thrones(level(b));
				PlacedMap pb = placer().placed(level(b));
				MapMarks.Seat turned = pb.marks().seats().get(4);
				return t.size() == 5 && t.stream().anyMatch(e -> e.blockPosition().getX() == turned.pos().getX()
					&& e.blockPosition().getZ() == turned.pos().getZ() && Math.abs(e.getYRot() - 45) < 0.5f);
			}), "5 trônes sculptés, le trône tourné regarde à 45°");
			check(server.computeOnServer(s -> mapA.equals(worlds().get(b).carte())), "la fiche de B note sa carte");

			// Une capture dans la console du joueur : on va voir la carte dans B.
			send(ctx, "vc monde aller " + b);
			check(waitFor(ctx, world, 100, s -> s.getPlayerList().getPlayerByName(me).level() == level(b)), "joueur dans B");
			ctx.waitTicks(40);
			ctx.runOnClient(mc -> {
				mc.player.setYRot(-150);
				mc.player.setXRot(-15);
			});
			ctx.waitTicks(40);
			System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-carte-posee"));

			// B retouché (un bloc ajouté, le bloc lointain retiré), puis capturé par la commande.
			BlockPos added = new BlockPos(1, WorldManager.SURFACE_Y + 2, 1);
			server.runOnServer(s -> {
				level(b).setBlockAndUpdate(added, Blocks.DIAMOND_BLOCK.defaultBlockState());
				level(b).setBlockAndUpdate(FAR, Blocks.AIR.defaultBlockState());
			});
			send(ctx, "vc carte capturer " + b + " " + mapB + " Essai recapturé");
			check(waitFor(ctx, world, 100, s -> libraryDef(mapB) != null), "B capturé dans la bibliothèque");
			check(server.computeOnServer(s -> {
				MapStore.MapDef d = libraryDef(mapB);
				return d.nom.equals("Essai recapturé") && d.rayon == inA.rayon() && d.hauteur == inA.hauteur() && d.genere
					&& mapB.equals(worlds().get(b).carte()) && placer().placed(level(b)).mapId().equals(mapB);
			}), "capture de B : nom, emprise gardée, le monde porte la nouvelle carte");

			// Monde C : la capture de B reposée ailleurs que sur le centre du monde, puis comparée à B.
			String c = server.computeOnServer(s -> worlds().create(WorldKind.ESSAI, "Carte reposee " + stamp).id());
			BlockPos offset = new BlockPos(40, 0, -20);
			AtomicReference<MapPlacer.Placement> third = new AtomicReference<>();
			server.runOnServer(s -> third.set(placer().place(mapB, level(c), CENTER.offset(offset), null)));
			check(third.get().map().center().equals(CENTER.offset(offset)), "repères connus dès le lancement de la pose");
			check(waitFor(ctx, world, 1200, s -> third.get().done().isDone()), "pose dans C terminée");
			check(!third.get().done().isCompletedExceptionally(), "pose dans C sans erreur");
			check(server.computeOnServer(s -> {
				String diff = differences(level(b), level(c), inA.min(), inA.max(), offset);
				if (diff != null) {
					System.out.println("[valcombe-test] B / C : " + diff);
				}
				return diff == null;
			}), "C est bloc pour bloc la carte de B, décalée de " + offset.toShortString());
			check(server.computeOnServer(s -> level(c).getBlockState(added.offset(offset)).is(Blocks.DIAMOND_BLOCK)
				&& level(c).getBlockState(FAR.offset(offset)).isAir() && chestFilled(level(c), CHEST.offset(offset))),
				"la retouche de B est dans C, le bloc retiré n'y est pas, coffre plein");
			check(server.computeOnServer(s -> {
				PlacedMap pc = placer().placed(level(c));
				PlacedMap pb = placer().placed(level(b));
				return sameMarks(pb, pc) && pc.center().equals(CENTER.offset(offset))
					&& pc.marks().equals(pb.marks().movedTo(CENTER.offset(offset)));
			}), "repères de C : ceux de B, décalés");

			// Une nouvelle pose dans B, ailleurs : l'ancienne carte est retirée (sol plat, trônes enlevés).
			BlockPos away = new BlockPos(150, WorldManager.SURFACE_Y, 0);
			AtomicReference<MapPlacer.Placement> moved = new AtomicReference<>();
			server.runOnServer(s -> moved.set(placer().place(mapA, level(b), away, null)));
			check(waitFor(ctx, world, 1200, s -> moved.get().done().isDone() && !moved.get().done().isCompletedExceptionally()),
				"nouvelle pose dans B");
			check(server.computeOnServer(s -> level(b).getBlockState(TOWER.atY(TOWER_TOP)).isAir()
				&& level(b).getBlockState(CENTER).is(Blocks.GRASS_BLOCK) && level(b).getBlockState(HOLE).is(Blocks.GRASS_BLOCK)
				&& level(b).getBlockState(added).isAir() && level(b).getBlockEntity(CHEST) == null), "ancienne carte de B retirée, sol plat");
			check(server.computeOnServer(s -> {
				List<Entity> t = thrones(level(b));
				PlacedMap pb = placer().placed(level(b));
				return t.size() == 5 && t.stream().allMatch(e -> pb.contains(e.blockPosition())) && pb.center().equals(away);
			}), "seuls les trônes de la nouvelle carte restent");

			// Le monde remis à neuf pendant une pose : la pose échoue proprement, le monde neuf ne porte rien.
			AtomicReference<MapPlacer.Placement> cut = new AtomicReference<>();
			AtomicReference<CompletableFuture<ServerLevel>> recreated = new AtomicReference<>();
			server.runOnServer(s -> {
				cut.set(placer().place(mapA, level(c), CENTER, null));
				recreated.set(worlds().recreate(c));
			});
			check(waitFor(ctx, world, 1200, s -> recreated.get().isDone() && cut.get().done().isDone()), "remise à neuf de C terminée");
			check(cut.get().done().isCompletedExceptionally(), "pose interrompue par la remise à neuf");
			check(!recreated.get().isCompletedExceptionally(), "remise à neuf sans erreur");
			check(server.computeOnServer(s -> placer().placed(level(c)) == null && !placer().busy(level(c))
				&& level(c).getBlockState(CENTER.offset(offset)).is(Blocks.GRASS_BLOCK)), "C neuf, sans carte");

			// Le monde principal n'est pas un monde de Valcombe : refusé, sans rien lancer.
			check(server.computeOnServer(s -> {
				try {
					placer().place(mapA, s.overworld(), CENTER, null);
					return false;
				} catch (GameException e) {
					return true;
				}
			}), "pose refusée dans le monde principal");

			// Ménage : les mondes et les cartes d'essai.
			send(ctx, "vc monde aller principal");
			for (String id : List.of(a, b, c)) {
				server.runOnServer(s -> worlds().delete(id));
			}
			check(waitFor(ctx, world, 1200, s -> worlds().find(a) == null && worlds().find(b) == null && worlds().find(c) == null
				&& !worlds().busy(a) && !worlds().busy(b) && !worlds().busy(c)), "mondes d'essai supprimés");
			server.runOnServer(s -> {
				ValcombeMod.maps().delete(mapA);
				ValcombeMod.maps().delete(mapB);
			});
			check(server.computeOnServer(s -> !ValcombeMod.maps().exists(mapA) && !ValcombeMod.maps().exists(mapB)), "cartes d'essai supprimées");
		}
	}
}
