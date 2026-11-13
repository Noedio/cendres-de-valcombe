package fr.valcombe.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;

import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.net.ValcombeNet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * Les vignettes de l'Atelier en jeu, vues de dessus faites par le serveur (voir {@code fr.valcombe.mc.Vignettes}) :
 * chaque clé (« carte:port », « village:gothique:16:1:oui », « partie:… ») est demandée une fois par empreinte, puis
 * gardée en texture nette, avec sa chaîne de réductions (une vignette de 512 pixels dessinée sur 60 reste lisse).
 * Quand l'empreinte annoncée par la vue change (la carte a changé), l'ancienne image reste affichée jusqu'à
 * l'arrivée de la nouvelle.
 *
 * <pre>
 * Vignettes.draw(hd, "carte:" + id, stamp, x, y, w, h);   // pixels réels, placeholder tant qu'elle n'est pas là
 * </pre>
 */
public final class Vignettes {
	/** Délai avant de redemander une vignette restée sans réponse, en millisecondes. */
	private static final long RETRY = 8000;
	/**
	 * Vignettes gardées en textures, au plus (chacune pèse un peu moins de 1,5 Mo sur la carte graphique avec ses
	 * réductions) : au-delà, les moins récemment dessinées sont rendues, et redemandées si on les revoit. Sans cette
	 * limite, régler un village dans « Nouvelle carte » (thème, trônes, taille, maisons) en garderait des centaines.
	 */
	private static final int MAX_IMAGES = 80;

	/** Une vignette reçue, prête à dessiner. */
	public record Image(Identifier id, int width, int height, String stamp) {
	}

	private static final class Entry {
		Image image;
		/** Empreinte demandée au serveur, et quand. */
		String asked;
		long askedAt;
		/** Le serveur n'a pas pu la faire (pour cette empreinte). */
		String failed;
		/** Dernière fois qu'on l'a voulue (dessinée), pour rendre d'abord les plus anciennes. */
		long usedAt;
	}

	private static final Map<String, Entry> ENTRIES = new HashMap<>();
	private static int counter;

	private Vignettes() {
	}

	/**
	 * La vignette de cette clé (peut-être d'une empreinte plus ancienne), ou null ; la demande au serveur si elle
	 * manque ou si stamp en annonce une plus récente. stamp vide ou null : on prend ce qu'on a, sans redemander.
	 */
	public static Image get(String key, String stamp) {
		if (key == null) {
			return null;
		}
		Entry e = ENTRIES.computeIfAbsent(key, k -> new Entry());
		boolean upToDate = e.image != null && (stamp == null || stamp.isEmpty() || stamp.equals(e.image.stamp()));
		boolean refused = stamp != null && stamp.equals(e.failed);
		long now = Util.getMillis();
		e.usedAt = now;
		if (!upToDate && !refused && (e.asked == null || !e.asked.equals(stamp) || now - e.askedAt > RETRY)) {
			e.asked = stamp == null ? "" : stamp;
			e.askedAt = now;
			if (ClientPlayNetworking.canSend(ValcombeNet.VignetteRequestPayload.TYPE)) {
				ClientPlayNetworking.send(new ValcombeNet.VignetteRequestPayload(key));
			}
		}
		return e.image;
	}

	/** Le serveur n'a pas pu faire cette vignette (pour l'empreinte demandée). */
	public static boolean failed(String key, String stamp) {
		Entry e = ENTRIES.get(key);
		return e != null && e.image == null && e.failed != null && (stamp == null || stamp.isEmpty() || e.failed.equals(stamp));
	}

	/** La vignette de cette clé a-t-elle été reçue (avec cette empreinte, si elle est donnée) ? Pour les tests en jeu. */
	public static boolean ready(String key, String stamp) {
		Entry e = ENTRIES.get(key);
		return e != null && e.image != null && (stamp == null || stamp.isEmpty() || stamp.equals(e.image.stamp()));
	}

	/** Une vignette arrive du serveur (sur le fil du rendu). */
	static void received(ValcombeNet.VignettePayload p) {
		Entry e = ENTRIES.computeIfAbsent(p.key(), k -> new Entry());
		if (p.png().length == 0) {
			e.failed = e.asked == null ? "" : e.asked;
			return;
		}
		NativeImage img;
		try {
			img = NativeImage.read(p.png());
		} catch (java.io.IOException | RuntimeException ex) {
			e.failed = p.stamp();
			return;
		}
		Identifier id = Identifier.fromNamespaceAndPath("valcombe", "vignettes/" + slug(p.key()) + "_" + (++counter));
		Texture t = new Texture(id, img);
		Minecraft.getInstance().getTextureManager().register(id, t);
		if (e.image != null) {
			Minecraft.getInstance().getTextureManager().release(e.image.id());
		}
		e.image = new Image(id, t.width, t.height, p.stamp());
		e.failed = null;
		e.usedAt = Util.getMillis();
		trim(e);
	}

	/** Au-delà de MAX_IMAGES textures, rend les moins récemment voulues (jamais keep, qui vient d'arriver). */
	private static void trim(Entry keep) {
		List<Map.Entry<String, Entry>> held = new ArrayList<>();
		for (Map.Entry<String, Entry> me : ENTRIES.entrySet()) {
			if (me.getValue().image != null) {
				held.add(me);
			}
		}
		if (held.size() <= MAX_IMAGES) {
			return;
		}
		held.sort((a, b) -> Long.compare(a.getValue().usedAt, b.getValue().usedAt));
		for (int i = 0, extra = held.size() - MAX_IMAGES; i < held.size() && extra > 0; i++) {
			Entry old = held.get(i).getValue();
			if (old == keep) {
				continue;
			}
			Minecraft.getInstance().getTextureManager().release(old.image.id());
			ENTRIES.remove(held.get(i).getKey());
			extra--;
		}
	}

	/** Oublie toutes les vignettes (déconnexion : un autre serveur a d'autres cartes). */
	static void forget() {
		for (Entry e : ENTRIES.values()) {
			if (e.image != null) {
				Minecraft.getInstance().getTextureManager().release(e.image.id());
			}
		}
		ENTRIES.clear();
	}

	private static String slug(String key) {
		StringBuilder out = new StringBuilder();
		for (char c : key.toLowerCase(Locale.ROOT).toCharArray()) {
			out.append(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_' ? c : '_');
		}
		return out.length() > 60 ? out.substring(0, 60) : out.toString();
	}

	// ------------------------------------------------------------------
	// Dessin
	// ------------------------------------------------------------------

	/**
	 * Dessine la vignette de cette clé dans ce rectangle (pixels réels), centrée et sans déformation ; tant qu'elle
	 * n'est pas arrivée, un fond qui miroite (ou « Pas d'aperçu » si le serveur n'a pas pu la faire). Renvoie vrai si
	 * l'image est dessinée.
	 */
	public static boolean draw(Hd hd, String key, String stamp, int x, int y, int w, int h) {
		Image img = get(key, stamp);
		if (img == null) {
			placeholder(hd, x, y, w, h, failed(key, stamp));
			return false;
		}
		float k = Math.min(w / (float) img.width(), h / (float) img.height());
		int dw = Math.max(1, Math.round(img.width() * k));
		int dh = Math.max(1, Math.round(img.height() * k));
		int dx = x + (w - dw) / 2;
		int dy = y + (h - dh) / 2;
		hd.graphics().blit(RenderPipelines.GUI_TEXTURED, img.id(), dx, dy, 0, 0, dw, dh, img.width(), img.height(), img.width(),
			img.height(), -1);
		return true;
	}

	/** Le fond d'une vignette attendue : un parchemin sombre où passe un reflet, ou la mention « Pas d'aperçu ». */
	public static void placeholder(Hd hd, int x, int y, int w, int h, boolean failed) {
		hd.fill(x, y, x + w, y + h, 0xFF2A2024);
		if (failed) {
			if (h > hd.lineHeight(HdStyle.PETIT)) {
				String t = hd.ellipsize(HdStyle.PETIT, "Pas d'aperçu", w - hd.u(4));
				hd.textCentered(HdStyle.PETIT, t, x + w / 2, y + (h - hd.lineHeight(HdStyle.PETIT)) / 2, Hd.MUTED);
			}
			return;
		}
		// Un reflet en biais qui traverse la case toutes les 1,6 seconde.
		double t = (Util.getMillis() % 1600) / 1600.0;
		int band = Math.max(hd.u(6), w / 4);
		int bx = x - band + (int) Math.round((w + 2 * band) * t);
		for (int i = 0; i < band; i++) {
			double a = 1 - Math.abs(i - band / 2.0) / (band / 2.0);
			int alpha = (int) Math.round(a * 0x22);
			int lx = Math.max(x, bx + i);
			if (lx < x + w && alpha > 0) {
				hd.fill(lx, y, lx + 1, y + h, alpha << 24 | 0xF2E6C9);
			}
		}
	}

	// ------------------------------------------------------------------
	// La texture
	// ------------------------------------------------------------------

	/** Une image reçue, posée sur la carte graphique avec ses réductions successives (filtrage lisse). */
	private static final class Texture extends AbstractTexture {
		/** Nombre de niveaux de réduction au plus (512 pixels descendent jusqu'à 8). */
		private static final int MAX_LEVELS = 7;
		final int width;
		final int height;

		Texture(Identifier id, NativeImage image) {
			GpuDevice device = RenderSystem.getDevice();
			NativeImage[] mips = mipChain(image);
			width = image.getWidth();
			height = image.getHeight();
			this.texture = device.createTexture(id::toString, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
				GpuFormat.RGBA8_UNORM, width, height, 1, mips.length);
			this.textureView = device.createTextureView(this.texture);
			for (int level = 0; level < mips.length; level++) {
				device.createCommandEncoder().writeToTexture(this.texture, mips[level], level, 0, 0, 0);
			}
			for (NativeImage m : mips) {
				m.close();
			}
			this.sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
				FilterMode.LINEAR, FilterMode.LINEAR, true);
		}

		/** Réductions par moitié (moyenne de quatre pixels), jusqu'à une dimension impaire ou MAX_LEVELS niveaux. */
		private static NativeImage[] mipChain(NativeImage base) {
			List<NativeImage> out = new ArrayList<>();
			out.add(base);
			NativeImage last = base;
			while (out.size() < MAX_LEVELS && last.getWidth() % 2 == 0 && last.getHeight() % 2 == 0 && last.getWidth() >= 2) {
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

		private static int average(int a, int b, int c, int d) {
			int out = 0;
			for (int shift = 0; shift < 32; shift += 8) {
				int sum = (a >>> shift & 255) + (b >>> shift & 255) + (c >>> shift & 255) + (d >>> shift & 255);
				out |= (sum + 2) / 4 << shift;
			}
			return out;
		}
	}
}
