package fr.valcombe.admin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation d'une demande de changement de réglages (POST /admin/reglages), avant d'y toucher : tout est
 * vérifié d'abord, et une demande fautive est refusée en entier avec la liste de ses fautes. Rien ici ne
 * dépend de Minecraft ; l'application des changements se fait sur le fil du serveur (voir AdminApi).
 *
 * <pre>
 * {
 *   "listeBlanche": { "active": true, "ajouter": ["Alice"], "retirer": ["Bob"] },
 *   "operateurs":   { "ajouter": ["Alice"], "retirer": ["Bob"] },
 *   "messageAccueil": "Bienvenue au bourg",
 *   "prochaineSoiree": "vendredi 21 h",
 *   "joueursMax": 16,
 *   "port": 25565
 * }
 * </pre>
 * Chaque champ est facultatif, mais il en faut au moins un.
 */
public final class AdminSettings {
	/** Noms de joueur Minecraft : lettres, chiffres et soulignés, 16 caractères au plus. */
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	public static final int MAX_NAMES = 50;
	/** Le message d'accueil et la soirée tiennent sur une ligne de la liste des serveurs. */
	public static final int MAX_TEXT = 60;
	public static final int MIN_PLAYERS = 1;
	public static final int MAX_PLAYERS = 1000;
	public static final int MIN_PORT = 1024;
	public static final int MAX_PORT = 65535;

	private static final Set<String> TOP = Set.of("listeBlanche", "operateurs", "messageAccueil", "prochaineSoiree", "joueursMax", "port");
	private static final Set<String> WHITELIST = Set.of("active", "ajouter", "retirer");
	private static final Set<String> OPS = Set.of("ajouter", "retirer");

	/**
	 * Changements demandés ; null (ou liste vide) : on n'y touche pas.
	 *
	 * @param welcome message d'accueil ; chaîne vide : revenir au message par défaut
	 * @param evening prochaine soirée ; chaîne vide : l'effacer
	 */
	public record Change(Boolean whitelistOn, List<String> whitelistAdd, List<String> whitelistRemove,
			List<String> opsAdd, List<String> opsRemove, String welcome, String evening, Integer maxPlayers, Integer port) {
	}

	private AdminSettings() {
	}

	/** Lit et vérifie la demande ; une demande fautive lève une AdminException 400 qui liste toutes ses fautes. */
	public static Change parse(JsonObject o) {
		List<String> errors = new ArrayList<>();
		for (String key : o.keySet()) {
			if (!TOP.contains(key)) {
				errors.add("réglage inconnu « " + key + " »");
			}
		}
		Boolean whitelistOn = null;
		List<String> wlAdd = List.of();
		List<String> wlRemove = List.of();
		JsonObject wl = object(o, "listeBlanche", WHITELIST, errors);
		if (wl != null) {
			if (wl.has("active")) {
				JsonElement a = wl.get("active");
				if (a.isJsonPrimitive() && a.getAsJsonPrimitive().isBoolean()) {
					whitelistOn = a.getAsBoolean();
				} else {
					errors.add("listeBlanche.active : vrai ou faux attendu (true / false)");
				}
			}
			wlAdd = names(wl, "ajouter", "listeBlanche.ajouter", errors);
			wlRemove = names(wl, "retirer", "listeBlanche.retirer", errors);
			overlap(wlAdd, wlRemove, "listeBlanche", errors);
		}
		List<String> opAdd = List.of();
		List<String> opRemove = List.of();
		JsonObject ops = object(o, "operateurs", OPS, errors);
		if (ops != null) {
			opAdd = names(ops, "ajouter", "operateurs.ajouter", errors);
			opRemove = names(ops, "retirer", "operateurs.retirer", errors);
			overlap(opAdd, opRemove, "operateurs", errors);
		}
		String welcome = text(o, "messageAccueil", errors);
		String evening = text(o, "prochaineSoiree", errors);
		Integer max = integer(o, "joueursMax", MIN_PLAYERS, MAX_PLAYERS, errors);
		Integer port = integer(o, "port", MIN_PORT, MAX_PORT, errors);
		Change c = new Change(whitelistOn, wlAdd, wlRemove, opAdd, opRemove, welcome, evening, max, port);
		if (errors.isEmpty() && empty(c)) {
			errors.add("aucun réglage à changer");
		}
		if (!errors.isEmpty()) {
			throw new AdminException(400, "Réglages refusés : " + String.join(" ; ", errors) + ".");
		}
		return c;
	}

	/** La demande ne change-t-elle rien ? */
	public static boolean empty(Change c) {
		return c.whitelistOn() == null && c.whitelistAdd().isEmpty() && c.whitelistRemove().isEmpty() && c.opsAdd().isEmpty()
			&& c.opsRemove().isEmpty() && c.welcome() == null && c.evening() == null && c.maxPlayers() == null && c.port() == null;
	}

	/** Nom de joueur Minecraft valable ? */
	public static boolean validName(String s) {
		return s != null && NAME.matcher(s).matches();
	}

	/**
	 * Faute d'un texte d'une ligne (message d'accueil, soirée), ou null s'il convient : longueur bornée, aucun
	 * caractère de contrôle (retour à la ligne compris), pas de « § » (codes de couleur de Minecraft).
	 */
	public static String textProblem(String label, String s, int max) {
		if (s.codePointCount(0, s.length()) > max) {
			return label + " : " + max + " caractères au plus";
		}
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') {
				return label + " : une seule ligne, sans caractère de contrôle";
			}
			if (c == '§') {
				return label + " : le caractère « § » n'est pas permis";
			}
		}
		return null;
	}

	private static JsonObject object(JsonObject o, String key, Set<String> allowed, List<String> errors) {
		if (!o.has(key)) {
			return null;
		}
		JsonElement e = o.get(key);
		if (!e.isJsonObject()) {
			errors.add(key + " : objet attendu ({ ... })");
			return null;
		}
		JsonObject sub = e.getAsJsonObject();
		for (String k : sub.keySet()) {
			if (!allowed.contains(k)) {
				errors.add(key + " : champ inconnu « " + k + " »");
			}
		}
		return sub;
	}

	private static List<String> names(JsonObject o, String key, String label, List<String> errors) {
		if (!o.has(key)) {
			return List.of();
		}
		JsonElement e = o.get(key);
		if (!e.isJsonArray()) {
			errors.add(label + " : liste de noms attendue ([\"Alice\", ...])");
			return List.of();
		}
		if (e.getAsJsonArray().size() > MAX_NAMES) {
			errors.add(label + " : " + MAX_NAMES + " noms au plus par demande");
			return List.of();
		}
		// Sans doublon, la casse ne compte pas (Minecraft ne distingue pas Alice et alice).
		Map<String, String> seen = new java.util.LinkedHashMap<>();
		for (JsonElement n : e.getAsJsonArray()) {
			String name = n.isJsonPrimitive() && n.getAsJsonPrimitive().isString() ? n.getAsString() : null;
			if (!validName(name)) {
				errors.add(label + " : nom de joueur invalide " + (name == null ? n.toString() : "« " + name + " »")
					+ " (lettres, chiffres et _, 16 caractères au plus)");
				continue;
			}
			seen.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
		}
		return List.copyOf(seen.values());
	}

	private static void overlap(List<String> add, List<String> remove, String label, List<String> errors) {
		Set<String> a = new LinkedHashSet<>();
		add.forEach(n -> a.add(n.toLowerCase(Locale.ROOT)));
		for (String n : remove) {
			if (a.contains(n.toLowerCase(Locale.ROOT))) {
				errors.add(label + " : « " + n + " » à la fois ajouté et retiré");
			}
		}
	}

	private static String text(JsonObject o, String key, List<String> errors) {
		if (!o.has(key)) {
			return null;
		}
		JsonElement e = o.get(key);
		if (!(e.isJsonPrimitive() && e.getAsJsonPrimitive().isString())) {
			errors.add(key + " : texte attendu");
			return null;
		}
		String s = e.getAsString().strip();
		String problem = textProblem(key, s, MAX_TEXT);
		if (problem != null) {
			errors.add(problem);
			return null;
		}
		return s;
	}

	private static Integer integer(JsonObject o, String key, int min, int max, List<String> errors) {
		if (!o.has(key)) {
			return null;
		}
		JsonElement e = o.get(key);
		String expected = key + " : nombre entier de " + min + " à " + max + " attendu";
		if (!(e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber())) {
			errors.add(expected);
			return null;
		}
		BigDecimal d;
		try {
			d = ((JsonPrimitive) e).getAsBigDecimal();
		} catch (NumberFormatException x) {
			errors.add(expected);
			return null;
		}
		if (d.stripTrailingZeros().scale() > 0 || d.compareTo(BigDecimal.valueOf(min)) < 0 || d.compareTo(BigDecimal.valueOf(max)) > 0) {
			errors.add(expected);
			return null;
		}
		return d.intValueExact();
	}
}
