package fr.valcombe.client;

import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

/**
 * Fantôme de la structure en main : ses blocs visibles, en cubes translucides de leur couleur, là où elle
 * sera posée. Le serveur envoie la structure (dans son orientation) puis, deux fois par seconde, le coin visé.
 */
public final class Ghost {
	/** Au-delà, le fantôme ne garde que la silhouette (le cadre) : des milliers de cubes ralentiraient l'image. */
	private static final int MAX_CUBES = 900;
	private static Maquette shape;
	private static int[] corner;
	/** Cubes du fantôme (relatifs au coin) et leur style : préparés à la réception, pas à chaque image. */
	private static AABB[] boxes = new AABB[0];
	private static GizmoStyle[] styles = new GizmoStyle[0];

	private Ghost() {
	}

	public static void register() {
		// Les dessins de chaque image sont recueillis par le rendu du monde : on les lui confie à la fin d'une image,
		// il les affiche à la suivante (et ainsi de suite tant que le fantôme est là).
		LevelRenderEvents.END_MAIN.register(context -> {
			if (!active()) {
				return;
			}
			try (var collect = Minecraft.getInstance().levelRenderer.collectPerFrameRenderThreadGizmos()) {
				draw();
			}
		});
	}

	static void received(JsonObject o) {
		String kind = ClientState.str(o, "kind");
		if ("fantome".equals(kind)) {
			shape = ClientState.bool(o, "off") ? null : Maquette.parse(o);
			if (shape == null) {
				corner = null;
				boxes = new AABB[0];
				styles = new GizmoStyle[0];
			} else if (shape.cells.size() <= MAX_CUBES) {
				int n = shape.cells.size();
				AABB[] b = new AABB[n];
				GizmoStyle[] s = new GizmoStyle[n];
				for (int i = 0; i < n; i++) {
					Maquette.Cell c = shape.cells.get(i);
					// Un rien plus petit que le bloc, pour ne pas se confondre avec le décor ; couleur du bloc, à demi transparente.
					b[i] = new AABB(c.x() + 0.04, c.y() + 0.04, c.z() + 0.04, c.x() + 0.96, c.y() + 0.96, c.z() + 0.96);
					s[i] = GizmoStyle.fill(0x70000000 | c.color() & 0xFFFFFF);
				}
				boxes = b;
				styles = s;
			} else {
				boxes = new AABB[0];
				styles = new GizmoStyle[0];
			}
		} else if ("fantomeA".equals(kind)) {
			corner = ClientState.bool(o, "hors") ? null
				: new int[] {ClientState.num(o, "x", 0), ClientState.num(o, "y", 0), ClientState.num(o, "z", 0)};
		}
	}

	/** Un fantôme est prêt à être dessiné (structure en main et coin visé connus). */
	public static boolean active() {
		return shape != null && corner != null && holdingTool();
	}

	/** Le joueur tient l'outil « Carte : structure » (le nom de l'objet suffit : le serveur le donne ainsi). */
	private static boolean holdingTool() {
		var p = Minecraft.getInstance().player;
		if (p == null) {
			return false;
		}
		var name = p.getMainHandItem().get(DataComponents.CUSTOM_NAME);
		return name != null && name.getString().equals("Carte : structure");
	}

	private static void draw() {
		Maquette m = shape;
		int[] c = corner;
		if (m == null || c == null || !holdingTool()) {
			return;
		}
		AABB box = new AABB(c[0], c[1], c[2], c[0] + m.width, c[1] + m.height, c[2] + m.length);
		Gizmos.cuboid(box, GizmoStyle.stroke(0xFFFFD27A, 2f));
		AABB[] b = boxes;
		GizmoStyle[] s = styles;
		for (int i = 0; i < b.length && i < s.length; i++) {
			Gizmos.cuboid(b[i].move(c[0], c[1], c[2]), s[i]);
		}
	}
}
