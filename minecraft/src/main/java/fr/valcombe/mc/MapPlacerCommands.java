package fr.valcombe.mc;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldLookup;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.GameException;

import java.util.List;
import java.util.concurrent.CompletionException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;

/**
 * Commandes opérateur de test des cartes dans les mondes (sous /vc carte, déjà réservé aux opérateurs) :
 * {@code poser <id> <monde>} pose une carte de la bibliothèque au centre d'un monde de Valcombe, sur le sol plat ;
 * {@code capturer <monde> <id> [nom]} range dans la bibliothèque la carte que porte un monde. Le monde se désigne
 * par son identifiant ou son nom, même approché (voir WorldLookup).
 */
final class MapPlacerCommands {
	@FunctionalInterface
	private interface Action {
		String run(CommandContext<CommandSourceStack> ctx, MapPlacer placer, WorldManager worlds) throws Exception;
	}

	private MapPlacerCommands() {
	}

	private static int exec(CommandContext<CommandSourceStack> ctx, Action action) {
		try {
			MapPlacer placer = ValcombeMod.placer();
			WorldManager worlds = ValcombeMod.worlds();
			if (placer == null || worlds == null) {
				throw new GameException("Les mondes de Valcombe ne sont pas disponibles sur ce serveur (voir son journal).");
			}
			String result = action.run(ctx, placer, worlds);
			if (result != null && !result.isEmpty()) {
				ctx.getSource().sendSuccess(() -> Component.literal(result), false);
			}
			return 1;
		} catch (GameException e) {
			ctx.getSource().sendFailure(Component.literal(e.getMessage()));
			return 0;
		} catch (Exception e) {
			ValcombeMod.LOG.error("Erreur dans une commande /vc carte", e);
			ctx.getSource().sendFailure(Component.literal("Erreur interne : " + e.getMessage()));
			return 0;
		}
	}

	private static String arg(CommandContext<CommandSourceStack> ctx, String key) {
		return StringArgumentType.getString(ctx, key);
	}

	private static final SuggestionProvider<CommandSourceStack> MAPS = (c, b) -> SharedSuggestionProvider.suggest(
		ValcombeMod.maps() == null ? List.of() : ValcombeMod.maps().list().stream().map(d -> d.id).toList(), b);

	private static final SuggestionProvider<CommandSourceStack> WORLDS = (c, b) -> {
		WorldManager w = ValcombeMod.worlds();
		return SharedSuggestionProvider.suggest(w == null ? List.of() : w.list().stream().map(WorldEntry::id).toList(), b);
	};

	/** {@code poser <id> <monde>} : le monde en dernier, pour accepter un nom de plusieurs mots. */
	static LiteralArgumentBuilder<CommandSourceStack> place() {
		return Commands.literal("poser")
			.then(Commands.argument("id", StringArgumentType.word()).suggests(MAPS)
				.then(Commands.argument("monde", StringArgumentType.greedyString()).suggests(WORLDS)
					.executes(c -> exec(c, (x, placer, worlds) -> place(x.getSource(), placer, worlds, arg(x, "id"), arg(x, "monde"))))));
	}

	/** {@code capturer <monde> <id> [nom]} : un monde au nom de plusieurs mots se donne entre guillemets. */
	static LiteralArgumentBuilder<CommandSourceStack> capture() {
		return Commands.literal("capturer")
			.then(Commands.argument("monde", StringArgumentType.string()).suggests(WORLDS)
				.then(Commands.argument("id", StringArgumentType.word())
					.executes(c -> exec(c, (x, placer, worlds) -> capture(placer, worlds, arg(x, "monde"), arg(x, "id"), null)))
					.then(Commands.argument("nom", StringArgumentType.greedyString())
						.executes(c -> exec(c, (x, placer, worlds) -> capture(placer, worlds, arg(x, "monde"), arg(x, "id"),
							arg(x, "nom")))))));
	}

	/** Le message d'une erreur arrivée pendant une opération en plusieurs ticks. */
	private static String failure(Throwable t) {
		Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
		return c instanceof GameException ? c.getMessage() : "Erreur interne : " + c.getMessage();
	}

	private static String counts(PlacedMap map) {
		int seats = map.marks().seats().size();
		int houses = map.marks().houses().size();
		return seats + (seats > 1 ? " trônes, " : " trône, ") + houses + (houses > 1 ? " maisons" : " maison")
			+ (map.marks().convocation() != null ? ", salle de convocation" : "");
	}

	private static String place(CommandSourceStack src, MapPlacer placer, WorldManager worlds, String mapId, String world) {
		WorldEntry e = WorldLookup.resolve(worlds.list(), world);
		ServerLevel level = worlds.level(e.id());
		MapPlacer.Placement p = placer.place(mapId, level, new net.minecraft.core.BlockPos(0, WorldManager.SURFACE_Y, 0), src.getPlayer());
		PlacedMap map = p.map();
		p.done().whenComplete((placed, error) -> {
			if (error != null) {
				src.sendFailure(Component.literal(failure(error)));
				return;
			}
			String goTo = "/vc monde aller " + e.id();
			src.sendSuccess(() -> Component.literal("Carte « " + placed.nom() + " » posée dans « " + e.nom() + " » : " + counts(placed)
				+ ", place en " + placed.center().toShortString() + ".").withStyle(ChatFormatting.GREEN)
				.withStyle(st -> st.withClickEvent(new ClickEvent.RunCommand(goTo))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Cliquer pour y aller.")))), true);
		});
		return p.done().isDone() ? "" : "Pose de la carte « " + map.nom() + " » dans « " + e.nom() + " » lancée (" + counts(map) + ")."
			+ McHost.PROGRESS;
	}

	private static String capture(MapPlacer placer, WorldManager worlds, String world, String id, String nom) {
		WorldEntry e = WorldLookup.resolve(worlds.list(), world);
		MapPlacer.Capture c = placer.capture(worlds.level(e.id()), id, nom);
		PlacedMap map = c.map();
		int side = 2 * map.rayon() + 1;
		return "Carte « " + map.nom() + " » capturée dans « " + e.nom() + " » et rangée dans la bibliothèque (identifiant « " + map.mapId()
			+ " ») : " + counts(map) + ", zone de " + side + " × " + map.hauteur() + " × " + side + " blocs."
			+ (c.replaced() ? " Elle remplace la carte qui portait déjà cet identifiant." : "")
			+ (c.cutTop() ? " Des blocs montaient plus haut que " + map.hauteur() + " blocs : ils ne sont pas rangés." : "");
	}
}
