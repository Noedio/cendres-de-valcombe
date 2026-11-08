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
		boolean yes = game.rng.chance(misregisterChance);
		lastMisregister.put(key, yes);
		return yes;
	}

	@Override
	public Integer mayorBounce(Game game, int mayor) {
		if (!game.rng.chance(mayorBounceChance)) {
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
