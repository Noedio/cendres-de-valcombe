package fr.valcombe.mc.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Le monde de la partie principale : toujours le même, retrouvé d'un démarrage à l'autre. */
class MainGameTest {
	@TempDir
	Path tmp;

	private static WorldEntry entry(String id, WorldKind kind) {
		Instant t = Instant.parse("2026-11-13T09:00:00Z");
		return new WorldEntry(id, id, kind, null, t, t, "");
	}

	@Test
	void leMondeDeLaPartiePrincipaleEstRetrouve() {
		assertNull(WorldRegistry.mainGame(List.of()), "premier démarrage : il reste à créer");
		assertNull(WorldRegistry.mainGame(List.of(entry("hub", WorldKind.HUB), entry("essai", WorldKind.ESSAI))));
		assertEquals("partie-principale", WorldRegistry.mainGame(List.of(entry("hub", WorldKind.HUB),
			entry("essai", WorldKind.ESSAI), entry("partie-principale", WorldKind.PARTIE))).id());
		assertEquals("partie-principale", WorldRegistry.mainGame(List.of(entry("partie-principale-2", WorldKind.PARTIE),
			entry("partie-principale", WorldKind.PARTIE))).id(), "le nom exact d'abord");
		assertEquals("partie-principale-2", WorldRegistry.mainGame(List.of(entry("partie-du-soir", WorldKind.PARTIE),
			entry("partie-principale-2", WorldKind.PARTIE), entry("partie-principale-3", WorldKind.PARTIE))).id(),
			"créé sous un autre numéro (dossier sans fiche) : retrouvé, jamais recréé");
		assertNull(WorldRegistry.mainGame(List.of(entry("partie-principale", WorldKind.ESSAI),
			entry("partie-principale-bis", WorldKind.PARTIE))), "ni une autre sorte, ni un autre nom");
	}

	@Test
	void creeSousSonIdentifiant() {
		WorldRegistry r = new WorldRegistry(tmp.resolve("mondes.json"));
		WorldEntry e = r.create(WorldKind.PARTIE, WorldRegistry.MAIN_GAME_NAME);
		assertEquals(WorldRegistry.MAIN_GAME_ID, e.id(), "« Partie principale » donne l'identifiant partie-principale");
		assertEquals(e, WorldRegistry.mainGame(r.list()));
		r.rename(e.id(), "La grande table");
		assertEquals(WorldRegistry.MAIN_GAME_ID, WorldRegistry.mainGame(r.list()).id(), "renommé, il reste le monde de la partie");
	}
}
