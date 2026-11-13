package fr.valcombe.mc.games;

import fr.valcombe.mc.GameSave;
import fr.valcombe.mc.Gui;
import fr.valcombe.mc.McHost;
import fr.valcombe.mc.ScriptStore;
import fr.valcombe.mc.ValcombeMod;
import fr.valcombe.mc.ValcombeUi;
import fr.valcombe.mc.Village;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.Game;
import fr.valcombe.rules.GameException;
import fr.valcombe.session.Session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Les parties du serveur : une par monde de partie (la « Partie principale » et autant de parties d'essai qu'on veut),
 * chacune avec son hôte, sa session et ses menus ({@link GameRoom}). Elles se jouent en même temps, séparées : on ne
 * voit, n'entend et ne commande jamais une autre partie que celle du monde où l'on se trouve.
 *
 * <h2>Trouver la partie d'un joueur, d'un monde, d'une commande</h2>
 * <ul>
 * <li>{@link #of(Level)}, {@link #of(ServerPlayer)} : la partie dont c'est le monde, ou <b>null</b> au hub, dans un
 * atelier ou dans le monde principal de Minecraft (pas de partie là).</li>
 * <li>{@link #byWorld(String)} : la partie d'un monde, par son identifiant.</li>
 * <li>{@link #home(String)} : la table d'attache d'un joueur (inscrit, assis, Conteur, spectateur), où qu'il soit.</li>
 * <li>{@link #forPlayer(ServerPlayer)}, {@link #forSource(CommandSourceStack)} : la partie que vise un joueur, une
 * commande, un écran. C'est toujours celle du monde où il se trouve ; hors d'un monde de partie (hub, atelier,
 * console), le <b>repli</b> est explicite : sa table d'attache, sinon la partie principale.</li>
 * </ul>
 *
 * <h2>Cycle de vie (points d'accroche)</h2>
 * <ul>
 * <li><b>Démarrage</b> : {@link #start} ouvre une partie pour chaque monde de partie du registre, la principale
 * d'abord, et reprend la partie enregistrée de chacune (sauvegarde par monde, voir {@code McHost.saveFile}).</li>
 * <li><b>Création</b> : un monde de partie créé ({@code /vc partie creer}, {@code /vc monde creer essai}, API) ouvre
 * aussitôt sa partie ({@link #created}), qui reçoit le village généré par « Envoyer à la partie ».</li>
 * <li><b>Ouverture</b> : {@link #open} relie l'hôte aux services communs (bibliothèque, éditeur, envoi de cartes,
 * voyages, statistiques), lui donne sa session et ses menus, puis prépare son monde.</li>
 * <li><b>Fermeture</b> : un monde de partie supprimé ferme sa partie ({@link #deleted}) et efface sa sauvegarde ;
 * l'arrêt du serveur enregistre toutes les parties ({@link #stop}).</li>
 * <li><b>Tick</b> : chaque partie a le sien ({@link #tick}).</li>
 * </ul>
 *
 * <h2>Ce qui est déjà séparé, et ce qui s'y accroche</h2>
 * <ul>
 * <li>Commandes : {@code ValcombeCommands} résout la partie de chaque commande ({@link #require}) ; {@code /vc partie}
 * liste, crée, supprime et rejoint les parties.</li>
 * <li>Vues : {@code ViewSync} envoie à chacun la vue de sa partie, avec le champ {@code partie} (id, nom, sorte, ici) ;
 * hors de toute partie, une table vide. Annonces, titres, sons et barres : {@code McHost.here()}, les joueurs du monde.</li>
 * <li>Un joueur, une table : {@link GameSeats} (branché sur {@code Session.admission}).</li>
 * <li>Sauvegarde : une fiche par partie ({@code config/valcombe/parties/<monde>.json}, voir {@code GameSave.file} et
 * {@code McHost.saveFile}), reprise à l'ouverture ({@link #open}) : chaque partie enregistrée reprend dans son monde au
 * démarrage, en cours, en pause ou en suspens. L'ancienne sauvegarde unique ({@code config/valcombe-partie.json}) est
 * mise de côté au démarrage ({@link #start}).</li>
 * <li>Mise en suspens : {@link GameRoom#suspend}, {@link GameRoom#resume} ({@code /vc partie suspendre|reprendre},
 * pupitre du Conteur, {@code POST /admin/parties/suspendre|reprendre}). L'horloge de la partie est gelée, ses joueurs vont
 * au hub ; à la reprise, ils sont rappelés et retrouvent leur place. Un joueur d'une partie en suspens qui se connecte
 * arrive au hub ({@link #onJoin}) ; on n'entre pas dans son monde sans la mener ({@code Travel.go}).</li>
 * <li>Vocal : {@code VoiceRouter.update} route chaque partie dans son monde et range chaque joueur dans la zone de sa
 * partie ({@link VoiceZones}) : le plugin arrête toute voix d'une zone à l'autre, groupes du vocal compris.</li>
 * <li>Équipes et noms au-dessus des têtes : chaque partie a les siennes, nommées d'après son préfixe court
 * ({@link GameTeams}, donné à l'ouverture) ; retirées à la fin et au suspens, quittées par qui sort du monde ; toutes celles
 * laissées par un arrêt sont retirées au démarrage.</li>
 * <li>Hub, API et launcher : {@link #all()}, {@link GameRoom#state()}, {@code GET /admin/etat} (champ {@code parties}),
 * portes du hub (état de chaque partie) ; les dalles du hub inscrivent à la partie principale.</li>
 * <li>Tableau des parties : la fiche de chaque partie ({@link GameCard}, {@link GameRoom#card()}), au hub (deux panneaux,
 * {@code Hub}), dans l'écran « Parties » du menu J ({@link #board}, champ {@code parties} de la vue), à l'API
 * ({@code GET/POST /admin/parties}) et au launcher. On y rejoint une partie ({@link #join}) ou on la regarde
 * ({@link #watch}) : spectateur sans les rôles, dans les gradins du vocal ({@link VoiceZones#gallery}), sorti des gradins
 * en quittant son monde ({@link #onLevelChange}, {@link #stopWatching}).</li>
 * </ul>
 * Fil du serveur, sauf les lectures de {@link #current()}.
 */
public final class Games implements WorldManager.Listener {
	static final Logger LOG = LoggerFactory.getLogger("valcombe");
	private static volatile Games current;

	private final MinecraftServer server;
	private final WorldManager worlds;
	/** Les parties ouvertes, par identifiant de monde, la principale d'abord. */
	private final Map<String, GameRoom> rooms = new LinkedHashMap<>();
	private String mainId;
	/** Une copie de la liste des parties, refaite à chaque ouverture ou fermeture (lue à chaque tick, et d'autres fils). */
	private volatile List<GameRoom> snapshot = List.of();

	public Games(MinecraftServer server, WorldManager worlds) {
		this.server = server;
		this.worlds = worlds;
	}

	/** Les parties du serveur en service, ou null avant le démarrage (ou sans les mondes de Valcombe). */
	public static Games current() {
		return current;
	}

	/** Met en service (ou retire, avec null) les parties du serveur. */
	public static void install(Games games) {
		current = games;
	}

	public WorldManager worlds() {
		return worlds;
	}

	public MinecraftServer server() {
		return server;
	}

	// ------------------------------------------------------------------
	// Recherche (accès statiques : sans parties, rien n'est trouvé)
	// ------------------------------------------------------------------

	/** Toutes les parties ouvertes, la principale d'abord. */
	public static List<GameRoom> all() {
		Games g = current;
		return g == null ? List.of() : g.snapshot;
	}

	/** Les sessions de toutes les parties (créations communes, voir CreationStore). */
	public static List<Session> sessions() {
		return all().stream().map(GameRoom::session).toList();
	}

	/** Les hôtes de toutes les parties. */
	public static List<McHost> hosts() {
		return all().stream().map(GameRoom::host).toList();
	}

	/** La partie principale du serveur, ou null sans parties. */
	public static GameRoom main() {
		Games g = current;
		if (g == null) {
			return null;
		}
		// Par la copie de la liste, jamais par la table elle-même : lue aussi hors du fil du serveur.
		List<GameRoom> list = g.snapshot;
		String id = g.mainId;
		for (GameRoom r : list) {
			if (r.id().equals(id)) {
				return r;
			}
		}
		return list.isEmpty() ? null : list.get(0);
	}

	/** La partie qui se joue dans ce monde de Valcombe, ou null. */
	public static GameRoom byWorld(String worldId) {
		Games g = current;
		if (g == null || worldId == null) {
			return null;
		}
		for (GameRoom r : g.snapshot) {
			if (r.id().equals(worldId)) {
				return r;
			}
		}
		return null;
	}

	/** La partie dont ce monde est le monde de partie ; null au hub, dans un atelier, dans le monde principal. */
	public static GameRoom of(Level level) {
		if (level == null) {
			return null;
		}
		for (GameRoom r : all()) {
			if (r.host().isGameLevel(level)) {
				return r;
			}
		}
		return null;
	}

	/** La partie du monde où se trouve ce joueur, ou null (hub, atelier). */
	public static GameRoom of(ServerPlayer p) {
		return p == null ? null : of(p.level());
	}

	/** La table d'attache de ce joueur, où qu'il soit (voir {@link GameSeats#home}), ou null. */
	public static GameRoom home(String player) {
		List<GameRoom> list = all();
		GameSeats.Table t = GameSeats.home(list.stream().map(GameRoom::table).toList(), player);
		return t == null ? null : byWorld(t.id());
	}

	/**
	 * La partie que vise ce joueur (menus, écrans, tablette) : celle du monde où il est ; ailleurs, sa table d'attache,
	 * sinon la partie principale. Null seulement s'il n'y a aucune partie sur le serveur.
	 */
	public static GameRoom forPlayer(ServerPlayer p) {
		GameRoom r = of(p);
		if (r == null && p != null) {
			r = home(p.getScoreboardName());
		}
		return r != null ? r : main();
	}

	/**
	 * La partie que vise une commande : celle du monde d'où elle part (le monde du joueur, ou celui d'un {@code execute
	 * in}) ; hors d'un monde de partie, la table d'attache du joueur, sinon la partie principale. Null seulement sans
	 * aucune partie.
	 */
	public static GameRoom forSource(CommandSourceStack src) {
		GameRoom r = of(src.getLevel());
		if (r == null && src.getPlayer() != null) {
			r = home(src.getPlayer().getScoreboardName());
		}
		return r != null ? r : main();
	}

	/** Comme {@link #forSource}, mais une GameException (message pour le joueur) s'il n'y a aucune partie. */
	public static GameRoom require(CommandSourceStack src) {
		GameRoom r = forSource(src);
		if (r == null) {
			throw new GameException("Aucune partie sur ce serveur : les mondes de Valcombe ne sont pas disponibles (voir son journal).");
		}
		return r;
	}

	/** L'hôte de la partie de ce monde, ou null (pour les repères : ils n'existent que dans un monde de partie). */
	public static McHost hostAt(Level level) {
		GameRoom r = of(level);
		return r == null ? null : r.host();
	}

	/**
	 * L'hôte qui pose les blocs de l'éditeur de carte dans ce monde : celui de sa partie, ou celui de la partie principale
	 * pour un monde sans partie (atelier, hub). Null sans parties.
	 */
	public static McHost editorHost(Level level) {
		GameRoom r = of(level);
		if (r == null) {
			r = main();
		}
		return r == null ? null : r.host();
	}

	/**
	 * Une partie au moins se joue-t-elle en ce moment (joueurs assis, pas en suspens) ? Les sauvegardes automatiques
	 * l'attendent. Une partie en suspens est enregistrée et gelée : elle ne les retient pas.
	 */
	public static boolean anyInProgress() {
		return all().stream().anyMatch(GameRoom::live);
	}

	/** Enregistre toutes les parties en cours (arrêt du serveur, sauvegarde du serveur, sauvegarde périodique). */
	public static void saveAll() {
		for (GameRoom r : all()) {
			try {
				r.session().saveNow();
			} catch (RuntimeException e) {
				LOG.error("Sauvegarde de la partie « " + r.name() + " » impossible", e);
			}
		}
	}

	/** Un chantier ou une remise en état est-il en cours dans une partie (le serveur en veille doit les faire avancer) ? */
	public static boolean anyWork() {
		return all().stream().anyMatch(r -> r.host().hasWork());
	}

	// ------------------------------------------------------------------
	// Ouverture et fermeture
	// ------------------------------------------------------------------

	/** Un monde de partie (vraie partie ou partie d'essai) porte une partie. */
	public static boolean hosts(WorldKind kind) {
		return kind == WorldKind.PARTIE || kind == WorldKind.ESSAI;
	}

	/**
	 * Au démarrage : une partie pour chaque monde de partie du registre, la principale d'abord. Chacune prépare son
	 * monde et reprend la partie qu'un arrêt du serveur y a laissée.
	 */
	public void start(String mainWorldId) {
		this.mainId = mainWorldId;
		// Le tableau des scores est enregistré avec le monde : les équipes des parties d'avant l'arrêt y sont encore. Elles
		// sont retirées, et chaque partie reprise repose les siennes.
		try {
			int purged = McHost.purgeTeams(server);
			if (purged > 0) {
				LOG.info("Équipes de parties laissées par l'arrêt retirées : {}.", purged);
			}
		} catch (RuntimeException ex) {
			LOG.warn("Équipes des parties non nettoyées au démarrage", ex);
		}
		java.nio.file.Path config = server.getServerDirectory().resolve("config");
		try {
			String said = GameSave.retireLegacy(config);
			if (said != null) {
				LOG.warn(said);
			}
		} catch (java.io.IOException | RuntimeException ex) {
			LOG.warn("Ancienne sauvegarde de partie (config/" + GameSave.LEGACY + ") ignorée, mais pas mise de côté", ex);
		}
		WorldEntry main = worlds.find(mainWorldId);
		if (main != null) {
			openSafely(main, true); // la partie principale a toujours où se jouer : son village est bâti s'il manque
		}
		for (WorldEntry e : worlds.list()) {
			if (hosts(e.sorte()) && !rooms.containsKey(e.id())) {
				openSafely(e, false); // une partie d'essai garde son monde tel qu'on l'a laissé (vide, s'il l'était)
			}
		}
		worlds.addListener(this);
		for (String id : GameSave.savedWorlds(config)) {
			if (!rooms.containsKey(id)) {
				LOG.warn("Fiche de partie sans monde de partie (« {} », dans config/{}) : ignorée, laissée sur le disque.", id,
					GameSave.DIR);
			}
		}
		LOG.info("Parties de Valcombe : {}.", String.join(", ", snapshot.stream()
			.map(r -> r.id() + (r.suspended() ? " (en suspens)" : r.inProgress() ? " (en cours)" : "")).toList()));
	}

	/** Au démarrage : une partie qui ne s'ouvre pas est journalisée, et n'empêche pas les autres de s'ouvrir. */
	private void openSafely(WorldEntry e, boolean build) {
		try {
			open(e, build);
		} catch (RuntimeException | LinkageError ex) {
			LOG.error("Partie « " + e.id() + " » non ouverte au démarrage", ex);
		}
	}

	/**
	 * Ouvre la partie de ce monde : hôte relié aux services communs, session, menus, monde préparé, partie enregistrée
	 * reprise. build : bâtir le village de Valcombe si le monde n'a aucune carte (faux quand la carte arrive juste après).
	 */
	GameRoom open(WorldEntry e, boolean build) {
		GameRoom already = rooms.get(e.id());
		if (already != null) {
			return already;
		}
		McHost host = new McHost();
		Session session = new Session(host);
		ValcombeUi ui = new ValcombeUi(session, host);
		GameRoom room = new GameRoom(e.id(), host, session, ui);
		boolean main = e.id().equals(mainId);
		host.setMainGame(main);
		// Ses équipes du tableau des scores portent un préfixe court qui n'est qu'à elle (voir GameTeams).
		host.setTeamKey(GameTeams.key(e.id(), rooms.values().stream().map(r -> r.host().teamKey()).toList()));
		host.setSession(session);
		host.setUi(ui);
		host.setServer(server);
		host.setCommunity(ValcombeMod.community());
		host.setMaps(ValcombeMod.maps());
		host.setEditor(ValcombeMod.editor());
		host.setSender(ValcombeMod.sender());
		host.setPadHandler((p, pad) -> pad(room, p, pad));
		var travel = ValcombeMod.travel();
		if (travel != null) {
			// Les joueurs que la partie amène à sa table (inscription, Conteur, lancement) voyagent comme les autres : leur
			// position dans le monde qu'ils quittent est gardée.
			host.setLeaving(travel::remember);
			host.setBring(p -> travel.toGame(p, e.id()));
			host.setHubArrival(travel::toHubArrival);
			host.setToHub(travel::toHub); // mise en suspens : chacun au hub, sa place ici gardée
		}
		var hub = ValcombeMod.hub();
		if (hub != null && main) {
			// Les dalles d'inscription du hub inscrivent à la partie principale (le tableau des parties du hub, lui, laisse
			// choisir sa partie).
			host.setHubPads(hub::padAt);
		}
		var community = ValcombeMod.community();
		if (community != null) {
			session.carnetSource = community::carnet;
		}
		session.modVersion = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("valcombe")
			.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
		session.admission = player -> admit(room, player);
		var creations = ValcombeMod.creations();
		if (creations != null) {
			creations.applyTo(session);
		}
		session.setSavedScripts(ScriptStore.list(server));
		host.bindWorld(worlds, e.id());
		worlds.addGuard(host);
		worlds.addListener(host);
		rooms.put(e.id(), room);
		refresh();
		String saved = host.savedGame();
		// Le monde d'une partie d'essai sans partie à reprendre n'est ouvert qu'à la première visite (voyage, envoi de
		// carte) : un serveur qui garde dix parties d'essai ne les charge pas toutes au démarrage. Ses règles de monde
		// ont été posées à sa création.
		if (main || build || saved != null || worlds.isOpen(e.id())) {
			try {
				host.prepareWorld(build);
			} catch (RuntimeException ex) {
				LOG.error("Monde de la partie « " + e.id() + " » non préparé", ex);
			}
		}
		if (saved != null) {
			try {
				session.restore(saved);
			} catch (RuntimeException ex) {
				LOG.error("Sauvegarde de la partie « " + e.id() + " » illisible : ignorée", ex);
			}
		}
		host.resumeRestore(); // carte laissée en l'état par un arrêt en pleine partie ou en pleine remise en état
		LOG.info("Partie ouverte : « {} » ({}).", host.worldName(), e.sorte().label());
		return room;
	}

	/** Ferme la partie de ce monde (son monde est supprimé) : elle ne reprendra pas, sa sauvegarde est effacée. */
	void close(String worldId) {
		GameRoom room = rooms.remove(worldId);
		if (room == null) {
			return;
		}
		refresh();
		worlds.removeGuard(room.host());
		worlds.removeListener(room.host());
		try {
			room.host().clearTeams();
		} catch (RuntimeException ex) {
			LOG.warn("Équipes de la partie « {} » non retirées : {}", worldId, ex.getMessage());
		}
		try {
			// Par l'identifiant du monde, pas par l'hôte : il a peut-être déjà oublié son monde supprimé.
			fr.valcombe.mc.SafeFile.delete(GameSave.file(server.getServerDirectory().resolve("config"), worldId));
		} catch (java.io.IOException | RuntimeException ex) {
			LOG.warn("Fiche de la partie « {} » non effacée : {}", worldId, ex.getMessage());
		}
		room.host().setServer(null);
		LOG.info("Partie fermée : « {} ».", worldId);
	}

	private void refresh() {
		snapshot = List.copyOf(new ArrayList<>(rooms.values()));
	}

	/** Arrêt du serveur : toutes les parties sont enregistrées (elles reprendront), puis détachées du serveur. */
	public void stop() {
		saveAll();
		for (GameRoom r : snapshot) {
			try {
				r.host().setSender(null);
				r.host().setHubPads(null);
				r.host().setHubArrival(null);
				r.host().setLeaving(null);
				r.host().setBring(null);
				r.host().setToHub(null);
				r.host().setServer(null);
			} catch (RuntimeException e) {
				LOG.error("Partie « " + r.id() + " » mal fermée à l'arrêt", e);
			}
		}
		worlds.removeListener(this);
	}

	/** À chaque tick du serveur : le tick de chaque partie (chantiers, tâches de la session, minuteurs, dalles). */
	public void tick() {
		for (GameRoom r : snapshot) {
			guarded("la partie « " + r.id() + " »", r.host()::onTick);
		}
		if (++ticks % 20 == 0) {
			guarded("les spectateurs du hub", this::pruneWatchers);
		}
	}

	private int ticks;

	/**
	 * Chaque seconde : qui regardait une partie et n'est plus dans son monde (parti par le menu « Mondes », par une
	 * commande, déconnecté) cesse de la regarder. Le changement de monde le fait déjà sur le moment ({@link #onLevelChange}) ;
	 * ceci rattrape le reste.
	 */
	private void pruneWatchers() {
		for (GameRoom r : snapshot) {
			for (String name : r.session().watchers()) {
				ServerPlayer p = server.getPlayerList().getPlayerByName(name);
				if (p == null || !r.here(p)) {
					r.session().stopWatching(name, p == null);
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Tableau des parties : rejoindre, regarder
	// ------------------------------------------------------------------

	private static fr.valcombe.mc.hub.Travel travel() {
		var t = ValcombeMod.travel();
		if (t == null) {
			throw new GameException("Les voyages entre les mondes ne sont pas disponibles sur ce serveur.");
		}
		return t;
	}

	/** Pourquoi une partie en cours ailleurs retient ce joueur loin de cette table, ou null. */
	private static String heldElsewhere(GameRoom target, String player) {
		return GameSeats.heldElsewhere(all().stream().map(GameRoom::table).toList(), target.table(), player);
	}

	/**
	 * « Rejoindre » (tableau des parties, écran « Parties », {@code /vc partie rejoindre}) : s'inscrire au salon d'une
	 * partie ouverte, et y aller ; ou retourner à sa place dans une partie qu'on joue, qu'on conte ou où l'on est inscrit.
	 * Renvoie le message pour le joueur ; une GameException avec la raison s'il ne le peut pas.
	 */
	public static String join(ServerPlayer p, GameRoom room) {
		String name = p.getScoreboardName();
		Session s = room.session();
		String why = GameCard.joinRefusal(s, name, room.seats());
		if (why == null) {
			why = heldElsewhere(room, name);
		}
		if (why != null) {
			throw new GameException(why);
		}
		String seat = GameCard.seat(s, name);
		boolean back = "joueur".equals(seat) || "conteur".equals(seat) || "inscrit".equals(seat);
		String said;
		if (back) {
			said = "Tu retournes à « " + room.name() + " ».";
		} else {
			// Il la regardait : il quitte les gradins pour le salon.
			s.stopWatching(name, true);
			if (s.isSpectator(name)) {
				s.spectate(name);
			}
			s.join(name); // l'emmène si le monde est ouvert
			said = "Te voilà inscrit à « " + room.name() + " ».";
		}
		if (!room.here(p)) {
			said += " " + travel().toGame(p, room.id()); // ouvre le monde au besoin (partie d'essai jamais visitée)
		}
		return said;
	}

	/**
	 * « Regarder » : le joueur va dans le monde de la partie en spectateur (mode spectateur de Minecraft), sans voir les
	 * rôles ni entendre le vocal des joueurs ({@link Session#watch}). Il en sort par « Arrêter de regarder »
	 * ({@link #stopWatching}), ou en quittant le monde. Renvoie le message pour lui.
	 */
	public static String watch(ServerPlayer p, GameRoom room) {
		String name = p.getScoreboardName();
		Session s = room.session();
		if (s.isWatcher(name) && room.here(p)) {
			return "Tu regardes déjà « " + room.name() + " ».";
		}
		String why = s.watchRefusal(name);
		if (why == null) {
			why = heldElsewhere(room, name);
		}
		if (why != null) {
			throw new GameException(why);
		}
		if (!room.here(p)) {
			travel().toGame(p, room.id()); // d'abord le voyage : en mode spectateur au hub, il n'aurait rien à faire
		}
		try {
			return "« " + room.name() + " » : " + s.watch(name);
		} catch (GameException e) {
			travel().toHub(p); // la table a changé entre-temps : il n'a rien à faire là
			throw e;
		}
	}

	/** La partie que ce joueur regarde depuis le tableau des parties, ou null. */
	public static GameRoom watching(String player) {
		for (GameRoom r : all()) {
			if (r.session().isWatcher(player)) {
				return r;
			}
		}
		return null;
	}

	/** « Arrêter de regarder » : il quitte les gradins et revient au hub, à sa dernière place. */
	public static String stopWatching(ServerPlayer p) {
		String name = p.getScoreboardName();
		GameRoom r = watching(name);
		if (r == null) {
			throw new GameException("Tu ne regardes aucune partie. Pour revenir au hub : /vc hub.");
		}
		r.session().stopWatching(name, false);
		return "Tu ne regardes plus « " + r.name() + " ». " + travel().toHub(p);
	}

	/** Un joueur change de monde : s'il regardait la partie du monde qu'il quitte, il ne la regarde plus. */
	public void onLevelChange(ServerPlayer p, ServerLevel from) {
		GameRoom r = of(from);
		if (r != null && !r.here(p)) {
			r.session().stopWatching(p.getScoreboardName(), false);
		}
	}

	/**
	 * Le tableau des parties tel que ce joueur le voit (écran « Parties » du menu J) : chaque partie avec sa fiche, ce
	 * qu'il y est, s'il peut la rejoindre ou la regarder (sinon pourquoi), s'il la mène ; et s'il est opérateur (il crée,
	 * met en suspens, reprend et supprime les parties).
	 */
	public static Map<String, Object> board(ServerPlayer p) {
		String name = p.getScoreboardName();
		boolean op = p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
		List<Map<String, Object>> list = new ArrayList<>();
		for (GameRoom r : all()) {
			Session s = r.session();
			Map<String, Object> m = r.card().view();
			m.put("ici", r.here(p));
			m.put("place", GameCard.seat(s, name));
			String held = heldElsewhere(r, name);
			String join = GameCard.joinRefusal(s, name, r.seats());
			join = join != null ? join : held;
			m.put("rejoindre", join == null);
			m.put("rejoindreTexte", GameCard.joinLabel(s, name));
			m.put("rejoindreRaison", join);
			String watch;
			if (s.isWatcher(name)) {
				watch = r.here(p) ? "Tu la regardes déjà." : null;
			} else {
				watch = s.watchRefusal(name);
				watch = watch != null ? watch : held;
			}
			m.put("regarder", watch == null);
			m.put("regarderRaison", watch);
			m.put("mener", r.isLeader(p));
			list.add(m);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("liste", list);
		out.put("operateur", op);
		GameRoom w = watching(name);
		out.put("regarde", w == null ? null : w.id());
		return out;
	}

	/**
	 * La fiche d'une partie dans le chat, avec ses liens [Rejoindre] [Regarder] (tableau du hub cliqué sans le mod client,
	 * {@code /vc partie voir}).
	 */
	public static void tellCard(ServerPlayer p, GameRoom r) {
		GameCard c = r.card();
		ChatFormatting color = switch (c.status()) {
			case OUVERTE -> ChatFormatting.GREEN;
			case EN_COURS -> ChatFormatting.RED;
			case EN_PAUSE -> ChatFormatting.GOLD;
			case SUSPENDUE -> ChatFormatting.GRAY;
		};
		p.sendSystemMessage(net.minecraft.network.chat.Component.literal("« " + c.name() + " »").withStyle(ChatFormatting.GOLD,
				ChatFormatting.BOLD)
			.append(net.minecraft.network.chat.Component.literal("  " + c.status().label).withStyle(color))
			.append(net.minecraft.network.chat.Component.literal(" · " + c.kindLabel()).withStyle(ChatFormatting.GRAY)));
		String watchers = c.watchersLine();
		p.sendSystemMessage(Gui.plain(c.phaseLine() + " · " + c.playersLine() + " · " + c.storytellerLine()
			+ (watchers.isEmpty() ? "" : " · " + watchers), ChatFormatting.WHITE));
		net.minecraft.network.chat.MutableComponent links = net.minecraft.network.chat.Component.empty();
		String name = p.getScoreboardName();
		String join = GameCard.joinRefusal(r.session(), name, r.seats());
		join = join != null ? join : heldElsewhere(r, name);
		links.append(link("[" + GameCard.joinLabel(r.session(), name) + "]", "/vc partie rejoindre " + r.id(), join,
			"S'inscrire à cette partie (ou retrouver ta place), et y aller"));
		links.append(" ");
		String watch = r.session().watchRefusal(name);
		watch = watch != null ? watch : heldElsewhere(r, name);
		links.append(link("[Regarder]", "/vc partie regarder " + r.id(), watch,
			"La regarder en spectateur, sans voir les rôles ni entendre les joueurs"));
		p.sendSystemMessage(links);
	}

	/** Un lien du chat : actif (souligné, une commande au clic), ou grisé avec la raison au survol. */
	private static net.minecraft.network.chat.MutableComponent link(String label, String command, String refusal, String hint) {
		var text = net.minecraft.network.chat.Component.literal(label);
		if (refusal != null) {
			return text.withStyle(st -> st.withColor(ChatFormatting.DARK_GRAY)
				.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(net.minecraft.network.chat.Component.literal(refusal))));
		}
		return text.withStyle(st -> st.withColor(ChatFormatting.AQUA).withUnderlined(true)
			.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(command))
			.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(net.minecraft.network.chat.Component.literal(hint))));
	}

	/**
	 * Ouvre la fiche de cette partie pour ce joueur : l'écran « Parties » du mod client, sur elle ; sans le mod, la fiche
	 * dans le chat avec ses liens.
	 */
	public static void showCard(ServerPlayer p, GameRoom r) {
		if (fr.valcombe.mc.ViewSync.hasClientMod(p)) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new fr.valcombe.net.ValcombeNet.OpenPayload("parties:" + r.id()));
		} else {
			tellCard(p, r);
		}
	}

	// ------------------------------------------------------------------
	// Mondes créés et supprimés
	// ------------------------------------------------------------------

	/**
	 * Un monde de partie vient d'être créé ({@code /vc monde creer essai}, API, {@link #create}) : sa partie ouvre
	 * aussitôt. Le monde reste plat et vide, comme tout monde neuf : on y envoie ensuite une carte (« Envoyer à la
	 * partie »), ce que fait {@link #create} avec le village de Valcombe.
	 */
	@Override
	public void created(WorldEntry entry, ServerLevel level) {
		if (hosts(entry.sorte()) && !rooms.containsKey(entry.id())) {
			open(entry, false);
		}
	}

	@Override
	public void deleted(WorldEntry entry) {
		close(entry.id());
	}

	/**
	 * /vc partie creer : une partie d'essai neuve, dans son propre monde plat, qui reçoit le village de Valcombe par
	 * « Envoyer à la partie » (posé par petits lots, avec sa barre). Renvoie le message pour celui qui l'a demandée.
	 */
	public String create(String name, ServerPlayer by) {
		if (name == null || name.isBlank()) {
			throw new GameException("Donnez un nom à la partie : /vc partie creer <nom>.");
		}
		WorldEntry e = worlds.create(WorldKind.ESSAI, name.strip()); // sa partie ouvre aussitôt (created)
		GameRoom room = byWorld(e.id());
		var sender = ValcombeMod.sender();
		String village;
		if (room == null || sender == null) {
			village = " Envoyez-y une carte : /vc carte envoyer <id> " + e.id() + ".";
		} else {
			try {
				sender.sendVillage(Village.SEATS, 1, "gothique", true, e.id(), by);
				village = " Son village est en route." + McHost.PROGRESS;
			} catch (GameException ex) {
				LOG.warn("Partie « {} » : village non envoyé ({})", e.nom(), ex.getMessage());
				village = " Son village n'a pas pu être posé (" + ex.getMessage() + ") : envoyez-y une carte, /vc carte envoyer <id> "
					+ e.id() + ".";
			}
		}
		LOG.info("Partie d'essai « {} » créée ({}){}.", e.nom(), e.id(), by == null ? "" : " par " + by.getScoreboardName());
		return "Partie d'essai « " + e.nom() + " » créée (" + e.id() + ")." + village + " Y aller : /vc partie aller " + e.id() + ".";
	}

	/** /vc partie supprimer : la partie d'essai et son monde disparaissent (jamais la partie principale, ni pendant une partie). */
	public String delete(String worldId) {
		GameRoom r = byWorld(worldId);
		if (r == null) {
			throw new GameException("Aucune partie dans « " + worldId + " » (/vc partie liste).");
		}
		String name = r.name();
		worlds.delete(r.id()); // refusé par l'hôte (partie principale, partie en cours) avant que rien ne bouge
		return "Partie « " + name + " » supprimée : ses joueurs vont au hub, son monde est effacé.";
	}

	// ------------------------------------------------------------------
	// Joueurs
	// ------------------------------------------------------------------

	/**
	 * Admission d'un joueur à une table (voir {@link GameSeats#admit}) : il quitte les tables où il ne faisait
	 * qu'attendre ; une partie en cours ailleurs le retient.
	 */
	private static String admit(GameRoom target, String player) {
		return GameSeats.admit(all().stream().map(GameRoom::table).toList(), target.table(), player);
	}

	/** Dalles d'inscription (salle d'attente du village de cette partie, ou dalles du hub pour la partie principale). */
	private static void pad(GameRoom room, ServerPlayer p, Village.Pad pad) {
		String name = p.getScoreboardName();
		Session s = room.session();
		try {
			String msg = switch (pad) {
				case JOIN -> s.lobby().contains(name) ? "Tu es déjà inscrit." : s.join(name);
				case LEAVE -> s.lobby().contains(name) ? s.leave(name) : "Tu n'es pas inscrit.";
				case STORYTELLER -> p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)
					? s.toggleStoryteller(name) : "Seul un opérateur peut devenir Conteur.";
			};
			p.sendSystemMessage(Gui.plain(msg, ChatFormatting.GREEN));
		} catch (GameException e) {
			p.sendSystemMessage(Gui.plain(e.getMessage(), ChatFormatting.RED));
		}
	}

	/**
	 * Un joueur se connecte : sa table d'attache (sinon la partie principale) l'accueille. Une partie en cours qu'il
	 * joue ou qu'il conte le retient à sa place ; sinon il arrive au hub.
	 */
	public void onJoin(ServerPlayer p) {
		String name = p.getScoreboardName();
		GameRoom home = home(name);
		GameRoom target = home != null ? home : main();
		if (target == null) {
			return;
		}
		Game g = target.session().game();
		boolean seated = g != null && g.seatOf(name) >= 0;
		boolean stay = seated || (g != null && g.winner() == null && name.equals(target.session().storyteller()));
		boolean suspended = target.suspended();
		// Sa partie est en suspens : il arrive au hub, prévenu ; elle le rappellera à sa reprise.
		target.host().onJoin(p, seated && !suspended, stay && !suspended);
		for (GameRoom r : snapshot) {
			if (r != target) {
				r.host().warnOperator(p);
				r.host().restoreMode(p);
			}
		}
		if (suspended) {
			if (stay) {
				p.sendSystemMessage(Gui.plain("Ta partie « " + target.name() + " » est en suspens : elle reprendra où elle en est, "
					+ "et toi à ta place. Tu seras rappelé à sa reprise.", ChatFormatting.GOLD));
			}
			return;
		}
		// Une seconde plus tard : le client a eu le temps de s'annoncer (écrans du mod ou menus coffre).
		target.host().schedule(20, () -> target.session().onJoin(name));
	}

	/** Un joueur s'en va : chaque partie le note (ses invites l'attendent, le Conteur est prévenu s'il jouait). */
	public void onDisconnect(String name) {
		for (GameRoom r : snapshot) {
			try {
				r.session().stopWatching(name, true); // il regardait : il ne regarde plus (il reviendra au hub)
				r.session().onDisconnect(name);
			} catch (RuntimeException e) {
				LOG.error("Départ de " + name + " dans la partie « " + r.id() + " »", e);
			}
		}
	}

	private static final Map<String, Integer> ERRORS = new java.util.HashMap<>();

	/** Une partie du tick ; une erreur est journalisée (dix fois au plus par sujet) sans rien arrêter. */
	private static void guarded(String what, Runnable r) {
		try {
			r.run();
		} catch (RuntimeException | LinkageError | StackOverflowError e) {
			if (ERRORS.merge(what, 1, Integer::sum) <= 10) {
				LOG.error("Erreur pendant le tick (" + what + ")", e);
			}
		}
	}
}
