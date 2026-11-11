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

	/** La table de jeu du serveur (utilisée par le plugin vocal). */
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
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> HOST.setServer(null));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			HOST.onTick();
			SYNC.onTick(server);
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
			if (ValcombeUi.isMenuItem(player.getItemInHand(hand))) {
				if (player instanceof ServerPlayer sp) {
					UI.openMain(sp);
				}
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
			ValcombeCommands.register(dispatcher, SESSION, UI));
		LOG.info("Les Cendres de Valcombe : prêt.");
	}
}
