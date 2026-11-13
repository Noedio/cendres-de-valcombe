package fr.valcombe.mc;

import fr.valcombe.mc.worlds.BuildAccess;
import fr.valcombe.rules.GameException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * L'« annuler » commun de la construction : {@code /vc annuler} et {@code /vc retablir} défont et refont <b>dans
 * l'ordre où ils ont été faits</b> les gestes de trois piles qui ont chacune leur propre historique :
 * <ul>
 * <li>les modifications <b>WorldEdit</b> du joueur (sa session WorldEdit, celle de //undo et //redo) ;</li>
 * <li>les <b>structures du catalogue</b> posées, tournées, retirées (l'historique de {@link MapEditor}, en
 * {@link History}) ;</li>
 * <li>les <b>repères</b> de la carte : trônes, maisons, centre, salle de convocation (la pile de {@link McHost}).</li>
 * </ul>
 *
 * <h2>Le choix</h2>
 * Chaque pile garde sa façon d'annuler (WorldEdit son historique, Valcombe ses copies de terrain et ses repères) :
 * on ne les fond pas en une seule, on tient seulement le <b>journal</b> de l'ordre des gestes ({@link Journal}). Un
 * « annuler » prend le dernier geste du journal et le confie à la bonne pile. Ainsi //undo, {@code /vc carte annuler}
 * et le bouton « Annuler » de l'Atelier continuent de marcher : le journal se recale sur les piles à chaque tick
 * (un geste défait directement en disparaît, un geste fait ailleurs y entre à son rang).
 * <ul>
 * <li>Les gestes WorldEdit sont ceux <b>du joueur</b> (chacun a sa session). Les gestes Valcombe sont dans des piles
 * communes (une seule carte en construction, un seul historique) : chacun porte son auteur, le joueur dont la commande
 * ou le clic l'a fait ({@link #acting}). Un invité d'un atelier n'annule que les siens, et seulement tant qu'aucun geste
 * d'un autre ne les recouvre dans la même pile ; un opérateur annule tout. Les repères sont une pile par monde (chaque
 * partie, chaque atelier carte) : le journal note le monde de chaque geste de repère, et seuls ceux du monde où se tient
 * le joueur comptent pour lui (un geste fait dans un autre monde ne recouvre rien ici, ni ne s'y défait).</li>
 * <li>Les repères n'ont pas de « rétablir » : un repère annulé se repose avec son outil.</li>
 * <li>Un nouveau geste vide la liste de ce qu'on peut rétablir, comme partout.</li>
 * </ul>
 * Tout se fait sur le fil du serveur.
 */
public final class SharedUndo {
	/** Les piles d'annulation. */
	public enum Stack {
		WORLDEDIT("modification WorldEdit"),
		STRUCTURES("structure du catalogue"),
		MARKERS("modification des repères");

		final String label;

		Stack(String label) {
			this.label = label;
		}
	}

	/**
	 * Le journal : l'ordre des gestes des trois piles, recalé sur elles. Sans Minecraft (les joueurs sont des
	 * identifiants, les gestes WorldEdit des objets comparés par identité) : vérifié par les tests JUnit.
	 */
	public static final class Journal {
		/**
		 * Un geste : sa pile, son auteur (null s'il n'est pas connu : seul un opérateur le défait), l'objet de WorldEdit
		 * qui le représente, et le monde où il a été fait (repères : l'identifiant du monde de la partie ou de l'atelier ;
		 * null : partout). Deux gestes ne sont jamais « égaux » : chacun est lui-même (deux poses de structure se
		 * ressemblent, mais n'ont pas le même rang).
		 */
		public static final class Entry {
			private final Stack stack;
			private final UUID player;
			private final Object token;
			private final String place;

			Entry(Stack stack, UUID player, Object token) {
				this(stack, player, token, null);
			}

			Entry(Stack stack, UUID player, Object token, String place) {
				this.stack = stack;
				this.player = player;
				this.token = token;
				this.place = place;
			}

			public Stack stack() {
				return stack;
			}

			public UUID player() {
				return player;
			}

			public Object token() {
				return token;
			}

			public String place() {
				return place;
			}
		}

		/** La pile d'un geste de Valcombe dans un monde (les repères en ont une par monde). */
		private static String key(Stack stack, String place) {
			return place == null ? stack.name() : stack.name() + "@" + place;
		}

		private static String key(Entry e) {
			return key(e.stack(), e.place());
		}

		/** Ce geste compte-t-il pour un joueur qui se tient dans ce monde (here, null : partout) ? */
		private static boolean here(Entry e, String here) {
			return e.place() == null || here == null || e.place().equals(here);
		}

		/** Au-delà, les plus anciens gestes sont oubliés du journal (leurs piles, elles, ont leurs propres limites). */
		static final int MAX = 400;

		private final List<Entry> done = new ArrayList<>();
		private final List<Entry> undone = new ArrayList<>();
		/**
		 * Combien des prochaines entrées d'une pile Valcombe (clé : {@link #key(Stack, String)}) sont un « rétablir »
		 * attendu (et pas un geste nouveau).
		 */
		private final Map<String, Integer> expected = new java.util.HashMap<>();
		/**
		 * Combien de gestes d'une pile Valcombe le journal a déjà défaits sans que la pile l'ait encore vu : une pile qui
		 * pose ses blocs par petits lots (les structures) défait le premier geste tout de suite et enchaîne les suivants
		 * à la fin de chaque chantier. Sans ce compte, le recalage prendrait l'écart pour des gestes nouveaux, et
		 * viderait la liste à rétablir.
		 */
		private final Map<String, Integer> undoing = new java.util.HashMap<>();
		/** Les auteurs des « rétablir » attendus d'une pile Valcombe, dans l'ordre : le geste rétabli garde son auteur. */
		private final Map<String, List<UUID>> expectedAuthors = new java.util.HashMap<>();

		private int count(Stack s, String place) {
			int n = 0;
			for (Entry e : done) {
				if (e.stack() == s && java.util.Objects.equals(e.place(), place)) {
					n++;
				}
			}
			return n;
		}

		/** Les mondes où le journal tient des gestes de cette pile (pour recaler ceux qui n'existent plus). */
		public Set<String> places(Stack s) {
			Set<String> out = new java.util.HashSet<>();
			for (Entry e : done) {
				if (e.stack() == s && e.place() != null) {
					out.add(e.place());
				}
			}
			for (Entry e : undone) {
				if (e.stack() == s && e.place() != null) {
					out.add(e.place());
				}
			}
			return out;
		}

		private void add(Entry e, boolean redo) {
			done.add(e);
			if (!redo) {
				undone.clear(); // un geste nouveau : plus rien à rétablir
			}
			while (done.size() > MAX) {
				done.remove(0);
			}
		}

		/**
		 * Recale le journal sur une pile de Valcombe, qui compte depth gestes à annuler : ceux qui manquent ont été
		 * défaits directement (on retire les plus récents), ceux qui s'ajoutent viennent d'être faits (à la fin).
		 */
		public void syncCount(Stack stack, int depth) {
			syncCount(stack, depth, List.of());
		}

		/**
		 * Recale le journal sur une pile de Valcombe (voir {@link #syncCount(Stack, int)}) ; authors : les auteurs des
		 * gestes faits sur cette pile depuis le recalage d'avant, du plus ancien au plus récent. Les gestes nouveaux
		 * reçoivent les derniers d'entre eux (une pile pleine oublie ses plus anciens gestes : il peut y avoir plus
		 * d'auteurs que de gestes nouveaux).
		 */
		public void syncCount(Stack stack, int depth, List<UUID> authors) {
			syncCount(stack, null, depth, authors);
		}

		/**
		 * Recale le journal sur la pile d'une pile de Valcombe dans un monde (place : son identifiant ; null pour une
		 * pile qui sert partout, comme celle des structures). Voir {@link #syncCount(Stack, int, List)}.
		 */
		public void syncCount(Stack stack, String place, int depth, List<UUID> authors) {
			String k = key(stack, place);
			int pending = undoing.getOrDefault(k, 0);
			int have = count(stack, place) + pending;
			// La pile rattrape d'abord les gestes que le journal a déjà défaits.
			int caught = Math.min(pending, Math.max(0, have - depth));
			if (caught > 0) {
				pending -= caught;
				have -= caught;
				if (pending > 0) {
					undoing.put(k, pending);
				} else {
					undoing.remove(k);
				}
			}
			for (int i = done.size() - 1; i >= 0 && have > depth; i--) {
				if (done.get(i).stack() == stack && java.util.Objects.equals(done.get(i).place(), place)) {
					done.remove(i);
					have--;
				}
			}
			int fresh = Math.max(0, depth - have - expected.getOrDefault(k, 0));
			int next = authors.size() - fresh;
			while (have < depth) {
				int wait = expected.getOrDefault(k, 0);
				UUID author;
				if (wait > 0) {
					expected.put(k, wait - 1);
					List<UUID> waiting = expectedAuthors.get(k);
					author = waiting == null || waiting.isEmpty() ? null : waiting.remove(0);
				} else {
					author = next >= 0 && next < authors.size() ? authors.get(next) : null;
					next++;
				}
				add(new Entry(stack, author, null, place), wait > 0);
				have++;
			}
		}

		/**
		 * Recale le journal sur la session WorldEdit d'un joueur : live est la liste de ses modifications qu'on peut
		 * annuler, de la plus ancienne à la plus récente. Celles qui n'y sont plus (défaites par //undo, ou oubliées
		 * par WorldEdit) sortent du journal ; les nouvelles y entrent à la fin (une modification rétablie par //redo
		 * sort aussi de la liste à rétablir).
		 */
		public void syncWorldEdit(UUID player, List<Object> live) {
			Set<Object> alive = Collections.newSetFromMap(new IdentityHashMap<>());
			alive.addAll(live);
			done.removeIf(e -> e.stack() == Stack.WORLDEDIT && player.equals(e.player()) && !alive.contains(e.token()));
			Set<Object> known = Collections.newSetFromMap(new IdentityHashMap<>());
			for (Entry e : done) {
				if (e.stack() == Stack.WORLDEDIT && player.equals(e.player())) {
					known.add(e.token());
				}
			}
			for (Object token : live) {
				if (known.contains(token)) {
					continue;
				}
				boolean redo = undone.removeIf(e -> e.stack() == Stack.WORLDEDIT && e.token() == token);
				add(new Entry(Stack.WORLDEDIT, player, token), redo);
			}
		}

		/**
		 * Ce geste est-il à ce joueur ? Les siens dans WorldEdit ; dans les piles de Valcombe (valcombe : il peut y toucher
		 * là où il est), tous s'il est opérateur (all), sinon ceux dont il est l'auteur.
		 */
		private static boolean mine(Entry e, UUID player, boolean valcombe, boolean all) {
			if (e.stack() == Stack.WORLDEDIT) {
				return player.equals(e.player());
			}
			return valcombe && (all || player.equals(e.player()));
		}

		/**
		 * Les gestes que ce joueur, dans le monde here (null : partout), peut défaire (ou refaire), du plus récent au plus
		 * ancien. Une pile de Valcombe défait toujours son dernier geste : un geste d'un autre au sommet d'une pile
		 * bloque, pour un invité, les siens dessous. Les repères d'un autre monde ne comptent pas : ni à défaire ici, ni
		 * par-dessus les siens.
		 */
		private List<Entry> reachable(List<Entry> from, UUID player, boolean valcombe, boolean all, String here) {
			List<Entry> out = new ArrayList<>();
			Set<String> blocked = new java.util.HashSet<>();
			for (int i = from.size() - 1; i >= 0; i--) {
				Entry e = from.get(i);
				if (!here(e, here) || blocked.contains(key(e))) {
					continue;
				}
				if (mine(e, player, valcombe, all)) {
					out.add(e);
				} else if (e.stack() != Stack.WORLDEDIT) {
					blocked.add(key(e));
				}
			}
			return out;
		}

		/** Le dernier geste que ce joueur peut annuler, ou null. */
		public Entry nextUndo(UUID player, boolean valcombe) {
			return nextUndo(player, valcombe, true);
		}

		public Entry nextUndo(UUID player, boolean valcombe, boolean all) {
			return nextUndo(player, valcombe, all, null);
		}

		public Entry nextUndo(UUID player, boolean valcombe, boolean all, String here) {
			List<Entry> r = reachable(done, player, valcombe, all, here);
			return r.isEmpty() ? null : r.get(0);
		}

		/** Le dernier geste annulé que ce joueur peut rétablir, ou null. */
		public Entry nextRedo(UUID player, boolean valcombe) {
			return nextRedo(player, valcombe, true);
		}

		public Entry nextRedo(UUID player, boolean valcombe, boolean all) {
			List<Entry> r = reachable(undone, player, valcombe, all, null);
			return r.isEmpty() ? null : r.get(0);
		}

		/**
		 * Les derniers gestes de ce joueur (au plus max) qui sont tous de la même pile que le tout dernier, du plus
		 * récent au plus ancien : une pile qui pose ses blocs par petits lots (les structures) les défait d'un coup.
		 */
		public List<Entry> lastRun(UUID player, boolean valcombe, int max, boolean redo) {
			return lastRun(player, valcombe, true, max, redo);
		}

		/** Comme {@link #lastRun(UUID, boolean, int, boolean)} ; all : il peut défaire les gestes Valcombe de tous. */
		public List<Entry> lastRun(UUID player, boolean valcombe, boolean all, int max, boolean redo) {
			return lastRun(player, valcombe, all, null, max, redo);
		}

		/** Comme {@link #lastRun(UUID, boolean, boolean, int, boolean)}, pour un joueur qui se tient dans le monde here. */
		public List<Entry> lastRun(UUID player, boolean valcombe, boolean all, String here, int max, boolean redo) {
			List<Entry> from = redo ? undone : done;
			List<Entry> out = new ArrayList<>();
			Set<String> blocked = new java.util.HashSet<>();
			for (int i = from.size() - 1; i >= 0 && out.size() < max; i--) {
				Entry e = from.get(i);
				if (!here(e, here)) {
					continue; // un repère d'un autre monde : il ne se défait pas d'ici, et ne recouvre rien ici
				}
				boolean m = mine(e, player, valcombe, all) && !blocked.contains(key(e));
				if (!m) {
					if (e.stack() != Stack.WORLDEDIT) {
						blocked.add(key(e)); // le geste d'un autre au sommet de sa pile : rien dessous pour ce joueur
					}
					continue;
				}
				if (!out.isEmpty() && e.stack() != out.get(0).stack()) {
					break;
				}
				out.add(e);
			}
			return out;
		}

		/** Ce geste vient d'être défait par l'« annuler » commun : il passe dans la liste à rétablir (sauf un repère). */
		public void undid(Entry e) {
			done.remove(e);
			if (e.stack() != Stack.MARKERS) {
				undone.add(e);
			}
			if (e.stack() != Stack.WORLDEDIT) {
				undoing.merge(key(e), 1, Integer::sum); // la pile le défait maintenant, ou à la fin du chantier en cours
			}
		}

		/** Ce geste va être refait par le « rétablir » commun : sa pile va le rendre, ce n'est pas un geste nouveau. */
		public void redoing(Entry e) {
			if (e.stack() != Stack.WORLDEDIT) {
				undone.remove(e);
				expected.merge(key(e), 1, Integer::sum);
				expectedAuthors.computeIfAbsent(key(e), k -> new ArrayList<>()).add(e.player());
			}
			// Un geste WorldEdit reste dans la liste à rétablir : syncWorldEdit l'en retire quand la session le rend.
		}

		/** Le rétablir attendu n'a pas eu lieu (la pile a refusé, ou n'avait plus rien) : on ne l'attend plus. */
		public void redoFailed(Entry e) {
			undone.remove(e);
			expected.computeIfPresent(key(e), (s, n) -> n > 1 ? n - 1 : null);
			List<UUID> waiting = expectedAuthors.get(key(e));
			if (waiting != null && !waiting.isEmpty()) {
				waiting.remove(waiting.size() - 1);
			}
		}

		public int undoable(UUID player, boolean valcombe) {
			return undoable(player, valcombe, true);
		}

		public int undoable(UUID player, boolean valcombe, boolean all) {
			return undoable(player, valcombe, all, null);
		}

		public int undoable(UUID player, boolean valcombe, boolean all, String here) {
			return reachable(done, player, valcombe, all, here).size();
		}

		public int redoable(UUID player, boolean valcombe) {
			return redoable(player, valcombe, true);
		}

		public int redoable(UUID player, boolean valcombe, boolean all) {
			return redoable(player, valcombe, all, null);
		}

		public int redoable(UUID player, boolean valcombe, boolean all, String here) {
			return reachable(undone, player, valcombe, all, here).size();
		}

		/** Les piles des gestes, du plus ancien au plus récent (pour les tests). */
		public List<Stack> order() {
			return done.stream().map(Entry::stack).toList();
		}

		public void clear() {
			done.clear();
			undone.clear();
			expected.clear();
			undoing.clear();
			expectedAuthors.clear();
		}
	}

	// ------------------------------------------------------------------
	// Les auteurs des gestes de Valcombe
	// ------------------------------------------------------------------

	/** Le joueur dont la commande ou le clic est en cours de traitement (null : la console, un bloc de commande...). */
	private static UUID actor;
	/**
	 * Les auteurs des gestes faits sur chaque pile de Valcombe (clé : Journal.key, la pile et son monde) depuis le
	 * dernier recalage, du plus ancien au plus récent.
	 */
	private static final Map<String, List<UUID>> RECORDED = new java.util.HashMap<>();

	/** Les auteurs en cours au début de chaque commande imbriquée (rendus à sa fin). */
	private static final java.util.ArrayDeque<java.util.Optional<UUID>> OUTER = new java.util.ArrayDeque<>();

	/**
	 * Un clic de ce joueur commence (null : pas un joueur) : les gestes de Valcombe qu'il fait sont à lui. Appelé avant
	 * les écouteurs de clic (ToolPriority).
	 */
	public static void acting(ServerPlayer p) {
		actor = p == null ? null : p.getUUID();
	}

	/**
	 * Une commande commence (CommandsMixin). Celle d'un joueur lui donne les gestes qu'elle fait ; celle du serveur
	 * (p null) garde l'auteur en cours : le mod lance des commandes pour son propre compte (effacer des étiquettes,
	 * poser un décor) au milieu du geste d'un joueur, qui reste à lui.
	 */
	public static void commandStarts(ServerPlayer p) {
		OUTER.push(java.util.Optional.ofNullable(actor));
		if (p != null) {
			actor = p.getUUID();
		}
	}

	/** La commande commencée par {@link #commandStarts} est finie : l'auteur d'avant revient. */
	public static void commandEnds() {
		if (!OUTER.isEmpty()) {
			actor = OUTER.pop().orElse(null);
		}
	}

	/** Fin d'un tick : plus aucune action en cours (un geste fait ensuite sans commande ni clic n'a pas d'auteur). */
	static void tickEnded() {
		actor = null;
		OUTER.clear();
	}

	/** Une pile de Valcombe qui sert partout (les structures) vient de recevoir un geste : il est au joueur dont l'action est en cours. */
	static void recorded(Stack stack) {
		recorded(stack, null);
	}

	/**
	 * La pile de Valcombe de ce monde (place : l'identifiant du monde de la partie ou de l'atelier carte) vient de
	 * recevoir un geste : il est au joueur dont l'action est en cours.
	 */
	static void recorded(Stack stack, String place) {
		List<UUID> l = RECORDED.computeIfAbsent(Journal.key(stack, place), k -> new ArrayList<>());
		l.add(actor);
		while (l.size() > 64) {
			l.remove(0);
		}
	}

	/** Les auteurs notés pour cette pile dans ce monde depuis le dernier recalage (et on les oublie). */
	private static List<UUID> takeRecorded(Stack stack, String place) {
		List<UUID> l = RECORDED.remove(Journal.key(stack, place));
		return l == null ? List.of() : l;
	}

	/** Le monde de Valcombe où se tient ce joueur (son identifiant), ou null hors des mondes de Valcombe. */
	private static String place(ServerPlayer p) {
		var worlds = ValcombeMod.worlds();
		var e = worlds == null ? null : worlds.entryOf(p.level());
		return e == null ? null : e.id();
	}

	private final Journal journal = new Journal();
	private final WorldEditLink worldEdit;
	/** L'hôte de la partie qui se joue dans un monde, ou null (hub, atelier) : les repères sont ceux de son village. */
	private final java.util.function.Function<net.minecraft.world.level.Level, McHost> gameAt;
	/** Les hôtes de toutes les parties, pour compter leurs repères à défaire. */
	private final java.util.function.Supplier<java.util.List<McHost>> hosts;
	private final MapEditor editor;
	private MinecraftServer server;

	SharedUndo(WorldEditLink worldEdit, java.util.function.Function<net.minecraft.world.level.Level, McHost> gameAt,
			java.util.function.Supplier<java.util.List<McHost>> hosts, MapEditor editor) {
		this.worldEdit = worldEdit;
		this.gameAt = gameAt;
		this.hosts = hosts;
		this.editor = editor;
	}

	/** L'hôte de la partie du monde où se tient ce joueur, ou null. */
	private McHost gameOf(ServerPlayer p) {
		McHost h = gameAt.apply(p.level());
		return h != null && h.isGameLevel(p.level()) ? h : null;
	}

	void setServer(MinecraftServer server) {
		this.server = server;
		journal.clear();
		RECORDED.clear();
		tickEnded();
	}

	/** Recale le journal sur les trois piles (à chaque tick, et avant chaque « annuler »). */
	void sync() {
		if (server == null) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			journal.syncWorldEdit(p.getUUID(), worldEdit.history(p));
		}
		journal.syncCount(Stack.STRUCTURES, null, editor.undoable(), takeRecorded(Stack.STRUCTURES, null));
		// Chaque partie a ses repères, chaque atelier carte aussi : le journal les recale monde par monde, et chaque
		// « annuler » ne défait que ceux du monde où se tient le joueur (checkPlace, puis undoMarker de son hôte ou de
		// l'atelier).
		Map<String, Integer> markers = new java.util.HashMap<>();
		for (McHost h : hosts.get()) {
			markers.merge(h.worldId(), h.markerUndoDepth(), Integer::sum);
		}
		MapWorkshop atelier = ValcombeMod.mapWorkshop();
		if (atelier != null) {
			atelier.markerUndoDepths().forEach((id, n) -> markers.merge(id, n, Integer::sum));
		}
		for (String gone : journal.places(Stack.MARKERS)) {
			markers.putIfAbsent(gone, 0); // un monde fermé ou supprimé : ses repères sortent du journal
		}
		markers.putIfAbsent(null, 0); // de même pour ceux d'une partie hors des mondes de Valcombe
		markers.forEach((place, depth) -> journal.syncCount(Stack.MARKERS, place, depth, takeRecorded(Stack.MARKERS, place)));
		RECORDED.keySet().removeIf(k -> k.startsWith(Stack.MARKERS.name())); // notés pour un monde qui n'a plus de pile
	}

	/** Ce joueur est-il dans un atelier carte où il construit ? Les repères de sa carte s'y défont. */
	private static boolean inMapWorkshop(ServerPlayer p) {
		MapWorkshop atelier = ValcombeMod.mapWorkshop();
		return atelier != null && atelier.buildsHere(p);
	}

	private static boolean operator(ServerPlayer p) {
		return BuildAccess.operator(p);
	}

	/**
	 * Ce joueur peut-il défaire ici les gestes de Valcombe ? Les repères appartiennent au village du monde de la partie ;
	 * les poses de structures, au monde où travaille l'éditeur (celui où un opérateur s'en est servi en dernier). Ailleurs,
	 * l'« annuler » commun ne défait que ses gestes WorldEdit. Chaque pile vérifie aussi, au moment de défaire, que le
	 * joueur est bien là où elle travaille ({@link #checkPlace}).
	 */
	private boolean valcombeHere(ServerPlayer p) {
		return server != null && (inMapWorkshop(p) || operator(p) && (gameOf(p) != null || editor.worksIn(p.level())));
	}

	/**
	 * Refuse de défaire (ou refaire) un geste de Valcombe hors du monde où sa pile travaille : un repère se défait dans
	 * le monde de la partie, une pose de structure là où travaille l'éditeur. Sans cela, un opérateur dans un atelier
	 * retoucherait les repères de la partie à distance, ou l'éditeur oublierait son historique en changeant de monde.
	 */
	private void checkPlace(ServerPlayer p, Stack stack) {
		if (stack == Stack.MARKERS && gameOf(p) == null && !inMapWorkshop(p)) {
			throw new GameException("les repères se défont là où ils ont été posés : le monde de leur partie ou l'atelier de la carte "
				+ "(Menu J › Mondes).");
		}
		if (stack == Stack.MARKERS && inMapWorkshop(p) && ValcombeMod.mapWorkshop().markerUndoDepth(p.level()) == 0) {
			throw new GameException("les derniers repères modifiés sont ceux d'une autre carte : ils se défont dans son monde.");
		}
		if (stack == Stack.MARKERS && !inMapWorkshop(p) && gameOf(p) != null && gameOf(p).markerUndoDepth() == 0) {
			throw new GameException("les derniers repères modifiés sont ceux d'une autre partie ou d'un atelier carte : "
				+ "ils se défont dans leur monde.");
		}
		if (stack == Stack.STRUCTURES && !editor.worksIn(p.level())) {
			throw new GameException("les structures se défont dans le monde où elles ont été posées.");
		}
	}

	public int undoable(ServerPlayer p) {
		return journal.undoable(p.getUUID(), valcombeHere(p), operator(p), place(p));
	}

	public int redoable(ServerPlayer p) {
		return journal.redoable(p.getUUID(), valcombeHere(p), operator(p), place(p));
	}

	/**
	 * La réponse d'une pile, à glisser dans une phrase : « Annulé : pose de « Puits ». » devient « pose de « Puits » ».
	 */
	static String clean(String msg) {
		String m = msg.strip();
		for (String head : new String[] {"Annulé : ", "Rétabli : "}) {
			if (m.startsWith(head)) {
				m = m.substring(head.length());
			}
		}
		if (m.startsWith("Dernière modification des repères annulée")) {
			m = "modification des repères" + m.substring("Dernière modification des repères annulée".length());
		}
		if (m.endsWith(".")) {
			m = m.substring(0, m.length() - 1);
		}
		return m;
	}

	/** Seul qui construit quelque part s'en sert : les opérateurs, et les invités dans leurs ateliers. */
	private static void requireBuilder(ServerPlayer p) {
		BuildAccess a = BuildAccess.current();
		if (!operator(p) && (a == null || !a.canUseWorldEdit(p))) {
			throw new GameException(a == null ? "La construction n'est pas disponible sur ce serveur." : a.refusal(p));
		}
	}

	/**
	 * {@code /vc annuler [fois]} : défait les derniers gestes, du plus récent au plus ancien, chacun par sa pile. Des
	 * structures annulées à la suite le sont d'un seul coup (leur pile pose ses blocs par petits lots, et enchaîne).
	 */
	public String undo(ServerPlayer p, int times) {
		requireBuilder(p);
		List<String> said = new ArrayList<>();
		int count = 0;
		int left = Math.max(1, times);
		while (left > 0) {
			sync();
			List<Journal.Entry> run = journal.lastRun(p.getUUID(), valcombeHere(p), operator(p), place(p), left, false);
			if (run.isEmpty()) {
				if (said.isEmpty() && !operator(p) && journal.undoable(p.getUUID(), valcombeHere(p), true, place(p)) > 0) {
					// Il reste des gestes, mais pas à lui : un invité ne défait que les siens.
					throw new GameException("Rien à annuler de votre part : les derniers gestes de cet atelier sont ceux d'un autre "
						+ "(chacun annule les siens ; un opérateur peut tout annuler).");
				}
				break;
			}
			Journal.Entry e = run.get(0);
			int n = e.stack() == Stack.STRUCTURES ? run.size() : 1;
			String msg;
			try {
				checkPlace(p, e.stack());
				msg = switch (e.stack()) {
					case WORLDEDIT -> worldEdit.undo(p);
					case STRUCTURES -> editor.undo(p, n);
					case MARKERS -> inMapWorkshop(p) ? ValcombeMod.mapWorkshop().undoMarker(p) : gameOf(p).undoMarker();
				};
			} catch (GameException refused) {
				if (said.isEmpty()) {
					throw new GameException("La dernière " + e.stack().label + " ne s'annule pas maintenant : " + refused.getMessage());
				}
				said.add("arrêt : " + refused.getMessage());
				break;
			}
			for (int i = 0; i < n; i++) {
				journal.undid(run.get(i));
			}
			count += n;
			left -= n;
			said.add(n > 1 ? n + " opérations sur les structures du catalogue"
				: msg == null || msg.isBlank() ? e.stack().label : clean(msg));
		}
		sync();
		if (said.isEmpty()) {
			return "Rien à annuler.";
		}
		return (count == 1 ? "Annulé : " : count + " gestes annulés : ") + String.join(" ; ", said) + "."
			+ (redoable(p) > 0 ? " Rétablir : /vc retablir." : "");
	}

	/** {@code /vc retablir [fois]} : refait les derniers gestes annulés par {@code /vc annuler}. */
	public String redo(ServerPlayer p, int times) {
		requireBuilder(p);
		List<String> said = new ArrayList<>();
		int count = 0;
		int left = Math.max(1, times);
		while (left > 0) {
			sync();
			List<Journal.Entry> run = journal.lastRun(p.getUUID(), valcombeHere(p), operator(p), place(p), left, true);
			if (run.isEmpty()) {
				break;
			}
			Journal.Entry e = run.get(0);
			int n = e.stack() == Stack.STRUCTURES ? run.size() : 1;
			List<Journal.Entry> these = run.subList(0, n);
			these.forEach(journal::redoing);
			String msg;
			try {
				checkPlace(p, e.stack());
				msg = switch (e.stack()) {
					case WORLDEDIT -> worldEdit.redo(p);
					case STRUCTURES -> editor.redo(p, n);
					case MARKERS -> null;
				};
			} catch (GameException refused) {
				these.forEach(journal::redoFailed);
				if (said.isEmpty()) {
					throw new GameException("La dernière " + e.stack().label + " annulée ne se rétablit pas maintenant : "
						+ refused.getMessage());
				}
				break;
			}
			left -= n;
			if (msg == null) {
				these.forEach(journal::redoFailed);
				continue;
			}
			count += n;
			said.add(n > 1 ? n + " opérations sur les structures du catalogue" : msg.isBlank() ? e.stack().label : clean(msg));
		}
		sync();
		if (said.isEmpty()) {
			return "Rien à rétablir.";
		}
		return (count == 1 ? "Rétabli : " : count + " gestes rétablis : ") + String.join(" ; ", said) + ".";
	}

	/** Pour les tests en jeu : les piles des gestes du journal, du plus ancien au plus récent. */
	public List<Stack> order() {
		sync();
		return journal.order();
	}
}
