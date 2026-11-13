package fr.valcombe.mixin;

import fr.valcombe.mc.worlds.BuildAccess;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Poser suit la même règle que casser ({@link BuildAccess#mayModify}) : dans un monde de Valcombe, un objet utilisé sur
 * un bloc (un bloc à poser, un œuf d'apparition, une hache qui écorce, une pelle qui trace un chemin...) ne fait rien
 * pour qui n'y construit pas, exactement comme en mode aventure. Avant, seule la casse passait par BuildAccess : un
 * opérateur en survie au hub ou dans une partie pouvait encore poser un bloc là où il ne pouvait pas le casser.
 * Ouvrir une porte ou un coffre (le bloc lui-même répond au clic) reste permis : Minecraft le fait avant.
 */
@Mixin(ItemStack.class)
public abstract class PlaceGuardMixin {
	@Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
	private void valcombe$placeGuard(UseOnContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
		if (ctx.getPlayer() instanceof ServerPlayer sp && !BuildAccess.mayModify(sp, ctx.getLevel())) {
			BuildAccess.placeRefused(sp);
			cir.setReturnValue(InteractionResult.PASS);
		}
	}
}
