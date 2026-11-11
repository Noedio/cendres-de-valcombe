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

	public static void send(String command) {
		ClientPlayNetworking.send(new ValcombeNet.ActionPayload(command));
	}

	// Petits accès sûrs au JSON
	public static JsonObject obj(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
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
