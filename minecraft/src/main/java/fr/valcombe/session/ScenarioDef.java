package fr.valcombe.session;

import fr.valcombe.rules.Role;
import fr.valcombe.rules.Setup;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Scénario de soirée créé dans l'Atelier : les rôles, la carte, les réglages, les règles
 * spéciales et le texte d'ouverture. Les noms des champs sont ceux du fichier (JSON).
 */
public final class ScenarioDef {
	public String id = "";
	public String nom = "";
	/** Résumé affiché dans le salon et l'Atelier. */
	public String description = "";
	/** Texte lu à tous au début de la partie (l'ambiance). */
	public String ouverture = "";
	/** Rôles disponibles : noms intégrés (« goule ») ou identifiants de rôles créés. */
	public List<String> roles = new ArrayList<>();
	/** Carte de la bibliothèque à charger (vide : la carte actuelle). */
	public String carte = "";
	public Reglages reglages = new Reglages();
	public Regles regles = new Regles();

	/** Réglages de partie (ceux du Conteur). */
	public static final class Reglages {
		/** Toutes les accusations d'abord, puis les votes dans l'ordre (sans défense). */
		public boolean conseil = true;
		/** Durée du Conseil en secondes (0 : le Conteur passe aux votes). */
		public int dureeConseil = 0;
		public int defense = 20;
		/** Secondes de l'aiguille par siège. */
		public double horloge = 1.5;
		public boolean nuitMaisons = true;
		public boolean nuitImmobile = true;
		public boolean nuitSombre = true;
		public boolean nuitMuette = true;
		/** chapeau, crane ou aucun. */
		public String coiffe = "chapeau";
		public boolean effetsComplets = true;
	}

	/** Règles spéciales, à cocher. */
	public static final class Regles {
		/** Pas d'accusation le premier jour. */
		public boolean pasAccusationPremierJour;
		/** Discussion libre minutée au début de chaque jour (0 : aucune). */
		public int discussionMinutes;
		/** Les annonces de rôle sont interdites. */
		public boolean annoncesInterdites;
		/** Les conversations privées à deux sont interdites. */
		public boolean apartesInterdits;
		/** Figures neutres : sentinelle, doyen, sablier. */
		public List<String> figures = new ArrayList<>();
		/** Les morts n'ont pas de vote de fantôme. */
		public boolean mortsSansVote;
		/** Accusations par jour (0 : sans limite). */
		public int accusationsMax;
		/** Les chuchotements écrits sont interdits. */
		public boolean chuchotementsInterdits;
		/** Le rôle d'un joueur est révélé à tous quand il meurt. */
		public boolean roleReveleALaMort;
		/** Exécution seulement à la majorité absolue des vivants (au lieu de la moitié). */
		public boolean majoriteAbsolue;
		/** Le mal gagne dès qu'il est majoritaire parmi les vivants. */
		public boolean victoireMalMajoritaire;
		/** L'Héritière (Femme écarlate) devient le Démon sans seuil de 5 vivants. */
		public boolean heritiereSansSeuil;
		/** Les Sbires et le Démon se connaissent dès 5 joueurs (au lieu de 7). */
		public boolean infosDuMalDes5;
		/** Rôles toujours en jeu (dans la limite des places de leur type). */
		public List<String> imposes = new ArrayList<>();
		/** Répartition propre : nombre de joueurs → [Citadins, Étrangers, Sbires, Démons]. */
		public java.util.Map<String, List<Integer>> repartition = new java.util.LinkedHashMap<>();
		/** Ordre de réveil choisi (identifiants de rôles) ; vide : l'ordre habituel. */
		public List<String> ordrePremiere = new ArrayList<>();
		public List<String> ordreAutres = new ArrayList<>();
	}

	/** Les rôles toujours en jeu. */
	public Set<Role> forced() {
		Set<Role> out = EnumSet.noneOf(Role.class);
		if (regles.imposes != null) {
			for (String r : regles.imposes) {
				Role role = Role.parse(r);
				if (role != null) {
					out.add(role);
				}
			}
		}
		return out;
	}

	/** La répartition propre du scénario pour n joueurs, ou null (répartition habituelle). */
	public fr.valcombe.rules.Distribution.Counts counts(int players) {
		List<Integer> c = regles.repartition == null ? null : regles.repartition.get(String.valueOf(players));
		if (c == null || c.size() != 4 || c.stream().anyMatch(java.util.Objects::isNull)) {
			return null;
		}
		return new fr.valcombe.rules.Distribution.Counts(c.get(0), c.get(1), c.get(2), c.get(3));
	}

	/** Un ordre de réveil choisi, en rôles (les identifiants inconnus sont ignorés). */
	public List<Role> order(boolean first) {
		List<Role> out = new ArrayList<>();
		List<String> ids = first ? regles.ordrePremiere : regles.ordreAutres;
		if (ids != null) {
			for (String id : ids) {
				Role r = Role.parse(id);
				if (r != null && !out.contains(r)) {
					out.add(r);
				}
			}
		}
		return out;
	}

	/** Les rôles du scénario (les rôles inconnus, par exemple supprimés, sont ignorés). */
	public Set<Role> script() {
		Set<Role> out = EnumSet.noneOf(Role.class);
		for (String r : roles) {
			Role role = Role.parse(r);
			if (role != null) {
				out.add(role);
			}
		}
		return out;
	}

	/** Rôles cités mais introuvables. */
	public List<String> missingRoles() {
		return roles.stream().filter(r -> Role.parse(r) == null).toList();
	}

	/** Nombres de joueurs jouables avec ce script. */
	public List<Integer> playable() {
		List<Integer> out = new ArrayList<>();
		Set<Role> s = script();
		for (int n = fr.valcombe.rules.Distribution.MIN_PLAYERS; n <= fr.valcombe.rules.Distribution.MAX_PLAYERS; n++) {
			if (Setup.check(s, n, counts(n)) == null) {
				out.add(n);
			}
		}
		return out;
	}

	/** Problème bloquant, ou null. */
	public String problem() {
		if (id == null || !id.matches("[a-z0-9-]{2,30}")) {
			return "Identifiant : 2 à 30 lettres minuscules, chiffres ou tirets.";
		}
		if (nom == null || nom.isBlank() || nom.length() > 50) {
			return "Le nom est obligatoire (50 caractères au plus).";
		}
		if (!missingRoles().isEmpty()) {
			return "Rôles introuvables : " + String.join(", ", missingRoles()) + ".";
		}
		if (playable().isEmpty()) {
			return "Ce script ne permet aucune partie de 5 à 16 joueurs : ajoutez des rôles (au moins un Démon).";
		}
		if (regles.imposes != null) {
			Set<Role> in = script();
			for (String r : regles.imposes) {
				Role role = Role.parse(r);
				if (role == null || !in.contains(role)) {
					return "Rôle imposé absent du scénario : " + r + ".";
				}
			}
		}
		if (regles.repartition != null) {
			for (var e : regles.repartition.entrySet()) {
				int n;
				try {
					n = Integer.parseInt(e.getKey());
				} catch (NumberFormatException ex) {
					return "Répartition : nombre de joueurs illisible (" + e.getKey() + ").";
				}
				List<Integer> c = e.getValue();
				if (c == null || c.size() != 4 || c.stream().anyMatch(java.util.Objects::isNull) || c.stream().anyMatch(x -> x < 0)
					|| c.stream().mapToInt(Integer::intValue).sum() != n || c.get(3) < 1) {
					return "Répartition à " + n + " joueurs : quatre nombres (Citadins, Étrangers, Sbires, Démons) dont la somme fait "
						+ n + ", avec au moins un Démon.";
				}
				if (c.get(2) + c.get(3) >= c.get(0) + c.get(1)) {
					return "Répartition à " + n + " joueurs : le mal doit rester minoritaire.";
				}
			}
		}
		if (regles.accusationsMax < 0 || regles.accusationsMax > 20) {
			return "Accusations par jour : de 0 (sans limite) à 20.";
		}
		if (reglages.defense < 5 || reglages.defense > 180 || reglages.horloge < 0.5 || reglages.horloge > 5) {
			return "Réglages hors limites : défense de 5 à 180 s, aiguille de 0,5 à 5 s par trône.";
		}
		if (reglages.dureeConseil < 0 || reglages.dureeConseil > 1800) {
			return "Durée du Conseil : de 0 (à la main) à 1800 secondes.";
		}
		if (regles.discussionMinutes < 0 || regles.discussionMinutes > 30) {
			return "Discussion libre : de 0 à 30 minutes.";
		}
		return null;
	}
}
