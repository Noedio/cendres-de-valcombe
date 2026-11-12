package fr.valcombe.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;

import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Voix : chuchoter, parler, crier ; le Conseil s'entend en entier ; les morts s'y taisent ; noms cachés la nuit. */
class VoiceTest {

	static class Host extends SessionTest.FakeHost {
		Boolean tags;

		@Override
		public void nameTags(boolean visible) {
			tags = visible;
		}
	}

	private static Session day(Host h) {
		Session s = new Session(h, Rng.of(new Random(11)));
		for (int i = 1; i <= 7; i++) {
			s.join("J" + i);
		}
		s.toggleStoryteller("Conteur");
		s.start("Conteur", Session.Mode.HUMAIN);
		s.startNight();
		assertEquals(Boolean.FALSE, h.tags, "la nuit, les noms sont cachés");
		SessionTest.pump(s, h, new Random(11));
		assertEquals(Game.Phase.DAY, s.game().phase());
		return s;
	}

	@Test
	void niveauxDeVoixHorsDuConseil() {
		Host h = new Host();
		Session s = day(h);
		assertEquals(Boolean.TRUE, h.tags, "le jour, les noms reviennent");
		assertNull(s.voiceRule("J1"), "parler : vocal de proximité normal");
		s.setVoiceLevel("J1", "crier");
		assertEquals(new Session.VoiceRule(null, s.settings.shoutRange), s.voiceRule("J1"));
		s.setVoiceLevel("J1", "suivant");
		assertEquals("chuchoter", s.voiceLevel("J1"));
		assertEquals(new Session.VoiceRule(null, s.settings.whisperRange), s.voiceRule("J1"));
		assertThrows(GameException.class, () -> s.setVoiceLevel("J1", "hurler"));
		s.setting("noms", "non");
		assertEquals(Boolean.FALSE, h.tags, "le Conteur peut cacher les noms");
	}

	@Test
	void auConseilToutLeVillageSEntendEtLesMortsSeTaisent() {
		Host h = new Host();
		Session s = day(h);
		Game g = s.game();
		s.setNominations(true);
		assertTrue(s.inCouncil());
		Session.VoiceRule all = s.voiceRule("J1");
		assertTrue(Set.copyOf(all.targets()).containsAll(List.of("J2", "J3", "J4", "J5", "J6", "J7", "Conteur")),
			"pendant le Conseil, tout le monde entend tout le monde");
		s.setVoiceLevel("J1", "chuchoter");
		int me = g.seatOf("J1");
		Set<String> neighbours = Set.of(g.player(Math.floorMod(me - 1, g.size())).name, g.player((me + 1) % g.size()).name);
		assertEquals(neighbours, Set.copyOf(s.voiceRule("J1").targets()), "chuchoter : seulement les deux voisins de trône");

		s.stKill("J3");
		assertEquals(List.of(), s.voiceRule("J3").targets(), "un mort se tait pendant le Conseil");
		assertThrows(GameException.class, () -> s.giveFloor("J3", "J3"), "un mort ne se donne pas la parole");
		s.giveFloor("J2", "J3");
		assertFalse(s.voiceRule("J3").targets().isEmpty(), "on lui a donné la parole");
		s.giveFloor("J2", "J3");
		assertEquals(List.of(), s.voiceRule("J3").targets(), "on la lui reprend");
		s.giveFloor("J2", "J3");
		s.setNominations(false);
		s.endDay();
		assertFalse(s.hasFloor("J3"), "la parole donnée s'arrête le soir");
	}
}
