package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdToken;

import java.util.List;

/** Choisir un joueur vivant du cercle (nomination, tir). */
public class PickerScreen extends HdScreen {
	private final String commandPrefix;

	public PickerScreen(String title, String commandPrefix) {
		super(title);
		this.commandPrefix = commandPrefix;
	}

	@Override
	protected int[] panelSize() {
		return new int[] {260, 230};
	}

	@Override
	protected void build() {
		List<JsonObject> seats = objects(view(), "seats");
		// Le cercle tient entre le haut du panneau et le bouton « Retour », nom du jeton du bas compris (13 unités) :
		// sur un petit écran, il se resserre au lieu de passer sous le bouton.
		int size = 22;
		int radius = Math.max(40, Math.min(78, (ph - 51 - size) / 2));
		int cx = px + pw / 2;
		int cy = py + 8 + size / 2 + radius;
		int n = seats.size();
		circle(n, cx, cy, radius, (i, x, y) -> {
			JsonObject s = seats.get(i);
			String name = ClientState.str(s, "name");
			boolean alive = ClientState.bool(s, "alive");
			token(new HdToken(x, y, size, name, null, null, alive, false, alive ? () -> {
				if (commandPrefix.startsWith("vc chuchoter")) {
					// Le message s'écrit dans le chat, adressé au joueur choisi.
					minecraft.gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen("/" + commandPrefix + name + " ", false));
				} else {
					send(commandPrefix + name);
					onClose();
				}
			} : null));
		});
		button(px + pw / 2 - 50, py + ph - 26, 100, "Retour", () -> minecraft.gui.setScreen(new MainScreen()));
	}
}
