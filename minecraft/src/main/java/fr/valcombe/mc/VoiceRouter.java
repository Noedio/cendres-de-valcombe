package fr.valcombe.mc;

import fr.valcombe.session.Session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Routes du vocal, recalculées sur le fil du serveur (quatre fois par seconde) et lues par
 * le plugin Simple Voice Chat sur son propre fil : la nuit, le Conteur et le joueur réveillé
 * se parlent seuls à seuls ; pendant le Conseil, tout le village s'entend (les morts se taisent
 * sauf si on leur donne la parole) ; le jour, ceux qui sont dans une même maison s'entendent
 * entre eux ; ailleurs, on chuchote ou on crie à la portée choisie.
 */
public final class VoiceRouter {
	/**
	 * Qui entend un joueur. range == 0 : à pleine voix, où qu'ils soient ; range > 0 : voix
	 * placée dans le monde, entendue jusqu'à range blocs.
	 */
	public record Route(List<UUID> targets, float range) {
	}

	/** Joueur qui parle → route (absent : vocal de proximité normal). */
	private static volatile Map<UUID, Route> routes = Map.of();

	private VoiceRouter() {
	}

	private static final boolean VOICE = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voicechat");

	public static Route route(UUID speaker) {
		return routes.get(speaker);
	}

	/**
	 * Recalcule les routes de toutes les parties du serveur. Chaque partie route les joueurs de son monde, et
	 * seulement eux : une route ne mène jamais à un joueur d'un autre monde. On n'entend donc jamais une autre partie,
	 * et un joueur au hub ou dans un atelier garde le vocal de proximité de son monde.
	 */
	static void update(MinecraftServer server, List<fr.valcombe.mc.games.GameRoom> rooms) {
		Map<UUID, Route> next = new HashMap<>();
		java.util.Set<String> inNooks = new java.util.HashSet<>();
		for (fr.valcombe.mc.games.GameRoom room : rooms) {
			route(server, room.session(), room.host().village(), room.host().levelIfReady(), next, inNooks);
		}
		hideNames(server, inNooks);
		routes = next;
	}

	/**
	 * Les routes d'une partie. level : son monde (null s'il n'est pas prêt). Seuls les joueurs qui s'y trouvent sont
	 * dans la partie : un joueur au hub, dans un atelier ou dans une autre partie n'entend jamais cette table (ni la
	 * nuit, ni le Conseil).
	 */
	private static void route(MinecraftServer server, Session session, Village village, ServerLevel level, Map<UUID, Route> next,
			java.util.Set<String> inNooks) {
		if (level == null) {
			return;
		}
		List<ServerPlayer> players = List.copyOf(level.players());
		boolean day = session.game() != null && session.game().phase() == fr.valcombe.rules.Game.Phase.DAY;
		Map<Integer, List<ServerPlayer>> byHouse = new HashMap<>();
		Map<Integer, List<ServerPlayer>> byNook = new HashMap<>();
		if (day && village != null) {
			for (ServerPlayer p : players) {
				if (p.level() != level) {
					continue; // au hub ou dans un autre monde : aux mêmes coordonnées, ce n'est pas une maison du village
				}
				int h = village.houseAt(p.position());
				if (h >= 0) {
					byHouse.computeIfAbsent(h, k -> new ArrayList<>()).add(p);
				}
				int n = village.nookAt(p.position(), (net.minecraft.server.level.ServerLevel) p.level());
				if (n >= 0) {
					byNook.computeIfAbsent(n, k -> new ArrayList<>()).add(p);
					inNooks.add(p.getScoreboardName());
				}
			}
		}
		if (!VOICE) {
			return; // pas de Simple Voice Chat sur ce serveur : aucune route à calculer
		}
		for (ServerPlayer p : players) {
			if (p.level() != level) {
				continue; // hors du monde de la partie : vocal de proximité ordinaire, dans son monde
			}
			Session.VoiceRule rule = session.voiceRule(p.getScoreboardName());
			if (rule != null && rule.targets() != null) {
				List<UUID> ids = new ArrayList<>();
				for (String n : rule.targets()) {
					ServerPlayer t = server.getPlayerList().getPlayerByName(n);
					// Seulement ceux qui sont à la table, dans le monde de la partie.
					if (t != null && t != p && t.level() == level) {
						ids.add(t.getUUID());
					}
				}
				next.put(p.getUUID(), new Route(ids, 0));
				continue;
			}
			if (day && village != null) {
				int h = village.houseAt(p.position());
				if (h >= 0) {
					next.put(p.getUUID(), new Route(byHouse.get(h).stream().filter(o -> o != p).map(ServerPlayer::getUUID).toList(), 0));
					continue;
				}
				// Un recoin est une salle privée, comme une maison.
				for (var e : byNook.entrySet()) {
					if (e.getValue().contains(p)) {
						next.put(p.getUUID(), new Route(e.getValue().stream().filter(o -> o != p).map(ServerPlayer::getUUID).toList(), 0));
						break;
					}
				}
				if (next.containsKey(p.getUUID())) {
					continue;
				}
			}
			if (rule != null && rule.range() > 0) {
				double r2 = rule.range() * rule.range();
				List<UUID> ids = players.stream()
					.filter(o -> o != p && o.level() == p.level() && o.position().distanceToSqr(p.position()) <= r2)
					.map(ServerPlayer::getUUID).toList();
				next.put(p.getUUID(), new Route(ids, (float) rule.range()));
			}
		}
	}

	private static final String HIDDEN_TEAM = "valcombe_cache";

	/** Les noms des joueurs vivants qui sont dans un recoin ne se voient pas depuis la place. */
	private static void hideNames(MinecraftServer server, java.util.Set<String> hidden) {
		var board = server.getScoreboard();
		net.minecraft.world.scores.PlayerTeam cache = board.getPlayerTeam(HIDDEN_TEAM);
		if (cache == null) {
			if (hidden.isEmpty()) {
				return;
			}
			cache = board.addPlayerTeam(HIDDEN_TEAM);
			cache.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.NEVER);
		}
		net.minecraft.world.scores.PlayerTeam alive = board.getPlayerTeam("valcombe_vivants");
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			String name = p.getScoreboardName();
			net.minecraft.world.scores.PlayerTeam current = board.getPlayersTeam(name);
			if (hidden.contains(name)) {
				if (current == null || current == alive) {
					board.addPlayerToTeam(name, cache);
				}
			} else if (current == cache) {
				board.removePlayerFromTeam(name, cache);
				// Noms cachés pour tous en ce moment (réglage du Conteur) : il retrouve l'équipe des vivants.
				if (alive != null && alive.getNameTagVisibility() == net.minecraft.world.scores.Team.Visibility.NEVER) {
					board.addPlayerToTeam(name, alive);
				}
			}
		}
	}
}
