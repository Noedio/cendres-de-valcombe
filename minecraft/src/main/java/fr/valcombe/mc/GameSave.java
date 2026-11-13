package fr.valcombe.mc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * La sauvegarde de la partie en cours ({@code config/valcombe-partie.json}, reprise après un redémarrage) et le
 * monde où elle se joue. La session écrit l'état de la table ; l'hôte y ajoute l'identifiant du monde de la partie
 * (champ {@value #WORLD}). À la reprise, seule une sauvegarde de ce monde-là est reprise : une sauvegarde d'avant les
 * mondes de Valcombe (sans monde : ses joueurs étaient assis dans le monde principal) ou d'un autre monde est écartée.
 * <p>
 * Cette classe ne connaît pas Minecraft : elle ne fait que lire et écrire le texte.
 */
public final class GameSave {
	/** Le champ qui porte l'identifiant du monde de la partie. */
	public static final String WORLD = "monde";

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
