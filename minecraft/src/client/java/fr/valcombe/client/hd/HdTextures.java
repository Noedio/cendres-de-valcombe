package fr.valcombe.client.hd;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Textures dessinées par la boîte à outils, avec une chaîne de mipmaps : une image fine (128 px, ou 8 pixels par
 * unité) réduite à 20 px reste lisse au lieu de crénelée. Chaque image du jeu est enregistrée une seule fois sous
 * une clé à part (valcombe:hd_cache/...), si bien que les écrans qui la dessinent à l'ancienne ne changent pas.
 * Le gestionnaire de textures de Minecraft les recharge avec les packs de ressources.
 */
public final class HdTextures {
	/** Comment agrandir une image plus petite que sa place à l'écran. */
	public enum Magnify {
		/** Lissé : pour les textures dessinées pour la boîte à outils. */
		SMOOTH,
		/** Pixels nets : pour le pixel art (pictogrammes de 32 px agrandis). */
		PIXELS
	}

	private static final Map<String, HdTexture> CACHE = new HashMap<>();

	private HdTextures() {
	}

	/** La texture d'une ressource du jeu, prête à dessiner (chargée au premier appel). */
	public static HdTexture get(Identifier resource, Magnify magnify, boolean repeat) {
		String key = resource + "|" + magnify + "|" + repeat;
		HdTexture t = CACHE.get(key);
		if (t == null) {
			String path = "hd_cache/" + resource.getNamespace() + "/" + resource.getPath()
				+ (magnify == Magnify.PIXELS ? "_pixels" : "") + (repeat ? "_mosaique" : "");
			Identifier id = Identifier.fromNamespaceAndPath("valcombe", path);
			t = new HdTexture(id, resource, magnify, repeat);
			Minecraft.getInstance().getTextureManager().registerAndLoad(id, t);
			CACHE.put(key, t);
		}
		return t;
	}

	/** Une texture de la boîte à outils. */
	public static HdTexture get(HdSprites.Sprite sprite) {
		return get(Identifier.fromNamespaceAndPath("valcombe", sprite.path()), Magnify.SMOOTH, sprite.repeat());
	}

	/** Une image du jeu, mipmappée, avec ses dimensions. */
	public static final class HdTexture extends ReloadableTexture {
		/** Nombre de niveaux de réduction au plus (une image de 256 px descend jusqu'à 8 px). */
		private static final int MAX_LEVELS = 6;
		private final Identifier key;
		private final Magnify magnify;
		private final boolean repeat;
		private int width = 16;
		private int height = 16;

		HdTexture(Identifier key, Identifier resource, Magnify magnify, boolean repeat) {
			super(resource);
			this.key = key;
			this.magnify = magnify;
			this.repeat = repeat;
		}

		/** Clé sous laquelle la texture est enregistrée (celle qu'on passe à blit). */
		public Identifier key() {
			return key;
		}

		public int width() {
			return width;
		}

		public int height() {
			return height;
		}

		@Override
		public TextureContents loadContents(ResourceManager resourceManager) throws IOException {
			return TextureContents.load(resourceManager, resourceId());
		}

		@Override
		protected void setSampler(TextureContents contents) {
			AddressMode address = repeat ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE;
			FilterMode mag = magnify == Magnify.PIXELS ? FilterMode.NEAREST : FilterMode.LINEAR;
			this.sampler = RenderSystem.getSamplerCache().getSampler(address, address, FilterMode.LINEAR, mag, true);
		}

		@Override
		protected void doLoad(NativeImage image) {
			GpuDevice device = RenderSystem.getDevice();
			close();
			NativeImage[] mips = mipChain(image);
			width = image.getWidth();
			height = image.getHeight();
			this.texture = device.createTexture(key::toString, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
				GpuFormat.RGBA8_UNORM, width, height, 1, mips.length);
			this.textureView = device.createTextureView(this.texture);
			for (int level = 0; level < mips.length; level++) {
				device.createCommandEncoder().writeToTexture(this.texture, mips[level], level, 0, 0, 0);
			}
			for (int level = 1; level < mips.length; level++) {
				mips[level].close();
			}
		}

		/**
		 * Réductions successives par moitié, en moyennant les couleurs pondérées par l'opacité (pas de liseré
		 * sombre autour des formes, l'opacité des ombres douces est respectée). S'arrête à une dimension impaire.
		 */
		private static NativeImage[] mipChain(NativeImage base) {
			java.util.List<NativeImage> out = new java.util.ArrayList<>();
			out.add(base);
			NativeImage last = base;
			while (out.size() < MAX_LEVELS && last.getWidth() % 2 == 0 && last.getHeight() % 2 == 0
					&& last.getWidth() >= 2 && last.getHeight() >= 2) {
				int w = last.getWidth() / 2;
				int h = last.getHeight() / 2;
				NativeImage next = new NativeImage(w, h, false);
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						next.setPixel(x, y, average(last.getPixel(2 * x, 2 * y), last.getPixel(2 * x + 1, 2 * y),
							last.getPixel(2 * x, 2 * y + 1), last.getPixel(2 * x + 1, 2 * y + 1)));
					}
				}
				out.add(next);
				last = next;
			}
			return out.toArray(new NativeImage[0]);
		}

		/** Moyenne de quatre couleurs ARGB, pondérée par l'opacité. */
		static int average(int a, int b, int c, int d) {
			int[] px = {a, b, c, d};
			long sa = 0;
			long sr = 0;
			long sg = 0;
			long sb = 0;
			long pr = 0;
			long pg = 0;
			long pb = 0;
			for (int p : px) {
				int al = p >>> 24;
				int r = (p >> 16) & 0xFF;
				int g = (p >> 8) & 0xFF;
				int bl = p & 0xFF;
				sa += al;
				sr += (long) r * al;
				sg += (long) g * al;
				sb += (long) bl * al;
				pr += r;
				pg += g;
				pb += bl;
			}
			int alpha = (int) ((sa + 2) / 4);
			int r;
			int g;
			int bl;
			if (sa > 0) {
				r = (int) ((sr + sa / 2) / sa);
				g = (int) ((sg + sa / 2) / sa);
				bl = (int) ((sb + sa / 2) / sa);
			} else {
				// Tout transparent : on garde la couleur moyenne (déjà étendue par le script), pour le filtrage.
				r = (int) (pr / 4);
				g = (int) (pg / 4);
				bl = (int) (pb / 4);
			}
			return alpha << 24 | r << 16 | g << 8 | bl;
		}
	}
}
