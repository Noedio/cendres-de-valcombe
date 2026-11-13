package fr.valcombe.session;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleDef;
import fr.valcombe.rules.Step;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Le pupitre du Conteur : ce qu'il fait à la main, en plus du déroulé que mène le moteur.
 * Réveiller ou rendormir un joueur, lui demander un choix, lui montrer une information,
 * jetons de rappel propres à chaque rôle, temps de parole, voix portée à tous.
 */
final class Desk {
	private final Session s;

	Desk(Session s) {
		this.s = s;
	}

	/** Tout remettre à zéro (début et fin de partie). */
	void reset() {
		awake.clear();
		asks.clear();
		broadcast = false;
		stopSpeech(false);
	}

	/** Tombée de la nuit : plus de parole ni de voix portée. */
	void nightFalls() {
		broadcast = false;
		stopSpeech(false);
	}

	/** Lever du jour : tout le monde est réveillé, les demandes de nuit sont closes. */
	void dayBreaks() {
		awake.clear();
		for (String name : new ArrayList<>(asks.keySet())) {
			closeAsk(name);
		}
	}

	// ------------------------------------------------------------------
	// Jetons de rappel propres à chaque rôle
	// ------------------------------------------------------------------
	private static final Map<Role, List<String>> TOKENS = new LinkedHashMap<>();
	/** Jetons utiles quel que soit le script. */
	static final List<String> COMMON_TOKENS = List.of("ivre", "empoisonné", "protégé", "mort cette nuit", "a menti");

	static {
		TOKENS.put(Role.COMMERE, List.of("Citadin montré", "leurre"));
		TOKENS.put(Role.GREFFIER, List.of("Étranger montré", "leurre"));
		TOKENS.put(Role.INQUISITEUR, List.of("Sbire montré", "leurre"));
		TOKENS.put(Role.ASTROLOGUE, List.of("faux présage"));
		TOKENS.put(Role.EMBAUMEUR, List.of("exécuté aujourd'hui"));
		TOKENS.put(Role.CHAPELAIN, List.of("protégé"));
		TOKENS.put(Role.FAUCONNIER, List.of("vu par le Fauconnier"));
		TOKENS.put(Role.PELERINE, List.of("pouvoir utilisé"));
		TOKENS.put(Role.ARBALETRIER, List.of("a tiré"));
		TOKENS.put(Role.CHATELAIN, List.of("mort à la place du Châtelain"));
		TOKENS.put(Role.ECUYER, List.of("maître"));
		TOKENS.put(Role.SOMNAMBULE, List.of("ivre"));
		TOKENS.put(Role.ALCHIMISTE, List.of("empoisonné"));
		TOKENS.put(Role.HERITIERE, List.of("est le Démon"));
		TOKENS.put(Role.FAUSSAIRE, List.of("trompé cette nuit"));
		TOKENS.put(Role.GOULE, List.of("mort cette nuit"));
	}

	/** Jetons de rappel d'un rôle : ceux du jeu pour un rôle intégré, ceux de sa fiche pour un rôle créé. */
	static List<String> tokensOf(Role r) {
		if (r.custom()) {
			for (RoleDef d : Role.customDefs()) {
				if (d.id.equals(r.customId)) {
					return d.rappels == null ? List.of() : List.copyOf(d.rappels);
				}
			}
			return List.of();
		}
		return TOKENS.getOrDefault(r, List.of());
	}

	/** Jetons proposés au Conteur pour la partie en cours : par rôle en jeu, puis les jetons communs. */
	List<Map<String, Object>> tokenTray(Game g) {
		List<Map<String, Object>> out = new ArrayList<>();
		Set<Role> seen = new LinkedHashSet<>();
		for (Game.Seat p : g.players()) {
			seen.add(p.role());
			seen.add(p.believed());
		}
		for (Role r : seen) {
			List<String> items = tokensOf(r);
			if (!items.isEmpty()) {
				out.add(Map.of("role", r.displayName, "evil", r.evil(), "items", items));
			}
		}
		out.add(Map.of("role", "Pour tous", "evil", false, "items", COMMON_TOKENS));
		return out;
	}

	// ------------------------------------------------------------------
	// Ce qu'il faut faire et dire à une étape de nuit
	// ------------------------------------------------------------------
	/** Consigne de l'étape, pour le Conteur. */
	String todo(Step step) {
		return switch (step.kind) {
			case MINION_INFO -> "Les Sbires se réveillent ensemble : ils apprennent qui est le Démon et se reconnaissent.";
			case DEMON_INFO -> "Le Démon apprend qui sont ses Sbires, et trois rôles du bien absents de la partie, pour bluffer.";
			case NEW_DEMON -> "Ce joueur est devenu le Démon : il l'apprend à son réveil.";
			case ROLE -> step.role.text + (step.choose() > 0 ? " Il désigne " + step.choose() + " joueur(s), puis tu relis ce qu'il apprend."
				: " Tu relis ce qu'il apprend avant l'envoi.");
		};
	}

	/** Mises en garde sur le joueur réveillé : ivre, empoisonné, mort, absent. */
	List<String> alerts(Game g, Step step) {
		List<String> out = new ArrayList<>();
		if (step.seat < 0) {
			return out;
		}
		Game.Seat p = g.player(step.seat);
		if (p.believed() != p.role()) {
			out.add("Se croit " + p.believed().displayName + " : c'est en réalité " + p.role().displayName
				+ ". Son information doit être fausse.");
		}
		if (g.isPoisoned(step.seat)) {
			out.add("Empoisonné : son pouvoir ne fonctionne pas, son information doit être fausse.");
		} else if (p.believed() == p.role() && g.impaired(step.seat)) {
			out.add("Trompé cette nuit : son information doit être fausse.");
		}
		if (!p.alive()) {
			out.add("Mort : il ne se réveille que parce que son pouvoir le permet.");
		}
		if (s.isStandIn(p.name)) {
			out.add("Absent : un joueur fictif joue à sa place.");
		} else if (!s.isBot(p.name) && !s.host.online(p.name)) {
			out.add("Déconnecté : fais-le relayer, ou passe l'étape.");
		}
		return out;
	}

	// ------------------------------------------------------------------
	// Réveiller ou rendormir un joueur à la main
	// ------------------------------------------------------------------
	/** Joueurs réveillés à la main par le Conteur : ils voient, et lui parlent seul à seul. */
	final Set<String> awake = new LinkedHashSet<>();

	String wake(String target, boolean on) {
		Game g = s.requireLive();
		if (g.phase() != Game.Phase.NIGHT) {
			throw new GameException("On ne réveille un joueur que la nuit.");
		}
		String name = g.player(s.seatByName(target)).name;
		if (on ? !awake.add(name) : !awake.remove(name)) {
			throw new GameException(name + (on ? " est déjà réveillé." : " n'a pas été réveillé à la main."));
		}
		s.host.wake(name, on, s.settings);
		s.tell(name, new Msg().colored(on ? "[Nuit] Le Conteur te réveille : il te parle seul à seul."
			: "[Nuit] Tu te rendors.", "gold"));
		return name + (on ? " est réveillé : vous vous parlez seul à seul." : " se rendort.");
	}

	// ------------------------------------------------------------------
	// Demander un choix à un joueur, hors du déroulé
	// ------------------------------------------------------------------
	private static final class Ask {
		final int count;
		final Set<Integer> picked = new LinkedHashSet<>();

		Ask(int count) {
			this.count = count;
		}
	}

	private final Map<String, Ask> asks = new LinkedHashMap<>();

	boolean asking(String name) {
		return asks.containsKey(name);
	}

	String ask(String target, int count) {
		Game g = s.requireLive();
		if (s.storyteller == null) {
			throw new GameException("Il faut un Conteur pour poser une question à un joueur.");
		}
		String name = g.player(s.seatByName(target)).name;
		if (s.prompts.get(name) != null || name.equals(s.pendingChooser)) {
			throw new GameException(name + " a déjà quelque chose à faire : attends qu'il ait fini.");
		}
		asks.put(name, new Ask(count));
		sendAsk(name);
		return "Question posée à " + name + " : il désigne " + count + " joueur(s).";
	}

	private void sendAsk(String name) {
		Game g = s.game;
		Ask a = asks.get(name);
		List<Prompt.Option> options = new ArrayList<>();
		Msg row = new Msg();
		for (int i = 0; i < g.size(); i++) {
			Game.Seat p = g.player(i);
			options.add(new Prompt.Option(i, p.name, p.alive(), a.picked.contains(i)));
			row.button((a.picked.contains(i) ? "✔ " : "") + p.name + (p.alive() ? "" : " †"), "/vc choisir " + p.name, "Désigner " + p.name);
		}
		row.button("Valider", "/vc valider", "Valider ton choix");
		s.tell(name, new Msg().colored("[Conteur] Désigne " + a.count + " joueur(s).", "gold"));
		s.tell(name, row);
		s.setPrompt(name, new Prompt.Choose("Le Conteur te demande", a.count, options, false));
	}

	/** Bascule un joueur dans la réponse à une question du Conteur. */
	String choose(String name, String target) {
		Ask a = asks.get(name);
		int t = s.seatByName(target);
		if (!a.picked.remove(t)) {
			if (a.count == 1) {
				a.picked.clear();
			} else if (a.picked.size() >= a.count) {
				throw new GameException("Tu as déjà désigné " + a.count + " joueurs. Retire-en un d'abord.");
			}
			a.picked.add(t);
		}
		sendAsk(name);
		return "";
	}

	String validate(String name) {
		Ask a = asks.get(name);
		if (a.picked.size() != a.count) {
			throw new GameException("Il faut désigner " + a.count + " joueur(s).");
		}
		answer(name, new ArrayList<>(a.picked));
		return "Choix transmis au Conteur.";
	}

	/** Un joueur fictif répond à la question : des vivants, pas lui-même si possible. */
	void botAnswer(String bot) {
		Game g = s.game;
		Ask a = asks.get(bot);
		List<Integer> pool = new ArrayList<>(g.aliveSeats());
		pool.remove(Integer.valueOf(g.seatOf(bot)));
		List<Integer> picked = new ArrayList<>();
		while (picked.size() < a.count && !pool.isEmpty()) {
			picked.add(pool.remove(s.rng().next(pool.size())));
		}
		answer(bot, picked);
	}

	private void answer(String name, List<Integer> picked) {
		Game g = s.game;
		closeAsk(name);
		List<String> names = picked.stream().map(i -> g.player(i).name).toList();
		g.storytellerLog(name + " désigne pour le Conteur : " + String.join(", ", names) + ".");
		if (s.storyteller == null) {
			return;
		}
		Msg m = new Msg().colored("[Conteur] " + name + " désigne : " + String.join(", ", names) + ". ", "light_purple");
		for (String n : names) {
			m.button("Tuer " + n, "/vc tuer " + n, "Tuer " + n)
				.button("Empoisonner " + n, "/vc empoisonner " + n, "Empoisonner " + n);
		}
		s.tell(s.storyteller, m);
		answers.put(name, names);
	}

	/** Dernière réponse de chaque joueur à une question du Conteur (affichée sur son pupitre). */
	final Map<String, List<String>> answers = new LinkedHashMap<>();

	private void closeAsk(String name) {
		if (asks.remove(name) != null) {
			s.setPrompt(name, null);
		}
	}

	// ------------------------------------------------------------------
	// Montrer une information à un joueur
	// ------------------------------------------------------------------
	String show(String target, String text) {
		Game g = s.requireLive();
		String name = g.player(s.seatByName(target)).name;
		String t = text.trim();
		if (t.isEmpty() || t.length() > 200) {
			throw new GameException("L'information tient en 200 caractères.");
		}
		s.tell(name, new Msg().colored("[Le Conteur te montre] ", "gold").text(t));
		s.host.title(List.of(name), "Le Conteur te montre", t);
		g.storytellerLog("Le Conteur montre à " + name + " : « " + t + " ».");
		return "Montré à " + name + ".";
	}

	// ------------------------------------------------------------------
	// Voix du Conteur portée à tous
	// ------------------------------------------------------------------
	boolean broadcast;

	String toggleBroadcast() {
		Game g = s.requireGame();
		if (!broadcast && g.phase() != Game.Phase.DAY) {
			throw new GameException("La voix du Conteur ne porte à tous que de jour.");
		}
		broadcast = !broadcast;
		s.announce(broadcast ? "Le Conteur parle à tout le village." : "Le Conteur ne parle plus qu'à ceux qui l'entourent.");
		return "";
	}

	// ------------------------------------------------------------------
	// Temps de parole : des repères pour les discussions du jour
	// ------------------------------------------------------------------
	/** Joueurs qui ont demandé la parole, dans l'ordre. */
	final List<String> queue = new ArrayList<>();
	/** Tour de table en cours : ceux qui n'ont pas encore parlé. */
	final List<String> round = new ArrayList<>();
	String speaker;
	int speechLeft;
	int speechSeconds = 45;
	private int speechToken;

	private void requireDay() {
		Game g = s.requireLive();
		if (g.phase() != Game.Phase.DAY || g.dayOver()) {
			throw new GameException("Le temps de parole, c'est de jour.");
		}
	}

	/** Un joueur demande la parole, ou y renonce. */
	String request(String name) {
		requireDay();
		s.requireSeat(name);
		if (name.equals(speaker)) {
			endTurn(speechToken);
			return "Tu rends la parole.";
		}
		if (queue.remove(name)) {
			return "Tu renonces à ta demande de parole.";
		}
		queue.add(name);
		if (s.storyteller != null && s.host.online(s.storyteller) && s.mode == Session.Mode.HUMAIN) {
			s.tell(s.storyteller, new Msg().colored("[Conteur] " + name + " demande la parole. ", "light_purple")
				.button("La lui donner", "/vc parole donner " + name, "Donner la parole à " + name));
			return "Demande de parole transmise au Conteur (" + queue.size() + " en attente).";
		}
		if (speaker == null) {
			next();
			return "";
		}
		return "Tu auras la parole après " + speaker + " (" + queue.size() + " en attente).";
	}

	/** Donne la parole au suivant : tour de table d'abord, sinon file des demandes. */
	String next() {
		requireDay();
		Game g = s.game;
		while (!round.isEmpty()) {
			String n = round.remove(0);
			int seat = g.seatOf(n);
			if (seat >= 0 && g.player(seat).alive()) {
				return give(n);
			}
		}
		if (queue.isEmpty()) {
			if (speaker != null) {
				endTurn(speechToken);
				return "Plus personne ne demande la parole.";
			}
			throw new GameException("Personne ne demande la parole.");
		}
		return give(queue.remove(0));
	}

	String give(String target) {
		requireDay();
		Game g = s.game;
		String name = g.player(s.seatByName(target)).name;
		queue.remove(name);
		int token = ++speechToken;
		speaker = name;
		speechLeft = speechSeconds;
		s.host.timer(s.realNames(), "La parole est à " + name, speechLeft);
		s.host.sign(name, "🗣", "gold");
		s.announce("La parole est à " + name + " (" + speechSeconds + " secondes).");
		tick(token);
		return "";
	}

	/**
	 * Reprend un temps de parole enregistré (redémarrage du serveur) : name parle encore left secondes. Le minuteur revient
	 * à l'écran avec les autres (Session.restoreTimer).
	 */
	void resumeSpeech(String name, int left) {
		if (name == null || left <= 0 || s.game == null) {
			return;
		}
		int token = ++speechToken;
		speaker = name;
		speechLeft = left;
		tick(token);
	}

	private void tick(int token) {
		s.host.schedule(20, () -> {
			if (token != speechToken || speaker == null || s.game == null) {
				return;
			}
			if (s.paused || --speechLeft > 0) {
				tick(token);
				return;
			}
			endTurn(token);
		});
	}

	private void endTurn(int token) {
		if (token != speechToken || speaker == null) {
			return;
		}
		speechToken++;
		speaker = null;
		speechLeft = 0;
		s.host.sound(s.realNames(), "bell");
		s.restoreTimer();
		boolean auto = s.mode == Session.Mode.AUTO || s.storyteller == null || !s.host.online(s.storyteller);
		if (s.game != null && s.game.phase() == Game.Phase.DAY && !s.game.dayOver() && (!round.isEmpty() || (auto && !queue.isEmpty()))) {
			next();
		}
	}

	/** Tour de table : chaque vivant à son tour, dans l'ordre du cercle. */
	String startRound(int seconds) {
		requireDay();
		if (seconds > 0) {
			speechSeconds = Math.max(15, Math.min(180, seconds));
		}
		Game g = s.game;
		round.clear();
		for (Game.Seat p : g.players()) {
			if (p.alive()) {
				round.add(p.name);
			}
		}
		s.announce("Tour de table : chacun parle " + speechSeconds + " secondes, dans l'ordre du cercle.");
		return next();
	}

	String setSeconds(int seconds) {
		speechSeconds = Math.max(15, Math.min(180, seconds));
		return "Temps de parole : " + speechSeconds + " secondes par orateur.";
	}

	String stopSpeech(boolean say) {
		boolean was = speaker != null || !queue.isEmpty() || !round.isEmpty();
		speechToken++;
		speaker = null;
		speechLeft = 0;
		queue.clear();
		round.clear();
		if (say && was) {
			s.restoreTimer();
			s.announce("Le temps de parole est clos.");
		}
		return was ? "" : "Aucun temps de parole en cours.";
	}

	/** État du temps de parole pour les écrans, ou null s'il n'y en a pas. */
	Map<String, Object> speechView() {
		if (speaker == null && queue.isEmpty() && round.isEmpty()) {
			return null;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("speaker", speaker);
		m.put("queue", List.copyOf(queue));
		m.put("round", List.copyOf(round));
		m.put("seconds", speechSeconds);
		return m;
	}
}
