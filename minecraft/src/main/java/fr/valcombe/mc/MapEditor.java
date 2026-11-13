package fr.valcombe.mc;

import fr.valcombe.rules.GameException;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * L'éditeur de carte du mode construction : la pose des structures du catalogue avec aperçu (fantôme), leur reprise
 * avec la main (déplacer, tourner, retirer), et un historique pour annuler et rétablir tout cela.
 * Tout est réservé aux opérateurs en mode construction, hors partie ; les blocs sont posés par
 * petits lots (BuildQueue), et chaque opération garde en mémoire la zone telle qu'elle était.
 * <p>
 * Sélectionner, remplir, vider, remplacer, murer, copier, couper, coller, tourner, déplacer, empiler : c'est WorldEdit,
 * dans les ateliers (voir {@link WorldEditGate}). L'« annuler » commun ({@link SharedUndo}, {@code /vc annuler}) défait
 * dans l'ordre les gestes de WorldEdit et ceux de cet éditeur.
 */
public final class MapEditor {
	/** Nombre de blocs qu'une opération peut toucher (et donc garder en mémoire pour l'annuler). */
	public static final int MAX_VOLUME = 400_000;
	/** Niveaux d'annulation, et nombre total de blocs gardés pour eux. */
	static final int HISTORY_DEPTH = 40;
	static final long HISTORY_CELLS = 4_000_000;
	/** Plus grand côté d'une zone : au-delà, la lire ferait charger des centaines de chunks d'un coup. */
	static final int MAX_SPAN = 512;
	private static final DustParticleOptions PREVIEW = new DustParticleOptions(0x55FFFF, 1.2f);
	private static final DustParticleOptions ANCHOR = new DustParticleOptions(0xFF5555, 1.6f);

	/** Un pavé de blocs, coins compris. */
	record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
		static Box of(BlockPos a, BlockPos b) {
			return new Box(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
				Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		}

		static Box at(BlockPos min, int width, int height, int length) {
			return new Box(min.getX(), min.getY(), min.getZ(), min.getX() + width - 1, min.getY() + height - 1, min.getZ() + length - 1);
		}

		int width() {
			return x1 - x0 + 1;
		}

		int height() {
			return y1 - y0 + 1;
		}

		int length() {
			return z1 - z0 + 1;
		}

		long volume() {
			return (long) width() * height() * length();
		}

		BlockPos min() {
			return new BlockPos(x0, y0, z0);
		}

		Box union(Box o) {
			return new Box(Math.min(x0, o.x0), Math.min(y0, o.y0), Math.min(z0, o.z0), Math.max(x1, o.x1), Math.max(y1, o.y1),
				Math.max(z1, o.z1));
		}

		boolean contains(BlockPos p) {
			return p.getX() >= x0 && p.getX() <= x1 && p.getY() >= y0 && p.getY() <= y1 && p.getZ() >= z0 && p.getZ() <= z1;
		}
	}

	/** Ce qu'un constructeur a en cours : la structure qu'il pose. */
	private static final class Desk {
		String structure;
		Turn turn = Turn.NONE;
		long lastFlip;
		/** Le fantôme de la structure en main est affiché chez ce joueur (pour l'effacer quand il change d'outil). */
		boolean ghost;
	}

	/**
	 * Une structure posée depuis le catalogue, que l'on peut reprendre : où elle est (coin, zone, pieds de l'ancre),
	 * dans quelle orientation, et le terrain d'avant sa pose (pour la retirer sans laisser de trace).
	 */
	record Placed(String id, BlockPos feet, Turn turn, BlockPos corner, Box zone, Clip before, Marker marker) {
	}

	/** Les structures posées depuis l'ouverture de la carte, la plus récente en dernier. */
	private final java.util.List<Placed> placed = new java.util.ArrayList<>();

	/** Repère posé avec une structure : il part et revient avec elle quand on annule ou rétablit. */
	private record Marker(Village.Mark type, BlockPos pos, BlockPos previous, boolean present) {
	}

	/** De quoi défaire (ou refaire) une opération : la zone telle qu'elle était, et le repère concerné. */
	private record Change(String label, BlockPos corner, Clip before, Marker marker) {
	}

	private final McHost host;
	private final StructureStore store;
	private MinecraftServer server;
	private final Map<String, Desk> desks = new HashMap<>();
	private final History<Change> history = new History<>(HISTORY_DEPTH, HISTORY_CELLS);
	private long ticks;

	public MapEditor(McHost host, StructureStore store) {
		this.host = host;
		this.store = store;
	}

	public void setServer(MinecraftServer server) {
		this.server = server;
		desks.clear();
		history.clear();
		work = null;
	}

	/**
	 * Le monde où travaille l'éditeur : celui de l'opérateur qui s'en est servi en dernier (voir require). Son
	 * historique ne vaut que pour ce monde : il repart de zéro quand l'éditeur change de monde.
	 */
	private ServerLevel work;

	/**
	 * L'éditeur travaille-t-il dans ce monde ? Son historique (les poses de structures) ne vaut que là : l'« annuler »
	 * commun (SharedUndo) ne le défait que depuis ce monde.
	 */
	boolean worksIn(net.minecraft.world.level.Level level) {
		return work != null && work == level;
	}

	private ServerLevel level() {
		if (work == null || server == null || server.getLevel(work.dimension()) != work) {
			throw new GameException("L'éditeur n'a pas de monde où travailler : servez-vous d'abord d'un outil, là où vous êtes.");
		}
		return work;
	}

	/**
	 * La carte en service si l'éditeur travaille dans le monde de la partie, sinon null : les repères (maison, salle)
	 * ne se posent qu'avec la carte de ce monde-là.
	 */
	private Village village() {
		return work != null && host.isGameLevel(work) ? host.village() : null;
	}

	// ------------------------------------------------------------------
	// Droits et limites
	// ------------------------------------------------------------------
	/**
	 * L'éditeur ne sert qu'à un opérateur en mode construction ; il travaille dans le monde où se tient cet opérateur
	 * (jamais dans la partie en cours, si c'est son monde).
	 */
	private Desk require(ServerPlayer p) {
		if (server == null || !Atelier.isOperator(p)) {
			throw new GameException("L'éditeur de carte est réservé aux opérateurs.");
		}
		if (ValcombeMod.session().game() != null && host.inGameWorld(p)) {
			throw new GameException("On ne touche pas à la carte pendant une partie.");
		}
		if (!host.isBuilder(p.getScoreboardName())) {
			throw new GameException("L'éditeur s'utilise en mode construction : /vc carte construction.");
		}
		if (work != p.level()) {
			// Un autre monde : ce qu'on pouvait annuler l'a été ailleurs.
			forgetHistory();
			work = p.level();
		}
		return desks.computeIfAbsent(p.getScoreboardName(), k -> new Desk());
	}

	static void checkSpan(int width, int length) {
		if (width > MAX_SPAN || length > MAX_SPAN) {
			throw new GameException("Zone trop longue (" + Math.max(width, length) + " blocs) : " + MAX_SPAN + " au plus de côté. "
				+ "Procédez en plusieurs fois.");
		}
	}

	static void checkVolume(long volume) {
		if (volume > MAX_VOLUME) {
			throw new GameException("Zone trop grande (" + volume + " blocs) : " + MAX_VOLUME + " au plus par opération. "
				+ "Procédez en plusieurs fois.");
		}
	}

	/** La zone telle qu'elle est, pour pouvoir y revenir ; refusée si elle est trop grande ou sort du monde. */
	private Clip snap(Box zone) {
		checkVolume(zone.volume());
		checkSpan(zone.width(), zone.length());
		if (zone.y0() < level().getMinY() || zone.y1() > level().getMaxY()) {
			throw new GameException("Cette zone sort du monde (trop haut ou trop bas).");
		}
		return Clip.capture(level(), zone.min(), zone.width(), zone.height(), zone.length());
	}

	/** Lance une opération : elle entre dans l'historique, puis ses blocs sont posés par petits lots. */
	private void commit(String label, Box zone, Clip before, BuildQueue.Plan plan, Marker marker) {
		history.record(new Change(label, zone.min(), before, marker), before.volume());
		if (village() != null) {
			// Dans le monde de la partie : sa carte est retouchée, elle sera sauvegardée avant d'être remplacée.
			host.markEdited();
		}
		host.startEdit(label, level(), plan, null);
	}

	/** « Pose de « Maison gothique » » → « pose de « Maison gothique » », pour la glisser dans une phrase. */
	private static String lower(String label) {
		return label.isEmpty() ? label : label.substring(0, 1).toLowerCase(Locale.ROOT) + label.substring(1);
	}

	private static final String UNDO = " Revenir en arrière : /vc annuler.";

	// ------------------------------------------------------------------
	// Annuler, rétablir
	// ------------------------------------------------------------------
	/** Combien d'opérations de l'éditeur on peut annuler (pour l'« annuler » commun, voir SharedUndo). */
	int undoable() {
		return history.undoable();
	}

	public String undo(ServerPlayer p, int times) {
		return swap(p, true, times);
	}

	public String redo(ServerPlayer p, int times) {
		return swap(p, false, times);
	}

	private String swap(ServerPlayer p, boolean undo, int times) {
		require(p);
		host.requireFree();
		int n = Math.min(Math.max(1, times), undo ? history.undoable() : history.redoable());
		if (n == 0) {
			throw new GameException(undo ? "Rien à annuler." : "Rien à rétablir.");
		}
		String label = step(undo, n);
		return n == 1 ? (undo ? "Annulé : " : "Rétabli : ") + lower(label) + "."
			: n + " opérations " + (undo ? "annulées." : "rétablies.");
	}

	/** Défait (ou refait) une opération ; les suivantes s'enchaînent une fois ses blocs posés. */
	private String step(boolean undo, int left) {
		Change c = undo ? history.takeUndo() : history.takeRedo();
		if (c == null) {
			return "";
		}
		Box touched = Box.at(c.corner(), c.before().width, c.before().height, c.before().length);
		placed.removeIf(pl -> overlaps(pl.zone(), touched));
		// L'état actuel de la zone devient ce qu'il faudra remettre pour revenir sur ce pas.
		Clip now = Clip.capture(level(), c.corner(), c.before().width, c.before().height, c.before().length);
		Change inverse = new Change(c.label(), c.corner(), now, c.marker() == null ? null : toggle(c.marker()));
		if (undo) {
			history.putRedo(inverse, now.volume());
		} else {
			history.putUndo(inverse, now.volume());
		}
		if (village() != null) {
			host.markEdited();
		}
		host.startEdit((undo ? "Annulation : " : "Rétablissement : ") + lower(c.label()), level(),
			c.before().plan(level(), c.corner(), false, false), left > 1 ? () -> step(undo, left - 1) : null);
		return c.label();
	}

	/** Retire le repère posé avec une structure, ou le remet ; renvoie de quoi faire l'inverse. */
	private Marker toggle(Marker m) {
		Village v = village();
		if (v != null) {
			if (m.type() == Village.Mark.HOUSE) {
				Village.Hit h = v.markerAt(m.pos(), Village.Mark.HOUSE, 0);
				if (m.present() && h != null && v.markerPos(h).equals(m.pos())) {
					v.removeMarker(h);
				} else if (!m.present() && h == null && v.houseCount() < McHost.MAX_MARKERS) {
					v.addHouse(m.pos());
				}
			} else if (m.present() && m.pos().equals(v.convocation())) {
				v.setConvocation(m.previous());
			} else if (!m.present()) {
				BlockPos previous = v.convocation();
				v.setConvocation(m.pos());
				host.editorMarkersChanged();
				return new Marker(m.type(), m.pos(), previous, true);
			}
			host.editorMarkersChanged();
		}
		return new Marker(m.type(), m.pos(), m.previous(), !m.present());
	}

	/** La carte a changé (autre carte, partie lancée) : ce qu'on pouvait annuler n'a plus de sens. */
	void forgetHistory() {
		history.clear();
		placed.clear();
	}

	private static boolean overlaps(Box a, Box b) {
		return a.x0() <= b.x1() && b.x0() <= a.x1() && a.y0() <= b.y1() && b.y0() <= a.y1() && a.z0() <= b.z1() && b.z0() <= a.z1();
	}

	/** Numéro de la structure posée qui contient ce bloc (la plus récente d'abord), ou -1. */
	public int placedAt(BlockPos p) {
		for (int i = placed.size() - 1; i >= 0; i--) {
			if (placed.get(i).zone().contains(p)) {
				return i;
			}
		}
		return -1;
	}

	/** Le nom d'une structure posée, pour le menu de l'outil « Carte : main ». */
	public String placedName(int index) {
		Placed pl = placedEntry(index);
		try {
			return store.def(pl.id()).nom;
		} catch (GameException gone) {
			return pl.id();
		}
	}

	private Placed placedEntry(int index) {
		if (index < 0 || index >= placed.size()) {
			throw new GameException("Cette structure n'est plus là (la carte ou l'historique a changé).");
		}
		return placed.get(index);
	}

	/**
	 * Agit sur une structure posée : « retirer » remet le terrain d'avant, « tourner » et « retourner » la reposent
	 * autrement au même endroit, « deplacer » la retire et la remet en main pour la poser ailleurs.
	 */
	public String actOnPlaced(ServerPlayer p, int index, String action) {
		Desk d = require(p);
		host.requireFree();
		Placed pl = placedEntry(index);
		String nom = placedName(index);
		switch (action) {
			case "retirer", "deplacer" -> {
				Clip now = snap(pl.zone());
				if (pl.marker() != null) {
					toggleOff(pl.marker());
				}
				commit("Retrait de « " + nom + " »", pl.zone(), now, pl.before().plan(level(), pl.corner(), false, false), null);
				placed.remove(index);
				if (action.equals("retirer")) {
					return "« " + nom + " » retirée : le terrain d'avant est revenu." + UNDO;
				}
				d.structure = pl.id();
				d.turn = pl.turn();
				ValcombeUi.holdMapTool(p, ValcombeUi.MapTool.STRUCTURE);
				sendGhost(p, d);
				return "« " + nom + " » reprise en main : visez où la reposer, puis clic droit.";
			}
			case "tourner", "retourner" -> {
				StructureStore.Def def = store.def(pl.id());
				Turn turn = action.equals("tourner") ? pl.turn().rotated() : pl.turn().mirrored();
				Clip clip = store.clip(def.id).turned(turn);
				BlockPos corner = corner(def, turn, pl.feet());
				Box zone = Box.at(corner, clip.width, clip.height, clip.length);
				Box all = zone.union(pl.zone());
				Clip before = snap(all);
				// Le terrain qu'aura la nouvelle zone une fois l'ancienne pose effacée.
				Clip terrain = snap(zone).withPatch(corner, pl.before(), pl.corner());
				BuildQueue.Plan plan = pl.before().plan(level(), pl.corner(), false, false)
					.then(clip.plan(level(), corner, false, def.fournie));
				commit((action.equals("tourner") ? "Rotation de « " : "Retournement de « ") + nom + " »", all, before, plan, null);
				placed.set(index, new Placed(pl.id(), pl.feet(), turn, corner, zone, terrain, pl.marker()));
				return "« " + nom + " » " + (turn.label().isEmpty() ? "dans son orientation d'origine" : turn.label()) + "." + UNDO;
			}
			default -> throw new GameException("Action inconnue : " + action + " (retirer, deplacer, tourner, retourner).");
		}
	}

	/** Le repère posé avec une structure part avec elle. */
	private void toggleOff(Marker m) {
		if (m.present()) {
			toggle(m);
		}
	}

	/** Un constructeur quitte le mode construction : la structure qu'il avait en main est rendue. */
	void forget(String name) {
		desks.remove(name);
	}

	// ------------------------------------------------------------------
	// Structures du catalogue : choisir, orienter, poser, enregistrer
	// ------------------------------------------------------------------
	/** Prend une structure du catalogue en main ; l'outil « Carte : structure » la posera. */
	public String choose(ServerPlayer p, String id) {
		Desk d = require(p);
		StructureStore.Def def = store.def(id);
		store.clip(id); // lue tout de suite : un fichier abîmé se signale ici, pas au moment de poser
		d.structure = def.id;
		d.turn = Turn.NONE;
		ValcombeUi.holdMapTool(p, ValcombeUi.MapTool.STRUCTURE);
		sendGhost(p, d);
		return "« " + def.nom + " » en main (" + def.taille[0] + " × " + def.taille[1] + " × " + def.taille[2] + "). Visez un bloc : son fantôme "
			+ "montre où elle sera posée. Clic droit avec l'outil « Carte : structure » : la poser. Accroupi + clic droit : "
			+ "la tourner. Clic gauche : la retourner.";
	}

	private StructureStore.Def chosen(Desk d) {
		if (d.structure == null) {
			throw new GameException("Aucune structure en main : /vc structure poser <id> (catalogue : /vc structure liste).");
		}
		return store.def(d.structure);
	}

	public String rotate(ServerPlayer p) {
		Desk d = require(p);
		StructureStore.Def def = chosen(d);
		d.turn = d.turn.rotated();
		sendGhost(p, d);
		return "« " + def.nom + " » " + (d.turn.label().isEmpty() ? "dans son orientation d'origine" : d.turn.label()) + ".";
	}

	public String mirror(ServerPlayer p) {
		Desk d = require(p);
		StructureStore.Def def = chosen(d);
		// Un clic gauche maintenu redemande le miroir plusieurs fois par seconde : on n'en garde qu'un.
		if (ticks - d.lastFlip < 8) {
			return "";
		}
		d.lastFlip = ticks;
		d.turn = d.turn.mirrored();
		sendGhost(p, d);
		return "« " + def.nom + " » " + (d.turn.label().isEmpty() ? "dans son orientation d'origine" : d.turn.label()) + ".";
	}

	public String stopPlacing(ServerPlayer p) {
		Desk d = require(p);
		d.structure = null;
		ghostOff(p, d);
		return "Plus de structure en main.";
	}

	/** Où tombe le coin le plus bas de la structure quand les pieds sont en feet. */
	private static BlockPos corner(StructureStore.Def def, Turn turn, BlockPos feet) {
		int w = def.taille[0];
		int l = def.taille[2];
		return feet.offset(-turn.x(def.ancre[0], def.ancre[2], w, l), -def.ancre[1], -turn.z(def.ancre[0], def.ancre[2], w, l));
	}

	/** Pose la structure en main sur le bloc cliqué, dans l'orientation choisie, avec son repère s'il y a une carte. */
	public String place(ServerPlayer p, BlockPos clicked) {
		Desk d = require(p);
		host.requireFree();
		StructureStore.Def def = chosen(d);
		Clip clip = store.clip(def.id).turned(d.turn);
		BlockPos feet = clicked.above();
		BlockPos corner = corner(def, d.turn, feet);
		Box zone = Box.at(corner, clip.width, clip.height, clip.length);
		Village v = village();
		boolean house = v != null && def.repere.equals("maison");
		boolean room = v != null && def.repere.equals("salle");
		if (house) {
			host.requireRoom(v, false);
		}
		if (house || room) {
			McHost.requireNear(v, feet);
		}
		Clip before = snap(zone);
		Marker marker = null;
		String with = "";
		if (house) {
			v.addHouse(feet);
			marker = new Marker(Village.Mark.HOUSE, feet, null, true);
			with = ", avec le repère de la maison " + v.houseCount();
		} else if (room) {
			marker = new Marker(Village.Mark.ROOM, feet, v.convocation(), true);
			v.setConvocation(feet);
			with = ", avec le repère de la salle de convocation";
		}
		if (marker != null) {
			host.editorMarkersChanged();
		}
		commit("Pose de « " + def.nom + " »", zone, before, clip.plan(level(), corner, false, def.fournie), marker);
		placed.add(new Placed(def.id, feet, d.turn, corner, zone, before, marker));
		return "« " + def.nom + " » posée" + (d.turn.label().isEmpty() ? "" : " (" + d.turn.label() + ")") + with + "."
			+ " L'outil « Carte : main » la reprend, la tourne ou la retire." + UNDO;
	}

	/** Pose la structure en main à ses pieds (sans viser), pour les commandes. */
	public String placeHere(ServerPlayer p) {
		return place(p, p.blockPosition().below());
	}

	// ------------------------------------------------------------------
	// Maquettes et fantôme (mod client) : la structure en 3D avant de la choisir, puis là où on la posera
	// ------------------------------------------------------------------
	/** Au-delà, pas de maquette (le paquet resterait raisonnable, mais l'écran n'en montrerait qu'une bouillie). */
	static final int MODEL_MAX_CELLS = 96 * 96 * 48;

	/**
	 * La structure telle que le client la dessine : taille, palette (noms des blocs, sans l'air) et cases en
	 * base64 (un octet par case, 0 : vide, n : n-ième bloc de la palette ; deux octets si la palette dépasse 255).
	 */
	String modelJson(String id, Turn turn, String kind) {
		StructureStore.Def def = store.def(id);
		Clip clip = store.clip(id).turned(turn);
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		o.addProperty("kind", kind);
		o.addProperty("id", def.id);
		o.addProperty("nom", def.nom);
		o.addProperty("w", clip.width);
		o.addProperty("h", clip.height);
		o.addProperty("l", clip.length);
		if (clip.cells.length > MODEL_MAX_CELLS) {
			o.addProperty("tropGrande", true);
			return o.toString();
		}
		java.util.List<String> names = new java.util.ArrayList<>();
		int[] rank = new int[clip.palette.size()];
		for (int i = 0; i < clip.palette.size(); i++) {
			BlockState s = clip.palette.get(i);
			if (s.isAir() || s.is(Blocks.STRUCTURE_VOID)) {
				rank[i] = 0;
			} else {
				names.add(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
				rank[i] = names.size();
			}
		}
		boolean wide = names.size() > 255;
		byte[] raw = new byte[clip.cells.length * (wide ? 2 : 1)];
		for (int i = 0; i < clip.cells.length; i++) {
			int v = clip.cells[i] == Clip.EMPTY ? 0 : rank[clip.cells[i]];
			if (wide) {
				raw[2 * i] = (byte) (v >> 8);
				raw[2 * i + 1] = (byte) v;
			} else {
				raw[i] = (byte) v;
			}
		}
		com.google.gson.JsonArray pal = new com.google.gson.JsonArray();
		names.forEach(pal::add);
		o.add("palette", pal);
		o.addProperty("large", wide);
		o.addProperty("cases", java.util.Base64.getEncoder().encodeToString(raw));
		return o.toString();
	}

	private static void sendPreview(ServerPlayer p, String json) {
		if (ViewSync.hasClientMod(p)) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new fr.valcombe.net.ValcombeNet.PreviewPayload(json));
		}
	}

	/** La maquette d'une structure du catalogue, pour l'écran de ce joueur. */
	public String sendModel(ServerPlayer p, String id) {
		if (!ViewSync.hasClientMod(p)) {
			throw new GameException("La maquette s'affiche dans l'écran de l'Atelier, qui demande le mod de Valcombe sur votre jeu.");
		}
		sendPreview(p, modelJson(id, Turn.NONE, "maquette"));
		return "";
	}

	/** La structure en main, dans son orientation : le client en dessine le fantôme là où l'on vise. */
	private void sendGhost(ServerPlayer p, Desk d) {
		if (d.structure == null) {
			ghostOff(p, d);
			return;
		}
		try {
			sendPreview(p, modelJson(d.structure, d.turn, "fantome"));
			d.ghost = true;
		} catch (GameException gone) {
			ghostOff(p, d);
		}
	}

	private void ghostOff(ServerPlayer p, Desk d) {
		if (d.ghost) {
			sendPreview(p, "{\"kind\":\"fantome\",\"off\":true}");
			d.ghost = false;
		}
	}

	// ------------------------------------------------------------------
	// Ce que voit le constructeur : contours en particules, ligne d'état
	// ------------------------------------------------------------------
	private static BlockPos aimed(ServerPlayer p) {
		HitResult h = p.pick(64, 1f, false);
		return h instanceof BlockHitResult b && h.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
	}

	/** Appelé à chaque tick : deux fois par seconde, redessine l'aperçu de la structure en main. */
	void tick() {
		ticks++;
		if (server == null || desks.isEmpty() || ticks % 10 != 0) {
			return;
		}
		for (Map.Entry<String, Desk> e : desks.entrySet()) {
			ServerPlayer p = host.player(e.getKey());
			Desk d = e.getValue();
			if (p == null || !host.isBuilder(e.getKey()) || p.level() != work) {
				continue;
			}
			boolean holding = d.structure != null && ValcombeUi.mapTool(p.getMainHandItem()) == ValcombeUi.MapTool.STRUCTURE;
			if (!holding && d.ghost) {
				sendPreview(p, "{\"kind\":\"fantomeA\",\"hors\":true}");
			}
			if (holding) {
				BlockPos aim = aimed(p);
				if (aim == null && d.ghost) {
					sendPreview(p, "{\"kind\":\"fantomeA\",\"hors\":true}");
				}
				if (aim != null) {
					try {
						StructureStore.Def def = store.def(d.structure);
						BlockPos feet = aim.above();
						BlockPos c = corner(def, d.turn, feet);
						if (d.ghost) {
							// Le client dessine le fantôme en 3D à ce coin ; les particules restent pour un jeu sans le mod.
							sendPreview(p, "{\"kind\":\"fantomeA\",\"x\":" + c.getX() + ",\"y\":" + c.getY() + ",\"z\":" + c.getZ() + "}");
						}
						outline(p, Box.at(c, d.turn.width(def.taille[0], def.taille[2]), def.taille[1],
							d.turn.length(def.taille[0], def.taille[2])), PREVIEW);
						// Une colonne rouge là où se tiendront les pieds : le point que l'on vise.
						for (int k = 0; k < 4; k++) {
							p.level().sendParticles(p, ANCHOR, true, true, feet.getX() + 0.5, feet.getY() + 0.2 + k * 0.5, feet.getZ() + 0.5,
								1, 0, 0, 0, 0);
						}
					} catch (GameException gone) {
						d.structure = null; // supprimée du catalogue entre-temps
					}
				}
			}
		}
	}

	/** Les douze arêtes d'un pavé, en particules visibles de ce seul joueur (jamais plus de quelques centaines). */
	private static void outline(ServerPlayer p, Box b, DustParticleOptions dust) {
		double[] x = {b.x0(), b.x1() + 1};
		double[] y = {b.y0(), b.y1() + 1};
		double[] z = {b.z0(), b.z1() + 1};
		double step = Math.max(0.5, 4.0 * (b.width() + b.height() + b.length()) / 360);
		ServerLevel level = p.level();
		for (int i = 0; i < 2; i++) {
			for (int k = 0; k < 2; k++) {
				for (double t = x[0]; t <= x[1]; t += step) {
					level.sendParticles(p, dust, true, true, t, y[i], z[k], 1, 0, 0, 0, 0);
				}
				for (double t = y[0]; t <= y[1]; t += step) {
					level.sendParticles(p, dust, true, true, x[i], t, z[k], 1, 0, 0, 0, 0);
				}
				for (double t = z[0]; t <= z[1]; t += step) {
					level.sendParticles(p, dust, true, true, x[i], y[k], t, 1, 0, 0, 0, 0);
				}
			}
		}
	}

	/** La ligne au-dessus de la barre d'objets quand le constructeur tient un outil de l'éditeur ; null sinon. */
	String status(ServerPlayer p) {
		ValcombeUi.MapTool tool = ValcombeUi.mapTool(p.getMainHandItem());
		Desk d = desks.get(p.getScoreboardName());
		if (tool == ValcombeUi.MapTool.STRUCTURE) {
			if (d == null || d.structure == null) {
				return "Aucune structure en main : /vc structure liste";
			}
			try {
				// La ligne doit tenir dans la largeur de l'écran : les gestes sont rappelés par l'outil lui-même.
				StructureStore.Def def = store.def(d.structure);
				return "« " + def.nom + " » " + d.turn.width(def.taille[0], def.taille[2]) + " × " + def.taille[1] + " × "
					+ d.turn.length(def.taille[0], def.taille[2]) + (d.turn.label().isEmpty() ? "" : " · " + d.turn.label())
					+ " · clic droit : poser";
			} catch (GameException gone) {
				return null;
			}
		}
		return null;
	}

	/**
	 * Pour l'écran en jeu et l'Atelier : ce que ce constructeur a en cours. La sélection est celle de WorldEdit
	 * (largeur, hauteur, longueur), les nombres à annuler et à rétablir ceux de l'« annuler » commun.
	 */
	public Map<String, Object> state(String player) {
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		Desk d = desks.get(player);
		ServerPlayer p = server == null ? null : server.getPlayerList().getPlayerByName(player);
		WorldEditGate gate = WorldEditGate.current();
		WorldEditLink.Selection sel = gate == null || p == null ? null : gate.link().selection(p);
		out.put("construction", host.isBuilder(player));
		var access = fr.valcombe.mc.worlds.BuildAccess.current();
		out.put("worldedit", access != null && p != null && access.canUseWorldEdit(p));
		out.put("selection", sel == null || !sel.complete() || sel.level() != p.level() ? null
			: java.util.List.of(sel.width(), sel.height(), sel.length()));
		out.put("structure", d == null ? null : d.structure);
		out.put("orientation", d == null ? "" : d.turn.label());
		out.put("annulables", gate == null || p == null ? history.undoable() : gate.undo().undoable(p));
		out.put("retablissables", gate == null || p == null ? history.redoable() : gate.undo().redoable(p));
		return out;
	}
}
