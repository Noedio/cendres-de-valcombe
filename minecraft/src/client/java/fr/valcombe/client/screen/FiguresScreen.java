package fr.valcombe.client.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Figures neutres : des règles du Conteur, connues de tous, à cocher avant la partie. */
public class FiguresScreen extends BaseScreen {
	public FiguresScreen() {
		super("Figures neutres");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {340, 220};
	}

	@Override
	protected void build() {
		var list = view().getAsJsonArray("figures");
		int y = py + 30;
		if (list != null) {
			for (JsonElement e : list) {
				JsonObject f = e.getAsJsonObject();
				boolean on = ClientState.bool(f, "active");
				String id = ClientState.str(f, "id");
				button(px + 14, y, 150, ClientState.str(f, "nom") + " : " + (on ? "oui" : "non"),
					() -> send("vc figure " + id + " " + (on ? "non" : "oui")));
				y += 48;
			}
		}
		button(px + pw - 94, py + ph - 26, 80, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Theme.ink(g, font, "Annoncées à tous au début de la partie.", px + 14, py + 14, Theme.ASH);
		var list = view().getAsJsonArray("figures");
		int y = py + 30;
		if (list != null) {
			for (JsonElement e : list) {
				Theme.paragraph(g, font, ClientState.str(e.getAsJsonObject(), "texte"), px + 172, y, pw - 186, Theme.INK);
				y += 48;
			}
		}
	}
}
