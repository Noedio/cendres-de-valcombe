package fr.valcombe.mc.games;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Les équipes du tableau des scores d'une partie : elles portent les noms au-dessus des têtes (cachés la nuit ou dans un
 * recoin, grisés d'une croix pour les morts, une main pour ceux qui ont encore leur vote).
 * <p>
 * Le tableau des scores de Minecraft est <b>commun à tout le serveur</b> : une équipe « valcombe_vivants » partagée
 * cacherait les noms de toutes les parties dès qu'une seule tombe dans la nuit. Chaque partie a donc ses équipes, nommées
 * d'après un <b>préfixe court</b> qui lui est propre ({@link #key}) : {@code vcpp_viv}, {@code vcpp_cac},
 * {@code vcpp_fan}, {@code vcpp_fav} pour la « Partie principale », {@code vcedm_viv}... pour « essai-du-mardi ». Un nom
 * tient toujours dans les {@value #MAX_NAME} caractères d'un nom d'équipe de Minecraft (la limite des anciennes versions,
 * gardée pour que {@code /team} et les autres outils les acceptent tous).
 * <p>
 * Les équipes d'une partie sont retirées à la fin de la partie et à sa mise en suspens ; un joueur qui quitte le monde de
 * la partie (au hub, ailleurs) quitte ses équipes. Au démarrage du serveur, toutes les équipes de Valcombe laissées par un
 * arrêt (celles d'avant les parties multiples comprises, {@link #LEGACY}) sont retirées : les parties reprises les
 * reposent. Cette classe ne connaît pas Minecraft : elle ne fait que nommer.
 */
public final class GameTeams {
	/** La longueur maximale d'un nom d'équipe (voir la classe). */
	public static final int MAX_NAME = 16;
	/** La longueur maximale du préfixe d'une partie (avec son chiffre de départage). */
	static final int MAX_KEY = 5;

	/** Les équipes d'avant les parties multiples, communes à tout le serveur : retirées au démarrage. */
	public static final List<String> LEGACY = List.of("valcombe_vivants", "valcombe_cache", "valcombe_fantomes",
		"valcombe_fantomes_vote");

	/** Ce que porte une équipe de la partie. */
	public enum Kind {
		/** Les vivants, quand les noms sont cachés (la nuit, ou par réglage du Conteur). */
		VIVANTS("viv"),
		/** Les vivants dans un recoin du village : leur nom ne se voit pas depuis la place. */
		CACHE("cac"),
		/** Les morts sans vote : nom grisé, précédé d'une croix. */
		FANTOMES("fan"),
		/** Les morts qui ont encore leur vote : la croix, et une main. */
		FANTOMES_VOTE("fav");

		final String code;

		Kind(String code) {
			this.code = code;
		}
	}

	private static final Pattern OURS = Pattern.compile("vc[a-z0-9]{1," + MAX_KEY + "}_(viv|cac|fan|fav)");

	private GameTeams() {
	}

	/**
	 * Le préfixe court d'une partie, d'après l'identifiant de son monde : les initiales de ses mots (« partie-principale »
	 * donne {@code pp}, « essai-du-mardi » {@code edm}), quatre au plus ; un chiffre le départage d'un préfixe déjà pris
	 * par une autre partie ouverte ({@code edm2}). Toujours fait de lettres minuscules et de chiffres.
	 *
	 * @param worldId l'identifiant du monde de la partie
	 * @param taken   les préfixes des parties déjà ouvertes
	 */
	public static String key(String worldId, Collection<String> taken) {
		String id = worldId == null ? "" : worldId.toLowerCase(Locale.ROOT);
		StringBuilder initials = new StringBuilder();
		for (String word : id.split("[^a-z0-9]+")) {
			if (!word.isEmpty() && initials.length() < 4) {
				initials.append(word.charAt(0));
			}
		}
		String base = initials.length() == 0 ? "p" : initials.toString();
		if (!taken.contains(base)) {
			return base;
		}
		String stem = base.length() > MAX_KEY - 1 ? base.substring(0, MAX_KEY - 1) : base;
		for (int i = 2; i <= 9; i++) {
			if (!taken.contains(stem + i)) {
				return stem + i;
			}
		}
		// Plus de neuf parties aux mêmes initiales : deux lettres de l'empreinte de l'identifiant, puis un chiffre.
		String hash = Integer.toString(Math.abs(id.hashCode() % (36 * 36)), 36);
		for (int i = 0; i <= 9 * 36; i++) {
			String k = (stem.substring(0, Math.min(2, stem.length())) + hash + Integer.toString(i, 36));
			k = k.length() > MAX_KEY ? k.substring(k.length() - MAX_KEY) : k;
			if (!taken.contains(k)) {
				return k;
			}
		}
		throw new IllegalStateException("Plus de préfixe d'équipe libre pour la partie « " + worldId + " »");
	}

	/** Le nom de l'équipe {@code kind} de la partie au préfixe {@code key}. */
	public static String name(String key, Kind kind) {
		return "vc" + key + "_" + kind.code;
	}

	/** Les noms des équipes de la partie au préfixe {@code key}. */
	public static List<String> names(String key) {
		return List.of(name(key, Kind.VIVANTS), name(key, Kind.CACHE), name(key, Kind.FANTOMES), name(key, Kind.FANTOMES_VOTE));
	}

	/** Est-ce une équipe de Valcombe, d'une partie ou d'avant les parties multiples ? */
	public static boolean ours(String team) {
		return team != null && (LEGACY.contains(team) || OURS.matcher(team).matches());
	}

	/** Est-ce une équipe de la partie au préfixe {@code key} ? */
	public static boolean of(String team, String key) {
		return ours(team) && team.startsWith("vc" + key + "_");
	}
}
