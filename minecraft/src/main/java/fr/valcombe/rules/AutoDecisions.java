package fr.valcombe.rules;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Conteur automatique : règles fixes et un peu de hasard. */
public class AutoDecisions implements Decisions {
	public double misregisterChance = 1.0 / 3;
	public double mayorBounceChance = 1.0 / 2;
	private final Map<String, Boolean> lastMisregister = new HashMap<>();

	@Override
	public boolean misregister(Game game, int seat, String what, String key) {
		// Jamais deux fois de suite pour le même pouvoir.
		if (lastMisregister.getOrDefault(key, false)) {
			lastMisregister.put(key, false);
			return false;
		}
		boolean yes = game.rng.chance(balanced(game, misregisterChance));
		lastMisregister.put(key, yes);
		return yes;
	}

	/**
	 * Le Conteur automatique garde la partie disputée : quand le mal est presque éliminé, ses
	 * faux-semblants jouent plus souvent ; quand il domine, plus rarement.
	 */
	static double balanced(Game game, double base) {
		List<Integer> alive = game.aliveSeats();
		if (alive.isEmpty()) {
			return base;
		}
		long evil = alive.stream().filter(s -> game.player(s).team() == Team.EVIL).count();
		double ratio = (double) evil / alive.size();
		if (ratio < 0.2) {
			return Math.min(0.6, base * 1.5);
		}
		if (ratio > 0.4) {
			return base * 0.6;
		}
		return base;
	}

	@Override
	public Integer mayorBounce(Game game, int mayor) {
		if (!game.rng.chance(balanced(game, mayorBounceChance))) {
			return null;
		}
		List<Integer> candidates = game.aliveSeats().stream()
			.filter(s -> s != mayor && game.player(s).team() == Team.GOOD).toList();
		return game.rng.pick(candidates);
	}

	@Override
	public Integer starpassSuccessor(Game game, List<Integer> candidates) {
		return game.rng.pick(candidates);
	}
}
