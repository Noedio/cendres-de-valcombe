package fr.valcombe.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * L'inventaire d'un invité, mis de côté le temps de l'atelier.
 * <p>
 * Un invité qui n'est pas opérateur construit dans l'atelier en mode créatif : il peut y prendre n'importe quel objet
 * (perles de l'Ender, potions, élytres, œufs d'apparition…). Ces objets ne doivent pas le suivre au hub ni dans une
 * partie. À son entrée, son inventaire (et son coffre de l'Ender) est donc écrit sur le disque puis vidé ; à sa sortie,
 * ce qu'il a pris dans l'atelier disparaît et il retrouve exactement ce qu'il avait, avec son mode de jeu d'avant.
 * <p>
 * Fichiers : {@code config/valcombe/ateliers-inventaires/<uuid>.nbt}. Écrits avant de vider quoi que ce soit (un
 * fichier qui ne s'écrit pas laisse l'inventaire tel quel), ils survivent à un arrêt du serveur ou à une déconnexion
 * dans l'atelier : l'inventaire est rendu à la sortie suivante, même après un redémarrage. Les opérateurs ne sont pas
 * concernés (ils gardent leur inventaire partout, comme avant). Tout se fait sur le fil du serveur.
 */
final class WorkshopInventories {
	private static final int VERSION = 1;
	/** Un inventaire de joueur tient en bien moins ; au-delà, le fichier est tenu pour abîmé. */
	private static final long MAX_NBT = 64L * 1024 * 1024;

	private final Path dir;

	WorkshopInventories(Path dir) {
		this.dir = dir;
	}

	/** Le dossier de ce serveur : {@code config/valcombe/ateliers-inventaires}. */
	static Path dirIn(Path serverDir) {
		return serverDir.resolve("config").resolve("valcombe").resolve("ateliers-inventaires");
	}

	private Path file(UUID id) {
		return dir.resolve(id + ".nbt");
	}

	/** L'inventaire de ce joueur est-il mis de côté ? */
	boolean holds(UUID id) {
		return Files.exists(file(id));
	}

	private static ListTag save(Container c, RegistryOps<Tag> ops) {
		ListTag list = new ListTag();
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (s.isEmpty()) {
				continue;
			}
			Tag item = ItemStack.CODEC.encodeStart(ops, s).getOrThrow(IllegalStateException::new);
			CompoundTag e = new CompoundTag();
			e.putInt("Slot", i);
			e.put("Item", item);
			list.add(e);
		}
		return list;
	}

	private static void load(Container c, ListTag list, RegistryOps<Tag> ops) {
		c.clearContent();
		for (Tag t : list) {
			if (!(t instanceof CompoundTag e)) {
				continue;
			}
			int slot = e.getIntOr("Slot", -1);
			if (slot < 0 || slot >= c.getContainerSize()) {
				continue;
			}
			ItemStack s = ItemStack.CODEC.parse(ops, e.get("Item"))
				.resultOrPartial(err -> ValcombeMod.LOG.warn("[valcombe] Objet illisible dans un inventaire mis de côté : {}", err))
				.orElse(ItemStack.EMPTY);
			c.setItem(slot, s);
		}
	}

	/**
	 * Met de côté l'inventaire de ce joueur et son mode de jeu, puis vide son inventaire et son coffre de l'Ender. Rien
	 * si son inventaire est déjà de côté (retour dans l'atelier après un redémarrage). Vrai si c'est fait maintenant.
	 */
	boolean stash(ServerPlayer p, GameType mode) {
		Path f = file(p.getUUID());
		if (Files.exists(f)) {
			return false;
		}
		RegistryOps<Tag> ops = p.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		CompoundTag root = new CompoundTag();
		root.putInt("version", VERSION);
		root.putString("joueur", p.getScoreboardName());
		root.putString("mode", mode.getName());
		try {
			root.put("inventaire", save(p.getInventory(), ops));
			root.put("coffreEnder", save(p.getEnderChestInventory(), ops));
			Files.createDirectories(dir);
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			NbtIo.writeCompressed(root, tmp);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			// Rien n'est vidé : mieux vaut un invité qui garde ses objets qu'un invité qui les perd.
			ValcombeMod.LOG.error("[valcombe] Inventaire de {} non mis de côté à son entrée dans l'atelier", p.getScoreboardName(), e);
			return false;
		}
		p.getInventory().clearContent();
		p.getEnderChestInventory().clearContent();
		p.inventoryMenu.broadcastChanges();
		return true;
	}

	/**
	 * Rend à ce joueur l'inventaire mis de côté (ce qu'il a pris dans l'atelier disparaît) et efface le fichier. Le mode
	 * de jeu d'avant l'atelier, ou null s'il n'y avait rien de côté. Un fichier illisible est gardé à part
	 * ({@code .illisible}) et l'inventaire de l'atelier est vidé quand même.
	 */
	GameType restore(ServerPlayer p) {
		Path f = file(p.getUUID());
		if (!Files.exists(f)) {
			return null;
		}
		RegistryOps<Tag> ops = p.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		GameType mode = null;
		try {
			CompoundTag root = NbtIo.readCompressed(f, NbtAccounter.create(MAX_NBT));
			load(p.getInventory(), root.getListOrEmpty("inventaire"), ops);
			load(p.getEnderChestInventory(), root.getListOrEmpty("coffreEnder"), ops);
			mode = GameType.byName(root.getStringOr("mode", ""), null);
			Files.deleteIfExists(f);
		} catch (IOException | RuntimeException e) {
			ValcombeMod.LOG.error("[valcombe] Inventaire mis de côté de {} illisible : gardé à part, inventaire de l'atelier vidé",
				p.getScoreboardName(), e);
			p.getInventory().clearContent();
			p.getEnderChestInventory().clearContent();
			try {
				Files.move(f, f.resolveSibling(f.getFileName() + ".illisible"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException moved) {
				ValcombeMod.LOG.error("[valcombe] Fichier {} ni lu ni mis à part", f, moved);
			}
		}
		p.inventoryMenu.broadcastChanges();
		return mode;
	}
}
