package fr.valcombe.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class AdminGuardTest {
	private static final String KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
	private final AtomicLong now = new AtomicLong(1_000_000L);
	private final AdminGuard guard = new AdminGuard(now::get);

	private AdminGuard.Verdict check(String address, String given) {
		return guard.check(address, true, given, KEY).verdict();
	}

	@Test
	void goodKeyPassesWrongOrMissingKeyDoesNot() {
		assertEquals(AdminGuard.Verdict.OK, check("127.0.0.1", KEY));
		assertEquals(AdminGuard.Verdict.WRONG_KEY, check("127.0.0.1", KEY.substring(1)));
		assertEquals(AdminGuard.Verdict.WRONG_KEY, check("127.0.0.1", KEY.toUpperCase()));
		assertEquals(AdminGuard.Verdict.MISSING_KEY, check("127.0.0.1", null));
		assertEquals(AdminGuard.Verdict.MISSING_KEY, check("127.0.0.1", ""));
	}

	@Test
	void refusedNetworkIsRefusedEvenWithTheKey() {
		assertEquals(AdminGuard.Verdict.NETWORK_REFUSED, guard.check("203.0.113.5", false, KEY, KEY).verdict());
	}

	@Test
	void noConfiguredKeyRefusesEverything() {
		assertEquals(AdminGuard.Verdict.WRONG_KEY, guard.check("127.0.0.1", true, "x", null).verdict());
		assertEquals(AdminGuard.Verdict.WRONG_KEY, guard.check("127.0.0.2", true, "quelquechose", "").verdict());
		// Une clé vide en service n'ouvre jamais la porte, même à une clé vide.
		assertEquals(AdminGuard.Verdict.MISSING_KEY, guard.check("127.0.0.3", true, "", "").verdict());
	}

	@Test
	void sameKeyComparesWholeValuesWhateverTheirLength() {
		assertTrue(AdminGuard.sameKey(KEY, KEY));
		assertTrue(AdminGuard.sameKey(new String(KEY.toCharArray()), KEY));
		assertFalse(AdminGuard.sameKey(KEY + "0", KEY));
		assertFalse(AdminGuard.sameKey(KEY.substring(0, 63), KEY));
		assertFalse(AdminGuard.sameKey("", KEY));
		assertFalse(AdminGuard.sameKey(null, KEY));
		assertFalse(AdminGuard.sameKey(KEY, null));
		// Première ou dernière lettre différente : même réponse (la comparaison ne s'arrête pas au premier écart).
		assertFalse(AdminGuard.sameKey("1" + KEY.substring(1), KEY));
		assertFalse(AdminGuard.sameKey(KEY.substring(0, 63) + "0", KEY));
		assertTrue(AdminGuard.sameKey("clé é accentuée", "clé é accentuée"));
	}

	@Test
	void fiveWrongKeysLockTheAddressThenTheLockExpires() {
		for (int i = 1; i < AdminGuard.MAX_FAILURES; i++) {
			assertEquals(AdminGuard.Verdict.WRONG_KEY, check("10.0.0.7", "faux" + i));
		}
		AdminGuard.Result locked = guard.check("10.0.0.7", true, "faux", KEY);
		assertEquals(AdminGuard.Verdict.LOCKED, locked.verdict());
		assertEquals(30, locked.retryAfterSeconds());
		// Bloquée : même la bonne clé attend (on ne la regarde pas).
		assertEquals(AdminGuard.Verdict.LOCKED, check("10.0.0.7", KEY));
		now.addAndGet(10_000);
		assertEquals(20, guard.check("10.0.0.7", true, KEY, KEY).retryAfterSeconds());
		// Une autre adresse n'est pas touchée.
		assertEquals(AdminGuard.Verdict.OK, check("10.0.0.8", KEY));
		now.addAndGet(20_001);
		assertEquals(AdminGuard.Verdict.OK, check("10.0.0.7", KEY));
	}

	@Test
	void eachNewLockLastsTwiceAsLongUpToFifteenMinutes() {
		long[] expected = {30, 60, 120, 240, 480, 900, 900};
		for (long seconds : expected) {
			AdminGuard.Result r = null;
			for (int i = 0; i < AdminGuard.MAX_FAILURES; i++) {
				r = guard.check("10.0.0.9", true, "faux", KEY);
			}
			assertEquals(AdminGuard.Verdict.LOCKED, r.verdict());
			assertEquals(seconds, r.retryAfterSeconds());
			now.addAndGet(seconds * 1000 + 1);
		}
	}

	@Test
	void goodKeyClearsTheSlate() {
		for (int i = 0; i < AdminGuard.MAX_FAILURES - 1; i++) {
			check("10.0.0.10", "faux");
		}
		assertEquals(AdminGuard.Verdict.OK, check("10.0.0.10", KEY));
		for (int i = 0; i < AdminGuard.MAX_FAILURES - 1; i++) {
			assertEquals(AdminGuard.Verdict.WRONG_KEY, check("10.0.0.10", "faux"));
		}
	}

	@Test
	void failuresOlderThanTheWindowAreForgotten() {
		for (int i = 0; i < AdminGuard.MAX_FAILURES - 1; i++) {
			check("10.0.0.11", "faux");
		}
		now.addAndGet(AdminGuard.WINDOW_MS + 1);
		assertEquals(AdminGuard.Verdict.WRONG_KEY, check("10.0.0.11", "faux"));
	}

	@Test
	void missingKeysAreNotCounted() {
		for (int i = 0; i < 20; i++) {
			assertEquals(AdminGuard.Verdict.MISSING_KEY, check("10.0.0.12", null));
		}
		assertEquals(AdminGuard.Verdict.OK, check("10.0.0.12", KEY));
	}

	@Test
	void resetLiftsLocks() {
		for (int i = 0; i < AdminGuard.MAX_FAILURES; i++) {
			check("10.0.0.13", "faux");
		}
		assertEquals(AdminGuard.Verdict.LOCKED, check("10.0.0.13", KEY));
		guard.reset();
		assertEquals(AdminGuard.Verdict.OK, check("10.0.0.13", KEY));
	}

	@Test
	void theTableDoesNotGrowWithoutEnd() {
		for (int i = 0; i < AdminGuard.MAX_TRACKED + 500; i++) {
			check("addr-" + i, "faux");
			if (i % 1000 == 0) {
				now.addAndGet(AdminGuard.WINDOW_MS + 1);
			}
		}
		assertTrue(guard.tracked() <= AdminGuard.MAX_TRACKED, "adresses suivies : " + guard.tracked());
	}

	@Test
	void onlyTheMachineItselfIsLoopback() throws Exception {
		assertTrue(AdminGuard.loopback(InetAddress.getByName("127.0.0.1")));
		assertTrue(AdminGuard.loopback(InetAddress.getByName("127.8.9.10")));
		assertTrue(AdminGuard.loopback(InetAddress.getByName("::1")));
		assertFalse(AdminGuard.loopback(InetAddress.getByName("192.168.1.20")));
		assertFalse(AdminGuard.loopback(InetAddress.getByName("10.0.0.1")));
		assertFalse(AdminGuard.loopback(InetAddress.getByName("203.0.113.5")));
		assertFalse(AdminGuard.loopback(InetAddress.getByName("fe80::1")));
		assertFalse(AdminGuard.loopback(null));
	}
}
