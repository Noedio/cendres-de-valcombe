package fr.valcombe.mc;

import fr.valcombe.net.ValcombeNet;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Les vignettes de l'Atelier en jeu : une image PNG vue de dessus, faite par le serveur, envoyée au client qui en fait
 * une texture nette ({@code fr.valcombe.client.Vignettes}). Le client demande une vignette par sa clé
 * ({@link ValcombeNet.VignetteRequestPayload}) ; le serveur la fait (ou la reprend de sa mémoire) et la renvoie avec son
 * empreinte ({@link ValcombeNet.VignettePayload}). Quand ce qu'elle montre change, l'empreinte change : la vue de
 * l'Atelier la donne à côté de chaque carte, le client redemande alors l'image.
 * <p>
 * Les clés connues :
 * <ul>
 * <li>{@code carte:<id>} : une carte de la bibliothèque. Sa vue de dessus enregistrée avec elle (couleurs de carte des
 * blocs, relief), ou, pour une carte qui n'en a pas, celle qu'on tire de ses blocs ; ses repères par-dessus : trônes
 * dorés, maisons vertes, salle de convocation violette, centre de la place.</li>
 * <li>{@code village:<thème>:<trônes>:<taille>:<maisons>} : l'aperçu d'un village généré avant de le bâtir (plan de la
 * place, des trônes et des maisons, aux couleurs du thème).</li>
 * <li>{@code partie:<monde>} : la carte en place dans un monde de partie, vue de dessus dans le monde lui-même
 * (refaite au plus une fois par minute).</li>
 * </ul>
 * Réservé aux opérateurs, comme l'Atelier. Les images se font hors du fil du serveur quand elles le peuvent (cartes de
 * la bibliothèque, villages) ; celles d'un monde chargé se font sur son fil.
 */
public final class Vignettes {
	/**
	 * Côté d'une vignette, en pixels : une puissance de deux, pour que le client en tire ses réductions successives
	 * (nette dans la fiche d'une carte, lisse dans une petite case). Une carte plus petite est agrandie en blocs nets.
	 */
	public static final int SIZE = 512;
	/** Vignettes gardées en mémoire, au plus. */
	private static final int KEEP = 96;

	private static Vignettes current;

	/** Une vignette faite : son empreinte et son image. */
	private record Made(String stamp, byte[] png) {
	}

	private final MinecraftServer server;
	private final MapStore maps;
	private final ExecutorService pool;
	private final Map<String, Made> cache = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Made> eldest) {
			return size() > KEEP;
		}
	};
	/** Les vignettes en cours de fabrication, et qui les attend. */
	private final Map<String, Set<UUID>> waiting = new LinkedHashMap<>();

	private Vignettes(MinecraftServer server, MapStore maps) {
		this.server = server;
		this.maps = maps;
		this.pool = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "valcombe-vignettes");
			t.setDaemon(true);
			return t;
		});
	}

	/** Prépare les vignettes de ce serveur (au démarrage). */
	static void install(MinecraftServer server, MapStore maps) {
		stop();
		current = new Vignettes(server, maps);
	}

	/** À l'arrêt du serveur : les vignettes en cours sont abandonnées. */
	static void stop() {
		if (current != null) {
			current.pool.shutdownNow();
			current = null;
		}
	}

	// ------------------------------------------------------------------
	// Empreintes et demandes
	// ------------------------------------------------------------------

	/** L'empreinte de ce que montrerait la vignette de cette clé (vide si elle n'existe pas) ; sur le fil du serveur. */
	public static String stampOf(String key) {
		Vignettes v = current;
		if (v == null || key == null) {
			return "";
		}
		try {
			return v.stamp(key);
		} catch (RuntimeException e) {
			return "";
		}
	}

	private String stamp(String key) {
		int colon = key.indexOf(':');
		String kind = colon < 0 ? key : key.substring(0, colon);
		String arg = colon < 0 ? "" : key.substring(colon + 1);
		return switch (kind) {
			case "carte" -> maps.exists(arg) ? maps.stamp(arg) : "";
			case "village" -> village(arg) == null ? "" : "v1";
			case "partie" -> gameStamp(arg);
			default -> "";
		};
	}

	/** Le client demande cette vignette (sur le fil du serveur). */
	static void request(ServerPlayer p, String key) {
		Vignettes v = current;
		if (v == null || p == null || key == null || key.length() > 120 || !Atelier.isOperator(p)) {
			return;
		}
		v.handle(p, key);
	}

	private void handle(ServerPlayer p, String key) {
		String stamp = stampOf(key);
		if (stamp.isEmpty()) {
			send(p.getUUID(), key, "", new byte[0]);
			return;
		}
		Made made = cache.get(key);
		if (made != null && made.stamp().equals(stamp)) {
			send(p.getUUID(), key, made.stamp(), made.png());
			return;
		}
		Set<UUID> who = waiting.get(key);
		if (who != null) {
			who.add(p.getUUID());
			return;
		}
		who = new HashSet<>();
		who.add(p.getUUID());
		waiting.put(key, who);
		if (key.startsWith("partie:")) {
			// Le monde se lit sur son fil.
			finish(key, stamp, safely(() -> gameImage(key.substring("partie:".length()))));
			return;
		}
		pool.submit(() -> {
			byte[] png = safely(() -> key.startsWith("carte:") ? mapImage(key.substring("carte:".length()))
				: villageImage(key.substring("village:".length())));
			server.execute(() -> finish(key, stamp, png));
		});
	}

	private interface Maker {
		byte[] make() throws Exception;
	}

	private static byte[] safely(Maker m) {
		try {
			byte[] png = m.make();
			return png == null ? new byte[0] : png;
		} catch (Exception | LinkageError e) {
			ValcombeMod.LOG.warn("Vignette impossible : {}", e.toString());
			return new byte[0];
		}
	}

	private void finish(String key, String stamp, byte[] png) {
		if (png.length > 0) {
			cache.put(key, new Made(stamp, png));
		}
		Set<UUID> who = waiting.remove(key);
		if (who != null) {
			for (UUID id : who) {
				send(id, key, png.length > 0 ? stamp : "", png);
			}
		}
	}

	private void send(UUID id, String key, String stamp, byte[] png) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		if (p != null && ViewSync.hasClientMod(p)) {
			ServerPlayNetworking.send(p, new ValcombeNet.VignettePayload(key, stamp, png));
		}
	}

	// ------------------------------------------------------------------
	// Cartes de la bibliothèque
	// ------------------------------------------------------------------

	private byte[] mapImage(String id) throws IOException {
		MapStore.MapDef d = maps.list().stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
		if (d == null) {
			return null;
		}
		int side = 2 * d.rayon + 1;
		BufferedImage top = null;
		byte[] png = maps.preview(id);
		if (png != null) {
			top = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
		}
		if (top == null) {
			top = fromBlocks(maps.readClip(d));
		}
		return encode(compose(top, d.sieges, d.logis, d.convocation, side / 2));
	}

	/** La vue de dessus tirée des blocs d'une carte (pour une carte enregistrée sans son aperçu) : le bloc le plus haut de chaque colonne. */
	static BufferedImage fromBlocks(Clip clip) {
		BufferedImage img = new BufferedImage(clip.width, clip.length, BufferedImage.TYPE_INT_RGB);
		int[] north = new int[clip.width];
		for (int z = 0; z < clip.length; z++) {
			for (int x = 0; x < clip.width; x++) {
				int rgb = 0x5C7A3A; // le sol plat sous la carte : de l'herbe
				int top = -1;
				for (int y = clip.height - 1; y >= 0 && top < 0; y--) {
					int k = clip.cells[(y * clip.length + z) * clip.width + x];
					if (k == Clip.EMPTY) {
						continue;
					}
					BlockState s = clip.palette.get(k);
					if (s.isAir()) {
						continue;
					}
					int c = s.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
					if (c != 0) {
						rgb = c;
						top = y;
					}
				}
				double shade = z == 0 || top == north[x] ? 0.86 : top > north[x] ? 1.0 : 0.72;
				north[x] = top;
				img.setRGB(x, z, scale(rgb, shade));
			}
		}
		return img;
	}

	private static int scale(int rgb, double k) {
		int r = (int) Math.min(255, (rgb >> 16 & 255) * k);
		int g = (int) Math.min(255, (rgb >> 8 & 255) * k);
		int b = (int) Math.min(255, (rgb & 255) * k);
		return r << 16 | g << 8 | b;
	}

	// ------------------------------------------------------------------
	// Composition : la vue de dessus, agrandie nette ou réduite, et les repères par-dessus
	// ------------------------------------------------------------------

	/**
	 * La vignette : top (un pixel par bloc, le centre de la place au milieu, à radius blocs des bords) mise à la taille
	 * des vignettes, puis les repères ([est, hauteur, sud] depuis le centre) dessinés par-dessus.
	 */
	static BufferedImage compose(BufferedImage top, List<int[]> seats, List<int[]> houses, int[] room, int radius) {
		int n = Math.max(1, Math.max(top.getWidth(), top.getHeight()));
		BufferedImage base;
		if (n >= SIZE) {
			base = shrink(top, SIZE);
		} else {
			// Agrandie en blocs nets : chaque bloc garde sa couleur franche, la vignette reste lisible comme une carte.
			base = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = base.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			g.drawImage(top, 0, 0, SIZE, SIZE, null);
			g.dispose();
		}
		int out = base.getWidth();
		double cell = out / (double) n; // pixels par bloc
		double cx = out / 2.0;
		double cy = base.getHeight() / 2.0;
		Graphics2D g = base.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		// Un voile très léger : les repères ressortent sur n'importe quel décor.
		g.setColor(new Color(20, 12, 8, 46));
		g.fillRect(0, 0, out, base.getHeight());
		float mark = (float) Math.max(out / 34.0, Math.min(out / 18.0, cell * 3.2));
		float line = Math.max(1.2f, mark / 5.5f);
		Color edge = new Color(30, 18, 12, 230);
		// Les maisons : un petit toit vert.
		for (int[] h : houses) {
			double x = cx + (h[0] + 0.5) * cell;
			double y = cy + (h[2] + 0.5) * cell;
			double s = mark * 0.9;
			RoundRectangle2D r = new RoundRectangle2D.Double(x - s / 2, y - s / 2, s, s, s / 3, s / 3);
			g.setColor(new Color(111, 179, 122));
			g.fill(r);
			g.setStroke(new BasicStroke(line));
			g.setColor(edge);
			g.draw(r);
		}
		// La salle de convocation : un losange violet.
		if (room != null && room.length >= 3) {
			double x = cx + (room[0] + 0.5) * cell;
			double y = cy + (room[2] + 0.5) * cell;
			double s = mark * 0.8;
			Path2D d = new Path2D.Double();
			d.moveTo(x, y - s);
			d.lineTo(x + s, y);
			d.lineTo(x, y + s);
			d.lineTo(x - s, y);
			d.closePath();
			g.setColor(new Color(197, 139, 230));
			g.fill(d);
			g.setStroke(new BasicStroke(line));
			g.setColor(edge);
			g.draw(d);
		}
		// Le centre de la place : un anneau clair.
		double rc = mark * 0.45;
		g.setStroke(new BasicStroke(line * 1.4f));
		g.setColor(edge);
		g.draw(new Ellipse2D.Double(cx - rc, cy - rc, 2 * rc, 2 * rc));
		g.setStroke(new BasicStroke(line * 0.8f));
		g.setColor(new Color(255, 250, 235));
		g.draw(new Ellipse2D.Double(cx - rc, cy - rc, 2 * rc, 2 * rc));
		// Les trônes : des disques d'or.
		for (int[] s : seats) {
			double x = cx + (s[0] + 0.5) * cell;
			double y = cy + (s[2] + 0.5) * cell;
			double r = mark * 0.55;
			Ellipse2D e = new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r);
			g.setColor(new Color(242, 193, 78));
			g.fill(e);
			g.setStroke(new BasicStroke(line));
			g.setColor(edge);
			g.draw(e);
			g.setColor(new Color(255, 240, 190, 200));
			g.fill(new Ellipse2D.Double(x - r * 0.45, y - r * 0.55, r * 0.6, r * 0.5)); // reflet
		}
		g.dispose();
		return base;
	}

	/** Réduit une image à side × side pixels, chaque pixel la moyenne de ceux qu'il couvre. */
	static BufferedImage shrink(BufferedImage src, int side) {
		int w = src.getWidth();
		int h = src.getHeight();
		int[] rgb = src.getRGB(0, 0, w, h, null, 0, w);
		BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
		for (int cy = 0; cy < side; cy++) {
			int y0 = cy * h / side;
			int y1 = Math.max(y0 + 1, (cy + 1) * h / side);
			for (int cx = 0; cx < side; cx++) {
				int x0 = cx * w / side;
				int x1 = Math.max(x0 + 1, (cx + 1) * w / side);
				long r = 0;
				long g = 0;
				long b = 0;
				int n = 0;
				for (int y = y0; y < y1 && y < h; y++) {
					for (int x = x0; x < x1 && x < w; x++) {
						int c = rgb[y * w + x];
						r += c >> 16 & 255;
						g += c >> 8 & 255;
						b += c & 255;
						n++;
					}
				}
				out.setRGB(cx, cy, n == 0 ? 0xFF000000 : 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n));
			}
		}
		return out;
	}

	static byte[] encode(BufferedImage img) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	// ------------------------------------------------------------------
	// Village généré : l'aperçu avant de le bâtir
	// ------------------------------------------------------------------

	/** Les réglages d'un village lus dans la clé « thème:trônes:taille:maisons », ou null s'ils sont faux. */
	static Village village(String arg) {
		String[] p = arg.split(":");
		if (p.length != 4 || !Village.THEMES.contains(p[0])) {
			return null;
		}
		try {
			int seats = Integer.parseInt(p[1]);
			int size = Integer.parseInt(p[2]);
			if (seats < Village.MIN_SEATS || seats > Village.MAX_SEATS || size < 0 || size > 2) {
				return null;
			}
			return Village.generated(BlockPos.ZERO, p[0], seats, size, "oui".equals(p[3]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Les couleurs d'un thème : herbe, pavés de la place, toits, murs. */
	private static int[] palette(String theme) {
		return switch (theme) {
			case "monastere" -> new int[] {0x7C9A4E, 0xC9B999, 0xA04A32, 0xE4D7B8};
			case "port" -> new int[] {0x6E8F55, 0xA9A49A, 0x3E5F7A, 0xC8B79A};
			default -> new int[] {0x4F6E3A, 0x8C8A86, 0x3C3646, 0x9A8F84};
		};
	}

	/** Le plan d'un village généré, peint : herbe, place pavée, chemins, toits aux couleurs du thème, trônes. */
	static byte[] villageImage(String arg) throws IOException {
		Village v = village(arg);
		if (v == null) {
			return null;
		}
		List<int[]> seats = v.relSeats();
		List<int[]> houses = v.relHouses();
		double seatR = 0;
		for (int[] s : seats) {
			seatR = Math.max(seatR, Math.hypot(s[0], s[2]));
		}
		double houseR = 0;
		for (int[] h : houses) {
			houseR = Math.max(houseR, Math.hypot(h[0], h[2]));
		}
		double plaza = seatR + 6;
		double reach = Math.max(plaza + 8, houseR + 13) + 6;
		int radius = (int) Math.ceil(reach);
		int n = 2 * radius + 1;
		int[] pal = palette(v.theme());
		BufferedImage top = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
		java.util.Random noise = new java.util.Random(arg.hashCode());
		for (int z = 0; z < n; z++) {
			for (int x = 0; x < n; x++) {
				double d = Math.hypot(x - radius, z - radius);
				int c = pal[0];
				double k = 0.9 + noise.nextDouble() * 0.16;
				if (d <= plaza) {
					c = pal[1];
					k = 0.88 + noise.nextDouble() * 0.14;
				} else if (d <= plaza + 1.2) {
					c = 0x6A665E; // le rebord de la place
				}
				top.setRGB(x, z, scale(c, k));
			}
		}
		// Les chemins de la place aux maisons.
		for (int[] h : houses) {
			double a = Math.atan2(h[2], h[0]);
			for (double r = plaza; r < Math.hypot(h[0], h[2]) - 2; r += 0.5) {
				int x = (int) Math.round(radius + r * Math.cos(a));
				int z = (int) Math.round(radius + r * Math.sin(a));
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						int px = x + dx;
						int pz = z + dz;
						if (px >= 0 && pz >= 0 && px < n && pz < n) {
							top.setRGB(px, pz, scale(0x9C8460, 0.92 + noise.nextDouble() * 0.12));
						}
					}
				}
			}
		}
		// Les maisons : murs et toit, tournés vers la place.
		for (int[] h : houses) {
			int hx = radius + h[0];
			int hz = radius + h[2];
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					int px = hx + dx;
					int pz = hz + dz;
					if (px >= 0 && pz >= 0 && px < n && pz < n) {
						boolean wall = Math.abs(dx) == 3 || Math.abs(dz) == 3;
						top.setRGB(px, pz, wall ? pal[3] : scale(pal[2], 0.85 + (dx + 3) * 0.05));
					}
				}
			}
		}
		return encode(compose(top, seats, List.of(), null, radius));
	}

	// ------------------------------------------------------------------
	// La carte en place dans un monde de partie
	// ------------------------------------------------------------------

	private static String gameStamp(String worldId) {
		fr.valcombe.mc.games.GameRoom room = fr.valcombe.mc.games.Games.byWorld(worldId);
		if (room == null) {
			return "";
		}
		McHost host = room.host();
		Village v = host.village();
		if (v == null || host.busy()) {
			return "";
		}
		// Les blocs d'un monde peuvent changer à tout moment : la vignette se refait au plus une fois par minute.
		long minute = System.currentTimeMillis() / 60_000L;
		return Integer.toHexString(java.util.Objects.hash(v.libraryId(), v.seatCount(), v.houseCount(), v.modified(), v.center(), minute));
	}

	private static byte[] gameImage(String worldId) throws IOException {
		fr.valcombe.mc.games.GameRoom room = fr.valcombe.mc.games.Games.byWorld(worldId);
		if (room == null || room.host().village() == null || room.host().busy()) {
			return null;
		}
		McHost host = room.host();
		Village v = host.village();
		int r = Math.min(128, Math.max(8, v.extent())); // lu sur le fil du serveur : la place et ses abords suffisent
		byte[] png = McHost.topView(host.level(), v.center(), r);
		BufferedImage top = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
		List<int[]> seats = new ArrayList<>(v.relSeats());
		return encode(compose(top, seats, v.relHouses(), v.relConvocation(), r));
	}
}
