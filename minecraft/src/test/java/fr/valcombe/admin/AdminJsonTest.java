package fr.valcombe.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AdminJsonTest {
	private static InputStream bytes(String s) {
		return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
	}

	private static int status(Runnable r) {
		return assertThrows(AdminException.class, r::run).status();
	}

	@Test
	void bodyWithinTheLimitIsRead() throws IOException {
		assertEquals("{\"a\":\"é\"}", AdminJson.readBody(bytes("{\"a\":\"é\"}"), -1, 100));
		assertEquals("", AdminJson.readBody(bytes(""), 0, 100));
		assertEquals("x".repeat(100), AdminJson.readBody(bytes("x".repeat(100)), -1, 100));
	}

	@Test
	void bodyBeyondTheLimitIsRefused() {
		assertEquals(413, status(() -> read(bytes("x".repeat(101)), -1, 100)));
		// Longueur annoncée trop grande : refusée sans rien lire.
		InputStream never = new InputStream() {
			@Override
			public int read() {
				throw new AssertionError("ne doit pas être lu");
			}
		};
		assertEquals(413, status(() -> read(never, 10_000_000, 100)));
	}

	@Test
	void anEndlessBodyIsCutShort() {
		InputStream endless = new InputStream() {
			private long served;

			@Override
			public int read() {
				served++;
				return 'x';
			}

			@Override
			public int read(byte[] b, int off, int len) {
				if (served > 10_000) {
					throw new AssertionError("lecture au-delà de la limite");
				}
				for (int i = 0; i < len; i++) {
					b[off + i] = 'x';
				}
				served += len;
				return len;
			}
		};
		assertEquals(413, status(() -> read(endless, -1, 1024)));
	}

	@Test
	void invalidUtf8IsRefused() {
		assertEquals(400, status(() -> read(new ByteArrayInputStream(new byte[] {'{', (byte) 0xC3, '}'}), -1, 100)));
	}

	@Test
	void strictJsonObjectsOnly() {
		assertEquals(3, AdminJson.parseObject("{\"commande\": \"list\", \"a\": [1, 2], \"b\": null}").size());
		assertEquals(400, status(() -> AdminJson.parseObject("")));
		assertEquals(400, status(() -> AdminJson.parseObject("[1]")));
		assertEquals(400, status(() -> AdminJson.parseObject("\"texte\"")));
		assertEquals(400, status(() -> AdminJson.parseObject("{'a': 1}")));
		assertEquals(400, status(() -> AdminJson.parseObject("{a: 1}")));
		assertEquals(400, status(() -> AdminJson.parseObject("{\"a\": 1} {\"b\": 2}")));
		assertEquals(400, status(() -> AdminJson.parseObject("{\"a\": 1} x")));
		assertEquals(400, status(() -> AdminJson.parseObject("{\"a\": 1,}")));
		assertEquals(400, status(() -> AdminJson.parseObject("{\"a\": 1 // commentaire\n}")));
		assertEquals(400, status(() -> AdminJson.parseObject("{\"a\": NaN}")));
		assertEquals(400, status(() -> AdminJson.parseObject("{\"a\": ")));
		assertEquals(400, status(() -> AdminJson.parseObject("[".repeat(60_000))));
	}

	private static void read(InputStream in, long declared, int limit) {
		try {
			AdminJson.readBody(in, declared, limit);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
}
