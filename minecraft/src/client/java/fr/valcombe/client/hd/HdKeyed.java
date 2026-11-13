package fr.valcombe.client.hd;

/**
 * Un élément qui garde son état d'une reconstruction de l'écran à l'autre (texte tapé, défilement, focus).
 * Les écrans de Valcombe se reconstruisent à chaque nouvelle vue envoyée par le serveur : sans clé, un champ
 * perdrait ce qu'on y tape et une liste reviendrait en haut. La clé doit être unique dans l'écran.
 */
public interface HdKeyed {
	/** La clé de l'élément, ou null s'il n'a rien à garder. */
	String key();
}
