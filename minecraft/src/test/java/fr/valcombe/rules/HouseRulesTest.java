package fr.valcombe.rules;

import static fr.valcombe.rules.Role.ALCHIMISTE;
import static fr.valcombe.rules.Role.COMMERE;
import static fr.valcombe.rules.Role.GOULE;
import static fr.valcombe.rules.Role.GREFFIER;
import static fr.valcombe.rules.Role.TEMPLIER;
import static fr.valcombe.rules.Role.VEILLEUSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Règles de la table : majorité absolue pour exécuter, victoire du mal dès qu'il est majoritaire. */
class HouseRulesTest {

	@Test
	void majoriteAbsolue() {
		Game g = GameTest.fixed(GOULE, ALCHIMISTE, COMMERE, GREFFIER, VEILLEUSE, TEMPLIER);
		assertEquals(3, g.voteThreshold(), "règle habituelle : la moitié des 6 vivants");
		g.setHouseRules(true, false);
		assertEquals(4, g.voteThreshold(), "majorité absolue : plus de la moitié");
		GameTest.night(g, Map.of());
		g.nominate(2, 3);
		Game.VoteResult r = g.vote(0, List.of(0, 1, 2));
		assertEquals(-1, g.onTheBlock(), "3 voix sur 6 ne suffisent plus");
		assertEquals(4, r.threshold());
	}

	@Test
	void leMalGagneQuandIlEstMajoritaire() {
		Game g = GameTest.fixed(GOULE, ALCHIMISTE, COMMERE, GREFFIER, TEMPLIER);
		g.setHouseRules(false, true);
		GameTest.night(g, Map.of());
		g.storytellerKill(2);
		assertNull(g.winner(), "deux mauvais sur quatre vivants : pas encore majoritaires");
		g.storytellerKill(3);
		assertEquals(Team.EVIL, g.winner(), "deux mauvais sur trois vivants");
	}

	@Test
	void sansLaRegleLaPartieContinue() {
		Game g = GameTest.fixed(GOULE, ALCHIMISTE, COMMERE, GREFFIER, TEMPLIER);
		GameTest.night(g, Map.of());
		g.storytellerKill(2);
		g.storytellerKill(3);
		assertNull(g.winner(), "règle habituelle : le mal gagne à deux vivants seulement");
	}
}
