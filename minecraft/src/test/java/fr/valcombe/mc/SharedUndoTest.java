package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.valcombe.mc.SharedUndo.Journal;
import fr.valcombe.mc.SharedUndo.Stack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * L'« annuler » commun : le journal range dans l'ordre les gestes de WorldEdit (par joueur) et ceux de Valcombe
 * (structures, repères), et se recale sur les piles quand on les défait directement (//undo, /vc carte annuler).
 */
class SharedUndoTest {
	private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

	/** Une session WorldEdit pour rire : ses modifications sont des objets qu'on reconnaît par identité. */
	private static final class Session {
		final List<Object> history = new ArrayList<>();
		int pointer;

		Object edit() {
			while (history.size() > pointer) {
				history.remove(history.size() - 1); // une modification nouvelle efface ce qu'on pouvait rétablir
			}
			Object o = new Object();
			history.add(o);
			pointer++;
			return o;
		}

		void undo() {
			pointer--;
		}

		void redo() {
			pointer++;
		}

		List<Object> done() {
			return List.copyOf(history.subList(0, pointer));
		}
	}

	@Test
	void lesGestesSontDefaitsDansLOrdreOuIlsOntEteFaits() {
		Journal j = new Journal();
		Session we = new Session();
		we.edit();
		j.syncWorldEdit(ME, we.done());            // //set
		j.syncCount(Stack.STRUCTURES, 1);          // une structure posée
		we.edit();
		j.syncWorldEdit(ME, we.done());            // //walls
		j.syncCount(Stack.MARKERS, 1);             // un trône posé
		assertEquals(List.of(Stack.WORLDEDIT, Stack.STRUCTURES, Stack.WORLDEDIT, Stack.MARKERS), j.order());

		assertEquals(Stack.MARKERS, j.nextUndo(ME, true).stack());
		j.undid(j.nextUndo(ME, true));
		j.syncCount(Stack.MARKERS, 0);
		Journal.Entry e = j.nextUndo(ME, true);
		assertEquals(Stack.WORLDEDIT, e.stack());
		assertSame(we.done().get(1), e.token(), "la dernière modification WorldEdit, pas la première");
		j.undid(e);
		we.undo();
		j.syncWorldEdit(ME, we.done());
		assertEquals(Stack.STRUCTURES, j.nextUndo(ME, true).stack());
		assertEquals(2, j.undoable(ME, true));
		assertEquals(1, j.redoable(ME, true), "un repère annulé ne se rétablit pas : seule la modification WorldEdit");
	}

	@Test
	void unJoueurNeDefaitQueSesGestesWorldEditEtCeuxDeValcombeSIlEnALeDroit() {
		Journal j = new Journal();
		Session mine = new Session();
		Session theirs = new Session();
		mine.edit();
		j.syncWorldEdit(ME, mine.done());
		j.syncCount(Stack.STRUCTURES, 1);
		theirs.edit();
		j.syncWorldEdit(OTHER, theirs.done());
		assertEquals(Stack.STRUCTURES, j.nextUndo(ME, true).stack(), "la modification de l'autre joueur n'est pas à moi");
		assertEquals(Stack.WORLDEDIT, j.nextUndo(ME, false).stack(), "sans droit sur Valcombe : mes gestes WorldEdit");
		assertEquals(1, j.undoable(ME, false));
		assertEquals(1, j.undoable(OTHER, false));
		assertEquals(2, j.undoable(OTHER, true));
	}

	@Test
	void unGesteDefaitDirectementSortDuJournal() {
		Journal j = new Journal();
		Session we = new Session();
		we.edit();
		we.edit();
		j.syncWorldEdit(ME, we.done());
		j.syncCount(Stack.MARKERS, 2);
		// //undo tapé à la main, et /vc carte annuler : le journal suit.
		we.undo();
		j.syncWorldEdit(ME, we.done());
		j.syncCount(Stack.MARKERS, 1);
		assertEquals(List.of(Stack.WORLDEDIT, Stack.MARKERS), j.order());
		assertEquals(0, j.redoable(ME, true), "ce qui est défait ailleurs se rétablit ailleurs (//redo)");
		// //redo à la main : la modification revient, à la fin du journal.
		we.redo();
		j.syncWorldEdit(ME, we.done());
		assertEquals(List.of(Stack.WORLDEDIT, Stack.MARKERS, Stack.WORLDEDIT), j.order());
	}

	@Test
	void retablirPuisUnGesteNouveauVideCeQuOnPouvaitRetablir() {
		Journal j = new Journal();
		Session we = new Session();
		we.edit();
		j.syncWorldEdit(ME, we.done());
		j.syncCount(Stack.STRUCTURES, 1);
		// Annuler deux fois : la structure, puis la modification WorldEdit.
		Journal.Entry s = j.nextUndo(ME, true);
		j.undid(s);
		j.syncCount(Stack.STRUCTURES, 0);
		Journal.Entry w = j.nextUndo(ME, true);
		j.undid(w);
		we.undo();
		j.syncWorldEdit(ME, we.done());
		assertNull(j.nextUndo(ME, true));
		assertEquals(2, j.redoable(ME, true));
		// Rétablir : d'abord la dernière annulée (WorldEdit), rendue par sa session.
		Journal.Entry r = j.nextRedo(ME, true);
		assertEquals(Stack.WORLDEDIT, r.stack());
		j.redoing(r);
		we.redo();
		j.syncWorldEdit(ME, we.done());
		assertEquals(1, j.redoable(ME, true), "la structure reste à rétablir");
		// Puis la structure : sa pile la rend, ce n'est pas un geste nouveau.
		Journal.Entry r2 = j.nextRedo(ME, true);
		assertEquals(Stack.STRUCTURES, r2.stack());
		j.redoing(r2);
		j.syncCount(Stack.STRUCTURES, 1);
		assertEquals(List.of(Stack.WORLDEDIT, Stack.STRUCTURES), j.order());
		assertEquals(0, j.redoable(ME, true));
		// Annuler la structure, puis faire autre chose : plus rien à rétablir.
		j.undid(j.nextUndo(ME, true));
		j.syncCount(Stack.STRUCTURES, 0);
		assertEquals(1, j.redoable(ME, true));
		we.edit();
		j.syncWorldEdit(ME, we.done());
		assertEquals(0, j.redoable(ME, true));
		assertFalse(j.order().contains(Stack.STRUCTURES));
	}

	@Test
	void desStructuresAnnuleesALaSuiteRestentARetablirPendantQueLeurPileEnchaine() {
		Journal j = new Journal();
		j.syncCount(Stack.STRUCTURES, 1); // un puits
		j.syncCount(Stack.STRUCTURES, 2); // un banc
		// /vc annuler 2 : le journal défait les deux d'un coup ; la pile défait le banc tout de suite, et le puits à la
		// fin du chantier du banc.
		List<Journal.Entry> run = j.lastRun(ME, true, 2, false);
		assertEquals(2, run.size());
		run.forEach(j::undid);
		j.syncCount(Stack.STRUCTURES, 1);
		assertEquals(2, j.redoable(ME, true), "l'écart d'un chantier en cours n'est pas un geste nouveau");
		assertEquals(0, j.undoable(ME, true));
		j.syncCount(Stack.STRUCTURES, 0);
		assertEquals(2, j.redoable(ME, true));
		assertEquals(List.of(), j.order());
		// Une structure posée ensuite est bien un geste nouveau : plus rien à rétablir.
		j.syncCount(Stack.STRUCTURES, 1);
		assertEquals(0, j.redoable(ME, true));
		assertEquals(List.of(Stack.STRUCTURES), j.order());
	}

	@Test
	void unRetablirRefuseNAttendPlusRien() {
		Journal j = new Journal();
		j.syncCount(Stack.STRUCTURES, 1);
		Journal.Entry e = j.nextUndo(ME, true);
		j.undid(e);
		j.syncCount(Stack.STRUCTURES, 0);
		j.redoing(e);
		j.redoFailed(e); // la pile a refusé (chantier en cours)
		j.syncCount(Stack.STRUCTURES, 1); // un geste nouveau, plus tard
		assertEquals(0, j.redoable(ME, true));
		assertEquals(1, j.undoable(ME, true));
	}

	@Test
	void desStructuresALaSuiteSeDefontDUnCoup() {
		Journal j = new Journal();
		Session we = new Session();
		j.syncCount(Stack.STRUCTURES, 1);
		we.edit();
		j.syncWorldEdit(ME, we.done());
		j.syncCount(Stack.STRUCTURES, 3);
		assertEquals(List.of(Stack.STRUCTURES, Stack.WORLDEDIT, Stack.STRUCTURES, Stack.STRUCTURES), j.order());
		List<Journal.Entry> run = j.lastRun(ME, true, 40, false);
		assertEquals(2, run.size(), "la suite s'arrête à ma modification WorldEdit");
		assertEquals(3, j.lastRun(OTHER, true, 40, false).size(), "la modification d'un autre joueur ne coupe pas sa suite");
		assertEquals(1, j.lastRun(ME, true, 1, false).size());
		assertEquals(Stack.WORLDEDIT, j.lastRun(ME, false, 40, false).get(0).stack(), "sans droit sur Valcombe");
		// Deux poses de structure se ressemblent, mais c'est bien la plus récente qui part.
		j.undid(run.get(0));
		j.syncCount(Stack.STRUCTURES, 2);
		assertEquals(List.of(Stack.STRUCTURES, Stack.WORLDEDIT, Stack.STRUCTURES), j.order());
	}

	@Test
	void laReponseDUnePileSeGlisseDansUnePhrase() {
		assertEquals("pose de « Puits couvert »", SharedUndo.clean("Annulé : pose de « Puits couvert »."));
		assertEquals("modification des repères (16 trônes, 16 maisons)",
			SharedUndo.clean("Dernière modification des repères annulée (16 trônes, 16 maisons)."));
		assertEquals("modification WorldEdit (27 blocs)", SharedUndo.clean("modification WorldEdit (27 blocs)"));
		assertTrue(SharedUndo.clean("Rétabli : rotation.").equals("rotation"));
	}

	/**
	 * Dans un atelier carte, les piles de Valcombe sont communes : un invité n'annule que ses propres gestes, et pas
	 * ceux qu'un geste d'un autre recouvre ; un opérateur annule tout.
	 */
	@Test
	void unInviteNAnnuleQueSesPropresGestes() {
		UUID op = OTHER;
		UUID guest = ME;
		Journal j = new Journal();
		j.syncCount(Stack.STRUCTURES, 1, List.of(guest)); // l'invité pose une structure
		assertEquals(Stack.STRUCTURES, j.nextUndo(guest, true, false).stack(), "sa pose est à lui");
		assertEquals(guest, j.nextUndo(guest, true, false).player());
		j.syncCount(Stack.STRUCTURES, 2, List.of(op)); // puis l'opérateur en pose une autre par-dessus
		assertNull(j.nextUndo(guest, true, false), "la pose de l'opérateur est au sommet : l'invité ne défait rien");
		assertEquals(0, j.undoable(guest, true, false));
		assertTrue(j.lastRun(guest, true, false, 5, false).isEmpty());
		assertEquals(2, j.undoable(op, true, true), "l'opérateur annule tout");
		assertEquals(op, j.nextUndo(op, true, true).player());
		// L'opérateur défait la sienne : celle de l'invité redevient à sa portée, pas la rétablir de l'opérateur.
		j.undid(j.nextUndo(op, true, true));
		j.syncCount(Stack.STRUCTURES, 1, List.of());
		assertEquals(guest, j.nextUndo(guest, true, false).player());
		assertNull(j.nextRedo(guest, true, false), "rétablir la pose de l'opérateur n'est pas à l'invité");
		assertEquals(op, j.nextRedo(op, true, true).player());
		// Rétablie, la pose garde son auteur.
		Journal.Entry redo = j.nextRedo(op, true, true);
		j.redoing(redo);
		j.syncCount(Stack.STRUCTURES, 2, List.of(guest)); // un auteur noté entre-temps ne la lui donne pas
		assertEquals(op, j.nextUndo(op, true, true).player(), "rétablie, la pose reste à l'opérateur");
		assertNull(j.nextUndo(guest, true, false));
		// Un repère sans auteur connu (fait par la console) : seul un opérateur le défait.
		Journal k = new Journal();
		k.syncCount(Stack.MARKERS, 1, java.util.Collections.singletonList(null));
		assertNull(k.nextUndo(guest, true, false));
		assertEquals(Stack.MARKERS, k.nextUndo(op, true, true).stack());
		// Plus d'auteurs notés que de gestes nouveaux (la pile a oublié ses plus anciens) : les derniers comptent.
		Journal l = new Journal();
		l.syncCount(Stack.MARKERS, 1, List.of(op, guest));
		assertEquals(guest, l.nextUndo(guest, true, false).player());
	}

	/**
	 * Les repères ont une pile par monde (chaque partie, chaque atelier carte) : un geste fait dans un autre monde ne
	 * recouvre pas ceux de l'invité, et un invité ne défait jamais d'ici le repère d'un autre monde.
	 */
	@Test
	void lesReperesDUnAutreMondeNeComptentPas() {
		UUID op = OTHER;
		UUID guest = ME;
		Journal j = new Journal();
		j.syncCount(Stack.MARKERS, "atelier-a", 1, List.of(guest)); // l'invité pose un trône dans l'atelier A
		j.syncCount(Stack.MARKERS, "partie-1", 1, List.of(op)); // puis le Conteur règle un repère dans sa partie
		assertEquals(guest, j.nextUndo(guest, true, false, "atelier-a").player(),
			"le repère de la partie ne recouvre pas celui de l'invité dans son atelier");
		assertEquals(1, j.undoable(guest, true, false, "atelier-a"));
		assertEquals(List.of(Stack.MARKERS), j.lastRun(guest, true, false, "atelier-a", 5, false).stream().map(Journal.Entry::stack).toList());
		// Dans l'atelier B, où un opérateur a posé le dernier repère, l'invité ne défait rien (pas même son repère de A).
		j.syncCount(Stack.MARKERS, "atelier-b", 1, List.of(op));
		assertNull(j.nextUndo(guest, true, false, "atelier-b"));
		assertTrue(j.lastRun(guest, true, false, "atelier-b", 5, false).isEmpty());
		// Un opérateur, lui, défait le dernier repère du monde où il se tient, pas celui d'un autre monde.
		assertEquals("atelier-a", j.nextUndo(op, true, true, "atelier-a").place());
		// Le repère défait directement dans un monde (bouton de l'Atelier) sort du journal pour ce monde seulement.
		j.syncCount(Stack.MARKERS, "partie-1", 0, List.of());
		assertEquals(2, j.order().size());
		assertEquals(guest, j.nextUndo(guest, true, false, "atelier-a").player());
		// Un monde fermé : ses repères sortent du journal.
		assertTrue(j.places(Stack.MARKERS).containsAll(List.of("atelier-a", "atelier-b")));
		j.syncCount(Stack.MARKERS, "atelier-b", 0, List.of());
		assertEquals(java.util.Set.of("atelier-a"), j.places(Stack.MARKERS));
	}

	@Test
	void lesGestesWorldEditDUnAutreNeBloquentPas() {
		Journal j = new Journal();
		Session mine = new Session();
		Session theirs = new Session();
		j.syncCount(Stack.STRUCTURES, 1, List.of(ME));
		mine.edit();
		j.syncWorldEdit(ME, mine.done());
		theirs.edit();
		j.syncWorldEdit(OTHER, theirs.done());
		// Les sessions WorldEdit sont à chacun : celle de l'autre ne recouvre rien ; ma structure reste à ma portée.
		assertEquals(2, j.undoable(ME, true, false));
		assertEquals(List.of(Stack.WORLDEDIT), j.lastRun(ME, true, false, 5, false).stream().map(Journal.Entry::stack).toList());
	}
}
