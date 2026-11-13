package fr.valcombe.mc;

import fr.valcombe.mc.games.GameRoom;
import fr.valcombe.mc.games.Games;
import fr.valcombe.net.ValcombeNet;

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

/**
 * Point d'entrée du mod « Les Cendres de Valcombe » (partie commune et serveur).
 * <p>
 * Il n'y a plus de partie unique : chaque monde de partie porte la sienne (voir {@link Games}, qui les ouvre, les
 * ferme et les retrouve). Ce qui reste ici est commun à toutes : la bibliothèque, l'Atelier, la tablette, l'API
 * d'administration, les sauvegardes, les mondes, le hub, la vue envoyée aux joueurs et le vocal. Un geste de joueur
 * (objet en main, clic, commande) vise toujours la partie du monde où il se trouve.
 */
public final class ValcombeMod implements ModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("valcombe");

	/** La vue de chaque joueur, celle de sa partie (voir ViewSync). */
	private static final ViewSync SYNC = new ViewSync();
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
					// « Envoyer à la partie » depuis l'Atelier web compte aussi : remise à neuf du monde, puis pose de la
					// carte, chacune avancée par son propre tick.
					var w = worlds;
					var pl = placer;
					var ms = mapSender;
					var g = Games.current();
					idleWork = server.getPlayerCount() == 0
						&& (Games.anyWork() || (ms != null && ms.working()) || (w != null && w.working()));
					if (idleWork && System.nanoTime() - lastTick >= 500_000_000L) {
						if (g != null) {
							guarded("les parties", g::tick);
						}
						if (w != null) {
							guarded("les mondes", w::tick);
						}
						if (pl != null) {
							guarded("les cartes des mondes", pl::tick);
						}
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
	 * Une partie est-elle en cours, dans l'un quelconque des mondes de partie (joueurs assis, de la mise en place à la
	 * fin) ? Une sauvegarde automatique attend alors la fin des parties (réglage pendantPartie). Fil du serveur.
	 */
	static boolean gameInProgress() {
		return Games.anyInProgress();
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

	/** Ce joueur mène-t-il la partie qu'il voit (opérateur, Conteur de sa partie) ? */
	static boolean isLeader(ServerPlayer p) {
		return ViewSync.isLeader(p);
	}

	/** La partie du monde où se trouve ce joueur, sinon celle qu'il vise depuis le hub (sa table, la principale). */
	private static GameRoom roomOf(ServerPlayer p) {
		GameRoom r = Games.of(p);
		return r != null ? r : Games.forPlayer(p);
	}

	/** Une fiche du tableau des parties du hub, cliquée : l'écran « Parties » sur elle (sans le mod client, la fiche au chat). */
	private static void openBoardCard(ServerPlayer sp, net.minecraft.world.entity.Entity card) {
		String id = fr.valcombe.mc.hub.Hub.boardCard(card);
		GameRoom room = id == null ? null : Games.byWorld(id);
		if (room == null) {
			sp.sendSystemMessage(Gui.plain("Cette partie n'existe plus : le tableau se met à jour.", net.minecraft.ChatFormatting.GRAY));
			return;
		}
		guarded("le tableau des parties", () -> Games.showCard(sp, room));
	}

	/** Bâton du Conteur : grimoire du joueur visé, aparté (accroupi), ou panneau du Conteur, dans la partie de son monde. */
	private static void useStaff(ServerPlayer sp, String target) {
		GameRoom room = roomOf(sp);
		if (room == null || !room.isLeader(sp)) {
			sp.sendSystemMessage(Gui.plain("Ce bâton est celui du Conteur.", net.minecraft.ChatFormatting.GRAY));
			return;
		}
		var session = room.session();
		var game = session.game();
		if (sp.isShiftKeyDown() && game != null && (game.phase() == fr.valcombe.rules.Game.Phase.READY || session.summoned() != null)) {
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
			room.ui().openStoryteller(sp);
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
	 * les mondes n'ont pas pu être préparés (les parties, qui se jouent dans les mondes de partie, sont alors indisponibles).
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

	private static MapSender mapSender;

	/** « Envoyer à la partie » (voir MapSender) ; null tant que les mondes ne sont pas prêts (voir worlds()). */
	public static MapSender sender() {
		return mapSender;
	}

	private static StructureWorkshop structureWorkshop;

	/** L'atelier structures (toutes les structures sur leurs parcelles) ; null sans les mondes de Valcombe. */
	public static StructureWorkshop structureWorkshop() {
		return structureWorkshop;
	}

	private static MapWorkshop mapWorkshop;

	/** L'atelier carte (un monde par carte en cours de travail) ; null sans les mondes de Valcombe. */
	public static MapWorkshop mapWorkshop() {
		return mapWorkshop;
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
	 * des joueurs, dalles d'inscription de la salle d'accueil. Les parties s'y relient à leur ouverture (Games.open).
	 */
	private static void setUpHub(net.minecraft.server.MinecraftServer server, fr.valcombe.mc.worlds.WorldManager manager,
			String mainGame) {
		var positions = new fr.valcombe.mc.hub.LastPositions(fr.valcombe.mc.hub.LastPositions.fileIn(
			server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize()));
		positions.load();
		var t = new fr.valcombe.mc.hub.Travel(server, manager, positions, () -> mainGame);
		manager.setFallback(t);
		manager.addListener(t);
		travel = t;
		try {
			var h = new fr.valcombe.mc.hub.Hub(server, manager, t);
			h.start();
			manager.addListener(h);
			hub = h;
		} catch (RuntimeException | LinkageError e) {
			LOG.error("Hub de Valcombe indisponible : les joueurs arrivent au village", e);
		}
	}

	/**
	 * La construction : qui construit où (BuildAccess, invitations de config/valcombe/ateliers.json), WorldEdit limité aux
	 * ateliers et complété (WorldEditGate : baguette, sélection montrée en 3D), « annuler » commun (SharedUndo). Sans les
	 * mondes de Valcombe, les règles valent quand même (WorldEdit n'est alors permis nulle part).
	 */
	private static void setUpBuilding(net.minecraft.server.MinecraftServer server) {
		try {
			var invitations = new fr.valcombe.mc.worlds.BuildInvitations(
				fr.valcombe.mc.worlds.BuildInvitations.fileIn(server.getServerDirectory()));
			invitations.load();
			var access = new fr.valcombe.mc.worlds.BuildAccess(ValcombeMod::worlds, invitations);
			fr.valcombe.mc.worlds.BuildAccess.install(access);
			if (worlds != null) {
				// Un atelier supprimé emporte ses invitations.
				worlds.addListener(new fr.valcombe.mc.worlds.WorldManager.Listener() {
					@Override
					public void deleted(fr.valcombe.mc.worlds.WorldEntry entry) {
						invitations.forgetWorld(entry.id());
					}
				});
			}
			WorldEditLink link = WorldEditGate.loadLink();
			link.start(server);
			// Les repères se défont dans la partie du monde de l'opérateur ; chacune garde les siens.
			SharedUndo undo = new SharedUndo(link, Games::hostAt, Games::hosts, editor);
			undo.setServer(server);
			WorldEditGate.install(new WorldEditGate(server, access, link, undo));
		} catch (RuntimeException | LinkageError e) {
			LOG.error("Construction (ateliers, WorldEdit, invitations) indisponible", e);
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
	 * Description du serveur (liste des serveurs, launcher) : la prochaine soirée, la partie principale et le nombre
	 * de parties d'essai ouvertes. Le launcher la lit pour afficher « Prochaine soirée » sur son accueil.
	 */
	private static void updateMotd(net.minecraft.server.MinecraftServer server) {
		if (community == null || baseMotd == null) {
			return;
		}
		String evening = community.nextEvening();
		String state;
		GameRoom main = Games.main();
		var session = main == null ? null : main.session();
		if (session == null) {
			state = "Aucune partie ouverte";
		} else if (session.game() != null) {
			state = (session.suspended() ? "Partie en suspens (" : "Partie en cours (") + session.game().size() + " joueurs)";
		} else {
			int n = session.lobby().size();
			state = n == 0 ? "Salon ouvert" : n + " inscrit(s) au salon";
			if (session.scenario() != null) {
				state += " · « " + session.scenario().nom + " »";
			}
		}
		int others = Math.max(0, Games.all().size() - 1);
		if (others > 0) {
			state += " · " + others + (others == 1 ? " partie d'essai" : " parties d'essai");
		}
		String motd = (evening.isEmpty() ? community.welcomeShown() : "Prochaine soirée : " + evening) + "\n" + state;
		if (!motd.equals(server.getMotd())) {
			server.setMotd(motd);
		}
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
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			community = new Community(server.getServerDirectory().resolve("config"));
			tablet = new Tablet(community);
			tablet.setServer(server);
			// Créations de l'Atelier : communes à toutes les parties, qui les reçoivent à leur ouverture (Games.open), donc
			// avant la reprise de leur partie (elle peut en contenir les rôles).
			creations = new CreationStore(server.getServerDirectory().resolve("config"), Games::sessions);
			maps = new MapStore(creations.dir(), Games::hosts);
			maps.setServer(server);
			creations.setMaps(maps);
			structures = new StructureStore(creations.dir());
			structures.setServer(server);
			creations.setStructures(structures);
			// L'éditeur pose ses blocs avec les chantiers de la partie du monde où il travaille (la principale ailleurs).
			editor = new MapEditor(Games::editorHost, structures);
			editor.setServer(server);
			creations.load();
			baseMotd = server.getMotd();
			workshop = new Workshop(server, creations, maps, structures, editor);
			Atelier atelier = new Atelier(creations, maps, structures, editor, workshop);
			atelier.setServer(server);
			tablet.setAtelier(atelier);
			setUpAdmin(server);
			// Sauvegardes : réglages, archives interrompues effacées, sauvegardes automatiques (serveur dédié).
			try {
				BackupService b = new BackupService(server, modVersion(), Games::saveAll, ValcombeMod::gameInProgress);
				b.start();
				backups = b;
			} catch (RuntimeException e) {
				LOG.error("Sauvegardes indisponibles", e);
			}
			// Mondes de Valcombe (Fantasy) : rouverts avant l'arrivée des joueurs, qui peuvent s'y être déconnectés.
			try {
				var manager = new fr.valcombe.mc.worlds.WorldManager(server, fr.valcombe.mc.worlds.WorldManager.defaultFile(server));
				manager.start();
				worlds = manager;
				// La partie principale se joue dans son monde : « Partie principale », créé (plat) au premier démarrage. Ce
				// monde-là ne se supprime pas ; les parties d'essai ont chacune le leur.
				String mainGame = manager.ensureMainGame().entry().id();
				placer = new MapPlacer(maps, manager, server);
				// « Envoyer à la partie » : la seule façon de changer la carte d'un monde de partie (monde remis à neuf,
				// carte posée dessus). L'hôte d'un monde est celui de sa partie ; sans monde nommé, la partie principale.
				var sender = new MapSender(server, manager, placer, maps, id -> {
					GameRoom r = Games.byWorld(id);
					return r == null ? null : r.host();
				}, () -> mainGame);
				mapSender = sender;
				setWorldsView(manager::adminView); // la liste des mondes de l'API d'administration (GET /admin/etat)
				setUpHub(server, manager, mainGame);
				structureWorkshop = new StructureWorkshop(server, manager, structures);
				manager.addListener(structureWorkshop);
				// Les parties : une par monde de partie, la principale d'abord ; chacune reprend là où l'arrêt l'a laissée.
				Games games = new Games(server, manager);
				Games.install(games);
				games.start(mainGame);
				// L'atelier carte : un monde par carte en cours de travail, enregistrée dans la bibliothèque (voir MapWorkshop).
				// Il n'appartient à aucune partie : il envoie ses cartes au monde de partie choisi, la principale proposée d'abord.
				mapWorkshop = new MapWorkshop(server, manager, placer, maps, sender, () -> mainGame, editor);
				manager.addListener(mapWorkshop);
				manager.addGuard(mapWorkshop); // un atelier supprimé autrement que par « Fermer » enregistre d'abord sa carte
			} catch (RuntimeException | LinkageError e) {
				LOG.error("Mondes de Valcombe indisponibles : aucune partie ne pourra se jouer (elles se jouent dans les mondes de partie)", e);
			}
			setUpBuilding(server);
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
			if (admin != null) {
				admin.setServer(null); // les requêtes qui arrivent pendant l'arrêt reçoivent « pas prêt »
			}
			workshop = null;
			if (mapWorkshop != null) {
				// Les ateliers carte modifiés sont enregistrés dans la bibliothèque tant que leurs mondes sont ouverts.
				try {
					mapWorkshop.stop();
				} catch (RuntimeException e) {
					LOG.error("Ateliers carte non enregistrés à l'arrêt", e);
				}
				mapWorkshop = null;
			}
			if (placer != null) {
				placer.stop(); // les poses en cours sont abandonnées avant que les mondes ne se ferment
				placer = null;
			}
			if (mapSender != null) {
				mapSender.stop(); // un envoi coupé laisse sa marque dans le dossier du monde : le démarrage le signalera
				mapSender = null;
			}
			// Toutes les parties sont enregistrées (elles reprendront au prochain démarrage), puis détachées du serveur.
			Games games = Games.current();
			if (games != null) {
				try {
					games.stop();
				} catch (RuntimeException e) {
					LOG.error("Parties mal enregistrées à l'arrêt", e);
				}
				Games.install(null);
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
			hub = null;
			travel = null;
			WorldEditGate.install(null);
			fr.valcombe.mc.worlds.BuildAccess.install(null);
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
			Games games = Games.current();
			if (games != null) {
				guarded("les parties", games::tick); // chaque partie a son tick (protégé lui aussi)
			}
			var ed = editor;
			if (ed != null) {
				guarded("l'éditeur de carte", ed::tick); // commun à toutes les parties : un seul tick
			}
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
			var mw = mapWorkshop;
			if (mw != null) {
				guarded("l'atelier carte", mw::tick);
			}
			var t = travel;
			if (t != null) {
				guarded("les voyages", t::tick);
			}
			var h = hub;
			if (h != null) {
				guarded("le hub", h::tick);
			}
			var gate = WorldEditGate.current();
			if (gate != null) {
				guarded("les ateliers", gate::tick);
			}
			if (server.getTickCount() % 5 == 0) {
				guarded("le vocal", () -> VoiceRouter.update(server, Games.all()));
			}
			if (server.getTickCount() % 40 == 0) {
				guarded("l'accueil", () -> {
					updateMotd(server);
					for (GameRoom r : Games.all()) {
						r.host().updateHall(r.session());
					}
				});
			}
			if (server.getTickCount() % 1200 == 600) {
				guarded("la sauvegarde", Games::saveAll);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer p = handler.getPlayer();
			ValcombeUi.giveMenuItem(p);
			// Sa partie l'accueille : celle qui le retient à sa place (il y joue, il la conte), sinon le hub.
			Games games = Games.current();
			if (games != null) {
				games.onJoin(p);
			}
			var gate = WorldEditGate.current();
			if (gate != null) {
				guarded("les ateliers", () -> gate.onJoin(p));
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			String name = handler.getPlayer().getScoreboardName();
			var t = travel;
			if (t != null) {
				guarded("les voyages", () -> t.onDisconnect(handler.getPlayer()));
			}
			SYNC.forget(name);
			var gate = WorldEditGate.current();
			if (gate != null) {
				gate.onDisconnect(handler.getPlayer());
			}
			if (workshop != null) {
				workshop.forget(name);
			}
			Games games = Games.current();
			if (games != null) {
				games.onDisconnect(name);
			}
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
		// WorldEdit seulement dans les ateliers, pour les opérateurs et leurs invités (après ToolPriority, dont le refus passe d'abord).
		WorldEditGate.installPermissions();
		net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> {
			WorldEditGate g = WorldEditGate.current();
			if (g != null) {
				guarded("les ateliers", () -> g.onLevelChange(player, from, to));
			}
			// Qui regardait la partie du monde qu'il quitte n'est plus dans ses gradins (son mode de jeu lui est rendu).
			Games games = Games.current();
			if (games != null) {
				guarded("les spectateurs du hub", () -> games.onLevelChange(player, from));
			}
		});
		// Tableau des parties du hub : un clic gauche sur une fiche l'ouvre aussi (sans rien frapper).
		net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (fr.valcombe.mc.hub.Hub.boardCard(entity) == null || !(player instanceof ServerPlayer sp)) {
				return InteractionResult.PASS;
			}
			openBoardCard(sp, entity);
			return InteractionResult.SUCCESS;
		});
		// Clic droit avec le grimoire « Menu de Valcombe » : ouvre le menu (le mod client ouvre le sien).
		UseItemCallback.EVENT.register(ToolPriority.PHASE, (player, level, hand) -> {
			// Plume d'accusation : pendant un vote, lever ou baisser la main ; sinon accuser le joueur visé, même de loin.
			if (ValcombeUi.isAccuseItem(player.getItemInHand(hand))) {
				if (player instanceof ServerPlayer sp && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
					String name = sp.getScoreboardName();
					String cmd = null;
					GameRoom room = roomOf(sp);
					if (room != null && room.session().voteOpen()) {
						cmd = room.session().handRaised(name) ? "vc vote non" : "vc vote oui";
					} else {
						String target = room == null ? null : room.host().aimedPlayer(sp);
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
					GameRoom room = roomOf(sp);
					useStaff(sp, room == null ? null : room.host().aimedPlayer(sp));
				}
				return InteractionResult.SUCCESS;
			}
			if (ValcombeUi.isMenuItem(player.getItemInHand(hand))) {
				if (player instanceof ServerPlayer sp) {
					GameRoom room = roomOf(sp);
					if (room != null) {
						room.ui().openMain(sp);
					}
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
			// Tableau des parties du hub : un clic sur la fiche d'une partie l'ouvre.
			if (fr.valcombe.mc.hub.Hub.boardCard(entity) != null) {
				if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
					openBoardCard(sp, entity);
				}
				return InteractionResult.SUCCESS;
			}
			if (!entity.entityTags().contains("vc_lutrin")) {
				return InteractionResult.PASS;
			}
			if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
				// Le lutrin d'une place est celui de la partie de ce monde.
				GameRoom room = Games.of(entity.level());
				if (room != null && room.isLeader(sp)) {
					room.ui().openStoryteller(sp);
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
			// Baguette de construction hors des ateliers : WorldEdit n'y répond pas, le joueur l'apprend et rien ne se passe.
			if (ValcombeUi.isWand(player.getItemInHand(hand)) && player instanceof ServerPlayer sp && WorldEditGate.wandRefused(sp)) {
				return InteractionResult.SUCCESS;
			}
			ValcombeUi.MapTool tool = ValcombeUi.mapTool(player.getItemInHand(hand));
			if (tool == null) {
				// Un bloc posé à la main sur la carte d'une partie : elle ne sera plus remplacée sans sauvegarde.
				McHost here = Games.hostAt(level);
				if (player instanceof ServerPlayer builder
					&& player.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BlockItem) {
					if (here != null) {
						here.noteEdit(builder, hit.getBlockPos());
					}
					var mw = mapWorkshop;
					if (mw != null) {
						mw.noteEdit(builder.level()); // dans un atelier carte : la carte a changé depuis son enregistrement
					}
				}
				// Trône : clic droit pour s'asseoir (les trônes du village, dans le monde de sa partie)
				if (here != null && player instanceof ServerPlayer sp && !player.isShiftKeyDown()
					&& !ValcombeUi.isAccuseItem(player.getItemInHand(hand))
					&& here.village() != null && here.inGameWorld(sp) && here.village().seatIndexAt(hit.getBlockPos()) >= 0) {
					if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
						here.sitAt(sp, hit.getBlockPos());
					}
					return InteractionResult.SUCCESS;
				}
				return InteractionResult.PASS;
			}
			// Dans un atelier carte, les outils posent les repères de sa carte, pour ses opérateurs et ses invités.
			var mw = mapWorkshop;
			if (mw != null && player instanceof ServerPlayer ap && mw.isAtelier(ap.level())) {
				if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) {
					return InteractionResult.SUCCESS;
				}
				var clicked = hit.getBlockPos();
				if (tool == ValcombeUi.MapTool.STRUCTURE) {
					if (editor != null) {
						editorClick(ap, () -> ap.isShiftKeyDown() ? editor.rotate(ap) : editor.place(ap, clicked));
					}
					return InteractionResult.SUCCESS;
				}
				try {
					String msg = mw.useTool(ap, tool, clicked, ap.isShiftKeyDown());
					if (!msg.isEmpty()) {
						ap.sendSystemMessage(Gui.plain(msg, net.minecraft.ChatFormatting.GREEN));
						var at = clicked.above();
						WorldSetup.run(ap.level(), String.format(java.util.Locale.ROOT,
							"particle minecraft:happy_villager %d %d %d 0.3 0.3 0.3 0 12", at.getX(), at.getY(), at.getZ()));
					}
				} catch (fr.valcombe.rules.GameException e) {
					ap.sendSystemMessage(Gui.plain(e.getMessage(), net.minecraft.ChatFormatting.RED));
				}
				return InteractionResult.SUCCESS;
			}
			if (player instanceof ServerPlayer sp
				&& sp.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
				if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) {
					return InteractionResult.SUCCESS;
				}
				// Outil de structure : poser la structure en main, ou la tourner (accroupi).
				if (tool == ValcombeUi.MapTool.STRUCTURE) {
					var clicked = hit.getBlockPos();
					if (editor != null) {
						editorClick(sp, () -> sp.isShiftKeyDown() ? editor.rotate(sp) : editor.place(sp, clicked));
					}
					return InteractionResult.SUCCESS;
				}
				var at = hit.getBlockPos().above();
				try {
					// Les repères sont ceux du village de la partie de ce monde (ailleurs, l'hôte visé dit d'y aller).
					GameRoom room = roomOf(sp);
					if (room == null) {
						throw new fr.valcombe.rules.GameException("Aucune partie sur ce serveur.");
					}
					String msg = room.host().useTool(sp, tool, hit.getBlockPos(), sp.isShiftKeyDown());
					if (msg.isEmpty()) {
						return InteractionResult.SUCCESS; // le menu de l'objet visé s'est ouvert
					}
					sp.sendSystemMessage(Gui.plain(msg, net.minecraft.ChatFormatting.GREEN));
					WorldSetup.run(sp.level(), String.format(java.util.Locale.ROOT,
						"particle minecraft:happy_villager %d %d %d 0.3 0.3 0.3 0 12", at.getX(), at.getY(), at.getZ()));
				} catch (fr.valcombe.rules.GameException e) {
					sp.sendSystemMessage(Gui.plain(e.getMessage(), net.minecraft.ChatFormatting.RED));
				}
			}
			return InteractionResult.SUCCESS;
		});
		// Clic gauche avec l'outil de structure : miroir de la structure en main. Le bloc visé n'est pas cassé (des deux
		// côtés : le client n'a pas à le voir disparaître puis revenir). La baguette de construction, elle, est à WorldEdit
		// (premier coin de la sélection) là où il est permis ; ailleurs elle ne casse rien non plus.
		net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register(ToolPriority.PHASE, (player, level, hand, pos, direction) -> {
			if (ValcombeUi.isWand(player.getItemInHand(hand)) && player instanceof ServerPlayer sp && WorldEditGate.wandRefused(sp)) {
				return InteractionResult.SUCCESS;
			}
			ValcombeUi.MapTool tool = ValcombeUi.mapTool(player.getItemInHand(hand));
			if (tool == ValcombeUi.MapTool.HAND) {
				return InteractionResult.SUCCESS; // la main ne casse rien : elle ne fait que prendre
			}
			if (tool != ValcombeUi.MapTool.STRUCTURE) {
				return InteractionResult.PASS;
			}
			var mw = mapWorkshop;
			if (player instanceof ServerPlayer sp && editor != null && hand == net.minecraft.world.InteractionHand.MAIN_HAND
				&& (sp.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)
					|| mw != null && mw.buildsHere(sp))) {
				editorClick(sp, () -> editor.mirror(sp));
			}
			return InteractionResult.SUCCESS;
		});
		// Dans les mondes de Valcombe, ne casse que qui construit là (BuildAccess) : l'opérateur en mode créatif au hub et dans
		// les parties, comme avant ; les opérateurs et leurs invités dans les ateliers.
		// Poser suit la même règle (PlaceGuardMixin, BucketGuardMixin) : BuildAccess.mayModify.
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
			!(player instanceof ServerPlayer sp) || fr.valcombe.mc.worlds.BuildAccess.mayModify(sp, level));
		// Un bloc cassé à la main sur la carte d'une partie, hors partie, ou dans un atelier carte : même suivi que pour un
		// bloc posé.
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (player instanceof ServerPlayer sp) {
				McHost here = Games.hostAt(level);
				if (here != null) {
					here.noteEdit(sp, pos);
				}
				var mw = mapWorkshop;
				if (mw != null) {
					mw.noteEdit(level);
				}
			}
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
			ValcombeCommands.register(dispatcher, registry));
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
