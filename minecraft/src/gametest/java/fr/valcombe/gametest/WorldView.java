package fr.valcombe.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Avant la capture d'une vue du monde (le hub, un village, un atelier vu d'en haut) : le chat est effacé, ses lignes
 * cachaient ce qu'on voulait voir. Les captures dont le sujet est un message du chat (un lien [Y aller], la fin d'un
 * envoi) ne l'appellent pas.
 */
final class WorldView {
	private WorldView() {
	}

	static void clearChat(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
		ctx.waitTicks(2);
	}
}
