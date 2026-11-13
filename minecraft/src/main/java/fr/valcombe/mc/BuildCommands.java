package fr.valcombe.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import fr.valcombe.mc.worlds.BuildAccess;
import fr.valcombe.mc.worlds.BuildInvitations;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldLookup;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.GameException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Les commandes de la construction.
 * <ul>
 * <li>Opérateurs : {@code /vc atelier inviter <joueur> [atelier|tous]}, {@code /vc atelier retirer <joueur>
 * [atelier|tous]}, {@code /vc atelier liste} (ou {@code invitations}), {@code /vc atelier worldedit-essais
 * [oui|non]}.</li>
 * <li>Qui construit (opérateurs, et invités dans leurs ateliers) : {@code /vc annuler [fois]}, {@code /vc retablir
 * [fois]} (l'« annuler » commun, voir {@link SharedUndo}), {@code /vc baguette}, {@code /vc structures
 * depuis-selection <id> <nom> [catégorie]}.</li>
 * </ul>
 * Les invitations se gèrent aussi par l'API d'administration ({@code /admin/ateliers}) : mêmes méthodes
 * ({@link #invite}, {@link #uninvite}, {@link #view}).
 */
final class BuildCommands {
	@FunctionalInterface
	private interface Action {
		String run(CommandContext<CommandSourceStack> ctx) throws Exception;
	}

	private BuildCommands() {
	}

	private static int exec(CommandContext<CommandSourceStack> ctx, Action action) {
		try {
			String result = action.run(ctx);
			if (result != null && !result.isEmpty()) {
				ctx.getSource().sendSuccess(() -> Component.literal(result), false);
			}
			return 1;
		} catch (GameException e) {
			ctx.getSource().sendFailure(Component.literal(e.getMessage()));
			return 0;
		} catch (Exception e) {
			ValcombeMod.LOG.error("Erreur dans une commande de construction", e);
			ctx.getSource().sendFailure(Component.literal("Erreur interne : " + e.getMessage()));
			return 0;
		}
	}

	private static String arg(CommandContext<CommandSourceStack> ctx, String key) {
		return StringArgumentType.getString(ctx, key);
	}

	private static BuildAccess access() {
		BuildAccess a = BuildAccess.current();
		if (a == null) {
			throw new GameException("La construction n'est pas disponible sur ce serveur (voir son journal).");
		}
		return a;
	}

	private static WorldEditGate gate() {
		WorldEditGate g = WorldEditGate.current();
		if (g == null) {
			throw new GameException("La construction n'est pas disponible sur ce serveur (voir son journal).");
		}
		return g;
	}

	/** Les ateliers du serveur (carte et structures), dans l'ordre du registre. */
	private static List<WorldEntry> workshops() {
		WorldManager w = ValcombeMod.worlds();
		return w == null ? List.of() : w.list().stream().filter(e -> e.sorte().workshop()).toList();
	}

	/** Qui construit quelque part : un opérateur, ou un joueur invité dans au moins un atelier. */
	static boolean builder(CommandSourceStack source) {
		ServerPlayer p = source.getPlayer();
		if (p == null) {
			return false;
		}
		BuildAccess a = BuildAccess.current();
		return BuildAccess.operator(p) || a != null && a.guestSomewhere(p);
	}

	static void register(LiteralArgumentBuilder<CommandSourceStack> root, Predicate<CommandSourceStack> admin) {
		SuggestionProvider<CommandSourceStack> players = (c, b) -> SharedSuggestionProvider.suggest(
			c.getSource().getServer().getPlayerList().getPlayers().stream().map(ServerPlayer::getScoreboardName), b);
		SuggestionProvider<CommandSourceStack> places = (c, b) -> {
			List<String> all = new ArrayList<>(workshops().stream().map(WorldEntry::id).toList());
			all.add("tous");
			return SharedSuggestionProvider.suggest(all, b);
		};
		SuggestionProvider<CommandSourceStack> guests = (c, b) -> {
			BuildAccess a = BuildAccess.current();
			return SharedSuggestionProvider.suggest(a == null ? List.<String>of()
				: a.invitations().list().stream().map(BuildInvitations.Invite::joueur).distinct().toList(), b);
		};
		root.then(Commands.literal("atelier").requires(admin)
			.then(Commands.literal("inviter")
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(players)
					.executes(c -> exec(c, x -> invite(x.getSource(), arg(x, "joueur"), null)))
					.then(Commands.argument("atelier", StringArgumentType.word()).suggests(places)
						.executes(c -> exec(c, x -> invite(x.getSource(), arg(x, "joueur"), arg(x, "atelier")))))))
			.then(Commands.literal("retirer")
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(guests)
					.executes(c -> exec(c, x -> uninvite(x.getSource().getServer(), arg(x, "joueur"), null)))
					.then(Commands.argument("atelier", StringArgumentType.word()).suggests(places)
						.executes(c -> exec(c, x -> uninvite(x.getSource().getServer(), arg(x, "joueur"), arg(x, "atelier")))))))
			.then(Commands.literal("liste").executes(c -> exec(c, x -> lines(x, list()))))
			.then(Commands.literal("invitations").executes(c -> exec(c, x -> lines(x, list()))))
			.then(Commands.literal("worldedit-essais")
				.executes(c -> exec(c, x -> "WorldEdit dans les parties d'essai : "
					+ (access().invitations().worldEditInTrials() ? "ouvert aux opérateurs." : "fermé.")
					+ " /vc atelier worldedit-essais oui|non pour changer."))
				.then(Commands.argument("permis", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("oui", "non"), b))
					.executes(c -> exec(c, x -> trials(arg(x, "permis")))))));
		Predicate<CommandSourceStack> building = s -> admin.test(s) || builder(s);
		root.then(Commands.literal("annuler").requires(building)
			.executes(c -> exec(c, x -> gate().undo().undo(x.getSource().getPlayerOrException(), 1)))
			.then(Commands.argument("fois", IntegerArgumentType.integer(1, 40))
				.executes(c -> exec(c, x -> gate().undo().undo(x.getSource().getPlayerOrException(),
					IntegerArgumentType.getInteger(x, "fois"))))));
		root.then(Commands.literal("retablir").requires(building)
			.executes(c -> exec(c, x -> gate().undo().redo(x.getSource().getPlayerOrException(), 1)))
			.then(Commands.argument("fois", IntegerArgumentType.integer(1, 40))
				.executes(c -> exec(c, x -> gate().undo().redo(x.getSource().getPlayerOrException(),
					IntegerArgumentType.getInteger(x, "fois"))))));
		root.then(Commands.literal("baguette").requires(building)
			.executes(c -> exec(c, x -> wand(x.getSource().getPlayerOrException()))));
		root.then(Commands.literal("structures").requires(building)
			.then(Commands.literal("depuis-selection")
				.then(Commands.argument("id", StringArgumentType.word())
					.then(Commands.argument("nom", StringArgumentType.string())
						.executes(c -> exec(c, x -> fromSelection(x.getSource().getPlayerOrException(), arg(x, "id"), arg(x, "nom"), null)))
						.then(Commands.argument("categorie", StringArgumentType.word())
							.suggests((c, b) -> SharedSuggestionProvider.suggest(SelectionToStructure.CATEGORIES, b))
							.executes(c -> exec(c, x -> fromSelection(x.getSource().getPlayerOrException(), arg(x, "id"), arg(x, "nom"),
								arg(x, "categorie")))))))));
	}

	private static String lines(CommandContext<CommandSourceStack> ctx, List<String> lines) {
		for (String line : lines) {
			ctx.getSource().sendSuccess(() -> Component.literal(line), false);
		}
		return "";
	}

	// ------------------------------------------------------------------
	// Invitations
	// ------------------------------------------------------------------

	/**
	 * L'atelier que désigne ce texte ({@link BuildInvitations#ALL} pour « tous ») ; sans texte, celui où se trouve
	 * l'opérateur. Une GameException si rien ne correspond.
	 */
	static String resolveWorkshop(String text, ServerPlayer here) {
		List<WorldEntry> list = workshops();
		if (text == null || text.isBlank()) {
			BuildAccess a = access();
			WorldEntry e = here == null ? null : a.entryOf(here.level());
			if (e != null && e.sorte().workshop()) {
				return e.id();
			}
			throw new GameException("Quel atelier ? /vc atelier inviter <joueur> <atelier|tous>. "
				+ (list.isEmpty() ? "Aucun atelier sur ce serveur : /vc monde creer atelier-carte <nom>."
					: "Ateliers : " + String.join(", ", list.stream().map(WorldEntry::id).toList()) + "."));
		}
		String t = text.strip();
		if (t.equalsIgnoreCase("tous") || t.equals(BuildInvitations.ALL)) {
			return BuildInvitations.ALL;
		}
		if (list.isEmpty()) {
			throw new GameException("Aucun atelier sur ce serveur : /vc monde creer atelier-carte <nom>.");
		}
		return WorldLookup.resolve(list, t).id();
	}

	private static String placeName(String world) {
		if (BuildInvitations.ALL.equals(world)) {
			return "tous les ateliers";
		}
		WorldManager w = ValcombeMod.worlds();
		WorldEntry e = w == null ? null : w.find(world);
		return e == null ? "« " + world + " »" : "« " + e.nom() + " »";
	}

	private static ServerPlayer online(MinecraftServer server, String name) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.getScoreboardName().equalsIgnoreCase(name)) {
				return p;
			}
		}
		return null;
	}

	private static String invite(CommandSourceStack source, String name, String world) {
		ServerPlayer by = source.getPlayer();
		return invite(source.getServer(), by == null ? "la console" : by.getScoreboardName(), by, name, world);
	}

	/**
	 * Invite un joueur à construire dans un atelier (ou dans tous). Il est prévenu s'il est connecté, et ses droits
	 * changent tout de suite (l'atelier apparaît dans son menu « Mondes », les commandes de WorldEdit y sont à lui).
	 *
	 * @param byName qui invite (pour le fichier) ; here : où il se trouve (l'atelier par défaut), ou null
	 */
	static String invite(MinecraftServer server, String byName, ServerPlayer here, String name, String world) {
		BuildAccess a = access();
		BuildInvitations.checkName(name);
		String id = resolveWorkshop(world, here);
		ServerPlayer target = online(server, name);
		String exact = target != null ? target.getScoreboardName() : name;
		if (target != null && BuildAccess.operator(target)) {
			return exact + " est opérateur : il construit déjà dans tous les ateliers.";
		}
		boolean added = a.invitations().invite(exact, target == null ? null : target.getUUID(), id, byName, Instant.now());
		WorldEditGate g = WorldEditGate.current();
		if (g != null) {
			g.invitationsChanged();
		}
		String where = placeName(id);
		if (!added) {
			return exact + " est déjà invité à construire dans " + where + ".";
		}
		if (target != null) {
			target.sendSystemMessage(Gui.plain(byName + " t'invite à construire dans " + where + " : Menu J › Mondes pour y aller. "
				+ "Tu y construis en mode créatif, avec WorldEdit.", ChatFormatting.GREEN));
		}
		return exact + " est invité à construire dans " + where + (target == null ? " (il le verra à sa prochaine venue)" : "")
			+ " : il y va depuis le hub (Menu J › Mondes), y construit en mode créatif, avec WorldEdit. Retirer : /vc atelier "
			+ "retirer " + exact + ".";
	}

	/**
	 * Retire l'invitation d'un joueur pour un atelier ({@code tous} : son invitation à tous les ateliers), ou toutes ses
	 * invitations (world null). S'il se trouve dans un atelier où il n'a plus droit, il revient au hub.
	 */
	static String uninvite(MinecraftServer server, String name, String world) {
		BuildAccess a = access();
		BuildInvitations.checkName(name);
		String id = world == null ? null : resolveWorkshop(world, null);
		ServerPlayer target = online(server, name);
		int n = a.invitations().remove(name, target == null ? null : target.getUUID(), id);
		if (n == 0) {
			throw new GameException(name + " n'a pas d'invitation" + (id == null ? "" : " pour " + placeName(id)) + ". La liste : "
				+ "/vc atelier liste.");
		}
		WorldEditGate g = WorldEditGate.current();
		if (g != null) {
			g.invitationsChanged();
		}
		if (target != null) {
			target.sendSystemMessage(Gui.plain("Ton invitation à construire " + (id == null ? "dans les ateliers" : "dans " + placeName(id))
				+ " a été retirée.", ChatFormatting.GOLD));
		}
		return id == null ? "Invitations de " + name + " retirées (" + n + ")." : "Invitation de " + name + " pour " + placeName(id)
			+ " retirée.";
	}

	/** {@code /vc atelier liste} : les invités de chaque atelier. */
	static List<String> list() {
		BuildAccess a = access();
		List<String> out = new ArrayList<>();
		List<BuildInvitations.Invite> all = a.invitations().list();
		out.add(all.isEmpty() ? "Aucune invitation à construire. Inviter : /vc atelier inviter <joueur> [atelier|tous]."
			: "Invitations à construire (les opérateurs construisent partout) :");
		List<String> everywhere = all.stream().filter(i -> BuildInvitations.ALL.equals(i.monde())).map(BuildInvitations.Invite::joueur)
			.toList();
		if (!everywhere.isEmpty()) {
			out.add("  Tous les ateliers : " + BuildInvitations.names(everywhere));
		}
		for (WorldEntry e : workshops()) {
			List<String> here = all.stream().filter(i -> i.monde().equals(e.id())).map(BuildInvitations.Invite::joueur).toList();
			if (!here.isEmpty()) {
				out.add("  « " + e.nom() + " » (" + e.id() + ") : " + BuildInvitations.names(here));
			}
		}
		out.add("WorldEdit dans les parties d'essai : " + (a.invitations().worldEditInTrials() ? "ouvert aux opérateurs." : "fermé."));
		return out;
	}

	/** Ce que l'API d'administration et l'écran « Mondes » montrent des invitations. */
	static Map<String, Object> view() {
		BuildAccess a = access();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("worldEditEssais", a.invitations().worldEditInTrials());
		List<Map<String, Object>> list = new ArrayList<>();
		for (BuildInvitations.Invite i : a.invitations().list()) {
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("joueur", i.joueur());
			o.put("uuid", i.uuid());
			o.put("atelier", i.monde());
			o.put("nomAtelier", BuildInvitations.ALL.equals(i.monde()) ? "Tous les ateliers" : placeName(i.monde()).replace("« ", "")
				.replace(" »", ""));
			o.put("par", i.par());
			o.put("le", i.le().toString());
			list.add(o);
		}
		m.put("invitations", list);
		return m;
	}

	private static String trials(String yes) {
		boolean on = switch (yes.toLowerCase(java.util.Locale.ROOT)) {
			case "oui", "on", "vrai" -> true;
			case "non", "off", "faux" -> false;
			default -> throw new GameException("oui ou non.");
		};
		access().invitations().setWorldEditInTrials(on);
		WorldEditGate g = WorldEditGate.current();
		if (g != null) {
			g.invitationsChanged(); // arbre des commandes des opérateurs qui sont dans une partie d'essai
		}
		return on ? "WorldEdit est ouvert aux opérateurs dans les parties d'essai." : "WorldEdit est fermé dans les parties d'essai.";
	}

	// ------------------------------------------------------------------
	// Construire
	// ------------------------------------------------------------------

	private static String wand(ServerPlayer p) {
		BuildAccess a = access();
		if (!a.canUseWorldEdit(p)) {
			throw new GameException(a.refusal(p));
		}
		boolean given = WorldEditGate.giveWand(p);
		return given ? "Baguette de construction dans ton inventaire : clic gauche, premier coin ; clic droit, second coin."
			: "Tu as déjà la baguette de construction.";
	}

	private static String fromSelection(ServerPlayer p, String id, String nom, String categorie) {
		StructureStore store = ValcombeMod.structures();
		if (store == null) {
			throw new GameException("Le catalogue de structures n'est pas prêt.");
		}
		return SelectionToStructure.save(gate().link(), store, p, id, nom, categorie, "");
	}
}
