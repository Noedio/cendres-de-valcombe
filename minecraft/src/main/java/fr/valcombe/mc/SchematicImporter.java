package fr.valcombe.mc;

import fr.valcombe.rules.GameException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lecture de constructions faites ailleurs : schématiques WorldEdit / FAWE (.schem, versions 2 et 3),
 * Litematica (.litematic) et structures Minecraft (.nbt). Elles deviennent une carte (McHost.importSchematic)
 * ou une structure du catalogue (StructureStore.importFile).
 */
final class SchematicImporter {
	/** Taille maximale acceptée (blocs). */
	static final int MAX_SIDE = 320;
	static final int MAX_HEIGHT = 128;

	/** Construction lue : dimensions, palette et un index de palette par bloc (x, puis z, puis y). */
	record Shape(int width, int height, int length, List<BlockState> palette, int[] blocks) {
		BlockState at(int x, int y, int z) {
			return palette.get(blocks[(y * length + z) * width + x]);
		}
	}

	private SchematicImporter() {
	}

	/**
	 * Lit une construction sans rien poser. Elle sera posée par petits lots (voir Clip.plan), et jamais
	 * hors de ses dimensions : dans un .nbt, chaque bloc porte sa position, qui est contrôlée ici ; les
	 * entités du fichier sont ignorées et les données des blocs dangereux retirées.
	 */
	static Clip read(String fileName, byte[] data, HolderLookup.RegistryLookup<Block> blocks) {
		String name = fileName.toLowerCase(Locale.ROOT);
		if (!name.endsWith(".nbt") && !name.endsWith(".schem") && !name.endsWith(".litematic")) {
			throw new GameException("Format non reconnu : fichiers .schem (WorldEdit), .litematic (Litematica) ou .nbt (structure Minecraft).");
		}
		CompoundTag root;
		try {
			root = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(256L * 1024 * 1024));
		} catch (IOException | RuntimeException e) {
			throw new GameException("Fichier illisible : ce n'est pas une construction Minecraft compressée.");
		}
		try {
			if (name.endsWith(".nbt")) {
				return Clip.read(root, blocks, MAX_SIDE, MAX_HEIGHT, MAX_SIDE);
			}
			return Clip.of(name.endsWith(".litematic") ? litematic(root, blocks) : sponge(root, blocks));
		} catch (GameException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new GameException("Fichier illisible : son contenu n'est pas celui d'une construction (" + e.getMessage() + ").");
		}
	}

	private static void check(int w, int h, int l) {
		if (w <= 0 || h <= 0 || l <= 0) {
			throw new GameException("Construction vide.");
		}
		if (w > MAX_SIDE || l > MAX_SIDE || h > MAX_HEIGHT) {
			throw new GameException("Construction trop grande (" + w + " × " + h + " × " + l + ") : " + MAX_SIDE + " × "
				+ MAX_HEIGHT + " × " + MAX_SIDE + " au plus.");
		}
	}

	// ------------------------------------------------------------------
	// WorldEdit / FAWE (.schem) : Sponge Schematic v2 (racine) ou v3 (racine « Schematic », blocs dans « Blocks »)
	// ------------------------------------------------------------------
	private static Shape sponge(CompoundTag root, HolderLookup.RegistryLookup<Block> lookup) {
		CompoundTag s = root.contains("Schematic") ? root.getCompoundOrEmpty("Schematic") : root;
		int w = s.getShortOr("Width", (short) 0) & 0xFFFF;
		int h = s.getShortOr("Height", (short) 0) & 0xFFFF;
		int l = s.getShortOr("Length", (short) 0) & 0xFFFF;
		check(w, h, l);
		CompoundTag paletteTag;
		byte[] data;
		if (s.contains("Blocks")) {
			CompoundTag b = s.getCompoundOrEmpty("Blocks");
			paletteTag = b.getCompoundOrEmpty("Palette");
			data = b.getByteArray("Data").orElse(new byte[0]);
		} else {
			paletteTag = s.getCompoundOrEmpty("Palette");
			data = s.getByteArray("BlockData").orElse(new byte[0]);
		}
		if (paletteTag.keySet().isEmpty()) {
			throw new GameException("Schématique sans palette de blocs : format non reconnu (WorldEdit .schem attendu).");
		}
		int max = 0;
		for (String k : paletteTag.keySet()) {
			max = Math.max(max, paletteTag.getIntOr(k, 0));
		}
		List<BlockState> palette = new ArrayList<>();
		for (int i = 0; i <= max; i++) {
			palette.add(Blocks.AIR.defaultBlockState());
		}
		for (String k : paletteTag.keySet()) {
			palette.set(paletteTag.getIntOr(k, 0), parse(lookup, k));
		}
		// Index en entiers variables (varint), dans l'ordre x, puis z, puis y.
		int[] blocks = new int[w * h * l];
		int i = 0;
		int pos = 0;
		while (i < blocks.length && pos < data.length) {
			int value = 0;
			int shift = 0;
			byte b;
			do {
				b = data[pos++];
				value |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0 && pos < data.length);
			blocks[i++] = Math.min(value, palette.size() - 1);
		}
		return new Shape(w, h, l, palette, blocks);
	}

	private static BlockState parse(HolderLookup.RegistryLookup<Block> lookup, String id) {
		try {
			return BlockStateParser.parseForBlock(lookup, id, false).blockState();
		} catch (Exception e) {
			return Blocks.AIR.defaultBlockState(); // bloc inconnu dans cette version : remplacé par de l'air
		}
	}

	// ------------------------------------------------------------------
	// Litematica (.litematic) : régions, palette et états compactés dans des entiers longs
	// ------------------------------------------------------------------
	private static Shape litematic(CompoundTag root, HolderLookup.RegistryLookup<Block> lookup) {
		CompoundTag regions = root.getCompoundOrEmpty("Regions");
		if (regions.keySet().isEmpty()) {
			throw new GameException("Fichier Litematica sans région.");
		}
		// On prend la première région (la plupart des fichiers n'en ont qu'une).
		CompoundTag r = regions.getCompoundOrEmpty(regions.keySet().iterator().next());
		CompoundTag size = r.getCompoundOrEmpty("Size");
		int w = Math.abs(size.getIntOr("x", 0));
		int h = Math.abs(size.getIntOr("y", 0));
		int l = Math.abs(size.getIntOr("z", 0));
		check(w, h, l);
		ListTag pal = r.getListOrEmpty("BlockStatePalette");
		List<BlockState> palette = new ArrayList<>();
		for (int i = 0; i < pal.size(); i++) {
			// Nom et propriétés → « minecraft:oak_stairs[facing=north,half=top] », lu comme dans une commande
			CompoundTag e = pal.getCompoundOrEmpty(i);
			StringBuilder id = new StringBuilder(e.getStringOr("Name", "minecraft:air"));
			CompoundTag props = e.getCompoundOrEmpty("Properties");
			if (!props.keySet().isEmpty()) {
				id.append('[');
				for (String k : props.keySet()) {
					id.append(id.charAt(id.length() - 1) == '[' ? "" : ",").append(k).append('=').append(props.getStringOr(k, ""));
				}
				id.append(']');
			}
			palette.add(parse(lookup, id.toString()));
		}
		if (palette.isEmpty()) {
			palette.add(Blocks.AIR.defaultBlockState());
		}
		long[] states = r.getLongArray("BlockStates").orElse(new long[0]);
		int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
		long mask = (1L << bits) - 1;
		int[] blocks = new int[w * h * l];
		for (int i = 0; i < blocks.length; i++) {
			long start = (long) i * bits;
			int word = (int) (start >>> 6);
			int offset = (int) (start & 63);
			if (word >= states.length) {
				break;
			}
			long value = states[word] >>> offset;
			if (offset + bits > 64 && word + 1 < states.length) {
				value |= states[word + 1] << (64 - offset);
			}
			int idx = (int) (value & mask);
			blocks[i] = idx < palette.size() ? idx : 0;
		}
		return new Shape(w, h, l, palette, blocks);
	}
}
