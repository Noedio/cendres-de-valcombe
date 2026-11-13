package fr.valcombe.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import fr.valcombe.net.ValcombeNet;
import fr.valcombe.session.Session;

import java.util.HashMap;
import java.util.Map;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/** Envoie à chaque joueur équipé du mod client sa vue de la partie, quand elle change. */
public final class ViewSync {
	private static final Gson GSON = new GsonBuilder().serializeNulls().create();

	private final Session session;
	private final Map<String, String> lastSent = new HashMap<>();
	private int tick;

	/** Vue de l'Atelier d'un opérateur, gardée jusqu'à une seconde (elle liste cartes, structures et créations). */
	private record Cached(int tick, int key, Map<String, Object> view) {
	}

	private final Map<String, Cached> atelierCache = new HashMap<>();

	public ViewSync(Session session) {
		this.session = session;
	}

	public static boolean hasClientMod(ServerPlayer p) {
		return ServerPlayNetworking.canSend(p, ValcombeNet.ViewPayload.TYPE);
	}

	public boolean isLeader(ServerPlayer p) {
		return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
			|| p.getScoreboardName().equals(session.storyteller())
			|| session.isTrainee(p.getScoreboardName());
	}

	/** Appelé à chaque tick : synchronise toutes les 4 ticks (5 fois par seconde). */
	public void onTick(MinecraftServer server) {
		if (++tick % 4 != 0) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!hasClientMod(p)) {
				continue;
			}
			String name = p.getScoreboardName();
			Map<String, Object> view = session.view(name, isLeader(p));
			Workshop workshop = ValcombeMod.workshop();
			if (workshop != null && Atelier.isOperator(p)) {
				// Un opérateur reçoit en plus de quoi afficher l'Atelier en jeu : cartes, structures, éditeur.
				// Refaite au plus une fois par seconde, sauf si l'opérateur vient d'agir (réponse, chantier, outil en main).
				int key = workshop.changeKey(p);
				Cached c = atelierCache.get(name);
				if (c == null || c.key() != key || tick - c.tick() >= 20) {
					c = new Cached(tick, key, workshop.gameView(p));
					atelierCache.put(name, c);
				}
				view.put("atelier", c.view());
			}
			fr.valcombe.mc.hub.Travel travel = ValcombeMod.travel();
			if (travel != null) {
				// Menu J, « Mondes » : les mondes où ce joueur peut aller, celui où il est, et s'il peut partir.
				view.put("mondes", travel.view(p));
			}
			String json = GSON.toJson(view);
			if (!json.equals(lastSent.get(name))) {
				lastSent.put(name, json);
				ServerPlayNetworking.send(p, new ValcombeNet.ViewPayload(json));
			}
		}
	}

	public void forget(String name) {
		lastSent.remove(name);
		atelierCache.remove(name);
	}
}
