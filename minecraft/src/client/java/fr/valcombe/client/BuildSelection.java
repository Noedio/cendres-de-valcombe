package fr.valcombe.client;

import fr.valcombe.net.ValcombeNet;

import java.util.Locale;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * La sélection WorldEdit du joueur, dessinée en 3D dans les ateliers : la boîte et ses arêtes d'or, un quadrillage
 * léger sur ses faces, ses deux coins (rouge le premier, bleu le second, comme sur la baguette), ses trois côtés
 * mesurés le long des arêtes (L est-ouest, l nord-sud, h hauteur) et, au-dessus, son étiquette :
 * « L 12 × l 8 × h 5 » et le nombre de blocs. Chaque étiquette est une plaque tournée vers le joueur, fond sombre et
 * bord de sa couleur, lettres cernées de noir : lisible de près comme de loin (sa taille suit la distance, pour garder
 * la même taille à l'écran), sur l'herbe comme sur la pierre, et à travers les murs.
 * <p>
 * Le serveur envoie la sélection quand elle change ({@link ValcombeNet.SelectionPayload}) : rien n'est calculé ici à
 * chaque image, sinon la place des étiquettes.
 */
public final class BuildSelection {
	/** Or des arêtes, et sa teinte très légère pour les faces. */
	private static final int EDGE = 0xFFFFC857;
	private static final int FACE = 0x16FFC857;
	private static final int GRID = 0x38FFE7A8;
	/** Les deux coins, aux couleurs de la baguette de construction. */
	private static final int FIRST = 0xFFE5534B;
	private static final int SECOND = 0xFF4F8FE8;
	/** Les trois côtés mesurés. */
	private static final int AXIS_L = 0xFFFF8A6B;
	private static final int AXIS_H = 0xFF86E08A;
	private static final int AXIS_W = 0xFF7DB4FF;
	/** Au-delà de ce nombre de traits par face, le quadrillage s'espace (un trait tous les n blocs). */
	private static final int GRID_LINES = 32;

	private static volatile ValcombeNet.SelectionPayload current = ValcombeNet.SelectionPayload.NONE;

	private BuildSelection() {
	}

	public static void register() {
		// Une autre partie, un autre serveur : la sélection d'avant ne veut plus rien dire.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
			current = ValcombeNet.SelectionPayload.NONE);
		LevelRenderEvents.END_MAIN.register(context -> {
			ValcombeNet.SelectionPayload s = current;
			if (s.empty() || Minecraft.getInstance().level == null) {
				return;
			}
			try (var collect = Minecraft.getInstance().levelRenderer.collectPerFrameRenderThreadGizmos()) {
				draw(s);
			}
		});
	}

	/** Une nouvelle sélection reçue du serveur (vide : plus rien à montrer). */
	public static void received(ValcombeNet.SelectionPayload payload) {
		current = payload;
	}

	/** La sélection montrée en ce moment (pour les tests en jeu). */
	public static ValcombeNet.SelectionPayload current() {
		return current;
	}

	/** La sélection est-elle complète et dessinée ? */
	public static boolean shown() {
		ValcombeNet.SelectionPayload s = current;
		return !s.empty() && s.box().length == 6;
	}

	/** « 1 234 » : les milliers séparés d'une espace (que la police du jeu sait dessiner). */
	static String number(long n) {
		return String.format(Locale.ROOT, "%,d", n).replace(',', ' ');
	}

	/** L'étiquette principale : « L 12 × l 8 × h 5 ». */
	public static String dimensions(int[] box) {
		return "L " + (box[3] - box[0] + 1) + " × l " + (box[5] - box[2] + 1) + " × h " + (box[4] - box[1] + 1);
	}

	/** La seconde ligne : « 480 blocs », ou la forme et ses blocs pour une sélection qui n'est pas un pavé. */
	public static String blocks(String shape, long volume) {
		String n = number(volume) + (volume > 1 ? " blocs" : " bloc");
		return switch (shape) {
			case "cuboide" -> n;
			case "polygone" -> "polygone · " + n;
			case "ellipsoide" -> "ellipsoïde · " + n;
			case "sphere" -> "sphère · " + n;
			case "cylindre" -> "cylindre · " + n;
			case "enveloppe" -> "enveloppe · " + n;
			default -> n;
		};
	}

	/**
	 * Taille d'une étiquette selon sa distance au joueur : la même taille à l'écran à toute distance (factor par bloc
	 * d'éloignement), jamais plus petite que base de près. Un texte de taille s mesure 9 × s / 16 blocs de haut :
	 * factor = 0.085 donne des lettres d'environ 2,7° de haut, lisibles sur un petit écran comme sur un grand.
	 */
	static float scale(double distance, float base, double factor) {
		return (float) Math.max(base, Math.min(40.0, distance * factor));
	}

	private static float scale(Vec3 at, float base) {
		Vec3 eye = Minecraft.getInstance().gameRenderer.mainCamera().position();
		return scale(eye.distanceTo(at), base, 0.085);
	}

	/** Fond des plaques : sombre et opaque (un fond translucide serait dessiné après le texte, donc par-dessus). */
	private static final int PLATE = 0xFF1B1418;
	/** Contour des lettres. */
	private static final int INK = 0xFF0C0809;

	/**
	 * Une plaque toujours tournée vers le joueur, centrée sur at : des lignes de texte (chacune à sa taille), un fond
	 * sombre bordé de la couleur de la plaque, des lettres cernées de noir. Toujours visible, même derrière un mur.
	 */
	private static void plate(Vec3 at, int border, String[] lines, int[] colors, float[] sizes) {
		Minecraft mc = Minecraft.getInstance();
		var cam = mc.gameRenderer.mainCamera();
		Vec3 up = new Vec3(cam.upVector().x(), cam.upVector().y(), cam.upVector().z());
		Vec3 right = new Vec3(-cam.leftVector().x(), -cam.leftVector().y(), -cam.leftVector().z());
		Vec3 behind = new Vec3(cam.forwardVector().x(), cam.forwardVector().y(), cam.forwardVector().z()).scale(0.02 * sizes[0]);
		// Hauteur totale et plus grande largeur, en blocs : une ligne de taille s occupe 10 points de s / 16 blocs.
		double height = 0;
		double width = 0;
		for (int i = 0; i < lines.length; i++) {
			double px = sizes[i] / 16.0;
			height += 10 * px;
			width = Math.max(width, mc.font.width(lines[i]) * px);
		}
		double pad = 3 * sizes[0] / 16.0;
		double top = height / 2;
		double half = width / 2 + pad;
		Vec3 tl = at.add(right.scale(-half)).add(up.scale(top + pad * 0.7)).add(behind);
		Vec3 tr = at.add(right.scale(half)).add(up.scale(top + pad * 0.7)).add(behind);
		Vec3 br = at.add(right.scale(half)).add(up.scale(-top - pad * 0.4)).add(behind);
		Vec3 bl = at.add(right.scale(-half)).add(up.scale(-top - pad * 0.4)).add(behind);
		Gizmos.rect(tl, tr, br, bl, GizmoStyle.strokeAndFill(border, 2f, PLATE)).setAlwaysOnTop();
		double y = top;
		for (int i = 0; i < lines.length; i++) {
			double px = sizes[i] / 16.0;
			Vec3 line = at.add(up.scale(y));
			// Le contour : la ligne en noir décalée d'un point dans les quatre directions, puis la ligne elle-même.
			TextGizmo.Style ink = TextGizmo.Style.forColorAndCentered(INK).withScale(sizes[i]);
			for (int[] d : OUTLINE) {
				Gizmos.billboardText(lines[i], line.add(right.scale(d[0] * px)).add(up.scale(d[1] * px)), ink).setAlwaysOnTop();
			}
			Gizmos.billboardText(lines[i], line, TextGizmo.Style.forColorAndCentered(colors[i]).withScale(sizes[i])).setAlwaysOnTop();
			y -= 10 * px;
		}
	}

	private static final int[][] OUTLINE = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	private static void label(String text, Vec3 at, int color, float base) {
		plate(at, color, new String[] {text}, new int[] {color}, new float[] {scale(at, base)});
	}

	private static void corner(int[] p, int color, String name) {
		if (p.length != 3) {
			return;
		}
		BlockPos b = new BlockPos(p[0], p[1], p[2]);
		Gizmos.cuboid(new AABB(b).inflate(0.03), GizmoStyle.strokeAndFill(color, 3f, color & 0x00FFFFFF | 0x50000000));
		label(name, Vec3.atCenterOf(b).add(0, 1.1, 0), color, 0.45f);
	}

	private static void draw(ValcombeNet.SelectionPayload s) {
		int[] b = s.box();
		if (b.length == 6) {
			double x0 = b[0];
			double y0 = b[1];
			double z0 = b[2];
			double x1 = b[3] + 1;
			double y1 = b[4] + 1;
			double z1 = b[5] + 1;
			AABB box = new AABB(x0, y0, z0, x1, y1, z1);
			// Une forme autre que le pavé : sa boîte englobante, en pointillé plus discret.
			boolean cuboid = "cuboide".equals(s.shape());
			Gizmos.cuboid(box, GizmoStyle.strokeAndFill(cuboid ? EDGE : 0xC0FFC857, cuboid ? 2.5f : 1.5f, FACE));
			grid(x0, y0, z0, x1, y1, z1);
			// Les trois côtés mesurés, partant du coin le plus bas : longueur (est-ouest), largeur (nord-sud), hauteur.
			Gizmos.line(new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), AXIS_L, 4f);
			Gizmos.line(new Vec3(x0, y0, z0), new Vec3(x0, y0, z1), AXIS_W, 4f);
			Gizmos.line(new Vec3(x0, y0, z0), new Vec3(x0, y1, z0), AXIS_H, 4f);
			label("L " + (b[3] - b[0] + 1), new Vec3((x0 + x1) / 2, y0 + 0.4, z0 - 0.45), AXIS_L, 0.4f);
			label("l " + (b[5] - b[2] + 1), new Vec3(x0 - 0.45, y0 + 0.4, (z0 + z1) / 2), AXIS_W, 0.4f);
			label("h " + (b[4] - b[1] + 1), new Vec3(x0 - 0.45, (y0 + y1) / 2, z0 - 0.45), AXIS_H, 0.4f);
			// La plaque au-dessus du milieu de la boîte : dimensions, puis nombre de blocs (un peu plus petit). Son bas
			// reste à distance du dessus de la boîte quelle que soit sa taille.
			Vec3 top = new Vec3((x0 + x1) / 2, y1 + 0.6, (z0 + z1) / 2);
			float big = scale(top, 0.6f) * 1.15f;
			plate(top.add(0, 10 * big / 16.0, 0), EDGE, new String[] {dimensions(b), blocks(s.shape(), s.volume())},
				new int[] {0xFFFFF1C9, 0xFFE8D5A0}, new float[] {big, big * 0.75f});
		}
		corner(s.first(), FIRST, "1");
		corner(s.second(), SECOND, "2");
	}

	/** Un trait tous les n blocs sur les six faces (n grandit avec la boîte, pour ne jamais en tracer des milliers). */
	private static void grid(double x0, double y0, double z0, double x1, double y1, double z1) {
		double longest = Math.max(x1 - x0, Math.max(y1 - y0, z1 - z0));
		int step = (int) Math.max(1, Math.ceil(longest / GRID_LINES));
		for (double x = x0 + step; x < x1; x += step) {
			Gizmos.line(new Vec3(x, y0, z0), new Vec3(x, y1, z0), GRID, 1f);
			Gizmos.line(new Vec3(x, y0, z1), new Vec3(x, y1, z1), GRID, 1f);
			Gizmos.line(new Vec3(x, y0, z0), new Vec3(x, y0, z1), GRID, 1f);
			Gizmos.line(new Vec3(x, y1, z0), new Vec3(x, y1, z1), GRID, 1f);
		}
		for (double z = z0 + step; z < z1; z += step) {
			Gizmos.line(new Vec3(x0, y0, z), new Vec3(x0, y1, z), GRID, 1f);
			Gizmos.line(new Vec3(x1, y0, z), new Vec3(x1, y1, z), GRID, 1f);
			Gizmos.line(new Vec3(x0, y0, z), new Vec3(x1, y0, z), GRID, 1f);
			Gizmos.line(new Vec3(x0, y1, z), new Vec3(x1, y1, z), GRID, 1f);
		}
		for (double y = y0 + step; y < y1; y += step) {
			Gizmos.line(new Vec3(x0, y, z0), new Vec3(x1, y, z0), GRID, 1f);
			Gizmos.line(new Vec3(x0, y, z1), new Vec3(x1, y, z1), GRID, 1f);
			Gizmos.line(new Vec3(x0, y, z0), new Vec3(x0, y, z1), GRID, 1f);
			Gizmos.line(new Vec3(x1, y, z0), new Vec3(x1, y, z1), GRID, 1f);
		}
	}
}
