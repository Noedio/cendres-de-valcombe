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
		if (!settings.nightMute || g == null || g.phase() != Game.Phase.NIGHT) {
			return null;
		}
		List<String> awake = new ArrayList<>();
		for (var e : prompts.entrySet()) {
			if (!bots.contains(e.getKey()) && !(e.getValue() instanceof Prompt.Vote)) {
				awake.add(e.getKey());
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

	public boolean voteOpen() {
		return voteNom != null;
	}

	/** Invite en cours d'un joueur, ou null. */
	public Prompt currentPrompt(String name) {
		return prompts.get(name);
	}

	// ------------------------------------------------------------------
	// Outils
	// ------------------------------------------------------------------
	private boolean online(String name) {
		return bots.contains(name) || host.online(name);
	}

	/** Annonces publiques récentes, affichées par le client. */
	final List<String> feed = new ArrayList<>();

	private void announce(String text) {
		feed.add(text);
		if (feed.size() > 30) {
			feed.remove(0);
		}
		host.announce(text);
	}

	public List<String> feed() {
		return List.copyOf(feed);
	}

	private void tell(String player, String text) {
		tell(player, Msg.of(text));
	}

	private void tell(String player, Msg msg) {
		if (!bots.contains(player)) {
			host.tell(player, msg);
		}
	}

	private void setPrompt(String name, Prompt prompt) {
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
		if (bots.contains(name)) {
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

	private Game requireGame() {
		if (game == null) {
			throw new GameException("Aucune partie en cours.");
		}
		return game;
	}

	private int requireSeat(String name) {
		int s = requireGame().seatOf(name);
		if (s < 0) {
			throw new GameException("Tu ne joues pas dans cette partie.");
		}
		return s;
	}

	private int seatByName(String name) {
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
	private List<String> realNames() {
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
			return "Rappels de " + name + " effacés.";
		}
		if (t.isEmpty() || t.length() > 40) {
			throw new GameException("Un rappel tient en 40 caractères.");
		}
		List<String> list = reminders.computeIfAbsent(name, k -> new ArrayList<>());
		if (list.size() >= 6) {
			throw new GameException("Six rappels au plus par joueur.");
		}
		list.add(t);
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
		List<String> names = lobby.stream().filter(this::online).toList();
		if (names.size() < Distribution.MIN_PLAYERS || names.size() > Distribution.MAX_PLAYERS) {
			throw new GameException("Il faut entre " + Distribution.MIN_PLAYERS + " et " + Distribution.MAX_PLAYERS
				+ " joueurs inscrits et connectés (actuellement " + names.size() + ").");
		}
		if (wanted == Mode.HUMAIN && (storyteller == null || !host.online(storyteller))) {
			throw new GameException("Mode humain : il faut d'abord un Conteur.");
		}
		Game g = new Game(names, rng);
		g.setupRandom(script);
		if (wanted == Mode.HUMAIN) {
			g.decide = policies;
		}
		notes.clear();
		reminders.clear();
		mode = wanted;
		game = g;
		clearNight();
		clearVote();
		host.seatPlayers(names(), storyteller != null && host.online(storyteller) ? storyteller : starter);
		for (Game.Seat p : g.players()) {
			showRole(p.name, p.believed());
		}
		save();
		updateBoards();
		announce("La partie commence à " + names.size() + " joueurs (Conteur "
			+ (wanted == Mode.HUMAIN ? storyteller : "automatique") + "). Le Conteur fait tomber la première nuit.");
		return "Partie lancée.";
	}

	public String stop() {
		trainee = null;
		if (game != null) {
			host.endGame(realNames());
		}
		host.seatBoards(Map.of());
		game = null;
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
			case "effets" -> settings.fullEffects = !value.equalsIgnoreCase("reduits") && !value.equalsIgnoreCase("réduits")
				&& !value.equalsIgnoreCase("non");
			default -> throw new GameException(
				"Réglages : nuit_immobile, nuit_sombre, nuit_maisons, nuit_muette (oui|non), temps_defense <secondes>, "
					+ "temps_horloge <dixièmes de seconde par siège>, effets (complets|reduits).");
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
		tip("nuit", "La nuit tombe", "La nuit, chacun dort. Quand vient ton tour, tu te réveilles : un écran s'ouvre "
			+ "pour utiliser ton pouvoir. Les autres ne savent pas qui se réveille.");
		discussionToken++;
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
				processNext();
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
		List<Integer> pool = new ArrayList<>();
		for (int s = 0; s < game.size(); s++) {
			if (!(step.notSelf() && s == step.seat)) {
				pool.add(s);
			}
		}
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < step.choose(); i++) {
			out.add(rng.pick(pool));
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
		tell(name, new Msg().colored("[Nuit] " + step.title() + " : désigne " + step.choose() + " joueur(s).", "gold"));
		tell(name, row);
		setPrompt(name, new Prompt.Choose(step.title(), step.choose(), options));
	}

	/** Bascule la sélection d'un joueur pendant un choix de nuit. */
	public String choose(String name, String target) {
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

	private static final Set<Role> MISREG_SENSITIVE = java.util.EnumSet.of(Role.COMMERE, Role.GREFFIER, Role.INQUISITEUR,
		Role.AUBERGISTE, Role.VEILLEUSE, Role.ASTROLOGUE, Role.EMBAUMEUR, Role.FAUCONNIER);

	/** Pose une question au Conteur ; la nuit reprend avec sa réponse. */
	private void askStoryteller(String title, String question, List<String> options, java.util.function.IntConsumer then) {
		pendingDecision = then;
		decisionAsked = storyteller;
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
		if (MISREG_SENSITIVE.contains(step.role) && policies.misregister == HumanDecisions.Policy.HASARD) {
			List<String> tricksters = new ArrayList<>();
			for (Game.Seat p : g.players()) {
				int seat = g.seatOf(p.name);
				if ((p.role() == Role.MOUCHARD || p.role() == Role.EXCOMMUNIE) && g.poisonedSeat() != seat) {
					tricksters.add(p.name + " (" + p.role().displayName + ")");
				}
			}
			if (!tricksters.isEmpty()) {
				askStoryteller(step.title(), String.join(" et ", tricksters) + " trompe(nt)-il(s) " + step.role.displayName + " ?",
					List.of("Oui, tromper", "Non, la vérité"), choice -> {
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
			if (target.role() == Role.CHATELAIN && target.alive() && g.poisonedSeat() != t && g.protectedSeat() != t
				&& g.poisonedSeat() != step.seat) {
				List<String> names = new ArrayList<>();
				names.add("Le Châtelain meurt");
				for (Game.Seat p : g.players()) {
					if (p.alive() && p.team() == Team.GOOD && !p.name.equals(target.name)) {
						names.add(p.name);
					}
				}
				names.add("Personne ne meurt");
				askStoryteller(step.title(), "La Goule attaque le Châtelain (" + target.name + ") : qui meurt ?", names, choice -> {
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
		if (pendingChooser != null) {
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
		host.deadPlayers(g.players().stream().filter(p -> !p.alive()).map(p -> p.name).toList(),
			g.players().stream().filter(p -> !p.alive() && p.ghostVote()).map(p -> p.name).toList());
		if (deaths.isEmpty()) {
			announce("L'aube se lève. Personne n'est mort cette nuit.");
		} else {
			announce("L'aube se lève. Mort(s) cette nuit : "
				+ String.join(", ", deaths.stream().map(s -> g.player(s).name).toList()) + ".");
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
			host.timer(realNames(), "", 0);
			return "Minuteur arrêté.";
		}
		int seconds = Math.min(30, minutes) * 60;
		host.timer(realNames(), "Discussion libre", seconds);
		announce("Discussion libre : " + minutes + " minute(s). Allez chuchoter, puis revenez au cercle.");
		host.schedule(seconds * 20, () -> {
			if (token != discussionToken || game == null || game.phase() != Game.Phase.DAY) {
				return;
			}
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
		return "";
	}

	/** Le Conteur rassemble tout le monde à sa place sur la place du village. */
	public String gather() {
		requireGame();
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
		if (!nominationsOpen) {
			throw new GameException("Les nominations ne sont pas encore ouvertes.");
		}
		if (voteNom != null) {
			throw new GameException("Attends la fin du vote en cours.");
		}
		int a = requireSeat(name);
		int b = seatByName(target);
		Game.Nomination nom = g.nominate(a, b);
		announce(name + " accuse " + g.player(b).name + ".");
		host.accusation(name, g.player(b).name);
		host.title(realNames(), name + " accuse " + g.player(b).name, "");
		if (nom.pelerine()) {
			announce(name + " est exécuté sur-le-champ !");
			host.sound(realNames(), "death");
			host.execution(name);
			updateBoards();
			if (g.winner() != null) {
				finish();
			} else {
				announce("La journée s'achève. Le Conteur fait tomber la nuit.");
			}
			return "";
		}
		startDefense(nom);
		updateBoards();
		return "";
	}

	/** Temps de parole de l'accusé, puis le vote à l'horloge. */
	private void startDefense(Game.Nomination nom) {
		voteNom = nom;
		answers.clear();
		lockedHands.clear();
		voteToken++;
		defense = true;
		defenseLeft = settings.defenseSeconds;
		announce(game.player(nom.nominee()).name + " a " + defenseLeft + " secondes pour se défendre.");
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
		for (int k = 1; k <= g.size(); k++) {
			int s = (voteNom.nominee() + k) % g.size();
			Game.Seat p = g.player(s);
			if (p.alive() || p.ghostVote()) {
				clockOrder.add(s);
				if (online(p.name)) {
					waitingVote.add(p.name);
					answers.putIfAbsent(p.name, false);
					Msg m = new Msg().colored("[Vote] Exécuter " + nominee + " ? L'aiguille approche.", "gold");
					if (!p.alive()) {
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
		if (voteNom == null || defense) {
			throw new GameException("Aucun vote en cours.");
		}
		if (!waitingVote.contains(name)) {
			throw new GameException("L'aiguille est déjà passée devant toi.");
		}
		answers.put(name, yes);
		updateVoteScene(0);
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
		save();
		updateBoards();
		String nominee = g.player(nom.nominee()).name;
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
		updateBoards();
		if (g.winner() != null) {
			finish();
		}
		return "";
	}

	public String stRevive(String target) {
		Game g = requireGame();
		int s = seatByName(target);
		g.revive(s);
		announce(g.player(s).name + " revient parmi les vivants.");
		updateBoards();
		return "";
	}

	public String stPoison(String target) {
		Game g = requireGame();
		g.setPoisoned(seatByName(target));
		return target + " est empoisonné jusqu'au prochain crépuscule.";
	}

	public String stSwap(String target, String roleId, String believedId) {
		Game g = requireGame();
		int s = seatByName(target);
		Role r = parseRole(roleId);
		g.forceReassign(s, r, believedId == null ? null : parseRole(believedId));
		showRole(g.player(s).name, g.player(s).believed());
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
			if (game == g && g.winner() != null) {
				finish();
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

	public void onDisconnect(String name) {
		prompts.remove(name);
		if (game != null && game.phase() == Game.Phase.NIGHT) {
			if (name.equals(waitingAck)) {
				sleep(name);
			} else if (name.equals(pendingChooser)) {
				prompts.remove(name);
				pendingChooser = null;
				resolve(randomTargets(pendingStep));
			}
		}
	}

	// ------------------------------------------------------------------
	// Outils du Conteur humain
	// ------------------------------------------------------------------
	private static Role parseRole(String id) {
		try {
			return Role.valueOf(id.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new GameException("Rôle inconnu : " + id);
		}
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

	public String note(String target, String text) {
		requireGame();
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
	private void updateBoards() {
		Game g = game;
		if (g == null) {
			return;
		}
		Map<String, String> labels = new LinkedHashMap<>();
		String accused = voteNom != null ? g.player(voteNom.nominee()).name : null;
		for (Game.Seat p : g.players()) {
			String text = (p.alive() ? "" : "† ") + p.name + (!p.alive() && p.ghostVote() ? " ✋" : "");
			String honor = bots.contains(p.name) ? "" : host.playerTitle(p.name);
			if (!honor.isEmpty() && p.alive()) {
				text += "\n« " + honor + " »";
			}
			if (p.name.equals(accused)) {
				text += "\n⚖ accusé";
			}
			labels.put(p.name, (p.name.equals(accused) ? "gold" : p.alive() ? "white" : "gray") + "|" + text);
		}
		host.seatBoards(labels);
		Map<String, String> models = new LinkedHashMap<>();
		for (Game.Seat p : g.players()) {
			models.put(p.name, p.alive() ? "jeton_vivant" : p.ghostVote() ? "jeton_fantome" : "jeton_mort");
		}
		host.seatTokens(models, false);
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
			.filter(e -> Boolean.TRUE.equals(e.getValue()) && waitingVote.contains(e.getKey()))
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
			models.put(p.name, "jeton_" + p.role().name().toLowerCase(java.util.Locale.ROOT));
		}
		host.seatTokens(models, true);
	}

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
	}

	private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

	/** Enregistre l'état de la table (appelé aux moments clés de la partie). */
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
		if (prompts.get(bot) != prompt) {
			return; // invite déjà périmée
		}
		try {
			switch (prompt) {
				case Prompt.Choose c -> {
					List<Prompt.Option> pool = new ArrayList<>(c.options());
					for (int i = 0; i < c.count() && !pool.isEmpty(); i++) {
						Prompt.Option o = pool.remove(rng.next(pool.size()));
						selected.add(o.seat());
					}
					validate(bot);
				}
				case Prompt.Info i -> sleep(bot);
				case Prompt.Grimoire gr -> sleep(bot);
				case Prompt.Vote v -> answers.put(bot, rng.chance(0.5));
				case Prompt.Confirm cf -> storytellerSend(bot, null);
				case Prompt.Decide dc -> decide(bot, 0);
			}
		} catch (GameException ignored) {
			// État changé entre-temps : rien à faire.
		}
	}
}
