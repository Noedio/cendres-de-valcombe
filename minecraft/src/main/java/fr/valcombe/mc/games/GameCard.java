package fr.valcombe.mc.games;

import fr.valcombe.rules.Distribution;
import fr.valcombe.rules.Game;
import fr.valcombe.session.Session;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * La fiche d'une partie telle que la montrent le tableau des parties du hub, l'écran « Parties » du menu J, l'API
 * d'administration et le launcher : son nom, son état (ouverte, en cours, en pause, en suspens), où elle en est (jour et
 * phase), ses joueurs et ses places, son Conteur, et combien de monde s'y trouve.
 * <p>
 * Une fiche est une photographie, prise sur le fil du serveur ({@link #of}) ; elle ne connaît pas Minecraft et se vérifie
 * en test sur une session vivante. Les questions propres à un joueur (peut-il la rejoindre, la regarder, qu'y est-il)
 * sont posées à la session ({@link #joinRefusal}, {@link Session#watchRefusal}, {@link #seat}).
 *
 * @param id          l'identifiant du monde de la partie
 * @param name        son nom affiché
 * @param main        la partie principale du serveur
 * @param trial       une partie d'essai
 * @param status      ouverte, en cours, en pause ou en suspens
 * @param phase       la phase de la partie lancée, ou null au salon
 * @param night       numéro de la nuit (partie lancée)
 * @param day         numéro du jour (partie lancée)
 * @param seated      joueurs assis à la partie lancée (joueurs fictifs compris)
 * @param lobby       inscrits au salon
 * @param places      places du village (le nombre de joueurs au plus)
 * @param storyteller le Conteur, ou null (Conteur automatique)
 * @param present     joueurs dans le monde de la partie en ce moment
 * @param watchers    joueurs qui la regardent depuis le tableau des parties
 */
public record GameCard(String id, String name, boolean main, boolean trial, Status status, Game.Phase phase, int night, int day,
		int seated, int lobby, int places, String storyteller, int present, int watchers) {

	/** L'état d'une partie, tel qu'on le lit de loin. */
	public enum Status {
		/** Pas de partie lancée : le salon est ouvert, on peut s'inscrire. */
		OUVERTE("ouverte", "Ouverte"),
		/** Une partie se joue. */
		EN_COURS("en-cours", "En cours"),
		/** Le Conteur l'a mise en pause. */
		EN_PAUSE("en-pause", "En pause"),
		/** Mise en suspens : horloge gelée, joueurs au hub, elle reprendra où elle en est. */
		SUSPENDUE("suspendue", "En suspens");

		/** Le code de l'état (vue des joueurs, API). */
		public final String code;
		/** L'état en clair, pour les joueurs. */
		public final String label;

		Status(String code, String label) {
			this.code = code;
			this.label = label;
		}
	}

	/** L'état de la partie de cette session. */
	public static Status statusOf(Session s) {
		if (s.suspended()) {
			return Status.SUSPENDUE;
		}
		Game g = s.game();
		if (g == null || g.winner() != null) {
			return Status.OUVERTE;
		}
		return s.paused() ? Status.EN_PAUSE : Status.EN_COURS;
	}

	/** Les places d'un village de {@code seats} trônes : jamais plus que le nombre de joueurs qu'une partie accepte. */
	public static int places(int seats) {
		return seats <= 0 ? Distribution.MAX_PLAYERS : Math.min(seats, Distribution.MAX_PLAYERS);
	}

	/**
	 * La fiche de cette partie en ce moment.
	 *
	 * @param seats   les trônes de son village (0 : pas encore de village)
	 * @param present joueurs dans son monde en ce moment
	 */
	public static GameCard of(String id, String name, boolean main, boolean trial, Session s, int seats, int present) {
		Game g = s.game();
		boolean running = g != null && g.winner() == null;
		return new GameCard(id, name, main, trial, statusOf(s), running ? g.phase() : null, running ? g.night() : 0,
			running ? g.day() : 0, running ? g.size() : 0, s.lobby().size(), places(seats), s.storyteller(), present,
			s.watchers().size());
	}

	/** Une partie a-t-elle été lancée (de la mise en place à la fin), en suspens compris ? */
	public boolean running() {
		return phase != null;
	}

	/** Les joueurs de la partie : assis à la partie lancée, sinon inscrits au salon. */
	public int players() {
		return running() ? seated : lobby;
	}

	/** « Partie principale », « Partie d'essai » ou « Partie ». */
	public String kindLabel() {
		return main ? "Partie principale" : trial ? "Partie d'essai" : "Partie";
	}

	/** Où en est la partie : « Nuit 2 », « Jour 3 », « Mise en place », « Salon ouvert ». */
	public String phaseLine() {
		if (!running()) {
			return "Salon ouvert";
		}
		return switch (phase) {
			case NIGHT -> "Nuit " + night;
			case DAY -> "Jour " + day;
			default -> "Mise en place";
		};
	}

	/** Le code de la phase : {@code salon}, {@code mise-en-place}, {@code nuit}, {@code jour}. */
	public String phaseCode() {
		if (!running()) {
			return "salon";
		}
		return switch (phase) {
			case NIGHT -> "nuit";
			case DAY -> "jour";
			default -> "mise-en-place";
		};
	}

	/** Le numéro du jour ou de la nuit en cours (0 au salon et pendant la mise en place). */
	public int number() {
		if (!running()) {
			return 0;
		}
		return switch (phase) {
			case NIGHT -> night;
			case DAY -> day;
			default -> 0;
		};
	}

	/** « 7 joueurs sur 12 places », « 3 inscrits sur 16 places », « Personne d'inscrit, 16 places ». */
	public String playersLine() {
		int n = players();
		String places = this.places + (this.places > 1 ? " places" : " place");
		if (running()) {
			return n + (n > 1 ? " joueurs" : " joueur") + " sur " + places;
		}
		return n == 0 ? "Personne d'inscrit, " + places : n + (n > 1 ? " inscrits" : " inscrit") + " sur " + places;
	}

	/** « Conteur : Yoann » ou « Conteur automatique ». */
	public String storytellerLine() {
		return storyteller == null ? "Conteur automatique" : "Conteur : " + storyteller;
	}

	/** « 2 spectateurs », « 1 spectateur », ou vide. */
	public String watchersLine() {
		return watchers == 0 ? "" : watchers + (watchers > 1 ? " spectateurs" : " spectateur");
	}

	/** Le salon est-il plein ? */
	public boolean full() {
		return !running() && lobby >= places;
	}

	/** La fiche, pour la vue des joueurs, l'API et le launcher (codes et textes en clair). */
	public Map<String, Object> view() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("nom", name);
		m.put("sorte", trial ? "essai" : "partie");
		m.put("principale", main);
		m.put("statut", status.code);
		m.put("statutTexte", status.label);
		m.put("phase", phaseCode());
		m.put("numero", number());
		m.put("phaseTexte", phaseLine());
		m.put("joueurs", players());
		m.put("places", places);
		m.put("joueursTexte", playersLine());
		m.put("conteur", storyteller);
		m.put("surPlace", present);
		m.put("spectateurs", watchers);
		return m;
	}

	// ------------------------------------------------------------------
	// Un joueur devant la fiche
	// ------------------------------------------------------------------

	/** Ce que ce joueur est à cette table : {@code joueur}, {@code conteur}, {@code inscrit}, {@code regarde}, {@code spectateur}, ou null. */
	public static String seat(Session s, String player) {
		Game g = s.game();
		if (g != null && g.winner() == null && g.seatOf(player) >= 0) {
			return "joueur";
		}
		if (player.equals(s.storyteller())) {
			return "conteur";
		}
		if (s.lobby().contains(player)) {
			return "inscrit";
		}
		if (s.isWatcher(player)) {
			return "regarde";
		}
		return s.isSpectator(player) ? "spectateur" : null;
	}

	/**
	 * Pourquoi ce joueur ne peut pas rejoindre cette partie, ou null. Rejoindre, c'est s'inscrire au salon d'une partie
	 * ouverte, ou retourner à sa place dans une partie qu'on joue ou qu'on conte. Une partie lancée se regarde, elle ne se
	 * rejoint pas ; une partie en suspens attend sa reprise.
	 */
	public static String joinRefusal(Session s, String player, int seats) {
		if (s.suspended()) {
			return "La partie est en suspens : elle reprendra où elle en est, et ses joueurs seront rappelés.";
		}
		String seat = seat(s, player);
		if ("joueur".equals(seat) || "conteur".equals(seat) || "inscrit".equals(seat)) {
			return null; // il y retourne
		}
		Game g = s.game();
		if (g != null && g.winner() == null) {
			return "La partie a commencé : tu peux la regarder.";
		}
		if (s.lobby().size() >= places(seats)) {
			return "Le salon est plein : les " + places(seats) + " places sont prises.";
		}
		return null;
	}

	/** Le libellé du bouton « Rejoindre » pour ce joueur : « Y retourner » s'il a déjà sa place à cette table. */
	public static String joinLabel(Session s, String player) {
		String seat = seat(s, player);
		return "joueur".equals(seat) || "conteur".equals(seat) || "inscrit".equals(seat) ? "Y retourner" : "Rejoindre";
	}
}
