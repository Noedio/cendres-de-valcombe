package fr.valcombe.mc.games;

import fr.valcombe.mc.McHost;
import fr.valcombe.mc.ValcombeUi;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.rules.Game;
import fr.valcombe.session.Session;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * Une partie du serveur : un monde de partie (la « Partie principale », ou une partie d'essai) et tout ce qui y vit.
 * <ul>
 * <li>son <b>hôte</b> ({@link McHost}) : le village, la scène, les chantiers, les minuteurs, les annonces, la
 * sauvegarde, le tout dans son monde seulement ;</li>
 * <li>sa <b>session</b> ({@link Session}) : les règles, le salon, le Conteur, la partie en cours ;</li>
 * <li>ses <b>menus</b> ({@link ValcombeUi}), pour les joueurs sans le mod client.</li>
 * </ul>
 * Une partie est créée et fermée par {@link Games} ; elle ne connaît que son monde. Fil du serveur.
 */
public final class GameRoom {
	private final String worldId;
	private final McHost host;
	private final Session session;
	private final ValcombeUi ui;

	GameRoom(String worldId, McHost host, Session session, ValcombeUi ui) {
		this.worldId = worldId;
		this.host = host;
		this.session = session;
		this.ui = ui;
	}

	/** L'identifiant du monde de cette partie (« partie-principale », « essai-du-mardi »...) : celui de la partie aussi. */
	public String id() {
		return worldId;
	}

	public McHost host() {
		return host;
	}

	public Session session() {
		return session;
	}

	public ValcombeUi ui() {
		return ui;
	}

	/** La fiche du monde de cette partie, ou null si elle vient de disparaître du registre (suppression en cours). */
	public WorldEntry entry() {
		Games g = Games.current();
		return g == null ? null : g.worlds().find(worldId);
	}

	/** Le nom affiché de la partie : celui de son monde. */
	public String name() {
		return host.worldName();
	}

	/** Partie ou partie d'essai. */
	public WorldKind kind() {
		WorldEntry e = entry();
		return e == null ? WorldKind.ESSAI : e.sorte();
	}

	/** La partie principale du serveur (créée au premier démarrage ; elle ne se supprime pas). */
	public boolean main() {
		return host.mainGame();
	}

	/** Le monde de la partie s'il est ouvert, sinon null. */
	public ServerLevel levelIfReady() {
		return host.levelIfReady();
	}

	/** Ce joueur est-il dans le monde de cette partie ? */
	public boolean here(ServerPlayer p) {
		return host.inGameWorld(p);
	}

	/** Ce joueur mène-t-il cette partie : opérateur, Conteur de cette partie, ou élève de son entraînement ? */
	public boolean isLeader(ServerPlayer p) {
		String name = p.getScoreboardName();
		return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
			|| name.equals(session.storyteller()) || session.isTrainee(name);
	}

	/** Une partie est-elle en cours (joueurs assis, de la mise en place à la fin) ? */
	public boolean inProgress() {
		Game g = session.game();
		return g != null && g.phase() != Game.Phase.SETUP && g.phase() != Game.Phase.ENDED;
	}

	/** Cette partie vue par la logique des tables (voir {@link GameSeats}). */
	public GameSeats.Table table() {
		return new GameSeats.Table(worldId, name(), session);
	}

	/** Ce que la partie fait en une ligne : « nuit 2 », « jour 1 », « 3 inscrit(s) », « salon ouvert ». */
	public String state() {
		Game g = session.game();
		if (g != null && g.winner() == null) {
			return switch (g.phase()) {
				case NIGHT -> "nuit " + g.night();
				case DAY -> "jour " + g.day();
				default -> "mise en place";
			};
		}
		int n = session.lobby().size();
		return n == 0 ? "salon ouvert" : n + " inscrit(s)";
	}

	@Override
	public String toString() {
		return "GameRoom[" + worldId + "]";
	}
}
