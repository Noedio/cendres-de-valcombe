package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdToken;
import fr.valcombe.client.hd.RingLayout;
import fr.valcombe.client.hd.SeatRing;

import java.util.ArrayList;
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
		return new int[] {340, 260};
	}

	@Override
	protected void build() {
		List<JsonObject> seats = objects(view(), "seats");
		List<String> names = new ArrayList<>();
		for (JsonObject s : seats) {
			String name = ClientState.str(s, "name");
			names.add(name == null ? "" : name);
		}
		// Le cercle tient entre le haut du panneau et le bouton « Retour », noms compris, sans qu'aucun nom n'en
		// chevauche un autre (RingLayout) : sur un petit écran, il se resserre et raccourcit les noms au lieu de
		// passer sous le bouton.
		RingLayout.Result ring = SeatRing.layout(names, SeatRing.spec(px + 8, py + 10, pw - 16, ph - 42).tokens(26, 14));
		for (int i = 0; i < seats.size(); i++) {
			JsonObject s = seats.get(i);
			String name = names.get(i);
			boolean alive = ClientState.bool(s, "alive");
			RingLayout.Seat seat = ring.seats().get(i);
			token(new HdToken(seat.cx(), seat.cy(), ring.size(), name, null, null, alive, false, alive ? () -> {
				if (commandPrefix.startsWith("vc chuchoter")) {
					// Le message s'écrit dans le chat, adressé au joueur choisi.
					minecraft.gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen("/" + commandPrefix + name + " ", false));
				} else {
					send(commandPrefix + name);
					onClose();
				}
			} : null).seat(seat));
		}
		button(px + pw / 2 - 50, py + ph - 26, 100, "Retour", () -> minecraft.gui.setScreen(new MainScreen()));
	}
}
