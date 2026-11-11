package fr.valcombe.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleDef;
import fr.valcombe.session.ScenarioDef;
import fr.valcombe.session.Session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Les créations de l'Atelier, enregistrées sur le serveur dans config/valcombe/creations :
 * roles/*.json, scenarios/*.json (et cartes/, voir MapStore). Partage : un fichier .valcombe
 * (JSON) qui regroupe rôles, scénarios et cartes.
 */
public final class CreationStore {
	public static final String PACK_FORMAT = "valcombe-creations";
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("valcombe");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path dir;
	private final Session session;
	private final List<RoleDef> roles = new ArrayList<>();
	private final List<ScenarioDef> scenarios = new ArrayList<>();
	private MapStore maps;

	public CreationStore(Path configDir, Session session) {
		this.dir = configDir.resolve("valcombe").resolve("creations");
		this.session = session;
	}

	public void setMaps(MapStore maps) {
		this.maps = maps;
	}

	public Path dir() {
		return dir;
	}

	// ------------------------------------------------------------------
	// Lecture
	// ------------------------------------------------------------------
	public synchronized void load() {
		roles.clear();
		scenarios.clear();
		for (Path f : files("roles")) {
			RoleDef d = read(f, RoleDef.class);
			if (d != null && d.id != null) {
				roles.add(d);
			}
		}
		for (Path f : files("scenarios")) {
			ScenarioDef d = read(f, ScenarioDef.class);
			if (d != null && d.id != null) {
				scenarios.add(d);
			}
		}
		// Deux fichiers sur le même emplacement (copie à la main) : le second en reçoit un autre.
		Set<Integer> used = new HashSet<>();
		for (RoleDef d : roles) {
			if (d.emplacement < 1 || d.emplacement > Role.CUSTOM_SLOTS || !used.add(d.emplacement)) {
				d.emplacement = freeSlot(used);
				used.add(d.emplacement);
			}
		}
		apply();
		LOG.info("Atelier : {} rôle(s) et {} scénario(s) créés", roles.size(), scenarios.size());
	}

	private List<Path> files(String kind) {
		Path d = dir.resolve(kind);
		if (!Files.isDirectory(d)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(d)) {
			return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		} catch (IOException e) {
			LOG.warn("Atelier : dossier illisible {}", d, e);
			return List.of();
		}
	}

	private static <T> T read(Path f, Class<T> type) {
		try {
			return GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), type);
		} catch (IOException | RuntimeException e) {
			LOG.warn("Atelier : fichier illisible {}", f, e);
			return null;
		}
	}

	private void apply() {
		roles.sort(Comparator.comparing(d -> d.id));
		scenarios.sort(Comparator.comparing(d -> d.nom.toLowerCase()));
		Role.defineCustom(roles);
		session.setScenarios(scenarios);
	}

	public synchronized List<RoleDef> roles() {
		return List.copyOf(roles);
	}

	public synchronized List<ScenarioDef> scenarios() {
		return List.copyOf(scenarios);
	}

	// ------------------------------------------------------------------
	// Rôles
	// ------------------------------------------------------------------
	private static int freeSlot(Set<Integer> used) {
		for (int i = 1; i <= Role.CUSTOM_SLOTS; i++) {
			if (!used.contains(i)) {
				return i;
			}
		}
		throw new GameException("Plus de place : " + Role.CUSTOM_SLOTS + " rôles créés au plus. Supprimez-en un.");
	}

	/** Le rôle est-il utilisé par la partie en cours ? */
	private boolean inPlay(String id) {
		var g = session.game();
		if (g == null) {
			return false;
		}
		Role r = Role.parse(id);
		return r != null && (session.script().contains(r)
			|| g.players().stream().anyMatch(p -> p.role() == r || p.believed() == r));
	}

	public synchronized String saveRole(RoleDef d) {
		if (d == null) {
			throw new GameException("Fiche vide.");
		}
		d.id = d.id == null ? "" : d.id.trim().toLowerCase();
		String problem = d.problem();
		if (problem != null) {
			throw new GameException(problem);
		}
		for (String when : List.of(d.premiereNuit, d.autresNuits)) {
			if (when != null && when.contains(":") && Role.parse(when.split(":", 2)[1]) == null) {
				throw new GameException("Réveil : rôle de référence introuvable (" + when + ").");
			}
		}
		if (inPlay(d.id)) {
			throw new GameException("Ce rôle est dans la partie en cours : modifiez-le après la partie.");
		}
		RoleDef old = roles.stream().filter(x -> x.id.equals(d.id)).findFirst().orElse(null);
		Set<Integer> used = new HashSet<>();
		roles.stream().filter(x -> x != old).forEach(x -> used.add(x.emplacement));
		if (old != null) {
			d.emplacement = old.emplacement;
		} else if (d.emplacement < 1 || d.emplacement > Role.CUSTOM_SLOTS || used.contains(d.emplacement)) {
			d.emplacement = freeSlot(used);
		}
		write("roles", d.id, d);
		roles.remove(old);
		roles.add(d);
		apply();
		return old == null ? "Rôle « " + d.nom + " » créé." : "Rôle « " + d.nom + " » modifié.";
	}

	public synchronized String deleteRole(String id) {
		RoleDef old = roles.stream().filter(x -> x.id.equals(id)).findFirst()
			.orElseThrow(() -> new GameException("Rôle inconnu : " + id));
		if (inPlay(id)) {
			throw new GameException("Ce rôle est dans la partie en cours : supprimez-le après la partie.");
		}
		remove("roles", id);
		roles.remove(old);
		apply();
		List<String> users = scenarios.stream().filter(s -> s.roles.contains(id)).map(s -> s.nom).toList();
		return "Rôle « " + old.nom + " » supprimé."
			+ (users.isEmpty() ? "" : " Il manque désormais aux scénarios : " + String.join(", ", users) + ".");
	}

	// ------------------------------------------------------------------
	// Scénarios
	// ------------------------------------------------------------------
	public synchronized String saveScenario(ScenarioDef d) {
		if (d == null) {
			throw new GameException("Fiche vide.");
		}
		d.id = d.id == null ? "" : d.id.trim().toLowerCase();
		if (d.reglages == null) {
			d.reglages = new ScenarioDef.Reglages();
		}
		if (d.regles == null) {
			d.regles = new ScenarioDef.Regles();
		}
		if (d.roles == null) {
			d.roles = new ArrayList<>();
		}
		String problem = d.problem();
		if (problem != null) {
			throw new GameException(problem);
		}
		if (d.carte != null && !d.carte.isBlank() && (maps == null || !maps.exists(d.carte))) {
			throw new GameException("Carte introuvable dans la bibliothèque : " + d.carte + ".");
		}
		ScenarioDef old = scenarios.stream().filter(x -> x.id.equals(d.id)).findFirst().orElse(null);
		write("scenarios", d.id, d);
		scenarios.remove(old);
		scenarios.add(d);
		apply();
		return old == null ? "Scénario « " + d.nom + " » créé." : "Scénario « " + d.nom + " » modifié.";
	}

	public synchronized String deleteScenario(String id) {
		ScenarioDef old = scenarios.stream().filter(x -> x.id.equals(id)).findFirst()
			.orElseThrow(() -> new GameException("Scénario inconnu : " + id));
		remove("scenarios", id);
		scenarios.remove(old);
		apply();
		return "Scénario « " + old.nom + " » supprimé.";
	}

	// ------------------------------------------------------------------
	// Partage : fichiers .valcombe
	// ------------------------------------------------------------------
	/** Regroupe les créations choisies ; un scénario emporte ses rôles créés et sa carte. */
	public synchronized String exportPack(List<String> roleIds, List<String> scenarioIds, List<String> mapIds) {
		Set<String> r = new HashSet<>(roleIds);
		Set<String> m = new HashSet<>(mapIds);
		List<ScenarioDef> sc = scenarios.stream().filter(s -> scenarioIds.contains(s.id)).toList();
		for (ScenarioDef s : sc) {
			s.roles.stream().filter(id -> roles.stream().anyMatch(d -> d.id.equals(id))).forEach(r::add);
			if (s.carte != null && !s.carte.isBlank()) {
				m.add(s.carte);
			}
		}
		JsonObject pack = new JsonObject();
		pack.addProperty("format", PACK_FORMAT);
		pack.addProperty("version", 1);
		pack.add("roles", GSON.toJsonTree(roles.stream().filter(d -> r.contains(d.id)).toList()));
		pack.add("scenarios", GSON.toJsonTree(sc));
		JsonArray cartes = new JsonArray();
		if (maps != null) {
			for (String id : m) {
				JsonObject c = maps.exportMap(id);
				if (c != null) {
					cartes.add(c);
				}
			}
		}
		pack.add("cartes", cartes);
		return GSON.toJson(pack);
	}

	/** Importe un fichier .valcombe ; les créations de même identifiant sont remplacées. */
	public synchronized String importPack(String json) {
		JsonObject pack;
		try {
			pack = JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			throw new GameException("Fichier illisible : ce n'est pas un fichier .valcombe.");
		}
		if (!pack.has("format") || !PACK_FORMAT.equals(pack.get("format").getAsString())) {
			throw new GameException("Ce fichier n'est pas un fichier de créations Valcombe.");
		}
		List<String> done = new ArrayList<>();
		List<String> refused = new ArrayList<>();
		if (pack.has("cartes") && maps != null) {
			for (JsonElement e : pack.getAsJsonArray("cartes")) {
				try {
					done.add("carte « " + maps.importMap(e.getAsJsonObject()) + " »");
				} catch (GameException ex) {
					refused.add(ex.getMessage());
				}
			}
		}
		if (pack.has("roles")) {
			for (JsonElement e : pack.getAsJsonArray("roles")) {
				RoleDef d = GSON.fromJson(e, RoleDef.class);
				try {
					saveRole(d);
					done.add("rôle « " + d.nom + " »");
				} catch (GameException ex) {
					refused.add((d == null ? "?" : d.id) + " : " + ex.getMessage());
				}
			}
		}
		if (pack.has("scenarios")) {
			for (JsonElement e : pack.getAsJsonArray("scenarios")) {
				ScenarioDef d = GSON.fromJson(e, ScenarioDef.class);
				try {
					saveScenario(d);
					done.add("scénario « " + d.nom + " »");
				} catch (GameException ex) {
					refused.add((d == null ? "?" : d.id) + " : " + ex.getMessage());
				}
			}
		}
		String msg = done.isEmpty() ? "Rien d'importé." : "Importé : " + String.join(", ", done) + ".";
		return refused.isEmpty() ? msg : msg + " Refusé : " + String.join(" ; ", refused);
	}

	// ------------------------------------------------------------------
	// Fichiers
	// ------------------------------------------------------------------
	private void write(String kind, String id, Object data) {
		try {
			Path d = dir.resolve(kind);
			Files.createDirectories(d);
			Files.writeString(d.resolve(id + ".json"), GSON.toJson(data), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new GameException("Enregistrement impossible : " + e.getMessage());
		}
	}

	private void remove(String kind, String id) {
		try {
			Files.deleteIfExists(dir.resolve(kind).resolve(id + ".json"));
		} catch (IOException e) {
			throw new GameException("Suppression impossible : " + e.getMessage());
		}
	}
}
