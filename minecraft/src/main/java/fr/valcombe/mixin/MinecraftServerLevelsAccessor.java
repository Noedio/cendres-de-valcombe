package fr.valcombe.mixin;

import java.util.Map;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accès à la table des mondes ouverts du serveur, pour que le gestionnaire de mondes de Valcombe retire
 * aussitôt du serveur un monde qu'il supprime ou remet à neuf (le serveur cesse de le faire tourner), sans
 * attendre que tous ses tronçons soient déchargés comme le fait Fantasy.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerLevelsAccessor {
	@Accessor("levels")
	Map<ResourceKey<Level>, ServerLevel> valcombe$levels();
}
