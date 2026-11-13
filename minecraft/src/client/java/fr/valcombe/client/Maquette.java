package fr.valcombe.client;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Une structure du catalogue telle que le client la dessine : ses blocs visibles (ceux qui touchent l'air),
 * chacun avec l'objet qui le montre à l'écran et sa couleur (pour le fantôme dans le monde).
 */
public final class Maquette {
	/** Au-delà, on ne garde que les blocs du dessus : l'écran resterait fluide. */
	private static final int MAX_SHOWN = 1600;
	private static final Map<String, Maquette> CACHE = new HashMap<>();

	/** Un bloc visible : position dans la structure, objet affiché, couleur (ARGB opaque). */
	public record Cell(int x, int y, int z, ItemStack item, int color) {
	}

	public final String id;
	public final String name;
	public final int width;
	public final int height;
	public final int length;
	public final List<Cell> cells;
	/** Trop grande pour être dessinée. */
	public final boolean tooBig;

	private Maquette(String id, String name, int width, int height, int length, List<Cell> cells, boolean tooBig) {
		this.id = id;
		this.name = name;
		this.width = width;
		this.height = height;
		this.length = length;
		this.cells = cells;
		this.tooBig = tooBig;
	}

	/** La maquette reçue pour cette structure, ou null (pas encore demandée, ou en route). */
	public static Maquette get(String id) {
		return CACHE.get(id);
	}

	/** Demandée au serveur : on ne la redemande pas tant qu'elle n'est pas arrivée. */
	private static final java.util.Set<String> ASKED = new java.util.HashSet<>();

	public static Maquette request(String id) {
		Maquette m = CACHE.get(id);
		if (m == null && ASKED.add(id)) {
			ClientState.send("vc structure maquette " + id);
		}
		return m;
	}

	/** Le catalogue a changé (structure renommée, réenregistrée) : les maquettes seront redemandées. */
	public static void forget() {
		CACHE.clear();
		ASKED.clear();
	}

	static void received(JsonObject o) {
		Maquette m = parse(o);
		if (m != null) {
			CACHE.put(m.id, m);
		}
	}

	static Maquette parse(JsonObject o) {
		String id = ClientState.str(o, "id");
		int w = ClientState.num(o, "w", 0);
		int h = ClientState.num(o, "h", 0);
		int l = ClientState.num(o, "l", 0);
		if (id == null || w <= 0 || h <= 0 || l <= 0) {
			return null;
		}
		if (ClientState.bool(o, "tropGrande")) {
			return new Maquette(id, ClientState.str(o, "nom"), w, h, l, List.of(), true);
		}
		var pal = o.getAsJsonArray("palette");
		List<ItemStack> items = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		items.add(ItemStack.EMPTY);
		colors.add(0);
		for (var e : pal) {
			Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(e.getAsString()));
			ItemStack shown;
			if (b == Blocks.WATER) {
				shown = new ItemStack(Items.BLUE_ICE);
			} else if (b == Blocks.LAVA) {
				shown = new ItemStack(Items.MAGMA_BLOCK);
			} else {
				shown = b.asItem() == Items.AIR ? ItemStack.EMPTY : new ItemStack(b.asItem());
			}
			items.add(shown);
			colors.add(0xFF000000 | b.defaultMapColor().col);
		}
		byte[] raw = Base64.getDecoder().decode(ClientState.str(o, "cases"));
		boolean wide = ClientState.bool(o, "large");
		int[] rank = new int[w * h * l];
		for (int i = 0; i < rank.length && (wide ? 2 * i + 1 : i) < raw.length; i++) {
			rank[i] = wide ? (raw[2 * i] & 255) << 8 | raw[2 * i + 1] & 255 : raw[i] & 255;
		}
		// Seuls les blocs qui touchent le vide se voient : les autres ne sont jamais dessinés.
		List<Cell> visible = new ArrayList<>();
		for (int y = 0; y < h; y++) {
			for (int z = 0; z < l; z++) {
				for (int x = 0; x < w; x++) {
					int r = rank[(y * l + z) * w + x];
					if (r == 0 || r >= items.size()) {
						continue;
					}
					if (exposed(rank, w, h, l, x, y, z)) {
						visible.add(new Cell(x, y, z, items.get(r), colors.get(r)));
					}
				}
			}
		}
		if (visible.size() > MAX_SHOWN) {
			visible.sort((a, b) -> Integer.compare(b.y(), a.y()));
			visible = new ArrayList<>(visible.subList(0, MAX_SHOWN));
		}
		return new Maquette(id, ClientState.str(o, "nom"), w, h, l, visible, false);
	}

	private static boolean exposed(int[] rank, int w, int h, int l, int x, int y, int z) {
		int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (int[] d : around) {
			int nx = x + d[0];
			int ny = y + d[1];
			int nz = z + d[2];
			if (nx < 0 || ny < 0 || nz < 0 || nx >= w || ny >= h || nz >= l || rank[(ny * l + nz) * w + nx] == 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Dessine la maquette en perspective isométrique dans le cadre (x, y, w, h), tournée de `quarters`
	 * quarts de tour : chaque bloc est l'icône de son objet, du fond vers l'avant.
	 */
	public void draw(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, int w, int h, int quarters) {
		if (cells.isEmpty()) {
			return;
		}
		int q = Math.floorMod(quarters, 4);
		int rw = q % 2 == 0 ? width : length;
		int rl = q % 2 == 0 ? length : width;
		// Taille d'un bloc à l'écran : la structure entière tient dans le cadre.
		double unitsW = (rw + rl) * 0.5 + 1;
		double unitsH = (rw + rl) * 0.25 + height * 0.6 + 1;
		float k = (float) Math.max(0.05, Math.min(1.5, Math.min(w / (unitsW * 16), h / (unitsH * 16))));
		float cell = 16 * k;
		List<int[]> order = new ArrayList<>(cells.size());
		for (int i = 0; i < cells.size(); i++) {
			Cell c = cells.get(i);
			int[] p = turn(c.x(), c.z(), q);
			order.add(new int[] {p[0], c.y(), p[1], i});
		}
		// Du fond vers l'avant, puis de bas en haut : les blocs proches recouvrent les lointains.
		order.sort((a, b) -> a[0] + a[2] != b[0] + b[2] ? Integer.compare(a[0] + a[2], b[0] + b[2]) : Integer.compare(a[1], b[1]));
		float ox = x + w / 2f - cell / 2f + (rl - rw) * cell * 0.25f;
		float oy = y + h - (rw + rl) * cell * 0.25f - cell;
		g.enableScissor(x, y, x + w, y + h);
		for (int[] o : order) {
			ItemStack item = cells.get(o[3]).item();
			if (item.isEmpty()) {
				continue;
			}
			float sx = ox + (o[0] - o[2]) * cell * 0.5f;
			float sy = oy + (o[0] + o[2]) * cell * 0.25f - o[1] * cell * 0.6f;
			g.pose().pushMatrix();
			g.pose().translate(sx, sy);
			g.pose().scale(k, k);
			g.fakeItem(item, 0, 0);
			g.pose().popMatrix();
		}
		g.disableScissor();
	}

	/** Position (x, z) après `q` quarts de tour, dans la boîte tournée. */
	private int[] turn(int x, int z, int q) {
		return switch (q) {
			case 1 -> new int[] {length - 1 - z, x};
			case 2 -> new int[] {width - 1 - x, length - 1 - z};
			case 3 -> new int[] {z, width - 1 - x};
			default -> new int[] {x, z};
		};
	}
}
