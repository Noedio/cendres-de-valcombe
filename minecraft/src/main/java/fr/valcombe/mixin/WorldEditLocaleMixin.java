package fr.valcombe.mixin;

import java.util.Locale;

import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * WorldEdit parle français dans Valcombe. Il traduit ses messages (« Première position définie en… », « 576 blocs
 * modifiés ») sur le serveur, dans la langue du client du joueur : un client réglé sur une autre langue que le français
 * recevait ses réponses en anglais au milieu d'un jeu tout en français. Ici, tout joueur dont le client n'est pas en
 * français reçoit celles de WorldEdit en français (ses traductions françaises sont dans son jar). Un client en français
 * (France, Canada...) garde sa variante.
 * <p>
 * Configuration de mixins à part, facultative (valcombe.worldedit.mixins.json) : sans WorldEdit, elle est sautée.
 */
@Mixin(targets = "com.sk89q.worldedit.coremc.internal.CoreMcPlayer", remap = false)
public abstract class WorldEditLocaleMixin {
	@Shadow
	@Final
	private ServerPlayer player;

	@Inject(method = "getLocale", at = @At("HEAD"), cancellable = true, remap = false)
	private void valcombe$french(CallbackInfoReturnable<Locale> cir) {
		String lang = player == null ? null : player.clientInformation().language();
		if (lang == null || !lang.toLowerCase(Locale.ROOT).startsWith("fr")) {
			cir.setReturnValue(com.sk89q.worldedit.util.formatting.component.TextUtils.getLocaleByMinecraftTag("fr_fr"));
		}
	}
}
