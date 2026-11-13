package fr.valcombe.mc;

import fr.valcombe.rules.GameException;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Les vignettes de repli (voir {@link Thumbnails}) : sans rendu des blocs, mais propres et fidèles à ce qu'on sait.
 * <ul>
 * <li>Une carte : l'aperçu vu de dessus enregistré avec elle (les blocs, vus du ciel) ; à défaut, son plan dessiné
 * d'après ses repères : la zone enregistrée, les trônes en cercle, les maisons reliées à leur trône, le centre et la
 * salle de convocation.</li>
 * <li>Une structure : un volume en perspective, à ses proportions (largeur, hauteur, profondeur), quadrillé d'un trait
 * par bloc (ou tous les quelques blocs pour une grande), teinté selon son rayon du catalogue.</li>
 * </ul>
 * Rien n'y est écrit (pas de police : un serveur sans polices installées dessine aussi ces images) ; la page met le
 * nom et les mesures à côté. Les dessins sont des fonctions pures, vérifiées par {@code ThumbnailFallbackTest}.
 */
public final class ThumbnailFallback implements Thumbnails {
	/** Taille des vignettes dessinées (16:10), en pixels : nettes sur un écran à haute densité. */
	static final int WIDTH = 480;
	static final int HEIGHT = 300;

	private static final Color ZONE = new Color(0x4a3238);
	private static final Color ZONE_FILL = new Color(0x26, 0x19, 0x1d);
	private static final Color SEAT = new Color(0xd9b45a);
	private static final Color HOUSE = new Color(0x6fb37a);
	private static final Color CENTER = new Color(0x5cc8e0);
	private static final Color ROOM = new Color(0xc58be6);
	private static final Color INK = new Color(0x140d10);

	private final MapStore maps;
	private final StructureStore structures;

	public ThumbnailFallback(MapStore maps, StructureStore structures) {
		this.maps = maps;
		this.structures = structures;
	}

	@Override
	public byte[] map(String id) {
		MapStore.MapDef d = maps.list().stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
		if (d == null) {
			return null;
		}
		byte[] png = maps.preview(id);
		return png != null ? png : plan(d.sieges, d.logis, d.convocation, d.rayon);
	}

	@Override
	public byte[] structure(String id) {
		StructureStore.Def d;
		try {
			d = structures.def(id);
		} catch (GameException e) {
			return null;
		}
		return volume(d.taille[0], d.taille[1], d.taille[2], d.categorie);
	}

	// ------------------------------------------------------------------
	// Plan d'une carte
	// ------------------------------------------------------------------

	/**
	 * Le plan d'une carte vue de dessus, le nord en haut : repères relatifs au centre de la place ([est, hauteur, sud]),
	 * rayon de la zone enregistrée. Fond transparent.
	 */
	static byte[] plan(List<int[]> seats, List<int[]> houses, int[] room, int radius) {
		BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = smooth(img);
		// Le plan est cadré sur les repères (la zone enregistrée peut être bien plus grande que la place) : on lit les
		// trônes et les maisons même sur une petite vignette. La zone, elle, reste dessinée, coupée par le cadre au besoin.
		int reach = 0;
		for (List<int[]> marks : List.of(seats, houses)) {
			for (int[] m : marks) {
				reach = Math.max(reach, Math.max(Math.abs(m[0]), Math.abs(m[2])));
			}
		}
		if (room != null) {
			reach = Math.max(reach, Math.max(Math.abs(room[0]), Math.abs(room[2])));
		}
		int zone = Math.max(6, radius);
		int r = Math.max(6, Math.min(zone, reach + 4));
		double cx = WIDTH / 2.0;
		double cy = HEIGHT / 2.0;
		double k = (HEIGHT - 40) / (2.0 * r + 1);
		double side = (2 * zone + 1) * k;
		g.setColor(ZONE_FILL);
		g.fill(new RoundRectangle2D.Double(cx - side / 2, cy - side / 2, side, side, 18, 18));
		g.setColor(ZONE);
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {7f, 6f}, 0f));
		g.draw(new RoundRectangle2D.Double(cx - side / 2, cy - side / 2, side, side, 18, 18));
		double mark = Math.max(6, Math.min(13, 1.3 * k));
		// Le trône et la maison du même numéro vont ensemble : un trait les relie.
		g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(0xd9, 0xb4, 0x5a, 130));
		for (int i = 0; i < Math.min(seats.size(), houses.size()); i++) {
			g.draw(new Line2D.Double(cx + seats.get(i)[0] * k, cy + seats.get(i)[2] * k, cx + houses.get(i)[0] * k, cy + houses.get(i)[2] * k));
		}
		g.setStroke(new BasicStroke(2f));
		for (int[] h : houses) {
			mark(g, new Rectangle2D.Double(cx + h[0] * k - mark, cy + h[2] * k - mark, 2 * mark, 2 * mark), HOUSE);
		}
		mark(g, new Ellipse2D.Double(cx - mark, cy - mark, 2 * mark, 2 * mark), CENTER);
		if (room != null) {
			double x = cx + room[0] * k;
			double y = cy + room[2] * k;
			Path2D.Double diamond = new Path2D.Double();
			diamond.moveTo(x, y - mark * 1.3);
			diamond.lineTo(x + mark * 1.3, y);
			diamond.lineTo(x, y + mark * 1.3);
			diamond.lineTo(x - mark * 1.3, y);
			diamond.closePath();
			mark(g, diamond, ROOM);
		}
		for (int[] s : seats) {
			mark(g, new Ellipse2D.Double(cx + s[0] * k - mark, cy + s[2] * k - mark, 2 * mark, 2 * mark), SEAT);
		}
		g.dispose();
		return png(img);
	}

	private static void mark(Graphics2D g, java.awt.Shape shape, Color fill) {
		g.setColor(fill);
		g.fill(shape);
		g.setColor(INK);
		g.draw(shape);
	}

	// ------------------------------------------------------------------
	// Volume d'une structure
	// ------------------------------------------------------------------

	/** Teinte d'un rayon du catalogue (maisons et salles, place, décor, vos structures). */
	static Color tint(String categorie) {
		return switch (categorie == null ? "" : categorie) {
			case "maisons" -> new Color(0x9a6a52);
			case "place" -> new Color(0xb9a77c);
			case "decor" -> new Color(0x6f9a5e);
			default -> new Color(0xc9a14a);
		};
	}

	/**
	 * Une structure de w × h × l blocs (est-ouest, hauteur, nord-sud), en perspective isométrique : le dessus et les
	 * deux faces du devant, ombrées, avec un trait par bloc. Elle remplit la vignette sans déborder, centrée, posée sur
	 * une ombre. Fond transparent.
	 */
	static byte[] volume(int w, int h, int l, String categorie) {
		w = Math.max(1, w);
		h = Math.max(1, h);
		l = Math.max(1, l);
		BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = smooth(img);
		double c = Math.cos(Math.PI / 6);
		double u = Math.min((WIDTH - 60) / ((w + l) * c), (HEIGHT - 50) / ((w + l) * 0.5 + h));
		// Projection : x vers la droite et le bas, z vers la gauche et le bas, y vers le haut.
		double[] box = {0, 0};
		double minX = -l * c * u;
		double maxX = w * c * u;
		double minY = -h * u;
		double maxY = (w + l) * 0.5 * u;
		box[0] = (WIDTH - (maxX - minX)) / 2 - minX;
		box[1] = (HEIGHT - (maxY - minY)) / 2 - minY;
		Projector p = (x, y, z) -> new double[] {box[0] + (x - z) * c * u, box[1] + (x + z) * 0.5 * u - y * u};
		// L'ombre au sol.
		double[] o = p.at(w / 2.0, 0, l / 2.0);
		g.setColor(new Color(0, 0, 0, 70));
		g.fill(new Ellipse2D.Double(o[0] - (w + l) * c * u * 0.55, o[1] - (w + l) * 0.25 * u * 0.6, (w + l) * c * u * 1.1,
			(w + l) * 0.5 * u * 0.6 + 8));
		Color base = tint(categorie);
		Path2D top = quad(p.at(0, h, 0), p.at(w, h, 0), p.at(w, h, l), p.at(0, h, l));
		Path2D east = quad(p.at(w, 0, 0), p.at(w, h, 0), p.at(w, h, l), p.at(w, 0, l));
		Path2D south = quad(p.at(0, 0, l), p.at(w, 0, l), p.at(w, h, l), p.at(0, h, l));
		g.setColor(shade(base, 1.18));
		g.fill(top);
		g.setColor(shade(base, 0.86));
		g.fill(east);
		g.setColor(shade(base, 0.66));
		g.fill(south);
		// Un trait par bloc (tous les n blocs pour une grande structure), plus discret que les arêtes.
		int step = Math.max(1, (int) Math.ceil(Math.max(w, Math.max(h, l)) / 14.0));
		g.setStroke(new BasicStroke(1f));
		g.setColor(new Color(0, 0, 0, 46));
		for (int x = step; x < w; x += step) {
			line(g, p.at(x, h, 0), p.at(x, h, l));
			line(g, p.at(x, 0, l), p.at(x, h, l));
		}
		for (int z = step; z < l; z += step) {
			line(g, p.at(0, h, z), p.at(w, h, z));
			line(g, p.at(w, 0, z), p.at(w, h, z));
		}
		for (int y = step; y < h; y += step) {
			line(g, p.at(w, y, 0), p.at(w, y, l));
			line(g, p.at(0, y, l), p.at(w, y, l));
		}
		// Les arêtes.
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(0x1a, 0x10, 0x12, 220));
		g.draw(top);
		g.draw(east);
		g.draw(south);
		g.dispose();
		return png(img);
	}

	private interface Projector {
		double[] at(double x, double y, double z);
	}

	private static Path2D quad(double[] a, double[] b, double[] c, double[] d) {
		Path2D.Double q = new Path2D.Double();
		q.moveTo(a[0], a[1]);
		q.lineTo(b[0], b[1]);
		q.lineTo(c[0], c[1]);
		q.lineTo(d[0], d[1]);
		q.closePath();
		return q;
	}

	private static void line(Graphics2D g, double[] a, double[] b) {
		g.draw(new Line2D.Double(a[0], a[1], b[0], b[1]));
	}

	private static Color shade(Color c, double f) {
		return new Color(clamp(c.getRed() * f), clamp(c.getGreen() * f), clamp(c.getBlue() * f));
	}

	private static int clamp(double v) {
		return (int) Math.max(0, Math.min(255, Math.round(v)));
	}

	// ------------------------------------------------------------------

	private static Graphics2D smooth(BufferedImage img) {
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		return g;
	}

	private static byte[] png(BufferedImage img) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(img, "png", out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new GameException("Vignette impossible : " + e.getMessage());
		}
	}
}
