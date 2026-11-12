package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * Pupitre du Conteur : à gauche le grimoire (ou les inscrits), à droite les actions,
 * rangées par moment de la partie (mise en place, nuit, jour, parole, fin).
 */
public class StorytellerScreen extends BaseScreen {
	private record Entry(String label, Runnable run, boolean gap) {
	}

	private static final String[] THEMES = {"gothique", "monastere", "port"};
	private static final String[] THEME_NAMES = {"gothique", "monastère", "port"};
	private static final String[] TABS = {"Partie", "Nuit", "Jour", "Parole", "Fin"};
	private static int theme;
	/** Onglet choisi ; -1 : celui du moment de la partie. */
	private static int tab = -1;
	/** Action dangereuse en attente d'un second clic (arrêt, victoire). */
	private static String armed;

	public StorytellerScreen() {
		super("Pupitre du Conteur");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {430, 300};
	}

	private static int currentTab() {
		if (tab >= 0) {
			return tab;
		}
		return switch (ClientState.phase()) {
			case "NIGHT", "READY" -> 1; // avant la première nuit : remise des rôles, puis la nuit à faire tomber
			case "DAY" -> 2;
			default -> 0;
		};
	}

	/** Bouton à confirmer : le premier clic l'arme, le second lance la commande. */
	private Entry confirm(String label, String command, boolean gap) {
		boolean ready = command.equals(armed);
		return new Entry(ready ? "Confirmer : " + label : label, () -> {
			if (ready) {
				armed = null;
				send(command);
			} else {
				armed = command;
				rebuildWidgets();
			}
		}, gap);
	}

	@Override
	protected void build() {
		JsonObject v = view();
		boolean lobby = ClientState.phase().equals("LOBBY");
		boolean isSt = ClientState.bool(v, "isStoryteller");
		boolean paused = ClientState.bool(v, "paused");
		String phase = ClientState.phase();

		List<Entry> col = new ArrayList<>();
		if (lobby) {
			col.add(new Entry(isSt ? "Quitter le rôle de Conteur" : "Devenir le Conteur", () -> send("vc conteur"), false));
			col.add(new Entry("Ajouter 1 joueur fictif", () -> send("vc bots 1"), false));
			col.add(new Entry("Ajouter 4 joueurs fictifs", () -> send("vc bots 4"), false));
			col.add(new Entry("Retirer les fictifs", () -> send("vc bots retirer"), false));
			col.add(new Entry("Mélanger les places", () -> send("vc melanger"), false));
			col.add(new Entry("Lancer (automatique)", () -> send("vc lancer auto"), true));
			col.add(new Entry("Lancer (Conteur humain)", () -> send("vc lancer humain"), false));
			col.add(new Entry("Figures neutres…", () -> minecraft.gui.setScreen(new FiguresScreen()), false));
			col.add(new Entry("Réglages de la nuit…", () -> minecraft.gui.setScreen(new SettingsScreen()), true));
			button(px + 14, py + ph - 48, 150, "Éditer le script", () -> minecraft.gui.setScreen(new ScriptScreen()));
			// Bâtir un village remplace la carte actuelle : un second clic le confirme.
			String build = "vc carte construire " + THEMES[theme];
			boolean ready = build.equals(armed);
			button(px + 14, py + ph - 26, 104, ready ? "Confirmer" : "Bâtir le village", () -> {
				if (!ready) {
					armed = build;
					rebuildWidgets();
					return;
				}
				armed = null;
				send(build);
				onClose();
			});
			button(px + 122, py + ph - 26, 104, "Thème : " + THEME_NAMES[theme], () -> {
				theme = (theme + 1) % THEMES.length;
				rebuildWidgets();
			});
		} else {
			int t = currentTab();
			// Onglets : un par moment de la partie.
			int tw = (pw - 28) / TABS.length;
			for (int i = 0; i < TABS.length; i++) {
				int index = i;
				var b = button(px + 14 + i * tw, py + 10, tw - 2, TABS[i], () -> {
					tab = index;
					armed = null;
					rebuildWidgets();
				});
				if (i == t) {
					b.disabled();
				}
			}
			String pause = paused ? "Reprendre la partie" : "Mettre en pause";
			switch (t) {
				case 0 -> {
					boolean over = fr.valcombe.client.ClientConfig.get().rolesAuDessus;
					col.add(new Entry("Rôles affichés : " + (over ? "oui" : "non"), () -> {
						fr.valcombe.client.ClientConfig.get().rolesAuDessus = !over;
						fr.valcombe.client.ClientConfig.get().save();
						rebuildWidgets();
					}, false));
					col.add(new Entry("Accueillir un voyageur", () -> minecraft.gui.setScreen(new ChatScreen("/vc voyageur ", false)), false));
					col.add(new Entry("Réglages de la nuit…", () -> minecraft.gui.setScreen(new SettingsScreen()), false));
					if ("HUMAIN".equals(ClientState.str(v, "mode"))) {
						String pol = ClientState.str(ClientState.obj(v, "policies"), "misregister");
						String next = "HASARD".equals(pol) ? "toujours" : "TOUJOURS".equals(pol) ? "jamais" : "hasard";
						col.add(new Entry("Trompe : " + (pol == null ? "hasard" : pol.toLowerCase()),
							() -> send("vc consigne enregistrement " + next), false));
					}
					col.add(new Entry("Journal de la partie…", () -> minecraft.gui.setScreen(new ChronicleScreen("journal")), true));
					col.add(new Entry("Note de partie", () -> minecraft.gui.setScreen(new ChatScreen("/vc note partie ", false)), false));
					col.add(new Entry(pause, () -> send("vc pause"), true));
				}
				case 1 -> {
					if (phase.equals("READY") && "HUMAIN".equals(ClientState.str(v, "mode"))) {
						// Remise des rôles : le Conteur reçoit chaque joueur dans la salle de convocation.
						int left = v.has("unbriefed") ? v.getAsJsonArray("unbriefed").size() : 0;
						String here = ClientState.str(v, "summoned");
						col.add(new Entry(left > 0 ? "Convoquer le suivant (" + left + " à recevoir)" : "Convoquer le suivant",
							() -> send("vc convoquer suivant"), false));
						if (here != null && !here.isEmpty()) {
							col.add(new Entry("Renvoyer " + here + " au village", () -> send("vc convoquer fin"), false));
						}
					}
					if (!phase.equals("NIGHT")) {
						col.add(new Entry("Faire tomber la nuit", () -> {
							send("vc nuit");
							tab = -1;
						}, false));
					} else {
						col.add(new Entry("Nuit pas à pas…", () -> minecraft.gui.setScreen(new NightScreen()), false));
						col.add(new Entry("Étape précédente", () -> send("vc precedent"), false));
						col.add(new Entry("Passer l'étape", () -> send("vc suivant"), false));
					}
					col.add(new Entry(pause, () -> send("vc pause"), true));
				}
				case 2 -> {
					if (phase.equals("DAY")) {
						boolean open = ClientState.bool(v, "nominationsOpen");
						JsonObject vote = ClientState.obj(v, "vote");
						JsonObject today = ClientState.obj(v, "today");
						col.add(new Entry("Rassembler le village", () -> send("vc rassembler"), false));
						String here = ClientState.str(v, "summoned");
						if (here != null && !here.isEmpty()) {
							col.add(new Entry("Renvoyer " + here + " au village", () -> send("vc convoquer fin"), false));
						}
						col.add(new Entry("Discussion libre (5 min)", () -> send("vc discussion 5"), false));
						col.add(new Entry("Prolonger d'une minute", () -> send("vc prolonger 1"), false));
						if (ClientState.bool(v, "council")) {
							if (!ClientState.bool(v, "councilVoting")) {
								col.add(new Entry(open ? "Clore les accusations et voter" : "Ouvrir le Conseil",
									() -> send("vc nominations " + (open ? "fermer" : "ouvrir")), true));
							}
						} else {
							col.add(new Entry(open ? "Fermer les nominations" : "Ouvrir les nominations",
								() -> send("vc nominations " + (open ? "fermer" : "ouvrir")), true));
						}
						if (vote != null && "DEFENSE".equals(ClientState.str(vote, "stage"))) {
							col.add(new Entry("Lancer le vote", () -> send("vc voter"), false));
						}
						if (vote != null) {
							col.add(new Entry("Annuler le vote", () -> send("vc vote annuler"), false));
						}
						String block = ClientState.str(today, "block");
						if (block != null && !block.isEmpty() && vote == null) {
							col.add(new Entry("Gracier " + block, () -> send("vc gracier"), false));
						}
						col.add(new Entry(block != null && !block.isEmpty() ? "Exécuter et clore le jour" : "Clore la journée",
							() -> send("vc finjour"), false));
						boolean all = ClientState.bool(v, "broadcast");
						col.add(new Entry("Ma voix à tous : " + (all ? "oui" : "non"), () -> send("vc diffusion"), true));
					} else {
						col.add(new Entry("Clore la journée", () -> send("vc finjour"), false));
					}
					col.add(new Entry(pause, () -> send("vc pause"), !phase.equals("DAY")));
				}
				case 3 -> {
					JsonObject speech = ClientState.obj(v, "speech");
					int secs = speech == null ? 45 : ClientState.num(speech, "seconds", 45);
					col.add(new Entry("Donner la parole au suivant", () -> send("vc parole suivant"), false));
					col.add(new Entry("Tour de table", () -> send("vc parole tour"), false));
					col.add(new Entry("Temps : " + secs + " s  (+15)", () -> send("vc parole duree " + Math.min(180, secs + 15)), true));
					col.add(new Entry("Temps : " + secs + " s  (−15)", () -> send("vc parole duree " + Math.max(15, secs - 15)), false));
					col.add(new Entry("Arrêter le temps de parole", () -> send("vc parole stop"), true));
				}
				default -> {
					col.add(new Entry("Journal de la partie…", () -> minecraft.gui.setScreen(new ChronicleScreen("journal")), false));
					col.add(confirm("Victoire du bien", "vc victoire bien", true));
					col.add(confirm("Victoire du mal", "vc victoire mal", false));
					col.add(confirm("Arrêter sans révéler", "vc arreter", true));
				}
			}
		}

		int gaps = (int) col.stream().filter(Entry::gap).count();
		int top = py + (lobby ? 12 : 34);
		int rows = col.size() + 1; // + Retour
		int avail = py + ph - 4 - top;
		int step = Math.max(19, Math.min(22, (avail - gaps * 6) / rows));
		int bx = px + pw - 164;
		int y = top;
		for (Entry e : col) {
			if (e.gap()) {
				y += 6;
			}
			button(bx, y, 150, e.label(), e.run());
			y += step;
		}
		button(bx, py + ph - 26, 150, "Retour", () -> {
			armed = null;
			minecraft.gui.setScreen(new MainScreen());
		});

		if (!lobby && isSt) {
			// Le cercle commence sous le texte des bluffs (et l'avertissement), et s'arrête au-dessus de l'aide.
			int textH = 0;
			int lh = font.lineHeight + 1;
			textH += font.split(net.minecraft.network.chat.Component.literal(headText(v)), pw - 194).size() * lh;
			String warn = ClientState.str(view(), "distWarning");
			if (warn != null && ClientState.phase().equals("READY")) {
				textH += font.split(net.minecraft.network.chat.Component.literal(warn), pw - 194).size() * lh + 2;
			}
			int circleTop = py + 58 + textH;
			int bottom = py + ph - 26;
			int tok = bottom - circleTop > 190 ? 24 : 20;
			int radius = Math.max(30, Math.min(86, (bottom - circleTop - tok - 12) / 2));
			Grimoire.place(this, px + (pw - 164) / 2, circleTop + tok / 2 + radius, radius, tok,
				name -> minecraft.gui.setScreen(new SeatScreen(name)));
		}
	}

	/** Bluffs, orateur et note de partie : ce que le Conteur garde sous les yeux. */
	private static String headText(JsonObject v) {
		StringBuilder bl = new StringBuilder("Bluffs : ");
		for (String b : strings(v, "bluffs")) {
			bl.append(Role.valueOf(b).displayName).append(", ");
		}
		String text = bl.length() > 9 ? bl.substring(0, bl.length() - 2) : bl.toString();
		JsonObject speech = ClientState.obj(v, "speech");
		// L'orateur se lit dans l'onglet Parole, la note de partie dans l'onglet Partie : le cercle garde sa place.
		if (speech != null && currentTab() == 3) {
			String who = ClientState.str(speech, "speaker");
			List<String> queue = strings(speech, "queue");
			text += "\nParole : " + (who == null ? "personne" : who)
				+ (queue.isEmpty() ? "" : " — demandent : " + String.join(", ", queue));
		}
		String note = ClientState.str(v, "gameNote");
		if (note != null && !note.isEmpty() && currentTab() == 0) {
			text += "\nNote : " + note;
		}
		return text;
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		JsonObject v = view();
		boolean lobby = ClientState.phase().equals("LOBBY");
		int top = py + (lobby ? 12 : 34);
		String mode = "HUMAIN".equals(ClientState.str(v, "mode")) ? "Conteur humain" : "Conteur automatique";
		Theme.ink(g, font, phaseLabel() + (ClientState.bool(v, "paused") ? " — EN PAUSE" : ""), px + 14, top, Theme.WINE);
		Theme.ink(g, font, mode, px + 14, top + 11, Theme.ASH);
		if (lobby) {
			int y = top + 30;
			List<String> names = strings(v, "lobby");
			Theme.ink(g, font, "Inscrits (" + names.size() + ") :", px + 14, y, Theme.INK);
			for (int i = 0; i < names.size(); i++) {
				Theme.ink(g, font, "• " + names.get(i), px + 18 + (i / 10) * 100, y + 12 + (i % 10) * 10, Theme.WINE);
			}
			String status = ClientState.str(v, "lobbyStatus");
			if (status != null) {
				Theme.paragraph(g, font, status, px + 14, y + 12 + Math.min(10, Math.max(1, names.size())) * 10 + 6, pw - 194,
					ClientState.bool(v, "lobbyReady") ? Theme.BRONZE : Theme.BLOOD);
			}
		} else if (!ClientState.bool(v, "isStoryteller")) {
			Theme.paragraph(g, font, "Le grimoire n'est visible que du Conteur. Devenez Conteur avant de lancer la partie.",
				px + 14, top + 30, 200, Theme.ASH);
		} else {
			int h = Theme.paragraph(g, font, headText(v), px + 14, top + 24, pw - 194, Theme.INK);
			String warn = ClientState.str(v, "distWarning");
			if (warn != null && ClientState.phase().equals("READY")) {
				Theme.paragraph(g, font, warn, px + 14, top + 26 + h, pw - 194, Theme.BLOOD);
			}
			Theme.ink(g, font, "Un clic sur un jeton : la fiche du joueur.", px + 14, py + ph - 18, Theme.ASH);
		}
	}
}
