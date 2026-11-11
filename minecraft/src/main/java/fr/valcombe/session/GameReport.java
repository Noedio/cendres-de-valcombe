package fr.valcombe.session;

import java.util.List;

/**
 * Bilan d'une partie terminée : sert au compte rendu (Discord) et aux statistiques.
 *
 * @param winner "GOOD" ou "EVIL"
 * @param reason pourquoi la partie s'arrête
 * @param days nombre de jours joués
 * @param script nom du script de rôles
 * @param storyteller Conteur humain (null en mode automatique)
 * @param players un bilan par joueur, dans l'ordre du cercle
 * @param log historique complet de la partie
 */
public record GameReport(String winner, String reason, int days, String script, String storyteller,
	List<Player> players, List<String> log) {

	/**
	 * @param role rôle réel (nom affiché)
	 * @param believed rôle que le joueur croyait avoir, si différent (sinon null)
	 * @param team "GOOD" ou "EVIL"
	 */
	public record Player(String name, String role, String believed, String team, boolean alive, boolean bot) {
		public boolean won(String winner) {
			return team.equals(winner);
		}
	}
}
