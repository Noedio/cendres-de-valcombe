package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/** Recoins du village : une place sur quatre entre deux maisons, hors de la place. */
class VillageNookTest {

	@Test
	void unRecoinSurQuatreEntreLesMaisons() {
		Village v = Village.generated(new BlockPos(100, 64, -40), "gothique", 16, 1, true);
		List<BlockPos> nooks = v.nooks();
		assertEquals(4, nooks.size(), "16 trônes : 4 recoins");
		for (BlockPos n : nooks) {
			double d = Math.hypot(n.getX() - 100, n.getZ() + 40);
			assertTrue(d > v.farthest() / 4.0, "le recoin est hors de la place");
			assertEquals(65, n.getY(), "pieds au-dessus du sol du village");
		}
		assertEquals(1, Village.generated(BlockPos.ZERO, "port", 5, 0, false).nooks().size(), "5 trônes : 1 recoin");
	}
}
