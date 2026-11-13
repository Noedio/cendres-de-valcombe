package fr.valcombe.client.hd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Défilement des listes : bornes, curseur de l'ascenseur, glisser, ligne amenée dans la fenêtre. */
class ScrollTest {

	@Test
	void bornes() {
		Scroll s = new Scroll().resize(400, 100);
		assertTrue(s.scrollable());
		assertEquals(300, s.max());
		assertTrue(s.scrollBy(50));
		assertEquals(50, s.offset());
		s.scrollBy(1000);
		assertEquals(300, s.offset(), "pas plus bas que le bas");
		assertFalse(s.scrollBy(10), "déjà en bas");
		s.scrollBy(-1000);
		assertEquals(0, s.offset(), "pas plus haut que le haut");
	}

	@Test
	void contenuPlusPetitQueLaFenetre() {
		Scroll s = new Scroll().resize(80, 100);
		assertFalse(s.scrollable());
		s.scrollBy(30);
		assertEquals(0, s.offset());
		assertEquals(100, s.thumbLength(100), "le curseur occupe tout le rail");
	}

	@Test
	void retrecirGardeLaPositionValide() {
		Scroll s = new Scroll().resize(400, 100);
		s.setOffset(250);
		s.resize(200, 100);
		assertEquals(100, s.offset(), "la liste a raccourci : on remonte à la nouvelle fin");
	}

	@Test
	void curseurEtGlisser() {
		Scroll s = new Scroll().resize(400, 100);
		assertEquals(25, s.thumbLength(100), 1e-9, "fenêtre = un quart du contenu");
		assertEquals(0, s.thumbStart(100), 1e-9);
		s.setOffset(300);
		assertEquals(75, s.thumbStart(100), 1e-9, "en bas : curseur au bas du rail");
		s.dragThumbTo(37.5, 100);
		assertEquals(150, s.offset(), 1e-9, "curseur à mi-course : contenu à mi-course");
		Scroll longList = new Scroll().resize(100000, 100);
		assertEquals(12, longList.thumbLength(100), 1e-9, "longueur minimale du curseur");
	}

	@Test
	void reveler() {
		Scroll s = new Scroll().resize(400, 100);
		s.reveal(150, 170);
		assertEquals(70, s.offset(), "la ligne apparaît en bas de la fenêtre");
		s.reveal(20, 40);
		assertEquals(20, s.offset(), "la ligne apparaît en haut de la fenêtre");
		s.reveal(30, 50);
		assertEquals(20, s.offset(), "déjà visible : on ne bouge pas");
	}
}
