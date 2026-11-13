package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

/** La sauvegarde de partie porte le monde où elle se joue ; une ancienne sauvegarde (sans monde) ne reprend pas. */
class GameSaveTest {
	private static final String TABLE = "{\"mode\":\"HUMAIN\",\"storyteller\":\"Alice\",\"lobby\":[\"Bob\",\"Chloé\"],"
		+ "\"game\":\"{\\\"jour\\\":2}\",\"settings\":{\"nightFreeze\":true,\"clockTicks\":12}}";

	@Test
	void leMondeEstEcritEtLaPartieReprendDansCeMonde() {
		String saved = GameSave.withWorld(TABLE, "partie-principale");
		assertEquals("partie-principale", JsonParser.parseString(saved).getAsJsonObject().get(GameSave.WORLD).getAsString());
		GameSave.Read back = GameSave.read(saved, "partie-principale");
		assertEquals(GameSave.Verdict.OK, back.verdict());
		assertEquals("partie-principale", back.world());
		JsonObject table = JsonParser.parseString(back.json()).getAsJsonObject();
		assertFalse(table.has(GameSave.WORLD), "la session reçoit l'état de la table, sans le champ du monde");
		assertEquals(JsonParser.parseString(TABLE), table, "l'état de la table revient tel quel");
	}

	@Test
	void uneAncienneSauvegardeSansMondeNeReprendPas() {
		GameSave.Read old = GameSave.read(TABLE, "partie-principale");
		assertEquals(GameSave.Verdict.NO_WORLD, old.verdict());
		assertNull(old.json());
		assertEquals(GameSave.Verdict.NO_WORLD, GameSave.read("{\"monde\":\"  \",\"mode\":\"AUTO\"}", "partie-principale").verdict(),
			"un monde vide ne compte pas");
	}

	@Test
	void unePartieDUnAutreMondeNeReprendPasIci() {
		GameSave.Read other = GameSave.read(GameSave.withWorld(TABLE, "essai-du-soir"), "partie-principale");
		assertEquals(GameSave.Verdict.OTHER_WORLD, other.verdict());
		assertEquals("essai-du-soir", other.world());
		assertNull(other.json());
	}

	@Test
	void unTexteIllisibleEstReconnu() {
		assertEquals(GameSave.Verdict.UNREADABLE, GameSave.read("{tronqué", "partie-principale").verdict());
		assertEquals(GameSave.Verdict.UNREADABLE, GameSave.read("[1, 2]", "partie-principale").verdict());
		assertEquals(GameSave.Verdict.UNREADABLE, GameSave.read("", "partie-principale").verdict());
	}

	@Test
	void reecrireRemplaceLeMondeSansLeDoubler() {
		String once = GameSave.withWorld(TABLE, "partie-principale");
		String twice = GameSave.withWorld(once, "partie-principale-2");
		JsonObject o = JsonParser.parseString(twice).getAsJsonObject();
		assertEquals("partie-principale-2", o.get(GameSave.WORLD).getAsString());
		assertTrue(o.has("lobby"));
		assertFalse(JsonParser.parseString(GameSave.withWorld(TABLE, null)).getAsJsonObject().has(GameSave.WORLD),
			"sans monde, rien n'est ajouté");
	}
}
