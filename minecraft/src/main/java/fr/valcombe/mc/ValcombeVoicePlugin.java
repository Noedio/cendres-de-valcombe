package fr.valcombe.mc;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

import net.minecraft.server.level.ServerPlayer;

/**
 * Branchement sur Simple Voice Chat (si le mod est installé) : la nuit, les micros
 * des joueurs sont coupés ; le Conteur garde le sien. Le reste du temps, le vocal
 * de proximité fonctionne normalement (on s'isole dans les ruelles pour chuchoter).
 */
public final class ValcombeVoicePlugin implements VoicechatPlugin {
	@Override
	public String getPluginId() {
		return "valcombe";
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(MicrophonePacketEvent.class, event -> {
			VoicechatConnection sender = event.getSenderConnection();
			if (sender == null) {
				return;
			}
			Object player = sender.getPlayer().getPlayer();
			if (player instanceof ServerPlayer sp && ValcombeMod.session().isMuted(sp.getScoreboardName())) {
				event.cancel();
			}
		});
	}
}
