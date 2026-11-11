package fr.valcombe.mixin;

import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Display;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Assis sur un trône (monté sur l'affichage du siège), un clic droit sur un bloc ne sert à rien :
 * on l'ignore avant que Minecraft ne réponde par un message hors de propos (« hauteur maximale de
 * construction »). Le bloc visé est noté dans le journal du serveur pour comprendre le cas.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class UseItemOnMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = "handleUseItemOn", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;ackBlockChangesUpTo(I)V", shift = At.Shift.AFTER),
		cancellable = true)
	private void valcombe$seatedClick(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
		if (player.getVehicle() instanceof Display.ItemDisplay) {
			var pos = packet.hitResult().getBlockPos();
			if (pos.getY() > player.level().getMaxY() || pos.getY() < player.level().getMinY()) {
				org.slf4j.LoggerFactory.getLogger("valcombe").info("Clic assis hors limites ignoré : {} vise {} (joueur en {})",
					player.getScoreboardName(), pos, player.position());
			}
			ci.cancel();
		}
	}
}
