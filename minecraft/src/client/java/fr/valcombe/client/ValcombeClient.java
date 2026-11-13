package fr.valcombe.client;

import fr.valcombe.net.ValcombeNet;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import com.mojang.blaze3d.platform.InputConstants;

/** Point d'entrée du mod côté client : écrans sur mesure et HUD. */
public final class ValcombeClient implements ClientModInitializer {
	private static KeyMapping menuKey;
	private static KeyMapping handKey;
	private static KeyMapping signKey;
	private static KeyMapping roleKey;
	private static KeyMapping voiceKey;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(ValcombeNet.ViewPayload.TYPE,
			(payload, context) -> context.client().execute(() -> ClientState.apply(payload.json())));
		ClientPlayNetworking.registerGlobalReceiver(ValcombeNet.OpenPayload.TYPE,
			(payload, context) -> context.client().execute(() -> ScreenRouter.open(payload.screen())));
		ClientPlayNetworking.registerGlobalReceiver(ValcombeNet.PreviewPayload.TYPE,
			(payload, context) -> context.client().execute(() -> ClientState.applyPreview(payload.json())));

		Hud.register();
		Ghost.register();
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("valcombe", "main"));
		menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.valcombe.menu", InputConstants.KEY_J, category));
		handKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.valcombe.hand", InputConstants.KEY_H, category));
		signKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.valcombe.sign", InputConstants.KEY_G, category));
		roleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.valcombe.role", InputConstants.KEY_K, category));
		voiceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.valcombe.voice", InputConstants.KEY_B, category));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (handKey.consumeClick()) {
				// Lever ou baisser la main pendant le vote à l'horloge
				var vote = ClientState.obj(ClientState.view(), "vote");
				if (vote != null && ClientState.bool(vote, "canVote")) {
					ClientState.send(ClientState.bool(vote, "myHand") ? "vc vote non" : "vc vote oui");
				}
			}
			while (signKey.consumeClick()) {
				if (client.gui.screen() == null) {
					client.gui.setScreen(new fr.valcombe.client.screen.SignScreen());
				}
			}
			while (voiceKey.consumeClick()) {
				// Chuchoter → parler → crier
				if (ClientState.num(ClientState.view(), "mySeat", -1) >= 0) {
					ClientState.send("vc voix suivant");
				}
			}
			while (roleKey.consumeClick()) {
				Hud.showCard(); // revoir sa carte de rôle
			}
			while (menuKey.consumeClick()) {
				if (client.gui.screen() == null) {
					// Une action en attente passe avant le menu principal.
					ScreenRouter.open(ClientState.obj(ClientState.view(), "prompt") != null ? "prompt" : "main");
				}
			}
		});
	}
}
