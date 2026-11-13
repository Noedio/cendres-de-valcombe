package fr.valcombe.client;

import com.google.gson.JsonObject;

import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdGlyphs;
import fr.valcombe.client.hd.HdSprites;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTokenArt;
import fr.valcombe.client.ui.Theme;

import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

/**
 * Affichage en jeu : voile de nuit, bandeau de phase, vote en cours ; sons d'ambiance.
 *
 * <p>Tout ce qui porte du texte est dessiné en haute résolution (boîte à outils fr.valcombe.client.hd) : les positions
 * restent pensées en unités d'interface, comme avant, et sont converties en pixels réels au moment de dessiner
 * (hd.px pour une coordonnée, hd.u pour une longueur). Les voiles plein écran, sans texte, restent en unités.
 */
public final class Hud {
	/** Texte des bandeaux (phase, vote, réveil). */
	private static final HdStyle STRIP = HdStyle.FORT.size(10);
	/** Messages au milieu de l'écran (consignes, apartés, pause). */
	private static final HdStyle MESSAGE = HdStyle.FORT.size(10);
	/** Lignes serrées du tableau du jour et des étiquettes du Conteur. */
	private static final HdStyle SMALL = HdStyle.TEXTE.size(9.5f);
	/** Interligne des messages et du tableau du jour, en unités. */
	private static final double PITCH = 10.5;
	/** Ombre portée des messages posés sur le monde. */
	private static final int SHADOW = 0xC0000000;

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

	/** Couleur RGB avec une opacité (0 à 255). */
	private static int alpha(int alpha, int rgb) {
		return Math.max(0, Math.min(255, alpha)) << 24 | rgb & 0xFFFFFF;
	}

	/** Interligne des messages, en pixels réels. */
	private static int pitch(Hd hd) {
		return hd.u(PITCH);
	}

	/** La carte tombe face cachée, se retourne, reste lisible quelques secondes puis s'efface. */
	private static void drawCard(Hd hd, int w, int h) {
		long t = System.currentTimeMillis() - cardAt;
		if (card == null || t > 7000) {
			return;
		}
		// Géométrie en unités d'interface (comme avant), dessin en pixels réels.
		int cwU = Math.min(150, w / 3);
		int chU = cwU * 3 / 2;
		float drop = Math.min(1f, t / 500f);
		int cyU = (int) (h / 2 - (1 - drop) * (1 - drop) * h / 2);
		float flip = t < 900 ? 1f : t < 1500 ? (float) Math.cos(Math.PI * (t - 900) / 600.0) : -1f;
		if (flip < 0 && !cardFlipped) {
			cardFlipped = true;
			Ambience.play("parchemin", 1f, 1f);
		}
		float fadeOut = t > 6200 ? 1 - (t - 6200) / 800f : 1f;
		int a = (int) (fadeOut * 255);
		if (a < 8) {
			return;
		}
		int cw = hd.u(cwU);
		int ch = hd.u(chU);
		int cx = hd.px(w / 2);
		int cy = hd.px(cyU);
		var pose = hd.graphics().pose();
		pose.pushMatrix();
		pose.translate(cx, cy);
		pose.scale(Math.max(0.02f, Math.abs(flip)), 1f);
		pose.translate(-cx, -cy);
		int x = cx - cw / 2;
		int y = cy - ch / 2;
		int frameTint = alpha(a, 0xFFFFFF);
		boolean evil = "EVIL".equals(ClientState.str(card, "team"));
		if (flip > 0) {
			// Dos de la carte : lie-de-vin, cadre, jeton neutre.
			hd.fill(x + hd.u(2), y + hd.u(2), x + cw - hd.u(2), y + ch - hd.u(2), alpha(a, 0x5A1E2A));
			hd.sprite(HdSprites.CADRE, x, y, cw, ch, frameTint);
			hd.image(Theme.tex("token_neutral"), cx - cw / 4, cy - cw / 4, cw / 2, cw / 2, frameTint);
		} else {
			// Face : parchemin, jeton du rôle, nom, camp, texte du rôle.
			hd.fill(x + hd.u(2), y + hd.u(2), x + cw - hd.u(2), y + ch - hd.u(2), alpha(a, 0xE8D9B5));
			hd.sprite(HdSprites.CADRE, x, y, cw, ch, frameTint);
			int side = evil ? 0xA83232 : 0x3F6FA8;
			hd.frame(x + hd.u(4), y + hd.u(4), cw - hd.u(8), ch - hd.u(8), Math.max(1, hd.u(0.75)), alpha(a * 3 / 4, side));
			int size = cw / 2;
			HdTokenArt.drawWithAlpha(hd, cx - size / 2, y + hd.u(12), size, ClientState.str(card, "id"), evil ? "EVIL" : "GOOD", true,
				false, false, a);
			int ty = y + hd.u(14) + size;
			String name = hd.ellipsize(HdStyle.FORT, ClientState.str(card, "name"), cw - hd.u(12));
			hd.textCentered(HdStyle.FORT, name, cx, ty, alpha(a, 0x1B1214));
			ty += hd.lineHeight(HdStyle.FORT);
			hd.textCentered(SMALL, evil ? "Camp du mal" : "Camp du bien", cx, ty, alpha(a, side));
			ty += hd.lineHeight(SMALL) + hd.u(2);
			int lineH = hd.u(PITCH);
			for (String line : hd.wrap(SMALL, ClientState.str(card, "text"), cw - hd.u(16))) {
				if (ty + hd.lineHeight(SMALL) > y + ch - hd.u(8)) {
					break;
				}
				hd.text(SMALL, line, x + hd.u(8), ty, alpha(a, 0x3A2E2A));
				ty += lineH;
			}
		}
		pose.popMatrix();
	}

	/**
	 * L'horloge du vote, en bandeau : un carré par votant, dans l'ordre où l'aiguille passe.
	 * Rouge : main comptée ; gris : passé sans voter ; bleu : main levée, pas encore passé ;
	 * cadre doré : l'aiguille est là ; cadre blanc : toi. y et le retour sont en pixels réels.
	 */
	private static int drawClock(Hd hd, Minecraft mc, JsonObject vote, int w, int y) {
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
		int box = hd.u(12);
		int gap = hd.u(3);
		int total = n * box + (n - 1) * gap;
		int x0 = hd.px(w / 2) - total / 2;
		hd.sprite(HdSprites.INFOBULLE, x0 - hd.u(5), y - hd.u(4), total + hd.u(10), box + hd.u(8));
		int line = Math.max(1, hd.u(0.75));
		int mine = -1;
		for (int i = 0; i < n; i++) {
			String name = order.get(i).getAsString();
			int x = x0 + i * (box + gap);
			int fill = locked.contains(name) ? Theme.EVIL
				: i < pos ? 0xFF4A4448
				: hands.contains(name) ? 0xFF3F6FA8 : 0xFF2A2024;
			hd.fill(x, y, x + box, y + box, fill);
			if (locked.contains(name)) {
				HdGlyphs.hand(hd, x + hd.u(2), y + hd.u(2), box - hd.u(4), 0xFFFFFFFF);
			} else if (i >= pos && hands.contains(name)) {
				// main levée, pas encore comptée : une flèche vers le haut
				int cx = x + box / 2;
				hd.triangle(cx, y + hd.u(2), hd.u(6), hd.u(4), 0xFFFFFFFF);
				hd.fill(cx - hd.u(1), y + hd.u(6), cx + hd.u(1), y + box - hd.u(2), 0xFFFFFFFF);
			}
			if (i == pos) {
				hd.frame(x - hd.u(2), y - hd.u(2), box + hd.u(4), box + hd.u(4), line, Theme.GOLD);
			}
			if (name.equals(me)) {
				hd.frame(x, y, box, box, line, 0xFFFFFFFF);
				mine = i;
			}
		}
		y += box + hd.u(7);
		if (mine >= 0 && mine >= pos) {
			int left = mine - Math.max(0, pos);
			String t = left == 0 ? "L'aiguille est devant toi !" : "L'aiguille arrive à toi dans " + left + " trône" + (left > 1 ? "s" : "");
			y += centered(hd, t, w, y, Theme.GOLD, false) + hd.u(2);
		}
		return y;
	}

	/**
	 * Conteur : au-dessus de chaque joueur, son nom et son rôle aux couleurs de son camp, dessinés
	 * par-dessus le monde (donc visibles à travers les murs). Joueurs fictifs : à leur trône.
	 */
	private static void drawStorytellerRoles(Hd hd, Minecraft mc, JsonObject v) {
		var grim = v.getAsJsonArray("grimoire");
		if (grim == null || !fr.valcombe.client.ClientConfig.get().rolesAuDessus || mc.level == null
				|| !ClientState.bool(v, "isStoryteller") || mc.gui.screen() != null) {
			return;
		}
		// Projection en pixels réels : l'étiquette tombe au pixel près au-dessus de la tête.
		int w = hd.screenWidth();
		int h = hd.screenHeight();
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
			if (sx < -hd.u(50) || sx > w + hd.u(50) || sy < -hd.u(20) || sy > h + hd.u(20)) {
				continue;
			}
			JsonObject role = ClientState.obj(row, "role");
			boolean evil = "EVIL".equals(ClientState.str(role, "team"));
			String label = name + " · " + ClientState.str(role, "name") + (ClientState.bool(row, "alive") ? "" : " †");
			labels.add(new Object[] {sx, sy, label, evil ? 0xFFFF7A70 : 0xFF8CC4FF, cz});
		}
		// Les plus proches d'abord ; une étiquette qui en chevauche une autre monte d'un cran.
		labels.sort(java.util.Comparator.comparingDouble(l -> (double) l[4]));
		java.util.List<int[]> placed = new java.util.ArrayList<>();
		int lh = hd.lineHeight(SMALL);
		int pad = hd.u(3);
		int step = lh + hd.u(1);
		for (Object[] l : labels) {
			String label = (String) l[2];
			int tw = hd.width(SMALL, label);
			// Jamais sous les barres du bas (chat, aides, inventaire) ni hors de l'écran sur les côtés.
			int sx = Math.max(tw / 2 + hd.u(4), Math.min(w - tw / 2 - hd.u(4), (int) l[0]));
			int sy = (int) l[1];
			if (sy > h - hd.u(90) || sy < hd.u(46)) {
				continue;
			}
			for (int tries = 0; tries < 8; tries++) {
				boolean hit = false;
				for (int[] box : placed) {
					if (sx - tw / 2 - pad < box[2] && sx + tw / 2 + pad > box[0] && sy < box[3] && sy + step > box[1]) {
						hit = true;
						sy = box[1] - step;
					}
				}
				if (!hit) {
					break;
				}
			}
			placed.add(new int[] {sx - tw / 2 - pad, sy, sx + tw / 2 + pad, sy + step});
			int color = (int) l[3];
			hd.fill(sx - tw / 2 - pad, sy, sx + tw / 2 + pad, sy + lh, 0xC8101018);
			hd.fill(sx - tw / 2 - pad, sy + lh, sx + tw / 2 + pad, sy + lh + Math.max(1, hd.u(0.75)), color);
			hd.text(SMALL, label, sx - tw / 2, sy, color);
		}
	}

	/** Tableau du jour, en haut à droite : nominations, voix, et qui est sur le billot. top en pixels réels. */
	private static void drawToday(Hd hd, Minecraft mc, JsonObject v, int top) {
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
				JsonObject current = ClientState.obj(v, "vote");
				boolean now = current != null && target.equals(ClientState.str(current, "nominee"));
				String t = ClientState.str(n, "by") + " → " + target + " : "
					+ (votes >= 0 ? votes + " voix" : now ? "vote en cours" : "vote à venir");
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
		} else if (ClientState.bool(v, "councilVoting")) {
			lines.add(new String[] {"Votes en cours, dans l'ordre des accusations", "gold"});
		} else if (!ClientState.bool(v, "nominationsOpen")) {
			lines.add(new String[] {"Accusations fermées", "gray"});
		} else if (ClientState.bool(v, "council") && ClientState.bool(day, "canNominate")) {
			lines.add(new String[] {"Conseil ouvert : accuse (plume en main, vise, clic droit). Les votes viendront après.", "gold"});
		} else if (ClientState.bool(day, "canNominate")) {
			lines.add(new String[] {"Tu peux accuser : plume en main, vise, clic droit", "gold"});
		}
		// Encadré en haut à DROITE (le chat est à gauche), lignes repliées à la largeur permise.
		String title = "Accusations du jour";
		HdStyle titleStyle = HdStyle.SECTION.size(9);
		int sw = hd.screenWidth();
		int maxW = hd.u(Math.max(120, Math.min(250, mc.getWindow().getGuiScaledWidth() / 2 - 20)));
		List<String> texts = new java.util.ArrayList<>();
		List<Integer> colors = new java.util.ArrayList<>();
		int width = hd.width(titleStyle, title);
		for (String[] l : lines) {
			int c = switch (l[1]) {
				case "red" -> 0xFFE05A4A;
				case "gold" -> Theme.GOLD;
				case "gray" -> 0xFF9A9090;
				default -> Theme.PARCH;
			};
			for (String part : hd.wrap(SMALL, l[0], maxW)) {
				texts.add(part);
				colors.add(c);
				width = Math.max(width, HdGlyphs.width(hd, SMALL, part));
			}
		}
		int pitch = pitch(hd);
		int pad = hd.u(6);
		int x = sw - width - hd.u(11);
		int y = top;
		int titleH = hd.lineHeight(titleStyle);
		int h = titleH + hd.u(4) + (texts.size() - 1) * pitch + hd.lineHeight(SMALL);
		hd.sprite(HdSprites.INFOBULLE, x - pad, y - pad, width + 2 * pad, h + 2 * pad);
		hd.text(titleStyle, title, x, y, Theme.GOLD);
		hd.fill(x, y + titleH + hd.u(1), x + width, y + titleH + hd.u(1) + Math.max(1, hd.u(0.5)), 0x80B08D57);
		y += titleH + hd.u(4);
		for (int i = 0; i < texts.size(); i++) {
			HdGlyphs.text(hd, SMALL, texts.get(i), x, y, colors.get(i));
			y += pitch;
		}
	}

	/**
	 * Texte centré qui passe à la ligne s'il est plus large que l'écran. Avec up, les lignes supplémentaires
	 * montent (pour les messages du bas de l'écran). w en unités d'interface, y et le retour en pixels réels.
	 */
	private static int centered(Hd hd, String text, int w, int y, int color, boolean up) {
		List<String> lines = hd.wrap(MESSAGE, text, hd.u(Math.max(80, w - 40)));
		int lh = pitch(hd) + hd.u(1);
		int top = up ? y - (lines.size() - 1) * lh : y;
		int cx = hd.px(w / 2);
		// Une plaque sombre sous le message : il se lit sur n'importe quel décor, et ne se mêle plus aux textes posés
		// dans le monde (accueil du hub, panneaux), qu'il recouvre au lieu de s'y superposer.
		int widest = 0;
		for (String line : lines) {
			widest = Math.max(widest, HdGlyphs.width(hd, MESSAGE, line));
		}
		int padX = hd.u(7);
		int padY = hd.u(2.5);
		hd.sprite(HdSprites.INFOBULLE, cx - widest / 2 - padX, top - padY, widest + 2 * padX,
			(lines.size() - 1) * lh + hd.lineHeight(MESSAGE) + 2 * padY, 0xF0FFFFFF);
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			HdGlyphs.textShadow(hd, MESSAGE, line, cx - HdGlyphs.width(hd, MESSAGE, line) / 2, top + i * lh, color, SHADOW);
		}
		return lines.size() * lh + hd.u(4);
	}

	/**
	 * Messages du bas de l'écran, empilés vers le haut depuis bottom (pixels réels) sans se recouvrir. Renvoie le
	 * nouveau bas de la pile.
	 */
	private static int stackUp(Hd hd, String text, int w, int bottom, int color) {
		List<String> lines = hd.wrap(MESSAGE, text, hd.u(Math.max(80, w - 40)));
		int lh = pitch(hd) + hd.u(1);
		int height = (lines.size() - 1) * lh + hd.lineHeight(MESSAGE);
		int top = bottom - height;
		centered(hd, text, w, top, color, false);
		return top - hd.u(2.5) - hd.u(4);
	}

	/** Voix du joueur, en bas à droite de la barre d'objets : niveau choisi (touche B), et règle du Conseil. */
	private static void drawVoice(Hd hd, Minecraft mc, JsonObject v, int w, int h) {
		int me = ClientState.num(v, "mySeat", -1);
		if (me < 0 || mc.gui.screen() != null || !"DAY".equals(ClientState.phase())) {
			return;
		}
		boolean alive = true;
		boolean floor = false;
		var seats = v.getAsJsonArray("seats");
		if (seats != null) {
			for (var e : seats) {
				JsonObject s = e.getAsJsonObject();
				if (ClientState.num(s, "seat", -2) == me) {
					alive = ClientState.bool(s, "alive");
					floor = ClientState.bool(s, "floor");
				}
			}
		}
		String voice = ClientState.str(v, "voice");
		JsonObject st = ClientState.obj(v, "settings");
		String text;
		int color = Theme.PARCH;
		if (ClientState.bool(v, "inCouncil")) {
			if (!alive && !floor) {
				text = "Mort : tu te tais";
				color = 0xFF9A9090;
			} else if ("chuchoter".equals(voice)) {
				text = "Chuchote aux voisins";
			} else {
				text = "Conseil : à voix haute";
			}
		} else if ("chuchoter".equals(voice)) {
			text = "Chuchoter : " + ClientState.num(st, "whisperRange", 4) + " blocs";
		} else if ("crier".equals(voice)) {
			text = "Crier : " + ClientState.num(st, "shoutRange", 96) + " blocs";
			color = Theme.GOLD;
		} else {
			return;
		}
		// À droite de la barre d'objets : le chat (à gauche) et les consignes (au-dessus) restent libres.
		int tw = hd.width(STRIP, text);
		int x = Math.max(hd.px(w / 2 + 96), hd.px(w) - tw - hd.u(9));
		int top = hd.px(h - 21);
		int bh = hd.u(15);
		hd.sprite(HdSprites.INFOBULLE, x - hd.u(5), top, tw + hd.u(10), bh);
		hd.textMiddle(STRIP, text, x, top, bh, color);
	}

	/** Bandeau de phase, en haut au centre : lune ou soleil, puis « Nuit 2 », « Jour 3 »... */
	private static void drawPhase(Hd hd, String phase, String label, int w) {
		int icon = hd.u(12);
		int gap = hd.u(4);
		int tw = hd.width(STRIP, label);
		int content = icon + gap + tw;
		int bw = content + hd.u(30);
		int bh = hd.u(16);
		int cx = hd.px(w / 2);
		int y = hd.px(4);
		hd.sprite(HdSprites.BANDEAU, cx - bw / 2, y, bw, bh);
		int x = cx - content / 2;
		hd.image(Theme.tex(phase.equals("NIGHT") ? "moon" : "sun"), x, y + (bh - icon) / 2, icon, icon, -1);
		hd.textShadow(STRIP, label, x + icon + gap, y + (bh - hd.lineHeight(STRIP)) / 2, 0xFFF3D98E, 0xC0140A0C);
	}

	/**
	 * Le vote en cours : bandeau (sablier, défense ou compte des voix, jauge du seuil), horloge, et ta main.
	 * Renvoie le bas de ce qui a été dessiné, en pixels réels.
	 */
	private static int drawVote(Hd hd, Minecraft mc, JsonObject vote, int w) {
		boolean defense = "DEFENSE".equals(ClientState.str(vote, "stage"));
		int count = ClientState.num(vote, "count", 0);
		int threshold = Math.max(1, ClientState.num(vote, "threshold", 1));
		String text = defense
			? ClientState.str(vote, "nominee") + " se défend (" + ClientState.num(vote, "defenseLeft", 0) + " s)"
			: "Vote sur " + ClientState.str(vote, "nominee") + " : " + count + " voix, seuil " + threshold;
		int icon = hd.u(12);
		int vw = hd.width(STRIP, text) + hd.u(28);
		int vx = hd.px(w / 2) - vw / 2;
		int vy = hd.px(23);
		int vh = hd.u(16);
		hd.sprite(HdSprites.INFOBULLE, vx, vy, vw, vh);
		hd.fill(vx + hd.u(2.5), vy + hd.u(2.5), vx + vw - hd.u(2.5), vy + vh - hd.u(2.5), 0x705A1E2A);
		hd.image(Theme.tex("hourglass"), vx + hd.u(5), vy + (vh - icon) / 2, icon, icon, -1);
		hd.textMiddle(STRIP, text, vx + hd.u(20), vy, vh, Theme.PARCH);
		if (!defense) {
			// Jauge : la part du seuil déjà atteinte, rouge quand il est atteint.
			float frac = Math.min(1f, count / (float) threshold);
			int gx = vx + hd.u(3);
			int gw = vw - hd.u(6);
			int gy = vy + vh - hd.u(3);
			hd.fill(gx, gy, gx + (int) (gw * frac), gy + Math.max(1, hd.u(1)), count >= threshold ? Theme.EVIL : Theme.GOLD);
		}
		int y = hd.px(44);
		if (!defense) {
			y = drawClock(hd, mc, vote, w, y);
		}
		if (ClientState.bool(vote, "canVote")) {
			boolean up = ClientState.bool(vote, "myHand");
			String hand = up ? "✋ Ta main est levée. H ou plume (clic droit) pour la baisser"
				: "Ta main est baissée. H ou plume (clic droit) pour la lever";
			y += centered(hd, hand, w, y, up ? 0xFF8FD18F : Theme.PARCH, false);
		}
		return y;
	}

	private static void draw(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		String phase = ClientState.phase();
		int w = g.guiWidth();
		int h = g.guiHeight();
		// Un écran de Valcombe ouvert (menu, invite, pupitre) passe devant : l'affichage en jeu n'y glisse pas ses textes,
		// qui se mêleraient au bandeau et au panneau de l'écran sous son voile. Le chat et les écrans du jeu les gardent.
		boolean screenOpen = mc.gui.screen() instanceof fr.valcombe.client.screen.BaseScreen;

		// Voile de nuit qui s'installe doucement (plein écran, sans texte : en unités d'interface)
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
		float wake = since < 2600 ? since / 2600f : -1f;
		if (wake >= 0) {
			int alpha = (int) ((1 - wake) * 220);
			g.fill(0, 0, w, h, (alpha << 24) | 0x05030A);
		}
		if (screenOpen) {
			return;
		}
		try (Hd hd = Hd.begin(g)) {
			drawHudText(hd, mc, phase, w, h);
			if (wake >= 0) {
				int textAlpha = (int) (255 * Math.min(1f, (1 - wake) * 2));
				if (textAlpha > 8) {
					HdStyle big = HdStyle.GRAND_TITRE.size(24);
					hd.textShadow(big, "Tu te réveilles", hd.px(w / 2) - hd.width(big, "Tu te réveilles") / 2,
						hd.px(h / 2) - hd.lineHeight(big) / 2 - hd.u(8), alpha(textAlpha, 0xD9B45A), alpha(textAlpha * 3 / 4, 0x140A0C));
				}
			}
			// La carte de rôle passe par-dessus tout le reste : rien ne s'écrit sur elle.
			drawCard(hd, w, h);
		}
	}

	/** Tout ce qui s'écrit sur l'affichage en jeu, dans un contexte haute résolution déjà ouvert. */
	private static void drawHudText(Hd hd, Minecraft mc, String phase, int w, int h) {
		// Messages du bas, empilés vers le haut au-dessus de la barre d'objets.
		int bottom = hd.px(h - 47);
		// Le serveur n'a pas la même version du mod : des écrans et des commandes ne marcheront pas.
		String other = ClientState.serverVersionMismatch();
		if (phase.equals("LOBBY")) {
			if (other != null) {
				drawVersionWarning(hd, other, w, bottom);
			}
			return;
		}
		JsonObject v = ClientState.view();
		String label = switch (phase) {
			case "NIGHT" -> "Nuit " + ClientState.num(v, "night", 0);
			case "DAY" -> "Jour " + ClientState.num(v, "day", 0);
			case "READY" -> "La partie va commencer";
			default -> "";
		};
		drawPhase(hd, phase, label, w);

		// En haut, sous le bandeau de phase : le vote en cours (ou l'étape de la nuit pour le Conteur), puis les
		// messages pour tous, les uns sous les autres.
		int y = hd.px(24);
		JsonObject vote = ClientState.obj(v, "vote");
		if (vote != null) {
			y = drawVote(hd, mc, vote, w);
		}
		JsonObject ns = ClientState.obj(v, "nightStep");
		if (ns != null) {
			y = Math.max(y, drawNightStep(hd, ns, w) + hd.u(5));
		}
		// Conteur : joueurs déconnectés
		var absents = v.getAsJsonArray("absents");
		if (absents != null && !absents.isEmpty()) {
			StringBuilder names = new StringBuilder();
			absents.forEach(e -> names.append(names.length() == 0 ? "" : ", ").append(e.getAsString()));
			String t = "Absents : " + names + " (fiche du joueur : le faire relayer)";
			y += centered(hd, t, w, y + hd.u(3), 0xFFE05A4A, false) + hd.u(3);
		}
		// Partie en pause : tout le monde le voit
		if (ClientState.bool(v, "paused")) {
			y += centered(hd, "⏸ Partie en pause", w, y + hd.u(3), 0xFFE8C36A, false) + hd.u(3);
		}
		// Le tableau du jour se pose sous tout cela, à droite.
		drawToday(hd, mc, v, Math.max(vote != null ? hd.px(84) : hd.px(30), y + hd.u(8)));
		drawVoice(hd, mc, v, w, h);
		drawStorytellerRoles(hd, mc, v);

		// En bas, du plus bas au plus haut : l'action qui attend (ou le sommeil), l'aparté, la conversation privée, la
		// version du serveur.
		if (ClientState.obj(v, "prompt") != null) {
			bottom = stackUp(hd, "Une action t'attend (touche J)", w, bottom, Theme.GOLD);
		} else if (phase.equals("NIGHT") && !ClientState.bool(v, "isStoryteller") && ClientState.num(v, "mySeat", -1) >= 0) {
			// Nuit d'attente : ceux qui dorment savent quoi faire
			bottom = stackUp(hd, "Tu dors… Le Conteur réveille chacun à son tour. (J : ton carnet)", w, bottom, 0xFFB8A6C8);
		}
		// Aparté avec le Conteur (vocal seul à seul)
		String aside = ClientState.str(v, "aside");
		String me = mc.player == null ? "" : mc.player.getScoreboardName();
		if (aside != null && !aside.isEmpty() && (aside.equals(me) || ClientState.bool(v, "isStoryteller"))) {
			String t = aside.equals(me) ? "En aparté avec le Conteur : vous seuls vous entendez"
				: "En aparté avec " + aside + " (accroupi + clic droit au bâton pour finir)";
			bottom = stackUp(hd, t, w, bottom, 0xFFC8A8F0);
		}
		// Conversation privée entre joueurs, et invitation en attente
		String talk = ClientState.str(v, "talkingWith");
		String invite = ClientState.str(v, "talkInvite");
		if (talk != null && !talk.isEmpty()) {
			bottom = stackUp(hd, "En privé avec " + talk + " : vous seuls vous entendez (menu J pour revenir)", w, bottom, 0xFFC8A8F0);
		} else if (invite != null && !invite.isEmpty()) {
			bottom = stackUp(hd, invite + " veut te parler en privé (touche J pour répondre)", w, bottom, Theme.GOLD);
		}
		if (other != null) {
			drawVersionWarning(hd, other, w, bottom);
		}
	}

	/** Conteur, la nuit : qui est réveillé et ce qu'on attend, sous le bandeau de phase. Renvoie le bas du bandeau. */
	private static int drawNightStep(Hd hd, JsonObject ns, int w) {
		String who = ClientState.str(ns, "player");
		String wait = ClientState.str(ns, "waiting");
		String t = "Réveil : " + ClientState.str(ns, "title") + (who == null || who.isEmpty() ? "" : " (" + who + ")")
			+ (wait == null || wait.isEmpty() ? "" : " : " + wait);
		t = hd.ellipsize(STRIP, t, hd.u(w - 20));
		int nw = hd.width(STRIP, t) + hd.u(14);
		int ny = hd.px(23);
		int nh = hd.u(16);
		int nx = hd.px(w / 2) - nw / 2;
		hd.sprite(HdSprites.INFOBULLE, nx, ny, nw, nh, 0xFFC8B0F0);
		hd.fill(nx + hd.u(2.5), ny + hd.u(2.5), nx + nw - hd.u(2.5), ny + nh - hd.u(2.5), 0x702A1840);
		hd.textMiddle(STRIP, t, nx + hd.u(7), ny, nh, 0xFFE8DCF8);
		return ny + nh;
	}

	/** Bandeau rouge : le serveur a une autre version du mod. Posé au-dessus de bottom (pixels réels). */
	private static void drawVersionWarning(Hd hd, String other, int w, int bottom) {
		String t = "Le serveur a une autre version du mod Valcombe (serveur : " + other + ", vous : "
			+ ClientState.modVersion() + "). L'hôte doit le mettre à jour.";
		List<String> lines = hd.wrap(MESSAGE, t, hd.u(Math.max(80, w - 40)));
		int lw = 0;
		for (String l : lines) {
			lw = Math.max(lw, hd.width(MESSAGE, l));
		}
		int lh = pitch(hd) + hd.u(1);
		int top = bottom - hd.u(4) - lines.size() * lh;
		int cx = hd.px(w / 2);
		hd.fill(cx - lw / 2 - hd.u(7), top - hd.u(3), cx + lw / 2 + hd.u(7), bottom, 0xE0801818);
		for (int i = 0; i < lines.size(); i++) {
			hd.textCentered(MESSAGE, lines.get(i), cx, top + i * lh, 0xFFFFFFFF);
		}
	}
}
