package fr.valcombe.mc;

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

/** Pont entre la session de jeu et le serveur Minecraft. */
public final class McHost implements Host {
	private record Task(long due, Runnable run) {
	}

	private record SeatPos(ServerLevel level, Vec3 pos, float yaw) {
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

	/**
	 * Carte oubliée (/vc carte oublier) : on ne joue plus dessus, mais ses blocs sont encore dans le
	 * monde. Elle est gardée pour que la prochaine carte bâtie la retire, où qu'elle soit posée.
	 */
	private Village abandoned;
	/** Avertissement du démarrage (fiche du village illisible…), redit à chaque opérateur qui se connecte. */
	private String startupWarning;

	public void setServer(MinecraftServer server) {
		if (server == null) {
			if (this.server != null) {
				stage.clear(this.server); // la scène ne reste pas dans le monde enregistré : elle est remontée à la reprise
			}
			builds.clear();
			// L'instantané reste sur le disque : une remise en état coupée par l'arrêt reprend au démarrage.
			restoreAt = -1;
			restoring = false;
			holdCenter = null;
		}
		this.server = server;
		village = null;
		abandoned = null;
		startupWarning = null;
		firstStart = false;
		if (server != null) {
			Village.Loaded fiche = Village.load(server);
			switch (fiche.state()) {
				case READY -> village = fiche.village();
				case FORGOTTEN -> abandoned = fiche.village();
				case ABSENT -> firstStart = true;
				case UNREADABLE -> startupWarning = "La fiche du village (config/valcombe-village.json) est illisible : aucune carte "
					+ "n'est en service et rien n'a été rebâti. Rechargez une carte (/vc carte charger <id>) ou bâtissez-en une "
					+ "(/vc carte construire) à l'endroit voulu.";
			}
			try {
				java.nio.file.Path mark = buildMarker();
				if (java.nio.file.Files.exists(mark)) {
					String cut = "Le chantier « " + java.nio.file.Files.readString(mark).trim() + " » a été interrompu par l'arrêt du "
						+ "serveur : la carte est peut-être incomplète. Relancez-le, ou chargez une carte de la bibliothèque.";
					startupWarning = startupWarning == null ? cut : startupWarning + " " + cut;
				}
			} catch (java.io.IOException e) {
				ValcombeMod.LOG.warn("Marque de chantier illisible", e);
			}
		}
	}

	/** Aucune fiche de village n'a jamais existé : c'est le seul cas où un village est bâti d'office. */
	private boolean firstStart;

	/** Au démarrage : règles du monde, et village construit au point d'apparition au tout premier lancement. */
	public void prepareWorld() {
		WorldSetup.apply(server);
		ServerLevel level = server.overworld();
		if (village == null && firstStart) {
			BlockPos spawn = level.getRespawnData().pos();
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ()) - 1;
			Village v = Village.generated(new BlockPos(spawn.getX(), y, spawn.getZ()));
			v.build(level);
			v.save(server);
			village = v;
			ValcombeMod.LOG.info("Village de Valcombe construit en {}", v.center());
		}
		if (village == null) {
			// Carte oubliée ou fiche illisible : surtout ne pas bâtir un second village par-dessus le premier.
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
			v.save(server);
			village = v;
			lanterns = null;
			ValcombeMod.LOG.info("Village de Valcombe agrandi (plan {}) en {}", Village.LAYOUT, v.center());
		}
		if (village.oldThrones()) {
			village.upgradeThrones(level);
			village.save(server);
			ValcombeMod.LOG.info("Trônes du village remplacés par les trônes sculptés");
		}
		if (village.isGenerated() && !village.hasHall()) {
			buildHall();
		}
		applySpawn();
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
		if (village == null || server == null || !inMainWorld(p)) {
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
		WorldSetup.run(server, String.format(java.util.Locale.ROOT,
			"summon minecraft:item_display %.3f %.3f %.3f {Tags:[\"vc_chair\",\"%s\"],Rotation:[%.1ff,0f]}",
			feet.x, feet.y + 0.45, feet.z, tag, yaw));
		p.teleportTo(server.overworld(), feet.x, feet.y, feet.z, Set.of(), yaw, p.getXRot(), false);
		WorldSetup.run(server, "ride " + name + " mount @e[type=minecraft:item_display,tag=" + tag + ",limit=1]");
		p.sendSystemMessage(Component.literal("Accroupis-toi pour te lever.").withStyle(ChatFormatting.GRAY), true);
		return true;
	}

	/** Les sièges libres (hors partie) disparaissent quand on se lève. */
	private void cleanChairs() {
		if (chairCount == 0 || server == null) {
			return;
		}
		List<net.minecraft.world.entity.Entity> empty = new ArrayList<>();
		for (var e : server.overworld().getAllEntities()) {
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
		if (server == null || village == null || village.hallSpot() == null) {
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
		WorldSetup.run(server, "kill @e[tag=vc_presentoir]");
		WorldSetup.run(server, String.format(java.util.Locale.ROOT,
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
			WorldSetup.run(server, String.format(java.util.Locale.ROOT,
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
		if (server == null || p == null || seats.containsKey(name)) {
			return;
		}
		ServerLevel level = server.overworld();
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
			Vec3 c = village != null ? new Vec3(village.center().getX() + 0.5, village.center().getY() + 1, village.center().getZ() + 3.5)
				: p.position();
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
		var ses = ValcombeMod.session();
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
		var ses = ValcombeMod.session();
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
		v.buildHall(server.overworld());
		v.save(server);
		WorldSetup.run(server, "kill @e[type=minecraft:text_display,tag=valcombe_salle]");
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
			WorldSetup.run(server, String.format(java.util.Locale.ROOT,
				"summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"valcombe_salle\",\"vc_salle_%d\"],Rotation:[%.1ff,0f],"
					+ "billboard:\"%s\",line_width:220,background:-937749996,shadow:1b,"
					+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],"
					+ "scale:[%.2ff,%.2ff,%.2ff]},text:\"%s\"}",
				p.x, p.y, p.z, boardGen, yaw, wall ? "fixed" : "center", wall ? 1.35 : 0.6, wall ? 1.35 : 0.6, wall ? 1.35 : 0.6,
				e.getKey().replace("\"", "'").replace("\n", "\\n")));
		}
		applySpawn();
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
			if (pad == null && village != null && p.level() == server.overworld()) {
				// Seulement dans le monde principal : les mêmes blocs posés dans un autre monde ne sont pas des dalles.
				pad = village.padAt(p.blockPosition(), p.level());
			}
			Village.Pad before = lastPad.put(p.getScoreboardName(), pad);
			if (pad != null && pad != before) {
				padHandler.accept(p, pad);
			}
		}
	}

	private void applySpawn() {
		if (village != null) {
			server.overworld().setRespawnData(
				net.minecraft.world.level.storage.LevelData.RespawnData.of(server.overworld().dimension(), village.spawn(), 180, 0));
		}
	}

	/** Arrivée d'un joueur : comme {@link #onJoin(ServerPlayer, boolean, boolean)}, retenu au village s'il joue. */
	public void onJoin(ServerPlayer p, boolean inGame) {
		onJoin(p, inGame, inGame);
	}

	/**
	 * Arrivée d'un joueur : mode aventure (sauf opérateurs). Au hub, sauf si la partie en cours le retient au
	 * village (stay : il y a sa place, ou il en est le Conteur) ; sans hub (mondes de Valcombe indisponibles), sur
	 * la place du village comme avant.
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
		if (hubArrival != null && hubArrival.test(p)) {
			return;
		}
		if (village != null) {
			BlockPos s = village.spawn();
			p.teleportTo(server.overworld(), s.getX() + 0.5, s.getY(), s.getZ() + 0.5, Set.of(), 180, 0, false);
		}
	}

	/** Le point d'arrivée du village actuel (salle d'attente ou place), ou null sans carte en service. */
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
	// Carte personnalisée : /vc carte nouvelle, siege, maison, retirer, infos
	// ------------------------------------------------------------------
	public String customMap(ServerPlayer p) {
		requireMainWorld(p);
		return customMapAt(p.blockPosition().below());
	}

	/** Carte vide à compléter à la main, dont la place est en c (depuis l'Atelier web sans être en jeu : le point d'apparition). */
	public String customMapAt(BlockPos c) {
		Village old = village != null ? village : abandoned;
		// Déclarée sur l'ancienne carte, la nouvelle garde ses blocs (c'est peut-être ce qu'on vient d'y bâtir à la
		// main) et ne change que les repères ; déclarée ailleurs, elle la remplace : l'ancienne est retirée du monde.
		boolean inPlace = old != null && Math.abs(c.getX() - old.center().getX()) <= old.extent() + 16
			&& Math.abs(c.getZ() - old.center().getZ()) <= old.extent() + 16;
		String saved = replaceMap("Nouvelle carte", new BuildQueue.Plan(), !inPlace, () -> {
			useVillage(Village.custom(c));
			announceAll("Nouvelle carte : la place est en " + c.toShortString() + ". Posez les trônes et les maisons, puis enregistrez-la.");
		});
		return "Nouvelle carte vide : la place est en " + c.toShortString() + (inPlace ? "." : ", et l'ancienne carte est retirée du monde.")
			+ " Posez les trônes et les maisons (mode construction, plan de l'Atelier ou /vc carte trone et /vc carte maison), "
			+ "puis enregistrez-la." + saved;
	}

	/** Ce joueur est-il dans le monde principal, celui du village actuel ? */
	private boolean inMainWorld(ServerPlayer p) {
		return p != null && server != null && p.level() == server.overworld();
	}

	/**
	 * La carte du village se travaille sur place : un geste fait depuis un autre monde (le hub, une partie d'essai)
	 * prendrait la position du joueur dans ce monde-là pour une position du village.
	 */
	private void requireMainWorld(ServerPlayer p) {
		if (p != null && !inMainWorld(p)) {
			throw new fr.valcombe.rules.GameException("La carte du village se travaille sur place : va d'abord au village actuel "
				+ "(menu J, « Mondes », ou /vc monde aller principal).");
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
		if (ValcombeMod.session().game() != null) {
			throw new fr.valcombe.rules.GameException("On ne touche pas à la carte pendant une partie.");
		}
		requireFree();
		return v;
	}

	/**
	 * Importe une construction à la place de la carte actuelle : un fichier déposé dans le dossier d'import
	 * (.schem, .litematic, .nbt), ou une structure du monde (enregistrée avec un bloc de structure). Même
	 * chemin que l'import de l'Atelier web : mêmes contrôles, même pose par petits lots.
	 */
	public String importStructure(ServerPlayer p, String name) {
		// Sans carte à remplacer, la construction arrive là où se tient l'opérateur.
		if (village == null && abandoned == null) {
			requireMainWorld(p);
		}
		BlockPos where = village == null && abandoned == null && p != null ? p.blockPosition().below() : null;
		if (ValcombeMod.structures().importable().contains(name)) {
			return importSchematic(name, ValcombeMod.structures().inboxFile(name), where);
		}
		var id = net.minecraft.resources.Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
		if (id == null) {
			throw new fr.valcombe.rules.GameException("Nom de fichier ou de structure invalide : " + name);
		}
		var template = server.getStructureTemplateManager().get(id)
			.orElseThrow(() -> new fr.valcombe.rules.GameException("Construction introuvable : " + name
				+ ". Déposez un fichier .schem, .litematic ou .nbt dans " + ValcombeMod.structures().inboxPath()
				+ ", ou enregistrez-la dans le monde avec un bloc de structure."));
		byte[] data;
		try {
			// La structure du monde suit le même chemin qu'un fichier : relue case par case, sans entités ni données à risque.
			data = MapStore.compress(template.save(new net.minecraft.nbt.CompoundTag()));
		} catch (java.io.IOException e) {
			throw new fr.valcombe.rules.GameException("Structure illisible : " + e.getMessage());
		}
		String file = id.getPath().substring(id.getPath().lastIndexOf('/') + 1) + ".nbt";
		return importSchematic(file, data, where);
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
		village.save(server);
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
		if (v == null || server == null || v.modified() || ValcombeMod.session().game() != null || p.level() != server.overworld()
			|| p.gameMode.getGameModeForPlayer() == GameType.ADVENTURE || p.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
			return;
		}
		int reach = v.extent() + 8;
		if (Math.abs(pos.getX() - v.center().getX()) <= reach && Math.abs(pos.getZ() - v.center().getZ()) <= reach) {
			v.setModified(true);
			v.save(server);
		}
	}

	/** L'éditeur vient de changer des blocs : comme pour un bloc posé à la main, la carte est tenue pour retouchée. */
	/** L'éditeur a bâti dans cette boîte : la carte la retient (voir Village.include), pour la raser en entier plus tard. */
	void markBuilt(int x0, int y0, int z0, int x1, int y1, int z1) {
		Village v = village;
		if (v != null && server != null) {
			v.include(x0, y0, z0, x1, y1, z1);
			v.save(server);
		}
	}

	void markEdited() {
		Village v = village;
		if (v != null && server != null && !v.modified()) {
			v.setModified(true);
			v.save(server);
		}
	}

	/** La carte v vient d'être enregistrée dans la bibliothèque : elle n'a plus rien à perdre. */
	void mapSaved(Village v) {
		v.setModified(false);
		if (v == village) {
			v.save(server);
		}
	}

	public String addSeat(ServerPlayer p) {
		requireMainWorld(p);
		return addSeatAt(p.blockPosition());
	}

	public String addHouse(ServerPlayer p) {
		requireMainWorld(p);
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
		ServerLevel level = server.overworld();
		prefabs.entrySet().removeIf(e -> {
			BlockPos at = e.getKey();
			if (now.seats().contains(at) || now.houses().contains(at) || at.equals(now.convocation())) {
				return false;
			}
			e.getValue().forEach((b, s) -> level.setBlock(b, s, Clip.EXACT));
			return true;
		});
		carrying.clear();
		applySpawn();
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
		requireMainWorld(p);
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
				applySpawn();
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
			WorldSetup.run(server, "kill @e[type=minecraft:item_display,tag=vc_trone]");
			village.placeThrones(server);
		}
	}

	/** Un constructeur clique sur un bloc avec un outil de carte ; renvoie ce qui s'est passé. */
	public String useTool(ServerPlayer p, ValcombeUi.MapTool tool, BlockPos clicked, boolean sneak) {
		if (tool == ValcombeUi.MapTool.SELECT || tool == ValcombeUi.MapTool.STRUCTURE) {
			throw new IllegalArgumentException("Outil de l'éditeur : voir MapEditor.");
		}
		requireMainWorld(p);
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
			applySpawn();
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
		BlockPos at = v.groundAt(server.overworld(), v.center().getX() + dx, v.center().getZ() + dz);
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
					built.forEach((b, s) -> server.overworld().setBlock(b, s, Clip.EXACT));
				}
				msg = h.label() + done(h, " retiré") + (built != null ? ", et sa construction avec." : ".");
			}
			default -> throw new fr.valcombe.rules.GameException("Action inconnue : " + action + ".");
		}
		if (mark == Village.Mark.CENTER) {
			applySpawn();
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
		prefabs.put(at, room ? v.buildRoomAt(server.overworld(), at) : v.buildHouseAt(server.overworld(), at));
		markEdited();
		return h.label() + " bâtie. La gomme la retirera avec son repère.";
	}

	/** Depuis l'Atelier : amène l'opérateur sur un repère, pour voir dans le jeu ce que montre le plan. */
	public String visitMarker(ServerPlayer p, String type, int index) {
		Village v = requireVillage();
		var game = ValcombeMod.session().game();
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
		p.teleportTo(server.overworld(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, Set.of(), p.getYRot(), p.getXRot(), false);
		return "Vous voilà sur le repère.";
	}

	private int labelGen;

	/** Étiquettes flottantes des repères, visibles à travers les murs tant que quelqu'un construit. */
	private void refreshLabels() {
		if (server == null) {
			return;
		}
		labelGen++;
		WorldSetup.run(server, "kill @e[type=minecraft:text_display,tag=vc_repere]");
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
		WorldSetup.run(server, String.format(java.util.Locale.ROOT,
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
		if (server == null || pp == null || village == null) {
			return false;
		}
		ServerLevel level = server.overworld();
		if (village.convocation() == null && village.isGenerated()) {
			village.buildConvocation(level); // village bâti avant cette version : on lui ajoute sa salle
			village.save(server);
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
		ServerLevel level = server.overworld();
		int r = Math.min(200, v.extent());
		int n = 2 * r + 1;
		var img = new java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_INT_RGB);
		BlockPos c = v.center();
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

	private java.nio.file.Path saveFile() {
		return server.getServerDirectory().resolve("config").resolve("valcombe-partie.json");
	}

	@Override
	public void persist(String json) {
		if (server == null) {
			return;
		}
		try {
			java.nio.file.Path f = saveFile();
			if (json.isEmpty()) {
				java.nio.file.Files.deleteIfExists(f);
			} else {
				// Écriture dans un fichier voisin, puis remplacement : une coupure ne laisse jamais une sauvegarde à moitié écrite.
				java.nio.file.Files.createDirectories(f.getParent());
				java.nio.file.Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
				java.nio.file.Files.writeString(tmp, json);
				try {
					java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
						java.nio.file.StandardCopyOption.ATOMIC_MOVE);
				} catch (java.nio.file.AtomicMoveNotSupportedException e) {
					java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} catch (java.io.IOException e) {
			ValcombeMod.LOG.warn("Sauvegarde de la partie impossible", e);
		}
	}

	/** Sauvegarde laissée par un arrêt du serveur, ou null. */
	public String savedGame() {
		try {
			java.nio.file.Path f = saveFile();
			return java.nio.file.Files.exists(f) ? java.nio.file.Files.readString(f) : null;
		} catch (java.io.IOException e) {
			return null;
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

	@Override
	public String loadMap(String id) {
		if (maps == null) {
			throw new fr.valcombe.rules.GameException("Bibliothèque de cartes indisponible.");
		}
		return maps.load(id);
	}

	@Override
	public String mapInPlace() {
		// Pendant un chantier ou une remise en état, aucune carte n'est « en place » : elle est en train de changer.
		return village != null && !village.modified() && !busy() ? village.libraryId() : "";
	}

	/** Centre par défaut d'une carte (point d'apparition du monde, au niveau du sol). */
	public BlockPos defaultCenter() {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getRespawnData().pos();
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ()) - 1;
		return new BlockPos(spawn.getX(), y, spawn.getZ());
	}

	/** Où poser une carte chargée ou importée : à la place de l'actuelle (ou de celle qu'on a oubliée), sinon au point d'apparition. */
	BlockPos mapCenter() {
		return village != null ? village.center() : abandoned != null ? abandoned.center() : defaultCenter();
	}

	/** Chantiers en cours : les blocs sont posés par petits lots à chaque tick. */
	private final BuildQueue builds = new BuildQueue();

	@Override
	public boolean busy() {
		return builds.busy() || restoreAt >= 0;
	}

	/** Rien ne touche à la carte pendant un chantier, ni tant que la remise en état d'après partie n'est pas faite. */
	void requireFree() {
		if (builds.busy()) {
			throw new fr.valcombe.rules.GameException("Une construction est déjà en cours : attendez la fin de la barre de progression.");
		}
		if (restoreAt >= 0) {
			throw new fr.valcombe.rules.GameException("La carte va être remise en état après la partie : patientez quelques secondes "
				+ "(/vc arreter la lance tout de suite).");
		}
	}

	/** Raser sans rien faire tomber : ni le contenu d'un coffre, ni la torche dont le support disparaît. */
	private static final int CLEAR_FLAGS = net.minecraft.world.level.block.Block.UPDATE_CLIENTS
		| net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS
		| net.minecraft.world.level.block.Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

	/**
	 * La suite de blocs qui rase un carré autour du centre : socle de pierre, herbe, et de l'air au-dessus.
	 * Elle est calculée bloc à bloc : même la plus grande carte ne demande aucune mémoire.
	 */
	BuildQueue.Part clearPart(BlockPos c, int radius, int below, int height) {
		return clearBox(c.getX() - radius, c.getZ() - radius, c.getX() + radius, c.getZ() + radius, c.getY(), below, height);
	}

	/**
	 * Rase un rectangle : `below` rangées de pierre sous baseY, de l'herbe à baseY, de l'air au-dessus,
	 * sur `height` rangées en tout.
	 */
	BuildQueue.Part clearBox(int x0, int z0, int x1, int z1, int baseY, int below, int height) {
		var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
		var grass = net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
		var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		int sx = x1 - x0 + 1;
		int sz = z1 - z0 + 1;
		return new BuildQueue.Part() {
			@Override
			int size() {
				return sx * sz * height;
			}

			@Override
			net.minecraft.world.level.block.state.BlockState at(int i, BlockPos.MutableBlockPos pos) {
				int y = i / (sx * sz) - below;
				int inLayer = i % (sx * sz);
				pos.set(x0 + inLayer / sz, baseY + y, z0 + inLayer % sz);
				return y < 0 ? stone : y == 0 ? grass : air;
			}

			@Override
			int flags() {
				return CLEAR_FLAGS;
			}
		};
	}

	/** Hauteur à raser pour retirer la carte v : 28 blocs comme une carte enregistrée, davantage si une construction importée dépasse. */
	int clearHeight(Village v) {
		if (v.isGenerated()) {
			return 28;
		}
		return surfaceHeight(v.center(), v.extent());
	}

	/** Hauteur à raser (4 rangées de sous-sol comprises) pour tout ce qui dépasse dans le carré de rayon r autour de c. */
	private int surfaceHeight(BlockPos c, int r) {
		ServerLevel level = server.overworld();
		int top = level.getMinY();
		for (int cx = c.getX() - r >> 4; cx <= c.getX() + r >> 4; cx++) {
			for (int cz = c.getZ() - r >> 4; cz <= c.getZ() + r >> 4; cz++) {
				var chunk = level.getChunk(cx, cz);
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						if (Math.abs((cx << 4) + x - c.getX()) <= r && Math.abs((cz << 4) + z - c.getZ()) <= r) {
							top = Math.max(top, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
						}
					}
				}
			}
		}
		return Math.max(28, Math.min(SchematicImporter.MAX_HEIGHT + 10, top - c.getY() + 6));
	}

	/** Façons de raser l'ancienne carte avant d'en bâtir une autre. */
	public enum Raze {
		/** Tout ce que la carte occupe et tout ce que l'éditeur y a bâti. */
		BATI,
		/** Un grand carré fixe autour du centre (Village.MAX_EXTENT blocs de chaque côté), quoi qu'il contienne. */
		CARRE;

		public static Raze parse(String s) {
			return s != null && s.toLowerCase(java.util.Locale.ROOT).startsWith("carr") ? CARRE : BATI;
		}
	}

	/** Le rasage de l'ancienne carte old : {x0, z0, x1, z1, hauteur} autour de son centre. */
	private int[] razeBox(Village old, Raze mode) {
		BlockPos c = old.center();
		if (mode == Raze.CARRE) {
			int r = Village.MAX_EXTENT;
			return new int[] {c.getX() - r, c.getZ() - r, c.getX() + r, c.getZ() + r, surfaceHeight(c, r)};
		}
		int r = old.extent();
		int[] box = {c.getX() - r, c.getZ() - r, c.getX() + r, c.getZ() + r, clearHeight(old)};
		int[] b = old.built();
		if (b != null) {
			// Ce que l'éditeur a bâti, même hors des repères (borné pour ne jamais raser au-delà d'un très grand carré).
			int cap = 2 * Village.MAX_EXTENT;
			box[0] = Math.max(c.getX() - cap, Math.min(box[0], b[0]));
			box[1] = Math.max(c.getZ() - cap, Math.min(box[1], b[2]));
			box[2] = Math.min(c.getX() + cap, Math.max(box[2], b[3]));
			box[3] = Math.min(c.getZ() + cap, Math.max(box[3], b[5]));
			box[4] = Math.max(box[4], Math.min(SchematicImporter.MAX_HEIGHT + 10, b[4] - c.getY() + 1 + 4));
		}
		return box;
	}

	/** L'éditeur de carte du mode construction (sélection, structures, annulation). */
	private MapEditor editor;

	public void setEditor(MapEditor editor) {
		this.editor = editor;
	}

	/** Ce joueur est-il en mode construction ? */
	boolean isBuilder(String name) {
		return builders.contains(name);
	}

	/** Une opération de l'éditeur : posée par petits lots comme un chantier, sans toucher aux panneaux de la salle. */
	void startEdit(String label, BuildQueue.Plan plan, Runnable done) {
		builds.add(label, plan, done);
	}

	/** Lance un chantier ; done est appelé sur le fil du serveur quand tout est posé. */
	void startBuild(String label, BuildQueue.Plan plan, Runnable done) {
		WorldSetup.run(server, "kill @e[type=minecraft:text_display,tag=valcombe_salle]");
		builds.add(label, plan, done);
	}

	/** Changement de carte en cours (son nom), noté sur le disque : un arrêt du serveur en plein chantier se voit au redémarrage. */
	private java.nio.file.Path buildMarker() {
		return server.getServerDirectory().resolve("config").resolve("valcombe-chantier.txt");
	}

	private void markBuild(String label) {
		try {
			java.nio.file.Path f = buildMarker();
			if (label == null) {
				java.nio.file.Files.deleteIfExists(f);
			} else {
				java.nio.file.Files.createDirectories(f.getParent());
				java.nio.file.Files.writeString(f, label);
			}
		} catch (java.io.IOException e) {
			ValcombeMod.LOG.warn("Marque de chantier non écrite", e);
		}
	}

	/** Zone dont les chunks sont tenus chargés pendant qu'on retire une ancienne carte (ses entités doivent être là pour partir). */
	private BlockPos holdCenter;
	private int holdRadius;

	private void holdChunks() {
		server.overworld().getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.ENDER_PEARL,
			net.minecraft.world.level.ChunkPos.containing(holdCenter), holdRadius / 16 + 2);
	}

	/** Retire du monde tous les décors en entités d'une carte : scène, trônes sculptés, panneaux, présentoir, étiquettes, sièges libres. */
	private void removeDecor() {
		stage.clear(server);
		List<net.minecraft.world.entity.Entity> gone = new ArrayList<>();
		for (var e : server.overworld().getAllEntities()) {
			var tags = e.entityTags();
			if (tags.contains("vc_trone") || tags.contains("vc_scene") || tags.contains("valcombe_salle")
				|| tags.contains("vc_presentoir") || tags.contains("vc_repere") || tags.contains("vc_chair")) {
				gone.add(e);
			}
		}
		gone.forEach(net.minecraft.world.entity.Entity::discard);
		hallKey = ""; // le présentoir sera reposé dans la nouvelle salle
	}

	/**
	 * Le seul chemin pour changer de carte, afin qu'il n'y en ait jamais deux dans le monde : l'ancienne
	 * (en service ou oubliée) est d'abord retirée — toute son emprise, où qu'elle soit, avec ses trônes
	 * sculptés, ses panneaux et ses étiquettes —, puis build est posé et done adopte la nouvelle.
	 * Une ancienne carte retouchée à la main est d'abord sauvegardée dans la bibliothèque ; si cette
	 * sauvegarde échoue, on ne touche à rien. raze = false : la nouvelle carte se déclare sur les blocs
	 * de l'ancienne, qui restent en place. Renvoie la phrase à ajouter au message (vide le plus souvent).
	 */
	String replaceMap(String label, BuildQueue.Plan build, boolean raze, Runnable done) {
		return replaceMap(label, build, raze, Raze.BATI, done);
	}

	String replaceMap(String label, BuildQueue.Plan build, boolean raze, Raze mode, Runnable done) {
		if (ValcombeMod.session().game() != null) {
			throw new fr.valcombe.rules.GameException("On change de carte entre deux parties.");
		}
		requireFree();
		Village old = village != null ? village : abandoned;
		String saved = "";
		if (old != null && old.modified()) {
			try {
				if (maps == null) {
					throw new fr.valcombe.rules.GameException("bibliothèque de cartes indisponible");
				}
				saved = " L'ancienne carte, retouchée à la main, est sauvegardée dans la bibliothèque : « " + maps.backup(old) + " ».";
				mapSaved(old);
			} catch (fr.valcombe.rules.GameException e) {
				throw new fr.valcombe.rules.GameException("Carte non remplacée : l'actuelle a été retouchée à la main et sa sauvegarde "
					+ "automatique a échoué (" + e.getMessage() + "). Enregistrez-la d'abord : /vc carte enregistrer <id> <nom>.");
			}
		}
		BuildQueue.Plan plan = new BuildQueue.Plan();
		if (old != null && raze) {
			int[] box = razeBox(old, mode);
			plan.first(clearBox(box[0], box[1], box[2], box[3], old.center().getY(), 4, box[4]));
			holdCenter = old.center();
			holdRadius = Math.max(Math.max(old.center().getX() - box[0], box[2] - old.center().getX()),
				Math.max(old.center().getZ() - box[1], box[3] - old.center().getZ()));
			holdChunks();
		}
		plan.then(build);
		// Les décors de l'ancienne carte partent avant que ceux de la nouvelle n'arrivent.
		plan.after.add(0, this::removeDecor);
		dropSnapshot();
		if (editor != null) {
			editor.forgetHistory(); // on n'annule pas une opération faite sur une carte qui n'est plus là
		}
		markBuild(label);
		startBuild(label, plan, () -> {
			abandoned = null;
			done.run();
			markBuild(null);
			if (old != null && raze && village != null && village != old) {
				rescuePlayers(old); // personne ne reste sur le terrain nu de l'ancienne carte
			}
		});
		return saved;
	}

	/** Ceux qui se trouvent dans l'emprise de la carte v (un chantier, une remise en état) sont ramenés au point d'apparition. */
	private void rescuePlayers(Village v) {
		BlockPos spot = (village != null ? village : v).spawn();
		int reach = v.extent() + 6;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (builders.contains(p.getScoreboardName())) {
				continue; // un constructeur reste où il travaille
			}
			if (p.level() != server.overworld()) {
				continue; // au hub ou dans un autre monde : rien à craindre du chantier du village
			}
			if (Math.abs(p.getX() - v.center().getX()) <= reach && Math.abs(p.getZ() - v.center().getZ()) <= reach) {
				if (p.isPassenger()) {
					p.stopRiding();
				}
				p.teleportTo(server.overworld(), spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, Set.of(), 180, 0, false);
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

	/** L'instantané de la carte pris au lancement de la partie : il reste sur le disque tant que la carte n'est pas remise en état. */
	private java.nio.file.Path snapshotFile() {
		return server.getServerDirectory().resolve("config").resolve("valcombe-instantane.nbt");
	}

	private void dropSnapshot() {
		restoreAt = -1;
		try {
			java.nio.file.Files.deleteIfExists(snapshotFile());
		} catch (java.io.IOException e) {
			ValcombeMod.LOG.warn("Instantané de la carte non supprimé", e);
		}
	}

	/** Une partie commence : on garde la carte telle qu'elle est, pour la rendre intacte à la fin. */
	@Override
	public void gameStarted() {
		if (server == null) {
			return;
		}
		dropSnapshot();
		if (editor != null) {
			editor.forgetHistory(); // après la partie, la carte est remise en état : plus rien à annuler
		}
		if (village == null) {
			return;
		}
		try {
			if (village.isGenerated() && village.convocation() == null) {
				// Village d'avant les salles de convocation : bâtie maintenant, elle fait partie de ce qu'on garde.
				village.buildConvocation(server.overworld());
				village.save(server);
			}
			long start = System.nanoTime();
			MapSnapshot.capture(server.overworld(), village.center(), village.extent()).write(snapshotFile());
			ValcombeMod.LOG.info("Instantané de la carte pris en {} ms ({} Ko)", (System.nanoTime() - start) / 1_000_000,
				java.nio.file.Files.size(snapshotFile()) / 1024);
		} catch (java.io.IOException | RuntimeException e) {
			ValcombeMod.LOG.warn("Instantané de la carte impossible : elle ne sera pas remise en état à la fin de la partie", e);
		}
	}

	/** La partie est finie : la carte retrouvera son état d'avant dans delayTicks (le temps de voir les rôles révélés). */
	@Override
	public void restoreMap(int delayTicks) {
		if (server == null || restoring || !java.nio.file.Files.exists(snapshotFile())) {
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
		if (server != null && ValcombeMod.session().game() == null && java.nio.file.Files.exists(snapshotFile())) {
			ValcombeMod.LOG.info("Carte laissée en l'état par l'arrêt du serveur : sa remise en état reprend");
			restoreAt = tick + 100;
		}
	}

	private void startRestore() {
		ServerLevel level = server.overworld();
		java.nio.file.Path f = snapshotFile();
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
		stage.clear(server);
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
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (p.level() == level && !p.isSpectator() && !level.noCollision(p)) {
					BlockPos s = v.spawn();
					p.teleportTo(level, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, Set.of(), 180, 0, false);
				}
			}
			announceAll("La carte est remise en état.");
		});
	}

	/** Adopte une carte (chargée ou générée) : enregistrement, salle d'attente, apparition. */
	public void useVillage(Village v) {
		stage.clear(server);
		village = v;
		abandoned = null;
		startupWarning = null;
		lanterns = null;
		v.save(server);
		if (v.hasHall()) {
			buildHall();
		}
		applySpawn();
		rescuePlayers(v);
		carrying.clear();
		prefabs.clear();
		markerUndo.clear();
		if (editor != null) {
			editor.forgetHistory();
		}
		refreshLabels();
	}

	/** Fin des messages qui lancent un chantier : valable dans le jeu comme sur la page de l'Atelier. */
	static final String PROGRESS = " Dans le jeu, une barre en haut de l'écran suit le chantier.";

	/** Génère un village réglable au même endroit (ou sous les pieds du joueur s'il n'y en a pas). */
	public String generateVillage(ServerPlayer p, int seatCount, int size, String theme, boolean houses) {
		return generateVillage(p, seatCount, size, theme, houses, Raze.BATI);
	}

	public String generateVillage(ServerPlayer p, int seatCount, int size, String theme, boolean houses, Raze raze) {
		if (ValcombeMod.session().game() != null) {
			throw new fr.valcombe.rules.GameException("On change de carte entre deux parties.");
		}
		requireFree();
		// Sans carte, le village est bâti sous les pieds de l'opérateur s'il est au village, sinon au centre habituel.
		BlockPos c = village != null ? village.center()
			: inMainWorld(p) ? new BlockPos(p.getBlockX(), p.getBlockY() - 1, p.getBlockZ()) : mapCenter();
		Village v = Village.generated(c, theme, seatCount, size, houses);
		// L'ancienne carte est retirée, puis le village est bâti par petits lots : le serveur reste fluide, une barre montre l'avancement.
		String saved = replaceMap("Construction du village", v.planBuild(server.overworld()), true, raze, () -> {
			adoptGenerated(v);
			announceAll("Le village est construit : " + v.seatTotal() + " trônes, thème " + v.theme() + ".");
		});
		return "Construction lancée : " + v.seatTotal() + " trônes, taille " + new String[] {"petite", "normale", "grande"}[v.size()]
			+ ", thème " + v.theme() + (houses ? ", avec maisons." : ", sans maisons.") + PROGRESS + saved;
	}

	/** Le village généré v vient d'être bâti : sa salle de convocation, sa salle d'attente, et il devient la carte. */
	private void adoptGenerated(Village v) {
		v.buildConvocation(server.overworld());
		useVillage(v);
		if (!v.hasHall()) {
			buildHall();
		}
	}

	/**
	 * Importe une construction (.schem, .litematic, .nbt) à la place de la carte actuelle, centrée
	 * sur la place ; elle devient une carte à compléter en mode construction (trônes, maisons, centre).
	 */
	public String importSchematic(String fileName, byte[] data) {
		return importSchematic(fileName, data, null);
	}

	/** where : le centre voulu quand il n'y a aucune carte à remplacer (null : le point d'apparition). */
	public String importSchematic(String fileName, byte[] data, BlockPos where) {
		if (ValcombeMod.session().game() != null) {
			throw new fr.valcombe.rules.GameException("On change de carte entre deux parties.");
		}
		ServerLevel level = server.overworld();
		Clip clip = SchematicImporter.read(fileName, data, server.registryAccess().lookupOrThrow(
			net.minecraft.core.registries.Registries.BLOCK));
		BlockPos c = village == null && abandoned == null && where != null ? where : mapCenter();
		requireFree();
		int half = Math.max(clip.width, clip.length) / 2 + 2;
		BlockPos corner = c.offset(-clip.width / 2, 0, -clip.length / 2);
		// L'ancienne carte est retirée et le terrain rasé, puis la construction est posée : le tout par petits lots.
		BuildQueue.Plan build = new BuildQueue.Plan().first(clearPart(c, half, 4, Math.max(28, clip.height + 6)))
			.then(clip.plan(level, corner, false, false));
		String saved = replaceMap("Import de « " + fileName + " »", build, true, () -> {
			useVillage(Village.custom(c));
			announceAll("Construction « " + fileName + " » importée (" + clip.dims() + "). Passez en mode construction pour poser "
				+ "les trônes, les maisons et le centre, puis enregistrez la carte.");
		});
		return "Import de « " + fileName + " » lancé (" + clip.dims() + " blocs)." + PROGRESS + saved;
	}

	public String setMapCenter(ServerPlayer p) {
		requireMainWorld(p);
		Village v = requireEditable();
		Village.Marks before = v.marks();
		moveCenter(v, p.blockPosition().below());
		applySpawn();
		markersChanged(before);
		return "Centre de la place posé ici.";
	}

	private final java.util.Set<String> builders = new java.util.HashSet<>();
	private final Map<String, GameType> builderModes = new HashMap<>();

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
		if (ValcombeMod.session().game() != null) {
			throw new fr.valcombe.rules.GameException("Pas de construction pendant une partie.");
		}
		requireMainWorld(p);
		builders.add(name);
		if (village != null && !village.modified()) {
			// On entre ici pour bâtir : la carte est désormais tenue pour retouchée, elle ne sera pas remplacée sans sauvegarde.
			village.setModified(true);
			village.save(server);
		}
		builderModes.put(name, p.gameMode.getGameModeForPlayer());
		p.setGameMode(GameType.CREATIVE);
		ValcombeUi.mapTools(p, true);
		refreshLabels();
		return "Mode construction : construisez librement. Cinq outils de repères (trône, maison, centre, salle de convocation, "
			+ "gomme) : clic droit pour poser, ou pour prendre un repère et le déplacer ; accroupi, maison et salle sont bâties "
			+ "d'un coup. Outil de sélection : clic gauche et clic droit posent deux coins. Outil de structure : il pose la "
			+ "structure choisie dans le catalogue. L'Atelier en jeu (/vc atelier jeu) réunit le catalogue, les opérations "
			+ "sur la sélection et l'annulation.";
	}

	private void showMarkers() {
		if (editor != null) {
			// L'éditeur sert aussi sans carte : sa ligne d'état (sélection, structure en main) passe d'abord.
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
			if (p == null) {
				continue;
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

	/** Construit le village de Valcombe sous les pieds du joueur. */
	public String buildVillage(ServerPlayer p) {
		return buildVillage(p, "gothique");
	}

	public String buildVillage(ServerPlayer p, String theme) {
		if (ValcombeMod.session().game() != null) {
			throw new fr.valcombe.rules.GameException("On change de carte entre deux parties.");
		}
		requireFree();
		requireMainWorld(p);
		BlockPos c = new BlockPos(p.getBlockX(), p.getBlockY() - 1, p.getBlockZ());
		Village v = Village.generated(c, theme);
		// Bâti ailleurs que l'ancienne carte, le village la remplace tout de même : elle est d'abord retirée du monde.
		String saved = replaceMap("Construction du village", v.planBuild(server.overworld()), true, () -> {
			adoptGenerated(v);
			announceAll("Le village de Valcombe (" + v.theme() + ") est construit, avec sa salle d'attente.");
		});
		return "Construction du village lancée." + PROGRESS + saved;
	}

	/**
	 * Oublie la carte : on jouera autour du Conteur. Ses blocs ne sont pas détruits (c'est peut-être un travail
	 * fait à la main) ; la fiche retient son emprise, et la prochaine carte bâtie la retirera du monde.
	 */
	public String forgetVillage() {
		if (ValcombeMod.session().game() != null) {
			throw new fr.valcombe.rules.GameException("On change de carte entre deux parties.");
		}
		requireFree();
		Village v = requireVillage();
		v.saveForgotten(server);
		stage.clear(server);
		abandoned = v;
		village = null;
		lanterns = null;
		carrying.clear();
		refreshLabels();
		return "La carte est oubliée : les joueurs seront placés autour du Conteur. Ses blocs restent en place ; "
			+ "ils seront retirés du monde dès qu'une autre carte sera bâtie, générée ou chargée.";
	}

	/** Message à tous les joueurs connectés. */
	void announceAll(String text) {
		if (server == null) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			p.sendSystemMessage(Component.literal("[Valcombe] " + text).withStyle(ChatFormatting.GOLD));
		}
	}

	public void setUi(ValcombeUi ui) {
		this.ui = ui;
	}

	public ServerPlayer player(String name) {
		return server == null ? null : server.getPlayerList().getPlayerByName(name);
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

	@Override
	public void announce(String text) {
		if (server != null) {
			server.getPlayerList().broadcastSystemMessage(
				Component.empty().append(PREFIX).append(Component.literal(text).withStyle(ChatFormatting.GOLD)), false);
		}
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
			ServerPlayer p = player(name);
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
			ServerPlayer p = player(name);
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
			stage.clear(server);
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
		stage.setFullEffects(ValcombeMod.session().settings().fullEffects);
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
			if (p != null && i >= 0 && !p.isPassenger()) {
				WorldSetup.run(server, "ride " + name + " mount @e[type=minecraft:item_display,tag=vc_seat_" + i + ",limit=1]");
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
			ServerPlayer p = player(name);
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
			ServerPlayer p = player(name);
			if (p != null) {
				nightBar.addPlayer(p);
			}
		}
	}

	@Override
	public void sign(String player, String symbol, String color) {
		ServerPlayer p = player(player);
		if (server == null || p == null) {
			return;
		}
		String tag = "vc_signe_" + Math.abs(player.hashCode());
		WorldSetup.run(server, "kill @e[type=minecraft:text_display,tag=" + tag + "]");
		WorldSetup.run(server, String.format(java.util.Locale.ROOT,
			"summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"%s\"],billboard:\"center\",background:0,"
				+ "see_through:0b,shadow:1b,transformation:{translation:[0f,0.7f,0f],left_rotation:[0f,0f,0f,1f],"
				+ "right_rotation:[0f,0f,0f,1f],scale:[2.6f,2.6f,2.6f]},text:{text:\"%s\",color:\"%s\",bold:1b}}",
			p.getX(), p.getY() + 2.1, p.getZ(), tag, symbol, color));
		WorldSetup.run(server, "ride @e[type=minecraft:text_display,tag=" + tag + ",limit=1] mount " + player);
		WorldSetup.run(server, String.format(java.util.Locale.ROOT, "playsound minecraft:block.note_block.hat master @a %.1f %.1f %.1f 0.6 1.6",
			p.getX(), p.getY(), p.getZ()));
		int token = ++signToken;
		signTokens.put(player, token);
		schedule(80, () -> {
			if (signTokens.getOrDefault(player, 0) == token) {
				WorldSetup.run(server, "kill @e[type=minecraft:text_display,tag=" + tag + "]");
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
			if (p == null) {
				continue;
			}
			String tag = "vc_bulle_" + Math.abs(name.hashCode());
			WorldSetup.run(server, "kill @e[type=minecraft:text_display,tag=" + tag + "]");
			WorldSetup.run(server, String.format(java.util.Locale.ROOT,
				"summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"%s\"],billboard:\"center\",background:-1073741824,"
					+ "transformation:{translation:[0f,0.55f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
					+ "scale:[1.4f,1.4f,1.4f]},text:{text:\"…\",color:\"light_purple\"}}",
				p.getX(), p.getY() + 2.1, p.getZ(), tag));
			WorldSetup.run(server, "ride @e[type=minecraft:text_display,tag=" + tag + ",limit=1] mount " + name);
			schedule(160, () -> WorldSetup.run(server, "kill @e[type=minecraft:text_display,tag=" + tag + "]"));
		}
	}

	@Override
	public void accusation(String from, String to) {
		ServerPlayer a = player(from);
		ServerPlayer b = player(to);
		if (server == null || a == null || b == null) {
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
		WorldSetup.run(server, String.format(java.util.Locale.ROOT, "playsound valcombe:cloche master @a %.1f %.1f %.1f 1.5 1.4",
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
		if (server == null || p == null) {
			return;
		}
		String at = String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f", p.getX(), p.getY() + 1, p.getZ());
		WorldSetup.run(server, "particle minecraft:large_smoke " + at + " 0.4 1 0.4 0.02 60 force");
		WorldSetup.run(server, "particle minecraft:soul_fire_flame " + at + " 0.3 0.8 0.3 0.03 50 force");
		WorldSetup.run(server, "playsound minecraft:entity.wither.death master @a " + at + " 0.6 0.7");
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
				WorldSetup.run(server, "particle minecraft:firework " + at + " 1.5 1.5 1.5 0.15 120 force");
				WorldSetup.run(server, "playsound minecraft:entity.firework_rocket.twinkle master @a " + at + " 2 1");
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

	/** Fantômes : nom grisé précédé d'une croix ; une main s'ils ont encore leur vote. */
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
			if (p == null) {
				continue;
			}
			// Les morts deviennent translucides (le mod client les garde visibles, en transparence).
			if (dead.contains(name)) {
				p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false));
			} else {
				p.removeEffect(MobEffects.INVISIBILITY);
			}
		}
		String hat = ValcombeMod.session().settings().deadHat;
		for (String name : seats.keySet()) {
			ServerPlayer p = player(name);
			if (p != null) {
				String model = !dead.contains(name) || "aucun".equals(hat) ? null
					: ("crane".equals(hat) ? "crane_mort" : "chapeau_mort") + (withGhostVote.contains(name) ? "_vote" : "");
				wearHat(p, model);
			}
		}
		PlayerTeam ghosts = team(board, "valcombe_fantomes", "† ", "");
		PlayerTeam voters = team(board, "valcombe_fantomes_vote", "† ", " ✋");
		for (String name : seats.keySet()) {
			PlayerTeam current = board.getPlayersTeam(name);
			// Un vivant garde l'équipe « valcombe_vivants » (noms cachés) ou « valcombe_cache » (dans un recoin), sinon aucune.
			PlayerTeam wanted = !dead.contains(name)
				? (current != null && (current.getName().equals(ALIVE_TEAM) || current.getName().equals("valcombe_cache")) ? current : null)
				: withGhostVote.contains(name) ? voters : ghosts;
			if (current != wanted) {
				if (current != null && current.getName().startsWith("valcombe_")) {
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

	private static final String ALIVE_TEAM = "valcombe_vivants";

	@Override
	public void nameTags(boolean visible) {
		if (server == null) {
			return;
		}
		var board = server.getScoreboard();
		var mode = visible ? net.minecraft.world.scores.Team.Visibility.ALWAYS : net.minecraft.world.scores.Team.Visibility.NEVER;
		PlayerTeam alive = board.getPlayerTeam(ALIVE_TEAM);
		if (alive == null) {
			alive = board.addPlayerTeam(ALIVE_TEAM);
		}
		for (String id : List.of(ALIVE_TEAM, "valcombe_fantomes", "valcombe_fantomes_vote")) {
			PlayerTeam t = board.getPlayerTeam(id);
			if (t != null && t.getNameTagVisibility() != mode) {
				t.setNameTagVisibility(mode);
			}
		}
		// Les vivants n'ont pas d'équipe à eux : on les range dans celle-ci tant que les noms sont cachés.
		for (String name : seats.keySet()) {
			PlayerTeam current = board.getPlayersTeam(name);
			if (!visible && current == null) {
				board.addPlayerToTeam(name, alive);
			} else if (visible && current == alive) {
				board.removePlayerFromTeam(name, alive);
			}
		}
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
	// Temps
	// ------------------------------------------------------------------
	@Override
	public void schedule(int ticks, Runnable r) {
		tasks.add(new Task(tick + Math.max(1, ticks), r));
	}

	/** Un chantier ou une remise en état de la carte est en cours ou attend son heure. */
	public boolean hasWork() {
		return server != null && (builds.busy() || restoring || restoreAt >= 0);
	}

	/** Appelé à chaque tick serveur. */
	public void onTick() {
		tick++;
		if (restoreAt >= 0 && tick >= restoreAt && server != null && !builds.busy() && ValcombeMod.session().game() == null) {
			restoreAt = -1;
			startRestore();
		}
		if (holdCenter != null && server != null) {
			if (!builds.busy()) {
				holdCenter = null;
			} else if (tick % 20 == 0) {
				holdChunks();
			}
		}
		builds.tick(server);
		if (editor != null) {
			editor.tick();
		}
		if (tick % 10 == 0) {
			checkPads();
		}
		if (tick % 20 == 0) {
			cleanChairs();
			showMarkers();
		}
		if (tick % 20 == 0 && !seats.isEmpty()) {
			councilGlow();
		}
		if (tick % 100 == 0 && server != null) {
			if (village != null) {
				WorldSetup.run(server, "kill @e[type=minecraft:item_display,tag=vc_trone,tag=!vc_tr_" + village.decorGen() + "]");
			}
			// Scène d'avant un redémarrage, restée dans des chunks qui n'étaient pas chargés quand on l'a retirée.
			WorldSetup.run(server, "kill @e[tag=vc_scene,tag=!" + Stage.GEN_TAG + "]");
			WorldSetup.run(server, "kill @e[tag=vc_presentoir,tag=!vc_pres_" + hallGen + "]");
			WorldSetup.run(server, "kill @e[tag=valcombe_salle,tag=!vc_salle_" + boardGen + "]");
			WorldSetup.run(server, "kill @e[tag=vc_repere,tag=!vc_rep_" + (builders.isEmpty() ? -1 : labelGen) + "]");
		}
		if (tick % 4 == 0 && !seats.isEmpty()) {
			featherHints();
		}
		List<Task> due = new ArrayList<>();
		for (Iterator<Task> it = tasks.iterator(); it.hasNext();) {
			Task t = it.next();
			if (t.due() <= tick) {
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
		if (tick % 30 == 0 && !ghostNames.isEmpty() && ValcombeMod.session().settings().fullEffects) {
			for (String name : ghostNames) {
				ServerPlayer p = player(name);
				if (p != null) {
					p.level().sendParticles(net.minecraft.core.particles.ParticleTypes.SOUL, p.getX(), p.getY() + 0.4, p.getZ(),
						3, 0.25, 0.4, 0.25, 0.01);
				}
			}
		}
		if (sitEnforced && !inHouses && tick % 10 == 0) {
			sit(new ArrayList<>(seats.keySet()), true);
		}
		if (frozen) {
			for (Map.Entry<String, SeatPos> e : (inHouses ? houses : seats).entrySet()) {
				ServerPlayer p = player(e.getKey());
				SeatPos s = e.getValue();
				if (p != null && !p.isPassenger() && p.position().distanceToSqr(s.pos()) > 0.36) {
					p.teleportTo(s.level(), s.pos().x, s.pos().y, s.pos().z, Set.of(), s.yaw(), p.getXRot(), false);
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Placement et effets
	// ------------------------------------------------------------------
	private void teleport(ServerPlayer p, SeatPos s) {
		p.teleportTo(s.level(), s.pos().x, s.pos().y, s.pos().z, Set.of(), s.yaw(), 0, false);
	}

	@Override
	public void seatPlayers(List<String> players, String anchor) {
		houses.clear();
		inHouses = false;
		if (village != null && server != null) {
			ServerLevel level = server.overworld();
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
		// Sans carte, le cercle se forme autour du meneur, dans le monde principal : s'il attend au hub (ou dans un
		// autre monde de Valcombe), le cercle se forme au point d'apparition du monde principal, jamais au hub.
		ServerLevel level = server != null ? server.overworld() : center.level();
		double cx;
		double cz;
		double cy;
		if (center.level() == level) {
			cx = Math.floor(center.getX()) + 0.5;
			cz = Math.floor(center.getZ()) + 0.5;
			cy = center.getY();
		} else {
			BlockPos s = server.getRespawnData().pos();
			cx = s.getX() + 0.5;
			cz = s.getZ() + 0.5;
			cy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, s.getX(), s.getZ());
		}
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
				p.teleportTo(level, x, y, z, Set.of(), yaw, 0, false);
			}
		}
		setupStage(new Vec3(cx, cy, cz), cy + 3.5, players, false);
	}

	/** Lanternes du village : la nuit, elles virent une à une à la flamme d'âme. */
	private List<BlockPos> lanterns;

	private void lights(boolean night) {
		if (server == null || village == null) {
			return;
		}
		ServerLevel level = server.overworld();
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
		WorldSetup.run(server, "kill @e[type=minecraft:text_display,tag=valcombe_jeton]"); // anciennes versions
		Map<String, Stage.Spot> spots = new java.util.LinkedHashMap<>();
		for (String name : players) {
			SeatPos s = seats.get(name);
			if (s != null) {
				spots.put(name, new Stage.Spot(s.pos(), s.yaw()));
			}
		}
		// Village généré : les trônes sont en blocs ; ailleurs, la scène pose des trônes sculptés.
		stage.setup(server, center, handY, spots, lectern, village == null || !village.isGenerated());
	}

	@Override
	public void nightEffects(List<String> players, boolean night, Settings settings) {
		this.night = night;
		frozen = night && settings.nightFreeze;
		if (server != null) {
			if (night) {
				WorldSetup.night(server);
			} else {
				WorldSetup.day(server);
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
		if (night && server != null) {
			// Partie arrêtée en pleine nuit : le jour et les lanternes reviennent.
			night = false;
			WorldSetup.day(server);
			lights(false);
		}
		deadPlayers(List.of(), List.of());
		nameTags(true);
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
