package fr.valcombe.admin;

/**
 * Requête de l'API d'administration refusée : le statut HTTP à renvoyer et un message en français,
 * lisible tel quel par la personne qui pilote le serveur (le launcher l'affiche sans le retoucher).
 */
public final class AdminException extends RuntimeException {
	private final int status;

	public AdminException(int status, String message) {
		super(message);
		this.status = status;
	}

	/** Statut HTTP : 400 requête fautive, 404 introuvable, 409 impossible dans cet état, 413 trop gros… */
	public int status() {
		return status;
	}
}
