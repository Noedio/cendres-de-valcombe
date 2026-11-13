package fr.valcombe.client.hd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * L'échelle des polices de la boîte à outils : choix de la taille déclarée, et accord entre FontLadder et les
 * définitions produites par tools/make_ui.py (une par face et par taille, décalage qui centre les capitales).
 */
class FontLadderTest {
	private static final Path FONTS = Path.of("src/client/resources/assets/valcombe/font/hd");

	@Test
	void choisitLaTailleLaPlusProche() {
		assertEquals(22, FontLadder.pick(FontLadder.Face.TEXTE, 22), "taille exacte");
		assertEquals(22, FontLadder.pick(FontLadder.Face.TEXTE, 21), "21 px : 22 est plus près que 19");
		assertEquals(32, FontLadder.pick(FontLadder.Face.TEXTE, 33), "33 px : 32");
		assertEquals(8, FontLadder.pick(FontLadder.Face.TEXTE, 3), "en dessous : la plus petite");
		assertEquals(108, FontLadder.pick(FontLadder.Face.TEXTE, 400), "au-dessus : la plus grande de la face");
		assertEquals(12, FontLadder.pick(FontLadder.Face.TITRE, 8), "les titres commencent à 12 px");
		assertEquals(142, FontLadder.pick(FontLadder.Face.TITRE, 160), "les titres montent à 142 px");
	}

	@Test
	void ecartBorneEntreTailleVoulueEtDeclaree() {
		// Le texte courant, de l'échelle 1 à 9 : la mise à l'échelle reste sous 8 %, donc invisible une fois lissée.
		for (FontLadder.Face face : FontLadder.Face.values()) {
			for (float px = face.min; px <= face.max; px += 0.5f) {
				int s = FontLadder.pick(face, px);
				double ratio = px / s;
				assertTrue(ratio > 0.92 && ratio < 1.08, face + " " + px + " px -> " + s);
			}
		}
	}

	@Test
	void hauteurDeLigne() {
		assertEquals(29, FontLadder.lineHeight(22));
		assertEquals(10, FontLadder.lineHeight(8));
	}

	/** Hauteur des capitales en fraction du cadratin, lue dans la police (tables head et OS/2). */
	private static double capRatio(Path ttf) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(ttf));
		int n = b.getShort(4) & 0xFFFF;
		int head = -1;
		int os2 = -1;
		for (int i = 0; i < n; i++) {
			int rec = 12 + 16 * i;
			String tag = new String(new byte[] {b.get(rec), b.get(rec + 1), b.get(rec + 2), b.get(rec + 3)}, java.nio.charset.StandardCharsets.ISO_8859_1);
			if (tag.equals("head")) {
				head = b.getInt(rec + 8);
			} else if (tag.equals("OS/2")) {
				os2 = b.getInt(rec + 8);
			}
		}
		int upm = b.getShort(head + 18) & 0xFFFF;
		int version = b.getShort(os2) & 0xFFFF;
		int cap = version >= 2 ? b.getShort(os2 + 88) : (int) (upm * 0.7);
		return cap / (double) upm;
	}

	@Test
	void definitionsConformesAuScript() throws IOException {
		Set<String> expected = new HashSet<>();
		for (FontLadder.Face face : FontLadder.Face.values()) {
			int[] sizes = FontLadder.sizes(face);
			assertTrue(sizes.length >= 15, "échelle complète pour " + face);
			for (int size : sizes) {
				String name = face.id + "_" + size + ".json";
				expected.add(name);
				Path file = FONTS.resolve(name);
				assertTrue(Files.exists(file), "définition " + name);
				JsonObject def = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				JsonArray providers = def.getAsJsonArray("providers");
				JsonObject ttf = providers.get(0).getAsJsonObject();
				assertEquals("ttf", ttf.get("type").getAsString());
				assertEquals(size, ttf.get("size").getAsInt(), name);
				assertEquals(1.0, ttf.get("oversample").getAsDouble(), name + " : un pixel de police par pixel d'écran");
				String file0 = ttf.get("file").getAsString();
				assertTrue(file0.startsWith("valcombe:hd/"), name);
				Path font = FONTS.resolve(file0.substring("valcombe:hd/".length()));
				assertTrue(Files.exists(font), "police " + font);
				// Ligne de base : capitales centrées dans la ligne (même calcul que make_ui.py).
				int baseline = (int) Math.floor((FontLadder.lineHeight(size) + capRatio(font) * size) / 2 + 0.5);
				assertEquals(baseline - 7, ttf.getAsJsonArray("shift").get(1).getAsDouble(), 1e-6, name + " : décalage vertical");
				assertEquals("reference", providers.get(1).getAsJsonObject().get("type").getAsString(), name + " : repli");
			}
		}
		try (Stream<Path> files = Files.list(FONTS)) {
			files.map(p -> p.getFileName().toString()).filter(f -> f.endsWith(".json"))
				.forEach(f -> assertTrue(expected.contains(f), "définition en trop : " + f));
		}
		assertTrue(Files.exists(FONTS.resolve("licence_cinzel_ofl.txt")), "licence OFL de Cinzel");
		assertTrue(Files.exists(FONTS.resolve("licence_alegreya_sans_ofl.txt")), "licence OFL d'Alegreya Sans");
	}
}
