package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.TokenWidget;

import java.util.List;

/** Choisir un joueur vivant du cercle (nomination, tir). */
public class PickerScreen extends BaseScreen {
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
		circle(seats.size(), px + pw / 2, py + 100, 78, (i, x, y) -> {
			JsonObject s = seats.get(i);
			String name = ClientState.str(s, "name");
			boolean alive = ClientState.bool(s, "alive");
			token(new TokenWidget(x, y, 22, name, null, null, alive, false, alive ? () -> {
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
