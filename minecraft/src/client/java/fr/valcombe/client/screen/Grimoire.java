package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.HdToken;
import fr.valcombe.client.hd.RingLayout;
import fr.valcombe.client.hd.SeatRing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.network.chat.Component;

/**
 * Le grimoire en cercle, en haute résolution : rôles, linceuls, marqueurs, noms sur leurs plaques (sans jamais se
 * chevaucher, voir RingLayout) et infobulle détaillée. Pupitre du Conteur et grimoire montré au Mouchard.
 */
final class Grimoire {
	private Grimoire() {
	}

	/**
	 * Les jetons du grimoire placés dans la zone (x, y, w, h en unités) : une ellipse qui l'épouse, les plus grands
	 * jetons qui laissent lire les noms (de max à min unités). Vide si la vue n'a pas de grimoire complet (une ligne
	 * par siège). onClick reçoit le nom du joueur cliqué (null : jetons sans action).
	 */
	static List<HdToken> hdTokens(int x, int y, int w, int h, int max, int min, Consumer<String> onClick) {
		JsonObject v = ClientState.view();
		List<JsonObject> seats = BaseScreen.objects(v, "seats");
		List<JsonObject> grim = BaseScreen.objects(v, "grimoire");
		if (grim.size() != seats.size() || seats.isEmpty()) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		for (JsonObject s : seats) {
			String name = ClientState.str(s, "name");
			names.add(name == null ? "" : name);
		}
		RingLayout.Result ring = SeatRing.layout(names, SeatRing.spec(x, y, w, h).tokens(max, min));
		List<HdToken> out = new ArrayList<>();
		for (int i = 0; i < seats.size(); i++) {
			JsonObject s = seats.get(i);
			JsonObject row = grim.get(i);
			JsonObject role = ClientState.obj(row, "role");
			String name = names.get(i);
			RingLayout.Seat seat = ring.seats().get(i);
			HdToken t = new HdToken(seat.cx(), seat.cy(), ring.size(), name, ClientState.str(role, "id"),
				ClientState.str(role, "team"), ClientState.bool(s, "alive"), false,
				onClick == null ? null : () -> onClick.accept(name)).seat(seat);
			// La narration (et le message du widget) garde le nom entier.
			t.setMessage(Component.literal(name));
			if (ClientState.bool(row, "poisoned")) {
				t.marker("poison");
			}
			if (ClientState.bool(row, "protected")) {
				t.marker("protect");
			}
			if (ClientState.obj(row, "believed") != null) {
				t.marker("drunk");
			}
			if (ClientState.bool(row, "redHerring")) {
				t.marker("herring");
			}
			if (ClientState.bool(row, "master")) {
				t.marker("master");
			}
			if (!ClientState.bool(s, "alive") && ClientState.bool(s, "ghostVote")) {
				t.marker("ghost");
			}
			String note = ClientState.str(row, "note");
			t.tooltip(ClientState.str(row, "line") + (note != null ? "\nNote : " + note : ""));
			out.add(t);
		}
		return out;
	}
}
