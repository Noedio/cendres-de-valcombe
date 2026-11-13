package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import fr.valcombe.rules.Role;

import org.junit.jupiter.api.Test;

/**
 * Textures du mod (dessinées par tools/make_assets.py, make_models.py et make_tools.py) :
 * tailles haute résolution attendues par le code d'interface (Theme.TOKEN_TEX, ICON_TEX, HUD_TEX,
 * MARKER_TEX), filtrage lisse des textures d'interface, et aucune texture manquante.
 */
class TexturesTest {
	private static final Path ASSETS = Path.of("src/client/resources/assets/valcombe");
	private static final Path GUI = ASSETS.resolve("textures/gui");
	/** Les emblèmes des rôles : dans les ressources communes (le serveur les sert à la page web de l'Atelier). */
	private static final Path ROLES = Path.of("src/main/resources/assets/valcombe/textures/gui/roles");

	/** Largeur, hauteur, profondeur et type de couleur lus dans l'en-tête IHDR du PNG. */
	private static int[] header(Path png) throws IOException {
		byte[] b = Files.readAllBytes(png);
		assertTrue(b.length > 26 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G', png + " n'est pas un PNG");
		ByteBuffer buf = ByteBuffer.wrap(b);
		return new int[] { buf.getInt(16), buf.getInt(20), b[24], b[25] };
	}

	private static void assertSize(Path png, int w, int h) throws IOException {
		assertTrue(Files.exists(png), "texture manquante : " + png);
		int[] hd = header(png);
		assertEquals(w, hd[0], "largeur de " + png);
		assertEquals(h, hd[1], "hauteur de " + png);
		assertEquals(8, hd[2], "profondeur de " + png);
		assertEquals(6, hd[3], "RGBA attendu pour " + png);
	}

	/** Texture d'interface haute résolution : taille réelle et .mcmeta qui demande le filtrage lisse. */
	private static void assertHd(Path png, int size) throws IOException {
		assertSize(png, size, size);
		Path meta = png.resolveSibling(png.getFileName() + ".mcmeta");
		assertTrue(Files.exists(meta), "filtrage lisse manquant : " + meta);
		String json = Files.readString(meta, StandardCharsets.UTF_8).replaceAll("\\s", "");
		assertTrue(json.contains("\"blur\":true"), meta + " doit demander \"blur\": true");
	}

	private static List<Path> list(Path dir, String glob) throws IOException {
		List<Path> out = new ArrayList<>();
		try (var ds = Files.newDirectoryStream(dir, glob)) {
			ds.forEach(out::add);
		}
		return out;
	}

	@Test
	void jetonsCalquesMarqueursEtHudEnHauteResolution() throws IOException {
		for (String n : List.of("token_good", "token_evil", "token_neutral", "token_dead", "token_selected", "token_hover")) {
			assertHd(GUI.resolve(n + ".png"), 128);
		}
		for (String n : List.of("moon", "sun", "hourglass")) {
			assertHd(GUI.resolve(n + ".png"), 128);
		}
		List<Path> markers = list(GUI, "marker_*.png");
		assertEquals(6, markers.size(), "six marqueurs du grimoire");
		for (Path p : markers) {
			assertHd(p, 96);
		}
	}

	@Test
	void chaqueEmblemeExisteEn128() throws IOException {
		List<String> icons = new ArrayList<>(Atelier.EMBLEMS);
		for (Role r : Role.values()) {
			if (r.icon != null) {
				icons.add(r.icon);
			}
		}
		for (String icon : icons) {
			assertHd(ROLES.resolve(icon + ".png"), 128);
		}
		for (Path p : list(ROLES, "*.png")) {
			assertHd(p, 128);
		}
	}

	@Test
	void cadresEtBoutonsGardentLeursDimensions() throws IOException {
		// Theme.panel et Theme.nineSlice découpent ces images avec des tailles écrites en dur.
		assertSize(GUI.resolve("panel.png"), 48, 48);
		assertSize(GUI.resolve("parchment.png"), 256, 256);
		for (String n : List.of("button", "button_hover", "button_disabled")) {
			assertSize(GUI.resolve(n + ".png"), 64, 20);
		}
	}

	@Test
	void texturesDObjetsEn128SansFiltrageForce() throws IOException {
		int n = 0;
		for (String dir : List.of("item/scene", "item/objets", "item/outils")) {
			for (Path p : list(ASSETS.resolve("textures/" + dir), "*.png")) {
				assertSize(p, 128, 128);
				// Les textures d'objets vont dans l'atlas des objets : pas de .mcmeta de texture seule
				assertFalse(Files.exists(p.resolveSibling(p.getFileName() + ".mcmeta")), "mcmeta inattendu : " + p);
				n++;
			}
		}
		assertTrue(n > 150, "textures d'objets trouvées : " + n);
	}

	@Test
	void chaqueTextureCiteeParUnModeleExiste() throws IOException {
		Pattern ref = Pattern.compile("\"valcombe:(item/[a-z0-9_/]+)\"");
		int n = 0;
		try (Stream<Path> models = Files.list(ASSETS.resolve("models/item"))) {
			for (Path m : models.toList()) {
				Matcher mt = ref.matcher(Files.readString(m, StandardCharsets.UTF_8));
				while (mt.find()) {
					String id = mt.group(1);
					if (Files.exists(ASSETS.resolve("models/" + id + ".json"))) {
						continue; // référence à un modèle (parent), pas à une texture
					}
					assertTrue(Files.exists(ASSETS.resolve("textures/" + id + ".png")), m.getFileName() + " cite " + id);
					n++;
				}
			}
		}
		assertTrue(n > 150, "références de textures vérifiées : " + n);
	}
}
