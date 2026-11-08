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

	private final Host host;
	private final Rng rng;
	public final Settings settings = new Settings();
	private final List<String> lobby = new ArrayList<>();
	private final Set<String> bots = new LinkedHashSet<>();
	private final Map<String, Prompt> prompts = new HashMap<>();
	private String storyteller;
	private Mode mode = Mode.AUTO;
	/** Consignes du Conteur humain et notes par joueur. */
	public final HumanDecisions policies = new HumanDecisions();
	private final Map<String, String> notes = new HashMap<>();
	/** Script de la prochaine partie : les rôles qui peuvent être tirés. */
	private java.util.EnumSet<Role> script = java.util.EnumSet.allOf(Role.class);
	private String scriptName = "Valcombe (complet)";
	private List<String> savedScripts = List.of();
	private Game game;
	private Game lastGame;

	// Nuit : étape en attente
	private Step pendingStep;
	private String pendingChooser;
	private final Set<Integer> selected = new LinkedHashSet<>();
	private String waitingAck;
	private String deliverTo;
	private String deliverTitle;
	private String deliverText;
	private int nightToken;

	// Jour : vote en cours
	private Game.Nomination voteNom;
	private final Map<String, Boolean> answers = new LinkedHashMap<>();
	private final Set<String> waitingVote = new LinkedHashSet<>();
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
	private final List<String> feed = new ArrayList<>();

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
		mode = wanted;
		game = g;
		clearNight();
		clearVote();
		host.seatPlayers(realNames(), storyteller != null && host.online(storyteller) ? storyteller : starter);
		for (Game.Seat p : g.players()) {
			showRole(p.name, p.believed());
		}
		announce("La partie commence à " + names.size() + " joueurs (Conteur "
			+ (wanted == Mode.HUMAIN ? storyteller : "automatique") + "). Le Conteur fait tomber la première nuit.");
		return "Partie lancée.";
	}

	public String stop() {
		if (game != null) {
			host.endGame(realNames());
		}
		game = null;
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

	public String setting(String key, String value) {
		boolean yes = value.equalsIgnoreCase("oui");
		switch (key) {
			case "nuit_immobile" -> settings.nightFreeze = yes;
			case "nuit_sombre" -> settings.nightDark = yes;
			case "nuit_maisons" -> settings.nightHouses = yes;
			case "nuit_muette" -> settings.nightMute = yes;
			case "temps_vote" -> {
				try {
					settings.voteSeconds = Math.max(5, Math.min(120, Integer.parseInt(value)));
				} catch (NumberFormatException e) {
					throw new GameException("temps_vote attend un nombre de secondes.");
				}
			}
			default -> throw new GameException(
				"Réglages : nuit_immobile, nuit_sombre, nuit_maisons, nuit_muette (oui|non), temps_vote <secondes>.");
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
	}

	public String startNight() {
		Game g = requireGame();
		if (voteNom != null) {
			throw new GameException("Un vote est en cours.");
		}
		g.startNight();
		host.nightEffects(realNames(), true, settings);
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

	private void resolve(List<Integer> targets) {
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
			announce("L'aube se lève. Personne n'est mort cette nuit.");
		} else {
			announce("L'aube se lève. Mort(s) cette nuit : "
				+ String.join(", ", deaths.stream().map(s -> g.player(s).name).toList()) + ".");
		}
		if (g.winner() != null) {
			finish();
			return;
		}
		announce("Jour " + g.day() + " : discutez, puis nominez (menu ou /vc nominer). Le Conteur clôt la journée.");
	}

	// ------------------------------------------------------------------
	// Jour
	// ------------------------------------------------------------------
	private void clearVote() {
		voteNom = null;
		answers.clear();
		waitingVote.clear();
		voteToken++;
		clearPrompts(Prompt.Vote.class);
	}

	public String nominate(String name, String target) {
		Game g = requireGame();
		if (g.phase() != Game.Phase.DAY) {
			throw new GameException("On ne nomine que de jour.");
		}
		if (voteNom != null) {
			throw new GameException("Attends la fin du vote en cours.");
		}
		int a = requireSeat(name);
		int b = seatByName(target);
		Game.Nomination nom = g.nominate(a, b);
		announce(name + " nomine " + g.player(b).name + ".");
		if (nom.pelerine()) {
			announce(name + " est exécuté sur-le-champ !");
			if (g.winner() != null) {
				finish();
			} else {
				announce("La journée s'achève. Le Conteur fait tomber la nuit.");
			}
			return "";
		}
		openVote(nom);
		return "";
	}

	private void openVote(Game.Nomination nom) {
		voteNom = nom;
		answers.clear();
		waitingVote.clear();
		voteToken++;
		String nominee = game.player(nom.nominee()).name;
		for (Game.Seat p : game.players()) {
			if ((p.alive() || p.ghostVote()) && online(p.name)) {
				waitingVote.add(p.name);
				Msg m = new Msg().colored("[Vote] Exécuter " + nominee + " ?", "gold");
				if (!p.alive()) {
					m.text(" (ton unique vote de fantôme)");
				}
				m.button("Je lève la main", "/vc vote oui", "Voter pour l'exécution")
					.button("Je m'abstiens", "/vc vote non", "Ne pas voter");
				tell(p.name, m);
				setPrompt(p.name, new Prompt.Vote(nominee, !p.alive()));
			}
		}
		announce("Vote sur " + nominee + " : " + settings.voteSeconds + " secondes pour lever la main.");
		int token = voteToken;
		host.schedule(settings.voteSeconds * 20, () -> {
			if (voteNom != null && token == voteToken) {
				closeVote();
			}
		});
		if (waitingVote.isEmpty()) {
			closeVote();
		}
	}

	public String vote(String name, boolean yes) {
		if (voteNom == null || !waitingVote.remove(name)) {
			throw new GameException("Aucun vote ne t'attend.");
		}
		answers.put(name, yes);
		setPrompt(name, null);
		if (waitingVote.isEmpty()) {
			closeVote();
		}
		return yes ? "Tu lèves la main." : "Tu t'abstiens.";
	}

	private void closeVote() {
		Game g = game;
		Game.Nomination nom = voteNom;
		if (g == null || nom == null) {
			return;
		}
		List<Integer> voters = new ArrayList<>();
		List<String> hands = new ArrayList<>();
		for (int i = 0; i < g.size(); i++) {
			String n = g.player(i).name;
			if (Boolean.TRUE.equals(answers.get(n))) {
				voters.add(i);
				hands.add(n);
			}
		}
		clearVote();
		Game.VoteResult r = g.vote(nom.index(), voters);
		announce(r.count() + " voix (seuil " + r.threshold() + ") : "
			+ (r.onBlock() ? "en passe d'être exécuté" : "pas d'exécution pour l'instant")
			+ ". Mains levées : " + (hands.isEmpty() ? "aucune" : String.join(", ", hands)) + ".");
		if (!r.warnings().isEmpty() && storyteller != null) {
			tell(storyteller, "Avertissement : " + String.join(" ", r.warnings()));
		}
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
		host.endGame(realNames());
		announce("Fin de la partie : victoire du " + (g.winner() == Team.GOOD ? "bien" : "mal") + " ! "
			+ g.winReason());
		announce("Rôles : " + g.describeRoles() + ". Historique complet : menu ou /vc historique.");
		lastGame = g;
		game = null;
		clearNight();
		clearVote();
		clearPrompts(null);
	}

	public void onDisconnect(String name) {
		if (voteNom != null && waitingVote.remove(name)) {
			prompts.remove(name);
			if (waitingVote.isEmpty()) {
				closeVote();
			}
		}
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
	// Vue envoyée au client (écrans sur mesure)
	// ------------------------------------------------------------------
	private static Map<String, Object> roleView(Role r) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", r.name());
		m.put("name", r.displayName);
		m.put("type", r.type.name());
		m.put("team", Team.of(r).name());
		m.put("text", r.text);
		return m;
	}

	/**
	 * Ce qu'un joueur a le droit de voir, sous forme d'arbre simple (sérialisé en JSON).
	 * leader : opérateur ou Conteur (accès au panneau). Le grimoire n'est inclus que pour
	 * le Conteur, ou pour le Mouchard pendant son réveil.
	 */
	public Map<String, Object> view(String me, boolean leader) {
		Map<String, Object> v = new LinkedHashMap<>();
		v.put("me", me);
		v.put("leader", leader);
		v.put("storyteller", storyteller);
		v.put("isStoryteller", me.equals(storyteller));
		v.put("mode", mode.name());
		v.put("lobby", List.copyOf(lobby));
		v.put("bots", List.copyOf(bots));
		v.put("inLobby", lobby.contains(me));
		Map<String, Object> sc = new LinkedHashMap<>();
		sc.put("name", scriptName);
		sc.put("roles", script.stream().map(Role::name).toList());
		sc.put("saved", savedScripts);
		sc.put("problem", fr.valcombe.rules.Setup.check(script, Math.max(5, lobby.size())));
		v.put("script", sc);
		v.put("feed", feed.subList(Math.max(0, feed.size() - 8), feed.size()));
		Map<String, Object> st = new LinkedHashMap<>();
		st.put("nightFreeze", settings.nightFreeze);
		st.put("nightDark", settings.nightDark);
		st.put("nightHouses", settings.nightHouses);
		st.put("nightMute", settings.nightMute);
		st.put("voteSeconds", settings.voteSeconds);
		v.put("settings", st);
		Game g = game;
		if (g == null) {
			v.put("phase", "LOBBY");
			v.put("lastWinner", lastGame != null && lastGame.winner() != null ? lastGame.winner().name() : null);
		} else {
			v.put("phase", g.phase().name());
			v.put("night", g.night());
			v.put("day", g.day());
			v.put("dayOver", g.dayOver());
			int mySeat = g.seatOf(me);
			v.put("mySeat", mySeat);
			List<Map<String, Object>> seats = new ArrayList<>();
			for (int i = 0; i < g.size(); i++) {
				Game.Seat p = g.player(i);
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("seat", i);
				s.put("name", p.name);
				s.put("alive", p.alive());
				s.put("ghostVote", p.ghostVote());
				s.put("bot", bots.contains(p.name));
				seats.add(s);
			}
			v.put("seats", seats);
			if (mySeat >= 0) {
				v.put("role", roleView(g.player(mySeat).believed()));
			}
			if (voteNom != null) {
				Map<String, Object> vote = new LinkedHashMap<>();
				vote.put("nominator", g.player(voteNom.nominator()).name);
				vote.put("nominee", g.player(voteNom.nominee()).name);
				vote.put("hands", answers.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList());
				vote.put("seconds", settings.voteSeconds);
				v.put("vote", vote);
			}
			boolean spyAwake = prompts.get(me) instanceof Prompt.Grimoire;
			if (me.equals(storyteller) || spyAwake) {
				List<Map<String, Object>> grim = new ArrayList<>();
				List<String> lines = g.grimoireLines();
				for (int i = 0; i < g.size(); i++) {
					Game.Seat p = g.player(i);
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("seat", i);
					row.put("role", roleView(p.role()));
					row.put("believed", p.believed() != p.role() ? roleView(p.believed()) : null);
					row.put("line", lines.get(i));
					row.put("note", notes.get(p.name));
					row.put("poisoned", g.poisonedSeat() == i);
					row.put("protected", g.protectedSeat() == i);
					row.put("redHerring", g.redHerring() == i);
					row.put("master", g.masterSeat() == i);
					grim.add(row);
				}
				v.put("grimoire", grim);
				v.put("bluffs", g.bluffs().stream().map(Role::name).toList());
				v.put("distWarning", g.distributionWarning());
				Map<String, Object> pol = new LinkedHashMap<>();
				pol.put("misregister", policies.misregister.name());
				pol.put("mayorTarget", policies.mayorTarget);
				pol.put("successor", policies.successor);
				v.put("policies", pol);
			}
		}
		Prompt pr = prompts.get(me);
		if (pr != null) {
			Map<String, Object> m = new LinkedHashMap<>();
			switch (pr) {
				case Prompt.Choose c -> {
					m.put("type", "choose");
					m.put("title", c.title());
					m.put("count", c.count());
					List<Map<String, Object>> opts = new ArrayList<>();
					for (Prompt.Option o : c.options()) {
						opts.add(Map.of("seat", o.seat(), "name", o.name(), "alive", o.alive(), "selected", o.selected()));
					}
					m.put("options", opts);
				}
				case Prompt.Info i -> {
					m.put("type", "info");
					m.put("title", i.title());
					m.put("text", i.text());
				}
				case Prompt.Grimoire gr -> m.put("type", "grimoire");
				case Prompt.Vote vo -> {
					m.put("type", "vote");
					m.put("nominee", vo.nominee());
					m.put("ghost", vo.ghost());
				}
				case Prompt.Confirm cf -> {
					m.put("type", "confirm");
					m.put("title", cf.title());
					m.put("to", cf.to());
					m.put("text", cf.text());
					m.put("truth", cf.truth());
				}
			}
			v.put("prompt", m);
		}
		return v;
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
				case Prompt.Vote v -> vote(bot, rng.chance(0.5));
				case Prompt.Confirm cf -> storytellerSend(bot, null);
			}
		} catch (GameException ignored) {
			// État changé entre-temps : rien à faire.
		}
	}
}
