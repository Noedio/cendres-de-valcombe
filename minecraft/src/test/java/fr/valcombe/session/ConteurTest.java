package fr.valcombe.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;
import fr.valcombe.rules.Step;
import fr.valcombe.rules.Team;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Les outils du Conteur pour reprendre la main : pause, vote annulé, grâce, relais, pupitre. */
class ConteurTest {

	/** Faux serveur où l'on peut déconnecter des joueurs. */
	static class Host extends SessionTest.FakeHost {
		final Set<String> offline = new HashSet<>();
		final List<String> rejoined = new ArrayList<>();
		final List<String> woken = new ArrayList<>();

		@Override
		public boolean online(String player) {
			return !offline.contains(player);
		}

		@Override
		public void rejoin(String player, boolean night, Settings settings) {
			rejoined.add(player);
		}

		@Override
		public void wake(String player, boolean awake, Settings settings) {
			woken.add(player + (awake ? "+" : "-"));
		}
	}

	private static Session game(Host h, int players, Session.Mode mode, long seed) {
		Session s = new Session(h, Rng.of(new Random(seed)));
		for (int i = 1; i <= players; i++) {
			s.join("J" + i);
		}
		if (mode == Session.Mode.HUMAIN) {
			s.toggleStoryteller("Conteur");
		}
		s.start("Conteur", mode);
		return s;
	}

	/** Mène la partie jusqu'au premier jour, nominations ouvertes. */
	private static Session firstDay(Host h, Session.Mode mode, long seed) {
		Session s = game(h, 7, mode, seed);
		s.startNight();
		SessionTest.pump(s, h, new Random(seed));
		assertEquals(Game.Phase.DAY, s.game().phase());
		if (!s.nominationsOpen()) {
			s.setNominations(true);
		}
		return s;
	}

	private static void runTasks(Host h, int max) {
		for (int i = 0; i < max && !h.tasks.isEmpty(); i++) {
			h.tasks.poll().run();
		}
	}

	@Test
	void voteAnnuleRendLeursDroitsAuxDeux() {
		Host h = new Host();
		Session s = firstDay(h, Session.Mode.HUMAIN, 3);
		assertThrows(GameException.class, s::cancelVote, "pas de vote à annuler");
		s.nominate("J1", "J2");
		assertTrue(s.voteOpen());
		s.cancelVote();
		assertFalse(s.voteOpen(), "le vote est clos");
		assertTrue(s.game().nominationsToday().isEmpty(), "l'accusation est retirée du tableau du jour");
		s.nominate("J1", "J2"); // les deux peuvent de nouveau accuser et être accusés
		assertTrue(s.voteOpen());
	}

	@Test
	void laPauseSuspendLeVotePuisIlReprend() {
		Host h = new Host();
		Session s = firstDay(h, Session.Mode.HUMAIN, 4);
		s.nominate("J1", "J2");
		s.startVoteNow();
		s.togglePause();
		assertTrue(s.paused());
		assertEquals(true, s.view("J3", false).get("paused"));
		runTasks(h, 200);
		assertTrue(s.voteOpen(), "en pause, l'aiguille n'avance pas");
		assertThrows(GameException.class, () -> s.nominate("J3", "J4"), "pas d'accusation pendant la pause");
		s.togglePause();
		h.prompts.clear();
		while (!h.tasks.isEmpty() && s.voteOpen()) {
			h.tasks.poll().run();
		}
		assertFalse(s.voteOpen(), "le vote se termine après la reprise");
	}

	@Test
	void graceEtVictoireDeclaree() {
		Host h = new Host();
		Session s = firstDay(h, Session.Mode.HUMAIN, 5);
		assertThrows(GameException.class, s::pardon, "personne sur le billot");
		s.nominate("J1", "J2");
		for (int i = 1; i <= 7; i++) {
			s.vote("J" + i, true);
		}
		s.startVoteNow();
		h.prompts.clear();
		while (!h.tasks.isEmpty() && s.voteOpen()) {
			h.tasks.poll().run();
		}
		assertEquals("J2", s.game().player(s.game().onTheBlock()).name);
		s.pardon();
		assertEquals(-1, s.game().onTheBlock(), "plus personne sur le billot");
		s.endDay();
		assertTrue(s.game().player(s.game().seatOf("J2")).alive(), "le gracié n'est pas exécuté");
		s.declareWinner("mal");
		assertNull(s.game(), "la partie est terminée");
		assertEquals(Team.EVIL, s.lastGame().winner());
	}

	@Test
	void etapePrecedenteEtOrdreDeNuit() {
		Game g = new Game(List.of("A", "B", "C", "D", "E", "F", "G"), Rng.of(new Random(7)));
		g.setupRandom();
		g.startNight();
		assertThrows(GameException.class, g::previousStep, "aucune étape jouée");
		Step first = g.nextStep();
		g.resolve(first, List.of());
		Step second = g.nextStep();
		assertNotNull(second);
		List<Game.NightEntry> order = g.nightOrder();
		assertEquals(-1, order.get(0).state(), "la première étape est jouée");
		assertEquals(1, order.stream().filter(e -> e.state() == 0).count(), "une seule étape en cours");
		g.previousStep();
		Step again = g.nextStep();
		assertEquals(first.kind, again.kind);
		assertEquals(first.title(), again.title(), "on rejoue l'étape précédente");
	}

	@Test
	void joueurAbsentRelayePuisDeRetour() {
		Host h = new Host();
		Session s = firstDay(h, Session.Mode.HUMAIN, 6);
		assertThrows(GameException.class, () -> s.standIn("J3"), "un joueur connecté ne se relaie pas");
		h.offline.add("J3");
		s.onDisconnect("J3");
		assertTrue(SessionTest.has(h.prompts.getOrDefault("Conteur", List.of()), "/vc relayer J3"), "le Conteur est prévenu");
		s.standIn("J3");
		assertTrue(s.isStandIn("J3"));
		// Le relais vote à la place de l'absent, sans bloquer le vote.
		s.nominate("J1", "J2");
		s.startVoteNow();
		h.prompts.clear();
		while (!h.tasks.isEmpty() && s.voteOpen()) {
			h.tasks.poll().run();
		}
		assertFalse(s.voteOpen());
		// Retour du joueur : il reprend sa place.
		h.offline.remove("J3");
		s.onJoin("J3");
		assertFalse(s.isStandIn("J3"));
		assertTrue(h.rejoined.contains("J3"), "le serveur le remet à sa place");
	}

	@Test
	void absentDeNuitLaPartieAutomatiqueAttendPuisJoueALaPlace() {
		Host h = null;
		Session s = null;
		// On cherche une partie où quelqu'un doit choisir dès la première nuit, et on avance jusqu'à lui.
		for (long seed = 11; seed < 60 && (s == null || s.pendingChooser == null); seed++) {
			h = new Host();
			s = game(h, 7, Session.Mode.AUTO, seed);
			s.startNight();
			for (int i = 0; i < 400 && s.pendingChooser == null && s.game().phase() == Game.Phase.NIGHT; i++) {
				if (!h.tasks.isEmpty()) {
					h.tasks.poll().run();
				} else if (!h.prompts.isEmpty()) {
					String who = h.prompts.keySet().iterator().next();
					h.prompts.remove(who);
					s.sleep(who);
				} else {
					break;
				}
			}
		}
		String chooser = s.pendingChooser;
		assertNotNull(chooser, "quelqu'un doit choisir la première nuit");
		Step step = s.pendingStep;
		h.offline.add(chooser);
		s.onDisconnect(chooser);
		assertEquals(chooser, s.pendingChooser, "on lui laisse le temps de revenir");
		runTasks(h, 5);
		assertTrue(s.pendingStep != step || s.pendingChooser == null, "passé le délai, son choix est joué à sa place");
	}

	@Test
	void pupitreQuestionInformationRappelsEtParole() {
		Host h = new Host();
		Session s = firstDay(h, Session.Mode.HUMAIN, 8);
		// Question à un joueur, hors du déroulé.
		h.prompts.clear();
		s.ask("J4", 1);
		assertTrue(SessionTest.has(h.prompts.get("J4"), "/vc valider"));
		assertThrows(GameException.class, () -> s.ask("J4", 1), "il a déjà une question");
		s.choose("J4", "J5");
		s.validate("J4");
		assertEquals(List.of("J5"), s.desk.answers.get("J4"));
		assertNull(s.currentPrompt("J4"));
		// Information libre.
		assertEquals("Montré à J2.", s.show("J2", "Oui"));
		assertTrue(s.game().log().stream().anyMatch(l -> l.contains("montre à J2")));
		// Rappels : poser, retirer un seul.
		s.reminder("J2", "protégé");
		s.reminder("J2", "a menti");
		s.reminder("J2", "retirer protégé");
		assertEquals(List.of("a menti"), s.reminders.get("J2"));
		assertThrows(GameException.class, () -> s.reminder("J2", "retirer absent"));
		// Le Conteur a sous la main les jetons des rôles en jeu, le journal et la note de partie.
		s.note("partie", "J1 joue vite");
		Map<String, Object> view = s.view("Conteur", true);
		assertFalse(((List<?>) view.get("tokens")).isEmpty());
		assertFalse(((List<?>) view.get("journal")).isEmpty());
		assertEquals("J1 joue vite", view.get("gameNote"));
		assertNull(s.view("J1", false).get("journal"), "le journal reste au Conteur");
		// Temps de parole : demande, tour de table, arrêt.
		s.speechRequest("J6");
		assertEquals(List.of("J6"), s.desk.queue);
		s.speechNext();
		assertEquals("J6", s.desk.speaker);
		s.speechRound(30);
		assertNotNull(s.desk.speaker);
		assertEquals(30, s.desk.speechSeconds);
		s.speechStop();
		assertNull(s.desk.speaker);
		assertNull(s.view("J1", false).get("speech"));
		// Voix portée à tous, de jour seulement.
		s.toggleBroadcast();
		assertTrue(s.voiceTargets("Conteur").contains("J1"));
		s.endDay();
		s.startNight();
		assertFalse(s.desk.broadcast, "la nuit, la voix du Conteur ne porte plus à tous");
		// Réveil à la main : le joueur parle au Conteur seul à seul.
		s.wake("J2", true);
		assertTrue(h.woken.contains("J2+"));
		assertTrue(s.voiceTargets("Conteur").contains("J2"));
		s.wake("J2", false);
		assertTrue(h.woken.contains("J2-"));
	}

	@Test
	void salonMelangeExclusionEtEtat() {
		Host h = new Host();
		Session s = new Session(h, Rng.of(new Random(2)));
		for (int i = 1; i <= 4; i++) {
			s.join("J" + i);
		}
		assertEquals("non", s.lobbyStatus()[0], "quatre joueurs ne suffisent pas");
		s.addBots(3);
		assertEquals("oui", s.lobbyStatus()[0]);
		s.shuffleLobby();
		assertEquals(7, s.lobby().size());
		assertTrue(s.lobby().containsAll(List.of("J1", "J2", "J3", "J4")));
		s.kick("J2");
		assertFalse(s.lobby().contains("J2"));
		assertThrows(GameException.class, () -> s.kick("Inconnu"));
	}

	@Test
	void repriseGardeFiguresReglagesEtAnnonces() {
		Host h = new Host();
		Session s = new Session(h, Rng.of(new Random(9)));
		for (int i = 1; i <= 7; i++) {
			s.join("J" + i);
		}
		s.setFigure("sablier", true);
		s.setting("coiffe", "crane");
		s.setting("effets", "reduits");
		s.start("J1", Session.Mode.AUTO);
		s.startNight();
		SessionTest.pump(s, h, new Random(9));
		s.claim("J1", "COMMERE");
		s.stKill("J7"); // une action du Conteur est enregistrée tout de suite
		Host h2 = new Host();
		Session s2 = new Session(h2, Rng.of(new Random(9)));
		s2.restore(h.saved);
		assertTrue(s2.figures().contains("sablier"));
		assertEquals("crane", s2.settings().deadHat);
		assertFalse(s2.settings().fullEffects);
		assertNotNull(s2.claimOf("J1"));
		assertFalse(s2.game().player(s2.game().seatOf("J7")).alive(), "la mort décidée par le Conteur est gardée");
	}
}
