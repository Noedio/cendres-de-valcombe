package fr.valcombe.mc;

import fr.valcombe.session.Session;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Point d'entrée du mod « Les Cendres de Valcombe ». */
public final class ValcombeMod implements ModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("valcombe");

	private static final McHost HOST = new McHost();
	private static final Session SESSION = new Session(HOST);
	private static final ValcombeUi UI = new ValcombeUi(SESSION, HOST);

	@Override
	public void onInitialize() {
		HOST.setUi(UI);
		ServerLifecycleEvents.SERVER_STARTED.register(HOST::setServer);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> HOST.setServer(null));
		ServerTickEvents.END_SERVER_TICK.register(server -> HOST.onTick());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> UI.giveMenuItem(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			SESSION.onDisconnect(handler.getPlayer().getScoreboardName()));
		// Clic droit avec l'horloge « Menu de Valcombe » : ouvre le menu.
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (player instanceof ServerPlayer sp && ValcombeUi.isMenuItem(sp.getItemInHand(hand))) {
				UI.openMain(sp);
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
			ValcombeCommands.register(dispatcher, SESSION, UI));
		LOG.info("Les Cendres de Valcombe : prêt.");
	}
}
