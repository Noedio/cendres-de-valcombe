package fr.valcombe.session;

import java.util.List;
import java.util.Map;

/** Ce que la session attend du serveur de jeu (Minecraft, ou un faux serveur en test). */
public interface Host {
	/** Donne (ou reprend) au Conteur ses outils en jeu (bâton du Conteur). */
	default void storytellerTools(String name, boolean on) {
	}

	/**
	 * Ce joueur rejoint la table (il s'inscrit, il devient Conteur, la partie commence) : l'amener là où se joue la
	 * partie s'il est ailleurs (au hub, dans un atelier).
	 */
	default void toTable(String player) {
	}

	/** Un voyageur arrive : le placer sur un trône libre de la place. */
	default void addSeat(String player) {
	}

	/** Un joueur de la partie se reconnecte : le remettre à sa place (maison ou trône) et dans l'état de la phase. */
	default void rejoin(String player, boolean night, Settings settings) {
	}

	/** Le Conteur réveille (ou rendort) un joueur à la main, la nuit : il retrouve (ou perd) la vue. */
	default void wake(String player, boolean awake, Settings settings) {
	}

	/**
	 * Le Conteur convoque un joueur dans la salle à l'écart : tous deux y sont amenés, face à face.
	 * Renvoie false si la carte n'a pas de salle de convocation (ils restent alors où ils sont).
	 */
	default boolean summon(String storyteller, String player) {
		return false;
	}

	/** Fin d'une convocation : le joueur retrouve son trône ; le Conteur aussi retourne d'où il venait si storytellerToo. */
	default void dismiss(String storyteller, String player, boolean storytellerToo) {
	}

	/** Position du siège d'un joueur dans le monde (x, y, z), ou null. */
	default double[] seatPosition(String player) {
		return null;
	}

	/** Parties jouées par ce joueur sur le serveur (figure du Doyen). */
	default int gamesPlayed(String player) {
		return 0;
	}

	/** Charge une carte de la bibliothèque (scénario) ; renvoie un message, ou lève GameException. */
	default String loadMap(String id) {
		return "";
	}

	/** Identifiant de la carte de la bibliothèque en place, si elle n'a pas été retouchée depuis ; chaîne vide sinon. */
	default String mapInPlace() {
		return "";
	}

	/** Une carte est en cours de construction ou de chargement : la partie ne peut pas commencer. */
	default boolean busy() {
		return false;
	}

	/** Message à tout le serveur. */
	void announce(String text);

	/** Message privé (ignoré si le joueur est hors ligne). */
	void tell(String player, Msg msg);

	boolean online(String player);

	/** Titre à l'écran pour ces joueurs. */
	default void title(List<String> players, String title, String subtitle) {
	}

	/** Son d'ambiance : "bell" (cloche), "death" (glas), "clock" (aiguille du vote). */
	default void sound(List<String> players, String sound) {
	}

	/** Ramène les joueurs à leur siège. */
	default void gather(List<String> players) {
	}

	/** Met à jour l'apparence des morts (fantômes) et de ceux qui ont encore leur vote. */
	default void deadPlayers(List<String> dead, List<String> withGhostVote) {
	}

	/**
	 * L'hôte suit le temps réel : la partie sans Conteur peut enchaîner seule (nuit qui tombe d'elle-même).
	 * Faux pour les hôtes de test et de simulation, qui jouent toutes les tâches d'un coup.
	 */
	default boolean realTime() {
		return true;
	}

	/** Montre ou cache les noms au-dessus des têtes des joueurs assis à la table. */
	default void nameTags(boolean visible) {
	}

	/**
	 * Jetons flottants au-dessus des sièges : nom du joueur → « couleur|texte »
	 * (couleur : white, gray, gold, red, aqua). Liste vide : on retire tout.
	 */
	default void seatBoards(Map<String, String> labels) {
	}

	/** Mise en scène d'une exécution sur ce joueur. */
	default void execution(String player) {
	}

	/** Célébration de fin de partie. */
	default void celebrate(List<String> players) {
	}

	/**
	 * Jetons 3D au-dessus des sièges : nom du joueur → modèle (jeton_vivant, jeton_mort,
	 * jeton_fantome, jeton_<rôle>). flip : les jetons se retournent (révélation).
	 */
	default void seatTokens(Map<String, String> models, boolean flip) {
	}

	/** Vote en cours à montrer dans le monde ; null : plus de vote. */
	default void voteScene(VoteScene scene) {
	}

	/** Les joueurs s'assoient sur leur siège (enforce : ils ne peuvent pas se lever). */
	default void sit(List<String> players, boolean enforce) {
	}

	/** Minuteur affiché à tous (barre en haut de l'écran) ; seconds <= 0 : on le retire. */
	default void timer(List<String> players, String title, int seconds) {
	}

	/** Deux joueurs chuchotent : une bulle apparaît au-dessus d'eux. */
	/** Signe rapide au-dessus de la tête d'un joueur (✔, ✘, ?, !…). */
	default void sign(String player, String symbol, String color) {
	}

	default void whisperBubble(String from, String to) {
	}

	/** Une accusation : un trait de lumière relie l'accusateur à l'accusé. */
	default void accusation(String from, String to) {
	}

	/** Titre honorifique d'un joueur (tiré de ses statistiques), ou chaîne vide. */
	default String playerTitle(String player) {
		return "";
	}

	/** Passe un joueur en spectateur (ou le remet en jeu). */
	default void spectator(String player, boolean on) {
	}

	/** Bilan d'une partie terminée (statistiques, compte rendu). */
	default void report(GameReport report) {
	}

	/** Enregistre la sauvegarde de la partie (chaîne vide : plus de partie à reprendre). */
	default void persist(String json) {
	}

	/** Affiche (ou retire, si prompt == null) l'invite en cours d'un joueur. */
	default void prompt(String player, Prompt prompt) {
	}

	/** Exécute r dans `ticks` ticks (20 ticks = 1 seconde). */
	void schedule(int ticks, Runnable r);

	/** Installe les joueurs en cercle, dans l'ordre de la liste, autour de `anchor` (nom d'un joueur). */
	void seatPlayers(List<String> players, String anchor);

	/** Effets de nuit (obscurité, immobilité) ou retour au jour. */
	void nightEffects(List<String> players, boolean night, Settings settings);

	/** Fin de partie : remettre les joueurs en état normal. */
	void endGame(List<String> players);

	/** Une partie va commencer : c'est le moment de garder la carte telle qu'elle est, pour la rendre intacte à la fin. */
	default void gameStarted() {
	}

	/** La partie est finie : la carte retrouve son état d'avant, dans delayTicks (0 : tout de suite). */
	default void restoreMap(int delayTicks) {
	}

	/** La partie est finie et la carte reste comme elle est (remise en état coupée dans les réglages). */
	default void keepMap() {
	}
}
