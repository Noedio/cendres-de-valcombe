package fr.valcombe.rules;

import java.util.List;

/** Décisions laissées au Conteur. L'implémentation par défaut est le Conteur automatique. */
public interface Decisions {
	/** Le Mouchard ou l'Excommunié s'enregistre-t-il « à tort » pour ce pouvoir ? */
	boolean misregister(Game game, int seat, String what, String key);

	/** Siège qui meurt à la place du Châtelain, ou null s'il meurt lui-même. */
	Integer mayorBounce(Game game, int mayor);

	/** Sbire qui devient la Goule quand elle se sacrifie (null si aucun). */
	Integer starpassSuccessor(Game game, List<Integer> candidates);
}
