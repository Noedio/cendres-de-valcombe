package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/**
 * Cartes de la bibliothèque posées dans un monde : passage des repères relatifs au centre (la fiche) aux coordonnées
 * du monde et retour, orientations des trônes, emprise, taille d'une capture.
 */
class MapPlacementTest {
	/** Une fiche de carte faite à la main : 5 trônes (le dernier tourné vers l'est), 2 maisons, une salle de convocation. */
	private static MapStore.MapDef def() {
		MapStore.MapDef d = new MapStore.MapDef();
		d.id = "essai";
		d.nom = "Essai";
		d.genere = false;
		d.theme = "gothique";
		d.places = 5;
		d.taille = 1;
		d.rayon = 40;
		d.hauteur = 36;
		d.sieges = new ArrayList<>(List.of(new int[] {10, 1, 0}, new int[] {0, 1, 10}, new int[] {-10, 1, 0}, new int[] {0, 1, -10},
			new int[] {7, 2, 7}));
		d.logis = new ArrayList<>(List.of(new int[] {25, 1, 3}, new int[] {-20, 0, -18}));
		d.convocation = new int[] {0, 1, 30};
		d.orientations = new ArrayList<>(List.of(new double[] {7, 2, 7, -90}, new double[] {99, 0, 99, 45}));
		return d;
	}

	private static final BlockPos CENTER = new BlockPos(100, 64, -50);

	@Test
	void lesReperesSontPosesEnCoordonneesDuMonde() {
		MapMarks m = MapMarks.of(def(), CENTER);
		assertEquals(CENTER, m.center());
		assertEquals(List.of(new BlockPos(110, 65, -50), new BlockPos(100, 65, -40), new BlockPos(90, 65, -50), new BlockPos(100, 65, -60),
			new BlockPos(107, 66, -43)), m.seatPositions());
		assertEquals(List.of(new BlockPos(125, 65, -47), new BlockPos(80, 64, -68)), m.houses());
		assertEquals(new BlockPos(100, 65, -20), m.convocation());
	}

	@Test
	void unTroneNonTourneRegardeLeCentre() {
		MapMarks m = MapMarks.of(def(), CENTER);
		// À l'est de la place, il regarde l'ouest (90°) ; au sud, le nord (180°) ; à l'ouest, l'est (-90°) ; au nord, le sud (0°).
		assertEquals(90f, m.seats().get(0).yaw(), 1e-4);
		assertEquals(180f, Math.abs(m.seats().get(1).yaw()), 1e-4);
		assertEquals(-90f, m.seats().get(2).yaw(), 1e-4);
		assertEquals(0f, m.seats().get(3).yaw(), 1e-4);
		for (int i = 0; i < 4; i++) {
			assertFalse(m.seats().get(i).turned());
		}
		// Le même calcul que la carte de jeu.
		Village v = Village.restored(CENTER, false, "gothique", 5, 1, true, false, def().sieges, def().logis, def().convocation);
		for (int i = 0; i < 4; i++) {
			assertEquals(v.seatYaw(i), m.seats().get(i).yaw(), 1e-4);
		}
	}

	@Test
	void uneOrientationEnregistreeSuitSonTroneEtLesAutresSontIgnorees() {
		MapMarks m = MapMarks.of(def(), CENTER);
		assertTrue(m.seats().get(4).turned());
		assertEquals(-90f, m.seats().get(4).yaw(), 1e-4);
		MapStore.MapDef back = new MapStore.MapDef();
		m.writeTo(back);
		// Seule l'orientation du trône tourné revient ; celle qui ne désignait aucun trône a disparu.
		assertEquals(1, back.orientations.size());
		assertArrayEquals(new double[] {7, 2, 7, -90}, back.orientations.get(0), 1e-6);
	}

	@Test
	void allerEtRetourDansLaFiche() {
		MapStore.MapDef d = def();
		MapStore.MapDef back = new MapStore.MapDef();
		MapMarks.of(d, CENTER).writeTo(back);
		assertEquals(d.sieges.size(), back.sieges.size());
		for (int i = 0; i < d.sieges.size(); i++) {
			assertArrayEquals(d.sieges.get(i), back.sieges.get(i));
		}
		for (int i = 0; i < d.logis.size(); i++) {
			assertArrayEquals(d.logis.get(i), back.logis.get(i));
		}
		assertArrayEquals(d.convocation, back.convocation);
	}

	@Test
	void sansTroneTourneNiSalleLaFicheResteCommeAvant() {
		MapStore.MapDef d = def();
		d.orientations = null;
		d.convocation = null;
		MapStore.MapDef back = new MapStore.MapDef();
		MapMarks m = MapMarks.of(d, CENTER);
		m.writeTo(back);
		assertNull(m.convocation());
		assertNull(back.orientations, "aucune orientation à la main : le champ reste absent");
		assertNull(back.convocation);
	}

	@Test
	void deplacerLaPlaceDeplaceTousLesRepereSansTournerLesTrones() {
		MapMarks m = MapMarks.of(def(), CENTER);
		BlockPos elsewhere = new BlockPos(0, 64, 0);
		MapMarks moved = m.movedTo(elsewhere);
		assertEquals(elsewhere, moved.center());
		assertEquals(MapMarks.of(def(), elsewhere), moved);
		for (int i = 0; i < m.seats().size(); i++) {
			assertArrayEquals(m.rel(m.seats().get(i).pos()), moved.rel(moved.seats().get(i).pos()));
			assertEquals(m.seats().get(i).yaw(), moved.seats().get(i).yaw(), 1e-6);
			assertEquals(m.seats().get(i).turned(), moved.seats().get(i).turned());
		}
		assertEquals(m, moved.translated(100, 0, -50));
	}

	@Test
	void rayonDemandeParLesReperes() {
		MapMarks m = MapMarks.of(def(), CENTER);
		// La salle de convocation, à 30 blocs, est le repère le plus loin : 30 + 8 de marge.
		assertEquals(38, m.reach());
		MapMarks close = new MapMarks(CENTER, List.of(new MapMarks.Seat(CENTER.offset(1, 0, 1), 0, false)), List.of(), null);
		assertEquals(MapMarks.MIN_REACH, close.reach());
		// Même calcul que la carte de jeu.
		Village v = Village.restored(CENTER, false, "gothique", 5, 1, true, false, def().sieges, def().logis, def().convocation);
		assertEquals(v.farthest(), m.reach());
	}

	@Test
	void emprisePoseeAutourDuCentre() {
		PlacedMap map = PlacedMap.of(def(), null, CENTER);
		assertEquals(new BlockPos(60, 60, -90), map.min());
		assertEquals(new BlockPos(140, 95, -10), map.max());
		assertEquals(36, map.max().getY() - map.min().getY() + 1);
		assertTrue(map.contains(CENTER));
		assertTrue(map.contains(map.min()));
		assertTrue(map.contains(map.max()));
		assertFalse(map.contains(map.max().above()));
		assertFalse(map.contains(map.min().west()));
		// Une carte d'avant le champ « hauteur » : 28 blocs.
		MapStore.MapDef old = def();
		old.hauteur = 0;
		assertEquals(28, PlacedMap.of(old, null, CENTER).hauteur());
	}

	@Test
	void laFicheDUneCartePoseeSeRelitTelleQuelle() {
		MapStore.MapDef d = def();
		PlacedMap map = PlacedMap.of(d, null, CENTER);
		MapStore.MapDef back = map.toDef();
		MapStore.checkDef(back);
		assertEquals(d.id, back.id);
		assertEquals(d.nom, back.nom);
		assertEquals(d.rayon, back.rayon);
		assertEquals(d.hauteur, back.hauteur);
		assertEquals(d.places, back.places);
		assertEquals(d.theme, back.theme);
		// Reposée ailleurs, la même carte a les mêmes repères relatifs.
		PlacedMap again = PlacedMap.of(back, null, new BlockPos(-300, 64, 12));
		List<int[]> a = map.relativeMarks();
		List<int[]> b = again.relativeMarks();
		assertEquals(a.size(), b.size());
		for (int i = 0; i < a.size(); i++) {
			assertArrayEquals(a.get(i), b.get(i));
		}
		PlacedMap renamed = map.renamed("autre", "Autre");
		assertEquals("autre", renamed.mapId());
		assertEquals(map.marks(), renamed.marks());
	}

	@Test
	void leVillageDeJeuDUneCartePosee() {
		PlacedMap map = PlacedMap.of(def(), null, CENTER);
		Village v = map.toVillage();
		assertEquals(CENTER, v.center());
		assertEquals("essai", v.libraryId());
		assertEquals("Essai", v.libraryName());
		assertEquals(5, v.seatCount());
		for (int i = 0; i < 5; i++) {
			assertEquals(map.marks().seats().get(i).yaw(), v.seatYaw(i), 1e-4);
		}
		assertTrue(v.seatTurned(4));
		assertFalse(v.seatTurned(0));
		assertEquals(map.marks().convocation(), v.convocation());
	}

	@Test
	void tailleDUneCapture() {
		// Le rayon couvre l'emprise prévue, les repères et la zone bâtie, sans dépasser la plus grande carte.
		assertEquals(40, MapPlacer.captureRadius(40, 38, 12));
		assertEquals(55, MapPlacer.captureRadius(40, 38, 55));
		assertEquals(MapStore.MAX_RADIUS, MapPlacer.captureRadius(40, 38, 900));
		// La hauteur : au moins 28, l'emprise prévue, et le plus haut bloc bâti plus un bloc de marge.
		assertEquals(28, MapPlacer.captureHeight(0, 64, Integer.MIN_VALUE, MapStore.MAX_HEIGHT));
		assertEquals(36, MapPlacer.captureHeight(36, 64, 70, MapStore.MAX_HEIGHT));
		// Plus haut bloc à y = 120, place à 64 : de 60 à 120, 61 rangées, plus une de marge.
		assertEquals(62, MapPlacer.captureHeight(36, 64, 120, MapStore.MAX_HEIGHT));
		assertEquals(MapStore.MAX_HEIGHT, MapPlacer.captureHeight(36, 64, 400, MapStore.MAX_HEIGHT));
		PlacedMap map = PlacedMap.of(def(), null, CENTER).resized(55, 62);
		assertNotNull(map.toDef());
		assertEquals(55, map.toDef().rayon);
		MapStore.checkDef(map.toDef());
	}
}
