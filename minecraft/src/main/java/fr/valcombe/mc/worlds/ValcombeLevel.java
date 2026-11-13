package fr.valcombe.mc.worlds;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import xyz.nucleoid.fantasy.RuntimeLevel;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;

/**
 * Monde Fantasy de Valcombe. Il ne diffère du monde Fantasy ordinaire que par la météo : la pluie et l'orage
 * sont réglés pour tout le serveur, un même ciel pour tous les mondes ; un atelier n'en a jamais.
 */
final class ValcombeLevel extends RuntimeLevel {
	private final boolean weather;

	ValcombeLevel(MinecraftServer server, ResourceKey<Level> key, RuntimeLevelConfig config, Style style, boolean weather) {
		// Avant super() : le constructeur de ServerLevel demande déjà si ce monde a une météo.
		this.weather = weather;
		super(server, key, config, style);
	}

	/** Sans météo, le monde ne suit pas la pluie du serveur et n'en envoie jamais aux joueurs qui s'y trouvent. */
	@Override
	public boolean canHaveWeather() {
		return weather && super.canHaveWeather();
	}
}
