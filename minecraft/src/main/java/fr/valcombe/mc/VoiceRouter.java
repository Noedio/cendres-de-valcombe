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
 * se parlent seuls à seuls ; le jour, ceux qui sont dans une même maison s'entendent entre eux
 * sans être entendus de la place.
 */
public final class VoiceRouter {
	/** Joueur qui parle → joueurs qui l'entendent (absent : vocal de proximité normal). */
	private static volatile Map<UUID, List<UUID>> routes = Map.of();

	private VoiceRouter() {
	}

	public static List<UUID> targets(UUID speaker) {
		return routes.get(speaker);
	}

	static void update(MinecraftServer server, Session session, Village village) {
		Map<UUID, List<UUID>> next = new HashMap<>();
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
			List<String> names = session.voiceTargets(p.getScoreboardName());
			if (names != null) {
				List<UUID> ids = new ArrayList<>();
				for (String n : names) {
					ServerPlayer t = server.getPlayerList().getPlayerByName(n);
					if (t != null && t != p) {
						ids.add(t.getUUID());
					}
				}
				next.put(p.getUUID(), ids);
				continue;
			}
			if (day && village != null) {
				int h = village.houseAt(p.position());
				if (h >= 0) {
					next.put(p.getUUID(), byHouse.get(h).stream().filter(o -> o != p).map(ServerPlayer::getUUID).toList());
				}
			}
		}
		routes = next;
	}
}
