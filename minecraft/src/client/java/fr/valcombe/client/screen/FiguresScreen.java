package fr.valcombe.client.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;

/** Figures neutres : des règles du Conteur, connues de tous, à cocher avant la partie. */
public class FiguresScreen extends HdScreen {
	/** Hauteur d'une figure : son interrupteur à gauche, sa règle en trois lignes à droite. */
	private static final int STEP = 48;

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
				// Interrupteur : son message reste « nom : oui/non ».
				toggle(px + 14, y, 150, ClientState.str(f, "nom"), on,
					() -> send("vc figure " + id + " " + (on ? "non" : "oui")));
				y += STEP;
			}
		}
		button(px + pw - 94, py + ph - 26, 80, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
	}

	@Override
	protected void drawHd(Hd hd) {
		hd.text(HdStyle.TEXTE.size(10), "Annoncées à tous au début de la partie.", hd.px(px + 14), hd.px(py + 12), Hd.INK_SOFT);
		var list = view().getAsJsonArray("figures");
		int y = py + 30;
		if (list != null) {
			HdStyle st = HdStyle.TEXTE.size(10);
			for (JsonElement e : list) {
				// La règle, à droite de son interrupteur, coupée à la hauteur d'une rangée.
				hd.paragraph(st, ClientState.str(e.getAsJsonObject(), "texte"), hd.px(px + 172), hd.px(y), hd.px(pw - 186), Hd.INK,
					hd.px(STEP - 4));
				y += STEP;
			}
		}
	}
}
