package fr.valcombe.session;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleType;
import fr.valcombe.rules.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Joueurs fictifs un peu crédibles, pour l'entraînement et les parties d'essai : le premier jour,
 * chacun annonce un rôle (le bien dit ce qu'il croit être, le mal bluffe) ; ensuite, certains
 * accusent ceux qu'ils soupçonnent, et tous votent selon ce qu'ils savent.
 *
 * Soupçons du bien : deux joueurs qui annoncent le même rôle (l'un ment), et un joueur qui n'a
 * rien annoncé. Le mal connaît son camp : il accuse le bien et protège son Démon.
 */
final class BotBrain {
	private final Session s;
	private final Rng rng;

	BotBrain(Session s, Rng rng) {
		this.s = s;
		this.rng = rng;
	}

	/** À l'aube : annonces (premier jour), puis quelques accusations au fil de la journée. */
	void dawn() {
		Game g = s.game;
		if (g == null || s.bots.isEmpty()) {
			return;
		}
		int day = g.day();
		int k = 0;
		for (String bot : new ArrayList<>(s.bots)) {
			int seat = g.seatOf(bot);
			if (seat < 0 || !g.player(seat).alive() || s.social.claims.containsKey(bot)) {
				continue;
			}
			Role claim = claimFor(g, seat);
			if (claim != null) {
				s.host.schedule(60 + 30 * k++, () -> {
					try {
						if (s.game == g && g.day() == day && !s.social.claims.containsKey(bot)) {
							s.claim(bot, claim.name());
						}
					} catch (GameException ignored) {
						// annonces interdites par le scénario : tant pis
					}
				});
			}
		}
		// Une ou deux accusations dans la journée, si les joueurs humains n'ont pas pris les devants.
		int accusers = 1 + rng.next(2);
		for (int i = 0; i < accusers; i++) {
			int delay = 600 + 400 * i + rng.next(200);
			s.host.schedule(delay, () -> tryAccuse(g, day, 0));
		}
	}

	/** Rôle annoncé : celui que l'on croit avoir (bien), ou un bluff (mal). */
	private Role claimFor(Game g, int seat) {
		Game.Seat p = g.player(seat);
		if (p.team() == Team.GOOD) {
			return p.believed();
		}
		List<Role> bluffs = new ArrayList<>(g.bluffs());
		bluffs.removeIf(r -> s.social.claims.containsValue(r));
		if (!bluffs.isEmpty()) {
			return bluffs.get(rng.next(bluffs.size()));
		}
		List<Role> town = new ArrayList<>(Role.ofType(RoleType.CITADIN));
		town.retainAll(s.script());
		return town.isEmpty() ? null : town.get(rng.next(town.size()));
	}

	private void tryAccuse(Game g, int day, int attempt) {
		if (s.game != g || g.day() != day || g.phase() != Game.Phase.DAY || g.dayOver()) {
			return;
		}
		if (s.voteNom != null || !s.nominationsOpen()) {
			if (attempt < 6) {
				s.host.schedule(200, () -> tryAccuse(g, day, attempt + 1));
			}
			return;
		}
		List<String> candidates = new ArrayList<>();
		for (String bot : s.bots) {
			int seat = g.seatOf(bot);
			if (seat >= 0 && g.player(seat).alive() && !g.hasNominatedToday(seat)) {
				candidates.add(bot);
			}
		}
		if (candidates.isEmpty()) {
			return;
		}
		String accuser = candidates.get(rng.next(candidates.size()));
		Integer target = suspect(g, g.seatOf(accuser));
		if (target == null) {
			return;
		}
		try {
			s.nominate(accuser, g.player(target).name);
		} catch (GameException ignored) {
			// cible déjà nommée, règle du scénario… : la journée continue
		}
	}

	/** Le joueur le plus suspect aux yeux de ce joueur fictif (vivant, pas encore nommé aujourd'hui). */
	private Integer suspect(Game g, int seat) {
		Map<Integer, Double> score = suspicion(g, seat);
		Integer best = null;
		double top = -1;
		for (var e : score.entrySet()) {
			int t = e.getKey();
			if (t == seat || !g.player(t).alive() || g.wasNominatedToday(t)) {
				continue;
			}
			double v = e.getValue() + rng.next(100) / 250.0;
			if (v > top) {
				top = v;
				best = t;
			}
		}
		return best;
	}

	/** Soupçon de chaque joueur, de 0 à environ 2. */
	private Map<Integer, Double> suspicion(Game g, int seat) {
		Map<Integer, Double> out = new HashMap<>();
		boolean evil = g.player(seat).team() == Team.EVIL;
		Map<Role, Integer> claimed = new HashMap<>();
		for (Role r : s.social.claims.values()) {
			claimed.merge(r, 1, Integer::sum);
		}
		for (int t = 0; t < g.size(); t++) {
			Game.Seat p = g.player(t);
			double v;
			if (evil) {
				// Le mal accuse le bien, jamais les siens.
				v = p.team() == Team.EVIL ? -5 : 1;
			} else {
				Role c = s.social.claims.get(p.name);
				v = c == null ? 0.8 : claimed.getOrDefault(c, 0) > 1 ? 1.5 : 0.3;
			}
			out.put(t, v);
		}
		return out;
	}

	/** Vote d'un joueur fictif sur l'accusé. */
	boolean votesYes(String bot) {
		Game g = s.game;
		Game.Nomination nom = s.voteNom;
		if (g == null || nom == null) {
			return false;
		}
		int seat = g.seatOf(bot);
		if (seat < 0) {
			return false;
		}
		Game.Seat me = g.player(seat);
		Game.Seat target = g.player(nom.nominee());
		if (!me.alive() && rng.next(3) > 0) {
			return false; // les morts gardent leur unique vote pour plus tard
		}
		if (target.traveller()) {
			// Exil d'un voyageur : le mal chasse les voyageurs du bien, le bien hésite.
			return me.team() == Team.EVIL ? target.team() == Team.GOOD : rng.next(10) < 4;
		}
		if (me.team() == Team.EVIL) {
			if (target.role().type == RoleType.DEMON) {
				return false;
			}
			return target.team() == Team.GOOD ? rng.next(10) < 7 : rng.next(10) < 3;
		}
		if (nom.nominee() == seat) {
			return false;
		}
		double v = suspicion(g, seat).getOrDefault(nom.nominee(), 0.5);
		return rng.next(100) < (int) (25 + 35 * v);
	}
}
