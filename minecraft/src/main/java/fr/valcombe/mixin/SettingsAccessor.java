package fr.valcombe.mixin;

import java.util.Properties;

import net.minecraft.server.dedicated.Settings;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Les valeurs brutes de server.properties telles que Minecraft les garde en mémoire et les réécrit.
 * Une valeur changée ici est écrite dans le fichier à la prochaine sauvegarde des réglages, et reprise
 * par chaque nouvelle copie des réglages (Minecraft en refait une à chaque changement).
 */
@Mixin(Settings.class)
public interface SettingsAccessor {
	@Accessor("properties")
	Properties valcombe$properties();
}
