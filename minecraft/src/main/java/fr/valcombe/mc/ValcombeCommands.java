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

	private static String lines(CommandContext<CommandSourceStack> ctx, List<String> lines) {
		for (String line : lines) {
			ctx.getSource().sendSuccess(() -> Component.literal(line), false);
		}
		return "";
	}

	private static String arg(CommandContext<CommandSourceStack> ctx, String key) {
		return StringArgumentType.getString(ctx, key);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Session session, ValcombeUi ui) {
		Predicate<CommandSourceStack> admin = src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
		// Le Conteur (même sans être opérateur) ou un opérateur mène la partie.
		Predicate<CommandSourceStack> leader = src -> admin.test(src)
			|| (src.getPlayer() != null && (src.getPlayer().getScoreboardName().equals(session.storyteller())
				|| session.isTrainee(src.getPlayer().getScoreboardName())));

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
			fr.valcombe.rules.Role.all().stream().map(r -> r.custom() ? r.customId : r.name().toLowerCase()).toList(), builder);

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
				.then(Commands.literal("complet").executes(c -> exec(c, x -> session.useScript("Les Cendres de Valcombe",
					fr.valcombe.rules.Role.classic()))))
				.then(Commands.literal("brumes").executes(c -> exec(c, x -> session.useScript("Les Brumes de Valcombe",
					fr.valcombe.rules.Role.brumes()))))
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
					.executes(c -> exec(c, x -> ui.host().buildVillage(x.getSource().getPlayerOrException())))
					.then(Commands.argument("theme", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(Village.THEMES, b))
						.executes(c -> exec(c, x -> ui.host().buildVillage(x.getSource().getPlayerOrException(),
							arg(x, "theme"))))))
				.then(Commands.literal("importer")
					.then(Commands.argument("structure", StringArgumentType.greedyString())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(ui.host().structures(), b))
						.executes(c -> exec(c, x -> ui.host().importStructure(x.getSource().getPlayerOrException(),
							arg(x, "structure"))))))
				.then(Commands.literal("baguette").executes(c -> exec(c, x -> {
					x.getSource().getPlayerOrException().getInventory().add(ValcombeUi.wandItem());
					return "Baguette de carte : clic droit sur un bloc pour y poser un siège, accroupi pour une maison.";
				})))
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
				.then(Commands.literal("construction")
					.executes(c -> exec(c, x -> ui.host().toggleConstruction(x.getSource().getPlayerOrException()))))
				.then(Commands.literal("centre")
					.executes(c -> exec(c, x -> ui.host().setMapCenter(x.getSource().getPlayerOrException()))))
				.then(Commands.literal("liste").executes(c -> exec(c, x -> {
					var list = ValcombeMod.maps().list();
					return list.isEmpty() ? "Aucune carte enregistrée : /vc carte enregistrer <id> <nom>."
						: "Cartes : " + String.join(", ", list.stream().map(d -> d.id + " (« " + d.nom + " », "
							+ d.sieges.size() + " trônes)").toList());
				})))
				.then(Commands.literal("enregistrer")
					.then(Commands.argument("id", StringArgumentType.word())
						.executes(c -> exec(c, x -> ValcombeMod.maps().saveCurrent(arg(x, "id"), arg(x, "id"))))
						.then(Commands.argument("nom", StringArgumentType.greedyString())
							.executes(c -> exec(c, x -> ValcombeMod.maps().saveCurrent(arg(x, "id"), arg(x, "nom")))))))
				.then(Commands.literal("charger")
					.then(Commands.argument("id", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(ValcombeMod.maps().list().stream().map(d -> d.id), b))
						.executes(c -> exec(c, x -> ValcombeMod.maps().load(arg(x, "id"))))))
				.then(Commands.literal("supprimer")
					.then(Commands.argument("id", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(ValcombeMod.maps().list().stream().map(d -> d.id), b))
						.executes(c -> exec(c, x -> ValcombeMod.maps().delete(arg(x, "id"))))))
				.then(Commands.literal("generer")
					.then(Commands.argument("places", IntegerArgumentType.integer(5, 20))
						.executes(c -> exec(c, x -> ui.host().generateVillage(x.getSource().getPlayerOrException(),
							IntegerArgumentType.getInteger(x, "places"), 1, "gothique", true)))
						.then(Commands.argument("taille", StringArgumentType.word())
							.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("petite", "normale", "grande"), b))
							.then(Commands.argument("theme", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(Village.THEMES, b))
								.then(Commands.argument("maisons", StringArgumentType.word())
									.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("oui", "non"), b))
									.executes(c -> exec(c, x -> ui.host().generateVillage(x.getSource().getPlayerOrException(),
										IntegerArgumentType.getInteger(x, "places"),
										List.of("petite", "normale", "grande").indexOf(arg(x, "taille")),
										arg(x, "theme"), !arg(x, "maisons").equals("non")))))))))
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
			.then(Commands.literal("aparte").requires(leader)
				.then(Commands.literal("fin").executes(c -> exec(c, x -> session.setAside(null))))
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> session.setAside(arg(x, "joueur"))))))
			.then(Commands.literal("appeler").executes(c -> exec(c, x -> session.callStoryteller(name(x)))))
			.then(Commands.literal("annoncer")
				.then(Commands.argument("role", StringArgumentType.word()).suggests(roleNames)
					.executes(c -> exec(c, x -> session.claim(name(x), arg(x, "role"))))))
			.then(Commands.literal("signe")
				.then(Commands.argument("signe", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(
						List.of("oui", "non", "question", "attention", "reflexion", "accord"), b))
					.executes(c -> exec(c, x -> session.sign(name(x), arg(x, "signe"))))))
			.then(Commands.literal("parler")
				.then(Commands.literal("accepter").executes(c -> exec(c, x -> session.answerTalk(name(x), true))))
				.then(Commands.literal("refuser").executes(c -> exec(c, x -> session.answerTalk(name(x), false))))
				.then(Commands.literal("fin").executes(c -> exec(c, x -> session.endTalk(name(x)))))
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> session.inviteTalk(name(x), arg(x, "joueur"))))))
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
			.then(Commands.literal("rappel").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.then(Commands.argument("texte", StringArgumentType.greedyString())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(
							List.of("ivre", "empoisonné", "protégé", "fausse piste", "a choisi", "effacer"), b))
						.executes(c -> exec(c, x -> session.reminder(arg(x, "joueur"), arg(x, "texte")))))))
			.then(Commands.literal("discussion").requires(leader)
				.then(Commands.argument("minutes", IntegerArgumentType.integer(0, 30))
					.executes(c -> exec(c, x -> session.discussion(IntegerArgumentType.getInteger(x, "minutes"))))))
			.then(Commands.literal("spectateur").executes(c -> exec(c, x -> session.spectate(name(x)))))
			.then(Commands.literal("entrainement").executes(c -> exec(c, x -> session.training(name(x)))))
			.then(Commands.literal("stats")
				.executes(c -> exec(c, x -> lines(x, ValcombeMod.community().statsLines(null))))
				.then(Commands.argument("joueur", StringArgumentType.word())
					.executes(c -> exec(c, x -> lines(x, ValcombeMod.community().statsLines(arg(x, "joueur")))))))
			.then(Commands.literal("discord").requires(admin)
				.then(Commands.literal("essai").executes(c -> exec(c, x -> {
					CommandSourceStack src = x.getSource();
					ValcombeMod.community().test().whenComplete((ok, err) -> src.getServer().execute(() -> {
						if (err == null) {
							src.sendSuccess(() -> Component.literal("Message d'essai envoyé sur Discord."), false);
						} else {
							src.sendFailure(Component.literal("Échec de l'envoi : " + err.getMessage()));
						}
					}));
					return "Envoi du message d'essai…";
				})))
				.then(Commands.argument("adresse", StringArgumentType.greedyString())
					.executes(c -> exec(c, x -> ValcombeMod.community().setWebhook(arg(x, "adresse"))))))
			.then(Commands.literal("atelier").requires(admin)
				.executes(c -> exec(c, x -> {
					ServerPlayer p = x.getSource().getPlayerOrException();
					List<String> links = ValcombeMod.tablet().openAtelier(p.getScoreboardName());
					p.sendSystemMessage(Component.literal("Atelier ouvert : créez des rôles et des scénarios, gérez les cartes. "
						+ "Ouvrez ce lien sur un ordinateur du même réseau — ne le partagez pas :")
						.withStyle(net.minecraft.ChatFormatting.GOLD));
					for (String link : links) {
						p.sendSystemMessage(Component.literal("  " + link).withStyle(st -> st
							.withColor(net.minecraft.ChatFormatting.AQUA).withUnderlined(true)
							.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(link)))));
					}
					return "Fermer : /vc tablette fermer.";
				})))
			.then(Commands.literal("soiree").requires(admin)
				.then(Commands.literal("effacer").executes(c -> exec(c, x -> ValcombeMod.community().setNextEvening(""))))
				.then(Commands.argument("quand", StringArgumentType.greedyString())
					.executes(c -> exec(c, x -> ValcombeMod.community().setNextEvening(arg(x, "quand"))))))
			.then(Commands.literal("scenario").requires(leader)
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(
						java.util.stream.Stream.concat(java.util.stream.Stream.of("aucun"),
							session.scenarios().stream().map(d -> d.id)), b))
					.executes(c -> exec(c, x -> session.useScenario(arg(x, "id"))))))
			.then(Commands.literal("tablette").requires(leader)
				.executes(c -> exec(c, x -> {
					ServerPlayer p = x.getSource().getPlayerOrException();
					Tablet t = ValcombeMod.tablet();
					t.open(p.getScoreboardName());
					p.sendSystemMessage(Component.literal("Grimoire sur tablette ouvert. Ouvrez ce lien sur votre tablette ou téléphone "
						+ "(même réseau, ou port " + ValcombeMod.community().tabletPort() + " ouvert) — ne le partagez pas :")
						.withStyle(net.minecraft.ChatFormatting.GOLD));
					for (String link : t.links()) {
						p.sendSystemMessage(Component.literal("  " + link).withStyle(st -> st
							.withColor(net.minecraft.ChatFormatting.AQUA).withUnderlined(true)
							.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(link)))));
					}
					return "Fermer : /vc tablette fermer. Nouveau lien (l'ancien ne marche plus) : /vc tablette";
				}))
				.then(Commands.literal("fermer").executes(c -> exec(c, x -> {
					ValcombeMod.tablet().close();
					return "Tablette fermée : le lien ne fonctionne plus.";
				})))
				.then(Commands.literal("internet").requires(admin)
					.then(Commands.literal("oui").executes(c -> exec(c, x -> ValcombeMod.community().setTabletInternet(true))))
					.then(Commands.literal("non").executes(c -> exec(c, x -> ValcombeMod.community().setTabletInternet(false)))))
				.then(Commands.literal("port").requires(admin)
					.then(Commands.argument("port", IntegerArgumentType.integer(1024, 65535))
						.executes(c -> exec(c, x -> ValcombeMod.community().setTabletPort(IntegerArgumentType.getInteger(x, "port")))))))
			.then(Commands.literal("reglage").requires(leader)
				.then(Commands.argument("cle", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(
						List.of("nuit_immobile", "nuit_sombre", "nuit_maisons", "nuit_muette", "temps_defense", "temps_horloge",
							"effets", "spectateurs_grimoire", "coiffe"), b))
					.then(Commands.argument("valeur", StringArgumentType.word())
						.executes(c -> exec(c, x -> session.setting(arg(x, "cle"), arg(x, "valeur")))))));

		var node = dispatcher.register(root);
		dispatcher.register(Commands.literal("valcombe").redirect(node));
	}
}
