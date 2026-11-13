package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdFonts;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdToken;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.network.chat.Component;

/**
 * Le grimoire en cercle : rôles, marqueurs, infobulle détaillée. hdTokens() le dessine en haute résolution (pupitre
 * du Conteur).
 */
final class Grimoire {
	private Grimoire() {
	}

	// ------------------------------------------------------------------
	// Grimoire en haute résolution (pupitre du Conteur)
	// ------------------------------------------------------------------
	/**
	 * Géométrie du grimoire (unités) : centre, rayons de l'ellipse, taille des jetons, largeur des noms posés
	 * au-dessus ou au-dessous d'un jeton (labelMax) et à côté d'un jeton (sideMax).
	 */
	record Ring(int cx, int cy, int rx, int ry, int size, int labelMax, int sideMax, double[] angles) {
		/** Angle du siège i sur l'ellipse (0 = à droite, sens horaire à l'écran). */
		double angle(int i) {
			return angles[i];
		}

		int x(int i) {
			return cx + (int) Math.round(rx * Math.cos(angles[i]));
		}

		int y(int i) {
			return cy + (int) Math.round(ry * Math.sin(angles[i]));
		}

		Ring withLabels(int newLabelMax) {
			return new Ring(cx, cy, rx, ry, size, newLabelMax, sideMax, angles);
		}
	}

	/**
	 * Angles de n sièges espacés également le long du bord de l'ellipse, le premier en haut, dans le sens horaire.
	 * À pas d'angle égal, les sièges se serreraient sur les côtés d'une ellipse plus large que haute : à longueur
	 * d'arc égale, l'écart est le même partout et les jetons peuvent être plus grands.
	 */
	static double[] spread(int n, int rx, int ry) {
		int steps = 720;
		double[] len = new double[steps + 1];
		double lx = 0;
		double ly = -ry;
		for (int k = 1; k <= steps; k++) {
			double a = -Math.PI / 2 + 2 * Math.PI * k / steps;
			double ex = rx * Math.cos(a);
			double ey = ry * Math.sin(a);
			len[k] = len[k - 1] + Math.hypot(ex - lx, ey - ly);
			lx = ex;
			ly = ey;
		}
		double[] out = new double[Math.max(0, n)];
		int k = 0;
		for (int i = 0; i < n; i++) {
			double target = len[steps] * i / n;
			while (k < steps - 1 && len[k + 1] < target) {
				k++;
			}
			double span = len[k + 1] - len[k];
			double frac = span <= 0 ? 0 : (target - len[k]) / span;
			out[i] = -Math.PI / 2 + 2 * Math.PI * (k + frac) / steps;
		}
		return out;
	}

	/** Plus grande taille de jeton essayée, et la plus petite. */
	private static final int SIZE_MAX = 26;
	private static final int SIZE_MIN = 14;
	/** Largeur au-delà de laquelle le nom est raccourci par le jeton lui-même. */
	private static final int LABEL_MAX = 70;

	/**
	 * Choisit la géométrie du grimoire dans la zone (x, y, w, h en unités) : une ellipse qui épouse la zone, les
	 * jetons les plus grands possible sans se toucher, les noms du haut et du bas au-dessus et au-dessous de leur
	 * jeton, ceux des côtés à l'extérieur, tous dans la zone. widest : largeur du plus long nom, labelH : hauteur
	 * d'une plaque de nom (unités). Calcul pur, sans Minecraft (vérifié par GrimoireRingTest).
	 */
	static Ring ring(int seats, int widest, int labelH, int x, int y, int w, int h) {
		int n = Math.max(1, seats);
		widest = Math.min(LABEL_MAX, widest);
		// Les noms des côtés prennent sur la largeur du cercle : au plus un cinquième de la zone (raccourcis au-delà).
		int sideMax = Math.min(widest, Math.max(30, w / 5));
		// Plaque du nom : texte, 3 unités de marge de chaque côté, posée à 3 unités du jeton.
		int side = sideMax + 9;
		int lh = labelH + 1;
		Ring best = null;
		for (int size = SIZE_MAX; size >= SIZE_MIN; size -= 2) {
			int ry = (h - size - 2 * lh) / 2;
			int rx = (w - size - 2 * side) / 2;
			// Un ovale franc, ni galette ni œuf debout : il garde l'allure d'une table ronde.
			rx = Math.min(rx, ry * 17 / 10);
			ry = Math.min(ry, rx * 6 / 5);
			if (rx < 8 || ry < 8) {
				continue;
			}
			Ring r = new Ring(x + w / 2, y + h / 2, rx, ry, size, LABEL_MAX, sideMax, spread(n, rx, ry));
			double chord = chord(r, n);
			// Les noms du haut et du bas ne dépassent pas l'écart entre deux jetons voisins.
			best = r.withLabels((int) Math.max(26, Math.min(LABEL_MAX, chord - 6)));
			if (chord >= size * 1.15) {
				return best;
			}
		}
		if (best == null) {
			int rx = Math.max(8, w / 4);
			int ry = Math.max(8, h / 4);
			best = new Ring(x + w / 2, y + h / 2, rx, ry, SIZE_MIN, 26, sideMax, spread(n, rx, ry));
		}
		return best;
	}

	/** Plus petite distance entre deux sièges voisins (unités). */
	private static double chord(Ring r, int n) {
		if (n < 2) {
			return Double.MAX_VALUE;
		}
		double min = Double.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			min = Math.min(min, Math.hypot(r.x(i) - r.x(j), r.y(i) - r.y(j)));
		}
		return min;
	}

	/**
	 * Les jetons du grimoire en haute résolution, placés dans la zone (x, y, w, h en unités) : rôle, linceul,
	 * marqueurs, nom vers l'extérieur, infobulle détaillée (ligne du grimoire et note). Vide si la vue n'a pas de
	 * grimoire complet.
	 */
	static List<HdToken> hdTokens(int x, int y, int w, int h, Consumer<String> onClick) {
		JsonObject v = ClientState.view();
		List<JsonObject> seats = BaseScreen.objects(v, "seats");
		List<JsonObject> grim = BaseScreen.objects(v, "grimoire");
		if (grim.size() != seats.size() || seats.isEmpty()) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		int widest = 0;
		for (JsonObject s : seats) {
			String name = ClientState.str(s, "name");
			names.add(name == null ? "" : name);
			widest = Math.max(widest, StorytellerKit.textWidth(HdStyle.PETIT, names.getLast()));
		}
		int n = seats.size();
		Ring ring = ring(n, widest, StorytellerKit.lineHeight(HdStyle.PETIT), x, y, w, h);
		int scale = Hd.guiScale();
		List<HdToken> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			JsonObject s = seats.get(i);
			JsonObject row = grim.get(i);
			JsonObject role = ClientState.obj(row, "role");
			String name = names.get(i);
			double angle = ring.angle(i);
			// Les noms du haut et du bas se côtoient : on les raccourcit à l'écart entre deux jetons voisins ; ceux
			// des côtés, à la place gardée pour eux de part et d'autre du cercle (même règle que labelOutward).
			boolean sideways = Math.abs(Math.cos(angle)) > 0.8;
			int room = sideways ? ring.sideMax() : ring.labelMax();
			String shown = HdFonts.ellipsize(HdStyle.PETIT, scale, name, room * scale);
			HdToken t = new HdToken(ring.x(i), ring.y(i), ring.size(), shown,
				ClientState.str(role, "id"), ClientState.str(role, "team"), ClientState.bool(s, "alive"), false,
				onClick == null ? null : () -> onClick.accept(name));
			t.labelOutward(angle);
			// La plaque montre le nom raccourci ; la narration (et le message du widget) garde le nom entier.
			t.setMessage(Component.literal(name));
			if (ClientState.bool(row, "poisoned")) {
				t.marker("poison");
			}
			if (ClientState.bool(row, "protected")) {
				t.marker("protect");
			}
			if (ClientState.obj(row, "believed") != null) {
				t.marker("drunk");
			}
			if (ClientState.bool(row, "redHerring")) {
				t.marker("herring");
			}
			if (ClientState.bool(row, "master")) {
				t.marker("master");
			}
			if (!ClientState.bool(s, "alive") && ClientState.bool(s, "ghostVote")) {
				t.marker("ghost");
			}
			String note = ClientState.str(row, "note");
			t.tooltip(ClientState.str(row, "line") + (note != null ? "\nNote : " + note : ""));
			out.add(t);
		}
		return out;
	}
}
