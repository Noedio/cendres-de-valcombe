package fr.valcombe.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class AdminSettingsTest {
	private static AdminSettings.Change parse(String json) {
		return AdminSettings.parse(AdminJson.parseObject(json));
	}

	private static String refused(String json) {
		AdminException e = assertThrows(AdminException.class, () -> parse(json));
		assertEquals(400, e.status());
		return e.getMessage();
	}

	@Test
	void fullRequestIsRead() {
		AdminSettings.Change c = parse("{\"listeBlanche\":{\"active\":true,\"ajouter\":[\"Alice\",\"bob_2\"],\"retirer\":[\"Eve\"]},"
			+ "\"operateurs\":{\"ajouter\":[\"Alice\"],\"retirer\":[\"Mallory\"]},\"messageAccueil\":\"  Bienvenue au bourg \","
			+ "\"prochaineSoiree\":\"vendredi 21 h\",\"joueursMax\":16,\"port\":25600}");
		assertEquals(Boolean.TRUE, c.whitelistOn());
		assertEquals(List.of("Alice", "bob_2"), c.whitelistAdd());
		assertEquals(List.of("Eve"), c.whitelistRemove());
		assertEquals(List.of("Alice"), c.opsAdd());
		assertEquals(List.of("Mallory"), c.opsRemove());
		assertEquals("Bienvenue au bourg", c.welcome());
		assertEquals("vendredi 21 h", c.evening());
		assertEquals(16, c.maxPlayers());
		assertEquals(25600, c.port());
	}

	@Test
	void absentFieldsAreLeftAlone() {
		AdminSettings.Change c = parse("{\"joueursMax\":8}");
		assertNull(c.whitelistOn());
		assertTrue(c.whitelistAdd().isEmpty());
		assertNull(c.welcome());
		assertNull(c.port());
		assertEquals(8, c.maxPlayers());
		// Un texte vide est une demande : revenir au message par défaut.
		assertEquals("", parse("{\"messageAccueil\":\"\"}").welcome());
		assertFalse(AdminSettings.empty(c));
	}

	@Test
	void emptyRequestIsRefused() {
		assertTrue(refused("{}").contains("aucun réglage"));
		assertTrue(refused("{\"listeBlanche\":{}}").contains("aucun réglage"));
	}

	@Test
	void unknownFieldsAreRefused() {
		assertTrue(refused("{\"joueursMax\":8,\"difficulte\":\"dur\"}").contains("« difficulte »"));
		assertTrue(refused("{\"listeBlanche\":{\"vider\":true}}").contains("« vider »"));
		assertTrue(refused("{\"operateurs\":{\"active\":true}}").contains("« active »"));
	}

	@Test
	void namesAreChecked() {
		assertTrue(refused("{\"listeBlanche\":{\"ajouter\":[\"Al ice\"]}}").contains("« Al ice »"));
		assertTrue(refused("{\"listeBlanche\":{\"ajouter\":[\"Abcdefghijklmnopq\"]}}").contains("nom de joueur invalide"));
		assertTrue(refused("{\"listeBlanche\":{\"ajouter\":[\"\"]}}").contains("nom de joueur invalide"));
		assertTrue(refused("{\"operateurs\":{\"ajouter\":[\"a;op\"]}}").contains("nom de joueur invalide"));
		assertTrue(refused("{\"operateurs\":{\"ajouter\":[42]}}").contains("42"));
		assertTrue(refused("{\"operateurs\":{\"ajouter\":\"Alice\"}}").contains("liste de noms attendue"));
		assertTrue(AdminSettings.validName("Notch"));
		assertTrue(AdminSettings.validName("a_b_c_1234567890"));
		assertFalse(AdminSettings.validName("é"));
		assertFalse(AdminSettings.validName(null));
	}

	@Test
	void duplicatesAreMergedAndContradictionsRefused() {
		assertEquals(List.of("Alice"), parse("{\"listeBlanche\":{\"ajouter\":[\"Alice\",\"alice\",\"ALICE\"]}}").whitelistAdd());
		assertTrue(refused("{\"listeBlanche\":{\"ajouter\":[\"Alice\"],\"retirer\":[\"alice\"]}}").contains("à la fois ajouté et retiré"));
	}

	@Test
	void tooManyNamesAreRefused() {
		StringBuilder names = new StringBuilder();
		for (int i = 0; i <= AdminSettings.MAX_NAMES; i++) {
			names.append(i == 0 ? "" : ",").append("\"J").append(i).append("\"");
		}
		assertTrue(refused("{\"listeBlanche\":{\"ajouter\":[" + names + "]}}").contains("50 noms au plus"));
	}

	@Test
	void numbersMustBeWholeAndInRange() {
		assertTrue(refused("{\"joueursMax\":0}").contains("joueursMax"));
		assertTrue(refused("{\"joueursMax\":1001}").contains("joueursMax"));
		assertTrue(refused("{\"joueursMax\":12.5}").contains("joueursMax"));
		assertTrue(refused("{\"joueursMax\":\"12\"}").contains("joueursMax"));
		assertTrue(refused("{\"joueursMax\":1e400}").contains("joueursMax"));
		assertTrue(refused("{\"port\":80}").contains("port"));
		assertTrue(refused("{\"port\":70000}").contains("port"));
		assertEquals(1000, parse("{\"joueursMax\":1000}").maxPlayers());
		assertEquals(12, parse("{\"joueursMax\":12.0}").maxPlayers());
		assertEquals(1024, parse("{\"port\":1024}").port());
	}

	@Test
	void textsHoldOnOneShortLine() {
		assertTrue(refused("{\"messageAccueil\":\"" + "a".repeat(61) + "\"}").contains("60 caractères"));
		assertEquals("é".repeat(60), parse("{\"messageAccueil\":\"" + "é".repeat(60) + "\"}").welcome());
		assertTrue(refused("{\"messageAccueil\":\"ligne\\nautre\"}").contains("une seule ligne"));
		assertTrue(refused("{\"prochaineSoiree\":\"a\\u0000b\"}").contains("une seule ligne"));
		assertTrue(refused("{\"messageAccueil\":\"§cRouge\"}").contains("§"));
		assertTrue(refused("{\"messageAccueil\":12}").contains("texte attendu"));
		assertTrue(refused("{\"listeBlanche\":{\"active\":\"oui\"}}").contains("vrai ou faux"));
	}

	@Test
	void allFaultsAreListedTogether() {
		String msg = refused("{\"joueursMax\":-1,\"port\":1,\"messageAccueil\":\"a\\nb\",\"inconnu\":1}");
		assertTrue(msg.contains("joueursMax") && msg.contains("port") && msg.contains("messageAccueil") && msg.contains("« inconnu »"), msg);
	}
}
