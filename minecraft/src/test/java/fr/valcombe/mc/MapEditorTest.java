package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.rules.GameException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;

import org.junit.jupiter.api.Test;

/** Éditeur de carte : orientation des structures, pile d'annulation, limites et contrôle des fichiers — sans lancer Minecraft. */
class MapEditorTest {
	private static final List<Turn> ALL = List.of(new Turn(0, false), new Turn(1, false), new Turn(2, false), new Turn(3, false),
		new Turn(0, true), new Turn(1, true), new Turn(2, true), new Turn(3, true));

	// ------------------------------------------------------------------
	// Rotation et miroir
	// ------------------------------------------------------------------
	@Test
	void chaqueOrientationRangeToutesLesCasesSansEnPerdre() {
		int w = 3;
		int l = 5;
		for (Turn t : ALL) {
			int tw = t.width(w, l);
			int tl = t.length(w, l);
			assertEquals(w * l, tw * tl);
			assertEquals(t.quarters() % 2 == 0 ? w : l, tw, "un quart de tour échange largeur et profondeur");
			Set<Integer> seen = new HashSet<>();
			for (int x = 0; x < w; x++) {
				for (int z = 0; z < l; z++) {
					int nx = t.x(x, z, w, l);
					int nz = t.z(x, z, w, l);
					assertTrue(nx >= 0 && nx < tw && nz >= 0 && nz < tl, t + " sort du rectangle en " + x + "," + z);
					assertTrue(seen.add(nz * tw + nx), t + " pose deux cases au même endroit");
				}
			}
		}
	}

	@Test
	void unQuartDeTourTourneDansLeSensDesAiguillesDUneMontre() {
		// Vu de dessus, x vers l'est et z vers le sud : le coin nord-ouest part au nord-est.
		Turn quarter = new Turn(1, false);
		assertEquals(4, quarter.x(0, 0, 3, 5));
		assertEquals(0, quarter.z(0, 0, 3, 5));
		// Le coin nord-est part au sud-est.
		assertEquals(4, quarter.x(2, 0, 3, 5));
		assertEquals(2, quarter.z(2, 0, 3, 5));
		// Demi-tour : chaque case passe à l'opposé.
		Turn half = new Turn(2, false);
		assertEquals(2, half.x(0, 0, 3, 5));
		assertEquals(4, half.z(0, 0, 3, 5));
	}

	@Test
	void quatreQuartsDeTourOuDeuxMiroirsRamenentALOrigine() {
		for (Turn t : ALL) {
			assertEquals(t, t.rotated().rotated().rotated().rotated());
			assertEquals(t, t.mirrored().mirrored());
		}
		assertEquals(new Turn(1, false), new Turn(5, false), "les quarts de tour se comptent modulo 4");
		assertEquals(new Turn(3, false), new Turn(-1, false));
	}

	@Test
	void leMiroirRetourneCeQueLOnVoit() {
		// Quelle que soit la rotation déjà faite, retourner échange l'est et l'ouest de la construction telle qu'elle est posée.
		int w = 3;
		int l = 5;
		for (Turn t : ALL) {
			Turn m = t.mirrored();
			int tw = t.width(w, l);
			for (int x = 0; x < w; x++) {
				for (int z = 0; z < l; z++) {
					assertEquals(tw - 1 - t.x(x, z, w, l), m.x(x, z, w, l), t + " retournée, case " + x + "," + z);
					assertEquals(t.z(x, z, w, l), m.z(x, z, w, l));
				}
			}
		}
	}

	@Test
	void lOrientationSeDitEnClair() {
		assertEquals("", Turn.NONE.label());
		assertEquals("tournée de 90°", new Turn(1, false).label());
		assertEquals("retournée", new Turn(0, true).label());
		assertEquals("tournée de 270°, retournée", new Turn(3, true).label());
	}

	// ------------------------------------------------------------------
	// Annuler, rétablir
	// ------------------------------------------------------------------
	@Test
	void annulerPuisRetablirSurPlusieursNiveaux() {
		History<String> h = new History<>(10, 1000);
		h.record("a", 1);
		h.record("b", 1);
		h.record("c", 1);
		assertEquals(3, h.undoable());
		assertEquals("c", h.takeUndo());
		h.putRedo("c'", 1);
		assertEquals("b", h.takeUndo());
		h.putRedo("b'", 1);
		assertEquals(1, h.undoable());
		assertEquals(2, h.redoable());
		// On rétablit dans l'ordre inverse de l'annulation.
		assertEquals("b'", h.takeRedo());
		h.putUndo("b", 1);
		assertEquals(1, h.redoable(), "rétablir un pas garde les suivants à rétablir");
		assertEquals("c'", h.takeRedo());
		h.putUndo("c", 1);
		assertEquals(3, h.undoable());
		assertNull(h.takeRedo());
	}

	@Test
	void uneNouvelleOperationEffaceCeQuOnPouvaitRetablir() {
		History<String> h = new History<>(10, 1000);
		h.record("a", 1);
		h.takeUndo();
		h.putRedo("a'", 1);
		h.record("b", 1);
		assertEquals(0, h.redoable());
		assertEquals("b", h.takeUndo());
		assertNull(h.takeUndo());
	}

	@Test
	void lHistoriqueOublieLesPlusAnciennesOperations() {
		History<Integer> h = new History<>(3, 1000);
		for (int i = 1; i <= 5; i++) {
			h.record(i, 1);
		}
		assertEquals(3, h.undoable(), "trois niveaux au plus");
		assertEquals(5, h.takeUndo());
		assertEquals(4, h.takeUndo());
		assertEquals(3, h.takeUndo());
		assertNull(h.takeUndo());
	}

	@Test
	void lHistoriqueTientDansSonBudgetDeBlocs() {
		History<String> h = new History<>(10, 100);
		h.record("petit", 30);
		h.record("moyen", 60);
		h.record("gros", 50);
		assertTrue(h.weight() <= 100);
		assertEquals("gros", h.takeUndo(), "la dernière opération reste annulable");
		h.clear();
		// Une opération plus lourde que tout le budget reste seule, mais reste.
		h.record("a", 10);
		h.record("énorme", 500);
		assertEquals(1, h.undoable());
		assertEquals("énorme", h.takeUndo());
		// Ce qu'on vient d'annuler doit pouvoir être rétabli, quitte à oublier de plus vieilles annulations.
		h.record("x", 60);
		h.record("y", 30);
		h.takeUndo();
		h.putRedo("y'", 60);
		assertEquals(1, h.redoable());
		assertEquals("y'", h.takeRedo());
	}

	// ------------------------------------------------------------------
	// Sélection et limites
	// ------------------------------------------------------------------
	@Test
	void laSelectionNeDependPasDeLOrdreDesCoins() {
		MapEditor.Box b = MapEditor.Box.of(new BlockPos(5, 70, -3), new BlockPos(1, 64, 2));
		assertEquals(new BlockPos(1, 64, -3), b.min());
		assertEquals(new BlockPos(5, 70, 2), b.max());
		assertEquals(5, b.width());
		assertEquals(7, b.height());
		assertEquals(6, b.length());
		assertEquals(210, b.volume());
		assertEquals("5 × 7 × 6", b.dims());
		assertTrue(b.contains(new BlockPos(1, 64, -3)));
		assertFalse(b.contains(new BlockPos(6, 64, 0)));
		assertEquals(b, MapEditor.Box.at(b.min(), 5, 7, 6));
	}

	@Test
	void etirerDeplacerEmpiler() {
		MapEditor.Box b = MapEditor.Box.of(new BlockPos(0, 64, 0), new BlockPos(2, 66, 4));
		assertEquals(3, b.along(Direction.EAST));
		assertEquals(5, b.along(Direction.NORTH));
		assertEquals(3, b.along(Direction.UP));
		MapEditor.Box up = b.grown(Direction.UP, 4);
		assertEquals(7, up.height());
		assertEquals(64, up.y0());
		MapEditor.Box north = b.grown(Direction.NORTH, 10);
		assertEquals(-10, north.z0());
		assertEquals(4, north.z1());
		MapEditor.Box moved = b.moved(-3, 0, 0);
		assertEquals(6, b.union(moved).width());
	}

	@Test
	void uneOperationTropGrandeEstRefusee() {
		assertDoesNotThrow(() -> MapEditor.checkVolume(MapEditor.MAX_VOLUME));
		GameException e = assertThrows(GameException.class, () -> MapEditor.checkVolume(MapEditor.MAX_VOLUME + 1L));
		assertTrue(e.getMessage().contains("trop grande"));
		// Deux coins très éloignés : le volume se compte sans déborder d'un entier.
		MapEditor.Box huge = MapEditor.Box.of(new BlockPos(-30000, -64, -30000), new BlockPos(30000, 319, 30000));
		assertTrue(huge.volume() > Integer.MAX_VALUE);
		assertThrows(GameException.class, () -> MapEditor.checkVolume(huge.volume()));
	}

	@Test
	void uneZoneTropLongueEstRefuseeMemeMince() {
		// Une ligne d'un bloc de large sur des milliers de blocs : peu de volume, mais des centaines de chunks à charger.
		assertDoesNotThrow(() -> MapEditor.checkSpan(MapEditor.MAX_SPAN, MapEditor.MAX_SPAN));
		GameException e = assertThrows(GameException.class, () -> MapEditor.checkSpan(1, MapEditor.MAX_SPAN + 1));
		assertTrue(e.getMessage().contains("trop longue"));
	}

	// ------------------------------------------------------------------
	// Fichiers de structure : rien hors des dimensions annoncées
	// ------------------------------------------------------------------
	private static ListTag ints(int... values) {
		ListTag l = new ListTag();
		for (int v : values) {
			l.add(IntTag.valueOf(v));
		}
		return l;
	}

	private static CompoundTag block(int x, int y, int z, int state) {
		CompoundTag b = new CompoundTag();
		b.put("pos", ints(x, y, z));
		b.putInt("state", state);
		return b;
	}

	private static CompoundTag structure(int w, int h, int l, int paletteSize, CompoundTag... blocks) {
		CompoundTag tag = new CompoundTag();
		tag.put("size", ints(w, h, l));
		ListTag palette = new ListTag();
		for (int i = 0; i < paletteSize; i++) {
			CompoundTag s = new CompoundTag();
			s.putString("Name", i == 0 ? "minecraft:air" : "minecraft:stone");
			palette.add(s);
		}
		tag.put("palette", palette);
		ListTag list = new ListTag();
		for (CompoundTag b : blocks) {
			list.add(b);
		}
		tag.put("blocks", list);
		return tag;
	}

	@Test
	void structureLueCaseParCase() {
		Clip.Raw raw = Clip.parse(structure(2, 1, 3, 2, block(0, 0, 0, 1), block(1, 0, 2, 1), block(1, 0, 0, 0)), 16, 16, 16);
		assertEquals(2, raw.width());
		assertEquals(1, raw.height());
		assertEquals(3, raw.length());
		// Cases rangées par x, puis z, puis y ; celles que le fichier ne cite pas restent vides.
		assertArrayEquals(new int[] {1, 0, Clip.EMPTY, Clip.EMPTY, Clip.EMPTY, 1}, raw.cells());
	}

	@Test
	void unBlocDeclareHorsDesDimensionsFaitRefuserLeFichier() {
		// Le piège : 1 × 1 × 1 annoncé, et un bloc à cent mille cases de là.
		CompoundTag trap = structure(1, 1, 1, 2, block(0, 0, 0, 1), block(100000, 0, 0, 1));
		GameException e = assertThrows(GameException.class, () -> Clip.parse(trap, 128, 128, 128));
		assertTrue(e.getMessage().contains("hors de ses dimensions"), e.getMessage());
		assertThrows(GameException.class, () -> Clip.parse(structure(2, 2, 2, 2, block(0, -1, 0, 1)), 128, 128, 128));
		assertThrows(GameException.class, () -> Clip.parse(structure(2, 2, 2, 2, block(0, 2, 0, 1)), 128, 128, 128));
		CompoundTag noPos = new CompoundTag();
		noPos.putInt("state", 1);
		assertThrows(GameException.class, () -> Clip.parse(structure(2, 2, 2, 2, noPos), 128, 128, 128));
	}

	@Test
	void tailleEtPaletteSontControlees() {
		assertThrows(GameException.class, () -> Clip.parse(structure(129, 1, 1, 2), 128, 128, 128), "trop large");
		assertThrows(GameException.class, () -> Clip.parse(structure(1, 29, 1, 2), 401, 28, 401), "trop haute pour une carte");
		assertThrows(GameException.class, () -> Clip.parse(structure(0, 1, 1, 2), 128, 128, 128), "vide");
		assertThrows(GameException.class, () -> Clip.parse(new CompoundTag(), 128, 128, 128), "sans dimensions");
		assertThrows(GameException.class, () -> Clip.parse(structure(1, 1, 1, 0), 128, 128, 128), "sans palette");
		assertThrows(GameException.class, () -> Clip.parse(structure(1, 1, 1, 2, block(0, 0, 0, 2)), 128, 128, 128),
			"état absent de la palette");
		assertThrows(GameException.class, () -> Clip.parse(structure(1, 1, 1, 2, block(0, 0, 0, -1)), 128, 128, 128));
		// Structure à plusieurs palettes (format des villages de Minecraft) : la première sert.
		CompoundTag multi = structure(1, 1, 1, 2, block(0, 0, 0, 1));
		ListTag palettes = new ListTag();
		palettes.add(multi.getListOrEmpty("palette"));
		multi.remove("palette");
		multi.put("palettes", palettes);
		assertEquals(2, Clip.parse(multi, 128, 128, 128).palette().size());
	}

	@Test
	void lesCommandesAuClicSontRetireesDesTextes() {
		// Une pancarte dont le texte lance une commande quand on clique dessus.
		CompoundTag click = new CompoundTag();
		click.putString("action", "run_command");
		click.putString("command", "/op intrus");
		CompoundTag line = new CompoundTag();
		line.putString("text", "Cliquez ici");
		line.put("click_event", click);
		CompoundTag old = new CompoundTag();
		old.putString("text", "ancien format");
		old.put("clickEvent", click.copy());
		ListTag messages = new ListTag();
		messages.add(line);
		messages.add(old);
		CompoundTag front = new CompoundTag();
		front.put("messages", messages);
		CompoundTag sign = new CompoundTag();
		sign.put("front_text", front);
		Clip.stripClicks(sign);
		assertFalse(sign.toString().contains("run_command"), sign.toString());
		assertTrue(sign.toString().contains("Cliquez ici"), "le texte, lui, reste");
		assertTrue(sign.toString().contains("ancien format"));
	}

	// ------------------------------------------------------------------
	// Fiches du catalogue
	// ------------------------------------------------------------------
	@Test
	void ficheDeStructureRameneeDansSesBornes() {
		StructureStore.Def d = new StructureStore.Def();
		d.id = "ma-tour";
		d.nom = "  Ma tour  ";
		d.repere = "n'importe quoi";
		d.taille = new int[] {5, 9, 7};
		d.ancre = new int[] {40, 0, 3}; // hors de la structure
		StructureStore.checked(d);
		assertEquals("Ma tour", d.nom);
		assertEquals("", d.repere);
		assertArrayEquals(new int[] {2, 0, 3}, d.ancre, "ancre ramenée au milieu de la base");
		d.ancre = new int[] {4, 9, 6};
		d.repere = "maison";
		StructureStore.checked(d);
		assertArrayEquals(new int[] {4, 9, 6}, d.ancre, "une ancre dans la structure est gardée");
		assertEquals("maison", d.repere);
		d.nom = null;
		assertEquals("ma-tour", StructureStore.checked(d).nom);
		d.nom = "x".repeat(51);
		assertThrows(GameException.class, () -> StructureStore.checked(d));
		d.nom = "ok";
		d.id = "../evasion";
		assertThrows(GameException.class, () -> StructureStore.checked(d), "jamais de chemin dans un identifiant");
	}

	@Test
	void tailleDUneStructureDuCatalogue() {
		assertDoesNotThrow(() -> StructureStore.checkSize(64, 64, 64));
		assertThrows(GameException.class, () -> StructureStore.checkSize(StructureStore.MAX_SIDE + 1, 1, 1));
		assertThrows(GameException.class, () -> StructureStore.checkSize(1, StructureStore.MAX_HEIGHT + 1, 1));
		assertThrows(GameException.class, () -> StructureStore.checkSize(128, 128, 128), "trop de blocs, même si chaque côté passe");
		assertThrows(GameException.class, () -> StructureStore.checkSize(0, 4, 4));
	}

	@Test
	void identifiantTireDuNomDuFichier() {
		assertEquals("ma-tour", StructureStore.slug("Ma Tour.schem"));
		assertEquals("eglise-st-jean", StructureStore.slug("Église  St-Jean!.litematic"));
		assertEquals("structure", StructureStore.slug("_.nbt"));
		assertTrue(StructureStore.slug("a".repeat(80) + ".nbt").length() <= 30);
	}
}
