package fr.valcombe.mc.games;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;
import fr.valcombe.session.Host;
import fr.valcombe.session.Msg;
import fr.valcombe.session.Session;
import fr.valcombe.session.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Un joueur n'est qu'à une table à la fois : s'inscrire ailleurs le libère de celle où il attendait, une partie en
 * cours qu'il joue ou qu'il conte le retient, et sa table d'attache est retrouvée où qu'il soit (voir GameSeats).
 */
class GameSeatsTest {
	/** Un hôte sans Minecraft : il note les annonces, et les tâches attendent qu'on les lance. */
	static final class QuietHost implements Host {
		final List<String> announces = new ArrayList<>();
		final List<Runnable> tasks = new ArrayList<>();

		@Override
		public boolean realTime() {
			return false;
		}

		@Override
		public void announce(String text) {
			announces.add(text);
		}

		@Override
		public void tell(String player, Msg msg) {
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
		public void nightEffects(List<String> players, boolean night, Settings settings) {
		}

		@Override
		public void endGame(List<String> players) {
		}
	}

	/** Deux tables reliées comme sur le serveur : chacune consulte GameSeats.admit avant d'asseoir quelqu'un. */
	private final QuietHost hostA = new QuietHost();
	private final QuietHost hostB = new QuietHost();
	private final Session a = new Session(hostA, Rng.of(new Random(1)));
	private final Session b = new Session(hostB, Rng.of(new Random(2)));
	private final GameSeats.Table tableA = new GameSeats.Table("partie-principale", "Partie principale", a);
	private final GameSeats.Table tableB = new GameSeats.Table("essai-du-mardi", "Essai du mardi", b);
	private final List<GameSeats.Table> tables = List.of(tableA, tableB);

	GameSeatsTest() {
		a.admission = p -> GameSeats.admit(tables, tableA, p);
		b.admission = p -> GameSeats.admit(tables, tableB, p);
	}

	@Test
	void sInscrireAilleursLibereDuPremierSalon() {
		a.join("Alice");
		assertEquals(tableA, GameSeats.home(tables, "Alice"));
		b.join("Alice");
		assertFalse(a.lobby().contains("Alice"), "Alice a quitté le salon de A");
		assertTrue(b.lobby().contains("Alice"));
		assertEquals(tableB, GameSeats.home(tables, "Alice"));
		assertTrue(hostA.announces.stream().anyMatch(t -> t.contains("Alice quitte la partie")), "A l'annonce à ses joueurs");
		assertTrue(hostB.announces.stream().noneMatch(t -> t.contains("quitte")), "B n'entend pas ce qui se passe dans A");
	}

	@Test
	void leConteurDUneTableEnAttenteDevientJoueurAilleurs() {
		a.toggleStoryteller("Conteuse");
		assertEquals(tableA, GameSeats.home(tables, "Conteuse"));
		b.join("Conteuse");
		assertNull(a.storyteller(), "A n'a plus de Conteur");
		assertTrue(b.lobby().contains("Conteuse"));
		// Et l'inverse : conter B libère du salon de A.
		a.join("Bruno");
		b.toggleStoryteller("Bruno");
		assertFalse(a.lobby().contains("Bruno"));
		assertEquals("Bruno", b.storyteller());
	}

	@Test
	void unePartieEnCoursRetientSesJoueursEtSonConteur() {
		a.toggleStoryteller("Conteuse");
		for (int i = 1; i <= 6; i++) {
			a.join("J" + i);
		}
		a.start("Conteuse", Session.Mode.HUMAIN);
		assertNotNull(a.game());
		assertTrue(a.holds("J1"));
		GameException refused = assertThrows(GameException.class, () -> b.join("J1"));
		assertTrue(refused.getMessage().contains("Partie principale"), refused.getMessage());
		assertThrows(GameException.class, () -> b.toggleStoryteller("Conteuse"), "le Conteur d'une partie en cours ne conte pas ailleurs");
		assertThrows(GameException.class, () -> b.spectate("J2"));
		assertTrue(b.lobby().isEmpty());
		assertEquals(tableA, GameSeats.home(tables, "J1"));
		assertEquals(tableA, GameSeats.home(tables, "Conteuse"));
		// Un joueur qui n'est à aucune table n'a pas d'attache, et peut s'inscrire dans B.
		assertNull(GameSeats.home(tables, "Zoé"));
		b.join("Zoé");
		assertEquals(tableB, GameSeats.home(tables, "Zoé"));
		// Fin de la partie de A : ses joueurs sont libres.
		a.stop();
		hostA.tasks.forEach(Runnable::run);
		assertFalse(a.holds("J1"));
		b.join("J1");
		assertTrue(b.lobby().contains("J1"));
	}

	@Test
	void lesJoueursFictifsNePassentPasParLAdmission() {
		a.addBots(3);
		b.addBots(3);
		assertEquals(3, a.lobby().size(), "Bot1 reste dans A même si B a aussi son Bot1");
		assertEquals(3, b.lobby().size());
	}

	@Test
	void sansAdmissionUneSessionSeule() {
		Session alone = new Session(new QuietHost(), Rng.of(new Random(3)));
		alone.join("Alice");
		assertTrue(alone.belongs("Alice"));
		assertFalse(alone.holds("Alice"));
	}
}
