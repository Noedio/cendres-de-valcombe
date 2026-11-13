package fr.valcombe.mc.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.GameException;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Retrouver un monde par son identifiant, son nom, un morceau de l'un ou de l'autre, ou un nom approché. */
class WorldLookupTest {
	private static WorldEntry entry(String id, String nom, WorldKind kind) {
		Instant t = Instant.parse("2026-11-13T09:00:00Z");
		return new WorldEntry(id, nom, kind, null, t, t, "");
	}

	private static final List<WorldEntry> ALL = List.of(
		entry("hub", "Hub", WorldKind.HUB),
		entry("partie", "Partie principale", WorldKind.PARTIE),
		entry("essai-du-soir", "Essai du soir", WorldKind.ESSAI),
		entry("essai-2", "Essai d'été", WorldKind.ESSAI),
		entry("chateau-d-ete", "Château d'Été", WorldKind.ATELIER_CARTE));

	private static String id(String typed) {
		return WorldLookup.resolve(ALL, typed).id();
	}

	@Test
	void parIdentifiantOuParNom() {
		assertEquals("partie", id("partie"));
		assertEquals("partie", id("PARTIE"));
		assertEquals("essai-du-soir", id("Essai du soir"));
		assertEquals("chateau-d-ete", id("château d'été"));
		assertEquals("essai-2", id("essai d'ete"), "le nom compte, même quand l'identifiant ne lui ressemble pas");
	}

	@Test
	void parLeDebutOuUnMorceau() {
		assertEquals("partie", id("partie princ"));
		assertEquals("essai-du-soir", id("soir"));
		assertEquals("chateau-d-ete", id("chat"));
	}

	@Test
	void unNomApproche() {
		assertEquals("partie", id("partei"));
		assertEquals("essai-du-soir", id("Esai du soir"));
	}

	@Test
	void plusieursMondesRepondent() {
		GameException e = assertThrows(GameException.class, () -> id("essai"));
		assertTrue(e.getMessage().contains("essai-du-soir") && e.getMessage().contains("essai-2"), e.getMessage());
	}

	@Test
	void aucunMondeNeRepond() {
		assertThrows(GameException.class, () -> id("caverne-lointaine"));
		assertThrows(GameException.class, () -> id("   "));
		assertThrows(GameException.class, () -> id(null));
	}

	@Test
	void distanceEntreDeuxNoms() {
		assertEquals(0, WorldLookup.distance("soir", "soir"));
		assertEquals(1, WorldLookup.distance("soir", "soirs"));
		assertEquals(1, WorldLookup.distance("partei", "partie"), "deux lettres interverties : une seule faute");
		assertEquals(2, WorldLookup.distance("pxrtxe", "partie"));
		assertEquals(4, WorldLookup.distance("", "soir"));
	}
}
