package fr.valcombe.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.rules.GameException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Commandes de l'atelier carte, {@code /vc atelier carte ...} (réservées aux opérateurs, comme tout {@code /vc atelier} ;
 * l'écran de l'atelier, le menu « Mondes » et l'Atelier en jeu envoient les mêmes). Sans rien : l'écran de l'atelier où
 * l'on se tient, ou celui qui ouvre une carte.
 * <ul>
 * <li>{@code liste}, {@code aller <carte|atelier>} ;</li>
 * <li>{@code ouvrir <carte>} (de la bibliothèque ; déjà en atelier : on y va), {@code nouvelle <nom>} (carte vierge),
 * {@code village <trônes> [<taille> <thème> <maisons> <nom>]} (village généré), {@code importer <fichier> [nom]}
 * (fichier .schem, .litematic ou .nbt du dossier d'import, entre guillemets s'il contient des espaces) ;</li>
 * <li>dans un atelier : {@code enregistrer}, {@code enregistrer-sous <nom>}, {@code versions}, {@code revenir <version>},
 * {@code envoyer [monde]}, {@code fermer [confirmer]}, {@code outils} ;</li>
 * <li>d'où que l'on soit (Atelier en jeu) : {@code enregistrer <atelier>}, {@code fermer <atelier> [confirmer]}.</li>
 * </ul>
 * Les repères se posent avec les outils, ou avec {@code /vc carte trone|maison|centre|annuler|ordonner}, qui visent
 * l'atelier où l'on se tient.
 */
final class MapWorkshopCommands {
	@FunctionalInterface
	private interface Action {
		String run(ServerPlayer player, MapWorkshop workshop, CommandContext<CommandSourceStack> ctx) throws Exception;
	}

	private static final List<String> SIZES = List.of("petite", "normale", "grande");

	private MapWorkshopCommands() {
	}

	private static int exec(CommandContext<CommandSourceStack> ctx, Action action) {
		MapWorkshop w = ValcombeMod.mapWorkshop();
		ServerPlayer p = ctx.getSource().getPlayer();
		try {
			if (w == null) {
				throw new GameException("L'atelier carte n'est pas disponible sur ce serveur (les mondes de Valcombe non plus : voir son journal).");
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
			ValcombeMod.LOG.error("Erreur dans une commande /vc atelier carte", e);
			ctx.getSource().sendFailure(Component.literal("Erreur interne : " + e.getMessage()));
			return 0;
		}
	}

	private static String arg(CommandContext<CommandSourceStack> ctx, String key) {
		return StringArgumentType.getString(ctx, key);
	}

	private static String lines(CommandContext<CommandSourceStack> ctx, List<Component> lines) {
		for (Component line : lines) {
			ctx.getSource().sendSuccess(() -> line, false);
		}
		return "";
	}

	/** L'écran de l'atelier où l'on se tient, sinon celui qui ouvre une carte ; sans le mod, la liste dans le chat. */
	private static String screen(ServerPlayer p, MapWorkshop w, CommandContext<CommandSourceStack> ctx) {
		boolean inside = w.isAtelier(p.level());
		if (!ViewSync.hasClientMod(p)) {
			if (inside) {
				return w.info(p) + " Commandes : /vc atelier carte enregistrer, versions, envoyer, fermer.";
			}
			return lines(ctx, w.list(p));
		}
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p,
			new fr.valcombe.net.ValcombeNet.OpenPayload(inside ? "atelier-carte" : "atelier-carte-ouvrir"));
		return "";
	}

	private static int size(String text) {
		int k = SIZES.indexOf(text == null ? "" : text.strip().toLowerCase(Locale.ROOT));
		if (k < 0) {
			throw new GameException("Taille : petite, normale ou grande.");
		}
		return k;
	}

	private static boolean yes(String text) {
		String t = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
		if (t.equals("oui") || t.equals("avec")) {
			return true;
		}
		if (t.equals("non") || t.equals("sans")) {
			return false;
		}
		throw new GameException("Maisons : oui ou non.");
	}

	/** {@code carte ...}, à ajouter sous {@code /vc atelier} (déjà réservé aux opérateurs). */
	static LiteralArgumentBuilder<CommandSourceStack> build() {
		SuggestionProvider<CommandSourceStack> maps = (c, b) -> SharedSuggestionProvider.suggest(
			ValcombeMod.maps() == null ? List.of() : ValcombeMod.maps().list().stream().map(d -> d.id).toList(), b);
		SuggestionProvider<CommandSourceStack> ateliers = (c, b) -> {
			MapWorkshop w = ValcombeMod.mapWorkshop();
			List<String> all = new ArrayList<>();
			if (w != null) {
				for (WorldEntry e : w.ateliers()) {
					if (e.carte() != null) {
						all.add(e.carte());
					}
				}
			}
			return SharedSuggestionProvider.suggest(all, b);
		};
		SuggestionProvider<CommandSourceStack> atelierIds = (c, b) -> {
			MapWorkshop w = ValcombeMod.mapWorkshop();
			return SharedSuggestionProvider.suggest(w == null ? List.of() : w.ateliers().stream().map(WorldEntry::id).toList(), b);
		};
		SuggestionProvider<CommandSourceStack> parties = (c, b) -> {
			MapWorkshop w = ValcombeMod.mapWorkshop();
			return SharedSuggestionProvider.suggest(w == null ? List.of() : w.parties().stream().map(WorldEntry::id).toList(), b);
		};
		SuggestionProvider<CommandSourceStack> versions = (c, b) -> {
			MapWorkshop w = ValcombeMod.mapWorkshop();
			ServerPlayer p = c.getSource().getPlayer();
			WorldEntry e = w == null || p == null ? null : w.entry(p.level());
			List<String> ids = e == null || e.carte() == null ? List.of()
				: ValcombeMod.maps().versions().list(e.carte()).stream().map(MapVersions.Version::id).toList();
			return SharedSuggestionProvider.suggest(ids, b);
		};
		return Commands.literal("carte")
			.executes(c -> exec(c, (p, w, x) -> screen(p, w, x)))
			.then(Commands.literal("liste").executes(c -> exec(c, (p, w, x) -> lines(x, w.list(p)))))
			.then(Commands.literal("aller").then(Commands.argument("atelier", StringArgumentType.greedyString()).suggests(ateliers)
				.executes(c -> exec(c, (p, w, x) -> w.go(p, arg(x, "atelier"))))))
			.then(Commands.literal("ouvrir").then(Commands.argument("id", StringArgumentType.word()).suggests(maps)
				.executes(c -> exec(c, (p, w, x) -> w.openLibrary(arg(x, "id"), p, true)))))
			.then(Commands.literal("nouvelle").then(Commands.argument("nom", StringArgumentType.greedyString())
				.executes(c -> exec(c, (p, w, x) -> w.openBlank(arg(x, "nom"), p)))))
			.then(Commands.literal("village")
				.then(Commands.argument("trones", IntegerArgumentType.integer(Village.MIN_SEATS, Village.MAX_SEATS))
					.executes(c -> exec(c, (p, w, x) -> w.openVillage(IntegerArgumentType.getInteger(x, "trones"), 1, "gothique", true,
						null, p)))
					.then(Commands.argument("taille", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(SIZES, b))
						.then(Commands.argument("theme", StringArgumentType.word())
							.suggests((c, b) -> SharedSuggestionProvider.suggest(Village.THEMES, b))
							.then(Commands.argument("maisons", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("oui", "non"), b))
								.executes(c -> exec(c, (p, w, x) -> w.openVillage(IntegerArgumentType.getInteger(x, "trones"),
									size(arg(x, "taille")), arg(x, "theme"), yes(arg(x, "maisons")), null, p)))
								.then(Commands.argument("nom", StringArgumentType.greedyString())
									.executes(c -> exec(c, (p, w, x) -> w.openVillage(IntegerArgumentType.getInteger(x, "trones"),
										size(arg(x, "taille")), arg(x, "theme"), yes(arg(x, "maisons")), arg(x, "nom"), p)))))))))
			.then(Commands.literal("importer").then(Commands.argument("fichier", StringArgumentType.string())
				.suggests((c, b) -> SharedSuggestionProvider.suggest(ValcombeMod.structures() == null ? List.of()
					: ValcombeMod.structures().importable().stream().map(f -> f.matches("[A-Za-z0-9_.+-]+") ? f : "\"" + f + "\"").toList(), b))
				.executes(c -> exec(c, (p, w, x) -> w.openImport(arg(x, "fichier"), null, p)))
				.then(Commands.argument("nom", StringArgumentType.greedyString())
					.executes(c -> exec(c, (p, w, x) -> w.openImport(arg(x, "fichier"), arg(x, "nom"), p))))))
			.then(Commands.literal("enregistrer").executes(c -> exec(c, (p, w, x) -> w.save(p)))
				.then(Commands.argument("atelier", StringArgumentType.greedyString()).suggests(ateliers)
					.executes(c -> exec(c, (p, w, x) -> w.save(p, arg(x, "atelier"))))))
			.then(Commands.literal("enregistrer-sous").then(Commands.argument("nom", StringArgumentType.greedyString())
				.executes(c -> exec(c, (p, w, x) -> w.saveAs(p, arg(x, "nom"))))))
			.then(Commands.literal("versions").executes(c -> exec(c, (p, w, x) -> lines(x, w.versions(p)))))
			.then(Commands.literal("revenir").then(Commands.argument("version", StringArgumentType.word()).suggests(versions)
				.executes(c -> exec(c, (p, w, x) -> w.revert(p, arg(x, "version"))))))
			.then(Commands.literal("envoyer")
				.executes(c -> exec(c, (p, w, x) -> w.send(p, null)))
				.then(Commands.argument("monde", StringArgumentType.greedyString()).suggests(parties)
					.executes(c -> exec(c, (p, w, x) -> w.send(p, arg(x, "monde"))))))
			.then(Commands.literal("fermer")
				.executes(c -> exec(c, (p, w, x) -> w.close(p, false)))
				.then(Commands.literal("confirmer").executes(c -> exec(c, (p, w, x) -> w.close(p, true))))
				// Un atelier désigné (identifiant de l'atelier ou de sa carte), d'où que l'on soit : l'Atelier en jeu.
				.then(Commands.argument("atelier", StringArgumentType.word()).suggests(atelierIds)
					.executes(c -> exec(c, (p, w, x) -> w.close(p, arg(x, "atelier"), false)))
					.then(Commands.literal("confirmer").executes(c -> exec(c, (p, w, x) -> w.close(p, arg(x, "atelier"), true))))))
			.then(Commands.literal("infos").executes(c -> exec(c, (p, w, x) -> w.info(p))))
			.then(Commands.literal("outils").executes(c -> exec(c, (p, w, x) -> {
				if (!w.buildsHere(p)) {
					throw new GameException("Les outils de repères servent dans un atelier carte : allez-y d'abord.");
				}
				ValcombeUi.mapTools(p, true);
				return "Outils de repères en main : trône, maison, centre, salle de convocation, gomme, structure et main.";
			})));
	}
}
