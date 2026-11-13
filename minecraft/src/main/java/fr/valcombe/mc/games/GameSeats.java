package fr.valcombe.mc.games;

import fr.valcombe.session.Session;

import java.util.List;

/**
 * Qui est à quelle table, quand plusieurs parties se jouent en même temps sur le serveur (voir {@link Games}).
 * <p>
 * Un joueur n'appartient qu'à une table à la fois : inscrit, Conteur, spectateur ou assis à une partie. S'asseoir à
 * une autre table le libère de celle où il ne faisait qu'attendre ; il ne peut pas quitter une partie en cours qu'il
 * joue ou qu'il conte. Cette classe ne connaît pas Minecraft : elle ne travaille que sur les sessions, pour être
 * vérifiée en test avec deux sessions vivantes.
 */
public final class GameSeats {
	/** Une table : le nom de sa partie (pour les messages) et sa session. */
	public record Table(String id, String name, Session session) {
	}

	private GameSeats() {
	}

	/**
	 * La table d'attache de ce joueur : celle dont la partie en cours le retient (il y joue, il la conte), sinon la
	 * première où il est inscrit, Conteur, élève ou spectateur ; null s'il n'est à aucune.
	 */
	public static Table home(List<Table> tables, String player) {
		if (player == null) {
			return null;
		}
		for (Table t : tables) {
			if (t.session().holds(player)) {
				return t;
			}
		}
		for (Table t : tables) {
			if (t.session().belongs(player)) {
				return t;
			}
		}
		return null;
	}

	/**
	 * Pourquoi ce joueur ne peut pas venir à la table {@code target} (une partie en cours qu'il joue ou qu'il conte le
	 * retient ailleurs), ou null. Sans effet : le tableau des parties la pose avant d'emmener le joueur.
	 */
	public static String heldElsewhere(List<Table> tables, Table target, String player) {
		for (Table t : tables) {
			if (t == target || t.session() == target.session()) {
				continue;
			}
			if (t.session().holds(player)) {
				return "Tu es retenu par la partie en cours de « " + t.name() + " » : elle doit finir avant que tu ne rejoignes « "
					+ target.name() + " ».";
			}
		}
		return null;
	}

	/**
	 * Ce joueur veut s'asseoir à la table {@code target} : renvoie pourquoi il ne le peut pas (une partie en cours le
	 * retient ailleurs), ou null après l'avoir libéré des autres tables où il n'était qu'en attente (inscrit, Conteur
	 * d'une partie pas encore lancée, spectateur). Chaque table quittée l'annonce à ses joueurs.
	 */
	public static String admit(List<Table> tables, Table target, String player) {
		String held = heldElsewhere(tables, target, player);
		if (held != null) {
			return held;
		}
		for (Table t : tables) {
			if (t == target || t.session() == target.session() || !t.session().belongs(player)) {
				continue;
			}
			Session s = t.session();
			try {
				if (s.lobby().contains(player) && s.game() == null) {
					s.leave(player);
				}
				if (player.equals(s.storyteller()) && s.game() == null) {
					s.toggleStoryteller(player);
				}
				if (s.isSpectator(player)) {
					s.spectate(player); // bascule : il ne regarde plus
				}
			} catch (RuntimeException e) {
				// Une table qui refuse de le rendre (état changé entre-temps) le garde : il n'en est pas moins admis ici.
			}
		}
		return null;
	}
}
