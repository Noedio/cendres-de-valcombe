package fr.valcombe.mc;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

import java.util.List;
import java.util.UUID;

/**
 * Branchement sur Simple Voice Chat (si le mod est installé) : le vocal suit le jeu.
 * La nuit, seuls le Conteur et le joueur réveillé se parlent ; le jour, une maison est
 * une salle privée. Ailleurs, le vocal de proximité fonctionne normalement.
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
			List<UUID> targets = VoiceRouter.targets(sender.getPlayer().getUuid());
			if (targets == null) {
				return;
			}
			event.cancel();
			VoicechatServerApi api = event.getVoicechat();
			var packet = event.getPacket().toStaticSoundPacket();
			for (UUID id : targets) {
				VoicechatConnection c = api.getConnectionOf(id);
				if (c != null) {
					api.sendStaticSoundPacketTo(c, packet);
				}
			}
		});
	}
}
