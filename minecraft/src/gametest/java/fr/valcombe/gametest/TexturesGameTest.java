package fr.valcombe.gametest;

import com.mojang.renderpearl.api.textures.FilterMode;

import fr.valcombe.client.ui.Theme;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Textures haute résolution (ticket « Textures du mod en 128 px ») vérifiées dans le vrai jeu :
 * - les textures d'interface sont chargées à leur taille réelle (Theme.TOKEN_TEX, ICON_TEX, HUD_TEX,
 *   MARKER_TEX) avec le filtrage lisse demandé par leur .png.mcmeta ;
 * - les textures d'objets (jetons 3D, icônes de rôles, objets du jeu, outils de l'éditeur) sont
 *   entrées dans un atlas à 128 px, sans texture manquante ;
 * - une planche (capture « valcombe-textures-hd ») montre jetons, marqueurs, astres et objets
 *   aux tailles où l'interface les dessine, pour juger du rendu à l'œil.
 */
public class TexturesGameTest implements FabricClientGameTest {
	private static final String[] ROLES = { "commere", "goule", "templier", "veilleuse", "e_rose", "e_lanterne" };
	private static final String[] MARKERS = { "poison", "protect", "drunk", "herring", "master", "ghost" };

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
	}

	/** Texture d'interface chargée par le jeu : taille de la texture sur la carte graphique et filtrage. */
	private static void checkGui(ClientGameTestContext ctx, String path, int size) {
		String err = ctx.computeOnClient(mc -> {
			var tex = mc.getTextureManager().getTexture(Theme.tex(path));
			int w = tex.getTexture().getWidth(0);
			int h = tex.getTexture().getHeight(0);
			if (w != size || h != size) {
				return path + " : " + w + " × " + h + " au lieu de " + size;
			}
			if (tex.getSampler().getMinFilter() != FilterMode.LINEAR) {
				return path + " : filtrage " + tex.getSampler().getMinFilter() + " au lieu de LINEAR";
			}
			return null;
		});
		check(err == null, err);
	}

	/** Texture d'objet : présente dans un atlas du jeu, à 128 px. */
	private static void checkItem(ClientGameTestContext ctx, String path) {
		String err = ctx.computeOnClient(mc -> {
			Identifier id = Identifier.fromNamespaceAndPath("valcombe", path);
			List<String> found = new ArrayList<>();
			mc.getAtlasManager().forEach((atlasId, atlas) -> {
				var sprite = atlas.getSprite(id);
				if (!sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
					found.add(atlasId + " " + sprite.contents().width() + "×" + sprite.contents().height());
				}
			});
			if (found.isEmpty()) {
				return path + " : absente des atlas";
			}
			return found.stream().allMatch(s -> s.endsWith(" 128×128")) ? null : path + " : " + found;
		});
		check(err == null, err);
	}

	private static ItemStack model(String name) {
		ItemStack s = new ItemStack(Items.PAPER);
		s.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("valcombe", name));
		return s;
	}

	/** Planche de contrôle : tout ce que le ticket a redessiné, aux tailles de l'interface. */
	private static final class Planche extends Screen {
		Planche() {
			super(Component.literal("Textures en 128 px"));
		}

		@Override
		public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
			g.fill(0, 0, width, height, 0xFF2A1C20);
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
			super.extractRenderState(g, mouseX, mouseY, partialTick);
			int y = 6;
			Theme.ink(g, font, "Jetons (16, 24, 32, 44 px), mort, choisi, survolé", 6, y, Theme.PARCH);
			y += 12;
			int x = 6;
			int[] sizes = { 16, 24, 32, 44 };
			for (int k = 0; k < ROLES.length; k++) {
				int s = sizes[k % sizes.length];
				Theme.token(g, x + s / 2, y + 22, s, ROLES[k], k % 2 == 0 ? "GOOD" : "EVIL", true, false, false);
				x += s + 6;
			}
			Theme.token(g, x + 16, y + 22, 32, "goule", "EVIL", false, false, false);
			Theme.token(g, x + 54, y + 22, 32, "commere", "GOOD", true, true, false);
			Theme.token(g, x + 92, y + 22, 32, "templier", null, true, false, true);
			y += 52;
			Theme.ink(g, font, "Marqueurs, lune, soleil, sablier", 6, y, Theme.PARCH);
			y += 12;
			x = 6;
			for (String m : MARKERS) {
				Theme.marker(g, m, x, y);
				x += 14;
			}
			for (String h : new String[] { "moon", "sun", "hourglass" }) {
				Theme.image(g, Theme.tex(h), x, y - 1, 12, 12, Theme.HUD_TEX, Theme.HUD_TEX);
				Theme.image(g, Theme.tex(h), x + 16, y - 7, 24, 24, Theme.HUD_TEX, Theme.HUD_TEX);
				x += 46;
			}
			y += 26;
			Theme.ink(g, font, "Objets : jetons 3D, icônes, plume, bâton, équerre, grimoire, outils", 6, y, Theme.PARCH);
			y += 12;
			x = 6;
			List<String> items = new ArrayList<>(List.of("jeton_vivant", "jeton_mort", "jeton_fantome", "jeton_goule",
				"icone_commere", "icone_e_rose_mal", "plume", "baton", "equerre", "grimoire"));
			for (String t : new String[] { "trone", "maison", "centre", "salle", "gomme", "selection", "structure", "main" }) {
				items.add("outil_" + t);
			}
			for (String name : items) {
				g.item(model(name), x, y);
				x += 20;
			}
		}

		@Override
		public boolean isPauseScreen() {
			return false;
		}
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.waitTicks(20);
		for (String t : new String[] { "token_good", "token_evil", "token_neutral", "token_dead", "token_selected", "token_hover" }) {
			checkGui(ctx, t, Theme.TOKEN_TEX);
		}
		for (String r : ROLES) {
			checkGui(ctx, "roles/" + r, Theme.ICON_TEX);
		}
		for (String h : new String[] { "moon", "sun", "hourglass" }) {
			checkGui(ctx, h, Theme.HUD_TEX);
		}
		for (String m : MARKERS) {
			checkGui(ctx, "marker_" + m, Theme.MARKER_TEX);
		}
		for (String p : new String[] { "item/scene/dos", "item/scene/role_goule", "item/scene/role_e_rose_mal", "item/scene/tranche",
				"item/scene/crane", "item/objets/plume", "item/objets/cuir", "item/outils/trone", "item/outils/structure" }) {
			checkItem(ctx, p);
		}
		ctx.setScreen(Planche::new);
		ctx.waitTicks(20);
		System.out.println("[valcombe-test] capture : " + ctx.takeScreenshot("valcombe-textures-hd"));
		ctx.setScreen(() -> null);
		ctx.waitTicks(5);
	}
}
