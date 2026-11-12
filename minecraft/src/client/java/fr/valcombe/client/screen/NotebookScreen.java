package fr.valcombe.client.screen;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Carnet du joueur, gardé seulement sur ce PC. Onglet « Joueurs » : pour chacun, le rôle qu'il annonce,
 * ta confiance (?, confiance, doute, suspect), le rôle que tu lui prêtes et une note. Onglet « Indices » :
 * ce que tu as appris la nuit (noté tout seul) et tes propres indices.
 */
public class NotebookScreen extends BaseScreen {
	private static final Gson GSON = new Gson();
	static final String[] TRUST = {"?", "Confiance", "Doute", "Suspect"};
	private static final int[] TRUST_COLORS = {0xFF6B5B4B, 0xFF2E6B2E, 0xFF8A6A12, 0xFF8E1F1F};
	private static final int ROW = 20;

	/** Ce que le carnet sait d'un joueur. */
	static final class Page {
		String note = "";
		/** Rôle prêté (nom du rôle), ou null. */
		String guess;
		/** Indice dans TRUST. */
		int trust;
	}

	private static final class Data {
		Map<String, Page> joueurs = new LinkedHashMap<>();
		List<String> indices = new ArrayList<>();
	}

	private static Data data;
	private static boolean cluesTab;
	private int scroll;

	public NotebookScreen() {
		super("Mon carnet");
	}

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("valcombe-carnet.json");
	}

	static Data data() {
		if (data == null) {
			data = new Data();
			try {
				if (Files.exists(file())) {
					JsonElement root = JsonParser.parseString(Files.readString(file()));
					if (root.isJsonObject() && root.getAsJsonObject().has("joueurs")) {
						Data read = GSON.fromJson(root, Data.class);
						if (read != null) {
							data = read;
						}
					} else if (root.isJsonObject()) {
						// Ancien carnet : une ligne de texte par joueur.
						for (var e : root.getAsJsonObject().entrySet()) {
							Page p = new Page();
							p.note = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "";
							data.joueurs.put(e.getKey(), p);
						}
					}
				}
			} catch (IOException | RuntimeException e) {
				// carnet illisible : on repart d'un carnet vide
			}
			if (data.joueurs == null) {
				data.joueurs = new LinkedHashMap<>();
			}
			if (data.indices == null) {
				data.indices = new ArrayList<>();
			}
		}
		return data;
	}

	static Page page(String name) {
		return data().joueurs.computeIfAbsent(name, k -> new Page());
	}

	static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(data()));
		} catch (IOException e) {
			// tant pis : les notes restent en mémoire pour cette session
		}
	}

	/** Note un indice (une information reçue la nuit), sans doublon. */
	public static void addClue(String text) {
		if (text == null || text.isBlank() || data().indices.contains(text)) {
			return;
		}
		data().indices.add(text);
		save();
	}

	private static List<String> players() {
		List<String> out = new ArrayList<>();
		for (JsonObject s : objects(view(), "seats")) {
			out.add(ClientState.str(s, "name"));
		}
		if (out.isEmpty()) {
			out.addAll(strings(view(), "lobby"));
		}
		out.remove(ClientState.str(view(), "me"));
		return out;
	}

	private static String claimOf(String name) {
		for (JsonObject s : objects(view(), "seats")) {
			if (name.equals(ClientState.str(s, "name"))) {
				Role r = roleOf(ClientState.str(s, "claim"));
				return r == null ? "" : r.displayName;
			}
		}
		return "";
	}

	static Role roleOf(String id) {
		if (id == null || id.isEmpty()) {
			return null;
		}
		try {
			return Role.valueOf(id);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Option du Conteur : le carnet ne s'ouvre que la nuit (temps de réflexion). */
	private static boolean lockedByDay() {
		JsonObject v = view();
		return ClientState.bool(ClientState.obj(v, "settings"), "notebookNightOnly") && "DAY".equals(ClientState.phase())
			&& ClientState.num(v, "mySeat", -1) >= 0;
	}

	@Override
	protected int[] panelSize() {
		return new int[] {440, 250};
	}

	private int visible() {
		return Math.max(1, (ph - 78) / ROW);
	}

	@Override
	protected void build() {
		button(px + pw - 94, py + ph - 26, 80, "Retour", () -> minecraft.gui.setScreen(new MainScreen()));
		if (lockedByDay()) {
			return;
		}
		var tabA = button(px + 14, py + 8, 90, "Joueurs", () -> {
			cluesTab = false;
			rebuildWidgets();
		});
		var tabB = button(px + 108, py + 8, 90, "Indices (" + data().indices.size() + ")", () -> {
			cluesTab = true;
			rebuildWidgets();
		});
		(cluesTab ? tabB : tabA).disabled();
		if (cluesTab) {
			buildClues();
		} else {
			buildPlayers();
		}
	}

	private void buildPlayers() {
		List<String> names = players();
		scroll = Math.max(0, Math.min(scroll, names.size() - visible()));
		int y = py + 46;
		for (int i = scroll; i < names.size() && i < scroll + visible(); i++) {
			String name = names.get(i);
			Page p = page(name);
			int x = px + 14 + 118;
			button(x, y, 62, TRUST[p.trust], () -> {
				p.trust = (p.trust + 1) % TRUST.length;
				save();
				rebuildWidgets();
			});
			Role guess = roleOf(p.guess);
			button(x + 66, y, 96, guess == null ? "Rôle prêté…" : guess.displayName,
				() -> minecraft.gui.setScreen(new NotebookGuessScreen(name)));
			EditBox box = new EditBox(font, x + 166, y + 1, pw - 14 - (x + 166 - px) - 14, 16, Component.literal(name));
			box.setMaxLength(160);
			box.setValue(p.note == null ? "" : p.note);
			box.setResponder(text -> {
				p.note = text;
				save();
			});
			addRenderableWidget(box);
			y += ROW;
		}
		button(px + 14, py + ph - 26, 120, "Tout effacer", () -> {
			data().joueurs.clear();
			save();
			rebuildWidgets();
		});
	}

	private void buildClues() {
		List<String> clues = data().indices;
		scroll = Math.max(0, Math.min(scroll, clues.size() - (visible() - 1)));
		int y = py + 46;
		for (int i = scroll; i < clues.size() && i < scroll + visible() - 1; i++) {
			int index = i;
			button(px + pw - 36, y, 22, "×", () -> {
				clues.remove(index);
				save();
				rebuildWidgets();
			});
			y += ROW;
		}
		EditBox add = new EditBox(font, px + 14, py + ph - 50, pw - 120, 16, Component.literal("indice"));
		add.setMaxLength(200);
		addRenderableWidget(add);
		button(px + pw - 100, py + ph - 51, 86, "Noter", () -> {
			String text = add.getValue().trim();
			if (!text.isEmpty()) {
				String night = ClientState.phase().equals("LOBBY") ? "" : "Jour " + ClientState.num(view(), "day", 0) + " : ";
				addClue(night + text);
				rebuildWidgets();
			}
		});
		button(px + 14, py + ph - 26, 120, "Effacer les indices", () -> {
			clues.clear();
			save();
			rebuildWidgets();
		});
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		scroll -= (int) Math.signum(dy);
		rebuildWidgets();
		return true;
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (lockedByDay()) {
			Theme.paragraph(g, font, "Le carnet ne s'ouvre que la nuit : c'est le moment de réfléchir à ce que tu as entendu "
				+ "pendant la journée.", px + 14, py + 40, pw - 28, Theme.INK);
			return;
		}
		if (cluesTab) {
			Theme.ink(g, font, "Ce que tu as appris la nuit est noté tout seul. Ajoute tes propres indices.", px + 14, py + 32, Theme.ASH);
			List<String> clues = data().indices;
			if (clues.isEmpty()) {
				Theme.paragraph(g, font, "Aucun indice pour l'instant.", px + 14, py + 50, pw - 28, Theme.INK);
			}
			int y = py + 51;
			for (int i = scroll; i < clues.size() && i < scroll + visible() - 1; i++) {
				String c = clues.get(i);
				String shown = font.plainSubstrByWidth(c, pw - 60);
				g.text(font, shown + (shown.length() < c.length() ? "…" : ""), px + 14, y, Theme.INK, false);
				y += ROW;
			}
			return;
		}
		Theme.ink(g, font, "Joueur — rôle annoncé · confiance · rôle que tu lui prêtes · note", px + 14, py + 32, Theme.ASH);
		List<String> names = players();
		if (names.isEmpty()) {
			Theme.paragraph(g, font, "Le carnet se remplit pendant une partie : une ligne par joueur. Rien à noter pour l'instant.",
				px + 14, py + 50, pw - 28, Theme.INK);
		}
		int y = py + 47;
		for (int i = scroll; i < names.size() && i < scroll + visible(); i++) {
			String name = names.get(i);
			Page p = page(name);
			String label = name.length() > 11 ? name.substring(0, 10) + "…" : name;
			g.text(font, label, px + 14, y, TRUST_COLORS[p.trust], false);
			String claim = claimOf(name);
			if (!claim.isEmpty()) {
				String c = font.plainSubstrByWidth("« " + claim + " »", 112);
				g.text(font, c, px + 14, y + 9, Theme.ASH, false);
			}
			y += ROW;
		}
	}
}
