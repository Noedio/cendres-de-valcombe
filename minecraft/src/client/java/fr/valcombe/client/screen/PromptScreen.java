package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.client.ui.TokenWidget;
import fr.valcombe.client.ui.WaxButton;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;

/** L'invite en cours : choix de nuit, information, vote, relecture du Conteur, grimoire du Mouchard. */
public class PromptScreen extends BaseScreen {
	private static String voteKey = "";
	private static long voteStart;

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

	/** Appelé par le routeur quand l'invite change sans changer d'écran. */
	public void rebuild() {
		rebuildWidgets();
	}

	@Override
	protected int[] panelSize() {
		return switch (type()) {
			case "choose", "grimoire" -> new int[] {300, 250};
			case "confirm" -> new int[] {300, 180};
			default -> new int[] {270, 170};
		};
	}

	@Override
	protected void init() {
		JsonObject p = prompt();
		String title = switch (type()) {
			case "choose", "info", "confirm" -> ClientState.str(p, "title");
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
				circle(opts.size(), cx, py + 108, 82, (i, x, y) -> {
					JsonObject o = opts.get(i);
					String name = ClientState.str(o, "name");
					token(new TokenWidget(x, y, 24, name, null, null, ClientState.bool(o, "alive"),
						ClientState.bool(o, "selected"), () -> send("vc choisir " + name)));
				});
				WaxButton ok = button(cx - 55, py + ph - 26, 110, "Valider (" + chosen + "/" + count + ")",
					() -> send("vc valider"));
				if (chosen != count) {
					ok.disabled();
				}
			}
			case "info" -> button(cx - 60, py + ph - 26, 120, "Je me rendors", () -> send("vc dormir"));
			case "grimoire" -> {
				Grimoire.place(this, cx, py + 112, 84, 24, null);
				button(cx - 60, py + ph - 26, 120, "Je me rendors", () -> send("vc dormir"));
			}
			case "vote" -> {
				String nominee = ClientState.str(p, "nominee");
				if (!nominee.equals(voteKey)) {
					voteKey = nominee;
					voteStart = System.currentTimeMillis();
				}
				token(new TokenWidget(cx, py + 50, 40, nominee, null, null, true, false, null));
				button(cx - 118, py + ph - 26, 112, "Je lève la main", () -> send("vc vote oui"));
				button(cx + 6, py + ph - 26, 112, "Je m'abstiens", () -> send("vc vote non"));
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
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject p = prompt();
		if (p == null) {
			Theme.ink(g, font, "Rien ne t'attend pour l'instant.", px + 16, py + 20, Theme.INK);
			return;
		}
		int x = px + 16;
		switch (type()) {
			case "choose" -> {
				int count = ClientState.num(p, "count", 1);
				Theme.centered(g, font, "Désigne " + count + " joueur" + (count > 1 ? "s" : "") + ".", px + pw / 2, py + 104,
					Theme.WINE);
			}
			case "info" -> {
				JsonObject role = ClientState.obj(view(), "role");
				if (role != null) {
					Theme.token(g, x + 16, py + 34, 32, ClientState.str(role, "id"), ClientState.str(role, "team"),
						true, false, false);
				}
				Theme.paragraph(g, font, ClientState.str(p, "text"), x + 40, py + 22, pw - 72, Theme.INK);
			}
			case "vote" -> {
				JsonObject vote = ClientState.obj(view(), "vote");
				Theme.centered(g, font, "Exécuter " + ClientState.str(p, "nominee") + " ?", px + pw / 2, py + 84, Theme.WINE);
				if (ClientState.bool(p, "ghost")) {
					Theme.centered(g, font, "Tu es mort : ce sera ton unique vote.", px + pw / 2, py + 96, Theme.ASH);
				}
				int seconds = ClientState.num(vote, "seconds", 20);
				int left = Math.max(0, seconds - (int) ((System.currentTimeMillis() - voteStart) / 1000));
				Theme.image(g, Theme.tex("hourglass"), px + pw / 2 - 22, py + 108, 12, 12, 16, 16);
				Theme.ink(g, font, left + " s", px + pw / 2 - 6, py + 110, Theme.INK);
				List<String> hands = strings(vote, "hands");
				Theme.centered(g, font, "Mains levées : " + hands.size(), px + pw / 2, py + 124, Theme.ASH);
			}
			case "confirm" -> {
				Theme.ink(g, font, "Pour " + ClientState.str(p, "to") + " :", x, py + 16, Theme.WINE);
				int h = Theme.paragraph(g, font, ClientState.str(p, "text"), x, py + 30, pw - 32, Theme.INK);
				String truth = ClientState.str(p, "truth");
				if (truth != null) {
					Theme.paragraph(g, font, "Vérité : " + truth, x, py + 36 + h, pw - 32, Theme.BLOOD);
				}
			}
			default -> {
			}
		}
	}
}
