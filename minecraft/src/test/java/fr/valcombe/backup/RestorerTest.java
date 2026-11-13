package fr.valcombe.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.backup.Restorer.Report;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestorerTest {
	private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
	private static final Instant NOW = Instant.parse("2026-11-14T08:00:00Z");

	@TempDir
	Path tmp;

	private Restorer restorer(ServerFixture s) {
		return new Restorer(s.dir, s.store, Clock.fixed(NOW, ZoneOffset.UTC), ZONE, "0.14.0");
	}

	private static void ask(ServerFixture s, String name, String world) throws IOException {
		RestoreMarker.write(s.valcombe, new RestoreMarker.Request(name, Instant.parse("2026-11-14T07:59:00Z"), "Alice", world));
	}

	/** Le serveur continue de vivre après la sauvegarde : on change, ajoute et retire des fichiers. */
	private static void playOn(ServerFixture s) throws IOException {
		Files.write(s.world.resolve("level.dat"), ServerFixture.noise(60_000, 2));
		s.write("world/region/r.1.0.mca", "nouvelle région");
		Files.delete(s.world.resolve("playerdata/alice.dat"));
		s.write("world/dimensions/valcombe/essai-3/region/r.0.0.mca", "monde d'essai créé après");
		s.write("config/valcombe/creations/roles.json", "{\"roles\": [\"modifié\"]}");
		s.write("config/valcombe/creations/nouvelle.json", "création faite après");
		s.write("config/valcombe-partie.json", "{\"partie\": 2}");
		s.write("config/valcombe-instantane.nbt", "instantané fait après");
		// Les fichiers privés changent eux aussi : la restauration ne doit pas y toucher.
		s.write("config/valcombe/admin.json", "{\"key\": \"nouvelle-cle\"}");
		s.write("config/valcombe-communaute.json", "{\"discordWebhook\": \"nouveau\"}");
		s.write("config/autre-mod.json", "{\"autre\": 2}");
	}

	@Test
	void markerRoundTrip() throws Exception {
		Path v = tmp.resolve("valcombe");
		assertTrue(RestoreMarker.read(v).isEmpty());
		assertFalse(RestoreMarker.present(v));
		String name = "valcombe-2026-11-13_09-12-44-auto.zip";
		RestoreMarker.write(v, new RestoreMarker.Request(name, NOW, "Alice", "world"));
		assertTrue(RestoreMarker.present(v));
		RestoreMarker.Request r = RestoreMarker.read(v).orElseThrow();
		assertEquals(name, r.nom());
		assertEquals(NOW, r.demandeLe());
		assertEquals("Alice", r.par());
		assertEquals("world", r.monde());
		assertThrows(IllegalArgumentException.class,
			() -> RestoreMarker.write(v, new RestoreMarker.Request("../../etc/passwd", NOW, "x", null)));
		assertTrue(RestoreMarker.clear(v));
		assertFalse(RestoreMarker.clear(v));
		Files.writeString(RestoreMarker.file(v), "{\"nom\": \"../x.zip\"}");
		assertThrows(IOException.class, () -> RestoreMarker.read(v));
	}

	@Test
	void restoringPutsBackTheSavedStateAndKeepsTheCurrentOneAside() throws Exception {
		ServerFixture s = new ServerFixture(tmp.resolve("srv"));
		String saved = s.backup(BackupKind.AUTO, LocalDateTime.of(2026, 11, 13, 9, 0));
		playOn(s);
		ask(s, saved, "world");

		Report r = restorer(s).applyPending().orElseThrow();
		assertTrue(r.ok(), r.message());
		assertEquals(saved, r.nom());
		assertFalse(RestoreMarker.present(s.valcombe), "demande retirée");

		// Le monde de la sauvegarde, tel quel.
		assertArrayEquals(ServerFixture.noise(50_000, 1), Files.readAllBytes(s.world.resolve("level.dat")));
		assertEquals("Alice", s.read("world/playerdata/alice.dat"));
		assertFalse(s.exists("world/region/r.1.0.mca"), "région créée après : partie");
		assertFalse(s.exists("world/dimensions/valcombe/essai-3"), "monde créé après : parti");
		assertEquals("région du hub", s.read("world/dimensions/valcombe/hub/region/r.0.0.mca"));
		// Les créations et l'état du jeu de la sauvegarde.
		assertEquals("{\"roles\": []}", s.read("config/valcombe/creations/roles.json"));
		assertFalse(s.exists("config/valcombe/creations/nouvelle.json"));
		assertEquals("{\"partie\": 1}", s.read("config/valcombe-partie.json"));
		assertFalse(s.exists("config/valcombe-instantane.nbt"));
		assertEquals("carte", s.read("config/valcombe/creations/cartes/bourg.nbt"));
		// Les fichiers privés et ceux des autres mods n'ont pas bougé.
		assertEquals("{\"key\": \"nouvelle-cle\"}", s.read("config/valcombe/admin.json"));
		assertEquals("{\"discordWebhook\": \"nouveau\"}", s.read("config/valcombe-communaute.json"));
		assertEquals("{\"autre\": 2}", s.read("config/autre-mod.json"));
		assertTrue(s.exists("config/valcombe/sauvegardes.json"));
		assertTrue(s.exists("server.properties"));

		// L'état d'avant est gardé : restaurer cette sauvegarde-là défait la restauration.
		assertNotNull(r.securite());
		BackupStore.Backup safety = s.store.find(r.securite()).orElseThrow();
		assertEquals(BackupKind.AVANT_RESTAURATION, safety.kind());
		assertEquals("valcombe-2026-11-14_09-00-00-avant-restauration.zip", safety.name(), "heure de Paris");
		assertEquals("restauration de " + saved, BackupManifest.read(s.store.resolve(safety.name())).par);
		ask(s, safety.name(), "world");
		assertTrue(restorer(s).applyPending().orElseThrow().ok());
		assertArrayEquals(ServerFixture.noise(60_000, 2), Files.readAllBytes(s.world.resolve("level.dat")));
		assertEquals("création faite après", s.read("config/valcombe/creations/nouvelle.json"));
		assertEquals("monde d'essai créé après", s.read("world/dimensions/valcombe/essai-3/region/r.0.0.mca"));

		// Rien ne traîne dans le dossier des sauvegardes, et le compte rendu est là.
		assertFalse(Files.exists(s.store.dir().resolve(Restorer.STAGING)));
		assertFalse(Files.exists(s.store.dir().resolve(Restorer.OLD)));
		Report last = Restorer.lastReport(s.store.dir()).orElseThrow();
		assertTrue(last.ok());
		assertEquals(safety.name(), last.nom());
		assertEquals(NOW.toString(), last.faiteLe());
	}

	@Test
	void nothingToDoWithoutARequest() throws Exception {
		ServerFixture s = new ServerFixture(tmp.resolve("srv"));
		assertTrue(restorer(s).applyPending().isEmpty());
		assertTrue(s.store.list().isEmpty());
		assertTrue(Restorer.lastReport(s.store.dir()).isEmpty());
	}

	@Test
	void aMissingArchiveChangesNothingAndTheRequestIsNotReplayed() throws Exception {
		ServerFixture s = new ServerFixture(tmp.resolve("srv"));
		ask(s, "valcombe-2020-01-01_00-00-00-auto.zip", null);
		Report r = restorer(s).applyPending().orElseThrow();
		assertFalse(r.ok());
		assertTrue(r.message().contains("rien n'a été restauré"), r.message());
		assertNull(r.securite());
		assertFalse(RestoreMarker.present(s.valcombe), "pas de boucle de redémarrages");
		assertTrue(restorer(s).applyPending().isEmpty());
		assertArrayEquals(ServerFixture.noise(50_000, 1), Files.readAllBytes(s.world.resolve("level.dat")));
		assertTrue(s.store.list().isEmpty(), "pas de sauvegarde de sécurité inutile");
		assertFalse(Restorer.lastReport(s.store.dir()).orElseThrow().ok());
	}

	@Test
	void aDamagedArchiveChangesNothing() throws Exception {
		ServerFixture s = new ServerFixture(tmp.resolve("srv"));
		String saved = s.backup(BackupKind.MANUELLE, LocalDateTime.of(2026, 11, 13, 9, 0));
		// Un octet abîmé au milieu des données du premier gros fichier : la fiche (à la fin) reste lisible.
		try (RandomAccessFile f = new RandomAccessFile(s.store.resolve(saved).toFile(), "rw")) {
			f.seek(20_000);
			int b = f.read();
			f.seek(20_000);
			f.write(b ^ 0xFF);
		}
		BackupManifest.read(s.store.resolve(saved));
		playOn(s);
		ask(s, saved, "world");
		Report r = restorer(s).applyPending().orElseThrow();
		assertFalse(r.ok());
		assertTrue(r.message().contains("rien n'a été changé"), r.message());
		assertArrayEquals(ServerFixture.noise(60_000, 2), Files.readAllBytes(s.world.resolve("level.dat")), "monde actuel intact");
		assertEquals("création faite après", s.read("config/valcombe/creations/nouvelle.json"));
		assertEquals(List.of(saved), s.store.list().stream().map(BackupStore.Backup::name).toList(), "pas de sauvegarde de sécurité");
		assertFalse(Files.exists(s.store.dir().resolve(Restorer.STAGING)), "dossier de travail effacé");
	}

	@Test
	void anArchiveWithoutAWorldIsRefused() throws Exception {
		ServerFixture s = new ServerFixture(tmp.resolve("srv"));
		String name = new BackupName(LocalDateTime.of(2026, 11, 13, 9, 0), BackupKind.MANUELLE, 1).fileName();
		Files.createDirectories(s.store.dir());
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(s.store.resolve(name)))) {
			out.putNextEntry(new ZipEntry("config/valcombe/mondes.json"));
			out.write("{}".getBytes());
			out.closeEntry();
			out.putNextEntry(new ZipEntry(BackupManifest.ENTRY));
			out.write("{\"format\": 1}".getBytes());
			out.closeEntry();
		}
		ask(s, name, "world");
		Report r = restorer(s).applyPending().orElseThrow();
		assertFalse(r.ok());
		assertTrue(r.message().contains("level.dat"), r.message());
		assertTrue(s.exists("world/level.dat"));
	}

	@Test
	void unpackingIgnoresAnythingOutsideABackup() throws Exception {
		Path zip = tmp.resolve("piege.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			for (String n : new String[] {"../hors.txt", "monde/../../hors2.txt", "config/valcombe/admin.json", "server.properties",
				"monde/level.dat"}) {
				out.putNextEntry(new ZipEntry(n));
				out.write(n.getBytes());
				out.closeEntry();
			}
		}
		Path dest = tmp.resolve("dest");
		Restorer.extract(zip, dest);
		assertTrue(Files.exists(dest.resolve("monde/level.dat")));
		assertFalse(Files.exists(tmp.resolve("hors.txt")));
		assertFalse(Files.exists(tmp.resolve("hors2.txt")));
		assertFalse(Files.exists(dest.resolve("config/valcombe/admin.json")), "une clé d'administration n'est jamais posée");
		assertFalse(Files.exists(dest.resolve("server.properties")));
	}

	@Test
	void theWorldFolderComesFromTheRequestOrServerProperties() throws Exception {
		Path srv = tmp.resolve("srv");
		Files.createDirectories(srv);
		assertEquals(srv.resolve("world").toAbsolutePath().normalize(), Restorer.worldDir(srv, null));
		Files.writeString(srv.resolve("server.properties"), "level-name=bourg\n");
		assertEquals(srv.resolve("bourg").toAbsolutePath().normalize(), Restorer.worldDir(srv, null));
		assertEquals(srv.resolve("mondes/valcombe").toAbsolutePath().normalize(), Restorer.worldDir(srv, "mondes/valcombe"));
		// Jamais hors du dossier du serveur, jamais le dossier lui-même.
		assertEquals(srv.resolve("bourg").toAbsolutePath().normalize(), Restorer.worldDir(srv, "../ailleurs"));
		assertEquals(srv.resolve("bourg").toAbsolutePath().normalize(), Restorer.worldDir(srv, "."));
		Files.writeString(srv.resolve("server.properties"), "level-name=../../etc\n");
		assertEquals(srv.resolve("world").toAbsolutePath().normalize(), Restorer.worldDir(srv, null));
	}

	@Test
	void anUnreadableRequestIsSetAside() throws Exception {
		ServerFixture s = new ServerFixture(tmp.resolve("srv"));
		Files.writeString(RestoreMarker.file(s.valcombe), "pas du json");
		Optional<Report> r = restorer(s).applyPending();
		assertFalse(r.orElseThrow().ok());
		assertFalse(RestoreMarker.present(s.valcombe));
		assertTrue(Files.exists(s.valcombe.resolve(RestoreMarker.FILE_NAME + ".illisible")));
		assertTrue(restorer(s).applyPending().isEmpty());
	}
}
