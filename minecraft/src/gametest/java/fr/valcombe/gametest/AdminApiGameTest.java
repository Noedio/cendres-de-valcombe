package fr.valcombe.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * L'API d'administration par de vraies requêtes HTTP, sur une partie solo : clé exigée, état, joueurs,
 * console, commande, réglages, refus (JSON fautif, corps trop gros, arrêt hors serveur dédié), changement
 * de clé et blocage après des essais ratés. Le serveur dédié (arrêt, redémarrage, joueurs max, port) n'existe
 * pas dans ce test : ces points d'accès doivent répondre qu'ils ne s'y appliquent pas.
 */
public class AdminApiGameTest implements FabricClientGameTest {
	private static final String HEADER = "X-Valcombe-Admin";

	private HttpClient http;
	private String base;

	/** Requête HTTP pendant que le jeu continue de tourner (le serveur ne répond qu'entre deux ticks). */
	private HttpResponse<String> fetch(ClientGameTestContext ctx, HttpRequest req) {
		var future = http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		for (int i = 0; i < 400 && !future.isDone(); i++) {
			ctx.waitTick();
		}
		return future.join();
	}

	private HttpResponse<String> get(ClientGameTestContext ctx, String path, String key) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
		if (key != null) {
			b.header(HEADER, key);
		}
		return fetch(ctx, b.build());
	}

	private HttpResponse<String> post(ClientGameTestContext ctx, String path, String key, String body) {
		return fetch(ctx, HttpRequest.newBuilder(URI.create(base + path)).header(HEADER, key)
			.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
	}

	private static JsonObject json(HttpResponse<String> r) {
		return JsonParser.parseString(r.body()).getAsJsonObject();
	}

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("Échec : " + what);
		}
	}

	private static void status(HttpResponse<String> r, int expected, String what) {
		check(r.statusCode() == expected, what + " : " + r.statusCode() + " " + r.body());
		check(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), what + " : réponse JSON");
	}

	private static String key() {
		return fr.valcombe.mc.ValcombeMod.adminKey().key();
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			ctx.waitTicks(60);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			world.getServer().runCommand("op " + me);
			ctx.waitTicks(20);
			// Port de test à part : un serveur Valcombe déjà lancé sur ce PC garde le sien.
			if (fr.valcombe.mc.ValcombeMod.community().tabletPort() != 25591) {
				fr.valcombe.mc.ValcombeMod.community().setTabletPort(25591);
			}
			check(fr.valcombe.mc.ValcombeMod.adminKey() != null, "clé d'administration prête");
			check(key().matches("[0-9a-f]{64}"), "clé de 32 octets");
			check(!fr.valcombe.mc.ValcombeMod.tablet().adminOpen(), "API fermée d'office en solo");
			int port;
			try {
				port = fr.valcombe.mc.ValcombeMod.tablet().openAdmin();
			} catch (java.io.IOException e) {
				throw new AssertionError("API d'administration non ouverte", e);
			}
			check(port == 25591, "API sur le port de test : " + port);
			http = HttpClient.newHttpClient();
			base = "http://127.0.0.1:" + port;

			// Clé exigée
			status(get(ctx, "/admin/etat", null), 401, "sans clé");
			status(get(ctx, "/admin/etat", "0".repeat(64)), 401, "mauvaise clé");

			// État
			var etat = get(ctx, "/admin/etat", key());
			status(etat, 200, "état");
			JsonObject e = json(etat);
			System.out.println("[valcombe-test] admin/etat : " + etat.body());
			check(e.get("api").getAsInt() == 1, "version de l'API");
			check(!e.get("version").getAsString().isEmpty() && !e.get("dedie").getAsBoolean(), "version du mod, serveur solo");
			check(e.getAsJsonObject("joueurs").get("connectes").getAsInt() == 1, "un joueur connecté");
			check(e.getAsJsonObject("memoire").get("maxMo").getAsLong() > 0, "mémoire");
			check(e.get("tps").getAsDouble() > 0, "TPS");
			check(e.getAsJsonArray("mondes").isEmpty(), "pas encore de mondes");

			// Joueurs
			var joueurs = get(ctx, "/admin/joueurs", key());
			status(joueurs, 200, "joueurs");
			JsonObject j = json(joueurs).getAsJsonArray("joueurs").get(0).getAsJsonObject();
			check(j.get("nom").getAsString().equals(me) && j.get("operateur").getAsBoolean(), "joueur opérateur listé : " + joueurs.body());
			check(j.get("monde").getAsString().equals("minecraft:overworld"), "monde du joueur");

			// Commande, puis sa trace dans la console
			var list = post(ctx, "/admin/commande", key(), "{\"commande\": \"/list\"}");
			status(list, 200, "commande list");
			JsonObject l = json(list);
			check(l.get("ok").getAsBoolean() && l.getAsJsonArray("sortie").toString().contains(me), "sortie de list : " + list.body());
			var unknown = post(ctx, "/admin/commande", key(), "{\"commande\": \"pasunecommande\"}");
			status(unknown, 200, "commande inconnue");
			check(!json(unknown).get("ok").getAsBoolean() && !json(unknown).getAsJsonArray("sortie").isEmpty(), "commande inconnue signalée");
			status(post(ctx, "/admin/commande", key(), "{\"commande\": 5}"), 400, "commande non textuelle");
			status(post(ctx, "/admin/commande", key(), "{\"commande\": \"say a\\nop x\"}"), 400, "deux lignes refusées");
			status(post(ctx, "/admin/commande", key(), "{\"commande\": \"list\""), 400, "JSON fautif");
			// Corps envoyé par morceaux (sans longueur annoncée) : la lecture s'arrête d'elle-même à la limite.
			byte[] big = ("{\"commande\": \"" + "x".repeat(70_000) + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
			status(fetch(ctx, HttpRequest.newBuilder(URI.create(base + "/admin/commande")).header(HEADER, key())
				.POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(big))).build()), 413, "corps trop gros");

			var console = get(ctx, "/admin/console?max=1000", key());
			status(console, 200, "console");
			JsonObject c = json(console);
			check(c.getAsJsonArray("lignes").toString().contains("[admin]"), "réponse de la commande dans la console");
			long next = c.get("suivant").getAsLong();
			check(next > 0, "numéro de la dernière ligne");
			post(ctx, "/admin/commande", key(), "{\"commande\": \"list\"}");
			JsonObject after = json(get(ctx, "/admin/console?depuis=" + next, key()));
			JsonArray fresh = after.getAsJsonArray("lignes");
			check(!fresh.isEmpty() && fresh.get(0).getAsJsonObject().get("n").getAsLong() == next + 1, "seulement les nouvelles lignes : " + fresh);
			status(get(ctx, "/admin/console?depuis=abc", key()), 400, "numéro illisible");

			// Réglages
			var reglages = get(ctx, "/admin/reglages", key());
			status(reglages, 200, "réglages");
			JsonObject r = json(reglages);
			check(!r.getAsJsonArray("modifiables").toString().contains("joueursMax"), "joueurs max réglable seulement sur serveur dédié");
			check(!r.get("redemarrageNecessaire").getAsBoolean(), "pas de redémarrage en attente");
			var welcome = post(ctx, "/admin/reglages", key(), "{\"messageAccueil\": \"Bienvenue au bourg d'essai\","
				+ " \"listeBlanche\": {\"ajouter\": [\"" + me + "\"]}, \"operateurs\": {\"ajouter\": [\"" + me + "\"]}}");
			status(welcome, 200, "réglages changés");
			JsonObject w = json(welcome);
			System.out.println("[valcombe-test] admin/reglages : " + welcome.body());
			check(w.get("ok").getAsBoolean(), "réglages appliqués");
			check(w.getAsJsonObject("reglages").get("messageAccueil").getAsString().equals("Bienvenue au bourg d'essai"), "message d'accueil");
			check(w.getAsJsonObject("reglages").getAsJsonObject("listeBlanche").getAsJsonArray("joueurs").toString().contains(me),
				"joueur dans la liste blanche");
			check(w.getAsJsonArray("resultats").toString().contains("déjà opérateur"), "déjà opérateur signalé");
			if (fr.valcombe.mc.ValcombeMod.community().nextEvening().isEmpty()) {
				check(world.getServer().computeOnServer(s -> s.getMotd()).startsWith("Bienvenue au bourg d'essai"), "description du serveur");
			}
			var undo = post(ctx, "/admin/reglages", key(), "{\"messageAccueil\": \"\", \"listeBlanche\": {\"retirer\": [\"" + me + "\"]}}");
			status(undo, 200, "réglages remis");
			check(json(undo).getAsJsonObject("reglages").get("messageAccueil").getAsString().isEmpty(), "message d'accueil par défaut");
			var dedicatedOnly = post(ctx, "/admin/reglages", key(), "{\"joueursMax\": 12}");
			status(dedicatedOnly, 200, "joueurs max en solo");
			check(!json(dedicatedOnly).get("ok").getAsBoolean() && dedicatedOnly.body().contains("serveur dédié"), "joueurs max refusé en solo");
			status(post(ctx, "/admin/reglages", key(), "{\"joueursMax\": \"douze\"}"), 400, "réglage fautif");
			status(post(ctx, "/admin/reglages", key(), "{\"difficulte\": 3}"), 400, "réglage inconnu");

			// Arrêt et redémarrage : seulement sur un serveur dédié
			status(post(ctx, "/admin/arreter", key(), "{}"), 409, "arrêt refusé en solo");
			status(post(ctx, "/admin/redemarrer", key(), "{}"), 409, "redémarrage refusé en solo");
			status(get(ctx, "/admin/inconnu", key()), 404, "point d'accès inconnu");
			var method = fetch(ctx, HttpRequest.newBuilder(URI.create(base + "/admin/etat")).header(HEADER, key())
				.method("DELETE", HttpRequest.BodyPublishers.noBody()).build());
			status(method, 405, "méthode refusée");

			// Nouvelle clé : l'ancienne ne marche plus
			String old = key();
			world.getServer().runCommand("vc admin nouvellecle");
			ctx.waitTicks(5);
			check(!key().equals(old), "clé remplacée");
			status(get(ctx, "/admin/etat", old), 401, "ancienne clé refusée");
			status(get(ctx, "/admin/etat", key()), 200, "nouvelle clé acceptée");

			// Cinq essais ratés : l'adresse est bloquée, même pour la bonne clé
			HttpResponse<String> last = null;
			for (int i = 0; i < 5; i++) {
				last = get(ctx, "/admin/etat", "faux" + i);
			}
			status(last, 429, "blocage après cinq essais ratés");
			check(last.headers().firstValue("Retry-After").isPresent(), "délai indiqué");
			status(get(ctx, "/admin/etat", key()), 429, "bonne clé pendant le blocage");
			// Une nouvelle clé lève les blocages
			world.getServer().runCommand("vc admin nouvellecle");
			ctx.waitTicks(5);
			status(get(ctx, "/admin/etat", key()), 200, "blocage levé par la nouvelle clé");
			System.out.println("[valcombe-test] API d'administration OK");
		}
	}
}
