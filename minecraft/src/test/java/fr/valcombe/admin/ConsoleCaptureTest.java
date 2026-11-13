package fr.valcombe.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

class ConsoleCaptureTest {
	@Test
	void journalLinesReachTheBuffer() {
		ConsoleBuffer buffer = new ConsoleBuffer(100);
		assertTrue(ConsoleCapture.install(buffer));
		assertTrue(ConsoleCapture.install(buffer), "une seconde installation ne gêne pas");
		Logger log = LogManager.getLogger("valcombe-test");
		// La configuration par défaut de log4j ne laisse passer que les erreurs : on écrit à ce niveau.
		log.error("Bonjour {} !", "Valcombe");
		log.error("Échec", new IllegalStateException("mur effondré"));
		List<ConsoleBuffer.Line> lines = buffer.since(0, 100).lines();
		ConsoleBuffer.Line hello = lines.stream().filter(l -> l.text().equals("Bonjour Valcombe !")).findFirst().orElseThrow();
		assertEquals("ERROR", hello.level());
		assertEquals("valcombe-test", hello.source());
		assertEquals(Thread.currentThread().getName(), hello.thread());
		ConsoleBuffer.Line failure = lines.stream().filter(l -> l.text().startsWith("Échec")).findFirst().orElseThrow();
		assertTrue(failure.text().contains("IllegalStateException: mur effondré"), failure.text());
		assertTrue(failure.text().contains("\tat "), "la trace suit le message");
	}
}
