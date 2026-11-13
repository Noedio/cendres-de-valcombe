package fr.valcombe.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.Test;

class ConsoleCaptureTest {
	/** La copie ne s'installe qu'une fois par processus : tous les tests lisent le même tampon. */
	private static final ConsoleBuffer BUFFER = new ConsoleBuffer(100);

	@Test
	void journalLinesReachTheBuffer() {
		assertTrue(ConsoleCapture.install(BUFFER));
		assertTrue(ConsoleCapture.install(BUFFER), "une seconde installation ne gêne pas");
		Logger log = LogManager.getLogger("valcombe-test");
		// La configuration par défaut de log4j ne laisse passer que les erreurs : on écrit à ce niveau.
		log.error("Bonjour {} !", "Valcombe");
		log.error("Échec", new IllegalStateException("mur effondré"));
		List<ConsoleBuffer.Line> lines = BUFFER.since(0, 100).lines();
		ConsoleBuffer.Line hello = lines.stream().filter(l -> l.text().equals("Bonjour Valcombe !")).findFirst().orElseThrow();
		assertEquals("ERROR", hello.level());
		assertEquals("valcombe-test", hello.source());
		assertEquals(Thread.currentThread().getName(), hello.thread());
		ConsoleBuffer.Line failure = lines.stream().filter(l -> l.text().startsWith("Échec")).findFirst().orElseThrow();
		assertTrue(failure.text().contains("IllegalStateException: mur effondré"), failure.text());
		assertTrue(failure.text().contains("\tat "), "la trace suit le message");
	}

	@Test
	void debugLinesStayOut() {
		assertTrue(ConsoleCapture.install(BUFFER));
		// Comme sous Fabric : ce journal laisse passer DEBUG (pour debug.log), mais la console n'en montre rien.
		Configurator.setLevel("valcombe-test-debug", Level.DEBUG);
		Logger log = LogManager.getLogger("valcombe-test-debug");
		log.debug("détail de mise au point");
		log.error("à montrer");
		List<ConsoleBuffer.Line> lines = BUFFER.since(0, 100).lines();
		assertTrue(lines.stream().noneMatch(l -> l.text().equals("détail de mise au point")), "DEBUG écarté");
		assertTrue(lines.stream().anyMatch(l -> l.text().equals("à montrer")), "ERROR copié");
	}
}
