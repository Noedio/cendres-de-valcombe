package fr.valcombe.client;

import com.google.gson.JsonObject;

import fr.valcombe.client.screen.MainScreen;
import fr.valcombe.client.screen.PromptScreen;
import fr.valcombe.client.screen.StorytellerScreen;
import fr.valcombe.client.screen.SheetsScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Ouvre le bon écran selon la demande du serveur ou l'invite en cours. */
public final class ScreenRouter {
	private ScreenRouter() {
	}

	public static void open(String which) {
		Minecraft mc = Minecraft.getInstance();
		if (which.startsWith("seat:")) {
			// Bâton du Conteur : la ligne du grimoire du joueur visé
			mc.gui.setScreen(new fr.valcombe.client.screen.SeatScreen(which.substring(5)));
			return;
		}
		Screen s = switch (which) {
			case "storyteller" -> new StorytellerScreen();
			case "sheets" -> new SheetsScreen();
			case "prompt" -> ClientState.obj(ClientState.view(), "prompt") != null ? new PromptScreen() : new MainScreen();
			default -> new MainScreen();
		};
		mc.gui.setScreen(s);
	}

	/** Nouvelle invite : on l'affiche (sauf si le joueur est déjà dans un autre écran du mod). */
	static void onPromptChanged(JsonObject prompt, boolean sameType) {
		Minecraft mc = Minecraft.getInstance();
		if (prompt == null) {
			if (mc.gui.screen() instanceof PromptScreen) {
				mc.gui.setScreen(null);
			}
			return;
		}
		if (mc.gui.screen() instanceof PromptScreen ps) {
			ps.rebuild();
			return;
		}
		if (mc.gui.screen() == null || mc.gui.screen() instanceof MainScreen) {
			mc.gui.setScreen(new PromptScreen());
		}
	}
}
