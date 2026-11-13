package fr.valcombe.rules;

import static fr.valcombe.rules.Role.ALCHIMISTE;
import static fr.valcombe.rules.Role.COMMERE;
import static fr.valcombe.rules.Role.GOULE;
import static fr.valcombe.rules.Role.GREFFIER;
import static fr.valcombe.rules.Role.HERITIERE;
import static fr.valcombe.rules.Role.TEMPLIER;
import static fr.valcombe.rules.Role.VEILLEUSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Règles de la table : majorité absolue pour exécuter, victoire du mal dès qu'il est majoritaire, Héritière sans seuil,
 * le mal qui se connaît dès 5 joueurs.
 */
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

	@Test
	void heritiereSansSeuil() {
		// Officiellement, à 4 vivants, la mort de la Goule fait gagner le bien.
		Game g = GameTest.fixed(GOULE, HERITIERE, COMMERE, GREFFIER, TEMPLIER);
		GameTest.night(g, Map.of());
		g.storytellerKill(2);
		g.storytellerKill(0);
		assertEquals(Team.GOOD, g.winner(), "règle officielle : 4 vivants, pas de relève");
		// Avec la règle de la table, l'Héritière prend la relève.
		Game t = GameTest.fixed(GOULE, HERITIERE, COMMERE, GREFFIER, TEMPLIER);
		t.setTableRules(true, false);
		GameTest.night(t, Map.of());
		t.storytellerKill(2);
		t.storytellerKill(0);
		assertNull(t.winner(), "l'Héritière devient le Démon");
		assertEquals(GOULE, t.player(1).role());
	}

	@Test
	void leMalSeConnaitDes5Joueurs() {
		Game g = GameTest.fixed(GOULE, ALCHIMISTE, COMMERE, GREFFIER, TEMPLIER);
		List<Step.Kind> official = firstNightKinds(g);
		assertFalse(official.contains(Step.Kind.MINION_INFO) || official.contains(Step.Kind.DEMON_INFO),
			"règle officielle : rien à 5 joueurs");
		Game t = GameTest.fixed(GOULE, ALCHIMISTE, COMMERE, GREFFIER, TEMPLIER);
		t.setTableRules(false, true);
		List<Step.Kind> table = firstNightKinds(t);
		assertTrue(table.contains(Step.Kind.MINION_INFO) && table.contains(Step.Kind.DEMON_INFO),
			"règle de la table : les Sbires et le Démon se connaissent à 5 joueurs " + table);
	}

	/** Les étapes de la première nuit, jouées avec des cibles quelconques. */
	private static List<Step.Kind> firstNightKinds(Game g) {
		List<Step.Kind> kinds = new ArrayList<>();
		g.startNight();
		Step step;
		while ((step = g.nextStep()) != null) {
			kinds.add(step.kind);
			List<Integer> targets = new ArrayList<>();
			if (step.isChoose()) {
				for (int i = 1; i <= step.choose(); i++) {
					targets.add((Math.max(0, step.seat) + i) % g.size());
				}
			}
			g.resolve(step, targets);
		}
		return kinds;
	}
}
