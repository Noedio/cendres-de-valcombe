package fr.valcombe.client.screen;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdGlyphButton;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTabs;
import fr.valcombe.client.hd.HdText;
import fr.valcombe.client.hd.HdTextField;
import fr.valcombe.rules.Role;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;

/**
 * Carnet du joueur, gardé seulement sur ce PC. Onglet « Joueurs » : pour chacun, le rôle qu'il annonce,
 * ta confiance (?, confiance, doute, suspect), le rôle que tu lui prêtes et une note. Onglet « Indices » :
 * ce que tu as appris la nuit (noté tout seul) et tes propres indices.
 */
public class NotebookScreen extends HdScreen {
	private static final Gson GSON = new Gson();
	static final String[] TRUST = {"?", "Confiance", "Doute", "Suspect"};
	private static final int[] TRUST_COLORS = {0xFF6B5B4B, 0xFF2E6B2E, 0xFF8A6A12, 0xFF8E1F1F};
	private static final int ROW = 20;
	/** Clés des champs : la saisie en cours et le focus survivent aux reconstructions (une vue du serveur arrive souvent). */
	private static final String NOTE_KEY = "carnet.note.";
	private static final String CLUE_KEY = "carnet.indice";

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
		addRenderableWidget(new HdTabs(px + 14, py + 8, pw - 28, 16, List.of("Joueurs", "Indices (" + data().indices.size() + ")"),
			cluesTab ? 1 : 0).onChange(i -> {
				cluesTab = i == 1;
				scroll = 0;
				rebuildSoon();
			}));
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
				rebuildSoon();
			}).key("carnet.confiance." + name).tooltip("Ta confiance en " + name + " : un clic passe à la suivante.");
			Role guess = roleOf(p.guess);
			button(x + 66, y, 96, guess == null ? "Rôle prêté…" : guess.displayName,
				() -> minecraft.gui.setScreen(new NotebookGuessScreen(name)));
			// La note, enregistrée à chaque frappe ; la clé garde le curseur et le focus quand l'écran se reconstruit.
			addRenderableWidget(new HdTextField(x + 166, y, pw - 14 - (x + 166 - px) - 14, 18, name)
				.maxLength(160).value(p.note == null ? "" : p.note).hint("Note…").key(NOTE_KEY + name)
				.responder(text -> {
					p.note = text;
					save();
				}));
			y += ROW;
		}
		button(px + 14, py + ph - 26, 120, "Tout effacer", () -> {
			for (String name : data().joueurs.keySet()) {
				HdTextField.forget(NOTE_KEY + name);
			}
			data().joueurs.clear();
			save();
			rebuildSoon();
		});
	}

	private void buildClues() {
		List<String> clues = data().indices;
		scroll = Math.max(0, Math.min(scroll, clues.size() - (visible() - 1)));
		int y = py + 46;
		for (int i = scroll; i < clues.size() && i < scroll + visible() - 1; i++) {
			int index = i;
			addRenderableWidget(new HdGlyphButton(px + pw - 36, y, 22, 18, "×", () -> {
				clues.remove(index);
				save();
				rebuildSoon();
			})).tooltip("Effacer cet indice");
			y += ROW;
		}
		HdTextField add = addRenderableWidget(new HdTextField(px + 14, py + ph - 51, pw - 120, 18, "indice")
			.maxLength(200).hint("Un indice à noter…").key(CLUE_KEY));
		Runnable note = () -> {
			String text = add.value().trim();
			if (!text.isEmpty()) {
				String night = ClientState.phase().equals("LOBBY") ? "" : "Jour " + ClientState.num(view(), "day", 0) + " : ";
				addClue(night + text);
				HdTextField.forget(CLUE_KEY);
				rebuildSoon();
			}
		};
		add.onEnter(note);
		button(px + pw - 100, py + ph - 51, 86, "Noter", note);
		button(px + 14, py + ph - 26, 120, "Effacer les indices", () -> {
			clues.clear();
			save();
			rebuildSoon();
		});
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		scroll -= (int) Math.signum(dy);
		rebuildWidgets();
		return true;
	}

	@Override
	protected void drawHd(Hd hd) {
		HdStyle dense = HdText.SERRE;
		int pitch = hd.u(HdText.PAS);
		int left = hd.px(px + 14);
		int width = hd.px(pw - 28);
		if (lockedByDay()) {
			hd.paragraph(HdStyle.TEXTE, "Le carnet ne s'ouvre que la nuit : c'est le moment de réfléchir à ce que tu as entendu "
				+ "pendant la journée.", left, hd.px(py + 40), width, Hd.INK);
			return;
		}
		if (cluesTab) {
			HdText.line(hd, dense, "Ce que tu as appris la nuit est noté tout seul. Ajoute tes propres indices.", left, hd.px(py + 30),
				width, Hd.INK_SOFT);
			List<String> clues = data().indices;
			if (clues.isEmpty()) {
				HdText.paragraph(hd, HdStyle.TEXTE, "Aucun indice pour l'instant.", left, hd.px(py + 50), width, Hd.INK, pitch);
			}
			int y = py + 46;
			for (int i = scroll; i < clues.size() && i < scroll + visible() - 1; i++) {
				// Un indice par ligne, raccourci avant son bouton « × » (son texte entier reste dans le fichier du carnet).
				HdText.line(hd, HdStyle.TEXTE.size(10.5f), clues.get(i), left, hd.px(y) + (hd.u(18) - hd.lineHeight(HdStyle.TEXTE.size(10.5f))) / 2,
					hd.px(pw - 60), Hd.INK);
				y += ROW;
			}
			return;
		}
		HdText.line(hd, dense, "Joueur et rôle annoncé, confiance, rôle que tu lui prêtes, note", left, hd.px(py + 30), width,
			Hd.INK_SOFT);
		List<String> names = players();
		if (names.isEmpty()) {
			HdText.paragraph(hd, HdStyle.TEXTE, "Le carnet se remplit pendant une partie : une ligne par joueur. Rien à noter pour l'instant.",
				left, hd.px(py + 50), width, Hd.INK, hd.u(13));
		}
		int y = py + 46;
		HdStyle nameStyle = HdStyle.FORT.size(10);
		HdStyle claimStyle = HdStyle.PETIT.size(8.5f);
		for (int i = scroll; i < names.size() && i < scroll + visible(); i++) {
			String name = names.get(i);
			Page p = page(name);
			// Nom dans la couleur de ta confiance, rôle qu'il annonce dessous ; sans rôle annoncé, le nom se centre sur
			// la hauteur des boutons de sa ligne.
			String claim = claimOf(name);
			int nameTop = claim.isEmpty() ? hd.px(y) + (hd.u(18) - hd.lineHeight(nameStyle)) / 2 : hd.px(y) - hd.u(2);
			HdText.line(hd, nameStyle, name, left, nameTop, hd.px(112), TRUST_COLORS[p.trust]);
			if (!claim.isEmpty()) {
				HdText.line(hd, claimStyle, "« " + claim + " »", left, hd.px(y + 8), hd.px(112), Hd.INK_SOFT);
			}
			y += ROW;
		}
	}
}
