package fr.valcombe.mc.worlds;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Le point unique « peut construire ici » : opérateurs partout, invités seulement dans les ateliers. */
class BuildRightsTest {
	@Test
	void operateursPartoutInvitesDansLesAteliers() {
		assertTrue(BuildRights.decide(true, null, () -> false), "un opérateur construit même hors des mondes de Valcombe");
		assertTrue(BuildRights.decide(true, WorldKind.PARTIE, () -> false));
		assertFalse(BuildRights.decide(false, WorldKind.ATELIER_STRUCTURES, () -> false), "pas invité : non");
		assertTrue(BuildRights.decide(false, WorldKind.ATELIER_STRUCTURES, () -> true), "invité dans l'atelier structures : oui");
		assertTrue(BuildRights.decide(false, WorldKind.ATELIER_CARTE, () -> true), "invité dans un atelier carte : oui");
		assertFalse(BuildRights.decide(false, WorldKind.PARTIE, () -> true), "une invitation ne vaut pas hors des ateliers");
		assertFalse(BuildRights.decide(false, WorldKind.HUB, () -> true));
		assertFalse(BuildRights.decide(false, null, () -> true), "ni dans le monde principal");
	}
}
