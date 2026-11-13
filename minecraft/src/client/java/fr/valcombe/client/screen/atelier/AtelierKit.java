package fr.valcombe.client.screen.atelier;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdGlyphs;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTokenArt;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Les petits outils communs aux pages Structures, Parties, Rôles et scénarios de l'Atelier en jeu : fond d'une ligne
 * de liste, détails « libellé valeur », ce qui manque en rouge, jetons de rôle en éventail, identifiant tiré d'un nom,
 * texte entre guillemets pour une commande.
 */
final class AtelierKit {
	/** Rouge sombre d'un avertissement écrit sur le parchemin. */
	static final int WARN = 0xFF7A1E24;
	/** Ambre d'une mention « modifiée », « en pause ». */
	static final int AMBER = 0xFF8A5A0E;

	private AtelierKit() {
	}

	/** Le fond d'une ligne de liste : choisie (lie-de-vin), survolée (voile), ou rien. */
	static void rowBack(Hd hd, int x, int y, int w, int h, boolean hovered, boolean selected) {
		if (selected) {
			hd.fill(x, y, x + w, y + h, Hd.SELECTION);
		} else if (hovered) {
			hd.fill(x, y, x + w, y + h, Hd.HOVER);
		}
	}

	/** Le fond d'une case de galerie : parchemin plus clair au survol, filet doré. */
	static void cardBack(Hd hd, int x, int y, int w, int h, boolean hovered) {
		hd.fill(x, y, x + w, y + h, hovered ? 0x40F2E6C9 : 0x22301E14);
		hd.frame(x, y, w, h, Math.max(1, hd.u(hovered ? 1 : 0.5)), hovered ? 0xFFD9B45A : 0x806E4F22);
	}

	/**
	 * Des détails « libellé  valeur », une ligne chacun, jusqu'à limit (pixels réels) ; renvoie le bas du dernier écrit.
	 * Une valeur trop longue est raccourcie (le libellé, lui, ne l'est jamais).
	 */
	static int details(Hd hd, List<String[]> rows, int x, int y, int w, int limit) {
		HdStyle ls = HdStyle.PETIT.size(9.5f);
		HdStyle vs = HdStyle.TEXTE.size(10);
		int labelW = 0;
		for (String[] r : rows) {
			labelW = Math.max(labelW, hd.width(ls, r[0]));
		}
		labelW += hd.u(6);
		int lh = hd.lineHeight(vs);
		for (String[] r : rows) {
			if (y + lh > limit) {
				break;
			}
			hd.text(ls, r[0], x, y + (lh - hd.lineHeight(ls)) / 2, Hd.BRONZE_INK);
			hd.text(vs, hd.ellipsize(vs, r[1], w - labelW), x + labelW, y, Hd.INK);
			y += lh;
		}
		return y;
	}

	/** Une ligne d'avertissement : le triangle rouge, puis le texte (sur plusieurs lignes) ; renvoie la hauteur prise. */
	static int warning(Hd hd, String text, int x, int y, int w, int maxH) {
		int s = hd.u(6);
		int lh = hd.lineHeight(HdStyle.PETIT);
		if (maxH < lh) {
			return 0;
		}
		HdGlyphs.warning(hd, x, y + (lh - s) / 2, s, AtelierArt.BAD);
		return hd.paragraph(HdStyle.PETIT, text, x + s + hd.u(4), y, w - s - hd.u(4), WARN, maxH);
	}

	/** Une ligne de conseil : un point bronze, puis le texte ; renvoie la hauteur prise. */
	static int note(Hd hd, String text, int x, int y, int w, int maxH) {
		int s = hd.u(6);
		int lh = hd.lineHeight(HdStyle.PETIT);
		if (maxH < lh) {
			return 0;
		}
		hd.disc(x + s / 2, y + lh / 2, Math.max(1, hd.u(1.2)), Hd.BRONZE_INK);
		return hd.paragraph(HdStyle.PETIT, text, x + s + hd.u(4), y, w - s - hd.u(4), Hd.INK_SOFT, maxH);
	}

	// ------------------------------------------------------------------
	// Jetons de rôle
	// ------------------------------------------------------------------

	/** Le camp d'un jeton pour HdTokenArt : « EVIL » pour le mal, « GOOD » sinon. */
	static String team(JsonObject role) {
		return ClientState.bool(role, "mal") ? "EVIL" : "GOOD";
	}

	/** La couleur de la pastille d'un camp (« CITADIN », « ETRANGER », « SBIRE », « DEMON »). */
	static int campColor(String camp) {
		return switch (camp == null ? "" : camp) {
			case "DEMON" -> 0xFF8A1E24;
			case "SBIRE" -> 0xFFA0402E;
			case "ETRANGER" -> 0xFF3F6E7E;
			default -> 0xFF2F5C8A;
		};
	}

	/** Un velours lie-de-vin semé d'étoiles d'or : le fond des jetons. */
	static void velvet(Hd hd, int x, int y, int w, int h, int seed) {
		hd.gradient(x, y, x + w, y + h, 0xFF3A1420, 0xFF1C0A10);
		for (int i = 0; i < 9; i++) {
			int sx = x + Math.floorMod(seed * 31 + i * 97, 89) * w / 89;
			int sy = y + Math.floorMod(seed * 17 + i * 61, 47) * h / 47;
			int s = Math.max(1, hd.u(0.8));
			hd.fill(sx, sy, sx + s, sy + s, 0x90F2C14E);
		}
	}

	/**
	 * Les jetons de rôles en éventail dans ce rectangle (pixels réels) : jusqu'à six, qui se chevauchent, le premier
	 * devant ; un « +n » doré s'il y en a plus.
	 */
	static void fan(Hd hd, List<JsonObject> roles, int x, int y, int w, int h) {
		int n = Math.min(6, roles.size());
		if (n == 0) {
			return;
		}
		int size = Math.min(h * 7 / 10, w * 2 / (n + 2));
		int step = n == 1 ? 0 : Math.min(size * 4 / 5, (w - size - hd.u(8)) / (n - 1));
		int total = size + step * (n - 1);
		int x0 = x + (w - total) / 2;
		int cy = y + (h - size) / 2;
		for (int i = n - 1; i >= 0; i--) {
			JsonObject r = roles.get(i);
			int lift = Math.abs(i - (n - 1) / 2) * hd.u(1.5);
			HdTokenArt.draw(hd, x0 + i * step, cy + lift, size, ClientState.str(r, "embleme"), team(r), true, false, false);
		}
		if (roles.size() > n) {
			String more = "+" + (roles.size() - n);
			HdStyle st = HdStyle.FORT.size(9);
			int tw = AtelierArt.chipWidth(hd, st, more);
			AtelierArt.chip(hd, st, more, x + w - tw - hd.u(3), y + h - AtelierArt.chipHeight(hd, st) - hd.u(3), 0xE06E4F22);
		}
	}

	// ------------------------------------------------------------------
	// Textes pour les commandes
	// ------------------------------------------------------------------

	/** Sans accents ni majuscules : « Château » se trouve en tapant « chateau ». */
	static String fold(String s) {
		return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
	}

	/** Un identifiant tiré d'un nom (« Port de Valcombe » : port-de-valcombe), libre d'après taken (suffixe -2, -3...). */
	static String slug(String name, Predicate<String> taken) {
		String s = fold(name).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		if (s.length() > 26) {
			s = s.substring(0, 26).replaceAll("-+$", "");
		}
		if (s.length() < 2) {
			s = "carte";
		}
		String id = s;
		for (int n = 2; taken.test(id); n++) {
			id = s + "-" + n;
		}
		return id;
	}

	/** Un argument de commande : tel quel s'il est simple, sinon entre guillemets. */
	static String quoted(String s) {
		return s.matches("[A-Za-z0-9_.+-]+") ? s : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	/** « 1 joueur », « 3 joueurs ». */
	static String plural(int n, String one, String many) {
		return n + " " + (n > 1 ? many : one);
	}
}
