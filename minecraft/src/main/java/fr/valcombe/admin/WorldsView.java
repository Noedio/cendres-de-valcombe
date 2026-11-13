package fr.valcombe.admin;

import java.util.List;

/**
 * Ce que l'API d'administration montre des mondes de Valcombe (GET /admin/etat, champ « mondes »).
 *
 * <p>Le gestionnaire de mondes s'y branche au démarrage du serveur ({@code ValcombeMod.setWorldsView(...)},
 * avec {@code WorldManager.adminView()}). Sans lui (avant le démarrage, ou s'il n'a pas pu démarrer), la vue par
 * défaut, {@link #NONE}, renvoie une liste vide.
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
	 * @param state état : charge, decharge, remise-a-neuf (suspendu viendra avec les parties)
	 * @param players joueurs présents
	 */
	record World(String id, String name, String kind, String state, int players) {
	}

	/** Pas encore de gestionnaire de mondes : aucun monde à montrer. */
	WorldsView NONE = List::of;

	List<World> worlds();
}
