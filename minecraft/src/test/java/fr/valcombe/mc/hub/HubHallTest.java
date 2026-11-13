package fr.valcombe.mc.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldManager;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/** Plan de la salle d'accueil du hub : portes, point d'arrivée, dalles ; et quels mondes chacun peut voir. */
class HubHallTest {
	private static BlockPos feet(Vec3 v) {
		return BlockPos.containing(v);
	}

	@Test
	void laSalleEstPoseeSurLeSolPlat() {
		assertEquals(WorldManager.SURFACE_Y, HubHall.FLOOR);
		assertEquals(WorldManager.SURFACE_Y + 1, HubHall.FEET);
		assertTrue(HubHall.CEILING > HubHall.WALL_TOP);
	}

	@Test
	void sixPortesDistinctesDansLesMursEstEtOuest() {
		assertEquals(6, HubHall.PORTALS.size());
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < HubHall.PORTALS.size(); i++) {
			HubHall.Portal p = HubHall.PORTALS.get(i);
			assertEquals(i, p.index(), "rangées dans l'ordre de leur numéro");
			assertTrue(seen.add(p.west() + ":" + p.z()), "deux portes au même endroit");
			assertEquals(p.west() ? -HubHall.HALF_X : HubHall.HALF_X, p.wallX());
			assertTrue(Math.abs(p.z()) + 2 < HubHall.HALF_Z, "la porte et son cadre tiennent dans le mur");
		}
		HubHall.Portal village = HubHall.PORTALS.get(0);
		assertTrue(village.west() && village.z() == 0, "le village actuel au milieu du mur ouest");
	}

	@Test
	void onEntreDansUnePorteParSonOuverture() {
		for (HubHall.Portal p : HubHall.PORTALS) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int y = HubHall.FEET; y <= HubHall.FEET + 3; y++) {
					assertEquals(p, HubHall.portalAt(new BlockPos(p.wallX(), y, p.z() + dz)), "ouverture de la porte " + p.index());
				}
			}
			assertNull(HubHall.portalAt(new BlockPos(p.wallX(), HubHall.FEET, p.z() + 2)), "le cadre n'est pas l'ouverture");
			assertNull(HubHall.portalAt(new BlockPos(p.wallX(), HubHall.FEET + 4, p.z())), "le linteau non plus");
			int inside = p.west() ? p.wallX() + 1 : p.wallX() - 1;
			assertNull(HubHall.portalAt(new BlockPos(inside, HubHall.FEET, p.z())), "devant la porte, on n'y est pas encore");
		}
	}

	@Test
	void onRevientDevantLaPorteEtPasDedans() {
		for (HubHall.Portal p : HubHall.PORTALS) {
			BlockPos front = feet(p.front());
			assertNull(HubHall.portalAt(front), "revenir dans la porte renverrait aussitôt de l'autre côté");
			assertTrue(HubHall.inside(front), "devant la porte, dans la salle");
			assertEquals(HubHall.FEET, front.getY());
			// Dos à la porte : ouest regarde vers l'est (lacet -90), est vers l'ouest (lacet 90).
			assertEquals(p.west() ? -90f : 90f, p.frontYaw());
			Vec3 label = p.label();
			assertTrue(Math.abs(label.x) < HubHall.HALF_X && Math.abs(label.x) > HubHall.HALF_X - 1.2,
				"le nom du monde est plaqué contre le mur, côté salle");
		}
	}

	@Test
	void arriveeDansLaSalleHorsDesDallesEtDesPortes() {
		BlockPos a = feet(HubHall.ARRIVAL);
		assertTrue(HubHall.inside(a));
		assertNull(HubHall.portalAt(a));
		assertTrue(Math.abs(a.getX() - HubHall.JOIN_X) > 1 || Math.abs(a.getZ() - HubHall.JOIN_Z) > 1,
			"on n'arrive pas sur la dalle dorée (ce serait s'inscrire)");
		assertEquals(180f, HubHall.ARRIVAL_YAW, "face au mur des rôles, au nord");
		assertTrue(HubHall.ROLE_WALL_Z < a.getZ());
	}

	@Test
	void dallesDansLaSalleEtSeparees() {
		for (int x : new int[] {HubHall.JOIN_X, HubHall.LEAVE_X, HubHall.STORYTELLER_X}) {
			assertTrue(HubHall.inside(new BlockPos(x, HubHall.FEET, 0)));
		}
		assertTrue(HubHall.LEAVE_X < HubHall.JOIN_X - 2 && HubHall.STORYTELLER_X > HubHall.JOIN_X + 2,
			"la dalle dorée fait 3 × 3 : les autres ne la touchent pas");
		assertFalse(HubHall.inside(new BlockPos(0, HubHall.FEET, HubHall.HALF_Z)), "l'entrée est dans le mur sud");
		assertFalse(HubHall.inside(new BlockPos(0, HubHall.FLOOR, 0)), "le sol n'est pas l'intérieur");
	}

	@Test
	void mondesOuvertsATous() {
		assertTrue(WorldKind.HUB.openToAll());
		assertTrue(WorldKind.PARTIE.openToAll());
		assertTrue(WorldKind.ESSAI.openToAll());
		assertFalse(WorldKind.ATELIER_CARTE.openToAll(), "les ateliers : opérateurs et leurs invités seulement");
		assertFalse(WorldKind.ATELIER_STRUCTURES.openToAll());
		assertNotNull(Travel.MAIN_NAME);
		assertEquals("principal", Travel.MAIN);
	}
}
