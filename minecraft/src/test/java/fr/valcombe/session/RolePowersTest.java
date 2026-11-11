package fr.valcombe.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.Ability;
import fr.valcombe.rules.Builtins;
import fr.valcombe.rules.Game;
import fr.valcombe.rules.Info;
import fr.valcombe.rules.Rng;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleDef;
import fr.valcombe.rules.RoleType;
import fr.valcombe.rules.Setup;
import fr.valcombe.rules.Step;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Rôles créés : effets multiples, usage unique, pouvoirs de jour, héritier, maître, faux-semblant. */
class RolePowersTest {
	private final List<RoleDef> defs = new ArrayList<>();

	@AfterEach
	void reset() {
		Role.defineCustom(List.of());
	}

	private RoleDef add(String id, RoleType camp, String type, String first, String other) {
		RoleDef d = CustomRolesTest.def(id, camp, type, first, other);
		d.emplacement = defs.size() + 1;
		defs.add(d);
		return d;
	}

	/** Partie à sept : les rôles donnés d'abord, puis des Citadins sans pouvoir de nuit gênant. */
	private Game game(Role... first) {
		Role.defineCustom(defs);
		List<Role> roles = new ArrayList<>(List.of(first));
		for (Role filler : List.of(Role.PELERINE, Role.TEMPLIER, Role.ARBALETRIER, Role.COMMERE, Role.GREFFIER, Role.INQUISITEUR)) {
			if (roles.size() < 7 && !roles.contains(filler)) {
				roles.add(filler);
			}
		}
		Game g = new Game(List.of("A", "B", "C", "D", "E", "F", "G"), Rng.of(new Random(1)));
		g.assign(roles.stream().map(r -> new Setup.Entry(r, r)).toList(), null, -1);
		return g;
	}

	private static Role role(String id) {
		Role r = Role.parse(id);
		assertNotNull(r, id);
		return r;
	}

	/** Joue une nuit : `picks` donne les cibles de certains sièges ; les autres désignent les derniers sièges vivants. */
	private static Map<Integer, Info> night(Game g, Map<Integer, List<Integer>> picks) {
		Map<Integer, Info> infos = new HashMap<>();
		g.startNight();
		Step step;
		while ((step = g.nextStep()) != null) {
			List<Integer> targets = picks.get(step.seat);
			if (targets == null) {
				targets = new ArrayList<>();
				for (int s = g.size() - 1; s >= 0 && targets.size() < step.choose(); s--) {
					if (s != step.seat && g.player(s).alive()) {
						targets.add(s);
					}
				}
			}
			infos.put(step.seat, g.resolve(step, targets));
		}
		g.endNight();
		return infos;
	}

	@Test
	void tirDeJourUneSeuleFois() {
		add("veneur", RoleType.CITADIN, "tir", "jamais", "jamais").pouvoir.cible = "sbire";
		Game g = game(Role.COMMERE, Role.GOULE, Role.HERITIERE);
		// Le veneur est le rôle créé : on le donne au premier siège.
		g.reassign(0, role("veneur"), null);
		night(g, Map.of());
		assertFalse(g.slayerShot(3, 2), "un autre joueur bluffe : rien ne se passe");
		assertTrue(g.slayerShot(0, 2), "le Sbire visé meurt");
		assertFalse(g.player(2).alive());
		assertFalse(g.slayerShot(0, 1), "le tir ne sert qu'une fois");
		assertTrue(g.player(1).alive());
	}

	@Test
	void represaillesALaPremiereAccusation() {
		add("gardien", RoleType.CITADIN, "represailles", "jamais", "jamais").pouvoir.cible = "citadin";
		Game g = game(Role.COMMERE, Role.GOULE, Role.HERITIERE);
		g.reassign(0, role("gardien"), null);
		night(g, Map.of());
		Game.Nomination nom = g.nominate(3, 0);
		assertTrue(nom.pelerine(), "l'accusateur est exécuté sur-le-champ");
		assertFalse(g.player(3).alive());
		assertTrue(g.dayOver());
	}

	@Test
	void pouvoirAUsageUniqueQueLOnPeutGarder() {
		RoleDef d = add("apothicaire", RoleType.CITADIN, "protection", "jamais", "avant:goule");
		d.pouvoir.uneFois = true;
		d.pouvoir.soiAutorise = true;
		assertNull(d.problem(), d.problem());
		Game g = game(Role.COMMERE, Role.GOULE, Role.HERITIERE);
		g.reassign(0, role("apothicaire"), null);
		night(g, Map.of());
		g.endDay();
		// Nuit 2 : il garde son pouvoir (aucune cible) ; la Goule tue le siège 6.
		night(g, Map.of(0, List.of(), 1, List.of(6)));
		assertFalse(g.onceUsed(0));
		assertFalse(g.player(6).alive());
		g.endDay();
		// Nuit 3 : il protège le siège 5, que la Goule attaque.
		night(g, Map.of(0, List.of(5), 1, List.of(5)));
		assertTrue(g.onceUsed(0));
		assertTrue(g.player(5).alive(), "le joueur protégé survit");
		g.endDay();
		// Nuit 4 : son pouvoir est usé, il ne se réveille plus.
		g.startNight();
		Step step;
		while ((step = g.nextStep()) != null) {
			assertTrue(step.seat != 0, "l'apothicaire ne se réveille plus");
			g.resolve(step, step.choose() > 0 ? List.of(4) : List.of());
		}
	}

	@Test
	void deuxEffetsSurLesMemesJoueurs() {
		RoleDef d = add("geolier", RoleType.CITADIN, "compte", "jamais", "avant:goule");
		d.pouvoir.nombre = 2;
		Ability protect = new Ability();
		protect.type = "protection";
		d.pouvoir.aussi.add(protect);
		assertNull(d.problem(), d.problem());
		Game g = game(Role.COMMERE, Role.GOULE, Role.HERITIERE);
		g.reassign(0, role("geolier"), null);
		night(g, Map.of());
		g.endDay();
		// Il désigne le siège 4 (protégé, car premier) et la Goule : un mauvais parmi les deux.
		Map<Integer, Info> infos = night(g, Map.of(0, List.of(4, 1), 1, List.of(4)));
		assertTrue(infos.get(0).text.contains("1"), infos.get(0).text);
		assertTrue(g.player(4).alive(), "le premier joueur désigné est protégé");
	}

	@Test
	void heritierMaitreEtFauxSemblant() {
		add("dauphin", RoleType.SBIRE, "heritier", "jamais", "jamais");
		add("valet", RoleType.ETRANGER, "maitre", "fin", "fin");
		add("masque", RoleType.SBIRE, "semblant", "jamais", "jamais");
		Game g = game(Role.COMMERE, Role.GOULE, Role.HERITIERE, Role.MARTYR, Role.EXCOMMUNIE);
		g.reassign(2, role("dauphin"), null);
		g.reassign(3, role("valet"), null);
		g.reassign(4, role("masque"), null);
		night(g, Map.of(3, List.of(5)));
		assertTrue(g.isMaster(5), "le valet a choisi son maître");
		assertTrue(g.canMisregister(4), "le masque peut passer pour un autre");
		// Le valet vote sans son maître : le Conteur est prévenu.
		Game.Nomination nom = g.nominate(0, 1);
		Game.VoteResult r = g.vote(nom.index(), List.of(0, 3, 6, 2));
		assertTrue(r.warnings().stream().anyMatch(w -> w.contains("sans son maître")), r.warnings().toString());
		// Le Démon est exécuté à sept vivants : le dauphin lui succède, la partie continue.
		g.endDay();
		assertFalse(g.player(1).alive());
		assertNull(g.winner(), "la partie continue");
		assertEquals(RoleType.DEMON, g.player(2).role().type, "le dauphin est devenu le Démon");
	}

	@Test
	void fichesRefuseesEtReveilSurUnRoleCree() {
		RoleDef info = add("curieux", RoleType.CITADIN, "voisins", "fin", "fin");
		info.pouvoir.uneFois = true;
		assertNotNull(info.problem(), "usage unique réservé aux pouvoirs avec désignation");
		RoleDef order = add("mal-range", RoleType.CITADIN, "voisins", "fin", "fin");
		Ability pick = new Ability();
		pick.type = "decouvrir";
		order.pouvoir.aussi.add(pick);
		assertNotNull(order.problem(), "l'effet qui désigne doit venir en premier");
		RoleDef prince = add("prince", RoleType.CITADIN, "heritier", "jamais", "jamais");
		assertNotNull(prince.problem(), "seul un Sbire hérite");
		defs.clear();
		// Un rôle créé peut se réveiller juste après un autre rôle créé.
		add("premier", RoleType.CITADIN, "voisins", "apres:veilleuse", "apres:veilleuse");
		add("second", RoleType.CITADIN, "voisins", "apres:premier", "apres:premier");
		Role.defineCustom(defs);
		List<Role> first = Role.firstNightOrder();
		assertEquals(first.indexOf(Role.VEILLEUSE) + 1, first.indexOf(role("premier")));
		assertEquals(first.indexOf(role("premier")) + 1, first.indexOf(role("second")));
	}

	@Test
	void rolesIntegresDecritsDansLeFormatDesRolesCrees() {
		List<RoleDef> templates = Builtins.templates();
		assertTrue(templates.size() >= 20, "la plupart des rôles intégrés se copient : " + templates.size());
		for (RoleDef d : templates) {
			assertNull(d.problem(), d.id + " : " + d.problem());
		}
	}
}
