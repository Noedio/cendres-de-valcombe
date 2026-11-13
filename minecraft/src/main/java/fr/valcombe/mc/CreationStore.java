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
 * roles/*.json, scenarios/*.json (et cartes/, voir MapStore ; structures/, voir StructureStore).
 * Partage : un fichier .valcombe (JSON) qui regroupe rôles, scénarios, cartes et structures.
 */
public final class CreationStore {
	public static final String PACK_FORMAT = "valcombe-creations";
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("valcombe");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path dir;
	private final Session session;
	private final List<RoleDef> roles = new ArrayList<>();
	private final List<ScenarioDef> scenarios = new ArrayList<>();
	/** Groupes de rôles de l'Atelier : nom → identifiants des rôles (intégrés ou créés), pour les ranger. */
	private final java.util.Map<String, List<String>> groups = new java.util.LinkedHashMap<>();
	private MapStore maps;

	public CreationStore(Path configDir, Session session) {
		this.dir = configDir.resolve("valcombe").resolve("creations");
		this.session = session;
	}

	public void setMaps(MapStore maps) {
		this.maps = maps;
	}

	/** Catalogue de structures : elles voyagent dans les packs comme les cartes. */
	private StructureStore structures;

	public void setStructures(StructureStore structures) {
		this.structures = structures;
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
		offerTableScenario();
		groups.clear();
		Path g = dir.resolve("groupes.json");
		if (Files.exists(g)) {
			JsonObject o = read(g, JsonObject.class);
			if (o != null) {
				parseGroups(o).forEach(groups::put);
			}
		}
		session.setRoleGroups(groups);
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

	/**
	 * Scénario « La Table de Valcombe » (les 26 rôles de la liste des joueurs, Conseil de la table, majorité
	 * absolue, victoire du mal majoritaire) : offert une fois. Supprimé, il ne revient pas.
	 */
	private void offerTableScenario() {
		Path marker = dir.resolve(".scenario-table-offert");
		if (Files.exists(marker) || scenarios.stream().anyMatch(s -> "table".equals(s.id))) {
			return;
		}
		ScenarioDef d = new ScenarioDef();
		d.id = "table";
		d.nom = "La Table de Valcombe";
		d.description = "Les règles de notre table : 26 rôles, toutes les accusations puis les votes, majorité absolue.";
		d.ouverture = "Ce soir, les Démons se sont glissés parmi vous. Le village tient tant que le Démon tombe avant "
			+ "qu'ils ne soient les plus nombreux.";
		d.roles = new ArrayList<>(Role.table().stream().map(r -> r.name().toLowerCase(java.util.Locale.ROOT)).toList());
		d.reglages.conseil = true;
		d.regles.majoriteAbsolue = true;
		d.regles.victoireMalMajoritaire = true;
		try {
			write("scenarios", d.id, d);
			Files.writeString(marker, "Scénario de la table offert une fois ; supprimez ce fichier pour le recevoir à nouveau.");
			scenarios.add(d);
		} catch (RuntimeException | IOException e) {
			LOG.warn("Atelier : scénario de la table non créé", e);
		}
	}

	public synchronized java.util.Map<String, List<String>> groups() {
		return new java.util.LinkedHashMap<>(groups);
	}

	/** Remplace tous les groupes de rôles (envoyés en entier par l'Atelier). */
	public synchronized String saveGroups(String json) {
		JsonElement e = JsonParser.parseString(json);
		if (!e.isJsonObject()) {
			throw new GameException("Groupes illisibles.");
		}
		java.util.Map<String, List<String>> next = parseGroups(e.getAsJsonObject());
		storeGroups(next);
		return "Groupes enregistrés (" + next.size() + ").";
	}

	/** Range des rôles dans un groupe (le crée ou le remplace) : depuis l'éditeur de script en jeu. */
	public synchronized String putGroup(String name, java.util.Collection<String> ids) {
		String n = name == null ? "" : name.strip();
		if (n.isEmpty() || n.length() > 40) {
			throw new GameException("Nom de groupe : 1 à 40 caractères.");
		}
		if (ids.isEmpty()) {
			throw new GameException("Le script est vide : rien à ranger dans le groupe.");
		}
		java.util.Map<String, List<String>> next = new java.util.LinkedHashMap<>(groups);
		boolean replaced = next.containsKey(n);
		if (!replaced && next.size() >= 50) {
			throw new GameException("50 groupes au plus : supprimez-en un.");
		}
		JsonObject o = GSON.toJsonTree(next).getAsJsonObject();
		o.add(n, GSON.toJsonTree(List.copyOf(ids)));
		next = parseGroups(o);
		storeGroups(next);
		return "Groupe « " + n + " » " + (replaced ? "remplacé" : "créé") + " : " + next.get(n).size() + " rôles.";
	}

	public synchronized String deleteGroup(String name) {
		String n = name == null ? "" : name.strip();
		if (!groups.containsKey(n)) {
			throw new GameException("Groupe inconnu : " + n);
		}
		java.util.Map<String, List<String>> next = new java.util.LinkedHashMap<>(groups);
		next.remove(n);
		storeGroups(next);
		return "Groupe « " + n + " » supprimé. Les rôles, eux, restent.";
	}

	private void storeGroups(java.util.Map<String, List<String>> next) {
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("groupes.json"), GSON.toJson(next), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new GameException("Enregistrement impossible : " + ex.getMessage());
		}
		groups.clear();
		groups.putAll(next);
		session.setRoleGroups(groups);
	}

	/** Groupes valides : 50 au plus, noms de 1 à 40 caractères, 60 rôles au plus par groupe, sans doublon. */
	private static java.util.Map<String, List<String>> parseGroups(JsonObject o) {
		java.util.Map<String, List<String>> out = new java.util.LinkedHashMap<>();
		for (var entry : o.entrySet()) {
			String name = entry.getKey().strip();
			if (name.isEmpty() || name.length() > 40 || out.size() >= 50 || !entry.getValue().isJsonArray()) {
				continue;
			}
			List<String> ids = new ArrayList<>();
			for (JsonElement id : entry.getValue().getAsJsonArray()) {
				if (id.isJsonPrimitive() && ids.size() < 60) {
					String s = id.getAsString().strip().toLowerCase(java.util.Locale.ROOT);
					if (s.matches("[a-z0-9_-]{1,40}") && !ids.contains(s)) {
						ids.add(s);
					}
				}
			}
			out.put(name, ids);
		}
		return out;
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
		boolean chosen = session.scenario() != null && session.scenario().id.equals(id);
		if (chosen && session.game() != null) {
			throw new GameException("Ce scénario est celui de la partie en cours : supprimez-le après la partie.");
		}
		remove("scenarios", id);
		scenarios.remove(old);
		apply();
		// Supprimé alors qu'il était choisi : la prochaine partie ne garde pas un scénario qui n'existe plus.
		session.scenarioDeleted(id);
		return "Scénario « " + old.nom + " » supprimé." + (chosen ? " Il était choisi pour la prochaine partie : plus de scénario." : "");
	}

	// ------------------------------------------------------------------
	// Partage : fichiers .valcombe
	// ------------------------------------------------------------------
	/** Regroupe les créations choisies ; un scénario emporte ses rôles créés et sa carte. */
	public synchronized String exportPack(List<String> roleIds, List<String> scenarioIds, List<String> mapIds) {
		return exportPack(roleIds, scenarioIds, mapIds, List.of());
	}

	/** Comme ci-dessus, avec des structures du catalogue (celles de l'utilisateur : les fournies, chacun les a). */
	public synchronized String exportPack(List<String> roleIds, List<String> scenarioIds, List<String> mapIds,
			List<String> structureIds) {
		return exportPack(roleIds, scenarioIds, mapIds, structureIds, List.of());
	}

	/** Avec des groupes de rôles : un groupe emporte ses rôles créés, pour qu'il soit complet chez l'autre. */
	public synchronized String exportPack(List<String> roleIds, List<String> scenarioIds, List<String> mapIds,
			List<String> structureIds, List<String> groupNames) {
		Set<String> r = new HashSet<>(roleIds);
		JsonObject groupes = new JsonObject();
		for (String n : new java.util.LinkedHashSet<>(groupNames)) {
			List<String> ids = groups.get(n);
			if (ids != null) {
				groupes.add(n, GSON.toJsonTree(ids));
				ids.stream().filter(id -> roles.stream().anyMatch(d -> d.id.equals(id))).forEach(r::add);
			}
		}
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
		JsonArray built = new JsonArray();
		if (structures != null) {
			for (String id : new java.util.LinkedHashSet<>(structureIds)) {
				JsonObject s = structures.exportStructure(id);
				if (s != null) {
					built.add(s);
				}
			}
		}
		pack.add("structures", built);
		pack.add("groupes", groupes);
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
		if (pack.has("structures") && structures != null) {
			for (JsonElement e : pack.getAsJsonArray("structures")) {
				try {
					done.add("structure « " + structures.importStructure(e.getAsJsonObject()) + " »");
				} catch (GameException ex) {
					refused.add(ex.getMessage());
				} catch (RuntimeException ex) {
					refused.add("une structure illisible");
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
		// Groupes après les rôles : ceux du fichier remplacent ceux de même nom, les autres restent.
		if (pack.has("groupes") && pack.get("groupes").isJsonObject()) {
			java.util.Map<String, List<String>> incoming = parseGroups(pack.getAsJsonObject("groupes"));
			java.util.Map<String, List<String>> next = new java.util.LinkedHashMap<>(groups);
			for (var e : incoming.entrySet()) {
				if (next.containsKey(e.getKey()) || next.size() < 50) {
					next.put(e.getKey(), e.getValue());
					done.add("groupe « " + e.getKey() + " »");
				} else {
					refused.add("groupe « " + e.getKey() + " » : 50 groupes au plus");
				}
			}
			if (!incoming.isEmpty()) {
				try {
					storeGroups(next);
				} catch (GameException ex) {
					refused.add(ex.getMessage());
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
