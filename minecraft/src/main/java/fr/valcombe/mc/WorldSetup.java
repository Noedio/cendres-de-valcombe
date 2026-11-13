package fr.valcombe.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Un vrai monde de partie : pas de créatures, pas de dégâts du décor, ni cycle jour/nuit
 * ni météo naturels (l'heure suit la phase de la partie), messages de mort et de progrès masqués.
 */
public final class WorldSetup {
	private WorldSetup() {
	}

	private static void off(GameRules rules, GameRule<Boolean> rule, MinecraftServer server) {
		rules.set(rule, false, server);
	}

	public static void apply(MinecraftServer server) {
		ServerLevel level = server.overworld();
		GameRules r = level.getGameRules();
		for (GameRule<Boolean> rule : java.util.List.of(
				GameRules.SPAWN_MOBS, GameRules.SPAWN_MONSTERS, GameRules.SPAWN_PATROLS, GameRules.SPAWN_PHANTOMS,
				GameRules.SPAWN_WANDERING_TRADERS, GameRules.SPAWN_WARDENS, GameRules.RAIDS,
				GameRules.ADVANCE_TIME, GameRules.ADVANCE_WEATHER,
				GameRules.FALL_DAMAGE, GameRules.FIRE_DAMAGE, GameRules.DROWNING_DAMAGE, GameRules.FREEZE_DAMAGE,
				GameRules.MOB_GRIEFING, GameRules.TNT_EXPLODES, GameRules.PVP,
				GameRules.SHOW_ADVANCEMENT_MESSAGES, GameRules.SHOW_DEATH_MESSAGES, GameRules.LOCATOR_BAR)) {
			off(r, rule, server);
		}
		r.set(GameRules.KEEP_INVENTORY, true, server);
		r.set(GameRules.IMMEDIATE_RESPAWN, true, server);
		r.set(GameRules.RANDOM_TICK_SPEED, 0, server);
		server.setDifficulty(Difficulty.PEACEFUL, true);
		run(server, "weather clear");
		day(server);
	}

	public static void day(MinecraftServer server) {
		run(server, "time set 6000");
	}

	public static void night(MinecraftServer server) {
		run(server, "time set 18000");
	}

	static void run(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
	}

	/** Lance une commande dans ce monde-ci (ses blocs et ses entités), sans rien afficher : une carte posée hors du monde principal. */
	static void run(ServerLevel level, String command) {
		MinecraftServer server = level.getServer();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(), command);
	}
}
