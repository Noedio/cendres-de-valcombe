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

		Seat(String name) {
			this.name = name;
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
			return Team.of(role);
		}
	}

	public record Nomination(int index, int nominator, int nominee, boolean pelerine) {
	}

	public record VoteResult(int count, int threshold, List<String> warnings, boolean onBlock) {
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
	private int poisoned = -1;
	/** Joueur désigné par la Faussaire : ses informations de la nuit sont fausses. */
	private int forged = -1;
	private int protectedSeat = -1;
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

	// ------------------------------------------------------------------
	// Mise en place
	// ------------------------------------------------------------------
	private Set<Role> script = Setup.fullScript();

	public void setupRandom() {
		setupRandom(Setup.fullScript());
	}

	/** Tire les rôles parmi ceux du script. */
	public void setupRandom(Set<Role> allowed) {
		String problem = Setup.check(allowed, size());
		if (problem != null) {
			throw new GameException(problem);
		}
		List<Setup.Entry> bag = Setup.drawBag(size(), rng, allowed);
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
		if (redHerring < 0 && wakerSeat(Role.ASTROLOGUE) >= 0) {
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
		Distribution.Counts c = Distribution.counts(size());
		if (c == null) {
			return null;
		}
		int[] have = new int[4];
		boolean margrave = false;
		for (Seat p : seats) {
			have[p.role.type.ordinal()]++;
			margrave |= p.role == Role.MARGRAVE;
		}
		int bonus = margrave ? 2 : 0;
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
		return poisoned == seat;
	}

	/** Ivre ou empoisonné : le pouvoir ne fonctionne pas. */
	public boolean impaired(int seat) {
		return isDrunk(seat) || isPoisoned(seat) || (phase == Phase.NIGHT && forged == seat);
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
		if (p.role == Role.MOUCHARD && !isPoisoned(seat)) {
			canFlip = Set.of("evil", "minion", "townsfolk", "outsider").contains(what);
		} else if (p.role == Role.EXCOMMUNIE && !isPoisoned(seat)) {
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
		if (p.role == Role.MOUCHARD && !isPoisoned(seat) && decide.misregister(this, seat, "role", key)) {
			List<Role> pool = new ArrayList<>(Role.ofType(RoleType.CITADIN));
			pool.addAll(Role.ofType(RoleType.ETRANGER));
			return rng.pick(pool);
		}
		if (p.role == Role.EXCOMMUNIE && !isPoisoned(seat) && decide.misregister(this, seat, "role", key)) {
			List<Role> pool = new ArrayList<>(Role.ofType(RoleType.SBIRE));
			pool.add(Role.GOULE);
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
		int aliveBefore = aliveCount();
		p.alive = false;
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
		if (p.role.type == RoleType.DEMON) {
			onDemonDeath(cause, aliveBefore);
		}
		return true;
	}

	private void makeDemon(int seat) {
		Seat p = player(seat);
		addLog(p.name + " (" + p.role.displayName + ") devient la Goule.");
		p.role = Role.GOULE;
		p.believed = Role.GOULE;
		newDemon = seat;
	}

	private void onDemonDeath(String cause, int aliveBefore) {
		int h = seatOf(Role.HERITIERE);
		if (h >= 0 && player(h).alive && !isPoisoned(h) && aliveBefore >= 5) {
			makeDemon(h);
			return;
		}
		if (cause.equals("suicide")) {
			List<Integer> candidates = minionSeats().stream().filter(s -> player(s).alive).toList();
			Integer successor = decide.starpassSuccessor(this, candidates);
			if (successor != null) {
				makeDemon(successor);
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
		if (winner == null && demonSeat() >= 0 && aliveCount() <= 2) {
			setWinner(Team.EVIL, "Il ne reste que deux joueurs vivants.");
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
		forged = -1;
		protectedSeat = -1;
		deathsTonight = new ArrayList<>();
		fauconnierWakes = -1;
		List<Slot> q = new ArrayList<>();
		if (night == 1) {
			q.add(new Slot(Step.Kind.MINION_INFO, null));
			q.add(new Slot(Step.Kind.DEMON_INFO, null));
			for (Role r : Role.firstNightOrder()) {
				q.add(new Slot(Step.Kind.ROLE, r));
			}
		} else {
			for (Role r : Role.otherNightsOrder()) {
				q.add(r == Role.HERITIERE ? new Slot(Step.Kind.NEW_DEMON, null) : new Slot(Step.Kind.ROLE, r));
			}
		}
		queue = q;
		queueIndex = 0;
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
		if (role == Role.EMBAUMEUR && executedYesterday < 0) {
			return null;
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
				return step;
			}
		}
		currentStep = null;
		return null;
	}

	public void validateTargets(Step step, List<Integer> targets) {
		if (!step.isChoose()) {
			return;
		}
		if (targets.size() != step.choose()) {
			throw new GameException("Il faut désigner " + step.choose() + " joueur(s).");
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
				i.text = "Tu es désormais la Goule.";
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
				poisoned = targets.get(0);
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
			default:
				return Info.none();
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
				role = rng.pick(Role.ofType(type));
			}
			truth = pairInfo(seat, role, c);
		}
		if (impaired(seat) || (truth == null && !allowNone)) {
			List<Integer> others = allSeats().stream().filter(s -> s != seat).toList();
			Info shown = pairInfo(seat, rng.pick(Role.ofType(type)), rng.pick(others));
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
		Role shown = impaired(seat) ? rng.pick(List.of(Role.values())) : truth;
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
				i.text = "La Goule s'est sacrifiée : tu es désormais la Goule.";
				return i;
			}
			return Info.none();
		}
		demonAttack(target);
		return Info.none();
	}

	private boolean safeFromDemon(int t) {
		Seat p = player(t);
		return !p.alive || protectedSeat == t || (p.role == Role.TEMPLIER && !isPoisoned(t));
	}

	private void demonAttack(int target) {
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
		kill(target, "tué par la Goule");
	}

	/** Fin de la nuit : renvoie les morts de la nuit. */
	public List<Integer> endNight() {
		if (phase != Phase.NIGHT) {
			throw new GameException("Ce n'est pas la nuit.");
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
		if (!a.alive) {
			throw new GameException("Les morts ne peuvent pas nominer.");
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
		nominations.add(new int[] {nominator, nominee, -1});
		return new Nomination(nominations.size() - 1, nominator, nominee, false);
	}

	/** voters : sièges ayant levé la main. */
	public VoteResult vote(int index, List<Integer> voters) {
		if (index < 0 || index >= nominations.size() || nominations.get(index)[2] >= 0) {
			throw new GameException("Nomination inconnue ou déjà votée.");
		}
		List<Integer> counted = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
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
		int count = counted.size();
		int threshold = (aliveCount() + 1) / 2;
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
		return new VoteResult(count, threshold, warnings, top == index && !tie);
	}

	private void execute(int seat, String cause) {
		Seat p = player(seat);
		executedToday = seat;
		executedYesterday = seat;
		addLog(p.name + " est exécuté (" + cause + ").");
		boolean martyr = p.role == Role.MARTYR && !isPoisoned(seat);
		kill(seat, "exécution");
		if (martyr) {
			setWinner(Team.EVIL, "Le Martyr a été exécuté.");
		}
	}

	public DayEnd endDay() {
		if (phase != Phase.DAY) {
			throw new GameException("Ce n'est pas le jour.");
		}
		if (executedToday < 0 && top >= 0 && !tie) {
			execute(nominations.get(top)[1], "vote du village");
		}
		if (winner == null && executedToday < 0 && aliveCount() == 3) {
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
		if (p.believed != Role.ARBALETRIER || arbaletrierUsed) {
			return false;
		}
		arbaletrierUsed = true;
		if (impaired(shooter) || !player(target).alive) {
			return false;
		}
		if (reg(target, "demon", new Ctx("arbaletrier"))) {
			kill(target, "carreau de l'Arbalétrier");
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
		poisoned = seat;
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
			if (poisoned == i) {
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
