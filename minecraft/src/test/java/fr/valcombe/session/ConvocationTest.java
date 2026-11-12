package fr.valcombe.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** La salle de convocation : le Conteur reçoit chaque joueur à l'écart pour lui remettre son rôle. */
class ConvocationTest {

	/** Faux serveur qui note qui entre dans la salle et qui en sort. */
	static class Host extends SessionTest.FakeHost {
		final List<String> moves = new ArrayList<>();
		boolean room = true;

		@Override
		public boolean summon(String storyteller, String player) {
			moves.add("+" + player);
			return room;
		}

		@Override
		public void dismiss(String storyteller, String player, boolean storytellerToo) {
			moves.add("-" + player + (storytellerToo ? "!" : ""));
		}
	}

	private static Session game(Host h, Session.Mode mode) {
		Session s = new Session(h, Rng.of(new Random(7)));
		for (int i = 1; i <= 7; i++) {
			s.join("J" + i);
		}
		if (mode == Session.Mode.HUMAIN) {
			s.toggleStoryteller("Conteur");
		}
		s.start("Conteur", mode);
		return s;
	}

	@Test
	void rolesAreHandedOutOneByOne() {
		Host h = new Host();
		Session s = game(h, Session.Mode.HUMAIN);
		String first = s.game().players().get(0).name;
		String second = s.game().players().get(1).name;
		assertFalse(s.roleKnown(first));
		assertThrows(GameException.class, () -> s.role(first));

		s.summonNext();
		assertEquals(first, s.summoned());
		assertTrue(s.roleKnown(first));
		assertFalse(s.roleKnown(second));
		// Dans la salle, le Conteur et le joueur ne s'entendent qu'entre eux.
		assertEquals(List.of(first), s.voiceTargets("Conteur"));
		assertEquals(List.of("Conteur"), s.voiceTargets(first));

		s.summonNext();
		assertEquals(second, s.summoned());
		assertEquals(List.of("+" + first, "-" + first, "+" + second), h.moves);

		s.endSummon();
		assertNull(s.summoned());
		assertEquals("-" + second + "!", h.moves.get(h.moves.size() - 1));
		assertNull(s.voiceTargets("Conteur"));
	}

	@Test
	void nightGivesTheRemainingRoles() {
		Host h = new Host();
		Session s = game(h, Session.Mode.HUMAIN);
		s.summonNext();
		s.startNight();
		assertNull(s.summoned());
		for (var p : s.game().players()) {
			assertTrue(s.roleKnown(p.name));
		}
		assertThrows(GameException.class, () -> s.summon(s.game().players().get(2).name));
	}

	@Test
	void everyoneReceivedThenNothingLeft() {
		Host h = new Host();
		Session s = game(h, Session.Mode.HUMAIN);
		for (int i = 0; i < 7; i++) {
			s.summonNext();
		}
		assertTrue(s.summonNext().contains("Tous les joueurs ont reçu leur rôle"));
		assertNull(s.summoned());
	}

	@Test
	void withoutRoomTheTalkStaysPrivate() {
		Host h = new Host();
		h.room = false;
		Session s = game(h, Session.Mode.HUMAIN);
		String first = s.game().players().get(0).name;
		assertTrue(s.summon(first).contains("pas de salle de convocation"));
		assertEquals(List.of(first), s.voiceTargets("Conteur"));
	}

	@Test
	void automaticStorytellerGivesRolesAtOnce() {
		Host h = new Host();
		Session s = game(h, Session.Mode.AUTO);
		for (var p : s.game().players()) {
			assertTrue(s.roleKnown(p.name));
		}
		assertThrows(GameException.class, () -> s.summon(s.game().players().get(0).name));
	}

	@Test
	void settingOffKeepsTheOldWay() {
		Host h = new Host();
		Session s = new Session(h, Rng.of(new Random(7)));
		for (int i = 1; i <= 7; i++) {
			s.join("J" + i);
		}
		s.toggleStoryteller("Conteur");
		s.setting("remise_convocation", "non");
		s.start("Conteur", Session.Mode.HUMAIN);
		assertTrue(s.roleKnown(s.game().players().get(0).name));
		// On peut toujours convoquer quelqu'un pour lui parler à l'écart.
		s.summon(s.game().players().get(0).name);
		assertEquals(s.game().players().get(0).name, s.summoned());
	}
}
