package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.TokenWidget;

import java.util.List;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Le grimoire en cercle : rôles, marqueurs, infobulle détaillée. */
final class Grimoire {
	private Grimoire() {
	}

	static void place(BaseScreen screen, int cx, int cy, int radius, int size,
			java.util.function.Consumer<String> onClick) {
		JsonObject v = ClientState.view();
		List<JsonObject> seats = BaseScreen.objects(v, "seats");
		List<JsonObject> grim = BaseScreen.objects(v, "grimoire");
		if (grim.size() != seats.size()) {
			return;
		}
		BaseScreen.circle(seats.size(), cx, cy, radius, (i, x, y) -> {
			JsonObject s = seats.get(i);
			JsonObject row = grim.get(i);
			JsonObject role = ClientState.obj(row, "role");
			JsonObject believed = ClientState.obj(row, "believed");
			String name = ClientState.str(s, "name");
			TokenWidget t = new TokenWidget(x, y, size, name,
				ClientState.str(role, "id"), ClientState.str(role, "team"), ClientState.bool(s, "alive"), false,
				onClick == null ? null : () -> onClick.accept(name));
			if (ClientState.bool(row, "poisoned")) {
				t.marker("poison");
			}
			if (ClientState.bool(row, "protected")) {
				t.marker("protect");
			}
			if (believed != null) {
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
			t.setTooltip(Tooltip.create(Component.literal(ClientState.str(row, "line") + (note != null ? "\nNote : " + note : ""))));
			screen.token(t);
		});
	}
}
