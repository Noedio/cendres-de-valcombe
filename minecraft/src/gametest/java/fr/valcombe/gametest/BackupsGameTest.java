package fr.valcombe.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import fr.valcombe.mc.BackupService;
import fr.valcombe.mc.ValcombeMod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Les sauvegardes sur une partie solo : une sauvegarde par la commande, puis la liste (commande et API), une
 * sauvegarde par l'API, le refus d'une deuxième pendant la première, le téléchargement de l'archive (contenu
 * vérifié : le monde, la fiche, jamais la clé d'administration), les réglages, le refus de la restauration hors
 * serveur dédié, puis la suppression des archives créées par le test (le dossier backups/ du jeu reste propre).
 */
public class BackupsGameTest implements FabricClientGameTest {
	private static final String HEADER = "X-Valcombe-Admin";

	private HttpClient http;
	private String base;

	private <T> HttpResponse<T> fetch(ClientGameTestContext ctx, HttpRequest req, HttpResponse.BodyHandler<T> handler) {
		var future = http.sendAsync(req, handler);
		for (int i = 0; i < 400 && !future.isDone(); i++) {
			ctx.waitTick();
		}
		return future.join();
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create(base + path)).header(HEADER, ValcombeMod.adminKey().key());
	}

	private HttpResponse<String> get(ClientGameTestContext ctx, String path) {
		return fetch(ctx, request(path).build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpRequest postRequest(String path, String body) {
		return request(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
	}

	private HttpResponse<String> post(ClientGameTestContext ctx, String path, String body) {
		return fetch(ctx, postRequest(path, body), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> delete(ClientGameTestContext ctx, String path) {
		return fetch(ctx, request(path).DELETE().build(), HttpResponse.BodyHandlers.ofString());
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
	}

	/** Attend la fin de la sauvegarde en cours (au plus une minute de jeu). */
	private static void waitBackup(ClientGameTestContext ctx) {
		for (int i = 0; i < 1200; i++) {
			BackupService b = ValcombeMod.backups();
			if (b != null && b.running() == null) {
				return;
			}
			ctx.waitTick();
		}
		throw new AssertionError("Échec : la sauvegarde ne s'est pas terminée en une minute");
	}

	/** Noms des sauvegardes listées par GET /admin/sauvegardes. */
	private List<String> names(ClientGameTestContext ctx) {
		var r = get(ctx, "/admin/sauvegardes");
		status(r, 200, "liste des sauvegardes");
		List<String> out = new ArrayList<>();
		for (JsonElement e : json(r).getAsJsonArray("sauvegardes")) {
			out.add(e.getAsJsonObject().get("nom").getAsString());
		}
		return out;
	}

	private JsonObject listed(ClientGameTestContext ctx, String name) {
		for (JsonElement e : json(get(ctx, "/admin/sauvegardes")).getAsJsonArray("sauvegardes")) {
			if (e.getAsJsonObject().get("nom").getAsString().equals(name)) {
				return e.getAsJsonObject();
			}
		}
		throw new AssertionError("Échec : " + name + " absente de la liste");
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		List<String> created = new ArrayList<>();
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(w -> w.setAllowCommands(true)).create()) {
			ctx.waitTicks(60);
			String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
			world.getServer().runCommand("op " + me);
			ctx.waitTicks(20);
			check(ValcombeMod.backups() != null, "gestionnaire de sauvegardes démarré");
			// Port de test à part : un serveur Valcombe déjà lancé sur ce PC garde le sien.
			if (ValcombeMod.community().tabletPort() != 25591) {
				ValcombeMod.community().setTabletPort(25591);
			}
			int port;
			try {
				port = ValcombeMod.tablet().openAdmin();
			} catch (java.io.IOException e) {
				throw new AssertionError("API d'administration non ouverte", e);
			}
			base = "http://127.0.0.1:" + port;
			http = HttpClient.newHttpClient();
			try {
				// État de départ
				var first = get(ctx, "/admin/sauvegardes");
				status(first, 200, "liste");
				JsonObject l0 = json(first);
				check(!l0.get("restaurationPossible").getAsBoolean(), "pas de restauration en solo");
				check(l0.get("prochaineAuto").isJsonNull(), "pas de sauvegarde automatique en solo");
				check(l0.get("enCours").isJsonNull(), "rien en cours");
				check(l0.getAsJsonObject("reglages").get("intervalleMinutes").getAsInt() >= 15, "réglages");
				check(l0.get("espaceLibre").getAsLong() > 0, "place libre connue");
				List<String> before = names(ctx);

				// 1. Sauvegarde par la commande, puis la liste
				world.getServer().runCommand("vc sauvegarde maintenant");
				ctx.waitTicks(2);
				waitBackup(ctx);
				BackupService.Outcome o = ValcombeMod.backups().last();
				check(o != null && o.ok(), "sauvegarde par la commande réussie : " + (o == null ? "aucune" : o.message()));
				created.add(o.name());
				System.out.println("[valcombe-test] " + o.message());
				List<String> after = names(ctx);
				check(after.size() == before.size() + 1 && after.get(0).equals(o.name()), "nouvelle sauvegarde en tête de liste : " + after);
				JsonObject b1 = listed(ctx, o.name());
				check(b1.get("sorte").getAsString().equals("manuelle") && b1.get("lisible").getAsBoolean(), "sauvegarde lisible : " + b1);
				check(b1.get("fichiers").getAsInt() > 0 && b1.get("octets").getAsLong() > 0, "archive non vide : " + b1);
				check(!b1.get("rotation").getAsBoolean(), "une sauvegarde manuelle n'est pas effacée par la rotation");
				var cmd = post(ctx, "/admin/commande", "{\"commande\": \"vc sauvegarde liste\"}");
				status(cmd, 200, "commande liste");
				check(json(cmd).getAsJsonArray("sortie").toString().contains(o.name()), "commande liste : " + cmd.body());

				// 2. Sauvegarde par l'API ; une deuxième demandée en même temps est refusée
				CompletableFuture<HttpResponse<String>> a = http.sendAsync(postRequest("/admin/sauvegardes", "{}"),
					HttpResponse.BodyHandlers.ofString());
				CompletableFuture<HttpResponse<String>> c = http.sendAsync(postRequest("/admin/sauvegardes", ""),
					HttpResponse.BodyHandlers.ofString());
				for (int i = 0; i < 400 && !(a.isDone() && c.isDone()); i++) {
					ctx.waitTick();
				}
				List<Integer> codes = List.of(a.join().statusCode(), c.join().statusCode());
				check(codes.contains(202) && codes.contains(409), "une sauvegarde à la fois : " + codes + " " + a.join().body() + " " + c.join().body());
				HttpResponse<String> accepted = a.join().statusCode() == 202 ? a.join() : c.join();
				String apiName = json(accepted).get("nom").getAsString();
				created.add(apiName);
				waitBackup(ctx);
				check(ValcombeMod.backups().last().ok() && ValcombeMod.backups().last().name().equals(apiName), "sauvegarde par l'API réussie");
				check(listed(ctx, apiName).get("par").getAsString().startsWith("API d'administration"), "auteur noté");
				status(post(ctx, "/admin/sauvegardes", "{\"note\": 1}"), 400, "aucun champ attendu");

				// 3. Téléchargement : le monde et la fiche, jamais la clé d'administration
				var dl = fetch(ctx, request("/admin/sauvegardes/" + o.name()).build(), HttpResponse.BodyHandlers.ofByteArray());
				check(dl.statusCode() == 200, "téléchargement : " + dl.statusCode());
				check(dl.headers().firstValue("Content-Type").orElse("").equals("application/zip"), "type zip");
				check(dl.headers().firstValue("Content-Disposition").orElse("").contains(o.name()), "nom du fichier");
				check(dl.body().length == b1.get("octets").getAsLong(), "taille annoncée");
				boolean level = false;
				boolean manifest = false;
				boolean valcombe = false;
				try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(dl.body()), StandardCharsets.UTF_8)) {
					ZipEntry e;
					while ((e = zin.getNextEntry()) != null) {
						String n = e.getName();
						level |= n.equals("monde/level.dat");
						manifest |= n.equals("valcombe-sauvegarde.json");
						valcombe |= n.startsWith("config/valcombe/");
						check(!n.contains("admin.json") && !n.contains("valcombe-communaute") && !n.endsWith("session.lock"),
							"fichier privé dans l'archive : " + n);
						zin.readAllBytes(); // vérifie la somme de contrôle
					}
				} catch (java.io.IOException x) {
					throw new AssertionError("archive téléchargée illisible", x);
				}
				check(level && manifest && valcombe, "archive complète (monde, fiche, config/valcombe)");
				status(get(ctx, "/admin/sauvegardes/valcombe-2000-01-01_00-00-00-auto.zip"), 404, "sauvegarde inconnue");
				status(get(ctx, "/admin/sauvegardes/..%2Fadmin.json"), 404, "nom hors modèle");

				// 4. Réglages
				var reg = get(ctx, "/admin/sauvegardes/reglages");
				status(reg, 200, "réglages");
				int keep = json(reg).getAsJsonObject("reglages").get("garder").getAsInt();
				var changed = post(ctx, "/admin/sauvegardes/reglages", "{\"garder\": 7}");
				status(changed, 200, "réglage changé");
				check(json(changed).getAsJsonObject("reglages").get("garder").getAsInt() == 7, "garder = 7");
				status(post(ctx, "/admin/sauvegardes/reglages", "{\"garder\": 0}"), 400, "réglage hors bornes");
				status(post(ctx, "/admin/sauvegardes/reglages", "{\"dossier\": \"/tmp\"}"), 400, "réglage inconnu");
				status(post(ctx, "/admin/sauvegardes/reglages", "{\"garder\": " + keep + "}"), 200, "réglage remis");

				// 5. Restauration : seulement sur un serveur dédié
				status(post(ctx, "/admin/sauvegardes/restaurer", "{\"nom\": \"" + o.name() + "\", \"redemarrer\": true}"), 409,
					"restauration refusée en solo");
				status(post(ctx, "/admin/sauvegardes/restaurer", "{\"nom\": \"../admin.json\"}"), 400, "nom de sauvegarde invalide");
				status(post(ctx, "/admin/sauvegardes/restaurer", "{}"), 400, "nom manquant");
				var solo = post(ctx, "/admin/commande", "{\"commande\": \"vc sauvegarde restaurer " + o.name() + "\"}");
				// « ok » dit que la commande a été exécutée ; son refus se lit dans « resultat » (0) et dans la sortie.
				check(json(solo).get("resultat").getAsInt() == 0 && solo.body().contains("serveur dédié"), "commande restaurer refusée en solo : " + solo.body());
				status(delete(ctx, "/admin/sauvegardes/restaurer"), 200, "annuler sans restauration prévue");
				status(fetch(ctx, request("/admin/sauvegardes").PUT(HttpRequest.BodyPublishers.noBody()).build(),
					HttpResponse.BodyHandlers.ofString()), 405, "méthode refusée");
				System.out.println("[valcombe-test] admin/sauvegardes : " + get(ctx, "/admin/sauvegardes").body());
			} finally {
				// Les archives du test sont effacées, même après un échec : le dossier backups/ du jeu reste propre.
				waitBackup(ctx);
				for (String n : created) {
					delete(ctx, "/admin/sauvegardes/" + n);
				}
			}
			for (String n : created) {
				check(!names(ctx).contains(n), "effacée de la liste : " + n);
			}
			if (!created.isEmpty()) {
				status(delete(ctx, "/admin/sauvegardes/" + created.get(0)), 404, "déjà effacée");
			}
			System.out.println("[valcombe-test] Sauvegardes OK");
		}
	}
}
