package fr.valcombe.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Le bandeau « autre version du mod » : jamais en solo ni sans vue du serveur, toujours face à un vrai écart. */
class VersionCheckTest {
	@Test
	void jamaisEnSolo() {
		// Serveur intégré : même mod, même version, quoi que dise la vue (fabriquée sans version, ou ancienne).
		assertNull(VersionCheck.mismatch(true, null, "0.13.1", true));
		assertNull(VersionCheck.mismatch(true, "0.12.0", "0.13.1", true));
	}

	@Test
	void rienAvantLaPremiereVueDuServeur() {
		// Aucune vue reçue de ce serveur : la vue en mémoire vient d'ailleurs (connexion précédente, client lui-même).
		assertNull(VersionCheck.mismatch(false, null, "0.13.1", false));
		assertNull(VersionCheck.mismatch(false, "0.12.0", "0.13.1", false));
	}

	@Test
	void memeVersion() {
		assertNull(VersionCheck.mismatch(true, "0.13.1", "0.13.1", false));
		assertNull(VersionCheck.mismatch(true, " 0.13.1 ", "0.13.1", false), "espaces autour");
		assertNull(VersionCheck.mismatch(true, "0.13.1+dev", "0.13.1", false), "métadonnées de construction");
		assertNull(VersionCheck.mismatch(true, "?", "0.13.1", false), "version illisible du serveur");
	}

	@Test
	void unVraiServeurDUneAutreVersion() {
		assertEquals("0.12.4", VersionCheck.mismatch(true, "0.12.4", "0.13.1", false));
		assertEquals("0.14.0", VersionCheck.mismatch(true, "0.14.0", "0.13.1", false));
		// Serveur d'avant la 0.5.3 : il n'envoie pas sa version.
		assertEquals("plus ancienne", VersionCheck.mismatch(true, null, "0.13.1", false));
	}
}
