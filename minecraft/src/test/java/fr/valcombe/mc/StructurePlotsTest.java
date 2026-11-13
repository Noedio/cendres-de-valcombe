package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** Disposition des parcelles de l'atelier structures : rangées par catégorie, rien ne bouge, places réutilisées. */
class StructurePlotsTest {
	private static StructurePlots.Need need(String id, String categorie, int rayon) {
		return new StructurePlots.Need(id, categorie, rayon, 2, 16);
	}

	/** Deux parcelles d'une même rangée ne se touchent pas : de l'herbe (GAP) entre leurs bordures. */
	private static void assertApart(StructurePlots.Layout l) {
		for (StructurePlots.Plot a : l.parcelles) {
			for (StructurePlots.Plot b : l.parcelles) {
				if (a == b) {
					continue;
				}
				boolean xApart = a.east() + StructurePlots.GAP < b.west() || b.east() + StructurePlots.GAP < a.west();
				boolean zApart = a.z + a.rayon + 1 < b.z - b.rayon - 1 || b.z + b.rayon + 1 < a.z - a.rayon - 1;
				assertTrue(xApart || zApart, a.id + " et " + b.id + " se chevauchent");
			}
		}
	}

	@Test
	void uneRangeeParCategorieDansLOrdre() {
		StructurePlots.Layout l = new StructurePlots.Layout();
		List<StructurePlots.Need> needs = List.of(
			need("banc", "decor", 4), need("maison-a", "maisons", 8), need("puits", "place", 5),
			need("tour", "perso", 6), need("maison-b", "maisons", 8), need("pavillon", "place", 15));
		List<StructurePlots.Plot> made = StructurePlots.allocate(l, needs);
		assertEquals(6, made.size());
		for (int i = 0; i < needs.size(); i++) {
			assertEquals(needs.get(i).id(), made.get(i).id, "renvoyées dans l'ordre des demandes");
		}
		assertEquals(4, l.bandes.size(), "une rangée par catégorie");
		List<String> order = l.bandes.stream().sorted((a, b) -> Integer.compare(a.z0, b.z0)).map(b -> b.categorie).toList();
		assertEquals(List.of("maisons", "place", "decor", "perso"), order, "maisons au nord, vos structures au sud");
		// Toutes les parcelles d'une rangée ont leur centre sur la même ligne, et tiennent dans la rangée.
		Map<Integer, List<StructurePlots.Plot>> rows = l.parcelles.stream().collect(Collectors.groupingBy(p -> p.bande));
		for (StructurePlots.Band b : l.bandes) {
			for (StructurePlots.Plot p : rows.get(b.z0)) {
				assertEquals(b.centerZ(), p.z, p.id + " sur la ligne de sa rangée");
				assertTrue(p.z - p.rayon - 1 >= b.z0 && p.z + p.rayon + 1 <= b.southZ(), p.id + " dans sa rangée");
				assertEquals(b.categorie, p.categorie);
			}
		}
		// La rangée « pour la place » est aussi large que le pavillon.
		assertEquals(15, l.bandes.stream().filter(b -> b.categorie.equals("place")).findFirst().orElseThrow().rayon);
		// Les rangées laissent la place d'une allée entre elles.
		List<StructurePlots.Band> sorted = new ArrayList<>(l.bandes);
		sorted.sort((a, b) -> Integer.compare(a.z0, b.z0));
		for (int i = 1; i < sorted.size(); i++) {
			assertTrue(sorted.get(i).z0 - sorted.get(i - 1).southZ() > 4, "allée entre les rangées");
		}
		assertEquals(StructurePlots.FIRST_Z, sorted.get(0).z0);
		assertEquals(StructurePlots.X0, l.plot("maison-a").west(), "la première parcelle commence au bord ouest");
		assertTrue(l.plot("maison-b").west() > l.plot("maison-a").east() + StructurePlots.GAP, "la seconde à côté, d'ouest en est");
		assertApart(l);
	}

	@Test
	void lesParcellesEnPlaceNeBougentPas() {
		StructurePlots.Layout l = new StructurePlots.Layout();
		StructurePlots.allocate(l, List.of(need("a", "decor", 5), need("b", "decor", 5), need("c", "decor", 5)));
		int ax = l.plot("a").x;
		int bx = l.plot("b").x;
		int cx = l.plot("c").x;
		int z = l.plot("a").z;
		StructurePlots.allocate(l, List.of(need("d", "decor", 4), need("e", "maisons", 8)));
		assertEquals(ax, l.plot("a").x);
		assertEquals(bx, l.plot("b").x);
		assertEquals(cx, l.plot("c").x);
		assertEquals(z, l.plot("a").z, "une rangée neuve s'ouvre au sud, les autres restent");
		assertTrue(l.plot("d").x > cx, "la nouvelle va au bout de sa rangée");
		assertEquals(l.plot("a").bande, l.plot("d").bande);
		assertTrue(l.plot("e").bande > l.plot("a").bande, "les maisons arrivées après : rangée neuve au sud");
		assertApart(l);
	}

	@Test
	void unePlaceLibereeEstReprise() {
		StructurePlots.Layout l = new StructurePlots.Layout();
		StructurePlots.allocate(l, List.of(need("a", "perso", 5), need("b", "perso", 5), need("c", "perso", 5)));
		int bx = l.plot("b").x;
		assertTrue(StructurePlots.remove(l, "b"));
		assertNull(l.plot("b"));
		StructurePlots.Plot d = StructurePlots.allocate(l, List.of(need("d", "perso", 4))).get(0);
		assertEquals(l.plot("a").bande, d.bande, "même rangée");
		assertTrue(d.west() > l.plot("a").east() && d.east() < l.plot("c").west(), "dans le trou laissé par b");
		assertTrue(Math.abs(d.x - bx) <= 1);
		// Une parcelle trop large pour le trou va au bout.
		StructurePlots.Plot e = StructurePlots.allocate(l, List.of(need("e", "perso", 5))).get(0);
		assertTrue(e.west() > l.plot("c").east(), "trop large pour le trou : au bout de la rangée");
		assertApart(l);
	}

	@Test
	void uneParcelleTropGrandePourSaRangeeOuvreUneRangee() {
		StructurePlots.Layout l = new StructurePlots.Layout();
		StructurePlots.allocate(l, List.of(need("petit", "decor", 4)));
		StructurePlots.Plot big = StructurePlots.allocate(l, List.of(need("grand", "decor", 12))).get(0);
		assertEquals(2, l.bandes.size());
		assertTrue(big.bande > l.plot("petit").bande);
		// Une petite suivante reprend la première rangée de sa catégorie (la plus au nord où elle tient).
		StructurePlots.Plot small = StructurePlots.allocate(l, List.of(need("petit-2", "decor", 4))).get(0);
		assertEquals(l.plot("petit").bande, small.bande);
	}

	@Test
	void uneRangeePleineContinueDansUneAutre() {
		StructurePlots.Layout l = new StructurePlots.Layout();
		List<StructurePlots.Need> needs = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			needs.add(need("s" + i, "perso", 8));
		}
		StructurePlots.allocate(l, needs);
		assertTrue(l.bandes.size() >= 2, "20 parcelles de 17 blocs ne tiennent pas sur " + StructurePlots.MAX_ROW + " blocs");
		for (StructurePlots.Plot p : l.parcelles) {
			assertTrue(p.east() <= StructurePlots.X0 + StructurePlots.MAX_ROW - 1, p.id + " dépasse le bout de la rangée");
		}
		assertApart(l);
	}

	@Test
	void rayonDUneParcelle() {
		// Une maison de 11 × 11, les pieds au milieu : 5 de chaque côté, plus la marge.
		assertEquals(5 + StructurePlots.MARGIN, StructurePlots.rayonFor(11, 11, 5, 5));
		// Les pieds au bord : la parcelle reste carrée et centrée sur eux, assez grande pour la tourner.
		assertEquals(10 + StructurePlots.MARGIN, StructurePlots.rayonFor(11, 3, 0, 1));
		assertEquals(StructurePlots.MIN_RAYON, StructurePlots.rayonFor(1, 1, 0, 0), "jamais plus petite que le minimum");
		assertEquals(StructurePlots.MAX_RAYON, StructurePlots.rayonFor(128, 128, 0, 0), "ni plus grande que le maximum");
	}

	@Test
	void parcelleSousLesPiedsEtPupitres() {
		StructurePlots.Layout l = new StructurePlots.Layout();
		StructurePlots.allocate(l, List.of(need("a", "decor", 5), need("b", "decor", 5)));
		StructurePlots.Plot a = l.plot("a");
		StructurePlots.Plot b = l.plot("b");
		assertSame(a, l.at(a.x, a.z), "au centre");
		assertSame(a, l.at(a.west(), a.z), "sur la bordure");
		assertSame(a, l.at(a.x, a.lecternZ() - 1), "dans l'allée, devant le pupitre");
		assertNull(l.at(a.east() + 1, a.z), "dans l'herbe entre deux parcelles : ni l'une ni l'autre");
		assertSame(b, l.lecternAt(b.x, b.lecternZ()));
		assertNull(l.lecternAt(b.x + 1, b.lecternZ()));
		assertEquals(a.bande - 2, a.lecternZ(), "le pupitre est dans l'allée");
		assertTrue(a.inside(a.x + a.rayon, a.z - a.rayon));
		assertFalse(a.inside(a.x + a.rayon + 1, a.z));
		List<StructurePlots.Plot> ordered = l.ordered();
		assertEquals("a", ordered.get(0).id, "ordre de lecture : d'ouest en est");
		assertNotNull(l.band(a.bande));
	}
}
