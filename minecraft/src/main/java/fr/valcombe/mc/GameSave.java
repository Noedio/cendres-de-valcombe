package fr.valcombe.mc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * La fiche d'une partie : son état, enregistré aux moments clés et à l'arrêt du serveur, pour qu'elle reprenne après un
 * redémarrage, en cours, en pause ou en suspens. <b>Une fiche par partie</b>, rangée sous le nom du monde de la partie
 * ({@link #file} : {@code config/valcombe/parties/<monde>.json}), la principale comme les parties d'essai.
 * <p>
 * La session écrit l'état de la table ; l'hôte y ajoute l'identifiant du monde de la partie (champ {@value #WORLD}). À la
 * reprise, seule une fiche de ce monde-là est reprise : une fiche d'avant les mondes de Valcombe (sans monde : ses
 * joueurs étaient assis dans le monde principal) ou d'un autre monde est écartée. L'ancienne sauvegarde unique,
 * {@code config/valcombe-partie.json}, d'avant les parties multiples, est mise de côté au démarrage
 * ({@link #retireLegacy}).
 * <p>
 * Cette classe ne connaît pas Minecraft : elle ne fait que lire et écrire le texte, et nommer les fichiers.
 */
public final class GameSave {
	/** Le champ qui porte l'identifiant du monde de la partie. */
	public static final String WORLD = "monde";
	/** Le dossier des fiches de parties, dans le dossier {@code config} du serveur. */
	public static final String DIR = "valcombe/parties";
	/** L'ancienne sauvegarde unique, d'avant les parties multiples (dans le dossier {@code config}). */
	public static final String LEGACY = "valcombe-partie.json";
	/** Le suffixe d'une sauvegarde mise de côté (ignorée, jamais effacée). */
	public static final String ASIDE = ".ancienne";

	/** La fiche de la partie du monde worldId, dans le dossier {@code config} du serveur. */
	public static java.nio.file.Path file(java.nio.file.Path config, String worldId) {
		if (worldId == null || worldId.isBlank() || !worldId.matches("[a-z0-9][a-z0-9_.-]*")) {
			throw new IllegalArgumentException("Identifiant de monde invalide pour une fiche de partie : " + worldId);
		}
		return config.resolve(DIR).resolve(worldId + ".json");
	}

	/** Les identifiants des mondes qui ont une fiche de partie (ou sa seule copie de secours) dans ce dossier config. */
	public static java.util.List<String> savedWorlds(java.nio.file.Path config) {
		java.nio.file.Path dir = config.resolve(DIR);
		if (!java.nio.file.Files.isDirectory(dir)) {
			return java.util.List.of();
		}
		java.util.Set<String> out = new java.util.TreeSet<>();
		try (var files = java.nio.file.Files.list(dir)) {
			files.map(p -> p.getFileName().toString()).forEach(n -> {
				String base = n.endsWith(".json.bak") ? n.substring(0, n.length() - 4) : n;
				if (base.endsWith(".json") && base.length() > 5) {
					out.add(base.substring(0, base.length() - 5));
				}
			});
		} catch (java.io.IOException e) {
			return java.util.List.of();
		}
		return java.util.List.copyOf(out);
	}

	/**
	 * L'ancienne sauvegarde unique ({@value #LEGACY}) est ignorée : chaque partie a maintenant sa fiche. Si elle existe,
	 * elle est mise de côté ({@value #LEGACY}{@value #ASIDE}, jamais effacée) et la phrase à écrire au journal est renvoyée ;
	 * sinon null.
	 */
	public static String retireLegacy(java.nio.file.Path config) throws java.io.IOException {
		java.nio.file.Path old = config.resolve(LEGACY);
		if (!java.nio.file.Files.exists(old)) {
			return null;
		}
		java.nio.file.Path aside = config.resolve(LEGACY + ASIDE);
		java.nio.file.Files.move(old, aside, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		for (String extra : new String[] {".tmp", ".bak"}) {
			java.nio.file.Files.deleteIfExists(config.resolve(LEGACY + extra));
		}
		return "Ancienne sauvegarde de partie (config/" + LEGACY + ") ignorée : chaque partie a maintenant sa fiche, dans config/"
			+ DIR + "/. Le fichier est gardé de côté (config/" + LEGACY + ASIDE + ").";
	}

	/** Ce que dit une sauvegarde relue. */
	public enum Verdict {
		/** Une partie de ce monde : elle reprend. */
		OK,
		/** Une sauvegarde sans monde, d'avant les mondes de Valcombe : elle ne reprend pas. */
		NO_WORLD,
		/** Une partie d'un autre monde : elle ne reprend pas ici. */
		OTHER_WORLD,
		/** Un texte qui n'est pas une sauvegarde (illisible, tronqué). */
		UNREADABLE
	}

	/**
	 * Une sauvegarde relue.
	 *
	 * @param verdict ce qu'elle est
	 * @param json    l'état de la table à donner à la session (OK seulement), ou null
	 * @param world   le monde qu'elle annonce, ou null
	 */
	public record Read(Verdict verdict, String json, String world) {
	}

	private GameSave() {
	}

	/** L'état de la table (objet JSON de la session), avec le monde où se joue la partie. */
	public static String withWorld(String json, String worldId) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		o.remove(WORLD);
		if (worldId != null) {
			o.addProperty(WORLD, worldId);
		}
		return o.toString();
	}

	/** Relit une sauvegarde pour la partie du monde worldId. */
	public static Read read(String text, String worldId) {
		JsonObject o;
		try {
			JsonElement e = JsonParser.parseString(text);
			if (!e.isJsonObject()) {
				return new Read(Verdict.UNREADABLE, null, null);
			}
			o = e.getAsJsonObject();
		} catch (RuntimeException e) {
			return new Read(Verdict.UNREADABLE, null, null);
		}
		JsonElement w = o.get(WORLD);
		if (w == null || !w.isJsonPrimitive() || w.getAsString().isBlank()) {
			return new Read(Verdict.NO_WORLD, null, null);
		}
		String world = w.getAsString();
		if (!world.equals(worldId)) {
			return new Read(Verdict.OTHER_WORLD, null, world);
		}
		o.remove(WORLD);
		return new Read(Verdict.OK, o.toString(), world);
	}
}
