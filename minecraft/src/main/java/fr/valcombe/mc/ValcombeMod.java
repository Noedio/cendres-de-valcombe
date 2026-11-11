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
		if (sp.isShiftKeyDown()) {
			String cmd = target != null ? "vc aparte " + target : "vc aparte fin";
			sp.level().getServer().getCommands().performPrefixedCommand(sp.createCommandSourceStack(), cmd);
		} else if (target != null) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(sp,
				new fr.valcombe.net.ValcombeNet.OpenPayload("seat:" + target));
		} else {
			UI.openStoryteller(sp);
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
			HOST.prepareWorld();
			String saved = HOST.savedGame();
			if (saved != null) {
				try {
					SESSION.restore(saved);
				} catch (RuntimeException e) {
					LOG.error("Sauvegarde de partie illisible : ignorée", e);
				}
			}
			SESSION.setSavedScripts(ScriptStore.list(server));
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			HOST.setServer(null);
			if (tablet != null) {
				tablet.close();
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			HOST.onTick();
			SYNC.onTick(server);
			if (server.getTickCount() % 5 == 0) {
				VoiceRouter.update(server, SESSION, HOST.village());
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer p = handler.getPlayer();
			UI.giveMenuItem(p);
			var g = SESSION.game();
			HOST.onJoin(p, g != null && g.seatOf(p.getScoreboardName()) >= 0);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			String name = handler.getPlayer().getScoreboardName();
			SYNC.forget(name);
			SESSION.onDisconnect(name);
		});
		// Actions des écrans du mod client : ce sont des commandes /vc, vérifiées comme si elles étaient tapées.
		ServerPlayNetworking.registerGlobalReceiver(ValcombeNet.ActionPayload.TYPE, (payload, context) -> {
			String cmd = payload.command();
			if (cmd.equals("vc") || cmd.startsWith("vc ")) {
				ServerPlayer p = context.player();
				p.level().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), cmd);
			}
		});
		// Clic droit avec l'horloge « Menu de Valcombe » : ouvre le menu (le mod client ouvre le sien).
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
			if (!ValcombeUi.isWand(player.getItemInHand(hand))) {
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
				var at = hit.getBlockPos().above();
				try {
					String msg = sp.isShiftKeyDown() ? HOST.addHouseAt(at) : HOST.addSeatAt(at);
					sp.sendSystemMessage(Gui.plain(msg, net.minecraft.ChatFormatting.GREEN));
					WorldSetup.run(sp.level().getServer(), String.format(java.util.Locale.ROOT,
						"particle minecraft:happy_villager %d %d %d 0.3 0.3 0.3 0 12", at.getX(), at.getY(), at.getZ()));
				} catch (fr.valcombe.rules.GameException e) {
					sp.sendSystemMessage(Gui.plain(e.getMessage(), net.minecraft.ChatFormatting.RED));
				}
			}
			return InteractionResult.SUCCESS;
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
			ValcombeCommands.register(dispatcher, SESSION, UI));
		LOG.info("Les Cendres de Valcombe : prêt.");
	}
}
