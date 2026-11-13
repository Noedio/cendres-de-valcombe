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
		"Si le Démon meurt, tu deviens le Démon : s'il reste au moins 5 vivants, ou toujours avec la règle de la table."),
	MARGRAVE("Le Margrave", RoleType.SBIRE, 0, 0, 0, false,
		"Deux Étrangers de plus sont en jeu."),
	// Sbire des Brumes de Valcombe
	FAUSSAIRE("La Faussaire", RoleType.SBIRE, 3, 1, 1, false,
		"Chaque nuit, désigne un joueur : s'il reçoit une information cette nuit, elle est fausse."),
	// Démon
	GOULE("La Goule", RoleType.DEMON, 0, 4, 1, false,
		"Chaque nuit sauf la première, désigne un joueur : il meurt. Si tu te désignes, un Sbire devient la Goule."),
	// Rôles de la table (la liste de règles des joueurs), absents des Cendres et des Brumes
	FLEURISTE("La Fleuriste", RoleType.CITADIN, 0, 7, 0, false,
		"Chaque nuit sauf la première, tu apprends si au moins un joueur du camp des Démons (Sbire ou Démon) a levé "
			+ "la main pendant le dernier Conseil.", "e_rose"),
	JONGLEUR("Le Jongleur", RoleType.CITADIN, 0, 7, 5, false,
		"La deuxième nuit, tu prêtes un rôle à cinq joueurs. Tu apprends combien de tes cinq hypothèses sont justes.",
		"e_masque"),
	BAVARD("Le Bavard", RoleType.ETRANGER, 0, 0, 0, false,
		"À la fin de chaque Conseil, tu dois confier au Conteur le rôle que tu prêtes à un joueur. Si tu as vu juste, "
			+ "tu meurs la nuit suivante.", "e_cloche"),
	SORCIERE("La Sorcière", RoleType.SBIRE, 3, 1, 1, false,
		"Chaque nuit, maudis un joueur : s'il est accusé au Conseil du lendemain, il meurt à la fin du Conseil, après les "
			+ "votes. Quand il ne reste que trois vivants, ton pouvoir s'éteint.", "e_serpent"),
	// Voyageur : joueur arrivé en cours de partie (hors script, jamais tiré au sort).
	VOYAGEUR("Le Voyageur", RoleType.CITADIN, 0, 0, 0, false,
		"Tu arrives à Valcombe en cours de partie, dans le camp que le Conteur t'a donné. Tu votes et tu peux "
			+ "accuser ; le village peut t'exiler, et tu peux repartir quand tu veux."),
	// Emplacements des rôles créés dans l'Atelier : remplis au démarrage du serveur (voir defineCustom).
	PERSO_01,
	PERSO_02,
	PERSO_03,
	PERSO_04,
	PERSO_05,
	PERSO_06,
	PERSO_07,
	PERSO_08,
	PERSO_09,
	PERSO_10,
	PERSO_11,
	PERSO_12,
	PERSO_13,
	PERSO_14,
	PERSO_15,
	PERSO_16,
	PERSO_17,
	PERSO_18,
	PERSO_19,
	PERSO_20,
	PERSO_21,
	PERSO_22,
	PERSO_23,
	PERSO_24,
	PERSO_25,
	PERSO_26,
	PERSO_27,
	PERSO_28,
	PERSO_29,
	PERSO_30,
	PERSO_31,
	PERSO_32,
	PERSO_33,
	PERSO_34,
	PERSO_35,
	PERSO_36,
	PERSO_37,
	PERSO_38,
	PERSO_39,
	PERSO_40;

	private static List<RoleDef> customDefs = List.of();

	/** Les fiches des rôles créés actuellement chargés. */
	public static List<RoleDef> customDefs() {
		return customDefs;
	}

	/** Nombre d'emplacements pour les rôles créés. */
	public static final int CUSTOM_SLOTS = 40;

	public String displayName;
	public RoleType type;
	public int firstNight;
	public int otherNights;
	public int choose;
	public boolean notSelf;
	public String text;
	/** Emblème du rôle (pictogramme du mod : textures/gui/roles/<icon>.png). */
	public String icon;
	/** Emplacement de rôle créé (PERSO_xx). */
	public final boolean slot;
	/** Faux pour un emplacement vide : le rôle n'existe pas. */
	public boolean active;
	/** Identifiant de la création (rôles créés), sinon null. */
	public String customId;
	/** Pouvoir d'un rôle créé (modèle de la bibliothèque), sinon null. */
	public Ability ability;
	/** Clés de tri du réveil : le rang pour les rôles intégrés, une position intercalée pour les rôles créés. */
	public double firstKey;
	public double otherKey;

	Role(String displayName, RoleType type, int firstNight, int otherNights, int choose, boolean notSelf, String text) {
		this(displayName, type, firstNight, otherNights, choose, notSelf, text, null);
	}

	/** icon : emblème de la bibliothèque (« e_rose »…) pour un rôle intégré sans jeton dessiné à lui. */
	Role(String displayName, RoleType type, int firstNight, int otherNights, int choose, boolean notSelf, String text, String icon) {
		this.displayName = displayName;
		this.type = type;
		this.firstNight = firstNight;
		this.otherNights = otherNights;
		this.choose = choose;
		this.notSelf = notSelf;
		this.text = text;
		this.icon = icon != null ? icon : name().toLowerCase(java.util.Locale.ROOT);
		this.slot = false;
		this.active = true;
		this.firstKey = firstNight;
		this.otherKey = otherNights;
	}

	/** Emplacement vide, prêt à recevoir un rôle créé. */
	Role() {
		this.displayName = "?";
		this.type = RoleType.CITADIN;
		this.text = "";
		this.icon = "e_etoile";
		this.slot = true;
		this.active = false;
	}

	/** Rôle créé dans l'Atelier. */
	public boolean custom() {
		return slot;
	}

	/**
	 * Suffixe des modèles du mod pour ce rôle (« jeton_ » + suffixe, « icone_ » + suffixe) : le rôle intégré
	 * a le sien ; un rôle créé, ou un rôle intégré qui emprunte un emblème, prend l'emblème aux couleurs du camp.
	 */
	public String modelSuffix() {
		return custom() || icon.startsWith("e_") ? icon + "_" + (evil() ? "mal" : "bien") : name().toLowerCase(java.util.Locale.ROOT);
	}

	/** Les rôles existants : les rôles intégrés et les rôles créés chargés (sans le Voyageur, hors script). */
	public static List<Role> all() {
		return Arrays.stream(values()).filter(r -> r.active && r != VOYAGEUR).toList();
	}

	/** Identifiant d'échange : le nom de l'emplacement pour tous (PERSO_07, GOULE…). */
	public String id() {
		return name();
	}

	/** Rôle d'après un identifiant : nom intégré (« goule »), emplacement (« PERSO_07 ») ou création (« passeur »). */
	public static Role parse(String id) {
		if (id == null) {
			return null;
		}
		String k = id.trim();
		for (Role r : values()) {
			if (r.active && (r.name().equalsIgnoreCase(k) || (r.customId != null && r.customId.equalsIgnoreCase(k)))) {
				return r;
			}
		}
		return null;
	}

	/**
	 * Charge les rôles créés dans leurs emplacements (les emplacements absents redeviennent vides).
	 * Appelé au démarrage du serveur, à chaque enregistrement dans l'Atelier, et côté client à chaque vue.
	 */
	public static synchronized void defineCustom(List<RoleDef> defs) {
		customDefs = List.copyOf(defs);
		for (Role r : values()) {
			if (r.slot) {
				r.active = false;
				r.customId = null;
				r.ability = null;
			}
		}
		for (RoleDef d : defs) {
			if (d.emplacement < 1 || d.emplacement > CUSTOM_SLOTS) {
				continue;
			}
			Role r = valueOf(String.format("PERSO_%02d", d.emplacement));
			r.active = true;
			r.customId = d.id;
			r.displayName = d.nom == null || d.nom.isBlank() ? d.id : d.nom;
			r.type = d.type();
			r.text = d.texte == null ? "" : d.texte;
			r.icon = d.embleme == null || d.embleme.isBlank() ? "e_etoile" : d.embleme;
			r.ability = d.pouvoir == null ? new Ability() : d.pouvoir;
			r.choose = r.ability.choose();
			r.notSelf = r.ability.notSelf();
			boolean wakes = r.ability.wakes();
			r.firstKey = wakes ? d.wakeKey(d.premiereNuit, true) : 0;
			r.otherKey = wakes ? d.wakeKey(d.autresNuits, false) : 0;
			r.firstNight = r.firstKey > 0 ? 1 : 0;
			r.otherNights = r.otherKey > 0 ? 1 : 0;
		}
	}

	public boolean evil() {
		return type.evil();
	}

	/** Rôles ajoutés par le script des Brumes (absents du script classique). */
	private static final java.util.Set<Role> BRUMES_ONLY = java.util.EnumSet.of(SONNEUR, CARTOGRAPHE, FAUSSAIRE);
	/** Rôles ajoutés pour la table des joueurs (absents des deux scripts d'origine). */
	private static final java.util.Set<Role> TABLE_ONLY = java.util.EnumSet.of(FLEURISTE, JONGLEUR, BAVARD, SORCIERE);

	/** Le script classique : les 22 rôles des Cendres de Valcombe. */
	public static java.util.EnumSet<Role> classic() {
		java.util.EnumSet<Role> s = java.util.EnumSet.noneOf(Role.class);
		for (Role r : values()) {
			if (!r.slot && r != VOYAGEUR) {
				s.add(r);
			}
		}
		s.removeAll(BRUMES_ONLY);
		s.removeAll(TABLE_ONLY);
		return s;
	}

	/** Le script de la table : les 22 rôles des Cendres, plus la Fleuriste, le Jongleur, le Bavard et la Sorcière. */
	public static java.util.EnumSet<Role> table() {
		java.util.EnumSet<Role> s = classic();
		s.addAll(TABLE_ONLY);
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
		return Arrays.stream(values()).filter(r -> r.active && r.type == t && r != VOYAGEUR).toList();
	}

	/** Rôles qui se réveillent la première nuit, dans l'ordre. */
	public static List<Role> firstNightOrder() {
		return sortedBy(r -> r.firstKey);
	}

	/** Rôles qui se réveillent les nuits suivantes, dans l'ordre. */
	public static List<Role> otherNightsOrder() {
		return sortedBy(r -> r.otherKey);
	}

	private static List<Role> sortedBy(java.util.function.ToDoubleFunction<Role> f) {
		List<Role> out = new ArrayList<>();
		for (Role r : values()) {
			if (r.active && f.applyAsDouble(r) > 0) {
				out.add(r);
			}
		}
		out.sort(Comparator.comparingDouble(f));
		return out;
	}
}
