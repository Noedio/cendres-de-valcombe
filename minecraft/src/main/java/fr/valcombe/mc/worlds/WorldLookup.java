package fr.valcombe.mc.worlds;

import fr.valcombe.rules.GameException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Retrouve un monde à partir de ce qu'un opérateur tape : son identifiant, son nom (sans souci des majuscules ni des
 * accents), le début ou un morceau de l'un ou de l'autre, ou un nom proche (une ou deux lettres de travers). Le premier
 * critère qui désigne un seul monde l'emporte ; si plusieurs mondes répondent, rien n'est choisi au hasard.
 */
public final class WorldLookup {
	private WorldLookup() {
	}

	/**
	 * Le monde que désigne text parmi entries ; une GameException (message pour le joueur) si aucun ne répond, ou si
	 * plusieurs répondent aussi bien.
	 */
	public static WorldEntry resolve(List<WorldEntry> entries, String text) {
		String typed = text == null ? "" : text.strip();
		String slug = WorldRegistry.slug(typed);
		if (typed.isEmpty() || slug.isEmpty()) {
			throw new GameException("Donnez l'identifiant ou le nom d'un monde. La liste : /vc monde liste.");
		}
		List<Predicate<WorldEntry>> rules = List.of(
			e -> e.id().equalsIgnoreCase(typed),
			e -> e.id().equals(slug),
			e -> WorldRegistry.slug(e.nom()).equals(slug),
			e -> e.id().startsWith(slug) || WorldRegistry.slug(e.nom()).startsWith(slug),
			e -> e.id().contains(slug) || WorldRegistry.slug(e.nom()).contains(slug));
		for (Predicate<WorldEntry> rule : rules) {
			List<WorldEntry> found = entries.stream().filter(rule).toList();
			if (found.size() == 1) {
				return found.get(0);
			}
			if (found.size() > 1) {
				throw ambiguous(typed, found);
			}
		}
		// Un nom proche : le plus petit nombre de lettres à changer, s'il reste raisonnable pour la longueur tapée.
		int limit = Math.max(1, slug.length() / 4);
		int best = Integer.MAX_VALUE;
		List<WorldEntry> closest = new ArrayList<>();
		for (WorldEntry e : entries) {
			int d = Math.min(distance(slug, e.id()), distance(slug, WorldRegistry.slug(e.nom())));
			if (d < best) {
				best = d;
				closest.clear();
			}
			if (d == best) {
				closest.add(e);
			}
		}
		if (best <= limit && closest.size() == 1) {
			return closest.get(0);
		}
		if (best <= limit) {
			throw ambiguous(typed, closest);
		}
		throw new GameException("Aucun monde « " + typed + " ». La liste : /vc monde liste.");
	}

	private static GameException ambiguous(String typed, List<WorldEntry> found) {
		List<String> names = found.stream().map(e -> e.id() + " (« " + e.nom() + " »)").toList();
		return new GameException("Plusieurs mondes répondent à « " + typed + " » : " + String.join(", ", names)
			+ ". Donnez son identifiant.");
	}

	/**
	 * Nombre de fautes de frappe pour passer de a à b : une lettre ajoutée, retirée, changée, ou deux lettres voisines
	 * interverties (distance de Damerau-Levenshtein restreinte).
	 */
	static int distance(String a, String b) {
		int[][] d = new int[a.length() + 1][b.length() + 1];
		for (int i = 0; i <= a.length(); i++) {
			d[i][0] = i;
		}
		for (int j = 0; j <= b.length(); j++) {
			d[0][j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			for (int j = 1; j <= b.length(); j++) {
				int change = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + change);
				if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
					d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
				}
			}
		}
		return d[a.length()][b.length()];
	}
}
