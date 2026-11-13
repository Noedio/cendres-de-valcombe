package fr.valcombe.backup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Random;

/** Un faux dossier de serveur : un monde (avec un monde de Valcombe), config/valcombe et les fichiers à côté. */
final class ServerFixture {
	final Path dir;
	final Path world;
	final Path config;
	final Path valcombe;
	final BackupStore store;

	ServerFixture(Path dir) throws IOException {
		this.dir = dir;
		this.world = dir.resolve("world");
		this.config = dir.resolve("config");
		this.valcombe = config.resolve("valcombe");
		this.store = new BackupStore(dir.resolve(BackupStore.DIR_NAME));
		write("server.properties", "motd=Valcombe\nlevel-name=world\n");
		write("world/level.dat", null);
		Files.write(world.resolve("level.dat"), noise(50_000, 1));
		write("world/session.lock", "verrou");
		write("world/region/r.0.0.mca", "région du monde principal");
		write("world/dimensions/valcombe/hub/region/r.0.0.mca", "région du hub");
		write("world/playerdata/alice.dat", "Alice");
		write("config/valcombe/mondes.json", "{\"format\": 1, \"mondes\": [{\"id\": \"hub\", \"nom\": \"Hub\"}, "
			+ "{\"id\": \"partie-principale\", \"nom\": \"Partie principale\"}], \"aEffacer\": []}\n");
		write("config/valcombe/creations/roles.json", "{\"roles\": []}");
		write("config/valcombe/creations/cartes/bourg.nbt", "carte");
		write("config/valcombe/creations/structures/puits.nbt", "puits");
		write("config/valcombe/admin.json", "{\"key\": \"secret-du-serveur\"}");
		write("config/valcombe/admin.json.tmp", "secret en cours d'écriture");
		write("config/valcombe/sauvegardes.json", "{\"actives\": true}");
		write("config/valcombe/redemarrer", "{}");
		write("config/valcombe/creations/roles.json.tmp", "en cours");
		write("config/valcombe-partie.json", "{\"partie\": 1}");
		write("config/valcombe-village.json", "{\"village\": 1}");
		write("config/valcombe-stats.json", "{}");
		write("config/valcombe-scripts/soiree.json", "{\"script\": 1}");
		write("config/valcombe-communaute.json", "{\"discordWebhook\": \"https://discord.example/secret\"}");
		write("config/valcombe-hote.json", "{\"lien\": 1}");
		write("config/autre-mod.json", "{}");
	}

	/** Écrit un fichier texte (dossiers créés) ; null : crée seulement les dossiers. */
	void write(String rel, String text) throws IOException {
		Path f = dir.resolve(rel);
		Files.createDirectories(f.getParent());
		if (text != null) {
			Files.writeString(f, text, StandardCharsets.UTF_8);
		}
	}

	String read(String rel) throws IOException {
		return Files.readString(dir.resolve(rel), StandardCharsets.UTF_8);
	}

	boolean exists(String rel) {
		return Files.exists(dir.resolve(rel));
	}

	/** Octets pseudo-aléatoires (incompressibles), toujours les mêmes pour une graine donnée. */
	static byte[] noise(int n, long seed) {
		byte[] b = new byte[n];
		new Random(seed).nextBytes(b);
		return b;
	}

	/** Une sauvegarde de l'état actuel, de cette sorte, née à ce moment. */
	String backup(BackupKind kind, LocalDateTime at) throws IOException {
		BackupName name = store.newName(kind, at);
		BackupManifest m = new BackupManifest();
		m.nom = name.fileName();
		m.sorte = kind.id();
		m.versionMod = "0.13.1";
		m.dossierMonde = "world";
		BackupWriter.write(store.resolve(name.fileName()), BackupContents.scan(dir, world), m, (f, b) -> { }, () -> false);
		return name.fileName();
	}
}
