package fr.valcombe.mc.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dernières positions des joueurs, par monde : table, oubli d'un monde, lecture et écriture de positions.json. */
class LastPositionsTest {
	@TempDir
	Path tmp;

	private static final UUID ALICE = UUID.fromString("0f2b8c1e-1111-4a2b-9c3d-000000000001");
	private static final UUID BOB = UUID.fromString("0f2b8c1e-2222-4a2b-9c3d-000000000002");

	private Path file() {
		return tmp.resolve("config").resolve("valcombe").resolve("positions.json");
	}

	private static LastPositions.Spot at(double x, double y, double z, float yaw, float pitch) {
		return new LastPositions.Spot(x, y, z, yaw, pitch);
	}

	@Test
	void fichierDansLaSauvegarde() {
		assertEquals(tmp.resolve("valcombe").resolve("positions.json"), LastPositions.fileIn(tmp));
	}

	@Test
	void chaqueJoueurAUnePositionParMonde() {
		LastPositions p = new LastPositions(file());
		p.put(ALICE, "Alice", "hub", at(0.5, 65, 8.5, 180f, 0f));
		p.put(ALICE, "Alice", LastPositions.MAIN, at(120.3, 70, -45.7, 90f, 12.5f));
		p.put(BOB, "Bob", "hub", at(3, 65, 2, -90f, 0f));
		assertEquals(at(0.5, 65, 8.5, 180f, 0f), p.get(ALICE, "hub"));
		assertEquals(at(120.3, 70, -45.7, 90f, 12.5f), p.get(ALICE, "principal"));
		assertEquals(at(3, 65, 2, -90f, 0f), p.get(BOB, "hub"));
		assertNull(p.get(BOB, "principal"), "jamais allé au village : pas de position");
		assertNull(p.get(UUID.randomUUID(), "hub"));
		assertEquals(List.of("hub", "principal"), p.worldsOf(ALICE), "dans l'ordre des premières visites");

		// Une nouvelle position remplace l'ancienne dans le même monde.
		p.put(ALICE, "Alice", "hub", at(-10.5, 65, 0.5, -90f, 5f));
		assertEquals(at(-10.5, 65, 0.5, -90f, 5f), p.get(ALICE, "hub"));
	}

	@Test
	void positionsAbimeesOuMondeInvalideIgnores() {
		LastPositions p = new LastPositions(file());
		p.put(ALICE, "Alice", "Monde Invalide", at(1, 65, 1, 0f, 0f));
		p.put(ALICE, "Alice", "hub", at(Double.NaN, 65, 1, 0f, 0f));
		p.put(ALICE, "Alice", "hub", at(1, Double.POSITIVE_INFINITY, 1, 0f, 0f));
		p.put(ALICE, "Alice", "hub", at(4.0E7, 65, 1, 0f, 0f));
		p.put(null, "Alice", "hub", at(1, 65, 1, 0f, 0f));
		p.put(ALICE, "Alice", "hub", null);
		assertTrue(p.worldsOf(ALICE).isEmpty());
		assertFalse(p.dirty(), "rien de noté, rien à écrire");
		assertFalse(LastPositions.validWorld(null));
		assertTrue(LastPositions.validWorld("principal"));
		assertTrue(LastPositions.validWorld("essai-2"));
	}

	@Test
	void oublierUnMondeRemisANeuf() {
		LastPositions p = new LastPositions(file());
		p.put(ALICE, "Alice", "essai", at(5, 66, 5, 0f, 0f));
		p.put(BOB, "Bob", "essai", at(6, 66, 6, 0f, 0f));
		p.put(BOB, "Bob", "hub", at(0.5, 65, 8.5, 180f, 0f));
		assertEquals(2, p.forgetWorld("essai"));
		assertNull(p.get(ALICE, "essai"));
		assertNull(p.get(BOB, "essai"));
		assertEquals(at(0.5, 65, 8.5, 180f, 0f), p.get(BOB, "hub"), "les autres mondes ne bougent pas");
		assertEquals(0, p.forgetWorld("essai"), "plus rien à oublier");
		p.forget(BOB, "hub");
		assertNull(p.get(BOB, "hub"));
	}

	@Test
	void ecritPuisRelit() throws Exception {
		LastPositions p = new LastPositions(file());
		p.put(ALICE, "Alice", "hub", at(0.5, 65, 8.5, 180f, 0f));
		p.put(ALICE, "Alice", LastPositions.MAIN, at(120.333, 70.0, -45.777, 90.25f, -12.5f));
		p.put(BOB, "Bob", "partie-du-jeudi", at(-3.5, 66, 12.5, 45f, 10f));
		assertTrue(p.dirty());
		assertTrue(p.save());
		assertFalse(p.dirty(), "écrit : plus rien en attente");
		assertTrue(Files.exists(file()));

		JsonObject root = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals(LastPositions.FORMAT, root.get("format").getAsInt());
		JsonObject alice = root.getAsJsonObject("joueurs").getAsJsonObject(ALICE.toString());
		assertEquals("Alice", alice.get("nom").getAsString(), "le nom à côté de l'UUID, pour qui lit le fichier");
		assertEquals(120.33, alice.getAsJsonObject("mondes").getAsJsonObject("principal").get("x").getAsDouble(), 1e-9,
			"au centimètre près");

		LastPositions back = new LastPositions(file());
		back.load();
		assertEquals(at(0.5, 65, 8.5, 180f, 0f), back.get(ALICE, "hub"));
		LastPositions.Spot main = back.get(ALICE, "principal");
		assertEquals(120.33, main.x(), 1e-9);
		assertEquals(-45.78, main.z(), 1e-9);
		assertEquals(90.25f, main.yaw(), 1e-4);
		assertEquals(-12.5f, main.pitch(), 1e-4);
		assertEquals(at(-3.5, 66, 12.5, 45f, 10f), back.get(BOB, "partie-du-jeudi"));
		assertFalse(back.dirty());

		// Une même position notée de nouveau ne demande pas d'écriture.
		back.put(BOB, "Bob", "partie-du-jeudi", at(-3.5, 66, 12.5, 45f, 10f));
		assertFalse(back.dirty());
		back.put(BOB, "Bob", "partie-du-jeudi", at(-2.5, 66, 12.5, 45f, 10f));
		assertTrue(back.dirty());
	}

	@Test
	void fichierAbsentOuIllisible() throws Exception {
		LastPositions absent = new LastPositions(file());
		absent.load();
		assertTrue(absent.worldsOf(ALICE).isEmpty(), "pas de fichier : table vide");

		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{ ceci n'est pas du JSON", StandardCharsets.UTF_8);
		LastPositions broken = new LastPositions(file());
		broken.load();
		assertTrue(broken.worldsOf(ALICE).isEmpty());
		assertTrue(Files.exists(file().resolveSibling("positions.json.illisible")), "fichier illisible mis de côté");

		// Une entrée abîmée n'empêche pas de lire les autres.
		Files.writeString(file(), "{\"format\":1,\"joueurs\":{"
			+ "\"pas-un-uuid\":{\"mondes\":{\"hub\":{\"x\":1,\"y\":65,\"z\":1}}},"
			+ "\"" + BOB + "\":{\"nom\":\"Bob\",\"mondes\":{\"hub\":{\"x\":2,\"y\":65,\"z\":3,\"lacet\":90},"
			+ "\"Pas Valide\":{\"x\":1,\"y\":65,\"z\":1},\"essai\":{\"x\":\"oups\"}}}}}", StandardCharsets.UTF_8);
		LastPositions partial = new LastPositions(file());
		partial.load();
		assertEquals(at(2, 65, 3, 90f, 0f), partial.get(BOB, "hub"));
		assertEquals(List.of("hub"), partial.worldsOf(BOB));
	}
}
