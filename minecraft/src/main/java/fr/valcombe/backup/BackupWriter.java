package fr.valcombe.backup;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Écrit une archive de sauvegarde (zip) à partir d'un {@linkplain BackupContents#scan relevé}.
 *
 * <p>L'archive s'écrit d'abord à côté, sous le nom {@code <nom>.zip.partiel}, puis prend son vrai nom d'un coup :
 * une archive qui porte un nom de sauvegarde est toujours complète. Une écriture interrompue (arrêt du serveur,
 * disque plein) ne laisse qu'un {@code .partiel}, effacé tout de suite ou au démarrage suivant.
 *
 * <p>Compression rapide ({@link Deflater#BEST_SPEED}) : les fichiers de région de Minecraft sont déjà compressés,
 * compresser plus fort coûterait beaucoup de temps de calcul pour presque rien. La fiche
 * ({@link BackupManifest#ENTRY}) est la dernière entrée de l'archive.
 */
public final class BackupWriter {
	/** Suffixe d'une archive en cours d'écriture. */
	public static final String PARTIAL = ".partiel";
	private static final int BUFFER = 64 * 1024;

	/** Avancement de l'écriture : fichiers et octets déjà copiés. */
	@FunctionalInterface
	public interface Progress {
		void update(int files, long bytes);
	}

	/** Écriture abandonnée à la demande (arrêt du serveur) : l'archive partielle est effacée. */
	public static final class Cancelled extends IOException {
		public Cancelled() {
			super("sauvegarde interrompue");
		}
	}

	/**
	 * Archive écrite.
	 *
	 * @param file    l'archive
	 * @param files   fichiers copiés
	 * @param bytes   octets copiés (avant compression)
	 * @param size    taille de l'archive
	 * @param skipped fichiers laissés de côté (disparus ou illisibles pendant la copie, ou déjà au relevé)
	 */
	public record Result(Path file, int files, long bytes, long size, List<String> skipped) {
	}

	private BackupWriter() {
	}

	/**
	 * Écrit l'archive {@code target}. La fiche est complétée (fichiers, octets, fichiers ignorés) puis rangée dans
	 * l'archive. {@code cancelled} est consulté entre deux fichiers et pendant la copie des gros fichiers.
	 */
	public static Result write(Path target, BackupContents.Scan scan, BackupManifest manifest, Progress progress,
			BooleanSupplier cancelled) throws IOException {
		Files.createDirectories(target.getParent());
		Path partial = target.resolveSibling(target.getFileName() + PARTIAL);
		List<String> skipped = new ArrayList<>(scan.skipped());
		int files = 0;
		long bytes = 0;
		byte[] buffer = new byte[BUFFER];
		try {
			try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(partial), BUFFER),
					StandardCharsets.UTF_8)) {
				zip.setLevel(Deflater.BEST_SPEED);
				for (BackupContents.Entry e : scan.entries()) {
					if (cancelled.getAsBoolean()) {
						throw new Cancelled();
					}
					InputStream in;
					try {
						in = Files.newInputStream(e.file());
					} catch (NoSuchFileException x) {
						skipped.add(e.name() + " (disparu pendant la copie)");
						continue;
					} catch (AccessDeniedException x) {
						skipped.add(e.name() + " (illisible)");
						continue;
					}
					try (in) {
						ZipEntry ze = new ZipEntry(e.name());
						ze.setLastModifiedTime(e.modified());
						zip.putNextEntry(ze);
						long copied = 0;
						int n;
						while ((n = in.read(buffer)) > 0) {
							zip.write(buffer, 0, n);
							copied += n;
							// Un gros fichier ne doit pas retarder l'arrêt du serveur : on regarde à chaque mégaoctet.
							if ((copied & 0xFFFFF) < n && cancelled.getAsBoolean()) {
								throw new Cancelled();
							}
						}
						zip.closeEntry();
						files++;
						bytes += copied;
						progress.update(files, bytes);
					}
				}
				manifest.fichiers = files;
				manifest.octets = bytes;
				manifest.ignores = List.copyOf(skipped);
				zip.putNextEntry(new ZipEntry(BackupManifest.ENTRY));
				zip.write(manifest.toJson().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
			move(partial, target);
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(partial);
			throw e;
		}
		return new Result(target, files, bytes, Files.size(target), List.copyOf(skipped));
	}

	/** Met en place d'un coup (ou, sur un système qui ne le sait pas, par un simple déplacement). */
	static void move(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to);
		}
	}
}
