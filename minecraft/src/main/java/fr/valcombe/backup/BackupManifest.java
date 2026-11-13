package fr.valcombe.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Fiche d'une sauvegarde, rangée dans l'archive elle-même ({@value #ENTRY}, dernière entrée de l'archive) : ce
 * qu'elle contient, d'où et de quand elle vient. Une archive sans fiche lisible n'est pas une sauvegarde de
 * Valcombe : on ne la restaure pas.
 */
public final class BackupManifest {
	/** Nom de la fiche dans l'archive. */
	public static final String ENTRY = "valcombe-sauvegarde.json";
	/** Version du format des archives ; une archive d'un format plus récent n'est pas restaurée. */
	public static final int FORMAT = 1;
	/** Taille au-delà de laquelle une fiche est jugée abîmée. */
	private static final int MAX_BYTES = 256 * 1024;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

	public int format = FORMAT;
	/** Nom de l'archive. */
	public String nom = "";
	/** Sorte : auto, arret, manuelle, avant-restauration. */
	public String sorte = "";
	/** Moment de la sauvegarde (ISO 8601, avec le décalage horaire du serveur). */
	public String date = "";
	/** Qui l'a demandée : « automatique », « arrêt du serveur », un joueur, « API d'administration (adresse) »... */
	public String par = "";
	/** Version du mod Valcombe et de Minecraft qui l'ont faite. */
	public String versionMod = "";
	public String minecraft = "";
	/** Nom du dossier du monde sur le serveur d'origine (world d'ordinaire). */
	public String dossierMonde = "";
	/** Nombre de fichiers et taille totale avant compression. */
	public int fichiers;
	public long octets;
	/** Noms des mondes de Valcombe inscrits dans mondes.json au moment de la sauvegarde. */
	public List<String> mondes = new ArrayList<>();
	/** Fichiers laissés de côté parce qu'illisibles ou disparus pendant la copie. */
	public List<String> ignores = new ArrayList<>();

	public String toJson() {
		return GSON.toJson(this) + "\n";
	}

	/** Lit une fiche ; lève une IOException si elle est illisible ou d'un format inconnu. */
	public static BackupManifest fromJson(String json) throws IOException {
		BackupManifest m;
		try {
			m = GSON.fromJson(json, BackupManifest.class);
		} catch (JsonParseException e) {
			throw new IOException("fiche de sauvegarde illisible", e);
		}
		if (m == null || m.format < 1) {
			throw new IOException("fiche de sauvegarde illisible");
		}
		if (m.format > FORMAT) {
			throw new IOException("archive d'une version plus récente de Valcombe (format " + m.format + ")");
		}
		if (m.mondes == null) {
			m.mondes = new ArrayList<>();
		}
		if (m.ignores == null) {
			m.ignores = new ArrayList<>();
		}
		return m;
	}

	/** Lit la fiche d'une archive ; lève une IOException si ce n'est pas une archive de sauvegarde de Valcombe. */
	public static BackupManifest read(Path zip) throws IOException {
		try (ZipFile zf = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
			ZipEntry e = zf.getEntry(ENTRY);
			if (e == null) {
				throw new IOException("ce n'est pas une sauvegarde de Valcombe (pas de fiche " + ENTRY + ")");
			}
			try (InputStream in = zf.getInputStream(e)) {
				byte[] b = in.readNBytes(MAX_BYTES + 1);
				if (b.length > MAX_BYTES) {
					throw new IOException("fiche de sauvegarde trop grosse");
				}
				return fromJson(new String(b, StandardCharsets.UTF_8));
			}
		} catch (ZipException e) {
			throw new IOException("archive abîmée : " + e.getMessage(), e);
		}
	}

	/** Noms des mondes inscrits dans ce registre ({@code config/valcombe/mondes.json}) ; liste vide s'il est illisible. */
	public static List<String> worldNames(Path registry) {
		List<String> out = new ArrayList<>();
		try {
			if (!Files.isRegularFile(registry)) {
				return out;
			}
			JsonElement root = JsonParser.parseString(Files.readString(registry, StandardCharsets.UTF_8));
			if (root.isJsonObject() && root.getAsJsonObject().has("mondes") && root.getAsJsonObject().get("mondes").isJsonArray()) {
				for (JsonElement el : root.getAsJsonObject().getAsJsonArray("mondes")) {
					if (el.isJsonObject()) {
						JsonObject o = el.getAsJsonObject();
						JsonElement nom = o.has("nom") ? o.get("nom") : o.get("id");
						if (nom != null && nom.isJsonPrimitive()) {
							out.add(nom.getAsString());
						}
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			// registre illisible : la fiche n'aura simplement pas la liste
		}
		return out;
	}
}
