package fr.valcombe.client.hd;

import java.util.function.Predicate;

/**
 * Le texte d'un champ de saisie et son curseur : sélection, insertion, effacement, déplacements par caractère
 * et par mot, longueur maximale, filtre. Les positions comptent des caractères Java ; une paire de substitution
 * (émoji) n'est jamais coupée. Aucune dépendance à Minecraft : testé par JUnit.
 */
public final class TextEditor {
	private String text = "";
	private int cursor;
	/** L'autre bout de la sélection (égal au curseur quand rien n'est sélectionné). */
	private int anchor;
	private int maxLength = 256;
	private Predicate<String> filter = s -> true;

	public String text() {
		return text;
	}

	public int cursor() {
		return cursor;
	}

	public int anchor() {
		return anchor;
	}

	public int maxLength() {
		return maxLength;
	}

	public TextEditor maxLength(int max) {
		this.maxLength = Math.max(0, max);
		if (text.length() > maxLength) {
			setText(text.substring(0, maxLength));
		}
		return this;
	}

	/** N'accepte que les textes qui passent ce filtre (par exemple : chiffres seulement). */
	public TextEditor filter(Predicate<String> accept) {
		this.filter = accept == null ? s -> true : accept;
		return this;
	}

	/** Remplace tout le texte (tronqué à la longueur maximale) et met le curseur au bout. */
	public void setText(String value) {
		String v = value == null ? "" : value;
		if (v.length() > maxLength) {
			v = v.substring(0, safeCut(v, maxLength));
		}
		text = v;
		cursor = anchor = text.length();
	}

	public boolean hasSelection() {
		return cursor != anchor;
	}

	public int selectionStart() {
		return Math.min(cursor, anchor);
	}

	public int selectionEnd() {
		return Math.max(cursor, anchor);
	}

	public String selected() {
		return text.substring(selectionStart(), selectionEnd());
	}

	public void selectAll() {
		anchor = 0;
		cursor = text.length();
	}

	/** Place le curseur ; select garde l'autre bout de la sélection, sinon la sélection est vidée. */
	public void moveTo(int position, boolean select) {
		cursor = snap(Math.max(0, Math.min(text.length(), position)));
		if (!select) {
			anchor = cursor;
		}
	}

	/** Déplace le curseur d'un caractère (direction -1 ou 1), ou d'un mot. Sans sélection étendue, une flèche
	 * posée sur une sélection se contente de la refermer du bon côté, comme partout ailleurs. */
	public void move(int direction, boolean byWord, boolean select) {
		if (!select && hasSelection() && !byWord) {
			moveTo(direction < 0 ? selectionStart() : selectionEnd(), false);
			return;
		}
		moveTo(byWord ? wordPosition(direction) : step(cursor, direction), select);
	}

	public void home(boolean select) {
		moveTo(0, select);
	}

	public void end(boolean select) {
		moveTo(text.length(), select);
	}

	/**
	 * Insère du texte à la place de la sélection. Les retours à la ligne et caractères de contrôle sont retirés ;
	 * ce qui dépasse la longueur maximale est coupé. Faux si le filtre refuse le résultat (rien ne change).
	 */
	public boolean insert(String raw) {
		StringBuilder clean = new StringBuilder();
		(raw == null ? "" : raw).codePoints().filter(c -> c >= 32 && c != 127).forEach(clean::appendCodePoint);
		int start = selectionStart();
		int end = selectionEnd();
		int room = maxLength - (text.length() - (end - start));
		String add = clean.toString();
		if (add.length() > room) {
			add = add.substring(0, safeCut(add, Math.max(0, room)));
		}
		String next = text.substring(0, start) + add + text.substring(end);
		if (!filter.test(next)) {
			return false;
		}
		text = next;
		cursor = anchor = start + add.length();
		return true;
	}

	/** Efface la sélection, ou le caractère (ou le mot) avant (direction -1) ou après (1) le curseur. */
	public boolean delete(int direction, boolean byWord) {
		if (hasSelection()) {
			return insert("");
		}
		int other = byWord ? wordPosition(direction) : step(cursor, direction);
		if (other == cursor) {
			return false;
		}
		int start = Math.min(cursor, other);
		int end = Math.max(cursor, other);
		String next = text.substring(0, start) + text.substring(end);
		if (!filter.test(next)) {
			return false;
		}
		text = next;
		cursor = anchor = start;
		return true;
	}

	/** Position du début du mot précédent (direction -1) ou de la fin du mot suivant (1). */
	public int wordPosition(int direction) {
		int i = cursor;
		if (direction < 0) {
			while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
				i--;
			}
			while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) {
				i--;
			}
		} else {
			int n = text.length();
			while (i < n && Character.isWhitespace(text.charAt(i))) {
				i++;
			}
			while (i < n && !Character.isWhitespace(text.charAt(i))) {
				i++;
			}
		}
		return i;
	}

	/** Un caractère plus loin, sans couper une paire de substitution. */
	private int step(int from, int direction) {
		if (direction < 0) {
			if (from <= 0) {
				return 0;
			}
			int i = from - 1;
			return i > 0 && Character.isLowSurrogate(text.charAt(i)) && Character.isHighSurrogate(text.charAt(i - 1)) ? i - 1 : i;
		}
		if (from >= text.length()) {
			return text.length();
		}
		int i = from + 1;
		return i < text.length() && Character.isLowSurrogate(text.charAt(i)) && Character.isHighSurrogate(text.charAt(i - 1)) ? i + 1 : i;
	}

	/** Ramène une position au début d'une paire de substitution si elle tombe au milieu. */
	private int snap(int position) {
		if (position > 0 && position < text.length() && Character.isLowSurrogate(text.charAt(position))
				&& Character.isHighSurrogate(text.charAt(position - 1))) {
			return position - 1;
		}
		return position;
	}

	/** Longueur de coupe qui ne sépare pas une paire de substitution. */
	private static int safeCut(String s, int length) {
		if (length > 0 && length < s.length() && Character.isHighSurrogate(s.charAt(length - 1))) {
			return length - 1;
		}
		return length;
	}
}
