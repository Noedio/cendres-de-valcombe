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
		for (int n = 5; n <= 15; n++) {
			if (Setup.check(s, n) == null) {
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
			return "Ce script ne permet aucune partie de 5 à 15 joueurs : ajoutez des rôles (au moins un Démon).";
		}
		if (reglages.defense < 5 || reglages.defense > 180 || reglages.horloge < 0.5 || reglages.horloge > 5) {
			return "Réglages hors limites : défense de 5 à 180 s, aiguille de 0,5 à 5 s par siège.";
		}
		if (regles.discussionMinutes < 0 || regles.discussionMinutes > 30) {
			return "Discussion libre : de 0 à 30 minutes.";
		}
		return null;
	}
}
