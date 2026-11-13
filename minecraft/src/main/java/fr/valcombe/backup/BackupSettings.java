package fr.valcombe.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import fr.valcombe.admin.AdminException;
import fr.valcombe.mc.SafeFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Réglages des sauvegardes, gardés dans {@code config/valcombe/sauvegardes.json} (jamais dans les archives : une
 * restauration ne les change pas).
 *
 * <pre>
 * {
 *   "actives": true,            sauvegardes automatiques (à intervalle et à l'arrêt) en service
 *   "intervalleMinutes": 360,   une sauvegarde automatique toutes les 6 heures
 *   "aLArret": true,            une sauvegarde à chaque arrêt du serveur
 *   "garder": 10,               sauvegardes automatiques gardées (les plus anciennes sont effacées)
 *   "pendantPartie": false      faux : une sauvegarde automatique attend la fin de la partie en cours
 * }
 * </pre>
 * Un champ absent ou hors bornes prend sa valeur par défaut (ou la borne la plus proche) ; le fichier est réécrit
 * proprement au démarrage. Les mêmes noms de champs servent dans l'API d'administration.
 */
public final class BackupSettings {
	public static final String FILE_NAME = "sauvegardes.json";
	public static final int MIN_INTERVAL = 15;
	public static final int MAX_INTERVAL = 7 * 24 * 60;
	public static final int MIN_KEEP = 1;
	public static final int MAX_KEEP = 500;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Set<String> FIELDS = Set.of("actives", "intervalleMinutes", "aLArret", "garder", "pendantPartie");

	/** Les réglages. */
	public record Values(boolean actives, int intervalleMinutes, boolean aLArret, int garder, boolean pendantPartie) {
		public Values {
			intervalleMinutes = Math.clamp(intervalleMinutes, MIN_INTERVAL, MAX_INTERVAL);
			garder = Math.clamp(garder, MIN_KEEP, MAX_KEEP);
		}

		/** Pour le fichier et l'API, dans l'ordre du contrat. */
		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("actives", actives);
			m.put("intervalleMinutes", intervalleMinutes);
			m.put("aLArret", aLArret);
			m.put("garder", garder);
			m.put("pendantPartie", pendantPartie);
			return m;
		}
	}

	/** Par défaut : toutes les 6 heures et à l'arrêt, dix gardées, jamais pendant une partie en cours. */
	public static final Values DEFAULTS = new Values(true, 360, true, 10, false);

	/** Changement demandé ; null : on n'y touche pas. */
	public record Change(Boolean actives, Integer intervalleMinutes, Boolean aLArret, Integer garder, Boolean pendantPartie) {
		public boolean empty() {
			return actives == null && intervalleMinutes == null && aLArret == null && garder == null && pendantPartie == null;
		}

		/** Ces réglages, avec le changement. */
		public Values applyTo(Values v) {
			return new Values(actives != null ? actives : v.actives(),
				intervalleMinutes != null ? intervalleMinutes : v.intervalleMinutes(),
				aLArret != null ? aLArret : v.aLArret(),
				garder != null ? garder : v.garder(),
				pendantPartie != null ? pendantPartie : v.pendantPartie());
		}
	}

	private final Path file;
	private Values values = DEFAULTS;

	public BackupSettings(Path valcombeDir) {
		this.file = valcombeDir.resolve(FILE_NAME);
	}

	public Path file() {
		return file;
	}

	/** Lit le fichier (ou le crée avec les réglages par défaut) et le réécrit proprement. */
	public synchronized Values load() throws IOException {
		String text = SafeFile.read(file, s -> {
			try {
				return JsonParser.parseString(s).isJsonObject();
			} catch (RuntimeException e) {
				return false;
			}
		});
		values = text == null ? DEFAULTS : fromJson(JsonParser.parseString(text).getAsJsonObject());
		save();
		return values;
	}

	public synchronized Values get() {
		return values;
	}

	/** Applique un changement et l'enregistre ; renvoie les nouveaux réglages. */
	public synchronized Values apply(Change c) throws IOException {
		values = c.applyTo(values);
		save();
		return values;
	}

	private void save() throws IOException {
		SafeFile.write(file, GSON.toJson(values.toMap()) + "\n");
	}

	/** Réglages lus du fichier, avec indulgence : ce qui manque ou ne convient pas prend sa valeur par défaut. */
	static Values fromJson(JsonObject o) {
		return new Values(bool(o, "actives", DEFAULTS.actives()), integer(o, "intervalleMinutes", DEFAULTS.intervalleMinutes()),
			bool(o, "aLArret", DEFAULTS.aLArret()), integer(o, "garder", DEFAULTS.garder()),
			bool(o, "pendantPartie", DEFAULTS.pendantPartie()));
	}

	private static boolean bool(JsonObject o, String key, boolean fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
	}

	private static int integer(JsonObject o, String key, int fallback) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}
		try {
			return e.getAsBigDecimal().intValueExact();
		} catch (ArithmeticException x) {
			return fallback;
		}
	}

	/**
	 * Lit et vérifie une demande de changement (POST /admin/sauvegardes/reglages), strictement : un champ inconnu, une
	 * valeur du mauvais type ou hors bornes refuse toute la demande (AdminException 400, avec toutes ses fautes).
	 */
	public static Change parse(JsonObject o) {
		List<String> errors = new ArrayList<>();
		for (String k : o.keySet()) {
			if (!FIELDS.contains(k)) {
				errors.add("réglage inconnu « " + k + " »");
			}
		}
		Change c = new Change(strictBool(o, "actives", errors),
			strictInt(o, "intervalleMinutes", MIN_INTERVAL, MAX_INTERVAL, errors),
			strictBool(o, "aLArret", errors),
			strictInt(o, "garder", MIN_KEEP, MAX_KEEP, errors),
			strictBool(o, "pendantPartie", errors));
		if (errors.isEmpty() && c.empty()) {
			errors.add("aucun réglage à changer");
		}
		if (!errors.isEmpty()) {
			throw new AdminException(400, "Réglages des sauvegardes refusés : " + String.join(" ; ", errors) + ".");
		}
		return c;
	}

	private static Boolean strictBool(JsonObject o, String key, List<String> errors) {
		if (!o.has(key)) {
			return null;
		}
		JsonElement e = o.get(key);
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
			return e.getAsBoolean();
		}
		errors.add(key + " : vrai ou faux attendu (true / false)");
		return null;
	}

	private static Integer strictInt(JsonObject o, String key, int min, int max, List<String> errors) {
		if (!o.has(key)) {
			return null;
		}
		JsonElement e = o.get(key);
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
			JsonPrimitive p = e.getAsJsonPrimitive();
			try {
				BigDecimal d = p.getAsBigDecimal();
				int v = d.intValueExact();
				if (v >= min && v <= max) {
					return v;
				}
			} catch (ArithmeticException | NumberFormatException x) {
				// pas un entier : signalé ci-dessous
			}
		}
		errors.add(key + " : nombre entier de " + min + " à " + max + " attendu");
		return null;
	}
}
