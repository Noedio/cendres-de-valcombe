package fr.valcombe.session;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Role;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * La vie sociale d'une partie, hors règles du jeu : apartés du Conteur, appels au Conteur,
 * annonces de rôle, signes, conversations privées à deux. Sortie de Session pour l'alléger.
 */
final class Social {
	private final Session ses;
	/** Rôles annoncés publiquement par les joueurs (ce qu'ils disent être, vrai ou non). */
	final Map<String, Role> claims = new LinkedHashMap<>();
	/** Conversations privées entre deux joueurs, le jour : chacun → son interlocuteur. */
	final Map<String, String> talks = new LinkedHashMap<>();
	/** Invitations à parler en privé : invité → qui l'invite. */
	final Map<String, String> talkInvites = new LinkedHashMap<>();

	Social(Session ses) {
		this.ses = ses;
	}

	/** Joueur pris à part par le Conteur (vocal privé), ou null. */
	String aside;

	public String aside() {
		return aside;
	}

	/** Le Conteur prend un joueur à part (vocal seul à seul), ou met fin à l'aparté (target null). */
	public String setAside(String target) {
		Game g = ses.requireGame();
		if (ses.storyteller == null) {
			throw new GameException("Pas de Conteur dans cette partie.");
		}
		if (target == null || target.equals(aside)) {
			if (aside == null) {
				return "Aucun aparté en cours.";
			}
			String was = aside;
			aside = null;
			ses.host.title(List.of(was), "", "Fin de l'aparté avec le Conteur");
			ses.announce("Le Conteur rend " + was + " au village.");
			return "Aparté terminé.";
		}
		if (g.phase() != Game.Phase.DAY) {
			throw new GameException("La nuit, le Conteur parle déjà au joueur réveillé.");
		}
		String name = g.player(ses.seatByName(target)).name;
		if (ses.bots.contains(name)) {
			throw new GameException(name + " est un joueur fictif.");
		}
		aside = name;
		ses.host.title(List.of(name), "Le Conteur te prend à part", "Vous vous entendez seuls à seuls");
		ses.host.sound(List.of(name), "bell");
		ses.announce("Le Conteur prend " + name + " à part.");
		return "Tu parles seul à seul avec " + name + ". Accroupi + clic droit avec le bâton (ou /vc aparte fin) pour finir.";
	}

	private final Map<String, Long> calls = new java.util.HashMap<>();

	/** Un joueur appelle le Conteur (question de règle, pouvoir à déclarer…). */
	public String callStoryteller(String name) {
		ses.requireGame();
		if (ses.storyteller == null || !ses.host.online(ses.storyteller)) {
			throw new GameException("Le Conteur est automatique dans cette partie : utilise le menu (touche J).");
		}
		long now = System.currentTimeMillis();
		if (now - calls.getOrDefault(name, 0L) < 15_000) {
			return "Le Conteur est déjà prévenu.";
		}
		calls.put(name, now);
		ses.tell(ses.storyteller, new Msg().colored("[Appel] " + name + " demande le Conteur. ", "light_purple")
			.button("Prendre à part", "/vc aparte " + name, "Vocal seul à seul avec " + name)
			.button("Grimoire", "/vc grimoire", "Ouvrir le grimoire"));
		ses.host.sound(List.of(ses.storyteller), "clock");
		return "Le Conteur est prévenu.";
	}

	/** Pour le Conteur, d'un coup d'œil : rôle, état et rappels d'un joueur. */
	public String roleSummary(String name) {
		Game g = ses.game;
		int s = g == null ? -1 : g.seatOf(name);
		if (s < 0) {
			return name;
		}
		Game.Seat p = g.player(s);
		StringBuilder b = new StringBuilder(name).append(" · ").append(p.role().displayName);
		if (p.believed() != p.role()) {
			b.append(" (se croit ").append(p.believed().displayName).append(")");
		}
		if (!p.alive()) {
			b.append(p.ghostVote() ? " · mort, a son vote" : " · mort");
		}
		if (g.isPoisoned(s)) {
			b.append(" · empoisonné");
		}
		if (g.protectedSeat() == s) {
			b.append(" · protégé");
		}
		for (String r : ses.reminders.getOrDefault(name, List.of())) {
			b.append(" · ").append(r);
		}
		return b.toString();
	}

	// ------------------------------------------------------------------
	// Annonces de rôle, signes, conversations privées
	// ------------------------------------------------------------------
	/** Le joueur annonce publiquement un rôle (vrai ou bluff) ; « aucun » retire l'annonce. */
	public String claim(String name, String roleId) {
		Game g = ses.requireGame();
		if (ses.scenario != null && ses.scenario.regles.annoncesInterdites) {
			throw new GameException("Les annonces de rôle sont interdites dans ce scénario.");
		}
		ses.requireSeat(name);
		if (roleId.equalsIgnoreCase("aucun")) {
			claims.remove(name);
			ses.announce(name + " retire son annonce.");
		} else {
			Role r = ses.parseRole(roleId);
			claims.put(name, r);
			ses.announce(name + " dit être : " + r.displayName + ".");
		}
		ses.updateBoards();
		return "";
	}

	public Role claimOf(String name) {
		return claims.get(name);
	}

	private final Map<String, Long> lastSign = new java.util.HashMap<>();

	/** Signe rapide au-dessus de la tête : oui, non, question, attention, réflexion, accord. */
	public String sign(String name, String which) {
		String[] s = switch (which) {
			case "oui" -> new String[] {"✔", "green"};
			case "non" -> new String[] {"✘", "red"};
			case "question" -> new String[] {"?", "yellow"};
			case "attention" -> new String[] {"!", "gold"};
			case "reflexion" -> new String[] {"…", "gray"};
			case "accord" -> new String[] {"✋", "aqua"};
			default -> throw new GameException("Signes : oui, non, question, attention, reflexion, accord.");
		};
		long now = System.currentTimeMillis();
		if (now - lastSign.getOrDefault(name, 0L) < 800) {
			return "";
		}
		lastSign.put(name, now);
		ses.host.sign(name, s[0], s[1]);
		return "";
	}

	/** Invite un joueur à parler en privé (vocal seul à seul), le jour. */
	public String inviteTalk(String from, String to) {
		Game g = ses.requireGame();
		if (g.phase() != Game.Phase.DAY) {
			throw new GameException("On ne parle en privé que de jour.");
		}
		if (ses.scenario != null && ses.scenario.regles.apartesInterdits) {
			throw new GameException("Les conversations privées sont interdites dans ce scénario.");
		}
		ses.requireSeat(from);
		String target = g.player(ses.seatByName(to)).name;
		if (target.equals(from)) {
			throw new GameException("Parler à soi-même ?");
		}
		if (ses.bots.contains(target)) {
			throw new GameException(target + " est un joueur fictif : chuchote-lui plutôt.");
		}
		if (talks.containsKey(from)) {
			throw new GameException("Tu parles déjà avec " + talks.get(from) + " (/vc parler fin).");
		}
		if (talks.containsKey(target)) {
			throw new GameException(target + " parle déjà avec quelqu'un.");
		}
		talkInvites.put(target, from);
		ses.tell(target, new Msg().colored("[Valcombe] " + from + " veut te parler en privé. ", "light_purple")
			.button("Accepter", "/vc parler accepter", "Vous ne vous entendrez qu'à deux")
			.button("Refuser", "/vc parler refuser", "Décliner"));
		ses.host.sound(List.of(target), "clock");
		return "Invitation envoyée à " + target + ".";
	}

	public String answerTalk(String name, boolean yes) {
		String from = talkInvites.remove(name);
		if (from == null) {
			throw new GameException("Personne ne t'a invité à parler.");
		}
		if (!yes) {
			ses.tell(from, new Msg().colored(name + " décline ton invitation.", "gray"));
			return "Invitation refusée.";
		}
		if (talks.containsKey(from) || talks.containsKey(name)) {
			throw new GameException("L'un de vous parle déjà avec quelqu'un.");
		}
		talks.put(from, name);
		talks.put(name, from);
		ses.announce(from + " et " + name + " s'isolent pour parler.");
		ses.host.whisperBubble(from, name);
		return "Tu parles en privé avec " + from + ". /vc parler fin pour revenir au village.";
	}

	public String endTalk(String name) {
		String other = talks.remove(name);
		if (other == null) {
			throw new GameException("Tu ne parles en privé avec personne.");
		}
		talks.remove(other);
		ses.tell(other, new Msg().colored(name + " met fin à votre conversation privée.", "gray"));
		return "Tu reviens au village.";
	}

	/** Fin de toutes les conversations privées (cloche, nuit, vote). */
	void endAllTalks() {
		if (!talks.isEmpty()) {
			talks.clear();
			talkInvites.clear();
		}
	}

	public String talkingWith(String name) {
		return talks.get(name);
	}

}
