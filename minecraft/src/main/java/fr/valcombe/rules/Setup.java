package fr.valcombe.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Mise en place : tirage des rôles, Somnambule, bluffs du Démon. */
public final class Setup {
	/** Un rôle distribué ; believed = rôle que le joueur croit avoir. */
	public record Entry(Role role, Role believed) {
		public Entry(Role role) {
			this(role, role);
		}
	}

	private Setup() {
	}

	/** Tire un rôle par joueur (non placés). Renvoie null si le nombre de joueurs est invalide. */
	public static List<Entry> drawBag(int players, Rng rng) {
		Distribution.Counts c = Distribution.counts(players);
		if (c == null) {
			return null;
		}
		int citadins = c.citadin();
		int etrangers = c.etranger();
		List<Role> demons = rng.take(Role.ofType(RoleType.DEMON), c.demon());
		List<Role> minions = rng.take(Role.ofType(RoleType.SBIRE), c.sbire());
		if (minions.contains(Role.MARGRAVE)) {
			etrangers += 2;
			citadins -= 2;
		}
		List<Role> outsiders = rng.take(Role.ofType(RoleType.ETRANGER), etrangers);
		List<Role> townsfolk = rng.take(Role.ofType(RoleType.CITADIN), citadins);

		List<Entry> bag = new ArrayList<>();
		for (List<Role> group : List.of(demons, minions, outsiders, townsfolk)) {
			for (Role r : group) {
				if (r == Role.SOMNAMBULE) {
					List<Role> free = Role.ofType(RoleType.CITADIN).stream()
						.filter(t -> !townsfolk.contains(t)).toList();
					bag.add(new Entry(r, rng.pick(free)));
				} else {
					bag.add(new Entry(r));
				}
			}
		}
		return bag;
	}

	/** Trois rôles du bien absents de la partie, dont au moins un Citadin. */
	public static List<Role> pickBluffs(List<Entry> bag, Rng rng) {
		Set<Role> used = new HashSet<>();
		for (Entry e : bag) {
			used.add(e.role());
			used.add(e.believed());
		}
		List<Role> freeTown = Role.ofType(RoleType.CITADIN).stream().filter(r -> !used.contains(r)).toList();
		List<Role> freeOut = Role.ofType(RoleType.ETRANGER).stream()
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
