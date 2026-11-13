package fr.valcombe.mc.games;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Le vocal est séparé par partie : on n'entend jamais une autre partie, ni le hub depuis une partie, ni l'inverse ; le
 * Conteur n'entend que la sienne ; au hub et dans les ateliers, on s'entend comme avant.
 */
class VoiceZonesTest {
	private final UUID alba = UUID.randomUUID();
	private final UUID brune = UUID.randomUUID();
	private final UUID conteurA = UUID.randomUUID();
	private final UUID conteurB = UUID.randomUUID();
	private final UUID auHub = UUID.randomUUID();
	private final UUID enAtelier = UUID.randomUUID();

	private VoiceZones zones() {
		Map<UUID, String> m = new HashMap<>();
		m.put(alba, "partie-principale");
		m.put(conteurA, "partie-principale");
		m.put(brune, "essai-du-mardi");
		m.put(conteurB, "essai-du-mardi");
		m.put(auHub, VoiceZones.OUTSIDE);
		m.put(enAtelier, VoiceZones.OUTSIDE);
		return new VoiceZones(m);
	}

	@Test
	void uneMemePartieSEntend() {
		VoiceZones z = zones();
		assertTrue(z.mayHear(alba, conteurA));
		assertTrue(z.mayHear(conteurA, alba));
		assertTrue(z.mayHear(brune, conteurB));
	}

	@Test
	void jamaisUneAutrePartie() {
		VoiceZones z = zones();
		assertFalse(z.mayHear(alba, brune));
		assertFalse(z.mayHear(brune, alba));
		assertFalse(z.mayHear(conteurB, alba), "le Conteur de l'essai n'est pas entendu de la principale");
		assertFalse(z.mayHear(alba, conteurB), "le Conteur de l'essai n'entend que sa partie");
	}

	@Test
	void niLeHubDepuisUnePartieNiLInverse() {
		VoiceZones z = zones();
		assertFalse(z.mayHear(auHub, alba));
		assertFalse(z.mayHear(alba, auHub));
		assertFalse(z.mayHear(conteurA, auHub));
		assertTrue(z.mayHear(auHub, enAtelier), "hors des parties, le vocal reste celui de toujours");
	}

	@Test
	void unInconnuEstHorsDesParties() {
		VoiceZones z = zones();
		UUID nouveau = UUID.randomUUID();
		assertEquals(VoiceZones.OUTSIDE, z.zone(nouveau));
		assertTrue(z.mayHear(nouveau, auHub));
		assertFalse(z.mayHear(nouveau, alba), "tout juste arrivé, il n'entend pas une partie");
		assertTrue(z.mayHear(null, alba), "un son sans joueur (autre greffon) n'est pas arrêté");
		assertTrue(VoiceZones.NONE.mayHear(alba, brune), "avant le premier calcul, rien n'est séparé ni coupé");
	}

	@Test
	void lesZonesNeBougentPasSousLesYeuxDuVocal() {
		Map<UUID, String> m = new HashMap<>();
		m.put(alba, "partie-principale");
		VoiceZones z = new VoiceZones(m);
		m.put(alba, "essai-du-mardi"); // le fil du serveur prépare les zones suivantes : celles-ci ne changent pas
		assertEquals("partie-principale", z.zone(alba));
		assertEquals(1, z.size());
	}
}
