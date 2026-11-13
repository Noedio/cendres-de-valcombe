package fr.valcombe.mc;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import fr.valcombe.mc.worlds.BuildAccess;
import fr.valcombe.rules.GameException;

import java.util.List;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Commandes de l'atelier structures, {@code /vc structures ...} (l'écran de l'atelier et ses pupitres envoient les
 * mêmes). Sans identifiant, une action vise la parcelle où l'on se tient (bordure et bout d'allée compris), sinon la
 * dernière choisie à un pupitre.
 * <ul>
 * <li>{@code atelier} : aller dans l'atelier (créé et disposé à la première demande) ;</li>
 * <li>{@code liste}, {@code aller <id>}, {@code choisir <id>} ;</li>
 * <li>{@code enregistrer [id]}, {@code originale [id]}, {@code reposer [id]}, {@code tourner [id]} ;</li>
 * <li>{@code renommer <nom>}, {@code repere <maison|salle|aucun>}, {@code supprimer [confirmer]} ;</li>
 * <li>{@code nouvelle <petite|moyenne|grande|immense> <nom>}.</li>
 * </ul>
 * Droits : {@link BuildAccess} (opérateurs, et leurs invités dans l'atelier). Un invité voit ces commandes partout,
 * comme les autres commandes de construction ; elles ne servent que dans l'atelier.
 */
final class StructureWorkshopCommands {
	@FunctionalInterface
	private interface Action {
		String run(ServerPlayer player, StructureWorkshop workshop, CommandContext<CommandSourceStack> ctx) throws Exception;
	}

	private StructureWorkshopCommands() {
	}

	private static int exec(CommandContext<CommandSourceStack> ctx, Action action) {
		StructureWorkshop w = ValcombeMod.structureWorkshop();
		ServerPlayer p = ctx.getSource().getPlayer();
		try {
			if (w == null) {
				throw new GameException("L'atelier structures n'est pas disponible sur ce serveur (les mondes de Valcombe non plus : voir son journal).");
			}
			if (p == null) {
				throw new GameException("Commande à lancer en jeu, par un joueur.");
			}
			String result = action.run(p, w, ctx);
			if (result != null && !result.isEmpty()) {
				ctx.getSource().sendSuccess(() -> Component.literal(result), false);
				w.note(p, result, false);
			}
			return 1;
		} catch (GameException e) {
			ctx.getSource().sendFailure(Component.literal(e.getMessage()));
			if (w != null && p != null) {
				w.note(p, e.getMessage(), true);
			}
			return 0;
		} catch (Exception e) {
			ValcombeMod.LOG.error("Erreur dans une commande /vc structures", e);
			ctx.getSource().sendFailure(Component.literal("Erreur interne : " + e.getMessage()));
			return 0;
		}
	}

	private static String arg(CommandContext<CommandSourceStack> ctx, String key) {
		return StringArgumentType.getString(ctx, key);
	}

	/** Qui voit ces commandes : un joueur qui peut construire là où il est, ou un opérateur. */
	private static boolean allowed(CommandSourceStack src) {
		ServerPlayer p = src.getPlayer();
		if (p == null) {
			return false;
		}
		BuildAccess access = BuildAccess.current();
		return BuildAccess.operator(p) || access != null && access.guestSomewhere(p);
	}

	/** Une action qui vise une parcelle : sans argument (la parcelle où l'on est), ou suivie de son identifiant. */
	private static LiteralArgumentBuilder<CommandSourceStack> onPlot(String literal, SuggestionProvider<CommandSourceStack> ids,
			PlotAction action) {
		return Commands.literal(literal)
			.executes(c -> exec(c, (p, w, x) -> action.run(w, p, null)))
			.then(Commands.argument("id", StringArgumentType.word()).suggests(ids)
				.executes(c -> exec(c, (p, w, x) -> action.run(w, p, arg(x, "id")))));
	}

	@FunctionalInterface
	private interface PlotAction {
		String run(StructureWorkshop w, ServerPlayer p, String id);
	}

	/** /vc structures ... */
	static LiteralArgumentBuilder<CommandSourceStack> build() {
		SuggestionProvider<CommandSourceStack> ids = (c, b) -> {
			StructureWorkshop w = ValcombeMod.structureWorkshop();
			return SharedSuggestionProvider.suggest(w == null ? List.of() : w.parcels().stream().map(StructureWorkshop.ParcelInfo::id).toList(), b);
		};
		return Commands.literal("structures").requires(StructureWorkshopCommands::allowed)
			.then(Commands.literal("atelier").executes(c -> exec(c, (p, w, x) -> w.go(p))))
			.then(Commands.literal("liste").executes(c -> exec(c, (p, w, x) -> {
				w.list(p).forEach(line -> x.getSource().sendSuccess(() -> line, false));
				return "";
			})))
			.then(Commands.literal("aller").then(Commands.argument("id", StringArgumentType.word()).suggests(ids)
				.executes(c -> exec(c, (p, w, x) -> w.visit(p, arg(x, "id"))))))
			.then(Commands.literal("choisir").then(Commands.argument("id", StringArgumentType.word()).suggests(ids)
				.executes(c -> exec(c, (p, w, x) -> w.choose(p, arg(x, "id"))))))
			.then(onPlot("enregistrer", ids, StructureWorkshop::save))
			.then(onPlot("originale", ids, StructureWorkshop::revert))
			.then(onPlot("reposer", ids, StructureWorkshop::reset))
			.then(onPlot("tourner", ids, StructureWorkshop::rotate))
			.then(Commands.literal("renommer").then(Commands.argument("nom", StringArgumentType.greedyString())
				.executes(c -> exec(c, (p, w, x) -> w.rename(p, null, arg(x, "nom"))))))
			.then(Commands.literal("repere").then(Commands.argument("repere", StringArgumentType.word())
				.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("maison", "salle", "aucun"), b))
				.executes(c -> exec(c, (p, w, x) -> w.mark(p, null, arg(x, "repere"))))))
			.then(Commands.literal("supprimer")
				.executes(c -> exec(c, (p, w, x) -> w.delete(p, null, false)))
				.then(Commands.literal("confirmer").executes(c -> exec(c, (p, w, x) -> w.delete(p, null, true)))))
			.then(Commands.literal("nouvelle").then(Commands.argument("taille", StringArgumentType.word())
				.suggests((c, b) -> SharedSuggestionProvider.suggest(StructureWorkshop.SIZES, b))
				.then(Commands.argument("nom", StringArgumentType.greedyString())
					.executes(c -> exec(c, (p, w, x) -> w.create(p, arg(x, "taille"), arg(x, "nom")))))));
	}
}
