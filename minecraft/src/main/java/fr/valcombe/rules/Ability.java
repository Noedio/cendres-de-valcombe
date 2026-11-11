package fr.valcombe.rules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pouvoir d'un rôle créé dans l'Atelier : un modèle de la bibliothèque et ses réglages.
 * Les noms des champs sont ceux du fichier de création (JSON).
 */
public final class Ability {
	/** Modèle : voir MODELS. */
	public String type = "aucun";
	/** Ce que l'on cherche : citadin, etranger, sbire, demon, mauvais. */
	public String cible = "demon";
	/** Joueurs à désigner (1 à 3) pour les modèles qui en demandent. */
	public int nombre = 1;
	/** Détecteur de Démon : un joueur du bien passe pour le Démon (fausse piste). */
	public boolean faussePiste;
	/** Effet permanent : Étrangers en plus (positif) ou en moins (négatif). */
	public int etrangers;
	/** Pouvoir libre : ce que le Conteur doit faire ou répondre. */
	public String question = "";
	/** Protection : le joueur peut se désigner lui-même. */
	public boolean soiAutorise;

	/** Modèles de pouvoir : identifiant → libellé (pour l'Atelier). */
	public static final Map<String, String> MODELS = new LinkedHashMap<>();

	static {
		MODELS.put("aucun", "Aucun pouvoir (rôle d'ambiance)");
		MODELS.put("paire", "Paire : « l'un de ces deux joueurs est tel rôle »");
		MODELS.put("voisins", "Compte des mauvais parmi ses deux voisins vivants");
		MODELS.put("compte", "Compte des mauvais parmi des joueurs désignés");
		MODELS.put("detecteur", "Détecteur : l'un des joueurs désignés est-il… ?");
		MODELS.put("adjacents", "Paires de mauvais assis côte à côte");
		MODELS.put("distance", "Distance du Démon, en sièges");
		MODELS.put("execute", "Rôle du joueur exécuté la veille");
		MODELS.put("decouvrir", "Rôle d'un joueur désigné");
		MODELS.put("protection", "Protection : le joueur désigné ne peut pas mourir cette nuit");
		MODELS.put("mise_a_mort", "Mise à mort (Démon) : le joueur désigné meurt");
		MODELS.put("poison", "Poison : le pouvoir du joueur désigné ne fonctionne pas");
		MODELS.put("fausse_info", "Fausse information : le joueur désigné est trompé cette nuit");
		MODELS.put("immunite", "Immunité : le Démon ne peut pas le tuer");
		MODELS.put("etrangers", "Effet permanent : Étrangers en plus ou en moins");
		MODELS.put("martyr", "Martyr : s'il est exécuté, le mal gagne");
		MODELS.put("libre", "Pouvoir libre : le Conteur répond lui-même");
	}

	/** Cibles possibles pour la paire et le détecteur. */
	public static final List<String> TARGETS = List.of("citadin", "etranger", "sbire", "demon", "mauvais");

	public boolean wakes() {
		return switch (type) {
			case "aucun", "immunite", "etrangers", "martyr" -> false;
			default -> true;
		};
	}

	/** Joueurs à désigner au réveil. */
	public int choose() {
		return switch (type) {
			case "compte", "detecteur" -> clamp(nombre, 1, 3);
			case "libre" -> clamp(nombre, 0, 3);
			case "decouvrir", "protection", "mise_a_mort", "poison", "fausse_info" -> 1;
			default -> 0;
		};
	}

	public boolean notSelf() {
		return type.equals("protection") && !soiAutorise;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** Pour reg() : « townsfolk », « outsider »… */
	String regKey() {
		return switch (cible) {
			case "citadin" -> "townsfolk";
			case "etranger" -> "outsider";
			case "sbire" -> "minion";
			case "mauvais" -> "evil";
			default -> "demon";
		};
	}

	/** Type de rôle visé par une paire. */
	RoleType pairType() {
		return switch (cible) {
			case "etranger" -> RoleType.ETRANGER;
			case "sbire" -> RoleType.SBIRE;
			case "demon" -> RoleType.DEMON;
			default -> RoleType.CITADIN;
		};
	}

	/** Problème de réglage pour ce camp, ou null. */
	public String problem(RoleType camp) {
		if (!MODELS.containsKey(type)) {
			return "Pouvoir inconnu : " + type + ".";
		}
		if ((type.equals("paire") || type.equals("detecteur")) && !TARGETS.contains(cible)) {
			return "Cible inconnue : " + cible + ".";
		}
		if (type.equals("paire") && cible.equals("mauvais")) {
			return "La paire vise un type de rôle (citadin, étranger, sbire ou démon), pas « mauvais ».";
		}
		if (type.equals("mise_a_mort") && camp != RoleType.DEMON) {
			return "Seul un Démon peut avoir la mise à mort.";
		}
		if (camp == RoleType.DEMON && !type.equals("mise_a_mort") && !type.equals("libre")) {
			return "Un Démon doit tuer : choisissez la mise à mort (ou un pouvoir libre, joué par le Conteur).";
		}
		if (type.equals("etrangers") && (etrangers < -2 || etrangers > 2 || etrangers == 0)) {
			return "Étrangers en plus ou en moins : entre -2 et +2, sans 0.";
		}
		if (type.equals("etrangers") && !camp.evil()) {
			return "L'effet sur les Étrangers est réservé aux Sbires et aux Démons (il joue sur le tirage).";
		}
		if (type.equals("libre") && (question == null || question.isBlank())) {
			return "Pouvoir libre : écrivez ce que le Conteur doit faire.";
		}
		return null;
	}
}
