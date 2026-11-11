package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Panneau du Conteur : à gauche le grimoire (ou les inscrits), à droite une colonne
 * d'actions puis de réglages, qui se resserre si la fenêtre est petite.
 */
public class StorytellerScreen extends BaseScreen {
	private record Entry(String label, Runnable run, boolean gap) {
	}

	private static final String[] THEMES = {"gothique", "monastere", "port"};
	private static final String[] THEME_NAMES = {"gothique", "monastère", "port"};
	private static int theme;

	public StorytellerScreen() {
		super("Panneau du Conteur");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {400, 290};
	}

	@Override
	protected void build() {
		JsonObject v = view();
		JsonObject st = ClientState.obj(v, "settings");
		boolean lobby = ClientState.phase().equals("LOBBY");
		boolean isSt = ClientState.bool(v, "isStoryteller");

		List<Entry> col = new ArrayList<>();
		if (lobby) {
			col.add(new Entry(isSt ? "Quitter le rôle de Conteur" : "Devenir le Conteur", () -> send("vc conteur"), false));
			col.add(new Entry("Ajouter 1 joueur fictif", () -> send("vc bots 1"), false));
			col.add(new Entry("Ajouter 4 joueurs fictifs", () -> send("vc bots 4"), false));
			col.add(new Entry("Retirer les fictifs", () -> send("vc bots retirer"), false));
			col.add(new Entry("Lancer (automatique)", () -> send("vc lancer auto"), true));
			col.add(new Entry("Lancer (Conteur humain)", () -> send("vc lancer humain"), false));
			col.add(new Entry("Figures neutres…", () -> minecraft.gui.setScreen(new FiguresScreen()), false));
			button(px + 14, py + ph - 48, 150, "Éditer le script", () -> minecraft.gui.setScreen(new ScriptScreen()));
			button(px + 14, py + ph - 26, 104, "Bâtir le village", () -> {
				send("vc carte construire " + THEMES[theme]);
				onClose();
			});
			button(px + 122, py + ph - 26, 104, "Thème : " + THEME_NAMES[theme], () -> {
				theme = (theme + 1) % THEMES.length;
				rebuildWidgets();
			});
		} else {
			col.add(new Entry("Faire tomber la nuit", () -> {
				send("vc nuit");
				onClose();
			}, false));
			col.add(new Entry("Clore la journée", () -> send("vc finjour"), false));
			if (ClientState.phase().equals("NIGHT")) {
				col.add(new Entry("Passer l'étape", () -> send("vc suivant"), false));
			} else {
				boolean open = ClientState.bool(v, "nominationsOpen");
				col.add(new Entry("Rassembler le village", () -> send("vc rassembler"), false));
				col.add(new Entry("Discussion libre (5 min)", () -> send("vc discussion 5"), false));
				col.add(new Entry(open ? "Fermer les nominations" : "Ouvrir les nominations",
					() -> send("vc nominations " + (open ? "fermer" : "ouvrir")), false));
				JsonObject vote = ClientState.obj(v, "vote");
				if (vote != null && "DEFENSE".equals(ClientState.str(vote, "stage"))) {
					col.add(new Entry("Lancer le vote", () -> send("vc voter"), false));
				}
			}
			boolean over = fr.valcombe.client.ClientConfig.get().rolesAuDessus;
			col.add(new Entry("Rôles affichés : " + (over ? "oui" : "non"), () -> {
				fr.valcombe.client.ClientConfig.get().rolesAuDessus = !over;
				fr.valcombe.client.ClientConfig.get().save();
				minecraft.gui.setScreen(new StorytellerScreen());
			}, false));
			col.add(new Entry("Accueillir un voyageur", () -> minecraft.gui.setScreen(
				new net.minecraft.client.gui.screens.ChatScreen("/vc voyageur ", false)), false));
			col.add(new Entry("Arrêter la partie", () -> send("vc arreter"), false));
			if ("HUMAIN".equals(ClientState.str(v, "mode"))) {
				String pol = ClientState.str(ClientState.obj(v, "policies"), "misregister");
				String next = "HASARD".equals(pol) ? "toujours" : "TOUJOURS".equals(pol) ? "jamais" : "hasard";
				col.add(new Entry("Trompe : " + (pol == null ? "hasard" : pol.toLowerCase()),
					() -> send("vc consigne enregistrement " + next), true));
			}
		}
		col.add(new Entry("Réglages de la nuit…", () -> minecraft.gui.setScreen(new SettingsScreen()), true));

		int gaps = (int) col.stream().filter(Entry::gap).count();
		int rows = col.size() + 1; // + Retour
		int avail = ph - 24;
		int step = Math.max(19, Math.min(22, (avail - gaps * 6) / rows));
		int bx = px + pw - 150;
		int y = py + 12;
		for (Entry e : col) {
			if (e.gap()) {
				y += 6;
			}
			button(bx, y, 136, e.label(), e.run());
			y += step;
		}
		button(bx, py + ph - 26, 136, "Retour", () -> minecraft.gui.setScreen(new MainScreen()));

		if (!lobby && isSt) {
			// Le cercle commence sous le texte des bluffs (et l'avertissement), et s'arrête au-dessus de l'aide.
			int textH = 0;
			int lh = font.lineHeight + 1;
			textH += font.split(net.minecraft.network.chat.Component.literal(bluffText(view())), pw - 180).size() * lh;
			String warn = ClientState.str(view(), "distWarning");
			if (warn != null && ClientState.phase().equals("READY")) {
				textH += font.split(net.minecraft.network.chat.Component.literal(warn), pw - 180).size() * lh + 2;
			}
			int top = py + 36 + textH;
			int bottom = py + ph - 26;
			int tok = bottom - top > 190 ? 24 : 20;
			int radius = Math.max(30, Math.min(86, (bottom - top - tok - 12) / 2));
			Grimoire.place(this, px + (pw - 150) / 2, top + tok / 2 + radius, radius, tok,
				name -> minecraft.gui.setScreen(new SeatScreen(name)));
		}
	}

	private static String bluffText(JsonObject v) {
		StringBuilder bl = new StringBuilder("Bluffs : ");
		for (String b : strings(v, "bluffs")) {
			bl.append(Role.valueOf(b).displayName).append(", ");
		}
		return bl.length() > 9 ? bl.substring(0, bl.length() - 2) : bl.toString();
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject v = view();
		JsonObject st = ClientState.obj(v, "settings");
		int bx = px + pw - 150;
		Theme.ink(g, font, phaseLabel(), px + 14, py + 12, Theme.WINE);
		String mode = "HUMAIN".equals(ClientState.str(v, "mode")) ? "Conteur humain" : "Conteur automatique";
		Theme.ink(g, font, mode, px + 14, py + 23, Theme.ASH);
		if (ClientState.phase().equals("LOBBY")) {
			int y = py + 42;
			List<String> lobby = strings(v, "lobby");
			Theme.ink(g, font, "Inscrits (" + lobby.size() + ") :", px + 14, y, Theme.INK);
			for (int i = 0; i < lobby.size(); i++) {
				Theme.ink(g, font, "• " + lobby.get(i), px + 18 + (i / 10) * 100, y + 12 + (i % 10) * 10, Theme.WINE);
			}
		} else if (!ClientState.bool(v, "isStoryteller")) {
			Theme.paragraph(g, font, "Le grimoire n'est visible que du Conteur. Devenez Conteur avant de lancer la partie.",
				px + 14, py + 60, 200, Theme.ASH);
		} else {
			String text = bluffText(v);
			int h = Theme.paragraph(g, font, text, px + 14, py + 34, pw - 180, Theme.INK);
			String warn = ClientState.str(v, "distWarning");
			if (warn != null && ClientState.phase().equals("READY")) {
				Theme.paragraph(g, font, warn, px + 14, py + 36 + h, pw - 180, Theme.BLOOD);
			}
			if (ClientState.phase().equals("READY")) {
				Theme.ink(g, font, "Clique sur un jeton pour le retoucher.", px + 14, py + ph - 18, Theme.ASH);
			}
		}
	}
}
