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

	/** Le script classique : les 22 rôles des Cendres de Valcombe. */
	public static Set<Role> fullScript() {
		return Role.classic();
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
		return check(script, players, null);
	}

	/** Même vérification, avec la répartition propre à un scénario (null : la répartition habituelle). */
	public static String check(Set<Role> script, int players, Distribution.Counts counts) {
		Distribution.Counts c = counts != null ? counts : Distribution.counts(players);
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
		return drawBag(players, rng, script, 0);
	}

	/** Tirage avec un écart d'Étrangers imposé (figure de la Sentinelle : -1, 0 ou +1), si le script le permet. */
	public static List<Entry> drawBag(int players, Rng rng, Set<Role> script, int outsiderShift) {
		return drawBag(players, rng, script, outsiderShift, Set.of(), null);
	}

	/** Tire n rôles dans pool, en prenant d'abord ceux qui sont imposés. */
	private static List<Role> takeWith(Rng rng, List<Role> pool, int n, Set<Role> forced) {
		List<Role> first = rng.shuffle(pool.stream().filter(forced::contains).toList());
		List<Role> out = new ArrayList<>(first.subList(0, Math.min(n, first.size())));
		out.addAll(rng.take(pool.stream().filter(r -> !forced.contains(r)).toList(), n - out.size()));
		return out;
	}

	/**
	 * Tirage d'un scénario : forced, les rôles toujours en jeu (dans la limite des places de leur type) ;
	 * counts, la répartition propre au scénario pour ce nombre de joueurs (null : la répartition habituelle).
	 */
	public static List<Entry> drawBag(int players, Rng rng, Set<Role> script, int outsiderShift, Set<Role> forced,
		Distribution.Counts counts) {
		Distribution.Counts c = counts != null ? counts : Distribution.counts(players);
		if (c == null || check(script, players, counts) != null) {
			return null;
		}
		List<Role> townPool = ofType(script, RoleType.CITADIN);
		List<Role> outPool = new ArrayList<>(ofType(script, RoleType.ETRANGER));
		List<Role> minionPool = new ArrayList<>(ofType(script, RoleType.SBIRE));
		List<Role> demonPool = new ArrayList<>(ofType(script, RoleType.DEMON));
		// Un rôle qui change le nombre d'Étrangers (le Margrave, ou un rôle créé) n'est tiré
		// que si le script a de quoi fournir la répartition qu'il impose.
		for (List<Role> pool : List.of(minionPool, demonPool)) {
			pool.removeIf(r -> {
				int b = Game.outsiderBonus(r);
				return b != 0 && (outPool.size() < c.etranger() + b || townPool.size() < c.citadin() - b
					|| c.etranger() + b < 0);
			});
		}
		if (minionPool.size() < c.sbire() || demonPool.size() < c.demon()) {
			return null;
		}
		int citadins = c.citadin();
		int etrangers = c.etranger();
		List<Role> demons = takeWith(rng, demonPool, c.demon(), forced);
		List<Role> minions = takeWith(rng, minionPool, c.sbire(), forced);
		int bonus = 0;
		for (Role r : demons) {
			bonus += Game.outsiderBonus(r);
		}
		for (Role r : minions) {
			bonus += Game.outsiderBonus(r);
		}
		// Plusieurs effets cumulés : on reste dans ce que le script peut fournir.
		bonus = Math.max(-etrangers, Math.min(bonus, Math.min(outPool.size() - etrangers, citadins - 1)));
		etrangers += bonus;
		citadins -= bonus;
		if (outsiderShift != 0 && etrangers + outsiderShift >= 0 && etrangers + outsiderShift <= outPool.size()
			&& citadins - outsiderShift >= 1 && citadins - outsiderShift <= townPool.size()) {
			etrangers += outsiderShift;
			citadins -= outsiderShift;
		}
		// Le Somnambule a besoin d'un Citadin libre à croire incarner.
		if (townPool.size() <= citadins) {
			outPool.remove(Role.SOMNAMBULE);
			if (outPool.size() < etrangers) {
				return null;
			}
		}
		List<Role> outsiders = takeWith(rng, outPool, etrangers, forced);
		List<Role> townsfolk = takeWith(rng, townPool, citadins, forced);

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
