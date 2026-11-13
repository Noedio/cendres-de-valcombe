package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdFonts;
import fr.valcombe.client.hd.HdGlyphs;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdText;
import fr.valcombe.client.hd.HdToken;
import fr.valcombe.client.hd.HdTokenArt;
import fr.valcombe.client.hd.RingLayout;
import fr.valcombe.client.hd.SeatRing;
import fr.valcombe.client.ui.Theme;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.screens.ChatScreen;

/** L'invite en cours : choix de nuit, information, vote, relecture du Conteur, grimoire du Mouchard. */
public class PromptScreen extends HdScreen {
	/** Cercle posé par build() (choix de nuit, vote), repris par drawHd() pour le texte du centre et l'aiguille. */
	private RingLayout.Result ring;

	public PromptScreen() {
		super("Valcombe");
	}

	private static JsonObject prompt() {
		return ClientState.obj(view(), "prompt");
	}

	private static String type() {
		String t = ClientState.str(prompt(), "type");
		return t == null ? "" : t;
	}

	/** Haut de la zone du cercle de vote : sous la question « Exécuter X ? ». */
	private int voteTop() {
		return py + 24;
	}

	/** Bas de la zone du cercle de vote : au-dessus de l'état de ta main et du bouton. */
	private int voteBottom() {
		return py + ph - 46;
	}

	/**
	 * Cercle des joueurs de l'invite (choix de nuit, vote), dans la zone (x, y, w, h en unités), noms dehors : le
	 * centre porte la consigne ou l'aiguille du vote.
	 */
	private RingLayout.Result ringOf(List<String> names, int x, int y, int w, int h) {
		return SeatRing.layout(names, SeatRing.spec(x, y, w, h).tokens(26, 14).outsideOnly());
	}

	/**
	 * Largeur (unités) d'un texte de hauteur h posé au centre du cercle sans toucher les jetons : la corde de l'ellipse
	 * à mi-hauteur du texte, moins les jetons et une marge.
	 */
	private static int innerWidth(RingLayout.Result r, int h) {
		double dy = Math.min(r.ry(), h / 2.0);
		double half = r.rx() * Math.sqrt(Math.max(0, 1 - dy * dy / (r.ry() * r.ry()))) - r.size() / 2.0 - 6;
		return (int) Math.max(40, Math.min(190, 2 * half));
	}

	/** Appelé par le routeur quand l'invite change sans changer d'écran. */
	public void rebuild() {
		rebuildWidgets();
	}

	@Override
	protected int[] panelSize() {
		return switch (type()) {
			case "choose", "grimoire", "vote" -> new int[] {340, 260};
			case "confirm" -> new int[] {300, 180};
			case "decide" -> new int[] {300, 230};
			default -> new int[] {270, 170};
		};
	}

	@Override
	protected void init() {
		JsonObject p = prompt();
		String title = switch (type()) {
			case "choose", "info", "confirm", "decide" -> ClientState.str(p, "title");
			case "vote" -> "Le village vote";
			case "grimoire" -> "Le grimoire";
			default -> "Valcombe";
		};
		setTitleText(title == null ? "Valcombe" : title);
		super.init();
	}

	private void setTitleText(String t) {
		this.titleText = t;
	}

	private String titleText = "Valcombe";

	@Override
	public net.minecraft.network.chat.Component getTitle() {
		return net.minecraft.network.chat.Component.literal(titleText);
	}

	@Override
	protected void build() {
		JsonObject p = prompt();
		ring = null;
		if (p == null) {
			return;
		}
		int cx = px + pw / 2;
		switch (type()) {
			case "choose" -> {
				List<JsonObject> opts = objects(p, "options");
				int count = ClientState.num(p, "count", 1);
				long chosen = opts.stream().filter(o -> ClientState.bool(o, "selected")).count();
				// Les noms vont dehors : le centre de l'ellipse porte la consigne.
				List<String> names = new ArrayList<>();
				for (JsonObject o : opts) {
					String name = ClientState.str(o, "name");
					names.add(name == null ? "" : name);
				}
				ring = ringOf(names, px + 8, py + 10, pw - 16, ph - 42);
				for (int i = 0; i < opts.size(); i++) {
					JsonObject o = opts.get(i);
					String name = names.get(i);
					RingLayout.Seat seat = ring.seats().get(i);
					token(new HdToken(seat.cx(), seat.cy(), ring.size(), name, null, null, ClientState.bool(o, "alive"),
						ClientState.bool(o, "selected"), () -> send("vc choisir " + name)).seat(seat));
				}
				HdButton ok = button(cx - 55, py + ph - 26, 110, "Valider (" + chosen + "/" + count + ")",
					() -> send("vc valider"));
				if (chosen != count) {
					ok.disabled();
				}
			}
			case "info" -> {
				// Ce que l'on apprend la nuit est noté dans le carnet (onglet Indices).
				String phase = ClientState.phase().equals("NIGHT") ? "Nuit " + ClientState.num(view(), "night", 0) + " : " : "";
				NotebookScreen.addClue(phase + ClientState.str(p, "title") + " : " + ClientState.str(p, "text"));
				button(cx - 60, py + ph - 26, 120, "Je me rendors", () -> send("vc dormir"));
			}
			case "grimoire" -> {
				// Le grimoire montré au Mouchard : mêmes jetons qu'au pupitre du Conteur (rôles, marqueurs, infobulles).
				for (HdToken t : Grimoire.hdTokens(px + 8, py + 10, pw - 16, ph - 42, 26, 14, null)) {
					token(t);
				}
				button(cx - 60, py + ph - 26, 120, "Je me rendors", () -> send("vc dormir"));
			}
			case "vote" -> {
				// Le cercle : les mains déjà comptées par l'aiguille sont entourées d'or ; les noms dehors, l'aiguille et
				// le compte au centre.
				JsonObject vote = ClientState.obj(view(), "vote");
				List<JsonObject> seats = objects(view(), "seats");
				List<String> locked = strings(vote, "locked");
				List<String> names = new ArrayList<>();
				for (JsonObject s : seats) {
					String name = ClientState.str(s, "name");
					names.add(name == null ? "" : name);
				}
				ring = ringOf(names, px + 8, voteTop(), pw - 16, voteBottom() - voteTop());
				for (int i = 0; i < seats.size(); i++) {
					RingLayout.Seat seat = ring.seats().get(i);
					token(new HdToken(seat.cx(), seat.cy(), ring.size(), names.get(i), null, null,
						ClientState.bool(seats.get(i), "alive"), locked.contains(String.valueOf(i)), null).seat(seat));
				}
				boolean up = ClientState.bool(vote, "myHand");
				button(cx - 70, py + ph - 26, 140, up ? "Baisser la main" : "Lever la main",
					() -> send(up ? "vc vote non" : "vc vote oui"));
			}
			case "decide" -> {
				List<String> options = strings(p, "options");
				int cols = options.size() > 6 ? 2 : 1;
				int bw = cols == 2 ? 132 : 200;
				for (int i = 0; i < options.size(); i++) {
					int choice = i;
					int col = cols == 2 ? i % 2 : 0;
					int row = cols == 2 ? i / 2 : i;
					int bx = cols == 2 ? cx - bw - 3 + col * (bw + 6) : cx - bw / 2;
					// Une option trop longue pour son bouton se lit en entier dans l'infobulle.
					boolean cut = HdFonts.width(HdStyle.BOUTON, Hd.guiScale(), options.get(i)) > (bw - 10) * Hd.guiScale();
					button(bx, py + 52 + row * 21, bw, options.get(i), () -> send("vc decider " + choice))
						.tooltip(cut ? options.get(i) : null);
				}
			}
			case "confirm" -> {
				String text = ClientState.str(p, "text");
				button(cx - 118, py + ph - 26, 112, "Envoyer", () -> send("vc envoyer"));
				button(cx + 6, py + ph - 26, 112, "Modifier",
					() -> minecraft.gui.setScreen(new ChatScreen("/vc envoyer " + text, false)));
			}
			default -> {
			}
		}
	}

	@Override
	protected void drawHd(Hd hd) {
		JsonObject p = prompt();
		HdStyle dense = HdText.SERRE;
		int pitch = hd.u(HdText.PAS);
		if (p == null) {
			hd.text(HdStyle.TEXTE, "Rien ne t'attend pour l'instant.", hd.px(px + 16), hd.px(py + 18), Hd.INK);
			return;
		}
		int x = px + 16;
		int bottom = hd.px(py + ph - 30);
		switch (type()) {
			case "choose" -> {
				// Au centre de l'ellipse : combien désigner, et la règle d'un pouvoir à usage unique.
				if (ring == null) {
					return;
				}
				int count = ClientState.num(p, "count", 1);
				// Le bloc (consigne, puis la règle d'un pouvoir à usage unique) est centré dans l'ellipse, à la largeur
				// que les jetons lui laissent.
				int width = hd.px(innerWidth(ring, 46));
				String head = hd.ellipsize(HdStyle.FORT, "Désigne " + count + " joueur" + (count > 1 ? "s" : "") + ".", width);
				List<String> lines = ClientState.bool(p, "optional")
					? hd.wrap(dense, "Une seule fois : valide sans personne pour le garder.", width) : List.of();
				int shown = Math.min(3, lines.size());
				int blockH = hd.lineHeight(HdStyle.FORT) + (shown > 0 ? hd.u(3) + shown * pitch : 0);
				int cx = hd.u(ring.cx());
				int top = hd.u(ring.cy()) - blockH / 2;
				hd.textCentered(HdStyle.FORT, head, cx, top, Hd.WINE);
				for (int i = 0; i < shown; i++) {
					// Pouvoir à usage unique : valider sans personne le garde pour une autre nuit.
					hd.textCentered(dense, lines.get(i), cx, top + hd.lineHeight(HdStyle.FORT) + hd.u(3) + i * pitch, Hd.INK_SOFT);
				}
			}
			case "info" -> {
				JsonObject role = ClientState.obj(view(), "role");
				if (role != null) {
					HdTokenArt.draw(hd, hd.px(x), hd.px(py + 18), hd.u(32), ClientState.str(role, "id"), ClientState.str(role, "team"),
						true, false, false);
				}
				HdText.paragraph(hd, HdStyle.TEXTE, ClientState.str(p, "text"), hd.px(x + 40), hd.px(py + 18), hd.px(pw - 72), Hd.INK,
					hd.u(13), bottom - hd.px(py + 18));
			}
			case "vote" -> drawVote(hd, p);
			case "decide" -> HdText.paragraph(hd, dense, ClientState.str(p, "question"), hd.px(x), hd.px(py + 13), hd.px(pw - 32),
				Hd.WINE, pitch, hd.px(38));
			case "confirm" -> {
				hd.text(HdStyle.FORT, "Pour " + ClientState.str(p, "to") + " :", hd.px(x), hd.px(py + 12), Hd.WINE);
				int top = hd.px(py + 28);
				String truth = ClientState.str(p, "truth");
				// Le message, puis la vérité (pour le Conteur) : chacun coupé avant les boutons.
				int truthRoom = truth == null ? 0 : Math.min(3, HdText.lines(hd, dense, "Vérité : " + truth, hd.px(pw - 32))) * pitch
					+ hd.u(6);
				int h = HdText.paragraph(hd, dense, ClientState.str(p, "text"), hd.px(x), top, hd.px(pw - 32), Hd.INK, pitch,
					bottom - top - truthRoom);
				if (truth != null) {
					HdText.paragraph(hd, dense, "Vérité : " + truth, hd.px(x), top + h + hd.u(6), hd.px(pw - 32), Theme.BLOOD, pitch,
						bottom - top - h - hd.u(6));
				}
			}
			default -> {
			}
		}
	}

	/** Le vote : la question, l'aiguille qui va du centre au siège en cours, le compte des voix et l'état de ta main. */
	private void drawVote(Hd hd, JsonObject p) {
		JsonObject vote = ClientState.obj(view(), "vote");
		String question = hd.ellipsize(HdStyle.FORT, "Exécuter " + ClientState.str(p, "nominee") + " ?", hd.px(pw - 28));
		hd.textCentered(HdStyle.FORT, question, hd.px(px + pw / 2), hd.px(py + 9), Hd.WINE);
		if (ring == null) {
			return;
		}
		int cx = hd.u(ring.cx());
		int cy = hd.u(ring.cy());
		// L'aiguille : un trait du centre vers le jeton du siège en cours, doré à la pointe, arrêté avant le jeton.
		int seat = ClientState.num(vote, "clockSeat", -1);
		if (seat >= 0 && seat < ring.seats().size()) {
			RingLayout.Seat at = ring.seats().get(seat);
			double dx = at.cx() - ring.cx();
			double dy = at.cy() - ring.cy();
			double len = Math.hypot(dx, dy);
			if (len > ring.size()) {
				double c = dx / len;
				double s = dy / len;
				int t = Math.max(2, hd.u(1.5));
				double tip = hd.u(len - ring.size() / 2.0 - 4);
				double mid = hd.u(Math.max(6, len - ring.size() / 2.0 - 16));
				HdGlyphs.line(hd, cx + c * hd.u(5), cy + s * hd.u(5), cx + c * mid, cy + s * mid, t, Theme.WINE);
				HdGlyphs.line(hd, cx + c * mid, cy + s * mid, cx + c * tip, cy + s * tip, t, Theme.GOLD);
			}
		}
		hd.disc(cx, cy, hd.u(3.5), Theme.BRONZE);
		// Le compte, sur un cartouche : lisible même quand l'aiguille passe dessous.
		String count = ClientState.num(vote, "count", 0) + " voix / seuil " + ClientState.num(vote, "threshold", 0);
		HdStyle st = HdText.SERRE;
		int cw = hd.width(st, count) / 2 + hd.u(6);
		int top = cy + hd.u(7);
		int h = hd.lineHeight(st) + hd.u(1);
		int b = Math.max(1, hd.u(0.75));
		hd.fill(cx - cw, top, cx + cw, top + h, Theme.PARCH);
		hd.frame(cx - cw, top, 2 * cw, h, b, Theme.BRONZE);
		hd.textCentered(st, count, cx, top + (h - hd.lineHeight(st)) / 2, Hd.INK);
		String hint = ClientState.bool(vote, "myHand") ? "Ta main est levée." : "Ta main est baissée.";
		if (ClientState.bool(p, "ghost")) {
			hint += " (vote de fantôme)";
		}
		hd.textCentered(st, hint, hd.px(px + pw / 2), hd.px(py + ph - 41), Hd.INK_SOFT);
	}
}
