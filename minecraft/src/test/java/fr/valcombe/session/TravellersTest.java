package fr.valcombe.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleType;
import fr.valcombe.rules.Setup;
import fr.valcombe.rules.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Voyageurs (arrivée en cours de partie, exil, départ) et figures neutres. */
class TravellersTest {
	private static Game game(long seed) {
		List<String> names = new ArrayList<>();
		for (int i = 1; i <= 8; i++) {
			names.add("J" + i);
		}
		Game g = new Game(names, Rng.of(new Random(seed)));
		g.decide = new fr.valcombe.rules.AutoDecisions();
		g.setupRandom(Role.classic());
		return g;
	}

	private static void toDay(Game g) {
		g.startNight();
		for (var step = g.nextStep(); step != null; step = g.nextStep()) {
			List<Integer> targets = new ArrayList<>();
			for (int t = 0; targets.size() < step.choose(); t++) {
				if (!(step.notSelf() && t == step.seat)) {
					targets.add(t);
				}
			}
			g.resolve(step, targets);
		}
		g.endNight();
	}

	@Test
	void voyageurExileParTous() {
		Game g = game(3);
		toDay(g);
		int alive = g.aliveResidents();
		int v = g.addTraveller("Voya", Team.EVIL);
		assertTrue(g.player(v).traveller());
		assertEquals(Team.EVIL, g.player(v).team());
		assertEquals(alive, g.aliveResidents(), "un voyageur ne compte pas parmi les habitants");
		assertEquals(Role.VOYAGEUR, g.player(v).role());
		assertFalse(Role.all().contains(Role.VOYAGEUR), "le Voyageur n'est jamais tiré ni proposé");
		// Un voyageur ne passe pour aucun type de rôle
		assertFalse(g.reg(v, "townsfolk", new Game.Ctx("t")));
		assertTrue(g.reg(v, "evil", new Game.Ctx("t")));
		// Exil : tout le monde vote, et le seuil est atteint
		Game.Nomination n = g.nominate(0, v);
		List<Integer> all = new ArrayList<>();
		for (int i = 0; i < g.size(); i++) {
			all.add(i);
		}
		Game.VoteResult r = g.vote(n.index(), all);
		assertTrue(r.exiled());
		assertFalse(g.player(v).alive());
		assertEquals(-1, g.onTheBlock(), "un exil ne met personne sur le billot");
		assertFalse(g.hasNominatedToday(0), "demander un exil n'est pas une nomination");
	}

	@Test
	void voyageurQuiRepart() {
		Game g = game(4);
		toDay(g);
		int v = g.addTraveller("Voya", Team.GOOD);
		g.travellerLeaves(v);
		assertFalse(g.player(v).alive());
		assertThrows(GameException.class, () -> g.travellerLeaves(v));
		assertThrows(GameException.class, () -> g.addTraveller("J1", Team.GOOD), "déjà en jeu");
	}

	@Test
	void voyageurDansLaSession() {
		SessionTest.FakeHost h = new SessionTest.FakeHost();
		Session s = new Session(h, Rng.of(new Random(9)));
		s.addBots(7);
		s.start("Conteur", Session.Mode.AUTO);
		s.startNight();
		while (!h.tasks.isEmpty() && s.game() != null && s.game().phase() == Game.Phase.NIGHT) {
			h.tasks.poll().run();
		}
		s.addTraveller("Voya", "bien");
		Game g = s.game();
		assertTrue(g.player(g.seatOf("Voya")).traveller());
		assertTrue(h.announces.stream().anyMatch(a -> a.contains("Voya arrive")));
		s.travellerLeaves("Voya");
		assertFalse(g.player(g.seatOf("Voya")).alive());
	}

	@Test
	void sentinelleChangeLesEtrangers() {
		int base = 0;
		int shifted = 0;
		for (long seed = 0; seed < 20; seed++) {
			var a = Setup.drawBag(9, Rng.of(new Random(seed)), Role.classic(), 0);
			var b = Setup.drawBag(9, Rng.of(new Random(seed)), Role.classic(), 1);
			base += a.stream().filter(e -> e.role().type == RoleType.ETRANGER).count();
			shifted += b.stream().filter(e -> e.role().type == RoleType.ETRANGER).count();
		}
		assertTrue(shifted > base, "un Étranger de plus avec l'écart +1");
	}

	@Test
	void doyenEtSablier() {
		SessionTest.FakeHost h = new SessionTest.FakeHost() {
			@Override
			public int gamesPlayed(String player) {
				return player.equals("Ancien") ? 5 : 0;
			}
		};
		Session s = new Session(h, Rng.of(new Random(2)));
		s.setFigure("doyen", true);
		s.setFigure("sablier", true);
		assertThrows(GameException.class, () -> s.setFigure("inconnue", true));
		s.join("Ancien");
		s.join("Nouveau");
		s.addBots(6);
		s.start("Conteur", Session.Mode.AUTO);
		assertTrue(h.announces.stream().anyMatch(a -> a.startsWith("Figures neutres")));
		s.startNight();
		// La nuit se joue ; à l'aube, le Doyen fait taire l'ancien, pas le nouveau.
		while (!h.tasks.isEmpty() && s.game() != null && s.game().phase() == Game.Phase.NIGHT) {
			h.tasks.poll().run();
		}
		if (s.game() == null || s.game().phase() != Game.Phase.DAY) {
			return; // partie finie dès la première nuit : rien à vérifier
		}
		assertEquals(List.of(), s.voiceTargets("Ancien"), "l'ancien se tait");
		assertNull(s.voiceTargets("Nouveau"), "le nouveau parle normalement");
		// Le Sablier finit par clore la journée.
		for (int guard = 0; guard < 10_000 && !h.tasks.isEmpty() && s.game() != null && !s.game().dayOver(); guard++) {
			h.tasks.poll().run();
		}
		assertTrue(s.game() == null || s.game().dayOver(), "le Sablier clôt la journée");
	}
}
