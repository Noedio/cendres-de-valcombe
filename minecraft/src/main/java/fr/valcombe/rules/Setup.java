package fr.valcombe.rules;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Mise en place : tirage des rôles dans un script, Somnambule, bluffs du Démon. */
public final class Setup {
	/** Un rôle distribué ; believed = rôle que le joueur croit avoir. */
	public record Entry(Role role, Role believed) {
		public Entry(Role role) {
			this(role, role);
		}
	}

	/** Le script complet : les 22 rôles. */
	public static Set<Role> fullScript() {
		return EnumSet.allOf(Role.class);
	}

	private Setup() {
	}

	private static List<Role> ofType(Set<Role> script, RoleType t) {
		return Role.ofType(t).stream().filter(script::contains).toList();
	}

	/**
	 * Vérifie qu'un script permet une partie à n joueurs.
	 * @return null si c'est possible, sinon la raison (pour le Conteur)
	 */
	public static String check(Set<Role> script, int players) {
		Distribution.Counts c = Distribution.counts(players);
		if (c == null) {
			return "Il faut entre " + Distribution.MIN_PLAYERS + " et " + Distribution.MAX_PLAYERS + " joueurs.";
		}
		List<String> missing = new ArrayList<>();
		if (ofType(script, RoleType.DEMON).size() < c.demon()) {
			missing.add("un Démon");
		}
		if (ofType(script, RoleType.SBIRE).size() < c.sbire()) {
			missing.add(c.sbire() + " Sbire(s)");
		}
		if (ofType(script, RoleType.ETRANGER).size() < c.etranger()) {
			missing.add(c.etranger() + " Étranger(s)");
		}
		if (ofType(script, RoleType.CITADIN).size() < c.citadin()) {
			missing.add(c.citadin() + " Citadin(s)");
		}
		return missing.isEmpty() ? null
			: "Le script ne contient pas assez de rôles pour " + players + " joueurs : il faut au moins "
				+ String.join(", ", missing) + ".";
	}

	public static List<Entry> drawBag(int players, Rng rng) {
		return drawBag(players, rng, fullScript());
	}

	/** Tire un rôle par joueur dans le script (non placés). Renvoie null si impossible. */
	public static List<Entry> drawBag(int players, Rng rng, Set<Role> script) {
		Distribution.Counts c = Distribution.counts(players);
		if (c == null || check(script, players) != null) {
			return null;
		}
		List<Role> townPool = ofType(script, RoleType.CITADIN);
		List<Role> outPool = new ArrayList<>(ofType(script, RoleType.ETRANGER));
		List<Role> minionPool = new ArrayList<>(ofType(script, RoleType.SBIRE));
		// Le Margrave n'est tiré que si le script a de quoi fournir ses deux Étrangers.
		if (outPool.size() < c.etranger() + 2 || townPool.size() < c.citadin() - 2) {
			minionPool.remove(Role.MARGRAVE);
			if (minionPool.size() < c.sbire()) {
				return null;
			}
		}
		int citadins = c.citadin();
		int etrangers = c.etranger();
		List<Role> demons = rng.take(ofType(script, RoleType.DEMON), c.demon());
		List<Role> minions = rng.take(minionPool, c.sbire());
		if (minions.contains(Role.MARGRAVE)) {
			etrangers += 2;
			citadins -= 2;
		}
		// Le Somnambule a besoin d'un Citadin libre à croire incarner.
		if (townPool.size() <= citadins) {
			outPool.remove(Role.SOMNAMBULE);
			if (outPool.size() < etrangers) {
				return null;
			}
		}
		List<Role> outsiders = rng.take(outPool, etrangers);
		List<Role> townsfolk = rng.take(townPool, citadins);

		List<Entry> bag = new ArrayList<>();
		for (List<Role> group : List.of(demons, minions, outsiders, townsfolk)) {
			for (Role r : group) {
				if (r == Role.SOMNAMBULE) {
					List<Role> free = townPool.stream().filter(t -> !townsfolk.contains(t)).toList();
					bag.add(new Entry(r, rng.pick(free)));
				} else {
					bag.add(new Entry(r));
				}
			}
		}
		return bag;
	}

	public static List<Role> pickBluffs(List<Entry> bag, Rng rng) {
		return pickBluffs(bag, rng, fullScript());
	}

	/** Trois rôles du bien du script, absents de la partie, dont au moins un Citadin. */
	public static List<Role> pickBluffs(List<Entry> bag, Rng rng, Set<Role> script) {
		Set<Role> used = new HashSet<>();
		for (Entry e : bag) {
			used.add(e.role());
			used.add(e.believed());
		}
		List<Role> freeTown = ofType(script, RoleType.CITADIN).stream().filter(r -> !used.contains(r)).toList();
		List<Role> freeOut = ofType(script, RoleType.ETRANGER).stream()
			.filter(r -> !used.contains(r) && r != Role.SOMNAMBULE).toList();
		List<Role> bluffs = new ArrayList<>();
		Role first = rng.pick(freeTown);
		if (first != null) {
			bluffs.add(first);
		}
		List<Role> rest = new ArrayList<>();
		for (Role r : freeTown) {
			if (r != first) {
				rest.add(r);
			}
		}
		rest.addAll(freeOut);
		bluffs.addAll(rng.take(rest, 3 - bluffs.size()));
		return bluffs;
	}
}
