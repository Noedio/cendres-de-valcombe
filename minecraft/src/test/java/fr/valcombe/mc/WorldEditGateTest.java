package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.client.BuildSelection;

import org.junit.jupiter.api.Test;

/** WorldEdit pour les invités, et l'étiquette de la sélection montrée en 3D. */
class WorldEditGateTest {
	@Test
	void unInviteConstruitMaisNeToucheNiAuServeurNiASesFichiers() {
		for (String ok : new String[] {"selection.pos", "region.set", "region.walls", "clipboard.copy", "clipboard.paste", "history.undo",
			"brush.sphere", "navigation.jumpto.tool", "schematic.load", "wand", "worldsomething"}) {
			assertFalse(WorldEditGate.deniedToGuests(ok), ok);
		}
		for (String no : new String[] {"reload", "world", "scripting.execute", "snapshots.restore", "schematic.delete", "schematic.save",
			"limit.unrestricted", "delchunks", "anyblock", "report", "debugpaste"}) {
			assertTrue(WorldEditGate.deniedToGuests(no), no);
		}
	}

	@Test
	void lEtiquetteDitLesTroisCotesEtLeNombreDeBlocs() {
		// Boîte de x 0 à 11 (12), y 64 à 68 (5), z -3 à 4 (8).
		int[] box = {0, 64, -3, 11, 68, 4};
		assertEquals("L 12 × l 8 × h 5", BuildSelection.dimensions(box));
		assertEquals("480 blocs", BuildSelection.blocks("cuboide", 480));
		assertEquals("1 bloc", BuildSelection.blocks("cuboide", 1));
		assertEquals("ellipsoïde · 12 345 blocs", BuildSelection.blocks("ellipsoide", 12345));
		assertEquals("polygone · 1 000 000 blocs", BuildSelection.blocks("polygone", 1_000_000));
	}
}
