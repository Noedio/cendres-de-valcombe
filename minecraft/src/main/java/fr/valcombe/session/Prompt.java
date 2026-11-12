package fr.valcombe.session;

import java.util.List;

/** Ce qu'on attend d'un joueur en ce moment ; le serveur l'affiche sous forme de menu. */
public sealed interface Prompt {
	/** Un joueur du cercle, proposé comme cible. */
	record Option(int seat, String name, boolean alive, boolean selected) {
	}

	/** Désigner `count` joueur(s) la nuit ; optional : on peut valider sans désigner personne (pouvoir à usage unique). */
	record Choose(String title, int count, List<Option> options, boolean optional) implements Prompt {
	}

	/** Lire une information, puis se rendormir. */
	record Info(String title, String text) implements Prompt {
	}

	/** Consulter le grimoire (Mouchard), puis se rendormir. */
	record Grimoire(List<String> lines) implements Prompt {
	}

	/** Lever la main ou non. */
	record Vote(String nominee, boolean ghost) implements Prompt {
	}

	/** Le Conteur tranche en direct (la nuit est en pause) : une question, des réponses. */
	record Decide(String title, String question, List<String> options) implements Prompt {
	}

	/**
	 * Prêter un rôle à `count` joueurs (Jongleur la nuit, Bavard après le Conseil) : players, les joueurs
	 * possibles ; roles, les rôles du script (identifiants). Réponse : /vc deviner joueur=rôle …
	 */
	record Guess(String title, String text, int count, List<String> players, List<String> roles) implements Prompt {
	}

	/** Le Conteur relit l'information avant l'envoi. */
	record Confirm(String title, String to, String text, String truth) implements Prompt {
	}
}
