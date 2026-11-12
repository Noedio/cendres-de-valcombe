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
				view.put("atelier", workshop.gameView(p));
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
	}
}
