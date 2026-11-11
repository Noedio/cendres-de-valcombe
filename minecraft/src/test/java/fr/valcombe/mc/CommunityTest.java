package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;

import fr.valcombe.rules.GameException;
import fr.valcombe.session.GameReport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommunityTest {
	@TempDir
	Path dir;

	private static GameReport report(String winner) {
		return new GameReport(winner, "Le démon est mort.", 3, "Valcombe (complet)", "Mira",
			List.of(
				new GameReport.Player("Alice", "Voyante", null, "GOOD", true, false),
				new GameReport.Player("Bob", "Démon", null, "EVIL", false, false),
				new GameReport.Player("Bot1", "Ivrogne", "Cuisinier", "GOOD", true, true)),
			List.of("Nuit 1"));
	}

	@Test
	void statsCountGamesWinsAndRolesButNotBots() throws Exception {
		Community c = new Community(dir);
		c.record(report("GOOD"));
		c.record(report("EVIL"));
		assertTrue(Files.exists(dir.resolve("valcombe-stats.json")));

		// Relecture depuis le fichier
		Community again = new Community(dir);
		List<String> alice = again.statsLines("Alice");
		assertTrue(alice.get(1).contains("Parties : 2, victoires : 1 (50 %)"), alice.toString());
		assertTrue(alice.get(3).contains("Voyante ×2"), alice.toString());
		assertTrue(again.statsLines("Bot1").get(0).contains("aucune partie"));
		assertTrue(again.statsLines("Mira").stream().anyMatch(l -> l.contains("Conteur : 2")));
		List<String> board = again.statsLines(null);
		assertEquals("Classement de Valcombe (victoires / parties) :", board.get(0));
		assertEquals(3, board.size()); // Alice et Bob ; Mira n'a pas joué
	}

	@Test
	void webhookMustBeDiscord() {
		Community c = new Community(dir);
		assertThrows(GameException.class, () -> c.setWebhook("https://exemple.org/hook"));
		assertFalse(c.hasWebhook());
		c.setWebhook("https://discord.com/api/webhooks/1/abc");
		assertTrue(c.hasWebhook());
		assertTrue(new Community(dir).hasWebhook());
		c.setWebhook("aucun");
		assertFalse(c.hasWebhook());
	}

	@Test
	void discordMessageListsRolesWithoutMentions() {
		JsonObject msg = Community.discordMessage(report("GOOD"));
		JsonObject embed = msg.getAsJsonArray("embeds").get(0).getAsJsonObject();
		assertEquals("Victoire du bien", embed.get("title").getAsString());
		String desc = embed.get("description").getAsString();
		assertTrue(desc.contains("**Bob** — Démon ✝"), desc);
		assertTrue(desc.contains("croyait être Cuisinier"), desc);
		assertEquals(0, msg.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
	}

	@Test
	void tabletPortIsChecked() {
		Community c = new Community(dir);
		assertEquals(25570, c.tabletPort());
		assertThrows(GameException.class, () -> c.setTabletPort(80));
		c.setTabletPort(9000);
		assertEquals(9000, new Community(dir).tabletPort());
	}
}
