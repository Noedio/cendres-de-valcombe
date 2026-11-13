package fr.valcombe.admin;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Garde de l'API d'administration : d'où vient la requête, la clé est-elle la bonne, et combien d'essais
 * ratés cette adresse a déjà faits.
 *
 * <ul>
 *   <li>La clé est comparée en temps constant : on compare les empreintes SHA-256 des deux clés avec
 *   {@link MessageDigest#isEqual}, si bien que la durée ne dépend ni du contenu ni de la longueur de la clé essayée.</li>
 *   <li>Cinq clés fausses en dix minutes depuis une même adresse la bloquent 30 secondes ; chaque blocage
 *   suivant double, jusqu'à 15 minutes. Une bonne clé efface l'ardoise. Pendant un blocage, la clé n'est même
 *   pas regardée.</li>
 *   <li>Une requête sans clé n'est pas comptée : elle ne devine rien.</li>
 * </ul>
 * L'horloge est passée en paramètre pour que les tests règlent le temps.
 */
public final class AdminGuard {
	/** Clés fausses tolérées avant un blocage. */
	public static final int MAX_FAILURES = 5;
	/** Durée pendant laquelle les échecs s'additionnent. */
	static final long WINDOW_MS = 10 * 60_000L;
	static final long FIRST_LOCK_MS = 30_000L;
	static final long MAX_LOCK_MS = 15 * 60_000L;
	/** Au-delà, les adresses sans blocage en cours sont oubliées (la table ne grossit pas sans fin). */
	static final int MAX_TRACKED = 4096;

	public enum Verdict {
		/** Bonne clé. */
		OK,
		/** Adresse refusée (adminLocalOnly) : 403. */
		NETWORK_REFUSED,
		/** Pas d'en-tête de clé : 401. */
		MISSING_KEY,
		/** Mauvaise clé : 401. */
		WRONG_KEY,
		/** Trop d'essais ratés : 429, réessayer après retryAfterSeconds. */
		LOCKED
	}

	public record Result(Verdict verdict, long retryAfterSeconds) {
		public boolean ok() {
			return verdict == Verdict.OK;
		}
	}

	/** Ardoise d'une adresse. */
	private static final class Slate {
		int failures;
		long firstFailure;
		long lockedUntil;
		int locks;
	}

	private final Map<String, Slate> slates = new HashMap<>();
	private final LongSupplier clock;

	public AdminGuard() {
		this(System::currentTimeMillis);
	}

	public AdminGuard(LongSupplier clockMillis) {
		this.clock = clockMillis;
	}

	/**
	 * Verdict pour une requête.
	 *
	 * @param address adresse de l'appelant (sert de clé à la limitation)
	 * @param networkAllowed faux si l'adresse est refusée d'office (adminLocalOnly et requête venue d'ailleurs)
	 * @param given clé reçue dans l'en-tête, ou null
	 * @param expected clé en service
	 */
	public synchronized Result check(String address, boolean networkAllowed, String given, String expected) {
		if (!networkAllowed) {
			return new Result(Verdict.NETWORK_REFUSED, 0);
		}
		long now = clock.getAsLong();
		Slate slate = slates.get(address);
		if (slate != null && slate.lockedUntil > now) {
			return new Result(Verdict.LOCKED, seconds(slate.lockedUntil - now));
		}
		if (given == null || given.isEmpty()) {
			return new Result(Verdict.MISSING_KEY, 0);
		}
		if (expected != null && !expected.isEmpty() && sameKey(given, expected)) {
			slates.remove(address);
			return new Result(Verdict.OK, 0);
		}
		if (slate == null) {
			prune(now);
			slate = new Slate();
			slates.put(address, slate);
		}
		if (slate.failures == 0 || now - slate.firstFailure > WINDOW_MS) {
			slate.failures = 0;
			slate.firstFailure = now;
		}
		slate.failures++;
		if (slate.failures >= MAX_FAILURES) {
			long lock = Math.min(MAX_LOCK_MS, FIRST_LOCK_MS << Math.min(slate.locks, 10));
			slate.locks++;
			slate.failures = 0;
			slate.lockedUntil = now + lock;
			return new Result(Verdict.LOCKED, seconds(lock));
		}
		return new Result(Verdict.WRONG_KEY, 0);
	}

	/** Oublie toutes les ardoises (nouvelle clé : les blocages dus à l'ancienne n'ont plus de sens). */
	public synchronized void reset() {
		slates.clear();
	}

	/** Nombre d'adresses suivies (pour les tests). */
	synchronized int tracked() {
		return slates.size();
	}

	private void prune(long now) {
		if (slates.size() < MAX_TRACKED) {
			return;
		}
		slates.values().removeIf(s -> s.lockedUntil <= now && now - s.firstFailure > WINDOW_MS);
		if (slates.size() >= MAX_TRACKED) {
			slates.values().removeIf(s -> s.lockedUntil <= now);
		}
	}

	private static long seconds(long ms) {
		return Math.max(1, (ms + 999) / 1000);
	}

	/**
	 * Les deux clés sont-elles identiques ? Comparaison en temps constant : les empreintes SHA-256 ont toujours
	 * 32 octets, et {@link MessageDigest#isEqual} les parcourt en entier quel que soit le premier octet différent.
	 */
	public static boolean sameKey(String given, String expected) {
		if (given == null || expected == null) {
			return false;
		}
		return MessageDigest.isEqual(sha256(given), sha256(expected));
	}

	private static byte[] sha256(String s) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 indisponible", e); // garanti par toute machine Java
		}
	}

	/** La requête vient-elle de la machine elle-même (127.0.0.0/8, ::1) ? Un tunnel SSH arrive par là. */
	public static boolean loopback(InetAddress a) {
		return a != null && a.isLoopbackAddress();
	}
}
