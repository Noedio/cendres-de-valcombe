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
		if (m.cells.size() > MAX_CUBES) {
			return;
		}
		for (Maquette.Cell cell : m.cells) {
			double x = c[0] + cell.x();
			double y = c[1] + cell.y();
			double z = c[2] + cell.z();
			// Couleur du bloc, à demi transparente, et un rien plus petite que le bloc pour ne pas se confondre avec le décor.
			int argb = 0x70000000 | cell.color() & 0xFFFFFF;
			Gizmos.cuboid(new AABB(x + 0.04, y + 0.04, z + 0.04, x + 0.96, y + 0.96, z + 0.96), GizmoStyle.fill(argb));
		}
	}
}
