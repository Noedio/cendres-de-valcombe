package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;

import fr.valcombe.rules.GameException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ce que l'Atelier partage entre la page web et l'écran en jeu, et qui se vérifie sans lancer Minecraft. */
class WorkshopTest {
	@TempDir
	Path tmp;

	private static List<int[]> ring(int n, int radius) {
		List<int[]> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			double a = 2 * Math.PI * i / n;
			out.add(new int[] {(int) Math.round(radius * Math.cos(a)), 1, (int) Math.round(radius * Math.sin(a))});
		}
		return out;
	}

	// ------------------------------------------------------------------
	// Liste de contrôle
	// ------------------------------------------------------------------
	@Test
	void carteCompleteToutEstCoche() {
		List<Workshop.Check> checks = Workshop.checks(ring(16, 12), ring(16, 30), new int[] {40, 1, 0}, 60);
		assertEquals(5, checks.size());
		assertTrue(checks.stream().allMatch(Workshop.Check::ok), checks.toString());
		assertTrue(checks.get(0).texte().contains("16 trône(s)") && checks.get(0).texte().contains("16 joueurs"), checks.get(0).texte());
	}

	@Test
	void plusDeTronesQueDeJoueursPossibles() {
		// Une carte accepte 40 trônes, mais une partie se joue à 16 au plus : la liste ne promet pas davantage.
		String text = Workshop.checks(ring(30, 25), List.of(), null, 60).get(0).texte();
		assertTrue(text.contains("30 trône(s)") && text.contains("jusqu'à 16 joueurs"), text);
	}

	@Test
	void carteIncompleteChaqueManqueEstDit() {
		List<int[]> seats = new ArrayList<>(ring(4, 10));
		seats.add(new int[] {10, 1, 0}); // en double du premier
		seats.set(2, new int[] {80, 1, 0}); // hors de la zone enregistrée
		List<Workshop.Check> checks = Workshop.checks(seats.subList(0, 4), ring(2, 20), null, 20);
		assertFalse(checks.get(0).ok(), "quatre trônes ne suffisent pas");
		assertFalse(checks.get(1).ok(), "moins de maisons que de trônes");
		assertFalse(checks.get(2).ok(), "pas de salle de convocation");
		assertFalse(checks.get(3).ok(), "un trône hors zone");
		assertTrue(checks.get(4).ok());
		assertFalse(Workshop.checks(seats, List.of(), null, 100).get(4).ok(), "deux trônes au même endroit");
		assertTrue(Workshop.checks(seats, List.of(), null, 100).get(1).ok(), "aucune maison : ce n'est pas un défaut");
	}

	// ------------------------------------------------------------------
	// Nombres reçus de la page web
	// ------------------------------------------------------------------
	@Test
	void nombreAbsentVideOuHorsBornesMessageEnClair() {
		var ok = JsonParser.parseString("{\"places\": 12}").getAsJsonObject();
		assertEquals(12, Workshop.number(ok, "places", "Trônes", 5, 20));
		for (String body : List.of("{}", "{\"places\": null}", "{\"places\": \"\"}", "{\"places\": \"douze\"}", "{\"places\": [1]}")) {
			GameException e = assertThrows(GameException.class,
				() -> Workshop.number(JsonParser.parseString(body).getAsJsonObject(), "places", "Trônes", 5, 20), body);
			assertEquals("Trônes : indiquez un nombre de 5 à 20.", e.getMessage());
		}
		for (int n : new int[] {3, 30}) {
			GameException e = assertThrows(GameException.class,
				() -> Workshop.number(JsonParser.parseString("{\"places\": " + n + "}").getAsJsonObject(), "places", "Trônes", 5, 20));
			assertEquals("Trônes : de 5 à 20.", e.getMessage(), "pas de retour en silence à la borne");
		}
	}

	// ------------------------------------------------------------------
	// Aperçu réduit
	// ------------------------------------------------------------------
	@Test
	void apercuReduitMoyenneDeChaqueCase() {
		// Image de 4 × 4 : moitié gauche rouge, moitié droite bleue ; réduite à 2 × 2.
		int[] rgb = new int[16];
		for (int i = 0; i < 16; i++) {
			rgb[i] = i % 4 < 2 ? 0xFF0000 : 0x0000FF;
		}
		byte[] out = Workshop.shrink(4, 4, rgb, 2);
		assertEquals(12, out.length);
		assertEquals(255, out[0] & 255);
		assertEquals(0, out[2] & 255);
		assertEquals(0, out[3] & 255);
		assertEquals(255, out[5] & 255);
		// Image plus petite que l'aperçu : chaque case prend le pixel le plus proche, sans déborder.
		byte[] grown = Workshop.shrink(1, 1, new int[] {0x102030}, 3);
		assertEquals(27, grown.length);
		assertEquals(0x30, grown[26] & 255);
	}

	// ------------------------------------------------------------------
	// Repères : revenir sur la dernière modification, carte d'origine
	// ------------------------------------------------------------------
	@Test
	void reperesRemisCommeAvant() {
		Village v = Village.custom(new BlockPos(0, 64, 0));
		v.addSeat(new BlockPos(5, 65, 0));
		v.addHouse(new BlockPos(20, 65, 0));
		Village.Marks before = v.marks();
		v.addSeat(new BlockPos(-5, 65, 0));
		v.moveMarker(new Village.Hit(Village.Mark.HOUSE, 0), new BlockPos(0, 65, 20));
		v.setConvocation(new BlockPos(30, 65, 30));
		v.setCenter(new BlockPos(2, 64, 2));
		v.restore(before);
		assertEquals(1, v.seatCount());
		assertEquals(new BlockPos(20, 65, 0), v.markerPos(new Village.Hit(Village.Mark.HOUSE, 0)));
		assertEquals(null, v.convocation());
		assertEquals(new BlockPos(0, 64, 0), v.center());
	}

	@Test
	void laFicheRetientLaCarteDOrigine() {
		Path f = tmp.resolve("valcombe-village.json");
		Village v = Village.custom(new BlockPos(10, 70, -4));
		v.setLibrary("port-aux-brumes", "Le Port aux Brumes");
		v.setModified(false);
		v.save(f, false);
		Village back = Village.load(f).village();
		assertEquals("port-aux-brumes", back.libraryId());
		assertEquals("Le Port aux Brumes", back.libraryName());
		assertFalse(back.modified());
		// Une fiche d'avant cette version n'a pas ces champs : la carte ne vient d'aucune carte de la bibliothèque.
		Village plain = Village.custom(new BlockPos(0, 64, 0));
		plain.save(f, false);
		assertEquals("", Village.load(f).village().libraryId());
	}

	@Test
	void resumeDeLaCarteEnPlace() {
		assertEquals("Aucune carte sur le serveur.", Workshop.describe(null));
		java.util.Map<String, Object> cur = new java.util.HashMap<>(java.util.Map.of("nom", "Le Port", "modifiee", true, "genere", false,
			"theme", "port", "sieges", 12, "maisons", 12));
		assertEquals("Carte actuelle : « Le Port », retouchée depuis. 12 trônes, 12 maisons.", Workshop.describe(cur));
		cur.put("nom", "");
		assertEquals("Carte actuelle : faite à la main, pas encore enregistrée. 12 trônes, 12 maisons.", Workshop.describe(cur));
		cur.put("genere", true);
		cur.put("modifiee", false);
		assertEquals("Carte actuelle : village généré (port). 12 trônes, 12 maisons.", Workshop.describe(cur));
	}

	// ------------------------------------------------------------------
	// Bornes d'une fiche de carte : les mêmes à l'enregistrement qu'au chargement
	// ------------------------------------------------------------------
	@Test
	void ficheDeCarteNomTropLongOuRepereTropHautRefuses() {
		MapStore.MapDef d = new MapStore.MapDef();
		d.id = "port";
		d.nom = "Le Port";
		d.rayon = 40;
		d.sieges = ring(8, 10);
		MapStore.checkDef(d);
		d.nom = "x".repeat(51);
		assertThrows(GameException.class, () -> MapStore.checkDef(d), "un nom de plus de 50 caractères ne se rechargerait pas");
		d.nom = "Le Port";
		d.sieges = new ArrayList<>(d.sieges);
		d.sieges.add(new int[] {0, 70, 0});
		assertThrows(GameException.class, () -> MapStore.checkDef(d), "un repère à 70 blocs de hauteur non plus");
		d.sieges.remove(d.sieges.size() - 1);
		// Hauteur enregistrée : absente des cartes d'avant (28 blocs), sinon bornée.
		assertEquals(28, d.height());
		d.hauteur = 60;
		MapStore.checkDef(d);
		assertEquals(60, d.height());
		d.hauteur = 5000;
		assertThrows(GameException.class, () -> MapStore.checkDef(d), "une hauteur démesurée est refusée");
	}
}
