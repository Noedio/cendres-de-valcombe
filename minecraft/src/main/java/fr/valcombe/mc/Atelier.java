package fr.valcombe.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

import fr.valcombe.mc.games.GameCard;
import fr.valcombe.mc.games.GameRoom;
import fr.valcombe.mc.games.Games;
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
 * L'Atelier web : page servie par le serveur de la tablette, réservée aux opérateurs (lien donné par /vc atelier).
 * Elle suit les mêmes parcours que l'Atelier en jeu, dans le même ordre : Cartes, Structures, Parties, Rôles et
 * scénarios.
 * <ul>
 * <li><b>Cartes</b> et <b>Structures</b> : la bibliothèque et le catalogue, consultables avec leurs vignettes (voir
 * {@link Vignettes}, les mêmes que dans l'Atelier en jeu), et les gestes simples (envoyer à la partie, ouvrir dans un atelier, dupliquer, renommer,
 * supprimer). On les construit dans les ateliers du jeu : la page n'édite pas de blocs.</li>
 * <li><b>Parties</b> : les parties du serveur, celle que vise la page, et ce qu'on peut leur envoyer.</li>
 * <li><b>Rôles et scénarios</b> : la page en est l'outil d'édition (et le partage en fichier .valcombe).</li>
 * </ul>
 */
final class Atelier {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	/** Emblèmes de la bibliothèque, en plus de ceux des rôles intégrés. */
	static final List<String> EMBLEMS = List.of("e_etoile", "e_lune", "e_soleil", "e_cle", "e_plume", "e_oeil", "e_coeur",
		"e_epee", "e_bouclier", "e_couronne", "e_livre", "e_fiole", "e_rose", "e_cloche", "e_lanterne", "e_masque",
		"e_ancre", "e_corbeau", "e_serpent", "e_main");
	/** Nom d'une partie d'essai créée depuis la page (comme l'API d'administration). */
	static final int MAX_GAME_NAME = 40;

	private final CreationStore creations;
	private final MapStore maps;
	private final StructureStore structures;
	private final Workshop workshop;
	private MinecraftServer server;
	/** La partie choisie sur la page par chaque opérateur (identifiant de son monde), à la place de celle qu'on déduit. */
	private final Map<String, String> chosen = new java.util.concurrent.ConcurrentHashMap<>();

	Atelier(CreationStore creations, MapStore maps, StructureStore structures, Workshop workshop) {
		this.creations = creations;
		this.maps = maps;
		this.structures = structures;
		this.workshop = workshop;
	}

	/**
	 * La partie que vise la page de cet opérateur (fil du serveur) : celle qu'il a choisie sur la page (« partie »),
	 * sinon celle du monde où il se tient, sa table d'attache, ou la partie principale. Null sans parties.
	 */
	private GameRoom room(String owner) {
		String id = chosen.get(owner);
		GameRoom r = id == null ? null : Games.byWorld(id);
		if (r != null) {
			return r;
		}
		ServerPlayer p = server.getPlayerList().getPlayerByName(owner);
		if (p != null) {
			return Games.forPlayer(p);
		}
		r = Games.home(owner);
		return r != null ? r : Games.main();
	}

	/** La session de la partie que vise cet opérateur ; une GameException sans parties. */
	private Session session(String owner) {
		GameRoom r = room(owner);
		if (r == null) {
			throw new GameException("Aucune partie sur ce serveur.");
		}
		return r.session();
	}

	/** La page choisit la partie qu'elle vise (identifiant de son monde) ; vide : on la déduit de nouveau. */
	private String choose(String owner, String worldId) {
		if (worldId == null || worldId.isBlank()) {
			chosen.remove(owner);
			GameRoom r = room(owner);
			return r == null ? "Aucune partie." : "Partie visée : « " + r.name() + " » (celle où vous êtes).";
		}
		GameRoom r = Games.byWorld(worldId.strip());
		if (r == null) {
			throw new GameException("Aucune partie dans « " + worldId.strip() + " ».");
		}
		chosen.put(owner, r.id());
		return "Partie visée : « " + r.name() + " ».";
	}

	/** Une partie désignée par l'identifiant de son monde ; une GameException si elle n'existe pas. */
	private static GameRoom game(String worldId) {
		GameRoom r = worldId == null ? null : Games.byWorld(worldId.strip());
		if (r == null) {
			throw new GameException("Aucune partie dans « " + (worldId == null ? "" : worldId.strip()) + " ».");
		}
		return r;
	}

	/** L'opérateur du lien, s'il est connecté au jeu : ce geste se passe là-bas. */
	private ServerPlayer online(String owner) {
		ServerPlayer p = server.getPlayerList().getPlayerByName(owner);
		if (p == null) {
			throw new GameException("Connectez-vous au jeu : ce geste vous y conduit.");
		}
		return p;
	}

	private static MapWorkshop mapWorkshop() {
		MapWorkshop w = ValcombeMod.mapWorkshop();
		if (w == null) {
			throw new GameException("L'atelier carte n'est pas disponible sur ce serveur (voir son journal).");
		}
		return w;
	}

	private static StructureWorkshop structureWorkshop() {
		StructureWorkshop w = ValcombeMod.structureWorkshop();
		if (w == null) {
			throw new GameException("L'atelier structures n'est pas disponible sur ce serveur (voir son journal).");
		}
		return w;
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

	/** Reste-t-il un lien en service : celui de l'hôte, ou celui d'un opérateur donné depuis moins de douze heures ? */
	boolean inUse() {
		long now = System.currentTimeMillis();
		tokenBorn.entrySet().removeIf(e -> {
			boolean old = now - e.getValue() > TOKEN_LIFE_MS;
			if (old) {
				tokens.remove(e.getKey());
			}
			return old;
		});
		return hostToken != null || !tokens.isEmpty();
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
		activity.addFirst(time + " · " + who + " " + what);
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
			// Vignette d'une carte ou d'une structure : GET vignette/carte/<id>.png, vignette/structure/<id>.png.
			Thumb thumb = thumb(path);
			if (thumb != null) {
				// Lue sur le fil du serveur, dessinée ici : une page qui demande trente vignettes ne retient pas ses ticks.
				java.util.function.Supplier<byte[]> job = onServer(() -> thumbnailJob(thumb), 20);
				byte[] png = job == null ? null : job.get();
				if (job == null) {
					text(ex, 404, "Pas de vignette : « " + thumb.id() + " » n'existe pas.");
				} else if (png == null) {
					text(ex, 500, "La vignette de « " + thumb.id() + " » n'a pas pu se faire (voir le journal du serveur).");
				} else {
					send(ex, 200, "image/png", png);
				}
				return;
			}
			switch (path) {
				case "donnees" -> json(ex, onServer(() -> catalogue(owner), 5));
				// ---------------------------------------------------------------- Rôles et scénarios
				case "role" -> text(ex, 200, logged(owner, onServer(() -> creations.saveRole(GSON.fromJson(body, RoleDef.class)), 5)));
				case "role/supprimer" -> text(ex, 200, logged(owner, onServer(() -> creations.deleteRole(body.trim()), 5)));
				case "scenario" -> text(ex, 200, logged(owner, onServer(() -> creations.saveScenario(GSON.fromJson(body, ScenarioDef.class)), 5)));
				case "scenario/supprimer" -> text(ex, 200, logged(owner, onServer(() -> creations.deleteScenario(body.trim()), 5)));
				case "groupes" -> text(ex, 200, logged(owner, onServer(() -> creations.saveGroups(body), 5)));
				case "edition" -> {
					// Ce que l'opérateur a ouvert dans l'éditeur (« role:x », « scenario:y », ou vide)
					editing.values().removeIf(v -> v[0].equals(owner));
					String key = body.trim();
					if (!key.isEmpty()) {
						editing.put(key + "|" + owner, new String[] {owner, key});
					}
					text(ex, 200, "");
				}
				case "simuler" -> json(ex, simulate(body));
				case "jouer" -> text(ex, 200, logged(owner, onServer(() -> play(JsonParser.parseString(body).getAsJsonObject(), owner), 10)));
				case "exporter" -> exportPack(ex);
				case "importer" -> text(ex, 200, logged(owner, onServer(() -> creations.importPack(body), 120)));
				// ---------------------------------------------------------------- Parties
				// La partie que vise la page (identifiant de son monde ; vide : celle où se tient l'opérateur).
				case "partie" -> text(ex, 200, onServer(() -> choose(owner, body.trim()), 5));
				case "partie/creer" -> text(ex, 200, logged(owner, onServer(() -> createGame(owner, object(body)), 15)));
				case "partie/suspendre" -> text(ex, 200, logged(owner, onServer(() -> game(body).suspend(), 10)));
				case "partie/reprendre" -> text(ex, 200, logged(owner, onServer(() -> game(body).resume(), 10)));
				// Tout ce qui change la carte d'un monde de partie l'y envoie (MapSender) : le monde choisi (« monde »,
				// absent : celui de la partie que vise la page) est remis à neuf, puis la carte y est posée.
				case "carte/envoyer" -> {
					JsonObject o = object(body);
					text(ex, 200, logged(owner, onServer(() -> sender().sendMap(o.get("id").getAsString(), world(o, owner), player(owner)), 120)));
				}
				case "carte/generer" -> {
					JsonObject o = object(body);
					// Mêmes bornes que /vc carte generer : un champ vide ou hors bornes est refusé en clair, pas ramené en silence.
					int seats = Workshop.number(o, "places", "Trônes", Village.MIN_SEATS, Village.MAX_SEATS);
					int size = Workshop.number(o, "taille", "Taille", 0, 2);
					text(ex, 200, logged(owner, onServer(() -> sender().sendVillage(seats, size,
						o.has("theme") ? o.get("theme").getAsString() : "gothique", !o.has("maisons") || o.get("maisons").getAsBoolean(),
						world(o, owner), player(owner)), 120)));
				}
				case "carte/importer" -> {
					JsonObject o = object(body);
					byte[] data = java.util.Base64.getDecoder().decode(o.get("donnees").getAsString());
					String file = o.get("fichier").getAsString();
					text(ex, 200, logged(owner, onServer(() -> sender().sendImport(file, data, world(o, owner), player(owner)), 180)));
				}
				case "carte/vider" -> {
					JsonObject o = object(body);
					text(ex, 200, logged(owner, onServer(() -> sender().clear(world(o, owner), player(owner)), 30)));
				}
				// ---------------------------------------------------------------- Cartes
				case "carte/atelier" -> {
					// « Ouvrir dans un atelier » : un monde neuf où la carte est posée (déjà ouverte : on retrouve son atelier).
					// L'opérateur en jeu reçoit un lien pour y aller ; rien ne l'y emmène sans qu'il le demande.
					JsonObject o = object(body);
					text(ex, 200, logged(owner, onServer(() -> mapWorkshop().openLibrary(o.get("id").getAsString(), player(owner), false), 30)));
				}
				case "carte/atelier/aller" -> text(ex, 200, onServer(() -> {
					// « Y aller » : l'opérateur l'a demandé, il est conduit dans l'atelier de cette carte.
					String said = mapWorkshop().go(online(owner), body.trim());
					return said.isEmpty() ? "Vous voilà dans l'atelier de la carte, dans le jeu." : said;
				}, 10));
				case "carte/atelier/nouvelle" -> {
					JsonObject o = object(body);
					text(ex, 200, logged(owner, onServer(() -> mapWorkshop().openBlank(o.has("nom") ? o.get("nom").getAsString() : "", null)
						+ " Pour y aller : « Y aller » sur sa fiche, ou Menu J › Mondes.", 30)));
				}
				case "carte/atelier/village" -> {
					JsonObject o = object(body);
					int seats = Workshop.number(o, "places", "Trônes", Village.MIN_SEATS, Village.MAX_SEATS);
					int size = Workshop.number(o, "taille", "Taille", 0, 2);
					text(ex, 200, logged(owner, onServer(() -> mapWorkshop().openVillage(seats, size,
						o.has("theme") ? o.get("theme").getAsString() : "gothique", !o.has("maisons") || o.get("maisons").getAsBoolean(),
						o.has("nom") ? o.get("nom").getAsString() : "", null)
						.replace(" : vous y serez conduit à la fin.", " : sa fiche le dira prête, puis « Y aller ».")
						.replace(McHost.PROGRESS, ""), 60)));
				}
				case "carte/renommer" -> {
					JsonObject o = object(body);
					text(ex, 200, logged(owner, onServer(() -> maps.rename(o.get("id").getAsString(), o.get("nom").getAsString()), 5)));
				}
				case "carte/dupliquer" -> {
					JsonObject o = object(body);
					// Identifiant et nom facultatifs, comme /vc carte copier : sans eux, « <nom> (copie) ».
					text(ex, 200, logged(owner, onServer(() -> maps.duplicate(o.get("id").getAsString(),
						o.has("nouvelId") ? o.get("nouvelId").getAsString() : "", o.has("nom") ? o.get("nom").getAsString() : ""), 30)));
				}
				case "carte/supprimer" -> text(ex, 200, logged(owner, onServer(() -> maps.delete(body.trim()), 5)));
				// ---------------------------------------------------------------- Structures
				case "structure/atelier" -> text(ex, 200, onServer(() -> {
					// « Voir dans l'atelier » : l'opérateur l'a demandé, il est conduit devant la parcelle (ou dans l'atelier).
					StructureWorkshop w = structureWorkshop();
					ServerPlayer p = online(owner);
					String said = w.go(p);
					String id = body.trim();
					return !id.isEmpty() && w.isWorkshop(p.level()) ? w.visit(p, id) : said;
				}, 30));
				case "structure/dupliquer" -> {
					JsonObject o = object(body);
					text(ex, 200, logged(owner, onServer(() -> structures.duplicate(o.get("id").getAsString(),
						o.has("nom") ? o.get("nom").getAsString() : ""), 30)));
				}
				case "structure/importer" -> {
					// Un fichier .schem, .litematic ou .nbt ajouté au catalogue : la carte n'est pas touchée.
					JsonObject o = object(body);
					byte[] data = java.util.Base64.getDecoder().decode(o.get("donnees").getAsString());
					text(ex, 200, logged(owner, onServer(() -> structures.importFile(o.get("fichier").getAsString(), data,
						o.has("id") ? o.get("id").getAsString() : "", o.has("nom") ? o.get("nom").getAsString() : ""), 60)));
				}
				case "structure/renommer" -> {
					JsonObject o = object(body);
					text(ex, 200, logged(owner, onServer(() -> structures.rename(o.get("id").getAsString(), o.get("nom").getAsString()), 5)));
				}
				case "structure/repere" -> {
					JsonObject o = object(body);
					text(ex, 200, logged(owner, onServer(() -> structures.setMark(o.get("id").getAsString(),
						o.get("repere").getAsString()), 5)));
				}
				case "structure/supprimer" -> text(ex, 200, logged(owner, onServer(() -> structures.delete(body.trim()), 5)));
				// Structure fournie modifiée dans l'atelier structures : l'originale revient partout (sa parcelle suit).
				case "structure/originale" -> text(ex, 200, logged(owner, onServer(() -> structures.revert(body.trim()), 5)));
				case "fermer" -> {
					// Ferme le lien de cet opérateur (celui de l'hôte du serveur local reste valable tant que le serveur tourne).
					// La réponse part d'abord : fermer le dernier lien arrête le serveur web, et la requête avec.
					text(ex, 200, "Atelier fermé : ce lien ne fonctionne plus.");
					ValcombeMod.tablet().closeAtelier(owner);
				}
				default -> text(ex, 404, "Action inconnue.");
			}
		} catch (GameException e) {
			text(ex, 409, e.getMessage());
		} catch (RuntimeException e) {
			ValcombeMod.LOG.warn("Atelier : requête refusée", e);
			text(ex, 400, "Requête illisible : " + e.getMessage());
		}
	}

	/** Une vignette demandée par la page : sa sorte et l'identifiant de la carte ou de la structure. */
	record Thumb(String kind, String id) {
		/** La clé de cette vignette chez {@link Vignettes} (« carte:port », « structure:puits »). */
		String key() {
			return kind + ":" + id;
		}
	}

	/**
	 * La vignette que désigne ce chemin de l'API (« vignette/carte/port-aux-brumes.png »,
	 * « vignette/structure/puits.png »), ou null pour un autre chemin, une autre sorte ou un identifiant mal formé.
	 */
	static Thumb thumb(String path) {
		if (path == null || !path.startsWith("vignette/") || !path.endsWith(".png")) {
			return null;
		}
		String rest = path.substring("vignette/".length(), path.length() - ".png".length());
		int slash = rest.indexOf('/');
		if (slash < 0) {
			return null;
		}
		String kind = rest.substring(0, slash);
		String id = rest.substring(slash + 1);
		return !(kind.equals("carte") || kind.equals("structure")) || !id.matches("[a-z0-9_-]{1,64}") ? null : new Thumb(kind, id);
	}

	/**
	 * Fil du serveur : ce qu'il faut pour faire la vignette (voir {@link Vignettes#job}), l'image se faisant ensuite
	 * hors de ce fil. Null si la carte ou la structure n'existe pas.
	 */
	private static java.util.function.Supplier<byte[]> thumbnailJob(Thumb t) {
		return Vignettes.job(t.key());
	}

	/** Le corps d'une requête en objet JSON (vide : un objet vide). */
	private static JsonObject object(String body) {
		return body == null || body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
	}

	/** Qui envoie les cartes aux mondes de partie ; une GameException sans les mondes de Valcombe. */
	private static MapSender sender() {
		MapSender s = ValcombeMod.sender();
		if (s == null) {
			throw new GameException("Les mondes de Valcombe ne sont pas disponibles sur ce serveur (voir son journal) : "
				+ "on ne peut pas envoyer de carte à une partie.");
		}
		return s;
	}

	/**
	 * Le monde de partie choisi sur la page (champ « monde »), sinon celui de la partie que vise l'opérateur (voir
	 * {@link #room}). Fil du serveur.
	 */
	private String world(JsonObject o, String owner) {
		if (o.has("monde") && !o.get("monde").getAsString().isBlank()) {
			return o.get("monde").getAsString();
		}
		GameRoom r = room(owner);
		return r == null ? null : r.id();
	}

	/** L'opérateur dans le jeu (il suit la barre de l'envoi où qu'il soit), ou null s'il n'y est pas. */
	private ServerPlayer player(String owner) {
		return server.getPlayerList().getPlayerByName(owner);
	}

	/** Crée une partie d'essai (comme le menu « Parties » du jeu) ; la page la vise aussitôt. */
	private String createGame(String owner, JsonObject o) {
		String name = o.has("nom") ? o.get("nom").getAsString().strip() : "";
		if (name.isEmpty() || name.length() > MAX_GAME_NAME) {
			throw new GameException("Nom de la partie d'essai : 1 à " + MAX_GAME_NAME + " caractères.");
		}
		Games g = Games.current();
		if (g == null) {
			throw new GameException("Les parties ne sont pas disponibles sur ce serveur : les mondes de Valcombe manquent (voir son journal).");
		}
		java.util.Set<String> before = new java.util.HashSet<>();
		Games.all().forEach(r -> before.add(r.id()));
		String said = g.create(name, null);
		Games.all().stream().filter(r -> !before.contains(r.id())).findFirst().ifPresent(r -> chosen.put(owner, r.id()));
		return said;
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

	// ------------------------------------------------------------------
	// Ce que la page affiche
	// ------------------------------------------------------------------

	/**
	 * Ce qui empêche, en ce moment, d'envoyer une carte au monde d'une partie (le même contrôle que MapSender, fait
	 * d'avance pour griser le bouton et dire pourquoi), ou null si rien ne l'empêche. Chaque partie a le sien : une
	 * partie qui se joue dans la partie principale n'empêche pas d'envoyer une carte à une partie d'essai.
	 *
	 * @param name    nom de la partie
	 * @param running une partie se joue dans ce monde (on change de carte entre deux parties)
	 * @param sending ce qui est en route vers ce monde (« la carte « Port » »), ou null
	 * @param busy    un chantier, une remise en état ou une remise à neuf y est en cours
	 */
	static String sendBlocker(String name, boolean running, String sending, boolean busy) {
		if (sending != null) {
			return "« " + name + " » reçoit déjà " + sending + " : attendez la fin de la barre.";
		}
		if (running) {
			return "Une partie se joue dans « " + name + " » : on change de carte entre deux parties.";
		}
		if (busy) {
			return "Un chantier est en cours dans « " + name + " » : attendez la fin de la barre.";
		}
		return null;
	}

	/** Une partie telle que la page la montre (fiche « Parties », choix de la partie visée, envois). Fil du serveur. */
	private Map<String, Object> gameCard(GameRoom r, GameRoom aimed) {
		McHost host = r.host();
		Session session = r.session();
		MapSender s = ValcombeMod.sender();
		String sending = s == null ? null : s.sendingTo(r.id());
		boolean running = host.gameRunning();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", r.id());
		m.put("nom", r.name());
		m.put("principale", r.main());
		m.put("sorte", r.main() ? "Partie principale" : r.kind() == fr.valcombe.mc.worlds.WorldKind.ESSAI ? "Partie d'essai" : r.kind().label());
		m.put("visee", aimed != null && aimed.id().equals(r.id()));
		GameCard.Status status = GameCard.statusOf(session);
		m.put("statut", status.code);
		m.put("statutNom", status.label);
		m.put("etat", r.state());
		m.put("enCours", running);
		m.put("suspendue", r.suspended());
		m.put("enJeu", r.inProgress());
		m.put("presents", r.present());
		m.put("places", GameCard.places(r.seats()));
		m.put("conteur", session.storyteller());
		m.put("scenario", session.scenario() == null ? null : session.scenario().nom);
		Map<String, Object> cur = workshop.current(host, false);
		m.put("carte", Workshop.brief(cur));
		m.put("carteId", cur == null ? "" : cur.get("id"));
		m.put("envoi", sending);
		m.put("bloque", sendBlocker(r.name(), running, sending, host.busy()));
		return m;
	}

	/** Tout ce que la page affiche : cartes, structures, parties, rôles et scénarios, emblèmes, présence et journal. */
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
		ServerPlayer meInGame = server.getPlayerList().getPlayerByName(me);
		out.put("enJeu", meInGame != null);
		var worlds = ValcombeMod.worlds();
		var here = meInGame == null || worlds == null ? null : worlds.entryOf(meInGame.level());
		out.put("ici", here == null ? null : here.nom());

		// ---------------------------------------------------------------- Parties
		GameRoom room = room(me);
		List<Map<String, Object>> games = new ArrayList<>();
		for (GameRoom r : Games.all()) {
			games.add(gameCard(r, room));
		}
		out.put("parties", games);
		out.put("partie", room == null ? null : Map.of("id", room.id(), "nom", room.name(), "choisie", chosen.containsKey(me)));
		McHost host = room == null ? null : room.host();
		Session session = room == null ? null : room.session();
		Map<String, Object> current = workshop.current(host, false);
		if (current != null) {
			out.put("carteActuelle", current);
		}
		out.put("carteResume", Workshop.describe(current));
		out.put("carteEnPlace", host == null ? "" : host.mapInPlace());
		out.put("chantier", host != null && host.busy());
		out.put("essaiEnAttente", session != null && session.trialPending());
		out.put("partieEnCours", session != null && session.game() != null);
		out.put("scenarioActuel", session == null || session.scenario() == null ? null : session.scenario().id);
		out.put("scenarioActuelNom", session == null || session.scenario() == null ? null : session.scenario().nom);

		// ---------------------------------------------------------------- Cartes
		// La même description que pour l'écran en jeu (voir Workshop), avec les repères et la liste de contrôle.
		List<Map<String, Object>> cards = workshop.library(true);
		MapWorkshop mw = ValcombeMod.mapWorkshop();
		for (Map<String, Object> m : cards) {
			Object a = m.get("atelier");
			var entry = a == null || worlds == null ? null : worlds.list().stream().filter(e -> e.id().equals(a)).findFirst().orElse(null);
			m.put("atelierNom", entry == null ? null : entry.nom());
			m.put("atelierPret", a != null && mw != null && mw.idle((String) a));
		}
		out.put("cartes", cards);

		// ---------------------------------------------------------------- Structures
		StructureWorkshop sw = ValcombeMod.structureWorkshop();
		List<StructureWorkshop.ParcelInfo> parcels = sw == null ? List.of() : sw.parcels();
		List<Map<String, Object>> list = new ArrayList<>();
		for (StructureStore.Def d : structures.list()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", d.id);
			m.put("nom", d.nom);
			m.put("categorie", d.categorie);
			m.put("taille", d.taille);
			m.put("blocs", d.blocs);
			m.put("repere", d.repere == null ? "" : d.repere);
			m.put("fournie", d.fournie);
			m.put("modifiee", d.modifiee);
			m.put("modifieeLe", d.modifieeLe);
			// L'empreinte de la version actuelle : la page refait la vignette quand elle change.
			m.put("signature", structures.signature(d.id));
			StructureWorkshop.ParcelInfo p = parcels.stream().filter(x -> x.id().equals(d.id)).findFirst().orElse(null);
			m.put("parcelle", p == null ? null : p.etat());
			m.put("conflit", p != null && p.conflit());
			list.add(m);
		}
		out.put("structures", list);
		out.put("atelierStructures", sw != null && sw.entry() != null);

		// ---------------------------------------------------------------- Rôles et scénarios
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
		out.put("groupes", creations.groups());
		List<Map<String, Object>> sc = new ArrayList<>();
		for (ScenarioDef d : creations.scenarios()) {
			Map<String, Object> m = GSON.fromJson(GSON.toJson(d), Map.class);
			m.put("joueurs", d.playable());
			m.put("probleme", d.problem());
			sc.add(m);
		}
		out.put("scenarios", sc);
		out.put("joueursMin", fr.valcombe.rules.Distribution.MIN_PLAYERS);
		out.put("joueursMax", fr.valcombe.rules.Distribution.MAX_PLAYERS);
		out.put("tronesMin", Village.MIN_SEATS);
		out.put("tronesMax", Village.MAX_SEATS);
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
		return GSON.toJson(out);
	}

	/** Simulation : rôles (identifiants), nombre de joueurs et de parties ; hors du fil du serveur. */
	private String simulate(String body) {
		JsonObject o = JsonParser.parseString(body).getAsJsonObject();
		ScenarioDef d = new ScenarioDef();
		o.getAsJsonArray("roles").forEach(e -> d.roles.add(e.getAsString()));
		int players = Workshop.number(o, "joueurs", "Joueurs", fr.valcombe.rules.Distribution.MIN_PLAYERS,
			fr.valcombe.rules.Distribution.MAX_PLAYERS);
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

	/**
	 * Choisit le scénario pour la prochaine partie de la partie visée ; « essai » y lance tout de suite une partie avec
	 * des joueurs fictifs.
	 */
	private String play(JsonObject o, String owner) {
		String id = o.get("scenario").getAsString();
		boolean trial = o.has("essai") && o.get("essai").getAsBoolean();
		Session session = session(owner);
		if (session.game() != null) {
			throw new GameException("Une partie est en cours : arrêtez-la d'abord (/vc arreter).");
		}
		if (trial) {
			// Même méthode que /vc atelier essai : mêmes bornes, et la partie attend d'elle-même la fin du chargement de la carte.
			return session.trial(owner, id, Workshop.number(o, "joueurs", "Joueurs", fr.valcombe.rules.Distribution.MIN_PLAYERS,
				fr.valcombe.rules.Distribution.MAX_PLAYERS));
		}
		String map = session.useScenario(id);
		return id.equals("aucun") ? "Plus de scénario : script et réglages libres."
			: "Scénario « " + session.scenario().nom + " » choisi pour la prochaine partie." + (map.isEmpty() ? "" : " " + map);
	}

	private void exportPack(HttpExchange ex) throws IOException {
		Map<String, List<String>> q = query(ex.getRequestURI().getRawQuery());
		String pack = onServer(() -> creations.exportPack(q.getOrDefault("roles", List.of()),
			q.getOrDefault("scenarios", List.of()), q.getOrDefault("cartes", List.of()), q.getOrDefault("structures", List.of()),
			// Un nom de groupe peut contenir une virgule : chaque nom arrive encodé une seconde fois.
			q.getOrDefault("groupes", List.<String>of()).stream().map(n -> URLDecoder.decode(n, StandardCharsets.UTF_8)).toList()), 60);
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
