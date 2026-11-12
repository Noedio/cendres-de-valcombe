package fr.valcombe.mc;

import fr.valcombe.net.ValcombeNet;
import fr.valcombe.session.Session;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Point d'entrée du mod « Les Cendres de Valcombe » (partie commune et serveur). */
public final class ValcombeMod implements ModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("valcombe");

	private static final McHost HOST = new McHost();
	private static final Session SESSION = new Session(HOST);
	private static final ValcombeUi UI = new ValcombeUi(SESSION, HOST);
	private static final ViewSync SYNC = new ViewSync(SESSION);
	private static Community community;
	private static Tablet tablet;

	/** Statistiques et compte rendu Discord (disponible une fois le serveur démarré). */
	public static Community community() {
		return community;
	}

	/** Grimoire sur tablette (disponible une fois le serveur démarré). */
	public static Tablet tablet() {
		return tablet;
	}

	static boolean isLeader(ServerPlayer p) {
		return SYNC.isLeader(p);
	}

	/** La table de jeu du serveur (utilisée par le plugin vocal). */
	/** Bâton du Conteur : grimoire du joueur visé, aparté (accroupi), ou panneau du Conteur. */
	private static void useStaff(ServerPlayer sp, String target) {
		if (!SYNC.isLeader(sp)) {
			sp.sendSystemMessage(Gui.plain("Ce bâton est celui du Conteur.", net.minecraft.ChatFormatting.GRAY));
			return;
		}
		var game = SESSION.game();
		if (sp.isShiftKeyDown() && game != null && (game.phase() == fr.valcombe.rules.Game.Phase.READY || SESSION.summoned() != null)) {
			// Avant la première nuit (ou pendant une convocation) : le bâton convoque dans la salle à l'écart.
			String cmd = target != null ? "vc convoquer " + target : "vc convoquer fin";
			sp.level().getServer().getCommands().performPrefixedCommand(sp.createCommandSourceStack(), cmd);
		} else if (sp.isShiftKeyDown()) {
			String cmd = target != null ? "vc aparte " + target : "vc aparte fin";
			sp.level().getServer().getCommands().performPrefixedCommand(sp.createCommandSourceStack(), cmd);
		} else if (target != null) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(sp,
				new fr.valcombe.net.ValcombeNet.OpenPayload("seat:" + target));
		} else {
			UI.openStoryteller(sp);
		}
	}

	private static CreationStore creations;
	private static MapStore maps;

	public static CreationStore creations() {
		return creations;
	}

	public static MapStore maps() {
		return maps;
	}

	private static StructureStore structures;
	private static MapEditor editor;

	/** Catalogue de structures plaçables (disponible une fois le serveur démarré). */
	public static StructureStore structures() {
		return structures;
	}

	/** Éditeur de carte du mode construction (disponible une fois le serveur démarré). */
	public static MapEditor editor() {
		return editor;
	}

	private static Workshop workshop;

	/** Ce que l'Atelier montre des cartes et des structures, pour la page web et l'écran en jeu ; null avant le démarrage du serveur. */
	public static Workshop workshop() {
		return workshop;
	}

	/** Un outil de l'éditeur (sélection ou structure) vient de servir : le résultat est dit au joueur. */
	private static void editorClick(ServerPlayer sp, java.util.function.Supplier<String> action) {
		try {
			String msg = action.get();
			if (msg != null && !msg.isEmpty()) {
				sp.sendSystemMessage(Gui.plain(msg, net.minecraft.ChatFormatting.GREEN));
			}
		} catch (fr.valcombe.rules.GameException e) {
			sp.sendSystemMessage(Gui.plain(e.getMessage(), net.minecraft.ChatFormatting.RED));
		}
	}

	private static String baseMotd;

	/**
	 * Description du serveur (liste des serveurs, launcher) : la prochaine soirée et le salon.
	 * Le launcher la lit pour afficher « Prochaine soirée » sur son accueil.
	 */
	private static void updateMotd(net.minecraft.server.MinecraftServer server) {
		if (community == null || baseMotd == null) {
			return;
		}
		String evening = community.nextEvening();
		String state;
		if (SESSION.game() != null) {
			state = "Partie en cours (" + SESSION.game().size() + " joueurs)";
		} else {
			int n = SESSION.lobby().size();
			state = n == 0 ? "Salon ouvert" : n + " inscrit(s) au salon";
			if (SESSION.scenario() != null) {
				state += " · « " + SESSION.scenario().nom + " »";
			}
		}
		String motd = (evening.isEmpty() ? "Les Cendres de Valcombe" : "Prochaine soirée : " + evening) + "\n" + state;
		if (!motd.equals(server.getMotd())) {
			server.setMotd(motd);
		}
	}

	public static McHost host() {
		return HOST;
	}

	public static Session session() {
		return SESSION;
	}

	@Override
	public void onInitialize() {
		ValcombeNet.registerPayloads();
		HOST.setUi(UI);
		// Dalles de la salle d'attente
		HOST.setPadHandler((p, pad) -> {
			String name = p.getScoreboardName();
			try {
				String msg = switch (pad) {
					case JOIN -> SESSION.lobby().contains(name) ? "Tu es déjà inscrit." : SESSION.join(name);
					case LEAVE -> SESSION.lobby().contains(name) ? SESSION.leave(name) : "Tu n'es pas inscrit.";
					case STORYTELLER -> p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)
						? SESSION.toggleStoryteller(name) : "Seul un opérateur peut devenir Conteur.";
				};
				p.sendSystemMessage(Gui.plain(msg, net.minecraft.ChatFormatting.GREEN));
			} catch (fr.valcombe.rules.GameException e) {
				p.sendSystemMessage(Gui.plain(e.getMessage(), net.minecraft.ChatFormatting.RED));
			}
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			HOST.setServer(server);
			community = new Community(server.getServerDirectory().resolve("config"));
			HOST.setCommunity(community);
			tablet = new Tablet(SESSION, community);
			tablet.setServer(server);
			// Créations de l'Atelier : chargées avant la reprise d'une partie (elles peuvent en contenir les rôles).
			creations = new CreationStore(server.getServerDirectory().resolve("config"), SESSION);
			maps = new MapStore(creations.dir(), HOST);
			maps.setServer(server);
			creations.setMaps(maps);
			HOST.setMaps(maps);
			structures = new StructureStore(creations.dir());
			structures.setServer(server);
			creations.setStructures(structures);
			editor = new MapEditor(HOST, structures);
			editor.setServer(server);
			HOST.setEditor(editor);
			creations.load();
			SESSION.carnetSource = community::carnet;
			SESSION.modVersion = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("valcombe")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
			baseMotd = server.getMotd();
			workshop = new Workshop(server, SESSION, creations, maps, HOST, structures, editor);
			Atelier atelier = new Atelier(SESSION, creations, maps, HOST, structures, editor, workshop);
			atelier.setServer(server);
			tablet.setAtelier(atelier);
			HOST.prepareWorld();
			String saved = HOST.savedGame();
			if (saved != null) {
				try {
					SESSION.restore(saved);
				} catch (RuntimeException e) {
					LOG.error("Sauvegarde de partie illisible : ignorée", e);
				}
			}
			HOST.resumeRestore(); // carte laissée en l'état par un arrêt en pleine partie ou en pleine remise en état
			SESSION.setSavedScripts(ScriptStore.list(server));
			tablet.openForHost(server.getServerDirectory().resolve("config").resolve("valcombe-hote.json"));
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			try {
				SESSION.saveNow(); // la partie en cours reprendra au prochain démarrage
			} catch (RuntimeException e) {
				LOG.error("Sauvegarde de la partie impossible à l'arrêt", e);
			}
			HOST.setServer(null);
			workshop = null;
			if (editor != null) {
				editor.setServer(null);
			}
			if (tablet != null) {
				tablet.close();
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			// Chaque partie du tick est protégée : une erreur d'affichage ne doit pas arrêter le serveur ni la partie.
			guarded("la partie", HOST::onTick);
			guarded("la vue des joueurs", () -> SYNC.onTick(server));
			if (server.getTickCount() % 5 == 0) {
				guarded("le vocal", () -> VoiceRouter.update(server, SESSION, HOST.village()));
			}
			if (server.getTickCount() % 40 == 0) {
				guarded("l'accueil", () -> {
					updateMotd(server);
					HOST.updateHall(SESSION);
				});
			}
			if (server.getTickCount() % 1200 == 600) {
				guarded("la sauvegarde", SESSION::saveNow);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer p = handler.getPlayer();
			UI.giveMenuItem(p);
			var g = SESSION.game();
			HOST.onJoin(p, g != null && g.seatOf(p.getScoreboardName()) >= 0);
			// Une seconde plus tard : le client a eu le temps de s'annoncer (écrans du mod ou menus coffre).
			String joined = p.getScoreboardName();
			HOST.schedule(20, () -> SESSION.onJoin(joined));
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			String name = handler.getPlayer().getScoreboardName();
			SYNC.forget(name);
			if (workshop != null) {
				workshop.forget(name);
			}
			SESSION.onDisconnect(name);
		});
		// Actions des écrans du mod client : ce sont des commandes /vc, vérifiées comme si elles étaient tapées.
		ServerPlayNetworking.registerGlobalReceiver(ValcombeNet.ActionPayload.TYPE, (payload, context) -> {
			String cmd = payload.command();
			if (cmd.equals("vc") || cmd.startsWith("vc ")) {
				ServerPlayer p = context.player();
				Workshop w = workshop;
				if (w == null || !Workshop.shows(cmd) || !Atelier.isOperator(p)) {
					p.level().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), cmd);
					return;
				}
				// Commande de l'Atelier en jeu : la réponse va dans le chat comme d'habitude, et l'écran, qui le cache, la reçoit aussi.
				StringBuilder out = new StringBuilder();
				boolean[] failed = new boolean[1];
				net.minecraft.commands.CommandSource capture = new net.minecraft.commands.CommandSource() {
					@Override
					public void sendSystemMessage(net.minecraft.network.chat.Component message) {
						out.append(out.length() == 0 ? "" : " ").append(message.getString());
						var color = message.getStyle().getColor();
						failed[0] |= color != null && color.equals(net.minecraft.network.chat.TextColor.fromLegacyFormat(
							net.minecraft.ChatFormatting.RED));
						p.sendSystemMessage(message);
					}

					@Override
					public boolean acceptsSuccess() {
						return true;
					}

					@Override
					public boolean acceptsFailure() {
						return true;
					}

					@Override
					public boolean shouldInformAdmins() {
						return false;
					}
				};
				p.level().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack().withSource(capture), cmd);
				w.reply(p.getScoreboardName(), out.toString(), failed[0]);
			}
		});
		// Clic droit avec le grimoire « Menu de Valcombe » : ouvre le menu (le mod client ouvre le sien).
		UseItemCallback.EVENT.register((player, level, hand) -> {
			// Plume d'accusation : pendant un vote, lever ou baisser la main ; sinon accuser le joueur visé, même de loin.
			if (ValcombeUi.isAccuseItem(player.getItemInHand(hand))) {
				if (player instanceof ServerPlayer sp && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
					String name = sp.getScoreboardName();
					String cmd = null;
					if (SESSION.voteOpen()) {
						cmd = SESSION.handRaised(name) ? "vc vote non" : "vc vote oui";
					} else {
						String target = HOST.aimedPlayer(sp);
						if (target != null) {
							cmd = "vc nominer " + target;
						} else {
							sp.sendSystemMessage(Gui.plain("Vise un joueur (ou son trône), puis clic droit.",
								net.minecraft.ChatFormatting.GRAY));
						}
					}
					if (cmd != null) {
						sp.level().getServer().getCommands().performPrefixedCommand(sp.createCommandSourceStack(), cmd);
					}
				}
				return InteractionResult.SUCCESS;
			}
			if (ValcombeUi.isStaffItem(player.getItemInHand(hand))) {
				if (player instanceof ServerPlayer sp && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
					useStaff(sp, HOST.aimedPlayer(sp));
				}
				return InteractionResult.SUCCESS;
			}
			if (ValcombeUi.isMenuItem(player.getItemInHand(hand))) {
				if (player instanceof ServerPlayer sp) {
					UI.openMain(sp);
				}
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
		// Baguette de carte : poser sièges et maisons d'un clic sur un bloc (opérateurs)
		// Lutrin du Conteur, sur la place : un clic ouvre le grimoire
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(player instanceof ServerPlayer sp)) {
				return InteractionResult.PASS;
			}
			if (entity instanceof ServerPlayer target && ValcombeUi.isStaffItem(sp.getItemInHand(hand))) {
				if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
					useStaff(sp, target.getScoreboardName());
				}
				return InteractionResult.SUCCESS;
			}
			// Plume d'accusation : clic droit sur un joueur pour le nominer
			if (entity instanceof ServerPlayer target && ValcombeUi.isAccuseItem(sp.getItemInHand(hand))) {
				if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
					sp.level().getServer().getCommands().performPrefixedCommand(sp.createCommandSourceStack(),
						"vc nominer " + target.getScoreboardName());
				}
				return InteractionResult.SUCCESS;
			}
			if (!entity.entityTags().contains("vc_lutrin")) {
				return InteractionResult.PASS;
			}
			if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
				if (SYNC.isLeader(sp)) {
					UI.openStoryteller(sp);
				} else {
					sp.sendSystemMessage(Gui.plain("Le grimoire est réservé au Conteur.", net.minecraft.ChatFormatting.GRAY));
				}
			}
			return InteractionResult.SUCCESS;
		});
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			ValcombeUi.MapTool tool = ValcombeUi.mapTool(player.getItemInHand(hand));
			if (tool == null) {
				// Un bloc posé à la main sur la carte : elle ne sera plus remplacée sans sauvegarde.
				if (player instanceof ServerPlayer builder
					&& player.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BlockItem) {
					HOST.noteEdit(builder, hit.getBlockPos());
				}
				// Trône : clic droit pour s'asseoir
				if (player instanceof ServerPlayer sp && !player.isShiftKeyDown() && !ValcombeUi.isAccuseItem(player.getItemInHand(hand))
					&& HOST.village() != null && HOST.village().seatIndexAt(hit.getBlockPos()) >= 0) {
					if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
						HOST.sitAt(sp, hit.getBlockPos());
					}
					return InteractionResult.SUCCESS;
				}
				return InteractionResult.PASS;
			}
			if (player instanceof ServerPlayer sp
				&& sp.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
				if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) {
					return InteractionResult.SUCCESS;
				}
				// Outils de l'éditeur : second coin de la sélection ; poser la structure en main, ou la tourner (accroupi).
				if (tool == ValcombeUi.MapTool.SELECT || tool == ValcombeUi.MapTool.STRUCTURE) {
					var clicked = hit.getBlockPos();
					if (editor != null) {
						editorClick(sp, () -> tool == ValcombeUi.MapTool.SELECT ? editor.setCorner(sp, 2, clicked)
							: sp.isShiftKeyDown() ? editor.rotate(sp) : editor.place(sp, clicked));
					}
					return InteractionResult.SUCCESS;
				}
				var at = hit.getBlockPos().above();
				try {
					String msg = HOST.useTool(sp, tool, hit.getBlockPos(), sp.isShiftKeyDown());
					sp.sendSystemMessage(Gui.plain(msg, net.minecraft.ChatFormatting.GREEN));
					WorldSetup.run(sp.level().getServer(), String.format(java.util.Locale.ROOT,
						"particle minecraft:happy_villager %d %d %d 0.3 0.3 0.3 0 12", at.getX(), at.getY(), at.getZ()));
				} catch (fr.valcombe.rules.GameException e) {
					sp.sendSystemMessage(Gui.plain(e.getMessage(), net.minecraft.ChatFormatting.RED));
				}
			}
			return InteractionResult.SUCCESS;
		});
		// Clic gauche avec un outil de l'éditeur : premier coin de la sélection, ou miroir de la structure en main.
		// Le bloc visé n'est pas cassé (des deux côtés : le client n'a pas à le voir disparaître puis revenir).
		net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			ValcombeUi.MapTool tool = ValcombeUi.mapTool(player.getItemInHand(hand));
			if (tool != ValcombeUi.MapTool.SELECT && tool != ValcombeUi.MapTool.STRUCTURE) {
				return InteractionResult.PASS;
			}
			if (player instanceof ServerPlayer sp && editor != null && hand == net.minecraft.world.InteractionHand.MAIN_HAND
				&& sp.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
				var clicked = pos.immutable();
				if (tool == ValcombeUi.MapTool.STRUCTURE) {
					editorClick(sp, () -> editor.mirror(sp));
				} else if (!editor.isCorner(sp, 1, clicked)) { // un clic maintenu redemande le même coin : une seule réponse
					editorClick(sp, () -> editor.setCorner(sp, 1, clicked));
				}
			}
			return InteractionResult.SUCCESS;
		});
		// Un bloc cassé à la main sur la carte, hors partie : même suivi que pour un bloc posé.
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (player instanceof ServerPlayer sp) {
				HOST.noteEdit(sp, pos);
			}
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
			ValcombeCommands.register(dispatcher, registry, SESSION, UI));
		LOG.info("Les Cendres de Valcombe : prêt.");
	}

	private static final java.util.Map<String, Integer> TICK_ERRORS = new java.util.HashMap<>();

	/** Exécute une partie du tick ; une erreur est journalisée (dix fois au plus par sujet) sans rien arrêter. */
	private static void guarded(String what, Runnable r) {
		try {
			r.run();
		} catch (RuntimeException | LinkageError | StackOverflowError e) {
			if (TICK_ERRORS.merge(what, 1, Integer::sum) <= 10) {
				LOG.error("Erreur pendant le tick (" + what + ")", e);
			}
		}
	}
}
