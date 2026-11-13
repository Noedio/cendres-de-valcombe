package fr.valcombe.mc.worlds;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import fr.valcombe.rules.GameException;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Commandes opérateur des mondes : {@code /vc monde liste | creer <sorte> <nom> | aller <id> | recreer <id>
 * | supprimer <id> | renommer <id> <nom>}. {@code /vc monde aller principal} ramène au monde principal.
 */
public final class WorldCommands {
	private static final Logger LOG = LoggerFactory.getLogger("valcombe");
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault());

	@FunctionalInterface
	private interface Action {
		String run(CommandContext<CommandSourceStack> ctx, WorldManager worlds) throws Exception;
	}

	private WorldCommands() {
	}

	private static int exec(CommandContext<CommandSourceStack> ctx, Supplier<WorldManager> worlds, Action action) {
		try {
			WorldManager w = worlds.get();
			if (w == null) {
				throw new GameException("Les mondes de Valcombe ne sont pas disponibles sur ce serveur (voir son journal).");
			}
			String result = action.run(ctx, w);
			if (result != null && !result.isEmpty()) {
				ctx.getSource().sendSuccess(() -> Component.literal(result), false);
			}
			return 1;
		} catch (GameException e) {
			ctx.getSource().sendFailure(Component.literal(e.getMessage()));
			return 0;
		} catch (Exception e) {
			LOG.error("Erreur dans une commande /vc monde", e);
			ctx.getSource().sendFailure(Component.literal("Erreur interne : " + e.getMessage()));
			return 0;
		}
	}

	private static String arg(CommandContext<CommandSourceStack> ctx, String key) {
		return StringArgumentType.getString(ctx, key);
	}

	/** Ajoute {@code monde} à la commande {@code /vc}, réservé aux opérateurs. */
	public static void register(LiteralArgumentBuilder<CommandSourceStack> root, Predicate<CommandSourceStack> admin,
			Supplier<WorldManager> worlds) {
		SuggestionProvider<CommandSourceStack> ids = (c, b) -> {
			WorldManager w = worlds.get();
			return SharedSuggestionProvider.suggest(w == null ? List.of() : w.list().stream().map(WorldEntry::id).toList(), b);
		};
		SuggestionProvider<CommandSourceStack> destinations = (c, b) -> {
			WorldManager w = worlds.get();
			List<String> all = new ArrayList<>(List.of("principal"));
			if (w != null) {
				w.list().forEach(e -> all.add(e.id()));
			}
			return SharedSuggestionProvider.suggest(all, b);
		};
		SuggestionProvider<CommandSourceStack> kinds = (c, b) -> SharedSuggestionProvider.suggest(
			Arrays.stream(WorldKind.values()).map(WorldKind::code).toList(), b);

		root.then(Commands.literal("monde").requires(admin)
			.executes(c -> exec(c, worlds, WorldCommands::list))
			.then(Commands.literal("liste").executes(c -> exec(c, worlds, WorldCommands::list)))
			.then(Commands.literal("creer")
				.then(Commands.argument("sorte", StringArgumentType.word()).suggests(kinds)
					.then(Commands.argument("nom", StringArgumentType.greedyString())
						.executes(c -> exec(c, worlds, (x, w) -> create(w, arg(x, "sorte"), arg(x, "nom")))))))
			.then(Commands.literal("aller")
				.then(Commands.argument("id", StringArgumentType.word()).suggests(destinations)
					.executes(c -> exec(c, worlds, (x, w) -> go(w, x.getSource().getPlayer(), arg(x, "id"))))))
			.then(Commands.literal("recreer")
				.then(Commands.argument("id", StringArgumentType.word()).suggests(ids)
					.executes(c -> exec(c, worlds, (x, w) -> recreate(w, x.getSource(), arg(x, "id"))))))
			.then(Commands.literal("supprimer")
				.then(Commands.argument("id", StringArgumentType.word()).suggests(ids)
					.executes(c -> exec(c, worlds, (x, w) -> delete(w, x.getSource(), arg(x, "id"))))))
			.then(Commands.literal("renommer")
				.then(Commands.argument("id", StringArgumentType.word()).suggests(ids)
					.then(Commands.argument("nom", StringArgumentType.greedyString())
						.executes(c -> exec(c, worlds, (x, w) -> rename(w, arg(x, "id"), arg(x, "nom"))))))));
	}

	/** Une ligne de la liste : identifiant, nom, sorte, carte, état ; un clic y emmène. */
	private static Component line(WorldManager w, WorldEntry e) {
		StringBuilder s = new StringBuilder("  ").append(e.id()).append(" · « ").append(e.nom()).append(" » · ").append(e.sorte().label());
		if (e.carte() != null) {
			s.append(" · carte ").append(e.carte());
		}
		if (!e.etat().isEmpty()) {
			s.append(" · ").append(e.etat());
		}
		if (w.busy(e.id())) {
			s.append(" · remise à neuf en cours");
		} else if (w.isOpen(e.id())) {
			int n = w.level(e.id()).players().size();
			if (n > 0) {
				s.append(" · ").append(n).append(n > 1 ? " joueurs" : " joueur");
			}
		} else {
			s.append(" · fermé");
		}
		String goTo = "/vc monde aller " + e.id();
		MutableComponent c = Component.literal(s.toString());
		return c.withStyle(st -> st.withClickEvent(new ClickEvent.RunCommand(goTo))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Créé le " + DAY.format(e.cree()) + ", modifié le "
				+ DAY.format(e.modifie()) + ".\nCliquer pour y aller."))));
	}

	private static String list(CommandContext<CommandSourceStack> ctx, WorldManager w) {
		List<WorldEntry> all = w.list();
		if (all.isEmpty()) {
			return "Aucun monde de Valcombe pour l'instant. Pour en créer un : /vc monde creer <sorte> <nom> (sortes : "
				+ String.join(", ", Arrays.stream(WorldKind.values()).map(WorldKind::code).toList()) + ").";
		}
		CommandSourceStack src = ctx.getSource();
		src.sendSuccess(() -> Component.literal("Mondes de Valcombe (" + all.size() + ") :").withStyle(ChatFormatting.GOLD), false);
		for (WorldEntry e : all) {
			Component c = line(w, e);
			src.sendSuccess(() -> c, false);
		}
		return "Cliquer sur un monde pour y aller ; /vc monde aller principal ramène au monde principal.";
	}

	private static String create(WorldManager w, String kindText, String name) {
		WorldKind kind = WorldKind.parse(kindText);
		if (kind == null) {
			throw new GameException("Sorte de monde inconnue : « " + kindText + " ». Sortes : "
				+ String.join(", ", Arrays.stream(WorldKind.values()).map(WorldKind::code).toList()) + ".");
		}
		WorldEntry e = w.create(kind, name);
		return "Monde « " + e.nom() + " » créé (" + kind.label() + ", identifiant " + e.id() + "). Pour y aller : /vc monde aller "
			+ e.id() + ".";
	}

	private static String go(WorldManager w, ServerPlayer p, String id) {
		if (p == null) {
			throw new GameException("Seul un joueur en jeu peut aller dans un monde (pas la console du serveur).");
		}
		if ("principal".equalsIgnoreCase(id)) {
			WorldManager.toMainWorld(p);
			return "Te voilà dans le monde principal.";
		}
		WorldEntry e = w.get(id);
		w.teleport(p, e.id());
		return "Te voilà dans « " + e.nom() + " » (" + e.sorte().label() + ").";
	}

	/** Le message d'une erreur arrivée pendant une opération en plusieurs ticks. */
	private static String failure(Throwable t) {
		Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
		return c instanceof GameException ? c.getMessage() : "Erreur interne : " + c.getMessage();
	}

	private static String recreate(WorldManager w, CommandSourceStack src, String id) {
		WorldEntry e = w.get(id);
		CompletableFuture<ServerLevel> done = w.recreate(e.id());
		done.whenComplete((level, error) -> {
			if (error != null) {
				src.sendFailure(Component.literal(failure(error)));
			} else {
				src.sendSuccess(() -> Component.literal("Monde « " + e.nom() + " » remis à neuf : plat et vide.")
					.withStyle(ChatFormatting.GREEN), true);
			}
		});
		return done.isDone() ? "" : "Remise à neuf du monde « " + e.nom() + " »...";
	}

	private static String delete(WorldManager w, CommandSourceStack src, String id) {
		WorldEntry e = w.get(id);
		CompletableFuture<Void> done = w.delete(e.id());
		done.whenComplete((ignored, error) -> {
			if (error != null) {
				src.sendFailure(Component.literal(failure(error)));
			} else {
				src.sendSuccess(() -> Component.literal("Monde « " + e.nom() + " » supprimé, fichiers compris.")
					.withStyle(ChatFormatting.GREEN), true);
			}
		});
		return done.isDone() ? "" : "Suppression du monde « " + e.nom() + " »...";
	}

	private static String rename(WorldManager w, String id, String name) {
		WorldEntry before = w.get(id);
		WorldEntry after = w.rename(before.id(), name);
		return "Monde « " + before.nom() + " » renommé « " + after.nom() + " » (identifiant inchangé : " + after.id() + ").";
	}
}
