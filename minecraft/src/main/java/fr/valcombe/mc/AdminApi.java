package fr.valcombe.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import fr.valcombe.admin.AdminException;
import fr.valcombe.admin.AdminGuard;
import fr.valcombe.admin.AdminJson;
import fr.valcombe.admin.AdminKey;
import fr.valcombe.admin.AdminSettings;
import fr.valcombe.admin.ConsoleBuffer;
import fr.valcombe.admin.RestartFlag;
import fr.valcombe.admin.WorldsView;
import fr.valcombe.backup.BackupKind;
import fr.valcombe.backup.BackupManifest;
import fr.valcombe.backup.BackupName;
import fr.valcombe.backup.BackupSettings;
import fr.valcombe.backup.BackupStore;
import fr.valcombe.backup.Restorer;
import fr.valcombe.mixin.DedicatedServerAccessor;
import fr.valcombe.mixin.SettingsAccessor;
import fr.valcombe.rules.GameException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;

/**
 * API d'administration du serveur, sous /admin/... sur le serveur web de la tablette et de l'Atelier.
 * Le launcher s'en sert pour piloter un serveur, sur ce PC ou distant (par un tunnel SSH). Le contrat
 * complet est dans docs/ADMIN-API.md : toute évolution ici doit y être reportée.
 *
 * <p>Sécurité :
 * <ul>
 *   <li>chaque requête porte la clé d'administration dans l'en-tête {@code X-Valcombe-Admin} ; elle est comparée en
 *   temps constant, et les essais ratés sont limités par adresse ({@link AdminGuard}) ;</li>
 *   <li>avec {@code adminLocalOnly} (par défaut), seules les requêtes de la machine elle-même sont acceptées ;</li>
 *   <li>les corps de requête sont bornés (64 Ko) et le JSON est lu strictement ({@link AdminJson}) ;</li>
 *   <li>tout ce qui touche au jeu s'exécute sur le fil du serveur Minecraft, avec un délai maximal : les fils du
 *   serveur web ne lisent ni ne modifient jamais l'état du jeu eux-mêmes ;</li>
 *   <li>pas d'en-tête CORS : une page web ouverte dans un navigateur ne peut pas appeler l'API.</li>
 * </ul>
 */
final class AdminApi {
	/** Version du contrat ; elle augmente quand un point d'accès change de façon incompatible. */
	static final int API_VERSION = 1;
	static final String HEADER = "X-Valcombe-Admin";
	static final int MAX_BODY = 64 * 1024;
	static final int MAX_COMMAND = 2000;
	/** Lignes de sortie gardées pour une commande (une commande bavarde est coupée). */
	private static final int MAX_OUTPUT_LINES = 200;
	private static final long MB = 1024L * 1024L;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

	private final AdminKey key;
	private final AdminGuard guard;
	private final ConsoleBuffer console;
	private final Community community;
	private final Path valcombeDir;
	private final String modVersion;
	private final long startedAt = System.currentTimeMillis();
	private volatile MinecraftServer server;

	AdminApi(AdminKey key, AdminGuard guard, ConsoleBuffer console, Community community, Path valcombeDir, String modVersion) {
		this.key = key;
		this.guard = guard;
		this.console = console;
		this.community = community;
		this.valcombeDir = valcombeDir;
		this.modVersion = modVersion;
	}

	void setServer(MinecraftServer server) {
		this.server = server;
	}

	AdminKey key() {
		return key;
	}

	/** Remplace la clé : l'ancienne cesse aussitôt de fonctionner, et les blocages dus aux essais ratés sont levés. */
	String newKey() throws IOException {
		String k = key.regenerate();
		guard.reset();
		return k;
	}

	// ------------------------------------------------------------------
	// Entrée : garde, puis aiguillage
	// ------------------------------------------------------------------
	void handle(HttpExchange ex) throws IOException {
		try {
			if (!key.enabled()) {
				throw new AdminException(404, "API d'administration désactivée (\"enabled\": false dans config/valcombe/admin.json).");
			}
			InetAddress from = ex.getRemoteAddress().getAddress();
			AdminGuard.Result verdict = guard.check(from.getHostAddress(), !key.localOnly() || AdminGuard.loopback(from),
				ex.getRequestHeaders().getFirst(HEADER), key.key());
			switch (verdict.verdict()) {
				case NETWORK_REFUSED -> throw new AdminException(403, "L'API d'administration n'accepte que les requêtes de cette "
					+ "machine (adminLocalOnly) : depuis un autre PC, passez par un tunnel SSH.");
				case MISSING_KEY -> throw new AdminException(401, "Clé d'administration absente (en-tête " + HEADER + ").");
				case WRONG_KEY -> {
					ValcombeMod.LOG.warn("API d'administration : clé refusée pour {}", from.getHostAddress());
					throw new AdminException(401, "Clé d'administration refusée.");
				}
				case LOCKED -> {
					ex.getResponseHeaders().set("Retry-After", String.valueOf(verdict.retryAfterSeconds()));
					throw new AdminException(429, "Trop d'essais avec une mauvaise clé : réessayez dans "
						+ verdict.retryAfterSeconds() + " s.");
				}
				case OK -> route(ex, from);
			}
		} catch (AdminException e) {
			error(ex, e.status(), e.getMessage());
		} catch (GameException e) {
			error(ex, 409, e.getMessage());
		} catch (RuntimeException e) {
			ValcombeMod.LOG.warn("API d'administration : erreur", e);
			error(ex, 500, "Erreur interne : " + e.getMessage());
		}
	}

	/**
	 * Réponse de l'API quand elle n'est pas ouverte : le serveur web tourne pour la tablette ou l'Atelier d'une
	 * partie solo, mais l'administration reste fermée (ni clé regardée, ni essai compté).
	 */
	void closed(HttpExchange ex) throws IOException {
		error(ex, 404, "API d'administration fermée : elle ne s'ouvre que sur un serveur dédié.");
	}

	private void route(HttpExchange ex, InetAddress from) throws IOException {
		String path = ex.getRequestURI().getPath().substring("/admin/".length());
		if (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		String method = ex.getRequestMethod();
		if (path.equals("sauvegardes") || path.startsWith("sauvegardes/")) {
			backups(ex, path.equals("sauvegardes") ? "" : path.substring("sauvegardes/".length()), method, from);
			return;
		}
		if (path.equals("ateliers") || path.startsWith("ateliers/")) {
			workshops(ex, path, method, from);
			return;
		}
		switch (path) {
			case "etat" -> {
				only(ex, method, "GET");
				json(ex, 200, onServer(this::state, 5));
			}
			case "joueurs" -> {
				only(ex, method, "GET");
				json(ex, 200, onServer(this::players, 5));
			}
			case "console" -> {
				only(ex, method, "GET");
				json(ex, 200, console(ex.getRequestURI().getRawQuery()));
			}
			case "commande" -> {
				only(ex, method, "POST");
				json(ex, 200, command(readJson(ex), from));
			}
			case "reglages" -> {
				if (method.equals("GET")) {
					json(ex, 200, onServer(this::settings, 5));
				} else if (method.equals("POST")) {
					AdminSettings.Change change = AdminSettings.parse(readJson(ex));
					ValcombeMod.LOG.info("API d'administration ({}) : réglages {}", from.getHostAddress(), change);
					// Une recherche de compte Minecraft (nom inconnu du cache) peut prendre quelques secondes.
					json(ex, 200, onServer(() -> apply(change), 30));
				} else {
					throw notAllowed(ex, method, "GET, POST");
				}
			}
			case "arreter" -> {
				only(ex, method, "POST");
				stop(ex, from, false);
			}
			case "redemarrer" -> {
				only(ex, method, "POST");
				stop(ex, from, true);
			}
			default -> throw new AdminException(404, "Point d'accès inconnu : /admin/" + path);
		}
	}

	// ------------------------------------------------------------------
	// /admin/ateliers : invitations à construire
	// ------------------------------------------------------------------
	/** Un champ texte du corps, obligatoire ou non. */
	private static String text(JsonObject o, String key, boolean required) {
		if (o.has(key) && o.get(key).isJsonPrimitive()) {
			String v = o.get(key).getAsString().strip();
			if (!v.isEmpty()) {
				return v;
			}
		}
		if (required) {
			throw new AdminException(400, "Champ « " + key + " » attendu.");
		}
		return null;
	}

	private void workshops(HttpExchange ex, String path, String method, InetAddress from) throws IOException {
		switch (path) {
			case "ateliers" -> {
				if (method.equals("GET")) {
					json(ex, 200, onServer(BuildCommands::view, 5));
				} else if (method.equals("POST")) {
					// Réglages des ateliers : pour l'instant, WorldEdit dans les parties d'essai.
					JsonObject o = readJson(ex);
					if (!o.has("worldEditEssais") || !o.get("worldEditEssais").isJsonPrimitive()
						|| !o.get("worldEditEssais").getAsJsonPrimitive().isBoolean()) {
						throw new AdminException(400, "Champ « worldEditEssais » (vrai ou faux) attendu.");
					}
					boolean on = o.get("worldEditEssais").getAsBoolean();
					ValcombeMod.LOG.info("API d'administration ({}) : WorldEdit dans les parties d'essai {}", from.getHostAddress(),
						on ? "ouvert" : "fermé");
					json(ex, 200, onServer(() -> {
						var access = fr.valcombe.mc.worlds.BuildAccess.current();
						if (access == null) {
							throw new GameException("La construction n'est pas disponible sur ce serveur.");
						}
						access.invitations().setWorldEditInTrials(on);
						WorldEditGate g = WorldEditGate.current();
						if (g != null) {
							g.invitationsChanged();
						}
						return BuildCommands.view();
					}, 5));
				} else {
					throw notAllowed(ex, method, "GET, POST");
				}
			}
			case "ateliers/inviter", "ateliers/retirer" -> {
				only(ex, method, "POST");
				JsonObject o = readJson(ex);
				String player = text(o, "joueur", true);
				String workshop = text(o, "atelier", path.endsWith("inviter"));
				boolean invite = path.endsWith("inviter");
				ValcombeMod.LOG.info("API d'administration ({}) : {} {} ({})", from.getHostAddress(), invite ? "invite" : "retire l'invitation de",
					player, workshop == null ? "tous ses ateliers" : workshop);
				json(ex, 200, onServer(() -> {
					MinecraftServer s = server;
					String said = invite ? BuildCommands.invite(s, "l'API d'administration", null, player, workshop)
						: BuildCommands.uninvite(s, player, workshop);
					Map<String, Object> m = new LinkedHashMap<>(BuildCommands.view());
					m.put("message", said);
					return m;
				}, 5));
			}
			default -> throw new AdminException(404, "Point d'accès inconnu : /admin/" + path);
		}
	}

	private static void only(HttpExchange ex, String method, String expected) {
		if (!method.equals(expected)) {
			throw notAllowed(ex, method, expected);
		}
	}

	private static AdminException notAllowed(HttpExchange ex, String method, String allowed) {
		ex.getResponseHeaders().set("Allow", allowed);
		return new AdminException(405, "Méthode " + method + " refusée ici : " + allowed + " attendu.");
	}

	private static JsonObject readJson(HttpExchange ex) throws IOException {
		return AdminJson.parseObject(AdminJson.readBody(ex.getRequestBody(), declaredLength(ex), MAX_BODY));
	}

	private static long declaredLength(HttpExchange ex) {
		String h = ex.getRequestHeaders().getFirst("Content-Length");
		try {
			return h == null ? -1 : Long.parseLong(h.trim());
		} catch (NumberFormatException e) {
			throw new AdminException(400, "En-tête Content-Length invalide.");
		}
	}

	/** Exécute sur le fil du serveur, en attendant au plus {@code seconds} secondes. */
	private <T> T onServer(Supplier<T> task, int seconds) {
		MinecraftServer s = server;
		if (s == null || !s.isRunning()) {
			throw new AdminException(503, "Le serveur n'est pas prêt (démarrage ou arrêt en cours).");
		}
		try {
			return s.submit(task).get(seconds, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof AdminException ae) {
				throw ae;
			}
			if (cause instanceof GameException ge) {
				throw ge;
			}
			throw new IllegalStateException(cause == null ? "erreur" : cause.getMessage(), cause);
		} catch (TimeoutException e) {
			throw new AdminException(504, "Le serveur n'a pas répondu à temps (il est très occupé) : réessayez.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AdminException(503, "Requête interrompue.");
		}
	}

	// ------------------------------------------------------------------
	// GET /admin/etat
	// ------------------------------------------------------------------
	private Map<String, Object> state() {
		MinecraftServer s = server;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("api", API_VERSION);
		m.put("version", modVersion);
		m.put("minecraft", s.getServerVersion());
		m.put("dedie", s.isDedicatedServer());
		long now = System.currentTimeMillis();
		m.put("demarreLe", Instant.ofEpochMilli(startedAt).toString());
		m.put("dureeSecondes", (now - startedAt) / 1000);
		// TPS : la cadence visée (20), ou moins si un tick prend plus que sa part (moyenne des 100 derniers ticks).
		double ms = s.getAverageTickTimeNanos() / 1_000_000.0;
		double target = s.tickRateManager().tickrate();
		double tps = ms <= 0 ? target : Math.min(target, 1000.0 / ms);
		m.put("tps", Math.round(tps * 10) / 10.0);
		m.put("tpsCible", target);
		m.put("msParTick", Math.round(ms * 100) / 100.0);
		m.put("enVeille", ValcombeMod.asleep());
		Runtime rt = Runtime.getRuntime();
		Map<String, Object> memory = new LinkedHashMap<>();
		memory.put("utiliseeMo", (rt.totalMemory() - rt.freeMemory()) / MB);
		memory.put("reserveeMo", rt.totalMemory() / MB);
		memory.put("maxMo", rt.maxMemory() / MB);
		m.put("memoire", memory);
		Map<String, Object> players = new LinkedHashMap<>();
		players.put("connectes", s.getPlayerCount());
		players.put("max", s.getPlayerList().getMaxPlayers());
		m.put("joueurs", players);
		m.put("messageAccueil", community.welcomeShown());
		m.put("prochaineSoiree", community.nextEvening());
		m.put("motd", s.getMotd());
		m.put("mondes", worlds());
		m.put("redemarrageNecessaire", !pendingRestart(s).isEmpty());
		return m;
	}

	private static List<Map<String, Object>> worlds() {
		List<Map<String, Object>> out = new ArrayList<>();
		try {
			for (WorldsView.World w : ValcombeMod.worldsView().worlds()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", w.id());
				m.put("nom", w.name());
				m.put("sorte", w.kind());
				m.put("etat", w.state());
				m.put("joueurs", w.players());
				var access = fr.valcombe.mc.worlds.BuildAccess.current();
				var kind = fr.valcombe.mc.worlds.WorldKind.parse(w.kind());
				if (access != null && kind != null && kind.workshop()) {
					m.put("invites", access.invitations().guests(w.id())); // qui y construit, en plus des opérateurs
				}
				out.add(m);
			}
		} catch (RuntimeException e) {
			ValcombeMod.LOG.warn("API d'administration : liste des mondes illisible", e);
		}
		return out;
	}

	// ------------------------------------------------------------------
	// GET /admin/joueurs
	// ------------------------------------------------------------------
	private Map<String, Object> players() {
		MinecraftServer s = server;
		List<Map<String, Object>> list = new ArrayList<>();
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("nom", p.getScoreboardName());
			m.put("uuid", p.getUUID().toString());
			m.put("operateur", s.getPlayerList().isOp(new NameAndId(p.getUUID(), p.getScoreboardName())));
			m.put("monde", p.level().dimension().identifier().toString());
			m.put("ping", p.connection == null ? -1 : p.connection.latency());
			m.put("modeDeJeu", p.gameMode().getName());
			list.add(m);
		}
		list.sort(Comparator.comparing(m -> ((String) m.get("nom")).toLowerCase(Locale.ROOT)));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("connectes", list.size());
		out.put("max", s.getPlayerList().getMaxPlayers());
		out.put("joueurs", list);
		return out;
	}

	// ------------------------------------------------------------------
	// GET /admin/console?depuis=<n>&max=<m>
	// ------------------------------------------------------------------
	private Map<String, Object> console(String rawQuery) {
		Map<String, String> q = query(rawQuery);
		boolean follow = q.containsKey("depuis");
		int max = (int) number(q, "max", 1, 1000, follow ? 500 : 200);
		ConsoleBuffer.Page page = follow ? console.since(number(q, "depuis", 0, Long.MAX_VALUE, 0), max) : console.tail(max);
		List<Map<String, Object>> lines = new ArrayList<>(page.lines().size());
		for (ConsoleBuffer.Line l : page.lines()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("n", l.n());
			m.put("t", l.time());
			m.put("niveau", l.level());
			m.put("fil", l.thread());
			m.put("source", l.source());
			m.put("texte", l.text());
			lines.add(m);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("instance", console.instance());
		out.put("lignes", lines);
		out.put("suivant", page.next());
		out.put("premiere", page.first());
		out.put("perdues", page.lost());
		out.put("plusLoin", page.more());
		return out;
	}

	private static Map<String, String> query(String raw) {
		Map<String, String> out = new LinkedHashMap<>();
		if (raw == null || raw.isEmpty()) {
			return out;
		}
		if (raw.length() > 1000) {
			throw new AdminException(400, "Paramètres trop longs.");
		}
		for (String part : raw.split("&")) {
			if (part.isEmpty()) {
				continue;
			}
			String[] kv = part.split("=", 2);
			try {
				out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length == 2 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
			} catch (IllegalArgumentException e) {
				throw new AdminException(400, "Paramètres illisibles.");
			}
		}
		return out;
	}

	private static long number(Map<String, String> q, String name, long min, long max, long fallback) {
		String v = q.get(name);
		if (v == null) {
			return fallback;
		}
		try {
			long n = Long.parseLong(v.trim());
			if (n < min || n > max) {
				throw new NumberFormatException();
			}
			return n;
		} catch (NumberFormatException e) {
			throw new AdminException(400, name + " : nombre entier de " + min + " à " + max + " attendu.");
		}
	}

	// ------------------------------------------------------------------
	// POST /admin/commande
	// ------------------------------------------------------------------
	private Map<String, Object> command(JsonObject o, InetAddress from) {
		for (String k : o.keySet()) {
			if (!k.equals("commande")) {
				throw new AdminException(400, "Champ inconnu « " + k + " » : seul « commande » est attendu.");
			}
		}
		JsonElement c = o.get("commande");
		if (c == null || !c.isJsonPrimitive() || !c.getAsJsonPrimitive().isString()) {
			throw new AdminException(400, "« commande » : texte attendu, par exemple {\"commande\": \"list\"}.");
		}
		String cmd = c.getAsString().strip();
		if (cmd.startsWith("/")) {
			cmd = cmd.substring(1).strip();
		}
		if (cmd.isEmpty()) {
			throw new AdminException(400, "Commande vide.");
		}
		if (cmd.length() > MAX_COMMAND) {
			throw new AdminException(400, "Commande trop longue (" + MAX_COMMAND + " caractères au plus).");
		}
		if (cmd.chars().anyMatch(ch -> Character.isISOControl(ch) || ch == '\u2028' || ch == '\u2029')) {
			throw new AdminException(400, "Une seule commande à la fois, sur une ligne.");
		}
		String line = cmd;
		ValcombeMod.LOG.info("API d'administration ({}) : /{}", from.getHostAddress(), line);
		return onServer(() -> run(line), 30);
	}

	/** Exécute une commande comme la console du serveur (tous les droits) et recueille sa réponse. */
	private Map<String, Object> run(String cmd) {
		MinecraftServer s = server;
		List<String> output = new ArrayList<>();
		boolean[] success = new boolean[1];
		int[] result = new int[1];
		CommandSource capture = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				String text = message.getString();
				// Recopiée dans le journal : la console (et donc /admin/console) montre la réponse, comme pour une commande tapée.
				ValcombeMod.LOG.info("[admin] {}", text);
				if (output.size() < MAX_OUTPUT_LINES) {
					output.add(text.length() > ConsoleBuffer.MAX_CHARS ? text.substring(0, ConsoleBuffer.MAX_CHARS) + "…" : text);
				}
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
				return s.shouldInformAdmins(); // comme la console : les opérateurs en jeu voient ce qui a été fait
			}
		};
		CommandSourceStack source = s.createCommandSourceStack().withSource(capture).withCallback((ok, value) -> {
			success[0] = ok;
			result[0] = value;
		});
		s.getCommands().performPrefixedCommand(source, cmd);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", success[0]);
		out.put("resultat", result[0]);
		out.put("sortie", output);
		return out;
	}

	// ------------------------------------------------------------------
	// GET et POST /admin/reglages
	// ------------------------------------------------------------------
	private Map<String, Object> settings() {
		MinecraftServer s = server;
		Map<String, Object> m = new LinkedHashMap<>();
		Map<String, Object> wl = new LinkedHashMap<>();
		wl.put("active", s.isUsingWhitelist());
		wl.put("imposee", s.isEnforceWhitelist());
		wl.put("joueurs", s.getPlayerList().getWhiteList().getEntries().stream()
			.map(e -> e.getUser().name()).sorted(String.CASE_INSENSITIVE_ORDER).toList());
		m.put("listeBlanche", wl);
		List<Map<String, Object>> ops = new ArrayList<>();
		for (ServerOpListEntry e : s.getPlayerList().getOps().getEntries()) {
			Map<String, Object> op = new LinkedHashMap<>();
			op.put("nom", e.getUser().name());
			op.put("uuid", e.getUser().id().toString());
			op.put("niveau", e.permissions().level().id());
			ops.add(op);
		}
		ops.sort(Comparator.comparing(op -> ((String) op.get("nom")).toLowerCase(Locale.ROOT)));
		m.put("operateurs", ops);
		m.put("messageAccueil", community.welcome());
		m.put("messageAccueilParDefaut", Community.DEFAULT_WELCOME);
		m.put("prochaineSoiree", community.nextEvening());
		m.put("joueursMax", s.getPlayerList().getMaxPlayers());
		m.put("port", s.getPort());
		Integer saved = savedPort(s);
		m.put("portEnregistre", saved == null ? s.getPort() : saved);
		boolean dedicated = s instanceof DedicatedServer;
		m.put("modifiables", dedicated
			? List.of("listeBlanche", "operateurs", "messageAccueil", "prochaineSoiree", "joueursMax", "port")
			: List.of("listeBlanche", "operateurs", "messageAccueil", "prochaineSoiree"));
		List<String> pending = pendingRestart(s);
		m.put("redemarrageNecessaire", !pending.isEmpty());
		m.put("enAttente", pending);
		return m;
	}

	/** Applique une demande déjà validée, point par point : un nom inconnu n'empêche pas le reste. */
	private Map<String, Object> apply(AdminSettings.Change c) {
		MinecraftServer s = server;
		List<Map<String, Object>> results = new ArrayList<>();
		if (c.whitelistOn() != null) {
			step(results, "listeBlanche.active", c.whitelistOn(), () -> {
				s.setUsingWhitelist(c.whitelistOn());
				if (c.whitelistOn()) {
					s.kickUnlistedPlayers(); // comme « whitelist on » : ceux qui n'y sont pas sortent
				}
				return c.whitelistOn() ? "Liste blanche activée : seuls les joueurs inscrits peuvent entrer."
					: "Liste blanche désactivée : tout le monde peut entrer.";
			});
		}
		UserWhiteList wl = s.getPlayerList().getWhiteList();
		for (String name : c.whitelistAdd()) {
			step(results, "listeBlanche.ajouter", name, () -> {
				if (whitelisted(wl, name).isPresent()) {
					return "« " + name + " » est déjà dans la liste blanche.";
				}
				NameAndId id = resolve(s, name);
				if (wl.isWhiteListed(id)) {
					return "« " + id.name() + " » est déjà dans la liste blanche.";
				}
				wl.add(new UserWhiteListEntry(id));
				return "« " + id.name() + " » ajouté à la liste blanche.";
			});
		}
		boolean removed = false;
		for (String name : c.whitelistRemove()) {
			Optional<UserWhiteListEntry> e = whitelisted(wl, name);
			removed |= e.isPresent();
			step(results, "listeBlanche.retirer", name, () -> {
				if (e.isEmpty()) {
					return "« " + name + " » n'était pas dans la liste blanche.";
				}
				wl.remove(e.get().getUser());
				return "« " + e.get().getUser().name() + " » retiré de la liste blanche.";
			});
		}
		if (removed) {
			s.kickUnlistedPlayers(); // comme « whitelist remove »
		}
		for (String name : c.opsAdd()) {
			step(results, "operateurs.ajouter", name, () -> {
				if (operator(s, name).isPresent()) {
					return "« " + name + " » est déjà opérateur.";
				}
				NameAndId id = resolve(s, name);
				if (s.getPlayerList().isOp(id)) {
					return "« " + id.name() + " » est déjà opérateur.";
				}
				s.getPlayerList().op(id);
				return "« " + id.name() + " » est maintenant opérateur.";
			});
		}
		for (String name : c.opsRemove()) {
			step(results, "operateurs.retirer", name, () -> {
				Optional<ServerOpListEntry> e = operator(s, name);
				if (e.isEmpty()) {
					return "« " + name + " » n'était pas opérateur.";
				}
				s.getPlayerList().deop(e.get().getUser());
				return "« " + e.get().getUser().name() + " » n'est plus opérateur.";
			});
		}
		if (c.welcome() != null) {
			step(results, "messageAccueil", c.welcome(), () -> {
				String msg = community.setWelcome(c.welcome());
				ValcombeMod.refreshMotd(s);
				return msg;
			});
		}
		if (c.evening() != null) {
			step(results, "prochaineSoiree", c.evening(), () -> {
				String msg = community.setNextEvening(c.evening());
				ValcombeMod.refreshMotd(s);
				return msg;
			});
		}
		if (c.maxPlayers() != null) {
			step(results, "joueursMax", c.maxPlayers(), () -> {
				if (!(s instanceof DedicatedServer d)) {
					throw new GameException("Le nombre de joueurs maximal ne se règle que sur un serveur dédié.");
				}
				// Minecraft l'applique tout de suite et l'écrit dans server.properties.
				d.setMaxPlayers(c.maxPlayers());
				return "Joueurs au plus : " + c.maxPlayers() + " (tout de suite).";
			});
		}
		if (c.port() != null) {
			step(results, "port", c.port(), () -> savePort(s, c.port()));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("ok"))));
		out.put("resultats", results);
		out.put("redemarrageNecessaire", !pendingRestart(s).isEmpty());
		out.put("reglages", settings());
		return out;
	}

	private static void step(List<Map<String, Object>> results, String setting, Object value, Supplier<String> action) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("reglage", setting);
		r.put("valeur", value);
		try {
			r.put("message", action.get());
			r.put("ok", true);
		} catch (GameException | AdminException e) {
			r.put("message", e.getMessage());
			r.put("ok", false);
		} catch (RuntimeException e) {
			ValcombeMod.LOG.warn("API d'administration : réglage {} non appliqué", setting, e);
			r.put("message", "Erreur : " + e.getMessage());
			r.put("ok", false);
		}
		results.add(r);
	}

	private static Optional<UserWhiteListEntry> whitelisted(UserWhiteList wl, String name) {
		return wl.getEntries().stream().filter(e -> e.getUser().name().equalsIgnoreCase(name)).findFirst();
	}

	private static Optional<ServerOpListEntry> operator(MinecraftServer s, String name) {
		return s.getPlayerList().getOps().getEntries().stream().filter(e -> e.getUser().name().equalsIgnoreCase(name)).findFirst();
	}

	/**
	 * Le compte Minecraft de ce nom : celui du joueur connecté s'il est là, sinon le cache du serveur ou les
	 * serveurs de Mojang, comme pour « whitelist add ».
	 */
	private static NameAndId resolve(MinecraftServer s, String name) {
		ServerPlayer online = s.getPlayerList().getPlayerByName(name);
		if (online != null) {
			return new NameAndId(online.getUUID(), online.getScoreboardName());
		}
		return s.services().nameToIdCache().get(name)
			.orElseThrow(() -> new GameException("Joueur inconnu : aucun compte Minecraft ne s'appelle « " + name + " »."));
	}

	/** Valeurs de server.properties gardées en mémoire par le serveur dédié (null hors serveur dédié). */
	private static Properties properties(MinecraftServer s) {
		if (!(s instanceof DedicatedServer d)) {
			return null;
		}
		return ((SettingsAccessor) (Object) d.getProperties()).valcombe$properties();
	}

	/** Port écrit dans server.properties (celui du prochain démarrage), ou null hors serveur dédié. */
	private static Integer savedPort(MinecraftServer s) {
		Properties p = properties(s);
		if (p == null) {
			return null;
		}
		try {
			String v = p.getProperty("server-port");
			return v == null ? null : Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Enregistre le port du prochain démarrage. Minecraft ne sait pas changer de port en marche : la valeur est
	 * placée dans ses réglages en mémoire (il la réécrira donc à chaque sauvegarde de server.properties) puis le
	 * fichier est écrit tout de suite.
	 */
	private String savePort(MinecraftServer s, int port) {
		if (!(s instanceof DedicatedServer d)) {
			throw new GameException("Le port ne se règle que sur un serveur dédié.");
		}
		if (port == community.tabletPort()) {
			throw new GameException("Le port " + port + " est celui de la tablette et de l'API d'administration : choisissez-en un autre.");
		}
		properties(s).setProperty("server-port", String.valueOf(port));
		((DedicatedServerAccessor) d).valcombe$settings().forceSave();
		return port == s.getPort() ? "Port : " + port + " (inchangé)."
			: "Port enregistré : " + port + ". Il sera pris en compte au prochain redémarrage du serveur.";
	}

	/** Réglages enregistrés qui n'agiront qu'après un redémarrage. */
	private static List<String> pendingRestart(MinecraftServer s) {
		Integer saved = savedPort(s);
		return saved != null && saved != s.getPort() ? List.of("port") : List.of();
	}

	// ------------------------------------------------------------------
	// POST /admin/arreter et /admin/redemarrer
	// ------------------------------------------------------------------
	private void stop(HttpExchange ex, InetAddress from, boolean restart) throws IOException {
		MinecraftServer s = server;
		if (s == null || !s.isRunning()) {
			throw new AdminException(503, "Le serveur n'est pas prêt (démarrage ou arrêt en cours).");
		}
		if (!s.isDedicatedServer()) {
			throw new AdminException(409, "Arrêt et redémarrage par l'API seulement sur un serveur dédié : une partie solo se ferme depuis le jeu.");
		}
		AdminJson.readBody(ex.getRequestBody(), declaredLength(ex), 1024); // corps ignoré, mais borné
		if (restart) {
			try {
				RestartFlag.write(valcombeDir, "API d'administration (" + from.getHostAddress() + ")", Instant.now());
			} catch (IOException e) {
				throw new AdminException(500, "Drapeau de redémarrage impossible à écrire : " + e.getMessage() + ". Le serveur n'est pas arrêté.");
			}
		}
		ValcombeMod.LOG.info("API d'administration ({}) : {} du serveur demandé", from.getHostAddress(), restart ? "redémarrage" : "arrêt");
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("message", restart ? "Redémarrage : le serveur s'arrête proprement, puis celui qui l'a lancé le relance."
			: "Arrêt du serveur en cours.");
		out.put("drapeau", restart ? "config/valcombe/" + RestartFlag.FILE_NAME : null);
		// La réponse part d'abord : l'arrêt ferme le serveur web, et la requête avec.
		json(ex, 202, out);
		s.execute(() -> s.halt(false)); // comme /stop : les mondes et la partie sont enregistrés
	}

	// ------------------------------------------------------------------
	// /admin/sauvegardes : liste, sauvegarde, réglages, restauration, téléchargement, suppression
	// ------------------------------------------------------------------
	private void backups(HttpExchange ex, String sub, String method, InetAddress from) throws IOException {
		BackupService b = ValcombeMod.backups();
		if (b == null) {
			throw new AdminException(503, "Le serveur n'est pas prêt (démarrage ou arrêt en cours).");
		}
		String by = "API d'administration (" + from.getHostAddress() + ")";
		switch (sub) {
			case "" -> {
				if (method.equals("GET")) {
					json(ex, 200, backupList(b));
				} else if (method.equals("POST")) {
					noFields(ex);
					BackupService.Job job = b.start(BackupKind.MANUELLE, by, null);
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("ok", true);
					out.put("message", "Sauvegarde " + job.name + " lancée : suivez-la dans GET /admin/sauvegardes (enCours).");
					out.put("nom", job.name);
					out.put("enCours", job.toMap());
					json(ex, 202, out);
				} else {
					throw notAllowed(ex, method, "GET, POST");
				}
			}
			case "reglages" -> {
				if (method.equals("GET")) {
					json(ex, 200, backupSettings(b));
				} else if (method.equals("POST")) {
					BackupSettings.Change change = BackupSettings.parse(readJson(ex));
					BackupSettings.Values v;
					try {
						v = b.settings().apply(change);
					} catch (IOException e) {
						throw new AdminException(500, "Réglages des sauvegardes impossibles à enregistrer : " + e.getMessage());
					}
					ValcombeMod.LOG.info("API d'administration ({}) : réglages des sauvegardes {}", from.getHostAddress(), v);
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("ok", true);
					out.put("message", v.actives() ? "Sauvegardes automatiques : " + BackupService.describe(v) + "."
						: "Sauvegardes automatiques désactivées : seulement à la demande.");
					out.putAll(backupSettings(b));
					json(ex, 200, out);
				} else {
					throw notAllowed(ex, method, "GET, POST");
				}
			}
			case "restaurer" -> {
				if (method.equals("POST")) {
					restore(ex, b, readJson(ex), from, by);
				} else if (method.equals("DELETE")) {
					boolean had;
					try {
						had = b.cancelRestore();
					} catch (IOException e) {
						throw new AdminException(500, "Demande de restauration impossible à retirer : " + e.getMessage());
					}
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("ok", true);
					out.put("annulee", had);
					out.put("message", had ? "Restauration prévue annulée." : "Aucune restauration n'était prévue.");
					json(ex, 200, out);
				} else {
					throw notAllowed(ex, method, "POST, DELETE");
				}
			}
			default -> {
				if (!BackupName.valid(sub)) {
					throw new AdminException(404, "Point d'accès inconnu : /admin/sauvegardes/" + sub);
				}
				if (method.equals("GET")) {
					download(ex, b, sub, from);
				} else if (method.equals("DELETE")) {
					boolean done;
					try {
						done = b.delete(sub, by);
					} catch (IOException e) {
						throw new AdminException(500, "Sauvegarde impossible à effacer : " + e.getMessage());
					}
					if (!done) {
						throw new AdminException(404, "Aucune sauvegarde ne s'appelle « " + sub + " ».");
					}
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("ok", true);
					out.put("message", "Sauvegarde « " + sub + " » effacée.");
					json(ex, 200, out);
				} else {
					throw notAllowed(ex, method, "GET, DELETE");
				}
			}
		}
	}

	/** Corps vide ou {} : ce point d'accès ne prend aucun champ. */
	private static void noFields(HttpExchange ex) throws IOException {
		String body = AdminJson.readBody(ex.getRequestBody(), declaredLength(ex), 1024);
		if (!body.isBlank()) {
			JsonObject o = AdminJson.parseObject(body);
			if (!o.keySet().isEmpty()) {
				throw new AdminException(400, "Aucun champ attendu ici (« " + o.keySet().iterator().next() + " » reçu).");
			}
		}
	}

	/** GET /admin/sauvegardes */
	private static Map<String, Object> backupList(BackupService b) {
		List<BackupStore.Backup> all;
		try {
			all = b.store().list();
		} catch (IOException e) {
			throw new AdminException(500, "Dossier des sauvegardes illisible : " + e.getMessage());
		}
		List<Map<String, Object>> list = new ArrayList<>();
		long total = 0;
		for (BackupStore.Backup bk : all) {
			total += bk.size();
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("nom", bk.name());
			m.put("sorte", bk.kind().id());
			m.put("date", bk.parsed().at(b.zone()).toOffsetDateTime().toString());
			m.put("octets", bk.size());
			m.put("rotation", bk.kind().rotated());
			try {
				BackupManifest man = b.store().manifest(bk);
				m.put("lisible", true);
				m.put("par", man.par);
				m.put("versionMod", man.versionMod);
				m.put("fichiers", man.fichiers);
				m.put("octetsDecompresses", man.octets);
				m.put("mondes", man.mondes);
			} catch (IOException e) {
				m.put("lisible", false);
				m.put("erreur", e.getMessage());
			}
			list.add(m);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("dossier", BackupStore.DIR_NAME);
		out.put("sauvegardes", list);
		out.put("nombre", list.size());
		out.put("octetsTotal", total);
		out.put("espaceLibre", b.store().freeSpace());
		BackupService.Job job = b.running();
		out.put("enCours", job == null ? null : job.toMap());
		BackupService.Outcome last = b.last();
		out.put("derniere", last == null ? null : last.toMap());
		BackupSettings.Values v = b.settings().get();
		Instant next = b.nextAutomatic(v);
		out.put("prochaineAuto", next == null ? null : next.toString());
		out.put("reportee", b.postponed());
		out.put("restaurationPossible", b.canRestore());
		out.put("restaurationPrevue", b.pendingRestore().map(r -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("nom", r.nom());
			m.put("demandeLe", r.demandeLe().toString());
			m.put("par", r.par());
			return m;
		}).orElse(null));
		out.put("derniereRestauration", Restorer.lastReport(b.store().dir()).map(Restorer.Report::toMap).orElse(null));
		out.put("reglages", v.toMap());
		return out;
	}

	/** GET /admin/sauvegardes/reglages (et la fin de la réponse au POST). */
	private static Map<String, Object> backupSettings(BackupService b) {
		BackupSettings.Values v = b.settings().get();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("reglages", v.toMap());
		Map<String, Object> bounds = new LinkedHashMap<>();
		bounds.put("intervalleMinutes", List.of(BackupSettings.MIN_INTERVAL, BackupSettings.MAX_INTERVAL));
		bounds.put("garder", List.of(BackupSettings.MIN_KEEP, BackupSettings.MAX_KEEP));
		out.put("bornes", bounds);
		out.put("parDefaut", BackupSettings.DEFAULTS.toMap());
		out.put("automatiquesPossibles", b.canRestore()); // serveur dédié : mêmes conditions que la restauration
		Instant next = b.nextAutomatic(v);
		out.put("prochaineAuto", next == null ? null : next.toString());
		return out;
	}

	/** POST /admin/sauvegardes/restaurer : {"nom": "...", "redemarrer": true} */
	private void restore(HttpExchange ex, BackupService b, JsonObject o, InetAddress from, String by) throws IOException {
		List<String> errors = new ArrayList<>();
		for (String k : o.keySet()) {
			if (!k.equals("nom") && !k.equals("redemarrer")) {
				errors.add("champ inconnu « " + k + " »");
			}
		}
		JsonElement n = o.get("nom");
		String name = n != null && n.isJsonPrimitive() && n.getAsJsonPrimitive().isString() ? n.getAsString() : null;
		if (name == null) {
			errors.add("« nom » : nom de la sauvegarde attendu (texte)");
		} else if (!BackupName.valid(name)) {
			errors.add("« nom » : « " + name + " » n'est pas un nom de sauvegarde (valcombe-AAAA-MM-JJ_HH-MM-SS-sorte.zip)");
		}
		JsonElement r = o.get("redemarrer");
		boolean restart = false;
		if (r != null) {
			if (r.isJsonPrimitive() && r.getAsJsonPrimitive().isBoolean()) {
				restart = r.getAsBoolean();
			} else {
				errors.add("« redemarrer » : vrai ou faux attendu (true / false)");
			}
		}
		if (!errors.isEmpty()) {
			throw new AdminException(400, "Restauration refusée : " + String.join(" ; ", errors) + ".");
		}
		if (!b.canRestore()) {
			throw new AdminException(409, "La restauration ne se fait que sur un serveur dédié.");
		}
		MinecraftServer s = server;
		if (restart && (s == null || !s.isRunning())) {
			throw new AdminException(503, "Le serveur n'est pas prêt (démarrage ou arrêt en cours).");
		}
		String message;
		try {
			if (b.store().find(name).isEmpty()) {
				throw new AdminException(404, "Aucune sauvegarde ne s'appelle « " + name + " ».");
			}
			message = b.scheduleRestore(name, by);
		} catch (IOException e) {
			throw new AdminException(500, "Restauration impossible à prévoir : " + e.getMessage());
		}
		ValcombeMod.LOG.info("API d'administration ({}) : restauration de {} prévue{}", from.getHostAddress(), name,
			restart ? ", redémarrage demandé" : "");
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("nom", name);
		out.put("redemarrage", restart);
		if (!restart) {
			out.put("message", message + " Redémarrez le serveur pour l'appliquer (POST /admin/redemarrer).");
			out.put("drapeau", null);
			json(ex, 200, out);
			return;
		}
		try {
			RestartFlag.write(valcombeDir, "restauration de " + name + " (" + by + ")", Instant.now());
		} catch (IOException e) {
			throw new AdminException(500, "Drapeau de redémarrage impossible à écrire : " + e.getMessage()
				+ ". La restauration reste prévue au prochain démarrage ; le serveur n'est pas arrêté.");
		}
		out.put("message", message + " Le serveur s'arrête proprement, puis celui qui l'a lancé le relance.");
		out.put("drapeau", "config/valcombe/" + RestartFlag.FILE_NAME);
		json(ex, 202, out);
		s.execute(() -> s.halt(false)); // comme /admin/redemarrer
	}

	/** GET /admin/sauvegardes/<nom> : l'archive elle-même. */
	private static void download(HttpExchange ex, BackupService b, String name, InetAddress from) throws IOException {
		Path f = b.store().resolve(name);
		long size;
		InputStream in;
		try {
			size = Files.size(f);
			in = Files.newInputStream(f);
		} catch (NoSuchFileException e) {
			throw new AdminException(404, "Aucune sauvegarde ne s'appelle « " + name + " ».");
		}
		ValcombeMod.LOG.info("API d'administration ({}) : téléchargement de la sauvegarde {}", from.getHostAddress(), name);
		try (in) {
			ex.getResponseHeaders().set("Content-Type", "application/zip");
			ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
			ex.getResponseHeaders().set("Cache-Control", "no-store");
			ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
			ex.sendResponseHeaders(200, size);
			try (OutputStream os = ex.getResponseBody()) {
				in.transferTo(os);
			}
		}
	}

	// ------------------------------------------------------------------
	// Réponses
	// ------------------------------------------------------------------
	private static void error(HttpExchange ex, int status, String message) throws IOException {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("erreur", message);
		json(ex, status, m);
	}

	private static void json(HttpExchange ex, int status, Object body) throws IOException {
		byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.getResponseHeaders().set("Cache-Control", "no-store");
		ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}
}
