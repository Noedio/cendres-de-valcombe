package fr.valcombe.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Générateur aléatoire : next(n) renvoie un entier entre 0 et n - 1. */
@FunctionalInterface
public interface Rng {
	int next(int n);

	static Rng of(Random random) {
		return random::nextInt;
	}

	default boolean chance(double p) {
		if (p <= 0) {
			return false;
		}
		if (p >= 1) {
			return true;
		}
		return next(1000) < (int) Math.floor(p * 1000);
	}

	default <T> T pick(List<T> list) {
		return list.isEmpty() ? null : list.get(next(list.size()));
	}

	default <T> List<T> shuffle(List<T> list) {
		List<T> l = new ArrayList<>(list);
		for (int i = l.size() - 1; i > 0; i--) {
			Collections.swap(l, i, next(i + 1));
		}
		return l;
	}

	default <T> List<T> take(List<T> list, int n) {
		List<T> s = shuffle(list);
		return new ArrayList<>(s.subList(0, Math.min(n, s.size())));
	}
}
