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

	public void setServer(MinecraftServer server) {
		this.server = server;
		this.village = server == null ? null : Village.load(server);
	}

	/** Au démarrage : règles du monde, et village construit au point d'apparition s'il n'existe pas encore. */
	public void prepareWorld() {
		WorldSetup.apply(server);
		ServerLevel level = server.overworld();
		if (village == null) {
			BlockPos spawn = level.getRespawnData().pos();
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ()) - 1;
			Village v = Village.generated(new BlockPos(spawn.getX(), y, spawn.getZ()));
			v.build(level);
			v.save(server);
			village = v;
			ValcombeMod.LOG.info("Village de Valcombe construit en {}", v.center());
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
		if (village == null || server == null) {
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
		float yaw = village.yawToCenter(feet);
		WorldSetup.run(server, String.format(java.util.Locale.ROOT,
			"summon minecraft:item_display %.3f %.3f %.3f {Tags:[\"vc_chair\",\"%s\"],Rotation:[%.1ff,0f]}",
			feet.x, feet.y + 0.35, feet.z, tag, yaw));
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

	private final Map<String, String> featherHint = new HashMap<>();

	/** Indication dans la barre d'action quand on tient la plume : qui est visé, ou comment voter. */
	private void featherHints() {
		var ses = ValcombeMod.session();
		var g = ses.game();
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
						: ses.handRaised(name) ? "✋ Main levée — clic droit (ou H) pour la baisser"
						: "Main baissée — clic droit (ou H) pour la lever";
				} else if (!ses.nominationsOpen()) {
					hint = "Les nominations ne sont pas ouvertes.";
				} else {
					String target = aimedPlayer(p);
					hint = target == null ? "Vise un joueur, puis clic droit pour l'accuser"
						: "⚖ " + target + " — clic droit pour l'accuser";
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
		for (var e : v.hallBoards().entrySet()) {
			Vec3 p = e.getValue();
			WorldSetup.run(server, String.format(java.util.Locale.ROOT,
				"summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"valcombe_salle\"],billboard:\"center\","
					+ "line_width:220,background:-1308622848,text:\"%s\"}",
				p.x, p.y, p.z, e.getKey().replace("\"", "'").replace("\n", "\\n")));
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

	private void checkPads() {
		if (village == null || padHandler == null || server == null) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Village.Pad pad = village.padAt(p.blockPosition(), p.level());
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

	/** Arrivée d'un joueur : mode aventure (sauf opérateurs), sur la place si aucune partie ne le retient. */
	public void onJoin(ServerPlayer p, boolean inGame) {
		boolean op = p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
		if (!op) {
			p.setGameMode(GameType.ADVENTURE);
		}
		if (!inGame && village != null) {
			BlockPos s = village.spawn();
			p.teleportTo(server.overworld(), s.getX() + 0.5, s.getY(), s.getZ() + 0.5, Set.of(), 180, 0, false);
		}
	}

	// ------------------------------------------------------------------
	// Carte personnalisée : /vc carte nouvelle, siege, maison, retirer, infos
	// ------------------------------------------------------------------
	public String customMap(ServerPlayer p) {
		village = Village.custom(p.blockPosition().below());
		lanterns = null;
		village.save(server);
		applySpawn();
		return "Nouvelle carte : la place est ici. Placez-vous sur chaque siège puis faites /vc carte siege, "
			+ "et dans chaque maison /vc carte maison.";
	}

	private Village requireVillage() {
		if (village == null) {
			throw new fr.valcombe.rules.GameException("Aucune carte : /vc carte construire ou /vc carte nouvelle.");
		}
		return village;
	}

	/**
	 * Importe une construction (fichier de structure .nbt, par exemple enregistré avec un bloc
	 * de structure, ou copié dans world/generated/minecraft/structures/) sous les pieds du joueur,
	 * puis en fait la carte : il reste à poser les sièges et les maisons avec la baguette.
	 */
	public String importStructure(ServerPlayer p, String name) {
		var id = net.minecraft.resources.Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
		if (id == null) {
			throw new fr.valcombe.rules.GameException("Nom de structure invalide : " + name);
		}
		var template = server.getStructureTemplateManager().get(id)
			.orElseThrow(() -> new fr.valcombe.rules.GameException("Structure introuvable : " + id
				+ ". Placez le fichier dans world/generated/" + id.getNamespace() + "/structures/" + id.getPath() + ".nbt."));
		var size = template.getSize();
		BlockPos corner = p.blockPosition().offset(-size.getX() / 2, -1, -size.getZ() / 2);
		template.placeInWorld(p.level(), corner, corner,
			new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
			p.level().getRandom(), 2);
		village = Village.custom(p.blockPosition().below());
		village.save(server);
		lanterns = null;
		applySpawn();
		return "Construction « " + id + " » importée (" + size.getX() + " × " + size.getY() + " × " + size.getZ()
			+ "). Posez maintenant les sièges et les maisons avec /vc carte baguette.";
	}

	public List<String> structures() {
		return server.getStructureTemplateManager().listTemplates()
			.filter(i -> !i.getNamespace().equals("minecraft") || !i.getPath().contains("/"))
			.map(Object::toString).limit(60).toList();
	}

	public String addSeat(ServerPlayer p) {
		Village v = requireVillage();
		v.addSeat(p.blockPosition());
		v.save(server);
		return "Siège n°" + v.seatCount() + " enregistré.";
	}

	public String addHouse(ServerPlayer p) {
		Village v = requireVillage();
		v.addHouse(p.blockPosition());
		v.save(server);
		return "Maison n°" + v.houseCount() + " enregistrée.";
	}

	public String addSeatAt(BlockPos pos) {
		Village v = requireVillage();
		v.addSeat(pos);
		v.save(server);
		return "Siège n°" + v.seatCount() + " posé.";
	}

	public String addHouseAt(BlockPos pos) {
		Village v = requireVillage();
		v.addHouse(pos);
		v.save(server);
		return "Maison n°" + v.houseCount() + " posée.";
	}

	public String removeSeat() {
		Village v = requireVillage();
		boolean ok = v.removeLastSeat();
		v.save(server);
		return ok ? "Dernier siège retiré (" + v.seatCount() + " restants)." : "Aucun siège à retirer.";
	}

	public String removeHouse() {
		Village v = requireVillage();
		boolean ok = v.removeLastHouse();
		v.save(server);
		return ok ? "Dernière maison retirée (" + v.houseCount() + " restantes)." : "Aucune maison à retirer.";
	}

	public String mapInfo() {
		Village v = requireVillage();
		return (v.isGenerated() ? "Village généré" : "Carte personnalisée") + " — place en " + v.center().toShortString()
			+ ", " + v.seatCount() + " siège(s), " + v.houseCount() + " maison(s).";
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
				java.nio.file.Files.createDirectories(f.getParent());
				java.nio.file.Files.writeString(f, json);
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

	/** Construit le village de Valcombe sous les pieds du joueur. */
	public String buildVillage(ServerPlayer p) {
		return buildVillage(p, "gothique");
	}

	public String buildVillage(ServerPlayer p, String theme) {
		BlockPos c = new BlockPos(p.getBlockX(), p.getBlockY() - 1, p.getBlockZ());
		Village v = Village.generated(c, theme);
		v.build(p.level());
		v.save(server);
		village = v;
		lanterns = null;
		applySpawn();
		buildHall();
		Vec3 spot = new Vec3(c.getX() + 0.5, c.getY() + 1, c.getZ() + 4.5);
		p.teleportTo(p.level(), spot.x, spot.y, spot.z, Set.of(), 180, 0, false);
		return "Le village de Valcombe (" + v.theme() + ") est construit, avec sa salle d'attente.";
	}

	public String forgetVillage() {
		Village.forget(server);
		village = null;
		return "Le village est oublié : les joueurs seront placés autour du Conteur.";
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
		for (String name : players) {
			ServerPlayer p = player(name);
			if (p != null) {
				p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 15));
				p.connection.send(new ClientboundSetSubtitleTextPacket(
					Component.literal(subtitle).withStyle(ChatFormatting.GRAY)));
				p.connection.send(new ClientboundSetTitleTextPacket(
					Component.literal(title).withStyle(ChatFormatting.GOLD)));
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
		bar.setName(Component.literal(title + " — " + left / 60 + ":" + String.format("%02d", left % 60)));
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
		PlayerTeam ghosts = team(board, "valcombe_fantomes", "† ", "");
		PlayerTeam voters = team(board, "valcombe_fantomes_vote", "† ", " ✋");
		for (String name : seats.keySet()) {
			PlayerTeam current = board.getPlayersTeam(name);
			PlayerTeam wanted = !dead.contains(name) ? null : withGhostVote.contains(name) ? voters : ghosts;
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

	/** Appelé à chaque tick serveur. */
	public void onTick() {
		tick++;
		if (tick % 10 == 0) {
			checkPads();
		}
		if (tick % 20 == 0) {
			cleanChairs();
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
			} catch (RuntimeException e) {
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
				announce("Attention : la carte n'a que " + village.seatCount() + " siège(s) pour " + n + " joueurs.");
			}
			seats.clear();
			for (int i = 0; i < n; i++) {
				Vec3 seat = village.seat(i, n);
				Vec3 house = village.house(i, n);
				seats.put(players.get(i), new SeatPos(level, seat, village.yawToCenter(seat)));
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
		ServerLevel level = center.level();
		double cx = Math.floor(center.getX()) + 0.5;
		double cz = Math.floor(center.getZ()) + 0.5;
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
		setupStage(new Vec3(cx, center.getY(), cz), center.getY() + 3.5, players, false);
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
			for (int x = -52; x <= 52; x++) {
				for (int z = -52; z <= 52; z++) {
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
		for (String name : players) {
			ServerPlayer p = player(name);
			if (p != null) {
				p.removeEffect(MobEffects.INVISIBILITY);
			}
		}
		deadPlayers(List.of(), List.of());
		frozen = false;
		inHouses = false;
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
