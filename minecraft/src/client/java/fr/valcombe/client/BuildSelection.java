package fr.valcombe.client;

import fr.valcombe.net.ValcombeNet;

import java.util.List;
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
 * Les plaques ne se chevauchent jamais à l'écran : à chaque image, leurs rectangles vus du joueur sont calculés, et
 * celles qui toucheraient une plaque plus importante (le cartouche d'abord, puis les coins, puis les côtés mesurés)
 * sont masquées : de loin, la boîte est petite et le cartouche dit déjà ses trois côtés (voir {@link Layout}). Le
 * texte d'une plaque est dessiné devant son contour noir : il n'est jamais mangé par lui.
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

	/** Une plaque à poser : où, sa bordure, ses lignes, leurs couleurs et leurs tailles. */
	private record Plate(Vec3 at, int border, String[] lines, int[] colors, float[] sizes) {
		static Plate label(String text, Vec3 at, int color, float base) {
			return new Plate(at, color, new String[] {text}, new int[] {color}, new float[] {scale(at, base)});
		}

		/** Largeur du texte, hauteur des lignes et marge, en blocs : une ligne de taille s occupe 10 points de s / 16 blocs. */
		double[] extent() {
			Minecraft mc = Minecraft.getInstance();
			double height = 0;
			double width = 0;
			for (int i = 0; i < lines.length; i++) {
				double px = sizes[i] / 16.0;
				height += 10 * px;
				width = Math.max(width, mc.font.width(lines[i]) * px);
			}
			double pad = 3 * sizes[0] / 16.0;
			// demi-largeur, haut (au-dessus de at), bas (au-dessous de at)
			return new double[] {width / 2 + pad, height / 2 + pad * 0.7, height / 2 + pad * 0.4};
		}
	}

	/**
	 * Placement des plaques à l'écran, sans Minecraft (vérifié par BuildSelectionTest) : le rectangle d'une plaque vu
	 * du joueur, et lesquelles montrer pour qu'aucune n'en touche une autre.
	 */
	public static final class Layout {
		/** Écart minimal entre deux plaques, en part de la distance (tangente de l'angle vu) : environ un quart de degré. */
		public static final double GAP = 0.005;

		private Layout() {
		}

		/**
		 * Le rectangle d'une plaque vue du joueur, en tangentes d'angle (x vers la droite, y vers le haut) : x0, y0, x1, y1.
		 * eye : l'œil ; right, up, forward : les axes de la caméra ; at : le centre de la plaque ; half, top, bottom :
		 * ses demi-largeur, haut et bas en blocs. Null si elle est derrière le joueur.
		 */
		public static double[] rect(double[] eye, double[] right, double[] up, double[] forward, double[] at, double half, double top,
				double bottom) {
			double vx = at[0] - eye[0];
			double vy = at[1] - eye[1];
			double vz = at[2] - eye[2];
			double z = vx * forward[0] + vy * forward[1] + vz * forward[2];
			if (z < 0.05) {
				return null;
			}
			double x = vx * right[0] + vy * right[1] + vz * right[2];
			double y = vx * up[0] + vy * up[1] + vz * up[2];
			return new double[] {(x - half) / z, (y - bottom) / z, (x + half) / z, (y + top) / z};
		}

		/** Deux rectangles (ou null) sont-ils à moins de gap l'un de l'autre ? */
		public static boolean near(double[] a, double[] b, double gap) {
			return a != null && b != null && a[0] < b[2] + gap && b[0] < a[2] + gap && a[1] < b[3] + gap && b[1] < a[3] + gap;
		}

		/**
		 * Lesquelles montrer, dans l'ordre d'importance (la première d'abord) : une plaque est montrée si elle ne touche
		 * aucune plaque déjà montrée. La première l'est toujours.
		 */
		public static boolean[] visible(List<double[]> rects, double gap) {
			boolean[] out = new boolean[rects.size()];
			for (int i = 0; i < rects.size(); i++) {
				boolean free = rects.get(i) != null || i == 0;
				for (int j = 0; j < i && free; j++) {
					free = !out[j] || !near(rects.get(i), rects.get(j), gap);
				}
				out[i] = free;
			}
			return out;
		}
	}

	/** Les rectangles et la visibilité des plaques de la dernière image (pour les tests en jeu) ; dans l'ordre d'importance. */
	private static volatile List<double[]> lastRects = List.of();
	private static volatile boolean[] lastVisible = new boolean[0];
	private static volatile List<String> lastTexts = List.of();

	/**
	 * Ce qui ne va pas dans les plaques de la dernière image (null si rien) : deux plaques montrées qui se touchent, le
	 * cartouche masqué, ou un texte plus large que sa plaque. Pour les tests en jeu.
	 */
	public static String layoutProblem() {
		List<double[]> rects = lastRects;
		boolean[] shown = lastVisible;
		if (rects.isEmpty()) {
			return "aucune plaque dessinée";
		}
		if (!shown[0]) {
			return "le cartouche est masqué";
		}
		for (int i = 0; i < rects.size(); i++) {
			for (int j = i + 1; j < rects.size(); j++) {
				if (shown[i] && shown[j] && Layout.near(rects.get(i), rects.get(j), 0)) {
					return "les plaques « " + lastTexts.get(i) + " » et « " + lastTexts.get(j) + " » se touchent";
				}
			}
		}
		return null;
	}

	/** Les textes des plaques montrées à la dernière image (pour les tests en jeu). */
	public static List<String> shownTexts() {
		List<String> out = new java.util.ArrayList<>();
		for (int i = 0; i < lastTexts.size() && i < lastVisible.length; i++) {
			if (lastVisible[i]) {
				out.add(lastTexts.get(i));
			}
		}
		return out;
	}

	private static double[] vec(Vec3 v) {
		return new double[] {v.x, v.y, v.z};
	}

	/**
	 * Pose les plaques : rectangles vus du joueur, masque celles qui en toucheraient une plus importante, puis dessine
	 * les autres, de la moins importante à la plus importante (la plus importante passe par-dessus).
	 */
	private static void place(List<Plate> plates) {
		var cam = Minecraft.getInstance().gameRenderer.mainCamera();
		double[] eye = vec(cam.position());
		double[] up = {cam.upVector().x(), cam.upVector().y(), cam.upVector().z()};
		double[] right = {-cam.leftVector().x(), -cam.leftVector().y(), -cam.leftVector().z()};
		double[] forward = {cam.forwardVector().x(), cam.forwardVector().y(), cam.forwardVector().z()};
		List<double[]> rects = new java.util.ArrayList<>();
		List<String> texts = new java.util.ArrayList<>();
		for (Plate p : plates) {
			double[] e = p.extent();
			rects.add(Layout.rect(eye, right, up, forward, vec(p.at()), e[0], e[1], e[2]));
			texts.add(String.join(" / ", p.lines()));
		}
		boolean[] shown = Layout.visible(rects, Layout.GAP);
		lastRects = List.copyOf(rects.stream().map(r -> r == null ? new double[] {0, 0, 0, 0} : r).toList());
		lastVisible = shown;
		lastTexts = List.copyOf(texts);
		for (int i = plates.size() - 1; i >= 0; i--) {
			if (shown[i]) {
				Plate p = plates.get(i);
				plate(p.at(), p.border(), p.lines(), p.colors(), p.sizes());
			}
		}
	}

	/**
	 * Une plaque toujours tournée vers le joueur, centrée sur at : des lignes de texte (chacune à sa taille), un fond
	 * sombre bordé de la couleur de la plaque, des lettres cernées de noir. Toujours visible, même derrière un mur.
	 */
	private static void plate(Vec3 at, int border, String[] lines, int[] colors, float[] sizes) {
		Minecraft mc = Minecraft.getInstance();
		var cam = mc.gameRenderer.mainCamera();
		Vec3 up = new Vec3(cam.upVector().x(), cam.upVector().y(), cam.upVector().z());
		Vec3 right = new Vec3(-cam.leftVector().x(), -cam.leftVector().y(), -cam.leftVector().z());
		Vec3 forward = new Vec3(cam.forwardVector().x(), cam.forwardVector().y(), cam.forwardVector().z());
		// Trois profondeurs : la plaque derrière, le contour noir, puis le texte devant (plus près du joueur). Les textes
		// sont rangés du plus loin au plus proche : à profondeur égale, le contour pouvait passer sur une lettre.
		Vec3 behind = forward.scale(0.02 * sizes[0]);
		Vec3 front = forward.scale(-0.006 * sizes[0]);
		double[] e = new Plate(at, border, lines, colors, sizes).extent();
		double half = e[0];
		Vec3 tl = at.add(right.scale(-half)).add(up.scale(e[1])).add(behind);
		Vec3 tr = at.add(right.scale(half)).add(up.scale(e[1])).add(behind);
		Vec3 br = at.add(right.scale(half)).add(up.scale(-e[2])).add(behind);
		Vec3 bl = at.add(right.scale(-half)).add(up.scale(-e[2])).add(behind);
		Gizmos.rect(tl, tr, br, bl, GizmoStyle.strokeAndFill(border, 2f, PLATE)).setAlwaysOnTop();
		double height = 0;
		for (float s : sizes) {
			height += 10 * s / 16.0;
		}
		double y = height / 2;
		for (int i = 0; i < lines.length; i++) {
			double px = sizes[i] / 16.0;
			Vec3 line = at.add(up.scale(y));
			// Le contour : la ligne en noir décalée d'un point dans les quatre directions, puis la ligne elle-même, devant.
			TextGizmo.Style ink = TextGizmo.Style.forColorAndCentered(INK).withScale(sizes[i]);
			for (int[] d : OUTLINE) {
				Gizmos.billboardText(lines[i], line.add(right.scale(d[0] * px)).add(up.scale(d[1] * px)), ink).setAlwaysOnTop();
			}
			Gizmos.billboardText(lines[i], line.add(front), TextGizmo.Style.forColorAndCentered(colors[i]).withScale(sizes[i]))
				.setAlwaysOnTop();
			y -= 10 * px;
		}
	}

	private static final int[][] OUTLINE = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	/** Le cube d'un coin (toujours dessiné) ; sa plaque, elle, passe par le placement. */
	private static Plate corner(int[] p, int color, String name) {
		if (p.length != 3) {
			return null;
		}
		BlockPos b = new BlockPos(p[0], p[1], p[2]);
		Gizmos.cuboid(new AABB(b).inflate(0.03), GizmoStyle.strokeAndFill(color, 3f, color & 0x00FFFFFF | 0x50000000));
		return Plate.label(name, Vec3.atCenterOf(b).add(0, 1.1, 0), color, 0.45f);
	}

	private static void draw(ValcombeNet.SelectionPayload s) {
		int[] b = s.box();
		// Par ordre d'importance : le cartouche, les deux coins, puis les trois côtés mesurés.
		List<Plate> plates = new java.util.ArrayList<>();
		List<Plate> edges = new java.util.ArrayList<>();
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
			edges.add(Plate.label("L " + (b[3] - b[0] + 1), new Vec3((x0 + x1) / 2, y0 + 0.4, z0 - 0.45), AXIS_L, 0.4f));
			edges.add(Plate.label("l " + (b[5] - b[2] + 1), new Vec3(x0 - 0.45, y0 + 0.4, (z0 + z1) / 2), AXIS_W, 0.4f));
			edges.add(Plate.label("h " + (b[4] - b[1] + 1), new Vec3(x0 - 0.45, (y0 + y1) / 2, z0 - 0.45), AXIS_H, 0.4f));
			// La plaque au-dessus du milieu de la boîte : dimensions, puis nombre de blocs (un peu plus petit). Son bas
			// reste à distance du dessus de la boîte quelle que soit sa taille.
			Vec3 top = new Vec3((x0 + x1) / 2, y1 + 0.6, (z0 + z1) / 2);
			float big = scale(top, 0.6f) * 1.15f;
			plates.add(new Plate(top.add(0, 10 * big / 16.0, 0), EDGE, new String[] {dimensions(b), blocks(s.shape(), s.volume())},
				new int[] {0xFFFFF1C9, 0xFFE8D5A0}, new float[] {big, big * 0.75f}));
		}
		Plate first = corner(s.first(), FIRST, "1");
		Plate second = corner(s.second(), SECOND, "2");
		if (first != null) {
			plates.add(first);
		}
		if (second != null) {
			plates.add(second);
		}
		plates.addAll(edges);
		if (!plates.isEmpty()) {
			place(plates);
		}
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
