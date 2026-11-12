package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/** La carte retient ce que l'éditeur a bâti, pour tout raser avant d'en bâtir une autre. */
class VillageBuiltTest {

	@Test
	void laZoneBatieSAgrandit() {
		Village v = Village.generated(new BlockPos(0, 64, 0));
		assertNull(v.built(), "rien de bâti hors des repères");
		v.include(10, 64, 10, 20, 70, 12);
		v.include(-150, 60, 5, -140, 90, 8);
		assertArrayEquals(new int[] {-150, 60, 5, 20, 90, 12}, v.built());
	}

	@Test
	void choixDuRasage() {
		assertEquals(McHost.Raze.CARRE, McHost.Raze.parse("carre"));
		assertEquals(McHost.Raze.CARRE, McHost.Raze.parse("Carré"));
		assertEquals(McHost.Raze.BATI, McHost.Raze.parse("bati"));
		assertEquals(McHost.Raze.BATI, McHost.Raze.parse(null));
	}
}
