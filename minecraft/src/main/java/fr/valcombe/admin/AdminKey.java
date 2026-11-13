package fr.valcombe.admin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Clé de l'API d'administration, gardée dans {@code config/valcombe/admin.json}.
 *
 * <p>Le fichier contient :
 * <ul>
 *   <li>{@code key} : la clé, 32 octets tirés au hasard (SecureRandom), écrits en 64 chiffres hexadécimaux ;</li>
 *   <li>{@code adminLocalOnly} : vrai par défaut, seules les requêtes venues de la machine elle-même sont acceptées
 *   (un launcher distant passe par un tunnel SSH, ses requêtes arrivent alors de la machine elle-même) ;</li>
 *   <li>{@code enabled} : faux pour couper l'API (pris en compte au prochain démarrage) ;</li>
 *   <li>{@code created} : moment où la clé a été tirée, pour information.</li>
 * </ul>
 * Une clé absente ou mal formée est remplacée par une neuve ; un fichier illisible est mis de côté
 * ({@code admin.json.illisible}) avant d'en écrire un nouveau. Sur Linux, le fichier n'est lisible que par
 * le compte qui fait tourner le serveur.
 */
public final class AdminKey {
	public static final String FILE_NAME = "admin.json";
	/** Une clé écrite à la main est acceptée si elle est assez longue et sans caractère qui gênerait un en-tête HTTP. */
	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{32,128}");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int KEY_BYTES = 32;

	/** Contenu du fichier ; les champs absents gardent ces valeurs par défaut. */
	static final class Stored {
		String key = "";
		boolean adminLocalOnly = true;
		boolean enabled = true;
		String created = "";
	}

	private final Path file;
	private final SecureRandom random;
	private Stored stored;

	public AdminKey(Path valcombeDir) {
		this(valcombeDir, new SecureRandom());
	}

	AdminKey(Path valcombeDir, SecureRandom random) {
		this.file = valcombeDir.resolve(FILE_NAME);
		this.random = random;
	}

	/** Chemin du fichier, pour les messages. */
	public Path file() {
		return file;
	}

	/**
	 * Lit le fichier ; crée une clé neuve s'il n'existe pas encore ou si sa clé n'est pas valable.
	 * Renvoie vrai quand une clé vient d'être tirée (c'est alors qu'on l'affiche dans la console).
	 */
	public synchronized boolean load() throws IOException {
		Stored s = null;
		if (Files.exists(file)) {
			try {
				s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
			} catch (JsonParseException | IOException e) {
				// Fichier abîmé : on le garde de côté pour qu'on puisse y retrouver les réglages à la main.
				Files.move(file, file.resolveSibling(FILE_NAME + ".illisible"), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		if (s == null) {
			s = new Stored();
		}
		stored = s;
		if (s.key == null || !VALID.matcher(s.key).matches()) {
			s.key = generate(random);
			s.created = Instant.now().toString();
			save();
			return true;
		}
		restrict();
		return false;
	}

	/** La clé en service. */
	public synchronized String key() {
		return loaded().key;
	}

	/** Seules les requêtes de la machine elle-même sont-elles acceptées ? */
	public synchronized boolean localOnly() {
		return loaded().adminLocalOnly;
	}

	/** L'API est-elle active ? */
	public synchronized boolean enabled() {
		return loaded().enabled;
	}

	/** Tire une nouvelle clé et l'enregistre : l'ancienne cesse aussitôt de fonctionner. */
	public synchronized String regenerate() throws IOException {
		Stored s = loaded();
		s.key = generate(random);
		s.created = Instant.now().toString();
		save();
		return s.key;
	}

	private Stored loaded() {
		if (stored == null) {
			throw new IllegalStateException("Clé d'administration pas encore lue");
		}
		return stored;
	}

	/** 32 octets au hasard, en hexadécimal (64 caractères). */
	static String generate(SecureRandom random) {
		byte[] b = new byte[KEY_BYTES];
		random.nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	/** Écrit à côté puis met en place d'un coup : une coupure ne laisse jamais un fichier à moitié écrit. */
	private void save() throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
		Files.writeString(tmp, GSON.toJson(stored) + "\n", StandardCharsets.UTF_8);
		restrict(tmp);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void restrict() {
		if (Files.exists(file)) {
			restrict(file);
		}
	}

	/** Sur Linux et macOS : lecture et écriture pour le seul compte du serveur. Ailleurs, rien à faire. */
	private static void restrict(Path f) {
		try {
			Files.setPosixFilePermissions(f, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		} catch (UnsupportedOperationException | IOException e) {
			// système de fichiers sans droits POSIX (Windows) : le dossier du serveur protège déjà le fichier
		}
	}
}
