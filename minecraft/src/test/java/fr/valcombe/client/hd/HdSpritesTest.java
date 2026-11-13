package fr.valcombe.client.hd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Les textures de la boîte à outils existent, avec les dimensions et les bords que le code leur prête. */
class HdSpritesTest {
	private static final Path ASSETS = Path.of("src/client/resources/assets/valcombe");

	/** Largeur et hauteur lues dans l'en-tête PNG. */
	private static int[] pngSize(Path file) throws IOException {
		try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
			in.skipNBytes(16);
			return new int[] {in.readInt(), in.readInt()};
		}
	}

	@Test
	void texturesConformes() throws IOException {
		for (HdSprites.Sprite sp : HdSprites.ALL) {
			Path file = ASSETS.resolve(sp.path());
			assertTrue(Files.exists(file), "texture " + file);
			int[] size = pngSize(file);
			assertEquals(sp.width(), size[0], sp.name() + " : largeur");
			assertEquals(sp.height(), size[1], sp.name() + " : hauteur");
			assertTrue(sp.left() + sp.right() <= sp.width() && sp.top() + sp.bottom() <= sp.height(), sp.name() + " : bords");
			// Dimensions paires : la chaîne de mipmaps descend d'au moins trois niveaux.
			assertTrue(sp.width() % 8 == 0 && sp.height() % 8 == 0, sp.name() + " : dimensions multiples de 8");
		}
	}

	@Test
	void unitesDInterface() {
		assertEquals(10f, HdSprites.CADRE.leftUnits());
		assertEquals(64f, HdSprites.PARCHEMIN.widthUnits(), "carreau de parchemin de 64 unités");
		assertEquals(16f, HdSprites.BANDEAU.heightUnits());
		assertEquals(12f, HdSprites.BANDEAU.leftUnits());
		assertEquals(8f, HdSprites.BANDEAU.topUnits());
	}
}
