package fr.valcombe.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.Rng;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.Team;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** Joueurs fictifs : annonces de rôle (bluff du mal), accusations et votes d'eux-mêmes. */
class BotBrainTest {
	@Test
	void annoncesEtAccusations() {
		int accusedGames = 0;
		for (int seed = 0; seed < 10; seed++) {
			SessionTest.FakeHost h = new SessionTest.FakeHost();
			Session s = new Session(h, Rng.of(new Random(seed)));
			s.addBots(9);
			s.start("Conteur", Session.Mode.AUTO);
			s.startNight();
			while (!h.tasks.isEmpty() && s.game() != null) {
				h.tasks.poll().run();
			}
			if (s.game() == null) {
				continue;
			}
			Game g = s.game();
			for (Game.Seat p : g.players()) {
				Role c = s.claimOf(p.name);
				if (!p.alive()) {
					continue;
				}
				assertTrue(c != null, p.name + " annonce un rôle");
				if (p.team() == Team.GOOD) {
					assertTrue(c == p.believed(), "le bien annonce ce qu'il croit être");
				} else {
					assertFalse(c.evil(), "le mal bluffe avec un rôle du bien");
				}
			}
			if (!g.nominationsToday().isEmpty()) {
				accusedGames++;
			}
		}
		assertTrue(accusedGames >= 5, "les joueurs fictifs accusent d'eux-mêmes (" + accusedGames + "/10)");
	}
}
