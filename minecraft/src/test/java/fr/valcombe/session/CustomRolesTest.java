package fr.valcombe.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.Ability;
import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleDef;
import fr.valcombe.rules.RoleType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Rôles créés dans l'Atelier : chaque modèle de pouvoir, joué dans des parties complètes. */
class CustomRolesTest {
	private static int slot;

	static RoleDef def(String id, RoleType camp, String type, String first, String other) {
		RoleDef d = new RoleDef();
		d.id = id;
		d.nom = "Le " + id;
		d.camp = camp.name();
		d.texte = "Pouvoir de test.";
		d.premiereNuit = first;
		d.autresNuits = other;
		d.pouvoir = new Ability();
		d.pouvoir.type = type;
		d.emplacement = ++slot;
		return d;
	}

	static List<RoleDef> library() {
		slot = 0;
		List<RoleDef> l = new ArrayList<>();
		l.add(def("veilleur", RoleType.CITADIN, "voisins", "apres:veilleuse", "apres:veilleuse"));
		RoleDef guet = def("guetteur", RoleType.CITADIN, "detecteur", "debut", "fin");
		guet.pouvoir.cible = "demon";
		guet.pouvoir.nombre = 2;
		guet.pouvoir.faussePiste = true;
		l.add(guet);
		l.add(def("medecin", RoleType.CITADIN, "protection", "jamais", "avant:goule"));
		l.add(def("devin", RoleType.CITADIN, "decouvrir", "jamais", "fin"));
		l.add(def("paysan", RoleType.CITADIN, "aucun", "jamais", "jamais"));
		l.add(def("fossoyeur", RoleType.CITADIN, "execute", "jamais", "fin"));
		l.add(def("chevalier", RoleType.CITADIN, "immunite", "jamais", "jamais"));
		RoleDef voyant = def("voyant", RoleType.CITADIN, "libre", "fin", "fin");
		voyant.pouvoir.question = "Dis-lui si ce joueur a menti aujourd'hui.";
		l.add(voyant);
		l.add(def("pisteur", RoleType.CITADIN, "distance", "fin", "jamais"));
		l.add(def("comptable", RoleType.CITADIN, "adjacents", "fin", "jamais"));
		RoleDef paire = def("delateur", RoleType.CITADIN, "paire", "debut", "jamais");
		paire.pouvoir.cible = "sbire";
		l.add(paire);
		RoleDef compte = def("compteur", RoleType.CITADIN, "compte", "jamais", "fin");
		compte.pouvoir.nombre = 3;
		l.add(compte);
		l.add(def("saint", RoleType.ETRANGER, "martyr", "jamais", "jamais"));
		l.add(def("herboriste", RoleType.SBIRE, "poison", "debut", "debut"));
		l.add(def("illusionniste", RoleType.SBIRE, "fausse_info", "debut", "debut"));
		RoleDef baron = def("baron-noir", RoleType.SBIRE, "etrangers", "jamais", "jamais");
		baron.pouvoir.etrangers = 1;
		l.add(baron);
		l.add(def("ogre", RoleType.DEMON, "mise_a_mort", "jamais", "apres:goule"));
		return l;
	}

	@AfterEach
	void reset() {
		Role.defineCustom(List.of());
	}

	@Test
	void fichesVerifiees() {
		for (RoleDef d : library()) {
			assertNull(d.problem(), d.id + " : " + d.problem());
		}
		RoleDef bad = def("tueur", RoleType.CITADIN, "mise_a_mort", "jamais", "fin");
		assertNotNull(bad.problem(), "un Citadin ne tue pas");
		RoleDef demon = def("demon-mou", RoleType.DEMON, "voisins", "fin", "fin");
		assertNotNull(demon.problem(), "un Démon doit tuer");
		RoleDef sleepy = def("dormeur", RoleType.CITADIN, "voisins", "jamais", "jamais");
		assertNotNull(sleepy.problem(), "un pouvoir de nuit doit se réveiller");
		RoleDef builtin = def("goule", RoleType.DEMON, "mise_a_mort", "jamais", "fin");
		assertNotNull(builtin.problem(), "identifiant d'un rôle intégré");
	}

	@Test
	void reveilsIntercales() {
		Role.defineCustom(library());
		Role veilleur = Role.parse("veilleur");
		assertNotNull(veilleur);
		assertTrue(veilleur.custom());
		List<Role> first = Role.firstNightOrder();
		assertEquals(first.indexOf(Role.VEILLEUSE) + 1, first.indexOf(veilleur), "réveil juste après la Veilleuse");
		List<Role> other = Role.otherNightsOrder();
		assertTrue(other.indexOf(Role.parse("medecin")) < other.indexOf(Role.GOULE), "le médecin protège avant la Goule");
		assertTrue(other.indexOf(Role.parse("ogre")) > other.indexOf(Role.GOULE));
		assertEquals(25 + library().size(), Role.all().size());
		Role.defineCustom(List.of());
		assertNull(Role.parse("veilleur"), "emplacement vidé");
	}

	@Test
	void partiesAvecRolesCrees() {
		Role.defineCustom(library());
		Set<Role> script = EnumSet.copyOf(Role.classic());
		for (RoleDef d : library()) {
			script.add(Role.parse(d.id));
		}
		play(script, 40);
	}

	@Test
	void partiesAvecUnDemonCree() {
		Role.defineCustom(library());
		Set<Role> script = EnumSet.copyOf(Role.classic());
		script.remove(Role.GOULE);
		script.add(Role.parse("ogre"));
		script.add(Role.parse("medecin"));
		script.add(Role.parse("baron-noir"));
		List<String> logs = play(script, 30);
		assertTrue(logs.stream().anyMatch(l -> l.contains("tué par le ogre")), "l'Ogre tue la nuit");
	}

	/** Joue des parties de joueurs fictifs (Conteur automatique) et renvoie leurs chroniques. */
	private static List<String> play(Set<Role> script, int games) {
		List<String> logs = new ArrayList<>();
		for (int seed = 0; seed < games; seed++) {
			Random random = new Random(seed);
			SessionTest.FakeHost h = new SessionTest.FakeHost();
			Session s = new Session(h, Rng.of(random));
			s.useScript("Essai", script);
			s.addBots(7 + seed % 9);
			s.start("Conteur", Session.Mode.AUTO);
			for (int day = 0; day < 300 && s.game() != null; day++) {
				s.startNight();
				while (!h.tasks.isEmpty()) {
					h.tasks.poll().run();
				}
				if (s.game() == null) {
					break;
				}
				Game g = s.game();
				List<Integer> alive = g.aliveSeats();
				try {
					SessionTest.openNominations(s);
					s.nominate(g.player(alive.get(random.nextInt(alive.size()))).name,
						g.player(alive.get(random.nextInt(alive.size()))).name);
				} catch (GameException ignored) {
					// nomination refusée : normal
				}
				while (!h.tasks.isEmpty()) {
					h.tasks.poll().run();
				}
				if (s.game() != null && !s.game().dayOver()) {
					s.endDay();
				}
			}
			assertNull(s.game(), "la partie doit se terminer (graine " + seed + ")");
			assertNotNull(s.lastGame().winner());
			logs.addAll(s.lastGame().log());
		}
		return logs;
	}
}
