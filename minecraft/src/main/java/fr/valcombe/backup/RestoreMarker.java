package fr.valcombe.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Demande de restauration : le fichier {@code config/valcombe/restaurer.json}.
 *
 * <p>Un serveur qui tourne garde ses mondes ouverts : on ne peut pas remplacer leurs fichiers sous ses pieds. La
 * restauration est donc <b>prévue</b> : ce fichier dit quelle archive restaurer, et le mod l'applique au démarrage
 * suivant, très tôt, avant que Minecraft n'ouvre le monde (voir {@link Restorer}). Le fichier est retiré dès qu'il
 * a été lu, que la restauration réussisse ou non : une archive qui poserait problème ne bloque jamais le serveur
 * dans une boucle de redémarrages.
 *
 * <pre>{"nom": "valcombe-2026-11-13_09-12-44-auto.zip", "demandeLe": "2026-11-13T10:02:11Z", "par": "Alice", "monde": "world"}</pre>
 * {@code monde} : le dossier du monde, relatif au dossier du serveur, relevé par le serveur qui tournait.
 */
public final class RestoreMarker {
	public static final String FILE_NAME = "restaurer.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/**
	 * Une demande.
	 *
	 * @param nom       l'archive à restaurer (nom dans le dossier des sauvegardes)
	 * @param demandeLe moment de la demande
	 * @param par       qui l'a demandée
	 * @param monde     dossier du monde relatif au dossier du serveur, ou null (alors {@code level-name} de server.properties)
	 */
	public record Request(String nom, Instant demandeLe, String par, String monde) {
	}

	private RestoreMarker() {
	}

	public static Path file(Path valcombeDir) {
		return valcombeDir.resolve(FILE_NAME);
	}

	/** Pose la demande (une demande précédente est remplacée). */
	public static void write(Path valcombeDir, Request r) throws IOException {
		if (!BackupName.valid(r.nom())) {
			throw new IllegalArgumentException("Nom de sauvegarde invalide : « " + r.nom() + " ».");
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("nom", r.nom());
		m.put("demandeLe", r.demandeLe().toString());
		m.put("par", r.par() == null ? "" : r.par());
		if (r.monde() != null) {
			m.put("monde", r.monde());
		}
		Files.createDirectories(valcombeDir);
		Path f = file(valcombeDir);
		Path tmp = f.resolveSibling(FILE_NAME + ".tmp");
		Files.writeString(tmp, GSON.toJson(m) + "\n", StandardCharsets.UTF_8);
		Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
	}

	/** La demande en attente, s'il y en a une ; IOException si le fichier est là mais illisible. */
	public static Optional<Request> read(Path valcombeDir) throws IOException {
		Path f = file(valcombeDir);
		if (!Files.exists(f)) {
			return Optional.empty();
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
			if (!root.isJsonObject()) {
				throw new IOException("demande de restauration illisible");
			}
			JsonObject o = root.getAsJsonObject();
			String nom = string(o, "nom");
			if (nom == null || !BackupName.valid(nom)) {
				throw new IOException("demande de restauration sans nom de sauvegarde valable");
			}
			String at = string(o, "demandeLe");
			Instant when = at == null ? Instant.EPOCH : Instant.parse(at);
			return Optional.of(new Request(nom, when, string(o, "par"), string(o, "monde")));
		} catch (RuntimeException e) {
			// JSON fautif ou date illisible
			throw new IOException("demande de restauration illisible : " + e.getMessage(), e);
		}
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
	}

	/** Une demande est-elle en attente ? */
	public static boolean present(Path valcombeDir) {
		return Files.exists(file(valcombeDir));
	}

	/** Retire la demande ; vrai s'il y en avait une. */
	public static boolean clear(Path valcombeDir) throws IOException {
		return Files.deleteIfExists(file(valcombeDir));
	}
}
