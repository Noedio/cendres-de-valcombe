package fr.valcombe.mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * Menu en forme de coffre, affiché par le serveur (aucun mod côté joueur).
 * Rien ne peut en être retiré ; chaque case peut porter une action.
 */
public final class Gui {
	/** Identifie le type de menu, pour le mettre à jour sur place au lieu de le rouvrir. */
	public final String key;
	final Component title;
	final int rows;
	final Map<Integer, ItemStack> icons = new HashMap<>();
	final Map<Integer, Consumer<ServerPlayer>> actions = new HashMap<>();

	public Gui(String key, String title, int rows) {
		this.key = key;
		this.title = Component.literal(title);
		this.rows = rows;
	}

	public Gui set(int slot, ItemStack icon, Consumer<ServerPlayer> action) {
		icons.put(slot, icon);
		if (action != null) {
			actions.put(slot, action);
		}
		return this;
	}

	public Gui set(int slot, ItemStack icon) {
		return set(slot, icon, null);
	}

	private void fill(SimpleContainer c) {
		ItemStack filler = icon(Items.STAINED_GLASS_PANE.black(), " ");
		for (int i = 0; i < rows * 9; i++) {
			c.setItem(i, icons.containsKey(i) ? icons.get(i).copy() : filler.copy());
		}
	}

	private static MenuType<ChestMenu> type(int rows) {
		return switch (rows) {
			case 1 -> MenuType.GENERIC_9x1;
			case 2 -> MenuType.GENERIC_9x2;
			case 3 -> MenuType.GENERIC_9x3;
			case 4 -> MenuType.GENERIC_9x4;
			case 5 -> MenuType.GENERIC_9x5;
			default -> MenuType.GENERIC_9x6;
		};
	}

	/** Ouvre le menu, ou met à jour sur place un menu du même type déjà ouvert. */
	public void open(ServerPlayer player, McHost host) {
		if (player.containerMenu instanceof Menu current && current.gui.key.equals(key)
			&& current.gui.rows == rows) {
			current.gui = this;
			fill(current.container);
			current.broadcastChanges();
			return;
		}
		SimpleContainer container = new SimpleContainer(rows * 9);
		fill(container);
		player.openMenu(new SimpleMenuProvider(
			(id, inv, p) -> new Menu(type(rows), id, inv, container, this, host), title));
	}

	/** Le menu côté serveur : intercepte tous les clics. */
	public static final class Menu extends ChestMenu {
		Gui gui;
		final SimpleContainer container;
		private final McHost host;

		Menu(MenuType<ChestMenu> type, int id, Inventory inv, SimpleContainer container, Gui gui, McHost host) {
			super(type, id, inv, container, gui.rows);
			this.gui = gui;
			this.container = container;
			this.host = host;
		}

		public Gui gui() {
			return gui;
		}

		@Override
		public void clicked(int slot, int button, ContainerInput input, Player player) {
			setCarried(ItemStack.EMPTY);
			if (player instanceof ServerPlayer sp && slot >= 0 && slot < gui.rows * 9) {
				Consumer<ServerPlayer> action = gui.actions.get(slot);
				if (action != null) {
					// Au tick suivant : une action peut ouvrir un autre menu.
					host.schedule(1, () -> action.accept(sp));
				}
			}
			sendAllDataToRemote();
			player.inventoryMenu.sendAllDataToRemote();
		}

		@Override
		public ItemStack quickMoveStack(Player player, int slot) {
			return ItemStack.EMPTY;
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}
	}

	// ------------------------------------------------------------------
	// Fabrication des icônes
	// ------------------------------------------------------------------
	public static Component plain(String text, ChatFormatting color) {
		return Component.literal(text).withStyle(s -> s.withItalic(false).withColor(color));
	}

	/** Coupe un texte en lignes d'environ 38 caractères pour les infobulles. */
	public static List<String> wrap(String text) {
		List<String> lines = new ArrayList<>();
		for (String paragraph : text.split("\n")) {
			StringBuilder line = new StringBuilder();
			for (String word : paragraph.split(" ")) {
				if (line.length() > 0 && line.length() + word.length() + 1 > 38) {
					lines.add(line.toString());
					line = new StringBuilder();
				}
				if (line.length() > 0) {
					line.append(' ');
				}
				line.append(word);
			}
			lines.add(line.toString());
		}
		return lines;
	}

	public static ItemStack icon(Item item, String name, String... lore) {
		return icon(item, name, ChatFormatting.GOLD, List.of(lore));
	}

	public static ItemStack icon(Item item, String name, ChatFormatting color, List<String> lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, plain(name, color));
		if (!lore.isEmpty()) {
			List<Component> lines = new ArrayList<>();
			for (String l : lore) {
				for (String w : wrap(l)) {
					lines.add(plain(w, ChatFormatting.GRAY));
				}
			}
			stack.set(DataComponents.LORE, new ItemLore(lines));
		}
		return stack;
	}

	public static ItemStack glow(ItemStack stack) {
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return stack;
	}

	/** Tête du joueur (avec son apparence s'il est connecté). */
	public static ItemStack head(ServerPlayer owner, String name, String title, ChatFormatting color, List<String> lore) {
		ItemStack stack = icon(owner != null ? Items.PLAYER_HEAD : Items.SKELETON_SKULL, title, color, lore);
		if (owner != null) {
			stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(owner.getGameProfile()));
		}
		return stack;
	}
}
