package fr.valcombe.mc.games;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Les équipes du tableau des scores sont propres à chaque partie : préfixe court tiré du monde, unique parmi les parties
 * ouvertes, noms qui tiennent dans la limite de Minecraft, et reconnus comme ceux de Valcombe (pour le ménage du
 * démarrage) sans jamais toucher aux équipes d'un autre greffon.
 */
class GameTeamsTest {
	@Test
	void lePrefixeVientDesInitialesDuMonde() {
		assertEquals("pp", GameTeams.key("partie-principale", List.of()));
		assertEquals("edm", GameTeams.key("essai-du-mardi", List.of()));
		assertEquals("eals", GameTeams.key("essai-avec-les-soeurs-de-saint-roch", List.of()), "quatre initiales au plus");
		assertEquals("e", GameTeams.key("essai", List.of()));
		assertEquals("p", GameTeams.key("", List.of()), "un monde sans lettres a tout de même un préfixe");
	}

	@Test
	void deuxPartiesNOntJamaisLeMemePrefixe() {
		List<String> taken = new ArrayList<>();
		Set<String> names = new HashSet<>();
		// Douze parties aux mêmes initiales, puis d'autres : chacune a son préfixe, et ses noms d'équipe.
		for (int i = 0; i < 12; i++) {
			String k = GameTeams.key("essai-du-mardi" + "abcdefghijkl".substring(0, i), taken);
			assertFalse(taken.contains(k), "préfixe déjà pris : " + k);
			taken.add(k);
			for (String n : GameTeams.names(k)) {
				assertTrue(names.add(n), "nom d'équipe en double : " + n);
			}
		}
		assertEquals("edm", taken.get(0));
		assertEquals("edm2", taken.get(1), "un chiffre départage deux parties aux mêmes initiales");
		assertNotEquals(GameTeams.key("partie-principale", List.of("pp")), "pp");
	}

	@Test
	void lesNomsTiennentDansLaLimiteDeMinecraft() {
		List<String> taken = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			String k = GameTeams.key("aaaa-bbbb-cccc-dddd-" + i, taken);
			taken.add(k);
			assertTrue(k.length() <= 5, "préfixe trop long : " + k);
			for (String n : GameTeams.names(k)) {
				assertTrue(n.length() <= GameTeams.MAX_NAME, "nom trop long : " + n);
				assertTrue(n.matches("[a-z0-9_]+"), "nom avec des caractères à éviter : " + n);
			}
		}
	}

	@Test
	void chaquePartieReconnaitSesEquipesEtPasCellesDesAutres() {
		String a = GameTeams.name("pp", GameTeams.Kind.VIVANTS);
		String b = GameTeams.name("edm", GameTeams.Kind.FANTOMES_VOTE);
		assertEquals("vcpp_viv", a);
		assertEquals("vcedm_fav", b);
		assertTrue(GameTeams.of(a, "pp"));
		assertFalse(GameTeams.of(a, "edm"), "les vivants de la principale ne sont pas ceux de l'essai");
		assertFalse(GameTeams.of(b, "pp"));
		assertFalse(GameTeams.of(GameTeams.name("pp2", GameTeams.Kind.CACHE), "pp"), "pp2 n'est pas pp");
		assertEquals(4, GameTeams.names("pp").size());
	}

	@Test
	void leMenageDuDemarrageNeVisQueLesEquipesDeValcombe() {
		for (String legacy : GameTeams.LEGACY) {
			assertTrue(GameTeams.ours(legacy), "équipe d'avant les parties multiples : " + legacy);
		}
		assertTrue(GameTeams.ours("vcedm2_cac"));
		assertFalse(GameTeams.ours("rouge"), "une équipe d'un autre greffon reste");
		assertFalse(GameTeams.ours("vc_viv"), "pas de préfixe : pas à nous");
		assertFalse(GameTeams.ours("vcpp_autre"));
		assertFalse(GameTeams.ours("vcbeaucouptroplong_viv"));
		assertFalse(GameTeams.ours(null));
	}
}
