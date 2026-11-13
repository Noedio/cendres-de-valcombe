package fr.valcombe.mc;

import fr.valcombe.mc.games.VoiceZones;
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
 * <p>
 * <b>Une partie, un vocal.</b> Chaque partie route les joueurs de son monde, et eux seuls ; et chaque joueur est rangé
 * dans la zone de sa partie ({@link VoiceZones}) : le plugin arrête toute voix qui passerait d'une zone à l'autre (groupes
 * du vocal compris). On n'entend donc jamais une autre partie, ni le hub depuis une partie, ni l'inverse ; le Conteur
 * n'entend que sa partie. Les noms cachés dans les recoins sont ceux de chaque partie, dans ses propres équipes
 * ({@link McHost#hideNames}).
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

	/** La zone de chaque joueur (sa partie, ou hors des parties), lue par le plugin du vocal sur son fil. */
	private static volatile VoiceZones zones = VoiceZones.NONE;

	public static Route route(UUID speaker) {
		return routes.get(speaker);
	}

	/** Cette voix peut-elle aller de speaker à listener ? Seulement dans une même partie, ou hors des parties tous deux. */
	public static boolean mayHear(UUID speaker, UUID listener) {
		return zones.mayHear(speaker, listener);
	}

	/** Les zones du vocal en ce moment (pour les tests en jeu). */
	public static VoiceZones zones() {
		return zones;
	}

	/**
	 * Recalcule les routes de toutes les parties du serveur. Chaque partie route les joueurs de son monde, et
	 * seulement eux : une route ne mène jamais à un joueur d'un autre monde. Chaque joueur est rangé dans la zone de sa
	 * partie (hors des parties : hub, ateliers) ; une voix ne passe jamais d'une zone à l'autre.
	 */
	static void update(MinecraftServer server, List<fr.valcombe.mc.games.GameRoom> rooms) {
		Map<UUID, Route> next = new HashMap<>();
		Map<UUID, String> zoneOf = new HashMap<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			zoneOf.put(p.getUUID(), VoiceZones.OUTSIDE);
		}
		for (fr.valcombe.mc.games.GameRoom room : rooms) {
			java.util.Set<String> inNooks = new java.util.HashSet<>();
			ServerLevel level = room.host().levelIfReady();
			if (level != null) {
				for (ServerPlayer p : level.players()) {
					zoneOf.put(p.getUUID(), room.id());
				}
			}
			route(server, room.session(), room.host().village(), level, next, inNooks);
			room.host().hideNames(inNooks);
		}
		zones = new VoiceZones(zoneOf);
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
}
