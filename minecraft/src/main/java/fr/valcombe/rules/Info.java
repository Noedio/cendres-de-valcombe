package fr.valcombe.rules;

import java.util.List;

/** Information remise à un joueur réveillé. */
public final class Info {
	public enum Kind { NONE, PAIR_ROLE, NO_OUTSIDER, NUMBER, YESNO, ROLE, GRIMOIRE, TEAM, NEW_DEMON, STARPASS }

	public Kind kind = Kind.NONE;
	/** Texte montré au joueur (null = rien à montrer). */
	public String text;
	/** La vérité, pour le Conteur, quand elle diffère de ce qui est montré. */
	public String truthText;
	public List<Integer> players = List.of();
	public Role role;
	public int value;
	public boolean yes;
	public Integer truthValue;
	public Boolean truthYes;
	public Role truthRole;
	/** Siège du nouveau Démon après un sacrifice de la Goule, sinon -1. */
	public int newDemon = -1;

	static Info none() {
		return new Info();
	}
}
