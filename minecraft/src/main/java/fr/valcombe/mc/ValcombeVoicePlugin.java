package fr.valcombe.mc;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;

import java.util.UUID;

/**
 * Branchement sur Simple Voice Chat (si le mod est installé) : le vocal suit le jeu.
 * La nuit, seuls le Conteur et le joueur réveillé se parlent ; pendant le Conseil, tout le
 * village s'entend ; le jour, une maison est une salle privée ; ailleurs, la voix porte à la
 * portée choisie (chuchoter, parler, crier).
 * <p>
 * Chaque partie a son vocal : toute voix qui irait d'un joueur à un autre est arrêtée s'ils ne sont pas dans la même
 * partie ({@link VoiceRouter#mayHear}), qu'elle passe par la proximité, un groupe du vocal, la vue d'un spectateur ou
 * un autre greffon. On n'entend jamais une autre partie, ni le hub depuis une partie, ni l'inverse.
 */
public final class ValcombeVoicePlugin implements VoicechatPlugin {
	@Override
	public String getPluginId() {
		return "valcombe";
	}

	/** Arrête une voix qui passerait d'une partie à une autre (ou entre une partie et le hub). */
	private static void separate(SoundPacketEvent<?> event) {
		VoicechatConnection from = event.getSenderConnection();
		VoicechatConnection to = event.getReceiverConnection();
		if (from == null || to == null || !event.isCancellable()) {
			return; // un son sans joueur (autre greffon) : rien à séparer
		}
		if (!VoiceRouter.mayHear(from.getPlayer().getUuid(), to.getPlayer().getUuid())) {
			event.cancel();
		}
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		// Chaque paquet de voix, juste avant de partir vers un joueur : jamais d'une partie à l'autre.
		registration.registerEvent(StaticSoundPacketEvent.class, ValcombeVoicePlugin::separate);
		registration.registerEvent(LocationalSoundPacketEvent.class, ValcombeVoicePlugin::separate);
		registration.registerEvent(EntitySoundPacketEvent.class, ValcombeVoicePlugin::separate);
		registration.registerEvent(MicrophonePacketEvent.class, event -> {
			VoicechatConnection sender = event.getSenderConnection();
			if (sender == null) {
				return;
			}
			UUID speaker = sender.getPlayer().getUuid();
			VoiceRouter.Route route = VoiceRouter.route(speaker);
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
					VoicechatConnection c = VoiceRouter.mayHear(speaker, id) ? api.getConnectionOf(id) : null;
					if (c != null) {
						api.sendLocationalSoundPacketTo(c, packet);
					}
				}
				return;
			}
			var packet = event.getPacket().staticSoundPacketBuilder().build();
			for (UUID id : route.targets()) {
				VoicechatConnection c = VoiceRouter.mayHear(speaker, id) ? api.getConnectionOf(id) : null;
				if (c != null) {
					api.sendStaticSoundPacketTo(c, packet);
				}
			}
		});
	}
}
