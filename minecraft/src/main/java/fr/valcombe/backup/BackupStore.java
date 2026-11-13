package fr.valcombe.backup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le dossier des sauvegardes ({@code backups/} dans le dossier du serveur) : liste des archives, noms des
 * nouvelles, rotation (les plus anciennes effacées au-delà du nombre gardé) et suppression.
 *
 * <p>Seuls les fichiers dont le nom suit le modèle de {@link BackupName} sont des sauvegardes : tout le reste du
 * dossier (archives en cours d'écriture, dossiers de travail d'une restauration, fichiers déposés à la main) est
 * ignoré par la liste et jamais effacé par la rotation. Sûr entre fils d'exécution.
 */
public final class BackupStore {
	/** Nom du dossier des sauvegardes dans le dossier du serveur. */
	public static final String DIR_NAME = "backups";

	/**
	 * Une archive du dossier.
	 *
	 * @param name     nom du fichier (son identifiant)
	 * @param parsed   ce que dit son nom : moment, sorte
	 * @param size     taille de l'archive
	 * @param modified dernière écriture (la fin de la sauvegarde)
	 */
	public record Backup(String name, BackupName parsed, long size, Instant modified) {
		public BackupKind kind() {
			return parsed.kind();
		}
	}

	private record Cached(long size, Instant modified, BackupManifest manifest) {
	}

	private final Path dir;
	/** Fiches déjà lues, par nom d'archive (relues si l'archive change de taille ou de date). */
	private final Map<String, Cached> manifests = new ConcurrentHashMap<>();

	public BackupStore(Path dir) {
		this.dir = dir;
	}

	public Path dir() {
		return dir;
	}

	/** Les sauvegardes, de la plus récente à la plus ancienne ; liste vide si le dossier n'existe pas encore. */
	public List<Backup> list() throws IOException {
		List<Backup> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return out;
		}
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
			for (Path p : ds) {
				Optional<BackupName> n = BackupName.parse(p.getFileName().toString());
				if (n.isEmpty()) {
					continue;
				}
				try {
					BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
					if (a.isRegularFile()) {
						out.add(new Backup(p.getFileName().toString(), n.get(), a.size(), a.lastModifiedTime().toInstant()));
					}
				} catch (IOException e) {
					// effacée entre-temps : elle n'est plus là
				}
			}
		}
		out.sort(Comparator.comparing(Backup::parsed).reversed());
		return out;
	}

	/** La plus récente, toutes sortes confondues. */
	public Optional<Backup> latest() throws IOException {
		List<Backup> all = list();
		return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
	}

	/** Cette sauvegarde, si elle existe. Un nom qui ne suit pas le modèle n'en désigne aucune. */
	public Optional<Backup> find(String name) throws IOException {
		if (!BackupName.valid(name)) {
			return Optional.empty();
		}
		for (Backup b : list()) {
			if (b.name().equals(name)) {
				return Optional.of(b);
			}
		}
		return Optional.empty();
	}

	/**
	 * Le fichier de cette sauvegarde (qu'il existe ou non). Lève une IllegalArgumentException si le nom ne suit pas
	 * le modèle : aucun chemin venu de l'extérieur ne peut ainsi désigner un fichier hors du dossier.
	 */
	public Path resolve(String name) {
		if (!BackupName.valid(name)) {
			throw new IllegalArgumentException("Nom de sauvegarde invalide : « " + name + " ».");
		}
		return dir.resolve(name);
	}

	/** Nom libre pour une nouvelle sauvegarde de cette sorte, née à ce moment. */
	public synchronized BackupName newName(BackupKind kind, LocalDateTime now) {
		for (int seq = 1; seq <= BackupName.MAX_SEQ; seq++) {
			BackupName n = new BackupName(now, kind, seq);
			Path f = dir.resolve(n.fileName());
			if (!Files.exists(f) && !Files.exists(f.resolveSibling(n.fileName() + BackupWriter.PARTIAL))) {
				return n;
			}
		}
		throw new IllegalStateException("Trop de sauvegardes dans la même seconde.");
	}

	/**
	 * Les archives que la rotation efface pour n'en garder que {@code keep} parmi celles qu'elle gère (automatiques,
	 * à l'arrêt, avant restauration) : les plus anciennes. Les sauvegardes manuelles ne comptent pas et ne sont
	 * jamais effacées.
	 *
	 * @param all les sauvegardes, dans n'importe quel ordre
	 */
	public static List<Backup> toDelete(List<Backup> all, int keep) {
		List<Backup> rotated = new ArrayList<>();
		for (Backup b : all) {
			if (b.kind().rotated()) {
				rotated.add(b);
			}
		}
		rotated.sort(Comparator.comparing(Backup::parsed).reversed());
		return rotated.size() <= keep ? List.of() : List.copyOf(rotated.subList(Math.max(keep, 0), rotated.size()));
	}

	/** Efface les plus anciennes sauvegardes automatiques au-delà de {@code keep} ; renvoie les noms effacés. */
	public synchronized List<String> rotate(int keep) throws IOException {
		List<String> deleted = new ArrayList<>();
		for (Backup b : toDelete(list(), keep)) {
			if (Files.deleteIfExists(dir.resolve(b.name()))) {
				deleted.add(b.name());
			}
			manifests.remove(b.name());
		}
		return deleted;
	}

	/** Efface une sauvegarde ; faux si elle n'existait pas. */
	public synchronized boolean delete(String name) throws IOException {
		Path f = resolve(name);
		manifests.remove(name);
		return Files.deleteIfExists(f);
	}

	/** La fiche d'une sauvegarde (lue une fois, puis gardée tant que l'archive ne change pas). */
	public BackupManifest manifest(Backup b) throws IOException {
		Cached c = manifests.get(b.name());
		if (c != null && c.size() == b.size() && c.modified().equals(b.modified())) {
			return c.manifest();
		}
		BackupManifest m = BackupManifest.read(dir.resolve(b.name()));
		manifests.put(b.name(), new Cached(b.size(), b.modified(), m));
		return m;
	}

	/** Efface les archives laissées à moitié écrites par un arrêt brutal ; renvoie leur nombre. */
	public int cleanPartials() throws IOException {
		int n = 0;
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + BackupWriter.PARTIAL)) {
			for (Path p : ds) {
				if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && Files.deleteIfExists(p)) {
					n++;
				}
			}
		}
		return n;
	}

	/** Place libre sur le disque du dossier des sauvegardes, en octets (-1 si inconnue). */
	public long freeSpace() {
		try {
			Files.createDirectories(dir);
			return Files.getFileStore(dir).getUsableSpace();
		} catch (IOException e) {
			return -1;
		}
	}
}
