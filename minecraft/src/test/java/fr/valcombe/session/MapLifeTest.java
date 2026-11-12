package fr.valcombe.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Cycle de vie de la carte vu de la session : instantané au lancement, remise en état à la fin. */
class MapLifeTest {
	/** Note, dans l'ordre, ce que la session demande pour la carte. */
	static class MapHost extends SessionTest.FakeHost {
		final List<String> calls = new ArrayList<>();
		boolean busy;

		@Override
		public boolean busy() {
			return busy;
		}

		@Override
		public void gameStarted() {
			calls.add("instantané");
		}

		@Override
		public void seatPlayers(List<String> players, String anchor) {
			calls.add("installation");
		}

		@Override
		public void restoreMap(int delayTicks) {
			calls.add("remise:" + delayTicks);
		}

		@Override
		public void keepMap() {
			calls.add("gardée");
		}
	}

	private static Session started(MapHost h) {
		Session s = new Session(h, Rng.of(new Random(5)));
		s.toggleStoryteller("Conteur");
		s.addBots(6);
		s.start("Conteur", Session.Mode.AUTO);
		return s;
	}

	@Test
	void instantanePrisAvantDInstallerLesJoueurs() {
		MapHost h = new MapHost();
		started(h);
		assertEquals(List.of("instantané", "installation"), h.calls);
	}

	@Test
	void partieArreteeLaCarteEstRemiseEnEtatSansAttendre() {
		MapHost h = new MapHost();
		Session s = started(h);
		h.calls.clear();
		s.stop();
		assertEquals(List.of("remise:0"), h.calls);
	}

	@Test
	void partieGagneeLaCarteEstRemiseEnEtatApresLaRevelation() {
		MapHost h = new MapHost();
		Session s = started(h);
		h.calls.clear();
		int delay = Session.restoreDelay(s.game().size());
		for (int b = 1; b <= 6 && s.game() != null; b++) {
			s.stKill("Bot" + b);
		}
		assertNull(s.game(), "la partie s'est terminée");
		assertEquals(List.of("remise:" + delay), h.calls);
		assertTrue(delay >= Session.RESTORE_DELAY && Session.RESTORE_DELAY >= 200, "le temps de regarder les rôles révélés");
		// À seize joueurs la veillée dure plus de trente secondes : la carte n'est pas remise en état avant sa fin.
		assertTrue(Session.restoreDelay(16) >= Session.vigilTicks(16) + 200);
		// Arrêter ensuite ne fait que hâter la remise en état déjà prévue.
		s.stop();
		assertEquals(List.of("remise:" + delay, "remise:0"), h.calls);
	}

	@Test
	void reglageCoupeLaRemiseEnEtat() {
		MapHost h = new MapHost();
		Session s = started(h);
		h.calls.clear();
		s.setting("remise_en_etat", "non");
		s.stop();
		assertEquals(List.of("gardée"), h.calls);
	}

	@Test
	void reglageGardeApresUneReprise() {
		MapHost h = new MapHost();
		Session s = started(h);
		s.setting("remise_en_etat", "non");
		s.saveNow();
		MapHost h2 = new MapHost();
		Session s2 = new Session(h2, Rng.of(new Random(5)));
		s2.restore(h.saved);
		// Une reprise ne reprend pas d'instantané : celui du lancement est toujours sur le disque.
		assertEquals(List.of("installation"), h2.calls);
		h2.calls.clear();
		s2.stop();
		assertEquals(List.of("gardée"), h2.calls);
	}

	@Test
	void pasDePartieTantQueLaCarteNEstPasRemiseEnEtat() {
		MapHost h = new MapHost();
		Session s = new Session(h, Rng.of(new Random(5)));
		s.toggleStoryteller("Conteur");
		s.addBots(6);
		h.busy = true;
		GameException e = assertThrows(GameException.class, () -> s.start("Conteur", Session.Mode.AUTO));
		assertTrue(e.getMessage().contains("remise en état"), e.getMessage());
		assertTrue(h.calls.isEmpty(), "ni instantané ni installation");
	}
}
