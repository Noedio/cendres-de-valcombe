package fr.valcombe.gametest;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.Vignettes;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdGrid;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdTabs;
import fr.valcombe.client.screen.MainScreen;
import fr.valcombe.client.screen.atelier.AtelierHome;
import fr.valcombe.client.screen.atelier.AtelierPage;
import fr.valcombe.client.screen.atelier.MapSheetPage;
import fr.valcombe.client.screen.atelier.MapsPage;
import fr.valcombe.client.screen.atelier.NewMapPage;
import fr.valcombe.client.screen.atelier.NewStructurePage;
import fr.valcombe.client.screen.atelier.PartiesPage;
import fr.valcombe.client.screen.atelier.PartySheetPage;
import fr.valcombe.client.screen.atelier.RoleSheetPage;
import fr.valcombe.client.screen.atelier.RolesPage;
import fr.valcombe.client.screen.atelier.ScenarioSheetPage;
import fr.valcombe.client.screen.atelier.StructureSheetPage;
import fr.valcombe.client.screen.atelier.StructuresPage;
import fr.valcombe.mc.MapWorkshop;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.worlds.WorldEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * L'Atelier en jeu refait (Sprint 16, entrée Cartes), de bout en bout.
 * <ol>
 * <li>Une carte pour la bibliothèque : un village généré dans son atelier, enregistré puis l'atelier fermé depuis
 * l'extérieur (« enregistrer &lt;atelier&gt; », « fermer &lt;atelier&gt; confirmer »).</li>
 * <li>Menu J › Atelier : l'accueil à quatre entrées (capture) ; « Cartes » : la bibliothèque, la vignette de la carte
 * faite par le serveur et reçue en texture (capture) ; la recherche et le tri ; sa fiche (capture).</li>
 * <li>Une action réelle : « Dupliquer » (la copie paraît dans la bibliothèque), puis la copie « Supprimer », confirmé :
 * elle part à la corbeille ; « Restaurer » la remet, puis elle est jetée de nouveau.</li>
 * <li>« Nouvelle carte » : les trois étapes (captures), ce qui manque dit et « Suivant » grisé tant qu'il manque ; un
 * fichier .nbt déposé dans le dossier d'import est importé dans un atelier, qui paraît dans « Ateliers ouverts »
 * (capture), puis il est fermé.</li>
 * <li>Structures : le catalogue en vignettes vues de trois quarts (512 × 512, faites par le serveur), « À vous » vide et
 * « Nouvelle structure » mis en avant, le parcours guidé (la sélection manque hors d'un atelier) ; une structure importée,
 * sa fiche, renommée sur place puis supprimée après confirmation.</li>
 * <li>Parties : la liste, la fiche de la partie principale, « Carte en place » (vider le monde se confirme, « Annuler » ne
 * lance rien) ; une partie d'essai créée par la page, sa fiche une fois son village posé, supprimée après confirmation.</li>
 * <li>Rôles et scénarios : un rôle et un scénario créés, leurs fiches (jetons HD), le choix d'une partie ; un pack .valcombe
 * déposé, importé par la page ; le rôle supprimé par sa fiche après confirmation.</li>
 * <li>La mise en page de chaque page à 427 × 240 (fenêtre de 854 × 480 à l'échelle 2), à l'échelle 3, à l'échelle 1 et
 * dans une fenêtre de 2560 × 1440 : aucun widget ne sort du panneau ni n'en chevauche un autre.</li>
 * </ol>
 * Captures {@code valcombe-atelier-hd-*.png}.
 */
public class AtelierHdGameTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
		System.out.println("[valcombe-test] atelier hd : " + what);
	}

	private static void send(ClientGameTestContext ctx, String cmd) {
		ctx.runOnClient(mc -> ClientState.send(cmd));
		ctx.waitTicks(10);
	}

	private static void shot(ClientGameTestContext ctx, String name) {
		ctx.waitTicks(10);
		System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-atelier-hd-" + name));
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

	private static boolean waitClient(ClientGameTestContext ctx, int ticks, Predicate<Minecraft> cond) {
		for (int i = 0; i < ticks; i += 2) {
			if (ctx.computeOnClient(cond::test)) {
				return true;
			}
			ctx.waitTicks(2);
		}
		return ctx.computeOnClient(cond::test);
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

	// ------------------------------------------------------------------
	// L'écran
	// ------------------------------------------------------------------

	private static HdButton find(Minecraft mc, String label) {
		if (mc.gui.screen() == null) {
			return null;
		}
		for (GuiEventListener w : mc.gui.screen().children()) {
			if (w instanceof HdButton b && b.visible && b.getMessage().getString().equals(label)) {
				return b;
			}
		}
		return null;
	}

	private static String buttons(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			StringBuilder out = new StringBuilder();
			if (mc.gui.screen() != null) {
				for (var w : mc.gui.screen().children()) {
					if (w instanceof HdButton b) {
						out.append(b.active ? "[" : "(").append(b.getMessage().getString()).append(b.active ? "] " : ") ");
					}
				}
			}
			return out.toString();
		});
	}

	/** Clique le bouton qui porte ce libellé (il doit être là et actif). */
	private static void press(ClientGameTestContext ctx, String label) {
		boolean done = ctx.computeOnClient(mc -> {
			HdButton b = find(mc, label);
			if (b == null || !b.active) {
				return false;
			}
			b.press();
			return true;
		});
		check(done, "bouton « " + label + " » parmi : " + buttons(ctx));
		ctx.waitTicks(12);
	}

	/** Le bouton est là, grisé. */
	private static boolean greyed(ClientGameTestContext ctx, String label) {
		return ctx.computeOnClient(mc -> {
			HdButton b = find(mc, label);
			return b != null && !b.active;
		});
	}

	/** Le bouton est là, actif et mis en avant (l'action suivante). */
	private static boolean primary(ClientGameTestContext ctx, String label) {
		return ctx.computeOnClient(mc -> {
			HdButton b = find(mc, label);
			return b != null && b.active && b.isPrimary();
		});
	}

	private static void chooseTab(ClientGameTestContext ctx, String label) {
		boolean ok = ctx.computeOnClient(mc -> {
			for (var w : mc.gui.screen().children()) {
				if (w instanceof HdTabs t) {
					for (int i = 0; i < t.labels().size(); i++) {
						if (t.labels().get(i).startsWith(label)) {
							t.choose(i);
							return true;
						}
					}
				}
			}
			return false;
		});
		check(ok, "onglet « " + label + " »");
		ctx.waitTicks(12);
	}

	@SuppressWarnings("unchecked")
	private static HdGrid<JsonObject> grid(Minecraft mc) {
		for (var w : mc.gui.screen().children()) {
			if (w instanceof HdGrid<?> g) {
				return (HdGrid<JsonObject>) g;
			}
		}
		return null;
	}

	private static int field(Screen s, String name) {
		try {
			var f = fr.valcombe.client.screen.BaseScreen.class.getDeclaredField(name);
			f.setAccessible(true);
			return f.getInt(s);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** Un widget visible hors du panneau, ou deux qui se chevauchent ; null si la mise en page est bonne. */
	private static String layoutProblem(Minecraft mc) {
		Screen s = mc.gui.screen();
		if (!(s instanceof HdScreen)) {
			return "pas un écran haute résolution : " + s;
		}
		int px = field(s, "px");
		int py = field(s, "py");
		int pw = field(s, "pw");
		int ph = field(s, "ph");
		if (px < 0 || py - 9 < 0 || px + pw > s.width || py + ph > s.height) {
			return "panneau hors de l'écran (" + px + ", " + py + ", " + pw + " x " + ph + ") dans " + s.width + " x " + s.height;
		}
		List<AbstractWidget> ws = new ArrayList<>();
		for (GuiEventListener child : s.children()) {
			if (child instanceof AbstractWidget w && w.visible) {
				ws.add(w);
			}
		}
		for (AbstractWidget w : ws) {
			if (w.getX() < px + 4 || w.getY() < py + 4 || w.getRight() > px + pw - 4 || w.getBottom() > py + ph - 4) {
				return "« " + w.getMessage().getString() + " » sort du panneau (" + w.getX() + ", " + w.getY() + ", " + w.getWidth() + " x "
					+ w.getHeight() + ")";
			}
		}
		for (int i = 0; i < ws.size(); i++) {
			for (int j = i + 1; j < ws.size(); j++) {
				AbstractWidget a = ws.get(i);
				AbstractWidget b = ws.get(j);
				if (a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom()) {
					return "« " + a.getMessage().getString() + " » chevauche « " + b.getMessage().getString() + " »";
				}
			}
		}
		return null;
	}

	private static boolean scale(ClientGameTestContext ctx, int guiScale, int windowW, int windowH) {
		ctx.getInput().resizeWindow(windowW, windowH);
		ctx.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			mc.resizeGui();
		});
		ctx.waitTicks(10);
		int got = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
		if (got != guiScale) {
			System.out.println("[valcombe-test] échelle " + guiScale + " dans " + windowW + " x " + windowH + " refusée (obtenu " + got
				+ ") : configuration sautée");
			return false;
		}
		return true;
	}

	/** Ce que le test crée, pour le ménage de la fin. */
	private static final class Made {
		final List<String> maps = new ArrayList<>();
		final List<String> trials = new ArrayList<>();
		final List<Path> files = new ArrayList<>();
		String structure;
		String role;
		String scenario;
	}

	/** Les pages de l'Atelier, chacune avec son nom (captures, messages) ; celles qui se capturent à chaque taille. */
	private static final List<String> SHOWN = List.of("accueil", "bibliotheque", "fiche", "structures", "fiche-structure", "fiche-partie",
		"fiche-scenario");

	/** Ouvre chaque page, vérifie sa mise en page, et capture les principales sous ce suffixe. */
	private static void layouts(ClientGameTestContext ctx, String suffix, String mapId, String mainId, Made made) {
		List<Supplier<Screen>> pages = List.of(AtelierHome::new, () -> new MapsPage(0), () -> new MapsPage(1), () -> new MapsPage(2),
			() -> MapSheetPage.visit(mapId), NewMapPage::start, () -> new StructuresPage(0), () -> new StructuresPage(1),
			() -> StructureSheetPage.visit("maison-gothique"), NewStructurePage::start, PartiesPage::new, () -> PartySheetPage.visit(mainId),
			() -> new RolesPage(0), () -> new RolesPage(1), () -> new RolesPage(2), () -> ScenarioSheetPage.visit(made.scenario),
			() -> RoleSheetPage.visit(made.role));
		String[] names = {"accueil", "bibliotheque", "ateliers", "corbeille", "fiche", "nouvelle", "structures", "construire",
			"fiche-structure", "nouvelle-structure", "parties", "fiche-partie", "scenarios", "roles", "packs", "fiche-scenario", "fiche-role"};
		List<String> problems = new ArrayList<>();
		for (int i = 0; i < pages.size(); i++) {
			Supplier<Screen> page = pages.get(i);
			ctx.setScreen(page::get);
			ctx.waitTicks(6);
			String problem = ctx.computeOnClient(AtelierHdGameTest::layoutProblem);
			if (problem == null && i == 0) {
				// Accueil : aucun nom d'entrée coupé, à aucune taille (plus petit, ou sur deux lignes).
				problem = ctx.computeOnClient(mc -> {
					int sc = mc.getWindow().getGuiScale();
					for (var w : mc.gui.screen().children()) {
						if (w instanceof AtelierHome.Tile t) {
							var fit = t.titleAt(sc);
							String title = t.getMessage().getString();
							if (fit.lines().size() > 2 || !String.join(" ", fit.lines()).equals(title)
								|| fit.lines().stream().anyMatch(l -> l.endsWith("…"))) {
								return "nom d'entrée coupé : « " + title + " » → " + fit.lines();
							}
						}
					}
					return null;
				});
			}
			if (problem == null) {
				System.out.println("[valcombe-test] atelier hd : mise en page « " + names[i] + " » (" + suffix + ") : bonne");
			} else {
				System.out.println("[valcombe-test] atelier hd : mise en page « " + names[i] + " » (" + suffix + ") : " + problem);
				problems.add(names[i] + " : " + problem);
			}
			if (SHOWN.contains(names[i]) || problem != null) {
				shot(ctx, "taille-" + suffix + "-" + names[i]);
			}
		}
		check(problems.isEmpty(), "mise en page (" + suffix + ") : " + String.join(" ; ", problems));
	}

	/** Donne ce texte au champ de saisie de l'écran (comme tapé), puis rouvre la page pour qu'elle en tienne compte. */
	private static void typeIn(ClientGameTestContext ctx, String text, Supplier<Screen> reopen) {
		boolean ok = ctx.computeOnClient(mc -> {
			for (var w : mc.gui.screen().children()) {
				if (w instanceof fr.valcombe.client.hd.HdTextField f) {
					f.value(text);
					return true;
				}
			}
			return false;
		});
		check(ok, "champ de saisie pour « " + text + " »");
		ctx.setScreen(reopen::get);
		ctx.waitTicks(8);
	}

	/** Choisit dans la liste de l'écran la ligne dont ce champ vaut ce texte. */
	private static boolean chooseRow(ClientGameTestContext ctx, String key, String value) {
		boolean ok = ctx.computeOnClient(mc -> {
			for (var w : mc.gui.screen().children()) {
				if (w instanceof fr.valcombe.client.hd.HdList<?> l) {
					for (int i = 0; i < l.items().size(); i++) {
						if (l.items().get(i) instanceof JsonObject o && value.equals(ClientState.str(o, key))) {
							l.choose(i);
							return true;
						}
					}
				}
			}
			return false;
		});
		ctx.waitTicks(8);
		return ok;
	}

	@SuppressWarnings("unchecked")
	private static boolean openInGrid(ClientGameTestContext ctx, String name) {
		boolean ok = ctx.computeOnClient(mc -> {
			HdGrid<JsonObject> g = grid(mc);
			return g != null && g.open(name);
		});
		ctx.waitTicks(10);
		return ok;
	}

	private static net.minecraft.nbt.ListTag ints(int... values) {
		net.minecraft.nbt.ListTag l = new net.minecraft.nbt.ListTag();
		for (int v : values) {
			l.add(net.minecraft.nbt.IntTag.valueOf(v));
		}
		return l;
	}

	/** Une petite construction au format des structures Minecraft (.nbt) : un dallage de 7 × 7 et un pilier de planches. */
	private static void writeStructure(Path file) {
		var tag = new net.minecraft.nbt.CompoundTag();
		tag.put("size", ints(7, 4, 7));
		var palette = new net.minecraft.nbt.ListTag();
		for (String block : List.of("minecraft:stone_bricks", "minecraft:oak_planks")) {
			var s = new net.minecraft.nbt.CompoundTag();
			s.putString("Name", block);
			palette.add(s);
		}
		tag.put("palette", palette);
		var blocks = new net.minecraft.nbt.ListTag();
		for (int x = 0; x < 7; x++) {
			for (int z = 0; z < 7; z++) {
				var b = new net.minecraft.nbt.CompoundTag();
				b.put("pos", ints(x, 0, z));
				b.putInt("state", 0);
				blocks.add(b);
			}
		}
		for (int y = 1; y < 4; y++) {
			var b = new net.minecraft.nbt.CompoundTag();
			b.put("pos", ints(3, y, 3));
			b.putInt("state", 1);
			blocks.add(b);
		}
		tag.put("blocks", blocks);
		tag.put("entities", new net.minecraft.nbt.ListTag());
		try {
			Files.createDirectories(file.getParent());
			net.minecraft.nbt.NbtIo.writeCompressed(tag, file);
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	// ------------------------------------------------------------------
	// Le test
	// ------------------------------------------------------------------

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			ctx.waitTicks(60);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			var server = world.getServer();
			server.runCommand("op " + me);
			server.runCommand("gamemode creative " + me);
			ctx.waitTicks(40);
			check(server.computeOnServer(s -> mw() != null && ValcombeMod.maps() != null), "atelier carte disponible");
			int initialScale = ctx.computeOnClient(mc -> mc.options.guiScale().get());
			Made made = new Made();
			List<String> maps = made.maps;
			Path dropped = server.computeOnServer(s -> Path.of(ValcombeMod.structures().inboxPath()).resolve("atelier-hd-essai.nbt"));
			made.files.add(dropped);
			try {
				run(ctx, world, me, made, dropped);
			} finally {
				ctx.setScreen(() -> null);
				ctx.getInput().resizeWindow(854, 480);
				ctx.runOnClient(mc -> {
					mc.options.guiScale().set(initialScale);
					mc.resizeGui();
				});
				// Ménage : ateliers et cartes d'essai, parties d'essai, structure, rôle et scénario, fichiers déposés.
				server.runOnServer(s -> {
					var creations = ValcombeMod.creations();
					if (made.scenario != null && creations.scenarios().stream().anyMatch(d -> d.id.equals(made.scenario))) {
						creations.deleteScenario(made.scenario);
					}
					if (made.role != null && creations.roles().stream().anyMatch(d -> d.id.equals(made.role))) {
						creations.deleteRole(made.role);
					}
					if (made.structure != null && ValcombeMod.structures().exists(made.structure)) {
						ValcombeMod.structures().delete(made.structure);
					}
					for (String id : made.trials) {
						if (fr.valcombe.mc.games.Games.byWorld(id) != null && !ValcombeMod.worlds().busy(id)) {
							try {
								fr.valcombe.mc.games.Games.current().delete(id);
							} catch (RuntimeException e) {
								System.out.println("[valcombe-test] partie d'essai restée : " + id + " (" + e.getMessage() + ")");
							}
						}
					}
					for (String id : maps) {
						WorldEntry e = mw().atelierOf(id);
						if (e != null && !ValcombeMod.worlds().busy(e.id())) {
							ValcombeMod.worlds().delete(e.id());
						}
						if (ValcombeMod.maps().exists(id)) {
							ValcombeMod.maps().delete(id);
						}
					}
				});
				for (Path f : made.files) {
					try {
						Files.deleteIfExists(f);
					} catch (java.io.IOException e) {
						System.out.println("[valcombe-test] fichier d'essai resté : " + f);
					}
				}
				ctx.waitTicks(40);
			}
		}
	}

	private void run(ClientGameTestContext ctx, TestSingleplayerContext world, String me, Made made, Path dropped) {
		List<String> maps = made.maps;
		var server = world.getServer();
		String hubDim = "valcombe:" + server.computeOnServer(s -> ValcombeMod.hub().id());
		long stamp = System.currentTimeMillis() % 100000;
		String name = "Hameau " + stamp;

		// 1. Une carte dans la bibliothèque : un village de 7 trônes bâti dans son atelier, enregistré et fermé de dehors.
		send(ctx, "vc atelier carte village 7 petite port oui " + name);
		check(waitFor(ctx, world, 200, s -> mw().ateliers().stream().anyMatch(e -> e.nom().equals(name))), "atelier créé pour le village");
		String atelier = server.computeOnServer(s -> mw().ateliers().stream().filter(e -> e.nom().equals(name)).findFirst().orElseThrow().id());
		String mapId = server.computeOnServer(s -> ValcombeMod.worlds().find(atelier).carte());
		maps.add(mapId);
		check(waitFor(ctx, world, 3000, s -> mw().idle(atelier) && dimensionOf(s, me).equals("valcombe:" + atelier)),
			"village bâti dans son atelier, joueur conduit dedans");
		send(ctx, "vc atelier carte enregistrer " + atelier);
		check(waitFor(ctx, world, 100, s -> ValcombeMod.maps().exists(mapId)), "« enregistrer <atelier> » range la carte dans la bibliothèque");
		send(ctx, "vc atelier carte fermer " + atelier + " confirmer");
		check(waitFor(ctx, world, 600, s -> ValcombeMod.worlds().find(atelier) == null && dimensionOf(s, me).equals(hubDim)),
			"« fermer <atelier> confirmer » : le monde est supprimé, le joueur attend au hub");

		// 2. Menu J › Atelier : l'accueil, ses quatre entrées.
		ctx.setScreen(MainScreen::new);
		ctx.waitTicks(10);
		press(ctx, "Atelier");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof AtelierHome), "le bouton Atelier du menu J ouvre l'accueil");
		for (String tile : List.of(AtelierHome.CARTES, AtelierHome.STRUCTURES, AtelierHome.PARTIES, AtelierHome.ROLES)) {
			check(ctx.computeOnClient(mc -> find(mc, tile) != null), "entrée « " + tile + " »");
		}
		check(waitClient(ctx, 200, mc -> MapsPage.library().stream().anyMatch(o -> mapId.equals(ClientState.str(o, "id")))),
			"la carte est dans la vue de l'Atelier");
		JsonObject card = ctx.computeOnClient(mc -> MapsPage.library().stream().filter(o -> mapId.equals(ClientState.str(o, "id")))
			.findFirst().orElseThrow());
		check(ClientState.num(card, "sieges", 0) == 7 && ClientState.bool(card, "jouable") && ClientState.num(card, "cote", 0) > 20
			&& !ClientState.str(card, "vignette").isEmpty() && !ClientState.str(card, "modifieeTexte").isEmpty(),
			"fiche de la bibliothèque : 7 trônes, jouable, taille, date, empreinte de vignette : " + card);
		shot(ctx, "1-accueil");

		// 3. Cartes : la bibliothèque en vignettes ; la vignette arrive du serveur et devient une texture.
		press(ctx, AtelierHome.CARTES);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof MapsPage), "entrée Cartes");
		ctx.runOnClient(mc -> MapsPage.resetFilters());
		String vstamp = ClientState.str(card, "vignette");
		check(waitClient(ctx, 300, mc -> Vignettes.get("carte:" + mapId, vstamp) != null && Vignettes.ready("carte:" + mapId, vstamp)), "vignette de la carte reçue en texture");
		check(ctx.computeOnClient(mc -> {
			var img = Vignettes.get("carte:" + mapId, vstamp);
			return img != null && img.width() == 512 && img.height() == 512;
		}), "vignette de 512 × 512 pixels");
		ctx.runOnClient(mc -> ((MapsPage) mc.gui.screen()).rebuildNow());
		ctx.waitTicks(5);
		shot(ctx, "2-bibliotheque");
		// La recherche ne garde que ce qui répond, sans accents ni majuscules.
		check(ctx.computeOnClient(mc -> grid(mc) != null && grid(mc).labels().contains(name)), "la carte est dans la galerie");
		ctx.runOnClient(mc -> {
			for (var w : mc.gui.screen().children()) {
				if (w instanceof fr.valcombe.client.hd.HdTextField f) {
					mc.gui.screen().setFocused(f);
				}
			}
		});
		ctx.getInput().typeChars("HAMEAU " + stamp);
		ctx.waitTicks(6);
		check(ctx.computeOnClient(mc -> grid(mc) != null && grid(mc).labels().equals(List.of(name))), "la recherche ne garde que la carte");
		ctx.runOnClient(mc -> {
			MapsPage.resetFilters();
			((MapsPage) mc.gui.screen()).rebuildNow();
		});
		ctx.waitTicks(4);

		// 4. Sa fiche : grande vignette, détails, actions.
		check(ctx.computeOnClient(mc -> grid(mc).open(name)), "un clic sur la case ouvre la fiche");
		ctx.waitTicks(10);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof MapSheetPage p && p.mapId().equals(mapId)), "fiche de la carte");
		check(ctx.computeOnClient(mc -> AtelierPage.current().trailLabels().equals(List.of("Atelier", "Cartes", name))),
			"fil d'Ariane : Atelier › Cartes › " + name);
		check(primary(ctx, MapSheetPage.OPEN), "« Ouvrir dans un atelier » mis en avant");
		shot(ctx, "3-fiche");

		// 5. Une action réelle : dupliquer, puis supprimer la copie (confirmé), la restaurer, la jeter de nouveau.
		List<String> before = server.computeOnServer(s -> ValcombeMod.maps().list().stream().map(d -> d.id).toList());
		press(ctx, MapSheetPage.DUPLICATE);
		check(waitFor(ctx, world, 100, s -> ValcombeMod.maps().list().size() == before.size() + 1), "carte dupliquée");
		String copy = server.computeOnServer(s -> ValcombeMod.maps().list().stream().map(d -> d.id).filter(id -> !before.contains(id))
			.findFirst().orElseThrow());
		maps.add(copy);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof MapsPage), "retour à la bibliothèque après la copie");
		check(waitClient(ctx, 100, mc -> AtelierPage.reply() != null && ClientState.str(AtelierPage.reply(), "texte").contains("(copie)")),
			"la réponse du serveur s'affiche au pied de la page");
		check(waitClient(ctx, 100, mc -> grid(mc) != null && grid(mc).labels().contains(name + " (copie)")), "la copie paraît dans la galerie");
		shot(ctx, "4-copie-mise-en-avant");
		check(ctx.computeOnClient(mc -> grid(mc).open(name + " (copie)")), "fiche de la copie");
		ctx.waitTicks(10);
		// Le message appartient à la page qui l'a provoqué : il s'efface en changeant de page, et ne revient pas au retour.
		check(ctx.computeOnClient(mc -> AtelierPage.reply() == null), "le message de la bibliothèque s'efface sur la fiche");
		// Un long message passe sur deux lignes (plus petit au besoin), sans être coupé ; un très long finit par « … » et
		// se lit en entier au survol.
		String longText = "Sélection non enregistrée. WorldEdit ne sert que dans les ateliers, pas dans un monde de partie : une "
			+ "carte se prépare dans un atelier carte. Menu J › Mondes pour y aller.";
		check(ctx.computeOnClient(mc -> {
			int sc = mc.getWindow().getGuiScale();
			var ft = AtelierPage.footerText(longText, AtelierPage.current().footerTextWidth(sc), sc);
			return ft.lines().size() == 2 && !ft.cut() && String.join(" ", ft.lines()).replace("  ", " ").length() >= longText.length() - 2;
		}), "un long message tient en entier sur deux lignes");
		check(ctx.computeOnClient(mc -> {
			int sc = mc.getWindow().getGuiScale();
			var ft = AtelierPage.footerText(longText.repeat(3), AtelierPage.current().footerTextWidth(sc), sc);
			return ft.lines().size() == 2 && ft.cut() && ft.lines().get(1).endsWith("…");
		}), "un très long message : deux lignes, « … », le texte entier au survol");
		press(ctx, MapSheetPage.DELETE);
		check(server.computeOnServer(s -> ValcombeMod.maps().exists(copy)), "un seul clic ne supprime rien");
		check(ctx.computeOnClient(mc -> find(mc, MapSheetPage.DELETE_CONFIRM) != null && find(mc, MapSheetPage.CANCEL) != null
			&& find(mc, MapSheetPage.DELETE) == null), "la confirmation est demandée (« Mettre à la corbeille » ou « Annuler »)");
		shot(ctx, "5-suppression-a-confirmer");
		press(ctx, MapSheetPage.DELETE_CONFIRM);
		check(waitFor(ctx, world, 100, s -> !ValcombeMod.maps().exists(copy)), "la copie a quitté la bibliothèque");
		check(waitClient(ctx, 100, mc -> mc.gui.screen() instanceof MapsPage), "la fiche revient d'elle-même à la bibliothèque");
		chooseTab(ctx, "Corbeille");
		check(waitClient(ctx, 60, mc -> grid(mc) == null && find(mc, "Restaurer") != null), "la copie est à la corbeille");
		shot(ctx, "6-corbeille");
		press(ctx, "Restaurer");
		check(waitFor(ctx, world, 100, s -> ValcombeMod.maps().exists(copy)), "« Restaurer » la remet dans la bibliothèque");
		server.runOnServer(s -> ValcombeMod.maps().delete(copy));

		// 6. Nouvelle carte : trois étapes, ce qui manque est dit, « Suivant » grisé tant qu'il manque.
		ctx.setScreen(MapsPage::new);
		ctx.waitTicks(5);
		press(ctx, MapsPage.NEW);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof NewMapPage) && NewMapPage.step() == 0, "parcours « Nouvelle carte »");
		check(greyed(ctx, NewMapPage.NEXT), "« Suivant » grisé tant qu'aucun départ n'est choisi");
		shot(ctx, "7-nouvelle-depart");
		press(ctx, NewMapPage.VILLAGE);
		check(primary(ctx, NewMapPage.NEXT), "« Suivant » mis en avant une fois le départ choisi");
		press(ctx, NewMapPage.NEXT);
		check(waitClient(ctx, 300, mc -> Vignettes.ready("village:gothique:16:1:oui", "v1")), "aperçu du village fait par le serveur");
		shot(ctx, "8-nouvelle-reglages-village");
		press(ctx, NewMapPage.NEXT);
		shot(ctx, "9-nouvelle-verifier");
		press(ctx, NewMapPage.PREVIOUS);
		press(ctx, NewMapPage.PREVIOUS);
		press(ctx, NewMapPage.BLANK);
		press(ctx, NewMapPage.NEXT);
		// Le nom d'une carte vierge est facultatif : envoyée directement à une partie, elle n'en demande pas.
		check(primary(ctx, NewMapPage.NEXT), "carte vierge : « Suivant » sans nom");
		shot(ctx, "10-nouvelle-vierge-sans-nom");
		press(ctx, NewMapPage.NEXT);
		chooseTab(ctx, NewMapPage.IN_GAME);
		check(ctx.computeOnClient(mc -> find(mc, "Envoyer une carte vierge") != null), "carte vierge vers une partie, sans nom demandé");
		shot(ctx, "10b-nouvelle-vierge-vers-une-partie");
		chooseTab(ctx, NewMapPage.IN_WORKSHOP);
		check(ctx.computeOnClient(mc -> find(mc, "Créer dans un atelier") != null && find(mc, "Créer dans un atelier").active),
			"carte vierge dans un atelier : un nom est proposé (« Carte vierge »)");
		press(ctx, NewMapPage.PREVIOUS);

		// Import : un fichier .nbt déposé dans le dossier d'import, importé dans un atelier.
		writeStructure(dropped);
		press(ctx, NewMapPage.PREVIOUS);
		press(ctx, NewMapPage.IMPORT);
		press(ctx, NewMapPage.NEXT);
		check(greyed(ctx, NewMapPage.NEXT), "import : « Suivant » grisé sans fichier choisi");
		check(waitClient(ctx, 60, mc -> {
			for (var w : mc.gui.screen().children()) {
				if (w instanceof fr.valcombe.client.hd.HdList<?> l) {
					for (int i = 0; i < l.items().size(); i++) {
						if (l.items().get(i) instanceof JsonObject o && "atelier-hd-essai.nbt".equals(ClientState.str(o, "nom"))) {
							l.choose(i);
							return true;
						}
					}
				}
			}
			return false;
		}), "le fichier déposé est dans la liste");
		ctx.waitTicks(6);
		check(primary(ctx, NewMapPage.NEXT), "fichier choisi : « Suivant » mis en avant");
		ctx.waitTicks(6);
		shot(ctx, "11-nouvelle-import");
		press(ctx, NewMapPage.NEXT);
		// Vu dans le même instant que le clic : un petit fichier est posé aussitôt et le joueur est alors conduit dans
		// l'atelier, ce qui ferme l'écran.
		check(ctx.computeOnClient(mc -> {
			HdButton b = find(mc, "Importer dans un atelier");
			if (b == null || !b.active) {
				return false;
			}
			b.press();
			return mc.gui.screen() instanceof MapsPage && MapsPage.tab() == 1;
		}), "« Importer dans un atelier » ouvre « Ateliers ouverts »");
		check(waitFor(ctx, world, 200, s -> mw().ateliers().stream().anyMatch(e -> e.nom().equals("atelier-hd-essai"))),
			"atelier créé pour l'import");
		String imported = server.computeOnServer(s -> mw().ateliers().stream().filter(e -> e.nom().equals("atelier-hd-essai"))
			.findFirst().orElseThrow().id());
		maps.add(server.computeOnServer(s -> ValcombeMod.worlds().find(imported).carte()));
		ctx.setScreen(() -> new MapsPage(1));
		ctx.waitTicks(20);
		shot(ctx, "12-ateliers-ouverts");
		check(waitFor(ctx, world, 3000, s -> mw().idle(imported)), "construction importée posée dans l'atelier");
		ctx.setScreen(() -> new MapsPage(1));
		ctx.waitTicks(30);
		// D'autres ateliers peuvent être ouverts : on choisit celui de l'import dans la liste.
		check(waitClient(ctx, 60, mc -> {
			for (var w : mc.gui.screen().children()) {
				if (w instanceof fr.valcombe.client.hd.HdList<?> l) {
					for (int i = 0; i < l.items().size(); i++) {
						if (l.items().get(i) instanceof JsonObject o && imported.equals(ClientState.str(o, "monde"))) {
							l.choose(i);
							return true;
						}
					}
				}
			}
			return false;
		}), "l'atelier d'import est dans « Ateliers ouverts »");
		ctx.waitTicks(6);
		press(ctx, "Fermer l'atelier");
		press(ctx, "Confirmer la fermeture");
		check(waitFor(ctx, world, 600, s -> ValcombeMod.worlds().find(imported) == null), "atelier d'import fermé depuis la liste");

		String mainId = server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().id());
		// Chaque entrée est vérifiée même si une autre a échoué : le test dit tout ce qui ne va pas, puis échoue.
		List<String> failed = new ArrayList<>();
		section(ctx, failed, "Structures", () -> structures(ctx, world, made, stamp));
		section(ctx, failed, "Parties", () -> parties(ctx, world, made, stamp, mainId));
		section(ctx, failed, "Rôles et scénarios", () -> roles(ctx, world, made, stamp, mapId));

		// 10. La mise en page de chaque page, à plusieurs tailles d'interface.
		section(ctx, failed, "mise en page 427x240", () -> {
			if (scale(ctx, 2, 854, 480)) {
				layouts(ctx, "427x240", mapId, mainId, made);
			}
		});
		section(ctx, failed, "mise en page à l'échelle 3", () -> {
			if (scale(ctx, 3, 1280, 720)) {
				layouts(ctx, "echelle3", mapId, mainId, made);
			}
		});
		section(ctx, failed, "mise en page à l'échelle 1", () -> {
			if (scale(ctx, 1, 854, 480)) {
				layouts(ctx, "echelle1", mapId, mainId, made);
			}
		});
		section(ctx, failed, "mise en page 2560x1440", () -> {
			if (scale(ctx, 2, 2560, 1440)) {
				layouts(ctx, "2560x1440", mapId, mainId, made);
			}
		});
		check(failed.isEmpty(), "entrées de l'Atelier en échec : " + String.join(" | ", failed));
	}

	/** Lance une partie du test ; un échec est noté (avec la capture de l'écran) et la suite continue. */
	private static void section(ClientGameTestContext ctx, List<String> failed, String name, Runnable part) {
		try {
			part.run();
		} catch (AssertionError e) {
			System.out.println("[valcombe-test] atelier hd : ÉCHEC dans « " + name + " » : " + e.getMessage());
			failed.add(name + " : " + e.getMessage());
			try {
				shot(ctx, "echec-" + failed.size());
			} catch (RuntimeException ignored) {
				// La capture n'est qu'un indice : l'échec est déjà noté.
			}
		}
	}

	// ------------------------------------------------------------------
	// 7. Structures
	// ------------------------------------------------------------------

	/**
	 * Le catalogue en vignettes vues de trois quarts ; « À vous » vide, avec « Nouvelle structure » mis en avant ; le parcours
	 * « Nouvelle structure » (la sélection manque hors d'un atelier, la parcelle vide passe) ; une structure à vous (un
	 * fichier .nbt importé), sa fiche, renommée sur place puis supprimée après confirmation.
	 */
	private void structures(ClientGameTestContext ctx, TestSingleplayerContext world, Made made, long stamp) {
		var server = world.getServer();
		ctx.setScreen(AtelierHome::new);
		ctx.waitTicks(5);
		ctx.runOnClient(mc -> StructuresPage.resetFilters());
		press(ctx, AtelierHome.STRUCTURES);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof StructuresPage), "entrée Structures");
		check(waitClient(ctx, 400, mc -> StructuresPage.catalog().stream().filter(o -> "maison-gothique".equals(ClientState.str(o, "id")))
			.anyMatch(o -> Vignettes.get("structure:maison-gothique", ClientState.str(o, "vignette")) != null
				&& Vignettes.ready("structure:maison-gothique", ClientState.str(o, "vignette")))), "vignette de trois quarts reçue");
		check(ctx.computeOnClient(mc -> {
			var img = Vignettes.get("structure:maison-gothique", "");
			return img != null && img.width() == 512 && img.height() == 512;
		}), "vignette de structure de 512 × 512 pixels");
		ctx.waitTicks(40); // les autres vignettes du catalogue arrivent
		shot(ctx, "13-structures");
		chooseTab(ctx, "À vous");
		boolean noneMine = server.computeOnServer(s -> ValcombeMod.structures().list().stream().allMatch(d -> d.fournie));
		if (noneMine) {
			check(primary(ctx, StructuresPage.NEW), "« À vous » vide : « Nouvelle structure » mis en avant");
			shot(ctx, "14-structures-a-vous-vide");
		}

		// Le parcours « Nouvelle structure » : hors d'un atelier, la sélection manque et le dit ; la parcelle vide passe.
		press(ctx, StructuresPage.NEW);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof NewStructurePage) && NewStructurePage.step() == 0, "parcours « Nouvelle structure »");
		press(ctx, NewStructurePage.FROM_SELECTION);
		check(greyed(ctx, NewStructurePage.NEXT), "sans sélection, « Suivant » reste grisé");
		shot(ctx, "15-nouvelle-structure-sans-selection");
		press(ctx, NewStructurePage.EMPTY_PLOT);
		check(primary(ctx, NewStructurePage.NEXT), "parcelle vide : « Suivant » mis en avant");
		press(ctx, NewStructurePage.NEXT);
		check(greyed(ctx, NewStructurePage.NEXT), "sans nom, « Suivant » grisé");
		typeIn(ctx, "Tour d'essai", NewStructurePage::new);
		check(primary(ctx, NewStructurePage.NEXT), "nommée : « Suivant » mis en avant");
		press(ctx, NewStructurePage.NEXT);
		check(primary(ctx, NewStructurePage.OPEN_PLOT), "dernière étape : « Ouvrir la parcelle » mis en avant");
		shot(ctx, "16-nouvelle-structure-verifier");

		// Une structure à vous : un fichier .nbt déposé, importé par la commande ; sa fiche.
		Path file = server.computeOnServer(s -> Path.of(ValcombeMod.structures().inboxPath()).resolve("pilier-" + stamp + ".nbt"));
		made.files.add(file);
		writeStructure(file);
		send(ctx, "vc structure importer pilier-" + stamp + ".nbt");
		String sid = server.computeOnServer(s -> ValcombeMod.structures().list().stream().filter(d -> !d.fournie
			&& d.id.startsWith("pilier-" + stamp)).map(d -> d.id).findFirst().orElse(null));
		check(sid != null, "structure importée au catalogue");
		made.structure = sid;
		String sname = server.computeOnServer(s -> ValcombeMod.structures().def(sid).nom);
		ctx.runOnClient(mc -> StructuresPage.resetFilters());
		ctx.setScreen(() -> new StructuresPage(0));
		check(waitClient(ctx, 100, mc -> StructuresPage.catalog().stream().anyMatch(o -> sid.equals(ClientState.str(o, "id")))),
			"la structure paraît dans le catalogue");
		chooseTab(ctx, "À vous");
		check(waitClient(ctx, 300, mc -> Vignettes.ready("structure:" + sid, "")), "sa vignette est faite");
		// Sa vignette montre ses blocs, pas seulement la scène et l'ombre : le dallage de pierre (gris) et le pilier de
		// planches (brun) y sont peints. Un fichier à l'ancien format de palette (« Name ») ne donne plus de l'air.
		check(server.computeOnServer(s -> ValcombeMod.structures().solidCount(sid) == 52),
			"structure importée avec ses vrais blocs (pierre et planches, pas de l'air) : "
				+ server.computeOnServer(s -> ValcombeMod.structures().solidCount(sid)));
		var job = server.computeOnServer(s -> fr.valcombe.mc.Vignettes.job("structure:" + sid));
		check(job != null, "vignette de la structure demandée au serveur");
		byte[] png = job.get();
		check(png != null && png.length > 0, "vignette de la structure faite");
		int[] painted = new int[2];
		try {
			java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
			for (int y = 0; y < img.getHeight(); y += 2) {
				for (int x = 0; x < img.getWidth(); x += 2) {
					int c = img.getRGB(x, y);
					int r = c >> 16 & 255;
					int g = c >> 8 & 255;
					int b = c & 255;
					if (Math.abs(r - g) < 8 && Math.abs(g - b) < 8 && r > 60) {
						painted[0]++; // gris de la pierre (la scène, elle, tire sur le violet et reste sombre)
					} else if (r > g + 10 && g > b + 10 && r > 80) {
						painted[1]++; // brun des planches
					}
				}
			}
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		check(painted[0] > 400 && painted[1] > 40, "vignette avec des pixels de blocs : pierre " + painted[0] + ", planches " + painted[1]);
		shot(ctx, "17-structures-a-vous");
		check(openInGrid(ctx, sname), "case de la structure");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof StructureSheetPage p && p.structureId().equals(sid)), "fiche de la structure");
		check(ctx.computeOnClient(mc -> AtelierPage.current().trailLabels().equals(List.of("Atelier", "Structures", sname))),
			"fil d'Ariane : Atelier › Structures › " + sname);
		check(primary(ctx, StructureSheetPage.TAKE), "« Prendre en main » mis en avant");
		shot(ctx, "18-fiche-structure");

		// Une action réelle : renommée sur place, puis supprimée après confirmation.
		press(ctx, StructureSheetPage.RENAME);
		check(greyed(ctx, StructureSheetPage.RENAME_CONFIRM), "même nom : « Renommer » grisé");
		typeIn(ctx, "Pilier renommé " + stamp, () -> new StructureSheetPage(sid));
		press(ctx, StructureSheetPage.RENAME_CONFIRM);
		check(waitFor(ctx, world, 100, s -> ValcombeMod.structures().def(sid).nom.equals("Pilier renommé " + stamp)), "structure renommée");
		check(waitClient(ctx, 100, mc -> AtelierPage.reply() != null && ClientState.str(AtelierPage.reply(), "texte").contains("renommée")),
			"la réponse du serveur s'affiche");
		press(ctx, StructureSheetPage.DELETE);
		check(server.computeOnServer(s -> ValcombeMod.structures().exists(sid)), "un seul clic ne supprime rien");
		// Échap défait la confirmation sans fermer l'Atelier ; un second Échap, lui, le fermerait.
		ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE);
		ctx.waitTicks(8);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof StructureSheetPage && find(mc, StructureSheetPage.DELETE) != null
			&& find(mc, StructureSheetPage.DELETE_CONFIRM) == null), "Échap annule la confirmation, la fiche reste ouverte");
		press(ctx, StructureSheetPage.DELETE);
		shot(ctx, "19-structure-suppression-a-confirmer");
		press(ctx, StructureSheetPage.DELETE_CONFIRM);
		check(waitFor(ctx, world, 100, s -> !ValcombeMod.structures().exists(sid)), "structure supprimée du catalogue");
		check(waitClient(ctx, 100, mc -> mc.gui.screen() instanceof StructuresPage), "la fiche revient d'elle-même au catalogue");
	}

	// ------------------------------------------------------------------
	// 8. Parties
	// ------------------------------------------------------------------

	/**
	 * Les parties en vignettes ; la fiche de la partie principale et « Carte en place » ; une partie d'essai créée par la
	 * page (son nom écrit sur place), mise en avant, sa fiche une fois son village posé, puis supprimée après confirmation.
	 */
	private void parties(ClientGameTestContext ctx, TestSingleplayerContext world, Made made, long stamp, String mainId) {
		var server = world.getServer();
		ctx.setScreen(AtelierHome::new);
		ctx.waitTicks(5);
		press(ctx, AtelierHome.PARTIES);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof PartiesPage), "entrée Parties");
		check(waitClient(ctx, 100, mc -> PartiesPage.games().stream().anyMatch(g -> mainId.equals(ClientState.str(g, "id")))),
			"la partie principale est dans la liste");
		ctx.waitTicks(30);
		shot(ctx, "20-parties");
		// L'écran Parties ouvert depuis l'Atelier : « Retour » ramène à Atelier › Parties ; ouvert du menu J, au menu J.
		press(ctx, PartiesPage.JOIN);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof fr.valcombe.client.screen.PartiesScreen), "écran Parties depuis l'Atelier");
		press(ctx, "Retour");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof PartiesPage), "« Retour » ramène à Atelier › Parties");
		ctx.setScreen(MainScreen::new);
		ctx.waitTicks(5);
		if (ctx.computeOnClient(mc -> find(mc, "Parties") != null)) {
			press(ctx, "Parties");
			check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof fr.valcombe.client.screen.PartiesScreen), "écran Parties du menu J");
			press(ctx, "Retour");
			check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof MainScreen), "« Retour » ramène au menu J");
		}
		ctx.setScreen(PartiesPage::new);
		ctx.waitTicks(5);
		String mainName = server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().name());
		check(openInGrid(ctx, mainName), "case de la partie principale");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof PartySheetPage p && p.gameId().equals(mainId)), "fiche de la partie");
		shot(ctx, "21-fiche-partie");
		press(ctx, PartySheetPage.MAP);
		check(ctx.computeOnClient(mc -> find(mc, PartySheetPage.SAVE_AS) != null && find(mc, PartySheetPage.CLEAR) != null),
			"« Carte en place » : enregistrer sous, carte vide, vider le monde");
		shot(ctx, "22-partie-carte-en-place");
		press(ctx, PartySheetPage.CLEAR);
		check(ctx.computeOnClient(mc -> find(mc, PartySheetPage.CLEAR_CONFIRM) != null), "vider le monde se confirme");
		press(ctx, PartySheetPage.CANCEL);
		check(!server.computeOnServer(s -> fr.valcombe.mc.games.Games.main().host().busy()), "« Annuler » : rien n'est lancé");

		// Une partie d'essai créée par la page.
		ctx.setScreen(PartiesPage::new);
		ctx.waitTicks(5);
		press(ctx, PartiesPage.CREATE);
		check(greyed(ctx, PartiesPage.CREATE_CONFIRM), "sans nom, « Créer » grisé");
		String trialName = "Essai atelier " + stamp;
		typeIn(ctx, trialName, PartiesPage::new);
		press(ctx, PartiesPage.CREATE_CONFIRM);
		check(waitFor(ctx, world, 200, s -> ValcombeMod.worlds().list().stream().anyMatch(e -> e.nom().equals(trialName))), "partie d'essai créée");
		String trial = server.computeOnServer(s -> ValcombeMod.worlds().list().stream().filter(e -> e.nom().equals(trialName))
			.map(WorldEntry::id).findFirst().orElseThrow());
		made.trials.add(trial);
		check(waitClient(ctx, 100, mc -> grid(mc) != null && grid(mc).labels().contains(trialName)), "elle paraît dans la liste");
		shot(ctx, "23-partie-essai-mise-en-avant");
		check(waitFor(ctx, world, 2400, s -> !ValcombeMod.sender().busy(trial) && fr.valcombe.mc.games.Games.byWorld(trial).host().village() != null
			&& !fr.valcombe.mc.games.Games.byWorld(trial).host().busy()), "son village est posé");
		ctx.setScreen(() -> new PartySheetPage(trial));
		check(waitClient(ctx, 200, mc -> PartiesPage.games().stream().filter(g -> trial.equals(ClientState.str(g, "id")))
			.anyMatch(g -> Vignettes.ready("partie:" + trial, ""))), "la vignette de sa carte est faite");
		ctx.setScreen(() -> new PartySheetPage(trial));
		ctx.waitTicks(10);
		shot(ctx, "24-fiche-partie-essai");
		press(ctx, PartySheetPage.DELETE);
		check(server.computeOnServer(s -> fr.valcombe.mc.games.Games.byWorld(trial) != null), "un seul clic ne supprime rien");
		shot(ctx, "25-partie-suppression-a-confirmer");
		press(ctx, PartySheetPage.DELETE_CONFIRM);
		check(waitFor(ctx, world, 600, s -> ValcombeMod.worlds().find(trial) == null && fr.valcombe.mc.games.Games.byWorld(trial) == null),
			"partie d'essai supprimée");
		check(waitClient(ctx, 100, mc -> mc.gui.screen() instanceof PartiesPage), "la fiche revient d'elle-même à la liste");
	}

	// ------------------------------------------------------------------
	// 9. Rôles et scénarios
	// ------------------------------------------------------------------

	/**
	 * Un rôle créé et un scénario (sur la carte d'essai) ; les scénarios en jetons, sa fiche et le choix d'une partie ;
	 * les rôles, sa fiche ; un pack déposé (exporté puis rôle et scénario retirés) importé par la page ; le rôle supprimé
	 * par sa fiche après confirmation.
	 */
	private void roles(ClientGameTestContext ctx, TestSingleplayerContext world, Made made, long stamp, String mapId) {
		var server = world.getServer();
		String roleId = "veilleur-" + stamp;
		String scId = "cendres-" + stamp;
		String roleName = "Veilleur des cendres";
		String scName = "Nuit des cendres " + stamp;
		made.role = roleId;
		made.scenario = scId;
		Runnable create = () -> server.runOnServer(s -> {
			var r = new fr.valcombe.rules.RoleDef();
			r.id = roleId;
			r.nom = roleName;
			r.camp = "CITADIN";
			r.embleme = "e_cle";
			r.texte = "Vous veillez sur les braises : vous savez si le feu a été attisé cette nuit.";
			ValcombeMod.creations().saveRole(r);
			var d = new fr.valcombe.session.ScenarioDef();
			d.id = scId;
			d.nom = scName;
			d.description = "Une soirée autour des braises de Valcombe, avec un rôle créé pour l'occasion.";
			d.carte = mapId;
			for (var role : fr.valcombe.rules.Role.classic()) {
				d.roles.add(role.name().toLowerCase(java.util.Locale.ROOT));
			}
			d.roles.add(roleId);
			ValcombeMod.creations().saveScenario(d);
		});
		create.run();
		ctx.setScreen(AtelierHome::new);
		ctx.waitTicks(5);
		press(ctx, AtelierHome.ROLES);
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof RolesPage), "entrée Rôles et scénarios");
		check(waitClient(ctx, 100, mc -> RolesPage.scenarios().stream().anyMatch(o -> scId.equals(ClientState.str(o, "id")))),
			"le scénario est dans la vue de l'Atelier");
		ctx.setScreen(() -> new RolesPage(0));
		ctx.waitTicks(10);
		shot(ctx, "26-scenarios");
		check(openInGrid(ctx, scName), "case du scénario");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof ScenarioSheetPage p && p.scenarioId().equals(scId)), "fiche du scénario");
		check(primary(ctx, ScenarioSheetPage.CHOOSE), "« Choisir pour une partie » mis en avant");
		shot(ctx, "27-fiche-scenario");
		press(ctx, ScenarioSheetPage.CHOOSE);
		shot(ctx, "28-scenario-pour-une-partie");
		press(ctx, ScenarioSheetPage.CANCEL);
		ctx.setScreen(() -> new RolesPage(1));
		ctx.waitTicks(10);
		shot(ctx, "29-roles");
		check(openInGrid(ctx, roleName), "case du rôle");
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof RoleSheetPage p && p.roleId().equals(roleId)), "fiche du rôle");
		shot(ctx, "30-fiche-role");

		// Un pack déposé : exporté dans le dossier d'import, rôle et scénario retirés, puis importé par la page.
		Path pack = server.computeOnServer(s -> Path.of(ValcombeMod.structures().inboxPath()).resolve("atelier-hd-" + stamp + ".valcombe"));
		made.files.add(pack);
		server.runOnServer(s -> {
			var creations = ValcombeMod.creations();
			String json = creations.exportPack(List.of(roleId), List.of(scId), List.of());
			try {
				Files.writeString(pack, json);
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
			creations.deleteScenario(scId);
			creations.deleteRole(roleId);
		});
		ctx.setScreen(() -> new RolesPage(2));
		check(waitClient(ctx, 100, mc -> RolesPage.packList().stream().anyMatch(p -> pack.getFileName().toString()
			.equals(ClientState.str(p, "fichier")))), "le pack déposé paraît");
		ctx.setScreen(() -> new RolesPage(2));
		ctx.waitTicks(5);
		check(chooseRow(ctx, "fichier", pack.getFileName().toString()), "pack choisi");
		check(primary(ctx, RolesPage.IMPORT), "« Importer » mis en avant");
		shot(ctx, "31-packs");
		press(ctx, RolesPage.IMPORT);
		check(waitFor(ctx, world, 100, s -> ValcombeMod.creations().roles().stream().anyMatch(d -> d.id.equals(roleId))
			&& ValcombeMod.creations().scenarios().stream().anyMatch(d -> d.id.equals(scId))), "rôle et scénario revenus du pack : "
			+ ctx.computeOnClient(mc -> AtelierPage.reply() == null ? "" : ClientState.str(AtelierPage.reply(), "texte")));

		// Le rôle supprimé par sa fiche, après confirmation (puis remis, pour les mises en page).
		ctx.setScreen(() -> new RoleSheetPage(roleId));
		ctx.waitTicks(30);
		press(ctx, RoleSheetPage.DELETE);
		check(server.computeOnServer(s -> ValcombeMod.creations().roles().stream().anyMatch(d -> d.id.equals(roleId))), "un seul clic ne supprime rien");
		shot(ctx, "32-role-suppression-a-confirmer");
		press(ctx, RoleSheetPage.DELETE_CONFIRM);
		check(waitFor(ctx, world, 100, s -> ValcombeMod.creations().roles().stream().noneMatch(d -> d.id.equals(roleId))), "rôle supprimé");
		check(waitClient(ctx, 100, mc -> mc.gui.screen() instanceof RolesPage), "la fiche revient d'elle-même aux rôles");
		server.runOnServer(s -> {
			var r = new fr.valcombe.rules.RoleDef();
			r.id = roleId;
			r.nom = roleName;
			r.camp = "CITADIN";
			r.embleme = "e_cle";
			r.texte = "Vous veillez sur les braises : vous savez si le feu a été attisé cette nuit.";
			ValcombeMod.creations().saveRole(r);
		});
	}
}
