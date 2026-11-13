package fr.valcombe.admin;

import java.util.ArrayList;
import java.util.List;

/**
 * Tampon circulaire des dernières lignes de la console du serveur. Chaque ligne reçoit un numéro de
 * séquence qui ne fait que croître (1, 2, 3…) : le launcher redemande « ce qui suit le numéro n » et ne
 * reçoit que les nouvelles lignes. Quand le tampon est plein, les plus anciennes lignes sont écrasées ; une
 * lecture qui arrive trop tard est prévenue du nombre de lignes perdues.
 *
 * <p>Alimenté par n'importe quel fil (celui qui écrit dans le journal), lu par les fils du serveur web :
 * toutes les méthodes sont synchronisées, et chacune ne fait que quelques copies.
 */
public final class ConsoleBuffer {
	/** Longueur maximale d'une ligne (une trace d'erreur comprise) : au-delà, elle est coupée. */
	public static final int MAX_CHARS = 4000;

	/**
	 * Une ligne de console.
	 *
	 * @param n numéro de séquence
	 * @param time moment, en millisecondes depuis 1970
	 * @param level niveau (INFO, WARN, ERROR…)
	 * @param thread fil qui a écrit (« Server thread »…)
	 * @param source journal d'origine (« valcombe », « net.minecraft.server.MinecraftServer »…)
	 * @param text message, éventuellement sur plusieurs lignes (trace d'erreur)
	 */
	public record Line(long n, long time, String level, String thread, String source, String text) {
	}

	/**
	 * Une page de lecture.
	 *
	 * @param lines lignes, de la plus ancienne à la plus récente
	 * @param next numéro à redonner pour la lecture suivante
	 * @param first plus ancien numéro encore dans le tampon (0 s'il est vide)
	 * @param lost lignes écrasées avant d'avoir été lues
	 * @param more vrai s'il reste des lignes au-delà de cette page
	 */
	public record Page(List<Line> lines, long next, long first, long lost, boolean more) {
	}

	private final Line[] ring;
	/** Numéro de la dernière ligne ajoutée (0 : aucune). */
	private long last;
	/** Identifiant de ce tampon : il change quand le serveur redémarre, et les numéros repartent de 1. */
	private final String instance = java.util.UUID.randomUUID().toString();

	public ConsoleBuffer(int capacity) {
		if (capacity < 1) {
			throw new IllegalArgumentException("capacité");
		}
		ring = new Line[capacity];
	}

	public int capacity() {
		return ring.length;
	}

	/** Identifiant de ce tampon (un par lancement du serveur). */
	public String instance() {
		return instance;
	}

	/** Ajoute une ligne et renvoie son numéro. */
	public synchronized long append(long time, String level, String thread, String source, String text) {
		long n = ++last;
		ring[(int) ((n - 1) % ring.length)] = new Line(n, time, nonNull(level), nonNull(thread), nonNull(source), cut(nonNull(text)));
		return n;
	}

	/** Numéro de la dernière ligne (0 si aucune). */
	public synchronized long last() {
		return last;
	}

	/** Plus ancien numéro encore gardé (0 si le tampon est vide). */
	public synchronized long first() {
		return last == 0 ? 0 : Math.max(1, last - ring.length + 1);
	}

	/**
	 * Les lignes qui suivent le numéro {@code after}, de la plus ancienne à la plus récente, {@code max} au plus.
	 * Un numéro plus grand que le dernier (le serveur a redémarré depuis la lecture précédente) ou négatif
	 * repart du début du tampon.
	 */
	public synchronized Page since(long after, int max) {
		int limit = Math.max(1, max);
		if (after < 0 || after > last) {
			after = 0;
		}
		long first = first();
		if (last == 0) {
			return new Page(List.of(), 0, 0, 0, false);
		}
		long start = Math.max(after + 1, first);
		long lost = Math.max(0, first - (after + 1));
		if (start > last) {
			return new Page(List.of(), last, first, 0, false);
		}
		long end = Math.min(last, start + limit - 1);
		List<Line> out = new ArrayList<>((int) (end - start + 1));
		for (long n = start; n <= end; n++) {
			out.add(ring[(int) ((n - 1) % ring.length)]);
		}
		return new Page(out, end, first, lost, end < last);
	}

	/** Les {@code max} dernières lignes (première lecture : on n'a encore rien vu). */
	public synchronized Page tail(int max) {
		int limit = Math.max(1, max);
		long first = first();
		long from = Math.max(first, last - limit + 1);
		Page p = since(Math.max(0, from - 1), limit);
		return new Page(p.lines(), p.next(), p.first(), 0, p.more());
	}

	private static String nonNull(String s) {
		return s == null ? "" : s;
	}

	private static String cut(String s) {
		return s.length() <= MAX_CHARS ? s : s.substring(0, MAX_CHARS - 1) + "…";
	}
}
