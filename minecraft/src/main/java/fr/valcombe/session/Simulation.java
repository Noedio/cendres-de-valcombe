package fr.valcombe.session;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Rng;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.Setup;
import fr.valcombe.rules.Team;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Simulation d'équilibre pour l'Atelier : des parties entières jouées par des joueurs fictifs,
 * avec le Conteur automatique. Les joueurs fictifs accusent et votent au hasard : le résultat
 * montre surtout si un script penche nettement vers un camp, ou si des parties se bloquent.
 */
public final class Simulation {
	/** Résultat : parties gagnées par chaque camp, durée moyenne en jours, parties bloquées. */
	public record Result(int games, int good, int evil, double days, int stuck, String problem,
		int shortest, int longest, java.util.Map<String, int[]> roles) {
		public Result(int games, int good, int evil, double days, int stuck, String problem) {
			this(games, good, evil, days, stuck, problem, 0, 0, java.util.Map.of());
		}

		public int goodPercent() {
			return games == 0 ? 0 : Math.round(100f * good / games);
		}
	}

	private Simulation() {
	}

	/** Hôte muet : aucune sortie, les tâches sont exécutées à la suite. */
	private static final class QuietHost implements Host {
		final Deque<Runnable> tasks = new ArrayDeque<>();

		@Override
		public void announce(String text) {
		}

		@Override
		public void tell(String player, Msg msg) {
		}

		@Override
		public boolean online(String player) {
			return false;
		}

		@Override
		public void schedule(int ticks, Runnable r) {
			tasks.add(r);
		}

		@Override
		public void seatPlayers(List<String> players, String anchor) {
		}

		@Override
		public void nightEffects(List<String> players, boolean night, Settings settings) {
		}

		@Override
		public void endGame(List<String> players) {
		}

		void drain() {
			int guard = 0;
			while (!tasks.isEmpty() && guard++ < 100_000) {
				tasks.poll().run();
			}
		}
	}

	public static Result run(Set<Role> script, int players, int games, long seed) {
		return run(script, null, players, games, seed);
	}

	/**
	 * Simule un scénario entier (rôles imposés, répartition, ordre de nuit, règles) quand il est donné.
	 * roles : pour chaque rôle, { parties où il était en jeu, parties gagnées par son camp }.
	 */
	public static Result run(Set<Role> script, ScenarioDef scenario, int players, int games, long seed) {
		String problem = Setup.check(script, players, scenario == null ? null : scenario.counts(players));
		if (problem != null) {
			return new Result(0, 0, 0, 0, 0, problem);
		}
		java.util.Map<String, int[]> perRole = new java.util.LinkedHashMap<>();
		int shortest = Integer.MAX_VALUE;
		int longest = 0;
		int good = 0;
		int evil = 0;
		int stuck = 0;
		long days = 0;
		for (int k = 0; k < games; k++) {
			Random random = new Random(seed + k);
			QuietHost h = new QuietHost();
			Session s = new Session(h, Rng.of(random));
			try {
				if (scenario != null) {
					s.setScenarios(List.of(scenario));
					s.useScenario(scenario.id);
				} else {
					s.useScript("Simulation", script);
				}
				s.addBots(players);
				s.start("Simulation", Session.Mode.AUTO);
				int day = 0;
				for (; day < 60 && s.game() != null; day++) {
					s.startNight();
					h.drain();
					if (s.game() == null) {
						break;
					}
					Game g = s.game();
					List<Integer> alive = g.aliveSeats();
					try {
						if (g.phase() == Game.Phase.DAY && !g.dayOver() && !s.nominationsOpen()) {
							s.setNominations(true);
						}
						s.nominate(g.player(alive.get(random.nextInt(alive.size()))).name,
							g.player(alive.get(random.nextInt(alive.size()))).name);
					} catch (GameException ignored) {
						// accusation refusée (règle, joueur déjà nommé…) : la journée continue
					}
					h.drain();
					if (s.game() != null && !s.game().dayOver()) {
						s.endDay();
					}
				}
				if (s.game() != null || s.lastGame() == null || s.lastGame().winner() == null) {
					stuck++;
					continue;
				}
				days += s.lastGame().day();
				shortest = Math.min(shortest, s.lastGame().day());
				longest = Math.max(longest, s.lastGame().day());
				for (Game.Seat p : s.lastGame().players()) {
					int[] c = perRole.computeIfAbsent(p.role().displayName, key -> new int[2]);
					c[0]++;
					if (p.team() == s.lastGame().winner()) {
						c[1]++;
					}
				}
				if (s.lastGame().winner() == Team.GOOD) {
					good++;
				} else {
					evil++;
				}
			} catch (RuntimeException e) {
				return new Result(k, good, evil, 0, stuck, "Erreur pendant la partie " + (k + 1) + " : " + e.getMessage());
			}
		}
		int finished = good + evil;
		return new Result(games, good, evil, finished == 0 ? 0 : (double) days / finished, stuck, null,
			finished == 0 ? 0 : shortest, longest, perRole);
	}
}
