package fr.valcombe.mc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fr.valcombe.rules.GameException;
import fr.valcombe.session.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Grimoire sur tablette ou téléphone : une petite page web servie par le serveur Minecraft.
 * Chaque Conteur a la sienne (celui qui a lancé /vc tablette), protégée par un jeton aléatoire présent dans son
 * lien : plusieurs parties se mènent en même temps, chacune depuis sa tablette. Les actions de la page sont des
 * commandes /vc exécutées au nom de ce joueur, avec ses droits : la tablette ne permet rien de plus que le jeu.
 * <p>
 * La partie montrée et commandée est toujours celle de ce joueur ({@link fr.valcombe.mc.games.Games#forPlayer}) :
 * celle du monde où il se trouve, ou sa table d'attache s'il est au hub ; jamais « la seule » partie du serveur.
 */
public final class Tablet {
	private static final Gson GSON = new Gson();
	private static final SecureRandom RANDOM = new SecureRandom();

	private final Community community;
	private MinecraftServer server;
	private HttpServer http;
	/** Les tablettes ouvertes : jeton → joueur (un jeton par joueur ; en rouvrir une remplace l'ancien lien). */
	private final java.util.Map<String, String> tokens = new java.util.concurrent.ConcurrentHashMap<>();
	/** La dernière tablette ouverte (jeton, joueur), pour {@link #owner()} et {@link #links()}. */
	private volatile String token;
	private volatile String owner;
	private int port;
	private Atelier atelier;
	/** API d'administration (/admin/...), servie par ce même serveur web. */
	private AdminApi admin;
	/** L'API d'administration est ouverte : le serveur web tourne tant que le serveur Minecraft tourne. */
	private volatile boolean adminOpen;

	void setAtelier(Atelier atelier) {
		this.atelier = atelier;
	}

	void setAdmin(AdminApi admin) {
		this.admin = admin;
	}

	/**
	 * Ouvre l'API d'administration : le serveur web démarre (s'il ne tournait pas) et reste en service jusqu'à
	 * l'arrêt du serveur Minecraft, même quand la tablette et l'Atelier sont fermés. Renvoie le port.
	 * Un port déjà pris lève une GameException, comme pour la tablette.
	 */
	public synchronized int openAdmin() throws IOException {
		if (admin == null) {
			throw new GameException("API d'administration indisponible.");
		}
		adminOpen = true;
		try {
			start();
		} catch (IOException | RuntimeException e) {
			adminOpen = false;
			throw e;
		}
		return port;
	}

	/** L'API d'administration est-elle en service ? */
	public synchronized boolean adminOpen() {
		return adminOpen && http != null;
	}

	/** Port du serveur web (0 s'il ne tourne pas). */
	public synchronized int port() {
		return http == null ? 0 : port;
	}

	/**
	 * Lien vers l'Atelier pour cet opérateur. Chaque opérateur a son propre jeton : plusieurs
	 * personnes travaillent ensemble dans l'Atelier, sans toucher à la tablette du Conteur.
	 */
	public synchronized List<String> openAtelier(String player) throws IOException {
		start();
		String t = atelier.tokenFor(player);
		return bases().stream().map(b -> b + "/atelier#" + t).toList();
	}

	/**
	 * Ferme l'Atelier de cet opérateur : son lien ne fonctionne plus. Les autres opérateurs, l'hôte du
	 * serveur local et la tablette du Conteur ne sont pas touchés.
	 */
	public synchronized void closeAtelier(String player) {
		if (atelier != null) {
			atelier.revoke(player);
		}
		stopIfUnused();
	}

	/** Ferme toutes les tablettes : leurs liens ne fonctionnent plus. L'Atelier reste ouvert tant qu'un de ses liens sert. */
	public synchronized void closeTablet() {
		tokens.clear();
		token = null;
		owner = null;
		stopIfUnused();
	}

	/** Ferme la tablette de ce joueur : son lien ne fonctionne plus ; celles des autres Conteurs restent ouvertes. */
	public synchronized void closeTablet(String player) {
		tokens.values().removeIf(player::equals);
		if (player.equals(owner)) {
			token = null;
			owner = null;
		}
		stopIfUnused();
	}

	/** L'Atelier a-t-il encore un lien en service (un opérateur, ou l'hôte du serveur local) ? */
	public boolean atelierOpen() {
		return http != null && atelier != null && atelier.inUse();
	}

	/**
	 * Le serveur web ne tourne que tant qu'un lien sert : celui de la tablette, un lien d'Atelier, ou l'API
	 * d'administration (ouverte d'office sur un serveur dédié).
	 */
	private void stopIfUnused() {
		if (http != null && tokens.isEmpty() && !adminOpen && (atelier == null || !atelier.inUse())) {
			http.stop(0);
			http = null;
		}
	}

	/**
	 * Serveur lancé depuis le launcher, sur le PC de l'hôte : le launcher a déposé un lien d'Atelier
	 * dans config/valcombe-hote.json. On ouvre l'Atelier avec ce lien, et on efface le fichier.
	 * Renvoie le port ouvert, ou 0 s'il n'y a rien à ouvrir.
	 */
	public synchronized int openForHost(java.nio.file.Path file) {
		if (atelier == null || !java.nio.file.Files.exists(file)) {
			return 0;
		}
		try {
			com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(
				java.nio.file.Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			java.nio.file.Files.deleteIfExists(file);
			String name = o.has("nom") ? o.get("nom").getAsString() : "";
			String given = o.has("jeton") ? o.get("jeton").getAsString() : "";
			if (!name.matches("[A-Za-z0-9_]{1,16}") || !given.matches("[0-9a-f]{32,64}")) {
				ValcombeMod.LOG.warn("Lien d'Atelier de l'hôte illisible : ignoré");
				return 0;
			}
			start();
			atelier.setHostToken(name, given);
			ValcombeMod.LOG.info("[valcombe] Atelier de l'hôte ouvert sur le port {}", port);
			return port;
		} catch (IOException | RuntimeException e) {
			ValcombeMod.LOG.warn("Atelier de l'hôte non ouvert : {}", e.getMessage());
			return 0;
		}
	}

	public Tablet(Community community) {
		this.community = community;
	}

	public void setServer(MinecraftServer server) {
		this.server = server;
	}

	public boolean running() {
		return http != null;
	}

	/** Démarre (ou redémarre avec un nouveau jeton) la tablette de ce joueur. Retourne le lien. */
	public synchronized String open(String player) throws IOException {
		start();
		byte[] b = new byte[18];
		RANDOM.nextBytes(b);
		String t = HexFormat.of().formatHex(b);
		tokens.values().removeIf(player::equals); // son ancien lien ne marche plus
		tokens.put(t, player);
		token = t;
		owner = player;
		return links(player).get(0);
	}

	/** Démarre le serveur web s'il ne tourne pas (ou le déplace si le port a changé), sans toucher aux liens déjà donnés. */
	private void start() throws IOException {
		if (http != null && port == community.tabletPort()) {
			return;
		}
		if (http != null) {
			http.stop(0);
			http = null;
		}
		port = community.tabletPort();
		HttpServer h;
		try {
			h = HttpServer.create(new InetSocketAddress(port), 0);
		} catch (java.net.BindException e) {
			throw new GameException("Le port " + port + " est déjà utilisé sur cette machine. "
				+ "Choisissez-en un autre : /vc tablette port <numéro>");
		}
		h.createContext("/", this::page);
		h.createContext("/api/vue", this::view);
		h.createContext("/api/commande", this::command);
		if (atelier != null) {
			h.createContext("/atelier", atelier::page);
			h.createContext("/api/atelier/", ex -> atelier.api(ex, !community.tabletLocalOnly() || local(ex)));
		}
		if (admin != null) {
			// Ses propres règles d'accès (clé, machine elle-même) : voir AdminApi et docs/ADMIN-API.md. Tant qu'elle
			// n'est pas ouverte (partie solo dont la tablette tourne), elle répond 404 sans regarder la clé.
			AdminApi api = admin;
			h.createContext("/admin/", ex -> {
				if (adminOpen) {
					api.handle(ex);
				} else {
					api.closed(ex);
				}
			});
		}
		// Six fils : une longue opération de l'Atelier ne bloque ni le grimoire du Conteur ni le launcher qui suit la console.
		h.setExecutor(Executors.newFixedThreadPool(6, r -> {
			Thread t = new Thread(r, "valcombe-tablette");
			t.setDaemon(true);
			return t;
		}));
		h.start();
		http = h;
	}

	/** Arrêt du serveur Minecraft : la tablette et tous les liens de l'Atelier sont fermés, celui de l'hôte compris. */
	public synchronized void close() {
		if (http != null) {
			http.stop(0);
			http = null;
		}
		tokens.clear();
		token = null;
		owner = null;
		adminOpen = false;
		if (atelier != null) {
			atelier.revoke(null);
			atelier.setHostToken(null, null);
		}
	}

	/** Liens possibles vers la dernière tablette ouverte (adresse déclarée du serveur, puis adresses du réseau local). */
	public List<String> links() {
		String t = token;
		return bases().stream().map(b -> b + "/#" + t).toList();
	}

	/** Liens possibles vers la tablette de ce joueur ; liste vide s'il n'en a pas. */
	public List<String> links(String player) {
		String t = tokens.entrySet().stream().filter(e -> e.getValue().equals(player)).map(java.util.Map.Entry::getKey)
			.findFirst().orElse(null);
		return t == null ? List.of() : bases().stream().map(b -> b + "/#" + t).toList();
	}

	/** Adresses possibles du serveur web : « http://hôte:port ». */
	private List<String> bases() {
		List<String> hosts = new ArrayList<>();
		String declared = server == null ? "" : server.getLocalIp();
		if (declared != null && !declared.isBlank()) {
			hosts.add(declared);
		}
		try {
			for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
					continue;
				}
				for (InetAddress a : Collections.list(ni.getInetAddresses())) {
					if (a instanceof Inet4Address && !hosts.contains(a.getHostAddress())) {
						hosts.add(a.getHostAddress());
					}
				}
			}
		} catch (IOException e) {
			// pas d'interface lisible : on garde ce qu'on a
		}
		if (hosts.isEmpty()) {
			hosts.add("localhost");
		}
		List<String> out = new ArrayList<>();
		for (String host : hosts) {
			out.add("http://" + host + ":" + port);
		}
		return out;
	}

	public String owner() {
		return owner;
	}

	// ------------------------------------------------------------------
	// Pages
	// ------------------------------------------------------------------
	private void page(HttpExchange ex) throws IOException {
		if (!ex.getRequestURI().getPath().equals("/")) {
			send(ex, 404, "text/plain", "Introuvable");
			return;
		}
		try (InputStream in = Tablet.class.getResourceAsStream("/valcombe/tablette.html")) {
			if (in == null) {
				send(ex, 500, "text/plain", "Page absente du mod");
				return;
			}
			send(ex, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	/** Réseau local (box, maison) ou la machine elle-même. */
	private static boolean local(HttpExchange ex) {
		InetAddress a = ex.getRemoteAddress().getAddress();
		return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
			|| (a.getAddress().length == 16 && (a.getAddress()[0] & 0xFE) == 0xFC); // IPv6 locale (fc00::/7)
	}

	/** Le joueur dont c'est la tablette, ou null si le lien n'est pas (ou plus) valable. */
	private String authorized(HttpExchange ex) {
		if (community.tabletLocalOnly() && !local(ex)) {
			return null;
		}
		String given = ex.getRequestHeaders().getFirst("X-Valcombe-Jeton");
		if (given == null) {
			return null;
		}
		for (var e : tokens.entrySet()) {
			if (MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), e.getKey().getBytes(StandardCharsets.UTF_8))) {
				return e.getValue();
			}
		}
		return null;
	}

	private void view(HttpExchange ex) throws IOException {
		String who = authorized(ex);
		if (who == null) {
			send(ex, 403, "text/plain", "Lien expiré : relancez /vc tablette dans le jeu.");
			return;
		}
		try {
			String json = server.submit(() -> {
				ServerPlayer p = server.getPlayerList().getPlayerByName(who);
				// Sa partie : celle du monde où il est, sinon sa table d'attache (il conte depuis le hub), sinon la principale.
				fr.valcombe.mc.games.GameRoom room = p != null ? fr.valcombe.mc.games.Games.forPlayer(p)
					: fr.valcombe.mc.games.Games.home(who) != null ? fr.valcombe.mc.games.Games.home(who) : fr.valcombe.mc.games.Games.main();
				if (room == null) {
					throw new GameException("Aucune partie sur ce serveur.");
				}
				Session session = room.session();
				// Qui regarde depuis le tableau des parties ne voit jamais les rôles, même opérateur (comme dans le jeu, ViewSync).
				boolean leader = !session.isWatcher(who) && (who.equals(session.storyteller()) || (p != null && room.isLeader(p)));
				JsonObject v = GSON.toJsonTree(session.view(who, leader)).getAsJsonObject();
				v.addProperty("online", p != null);
				v.addProperty("partie", room.name());
				v.addProperty("partieId", room.id());
				return v.toString();
			}).get(3, TimeUnit.SECONDS);
			send(ex, 200, "application/json; charset=utf-8", json);
		} catch (Exception e) {
			send(ex, 503, "text/plain", "Serveur occupé, nouvel essai…");
		}
	}

	private void command(HttpExchange ex) throws IOException {
		String who = authorized(ex);
		if (who == null) {
			send(ex, 403, "text/plain", "Lien expiré : relancez /vc tablette dans le jeu.");
			return;
		}
		if (!ex.getRequestMethod().equals("POST")) {
			send(ex, 405, "text/plain", "POST attendu");
			return;
		}
		String cmd = new String(ex.getRequestBody().readNBytes(1200), StandardCharsets.UTF_8).trim();
		if (!(cmd.equals("vc") || cmd.startsWith("vc ")) || cmd.contains("\n") || cmd.length() > 400
			|| cmd.startsWith("vc tablette")) {
			send(ex, 400, "text/plain", "Commande refusée.");
			return;
		}
		try {
			// Commande au nom du joueur, depuis là où il est : elle vise sa partie, comme dans le jeu (Games.forSource).
			String reply = server.submit(() -> {
				ServerPlayer p = server.getPlayerList().getPlayerByName(who);
				if (p == null) {
					throw new GameException(who + " doit être connecté au jeu pour agir depuis la tablette.");
				}
				StringBuilder out = new StringBuilder();
				CommandSource capture = new CommandSource() {
					@Override
					public void sendSystemMessage(Component message) {
						out.append(message.getString()).append('\n');
						p.sendSystemMessage(message);
					}

					@Override
					public boolean acceptsSuccess() {
						return true;
					}

					@Override
					public boolean acceptsFailure() {
						return true;
					}

					@Override
					public boolean shouldInformAdmins() {
						return false;
					}
				};
				server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withSource(capture), cmd);
				return out.toString().trim();
			}).get(3, TimeUnit.SECONDS);
			send(ex, 200, "text/plain; charset=utf-8", reply);
		} catch (java.util.concurrent.ExecutionException e) {
			send(ex, 409, "text/plain; charset=utf-8", e.getCause() == null ? "Erreur" : e.getCause().getMessage());
		} catch (Exception e) {
			send(ex, 503, "text/plain", "Serveur occupé, nouvel essai…");
		}
	}

	private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", type);
		ex.getResponseHeaders().set("Cache-Control", "no-store");
		ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
		ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}
}
