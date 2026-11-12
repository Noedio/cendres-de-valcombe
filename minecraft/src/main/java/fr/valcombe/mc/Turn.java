package fr.valcombe.mc;

/**
 * Orientation d'une construction qu'on pose : d'abord retournée ou non (miroir est-ouest), puis
 * tournée de 0 à 3 quarts de tour dans le sens des aiguilles d'une montre, vue de dessus.
 * Tout se calcule sur des entiers : une case (x, z) d'un rectangle de `width` × `length` cases.
 */
record Turn(int quarters, boolean flipped) {
	static final Turn NONE = new Turn(0, false);

	Turn {
		quarters = (quarters % 4 + 4) % 4;
	}

	/** Un quart de tour de plus. */
	Turn rotated() {
		return new Turn(quarters + 1, flipped);
	}

	/** Retournée telle qu'on la voit (miroir est-ouest de la construction déjà tournée). */
	Turn mirrored() {
		return new Turn(-quarters, !flipped);
	}

	/** Largeur (est-ouest) une fois orientée. */
	int width(int width, int length) {
		return quarters % 2 == 0 ? width : length;
	}

	/** Profondeur (nord-sud) une fois orientée. */
	int length(int width, int length) {
		return quarters % 2 == 0 ? length : width;
	}

	/** Où arrive, à l'est, la case (x, z). */
	int x(int x, int z, int width, int length) {
		int fx = flipped ? width - 1 - x : x;
		return switch (quarters) {
			case 1 -> length - 1 - z;
			case 2 -> width - 1 - fx;
			case 3 -> z;
			default -> fx;
		};
	}

	/** Où arrive, au sud, la case (x, z). */
	int z(int x, int z, int width, int length) {
		int fx = flipped ? width - 1 - x : x;
		return switch (quarters) {
			case 1 -> fx;
			case 2 -> length - 1 - z;
			case 3 -> width - 1 - fx;
			default -> z;
		};
	}

	/** « tournée de 90°, retournée », ou une chaîne vide pour l'orientation d'origine. */
	String label() {
		String turn = quarters == 0 ? "" : "tournée de " + quarters * 90 + "°";
		return flipped ? (turn.isEmpty() ? "retournée" : turn + ", retournée") : turn;
	}
}
