package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldKind;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Le lien entre les ateliers carte et la bibliothèque : chaque atelier porte sa carte (fiche du registre des mondes), un
 * seul atelier par carte, et une carte neuve reçoit un identifiant libre tiré de son nom.
 */
class AtelierLinksTest {
	private static final Instant T = Instant.parse("2026-11-13T09:00:00Z");

	private static WorldEntry world(String id, WorldKind kind, String carte) {
		return new WorldEntry(id, id, kind, carte, T, T, "");
	}

	@Test
	void findsTheAtelierCarryingAMap() {
		List<WorldEntry> all = List.of(
			world("hub", WorldKind.HUB, null),
			world("partie-principale", WorldKind.PARTIE, "port"),
			world("essai", WorldKind.ESSAI, "monastere"),
			world("port-des-brumes", WorldKind.ATELIER_CARTE, "port"),
			world("chantier", WorldKind.ATELIER_CARTE, "abbaye"));
		assertEquals("port-des-brumes", AtelierLinks.atelierOf(all, "port").id(), "la partie qui porte la même carte ne compte pas");
		assertEquals("chantier", AtelierLinks.atelierOf(all, "abbaye").id());
		assertNull(AtelierLinks.atelierOf(all, "monastere"), "une carte posée dans une partie d'essai n'a pas d'atelier");
		assertNull(AtelierLinks.atelierOf(all, "absente"));
		assertNull(AtelierLinks.atelierOf(all, null));
		assertEquals(List.of("port-des-brumes", "chantier"), AtelierLinks.ateliers(all).stream().map(WorldEntry::id).toList());
	}

	@Test
	void freeMapIdComesFromTheName() {
		Set<String> taken = Set.of("port-des-brumes", "port-des-brumes-2", "carte");
		assertEquals("abbaye-d-ete", AtelierLinks.freeMapId("Abbaye d'Été", taken::contains));
		assertEquals("port-des-brumes-3", AtelierLinks.freeMapId("Port des Brumes", taken::contains));
		assertEquals("carte-2", AtelierLinks.freeMapId("!!", taken::contains), "un nom qui ne garde rien : « carte »");
		String longName = "Le très grand village au bord de la rivière";
		String id = AtelierLinks.freeMapId(longName, x -> false);
		assertEquals("le-tres-grand-village-au-bord", id, "coupé à 30 signes, sans tiret final");
		assertTrue(id.matches(AtelierLinks.MAP_ID), id);
		String next = AtelierLinks.freeMapId(longName, x -> x.equals(id));
		assertTrue(next.length() <= 30 && next.endsWith("-2") && next.matches(AtelierLinks.MAP_ID), next);
	}

	@Test
	void cleansMapNames() {
		assertEquals("Port des Brumes", AtelierLinks.cleanName("  Port   des Brumes "));
		assertNull(AtelierLinks.cleanName(""));
		assertNull(AtelierLinks.cleanName("   "));
		assertNull(AtelierLinks.cleanName("§cRouge"));
		assertNull(AtelierLinks.cleanName("x".repeat(51)));
		assertEquals("x".repeat(50), AtelierLinks.cleanName("x".repeat(50)));
	}
}
