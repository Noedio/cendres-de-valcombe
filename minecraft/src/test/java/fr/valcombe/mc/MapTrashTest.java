package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.GameException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * La corbeille de la bibliothèque de cartes : une carte supprimée y part avec ses fichiers et ses versions, revient
 * telle quelle (sous un autre identifiant si le sien a été repris), et part pour de bon au bout de 30 jours.
 */
class MapTrashTest {
	@TempDir
	Path lib;

	/** Horloge réglable : on la fait avancer à la main. */
	private static final class Manual extends Clock {
		Instant now = Instant.parse("2026-11-13T09:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private void write(String id, String nom, int seats, int marker) throws Exception {
		StringBuilder s = new StringBuilder("{\"id\":\"" + id + "\",\"nom\":\"" + nom + "\",\"rayon\":12,\"sieges\":[");
		for (int i = 0; i < seats; i++) {
			s.append(i == 0 ? "" : ",").append("[").append(i).append(",1,0]");
		}
		s.append("],\"logis\":[[0,1,5],[2,1,5]]}");
		Files.writeString(lib.resolve(id + ".json"), s.toString(), StandardCharsets.UTF_8);
		Files.write(lib.resolve(id + ".nbt"), new byte[] {1, 2, (byte) marker});
		Files.write(lib.resolve(id + ".png"), new byte[] {9, (byte) marker});
	}

	@Test
	void aDeletedMapGoesToTheBinWithItsVersionsAndComesBack() throws Exception {
		Manual clock = new Manual();
		MapVersions versions = new MapVersions(lib, clock);
		MapTrash trash = new MapTrash(lib, versions, clock);
		write("port", "Port de Valcombe", 6, 7);
		versions.record("port", MapVersions.Kind.MANUELLE);
		assertEquals(1, versions.list("port").size());

		MapTrash.Entry e = trash.put("port");
		assertFalse(Files.exists(lib.resolve("port.json")), "la fiche a quitté la bibliothèque");
		assertFalse(Files.exists(lib.resolve("port.nbt")));
		assertFalse(Files.exists(lib.resolve("port.png")));
		assertTrue(versions.list("port").isEmpty(), "ses versions l'ont suivie");
		assertEquals("port", e.mapId());
		assertEquals("Port de Valcombe", e.nom());
		assertEquals(6, e.seats());
		assertEquals(2, e.houses());
		assertTrue(e.id().startsWith("port_"));
		assertEquals(List.of(e), trash.list());

		String back = trash.restore(e.id(), id -> Files.exists(lib.resolve(id + ".json")), id -> id + "-2");
		assertEquals("port", back);
		assertArrayEquals(new byte[] {1, 2, 7}, Files.readAllBytes(lib.resolve("port.nbt")));
		assertArrayEquals(new byte[] {9, 7}, Files.readAllBytes(lib.resolve("port.png")));
		assertTrue(Files.readString(lib.resolve("port.json")).contains("Port de Valcombe"));
		assertEquals(1, versions.list("port").size(), "ses versions sont revenues");
		assertTrue(trash.list().isEmpty(), "la corbeille est vide");
	}

	@Test
	void aRestoredMapWhoseIdWasTakenGetsAnotherOne() throws Exception {
		Manual clock = new Manual();
		MapVersions versions = new MapVersions(lib, clock);
		MapTrash trash = new MapTrash(lib, versions, clock);
		write("port", "Port", 5, 1);
		MapTrash.Entry e = trash.put("port");
		write("port", "Autre port", 8, 2); // l'identifiant est repris entre-temps
		String back = trash.restore(e.id(), id -> Files.exists(lib.resolve(id + ".json")), id -> id + "-copie");
		assertEquals("port-copie", back);
		String json = Files.readString(lib.resolve("port-copie.json"));
		assertTrue(json.contains("\"id\": \"port-copie\""), json);
		assertTrue(json.contains("\"Port\""));
		assertArrayEquals(new byte[] {1, 2, 2}, Files.readAllBytes(lib.resolve("port.nbt")), "l'autre carte n'est pas touchée");
		assertArrayEquals(new byte[] {1, 2, 1}, Files.readAllBytes(lib.resolve("port-copie.nbt")));
	}

	@Test
	void twoDeletionsOfTheSameIdKeepBothAndTheOldOnesExpire() throws Exception {
		Manual clock = new Manual();
		MapVersions versions = new MapVersions(lib, clock);
		MapTrash trash = new MapTrash(lib, versions, clock);
		write("port", "Port 1", 5, 1);
		MapTrash.Entry first = trash.put("port");
		write("port", "Port 2", 5, 2);
		MapTrash.Entry second = trash.put("port"); // la même seconde : un numéro les distingue
		assertTrue(!first.id().equals(second.id()));
		assertEquals(2, trash.list().size());
		assertEquals(second.id(), trash.list().get(0).id(), "la plus récente d'abord");

		clock.now = clock.now.plus(Duration.ofDays(MapTrash.KEEP_DAYS + 1));
		write("autre", "Autre", 5, 3);
		trash.put("autre"); // jeter une carte fait le ménage
		List<MapTrash.Entry> left = trash.list();
		assertEquals(1, left.size());
		assertEquals("autre", left.get(0).mapId());
	}

	@Test
	void refusesWhatIsNotThere() throws Exception {
		MapTrash trash = new MapTrash(lib, new MapVersions(lib), Clock.systemUTC());
		assertThrows(GameException.class, () -> trash.put("absente"));
		assertThrows(GameException.class, () -> trash.put("../evasion"));
		assertThrows(GameException.class, () -> trash.restore("../../x_20261113-090000", id -> false, id -> id));
		assertThrows(GameException.class, () -> trash.restore("port_20261113-090000", id -> false, id -> id));
	}
}
