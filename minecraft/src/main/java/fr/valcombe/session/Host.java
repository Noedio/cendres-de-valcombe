package fr.valcombe.session;

import java.util.List;
import java.util.Map;

/** Ce que la session attend du serveur de jeu (Minecraft, ou un faux serveur en test). */
public interface Host {
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
}
