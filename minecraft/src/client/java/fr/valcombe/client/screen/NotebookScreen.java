package fr.valcombe.client.screen;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;

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
 * Carnet du joueur : une ligne de notes par joueur du cercle (ce qu'il prétend être,
 * ses votes, vos soupçons). Enregistré seulement sur ce PC.
 */
public class NotebookScreen extends BaseScreen {
	private static final Gson GSON = new Gson();
	private static Map<String, String> notes;

	public NotebookScreen() {
		super("Mon carnet");
	}

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("valcombe-carnet.json");
	}

	private static Map<String, String> notes() {
		if (notes == null) {
			notes = new LinkedHashMap<>();
			try {
				if (Files.exists(file())) {
					Map<String, String> read = GSON.fromJson(Files.readString(file()),
						new TypeToken<Map<String, String>>() { }.getType());
					if (read != null) {
						notes.putAll(read);
					}
				}
			} catch (IOException | RuntimeException e) {
				// carnet illisible : on repart d'un carnet vide
			}
		}
		return notes;
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(notes()));
		} catch (IOException e) {
			// tant pis : les notes restent en mémoire pour cette session
		}
	}

	private static List<String> players() {
		List<String> out = new ArrayList<>();
		for (JsonObject s : objects(view(), "seats")) {
			out.add(ClientState.str(s, "name"));
		}
		if (out.isEmpty()) {
			out.addAll(strings(view(), "lobby"));
		}
		return out;
	}

	@Override
	protected int[] panelSize() {
		return new int[] {420, 250};
	}

	@Override
	protected void build() {
		List<String> names = players();
		String me = ClientState.str(view(), "me");
		int rows = Math.max(1, Math.min(8, (ph - 70) / 21));
		int colW = (pw - 36) / 2;
		int i = 0;
		for (String name : names) {
			if (name.equals(me) || i >= rows * 2) {
				continue;
			}
			int col = i / rows;
			int row = i % rows;
			int x = px + 14 + col * (colW + 8);
			int y = py + 30 + row * 21;
			EditBox box = new EditBox(font, x + 64, y, colW - 64, 16, Component.literal(name));
			box.setMaxLength(120);
			box.setValue(notes().getOrDefault(name, ""));
			box.setResponder(text -> {
				notes().put(name, text);
				save();
			});
			addRenderableWidget(box);
			i++;
		}
		button(px + 14, py + ph - 26, 120, "Tout effacer", () -> {
			notes().clear();
			save();
			rebuildWidgets();
		});
		button(px + pw - 94, py + ph - 26, 80, "Retour", () -> minecraft.gui.setScreen(new MainScreen()));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Theme.ink(g, font, "Ce que chacun prétend être, ses votes, vos soupçons. Gardé sur votre PC.", px + 14, py + 14, Theme.ASH);
		List<String> names = players();
		String me = ClientState.str(view(), "me");
		int rows = Math.max(1, Math.min(8, (ph - 70) / 21));
		int colW = (pw - 36) / 2;
		int i = 0;
		for (String name : names) {
			if (name.equals(me) || i >= rows * 2) {
				continue;
			}
			int col = i / rows;
			int row = i % rows;
			String label = name.length() > 9 ? name.substring(0, 8) + "…" : name;
			Theme.ink(g, font, label, px + 14 + col * (colW + 8), py + 34 + row * 21, Theme.WINE);
			i++;
		}
	}
}
