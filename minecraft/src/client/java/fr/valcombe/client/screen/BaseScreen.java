package fr.valcombe.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.client.ui.TokenWidget;
import fr.valcombe.client.ui.WaxButton;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Écran du mod : voile sombre, panneau de parchemin encadré, bandeau de titre.
 * Il se reconstruit dès que le serveur envoie une nouvelle vue de la partie.
 */
public abstract class BaseScreen extends Screen {
	protected int px;
	protected int py;
	protected int pw;
	protected int ph;
	private long seenVersion;

	protected BaseScreen(String title) {
		super(Component.literal(title));
	}

	/** Taille souhaitée du panneau (réduite si la fenêtre est petite). */
	protected abstract int[] panelSize();

	/** Ajoute les widgets. */
	protected abstract void build();

	/** Dessine le contenu sous les widgets. */
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
	}

	@Override
	protected void init() {
		int[] s = panelSize();
		pw = Math.min(s[0], width - 16);
		ph = Math.min(s[1], height - 24);
		px = (width - pw) / 2;
		py = (height - ph) / 2 + 6;
		seenVersion = ClientState.version();
		build();
	}

	@Override
	public void tick() {
		if (ClientState.version() != seenVersion) {
			rebuildWidgets();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, width, height, Theme.SHADE);
		Theme.panel(g, px, py, pw, ph);
		Theme.banner(g, font, getTitle().getString(), px + pw / 2, py - 8);
		drawContent(g, mouseX, mouseY);
	}

	// ------------------------------------------------------------------
	// Outils
	// ------------------------------------------------------------------
	protected static JsonObject view() {
		return ClientState.view();
	}

	protected static void send(String command) {
		ClientState.send(command);
	}

	protected WaxButton button(int x, int y, int w, String label, Runnable action) {
		return addRenderableWidget(new WaxButton(x, y, w, 18, label, action));
	}

	/** Place n jetons en cercle ; consumer reçoit (index, cx, cy). */
	protected static void circle(int n, int cx, int cy, int radius, CircleSlot slot) {
		for (int i = 0; i < n; i++) {
			double a = -Math.PI / 2 + 2 * Math.PI * i / Math.max(1, n);
			slot.at(i, cx + (int) Math.round(radius * Math.cos(a)), cy + (int) Math.round(radius * Math.sin(a)));
		}
	}

	@FunctionalInterface
	protected interface CircleSlot {
		void at(int index, int x, int y);
	}

	protected TokenWidget token(TokenWidget t) {
		return addRenderableWidget(t);
	}

	protected static List<JsonObject> objects(JsonObject o, String key) {
		List<JsonObject> out = new ArrayList<>();
		if (o != null && o.has(key) && o.get(key).isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray(key)) {
				if (e.isJsonObject()) {
					out.add(e.getAsJsonObject());
				}
			}
		}
		return out;
	}

	protected static List<String> strings(JsonObject o, String key) {
		List<String> out = new ArrayList<>();
		if (o != null && o.has(key) && o.get(key).isJsonArray()) {
			JsonArray a = o.getAsJsonArray(key);
			for (JsonElement e : a) {
				if (e.isJsonPrimitive()) {
					out.add(e.getAsString());
				}
			}
		}
		return out;
	}

	protected static void each(List<JsonObject> list, Consumer<JsonObject> c) {
		list.forEach(c);
	}

	/** Raccourci pour le nom d'une phase. */
	protected static String phaseLabel() {
		JsonObject v = view();
		return switch (ClientState.phase()) {
			case "NIGHT" -> "Nuit " + ClientState.num(v, "night", 0);
			case "DAY" -> "Jour " + ClientState.num(v, "day", 0) + (ClientState.bool(v, "dayOver") ? " (crépuscule)" : "");
			case "READY" -> "Avant la première nuit";
			case "ENDED" -> "Partie terminée";
			default -> "Salon";
		};
	}
}
