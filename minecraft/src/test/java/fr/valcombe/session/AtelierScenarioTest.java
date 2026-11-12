package fr.valcombe.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;
import fr.valcombe.rules.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Scénario lié à une carte : choix, partie d'essai et suppression, vus de la session (page web et écran en jeu). */
class AtelierScenarioTest {
	/** Un serveur dont la carte se charge en plusieurs ticks, comme le vrai. */
	static class MapHost extends SessionTest.FakeHost {
		final List<String> loaded = new ArrayList<>();
		String inPlace = "";
		boolean busy;

		@Override
		public String loadMap(String id) {
			loaded.add(id);
			busy = true; // le chantier commence : la carte ne sera en place qu'à la fin
			return "Chargement de la carte lancé.";
		}

		@Override
		public String mapInPlace() {
			return inPlace;
		}

		@Override
		public boolean busy() {
			return busy;
		}

		/** Le chantier se termine : la carte demandée est en place. */
		void built() {
			busy = false;
			inPlace = loaded.get(loaded.size() - 1);
		}

		/** Fait passer une seconde : la tâche programmée la plus ancienne s'exécute. */
		void second() {
			tasks.poll().run();
		}
	}

	private static ScenarioDef scenario(String map) {
		ScenarioDef d = new ScenarioDef();
		d.id = "bal";
		d.nom = "Le Bal";
		d.carte = map;
		for (Role r : Role.classic()) {
			d.roles.add(r.name().toLowerCase());
		}
		return d;
	}

	private static Session session(MapHost h, ScenarioDef d) {
		Session s = new Session(h, Rng.of(new Random(3)));
		s.setScenarios(List.of(d));
		return s;
	}

	@Test
	void choisirDeuxFoisLeMemeScenarioNeRechargePasLaCarte() {
		MapHost h = new MapHost();
		Session s = session(h, scenario("port"));
		assertFalse(s.useScenario("bal").isEmpty(), "la carte du scénario est chargée la première fois");
		h.built();
		assertEquals("", s.useScenario("bal"), "déjà en place et intacte : rien à recharger");
		assertEquals(List.of("port"), h.loaded);
		// Retouchée à la main depuis, elle n'est plus « en place » : le scénario la recharge (et l'hôte la sauvegarde d'abord).
		h.inPlace = "";
		s.useScenario("bal");
		assertEquals(List.of("port", "port"), h.loaded);
	}

	@Test
	void partieDEssaiAttendLaFinDuChargementDeLaCarte() {
		MapHost h = new MapHost();
		Session s = session(h, scenario("port"));
		String msg = s.trial("Hote", "bal", 7);
		assertTrue(msg.contains("démarrera d'elle-même"), msg);
		assertNull(s.game(), "pas de partie tant que la carte est en chantier");
		assertTrue(s.trialPending());
		assertEquals(7, s.lobby().size(), "le salon est prêt : l'hôte et six joueurs fictifs");
		h.second();
		assertNull(s.game(), "toujours en chantier une seconde plus tard");
		h.built();
		h.second();
		assertNotNull(s.game(), "la partie d'essai démarre d'elle-même à la fin du chantier");
		assertEquals(7, s.game().size());
		assertFalse(s.trialPending());
		assertEquals(List.of("port"), h.loaded, "la carte n'a été chargée qu'une fois");
	}

	@Test
	void partieDEssaiSansChantierDemarreToutDeSuite() {
		MapHost h = new MapHost();
		h.inPlace = "port";
		Session s = session(h, scenario("port"));
		String msg = s.trial("Hote", "bal", 8);
		assertTrue(msg.startsWith("Partie d'essai lancée (8 joueurs"), msg);
		assertTrue(h.loaded.isEmpty(), "la carte du scénario est déjà en place");
	}

	@Test
	void partieDEssaiHorsBornesRefuseeSansToucherAuSalon() {
		MapHost h = new MapHost();
		Session s = session(h, scenario(""));
		s.addBots(3);
		assertThrows(GameException.class, () -> s.trial("Hote", "bal", 17));
		assertThrows(GameException.class, () -> s.trial("Hote", "bal", 4));
		assertEquals(3, s.lobby().size(), "le salon n'a pas bougé");
		assertNull(s.scenario(), "et le scénario n'a pas été choisi");
	}

	@Test
	void partieLanceeALaMainPendantLeChantierLEssaiNeLaRelancePas() {
		MapHost h = new MapHost();
		Session s = session(h, scenario("port"));
		s.trial("Hote", "bal", 7);
		h.built();
		s.start("Hote", Session.Mode.AUTO);
		var game = s.game();
		h.second();
		assertTrue(s.game() == game, "la partie lancée à la main n'est pas remplacée");
		assertFalse(s.trialPending());
	}

	@Test
	void scenarioChoisiPuisSupprimeLaProchainePartieRedevientLibre() {
		MapHost h = new MapHost();
		ScenarioDef d = scenario("");
		Session s = session(h, d);
		s.useScenario("bal");
		assertNotNull(s.scenario());
		s.setScenarios(List.of());
		s.scenarioDeleted("bal");
		assertNull(s.scenario(), "plus de scénario fantôme pour la prochaine partie");
		assertFalse(s.view("Hote", true).containsKey("scenario"));
	}

	@Test
	void laVueDitQuelleCarteChargeUnScenario() {
		MapHost h = new MapHost();
		h.inPlace = "village";
		Session s = session(h, scenario("port"));
		var view = s.view("Hote", true);
		assertEquals("village", view.get("mapInPlace"));
		@SuppressWarnings("unchecked")
		List<java.util.Map<String, Object>> list = (List<java.util.Map<String, Object>>) view.get("scenarios");
		assertEquals("port", list.get(0).get("carte"), "l'écran peut prévenir avant de charger une autre carte");
	}
}
