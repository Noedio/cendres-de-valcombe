package fr.valcombe.admin;

import java.util.List;

/**
 * Ce que l'API d'administration montre des mondes de Valcombe (GET /admin/etat, champ « mondes »).
 *
 * <p>Pour l'instant le jeu n'a qu'un monde : la vue par défaut, {@link #NONE}, renvoie une liste vide.
 * Le gestionnaire de mondes (sprint 10) s'y branchera avec {@code ValcombeMod.setWorldsView(...)}.
 * La méthode est appelée sur le fil du serveur : elle peut lire l'état du jeu sans précaution.
 */
@FunctionalInterface
public interface WorldsView {
	/**
	 * Un monde.
	 *
	 * @param id identifiant stable (« partie-principale », « atelier-carte-3 »…)
	 * @param name nom affiché
	 * @param kind sorte : hub, partie, essai, atelier-carte, atelier-structures
	 * @param state état : charge, decharge, suspendu…
	 * @param players joueurs présents
	 */
	record World(String id, String name, String kind, String state, int players) {
	}

	/** Pas encore de gestionnaire de mondes : aucun monde à montrer. */
	WorldsView NONE = List::of;

	List<World> worlds();
}
