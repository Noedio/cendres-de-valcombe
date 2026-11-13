package fr.valcombe.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;

import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lettres lisses pour les polices haute résolution de Valcombe (valcombe:hd/...). Minecraft échantillonne les
 * lettres « au plus proche », ce qui convient à sa police en pixels mais crénèle une police TTF dès qu'elle est
 * mise à l'échelle de quelques pour cent (taille déclarée voisine de la taille voulue). Pour nos polices
 * seulement, on filtre linéairement ; les autres textes du jeu ne changent pas.
 */
@Mixin(GlyphRenderState.class)
public abstract class GlyphRenderStateMixin {
	@Shadow
	@Final
	private TextRenderable renderable;

	@Inject(method = "textureSetup", at = @At("HEAD"), cancellable = true)
	private void valcombe$smoothHdGlyphs(CallbackInfoReturnable<TextureSetup> cir) {
		var view = renderable.textureView();
		if (view != null && view.texture().getLabel().startsWith("valcombe:hd/")) {
			cir.setReturnValue(TextureSetup.singleTextureWithLightmap(view,
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)));
		}
	}
}
