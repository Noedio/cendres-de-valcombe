package fr.valcombe.mixin;

import fr.valcombe.mc.worlds.BuildAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Les seaux (eau, lave, neige poudreuse...) ne passent pas par ItemStack.useOn : ils demandent à Minecraft si le joueur
 * peut se servir d'un objet à cet endroit. Même règle que pour poser et casser ({@link BuildAccess#mayModify}).
 */
@Mixin(Player.class)
public abstract class BucketGuardMixin {
	@Inject(method = "mayUseItemAt", at = @At("HEAD"), cancellable = true)
	private void valcombe$bucketGuard(BlockPos pos, Direction direction, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof ServerPlayer sp && !BuildAccess.mayModify(sp, sp.level())) {
			BuildAccess.placeRefused(sp);
			cir.setReturnValue(false);
		}
	}
}
