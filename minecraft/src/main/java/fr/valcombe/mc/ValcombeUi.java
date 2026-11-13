package fr.valcombe.mc;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleType;
import fr.valcombe.rules.Team;
import fr.valcombe.session.Prompt;
import fr.valcombe.session.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Tous les menus du jeu. */
public final class ValcombeUi {
	public static final String MENU_ITEM_NAME = "Menu de Valcombe";

	private final Session session;
	private final McHost host;

	public ValcombeUi(Session session, McHost host) {
		this.session = session;
		this.host = host;
	}

	public McHost host() {
		return host;
	}

	// ------------------------------------------------------------------
	// Outils
	// ------------------------------------------------------------------
	private static String name(ServerPlayer p) {
		return p.getScoreboardName();
	}

	private boolean admin(ServerPlayer p) {
		return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private boolean leader(ServerPlayer p) {
		return admin(p) || name(p).equals(session.storyteller());
	}

	/** Lance une action de la session ; affiche le résultat ou l'erreur au joueur. */
	private Consumer<ServerPlayer> act(Function<ServerPlayer, String> action, Consumer<ServerPlayer> then) {
		return p -> {
			try {
				String result = action.apply(p);
				if (result != null && !result.isEmpty()) {
					p.sendSystemMessage(Gui.plain(result, ChatFormatting.GREEN));
				}
				if (then != null) {
					then.accept(p);
				}
			} catch (GameException e) {
				p.sendSystemMessage(Gui.plain(e.getMessage(), ChatFormatting.RED));
			}
		};
	}

	private Consumer<ServerPlayer> act(Function<ServerPlayer, String> action) {
		return act(action, null);
	}

	private static ItemStack roleIcon(Role r) {
		Item item = switch (r.type) {
			case CITADIN -> Items.BOOK;
			case ETRANGER -> Items.FEATHER;
			case SBIRE -> Items.REDSTONE;
			case DEMON -> Items.WITHER_SKELETON_SKULL;
		};
		ChatFormatting color = r.evil() ? ChatFormatting.RED : ChatFormatting.AQUA;
		ItemStack s = Gui.icon(item, r.displayName, color,
			List.of(r.type.displayName + ", camp : " + Team.of(r).displayName, r.text));
		// Le jeton du rôle lui-même (emblème aux couleurs du camp), dessiné par le mod.
		String icon = "icone_" + r.modelSuffix();
		return model(s, icon);
	}

	/** Donne à un objet l'apparence d'un modèle du mod (assets/valcombe/items/<nom>.json). */
	static ItemStack model(ItemStack s, String name) {
		s.set(DataComponents.ITEM_MODEL, net.minecraft.resources.Identifier.fromNamespaceAndPath("valcombe", name));
		return s;
	}

	/** Icône d'un joueur du cercle : sa tête s'il est connecté, un crâne s'il est mort. */
	private ItemStack seatIcon(String name, boolean alive, String title, ChatFormatting color, List<String> lore) {
		if (!alive) {
			return Gui.icon(Items.SKELETON_SKULL, title, ChatFormatting.DARK_GRAY, lore);
		}
		ServerPlayer owner = host.player(name);
		if (owner == null) {
			return Gui.icon(Items.ZOMBIE_HEAD, title, color, lore);
		}
		return Gui.head(owner, name, title, color, lore);
	}

	private static void suggestInChat(ServerPlayer p, String label, String command) {
		p.sendSystemMessage(Component.literal("[" + label + "]").withStyle(s -> s.withColor(ChatFormatting.AQUA)
			.withBold(true).withClickEvent(new ClickEvent.SuggestCommand(command))));
	}

	/** Objet « Menu de Valcombe » : clic droit pour ouvrir le menu principal. */
	public static ItemStack menuItem() {
		return model(Gui.icon(Items.CLOCK, MENU_ITEM_NAME, "Clic droit : ouvrir le menu du jeu."), "grimoire");
	}

	/**
	 * L'objet de la baguette de construction, tel que WorldEdit le nomme : c'est aussi la baguette de WorldEdit
	 * ({@code wand-item}, voir WorldEditBridge). Un mot, et non l'objet lui-même : lire Items ici chargerait tous les
	 * objets du jeu avant son démarrage.
	 */
	public static final String WAND_ID = "minecraft:blaze_rod";
	public static final String WAND_NAME = "Baguette de construction";

	/** L'objet de base de la baguette de construction (la baguette de feu). */
	public static net.minecraft.world.item.Item wandBase() {
		return Items.BLAZE_ROD;
	}

	/**
	 * Baguette de construction : la baguette de sélection de WorldEdit, dessinée pour Valcombe. Elle est donnée à qui
	 * construit dans un atelier (et reprise à la sortie) ; elle n'agit que là où WorldEdit est permis.
	 */
	public static ItemStack wandItem() {
		return model(Gui.icon(wandBase(), WAND_NAME, "Clic gauche sur un bloc : premier coin de la sélection.",
			"Clic droit sur un bloc : second coin. La sélection s'affiche avec ses dimensions.",
			"Puis //set, //replace, //walls, //copy, //paste, //rotate, //move, //stack…",
			"Annuler : /vc annuler. En faire une structure : /vc structures depuis-selection."), "outil_baguette");
	}

	public static boolean isWand(ItemStack stack) {
		Component n = stack.get(DataComponents.CUSTOM_NAME);
		return stack.is(wandBase()) && n != null && n.getString().equals(WAND_NAME);
	}

	/** Outils de carte du mode construction : un objet par sorte de repère, une gomme, l'outil de structure et la main. */
	public enum MapTool {
		SEAT(Items.GOLD_INGOT, "Carte : trône", "Clic droit sur un bloc : poser un trône.",
			"Clic droit sur un trône déjà posé : le prendre, puis cliquer là où le reposer."),
		HOUSE(Items.EMERALD, "Carte : maison", "Clic droit sur un bloc : poser une maison (là où l'on dort).",
			"Clic droit sur une maison déjà posée : la prendre pour la déplacer.",
			"Accroupi + clic droit : bâtir une maison ici."),
		CENTER(Items.COMPASS, "Carte : centre de la place", "Clic droit sur un bloc : le centre de la place est ici."),
		ROOM(Items.AMETHYST_SHARD, "Carte : salle de convocation", "Clic droit sur un bloc : le joueur convoqué arrivera ici.",
			"Accroupi + clic droit : bâtir la salle ici."),
		ERASE(Items.BARRIER, "Carte : gomme", "Clic droit sur le bloc sous une étiquette : retirer ce repère.",
			"Une maison ou une salle bâtie d'un clic disparaît avec lui."),
		STRUCTURE(Items.BRICK, "Carte : structure", "Choisir une structure du catalogue : /vc structure poser <id>.",
			"Clic droit sur un bloc : la poser là où le fantôme la montre.",
			"Accroupi + clic droit : la tourner d'un quart de tour. Clic gauche : la retourner."),
		HAND(Items.STICK, "Carte : main", "Clic droit sur un trône, une maison, la salle, le centre",
			"ou une structure posée : un menu pour la déplacer, la tourner ou la retirer.",
			"Après « Déplacer » : clic droit là où la reposer.");

		final net.minecraft.world.item.Item item;
		final String title;
		final String[] lore;

		MapTool(net.minecraft.world.item.Item item, String title, String... lore) {
			this.item = item;
			this.title = title;
			this.lore = lore;
		}

		/** Modèle dessiné par le mod pour cet outil (assets/valcombe/items/outil_*.json). */
		String model() {
			return "outil_" + switch (this) {
				case SEAT -> "trone";
				case HOUSE -> "maison";
				case CENTER -> "centre";
				case ROOM -> "salle";
				case ERASE -> "gomme";
				case STRUCTURE -> "structure";
				case HAND -> "main";
			};
		}

		ItemStack stack() {
			return ValcombeUi.model(Gui.icon(item, title, lore), model());
		}
	}

	/** L'outil de carte que représente cet objet, ou null. */
	public static MapTool mapTool(ItemStack stack) {
		Component n = stack.get(DataComponents.CUSTOM_NAME);
		if (n == null) {
			return null;
		}
		for (MapTool t : MapTool.values()) {
			if (stack.is(t.item) && n.getString().equals(t.title)) {
				return t;
			}
		}
		return null;
	}

	/** Donne les outils de carte (ou les reprend tous). */
	public static void mapTools(ServerPlayer p, boolean on) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (mapTool(inv.getItem(i)) != null) {
				inv.setItem(i, ItemStack.EMPTY);
			}
		}
		if (on) {
			for (MapTool t : MapTool.values()) {
				inv.add(t.stack());
			}
		}
	}

	/** Met cet outil de carte dans la main du joueur (il le reçoit s'il ne l'avait pas). */
	public static void holdMapTool(ServerPlayer p, MapTool tool) {
		var inv = p.getInventory();
		int slot = -1;
		for (int i = 0; i < inv.getContainerSize() && slot < 0; i++) {
			if (mapTool(inv.getItem(i)) == tool) {
				slot = i;
			}
		}
		if (slot < 0) {
			inv.add(tool.stack());
			for (int i = 0; i < inv.getContainerSize() && slot < 0; i++) {
				if (mapTool(inv.getItem(i)) == tool) {
					slot = i;
				}
			}
		}
		if (slot >= 9 && slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE) {
			// Barre d'objets pleine (lanternes ramassées, objets du joueur) : l'outil est rangé dans le sac. Il prend
			// la case choisie de la barre, et l'objet qui s'y trouvait va à sa place dans le sac.
			int selected = inv.getSelectedSlot();
			ItemStack held = inv.getItem(selected);
			inv.setItem(selected, inv.getItem(slot));
			inv.setItem(slot, held);
			slot = selected;
		}
		if (slot >= 0 && slot < 9) {
			inv.setSelectedSlot(slot);
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(slot));
		}
	}

	public static final String STAFF_NAME = "Bâton du Conteur";

	/** Bâton du Conteur : viser un joueur pour voir son rôle, ouvrir sa ligne du grimoire, le prendre à part. */
	public static ItemStack staffItem() {
		return model(Gui.icon(Items.BREEZE_ROD, STAFF_NAME, "Viser un joueur : son rôle s'affiche.",
			"Clic droit : sa ligne du grimoire. Accroupi + clic droit : aparté vocal.",
			"Clic droit dans le vide : le panneau du Conteur."), "baton");
	}

	public static boolean isStaffItem(ItemStack stack) {
		Component n = stack.get(DataComponents.CUSTOM_NAME);
		return stack.is(Items.BREEZE_ROD) && n != null && n.getString().equals(STAFF_NAME);
	}

	public static void staffItem(ServerPlayer p, boolean on) {
		var inv = p.getInventory();
		boolean has = false;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (isStaffItem(inv.getItem(i))) {
				if (on) {
					has = true;
				} else {
					inv.setItem(i, ItemStack.EMPTY);
				}
			}
		}
		if (on && !has) {
			inv.add(staffItem());
		}
	}

	public static final String HAT_NAME = "Coiffe des âmes";

	/** Coiffe d'un mort : un objet au modèle du mod, porté sur la tête. */
	public static ItemStack deadHat(String model) {
		ItemStack s = new ItemStack(Items.PAPER);
		s.set(DataComponents.ITEM_MODEL, net.minecraft.resources.Identifier.fromNamespaceAndPath("valcombe", model));
		s.set(DataComponents.CUSTOM_NAME, Component.literal(HAT_NAME));
		return s;
	}

	public static boolean isDeadHat(ItemStack stack) {
		Component n = stack.get(DataComponents.CUSTOM_NAME);
		return stack.is(Items.PAPER) && n != null && n.getString().equals(HAT_NAME);
	}

	public static final String ACCUSE_NAME = "Plume d'accusation";

	public static ItemStack accuseItem() {
		return model(Gui.icon(Items.FEATHER, ACCUSE_NAME, "Le jour, quand les nominations sont ouvertes :",
			"clic droit sur un joueur pour l'accuser."), "plume");
	}

	public static boolean isAccuseItem(ItemStack stack) {
		Component n = stack.get(DataComponents.CUSTOM_NAME);
		return stack.is(Items.FEATHER) && n != null && n.getString().equals(ACCUSE_NAME);
	}

	/** Donne (on = true) ou reprend la plume d'accusation. */
	public static void accuseItem(ServerPlayer p, boolean on) {
		var inv = p.getInventory();
		boolean has = false;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (isAccuseItem(inv.getItem(i))) {
				if (on) {
					has = true;
				} else {
					inv.setItem(i, ItemStack.EMPTY);
				}
			}
		}
		if (on && !has) {
			inv.add(accuseItem());
		}
	}

	public static boolean isMenuItem(ItemStack stack) {
		Component n = stack.get(DataComponents.CUSTOM_NAME);
		return stack.is(Items.CLOCK) && n != null && n.getString().equals(MENU_ITEM_NAME);
	}

	/** Donne le grimoire « Menu de Valcombe » (commun à toutes les parties : il ouvre le menu de la partie du joueur). */
	public static void giveMenuItem(ServerPlayer p) {
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			if (isMenuItem(p.getInventory().getItem(i))) {
				if (!p.getInventory().getItem(i).has(DataComponents.ITEM_MODEL)) {
					p.getInventory().setItem(i, menuItem()); // ancienne horloge : remplacée par le grimoire
				}
				return;
			}
		}
		p.getInventory().add(menuItem());
	}

	// ------------------------------------------------------------------
	// Menu principal
	// ------------------------------------------------------------------
	public void openMain(ServerPlayer p) {
		if (ViewSync.hasClientMod(p)) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new fr.valcombe.net.ValcombeNet.OpenPayload("main"));
			return;
		}
		String me = name(p);
		Game g = session.game();
		Gui gui = new Gui("main", "Les Cendres de Valcombe", 4);

		Prompt pending = session.currentPrompt(me);
		if (pending != null) {
			gui.set(4, Gui.glow(Gui.icon(Items.BELL, "Action en attente", "Clique pour y répondre.")),
				pl -> openPrompt(pl, session.currentPrompt(name(pl))));
		}

		if (g == null) {
			boolean inLobby = session.lobby().contains(me);
			gui.set(10, inLobby
					? Gui.icon(Items.WOOL.red(), "Se désinscrire", "Quitter la prochaine partie.")
					: Gui.icon(Items.WOOL.lime(), "S'inscrire", "Rejoindre la prochaine partie."),
				act(pl -> inLobby ? session.leave(name(pl)) : session.join(name(pl)), this::openMain));
		} else {
			Role role = session.believedRole(me);
			if (role != null) {
				gui.set(10, roleIcon(role).copy(), null);
			}
			boolean day = g.phase() == Game.Phase.DAY && !g.dayOver();
			if (day && role != null) {
				gui.set(12, Gui.icon(Items.OAK_SIGN, "Nominer un joueur", "Ouvre un vote pour l'exécuter."),
					pl -> openPicker(pl, "Nominer qui ?", true, target -> act(x -> session.nominate(name(x), target))));
				gui.set(14, Gui.icon(Items.CROSSBOW, "Tirer (Arbalétrier)",
						"Une fois par partie. Tout le monde peut bluffer : sans effet si tu n'es pas l'Arbalétrier."),
					pl -> openPicker(pl, "Tirer sur qui ?", true, target -> act(x -> session.shoot(name(x), target))));
			}
		}
		gui.set(16, Gui.icon(Items.BOOK, "Fiches des rôles", "Les 22 rôles du script."), this::openSheets);

		List<String> lobbyLore = new ArrayList<>();
		if (g != null) {
			for (Game.Seat s : g.players()) {
				lobbyLore.add(s.name + (s.alive() ? "" : " (mort)"));
			}
		} else {
			lobbyLore.addAll(session.lobby());
		}
		if (session.storyteller() != null) {
			lobbyLore.add("Conteur : " + session.storyteller());
		}
		gui.set(19, Gui.icon(Items.NAME_TAG, g != null ? "Le cercle" : "Inscrits (" + session.lobby().size() + ")",
			ChatFormatting.GOLD, lobbyLore.isEmpty() ? List.of("Personne") : lobbyLore));
		gui.set(21, Gui.icon(Items.WRITTEN_BOOK, "Historique", "Le déroulé de la dernière partie, dans le chat."),
			act(pl -> session.history(name(pl))));
		gui.set(23, Gui.icon(Items.WRITABLE_BOOK, "Écrire au Conteur", "Message privé au Conteur."), pl -> {
			pl.closeContainer();
			suggestInChat(pl, "Clique pour écrire au Conteur", "/vc mp ");
		});
		if (leader(p)) {
			gui.set(25, Gui.glow(Gui.icon(Items.ENDER_EYE, "Panneau du Conteur", "Mener la partie.")),
				this::openStoryteller);
		}
		gui.open(p, host);
	}

	/** Choix d'un joueur vivant du cercle (nomination, tir). */
	private void openPicker(ServerPlayer p, String title, boolean aliveOnly, Function<String, Consumer<ServerPlayer>> onPick) {
		Game g = session.game();
		if (g == null) {
			return;
		}
		Gui gui = new Gui("picker", title, 3);
		int slot = 0;
		for (Game.Seat s : g.players()) {
			if (aliveOnly && !s.alive()) {
				continue;
			}
			String target = s.name;
			gui.set(slot++, seatIcon(target, s.alive(), target, ChatFormatting.YELLOW, List.of()), pl -> {
				pl.closeContainer();
				onPick.apply(target).accept(pl);
			});
		}
		gui.set(26, Gui.icon(Items.BARRIER, "Retour"), this::openMain);
		gui.open(p, host);
	}

	public void openSheets(ServerPlayer p) {
		Gui gui = new Gui("sheets", "Fiches des rôles", 4);
		// Lignes 1-2 : Citadins ; ligne 3 : Étrangers à gauche, Sbires à droite ; ligne 4 : le Démon.
		int[] start = {0, 18, 23, 31};
		RoleType[] types = RoleType.values();
		for (int t = 0; t < types.length; t++) {
			int slot = start[t];
			for (Role r : Role.ofType(types[t])) {
				gui.set(slot++, roleIcon(r));
			}
		}
		gui.set(35, Gui.icon(Items.BARRIER, "Retour"), this::openMain);
		gui.open(p, host);
	}

	// ------------------------------------------------------------------
	// Invites (nuit, vote, Conteur)
	// ------------------------------------------------------------------
	/** Affiche l'invite en cours d'un joueur, ou ferme le menu d'invite si elle a disparu. */
	public void openPrompt(ServerPlayer p, Prompt prompt) {
		if (prompt == null) {
			if (p.containerMenu instanceof Gui.Menu m && m.gui().key.startsWith("prompt:")) {
				p.closeContainer();
			}
			return;
		}
		switch (prompt) {
			case Prompt.Choose c -> openChoose(p, c);
			case Prompt.Info i -> openInfo(p, i.title(), i.text());
			case Prompt.Grimoire gr -> openGrimoire(p, true);
			case Prompt.Vote v -> openVote(p, v);
			case Prompt.Confirm cf -> openConfirm(p, cf);
			case Prompt.Decide dc -> openDecide(p, dc);
			// Sans le mod : l'hypothèse se donne par commande (le message du chat l'explique).
			case Prompt.Guess gs -> openInfo(p, gs.title(), gs.text() + " Réponds par /vc deviner joueur=rôle (" + gs.count()
				+ " fois, séparés par des espaces).");
		}
	}

	private void openChoose(ServerPlayer p, Prompt.Choose c) {
		Gui gui = new Gui("prompt:choose", c.title() + " : désigne " + c.count() + " joueur(s)", 3);
		int slot = 0;
		for (Prompt.Option o : c.options()) {
			String title = (o.selected() ? "✔ " : "") + o.name() + (o.alive() ? "" : " (mort)");
			ItemStack icon = seatIcon(o.name(), o.alive(), title,
				o.selected() ? ChatFormatting.GREEN : ChatFormatting.YELLOW,
				List.of(o.selected() ? "Clique pour retirer." : "Clique pour désigner."));
			if (o.selected()) {
				Gui.glow(icon);
			}
			gui.set(slot++, icon, act(pl -> session.choose(name(pl), o.name())));
		}
		Role role = session.believedRole(name(p));
		if (role != null) {
			gui.set(18, roleIcon(role));
		}
		gui.set(22, Gui.icon(Items.WOOL.lime(), "Valider", c.optional()
				? "Une seule fois par partie : valide sans personne pour garder ton pouvoir." : "Confirmer ton choix."),
			act(pl -> session.validate(name(pl))));
		gui.open(p, host);
	}

	private void openInfo(ServerPlayer p, String title, String text) {
		Gui gui = new Gui("prompt:info", title, 3);
		gui.set(13, Gui.glow(Gui.icon(Items.PAPER, title, ChatFormatting.GOLD, List.of(text))));
		gui.set(22, Gui.icon(Items.WOOL.lime(), "Je me rendors", "Terminer ton réveil."),
			act(pl -> session.sleep(name(pl))));
		gui.open(p, host);
	}

	private void openVote(ServerPlayer p, Prompt.Vote v) {
		Gui gui = new Gui("prompt:vote", "Exécuter " + v.nominee() + " ?", 3);
		gui.set(4, seatIcon(v.nominee(), true, v.nominee(), ChatFormatting.YELLOW,
			v.ghost() ? List.of("Tu es mort : voter utilise ton unique vote de fantôme.") : List.of()));
		gui.set(11, Gui.icon(Items.WOOL.lime(), "Je lève la main", "Voter pour l'exécution."),
			act(pl -> session.vote(name(pl), true)));
		gui.set(15, Gui.icon(Items.WOOL.red(), "Je m'abstiens", "Ne pas voter."),
			act(pl -> session.vote(name(pl), false)));
		gui.open(p, host);
	}

	private void openDecide(ServerPlayer p, Prompt.Decide dc) {
		Gui gui = new Gui("prompt:decide", dc.title(), 3);
		gui.set(4, Gui.glow(Gui.icon(Items.PAPER, "Décision du Conteur", ChatFormatting.LIGHT_PURPLE, List.of(dc.question()))));
		for (int i = 0; i < dc.options().size() && i < 18; i++) {
			int choice = i;
			gui.set(9 + i, Gui.icon(Items.WOOL.purple(), dc.options().get(i)), act(pl -> session.decide(name(pl), choice)));
		}
		gui.open(p, host);
	}

	private void openConfirm(ServerPlayer p, Prompt.Confirm cf) {
		Gui gui = new Gui("prompt:confirm", "Information pour " + cf.to(), 3);
		List<String> lore = new ArrayList<>(List.of("Pour " + cf.to() + " :", cf.text()));
		if (cf.truth() != null) {
			lore.add("Vérité : " + cf.truth());
		}
		gui.set(13, Gui.glow(Gui.icon(Items.PAPER, cf.title(), ChatFormatting.LIGHT_PURPLE, lore)));
		gui.set(21, Gui.icon(Items.WOOL.lime(), "Envoyer", "Envoyer ce texte au joueur."),
			act(pl -> session.storytellerSend(name(pl), null)));
		gui.set(23, Gui.icon(Items.WRITABLE_BOOK, "Modifier le texte", "Le texte s'ouvre dans le chat pour être corrigé."),
			pl -> {
				pl.closeContainer();
				suggestInChat(pl, "Clique pour modifier le texte", "/vc envoyer " + cf.text());
			});
		gui.open(p, host);
	}

	// ------------------------------------------------------------------
	// Grimoire
	// ------------------------------------------------------------------
	/** Grimoire du Conteur, ou du Mouchard la nuit (withAck : bouton « Je me rendors »). */
	public void openGrimoire(ServerPlayer p, boolean withAck) {
		Game g = session.game();
		if (g == null) {
			return;
		}
		Gui gui = new Gui(withAck ? "prompt:grimoire" : "grimoire", "Grimoire", 4);
		List<String> lines = g.grimoireLines();
		for (int i = 0; i < g.size(); i++) {
			Game.Seat s = g.player(i);
			Role r = s.role();
			ChatFormatting color = r.evil() ? ChatFormatting.RED : ChatFormatting.AQUA;
			gui.set(i, seatIcon(s.name, s.alive(), s.name + " · " + r.displayName, color, List.of(lines.get(i))));
		}
		gui.set(31, Gui.icon(Items.PAPER, "Bluffs et pouvoirs", ChatFormatting.GOLD,
			List.of(lines.get(g.size()), lines.get(g.size() + 1))));
		if (withAck) {
			gui.set(35, Gui.icon(Items.WOOL.lime(), "Je me rendors", "Terminer ton réveil."),
				act(pl -> session.sleep(name(pl))));
		} else {
			gui.set(35, Gui.icon(Items.BARRIER, "Retour"), this::openStoryteller);
		}
		gui.open(p, host);
	}

	// ------------------------------------------------------------------
	// Panneau du Conteur
	// ------------------------------------------------------------------
	public void openStoryteller(ServerPlayer p) {
		if (!leader(p)) {
			return;
		}
		if (ViewSync.hasClientMod(p)) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new fr.valcombe.net.ValcombeNet.OpenPayload("storyteller"));
			return;
		}
		Game g = session.game();
		Gui gui = new Gui("storyteller", "Panneau du Conteur", 4);
		boolean isSt = name(p).equals(session.storyteller());

		if (g == null) {
			if (admin(p)) {
				gui.set(10, Gui.icon(Items.ENDER_EYE, isSt ? "Quitter le rôle de Conteur" : "Devenir le Conteur",
						"Le Conteur ne joue pas : il relit les informations en mode humain."),
					act(pl -> session.toggleStoryteller(name(pl)), this::openStoryteller));
			}
			gui.set(11, Gui.icon(Items.ARMOR_STAND, "Ajouter 1 joueur fictif", "Pour tester seul."),
				act(pl -> session.addBots(1), this::openStoryteller));
			gui.set(12, Gui.icon(Items.ARMOR_STAND, "Ajouter 4 joueurs fictifs", "Pour tester seul."),
				act(pl -> session.addBots(4), this::openStoryteller));
			gui.set(13, Gui.icon(Items.BARRIER, "Retirer les joueurs fictifs"),
				act(pl -> session.removeBots(), this::openStoryteller));
			gui.set(15, Gui.icon(Items.WOOL.lime(), "Lancer (Conteur automatique)",
					"Inscrits : " + session.lobby().size()),
				act(pl -> session.start(name(pl), Session.Mode.AUTO), pl -> pl.closeContainer()));
			gui.set(16, Gui.icon(Items.WOOL.purple(), "Lancer (Conteur humain)",
					"Le Conteur relit chaque information avant l'envoi."),
				act(pl -> session.start(name(pl), Session.Mode.HUMAIN), pl -> pl.closeContainer()));
		} else {
			gui.set(10, Gui.icon(Items.CLOCK, "Faire tomber la nuit"),
				act(pl -> session.startNight(), pl -> pl.closeContainer()));
			gui.set(11, Gui.icon(Items.SUNFLOWER, "Clore la journée", "Exécute le joueur en tête des votes."),
				act(pl -> session.endDay(), this::openStoryteller));
			gui.set(12, Gui.icon(Items.FEATHER, "Passer l'étape", "Débloque un réveil (joueur absent)."),
				act(pl -> session.skip()));
			if (isSt || admin(p)) {
				gui.set(14, Gui.glow(Gui.icon(Items.ENDER_EYE, "Grimoire", "Rôles, marqueurs, bluffs.")),
					pl -> openGrimoire(pl, false));
			}
			gui.set(16, Gui.icon(Items.BARRIER, "Arrêter la partie"),
				act(pl -> session.stop(), pl -> pl.closeContainer()));
			ChatFormatting c = ChatFormatting.GOLD;
			gui.set(22, Gui.icon(Items.NAME_TAG, "Phase", c, List.of(
				switch (g.phase()) {
					case NIGHT -> "Nuit " + g.night();
					case DAY -> "Jour " + g.day() + (g.dayOver() ? " (terminé)" : "");
					default -> g.phase().name();
				},
				"Vivants : " + g.aliveCount() + " / " + g.size(),
				"Conteur : " + (session.mode() == Session.Mode.HUMAIN ? session.storyteller() : "automatique"))));
		}
		gui.set(19, Gui.icon(session.settings.nightFreeze ? Items.WOOL.lime() : Items.WOOL.gray(),
				"Nuit : joueurs immobiles", session.settings.nightFreeze ? "Activé" : "Désactivé"),
			act(pl -> session.setting("nuit_immobile", session.settings.nightFreeze ? "non" : "oui"), this::openStoryteller));
		gui.set(20, Gui.icon(session.settings.nightDark ? Items.WOOL.lime() : Items.WOOL.gray(),
				"Nuit : écran noir", session.settings.nightDark ? "Activé" : "Désactivé"),
			act(pl -> session.setting("nuit_sombre", session.settings.nightDark ? "non" : "oui"), this::openStoryteller));
		gui.set(21, Gui.icon(session.settings.nightHouses ? Items.WOOL.lime() : Items.WOOL.gray(),
				"Nuit : chacun chez soi", session.settings.nightHouses ? "Activé (si le village est construit)" : "Désactivé"),
			act(pl -> session.setting("nuit_maisons", session.settings.nightHouses ? "non" : "oui"), this::openStoryteller));
		gui.set(24, Gui.icon(Items.ARROW, "Temps de défense : -5 s", "Actuel : " + session.settings.defenseSeconds + " s"),
			act(pl -> session.setting("temps_defense", String.valueOf(session.settings.defenseSeconds - 5)), this::openStoryteller));
		gui.set(25, Gui.icon(Items.ARROW, "Temps de défense : +5 s", "Actuel : " + session.settings.defenseSeconds + " s"),
			act(pl -> session.setting("temps_defense", String.valueOf(session.settings.defenseSeconds + 5)), this::openStoryteller));
		if (session.game() != null) {
			gui.set(28, Gui.icon(Items.BELL, "Rassembler le village", "Ramène tout le monde au cercle."),
				act(pl -> session.gather()));
			boolean council = session.settings.nominationsFirst;
			gui.set(29, Gui.icon(Items.OAK_SIGN, council
					? session.nominationsOpen() ? "Clore les accusations et voter" : "Ouvrir le Conseil"
					: session.nominationsOpen() ? "Fermer les nominations" : "Ouvrir les nominations"),
				act(pl -> session.setNominations(!session.nominationsOpen()), this::openStoryteller));
			gui.set(30, Gui.icon(Items.CLOCK, council ? "Passer aux votes" : "Lancer le vote maintenant",
					council ? "Clôt les accusations : un vote par accusé, dans l'ordre." : "Écourte la défense de l'accusé."),
				act(pl -> session.startVoteNow()));
			// Reprendre la main : pause, vote annulé, grâce, étape précédente.
			gui.set(31, Gui.icon(Items.SHEARS, "Annuler le vote", "L'accusation est retirée."),
				act(pl -> session.cancelVote(), this::openStoryteller));
			gui.set(32, Gui.icon(Items.PAPER, "Gracier", "Personne n'est plus sur le billot."),
				act(pl -> session.pardon(), this::openStoryteller));
			gui.set(33, Gui.icon(Items.HOPPER, session.paused() ? "Reprendre la partie" : "Mettre en pause",
					"Minuteurs, nuit et vote attendent."),
				act(pl -> session.togglePause(), this::openStoryteller));
			// Mise en suspens : la partie s'arrête là où elle en est, tout le monde va au hub ; elle reprendra plus tard.
			gui.set(34, session.suspended()
					? Gui.icon(Items.RECOVERY_COMPASS, "Reprendre la partie en suspens",
						"Les joueurs sont rappelés et retrouvent leur place.")
					: Gui.icon(Items.ENDER_CHEST, "Mettre en suspens",
						"La partie s'arrête là où elle en est, tout le monde va au hub. Elle reprendra plus tard, même après un redémarrage."),
				act(pl -> session.suspended() ? session.resumeSuspended() : session.suspend()));
			gui.set(13, Gui.icon(Items.SPECTRAL_ARROW, "Étape précédente", "Rejoue le réveil précédent de la nuit."),
				act(pl -> session.previous()));
		}
		gui.set(35, Gui.icon(Items.BARRIER, "Retour"), this::openMain);
		gui.open(p, host);
	}
}
