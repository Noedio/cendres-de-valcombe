package fr.valcombe.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Restauration d'une sauvegarde, appliquée <b>au démarrage du serveur, avant que Minecraft n'ouvre le monde</b>
 * (le mod l'appelle dès son initialisation sur un serveur dédié, avant même la lecture de server.properties).
 *
 * <p>Déroulement, pensé pour ne jamais laisser le serveur à moitié restauré :
 * <ol>
 *   <li>la demande ({@link RestoreMarker}) est lue puis retirée tout de suite : quoi qu'il arrive, elle ne sera pas
 *   rejouée au démarrage suivant ;</li>
 *   <li>l'archive est vérifiée (nom, fiche) puis déballée en entier dans {@code backups/.restauration} : chaque
 *   fichier est contrôlé (somme de contrôle du zip, chemin sans danger). Une archive abîmée s'arrête là, sans que
 *   rien n'ait été touché ;</li>
 *   <li>l'état actuel est lui-même sauvegardé ({@link BackupKind#AVANT_RESTAURATION}) : une restauration se défait
 *   en restaurant cette archive-là. Si cette sauvegarde échoue (disque plein...), rien n'est changé ;</li>
 *   <li>le monde actuel est mis de côté, le monde restauré prend sa place ; les fichiers de Valcombe de
 *   {@code config/} sont remplacés (les fichiers privés, comme la clé d'administration, ne sont jamais touchés :
 *   voir {@link BackupContents}) ;</li>
 *   <li>si cette mise en place échoue en route, l'état d'avant est remis depuis la sauvegarde de sécurité.</li>
 * </ol>
 * Le compte rendu est écrit dans {@code backups/derniere-restauration.json} (l'API d'administration le montre) et
 * dans le journal du serveur.
 */
public final class Restorer {
	private static final Logger LOG = LoggerFactory.getLogger("valcombe");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
	/** Dossier de travail : l'archive déballée. */
	public static final String STAGING = ".restauration";
	/** Dossier de travail : l'état d'avant, déballé pour un retour en arrière. */
	public static final String ROLLBACK = ".restauration-retour";
	/** Dossier de travail : le monde remplacé, gardé le temps de la mise en place. */
	public static final String OLD = ".ancien";
	/** Compte rendu de la dernière restauration, dans le dossier des sauvegardes. */
	public static final String REPORT = "derniere-restauration.json";
	/** Place gardée libre en plus de ce qu'il faut écrire. */
	static final long MARGIN = 64L * 1024 * 1024;

	/**
	 * Compte rendu d'une restauration.
	 *
	 * @param nom       l'archive demandée (null si la demande était illisible)
	 * @param demandeLe moment de la demande
	 * @param faiteLe   moment où elle a été appliquée (ou a échoué)
	 * @param ok        restaurée
	 * @param message   ce qui s'est passé, en français, à montrer tel quel
	 * @param securite  la sauvegarde de l'état d'avant (null si elle n'a pas été faite)
	 */
	public record Report(String nom, String demandeLe, String faiteLe, boolean ok, String message, String securite) {
		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("nom", nom);
			m.put("demandeLe", demandeLe);
			m.put("faiteLe", faiteLe);
			m.put("ok", ok);
			m.put("message", message);
			m.put("securite", securite);
			return m;
		}
	}

	private final Path serverDir;
	private final Path configDir;
	private final Path valcombeDir;
	private final BackupStore store;
	private final Clock clock;
	private final ZoneId zone;
	private final String modVersion;

	/**
	 * @param serverDir  dossier du serveur (celui de config/ et de server.properties)
	 * @param store      dossier des sauvegardes
	 * @param clock      horloge (pour dater la sauvegarde de sécurité et le compte rendu)
	 * @param zone       fuseau horaire des noms d'archive
	 * @param modVersion version du mod, notée dans la sauvegarde de sécurité
	 */
	public Restorer(Path serverDir, BackupStore store, Clock clock, ZoneId zone, String modVersion) {
		this.serverDir = serverDir.toAbsolutePath().normalize();
		this.configDir = this.serverDir.resolve("config");
		this.valcombeDir = configDir.resolve(BackupContents.VALCOMBE);
		this.store = store;
		this.clock = clock;
		this.zone = zone;
		this.modVersion = modVersion;
	}

	/** Applique la restauration demandée, s'il y en a une ; vide sinon. Ne lève jamais : tout est dans le compte rendu. */
	public Optional<Report> applyPending() {
		Optional<RestoreMarker.Request> req;
		try {
			req = RestoreMarker.read(valcombeDir);
		} catch (IOException e) {
			// Mise de côté pour qu'on puisse la lire à la main, et pour ne pas la relire à chaque démarrage.
			try {
				Files.move(RestoreMarker.file(valcombeDir), valcombeDir.resolve(RestoreMarker.FILE_NAME + ".illisible"),
					StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException x) {
				LOG.warn("Demande de restauration impossible à mettre de côté : {}", x.getMessage());
			}
			return Optional.of(report(failure(null, null, "Demande de restauration illisible (" + e.getMessage()
				+ ") : rien n'a été restauré.", null)));
		}
		if (req.isEmpty()) {
			return Optional.empty();
		}
		RestoreMarker.Request r = req.get();
		try {
			RestoreMarker.clear(valcombeDir);
		} catch (IOException e) {
			// Si elle restait là, elle serait rejouée à chaque démarrage : mieux vaut ne rien faire.
			return Optional.of(report(failure(r.nom(), r.demandeLe().toString(), "Demande de restauration impossible à retirer ("
				+ e.getMessage() + ") : rien n'a été restauré.", null)));
		}
		LOG.info("[valcombe] Restauration de la sauvegarde {} demandée par {} : en cours...", r.nom(), r.par());
		return Optional.of(report(restore(r)));
	}

	private Report report(Report r) {
		if (r.ok()) {
			LOG.info("[valcombe] {}", r.message());
		} else {
			LOG.error("[valcombe] {}", r.message());
		}
		try {
			Files.createDirectories(store.dir());
			Path f = store.dir().resolve(REPORT);
			Path tmp = f.resolveSibling(REPORT + ".tmp");
			Files.writeString(tmp, GSON.toJson(r.toMap()) + "\n", StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LOG.warn("Compte rendu de restauration impossible à écrire : {}", e.getMessage());
		}
		return r;
	}

	private Report failure(String nom, String asked, String message, String safety) {
		return new Report(nom, asked, clock.instant().toString(), false, message, safety);
	}

	/** Restaure cette archive (la demande a déjà été retirée). */
	Report restore(RestoreMarker.Request req) {
		String nom = req.nom();
		String asked = req.demandeLe().toString();
		Path archive;
		try {
			archive = store.resolve(nom);
		} catch (IllegalArgumentException e) {
			return failure(nom, asked, e.getMessage() + " Rien n'a été restauré.", null);
		}
		if (!Files.isRegularFile(archive)) {
			return failure(nom, asked, "La sauvegarde « " + nom + " » n'est plus dans le dossier " + BackupStore.DIR_NAME
				+ "/ : rien n'a été restauré.", null);
		}
		BackupManifest manifest;
		try {
			manifest = BackupManifest.read(archive);
		} catch (IOException e) {
			return failure(nom, asked, "Sauvegarde « " + nom + " » illisible (" + e.getMessage() + ") : rien n'a été restauré.", null);
		}
		Path world = worldDir(serverDir, req.monde());
		Path staging = store.dir().resolve(STAGING);
		// 1. L'archive est déballée en entier à part : une archive abîmée s'arrête ici, sans rien changer.
		try {
			deleteTree(staging);
			requireSpace(manifest.octets, "déballer l'archive");
			extract(archive, staging);
		} catch (IOException e) {
			deleteQuietly(staging);
			return failure(nom, asked, "Sauvegarde « " + nom + " » impossible à déballer (" + e.getMessage()
				+ ") : rien n'a été changé.", null);
		}
		if (!Files.isRegularFile(staging.resolve("monde").resolve("level.dat"))) {
			deleteQuietly(staging);
			return failure(nom, asked, "La sauvegarde « " + nom + " » ne contient pas de monde (level.dat absent) : rien n'a été changé.", null);
		}
		// 2. L'état actuel est sauvegardé : de quoi revenir en arrière.
		BackupName safety;
		try {
			safety = snapshot(world, nom);
		} catch (IOException e) {
			deleteQuietly(staging);
			return failure(nom, asked, "Sauvegarde de sécurité de l'état actuel impossible (" + e.getMessage()
				+ ") : rien n'a été changé.", null);
		}
		// 3. Mise en place ; en cas d'échec, retour à l'état d'avant depuis la sauvegarde de sécurité.
		Path old = store.dir().resolve(OLD);
		try {
			install(staging, world);
		} catch (IOException e) {
			LOG.error("Restauration de {} interrompue", nom, e);
			Path back = store.dir().resolve(ROLLBACK);
			try {
				deleteTree(back);
				extract(store.resolve(safety.fileName()), back);
				install(back, world);
				deleteQuietly(old);
				return failure(nom, asked, "Restauration de « " + nom + " » interrompue (" + e.getMessage()
					+ ") : l'état d'avant a été remis en place.", safety.fileName());
			} catch (IOException e2) {
				LOG.error("Retour à l'état d'avant impossible", e2);
				return failure(nom, asked, "Restauration de « " + nom + " » interrompue (" + e.getMessage()
					+ "), et l'état d'avant n'a pas pu être remis (" + e2.getMessage() + ") : arrêtez le serveur et restaurez "
					+ "la sauvegarde « " + safety.fileName() + " » (dossier " + BackupStore.DIR_NAME + "/).", safety.fileName());
			} finally {
				deleteQuietly(back);
			}
		} finally {
			deleteQuietly(staging);
		}
		deleteQuietly(old);
		String from = manifest.versionMod == null || manifest.versionMod.isEmpty() || manifest.versionMod.equals(modVersion) ? ""
			: " (faite avec la version " + manifest.versionMod + " de Valcombe)";
		return new Report(nom, asked, clock.instant().toString(), true, "Sauvegarde « " + nom + " » restaurée" + from
			+ ". L'état d'avant est gardé dans « " + safety.fileName() + " ».", safety.fileName());
	}

	/** Sauvegarde l'état actuel (sorte « avant-restauration ») et renvoie son nom. */
	private BackupName snapshot(Path world, String restoring) throws IOException {
		BackupContents.Scan scan = BackupContents.scan(serverDir, world);
		requireSpace(scan.bytes(), "sauvegarder l'état actuel");
		BackupName name = store.newName(BackupKind.AVANT_RESTAURATION, LocalDateTime.now(clock.withZone(zone)));
		BackupManifest m = new BackupManifest();
		m.nom = name.fileName();
		m.sorte = name.kind().id();
		m.date = name.at(zone).toOffsetDateTime().toString();
		m.par = "restauration de " + restoring;
		m.versionMod = modVersion;
		m.dossierMonde = world.getFileName() == null ? "" : world.getFileName().toString();
		m.mondes = BackupManifest.worldNames(valcombeDir.resolve("mondes.json"));
		BackupWriter.write(store.resolve(name.fileName()), scan, m, (f, b) -> { }, () -> false);
		return name;
	}

	private void requireSpace(long bytes, String what) throws IOException {
		long free = store.freeSpace();
		if (free >= 0 && free < bytes + MARGIN) {
			throw new IOException("place insuffisante sur le disque pour " + what + " : " + (bytes + MARGIN) / (1024 * 1024)
				+ " Mo nécessaires, " + free / (1024 * 1024) + " Mo libres");
		}
	}

	/** Met en place un état déballé : le monde, puis les fichiers de Valcombe de config/. */
	private void install(Path unpacked, Path world) throws IOException {
		Path old = store.dir().resolve(OLD);
		deleteTree(old);
		Files.createDirectories(old);
		if (Files.exists(world, LinkOption.NOFOLLOW_LINKS)) {
			moveTree(world, old.resolve("monde"));
		}
		Path restoredWorld = unpacked.resolve("monde");
		if (Files.isDirectory(restoredWorld)) {
			moveTree(restoredWorld, world);
		}
		clearManagedConfig();
		Path restoredConfig = unpacked.resolve("config");
		if (Files.isDirectory(restoredConfig)) {
			copyTree(restoredConfig, configDir);
		}
	}

	/** Efface les fichiers de Valcombe de config/ que les archives contiennent (jamais les fichiers privés). */
	private void clearManagedConfig() throws IOException {
		Path valcombe = configDir.resolve(BackupContents.VALCOMBE);
		if (Files.isDirectory(valcombe, LinkOption.NOFOLLOW_LINKS)) {
			Files.walkFileTree(valcombe, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					String name = BackupContents.CONFIG + BackupContents.VALCOMBE + "/" + BackupContents.relative(valcombe, file);
					if (BackupContents.managed(name)) {
						Files.delete(file);
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
					if (e != null) {
						throw e;
					}
					if (!dir.equals(valcombe)) {
						try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
							if (!ds.iterator().hasNext()) {
								Files.delete(dir);
							}
						}
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}
		if (Files.isDirectory(configDir)) {
			List<Path> tops = new ArrayList<>();
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(configDir, p -> BackupContents.configTop(p.getFileName().toString()))) {
				ds.forEach(tops::add);
			}
			for (Path p : tops) {
				deleteTree(p);
			}
		}
	}

	/**
	 * Déballe les fichiers d'une archive qui font partie d'une sauvegarde (voir {@link BackupContents#managed}) ; le
	 * reste est ignoré. La somme de contrôle de chaque fichier est vérifiée : une archive abîmée lève une IOException.
	 */
	static void extract(Path zip, Path dest) throws IOException {
		Path root = dest.toAbsolutePath().normalize();
		Files.createDirectories(root);
		try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip), 64 * 1024),
				StandardCharsets.UTF_8)) {
			ZipEntry e;
			while ((e = in.getNextEntry()) != null) {
				String name = e.getName();
				if (e.isDirectory() || name.equals(BackupManifest.ENTRY) || !BackupContents.managed(name)) {
					continue;
				}
				Path out;
				try {
					out = root.resolve(name).normalize();
				} catch (InvalidPathException x) {
					throw new IOException("nom de fichier impossible ici : " + name);
				}
				if (!out.startsWith(root) || out.equals(root)) {
					throw new IOException("chemin interdit dans l'archive : " + name);
				}
				Files.createDirectories(out.getParent());
				Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
				if (e.getLastModifiedTime() != null) {
					Files.setLastModifiedTime(out, e.getLastModifiedTime());
				}
			}
		}
	}

	/**
	 * Le dossier du monde : celui que le serveur a relevé en posant la demande (relatif à son dossier) s'il est
	 * valable, sinon {@code level-name} de server.properties ({@code world} par défaut). Jamais hors du dossier du
	 * serveur, jamais le dossier du serveur lui-même.
	 */
	public static Path worldDir(Path serverDir, String fromRequest) {
		Path base = serverDir.toAbsolutePath().normalize();
		for (String candidate : new String[] {fromRequest, levelName(base), "world"}) {
			if (candidate == null || candidate.isBlank()) {
				continue;
			}
			try {
				Path p = base.resolve(candidate).normalize();
				if (p.startsWith(base) && !p.equals(base)) {
					return p;
				}
			} catch (InvalidPathException e) {
				// nom impossible : on passe au suivant
			}
		}
		return base.resolve("world");
	}

	/** Nom du monde dans server.properties ({@code level-name}), ou null. */
	static String levelName(Path serverDir) {
		Path f = serverDir.resolve("server.properties");
		if (!Files.isRegularFile(f)) {
			return null;
		}
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			p.load(r);
		} catch (IOException | IllegalArgumentException e) {
			try (InputStream in = Files.newInputStream(f)) {
				p.load(in); // ancien fichier en ISO-8859-1
			} catch (IOException | IllegalArgumentException x) {
				return null;
			}
		}
		String v = p.getProperty("level-name");
		return v == null ? null : v.strip();
	}

	/** Compte rendu de la dernière restauration, s'il y en a un. */
	public static Optional<Report> lastReport(Path backupsDir) {
		Path f = backupsDir.resolve(REPORT);
		if (!Files.isRegularFile(f)) {
			return Optional.empty();
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
			if (!root.isJsonObject()) {
				return Optional.empty();
			}
			JsonObject o = root.getAsJsonObject();
			return Optional.of(new Report(str(o, "nom"), str(o, "demandeLe"), str(o, "faiteLe"),
				o.has("ok") && o.get("ok").isJsonPrimitive() && o.get("ok").getAsBoolean(), str(o, "message"), str(o, "securite")));
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	// ------------------------------------------------------------------
	// Fichiers
	// ------------------------------------------------------------------

	/** Déplace un dossier ; s'il ne peut pas l'être d'un coup (autre disque...), il est copié puis effacé. */
	static void moveTree(Path from, Path to) throws IOException {
		Files.createDirectories(to.getParent());
		try {
			Files.move(from, to);
		} catch (IOException e) {
			deleteTree(to);
			copyTree(from, to);
			deleteTree(from);
		}
	}

	/** Copie un dossier dans un autre (les fichiers déjà là sont remplacés), sans suivre les liens. */
	static void copyTree(Path from, Path to) throws IOException {
		Files.walkFileTree(from, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(to.resolve(from.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (attrs.isRegularFile()) {
					Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.COPY_ATTRIBUTES);
				}
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Efface un fichier ou un dossier et tout ce qu'il contient (un lien est effacé, pas ce qu'il désigne). */
	static void deleteTree(Path p) throws IOException {
		if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
			Files.delete(p);
			return;
		}
		Files.walkFileTree(p, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
				if (e != null) {
					throw e;
				}
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static void deleteQuietly(Path p) {
		try {
			deleteTree(p);
		} catch (IOException e) {
			LOG.warn("Dossier de travail impossible à effacer ({}) : {}", p, e.getMessage());
		}
	}
}
