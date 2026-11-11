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
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BELL_RESONATE, 0.7f, 0.6f));
			} else if (phase.equals("DAY") && lastPhase.equals("NIGHT")) {
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BELL_BLOCK, 1.2f, 0.8f));
			}
			lastPhase = phase;
		}
		if (promptAppeared && phase.equals("NIGHT")) {
			wakeAt = System.currentTimeMillis();
		}
		if (promptAppeared) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 1.0f));
		}
		JsonObject vote = ClientState.obj(view, "vote");
		String key = vote == null ? "" : ClientState.str(vote, "nominee");
		if (!key.equals(lastVote)) {
			lastVote = key;
			voteStart = System.currentTimeMillis();
		}
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
