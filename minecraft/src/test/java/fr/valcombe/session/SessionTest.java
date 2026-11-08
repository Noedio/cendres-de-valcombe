package fr.valcombe.session;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;
import fr.valcombe.rules.Step;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Joue des parties complètes à travers la session, en cliquant sur les boutons du chat. */
class SessionTest {

	static final class FakeHost implements Host {
		final Deque<Runnable> tasks = new ArrayDeque<>();
		final Map<String, List<Msg>> prompts = new HashMap<>();
		final List<String> announces = new ArrayList<>();
		boolean night;

		@Override
		public void announce(String text) {
			announces.add(text);
		}

		@Override
		public void tell(String player, Msg msg) {
			boolean button = msg.parts.stream().anyMatch(p -> p.command() != null);
			if (button) {
				prompts.computeIfAbsent(player, k -> new ArrayList<>()).add(msg);
			}
		}

		@Override
		public boolean online(String player) {
			return true;
		}

		@Override
		public void schedule(int ticks, Runnable r) {
			tasks.add(r);
		}

		@Override
		public void seatPlayers(List<String> players, String anchor) {
		}

		@Override
		public void nightEffects(List<String> players, boolean on, Settings s) {
			night = on;
		}

		@Override
		public void endGame(List<String> players) {
			night = false;
		}
	}

	static boolean has(List<Msg> msgs, String command) {
		return msgs.stream().anyMatch(m -> m.parts.stream().anyMatch(p -> command.equals(p.command())));
	}

	/** Exécute les tâches et répond aux boutons jusqu'à ce que plus rien n'attende. */
	static void pump(Session s, FakeHost h, Random random) {
		for (int guard = 0; guard < 20000; guard++) {
			if (!h.tasks.isEmpty()) {
				h.tasks.poll().run();
				continue;
			}
			String who = h.prompts.keySet().stream().findFirst().orElse(null);
			if (who == null) {
				return;
			}
			List<Msg> msgs = h.prompts.remove(who);
			try {
				if (has(msgs, "/vc valider")) {
					Game g = s.game();
					Step step = g.currentStep();
					List<String> picked = new ArrayList<>();
					while (picked.size() < step.choose()) {
						int t = random.nextInt(g.size());
						String name = g.player(t).name;
						if (!(step.notSelf() && t == step.seat) && !picked.contains(name)) {
							picked.add(name);
						}
					}
					for (String p : picked) {
						s.choose(who, p);
					}
					h.prompts.remove(who);
					s.validate(who);
				} else if (has(msgs, "/vc envoyer")) {
					s.storytellerSend(who, null);
				} else if (has(msgs, "/vc dormir")) {
					s.sleep(who);
				} else if (has(msgs, "/vc vote oui")) {
					s.vote(who, random.nextBoolean());
				}
			} catch (GameException ignored) {
				// Bouton périmé : normal.
			}
		}
		throw new AssertionError("boucle sans fin");
	}

	static Game play(long seed, Session.Mode mode, int n) {
		Random random = new Random(seed);
		FakeHost h = new FakeHost();
		Session s = new Session(h, Rng.of(random));
		if (mode == Session.Mode.HUMAIN) {
			s.toggleStoryteller("Conteur");
		}
		for (int i = 1; i <= n; i++) {
			s.join("J" + i);
		}
		s.start("Conteur", mode);
		pump(s, h, random);
		for (int day = 0; day < 300 && s.game() != null; day++) {
			s.startNight();
			pump(s, h, random);
			if (s.game() == null) {
				break;
			}
			assertTrue(s.game().phase() == Game.Phase.DAY, "le jour suit la nuit");
			Game g = s.game();
			List<Integer> alive = g.aliveSeats();
			String a = g.player(alive.get(random.nextInt(alive.size()))).name;
			String b = g.player(alive.get(random.nextInt(alive.size()))).name;
			try {
				s.shoot(a, b);
			} catch (GameException ignored) {
			}
			pump(s, h, random);
			if (s.game() == null) {
				break;
			}
			try {
				s.nominate(a, b);
			} catch (GameException ignored) {
			}
			pump(s, h, random);
			if (s.game() != null && !s.game().dayOver()) {
				s.endDay();
				pump(s, h, random);
			}
		}
		assertNull(s.game(), "la partie doit se terminer");
		assertTrue(!h.night, "les effets de nuit sont retirés");
		return s.lastGame();
	}

	@Test
	void partiesCompletesAutoEtHumain() {
		for (int seed = 0; seed < 80; seed++) {
			Session.Mode mode = seed % 2 == 0 ? Session.Mode.HUMAIN : Session.Mode.AUTO;
			Game g = play(seed, mode, 5 + seed % 12);
			assertNotNull(g.winner());
		}
	}

	@Test
	void partiesJoueesParDesJoueursFictifs() {
		for (int seed = 0; seed < 40; seed++) {
			Random random = new Random(seed);
			FakeHost h = new FakeHost();
			Session s = new Session(h, Rng.of(random));
			s.addBots(5 + seed % 12);
			s.start("Conteur", Session.Mode.AUTO);
			for (int day = 0; day < 300 && s.game() != null; day++) {
				s.startNight();
				while (!h.tasks.isEmpty()) {
					h.tasks.poll().run();
				}
				if (s.game() == null) {
					break;
				}
				Game g = s.game();
				List<Integer> alive = g.aliveSeats();
				try {
					s.nominate(g.player(alive.get(random.nextInt(alive.size()))).name,
						g.player(alive.get(random.nextInt(alive.size()))).name);
				} catch (GameException ignored) {
				}
				while (!h.tasks.isEmpty()) {
					h.tasks.poll().run();
				}
				if (s.game() != null && !s.game().dayOver()) {
					s.endDay();
				}
			}
			assertNull(s.game(), "la partie de bots doit se terminer (graine " + seed + ")");
			assertTrue(h.prompts.isEmpty(), "les bots ne reçoivent pas de messages");
		}
	}

	@Test
	void microsCoupesLaNuitSaufLeConteur() {
		FakeHost h = new FakeHost();
		Session s = new Session(h, Rng.of(new Random(3)));
		s.toggleStoryteller("Conteur");
		s.addBots(6);
		s.start("Conteur", Session.Mode.AUTO);
		assertTrue(!s.isMuted("Bot1"), "avant la nuit");
		s.startNight();
		assertTrue(s.isMuted("Bot1"));
		assertTrue(!s.isMuted("Conteur"), "le Conteur parle");
		assertTrue(!s.isMuted("Inconnu"), "un spectateur n'est pas concerné");
		s.setting("nuit_muette", "non");
		assertTrue(!s.isMuted("Bot1"));
	}

	@Test
	void reglesDInscription() {
		FakeHost h = new FakeHost();
		Session s = new Session(h);
		s.join("A");
		assertThrows(GameException.class, () -> s.join("A"));
		assertThrows(GameException.class, () -> s.toggleStoryteller("A"));
		s.toggleStoryteller("B");
		assertThrows(GameException.class, () -> s.join("B"));
		assertThrows(GameException.class, () -> s.start("B", Session.Mode.AUTO), "trop peu de joueurs");
	}
}
