package fr.valcombe.client;

import com.google.gson.JsonObject;

import fr.valcombe.client.ui.Theme;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

/** Affichage en jeu : voile de nuit, bandeau de phase, vote en cours ; sons d'ambiance. */
public final class Hud {
	private static String lastPhase = "LOBBY";
	private static String lastVote = "";
	private static long voteStart;
	private static float veil;
	private static long wakeAt;
	/** Carte de rôle qui tombe et se retourne au début de la partie. */
	private static JsonObject card;
	private static String cardKey = "";
	private static long cardAt;
	private static boolean cardFlipped;

	private Hud() {
	}

	public static void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("valcombe", "hud"), (g, delta) -> draw(g));
	}

	/** Appelé à chaque nouvelle vue : sons de transition. */
	static void onView(JsonObject view, boolean promptAppeared) {
		Minecraft mc = Minecraft.getInstance();
		String phase = ClientState.phase();
		if (!phase.equals(lastPhase)) {
			if (phase.equals("NIGHT")) {
				Ambience.play("cloche", 0.8f, 0.75f);
			} else if (phase.equals("DAY") && lastPhase.equals("NIGHT")) {
				Ambience.play("cloche", 1f, 1f);
			} else if (phase.equals("LOBBY") && (lastPhase.equals("DAY") || lastPhase.equals("NIGHT"))) {
				String winner = ClientState.str(view, "lastWinner");
				if (winner != null && !winner.isEmpty()) {
					Ambience.play(winner.equals("EVIL") ? "victoire_mal" : "victoire_bien", 0.9f, 1f);
				}
			}
			lastPhase = phase;
			Ambience.onPhase(phase);
		}
		if (promptAppeared && phase.equals("NIGHT")) {
			wakeAt = System.currentTimeMillis();
			Ambience.play("reveil", 0.9f, 1f);
		} else if (promptAppeared) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 1.0f));
		}
		JsonObject role = ClientState.obj(view, "role");
		String roleKey = role == null ? "" : ClientState.str(role, "id") + "@" + ClientState.num(view, "night", 0);
		if (role == null) {
			cardKey = "";
		} else if (cardKey.isEmpty() || !cardKey.startsWith(ClientState.str(role, "id") + "@")) {
			// Nouveau rôle (début de partie ou rôle changé par le Conteur)
			cardKey = roleKey;
			card = role;
			cardAt = System.currentTimeMillis();
			cardFlipped = false;
		}
		JsonObject vote = ClientState.obj(view, "vote");
		String key = vote == null ? "" : ClientState.str(vote, "nominee");
		if (!key.equals(lastVote)) {
			lastVote = key;
			voteStart = System.currentTimeMillis();
		}
	}

	/** La carte tombe face cachée, se retourne, reste lisible quelques secondes puis s'efface. */
	private static void drawCard(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		long t = System.currentTimeMillis() - cardAt;
		if (card == null || t > 7000) {
			return;
		}
		int cw = Math.min(150, w / 3);
		int ch = cw * 3 / 2;
		int cx = w / 2;
		float drop = Math.min(1f, t / 500f);
		int cy = (int) (h / 2 - (1 - drop) * (1 - drop) * h / 2);
		float flip = t < 900 ? 1f : t < 1500 ? (float) Math.cos(Math.PI * (t - 900) / 600.0) : -1f;
		if (flip < 0 && !cardFlipped) {
			cardFlipped = true;
			Ambience.play("parchemin", 1f, 1f);
		}
		float fadeOut = t > 6200 ? 1 - (t - 6200) / 800f : 1f;
		int alpha = (int) (fadeOut * 255);
		if (alpha < 8) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(Math.max(0.02f, Math.abs(flip)), 1f);
		g.pose().translate(-cx, -cy);
		int x = cx - cw / 2;
		int y = cy - ch / 2;
		boolean evil = "EVIL".equals(ClientState.str(card, "team"));
		if (flip > 0) {
			g.fill(x, y, x + cw, y + ch, (alpha << 24) | 0x5A1E2A);
			g.outline(x + 3, y + 3, cw - 6, ch - 6, (alpha << 24) | 0xD9B45A);
			Theme.image(g, Theme.tex("token_neutral"), cx - cw / 4, cy - cw / 4, cw / 2, cw / 2, 32, 32);
		} else {
			g.fill(x, y, x + cw, y + ch, (alpha << 24) | 0xE8D9B5);
			g.outline(x + 3, y + 3, cw - 6, ch - 6, (alpha << 24) | (evil ? 0xA83232 : 0x3F6FA8));
			int size = cw / 2;
			Theme.token(g, cx, y + 12 + size / 2, size, ClientState.str(card, "id"), evil ? "EVIL" : "GOOD", true, false, false);
			String name = ClientState.str(card, "name");
			g.text(mc.font, name, cx - mc.font.width(name) / 2, y + 18 + size, (alpha << 24) | 0x1B1214, false);
			String side = evil ? "Camp du mal" : "Camp du bien";
			g.text(mc.font, side, cx - mc.font.width(side) / 2, y + 30 + size, (alpha << 24) | (evil ? 0xA83232 : 0x3F6FA8), false);
			int ty = y + 44 + size;
			for (var line : mc.font.split(net.minecraft.network.chat.Component.literal(ClientState.str(card, "text")), cw - 16)) {
				if (ty > y + ch - 12) {
					break;
				}
				g.text(mc.font, line, x + 8, ty, (alpha << 24) | 0x3A2E2A, false);
				ty += mc.font.lineHeight;
			}
		}
		g.pose().popMatrix();
	}

	private static void draw(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		String phase = ClientState.phase();
		int w = g.guiWidth();
		int h = g.guiHeight();

		// Voile de nuit qui s'installe doucement
		float target = phase.equals("NIGHT") ? 1f : 0f;
		veil += (target - veil) * 0.05f;
		if (veil > 0.01f) {
			int a = (int) (veil * 150);
			g.fill(0, 0, w, h, (a << 24) | 0x0A0612);
			int edge = (int) (veil * 90);
			g.fillGradient(0, 0, w, 40, (edge << 24), 0);
			g.fillGradient(0, h - 40, w, h, 0, (edge << 24));
		}

		// Réveil : le voile s'éclaircit et « Tu te réveilles » s'affiche en grand.
		long since = System.currentTimeMillis() - wakeAt;
		if (since < 2600) {
			float t = since / 2600f;
			int alpha = (int) ((1 - t) * 220);
			g.fill(0, 0, w, h, (alpha << 24) | 0x05030A);
			int textAlpha = (int) (255 * Math.min(1f, (1 - t) * 2));
			if (textAlpha > 8) {
				String wake = "Tu te réveilles";
				g.pose().pushMatrix();
				g.pose().scale(2.5f, 2.5f);
				g.text(mc.font, wake, (int) (w / 2.5f / 2 - mc.font.width(wake) / 2f), (int) (h / 2.5f / 2 - 8),
					(textAlpha << 24) | 0xD9B45A, true);
				g.pose().popMatrix();
			}
		}
		drawCard(g, mc, w, h);
		if (phase.equals("LOBBY")) {
			return;
		}
		JsonObject v = ClientState.view();
		String label = switch (phase) {
			case "NIGHT" -> "Nuit " + ClientState.num(v, "night", 0);
			case "DAY" -> "Jour " + ClientState.num(v, "day", 0);
			case "READY" -> "La partie va commencer";
			default -> "";
		};
		int tw = mc.font.width(label) + 30;
		int x = w / 2 - tw / 2;
		g.fill(x, 4, x + tw, 20, 0xD01B1214);
		g.outline(x, 4, tw, 16, Theme.BRONZE);
		Theme.image(g, Theme.tex(phase.equals("NIGHT") ? "moon" : "sun"), x + 4, 6, 12, 12, 16, 16);
		g.text(mc.font, label, x + 20, 8, Theme.GOLD, false);

		JsonObject vote = ClientState.obj(v, "vote");
		if (vote != null) {
			boolean defense = "DEFENSE".equals(ClientState.str(vote, "stage"));
			int count = ClientState.num(vote, "count", 0);
			int threshold = Math.max(1, ClientState.num(vote, "threshold", 1));
			String text = defense
				? ClientState.str(vote, "nominee") + " se défend — " + ClientState.num(vote, "defenseLeft", 0) + " s"
				: "Vote sur " + ClientState.str(vote, "nominee") + " — " + count + " voix / seuil " + threshold;
			int vw = mc.font.width(text) + 26;
			int vx = w / 2 - vw / 2;
			g.fill(vx, 24, vx + vw, 38, 0xD05A1E2A);
			g.outline(vx, 24, vw, 14, Theme.GOLD);
			Theme.image(g, Theme.tex("hourglass"), vx + 4, 25, 12, 12, 16, 16);
			g.text(mc.font, text, vx + 20, 27, Theme.PARCH, false);
			if (!defense) {
				float frac = Math.min(1f, count / (float) threshold);
				g.fill(vx + 1, 37, vx + 1 + (int) ((vw - 2) * frac), 38, count >= threshold ? Theme.EVIL : Theme.GOLD);
			}
		}
		if (ClientState.obj(v, "prompt") != null && mc.gui.screen() == null) {
			String hint = "Une action t'attend — touche J";
			int hw = mc.font.width(hint);
			g.text(mc.font, hint, w / 2 - hw / 2, h - 60, Theme.GOLD, true);
		}
	}
}
