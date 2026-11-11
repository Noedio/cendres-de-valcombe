package fr.valcombe.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import fr.valcombe.rules.GameException;
import fr.valcombe.session.GameReport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Vie de la communauté : statistiques par joueur (config/valcombe-stats.json) et compte rendu
 * de partie sur Discord par webhook (config/valcombe-communaute.json).
 */
public final class Community {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Réglages enregistrés. */
	static final class Config {
		String discordWebhook = "";
		int tabletPort = 25570;
	}

	/** Statistiques d'un joueur. */
	static final class Stats {
		int games;
		int wins;
		int good;
		int evil;
		int survived;
		int storyteller;
		String last = "";
		Map<String, Integer> roles = new LinkedHashMap<>();
	}

	private final Path dir;
	private Config config;
	private Map<String, Stats> stats;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public Community(Path configDir) {
		this.dir = configDir;
	}

	// ------------------------------------------------------------------
	// Réglages
	// ------------------------------------------------------------------
	Config config() {
		if (config == null) {
			config = read(dir.resolve("valcombe-communaute.json"), Config.class, new Config());
		}
		return config;
	}

	private void saveConfig() {
		write(dir.resolve("valcombe-communaute.json"), config());
	}

	public int tabletPort() {
		return config().tabletPort;
	}

	public String setTabletPort(int port) {
		if (port < 1024 || port > 65535) {
			throw new GameException("Port entre 1024 et 65535.");
		}
		config().tabletPort = port;
		saveConfig();
		return "Port de la tablette : " + port + " (pris en compte au prochain /vc tablette).";
	}

	public boolean hasWebhook() {
		return !config().discordWebhook.isEmpty();
	}

	public String setWebhook(String url) {
		String u = url.trim();
		if (u.equalsIgnoreCase("aucun")) {
			config().discordWebhook = "";
			saveConfig();
			return "Compte rendu Discord désactivé.";
		}
		if (!u.startsWith("https://discord.com/api/webhooks/") && !u.startsWith("https://discordapp.com/api/webhooks/")
			&& !u.startsWith("https://ptb.discord.com/api/webhooks/") && !u.startsWith("https://canary.discord.com/api/webhooks/")) {
			throw new GameException("Adresse de webhook Discord attendue (https://discord.com/api/webhooks/...).");
		}
		config().discordWebhook = u;
		saveConfig();
		return "Webhook Discord enregistré. Essai : /vc discord essai";
	}

	// ------------------------------------------------------------------
	// Fin de partie
	// ------------------------------------------------------------------
	/** Met à jour les statistiques et envoie le compte rendu (si un webhook est réglé). */
	public void record(GameReport r) {
		Map<String, Stats> all = stats();
		String today = LocalDate.now().toString();
		for (GameReport.Player p : r.players()) {
			if (p.bot()) {
				continue;
			}
			Stats s = all.computeIfAbsent(p.name(), k -> new Stats());
			s.games++;
			if (p.won(r.winner())) {
				s.wins++;
			}
			if (p.team().equals("EVIL")) {
				s.evil++;
			} else {
				s.good++;
			}
			if (p.alive()) {
				s.survived++;
			}
			s.roles.merge(p.role(), 1, Integer::sum);
			s.last = today;
		}
		if (r.storyteller() != null) {
			Stats s = all.computeIfAbsent(r.storyteller(), k -> new Stats());
			s.storyteller++;
			s.last = today;
		}
		write(dir.resolve("valcombe-stats.json"), all);
		if (hasWebhook()) {
			post(discordMessage(r)).exceptionally(e -> {
				ValcombeMod.LOG.warn("Compte rendu Discord non envoyé : {}", e.getMessage());
				return null;
			});
		}
	}

	/** Message d'essai, pour vérifier le webhook. Retourne le futur de l'envoi. */
	public CompletableFuture<Void> test() {
		if (!hasWebhook()) {
			throw new GameException("Aucun webhook réglé : /vc discord <adresse>");
		}
		JsonObject msg = new JsonObject();
		msg.addProperty("username", "Valcombe");
		msg.addProperty("content", "Les cloches de Valcombe sonnent : le compte rendu des parties arrivera ici.");
		return post(msg);
	}

	static JsonObject discordMessage(GameReport r) {
		boolean good = r.winner().equals("GOOD");
		StringBuilder d = new StringBuilder();
		d.append("*").append(r.reason()).append("*\n\n");
		for (GameReport.Player p : r.players()) {
			d.append(p.team().equals("EVIL") ? "🔴 " : "🔵 ")
				.append("**").append(escape(p.name())).append("** — ").append(p.role());
			if (p.believed() != null) {
				d.append(" (croyait être ").append(p.believed()).append(")");
			}
			if (!p.alive()) {
				d.append(" ✝");
			}
			d.append("\n");
		}
		String desc = d.length() > 3900 ? d.substring(0, 3900) + "…" : d.toString();
		JsonObject embed = new JsonObject();
		embed.addProperty("title", good ? "Victoire du bien" : "Victoire du mal");
		embed.addProperty("description", desc);
		embed.addProperty("color", good ? 0x3A7BD5 : 0xB02A2A);
		JsonArray fields = new JsonArray();
		fields.add(field("Jours", String.valueOf(r.days())));
		fields.add(field("Joueurs", String.valueOf(r.players().size())));
		fields.add(field("Script", r.script()));
		if (r.storyteller() != null) {
			fields.add(field("Conteur", escape(r.storyteller())));
		}
		embed.add("fields", fields);
		JsonObject footer = new JsonObject();
		footer.addProperty("text", "Les Cendres de Valcombe");
		embed.add("footer", footer);
		JsonObject msg = new JsonObject();
		msg.addProperty("username", "Valcombe");
		JsonArray embeds = new JsonArray();
		embeds.add(embed);
		msg.add("embeds", embeds);
		JsonObject mentions = new JsonObject();
		mentions.add("parse", new JsonArray()); // aucune mention (@everyone, etc.)
		msg.add("allowed_mentions", mentions);
		return msg;
	}

	private static JsonObject field(String name, String value) {
		JsonObject f = new JsonObject();
		f.addProperty("name", name);
		f.addProperty("value", value.isEmpty() ? "—" : value);
		f.addProperty("inline", true);
		return f;
	}

	private static String escape(String s) {
		return s.replace("_", "\\_").replace("*", "\\*");
	}

	private CompletableFuture<Void> post(JsonObject body) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(config().discordWebhook))
			.timeout(Duration.ofSeconds(15))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
			.build();
		return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(resp -> {
			if (resp.statusCode() >= 300) {
				throw new IllegalStateException("Discord a répondu " + resp.statusCode());
			}
		});
	}

	// ------------------------------------------------------------------
	// Statistiques
	// ------------------------------------------------------------------
	private Map<String, Stats> stats() {
		if (stats == null) {
			Map<String, Stats> read = read(dir.resolve("valcombe-stats.json"),
				new com.google.gson.reflect.TypeToken<Map<String, Stats>>() { }.getType(), null);
			stats = read == null ? new LinkedHashMap<>() : new LinkedHashMap<>(read);
		}
		return stats;
	}

	/** Fiche d'un joueur, ou classement général si name est null. */
	public List<String> statsLines(String name) {
		List<String> out = new ArrayList<>();
		Map<String, Stats> all = stats();
		if (name == null) {
			if (all.isEmpty()) {
				out.add("Aucune partie enregistrée pour l'instant.");
				return out;
			}
			out.add("Classement de Valcombe (victoires / parties) :");
			all.entrySet().stream()
				.filter(e -> e.getValue().games > 0)
				.sorted(Comparator.comparingInt((Map.Entry<String, Stats> e) -> -e.getValue().wins)
					.thenComparingInt(e -> e.getValue().games))
				.limit(10)
				.forEach(e -> out.add("  " + e.getKey() + " : " + e.getValue().wins + " / " + e.getValue().games
					+ " (" + percent(e.getValue().wins, e.getValue().games) + ")"));
			return out;
		}
		Stats s = all.get(name);
		if (s == null) {
			out.add(name + " n'a encore joué aucune partie.");
			return out;
		}
		out.add("Statistiques de " + name + " :");
		out.add("  Parties : " + s.games + ", victoires : " + s.wins + " (" + percent(s.wins, s.games) + ")");
		out.add("  Dans le bien : " + s.good + ", dans le mal : " + s.evil + ", survivant en fin de partie : " + s.survived);
		if (s.storyteller > 0) {
			out.add("  Parties menées comme Conteur : " + s.storyteller);
		}
		if (!s.roles.isEmpty()) {
			StringBuilder roles = new StringBuilder("  Rôles joués : ");
			s.roles.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.forEach(e -> roles.append(e.getKey()).append(" ×").append(e.getValue()).append(", "));
			out.add(roles.substring(0, roles.length() - 2));
		}
		return out;
	}

	private static String percent(int a, int b) {
		return b == 0 ? "0 %" : Math.round(100.0 * a / b) + " %";
	}

	// ------------------------------------------------------------------
	// Fichiers
	// ------------------------------------------------------------------
	private static <T> T read(Path file, java.lang.reflect.Type type, T fallback) {
		try {
			if (Files.exists(file)) {
				T t = GSON.fromJson(Files.readString(file), type);
				if (t != null) {
					return t;
				}
			}
		} catch (IOException | RuntimeException e) {
			ValcombeMod.LOG.warn("Fichier illisible, ignoré : {}", file);
		}
		return fallback;
	}

	private static void write(Path file, Object value) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(value));
		} catch (IOException e) {
			ValcombeMod.LOG.warn("Impossible d'écrire {}", file, e);
		}
	}
}
