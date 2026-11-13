package fr.valcombe.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.valcombe.admin.AdminException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupSettingsTest {
	@TempDir
	Path dir;

	private static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void defaultsAreEverySixHoursAndAtStopKeepingTen() throws Exception {
		BackupSettings s = new BackupSettings(dir);
		BackupSettings.Values v = s.load();
		assertEquals(BackupSettings.DEFAULTS, v);
		assertTrue(v.actives());
		assertEquals(360, v.intervalleMinutes());
		assertTrue(v.aLArret());
		assertEquals(10, v.garder());
		assertFalse(v.pendantPartie());
		// Le fichier est écrit au premier démarrage, avec les noms de champs du contrat.
		JsonObject f = json(Files.readString(dir.resolve(BackupSettings.FILE_NAME), StandardCharsets.UTF_8));
		assertEquals(360, f.get("intervalleMinutes").getAsInt());
		assertTrue(f.get("aLArret").getAsBoolean());
	}

	@Test
	void theFileIsReadWithIndulgence() throws Exception {
		Files.writeString(dir.resolve(BackupSettings.FILE_NAME),
			"{\"actives\": false, \"intervalleMinutes\": 5, \"garder\": 100000, \"pendantPartie\": \"oui\", \"inconnu\": 1}");
		BackupSettings.Values v = new BackupSettings(dir).load();
		assertFalse(v.actives());
		assertEquals(BackupSettings.MIN_INTERVAL, v.intervalleMinutes(), "ramené à la borne");
		assertEquals(BackupSettings.MAX_KEEP, v.garder(), "ramené à la borne");
		assertFalse(v.pendantPartie(), "mauvais type : valeur par défaut");
		assertTrue(v.aLArret(), "absent : valeur par défaut");
		// Un fichier abîmé : la copie de secours, sinon les réglages par défaut.
		Files.writeString(dir.resolve(BackupSettings.FILE_NAME), "{pas du json");
		BackupSettings.Values again = new BackupSettings(dir).load();
		assertFalse(again.actives(), "copie de secours (.bak) relue");
	}

	@Test
	void changesAreValidatedStrictlyThenSaved() throws Exception {
		BackupSettings s = new BackupSettings(dir);
		s.load();
		BackupSettings.Change c = BackupSettings.parse(json("{\"intervalleMinutes\": 120, \"garder\": 3, \"pendantPartie\": true}"));
		assertNull(c.actives());
		BackupSettings.Values v = s.apply(c);
		assertEquals(120, v.intervalleMinutes());
		assertEquals(3, v.garder());
		assertTrue(v.pendantPartie());
		assertTrue(v.actives(), "non demandé : inchangé");
		assertEquals(v, new BackupSettings(dir).load(), "enregistré");

		AdminException e = assertThrows(AdminException.class, () -> BackupSettings.parse(json(
			"{\"intervalleMinutes\": 1, \"garder\": 2.5, \"actives\": \"oui\", \"dossier\": \"/tmp\"}")));
		assertEquals(400, e.status());
		for (String part : new String[] {"intervalleMinutes", "garder", "actives", "dossier"}) {
			assertTrue(e.getMessage().contains(part), e.getMessage());
		}
		assertThrows(AdminException.class, () -> BackupSettings.parse(json("{}")), "rien à changer");
		assertThrows(AdminException.class, () -> BackupSettings.parse(json("{\"garder\": 0}")));
		assertThrows(AdminException.class, () -> BackupSettings.parse(json("{\"intervalleMinutes\": 20000}")));
		assertEquals(500, BackupSettings.parse(json("{\"garder\": 500}")).garder());
	}
}
