package fr.valcombe.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pouvoir d'un rôle créé dans l'Atelier : un modèle de la bibliothèque et ses réglages,
 * et jusqu'à deux effets supplémentaires (« aussi ») qui s'ajoutent au premier.
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
	/**
	 * Une seule fois par partie : chaque nuit, le joueur peut garder son pouvoir (valider sans désigner
	 * personne) ; une fois utilisé, il ne se réveille plus. Ne se règle que sur le premier effet.
	 */
	public boolean uneFois;
	/** Effets supplémentaires du même rôle (deux au plus), appliqués après le premier. */
	public List<Ability> aussi = new ArrayList<>();

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
		MODELS.put("grimoire", "Grimoire : il consulte le grimoire du Conteur");
		MODELS.put("protection", "Protection : le joueur désigné ne peut pas mourir cette nuit");
		MODELS.put("mise_a_mort", "Mise à mort (Démon) : le joueur désigné meurt");
		MODELS.put("poison", "Poison : le pouvoir du joueur désigné ne fonctionne pas");
		MODELS.put("fausse_info", "Fausse information : le joueur désigné est trompé cette nuit");
		MODELS.put("maitre", "Maître : il désigne un maître, et ne vote que si son maître vote");
		MODELS.put("tir", "Tir, de jour, une fois par partie : si le joueur désigné est…, il meurt");
		MODELS.put("represailles", "Représailles : la première fois qu'il est accusé, l'accusateur est exécuté s'il est…");
		MODELS.put("heritier", "Héritier : si le Démon meurt à 5 vivants ou plus, il devient le Démon");
		MODELS.put("semblant", "Faux-semblant : il peut passer pour un joueur de l'autre camp");
		MODELS.put("immunite", "Immunité : le Démon ne peut pas le tuer");
		MODELS.put("etrangers", "Effet permanent : Étrangers en plus ou en moins");
		MODELS.put("martyr", "Martyr : s'il est exécuté, le mal gagne");
		MODELS.put("libre", "Pouvoir libre : le Conteur répond lui-même");
	}

	/** Cibles possibles pour la paire, le détecteur, le tir et les représailles. */
	public static final List<String> TARGETS = List.of("citadin", "etranger", "sbire", "demon", "mauvais");

	/** Tous les effets du rôle : le premier, puis les supplémentaires. */
	public List<Ability> all() {
		List<Ability> out = new ArrayList<>();
		out.add(this);
		if (aussi != null) {
			out.addAll(aussi);
		}
		return out;
	}

	/** L'effet de ce modèle parmi ceux du rôle, ou null. */
	public Ability effect(String model) {
		for (Ability a : all()) {
			if (a.type.equals(model)) {
				return a;
			}
		}
		return null;
	}

	public boolean has(String model) {
		return effect(model) != null;
	}

	/** Cet effet se joue-t-il au réveil, la nuit ? */
	private boolean wakesAlone() {
		return switch (type) {
			case "aucun", "immunite", "etrangers", "martyr", "tir", "represailles", "heritier", "semblant" -> false;
			default -> true;
		};
	}

	/** Le rôle se réveille-t-il la nuit (au moins un de ses effets) ? */
	public boolean wakes() {
		return all().stream().anyMatch(Ability::wakesAlone);
	}

	private int chooseAlone() {
		return switch (type) {
			case "compte", "detecteur" -> clamp(nombre, 1, 3);
			case "libre" -> clamp(nombre, 0, 3);
			case "decouvrir", "protection", "mise_a_mort", "poison", "fausse_info", "maitre" -> 1;
			default -> 0;
		};
	}

	/** Joueurs à désigner au réveil : le plus grand nombre demandé par un effet. */
	public int choose() {
		return all().stream().mapToInt(Ability::chooseAlone).max().orElse(0);
	}

	/** Joueurs que cet effet-ci utilise, parmi ceux désignés au réveil. */
	int targetsUsed() {
		return chooseAlone();
	}

	boolean playedAtNight() {
		return wakesAlone();
	}

	public boolean notSelf() {
		return all().stream().anyMatch(a -> (a.type.equals("protection") && !a.soiAutorise) || a.type.equals("maitre"));
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** Pour reg() : « townsfolk », « outsider »… */
	String regKey() {
		return switch (cible == null ? "demon" : cible) {
			case "citadin" -> "townsfolk";
			case "etranger" -> "outsider";
			case "sbire" -> "minion";
			case "mauvais" -> "evil";
			default -> "demon";
		};
	}

	/** Type de rôle visé par une paire. */
	RoleType pairType() {
		return switch (cible == null ? "citadin" : cible) {
			case "etranger" -> RoleType.ETRANGER;
			case "sbire" -> RoleType.SBIRE;
			case "demon" -> RoleType.DEMON;
			default -> RoleType.CITADIN;
		};
	}

	/** Problème de réglage pour ce camp, ou null. */
	public String problem(RoleType camp) {
		List<Ability> extras = aussi == null ? List.of() : aussi;
		if (extras.size() > 2) {
			return "Deux effets supplémentaires au plus.";
		}
		String p = problemAlone(camp);
		if (p != null) {
			return p;
		}
		for (Ability a : extras) {
			if (a == null || a.type == null) {
				return "Effet supplémentaire incomplet.";
			}
			if (a.aussi != null && !a.aussi.isEmpty()) {
				return "Un effet supplémentaire ne peut pas en porter d'autres.";
			}
			if (a.type.equals("aucun") || a.type.equals("libre")) {
				return "« " + MODELS.getOrDefault(a.type, a.type) + " » ne s'ajoute pas à un autre effet.";
			}
			if (a.uneFois) {
				return "« Une seule fois par partie » se règle sur le premier effet.";
			}
			if (a.type.equals(type) || extras.stream().filter(x -> x != null && a.type.equals(x.type)).count() > 1) {
				return "Chaque effet ne peut figurer qu'une fois.";
			}
			p = a.problemAlone(camp);
			if (p != null) {
				return p;
			}
			if (a.chooseAlone() > chooseAlone()) {
				return "Mettez en premier l'effet qui fait désigner le plus de joueurs : les autres s'appliquent aux mêmes joueurs.";
			}
		}
		if (type.equals("aucun") && !extras.isEmpty()) {
			return "Choisissez un premier effet avant d'en ajouter d'autres.";
		}
		if (camp == RoleType.DEMON && !has("mise_a_mort") && !type.equals("libre")) {
			return "Un Démon doit tuer : choisissez la mise à mort (ou un pouvoir libre, joué par le Conteur).";
		}
		if (uneFois && chooseAlone() == 0) {
			return "« Une seule fois par partie » est réservé aux pouvoirs où le joueur désigne quelqu'un.";
		}
		if (uneFois && camp == RoleType.DEMON) {
			return "Un Démon tue chaque nuit : pas de pouvoir à usage unique.";
		}
		return null;
	}

	private String problemAlone(RoleType camp) {
		if (!MODELS.containsKey(type)) {
			return "Pouvoir inconnu : " + type + ".";
		}
		boolean aims = type.equals("paire") || type.equals("detecteur") || type.equals("tir") || type.equals("represailles");
		if (aims && !TARGETS.contains(cible)) {
			return "Cible inconnue : " + cible + ".";
		}
		if (type.equals("paire") && cible.equals("mauvais")) {
			return "La paire vise un type de rôle (citadin, étranger, sbire ou démon), pas « mauvais ».";
		}
		if (type.equals("mise_a_mort") && camp != RoleType.DEMON) {
			return "Seul un Démon peut avoir la mise à mort.";
		}
		if (type.equals("etrangers") && (etrangers < -2 || etrangers > 2 || etrangers == 0)) {
			return "Étrangers en plus ou en moins : entre -2 et +2, sans 0.";
		}
		if (type.equals("etrangers") && !camp.evil()) {
			return "L'effet sur les Étrangers est réservé aux Sbires et aux Démons (il joue sur le tirage).";
		}
		if (type.equals("heritier") && camp != RoleType.SBIRE) {
			return "Seul un Sbire peut hériter du Démon.";
		}
		if (type.equals("libre") && (question == null || question.isBlank())) {
			return "Pouvoir libre : écrivez ce que le Conteur doit faire.";
		}
		return null;
	}
}
