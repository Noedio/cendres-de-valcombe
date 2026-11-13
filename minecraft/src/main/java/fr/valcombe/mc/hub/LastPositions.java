package fr.valcombe.mc.hub;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.valcombe.mc.SafeFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * La dernière position de chaque joueur dans chaque monde : là où il réapparaît quand il y retourne.
 * <p>
 * Enregistrée dans le dossier de la sauvegarde ({@code <monde>/valcombe/positions.json}, voir {@link #fileIn}), par
 * joueur (son UUID : un pseudo peut changer) et par
 * monde (l'identifiant du monde de Valcombe, ou {@link #MAIN} pour le monde principal où se joue le village
 * actuel). Le nom du joueur est noté à côté, pour qu'un humain s'y retrouve en lisant le fichier.
 * <p>
 * Cette classe ne connaît pas Minecraft : elle tient la table et lit ou écrit le fichier (sans jamais le laisser à
 * moitié écrit, copie de secours {@code positions.json.bak}). Les modifications restent en mémoire jusqu'au
 * prochain {@link #save()} : le voyageur l'appelle à chaque voyage, à chaque départ de joueur, régulièrement et à
 * l'arrêt du serveur. Toutes les méthodes sont sûres entre fils d'exécution.
 */
public final class LastPositions {
	private static final Logger LOG = LoggerFactory.getLogger("valcombe");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Version du format de {@code positions.json}. */
	public static final int FORMAT = 1;
	/** L'ancienne clé du monde principal de Minecraft, où se jouait la partie avant les mondes de partie (voir Travel#MAIN). */
	public static final String MAIN = "principal";
	/** Une clé de monde : {@link #MAIN} ou l'identifiant d'un monde de Valcombe. */
	private static final String WORLD = "[a-z0-9-]{2,30}";

	/** Une position et une orientation (lacet et tangage, en degrés, comme Minecraft les donne). */
	public record Spot(double x, double y, double z, float yaw, float pitch) {
		/** Des nombres utilisables (pas d'infini, pas de NaN, une hauteur raisonnable). */
		public boolean valid() {
			return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Float.isFinite(yaw) && Float.isFinite(pitch)
				&& Math.abs(x) < 3.0E7 && Math.abs(z) < 3.0E7 && y > -2048 && y < 4096;
		}
	}

	private final Path file;
	/** Les positions, par joueur puis par monde. */
	private final Map<UUID, Map<String, Spot>> spots = new LinkedHashMap<>();
	private final Map<UUID, String> names = new LinkedHashMap<>();
	private boolean dirty;

	/** Table enregistrée dans ce fichier ; rien n'est lu avant {@link #load()}. */
	public LastPositions(Path file) {
		this.file = file;
	}

	/**
	 * Le fichier d'une sauvegarde : {@code valcombe/positions.json} dans le dossier du monde (à côté de ses
	 * dimensions). Les positions vont avec les mondes qu'elles désignent : en solo, deux sauvegardes ne partagent
	 * pas leurs positions, et une sauvegarde du monde emporte les positions avec elle.
	 */
	public static Path fileIn(Path saveDir) {
		return saveDir.resolve("valcombe").resolve("positions.json");
	}

	public Path file() {
		return file;
	}

	/** Une clé de monde acceptée : {@link #MAIN} ou un identifiant de monde de Valcombe. */
	public static boolean validWorld(String world) {
		return world != null && world.matches(WORLD);
	}

	// ------------------------------------------------------------------
	// Lecture et écriture
	// ------------------------------------------------------------------

	private static boolean readable(String text) {
		return JsonParser.parseString(text).isJsonObject();
	}

	/**
	 * Relit le fichier (ou sa copie de secours). Un fichier absent ou illisible donne une table vide ; un fichier
	 * illisible est mis de côté ({@code positions.json.illisible}) au lieu d'être écrasé. Une position abîmée est
	 * ignorée.
	 */
	public synchronized void load() {
		spots.clear();
		names.clear();
		dirty = false;
		if (!SafeFile.exists(file)) {
			return;
		}
		String text = SafeFile.read(file, LastPositions::readable);
		if (text == null) {
			LOG.error("Positions des joueurs illisibles : {} mis de côté, chacun repartira du point d'arrivée des mondes.", file);
			try {
				if (Files.exists(file)) {
					Files.copy(file, file.resolveSibling(file.getFileName() + ".illisible"),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException e) {
				LOG.error("Copie des positions illisibles impossible", e);
			}
			return;
		}
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		if (!root.has("joueurs") || !root.get("joueurs").isJsonObject()) {
			return;
		}
		for (Map.Entry<String, JsonElement> pl : root.getAsJsonObject("joueurs").entrySet()) {
			try {
				UUID id = UUID.fromString(pl.getKey());
				JsonObject o = pl.getValue().getAsJsonObject();
				if (o.has("nom") && o.get("nom").isJsonPrimitive()) {
					names.put(id, o.get("nom").getAsString());
				}
				if (!o.has("mondes") || !o.get("mondes").isJsonObject()) {
					continue;
				}
				for (Map.Entry<String, JsonElement> w : o.getAsJsonObject("mondes").entrySet()) {
					Spot s = spot(w.getValue());
					if (validWorld(w.getKey()) && s != null) {
						spots.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(w.getKey(), s);
					}
				}
			} catch (RuntimeException ex) {
				LOG.warn("Positions ignorées dans {} ({}) : {}", file, ex.getMessage(), pl.getKey());
			}
		}
	}

	private static Spot spot(JsonElement el) {
		if (el == null || !el.isJsonObject()) {
			return null;
		}
		JsonObject o = el.getAsJsonObject();
		try {
			Spot s = new Spot(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(),
				o.has("lacet") ? o.get("lacet").getAsFloat() : 0f, o.has("tangage") ? o.get("tangage").getAsFloat() : 0f);
			return s.valid() ? s : null;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/** Arrondi à deux décimales dans le fichier : au centimètre près, c'est bien assez. */
	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	/** Le contenu de {@code positions.json} tel qu'il serait écrit maintenant. */
	synchronized String toJsonText() {
		JsonObject root = new JsonObject();
		root.addProperty("format", FORMAT);
		JsonObject players = new JsonObject();
		for (Map.Entry<UUID, Map<String, Spot>> pl : spots.entrySet()) {
			if (pl.getValue().isEmpty()) {
				continue;
			}
			JsonObject o = new JsonObject();
			String name = names.get(pl.getKey());
			if (name != null) {
				o.addProperty("nom", name);
			}
			JsonObject worlds = new JsonObject();
			for (Map.Entry<String, Spot> w : pl.getValue().entrySet()) {
				Spot s = w.getValue();
				JsonObject j = new JsonObject();
				j.addProperty("x", round(s.x()));
				j.addProperty("y", round(s.y()));
				j.addProperty("z", round(s.z()));
				j.addProperty("lacet", (float) round(s.yaw()));
				j.addProperty("tangage", (float) round(s.pitch()));
				worlds.add(w.getKey(), j);
			}
			o.add("mondes", worlds);
			players.add(pl.getKey().toString(), o);
		}
		root.add("joueurs", players);
		return GSON.toJson(root);
	}

	/** Écrit le fichier s'il y a du nouveau depuis la dernière écriture ; vrai si c'est fait (ou rien à faire). */
	public synchronized boolean save() {
		if (!dirty) {
			return true;
		}
		try {
			SafeFile.write(file, toJsonText());
			dirty = false;
			return true;
		} catch (IOException e) {
			LOG.error("Écriture des positions des joueurs impossible : " + file, e);
			return false;
		}
	}

	/** Y a-t-il des modifications pas encore écrites ? */
	public synchronized boolean dirty() {
		return dirty;
	}

	// ------------------------------------------------------------------
	// Table
	// ------------------------------------------------------------------

	/** La dernière position de ce joueur dans ce monde, ou null s'il n'y est jamais allé (ou si elle a été oubliée). */
	public synchronized Spot get(UUID player, String world) {
		Map<String, Spot> m = spots.get(player);
		return m == null ? null : m.get(world);
	}

	/** Note la position de ce joueur dans ce monde (une clé de monde invalide ou une position abîmée sont ignorées). */
	public synchronized void put(UUID player, String name, String world, Spot spot) {
		if (player == null || !validWorld(world) || spot == null || !spot.valid()) {
			return;
		}
		Spot before = spots.computeIfAbsent(player, k -> new LinkedHashMap<>()).put(world, spot);
		if (name != null && !name.equals(names.put(player, name))) {
			dirty = true;
		}
		dirty |= !spot.equals(before);
	}

	/** Oublie la position de ce joueur dans ce monde. */
	public synchronized void forget(UUID player, String world) {
		Map<String, Spot> m = spots.get(player);
		if (m != null && m.remove(world) != null) {
			dirty = true;
		}
	}

	/** Oublie les positions de tous les joueurs dans ce monde (remis à neuf ou supprimé) ; renvoie combien. */
	public synchronized int forgetWorld(String world) {
		int n = 0;
		for (Map<String, Spot> m : spots.values()) {
			if (m.remove(world) != null) {
				n++;
			}
		}
		dirty |= n > 0;
		return n;
	}

	/** Les mondes où ce joueur a une position gardée, dans l'ordre où il les a visités la première fois. */
	public synchronized java.util.List<String> worldsOf(UUID player) {
		Map<String, Spot> m = spots.get(player);
		return m == null ? java.util.List.of() : java.util.List.copyOf(m.keySet());
	}
}
