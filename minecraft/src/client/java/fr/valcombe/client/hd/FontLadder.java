package fr.valcombe.client.hd;

/**
 * Échelle des tailles de police déclarées dans assets/valcombe/font/hd/ (une définition par face et par taille).
 *
 * <p>Minecraft rastérise une police TTF à une seule taille en pixels. Pour qu'un texte soit net à toutes les
 * échelles d'interface, tools/make_ui.py déclare chaque face à une série de tailles en pixels réels (oversample 1),
 * et la boîte à outils prend la taille déclarée la plus proche de la taille voulue, puis corrige l'écart (quelques
 * pour cent au plus) par une mise à l'échelle filtrée. Aucune dépendance à Minecraft ici : la classe est testée
 * par JUnit, qui vérifie aussi qu'elle correspond aux fichiers produits par le script.
 */
public final class FontLadder {
	/** Tailles déclarées, en pixels réels (identiques à LADDER dans tools/make_ui.py). */
	static final int[] SIZES = {8, 9, 10, 11, 12, 13, 15, 17, 19, 22, 25, 28, 32, 36, 41, 47, 54, 62, 71, 82, 94, 108, 124, 142};

	/** Hauteur de ligne, en multiple de la taille (LINE dans tools/make_ui.py). */
	public static final float LINE = 1.3f;

	/** Les faces de la boîte à outils. */
	public enum Face {
		/** Cinzel : capitales romaines gravées, pour les titres et bandeaux. */
		TITRE("titre", 12, 142),
		/** Alegreya Sans : texte courant, très lisible en petit. */
		TEXTE("texte", 8, 108),
		/** Alegreya Sans Medium : boutons, libellés appuyés. */
		FORT("fort", 8, 108);

		/** Préfixe des définitions : font/hd/&lt;id&gt;_&lt;taille&gt;.json. */
		public final String id;
		public final int min;
		public final int max;

		Face(String id, int min, int max) {
			this.id = id;
			this.min = min;
			this.max = max;
		}
	}

	private FontLadder() {
	}

	/** Les tailles déclarées pour cette face, de la plus petite à la plus grande. */
	public static int[] sizes(Face face) {
		return java.util.Arrays.stream(SIZES).filter(s -> s >= face.min && s <= face.max).toArray();
	}

	/** La taille déclarée la plus proche (en rapport, pas en écart absolu) d'une taille voulue en pixels réels. */
	public static int pick(Face face, float pixels) {
		int best = -1;
		double bestGap = Double.MAX_VALUE;
		for (int s : SIZES) {
			if (s < face.min || s > face.max) {
				continue;
			}
			double gap = Math.abs(Math.log(s / (double) Math.max(1f, pixels)));
			if (gap < bestGap) {
				bestGap = gap;
				best = s;
			}
		}
		return best;
	}

	/** Hauteur de ligne d'une taille déclarée, en pixels (Math.round, comme jround dans le script). */
	public static int lineHeight(int size) {
		return Math.round(size * LINE);
	}
}
