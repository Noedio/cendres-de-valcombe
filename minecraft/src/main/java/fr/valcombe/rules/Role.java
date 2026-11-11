package fr.valcombe.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Les 22 rôles du script « Les Cendres de Valcombe ».
 * first / other : rang dans l'ordre de réveil (0 = ne se réveille pas).
 */
public enum Role {
	// Citadins
	COMMERE("La Commère", RoleType.CITADIN, 5, 0, 0, false,
		"La première nuit, tu apprends que l'un de deux joueurs est un Citadin précis."),
	GREFFIER("Le Greffier", RoleType.CITADIN, 6, 0, 0, false,
		"La première nuit, tu apprends que l'un de deux joueurs est un Étranger précis, ou qu'il n'y a aucun Étranger."),
	INQUISITEUR("L'Inquisiteur", RoleType.CITADIN, 7, 0, 0, false,
		"La première nuit, tu apprends que l'un de deux joueurs est un Sbire précis."),
	AUBERGISTE("L'Aubergiste", RoleType.CITADIN, 8, 0, 0, false,
		"La première nuit, tu apprends combien de paires de joueurs mauvais sont assis côte à côte."),
	VEILLEUSE("La Veilleuse", RoleType.CITADIN, 9, 6, 0, false,
		"Chaque nuit, tu apprends combien de tes deux voisins vivants sont mauvais."),
	ASTROLOGUE("L'Astrologue", RoleType.CITADIN, 10, 7, 2, false,
		"Chaque nuit, désigne deux joueurs : tu apprends si l'un d'eux est le Démon. Un joueur du bien passe pour le Démon à tes yeux."),
	EMBAUMEUR("L'Embaumeur", RoleType.CITADIN, 0, 8, 0, false,
		"Chaque nuit sauf la première, tu apprends le rôle du joueur exécuté dans la journée."),
	CHAPELAIN("Le Chapelain", RoleType.CITADIN, 0, 2, 1, true,
		"Chaque nuit sauf la première, désigne un autre joueur : le Démon ne peut pas l'atteindre cette nuit."),
	FAUCONNIER("Le Fauconnier", RoleType.CITADIN, 0, 5, 1, false,
		"Si tu meurs la nuit, tu te réveilles pour désigner un joueur et apprendre son rôle."),
	PELERINE("La Pèlerine", RoleType.CITADIN, 0, 0, 0, false,
		"La première fois que tu es nominée, si le nominateur est un Citadin, il est exécuté sur-le-champ."),
	ARBALETRIER("L'Arbalétrier", RoleType.CITADIN, 0, 0, 0, false,
		"Une fois par partie, de jour, désigne publiquement un joueur : s'il est le Démon, il meurt."),
	TEMPLIER("Le Templier", RoleType.CITADIN, 0, 0, 0, false,
		"Le Démon ne peut pas te tuer."),
	CHATELAIN("Le Châtelain", RoleType.CITADIN, 0, 0, 0, false,
		"S'il ne reste que 3 vivants et qu'aucune exécution n'a lieu, le bien gagne. Si tu meurs la nuit, un autre joueur peut mourir à ta place."),
	// Citadins des Brumes de Valcombe
	SONNEUR("Le Sonneur", RoleType.CITADIN, 0, 7, 2, false,
		"Chaque nuit sauf la première, désigne deux joueurs : tu apprends combien d'entre eux sont mauvais."),
	CARTOGRAPHE("Le Cartographe", RoleType.CITADIN, 8, 0, 0, false,
		"La première nuit, tu apprends à combien de sièges de toi se trouve le Démon, par le plus court côté du cercle."),
	// Étrangers
	ECUYER("L'Écuyer", RoleType.ETRANGER, 11, 9, 1, true,
		"Chaque nuit, choisis un maître (pas toi) : demain, tu ne peux voter que si ton maître vote aussi."),
	SOMNAMBULE("Le Somnambule", RoleType.ETRANGER, 0, 0, 0, false,
		"Tu crois être un Citadin, mais tu ne l'es pas."),
	EXCOMMUNIE("L'Excommunié", RoleType.ETRANGER, 0, 0, 0, false,
		"Tu peux passer pour mauvais, Sbire ou Démon, même mort."),
	MARTYR("Le Martyr", RoleType.ETRANGER, 0, 0, 0, false,
		"Si tu meurs exécuté, ton équipe perd."),
	// Sbires
	ALCHIMISTE("L'Alchimiste", RoleType.SBIRE, 3, 1, 1, false,
		"Chaque nuit, désigne un joueur : il est empoisonné cette nuit et demain."),
	MOUCHARD("Le Mouchard", RoleType.SBIRE, 4, 10, 0, false,
		"Chaque nuit, tu consultes le grimoire. Tu peux passer pour bon, Citadin ou Étranger, même mort."),
	HERITIERE("L'Héritière", RoleType.SBIRE, 0, 3, 0, false,
		"S'il reste au moins 5 vivants et que le Démon meurt, tu deviens le Démon."),
	MARGRAVE("Le Margrave", RoleType.SBIRE, 0, 0, 0, false,
		"Deux Étrangers de plus sont en jeu."),
	// Sbire des Brumes de Valcombe
	FAUSSAIRE("La Faussaire", RoleType.SBIRE, 3, 1, 1, false,
		"Chaque nuit, désigne un joueur : s'il reçoit une information cette nuit, elle est fausse."),
	// Démon
	GOULE("La Goule", RoleType.DEMON, 0, 4, 1, false,
		"Chaque nuit sauf la première, désigne un joueur : il meurt. Si tu te désignes, un Sbire devient la Goule.");

	public final String displayName;
	public final RoleType type;
	public final int firstNight;
	public final int otherNights;
	public final int choose;
	public final boolean notSelf;
	public final String text;

	Role(String displayName, RoleType type, int firstNight, int otherNights, int choose, boolean notSelf, String text) {
		this.displayName = displayName;
		this.type = type;
		this.firstNight = firstNight;
		this.otherNights = otherNights;
		this.choose = choose;
		this.notSelf = notSelf;
		this.text = text;
	}

	public boolean evil() {
		return type.evil();
	}

	/** Rôles ajoutés par le script des Brumes (absents du script classique). */
	private static final java.util.Set<Role> BRUMES_ONLY = java.util.EnumSet.of(SONNEUR, CARTOGRAPHE, FAUSSAIRE);

	/** Le script classique : les 22 rôles des Cendres de Valcombe. */
	public static java.util.EnumSet<Role> classic() {
		java.util.EnumSet<Role> s = java.util.EnumSet.allOf(Role.class);
		s.removeAll(BRUMES_ONLY);
		return s;
	}

	/** Le script des Brumes : informations plus troubles et une Faussaire parmi les Sbires. */
	public static java.util.EnumSet<Role> brumes() {
		java.util.EnumSet<Role> s = classic();
		s.removeAll(java.util.EnumSet.of(COMMERE, AUBERGISTE, ALCHIMISTE));
		s.addAll(BRUMES_ONLY);
		return s;
	}

	public static List<Role> ofType(RoleType t) {
		return Arrays.stream(values()).filter(r -> r.type == t).toList();
	}

	/** Rôles qui se réveillent la première nuit, dans l'ordre. */
	public static List<Role> firstNightOrder() {
		return sortedBy(r -> r.firstNight);
	}

	/** Rôles qui se réveillent les nuits suivantes, dans l'ordre. */
	public static List<Role> otherNightsOrder() {
		return sortedBy(r -> r.otherNights);
	}

	private static List<Role> sortedBy(java.util.function.ToIntFunction<Role> f) {
		List<Role> out = new ArrayList<>();
		for (Role r : values()) {
			if (f.applyAsInt(r) > 0) {
				out.add(r);
			}
		}
		out.sort(Comparator.comparingInt(f));
		return out;
	}
}
