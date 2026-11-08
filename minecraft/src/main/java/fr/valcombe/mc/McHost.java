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
		applySpawn();
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

	public Village village() {
		return village;
	}

	/** Construit le village de Valcombe sous les pieds du joueur. */
	public String buildVillage(ServerPlayer p) {
		BlockPos c = new BlockPos(p.getBlockX(), p.getBlockY() - 1, p.getBlockZ());
		Village v = Village.generated(c);
		v.build(p.level());
		v.save(server);
		village = v;
		applySpawn();
		Vec3 spot = new Vec3(c.getX() + 0.5, c.getY() + 1, c.getZ() + 4.5);
		p.teleportTo(p.level(), spot.x, spot.y, spot.z, Set.of(), 180, 0, false);
		return "Le village de Valcombe est construit. Les parties s'y joueront désormais.";
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
