package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.GameException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Structures fournies modifiées (surcharges) et retour à l'originale, côté fichiers ; correspondance entre les
 * morceaux du village généré et les structures fournies ; corbeille.
 */
class StructureOverrideTest {
	@TempDir
	Path creations;

	private static StructureStore.Def meta(String id, int w, int h, int l, int[] ancre) {
		StructureStore.Def d = new StructureStore.Def();
		d.id = id;
		d.nom = "Puits couvert et sa cloche";
		d.categorie = "place";
		d.taille = new int[] {w, h, l};
		d.ancre = ancre;
		d.blocs = 42;
		d.fournie = true;
		return d;
	}

	private List<String> trash() throws IOException {
		Path t = creations.resolve("structures").resolve(StructureStore.TRASH);
		if (!Files.isDirectory(t)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(t)) {
			return s.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	@Test
	void uneSurchargeRemplaceLaFournieJusquAuRetourALOriginale() throws IOException {
		StructureStore store = new StructureStore(creations);
		assertFalse(store.isOverridden("puits"));
		assertEquals("fournie", store.signature("puits"));
		byte[] blocks = {1, 2, 3, 4};
		store.writeOverride(meta("puits", 5, 8, 5, new int[] {2, 1, 2}), blocks);
		assertTrue(store.isOverridden("puits"));
		assertTrue(store.signature("puits").startsWith("surcharge:"), store.signature("puits"));
		Path dir = creations.resolve("structures").resolve(StructureStore.OVERRIDES);
		assertArrayEquals(blocks, Files.readAllBytes(dir.resolve("puits.nbt")));
		String json = Files.readString(dir.resolve("puits.json"), StandardCharsets.UTF_8);
		assertTrue(json.contains("\"modifiee\": true") && json.contains("\"modifieeLe\""), json);

		// Relue par un catalogue neuf (redémarrage) : la surcharge est toujours là.
		StructureStore again = new StructureStore(creations);
		assertTrue(again.isOverridden("puits"));
		assertFalse(again.isOverridden("fontaine"), "les autres structures fournies restent les originales");

		// Revenir à l'originale : la surcharge part à la corbeille, l'originale revient.
		String msg = again.revert("puits");
		assertTrue(msg.contains("revenue à l'originale"), msg);
		assertFalse(again.isOverridden("puits"));
		assertEquals("fournie", again.signature("puits"));
		assertFalse(Files.exists(dir.resolve("puits.nbt")));
		assertFalse(Files.exists(dir.resolve("puits.json")));
		List<String> trashed = trash();
		assertEquals(2, trashed.size(), "fiche et blocs gardés dans la corbeille : " + trashed);
		assertTrue(trashed.stream().allMatch(n -> n.endsWith("-puits.json") || n.endsWith("-puits.nbt")), trashed.toString());
		assertThrows(GameException.class, () -> again.revert("puits"), "déjà l'originale");
		assertThrows(GameException.class, () -> again.revert("tour-perso"), "pas une structure fournie");
	}

	@Test
	void seulesLesStructuresFourniesSeSurchargent() {
		StructureStore store = new StructureStore(creations);
		StructureStore.Def d = meta("ma-tour", 3, 3, 3, new int[] {1, 0, 1});
		assertThrows(GameException.class, () -> store.writeOverride(d, new byte[] {1}));
		assertFalse(StructureStore.isBuiltin("ma-tour"));
		assertTrue(StructureStore.isBuiltin("maison-gothique"));
	}

	@Test
	void uneSurchargeSansSesBlocsEstIgnoree() throws IOException {
		Path dir = creations.resolve("structures").resolve(StructureStore.OVERRIDES);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("fanal.json"), "{\"id\":\"fanal\",\"nom\":\"Fanal\",\"taille\":[5,8,5]}");
		Files.writeString(dir.resolve("inconnue.json"), "{\"id\":\"inconnue\",\"nom\":\"?\",\"taille\":[1,1,1]}");
		Files.write(dir.resolve("inconnue.nbt"), new byte[] {1});
		StructureStore store = new StructureStore(creations);
		assertFalse(store.isOverridden("fanal"), "fiche sans blocs : l'originale sert");
		assertFalse(store.isOverridden("inconnue"));
	}

	@Test
	void lesMorceauxDuVillageTrouventLeurStructureFournie() {
		// Exactement : sorte, thème, variante.
		assertEquals("maison-gothique", StructureStore.builtinFor("maison", "gothique", 0));
		assertEquals("maison-gothique-claire", StructureStore.builtinFor("maison", "gothique", 1));
		// Même sorte et même thème : une maison impaire du monastère prend la cellule du monastère.
		assertEquals("maison-monastere", StructureStore.builtinFor("maison", "monastere", 1));
		assertEquals("puits", StructureStore.builtinFor("monument", "gothique", 0));
		assertEquals("cloitre", StructureStore.builtinFor("monument", "monastere", 0));
		assertEquals("fanal", StructureStore.builtinFor("monument", "port", 0));
		assertEquals("arbre-chene", StructureStore.builtinFor("arbre", "port", 0));
		// Une seule structure fournie de cette sorte : elle vaut pour tous les thèmes.
		assertEquals("salle-convocation", StructureStore.builtinFor("salle", "port", 0));
		assertEquals("tonneaux", StructureStore.builtinFor("tonneaux", "gothique", 0));
		// Les étals se bâtissent pareil dans tous les thèmes : la variante (couleur) suffit.
		assertEquals("etal-bleu", StructureStore.builtinFor("etal", "port", 2));
		assertEquals("etal-vert", StructureStore.builtinFor("etal", "gothique", 4));
		assertEquals("pavillon", StructureStore.builtinFor("pavillon", "monastere", 0));
		// Le lampadaire du port n'a pas de structure fournie (deux lampadaires, ni l'un ni l'autre du port).
		assertNull(StructureStore.builtinFor("lampadaire", "port", 0));
		assertEquals("lampadaire-ames", StructureStore.builtinFor("lampadaire", "monastere", 0));
		assertNull(StructureStore.builtinFor("inconnu", "gothique", 0));
	}

	@Test
	void uneStructureSupprimeeVaALaCorbeille() throws IOException {
		Path dir = creations.resolve("structures");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("ma-tour.json"), "{\"id\":\"ma-tour\",\"nom\":\"Ma tour\",\"taille\":[3,5,3],\"ancre\":[1,0,1]}");
		Files.write(dir.resolve("ma-tour.nbt"), new byte[] {9, 9});
		StructureStore store = new StructureStore(creations);
		assertTrue(store.exists("ma-tour"));
		assertTrue(store.signature("ma-tour").startsWith("perso:"));
		String msg = store.delete("ma-tour");
		assertTrue(msg.contains("corbeille"), msg);
		assertFalse(store.exists("ma-tour"));
		assertEquals("", store.signature("ma-tour"));
		assertFalse(Files.exists(dir.resolve("ma-tour.nbt")));
		assertEquals(2, trash().size(), "rien n'est effacé : " + trash());
		assertThrows(GameException.class, () -> store.delete("puits"), "une structure fournie ne se supprime pas");
	}

	@Test
	void uneCopieDeLaVersionActuelleVaALaCorbeilleSansLaRetirer() throws IOException {
		StructureStore store = new StructureStore(creations);
		assertFalse(store.backup("puits"), "une structure fournie jamais modifiée : rien à copier");
		store.writeOverride(meta("puits", 5, 8, 5, new int[] {2, 1, 2}), new byte[] {7, 7});
		assertTrue(store.backup("puits"));
		assertTrue(store.isOverridden("puits"), "la version copiée reste en place");
		Path dir = creations.resolve("structures");
		Files.writeString(dir.resolve("ma-tour.json"), "{\"id\":\"ma-tour\",\"nom\":\"Ma tour\",\"taille\":[3,5,3]}");
		Files.write(dir.resolve("ma-tour.nbt"), new byte[] {9});
		store.reload();
		assertTrue(store.backup("ma-tour"));
		assertTrue(store.exists("ma-tour"));
		List<String> trashed = trash();
		assertEquals(4, trashed.size(), trashed.toString());
		assertTrue(trashed.stream().anyMatch(n -> n.endsWith("-ma-tour.nbt")) && trashed.stream().anyMatch(n -> n.endsWith("-puits.nbt")),
			trashed.toString());
		assertFalse(store.backup("inconnue"));
	}

	@Test
	void uneStructureAVousGardeSonRayon() throws IOException {
		Path dir = creations.resolve("structures");
		Files.createDirectories(dir);
		// Rangée dans « Décor » à l'enregistrement de la sélection WorldEdit (/vc structures depuis-selection ... decor).
		Files.writeString(dir.resolve("ma-fontaine.json"), "{\"id\":\"ma-fontaine\",\"nom\":\"Fontaine\",\"categorie\":\"decor\",\"taille\":[3,2,3]}");
		Files.write(dir.resolve("ma-fontaine.nbt"), new byte[] {1});
		// Un rayon inconnu (fichier retouché à la main) : « Vos structures ».
		Files.writeString(dir.resolve("ma-tour.json"), "{\"id\":\"ma-tour\",\"nom\":\"Tour\",\"categorie\":\"donjons\",\"taille\":[3,5,3]}");
		Files.write(dir.resolve("ma-tour.nbt"), new byte[] {1});
		StructureStore store = new StructureStore(creations);
		assertEquals("decor", store.def("ma-fontaine").categorie);
		assertFalse(store.def("ma-fontaine").fournie, "elle reste à l'utilisateur");
		assertEquals("perso", store.def("ma-tour").categorie);
		assertEquals(List.of("maisons", "place", "decor", "perso"), StructureStore.CATEGORIES);
		assertEquals(StructurePlots.ORDER, StructureStore.CATEGORIES, "l'atelier structures range ses rangées dans le même ordre");
	}

	@Test
	void identifiantLibreTireDUnNom() throws IOException {
		Path dir = creations.resolve("structures");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("tour-d-essai.json"), "{\"id\":\"tour-d-essai\",\"nom\":\"Tour\",\"taille\":[1,1,1]}");
		Files.write(dir.resolve("tour-d-essai.nbt"), new byte[] {1});
		StructureStore store = new StructureStore(creations);
		assertEquals("tour-d-essai-2", store.freeId("Tour d'essai", id -> false));
		assertEquals("tour-d-essai-3", store.freeId("Tour d'essai", id -> id.equals("tour-d-essai-2")));
		assertEquals("puits-2", store.freeId("Puits", id -> false), "jamais l'identifiant d'une structure fournie");
		assertEquals("nouvelle", store.freeId("Nouvelle", id -> false));
	}
}
