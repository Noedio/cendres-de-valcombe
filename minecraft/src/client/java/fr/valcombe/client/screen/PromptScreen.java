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
import fr.valcombe.client.ui.Theme;

import java.util.List;

import net.minecraft.client.gui.screens.ChatScreen;

/** L'invite en cours : choix de nuit, information, vote, relecture du Conteur, grimoire du Mouchard. */
public class PromptScreen extends HdScreen {
	/** Place des noms au-dessus et au-dessous d'un cercle de jetons, en unités. */
	private static final int LABEL = 13;

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

	/** Rayon du cercle de vote, réduit pour laisser la place au texte et au bouton sur un petit écran. */
	private int voteRadius() {
		return Math.max(48, Math.min(82, (ph - 100) / 2));
	}

	/**
	 * Demi-hauteur d'un cercle de jetons de cette taille qui tient entre le haut du panneau et le bouton du bas, nom
	 * du jeton du bas compris : sur un petit écran, le cercle se resserre au lieu de passer sous le bouton.
	 */
	private int fitRadius(int size, int max) {
		return Math.max(40, Math.min(max, (ph - 38 - size - LABEL) / 2));
	}

	/** Centre vertical du cercle de fitRadius. */
	private int fitCenter(int size, int radius) {
		return py + 8 + size / 2 + radius;
	}

	/** Appelé par le routeur quand l'invite change sans changer d'écran. */
	public void rebuild() {
		rebuildWidgets();
	}

	@Override
	protected int[] panelSize() {
		return switch (type()) {
			case "choose", "grimoire", "vote" -> new int[] {300, 250};
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
		if (p == null) {
			return;
		}
		int cx = px + pw / 2;
		switch (type()) {
			case "choose" -> {
				List<JsonObject> opts = objects(p, "options");
				int count = ClientState.num(p, "count", 1);
				long chosen = opts.stream().filter(o -> ClientState.bool(o, "selected")).count();
				// Une ellipse plutôt qu'un cercle : plus large que haute, elle garde de la place entre les jetons.
				int size = 24;
				int ry = fitRadius(size, 82);
				int rx = Math.max(ry, Math.min(110, pw / 2 - size / 2 - 40));
				int n = opts.size();
				ellipse(n, cx, fitCenter(size, ry), rx, ry, (i, x, y) -> {
					JsonObject o = opts.get(i);
					String name = ClientState.str(o, "name");
					token(new HdToken(x, y, size, name, null, null, ClientState.bool(o, "alive"),
						ClientState.bool(o, "selected"), () -> send("vc choisir " + name)));
				});
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
				int r = fitRadius(24, 84);
				Grimoire.place(this, cx, fitCenter(24, r), r, 24, null);
				button(cx - 60, py + ph - 26, 120, "Je me rendors", () -> send("vc dormir"));
			}
			case "vote" -> {
				// Le cercle : les mains déjà comptées par l'aiguille sont entourées d'or.
				JsonObject vote = ClientState.obj(view(), "vote");
				List<JsonObject> seats = objects(view(), "seats");
				List<String> locked = strings(vote, "locked");
				int vr = voteRadius();
				circle(seats.size(), cx, py + 34 + vr, vr, (i, x, y) -> {
					JsonObject s = seats.get(i);
					token(new HdToken(x, y, 22, ClientState.str(s, "name"), null, null, ClientState.bool(s, "alive"),
						locked.contains(String.valueOf(i)), null));
				});
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
				int count = ClientState.num(p, "count", 1);
				int cy = fitCenter(24, fitRadius(24, 82));
				int ry = fitRadius(24, 82);
				int rx = Math.max(ry, Math.min(110, pw / 2 - 12 - 40));
				int cx = hd.px(px + pw / 2);
				hd.textCentered(HdStyle.FORT, "Désigne " + count + " joueur" + (count > 1 ? "s" : "") + ".", cx, hd.px(cy - 12), Hd.WINE);
				if (ClientState.bool(p, "optional")) {
					// Pouvoir à usage unique : valider sans personne le garde pour une autre nuit.
					List<String> lines = hd.wrap(dense, "Une seule fois : valide sans personne pour le garder.", hd.px(2 * rx - 48));
					for (int i = 0; i < Math.min(3, lines.size()); i++) {
						hd.textCentered(dense, lines.get(i), cx, hd.px(cy + 3) + i * pitch, Hd.INK_SOFT);
					}
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
		int cx = hd.px(px + pw / 2);
		int vr = voteRadius();
		int cy = hd.px(py + 34 + vr);
		int n = objects(view(), "seats").size();
		// L'aiguille : un trait du centre vers le siège en cours, doré à la pointe.
		int seat = ClientState.num(vote, "clockSeat", -1);
		if (seat >= 0 && n > 0) {
			double a = -Math.PI / 2 + 2 * Math.PI * seat / n;
			double c = Math.cos(a);
			double s = Math.sin(a);
			int t = Math.max(2, hd.u(1.5));
			double mid = hd.u(vr - 30);
			double tip = hd.u(vr - 18);
			HdGlyphs.line(hd, cx + c * hd.u(5), cy + s * hd.u(5), cx + c * mid, cy + s * mid, t, Theme.WINE);
			HdGlyphs.line(hd, cx + c * mid, cy + s * mid, cx + c * tip, cy + s * tip, t, Theme.GOLD);
		}
		hd.disc(cx, cy, hd.u(3.5), Theme.BRONZE);
		String question = hd.ellipsize(HdStyle.FORT, "Exécuter " + ClientState.str(p, "nominee") + " ?", hd.px(pw - 28));
		hd.textCentered(HdStyle.FORT, question, cx, hd.px(py + 9), Hd.WINE);
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
		hd.textCentered(st, hint, cx, hd.px(py + ph - 41), Hd.INK_SOFT);
	}
}
