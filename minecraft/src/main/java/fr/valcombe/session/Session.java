package fr.valcombe.session;

import fr.valcombe.rules.Distribution;
import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Info;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleType;
import fr.valcombe.rules.Rng;
import fr.valcombe.rules.Step;
import fr.valcombe.rules.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Une table de jeu : inscriptions, Conteur, déroulement des nuits et des jours.
 * Chaque action attendue d'un joueur devient une « invite » (Prompt) que le serveur
 * affiche en menu ; des boutons dans le chat permettent aussi de répondre.
 * Les joueurs fictifs (bots) répondent seuls à leurs invites, pour tester une partie.
 */
public final class Session {
	public enum Mode { AUTO, HUMAIN }

	final Host host;
	private final Rng rng;
	public final Settings settings = new Settings();
	final List<String> lobby = new ArrayList<>();
	final Set<String> bots = new LinkedHashSet<>();
	/** Joueurs absents dont un joueur fictif tient la place jusqu'à leur retour. */
	final Set<String> standIns = new LinkedHashSet<>();
	/** Joueur que le Conteur reçoit dans la salle de convocation, ou null. */
	String summoned;
	/** Joueurs à qui le Conteur n'a pas encore remis leur rôle (remise en salle de convocation). */
	final Set<String> unbriefed = new LinkedHashSet<>();
	/** Partie en pause : les minuteurs, la nuit et le vote attendent. */
	boolean paused;
	/** Spectateurs : ils regardent la partie sans y jouer. */
	final Set<String> spectators = new LinkedHashSet<>();
	/** Jetons de rappel posés par le Conteur sur les sièges (« ivre », « a choisi X »…). */
	final Map<String, List<String>> reminders = new LinkedHashMap<>();
	/** Partie d'entraînement : le joueur guidé, et les conseils déjà donnés. */
	String trainee;
	private final Set<String> tipsGiven = new java.util.HashSet<>();
	final Map<String, Prompt> prompts = new HashMap<>();
	String storyteller;
	Mode mode = Mode.AUTO;
	/** Consignes du Conteur humain et notes par joueur. */
	public final HumanDecisions policies = new HumanDecisions();
	final Map<String, String> notes = new HashMap<>();
	/** Script de la prochaine partie : les rôles qui peuvent être tirés. */
	java.util.EnumSet<Role> script = Role.classic();
	String scriptName = "Les Cendres de Valcombe";
	List<String> savedScripts = List.of();
	/** Groupes de rôles de l'Atelier (nom → identifiants), proposés dans l'éditeur de script. */
	Map<String, List<String>> roleGroups = Map.of();
	Game game;
	Game lastGame;

	// Nuit : étape en attente
	Step pendingStep;
	String pendingChooser;
	final Set<Integer> selected = new LinkedHashSet<>();
	private String waitingAck;
	private String deliverTo;
	private String deliverTitle;
	private String deliverText;
	private String deliverTruth;
	private int nightToken;

	// Jour : vote en cours
	Game.Nomination voteNom;
	/** Main levée ou non, modifiable jusqu'au passage de l'aiguille. */
	final Map<String, Boolean> answers = new LinkedHashMap<>();
	final Set<String> waitingVote = new LinkedHashSet<>();
	/** Sièges dans l'ordre de passage de l'aiguille, position actuelle, mains verrouillées. */
	final List<Integer> clockOrder = new ArrayList<>();
	int clockPos = -1;
	final List<Integer> lockedHands = new ArrayList<>();
	boolean defense;
	int defenseLeft;
	/** Le Conteur a arrêté le minuteur de la défense : le vote attend qu'il le lance. */
	boolean defenseHold;
	/** Mode manuel, la nuit : l'étape suivante attend que le Conteur la lance. */
	boolean waitingNext;
	/** Nominations ouvertes par le Conteur (automatiquement en mode automatique). */
	boolean nominationsOpen;
	private int voteToken;

	public Session(Host host) {
		this(host, Rng.of(new Random()));
	}

	public Session(Host host, Rng rng) {
		this.host = host;
		this.rng = rng;
		this.brain = new BotBrain(this, rng);
	}

	/** Les joueurs fictifs : annonces, accusations et votes. */
	private final BotBrain brain;
	/** Le pupitre du Conteur : actions à la main, jetons par rôle, temps de parole. */
	final Desk desk = new Desk(this);

	Rng rng() {
		return rng;
	}

	// ------------------------------------------------------------------
	// État lisible par les menus
	// ------------------------------------------------------------------
	public Game game() {
		return game;
	}

	public Game lastGame() {
		return lastGame;
	}

	public Settings settings() {
		return settings;
	}

	public String storyteller() {
		return storyteller;
	}

	public Mode mode() {
		return mode;
	}

	public List<String> lobby() {
		return List.copyOf(lobby);
	}

	public boolean isBot(String name) {
		return bots.contains(name);
	}

	/** Micro coupé ? La nuit, pour tous les joueurs de la partie sauf le Conteur. */
	public boolean isMuted(String name) {
		Game g = game;
		return settings.nightMute && g != null && g.phase() == Game.Phase.NIGHT
			&& !name.equals(storyteller) && g.seatOf(name) >= 0;
	}

	// ------------------------------------------------------------------
	// Voix : chuchoter, parler, crier ; le Conseil s'entend en entier ; les morts s'y taisent
	// ------------------------------------------------------------------
	public static final List<String> VOICE_LEVELS = List.of("chuchoter", "parler", "crier");
	/** Niveau de voix choisi par chaque joueur (absent : « parler »). */
	final Map<String, String> voiceLevel = new HashMap<>();
	/** Morts à qui un vivant a donné la parole pour le Conseil (jusqu'au soir). */
	final Set<String> floor = new LinkedHashSet<>();

	/**
	 * Règle de voix d'un joueur. targets non null : seuls ces joueurs l'entendent (à pleine voix) ;
	 * sinon range > 0 : on l'entend dans ce rayon (en blocs) ; null : vocal de proximité normal.
	 */
	public record VoiceRule(List<String> targets, double range) {
	}

	public String voiceLevel(String name) {
		return voiceLevel.getOrDefault(name, "parler");
	}

	/** Change le niveau de voix (« chuchoter », « parler », « crier », ou « suivant » pour passer au suivant). */
	public String setVoiceLevel(String name, String level) {
		String l = level.toLowerCase(java.util.Locale.ROOT);
		if (l.equals("suivant")) {
			l = VOICE_LEVELS.get((VOICE_LEVELS.indexOf(voiceLevel(name)) + 1) % VOICE_LEVELS.size());
		}
		if (!VOICE_LEVELS.contains(l)) {
			throw new GameException("Voix : chuchoter, parler ou crier.");
		}
		if (l.equals("parler")) {
			voiceLevel.remove(name);
		} else {
			voiceLevel.put(name, l);
		}
		updateBoards();
		return switch (l) {
			case "chuchoter" -> inCouncil() ? "Tu chuchotes : seuls tes deux voisins de trône t'entendent."
				: "Tu chuchotes : on t'entend à " + settings.whisperRange + " blocs.";
			case "crier" -> inCouncil() ? "Tu cries (pendant le Conseil, tout le monde t'entend déjà)."
				: "Tu cries : on t'entend à " + settings.shoutRange + " blocs.";
			default -> "Tu parles normalement.";
		};
	}

	/** Le Conseil est en cours : accusations ouvertes, ou votes. */
	public boolean inCouncil() {
		Game g = game;
		return g != null && g.phase() == Game.Phase.DAY && !g.dayOver() && (nominationsOpen || councilVoting || voteNom != null);
	}

	/** Un vivant donne (ou reprend) la parole à un mort pour le Conseil. */
	public String giveFloor(String name, String target) {
		Game g = requireGame();
		int a = g.seatOf(name);
		boolean st = name.equals(storyteller);
		if (!st && (a < 0 || !g.player(a).alive())) {
			throw new GameException("Seul un vivant (ou le Conteur) peut donner la parole à un mort.");
		}
		int b = seatByName(target);
		Game.Seat dead = g.player(b);
		if (dead.alive()) {
			throw new GameException(dead.name + " est vivant : il a déjà la parole.");
		}
		if (floor.remove(dead.name)) {
			announce(name + " reprend la parole à " + dead.name + ".");
			tell(dead.name, "Tu n'as plus la parole pendant le Conseil.");
		} else {
			floor.add(dead.name);
			announce(name + " donne la parole à " + dead.name + ".");
			tell(dead.name, "Tu as la parole pendant le Conseil.");
		}
		updateBoards();
		return "";
	}

	public boolean hasFloor(String name) {
		return floor.contains(name);
	}

	/** Règle de voix complète d'un joueur : moments du jeu, Conseil, puis niveau de voix choisi. */
	public VoiceRule voiceRule(String name) {
		List<String> special = voiceTargets(name);
		if (special != null) {
			return new VoiceRule(special, 0);
		}
		Game g = game;
		int s = g == null ? -1 : g.seatOf(name);
		if (s >= 0 && inCouncil()) {
			// Pendant le Conseil, tout le village s'entend ; un mort se tait tant qu'on ne lui a pas donné la parole.
			if (!g.player(s).alive() && !floor.contains(name)) {
				return new VoiceRule(List.of(), 0);
			}
			if (voiceLevel(name).equals("chuchoter")) {
				List<String> near = new ArrayList<>();
				for (int d : new int[] {-1, 1}) {
					String n = g.player(Math.floorMod(s + d, g.size())).name;
					if (!n.equals(name) && !near.contains(n)) {
						near.add(n);
					}
				}
				return new VoiceRule(near, 0);
			}
			List<String> all = new ArrayList<>(realNames());
			if (storyteller != null && !all.contains(storyteller)) {
				all.add(storyteller);
			}
			spectators.forEach(n -> {
				if (!all.contains(n)) {
					all.add(n);
				}
			});
			all.remove(name);
			return new VoiceRule(all, 0);
		}
		return switch (voiceLevel(name)) {
			case "chuchoter" -> new VoiceRule(null, settings.whisperRange);
			case "crier" -> new VoiceRule(null, settings.shoutRange);
			default -> null;
		};
	}

	/**
	 * Vocal piloté par le jeu, la nuit : à qui parle ce joueur ? null : vocal normal ;
	 * liste vide : personne ne l'entend. Le Conteur parle aux joueurs réveillés, et
	 * chaque joueur réveillé ne parle qu'au Conteur.
	 */
	public List<String> voiceTargets(String name) {
		Game g = game;
		if (silencedByElder(name)) {
			return List.of();
		}
		// Salle de convocation : le Conteur et le joueur reçu ne s'entendent qu'entre eux.
		if (summoned != null && storyteller != null) {
			if (name.equals(storyteller)) {
				return List.of(summoned);
			}
			if (name.equals(summoned)) {
				return List.of(storyteller);
			}
		}
		if (desk.broadcast && g != null && g.phase() == Game.Phase.DAY && name.equals(storyteller) && social.aside == null) {
			return realNames(); // la voix du Conteur porte à tout le village
		}
		// Aparté de jour : le Conteur et le joueur pris à part ne s'entendent qu'entre eux.
		if (social.aside != null && storyteller != null && g != null && g.phase() == Game.Phase.DAY) {
			if (name.equals(storyteller)) {
				return List.of(social.aside);
			}
			if (name.equals(social.aside)) {
				return List.of(storyteller);
			}
		}
		if (g != null && g.phase() == Game.Phase.DAY && social.talks.containsKey(name) && !name.equals(social.aside)) {
			return List.of(social.talks.get(name));
		}
		if (!settings.nightMute || g == null || g.phase() != Game.Phase.NIGHT) {
			return null;
		}
		List<String> awake = new ArrayList<>();
		for (var e : prompts.entrySet()) {
			if (!bots.contains(e.getKey()) && !(e.getValue() instanceof Prompt.Vote)) {
				awake.add(e.getKey());
			}
		}
		for (String n : desk.awake) {
			if (!awake.contains(n)) {
				awake.add(n);
			}
		}
		if (name.equals(storyteller)) {
			return awake;
		}
		if (g.seatOf(name) < 0) {
			return null; // spectateurs et visiteurs : vocal normal entre eux
		}
		if (awake.contains(name) && storyteller != null && host.online(storyteller)) {
			return List.of(storyteller);
		}
		return List.of();
	}

	/** Votants de chaque nomination du jour (numéro de nomination → noms). */
	final Map<Integer, List<String>> votersOf = new java.util.HashMap<>();

	/** Version du mod sur le serveur (envoyée aux joueurs pour repérer un serveur pas à jour). */
	public String modVersion = "?";

	/** Carnet d'un joueur (statistiques du serveur), fourni par le serveur ; null dans les tests. */
	public java.util.function.Function<String, Map<String, Object>> carnetSource;

	// ------------------------------------------------------------------
	// Figures neutres : règles du Conteur, connues de tous
	// ------------------------------------------------------------------
	/** Figures disponibles : identifiant → { nom, texte }. */
	public static final Map<String, String[]> FIGURES = new LinkedHashMap<>();

	static {
		FIGURES.put("sentinelle", new String[] {"La Sentinelle", "Il peut y avoir un Étranger de plus ou de moins."});
		FIGURES.put("doyen", new String[] {"Le Doyen",
			"Les deux premières minutes de chaque jour, les joueurs aguerris (3 parties ou plus) se taisent."});
		FIGURES.put("sablier", new String[] {"Le Sablier", "Chaque journée dure dix minutes au plus : la cloche la clôt."});
	}

	final Set<String> figures = new LinkedHashSet<>();
	private long dayStartedAt;
	private int sandToken;
	/** Temps ajouté au Sablier par le Conteur (/vc prolonger). */
	private int sandExtraTicks;

	public java.util.Set<String> figures() {
		return java.util.Collections.unmodifiableSet(figures);
	}

	public String setFigure(String id, boolean on) {
		if (!FIGURES.containsKey(id)) {
			throw new GameException("Figures : " + String.join(", ", FIGURES.keySet()) + ".");
		}
		if (game != null) {
			throw new GameException("Les figures se choisissent entre deux parties.");
		}
		if (on ? figures.add(id) : figures.remove(id)) {
			announce((on ? "Figure neutre ajoutée : " : "Figure neutre retirée : ") + FIGURES.get(id)[0] + ". " + FIGURES.get(id)[1]);
		}
		return "";
	}

	/** À l'aube : le Doyen fait taire les aguerris, le Sablier compte le temps de la journée. */
	private void dawnFigures(Game g) {
		dayStartedAt = System.currentTimeMillis();
		if (figures.contains("doyen")) {
			announce("Le Doyen : les joueurs aguerris se taisent deux minutes.");
		}
		if (figures.contains("sablier")) {
			int token = ++sandToken;
			sandExtraTicks = 0;
			int day = g.day();
			host.schedule(20 * 60 * 9, () -> {
				if (token == sandToken && game == g && g.day() == day && !g.dayOver() && sandExtraTicks == 0 && !paused) {
					host.title(realNames(), "Le Sablier", "Plus qu'une minute avant la fin du jour");
				}
			});
			host.schedule(20 * 60 * 10, () -> sandglass(token, g, day));
		}
	}

	private void sandglass(int token, Game g, int day) {
		if (token != sandToken || game != g || g.day() != day || g.dayOver() || g.phase() != Game.Phase.DAY) {
			return;
		}
		if (voteNom != null || paused) {
			host.schedule(400, () -> sandglass(token, g, day)); // on laisse finir le vote en cours, ou la pause
			return;
		}
		if (sandExtraTicks > 0) {
			int extra = sandExtraTicks;
			sandExtraTicks = 0;
			host.schedule(extra, () -> sandglass(token, g, day));
			return;
		}
		announce("Le Sablier est vide : la journée s'achève.");
		try {
			endDay();
		} catch (GameException ignored) {
			// la journée a été close entre-temps
		}
	}

	/** Le Doyen : ce joueur est-il réduit au silence en ce moment ? */
	private boolean silencedByElder(String name) {
		Game g = game;
		return figures.contains("doyen") && g != null && g.phase() == Game.Phase.DAY
			&& System.currentTimeMillis() - dayStartedAt < 120_000 && !name.equals(storyteller)
			&& !bots.contains(name) && host.gamesPlayed(name) >= 3;
	}

	// ------------------------------------------------------------------
	// Voyageurs : joueurs arrivés en cours de partie
	// ------------------------------------------------------------------
	/**
	 * Un joueur rejoint la partie en cours comme voyageur. camp : « bien », « mal », ou null
	 * (Conteur automatique : le bien le plus souvent).
	 */
	public String addTraveller(String name, String camp) {
		Game g = requireGame();
		if (g.winner() != null) {
			throw new GameException("La partie est terminée.");
		}
		if (bots.contains(name) || !host.online(name)) {
			throw new GameException(name + " doit être connecté au jeu.");
		}
		if (name.equals(storyteller)) {
			throw new GameException("Le Conteur ne joue pas.");
		}
		fr.valcombe.rules.Team team = camp == null ? (rng.chance(0.25) ? fr.valcombe.rules.Team.EVIL : fr.valcombe.rules.Team.GOOD)
			: camp.startsWith("m") ? fr.valcombe.rules.Team.EVIL : fr.valcombe.rules.Team.GOOD;
		g.addTraveller(name, team);
		spectators.remove(name);
		host.spectator(name, false);
		host.addSeat(name);
		boolean evil = team == fr.valcombe.rules.Team.EVIL;
		tell(name, new Msg().colored("[Valcombe] Tu arrives à Valcombe en voyageur, dans le camp du "
			+ (evil ? "mal" : "bien") + ". Tu votes et tu peux accuser ; le village peut t'exiler. "
			+ "Pour repartir : /vc voyageur partir.", evil ? "red" : "aqua"));
		if (evil) {
			int d = g.demonSeat();
			if (d >= 0) {
				tell(name, new Msg().colored("[Valcombe] Le Démon est " + g.player(d).name + ".", "red"));
				tell(g.player(d).name, new Msg().colored("[Valcombe] " + name + " est un voyageur du mal : il est de ton côté.", "red"));
			}
		}
		showRole(name, fr.valcombe.rules.Role.VOYAGEUR);
		announce(name + " arrive à Valcombe : c'est un voyageur.");
		host.sound(realNames(), "bell");
		save();
		updateBoards();
		return "";
	}

	/** Le voyageur repart. */
	public String travellerLeaves(String name) {
		Game g = requireGame();
		int s = g.seatOf(name);
		if (s < 0 || !g.player(s).traveller()) {
			throw new GameException("Tu n'es pas un voyageur.");
		}
		g.travellerLeaves(s);
		announce(name + " quitte Valcombe.");
		save();
		updateBoards();
		if (g.winner() != null) {
			finish();
		}
		return "";
	}

	// ------------------------------------------------------------------
	// Scénarios (Atelier)
	// ------------------------------------------------------------------
	/** Scénario de la prochaine partie (ou en cours), ou null. */
	ScenarioDef scenario;
	private List<ScenarioDef> scenarios = List.of();

	/** Scénarios enregistrés (fournis par le serveur). */
	public void setScenarios(List<ScenarioDef> list) {
		scenarios = List.copyOf(list);
		if (scenario != null) {
			scenario = scenarios.stream().filter(d -> d.id.equals(scenario.id)).findFirst().orElse(scenario);
		}
	}

	public List<ScenarioDef> scenarios() {
		return scenarios;
	}

	public ScenarioDef scenario() {
		return scenario;
	}

	/** Choisit le scénario de la prochaine partie (« aucun » : retour au script libre). */
	public String useScenario(String id) {
		if (game != null) {
			throw new GameException("Le scénario se choisit entre deux parties.");
		}
		if (id == null || id.equals("aucun")) {
			scenario = null;
			announce("Plus de scénario : script et réglages libres.");
			return "";
		}
		ScenarioDef d = scenarios.stream().filter(x -> x.id.equalsIgnoreCase(id)).findFirst()
			.orElseThrow(() -> new GameException("Scénario inconnu : " + id));
		String problem = d.problem();
		if (problem != null) {
			throw new GameException("Scénario « " + d.nom + " » injouable : " + problem);
		}
		// La carte du scénario n'est rechargée que si ce n'est pas déjà elle, intacte, qui est en place :
		// choisir deux fois le même scénario ne rase pas le village une seconde fois.
		String map = d.carte == null || d.carte.isBlank() || d.carte.equals(host.mapInPlace()) ? "" : host.loadMap(d.carte);
		script = java.util.EnumSet.copyOf(d.script());
		scriptName = d.nom;
		ScenarioDef.Reglages r = d.reglages;
		settings.nominationsFirst = r.conseil;
		settings.councilSeconds = Math.max(0, r.dureeConseil);
		settings.absoluteMajority = d.regles.majoriteAbsolue;
		settings.discussionMinutes = Math.max(0, d.regles.discussionMinutes);
		settings.evilMajorityWins = d.regles.victoireMalMajoritaire;
		settings.defenseSeconds = r.defense;
		settings.clockTicks = (int) Math.round(r.horloge * 20);
		settings.nightHouses = r.nuitMaisons;
		settings.nightFreeze = r.nuitImmobile;
		settings.nightDark = r.nuitSombre;
		settings.nightMute = r.nuitMuette;
		settings.deadHat = r.coiffe == null ? "chapeau" : r.coiffe;
		settings.fullEffects = r.effetsComplets;
		figures.clear();
		if (d.regles.figures != null) {
			d.regles.figures.stream().filter(FIGURES::containsKey).forEach(figures::add);
		}
		scenario = d;
		List<Integer> n = d.playable();
		announce("Scénario de la prochaine partie : « " + d.nom + " » (" + n.get(0) + " à " + n.get(n.size() - 1)
			+ " joueurs)." + (d.description == null || d.description.isBlank() ? "" : " " + d.description));
		return map;
	}

	String hostMapInPlace() {
		return host.mapInPlace();
	}

	/** Le scénario choisi vient d'être supprimé de l'Atelier : la prochaine partie redevient libre. */
	public void scenarioDeleted(String id) {
		if (scenario != null && scenario.id.equals(id) && game == null) {
			scenario = null;
			announce("Le scénario choisi vient d'être supprimé : script et réglages libres.");
		}
	}

	/** Celui qui a demandé une partie d'essai, tant qu'elle attend la fin du chargement de la carte ; null sinon. */
	private String pendingTrial;

	/**
	 * Partie d'essai d'un scénario : des joueurs fictifs complètent le salon et le Conteur est automatique.
	 * Si le scénario charge sa carte, la partie démarre d'elle-même dès que le chantier est fini.
	 */
	public String trial(String owner, String id, int players) {
		if (game != null) {
			throw new GameException("Une partie est en cours : arrêtez-la d'abord (/vc arreter).");
		}
		if (players < Distribution.MIN_PLAYERS || players > Distribution.MAX_PLAYERS) {
			throw new GameException("Partie d'essai : de " + Distribution.MIN_PLAYERS + " à " + Distribution.MAX_PLAYERS + " joueurs.");
		}
		String map = useScenario(id);
		removeBots();
		if (host.online(owner) && !lobby.contains(owner) && !owner.equals(storyteller)) {
			join(owner);
		}
		int missing = players - lobby.size();
		if (missing > 0) {
			addBots(missing);
		}
		if (host.busy()) {
			pendingTrial = owner;
			awaitTrial();
			return "Carte en cours de chargement : la partie d'essai démarrera d'elle-même à la fin du chantier."
				+ (map.isEmpty() ? "" : " " + map);
		}
		pendingTrial = null;
		start(owner, Mode.AUTO);
		return "Partie d'essai lancée (" + game.size() + " joueurs, Conteur automatique)."
			+ (host.online(owner) ? "" : " Connectez-vous au jeu pour y assister.");
	}

	/** Une partie d'essai attend-elle la fin d'un chantier ? */
	public boolean trialPending() {
		return pendingTrial != null;
	}

	private void awaitTrial() {
		host.schedule(20, () -> {
			String owner = pendingTrial;
			if (owner == null || game != null) {
				pendingTrial = null; // une partie a été lancée entre-temps
				return;
			}
			if (host.busy()) {
				awaitTrial();
				return;
			}
			pendingTrial = null;
			try {
				start(owner, Mode.AUTO);
			} catch (GameException e) {
				tell(owner, "Partie d'essai non lancée : " + e.getMessage());
			}
		});
	}

	boolean pendingDecisionOpen() {
		return pendingDecision != null;
	}

	String waitingAckName() {
		return waitingAck;
	}

	// ------------------------------------------------------------------
	// Vie sociale : apartés du Conteur, appels, annonces, signes, conversations privées (voir Social)
	// ------------------------------------------------------------------
	final Social social = new Social(this);

	public String aside() {
		return social.aside;
	}

	public String setAside(String target) {
		return social.setAside(target);
	}

	public String callStoryteller(String name) {
		return social.callStoryteller(name);
	}

	public String roleSummary(String name) {
		return social.roleSummary(name);
	}

	public String claim(String name, String roleId) {
		return social.claim(name, roleId);
	}

	public Role claimOf(String name) {
		return social.claims.get(name);
	}

	public String sign(String name, String which) {
		return social.sign(name, which);
	}

	public String inviteTalk(String from, String to) {
		return social.inviteTalk(from, to);
	}

	public String answerTalk(String name, boolean yes) {
		return social.answerTalk(name, yes);
	}

	public String endTalk(String name) {
		return social.endTalk(name);
	}

	public String talkingWith(String name) {
		return social.talks.get(name);
	}

	public boolean voteOpen() {
		return voteNom != null;
	}

	/** Main levée (intention) pour le vote en cours. */
	public boolean handRaised(String name) {
		return voteNom != null && Boolean.TRUE.equals(answers.get(name));
	}

	/**
	 * Ce joueur peut encore lever ou baisser la main : pendant la défense (vivant, ou mort
	 * avec son vote de fantôme), puis tant que l'aiguille n'est pas passée devant lui.
	 */
	public boolean canVote(String name) {
		Game g = game;
		if (g == null || voteNom == null || bots.contains(name)) {
			return false;
		}
		if (!defense) {
			return waitingVote.contains(name);
		}
		int s = g.seatOf(name);
		return s >= 0 && (g.player(s).alive() || g.player(s).ghostVote() || g.player(voteNom.nominee()).traveller());
	}

	/** Invite en cours d'un joueur, ou null. */
	public Prompt currentPrompt(String name) {
		return prompts.get(name);
	}

	// ------------------------------------------------------------------
	// Outils
	// ------------------------------------------------------------------
	private boolean online(String name) {
		return isAuto(name) || host.online(name);
	}

	/** Joué par la machine : joueur fictif, ou absent relayé par un joueur fictif. */
	boolean isAuto(String name) {
		return bots.contains(name) || standIns.contains(name);
	}

	public boolean isStandIn(String name) {
		return standIns.contains(name);
	}

	/** Annonces publiques récentes, affichées par le client. */
	final List<String> feed = new ArrayList<>();

	void announce(String text) {
		feed.add(text);
		if (feed.size() > 30) {
			feed.remove(0);
		}
		host.announce(text);
	}

	public List<String> feed() {
		return List.copyOf(feed);
	}

	void tell(String player, String text) {
		tell(player, Msg.of(text));
	}

	void tell(String player, Msg msg) {
		if (!bots.contains(player)) {
			host.tell(player, msg);
		}
	}

	void setPrompt(String name, Prompt prompt) {
		if (prompt == null) {
			if (prompts.remove(name) != null && !bots.contains(name)) {
				host.prompt(name, null);
			}
			return;
		}
		prompts.put(name, prompt);
		if (name.equals(trainee)) {
			switch (prompt) {
				case Prompt.Choose c -> tip("choisir", "Ton pouvoir s'éveille", "Choisis le ou les joueurs demandés, puis "
					+ "valide. Le Conteur (ici automatique) applique ton pouvoir.");
				case Prompt.Info i -> tip("info", "Une information", "Le Conteur te confie une information. Retiens-la : "
					+ "elle peut être fausse si tu es ivre ou empoisonné. Note-la dans ton carnet.");
				case Prompt.Vote v -> tip("vote", "Le vote", "L'aiguille fait le tour du cercle. Lève la main (bouton, "
					+ "ou touche H) avant qu'elle ne passe devant toi pour voter l'exécution.");
				default -> {
				}
			}
		}
		if (isAuto(name)) {
			host.schedule(15, () -> botAct(name, prompt));
		} else {
			host.prompt(name, prompt);
		}
	}

	private void clearPrompts(Class<? extends Prompt> type) {
		for (String name : new ArrayList<>(prompts.keySet())) {
			if (type == null || type.isInstance(prompts.get(name))) {
				setPrompt(name, null);
			}
		}
	}

	private boolean human() {
		return mode == Mode.HUMAIN && storyteller != null && host.online(storyteller);
	}

	Game requireGame() {
		if (game == null) {
			throw new GameException("Aucune partie en cours.");
		}
		return game;
	}

	int requireSeat(String name) {
		int s = requireGame().seatOf(name);
		if (s < 0) {
			throw new GameException("Tu ne joues pas dans cette partie.");
		}
		return s;
	}

	int seatByName(String name) {
		Game g = requireGame();
		for (int i = 0; i < g.size(); i++) {
			if (g.player(i).name.equalsIgnoreCase(name)) {
				return i;
			}
		}
		throw new GameException("Joueur introuvable : " + name);
	}

	private List<String> names() {
		return game.players().stream().map(p -> p.name).toList();
	}

	/** Joueurs réels (hors bots) de la partie. */
	List<String> realNames() {
		return names().stream().filter(n -> !bots.contains(n)).toList();
	}

	// ------------------------------------------------------------------
	// Inscriptions, Conteur, joueurs fictifs
	// ------------------------------------------------------------------
	// ------------------------------------------------------------------
	// Jetons de rappel du Conteur
	// ------------------------------------------------------------------
	public String reminder(String target, String text) {
		Game g = requireGame();
		String name = g.player(seatByName(target)).name;
		String t = text.trim();
		if (t.equalsIgnoreCase("effacer")) {
			reminders.remove(name);
			save();
			return "Rappels de " + name + " effacés.";
		}
		if (t.toLowerCase(java.util.Locale.ROOT).startsWith("retirer ")) {
			String which = t.substring(8).trim();
			List<String> have = reminders.get(name);
			if (have == null || !have.remove(which)) {
				throw new GameException(name + " ne porte pas le rappel « " + which + " ».");
			}
			if (have.isEmpty()) {
				reminders.remove(name);
			}
			save();
			return "Rappel « " + which + " » retiré de " + name + ".";
		}
		if (t.isEmpty() || t.length() > 40) {
			throw new GameException("Un rappel tient en 40 caractères.");
		}
		List<String> list = reminders.computeIfAbsent(name, k -> new ArrayList<>());
		if (list.size() >= 6) {
			throw new GameException("Six rappels au plus par joueur.");
		}
		list.add(t);
		save();
		return "Rappel posé sur " + name + " : « " + t + " ».";
	}

	// ------------------------------------------------------------------
	// Spectateurs
	// ------------------------------------------------------------------
	public boolean isSpectator(String name) {
		return spectators.contains(name);
	}

	/** Devenir spectateur (ou redevenir joueur). */
	public String spectate(String name) {
		if (spectators.remove(name)) {
			host.spectator(name, false);
			announce(name + " ne regarde plus la partie.");
			return "Tu n'es plus spectateur.";
		}
		if (lobby.contains(name)) {
			throw new GameException("Quitte d'abord le salon (tu es inscrit pour jouer).");
		}
		if (game != null && game.seatOf(name) >= 0) {
			throw new GameException("Tu joues dans la partie en cours.");
		}
		if (name.equals(storyteller)) {
			throw new GameException("Le Conteur ne peut pas être spectateur.");
		}
		spectators.add(name);
		host.spectator(name, true);
		announce(name + " regarde la partie en spectateur.");
		return "Tu es spectateur : tu vois tout le monde, mais tu ne joues pas et ton micro ne porte pas jusqu'aux joueurs."
			+ " /vc spectateur pour revenir.";
	}

	// ------------------------------------------------------------------
	// Partie d'entraînement guidée
	// ------------------------------------------------------------------
	public boolean isTrainee(String name) {
		return name.equals(trainee) && game != null;
	}

	/** Une partie d'entraînement : le joueur, six joueurs fictifs et un guide qui explique chaque étape. */
	public String training(String name) {
		if (game != null) {
			throw new GameException("Une partie est déjà en cours.");
		}
		if (name.equals(storyteller)) {
			storyteller = null;
		}
		spectators.remove(name);
		lobby.removeIf(n -> !n.equals(name) && !bots.contains(n));
		if (!lobby.contains(name)) {
			lobby.add(name);
		}
		int missing = 7 - lobby.size();
		if (missing > 0) {
			addBots(missing);
		}
		trainee = name;
		tipsGiven.clear();
		start(name, Mode.AUTO);
		tip("debut", "Bienvenue à Valcombe !", "Ta carte de rôle vient de tomber : lis-la bien. Les autres sont des joueurs "
			+ "fictifs. Ouvre le menu avec J ; le bouton « Nuit » du panneau fait tomber la première nuit.");
		return "";
	}

	/** Un conseil du guide, donné une seule fois par partie d'entraînement. */
	private void tip(String key, String title, String text) {
		if (trainee == null || game == null || !tipsGiven.add(key)) {
			return;
		}
		host.title(List.of(trainee), title, "");
		tell(trainee, new Msg().colored("[Guide] ", "aqua").text(text));
	}

	public String join(String name) {
		if (game != null) {
			throw new GameException("Une partie est en cours.");
		}
		if (name.equals(storyteller)) {
			throw new GameException("Le Conteur ne joue pas. Quitte d'abord ce rôle.");
		}
		if (lobby.contains(name)) {
			throw new GameException("Tu es déjà inscrit.");
		}
		lobby.add(name);
		announce(name + " rejoint la partie (" + lobby.size() + " inscrits).");
		return "Inscription enregistrée.";
	}

	public String leave(String name) {
		if (game != null) {
			throw new GameException("Impossible pendant une partie.");
		}
		if (!lobby.remove(name)) {
			throw new GameException("Tu n'es pas inscrit.");
		}
		announce(name + " quitte la partie (" + lobby.size() + " inscrits).");
		return "Désinscription enregistrée.";
	}

	/** Mélange l'ordre du cercle (les places de la prochaine partie). */
	public String shuffleLobby() {
		if (game != null) {
			throw new GameException("Les places se mélangent avant la partie.");
		}
		if (lobby.size() < 2) {
			throw new GameException("Il faut au moins deux inscrits.");
		}
		List<String> mixed = rng.shuffle(lobby);
		lobby.clear();
		lobby.addAll(mixed);
		announce("Les places sont mélangées : " + String.join(", ", lobby) + ".");
		return "";
	}

	/** Le meneur retire un inscrit du salon. */
	public String kick(String target) {
		if (game != null) {
			throw new GameException("Impossible pendant une partie.");
		}
		String name = lobby.stream().filter(n -> n.equalsIgnoreCase(target)).findFirst()
			.orElseThrow(() -> new GameException(target + " n'est pas inscrit."));
		lobby.remove(name);
		bots.remove(name);
		announce(name + " est retiré du salon (" + lobby.size() + " inscrits).");
		return "";
	}

	/** La partie peut-elle être lancée ? Renvoie { prêt ("oui" ou "non"), explication }. */
	public String[] lobbyStatus() {
		int n = (int) lobby.stream().filter(this::online).count();
		if (n < Distribution.MIN_PLAYERS || n > Distribution.MAX_PLAYERS) {
			return new String[] {"non", "Il faut entre " + Distribution.MIN_PLAYERS + " et " + Distribution.MAX_PLAYERS
				+ " joueurs inscrits et connectés (" + n + " pour l'instant)."};
		}
		String problem = fr.valcombe.rules.Setup.check(script, n);
		if (problem != null) {
			return new String[] {"non", problem};
		}
		Distribution.Counts c = Distribution.counts(n);
		return new String[] {"oui", n + " joueurs : " + c.citadin() + " Citadins, " + c.etranger() + " Étranger(s), "
			+ c.sbire() + " Sbire(s), " + c.demon() + " Démon."};
	}

	public String listPlayers() {
		if (lobby.isEmpty()) {
			return "Personne n'est inscrit.";
		}
		return "Inscrits : " + String.join(", ", lobby)
			+ (storyteller != null ? ", Conteur : " + storyteller : "");
	}

	public String toggleStoryteller(String name) {
		if (game != null) {
			throw new GameException("Impossible pendant une partie.");
		}
		if (name.equals(storyteller)) {
			storyteller = null;
			announce(name + " n'est plus Conteur.");
			return "Tu n'es plus Conteur.";
		}
		if (lobby.contains(name)) {
			throw new GameException("Désinscris-toi avant : le Conteur ne joue pas.");
		}
		storyteller = name;
		announce(name + " est le Conteur.");
		return "Tu es le Conteur.";
	}

	/** Ajoute n joueurs fictifs qui jouent seuls (pour tester). */
	public String addBots(int n) {
		if (game != null) {
			throw new GameException("Impossible pendant une partie.");
		}
		int added = 0;
		for (int i = 1; added < n && lobby.size() < Distribution.MAX_PLAYERS; i++) {
			String name = "Bot" + i;
			if (!lobby.contains(name)) {
				lobby.add(name);
				bots.add(name);
				added++;
			}
		}
		announce(added + " joueur(s) fictif(s) ajouté(s) (" + lobby.size() + " inscrits).");
		return "";
	}

	public String removeBots() {
		if (game != null) {
			throw new GameException("Impossible pendant une partie.");
		}
		lobby.removeAll(bots);
		bots.clear();
		announce("Joueurs fictifs retirés (" + lobby.size() + " inscrits).");
		return "";
	}

	// ------------------------------------------------------------------
	// Lancement et arrêt
	// ------------------------------------------------------------------
	public String start(String starter, Mode wanted) {
		if (game != null) {
			throw new GameException("Une partie est déjà en cours.");
		}
		if (host.busy()) {
			throw new GameException("La carte est en chantier ou en cours de remise en état : patientez quelques secondes.");
		}
		List<String> names = lobby.stream().filter(this::online).toList();
		if (scenario != null && !scenario.playable().contains(names.size())) {
			List<Integer> ok = scenario.playable();
			throw new GameException("Le scénario « " + scenario.nom + " » se joue de " + ok.get(0) + " à " + ok.get(ok.size() - 1)
				+ " joueurs (actuellement " + names.size() + ").");
		}
		if (names.size() < Distribution.MIN_PLAYERS || names.size() > Distribution.MAX_PLAYERS) {
			throw new GameException("Il faut entre " + Distribution.MIN_PLAYERS + " et " + Distribution.MAX_PLAYERS
				+ " joueurs inscrits et connectés (actuellement " + names.size() + ").");
		}
		if (wanted == Mode.HUMAIN && (storyteller == null || !host.online(storyteller))) {
			throw new GameException("Mode humain : il faut d'abord un Conteur.");
		}
		Game g = new Game(names, rng);
		int shift = figures.contains("sentinelle") ? rng.next(3) - 1 : 0;
		// Rôles imposés au salon par le Conteur : le tirage les met en jeu à coup sûr, puis ils vont à leurs joueurs.
		Set<Role> forced = java.util.EnumSet.noneOf(Role.class);
		Map<Integer, Role[]> imposed = new LinkedHashMap<>();
		presets.forEach((n, r) -> {
			int seat = names.indexOf(n);
			if (seat >= 0) {
				forced.add(r[0]);
				imposed.put(seat, r);
			}
		});
		if (scenario != null) {
			// Le scénario règle le tirage (rôles imposés, répartition), l'ordre de nuit et quelques règles.
			forced.addAll(scenario.forced());
			g.setupRandom(script, shift, forced, scenario.counts(names.size()));
			g.setNightOrder(scenario.order(true), scenario.order(false));
			g.setRules(scenario.regles.mortsSansVote, scenario.regles.accusationsMax);
		} else {
			g.setupRandom(script, shift, forced, null);
		}
		if (!imposed.isEmpty()) {
			g.applyPresets(imposed);
			presets.clear();
		}
		g.setHouseRules(settings.absoluteMajority, settings.evilMajorityWins);
		if (wanted == Mode.HUMAIN) {
			g.decide = policies;
		}
		notes.clear();
		reminders.clear();
		standIns.clear();
		floor.clear();
		paused = false;
		social.claims.clear();
		social.talks.clear();
		social.talkInvites.clear();
		mode = wanted;
		game = g;
		desk.reset();
		clearNight();
		clearVote();
		host.gameStarted(); // avant d'installer qui que ce soit : la carte est gardée telle qu'elle est
		host.seatPlayers(names(), storyteller != null && host.online(storyteller) ? storyteller : starter);
		if (wanted == Mode.HUMAIN && storyteller != null) {
			host.storytellerTools(storyteller, true);
		}
		summoned = null;
		unbriefed.clear();
		boolean byHand = wanted == Mode.HUMAIN && storyteller != null && settings.roleRoom;
		if (byHand) {
			g.players().stream().map(p -> p.name).filter(n -> !bots.contains(n) && !n.equals(storyteller)).forEach(unbriefed::add);
		}
		for (Game.Seat p : g.players()) {
			showRole(p.name, p.believed());
		}
		save();
		updateBoards();
		if (!figures.isEmpty()) {
			announce("Figures neutres : " + String.join(" · ", figures.stream()
				.map(f -> FIGURES.get(f)[0] + " (" + FIGURES.get(f)[1] + ")").toList()));
		}
		if (scenario != null && scenario.ouverture != null && !scenario.ouverture.isBlank()) {
			host.title(realNames(), scenario.nom, "");
			announce("« " + scenario.nom + " » : " + scenario.ouverture);
		}
		boolean autoFirst = wanted == Mode.AUTO && settings.autoNight;
		announce("La partie commence à " + names.size() + " joueurs (Conteur "
			+ (wanted == Mode.HUMAIN ? storyteller : "automatique") + "). " + (!unbriefed.isEmpty()
				? "Le Conteur va recevoir chacun à l'écart pour lui remettre son rôle."
				: autoFirst ? "Lisez votre rôle : la première nuit tombe dans 20 secondes."
				: "Le Conteur fait tomber la première nuit."));
		if (autoFirst) {
			nightLater(g, 400);
		}
		if (!unbriefed.isEmpty()) {
			tell(storyteller, new Msg().colored("[Conteur] Remise des rôles : ", "light_purple")
				.button("Convoquer le suivant", "/vc convoquer suivant", "Amène le joueur suivant dans la salle de convocation et lui remet son rôle")
				.text(" Accroupi + clic droit sur un joueur avec le bâton le convoque aussi. Faire tomber la nuit remet d'un coup les rôles restants."));
		}
		return "Partie lancée.";
	}

	public String stop() {
		trainee = null;
		presets.keySet().retainAll(lobby);
		paused = false;
		standIns.clear();
		if (game != null) {
			host.endGame(realNames());
		social.aside = null;
		summoned = null;
		unbriefed.clear();
		social.endAllTalks();
		if (storyteller != null) {
			host.storytellerTools(storyteller, false);
		}
		}
		host.seatBoards(Map.of());
		game = null;
		desk.reset();
		host.persist("");
		clearNight();
		clearVote();
		clearPrompts(null);
		restoreMap(0); // sans attendre : plus rien à regarder sur la place
		announce("La partie est interrompue.");
		return "Partie interrompue.";
	}

	// ------------------------------------------------------------------
	// Fiches
	// ------------------------------------------------------------------
	private void showRole(String name, Role role) {
		if (unbriefed.contains(name)) {
			return; // le Conteur le lui remettra dans la salle de convocation
		}
		tell(name, new Msg()
			.colored("Ton rôle : " + role.displayName, "gold")
			.text(" : " + role.type.displayName + ", camp : " + Team.of(role).displayName + ". ")
			.text(role.text));
	}

	/** Rôle que le joueur croit avoir, ou null s'il ne joue pas. */
	public Role believedRole(String name) {
		if (game == null) {
			return null;
		}
		int s = game.seatOf(name);
		return s < 0 ? null : game.player(s).believed();
	}

	public String role(String name) {
		Game g = requireGame();
		if (unbriefed.contains(name)) {
			throw new GameException("Le Conteur ne t'a pas encore remis ton rôle : il va te convoquer.");
		}
		showRole(name, g.player(requireSeat(name)).believed());
		return "";
	}

	// ------------------------------------------------------------------
	// Salle de convocation : le Conteur reçoit un joueur à l'écart
	// ------------------------------------------------------------------
	public String summoned() {
		return summoned;
	}

	/** Le joueur connaît-il déjà son rôle ? (Non tant que le Conteur ne le lui a pas remis.) */
	public boolean roleKnown(String name) {
		return !unbriefed.contains(name);
	}

	/** Le Conteur convoque un joueur dans la salle à l'écart ; à sa première venue, il y reçoit son rôle. */
	public String summon(String target) {
		Game g = requireGame();
		if (storyteller == null || mode != Mode.HUMAIN) {
			throw new GameException("La convocation demande un Conteur humain.");
		}
		if (g.phase() != Game.Phase.READY && g.phase() != Game.Phase.DAY) {
			throw new GameException("On convoque avant la première nuit, ou en plein jour.");
		}
		if (voteNom != null) {
			throw new GameException("Un vote est en cours.");
		}
		String name = g.player(seatByName(target)).name;
		if (bots.contains(name)) {
			throw new GameException(name + " est un joueur fictif.");
		}
		if (name.equals(storyteller)) {
			throw new GameException("Le Conteur ne se convoque pas lui-même.");
		}
		if (!host.online(name)) {
			throw new GameException(name + " n'est pas connecté.");
		}
		if (name.equals(summoned)) {
			return name + " est déjà avec toi.";
		}
		if (summoned != null) {
			host.dismiss(storyteller, summoned, false);
			host.title(List.of(summoned), "", "Retour au village");
		}
		summoned = name;
		social.aside = null;
		boolean room = host.summon(storyteller, name);
		host.sound(List.of(name), "bell");
		if (unbriefed.remove(name)) {
			Role r = g.player(g.seatOf(name)).believed();
			host.title(List.of(name), r.displayName, "Le Conteur te remet ton rôle");
			showRole(name, r);
			save();
		} else {
			host.title(List.of(name), "Le Conteur te convoque", "Vous êtes seuls : personne ne vous entend");
		}
		return (room ? name + " est avec toi dans la salle de convocation"
			: "Cette carte n'a pas de salle de convocation : " + name + " reste à sa place, mais vous vous entendez seuls à seuls")
			+ ". " + summonLeft();
	}

	private String summonLeft() {
		return unbriefed.isEmpty() ? "Tous les rôles sont remis." : "Encore " + unbriefed.size() + " rôle(s) à remettre.";
	}

	/** Convoque le prochain joueur qui attend son rôle, dans l'ordre du cercle ; s'il n'en reste pas, tout le monde rentre. */
	public String summonNext() {
		Game g = requireGame();
		for (Game.Seat p : g.players()) {
			if (unbriefed.contains(p.name) && host.online(p.name)) {
				return summon(p.name);
			}
		}
		if (summoned != null) {
			endSummon();
		}
		return unbriefed.isEmpty() ? "Tous les joueurs ont reçu leur rôle : tu peux faire tomber la première nuit."
			: "Les joueurs qui attendent encore leur rôle sont déconnectés : ils le recevront à la première nuit.";
	}

	/** Fin de la convocation : le joueur retrouve son trône, le Conteur retourne d'où il venait. */
	public String endSummon() {
		if (summoned == null) {
			return "Personne n'est convoqué.";
		}
		String was = summoned;
		summoned = null;
		host.dismiss(storyteller, was, true);
		host.title(List.of(was), "", "Retour au village");
		return was + " retourne au village. " + summonLeft();
	}

	/** La nuit tombe : plus de convocation, et ceux qui attendaient encore leur rôle le reçoivent. */
	private void closeSummons(Game g) {
		if (summoned != null) {
			host.dismiss(storyteller, summoned, true);
			summoned = null;
		}
		if (!unbriefed.isEmpty()) {
			List<String> late = List.copyOf(unbriefed);
			unbriefed.clear();
			for (String name : late) {
				int seat = g.seatOf(name);
				if (seat >= 0) {
					showRole(name, g.player(seat).believed());
				}
			}
		}
	}

	public String grimoire(String name) {
		requireGame();
		if (!name.equals(storyteller)) {
			throw new GameException("Seul le Conteur consulte le grimoire.");
		}
		sendGrimoire(name);
		return "";
	}

	private void sendGrimoire(String name) {
		tell(name, "Grimoire :");
		for (String line : game.grimoireLines()) {
			tell(name, line);
		}
	}

	public static List<String> roleSheets() {
		List<String> out = new ArrayList<>();
		for (RoleType t : RoleType.values()) {
			out.add("== " + t.displayName + "s ==");
			for (Role r : Role.ofType(t)) {
				out.add(r.displayName + " : " + r.text);
			}
		}
		return out;
	}

	public String sheets(String name) {
		for (String line : roleSheets()) {
			tell(name, line);
		}
		return "";
	}

	public String history(String name) {
		if (lastGame == null) {
			throw new GameException("Aucune partie terminée.");
		}
		for (String line : lastGame.log()) {
			tell(name, line);
		}
		return "";
	}

	public String messageStoryteller(String from, String text) {
		if (storyteller == null || !host.online(storyteller)) {
			throw new GameException("Aucun Conteur humain connecté.");
		}
		tell(storyteller, new Msg().colored("[MP de " + from + "] ", "light_purple").text(text));
		return "Message envoyé au Conteur.";
	}

	private static int number(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new GameException("Nombre attendu : " + value);
		}
	}

	public String setting(String key, String value) {
		boolean yes = value.equalsIgnoreCase("oui");
		switch (key) {
			case "nuit_immobile" -> settings.nightFreeze = yes;
			case "nuit_sombre" -> settings.nightDark = yes;
			case "nuit_maisons" -> settings.nightHouses = yes;
			case "nuit_muette" -> settings.nightMute = yes;
			case "conseil" -> settings.nominationsFirst = yes;
			case "carnet_nuit" -> settings.notebookNightOnly = yes;
			case "nuit_auto" -> settings.autoNight = yes;
			case "manuel" -> {
				settings.manual = yes;
				if (yes) {
					stopCouncilTimer();
				} else if (waitingNext) {
					waitingNext = false;
					nextLater();
				}
				updateBoards();
			}
			case "discussion_minutes" -> settings.discussionMinutes = Math.max(0, Math.min(30, number(value)));
			case "pause_etapes" -> settings.stepDelayTicks = Math.max(10, Math.min(200, number(value) * 20));
			case "coucher" -> settings.duskSeconds = Math.max(3, Math.min(120, number(value)));
			case "noms" -> {
				settings.nameTags = yes;
				refreshNameTags();
			}
			case "portee_chuchoter" -> settings.whisperRange = Math.max(1, Math.min(16, number(value)));
			case "portee_crier" -> settings.shoutRange = Math.max(16, Math.min(256, number(value)));
			case "duree_conseil" -> settings.councilSeconds = Math.max(0, Math.min(1800, number(value)));
			case "majorite_absolue", "victoire_majorite" -> {
				if (key.equals("majorite_absolue")) {
					settings.absoluteMajority = yes;
				} else {
					settings.evilMajorityWins = yes;
				}
				if (game != null) {
					game.setHouseRules(settings.absoluteMajority, settings.evilMajorityWins);
					updateBoards();
				}
			}
			case "temps_defense" -> settings.defenseSeconds = Math.max(5, Math.min(180, number(value)));
			case "temps_horloge" -> settings.clockTicks = Math.max(10, Math.min(100, number(value) * 2));
			case "spectateurs_grimoire" -> settings.spectatorGrimoire = yes;
			case "remise_convocation" -> settings.roleRoom = yes;
			case "remise_en_etat" -> settings.restoreMap = yes;
			case "coiffe" -> {
				String v = value.toLowerCase(java.util.Locale.ROOT).replace("â", "a");
				if (!List.of("chapeau", "crane", "aucun").contains(v)) {
					throw new GameException("Coiffe des morts : chapeau, crane ou aucun.");
				}
				settings.deadHat = v;
				updateBoards();
			}
			case "effets" -> settings.fullEffects = !value.equalsIgnoreCase("reduits") && !value.equalsIgnoreCase("réduits")
				&& !value.equalsIgnoreCase("non");
			default -> throw new GameException(
				"Réglages : nuit_immobile, nuit_sombre, nuit_maisons, nuit_muette, remise_convocation, remise_en_etat, "
					+ "conseil, manuel, noms, carnet_nuit, nuit_auto, majorite_absolue, victoire_majorite (oui|non), discussion_minutes <min>, "
					+ "pause_etapes <s>, coucher <s>, portee_chuchoter, portee_crier <blocs>, "
					+ "duree_conseil <secondes, 0 : à la main>, temps_defense <secondes>, "
					+ "temps_horloge <dixièmes de seconde par trône>, effets (complets|reduits), coiffe (chapeau|crane|aucun).");
		}
		return "Réglage modifié.";
	}

	// ------------------------------------------------------------------
	// Nuit
	// ------------------------------------------------------------------
	private void clearNight() {
		pendingStep = null;
		pendingChooser = null;
		selected.clear();
		waitingAck = null;
		deliverTo = null;
		nightToken++;
		clearPrompts(Prompt.Choose.class);
		clearPrompts(Prompt.Info.class);
		clearPrompts(Prompt.Grimoire.class);
		clearPrompts(Prompt.Confirm.class);
		clearPrompts(Prompt.Decide.class);
		pendingDecision = null;
	}

	/** Les noms au-dessus des têtes : selon le réglage, et toujours cachés la nuit. */
	private void refreshNameTags() {
		Game g = game;
		host.nameTags(g == null || settings.nameTags && g.phase() != Game.Phase.NIGHT);
	}

	public String startNight() {
		Game g = requireGame();
		if (voteNom != null) {
			throw new GameException("Un vote est en cours.");
		}
		g.startNight();
		refreshNameTags();
		floor.clear();
		closeSummons(g);
		social.aside = null;
		social.endAllTalks();
		tip("nuit", "La nuit tombe", "La nuit, chacun dort. Quand vient ton tour, tu te réveilles : un écran s'ouvre "
			+ "pour utiliser ton pouvoir. Les autres ne savent pas qui se réveille.");
		discussionToken++;
		discussionLeft = 0;
		desk.nightFalls();
		host.timer(realNames(), "", 0);
		host.nightEffects(realNames(), true, settings);
		save();
		announce("La nuit " + g.night() + " tombe sur Valcombe. Fermez les yeux…");
		nextLater();
		return "";
	}

	private void nextLater() {
		int token = nightToken;
		if (manual()) {
			waitingNext = true;
			updateBoards();
			return;
		}
		host.schedule(settings.stepDelayTicks, () -> {
			if (game != null && game.phase() == Game.Phase.NIGHT && token == nightToken) {
				if (paused) {
					nextLater();
				} else {
					processNext();
				}
			}
		});
	}

	private void processNext() {
		Game g = game;
		waitingNext = false;
		pendingStep = g.nextStep();
		pendingChooser = null;
		selected.clear();
		waitingAck = null;
		if (pendingStep == null) {
			finishNight();
			return;
		}
		nightToken++;
		Step step = pendingStep;
		if (step.isChoose()) {
			String name = g.player(step.seat).name;
			if (online(name)) {
				pendingChooser = name;
				if (step.role == Role.JONGLEUR) {
					sendGuess(name, step);
				} else {
					sendChoice(name);
				}
			} else {
				resolve(autoTargets(step));
			}
			return;
		}
		resolve(List.of());
	}

	/** Cibles tirées au hasard (joueur absent, étape passée) ; le Jongleur prête aussi des rôles au hasard. */
	private List<Integer> autoTargets(Step step) {
		List<Integer> targets = randomTargets(step);
		if (step.role == Role.JONGLEUR) {
			List<Role> roles = new ArrayList<>(script);
			Map<Integer, Role> g = new HashMap<>();
			for (int t : targets) {
				g.put(t, roles.get(rng.next(roles.size())));
			}
			game.setGuesses(g);
		}
		return targets;
	}

	/** Les rôles du script, pour prêter un rôle (Jongleur, Bavard). */
	private List<String> guessRoles() {
		return script.stream().map(Role::name).toList();
	}

	private void sendGuess(String name, Step step) {
		List<String> players = game.players().stream().map(p -> p.name).toList();
		tell(name, new Msg().colored("[Nuit] " + step.title() + " : prête un rôle à " + step.choose()
			+ " joueurs (menu J, ou /vc deviner joueur=rôle …).", "gold"));
		setPrompt(name, new Prompt.Guess(step.title(), "Prête un rôle à " + step.choose() + " joueurs : tu apprendras combien "
			+ "de tes hypothèses sont justes.", step.choose(), players, guessRoles()));
	}

	/** Joueurs du Bavard qui doivent encore confier leur hypothèse. */
	final Set<String> bavardPending = new LinkedHashSet<>();

	/** À la fin du Conseil, le Bavard vivant doit prêter un rôle à un joueur. */
	private void askBavard(Game g) {
		for (Game.Seat p : g.players()) {
			if (p.role() == Role.BAVARD && p.alive()) {
				bavardPending.add(p.name);
				List<String> others = g.players().stream().map(o -> o.name).filter(n -> !n.equals(p.name)).toList();
				tell(p.name, new Msg().colored("[Bavard] Le Conseil est fini : confie au Conteur le rôle que tu prêtes à un "
					+ "joueur (menu J, ou /vc deviner joueur=rôle).", "gold"));
				setPrompt(p.name, new Prompt.Guess("Le Bavard", "Quel rôle prêtes-tu à un joueur ? Si tu as vu juste, "
					+ "tu mourras la nuit prochaine.", 1, others, guessRoles()));
			}
		}
	}

	/** Réponse à une invite « prêter un rôle » : des paires joueur=rôle. */
	public String guess(String name, List<String> pairs) {
		Game g = requireGame();
		Map<Integer, Role> map = new java.util.LinkedHashMap<>();
		for (String pair : pairs) {
			int eq = pair.indexOf('=');
			if (eq <= 0 || eq == pair.length() - 1) {
				throw new GameException("Format : joueur=rôle (par exemple " + g.player(0).name + "=goule).");
			}
			int seat = seatByName(pair.substring(0, eq));
			Role r = parseRole(pair.substring(eq + 1));
			if (map.put(seat, r) != null) {
				throw new GameException("Un seul rôle par joueur.");
			}
		}
		if (pendingStep != null && pendingStep.role == Role.JONGLEUR && name.equals(pendingChooser)) {
			if (map.size() != pendingStep.choose()) {
				throw new GameException("Prête un rôle à exactement " + pendingStep.choose() + " joueurs.");
			}
			List<Integer> targets = new ArrayList<>(map.keySet());
			g.validateTargets(pendingStep, targets);
			g.setGuesses(map);
			pendingChooser = null;
			setPrompt(name, null);
			resolve(targets);
			return "Hypothèses notées.";
		}
		if (bavardPending.contains(name)) {
			if (map.size() != 1) {
				throw new GameException("Le Bavard prête un rôle à un seul joueur.");
			}
			var e = map.entrySet().iterator().next();
			g.bavardGuess(g.seatOf(name), e.getKey(), e.getValue());
			bavardPending.remove(name);
			setPrompt(name, null);
			if (storyteller != null && !storyteller.equals(name)) {
				tell(storyteller, new Msg().colored("[Conteur] Le Bavard " + name + " prête à " + g.player(e.getKey()).name
					+ " le rôle " + e.getValue().displayName + ".", "light_purple"));
			}
			save();
			return "Hypothèse confiée au Conteur.";
		}
		throw new GameException("Tu n'as pas d'hypothèse à faire en ce moment.");
	}

	private List<Integer> randomTargets(Step step) {
		// Sans doublon ; les vivants d'abord, et soi-même seulement faute de mieux.
		List<Integer> pool = new ArrayList<>();
		List<Integer> rest = new ArrayList<>();
		for (int s = 0; s < game.size(); s++) {
			if (s != step.seat) {
				(game.player(s).alive() ? pool : rest).add(s);
			}
		}
		if (!step.notSelf()) {
			rest.add(step.seat);
		}
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < step.choose(); i++) {
			List<Integer> from = pool.isEmpty() ? rest : pool;
			if (from.isEmpty()) {
				break;
			}
			out.add(from.remove(rng.next(from.size())));
		}
		return out;
	}

	private void sendChoice(String name) {
		Step step = pendingStep;
		List<Prompt.Option> options = new ArrayList<>();
		Msg row = new Msg();
		for (int i = 0; i < game.size(); i++) {
			if (step.notSelf() && i == step.seat) {
				continue;
			}
			Game.Seat p = game.player(i);
			options.add(new Prompt.Option(i, p.name, p.alive(), selected.contains(i)));
			String label = (selected.contains(i) ? "✔ " : "") + p.name + (p.alive() ? "" : " †");
			row.button(label, "/vc choisir " + p.name, "Désigner " + p.name);
		}
		row.button("Valider", "/vc valider", "Valider ton choix");
		tell(name, new Msg().colored("[Nuit] " + step.title() + " : désigne " + step.choose() + " joueur(s)."
			+ (step.optional() ? " Pouvoir à usage unique : valide sans désigner personne pour le garder." : ""), "gold"));
		tell(name, row);
		setPrompt(name, new Prompt.Choose(step.title(), step.choose(), options, step.optional()));
	}

	/** Bascule la sélection d'un joueur pendant un choix de nuit. */
	public String choose(String name, String target) {
		if (desk.asking(name)) {
			return desk.choose(name, target);
		}
		if (pendingStep == null || !name.equals(pendingChooser)) {
			throw new GameException("Ce n'est pas à toi de choisir.");
		}
		int t = seatByName(target);
		if (pendingStep.notSelf() && t == pendingStep.seat) {
			throw new GameException("Tu ne peux pas te désigner toi-même.");
		}
		if (!selected.remove(t)) {
			if (pendingStep.choose() == 1) {
				selected.clear();
			} else if (selected.size() >= pendingStep.choose()) {
				throw new GameException("Tu as déjà désigné " + pendingStep.choose() + " joueurs. Retire-en un d'abord.");
			}
			selected.add(t);
		}
		sendChoice(name);
		return "";
	}

	public String validate(String name) {
		if (desk.asking(name)) {
			return desk.validate(name);
		}
		if (pendingStep == null || !name.equals(pendingChooser)) {
			throw new GameException("Ce n'est pas à toi de choisir.");
		}
		List<Integer> targets = new ArrayList<>(selected);
		game.validateTargets(pendingStep, targets);
		pendingChooser = null;
		setPrompt(name, null);
		resolve(targets);
		return "Choix enregistré.";
	}

	/** Décision en attente du Conteur : ce qu'il faut faire de sa réponse. */
	private java.util.function.IntConsumer pendingDecision;
	private String decisionAsked;
	private int decisionOptions;
	/** Réponse retenue si le Conteur passe l'étape sans trancher. */
	private int decisionDefault;

	private static final Set<Role> MISREG_SENSITIVE = java.util.EnumSet.of(Role.COMMERE, Role.GREFFIER, Role.INQUISITEUR,
		Role.AUBERGISTE, Role.VEILLEUSE, Role.ASTROLOGUE, Role.EMBAUMEUR, Role.FAUCONNIER);

	/** Pose une question au Conteur ; la nuit reprend avec sa réponse. */
	private void askStoryteller(String title, String question, List<String> options, int byDefault,
		java.util.function.IntConsumer then) {
		pendingDecision = then;
		decisionAsked = storyteller;
		decisionOptions = options.size();
		decisionDefault = byDefault;
		tell(storyteller, new Msg().colored("[Conteur] " + question, "light_purple"));
		Msg row = new Msg();
		for (int i = 0; i < options.size(); i++) {
			row.button(options.get(i), "/vc decider " + i, options.get(i));
		}
		tell(storyteller, row);
		setPrompt(storyteller, new Prompt.Decide(title, question, options));
	}

	/** Réponse du Conteur à une décision en direct. */
	public String decide(String name, int option) {
		if (pendingDecision == null || !name.equals(decisionAsked)) {
			throw new GameException("Aucune décision en attente.");
		}
		if (option < 0 || option >= decisionOptions) {
			throw new GameException("Réponse inconnue : choisis l'une des réponses proposées.");
		}
		var then = pendingDecision;
		pendingDecision = null;
		setPrompt(name, null);
		then.accept(option);
		return "Décision prise.";
	}

	/** Le Conteur humain doit-il trancher avant ce réveil ? Si oui, la question est posée et on attend. */
	private boolean needsLiveDecision(Step step, List<Integer> targets) {
		if (!human() || step.kind != Step.Kind.ROLE) {
			return false;
		}
		Game g = game;
		boolean reads = MISREG_SENSITIVE.contains(step.role) || (step.role.custom() && step.role.ability != null && step.role.ability.wakes());
		if (reads && policies.misregister == HumanDecisions.Policy.HASARD) {
			List<String> tricksters = new ArrayList<>();
			for (Game.Seat p : g.players()) {
				int seat = g.seatOf(p.name);
				if (g.canMisregister(seat)) {
					tricksters.add(p.name + " (" + p.role().displayName + ")");
				}
			}
			if (!tricksters.isEmpty()) {
				askStoryteller(step.title(), String.join(" et ", tricksters) + " trompe(nt)-il(s) " + step.role.displayName + " ?",
					List.of("Oui, tromper", "Non, la vérité"), 1, choice -> {
						policies.misregister = choice == 0 ? HumanDecisions.Policy.TOUJOURS : HumanDecisions.Policy.JAMAIS;
						try {
							resolveNow(targets);
						} finally {
							policies.misregister = HumanDecisions.Policy.HASARD;
						}
					});
				return true;
			}
		}
		if (step.role == Role.GOULE && !targets.isEmpty() && policies.mayorTarget == null) {
			int t = targets.get(0);
			Game.Seat target = g.player(t);
			if (target.role() == Role.CHATELAIN && target.alive() && !g.isPoisoned(t) && g.protectedSeat() != t
				&& !g.isPoisoned(step.seat)) {
				List<String> names = new ArrayList<>();
				names.add("Le Châtelain meurt");
				for (Game.Seat p : g.players()) {
					if (p.alive() && p.team() == Team.GOOD && !p.name.equals(target.name)) {
						names.add(p.name);
					}
				}
				names.add("Personne ne meurt");
				askStoryteller(step.title(), "La Goule attaque le Châtelain (" + target.name + ") : qui meurt ?", names, 0, choice -> {
					String pick = names.get(choice);
					policies.mayorTarget = choice == 0 ? "" : choice == names.size() - 1 ? HumanDecisions.NOBODY : pick;
					try {
						resolveNow(targets);
					} finally {
						policies.mayorTarget = null;
					}
				});
				return true;
			}
		}
		return false;
	}

	private void resolve(List<Integer> targets) {
		if (needsLiveDecision(pendingStep, targets)) {
			return; // la nuit attend la réponse du Conteur
		}
		resolveNow(targets);
	}

	private void resolveNow(List<Integer> targets) {
		Game g = game;
		Step step = pendingStep;
		if ((step.kind == Step.Kind.DEMON_INFO || step.kind == Step.Kind.NEW_DEMON) && step.seat < 0) {
			nextLater(); // aucun Démon (répartition choisie à la main) : rien à dire
			return;
		}
		Info info = g.resolve(step, targets);
		if (g.winner() != null) {
			finish();
			return;
		}
		switch (step.kind) {
			case MINION_INFO -> {
				for (int s : step.seats) {
					tell(g.player(s).name, new Msg().colored("[Nuit] Les Sbires se réveillent. ", "red").text(info.text));
				}
				nextLater();
			}
			case DEMON_INFO, NEW_DEMON -> deliver(g.player(step.seat).name, step.title(), info.text, info.truthText);
			case ROLE -> {
				if (info.kind == Info.Kind.GRIMOIRE) {
					String name = g.player(step.seat).name;
					if (online(name)) {
						sendGrimoire(name);
						askAck(name, new Prompt.Grimoire(g.grimoireLines()));
					} else {
						nextLater();
					}
				} else if (info.kind == Info.Kind.STARPASS) {
					tell(g.player(info.newDemon).name, new Msg().colored("[Nuit] ", "red").text(info.text));
					nextLater();
				} else {
					deliver(g.player(step.seat).name, step.title(), info.text, info.truthText);
				}
			}
		}
	}

	/** Remet une information au joueur, après relecture du Conteur humain. */
	private void deliver(String name, String title, String text, String truth) {
		if (text == null) {
			nextLater();
			return;
		}
		if (human()) {
			deliverTo = name;
			deliverTitle = title;
			deliverText = text;
			deliverTruth = truth;
			tell(storyteller, new Msg().colored("[Conteur] " + title + " → " + name + " : ", "light_purple")
				.text(text + (truth != null ? "  (vérité : " + truth + ")" : "")));
			tell(storyteller, new Msg()
				.button("Envoyer", "/vc envoyer", "Envoyer ce texte au joueur")
				.suggest("Modifier", "/vc envoyer " + text, "Corriger le texte avant l'envoi"));
			setPrompt(storyteller, new Prompt.Confirm(title, name, text, truth));
			return;
		}
		send(name, title, text);
	}

	private void send(String name, String title, String text) {
		if (!online(name)) {
			nextLater();
			return;
		}
		tell(name, new Msg().colored("[Nuit] " + title + " : ", "gold").text(text));
		askAck(name, new Prompt.Info(title, text));
	}

	private void askAck(String name, Prompt prompt) {
		waitingAck = name;
		tell(name, new Msg().button("Je me rendors", "/vc dormir", "Terminer ton réveil"));
		setPrompt(name, prompt);
	}

	/** Le Conteur humain envoie l'information (éventuellement corrigée). */
	public String storytellerSend(String name, String text) {
		if (!name.equals(storyteller) || deliverTo == null) {
			throw new GameException("Aucune information en attente d'envoi.");
		}
		String to = deliverTo;
		deliverTo = null;
		setPrompt(storyteller, null);
		send(to, deliverTitle, text == null || text.isBlank() ? deliverText : text);
		return "Information envoyée.";
	}

	public String sleep(String name) {
		if (!name.equals(waitingAck)) {
			throw new GameException("Tu n'es pas réveillé.");
		}
		waitingAck = null;
		setPrompt(name, null);
		nextLater();
		return "Tu te rendors.";
	}

	/** Le Conteur force l'étape suivante (joueur absent ou bloqué). */
	public String skip() {
		Game g = requireGame();
		if (g.phase() != Game.Phase.NIGHT) {
			throw new GameException("Ce n'est pas la nuit.");
		}
		if (pendingDecision != null) {
			decide(decisionAsked, decisionDefault);
		} else if (pendingChooser != null) {
			setPrompt(pendingChooser, null);
			pendingChooser = null;
			resolve(autoTargets(pendingStep));
		} else if (deliverTo != null) {
			storytellerSend(storyteller, null);
		} else if (waitingAck != null) {
			sleep(waitingAck);
		} else if (waitingNext) {
			if (paused) {
				throw new GameException("La partie est en pause.");
			}
			processNext();
			return "Étape suivante.";
		} else {
			nextLater();
		}
		return "Étape passée.";
	}

	private void finishNight() {
		Game g = game;
		for (String b : bavardPending) {
			setPrompt(b, null);
		}
		bavardPending.clear();
		List<Integer> deaths = g.endNight();
		desk.dayBreaks();
		clearNight();
		host.nightEffects(realNames(), false, settings);
		if (deaths.isEmpty()) {
			host.title(realNames(), "Jour " + g.day(), "Personne n'est mort cette nuit");
		} else {
			host.sound(realNames(), "death");
			host.title(realNames(), "Jour " + g.day(), String.join(", ", deaths.stream().map(s -> g.player(s).name).toList())
				+ (deaths.size() > 1 ? " sont morts" : " est mort") + " cette nuit");
		}
		updateBoards();
		if (deaths.isEmpty()) {
			announce("L'aube se lève. Personne n'est mort cette nuit.");
		} else {
			announce("L'aube se lève. Mort(s) cette nuit : "
				+ String.join(", ", deaths.stream().map(s -> g.player(s).name).toList()) + ".");
			deaths.forEach(s -> revealDeath(g, s));
		}
		if (g.winner() != null) {
			finish();
			return;
		}
		boolean talkFirst = settings.discussionMinutes > 0;
		// Avec une discussion minutée, le Conseil (les accusations) ne s'ouvre qu'à la fin de la discussion.
		nominationsOpen = mode == Mode.AUTO && !talkFirst;
		councilVoting = false;
		save();
		updateBoards();
		announce("Jour " + g.day() + " : discutez, chuchotez, puis le Conteur ouvre "
			+ (settings.nominationsFirst ? "le Conseil." : "les nominations."));
		tip("jour", "Le jour se lève", "Le jour, on discute. Chuchoter (bouton du menu) est privé, mais tout le monde "
			+ "voit qui chuchote. Avec la plume d'accusation, vise un joueur et fais un clic droit pour l'accuser.");
		if (talkFirst) {
			discussion(settings.discussionMinutes);
		} else if (nominationsOpen) {
			councilTimer();
		}
		votersOf.clear();
		dawnFigures(g);
		brain.dawn();
	}

	// ------------------------------------------------------------------
	// Jour
	// ------------------------------------------------------------------
	private void clearVote() {
		voteNom = null;
		answers.clear();
		waitingVote.clear();
		clockOrder.clear();
		clockPos = -1;
		lockedHands.clear();
		defense = false;
		voteToken++;
		clearPrompts(Prompt.Vote.class);
		host.voteScene(null);
	}

	public boolean nominationsOpen() {
		return nominationsOpen;
	}

	/** Le Conteur ouvre (ou ferme) les nominations de la journée. */
	public String setNominations(boolean open) {
		Game g = requireGame();
		if (g.phase() != Game.Phase.DAY || g.dayOver()) {
			throw new GameException("Les nominations n'ont lieu que de jour.");
		}
		if (open && councilVoting) {
			throw new GameException("Les votes du Conseil sont en cours.");
		}
		nominationsOpen = open;
		if (open) {
			host.sound(realNames(), "bell");
			if (settings.nominationsFirst) {
				host.title(realNames(), "Le Conseil est ouvert", "Qui accusez-vous ?");
				announce("Le Conseil est ouvert : accusez maintenant. Les votes auront lieu ensuite, un par accusé, "
					+ "dans l'ordre des accusations.");
				councilTimer();
			} else {
				host.title(realNames(), "Les nominations sont ouvertes", "Qui accusez-vous ?");
				announce("Les nominations sont ouvertes.");
			}
		} else {
			stopCouncilTimer();
			if (settings.nominationsFirst && voteNom == null && !g.pendingNominations().isEmpty()) {
				return startVotes();
			}
			announce("Les nominations sont closes.");
		}
		updateBoards();
		return "";
	}

	// ------------------------------------------------------------------
	// Conseil de la table : toutes les accusations, puis un vote par accusé, dans l'ordre
	// ------------------------------------------------------------------
	/** Les votes du Conseil sont en cours (entre deux votes compris). */
	boolean councilVoting;
	private int councilToken;
	/** Secondes de Conseil restantes avant les votes (0 : pas de minuteur). */
	int councilLeft;

	public boolean councilVoting() {
		return councilVoting;
	}

	/** Durée du Conseil : celle du réglage ; sans Conteur humain, trois minutes au plus si rien n'est réglé. */
	private int councilDuration() {
		if (!settings.nominationsFirst || manual()) {
			return 0;
		}
		return settings.councilSeconds > 0 ? settings.councilSeconds : mode == Mode.AUTO ? 180 : 0;
	}

	private void councilTimer() {
		int token = ++councilToken;
		int secs = councilDuration();
		if (secs <= 0) {
			councilLeft = 0;
			return;
		}
		councilLeft = secs;
		host.timer(realNames(), "Conseil", secs);
		tickCouncil(token);
	}

	private void stopCouncilTimer() {
		councilToken++;
		if (councilLeft > 0) {
			councilLeft = 0;
			host.timer(realNames(), "", 0);
		}
	}

	private void tickCouncil(int token) {
		host.schedule(20, () -> {
			Game g = game;
			if (token != councilToken || g == null || g.phase() != Game.Phase.DAY || g.dayOver() || !nominationsOpen) {
				return;
			}
			if (paused || voteNom != null || --councilLeft > 0) {
				tickCouncil(token);
				return;
			}
			councilLeft = 0;
			host.timer(realNames(), "", 0);
			if (!g.pendingNominations().isEmpty()) {
				startVotes();
				return;
			}
			nominationsOpen = false;
			host.sound(realNames(), "bell");
			announce("Fin du Conseil : personne n'a été accusé.");
			updateBoards();
			autoDusk(g);
		});
	}

	/** Clôt les accusations et lance les votes, un par accusé, dans l'ordre des accusations. */
	public String startVotes() {
		Game g = requireGame();
		if (g.phase() != Game.Phase.DAY || g.dayOver()) {
			throw new GameException("Les votes n'ont lieu que de jour.");
		}
		if (voteNom != null || councilVoting) {
			throw new GameException("Les votes sont déjà en cours.");
		}
		List<Game.Nomination> pending = g.pendingNominations();
		if (pending.isEmpty()) {
			throw new GameException("Aucune accusation à mettre aux voix.");
		}
		nominationsOpen = false;
		stopCouncilTimer();
		councilVoting = true;
		List<String> order = new ArrayList<>();
		for (Game.Nomination n : pending) {
			order.add((order.size() + 1) + ". " + g.player(n.nominee()).name + " (accusé par " + g.player(n.nominator()).name + ")");
		}
		host.sound(realNames(), "bell");
		host.title(realNames(), "Place aux votes", pending.size() + (pending.size() > 1 ? " accusés" : " accusé"));
		announce("Fin des accusations. Ordre des votes : " + String.join(", ", order) + ".");
		updateBoards();
		save();
		host.schedule(Math.max(20, settings.stepDelayTicks * 2), this::nextVote);
		return "";
	}

	private void nextVote() {
		Game g = game;
		if (g == null || !councilVoting || voteNom != null) {
			return;
		}
		if (g.phase() != Game.Phase.DAY || g.dayOver()) {
			councilVoting = false;
			return;
		}
		if (paused) {
			host.schedule(20, this::nextVote);
			return;
		}
		List<Game.Nomination> pending = g.pendingNominations();
		if (pending.isEmpty()) {
			councilVoting = false;
			councilDone(g);
			return;
		}
		social.endAllTalks();
		voteNom = pending.get(0);
		answers.clear();
		lockedHands.clear();
		defense = false;
		host.sit(realNames(), false);
		startClock();
		updateBoards();
	}

	/** Après un vote du Conseil : le suivant, après un court temps (en mode manuel, quand le Conteur le lance). */
	private void afterVote() {
		if (!councilVoting) {
			return;
		}
		if (manual()) {
			Game g = game;
			int left = g == null ? 0 : g.pendingNominations().size();
			if (left > 0 && storyteller != null) {
				tell(storyteller, "Vote suivant : à toi de le lancer (« Vote suivant »). Accusations restantes : " + left + ".");
			} else {
				host.schedule(20, this::nextVote); // plus rien à voter : le bilan du Conseil
			}
			updateBoards();
			return;
		}
		host.schedule(Math.max(20, settings.stepDelayTicks * 2), this::nextVote);
	}

	/** Mode manuel effectif : seulement avec un Conteur humain. */
	boolean manual() {
		return settings.manual && mode == Mode.HUMAIN;
	}

	/** Un vote du Conseil attend que le Conteur le lance (mode manuel). */
	public boolean waitingNextVote() {
		return councilVoting && voteNom == null && manual();
	}

	/** Malédiction de la Sorcière à la fin du Conseil ; true si la partie s'est terminée. */
	private boolean curseFalls(Game g) {
		int c = g.applyCurse();
		if (c < 0) {
			return false;
		}
		host.sound(realNames(), "death");
		host.title(realNames(), g.player(c).name + " s'effondre", "Une malédiction pesait sur lui");
		announce(g.player(c).name + " s'effondre : une malédiction pesait sur lui.");
		revealDeath(g, c);
		updateBoards();
		save();
		if (g.winner() != null) {
			finish();
			return true;
		}
		return false;
	}

	private void councilDone(Game g) {
		if (curseFalls(g)) {
			return;
		}
		int block = g.onTheBlock();
		if (block >= 0 && !g.player(block).alive()) {
			block = -1;
		}
		if (block >= 0) {
			announce("Fin des votes : " + g.player(block).name + " sera exécuté (" + g.blockVotes() + " voix).");
			host.title(realNames(), "Fin des votes", g.player(block).name + " sera exécuté");
		} else if (g.blockTie()) {
			announce("Fin des votes : égalité en tête, personne ne sera exécuté.");
			host.title(realNames(), "Fin des votes", "Égalité : personne n'est exécuté");
		} else {
			announce("Fin des votes : personne n'atteint le seuil, personne ne sera exécuté.");
			host.title(realNames(), "Fin des votes", "Personne n'est exécuté");
		}
		updateBoards();
		save();
		if (mode == Mode.HUMAIN && storyteller != null) {
			tell(storyteller, "Les votes sont terminés : clos la journée quand tu veux.");
		}
		autoDusk(g);
	}

	/** Sans Conteur humain, la journée se clôt seule peu après la fin du Conseil. */
	private void autoDusk(Game g) {
		if (mode != Mode.AUTO) {
			return;
		}
		announce("Le soleil se couchera dans " + settings.duskSeconds + " secondes.");
		host.schedule(Math.max(20, settings.duskSeconds * 20), () -> {
			if (game == g && g.phase() == Game.Phase.DAY && !g.dayOver() && voteNom == null && !councilVoting) {
				try {
					endDay();
				} catch (GameException ignored) {
					// la journée a été close entre-temps
				}
			}
		});
	}

	private int discussionToken;

	/** Discussion libre minutée : une barre décompte pour tous, la cloche sonne à la fin. */
	public String discussion(int minutes) {
		Game g = requireGame();
		if (g.phase() != Game.Phase.DAY || g.dayOver()) {
			throw new GameException("La discussion n'a lieu que de jour.");
		}
		int token = ++discussionToken;
		if (minutes <= 0) {
			discussionLeft = 0;
			host.timer(realNames(), "", 0);
			return "Minuteur arrêté.";
		}
		discussionLeft = Math.min(30, minutes) * 60;
		host.timer(realNames(), "Discussion libre", discussionLeft);
		announce("Discussion libre : " + minutes + " minute(s). Allez chuchoter, puis revenez au cercle.");
		tickDiscussion(token);
		return "";
	}

	/** Secondes de discussion libre restantes (0 : pas de minuteur). */
	int discussionLeft;

	private void tickDiscussion(int token) {
		host.schedule(20, () -> {
			if (token != discussionToken || game == null || game.phase() != Game.Phase.DAY) {
				return;
			}
			if (paused || --discussionLeft > 0) {
				tickDiscussion(token);
				return;
			}
			discussionLeft = 0;
			host.timer(realNames(), "", 0);
			host.sound(realNames(), "bell");
			host.title(realNames(), "Fin de la discussion", "Tout le monde au cercle");
			announce("Fin de la discussion : tout le monde au cercle.");
			if (mode == Mode.AUTO) {
				host.gather(realNames());
				host.sit(realNames(), false);
				setNominations(true);
			}
		});
	}

	/** Le Conteur rassemble tout le monde à sa place sur la place du village. */
	public String gather() {
		requireGame();
		social.endAllTalks();
		host.gather(realNames());
		host.sit(realNames(), false);
		host.sound(realNames(), "bell");
		announce("La cloche sonne : tout le monde au cercle !");
		return "";
	}

	public String nominate(String name, String target) {
		Game g = requireGame();
		if (g.phase() != Game.Phase.DAY) {
			throw new GameException("On ne nomine que de jour.");
		}
		if (paused) {
			throw new GameException("La partie est en pause.");
		}
		if (!nominationsOpen) {
			throw new GameException("Les nominations ne sont pas encore ouvertes.");
		}
		if (scenario != null && scenario.regles.pasAccusationPremierJour && g.day() == 1) {
			throw new GameException("Pas d'accusation le premier jour dans ce scénario.");
		}
		if (voteNom != null) {
			throw new GameException("Attends la fin du vote en cours.");
		}
		if (councilVoting) {
			throw new GameException("Les votes ont commencé : plus d'accusation aujourd'hui.");
		}
		int a = requireSeat(name);
		int b = seatByName(target);
		Game.Nomination nom = g.nominate(a, b);
		boolean exileCall = g.player(b).traveller();
		announce(name + (exileCall ? " demande l'exil du voyageur " : " accuse ") + g.player(b).name + ".");
		host.accusation(name, g.player(b).name);
		host.title(realNames(), name + (exileCall ? " veut exiler " : " accuse ") + g.player(b).name, "");
		if (nom.pelerine()) {
			// Le Conseil prend fin : les accusations déjà faites ne seront pas votées.
			stopCouncilTimer();
			nominationsOpen = false;
			announce(name + " est exécuté sur-le-champ !");
			revealDeath(g, a);
			host.sound(realNames(), "death");
			host.execution(name);
			updateBoards();
			if (g.winner() != null) {
				finish();
			} else {
				save();
				announce("La journée s'achève. Le Conteur fait tomber la nuit.");
			}
			return "";
		}
		if (settings.nominationsFirst) {
			// Conseil de la table : l'accusation attend son vote, qui aura lieu à la fin des accusations.
			int waiting = g.pendingNominations().size();
			announce("Accusation n°" + waiting + " notée : le vote aura lieu à la fin du Conseil.");
			updateBoards();
			save();
			return "";
		}
		startDefense(nom);
		updateBoards();
		save();
		return "";
	}

	/** Règle de scénario : le rôle d'un mort est révélé à tous. */
	private void revealDeath(Game g, int seat) {
		if (scenario == null || !scenario.regles.roleReveleALaMort || seat < 0) {
			return;
		}
		Game.Seat p = g.player(seat);
		if (!p.alive() && !p.traveller()) {
			announce(p.name + " était " + p.role().displayName + ".");
		}
	}

	/** Temps de parole de l'accusé, puis le vote à l'horloge. */
	private void startDefense(Game.Nomination nom) {
		social.endAllTalks();
		voteNom = nom;
		answers.clear();
		lockedHands.clear();
		voteToken++;
		defense = true;
		defenseHold = false;
		defenseLeft = settings.defenseSeconds;
		announce(game.player(nom.nominee()).name + " a " + defenseLeft
			+ " secondes pour se défendre. Vous pouvez déjà lever la main (touche H, ou clic droit avec la plume).");
		host.sit(realNames(), false);
		updateVoteScene(0);
		int token = voteToken;
		tickDefense(token);
	}

	private void tickDefense(int token) {
		host.schedule(20, () -> {
			if (voteNom == null || token != voteToken || !defense) {
				return;
			}
			if (paused || defenseHold) {
				tickDefense(token);
				return;
			}
			defenseLeft--;
			if (defenseLeft > 0) {
				tickDefense(token);
			} else if (manual()) {
				defenseLeft = 0;
				if (storyteller != null) {
					tell(storyteller, "La défense est terminée : lance le vote quand tu veux (« Lancer le vote »).");
				}
				updateBoards();
			} else {
				startClock();
			}
		});
	}

	/** Le Conteur écourte la défense et lance le vote. */
	public String startVoteNow() {
		if (voteNom == null && councilVoting) {
			nextVote();
			return "";
		}
		if (voteNom == null && settings.nominationsFirst) {
			return startVotes();
		}
		if (voteNom == null || !defense) {
			throw new GameException("Aucune défense en cours.");
		}
		startClock();
		return "";
	}

	private void startClock() {
		Game g = game;
		defense = false;
		voteToken++;
		String nominee = g.player(voteNom.nominee()).name;
		clockOrder.clear();
		waitingVote.clear();
		boolean exile = g.player(voteNom.nominee()).traveller();
		for (int k = 1; k <= g.size(); k++) {
			int s = (voteNom.nominee() + k) % g.size();
			Game.Seat p = g.player(s);
			if (p.alive() || p.ghostVote() || exile) {
				clockOrder.add(s);
				if (online(p.name)) {
					waitingVote.add(p.name);
					answers.putIfAbsent(p.name, false);
					Msg m = new Msg().colored(exile ? "[Vote] Exiler le voyageur " + nominee + " ? L'aiguille approche."
						: "[Vote] Exécuter " + nominee + " ? L'aiguille approche.", "gold");
					if (!p.alive() && !exile) {
						m.text(" (ton unique vote de fantôme)");
					}
					m.button("Lever la main", "/vc vote oui", "Voter pour l'exécution")
						.button("Baisser la main", "/vc vote non", "Ne pas voter");
					tell(p.name, m);
					setPrompt(p.name, new Prompt.Vote(nominee, !p.alive()));
				}
			}
		}
		clockPos = 0;
		updateVoteScene(20);
		host.sound(realNames(), "clock");
		announce("Vote sur " + nominee + " : l'aiguille fait le tour du cercle. Levez la main avant qu'elle ne passe.");
		int token = voteToken;
		// Un temps de réflexion avant le premier siège, puis l'aiguille avance.
		host.schedule(settings.clockTicks * 2, () -> advanceClock(token));
	}

	private void advanceClock(int token) {
		Game g = game;
		if (g == null || voteNom == null || token != voteToken) {
			return;
		}
		if (paused) {
			host.schedule(10, () -> advanceClock(token));
			return;
		}
		try {
			clockStep(g, token);
		} catch (RuntimeException e) {
			// Une erreur ne doit jamais laisser le village bloqué dans un vote sans fin.
			abortVote("Le vote a été interrompu par une erreur (" + e + ") : il est annulé, l'accusation peut être refaite.");
		}
	}

	private void clockStep(Game g, int token) {
		if (clockPos >= clockOrder.size()) {
			closeVote();
			return;
		}
		int seat = clockOrder.get(clockPos);
		String name = g.player(seat).name;
		if (Boolean.TRUE.equals(answers.get(name))) {
			lockedHands.add(seat);
			announce("✋ " + name + " lève la main (" + lockedHands.size() + ").");
		}
		waitingVote.remove(name);
		setPrompt(name, null);
		clockPos++;
		updateVoteScene(settings.clockTicks);
		host.schedule(settings.clockTicks, () -> advanceClock(token));
	}

	/** Lever ou baisser la main, tant que l'aiguille n'est pas passée. */
	public String vote(String name, boolean yes) {
		if (voteNom == null) {
			throw new GameException("Aucun vote en cours.");
		}
		if (!canVote(name)) {
			throw new GameException(defense ? "Tu n'as pas de voix pour ce vote." : "L'aiguille est déjà passée devant toi.");
		}
		answers.put(name, yes);
		updateVoteScene(0);
		if (defense) {
			return yes ? "Tu lèves la main : elle sera comptée quand l'aiguille passera devant toi." : "Tu baisses la main.";
		}
		return yes ? "Tu lèves la main." : "Tu baisses la main.";
	}

	private void closeVote() {
		Game g = game;
		Game.Nomination nom = voteNom;
		if (g == null || nom == null) {
			return;
		}
		List<Integer> voters = new ArrayList<>(lockedHands);
		clearVote();
		Game.VoteResult r = g.vote(nom.index(), voters);
		votersOf.put(nom.index(), voters.stream().map(v -> g.player(v).name).toList());
		save();
		updateBoards();
		String nominee = g.player(nom.nominee()).name;
		if (g.player(nom.nominee()).traveller()) {
			announce(r.count() + " voix pour l'exil de " + nominee + " (seuil " + r.threshold() + ") : "
				+ (r.exiled() ? "le voyageur est chassé de Valcombe." : "il reste."));
			host.title(realNames(), r.count() + " voix", r.exiled() ? nominee + " est exilé" : nominee + " reste");
			if (r.exiled()) {
				host.sound(realNames(), "death");
				host.execution(nominee);
				updateBoards();
			}
			if (g.winner() != null) {
				finish();
			}
			afterVote();
			return;
		}
		announce(r.count() + " voix contre " + nominee + " (seuil " + r.threshold() + ") : "
			+ (r.onBlock() ? "en passe d'être exécuté." : "pas d'exécution pour l'instant."));
		host.title(realNames(), r.count() + " voix", r.onBlock() ? nominee + " est en passe d'être exécuté" : "Pas assez de voix");
		if (!r.warnings().isEmpty() && storyteller != null) {
			tell(storyteller, "Avertissement : " + String.join(" ", r.warnings()));
		}
		afterVote();
	}

	// ------------------------------------------------------------------
	// Chuchotements : le contenu est privé, mais le fait de chuchoter est public
	// ------------------------------------------------------------------
	public String whisper(String from, String to, String text) {
		Game g = requireGame();
		if (g.phase() != Game.Phase.DAY) {
			throw new GameException("On ne chuchote que de jour.");
		}
		if (scenario != null && scenario.regles.chuchotementsInterdits) {
			throw new GameException("Les chuchotements sont interdits dans ce scénario.");
		}
		int a = requireSeat(from);
		int b = seatByName(to);
		if (a == b) {
			throw new GameException("Chuchoter à soi-même ?");
		}
		String target = g.player(b).name;
		tell(target, new Msg().colored("[" + from + " te chuchote] ", "light_purple").text(text));
		announce(from + " chuchote à " + target + ".");
		host.whisperBubble(from, target);
		return "Chuchoté à " + target + ".";
	}

	// ------------------------------------------------------------------
	// Pouvoirs du Conteur en cours de partie
	// ------------------------------------------------------------------
	public String stKill(String target) {
		Game g = requireGame();
		int s = seatByName(target);
		if (!g.storytellerKill(s)) {
			throw new GameException(target + " est déjà mort.");
		}
		host.sound(realNames(), "death");
		announce(g.player(s).name + " meurt.");
		revealDeath(g, s);
		updateBoards();
		if (g.winner() != null) {
			finish();
		} else {
			save();
		}
		return "";
	}

	public String stRevive(String target) {
		Game g = requireGame();
		int s = seatByName(target);
		g.revive(s);
		announce(g.player(s).name + " revient parmi les vivants.");
		updateBoards();
		save();
		return "";
	}

	public String stPoison(String target) {
		Game g = requireGame();
		g.setPoisoned(seatByName(target));
		save();
		return target + " est empoisonné jusqu'au prochain crépuscule.";
	}

	public String stSwap(String target, String roleId, String believedId) {
		Game g = requireGame();
		int s = seatByName(target);
		Role r = parseRole(roleId);
		g.forceReassign(s, r, believedId == null ? null : parseRole(believedId));
		showRole(g.player(s).name, g.player(s).believed());
		updateBoards();
		save();
		return target + " est maintenant " + r.displayName + ".";
	}

	public String shoot(String name, String target) {
		Game g = requireGame();
		int a = requireSeat(name);
		int b = seatByName(target);
		boolean hit = g.slayerShot(a, b);
		announce(name + " se déclare Arbalétrier et tire sur " + g.player(b).name + "…");
		host.schedule(40, () -> {
			announce(hit ? g.player(b).name + " s'effondre, transpercé !" : "Rien ne se passe.");
			if (hit) {
				revealDeath(g, b);
			}
			if (game == g && g.winner() != null) {
				finish();
			} else if (game == g) {
				updateBoards();
				save();
			}
		});
		return "";
	}

	public String endDay() {
		Game g = requireGame();
		if (voteNom != null) {
			throw new GameException("Un vote est en cours.");
		}
		// Le Conteur peut clore la journée entre deux votes du Conseil : les votes restants n'ont pas lieu.
		councilVoting = false;
		floor.clear();
		stopCouncilTimer();
		nominationsOpen = false;
		if (curseFalls(g)) {
			return "";
		}
		Game.DayEnd end = g.endDay();
		save();
		if (end.executed() >= 0) {
			host.execution(g.player(end.executed()).name);
			host.title(realNames(), g.player(end.executed()).name + " est exécuté", "");
		}
		updateBoards();
		announce(end.executed() >= 0
			? g.player(end.executed()).name + " est exécuté."
			: "Personne n'est exécuté aujourd'hui.");
		revealDeath(g, end.executed());
		if (g.winner() != null) {
			finish();
		} else {
			boolean auto = mode == Mode.AUTO && settings.autoNight;
			announce(auto ? "Le soleil se couche. La nuit tombe dans " + settings.duskSeconds + " secondes."
				: "Le soleil se couche. Le Conteur fait tomber la nuit.");
			askBavard(g);
			if (auto) {
				nightLater(g, Math.max(20, settings.duskSeconds * 20));
			}
		}
		return "";
	}

	/** Sans Conteur humain : la nuit tombe d'elle-même, si personne ne l'a fait tomber entre-temps. */
	private void nightLater(Game g, int ticks) {
		if (!host.realTime()) {
			return;
		}
		host.schedule(ticks, () -> {
			if (game != g || paused || g.winner() != null) {
				return;
			}
			boolean ready = g.phase() == Game.Phase.READY || g.phase() == Game.Phase.SETUP
				|| g.phase() == Game.Phase.DAY && g.dayOver();
			if (ready && voteNom == null) {
				try {
					startNight();
				} catch (GameException ignored) {
					// la nuit est déjà tombée, ou ne peut pas encore tomber
				}
			}
		});
	}

	// ------------------------------------------------------------------
	// Fin de partie et départs
	// ------------------------------------------------------------------
	private void finish() {
		Game g = game;
		if (g == null) {
			return;
		}
		revealBoards(g); // avant endGame, qui oublie la place des sièges
		host.endGame(realNames());
		social.aside = null;
		summoned = null;
		unbriefed.clear();
		social.endAllTalks();
		if (storyteller != null) {
			host.storytellerTools(storyteller, false);
		}
		host.celebrate(realNames());
		host.report(report(g));
		host.title(realNames(), g.winner() == Team.GOOD ? "Victoire du bien" : "Victoire du mal", g.winReason());
		tip("fin", "Partie terminée", "Les jetons se retournent : chacun découvre les vrais rôles. Relis la chronique "
			+ "(menu) pour comprendre ce qui s'est passé. Prêt pour une vraie partie avec tes amis !");
		trainee = null;
		announce("Fin de la partie : victoire du " + (g.winner() == Team.GOOD ? "bien" : "mal") + " ! "
			+ g.winReason());
		announce("Rôles : " + g.describeRoles() + ". Historique complet : menu ou /vc historique.");
		lastGame = g;
		game = null;
		paused = false;
		standIns.clear();
		desk.reset();
		host.persist("");
		clearNight();
		clearVote();
		clearPrompts(null);
		restoreMap(restoreDelay(g.players().size()));
	}

	/** Temps laissé au moins pour regarder les rôles révélés sur la place avant que la carte soit remise en état (30 secondes). */
	static final int RESTORE_DELAY = 600;

	/** Durée de la veillée (chaque joueur révélé à tour de rôle), en ticks. */
	static int vigilTicks(int players) {
		return 110 + 34 * players;
	}

	/** La remise en état attend la fin de la veillée, plus dix secondes : à seize, elle dure plus de trente secondes. */
	static int restoreDelay(int players) {
		return Math.max(RESTORE_DELAY, vigilTicks(players) + 200);
	}

	/** Après une partie : la carte retrouve son état d'avant, sauf si le réglage l'a coupé. */
	private void restoreMap(int delayTicks) {
		if (settings.restoreMap) {
			host.restoreMap(delayTicks);
		} else {
			host.keepMap();
		}
	}

	private GameReport report(Game g) {
		List<GameReport.Player> players = new ArrayList<>();
		for (Game.Seat p : g.players()) {
			players.add(new GameReport.Player(p.name, p.role().displayName,
				p.believed() != p.role() ? p.believed().displayName : null,
				p.team().name(), p.alive(), bots.contains(p.name)));
		}
		return new GameReport(g.winner() == null ? "" : g.winner().name(), g.winReason(), g.day(), scriptName,
			mode == Mode.HUMAIN ? storyteller : null, players, List.copyOf(g.log()));
	}

	/** Délai laissé à un joueur déconnecté pendant son choix de nuit, en partie automatique (30 secondes). */
	static final int ABSENT_TICKS = 600;

	/**
	 * Un joueur se déconnecte : ses invites l'attendent. De nuit, s'il devait choisir, on lui laisse
	 * le temps de revenir (partie automatique) ou le Conteur décide (le relayer, passer l'étape).
	 */
	public void onDisconnect(String name) {
		Game g = game;
		if (g == null) {
			prompts.remove(name);
			return;
		}
		int seat = g.seatOf(name);
		if (name.equals(summoned) || (summoned != null && name.equals(storyteller))) {
			endSummon(); // celui qui reste ne doit pas demeurer enfermé dans la salle
		}
		boolean stOnline = storyteller != null && !name.equals(storyteller) && host.online(storyteller);
		if (seat >= 0 && !bots.contains(name) && stOnline) {
			tell(storyteller, new Msg().colored("[Conteur] " + name + " s'est déconnecté. ", "light_purple")
				.button("Le faire relayer", "/vc relayer " + name, "Un joueur fictif tient sa place jusqu'à son retour"));
		}
		if (g.phase() != Game.Phase.NIGHT) {
			return;
		}
		if (name.equals(waitingAck)) {
			sleep(name);
		} else if (name.equals(pendingChooser) && !stOnline) {
			int token = nightToken;
			host.schedule(ABSENT_TICKS, () -> {
				if (game == g && token == nightToken && name.equals(pendingChooser) && !online(name)) {
					prompts.remove(name);
					pendingChooser = null;
					resolve(autoTargets(pendingStep));
				}
			});
		}
	}

	/** Un joueur (ou le Conteur) revient en cours de partie : il retrouve sa place et ce qu'on attendait de lui. */
	public void onJoin(String name) {
		Game g = game;
		if (g == null) {
			return;
		}
		int seat = g.seatOf(name);
		boolean st = name.equals(storyteller);
		if (seat < 0 && !st) {
			return;
		}
		if (standIns.remove(name)) {
			announce(name + " est de retour et reprend sa place.");
		}
		if (seat >= 0) {
			host.rejoin(name, g.phase() == Game.Phase.NIGHT, settings);
			showRole(name, g.player(seat).believed());
			if (name.equals(pendingChooser)) {
				sendChoice(name);
			} else if (prompts.get(name) != null) {
				host.prompt(name, prompts.get(name));
			}
			updateBoards();
		}
		if (st) {
			if (mode == Mode.HUMAIN) {
				host.storytellerTools(name, true);
			}
			if (prompts.get(name) != null) {
				host.prompt(name, prompts.get(name));
			}
		}
	}

	/** Un joueur fictif tient la place d'un joueur absent, jusqu'à son retour. */
	public String standIn(String target) {
		Game g = requireGame();
		int seat = seatByName(target);
		String name = g.player(seat).name;
		if (bots.contains(name)) {
			throw new GameException(name + " est déjà un joueur fictif.");
		}
		if (host.online(name)) {
			throw new GameException(name + " est connecté.");
		}
		if (!standIns.add(name)) {
			throw new GameException(name + " est déjà relayé.");
		}
		announce(name + " est absent : un joueur fictif tient sa place jusqu'à son retour.");
		if (name.equals(pendingChooser)) {
			sendChoice(name);
		} else if (voteNom != null && !defense && clockOrder.indexOf(seat) >= Math.max(0, clockPos)) {
			waitingVote.add(name);
			setPrompt(name, new Prompt.Vote(g.player(voteNom.nominee()).name, !g.player(seat).alive()));
		}
		return "";
	}

	// ------------------------------------------------------------------
	// Le Conteur reprend la main : pause, vote annulé, grâce, victoire, étape précédente
	// ------------------------------------------------------------------
	public boolean paused() {
		return paused;
	}

	/** Met la partie en pause, ou la reprend : minuteurs, nuit et vote attendent. */
	public String togglePause() {
		requireGame();
		paused = !paused;
		if (paused) {
			host.timer(realNames(), "", 0);
			host.title(realNames(), "Partie en pause", "Le Conteur reprend dans un instant");
			announce("La partie est en pause.");
		} else {
			restoreTimer();
			host.title(realNames(), "La partie reprend", "");
			announce("La partie reprend.");
		}
		return "";
	}

	/** Remet à l'écran le minuteur qui a cours : l'orateur d'abord, sinon la discussion libre, sinon rien. */
	void restoreTimer() {
		if (game == null || paused) {
			return;
		}
		if (desk.speaker != null) {
			host.timer(realNames(), "La parole est à " + desk.speaker, desk.speechLeft);
		} else if (discussionLeft > 0) {
			host.timer(realNames(), "Discussion libre", discussionLeft);
		} else {
			host.timer(realNames(), "", 0);
		}
	}

	// ------------------------------------------------------------------
	// Pupitre du Conteur : actions à la main, temps de parole (voir Desk)
	// ------------------------------------------------------------------
	public String wake(String target, boolean on) {
		return desk.wake(target, on);
	}

	public String ask(String target, int count) {
		return desk.ask(target, count);
	}

	public String show(String target, String text) {
		return desk.show(target, text);
	}

	public String toggleBroadcast() {
		return desk.toggleBroadcast();
	}

	public String speechRequest(String name) {
		return desk.request(name);
	}

	public String speechNext() {
		return desk.next();
	}

	public String speechGive(String target) {
		return desk.give(target);
	}

	public String speechRound(int seconds) {
		return desk.startRound(seconds);
	}

	public String speechSeconds(int seconds) {
		return desk.setSeconds(seconds);
	}

	public String speechStop() {
		return desk.stopSpeech(true);
	}

	/** Le journal de la partie en cours, pour le Conteur (dans le chat). */
	public String journal(String name) {
		Game g = requireGame();
		if (!name.equals(storyteller)) {
			throw new GameException("Seul le Conteur lit le journal pendant la partie.");
		}
		tell(name, "Journal de la partie :");
		for (String line : g.log()) {
			tell(name, line);
		}
		return "";
	}

	/** Ajoute du temps à la journée : discussion libre en cours, ou Sablier. */
	public String extend(int minutes) {
		Game g = requireGame();
		if (g.phase() != Game.Phase.DAY || g.dayOver()) {
			throw new GameException("On ne prolonge que la journée.");
		}
		boolean sand = figures.contains("sablier");
		if (discussionLeft <= 0 && !sand) {
			throw new GameException("Aucun minuteur en cours (discussion libre ou Sablier).");
		}
		if (discussionLeft > 0) {
			discussionLeft = Math.min(30 * 60, discussionLeft + minutes * 60);
			restoreTimer();
		}
		if (sand) {
			sandExtraTicks += minutes * 1200;
		}
		announce("Le Conteur prolonge la journée de " + minutes + " minute(s).");
		return "";
	}

	/**
	 * Le Conteur pilote le minuteur en cours (défense, Conseil, discussion libre, temps de parole) :
	 * « passer » l'achève tout de suite, « prolonger » ajoute des secondes, « arreter » le suspend
	 * (l'étape attend alors le Conteur).
	 */
	public String timerControl(String action, int seconds) {
		Game g = requireGame();
		String a = action.toLowerCase(java.util.Locale.ROOT).replace("ê", "e");
		int add = Math.max(5, Math.min(1800, seconds));
		if (voteNom != null && defense) {
			switch (a) {
				case "passer" -> {
					defenseHold = false;
					startClock();
					return "Place au vote.";
				}
				case "prolonger" -> {
					defenseLeft += add;
					updateBoards();
					return "Défense prolongée de " + add + " s.";
				}
				case "arreter" -> {
					defenseHold = true;
					updateBoards();
					return "Défense sans limite : « Passer » lance le vote.";
				}
				default -> throw new GameException("Minuteur : passer, prolonger ou arreter.");
			}
		}
		if (nominationsOpen && councilLeft > 0 && g.phase() == Game.Phase.DAY) {
			switch (a) {
				case "passer" -> {
					return setNominations(false);
				}
				case "prolonger" -> {
					councilLeft = Math.min(1800, councilLeft + add);
					host.timer(realNames(), "Conseil", councilLeft);
					return "Conseil prolongé de " + add + " s.";
				}
				case "arreter" -> {
					stopCouncilTimer();
					announce("Le Conseil reste ouvert : le Conteur passera aux votes.");
					return "";
				}
				default -> throw new GameException("Minuteur : passer, prolonger ou arreter.");
			}
		}
		if (discussionLeft > 0) {
			switch (a) {
				case "passer" -> {
					discussionLeft = 1; // la discussion s'achève à la seconde suivante, cloche comprise
					return "Fin de la discussion.";
				}
				case "prolonger" -> {
					discussionLeft = Math.min(30 * 60, discussionLeft + add);
					restoreTimer();
					return "Discussion prolongée de " + add + " s.";
				}
				case "arreter" -> {
					return discussion(0);
				}
				default -> throw new GameException("Minuteur : passer, prolonger ou arreter.");
			}
		}
		if (desk.speaker != null) {
			switch (a) {
				case "passer" -> {
					return speechNext();
				}
				case "prolonger" -> {
					desk.speechLeft += add;
					restoreTimer();
					return "Temps de parole prolongé de " + add + " s.";
				}
				case "arreter" -> {
					return speechStop();
				}
				default -> throw new GameException("Minuteur : passer, prolonger ou arreter.");
			}
		}
		throw new GameException("Aucun minuteur en cours.");
	}

	/** Le minuteur en cours, pour le panneau du Conteur : {nom, secondes restantes}, ou null. */
	public Map<String, Object> runningTimer() {
		if (game == null) {
			return null;
		}
		String kind = voteNom != null && defense ? "Défense" : nominationsOpen && councilLeft > 0 ? "Conseil"
			: discussionLeft > 0 ? "Discussion" : desk.speaker != null ? "Parole" : null;
		if (kind == null) {
			return null;
		}
		int left = switch (kind) {
			case "Défense" -> defenseLeft;
			case "Conseil" -> councilLeft;
			case "Discussion" -> discussionLeft;
			default -> desk.speechLeft;
		};
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("kind", kind);
		m.put("left", left);
		m.put("held", kind.equals("Défense") && (defenseHold || manual() && defenseLeft <= 0));
		return m;
	}

	/** Le Conteur avance (« haut ») ou recule (« bas ») un rôle dans l'ordre de réveil. */
	public String moveInOrder(String roleId, String direction) {
		Game g = requireGame();
		Role r = parseRole(roleId);
		String d = direction.toLowerCase(java.util.Locale.ROOT);
		if (!d.equals("haut") && !d.equals("bas")) {
			throw new GameException("Ordre : haut ou bas.");
		}
		g.moveInNightOrder(r, d.equals("haut") ? -1 : 1);
		save();
		updateBoards();
		return "";
	}

	/** Annule le vote en cours : l'accusation est retirée, accusateur et accusé retrouvent leurs droits. */
	public String cancelVote() {
		requireGame();
		if (voteNom == null) {
			throw new GameException("Aucun vote en cours.");
		}
		abortVote("Le Conteur annule le vote : l'accusation est retirée.");
		return "";
	}

	private void abortVote(String message) {
		Game g = game;
		Game.Nomination nom = voteNom;
		clearVote();
		if (g != null && nom != null) {
			try {
				g.cancelNomination(nom.index());
			} catch (GameException ignored) {
				// la nomination a déjà été comptée : rien à retirer
			}
			host.title(realNames(), "Vote annulé", "");
			updateBoards();
			save();
		}
		announce(message);
		afterVote();
	}

	/** Gracie le joueur sur le billot. */
	public String pardon() {
		String name = requireGame().pardon();
		announce(name + " est gracié par le Conteur : personne n'est sur le billot.");
		host.title(realNames(), name + " est gracié", "");
		updateBoards();
		save();
		return "";
	}

	/** Le Conteur déclare la victoire d'un camp (« bien » ou « mal »). */
	public String declareWinner(String camp) {
		Game g = requireGame();
		if (voteNom != null) {
			clearVote();
		}
		g.declareWinner(camp.toLowerCase(java.util.Locale.ROOT).startsWith("m") ? Team.EVIL : Team.GOOD);
		finish();
		return "";
	}

	/** Revient à l'étape précédente de la nuit, pour la rejouer. */
	public String previous() {
		Game g = requireGame();
		g.previousStep();
		clearNight();
		nextLater();
		return "Retour à l'étape précédente. Ses effets déjà appliqués (mort, poison) ne sont pas annulés.";
	}

	// ------------------------------------------------------------------
	// Outils du Conteur humain
	// ------------------------------------------------------------------
	static Role parseRole(String id) {
		Role r = Role.parse(id);
		if (r == null) {
			throw new GameException("Rôle inconnu : " + id);
		}
		return r;
	}

	/** Rôles imposés au salon par le Conteur : joueur → {rôle, rôle cru (Somnambule) ou null}. */
	final Map<String, Role[]> presets = new LinkedHashMap<>();

	/**
	 * Au salon, le Conteur impose le rôle d'un joueur (« aucun » : tiré au hasard). Les autres rôles
	 * sont tirés au lancement, comme d'habitude.
	 */
	public String presetRole(String target, String roleId, String believedId) {
		if (game != null) {
			throw new GameException("La partie est lancée : utilise l'écran « Distribution » (ou /vc attribuer).");
		}
		String name = lobby.stream().filter(n -> n.equalsIgnoreCase(target)).findFirst()
			.orElseThrow(() -> new GameException(target + " n'est pas inscrit à la prochaine partie."));
		if (roleId.equalsIgnoreCase("aucun") || roleId.equalsIgnoreCase("hasard")) {
			presets.remove(name);
			return name + " : rôle tiré au hasard.";
		}
		Role r = parseRole(roleId);
		if (!script.contains(r)) {
			throw new GameException(r.displayName + " n'est pas dans le script de la partie.");
		}
		for (var e : presets.entrySet()) {
			if (e.getValue()[0] == r && !e.getKey().equals(name)) {
				throw new GameException(r.displayName + " est déjà imposé à " + e.getKey() + ".");
			}
		}
		Role believed = believedId == null ? null : parseRole(believedId);
		if (r == Role.SOMNAMBULE && believed != null && believed.type != fr.valcombe.rules.RoleType.CITADIN) {
			throw new GameException("Le Somnambule doit croire être un Citadin.");
		}
		presets.put(name, new Role[] {r, r == Role.SOMNAMBULE ? believed : null});
		return name + " aura le rôle " + r.displayName + (r == Role.SOMNAMBULE && believed != null
			? " (croira être " + believed.displayName + ")" : "") + ". Les autres rôles seront tirés au hasard.";
	}

	/** Change le rôle d'un joueur avant la première nuit. */
	public String assignRole(String target, String roleId, String believedId) {
		Game g = requireGame();
		Role r = parseRole(roleId);
		Role believed = believedId == null ? null : parseRole(believedId);
		g.reassign(seatByName(target), r, believed);
		String warn = g.distributionWarning();
		return g.player(seatByName(target)).name + " : " + r.displayName + "." + (warn != null ? " " + warn : "");
	}

	public String setRedHerring(String target) {
		Game g = requireGame();
		g.setRedHerring(seatByName(target));
		return target + " est le faux présage.";
	}

	public String setBluffs(List<String> ids) {
		if (ids.size() != 3) {
			throw new GameException("Il faut trois rôles.");
		}
		requireGame().setBluffs(ids.stream().map(Session::parseRole).toList());
		return "Bluffs du Démon modifiés.";
	}

	/** Clé de la note de partie (les autres notes sont rangées par joueur). */
	static final String GAME_NOTE = "#partie";

	public String note(String target, String text) {
		requireGame();
		if (target.equalsIgnoreCase("partie")) {
			if (text == null || text.isBlank()) {
				notes.remove(GAME_NOTE);
				save();
				return "Note de partie effacée.";
			}
			notes.put(GAME_NOTE, text);
			save();
			return "Note de partie enregistrée.";
		}
		int s = seatByName(target);
		if (text == null || text.isBlank()) {
			notes.remove(game.player(s).name);
			return "Note effacée.";
		}
		notes.put(game.player(s).name, text);
		return "Note enregistrée.";
	}

	/** Consignes : enregistrement (hasard|toujours|jamais), chatelain (joueur|personne|hasard), successeur (joueur|hasard). */
	public String policy(String key, String value) {
		switch (key) {
			case "enregistrement" -> {
				try {
					policies.misregister = HumanDecisions.Policy.valueOf(value.toUpperCase(java.util.Locale.ROOT));
				} catch (IllegalArgumentException e) {
					throw new GameException("enregistrement : hasard, toujours ou jamais.");
				}
			}
			case "chatelain" -> policies.mayorTarget = value.equalsIgnoreCase("hasard") ? null
				: value.equalsIgnoreCase("personne") ? "" : value;
			case "successeur" -> policies.successor = value.equalsIgnoreCase("hasard") ? null : value;
			default -> throw new GameException("Consignes : enregistrement, chatelain, successeur.");
		}
		return "Consigne enregistrée.";
	}

	// ------------------------------------------------------------------
	// Script (rôles de la prochaine partie)
	// ------------------------------------------------------------------
	public java.util.Set<Role> script() {
		return java.util.EnumSet.copyOf(script);
	}

	public String scriptName() {
		return scriptName;
	}

	public String toggleScriptRole(String id) {
		if (game != null) {
			throw new GameException("Le script se modifie entre deux parties.");
		}
		Role r = parseRole(id);
		if (!script.remove(r)) {
			script.add(r);
		}
		scriptName = "Script personnalisé";
		String problem = fr.valcombe.rules.Setup.check(script, Math.max(5, lobby.size()));
		return r.displayName + (script.contains(r) ? " ajouté au script." : " retiré du script.")
			+ (problem != null ? " Attention : " + problem : "");
	}

	public String useScript(String name, java.util.Collection<Role> roles) {
		if (game != null) {
			throw new GameException("Le script se modifie entre deux parties.");
		}
		if (roles.isEmpty()) {
			throw new GameException("Script vide.");
		}
		script = java.util.EnumSet.copyOf(roles);
		scriptName = name;
		announce("Script de la prochaine partie : " + name + " (" + script.size() + " rôles).");
		return "";
	}

	/** Noms des scripts enregistrés (fournis par le serveur). */
	public void setSavedScripts(List<String> names) {
		savedScripts = List.copyOf(names);
	}

	public void setRoleGroups(Map<String, List<String>> groups) {
		Map<String, List<String>> copy = new LinkedHashMap<>();
		groups.forEach((k, v) -> copy.put(k, List.copyOf(v)));
		roleGroups = java.util.Collections.unmodifiableMap(copy);
	}

	/** Rôles d'un groupe encore existants (un rôle créé puis supprimé est ignoré). */
	java.util.Set<Role> groupRoles(String name) {
		List<String> ids = roleGroups.get(name);
		if (ids == null) {
			throw new GameException("Groupe inconnu : " + name);
		}
		java.util.Set<Role> out = new java.util.LinkedHashSet<>();
		for (String id : ids) {
			Role r = Role.parse(id);
			if (r != null) {
				out.add(r);
			}
		}
		return out;
	}

	/** Applique un groupe au script : ajouter ses rôles, les retirer, ou ne garder que lui. */
	public String scriptGroup(String name, String action) {
		if (game != null) {
			throw new GameException("Le script se modifie entre deux parties.");
		}
		java.util.Set<Role> roles = groupRoles(name);
		if (roles.isEmpty()) {
			throw new GameException("Le groupe « " + name + " » ne contient aucun rôle disponible.");
		}
		String done;
		switch (action) {
			case "ajouter" -> {
				script.addAll(roles);
				done = "Groupe « " + name + " » ajouté au script";
			}
			case "retirer" -> {
				if (script.size() - script.stream().filter(roles::contains).count() == 0) {
					throw new GameException("Le script serait vide.");
				}
				script.removeAll(roles);
				done = "Groupe « " + name + " » retiré du script";
			}
			case "seulement" -> {
				script = java.util.EnumSet.copyOf(roles);
				done = "Script réduit au groupe « " + name + " »";
			}
			default -> throw new GameException("Groupe : ajouter, retirer ou seulement.");
		}
		scriptName = "Script personnalisé";
		String problem = fr.valcombe.rules.Setup.check(script, Math.max(5, lobby.size()));
		return done + " (" + script.size() + " rôles)." + (problem != null ? " Attention : " + problem : "");
	}

	// ------------------------------------------------------------------
	// Jetons flottants au-dessus des sièges
	// ------------------------------------------------------------------
	void updateBoards() {
		Game g = game;
		if (g == null) {
			return;
		}
		refreshNameTags();
		Map<String, String> labels = new LinkedHashMap<>();
		String accused = voteNom != null ? g.player(voteNom.nominee()).name : null;
		String block = g.phase() == Game.Phase.DAY && g.onTheBlock() >= 0 ? g.player(g.onTheBlock()).name : null;
		for (Game.Seat p : g.players()) {
			String text = (p.alive() ? "" : "† ") + p.name + (!p.alive() && p.ghostVote() ? " ✋" : "");
			String honor = bots.contains(p.name) ? "" : host.playerTitle(p.name);
			if (!honor.isEmpty() && p.alive()) {
				text += "\n« " + honor + " »";
			}
			if (p.traveller()) {
				text += "\n✦ voyageur";
			}
			Role claimed = social.claims.get(p.name);
			if (claimed != null) {
				text += "\n« dit : " + claimed.displayName + " »";
			}
			if (p.name.equals(accused)) {
				text += "\n⚖ accusé";
			} else if (p.name.equals(block)) {
				text += "\n☠ sur le billot (" + g.blockVotes() + " voix)";
			}
			String color = p.name.equals(accused) ? "gold" : p.name.equals(block) ? "red" : p.alive() ? "white" : "gray";
			labels.put(p.name, color + "|" + text);
		}
		host.seatBoards(labels);
		Map<String, String> models = new LinkedHashMap<>();
		for (Game.Seat p : g.players()) {
			models.put(p.name, p.alive() ? "jeton_vivant" : p.ghostVote() ? "jeton_fantome" : "jeton_mort");
		}
		host.seatTokens(models, false);
		// Morts : translucides, nom grisé, et coiffés (chapeau ou crâne, avec un cierge tant qu'ils ont leur vote).
		host.deadPlayers(g.players().stream().filter(p -> !p.alive()).map(p -> p.name).toList(),
			g.players().stream().filter(p -> !p.alive() && p.ghostVote()).map(p -> p.name).toList());
	}

	/** Vote en cours, tel que le monde le montre (horloge géante, mains, gibet). */
	private void updateVoteScene(int moveTicks) {
		Game g = game;
		if (g == null || voteNom == null) {
			host.voteScene(null);
			return;
		}
		String nominee = g.player(voteNom.nominee()).name;
		String pointing = null;
		if (!defense) {
			pointing = clockPos >= 0 && clockPos < clockOrder.size() ? g.player(clockOrder.get(clockPos)).name : nominee;
		}
		List<String> locked = lockedHands.stream().map(i -> g.player(i).name).toList();
		List<String> raised = answers.entrySet().stream()
			.filter(e -> Boolean.TRUE.equals(e.getValue()) && (defense || waitingVote.contains(e.getKey())))
			.map(Map.Entry::getKey).toList();
		host.voteScene(new VoteScene(nominee, defense, pointing, moveTicks, raised, locked, g.voteThreshold()));
	}

	/** Fin de partie : chaque jeton révèle le rôle, aux couleurs du camp. */
	private void revealBoards(Game g) {
		Map<String, String> labels = new LinkedHashMap<>();
		for (Game.Seat p : g.players()) {
			String role = p.role().displayName + (p.believed() != p.role() ? " (croyait être " + p.believed().displayName + ")" : "");
			labels.put(p.name, (p.team() == Team.EVIL ? "red" : "aqua") + "|" + p.name + "\n" + role);
		}
		host.seatBoards(labels);
		Map<String, String> models = new LinkedHashMap<>();
		for (Game.Seat p : g.players()) {
			Role r = p.role();
			models.put(p.name, p.traveller() ? "jeton_voyageur_" + (p.team() == Team.EVIL ? "mal" : "bien")
				: "jeton_" + r.modelSuffix());
		}
		host.seatTokens(models, true);
		// Veillée : chaque joueur est révélé à tour de rôle, au rythme des jetons qui se retournent.
		int token = ++vigilToken;
		List<String> watchers = realNames();
		List<Game.Seat> seats = new ArrayList<>(g.players());
		for (int i = 0; i < seats.size(); i++) {
			Game.Seat p = seats.get(i);
			String sub = p.role().displayName + (p.team() == Team.EVIL ? ", camp du mal" : ", camp du bien")
				+ (p.alive() ? "" : " († mort)");
			host.schedule(100 + 34 * i, () -> {
				if (token == vigilToken && game == null) {
					host.title(watchers, p.name, sub);
				}
			});
		}
		host.schedule(vigilTicks(seats.size()), () -> {
			if (token == vigilToken && game == null) {
				announce("Fin de la veillée. La chronique de la partie se relit dans le menu (touche J → Chronique).");
			}
		});
	}

	private int vigilToken;

	// ------------------------------------------------------------------
	// Sauvegarde automatique
	// ------------------------------------------------------------------
	private static final class SaveData {
		String mode;
		String storyteller;
		List<String> lobby;
		List<String> bots;
		Map<String, String> notes;
		Map<String, List<String>> reminders;
		List<String> script;
		String scriptName;
		Settings settings;
		String misregister;
		String mayorTarget;
		String successor;
		boolean nominationsOpen;
		String game;
		List<String> figures;
		String scenario;
		Map<String, String> claims;
		List<String> standIns;
		List<String> unbriefed;
	}

	private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

	/** Enregistre l'état de la table (appelé aux moments clés de la partie). */
	/** Enregistre la partie en cours (arrêt du serveur, sauvegarde périodique). */
	public void saveNow() {
		if (game != null) {
			save();
		}
	}

	private void save() {
		if (game == null) {
			host.persist("");
			return;
		}
		SaveData d = new SaveData();
		d.mode = mode.name();
		d.storyteller = storyteller;
		d.lobby = List.copyOf(lobby);
		d.bots = List.copyOf(bots);
		d.notes = Map.copyOf(notes);
		d.reminders = new LinkedHashMap<>(reminders);
		d.script = script.stream().map(Role::name).toList();
		d.scriptName = scriptName;
		d.settings = settings;
		d.misregister = policies.misregister.name();
		d.mayorTarget = policies.mayorTarget;
		d.successor = policies.successor;
		d.nominationsOpen = nominationsOpen;
		d.figures = List.copyOf(figures);
		d.scenario = scenario == null ? null : scenario.id;
		d.claims = new LinkedHashMap<>();
		social.claims.forEach((k, v) -> d.claims.put(k, v.name()));
		d.standIns = List.copyOf(standIns);
		d.unbriefed = List.copyOf(unbriefed);
		d.game = game.toJson();
		host.persist(GSON.toJson(d));
	}

	/** Reprend une partie enregistrée (après un redémarrage du serveur). */
	public String restore(String json) {
		SaveData d = GSON.fromJson(json, SaveData.class);
		mode = Mode.valueOf(d.mode);
		storyteller = d.storyteller;
		lobby.clear();
		lobby.addAll(d.lobby);
		bots.clear();
		bots.addAll(d.bots);
		notes.clear();
		notes.putAll(d.notes);
		social.claims.clear();
		social.talks.clear();
		social.talkInvites.clear();
		reminders.clear();
		if (d.reminders != null) {
			d.reminders.forEach((k, v) -> reminders.put(k, new ArrayList<>(v)));
		}
		script = java.util.EnumSet.noneOf(Role.class);
		d.script.forEach(r -> script.add(Role.valueOf(r)));
		scriptName = d.scriptName;
		settings.nightFreeze = d.settings.nightFreeze;
		settings.nightDark = d.settings.nightDark;
		settings.nightHouses = d.settings.nightHouses;
		settings.nightMute = d.settings.nightMute;
		settings.defenseSeconds = d.settings.defenseSeconds;
		settings.clockTicks = d.settings.clockTicks;
		settings.fullEffects = d.settings.fullEffects;
		settings.spectatorGrimoire = d.settings.spectatorGrimoire;
		settings.roleRoom = d.settings.roleRoom;
		settings.restoreMap = d.settings.restoreMap;
		summoned = null;
		unbriefed.clear();
		if (d.unbriefed != null) {
			unbriefed.addAll(d.unbriefed);
		}
		if (d.settings.stepDelayTicks > 0) {
			settings.stepDelayTicks = d.settings.stepDelayTicks;
		}
		if (d.settings.deadHat != null) {
			settings.deadHat = d.settings.deadHat;
		}
		figures.clear();
		if (d.figures != null) {
			d.figures.stream().filter(FIGURES::containsKey).forEach(figures::add);
		}
		scenario = d.scenario == null ? null
			: scenarios.stream().filter(x -> x.id.equals(d.scenario)).findFirst().orElse(null);
		if (d.claims != null) {
			d.claims.forEach((k, v) -> {
				Role r = Role.parse(v);
				if (r != null) {
					social.claims.put(k, r);
				}
			});
		}
		standIns.clear();
		paused = false;
		if (d.standIns != null) {
			standIns.addAll(d.standIns);
		}
		policies.misregister = HumanDecisions.Policy.valueOf(d.misregister);
		policies.mayorTarget = d.mayorTarget;
		policies.successor = d.successor;
		nominationsOpen = d.nominationsOpen;
		game = Game.fromJson(d.game, rng);
		if (mode == Mode.HUMAIN) {
			game.decide = policies;
		}
		clearNight();
		clearVote();
		host.seatPlayers(names(), storyteller != null ? storyteller : realNames().isEmpty() ? "" : realNames().get(0));
		String where = switch (game.phase()) {
			case NIGHT -> "la nuit " + game.night();
			case DAY -> "le jour " + game.day();
			default -> "le début de partie";
		};
		announce("Partie reprise là où elle s'était arrêtée : " + where + ".");
		if (mode == Mode.HUMAIN && storyteller != null) {
			host.storytellerTools(storyteller, true);
		}
		updateBoards();
		if (game.phase() == Game.Phase.NIGHT) {
			host.nightEffects(realNames(), true, settings);
			nextLater(); // le réveil en cours au moment de l'arrêt est repris à l'étape suivante
		}
		return "";
	}

	// ------------------------------------------------------------------
	// Vue envoyée au client : voir SessionView
	// ------------------------------------------------------------------
	/**
	 * Ce qu'un joueur a le droit de voir, sous forme d'arbre simple (sérialisé en JSON).
	 * leader : opérateur ou Conteur (accès au panneau).
	 */
	public Map<String, Object> view(String me, boolean leader) {
		return SessionView.build(this, me, leader);
	}

	// ------------------------------------------------------------------
	// Joueurs fictifs
	// ------------------------------------------------------------------
	private void botAct(String bot, Prompt prompt) {
		if (prompts.get(bot) != prompt || !isAuto(bot)) {
			return; // invite déjà périmée, ou joueur revenu entre-temps
		}
		try {
			switch (prompt) {
				case Prompt.Choose c when desk.asking(bot) -> desk.botAnswer(bot);
				case Prompt.Choose c when c.optional() && rng.chance(0.5) -> {
					selected.clear();
					validate(bot);
				}
				case Prompt.Choose c -> {
					int own = game.seatOf(bot);
					List<Prompt.Option> pool = new ArrayList<>(c.options().stream().filter(o -> o.alive() && o.seat() != own).toList());
					List<Prompt.Option> rest = new ArrayList<>(c.options().stream().filter(o -> !pool.contains(o)).toList());
					selected.clear();
					for (int i = 0; i < c.count(); i++) {
						List<Prompt.Option> from = pool.isEmpty() ? rest : pool;
						if (from.isEmpty()) {
							break;
						}
						selected.add(from.remove(rng.next(from.size())).seat());
					}
					validate(bot);
				}
				case Prompt.Info i -> sleep(bot);
				case Prompt.Grimoire gr -> sleep(bot);
				case Prompt.Vote v -> answers.put(bot, brain.votesYes(bot));
				case Prompt.Confirm cf -> storytellerSend(bot, null);
				case Prompt.Decide dc -> decide(bot, 0);
				case Prompt.Guess gs -> {
					List<String> players = new ArrayList<>(gs.players());
					List<String> pairs = new ArrayList<>();
					for (int i = 0; i < gs.count() && !players.isEmpty(); i++) {
						pairs.add(players.remove(rng.next(players.size())) + "=" + gs.roles().get(rng.next(gs.roles().size())));
					}
					guess(bot, pairs);
				}
			}
		} catch (GameException ignored) {
			// État changé entre-temps : rien à faire.
		}
	}
}
