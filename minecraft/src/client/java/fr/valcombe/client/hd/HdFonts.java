package fr.valcombe.client.hd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * Les polices de la boîte à outils, mesurées en pixels réels. Un style (face, taille en unités) se résout, pour
 * une échelle d'interface, en une police déclarée (valcombe:hd/&lt;face&gt;_&lt;taille&gt;) et un facteur de mise à
 * l'échelle proche de 1. Toutes les mesures passent par la police de Minecraft (avances, coupure des lignes).
 */
public final class HdFonts {
	/** Une police déclarée et le facteur qui la porte à la taille voulue. */
	public record Fit(int size, float factor, Style style) {
		/** Hauteur de ligne en pixels réels. */
		public int lineHeight() {
			return Math.round(FontLadder.lineHeight(size) * factor);
		}

		/** Largeur passée à la police (unités de la police déclarée) pour une largeur en pixels réels. */
		public int fontWidth(int pixels) {
			return (int) Math.floor(pixels / factor);
		}
	}

	private record Key(HdStyle style, int scale) {
	}

	private static final Map<Key, Fit> FITS = new HashMap<>();

	private HdFonts() {
	}

	/** L'identifiant de la police déclarée d'une face à une taille. */
	public static Identifier id(FontLadder.Face face, int size) {
		return Identifier.fromNamespaceAndPath("valcombe", "hd/" + face.id + "_" + size);
	}

	/** Résout un style à une échelle d'interface. */
	public static Fit fit(HdStyle style, int scale) {
		Key key = new Key(style, scale);
		Fit f = FITS.get(key);
		if (f == null) {
			float pixels = style.size() * scale;
			int size = FontLadder.pick(style.face(), pixels);
			f = new Fit(size, pixels / size, Style.EMPTY.withFont(new FontDescription.Resource(id(style.face(), size))));
			FITS.put(key, f);
		}
		return f;
	}

	static Font font() {
		return Minecraft.getInstance().font;
	}

	static MutableComponent styled(String text, Fit fit) {
		return Component.literal(text).withStyle(fit.style());
	}

	/** Largeur d'un texte en pixels réels. */
	public static int width(HdStyle style, int scale, String text) {
		Fit f = fit(style, scale);
		return (int) Math.ceil(font().getSplitter().stringWidth(styled(text, f)) * f.factor());
	}

	/** Hauteur de ligne en pixels réels. */
	public static int lineHeight(HdStyle style, int scale) {
		return fit(style, scale).lineHeight();
	}

	/** Le texte, raccourci par « … » s'il dépasse maxWidth pixels réels. */
	public static String ellipsize(HdStyle style, int scale, String text, int maxWidth) {
		if (width(style, scale, text) <= maxWidth) {
			return text;
		}
		Fit f = fit(style, scale);
		int room = Math.max(0, maxWidth - width(style, scale, "…"));
		String head = font().getSplitter().plainHeadByWidth(text, f.fontWidth(room), f.style());
		return head.stripTrailing() + "…";
	}

	/** Coupe un texte en lignes d'au plus maxWidth pixels réels (les retours à la ligne sont respectés). */
	public static List<String> wrap(HdStyle style, int scale, String text, int maxWidth) {
		Fit f = fit(style, scale);
		List<String> out = new ArrayList<>();
		for (FormattedText line : font().getSplitter().splitLines(text, Math.max(1, f.fontWidth(maxWidth)), f.style())) {
			out.add(line.getString());
		}
		if (out.isEmpty()) {
			out.add("");
		}
		return out;
	}

	/** Indice du caractère le plus proche d'une abscisse (pixels réels depuis le début du texte). */
	public static int indexAt(HdStyle style, int scale, String text, double x) {
		if (x <= 0) {
			return 0;
		}
		int best = text.length();
		int prev = 0;
		for (int i = 1; i <= text.length(); i++) {
			int w = width(style, scale, text.substring(0, i));
			if (w >= x) {
				best = (x - prev < w - x) ? i - 1 : i;
				break;
			}
			prev = w;
		}
		return best;
	}

}
