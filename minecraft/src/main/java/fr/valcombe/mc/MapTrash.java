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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * La corbeille de la bibliothèque de cartes ({@code creations/cartes/corbeille}) : une carte supprimée y part avec ses
 * trois fichiers et ses versions gardées, et peut revenir telle quelle. Chaque carte jetée a son dossier,
 * {@code <identifiant>_<date>} : {@code carte.json}, {@code carte.nbt}, {@code carte.png} (s'il y en a un),
 * {@code versions/}, et {@code corbeille.json} (date, nom, trônes, maisons).
 * <p>
 * Une carte reste {@value #KEEP_DAYS} jours à la corbeille ; au-delà de {@value #KEEP_MAX} cartes jetées, les plus
 * anciennes partent pour de bon. Sans Minecraft : vérifiée par les tests JUnit.
 */
public final class MapTrash {
	/** Jours qu'une carte passe à la corbeille avant d'être effacée. */
	static final int KEEP_DAYS = 30;
	/** Cartes gardées à la corbeille, au plus. */
	static final int KEEP_MAX = 40;
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("valcombe");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
	private static final String MAP_ID = "[a-z0-9-]{2,30}";
	/** Un dossier de la corbeille : l'identifiant de la carte, sa date, et un numéro si deux tombent la même seconde. */
	private static final String ENTRY_ID = MAP_ID + "_\\d{8}-\\d{6}(-\\d{1,3})?";

	/**
	 * Une carte à la corbeille.
	 *
	 * @param id     son dossier (ce qu'on passe à {@link #restore})
	 * @param mapId  son identifiant dans la bibliothèque quand elle y était
	 * @param date   quand elle a été jetée
	 */
	public record Entry(String id, String mapId, String nom, Instant date, int seats, int houses) {
	}

	private final Path library;
	private final Path root;
	private final MapVersions versions;
	private final Clock clock;

	MapTrash(Path library, MapVersions versions) {
		this(library, versions, Clock.systemDefaultZone());
	}

	/** Pour les tests : l'horloge qui date les cartes jetées. */
	MapTrash(Path library, MapVersions versions, Clock clock) {
		this.library = library;
		this.root = library.resolve("corbeille");
		this.versions = versions;
		this.clock = clock;
	}

	/** Le dossier de la corbeille (pour le dire à l'opérateur). */
	Path root() {
		return root;
	}

	/** Jette la carte mapId de la bibliothèque : ses fichiers et ses versions partent à la corbeille. */
	synchronized Entry put(String mapId) {
		if (mapId == null || !mapId.matches(MAP_ID)) {
			throw new GameException("Identifiant de carte : 2 à 30 lettres minuscules, chiffres ou tirets.");
		}
		Path json = library.resolve(mapId + ".json");
		Path nbt = library.resolve(mapId + ".nbt");
		if (!Files.exists(json) || !Files.exists(nbt)) {
			throw new GameException("Carte introuvable : " + mapId + ".");
		}
		Instant now = Instant.now(clock);
		String base = mapId + "_" + STAMP.format(now);
		String id = base;
		for (int n = 2; Files.exists(root.resolve(id)); n++) {
			id = base + "-" + n;
		}
		Path to = root.resolve(id);
		JsonObject meta = new JsonObject();
		try {
			JsonObject def = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
			meta.addProperty("carte", mapId);
			meta.addProperty("nom", def.has("nom") ? def.get("nom").getAsString() : mapId);
			meta.addProperty("date", now.toString());
			meta.addProperty("trones", def.has("sieges") && def.get("sieges").isJsonArray() ? def.getAsJsonArray("sieges").size() : 0);
			meta.addProperty("maisons", def.has("logis") && def.get("logis").isJsonArray() ? def.getAsJsonArray("logis").size() : 0);
			Files.createDirectories(to);
			Files.writeString(to.resolve("corbeille.json"), GSON.toJson(meta), StandardCharsets.UTF_8);
			Files.move(nbt, to.resolve("carte.nbt"));
			Path png = library.resolve(mapId + ".png");
			if (Files.exists(png)) {
				Files.move(png, to.resolve("carte.png"));
			}
			// La fiche part en dernier : tant qu'elle est là, la carte est encore dans la bibliothèque.
			Files.move(json, to.resolve("carte.json"));
		} catch (IOException | RuntimeException e) {
			throw new GameException("Mise à la corbeille impossible : " + e.getMessage());
		}
		try {
			versions.moveOut(mapId, to.resolve("versions"));
		} catch (IOException | RuntimeException e) {
			LOG.warn("Versions de la carte {} restées en place : {}", mapId, e.getMessage());
		}
		purge();
		return read(to);
	}

	/** Les cartes à la corbeille, la plus récemment jetée d'abord. */
	synchronized List<Entry> list() {
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		List<Entry> out = new ArrayList<>();
		try (Stream<Path> s = Files.list(root)) {
			for (Path p : s.filter(Files::isDirectory).toList()) {
				Entry e = read(p);
				if (e != null) {
					out.add(e);
				}
			}
		} catch (IOException e) {
			return List.of();
		}
		out.sort(Comparator.comparing(Entry::date).thenComparing(Entry::id).reversed());
		return out;
	}

	/**
	 * Remet une carte de la corbeille dans la bibliothèque. Son identifiant y est repris s'il est libre ; sinon free
	 * (l'identifiant déjà pris) en donne un autre. Renvoie l'identifiant sous lequel elle revient.
	 */
	synchronized String restore(String entryId, Predicate<String> taken, java.util.function.UnaryOperator<String> free) {
		if (entryId == null || !entryId.matches(ENTRY_ID)) {
			throw new GameException("Rien de tel à la corbeille : " + entryId + ".");
		}
		Path from = root.resolve(entryId);
		Entry e = read(from);
		if (e == null) {
			throw new GameException("Rien de tel à la corbeille : " + entryId + ".");
		}
		String mapId = taken.test(e.mapId()) ? free.apply(e.mapId()) : e.mapId();
		try {
			JsonObject def = JsonParser.parseString(Files.readString(from.resolve("carte.json"), StandardCharsets.UTF_8)).getAsJsonObject();
			def.addProperty("id", mapId);
			Files.createDirectories(library);
			Files.move(from.resolve("carte.nbt"), library.resolve(mapId + ".nbt"), StandardCopyOption.REPLACE_EXISTING);
			if (Files.exists(from.resolve("carte.png"))) {
				Files.move(from.resolve("carte.png"), library.resolve(mapId + ".png"), StandardCopyOption.REPLACE_EXISTING);
			}
			Files.writeString(library.resolve(mapId + ".json"), GSON.toJson(def), StandardCharsets.UTF_8);
			Files.deleteIfExists(from.resolve("carte.json"));
		} catch (IOException | RuntimeException ex) {
			throw new GameException("Restauration impossible : " + ex.getMessage());
		}
		try {
			versions.moveIn(from.resolve("versions"), mapId);
		} catch (IOException | RuntimeException ex) {
			LOG.warn("Versions de la carte {} restées à la corbeille : {}", mapId, ex.getMessage());
		}
		deleteTree(from);
		return mapId;
	}

	/** Efface les cartes jetées depuis plus de KEEP_DAYS jours, et les plus anciennes au-delà de KEEP_MAX ; renvoie combien. */
	synchronized int purge() {
		List<Entry> all = list();
		Instant limit = Instant.now(clock).minus(Duration.ofDays(KEEP_DAYS));
		int gone = 0;
		for (int i = 0; i < all.size(); i++) {
			Entry e = all.get(i);
			if (i >= KEEP_MAX || e.date().isBefore(limit)) {
				deleteTree(root.resolve(e.id()));
				gone++;
			}
		}
		return gone;
	}

	/** La carte jetée de ce dossier, ou null s'il ne ressemble pas à une entrée de la corbeille. */
	private static Entry read(Path dir) {
		String id = dir.getFileName().toString();
		if (!id.matches(ENTRY_ID) || !Files.exists(dir.resolve("carte.json")) || !Files.exists(dir.resolve("carte.nbt"))) {
			return null;
		}
		try {
			JsonObject meta = JsonParser.parseString(Files.readString(dir.resolve("corbeille.json"), StandardCharsets.UTF_8)).getAsJsonObject();
			return new Entry(id, meta.get("carte").getAsString(), meta.get("nom").getAsString(), Instant.parse(meta.get("date").getAsString()),
				meta.has("trones") ? meta.get("trones").getAsInt() : 0, meta.has("maisons") ? meta.get("maisons").getAsInt() : 0);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static void deleteTree(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(f);
			}
		} catch (IOException | RuntimeException e) {
			LOG.warn("Dossier de la corbeille {} pas entièrement effacé : {}", dir, e.getMessage());
		}
	}
}
