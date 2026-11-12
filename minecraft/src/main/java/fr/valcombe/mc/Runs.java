package fr.valcombe.mc;

import java.util.Arrays;

/**
 * Suite d'entiers rangée par plages (valeur, longueur). Un terrain est surtout fait de longues suites
 * du même bloc : l'instantané d'une carte entière tient ainsi en quelques centaines de kilo-octets.
 */
final class Runs {
	/** Paires (valeur, longueur). */
	private int[] data = new int[64];
	private int used;
	private int size;

	void add(int value) {
		if (used > 0 && data[used - 2] == value) {
			data[used - 1]++;
		} else {
			if (used == data.length) {
				data = Arrays.copyOf(data, used * 2);
			}
			data[used++] = value;
			data[used++] = 1;
		}
		size++;
	}

	/** Nombre de valeurs. */
	int size() {
		return size;
	}

	/** Les paires (valeur, longueur), telles qu'on les enregistre. */
	int[] packed() {
		return Arrays.copyOf(data, used);
	}

	/** Relit des paires enregistrées ; renvoie null si elles sont abîmées. */
	static Runs unpack(int[] packed) {
		if (packed == null || packed.length % 2 != 0) {
			return null;
		}
		long total = 0;
		for (int i = 1; i < packed.length; i += 2) {
			if (packed[i] <= 0) {
				return null;
			}
			total += packed[i];
		}
		if (total > Integer.MAX_VALUE) {
			return null;
		}
		Runs r = new Runs();
		r.data = packed.clone();
		r.used = packed.length;
		r.size = (int) total;
		return r;
	}

	/** Plus grande valeur (-1 si la suite est vide). */
	int max() {
		int m = -1;
		for (int i = 0; i < used; i += 2) {
			m = Math.max(m, data[i]);
		}
		return m;
	}

	/** Lecture dans l'ordre : les rangs demandés ne font qu'avancer. */
	final class Reader {
		private int pair;
		/** Rang de la première valeur de la plage en cours. */
		private int start;

		int at(int index) {
			if (index < start) {
				pair = 0;
				start = 0;
			}
			while (index >= start + data[pair + 1]) {
				start += data[pair + 1];
				pair += 2;
			}
			return data[pair];
		}
	}

	Reader reader() {
		return new Reader();
	}
}
