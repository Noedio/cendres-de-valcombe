package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Versions des cartes de l'atelier carte : chaque enregistrement garde une copie de la carte, les versions automatiques
 * sont en nombre borné (sans jamais emporter une version demandée), et « Revenir à la version du ... » remet la carte
 * telle qu'elle était dans la bibliothèque.
 */
class MapVersionsTest {
	@TempDir
	Path lib;

	/** Horloge qui avance d'une minute à chaque lecture : chaque version a sa propre date. */
	private static final class Ticking extends Clock {
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
			now = now.plus(Duration.ofMinutes(1));
			return now;
		}
	}

	private MapVersions versions() {
		return new MapVersions(lib, new Ticking());
	}

	/** Écrit la carte id dans la bibliothèque : sa fiche (n trônes), ses blocs (un octet qui les distingue), un aperçu. */
	private void write(String id, int seats, int marker) throws Exception {
		StringBuilder s = new StringBuilder("{\"id\":\"" + id + "\",\"nom\":\"Carte " + marker + "\",\"rayon\":12,\"sieges\":[");
		for (int i = 0; i < seats; i++) {
			s.append(i == 0 ? "" : ",").append("[").append(i).append(",1,0]");
		}
		s.append("],\"logis\":[[0,1,5]],\"convocation\":[3,1,3]}");
		Files.writeString(lib.resolve(id + ".json"), s.toString(), StandardCharsets.UTF_8);
		Files.write(lib.resolve(id + ".nbt"), new byte[] {1, 2, (byte) marker});
		Files.write(lib.resolve(id + ".png"), new byte[] {9, (byte) marker});
	}

	@Test
	void recordKeepsACopyWithItsCounts() throws Exception {
		MapVersions v = versions();
		write("port", 6, 1);
		MapVersions.Version first = v.record("port", MapVersions.Kind.MANUELLE);
		assertEquals(6, first.seats());
		assertEquals(1, first.houses());
		assertTrue(first.room());
		assertEquals("Carte 1", first.nom());
		Path dir = lib.resolve("versions").resolve("port").resolve(first.id());
		assertArrayEquals(new byte[] {1, 2, 1}, Files.readAllBytes(dir.resolve("carte.nbt")));
		assertTrue(Files.exists(dir.resolve("carte.png")));
		assertTrue(first.describe().startsWith("version du "), first.describe());
		assertTrue(first.describe().contains("(enregistrée), 6 trônes, 1 maison"), first.describe());

		write("port", 8, 2);
		MapVersions.Version second = v.record("port", MapVersions.Kind.AUTO);
		List<MapVersions.Version> list = v.list("port");
		assertEquals(2, list.size());
		assertEquals(second.id(), list.get(0).id(), "la plus récente d'abord");
		assertEquals(second.id(), v.last("port").id());
		assertEquals(first.id(), v.find("port", first.id()).id());
		assertNull(v.find("port", "20000101-000000-000"));
		// Relues d'un nouveau gestionnaire (après un redémarrage) : les mêmes.
		assertEquals(2, new MapVersions(lib).list("port").size());
	}

	@Test
	void automaticVersionsAreBoundedWithoutTouchingRequestedOnes() throws Exception {
		MapVersions v = versions();
		List<String> manual = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			write("port", 5 + i, i);
			manual.add(v.record("port", MapVersions.Kind.MANUELLE).id());
		}
		// Une longue séance : bien plus de versions automatiques que ce qu'on en garde.
		List<String> auto = new ArrayList<>();
		for (int i = 0; i < MapVersions.KEEP_AUTO + 5; i++) {
			write("port", 5, 10 + i);
			auto.add(v.record("port", i % 3 == 0 ? MapVersions.Kind.ARRET : MapVersions.Kind.AUTO).id());
		}
		List<MapVersions.Version> list = v.list("port");
		assertEquals(MapVersions.KEEP_AUTO + 3, list.size());
		long autoKept = list.stream().filter(x -> x.kind().automatic()).count();
		assertEquals(MapVersions.KEEP_AUTO, autoKept);
		for (String id : manual) {
			assertTrue(list.stream().anyMatch(x -> x.id().equals(id)), "une version demandée n'est jamais emportée : " + id);
		}
		// Ce sont les plus anciennes automatiques qui sont parties, dossiers compris.
		for (int i = 0; i < 5; i++) {
			String gone = auto.get(i);
			assertFalse(list.stream().anyMatch(x -> x.id().equals(gone)), "version automatique ancienne effacée : " + gone);
			assertFalse(Files.exists(lib.resolve("versions").resolve("port").resolve(gone)));
		}
		assertEquals(auto.get(auto.size() - 1), list.get(0).id());
	}

	@Test
	void staleCountsBothGroupsApart() {
		List<MapVersions.Version> all = new ArrayList<>();
		Instant t = Instant.parse("2026-11-13T09:00:00Z");
		for (int i = 0; i < 6; i++) {
			all.add(new MapVersions.Version("a" + i, t.plusSeconds(i * 60L), MapVersions.Kind.AUTO, "", 5, 0, false));
			all.add(new MapVersions.Version("m" + i, t.plusSeconds(i * 60L + 30), MapVersions.Kind.FERMETURE, "", 5, 0, false));
		}
		List<String> gone = MapVersions.stale(all, 2, 4).stream().map(MapVersions.Version::id).sorted().toList();
		assertEquals(List.of("a0", "a1", "a2", "a3", "m0", "m1"), gone);
		assertTrue(MapVersions.stale(all, 10, 10).isEmpty());
	}

	@Test
	void restorePutsTheVersionBackIntoTheLibrary() throws Exception {
		MapVersions v = versions();
		write("port", 7, 1);
		MapVersions.Version old = v.record("port", MapVersions.Kind.MANUELLE);
		write("port", 9, 2);
		v.record("port", MapVersions.Kind.AUTO);
		Files.delete(lib.resolve("port.png")); // la carte présente n'a plus d'aperçu ; la version en a un
		MapVersions.Version back = v.restore("port", old.id());
		assertEquals(old.id(), back.id());
		assertArrayEquals(new byte[] {1, 2, 1}, Files.readAllBytes(lib.resolve("port.nbt")));
		assertArrayEquals(new byte[] {9, 1}, Files.readAllBytes(lib.resolve("port.png")));
		assertTrue(Files.readString(lib.resolve("port.json")).contains("\"Carte 1\""));
		assertEquals(2, v.list("port").size(), "la version reste gardée après le retour");
		GameException missing = assertThrows(GameException.class, () -> v.restore("port", "19990101-000000-000"));
		assertTrue(missing.getMessage().contains("Aucune version"), missing.getMessage());
	}

	@Test
	void refusesUnknownMapsAndForeignPaths() throws Exception {
		MapVersions v = versions();
		assertThrows(GameException.class, () -> v.record("absente", MapVersions.Kind.AUTO), "pas de version d'une carte absente");
		assertThrows(GameException.class, () -> v.list("../evasion"), "jamais de chemin fabriqué avec un identifiant libre");
		assertThrows(GameException.class, () -> v.record("A B", MapVersions.Kind.AUTO));
		write("port", 5, 1);
		v.record("port", MapVersions.Kind.AUTO);
		v.forget("port");
		assertTrue(v.list("port").isEmpty());
		assertFalse(Files.exists(lib.resolve("versions").resolve("port")));
	}

	@Test
	void twoVersionsInTheSameMillisecondStayApart() throws Exception {
		Clock fixed = Clock.fixed(Instant.parse("2026-11-13T09:00:00Z"), ZoneOffset.UTC);
		MapVersions v = new MapVersions(lib, fixed);
		write("port", 5, 1);
		String a = v.record("port", MapVersions.Kind.AUTO).id();
		String b = v.record("port", MapVersions.Kind.MANUELLE).id();
		assertNotEquals(a, b);
		assertEquals(2, v.list("port").size());
	}
}
