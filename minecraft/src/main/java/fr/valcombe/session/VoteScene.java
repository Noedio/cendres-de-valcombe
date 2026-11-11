package fr.valcombe.session;

import java.util.List;

/**
 * État du vote à montrer dans le monde (horloge géante, mains levées, gibet).
 *
 * @param nominee l'accusé
 * @param defense true pendant le temps de parole de l'accusé
 * @param pointing joueur devant qui se trouve l'aiguille (null pendant la défense)
 * @param moveTicks durée du déplacement de l'aiguille jusqu'à ce joueur
 * @param raised mains levées dont l'aiguille n'est pas encore passée
 * @param locked mains comptées
 * @param threshold voix nécessaires
 */
public record VoteScene(String nominee, boolean defense, String pointing, int moveTicks,
	List<String> raised, List<String> locked, int threshold) {
}
