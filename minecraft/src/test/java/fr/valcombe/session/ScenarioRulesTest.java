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
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleType;
import fr.valcombe.rules.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Règles d'un scénario : rôles imposés, répartition propre, ordre de nuit, règles spéciales, simulation. */
class ScenarioRulesTest {

	private static ScenarioDef scenario() {
		ScenarioDef d = new ScenarioDef();
		d.id = "essai";
		d.nom = "Essai";
		for (Role r : Role.classic()) {
			d.roles.add(r.name().toLowerCase());
		}
		return d;
	}

	private static Session start(ScenarioDef d, int players, long seed) {
		SessionTest.FakeHost h = new SessionTest.FakeHost();
		Session s = new Session(h, Rng.of(new Random(seed)));
		s.setScenarios(List.of(d));
		s.useScenario(d.id);
		for (int i = 1; i <= players; i++) {
			s.join("J" + i);
		}
		s.start("J1", Session.Mode.AUTO);
		return s;
	}

	@Test
	void rolesImposesToujoursEnJeu() {
		ScenarioDef d = scenario();
		d.regles.imposes = List.of("templier", "martyr", "margrave");
		assertNull(d.problem(), d.problem());
		for (long seed = 0; seed < 25; seed++) {
			Game g = start(d, 9, seed).game();
			List<Role> in = g.players().stream().map(Game.Seat::role).toList();
			assertTrue(in.contains(Role.TEMPLIER) && in.contains(Role.MARTYR) && in.contains(Role.MARGRAVE),
				"graine " + seed + " : " + g.describeRoles());
		}
		d.regles.imposes = List.of("sonneur"); // absent du script classique
		assertNotNull(d.problem(), "un rôle imposé doit faire partie du scénario");
	}

	@Test
	void repartitionPropreAuScenario() {
		ScenarioDef d = scenario();
		d.roles.remove("margrave"); // il changerait le nombre d'Étrangers
		d.regles.repartition.put("8", List.of(4, 2, 1, 1));
		assertNull(d.problem(), d.problem());
		Game g = start(d, 8, 3).game();
		assertEquals(4, g.players().stream().filter(p -> p.role().type == RoleType.CITADIN).count());
		assertEquals(2, g.players().stream().filter(p -> p.role().type == RoleType.ETRANGER).count());
		assertNull(g.distributionWarning(), "la répartition du scénario n'est pas une anomalie");
		d.regles.repartition.put("8", List.of(4, 2, 1, 2));
		assertNotNull(d.problem(), "la somme doit faire huit");
		d.regles.repartition.put("8", List.of(3, 1, 3, 1));
		assertNotNull(d.problem(), "le mal doit rester minoritaire");
		d.regles.repartition.clear();
		assertEquals(16, d.playable().get(d.playable().size() - 1), "un scénario se joue jusqu'à seize");
	}

	@Test
	void ordreDeNuitChoisi() {
		ScenarioDef d = scenario();
		d.regles.imposes = List.of("veilleuse", "astrologue", "alchimiste");
		d.regles.ordrePremiere = List.of("astrologue", "veilleuse", "alchimiste");
		Game g = start(d, 9, 4).game();
		g.startNight();
		List<Role> woken = new ArrayList<>();
		Step step;
		while ((step = g.nextStep()) != null) {
			if (step.role != null) {
				woken.add(step.role);
			}
			List<Integer> targets = new ArrayList<>();
			for (int s = g.size() - 1; targets.size() < step.choose(); s--) {
				if (s != step.seat) {
					targets.add(s);
				}
			}
			g.resolve(step, targets);
		}
		assertTrue(woken.indexOf(Role.ASTROLOGUE) < woken.indexOf(Role.VEILLEUSE), woken.toString());
		assertTrue(woken.indexOf(Role.VEILLEUSE) < woken.indexOf(Role.ALCHIMISTE), woken.toString());
	}

	@Test
	void reglesSpeciales() {
		ScenarioDef d = scenario();
		d.regles.mortsSansVote = true;
		d.regles.accusationsMax = 1;
		d.regles.chuchotementsInterdits = true;
		d.regles.roleReveleALaMort = true;
		d.reglages.conseil = false; // accusation, défense puis vote, une à la fois
		Session s = start(d, 7, 6);
		SessionTest.FakeHost h = (SessionTest.FakeHost) s.host;
		s.startNight();
		SessionTest.pump(s, h, new Random(6));
		assertThrows(GameException.class, () -> s.whisper("J1", "J2", "psst"), "chuchotements interdits");
		Game g = s.game();
		s.stKill("J7");
		assertFalse(g.player(g.seatOf("J7")).ghostVote(), "les morts n'ont pas de vote");
		assertTrue(h.announces.stream().anyMatch(a -> a.startsWith("J7 était ")), "le rôle du mort est révélé");
		if (s.game() != null) {
			s.nominate("J1", "J2");
			s.cancelVote();
			s.nominate("J1", "J2");
			while (!h.tasks.isEmpty() && s.voteOpen()) {
				h.prompts.clear();
				h.tasks.poll().run();
			}
			assertThrows(GameException.class, () -> s.nominate("J3", "J4"), "une seule accusation par jour");
		}
	}

	@Test
	void simulationDuScenarioEtPoidsDesRoles() {
		ScenarioDef d = scenario();
		d.regles.imposes = List.of("templier");
		Simulation.Result r = Simulation.run(d.script(), d, 8, 40, 1);
		assertNull(r.problem(), r.problem());
		assertEquals(0, r.stuck(), "aucune partie bloquée");
		assertEquals(40, r.good() + r.evil());
		assertTrue(r.shortest() >= 1 && r.longest() >= r.shortest());
		int[] templier = r.roles().get(Role.TEMPLIER.displayName);
		assertNotNull(templier, "le rôle imposé apparaît dans le bilan");
		assertEquals(40, templier[0], "il est en jeu à chaque partie");
	}
}
