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
 * Une seule personne à la fois (celle qui a lancé /vc tablette), protégée par un jeton
 * aléatoire présent dans le lien. Les actions de la page sont des commandes /vc exécutées
 * au nom de ce joueur, avec ses droits : la tablette ne permet rien de plus que le jeu.
 */
public final class Tablet {
	private static final Gson GSON = new Gson();
	private static final SecureRandom RANDOM = new SecureRandom();

	private final Session session;
	private final Community community;
	private MinecraftServer server;
	private HttpServer http;
	private volatile String token;
	private volatile String owner;
	private int port;
	private Atelier atelier;
	/** L'Atelier n'est ouvert que si un opérateur a demandé le lien (/vc atelier). */
	private volatile boolean atelierAllowed;

	void setAtelier(Atelier atelier) {
		this.atelier = atelier;
	}

	/**
	 * Lien vers l'Atelier pour cet opérateur. Chaque opérateur a son propre jeton : plusieurs
	 * personnes travaillent ensemble dans l'Atelier, sans toucher à la tablette du Conteur.
	 */
	public synchronized List<String> openAtelier(String player) throws IOException {
		if (http == null) {
			open(player);
		}
		String t = atelier.tokenFor(player);
		return links().stream().map(l -> l.substring(0, l.indexOf("/#")) + "/atelier#" + t).toList();
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
			if (http == null) {
				open(name);
			}
			atelier.setHostToken(name, given);
			ValcombeMod.LOG.info("[valcombe] Atelier de l'hôte ouvert sur le port {}", port);
			return port;
		} catch (IOException | RuntimeException e) {
			ValcombeMod.LOG.warn("Atelier de l'hôte non ouvert : {}", e.getMessage());
			return 0;
		}
	}

	public Tablet(Session session, Community community) {
		this.session = session;
		this.community = community;
	}

	public void setServer(MinecraftServer server) {
		this.server = server;
	}

	public boolean running() {
		return http != null;
	}

	/** Démarre (ou redémarre avec un nouveau jeton) la tablette pour ce joueur. Retourne le lien. */
	public synchronized String open(String player) throws IOException {
		if (http != null && port == community.tabletPort()) {
			// Serveur déjà ouvert (Atelier ou tablette) : nouveau jeton seulement, sans couper l'Atelier.
			byte[] b = new byte[18];
			RANDOM.nextBytes(b);
			token = HexFormat.of().formatHex(b);
			owner = player;
			return links().get(0);
		}
		close();
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
		// Quatre fils : une longue opération de l'Atelier ne bloque plus le grimoire du Conteur.
		h.setExecutor(Executors.newFixedThreadPool(4, r -> {
			Thread t = new Thread(r, "valcombe-tablette");
			t.setDaemon(true);
			return t;
		}));
		h.start();
		byte[] b = new byte[18];
		RANDOM.nextBytes(b);
		token = HexFormat.of().formatHex(b);
		owner = player;
		atelierAllowed = false;
		http = h;
		return links().get(0);
	}

	public synchronized void close() {
		if (http != null) {
			http.stop(0);
			http = null;
			token = null;
			owner = null;
			if (atelier != null) {
				atelier.revoke(null); // les liens de l'Atelier sont fermés avec la tablette
				atelier.setHostToken(null, null);
			}
		}
	}

	/** Liens possibles vers la page (adresse déclarée du serveur, puis adresses du réseau local). */
	public List<String> links() {
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
			out.add("http://" + host + ":" + port + "/#" + token);
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

	private boolean authorized(HttpExchange ex) {
		if (community.tabletLocalOnly() && !local(ex)) {
			return false;
		}
		String given = ex.getRequestHeaders().getFirst("X-Valcombe-Jeton");
		String expected = token;
		return given != null && expected != null
			&& MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
	}

	private void view(HttpExchange ex) throws IOException {
		if (!authorized(ex)) {
			send(ex, 403, "text/plain", "Lien expiré : relancez /vc tablette dans le jeu.");
			return;
		}
		String who = owner;
		try {
			String json = server.submit(() -> {
				ServerPlayer p = server.getPlayerList().getPlayerByName(who);
				boolean leader = who.equals(session.storyteller()) || (p != null && ValcombeMod.isLeader(p));
				JsonObject v = GSON.toJsonTree(session.view(who, leader)).getAsJsonObject();
				v.addProperty("online", p != null);
				return v.toString();
			}).get(3, TimeUnit.SECONDS);
			send(ex, 200, "application/json; charset=utf-8", json);
		} catch (Exception e) {
			send(ex, 503, "text/plain", "Serveur occupé, nouvel essai…");
		}
	}

	private void command(HttpExchange ex) throws IOException {
		if (!authorized(ex)) {
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
		String who = owner;
		try {
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
