package fr.valcombe.rules;

/** Action refusée par les règles ; le message est destiné au joueur. */
public class GameException extends RuntimeException {
	public GameException(String message) {
		super(message);
	}
}
