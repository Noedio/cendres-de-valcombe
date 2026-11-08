package fr.valcombe.rules;

import java.util.Map;

/** Répartition des types de rôles selon le nombre de joueurs (Conteur exclu). */
public final class Distribution {
	public static final int MIN_PLAYERS = 5;
	public static final int MAX_PLAYERS = 16;

	/** Citadins, Étrangers, Sbires, Démon. */
	public record Counts(int citadin, int etranger, int sbire, int demon) {
		public int of(RoleType t) {
			return switch (t) {
				case CITADIN -> citadin;
				case ETRANGER -> etranger;
				case SBIRE -> sbire;
				case DEMON -> demon;
			};
		}
	}

	private static final Map<Integer, Counts> TABLE = Map.ofEntries(
		Map.entry(5, new Counts(3, 0, 1, 1)),
		Map.entry(6, new Counts(3, 1, 1, 1)),
		Map.entry(7, new Counts(5, 0, 1, 1)),
		Map.entry(8, new Counts(5, 1, 1, 1)),
		Map.entry(9, new Counts(5, 2, 1, 1)),
		Map.entry(10, new Counts(7, 0, 2, 1)),
		Map.entry(11, new Counts(7, 1, 2, 1)),
		Map.entry(12, new Counts(7, 2, 2, 1)),
		Map.entry(13, new Counts(9, 0, 3, 1)),
		Map.entry(14, new Counts(9, 1, 3, 1)),
		Map.entry(15, new Counts(9, 2, 3, 1)),
		Map.entry(16, new Counts(10, 2, 3, 1)));

	private Distribution() {
	}

	/** @return la répartition, ou null si le nombre de joueurs n'est pas pris en charge. */
	public static Counts counts(int players) {
		return TABLE.get(players);
	}
}
