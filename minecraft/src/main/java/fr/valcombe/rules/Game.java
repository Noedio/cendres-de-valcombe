package fr.valcombe.rules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Déroulement d'une partie : nuits, jours, nominations, votes, morts, victoire.
 * Les sièges sont numérotés de 0 à n - 1 dans l'ordre du cercle.
 * Aucune dépendance à Minecraft.
 */
public final class Game {
	public enum Phase { SETUP, READY, NIGHT, DAY, ENDED }

	/** État d'un joueur. */
	public static final class Seat {
		public final String name;
		Role role;
		Role believed;
		boolean alive = true;
		boolean ghostVote = true;
		/** Voyageur arrivé en cours de partie, et son camp (choisi par le Conteur). */
		boolean traveller;
		Team travellerTeam;

		Seat(String name) {
			this.name = name;
		}

		public boolean traveller() {
			return traveller;
		}

		public Role role() {
			return role;
		}

		/** Rôle que le joueur croit avoir (différent pour le Somnambule). */
		public Role believed() {
			return believed;
		}

		public boolean alive() {
			return alive;
		}

		public boolean ghostVote() {
			return ghostVote;
		}

		public Team team() {
			return traveller && travellerTeam != null ? travellerTeam : Team.of(role);
		}
	}

	public record Nomination(int index, int nominator, int nominee, boolean pelerine) {
	}

	public record VoteResult(int count, int threshold, List<String> warnings, boolean onBlock, boolean exiled) {
	}

	public record DayEnd(int executed, Team winner) {
	}

	private record Slot(Step.Kind kind, Role role) {
	}

	/** Non enregistrés : recréés au rechargement. */
	public transient Rng rng;
	public transient Decisions decide;
	private final List<Seat> seats = new ArrayList<>();
	private final List<String> log = new ArrayList<>();
	private Phase phase = Phase.SETUP;
	private int night;
	private int day;
	private Team winner;
	private String winReason;

	// État de la partie
	private List<Role> bluffs = List.of();
	private int redHerring = -1;
	/** Rôles créés : empoisonneur → cible, faussaire → cible, protecteur → cible (nuit en cours). */
	private Map<Integer, Integer> customPoison;
	private Map<Integer, Integer> customForged;
	private Map<Integer, Integer> customProtect;
	private int poisoned = -1;
	/** Joueur empoisonné par le Conteur (à part de celui de l'Alchimiste) ; null : personne. */
	private Integer stPoisoned;
	/** Rôles créés : sièges ayant utilisé leur pouvoir à usage unique (nuit, tir, représailles). */
	private Set<Integer> onceUsed;
	/** Rôles créés au modèle « maître » : siège → maître choisi. */
	private Map<Integer, Integer> customMaster;
	/** Joueur désigné par la Faussaire : ses informations de la nuit sont fausses. */
	private int forged = -1;
	private int protectedSeat = -1;
	/** Sorcière : joueur maudit cette nuit (-1 : personne). */
	private int cursed = -1;
	/** Sièges dont la voix a été comptée au Conseil du jour, puis au dernier Conseil (Fleuriste). */
	private List<Integer> votersToday;
	private List<Integer> votersLastCouncil;
	/** Jongleur : hypothèses de la nuit (siège → rôle prêté). */
	private Map<Integer, Role> guesses;
	/** Bavard : son hypothèse est juste ; il mourra à la fin de la nuit qui vient. */
	private boolean bavardDoomed;
	private int master = -1;
	private boolean pelerineUsed;
	private boolean arbaletrierUsed;
	private int newDemon = -1;
	private int fauconnierWakes = -1;
	private int executedYesterday = -1;
	private List<Integer> deathsTonight = new ArrayList<>();

	// Nuit en cours
	private List<Slot> queue = List.of();
	private int queueIndex;
	private Step currentStep;
	/** Positions dans la file des étapes déjà jouées cette nuit (pour revenir en arrière). */
	private List<Integer> stepHistory;

	// Jour en cours
	private final List<int[]> nominations = new ArrayList<>(); // {nominator, nominee, votes(-1)}
	private final Set<Integer> hasNominated = new HashSet<>();
	private final Set<Integer> wasNominated = new HashSet<>();
	private int top = -1;
	private int topVotes;
	private boolean tie;
	private boolean dayOver;
	private int executedToday = -1;

	private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

	/** État complet de la partie, pour la sauvegarde automatique. */
	public String toJson() {
		return GSON.toJson(this);
	}

	public static Game fromJson(String json, Rng rng) {
		Game g = GSON.fromJson(json, Game.class);
		g.rng = rng;
		g.decide = new AutoDecisions();
		return g;
	}

	public Game(List<String> names, Rng rng) {
		this.rng = rng;
		this.decide = new AutoDecisions();
		for (String n : names) {
			seats.add(new Seat(n));
		}
	}

	// ------------------------------------------------------------------
	// Accès
	// ------------------------------------------------------------------
	public int size() {
		return seats.size();
	}

	public Seat player(int seat) {
		return seats.get(seat);
	}

	public List<Seat> players() {
		return List.copyOf(seats);
	}

	public Phase phase() {
		return phase;
	}

	public int night() {
		return night;
	}

	public int day() {
		return day;
	}

	public Team winner() {
		return winner;
	}

	public String winReason() {
		return winReason;
	}

	public List<String> log() {
		return List.copyOf(log);
	}

	public List<Role> bluffs() {
		return bluffs;
	}

	public int redHerring() {
		return redHerring;
	}

	public Step currentStep() {
		return currentStep;
	}

	public boolean dayOver() {
		return dayOver;
	}

	public boolean pelerineUsed() {
		return pelerineUsed;
	}

	public boolean arbaletrierUsed() {
		return arbaletrierUsed;
	}

	public int poisonedSeat() {
		return poisoned;
	}

	private Set<Integer> onceUsed() {
		if (onceUsed == null) {
			onceUsed = new HashSet<>();
		}
		return onceUsed;
	}

	private Map<Integer, Integer> customMaster() {
		if (customMaster == null) {
			customMaster = new HashMap<>();
		}
		return customMaster;
	}

	/** Ce joueur est-il le maître de quelqu'un (Écuyer, ou rôle créé au modèle « maître ») ? */
	public boolean isMaster(int seat) {
		return master == seat || customMaster().containsValue(seat);
	}

	/** Ce joueur a-t-il déjà utilisé son pouvoir à usage unique ? */
	public boolean onceUsed(int seat) {
		return onceUsed().contains(seat);
	}

	public int protectedSeat() {
		return protectedSeat;
	}

	public int masterSeat() {
		return master;
	}

	public int seatOf(String name) {
		for (int i = 0; i < seats.size(); i++) {
			if (seats.get(i).name.equals(name)) {
				return i;
			}
		}
		return -1;
	}

	void addLog(String text) {
		log.add(text);
	}

	/** Ligne ajoutée au journal par le Conteur (information montrée, réponse d'un joueur). */
	public void storytellerLog(String text) {
		log.add(text);
	}

	// ------------------------------------------------------------------
	// Mise en place
	// ------------------------------------------------------------------
	private Set<Role> script = Setup.fullScript();
	/** Répartition propre au scénario (Citadins, Étrangers, Sbires, Démons), ou null. */
	private int[] customCounts;
	/** Ordre de réveil propre au scénario (noms de rôles), ou null : l'ordre habituel. */
	private List<String> firstOrder;
	private List<String> otherOrder;
	/** Règles de scénario : les morts n'ont pas de vote, et nombre d'accusations par jour (0 : sans limite). */
	private boolean noGhostVote;
	private int maxNominations;

	/** Ordre de réveil d'un scénario : les rôles cités passent dans cet ordre, les autres ensuite, dans l'ordre habituel. */
	public void setNightOrder(List<Role> first, List<Role> other) {
		firstOrder = first == null || first.isEmpty() ? null : first.stream().map(Role::name).toList();
		otherOrder = other == null || other.isEmpty() ? null : other.stream().map(Role::name).toList();
	}

	public void setRules(boolean noGhostVote, int maxNominations) {
		this.noGhostVote = noGhostVote;
		this.maxNominations = Math.max(0, maxNominations);
	}

	/** Règles de table : exécution à la majorité absolue des vivants ; le mal gagne dès qu'il est majoritaire. */
	private boolean absoluteMajority;
	private boolean evilMajorityWins;

	public void setHouseRules(boolean absoluteMajority, boolean evilMajorityWins) {
		this.absoluteMajority = absoluteMajority;
		this.evilMajorityWins = evilMajorityWins;
	}

	// Les sauvegardes d'avant les rôles de la table n'ont pas ces listes : elles sont créées à la demande.
	private List<Integer> votersToday() {
		if (votersToday == null) {
			votersToday = new ArrayList<>();
		}
		return votersToday;
	}

	private List<Integer> votersLastCouncil() {
		if (votersLastCouncil == null) {
			votersLastCouncil = new ArrayList<>();
		}
		return votersLastCouncil;
	}

	private Map<Integer, Role> guesses() {
		if (guesses == null) {
			guesses = new HashMap<>();
		}
		return guesses;
	}

	/** Jongleur : les rôles qu'il prête, avant de résoudre son réveil (les sièges sont les cibles de l'étape). */
	public void setGuesses(Map<Integer, Role> g) {
		guesses().clear();
		guesses().putAll(g);
	}

	/**
	 * Sorcière : à la fin du Conseil (après les votes), le joueur maudit meurt s'il a été accusé.
	 * Sans effet à trois vivants ou moins. Renvoie le siège du mort, ou -1.
	 */
	public int applyCurse() {
		int c = cursed;
		cursed = -1;
		if (c < 0 || phase != Phase.DAY || !wasNominated.contains(c) || !player(c).alive || aliveCount() <= 3) {
			return -1;
		}
		kill(c, "malédiction de la Sorcière");
		checkEvilWin();
		return c;
	}

	/** Bavard : le rôle qu'il prête à un joueur. Juste : il mourra à la fin de la nuit qui vient. */
	public void bavardGuess(int seat, int target, Role role) {
		Seat b = player(seat);
		if (b.role != Role.BAVARD || !b.alive) {
			throw new GameException("Seul le Bavard vivant fait cette hypothèse.");
		}
		if (target == seat) {
			throw new GameException("Prête un rôle à un autre joueur que toi.");
		}
		bavardDoomed = player(target).role == role;
		addLog(b.name + " (Bavard) prête à " + player(target).name + " le rôle " + role.displayName
			+ (bavardDoomed ? " : c'est juste, il mourra cette nuit." : " : c'est faux."));
	}

	/** Voix nécessaires pour mettre un joueur sur le billot. */
	public int voteThreshold() {
		return absoluteMajority ? aliveCount() / 2 + 1 : (aliveCount() + 1) / 2;
	}

	/** Applique un ordre choisi à la liste habituelle des réveils. */
	static List<Role> ordered(List<Role> usual, List<String> wanted) {
		if (wanted == null || wanted.isEmpty()) {
			return usual;
		}
		List<Role> out = new ArrayList<>();
		for (String name : wanted) {
			for (Role r : usual) {
				if (r.name().equals(name) && !out.contains(r)) {
					out.add(r);
				}
			}
		}
		for (Role r : usual) {
			if (!out.contains(r)) {
				out.add(r);
			}
		}
		return out;
	}

	public void setupRandom() {
		setupRandom(Setup.fullScript());
	}

	/** Tire les rôles parmi ceux du script. */
	public void setupRandom(Set<Role> allowed) {
		setupRandom(allowed, 0);
	}

	/** Tire les rôles, avec un écart d'Étrangers (figure de la Sentinelle). */
	public void setupRandom(Set<Role> allowed, int outsiderShift) {
		setupRandom(allowed, outsiderShift, Set.of(), null);
	}

	/** Tirage d'un scénario : rôles toujours en jeu, et répartition propre (null : l'habituelle). */
	public void setupRandom(Set<Role> allowed, int outsiderShift, Set<Role> forced, Distribution.Counts counts) {
		String problem = Setup.check(allowed, size(), counts);
		if (problem != null) {
			throw new GameException(problem);
		}
		customCounts = counts == null ? null : new int[] {counts.citadin(), counts.etranger(), counts.sbire(), counts.demon()};
		List<Setup.Entry> bag = Setup.drawBag(size(), rng, allowed, outsiderShift, forced, counts);
		if (bag == null) {
			throw new GameException("Ce script ne permet pas de distribuer les rôles pour " + size() + " joueurs.");
		}
		script = java.util.EnumSet.copyOf(allowed);
		assign(rng.shuffle(bag), null, -1);
	}

	/**
	 * Distribution manuelle (Conteur ou tests).
	 * @param bluffList bluffs imposés, ou null pour un tirage
	 * @param herring faux présage imposé, ou -1 pour un tirage
	 */
	public void assign(List<Setup.Entry> list, List<Role> bluffList, int herring) {
		if (list.size() != size()) {
			throw new GameException("Il faut exactement un rôle par joueur.");
		}
		for (int i = 0; i < list.size(); i++) {
			seats.get(i).role = list.get(i).role();
			seats.get(i).believed = list.get(i).believed();
		}
		bluffs = bluffList != null ? List.copyOf(bluffList) : Setup.pickBluffs(list, rng, script);
		redHerring = herring;
		boolean customLead = seats.stream().anyMatch(p -> model(p.believed, "detecteur") && effect(p.believed, "detecteur").faussePiste);
		if (redHerring < 0 && (wakerSeat(Role.ASTROLOGUE) >= 0 || customLead)) {
			List<Integer> good = allSeats().stream().filter(s -> player(s).team() == Team.GOOD).toList();
			Integer pick = rng.pick(good);
			redHerring = pick == null ? -1 : pick;
		}
		phase = Phase.READY;
		addLog("Mise en place : " + describeRoles());
	}

	// ------------------------------------------------------------------
	// Retouches du Conteur avant la première nuit
	// ------------------------------------------------------------------
	private void requireReady() {
		if (phase != Phase.READY) {
			throw new GameException("La distribution ne se modifie qu'avant la première nuit.");
		}
	}

	/** Change le rôle d'un joueur ; believed sert au Somnambule (rôle qu'il croit avoir). */
	public void reassign(int seat, Role role, Role believed) {
		requireReady();
		if (role == Role.SOMNAMBULE && (believed == null || believed.type != RoleType.CITADIN)) {
			throw new GameException("Le Somnambule doit croire être un Citadin.");
		}
		Seat p = player(seat);
		p.role = role;
		p.believed = role == Role.SOMNAMBULE ? believed : role;
		if (redHerring >= 0 && player(redHerring).team() == Team.EVIL) {
			redHerring = -1;
		}
		addLog("Le Conteur donne à " + p.name + " le rôle " + role.displayName
			+ (p.believed != role ? " (croit être " + p.believed.displayName + ")" : "") + ".");
	}

	/** Désigne le faux présage de l'Astrologue (un joueur du bien). */
	public void setRedHerring(int seat) {
		if (player(seat).team() != Team.GOOD) {
			throw new GameException("Le faux présage doit être un joueur du bien.");
		}
		redHerring = seat;
		addLog("Le Conteur désigne " + player(seat).name + " comme faux présage.");
	}

	/** Impose les trois bluffs du Démon (rôles du bien absents). */
	public void setBluffs(List<Role> list) {
		requireReady();
		for (Role r : list) {
			if (r.evil()) {
				throw new GameException(r.displayName + " n'est pas un rôle du bien.");
			}
			for (Seat p : seats) {
				if (p.role == r || p.believed == r) {
					throw new GameException(r.displayName + " est déjà en jeu.");
				}
			}
		}
		bluffs = List.copyOf(list);
	}

	/** Écart entre la distribution actuelle et la répartition prévue (null si conforme). */
	public String distributionWarning() {
		Distribution.Counts c = customCounts != null && customCounts.length == 4
			? new Distribution.Counts(customCounts[0], customCounts[1], customCounts[2], customCounts[3])
			: Distribution.counts(size());
		if (c == null) {
			return null;
		}
		int[] have = new int[4];
		int bonus = 0;
		for (Seat p : seats) {
			if (p.traveller) {
				continue;
			}
			have[p.role.type.ordinal()]++;
			bonus += outsiderBonus(p.role);
		}
		if (have[0] != c.citadin() - bonus || have[1] != c.etranger() + bonus || have[2] != c.sbire() || have[3] != c.demon()) {
			return "Répartition inhabituelle : " + have[0] + " Citadins, " + have[1] + " Étrangers, " + have[2]
				+ " Sbires, " + have[3] + " Démon(s).";
		}
		return null;
	}

	public String describeRoles() {
		List<String> parts = new ArrayList<>();
		for (Seat p : seats) {
			String s = p.name + " = " + p.role.displayName;
			if (p.believed != p.role) {
				s += " (croit être " + p.believed.displayName + ")";
			}
			parts.add(s);
		}
		return String.join(", ", parts);
	}

	// ------------------------------------------------------------------
	// Requêtes
	// ------------------------------------------------------------------
	public List<Integer> allSeats() {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < size(); i++) {
			out.add(i);
		}
		return out;
	}

	public List<Integer> aliveSeats() {
		return allSeats().stream().filter(s -> player(s).alive).toList();
	}

	/** Vivants sans les voyageurs (conditions de victoire, Héritière, Châtelain). */
	public int aliveResidents() {
		return (int) seats.stream().filter(p -> p.alive && !p.traveller).count();
	}

	/**
	 * Un voyageur arrive en cours de partie : il s'assoit au bout du cercle, avec le camp choisi.
	 * Il vote et accuse comme les autres ; le village peut l'exiler.
	 */
	public int addTraveller(String name, Team team) {
		if (phase != Phase.DAY && phase != Phase.NIGHT) {
			throw new GameException("Les voyageurs arrivent pendant une partie.");
		}
		if (seatOf(name) >= 0) {
			throw new GameException(name + " joue déjà.");
		}
		Seat s = new Seat(name);
		s.role = Role.VOYAGEUR;
		s.believed = Role.VOYAGEUR;
		s.traveller = true;
		s.travellerTeam = team;
		seats.add(s);
		addLog(name + " arrive en voyageur (" + (team == Team.EVIL ? "mal" : "bien") + ").");
		return seats.size() - 1;
	}

	/** Le voyageur repart : il quitte la partie (compté comme mort, sans vote de fantôme). */
	public void travellerLeaves(int seat) {
		Seat p = player(seat);
		if (!p.traveller || !p.alive) {
			throw new GameException("Seul un voyageur encore présent peut repartir.");
		}
		p.ghostVote = false;
		kill(seat, "départ du voyageur");
	}

	public int aliveCount() {
		return aliveSeats().size();
	}

	/** Siège qui tient réellement ce rôle, ou -1. */
	public int seatOf(Role role) {
		for (int i = 0; i < size(); i++) {
			if (seats.get(i).role == role) {
				return i;
			}
		}
		return -1;
	}

	/** Siège réveillé pour ce rôle (le Somnambule compris) ; un vivant passe avant un mort. */
	public int wakerSeat(Role role) {
		int dead = -1;
		for (int i = 0; i < size(); i++) {
			Seat p = seats.get(i);
			if (p.believed == role) {
				if (p.alive) {
					return i;
				}
				if (dead < 0) {
					dead = i;
				}
			}
		}
		return dead;
	}

	public boolean isDrunk(int seat) {
		return player(seat).role == Role.SOMNAMBULE;
	}

	public boolean isPoisoned(int seat) {
		return poisoned == seat || (stPoisoned != null && stPoisoned == seat) || customPoison().containsValue(seat);
	}

	/** Peut passer pour un joueur de l'autre camp (Mouchard, Excommunié, rôle créé au modèle « faux-semblant ») ? */
	public boolean canMisregister(int seat) {
		Role r = player(seat).role;
		return (r == Role.MOUCHARD || r == Role.EXCOMMUNIE || model(r, "semblant")) && !isPoisoned(seat);
	}

	/** Rôles du script de cette partie, d'un type donné (pour les fausses informations). */
	private List<Role> scriptRoles(RoleType t) {
		List<Role> in = Role.ofType(t).stream().filter(script::contains).toList();
		return in.isEmpty() ? Role.ofType(t) : in;
	}

	private List<Role> scriptRoles() {
		List<Role> in = Role.all().stream().filter(script::contains).toList();
		return in.isEmpty() ? Role.all() : in;
	}

	/** Ivre ou empoisonné : le pouvoir ne fonctionne pas. */
	public boolean impaired(int seat) {
		return isDrunk(seat) || isPoisoned(seat)
			|| (phase == Phase.NIGHT && (forged == seat || customForged().containsValue(seat)));
	}

	// Les sauvegardes d'avant la 0.4.0 n'ont pas ces tables : elles sont créées à la demande.
	private Map<Integer, Integer> customPoison() {
		if (customPoison == null) {
			customPoison = new HashMap<>();
		}
		return customPoison;
	}

	private Map<Integer, Integer> customForged() {
		if (customForged == null) {
			customForged = new HashMap<>();
		}
		return customForged;
	}

	private Map<Integer, Integer> customProtect() {
		if (customProtect == null) {
			customProtect = new HashMap<>();
		}
		return customProtect;
	}

	/** Le rôle est un rôle créé avec ce modèle de pouvoir. */
	private static boolean model(Role r, String type) {
		return r != null && r.custom() && r.ability != null && r.ability.has(type);
	}

	/** L'effet de ce modèle porté par un rôle créé, ou null. */
	private static Ability effect(Role r, String type) {
		return r != null && r.custom() && r.ability != null ? r.ability.effect(type) : null;
	}

	/** Étrangers en plus (ou en moins) imposés par un rôle au tirage. */
	public static int outsiderBonus(Role r) {
		if (r == Role.MARGRAVE) {
			return 2;
		}
		return model(r, "etrangers") ? effect(r, "etrangers").etrangers : 0;
	}

	/** « La Goule » → « la Goule », pour les phrases. */
	private static String lower(String name) {
		return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
	}

	public int demonSeat() {
		for (int i = 0; i < size(); i++) {
			if (seats.get(i).alive && seats.get(i).role.type == RoleType.DEMON) {
				return i;
			}
		}
		return -1;
	}

	public List<Integer> minionSeats() {
		return allSeats().stream().filter(s -> player(s).role.type == RoleType.SBIRE).toList();
	}

	/** Voisins vivants les plus proches de chaque côté. */
	public List<Integer> aliveNeighbours(int seat) {
		List<Integer> out = new ArrayList<>();
		int n = size();
		for (int dir : new int[] {-1, 1}) {
			int s = seat;
			for (int k = 0; k < n - 1; k++) {
				s = Math.floorMod(s + dir, n);
				if (s == seat) {
					break;
				}
				if (seats.get(s).alive) {
					if (!out.contains(s)) {
						out.add(s);
					}
					break;
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------------
	// Enregistrement (Mouchard, Excommunié)
	// ------------------------------------------------------------------
	/** Contexte d'un calcul, pour rester cohérent d'un joueur à l'autre. */
	public static final class Ctx {
		final String key;
		final Map<String, Boolean> cache = new HashMap<>();

		public Ctx(String key) {
			this.key = key;
		}
	}

	/** what : "evil", "demon", "minion", "townsfolk", "outsider". */
	public boolean reg(int seat, String what, Ctx ctx) {
		String k = seat + ":" + what;
		Boolean cached = ctx.cache.get(k);
		if (cached != null) {
			return cached;
		}
		Seat p = player(seat);
		if (p.traveller) {
			// Un voyageur n'a pas de type de rôle : seul son camp compte, sans faux-semblant.
			boolean v = what.equals("evil") && p.team() == Team.EVIL;
			ctx.cache.put(k, v);
			return v;
		}
		RoleType t = p.role.type;
		boolean truth = switch (what) {
			case "evil" -> p.team() == Team.EVIL;
			case "demon" -> t == RoleType.DEMON;
			case "minion" -> t == RoleType.SBIRE;
			case "townsfolk" -> t == RoleType.CITADIN;
			case "outsider" -> t == RoleType.ETRANGER;
			default -> throw new IllegalArgumentException(what);
		};
		boolean canFlip = false;
		boolean custom = model(p.role, "semblant");
		if ((p.role == Role.MOUCHARD || (custom && p.role.evil())) && !isPoisoned(seat)) {
			canFlip = Set.of("evil", "minion", "townsfolk", "outsider").contains(what);
		} else if ((p.role == Role.EXCOMMUNIE || (custom && !p.role.evil())) && !isPoisoned(seat)) {
			canFlip = Set.of("evil", "minion", "demon", "outsider").contains(what);
		}
		boolean v = truth;
		if (canFlip && decide.misregister(this, seat, what, ctx.key)) {
			v = !truth;
		}
		ctx.cache.put(k, v);
		return v;
	}

	/** Rôle montré à un pouvoir qui révèle un personnage. */
	public Role shownRole(int seat, String key) {
		Seat p = player(seat);
		boolean custom = model(p.role, "semblant");
		if ((p.role == Role.MOUCHARD || (custom && p.role.evil())) && !isPoisoned(seat) && decide.misregister(this, seat, "role", key)) {
			List<Role> pool = new ArrayList<>(scriptRoles(RoleType.CITADIN));
			pool.addAll(scriptRoles(RoleType.ETRANGER));
			return rng.pick(pool);
		}
		if ((p.role == Role.EXCOMMUNIE || (custom && !p.role.evil())) && !isPoisoned(seat) && decide.misregister(this, seat, "role", key)) {
			List<Role> pool = new ArrayList<>(scriptRoles(RoleType.SBIRE));
			pool.addAll(scriptRoles(RoleType.DEMON));
			return rng.pick(pool);
		}
		return p.role;
	}

	// ------------------------------------------------------------------
	// Morts
	// ------------------------------------------------------------------
	public boolean kill(int seat, String cause) {
		Seat p = player(seat);
		if (!p.alive) {
			return false;
		}
		int aliveBefore = aliveResidents();
		p.alive = false;
		if (noGhostVote) {
			p.ghostVote = false;
		}
		addLog(p.name + " meurt (" + cause + ").");
		if (phase == Phase.NIGHT) {
			deathsTonight.add(seat);
			if (p.believed == Role.FAUCONNIER) {
				fauconnierWakes = seat;
			}
		}
		if (p.role == Role.ALCHIMISTE) {
			poisoned = -1;
		}
		if (p.role == Role.FAUSSAIRE) {
			forged = -1;
		}
		customPoison().remove(seat);
		customForged().remove(seat);
		if (p.role.type == RoleType.DEMON) {
			onDemonDeath(cause, aliveBefore, p.role);
		}
		return true;
	}

	private void makeDemon(int seat, Role demon) {
		Seat p = player(seat);
		addLog(p.name + " (" + p.role.displayName + ") devient " + lower(demon.displayName) + ".");
		p.role = demon;
		p.believed = demon;
		newDemon = seat;
	}

	private void onDemonDeath(String cause, int aliveBefore, Role demon) {
		int h = seatOf(Role.HERITIERE);
		if (h < 0 || !player(h).alive || isPoisoned(h)) {
			h = -1;
			for (int s = 0; s < size(); s++) {
				if (player(s).alive && model(player(s).role, "heritier") && !isPoisoned(s)) {
					h = s;
					break;
				}
			}
		}
		if (h >= 0 && player(h).alive && !isPoisoned(h) && aliveBefore >= 5) {
			makeDemon(h, demon);
			return;
		}
		if (cause.equals("suicide")) {
			List<Integer> candidates = minionSeats().stream().filter(s -> player(s).alive).toList();
			Integer successor = decide.starpassSuccessor(this, candidates);
			if (successor != null) {
				makeDemon(successor, demon);
				return;
			}
		}
		setWinner(Team.GOOD, "Le Démon est mort.");
	}

	private void setWinner(Team team, String reason) {
		if (winner != null) {
			return;
		}
		winner = team;
		winReason = reason;
		phase = Phase.ENDED;
		addLog("Victoire du " + (team == Team.GOOD ? "bien" : "mal") + " : " + reason);
	}

	private void checkEvilWin() {
		if (winner == null && demonSeat() >= 0 && aliveResidents() <= 2) {
			setWinner(Team.EVIL, "Il ne reste que deux joueurs vivants.");
		}
		if (winner == null && evilMajorityWins && demonSeat() >= 0) {
			long evil = seats.stream().filter(p -> p.alive && !p.traveller && p.team() == Team.EVIL).count();
			if (evil * 2 > aliveResidents()) {
				setWinner(Team.EVIL, "Le mal est majoritaire parmi les vivants.");
			}
		}
	}

	// ------------------------------------------------------------------
	// Nuit
	// ------------------------------------------------------------------
	public void startNight() {
		if (winner != null) {
			throw new GameException("La partie est terminée.");
		}
		if (phase == Phase.NIGHT) {
			throw new GameException("C'est déjà la nuit.");
		}
		if (phase == Phase.DAY && !dayOver) {
			throw new GameException("Il faut d'abord clore la journée.");
		}
		night++;
		phase = Phase.NIGHT;
		// Le poison de la veille et la protection du Chapelain prennent fin au crépuscule.
		poisoned = -1;
		stPoisoned = null;
		forged = -1;
		protectedSeat = -1;
		customPoison().clear();
		customForged().clear();
		customProtect().clear();
		deathsTonight = new ArrayList<>();
		fauconnierWakes = -1;
		cursed = -1;
		votersLastCouncil().clear();
		votersLastCouncil().addAll(votersToday());
		List<Slot> q = new ArrayList<>();
		if (night == 1) {
			q.add(new Slot(Step.Kind.MINION_INFO, null));
			q.add(new Slot(Step.Kind.DEMON_INFO, null));
			for (Role r : ordered(Role.firstNightOrder(), firstOrder)) {
				q.add(new Slot(Step.Kind.ROLE, r));
			}
		} else {
			for (Role r : ordered(Role.otherNightsOrder(), otherOrder)) {
				q.add(r == Role.HERITIERE ? new Slot(Step.Kind.NEW_DEMON, null) : new Slot(Step.Kind.ROLE, r));
			}
		}
		queue = q;
		queueIndex = 0;
		stepHistory = new ArrayList<>();
		addLog("La nuit " + night + " tombe.");
	}

	private Step buildStep(Slot slot) {
		switch (slot.kind) {
			case MINION_INFO:
				return size() < 7 ? null : new Step(Step.Kind.MINION_INFO, null, -1, minionSeats());
			case DEMON_INFO:
				return size() < 7 ? null : new Step(Step.Kind.DEMON_INFO, null, demonSeat(), List.of());
			case NEW_DEMON:
				return newDemon >= 0 && player(newDemon).alive
					? new Step(Step.Kind.NEW_DEMON, null, newDemon, List.of()) : null;
			default:
				break;
		}
		Role role = slot.role;
		int seat = wakerSeat(role);
		if (seat < 0) {
			return null;
		}
		if (role == Role.FAUCONNIER) {
			if (fauconnierWakes != seat) {
				return null;
			}
		} else if (!player(seat).alive) {
			return null;
		}
		if (role == Role.JONGLEUR && night != 2) {
			return null; // le Jongleur ne se réveille que la deuxième nuit
		}
		if (role == Role.SORCIERE && aliveCount() <= 3) {
			return null; // à trois vivants, le pouvoir de la Sorcière s'éteint
		}
		if (role == Role.EMBAUMEUR && executedYesterday < 0) {
			return null;
		}
		if (role.custom() && role.ability != null) {
			if (role.ability.uneFois && onceUsed().contains(seat)) {
				return null; // pouvoir à usage unique déjà utilisé
			}
			// Sans exécution la veille, un rôle qui n'a que ce pouvoir-là ne se réveille pas.
			boolean onlyExecuted = role.ability.all().stream().filter(Ability::playedAtNight).allMatch(a -> a.type.equals("execute"));
			if (onlyExecuted && executedYesterday < 0) {
				return null;
			}
		}
		return new Step(Step.Kind.ROLE, role, seat, List.of());
	}

	/** Étape suivante de la nuit, ou null quand tout le monde a joué. */
	public Step nextStep() {
		if (phase != Phase.NIGHT) {
			return null;
		}
		while (queueIndex < queue.size()) {
			Step step = buildStep(queue.get(queueIndex++));
			if (step != null) {
				currentStep = step;
				history().add(queueIndex - 1);
				return step;
			}
		}
		currentStep = null;
		return null;
	}

	private List<Integer> history() {
		if (stepHistory == null) {
			stepHistory = new ArrayList<>();
		}
		return stepHistory;
	}

	/**
	 * Revient à l'étape précédente de la nuit : le prochain nextStep() la rejoue.
	 * Ses effets déjà appliqués (mort, poison) ne sont pas annulés.
	 */
	public void previousStep() {
		if (phase != Phase.NIGHT) {
			throw new GameException("Ce n'est pas la nuit.");
		}
		List<Integer> h = history();
		// Dernier élément : l'étape en cours (si la nuit n'est pas finie) ; celui d'avant : la précédente.
		int back = currentStep != null ? 2 : 1;
		if (h.size() < back) {
			throw new GameException("Il n'y a pas d'étape précédente cette nuit.");
		}
		int index = h.get(h.size() - back);
		for (int k = 0; k < back; k++) {
			h.remove(h.size() - 1);
		}
		queueIndex = index;
		currentStep = null;
		addLog("Le Conteur revient à l'étape précédente.");
	}

	/**
	 * Ordre de réveil prévu : la nuit en cours (réveils à venir compris), ou sinon la prochaine nuit.
	 * Seuls les rôles en jeu y figurent.
	 */
	public List<Role> plannedOrder() {
		boolean first = phase == Phase.NIGHT ? night == 1 : night == 0;
		List<Role> out = new ArrayList<>();
		for (Role r : ordered(first ? Role.firstNightOrder() : Role.otherNightsOrder(), first ? firstOrder : otherOrder)) {
			if (wakerSeat(r) >= 0 && !out.contains(r)) {
				out.add(r);
			}
		}
		return out;
	}

	/**
	 * Le Conteur avance (delta -1) ou recule (+1) un rôle dans l'ordre de réveil, par rapport au rôle en jeu
	 * voisin. Vaut pour les nuits suivantes et, la nuit, pour les réveils encore à venir.
	 */
	public void moveInNightOrder(Role role, int delta) {
		List<Role> inPlay = plannedOrder();
		int i = inPlay.indexOf(role);
		if (i < 0) {
			throw new GameException("Ce rôle ne se réveille pas dans cette partie.");
		}
		int j = i + Integer.signum(delta);
		if (j < 0 || j >= inPlay.size()) {
			throw new GameException(delta < 0 ? "Ce rôle est déjà le premier." : "Ce rôle est déjà le dernier.");
		}
		Role other = inPlay.get(j);
		boolean first = phase == Phase.NIGHT ? night == 1 : night == 0;
		List<Role> full = new ArrayList<>(ordered(first ? Role.firstNightOrder() : Role.otherNightsOrder(), first ? firstOrder : otherOrder));
		full.remove(role);
		int at = full.indexOf(other);
		full.add(delta < 0 ? at : at + 1, role);
		List<String> names = full.stream().map(Role::name).toList();
		if (first) {
			firstOrder = names;
		} else {
			otherOrder = names;
		}
		// La nuit, les réveils encore à venir suivent le nouvel ordre.
		if (phase == Phase.NIGHT) {
			int a = -1;
			int b = -1;
			for (int k = queueIndex; k < queue.size(); k++) {
				if (queue.get(k).role == role) {
					a = k;
				} else if (queue.get(k).role == other) {
					b = k;
				}
			}
			if (a >= 0 && b >= 0) {
				List<Slot> q = new ArrayList<>(queue);
				Slot moved = q.remove(a);
				int pos = q.indexOf(queue.get(b));
				q.add(delta < 0 ? pos : pos + 1, moved);
				queue = q;
			}
		}
		addLog("Le Conteur place " + role.displayName + (delta < 0 ? " avant " : " après ") + other.displayName + " dans l'ordre de réveil.");
	}

	/** Les réveils de la nuit en cours, dans l'ordre : ceux déjà joués, celui en cours, ceux à venir. */
	public List<NightEntry> nightOrder() {
		List<NightEntry> out = new ArrayList<>();
		if (phase != Phase.NIGHT) {
			return out;
		}
		int current = currentStep != null ? queueIndex - 1 : -1;
		for (int i = 0; i < queue.size(); i++) {
			Step step = buildStep(queue.get(i));
			boolean played = history().contains(i) && i != current;
			if (step == null && !played) {
				continue;
			}
			String title = step != null ? step.title() : queue.get(i).role != null ? queue.get(i).role.displayName : "Réveil";
			out.add(new NightEntry(title, step != null && step.seat >= 0 ? player(step.seat).name : "",
				i == current ? 0 : played || i < queueIndex ? -1 : 1));
		}
		return out;
	}

	/** Une ligne de l'ordre de nuit ; state : -1 jouée, 0 en cours, 1 à venir. */
	public record NightEntry(String title, String player, int state) {
	}

	public void validateTargets(Step step, List<Integer> targets) {
		if (!step.isChoose()) {
			return;
		}
		if (targets.isEmpty() && step.optional()) {
			return; // pouvoir à usage unique : le joueur le garde pour une autre nuit
		}
		if (targets.size() != step.choose()) {
			throw new GameException("Il faut désigner " + step.choose() + " joueur(s).");
		}
		if (new HashSet<>(targets).size() != targets.size()) {
			throw new GameException("Chaque joueur ne peut être désigné qu'une fois.");
		}
		for (int t : targets) {
			if (t < 0 || t >= size()) {
				throw new GameException("Joueur inconnu.");
			}
			if (step.notSelf() && t == step.seat) {
				throw new GameException("Tu ne peux pas te désigner toi-même.");
			}
		}
	}

	/** Applique l'étape et renvoie l'information proposée (jamais null). */
	public Info resolve(Step step, List<Integer> targets) {
		validateTargets(step, targets);
		Info info = switch (step.kind) {
			case MINION_INFO -> minionInfo();
			case DEMON_INFO -> demonInfo();
			case NEW_DEMON -> {
				Info i = new Info();
				i.kind = Info.Kind.NEW_DEMON;
				i.text = "Tu es désormais " + lower(player(newDemon).role.displayName) + ".";
				yield i;
			}
			case ROLE -> resolveRole(step, targets);
		};
		StringBuilder sb = new StringBuilder("Nuit " + night + ", " + step.title() + " :");
		if (step.seat >= 0) {
			sb.append(' ').append(player(step.seat).name);
		}
		for (int t : targets) {
			sb.append(" → ").append(player(t).name);
		}
		if (info.text != null) {
			sb.append(" « ").append(info.text).append(" »");
		}
		addLog(sb.toString());
		return info;
	}

	private Info resolveRole(Step step, List<Integer> targets) {
		int seat = step.seat;
		switch (step.role) {
			case COMMERE:
				return pairAbility(seat, "townsfolk", RoleType.CITADIN, "commere", false);
			case INQUISITEUR:
				return pairAbility(seat, "minion", RoleType.SBIRE, "inquisiteur", false);
			case GREFFIER: {
				Info i = pairAbility(seat, "outsider", RoleType.ETRANGER, "greffier", true);
				if (i != null) {
					return i;
				}
				Info none = new Info();
				none.kind = Info.Kind.NO_OUTSIDER;
				none.text = "Il n'y a aucun Étranger en jeu.";
				return none;
			}
			case AUBERGISTE: {
				Ctx ctx = new Ctx("aubergiste");
				int count = 0;
				for (int s = 0; s < size(); s++) {
					int next = (s + 1) % size();
					if (reg(s, "evil", ctx) && reg(next, "evil", ctx)) {
						count++;
					}
				}
				Info i = numberInfo(seat, count, size());
				i.text = "Paires de mauvais côte à côte : " + i.value + ".";
				return i;
			}
			case VEILLEUSE: {
				Ctx ctx = new Ctx("veilleuse");
				int count = 0;
				for (int s : aliveNeighbours(seat)) {
					if (reg(s, "evil", ctx)) {
						count++;
					}
				}
				Info i = numberInfo(seat, count, 2);
				i.text = "Voisins vivants mauvais : " + i.value + ".";
				return i;
			}
			case ASTROLOGUE: {
				Ctx ctx = new Ctx("astrologue");
				boolean yes = false;
				for (int t : targets) {
					if (t == redHerring || reg(t, "demon", ctx)) {
						yes = true;
					}
				}
				Info i = new Info();
				i.kind = Info.Kind.YESNO;
				i.yes = impaired(seat) != yes;
				i.truthYes = yes;
				i.text = i.yes ? "Oui" : "Non";
				if (i.yes != yes) {
					i.truthText = yes ? "Oui" : "Non";
				}
				return i;
			}
			case EMBAUMEUR:
				return roleReveal(seat, executedYesterday, "embaumeur");
			case FAUCONNIER:
				return roleReveal(seat, targets.get(0), "fauconnier");
			case MOUCHARD: {
				Info i = new Info();
				i.kind = Info.Kind.GRIMOIRE;
				i.text = "Tu consultes le grimoire.";
				return i;
			}
			case ALCHIMISTE:
				if (!impaired(seat)) {
					poisoned = targets.get(0);
				}
				return Info.none();
			case FAUSSAIRE:
				if (!impaired(seat)) {
					forged = targets.get(0);
				}
				return Info.none();
			case SONNEUR: {
				Ctx ctx = new Ctx("sonneur");
				int count = 0;
				for (int t : targets) {
					if (reg(t, "evil", ctx)) {
						count++;
					}
				}
				Info i = numberInfo(seat, count, 2);
				i.text = "Parmi ces deux joueurs, mauvais : " + i.value + ".";
				return i;
			}
			case CARTOGRAPHE: {
				Ctx ctx = new Ctx("cartographe");
				int best = size();
				for (int s = 0; s < size(); s++) {
					if (s != seat && reg(s, "demon", ctx)) {
						int d = Math.abs(s - seat);
						best = Math.min(best, Math.min(d, size() - d));
					}
				}
				if (best == size()) {
					best = 0;
				}
				Info i = numberInfo(seat, best, size() / 2);
				i.text = "Le Démon est à " + i.value + " siège(s) de toi.";
				return i;
			}
			case CHAPELAIN:
				if (!impaired(seat)) {
					protectedSeat = targets.get(0);
				}
				return Info.none();
			case ECUYER:
				master = targets.get(0);
				return Info.none();
			case GOULE:
				return goule(seat, targets.get(0));
			case SORCIERE:
				if (!impaired(seat) && aliveCount() > 3) {
					cursed = targets.get(0);
				}
				return Info.none();
			case FLEURISTE: {
				Ctx ctx = new Ctx("fleuriste");
				boolean yes = false;
				for (int v : votersLastCouncil()) {
					if (v >= 0 && v < size() && reg(v, "evil", ctx)) {
						yes = true;
					}
				}
				Info i = new Info();
				i.kind = Info.Kind.YESNO;
				i.yes = impaired(seat) != yes;
				i.truthYes = yes;
				i.text = i.yes ? "Oui : au moins un joueur du camp des Démons a voté au dernier Conseil."
					: "Non : aucun joueur du camp des Démons n'a voté au dernier Conseil.";
				if (i.yes != yes) {
					i.truthText = yes ? "Oui" : "Non";
				}
				return i;
			}
			case JONGLEUR: {
				int right = 0;
				for (int t : targets) {
					Role g = guesses().get(t);
					if (g != null && player(t).role == g) {
						right++;
					}
				}
				Info i = numberInfo(seat, right, targets.size());
				i.text = "Hypothèses justes : " + i.value + " sur " + targets.size() + ".";
				guesses().clear();
				return i;
			}
			default:
				return step.role.custom() ? resolveCustom(step, targets) : Info.none();
		}
	}

	private Info pairInfo(int seat, Role role, int real) {
		List<Integer> pool = allSeats().stream().filter(s -> s != seat && s != real).toList();
		Integer decoy = rng.pick(pool);
		List<Integer> pair = rng.shuffle(decoy == null ? List.of(real) : List.of(real, decoy));
		Info i = new Info();
		i.kind = Info.Kind.PAIR_ROLE;
		i.players = pair;
		i.role = role;
		String names = pair.size() == 2
			? player(pair.get(0)).name + " ou " + player(pair.get(1)).name
			: player(pair.get(0)).name;
		i.text = names + " est " + role.displayName + ".";
		return i;
	}

	/**
	 * Information « l'un de ces deux joueurs est tel rôle ». Si personne ne correspond
	 * (ex. Mouchard enregistré comme bon), renvoie null quand allowNone, sinon une fausse paire.
	 */
	private Info pairAbility(int seat, String what, RoleType type, String key, boolean allowNone) {
		Ctx ctx = new Ctx(key);
		List<Integer> candidates = allSeats().stream().filter(s -> s != seat && reg(s, what, ctx)).toList();
		Info truth = null;
		if (!candidates.isEmpty()) {
			int c = rng.pick(candidates);
			Role role = player(c).role;
			if (role.type != type) {
				role = rng.pick(scriptRoles(type));
			}
			truth = pairInfo(seat, role, c);
		}
		if (impaired(seat) || (truth == null && !allowNone)) {
			List<Integer> others = allSeats().stream().filter(s -> s != seat).toList();
			Info shown = pairInfo(seat, rng.pick(scriptRoles(type)), rng.pick(others));
			shown.truthText = truth == null ? "Aucun joueur ne correspond." : truth.text;
			return shown;
		}
		return truth;
	}

	private Info numberInfo(int seat, int truth, int max) {
		Info i = new Info();
		i.kind = Info.Kind.NUMBER;
		i.value = truth;
		i.truthValue = truth;
		if (impaired(seat)) {
			List<Integer> choices = new ArrayList<>();
			for (int v = 0; v <= max; v++) {
				if (v != truth) {
					choices.add(v);
				}
			}
			i.value = rng.pick(choices);
			i.truthText = String.valueOf(truth);
		}
		return i;
	}

	private Info roleReveal(int seat, int target, String key) {
		Role truth = shownRole(target, key);
		Role shown = impaired(seat) ? rng.pick(scriptRoles()) : truth;
		Info i = new Info();
		i.kind = Info.Kind.ROLE;
		i.players = List.of(target);
		i.role = shown;
		i.truthRole = truth;
		i.text = player(target).name + " est " + shown.displayName + ".";
		if (shown != truth) {
			i.truthText = truth.displayName;
		}
		return i;
	}

	private Info minionInfo() {
		int d = demonSeat();
		List<String> names = minionSeats().stream().map(s -> player(s).name).toList();
		Info i = new Info();
		i.kind = Info.Kind.TEAM;
		i.text = "Le Démon est " + (d >= 0 ? player(d).name : "?") + ". Les Sbires : " + String.join(", ", names) + ".";
		return i;
	}

	private Info demonInfo() {
		List<String> names = minionSeats().stream().map(s -> player(s).name).toList();
		List<String> b = bluffs.stream().map(r -> r.displayName).toList();
		Info i = new Info();
		i.kind = Info.Kind.TEAM;
		i.text = "Tes Sbires : " + String.join(", ", names) + ". Rôles absents pour bluffer : " + String.join(", ", b) + ".";
		return i;
	}

	private Info goule(int seat, int target) {
		if (isPoisoned(seat)) {
			return Info.none();
		}
		if (target == seat) {
			kill(seat, "suicide");
			if (newDemon >= 0 && newDemon != seat) {
				// Le nouveau Démon l'apprend tout de suite, mais ne tue pas cette nuit.
				Info i = new Info();
				i.kind = Info.Kind.STARPASS;
				i.newDemon = newDemon;
				String d = player(newDemon).role.displayName;
				i.text = d + " s'est sacrifié(e) : tu es désormais " + lower(d) + ".";
				return i;
			}
			return Info.none();
		}
		demonAttack(target, player(seat).role);
		return Info.none();
	}

	private boolean safeFromDemon(int t) {
		Seat p = player(t);
		return !p.alive || protectedSeat == t || customProtect().containsValue(t)
			|| ((p.role == Role.TEMPLIER || model(p.role, "immunite")) && !isPoisoned(t));
	}

	/**
	 * Pouvoirs des rôles créés : chaque modèle reprend la mécanique d'un rôle intégré
	 * (ivresse, poison, enregistrements trompeurs compris).
	 */
	private Info resolveCustom(Step step, List<Integer> targets) {
		int seat = step.seat;
		Role role = step.role;
		Ability first = role.ability;
		if (first.uneFois) {
			if (targets.isEmpty()) {
				addLog(player(seat).name + " garde son pouvoir pour une autre nuit.");
				return Info.none();
			}
			onceUsed().add(seat);
		}
		Info out = null;
		for (Ability a : first.all()) {
			if (!a.playedAtNight()) {
				continue;
			}
			if (a.type.equals("execute") && executedYesterday < 0) {
				continue;
			}
			int need = a.targetsUsed();
			if (need > targets.size()) {
				continue;
			}
			Info i = resolveEffect(step, a, need == targets.size() ? targets : targets.subList(0, need));
			if (out == null) {
				out = i;
			} else if (i.text != null) {
				// Deux informations la même nuit : le joueur les reçoit ensemble.
				out.text = out.text == null ? i.text : out.text + " " + i.text;
				if (i.truthText != null) {
					out.truthText = out.truthText == null ? i.truthText : out.truthText + " " + i.truthText;
				}
				if (out.kind == Info.Kind.NONE || out.kind == Info.Kind.STARPASS) {
					out.kind = i.kind;
				}
			}
		}
		return out == null ? Info.none() : out;
	}

	private Info resolveEffect(Step step, Ability a, List<Integer> targets) {
		int seat = step.seat;
		Role role = step.role;
		String key = "perso-" + role.customId + "-" + a.type;
		switch (a.type) {
			case "paire": {
				RoleType t = a.pairType();
				Info i = pairAbility(seat, a.regKey(), t, key, true);
				if (i != null) {
					return i;
				}
				Info none = new Info();
				none.kind = Info.Kind.NO_OUTSIDER;
				none.text = "Aucun " + t.displayName + " n'est en jeu.";
				return none;
			}
			case "voisins": {
				Ctx ctx = new Ctx(key);
				int count = 0;
				for (int s : aliveNeighbours(seat)) {
					if (reg(s, "evil", ctx)) {
						count++;
					}
				}
				Info i = numberInfo(seat, count, 2);
				i.text = "Voisins vivants mauvais : " + i.value + ".";
				return i;
			}
			case "compte": {
				Ctx ctx = new Ctx(key);
				int count = 0;
				for (int t : targets) {
					if (reg(t, "evil", ctx)) {
						count++;
					}
				}
				Info i = numberInfo(seat, count, targets.size());
				i.text = "Parmi ces joueurs, mauvais : " + i.value + ".";
				return i;
			}
			case "detecteur": {
				Ctx ctx = new Ctx(key);
				boolean yes = false;
				for (int t : targets) {
					if ((a.faussePiste && a.cible.equals("demon") && t == redHerring) || reg(t, a.regKey(), ctx)) {
						yes = true;
					}
				}
				Info i = new Info();
				i.kind = Info.Kind.YESNO;
				i.yes = impaired(seat) != yes;
				i.truthYes = yes;
				i.text = i.yes ? "Oui" : "Non";
				if (i.yes != yes) {
					i.truthText = yes ? "Oui" : "Non";
				}
				return i;
			}
			case "adjacents": {
				Ctx ctx = new Ctx(key);
				int count = 0;
				for (int s = 0; s < size(); s++) {
					if (reg(s, "evil", ctx) && reg((s + 1) % size(), "evil", ctx)) {
						count++;
					}
				}
				Info i = numberInfo(seat, count, size());
				i.text = "Paires de mauvais côte à côte : " + i.value + ".";
				return i;
			}
			case "distance": {
				Ctx ctx = new Ctx(key);
				int best = size();
				for (int s = 0; s < size(); s++) {
					if (s != seat && reg(s, "demon", ctx)) {
						int d = Math.abs(s - seat);
						best = Math.min(best, Math.min(d, size() - d));
					}
				}
				if (best == size()) {
					best = 0;
				}
				Info i = numberInfo(seat, best, size() / 2);
				i.text = "Le Démon est à " + i.value + " siège(s) de toi.";
				return i;
			}
			case "execute":
				return roleReveal(seat, executedYesterday, key);
			case "decouvrir":
				return roleReveal(seat, targets.get(0), key);
			case "grimoire": {
				Info i = new Info();
				i.kind = Info.Kind.GRIMOIRE;
				i.text = "Tu consultes le grimoire.";
				return i;
			}
			case "maitre":
				customMaster().put(seat, targets.get(0));
				return Info.none();
			case "protection":
				if (!impaired(seat)) {
					customProtect().put(seat, targets.get(0));
				}
				return Info.none();
			case "mise_a_mort":
				return goule(seat, targets.get(0));
			case "poison":
				if (!impaired(seat)) {
					customPoison().put(seat, targets.get(0));
				}
				return Info.none();
			case "fausse_info":
				if (!impaired(seat)) {
					customForged().put(seat, targets.get(0));
				}
				return Info.none();
			case "libre": {
				Info i = new Info();
				i.text = "Le Conteur te répond en personne.";
				StringBuilder t = new StringBuilder("Pouvoir libre : " + a.question);
				if (!targets.isEmpty()) {
					t.append(" (désignés : ").append(String.join(", ", targets.stream().map(x -> player(x).name).toList())).append(")");
				}
				if (impaired(seat)) {
					t.append(" — le joueur est ivre ou empoisonné");
				}
				i.truthText = t.toString();
				return i;
			}
			default:
				return Info.none();
		}
	}

	private void demonAttack(int target, Role by) {
		if (safeFromDemon(target)) {
			return;
		}
		if (player(target).role == Role.CHATELAIN && !isPoisoned(target)) {
			Integer other = decide.mayorBounce(this, target);
			if (other != null && other < 0) {
				addLog("L'attaque sur le Châtelain est détournée : personne ne meurt.");
				return;
			}
			if (other != null) {
				addLog("L'attaque sur le Châtelain est détournée vers " + player(other).name + ".");
				if (safeFromDemon(other)) {
					return;
				}
				target = other;
			}
		}
		kill(target, "tué par " + lower(by.displayName));
	}

	/** Fin de la nuit : renvoie les morts de la nuit. */
	public List<Integer> endNight() {
		if (phase != Phase.NIGHT) {
			throw new GameException("Ce n'est pas la nuit.");
		}
		if (bavardDoomed) {
			bavardDoomed = false;
			int b = seatOf(Role.BAVARD);
			if (b >= 0 && player(b).alive && !impaired(b)) {
				kill(b, "il a vu juste");
			}
		}
		day = night;
		newDemon = -1;
		executedYesterday = -1;
		List<Integer> deaths = List.copyOf(deathsTonight);
		checkEvilWin();
		startDay();
		return deaths;
	}

	// ------------------------------------------------------------------
	// Jour
	// ------------------------------------------------------------------
	private void startDay() {
		if (winner != null) {
			return;
		}
		phase = Phase.DAY;
		votersToday().clear();
		nominations.clear();
		hasNominated.clear();
		wasNominated.clear();
		top = -1;
		topVotes = 0;
		tie = false;
		dayOver = false;
		executedToday = -1;
		addLog("Le jour " + day + " se lève.");
	}

	/** Nominations du jour, dans l'ordre : {nominateur, nominé, voix (-1 tant que le vote n'a pas eu lieu)}. */
	public List<int[]> nominationsToday() {
		List<int[]> out = new ArrayList<>();
		nominations.forEach(n -> out.add(n.clone()));
		return out;
	}

	/** Nominations du jour pas encore votées, dans l'ordre où elles ont été faites. */
	public List<Nomination> pendingNominations() {
		List<Nomination> out = new ArrayList<>();
		for (int i = 0; i < nominations.size(); i++) {
			int[] n = nominations.get(i);
			if (n[2] < 0) {
				out.add(new Nomination(i, n[0], n[1], false));
			}
		}
		return out;
	}

	/** Siège « sur le billot » (le plus de voix, au moins le seuil, sans égalité), ou -1. */
	public int onTheBlock() {
		return top >= 0 && !tie && executedToday < 0 ? nominations.get(top)[1] : -1;
	}

	public int blockVotes() {
		return topVotes;
	}

	/** Égalité en tête : personne n'est sur le billot. */
	public boolean blockTie() {
		return tie && topVotes > 0;
	}

	public boolean hasNominatedToday(int seat) {
		return hasNominated.contains(seat);
	}

	public boolean wasNominatedToday(int seat) {
		return wasNominated.contains(seat);
	}

	public Nomination nominate(int nominator, int nominee) {
		if (phase != Phase.DAY || dayOver) {
			throw new GameException("Ce n'est pas le moment de nominer.");
		}
		Seat a = player(nominator);
		Seat b = player(nominee);
		if (b.traveller && b.alive) {
			if (wasNominated.contains(nominee)) {
				throw new GameException("L'exil de ce voyageur a déjà été demandé aujourd'hui.");
			}
			wasNominated.add(nominee);
			addLog(a.name + " demande l'exil de " + b.name + ".");
			nominations.add(new int[] {nominator, nominee, -1});
			return new Nomination(nominations.size() - 1, nominator, nominee, false);
		}
		if (!a.alive) {
			throw new GameException("Les morts ne peuvent pas nominer.");
		}
		if (maxNominations > 0 && nominations.size() >= maxNominations) {
			throw new GameException("Plus d'accusation aujourd'hui : " + maxNominations + " au plus par jour dans ce scénario.");
		}
		if (!b.alive) {
			throw new GameException("On ne peut pas nominer un mort.");
		}
		if (hasNominated.contains(nominator)) {
			throw new GameException("Tu as déjà nominé aujourd'hui.");
		}
		if (wasNominated.contains(nominee)) {
			throw new GameException("Ce joueur a déjà été nominé aujourd'hui.");
		}
		hasNominated.add(nominator);
		wasNominated.add(nominee);
		addLog(a.name + " nomine " + b.name + ".");
		if (b.role == Role.PELERINE && !pelerineUsed) {
			pelerineUsed = true;
			if (!isPoisoned(nominee) && reg(nominator, "townsfolk", new Ctx("pelerine"))) {
				execute(nominator, "pouvoir de la Pèlerine");
				dayOver = true;
				return new Nomination(-1, nominator, nominee, true);
			}
		}
		Ability reprisal = effect(b.role, "represailles");
		if (reprisal != null && onceUsed().add(nominee)) {
			if (!isPoisoned(nominee) && reg(nominator, reprisal.regKey(), new Ctx("represailles-" + b.role.customId))) {
				execute(nominator, "pouvoir de " + lower(b.role.displayName));
				dayOver = true;
				return new Nomination(-1, nominator, nominee, true);
			}
		}
		nominations.add(new int[] {nominator, nominee, -1});
		return new Nomination(nominations.size() - 1, nominator, nominee, false);
	}

	/** Annule une nomination tant qu'elle n'a pas été votée : accusateur et accusé retrouvent leurs droits. */
	public void cancelNomination(int index) {
		if (index < 0 || index >= nominations.size() || nominations.get(index)[2] >= 0) {
			throw new GameException("Cette nomination ne peut plus être annulée.");
		}
		int[] n = nominations.remove(index);
		if (top > index) {
			top--;
		}
		hasNominated.remove(n[0]);
		wasNominated.remove(n[1]);
		addLog("Le Conteur annule la nomination de " + player(n[1]).name + " par " + player(n[0]).name + ".");
	}

	/** Le Conteur gracie le joueur sur le billot : personne ne sera exécuté pour ce vote. */
	public String pardon() {
		if (phase != Phase.DAY || dayOver) {
			throw new GameException("On ne gracie que de jour.");
		}
		int seat = onTheBlock();
		if (seat < 0) {
			throw new GameException("Personne n'est sur le billot.");
		}
		top = -1;
		topVotes = 0;
		tie = false;
		addLog("Le Conteur gracie " + player(seat).name + ".");
		return player(seat).name;
	}

	/** Le Conteur déclare la victoire d'un camp. */
	public void declareWinner(Team team) {
		if (winner != null) {
			throw new GameException("La partie est déjà terminée.");
		}
		setWinner(team, "Décision du Conteur.");
	}

	/** voters : sièges ayant levé la main. */
	public VoteResult vote(int index, List<Integer> voters) {
		if (phase != Phase.DAY || dayOver) {
			throw new GameException("On ne vote que de jour.");
		}
		if (index < 0 || index >= nominations.size() || nominations.get(index)[2] >= 0) {
			throw new GameException("Nomination inconnue ou déjà votée.");
		}
		List<Integer> counted = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		int exileOf = nominations.get(index)[1];
		if (player(exileOf).traveller) {
			// Exil : tout le monde vote, morts compris, sans dépenser son vote de fantôme.
			for (int v : voters) {
				if (v >= 0 && v < size() && !counted.contains(v)) {
					counted.add(v);
				}
			}
			int threshold = (aliveCount() + 1) / 2;
			nominations.get(index)[2] = counted.size();
			boolean exiled = counted.size() >= threshold;
			addLog("Exil de " + player(exileOf).name + " : " + counted.size() + " voix (seuil " + threshold + ")"
				+ (exiled ? ", exilé." : ", reste."));
			if (exiled) {
				player(exileOf).ghostVote = false;
				kill(exileOf, "exil");
			}
			return new VoteResult(counted.size(), threshold, warnings, false, exiled);
		}
		for (int v : voters) {
			if (v < 0 || v >= size() || counted.contains(v)) {
				continue;
			}
			Seat p = player(v);
			if (p.alive) {
				counted.add(v);
			} else if (p.ghostVote) {
				p.ghostVote = false;
				counted.add(v);
			} else {
				warnings.add(p.name + " n'a plus de vote.");
			}
		}
		// L'Écuyer : un vote « illégal » est compté quand même ; le Conteur est prévenu.
		int e = seatOf(Role.ECUYER);
		if (e >= 0 && player(e).alive && counted.contains(e) && master >= 0 && !counted.contains(master)) {
			warnings.add("L'Écuyer a voté sans son maître.");
		}
		for (var m : customMaster().entrySet()) {
			if (player(m.getKey()).alive && counted.contains(m.getKey()) && !counted.contains(m.getValue())) {
				warnings.add(player(m.getKey()).name + " a voté sans son maître.");
			}
		}
		for (int v : counted) {
			if (!votersToday().contains(v)) {
				votersToday().add(v);
			}
		}
		int count = counted.size();
		int threshold = voteThreshold();
		nominations.get(index)[2] = count;
		if (count >= threshold) {
			if (count > topVotes) {
				top = index;
				topVotes = count;
				tie = false;
			} else if (count == topVotes) {
				tie = true;
			}
		}
		addLog("Vote sur " + player(nominations.get(index)[1]).name + " : " + count + " voix (seuil " + threshold + ").");
		return new VoteResult(count, threshold, warnings, top == index && !tie, false);
	}

	private void execute(int seat, String cause) {
		Seat p = player(seat);
		executedToday = seat;
		executedYesterday = seat;
		addLog(p.name + " est exécuté (" + cause + ").");
		boolean martyr = p.alive && (p.role == Role.MARTYR || model(p.role, "martyr")) && !isPoisoned(seat);
		kill(seat, "exécution");
		if (martyr) {
			setWinner(Team.EVIL, "Le Martyr a été exécuté.");
		}
	}

	public DayEnd endDay() {
		if (phase != Phase.DAY) {
			throw new GameException("Ce n'est pas le jour.");
		}
		if (executedToday < 0 && top >= 0 && !tie && player(nominations.get(top)[1]).alive) {
			execute(nominations.get(top)[1], "vote du village");
		}
		if (winner == null && executedToday < 0 && aliveResidents() == 3) {
			int c = seatOf(Role.CHATELAIN);
			if (c >= 0 && player(c).alive && !isPoisoned(c)) {
				setWinner(Team.GOOD, "Le Châtelain a survécu à trois sans exécution.");
			}
		}
		checkEvilWin();
		dayOver = true;
		return new DayEnd(executedToday, winner);
	}

	/** Tir de l'Arbalétrier. N'importe qui peut « tirer » pour bluffer : sans effet. */
	public boolean slayerShot(int shooter, int target) {
		if (phase != Phase.DAY || dayOver) {
			throw new GameException("Le tir n'est possible que de jour.");
		}
		Seat p = player(shooter);
		if (!p.alive) {
			throw new GameException("Les morts ne peuvent pas tirer.");
		}
		addLog(p.name + " tire sur " + player(target).name + ".");
		Ability shot = effect(p.believed, "tir");
		String what;
		String cause;
		if (p.believed == Role.ARBALETRIER) {
			if (arbaletrierUsed) {
				return false;
			}
			arbaletrierUsed = true;
			what = "demon";
			cause = "carreau de l'Arbalétrier";
		} else if (shot != null) {
			if (!onceUsed().add(shooter)) {
				return false;
			}
			what = shot.regKey();
			cause = "tir de " + lower(p.believed.displayName);
		} else {
			return false;
		}
		if (impaired(shooter) || !player(target).alive) {
			return false;
		}
		if (reg(target, what, new Ctx("tir-" + shooter))) {
			kill(target, cause);
			checkEvilWin();
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------
	// Interventions du Conteur
	// ------------------------------------------------------------------
	/** Mort décidée par le Conteur (renvoie false si le joueur était déjà mort). */
	public boolean storytellerKill(int seat) {
		if (winner != null) {
			throw new GameException("La partie est terminée.");
		}
		boolean killed = kill(seat, "décision du Conteur");
		checkEvilWin();
		return killed;
	}

	public void revive(int seat) {
		Seat p = player(seat);
		if (p.alive) {
			throw new GameException(p.name + " est vivant.");
		}
		p.alive = true;
		p.ghostVote = true;
		addLog(p.name + " revient à la vie (décision du Conteur).");
	}

	public void setPoisoned(int seat) {
		stPoisoned = seat;
		addLog("Le Conteur empoisonne " + player(seat).name + ".");
	}

	/** Change un rôle en cours de partie (le Conteur assume la cohérence). */
	public void forceReassign(int seat, Role role, Role believed) {
		Seat p = player(seat);
		p.role = role;
		p.believed = role == Role.SOMNAMBULE && believed != null ? believed : role;
		addLog("Le Conteur change le rôle de " + p.name + " : " + role.displayName + ".");
	}

	// ------------------------------------------------------------------
	// Grimoire
	// ------------------------------------------------------------------
	public List<String> grimoireLines() {
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < size(); i++) {
			Seat p = seats.get(i);
			List<String> marks = new ArrayList<>();
			if (isPoisoned(i)) {
				marks.add("empoisonné");
			}
			if (p.role == Role.SOMNAMBULE) {
				marks.add("ivre");
			}
			if (protectedSeat == i) {
				marks.add("protégé");
			}
			if (redHerring == i) {
				marks.add("faux présage");
			}
			if (master == i) {
				marks.add("maître de l'Écuyer");
			} else if (customMaster().containsValue(i)) {
				marks.add("maître");
			}
			if (!p.alive && p.ghostVote) {
				marks.add("vote de fantôme");
			}
			String role = p.role.displayName;
			if (p.believed != p.role) {
				role += " (croit être " + p.believed.displayName + ")";
			}
			lines.add((i + 1) + ". " + p.name + (p.alive ? "" : " (mort)") + " — " + role
				+ (marks.isEmpty() ? "" : " [" + String.join(", ", marks) + "]"));
		}
		lines.add("Bluffs du Démon : " + String.join(", ", bluffs.stream().map(r -> r.displayName).toList()));
		lines.add("Pèlerine utilisée : " + (pelerineUsed ? "oui" : "non")
			+ " — Tir de l'Arbalétrier : " + (arbaletrierUsed ? "oui" : "non"));
		return lines;
	}
}
