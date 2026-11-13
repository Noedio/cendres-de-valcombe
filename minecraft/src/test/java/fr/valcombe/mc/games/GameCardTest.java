package fr.valcombe.mc.games;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.Distribution;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;
import fr.valcombe.session.Session;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Le tableau des parties : la fiche de chaque partie (état, phase, joueurs et places, Conteur, spectateurs), qui peut la
 * rejoindre ou la regarder et pourquoi, et ceux qui la regardent depuis le hub (sans les rôles, à part au vocal).
 */
class GameCardTest {
	private final GameSeatsTest.QuietHost hostA = new GameSeatsTest.QuietHost();
	private final GameSeatsTest.QuietHost hostB = new GameSeatsTest.QuietHost();
	private final Session a = new Session(hostA, Rng.of(new Random(5)));
	private final Session b = new Session(hostB, Rng.of(new Random(6)));
	private final GameSeats.Table tableA = new GameSeats.Table("partie-principale", "Partie principale", a);
	private final GameSeats.Table tableB = new GameSeats.Table("essai-du-mardi", "Essai du mardi", b);
	private final List<GameSeats.Table> tables = List.of(tableA, tableB);

	GameCardTest() {
		a.admission = p -> GameSeats.admit(tables, tableA, p);
		b.admission = p -> GameSeats.admit(tables, tableB, p);
	}

	private GameCard cardA(int seats, int present) {
		return GameCard.of("partie-principale", "Partie principale", true, false, a, seats, present);
	}

	/** Une partie de six joueurs menée par une Conteuse, lancée. */
	private void startA() {
		a.toggleStoryteller("Conteuse");
		for (int i = 1; i <= 6; i++) {
			a.join("J" + i);
		}
		a.start("Conteuse", Session.Mode.HUMAIN);
		assertNotNull(a.game());
	}

	@Test
	void uneFicheDeSalon() {
		GameCard c = cardA(12, 0);
		assertEquals(GameCard.Status.OUVERTE, c.status());
		assertFalse(c.running());
		assertEquals("Salon ouvert", c.phaseLine());
		assertEquals("salon", c.phaseCode());
		assertEquals(0, c.number());
		assertEquals("Personne d'inscrit, 12 places", c.playersLine());
		assertEquals("Conteur automatique", c.storytellerLine());
		assertEquals("Partie principale", c.kindLabel());
		a.join("Alice");
		a.join("Bruno");
		a.toggleStoryteller("Conteuse");
		c = cardA(12, 3);
		assertEquals("2 inscrits sur 12 places", c.playersLine());
		assertEquals("Conteur : Conteuse", c.storytellerLine());
		assertEquals(3, c.present());
		a.leave("Bruno");
		assertEquals("1 inscrit sur 12 places", cardA(12, 0).playersLine());
	}

	@Test
	void lesPlacesNeDepassentJamaisUnePartie() {
		assertEquals(Distribution.MAX_PLAYERS, GameCard.places(0), "pas encore de village : le nombre de joueurs d'une partie");
		assertEquals(Distribution.MAX_PLAYERS, GameCard.places(20), "un village de vingt trônes n'accueille pas plus d'une partie");
		assertEquals(9, GameCard.places(9));
		assertEquals(1, GameCard.places(1));
	}

	@Test
	void enCoursEnPauseEnSuspensPuisFinie() {
		startA();
		GameCard c = cardA(12, 7);
		assertEquals(GameCard.Status.EN_COURS, c.status());
		assertTrue(c.running());
		assertEquals(6, c.players(), "les assis, pas les inscrits");
		assertEquals("6 joueurs sur 12 places", c.playersLine());
		assertTrue(List.of("Mise en place", "Nuit 1", "Jour 1").contains(c.phaseLine()), c.phaseLine());

		a.togglePause();
		assertEquals(GameCard.Status.EN_PAUSE, cardA(12, 7).status());
		a.togglePause();
		assertEquals(GameCard.Status.EN_COURS, cardA(12, 7).status());

		a.suspend();
		GameCard s = cardA(12, 0);
		assertEquals(GameCard.Status.SUSPENDUE, s.status(), "en suspens l'emporte sur la pause qu'il pose");
		assertEquals("suspendue", s.view().get("statut"));
		assertEquals("En suspens", s.view().get("statutTexte"));
		a.resumeSuspended();
		assertNotEquals(GameCard.Status.SUSPENDUE, cardA(12, 7).status());

		a.stop();
		hostA.tasks.forEach(Runnable::run);
		assertEquals(GameCard.Status.OUVERTE, cardA(12, 0).status(), "une partie finie rouvre son salon");
	}

	@Test
	void laVueDuneFiche() {
		a.join("Alice");
		Map<String, Object> v = GameCard.of("essai-du-mardi", "Essai du mardi", false, true, a, 10, 2).view();
		assertEquals("essai-du-mardi", v.get("id"));
		assertEquals("essai", v.get("sorte"));
		assertEquals(false, v.get("principale"));
		assertEquals("ouverte", v.get("statut"));
		assertEquals("salon", v.get("phase"));
		assertEquals(1, v.get("joueurs"));
		assertEquals(10, v.get("places"));
		assertEquals(2, v.get("surPlace"));
		assertEquals(0, v.get("spectateurs"));
		assertEquals("Partie d'essai", GameCard.of("e", "E", false, true, a, 10, 0).kindLabel());
	}

	@Test
	void quiPeutRejoindre() {
		assertNull(GameCard.joinRefusal(a, "Alice", 12), "salon ouvert : on s'inscrit");
		assertEquals("Rejoindre", GameCard.joinLabel(a, "Alice"));
		a.join("Alice");
		assertNull(GameCard.joinRefusal(a, "Alice", 12), "inscrite : elle y retourne");
		assertEquals("Y retourner", GameCard.joinLabel(a, "Alice"));
		assertEquals("inscrit", GameCard.seat(a, "Alice"));

		// Un salon plein refuse les suivants.
		for (int i = 1; i <= 4; i++) {
			a.join("P" + i);
		}
		String full = GameCard.joinRefusal(a, "Zoe", 5);
		assertNotNull(full);
		assertTrue(full.contains("plein") && full.contains("5 places"), full);

		// Partie lancée : on ne la rejoint plus, on la regarde ; ses joueurs et sa Conteuse y retournent.
		GameSeatsTest.QuietHost h = new GameSeatsTest.QuietHost();
		Session s = new Session(h, Rng.of(new Random(9)));
		s.toggleStoryteller("Conteuse");
		for (int i = 1; i <= 6; i++) {
			s.join("J" + i);
		}
		s.start("Conteuse", Session.Mode.HUMAIN);
		String started = GameCard.joinRefusal(s, "Zoe", 12);
		assertTrue(started != null && started.contains("regarder"), started);
		assertNull(GameCard.joinRefusal(s, "J3", 12));
		assertEquals("joueur", GameCard.seat(s, "J3"));
		assertNull(GameCard.joinRefusal(s, "Conteuse", 12));
		assertEquals("conteur", GameCard.seat(s, "Conteuse"));
		s.suspend();
		String suspended = GameCard.joinRefusal(s, "J3", 12);
		assertTrue(suspended != null && suspended.contains("suspens"), "en suspens, même ses joueurs attendent la reprise");
	}

	@Test
	void regarderSansVoirLesRoles() {
		startA();
		a.setting("spectateurs_grimoire", "oui");
		// Un spectateur ordinaire voit le grimoire (réglage du Conteur) ; celui qui regarde depuis le hub, jamais.
		a.spectate("Curieux");
		assertNotNull(a.view("Curieux", false).get("grimoire"), "le réglage du Conteur vaut pour ses spectateurs");
		String said = a.watch("Zoe");
		assertTrue(said.contains("sans voir les rôles"), said);
		assertTrue(a.isWatcher("Zoe") && a.isSpectator("Zoe"));
		assertEquals("regarde", GameCard.seat(a, "Zoe"));
		Map<String, Object> view = a.view("Zoe", false);
		assertNull(view.get("grimoire"), "pas de grimoire pour qui regarde depuis le hub");
		assertEquals(true, view.get("watching"));
		assertEquals(true, view.get("spectator"));
		assertEquals(1, cardA(12, 9).watchers());
		assertEquals("1 spectateur", cardA(12, 9).watchersLine());
		assertEquals("Tu regardes déjà cette partie.", a.watch("Zoe"), "regarder deux fois ne change rien");

		// Elle s'en va : plus spectatrice, plus dans les gradins.
		assertTrue(a.stopWatching("Zoe", false));
		assertFalse(a.isWatcher("Zoe") || a.isSpectator("Zoe"));
		assertFalse(a.stopWatching("Zoe", false), "deux fois : rien");
		assertTrue(hostA.announces.stream().anyMatch(t -> t.contains("Zoe ne regarde plus")));
	}

	@Test
	void onNeRegardePasUnePartieOuLOnAPlace() {
		a.join("Alice");
		String inLobby = a.watchRefusal("Alice");
		assertTrue(inLobby != null && inLobby.contains("inscrit"), inLobby);
		assertThrows(GameException.class, () -> a.watch("Alice"));
		a.toggleStoryteller("Conteuse");
		assertNotNull(a.watchRefusal("Conteuse"));
		assertNull(a.watchRefusal("Zoe"));
	}

	@Test
	void regarderUneAutrePartieLiberesDeLaPremiere() {
		a.watch("Zoe");
		assertEquals(tableA, GameSeats.home(tables, "Zoe"), "elle est à la table de A, dans ses gradins");
		b.watch("Zoe");
		assertFalse(a.isWatcher("Zoe") || a.isSpectator("Zoe"), "elle a quitté les gradins de A");
		assertTrue(b.isWatcher("Zoe"));
		// S'inscrire ailleurs la libère aussi.
		a.join("Zoe");
		assertFalse(b.isWatcher("Zoe"));
	}

	@Test
	void unePartieEnCoursRetientLoinDesGradins() {
		startA();
		String held = GameSeats.heldElsewhere(tables, tableB, "J2");
		assertTrue(held != null && held.contains("Partie principale"), held);
		assertThrows(GameException.class, () -> b.watch("J2"), "l'admission le retient aussi");
		assertNull(GameSeats.heldElsewhere(tables, tableB, "Zoe"));
	}

	@Test
	void laMiseEnSuspensVideLesGradins() {
		startA();
		a.watch("Zoe");
		a.suspend();
		assertFalse(a.isWatcher("Zoe"), "ceux qui regardaient vont au hub et n'y sont plus spectateurs");
		assertFalse(a.isSpectator("Zoe"));
	}

	@Test
	void lesGradinsSontAPartAuVocal() {
		UUID player = UUID.randomUUID();
		UUID watcher = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		UUID atHub = UUID.randomUUID();
		VoiceZones zones = new VoiceZones(Map.of(player, "partie-principale", watcher, VoiceZones.gallery("partie-principale"),
			other, VoiceZones.gallery("partie-principale"), atHub, VoiceZones.OUTSIDE));
		assertFalse(zones.mayHear(player, watcher), "les gradins n'entendent pas les joueurs");
		assertFalse(zones.mayHear(watcher, player), "les joueurs n'entendent pas les gradins");
		assertTrue(zones.mayHear(watcher, other), "les spectateurs s'entendent entre eux");
		assertFalse(zones.mayHear(watcher, atHub), "ni le hub");
		assertNotEquals(VoiceZones.gallery("partie-principale"), VoiceZones.gallery("essai-du-mardi"));
	}
}
