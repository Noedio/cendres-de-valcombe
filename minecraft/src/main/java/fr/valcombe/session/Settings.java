package fr.valcombe.session;

/** Réglages de partie modifiables par le Conteur. */
public final class Settings {
	public boolean nightFreeze = true;
	public boolean nightDark = true;
	/** La nuit, chacun dort dans sa maison du village (si le village est construit). */
	public boolean nightHouses = true;
	/** La nuit, les micros des joueurs sont coupés (Simple Voice Chat). */
	public boolean nightMute = true;
	/** Temps de parole de l'accusé avant le vote. */
	public int defenseSeconds = 20;
	/** Ticks entre deux sièges pour l'aiguille du vote (20 = 1 seconde). */
	public int clockTicks = 30;
	public int stepDelayTicks = 30;
	/** Effets complets (flammes, gibet, feux d'artifice) ; false : effets réduits pour les petites machines. */
	public boolean fullEffects = true;
	/** Les spectateurs voient le grimoire (pour apprendre en regardant). */
	public boolean spectatorGrimoire;
	/** Coiffe des morts : « chapeau », « crane » ou « aucun ». */
	public String deadHat = "chapeau";
	/**
	 * Avec un Conteur humain, les rôles ne sont pas distribués au lancement : le Conteur convoque chaque
	 * joueur dans la salle à l'écart pour lui remettre le sien (ceux qui restent le reçoivent à la première nuit).
	 */
	public boolean roleRoom = true;
	/** À la fin de la partie, la carte retrouve son état d'avant (blocs cassés ou posés, objets au sol). */
	public boolean restoreMap = true;
}
