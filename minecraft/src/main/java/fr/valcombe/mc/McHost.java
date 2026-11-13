package fr.valcombe.mc;

import fr.valcombe.mc.games.GameTeams;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.session.Host;
import fr.valcombe.session.Msg;
import fr.valcombe.session.Prompt;
import fr.valcombe.session.Settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Pont entre la session de jeu et le serveur Minecraft.
 * <p>
 * La partie se joue dans <b>son monde</b> : un monde de partie de Valcombe (« Partie principale »), lié au démarrage
 * ({@link #bindWorld}) et résolu par le gestionnaire de mondes ({@link #level()}). Le village, la scène, les trônes,
 * les décors, les ménages, l'heure et les chantiers ne touchent qu'à ce monde ; la fiche du village, l'instantané de la
 * carte et la marque de chantier sont rangés dans son dossier et disparaissent avec lui quand il est remis à neuf. Le
 * monde principal de Minecraft ne sert plus au jeu : les joueurs arrivent au hub.
 * <p>
 * <b>Un hôte par partie.</b> Plusieurs parties se jouent en même temps, chacune dans son monde de partie, chacune avec
 * son hôte et sa session (voir {@link fr.valcombe.mc.games.Games}, qui les crée et les relie). Ce qu'un hôte montre et
 * fait entendre (annonces, titres, sons, barres) ne va qu'aux joueurs qui se trouvent dans son monde : on ne voit ni
 * n'entend jamais une autre partie que celle du monde où l'on est.
 */
public final class McHost implements Host, WorldManager.Listener, WorldManager.Guard {
	private record Task(long due, Runnable run) {
	}

	private record SeatPos(ServerLevel level, Vec3 pos, float yaw) {
	}

	/** La session de cette partie (branchée par Games à la création de la partie, avant tout le reste). */
	private fr.valcombe.session.Session session;

	public void setSession(fr.valcombe.session.Session session) {
		this.session = session;
	}

	/** La session de cette partie. */
	public fr.valcombe.session.Session session() {
		return session;
	}

	/**
	 * Le monde de cette partie est celui de la partie principale : il ne se supprime pas (il se remet seulement à neuf)
	 * et il reçoit les dalles du hub. Les parties d'essai, elles, se suppriment entre deux parties.
	 */
	private boolean mainGame = true;

	public void setMainGame(boolean main) {
		this.mainGame = main;
	}

	public boolean mainGame() {
		return mainGame;
	}

	private MinecraftServer server;
	private long tick;
	private final List<Task> tasks = new ArrayList<>();
	private final Map<String, SeatPos> seats = new HashMap<>();
	private final Map<String, GameType> previousMode = new HashMap<>();
	private boolean frozen;
	private ValcombeUi ui;
	private Village village;
	/** Positions de nuit (maisons), quand les joueurs dorment chez eux. */
	private final Map<String, SeatPos> houses = new HashMap<>();
	private boolean inHouses;

	private Community community;
	/** La scène 3D de la place (trônes, jetons, horloge, gibet). */
	private final Stage stage = new Stage(this::schedule);
	/** Les joueurs assis ne peuvent pas se lever (nuit). */
	private boolean sitEnforced;
	/** Joueurs morts de la partie en cours (traînée d'âme). */
	private final java.util.Set<String> ghostNames = new java.util.HashSet<>();

	public void setCommunity(Community community) {
		this.community = community;
	}

	@Override
	public void report(fr.valcombe.session.GameReport report) {
		if (community != null) {
			try {
				community.record(report);
			} catch (RuntimeException e) {
				ValcombeMod.LOG.warn("Statistiques non enregistrées", e);
			}
		}
	}

	/** Avertissement du démarrage (fiche du village illisible…), redit à chaque opérateur qui se connecte. */
	private String startupWarning;

	public void setServer(MinecraftServer server) {
		if (server == null) {
			if (this.server != null) {
				stage.clear(levelIfReady()); // la scène ne reste pas dans le monde enregistré : elle est remontée à la reprise
			}
			builds.clear();
			// L'instantané reste sur le disque : une remise en état coupée par l'arrêt reprend au démarrage.
			restoreAt = -1;
			restoring = false;
			sending = null;
			worlds = null;
			worldId = null;
		}
		this.server = server;
		village = null;
		startupWarning = null;
		noMap = false;
	}

	// ------------------------------------------------------------------
	// Le monde de la partie
	// ------------------------------------------------------------------
	/** Les mondes de Valcombe, qui ouvrent le monde de la partie ; null avant le démarrage (ou s'ils manquent). */
	private WorldManager worlds;
	/**
	 * L'identifiant du monde de Valcombe où se joue cette partie (voir {@link #bindWorld}). C'est une donnée de
	 * l'hôte, jamais le monde principal de Minecraft : un hôte par monde de partie, et plusieurs parties à la fois.
	 */
	private String worldId;
	/**
	 * Le monde de la partie ne porte aucune carte : ni fiche de village dans son dossier, ni carte de la bibliothèque
	 * posée (MapPlacer), et il n'a pas été vidé exprès (MapSender.EMPTY). Il est plat et vide (monde neuf, ou remis à
	 * neuf par /vc monde recreer) : le village de Valcombe y est bâti au démarrage, pour que la partie ait toujours où
	 * se jouer.
	 */
	private boolean noMap;

	/**
	 * Lie cette partie à son monde (fil du serveur, au démarrage, avant {@link #prepareWorld()}) et relit ce que le
	 * dossier de ce monde garde d'elle : la fiche du village, la marque d'un chantier coupé par l'arrêt du serveur.
	 */
	public void bindWorld(WorldManager worlds, String worldId) {
		this.worlds = worlds;
		this.worldId = worldId;
		noMap = false;
		village = null;
		sending = null;
		startupWarning = null;
		Village.Loaded fiche = Village.load(worldFile(Village.FILE));
		switch (fiche.state()) {
			case READY -> village = fiche.village();
			case ABSENT -> {
				// Pas de fiche : le monde est neuf (le registre des mondes est commun à toutes les sauvegardes d'un même
				// dossier de jeu, ses fichiers non), ou remis à neuf. Une carte de la bibliothèque posée là sans passer
				// par la partie (/vc carte poser) n'est pas recouverte, ni un monde vidé exprès (/vc carte vider).
				noMap = !java.nio.file.Files.exists(worldFile(MapPlacer.FILE))
					&& !java.nio.file.Files.exists(worldFile(MapSender.EMPTY));
			}
			case UNREADABLE -> startupWarning = "La fiche du village du monde « " + worldName() + " » (" + Village.FILE
				+ ") est illisible : aucune carte n'est en service et rien n'a été rebâti. Envoyez-y une carte "
				+ "(/vc carte envoyer <id>) ou un village généré (/vc carte construire).";
		}
		try {
			java.nio.file.Path mark = buildMarker();
			if (mark != null && java.nio.file.Files.exists(mark)) {
				String cut = "Un chantier a été interrompu par l'arrêt du serveur (" + java.nio.file.Files.readString(mark).trim()
					+ ") : la carte du monde « " + worldName() + " » est peut-être incomplète. Envoyez-y de nouveau une carte "
					+ "(/vc carte envoyer <id>) ou un village généré (/vc carte construire).";
				startupWarning = startupWarning == null ? cut : startupWarning + " " + cut;
			}
		} catch (java.io.IOException e) {
			ValcombeMod.LOG.warn("Marque de chantier illisible", e);
		}
		if (server != null) {
			// Avant les mondes de Valcombe, la partie se jouait dans le monde principal : ses fichiers sont laissés là
			// (ils décrivent des blocs du monde principal), mais ils ne servent plus.
			java.nio.file.Path config = server.getServerDirectory().resolve("config");
			for (String old : List.of("valcombe-village.json", "valcombe-instantane.nbt", "valcombe-chantier.txt")) {
				if (java.nio.file.Files.exists(config.resolve(old))) {
					ValcombeMod.LOG.info("config/{} (monde principal, avant les mondes de Valcombe) ignoré : la partie se joue "
						+ "dans le monde « {} ».", old, worldName());
				}
			}
		}
	}

	/** L'identifiant du monde de la partie, ou null tant qu'elle n'est liée à aucun monde. */
	public String worldId() {
		return worldId;
	}

	/** Le nom affiché du monde de la partie (« Partie principale »), ou son identifiant. */
	public String worldName() {
		WorldEntry e = worlds == null || worldId == null ? null : worlds.find(worldId);
		return e != null ? e.nom() : worldId == null ? "?" : worldId;
	}

	/**
	 * Le monde de la partie, ouvert au besoin (fil du serveur). Une GameException (message pour le joueur) s'il n'est
	 * pas disponible : mondes de Valcombe absents, ou monde en cours de remise à neuf.
	 */
	public ServerLevel level() {
		if (server == null || worlds == null || worldId == null) {
			throw new fr.valcombe.rules.GameException("Le monde de la partie n'est pas disponible sur ce serveur (voir son journal).");
		}
		return worlds.level(worldId);
	}

	/**
	 * Le monde de la partie s'il est ouvert et prêt, sinon null, sans jamais l'ouvrir : pour ce qui se fait à chaque
	 * tick ou en passant (affichages, ménages), qui attend simplement qu'il revienne.
	 */
	public ServerLevel levelIfReady() {
		if (server == null || worlds == null || worldId == null || !worlds.isOpen(worldId)) {
			return null;
		}
		return server.getLevel(WorldManager.key(worldId));
	}

	/** Ce monde est-il celui de la partie ? */
	public boolean isGameLevel(net.minecraft.world.level.Level level) {
		return level != null && level == levelIfReady();
	}

	/** Ce joueur est-il dans le monde de la partie ? Ailleurs (hub, atelier), il n'est pas dans la partie. */
	public boolean inGameWorld(ServerPlayer p) {
		return p != null && isGameLevel(p.level());
	}

	/** Un fichier du dossier du monde de la partie (il disparaît avec le monde), ou null sans monde. */
	private java.nio.file.Path worldFile(String name) {
		return worlds == null || worldId == null ? null : worlds.folder(worldId).resolve(name);
	}

	/** La fiche du village dans le dossier du monde de la partie : la carte en service (pour les tests en jeu aussi). */
	public java.nio.file.Path villageFile() {
		return worldFile(Village.FILE);
	}

	/** Enregistre la fiche de cette carte, celle du monde de la partie (rien pendant sa remise à neuf : elle part avec lui). */
	private void saveVillage(Village v) {
		java.nio.file.Path f = worldFile(Village.FILE);
		if (f != null && !worlds.busy(worldId)) {
			v.save(f);
		}
	}

	/** Enregistre la fiche de la carte en service (son nom a changé dans la bibliothèque, par exemple). */
	void saveVillage() {
		if (village != null) {
			saveVillage(village);
		}
	}

	/** Lance une commande dans le monde de la partie (rien s'il n'est pas prêt). */
	private void run(String command) {
		ServerLevel level = levelIfReady();
		if (level != null) {
			WorldSetup.run(level, command);
		}
	}

	/**
	 * Le monde de la partie principale ne se supprime pas ; aucun monde de partie ne se remet à neuf ni ne se supprime
	 * pendant une partie (ses joueurs y sont assis). Branché sur le gestionnaire de mondes.
	 */
	@Override
	public String refuse(WorldEntry e, boolean recreate) {
		if (worldId == null || !worldId.equals(e.id())) {
			return null;
		}
		if (!recreate && mainGame) {
			return "« " + e.nom() + " » est le monde de la partie principale : il ne se supprime pas. /vc monde recreer " + e.id()
				+ " le remet à neuf, plat et vide.";
		}
		if (gameRunning()) {
			return "Une partie se joue dans « " + e.nom() + " » : arrêtez-la d'abord (/vc arreter), puis "
				+ (recreate ? "remettez ce monde à neuf." : "supprimez-le.");
		}
		Village v = village;
		if (sending == null && v != null && v.modified()) {
			// Une remise à neuf directe (/vc monde recreer, API d'administration) ne passe pas par MapSender : la carte
			// retouchée à la main est rangée dans la bibliothèque d'abord, comme pour un envoi. Si c'est impossible,
			// rien ne bouge (elle partirait avec le monde).
			try {
				if (maps == null) {
					throw new fr.valcombe.rules.GameException("bibliothèque de cartes indisponible");
				}
				String saved = maps.backup(this, v);
				mapSaved(v);
				ValcombeMod.LOG.info("Monde de la partie « {} » {} : sa carte retouchée à la main est sauvegardée dans la "
					+ "bibliothèque (« {} »).", e.nom(), recreate ? "remis à neuf" : "supprimé", saved);
				tellOperators("La carte de « " + e.nom() + " », retouchée à la main, est sauvegardée dans la bibliothèque : « "
					+ saved + " ».");
			} catch (fr.valcombe.rules.GameException ex) {
				return "Rien n'est " + (recreate ? "remis à neuf" : "supprimé") + " : la carte de « " + e.nom()
					+ " » a été retouchée à la main et sa sauvegarde "
					+ "automatique a échoué (" + ex.getMessage() + "). Enregistrez-la d'abord : /vc carte enregistrer <id> <nom>.";
			}
		}
		return null;
	}

	/** Un message aux opérateurs connectés. */
	private void tellOperators(String text) {
		if (server == null) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (Atelier.isOperator(p)) {
				p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
			}
		}
	}

	/** Une partie se joue-t-elle dans ce monde ? On n'y change pas de carte, on ne le remet pas à neuf. */
	public boolean gameRunning() {
		return session != null && session.game() != null;
	}

	// ------------------------------------------------------------------
	// « Envoyer à la partie » (voir MapSender)
	// ------------------------------------------------------------------
	/** Ce que MapSender est en train d'envoyer à ce monde (« la carte « Port » »), ou null. */
	private String sending;
	/** Qui envoie les cartes au monde de la partie ; null avant le démarrage, ou sans les mondes de Valcombe. */
	private MapSender sender;

	public void setSender(MapSender sender) {
		this.sender = sender;
	}

	/** Qui envoie les cartes au monde de la partie ; une GameException s'il n'est pas disponible. */
	MapSender sender() {
		if (sender == null) {
			throw new fr.valcombe.rules.GameException("Les mondes de Valcombe ne sont pas disponibles sur ce serveur (voir son journal) : "
				+ "on ne peut pas envoyer de carte à la partie.");
		}
		return sender;
	}

	/**
	 * Une carte est en route vers ce monde (label, ce qu'on envoie), ou plus rien (null) : pendant l'envoi, la partie
	 * est occupée (pas de lancement, pas de retouche de la carte).
	 */
	void setSending(String label) {
		sending = label;
	}

	/**
	 * La carte que MapSender vient de poser dans le monde neuf de la partie devient sa carte : enregistrement, salle
	 * d'attente, et, pour un village généré, sa salle de convocation.
	 */
	void adopt(Village v) {
		if (v.isGenerated() && v.libraryId().isEmpty()) {
			adoptGenerated(v);
		} else {
			useVillage(v);
		}
	}

	/**
	 * Le monde de la partie vient d'être remis à neuf, plat et vide : la carte qu'il portait est partie avec lui (sa
	 * fiche aussi, rangée dans son dossier). Il n'y a plus de carte en service jusqu'à ce qu'on en pose une.
	 */
	@Override
	public void recreated(WorldEntry e, ServerLevel level) {
		if (worldId == null || !worldId.equals(e.id())) {
			return;
		}
		forgetMap();
		WorldSetup.apply(level);
		ValcombeMod.LOG.info("Monde de la partie « {} » remis à neuf : aucune carte en service.", e.nom());
	}

	/** Ce que l'hôte retient de la carte du monde de la partie, oublié d'un coup (le monde vient d'être remis à neuf). */
	private void forgetMap() {
		stage.clear(null); // ses entités sont parties avec l'ancien monde
		village = null;
		startupWarning = null;
		lanterns = null;
		hallKey = "";
		carrying.clear();
		handCarry.clear();
		prefabs.clear();
		markerUndo.clear();
		restoreAt = -1;
		restoring = false;
		if (editor != null) {
			editor.forgetHistory();
		}
		refreshLabels();
	}

	/**
	 * Au démarrage, une fois le monde lié : règles de la partie dans ce monde, et village de Valcombe bâti au centre
	 * du sol plat quand le monde ne porte aucune carte.
	 */
	public void prepareWorld() {
		prepareWorld(true);
	}

	/**
	 * Comme {@link #prepareWorld()} ; build faux : un monde sans carte reste plat et vide, parce que sa carte arrive
	 * d'elle-même juste après (une partie d'essai créée reçoit son village par « Envoyer à la partie », posé par petits
	 * lots avec sa barre, au lieu d'être bâti d'un coup).
	 */
	public void prepareWorld(boolean build) {
		ServerLevel level = levelIfReady();
		if (level == null && worlds != null && worldId != null) {
			try {
				level = level();
			} catch (RuntimeException e) {
				ValcombeMod.LOG.error("Monde de la partie « " + worldId + " » impossible à ouvrir", e);
			}
		}
		if (level == null) {
			ValcombeMod.LOG.error("Aucun monde de partie : la partie ne pourra pas se jouer sur ce serveur.");
			return;
		}
		WorldSetup.apply(level);
		if (village == null && noMap && startupWarning == null && build) {
			// La place sur le sol plat, au centre du monde : on y arrive en entrant dans le monde.
			Village v = Village.generated(new BlockPos(0, WorldManager.SURFACE_Y, 0));
			v.build(level);
			saveVillage(v);
			village = v;
			ValcombeMod.LOG.info("Village de Valcombe construit dans « {} » en {}", worldName(), v.center());
		}
		noMap = false;
		if (village == null) {
			// Monde vidé exprès, envoi interrompu ou fiche illisible : surtout ne rien bâtir par-dessus.
			if (startupWarning != null) {
				ValcombeMod.LOG.error(startupWarning);
			}
			return;
		}
		if (village.outdated()) {
			// Plan plus ancien (place plus petite) : le village est reconstruit au même endroit.
			village.clearLegacyHall(level);
			Village v = village.regenerated();
			v.build(level);
			saveVillage(v);
			village = v;
			lanterns = null;
			ValcombeMod.LOG.info("Village de Valcombe agrandi (plan {}) en {}", Village.LAYOUT, v.center());
		}
		if (village.oldThrones()) {
			village.upgradeThrones(level);
			saveVillage(village);
			ValcombeMod.LOG.info("Trônes du village remplacés par les trônes sculptés");
		}
		if (village.isGenerated() && !village.hasHall()) {
			buildHall();
		}
	}

	// ------------------------------------------------------------------
	// S'asseoir sur un trône d'un clic droit
	// ------------------------------------------------------------------
	private int chairCount;

	/**
	 * Clic droit sur un trône de la carte. En partie, chacun s'assoit sur le sien (les autres
	 * sont indiqués) ; hors partie, n'importe qui s'assoit sur un trône libre.
	 * Renvoie false si le bloc n'est pas un siège.
	 */
	public boolean sitAt(ServerPlayer p, BlockPos clicked) {
		if (village == null || server == null || !inGameWorld(p)) {
			return false;
		}
		int k = village.seatIndexAt(clicked);
		if (k < 0) {
			return false;
		}
		if (p.isPassenger()) {
			return true;
		}
		String name = p.getScoreboardName();
		Vec3 feet = village.seatFeet(k);
		String owner = null;
		for (var e : seats.entrySet()) {
			if (e.getValue().pos().distanceToSqr(feet) < 0.5) {
				owner = e.getKey();
			}
		}
		SeatPos mine = seats.get(name);
		if (mine != null) {
			if (name.equals(owner)) {
				teleport(p, mine);
				sit(List.of(name), sitEnforced);
			} else {
				p.sendSystemMessage(Component.literal(owner != null ? "Ce trône est celui de " + owner + ". Le tien scintille."
					: "Ce trône est libre. Le tien scintille.").withStyle(ChatFormatting.GRAY));
				p.level().sendParticles(p, net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, true, false,
					mine.pos().x, mine.pos().y + 1, mine.pos().z, 20, 0.4, 0.6, 0.4, 0);
			}
			return true;
		}
		if (owner != null) {
			p.sendSystemMessage(Component.literal("Ce trône est celui de " + owner + ".").withStyle(ChatFormatting.GRAY));
			return true;
		}
		String tag = "vc_chair_" + (++chairCount);
		float yaw = village.seatYaw(k);
		run(String.format(java.util.Locale.ROOT,
			"summon minecraft:item_display %.3f %.3f %.3f {Tags:[\"vc_chair\",\"%s\"],Rotation:[%.1ff,0f]}",
			feet.x, feet.y + 0.45, feet.z, tag, yaw));
		p.teleportTo((ServerLevel) p.level(), feet.x, feet.y, feet.z, Set.of(), yaw, p.getXRot(), false);
		run("ride " + name + " mount @e[distance=0..,type=minecraft:item_display,tag=" + tag + ",limit=1]");
		p.sendSystemMessage(Component.literal("Accroupis-toi pour te lever.").withStyle(ChatFormatting.GRAY), true);
		return true;
	}

	/** Les sièges libres (hors partie) disparaissent quand on se lève. */
	private void cleanChairs() {
		ServerLevel level = levelIfReady();
		if (chairCount == 0 || level == null) {
			return;
		}
		List<net.minecraft.world.entity.Entity> empty = new ArrayList<>();
		for (var e : level.getAllEntities()) {
			if (e.entityTags().contains("vc_chair") && e.getPassengers().isEmpty()) {
				empty.add(e);
			}
		}
		empty.forEach(net.minecraft.world.entity.Entity::discard);
	}

	// ------------------------------------------------------------------
	// Plume d'accusation : viser un joueur de loin
	// ------------------------------------------------------------------
	/** Joueur de la partie visé par le regard (son corps ou son trône), à 48 blocs au plus ; null sinon. */
	public String aimedPlayer(ServerPlayer p) {
		if (!inGameWorld(p)) {
			return null; // les trônes et les joueurs de la partie sont dans son monde
		}
		Vec3 eye = p.getEyePosition();
		Vec3 dir = p.getLookAngle();
		String best = null;
		double bestScore = Double.MAX_VALUE;
		for (var e : seats.entrySet()) {
			if (e.getKey().equals(p.getScoreboardName())) {
				continue;
			}
			ServerPlayer o = player(e.getKey());
			Vec3 body = o != null && o.level() == p.level() ? o.position() : e.getValue().pos();
			Vec3 to = body.add(0, 1.0, 0).subtract(eye);
			double t = to.dot(dir);
			if (t <= 0.5 || t > 48) {
				continue;
			}
			double off = to.subtract(dir.scale(t)).length();
			if (off > 1.1 + t * 0.015) {
				continue;
			}
			double score = off / t;
			if (score < bestScore) {
				bestScore = score;
				best = e.getKey();
			}
		}
		return best;
	}

	// ------------------------------------------------------------------
	// Salle d'attente vivante : présentoir des rôles du script, panneau de la prochaine partie
	// ------------------------------------------------------------------
	private String hallKey = "";
	/** Générations du présentoir et des panneaux de la salle : les anciennes sont retirées. */
	private int hallGen = (int) (System.nanoTime() & 0xFFFFF);
	private int boardGen = (int) (System.nanoTime() >> 8 & 0xFFFFF);

	/** Les rôles du script en cours, rangés comme sur le mur de la salle d'attente (par type, puis dans l'ordre du jeu). */
	public static List<fr.valcombe.rules.Role> hallRoles(fr.valcombe.session.Session ses) {
		List<fr.valcombe.rules.Role> roles = new ArrayList<>(ses.script());
		roles.sort(java.util.Comparator.comparingInt((fr.valcombe.rules.Role r) -> r.type.ordinal()).thenComparing(Enum::ordinal));
		return roles;
	}

	/**
	 * Le panneau de la prochaine partie (ou de la partie en cours), affiché au-dessus des rôles du script dans la
	 * salle d'attente du village et sur le mur des rôles du hub.
	 */
	public static String hallText(fr.valcombe.session.Session ses) {
		var g = ses.game();
		String name = ses.scenario() != null ? "« " + ses.scenario().nom + " »" : ses.scriptName();
		List<String> lobby = ses.lobby();
		String st = ses.storyteller();
		return g != null
			? "Partie en cours (" + (g.phase() == fr.valcombe.rules.Game.Phase.NIGHT ? "nuit " + g.night() : "jour " + g.day()) + ")"
				+ "\nRôles du script : " + name
			: "Prochaine partie : " + name + "\n" + (lobby.isEmpty() ? "Personne n'est encore inscrit"
				: lobby.size() + " inscrit(s) : " + String.join(", ", lobby.stream().limit(8).toList())
					+ (lobby.size() > 8 ? "…" : ""))
				+ "\nConteur : " + (st == null ? "automatique" : st);
	}

	public void updateHall(fr.valcombe.session.Session ses) {
		ServerLevel level = levelIfReady();
		// Seulement quand quelqu'un est dans le monde de la partie : la salle est alors chargée (on y arrive), et
		// personne d'autre n'a besoin du présentoir.
		if (level == null || village == null || village.hallSpot() == null || level.players().isEmpty()) {
			return;
		}
		List<fr.valcombe.rules.Role> roles = hallRoles(ses);
		String text = hallText(ses);
		String key = text + "|" + roles + "|" + fr.valcombe.rules.Role.customDefs().size();
		if (key.equals(hallKey)) {
			return;
		}
		hallKey = key;
		hallGen++;
		Vec3 h = village.hallSpot();
		run("kill @e[distance=0..,tag=vc_presentoir]");
		run(String.format(java.util.Locale.ROOT,
			"summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"vc_presentoir\",\"vc_pres_" + hallGen + "\"],billboard:\"center\",line_width:260,"
				+ "background:-1308622848,text:{text:\"%s\",color:\"gold\"}}",
			h.x, h.y + 4.4, h.z, text.replace("\"", "'")));
		// Jetons des rôles du script, en rangées le long du mur du fond
		int perRow = 13;
		for (int i = 0; i < roles.size(); i++) {
			fr.valcombe.rules.Role r = roles.get(i);
			String model = "jeton_" + r.modelSuffix();
			int row = i / perRow;
			int inRow = Math.min(perRow, roles.size() - row * perRow);
			double x = h.x + (i % perRow - (inRow - 1) / 2.0) * 1.2;
			double y = h.y + 4.1 - row * 0.75;
			run(String.format(java.util.Locale.ROOT,
				"summon minecraft:item_display %.2f %.2f %.2f {Tags:[\"vc_presentoir\",\"vc_pres_" + hallGen + "\"],billboard:\"vertical\","
					+ "brightness:{sky:15,block:15},item:{id:\"minecraft:paper\",count:1,components:{\"minecraft:item_model\":"
					+ "\"valcombe:%s\",\"minecraft:custom_name\":\"%s\"}},transformation:{translation:[0f,0f,0f],"
					+ "left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[0.7f,0.7f,0.7f]}}",
				x, y, h.z - 6.2, model, r.displayName.replace("\"", "'")));
		}
	}

	/** Un voyageur arrive : un trône libre de la carte (ou une place près du cercle), et une maison libre. */
	@Override
	public void addSeat(String name) {
		ServerPlayer p = player(name);
		ServerLevel level = levelIfReady();
		if (level == null || p == null || seats.containsKey(name)) {
			return;
		}
		Vec3 spot = null;
		if (village != null) {
			for (int k = 0; k < village.seatCount() && spot == null; k++) {
				Vec3 f = village.seatFeet(k);
				if (seats.values().stream().noneMatch(s -> s.pos().distanceToSqr(f) < 0.5)) {
					spot = f;
				}
			}
			for (Vec3 h : village.houseFeet()) {
				if (houses.values().stream().noneMatch(s -> s.pos().distanceToSqr(h) < 0.5)) {
					houses.put(name, new SeatPos(level, h, village.yawToCenter(h) + 180));
					break;
				}
			}
		}
		if (spot == null) {
			// Sans carte : là où il se tient dans le monde de la partie, ou au centre de ce monde s'il arrive d'ailleurs.
			Vec3 c = village != null ? new Vec3(village.center().getX() + 0.5, village.center().getY() + 1, village.center().getZ() + 3.5)
				: inGameWorld(p) ? p.position() : WorldManager.spawn();
			spot = c;
		}
		float yaw = village != null ? village.yawToCenter(spot) : p.getYRot();
		SeatPos sp = new SeatPos(level, spot, yaw);
		seats.put(name, sp);
		previousMode.putIfAbsent(name, p.gameMode.getGameModeForPlayer());
		p.setGameMode(GameType.ADVENTURE);
		teleport(p, sp);
		ValcombeUi.accuseItem(p, true);
		stage.addSeat(name, new Stage.Spot(spot, yaw));
	}

	private boolean glowing;

	/**
	 * Pendant le conseil (accusations ouvertes ou vote en cours), les joueurs de la partie brillent :
	 * on voit leur silhouette à travers les murs, pour savoir qui est où.
	 */
	private void councilGlow() {
		var ses = session;
		var g = ses.game();
		boolean council = g != null && g.phase() == fr.valcombe.rules.Game.Phase.DAY && !g.dayOver()
			&& (ses.nominationsOpen() || ses.voteOpen());
		if (!council && !glowing) {
			return;
		}
		glowing = council;
		for (String name : seats.keySet()) {
			ServerPlayer p = player(name);
			if (p == null) {
				continue;
			}
			if (council) {
				p.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0, false, false));
			} else {
				p.removeEffect(MobEffects.GLOWING);
			}
		}
	}

	@Override
	public double[] seatPosition(String player) {
		SeatPos s = seats.get(player);
		return s == null ? null : new double[] {s.pos().x, s.pos().y, s.pos().z};
	}

	@Override
	public int gamesPlayed(String player) {
		return community == null ? 0 : community.games(player);
	}

	@Override
	public void storytellerTools(String name, boolean on) {
		ServerPlayer p = player(name);
		if (p != null) {
			ValcombeUi.staffItem(p, on);
		}
	}

	private final Map<String, String> featherHint = new HashMap<>();

	/** Indication dans la barre d'action quand on tient la plume : qui est visé, ou comment voter. */
	private void featherHints() {
		var ses = session;
		var g = ses.game();
		// Bâton du Conteur : le rôle du joueur visé, comme dans le grimoire
		String st = ses.storyteller();
		ServerPlayer sp = st == null ? null : player(st);
		if (sp != null) {
			String hint = "";
			if (g != null && ValcombeUi.isStaffItem(sp.getMainHandItem())) {
				String target = aimedPlayer(sp);
				hint = target != null ? ses.roleSummary(target)
					: ses.aside() != null ? "Aparté avec " + ses.aside() + " : accroupi + clic droit pour finir"
					: "Vise un joueur pour voir son rôle";
			}
			String before = featherHint.getOrDefault(st, "");
			if (!hint.equals(before) || (!hint.isEmpty() && tick % 40 == 0)) {
				featherHint.put(st, hint);
				if (!hint.isEmpty() || !before.isEmpty()) {
					sp.sendSystemMessage(Component.literal(hint).withStyle(ChatFormatting.LIGHT_PURPLE), true);
				}
			}
		}
		for (String name : seats.keySet()) {
			ServerPlayer p = player(name);
			if (p == null) {
				continue;
			}
			String hint = "";
			if (g != null && g.phase() == fr.valcombe.rules.Game.Phase.DAY
				&& ValcombeUi.isAccuseItem(p.getMainHandItem())) {
				if (ses.voteOpen()) {
					hint = !ses.canVote(name) ? "Ta voix n'est plus attendue pour ce vote."
						: ses.handRaised(name) ? "✋ Main levée : clic droit (ou H) pour la baisser"
						: "Main baissée : clic droit (ou H) pour la lever";
				} else if (!ses.nominationsOpen()) {
					hint = "Les nominations ne sont pas ouvertes.";
				} else {
					String target = aimedPlayer(p);
					hint = target == null ? "Vise un joueur, puis clic droit pour l'accuser"
						: "⚖ " + target + " : clic droit pour l'accuser";
				}
			}
			String before = featherHint.getOrDefault(name, "");
			if (!hint.equals(before) || (!hint.isEmpty() && tick % 40 == 0)) {
				featherHint.put(name, hint);
				if (!hint.isEmpty() || !before.isEmpty()) {
					p.sendSystemMessage(Component.literal(hint).withStyle(ChatFormatting.GOLD), true);
				}
			}
		}
	}

	/** Construit la salle d'attente du village et ses panneaux de règles. */
	public String buildHall() {
		Village v = requireVillage();
		v.buildHall(level());
		saveVillage(v);
		run("kill @e[distance=0..,type=minecraft:text_display,tag=valcombe_salle]");
		boardGen++;
		Vec3 hc = v.hallSpot();
		for (var e : v.hallBoards().entrySet()) {
			Vec3 p = e.getValue();
			// Panneaux plaqués contre les murs, tournés vers la salle (sinon le texte traverse le mur) ;
			// celui du centre suit le regard.
			double dx = p.x - hc.x;
			double dz = p.z - hc.z;
			boolean wall = Math.abs(dx) > 3 || Math.abs(dz) > 3;
			float yaw = Math.abs(dx) > Math.abs(dz) ? (dx < 0 ? -90f : 90f) : 0f;
			run(String.format(java.util.Locale.ROOT,
				"summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"valcombe_salle\",\"vc_salle_%d\"],Rotation:[%.1ff,0f],"
					+ "billboard:\"%s\",line_width:220,background:-937749996,shadow:1b,"
					+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],"
					+ "scale:[%.2ff,%.2ff,%.2ff]},text:\"%s\"}",
				p.x, p.y, p.z, boardGen, yaw, wall ? "fixed" : "center", wall ? 1.35 : 0.6, wall ? 1.35 : 0.6, wall ? 1.35 : 0.6,
				e.getKey().replace("\"", "'").replace("\n", "\\n")));
		}
		return "Salle d'attente construite : dalle dorée pour s'inscrire, rouge pour se désinscrire.";
	}

	/** Gestion des dalles de la salle : appelée avec le joueur et la dalle sur laquelle il vient de marcher. */
	private java.util.function.BiConsumer<ServerPlayer, Village.Pad> padHandler;
	private final Map<String, Village.Pad> lastPad = new HashMap<>();

	public void setPadHandler(java.util.function.BiConsumer<ServerPlayer, Village.Pad> handler) {
		this.padHandler = handler;
	}

	/** Dalles de la salle d'accueil du hub : la dalle sous les pieds d'un joueur qui s'y trouve, ou null. */
	private java.util.function.Function<ServerPlayer, Village.Pad> hubPads;

	/** Branche les dalles du hub (mêmes dalles, mêmes effets que celles de la salle d'attente du village). */
	public void setHubPads(java.util.function.Function<ServerPlayer, Village.Pad> hubPads) {
		this.hubPads = hubPads;
	}

	/**
	 * Arrivée au hub d'un joueur qui se connecte : vrai s'il y a été envoyé (sinon, il va sur la place du village
	 * comme avant les mondes de Valcombe).
	 */
	private java.util.function.Predicate<ServerPlayer> hubArrival;

	public void setHubArrival(java.util.function.Predicate<ServerPlayer> hubArrival) {
		this.hubArrival = hubArrival;
	}

	private void checkPads() {
		if (padHandler == null || server == null || (village == null && hubPads == null)) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Village.Pad pad = hubPads != null ? hubPads.apply(p) : null;
			if (pad == null && village != null && inGameWorld(p)) {
				// Seulement dans le monde de la partie : les mêmes blocs posés dans un autre monde ne sont pas des dalles.
				pad = village.padAt(p.blockPosition(), p.level());
			}
			Village.Pad before = lastPad.put(p.getScoreboardName(), pad);
			if (pad != null && pad != before) {
				padHandler.accept(p, pad);
			}
		}
	}

	/**
	 * L'avertissement du démarrage de cette partie (fiche illisible, chantier coupé), redit à un opérateur qui se
	 * connecte : les autres parties du serveur l'appellent pour lui, la sienne le dit dans {@link #onJoin}.
	 */
	public void warnOperator(ServerPlayer p) {
		if (startupWarning != null && p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
			p.sendSystemMessage(Component.literal("[Valcombe] " + startupWarning).withStyle(ChatFormatting.RED));
		}
	}

	/**
	 * Un joueur arrive sans être de cette partie (une autre l'accueille) : s'il y avait joué en mode aventure, un
	 * opérateur retrouve son mode de jeu d'avant.
	 */
	public void restoreMode(ServerPlayer p) {
		GameType mode = previousMode.remove(p.getScoreboardName());
		if (mode != null && p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)
			&& (session == null || !session.holds(p.getScoreboardName()))) {
			p.setGameMode(mode);
		}
	}

	/** Arrivée d'un joueur : comme {@link #onJoin(ServerPlayer, boolean, boolean)}, retenu au village s'il joue. */
	public void onJoin(ServerPlayer p, boolean inGame) {
		onJoin(p, inGame, inGame);
	}

	/**
	 * Arrivée d'un joueur : mode aventure (sauf opérateurs). Au hub, sauf si la partie en cours le retient au
	 * village (stay : il y a sa place, ou il en est le Conteur) ; sans hub, sur la place du village du monde de la
	 * partie.
	 */
	public void onJoin(ServerPlayer p, boolean inGame, boolean stay) {
		boolean op = p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
		if (!op) {
			p.setGameMode(GameType.ADVENTURE);
		} else if (startupWarning != null) {
			p.sendSystemMessage(Component.literal("[Valcombe] " + startupWarning).withStyle(ChatFormatting.RED));
		}
		if (!inGame) {
			// Parti avant la fin de la partie : il ne garde ni la nuit, ni l'apparence de fantôme, ni la plume.
			p.removeEffect(MobEffects.BLINDNESS);
			p.removeEffect(MobEffects.INVISIBILITY);
			ValcombeUi.accuseItem(p, false);
			GameType mode = previousMode.remove(p.getScoreboardName());
			if (mode != null && op) {
				p.setGameMode(mode);
			}
		}
		if (stay) {
			return; // sa place l'attend : la partie l'y ramène (Session.onJoin)
		}
		var hub = hubArrival;
		if (hub != null) {
			// L'arrivée de Fabric (JOIN) est appelée au milieu de PlayerList.placeNewPlayer, avant que Minecraft n'ajoute
			// le joueur à son monde de départ : un voyage vers un autre monde à ce moment-là le laisserait inscrit dans les
			// deux (le hub le garderait en double, et le voyage suivant le marquerait retiré). Le hub l'accueille donc au
			// tick suivant, une fois le joueur bien posé dans le monde où Minecraft le place (le monde principal pour un
			// nouveau venu : il n'y passe que ce tick-là). Horloge du serveur : une partie en suspens n'arrête pas l'accueil.
			later(1, () -> {
				if (p.isRemoved() || server.getPlayerList().getPlayer(p.getUUID()) != p) {
					return; // reparti entre-temps
				}
				if (!hub.test(p)) {
					toVillageSpawn(p);
				}
			});
			return;
		}
		toVillageSpawn(p);
	}

	/**
	 * Sur la place (ou la salle d'attente) du village du monde de la partie, ou au centre de ce monde sans carte ;
	 * sans monde de partie, le joueur reste où il est.
	 */
	private void toVillageSpawn(ServerPlayer p) {
		ServerLevel level = levelIfReady();
		if (level == null) {
			return;
		}
		Vec3 at = village != null ? Vec3.atBottomCenterOf(village.spawn()) : WorldManager.spawn();
		p.teleportTo(level, at.x, at.y, at.z, Set.of(), 180, 0, false);
	}

	/** Le point d'arrivée du village du monde de la partie (salle d'attente ou place), ou null sans carte en service. */
	public BlockPos villageSpawn() {
		return village == null ? null : village.spawn();
	}

	/** Réveil ou sommeil décidé par le Conteur, la nuit : le joueur retrouve ou perd la vue. */
	@Override
	public void wake(String name, boolean awake, Settings settings) {
		ServerPlayer p = player(name);
		if (p == null) {
			return;
		}
		if (awake) {
			p.removeEffect(MobEffects.BLINDNESS);
		} else if (settings.nightDark) {
			p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 * 60 * 30, 0, false, false));
		}
	}

	/** Retour d'un joueur en cours de partie : sa place (maison la nuit, trône le jour) et l'état de la phase. */
	@Override
	public void rejoin(String name, boolean night, Settings settings) {
		ServerPlayer p = player(name);
		if (p == null) {
			return;
		}
		SeatPos target = (inHouses && houses.containsKey(name) ? houses : seats).get(name);
		if (target != null) {
			teleport(p, target);
		}
		if (night && settings.nightDark) {
			p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 * 60 * 30, 0, false, false));
		} else {
			p.removeEffect(MobEffects.BLINDNESS);
		}
		if (!inHouses) {
			sit(List.of(name), night && settings.nightFreeze);
		}
		nightFog(List.of(name), night, settings.fullEffects);
	}

	// ------------------------------------------------------------------
	// Repères de la carte : /vc carte trone, maison, retirer, centre, infos (une nouvelle carte arrive par MapSender)
	// ------------------------------------------------------------------

	/**
	 * La carte du village se travaille sur place : un geste fait depuis un autre monde (le hub, une partie d'essai)
	 * prendrait la position du joueur dans ce monde-là pour une position du village.
	 */
	private void requireGameWorld(ServerPlayer p) {
		if (p != null && !inGameWorld(p)) {
			throw new fr.valcombe.rules.GameException("La carte du village se travaille sur place : va d'abord dans « "
				+ worldName() + " » (menu J, « Mondes », ou /vc monde aller " + (worldId == null ? "partie" : worldId) + ").");
		}
	}

	private Village requireVillage() {
		if (village == null) {
			throw new fr.valcombe.rules.GameException("Aucune carte : générez un village (/vc carte generer) ou partez d'une carte "
				+ "vide (/vc carte nouvelle).");
		}
		return village;
	}

	/** Les repères ne se modifient que sur une carte en place, hors partie et hors chantier : même règle pour le jeu et l'Atelier. */
	Village requireEditable() {
		Village v = requireVillage();
		if (session.game() != null) {
			throw new fr.valcombe.rules.GameException("On ne touche pas à la carte pendant une partie.");
		}
		requireFree();
		return v;
	}

	/** Ce qu'on peut importer depuis le jeu : les fichiers du dossier d'import, puis les structures du monde. */
	public List<String> structures() {
		List<String> out = new ArrayList<>(ValcombeMod.structures().importable());
		server.getStructureTemplateManager().listTemplates()
			.filter(i -> !i.getNamespace().equals("minecraft") || !i.getPath().contains("/"))
			.map(Object::toString).limit(60).forEach(out::add);
		return out;
	}

	/** Plus de repères d'une sorte qu'une carte n'en accepte (voir MapStore). */
	static final int MAX_MARKERS = 40;

	/** Les repères ont changé : on enregistre et on rafraîchit les étiquettes du mode construction. */
	void markersChanged() {
		village.setModified(true);
		saveVillage(village);
		refreshLabels();
	}

	/** Repères d'avant chaque modification faite à la main (outils, commandes, plan de l'Atelier), pour l'annuler. */
	private final java.util.ArrayDeque<Village.Marks> markerUndo = new java.util.ArrayDeque<>();
	private static final int MARKER_UNDO = 40;

	/** Une modification des repères est faite : on garde l'état d'avant, puis on enregistre. */
	private void markersChanged(Village.Marks before) {
		markerUndo.push(before);
		while (markerUndo.size() > MARKER_UNDO) {
			markerUndo.removeLast();
		}
		markersChanged();
	}

	/** Combien de modifications des repères on peut annuler (pour l'« annuler » commun, voir SharedUndo). */
	int markerUndoDepth() {
		return markerUndo.size();
	}

	/** L'éditeur a posé ou retiré un repère avec une structure : il a sa propre annulation, celle des repères repart de zéro. */
	void editorMarkersChanged() {
		markerUndo.clear();
		markersChanged();
	}

	void requireRoom(Village v, boolean seat) {
		if ((seat ? v.seatCount() : v.houseCount()) >= MAX_MARKERS) {
			throw new fr.valcombe.rules.GameException("Une carte compte " + MAX_MARKERS + (seat ? " trônes" : " maisons") + " au plus.");
		}
	}

	/** Un repère trop loin de la place donnerait une carte démesurée, impossible à enregistrer ou à remplacer. */
	static void requireNear(Village v, BlockPos at) {
		if (!v.reachable(at)) {
			throw new fr.valcombe.rules.GameException("Ce point est trop loin de la place (" + (Village.MAX_EXTENT - 8)
				+ " blocs au plus).");
		}
	}

	/** Déplace un repère du plan ; le centre passe par moveCenter, qui vérifie que tout reste à portée. */
	private void moveMarker(Village v, Village.Hit h, BlockPos to) {
		if (h.type() == Village.Mark.CENTER) {
			moveCenter(v, to.below());
		} else {
			// La maison ou la salle bâtie d'un clic suit son repère : la gomme la retirera encore.
			var built = prefabs.remove(v.markerPos(h));
			v.moveMarker(h, to);
			if (built != null) {
				prefabs.put(to, built);
			}
		}
	}

	/** Déplace le centre d'une carte faite à la main ; refusé si un repère se retrouvait hors de portée. */
	private void moveCenter(Village v, BlockPos to) {
		BlockPos before = v.center();
		v.setCenter(to);
		if (v.farthest() > Village.MAX_EXTENT) {
			v.setCenter(before);
			throw new fr.valcombe.rules.GameException("Ce centre laisserait un repère à plus de " + (Village.MAX_EXTENT - 8)
				+ " blocs de la place : déplacez d'abord les repères.");
		}
	}

	/**
	 * Un opérateur casse ou pose un bloc à la main sur la carte, hors partie : elle devient « retouchée »
	 * et ne sera plus remplacée sans être sauvegardée. (En partie, la remise en état défait les changements.)
	 */
	public void noteEdit(ServerPlayer p, BlockPos pos) {
		Village v = village;
		if (v == null || server == null || v.modified() || session.game() != null || !inGameWorld(p)
			|| p.gameMode.getGameModeForPlayer() == GameType.ADVENTURE || p.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
			return;
		}
		int reach = v.extent() + 8;
		if (Math.abs(pos.getX() - v.center().getX()) <= reach && Math.abs(pos.getZ() - v.center().getZ()) <= reach) {
			v.setModified(true);
			saveVillage(v);
		}
	}

	/** L'éditeur vient de changer des blocs : comme pour un bloc posé à la main, la carte est tenue pour retouchée. */
	void markEdited() {
		Village v = village;
		if (v != null && server != null && !v.modified()) {
			v.setModified(true);
			saveVillage(v);
		}
	}

	/** La carte v vient d'être enregistrée dans la bibliothèque : elle n'a plus rien à perdre. */
	void mapSaved(Village v) {
		v.setModified(false);
		if (v == village) {
			saveVillage(v);
		}
	}

	public String addSeat(ServerPlayer p) {
		requireGameWorld(p);
		return addSeatAt(p.blockPosition());
	}

	public String addHouse(ServerPlayer p) {
		requireGameWorld(p);
		return addHouseAt(p.blockPosition());
	}

	public String addSeatAt(BlockPos pos) {
		Village v = requireEditable();
		requireRoom(v, true);
		requireNear(v, pos);
		Village.Marks before = v.marks();
		v.addSeat(pos);
		markersChanged(before);
		return "Trône " + v.seatCount() + " posé.";
	}

	public String addHouseAt(BlockPos pos) {
		Village v = requireEditable();
		requireRoom(v, false);
		requireNear(v, pos);
		Village.Marks before = v.marks();
		v.addHouse(pos);
		markersChanged(before);
		return "Maison " + v.houseCount() + " posée.";
	}

	public String removeSeat() {
		Village v = requireEditable();
		Village.Marks before = v.marks();
		if (!v.removeLastSeat()) {
			return "Aucun trône à retirer.";
		}
		markersChanged(before);
		return "Dernier trône retiré (" + v.seatCount() + " restants).";
	}

	/**
	 * Annule la dernière modification des repères, quelle qu'elle soit (pose, déplacement, retrait, renumérotation,
	 * centre), qu'elle vienne d'un outil, d'une commande ou du plan de l'Atelier.
	 */
	public String undoMarker() {
		Village v = requireEditable();
		if (markerUndo.isEmpty()) {
			return "Rien à annuler : aucun repère modifié depuis que cette carte est en place.";
		}
		v.restore(markerUndo.pop());
		// Une maison ou une salle bâtie d'un clic part avec le repère dont on annule la pose.
		Village.Marks now = v.marks();
		ServerLevel level = level();
		prefabs.entrySet().removeIf(e -> {
			BlockPos at = e.getKey();
			if (now.seats().contains(at) || now.houses().contains(at) || at.equals(now.convocation())) {
				return false;
			}
			e.getValue().forEach((b, s) -> level.setBlock(b, s, Clip.EXACT));
			return true;
		});
		carrying.clear();
		markersChanged();
		return "Dernière modification des repères annulée (" + v.seatCount() + " trônes, " + v.houseCount() + " maisons).";
	}

	public String removeHouse() {
		Village v = requireEditable();
		Village.Marks before = v.marks();
		if (!v.removeLastHouse()) {
			return "Aucune maison à retirer.";
		}
		markersChanged(before);
		return "Dernière maison retirée (" + v.houseCount() + " restantes).";
	}

	/** Renumérote trônes et maisons dans le sens des aiguilles d'une montre, en partant du nord. */
	public String sortMarkers() {
		Village v = requireEditable();
		Village.Marks before = v.marks();
		v.sortClockwise();
		markersChanged(before);
		return "Trônes et maisons renumérotés dans le sens des aiguilles d'une montre, en partant du nord.";
	}

	// ------------------------------------------------------------------
	// Outils de carte (mode construction) : un objet par sorte de repère, et une gomme
	// ------------------------------------------------------------------
	/** Repère qu'un constructeur a pris en main pour le déplacer. */
	private final Map<String, Village.Hit> carrying = new HashMap<>();
	/** Constructions bâties d'un clic (maison, salle) : repère → blocs d'avant, pour tout remettre à la gomme. */
	private final Map<BlockPos, Map<BlockPos, net.minecraft.world.level.block.state.BlockState>> prefabs = new HashMap<>();

	private static Village.Mark markOf(ValcombeUi.MapTool tool) {
		return switch (tool) {
			case SEAT -> Village.Mark.SEAT;
			case HOUSE -> Village.Mark.HOUSE;
			case ROOM -> Village.Mark.ROOM;
			default -> Village.Mark.CENTER;
		};
	}

	/** Accord du participe : « Trône 3 déplacé », « Maison 3 déplacée ». */
	private static String done(Village.Hit h, String participle) {
		return participle + (h.type() == Village.Mark.HOUSE || h.type() == Village.Mark.ROOM ? "e" : "");
	}

	// ------------------------------------------------------------------
	// L'outil « Carte : main » : un menu pour déplacer, tourner ou retirer ce que l'on vise
	// ------------------------------------------------------------------
	/** Constructeurs qui ont pris un repère avec l'outil « Carte : main » (le prochain clic le repose). */
	private final Set<String> handCarry = new java.util.HashSet<>();

	private static String typeName(Village.Mark m) {
		return switch (m) {
			case SEAT -> "trone";
			case HOUSE -> "maison";
			case ROOM -> "salle";
			case CENTER -> "centre";
		};
	}

	private static Village.Mark markOf(String type) {
		return switch (type) {
			case "trone" -> Village.Mark.SEAT;
			case "maison" -> Village.Mark.HOUSE;
			case "salle" -> Village.Mark.ROOM;
			case "centre" -> Village.Mark.CENTER;
			default -> throw new fr.valcombe.rules.GameException("Sorte d'objet inconnue : " + type + ".");
		};
	}

	/** Clic droit avec l'outil « Carte : main » : reposer ce qu'on porte, ou ouvrir le menu de ce qu'on vise. */
	public String handClick(ServerPlayer p, BlockPos clicked) {
		requireGameWorld(p);
		Village v = requireEditable();
		String who = p.getScoreboardName();
		BlockPos at = clicked.above();
		Village.Hit held = handCarry.contains(who) ? carrying.get(who) : null;
		if (held != null) {
			Village.Marks before = v.marks();
			if (held.type() != Village.Mark.CENTER) {
				requireNear(v, at);
			}
			if (held.type() == Village.Mark.CENTER) {
				moveCenter(v, clicked);
			} else {
				moveMarker(v, held, at);
			}
			carrying.remove(who);
			handCarry.remove(who);
			markersChanged(before);
			if (held.type() == Village.Mark.SEAT) {
				refreshThrones();
			}
			return held.label() + done(held, " déplacé") + " ici.";
		}
		Village.Hit h = v.markerAt(at, null, 1);
		if (h == null && Math.abs(v.center().getX() - clicked.getX()) <= 1 && Math.abs(v.center().getZ() - clicked.getZ()) <= 1
			&& Math.abs(v.center().getY() - clicked.getY()) <= 2) {
			h = new Village.Hit(Village.Mark.CENTER, 0);
		}
		if (h != null) {
			String extra = h.type() == Village.Mark.SEAT ? " (regarde vers le " + Village.compass(v.seatYaw(h.index())) + ")" : "";
			openObject(p, typeName(h.type()), h.index(), h.label() + extra);
			return "";
		}
		int s = editor == null ? -1 : editor.placedAt(clicked);
		if (s >= 0) {
			openObject(p, "structure", s, editor.placedName(s));
			return "";
		}
		throw new fr.valcombe.rules.GameException("Rien à prendre ici : visez un trône, une maison, la salle, le centre, "
			+ "ou une structure posée depuis le catalogue.");
	}

	/** Le menu d'un objet : l'écran du mod, ou à défaut des liens dans le chat. */
	private void openObject(ServerPlayer p, String type, int index, String label) {
		if (ViewSync.hasClientMod(p)) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p,
				new fr.valcombe.net.ValcombeNet.OpenPayload("objet:" + type + ":" + index + ":" + label));
			return;
		}
		List<String[]> actions = new ArrayList<>();
		actions.add(new String[] {"Déplacer", "deplacer"});
		if (type.equals("trone")) {
			actions.add(new String[] {"⟲", "gauche"});
			actions.add(new String[] {"⟳", "droite"});
			actions.add(new String[] {"Vers le centre", "centre"});
		}
		if (type.equals("structure")) {
			actions.add(new String[] {"Tourner", "tourner"});
			actions.add(new String[] {"Retourner", "retourner"});
		}
		if (!type.equals("centre")) {
			actions.add(new String[] {"Retirer", "retirer"});
		}
		net.minecraft.network.chat.MutableComponent c = Component.literal(label + " : ").withStyle(ChatFormatting.GOLD);
		for (String[] a : actions) {
			String cmd = "/vc objet " + type + " " + index + " " + a[1];
			c.append(Component.literal("[" + a[0] + "] ").withStyle(st -> st.withClickEvent(new ClickEvent.RunCommand(cmd))
				.withColor(ChatFormatting.AQUA)));
		}
		p.sendSystemMessage(c);
	}

	/** Une action du menu d'un objet (repère ou structure posée). */
	public String objectAction(ServerPlayer p, String type, int index, String action) {
		if (type.equals("structure")) {
			if (editor == null) {
				throw new fr.valcombe.rules.GameException("L'éditeur de carte n'est pas prêt.");
			}
			return editor.actOnPlaced(p, index, action);
		}
		Village v = requireEditable();
		Village.Mark mark = markOf(type);
		boolean numbered = mark == Village.Mark.SEAT || mark == Village.Mark.HOUSE;
		if (numbered ? index < 0 || index >= (mark == Village.Mark.SEAT ? v.seatCount() : v.houseCount())
			: mark == Village.Mark.ROOM && v.convocation() == null) {
			throw new fr.valcombe.rules.GameException("Ce repère n'existe plus : visez-le de nouveau.");
		}
		Village.Hit h = new Village.Hit(mark, numbered ? index : 0);
		String who = p.getScoreboardName();
		switch (action) {
			case "deplacer" -> {
				carrying.put(who, h);
				handCarry.add(who);
				ValcombeUi.holdMapTool(p, ValcombeUi.MapTool.HAND);
				return h.label() + " en main : clic droit (outil « Carte : main ») là où le reposer.";
			}
			case "gauche", "droite", "centre" -> {
				if (mark != Village.Mark.SEAT) {
					throw new fr.valcombe.rules.GameException("Seuls les trônes se tournent.");
				}
				if (action.equals("centre")) {
					v.faceCenter(h.index());
				} else {
					v.turnSeat(h.index(), action.equals("droite") ? 1 : -1);
				}
				markersChanged();
				refreshThrones();
				return h.label() + " regarde vers le " + Village.compass(v.seatYaw(h.index()))
					+ (action.equals("centre") ? " (le centre de la place)." : ".");
			}
			case "retirer" -> {
				Village.Marks before = v.marks();
				BlockPos pos = v.markerPos(h);
				v.removeMarker(h);
				var built = prefabs.remove(pos);
				if (built != null) {
					built.forEach((b, s) -> p.level().setBlock(b, s, Clip.EXACT));
				}
				markersChanged(before);
				if (mark == Village.Mark.SEAT) {
					refreshThrones();
				}
				return h.label() + done(h, " retiré") + (built != null ? ", et sa construction avec." : ".");
			}
			default -> throw new fr.valcombe.rules.GameException("Action inconnue : " + action + ".");
		}
	}

	/** Trônes sculptés d'un village généré : reposés pour suivre les trônes déplacés ou tournés. */
	private void refreshThrones() {
		if (village != null && village.isGenerated() && server != null) {
			// distance=0.. : le monde de la partie seulement (un @e sans position vise tous les mondes, et des cartes
			// posées dans les autres mondes de Valcombe ont aussi leurs trônes sculptés, voir MapPlacer).
			run("kill @e[distance=0..,type=minecraft:item_display,tag=vc_trone]");
			village.placeThrones(level());
		}
	}

	/** Un constructeur clique sur un bloc avec un outil de carte ; renvoie ce qui s'est passé. */
	public String useTool(ServerPlayer p, ValcombeUi.MapTool tool, BlockPos clicked, boolean sneak) {
		if (tool == ValcombeUi.MapTool.STRUCTURE) {
			throw new IllegalArgumentException("Outil de l'éditeur : voir MapEditor.");
		}
		requireGameWorld(p);
		if (tool == ValcombeUi.MapTool.HAND) {
			return handClick(p, clicked);
		}
		handCarry.remove(p.getScoreboardName());
		Village v = requireEditable();
		Village.Marks before = v.marks();
		String who = p.getScoreboardName();
		BlockPos at = clicked.above();
		String msg;
		if (tool == ValcombeUi.MapTool.ERASE) {
			carrying.remove(who);
			Village.Hit h = v.markerAt(at, null, 1);
			if (h == null) {
				throw new fr.valcombe.rules.GameException("Aucun repère ici : cliquez sur le bloc sous une étiquette.");
			}
			BlockPos pos = v.markerPos(h);
			v.removeMarker(h);
			var built = prefabs.remove(pos);
			if (built != null) {
				// Pose exacte : la lanterne dont le poteau disparaît avant elle ne tombe pas au sol.
				built.forEach((b, s) -> p.level().setBlock(b, s, Clip.EXACT));
			}
			msg = h.label() + done(h, " retiré") + (built != null ? ", et sa construction avec." : ".")
				+ (h.type() == Village.Mark.SEAT || h.type() == Village.Mark.HOUSE ? " Les suivants reculent d'un numéro." : "");
		} else if (tool == ValcombeUi.MapTool.CENTER) {
			moveCenter(v, clicked);
			msg = "Centre de la place posé ici.";
		} else {
			Village.Mark type = markOf(tool);
			Village.Hit held = carrying.get(who);
			Village.Hit here = v.markerAt(at, type, 0);
			boolean moving = held != null && held.type() == type && (type == Village.Mark.ROOM
				|| held.index() < (type == Village.Mark.SEAT ? v.seatCount() : v.houseCount()));
			if (moving || here == null || sneak) {
				requireNear(v, at); // on pose ou on déplace un repère ici (prendre en main un repère lointain reste permis)
			}
			if (moving) {
				moveMarker(v, held, at);
				carrying.remove(who);
				msg = held.label() + done(held, " déplacé") + " ici.";
			} else if (here != null && !sneak) {
				carrying.put(who, here);
				return here.label() + " en main : cliquez là où le reposer. Pour le retirer, prenez la gomme.";
			} else if (type == Village.Mark.ROOM) {
				if (sneak) {
					prefabs.put(at, v.buildRoomAt(p.level(), at));
				}
				v.setConvocation(at);
				msg = sneak ? "Salle de convocation bâtie ici : le Conteur y recevra chaque joueur à l'écart."
					: "Salle de convocation posée ici : le joueur convoqué arrivera à cet endroit.";
			} else if (type == Village.Mark.SEAT) {
				requireRoom(v, true);
				v.addSeat(at);
				msg = "Trône " + v.seatCount() + " posé.";
			} else {
				requireRoom(v, false);
				if (sneak) {
					prefabs.put(at, v.buildHouseAt(p.level(), at));
				}
				v.addHouse(at);
				msg = "Maison " + v.houseCount() + (sneak ? " bâtie." : " posée.");
			}
		}
		markersChanged(before);
		return msg;
	}

	/** Depuis l'Atelier : ajoute, déplace ou retire un repère sur le plan (x, z relatifs au centre). */
	public String editMarker(String action, String type, int index, int dx, int dz) {
		Village v = requireEditable();
		Village.Marks before = v.marks();
		Village.Mark mark = switch (type) {
			case "trone", "siege" -> Village.Mark.SEAT;
			case "maison" -> Village.Mark.HOUSE;
			case "salle" -> Village.Mark.ROOM;
			case "centre" -> Village.Mark.CENTER;
			default -> throw new fr.valcombe.rules.GameException("Sorte de repère inconnue : " + type + ".");
		};
		if (Math.abs(dx) > Village.MAX_EXTENT || Math.abs(dz) > Village.MAX_EXTENT) {
			throw new fr.valcombe.rules.GameException("Ce point est trop loin de la place (" + (Village.MAX_EXTENT - 8)
				+ " blocs au plus).");
		}
		BlockPos at = v.groundAt(level(), v.center().getX() + dx, v.center().getZ() + dz);
		if (!action.equals("retirer") && mark != Village.Mark.CENTER) {
			requireNear(v, at);
		}
		Village.Hit h = new Village.Hit(mark, index);
		boolean numbered = mark == Village.Mark.SEAT || mark == Village.Mark.HOUSE;
		if (!action.equals("ajouter") && (numbered ? index < 0 || index >= (mark == Village.Mark.SEAT ? v.seatCount() : v.houseCount())
			: mark == Village.Mark.ROOM && v.convocation() == null)) {
			throw new fr.valcombe.rules.GameException("Ce repère n'existe plus : le plan vient d'être rechargé.");
		}
		String msg;
		switch (action) {
			case "ajouter" -> {
				if (mark == Village.Mark.SEAT) {
					return addSeatAt(at);
				}
				if (mark == Village.Mark.HOUSE) {
					return addHouseAt(at);
				}
				moveMarker(v, h, at);
				msg = mark.label + " posé" + (mark == Village.Mark.ROOM ? "e." : ".");
			}
			case "deplacer" -> {
				moveMarker(v, h, at);
				msg = h.label() + done(h, " déplacé") + ".";
			}
			case "retirer" -> {
				// Comme la gomme en jeu : la maison ou la salle bâtie d'un clic part avec son repère.
				var built = mark == Village.Mark.CENTER ? null : prefabs.remove(v.markerPos(h));
				v.removeMarker(h);
				if (built != null) {
					built.forEach((b, s) -> level().setBlock(b, s, Clip.EXACT));
				}
				msg = h.label() + done(h, " retiré") + (built != null ? ", et sa construction avec." : ".");
			}
			default -> throw new fr.valcombe.rules.GameException("Action inconnue : " + action + ".");
		}
		if (mark == Village.Mark.CENTER) {
		}
		markersChanged(before);
		return msg;
	}

	/** Depuis l'Atelier : bâtit d'un coup la maison ou la salle de convocation du repère choisi, comme l'outil tenu accroupi. */
	public String buildAtMarker(String type, int index) {
		Village v = requireEditable();
		boolean room = type.equals("salle");
		if (!room && !type.equals("maison")) {
			throw new fr.valcombe.rules.GameException("Seules une maison et la salle de convocation se bâtissent d'un coup.");
		}
		if (room ? v.convocation() == null : index < 0 || index >= v.houseCount()) {
			throw new fr.valcombe.rules.GameException("Ce repère n'existe plus : le plan vient d'être rechargé.");
		}
		Village.Hit h = new Village.Hit(room ? Village.Mark.ROOM : Village.Mark.HOUSE, room ? 0 : index);
		BlockPos at = v.markerPos(h);
		if (prefabs.containsKey(at)) {
			throw new fr.valcombe.rules.GameException(h.label() + " est déjà bâtie : la gomme la retire avec son repère.");
		}
		prefabs.put(at, room ? v.buildRoomAt(level(), at) : v.buildHouseAt(level(), at));
		markEdited();
		return h.label() + " bâtie. La gomme la retirera avec son repère.";
	}

	/** Depuis l'Atelier : amène l'opérateur sur un repère, pour voir dans le jeu ce que montre le plan. */
	public String visitMarker(ServerPlayer p, String type, int index) {
		Village v = requireVillage();
		var game = session.game();
		if (game != null && game.seatOf(p.getScoreboardName()) >= 0) {
			throw new fr.valcombe.rules.GameException("Vous jouez cette partie : on ne se déplace pas sur la carte en pleine partie.");
		}
		BlockPos at = switch (type) {
			case "trone", "siege" -> index >= 0 && index < v.seatCount() ? v.markerPos(new Village.Hit(Village.Mark.SEAT, index)) : null;
			case "maison" -> index >= 0 && index < v.houseCount() ? v.markerPos(new Village.Hit(Village.Mark.HOUSE, index)) : null;
			case "salle" -> v.convocation();
			default -> v.center().above();
		};
		if (at == null) {
			throw new fr.valcombe.rules.GameException("Ce repère n'existe plus.");
		}
		if (p.isPassenger()) {
			p.stopRiding();
		}
		moveTo(p, level(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), p.getYRot(), p.getXRot());
		return "Vous voilà sur le repère.";
	}

	private int labelGen;

	/** Étiquettes flottantes des repères, visibles à travers les murs tant que quelqu'un construit. */
	private void refreshLabels() {
		if (server == null) {
			return;
		}
		labelGen++;
		run("kill @e[distance=0..,type=minecraft:text_display,tag=vc_repere]");
		if (builders.isEmpty() || village == null) {
			return;
		}
		Village v = village;
		label(v.center().above(), "✚ Centre de la place", "aqua");
		for (int i = 0; i < v.seatCount(); i++) {
			label(v.markerPos(new Village.Hit(Village.Mark.SEAT, i)), "♛ Trône " + (i + 1), "gold");
		}
		for (int i = 0; i < v.houseCount(); i++) {
			label(v.markerPos(new Village.Hit(Village.Mark.HOUSE, i)), "⌂ Maison " + (i + 1), "green");
		}
		if (v.convocation() != null) {
			label(v.convocation(), "✉ Salle de convocation", "light_purple");
		}
	}

	private void label(BlockPos at, String text, String color) {
		run(String.format(java.util.Locale.ROOT,
			"summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"vc_repere\",\"vc_rep_%d\"],billboard:\"center\",see_through:1b,"
				+ "background:-1073741824,shadow:1b,view_range:1.5f,"
				+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],"
				+ "scale:[2.2f,2.2f,2.2f]},text:{text:\"%s\",color:\"%s\"}}",
			at.getX() + 0.5, at.getY() + 2.4, at.getZ() + 0.5, labelGen, text, color));
	}

	// ------------------------------------------------------------------
	// Salle de convocation : le Conteur reçoit un joueur à l'écart
	// ------------------------------------------------------------------
	/** Où se tenait le Conteur avant de convoquer : il y revient à la fin. */
	private SeatPos storytellerBack;

	private static float yawTo(Vec3 from, Vec3 to) {
		return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
	}

	@Override
	public boolean summon(String storyteller, String player) {
		ServerPlayer pp = player(player);
		ServerLevel level = levelIfReady();
		if (level == null || pp == null || village == null) {
			return false;
		}
		if (village.convocation() == null && village.isGenerated()) {
			village.buildConvocation(level); // village bâti avant cette version : on lui ajoute sa salle
			saveVillage(village);
		}
		BlockPos room = village.convocation();
		if (room == null) {
			return false;
		}
		BlockPos facing = Village.standNear(level, room);
		Vec3 a = new Vec3(room.getX() + 0.5, room.getY(), room.getZ() + 0.5);
		Vec3 b = new Vec3(facing.getX() + 0.5, facing.getY(), facing.getZ() + 0.5);
		if (pp.isPassenger()) {
			pp.stopRiding();
		}
		teleport(pp, new SeatPos(level, a, yawTo(a, b)));
		ServerPlayer sp = player(storyteller);
		if (sp != null && sp != pp) {
			if (storytellerBack == null) {
				storytellerBack = new SeatPos(sp.level(), sp.position(), sp.getYRot());
			}
			if (sp.isPassenger()) {
				sp.stopRiding();
			}
			teleport(sp, new SeatPos(level, b, yawTo(b, a)));
		}
		return true;
	}

	@Override
	public void dismiss(String storyteller, String player, boolean storytellerToo) {
		ServerPlayer pp = player == null ? null : player(player);
		SeatPos seat = player == null ? null : seats.get(player);
		if (pp != null && seat != null) {
			teleport(pp, seat);
			boolean enforced = sitEnforced;
			sit(List.of(player), enforced);
		}
		if (storytellerToo) {
			ServerPlayer sp = player(storyteller);
			if (sp != null && storytellerBack != null) {
				teleport(sp, storytellerBack);
			}
			storytellerBack = null;
		}
	}

	/**
	 * Vue de dessus de la carte actuelle (une image PNG, un point par bloc, le nord en haut) :
	 * l'Atelier la met sous son plan pour qu'on reconnaisse la place, les toits et les chemins.
	 */
	public byte[] topView() {
		return topView(requireVillage());
	}

	byte[] topView(Village v) {
		return topView(level(), v.center(), Math.min(200, v.extent()));
	}

	/** Vue de dessus du carré de rayon r autour de c dans ce monde (voir topView()) : la carte du monde de la partie, ou d'un autre monde. */
	static byte[] topView(ServerLevel level, BlockPos c, int r) {
		int n = 2 * r + 1;
		var img = new java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_INT_RGB);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int[] north = new int[n];
		for (int z = 0; z < n; z++) {
			for (int x = 0; x < n; x++) {
				int wx = c.getX() + x - r;
				int wz = c.getZ() + z - r;
				// La zone est chargée au besoin : sans joueur à proximité, elle ne l'est pas, et la vue sortirait noire.
				int y = level.getChunk(wx >> 4, wz >> 4).getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
				int rgb = 0;
				for (int k = 0; k < 12 && rgb == 0; k++, y--) {
					m.set(wx, y, wz);
					rgb = level.getBlockState(m).getMapColor(level, m).col;
				}
				y++;
				// Relief : plus clair quand la colonne est plus haute que sa voisine du nord, plus sombre sinon.
				double shade = z == 0 || y == north[x] ? 0.86 : y > north[x] ? 1.0 : 0.72;
				north[x] = y;
				int red = (int) ((rgb >> 16 & 255) * shade);
				int green = (int) ((rgb >> 8 & 255) * shade);
				int blue = (int) ((rgb & 255) * shade);
				img.setRGB(x, z, red << 16 | green << 8 | blue);
			}
		}
		try {
			var out = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(img, "png", out);
			return out.toByteArray();
		} catch (java.io.IOException e) {
			throw new fr.valcombe.rules.GameException("Vue de dessus impossible : " + e.getMessage());
		}
	}

	public String mapInfo() {
		Village v = requireVillage();
		return (v.libraryName().isEmpty() ? "" : "« " + v.libraryName() + " »" + (v.modified() ? " (retouchée depuis)" : "") + ", ")
			+ (v.isGenerated() ? "village généré" : "carte faite à la main") + ", place en " + v.center().toShortString()
			+ ", " + v.seatCount() + " trône(s), " + v.houseCount() + " maison(s), salle de convocation "
			+ (v.convocation() != null ? "posée." : "absente.");
	}

	/**
	 * La fiche de cette partie : {@code config/valcombe/parties/<monde>.json} (voir {@link GameSave#file}). Chaque partie a
	 * la sienne, la principale comme les parties d'essai : deux parties en cours ne s'écrasent jamais, et chacune reprend
	 * dans son monde au redémarrage (en cours, en pause ou en suspens). Null tant que la partie n'a pas de monde.
	 */
	public java.nio.file.Path saveFile() {
		if (server == null || worldId == null) {
			return null;
		}
		return GameSave.file(server.getServerDirectory().resolve("config"), worldId);
	}

	@Override
	public void persist(String json) {
		java.nio.file.Path f = saveFile();
		if (f == null) {
			return;
		}
		try {
			if (json.isEmpty()) {
				SafeFile.delete(f); // plus de partie à reprendre : la fiche et sa copie de secours s'en vont
			} else {
				// Écrite à côté puis mise en place d'un coup ; la précédente reste en « .bak » (voir SafeFile). Le monde où se
				// joue la partie y est noté : elle ne reprendra que là (voir GameSave).
				SafeFile.write(f, GameSave.withWorld(json, worldId));
			}
		} catch (java.io.IOException | RuntimeException e) {
			ValcombeMod.LOG.warn("Fiche de la partie « " + worldName() + " » non enregistrée", e);
		}
	}

	/**
	 * La partie laissée par un arrêt du serveur, à reprendre dans le monde de la partie, ou null. Une fiche illisible se
	 * relit dans sa copie de secours ; si aucune ne l'est, elle est mise de côté ({@code .illisible}). Une fiche sans monde
	 * (d'avant les mondes de Valcombe) est mise de côté ({@code .ancienne}) ; une fiche d'un autre monde reste sur le disque.
	 */
	public String savedGame() {
		java.nio.file.Path f = saveFile();
		if (f == null || !SafeFile.exists(f)) {
			return null;
		}
		String text = SafeFile.read(f, t -> GameSave.read(t, worldId).verdict() != GameSave.Verdict.UNREADABLE);
		if (text == null) {
			ValcombeMod.LOG.error("Fiche de la partie « {} » illisible, copie de secours comprise : elle ne reprend pas, et le "
				+ "fichier est mis de côté ({}.illisible).", worldName(), f.getFileName());
			setAside(f, ".illisible");
			return null;
		}
		GameSave.Read read = GameSave.read(text, worldId);
		switch (read.verdict()) {
			case OK -> {
				return read.json();
			}
			case NO_WORLD -> {
				ValcombeMod.LOG.warn("Partie enregistrée avant les mondes de Valcombe (dans le monde principal) : elle ne reprend "
					+ "pas, et le fichier est mis de côté ({}.ancienne).", f.getFileName());
				setAside(f, ".ancienne");
			}
			case OTHER_WORLD -> ValcombeMod.LOG.warn("Partie enregistrée dans le monde « {} », pas dans celui de la partie (« {} ») : "
				+ "elle ne reprend pas.", read.world(), worldId);
			case UNREADABLE -> ValcombeMod.LOG.error("Fiche de partie illisible ({}) : ignorée.", f);
		}
		return null;
	}

	/** Met une fiche de partie de côté (suffixe ajouté), sans rien effacer. */
	private static void setAside(java.nio.file.Path f, String suffix) {
		try {
			if (java.nio.file.Files.exists(f)) {
				java.nio.file.Files.move(f, f.resolveSibling(f.getFileName() + suffix), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			java.nio.file.Files.deleteIfExists(SafeFile.backup(f));
		} catch (java.io.IOException e) {
			ValcombeMod.LOG.warn("Fiche de partie non mise de côté ({})", f, e);
		}
	}

	public Village village() {
		return village;
	}

	// ------------------------------------------------------------------
	// Bibliothèque de cartes, générateur réglable, mode construction
	// ------------------------------------------------------------------
	private MapStore maps;

	public void setMaps(MapStore maps) {
		this.maps = maps;
	}

	/** La carte d'un scénario : envoyée à la partie (monde remis à neuf, puis la carte posée dessus, voir MapSender). */
	@Override
	public String loadMap(String id) {
		return sender().sendMap(id, worldId, null);
	}

	@Override
	public String mapInPlace() {
		// Pendant un chantier ou une remise en état, aucune carte n'est « en place » : elle est en train de changer.
		return village != null && !village.modified() && !busy() ? village.libraryId() : "";
	}

	/**
	 * Centre par défaut d'une carte : le centre du sol plat du monde de la partie (le bloc d'herbe), là où l'on arrive
	 * dans ce monde.
	 */
	public BlockPos defaultCenter() {
		return new BlockPos(0, WorldManager.SURFACE_Y, 0);
	}


	/** Chantiers en cours : les blocs sont posés par petits lots à chaque tick. */
	private final BuildQueue builds = new BuildQueue();

	/**
	 * Un chantier, une remise en état ou un envoi de carte est en cours, ou le monde de la partie n'est pas prêt (en
	 * cours de remise à neuf) : la partie ne peut pas commencer.
	 */
	@Override
	public boolean busy() {
		return building() || restoreAt >= 0 || sending != null || (server != null && levelIfReady() == null);
	}

	/**
	 * Un chantier est-il en cours dans le monde de la partie ? L'éditeur du mode construction travaille aussi ailleurs
	 * (un atelier) : ce qu'il y bâtit n'empêche ni de lancer la partie ni de lui envoyer une carte.
	 */
	private boolean building() {
		ServerLevel level = levelIfReady();
		return level != null ? builds.busy(level) : builds.busy();
	}

	/**
	 * Rien ne touche à la carte pendant un chantier ou un envoi, ni tant que la remise en état d'après partie n'est pas
	 * faite.
	 */
	void requireFree() {
		if (sending != null) {
			throw new fr.valcombe.rules.GameException("« " + worldName() + " » reçoit " + sending + " : attendez la fin de la barre.");
		}
		if (worldId != null && worlds != null && worlds.busy(worldId)) {
			throw new fr.valcombe.rules.GameException("Le monde « " + worldName() + " » est en cours de remise à neuf : "
				+ "réessayez dans un instant.");
		}
		if (building()) {
			throw new fr.valcombe.rules.GameException("Une construction est déjà en cours : attendez la fin de la barre de progression.");
		}
		if (restoreAt >= 0) {
			throw new fr.valcombe.rules.GameException("La carte va être remise en état après la partie : patientez quelques secondes "
				+ "(/vc arreter la lance tout de suite).");
		}
	}

	/** L'éditeur de carte du mode construction (sélection, structures, annulation). */
	private MapEditor editor;

	public void setEditor(MapEditor editor) {
		this.editor = editor;
	}

	/** Ce joueur est-il en mode construction ? */
	static boolean isBuilder(String name) {
		return builders.contains(name);
	}

	/**
	 * Une opération de l'éditeur, dans le monde où travaille l'opérateur : posée par petits lots comme un chantier,
	 * sans toucher aux panneaux de la salle. La barre suit les joueurs de ce monde et les opérateurs.
	 */
	void startEdit(String label, ServerLevel level, BuildQueue.Plan plan, Runnable done) {
		builds.add(label, level, plan, done, p -> p.level() == level || Atelier.isOperator(p), null);
	}

	/**
	 * Lance un chantier dans le monde de la partie ; done est appelé sur le fil du serveur quand tout est posé. La
	 * barre suit les joueurs de ce monde et les opérateurs (qui le lancent parfois depuis l'Atelier, d'ailleurs). Si le
	 * monde est remis à neuf avant la fin, le chantier est abandonné : il n'y a plus rien à poser.
	 */
	void startBuild(String label, BuildQueue.Plan plan, Runnable done) {
		ServerLevel level = level();
		WorldSetup.kill(level, "type=minecraft:text_display,tag=valcombe_salle");
		builds.add(label, level, plan, done, p -> p.level() == level || Atelier.isOperator(p), () -> {
			restoring = false;
			ValcombeMod.LOG.info("Chantier « {} » abandonné : le monde « {} » a quitté le serveur.", label, worldName());
		});
	}

	/**
	 * Envoi de carte en cours (ce qu'on envoie), noté par MapSender dans le dossier du monde de la partie : un arrêt du
	 * serveur en plein envoi se voit au redémarrage. Null sans monde.
	 */
	private java.nio.file.Path buildMarker() {
		return worldFile(MapSender.MARK);
	}

	/**
	 * Charge tout de suite les tronçons autour de c (r blocs) dans le monde de la partie, et les y tient un moment :
	 * on va y poser des entités (la scène) alors que les joueurs, qui arrivent d'un autre monde, ne les ont pas encore
	 * fait charger.
	 */
	private static void loadAround(ServerLevel level, BlockPos c, int r) {
		var at = net.minecraft.world.level.ChunkPos.containing(c);
		int n = Math.max(1, r / 16 + 1);
		level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.ENDER_PEARL, at, n);
		for (int dx = -n; dx <= n; dx++) {
			for (int dz = -n; dz <= n; dz++) {
				level.getChunk(at.x() + dx, at.z() + dz);
			}
		}
	}

	/**
	 * Ceux qui se trouvent dans l'emprise de la carte v (un chantier, une remise en état), dans le monde de la partie,
	 * sont ramenés au point d'arrivée du village.
	 */
	private void rescuePlayers(Village v) {
		ServerLevel level = levelIfReady();
		if (level == null) {
			return;
		}
		BlockPos spot = (village != null ? village : v).spawn();
		int reach = v.extent() + 6;
		for (ServerPlayer p : new ArrayList<>(level.players())) {
			if (builders.contains(p.getScoreboardName())) {
				continue; // un constructeur reste où il travaille
			}
			// Au hub ou dans un autre monde : rien à craindre du chantier du village (level.players() ne les compte pas).
			if (Math.abs(p.getX() - v.center().getX()) <= reach && Math.abs(p.getZ() - v.center().getZ()) <= reach) {
				if (p.isPassenger()) {
					p.stopRiding();
				}
				p.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, Set.of(), 180, 0, false);
			}
		}
	}

	// ------------------------------------------------------------------
	// Remise en état de la carte après une partie
	// ------------------------------------------------------------------
	/** Tick où la remise en état doit commencer ; -1 : rien en attente. */
	private long restoreAt = -1;
	/** La remise en état est en train de poser ses blocs. */
	private boolean restoring;

	/**
	 * L'instantané de la carte pris au lancement de la partie, dans le dossier du monde de la partie : il reste sur le
	 * disque tant que la carte n'est pas remise en état (et part avec le monde s'il est remis à neuf). Null sans monde.
	 */
	public java.nio.file.Path snapshotFile() {
		return worldFile("valcombe-instantane.nbt");
	}

	/** L'instantané attend-il sur le disque ? */
	private boolean hasSnapshot() {
		java.nio.file.Path f = snapshotFile();
		return f != null && java.nio.file.Files.exists(f);
	}

	private void dropSnapshot() {
		restoreAt = -1;
		java.nio.file.Path f = snapshotFile();
		if (f == null) {
			return;
		}
		try {
			java.nio.file.Files.deleteIfExists(f);
		} catch (java.io.IOException e) {
			ValcombeMod.LOG.warn("Instantané de la carte non supprimé", e);
		}
	}

	/** Une partie commence : on garde la carte telle qu'elle est, pour la rendre intacte à la fin. */
	@Override
	public void gameStarted() {
		if (levelIfReady() == null) {
			return;
		}
		dropSnapshot();
		if (editor != null && editor.worksIn(levelIfReady())) {
			editor.forgetHistory(); // après la partie, la carte est remise en état : plus rien à annuler
		}
		if (village == null) {
			return;
		}
		try {
			if (village.isGenerated() && village.convocation() == null) {
				// Village d'avant les salles de convocation : bâtie maintenant, elle fait partie de ce qu'on garde.
				village.buildConvocation(level());
				saveVillage(village);
			}
			long start = System.nanoTime();
			MapSnapshot.capture(level(), village.center(), village.extent()).write(snapshotFile());
			ValcombeMod.LOG.info("Instantané de la carte pris en {} ms ({} Ko)", (System.nanoTime() - start) / 1_000_000,
				java.nio.file.Files.size(snapshotFile()) / 1024);
		} catch (java.io.IOException | RuntimeException e) {
			ValcombeMod.LOG.warn("Instantané de la carte impossible : elle ne sera pas remise en état à la fin de la partie", e);
		}
	}

	/** La partie est finie : la carte retrouvera son état d'avant dans delayTicks (le temps de voir les rôles révélés). */
	@Override
	public void restoreMap(int delayTicks) {
		if (server == null || restoring || !hasSnapshot()) {
			return;
		}
		long at = tick + Math.max(1, delayTicks);
		if (restoreAt >= 0 && restoreAt <= at) {
			return;
		}
		boolean first = restoreAt < 0;
		restoreAt = at;
		if (first && delayTicks >= 100) {
			announceAll("La carte sera remise en état dans " + delayTicks / 20 + " secondes : chacun sera alors ramené "
				+ (village != null && village.hasHall() ? "à la salle d'attente." : "sur la place."));
		}
	}

	/** Remise en état coupée par le réglage : la carte reste comme la partie l'a laissée. */
	@Override
	public void keepMap() {
		if (server != null && !restoring) {
			dropSnapshot();
		}
	}

	/**
	 * Au démarrage : si le serveur s'est arrêté pendant une remise en état, ou avec une partie qui n'a pas
	 * pu reprendre, la carte n'est pas laissée à moitié refaite : la remise en état repart.
	 */
	public void resumeRestore() {
		if (server != null && session.game() == null && hasSnapshot()) {
			ValcombeMod.LOG.info("Carte laissée en l'état par l'arrêt du serveur : sa remise en état reprend");
			restoreAt = tick + 100;
		}
	}

	private void startRestore() {
		ServerLevel level = levelIfReady();
		java.nio.file.Path f = snapshotFile();
		if (level == null || f == null) {
			restoreAt = -1; // le monde de la partie est en cours de remise à neuf : l'instantané part avec lui
			return;
		}
		MapSnapshot snap;
		try {
			snap = MapSnapshot.read(f, level);
		} catch (java.io.IOException | RuntimeException e) {
			ValcombeMod.LOG.error("Instantané de la carte illisible : remise en état abandonnée", e);
			announceAll("La carte n'a pas pu être remise en état : l'instantané pris au début de la partie est illisible "
				+ "(ou vient d'un autre monde).");
			try {
				java.nio.file.Files.move(f, f.resolveSibling(f.getFileName() + ".illisible"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (java.io.IOException e2) {
				ValcombeMod.LOG.warn("Instantané illisible non écarté", e2);
			}
			return;
		}
		Village v = village;
		if (v == null || !v.center().equals(snap.center())) {
			dropSnapshot(); // la carte a changé depuis : il n'y a plus rien à remettre
			return;
		}
		// Ce que la partie a laissé derrière elle : scène, sièges libres, signes et bulles, objets au sol.
		stage.clear(level);
		loadAround(level, v.center(), Math.min(Village.MAX_EXTENT, v.extent()));
		List<net.minecraft.world.entity.Entity> gone = new ArrayList<>();
		for (var e : level.getAllEntities()) {
			for (String tag : e.entityTags()) {
				if (tag.equals("vc_chair") || tag.startsWith("vc_signe_") || tag.startsWith("vc_bulle_")) {
					gone.add(e);
					break;
				}
			}
		}
		gone.forEach(net.minecraft.world.entity.Entity::discard);
		snap.removeLoose(level);
		announceAll("Remise en état de la carte : chacun est ramené " + (v.hasHall() ? "à la salle d'attente" : "sur la place")
			+ " le temps que le village retrouve son état d'avant la partie.");
		rescuePlayers(v);
		restoring = true;
		BuildQueue.Plan plan = snap.restorePlan(level);
		// D'abord : même si la suite échouait, la remise en état ne doit pas rester « en cours » pour toujours.
		plan.after.add(0, () -> {
			restoring = false;
			dropSnapshot();
			lanterns = null;
		});
		startBuild("Remise en état de la carte", plan, () -> {
			// Un bloc a pu revenir là où quelqu'un se tenait.
			for (ServerPlayer p : new ArrayList<>(level.players())) {
				if (!p.isSpectator() && !level.noCollision(p)) {
					BlockPos s = v.spawn();
					p.teleportTo(level, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, Set.of(), 180, 0, false);
				}
			}
			announceAll("La carte est remise en état.");
		});
	}

	/** Adopte une carte (envoyée à la partie, voir MapSender) : enregistrement, salle d'attente, apparition. */
	public void useVillage(Village v) {
		stage.clear(levelIfReady());
		village = v;
		startupWarning = null;
		lanterns = null;
		saveVillage(v);
		if (v.hasHall()) {
			buildHall();
		}
		rescuePlayers(v);
		carrying.clear();
		prefabs.clear();
		markerUndo.clear();
		if (editor != null && editor.worksIn(levelIfReady())) {
			editor.forgetHistory();
		}
		refreshLabels();
	}

	/** Fin des messages qui lancent un chantier : valable dans le jeu comme sur la page de l'Atelier. */
	public static final String PROGRESS =" Dans le jeu, une barre en haut de l'écran suit le chantier.";

	/** Le village généré v vient d'être bâti : sa salle de convocation, sa salle d'attente, et il devient la carte. */
	private void adoptGenerated(Village v) {
		v.buildConvocation(level());
		useVillage(v);
		if (!v.hasHall()) {
			buildHall();
		}
	}

	public String setMapCenter(ServerPlayer p) {
		requireGameWorld(p);
		Village v = requireEditable();
		Village.Marks before = v.marks();
		moveCenter(v, p.blockPosition().below());
		markersChanged(before);
		return "Centre de la place posé ici.";
	}

	/**
	 * Les opérateurs en mode construction, et leur mode de jeu d'avant. C'est un état du joueur, pas d'une partie : il
	 * le garde d'un monde à l'autre (l'éditeur travaille là où il se tient), et toutes les parties le voient pareil.
	 */
	private static final java.util.Set<String> builders = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final Map<String, GameType> builderModes = new java.util.concurrent.ConcurrentHashMap<>();

	/** Mode construction : créatif, baguette de carte, repères lumineux et liste de contrôle. */
	public String toggleConstruction(ServerPlayer p) {
		String name = p.getScoreboardName();
		if (builders.remove(name)) {
			GameType mode = builderModes.remove(name);
			p.setGameMode(mode != null ? mode : GameType.ADVENTURE);
			p.sendSystemMessage(Component.literal("").withStyle(ChatFormatting.GOLD), true);
			ValcombeUi.mapTools(p, false);
			carrying.remove(name);
			if (editor != null) {
				editor.forget(name);
			}
			refreshLabels();
			return "Mode construction terminé. Pensez à enregistrer la carte : bouton « Enregistrer » de l'Atelier, "
				+ "ou /vc carte enregistrer <identifiant> <nom>.";
		}
		// Le mode construction sert dans n'importe quel monde (l'éditeur travaille là où se tient l'opérateur) ; les
		// repères, eux, ne se posent que dans le monde de la partie. Pendant une partie, on ne bâtit pas dans son monde.
		if (session.game() != null && inGameWorld(p)) {
			throw new fr.valcombe.rules.GameException("Pas de construction pendant une partie.");
		}
		builders.add(name);
		if (village != null && !village.modified() && inGameWorld(p)) {
			// On entre ici pour bâtir : la carte est désormais tenue pour retouchée, elle ne sera pas remplacée sans sauvegarde.
			village.setModified(true);
			saveVillage(village);
		}
		builderModes.put(name, p.gameMode.getGameModeForPlayer());
		p.setGameMode(GameType.CREATIVE);
		ValcombeUi.mapTools(p, true);
		refreshLabels();
		return "Mode construction : construisez librement. Cinq outils de repères (trône, maison, centre, salle de convocation, "
			+ "gomme) : clic droit pour poser, ou pour prendre un repère et le déplacer ; accroupi, maison et salle sont bâties "
			+ "d'un coup. Outil de structure : il pose la structure choisie dans le catalogue. L'Atelier en jeu (/vc atelier jeu) "
			+ "réunit le catalogue et l'annulation (/vc annuler). Sélectionner, remplir, copier, coller : WorldEdit, dans les "
			+ "ateliers.";
	}

	private void showMarkers() {
		if (editor != null && mainGame) {
			// L'éditeur sert aussi sans carte : sa ligne d'état (sélection, structure en main) passe d'abord. Il est commun à
			// toutes les parties : la partie principale seule la donne, une fois, à chaque constructeur où qu'il soit.
			for (String name : builders) {
				ServerPlayer p = player(name);
				String status = p == null ? null : editor.status(p);
				if (status != null) {
					p.sendSystemMessage(Component.literal(status).withStyle(ChatFormatting.AQUA), true);
				}
			}
		}
		if (builders.isEmpty() || village == null) {
			return;
		}
		for (String name : builders) {
			ServerPlayer p = player(name);
			if (p == null || !inGameWorld(p)) {
				continue; // les repères sont ceux du village du monde de la partie
			}
			ServerLevel level = p.level();
			BlockPos c = village.center();
			// Colonnes lumineuses, visibles de loin : bleue au centre, dorée sur les trônes, verte dans les maisons.
			for (int k = 0; k < 8; k++) {
				level.sendParticles(p, net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME, true, true,
					c.getX() + 0.5, c.getY() + 1.5 + k * 0.6, c.getZ() + 0.5, 2, 0.05, 0.1, 0.05, 0);
			}
			for (int i = 0; i < village.seatCount(); i++) {
				Vec3 f = village.seatFeet(i);
				for (int k = 0; k < 5; k++) {
					level.sendParticles(p, net.minecraft.core.particles.ParticleTypes.END_ROD, true, true,
						f.x, f.y + 1.2 + k * 0.6, f.z, 1, 0.02, 0.05, 0.02, 0);
				}
			}
			for (Vec3 h : village.houseFeet()) {
				for (int k = 0; k < 5; k++) {
					level.sendParticles(p, net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, true, true,
						h.x, h.y + 1.2 + k * 0.6, h.z, 2, 0.1, 0.1, 0.1, 0);
				}
			}
			int seats = village.seatCount();
			int houses = village.houseCount();
			// Près d'un trône ou d'une maison : un trait de lumière le relie à celui qui porte le même numéro.
			String pair = "";
			Village.Hit near = village.markerAt(p.blockPosition(), null, 3);
			Village.Hit held = carrying.get(name);
			if (held != null) {
				pair = held.label() + " en main : cliquez là où le reposer · ";
			} else if (near != null && near.type() != Village.Mark.ROOM) {
				boolean seat = near.type() == Village.Mark.SEAT;
				if (near.index() < (seat ? houses : seats)) {
					Vec3 a = village.seatFeet(near.index());
					Vec3 b = village.houseFeet().get(near.index());
					double len = Math.max(0.1, a.distanceTo(b));
					for (double t = 0; t <= len; t += 0.8) {
						Vec3 q = a.lerp(b, t / len);
						level.sendParticles(p, net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, true, true,
							q.x, q.y + 1.1, q.z, 1, 0, 0, 0, 0);
					}
					pair = "Trône " + (near.index() + 1) + " ↔ Maison " + (near.index() + 1) + " · ";
				} else {
					pair = near.label() + (seat ? " : pas de maison à ce numéro · " : " : pas de trône à ce numéro · ");
				}
			}
			String check = pair + seats + " trône(s) " + (seats >= 5 ? "✔" : "(5 au moins)") + " · " + houses
				+ " maison(s)" + (houses == 0 || houses >= seats ? "" : " (moins que de trônes)")
				+ " · salle de convocation " + (village.convocation() != null ? "✔" : "absente");
			if (editor != null && editor.status(p) != null) {
				continue; // un outil de l'éditeur en main : sa ligne d'état remplace la liste de contrôle
			}
			p.sendSystemMessage(Component.literal(check).withStyle(seats >= 5 ? ChatFormatting.GREEN : ChatFormatting.GOLD), true);
		}
	}

	/** Message à tous les joueurs du monde de cette partie (la remise en état de sa carte, par exemple). */
	void announceAll(String text) {
		for (ServerPlayer p : here()) {
			p.sendSystemMessage(Component.literal("[Valcombe] " + text).withStyle(ChatFormatting.GOLD));
		}
	}

	public void setUi(ValcombeUi ui) {
		this.ui = ui;
	}

	public ServerPlayer player(String name) {
		return server == null ? null : server.getPlayerList().getPlayerByName(name);
	}

	/**
	 * Les joueurs qui se trouvent dans le monde de cette partie : les seuls à voir et entendre ce qu'elle annonce
	 * (messages à tous, titres, sons, barres). Ailleurs (hub, atelier, autre partie), on n'en reçoit rien.
	 */
	public List<ServerPlayer> here() {
		ServerLevel level = levelIfReady();
		return level == null ? List.of() : List.copyOf(level.players());
	}

	/** Ce joueur, s'il est connecté et dans le monde de cette partie ; sinon null (il ne reçoit ni titre, ni son, ni barre). */
	private ServerPlayer present(String name) {
		ServerPlayer p = player(name);
		return p != null && inGameWorld(p) ? p : null;
	}

	// ------------------------------------------------------------------
	// Messages
	// ------------------------------------------------------------------
	private static final Component PREFIX = Component.literal("[Valcombe] ").withStyle(ChatFormatting.DARK_RED);

	private static ChatFormatting color(String name) {
		if (name == null) {
			return ChatFormatting.WHITE;
		}
		return switch (name) {
			case "gold" -> ChatFormatting.GOLD;
			case "aqua" -> ChatFormatting.AQUA;
			case "red" -> ChatFormatting.RED;
			case "light_purple" -> ChatFormatting.LIGHT_PURPLE;
			default -> ChatFormatting.WHITE;
		};
	}

	static Component toComponent(Msg msg) {
		MutableComponent out = Component.empty().append(PREFIX);
		for (Msg.Part p : msg.parts) {
			MutableComponent c = Component.literal(p.text()).withStyle(color(p.color()));
			if (p.command() != null) {
				ClickEvent click = p.suggest()
					? new ClickEvent.SuggestCommand(p.command())
					: new ClickEvent.RunCommand(p.command());
				c = c.withStyle(s -> s.withClickEvent(click).withBold(true));
				if (p.hover() != null) {
					HoverEvent hover = new HoverEvent.ShowText(Component.literal(p.hover()));
					c = c.withStyle(s -> s.withHoverEvent(hover));
				}
			}
			out.append(c);
		}
		return out;
	}

	/** Annonce de la partie : aux joueurs de son monde seulement (une autre partie, le hub et les ateliers n'en savent rien). */
	@Override
	public void announce(String text) {
		if (server == null) {
			return;
		}
		Component line = Component.empty().append(PREFIX).append(Component.literal(text).withStyle(ChatFormatting.GOLD));
		for (ServerPlayer p : here()) {
			p.sendSystemMessage(line);
		}
		// Le journal du serveur garde la trace de toutes les parties, avec le nom de la partie.
		server.sendSystemMessage(Component.literal("[" + worldName() + "] ").append(line));
	}

	@Override
	public void tell(String name, Msg msg) {
		ServerPlayer p = player(name);
		if (p != null) {
			p.sendSystemMessage(toComponent(msg));
		}
	}

	@Override
	public boolean online(String name) {
		return player(name) != null;
	}

	@Override
	public void title(List<String> players, String title, String subtitle) {
		// Le grand titre de Minecraft est énorme : seuls les titres courts (« Jour 2 », « 3 voix »)
		// y vont ; une phrase passe en sous-titre doré, plus discret, avec sa précision à la suite.
		boolean big = title.length() <= 12;
		Component top = big ? Component.literal(title).withStyle(ChatFormatting.GOLD) : Component.empty();
		Component sub = big ? Component.literal(subtitle).withStyle(ChatFormatting.GRAY)
			: Component.literal(title).withStyle(ChatFormatting.GOLD)
				.append(subtitle.isEmpty() ? Component.empty() : Component.literal(" : " + subtitle).withStyle(ChatFormatting.GRAY));
		for (String name : players) {
			ServerPlayer p = present(name);
			if (p != null) {
				p.connection.send(new ClientboundSetTitlesAnimationPacket(8, 40, 12));
				p.connection.send(new ClientboundSetSubtitleTextPacket(sub));
				p.connection.send(new ClientboundSetTitleTextPacket(top));
			}
		}
	}

	@Override
	public void sound(List<String> players, String sound) {
		// Sons originaux du mod (assets/valcombe/sounds) ; un client sans le mod n'entend rien.
		String id = switch (sound) {
			case "death" -> "glas";
			case "clock" -> "tic";
			default -> "cloche";
		};
		Holder<SoundEvent> event = Holder.direct(SoundEvent.createVariableRangeEvent(
			net.minecraft.resources.Identifier.fromNamespaceAndPath("valcombe", id)));
		float pitch = 1f;
		for (String name : players) {
			ServerPlayer p = present(name);
			if (p != null) {
				p.connection.send(new ClientboundSoundPacket(event, SoundSource.MASTER, p.getX(), p.getY(), p.getZ(),
					1f, pitch, p.level().getRandom().nextLong()));
			}
		}
	}

	@Override
	public void seatBoards(Map<String, String> labels) {
		if (server == null) {
			return;
		}
		if (labels.isEmpty()) {
			stage.clear(levelIfReady());
			return;
		}
		stage.labels(labels);
	}

	@Override
	public void seatTokens(Map<String, String> models, boolean flip) {
		stage.tokens(models, flip);
	}

	@Override
	public void voteScene(fr.valcombe.session.VoteScene scene) {
		stage.setFullEffects(session.settings().fullEffects);
		stage.vote(scene);
	}

	@Override
	public void sit(List<String> players, boolean enforce) {
		sitEnforced = enforce;
		if (server == null || !stage.active()) {
			return;
		}
		for (String name : players) {
			ServerPlayer p = player(name);
			int i = stage.index(name);
			if (p != null && i >= 0 && !p.isPassenger() && inGameWorld(p)) {
				run("ride " + name + " mount @e[distance=0..,type=minecraft:item_display,tag=vc_seat_" + i + ",limit=1]");
			}
		}
	}

	// ------------------------------------------------------------------
	// Minuteur, nuit brumeuse, chuchotements, accusations, titres, spectateurs
	// ------------------------------------------------------------------
	private net.minecraft.server.level.ServerBossEvent timerBar;
	private int timerToken;
	private net.minecraft.server.level.ServerBossEvent nightBar;

	@Override
	public void timer(List<String> players, String title, int seconds) {
		int token = ++timerToken;
		if (timerBar != null) {
			timerBar.removeAllPlayers();
			timerBar = null;
		}
		if (seconds <= 0 || server == null) {
			return;
		}
		var bar = new net.minecraft.server.level.ServerBossEvent(java.util.UUID.randomUUID(), Component.literal(title),
			net.minecraft.world.BossEvent.BossBarColor.YELLOW, net.minecraft.world.BossEvent.BossBarOverlay.NOTCHED_10);
		for (String name : players) {
			ServerPlayer p = present(name);
			if (p != null) {
				bar.addPlayer(p);
			}
		}
		timerBar = bar;
		tickTimer(token, bar, title, seconds, seconds);
	}

	private void tickTimer(int token, net.minecraft.server.level.ServerBossEvent bar, String title, int left, int total) {
		if (token != timerToken) {
			return;
		}
		bar.setProgress(Math.max(0f, left / (float) total));
		bar.setName(Component.literal(title + " : " + left / 60 + ":" + String.format("%02d", left % 60)));
		if (left > 0) {
			schedule(20, () -> tickTimer(token, bar, title, left - 1, total));
		}
	}

	/** La nuit : brume et ciel assombri pour tous (barre de nuit), si les effets complets sont activés. */
	private void nightFog(List<String> players, boolean night, boolean full) {
		if (nightBar != null) {
			nightBar.removeAllPlayers();
			nightBar = null;
		}
		if (!night || !full || server == null) {
			return;
		}
		nightBar = new net.minecraft.server.level.ServerBossEvent(java.util.UUID.randomUUID(),
			Component.literal("La nuit est tombée sur Valcombe"),
			net.minecraft.world.BossEvent.BossBarColor.PURPLE, net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
		nightBar.setProgress(1f);
		nightBar.setCreateWorldFog(true);
		nightBar.setDarkenScreen(true);
		for (String name : players) {
			ServerPlayer p = present(name);
			if (p != null) {
				nightBar.addPlayer(p);
			}
		}
	}

	/**
	 * Les barres de la partie (minuteur, nuit) ne suivent pas un joueur hors de son monde : qui part au hub ou dans
	 * une autre partie les perd aussitôt (appelé deux fois par seconde).
	 */
	private void pruneBars() {
		for (var bar : new net.minecraft.server.level.ServerBossEvent[] {timerBar, nightBar}) {
			if (bar == null) {
				continue;
			}
			for (ServerPlayer p : List.copyOf(bar.getPlayers())) {
				if (!inGameWorld(p) || p.isRemoved()) {
					bar.removePlayer(p);
				}
			}
		}
	}

	/** Le minuteur affiché en ce moment par cette partie, ou null (pour les tests en jeu). */
	public String timerTitle() {
		return timerBar == null ? null : timerBar.getName().getString();
	}

	/** Les joueurs qui voient en ce moment une barre de cette partie (minuteur, nuit), pour les tests en jeu. */
	public java.util.Set<String> barViewers() {
		java.util.Set<String> out = new java.util.TreeSet<>();
		for (var bar : new net.minecraft.server.level.ServerBossEvent[] {timerBar, nightBar}) {
			if (bar != null) {
				bar.getPlayers().forEach(p -> out.add(p.getScoreboardName()));
			}
		}
		return out;
	}

	@Override
	public void sign(String player, String symbol, String color) {
		ServerPlayer p = player(player);
		if (!inGameWorld(p)) {
			return; // hors du monde de la partie, il n'est pas à la table : personne ne verrait son signe
		}
		String tag = "vc_signe_" + Math.abs(player.hashCode());
		run("kill @e[distance=0..,type=minecraft:text_display,tag=" + tag + "]");
		run(String.format(java.util.Locale.ROOT,
			"summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"%s\"],billboard:\"center\",background:0,"
				+ "see_through:0b,shadow:1b,transformation:{translation:[0f,0.7f,0f],left_rotation:[0f,0f,0f,1f],"
				+ "right_rotation:[0f,0f,0f,1f],scale:[2.6f,2.6f,2.6f]},text:{text:\"%s\",color:\"%s\",bold:1b}}",
			p.getX(), p.getY() + 2.1, p.getZ(), tag, symbol, color));
		run("ride @e[distance=0..,type=minecraft:text_display,tag=" + tag + ",limit=1] mount " + player);
		run(String.format(java.util.Locale.ROOT, "playsound minecraft:block.note_block.hat master @a[distance=0..] %.1f %.1f %.1f 0.6 1.6",
			p.getX(), p.getY(), p.getZ()));
		int token = ++signToken;
		signTokens.put(player, token);
		schedule(80, () -> {
			if (signTokens.getOrDefault(player, 0) == token) {
				run("kill @e[distance=0..,type=minecraft:text_display,tag=" + tag + "]");
			}
		});
	}

	private int signToken;
	private final Map<String, Integer> signTokens = new HashMap<>();

	@Override
	public void whisperBubble(String from, String to) {
		if (server == null) {
			return;
		}
		for (String name : List.of(from, to)) {
			ServerPlayer p = player(name);
			if (!inGameWorld(p)) {
				continue;
			}
			String tag = "vc_bulle_" + Math.abs(name.hashCode());
			run("kill @e[distance=0..,type=minecraft:text_display,tag=" + tag + "]");
			run(String.format(java.util.Locale.ROOT,
				"summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"%s\"],billboard:\"center\",background:-1073741824,"
					+ "transformation:{translation:[0f,0.55f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
					+ "scale:[1.4f,1.4f,1.4f]},text:{text:\"…\",color:\"light_purple\"}}",
				p.getX(), p.getY() + 2.1, p.getZ(), tag));
			run("ride @e[distance=0..,type=minecraft:text_display,tag=" + tag + ",limit=1] mount " + name);
			schedule(160, () -> run("kill @e[distance=0..,type=minecraft:text_display,tag=" + tag + "]"));
		}
	}

	@Override
	public void accusation(String from, String to) {
		ServerPlayer a = player(from);
		ServerPlayer b = player(to);
		if (!inGameWorld(a) || !inGameWorld(b)) {
			return;
		}
		for (int k = 0; k < 4; k++) {
			schedule(1 + k * 10, () -> {
				Vec3 s = a.position().add(0, 1.4, 0);
				Vec3 e = b.position().add(0, 1.4, 0);
				int steps = (int) Math.max(4, s.distanceTo(e) * 3);
				for (int i = 0; i <= steps; i++) {
					Vec3 q = s.lerp(e, i / (double) steps);
					a.level().sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, q.x, q.y, q.z, 1, 0, 0, 0, 0);
				}
			});
		}
		run(String.format(java.util.Locale.ROOT, "playsound valcombe:cloche master @a[distance=0..] %.1f %.1f %.1f 1.5 1.4",
			b.getX(), b.getY(), b.getZ()));
	}

	@Override
	public String playerTitle(String name) {
		return community == null ? "" : community.title(name);
	}

	@Override
	public void spectator(String name, boolean on) {
		ServerPlayer p = player(name);
		if (p == null) {
			return;
		}
		if (on) {
			previousMode.putIfAbsent(name, p.gameMode.getGameModeForPlayer());
			p.setGameMode(GameType.SPECTATOR);
		} else {
			GameType mode = previousMode.remove(name);
			p.setGameMode(mode != null ? mode : GameType.ADVENTURE);
		}
	}

	private void standUp(List<String> players) {
		sitEnforced = false;
		for (String name : players) {
			ServerPlayer p = player(name);
			if (p != null && p.isPassenger()) {
				p.stopRiding();
			}
		}
	}

	@Override
	public void execution(String name) {
		ServerPlayer p = player(name);
		if (p == null) {
			return;
		}
		if (!inGameWorld(p)) {
			stage.execution(name); // le gibet se dresse quand même sur la place
			return;
		}
		String at = String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f", p.getX(), p.getY() + 1, p.getZ());
		run("particle minecraft:large_smoke " + at + " 0.4 1 0.4 0.02 60 force");
		run("particle minecraft:soul_fire_flame " + at + " 0.3 0.8 0.3 0.03 50 force");
		run("playsound minecraft:entity.wither.death master @a[distance=0..] " + at + " 0.6 0.7");
		stage.execution(name);
	}

	@Override
	public void celebrate(List<String> players) {
		if (server == null || village == null) {
			return;
		}
		BlockPos c = village.center();
		for (int i = 0; i < 6; i++) {
			int k = i;
			schedule(10 * i, () -> {
				String at = String.format(java.util.Locale.ROOT, "%d %d %d", c.getX() + (k % 3 - 1) * 4, c.getY() + 8, c.getZ() + (k / 3) * 4 - 2);
				run("particle minecraft:firework " + at + " 1.5 1.5 1.5 0.15 120 force");
				run("playsound minecraft:entity.firework_rocket.twinkle master @a[distance=0..] " + at + " 2 1");
			});
		}
	}

	@Override
	public void gather(List<String> players) {
		for (String name : players) {
			ServerPlayer p = player(name);
			SeatPos s = seats.get(name);
			if (p != null && s != null && !p.isPassenger()) {
				teleport(p, s);
			}
		}
	}

	/**
	 * Fantômes : nom grisé précédé d'une croix ; une main s'ils ont encore leur vote. Seulement pour les joueurs qui sont
	 * dans le monde de la partie : un joueur au hub (partie en suspens) n'y porte ni l'apparence ni le nom de la partie.
	 */
	@Override
	public void deadPlayers(List<String> dead, List<String> withGhostVote) {
		if (server == null) {
			return;
		}
		var board = server.getScoreboard();
		ghostNames.clear();
		ghostNames.addAll(dead);
		for (String name : seats.keySet()) {
			ServerPlayer p = player(name);
			if (p == null || away(name)) {
				continue;
			}
			// Les morts deviennent translucides (le mod client les garde visibles, en transparence).
			if (dead.contains(name)) {
				p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false));
			} else {
				p.removeEffect(MobEffects.INVISIBILITY);
			}
		}
		String hat = session.settings().deadHat;
		for (String name : seats.keySet()) {
			ServerPlayer p = player(name);
			if (p != null && !away(name)) {
				String model = !dead.contains(name) || "aucun".equals(hat) ? null
					: ("crane".equals(hat) ? "crane_mort" : "chapeau_mort") + (withGhostVote.contains(name) ? "_vote" : "");
				wearHat(p, model);
			}
		}
		String aliveId = teamName(GameTeams.Kind.VIVANTS);
		String hiddenId = teamName(GameTeams.Kind.CACHE);
		PlayerTeam ghosts = dead.isEmpty() ? null : team(board, teamName(GameTeams.Kind.FANTOMES), "† ", "");
		PlayerTeam voters = withGhostVote.isEmpty() ? null : team(board, teamName(GameTeams.Kind.FANTOMES_VOTE), "† ", " ✋");
		for (String name : seats.keySet()) {
			if (away(name)) {
				continue; // hors du monde de la partie : il n'en porte aucune équipe (voir pruneTeams)
			}
			PlayerTeam current = board.getPlayersTeam(name);
			// Un vivant garde l'équipe des vivants de la partie (noms cachés) ou celle du recoin, sinon aucune.
			PlayerTeam wanted = !dead.contains(name)
				? (current != null && (current.getName().equals(aliveId) || current.getName().equals(hiddenId)) ? current : null)
				: withGhostVote.contains(name) ? voters : ghosts;
			if (current != wanted) {
				if (current != null && GameTeams.ours(current.getName())) {
					board.removePlayerFromTeam(name, current);
				}
				if (wanted != null) {
					board.addPlayerToTeam(name, wanted);
				}
			}
		}
	}

	/** Coiffe le joueur (model) ou la lui retire (null) ; les coiffes égarées dans l'inventaire disparaissent. */
	private static void wearHat(ServerPlayer p, String model) {
		var slot = net.minecraft.world.entity.EquipmentSlot.HEAD;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (ValcombeUi.isDeadHat(inv.getItem(i))) {
				inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
			}
		}
		var current = p.getItemBySlot(slot);
		var id = current.get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
		boolean ours = ValcombeUi.isDeadHat(current);
		if (model == null) {
			if (ours) {
				p.setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
			}
			return;
		}
		if (!ours || id == null || !id.getPath().equals(model)) {
			if (!current.isEmpty() && !ours) {
				inv.add(current.copy()); // un casque à soi est rangé, pas perdu
			}
			p.setItemSlot(slot, ValcombeUi.deadHat(model));
		}
	}

	// ------------------------------------------------------------------
	// Équipes du tableau des scores : les noms au-dessus des têtes, propres à cette partie (voir GameTeams)
	// ------------------------------------------------------------------
	/** Le préfixe court des équipes de cette partie, donné par Games à son ouverture (unique parmi les parties). */
	private String teamKey = "pp";

	public void setTeamKey(String key) {
		this.teamKey = key;
	}

	public String teamKey() {
		return teamKey;
	}

	/** Le nom de l'équipe {@code kind} de cette partie. */
	public String teamName(GameTeams.Kind kind) {
		return GameTeams.name(teamKey, kind);
	}

	/** Ce joueur est connecté, mais hors du monde de la partie (au hub, dans un atelier, dans une autre partie). */
	private boolean away(String name) {
		ServerPlayer p = player(name);
		return p != null && !inGameWorld(p);
	}

	@Override
	public void nameTags(boolean visible) {
		if (server == null) {
			return;
		}
		var board = server.getScoreboard();
		var mode = visible ? net.minecraft.world.scores.Team.Visibility.ALWAYS : net.minecraft.world.scores.Team.Visibility.NEVER;
		String aliveId = teamName(GameTeams.Kind.VIVANTS);
		PlayerTeam alive = board.getPlayerTeam(aliveId);
		if (alive == null && !visible) {
			alive = board.addPlayerTeam(aliveId);
		}
		for (String id : List.of(aliveId, teamName(GameTeams.Kind.FANTOMES), teamName(GameTeams.Kind.FANTOMES_VOTE))) {
			PlayerTeam t = board.getPlayerTeam(id);
			if (t != null && t.getNameTagVisibility() != mode) {
				t.setNameTagVisibility(mode);
			}
		}
		if (alive == null) {
			return; // noms visibles, et personne n'est rangé chez les vivants : rien d'autre à faire
		}
		// Les vivants n'ont pas d'équipe à eux : on les range dans celle-ci tant que les noms sont cachés.
		for (String name : seats.keySet()) {
			if (away(name)) {
				continue;
			}
			PlayerTeam current = board.getPlayersTeam(name);
			if (!visible && current == null) {
				board.addPlayerToTeam(name, alive);
			} else if (visible && current == alive) {
				board.removePlayerFromTeam(name, alive);
			}
		}
	}

	/**
	 * Les noms des joueurs de cette partie qui sont dans un recoin ne se voient pas depuis la place (appelé par le vocal,
	 * quatre fois par seconde). hidden : les joueurs du monde de la partie qui sont dans un recoin.
	 */
	public void hideNames(Set<String> hidden) {
		if (server == null) {
			return;
		}
		var board = server.getScoreboard();
		String cacheId = teamName(GameTeams.Kind.CACHE);
		PlayerTeam cache = board.getPlayerTeam(cacheId);
		if (cache == null) {
			if (hidden.isEmpty()) {
				return;
			}
			cache = board.addPlayerTeam(cacheId);
			cache.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.NEVER);
		}
		PlayerTeam alive = board.getPlayerTeam(teamName(GameTeams.Kind.VIVANTS));
		Set<String> names = new java.util.HashSet<>(hidden);
		names.addAll(cache.getPlayers());
		for (String name : names) {
			PlayerTeam current = board.getPlayersTeam(name);
			if (hidden.contains(name)) {
				if (current == null || current == alive) {
					board.addPlayerToTeam(name, cache);
				}
			} else if (current == cache) {
				board.removePlayerFromTeam(name, cache);
				// Noms cachés pour tous en ce moment (nuit, réglage du Conteur) : il retrouve l'équipe des vivants.
				if (alive != null && alive.getNameTagVisibility() == net.minecraft.world.scores.Team.Visibility.NEVER
					&& seats.containsKey(name) && !away(name)) {
					board.addPlayerToTeam(name, alive);
				}
			}
		}
	}

	/**
	 * Qui quitte le monde de la partie (au hub, ailleurs) quitte ses équipes : il n'y porte plus le nom de la partie (pas de
	 * croix, pas de nom caché). Un joueur déconnecté les garde : il retrouve sa place en revenant.
	 */
	private void pruneTeams() {
		var board = server.getScoreboard();
		for (String id : GameTeams.names(teamKey)) {
			PlayerTeam t = board.getPlayerTeam(id);
			if (t == null) {
				continue;
			}
			for (String name : List.copyOf(t.getPlayers())) {
				if (away(name)) {
					board.removePlayerFromTeam(name, t);
				}
			}
		}
	}

	/** Retire les équipes de cette partie du tableau des scores (fin de partie, mise en suspens, partie fermée). */
	public void clearTeams() {
		if (server == null) {
			return;
		}
		var board = server.getScoreboard();
		for (String id : GameTeams.names(teamKey)) {
			PlayerTeam t = board.getPlayerTeam(id);
			if (t != null) {
				board.removePlayerTeam(t);
			}
		}
	}

	/** Les équipes de cette partie qui existent en ce moment, avec leurs membres (pour les tests en jeu). */
	public Map<String, List<String>> teams() {
		Map<String, List<String>> out = new java.util.LinkedHashMap<>();
		if (server == null) {
			return out;
		}
		for (String id : GameTeams.names(teamKey)) {
			PlayerTeam t = server.getScoreboard().getPlayerTeam(id);
			if (t != null) {
				out.put(id, List.copyOf(t.getPlayers()));
			}
		}
		return out;
	}

	/**
	 * Au démarrage du serveur : retire toutes les équipes de Valcombe laissées dans le tableau des scores (il est enregistré
	 * avec le monde), celles d'avant les parties multiples comprises. Les parties reprises reposent les leurs. Renvoie le
	 * nombre d'équipes retirées.
	 */
	public static int purgeTeams(MinecraftServer server) {
		var board = server.getScoreboard();
		int n = 0;
		for (PlayerTeam t : List.copyOf(board.getPlayerTeams())) {
			if (GameTeams.ours(t.getName())) {
				board.removePlayerTeam(t);
				n++;
			}
		}
		return n;
	}

	private static PlayerTeam team(net.minecraft.server.ServerScoreboard board, String id, String prefix, String suffix) {
		PlayerTeam t = board.getPlayerTeam(id);
		if (t == null) {
			t = board.addPlayerTeam(id);
			t.setColor(java.util.Optional.of(TeamColor.GRAY));
			t.setPlayerPrefix(Component.literal(prefix).withStyle(ChatFormatting.DARK_GRAY));
			t.setPlayerSuffix(Component.literal(suffix).withStyle(ChatFormatting.GOLD));
		}
		return t;
	}

	@Override
	public void prompt(String name, Prompt prompt) {
		// Au tick suivant : on peut être en train de traiter un clic dans un autre menu.
		schedule(1, () -> {
			ServerPlayer p = player(name);
			// Les joueurs équipés du mod client voient l'invite dans leurs propres écrans.
			if (p != null && ui != null && !ViewSync.hasClientMod(p)) {
				ui.openPrompt(p, prompt);
			}
		});
	}

	// ------------------------------------------------------------------
	// Mise en suspens : les joueurs au hub, puis rappelés à leur place
	// ------------------------------------------------------------------
	/** Le voyage d'un joueur vers le hub, sa position ici gardée (Travel.toHub) ; renvoie le message à lui dire. */
	private java.util.function.Function<ServerPlayer, String> toHub;

	public void setToHub(java.util.function.Function<ServerPlayer, String> toHub) {
		this.toHub = toHub;
	}

	/** Joueurs rappelés par la reprise d'une partie en suspens : à leur retour dans le monde, ils retrouvent leur place. */
	private final Set<String> recalled = new java.util.LinkedHashSet<>();

	@Override
	public List<String> sendAway() {
		List<String> out = new ArrayList<>();
		var go = toHub;
		for (ServerPlayer p : here()) {
			String name = p.getScoreboardName();
			if (p.isPassenger()) {
				p.stopRiding();
			}
			// Rien de la partie ne le suit au hub : ni la nuit, ni l'apparence de fantôme. Tout revient à la reprise.
			p.removeEffect(MobEffects.BLINDNESS);
			p.removeEffect(MobEffects.INVISIBILITY);
			p.removeEffect(MobEffects.GLOWING);
			wearHat(p, null);
			out.add(name);
			if (go == null) {
				ValcombeMod.LOG.warn("Partie « {} » en suspens : pas de hub où envoyer {}.", worldName(), name);
				continue;
			}
			try {
				go.apply(p);
			} catch (RuntimeException e) {
				ValcombeMod.LOG.warn("Partie « " + worldName() + " » en suspens : " + name + " n'a pas pu aller au hub", e);
				continue;
			}
			boolean seated = seats.containsKey(name);
			p.sendSystemMessage(Component.literal("[Valcombe] La partie « " + worldName() + " » est en suspens : te voilà au hub. "
				+ (seated ? "Elle reprendra où elle en est, et toi à ta place." : "Elle reprendra où elle en est."))
				.withStyle(ChatFormatting.GOLD));
		}
		clearTeams(); // personne n'emporte le nom de la partie ; ses équipes reviennent avec elle
		return out;
	}

	@Override
	public void recall(List<String> players) {
		for (String name : players) {
			recalled.add(name);
			ServerPlayer p = player(name);
			if (p == null || inGameWorld(p)) {
				continue; // déconnecté : sa place l'attend à la connexion ; déjà là : il la retrouve au prochain passage
			}
			p.sendSystemMessage(Component.literal("[Valcombe] La partie « " + worldName() + " » reprend : ")
				.withStyle(ChatFormatting.GOLD).append(goLink()));
		}
	}

	/** Le lien qui ramène dans le monde de cette partie (la même commande que le tableau des parties). */
	private MutableComponent goLink() {
		String cmd = "/vc partie aller " + worldId;
		return Component.literal("[Y aller]").withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
			.withStyle(st -> st.withClickEvent(new ClickEvent.RunCommand(cmd))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Retrouver ta place dans « " + worldName() + " »"))));
	}

	/** Les joueurs rappelés de retour dans le monde de la partie retrouvent leur place (deux fois par seconde). */
	private void recallArrivals() {
		if (recalled.isEmpty() || session == null || clockFrozen) {
			return;
		}
		for (String name : List.copyOf(recalled)) {
			ServerPlayer p = player(name);
			if (p != null && inGameWorld(p)) {
				recalled.remove(name);
				try {
					session.backAtTable(name);
				} catch (RuntimeException e) {
					ValcombeMod.LOG.warn("Retour de " + name + " à sa place impossible", e);
				}
			}
		}
	}

	/** Les joueurs rappelés qui ne sont pas encore revenus (pour les tests en jeu). */
	public Set<String> recalled() {
		return java.util.Collections.unmodifiableSet(recalled);
	}

	@Override
	public boolean atTable(String name) {
		ServerPlayer p = player(name);
		return p == null || inGameWorld(p);
	}

	// ------------------------------------------------------------------
	// Temps
	// ------------------------------------------------------------------
	/**
	 * Le temps de la partie, en ticks : il ne passe pas tant que la partie est en suspens ({@link #freeze}). Tout ce que
	 * la session confie à {@link #schedule} (minuteurs, étapes de la nuit, aiguille du vote, joueurs fictifs) est compté
	 * sur cette horloge, et repart exactement où il en était.
	 */
	private long gameTick;
	/** Partie en suspens : son horloge est gelée, ses joueurs sont au hub (voir {@link #freeze}, {@link #sendAway}). */
	private boolean clockFrozen;
	/** Ce que l'hôte fait pour lui-même, sur l'horloge du serveur (jamais gelée) : l'accueil des joueurs au hub. */
	private final List<Task> serverTasks = new ArrayList<>();

	@Override
	public void schedule(int ticks, Runnable r) {
		tasks.add(new Task(gameTick + Math.max(1, ticks), r));
	}

	/** Exécute r dans ticks ticks du serveur, que la partie soit en suspens ou non. */
	private void later(int ticks, Runnable r) {
		serverTasks.add(new Task(tick + Math.max(1, ticks), r));
	}

	@Override
	public void freeze(boolean on) {
		if (on && !clockFrozen) {
			ValcombeMod.LOG.info("Partie « {} » : horloge gelée (mise en suspens).", worldName());
		} else if (!on && clockFrozen) {
			ValcombeMod.LOG.info("Partie « {} » : horloge remise en marche.", worldName());
		}
		clockFrozen = on;
	}

	/** L'horloge de la partie est-elle gelée (partie en suspens) ? */
	public boolean clockFrozen() {
		return clockFrozen;
	}

	/** Un chantier ou une remise en état de la carte est en cours ou attend son heure. */
	public boolean hasWork() {
		return server != null && (builds.busy() || restoring || restoreAt >= 0);
	}

	/** Appelé à chaque tick serveur. */
	public void onTick() {
		tick++;
		if (!clockFrozen) {
			gameTick++;
		}
		if (restoreAt >= 0 && tick >= restoreAt && server != null && !builds.busy() && session.game() == null) {
			restoreAt = -1;
			startRestore();
		}
		builds.tick(server);
		// L'éditeur de carte est commun à toutes les parties : son tick est fait une fois, par le mod (voir Games.tick).
		if (tick % 10 == 0) {
			checkPads();
			pruneBars();
			if (server != null) {
				pruneTeams();
				recallArrivals();
			}
		}
		if (tick % 20 == 0) {
			cleanChairs();
			showMarkers();
		}
		if (tick % 20 == 0 && !seats.isEmpty() && !clockFrozen) {
			councilGlow();
		}
		if (tick % 100 == 0 && server != null) {
			// Ménages dans le monde de la partie seulement (run et distance=0..) : les décors des cartes posées dans
			// d'autres mondes (ateliers, parties d'essai) restent.
			if (village != null) {
				run("kill @e[distance=0..,type=minecraft:item_display,tag=vc_trone,tag=!vc_tr_" + village.decorGen() + "]");
			}
			// Scène d'avant un redémarrage, restée dans des chunks qui n'étaient pas chargés quand on l'a retirée.
			run("kill @e[distance=0..,tag=vc_scene,tag=!" + Stage.GEN_TAG + "]");
			run("kill @e[distance=0..,tag=vc_presentoir,tag=!vc_pres_" + hallGen + "]");
			run("kill @e[distance=0..,tag=valcombe_salle,tag=!vc_salle_" + boardGen + "]");
			run("kill @e[distance=0..,tag=vc_repere,tag=!vc_rep_" + (builders.isEmpty() ? -1 : labelGen) + "]");
		}
		if (tick % 4 == 0 && !seats.isEmpty() && !clockFrozen) {
			featherHints();
		}
		List<Task> due = new ArrayList<>();
		for (Iterator<Task> it = serverTasks.iterator(); it.hasNext();) {
			Task t = it.next();
			if (t.due() <= tick) {
				due.add(t);
				it.remove();
			}
		}
		// Horloge de la partie : gelée en suspens, rien n'y arrive à échéance.
		for (Iterator<Task> it = tasks.iterator(); it.hasNext();) {
			Task t = it.next();
			if (t.due() <= gameTick) {
				due.add(t);
				it.remove();
			}
		}
		for (Task t : due) {
			try {
				t.run().run();
			} catch (RuntimeException | LinkageError | StackOverflowError e) {
				ValcombeMod.LOG.error("Erreur dans une tâche de partie", e);
			}
		}
		if (tick % 30 == 0 && !ghostNames.isEmpty() && session.settings().fullEffects) {
			for (String name : ghostNames) {
				ServerPlayer p = player(name);
				if (inGameWorld(p)) {
					p.level().sendParticles(net.minecraft.core.particles.ParticleTypes.SOUL, p.getX(), p.getY() + 0.4, p.getZ(),
						3, 0.25, 0.4, 0.25, 0.01);
				}
			}
		}
		if (clockFrozen) {
			return; // en suspens : personne n'est retenu à sa place (ils sont au hub)
		}
		if (sitEnforced && !inHouses && tick % 10 == 0) {
			sit(new ArrayList<>(seats.keySet()), true);
		}
		if (frozen) {
			for (Map.Entry<String, SeatPos> e : (inHouses ? houses : seats).entrySet()) {
				ServerPlayer p = player(e.getKey());
				SeatPos s = e.getValue();
				// Seulement qui est à la table : un joueur rappelé, encore au hub, n'est pas ramené de force (il a son lien).
				if (p != null && !p.isPassenger() && inGameWorld(p) && p.position().distanceToSqr(s.pos()) > 0.36) {
					p.teleportTo(s.level(), s.pos().x, s.pos().y, s.pos().z, Set.of(), s.yaw(), p.getXRot(), false);
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Placement et effets
	// ------------------------------------------------------------------
	private void teleport(ServerPlayer p, SeatPos s) {
		moveTo(p, s.level(), s.pos(), s.yaw(), 0);
	}

	/**
	 * Ce que les voyages font d'un joueur qui quitte un monde : noter sa position, pour qu'il la retrouve en y
	 * revenant (branché sur Travel.remember). Null sans voyages.
	 */
	private java.util.function.Consumer<ServerPlayer> leaving;

	public void setLeaving(java.util.function.Consumer<ServerPlayer> leaving) {
		this.leaving = leaving;
	}

	/** Amène ce joueur ici ; s'il arrive d'un autre monde (le hub), sa position là-bas est notée d'abord. */
	private void moveTo(ServerPlayer p, ServerLevel level, Vec3 pos, float yaw, float pitch) {
		var l = leaving;
		if (p.level() != level && l != null) {
			try {
				l.accept(p);
			} catch (RuntimeException e) {
				ValcombeMod.LOG.warn("Position de " + p.getScoreboardName() + " non notée avant son départ", e);
			}
		}
		p.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), yaw, pitch, false);
	}

	/**
	 * Amène ce joueur dans le monde de la partie (il s'inscrit, il devient Conteur) : à la salle d'attente ou sur la
	 * place, ou là où il était la dernière fois s'il y revient (voyages). Rien s'il y est déjà, ou si la partie n'a
	 * pas de monde.
	 */
	@Override
	public void toTable(String name) {
		ServerPlayer p = player(name);
		ServerLevel level = levelIfReady();
		if (p == null || level == null || p.level() == level) {
			return;
		}
		var t = bring;
		if (t != null) {
			try {
				p.sendSystemMessage(Component.literal(t.apply(p)).withStyle(ChatFormatting.GREEN));
				return;
			} catch (fr.valcombe.rules.GameException e) {
				ValcombeMod.LOG.warn("Voyage de {} vers la partie : {}", name, e.getMessage());
			}
		}
		if (p.isPassenger()) {
			p.stopRiding();
		}
		Vec3 at = village != null ? Vec3.atBottomCenterOf(village.spawn()) : WorldManager.spawn();
		moveTo(p, level, at, 180, 0);
	}

	/** Le voyage d'un joueur vers le monde de la partie (Travel), qui renvoie le message à lui dire ; null sans voyages. */
	private java.util.function.Function<ServerPlayer, String> bring;

	public void setBring(java.util.function.Function<ServerPlayer, String> bring) {
		this.bring = bring;
	}

	@Override
	public void seatPlayers(List<String> players, String anchor) {
		houses.clear();
		inHouses = false;
		ServerLevel level = levelIfReady();
		if (level == null) {
			ValcombeMod.LOG.error("Joueurs non placés : le monde de la partie n'est pas prêt.");
			return;
		}
		if (village != null) {
			// Les joueurs arrivent souvent d'un autre monde (le hub) : la place est chargée avant d'y monter la scène.
			loadAround(level, village.center(), 48);
			int n = players.size();
			if (village.seatCount() < n) {
				announce("Attention : la carte n'a que " + village.seatCount() + " trône(s) pour " + n + " joueurs.");
			}
			seats.clear();
			for (int i = 0; i < n; i++) {
				Vec3 seat = village.seat(i, n);
				Vec3 house = village.house(i, n);
				seats.put(players.get(i), new SeatPos(level, seat, village.seatYaw(i, n)));
				houses.put(players.get(i), new SeatPos(level, house, village.yawToCenter(house) + 180));
				ServerPlayer p = player(players.get(i));
				if (p != null) {
					previousMode.putIfAbsent(p.getScoreboardName(), p.gameMode.getGameModeForPlayer());
					p.setGameMode(GameType.ADVENTURE);
					teleport(p, seats.get(players.get(i)));
					ValcombeUi.accuseItem(p, true);
				}
			}
			BlockPos c = village.center();
			setupStage(new Vec3(c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5),
				village.isGenerated() ? c.getY() + 6.55 : c.getY() + 4.5, players, village.isGenerated());
			return;
		}
		ServerPlayer center = player(anchor);
		if (center == null && !players.isEmpty()) {
			center = player(players.get(0));
		}
		if (center == null) {
			return;
		}
		// Sans carte, le cercle se forme autour du meneur, dans le monde de la partie : s'il attend ailleurs (le hub, un
		// atelier), le cercle se forme au centre du monde de la partie, jamais au hub.
		double cx;
		double cz;
		double cy;
		if (center.level() == level) {
			cx = Math.floor(center.getX()) + 0.5;
			cz = Math.floor(center.getZ()) + 0.5;
			cy = center.getY();
		} else {
			Vec3 s = WorldManager.spawn();
			cx = s.x;
			cz = s.z;
			cy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(s.x), (int) Math.floor(s.z));
		}
		loadAround(level, BlockPos.containing(cx, cy, cz), 32);
		int n = players.size();
		double radius = Math.max(4, Math.ceil(n * 0.75));
		seats.clear();
		for (int i = 0; i < n; i++) {
			double a = 2 * Math.PI * i / n;
			double x = Math.floor(cx + radius * Math.cos(a)) + 0.5;
			double z = Math.floor(cz + radius * Math.sin(a)) + 0.5;
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
			// Lacet Minecraft : 0 regarde vers +Z, 90 vers -X.
			float yaw = (float) Math.toDegrees(Math.atan2(-(cx - x), cz - z));
			Vec3 pos = new Vec3(x, y, z);
			seats.put(players.get(i), new SeatPos(level, pos, yaw));
			ServerPlayer p = player(players.get(i));
			if (p != null) {
				previousMode.putIfAbsent(p.getScoreboardName(), p.gameMode.getGameModeForPlayer());
				p.setGameMode(GameType.ADVENTURE);
				moveTo(p, level, pos, yaw, 0);
			}
		}
		setupStage(new Vec3(cx, cy, cz), cy + 3.5, players, false);
	}

	/** Lanternes du village : la nuit, elles virent une à une à la flamme d'âme. */
	private List<BlockPos> lanterns;

	private void lights(boolean night) {
		ServerLevel level = levelIfReady();
		if (level == null || village == null) {
			return;
		}
		if (lanterns == null) {
			lanterns = new ArrayList<>();
			BlockPos c = village.center();
			int r = Math.min(90, village.extent());
			for (int x = -r; x <= r; x++) {
				for (int z = -r; z <= r; z++) {
					for (int y = 1; y <= 12; y++) {
						BlockPos pos = c.offset(x, y, z);
						if (level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.LANTERN)) {
							lanterns.add(pos);
						}
					}
				}
			}
		}
		var to = night ? net.minecraft.world.level.block.Blocks.SOUL_LANTERN : net.minecraft.world.level.block.Blocks.LANTERN;
		for (int i = 0; i < lanterns.size(); i++) {
			BlockPos pos = lanterns.get(i);
			schedule(1 + i * 3, () -> {
				// Le monde a pu être remis à neuf entre-temps (l'ancien est fermé), ou ce tronçon déchargé : lire un
				// bloc là forcerait un chargement sur le fil du serveur, qui ne revient jamais pour un monde fermé.
				if (level != levelIfReady() || !level.isLoaded(pos)) {
					return;
				}
				var state = level.getBlockState(pos);
				if (state.getBlock() instanceof net.minecraft.world.level.block.LanternBlock) {
					level.setBlock(pos, to.defaultBlockState()
						.setValue(net.minecraft.world.level.block.LanternBlock.HANGING,
							state.getValue(net.minecraft.world.level.block.LanternBlock.HANGING)), 2);
				}
			});
		}
	}

	/** Il fait nuit sur la carte (lanternes à la flamme d'âme). */
	private boolean night;

	private void setupStage(Vec3 center, double handY, List<String> players, boolean lectern) {
		run("kill @e[distance=0..,type=minecraft:text_display,tag=valcombe_jeton]"); // anciennes versions
		Map<String, Stage.Spot> spots = new java.util.LinkedHashMap<>();
		for (String name : players) {
			SeatPos s = seats.get(name);
			if (s != null) {
				spots.put(name, new Stage.Spot(s.pos(), s.yaw()));
			}
		}
		// Village généré : les trônes sont en blocs ; ailleurs, la scène pose des trônes sculptés.
		ServerLevel level = levelIfReady();
		if (level != null) {
			stage.setup(level, center, handY, spots, lectern, village == null || !village.isGenerated());
		}
	}

	@Override
	public void nightEffects(List<String> players, boolean night, Settings settings) {
		this.night = night;
		frozen = night && settings.nightFreeze;
		ServerLevel level = levelIfReady();
		if (level != null) {
			// L'heure du monde de la partie seulement : le hub et les ateliers gardent la leur.
			if (night) {
				WorldSetup.night(level);
			} else {
				WorldSetup.day(level);
			}
		}
		boolean toHouses = night && settings.nightHouses && !houses.isEmpty();
		if (toHouses) {
			standUp(players);
		} else if (night && settings.nightFreeze) {
			sit(players, true);
		} else {
			sitEnforced = false;
		}
		if (toHouses != inHouses) {
			inHouses = toHouses;
			for (String name : players) {
				ServerPlayer p = player(name);
				SeatPos target = (inHouses ? houses : seats).get(name);
				if (p != null && target != null) {
					teleport(p, target);
				}
			}
		}
		if (!night && !inHouses) {
			sit(players, false); // à l'aube, chacun se réveille sur son trône
		}
		lights(night);
		nightFog(players, night, settings.fullEffects);
		for (String name : players) {
			ServerPlayer p = player(name);
			if (p == null) {
				continue;
			}
			if (night && settings.nightDark) {
				p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 * 60 * 30, 0, false, false));
			} else {
				p.removeEffect(MobEffects.BLINDNESS);
			}
		}
	}

	@Override
	public void endGame(List<String> players) {
		standUp(players);
		for (String name : players) {
			ServerPlayer p = player(name);
			if (p != null) {
				ValcombeUi.accuseItem(p, false);
			}
		}
		nightFog(players, false, false);
		timer(players, "", 0);
		ghostNames.clear();
		glowing = false;
		for (String name : players) {
			ServerPlayer p = player(name);
			if (p != null) {
				p.removeEffect(MobEffects.INVISIBILITY);
				p.removeEffect(MobEffects.GLOWING);
			}
		}
		if (night && levelIfReady() != null) {
			// Partie arrêtée en pleine nuit : le jour et les lanternes reviennent.
			night = false;
			WorldSetup.day(levelIfReady());
			lights(false);
		}
		deadPlayers(List.of(), List.of());
		nameTags(true);
		clearTeams(); // les équipes de la partie disparaissent avec elle
		clockFrozen = false;
		recalled.clear();
		frozen = false;
		inHouses = false;
		storytellerBack = null;
		houses.clear();
		for (String name : players) {
			ServerPlayer p = player(name);
			if (p != null) {
				p.removeEffect(MobEffects.BLINDNESS);
				GameType mode = previousMode.remove(name);
				if (mode != null) {
					p.setGameMode(mode);
				}
			}
		}
		seats.clear();
	}
}
