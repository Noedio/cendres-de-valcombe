package fr.valcombe.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Mise en suspens et reprise d'une partie : l'horloge est gelée et tout ce qui s'y comptait s'arrête là où il en était,
 * les joueurs vont au hub, la partie est enregistrée et reprend en suspens après un redémarrage ; à la reprise, les
 * joueurs sont rappelés, la partie attend (en pause) qu'ils aient regagné leur place, puis les minuteurs repartent où
 * ils en étaient. Une autre partie ne voit rien de tout cela.
 */
class SuspendTest {
	/**
	 * Un hôte qui tient une horloge gelable, les joueurs « à la table » (dans le monde de la partie) et ceux qui sont au
	 * hub, comme McHost.
	 */
	static final class SuspendHost extends SessionTest.FakeHost {
		boolean frozen;
		/** Les joueurs dans le monde de la partie. */
		final List<String> here = new ArrayList<>();
		/** Les joueurs ailleurs (au hub) : ils ne sont pas « à la table ». */
		final Set<String> away = new HashSet<>();
		final List<String> recalled = new ArrayList<>();
		final Map<String, List<String>> said = new HashMap<>();
		int freezes;

		@Override
		public void freeze(boolean on) {
			frozen = on;
			freezes++;
		}

		@Override
		public List<String> sendAway() {
			List<String> out = List.copyOf(here);
			away.addAll(out);
			here.clear();
			return out;
		}

		@Override
		public void recall(List<String> players) {
			recalled.addAll(players);
		}

		@Override
		public boolean atTable(String player) {
			return !away.contains(player);
		}

		@Override
		public void tell(String player, Msg msg) {
			super.tell(player, msg);
			StringBuilder t = new StringBuilder();
			msg.parts.forEach(p -> t.append(p.text()));
			said.computeIfAbsent(player, k -> new ArrayList<>()).add(t.toString());
		}

		/** Le joueur revient à la table (il a suivi le lien). */
		void back(String player) {
			away.remove(player);
			here.add(player);
		}

		/** Exécute au plus n tâches de l'horloge de la partie ; rien tant qu'elle est gelée. Renvoie combien ont tourné. */
		int run(int n) {
			int done = 0;
			while (done < n && !frozen && !tasks.isEmpty()) {
				tasks.poll().run();
				done++;
			}
			return done;
		}
	}

	private static final List<String> PLAYERS = List.of("Alba", "Brune", "Cyrille", "Dalia", "Eloi", "Fanny", "Gaspard");
	private static final String CONTEUR = "Conteur";

	/**
	 * Une partie de sept joueurs menée par un Conteur, au premier jour, sans rien qui attende ; tous dans le monde de la
	 * partie (le Conteur aussi).
	 */
	private static Session dayOne(SuspendHost h, long seed) {
		Random random = new Random(seed);
		Session s = new Session(h, Rng.of(random));
		s.toggleStoryteller(CONTEUR);
		PLAYERS.forEach(s::join);
		s.start(CONTEUR, Session.Mode.HUMAIN);
		SessionTest.pump(s, h, random);
		s.startNight();
		SessionTest.pump(s, h, random);
		assertNotNull(s.game(), "la partie continue après la première nuit (graine " + seed + ")");
		assertEquals(Game.Phase.DAY, s.game().phase(), "le premier jour");
		h.here.addAll(PLAYERS);
		h.here.add(CONTEUR);
		return s;
	}

	/** Une partie au premier jour, discussion libre lancée et entamée de quelques secondes. */
	private static Session dayWithDiscussion(SuspendHost h, long seed) {
		Session s = dayOne(h, seed);
		s.discussion(5);
		assertEquals(5, h.run(5));
		assertEquals(5 * 60 - 5, s.discussionLeft, "cinq secondes écoulées");
		return s;
	}

	@Test
	void miseEnSuspensGeleEnregistreEtRenvoieAuHub() {
		SuspendHost h = new SuspendHost();
		Session s = dayWithDiscussion(h, 3);
		int left = s.discussionLeft;

		String said = s.suspend();
		assertTrue(said.contains("8 joueur(s) renvoyé(s) au hub"), said);
		assertTrue(s.suspended() && s.paused(), "en suspens, donc en pause");
		assertTrue(h.frozen, "horloge gelée");
		assertTrue(h.away.containsAll(PLAYERS) && h.away.contains(CONTEUR), "tous au hub, le Conteur aussi");
		assertTrue(h.here.isEmpty());
		assertTrue(h.saved.contains("\"suspended\":true"), "la fiche le dit : " + h.saved);
		assertTrue(h.announces.stream().anyMatch(a -> a.contains("mise en suspens")));

		// Le temps passe : rien ne bouge.
		assertEquals(0, h.run(500));
		assertEquals(left, s.discussionLeft, "le minuteur n'a pas bougé");
		assertEquals(Game.Phase.DAY, s.game().phase());

		// Rien ne se commande d'une partie en suspens, et on ne la suspend pas deux fois.
		GameException pause = assertThrows(GameException.class, s::togglePause);
		assertTrue(pause.getMessage().contains("suspens"), pause.getMessage());
		assertThrows(GameException.class, s::suspend);
		assertThrows(GameException.class, () -> s.discussion(3), "rien ne se relance pendant le suspens");
		// Ni les gestes des joueurs, lancés depuis le hub (leur commande vise encore leur table) : main levée, choix de
		// nuit, réponse au réveil, décision du Conteur.
		for (org.junit.jupiter.api.function.Executable gesture : List.<org.junit.jupiter.api.function.Executable>of(
				() -> s.vote("Alba", true), () -> s.choose("Alba", "Brune"), () -> s.validate("Alba"), () -> s.sleep("Alba"),
				() -> s.decide(CONTEUR, 0))) {
			GameException refused = assertThrows(GameException.class, gesture);
			assertTrue(refused.getMessage().contains("suspens"), refused.getMessage());
		}

		// Un joueur qui se reconnecte reste au hub, prévenu.
		s.onJoin("Brune");
		assertTrue(h.said.getOrDefault("Brune", List.of()).stream().anyMatch(t -> t.contains("en suspens")), "prévenu");
	}

	@Test
	void repriseApresRedemarrageToujoursEnSuspensPuisRetourALaTable() {
		SuspendHost h = new SuspendHost();
		Session s = dayWithDiscussion(h, 3);
		int left = s.discussionLeft;
		s.suspend();
		String saved = h.saved;
		String roles = s.game().describeRoles();

		// Redémarrage du serveur : la partie reprend dans son monde, toujours en suspens, l'horloge gelée.
		SuspendHost h2 = new SuspendHost();
		h2.away.addAll(PLAYERS); // ils attendent au hub
		h2.away.add(CONTEUR);
		Session s2 = new Session(h2, Rng.of(new Random(99)));
		s2.restore(saved);
		assertEquals(roles, s2.game().describeRoles(), "la même partie");
		assertTrue(s2.suspended() && s2.paused() && h2.frozen, "en suspens après le redémarrage");
		assertEquals(left, s2.discussionLeft, "la discussion libre reprend où elle en était");
		assertFalse(h2.announces.stream().anyMatch(a -> a.contains("Partie reprise")), "pas d'annonce à une table vide");
		assertEquals(0, h2.run(100));

		// Reprise : l'horloge repart, chacun est rappelé ; la partie attend en pause que tous soient revenus.
		String said = s2.resumeSuspended();
		assertTrue(said.contains("rappelés"), said);
		assertFalse(s2.suspended());
		assertFalse(h2.frozen, "horloge remise en marche");
		assertTrue(h2.recalled.containsAll(PLAYERS) && h2.recalled.contains(CONTEUR), "tous rappelés : " + h2.recalled);
		assertTrue(s2.paused() && s2.gathering(), "en pause le temps du retour");
		assertTrue(h2.saved.contains("\"suspended\":false"), "la fiche n'est plus en suspens");
		PLAYERS.subList(0, 6).forEach(h2::back);
		h2.back(CONTEUR);
		h2.run(20);
		assertTrue(s2.paused(), "Gaspard n'est pas encore revenu : on l'attend");
		assertEquals(left, s2.discussionLeft, "et le minuteur attend avec la partie");
		h2.back("Gaspard");
		h2.run(5);
		assertFalse(s2.paused(), "tout le monde est là : la partie repart");
		assertFalse(s2.gathering());
		assertTrue(h2.announces.stream().anyMatch(a -> a.contains("reprend")));
		h2.run(10);
		assertTrue(s2.discussionLeft < left, "la discussion libre s'écoule de nouveau");
	}

	@Test
	void lesRetardatairesNeRetiennentLaPartiePlusDUneMinute() {
		SuspendHost h = new SuspendHost();
		Session s = dayWithDiscussion(h, 5);
		s.suspend();
		s.resumeSuspended();
		PLAYERS.subList(1, 7).forEach(h::back); // Alba reste au hub
		h.back(CONTEUR);
		h.run(Session.GATHER_SECONDS * 3); // la discussion libre ne s'écoule pas pendant l'attente : ses tâches s'intercalent
		assertFalse(s.paused(), "au bout d'une minute, la partie repart sans l'absente");
		assertFalse(s.gathering());
	}

	@Test
	void laPauseDuConteurResteApresLaReprise() {
		SuspendHost h = new SuspendHost();
		Session s = dayWithDiscussion(h, 7);
		s.togglePause();
		assertTrue(s.paused());
		s.suspend();
		s.resumeSuspended();
		PLAYERS.forEach(h::back);
		h.back(CONTEUR);
		h.run(5);
		assertFalse(s.gathering());
		assertTrue(s.paused(), "la pause d'avant la mise en suspens est rendue telle quelle");
		assertTrue(h.announces.stream().anyMatch(a -> a.contains("reste en pause")));
		s.togglePause();
		assertFalse(s.paused());
	}

	@Test
	void leConteurRelancePendantLAttente() {
		SuspendHost h = new SuspendHost();
		Session s = dayWithDiscussion(h, 9);
		s.suspend();
		s.resumeSuspended();
		assertTrue(s.paused() && s.gathering());
		s.togglePause(); // lever la pause sans attendre les retardataires
		assertFalse(s.paused());
		assertFalse(s.gathering(), "l'attente s'arrête");
		h.run(Session.GATHER_SECONDS * 3);
		assertFalse(s.paused(), "la fin de l'attente ne remet pas de pause");
	}

	@Test
	void arreterUnePartieEnSuspens() {
		SuspendHost h = new SuspendHost();
		Session s = dayWithDiscussion(h, 11);
		s.suspend();
		assertTrue(h.frozen);
		s.stop();
		assertFalse(h.frozen, "l'horloge repart pour la fin de partie");
		assertFalse(s.suspended());
		assertTrue(h.saved.isEmpty(), "plus rien à reprendre");
		assertThrows(GameException.class, s::resumeSuspended);
	}

	@Test
	void uneMiseEnSuspensNeToucheQueSaPartie() {
		SuspendHost ha = new SuspendHost();
		SuspendHost hb = new SuspendHost();
		Session a = dayWithDiscussion(ha, 13);
		Session b = dayWithDiscussion(hb, 17);
		String bSaved = hb.saved;
		int bLeft = b.discussionLeft;
		a.suspend();
		assertTrue(a.suspended());
		assertFalse(b.suspended() || b.paused(), "l'autre partie continue");
		assertFalse(hb.frozen, "son horloge tourne");
		assertTrue(hb.away.isEmpty(), "ses joueurs restent à leur table");
		assertEquals(bSaved, hb.saved, "sa fiche n'a pas bougé");
		hb.run(10);
		assertTrue(b.discussionLeft < bLeft, "son minuteur s'écoule");
		assertEquals(0, ha.run(10));
	}

	@Test
	void minuteursEtPauseRepartentApresUnRedemarrage() {
		// Une partie de joueurs fictifs avec le Sablier : la journée a ses dix minutes, la discussion libre les siennes.
		Random random = new Random(21);
		SuspendHost h = new SuspendHost();
		Session s = new Session(h, Rng.of(random));
		s.setFigure("sablier", true);
		s.addBots(8);
		s.start("Conteur", Session.Mode.AUTO);
		for (int i = 0; i < 20000 && s.game() != null && s.game().phase() != Game.Phase.DAY && !h.tasks.isEmpty(); i++) {
			h.tasks.poll().run();
		}
		if (s.game() == null || s.game().phase() != Game.Phase.DAY) {
			s.startNight();
			for (int i = 0; i < 20000 && s.game() != null && s.game().phase() != Game.Phase.DAY && !h.tasks.isEmpty(); i++) {
				h.tasks.poll().run();
			}
		}
		assertNotNull(s.game());
		assertEquals(Game.Phase.DAY, s.game().phase(), "le premier jour se lève");
		assertEquals(Session.SAND_SECONDS, s.sandLeft, "le Sablier est plein à l'aube");
		s.discussion(4);
		h.run(30);
		assertTrue(s.sandLeft < Session.SAND_SECONDS, "le Sablier s'écoule");
		s.extend(1);
		int sand = s.sandLeft;
		int talk = s.discussionLeft;
		assertTrue(sand > 60 && talk > 0);
		s.togglePause();
		s.saveNow();

		SuspendHost h2 = new SuspendHost();
		Session s2 = new Session(h2, Rng.of(new Random(22)));
		s2.restore(h.saved);
		assertEquals(sand, s2.sandLeft, "le Sablier reprend où il en était");
		assertEquals(talk, s2.discussionLeft, "la discussion libre aussi");
		assertTrue(s2.paused(), "la pause du Conteur est gardée");
		assertFalse(s2.suspended());
		assertFalse(h2.frozen, "pas en suspens : l'horloge tourne");
		assertTrue(h2.announces.stream().anyMatch(a -> a.contains("en pause")), "l'annonce le dit");
		h2.run(20);
		assertEquals(sand, s2.sandLeft, "rien ne coule pendant la pause");
		s2.togglePause();
		h2.run(20);
		assertTrue(s2.sandLeft < sand, "il coule de nouveau");
	}
}
