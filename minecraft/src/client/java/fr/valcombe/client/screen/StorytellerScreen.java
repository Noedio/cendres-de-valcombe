package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientConfig;
import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTabs;
import fr.valcombe.client.hd.HdToggle;
import fr.valcombe.client.hd.HdToken;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.screens.ChatScreen;

/**
 * Pupitre du Conteur : à gauche le grimoire (ou les inscrits), à droite les actions,
 * rangées par moment de la partie (mise en place, nuit, jour, parole, fin) sous des onglets.
 * En haute résolution : le grimoire est une ellipse de jetons nets qui occupe toute la place laissée par les
 * actions ; quand les actions ne tiennent pas en hauteur (petite fenêtre, journée chargée), elles se resserrent,
 * puis leur colonne défile à la molette.
 */
public class StorytellerScreen extends HdScreen {
	/** Une action de la colonne ; on non nul en fait un interrupteur (« libellé : oui/non »). */
	private record Entry(String label, Runnable run, boolean gap, Boolean on) {
		Entry(String label, Runnable run, boolean gap) {
			this(label, run, gap, null);
		}
	}

	private static final String[] THEMES = {"gothique", "monastere", "port"};
	private static final String[] THEME_NAMES = {"gothique", "monastère", "port"};
	private static final String[] TABS = {"Partie", "Nuit", "Jour", "Parole", "Fin"};
	/** Marge intérieure du panneau et largeur de la colonne d'actions, en unités. */
	private static final int MARGIN = 12;
	private static final int COL_W = 150;
	/** Texte de tête (bluffs, orateur, note de partie) et avertissement de répartition. */
	private static final HdStyle HEAD = HdStyle.TEXTE.size(10);
	private static final int HEAD_LINES = 3;
	private static int theme;
	/** Onglet choisi ; -1 : celui du moment de la partie. */
	private static int tab = -1;
	/** Action dangereuse en attente d'un second clic (arrêt, victoire). */
	private static String armed;
	/** Première action montrée quand la colonne défile. */
	private int first;

	// Mise en page calculée par build(), reprise par drawHd() et la molette (unités d'interface).
	private int colX;
	private int colTop;
	private int colBottom;
	private int colShown;
	private int colTotal;
	/** Haut du texte de tête ; vrai si le mode du Conteur tient sur la ligne de la phase. */
	private int headTop;
	private boolean modeInline;

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
				rebuildSoon();
			}
		}, gap);
	}

	/** Bord gauche et largeur de la zone de gauche (grimoire, inscrits, textes). */
	private int leftX() {
		return px + MARGIN;
	}

	private int leftW() {
		return colX - 10 - leftX();
	}

	private static boolean lobby() {
		return ClientState.phase().equals("LOBBY");
	}

	/** Haut du contenu : sous les onglets pendant la partie, en haut du panneau au salon. */
	private int contentTop() {
		return py + (lobby() ? 10 : 30);
	}

	private static String modeLabel(JsonObject v) {
		return "HUMAIN".equals(ClientState.str(v, "mode")) ? "Conteur humain" : "Conteur automatique";
	}

	private static String phaseLine(JsonObject v) {
		return phaseLabel() + (ClientState.bool(v, "paused") ? " (en pause)" : "");
	}

	@Override
	protected void build() {
		JsonObject v = view();
		boolean lobby = lobby();
		boolean isSt = ClientState.bool(v, "isStoryteller");
		boolean paused = ClientState.bool(v, "paused");
		String phase = ClientState.phase();
		colX = px + pw - MARGIN - COL_W;

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
			col.add(new Entry("Imposer des rôles…", () -> minecraft.gui.setScreen(new DistributionScreen()), false));
			col.add(new Entry("Réglages de la nuit…", () -> minecraft.gui.setScreen(new SettingsScreen()), true));
			col.add(new Entry("Durées et minuteurs…", () -> minecraft.gui.setScreen(new DurationsScreen()), false));
			int lx = leftX();
			button(lx, py + ph - 48, 150, "Éditer le script", () -> minecraft.gui.setScreen(new ScriptScreen()));
			// Bâtir un village remplace la carte actuelle : un second clic le confirme.
			String build = "vc carte construire " + THEMES[theme];
			boolean ready = build.equals(armed);
			button(lx, py + ph - 26, 104, ready ? "Confirmer" : "Bâtir le village", () -> {
				if (!ready) {
					armed = build;
					rebuildSoon();
					return;
				}
				armed = null;
				send(build);
				onClose();
			}).tooltip(ready ? "Le village " + THEME_NAMES[theme] + " remplace la carte actuelle."
				: "Bâtit un village du thème choisi à la place de la carte actuelle (second clic pour confirmer).");
			button(lx + 108, py + ph - 26, 104, "Thème : " + THEME_NAMES[theme], () -> {
				theme = (theme + 1) % THEMES.length;
				rebuildSoon();
			});
		} else {
			int t = currentTab();
			// Onglets : un par moment de la partie.
			addRenderableWidget(new HdTabs(px + MARGIN, py + 8, pw - 2 * MARGIN, 16, List.of(TABS), t).onChange(i -> {
				tab = i;
				armed = null;
				first = 0;
				rebuildSoon();
			}));
			String pause = paused ? "Reprendre la partie" : "Mettre en pause";
			switch (t) {
				case 0 -> {
					boolean over = ClientConfig.get().rolesAuDessus;
					col.add(new Entry("Rôles affichés", () -> {
						ClientConfig.get().rolesAuDessus = !over;
						ClientConfig.get().save();
						rebuildSoon();
					}, false, over));
					col.add(new Entry("Accueillir un voyageur", () -> minecraft.gui.setScreen(new ChatScreen("/vc voyageur ", false)), false));
					col.add(new Entry("Réglages de la nuit…", () -> minecraft.gui.setScreen(new SettingsScreen()), false));
					col.add(new Entry("Durées et minuteurs…", () -> minecraft.gui.setScreen(new DurationsScreen()), false));
					col.add(new Entry("Ordre de réveil…", () -> minecraft.gui.setScreen(new OrderScreen()), false));
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
						col.add(new Entry("Distribution des rôles…", () -> minecraft.gui.setScreen(new DistributionScreen()), false));
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
						col.add(new Entry(ClientState.bool(v, "waitingNext") ? "Étape suivante" : "Passer l'étape",
							() -> send("vc suivant"), ClientState.bool(v, "waitingNext")));
						col.add(new Entry("Ordre de réveil…", () -> minecraft.gui.setScreen(new OrderScreen()), false));
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
						if (ClientState.bool(v, "waitingNextVote")) {
							col.add(new Entry("Vote suivant", () -> send("vc voter"), true));
						}
						JsonObject timer = ClientState.obj(v, "timer");
						if (timer != null) {
							col.add(new Entry(ClientState.str(timer, "kind") + " : passer", () -> send("vc minuteur passer"), true));
							col.add(new Entry(ClientState.str(timer, "kind") + " : +30 s", () -> send("vc minuteur prolonger 30"), false));
							col.add(new Entry(ClientState.str(timer, "kind") + " : arrêter", () -> send("vc minuteur arreter"), false));
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
						col.add(new Entry("Ma voix à tous", () -> send("vc diffusion"), true, all));
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

		layoutColumn(col, contentTop());
		button(colX, py + ph - 26, COL_W, "Retour", () -> {
			armed = null;
			minecraft.gui.setScreen(new MainScreen());
		});

		// Ligne de la phase : le mode du Conteur la suit s'il y tient, sinon il passe dessous.
		int lw = leftW();
		modeInline = StorytellerKit.textWidth(HdStyle.FORT, phaseLine(v)) + 10
			+ StorytellerKit.textWidth(HdStyle.PETIT, modeLabel(v)) <= lw;
		headTop = contentTop() + (modeInline ? 17 : 29);
		if (!lobby && isSt) {
			// Le grimoire occupe la place entre le texte de tête (et l'avertissement) et l'aide du bas.
			int top = headTop + headHeight(v, lw) + warnHeight(v, lw) + 4;
			int bottom = py + ph - 22;
			for (HdToken t : Grimoire.hdTokens(leftX(), top, lw, bottom - top,
					name -> minecraft.gui.setScreen(new SeatScreen(name)))) {
				token(t);
			}
		}
	}

	/**
	 * Place les actions dans la colonne de droite. Taille normale (boutons de 18, écarts entre les groupes) si
	 * elles tiennent ; sinon resserrées ; sinon la colonne montre ce qui tient et défile à la molette (les actions
	 * hors de la fenêtre restent dans l'écran, cachées).
	 */
	private void layoutColumn(List<Entry> col, int top) {
		colTop = top;
		colBottom = py + ph - 30;
		int avail = colBottom - colTop;
		int n = col.size();
		int gaps = 0;
		for (int i = 1; i < n; i++) {
			gaps += col.get(i).gap() ? 1 : 0;
		}
		int step;
		int h;
		int gap;
		boolean scroll = false;
		if (n * 19 + gaps * 6 <= avail) {
			gap = 6;
			step = Math.min(22, (avail - gaps * gap) / Math.max(1, n));
			h = 18;
		} else if (n * 17 + gaps * 3 <= avail) {
			gap = 3;
			step = Math.min(19, (avail - gaps * gap) / n);
			h = step >= 19 ? 18 : 16;
		} else {
			// Serrées, sans écarts : boutons de 15 tous les 16.
			gap = 0;
			step = 16;
			h = 15;
			scroll = (n - 1) * step + h > avail;
		}
		int w = scroll ? COL_W - 6 : COL_W;
		colTotal = n;
		colShown = scroll ? Math.max(1, (avail - h) / step + 1) : n;
		first = scroll ? Math.max(0, Math.min(first, n - colShown)) : 0;
		int y = colTop;
		for (int i = 0; i < n; i++) {
			Entry e = col.get(i);
			if (e.gap() && i > 0) {
				y += gap;
			}
			HdButton b = e.on() != null ? addRenderableWidget(new HdToggle(colX, y, w, h, e.label(), e.on(), e.run()))
				: addRenderableWidget(new HdButton(colX, y, w, h, e.label(), e.run()));
			if (i < first || i >= first + colShown) {
				b.visible = false; // hors de la fenêtre : ni dessinée ni cliquable, mais toujours dans l'écran
			} else {
				y += step;
			}
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		if (colShown < colTotal && dy != 0 && mouseX >= colX && mouseX < colX + COL_W
				&& mouseY >= colTop && mouseY < colBottom) {
			first = Math.max(0, Math.min(colTotal - colShown, first - (int) Math.signum(dy)));
			rebuildWidgets();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, dx, dy);
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
				+ (queue.isEmpty() ? "" : ". Demandent la parole : " + String.join(", ", queue));
		}
		String note = ClientState.str(v, "gameNote");
		if (note != null && !note.isEmpty() && currentTab() == 0) {
			text += "\nNote : " + note;
		}
		return text;
	}

	/** Avertissement de répartition, avant la première nuit seulement. */
	private static String warning(JsonObject v) {
		String warn = ClientState.str(v, "distWarning");
		return warn != null && !warn.isEmpty() && ClientState.phase().equals("READY") ? warn : null;
	}

	private static int headHeight(JsonObject v, int width) {
		return StorytellerKit.textHeight(HEAD, headText(v), width, HEAD_LINES);
	}

	private static int warnHeight(JsonObject v, int width) {
		String warn = warning(v);
		return warn == null ? 0 : StorytellerKit.textHeight(HEAD, warn, width, HEAD_LINES) + 2;
	}

	@Override
	protected void drawHd(Hd hd) {
		JsonObject v = view();
		boolean lobby = lobby();
		int lx = leftX();
		int lw = leftW();
		int top = contentTop();
		// Phase de la partie, et mode du Conteur à sa suite (ou dessous si la ligne est trop courte).
		String phase = hd.ellipsize(HdStyle.FORT, phaseLine(v), hd.px(lw));
		int pw0 = hd.text(HdStyle.FORT, phase, hd.px(lx), hd.px(top), Hd.WINE);
		if (modeInline) {
			int baseShift = hd.lineHeight(HdStyle.FORT) - hd.lineHeight(HdStyle.PETIT);
			hd.text(HdStyle.PETIT, modeLabel(v), hd.px(lx) + pw0 + hd.u(10), hd.px(top) + baseShift * 2 / 3, Hd.INK_SOFT);
		} else {
			hd.text(HdStyle.PETIT, modeLabel(v), hd.px(lx), hd.px(top + 14), Hd.INK_SOFT);
		}
		if (lobby) {
			drawLobby(hd, v, lx, lw, headTop);
		} else if (!ClientState.bool(v, "isStoryteller")) {
			hd.paragraph(HEAD, "Le grimoire n'est visible que du Conteur. Devenez Conteur avant de lancer la partie.",
				hd.px(lx), hd.px(headTop), hd.px(Math.min(lw, 200)), Hd.INK_SOFT);
		} else {
			int maxH = HEAD_LINES * hd.lineHeight(HEAD);
			int h = hd.paragraph(HEAD, headText(v), hd.px(lx), hd.px(headTop), hd.px(lw), Hd.INK, maxH);
			String warn = warning(v);
			if (warn != null) {
				hd.paragraph(HEAD, warn, hd.px(lx), hd.px(headTop) + h + hd.u(2), hd.px(lw), Theme.BLOOD, maxH);
			}
			hd.text(HdStyle.PETIT, "Un clic sur un jeton : la fiche du joueur.", hd.px(lx), hd.px(py + ph - 20), Hd.INK_SOFT);
		}
		// Ascenseur de la colonne d'actions quand elle défile.
		StorytellerKit.rail(hd, colX + COL_W - 4, colTop, colBottom - colTop, first, colShown, colTotal);
	}

	/** Salon : les inscrits en colonnes, puis l'état de la partie à lancer, au-dessus des boutons du bas. */
	private void drawLobby(Hd hd, JsonObject v, int lx, int lw, int y) {
		List<String> names = strings(v, "lobby");
		hd.text(HdStyle.FORT, "Inscrits (" + names.size() + ") :", hd.px(lx), hd.px(y), Hd.INK);
		String status = ClientState.str(v, "lobbyStatus");
		int statusH = status == null ? 0 : StorytellerKit.textHeight(HEAD, status, lw, HEAD_LINES);
		int statusTop = py + ph - 52 - statusH;
		int top = y + 16;
		int rowH = 13;
		int rows = Math.max(1, (statusTop - 4 - top) / rowH);
		int cols = Math.max(1, (names.size() + rows - 1) / rows);
		cols = Math.min(cols, Math.max(1, lw / 56));
		int colW = lw / cols;
		int capacity = rows * cols;
		HdStyle st = HEAD;
		for (int i = 0; i < Math.min(names.size(), capacity); i++) {
			int x = lx + (i / rows) * colW;
			int ry = top + (i % rows) * rowH;
			boolean last = i == capacity - 1 && names.size() > capacity;
			String text = last ? "et " + (names.size() - capacity + 1) + " autres" : names.get(i);
			int lineY = hd.px(ry);
			int mid = lineY + hd.lineHeight(st) / 2;
			hd.disc(hd.px(x) + hd.u(2), mid, Math.max(1, hd.u(1.5)), Hd.BRONZE);
			hd.text(st, hd.ellipsize(st, text, hd.px(colW - 10)), hd.px(x + 6), lineY, last ? Hd.INK_SOFT : Hd.WINE);
		}
		if (status != null) {
			hd.paragraph(HEAD, status, hd.px(lx), hd.px(statusTop), hd.px(lw),
				ClientState.bool(v, "lobbyReady") ? Hd.BRONZE : Theme.BLOOD, HEAD_LINES * hd.lineHeight(HEAD));
		}
	}
}
