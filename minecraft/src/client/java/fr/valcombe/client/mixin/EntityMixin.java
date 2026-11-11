package fr.valcombe.client.mixin;

import fr.valcombe.client.ClientState;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fantômes : le serveur rend les morts invisibles ; ici, ils restent visibles pour les joueurs
 * équipés du mod, et le jeu les dessine alors translucides.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "isInvisibleTo", at = @At("HEAD"), cancellable = true)
	private void valcombe$ghostsStayVisible(Player viewer, CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		if (self instanceof Player p && ClientState.isGhost(p.getScoreboardName())) {
			cir.setReturnValue(false);
		}
	}
}
