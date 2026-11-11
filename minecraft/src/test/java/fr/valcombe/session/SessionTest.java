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
		String saved = "";
		final List<VoteScene> scenes = new ArrayList<>();
		final List<String> spectators = new ArrayList<>();

		@Override
		public void voteScene(VoteScene scene) {
			scenes.add(scene);
		}

		@Override
		public void spectator(String player, boolean on) {
			if (on) {
				spectators.add(player);
			} else {
				spectators.remove(player);
			}
		}

		@Override
		public void persist(String json) {
			saved = json;
		}

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
				} else if (has(msgs, "/vc decider 0")) {
					long n = msgs.stream().flatMap(m -> m.parts.stream())
						.filter(pt -> pt.command() != null && pt.command().startsWith("/vc decider")).count();
					s.decide(who, random.nextInt((int) n));
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

	static void openNominations(Session s) {
		if (s.game() != null && s.game().phase() == Game.Phase.DAY && !s.game().dayOver() && !s.nominationsOpen()) {
			s.setNominations(true);
		}
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
				openNominations(s);
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
		botGames(false);
	}

	@Test
	void partiesDesBrumesDeValcombe() {
		botGames(true);
	}

	private static void botGames(boolean brumes) {
		for (int seed = 0; seed < 40; seed++) {
			Random random = new Random(seed);
			FakeHost h = new FakeHost();
			Session s = new Session(h, Rng.of(random));
			if (brumes) {
				s.useScript("Les Brumes de Valcombe", fr.valcombe.rules.Role.brumes());
			}
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
					openNominations(s);
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
	void partieSauvegardeeEtReprise() {
		Random random = new Random(11);
		FakeHost h = new FakeHost();
		Session s = new Session(h, Rng.of(random));
		s.addBots(8);
		s.start("Conteur", Session.Mode.AUTO);
		s.startNight();
		pump(s, h, random);
		assertTrue(!h.saved.isEmpty(), "partie enregistrée");
		String roles = s.game().describeRoles();
		int day = s.game().day();

		FakeHost h2 = new FakeHost();
		Session s2 = new Session(h2, Rng.of(new Random(12)));
		s2.restore(h.saved);
		assertNotNull(s2.game());
		assertTrue(s2.game().describeRoles().equals(roles), "mêmes rôles");
		assertTrue(s2.game().day() == day && s2.game().phase() == Game.Phase.DAY, "même jour");
		for (int i = 0; i < 300 && s2.game() != null; i++) {
			if (s2.game().phase() == Game.Phase.DAY) {
				s2.endDay();
			}
			if (s2.game() != null) {
				s2.startNight();
				while (!h2.tasks.isEmpty()) {
					h2.tasks.poll().run();
				}
			}
		}
		assertNull(s2.game(), "la partie reprise va jusqu'au bout");
		assertTrue(h2.saved.isEmpty(), "plus rien à reprendre");
	}

	@Test
	void voteALHorloge() {
		FakeHost h = new FakeHost();
		Session s = new Session(h, Rng.of(new Random(5)));
		for (int i = 1; i <= 6; i++) {
			s.join("J" + i);
		}
		s.toggleStoryteller("Conteur");
		s.start("Conteur", Session.Mode.HUMAIN);
		s.startNight();
		pump(s, h, new Random(5));
		assertThrows(GameException.class, () -> s.nominate("J1", "J2"), "nominations fermées");
		s.setNominations(true);
		s.nominate("J1", "J2");
		assertThrows(GameException.class, () -> s.vote("J3", true), "pendant la défense");
		s.startVoteNow();
		h.prompts.clear();
		s.vote("J3", true);
		s.vote("J4", true);
		s.vote("J4", false); // il se ravise avant le passage de l'aiguille
		s.vote("J5", true);
		while (!h.tasks.isEmpty() && s.voteOpen()) {
			h.tasks.poll().run();
		}
		assertTrue(!s.voteOpen(), "le vote est terminé");
		assertThrows(GameException.class, () -> s.vote("J5", false), "trop tard");
		assertTrue(h.announces.stream().anyMatch(a -> a.startsWith("2 voix contre J2")), String.join(" | ", h.announces));
		// La scène 3D a suivi le vote : défense, aiguille, mains levées, puis plus rien.
		assertTrue(h.scenes.stream().anyMatch(v -> v != null && v.defense() && v.nominee().equals("J2")), "défense");
		assertTrue(h.scenes.stream().anyMatch(v -> v != null && !v.defense() && v.pointing() != null), "aiguille");
		assertTrue(h.scenes.stream().anyMatch(v -> v != null && v.raised().contains("J3")), "main levée");
		assertTrue(h.scenes.stream().anyMatch(v -> v != null && v.locked().contains("J5")), "main comptée");
		assertNull(h.scenes.get(h.scenes.size() - 1), "scène retirée après le vote");
	}

	@Test
	void vocalDeNuitConteurEtJoueurReveille() {
		FakeHost h = new FakeHost();
		Session s = new Session(h, Rng.of(new Random(8)));
		for (int i = 1; i <= 6; i++) {
			s.join("J" + i);
		}
		s.toggleStoryteller("Conteur");
		s.start("Conteur", Session.Mode.HUMAIN);
		assertNull(s.voiceTargets("J1"), "avant la nuit : vocal normal");
		s.startNight();
		// On avance jusqu'au premier joueur réveillé
		String awake = null;
		for (int guard = 0; guard < 500 && awake == null; guard++) {
			awake = h.prompts.keySet().stream().filter(n -> !n.equals("Conteur")).findFirst().orElse(null);
			if (awake == null && !h.tasks.isEmpty()) {
				h.tasks.poll().run();
			} else if (awake == null) {
				h.prompts.clear();
				break;
			}
		}
		assertNotNull(awake, "un joueur se réveille");
		assertTrue(s.voiceTargets(awake).equals(List.of("Conteur")), "le réveillé ne parle qu'au Conteur");
		assertTrue(s.voiceTargets("Conteur").contains(awake), "le Conteur parle au réveillé");
		final String woken = awake;
		String asleep = s.game().players().stream().map(p -> p.name).filter(n -> !n.equals(woken)).findFirst().get();
		assertTrue(s.voiceTargets(asleep).isEmpty(), "les dormeurs ne sont entendus de personne");
		assertNull(s.voiceTargets("Visiteur"), "un visiteur hors partie garde le vocal normal");
	}

	@Test
	void spectateursEtEntrainement() {
		FakeHost h = new FakeHost();
		Session s = new Session(h, Rng.of(new Random(2)));
		s.spectate("Spec");
		assertTrue(s.isSpectator("Spec") && h.spectators.contains("Spec"));
		s.join("A");
		assertThrows(GameException.class, () -> s.spectate("A"), "un inscrit ne peut pas regarder");
		s.leave("A");
		s.training("Eleve");
		assertNotNull(s.game());
		assertTrue(s.isTrainee("Eleve"));
		assertTrue(s.game().size() == 7, "l'élève et six joueurs fictifs");
		assertTrue(s.game().seatOf("Spec") < 0, "le spectateur ne joue pas");
		assertThrows(GameException.class, () -> s.discussion(5), "pas de discussion avant le jour");
		s.spectate("Spec");
		assertTrue(!s.isSpectator("Spec") && h.spectators.isEmpty());
		s.stop();
		assertTrue(!s.isTrainee("Eleve"));
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
