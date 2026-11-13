package fr.valcombe.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupStoreTest {
	private static final LocalDateTime T = LocalDateTime.of(2026, 11, 13, 6, 0, 0);

	@TempDir
	Path dir;

	private String touch(BackupKind kind, LocalDateTime at) throws Exception {
		String name = new BackupName(at, kind, 1).fileName();
		Files.writeString(dir.resolve(name), "zip");
		return name;
	}

	private static List<String> names(List<BackupStore.Backup> list) {
		return list.stream().map(BackupStore.Backup::name).toList();
	}

	@Test
	void listShowsOnlyBackupsNewestFirst() throws Exception {
		BackupStore store = new BackupStore(dir);
		assertTrue(new BackupStore(dir.resolve("absent")).list().isEmpty(), "dossier absent : liste vide");
		String a = touch(BackupKind.AUTO, T);
		String b = touch(BackupKind.MANUELLE, T.plusHours(1));
		String c = touch(BackupKind.ARRET, T.minusHours(1));
		Files.writeString(dir.resolve("notes.txt"), "à la main");
		Files.writeString(dir.resolve(new BackupName(T.plusHours(2), BackupKind.AUTO, 1).fileName() + BackupWriter.PARTIAL), "moitié");
		Files.createDirectories(dir.resolve(".restauration"));
		Files.createDirectories(dir.resolve(new BackupName(T.plusHours(3), BackupKind.AUTO, 1).fileName())); // un dossier, pas une archive
		assertEquals(List.of(b, a, c), names(store.list()));
		assertEquals(b, store.latest().orElseThrow().name());
		assertTrue(store.find(a).isPresent());
		assertTrue(store.find("notes.txt").isEmpty());
	}

	@Test
	void newNamesNeverCollide() throws Exception {
		BackupStore store = new BackupStore(dir);
		BackupName first = store.newName(BackupKind.MANUELLE, T);
		assertEquals(1, first.seq());
		Files.writeString(dir.resolve(first.fileName()), "zip");
		BackupName second = store.newName(BackupKind.MANUELLE, T);
		assertEquals(2, second.seq());
		// Une archive encore en cours d'écriture réserve aussi son nom.
		Files.writeString(dir.resolve(second.fileName() + BackupWriter.PARTIAL), "moitié");
		assertEquals(3, store.newName(BackupKind.MANUELLE, T).seq());
		assertEquals(1, store.newName(BackupKind.AUTO, T).seq(), "autre sorte, même seconde : pas de conflit");
	}

	@Test
	void rotationKeepsTheMostRecentAutomaticOnesAndNeverManualOnes() throws Exception {
		List<String> autos = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			autos.add(touch(i % 2 == 0 ? BackupKind.AUTO : BackupKind.ARRET, T.plusHours(6L * i)));
		}
		String before = touch(BackupKind.AVANT_RESTAURATION, T.plusHours(40));
		String manualOld = touch(BackupKind.MANUELLE, T.minusDays(30));
		String manualNew = touch(BackupKind.MANUELLE, T.plusDays(3));
		BackupStore store = new BackupStore(dir);

		// Décision pure : les plus anciennes au-delà de 3, parmi les sortes que la rotation gère.
		List<String> doomed = names(BackupStore.toDelete(store.list(), 3));
		assertEquals(List.of(autos.get(3), autos.get(2), autos.get(1), autos.get(0)), doomed);

		List<String> deleted = store.rotate(3);
		assertEquals(doomed, deleted);
		assertEquals(List.of(manualNew, before, autos.get(5), autos.get(4), manualOld), names(store.list()));
		assertTrue(store.rotate(3).isEmpty(), "rien de plus à effacer");
		assertEquals(List.of(), BackupStore.toDelete(store.list(), 10));
		// Garder 1 : seule la plus récente des automatiques reste ; les manuelles restent toutes.
		store.rotate(1);
		assertEquals(List.of(manualNew, before, manualOld), names(store.list()));
	}

	@Test
	void deletionAndResolutionRefuseForeignNames() throws Exception {
		BackupStore store = new BackupStore(dir);
		String a = touch(BackupKind.MANUELLE, T);
		Files.writeString(dir.getParent().resolve("secret.txt"), "à ne jamais toucher");
		assertThrows(IllegalArgumentException.class, () -> store.resolve("../secret.txt"));
		assertThrows(IllegalArgumentException.class, () -> store.delete("../secret.txt"));
		assertThrows(IllegalArgumentException.class, () -> store.resolve("C:\\Windows\\win.ini"));
		assertTrue(Files.exists(dir.getParent().resolve("secret.txt")));
		assertTrue(store.delete(a));
		assertFalse(store.delete(a), "déjà effacée");
	}

	@Test
	void partialArchivesAreCleanedAtStartup() throws Exception {
		BackupStore store = new BackupStore(dir);
		String keep = touch(BackupKind.AUTO, T);
		Files.writeString(dir.resolve(new BackupName(T.plusHours(1), BackupKind.AUTO, 1).fileName() + BackupWriter.PARTIAL), "moitié");
		assertEquals(1, store.cleanPartials());
		assertEquals(List.of(keep), names(store.list()));
		assertEquals(0, store.cleanPartials());
		assertTrue(store.freeSpace() > 0);
		assertTrue(store.list().get(0).modified().isBefore(Instant.now().plusSeconds(5)));
	}
}
