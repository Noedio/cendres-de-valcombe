package fr.valcombe.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.session.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/** Commandes /vc (alias /valcombe). Les boutons du chat lancent ces mêmes commandes. */
public final class ValcombeCommands {
	@FunctionalInterface
	private interface Action {
		String run(CommandContext<CommandSourceStack> ctx) throws Exception;
	}

	private ValcombeCommands() {
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
			ValcombeMod.LOG.error("Erreur dans une commande /vc", e);
			ctx.getSource().sendFailure(Component.literal("Erreur interne : " + e.getMessage()));
			return 0;
		}
	}

	private static String name(CommandContext<CommandSourceStack> ctx) throws Exception {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		return p.getScoreboardName();
	}

	private static String arg(CommandContext<CommandSourceStack> ctx, String key) {
		return StringArgumentType.getString(ctx, key);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Session session, ValcombeUi ui) {
		Predicate<CommandSourceStack> admin = src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
		// Le Conteur (même sans être opérateur) ou un opérateur mène la partie.
		Predicate<CommandSourceStack> leader = src -> admin.test(src)
			|| (src.getPlayer() != null && src.getPlayer().getScoreboardName().equals(session.storyteller()));

		SuggestionProvider<CommandSourceStack> playerNames = (ctx, builder) -> {
			List<String> names = new ArrayList<>();
			Game g = session.game();
			if (g != null) {
				for (Game.Seat s : g.players()) {
					names.add(s.name);
				}
			} else {
				names.addAll(session.lobby());
			}
			return SharedSuggestionProvider.suggest(names, builder);
		};

		SuggestionProvider<CommandSourceStack> roleNames = (ctx, builder) -> SharedSuggestionProvider.suggest(
			java.util.Arrays.stream(fr.valcombe.rules.Role.values()).map(r -> r.name().toLowerCase()).toList(), builder);

		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("vc")
			.executes(c -> exec(c, x -> {
				ui.openMain(x.getSource().getPlayerOrException());
				return "";
			}))
			.then(Commands.literal("menu").executes(c -> exec(c, x -> {
				ServerPlayer p = x.getSource().getPlayerOrException();
				ui.giveMenuItem(p);
				ui.openMain(p);
				return "";
			})))
			.then(Commands.literal("conteurpanneau").requires(leader).executes(c -> exec(c, x -> {
				ui.openStoryteller(x.getSource().getPlayerOrException());
				return "";
			})))
			.then(Commands.literal("attribuer").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.then(Commands.argument("role", StringArgumentType.word()).suggests(roleNames)
						.executes(c -> exec(c, x -> session.assignRole(arg(x, "joueur"), arg(x, "role"), null)))
						.then(Commands.argument("cru", StringArgumentType.word()).suggests(roleNames)
							.executes(c -> exec(c, x -> session.assignRole(arg(x, "joueur"), arg(x, "role"), arg(x, "cru"))))))))
			.then(Commands.literal("presage").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> session.setRedHerring(arg(x, "joueur"))))))
			.then(Commands.literal("bluffs").requires(leader)
				.then(Commands.argument("r1", StringArgumentType.word()).suggests(roleNames)
					.then(Commands.argument("r2", StringArgumentType.word()).suggests(roleNames)
						.then(Commands.argument("r3", StringArgumentType.word()).suggests(roleNames)
							.executes(c -> exec(c, x -> session.setBluffs(List.of(arg(x, "r1"), arg(x, "r2"), arg(x, "r3")))))))))
			.then(Commands.literal("note").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> session.note(arg(x, "joueur"), null)))
					.then(Commands.argument("texte", StringArgumentType.greedyString())
						.executes(c -> exec(c, x -> session.note(arg(x, "joueur"), arg(x, "texte")))))))
			.then(Commands.literal("consigne").requires(leader)
				.then(Commands.argument("cle", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("enregistrement", "chatelain", "successeur"), b))
					.then(Commands.argument("valeur", StringArgumentType.word())
						.executes(c -> exec(c, x -> session.policy(arg(x, "cle"), arg(x, "valeur")))))))
			.then(Commands.literal("script").requires(leader)
				.then(Commands.literal("basculer")
					.then(Commands.argument("role", StringArgumentType.word()).suggests(roleNames)
						.executes(c -> exec(c, x -> session.toggleScriptRole(arg(x, "role"))))))
				.then(Commands.literal("complet").executes(c -> exec(c, x -> session.useScript("Valcombe (complet)",
					java.util.EnumSet.allOf(fr.valcombe.rules.Role.class)))))
				.then(Commands.literal("sauver")
					.then(Commands.argument("nom", StringArgumentType.greedyString()).executes(c -> exec(c, x -> {
						var server = x.getSource().getServer();
						ScriptStore.save(server, arg(x, "nom"), session.script());
						session.setSavedScripts(ScriptStore.list(server));
						return "Script enregistré.";
					}))))
				.then(Commands.literal("charger")
					.then(Commands.argument("nom", StringArgumentType.greedyString())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(ScriptStore.list(c.getSource().getServer()), b))
						.executes(c -> exec(c, x -> session.useScript(arg(x, "nom"),
							ScriptStore.load(x.getSource().getServer(), arg(x, "nom")))))))
				.then(Commands.literal("supprimer")
					.then(Commands.argument("nom", StringArgumentType.greedyString())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(ScriptStore.list(c.getSource().getServer()), b))
						.executes(c -> exec(c, x -> {
							var server = x.getSource().getServer();
							ScriptStore.delete(server, arg(x, "nom"));
							session.setSavedScripts(ScriptStore.list(server));
							return "Script supprimé.";
						})))))
			.then(Commands.literal("carte").requires(admin)
				.then(Commands.literal("construire")
					.executes(c -> exec(c, x -> ui.host().buildVillage(x.getSource().getPlayerOrException()))))
				.then(Commands.literal("oublier").executes(c -> exec(c, x -> ui.host().forgetVillage())))
				.then(Commands.literal("nouvelle")
					.executes(c -> exec(c, x -> ui.host().customMap(x.getSource().getPlayerOrException()))))
				.then(Commands.literal("siege")
					.executes(c -> exec(c, x -> ui.host().addSeat(x.getSource().getPlayerOrException()))))
				.then(Commands.literal("maison")
					.executes(c -> exec(c, x -> ui.host().addHouse(x.getSource().getPlayerOrException()))))
				.then(Commands.literal("retirer")
					.then(Commands.literal("siege").executes(c -> exec(c, x -> ui.host().removeSeat())))
					.then(Commands.literal("maison").executes(c -> exec(c, x -> ui.host().removeHouse()))))
				.then(Commands.literal("infos").executes(c -> exec(c, x -> ui.host().mapInfo())))
				.then(Commands.literal("salle").executes(c -> exec(c, x -> ui.host().buildHall()))))
			.then(Commands.literal("bots").requires(leader)
				.then(Commands.literal("retirer").executes(c -> exec(c, x -> session.removeBots())))
				.then(Commands.argument("nombre", IntegerArgumentType.integer(1, 15))
					.executes(c -> exec(c, x -> session.addBots(IntegerArgumentType.getInteger(x, "nombre"))))))
			.then(Commands.literal("rejoindre").executes(c -> exec(c, x -> session.join(name(x)))))
			.then(Commands.literal("quitter").executes(c -> exec(c, x -> session.leave(name(x)))))
			.then(Commands.literal("joueurs").executes(c -> exec(c, x -> session.listPlayers())))
			.then(Commands.literal("conteur").requires(admin)
				.executes(c -> exec(c, x -> session.toggleStoryteller(name(x)))))
			.then(Commands.literal("lancer").requires(leader)
				.executes(c -> exec(c, x -> session.start(x.getSource().getTextName(), Session.Mode.AUTO)))
				.then(Commands.literal("auto")
					.executes(c -> exec(c, x -> session.start(x.getSource().getTextName(), Session.Mode.AUTO))))
				.then(Commands.literal("humain")
					.executes(c -> exec(c, x -> session.start(x.getSource().getTextName(), Session.Mode.HUMAIN)))))
			.then(Commands.literal("arreter").requires(leader).executes(c -> exec(c, x -> session.stop())))
			.then(Commands.literal("nuit").requires(leader).executes(c -> exec(c, x -> session.startNight())))
			.then(Commands.literal("finjour").requires(leader).executes(c -> exec(c, x -> session.endDay())))
			.then(Commands.literal("suivant").requires(leader).executes(c -> exec(c, x -> session.skip())))
			.then(Commands.literal("nominer")
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> session.nominate(name(x), arg(x, "joueur"))))))
			.then(Commands.literal("chuchoter")
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.then(Commands.argument("message", StringArgumentType.greedyString())
						.executes(c -> exec(c, x -> session.whisper(name(x), arg(x, "joueur"), arg(x, "message")))))))
			.then(Commands.literal("nominations").requires(leader)
				.then(Commands.literal("ouvrir").executes(c -> exec(c, x -> session.setNominations(true))))
				.then(Commands.literal("fermer").executes(c -> exec(c, x -> session.setNominations(false)))))
			.then(Commands.literal("rassembler").requires(leader).executes(c -> exec(c, x -> session.gather())))
			.then(Commands.literal("voter").requires(leader).executes(c -> exec(c, x -> session.startVoteNow())))
			.then(Commands.literal("tuer").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> session.stKill(arg(x, "joueur"))))))
			.then(Commands.literal("ressusciter").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> session.stRevive(arg(x, "joueur"))))))
			.then(Commands.literal("empoisonner").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> session.stPoison(arg(x, "joueur"))))))
			.then(Commands.literal("changer").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.then(Commands.argument("role", StringArgumentType.word()).suggests(roleNames)
						.executes(c -> exec(c, x -> session.stSwap(arg(x, "joueur"), arg(x, "role"), null)))
						.then(Commands.argument("cru", StringArgumentType.word()).suggests(roleNames)
							.executes(c -> exec(c, x -> session.stSwap(arg(x, "joueur"), arg(x, "role"), arg(x, "cru"))))))))
			.then(Commands.literal("tirer")
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> session.shoot(name(x), arg(x, "joueur"))))))
			.then(Commands.literal("choisir")
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> session.choose(name(x), arg(x, "joueur"))))))
			.then(Commands.literal("valider").executes(c -> exec(c, x -> session.validate(name(x)))))
			.then(Commands.literal("decider")
				.then(Commands.argument("choix", IntegerArgumentType.integer(0, 30))
					.executes(c -> exec(c, x -> session.decide(name(x), IntegerArgumentType.getInteger(x, "choix"))))))
			.then(Commands.literal("dormir").executes(c -> exec(c, x -> session.sleep(name(x)))))
			.then(Commands.literal("envoyer")
				.executes(c -> exec(c, x -> session.storytellerSend(name(x), null)))
				.then(Commands.argument("texte", StringArgumentType.greedyString())
					.executes(c -> exec(c, x -> session.storytellerSend(name(x), arg(x, "texte"))))))
			.then(Commands.literal("vote")
				.then(Commands.literal("oui").executes(c -> exec(c, x -> session.vote(name(x), true))))
				.then(Commands.literal("non").executes(c -> exec(c, x -> session.vote(name(x), false)))))
			.then(Commands.literal("role").executes(c -> exec(c, x -> session.role(name(x)))))
			.then(Commands.literal("grimoire").executes(c -> exec(c, x -> session.grimoire(name(x)))))
			.then(Commands.literal("fiches").executes(c -> exec(c, x -> session.sheets(name(x)))))
			.then(Commands.literal("historique").executes(c -> exec(c, x -> session.history(name(x)))))
			.then(Commands.literal("mp")
				.then(Commands.argument("message", StringArgumentType.greedyString())
					.executes(c -> exec(c, x -> session.messageStoryteller(name(x), arg(x, "message"))))))
			.then(Commands.literal("reglage").requires(leader)
				.then(Commands.argument("cle", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(
						List.of("nuit_immobile", "nuit_sombre", "nuit_maisons", "nuit_muette", "temps_defense", "temps_horloge"), b))
					.then(Commands.argument("valeur", StringArgumentType.word())
						.executes(c -> exec(c, x -> session.setting(arg(x, "cle"), arg(x, "valeur")))))));

		var node = dispatcher.register(root);
		dispatcher.register(Commands.literal("valcombe").redirect(node));
	}
}
