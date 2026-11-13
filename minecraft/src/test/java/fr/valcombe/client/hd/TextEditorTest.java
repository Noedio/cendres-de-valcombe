package fr.valcombe.client.hd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Le texte d'un champ de saisie : insertion, sélection, effacement, mots, longueur, filtre, émojis. */
class TextEditorTest {

	@Test
	void saisieEtCurseur() {
		TextEditor e = new TextEditor();
		e.insert("Bonsoir");
		assertEquals("Bonsoir", e.text());
		assertEquals(7, e.cursor());
		e.move(-1, false, false);
		e.move(-1, false, false);
		e.insert("--");
		assertEquals("Bonso--ir", e.text());
		e.home(false);
		e.insert("« ");
		assertEquals("« Bonso--ir", e.text());
		e.end(false);
		assertEquals(e.text().length(), e.cursor());
	}

	@Test
	void selectionEtRemplacement() {
		TextEditor e = new TextEditor();
		e.setText("le Démon rôde");
		e.moveTo(3, false);
		e.moveTo(8, true);
		assertEquals("Démon", e.selected());
		e.insert("Sbire");
		assertEquals("le Sbire rôde", e.text());
		assertFalse(e.hasSelection());
		e.selectAll();
		assertEquals("le Sbire rôde", e.selected());
		e.delete(-1, false);
		assertEquals("", e.text());
	}

	@Test
	void flecheSurUneSelectionLaReferme() {
		TextEditor e = new TextEditor();
		e.setText("abcdef");
		e.moveTo(1, false);
		e.moveTo(4, true);
		e.move(-1, false, false);
		assertEquals(1, e.cursor(), "flèche gauche : au début de la sélection");
		assertFalse(e.hasSelection());
		e.moveTo(4, true);
		e.move(1, false, false);
		assertEquals(4, e.cursor(), "flèche droite : à la fin de la sélection");
	}

	@Test
	void parMots() {
		TextEditor e = new TextEditor();
		e.setText("la nuit tombe sur Valcombe");
		assertEquals(18, e.wordPosition(-1), "début du dernier mot");
		e.delete(-1, true);
		assertEquals("la nuit tombe sur ", e.text());
		e.home(false);
		e.move(1, true, false);
		assertEquals(2, e.cursor(), "fin du premier mot");
		e.move(1, true, true);
		assertEquals("la nuit", e.text().substring(0, e.cursor()));
		assertEquals(" nuit", e.selected());
		e.delete(1, true);
		assertEquals("la tombe sur ", e.text());
	}

	@Test
	void longueurMaximaleEtFiltre() {
		TextEditor e = new TextEditor().maxLength(5);
		e.insert("Valcombe");
		assertEquals("Valco", e.text(), "coupé à la longueur maximale");
		e.insert("x");
		assertEquals("Valco", e.text(), "plus de place");
		TextEditor digits = new TextEditor().filter(s -> s.chars().allMatch(Character::isDigit));
		assertTrue(digits.insert("42"));
		assertFalse(digits.insert("a"), "refusé par le filtre");
		assertEquals("42", digits.text());
	}

	@Test
	void controlesRetiresEtEmojisEntiers() {
		TextEditor e = new TextEditor();
		e.insert("une\nligne\t!");
		assertEquals("uneligne!", e.text(), "retours à la ligne et tabulations retirés");
		e.setText("a🕯b");
		e.moveTo(2, false);
		assertEquals(1, e.cursor(), "jamais au milieu d'un émoji");
		e.move(1, false, false);
		assertEquals(3, e.cursor(), "l'émoji se saute d'un coup");
		e.delete(-1, false);
		assertEquals("ab", e.text(), "l'émoji s'efface d'un coup");
		TextEditor cut = new TextEditor().maxLength(2);
		cut.insert("a🕯");
		assertEquals("a", cut.text(), "pas de demi-émoji au bout");
	}
}
