package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.Ability;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleDef;
import fr.valcombe.session.ScenarioDef;
import fr.valcombe.session.Session;
import fr.valcombe.session.Simulation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Atelier : enregistrement des rôles et scénarios, emplacements, partage en fichier .valcombe. */
class CreationStoreTest {
	@TempDir
	Path tmp;

	@AfterEach
	void reset() {
		Role.defineCustom(List.of());
	}

	private static Session session() {
		return new Session(new fr.valcombe.session.Host() {
			@Override
			public void announce(String text) {
			}

			@Override
			public void tell(String player, fr.valcombe.session.Msg msg) {
			}

			@Override
			public boolean online(String player) {
				return false;
			}

			@Override
			public void schedule(int ticks, Runnable r) {
			}

			@Override
			public void seatPlayers(List<String> players, String anchor) {
			}

			@Override
			public void nightEffects(List<String> players, boolean night, fr.valcombe.session.Settings settings) {
			}

			@Override
			public void endGame(List<String> players) {
			}
		});
	}

	private static RoleDef role(String id, String camp, String type, String other) {
		RoleDef d = new RoleDef();
		d.id = id;
		d.nom = "Le " + id;
		d.camp = camp;
		d.texte = "Un pouvoir.";
		d.embleme = "e_lune";
		d.autresNuits = other;
		d.pouvoir = new Ability();
		d.pouvoir.type = type;
		return d;
	}

	private static ScenarioDef scenario(String id, List<String> extra) {
		ScenarioDef s = new ScenarioDef();
		s.id = id;
		s.nom = "Scénario " + id;
		s.ouverture = "Il était une fois.";
		List<String> roles = new ArrayList<>();
		Role.classic().forEach(r -> roles.add(r.name().toLowerCase()));
		roles.addAll(extra);
		s.roles = roles;
		s.regles.annoncesInterdites = true;
		return s;
	}

	@Test
	void rolesEtEmplacements() throws Exception {
		Session ses = session();
		CreationStore store = new CreationStore(tmp, ses);
		store.load();
		store.saveRole(role("passeur", "CITADIN", "voisins", "fin"));
		store.saveRole(role("ogre", "DEMON", "mise_a_mort", "apres:goule"));
		RoleDef a = store.roles().stream().filter(d -> d.id.equals("passeur")).findFirst().orElseThrow();
		RoleDef b = store.roles().stream().filter(d -> d.id.equals("ogre")).findFirst().orElseThrow();
		assertNotEquals(a.emplacement, b.emplacement, "deux emplacements différents");
		assertNotNull(Role.parse("passeur"));
		assertTrue(Files.exists(tmp.resolve("valcombe/creations/roles/passeur.json")));
		// Modifier garde l'emplacement ; une fiche fautive est refusée
		RoleDef again = role("passeur", "CITADIN", "compte", "fin");
		again.pouvoir.nombre = 2;
		store.saveRole(again);
		assertEquals(a.emplacement, store.roles().stream().filter(d -> d.id.equals("passeur")).findFirst().orElseThrow().emplacement);
		assertThrows(GameException.class, () -> store.saveRole(role("tueur", "CITADIN", "mise_a_mort", "fin")));
		assertThrows(GameException.class, () -> store.saveRole(role("perdu", "CITADIN", "voisins", "avant:inconnu")));
		// Rechargement depuis le disque : mêmes emplacements
		Role.defineCustom(List.of());
		CreationStore reread = new CreationStore(tmp, ses);
		reread.load();
		assertEquals(Role.parse("passeur").name(), String.format("PERSO_%02d", a.emplacement));
	}

	@Test
	void scenarioJoueEtPartage() {
		Session ses = session();
		CreationStore store = new CreationStore(tmp, ses);
		store.load();
		store.saveRole(role("passeur", "CITADIN", "voisins", "fin"));
		ScenarioDef s = scenario("bal-masque", List.of("passeur"));
		store.saveScenario(s);
		assertEquals(2, ses.scenarios().size(), "le scénario de la table est offert, en plus");
		assertNull(store.scenarios().stream().filter(x -> x.id.equals("bal-masque")).findFirst().orElseThrow().problem());
		ScenarioDef table = store.scenarios().stream().filter(x -> x.id.equals("table")).findFirst().orElseThrow();
		assertNull(table.problem(), "le scénario de la table est jouable");
		assertEquals(26, table.script().size());
		assertTrue(table.regles.majoriteAbsolue && table.regles.victoireMalMajoritaire && table.reglages.conseil);
		// Choisir le scénario applique le script et les réglages
		ses.useScenario("bal-masque");
		assertTrue(ses.script().contains(Role.parse("passeur")));
		assertEquals("Scénario bal-masque", ses.scenario().nom);
		// Les règles spéciales s'appliquent en partie
		ses.addBots(7);
		ses.join("Alice");
		ses.start("Alice", Session.Mode.AUTO);
		assertThrows(GameException.class, () -> ses.claim("Alice", "greffier"), "annonces interdites");
		ses.stop();
		// Partage : export puis import dans un autre serveur
		String pack = store.exportPack(List.of(), List.of("bal-masque"), List.of());
		assertTrue(pack.contains("passeur"), "le scénario emporte ses rôles créés");
		Role.defineCustom(List.of());
		Path other = tmp.resolve("autre");
		Session ses2 = session();
		CreationStore store2 = new CreationStore(other, ses2);
		store2.load();
		String result = store2.importPack(pack);
		assertTrue(result.contains("passeur") || result.contains("Le passeur"), result);
		assertEquals(2, store2.scenarios().size());
		// Supprimé, le scénario de la table ne revient pas au prochain démarrage
		store.deleteScenario("table");
		CreationStore again = new CreationStore(tmp, session());
		again.load();
		assertTrue(again.scenarios().stream().noneMatch(x -> x.id.equals("table")));
		assertNotNull(Role.parse("passeur"));
		assertThrows(GameException.class, () -> store2.importPack("{\"format\":\"autre\"}"));
	}

	@Test
	void simulationDEquilibre() {
		Simulation.Result r = Simulation.run(Role.classic(), 8, 30, 1);
		assertNull(r.problem());
		assertEquals(30, r.good() + r.evil() + r.stuck());
		assertTrue(r.days() > 0);
		Simulation.Result bad = Simulation.run(java.util.EnumSet.of(Role.GOULE), 8, 10, 1);
		assertNotNull(bad.problem(), "script incomplet");
	}
}
