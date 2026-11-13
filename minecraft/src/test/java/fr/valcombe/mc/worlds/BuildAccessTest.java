package fr.valcombe.mc.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.mc.worlds.BuildAccess.Rights;

import org.junit.jupiter.api.Test;

/** Qui construit où, et qui se sert de WorldEdit : la règle de BuildAccess, monde par monde. */
class BuildAccessTest {
	/** Les droits d'un joueur, sans le réglage « WorldEdit dans les parties d'essai ». */
	private static Rights rights(WorldKind kind, boolean operator, boolean creative, boolean invited) {
		return BuildAccess.rights(kind, operator, creative, invited, false);
	}

	@Test
	void dansUnAtelierLesOperateursEtLeursInvitesConstruisentAvecWorldEdit() {
		for (WorldKind k : new WorldKind[] {WorldKind.ATELIER_CARTE, WorldKind.ATELIER_STRUCTURES}) {
			assertEquals(new Rights(true, true, true), rights(k, true, false, false), "opérateur, même pas encore en créatif");
			assertEquals(new Rights(true, true, true), rights(k, false, false, true), "invité de cet atelier");
			assertEquals(Rights.NONE, rights(k, false, true, false), "joueur non invité, même en créatif");
		}
	}

	@Test
	void auHubEtEnPartiePersonneNeSeSertDeWorldEdit() {
		for (WorldKind k : new WorldKind[] {WorldKind.HUB, WorldKind.PARTIE, null}) {
			assertFalse(rights(k, true, true, false).worldEdit(), "opérateur en créatif : " + k);
			assertFalse(rights(k, false, true, true).worldEdit(), "une invitation ne vaut pas hors des ateliers : " + k);
			assertFalse(rights(k, true, true, false).workshop(), "pas d'atelier : " + k);
			// Comme avant : l'opérateur en mode créatif casse et pose, personne d'autre.
			assertTrue(rights(k, true, true, false).build(), "opérateur en créatif : " + k);
			assertFalse(rights(k, true, false, false).build(), "opérateur en aventure : " + k);
			assertFalse(rights(k, false, true, false).build(), "joueur en créatif : " + k);
		}
		assertTrue(WorldKind.HUB.openToAll() && WorldKind.PARTIE.openToAll());
	}

	@Test
	void lesPartiesDEssaiOuvrentWorldEditAuxOperateursSurDemande() {
		assertFalse(BuildAccess.rights(WorldKind.ESSAI, true, true, false, false).worldEdit(), "fermé par défaut");
		assertTrue(BuildAccess.rights(WorldKind.ESSAI, true, true, false, true).worldEdit(), "ouvert par le réglage");
		assertFalse(BuildAccess.rights(WorldKind.ESSAI, false, true, true, true).worldEdit(), "jamais pour un joueur, même invité");
		assertFalse(BuildAccess.rights(WorldKind.ESSAI, true, true, false, true).workshop(), "pas de mode créatif donné");
		assertTrue(BuildAccess.rights(WorldKind.ESSAI, true, true, false, false).build(), "l'opérateur en créatif construit");
	}

	@Test
	void seulsLesAteliersSontDesMondesOuLOnConstruit() {
		for (WorldKind k : WorldKind.values()) {
			assertEquals(k.workshop(), rights(k, true, false, false).workshop(), k.name());
			assertEquals(k.workshop(), !k.openToAll(), "un atelier n'est pas ouvert à tous : " + k);
		}
	}
}
