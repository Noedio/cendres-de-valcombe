package fr.valcombe.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.valcombe.mc.ValcombeMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * L'API d'administration du mod, vue d'un test en jeu : ouverte sur le port des tests (25591, jamais celui d'un serveur
 * Valcombe déjà lancé sur ce PC), appelée avec la clé d'administration. Une requête part sans bloquer le fil du test, qui
 * laisse passer les ticks jusqu'à la réponse (le serveur intégré répond pendant ce temps).
 */
final class AdminHttp {
	static final String HEADER = "X-Valcombe-Admin";
	/** Le port des tests : un serveur Valcombe déjà lancé sur ce PC garde le sien. */
	static final int TEST_PORT = 25591;

	private final HttpClient http = HttpClient.newHttpClient();
	private final String base;
	private final int maxTicks;

	/**
	 * @param port     le port où l'API répond
	 * @param maxTicks le plus de ticks qu'on attend une réponse
	 */
	AdminHttp(int port, int maxTicks) {
		this.base = "http://127.0.0.1:" + port;
		this.maxTicks = maxTicks;
	}

	/** Ouvre l'API d'administration sur le port des tests (ou reprend celle qui est ouverte) et s'y adresse. */
	static AdminHttp open(int maxTicks) {
		if (ValcombeMod.community().tabletPort() != TEST_PORT) {
			ValcombeMod.community().setTabletPort(TEST_PORT);
		}
		try {
			int port = ValcombeMod.tablet().adminOpen() ? ValcombeMod.tablet().port() : ValcombeMod.tablet().openAdmin();
			return new AdminHttp(port, maxTicks);
		} catch (java.io.IOException e) {
			throw new AssertionError("API d'administration non ouverte", e);
		}
	}

	HttpClient client() {
		return http;
	}

	String base() {
		return base;
	}

	/** Une requête vers ce chemin, clé d'administration comprise. */
	HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create(base + path)).header(HEADER, ValcombeMod.adminKey().key());
	}

	HttpRequest postRequest(String path, String body) {
		return request(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
	}

	/** Envoie la requête et laisse passer les ticks jusqu'à sa réponse (au plus {@code maxTicks}). */
	<T> HttpResponse<T> fetch(ClientGameTestContext ctx, HttpRequest req, HttpResponse.BodyHandler<T> handler) {
		var future = http.sendAsync(req, handler);
		for (int i = 0; i < maxTicks && !future.isDone(); i++) {
			ctx.waitTick();
		}
		return future.join();
	}

	HttpResponse<String> get(ClientGameTestContext ctx, String path) {
		return fetch(ctx, request(path).build(), HttpResponse.BodyHandlers.ofString());
	}

	HttpResponse<String> post(ClientGameTestContext ctx, String path, String body) {
		return fetch(ctx, postRequest(path, body), HttpResponse.BodyHandlers.ofString());
	}

	HttpResponse<String> delete(ClientGameTestContext ctx, String path) {
		return fetch(ctx, request(path).DELETE().build(), HttpResponse.BodyHandlers.ofString());
	}

	static JsonObject json(HttpResponse<String> r) {
		return JsonParser.parseString(r.body()).getAsJsonObject();
	}
}
