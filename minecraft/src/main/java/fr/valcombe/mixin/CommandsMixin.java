package fr.valcombe.mixin;

import com.mojang.brigadier.ParseResults;

import fr.valcombe.mc.WorldEditGate;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Une commande de WorldEdit tapée hors des ateliers : WorldEdit n'y est pas permis (voir WorldEditGate), et Minecraft
 * répondrait « commande inconnue ». On répond avant lui, en clair : où WorldEdit sert, et comment y aller. Toutes les
 * commandes passent par ici (chat, écrans du mod, blocs de commande) ; seules celles d'un joueur sont regardées.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
	@Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
	private void valcombe$worldEditOutsideWorkshops(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
		CommandSourceStack source = parse.getContext().getSource();
		Component refusal = WorldEditGate.refuseCommand(source, command);
		if (refusal != null) {
			source.sendFailure(refusal);
			ci.cancel();
		}
	}
}
