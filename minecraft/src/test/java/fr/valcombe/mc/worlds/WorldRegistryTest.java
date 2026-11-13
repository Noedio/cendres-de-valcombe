package fr.valcombe.mc.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.valcombe.rules.GameException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Registre des mondes : identifiants, noms, fiches, lecture et écriture de mondes.json. */
class WorldRegistryTest {
	@TempDir
	Path tmp;

	/** Horloge qu'on avance à la main, pour dater les fiches. */
	private static final class ManualClock extends Clock {
		Instant now = Instant.parse("2026-11-13T09:00:00Z");

		void advance(Duration d) {
			now = now.plus(d);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private final ManualClock clock = new ManualClock();

	private Path file() {
		return tmp.resolve("valcombe").resolve("mondes.json");
	}

	private WorldRegistry registry() {
		WorldRegistry r = new WorldRegistry(file(), clock);
		r.load();
		return r;
	}

	@Test
	void slugsHaveNoAccentsAndStayShort() {
		assertEquals("chateau-d-ete", WorldRegistry.slug("Château d'Été"));
		assertEquals("soeurs-de-l-aube", WorldRegistry.slug("  Sœurs de l'Aube !  "));
		assertEquals("", WorldRegistry.slug("!!!"));
		assertEquals("", WorldRegistry.slug(null));
		String longSlug = WorldRegistry.slug("Une très longue soirée au village des cendres de Valcombe");
		assertTrue(longSlug.length() <= 30, longSlug);
		assertFalse(longSlug.endsWith("-"), longSlug);
		assertTrue(longSlug.matches(WorldRegistry.ID), longSlug);
	}

	@Test
	void validIdsFollowTheMapRule() {
		assertTrue(WorldRegistry.isValidId("partie-du-soir"));
		assertTrue(WorldRegistry.isValidId("a1"));
		assertFalse(WorldRegistry.isValidId("a"));
		assertFalse(WorldRegistry.isValidId("Partie"));
		assertFalse(WorldRegistry.isValidId("été"));
		assertFalse(WorldRegistry.isValidId("../monde"));
		assertFalse(WorldRegistry.isValidId("principal"), "réservé à /vc monde aller principal");
		assertFalse(WorldRegistry.isValidId(null));
	}

	@Test
	void createGivesUniqueIdsFromNames() {
		WorldRegistry r = registry();
		WorldEntry a = r.create(WorldKind.PARTIE, "La Soirée");
		WorldEntry b = r.create(WorldKind.ESSAI, "la soirée");
		WorldEntry c = r.create(WorldKind.ESSAI, "La soirée");
		assertEquals("la-soiree", a.id());
		assertEquals("la-soiree-2", b.id());
		assertEquals("la-soiree-3", c.id());
		assertEquals("La Soirée", a.nom());
		assertEquals(WorldKind.PARTIE, a.sorte());
		assertNull(a.carte());
		assertEquals("", a.etat());
		assertEquals(clock.now, a.cree());
		assertEquals(a.cree(), a.modifie());
		assertEquals("valcombe:la-soiree", a.dimension());
	}

	@Test
	void namesWithoutLettersUseTheKind() {
		WorldRegistry r = registry();
		assertEquals("atelier-carte", r.create(WorldKind.ATELIER_CARTE, "???").id());
		assertEquals("atelier-carte-2", r.create(WorldKind.ATELIER_CARTE, "« »").id());
		assertEquals("principal-2", r.create(WorldKind.PARTIE, "Principal").id(), "mot réservé : jamais un identifiant");
	}

	@Test
	void longNamesKeepIdsWithinThirtySigns() {
		WorldRegistry r = registry();
		String name = "Une partie au nom vraiment beaucoup trop long";
		WorldEntry first = r.create(WorldKind.PARTIE, name);
		for (int i = 0; i < 11; i++) {
			WorldEntry next = r.create(WorldKind.ESSAI, name);
			assertTrue(next.id().matches(WorldRegistry.ID), next.id());
		}
		assertTrue(first.id().length() <= 30);
		assertNotNull(r.find(first.id().substring(0, 27).replaceAll("-+$", "") + "-12"));
	}

	@Test
	void hubAndStructureWorkshopAreUnique() {
		WorldRegistry r = registry();
		r.create(WorldKind.HUB, "Accueil");
		r.create(WorldKind.ATELIER_STRUCTURES, "Structures");
		GameException hub = assertThrows(GameException.class, () -> r.create(WorldKind.HUB, "Autre accueil"));
		assertTrue(hub.getMessage().contains("Accueil"), hub.getMessage());
		assertThrows(GameException.class, () -> r.create(WorldKind.ATELIER_STRUCTURES, "Encore"));
		// Plusieurs parties, essais et ateliers de carte, autant qu'on veut.
		r.create(WorldKind.PARTIE, "Une");
		r.create(WorldKind.PARTIE, "Deux");
		r.create(WorldKind.ATELIER_CARTE, "Carte A");
		r.create(WorldKind.ATELIER_CARTE, "Carte B");
		assertEquals(6, r.list().size());
	}

	@Test
	void badNamesAreRefused() {
		WorldRegistry r = registry();
		assertThrows(GameException.class, () -> r.create(WorldKind.PARTIE, "   "));
		assertThrows(GameException.class, () -> r.create(WorldKind.PARTIE, null));
		assertThrows(GameException.class, () -> r.create(WorldKind.PARTIE, "x".repeat(51)));
		assertThrows(GameException.class, () -> r.create(WorldKind.PARTIE, "§cRouge"));
		assertThrows(GameException.class, () -> r.create(null, "Sans sorte"));
		assertEquals("Deux espaces", r.create(WorldKind.PARTIE, "  Deux    espaces ").nom());
		assertEquals(1, r.list().size(), "un nom refusé ne laisse pas de fiche");
	}

	@Test
	void renameKeepsTheIdAndDatesTheChange() {
		WorldRegistry r = registry();
		WorldEntry e = r.create(WorldKind.PARTIE, "Soirée");
		clock.advance(Duration.ofHours(2));
		WorldEntry renamed = r.rename("soiree", "Grande soirée");
		assertEquals("soiree", renamed.id());
		assertEquals("Grande soirée", renamed.nom());
		assertEquals(e.cree(), renamed.cree());
		assertEquals(e.cree().plus(Duration.ofHours(2)), renamed.modifie());
		assertEquals(renamed, r.get("soiree"));
		assertThrows(GameException.class, () -> r.rename("soiree", ""));
		GameException unknown = assertThrows(GameException.class, () -> r.rename("inconnu", "Nom"));
		assertTrue(unknown.getMessage().contains("inconnu"), unknown.getMessage());
	}

	@Test
	void mapAndStateAreKept() {
		WorldRegistry r = registry();
		r.create(WorldKind.ATELIER_CARTE, "Atelier du village");
		assertEquals("village-test", r.setCarte("atelier-du-village", "village-test").carte());
		assertThrows(GameException.class, () -> r.setCarte("atelier-du-village", "Pas Une Carte"));
		assertNull(r.setCarte("atelier-du-village", " ").carte());
		r.setCarte("atelier-du-village", "village-test");
		assertEquals("en pause", r.setEtat("atelier-du-village", "  en pause ").etat());
		assertThrows(GameException.class, () -> r.setEtat("atelier-du-village", "x".repeat(WorldRegistry.MAX_STATE + 1)));
		WorldEntry reread = registry().get("atelier-du-village");
		assertEquals("village-test", reread.carte());
		assertEquals("en pause", reread.etat());
	}

	@Test
	void findIgnoresCaseAndUnknownIdsFail() {
		WorldRegistry r = registry();
		r.create(WorldKind.PARTIE, "Soirée");
		assertNotNull(r.find(" SOIREE "));
		assertNull(r.find("autre"));
		assertNull(r.find(null));
		assertThrows(GameException.class, () -> r.get("autre"));
	}

	@Test
	void listPutsTheHubFirstThenByKindAndAge() {
		WorldRegistry r = registry();
		r.create(WorldKind.ATELIER_CARTE, "Atelier");
		clock.advance(Duration.ofMinutes(1));
		r.create(WorldKind.ESSAI, "Essai B");
		clock.advance(Duration.ofMinutes(1));
		r.create(WorldKind.ESSAI, "Essai A");
		clock.advance(Duration.ofMinutes(1));
		r.create(WorldKind.PARTIE, "Partie");
		r.create(WorldKind.HUB, "Hub");
		assertEquals(List.of("hub", "partie", "essai-b", "essai-a", "atelier"), r.list().stream().map(WorldEntry::id).toList());
		assertEquals(List.of("essai-b", "essai-a"), r.list(WorldKind.ESSAI).stream().map(WorldEntry::id).toList());
	}

	@Test
	void everyChangeIsWrittenAndReadBack() throws Exception {
		WorldRegistry r = registry();
		r.create(WorldKind.HUB, "Accueil");
		r.create(WorldKind.PARTIE, "Soirée du jeudi");
		r.setCarte("soiree-du-jeudi", "village");
		r.setEtat("soiree-du-jeudi", "en cours");
		WorldRegistry again = registry();
		assertEquals(r.list(), again.list());

		JsonObject root = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals(WorldRegistry.FORMAT, root.get("format").getAsInt());
		JsonObject partie = root.getAsJsonArray("mondes").get(1).getAsJsonObject();
		assertEquals("soiree-du-jeudi", partie.get("id").getAsString());
		assertEquals("Soirée du jeudi", partie.get("nom").getAsString());
		assertEquals("PARTIE", partie.get("sorte").getAsString());
		assertEquals("village", partie.get("carte").getAsString());
		assertEquals("2026-11-13T09:00:00Z", partie.get("cree").getAsString());
		assertEquals("en cours", partie.get("etat").getAsString());
		assertTrue(root.getAsJsonArray("aEffacer").isEmpty());
		assertFalse(root.getAsJsonArray("mondes").get(0).getAsJsonObject().has("carte"), "pas de carte : pas de champ");
	}

	@Test
	void removeForgetsTheEntry() {
		WorldRegistry r = registry();
		WorldEntry e = r.create(WorldKind.ESSAI, "Essai");
		assertEquals(e, r.remove("essai"));
		assertNull(r.find("essai"));
		assertNull(registry().find("essai"));
		assertThrows(GameException.class, () -> r.remove("essai"));
	}

	@Test
	void pendingWipesSurviveARestartAndReserveTheirId() {
		WorldRegistry r = registry();
		r.create(WorldKind.ESSAI, "Essai");
		r.markWipe("essai");
		r.remove("essai");
		WorldRegistry again = registry();
		assertEquals(List.of("essai"), again.wipes());
		// Tant que ses fichiers ne sont pas effacés, l'identifiant n'est pas redonné.
		assertEquals("essai-2", again.create(WorldKind.ESSAI, "Essai").id());
		again.clearWipe("essai");
		assertTrue(registry().wipes().isEmpty());
		r.markWipe("../hors-du-dossier");
		assertTrue(registry().wipes().isEmpty(), "un identifiant mal formé n'est jamais noté");
	}

	@Test
	void damagedEntriesAreSkipped() throws Exception {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), """
			{"format": 1, "mondes": [
			  {"id": "bonne", "nom": "Bonne", "sorte": "PARTIE", "cree": "2026-11-01T10:00:00Z", "modifie": "2026-11-02T10:00:00Z"},
			  {"id": "Mauvaise Id", "nom": "x", "sorte": "PARTIE"},
			  {"id": "sans-sorte", "nom": "x", "sorte": "VOLCAN"},
			  {"id": "bonne", "nom": "Double", "sorte": "ESSAI"},
			  {"id": "ancienne", "sorte": "atelier-carte", "carte": "Pas Valide", "cree": "pas une date"},
			  42
			], "aEffacer": ["vieux", "../x", 3]}
			""", StandardCharsets.UTF_8);
		WorldRegistry r = registry();
		assertEquals(List.of("bonne", "ancienne"), r.list().stream().map(WorldEntry::id).toList());
		WorldEntry bonne = r.get("bonne");
		assertEquals(Instant.parse("2026-11-01T10:00:00Z"), bonne.cree());
		assertEquals(Instant.parse("2026-11-02T10:00:00Z"), bonne.modifie());
		WorldEntry ancienne = r.get("ancienne");
		assertEquals("ancienne", ancienne.nom(), "sans nom : l'identifiant");
		assertEquals(WorldKind.ATELIER_CARTE, ancienne.sorte());
		assertNull(ancienne.carte());
		assertEquals(Instant.EPOCH, ancienne.cree());
		assertEquals(List.of("vieux"), r.wipes());
	}

	@Test
	void backupIsUsedWhenTheFileIsDamaged() throws Exception {
		WorldRegistry r = registry();
		r.create(WorldKind.PARTIE, "Première");
		r.create(WorldKind.ESSAI, "Seconde"); // la version à une seule fiche devient la copie de secours
		Files.writeString(file(), "{ cassé", StandardCharsets.UTF_8);
		assertEquals(List.of("premiere"), registry().list().stream().map(WorldEntry::id).toList());
	}

	@Test
	void unreadableFileIsSetAsideNotOverwritten() throws Exception {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "pas du json", StandardCharsets.UTF_8);
		WorldRegistry r = registry();
		assertTrue(r.list().isEmpty());
		Path aside = file().resolveSibling("mondes.json.illisible");
		assertEquals("pas du json", Files.readString(aside, StandardCharsets.UTF_8));
		r.create(WorldKind.PARTIE, "Nouvelle");
		assertEquals("pas du json", Files.readString(aside, StandardCharsets.UTF_8));
		assertEquals(1, registry().list().size());
	}

	@Test
	void missingFileIsAnEmptyRegistry() {
		WorldRegistry r = registry();
		assertTrue(r.list().isEmpty());
		assertTrue(r.wipes().isEmpty());
		assertFalse(Files.exists(file()), "rien n'est écrit tant que rien ne change");
	}

	@Test
	void kindsAreReadFromCommandsAndFiles() {
		assertEquals(WorldKind.ATELIER_CARTE, WorldKind.parse("atelier-carte"));
		assertEquals(WorldKind.ATELIER_CARTE, WorldKind.parse("ATELIER_CARTE"));
		assertEquals(WorldKind.ATELIER_CARTE, WorldKind.parse("carte"));
		assertEquals(WorldKind.ATELIER_STRUCTURES, WorldKind.parse("Structures"));
		assertEquals(WorldKind.ESSAI, WorldKind.parse(" essai "));
		assertEquals(WorldKind.HUB, WorldKind.parse("accueil"));
		assertNull(WorldKind.parse("volcan"));
		assertNull(WorldKind.parse(null));
		for (WorldKind k : WorldKind.values()) {
			assertEquals(k, WorldKind.parse(k.code()));
			assertEquals(k, WorldKind.parse(k.name()));
		}
		assertTrue(WorldKind.ATELIER_CARTE.workshop() && WorldKind.ATELIER_STRUCTURES.workshop());
		assertFalse(WorldKind.HUB.workshop() || WorldKind.PARTIE.workshop() || WorldKind.ESSAI.workshop());
	}
}
