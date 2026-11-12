package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Toutes les durées de la partie, le mode manuel, et le minuteur en cours (passer, prolonger, arrêter). */
public class DurationsScreen extends BaseScreen {
	/** {libellé, clé de réglage, pas, valeur affichée (calculée), unité}. */
	private record Row(String label, String key, int step, int value, String shown) {
	}

	public DurationsScreen() {
		super("Durées et minuteurs");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {340, 250};
	}

	private Row[] rows() {
		JsonObject st = ClientState.obj(view(), "settings");
		int disc = ClientState.num(st, "discussionMinutes", 0);
		int council = ClientState.num(st, "councilSeconds", 0);
		int defense = ClientState.num(st, "defenseSeconds", 20);
		int clock = (int) Math.round(ClientState.dbl(st, "clockSeconds", 1.5) * 10);
		int pause = (int) Math.round(ClientState.dbl(st, "stepDelaySeconds", 1.5));
		int speech = ClientState.num(st, "speechSeconds", 45);
		int dusk = ClientState.num(st, "duskSeconds", 10);
		return new Row[] {
			new Row("Discussion libre", "discussion_minutes", 1, disc, disc > 0 ? disc + " min" : "aucune"),
			new Row("Conseil (accusations)", "duree_conseil", 30, council, council > 0 ? clockText(council) : "à la main"),
			new Row("Défense (sans Conseil)", "temps_defense", 5, defense, defense + " s"),
			new Row("Aiguille du vote", "temps_horloge", 5, clock, clock / 10.0 + " s par trône"),
			new Row("Pause entre étapes", "pause_etapes", 1, pause, pause + " s"),
			new Row("Temps de parole", "parole_duree", 15, speech, speech + " s"),
			new Row("Coucher auto (sans Conteur)", "coucher", 5, dusk, dusk + " s"),
		};
	}

	private static String clockText(int s) {
		return s / 60 + ":" + String.format("%02d", s % 60);
	}

	private void set(Row r, int value) {
		if (r.key().equals("parole_duree")) {
			send("vc parole duree " + Math.max(15, Math.min(180, value)));
		} else {
			send("vc reglage " + r.key() + " " + Math.max(0, value));
		}
	}

	@Override
	protected void build() {
		JsonObject v = view();
		JsonObject st = ClientState.obj(v, "settings");
		int y = py + 12;
		boolean manual = ClientState.bool(st, "manual");
		boolean autoNight = ClientState.bool(st, "autoNight");
		int half = (pw - 34) / 2;
		button(px + 14, y, half, "Mode manuel : " + (manual ? "oui" : "non"),
			() -> send("vc reglage manuel " + (manual ? "non" : "oui")));
		button(px + 20 + half, y, half, "Nuit auto (sans Conteur) : " + (autoNight ? "oui" : "non"),
			() -> send("vc reglage nuit_auto " + (autoNight ? "non" : "oui")));
		y += 24;
		for (Row r : rows()) {
			button(px + pw - 70, y, 24, "−", () -> set(r, r.value() - r.step()));
			button(px + pw - 40, y, 24, "+", () -> set(r, r.value() + r.step()));
			y += 20;
		}
		JsonObject timer = ClientState.obj(v, "timer");
		if (timer != null) {
			int by = py + ph - 52;
			int bw = (pw - 36) / 3;
			button(px + 12, by, bw, "Passer", () -> send("vc minuteur passer"));
			button(px + 18 + bw, by, bw, "+30 s", () -> send("vc minuteur prolonger 30"));
			button(px + 24 + 2 * bw, by, bw, "Arrêter", () -> send("vc minuteur arreter"));
		}
		button(px + pw / 2 - 60, py + ph - 26, 120, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int y = py + 12 + 24 + 6;
		for (Row r : rows()) {
			g.text(font, r.label(), px + 14, y, Theme.INK, false);
			String shown = r.shown();
			g.text(font, shown, px + pw - 78 - font.width(shown), y, Theme.INK, false);
			y += 20;
		}
		JsonObject timer = ClientState.obj(view(), "timer");
		if (timer != null) {
			String kind = ClientState.str(timer, "kind");
			String text = "Minuteur en cours : " + kind + ", " + (ClientState.bool(timer, "held")
				? "attend le Conteur" : clockText(ClientState.num(timer, "left", 0)));
			Theme.centered(g, font, text, px + pw / 2, py + ph - 64, Theme.INK);
		}
	}
}
