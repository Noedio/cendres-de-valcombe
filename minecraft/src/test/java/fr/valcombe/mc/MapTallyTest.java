package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/** Le compte des repères de l'atelier carte (« 12 trônes sur 16, 12 maisons, salle de convocation : oui ») et ce qui manque. */
class MapTallyTest {
	@Test
	void counterSaysWhatTheMapHas() {
		assertEquals("12 trônes sur 16, 12 maisons, salle de convocation : oui", new MapTally(12, 16, 12, true).counter());
		assertEquals("16 trônes, 16 maisons, salle de convocation : oui", new MapTally(16, 16, 16, true).counter(),
			"autant ou plus que prévu : pas de « sur »");
		assertEquals("18 trônes, 2 maisons, salle de convocation : non", new MapTally(18, 16, 2, false).counter());
		// Zéro et un au singulier, comme en français.
		assertEquals("1 trône sur 16, 0 maison, salle de convocation : non", new MapTally(1, 16, 0, false).counter());
		assertEquals("0 trône sur 16, 1 maison, salle de convocation : non", new MapTally(0, 16, 1, false).counter());
	}

	@Test
	void playableFromFiveThrones() {
		MapTally four = new MapTally(4, 16, 4, true);
		assertFalse(four.playable());
		assertEquals(1, four.missingSeats());
		assertEquals("Pas encore jouable : il manque 1 trône (5 au moins).", four.verdict());
		MapTally none = new MapTally(0, 16, 0, false);
		assertEquals(5, none.missingSeats());
		assertEquals("Pas encore jouable : il manque 5 trônes (5 au moins).", none.verdict());
		MapTally five = new MapTally(5, 16, 5, true);
		assertTrue(five.playable());
		assertEquals(0, five.missingSeats());
		assertEquals("Jouable.", five.verdict());
	}

	@Test
	void missingListsBlockingFirstThenAdvice() {
		List<MapTally.Line> lines = new MapTally(2, 16, 0, false).missing();
		assertEquals(3, lines.size());
		assertTrue(lines.get(0).blocking());
		assertEquals("Il manque 3 trônes pour jouer (5 au moins, un par joueur).", lines.get(0).text());
		assertFalse(lines.get(1).blocking());
		assertEquals("Aucune maison : la nuit, chacun reste sur son trône.", lines.get(1).text());
		assertTrue(lines.get(2).text().startsWith("Pas de salle de convocation"), lines.get(2).text());

		List<MapTally.Line> houses = new MapTally(12, 16, 8, true).missing();
		assertEquals(1, houses.size());
		assertFalse(houses.get(0).blocking());
		assertEquals("4 trônes n'ont pas de maison : certains joueurs dormiront à plusieurs.", houses.get(0).text());
		assertEquals("1 trône n'a pas de maison : certains joueurs dormiront à plusieurs.",
			new MapTally(6, 16, 5, true).missing().get(0).text());

		assertTrue(new MapTally(12, 16, 12, true).missing().isEmpty(), "rien à redire");
		assertTrue(new MapTally(12, 16, 14, true).missing().isEmpty(), "plus de maisons que de trônes : rien à redire");
	}

	@Test
	void talliesAVillage() {
		BlockPos c = new BlockPos(0, 64, 0);
		Village blank = Village.custom(c);
		MapTally t = MapTally.of(blank);
		assertEquals("0 trône sur 16, 0 maison, salle de convocation : non", t.counter());
		blank.addSeat(c.offset(3, 1, 0));
		blank.addHouse(c.offset(20, 1, 0));
		blank.setConvocation(c.offset(-30, 1, 0));
		assertEquals("1 trône sur 16, 1 maison, salle de convocation : oui", MapTally.of(blank).counter());

		Village gen = Village.generated(c, "port", 8, 1, true);
		MapTally g = MapTally.of(gen);
		assertEquals(8, g.seats());
		assertEquals(8, g.places());
		assertEquals("8 trônes, 8 maisons, salle de convocation : non", g.counter());
		assertTrue(g.playable());
	}
}
