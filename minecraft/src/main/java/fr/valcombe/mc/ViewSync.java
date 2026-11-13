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

/**
 * Envoie à chaque joueur équipé du mod client sa vue de la partie, quand elle change.
 * <p>
 * Une vue par joueur, de <b>sa</b> partie : celle du monde où il se trouve. Au hub ou dans un atelier, c'est sa table
 * d'attache (la partie où il est inscrit, assis, Conteur ou spectateur) ; s'il n'en a pas, une table vide (aucune
 * partie : le menu propose de s'inscrire). On ne reçoit jamais la vue d'une autre partie. Le champ {@code partie} dit
 * laquelle c'est ({@code id}, {@code nom}, {@code sorte}, {@code ici} : le joueur est dans son monde, {@code suspens} :
 * elle est en suspens et ses joueurs l'attendent au hub), ou null.
 */
public final class ViewSync {
	private static final Gson GSON = new GsonBuilder().serializeNulls().create();

	private final Map<String, String> lastSent = new HashMap<>();
	private int tick;
	/** Une table sans partie ni joueur : la vue de qui n'est à aucune partie (au hub, dans un atelier). */
	private final Session noTable = new Session(new NoTable());

	/** Vue de l'Atelier d'un opérateur, gardée jusqu'à une seconde (elle liste cartes, structures et créations). */
	private record Cached(int tick, int key, Map<String, Object> view) {
	}

	private final Map<String, Cached> atelierCache = new HashMap<>();

	public ViewSync() {
		noTable.modVersion = ValcombeMod.modVersion(); // le client y repère un serveur pas à jour, partie ou pas
	}

	public static boolean hasClientMod(ServerPlayer p) {
		return ServerPlayNetworking.canSend(p, ValcombeNet.ViewPayload.TYPE);
	}

	/** Mène-t-il la partie qu'il voit : opérateur, ou Conteur (élève) de sa partie ? */
	public static boolean isLeader(ServerPlayer p) {
		fr.valcombe.mc.games.GameRoom room = fr.valcombe.mc.games.Games.forPlayer(p);
		return room != null ? room.isLeader(p) : p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	/** La partie que voit ce joueur : celle de son monde, sinon sa table d'attache, sinon aucune (null). */
	static fr.valcombe.mc.games.GameRoom seen(ServerPlayer p) {
		fr.valcombe.mc.games.GameRoom room = fr.valcombe.mc.games.Games.of(p);
		return room != null ? room : fr.valcombe.mc.games.Games.home(p.getScoreboardName());
	}

	/** La vue de la partie de ce joueur (voir la classe), avec le champ {@code partie}. */
	Map<String, Object> viewOf(ServerPlayer p) {
		String name = p.getScoreboardName();
		fr.valcombe.mc.games.GameRoom room = seen(p);
		Map<String, Object> view;
		if (room != null) {
			view = room.session().view(name, room.isLeader(p));
			Map<String, Object> game = new java.util.LinkedHashMap<>();
			game.put("id", room.id());
			game.put("nom", room.name());
			game.put("sorte", room.kind().code());
			game.put("ici", room.here(p));
			game.put("suspens", room.suspended());
			view.put("partie", game);
		} else {
			view = noTable.view(name, p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
			view.put("partie", null);
		}
		return view;
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
			Map<String, Object> view = viewOf(p);
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
			StructureWorkshop structures = ValcombeMod.structureWorkshop();
			if (structures != null && structures.isWorkshop(p.level())) {
				// Écran de l'atelier structures (pupitres) : les parcelles, celle qu'on a choisie, le dernier message.
				view.put("atelierStructures", structures.view(p));
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

	/** L'hôte de la table vide : rien ne s'y passe, rien n'est montré ni annoncé. */
	private static final class NoTable implements fr.valcombe.session.Host {
		@Override
		public void announce(String text) {
		}

		@Override
		public void tell(String player, fr.valcombe.session.Msg msg) {
		}

		@Override
		public boolean online(String player) {
			return false;
		}

		@Override
		public void schedule(int ticks, Runnable r) {
		}

		@Override
		public void seatPlayers(java.util.List<String> players, String anchor) {
		}

		@Override
		public void nightEffects(java.util.List<String> players, boolean night, fr.valcombe.session.Settings settings) {
		}

		@Override
		public void endGame(java.util.List<String> players) {
		}
	}
}
