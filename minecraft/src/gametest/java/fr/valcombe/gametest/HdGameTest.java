package fr.valcombe.gametest;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.FontLadder;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdFonts;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdToggle;
import fr.valcombe.client.screen.SettingsScreen;
import fr.valcombe.mc.ValcombeMod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * La boîte à outils haute résolution, en jeu : polices TTF chargées, écran des réglages (premier écran migré) et
 * vitrine de tous les composants, en captures à plusieurs échelles d'interface ; un interrupteur des réglages
 * envoie bien sa commande, la saisie et le défilement survivent à une reconstruction de l'écran.
 */
public class HdGameTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
	}

	private static void shot(ClientGameTestContext ctx, String name) {
		ctx.waitTicks(10);
		System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-" + name));
	}

	/** Change l'échelle d'interface (et la taille de la fenêtre si demandé), puis laisse l'écran se replacer. */
	private static void scale(ClientGameTestContext ctx, int guiScale, int windowW, int windowH) {
		ctx.getInput().resizeWindow(windowW, windowH);
		ctx.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			mc.resizeGui();
		});
		ctx.waitTicks(10);
		int got = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
		check(got == guiScale, "échelle d'interface " + guiScale + " (obtenu " + got + ")");
	}

	/** Le bouton de l'écran ouvert dont le libellé commence ainsi (null s'il n'y est pas). */
	private static HdButton findButton(Minecraft mc, String prefix) {
		if (mc.gui.screen() == null) {
			return null;
		}
		for (GuiEventListener w : mc.gui.screen().children()) {
			if (w instanceof HdButton b && b.getMessage().getString().startsWith(prefix)) {
				return b;
			}
		}
		return null;
	}

	/** Place la souris au centre d'un élément de l'écran (coordonnées d'interface converties pour la fenêtre). */
	private static void hover(ClientGameTestContext ctx, AbstractWidget w) {
		double[] pos = ctx.computeOnClient(mc -> {
			var win = mc.getWindow();
			double k = win.getScreenWidth() / (double) win.getGuiScaledWidth();
			return new double[] {(w.getX() + w.getWidth() / 2.0) * k, (w.getY() + w.getHeight() / 2.0) * k};
		});
		ctx.getInput().setCursorPos(pos[0], pos[1]);
		ctx.waitTicks(15);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			ctx.waitTicks(60);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			world.getServer().runCommand("op " + me);
			ctx.waitTicks(40);
			int initialScale = ctx.computeOnClient(mc -> mc.options.guiScale().get());

			// 1. Les polices TTF sont chargées : à taille double, un texte est deux fois plus large. Si une police
			// manquait, Minecraft prendrait sa police en pixels, de même largeur aux deux tailles.
			for (FontLadder.Face face : FontLadder.Face.values()) {
				int[] w = ctx.computeOnClient(mc -> new int[] {
					HdFonts.width(new HdStyle(face, 12), 1, "Valcombe"), HdFonts.width(new HdStyle(face, 24), 1, "Valcombe")});
				check(w[0] > 20 && Math.abs(w[1] - 2 * w[0]) <= 4, "police " + face.id + " chargée (" + w[0] + " puis " + w[1] + " px)");
			}

			// 2. Réglages de la partie : premier écran migré.
			ctx.runOnClient(mc -> ClientState.send("vc rejoindre"));
			ctx.waitTicks(20);
			ctx.setScreen(SettingsScreen::new);
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof HdScreen), "les réglages sont un écran haute résolution");
			shot(ctx, "hd-1-reglages");

			// Un interrupteur envoie sa commande ; l'écran se reconstruit avec le nouvel état.
			String label = "Chacun chez soi la nuit : ";
			boolean before = world.getServer().computeOnServer(s -> fr.valcombe.mc.games.Games.main().session().settings.nightHouses);
			// La vue envoyée par le serveur peut arriver un peu après l'ouverture de l'écran.
			ctx.waitFor(mc -> findButton(mc, label + (before ? "oui" : "non")) != null, 200);
			boolean pressed = ctx.computeOnClient(mc -> {
				HdButton b = findButton(mc, label);
				if (b instanceof HdToggle t && t.on() == before) {
					b.press();
					return true;
				}
				return false;
			});
			check(pressed, "interrupteur « " + label + "» trouvé, dans l'état du serveur");
			ctx.waitTicks(20);
			check(world.getServer().computeOnServer(s -> fr.valcombe.mc.games.Games.main().session().settings.nightHouses) != before,
				"le réglage a changé sur le serveur");
			check(ctx.computeOnClient(mc -> findButton(mc, label + (before ? "non" : "oui")) != null),
				"l'écran montre le nouvel état");
			ctx.runOnClient(mc -> findButton(mc, label).press());
			ctx.waitTicks(20);
			check(world.getServer().computeOnServer(s -> fr.valcombe.mc.games.Games.main().session().settings.nightHouses) == before,
				"le réglage est revenu");

			// Infobulle haute résolution au survol.
			HdButton heir = ctx.computeOnClient(mc -> findButton(mc, "Héritière sans seuil"));
			check(heir != null && heir.hdTooltip() != null, "bouton à infobulle « Héritière sans seuil »");
			hover(ctx, heir);
			shot(ctx, "hd-2-reglages-infobulle");
			ctx.getInput().setCursorPos(0, 0);

			// Nettes à toutes les échelles : 1 (petite), 3 (fenêtre 1281 x 720), puis retour à 2.
			scale(ctx, 1, 854, 480);
			shot(ctx, "hd-3-reglages-echelle-1");
			scale(ctx, 3, 1281, 720);
			shot(ctx, "hd-4-reglages-echelle-3");
			scale(ctx, 2, 854, 480);

			// 3. Vitrine de tous les composants.
			ctx.setScreen(HdShowcaseScreen::new);
			ctx.waitTicks(10);
			shot(ctx, "hd-5-vitrine");
			// Saisie au clavier dans le champ, défilement de la liste, ligne choisie.
			ctx.runOnClient(mc -> {
				HdShowcaseScreen s = (HdShowcaseScreen) mc.gui.screen();
				s.setFocused(s.field);
			});
			ctx.getInput().typeChars("Bastien ment");
			ctx.waitTicks(5);
			check("Bastien ment".equals(ctx.computeOnClient(mc -> ((HdShowcaseScreen) mc.gui.screen()).field.value())),
				"texte tapé dans le champ");
			ctx.runOnClient(mc -> {
				HdShowcaseScreen s = (HdShowcaseScreen) mc.gui.screen();
				s.list.scrollBy(40);
				s.list.select(5);
			});
			double offset = ctx.computeOnClient(mc -> ((HdShowcaseScreen) mc.gui.screen()).list.scrollOffset());
			check(offset > 0, "la liste défile (" + offset + ")");
			HdButton help = ctx.computeOnClient(mc -> findButton(mc, "Survolez-moi"));
			hover(ctx, help);
			shot(ctx, "hd-6-vitrine-saisie");
			ctx.getInput().setCursorPos(0, 0);

			// Une reconstruction (nouvelle vue du serveur) garde la saisie, le défilement et le focus.
			ctx.runOnClient(mc -> ((HdShowcaseScreen) mc.gui.screen()).rebuildForTest());
			ctx.waitTicks(5);
			check(ctx.computeOnClient(mc -> {
				HdShowcaseScreen s = (HdShowcaseScreen) mc.gui.screen();
				return "Bastien ment".equals(s.field.value()) && s.getFocused() == s.field && s.list.scrollOffset() == offset;
			}), "saisie, focus et défilement gardés après reconstruction");

			// Onglets : changer d'onglet change le contenu de la liste.
			ctx.runOnClient(mc -> ((HdShowcaseScreen) mc.gui.screen()).tabs.choose(1));
			ctx.waitTicks(5);
			check(ctx.computeOnClient(mc -> ((HdShowcaseScreen) mc.gui.screen()).currentRows().contains("Clémence")),
				"l'onglet « Joueurs » montre les joueurs");
			scale(ctx, 3, 1281, 720);
			shot(ctx, "hd-7-vitrine-echelle-3");
			// Retour aux réglages du départ (les autres tests en jeu capturent à l'échelle 2, en 854 x 480).
			ctx.getInput().resizeWindow(854, 480);
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(initialScale);
				mc.resizeGui();
			});
			ctx.setScreen(() -> null);
		}
	}
}
