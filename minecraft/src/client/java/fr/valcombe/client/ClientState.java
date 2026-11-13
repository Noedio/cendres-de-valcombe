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

	private static JsonObject preview;
	private static int[] previewColors = new int[0];

	/** Aperçu vu de dessus d'une carte, demandé par l'écran de l'Atelier (« vc carte apercu ») ; null tant qu'aucun n'est arrivé. */
	public static JsonObject preview() {
		return preview;
	}

	/** Les couleurs de l'aperçu, une par case, ligne par ligne (vide si la carte n'a pas d'image). */
	public static int[] previewColors() {
		return previewColors;
	}

	public static void applyPreview(String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		// Maquettes et fantôme des structures passent par le même canal que l'aperçu des cartes.
		String kind = str(o, "kind");
		if ("maquette".equals(kind)) {
			Maquette.received(o);
			version++;
			return;
		}
		if ("fantome".equals(kind) || "fantomeA".equals(kind)) {
			Ghost.received(o);
			return;
		}
		int[] colors = new int[0];
		String pixels = str(o, "pixels");
		if (pixels != null) {
			byte[] raw = java.util.Base64.getDecoder().decode(pixels);
			colors = new int[raw.length / 3];
			for (int i = 0; i < colors.length; i++) {
				colors[i] = 0xFF000000 | (raw[3 * i] & 255) << 16 | (raw[3 * i + 1] & 255) << 8 | raw[3 * i + 2] & 255;
			}
		}
		preview = o;
		previewColors = colors;
		version++; // l'écran ouvert se redessine avec l'aperçu
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
	 * Version du mod sur le serveur, ou null si elle est identique à celle du joueur. Un serveur
	 * d'avant la 0.5.3 n'envoie pas sa version : il est forcément différent.
	 */
	public static String serverVersionMismatch() {
		if (view == null || !view.has("phase")) {
			return null;
		}
		String server = str(view, "modVersion");
		String mine = modVersion();
		if (server != null && server.equals(mine)) {
			return null;
		}
		return server == null ? "plus ancienne" : server;
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
