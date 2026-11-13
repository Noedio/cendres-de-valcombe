package fr.valcombe.client.mixin;

import fr.valcombe.client.ClientState;

import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pas de bulle de progrès Minecraft (« Progrès réalisé ! ») sur un serveur de Valcombe : on n'y joue pas à Minecraft,
 * et ces bulles cachaient le haut des écrans et des captures (dans un atelier, toucher un feu suffisait). Ailleurs
 * (un autre serveur, sans Valcombe), elles restent.
 */
@Mixin(ToastManager.class)
public abstract class AdvancementToastMixin {
	@Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
	private void valcombe$noAdvancementToasts(Toast toast, CallbackInfo ci) {
		if (toast instanceof AdvancementToast && ClientState.onValcombeServer()) {
			ci.cancel();
		}
	}
}
