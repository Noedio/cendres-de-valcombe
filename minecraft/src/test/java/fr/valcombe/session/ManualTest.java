package fr.valcombe.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;
import fr.valcombe.rules.Role;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Le Conteur aux commandes : mode manuel, minuteurs pilotés, ordre de réveil modifiable en partie. */
class ManualTest {

	private static Session game(SessionTest.FakeHost h, Session.Mode mode, long seed) {
		Session s = new Session(h, Rng.of(new Random(seed)));
		for (int i = 1; i <= 7; i++) {
			s.join("J" + i);
		}
		if (mode == Session.Mode.HUMAIN) {
			s.toggleStoryteller("Conteur");
		}
		s.start("Conteur", mode);
		return s;
	}

	private static void drain(SessionTest.FakeHost h, int max) {
		for (int i = 0; i < max && !h.tasks.isEmpty(); i++) {
			h.prompts.clear();
			h.tasks.poll().run();
		}
	}

	@Test
	void enModeManuelLaNuitAttendLeConteur() {
		SessionTest.FakeHost h = new SessionTest.FakeHost();
		Session s = game(h, Session.Mode.HUMAIN, 21);
		s.setting("manuel", "oui");
		s.startNight();
		drain(h, 50);
		assertTrue(s.waitingNext, "aucun réveil ne part seul");
		assertTrue(s.game().nightOrder().stream().noneMatch(e -> e.state() == 0), "personne n'est encore réveillé");
		s.skip(); // « Étape suivante »
		assertTrue(s.game().phase() != Game.Phase.NIGHT || s.game().nightOrder().stream().anyMatch(e -> e.state() <= 0),
			"le Conteur a lancé le premier réveil");
	}

	@Test
	void enModeManuelChaqueVoteAttendLeConteur() {
		SessionTest.FakeHost h = new SessionTest.FakeHost();
		Session s = game(h, Session.Mode.HUMAIN, 22);
		s.startNight();
		SessionTest.pump(s, h, new Random(22));
		assertEquals(Game.Phase.DAY, s.game().phase());
		s.setting("manuel", "oui");
		s.setNominations(true);
		assertEquals(0, s.councilLeft, "pas de minuteur de Conseil en mode manuel");
		s.nominate("J1", "J2");
		s.nominate("J3", "J4");
		s.setNominations(false);
		for (int i = 0; i < 2000 && !h.tasks.isEmpty() && !s.waitingNextVote(); i++) {
			h.prompts.clear();
			h.tasks.poll().run();
		}
		assertTrue(s.waitingNextVote(), "après le premier vote, le suivant attend le Conteur");
		drain(h, 200);
		assertFalse(s.voteOpen(), "rien ne part seul");
		s.startVoteNow(); // « Vote suivant »
		assertTrue(s.voteOpen());
	}

	@Test
	void minuteurDuConseilProlongeEtArrete() {
		SessionTest.FakeHost h = new SessionTest.FakeHost();
		Session s = game(h, Session.Mode.HUMAIN, 23);
		s.startNight();
		SessionTest.pump(s, h, new Random(23));
		assertEquals(Game.Phase.DAY, s.game().phase());
		s.setting("duree_conseil", "120");
		s.setNominations(true);
		int before = s.councilLeft;
		assertEquals(120, before, "Conseil minuté");
		assertNotNull(s.runningTimer());
		s.timerControl("prolonger", 60);
		assertEquals(before + 60, s.councilLeft);
		s.timerControl("arreter", 0);
		assertEquals(0, s.councilLeft);
		assertTrue(s.nominationsOpen(), "le Conseil reste ouvert");
		assertThrows(GameException.class, () -> s.timerControl("passer", 0), "plus de minuteur en cours");
	}

	@Test
	void sansConteurLaNuitTombeDElleMeme() {
		SessionTest.FakeHost h = new SessionTest.FakeHost();
		h.realTime = true;
		Session s = game(h, Session.Mode.AUTO, 24);
		assertEquals(Game.Phase.READY, s.game().phase());
		for (int i = 0; i < 20 && s.game().phase() != Game.Phase.NIGHT && !h.tasks.isEmpty(); i++) {
			h.tasks.poll().run();
		}
		assertEquals(Game.Phase.NIGHT, s.game().phase(), "la première nuit tombe toute seule");
	}

	@Test
	void ordreDeReveilModifiableEnPartie() {
		Game g = fr.valcombe.rules.GameTestAccess.fixed(Role.GOULE, Role.ALCHIMISTE, Role.VEILLEUSE, Role.CHAPELAIN, Role.TEMPLIER);
		List<Role> before = g.plannedOrder();
		assertTrue(before.size() >= 2);
		Role second = before.get(1);
		g.moveInNightOrder(second, -1);
		assertEquals(second, g.plannedOrder().get(0), "le rôle passe en tête");
		assertThrows(GameException.class, () -> g.moveInNightOrder(second, -1), "déjà le premier");
		assertThrows(GameException.class, () -> g.moveInNightOrder(Role.MARTYR, 1), "rôle absent de la partie");
	}
}
