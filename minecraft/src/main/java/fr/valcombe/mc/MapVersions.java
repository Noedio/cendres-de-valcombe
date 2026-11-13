package fr.valcombe.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.valcombe.rules.GameException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Les versions des cartes de la bibliothèque : à chaque enregistrement fait depuis un atelier carte (à la main,
 * automatiquement, à la fermeture de l'atelier, à l'arrêt du serveur...), la carte telle qu'elle vient d'être rangée
 * est copiée à part. « Revenir à la version du 13/11 à 14h05 » la remet dans la bibliothèque.
 * <p>
 * <b>Rangement</b> : {@code creations/cartes/versions/<carte>/<date>/} (la date en {@code yyyyMMdd-HHmmss-SSS}),
 * avec les trois fichiers de la carte ({@code carte.json}, {@code carte.nbt}, {@code carte.png} s'il y a un aperçu)
 * et la fiche de la version ({@code version.json} : date, sorte, nom, trônes, maisons, salle). La bibliothèque ne voit
 * pas ce dossier : elle ne lit que les fichiers {@code .json} rangés à côté de lui.
 * <p>
 * <b>Nombre borné</b> : les versions prises sans qu'on le demande ({@link Kind#automatic()}) et les autres sont comptées à
 * part ; au-delà de {@link #KEEP_AUTO} et de {@link #KEEP_MANUAL}, les plus anciennes de chaque groupe sont effacées
 * ({@link #stale}). Une rafale d'enregistrements automatiques n'emporte donc jamais une version demandée.
 * <p>
 * Cette classe ne connaît pas Minecraft (vérifiée par les tests JUnit). Ses méthodes sont sûres entre fils d'exécution.
 */
public final class MapVersions {
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("valcombe");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/** Versions automatiques gardées par carte. */
	public static final int KEEP_AUTO = 10;
	/** Versions demandées gardées par carte. */
	public static final int KEEP_MANUAL = 20;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("dd/MM 'à' HH'h'mm", Locale.FRENCH)
		.withZone(ZoneId.systemDefault());
	private static final String ID = "[0-9]{8}-[0-9]{6}-[0-9]{3}(-[0-9]{1,3})?";
	private static final String MAP_ID = "[a-z0-9-]{2,30}";

	/** Pourquoi une version a été prise. */
	public enum Kind {
		/** « Enregistrer » (bouton, commande, Atelier web). */
		MANUELLE("enregistrée", false),
		/** Enregistrement automatique, à intervalle régulier. */
		AUTO("automatique", true),
		/** À la fermeture de l'atelier. */
		FERMETURE("à la fermeture de l'atelier", false),
		/** À l'arrêt du serveur. */
		ARRET("à l'arrêt du serveur", true),
		/** Juste avant un retour à une version plus ancienne : ce retour se défait ainsi lui aussi. */
		AVANT_RETOUR("avant un retour en arrière", true),
		/** Juste avant d'envoyer la carte à une partie. */
		ENVOI("avant l'envoi à la partie", false);

		private final String label;
		private final boolean automatic;

		Kind(String label, boolean automatic) {
			this.label = label;
			this.automatic = automatic;
		}

		/** Ce qu'on en dit aux joueurs : « automatique », « à la fermeture de l'atelier »... */
		public String label() {
			return label;
		}

		/** Prise sans qu'on le demande : elle compte parmi les {@link #KEEP_AUTO} versions automatiques. */
		public boolean automatic() {
			return automatic;
		}

		static Kind parse(String s) {
			for (Kind k : values()) {
				if (k.name().equalsIgnoreCase(s)) {
					return k;
				}
			}
			return MANUELLE;
		}
	}

	/**
	 * Une version gardée.
	 *
	 * @param id     son nom de dossier (la date) : c'est ce qu'on donne pour y revenir
	 * @param date   quand elle a été prise
	 * @param kind   pourquoi
	 * @param nom    le nom de la carte à ce moment
	 * @param seats  ses trônes
	 * @param houses ses maisons
	 * @param room   avec une salle de convocation
	 */
	public record Version(String id, Instant date, Kind kind, String nom, int seats, int houses, boolean room) {
		/** « 13/11 à 14h05 » : la date telle qu'on la montre. */
		public String shown() {
			return SHOWN.format(date);
		}

		/** « version du 13/11 à 14h05 (automatique), 12 trônes, 12 maisons ». */
		public String describe() {
			return "version du " + shown() + " (" + kind.label() + "), " + MapTally.plural(seats, "trône", "trônes") + ", "
				+ MapTally.plural(houses, "maison", "maisons");
		}
	}

	private final Path library;
	private final Path root;
	private final Clock clock;
	/** Les versions déjà lues, par carte (le dossier n'est relu qu'après un changement). */
	private final Map<String, List<Version>> cache = new HashMap<>();

	/** Les versions des cartes de ce dossier de bibliothèque ({@code creations/cartes}). */
	public MapVersions(Path library) {
		this(library, Clock.systemDefaultZone());
	}

	/** Pour les tests : l'horloge qui date les versions. */
	MapVersions(Path library, Clock clock) {
		this.library = library;
		this.root = library.resolve("versions");
		this.clock = clock;
	}

	private static void checkMap(String mapId) {
		if (mapId == null || !mapId.matches(MAP_ID)) {
			throw new GameException("Identifiant de carte : 2 à 30 lettres minuscules, chiffres ou tirets.");
		}
	}

	private Path dir(String mapId) {
		checkMap(mapId); // jamais de chemin fabriqué avec un identifiant libre
		return root.resolve(mapId);
	}

	// ------------------------------------------------------------------
	// Garder une version
	// ------------------------------------------------------------------

	/**
	 * Copie à part la carte mapId telle qu'elle est maintenant dans la bibliothèque (ses trois fichiers), sous la date
	 * d'aujourd'hui, puis efface les plus anciennes versions de trop. Une GameException si la carte n'y est pas.
	 */
	public synchronized Version record(String mapId, Kind kind) {
		Path dir = dir(mapId);
		Path json = library.resolve(mapId + ".json");
		Path nbt = library.resolve(mapId + ".nbt");
		if (!Files.exists(json) || !Files.exists(nbt)) {
			throw new GameException("La carte « " + mapId + " » n'est pas dans la bibliothèque : aucune version à garder.");
		}
		Instant now = Instant.now(clock);
		String base = STAMP.format(now);
		String id = base;
		for (int n = 2; Files.exists(dir.resolve(id)); n++) {
			id = base + "-" + n;
		}
		Path to = dir.resolve(id);
		try {
			Files.createDirectories(to);
			Files.copy(json, to.resolve("carte.json"), StandardCopyOption.REPLACE_EXISTING);
			Files.copy(nbt, to.resolve("carte.nbt"), StandardCopyOption.REPLACE_EXISTING);
			Path png = library.resolve(mapId + ".png");
			if (Files.exists(png)) {
				Files.copy(png, to.resolve("carte.png"), StandardCopyOption.REPLACE_EXISTING);
			}
			JsonObject def = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject meta = new JsonObject();
			meta.addProperty("date", now.toString());
			meta.addProperty("sorte", kind.name());
			meta.addProperty("nom", def.has("nom") ? def.get("nom").getAsString() : mapId);
			meta.addProperty("trones", def.has("sieges") && def.get("sieges").isJsonArray() ? def.getAsJsonArray("sieges").size() : 0);
			meta.addProperty("maisons", def.has("logis") && def.get("logis").isJsonArray() ? def.getAsJsonArray("logis").size() : 0);
			meta.addProperty("salle", def.has("convocation") && def.get("convocation").isJsonArray());
			Files.writeString(to.resolve("version.json"), GSON.toJson(meta), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			deleteTree(to);
			throw new GameException("Version de la carte non gardée : " + e.getMessage());
		}
		cache.remove(mapId);
		prune(mapId);
		return find(mapId, id);
	}

	/** Efface les versions de trop de cette carte (voir {@link #stale}) ; renvoie combien. */
	public synchronized int prune(String mapId) {
		List<Version> gone = stale(list(mapId), KEEP_AUTO, KEEP_MANUAL);
		for (Version v : gone) {
			deleteTree(dir(mapId).resolve(v.id()));
		}
		if (!gone.isEmpty()) {
			cache.remove(mapId);
		}
		return gone.size();
	}

	/**
	 * Parmi ces versions, celles à effacer : les plus anciennes au-delà de keepAuto versions automatiques, et au-delà de
	 * keepManual versions demandées. Les deux groupes sont comptés à part.
	 */
	static List<Version> stale(List<Version> versions, int keepAuto, int keepManual) {
		List<Version> sorted = new ArrayList<>(versions);
		sorted.sort(Comparator.comparing(Version::date).thenComparing(Version::id).reversed()); // la plus récente d'abord
		List<Version> out = new ArrayList<>();
		int auto = 0;
		int manual = 0;
		for (Version v : sorted) {
			boolean keep = v.kind().automatic() ? ++auto <= keepAuto : ++manual <= keepManual;
			if (!keep) {
				out.add(v);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------
	// Lire
	// ------------------------------------------------------------------

	/** Les versions de cette carte, la plus récente d'abord (vide s'il n'y en a pas). */
	public synchronized List<Version> list(String mapId) {
		List<Version> known = cache.get(mapId);
		if (known != null) {
			return known;
		}
		Path dir = dir(mapId);
		List<Version> out = new ArrayList<>();
		if (Files.isDirectory(dir)) {
			try (Stream<Path> s = Files.list(dir)) {
				for (Path p : s.filter(Files::isDirectory).toList()) {
					Version v = read(p);
					if (v != null) {
						out.add(v);
					}
				}
			} catch (IOException e) {
				LOG.warn("Versions de la carte {} illisibles : {}", mapId, e.getMessage());
			}
		}
		out.sort(Comparator.comparing(Version::date).thenComparing(Version::id).reversed());
		List<Version> fixed = List.copyOf(out);
		cache.put(mapId, fixed);
		return fixed;
	}

	/** Une version de cette carte, ou null. */
	public Version find(String mapId, String versionId) {
		if (versionId == null) {
			return null;
		}
		for (Version v : list(mapId)) {
			if (v.id().equals(versionId.strip())) {
				return v;
			}
		}
		return null;
	}

	/** La version la plus récente, ou null. */
	public Version last(String mapId) {
		List<Version> l = list(mapId);
		return l.isEmpty() ? null : l.get(0);
	}

	private static Version read(Path dir) {
		String id = dir.getFileName().toString();
		if (!id.matches(ID) || !Files.exists(dir.resolve("carte.json")) || !Files.exists(dir.resolve("carte.nbt"))) {
			return null;
		}
		try {
			JsonObject m = JsonParser.parseString(Files.readString(dir.resolve("version.json"), StandardCharsets.UTF_8)).getAsJsonObject();
			Instant date;
			try {
				date = Instant.parse(m.get("date").getAsString());
			} catch (RuntimeException e) { // date absente ou illisible : celle du dossier
				date = Files.getLastModifiedTime(dir).toInstant();
			}
			return new Version(id, date, Kind.parse(m.has("sorte") ? m.get("sorte").getAsString() : ""),
				m.has("nom") ? m.get("nom").getAsString() : "", m.has("trones") ? m.get("trones").getAsInt() : 0,
				m.has("maisons") ? m.get("maisons").getAsInt() : 0, m.has("salle") && m.get("salle").getAsBoolean());
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	// ------------------------------------------------------------------
	// Revenir à une version, oublier
	// ------------------------------------------------------------------

	/**
	 * Remet la version versionId de la carte mapId dans la bibliothèque, à la place de la carte actuelle (ses trois
	 * fichiers : sans aperçu dans la version, l'aperçu actuel est retiré). La version, elle, reste gardée.
	 */
	public synchronized Version restore(String mapId, String versionId) {
		Version v = find(mapId, versionId);
		if (v == null) {
			throw new GameException("Aucune version « " + versionId + " » pour la carte « " + mapId + " ». La liste : "
				+ "/vc atelier carte versions.");
		}
		Path from = dir(mapId).resolve(v.id());
		try {
			Files.createDirectories(library);
			Files.copy(from.resolve("carte.nbt"), library.resolve(mapId + ".nbt"), StandardCopyOption.REPLACE_EXISTING);
			if (Files.exists(from.resolve("carte.png"))) {
				Files.copy(from.resolve("carte.png"), library.resolve(mapId + ".png"), StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.deleteIfExists(library.resolve(mapId + ".png"));
			}
			// La fiche en dernier : la bibliothèque ne voit la carte qu'avec sa fiche (et la relit avec ses nouveaux blocs).
			Files.copy(from.resolve("carte.json"), library.resolve(mapId + ".json"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new GameException("Retour à la " + v.describe() + " impossible : " + e.getMessage());
		}
		return v;
	}

	/** Efface toutes les versions de cette carte (la carte quitte la bibliothèque). */
	public synchronized void forget(String mapId) {
		deleteTree(dir(mapId));
		cache.remove(mapId);
	}

	/** Efface ce dossier et tout ce qu'il contient (une erreur est notée dans le journal, sans plus). */
	private static void deleteTree(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(f);
			}
		} catch (IOException | RuntimeException e) {
			LOG.warn("Dossier de versions {} pas entièrement effacé : {}", dir, e.getMessage());
		}
	}
}
