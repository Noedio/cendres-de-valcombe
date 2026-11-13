package fr.valcombe.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import fr.valcombe.mc.games.GameRoom;
import fr.valcombe.mc.games.Games;
import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.session.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.commands.CommandBuildContext;
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

	private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) throws Exception {
		return ctx.getSource().getPlayerOrException();
	}

	private static int number(CommandContext<CommandSourceStack> ctx, String key) {
		return IntegerArgumentType.getInteger(ctx, key);
	}

	/** Groupes de rôles dans l'éditeur de script : appliquer au script, ou ranger le script dans un groupe. */
	private static String scriptGroup(Session session, String action, String name) {
		return switch (action) {
			case "ranger" -> ValcombeMod.creations().putGroup(name,
				session.script().stream().map(r -> r.custom() ? r.customId : r.name().toLowerCase(java.util.Locale.ROOT)).toList());
			case "supprimer" -> ValcombeMod.creations().deleteGroup(name);
			default -> session.scriptGroup(name.strip(), action);
		};
	}

	private static String mapTools(CommandContext<CommandSourceStack> ctx) throws Exception {
		ValcombeUi.mapTools(player(ctx), true);
		return "Outils de carte : trône, maison, centre, salle de convocation et gomme (clic droit sur un bloc pour poser, "
			+ "sur un repère déjà posé pour le déplacer), sélection et structure (éditeur du mode construction).";
	}

	/** Tailles de village de /vc carte generer, dans l'ordre (0 petite, 1 normale, 2 grande). */
	private static final List<String> SIZES = List.of("petite", "normale", "grande");

	/** Les cartes de la bibliothèque, pour les suggestions. */
	private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> MAP_IDS = (c, b) ->
		SharedSuggestionProvider.suggest(ValcombeMod.maps() == null ? List.<String>of()
			: ValcombeMod.maps().list().stream().map(d -> d.id).toList(), b);

	/** Les mondes de partie (vraies parties et parties d'essai), où l'on envoie une carte. */
	private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> GAME_WORLDS = (c, b) -> {
		var worlds = ValcombeMod.worlds();
		return SharedSuggestionProvider.suggest(worlds == null ? List.<String>of() : worlds.list().stream()
			.filter(e -> MapSender.accepts(e.sorte()))
			.map(fr.valcombe.mc.worlds.WorldEntry::id).toList(), b);
	};

	/** /vc carte generer <places> <taille> <theme> <maisons> [monde] : un village généré envoyé à un monde de partie. */
	private static String generate(CommandContext<CommandSourceStack> ctx, String world) {
		int size = SIZES.indexOf(arg(ctx, "taille"));
		if (size < 0) {
			throw new GameException("Taille : petite, normale ou grande.");
		}
		return sender(ctx).sendVillage(number(ctx, "places"), size, arg(ctx, "theme"),
			!arg(ctx, "maisons").equals("non"), world != null ? world : here(ctx), ctx.getSource().getPlayer());
	}

	/** Envoie à l'écran en jeu l'aperçu vu de dessus d'une carte (id vide : la carte actuelle de la partie du joueur). */
	private static String preview(CommandContext<CommandSourceStack> ctx, String id) throws Exception {
		ServerPlayer p = player(ctx);
		if (!ViewSync.hasClientMod(p)) {
			throw new GameException("L'aperçu s'affiche dans l'écran de l'Atelier, qui demande le mod de Valcombe sur votre jeu.");
		}
		GameRoom r = Games.forSource(ctx.getSource());
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p,
			new fr.valcombe.net.ValcombeNet.PreviewPayload(ValcombeMod.workshop().preview(r == null ? null : r.host(), id)));
		return "";
	}

	/** Le catalogue de structures, une ligne par rayon. */
	private static List<String> catalog() {
		java.util.Map<String, List<String>> shelves = new java.util.LinkedHashMap<>();
		for (StructureStore.Def d : ValcombeMod.structures().list()) {
			String shelf = switch (d.categorie) {
				case "maisons" -> "Maisons et salles";
				case "place" -> "Pour la place";
				case "decor" -> "Décor";
				default -> "Vos structures";
			};
			shelves.computeIfAbsent(shelf, k -> new ArrayList<>()).add(d.id + " (" + d.nom + ", " + d.taille[0] + " × " + d.taille[1]
				+ " × " + d.taille[2] + ")");
		}
		List<String> lines = new ArrayList<>();
		shelves.forEach((shelf, items) -> lines.add(shelf + " : " + String.join(", ", items) + "."));
		lines.add("Prendre une structure en main : /vc structure poser <id>.");
		return lines;
	}

	/** Le joueur qui lance la commande ; une erreur claire depuis la console. */
	private static ServerPlayer inGame(CommandContext<CommandSourceStack> x) {
		ServerPlayer p = x.getSource().getPlayer();
		if (p == null) {
			throw new fr.valcombe.rules.GameException("Commande à lancer en jeu, par un joueur.");
		}
		return p;
	}

	// Les repères hors d'un atelier carte : ceux du village de la partie de la commande (voir host(ctx)).
	private static String addSeat(CommandContext<CommandSourceStack> x) {
		return host(x).addSeat(inGame(x));
	}

	private static String addHouse(CommandContext<CommandSourceStack> x) {
		return host(x).addHouse(inGame(x));
	}

	private static String setCenter(CommandContext<CommandSourceStack> x) {
		return host(x).setMapCenter(inGame(x));
	}

	/** L'écran du mod client qu'on ouvre à ce joueur ; sans le mod, on lui dit quoi faire à la place. */
	private static String openScreen(ServerPlayer p, String screen, String otherwise) {
		if (!ViewSync.hasClientMod(p)) {
			throw new GameException(otherwise);
		}
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new fr.valcombe.net.ValcombeNet.OpenPayload(screen));
		return "";
	}

	/** Le lien de l'Atelier web, donné dans le chat à l'opérateur qui le demande. */
	private static String atelierLink(ServerPlayer p) throws Exception {
		List<String> links = ValcombeMod.tablet().openAtelier(p.getScoreboardName());
		p.sendSystemMessage(Component.literal("Atelier web ouvert : créez des rôles et des scénarios, gérez les cartes. "
			+ "Ouvrez ce lien sur un ordinateur du même réseau, sans le partager :")
			.withStyle(net.minecraft.ChatFormatting.GOLD));
		for (String link : links) {
			p.sendSystemMessage(Component.literal("  " + link).withStyle(st -> st
				.withColor(net.minecraft.ChatFormatting.AQUA).withUnderlined(true)
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(link)))));
		}
		return "Fermer ce lien : /vc atelier fermer. L'Atelier s'ouvre aussi dans le jeu : /vc atelier jeu.";
	}

	/** /vc carte copier (ou dupliquer) <id> [nouvel_id] [nom] : identifiant et nom facultatifs, comme sur la page web. */
	private static LiteralArgumentBuilder<CommandSourceStack> copyMap(String literal) {
		return Commands.literal(literal)
			.then(Commands.argument("id", StringArgumentType.word())
				.suggests((c, b) -> SharedSuggestionProvider.suggest(ValcombeMod.maps().list().stream().map(d -> d.id), b))
				.executes(c -> exec(c, x -> ValcombeMod.maps().duplicate(arg(x, "id"), null, null)))
				.then(Commands.argument("nouvel_id", StringArgumentType.word())
					.executes(c -> exec(c, x -> ValcombeMod.maps().duplicate(arg(x, "id"), arg(x, "nouvel_id"), null)))
					.then(Commands.argument("nom", StringArgumentType.greedyString())
						.executes(c -> exec(c, x -> ValcombeMod.maps().duplicate(arg(x, "id"), arg(x, "nouvel_id"), arg(x, "nom")))))));
	}

	/**
	 * Les commandes de l'éditeur de carte : /vc structure (catalogue) et /vc objet (menu de la main). Sélectionner,
	 * remplir, copier, coller, annuler les blocs : WorldEdit dans les ateliers, et /vc annuler (voir BuildCommands).
	 */
	private static void registerEditor(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext registry,
			Predicate<CommandSourceStack> admin) {
		SuggestionProvider<CommandSourceStack> all = (c, b) -> SharedSuggestionProvider.suggest(ValcombeMod.structures().ids(false), b);
		SuggestionProvider<CommandSourceStack> mine = (c, b) -> SharedSuggestionProvider.suggest(ValcombeMod.structures().ids(true), b);
		// Menu de l'outil « Carte : main » : déplacer, tourner ou retirer un repère ou une structure posée.
		// Dans un atelier carte, ses invités s'en servent aussi (les repères et les structures de cet atelier seulement).
		root.then(Commands.literal("objet").requires(s -> admin.test(s) || MapWorkshop.guestHere(s))
			.then(Commands.argument("sorte", StringArgumentType.word())
				.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("trone", "maison", "salle", "centre", "structure"), b))
				.then(Commands.argument("numero", IntegerArgumentType.integer(0))
					.then(Commands.argument("action", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(
							List.of("deplacer", "gauche", "droite", "centre", "tourner", "retourner", "retirer"), b))
						.executes(c -> exec(c, x -> MapWorkshop.inAtelierOr(inGame(x),
							w -> w.objectAction(inGame(x), arg(x, "sorte"), IntegerArgumentType.getInteger(x, "numero"), arg(x, "action")),
							() -> {
								if (!admin.test(x.getSource())) {
									throw new fr.valcombe.rules.GameException("Ce menu sert dans l'atelier où vous êtes invité.");
								}
								return host(x).objectAction(inGame(x), arg(x, "sorte"),
									IntegerArgumentType.getInteger(x, "numero"), arg(x, "action"));
							})))))));
		// Prendre, poser, tourner une structure du catalogue : aussi pour les invités d'un atelier carte, là où ils bâtissent.
		// Changer le catalogue lui-même (enregistrer, renommer, supprimer, importer...) reste aux opérateurs.
		root.then(Commands.literal("structure").requires(s -> admin.test(s) || MapWorkshop.guestHere(s))
			.then(Commands.literal("liste").executes(c -> exec(c, x -> lines(x, catalog()))))
			.then(Commands.literal("tenir").executes(c -> exec(c, x -> {
				ValcombeUi.holdMapTool(player(x), ValcombeUi.MapTool.HAND);
				return "Outil « Carte : main » en main : clic droit sur ce que vous voulez déplacer, tourner ou retirer.";
			})))
			.then(Commands.literal("maquette")
				.then(Commands.argument("id", StringArgumentType.word()).suggests(all)
					.executes(c -> exec(c, x -> ValcombeMod.editor().sendModel(player(x), arg(x, "id"))))))
			.then(Commands.literal("poser")
				.then(Commands.argument("id", StringArgumentType.word()).suggests(all)
					.executes(c -> exec(c, x -> ValcombeMod.editor().choose(player(x), arg(x, "id"))))
					.then(Commands.literal("ici").executes(c -> exec(c, x -> {
						ValcombeMod.editor().choose(player(x), arg(x, "id"));
						return ValcombeMod.editor().placeHere(player(x));
					})))))
			.then(Commands.literal("ici").executes(c -> exec(c, x -> ValcombeMod.editor().placeHere(player(x)))))
			.then(Commands.literal("tourner").executes(c -> exec(c, x -> ValcombeMod.editor().rotate(player(x)))))
			.then(Commands.literal("miroir").executes(c -> exec(c, x -> ValcombeMod.editor().mirror(player(x)))))
			.then(Commands.literal("fin").executes(c -> exec(c, x -> ValcombeMod.editor().stopPlacing(player(x)))))
			// La sélection WorldEdit vers le catalogue (même chose que /vc structures depuis-selection, nom en clair).
			.then(Commands.literal("enregistrer").requires(admin)
				.then(Commands.argument("id", StringArgumentType.word())
					.executes(c -> exec(c, x -> saveSelection(x, arg(x, "id"))))
					.then(Commands.argument("nom", StringArgumentType.greedyString())
						.executes(c -> exec(c, x -> saveSelection(x, arg(x, "nom")))))))
			.then(Commands.literal("repere").requires(admin)
				.then(Commands.argument("id", StringArgumentType.word()).suggests(mine)
					.then(Commands.argument("repere", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("maison", "salle", "aucun"), b))
						.executes(c -> exec(c, x -> ValcombeMod.structures().setMark(arg(x, "id"), arg(x, "repere")))))))
			.then(Commands.literal("renommer").requires(admin)
				.then(Commands.argument("id", StringArgumentType.word()).suggests(mine)
					.then(Commands.argument("nom", StringArgumentType.greedyString())
						.executes(c -> exec(c, x -> ValcombeMod.structures().rename(arg(x, "id"), arg(x, "nom")))))))
			.then(Commands.literal("supprimer").requires(admin)
				.then(Commands.argument("id", StringArgumentType.word()).suggests(mine)
					.executes(c -> exec(c, x -> ValcombeMod.structures().delete(arg(x, "id"))))))
			.then(Commands.literal("importer").requires(admin)
				.then(Commands.argument("fichier", StringArgumentType.greedyString())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(ValcombeMod.structures().importable(), b))
					.executes(c -> exec(c, x -> ValcombeMod.structures().importFromFolder(arg(x, "fichier"))))))
			.then(Commands.literal("recharger").requires(admin).executes(c -> exec(c, x -> {
				ValcombeMod.structures().reload();
				return "Catalogue relu : " + ValcombeMod.structures().list().size() + " structures.";
			}))));
	}

	// ------------------------------------------------------------------
	// /vc partie : les parties du serveur
	// ------------------------------------------------------------------
	/** Les parties du serveur, ou une GameException sans les mondes de Valcombe. */
	private static Games games() {
		Games g = Games.current();
		if (g == null) {
			throw new GameException("Les parties ne sont pas disponibles sur ce serveur : les mondes de Valcombe manquent (voir son journal).");
		}
		return g;
	}

	/** Une ligne par partie : nom, sorte, ce qui s'y passe, qui y est ; la partie de la commande est marquée. */
	private static List<String> gamesList(CommandSourceStack src) {
		games();
		GameRoom mine = Games.forSource(src);
		List<String> out = new ArrayList<>();
		out.add("Parties du serveur :");
		for (GameRoom r : Games.all()) {
			var level = r.levelIfReady();
			int here = level == null ? 0 : level.players().size();
			String st = r.session().storyteller();
			out.add((r == mine ? "▶ " : "  ") + "« " + r.name() + " » (" + r.id() + ", " + (r.main() ? "partie principale" : r.kind().label())
				+ ") : " + r.state() + ", " + (here == 0 ? "personne sur place" : here + " sur place")
				+ (st == null ? "" : ", Conteur " + st) + ".");
		}
		out.add("Y aller : /vc partie aller <id>. Créer une partie d'essai : /vc partie creer <nom> (opérateurs).");
		out.add("La rejoindre : /vc partie rejoindre <id> ; la regarder en spectateur : /vc partie regarder <id> (en sortir : "
			+ "/vc partie sortir). Au hub, le tableau des parties et le menu J, « Parties », font de même.");
		out.add("Mettre une partie en suspens (ses joueurs au hub, elle reprendra où elle en est) : /vc partie suspendre [id] ; "
			+ "la reprendre : /vc partie reprendre [id] (son Conteur ou un opérateur).");
		return out;
	}

	/**
	 * /vc partie (liste), creer, supprimer, aller : plusieurs parties séparées en même temps, chacune dans son monde.
	 * On commande toujours la partie du monde où l'on se trouve : pour en mener une autre, on y va.
	 */
	private static void registerGames(LiteralArgumentBuilder<CommandSourceStack> root, Predicate<CommandSourceStack> admin) {
		SuggestionProvider<CommandSourceStack> ids = (c, b) -> SharedSuggestionProvider.suggest(
			Games.all().stream().map(GameRoom::id).toList(), b);
		SuggestionProvider<CommandSourceStack> trials = (c, b) -> SharedSuggestionProvider.suggest(
			Games.all().stream().filter(r -> !r.main()).map(GameRoom::id).toList(), b);
		root.then(Commands.literal("partie")
			.executes(c -> exec(c, x -> lines(x, gamesList(x.getSource()))))
			.then(Commands.literal("liste").executes(c -> exec(c, x -> lines(x, gamesList(x.getSource())))))
			.then(Commands.literal("creer").requires(admin)
				.then(Commands.argument("nom", StringArgumentType.greedyString())
					.executes(c -> exec(c, x -> games().create(arg(x, "nom"), x.getSource().getPlayer())))))
			.then(Commands.literal("supprimer").requires(admin)
				.then(Commands.argument("id", StringArgumentType.word()).suggests(trials)
					.executes(c -> exec(c, x -> games().delete(arg(x, "id"))))))
			.then(Commands.literal("aller")
				.then(Commands.argument("id", StringArgumentType.word()).suggests(ids)
					.executes(c -> exec(c, x -> {
						GameRoom r = Games.byWorld(arg(x, "id"));
						if (r == null) {
							throw new GameException("Aucune partie dans « " + arg(x, "id") + " » (/vc partie liste).");
						}
						var travel = ValcombeMod.travel();
						if (travel == null) {
							throw new GameException("Les voyages entre les mondes ne sont pas disponibles sur ce serveur.");
						}
						return travel.toGame(player(x), r.id());
					}))))
			// Tableau des parties du hub : la fiche d'une partie, la rejoindre (s'inscrire, ou retrouver sa place), la regarder
			// en spectateur (sans les rôles ni le vocal des joueurs), et sortir des gradins.
			.then(Commands.literal("voir")
				.then(Commands.argument("id", StringArgumentType.word()).suggests(ids)
					.executes(c -> exec(c, x -> {
						Games.tellCard(player(x), namedRoom(arg(x, "id")));
						return "";
					}))))
			.then(Commands.literal("rejoindre")
				.then(Commands.argument("id", StringArgumentType.word()).suggests(ids)
					.executes(c -> exec(c, x -> Games.join(player(x), namedRoom(arg(x, "id")))))))
			.then(Commands.literal("regarder")
				.then(Commands.argument("id", StringArgumentType.word()).suggests(ids)
					.executes(c -> exec(c, x -> Games.watch(player(x), namedRoom(arg(x, "id")))))))
			.then(Commands.literal("sortir").executes(c -> exec(c, x -> Games.stopWatching(player(x)))))
			// Mise en suspens et reprise : la partie du monde d'où part la commande (au hub : sa table d'attache), ou celle
			// qu'on nomme. Le Conteur de la partie ou un opérateur.
			.then(Commands.literal("suspendre")
				.executes(c -> exec(c, x -> suspendGame(x.getSource(), null, true)))
				.then(Commands.argument("id", StringArgumentType.word()).suggests(ids)
					.executes(c -> exec(c, x -> suspendGame(x.getSource(), arg(x, "id"), true)))))
			.then(Commands.literal("reprendre")
				.executes(c -> exec(c, x -> suspendGame(x.getSource(), null, false)))
				.then(Commands.argument("id", StringArgumentType.word()).suggests(ids)
					.executes(c -> exec(c, x -> suspendGame(x.getSource(), arg(x, "id"), false))))));
	}

	/** La partie de ce monde, nommée par son identifiant ; une GameException si elle n'existe pas. */
	private static GameRoom namedRoom(String id) {
		games();
		GameRoom r = Games.byWorld(id);
		if (r == null) {
			throw new GameException("Aucune partie dans « " + id + " » (/vc partie liste).");
		}
		return r;
	}

	/**
	 * /vc partie suspendre|reprendre [id] : la partie nommée, sinon celle que vise la commande (son monde, ou la table
	 * d'attache de qui la lance depuis le hub). Seuls son Conteur (ou son élève) et les opérateurs la mènent.
	 */
	private static String suspendGame(CommandSourceStack src, String id, boolean suspend) {
		GameRoom r = id == null ? Games.require(src) : Games.byWorld(id);
		if (r == null) {
			throw new GameException("Aucune partie dans « " + id + " » (/vc partie liste).");
		}
		ServerPlayer p = src.getPlayer();
		if (p != null && !r.isLeader(p)) {
			throw new GameException("Seuls le Conteur de « " + r.name() + " » et les opérateurs peuvent la "
				+ (suspend ? "mettre en suspens." : "reprendre."));
		}
		return suspend ? r.suspend() : r.resume();
	}

	/** /vc structure enregistrer : la sélection WorldEdit du joueur devient une structure du catalogue. */
	private static String saveSelection(CommandContext<CommandSourceStack> ctx, String nom) throws Exception {
		WorldEditGate gate = WorldEditGate.current();
		if (gate == null || ValcombeMod.structures() == null) {
			throw new GameException("La construction n'est pas disponible sur ce serveur.");
		}
		return SelectionToStructure.save(gate.link(), ValcombeMod.structures(), player(ctx), arg(ctx, "id"), nom, null, "");
	}

	/** /vc admin : où en est l'API d'administration. */
	private static String adminStatus() {
		AdminApi api = ValcombeMod.admin();
		if (api == null) {
			throw new GameException("API d'administration indisponible : voir le journal du serveur (config/valcombe/admin.json).");
		}
		Tablet t = ValcombeMod.tablet();
		if (!api.key().enabled()) {
			return "API d'administration désactivée (\"enabled\": false dans config/valcombe/admin.json).";
		}
		if (t == null || !t.adminOpen()) {
			return "API d'administration fermée : elle ne s'ouvre que sur un serveur dédié. Clé : /vc admin cle";
		}
		return "API d'administration ouverte sur le port " + t.port() + ", "
			+ (api.key().localOnly() ? "pour les requêtes de cette machine seulement (tunnel SSH depuis un autre PC)."
				: "pour les requêtes de toute adresse.")
			+ " Clé : /vc admin cle";
	}

	/**
	 * Affiche la clé d'administration (ou en tire une nouvelle). À un joueur, elle s'affiche à lui seul, et un clic
	 * la copie ; à la console, elle s'écrit dans le journal du serveur, comme au premier démarrage.
	 */
	private static String showAdminKey(CommandSourceStack src, boolean renew) throws java.io.IOException {
		AdminApi api = ValcombeMod.admin();
		if (api == null) {
			throw new GameException("API d'administration indisponible : voir le journal du serveur (config/valcombe/admin.json).");
		}
		String key = renew ? api.newKey() : api.key().key();
		String title = renew ? "Nouvelle clé d'administration (l'ancienne ne fonctionne plus) : " : "Clé d'administration : ";
		ServerPlayer p = src.getPlayer();
		if (p != null) {
			p.sendSystemMessage(Component.literal(title).withStyle(net.minecraft.ChatFormatting.GOLD)
				.append(Component.literal(key).withStyle(st -> st.withColor(net.minecraft.ChatFormatting.AQUA).withUnderlined(true)
					.withClickEvent(new net.minecraft.network.chat.ClickEvent.CopyToClipboard(key))
					.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Cliquer pour la copier"))))));
		} else {
			src.sendSuccess(() -> Component.literal(title + key), false);
		}
		if (renew) {
			ValcombeMod.LOG.info("[valcombe] Clé d'administration remplacée par {}", src.getTextName());
		}
		return "Elle donne tout pouvoir sur le serveur : ne la partagez pas."
			+ (renew ? " Donnez la nouvelle au launcher qui pilote ce serveur." : " Pour en changer : /vc admin nouvellecle");
	}

	// ------------------------------------------------------------------
	// La partie d'une commande
	// ------------------------------------------------------------------
	/**
	 * La partie que vise cette commande (voir {@link Games#forSource}) : celle du monde d'où elle part ; hors d'un monde
	 * de partie (hub, atelier, console), la table d'attache du joueur, sinon la partie principale. Une GameException s'il
	 * n'y a aucune partie sur le serveur. On ne commande jamais une autre partie que celle du monde où l'on se trouve.
	 */
	private static GameRoom room(CommandContext<CommandSourceStack> ctx) {
		return Games.require(ctx.getSource());
	}

	private static Session ses(CommandContext<CommandSourceStack> ctx) {
		return room(ctx).session();
	}

	private static ValcombeUi ui(CommandContext<CommandSourceStack> ctx) {
		return room(ctx).ui();
	}

	private static McHost host(CommandContext<CommandSourceStack> ctx) {
		return room(ctx).host();
	}

	private static MapSender sender(CommandContext<CommandSourceStack> ctx) {
		return host(ctx).sender();
	}

	/** Le monde de la partie de cette commande : là où va une carte envoyée sans nommer de monde. */
	private static String here(CommandContext<CommandSourceStack> ctx) {
		return room(ctx).id();
	}

	/** La session de la partie de cette source pour les suggestions et les droits : null sans parties (jamais d'erreur). */
	private static Session sesOrNull(CommandSourceStack src) {
		GameRoom r = Games.forSource(src);
		return r == null ? null : r.session();
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registry) {
		Predicate<CommandSourceStack> admin = src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
		// Le Conteur (même sans être opérateur) ou un opérateur mène la partie : le Conteur de la partie de la commande.
		Predicate<CommandSourceStack> leader = src -> {
			if (admin.test(src)) {
				return true;
			}
			Session s = src.getPlayer() == null ? null : sesOrNull(src);
			return s != null && (src.getPlayer().getScoreboardName().equals(s.storyteller())
				|| s.isTrainee(src.getPlayer().getScoreboardName()));
		};

		SuggestionProvider<CommandSourceStack> playerNames = (ctx, builder) -> {
			List<String> names = new ArrayList<>();
			Session s = sesOrNull(ctx.getSource());
			Game g = s == null ? null : s.game();
			if (g != null) {
				for (Game.Seat seat : g.players()) {
					names.add(seat.name);
				}
			} else if (s != null) {
				names.addAll(s.lobby());
			}
			return SharedSuggestionProvider.suggest(names, builder);
		};
		SuggestionProvider<CommandSourceStack> scenarioIds = (ctx, builder) -> {
			Session s = sesOrNull(ctx.getSource());
			return SharedSuggestionProvider.suggest(s == null ? List.<String>of() : s.scenarios().stream().map(d -> d.id).toList(), builder);
		};

		SuggestionProvider<CommandSourceStack> roleNames = (ctx, builder) -> SharedSuggestionProvider.suggest(
			fr.valcombe.rules.Role.all().stream().map(r -> r.custom() ? r.customId : r.name().toLowerCase()).toList(), builder);

		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("vc")
			.executes(c -> exec(c, x -> {
				ui(x).openMain(x.getSource().getPlayerOrException());
				return "";
			}))
			.then(Commands.literal("menu").executes(c -> exec(c, x -> {
				ServerPlayer p = x.getSource().getPlayerOrException();
				ValcombeUi.giveMenuItem(p);
				ui(x).openMain(p);
				return "";
			})))
			.then(Commands.literal("conteurpanneau").requires(leader).executes(c -> exec(c, x -> {
				ui(x).openStoryteller(x.getSource().getPlayerOrException());
				return "";
			})))
			.then(Commands.literal("imposer").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.then(Commands.argument("role", StringArgumentType.word()).suggests(roleNames)
						.executes(c -> exec(c, x -> ses(x).presetRole(arg(x, "joueur"), arg(x, "role"), null)))
						.then(Commands.argument("cru", StringArgumentType.word()).suggests(roleNames)
							.executes(c -> exec(c, x -> ses(x).presetRole(arg(x, "joueur"), arg(x, "role"), arg(x, "cru"))))))))
			.then(Commands.literal("attribuer").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.then(Commands.argument("role", StringArgumentType.word()).suggests(roleNames)
						.executes(c -> exec(c, x -> ses(x).assignRole(arg(x, "joueur"), arg(x, "role"), null)))
						.then(Commands.argument("cru", StringArgumentType.word()).suggests(roleNames)
							.executes(c -> exec(c, x -> ses(x).assignRole(arg(x, "joueur"), arg(x, "role"), arg(x, "cru"))))))))
			.then(Commands.literal("presage").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).setRedHerring(arg(x, "joueur"))))))
			.then(Commands.literal("bluffs").requires(leader)
				.then(Commands.argument("r1", StringArgumentType.word()).suggests(roleNames)
					.then(Commands.argument("r2", StringArgumentType.word()).suggests(roleNames)
						.then(Commands.argument("r3", StringArgumentType.word()).suggests(roleNames)
							.executes(c -> exec(c, x -> ses(x).setBluffs(List.of(arg(x, "r1"), arg(x, "r2"), arg(x, "r3")))))))))
			.then(Commands.literal("note").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).note(arg(x, "joueur"), null)))
					.then(Commands.argument("texte", StringArgumentType.greedyString())
						.executes(c -> exec(c, x -> ses(x).note(arg(x, "joueur"), arg(x, "texte")))))))
			.then(Commands.literal("consigne").requires(leader)
				.then(Commands.argument("cle", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("enregistrement", "chatelain", "successeur"), b))
					.then(Commands.argument("valeur", StringArgumentType.word())
						.executes(c -> exec(c, x -> ses(x).policy(arg(x, "cle"), arg(x, "valeur")))))))
			.then(Commands.literal("script").requires(leader)
				.then(Commands.literal("basculer")
					.then(Commands.argument("role", StringArgumentType.word()).suggests(roleNames)
						.executes(c -> exec(c, x -> ses(x).toggleScriptRole(arg(x, "role"))))))
				.then(Commands.literal("complet").executes(c -> exec(c, x -> ses(x).useScript("Les Cendres de Valcombe",
					fr.valcombe.rules.Role.classic()))))
				.then(Commands.literal("brumes").executes(c -> exec(c, x -> ses(x).useScript("Les Brumes de Valcombe",
					fr.valcombe.rules.Role.brumes()))))
				.then(Commands.literal("table").executes(c -> exec(c, x -> ses(x).useScript("La Table de Valcombe",
					fr.valcombe.rules.Role.table()))))
				.then(Commands.literal("groupe")
					.then(Commands.argument("action", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("ajouter", "retirer", "seulement", "ranger", "supprimer"), b))
						.then(Commands.argument("nom", StringArgumentType.greedyString())
							.suggests((c, b) -> SharedSuggestionProvider.suggest(ValcombeMod.creations().groups().keySet(), b))
							.executes(c -> exec(c, x -> scriptGroup(ses(x), arg(x, "action"), arg(x, "nom")))))))
				.then(Commands.literal("sauver")
					.then(Commands.argument("nom", StringArgumentType.greedyString()).executes(c -> exec(c, x -> {
						var server = x.getSource().getServer();
						ScriptStore.save(server, arg(x, "nom"), ses(x).script());
						var saved = ScriptStore.list(server);
						Games.sessions().forEach(s -> s.setSavedScripts(saved)); // les scripts enregistrés sont communs
						return "Script enregistré.";
					}))))
				.then(Commands.literal("charger")
					.then(Commands.argument("nom", StringArgumentType.greedyString())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(ScriptStore.list(c.getSource().getServer()), b))
						.executes(c -> exec(c, x -> ses(x).useScript(arg(x, "nom"),
							ScriptStore.load(x.getSource().getServer(), arg(x, "nom")))))))
				.then(Commands.literal("supprimer")
					.then(Commands.argument("nom", StringArgumentType.greedyString())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(ScriptStore.list(c.getSource().getServer()), b))
						.executes(c -> exec(c, x -> {
							var server = x.getSource().getServer();
							ScriptStore.delete(server, arg(x, "nom"));
							var saved = ScriptStore.list(server);
							Games.sessions().forEach(s -> s.setSavedScripts(saved));
							return "Script supprimé.";
						})))))
			.then(Commands.literal("carte").requires(admin)
				// Tout ce qui change la carte d'un monde de partie passe par « Envoyer à la partie » (MapSender) : le monde
				// est remis à neuf, plat et vide, puis la carte y est posée. Sans monde nommé, celui de la partie de la commande.
				.then(Commands.literal("envoyer")
					.then(Commands.argument("id", StringArgumentType.word()).suggests(MAP_IDS)
						.executes(c -> exec(c, x -> sender(x).sendMap(arg(x, "id"), here(x), x.getSource().getPlayer())))
						.then(Commands.argument("monde", StringArgumentType.greedyString()).suggests(GAME_WORLDS)
							.executes(c -> exec(c, x -> sender(x).sendMap(arg(x, "id"), arg(x, "monde"),
								x.getSource().getPlayer()))))))
				.then(Commands.literal("construire")
					.executes(c -> exec(c, x -> sender(x).sendVillage(Village.SEATS, 1, "gothique", true, here(x),
						x.getSource().getPlayer())))
					.then(Commands.argument("theme", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(Village.THEMES, b))
						.executes(c -> exec(c, x -> sender(x).sendVillage(Village.SEATS, 1, arg(x, "theme"), true, here(x),
							x.getSource().getPlayer())))))
				.then(Commands.literal("importer")
					.then(Commands.argument("structure", StringArgumentType.greedyString())
						.suggests((c, b) -> {
							GameRoom r = Games.forSource(c.getSource());
							return SharedSuggestionProvider.suggest(r == null ? List.<String>of() : r.host().structures(), b);
						})
						.executes(c -> exec(c, x -> sender(x).sendStructure(arg(x, "structure"), here(x),
							x.getSource().getPlayer())))))
				.then(Commands.literal("outils").executes(c -> exec(c, x -> mapTools(x))))
				.then(Commands.literal("baguette").executes(c -> exec(c, x -> mapTools(x)))) // ancien nom de « outils »
				.then(Commands.literal("vider")
					.executes(c -> exec(c, x -> sender(x).clear(here(x), x.getSource().getPlayer())))
					.then(Commands.argument("monde", StringArgumentType.greedyString()).suggests(GAME_WORLDS)
						.executes(c -> exec(c, x -> sender(x).clear(arg(x, "monde"), x.getSource().getPlayer())))))
				.then(Commands.literal("nouvelle")
					.executes(c -> exec(c, x -> sender(x).sendBlank(here(x), x.getSource().getPlayer())))
					.then(Commands.argument("monde", StringArgumentType.greedyString()).suggests(GAME_WORLDS)
						.executes(c -> exec(c, x -> sender(x).sendBlank(arg(x, "monde"), x.getSource().getPlayer())))))
				// Les repères : dans un atelier carte, ceux de sa carte (MapWorkshop) ; ailleurs, ceux du village de la partie.
				.then(Commands.literal("trone")
					.executes(c -> exec(c, x -> MapWorkshop.inAtelierOr(x.getSource().getPlayer(),
						w -> w.markHere(x.getSource().getPlayer(), "trone"), () -> addSeat(x)))))
				.then(Commands.literal("siege") // ancien nom de « trone »
					.executes(c -> exec(c, x -> MapWorkshop.inAtelierOr(x.getSource().getPlayer(),
						w -> w.markHere(x.getSource().getPlayer(), "trone"), () -> addSeat(x)))))
				.then(Commands.literal("maison")
					.executes(c -> exec(c, x -> MapWorkshop.inAtelierOr(x.getSource().getPlayer(),
						w -> w.markHere(x.getSource().getPlayer(), "maison"), () -> addHouse(x)))))
				.then(Commands.literal("retirer")
					.then(Commands.literal("trone").executes(c -> exec(c, x -> MapWorkshop.inAtelierOr(x.getSource().getPlayer(),
						w -> w.removeLast(x.getSource().getPlayer(), true), () -> host(x).removeSeat()))))
					.then(Commands.literal("siege").executes(c -> exec(c, x -> MapWorkshop.inAtelierOr(x.getSource().getPlayer(),
						w -> w.removeLast(x.getSource().getPlayer(), true), () -> host(x).removeSeat()))))
					.then(Commands.literal("maison").executes(c -> exec(c, x -> MapWorkshop.inAtelierOr(x.getSource().getPlayer(),
						w -> w.removeLast(x.getSource().getPlayer(), false), () -> host(x).removeHouse())))))
				.then(Commands.literal("apercu")
					.executes(c -> exec(c, x -> preview(x, "")))
					.then(Commands.argument("id", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(ValcombeMod.maps().list().stream().map(d -> d.id), b))
						.executes(c -> exec(c, x -> preview(x, arg(x, "id"))))))
				.then(Commands.literal("infos").executes(c -> exec(c, x -> MapWorkshop.inAtelierOr(x.getSource().getPlayer(),
					w -> w.info(x.getSource().getPlayer()), () -> host(x).mapInfo()))))
				.then(Commands.literal("annuler").executes(c -> exec(c, x -> MapWorkshop.inAtelierOr(x.getSource().getPlayer(),
					w -> w.undoMarker(x.getSource().getPlayer()), () -> host(x).undoMarker()))))
				.then(Commands.literal("ordonner").executes(c -> exec(c, x -> MapWorkshop.inAtelierOr(x.getSource().getPlayer(),
					w -> w.sortMarkers(x.getSource().getPlayer()), () -> host(x).sortMarkers()))))
				.then(Commands.literal("renommer")
					.then(Commands.argument("id", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(ValcombeMod.maps().list().stream().map(d -> d.id), b))
						.then(Commands.argument("nom", StringArgumentType.greedyString())
							.executes(c -> exec(c, x -> ValcombeMod.maps().rename(arg(x, "id"), arg(x, "nom")))))))
				.then(copyMap("copier"))
				.then(copyMap("dupliquer"))
				.then(Commands.literal("construction")
					.executes(c -> exec(c, x -> host(x).toggleConstruction(x.getSource().getPlayerOrException()))))
				.then(Commands.literal("centre")
					.executes(c -> exec(c, x -> MapWorkshop.inAtelierOr(x.getSource().getPlayer(),
						w -> w.markHere(x.getSource().getPlayer(), "centre"), () -> setCenter(x)))))
				.then(Commands.literal("liste").executes(c -> exec(c, x -> {
					var list = ValcombeMod.maps().list();
					return list.isEmpty() ? "Aucune carte enregistrée : /vc carte enregistrer <identifiant> <nom>."
						: "Cartes : " + String.join(", ", list.stream().map(d -> d.id + " (« " + d.nom + " », "
							+ d.sieges.size() + " trônes)").toList());
				})))
				.then(Commands.literal("enregistrer")
					.then(Commands.argument("id", StringArgumentType.word())
						.executes(c -> exec(c, x -> ValcombeMod.maps().saveCurrent(host(x), arg(x, "id"), arg(x, "id"))))
						.then(Commands.argument("nom", StringArgumentType.greedyString())
							.executes(c -> exec(c, x -> ValcombeMod.maps().saveCurrent(host(x), arg(x, "id"), arg(x, "nom")))))))
				// Ancien nom de « envoyer », vers le monde de la partie du serveur.
				.then(Commands.literal("charger")
					.then(Commands.argument("id", StringArgumentType.word()).suggests(MAP_IDS)
						.executes(c -> exec(c, x -> sender(x).sendMap(arg(x, "id"), here(x), x.getSource().getPlayer())))))
				// Une carte de la bibliothèque dans un monde de Valcombe, et l'inverse (voir MapPlacer).
				.then(MapPlacerCommands.place())
				.then(MapPlacerCommands.capture())
				.then(Commands.literal("supprimer")
					.then(Commands.argument("id", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(ValcombeMod.maps().list().stream().map(d -> d.id), b))
						.executes(c -> exec(c, x -> ValcombeMod.maps().delete(arg(x, "id"))))))
				.then(Commands.literal("generer")
					.then(Commands.argument("places", IntegerArgumentType.integer(Village.MIN_SEATS, Village.MAX_SEATS))
						.executes(c -> exec(c, x -> sender(x).sendVillage(IntegerArgumentType.getInteger(x, "places"), 1,
							"gothique", true, here(x), x.getSource().getPlayer())))
						.then(Commands.argument("taille", StringArgumentType.word())
							.suggests((c, b) -> SharedSuggestionProvider.suggest(SIZES, b))
							.then(Commands.argument("theme", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(Village.THEMES, b))
								.then(Commands.argument("maisons", StringArgumentType.word())
									.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("oui", "non"), b))
									.executes(c -> exec(c, x -> generate(x, null)))
									.then(Commands.argument("monde", StringArgumentType.greedyString()).suggests(GAME_WORLDS)
										.executes(c -> exec(c, x -> generate(x, arg(x, "monde"))))))))))
				.then(Commands.literal("salle").executes(c -> exec(c, x -> host(x).buildHall()))))
			.then(Commands.literal("bots").requires(leader)
				.then(Commands.literal("retirer").executes(c -> exec(c, x -> ses(x).removeBots())))
				.then(Commands.argument("nombre", IntegerArgumentType.integer(1, 15))
					.executes(c -> exec(c, x -> ses(x).addBots(IntegerArgumentType.getInteger(x, "nombre"))))))
			.then(Commands.literal("rejoindre").executes(c -> exec(c, x -> ses(x).join(name(x)))))
			.then(Commands.literal("quitter").executes(c -> exec(c, x -> ses(x).leave(name(x)))))
			.then(Commands.literal("joueurs").executes(c -> exec(c, x -> ses(x).listPlayers())))
			.then(Commands.literal("conteur").requires(admin)
				.executes(c -> exec(c, x -> ses(x).toggleStoryteller(name(x)))))
			.then(Commands.literal("lancer").requires(leader)
				.executes(c -> exec(c, x -> ses(x).start(x.getSource().getTextName(), Session.Mode.AUTO)))
				.then(Commands.literal("auto")
					.executes(c -> exec(c, x -> ses(x).start(x.getSource().getTextName(), Session.Mode.AUTO))))
				.then(Commands.literal("humain")
					.executes(c -> exec(c, x -> ses(x).start(x.getSource().getTextName(), Session.Mode.HUMAIN)))))
			.then(Commands.literal("arreter").requires(leader).executes(c -> exec(c, x -> ses(x).stop())))
			.then(Commands.literal("nuit").requires(leader).executes(c -> exec(c, x -> ses(x).startNight())))
			.then(Commands.literal("finjour").requires(leader).executes(c -> exec(c, x -> ses(x).endDay())))
			.then(Commands.literal("suivant").requires(leader).executes(c -> exec(c, x -> ses(x).skip())))
			.then(Commands.literal("precedent").requires(leader).executes(c -> exec(c, x -> ses(x).previous())))
			.then(Commands.literal("pause").requires(leader).executes(c -> exec(c, x -> ses(x).togglePause())))
			.then(Commands.literal("minuteur").requires(leader)
				.then(Commands.literal("passer").executes(c -> exec(c, x -> ses(x).timerControl("passer", 0))))
				.then(Commands.literal("arreter").executes(c -> exec(c, x -> ses(x).timerControl("arreter", 0))))
				.then(Commands.literal("prolonger")
					.executes(c -> exec(c, x -> ses(x).timerControl("prolonger", 30)))
					.then(Commands.argument("secondes", IntegerArgumentType.integer(5, 1800))
						.executes(c -> exec(c, x -> ses(x).timerControl("prolonger", IntegerArgumentType.getInteger(x, "secondes")))))))
			.then(Commands.literal("ordre").requires(leader)
				.then(Commands.argument("role", StringArgumentType.word()).suggests(roleNames)
					.then(Commands.argument("sens", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("haut", "bas"), b))
						.executes(c -> exec(c, x -> ses(x).moveInOrder(arg(x, "role"), arg(x, "sens")))))))
			.then(Commands.literal("prolonger").requires(leader)
				.executes(c -> exec(c, x -> ses(x).extend(1)))
				.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 15))
					.executes(c -> exec(c, x -> ses(x).extend(IntegerArgumentType.getInteger(x, "minutes"))))))
			.then(Commands.literal("gracier").requires(leader).executes(c -> exec(c, x -> ses(x).pardon())))
			.then(Commands.literal("victoire").requires(leader)
				.then(Commands.literal("bien").executes(c -> exec(c, x -> ses(x).declareWinner("bien"))))
				.then(Commands.literal("mal").executes(c -> exec(c, x -> ses(x).declareWinner("mal")))))
			.then(Commands.literal("reveiller").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).wake(arg(x, "joueur"), true)))))
			.then(Commands.literal("rendormir").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).wake(arg(x, "joueur"), false)))))
			.then(Commands.literal("demander").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).ask(arg(x, "joueur"), 1)))
					.then(Commands.argument("nombre", IntegerArgumentType.integer(1, 3))
						.executes(c -> exec(c, x -> ses(x).ask(arg(x, "joueur"), IntegerArgumentType.getInteger(x, "nombre")))))))
			.then(Commands.literal("montrer").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.then(Commands.argument("texte", StringArgumentType.greedyString())
						.executes(c -> exec(c, x -> ses(x).show(arg(x, "joueur"), arg(x, "texte")))))))
			.then(Commands.literal("diffusion").requires(leader).executes(c -> exec(c, x -> ses(x).toggleBroadcast())))
			.then(Commands.literal("journal").requires(leader).executes(c -> exec(c, x -> ses(x).journal(name(x)))))
			.then(Commands.literal("melanger").requires(leader).executes(c -> exec(c, x -> ses(x).shuffleLobby())))
			.then(Commands.literal("exclure").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).kick(arg(x, "joueur"))))))
			.then(Commands.literal("parole")
				.executes(c -> exec(c, x -> ses(x).speechRequest(name(x))))
				.then(Commands.literal("suivant").requires(leader).executes(c -> exec(c, x -> ses(x).speechNext())))
				.then(Commands.literal("stop").requires(leader).executes(c -> exec(c, x -> ses(x).speechStop())))
				.then(Commands.literal("tour").requires(leader)
					.executes(c -> exec(c, x -> ses(x).speechRound(0)))
					.then(Commands.argument("secondes", IntegerArgumentType.integer(15, 180))
						.executes(c -> exec(c, x -> ses(x).speechRound(IntegerArgumentType.getInteger(x, "secondes"))))))
				.then(Commands.literal("duree").requires(leader)
					.then(Commands.argument("secondes", IntegerArgumentType.integer(15, 180))
						.executes(c -> exec(c, x -> ses(x).speechSeconds(IntegerArgumentType.getInteger(x, "secondes"))))))
				.then(Commands.literal("donner").requires(leader)
					.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
						.executes(c -> exec(c, x -> ses(x).speechGive(arg(x, "joueur")))))))
			.then(Commands.literal("relayer").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).standIn(arg(x, "joueur"))))))
			.then(Commands.literal("nominer")
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).nominate(name(x), arg(x, "joueur"))))))
			.then(Commands.literal("deviner")
				.then(Commands.argument("hypotheses", StringArgumentType.greedyString())
					.executes(c -> exec(c, x -> ses(x).guess(name(x), List.of(arg(x, "hypotheses").trim().split("\\s+")))))))
			.then(Commands.literal("voix")
				.then(Commands.argument("niveau", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("chuchoter", "parler", "crier", "suivant"), b))
					.executes(c -> exec(c, x -> ses(x).setVoiceLevel(name(x), arg(x, "niveau"))))))
			.then(Commands.literal("donnerparole")
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).giveFloor(name(x), arg(x, "joueur"))))))
			.then(Commands.literal("chuchoter")
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.then(Commands.argument("message", StringArgumentType.greedyString())
						.executes(c -> exec(c, x -> ses(x).whisper(name(x), arg(x, "joueur"), arg(x, "message")))))))
			.then(Commands.literal("nominations").requires(leader)
				.then(Commands.literal("ouvrir").executes(c -> exec(c, x -> ses(x).setNominations(true))))
				.then(Commands.literal("fermer").executes(c -> exec(c, x -> ses(x).setNominations(false)))))
			.then(Commands.literal("rassembler").requires(leader).executes(c -> exec(c, x -> ses(x).gather())))
			.then(Commands.literal("convoquer").requires(leader)
				.then(Commands.literal("suivant").executes(c -> exec(c, x -> ses(x).summonNext())))
				.then(Commands.literal("fin").executes(c -> exec(c, x -> ses(x).endSummon())))
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).summon(arg(x, "joueur"))))))
			.then(Commands.literal("aparte").requires(leader)
				.then(Commands.literal("fin").executes(c -> exec(c, x -> ses(x).setAside(null))))
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).setAside(arg(x, "joueur"))))))
			.then(Commands.literal("appeler").executes(c -> exec(c, x -> ses(x).callStoryteller(name(x)))))
			.then(Commands.literal("annoncer")
				.then(Commands.argument("role", StringArgumentType.word()).suggests(roleNames)
					.executes(c -> exec(c, x -> ses(x).claim(name(x), arg(x, "role"))))))
			.then(Commands.literal("signe")
				.then(Commands.argument("signe", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(
						List.of("oui", "non", "question", "attention", "reflexion", "accord"), b))
					.executes(c -> exec(c, x -> ses(x).sign(name(x), arg(x, "signe"))))))
			.then(Commands.literal("parler")
				.then(Commands.literal("accepter").executes(c -> exec(c, x -> ses(x).answerTalk(name(x), true))))
				.then(Commands.literal("refuser").executes(c -> exec(c, x -> ses(x).answerTalk(name(x), false))))
				.then(Commands.literal("fin").executes(c -> exec(c, x -> ses(x).endTalk(name(x)))))
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).inviteTalk(name(x), arg(x, "joueur"))))))
			.then(Commands.literal("voter").requires(leader).executes(c -> exec(c, x -> ses(x).startVoteNow())))
			.then(Commands.literal("tuer").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).stKill(arg(x, "joueur"))))))
			.then(Commands.literal("ressusciter").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).stRevive(arg(x, "joueur"))))))
			.then(Commands.literal("empoisonner").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).stPoison(arg(x, "joueur"))))))
			.then(Commands.literal("changer").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.then(Commands.argument("role", StringArgumentType.word()).suggests(roleNames)
						.executes(c -> exec(c, x -> ses(x).stSwap(arg(x, "joueur"), arg(x, "role"), null)))
						.then(Commands.argument("cru", StringArgumentType.word()).suggests(roleNames)
							.executes(c -> exec(c, x -> ses(x).stSwap(arg(x, "joueur"), arg(x, "role"), arg(x, "cru"))))))))
			.then(Commands.literal("tirer")
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).shoot(name(x), arg(x, "joueur"))))))
			.then(Commands.literal("choisir")
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.executes(c -> exec(c, x -> ses(x).choose(name(x), arg(x, "joueur"))))))
			.then(Commands.literal("valider").executes(c -> exec(c, x -> ses(x).validate(name(x)))))
			.then(Commands.literal("decider")
				.then(Commands.argument("choix", IntegerArgumentType.integer(0, 30))
					.executes(c -> exec(c, x -> ses(x).decide(name(x), IntegerArgumentType.getInteger(x, "choix"))))))
			.then(Commands.literal("dormir").executes(c -> exec(c, x -> ses(x).sleep(name(x)))))
			.then(Commands.literal("envoyer")
				.executes(c -> exec(c, x -> ses(x).storytellerSend(name(x), null)))
				.then(Commands.argument("texte", StringArgumentType.greedyString())
					.executes(c -> exec(c, x -> ses(x).storytellerSend(name(x), arg(x, "texte"))))))
			.then(Commands.literal("vote")
				.then(Commands.literal("oui").executes(c -> exec(c, x -> ses(x).vote(name(x), true))))
				.then(Commands.literal("non").executes(c -> exec(c, x -> ses(x).vote(name(x), false))))
				.then(Commands.literal("annuler").requires(leader).executes(c -> exec(c, x -> ses(x).cancelVote()))))
			.then(Commands.literal("role").executes(c -> exec(c, x -> ses(x).role(name(x)))))
			.then(Commands.literal("grimoire").executes(c -> exec(c, x -> ses(x).grimoire(name(x)))))
			.then(Commands.literal("fiches").executes(c -> exec(c, x -> ses(x).sheets(name(x)))))
			.then(Commands.literal("historique").executes(c -> exec(c, x -> ses(x).history(name(x)))))
			.then(Commands.literal("mp")
				.then(Commands.argument("message", StringArgumentType.greedyString())
					.executes(c -> exec(c, x -> ses(x).messageStoryteller(name(x), arg(x, "message"))))))
			.then(Commands.literal("rappel").requires(leader)
				.then(Commands.argument("joueur", StringArgumentType.word()).suggests(playerNames)
					.then(Commands.argument("texte", StringArgumentType.greedyString())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(
							List.of("ivre", "empoisonné", "protégé", "fausse piste", "a choisi", "effacer"), b))
						.executes(c -> exec(c, x -> ses(x).reminder(arg(x, "joueur"), arg(x, "texte")))))))
			.then(Commands.literal("discussion").requires(leader)
				.then(Commands.argument("minutes", IntegerArgumentType.integer(0, 30))
					.executes(c -> exec(c, x -> ses(x).discussion(IntegerArgumentType.getInteger(x, "minutes"))))))
			.then(Commands.literal("spectateur").executes(c -> exec(c, x -> ses(x).spectate(name(x)))))
			.then(Commands.literal("entrainement").executes(c -> exec(c, x -> ses(x).training(name(x)))))
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
				// Sans rien : le lien de la page web. « jeu » : l'écran de l'Atelier, dans le jeu.
				.executes(c -> exec(c, x -> atelierLink(x.getSource().getPlayerOrException())))
				.then(Commands.literal("lien").executes(c -> exec(c, x -> atelierLink(x.getSource().getPlayerOrException()))))
				.then(Commands.literal("jeu").executes(c -> exec(c, x -> openScreen(x.getSource().getPlayerOrException(), "atelier",
					"L'écran de l'Atelier demande le mod de Valcombe sur votre jeu. Sans lui, la page web : /vc atelier."))))
				.then(Commands.literal("fermer").executes(c -> exec(c, x -> {
					ValcombeMod.tablet().closeAtelier(name(x));
					return "Votre lien d'Atelier ne fonctionne plus. Un nouveau : /vc atelier.";
				})))
				.then(Commands.literal("essai")
					.then(Commands.argument("scenario", StringArgumentType.word())
						.suggests(scenarioIds)
						.executes(c -> exec(c, x -> ses(x).trial(name(x), arg(x, "scenario"), 8)))
						.then(Commands.argument("joueurs", IntegerArgumentType.integer(fr.valcombe.rules.Distribution.MIN_PLAYERS,
								fr.valcombe.rules.Distribution.MAX_PLAYERS))
							.executes(c -> exec(c, x -> ses(x).trial(name(x), arg(x, "scenario"), number(x, "joueurs")))))))
				.then(Commands.literal("supprimer")
					.then(Commands.literal("role")
						.then(Commands.argument("id", StringArgumentType.word())
							.suggests((c, b) -> SharedSuggestionProvider.suggest(ValcombeMod.creations().roles().stream().map(d -> d.id), b))
							.executes(c -> exec(c, x -> ValcombeMod.creations().deleteRole(arg(x, "id"))))))
					.then(Commands.literal("scenario")
						.then(Commands.argument("id", StringArgumentType.word())
							.suggests(scenarioIds)
							.executes(c -> exec(c, x -> ValcombeMod.creations().deleteScenario(arg(x, "id"))))))))
			.then(Commands.literal("figure").requires(leader)
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(Session.FIGURES.keySet(), b))
					.then(Commands.argument("actif", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("oui", "non"), b))
						.executes(c -> exec(c, x -> ses(x).setFigure(arg(x, "id"), !arg(x, "actif").equals("non")))))))
			.then(Commands.literal("voyager")
				.executes(c -> exec(c, x -> {
					if (ses(x).mode() != Session.Mode.AUTO && ses(x).storyteller() != null) {
						throw new fr.valcombe.rules.GameException("Demandez au Conteur : il vous accueille avec /vc voyageur <nom> bien|mal.");
					}
					return ses(x).addTraveller(name(x), null);
				})))
			.then(Commands.literal("voyageur")
				.then(Commands.literal("partir").executes(c -> exec(c, x -> ses(x).travellerLeaves(name(x)))))
				.then(Commands.argument("joueur", StringArgumentType.word()).requires(leader)
					.suggests((c, b) -> SharedSuggestionProvider.suggest(
						c.getSource().getServer().getPlayerList().getPlayers().stream().map(p -> p.getScoreboardName()), b))
					.then(Commands.argument("camp", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("bien", "mal"), b))
						.executes(c -> exec(c, x -> ses(x).addTraveller(arg(x, "joueur"), arg(x, "camp")))))))
			// Clé de l'API d'administration : la console, ou un opérateur de plein droit (niveau 4, celui de /stop et /op).
			.then(Commands.literal("admin").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_OWNER))
				.executes(c -> exec(c, x -> adminStatus()))
				.then(Commands.literal("cle").executes(c -> exec(c, x -> showAdminKey(x.getSource(), false))))
				.then(Commands.literal("nouvellecle").executes(c -> exec(c, x -> showAdminKey(x.getSource(), true)))))
			.then(Commands.literal("soiree").requires(admin)
				.then(Commands.literal("effacer").executes(c -> exec(c, x -> ValcombeMod.community().setNextEvening(""))))
				.then(Commands.argument("quand", StringArgumentType.greedyString())
					.executes(c -> exec(c, x -> ValcombeMod.community().setNextEvening(arg(x, "quand"))))))
			.then(Commands.literal("scenario").requires(leader)
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests((c, b) -> {
						Session s = sesOrNull(c.getSource());
						return SharedSuggestionProvider.suggest(java.util.stream.Stream.concat(java.util.stream.Stream.of("aucun"),
							s == null ? java.util.stream.Stream.<String>empty() : s.scenarios().stream().map(d -> d.id)), b);
					})
					.executes(c -> exec(c, x -> ses(x).useScenario(arg(x, "id"))))))
			.then(Commands.literal("tablette").requires(leader)
				.executes(c -> exec(c, x -> {
					ServerPlayer p = x.getSource().getPlayerOrException();
					Tablet t = ValcombeMod.tablet();
					t.open(p.getScoreboardName());
					p.sendSystemMessage(Component.literal("Grimoire sur tablette ouvert. Ouvrez ce lien sur votre tablette ou téléphone "
						+ "(même réseau, ou port " + ValcombeMod.community().tabletPort() + " ouvert), sans le partager :")
						.withStyle(net.minecraft.ChatFormatting.GOLD));
					for (String link : t.links(p.getScoreboardName())) {
						p.sendSystemMessage(Component.literal("  " + link).withStyle(st -> st
							.withColor(net.minecraft.ChatFormatting.AQUA).withUnderlined(true)
							.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(link)))));
					}
					return "Fermer : /vc tablette fermer. Nouveau lien (l'ancien ne marche plus) : /vc tablette";
				}))
				.then(Commands.literal("fermer").executes(c -> exec(c, x -> {
					// Seule la tablette de ce Conteur est fermée : celles des autres parties et les liens de l'Atelier restent valables.
					ValcombeMod.tablet().closeTablet(x.getSource().getTextName());
					return "Tablette fermée : son lien ne fonctionne plus."
						+ (ValcombeMod.tablet().atelierOpen() ? " L'Atelier reste ouvert (/vc atelier fermer pour le vôtre)." : "");
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
						List.of("nuit_immobile", "nuit_sombre", "nuit_maisons", "nuit_muette", "remise_convocation", "remise_en_etat", "temps_defense", "temps_horloge",
							"effets", "spectateurs_grimoire", "coiffe", "majorite_absolue", "victoire_majorite", "heritiere_sans_seuil",
							"infos_mal_des_5"), b))
					.then(Commands.argument("valeur", StringArgumentType.word())
						.executes(c -> exec(c, x -> ses(x).setting(arg(x, "cle"), arg(x, "valeur")))))));

		registerGames(root, admin);
		registerEditor(root, registry, admin);
		fr.valcombe.mc.worlds.WorldCommands.register(root, admin, ValcombeMod::worlds, ValcombeMod::travel);
		// Construction : invitations aux ateliers, « annuler » commun, baguette, sélection vers structure.
		BuildCommands.register(root, admin);
		// Atelier carte : un monde par carte en cours de travail (voir MapWorkshop).
		root.then(Commands.literal("atelier").requires(admin).then(MapWorkshopCommands.build()));
		BackupCommands.register(root, admin);
		root.then(StructureWorkshopCommands.build());
		var node = dispatcher.register(root);
		dispatcher.register(Commands.literal("valcombe").redirect(node));
	}
}
