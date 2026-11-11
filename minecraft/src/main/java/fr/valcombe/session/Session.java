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
			announce((on ? "Figure neutre ajoutée : " : "Figure neutre retirée : ") + FIGURES.get(id)[0] + " — " + FIGURES.get(id)[1]);
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
		String map = d.carte == null || d.carte.isBlank() ? "" : host.loadMap(d.carte);
		script = java.util.EnumSet.copyOf(d.script());
		scriptName = d.nom;
		ScenarioDef.Reglages r = d.reglages;
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
			+ (storyteller != null ? " — Conteur : " + storyteller : "");
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
			throw new GameException("La carte est en cours de construction : patientez quelques secondes.");
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
		if (scenario != null) {
			// Le scénario règle le tirage (rôles imposés, répartition), l'ordre de nuit et quelques règles.
			g.setupRandom(script, shift, scenario.forced(), scenario.counts(names.size()));
			g.setNightOrder(scenario.order(true), scenario.order(false));
			g.setRules(scenario.regles.mortsSansVote, scenario.regles.accusationsMax);
		} else {
			g.setupRandom(script, shift);
		}
		if (wanted == Mode.HUMAIN) {
			g.decide = policies;
		}
		notes.clear();
		reminders.clear();
		standIns.clear();
		paused = false;
		social.claims.clear();
		social.talks.clear();
		social.talkInvites.clear();
		mode = wanted;
		game = g;
		desk.reset();
		clearNight();
		clearVote();
		host.seatPlayers(names(), storyteller != null && host.online(storyteller) ? storyteller : starter);
		if (wanted == Mode.HUMAIN && storyteller != null) {
			host.storytellerTools(storyteller, true);
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
			announce("« " + scenario.nom + " » — " + scenario.ouverture);
		}
		announce("La partie commence à " + names.size() + " joueurs (Conteur "
			+ (wanted == Mode.HUMAIN ? storyteller : "automatique") + "). Le Conteur fait tomber la première nuit.");
		return "Partie lancée.";
	}

	public String stop() {
		trainee = null;
		paused = false;
		standIns.clear();
		if (game != null) {
			host.endGame(realNames());
		social.aside = null;
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
		announce("La partie est interrompue.");
		return "Partie interrompue.";
	}

	// ------------------------------------------------------------------
	// Fiches
	// ------------------------------------------------------------------
	private void showRole(String name, Role role) {
		tell(name, new Msg()
			.colored("Ton rôle : " + role.displayName, "gold")
			.text(" — " + role.type.displayName + ", camp : " + Team.of(role).displayName + ". ")
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
		showRole(name, g.player(requireSeat(name)).believed());
		return "";
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
		tell(name, "— Grimoire —");
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
			case "temps_defense" -> settings.defenseSeconds = Math.max(5, Math.min(180, number(value)));
			case "temps_horloge" -> settings.clockTicks = Math.max(10, Math.min(100, number(value) * 2));
			case "spectateurs_grimoire" -> settings.spectatorGrimoire = yes;
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
				"Réglages : nuit_immobile, nuit_sombre, nuit_maisons, nuit_muette (oui|non), temps_defense <secondes>, "
					+ "temps_horloge <dixièmes de seconde par siège>, effets (complets|reduits), coiffe (chapeau|crane|aucun).");
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

	public String startNight() {
		Game g = requireGame();
		if (voteNom != null) {
			throw new GameException("Un vote est en cours.");
		}
		g.startNight();
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
				sendChoice(name);
			} else {
				resolve(randomTargets(step));
			}
			return;
		}
		resolve(List.of());
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
			resolve(randomTargets(pendingStep));
		} else if (deliverTo != null) {
			storytellerSend(storyteller, null);
		} else if (waitingAck != null) {
			sleep(waitingAck);
		} else {
			nextLater();
		}
		return "Étape passée.";
	}

	private void finishNight() {
		Game g = game;
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
		nominationsOpen = mode == Mode.AUTO;
		save();
		updateBoards();
		announce("Jour " + g.day() + " : discutez, chuchotez, puis le Conteur ouvre les nominations.");
		tip("jour", "Le jour se lève", "Le jour, on discute. Chuchoter (bouton du menu) est privé, mais tout le monde "
			+ "voit qui chuchote. Avec la plume d'accusation, vise un joueur et fais un clic droit pour l'accuser.");
		if (scenario != null && scenario.regles.discussionMinutes > 0) {
			discussion(scenario.regles.discussionMinutes);
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
		nominationsOpen = open;
		if (open) {
			host.sound(realNames(), "bell");
			host.title(realNames(), "Les nominations sont ouvertes", "Qui accusez-vous ?");
			announce("Les nominations sont ouvertes.");
		} else {
			announce("Les nominations sont closes.");
		}
		return "";
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
		int a = requireSeat(name);
		int b = seatByName(target);
		Game.Nomination nom = g.nominate(a, b);
		boolean exileCall = g.player(b).traveller();
		announce(name + (exileCall ? " demande l'exil du voyageur " : " accuse ") + g.player(b).name + ".");
		host.accusation(name, g.player(b).name);
		host.title(realNames(), name + (exileCall ? " veut exiler " : " accuse ") + g.player(b).name, "");
		if (nom.pelerine()) {
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
			if (paused) {
				tickDefense(token);
				return;
			}
			defenseLeft--;
			if (defenseLeft <= 0) {
				startClock();
			} else {
				tickDefense(token);
			}
		});
	}

	/** Le Conteur écourte la défense et lance le vote. */
	public String startVoteNow() {
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
			return;
		}
		announce(r.count() + " voix contre " + nominee + " (seuil " + r.threshold() + ") : "
			+ (r.onBlock() ? "en passe d'être exécuté." : "pas d'exécution pour l'instant."));
		host.title(realNames(), r.count() + " voix", r.onBlock() ? nominee + " est en passe d'être exécuté" : "Pas assez de voix");
		if (!r.warnings().isEmpty() && storyteller != null) {
			tell(storyteller, "Avertissement : " + String.join(" ", r.warnings()));
		}
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
			announce("Le soleil se couche. Le Conteur fait tomber la nuit.");
		}
		return "";
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
					resolve(randomTargets(pendingStep));
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
		tell(name, "— Journal de la partie —");
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

	// ------------------------------------------------------------------
	// Jetons flottants au-dessus des sièges
	// ------------------------------------------------------------------
	void updateBoards() {
		Game g = game;
		if (g == null) {
			return;
		}
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
		host.voteScene(new VoteScene(nominee, defense, pointing, moveTicks, raised, locked, (g.aliveCount() + 1) / 2));
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
				: r.custom() ? "jeton_" + r.icon + "_" + (r.evil() ? "mal" : "bien")
				: "jeton_" + r.name().toLowerCase(java.util.Locale.ROOT));
		}
		host.seatTokens(models, true);
		// Veillée : chaque joueur est révélé à tour de rôle, au rythme des jetons qui se retournent.
		int token = ++vigilToken;
		List<String> watchers = realNames();
		List<Game.Seat> seats = new ArrayList<>(g.players());
		for (int i = 0; i < seats.size(); i++) {
			Game.Seat p = seats.get(i);
			String sub = p.role().displayName + (p.team() == Team.EVIL ? " — camp du mal" : " — camp du bien")
				+ (p.alive() ? "" : " († mort)");
			host.schedule(100 + 34 * i, () -> {
				if (token == vigilToken && game == null) {
					host.title(watchers, p.name, sub);
				}
			});
		}
		host.schedule(110 + 34 * seats.size(), () -> {
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
			}
		} catch (GameException ignored) {
			// État changé entre-temps : rien à faire.
		}
	}
}
