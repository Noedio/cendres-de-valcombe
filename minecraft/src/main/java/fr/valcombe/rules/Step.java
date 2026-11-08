package fr.valcombe.rules;

import java.util.List;

/** Une étape de nuit : un réveil à jouer. */
public final class Step {
	public enum Kind { MINION_INFO, DEMON_INFO, NEW_DEMON, ROLE }

	public final Kind kind;
	/** Rôle réveillé (kind == ROLE), sinon null. */
	public final Role role;
	/** Siège réveillé, ou -1 pour MINION_INFO. */
	public final int seat;
	/** Sièges réveillés ensemble (MINION_INFO). */
	public final List<Integer> seats;

	Step(Kind kind, Role role, int seat, List<Integer> seats) {
		this.kind = kind;
		this.role = role;
		this.seat = seat;
		this.seats = seats;
	}

	public boolean isChoose() {
		return role != null && role.choose > 0;
	}

	public int choose() {
		return role == null ? 0 : role.choose;
	}

	public boolean notSelf() {
		return role != null && role.notSelf;
	}

	public String title() {
		return switch (kind) {
			case MINION_INFO -> "Les Sbires se réveillent";
			case DEMON_INFO -> "Le Démon se réveille";
			case NEW_DEMON -> "Tu te réveilles";
			case ROLE -> role.displayName;
		};
	}
}
