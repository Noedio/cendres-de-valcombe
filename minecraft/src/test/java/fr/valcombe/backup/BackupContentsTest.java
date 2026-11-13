package fr.valcombe.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupContentsTest {
	@TempDir
	Path tmp;

	@Test
	void whatGoesIntoAnArchive() {
		// Le monde et tous les mondes de Valcombe qu'il contient, sauf le verrou du serveur.
		assertTrue(BackupContents.managed("monde/level.dat"));
		assertTrue(BackupContents.managed("monde/dimensions/valcombe/hub/region/r.0.0.mca"));
		assertFalse(BackupContents.managed("monde/session.lock"));
		assertTrue(BackupContents.managed("monde/dimensions/session.lock"), "seul le verrou du dossier du monde est écarté");
		// Créations, cartes, structures, registre des mondes.
		assertTrue(BackupContents.managed("config/valcombe/mondes.json"));
		assertTrue(BackupContents.managed("config/valcombe/mondes.json.bak"));
		assertTrue(BackupContents.managed("config/valcombe/creations/cartes/bourg.nbt"));
		// L'état du jeu rangé à côté.
		assertTrue(BackupContents.managed("config/valcombe-partie.json"));
		assertTrue(BackupContents.managed("config/valcombe-scripts/soiree.json"));
		// Jamais : la clé d'administration (et ses variantes), les réglages des sauvegardes, la demande de restauration,
		// le drapeau de redémarrage, le webhook Discord, le lien de l'hôte, les fichiers en cours d'écriture.
		for (String no : new String[] {"config/valcombe/admin.json", "config/valcombe/admin.json.tmp", "config/valcombe/admin.json.illisible",
			"config/valcombe/ADMIN.JSON", "config/valcombe/sauvegardes.json", "config/valcombe/sauvegardes.json.bak",
			"config/valcombe/restaurer.json", "config/valcombe/redemarrer", "config/valcombe-communaute.json",
			"config/valcombe-communaute.json.bak", "config/valcombe-hote.json", "config/valcombe/creations/roles.json.tmp",
			"config/valcombe", "config/valcombe-", "config/autre-mod.json", "server.properties", "ops.json", "monde/",
			// Chemins dangereux : jamais acceptés, même s'ils commencent bien.
			"monde/../config/valcombe/admin.json", "monde/./level.dat", "monde//level.dat", "/monde/level.dat", "monde\\level.dat",
			"C:/monde/level.dat", "config/valcombe/../../x", ""}) {
			assertFalse(BackupContents.managed(no), "jamais dans une archive : " + no);
		}
	}

	@Test
	void scanFindsExactlyTheManagedFiles() throws Exception {
		ServerFixture s = new ServerFixture(tmp.resolve("srv"));
		BackupContents.Scan scan = BackupContents.scan(s.dir, s.world);
		List<String> names = scan.entries().stream().map(BackupContents.Entry::name).toList();
		assertEquals(List.of(
			"monde/dimensions/valcombe/hub/region/r.0.0.mca", "monde/level.dat", "monde/playerdata/alice.dat", "monde/region/r.0.0.mca",
			"config/valcombe/creations/cartes/bourg.nbt", "config/valcombe/creations/roles.json",
			"config/valcombe/creations/structures/puits.nbt", "config/valcombe/mondes.json",
			"config/valcombe-partie.json", "config/valcombe-scripts/soiree.json", "config/valcombe-stats.json",
			"config/valcombe-village.json"), names);
		long bytes = 0;
		for (BackupContents.Entry e : scan.entries()) {
			bytes += Files.size(e.file());
		}
		assertEquals(bytes, scan.bytes());
		assertTrue(scan.skipped().isEmpty());
	}

	@Test
	void theArchiveHoldsTheFilesAndItsManifestLast() throws Exception {
		ServerFixture s = new ServerFixture(tmp.resolve("srv"));
		String name = s.backup(BackupKind.MANUELLE, LocalDateTime.of(2026, 11, 13, 9, 0));
		Path zip = s.store.resolve(name);
		List<String> entries = new ArrayList<>();
		try (ZipFile zf = new ZipFile(zip.toFile())) {
			Enumeration<? extends ZipEntry> en = zf.entries();
			while (en.hasMoreElements()) {
				entries.add(en.nextElement().getName());
			}
			assertArrayEquals(ServerFixture.noise(50_000, 1), zf.getInputStream(zf.getEntry("monde/level.dat")).readAllBytes());
			assertEquals("{\"partie\": 1}", new String(zf.getInputStream(zf.getEntry("config/valcombe-partie.json")).readAllBytes(),
				StandardCharsets.UTF_8));
		}
		assertEquals(BackupManifest.ENTRY, entries.get(entries.size() - 1), "la fiche en dernier");
		assertEquals(13, entries.size());
		assertFalse(entries.toString().contains("admin.json"), "pas de clé d'administration");
		assertFalse(entries.toString().contains("communaute"), "pas de webhook");
		BackupManifest m = BackupManifest.read(zip);
		assertEquals(name, m.nom);
		assertEquals("manuelle", m.sorte);
		assertEquals(12, m.fichiers);
		assertTrue(m.octets > 50_000);
		assertFalse(Files.exists(zip.resolveSibling(name + BackupWriter.PARTIAL)), "pas de reste d'écriture");
		assertEquals(List.of("Hub", "Partie principale"), BackupManifest.worldNames(s.valcombe.resolve("mondes.json")));
	}

	@Test
	void aCancelledWriteLeavesNothingBehind() throws Exception {
		ServerFixture s = new ServerFixture(tmp.resolve("srv"));
		Path target = s.store.resolve(new BackupName(LocalDateTime.of(2026, 11, 13, 9, 0), BackupKind.AUTO, 1).fileName());
		AtomicInteger seen = new AtomicInteger();
		assertThrows(BackupWriter.Cancelled.class, () -> BackupWriter.write(target, BackupContents.scan(s.dir, s.world),
			new BackupManifest(), (f, b) -> { }, () -> seen.incrementAndGet() > 3));
		assertFalse(Files.exists(target));
		assertFalse(Files.exists(target.resolveSibling(target.getFileName() + BackupWriter.PARTIAL)));
		assertTrue(s.store.list().isEmpty());
	}

	@Test
	void aFileThatVanishesDuringTheCopyIsSkipped() throws Exception {
		ServerFixture s = new ServerFixture(tmp.resolve("srv"));
		BackupContents.Scan scan = BackupContents.scan(s.dir, s.world);
		Files.delete(s.dir.resolve("config/valcombe-stats.json"));
		Path target = s.store.resolve(new BackupName(LocalDateTime.of(2026, 11, 13, 9, 0), BackupKind.AUTO, 1).fileName());
		BackupManifest m = new BackupManifest();
		BackupWriter.Result r = BackupWriter.write(target, scan, m, (f, b) -> { }, () -> false);
		assertEquals(11, r.files());
		assertEquals(1, r.skipped().size());
		assertTrue(r.skipped().get(0).startsWith("config/valcombe-stats.json"));
		assertEquals(r.skipped(), BackupManifest.read(target).ignores);
	}

	@Test
	void foreignOrNewerArchivesAreNotBackups() throws Exception {
		Path zip = tmp.resolve("x.zip");
		try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
			out.putNextEntry(new ZipEntry("monde/level.dat"));
			out.write(1);
			out.closeEntry();
		}
		assertThrows(IOException.class, () -> BackupManifest.read(zip));
		Files.writeString(tmp.resolve("pas-un-zip.zip"), "bonjour");
		assertThrows(IOException.class, () -> BackupManifest.read(tmp.resolve("pas-un-zip.zip")));
		assertThrows(IOException.class, () -> BackupManifest.fromJson("{\"format\": 99}"));
		assertThrows(IOException.class, () -> BackupManifest.fromJson("[]"));
		assertEquals(1, BackupManifest.fromJson("{\"format\": 1, \"nom\": \"a\"}").format);
	}
}
