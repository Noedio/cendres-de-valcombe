package fr.valcombe.mc;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Pile d'annulation à plusieurs niveaux, avec sa pile « rétablir ». Chaque élément a un poids (le
 * nombre de blocs qu'il garde en mémoire) : au-delà d'un nombre de niveaux ou d'un poids total,
 * les plus anciens sont oubliés.
 * <p>
 * C'est la pile d'une seule sorte de gestes (les structures du catalogue, dans MapEditor). L'« annuler » commun, qui
 * défait dans l'ordre les modifications WorldEdit et les gestes de Valcombe, est {@link SharedUndo}.
 */
final class History<T> {
	private record Entry<T>(T item, long weight) {
	}

	private final int depth;
	private final long maxWeight;
	private final Deque<Entry<T>> undo = new ArrayDeque<>();
	private final Deque<Entry<T>> redo = new ArrayDeque<>();

	History(int depth, long maxWeight) {
		this.depth = depth;
		this.maxWeight = maxWeight;
	}

	/** Une nouvelle action vient d'être faite : elle pourra être annulée, et il n'y a plus rien à rétablir. */
	void record(T item, long weight) {
		redo.clear();
		undo.addLast(new Entry<>(item, weight));
		trim();
	}

	/** La dernière action à annuler, retirée de la pile ; null s'il n'y en a pas. */
	T takeUndo() {
		Entry<T> e = undo.pollLast();
		return e == null ? null : e.item();
	}

	/** La dernière action annulée, retirée de la pile ; null s'il n'y en a pas. */
	T takeRedo() {
		Entry<T> e = redo.pollLast();
		return e == null ? null : e.item();
	}

	/** Ce qu'il faut pour rétablir l'action qu'on vient d'annuler. */
	void putRedo(T item, long weight) {
		redo.addLast(new Entry<>(item, weight));
		trim();
	}

	/** Ce qu'il faut pour annuler de nouveau l'action qu'on vient de rétablir (la pile « rétablir » reste). */
	void putUndo(T item, long weight) {
		undo.addLast(new Entry<>(item, weight));
		trim();
	}

	int undoable() {
		return undo.size();
	}

	int redoable() {
		return redo.size();
	}

	long weight() {
		long w = 0;
		for (Entry<T> e : undo) {
			w += e.weight();
		}
		for (Entry<T> e : redo) {
			w += e.weight();
		}
		return w;
	}

	void clear() {
		undo.clear();
		redo.clear();
	}

	/** Oublie les plus anciennes actions tant qu'il y en a trop ; la toute dernière reste toujours. */
	private void trim() {
		while (undo.size() > depth) {
			undo.pollFirst();
		}
		while (redo.size() > depth) {
			redo.pollFirst();
		}
		while (weight() > maxWeight && undo.size() + redo.size() > 1) {
			// D'abord les annulations les plus anciennes, puis les plus anciens pas à rétablir ; s'il ne reste qu'un pas
			// de chaque côté, on garde celui à rétablir : c'est ce qu'on vient d'annuler.
			if (undo.size() > 1 || redo.size() <= 1 && !undo.isEmpty()) {
				undo.pollFirst();
			} else {
				redo.pollFirst();
			}
		}
	}
}
