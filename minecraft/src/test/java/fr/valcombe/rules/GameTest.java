package fr.valcombe.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static fr.valcombe.rules.Role.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class GameTest {

	/** Conteur figé : jamais d'enregistrement trompeur ni de détournement. */
	static class Honest implements Decisions {
		Integer bounce;

		@Override
		public boolean misregister(Game g, int seat, String what, String key) {
			return false;
		}

		@Override
		public Integer mayorBounce(Game g, int mayor) {
			return bounce;
		}

		@Override
		public Integer starpassSuccessor(Game g, List<Integer> candidates) {
			return candidates.isEmpty() ? null : candidates.get(0);
		}
	}

	static List<String> names(int n) {
		List<String> out = new ArrayList<>();
		for (int i = 1; i <= n; i++) {
			out.add("J" + i);
		}
		return out;
	}

	/** Partie à rôles imposés ; un tableau {rôle, rôle cru} pour le Somnambule. */
	static Game withHerring(int herring, Object... roles) {
		List<Setup.Entry> list = new ArrayList<>();
		for (Object o : roles) {
			if (o instanceof Role[] pair) {
				list.add(new Setup.Entry(pair[0], pair[1]));
			} else {
				list.add(new Setup.Entry((Role) o));
			}
		}
		Game g = new Game(names(list.size()), Rng.of(new Random(7)));
		g.decide = new Honest();
		g.assign(list, null, herring);
		return g;
	}

	static Game fixed(Object... roles) {
		return withHerring(-1, roles);
	}

	static Role[] drunk(Role believed) {
		return new Role[] {SOMNAMBULE, believed};
	}

	record NightResult(Map<Role, Info> infos, List<Integer> deaths, Info newDemon) {
	}

	/**
	 * Joue la nuit. choices : cibles par rôle (sièges 0..n-1). Par défaut l'Alchimiste
	 * s'empoisonne lui-même (sans effet) et les autres désignent leur voisin de droite.
	 */
	static NightResult night(Game g, Map<Role, List<Integer>> choices) {
		g.startNight();
		Map<Role, Info> infos = new EnumMap<>(Role.class);
		Info newDemon = null;
		Step step;
		while ((step = g.nextStep()) != null) {
			List<Integer> targets = List.of();
			if (step.isChoose()) {
				targets = choices.get(step.role);
				if (targets == null && step.role == ALCHIMISTE) {
					targets = List.of(step.seat);
				}
				if (targets == null) {
					List<Integer> t = new ArrayList<>();
					for (int i = 1; i <= step.choose(); i++) {
						t.add((step.seat + i) % g.size());
					}
					targets = t;
				}
			}
			Info info = g.resolve(step, targets);
			if (step.role != null) {
				infos.put(step.role, info);
			}
			if (step.kind == Step.Kind.NEW_DEMON) {
				newDemon = info;
			}
		}
		// La partie peut se terminer en pleine nuit (ex. Goule sacrifiée sans successeur).
		List<Integer> deaths = g.winner() != null ? List.of() : g.endNight();
		return new NightResult(infos, deaths, newDemon);
	}

	static NightResult night(Game g) {
		return night(g, Map.of());
	}

	static Map<Role, List<Integer>> choose(Object... kv) {
		Map<Role, List<Integer>> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			Object v = kv[i + 1];
			m.put((Role) kv[i], v instanceof Integer x ? List.of(x) : (List<Integer>) v);
		}
		return m;
	}

	// ------------------------------------------------------------------

	@Test
	void vingtDeuxRolesEtOrdreDeNuit() {
		assertEquals(22, Role.values().length);
		assertEquals(13, Role.ofType(RoleType.CITADIN).size());
		assertEquals(4, Role.ofType(RoleType.ETRANGER).size());
		assertEquals(4, Role.ofType(RoleType.SBIRE).size());
		assertEquals(1, Role.ofType(RoleType.DEMON).size());
		assertEquals(List.of(ALCHIMISTE, MOUCHARD, COMMERE, GREFFIER, INQUISITEUR, AUBERGISTE, VEILLEUSE,
			ASTROLOGUE, ECUYER), Role.firstNightOrder());
		assertEquals(List.of(ALCHIMISTE, CHAPELAIN, HERITIERE, GOULE, FAUCONNIER, VEILLEUSE, ASTROLOGUE,
			EMBAUMEUR, ECUYER, MOUCHARD), Role.otherNightsOrder());
	}

	@Test
	void tirageRespecteLaRepartition() {
		for (int n = 5; n <= 16; n++) {
			for (int seed = 0; seed < 40; seed++) {
				List<Setup.Entry> bag = Setup.drawBag(n, Rng.of(new Random(seed)));
				assertEquals(n, bag.size());
				Map<RoleType, Integer> c = new EnumMap<>(RoleType.class);
				boolean margrave = false;
				for (Setup.Entry e : bag) {
					c.merge(e.role().type, 1, Integer::sum);
					margrave |= e.role() == MARGRAVE;
					if (e.role() == SOMNAMBULE) {
						assertEquals(RoleType.CITADIN, e.believed().type);
						for (Setup.Entry o : bag) {
							assertNotEquals(e.believed(), o.role(), "rôle cru déjà en jeu");
						}
					}
				}
				Distribution.Counts exp = Distribution.counts(n);
				int bonus = margrave ? 2 : 0;
				assertEquals(1, c.getOrDefault(RoleType.DEMON, 0));
				assertEquals(exp.sbire(), c.getOrDefault(RoleType.SBIRE, 0));
				assertEquals(exp.etranger() + bonus, c.getOrDefault(RoleType.ETRANGER, 0));
				assertEquals(exp.citadin() - bonus, c.getOrDefault(RoleType.CITADIN, 0));
			}
		}
		assertNull(Setup.drawBag(4, Rng.of(new Random())));
	}

	@Test
	void bluffsTroisRolesDuBienAbsents() {
		for (int seed = 0; seed < 50; seed++) {
			List<Setup.Entry> bag = Setup.drawBag(10, Rng.of(new Random(seed)));
			List<Role> b = Setup.pickBluffs(bag, Rng.of(new Random(seed + 1)));
			assertEquals(3, b.size());
			assertEquals(RoleType.CITADIN, b.get(0).type);
			for (Role r : b) {
				assertFalse(r.evil());
				for (Setup.Entry e : bag) {
					assertNotEquals(r, e.role());
					assertNotEquals(r, e.believed());
				}
			}
		}
	}

	@Test
	void templierSurvitALaGoule() {
		Game g = fixed(GOULE, TEMPLIER, COMMERE, VEILLEUSE, ALCHIMISTE);
		night(g);
		g.endDay();
		NightResult r = night(g, choose(GOULE, 1));
		assertTrue(r.deaths().isEmpty());
	}

	@Test
	void templierEmpoisonneMeurt() {
		Game g = fixed(GOULE, TEMPLIER, COMMERE, VEILLEUSE, ALCHIMISTE);
		night(g);
		g.endDay();
		NightResult r = night(g, choose(ALCHIMISTE, 1, GOULE, 1));
		assertEquals(List.of(1), r.deaths());
	}

	@Test
	void chapelainProtege() {
		Game g = fixed(GOULE, CHAPELAIN, COMMERE, VEILLEUSE, ALCHIMISTE);
		night(g);
		g.endDay();
		NightResult r = night(g, choose(CHAPELAIN, 2, GOULE, 2));
		assertTrue(r.deaths().isEmpty());
	}

	@Test
	void chatelainDetourneLAttaque() {
		Game g = fixed(GOULE, CHATELAIN, COMMERE, VEILLEUSE, ALCHIMISTE);
		((Honest) g.decide).bounce = 3;
		night(g);
		g.endDay();
		NightResult r = night(g, choose(GOULE, 1));
		assertEquals(List.of(3), r.deaths());
		assertTrue(g.player(1).alive());
	}

	@Test
	void fauconnierSeReveilleASaMort() {
		Game g = fixed(GOULE, FAUCONNIER, COMMERE, VEILLEUSE, ALCHIMISTE);
		night(g);
		g.endDay();
		NightResult r = night(g, choose(GOULE, 1, FAUCONNIER, 4));
		assertNotNull(r.infos().get(FAUCONNIER));
		assertEquals(ALCHIMISTE, r.infos().get(FAUCONNIER).role);
	}

	@Test
	void veilleuseCompteSesVoisinsMauvais() {
		Game g = fixed(VEILLEUSE, GOULE, COMMERE, TEMPLIER, ALCHIMISTE);
		assertEquals(2, night(g).infos().get(VEILLEUSE).value);
	}

	@Test
	void aubergisteTroisMauvaisDAffileeDeuxPaires() {
		Game g = fixed(AUBERGISTE, GOULE, ALCHIMISTE, MOUCHARD, COMMERE, TEMPLIER, CHATELAIN, PELERINE,
			VEILLEUSE, CHAPELAIN, EMBAUMEUR);
		assertEquals(2, night(g).infos().get(AUBERGISTE).value);
	}

	@Test
	void astrologueVoitLeDemonEtLeFauxPresage() {
		assertTrue(night(withHerring(2, ASTROLOGUE, GOULE, COMMERE, TEMPLIER, ALCHIMISTE), choose(ASTROLOGUE, List.of(1, 3)))
			.infos().get(ASTROLOGUE).yes);
		assertTrue(night(withHerring(2, ASTROLOGUE, GOULE, COMMERE, TEMPLIER, ALCHIMISTE), choose(ASTROLOGUE, List.of(2, 3)))
			.infos().get(ASTROLOGUE).yes, "faux présage");
		assertFalse(night(withHerring(2, ASTROLOGUE, GOULE, COMMERE, TEMPLIER, ALCHIMISTE), choose(ASTROLOGUE, List.of(3, 4)))
			.infos().get(ASTROLOGUE).yes);
	}

	@Test
	void astrologueEmpoisonneReponseInversee() {
		Game g = withHerring(2, ASTROLOGUE, GOULE, COMMERE, TEMPLIER, ALCHIMISTE);
		Info i = night(g, choose(ALCHIMISTE, 0, ASTROLOGUE, List.of(3, 4))).infos().get(ASTROLOGUE);
		assertTrue(i.yes);
		assertFalse(i.truthYes);
		assertEquals("Non", i.truthText);
	}

	@Test
	void somnambuleSeReveilleCommeSonRoleCruAvecUneFausseInfo() {
		Game g = fixed(drunk(VEILLEUSE), GOULE, COMMERE, TEMPLIER, ALCHIMISTE);
		Info i = night(g).infos().get(VEILLEUSE);
		assertNotNull(i);
		assertNotEquals(i.truthValue, i.value);
	}

	@Test
	void greffierMontreLeSomnambuleSousSonVraiRole() {
		Game g = fixed(GREFFIER, GOULE, drunk(TEMPLIER), COMMERE, ALCHIMISTE);
		assertEquals(SOMNAMBULE, night(g).infos().get(GREFFIER).role);
	}

	@Test
	void greffierSansEtranger() {
		Game g = fixed(GREFFIER, GOULE, CHATELAIN, COMMERE, ALCHIMISTE);
		assertEquals(Info.Kind.NO_OUTSIDER, night(g).infos().get(GREFFIER).kind);
	}

	@Test
	void pelerineExecuteLeCitadinQuiLaNomine() {
		Game g = fixed(PELERINE, GOULE, COMMERE, TEMPLIER, ALCHIMISTE);
		night(g);
		assertTrue(g.nominate(2, 0).pelerine());
		assertFalse(g.player(2).alive());
		assertThrows(GameException.class, () -> g.nominate(3, 1));
	}

	@Test
	void pelerineEmpoisonneeNeFaitRien() {
		Game g = fixed(PELERINE, GOULE, COMMERE, TEMPLIER, ALCHIMISTE);
		night(g, choose(ALCHIMISTE, 0));
		assertFalse(g.nominate(2, 0).pelerine());
		assertTrue(g.pelerineUsed());
		assertTrue(g.player(2).alive());
	}

	@Test
	void pelerineNomineeParUnSbire() {
		Game g = fixed(PELERINE, GOULE, COMMERE, TEMPLIER, ALCHIMISTE);
		night(g);
		assertFalse(g.nominate(4, 0).pelerine());
		assertTrue(g.pelerineUsed());
		assertTrue(g.player(4).alive());
	}

	@Test
	void seuilDeVoteEtEgalite() {
		Game g = fixed(GOULE, COMMERE, TEMPLIER, CHATELAIN, ALCHIMISTE, VEILLEUSE);
		night(g);
		Game.VoteResult r1 = g.vote(g.nominate(1, 0).index(), List.of(1, 2, 3));
		assertEquals(3, r1.threshold());
		assertTrue(r1.onBlock());
		Game.VoteResult r2 = g.vote(g.nominate(2, 4).index(), List.of(1, 2, 5));
		assertFalse(r2.onBlock());
		assertEquals(-1, g.endDay().executed());
	}

	@Test
	void voteDeFantomeUnique() {
		Game g = fixed(GOULE, COMMERE, TEMPLIER, CHATELAIN, ALCHIMISTE, VEILLEUSE);
		night(g);
		g.vote(g.nominate(1, 5).index(), List.of(0, 1, 2, 3));
		g.endDay();
		assertFalse(g.player(5).alive());
		night(g, choose(GOULE, 2));
		g.vote(g.nominate(1, 4).index(), List.of(5));
		assertFalse(g.player(5).ghostVote());
		Game.VoteResult r = g.vote(g.nominate(2, 0).index(), List.of(5));
		assertEquals(0, r.count());
		assertEquals(1, r.warnings().size());
	}

	@Test
	void leConteurRetoucheLaDistributionAvantLaPremiereNuit() {
		Game g = withHerring(-1, GOULE, COMMERE, TEMPLIER, CHATELAIN, ALCHIMISTE, VEILLEUSE);
		assertNotNull(g.distributionWarning(), "6 joueurs sans Étranger");
		g.reassign(1, SOMNAMBULE, ASTROLOGUE);
		assertEquals(SOMNAMBULE, g.player(1).role());
		assertEquals(ASTROLOGUE, g.player(1).believed());
		assertNull(g.distributionWarning(), "3 Citadins, 1 Étranger, 1 Sbire, 1 Démon");
		assertThrows(GameException.class, () -> g.reassign(2, SOMNAMBULE, GOULE));
		assertThrows(GameException.class, () -> g.setRedHerring(0));
		g.setRedHerring(3);
		assertEquals(3, g.redHerring());
		assertThrows(GameException.class, () -> g.setBluffs(List.of(TEMPLIER, PELERINE, MARTYR)), "Templier en jeu");
		g.setBluffs(List.of(PELERINE, MARTYR, ECUYER));
		assertEquals(List.of(PELERINE, MARTYR, ECUYER), g.bluffs());
		night(g);
		assertThrows(GameException.class, () -> g.reassign(2, GREFFIER, null), "trop tard");
	}

	@Test
	void scriptRestreint() {
		java.util.Set<Role> small = java.util.EnumSet.of(GOULE, ALCHIMISTE, MARGRAVE,
			COMMERE, GREFFIER, VEILLEUSE, TEMPLIER, CHATELAIN, CHAPELAIN);
		assertNull(Setup.check(small, 7), "5 Citadins, 1 Sbire, 1 Démon");
		assertNotNull(Setup.check(small, 9), "il manque des Étrangers");
		for (int seed = 0; seed < 60; seed++) {
			List<Setup.Entry> bag = Setup.drawBag(7, Rng.of(new Random(seed)), small);
			assertNotNull(bag);
			for (Setup.Entry e : bag) {
				assertTrue(small.contains(e.role()), "rôle hors script : " + e.role());
				assertNotEquals(MARGRAVE, e.role(), "pas d'Étranger pour le Margrave");
			}
			for (Role b : Setup.pickBluffs(bag, Rng.of(new Random(seed)), small)) {
				assertTrue(small.contains(b), "bluff hors script : " + b);
			}
		}
		Game g = new Game(names(9), Rng.of(new Random(1)));
		assertThrows(GameException.class, () -> g.setupRandom(small));
	}

	@Test
	void consignesDuConteurHumain() {
		Game g = withHerring(-1, GOULE, CHATELAIN, COMMERE, VEILLEUSE, ALCHIMISTE, HERITIERE);
		fr.valcombe.session.HumanDecisions h = new fr.valcombe.session.HumanDecisions();
		h.mayorTarget = "J3";
		h.misregister = fr.valcombe.session.HumanDecisions.Policy.JAMAIS;
		g.decide = h;
		night(g);
		g.endDay();
		NightResult r = night(g, choose(GOULE, 1));
		assertEquals(List.of(2), r.deaths(), "la Commère meurt à la place du Châtelain");
		h.mayorTarget = "";
		g.endDay();
		r = night(g, choose(GOULE, 1));
		assertEquals(List.of(1), r.deaths(), "le Châtelain meurt lui-même");
	}

	@Test
	void onNeNominePasUnMort() {
		Game g = withHerring(-1, GOULE, COMMERE, TEMPLIER, CHATELAIN, ALCHIMISTE, VEILLEUSE);
		night(g);
		g.vote(g.nominate(1, 5).index(), List.of(0, 1, 2, 3));
		g.endDay();
		night(g, choose(GOULE, 2));
		assertThrows(GameException.class, () -> g.nominate(1, 5));
	}

	@Test
	void executionDuDemonVictoireDuBien() {
		Game g = fixed(GOULE, COMMERE, TEMPLIER, CHATELAIN, ALCHIMISTE, VEILLEUSE);
		night(g);
		g.vote(g.nominate(1, 0).index(), List.of(1, 2, 3, 5));
		g.endDay();
		assertEquals(Team.GOOD, g.winner());
	}

	@Test
	void heritiereDevientLaGoule() {
		Game g = fixed(GOULE, COMMERE, TEMPLIER, CHATELAIN, HERITIERE, VEILLEUSE);
		night(g);
		g.vote(g.nominate(1, 0).index(), List.of(1, 2, 3, 5));
		g.endDay();
		assertNull(g.winner());
		assertEquals(GOULE, g.player(4).role());
		NightResult r = night(g, choose(GOULE, 1));
		assertNotNull(r.newDemon(), "elle apprend qu'elle est la Goule");
		assertFalse(g.player(1).alive());
	}

	@Test
	void heritiereSansEffetSousCinqVivants() {
		Game g = fixed(GOULE, COMMERE, TEMPLIER, CHATELAIN, HERITIERE);
		night(g);
		g.vote(g.nominate(1, 3).index(), List.of(0, 1, 2));
		g.endDay();
		night(g, choose(GOULE, 1));
		// 3 vivants : Goule, Templier, Héritière.
		g.vote(g.nominate(2, 0).index(), List.of(2, 4));
		g.endDay();
		assertEquals(Team.GOOD, g.winner());
	}

	@Test
	void gouleQuiSeSacrifieTransmetSonRole() {
		Game g = fixed(GOULE, COMMERE, TEMPLIER, CHATELAIN, ALCHIMISTE, VEILLEUSE);
		night(g);
		g.endDay();
		NightResult r = night(g, choose(ALCHIMISTE, 1, GOULE, 0));
		assertEquals(GOULE, g.player(4).role());
		assertEquals(4, r.infos().get(GOULE).newDemon);
		assertNull(g.winner());
	}

	@Test
	void gouleQuiSeSacrifieSansSbireVivant() {
		Game g = fixed(GOULE, CHATELAIN, TEMPLIER, COMMERE, ALCHIMISTE);
		night(g);
		g.vote(g.nominate(1, 4).index(), List.of(1, 2, 3));
		g.endDay();
		night(g, choose(GOULE, 0));
		assertEquals(Team.GOOD, g.winner());
	}

	@Test
	void martyrExecuteVictoireDuMal() {
		Game g = fixed(GOULE, MARTYR, TEMPLIER, CHATELAIN, ALCHIMISTE, VEILLEUSE);
		night(g);
		g.vote(g.nominate(2, 1).index(), List.of(0, 2, 3, 4));
		g.endDay();
		assertEquals(Team.EVIL, g.winner());
	}

	@Test
	void arbaletrierTueLeDemonEtLeBluffNeFaitRien() {
		Game g = fixed(GOULE, ARBALETRIER, TEMPLIER, CHATELAIN, ALCHIMISTE, VEILLEUSE);
		night(g);
		assertFalse(g.slayerShot(2, 0), "bluff du Templier");
		assertTrue(g.slayerShot(1, 0));
		assertEquals(Team.GOOD, g.winner());
	}

	@Test
	void chatelainGagneATroisSansExecution() {
		Game g = fixed(GOULE, CHATELAIN, TEMPLIER, COMMERE, ALCHIMISTE);
		night(g);
		g.vote(g.nominate(1, 3).index(), List.of(0, 1, 2));
		g.endDay();
		night(g, choose(ALCHIMISTE, 0)); // Goule empoisonnée : personne ne meurt
		g.vote(g.nominate(1, 4).index(), List.of(0, 1));
		g.endDay();
		assertEquals(3, g.aliveCount());
		assertNull(g.winner());
		night(g, choose(GOULE, 2)); // le Templier survit
		g.endDay();
		assertEquals(Team.GOOD, g.winner());
	}

	@Test
	void ecuyerSansSonMaitreEstSignale() {
		Game g = fixed(GOULE, ECUYER, TEMPLIER, CHATELAIN, ALCHIMISTE, VEILLEUSE);
		night(g, choose(ECUYER, 3));
		Game.VoteResult r = g.vote(g.nominate(2, 0).index(), List.of(1, 2));
		assertEquals(2, r.count());
		assertEquals(List.of("L'Écuyer a voté sans son maître."), r.warnings());
	}

	@Test
	void partiesAleatoiresSeTerminentToujours() {
		for (int seed = 0; seed < 300; seed++) {
			int n = 5 + seed % 12;
			Random random = new Random(seed);
			Rng rng = Rng.of(random);
			Game g = new Game(names(n), rng);
			g.setupRandom();
			int guard = 0;
			while (g.winner() == null) {
				assertTrue(++guard < 200, "partie sans fin, graine " + seed);
				g.startNight();
				Step step;
				while ((step = g.nextStep()) != null) {
					List<Integer> targets = new ArrayList<>();
					while (targets.size() < step.choose()) {
						int t = rng.next(n);
						if (!(step.notSelf() && t == step.seat)) {
							targets.add(t);
						}
					}
					g.resolve(step, targets);
				}
				if (g.winner() == null) {
					g.endNight();
				}
				if (g.winner() != null) {
					break;
				}
				List<Integer> alive = g.aliveSeats();
				int a = rng.pick(alive);
				int b = rng.pick(alive);
				Game.Nomination nom = g.nominate(a, b);
				if (!nom.pelerine()) {
					g.vote(nom.index(), alive);
				}
				if (g.winner() != null) {
					break;
				}
				g.endDay();
			}
			assertNotNull(g.winner());
		}
	}
}
