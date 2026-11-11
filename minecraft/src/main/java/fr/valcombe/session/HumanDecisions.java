package fr.valcombe.session;

import fr.valcombe.rules.AutoDecisions;
import fr.valcombe.rules.Game;

import java.util.List;

/**
 * Consignes du Conteur humain pour les décisions prises pendant la résolution d'un pouvoir
 * (le moteur ne peut pas attendre une réponse) ; à défaut, le Conteur automatique décide.
 */
public final class HumanDecisions extends AutoDecisions {
	public enum Policy { HASARD, TOUJOURS, JAMAIS }

	/** Valeur de mayorTarget : l'attaque est détournée et personne ne meurt. */
	public static final String NOBODY = "*personne*";

	/** Enregistrement trompeur du Mouchard et de l'Excommunié. */
	public Policy misregister = Policy.HASARD;
	/** Nom du joueur qui meurt à la place du Châtelain ; "" = le Châtelain meurt ; null = au hasard. */
	public String mayorTarget;
	/** Nom du Sbire qui succède à la Goule ; null = au hasard. */
	public String successor;

	@Override
	public boolean misregister(Game game, int seat, String what, String key) {
		return switch (misregister) {
			case TOUJOURS -> true;
			case JAMAIS -> false;
			case HASARD -> super.misregister(game, seat, what, key);
		};
	}

	@Override
	public Integer mayorBounce(Game game, int mayor) {
		if (mayorTarget == null) {
			return super.mayorBounce(game, mayor);
		}
		if (mayorTarget.isEmpty()) {
			return null;
		}
		if (mayorTarget.equals(NOBODY)) {
			return -1; // personne ne meurt
		}
		int s = game.seatOf(mayorTarget);
		return s >= 0 && s != mayor ? s : null;
	}

	@Override
	public Integer starpassSuccessor(Game game, List<Integer> candidates) {
		if (successor != null) {
			int s = game.seatOf(successor);
			if (candidates.contains(s)) {
				return s;
			}
		}
		return super.starpassSuccessor(game, candidates);
	}
}
