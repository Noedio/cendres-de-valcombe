package fr.valcombe.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

import fr.valcombe.rules.Ability;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleDef;
import fr.valcombe.session.ScenarioDef;
import fr.valcombe.session.Session;
import fr.valcombe.session.Simulation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * L'Atelier : page web (servie par le serveur de la tablette) pour créer des rôles, des
 * scénarios et gérer les cartes, puis y jouer. Réservé à un opérateur (lien donné par /vc atelier).
 */
final class Atelier {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	/** Emblèmes de la bibliothèque, en plus de ceux des rôles intégrés. */
	static final List<String> EMBLEMS = List.of("e_etoile", "e_lune", "e_soleil", "e_cle", "e_plume", "e_oeil", "e_coeur",
		"e_epee", "e_bouclier", "e_couronne", "e_livre", "e_fiole", "e_rose", "e_cloche", "e_lanterne", "e_masque",
		"e_ancre", "e_corbeau", "e_serpent", "e_main");

	private final Session session;
	private final CreationStore creations;
	private final MapStore maps;
	private final McHost host;
	private MinecraftServer server;

	Atelier(Session session, CreationStore creations, MapStore maps, McHost host) {
		this.session = session;
		this.creations = creations;
		this.maps = maps;
		this.host = host;
	}

	void setServer(MinecraftServer server) {
		this.server = server;
	}

	// ------------------------------------------------------------------
	// Plusieurs opérateurs à la fois : un lien (jeton) chacun, présence, modifications en cours, journal
	// ------------------------------------------------------------------
	private final Map<String, String> tokens = new java.util.concurrent.ConcurrentHashMap<>();
	/** Moment où chaque lien a été donné : un lien cesse de fonctionner douze heures plus tard. */
	private final Map<String, Long> tokenBorn = new java.util.concurrent.ConcurrentHashMap<>();
	private static final long TOKEN_LIFE_MS = 12L * 60 * 60 * 1000;
	private final Map<String, Long> lastSeen = new java.util.concurrent.ConcurrentHashMap<>();
	/** Création en cours de modification (« role:passeur ») → qui la modifie, et quand. */
	private final Map<String, String[]> editing = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Deque<String> activity = new java.util.concurrent.ConcurrentLinkedDeque<>();
	private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

	/** Jeton de l'Atelier pour cet opérateur (créé une fois par démarrage du serveur). */
	String tokenFor(String player) {
		// Un nouveau lien à chaque demande : l'ancien lien de cet opérateur cesse de fonctionner.
		revoke(player);
		byte[] b = new byte[18];
		RANDOM.nextBytes(b);
		String t = java.util.HexFormat.of().formatHex(b);
		tokens.put(t, player);
		tokenBorn.put(t, System.currentTimeMillis());
		return t;
	}

	/** Retire les liens d'un opérateur (ou de tous, si player est null). */
	void revoke(String player) {
		tokens.entrySet().removeIf(e -> {
			boolean gone = player == null || e.getValue().equals(player);
			if (gone) {
				tokenBorn.remove(e.getKey());
			}
			return gone;
		});
	}

	/** Lien de l'hôte : donné par le launcher qui a démarré ce serveur sur le PC de l'hôte. Il vaut tant que le serveur tourne. */
	private volatile String hostToken;
	private volatile String hostName;

	void setHostToken(String name, String token) {
		hostName = name;
		hostToken = token;
	}

	private String ownerOf(HttpExchange ex) {
		String given = ex.getRequestHeaders().getFirst("X-Valcombe-Jeton");
		if (given == null) {
			return null;
		}
		String host = hostToken;
		if (host != null && java.security.MessageDigest.isEqual(host.getBytes(StandardCharsets.UTF_8),
			given.getBytes(StandardCharsets.UTF_8))) {
			return hostName;
		}
		String owner = tokens.get(given);
		Long born = tokenBorn.get(given);
		if (owner == null || born == null || System.currentTimeMillis() - born > TOKEN_LIFE_MS) {
			tokens.remove(given);
			tokenBorn.remove(given);
			return null;
		}
		return owner;
	}

	/** Un opérateur connecté qui a perdu ses droits perd aussi l'Atelier. */
	private boolean stillOperator(String owner) {
		if (hostToken != null && owner.equals(hostName)) {
			return true; // l'hôte du serveur local reste maître de son Atelier
		}
		ServerPlayer p = server.getPlayerList().getPlayerByName(owner);
		return p == null || isOperator(p);
	}

	private void log(String who, String what) {
		String time = java.time.LocalTime.now().withNano(0).toString().substring(0, 5);
		activity.addFirst(time + " — " + who + " " + what);
		while (activity.size() > 15) {
			activity.removeLast();
		}
	}

	/** Une action réussie est notée dans le journal de l'Atelier. */
	private String logged(String who, String result) {
		log(who, ": " + result);
		return result;
	}

	// ------------------------------------------------------------------
	// Pages
	// ------------------------------------------------------------------
	void page(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		if (path.startsWith("/atelier/embleme/")) {
			String name = path.substring("/atelier/embleme/".length()).replace(".png", "");
			if (!name.matches("[a-z_0-9]{2,30}")) {
				send(ex, 404, "text/plain", "Introuvable".getBytes(StandardCharsets.UTF_8));
				return;
			}
			try (InputStream in = Atelier.class.getResourceAsStream("/assets/valcombe/textures/gui/roles/" + name + ".png")) {
				if (in == null) {
					send(ex, 404, "text/plain", "Introuvable".getBytes(StandardCharsets.UTF_8));
					return;
				}
				ex.getResponseHeaders().set("Cache-Control", "max-age=3600");
				send(ex, 200, "image/png", in.readAllBytes());
			}
			return;
		}
		if (!path.equals("/atelier") && !path.equals("/atelier/")) {
			send(ex, 404, "text/plain", "Introuvable".getBytes(StandardCharsets.UTF_8));
			return;
		}
		try (InputStream in = Atelier.class.getResourceAsStream("/valcombe/atelier.html")) {
			if (in == null) {
				send(ex, 500, "text/plain", "Page absente du mod".getBytes(StandardCharsets.UTF_8));
				return;
			}
			send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
		}
	}

	// ------------------------------------------------------------------
	// API
	// ------------------------------------------------------------------
	void api(HttpExchange ex, boolean networkOk) throws IOException {
		String owner = ownerOf(ex);
		if (!networkOk || owner == null) {
			text(ex, 403, "Lien expiré ou réservé : relancez /vc atelier dans le jeu (opérateur).");
			return;
		}
		try {
			if (!onServer(() -> stillOperator(owner), 10)) {
				revoke(owner);
				text(ex, 403, "L'Atelier est réservé aux opérateurs du serveur.");
				return;
			}
		} catch (GameException e) {
			text(ex, 503, e.getMessage());
			return;
		}
		lastSeen.put(owner, System.currentTimeMillis());
		String path = ex.getRequestURI().getPath().substring("/api/atelier/".length());
		boolean post = ex.getRequestMethod().equals("POST");
		String body = post ? new String(ex.getRequestBody().readNBytes(40 * 1024 * 1024), StandardCharsets.UTF_8) : "";
		try {
			switch (path) {
				case "donnees" -> json(ex, onServer(() -> catalogue(owner), 5));
				case "role" -> text(ex, 200, logged(owner, onServer(() -> creations.saveRole(GSON.fromJson(body, RoleDef.class)), 5)));
				case "role/supprimer" -> text(ex, 200, logged(owner, onServer(() -> creations.deleteRole(body.trim()), 5)));
				case "scenario" -> text(ex, 200, logged(owner, onServer(() -> creations.saveScenario(GSON.fromJson(body, ScenarioDef.class)), 5)));
				case "scenario/supprimer" -> text(ex, 200, logged(owner, onServer(() -> creations.deleteScenario(body.trim()), 5)));
				case "edition" -> {
					// Ce que l'opérateur a ouvert dans l'éditeur (« role:x », « scenario:y », ou vide)
					editing.values().removeIf(v -> v[0].equals(owner));
					String key = body.trim();
					if (!key.isEmpty()) {
						editing.put(key + "|" + owner, new String[] {owner, key});
					}
					text(ex, 200, "");
				}
				case "carte/importer" -> {
					JsonObject o = JsonParser.parseString(body).getAsJsonObject();
					byte[] data = java.util.Base64.getDecoder().decode(o.get("donnees").getAsString());
					String file = o.get("fichier").getAsString();
					text(ex, 200, logged(owner, onServer(() -> host.importSchematic(file, data), 180)));
				}
				case "simuler" -> json(ex, simulate(body));
				case "jouer" -> text(ex, 200, onServer(() -> play(JsonParser.parseString(body).getAsJsonObject(), owner), 10));
				case "carte/enregistrer" -> {
					JsonObject o = JsonParser.parseString(body).getAsJsonObject();
					text(ex, 200, logged(owner, onServer(() -> maps.saveCurrent(o.get("id").getAsString(), o.get("nom").getAsString()), 60)));
				}
				case "carte/charger" -> text(ex, 200, logged(owner, onServer(() -> maps.load(body.trim()), 120)));
				case "carte/supprimer" -> text(ex, 200, logged(owner, onServer(() -> maps.delete(body.trim()), 5)));
				case "carte/renommer" -> {
					JsonObject o = JsonParser.parseString(body).getAsJsonObject();
					text(ex, 200, logged(owner, onServer(() -> maps.rename(o.get("id").getAsString(), o.get("nom").getAsString()), 5)));
				}
				case "carte/dupliquer" -> {
					JsonObject o = JsonParser.parseString(body).getAsJsonObject();
					text(ex, 200, logged(owner, onServer(() -> maps.duplicate(o.get("id").getAsString(),
						o.get("nouvelId").getAsString(), o.get("nom").getAsString()), 30)));
				}
				case "carte/annuler" -> text(ex, 200, onServer(host::undoMarker, 5));
				case "carte/ordonner" -> text(ex, 200, logged(owner, onServer(host::sortMarkers, 5)));
				case "carte/repere" -> {
					// Un repère ajouté, déplacé ou retiré sur le plan : x et z sont comptés depuis le centre de la place.
					JsonObject o = JsonParser.parseString(body).getAsJsonObject();
					text(ex, 200, onServer(() -> host.editMarker(o.get("action").getAsString(), o.get("type").getAsString(),
						o.has("index") ? o.get("index").getAsInt() : 0, o.has("x") ? o.get("x").getAsInt() : 0,
						o.has("z") ? o.get("z").getAsInt() : 0), 10));
				}
				case "carte/aller" -> {
					JsonObject o = JsonParser.parseString(body).getAsJsonObject();
					text(ex, 200, onServer(() -> {
						ServerPlayer p = server.getPlayerList().getPlayerByName(owner);
						if (p == null) {
							throw new GameException("Connectez-vous au jeu pour aller voir ce repère.");
						}
						return host.visitMarker(p, o.get("type").getAsString(), o.has("index") ? o.get("index").getAsInt() : 0);
					}, 5));
				}
				case "carte/vue" -> {
					// Vue de dessus : la carte actuelle (corps vide) ou l'aperçu enregistré d'une carte de la bibliothèque.
					String id = body.trim();
					byte[] png = id.isEmpty() ? onServer(host::topView, 30) : maps.preview(id);
					if (png == null) {
						text(ex, 404, "Pas d'aperçu pour cette carte : chargez-la puis enregistrez-la de nouveau.");
					} else {
						send(ex, 200, "image/png", png);
					}
				}
				case "construction" -> text(ex, 200, onServer(() -> {
					ServerPlayer p = server.getPlayerList().getPlayerByName(owner);
					if (p == null) {
						throw new GameException("Connectez-vous au jeu pour construire.");
					}
					return host.toggleConstruction(p);
				}, 5));
				case "carte/generer" -> {
					JsonObject o = JsonParser.parseString(body).getAsJsonObject();
					text(ex, 200, onServer(() -> host.generateVillage(server.getPlayerList().getPlayerByName(owner),
						o.get("places").getAsInt(), o.get("taille").getAsInt(), o.get("theme").getAsString(),
						o.get("maisons").getAsBoolean()), 120));
				}
				case "exporter" -> exportPack(ex);
				case "importer" -> text(ex, 200, onServer(() -> creations.importPack(body), 120));
				default -> text(ex, 404, "Action inconnue.");
			}
		} catch (GameException e) {
			text(ex, 409, e.getMessage());
		} catch (RuntimeException e) {
			ValcombeMod.LOG.warn("Atelier : requête refusée", e);
			text(ex, 400, "Requête illisible : " + e.getMessage());
		}
	}

	/** Exécute sur le fil du serveur (les créations changent le registre des rôles). */
	private <T> T onServer(java.util.function.Supplier<T> task, int seconds) {
		try {
			return server.submit(task).get(seconds, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof GameException ge) {
				throw ge;
			}
			throw new GameException(e.getCause() == null ? "Erreur" : e.getCause().getMessage());
		} catch (InterruptedException | java.util.concurrent.TimeoutException e) {
			throw new GameException("Le serveur est occupé : réessayez dans un instant.");
		}
	}

	/** Tout ce que la page affiche : rôles intégrés et créés, scénarios, cartes, modèles, emblèmes. */
	private String catalogue(String me) {
		Map<String, Object> out = new LinkedHashMap<>();
		long now = System.currentTimeMillis();
		out.put("moi", me);
		out.put("presents", lastSeen.entrySet().stream().filter(e -> now - e.getValue() < 20_000).map(Map.Entry::getKey).sorted().toList());
		Map<String, String> edits = new LinkedHashMap<>();
		for (String[] v : editing.values()) {
			if (now - lastSeen.getOrDefault(v[0], 0L) < 20_000) {
				edits.merge(v[1], v[0], (a, b) -> a + ", " + b);
			}
		}
		out.put("editions", edits);
		out.put("journal", List.copyOf(activity));
		List<Map<String, Object>> builtin = new ArrayList<>();
		for (Role r : Role.all()) {
			if (r.custom()) {
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", r.name().toLowerCase());
			m.put("nom", r.displayName);
			m.put("camp", r.type.name());
			m.put("texte", r.text);
			m.put("embleme", r.icon);
			m.put("premiere", r.firstNight);
			m.put("autres", r.otherNights);
			builtin.add(m);
		}
		out.put("integres", builtin);
		out.put("roles", creations.roles());
		List<Map<String, Object>> sc = new ArrayList<>();
		for (ScenarioDef d : creations.scenarios()) {
			Map<String, Object> m = GSON.fromJson(GSON.toJson(d), Map.class);
			m.put("joueurs", d.playable());
			m.put("probleme", d.problem());
			sc.add(m);
		}
		out.put("scenarios", sc);
		List<Map<String, Object>> cartes = new ArrayList<>();
		for (MapStore.MapDef d : maps.list()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", d.id);
			m.put("nom", d.nom);
			m.put("genere", d.genere);
			m.put("theme", d.theme);
			m.put("sieges", d.sieges.size());
			m.put("maisons", d.logis.size());
			m.put("octets", maps.bytes(d.id));
			// Repères pour le plan vu de dessus : [est, hauteur, sud] depuis le centre de la place.
			m.put("rayon", d.rayon);
			Map<String, Object> plan = new LinkedHashMap<>();
			plan.put("sieges", d.sieges);
			plan.put("maisons", d.logis);
			plan.put("salle", d.convocation);
			m.put("plan", plan);
			cartes.add(m);
		}
		out.put("cartes", cartes);
		Village v = host.village();
		if (v != null) {
			Map<String, Object> plan = new LinkedHashMap<>();
			plan.put("sieges", v.relSeats());
			plan.put("maisons", v.relHouses());
			plan.put("salle", v.relConvocation());
			// Les joueurs connectés, pour se repérer : [nom, est, sud] depuis le centre de la place.
			List<List<Object>> here = new ArrayList<>();
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				here.add(List.of(p.getScoreboardName(), p.getBlockX() - v.center().getX(), p.getBlockZ() - v.center().getZ()));
			}
			plan.put("joueurs", here);
			out.put("carteActuelle", Map.of("genere", v.isGenerated(), "sieges", v.seatCount(), "maisons", v.houseCount(),
				"theme", v.theme(), "rayon", Math.min(200, v.extent()), "plan", plan));
		}
		out.put("chantier", host.busy());
		// Ordre habituel des réveils (première nuit, autres nuits), pour le régler dans un scénario.
		out.put("ordrePremiere", Role.firstNightOrder().stream().map(r -> r.custom() ? r.customId : r.name().toLowerCase()).toList());
		out.put("ordreAutres", Role.otherNightsOrder().stream().map(r -> r.custom() ? r.customId : r.name().toLowerCase()).toList());
		Map<String, List<Integer>> usual = new LinkedHashMap<>();
		for (int n = fr.valcombe.rules.Distribution.MIN_PLAYERS; n <= fr.valcombe.rules.Distribution.MAX_PLAYERS; n++) {
			var c = fr.valcombe.rules.Distribution.counts(n);
			usual.put(String.valueOf(n), List.of(c.citadin(), c.etranger(), c.sbire(), c.demon()));
		}
		out.put("repartition", usual);
		out.put("modeles", Ability.MODELS);
		out.put("modelesIntegres", fr.valcombe.rules.Builtins.templates());
		out.put("cibles", Ability.TARGETS);
		List<String> emb = new ArrayList<>(EMBLEMS);
		Role.all().stream().filter(r -> !r.custom()).forEach(r -> emb.add(r.icon));
		out.put("emblemes", emb);
		out.put("themes", Village.THEMES);
		out.put("partieEnCours", session.game() != null);
		out.put("scenarioActuel", session.scenario() == null ? null : session.scenario().id);
		return GSON.toJson(out);
	}

	/** Simulation : rôles (identifiants), nombre de joueurs et de parties ; hors du fil du serveur. */
	private String simulate(String body) {
		JsonObject o = JsonParser.parseString(body).getAsJsonObject();
		ScenarioDef d = new ScenarioDef();
		o.getAsJsonArray("roles").forEach(e -> d.roles.add(e.getAsString()));
		int players = Math.max(5, Math.min(16, o.get("joueurs").getAsInt()));
		int games = Math.max(10, Math.min(500, o.has("parties") ? o.get("parties").getAsInt() : 200));
		if (!d.missingRoles().isEmpty()) {
			throw new GameException("Rôles introuvables : " + String.join(", ", d.missingRoles()));
		}
		// Le scénario en cours d'édition est simulé avec ses règles (rôles imposés, répartition, ordre de nuit).
		ScenarioDef full = null;
		if (o.has("scenario") && o.get("scenario").isJsonObject()) {
			full = GSON.fromJson(o.get("scenario"), ScenarioDef.class);
			full.id = "simulation";
			if (full.nom == null || full.nom.isBlank()) {
				full.nom = "Simulation";
			}
			full.carte = "";
			String problem = full.problem();
			if (problem != null) {
				throw new GameException(problem);
			}
		}
		Simulation.Result r = Simulation.run(d.script(), full, players, games, System.nanoTime());
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("parties", r.games());
		m.put("bien", r.good());
		m.put("mal", r.evil());
		m.put("pourcentBien", r.goodPercent());
		m.put("jours", Math.round(r.days() * 10) / 10.0);
		m.put("bloquees", r.stuck());
		m.put("joursMin", r.shortest());
		m.put("joursMax", r.longest());
		// Poids de chaque rôle : part des parties gagnées par son camp quand il est en jeu.
		List<Map<String, Object>> weights = new ArrayList<>();
		r.roles().forEach((name, c) -> weights.add(Map.of("nom", name, "parties", c[0],
			"victoires", c[0] == 0 ? 0 : Math.round(100f * c[1] / c[0]))));
		m.put("roles", weights);
		m.put("probleme", r.problem());
		return GSON.toJson(m);
	}

	/** Choisit le scénario pour la prochaine partie ; « essai » lance tout de suite une partie avec des joueurs fictifs. */
	private String play(JsonObject o, String owner) {
		String id = o.get("scenario").getAsString();
		boolean trial = o.has("essai") && o.get("essai").getAsBoolean();
		if (session.game() != null) {
			throw new GameException("Une partie est en cours : arrêtez-la d'abord (/vc arreter).");
		}
		String map = session.useScenario(id);
		if (!trial) {
			return "Scénario choisi pour la prochaine partie." + (map.isEmpty() ? "" : " " + map);
		}
		int players = Math.max(5, Math.min(15, o.has("joueurs") ? o.get("joueurs").getAsInt() : 7));
		ServerPlayer p = server.getPlayerList().getPlayerByName(owner);
		session.removeBots();
		if (p != null && !session.lobby().contains(owner) && !owner.equals(session.storyteller())) {
			session.join(owner);
		}
		int missing = players - session.lobby().size();
		if (missing > 0) {
			session.addBots(missing);
		}
		session.start(owner, Session.Mode.AUTO);
		return "Partie d'essai lancée (" + session.game().size() + " joueurs, Conteur automatique)."
			+ (p == null ? " Connectez-vous au jeu pour y assister." : "");
	}

	private void exportPack(HttpExchange ex) throws IOException {
		Map<String, List<String>> q = query(ex.getRequestURI().getRawQuery());
		String pack = onServer(() -> creations.exportPack(q.getOrDefault("roles", List.of()),
			q.getOrDefault("scenarios", List.of()), q.getOrDefault("cartes", List.of())), 60);
		ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"creations.valcombe\"");
		send(ex, 200, "application/octet-stream", pack.getBytes(StandardCharsets.UTF_8));
	}

	private static Map<String, List<String>> query(String raw) {
		Map<String, List<String>> out = new LinkedHashMap<>();
		if (raw == null) {
			return out;
		}
		for (String part : raw.split("&")) {
			String[] kv = part.split("=", 2);
			if (kv.length == 2 && !kv[1].isBlank()) {
				out.put(kv[0], Arrays.asList(URLDecoder.decode(kv[1], StandardCharsets.UTF_8).split(",")));
			}
		}
		return out;
	}

	private static void json(HttpExchange ex, String body) throws IOException {
		send(ex, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
	}

	private static void text(HttpExchange ex, int status, String body) throws IOException {
		send(ex, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
	}

	private static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
		ex.getResponseHeaders().set("Content-Type", type);
		if (!ex.getResponseHeaders().containsKey("Cache-Control")) {
			ex.getResponseHeaders().set("Cache-Control", "no-store");
		}
		ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
		ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** Pour vérifier les droits : le joueur est-il opérateur ? */
	static boolean isOperator(ServerPlayer p) {
		return p != null && p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
	}

	static JsonObject parse(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}
}
