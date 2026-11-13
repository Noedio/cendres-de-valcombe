package fr.valcombe.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import fr.valcombe.backup.BackupSettings;
import fr.valcombe.backup.BackupStore;
import fr.valcombe.backup.Restorer;
import fr.valcombe.rules.GameException;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * Commandes des sauvegardes : {@code /vc sauvegarde [maintenant | liste | reglages]} pour les opérateurs, et
 * {@code /vc sauvegarde restaurer <nom> | restaurer annuler | supprimer <nom> | reglage <clé> <valeur>} pour la
 * console et les opérateurs de plein droit (niveau 4, celui de /stop), comme {@code /vc admin}.
 */
public final class BackupCommands {
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.FRANCE);
	/** Sauvegardes montrées par {@code liste} (les plus récentes). */
	private static final int LIST_MAX = 15;

	@FunctionalInterface
	private interface Action {
		String run(CommandContext<CommandSourceStack> ctx, BackupService backups) throws Exception;
	}

	private BackupCommands() {
	}

	private static int exec(CommandContext<CommandSourceStack> ctx, Action action) {
		try {
			BackupService b = ValcombeMod.backups();
			if (b == null) {
				throw new GameException("Sauvegardes indisponibles : le serveur démarre ou s'arrête.");
			}
			String result = action.run(ctx, b);
			if (result != null && !result.isEmpty()) {
				ctx.getSource().sendSuccess(() -> Component.literal(result), false);
			}
			return 1;
		} catch (GameException e) {
			ctx.getSource().sendFailure(Component.literal(e.getMessage()));
			return 0;
		} catch (Exception e) {
			ValcombeMod.LOG.error("Erreur dans une commande /vc sauvegarde", e);
			ctx.getSource().sendFailure(Component.literal("Erreur : " + e.getMessage()));
			return 0;
		}
	}

	/** Qui agit, pour le journal et la fiche de la sauvegarde. */
	private static String who(CommandSourceStack src) {
		return src.getPlayer() != null ? src.getPlayer().getScoreboardName() : src.getTextName();
	}

	/** Ajoute {@code sauvegarde} à la commande {@code /vc}. */
	public static void register(LiteralArgumentBuilder<CommandSourceStack> root, Predicate<CommandSourceStack> admin) {
		Predicate<CommandSourceStack> owner = src -> src.permissions().hasPermission(Permissions.COMMANDS_OWNER);
		SuggestionProvider<CommandSourceStack> names = (c, b) -> {
			BackupService s = ValcombeMod.backups();
			List<String> list = new ArrayList<>();
			if (s != null) {
				try {
					s.store().list().forEach(x -> list.add(x.name()));
				} catch (IOException e) {
					// dossier illisible : pas de suggestion
				}
			}
			return SharedSuggestionProvider.suggest(list, b);
		};
		root.then(Commands.literal("sauvegarde").requires(admin)
			.executes(c -> exec(c, (x, b) -> status(x.getSource(), b)))
			.then(Commands.literal("maintenant").executes(c -> exec(c, (x, b) -> now(x.getSource(), b))))
			.then(Commands.literal("liste").executes(c -> exec(c, (x, b) -> list(x.getSource(), b))))
			.then(Commands.literal("reglages").executes(c -> exec(c, (x, b) -> settings(x.getSource(), b))))
			.then(Commands.literal("restaurer").requires(owner)
				.then(Commands.literal("annuler").executes(c -> exec(c, (x, b) -> b.cancelRestore()
					? "Restauration prévue annulée." : "Aucune restauration n'était prévue.")))
				.then(Commands.argument("nom", StringArgumentType.word()).suggests(names)
					.executes(c -> exec(c, (x, b) -> b.scheduleRestore(StringArgumentType.getString(x, "nom"), who(x.getSource()))
						+ " Arrêtez puis relancez le serveur (ou « Redémarrer » dans le launcher) pour l'appliquer ;"
						+ " /vc sauvegarde restaurer annuler pour y renoncer."))))
			.then(Commands.literal("supprimer").requires(owner)
				.then(Commands.argument("nom", StringArgumentType.word()).suggests(names)
					.executes(c -> exec(c, (x, b) -> {
						String n = StringArgumentType.getString(x, "nom");
						if (!b.delete(n, who(x.getSource()))) {
							throw new GameException("Aucune sauvegarde ne s'appelle « " + n + " ».");
						}
						return "Sauvegarde « " + n + " » effacée.";
					}))))
			.then(Commands.literal("reglage").requires(owner)
				.then(Commands.literal("actives").then(Commands.argument("oui_non", StringArgumentType.word()).suggests(yesNo())
					.executes(c -> exec(c, (x, b) -> change(b, new BackupSettings.Change(yes(x), null, null, null, null))))))
				.then(Commands.literal("intervalle").then(Commands.argument("minutes",
						IntegerArgumentType.integer(BackupSettings.MIN_INTERVAL, BackupSettings.MAX_INTERVAL))
					.executes(c -> exec(c, (x, b) -> change(b, new BackupSettings.Change(null,
						IntegerArgumentType.getInteger(x, "minutes"), null, null, null))))))
				.then(Commands.literal("arret").then(Commands.argument("oui_non", StringArgumentType.word()).suggests(yesNo())
					.executes(c -> exec(c, (x, b) -> change(b, new BackupSettings.Change(null, null, yes(x), null, null))))))
				.then(Commands.literal("garder").then(Commands.argument("nombre",
						IntegerArgumentType.integer(BackupSettings.MIN_KEEP, BackupSettings.MAX_KEEP))
					.executes(c -> exec(c, (x, b) -> change(b, new BackupSettings.Change(null, null, null,
						IntegerArgumentType.getInteger(x, "nombre"), null))))))
				.then(Commands.literal("pendantpartie").then(Commands.argument("oui_non", StringArgumentType.word()).suggests(yesNo())
					.executes(c -> exec(c, (x, b) -> change(b, new BackupSettings.Change(null, null, null, null, yes(x)))))))));
	}

	private static SuggestionProvider<CommandSourceStack> yesNo() {
		return (c, b) -> SharedSuggestionProvider.suggest(List.of("oui", "non"), b);
	}

	private static boolean yes(CommandContext<CommandSourceStack> ctx) {
		String v = StringArgumentType.getString(ctx, "oui_non").toLowerCase(Locale.ROOT);
		return switch (v) {
			case "oui", "true", "on" -> true;
			case "non", "false", "off" -> false;
			default -> throw new GameException("« oui » ou « non » attendu.");
		};
	}

	private static String change(BackupService b, BackupSettings.Change c) throws IOException {
		BackupSettings.Values v = b.settings().apply(c);
		return v.actives() ? "Sauvegardes automatiques : " + BackupService.describe(v) + "."
			: "Sauvegardes automatiques désactivées : seulement à la demande (/vc sauvegarde maintenant).";
	}

	/** /vc sauvegarde maintenant : la réponse finale arrive au joueur quand la copie est finie. */
	private static String now(CommandSourceStack src, BackupService b) {
		ServerPlayer p = src.getPlayer();
		UUID id = p == null ? null : p.getUUID();
		MinecraftServer server = src.getServer();
		BackupService.Job job = b.start(fr.valcombe.backup.BackupKind.MANUELLE, who(src), o -> {
			if (id == null) {
				return; // console : le résultat est dans le journal
			}
			ServerPlayer again = server.getPlayerList().getPlayer(id);
			if (again != null) {
				again.sendSystemMessage(Component.literal(o.message()).withStyle(o.ok() ? ChatFormatting.GREEN : ChatFormatting.RED));
			}
		});
		return "Sauvegarde lancée : " + job.name + ". Le jeu continue pendant la copie ; un message dira quand elle est finie.";
	}

	/** /vc sauvegarde : où en sont les sauvegardes. */
	private static String status(CommandSourceStack src, BackupService b) throws IOException {
		List<BackupStore.Backup> all = b.store().list();
		long total = all.stream().mapToLong(BackupStore.Backup::size).sum();
		long free = b.store().freeSpace();
		say(src, "Sauvegardes : " + all.size() + " dans " + BackupStore.DIR_NAME + "/ (" + BackupService.mb(total) + ")"
			+ (free >= 0 ? ", " + BackupService.mb(free) + " libres sur le disque." : "."));
		BackupSettings.Values v = b.settings().get();
		Instant next = b.nextAutomatic(v);
		if (!b.canRestore()) {
			say(src, "Automatiques : seulement sur un serveur dédié. Ici, à la demande : /vc sauvegarde maintenant");
		} else if (!v.actives()) {
			say(src, "Automatiques : désactivées (/vc sauvegarde reglage actives oui).");
		} else {
			say(src, "Automatiques : " + BackupService.describe(v) + "."
				+ (next == null ? "" : " Prochaine : " + WHEN.format(next.atZone(b.zone()))
					+ (b.postponed() ? " (reportée : une partie est en cours)" : "") + "."));
		}
		BackupService.Job job = b.running();
		if (job != null) {
			say(src, "En cours : " + job.name + " (" + job.phase() + ", " + job.percent() + " %).");
		}
		BackupService.Outcome last = b.last();
		if (last != null) {
			say(src, "Dernière : " + last.message());
		}
		b.pendingRestore().ifPresent(r -> say(src, "Restauration prévue au prochain démarrage : " + r.nom() + " (demandée par "
			+ r.par() + "). /vc sauvegarde restaurer annuler pour y renoncer."));
		Restorer.lastReport(b.store().dir()).ifPresent(r -> say(src, "Dernière restauration : " + r.message()));
		return "Commandes : /vc sauvegarde maintenant | liste | reglages | restaurer <nom> | supprimer <nom>";
	}

	/** /vc sauvegarde liste : les plus récentes ; un clic prépare la commande de restauration. */
	private static String list(CommandSourceStack src, BackupService b) throws IOException {
		List<BackupStore.Backup> all = b.store().list();
		if (all.isEmpty()) {
			return "Aucune sauvegarde pour l'instant. /vc sauvegarde maintenant en fait une.";
		}
		say(src, all.size() + " sauvegarde(s), de la plus récente à la plus ancienne :");
		for (BackupStore.Backup bk : all.subList(0, Math.min(LIST_MAX, all.size()))) {
			String line = "  " + bk.name() + " · " + bk.kind().label() + " · " + BackupService.mb(bk.size());
			if (src.getPlayer() != null && b.canRestore()) {
				String cmd = "/vc sauvegarde restaurer " + bk.name();
				src.sendSuccess(() -> Component.literal(line).withStyle(st -> st.withColor(ChatFormatting.AQUA)
					.withClickEvent(new ClickEvent.SuggestCommand(cmd))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Cliquer pour préparer sa restauration")))), false);
			} else {
				say(src, line);
			}
		}
		return all.size() > LIST_MAX ? "... et " + (all.size() - LIST_MAX) + " plus ancienne(s)." : "";
	}

	/** /vc sauvegarde reglages */
	private static String settings(CommandSourceStack src, BackupService b) {
		BackupSettings.Values v = b.settings().get();
		say(src, "Réglages des sauvegardes (config/valcombe/sauvegardes.json) :");
		say(src, "  actives : " + (v.actives() ? "oui" : "non") + " · intervalle : " + v.intervalleMinutes() + " min · arrêt : "
			+ (v.aLArret() ? "oui" : "non") + " · garder : " + v.garder() + " · pendant une partie : " + (v.pendantPartie() ? "oui" : "non"));
		return "Changer : /vc sauvegarde reglage actives|intervalle|arret|garder|pendantpartie <valeur>";
	}

	private static void say(CommandSourceStack src, String line) {
		src.sendSuccess(() -> Component.literal(line), false);
	}
}
