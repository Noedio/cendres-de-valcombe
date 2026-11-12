package fr.valcombe.session;

/** Réglages de partie modifiables par le Conteur. */
public final class Settings {
	public boolean nightFreeze = true;
	public boolean nightDark = true;
	/** La nuit, chacun dort dans sa maison du village (si le village est construit). */
	public boolean nightHouses = true;
	/** La nuit, les micros des joueurs sont coupés (Simple Voice Chat). */
	public boolean nightMute = true;
	/**
	 * Conseil de la table : toutes les accusations d'abord, puis un vote pour chacune, dans l'ordre
	 * des accusations, sans temps de défense. false : accusation, défense puis vote, une à la fois.
	 */
	public boolean nominationsFirst = true;
	/** Durée du Conseil avant le passage aux votes, en secondes (0 : le Conteur passe aux votes lui-même). */
	public int councilSeconds = 0;
	/** Exécution seulement à la majorité absolue des vivants (au lieu de la moitié). */
	public boolean absoluteMajority;
	/** Le mal gagne dès qu'il est majoritaire parmi les vivants. */
	public boolean evilMajorityWins;
	/**
	 * Mode manuel (avec un Conteur humain) : aucun minuteur n'enchaîne seul. Le Conteur ouvre et clôt le
	 * Conseil, lance chaque vote, appelle chaque rôle la nuit et lance le vote après la défense.
	 */
	public boolean manual;
	/** Discussion libre minutée au début de chaque jour, en minutes (0 : aucune). */
	public int discussionMinutes;
	/** Sans Conteur humain : secondes entre la fin du Conseil et le coucher du soleil. */
	public int duskSeconds = 10;
	/** Sans Conteur humain : la nuit tombe d'elle-même (au début de la partie et après chaque coucher du soleil). */
	public boolean autoNight = true;
	/** Le carnet des joueurs ne s'ouvre que la nuit (temps de réflexion). */
	public boolean notebookNightOnly;
	/** Noms au-dessus des têtes des joueurs (toujours cachés la nuit). */
	public boolean nameTags = true;
	/** Portée d'un chuchotement, en blocs (voix « chuchoter »). */
	public int whisperRange = 4;
	/** Portée d'un cri, en blocs (voix « crier ») ; la voix normale porte à la distance réglée dans Simple Voice Chat. */
	public int shoutRange = 96;
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
