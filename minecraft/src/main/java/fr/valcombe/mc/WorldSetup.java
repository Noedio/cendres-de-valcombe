package fr.valcombe.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Le monde d'une partie : pas de créatures, pas de dégâts du décor, ni cycle jour/nuit
 * ni météo naturels (l'heure suit la phase de la partie), messages de mort et de progrès masqués.
 * <p>
 * Tout se fait dans <b>un monde donné</b> (celui de la partie, voir {@link McHost#level()}), jamais dans le monde
 * principal de Minecraft : les commandes partent de ce monde, les entités qu'on y vise sont les siennes, l'heure est
 * celle de son horloge (les mondes de Valcombe ont chacun la leur).
 */
public final class WorldSetup {
	private WorldSetup() {
	}

	/**
	 * Règles de la partie dans ce monde. Le gestionnaire de mondes les pose déjà à l'ouverture d'un monde de
	 * Valcombe ; elles sont reposées ici pour qu'un réglage changé à la main (/gamerule) ne gâche pas la partie.
	 */
	public static void apply(ServerLevel level) {
		MinecraftServer server = level.getServer();
		GameRules r = level.getGameRules();
		for (GameRule<Boolean> rule : java.util.List.of(
				GameRules.SPAWN_MOBS, GameRules.SPAWN_MONSTERS, GameRules.SPAWN_PATROLS, GameRules.SPAWN_PHANTOMS,
				GameRules.SPAWN_WANDERING_TRADERS, GameRules.SPAWN_WARDENS, GameRules.RAIDS,
				GameRules.ADVANCE_TIME, GameRules.ADVANCE_WEATHER,
				GameRules.FALL_DAMAGE, GameRules.FIRE_DAMAGE, GameRules.DROWNING_DAMAGE, GameRules.FREEZE_DAMAGE,
				GameRules.MOB_GRIEFING, GameRules.TNT_EXPLODES, GameRules.PVP,
				GameRules.SHOW_ADVANCEMENT_MESSAGES, GameRules.SHOW_DEATH_MESSAGES, GameRules.LOCATOR_BAR)) {
			r.set(rule, false, server);
		}
		quiet(level);
		r.set(GameRules.KEEP_INVENTORY, true, server);
		r.set(GameRules.IMMEDIATE_RESPAWN, true, server);
		r.set(GameRules.RANDOM_TICK_SPEED, 0, server);
		// La difficulté et la météo valent pour tout le serveur : paisible et ciel clair partout.
		server.setDifficulty(Difficulty.PEACEFUL, true);
		run(level, "weather clear");
		day(level);
	}

	/**
	 * Pas d'annonce de progrès Minecraft (« X a obtenu le progrès… ») dans ce monde de Valcombe, quelle que soit sa
	 * sorte (hub, partie, atelier) : la règle announceAdvancements est mise à faux pour ce monde et pour le serveur
	 * (dans cette version de Minecraft, un monde lit les règles du serveur ; un monde Fantasy peut avoir les siennes).
	 * Le gestionnaire de mondes l'appelle à chaque ouverture ; les bulles de progrès, elles, sont masquées côté client.
	 */
	public static void quiet(ServerLevel level) {
		MinecraftServer server = level.getServer();
		level.getGameRules().set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, server);
		if (server.getGameRules() != level.getGameRules()) {
			server.getGameRules().set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, server);
		}
	}

	/** Midi dans ce monde (son horloge à lui : les autres mondes gardent leur heure). */
	public static void day(ServerLevel level) {
		run(level, "time set 6000");
	}

	/** Minuit dans ce monde. */
	public static void night(ServerLevel level) {
		run(level, "time set 18000");
	}

	/**
	 * Lance une commande dans ce monde-ci (ses blocs, ses entités, son horloge), sans rien afficher. Attention : un
	 * sélecteur {@code @e} ou {@code @a} sans position vise tous les mondes ; pour s'en tenir à celui-ci, ajouter
	 * {@code distance=0..} (voir {@link #HERE}).
	 */
	static void run(ServerLevel level, String command) {
		MinecraftServer server = level.getServer();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(), command);
	}

	/**
	 * À ajouter aux arguments d'un sélecteur {@code @e[...]} ou {@code @a[...]} pour ne viser que le monde où la
	 * commande est lancée : sans position ni distance, Minecraft cherche dans tous les mondes.
	 */
	static final String HERE = "distance=0..";

	/** Retire de ce monde (et de lui seul) les entités que désignent ces arguments de sélecteur ({@code tag=vc_scene}). */
	static void kill(ServerLevel level, String filter) {
		run(level, "kill @e[" + filter + "," + HERE + "]");
	}
}
