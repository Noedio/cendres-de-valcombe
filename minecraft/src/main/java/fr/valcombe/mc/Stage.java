package fr.valcombe.mc;

import fr.valcombe.session.VoteScene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * La scène 3D de la place : trônes, jetons en relief, étiquettes, horloge de vote géante,
 * mains levées, gibet et lutrin du Conteur. Tout est fait d'entités d'affichage marquées
 * « vc_scene » : le serveur les déplace, le client les anime (interpolation).
 *
 * La scène vit dans le monde de la partie qui la monte, et seulement là : ses commandes partent de ce monde et ses
 * sélecteurs s'y tiennent (distance=0..), pour qu'une autre partie, ailleurs, garde la sienne.
 *
 * Optimisation : chaque élément n'est mis à jour que s'il a changé (aucune entité n'est
 * recréée à chaque événement de partie).
 */
final class Stage {
	/** Un siège : position des pieds du joueur et lacet (regard vers le centre). */
	record Spot(Vec3 pos, float yaw) {
	}

	private static final String SCENE = "vc_scene";
	/**
	 * Génération de la scène, propre à ce lancement du serveur : une scène d'avant le redémarrage, restée
	 * dans des chunks non chargés au moment de la retirer, se reconnaît à sa marque et est purgée (voir McHost).
	 */
	static final String GEN_TAG = "vc_sc_" + (System.currentTimeMillis() / 1000 & 0x3FFFFFFF);
	/** Les deux marques de toute entité de la scène, telles qu'elles s'écrivent dans une commande. */
	private static final String TAGS = SCENE + "\",\"" + GEN_TAG;
	private static final double TOKEN_Y = 2.9;
	private static final double LABEL_Y = 3.7;
	private static final double FLAME_Y = 2.05;

	private final BiConsumer<Integer, Runnable> scheduler;
	/** Le monde où la scène est montée, ou null. */
	private ServerLevel level;
	private final List<String> order = new ArrayList<>();
	private final Map<String, Spot> spots = new HashMap<>();
	private final Map<String, String> labels = new HashMap<>();
	private final Map<String, String> models = new HashMap<>();
	private final Map<String, String> flames = new HashMap<>();
	private Vec3 center;
	private double handY;
	private double handLength;
	private double handAngle;
	private double gibetDistance = 5;
	private String countText = "";
	private String gibetFor;
	private int gibetToken;
	private boolean full = true;

	Stage(BiConsumer<Integer, Runnable> scheduler) {
		this.scheduler = scheduler;
	}

	void setFullEffects(boolean full) {
		this.full = full;
	}

	boolean active() {
		return level != null && center != null;
	}

	/** Le monde où la scène est montée, ou null. */
	ServerLevel level() {
		return level;
	}

	int index(String name) {
		return order.indexOf(name);
	}

	/** Monte la scène dans ce monde pour ces sièges (dans l'ordre du cercle). */
	void setup(ServerLevel level, Vec3 center, double handY, Map<String, Spot> seats, boolean lectern, boolean thrones) {
		clear(level);
		this.level = level;
		this.center = center;
		this.handY = handY;
		double radius = 0;
		for (var e : seats.entrySet()) {
			order.add(e.getKey());
			spots.put(e.getKey(), e.getValue());
			radius += Math.hypot(e.getValue().pos().x - center.x, e.getValue().pos().z - center.z);
		}
		radius = seats.isEmpty() ? 6 : radius / seats.size();
		this.handLength = Math.max(2, radius - 1.6);
		this.gibetDistance = Math.max(4, radius - 5);
		this.thrones = thrones;
		for (int i = 0; i < order.size(); i++) {
			summonSeat(i, order.get(i), spots.get(order.get(i)));
		}
		// Aiguille de l'horloge, au repos vers le nord
		handAngle = Math.PI;
		run("summon minecraft:block_display " + xyz(center.x, handY, center.z) + " {Tags:[\"" + TAGS
			+ "\",\"vc_hand\"],block_state:\"minecraft:gold_block\",brightness:{sky:15,block:15},view_range:3f,"
			+ "transformation:" + handTransform(handAngle) + "}");
		run("summon minecraft:block_display " + xyz(center.x, handY - 0.12, center.z) + " {Tags:[\"" + TAGS
			+ "\"],block_state:\"minecraft:polished_blackstone\",brightness:{sky:15,block:15},"
			+ "transformation:" + transform("[-0.35f,0f,-0.35f]", quatY(0), "[0.7f,0.12f,0.7f]") + "}");
		if (lectern) {
			Vec3 l = new Vec3(center.x, center.y, center.z + 3.6);
			run("summon minecraft:item_display " + xyz(l.x, l.y + 0.5, l.z) + " {Tags:[\"" + TAGS + "\"],Rotation:[0f,0f],"
				+ item("lutrin") + "}");
			run("summon minecraft:interaction " + xyz(l.x, l.y, l.z) + " {Tags:[\"" + TAGS
				+ "\",\"vc_lutrin\"],width:1.2f,height:1.6f,response:1b}");
		}
	}

	private boolean thrones;

	/** Un siège de la scène : trône (s'il le faut), point d'assise, jeton et étiquette. */
	private void summonSeat(int i, String name, Spot s) {
		Vec3 p = s.pos();
		// Trône (dossier du côté opposé au centre) et point d'assise invisible
		if (thrones) {
			run("summon minecraft:item_display " + xyz(p.x, p.y + 0.5, p.z) + " {Tags:[\"" + TAGS + "\"],Rotation:["
				+ f(s.yaw()) + "f,0f]," + item("trone") + ",view_range:1.5f}");
		}
		run("summon minecraft:item_display " + xyz(p.x, p.y + 0.45, p.z) + " {Tags:[\"" + TAGS + "\",\"vc_seat_" + i
			+ "\"],Rotation:[" + f(s.yaw()) + "f,0f]}");
		// Jeton et étiquette
		run("summon minecraft:item_display " + xyz(p.x, p.y + TOKEN_Y, p.z) + " {Tags:[\"" + TAGS + "\",\"vc_tok_" + i
			+ "\"],billboard:\"vertical\"," + item("jeton_vivant") + ",brightness:{sky:15,block:15},"
			+ "transformation:" + transform("[0f,0f,0f]", quatY(0), "[1.1f,1.1f,1.1f]") + "}");
		models.put(name, "jeton_vivant");
		run("summon minecraft:text_display " + xyz(p.x, p.y + LABEL_Y, p.z) + " {Tags:[\"" + TAGS + "\",\"vc_lab_" + i
			+ "\"],billboard:\"center\",background:1073741824,brightness:{sky:15,block:15},"
			+ "transformation:" + transform("[0f,0f,0f]", quatY(0), "[1.8f,1.8f,1.8f]") + ",text:{text:\""
			+ esc(name) + "\",color:\"white\"}}");
		labels.put(name, "white|" + name);
	}

	/** Un voyageur arrive : son siège rejoint la scène, au bout du cercle. */
	void addSeat(String name, Spot s) {
		if (!active() || order.contains(name)) {
			return;
		}
		order.add(name);
		spots.put(name, s);
		summonSeat(order.size() - 1, name, s);
	}

	/**
	 * Retire toute la scène de ce monde (le monde de la partie ; null : seulement l'état en mémoire, le monde est
	 * fermé ou remis à neuf et la scène est partie avec lui). Une scène montée dans un autre monde y est retirée aussi.
	 */
	void clear(ServerLevel in) {
		if (in != null) {
			WorldSetup.kill(in, "tag=" + SCENE);
		}
		if (level != null && level != in && level.getServer().getLevel(level.dimension()) == level) {
			WorldSetup.kill(level, "tag=" + SCENE);
		}
		level = null;
		order.clear();
		spots.clear();
		labels.clear();
		models.clear();
		flames.clear();
		center = null;
		countText = "";
		gibetFor = null;
		gibetToken++;
	}

	// ------------------------------------------------------------------
	// Jetons et étiquettes
	// ------------------------------------------------------------------
	void labels(Map<String, String> wanted) {
		if (!active()) {
			return;
		}
		for (var e : wanted.entrySet()) {
			int i = order.indexOf(e.getKey());
			if (i < 0 || e.getValue().equals(labels.get(e.getKey()))) {
				continue;
			}
			labels.put(e.getKey(), e.getValue());
			String[] parts = e.getValue().split("\\|", 2);
			run("data merge entity @e[distance=0..,type=minecraft:text_display,tag=vc_lab_" + i + ",limit=1] {text:{text:\""
				+ esc(parts[1]) + "\",color:\"" + parts[0] + "\"}}");
		}
	}

	void tokens(Map<String, String> wanted, boolean flip) {
		if (!active()) {
			return;
		}
		for (var e : wanted.entrySet()) {
			int i = order.indexOf(e.getKey());
			if (i < 0 || e.getValue().equals(models.get(e.getKey()))) {
				continue;
			}
			models.put(e.getKey(), e.getValue());
			String target = "@e[distance=0..,type=minecraft:item_display,tag=vc_tok_" + i + ",limit=1]";
			if (!flip) {
				run("data merge entity " + target + " {" + item(e.getValue()) + "}");
				continue;
			}
			// Révélation : le jeton montre son dos, puis se retourne lentement.
			run("data merge entity " + target + " {" + item(e.getValue()) + ",interpolation_duration:0,transformation:"
				+ transform("[0f,0f,0f]", quatY(Math.PI), "[1.1f,1.1f,1.1f]") + "}");
			int delay = 6 + 4 * i;
			scheduler.accept(delay, () -> run("data merge entity " + target
				+ " {start_interpolation:0,interpolation_duration:24,transformation:"
				+ transform("[0f,0f,0f]", quatY(0), "[1.3f,1.3f,1.3f]") + "}"));
		}
	}

	// ------------------------------------------------------------------
	// Vote : horloge, mains levées, gibet
	// ------------------------------------------------------------------
	void vote(VoteScene v) {
		if (!active()) {
			return;
		}
		if (v == null) {
			pointHand(Math.PI, 30);
			setCount("");
			setFlames(Map.of());
			lowerGibet();
			return;
		}
		String target = v.pointing() != null ? v.pointing() : v.nominee();
		Spot s = spots.get(target);
		if (s != null) {
			pointHand(Math.atan2(s.pos().x - center.x, s.pos().z - center.z), Math.max(1, v.moveTicks()));
			if (!v.defense() && v.moveTicks() > 0) {
				run("playsound valcombe:tic master @a[distance=0..] " +xyz(center.x, handY, center.z) + " 2 1");
			}
		}
		setCount(v.defense()
			? "⚖ " + v.nominee() + "\nse défend"
			: "⚖ " + v.nominee() + "\n" + v.locked().size() + " / " + v.threshold());
		Map<String, String> wanted = new LinkedHashMap<>();
		if (full) {
			for (String n : v.raised()) {
				wanted.put(n, "minecraft:soul_fire");
			}
		}
		for (String n : v.locked()) {
			wanted.put(n, "minecraft:fire");
		}
		setFlames(wanted);
		if (full) {
			raiseGibet(v.nominee());
		}
	}

	/** Une exécution : le gibet se dresse devant le condamné, puis redescend. */
	void execution(String name) {
		if (!active() || !full) {
			return;
		}
		raiseGibet(name);
		int token = gibetToken;
		scheduler.accept(120, () -> {
			if (token == gibetToken) {
				lowerGibet();
			}
		});
	}

	private void pointHand(double angle, int ticks) {
		// Toujours dans le sens des aiguilles d'une montre, par le plus court chemin sinon.
		double delta = Math.atan2(Math.sin(angle - handAngle), Math.cos(angle - handAngle));
		handAngle += delta;
		run("data merge entity @e[distance=0..,type=minecraft:block_display,tag=vc_hand,limit=1] {start_interpolation:0,"
			+ "interpolation_duration:" + ticks + ",transformation:" + handTransform(handAngle) + "}");
	}

	private String handTransform(double phi) {
		double w = 0.16;
		// La barre part du centre : on recentre sa largeur en tenant compte de la rotation.
		String t = vec(-w / 2 * Math.cos(phi), 0, w / 2 * Math.sin(phi));
		return transform(t, quatY(phi), vec(w, 0.1, handLength));
	}

	private void setCount(String text) {
		if (text.equals(countText)) {
			return;
		}
		boolean had = !countText.isEmpty();
		countText = text;
		if (text.isEmpty()) {
			run("kill @e[distance=0..,type=minecraft:text_display,tag=vc_count]");
			return;
		}
		if (!had) {
			run("summon minecraft:text_display " + xyz(center.x, handY + 1.4, center.z) + " {Tags:[\"" + TAGS
				+ "\",\"vc_count\"],billboard:\"center\",background:1610612736,brightness:{sky:15,block:15},"
				+ "transformation:" + transform("[0f,0f,0f]", quatY(0), "[2.6f,2.6f,2.6f]") + ",text:{text:\""
				+ esc(text) + "\",color:\"gold\"}}");
		} else {
			run("data merge entity @e[distance=0..,type=minecraft:text_display,tag=vc_count,limit=1] {text:{text:\"" + esc(text)
				+ "\",color:\"gold\"}}");
		}
	}

	private void setFlames(Map<String, String> wanted) {
		for (String n : new ArrayList<>(flames.keySet())) {
			if (!wanted.containsKey(n)) {
				flames.remove(n);
				run("kill @e[distance=0..,type=minecraft:block_display,tag=vc_flame_" + order.indexOf(n) + "]");
			}
		}
		for (var e : wanted.entrySet()) {
			int i = order.indexOf(e.getKey());
			String before = flames.get(e.getKey());
			if (i < 0 || e.getValue().equals(before)) {
				continue;
			}
			flames.put(e.getKey(), e.getValue());
			if (before != null) {
				run("data merge entity @e[distance=0..,type=minecraft:block_display,tag=vc_flame_" + i + ",limit=1] {block_state:\""
					+ e.getValue() + "\"}");
				continue;
			}
			Vec3 p = spots.get(e.getKey()).pos();
			run("summon minecraft:block_display " + xyz(p.x, p.y + FLAME_Y, p.z) + " {Tags:[\"" + TAGS + "\",\"vc_flame_" + i
				+ "\"],block_state:\"" + e.getValue() + "\",brightness:{sky:15,block:15},"
				+ "transformation:" + transform("[-0.225f,0f,-0.225f]", quatY(0), "[0.45f,0.45f,0.45f]") + "}");
		}
	}

	/** Pièces du gibet, dans son repère : X le long de la poutre, Y vers le haut. */
	private static final String[][] GIBET = {
		{"minecraft:dark_oak_log", "[-1.4f,0f,-0.18f]", "[0.36f,3.6f,0.36f]"},
		{"minecraft:dark_oak_log", "[1.04f,0f,-0.18f]", "[0.36f,3.6f,0.36f]"},
		{"minecraft:stripped_dark_oak_log", "[-1.6f,3.6f,-0.2f]", "[3.2f,0.4f,0.4f]"},
		{"minecraft:iron_chain", "[-0.15f,2.35f,-0.15f]", "[0.3f,1.25f,0.3f]"},
		{"minecraft:dark_oak_slab", "[-1.7f,0f,-0.9f]", "[3.4f,0.5f,1.8f]"},
	};

	private void raiseGibet(String name) {
		if (name.equals(gibetFor)) {
			return;
		}
		// Un autre gibet est dressé ou en train de descendre : on le retire tout de suite.
		run("kill @e[distance=0..,type=minecraft:block_display,tag=vc_gibet]");
		Spot s = spots.get(name);
		if (s == null) {
			return;
		}
		gibetFor = name;
		int token = ++gibetToken;
		run("playsound valcombe:craquement master @a[distance=0..] " +xyz(center.x, center.y + 1, center.z) + " 2 0.9");
		double dx = s.pos().x - center.x;
		double dz = s.pos().z - center.z;
		double d = Math.max(0.1, Math.hypot(dx, dz));
		double gx = center.x + dx / d * gibetDistance;
		double gz = center.z + dz / d * gibetDistance;
		// Lacet : l'axe Z local suit le rayon, la poutre (axe X) lui est perpendiculaire.
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		for (int k = 0; k < GIBET.length; k++) {
			String[] part = GIBET[k];
			run("summon minecraft:block_display " + xyz(gx, center.y, gz) + " {Tags:[\"" + TAGS + "\",\"vc_gibet\",\"vc_gibet_"
				+ k + "\"],Rotation:[" + f(yaw) + "f,0f],block_state:\"" + part[0] + "\",transformation:"
				+ transform(lowered(part[1]), quatY(0), part[2]) + "}");
		}
		scheduler.accept(3, () -> {
			if (token != gibetToken) {
				return;
			}
			for (int k = 0; k < GIBET.length; k++) {
				String[] part = GIBET[k];
				run("data merge entity @e[distance=0..,type=minecraft:block_display,tag=vc_gibet_" + k
					+ ",limit=1] {start_interpolation:0,interpolation_duration:30,transformation:"
					+ transform(part[1], quatY(0), part[2]) + "}");
			}
		});
	}

	private void lowerGibet() {
		if (gibetFor == null) {
			return;
		}
		gibetFor = null;
		int token = ++gibetToken;
		for (int k = 0; k < GIBET.length; k++) {
			String[] part = GIBET[k];
			run("data merge entity @e[distance=0..,type=minecraft:block_display,tag=vc_gibet_" + k
				+ ",limit=1] {start_interpolation:0,interpolation_duration:25,transformation:"
				+ transform(lowered(part[1]), quatY(0), part[2]) + "}");
		}
		scheduler.accept(28, () -> {
			if (token == gibetToken) {
				run("kill @e[distance=0..,type=minecraft:block_display,tag=vc_gibet]");
			}
		});
	}

	/** La même pièce, enfoncée sous le pavé. */
	private static String lowered(String translation) {
		String[] v = translation.substring(1, translation.length() - 1).split(",");
		double y = Double.parseDouble(v[1].replace("f", "")) - 4.6;
		return "[" + v[0] + "," + f(y) + "f," + v[2] + "]";
	}

	// ------------------------------------------------------------------
	// Outils
	// ------------------------------------------------------------------
	private void run(String cmd) {
		if (level != null) {
			WorldSetup.run(level, cmd);
		}
	}

	private static String item(String model) {
		return "item:{id:\"minecraft:paper\",count:1,components:{\"minecraft:item_model\":\"valcombe:" + model + "\"}}";
	}

	private static String f(double d) {
		return String.format(Locale.ROOT, "%.3f", d);
	}

	private static String xyz(double x, double y, double z) {
		return f(x) + " " + f(y) + " " + f(z);
	}

	private static String vec(double x, double y, double z) {
		return "[" + f(x) + "f," + f(y) + "f," + f(z) + "f]";
	}

	private static String quatY(double phi) {
		return "[0f," + f(Math.sin(phi / 2)) + "f,0f," + f(Math.cos(phi / 2)) + "f]";
	}

	private static String transform(String translation, String rotation, String scale) {
		return "{translation:" + translation + ",left_rotation:" + rotation + ",scale:" + scale
			+ ",right_rotation:[0f,0f,0f,1f]}";
	}

	private static String esc(String s) {
		return s.replace("\\", "").replace("\"", "'").replace("\n", "\\n");
	}
}
