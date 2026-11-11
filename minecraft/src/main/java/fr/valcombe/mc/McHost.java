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
		if (village.isGenerated() && !village.hasHall()) {
			buildHall();
		}
		applySpawn();
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
		Holder<SoundEvent> event = switch (sound) {
			case "death" -> Holder.direct(SoundEvents.BELL_RESONATE);
			case "clock" -> SoundEvents.NOTE_BLOCK_HAT;
			default -> Holder.direct(SoundEvents.BELL_BLOCK);
		};
		float pitch = sound.equals("death") ? 0.5f : 1f;
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
		WorldSetup.run(server, "kill @e[type=minecraft:text_display,tag=valcombe_jeton]");
		for (var e : labels.entrySet()) {
			SeatPos s = seats.get(e.getKey());
			if (s == null) {
				continue;
			}
			String[] parts = e.getValue().split("\\|", 2);
			String text = parts[1].replace("\"", "'").replace("\n", "\\n");
			WorldSetup.run(server, String.format(java.util.Locale.ROOT,
				"summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"valcombe_jeton\"],billboard:\"center\","
					+ "background:1073741824,transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
					+ "translation:[0f,0f,0f],scale:[2.5f,2.5f,2.5f]},text:{text:\"%s\",color:\"%s\"}}",
				s.pos().x, s.pos().y + 2.6, s.pos().z, text, parts[0]));
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
			if (p != null && s != null) {
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
		if (frozen) {
			for (Map.Entry<String, SeatPos> e : (inHouses ? houses : seats).entrySet()) {
				ServerPlayer p = player(e.getKey());
				SeatPos s = e.getValue();
				if (p != null && p.position().distanceToSqr(s.pos()) > 0.36) {
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
				}
			}
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
