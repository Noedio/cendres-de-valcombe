package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.backup.BackupContents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Une fiche par partie : {@code config/valcombe/parties/<monde>.json}, écrite sans jamais être laissée à moitié, relue
 * dans sa copie de secours si besoin, et retrouvée au démarrage pour chaque partie enregistrée. L'ancienne sauvegarde
 * unique ({@code config/valcombe-partie.json}) est mise de côté, jamais reprise ni effacée.
 */
class GameFilesTest {
	private static final String TABLE = "{\"mode\":\"AUTO\",\"lobby\":[\"Alba\"],\"game\":\"{}\",\"suspended\":true}";

	@Test
	void chaquePartieASaFiche(@TempDir Path config) {
		Path main = GameSave.file(config, "partie-principale");
		Path essai = GameSave.file(config, "essai-du-mardi");
		assertEquals(config.resolve("valcombe").resolve("parties").resolve("partie-principale.json"), main);
		assertNotEquals(main, essai, "deux parties ne partagent jamais une fiche");
		assertEquals(main.getParent(), essai.getParent());
		assertThrows(IllegalArgumentException.class, () -> GameSave.file(config, "../valcombe-partie"),
			"un identifiant ne sort jamais du dossier des fiches");
		assertThrows(IllegalArgumentException.class, () -> GameSave.file(config, null));
		assertTrue(BackupContents.managed("config/valcombe/parties/partie-principale.json"), "les fiches sont dans les sauvegardes");
	}

	@Test
	void fichesEcritesSuresEtRetrouveesAuDemarrage(@TempDir Path config) throws IOException {
		assertEquals(List.of(), GameSave.savedWorlds(config), "aucun dossier : aucune fiche");
		Path main = GameSave.file(config, "partie-principale");
		Path essai = GameSave.file(config, "essai-du-mardi");
		SafeFile.write(main, GameSave.withWorld(TABLE, "partie-principale"));
		SafeFile.write(essai, GameSave.withWorld(TABLE, "essai-du-mardi"));
		SafeFile.write(essai, GameSave.withWorld(TABLE, "essai-du-mardi")); // la précédente reste en copie de secours
		assertTrue(Files.exists(SafeFile.backup(essai)));
		Files.writeString(main.resolveSibling("notes.txt"), "rien à voir");
		assertEquals(List.of("essai-du-mardi", "partie-principale"), GameSave.savedWorlds(config));

		// Une fiche abîmée se relit dans sa copie de secours : la partie reprend quand même.
		Files.writeString(essai, "{tronqu", StandardCharsets.UTF_8);
		String back = SafeFile.read(essai, t -> GameSave.read(t, "essai-du-mardi").verdict() != GameSave.Verdict.UNREADABLE);
		assertEquals(GameSave.Verdict.OK, GameSave.read(back, "essai-du-mardi").verdict());
		assertTrue(GameSave.read(back, "essai-du-mardi").json().contains("\"suspended\":true"), "en suspens, elle le reste");

		// Une partie finie efface sa fiche et sa copie : elle ne reprendra pas.
		SafeFile.delete(essai);
		assertEquals(List.of("partie-principale"), GameSave.savedWorlds(config));
	}

	@Test
	void lAncienneSauvegardeUniqueEstMiseDeCote(@TempDir Path config) throws IOException {
		assertNull(GameSave.retireLegacy(config), "rien à mettre de côté");
		Path old = config.resolve(GameSave.LEGACY);
		Files.writeString(old, GameSave.withWorld(TABLE, "partie-principale"), StandardCharsets.UTF_8);
		Files.writeString(config.resolve(GameSave.LEGACY + ".tmp"), "x");
		String said = GameSave.retireLegacy(config);
		assertTrue(said != null && said.contains("ignorée") && said.contains("valcombe/parties"), said);
		assertFalse(Files.exists(old), "elle ne sera plus lue");
		assertTrue(Files.exists(config.resolve(GameSave.LEGACY + GameSave.ASIDE)), "gardée de côté, jamais effacée");
		assertFalse(Files.exists(config.resolve(GameSave.LEGACY + ".tmp")));
		assertEquals(List.of(), GameSave.savedWorlds(config), "elle ne devient la fiche d'aucune partie");
		assertNull(GameSave.retireLegacy(config), "une seule fois");
	}
}
