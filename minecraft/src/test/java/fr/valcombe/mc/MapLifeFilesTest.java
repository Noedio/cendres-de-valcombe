package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fiche du village, plages de l'instantané, plan de chantier : ce qui se vérifie sans lancer Minecraft. */
class MapLifeFilesTest {
	@TempDir
	Path tmp;

	// ------------------------------------------------------------------
	// Fichier sûr
	// ------------------------------------------------------------------
	@Test
	void fichierTronqueLaCopieDeSecoursEstRelue() throws IOException {
		Path f = tmp.resolve("fiche.json");
		SafeFile.write(f, "{\"a\":1}");
		SafeFile.write(f, "{\"a\":2}");
		assertEquals("{\"a\":2}", SafeFile.read(f, t -> t.endsWith("}")));
		// Coupure pendant une écriture directe : le fichier est tronqué, la version d'avant est encore là.
		Files.writeString(f, "{\"a\":");
		assertEquals("{\"a\":1}", SafeFile.read(f, t -> t.endsWith("}")));
		assertFalse(Files.exists(tmp.resolve("fiche.json.tmp")), "pas de fichier provisoire oublié");
	}

	@Test
	void fichierJamaisEcritNEstPasUnFichierIllisible() throws IOException {
		Path f = tmp.resolve("fiche.json");
		assertFalse(SafeFile.exists(f));
		assertNull(SafeFile.read(f, t -> true));
		Files.writeString(f, "abîmé");
		assertTrue(SafeFile.exists(f));
		assertNull(SafeFile.read(f, t -> t.startsWith("{")), "illisible, et aucune copie de secours");
		SafeFile.delete(f);
		assertFalse(SafeFile.exists(f));
	}

	// ------------------------------------------------------------------
	// Fiche du village
	// ------------------------------------------------------------------
	@Test
	void premierDemarrageFicheAbsente() {
		assertEquals(Village.Fiche.ABSENT, Village.load(tmp.resolve("valcombe-village.json")).state());
	}

	@Test
	void ficheTronqueeLeVillageNEstPasPerdu() throws IOException {
		Path f = tmp.resolve("valcombe-village.json");
		Village v = Village.custom(new BlockPos(10, 64, -20));
		v.addSeat(new BlockPos(14, 65, -20));
		v.save(f);
		v.addSeat(new BlockPos(6, 65, -20));
		v.save(f);
		Files.writeString(f, "{\"center\":[10,64"); // coupure en pleine écriture
		Village.Loaded back = Village.load(f);
		assertEquals(Village.Fiche.READY, back.state(), "la copie de secours est relue");
		assertEquals(new BlockPos(10, 64, -20), back.village().center());
		assertEquals(1, back.village().seatCount(), "l'état d'avant la dernière écriture");
	}

	@Test
	void ficheIllisibleNEstPasUnPremierDemarrage() throws IOException {
		Path f = tmp.resolve("valcombe-village.json");
		Files.writeString(f, "{\"center\":[10,64");
		Village.Loaded back = Village.load(f);
		assertEquals(Village.Fiche.UNREADABLE, back.state(), "surtout pas ABSENT : un second village serait bâti");
		assertNull(back.village());
	}

	@Test
	void laFicheNeRetientPlusDeQuoiRaserUneAncienneCarte() throws IOException {
		// Chaque monde de partie porte sa carte, remis à neuf avant d'en recevoir une autre : plus de carte « oubliée »
		// ni de zone « bâtie » à raser. Une fiche écrite avant reste lisible : ces champs sont simplement ignorés.
		Path f = tmp.resolve("valcombe-village.json");
		Village v = Village.custom(new BlockPos(0, 70, 0));
		v.addSeat(new BlockPos(30, 71, 0));
		v.save(f);
		String json = Files.readString(f);
		assertFalse(json.contains("forgotten") || json.contains("built"), json);
		Files.writeString(f, json.replaceFirst("\\{", "{\"forgotten\": true, \"built\": [-150, 60, 5, 20, 90, 12],"));
		Files.deleteIfExists(SafeFile.backup(f));
		Village.Loaded back = Village.load(f);
		assertEquals(Village.Fiche.READY, back.state());
		assertEquals(1, back.village().seatCount());
		assertEquals(38, back.village().extent());
	}

	@Test
	void carteFaiteALaMainEstAProtegerTantQuElleNEstPasEnregistree() {
		Path f = tmp.resolve("valcombe-village.json");
		Village custom = Village.custom(new BlockPos(0, 70, 0));
		assertTrue(custom.modified());
		custom.setModified(false); // enregistrée dans la bibliothèque
		custom.save(f);
		assertFalse(Village.load(f).village().modified());
		custom.setModified(true);
		custom.save(f);
		assertTrue(Village.load(f).village().modified());
	}

	@Test
	void ficheDAvantLeSuiviEstTenuePourRetouchee() throws Exception {
		// Fiche écrite par une version d'avant : on ignore ce qui a été bâti à la main sur ce village, il est donc à protéger.
		Path f = tmp.resolve("valcombe-village.json");
		Village v = Village.generated(new BlockPos(0, 70, 0), "gothique");
		v.save(f);
		String old = java.nio.file.Files.readString(f).replaceAll("\"modified\"\s*:\s*(true|false),?", "");
		assertFalse(old.contains("modified"));
		java.nio.file.Files.writeString(f, old);
		java.nio.file.Files.deleteIfExists(SafeFile.backup(f));
		assertTrue(Village.load(f).village().modified());
	}

	@Test
	void deuxVillagesNOntJamaisLaMemeGenerationDeDecors() {
		Village a = Village.custom(new BlockPos(0, 70, 0));
		Village b = Village.custom(new BlockPos(0, 70, 0));
		// build() avance d'un cran : l'écart entre deux villages neufs doit rester plus grand.
		assertTrue(Math.abs(a.decorGen() - b.decorGen()) >= 2);
		assertNotEquals(a.decorGen() + 1, b.decorGen());
	}

	@Test
	void etendueDUneCarteALaMainEstBornee() {
		Village v = Village.custom(new BlockPos(0, 70, 0));
		assertEquals(12, v.extent());
		assertTrue(v.reachable(new BlockPos(192, 70, 0)));
		assertFalse(v.reachable(new BlockPos(193, 70, 0)));
		assertFalse(v.reachable(new BlockPos(600, 70, 0)));
		// Un repère lointain venu d'une ancienne fiche : l'étendue ne dépasse pas le plafond, mais on sait qu'il déborde.
		v.addSeat(new BlockPos(600, 70, 0));
		assertEquals(Village.MAX_EXTENT, v.extent());
		assertEquals(608, v.farthest());
	}

	// ------------------------------------------------------------------
	// Plages de l'instantané
	// ------------------------------------------------------------------
	@Test
	void plagesAllerRetour() {
		Random rnd = new Random(7);
		int[] values = new int[5000];
		Runs runs = new Runs();
		for (int i = 0; i < values.length; i++) {
			values[i] = i == 0 || rnd.nextInt(6) == 0 ? rnd.nextInt(9) : values[i - 1];
			runs.add(values[i]);
		}
		assertEquals(values.length, runs.size());
		assertTrue(runs.packed().length < values.length, "les suites sont bien regroupées");
		Runs back = Runs.unpack(runs.packed());
		assertEquals(values.length, back.size());
		assertEquals(8, back.max());
		Runs.Reader r = back.reader();
		for (int i = 0; i < values.length; i++) {
			assertEquals(values[i], r.at(i), "rang " + i);
		}
		// Lecture par sauts (les blocs déjà en place sont sautés), puis retour en arrière.
		Runs.Reader jumps = back.reader();
		for (int i = 0; i < values.length; i += 37) {
			assertEquals(values[i], jumps.at(i));
		}
		assertEquals(values[3], jumps.at(3));
	}

	@Test
	void plagesAbimeesRefusees() {
		assertNull(Runs.unpack(null));
		assertNull(Runs.unpack(new int[] {1, 2, 3}), "longueur impaire");
		assertNull(Runs.unpack(new int[] {1, 0}), "plage vide");
		assertNull(Runs.unpack(new int[] {1, -4}), "longueur négative");
		assertNull(Runs.unpack(new int[] {1, Integer.MAX_VALUE, 2, Integer.MAX_VALUE}), "trop long pour être vrai");
		assertEquals(0, Runs.unpack(new int[0]).size());
		assertEquals(-1, Runs.unpack(new int[0]).max());
	}

	// ------------------------------------------------------------------
	// Plan de chantier
	// ------------------------------------------------------------------
	private static BuildQueue.Part part(int size) {
		return new BuildQueue.Part() {
			@Override
			int size() {
				return size;
			}

			@Override
			net.minecraft.world.level.block.state.BlockState at(int i, BlockPos.MutableBlockPos pos) {
				return null;
			}
		};
	}

	@Test
	void planCompteLesSuitesCalculeesSansLesGarderEnMemoire() {
		BuildQueue.Plan clear = new BuildQueue.Plan().first(part(401 * 401 * 28)); // la plus grande carte à remettre à plat
		BuildQueue.Plan build = new BuildQueue.Plan();
		build.set(new BlockPos(0, 0, 0), null);
		build.set(new BlockPos(1, 0, 0), null);
		clear.then(build);
		assertEquals(401L * 401 * 28 + 2, clear.total());
		assertEquals(2, clear.size(), "seuls les blocs de la liste occupent la mémoire");
	}

	@Test
	void uneSuiteCalculeeNePasseJamaisApresLesBlocsDeLaListe() {
		BuildQueue.Plan build = new BuildQueue.Plan();
		build.set(new BlockPos(0, 0, 0), null);
		BuildQueue.Plan clear = new BuildQueue.Plan().first(part(10));
		// Remettre à plat après avoir bâti détruirait ce qu'on vient de poser : le plan le refuse.
		assertThrows(IllegalStateException.class, () -> build.then(clear));
	}

	// ------------------------------------------------------------------
	// Sauvegardes automatiques
	// ------------------------------------------------------------------
	@Test
	void seulesLesCinqDernieresSauvegardesAutomatiquesSontGardees() {
		var names = new java.util.HashMap<String, String>();
		names.put("port-aux-brumes", "Le Port aux Brumes");
		for (int day = 1; day <= 7; day++) {
			names.put("sauvegarde-2026110" + day + "-120000", "Sauvegarde du 0" + day + "/11/2026 à 12h00");
		}
		// Les deux plus anciennes partent ; une carte ordinaire n'est jamais touchée.
		assertEquals(java.util.List.of("sauvegarde-20261101-120000", "sauvegarde-20261102-120000"), MapStore.staleBackups(names));

		// Renommée, une sauvegarde devient une carte que l'on garde : elle ne compte plus parmi les cinq.
		names.put("sauvegarde-20261101-120000", "Ma tour, première version");
		assertEquals(java.util.List.of("sauvegarde-20261102-120000"), MapStore.staleBackups(names));

		names.keySet().removeIf(id -> id.startsWith("sauvegarde-2026110") && id.compareTo("sauvegarde-20261104") < 0);
		assertTrue(MapStore.staleBackups(names).isEmpty());
	}
}
