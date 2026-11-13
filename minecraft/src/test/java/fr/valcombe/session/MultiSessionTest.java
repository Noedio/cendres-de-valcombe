package fr.valcombe.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Deux parties vivantes en même temps sur le même serveur, chacune avec sa session et son hôte (comme deux mondes de
 * partie) : l'inscription, le lancement, la nuit, les nominations, les votes et la sauvegarde de l'une ne touchent
 * jamais l'autre, et aucune ne parle aux joueurs de l'autre.
 */
class MultiSessionTest {
	/** Ce qu'on regarde d'une partie pour voir si elle a bougé. */
	private static String snapshot(Session s) {
		Game g = s.game();
		return s.lobby() + "|" + s.storyteller() + "|" + s.mode() + "|" + s.voteOpen() + "|" + s.nominationsOpen() + "|"
			+ (g == null ? "-" : g.phase() + " n" + g.night() + " j" + g.day() + " vivants " + g.aliveSeats()
				+ " joueurs " + g.players().stream().map(p -> p.name).toList());
	}

	private static List<String> names(String prefix, int n) {
		List<String> out = new ArrayList<>();
		for (int i = 1; i <= n; i++) {
			out.add(prefix + i);
		}
		return out;
	}

	/** Aucune annonce ni aucun bouton de cet hôte ne nomme un joueur de l'autre partie. */
	private static void silentAbout(SessionTest.FakeHost h, List<String> others) {
		for (String a : h.announces) {
			for (String o : others) {
				assertFalse(a.contains(o), "annonce « " + a + " » qui parle de " + o + ", joueur d'une autre partie");
			}
		}
		for (String o : others) {
			assertFalse(h.prompts.containsKey(o), "un bouton envoyé à " + o + ", joueur d'une autre partie");
		}
	}

	@Test
	void deuxPartiesEnMemeTempsSansSeMeler() {
		Random ra = new Random(11);
		Random rb = new Random(22);
		SessionTest.FakeHost ha = new SessionTest.FakeHost();
		SessionTest.FakeHost hb = new SessionTest.FakeHost();
		Session a = new Session(ha, Rng.of(ra));
		Session b = new Session(hb, Rng.of(rb));
		List<String> playersA = names("Alba", 7);
		List<String> playersB = names("Brune", 6);

		// Inscriptions : chacune son salon.
		playersA.forEach(a::join);
		String bBefore = snapshot(b);
		playersB.forEach(b::join);
		assertEquals(playersA, a.lobby());
		assertEquals(playersB, b.lobby());
		assertFalse(bBefore.equals(snapshot(b)), "le salon de B a bien changé");
		silentAbout(ha, playersB);
		silentAbout(hb, playersA);

		// B a son Conteur ; A reste en Conteur automatique.
		b.toggleStoryteller("ConteurB");
		assertNull(a.storyteller(), "le Conteur de B ne conte pas A");

		// Lancement de A seulement : B ne bouge pas.
		bBefore = snapshot(b);
		a.start("Alba1", Session.Mode.AUTO);
		SessionTest.pump(a, ha, ra);
		assertNotNull(a.game());
		assertNull(b.game(), "lancer A ne lance pas B");
		assertEquals(bBefore, snapshot(b));
		assertTrue(hb.tasks.isEmpty(), "aucune tâche de A dans l'horloge de B");

		// B se lance à son tour, en mode humain.
		b.start("ConteurB", Session.Mode.HUMAIN);
		SessionTest.pump(b, hb, rb);
		assertNotNull(b.game());
		assertEquals(Session.Mode.HUMAIN, b.mode());
		assertEquals(Session.Mode.AUTO, a.mode());

		// La nuit tombe sur A : B ne voit ni la nuit, ni ses effets.
		bBefore = snapshot(b);
		a.startNight();
		assertTrue(ha.night, "nuit dans A");
		assertFalse(hb.night, "pas de nuit dans B");
		SessionTest.pump(a, ha, ra);
		assertEquals(bBefore, snapshot(b), "la nuit de A n'a rien changé à B");
		silentAbout(hb, playersA);

		// Jour dans A : nominations et votes de A, jamais ceux de B.
		if (a.game() != null && a.game().phase() == Game.Phase.DAY) {
			SessionTest.openNominations(a);
			Game g = a.game();
			List<Integer> alive = g.aliveSeats();
			bBefore = snapshot(b);
			try {
				a.nominate(g.player(alive.get(0)).name, g.player(alive.get(1)).name);
			} catch (GameException ignored) {
				// une nomination refusée (déjà nominé...) n'enlève rien au contrôle
			}
			SessionTest.pump(a, ha, ra);
			assertEquals(bBefore, snapshot(b), "nominations et votes de A sans effet sur B");
			assertFalse(b.voteOpen());
			assertFalse(b.nominationsOpen(), "les nominations de B restent fermées");
		}

		// Un joueur de A ne vote pas dans B, et inversement : chaque partie ne connaît que les siens.
		String aBefore = snapshot(a);
		for (String p : playersA) {
			try {
				b.vote(p, true);
			} catch (GameException expected) {
				// pas de vote ouvert dans B, ou pas joueur de B
			}
		}
		assertEquals(aBefore, snapshot(a));
		assertTrue(b.game().players().stream().noneMatch(s -> playersA.contains(s.name)));

		// La nuit de B (Conteur humain) : A n'en sait rien.
		aBefore = snapshot(a);
		b.startNight();
		assertTrue(hb.night);
		SessionTest.pump(b, hb, rb);
		assertEquals(aBefore, snapshot(a), "la nuit de B n'a rien changé à A");
		silentAbout(ha, playersB);
		silentAbout(hb, playersA);

		// Chacune sa sauvegarde, et chacune reprend seule, avec ses joueurs.
		a.saveNow();
		b.saveNow();
		assertFalse(ha.saved.isEmpty());
		assertFalse(hb.saved.isEmpty());
		for (String p : playersB) {
			assertFalse(ha.saved.contains(p), "la sauvegarde de A ne contient pas " + p);
		}
		for (String p : playersA) {
			assertFalse(hb.saved.contains(p), "la sauvegarde de B ne contient pas " + p);
		}
		Session a2 = new Session(new SessionTest.FakeHost(), Rng.of(new Random(5)));
		a2.restore(ha.saved);
		assertEquals(a.game().players().stream().map(p -> p.name).toList(), a2.game().players().stream().map(p -> p.name).toList());
		assertEquals(Session.Mode.AUTO, a2.mode());
		Session b2 = new Session(new SessionTest.FakeHost(), Rng.of(new Random(6)));
		b2.restore(hb.saved);
		assertEquals("ConteurB", b2.storyteller());
		assertEquals(b.game().players().stream().map(p -> p.name).toList(), b2.game().players().stream().map(p -> p.name).toList());

		// Arrêter A ne termine pas B.
		bBefore = snapshot(b);
		a.stop();
		SessionTest.pump(a, ha, ra);
		assertNull(a.game());
		assertNotNull(b.game(), "B continue");
		assertEquals(bBefore, snapshot(b));
	}

	@Test
	void deuxPartiesJoueesJusquAuBoutEnAlternance() {
		// Deux parties de joueurs fictifs menées pas à pas, en alternance : chacune finit avec son vainqueur, sans
		// jamais nommer un joueur de l'autre.
		Random ra = new Random(3);
		Random rb = new Random(4);
		SessionTest.FakeHost ha = new SessionTest.FakeHost();
		SessionTest.FakeHost hb = new SessionTest.FakeHost();
		Session a = new Session(ha, Rng.of(ra));
		Session b = new Session(hb, Rng.of(rb));
		a.addBots(8);
		b.addBots(5);
		List<String> botsA = a.lobby();
		a.start("Conteur", Session.Mode.AUTO);
		b.start("Conteur", Session.Mode.AUTO);
		assertEquals(8, a.game().size());
		assertEquals(5, b.game().size());
		for (int round = 0; round < 300 && (a.game() != null || b.game() != null); round++) {
			for (Session s : List.of(a, b)) {
				SessionTest.FakeHost h = s == a ? ha : hb;
				Random r = s == a ? ra : rb;
				if (s.game() == null) {
					continue;
				}
				s.startNight();
				SessionTest.pump(s, h, r);
				if (s.game() == null) {
					continue;
				}
				Game g = s.game();
				List<Integer> alive = g.aliveSeats();
				try {
					SessionTest.openNominations(s);
					s.nominate(g.player(alive.get(r.nextInt(alive.size()))).name, g.player(alive.get(r.nextInt(alive.size()))).name);
				} catch (GameException ignored) {
					// nomination refusée : la journée continue
				}
				SessionTest.pump(s, h, r);
				if (s.game() != null && !s.game().dayOver()) {
					s.endDay();
					SessionTest.pump(s, h, r);
				}
			}
		}
		assertNull(a.game(), "A est allée au bout");
		assertNull(b.game(), "B est allée au bout");
		assertNotNull(a.lastGame().winner());
		assertNotNull(b.lastGame().winner());
		assertEquals(8, a.lastGame().size());
		assertEquals(5, b.lastGame().size());
		// Les joueurs fictifs portent les mêmes noms d'une partie à l'autre (Bot1...) : ce sont bien deux tables distinctes.
		assertTrue(botsA.containsAll(b.lastGame().players().stream().map(p -> p.name).toList()));
		assertFalse(a.lastGame() == b.lastGame());
	}
}
