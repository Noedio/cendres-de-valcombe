package fr.valcombe.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminKeyTest {
	@TempDir
	Path dir;

	private JsonObject file() throws Exception {
		return JsonParser.parseString(Files.readString(dir.resolve(AdminKey.FILE_NAME), StandardCharsets.UTF_8)).getAsJsonObject();
	}

	@Test
	void firstStartDrawsAKeyAndKeepsIt() throws Exception {
		AdminKey k = new AdminKey(dir);
		assertTrue(k.load(), "clé tirée au premier démarrage");
		String key = k.key();
		assertTrue(key.matches("[0-9a-f]{64}"), key); // 32 octets
		assertTrue(k.localOnly());
		assertTrue(k.enabled());
		JsonObject f = file();
		assertEquals(key, f.get("key").getAsString());
		assertTrue(f.get("adminLocalOnly").getAsBoolean());
		assertTrue(f.get("enabled").getAsBoolean());
		Instant.parse(f.get("created").getAsString());

		AdminKey again = new AdminKey(dir);
		assertFalse(again.load(), "pas de nouvelle clé au démarrage suivant");
		assertEquals(key, again.key());
	}

	@Test
	void keysAreRandom() {
		SecureRandom r = new SecureRandom();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			assertTrue(seen.add(AdminKey.generate(r)));
		}
	}

	@Test
	void newKeyReplacesTheOldOne() throws Exception {
		AdminKey k = new AdminKey(dir);
		k.load();
		String old = k.key();
		String fresh = k.regenerate();
		assertNotEquals(old, fresh);
		assertEquals(fresh, k.key());
		assertEquals(fresh, file().get("key").getAsString());
		AdminKey reread = new AdminKey(dir);
		reread.load();
		assertEquals(fresh, reread.key());
	}

	@Test
	void handWrittenOptionsAreKeptAndMissingOnesDefaultToSafe() throws Exception {
		Files.writeString(dir.resolve(AdminKey.FILE_NAME), "{\"key\": \"ma-cle-ecrite-a-la-main-assez-longue-123\", \"adminLocalOnly\": false}");
		AdminKey k = new AdminKey(dir);
		assertFalse(k.load());
		assertEquals("ma-cle-ecrite-a-la-main-assez-longue-123", k.key());
		assertFalse(k.localOnly());
		assertTrue(k.enabled());
		Files.writeString(dir.resolve(AdminKey.FILE_NAME), "{\"key\": \"0123456789abcdef0123456789abcdef\", \"enabled\": false}");
		AdminKey off = new AdminKey(dir);
		off.load();
		assertFalse(off.enabled());
		assertTrue(off.localOnly());
	}

	@Test
	void weakOrMalformedKeysAreReplaced() throws Exception {
		for (String bad : new String[] {"courte", "", "avec des espaces dedans mais assez longue", "é".repeat(40)}) {
			Files.writeString(dir.resolve(AdminKey.FILE_NAME), "{\"key\": \"" + bad + "\", \"adminLocalOnly\": false}", StandardCharsets.UTF_8);
			AdminKey k = new AdminKey(dir);
			assertTrue(k.load(), "clé « " + bad + " » remplacée");
			assertTrue(k.key().matches("[0-9a-f]{64}"));
			assertFalse(k.localOnly(), "les autres réglages sont gardés");
		}
	}

	@Test
	void unreadableFileIsSetAsideAndReplaced() throws Exception {
		Files.writeString(dir.resolve(AdminKey.FILE_NAME), "{ ceci n'est pas du JSON");
		AdminKey k = new AdminKey(dir);
		assertTrue(k.load());
		assertTrue(Files.exists(dir.resolve(AdminKey.FILE_NAME + ".illisible")));
		assertTrue(k.localOnly());
		assertEquals(k.key(), file().get("key").getAsString());
	}

	@Test
	void restartFlagIsPutAndCleared() throws Exception {
		assertFalse(RestartFlag.present(dir));
		assertFalse(RestartFlag.clear(dir));
		RestartFlag.write(dir.resolve("sous-dossier"), "API \"d'administration\"\n(127.0.0.1)", Instant.parse("2026-11-13T10:00:00Z"));
		Path sub = dir.resolve("sous-dossier");
		assertTrue(RestartFlag.present(sub));
		JsonObject o = JsonParser.parseString(Files.readString(RestartFlag.file(sub))).getAsJsonObject();
		assertEquals("2026-11-13T10:00:00Z", o.get("demandeLe").getAsString());
		assertEquals("API \"d'administration\" (127.0.0.1)", o.get("motif").getAsString());
		assertEquals("redemarrer", RestartFlag.file(sub).getFileName().toString());
		assertTrue(RestartFlag.clear(sub));
		assertFalse(RestartFlag.present(sub));
	}
}
