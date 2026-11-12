package fr.valcombe.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleType;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** Le Conteur impose des rôles au salon ; les autres sont tirés au lancement, avec une répartition valide. */
class PresetRolesTest {

	private static Session lobby(long seed) {
		Session s = new Session(new SessionTest.FakeHost(), Rng.of(new Random(seed)));
		for (int i = 1; i <= 8; i++) {
			s.join("J" + i);
		}
		s.toggleStoryteller("Conteur");
		return s;
	}

	@Test
	void lesRolesImposesVontAuxBonsJoueurs() {
		for (long seed = 1; seed <= 20; seed++) {
			Session s = lobby(seed);
			s.presetRole("J1", "goule", null);
			s.presetRole("J2", "commere", null);
			s.presetRole("J3", "somnambule", "templier");
			s.start("Conteur", Session.Mode.HUMAIN);
			Game g = s.game();
			assertEquals(Role.GOULE, g.player(g.seatOf("J1")).role());
			assertEquals(Role.COMMERE, g.player(g.seatOf("J2")).role());
			assertEquals(Role.SOMNAMBULE, g.player(g.seatOf("J3")).role());
			assertEquals(Role.TEMPLIER, g.player(g.seatOf("J3")).believed(), "le Somnambule croit être le Templier");
			assertNull(g.distributionWarning(), "la répartition reste valide (graine " + seed + ")");
			assertEquals(1, g.players().stream().filter(p -> p.role().type == RoleType.DEMON).count());
			assertTrue(s.presets.isEmpty(), "les rôles imposés servent une seule partie");
		}
	}

	@Test
	void refusDesRolesImpossibles() {
		Session s = lobby(3);
		s.presetRole("J1", "goule", null);
		assertThrows(GameException.class, () -> s.presetRole("J2", "goule", null), "déjà imposé à un autre");
		assertThrows(GameException.class, () -> s.presetRole("J2", "sonneur", null), "pas dans le script");
		assertThrows(GameException.class, () -> s.presetRole("Inconnu", "commere", null), "pas inscrit");
		s.presetRole("J1", "aucun", null);
		assertTrue(s.presets.isEmpty(), "« aucun » rend le hasard");
	}
}
