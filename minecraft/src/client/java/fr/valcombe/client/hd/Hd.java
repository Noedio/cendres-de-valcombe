package fr.valcombe.client.hd;

import fr.valcombe.client.ui.Theme;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Contexte de dessin haute résolution : tant qu'il est ouvert, la pose est mise à l'échelle 1/échelle d'interface
 * et toutes les coordonnées sont en pixels réels de la fenêtre. Les cadres, textes et images tombent ainsi sur
 * les vrais pixels de l'écran au lieu des gros pixels de l'interface.
 *
 * <pre>
 * try (Hd hd = Hd.begin(g)) {
 *     hd.panel(hd.px(x), hd.px(y), hd.px(w), hd.px(h));
 *     hd.text(HdStyle.TEXTE, "Bonsoir", hd.px(x) + hd.u(8), hd.px(y) + hd.u(8), Hd.INK);
 * }
 * </pre>
 *
 * Les écrans placent leurs éléments en unités d'interface (comme avant) et convertissent avec px() ; u() convertit
 * une longueur de dessin (épaisseur, marge) en pixels réels. Les contextes s'emboîtent sans se cumuler.
 */
public final class Hd implements AutoCloseable {
	// Couleurs (ARGB) de la boîte à outils, dans la palette de Theme.
	public static final int INK = Theme.INK;
	public static final int WINE = Theme.WINE;
	public static final int BRONZE = Theme.BRONZE;
	public static final int GOLD = Theme.GOLD;
	public static final int PARCH = Theme.PARCH;
	public static final int ASH = Theme.ASH;
	/** Texte clair sur les boutons et fonds sombres. */
	public static final int LIGHT = 0xFFF2E6C9;
	/** Texte éteint (bouton inactif, indication). */
	public static final int MUTED = 0xFFB4A99C;
	/** Texte secondaire sur parchemin. */
	public static final int INK_SOFT = 0xFF5B4636;
	/** Fond d'une ligne choisie dans une liste. */
	public static final int SELECTION = 0xE05A1E2A;
	/** Fond d'une ligne survolée. */
	public static final int HOVER = 0x405A1E2A;
	/** Surlignage du texte choisi dans un champ. */
	public static final int TEXT_SELECTION = 0x80D9B45A;

	/** Contextes ouverts : seul le premier met la pose à l'échelle. */
	private static int depth;

	private final GuiGraphicsExtractor g;
	private final Minecraft mc;
	private final int scale;
	private final boolean owner;

	private Hd(GuiGraphicsExtractor g) {
		this.g = g;
		this.mc = Minecraft.getInstance();
		this.scale = guiScale();
		this.owner = depth == 0;
		depth++;
		if (owner) {
			g.pose().pushMatrix();
			g.pose().scale(1f / scale, 1f / scale);
		}
	}

	/** Ouvre un contexte (à fermer : try-with-resources). */
	public static Hd begin(GuiGraphicsExtractor g) {
		return new Hd(g);
	}

	@Override
	public void close() {
		depth--;
		if (owner) {
			g.pose().popMatrix();
		}
	}

	/** Échelle d'interface choisie par le joueur (pixels réels par unité d'interface). */
	public static int guiScale() {
		return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
	}

	public GuiGraphicsExtractor graphics() {
		return g;
	}

	public int scale() {
		return scale;
	}

	/** Une longueur en unités d'interface, en pixels réels (arrondie). */
	public int u(double units) {
		return (int) Math.round(units * scale);
	}

	/** Une coordonnée d'interface, en pixels réels. */
	public int px(int gui) {
		return gui * scale;
	}

	/** Largeur de l'écran en pixels réels. */
	public int screenWidth() {
		return mc.getWindow().getGuiScaledWidth() * scale;
	}

	public int screenHeight() {
		return mc.getWindow().getGuiScaledHeight() * scale;
	}

	/** Position de la souris en pixels réels (précise au pixel, pas à l'unité d'interface). */
	public double mouseX() {
		return mc.mouseHandler.getScaledXPos(mc.getWindow()) * scale;
	}

	public double mouseY() {
		return mc.mouseHandler.getScaledYPos(mc.getWindow()) * scale;
	}

	/** Vrai si la souris est dans le rectangle (pixels réels). */
	public boolean hovered(int x, int y, int w, int h) {
		double mx = mouseX();
		double my = mouseY();
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	// ------------------------------------------------------------------
	// Formes
	// ------------------------------------------------------------------
	public void fill(int x0, int y0, int x1, int y1, int color) {
		g.fill(x0, y0, x1, y1, color);
	}

	/** Dégradé vertical. */
	public void gradient(int x0, int y0, int x1, int y1, int top, int bottom) {
		g.fillGradient(x0, y0, x1, y1, top, bottom);
	}

	/** Contour d'épaisseur t pixels, à l'intérieur du rectangle. */
	public void frame(int x, int y, int w, int h, int t, int color) {
		fill(x, y, x + w, y + t, color);
		fill(x, y + h - t, x + w, y + h, color);
		fill(x, y + t, x + t, y + h - t, color);
		fill(x + w - t, y + t, x + w, y + h - t, color);
	}

	/** Disque plein (rangées de pixels). */
	public void disc(int cx, int cy, int r, int color) {
		for (int dy = -r; dy < r; dy++) {
			double yy = dy + 0.5;
			int half = (int) Math.round(Math.sqrt(Math.max(0, r * r - yy * yy)));
			if (half > 0) {
				fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
			}
		}
	}

	/** Triangle pointe en haut, de base w et de hauteur h, haut à (cx, y). */
	public void triangle(int cx, int y, int w, int h, int color) {
		for (int row = 0; row < h; row++) {
			int half = (int) Math.round((row + 0.5) * w / (2.0 * h));
			if (half > 0) {
				fill(cx - half, y + row, cx + half, y + row + 1, color);
			}
		}
	}

	// ------------------------------------------------------------------
	// Textures
	// ------------------------------------------------------------------
	/** Texture « 9 tranches » étirée sur le rectangle (coins à taille fixe). */
	public void sprite(HdSprites.Sprite sp, int x, int y, int w, int h) {
		sprite(sp, x, y, w, h, -1);
	}

	/** Texture « 9 tranches », teintée (ARGB, -1 pour aucune teinte). */
	public void sprite(HdSprites.Sprite sp, int x, int y, int w, int h, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		HdTextures.HdTexture t = HdTextures.get(sp);
		int tw = t.width();
		int th = t.height();
		// Les bords sont donnés pour la texture de référence : on les rapporte à l'image réellement chargée.
		float rx = tw / (float) sp.width();
		float ry = th / (float) sp.height();
		int sl = Math.round(sp.left() * rx);
		int sr = Math.round(sp.right() * rx);
		int st = Math.round(sp.top() * ry);
		int sb = Math.round(sp.bottom() * ry);
		int[] dx = edges(u(sp.leftUnits()), u(sp.rightUnits()), w);
		int[] dy = edges(u(sp.topUnits()), u(sp.bottomUnits()), h);
		int[] sx = {0, sl, tw - sr, tw};
		int[] sy = {0, st, th - sb, th};
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				int dw = dx[i + 1] - dx[i];
				int dh = dy[j + 1] - dy[j];
				int sw = sx[i + 1] - sx[i];
				int sh = sy[j + 1] - sy[j];
				if (dw > 0 && dh > 0 && sw > 0 && sh > 0) {
					g.blit(RenderPipelines.GUI_TEXTURED, t.key(), x + dx[i], y + dy[j], sx[i], sy[j], dw, dh, sw, sh, tw, th, color);
				}
			}
		}
	}

	/** Bornes des trois bandes (début, fin du bord 1, début du bord 2, fin), bords réduits si la place manque. */
	private static int[] edges(int a, int b, int length) {
		if (a + b > length) {
			float f = length / (float) Math.max(1, a + b);
			a = (int) Math.floor(a * f);
			b = length - a;
		}
		return new int[] {0, a, length - b, length};
	}

	/** Image entière d'une texture de la boîte à outils. */
	public void image(HdSprites.Sprite sp, int x, int y, int w, int h) {
		image(sp, x, y, w, h, -1);
	}

	public void image(HdSprites.Sprite sp, int x, int y, int w, int h, int color) {
		HdTextures.HdTexture t = HdTextures.get(sp);
		blitWhole(t, x, y, w, h, color);
	}

	/**
	 * Image entière d'une ressource du jeu (pictogramme, jeton) : réduite avec ses mipmaps, agrandie lissée ou en
	 * pixels nets. Un pictogramme de 32 px ou moins est du pixel art : on le garde net quand on l'agrandit.
	 */
	public void image(Identifier resource, int x, int y, int w, int h, int color) {
		HdTextures.HdTexture probe = HdTextures.get(resource, HdTextures.Magnify.SMOOTH, false);
		boolean pixelArt = probe.width() <= 32 && w >= probe.width();
		HdTextures.HdTexture t = pixelArt ? HdTextures.get(resource, HdTextures.Magnify.PIXELS, false) : probe;
		blitWhole(t, x, y, w, h, color);
	}

	private void blitWhole(HdTextures.HdTexture t, int x, int y, int w, int h, int color) {
		if (w > 0 && h > 0) {
			g.blit(RenderPipelines.GUI_TEXTURED, t.key(), x, y, 0, 0, w, h, t.width(), t.height(), t.width(), t.height(), color);
		}
	}

	/** Mosaïque (parchemin) : la texture est répétée, un carreau mesurant sa taille en unités d'interface. */
	public void tile(HdSprites.Sprite sp, int x, int y, int w, int h) {
		if (w <= 0 || h <= 0) {
			return;
		}
		HdTextures.HdTexture t = HdTextures.get(sp);
		float tilePx = sp.widthUnits() * scale;
		int srcW = Math.round(w / tilePx * t.width());
		int srcH = Math.round(h / tilePx * t.height());
		g.blit(RenderPipelines.GUI_TEXTURED, t.key(), x, y, 0, 0, w, h, srcW, srcH, t.width(), t.height(), -1);
	}

	// ------------------------------------------------------------------
	// Textes (x, y : coin haut gauche de la ligne ; les capitales sont centrées dans la ligne)
	// ------------------------------------------------------------------
	/** Écrit une ligne ; renvoie sa largeur en pixels réels. */
	public int text(HdStyle style, String text, int x, int y, int color) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		HdFonts.Fit f = HdFonts.fit(style, scale);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(f.factor(), f.factor());
		g.text(HdFonts.font(), HdFonts.styled(text, f), 0, 0, color, false);
		pose.popMatrix();
		return width(style, text);
	}

	/** Écrit une ligne centrée sur cx. */
	public void textCentered(HdStyle style, String text, int cx, int y, int color) {
		text(style, text, cx - width(style, text) / 2, y, color);
	}

	/** Écrit une ligne alignée à droite sur right. */
	public void textRight(HdStyle style, String text, int right, int y, int color) {
		text(style, text, right - width(style, text), y, color);
	}

	/** Écrit une ligne gravée : une ombre décalée d'un demi-point sous le texte. */
	public int textShadow(HdStyle style, String text, int x, int y, int color, int shadow) {
		int d = Math.max(1, Math.round(scale * 0.5f));
		text(style, text, x + d, y + d, shadow);
		return text(style, text, x, y, color);
	}

	/** Écrit une ligne verticalement centrée dans une hauteur (boutons, lignes de liste). */
	public int textMiddle(HdStyle style, String text, int x, int y, int h, int color) {
		return text(style, text, x, y + (h - lineHeight(style)) / 2, color);
	}

	public int width(HdStyle style, String text) {
		return HdFonts.width(style, scale, text);
	}

	public int lineHeight(HdStyle style) {
		return HdFonts.lineHeight(style, scale);
	}

	public String ellipsize(HdStyle style, String text, int maxWidth) {
		return HdFonts.ellipsize(style, scale, text, maxWidth);
	}

	public List<String> wrap(HdStyle style, String text, int maxWidth) {
		return HdFonts.wrap(style, scale, text, maxWidth);
	}

	/** Paragraphe coupé à la largeur ; renvoie la hauteur utilisée. */
	public int paragraph(HdStyle style, String text, int x, int y, int width, int color) {
		return paragraph(style, text, x, y, width, color, Integer.MAX_VALUE);
	}

	/** Paragraphe coupé à maxHeight pixels : la dernière ligne visible se termine par « … » si le texte dépasse. */
	public int paragraph(HdStyle style, String text, int x, int y, int width, int color, int maxHeight) {
		List<String> lines = wrap(style, text, width);
		int lh = lineHeight(style);
		int max = Math.max(1, maxHeight / lh);
		int shown = Math.min(max, lines.size());
		for (int i = 0; i < shown; i++) {
			String line = lines.get(i);
			if (i == shown - 1 && lines.size() > shown) {
				line = ellipsize(style, line + "…", width);
			}
			text(style, line, x, y + i * lh, color);
		}
		return shown * lh;
	}

	/** Hauteur qu'occuperait un paragraphe. */
	public int paragraphHeight(HdStyle style, String text, int width) {
		return wrap(style, text, width).size() * lineHeight(style);
	}

	// ------------------------------------------------------------------
	// Découpe (alignée sur les unités d'interface : c'est la précision du découpage de Minecraft)
	// ------------------------------------------------------------------
	public void clip(int x0, int y0, int x1, int y1) {
		g.enableScissor(x0, y0, x1, y1);
	}

	public void unclip() {
		g.disableScissor();
	}

	// ------------------------------------------------------------------
	// Compositions
	// ------------------------------------------------------------------
	/** Voile sombre sur tout l'écran. */
	public void shade() {
		fill(0, 0, screenWidth(), screenHeight(), Theme.SHADE);
	}

	/** Panneau : parchemin en mosaïque encadré de bois et de bronze. */
	public void panel(int x, int y, int w, int h) {
		int inset = u(3);
		tile(HdSprites.PARCHEMIN, x + inset, y + inset, w - 2 * inset, h - 2 * inset);
		sprite(HdSprites.CADRE, x, y, w, h);
	}

	/** Creux de parchemin plus sombre (fond de liste, d'encart). */
	public void inset(int x, int y, int w, int h) {
		sprite(HdSprites.LISTE, x, y, w, h);
	}

	/** Bandeau de titre centré sur cx, de 16 unités de haut à partir de y. */
	public void banner(String title, int cx, int y) {
		int tw = width(HdStyle.TITRE, title);
		int w = tw + u(30);
		int h = u(16);
		sprite(HdSprites.BANDEAU, cx - w / 2, y, w, h);
		int ty = y + (h - lineHeight(HdStyle.TITRE)) / 2;
		textShadow(HdStyle.TITRE, title, cx - tw / 2, ty, 0xFFF3D98E, 0xC0140A0C);
	}

	/** Séparateur orné : filets de bronze qui s'estompent de part et d'autre d'un losange. */
	public void separator(int x, int y, int w) {
		int cx = x + w / 2;
		int d = u(6);
		int t = Math.max(1, u(0.5));
		int steps = 10;
		for (int side = -1; side <= 1; side += 2) {
			for (int i = 0; i < steps; i++) {
				int a0 = d / 2 + u(2) + (w / 2 - d / 2 - u(2)) * i / steps;
				int a1 = d / 2 + u(2) + (w / 2 - d / 2 - u(2)) * (i + 1) / steps;
				int alpha = 0xC0 * (steps - i) / steps;
				int c = alpha << 24 | (BRONZE & 0xFFFFFF);
				if (side < 0) {
					fill(cx - a1, y - t / 2, cx - a0, y - t / 2 + t, c);
				} else {
					fill(cx + a0, y - t / 2, cx + a1, y - t / 2 + t, c);
				}
			}
		}
		image(HdSprites.LOSANGE, cx - d / 2, y - d / 2, d, d);
	}

	/**
	 * Infobulle près de la souris (mx, my en pixels réels), gardée dans l'écran. Le texte est coupé à 180 unités ;
	 * un « \n » force un retour à la ligne.
	 */
	public void tooltip(String text, double mx, double my) {
		HdStyle st = HdStyle.TEXTE.size(10);
		int pad = u(6);
		List<String> lines = wrap(st, text, u(180));
		int lh = lineHeight(st);
		int w = 0;
		for (String l : lines) {
			w = Math.max(w, width(st, l));
		}
		w += 2 * pad;
		int h = lines.size() * lh + 2 * pad - u(1);
		int x = (int) mx + u(10);
		int y = (int) my + u(10);
		if (x + w > screenWidth() - u(4)) {
			x = Math.max(u(4), (int) mx - u(6) - w);
		}
		if (y + h > screenHeight() - u(4)) {
			y = Math.max(u(4), (int) my - u(6) - h);
		}
		sprite(HdSprites.INFOBULLE, x, y, w, h);
		for (int i = 0; i < lines.size(); i++) {
			text(st, lines.get(i), x + pad, y + pad - u(0.5) + i * lh, PARCH);
		}
	}
}
