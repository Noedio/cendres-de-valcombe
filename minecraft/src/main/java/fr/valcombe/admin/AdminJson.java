package fr.valcombe.admin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Lecture prudente des requêtes de l'API d'administration : corps de taille bornée, UTF-8 valide,
 * JSON strict (un seul objet, rien après, pas de commentaire ni de guillemets simples).
 */
public final class AdminJson {
	private AdminJson() {
	}

	/**
	 * Lit le corps de la requête, {@code limit} octets au plus. Une longueur annoncée trop grande est refusée
	 * avant toute lecture ; un corps plus long que prévu est coupé à {@code limit + 1} octets puis refusé.
	 *
	 * @param declaredLength longueur annoncée par l'en-tête Content-Length, ou -1 si inconnue
	 */
	public static String readBody(InputStream in, long declaredLength, int limit) throws IOException {
		if (declaredLength > limit) {
			throw tooLarge(limit);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192));
		byte[] chunk = new byte[8192];
		int total = 0;
		int n;
		while ((n = in.read(chunk, 0, Math.min(chunk.length, limit + 1 - total))) > 0) {
			out.write(chunk, 0, n);
			total += n;
			if (total > limit) {
				throw tooLarge(limit);
			}
		}
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(out.toByteArray())).toString();
		} catch (CharacterCodingException e) {
			throw new AdminException(400, "Corps de requête illisible : UTF-8 attendu.");
		}
	}

	private static AdminException tooLarge(int limit) {
		return new AdminException(413, "Requête trop grosse (" + (limit / 1024) + " Ko au plus).");
	}

	/** Le corps doit être un objet JSON, strict et complet. */
	public static JsonObject parseObject(String body) {
		if (body == null || body.isBlank()) {
			throw new AdminException(400, "Corps JSON attendu (un objet : { ... }).");
		}
		try (JsonReader reader = new JsonReader(new StringReader(body))) {
			reader.setStrictness(Strictness.STRICT);
			JsonElement e = JsonParser.parseReader(reader);
			if (reader.peek() != JsonToken.END_DOCUMENT) {
				throw new AdminException(400, "JSON invalide : du texte suit l'objet.");
			}
			if (!e.isJsonObject()) {
				throw new AdminException(400, "Corps JSON attendu (un objet : { ... }).");
			}
			return e.getAsJsonObject();
		} catch (JsonParseException | IOException | IllegalStateException e) {
			throw new AdminException(400, "JSON invalide : " + firstLine(e.getMessage()));
		} catch (StackOverflowError e) {
			throw new AdminException(400, "JSON invalide : imbrication trop profonde.");
		}
	}

	private static String firstLine(String s) {
		if (s == null) {
			return "syntaxe incorrecte.";
		}
		int nl = s.indexOf('\n');
		String line = nl < 0 ? s : s.substring(0, nl);
		return line.length() > 200 ? line.substring(0, 200) + "…" : line;
	}
}
