package fr.valcombe.client.mixin;

import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.world.item.crafting.display.RecipeDisplay;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pas de bulle « Nouvelle recette débloquée » : à Valcombe on ne fabrique rien, et ces bulles
 * cachaient le haut des menus du jeu.
 */
@Mixin(RecipeToast.class)
public abstract class RecipeToastMixin {
	@Inject(method = "addOrUpdate", at = @At("HEAD"), cancellable = true)
	private static void valcombe$noRecipeToasts(ToastManager manager, RecipeDisplay display, CallbackInfo ci) {
		ci.cancel();
	}
}
