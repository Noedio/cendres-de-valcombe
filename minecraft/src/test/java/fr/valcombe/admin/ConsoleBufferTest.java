package fr.valcombe.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ConsoleBufferTest {
	private static ConsoleBuffer filled(int capacity, int lines) {
		ConsoleBuffer b = new ConsoleBuffer(capacity);
		for (int i = 1; i <= lines; i++) {
			b.append(i, "INFO", "Server thread", "test", "ligne " + i);
		}
		return b;
	}

	private static List<Long> numbers(ConsoleBuffer.Page p) {
		List<Long> out = new ArrayList<>();
		p.lines().forEach(l -> out.add(l.n()));
		return out;
	}

	@Test
	void emptyBufferGivesNothing() {
		ConsoleBuffer b = new ConsoleBuffer(5);
		ConsoleBuffer.Page p = b.since(0, 10);
		assertTrue(p.lines().isEmpty());
		assertEquals(0, p.next());
		assertEquals(0, p.first());
		assertFalse(p.more());
		assertTrue(b.tail(10).lines().isEmpty());
	}

	@Test
	void numbersGrowAndOnlyNewLinesAreRead() {
		ConsoleBuffer b = filled(10, 3);
		ConsoleBuffer.Page p = b.since(0, 100);
		assertEquals(List.of(1L, 2L, 3L), numbers(p));
		assertEquals("ligne 3", p.lines().get(2).text());
		assertEquals(3, p.next());
		assertEquals(4, b.append(4, "WARN", "t", "s", "ligne 4"));
		ConsoleBuffer.Page q = b.since(p.next(), 100);
		assertEquals(List.of(4L), numbers(q));
		assertEquals("WARN", q.lines().get(0).level());
		ConsoleBuffer.Page none = b.since(q.next(), 100);
		assertTrue(none.lines().isEmpty());
		assertEquals(4, none.next());
	}

	@Test
	void oldLinesAreOverwrittenAndLateReadersAreWarned() {
		ConsoleBuffer b = filled(5, 12); // lignes 8 à 12 gardées
		assertEquals(8, b.first());
		assertEquals(12, b.last());
		ConsoleBuffer.Page p = b.since(2, 100);
		assertEquals(List.of(8L, 9L, 10L, 11L, 12L), numbers(p));
		assertEquals(5, p.lost()); // lignes 3 à 7
		assertEquals("ligne 8", p.lines().get(0).text());
		assertEquals(0, b.since(7, 100).lost());
	}

	@Test
	void pagesAreBoundedAndSayWhenMoreRemains() {
		ConsoleBuffer b = filled(100, 25);
		ConsoleBuffer.Page p = b.since(0, 10);
		assertEquals(10, p.lines().size());
		assertTrue(p.more());
		assertEquals(10, p.next());
		ConsoleBuffer.Page q = b.since(p.next(), 10);
		assertEquals(11, q.lines().get(0).n());
		ConsoleBuffer.Page r = b.since(20, 10);
		assertEquals(5, r.lines().size());
		assertFalse(r.more());
	}

	@Test
	void tailGivesTheLastLines() {
		ConsoleBuffer b = filled(10, 30);
		ConsoleBuffer.Page p = b.tail(4);
		assertEquals(List.of(27L, 28L, 29L, 30L), numbers(p));
		assertEquals(30, p.next());
		assertEquals(0, p.lost());
		assertEquals(List.of(21L, 22L, 23L, 24L, 25L, 26L, 27L, 28L, 29L, 30L), numbers(b.tail(500)));
	}

	@Test
	void aNumberFromAPreviousRunStartsOver() {
		ConsoleBuffer b = filled(10, 3);
		// Le launcher garde « 500 » d'avant le redémarrage : il reçoit tout depuis le début.
		assertEquals(List.of(1L, 2L, 3L), numbers(b.since(500, 100)));
		assertEquals(List.of(1L, 2L, 3L), numbers(b.since(-4, 100)));
		assertNotEquals(new ConsoleBuffer(1).instance(), b.instance());
	}

	@Test
	void longLinesAreCut() {
		ConsoleBuffer b = new ConsoleBuffer(2);
		b.append(0, null, null, null, "x".repeat(ConsoleBuffer.MAX_CHARS * 2));
		ConsoleBuffer.Line l = b.since(0, 1).lines().get(0);
		assertEquals(ConsoleBuffer.MAX_CHARS, l.text().length());
		assertTrue(l.text().endsWith("…"));
		assertEquals("", l.level());
	}

	@Test
	void concurrentWritersNeverLoseANumber() throws Exception {
		ConsoleBuffer b = new ConsoleBuffer(50_000);
		List<Thread> threads = new ArrayList<>();
		for (int t = 0; t < 8; t++) {
			Thread th = new Thread(() -> {
				for (int i = 0; i < 2000; i++) {
					b.append(i, "INFO", "t", "s", "x");
				}
			});
			threads.add(th);
			th.start();
		}
		for (Thread th : threads) {
			th.join();
		}
		assertEquals(16_000, b.last());
		ConsoleBuffer.Page all = b.since(0, 20_000);
		assertEquals(16_000, all.lines().size());
		for (int i = 0; i < all.lines().size(); i++) {
			assertEquals(i + 1, all.lines().get(i).n());
		}
	}
}
