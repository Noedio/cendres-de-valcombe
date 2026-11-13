package fr.valcombe.mc;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

import java.util.UUID;

/**
 * Branchement sur Simple Voice Chat (si le mod est installé) : le vocal suit le jeu.
 * La nuit, seuls le Conteur et le joueur réveillé se parlent ; pendant le Conseil, tout le
 * village s'entend ; le jour, une maison est une salle privée ; ailleurs, la voix porte à la
 * portée choisie (chuchoter, parler, crier).
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
			VoiceRouter.Route route = VoiceRouter.route(sender.getPlayer().getUuid());
			if (route == null) {
				return;
			}
			event.cancel();
			VoicechatServerApi api = event.getVoicechat();
			if (route.range() > 0) {
				// Voix placée dans le monde : elle s'atténue avec la distance, jusqu'à la portée choisie.
				var at = sender.getPlayer().getPosition();
				var packet = event.getPacket().locationalSoundPacketBuilder()
					.position(api.createPosition(at.getX(), at.getY(), at.getZ()))
					.distance(route.range())
					.build();
				for (UUID id : route.targets()) {
					VoicechatConnection c = api.getConnectionOf(id);
					if (c != null) {
						api.sendLocationalSoundPacketTo(c, packet);
					}
				}
				return;
			}
			var packet = event.getPacket().staticSoundPacketBuilder().build();
			for (UUID id : route.targets()) {
				VoicechatConnection c = api.getConnectionOf(id);
				if (c != null) {
					api.sendStaticSoundPacketTo(c, packet);
				}
			}
		});
	}
}
