package fr.valcombe.client.hd;

/**
 * Un style de texte : une face et une taille en unités d'interface (la taille réelle en pixels vaut
 * taille x échelle d'interface). Les styles courants sont nommés ici pour que tous les écrans se ressemblent.
 */
public record HdStyle(FontLadder.Face face, float size) {
	/** Bandeau de titre d'un écran. */
	public static final HdStyle TITRE = new HdStyle(FontLadder.Face.TITRE, 13);
	/** Grand titre (écran d'accueil, annonce). */
	public static final HdStyle GRAND_TITRE = new HdStyle(FontLadder.Face.TITRE, 20);
	/** Intertitre d'une section dans un panneau. */
	public static final HdStyle SECTION = new HdStyle(FontLadder.Face.TITRE, 10);
	/** Texte courant. */
	public static final HdStyle TEXTE = new HdStyle(FontLadder.Face.TEXTE, 11);
	/** Texte appuyé (libellés, noms). */
	public static final HdStyle FORT = new HdStyle(FontLadder.Face.FORT, 11);
	/** Libellé de bouton. */
	public static final HdStyle BOUTON = new HdStyle(FontLadder.Face.FORT, 11);
	/** Petites mentions : légendes, noms sous les jetons, aides. */
	public static final HdStyle PETIT = new HdStyle(FontLadder.Face.TEXTE, 9);

	/** Le même style à une autre taille. */
	public HdStyle size(float newSize) {
		return new HdStyle(face, newSize);
	}
}
