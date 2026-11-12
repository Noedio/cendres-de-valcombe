package fr.valcombe.mc;

import fr.valcombe.session.Session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
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

	public static Route route(UUID speaker) {
		return routes.get(speaker);
	}

	static void update(MinecraftServer server, Session session, Village village) {
		Map<UUID, Route> next = new HashMap<>();
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		boolean day = session.game() != null && session.game().phase() == fr.valcombe.rules.Game.Phase.DAY;
		Map<Integer, List<ServerPlayer>> byHouse = new HashMap<>();
		if (day && village != null) {
			for (ServerPlayer p : players) {
				int h = village.houseAt(p.position());
				if (h >= 0) {
					byHouse.computeIfAbsent(h, k -> new ArrayList<>()).add(p);
				}
			}
		}
		for (ServerPlayer p : players) {
			Session.VoiceRule rule = session.voiceRule(p.getScoreboardName());
			if (rule != null && rule.targets() != null) {
				List<UUID> ids = new ArrayList<>();
				for (String n : rule.targets()) {
					ServerPlayer t = server.getPlayerList().getPlayerByName(n);
					if (t != null && t != p) {
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
			}
			if (rule != null && rule.range() > 0) {
				double r2 = rule.range() * rule.range();
				List<UUID> ids = players.stream()
					.filter(o -> o != p && o.level() == p.level() && o.position().distanceToSqr(p.position()) <= r2)
					.map(ServerPlayer::getUUID).toList();
				next.put(p.getUUID(), new Route(ids, (float) rule.range()));
			}
		}
		routes = next;
	}
}
