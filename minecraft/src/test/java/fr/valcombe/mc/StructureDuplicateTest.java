package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;

import fr.valcombe.rules.GameException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** « Dupliquer » une structure depuis l'Atelier web : une copie à soi, dans « Vos structures », sans toucher à l'originale. */
class StructureDuplicateTest {
	@TempDir
	Path creations;

	private void write(Path dir, String id, String nom, String categorie, String repere, byte[] blocks) throws IOException {
		Files.createDirectories(dir);
		StructureStore.Def d = new StructureStore.Def();
		d.id = id;
		d.nom = nom;
		d.categorie = categorie;
		d.repere = repere;
		d.taille = new int[] {7, 18, 7};
		d.ancre = new int[] {3, 1, 3};
		d.blocs = 642;
		Files.writeString(dir.resolve(id + ".json"), new Gson().toJson(d), StandardCharsets.UTF_8);
		Files.write(dir.resolve(id + ".nbt"), blocks);
	}

	@Test
	void copieDUneStructureASoi() throws IOException {
		Path dir = creations.resolve("structures");
		byte[] blocks = {9, 8, 7, 6};
		write(dir, "tour-du-guet", "La Tour du guet", "place", "maison", blocks);
		StructureStore store = new StructureStore(creations);
		String said = store.duplicate("tour-du-guet", "");
		assertTrue(said.contains("« La Tour du guet (copie) »") && said.contains("« Vos structures »"), said);
		String copy = store.ids(true).stream().filter(id -> !id.equals("tour-du-guet")).findFirst().orElseThrow();
		assertEquals("la-tour-du-guet-copie", copy);
		StructureStore.Def d = store.def(copy);
		assertEquals("La Tour du guet (copie)", d.nom);
		assertEquals("perso", d.categorie, "la copie va dans « Vos structures »");
		assertEquals("maison", d.repere, "même repère");
		assertArrayEquals(new int[] {7, 18, 7}, d.taille);
		assertArrayEquals(new int[] {3, 1, 3}, d.ancre);
		assertFalse(d.fournie);
		assertArrayEquals(blocks, Files.readAllBytes(dir.resolve(copy + ".nbt")), "mêmes blocs");
		assertEquals("place", store.def("tour-du-guet").categorie, "l'originale ne bouge pas");

		// Relue par un catalogue neuf (redémarrage) : la copie est toujours là.
		StructureStore again = new StructureStore(creations);
		assertTrue(again.exists(copy));

		// Une seconde copie du même nom prend un identifiant libre ; un nom trop long est refusé en clair.
		again.duplicate("tour-du-guet", "La Tour du guet (copie)");
		assertTrue(again.exists("la-tour-du-guet-copie-2"), again.ids(true).toString());
		GameException tooLong = assertThrows(GameException.class, () -> again.duplicate("tour-du-guet", "x".repeat(51)));
		assertTrue(tooLong.getMessage().contains("1 à 50"), tooLong.getMessage());
		assertThrows(GameException.class, () -> again.duplicate("inconnue", ""));
	}
}
