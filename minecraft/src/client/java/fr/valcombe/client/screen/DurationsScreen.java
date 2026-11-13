package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;

/**
 * Toutes les durées de la partie, le mode manuel, et le minuteur en cours (passer, prolonger, arrêter).
 * En haute résolution : un tableau (durée, valeur, boutons « − » et « + ») dont les lignes se resserrent pour
 * laisser la place au minuteur quand il tourne.
 */
public class DurationsScreen extends HdScreen {
	/** {libellé, clé de réglage, pas, valeur affichée (calculée), unité}. */
	private record Row(String label, String key, int step, int value, String shown) {
	}

	/** Haut du tableau, sous les deux interrupteurs (unités depuis py). */
	private static final int TABLE_TOP = 34;

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

	private static JsonObject timer() {
		return ClientState.obj(view(), "timer");
	}

	/** Bas du tableau : au-dessus de la ligne du minuteur s'il tourne, sinon au-dessus de « Retour ». */
	private int tableBottom() {
		return py + ph - 26 - 6 - (timer() != null ? 16 : 0);
	}

	/** Pas entre deux lignes du tableau (17 à 20 unités). */
	private int rowStep() {
		return Math.max(17, Math.min(20, (tableBottom() - py - TABLE_TOP) / rows().length));
	}

	@Override
	protected void build() {
		JsonObject st = ClientState.obj(view(), "settings");
		int y = py + 10;
		boolean manual = ClientState.bool(st, "manual");
		boolean autoNight = ClientState.bool(st, "autoNight");
		int half = (pw - 30) / 2;
		HdStyle small = HdStyle.BOUTON.size(10);
		toggle(px + 12, y, half, "Mode manuel", manual, () -> send("vc reglage manuel " + (manual ? "non" : "oui")))
			.style(small)
			.tooltip("Oui (avec un Conteur humain) : aucun minuteur n'enchaîne seul. Le Conteur ouvre et clôt le Conseil, "
				+ "lance chaque vote et appelle chaque rôle la nuit.");
		toggle(px + pw - 12 - half, y, half, "Nuit auto (sans Conteur)", autoNight,
			() -> send("vc reglage nuit_auto " + (autoNight ? "non" : "oui")))
			.style(small)
			.tooltip("Oui : sans Conteur humain, la nuit tombe d'elle-même, au début de la partie et après chaque coucher "
				+ "du soleil.");
		int step = rowStep();
		int h = Math.min(18, step - 2);
		y = py + TABLE_TOP;
		for (Row r : rows()) {
			int by = y + (step - h) / 2;
			button(px + pw - 68, by, 24, h, "−", () -> set(r, r.value() - r.step()));
			button(px + pw - 40, by, 24, h, "+", () -> set(r, r.value() + r.step()));
			y += step;
		}
		if (timer() != null) {
			// Minuteur en cours : ses trois commandes partagent la rangée du bas avec « Retour ».
			int gap = 6;
			int bw = (pw - 24 - 3 * gap) / 4;
			int bx = px + 12;
			button(bx, py + ph - 26, bw, "Passer", () -> send("vc minuteur passer"));
			button(bx + (bw + gap), py + ph - 26, bw, "+30 s", () -> send("vc minuteur prolonger 30"));
			button(bx + 2 * (bw + gap), py + ph - 26, bw, "Arrêter", () -> send("vc minuteur arreter"));
			button(px + pw - 12 - bw, py + ph - 26, bw, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
		} else {
			button(px + pw / 2 - 60, py + ph - 26, 120, "Retour", () -> minecraft.gui.setScreen(new StorytellerScreen()));
		}
	}

	/** Bouton de hauteur choisie (les « − » et « + » du tableau se resserrent avec lui). */
	private HdButton button(int x, int y, int w, int h, String label, Runnable action) {
		return addRenderableWidget(new HdButton(x, y, w, h, label, action));
	}

	@Override
	protected void drawHd(Hd hd) {
		int step = rowStep();
		int x = px + 12;
		int w = pw - 24;
		int y = py + TABLE_TOP;
		Row[] rows = rows();
		for (int i = 0; i < rows.length; i++) {
			Row r = rows[i];
			StorytellerKit.band(hd, x, y, w, step, i);
			hd.textMiddle(HdStyle.TEXTE, r.label(), hd.px(x + 4), hd.px(y), hd.px(step), Hd.INK);
			int vy = hd.px(y) + (hd.px(step) - hd.lineHeight(HdStyle.FORT)) / 2;
			hd.textRight(HdStyle.FORT, r.shown(), hd.px(px + pw - 76), vy, Hd.WINE);
			y += step;
		}
		JsonObject timer = timer();
		if (timer != null) {
			String kind = ClientState.str(timer, "kind");
			String text = "Minuteur en cours : " + kind + ", " + (ClientState.bool(timer, "held")
				? "attend le Conteur" : clockText(ClientState.num(timer, "left", 0)));
			hd.textCentered(HdStyle.FORT, hd.ellipsize(HdStyle.FORT, text, hd.px(w)), hd.px(px + pw / 2),
				hd.px(py + ph - 26 - 4) - hd.lineHeight(HdStyle.FORT), Hd.INK);
		}
	}
}
