package fr.valcombe.mc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Predicate;

/**
 * Petit fichier de configuration qu'une coupure de courant ne doit pas abîmer : il est écrit à côté
 * puis mis en place d'un coup, et la version précédente reste en « .bak » pour le cas où la dernière
 * serait tout de même illisible.
 */
final class SafeFile {
	private SafeFile() {
	}

	static Path backup(Path f) {
		return f.resolveSibling(f.getFileName() + ".bak");
	}

	private static void move(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Écrit le fichier sans jamais le laisser à moitié écrit ; l'ancien contenu devient la copie de secours. */
	static void write(Path f, String text) throws IOException {
		Files.createDirectories(f.getParent());
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		Files.writeString(tmp, text, StandardCharsets.UTF_8);
		if (Files.exists(f)) {
			move(f, backup(f));
		}
		move(tmp, f);
	}

	/** Le fichier, ou sa copie de secours, a-t-il déjà existé ? */
	static boolean exists(Path f) {
		return Files.exists(f) || Files.exists(backup(f));
	}

	/**
	 * Le contenu du fichier s'il est lisible (valid), sinon celui de sa copie de secours ;
	 * null si aucun des deux ne l'est.
	 */
	static String read(Path f, Predicate<String> valid) {
		for (Path p : new Path[] {f, backup(f)}) {
			try {
				if (Files.exists(p)) {
					String text = Files.readString(p, StandardCharsets.UTF_8);
					if (valid.test(text)) {
						return text;
					}
				}
			} catch (IOException | RuntimeException e) {
				// illisible : on essaie la copie de secours
			}
		}
		return null;
	}

	static void delete(Path f) throws IOException {
		Files.deleteIfExists(f);
		Files.deleteIfExists(backup(f));
		Files.deleteIfExists(f.resolveSibling(f.getFileName() + ".tmp"));
	}
}
