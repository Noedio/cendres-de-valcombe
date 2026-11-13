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
	/** Moment du dernier tick mené à son terme : un serveur vide, en veille, n'en fait plus. */
	private static volatile long lastTick = System.nanoTime();
	private static volatile boolean idleWork;
	private static java.util.concurrent.ScheduledExecutorService idleClock;

	/**
	 * Un serveur vide depuis une minute se met en veille et n'appelle plus le mod. Un chantier lancé depuis
	 * l'Atelier web, sans personne en jeu, resterait en plan : ce minuteur le fait avancer au rythme des ticks.
	 */
	private static void startIdleClock(net.minecraft.server.MinecraftServer server) {
		int[] beat = {0};
		idleClock = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "valcombe-veille");
			t.setDaemon(true);
			return t;
		});
		idleClock.scheduleAtFixedRate(() -> {
			if (System.nanoTime() - lastTick < 500_000_000L) {
				return; // le serveur tourne : il s'en charge
			}
			// Sans chantier, un coup d'œil par seconde suffit à voir celui qu'on vient de lancer.
			if (idleWork || beat[0]++ % 20 == 0) {
				server.execute(() -> {
					// Seulement sans joueur : en solo, un jeu mis en pause doit le rester.
					idleWork = server.getPlayerCount() == 0 && HOST.hasWork();
					if (idleWork && System.nanoTime() - lastTick >= 500_000_000L) {
						guarded("le chantier", HOST::onTick);
					}
				});
			}
		}, 1000, 50, java.util.concurrent.TimeUnit.MILLISECONDS);
	}
	private static Community community;
	private static Tablet tablet;

	// ------------------------------------------------------------------
	// API d'administration (voir AdminApi et docs/ADMIN-API.md)
	// ------------------------------------------------------------------
	/** Dernières lignes de la console, pour GET /admin/console : rempli dès le chargement du mod. */
	private static final fr.valcombe.admin.ConsoleBuffer CONSOLE = new fr.valcombe.admin.ConsoleBuffer(1000);
	/** Essais ratés de clé, par adresse : gardés d'un démarrage du serveur à l'autre dans le même processus. */
	private static final fr.valcombe.admin.AdminGuard ADMIN_GUARD = new fr.valcombe.admin.AdminGuard();
	private static AdminApi admin;
	private static volatile fr.valcombe.admin.WorldsView worldsView = fr.valcombe.admin.WorldsView.NONE;

	/** API d'administration (disponible une fois le serveur démarré). */
	static AdminApi admin() {
		return admin;
	}

	/** Clé de l'API d'administration (disponible une fois le serveur démarré), pour les commandes et les tests en jeu. */
	public static fr.valcombe.admin.AdminKey adminKey() {
		return admin == null ? null : admin.key();
	}

	/** Branche la liste des mondes montrée par l'API d'administration (gestionnaire de mondes). */
	public static void setWorldsView(fr.valcombe.admin.WorldsView view) {
		worldsView = view == null ? fr.valcombe.admin.WorldsView.NONE : view;
	}

	static fr.valcombe.admin.WorldsView worldsView() {
		return worldsView;
	}

	private static volatile BackupService backups;

	/** Gestionnaire des sauvegardes (disponible une fois le serveur démarré, jusqu'à son arrêt). */
	public static BackupService backups() {
		return backups;
	}

	/** Version du mod Valcombe. */
	static String modVersion() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("valcombe")
			.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	/**
	 * Une partie est-elle en cours (joueurs assis, de la mise en place à la fin) ? Une sauvegarde automatique attend
	 * alors la fin de la partie (réglage pendantPartie). Fil du serveur.
	 */
	static boolean gameInProgress() {
		var g = SESSION.game();
		return g != null && g.phase() != fr.valcombe.rules.Game.Phase.SETUP && g.phase() != fr.valcombe.rules.Game.Phase.ENDED;
	}

	/** Le serveur est-il en veille (vide depuis une minute, il ne fait plus de tick) ? */
	static boolean asleep() {
		return System.nanoTime() - lastTick > 2_000_000_000L;
	}

	/** Met à jour tout de suite la description du serveur (après un changement de message d'accueil ou de soirée). */
	static void refreshMotd(net.minecraft.server.MinecraftServer server) {
		updateMotd(server);
	}

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
	private static fr.valcombe.mc.worlds.WorldManager worlds;

	/**
	 * Gestionnaire des mondes de Valcombe (hub, parties, ateliers) ; null avant le démarrage du serveur, ou si
	 * les mondes n'ont pas pu être préparés (le jeu dans le monde principal continue alors sans eux).
	 */
	public static fr.valcombe.mc.worlds.WorldManager worlds() {
		return worlds;
	}

	private static MapPlacer placer;

	/**
	 * Pose des cartes de la bibliothèque dans les mondes de Valcombe, et capture inverse ; null tant que les mondes
	 * ne sont pas prêts (voir worlds()).
	 */
	public static MapPlacer placer() {
		return placer;
	}

	private static StructureWorkshop structureWorkshop;

	/** L'atelier structures (toutes les structures sur leurs parcelles) ; null sans les mondes de Valcombe. */
	public static StructureWorkshop structureWorkshop() {
		return structureWorkshop;
	}

	private static fr.valcombe.mc.hub.Travel travel;
	private static fr.valcombe.mc.hub.Hub hub;

	/** Voyages entre les mondes (dernière position gardée par monde) ; null sans les mondes de Valcombe. */
	public static fr.valcombe.mc.hub.Travel travel() {
		return travel;
	}

	/** Le hub, accueil commun des joueurs ; null sans les mondes de Valcombe, ou si le hub n'a pas pu être préparé. */
	public static fr.valcombe.mc.hub.Hub hub() {
		return hub;
	}

	/**
	 * Hub et voyages, branchés sur le gestionnaire de mondes : repli des mondes remis à neuf ou supprimés, arrivée
	 * des joueurs, dalles d'inscription de la salle d'accueil. Sans hub, les voyages marchent quand même (le repli
	 * se fait alors au village).
	 */
	private static void setUpHub(net.minecraft.server.MinecraftServer server, fr.valcombe.mc.worlds.WorldManager manager) {
		var positions = new fr.valcombe.mc.hub.LastPositions(fr.valcombe.mc.hub.LastPositions.fileIn(
			server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize()));
		positions.load();
		var t = new fr.valcombe.mc.hub.Travel(server, manager, positions, HOST, SESSION);
		manager.setFallback(t);
		manager.addListener(t);
		travel = t;
		try {
			var h = new fr.valcombe.mc.hub.Hub(server, manager, t, SESSION);
			h.start();
			manager.addListener(h);
			HOST.setHubPads(h::padAt);
			HOST.setHubArrival(t::toHubArrival);
			hub = h;
		} catch (RuntimeException | LinkageError e) {
			LOG.error("Hub de Valcombe indisponible : les joueurs arrivent au village", e);
		}
	}

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
		String motd = (evening.isEmpty() ? community.welcomeShown() : "Prochaine soirée : " + evening) + "\n" + state;
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
		// La console est copiée dès maintenant : GET /admin/console montre aussi le démarrage du serveur.
		if (!fr.valcombe.admin.ConsoleCapture.install(CONSOLE)) {
			LOG.warn("Console du serveur non copiée : l'API d'administration la montrera vide.");
		}
		// Restauration prévue (POST /admin/sauvegardes/restaurer, /vc sauvegarde restaurer) : sur un serveur dédié, le
		// mod est initialisé avant que Minecraft ne lise server.properties et n'ouvre le monde. C'est le seul moment où
		// l'on peut remplacer les fichiers des mondes.
		if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.SERVER) {
			BackupService.restoreAtStartup(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir(), modVersion());
		}
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
			setUpAdmin(server);
			// Sauvegardes : réglages, archives interrompues effacées, sauvegardes automatiques (serveur dédié).
			try {
				BackupService b = new BackupService(server, modVersion(), SESSION::saveNow, ValcombeMod::gameInProgress);
				b.start();
				backups = b;
			} catch (RuntimeException e) {
				LOG.error("Sauvegardes indisponibles", e);
			}
			HOST.prepareWorld();
			// Mondes de Valcombe (Fantasy) : rouverts avant l'arrivée des joueurs, qui peuvent s'y être déconnectés.
			try {
				var manager = new fr.valcombe.mc.worlds.WorldManager(server, fr.valcombe.mc.worlds.WorldManager.defaultFile(server));
				manager.start();
				worlds = manager;
				placer = new MapPlacer(maps, manager, server);
				setWorldsView(manager::adminView); // la liste des mondes de l'API d'administration (GET /admin/etat)
				setUpHub(server, manager);
				structureWorkshop = new StructureWorkshop(server, manager, structures);
				manager.addListener(structureWorkshop);
			} catch (RuntimeException | LinkageError e) {
				LOG.error("Mondes de Valcombe indisponibles : le jeu continue dans le monde principal", e);
			}
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
			openAdmin(server);
			startIdleClock(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (idleClock != null) {
				idleClock.shutdownNow();
				idleClock = null;
			}
			// Une copie en cours est abandonnée avant que Minecraft n'enregistre les mondes (la sauvegarde d'arrêt suit).
			if (backups != null) {
				backups.stopping();
			}
			try {
				SESSION.saveNow(); // la partie en cours reprendra au prochain démarrage
			} catch (RuntimeException e) {
				LOG.error("Sauvegarde de la partie impossible à l'arrêt", e);
			}
			HOST.setServer(null);
			if (admin != null) {
				admin.setServer(null); // les requêtes qui arrivent pendant l'arrêt reçoivent « pas prêt »
			}
			workshop = null;
			if (placer != null) {
				placer.stop(); // les poses en cours sont abandonnées avant que les mondes ne se ferment
				placer = null;
			}
			if (structureWorkshop != null) {
				structureWorkshop.stop();
				structureWorkshop = null;
			}
			if (travel != null) {
				try {
					travel.stop(); // la position de chacun dans son monde, pour le prochain voyage
				} catch (RuntimeException e) {
					LOG.error("Positions des joueurs non enregistrées à l'arrêt", e);
				}
			}
			HOST.setHubPads(null);
			HOST.setHubArrival(null);
			hub = null;
			travel = null;
			if (worlds != null) {
				setWorldsView(null);
				worlds.stop();
				worlds = null;
			}
			if (editor != null) {
				editor.setServer(null);
			}
			if (tablet != null) {
				tablet.close();
			}
		});
		// Mondes enregistrés et fermés : la sauvegarde d'arrêt (serveur dédié), avant que le processus ne se termine.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			BackupService b = backups;
			backups = null;
			if (b != null) {
				try {
					b.stopped();
				} catch (RuntimeException e) {
					LOG.error("Sauvegarde d'arrêt impossible", e);
				}
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			lastTick = System.nanoTime();
			// Chaque partie du tick est protégée : une erreur d'affichage ne doit pas arrêter le serveur ni la partie.
			guarded("la partie", HOST::onTick);
			guarded("la vue des joueurs", () -> SYNC.onTick(server));
			var w = worlds;
			if (w != null) {
				guarded("les mondes", w::tick);
			}
			var pl = placer;
			if (pl != null) {
				guarded("les cartes des mondes", pl::tick);
			}
			var sw = structureWorkshop;
			if (sw != null) {
				guarded("l'atelier structures", sw::tick);
			}
			var t = travel;
			if (t != null) {
				guarded("les voyages", t::tick);
			}
			var h = hub;
			if (h != null) {
				guarded("le hub", h::tick);
			}
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
			boolean seated = g != null && g.seatOf(p.getScoreboardName()) >= 0;
			// Arrivée au hub, sauf pour qui la partie en cours retient au village : sa place, ou le Conteur.
			boolean stay = seated || (g != null && g.winner() == null && p.getScoreboardName().equals(SESSION.storyteller()));
			HOST.onJoin(p, seated, stay);
			// Une seconde plus tard : le client a eu le temps de s'annoncer (écrans du mod ou menus coffre).
			String joined = p.getScoreboardName();
			HOST.schedule(20, () -> SESSION.onJoin(joined));
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			String name = handler.getPlayer().getScoreboardName();
			var t = travel;
			if (t != null) {
				guarded("les voyages", () -> t.onDisconnect(handler.getPlayer()));
			}
			SYNC.forget(name);
			if (workshop != null) {
				workshop.forget(name);
			}
			SESSION.onDisconnect(name);
		});
		// Mort dans un monde de Valcombe : on réapparaît dans ce monde-là, pas au village.
		net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((before, after, alive) -> {
			var t = travel;
			if (!alive && t != null) {
				guarded("les voyages", () -> t.afterRespawn(before, after));
			}
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
		// Les objets et outils de Valcombe passent avant les autres mods (WorldEdit réagit aussi à la hache en bois
		// et à la boussole) : les trois écouteurs de clic ci-dessous sont inscrits dans la phase ToolPriority.PHASE.
		ToolPriority.install();
		// Clic droit avec le grimoire « Menu de Valcombe » : ouvre le menu (le mod client ouvre le sien).
		UseItemCallback.EVENT.register(ToolPriority.PHASE, (player, level, hand) -> {
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
		// Pupitres de l'atelier structures : un clic droit ouvre l'écran de l'atelier sur la parcelle.
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(ToolPriority.PHASE, (player, level, hand, hit) -> {
			var sw = structureWorkshop;
			// Accroupi, le clic reste celui de Minecraft (poser un bloc contre le pupitre).
			if (sw != null && player instanceof ServerPlayer sp && level instanceof net.minecraft.server.level.ServerLevel sl && !sp.isShiftKeyDown()) {
				try {
					if (sw.onUse(sp, sl, hit.getBlockPos(), hand == net.minecraft.world.InteractionHand.MAIN_HAND)) {
						return InteractionResult.SUCCESS;
					}
				} catch (fr.valcombe.rules.GameException e) {
					sp.sendSystemMessage(Gui.plain(e.getMessage(), net.minecraft.ChatFormatting.RED));
					return InteractionResult.SUCCESS;
				}
			}
			return InteractionResult.PASS;
		});
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(ToolPriority.PHASE, (player, level, hand, hit) -> {
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
					if (msg.isEmpty()) {
						return InteractionResult.SUCCESS; // le menu de l'objet visé s'est ouvert
					}
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
		net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register(ToolPriority.PHASE, (player, level, hand, pos, direction) -> {
			ValcombeUi.MapTool tool = ValcombeUi.mapTool(player.getItemInHand(hand));
			if (tool == ValcombeUi.MapTool.HAND) {
				return InteractionResult.SUCCESS; // la main ne casse rien : elle ne fait que prendre
			}
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

	/**
	 * Prépare l'API d'administration : clé (tirée au premier démarrage), drapeau de redémarrage retiré, branchement
	 * sur le serveur web de la tablette. Elle n'est ouverte que plus tard (openAdmin), une fois le serveur prêt.
	 */
	private static void setUpAdmin(net.minecraft.server.MinecraftServer server) {
		java.nio.file.Path dir = server.getServerDirectory().resolve("config").resolve("valcombe");
		try {
			// Le serveur vient de (re)démarrer : un drapeau laissé là ne doit pas relancer un serveur arrêté plus tard pour de bon.
			if (fr.valcombe.admin.RestartFlag.clear(dir)) {
				LOG.info("[valcombe] Redémarrage demandé par l'API d'administration : le serveur est reparti.");
			}
		} catch (java.io.IOException e) {
			LOG.warn("Drapeau de redémarrage impossible à retirer : {}", e.getMessage());
		}
		fr.valcombe.admin.AdminKey key = new fr.valcombe.admin.AdminKey(dir);
		boolean created;
		try {
			created = key.load();
		} catch (java.io.IOException | RuntimeException e) {
			LOG.error("API d'administration désactivée : {} illisible ou impossible à écrire ({})", key.file(), e.getMessage());
			admin = null;
			return;
		}
		String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("valcombe")
			.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
		admin = new AdminApi(key, ADMIN_GUARD, CONSOLE, community, dir, version);
		admin.setServer(server);
		tablet.setAdmin(admin);
		if (created && server.isDedicatedServer()) {
			LOG.info("[valcombe] Clé d'administration du serveur (pour le launcher) : {}", key.key());
			LOG.info("[valcombe] Elle est gardée dans config/valcombe/admin.json. /vc admin cle la réaffiche, "
				+ "/vc admin nouvellecle la remplace. Ne la partagez pas : elle donne tout pouvoir sur le serveur.");
		}
	}

	/**
	 * Sur un serveur dédié, l'API d'administration est ouverte d'office (sauf si admin.json la désactive) :
	 * le serveur web reste alors en service jusqu'à l'arrêt. Une partie solo ne l'ouvre pas.
	 */
	private static void openAdmin(net.minecraft.server.MinecraftServer server) {
		if (admin == null || !server.isDedicatedServer()) {
			return;
		}
		if (!admin.key().enabled()) {
			LOG.info("[valcombe] API d'administration désactivée (config/valcombe/admin.json).");
			return;
		}
		try {
			int port = tablet.openAdmin();
			LOG.info("[valcombe] API d'administration prête sur le port {} ({}). Clé : /vc admin cle", port,
				admin.key().localOnly() ? "requêtes de cette machine seulement" : "requêtes de toute adresse");
		} catch (java.io.IOException | RuntimeException e) {
			LOG.warn("[valcombe] API d'administration indisponible : {}", e.getMessage());
		}
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
