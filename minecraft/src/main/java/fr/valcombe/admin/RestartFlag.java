package fr.valcombe.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Drapeau de redémarrage : le fichier {@code config/valcombe/redemarrer}, posé juste avant un arrêt propre
 * demandé par POST /admin/redemarrer.
 *
 * <p>Celui qui a lancé le serveur (le launcher, {@code demarrer.sh} ou {@code demarrer.bat}) regarde, quand le
 * serveur s'est arrêté, si ce fichier existe : s'il existe, il l'efface et relance le serveur ; sinon, l'arrêt
 * était voulu et il s'arrête là. Le mod l'efface aussi à chaque démarrage, pour qu'un drapeau oublié ne
 * relance pas un serveur qu'on arrêtera plus tard pour de bon. Le contenu (moment, motif) n'est qu'informatif :
 * seule compte la présence du fichier.
 */
public final class RestartFlag {
	public static final String FILE_NAME = "redemarrer";

	private RestartFlag() {
	}

	public static Path file(Path valcombeDir) {
		return valcombeDir.resolve(FILE_NAME);
	}

	/** Pose le drapeau. */
	public static void write(Path valcombeDir, String reason, Instant at) throws IOException {
		Path f = file(valcombeDir);
		Files.createDirectories(valcombeDir);
		String safe = reason == null ? "" : reason.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("\\p{Cntrl}", " ");
		Files.writeString(f, "{\"demandeLe\": \"" + at + "\", \"motif\": \"" + safe + "\"}\n", StandardCharsets.UTF_8);
	}

	/** Le drapeau est-il posé ? */
	public static boolean present(Path valcombeDir) {
		return Files.exists(file(valcombeDir));
	}

	/** Retire le drapeau ; renvoie vrai s'il était posé. */
	public static boolean clear(Path valcombeDir) throws IOException {
		return Files.deleteIfExists(file(valcombeDir));
	}
}
