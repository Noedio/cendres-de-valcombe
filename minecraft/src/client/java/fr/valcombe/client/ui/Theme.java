package fr.valcombe.client.ui;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/** Palette, textures et primitives de dessin de l'interface de Valcombe. */
public final class Theme {
	public static final int INK = 0xFF1B1214;
	public static final int WINE = 0xFF5A1E2A;
	public static final int BLOOD = 0xFF8E1B1B;
	public static final int BRONZE = 0xFFB08D57;
	public static final int GOLD = 0xFFD9B45A;
	public static final int PARCH = 0xFFE8D9B5;
	public static final int ASH = 0xFF6B6466;
	public static final int GOOD = 0xFF3F6FA8;
	public static final int EVIL = 0xFFA83232;
	public static final int SHADE = 0xC0100A0C;

	private Theme() {
	}

	public static Identifier tex(String path) {
		return Identifier.fromNamespaceAndPath("valcombe", "textures/gui/" + path + ".png");
	}

	public static final Identifier PARCHMENT = tex("parchment");
	public static final Identifier PANEL = tex("panel");

	/** Image entière étirée dans le rectangle. */
	public static void image(GuiGraphicsExtractor g, Identifier id, int x, int y, int w, int h, int texW, int texH) {
		g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, w, h, texW, texH, texW, texH);
	}

	/** Dessin « 9 tranches » : coins fixes, bords et centre étirés. */
	public static void nineSlice(GuiGraphicsExtractor g, Identifier id, int x, int y, int w, int h,
			int texW, int texH, int b) {
		int cw = texW - 2 * b;
		int ch = texH - 2 * b;
		int iw = Math.max(0, w - 2 * b);
		int ih = Math.max(0, h - 2 * b);
		g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, b, b, texW, texH);
		g.blit(RenderPipelines.GUI_TEXTURED, id, x + w - b, y, texW - b, 0, b, b, texW, texH);
		g.blit(RenderPipelines.GUI_TEXTURED, id, x, y + h - b, 0, texH - b, b, b, texW, texH);
		g.blit(RenderPipelines.GUI_TEXTURED, id, x + w - b, y + h - b, texW - b, texH - b, b, b, texW, texH);
		g.blit(RenderPipelines.GUI_TEXTURED, id, x + b, y, b, 0, iw, b, cw, b, texW, texH);
		g.blit(RenderPipelines.GUI_TEXTURED, id, x + b, y + h - b, b, texH - b, iw, b, cw, b, texW, texH);
		g.blit(RenderPipelines.GUI_TEXTURED, id, x, y + b, 0, b, b, ih, b, ch, texW, texH);
		g.blit(RenderPipelines.GUI_TEXTURED, id, x + w - b, y + b, texW - b, b, b, ih, b, ch, texW, texH);
		g.blit(RenderPipelines.GUI_TEXTURED, id, x + b, y + b, b, b, iw, ih, cw, ch, texW, texH);
	}

	/** Panneau : parchemin encadré de bois et de bronze. */
	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.blit(RenderPipelines.GUI_TEXTURED, PARCHMENT, x + 3, y + 3, 0, 0, w - 6, h - 6, 256, 256, 256, 256);
		nineSlice(g, PANEL, x, y, w, h, 48, 48, 6);
	}

	/** Bandeau de titre sombre. */
	public static void banner(GuiGraphicsExtractor g, Font font, String text, int cx, int y) {
		int w = font.width(text) + 24;
		g.fill(cx - w / 2, y, cx + w / 2, y + 14, WINE);
		g.outline(cx - w / 2, y, w, 14, BRONZE);
		g.centeredText(font, text, cx, y + 3, GOLD);
	}

	/** Texte centré à la plume (sans ombre), pour le parchemin. */
	public static void centered(GuiGraphicsExtractor g, Font font, String text, int cx, int y, int color) {
		g.text(font, text, cx - font.width(text) / 2, y, color, false);
	}

	/** Texte à la plume (sans ombre). */
	public static void ink(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
		g.text(font, text, x, y, color, false);
	}

	/** Paragraphe coupé à la largeur ; renvoie la hauteur utilisée. */
	public static int paragraph(GuiGraphicsExtractor g, Font font, String text, int x, int y, int width, int color) {
		List<FormattedCharSequence> lines = font.split(Component.literal(text), width);
		for (int i = 0; i < lines.size(); i++) {
			g.text(font, lines.get(i), x, y + i * (font.lineHeight + 1), color, false);
		}
		return lines.size() * (font.lineHeight + 1);
	}

	/** Paragraphe coupé à maxHeight pixels (dernière ligne terminée par « … » si le texte dépasse). */
	public static int paragraph(GuiGraphicsExtractor g, Font font, String text, int x, int y, int width, int color, int maxHeight) {
		List<FormattedCharSequence> lines = font.split(Component.literal(text), width);
		int lh = font.lineHeight + 1;
		int max = Math.max(1, maxHeight / lh);
		for (int i = 0; i < Math.min(max, lines.size()); i++) {
			if (i == max - 1 && lines.size() > max) {
				g.text(font, lines.get(i), x, y + i * lh, color, false);
				g.text(font, "…", x + Math.min(width - 6, font.width(lines.get(i)) + 1), y + i * lh, color, false);
			} else {
				g.text(font, lines.get(i), x, y + i * lh, color, false);
			}
		}
		return Math.min(max, lines.size()) * lh;
	}

	/** Jeton de siège : disque du camp, pictogramme, linceul si mort, anneau si choisi. */
	public static void token(GuiGraphicsExtractor g, int cx, int cy, int size, String roleId, String team,
			boolean alive, boolean selected, boolean hovered) {
		int x = cx - size / 2;
		int y = cy - size / 2;
		String disc = team == null ? "token_neutral" : team.equals("EVIL") ? "token_evil" : "token_good";
		image(g, tex(disc), x, y, size, size, 32, 32);
		if (roleId != null) {
			int is = size * 22 / 32;
			fr.valcombe.rules.Role r = fr.valcombe.rules.Role.parse(roleId);
			String icon = r != null ? r.icon : roleId.toLowerCase(java.util.Locale.ROOT);
			image(g, tex("roles/" + icon), cx - is / 2, cy - is / 2, is, is, 32, 32);
		}
		if (!alive) {
			image(g, tex("token_dead"), x, y, size, size, 32, 32);
		}
		if (team != null && fr.valcombe.client.ClientConfig.get().daltonien && size >= 16) {
			// Mode daltonien : un triangle pour le mal, un rond pour le bien, en plus de la couleur.
			var font = net.minecraft.client.Minecraft.getInstance().font;
			boolean evil = team.equals("EVIL");
			String mark = evil ? "▲" : "●";
			g.fill(x + size - 9, y, x + size, y + 9, 0xE01B1214);
			g.text(font, mark, x + size - 8, y + 1, evil ? 0xFFFF8080 : 0xFFA0C8FF, false);
		}
		if (selected) {
			image(g, tex("token_selected"), x, y, size, size, 32, 32);
		} else if (hovered) {
			image(g, tex("token_hover"), x, y, size, size, 32, 32);
		}
	}

	public static void marker(GuiGraphicsExtractor g, String name, int x, int y) {
		image(g, tex("marker_" + name), x, y, 10, 10, 12, 12);
	}
}
