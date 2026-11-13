package fr.valcombe.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.valcombe.net.ValcombeNet;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Dernière vue de la partie reçue du serveur, et envoi des actions. */
public final class ClientState {
	private static JsonObject view = new JsonObject();
	private static String lastPromptKey = "";
	private static long version;

	private ClientState() {
	}

	public static JsonObject view() {
		return view;
	}

	/** Incrémenté à chaque nouvelle vue : les écrans s'en servent pour se reconstruire. */
	public static long version() {
		return version;
	}

	private static String customKey = "";

	public static void apply(String json) {
		view = JsonParser.parseString(json).getAsJsonObject();
		// Rôles créés dans l'Atelier (serveur) : même registre que le serveur, rechargé s'il a changé.
		writeCarnet(obj(view, "carnet"));
		JsonElement custom = view.get("customRoles");
		String ck = custom == null ? "" : custom.toString();
		if (!ck.equals(customKey)) {
			customKey = ck;
			fr.valcombe.rules.RoleDef[] defs = custom == null || !custom.isJsonArray() ? new fr.valcombe.rules.RoleDef[0]
				: new com.google.gson.Gson().fromJson(custom, fr.valcombe.rules.RoleDef[].class);
			fr.valcombe.rules.Role.defineCustom(java.util.List.of(defs));
		}
		version++;
		JsonObject prompt = obj(view, "prompt");
		String key = prompt == null ? "" : prompt.toString();
		Hud.onView(view, prompt != null && lastPromptKey.isEmpty());
		if (!key.equals(lastPromptKey)) {
			boolean sameType = prompt != null && lastPromptKey.contains("\"type\":\"" + str(prompt, "type") + "\"");
			lastPromptKey = key;
			ScreenRouter.onPromptChanged(prompt, sameType);
		}
	}

	private static String carnetKey = "";

	/** Le carnet du joueur (statistiques du serveur), écrit dans le dossier du jeu pour le launcher. */
	private static void writeCarnet(JsonObject carnet) {
		if (carnet == null || carnet.toString().equals(carnetKey)) {
			return;
		}
		carnetKey = carnet.toString();
		try {
			var mc = net.minecraft.client.Minecraft.getInstance();
			JsonObject out = carnet.deepCopy();
			var server = mc.getCurrentServer();
			out.addProperty("serveur", server == null ? "partie solo" : server.ip);
			out.addProperty("misAJour", java.time.LocalDate.now().toString());
			java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("valcombe-carnet.json"), out.toString());
		} catch (java.io.IOException | RuntimeException e) {
			// carnet non écrit : le launcher montrera le précédent
		}
	}

	/** Maquettes 3D des structures (« Voir en 3D ») et fantôme de la structure en main : le canal des aperçus. */
	public static void applyPreview(String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		String kind = str(o, "kind");
		if ("maquette".equals(kind)) {
			Maquette.received(o);
			version++;
			return;
		}
		if ("fantome".equals(kind) || "fantomeA".equals(kind)) {
			Ghost.received(o);
		}
	}

	public static void send(String command) {
		ClientPlayNetworking.send(new ValcombeNet.ActionPayload(command));
	}

	// Petits accès sûrs au JSON
	public static JsonObject obj(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	/** Nombre à virgule, ou la valeur par défaut si le serveur ne l'envoie pas. */
	public static double dbl(JsonObject o, String key, double def) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : def;
	}

	/** Version du mod chez ce joueur. */
	public static String modVersion() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("valcombe")
			.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	/**
	 * Version du mod sur le serveur, ou null si elle est identique à celle du joueur (voir VersionCheck : jamais en
	 * partie solo, et seulement d'après une vue reçue du serveur de la connexion en cours). Un serveur d'avant la
	 * 0.5.3 n'envoie pas sa version : il est forcément différent.
	 */
	public static String serverVersionMismatch() {
		return VersionCheck.mismatch(serverViewSeen, serverModVersion, modVersion(),
			net.minecraft.client.Minecraft.getInstance().hasSingleplayerServer());
	}

	/**
	 * Le serveur de la connexion en cours est-il un serveur de Valcombe (il a envoyé une vue de partie) ? Une partie
	 * solo avec le mod en est un aussi.
	 */
	public static boolean onValcombeServer() {
		return serverViewSeen;
	}

	/** Une vue est arrivée du serveur pendant cette connexion, et la version du mod qu'elle annonçait. */
	private static boolean serverViewSeen;
	private static String serverModVersion;

	/** Une vue envoyée par le serveur (et non posée par le client lui-même) : on retient aussi sa version du mod. */
	public static void receive(String json) {
		apply(json);
		if (view.has("phase")) {
			serverViewSeen = true;
			serverModVersion = str(view, "modVersion");
		}
	}

	/**
	 * Connexion à un serveur, ou déconnexion : la vue et la version de l'ancien serveur sont oubliées (sans quoi
	 * l'affichage en jeu montrerait sa partie, et son bandeau de version, jusqu'à la première vue du nouveau).
	 */
	public static void forgetServer() {
		serverViewSeen = false;
		serverModVersion = null;
		view = new JsonObject();
		lastPromptKey = "";
		version++;
	}

	public static String str(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	public static boolean bool(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsBoolean();
	}

	public static int num(JsonObject o, String key, int def) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsInt() : def;
	}

	/** Ce joueur est-il un fantôme (mort) dans la partie en cours ? */
	public static boolean isGhost(String name) {
		JsonObject v = view();
		if (v == null || !v.has("seats")) {
			return false;
		}
		for (var e : v.getAsJsonArray("seats")) {
			JsonObject s = e.getAsJsonObject();
			if (name.equals(str(s, "name"))) {
				return !bool(s, "alive");
			}
		}
		return false;
	}

	public static String phase() {
		String p = str(view, "phase");
		return p == null ? "LOBBY" : p;
	}
}
