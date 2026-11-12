package fr.valcombe.rules;

import static fr.valcombe.rules.GameTest.choose;
import static fr.valcombe.rules.GameTest.fixed;
import static fr.valcombe.rules.GameTest.night;
import static fr.valcombe.rules.Role.ALCHIMISTE;
import static fr.valcombe.rules.Role.BAVARD;
import static fr.valcombe.rules.Role.COMMERE;
import static fr.valcombe.rules.Role.FLEURISTE;
import static fr.valcombe.rules.Role.GOULE;
import static fr.valcombe.rules.Role.GREFFIER;
import static fr.valcombe.rules.Role.JONGLEUR;
import static fr.valcombe.rules.Role.SORCIERE;
import static fr.valcombe.rules.Role.TEMPLIER;
import static fr.valcombe.rules.Role.VEILLEUSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.session.Simulation;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Les rôles de la table : Sorcière, Fleuriste, Jongleur, Bavard. */
class TableRolesTest {

	@Test
	void laSorciereMauditUnAccuse() {
		Game g = fixed(GOULE, SORCIERE, COMMERE, GREFFIER, VEILLEUSE, TEMPLIER);
		night(g, choose(SORCIERE, 2));
		g.nominate(3, 2);
		g.vote(0, List.of());
		assertEquals(2, g.applyCurse(), "la Commère maudite et accusée meurt à la fin du Conseil");
		assertFalse(g.player(2).alive());
		assertEquals(-1, g.applyCurse(), "une seule fois");
	}

	@Test
	void laMaledictionSansAccusationNeFaitRien() {
		Game g = fixed(GOULE, SORCIERE, COMMERE, GREFFIER, VEILLEUSE, TEMPLIER);
		night(g, choose(SORCIERE, 2));
		g.nominate(3, 4);
		g.vote(0, List.of());
		assertEquals(-1, g.applyCurse());
		assertTrue(g.player(2).alive());
	}

	@Test
	void laFleuristeSaitSiUnDemonAVote() {
		Game g = fixed(GOULE, ALCHIMISTE, FLEURISTE, COMMERE, TEMPLIER, VEILLEUSE);
		night(g);
		g.nominate(2, 3);
		g.vote(0, List.of(0, 5)); // la Goule lève la main
		g.endDay();
		var r = night(g, choose(GOULE, 4));
		assertTrue(r.infos().get(FLEURISTE).yes, "un Démon a voté");
		g.nominate(2, 3);
		g.vote(0, List.of(5)); // seule la Veilleuse vote
		g.endDay();
		r = night(g, choose(GOULE, 4));
		assertFalse(r.infos().get(FLEURISTE).yes, "aucun Démon n'a voté");
	}

	@Test
	void leJongleurCompteSesHypothesesJustesLaDeuxiemeNuit() {
		Game g = fixed(GOULE, ALCHIMISTE, JONGLEUR, COMMERE, TEMPLIER, VEILLEUSE);
		var first = night(g);
		assertNull(first.infos().get(JONGLEUR), "pas de réveil la première nuit");
		g.endDay();
		g.setGuesses(Map.of(0, GOULE, 1, ALCHIMISTE, 3, TEMPLIER, 4, TEMPLIER, 5, VEILLEUSE));
		var second = night(g, choose(GOULE, 4, JONGLEUR, List.of(0, 1, 3, 4, 5)));
		assertEquals(4, second.infos().get(JONGLEUR).value, "quatre rôles justes sur cinq");
		g.endDay();
		var third = night(g, choose(GOULE, 4));
		assertNull(third.infos().get(JONGLEUR), "pas de réveil la troisième nuit");
	}

	@Test
	void leBavardMeurtSIlAVuJuste() {
		Game g = fixed(GOULE, ALCHIMISTE, BAVARD, COMMERE, TEMPLIER, VEILLEUSE);
		night(g);
		g.bavardGuess(2, 0, GOULE);
		g.endDay();
		var r = night(g, choose(GOULE, 4));
		assertTrue(r.deaths().contains(2), "il a vu juste : il meurt la nuit suivante");
		assertThrows(GameException.class, () -> g.bavardGuess(2, 0, GOULE), "mort, il ne devine plus");
	}

	@Test
	void leBavardQuiSeTrompeSurvit() {
		Game g = fixed(GOULE, ALCHIMISTE, BAVARD, COMMERE, TEMPLIER, VEILLEUSE);
		night(g);
		g.bavardGuess(2, 3, GOULE);
		g.endDay();
		var r = night(g, choose(GOULE, 4));
		assertFalse(r.deaths().contains(2));
		assertTrue(g.player(2).alive());
	}

	@Test
	void desPartiesEntieresAvecLeScriptDeLaTable() {
		Simulation.Result r = Simulation.run(Role.table(), 9, 40, 5);
		assertNull(r.problem(), r.problem());
		assertEquals(0, r.stuck(), "aucune partie bloquée");
		assertEquals(40, r.good() + r.evil());
	}
}
