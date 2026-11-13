package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.GameException;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/** « Envoyer à la partie » : ce qui se vérifie sans lancer Minecraft (mondes visés, contrôles, messages). */
class MapSenderTest {

	@Test
	void uneCarteNeSEnvoieQuAUnMondeDePartie() {
		assertTrue(MapSender.accepts(WorldKind.PARTIE));
		assertTrue(MapSender.accepts(WorldKind.ESSAI), "les parties d'essai reçoivent aussi leur carte");
		assertFalse(MapSender.accepts(WorldKind.HUB), "le hub garde sa salle d'accueil");
		assertFalse(MapSender.accepts(WorldKind.ATELIER_CARTE), "dans un atelier, une carte se pose");
		assertFalse(MapSender.accepts(WorldKind.ATELIER_STRUCTURES));
	}

	@Test
	void leVillageDemandeEstControleAvantQueRienNeBouge() {
		MapSender.Generated g = MapSender.village(8, 0, " Monastere ", false);
		assertEquals(new MapSender.Generated("monastere", 8, 0, false), g);
		assertEquals("gothique", MapSender.village(16, 1, "", true).theme(), "sans thème : gothique");
		assertEquals("gothique", MapSender.village(16, 1, null, true).theme());
		assertThrows(GameException.class, () -> MapSender.village(Village.MIN_SEATS - 1, 1, "port", true));
		assertThrows(GameException.class, () -> MapSender.village(Village.MAX_SEATS + 1, 1, "port", true));
		assertThrows(GameException.class, () -> MapSender.village(10, 3, "port", true));
		assertThrows(GameException.class, () -> MapSender.village(10, -1, "port", true));
		GameException e = assertThrows(GameException.class, () -> MapSender.village(10, 1, "château", true));
		assertTrue(e.getMessage().contains("gothique, monastere, port"), e.getMessage());
	}

	@Test
	void laCarteEstPoseeAuCentreDuSolPlat() {
		// Là où l'on arrive en entrant dans un monde de Valcombe : on marche sur la place.
		assertEquals(new BlockPos(0, WorldManager.SURFACE_Y, 0), MapSender.center());
		assertEquals(WorldManager.SURFACE_Y + 1, MapSender.center().above().getY());
	}

	@Test
	void cequOnEnvoieSeDitEnClair() {
		assertEquals("un village généré (8 trônes, thème port)", new MapSender.Generated("port", 8, 1, true).label());
		assertEquals("une carte vide", new MapSender.Blank().label());
		assertEquals("un monde vide (sans carte)", new MapSender.Nothing().label());
		MapStore.MapDef d = new MapStore.MapDef();
		d.id = "port";
		d.nom = "Le Port aux Brumes";
		assertEquals("la carte « Le Port aux Brumes »", new MapSender.Library(d, null).label());
		assertEquals("la construction « tour.schem »", new MapSender.Imported("tour.schem", null).label());
	}

	@Test
	void leBilanDeLaCartePosee() {
		Village v = Village.custom(new BlockPos(0, 64, 0));
		assertEquals("0 trône, 0 maison.", MapSender.counts(v));
		v.addSeat(new BlockPos(5, 65, 0));
		v.addSeat(new BlockPos(-5, 65, 0));
		v.addHouse(new BlockPos(20, 65, 0));
		assertEquals("2 trônes, 1 maison.", MapSender.counts(v));
		v.setConvocation(new BlockPos(0, 65, 30));
		assertEquals("2 trônes, 1 maison, salle de convocation.", MapSender.counts(v));
		Village g = Village.generated(MapSender.center(), "gothique", 12, 1, true);
		assertEquals("12 trônes, 12 maisons.", MapSender.counts(g));
	}

	@Test
	void lesMarquesDuMondeSontDistinctesDesFichesDeCarte() {
		// Le démarrage lit ces fichiers dans le dossier du monde de la partie (voir McHost.bindWorld) : aucun ne doit
		// se confondre avec la fiche du village ou la carte posée.
		assertNotEquals(Village.FILE, MapSender.EMPTY);
		assertNotEquals(Village.FILE, MapSender.MARK);
		assertNotEquals(MapPlacer.FILE, MapSender.EMPTY);
		assertNotEquals(MapSender.EMPTY, MapSender.MARK);
	}
}
