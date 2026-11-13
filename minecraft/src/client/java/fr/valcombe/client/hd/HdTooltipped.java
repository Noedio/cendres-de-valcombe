package fr.valcombe.client.hd;

/**
 * Un élément d'écran qui a une infobulle haute résolution. HdScreen l'affiche après un court survol (ou quand
 * l'élément a le focus du clavier), au-dessus de tout le reste.
 */
public interface HdTooltipped {
	/** Le texte de l'infobulle, ou null s'il n'y en a pas. */
	String hdTooltip();
}
