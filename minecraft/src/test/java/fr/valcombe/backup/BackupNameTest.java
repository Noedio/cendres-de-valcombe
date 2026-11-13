package fr.valcombe.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class BackupNameTest {
	private static final LocalDateTime T = LocalDateTime.of(2026, 11, 13, 9, 12, 44, 987_000_000);

	@Test
	void namesAreTimestampedAndCarryTheirKind() {
		assertEquals("valcombe-2026-11-13_09-12-44-auto.zip", new BackupName(T, BackupKind.AUTO, 1).fileName());
		assertEquals("valcombe-2026-11-13_09-12-44-arret.zip", new BackupName(T, BackupKind.ARRET, 1).fileName());
		assertEquals("valcombe-2026-11-13_09-12-44-manuelle-2.zip", new BackupName(T, BackupKind.MANUELLE, 2).fileName());
		assertEquals("valcombe-2026-11-13_09-12-44-avant-restauration.zip",
			new BackupName(T, BackupKind.AVANT_RESTAURATION, 1).fileName());
	}

	@Test
	void parseIsTheInverseOfFileName() {
		for (BackupKind k : BackupKind.values()) {
			for (int seq : new int[] {1, 2, 9, 10, 999}) {
				BackupName n = new BackupName(T, k, seq);
				BackupName back = BackupName.parse(n.fileName()).orElseThrow();
				assertEquals(n, back, n.fileName());
				assertEquals(T.withNano(0), back.time());
			}
		}
	}

	@Test
	void anythingElseIsRefused() {
		for (String bad : new String[] {
			null, "", "valcombe.zip", "valcombe-2026-11-13_09-12-44-auto.tar.gz", "valcombe-2026-11-13_09-12-44-auto.zip.partiel",
			"valcombe-2026-11-13_09-12-44-hebdo.zip", "valcombe-2026-11-13_09-12-44-auto-1.zip", "valcombe-2026-11-13_09-12-44-auto-01.zip",
			"valcombe-2026-11-13_09-12-44-auto-1000.zip", "../valcombe-2026-11-13_09-12-44-auto.zip",
			"backups/valcombe-2026-11-13_09-12-44-auto.zip", "valcombe-2026-11-13_09-12-44-auto.zip/..",
			"VALCOMBE-2026-11-13_09-12-44-AUTO.ZIP", "valcombe-2026-13-01_09-12-44-auto.zip", "valcombe-2026-02-30_09-12-44-auto.zip",
			"valcombe-2026-11-13_24-00-00-auto.zip", "valcombe-2026-11-13 09-12-44-auto.zip", "valcombe-2026-11-13_09-12-44-auto.zip\n"}) {
			assertFalse(BackupName.valid(bad), "refusé : " + bad);
		}
		assertThrows(IllegalArgumentException.class, () -> new BackupName(T, BackupKind.AUTO, 0));
		assertThrows(IllegalArgumentException.class, () -> new BackupName(T, BackupKind.AUTO, 1000));
		assertThrows(IllegalArgumentException.class, () -> new BackupName(null, BackupKind.AUTO, 1));
	}

	@Test
	void orderIsChronologicalThenBySequence() {
		List<BackupName> names = new ArrayList<>(List.of(
			new BackupName(T.plusHours(6), BackupKind.AUTO, 1),
			new BackupName(T, BackupKind.MANUELLE, 2),
			new BackupName(T.minusDays(1), BackupKind.ARRET, 1),
			new BackupName(T, BackupKind.MANUELLE, 1)));
		names.sort(null);
		assertEquals(List.of(
			new BackupName(T.minusDays(1), BackupKind.ARRET, 1),
			new BackupName(T, BackupKind.MANUELLE, 1),
			new BackupName(T, BackupKind.MANUELLE, 2),
			new BackupName(T.plusHours(6), BackupKind.AUTO, 1)), names);
	}

	@Test
	void theDateCarriesTheServerOffset() {
		BackupName n = new BackupName(LocalDateTime.of(2026, 7, 1, 12, 0), BackupKind.AUTO, 1);
		assertEquals("2026-07-01T12:00+02:00", n.at(ZoneId.of("Europe/Paris")).toOffsetDateTime().toString());
	}

	@Test
	void onlyAutomaticKindsAreRotated() {
		assertTrue(BackupKind.AUTO.rotated());
		assertTrue(BackupKind.ARRET.rotated());
		assertTrue(BackupKind.AVANT_RESTAURATION.rotated());
		assertFalse(BackupKind.MANUELLE.rotated());
		assertEquals(BackupKind.AVANT_RESTAURATION, BackupKind.fromId("avant-restauration"));
	}
}
