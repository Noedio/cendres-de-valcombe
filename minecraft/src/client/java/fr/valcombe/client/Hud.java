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
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 0.6f));
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

	/** Touche K : la carte de rôle réapparaît, déjà retournée. */
	public static void showCard() {
		JsonObject role = ClientState.obj(ClientState.view(), "role");
		if (role == null) {
			return;
		}
		card = role;
		cardAt = System.currentTimeMillis() - 1500;
		cardFlipped = true;
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

	/**
	 * L'horloge du vote, en bandeau : un carré par votant, dans l'ordre où l'aiguille passe.
	 * Rouge : main comptée ; gris : passé sans voter ; bleu : main levée, pas encore passé ;
	 * cadre doré : l'aiguille est là ; cadre blanc : toi.
	 */
	private static int drawClock(GuiGraphicsExtractor g, Minecraft mc, JsonObject vote, int w, int y) {
		var order = vote.getAsJsonArray("order");
		if (order == null || order.isEmpty()) {
			return y;
		}
		java.util.Set<String> locked = new java.util.HashSet<>();
		java.util.Set<String> hands = new java.util.HashSet<>();
		if (vote.has("lockedNames")) {
			vote.getAsJsonArray("lockedNames").forEach(e -> locked.add(e.getAsString()));
		}
		if (vote.has("hands")) {
			vote.getAsJsonArray("hands").forEach(e -> hands.add(e.getAsString()));
		}
		String me = mc.player == null ? "" : mc.player.getScoreboardName();
		int pos = ClientState.num(vote, "clockPos", -1);
		int n = order.size();
		int box = 12;
		int gap = 3;
		int total = n * box + (n - 1) * gap;
		int x0 = w / 2 - total / 2;
		g.fill(x0 - 4, y - 3, x0 + total + 4, y + box + 3, 0xB01B1214);
		int mine = -1;
		for (int i = 0; i < n; i++) {
			String name = order.get(i).getAsString();
			int x = x0 + i * (box + gap);
			int fill = locked.contains(name) ? Theme.EVIL
				: i < pos ? 0xFF4A4448
				: hands.contains(name) ? 0xFF3F6FA8 : 0xFF2A2024;
			g.fill(x, y, x + box, y + box, fill);
			if (locked.contains(name)) {
				g.text(mc.font, "✋", x + 2, y + 2, 0xFFFFFFFF, false);
			} else if (i >= pos && hands.contains(name)) {
				g.text(mc.font, "↑", x + 3, y + 2, 0xFFFFFFFF, false); // main levée, pas encore comptée
			}
			if (i == pos) {
				g.outline(x - 2, y - 2, box + 4, box + 4, Theme.GOLD);
			}
			if (name.equals(me)) {
				g.outline(x, y, box, box, 0xFFFFFFFF);
				mine = i;
			}
		}
		y += box + 6;
		if (mine >= 0 && mine >= pos) {
			int left = mine - Math.max(0, pos);
			String t = left == 0 ? "L'aiguille est devant toi !" : "L'aiguille arrive à toi dans " + left + " trône" + (left > 1 ? "s" : "");
			centered(g, mc, t, w, y, Theme.GOLD, false);
			y += 12;
		}
		return y;
	}

	/**
	 * Conteur : au-dessus de chaque joueur, son nom et son rôle aux couleurs de son camp, dessinés
	 * par-dessus le monde (donc visibles à travers les murs). Joueurs fictifs : à leur trône.
	 */
	private static void drawStorytellerRoles(GuiGraphicsExtractor g, Minecraft mc, JsonObject v, int w, int h) {
		var grim = v.getAsJsonArray("grimoire");
		if (grim == null || !fr.valcombe.client.ClientConfig.get().rolesAuDessus || mc.level == null
				|| !ClientState.bool(v, "isStoryteller") || mc.gui.screen() != null) {
			return;
		}
		var camera = mc.gameRenderer.mainCamera();
		net.minecraft.world.phys.Vec3 cam = camera.position();
		double yr = Math.toRadians(camera.yRot());
		double pr = Math.toRadians(camera.xRot());
		double[] f = {-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr)};
		double[] r = {-Math.cos(yr), 0, -Math.sin(yr)};
		double[] u = {-Math.sin(yr) * Math.sin(pr), Math.cos(pr), Math.cos(yr) * Math.sin(pr)};
		double focal = (h / 2.0) / Math.tan(Math.toRadians(mc.options.fov().get()) / 2);
		java.util.List<Object[]> labels = new java.util.ArrayList<>(); // {x, y, texte, couleur, distance}
		for (var e : grim) {
			JsonObject row = e.getAsJsonObject();
			String name = ClientState.str(row, "name");
			if (name == null) {
				continue;
			}
			net.minecraft.world.phys.Vec3 at = null;
			for (var p : mc.level.players()) {
				if (p.getScoreboardName().equals(name)) {
					at = p.position().add(0, p.getBbHeight() + 0.9, 0);
				}
			}
			var pos = row.getAsJsonArray("pos");
			if (at == null && pos != null && pos.size() == 3) {
				at = new net.minecraft.world.phys.Vec3(pos.get(0).getAsDouble(), pos.get(1).getAsDouble() + 2.4, pos.get(2).getAsDouble());
			}
			if (at == null) {
				continue;
			}
			double dx = at.x - cam.x;
			double dy = at.y - cam.y;
			double dz = at.z - cam.z;
			double cz = dx * f[0] + dy * f[1] + dz * f[2];
			if (cz < 0.5) {
				continue; // derrière la caméra
			}
			double cx = dx * r[0] + dy * r[1] + dz * r[2];
			double cy = dx * u[0] + dy * u[1] + dz * u[2];
			int sx = (int) (w / 2.0 + cx / cz * focal);
			int sy = (int) (h / 2.0 - cy / cz * focal);
			if (sx < -50 || sx > w + 50 || sy < -20 || sy > h + 20) {
				continue;
			}
			JsonObject role = ClientState.obj(row, "role");
			boolean evil = "EVIL".equals(ClientState.str(role, "team"));
			String label = name + " — " + ClientState.str(role, "name") + (ClientState.bool(row, "alive") ? "" : " †");
			labels.add(new Object[] {sx, sy, label, evil ? 0xFFFF7A70 : 0xFF8CC4FF, cz});
		}
		// Les plus proches d'abord ; une étiquette qui en chevauche une autre monte d'un cran.
		labels.sort(java.util.Comparator.comparingDouble(l -> (double) l[4]));
		java.util.List<int[]> placed = new java.util.ArrayList<>();
		for (Object[] l : labels) {
			String label = (String) l[2];
			int tw = mc.font.width(label);
			// Jamais sous les barres du bas (chat, aides, inventaire) ni hors de l'écran sur les côtés.
			int sx = Math.max(tw / 2 + 4, Math.min(w - tw / 2 - 4, (int) l[0]));
			int sy = (int) l[1];
			if (sy > h - 90 || sy < 46) {
				continue;
			}
			for (int tries = 0; tries < 8; tries++) {
				boolean hit = false;
				for (int[] box : placed) {
					if (sx - tw / 2 - 4 < box[2] && sx + tw / 2 + 4 > box[0] && sy - 3 < box[3] && sy + 11 > box[1]) {
						hit = true;
						sy = box[1] - 12;
					}
				}
				if (!hit) {
					break;
				}
			}
			placed.add(new int[] {sx - tw / 2 - 4, sy - 3, sx + tw / 2 + 4, sy + 11});
			int color = (int) l[3];
			g.fill(sx - tw / 2 - 3, sy - 2, sx + tw / 2 + 3, sy + 9, 0xC8101018);
			g.fill(sx - tw / 2 - 3, sy + 9, sx + tw / 2 + 3, sy + 10, color);
			g.text(mc.font, label, sx - tw / 2, sy, color, false);
		}
	}

	/** Tableau du jour, en haut à droite : nominations, voix, et qui est sur le billot. */
	private static void drawToday(GuiGraphicsExtractor g, Minecraft mc, JsonObject v, int top) {
		JsonObject day = ClientState.obj(v, "today");
		if (day == null || !"DAY".equals(ClientState.phase()) || mc.gui.screen() != null) {
			return;
		}
		java.util.List<String[]> lines = new java.util.ArrayList<>(); // {texte, couleur}
		String block = ClientState.str(day, "block");
		var noms = day.getAsJsonArray("nominations");
		if (noms == null || noms.isEmpty()) {
			lines.add(new String[] {"Aucune accusation pour l'instant.", "gray"});
		} else {
			for (var e : noms) {
				JsonObject n = e.getAsJsonObject();
				String target = ClientState.str(n, "target");
				int votes = ClientState.num(n, "votes", -1);
				String t = ClientState.str(n, "by") + " → " + target + " : " + (votes < 0 ? "vote en cours" : votes + " voix");
				var vs = n.getAsJsonArray("voters");
				if (vs != null && !vs.isEmpty()) {
					StringBuilder who = new StringBuilder();
					vs.forEach(x -> who.append(who.length() == 0 ? "" : ", ").append(x.getAsString()));
					t += " (" + (who.length() > 40 ? who.substring(0, 38) + "…" : who) + ")";
				}
				lines.add(new String[] {t, target.equals(block) ? "red" : votes < 0 ? "gold" : "parch"});
			}
		}
		int threshold = ClientState.num(day, "threshold", 1);
		if (block != null && !block.isEmpty()) {
			lines.add(new String[] {"☠ Sur le billot : " + block + " (" + ClientState.num(day, "blockVotes", 0) + " voix)", "red"});
		} else if (ClientState.bool(day, "tie")) {
			lines.add(new String[] {"Égalité : personne sur le billot", "gold"});
		} else {
			lines.add(new String[] {"Personne sur le billot (seuil : " + threshold + " voix)", "gray"});
		}
		if (ClientState.bool(day, "forbidden")) {
			lines.add(new String[] {"Pas d'accusation aujourd'hui (règle du scénario)", "gray"});
		} else if (!ClientState.bool(v, "nominationsOpen")) {
			lines.add(new String[] {"Accusations fermées", "gray"});
		} else if (ClientState.bool(day, "canNominate")) {
			lines.add(new String[] {"Tu peux accuser : plume en main, vise, clic droit", "gold"});
		}
		// Encadré en haut à DROITE (le chat est à gauche), lignes repliées à la largeur permise.
		String title = "Accusations du jour";
		int sw = mc.getWindow().getGuiScaledWidth();
		int maxW = Math.max(120, Math.min(250, sw / 2 - 20));
		java.util.List<net.minecraft.util.FormattedCharSequence> seqs = new java.util.ArrayList<>();
		java.util.List<Integer> colors = new java.util.ArrayList<>();
		int width = mc.font.width(title);
		for (String[] l : lines) {
			int c = switch (l[1]) {
				case "red" -> 0xFFE05A4A;
				case "gold" -> Theme.GOLD;
				case "gray" -> 0xFF9A9090;
				default -> Theme.PARCH;
			};
			for (var seq : mc.font.split(net.minecraft.network.chat.Component.literal(l[0]), maxW)) {
				seqs.add(seq);
				colors.add(c);
				width = Math.max(width, mc.font.width(seq));
			}
		}
		int x = sw - width - 9;
		int y = top;
		int h = 16 + seqs.size() * 10;
		g.fill(x - 4, y - 4, x + width + 4, y + h, 0xD01B1214);
		g.outline(x - 4, y - 4, width + 8, h + 4, Theme.BRONZE);
		g.text(mc.font, title, x, y, Theme.GOLD, false);
		g.fill(x, y + 10, x + width, y + 11, 0x80B08D57);
		y += 14;
		for (int i = 0; i < seqs.size(); i++) {
			g.text(mc.font, seqs.get(i), x, y, colors.get(i), false);
			y += 10;
		}
	}

	/**
	 * Texte centré qui passe à la ligne s'il est plus large que l'écran. Avec up, les lignes
	 * supplémentaires montent (pour les messages du bas de l'écran). Renvoie la hauteur occupée.
	 */
	private static int centered(GuiGraphicsExtractor g, Minecraft mc, String text, int w, int y, int color, boolean up) {
		var lines = mc.font.split(net.minecraft.network.chat.Component.literal(text), Math.max(80, w - 24));
		int lh = mc.font.lineHeight + 1;
		int top = up ? y - (lines.size() - 1) * lh : y;
		for (int i = 0; i < lines.size(); i++) {
			var line = lines.get(i);
			g.text(mc.font, line, w / 2 - mc.font.width(line) / 2, top + i * lh, color, true);
		}
		return lines.size() * lh;
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
		// Le serveur n'a pas la même version du mod : des écrans et des commandes ne marcheront pas.
		String other = ClientState.serverVersionMismatch();
		if (other != null) {
			String t = "Le serveur a une autre version du mod Valcombe (serveur : " + other + ", vous : "
				+ ClientState.modVersion() + "). L'hôte doit le mettre à jour.";
			var lines = mc.font.split(net.minecraft.network.chat.Component.literal(t), Math.max(80, w - 30));
			int lw = 0;
			for (var l : lines) {
				lw = Math.max(lw, mc.font.width(l));
			}
			int top = h - 96 - (lines.size() - 1) * 10;
			g.fill(w / 2 - lw / 2 - 6, top - 3, w / 2 + lw / 2 + 6, h - 82, 0xE0801818);
			for (int i = 0; i < lines.size(); i++) {
				g.text(mc.font, lines.get(i), w / 2 - mc.font.width(lines.get(i)) / 2, top + i * 10, 0xFFFFFFFF, false);
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
			int y = 42;
			if (!defense) {
				y = drawClock(g, mc, vote, w, y);
			}
			if (ClientState.bool(vote, "canVote")) {
				boolean up = ClientState.bool(vote, "myHand");
				String hand = up ? "✋ Ta main est levée — H ou plume (clic droit) pour la baisser"
					: "Ta main est baissée — H ou plume (clic droit) pour la lever";
				centered(g, mc, hand, w, y, up ? 0xFF8FD18F : Theme.PARCH, false);
			}
		}
		drawToday(g, mc, v, vote == null ? 28 : 84);
		drawStorytellerRoles(g, mc, v, w, h);
		// Conteur : joueurs déconnectés
		var absents = v.getAsJsonArray("absents");
		if (absents != null && !absents.isEmpty()) {
			StringBuilder names = new StringBuilder();
			absents.forEach(e -> names.append(names.length() == 0 ? "" : ", ").append(e.getAsString()));
			String t = "Absents : " + names + " (fiche du joueur : le faire relayer)";
			centered(g, mc, t, w, 42, 0xFFE05A4A, false);
		}
		// Partie en pause : tout le monde le voit
		if (ClientState.bool(v, "paused") && mc.gui.screen() == null) {
			centered(g, mc, "⏸ Partie en pause", w, 54, 0xFFE8C36A, true);
		}
		// Nuit d'attente : ceux qui dorment savent quoi faire
		if (phase.equals("NIGHT") && ClientState.obj(v, "prompt") == null && !ClientState.bool(v, "isStoryteller")
				&& ClientState.num(v, "mySeat", -1) >= 0 && mc.gui.screen() == null) {
			String t = "Tu dors… Le Conteur réveille chacun à son tour. (J : ton carnet)";
			centered(g, mc, t, w, h - 60, 0xFFB8A6C8, true);
		}
		// Conteur, la nuit : qui est réveillé et ce qu'on attend
		JsonObject ns = ClientState.obj(v, "nightStep");
		if (ns != null) {
			String who = ClientState.str(ns, "player");
			String wait = ClientState.str(ns, "waiting");
			String t = "Réveil : " + ClientState.str(ns, "title") + (who == null || who.isEmpty() ? "" : " (" + who + ")")
				+ (wait == null || wait.isEmpty() ? "" : " — " + wait);
			int nw = mc.font.width(t) + 12;
			g.fill(w / 2 - nw / 2, 24, w / 2 + nw / 2, 38, 0xD02A1840);
			g.outline(w / 2 - nw / 2, 24, nw, 14, 0xFFB08CD8);
			g.text(mc.font, t, w / 2 - nw / 2 + 6, 27, 0xFFE8DCF8, false);
		}
		// Conversation privée entre joueurs, et invitation en attente
		String talk = ClientState.str(v, "talkingWith");
		String invite = ClientState.str(v, "talkInvite");
		if (talk != null && !talk.isEmpty()) {
			String t = "En privé avec " + talk + " : vous seuls vous entendez (menu J pour revenir)";
			centered(g, mc, t, w, h - 84, 0xFFC8A8F0, true);
		} else if (invite != null && !invite.isEmpty()) {
			String t = invite + " veut te parler en privé — touche J pour répondre";
			centered(g, mc, t, w, h - 84, Theme.GOLD, true);
		}
		// Aparté avec le Conteur (vocal seul à seul)
		String aside = ClientState.str(v, "aside");
		String me = mc.player == null ? "" : mc.player.getScoreboardName();
		if (aside != null && !aside.isEmpty() && (aside.equals(me) || ClientState.bool(v, "isStoryteller"))) {
			String t = aside.equals(me) ? "En aparté avec le Conteur : vous seuls vous entendez"
				: "En aparté avec " + aside + " (accroupi + clic droit au bâton pour finir)";
			centered(g, mc, t, w, h - 72, 0xFFC8A8F0, true);
		}
		if (ClientState.obj(v, "prompt") != null && mc.gui.screen() == null) {
			String hint = "Une action t'attend — touche J";
			centered(g, mc, hint, w, h - 60, Theme.GOLD, true);
		}
	}
}
