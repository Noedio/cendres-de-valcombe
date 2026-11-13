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

	/** Ouvre chaque page, vérifie sa mise en page, et la capture sous ce suffixe. */
	private static void layouts(ClientGameTestContext ctx, String suffix, String mapId) {
		List<Supplier<Screen>> pages = List.of(AtelierHome::new, () -> new MapsPage(0), () -> new MapsPage(1), () -> new MapsPage(2),
			() -> new MapSheetPage(mapId), NewMapPage::start);
		String[] names = {"accueil", "bibliotheque", "ateliers", "corbeille", "fiche", "nouvelle"};
		for (int i = 0; i < pages.size(); i++) {
			Supplier<Screen> page = pages.get(i);
			ctx.setScreen(page::get);
			ctx.waitTicks(6);
			String problem = ctx.computeOnClient(AtelierHdGameTest::layoutProblem);
			check(problem == null, "mise en page « " + names[i] + " » (" + suffix + ") : " + (problem == null ? "bonne" : problem));
			if (i == 0 || i == 1 || i == 4) {
				shot(ctx, "taille-" + suffix + "-" + names[i]);
			}
		}
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
			List<String> maps = new ArrayList<>();
			Path dropped = server.computeOnServer(s -> Path.of(ValcombeMod.structures().inboxPath()).resolve("atelier-hd-essai.nbt"));
			try {
				run(ctx, world, me, maps, dropped);
			} finally {
				ctx.setScreen(() -> null);
				ctx.getInput().resizeWindow(854, 480);
				ctx.runOnClient(mc -> {
					mc.options.guiScale().set(initialScale);
					mc.resizeGui();
				});
				// Ménage : ateliers et cartes d'essai, le fichier déposé.
				server.runOnServer(s -> {
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
				try {
					Files.deleteIfExists(dropped);
				} catch (java.io.IOException e) {
					System.out.println("[valcombe-test] fichier d'essai resté : " + dropped);
				}
				ctx.waitTicks(40);
			}
		}
	}

	private void run(ClientGameTestContext ctx, TestSingleplayerContext world, String me, List<String> maps, Path dropped) {
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
		check(greyed(ctx, NewMapPage.NEXT), "carte vierge : « Suivant » grisé sans nom");
		shot(ctx, "10-nouvelle-vierge-sans-nom");

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
		press(ctx, "Importer dans un atelier");
		check(waitFor(ctx, world, 200, s -> mw().ateliers().stream().anyMatch(e -> e.nom().equals("atelier-hd-essai"))),
			"atelier créé pour l'import");
		String imported = server.computeOnServer(s -> mw().ateliers().stream().filter(e -> e.nom().equals("atelier-hd-essai"))
			.findFirst().orElseThrow().id());
		maps.add(server.computeOnServer(s -> ValcombeMod.worlds().find(imported).carte()));
		check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof MapsPage) && MapsPage.tab() == 1, "« Ateliers ouverts » montre le nouvel atelier");
		shot(ctx, "12-ateliers-ouverts");
		check(waitFor(ctx, world, 3000, s -> mw().idle(imported)), "construction importée posée dans l'atelier");
		ctx.setScreen(() -> new MapsPage(1));
		ctx.waitTicks(30);
		press(ctx, "Fermer l'atelier");
		press(ctx, "Confirmer la fermeture");
		check(waitFor(ctx, world, 600, s -> ValcombeMod.worlds().find(imported) == null), "atelier d'import fermé depuis la liste");

		// 7. La mise en page de chaque page, à plusieurs tailles d'interface.
		if (scale(ctx, 2, 854, 480)) {
			layouts(ctx, "427x240", mapId);
		}
		if (scale(ctx, 3, 1280, 720)) {
			layouts(ctx, "echelle3", mapId);
		}
		if (scale(ctx, 1, 854, 480)) {
			layouts(ctx, "echelle1", mapId);
		}
		if (scale(ctx, 2, 2560, 1440)) {
			layouts(ctx, "2560x1440", mapId);
		}
	}
}
