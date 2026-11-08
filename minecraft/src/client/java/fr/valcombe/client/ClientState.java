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

	public static void apply(String json) {
		view = JsonParser.parseString(json).getAsJsonObject();
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

	public static String phase() {
		String p = str(view, "phase");
		return p == null ? "LOBBY" : p;
	}
}
