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
		return Gui.icon(item, r.displayName, color,
			List.of(r.type.displayName + " — camp : " + Team.of(r).displayName, r.text));
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
		return Gui.glow(Gui.icon(Items.CLOCK, MENU_ITEM_NAME, "Clic droit : ouvrir le menu du jeu."));
	}

	public static final String WAND_NAME = "Baguette de carte";

	public static ItemStack wandItem() {
		return Gui.glow(Gui.icon(Items.BLAZE_ROD, WAND_NAME, "Clic droit sur un bloc : siège.", "Accroupi + clic droit : maison."));
	}

	public static boolean isWand(ItemStack stack) {
		Component n = stack.get(DataComponents.CUSTOM_NAME);
		return stack.is(Items.BLAZE_ROD) && n != null && n.getString().equals(WAND_NAME);
	}

	public static boolean isMenuItem(ItemStack stack) {
		Component n = stack.get(DataComponents.CUSTOM_NAME);
		return stack.is(Items.CLOCK) && n != null && n.getString().equals(MENU_ITEM_NAME);
	}

	public void giveMenuItem(ServerPlayer p) {
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			if (isMenuItem(p.getInventory().getItem(i))) {
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
		gui.set(22, Gui.icon(Items.WOOL.lime(), "Valider", "Confirmer ton choix."),
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
			gui.set(i, seatIcon(s.name, s.alive(), s.name + " — " + r.displayName, color, List.of(lines.get(i))));
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
			gui.set(29, Gui.icon(Items.OAK_SIGN, session.nominationsOpen() ? "Fermer les nominations" : "Ouvrir les nominations"),
				act(pl -> session.setNominations(!session.nominationsOpen()), this::openStoryteller));
			gui.set(30, Gui.icon(Items.CLOCK, "Lancer le vote maintenant", "Écourte la défense de l'accusé."),
				act(pl -> session.startVoteNow()));
		}
		gui.set(35, Gui.icon(Items.BARRIER, "Retour"), this::openMain);
		gui.open(p, host);
	}
}
