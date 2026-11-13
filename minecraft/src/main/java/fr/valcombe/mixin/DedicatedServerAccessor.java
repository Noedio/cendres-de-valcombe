package fr.valcombe.mixin;

import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerSettings;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accès aux réglages enregistrés du serveur dédié (server.properties), pour que l'API d'administration
 * enregistre un réglage qui ne prend effet qu'au prochain démarrage (le port), sans que Minecraft ne
 * l'écrase à sa prochaine écriture du fichier.
 */
@Mixin(DedicatedServer.class)
public interface DedicatedServerAccessor {
	@Accessor("settings")
	DedicatedServerSettings valcombe$settings();
}
