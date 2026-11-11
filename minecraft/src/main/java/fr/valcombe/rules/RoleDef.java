package fr.valcombe.rules;

import java.util.Locale;

/**
 * Fiche d'un rôle créé dans l'Atelier, telle qu'elle est enregistrée (JSON) et partagée.
 * Les noms des champs sont ceux du fichier.
 */
public final class RoleDef {
	/** Identifiant court, unique (lettres, chiffres, tirets). */
	public String id = "";
	public String nom = "";
	/** CITADIN, ETRANGER, SBIRE ou DEMON. */
	public String camp = "CITADIN";
	public String texte = "";
	/** Emblème : pictogramme d'un rôle intégré (« goule ») ou de la bibliothèque (« e_lune »). */
	public String embleme = "e_etoile";
	/** Réveil la première nuit : « jamais », « debut », « fin », « avant:ROLE » ou « apres:ROLE ». */
	public String premiereNuit = "jamais";
	/** Réveil les autres nuits (mêmes valeurs). */
	public String autresNuits = "jamais";
	public Ability pouvoir = new Ability();
	/** Emplacement attribué (1 à 40), stable pour garder les sauvegardes lisibles. */
	public int emplacement;

	public RoleType type() {
		try {
			return RoleType.valueOf(camp.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException | NullPointerException e) {
			return RoleType.CITADIN;
		}
	}

	/** Clé de tri du réveil (0 = ne se réveille pas). */
	double wakeKey(String when, boolean first) {
		if (when == null || when.isBlank() || when.equals("jamais")) {
			return 0;
		}
		double tie = emplacement * 0.001;
		if (when.equals("debut")) {
			return 0.5 + tie;
		}
		if (when.equals("fin")) {
			return 90 + tie;
		}
		String[] p = when.split(":", 2);
		Role anchor = p.length == 2 ? Role.parse(p[1]) : null;
		if (anchor == null || anchor.slot) {
			return 90 + tie;
		}
		double rank = first ? anchor.firstNight : anchor.otherNights;
		if (rank <= 0) {
			return 90 + tie;
		}
		return (p[0].equals("avant") ? rank - 0.5 : rank + 0.5) + tie;
	}

	/** Problème de la fiche, ou null si elle est jouable. */
	public String problem() {
		if (id == null || !id.matches("[a-z0-9-]{2,30}")) {
			return "Identifiant : 2 à 30 lettres minuscules, chiffres ou tirets.";
		}
		if (nom == null || nom.isBlank() || nom.length() > 40) {
			return "Le nom est obligatoire (40 caractères au plus).";
		}
		Role same = Role.parse(id);
		if (same != null && !same.slot) {
			return "Cet identifiant est celui d'un rôle intégré.";
		}
		if (texte == null || texte.isBlank()) {
			return "Décrivez le pouvoir en une phrase : c'est ce que lira le joueur.";
		}
		Ability a = pouvoir == null ? new Ability() : pouvoir;
		String p = a.problem(type());
		if (p != null) {
			return p;
		}
		if (a.wakes() && "jamais".equals(premiereNuit) && "jamais".equals(autresNuits)) {
			return "Ce pouvoir se joue la nuit : choisissez quand le rôle se réveille.";
		}
		return null;
	}
}
