package fr.valcombe.session;

/** Réglages de partie modifiables par le Conteur. */
public final class Settings {
	public boolean nightFreeze = true;
	public boolean nightDark = true;
	/** La nuit, chacun dort dans sa maison du village (si le village est construit). */
	public boolean nightHouses = true;
	/** La nuit, les micros des joueurs sont coupés (Simple Voice Chat). */
	public boolean nightMute = true;
	public int voteSeconds = 20;
	public int stepDelayTicks = 30;
}
