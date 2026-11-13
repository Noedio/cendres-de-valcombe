package fr.valcombe.client;

/**
 * Le serveur a-t-il une autre version du mod que le joueur ? Calcul pur (vérifié par VersionCheckTest).
 *
 * <p>Le bandeau rouge « Le serveur a une autre version du mod Valcombe » s'affichait à tort en partie solo : la
 * version était lue dans la dernière vue de la partie, quelle qu'elle soit. Une vue posée par le client lui-même (les
 * tests en jeu en fabriquent sans version), ou restée d'une connexion précédente, passait pour celle d'un serveur
 * « plus ancien ». Désormais :
 * <ul>
 * <li>seule une vue reçue du serveur de la connexion en cours compte (oubliée à chaque connexion et déconnexion) ;</li>
 * <li>le serveur intégré (partie solo, ou monde ouvert en réseau local par ce joueur) fait tourner le même mod que le
 * joueur : jamais d'écart ;</li>
 * <li>une version illisible (« ? ») ne dit rien : pas de bandeau ;</li>
 * <li>les métadonnées de construction (« 0.13.1+dev ») ne comptent pas.</li>
 * </ul>
 * Un vrai serveur d'une autre version (ou d'avant la 0.5.3, qui n'envoie pas la sienne) garde son bandeau.
 */
public final class VersionCheck {
	private VersionCheck() {
	}

	/**
	 * Version du serveur à afficher, ou null si elle est la même que celle du joueur (ou s'il n'y a rien à dire).
	 *
	 * @param seen       une vue est arrivée du serveur pendant cette connexion
	 * @param server     version envoyée dans cette vue (null : serveur d'avant la 0.5.3, qui ne l'envoie pas)
	 * @param mine       version du mod chez le joueur
	 * @param integrated la partie tourne sur le serveur intégré du jeu (solo, ou monde ouvert en réseau local)
	 */
	public static String mismatch(boolean seen, String server, String mine, boolean integrated) {
		if (integrated || !seen) {
			return null;
		}
		if (server == null) {
			return "plus ancienne";
		}
		String s = core(server);
		if (s.isEmpty() || s.equals("?") || s.equals(core(mine))) {
			return null;
		}
		return server;
	}

	/** La version sans ses métadonnées de construction (« 0.13.1+dev » : « 0.13.1 »). */
	static String core(String version) {
		if (version == null) {
			return "";
		}
		String v = version.strip();
		int plus = v.indexOf('+');
		return plus >= 0 ? v.substring(0, plus) : v;
	}
}
