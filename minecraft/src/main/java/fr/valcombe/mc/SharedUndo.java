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
 * <li>Les gestes WorldEdit sont ceux <b>du joueur</b> (chacun a sa session) ; les gestes Valcombe sont communs à tous
 * les opérateurs (une seule carte en construction, un seul historique), et seuls les opérateurs les annulent.</li>
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
		 * Un geste : sa pile, son auteur (gestes WorldEdit), et l'objet de WorldEdit qui le représente. Deux gestes ne
		 * sont jamais « égaux » : chacun est lui-même (deux poses de structure se ressemblent, mais n'ont pas le même rang).
		 */
		public static final class Entry {
			private final Stack stack;
			private final UUID player;
			private final Object token;

			Entry(Stack stack, UUID player, Object token) {
				this.stack = stack;
				this.player = player;
				this.token = token;
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
		}

		/** Au-delà, les plus anciens gestes sont oubliés du journal (leurs piles, elles, ont leurs propres limites). */
		static final int MAX = 400;

		private final List<Entry> done = new ArrayList<>();
		private final List<Entry> undone = new ArrayList<>();
		/** Combien des prochaines entrées d'une pile Valcombe sont un « rétablir » attendu (et pas un geste nouveau). */
		private final Map<Stack, Integer> expected = new java.util.EnumMap<>(Stack.class);
		/**
		 * Combien de gestes d'une pile Valcombe le journal a déjà défaits sans que la pile l'ait encore vu : une pile qui
		 * pose ses blocs par petits lots (les structures) défait le premier geste tout de suite et enchaîne les suivants
		 * à la fin de chaque chantier. Sans ce compte, le recalage prendrait l'écart pour des gestes nouveaux, et
		 * viderait la liste à rétablir.
		 */
		private final Map<Stack, Integer> undoing = new java.util.EnumMap<>(Stack.class);

		private int count(Stack s) {
			int n = 0;
			for (Entry e : done) {
				if (e.stack() == s) {
					n++;
				}
			}
			return n;
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
			int pending = undoing.getOrDefault(stack, 0);
			int have = count(stack) + pending;
			// La pile rattrape d'abord les gestes que le journal a déjà défaits.
			int caught = Math.min(pending, Math.max(0, have - depth));
			if (caught > 0) {
				pending -= caught;
				have -= caught;
				if (pending > 0) {
					undoing.put(stack, pending);
				} else {
					undoing.remove(stack);
				}
			}
			for (int i = done.size() - 1; i >= 0 && have > depth; i--) {
				if (done.get(i).stack() == stack) {
					done.remove(i);
					have--;
				}
			}
			while (have < depth) {
				int wait = expected.getOrDefault(stack, 0);
				if (wait > 0) {
					expected.put(stack, wait - 1);
				}
				add(new Entry(stack, null, null), wait > 0);
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

		/** Ce geste peut-il être défait (ou refait) par ce joueur ? Les siens dans WorldEdit, et ceux de Valcombe s'il est opérateur. */
		private static boolean mine(Entry e, UUID player, boolean valcombe) {
			return e.stack() == Stack.WORLDEDIT ? player.equals(e.player()) : valcombe;
		}

		/** Le dernier geste que ce joueur peut annuler, ou null. */
		public Entry nextUndo(UUID player, boolean valcombe) {
			for (int i = done.size() - 1; i >= 0; i--) {
				if (mine(done.get(i), player, valcombe)) {
					return done.get(i);
				}
			}
			return null;
		}

		/** Le dernier geste annulé que ce joueur peut rétablir, ou null. */
		public Entry nextRedo(UUID player, boolean valcombe) {
			for (int i = undone.size() - 1; i >= 0; i--) {
				if (mine(undone.get(i), player, valcombe)) {
					return undone.get(i);
				}
			}
			return null;
		}

		/**
		 * Les derniers gestes de ce joueur (au plus max) qui sont tous de la même pile que le tout dernier, du plus
		 * récent au plus ancien : une pile qui pose ses blocs par petits lots (les structures) les défait d'un coup.
		 */
		public List<Entry> lastRun(UUID player, boolean valcombe, int max, boolean redo) {
			List<Entry> from = redo ? undone : done;
			List<Entry> out = new ArrayList<>();
			for (int i = from.size() - 1; i >= 0 && out.size() < max; i--) {
				Entry e = from.get(i);
				if (!mine(e, player, valcombe)) {
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
				undoing.merge(e.stack(), 1, Integer::sum); // la pile le défait maintenant, ou à la fin du chantier en cours
			}
		}

		/** Ce geste va être refait par le « rétablir » commun : sa pile va le rendre, ce n'est pas un geste nouveau. */
		public void redoing(Entry e) {
			if (e.stack() != Stack.WORLDEDIT) {
				undone.remove(e);
				expected.merge(e.stack(), 1, Integer::sum);
			}
			// Un geste WorldEdit reste dans la liste à rétablir : syncWorldEdit l'en retire quand la session le rend.
		}

		/** Le rétablir attendu n'a pas eu lieu (la pile a refusé, ou n'avait plus rien) : on ne l'attend plus. */
		public void redoFailed(Entry e) {
			undone.remove(e);
			expected.computeIfPresent(e.stack(), (s, n) -> n > 1 ? n - 1 : null);
		}

		public int undoable(UUID player, boolean valcombe) {
			int n = 0;
			for (Entry e : done) {
				if (mine(e, player, valcombe)) {
					n++;
				}
			}
			return n;
		}

		public int redoable(UUID player, boolean valcombe) {
			int n = 0;
			for (Entry e : undone) {
				if (mine(e, player, valcombe)) {
					n++;
				}
			}
			return n;
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
		}
	}

	private final Journal journal = new Journal();
	private final WorldEditLink worldEdit;
	private final McHost host;
	private final MapEditor editor;
	private MinecraftServer server;

	SharedUndo(WorldEditLink worldEdit, McHost host, MapEditor editor) {
		this.worldEdit = worldEdit;
		this.host = host;
		this.editor = editor;
	}

	void setServer(MinecraftServer server) {
		this.server = server;
		journal.clear();
	}

	/** Recale le journal sur les trois piles (à chaque tick, et avant chaque « annuler »). */
	void sync() {
		if (server == null) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			journal.syncWorldEdit(p.getUUID(), worldEdit.history(p));
		}
		journal.syncCount(Stack.STRUCTURES, editor.undoable());
		// Les repères : ceux du village de la partie, et ceux des ateliers carte (chacun défait dans son monde).
		MapWorkshop atelier = ValcombeMod.mapWorkshop();
		journal.syncCount(Stack.MARKERS, host.markerUndoDepth() + (atelier == null ? 0 : atelier.markerUndoDepth()));
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
		return server != null && (inMapWorkshop(p) || operator(p) && (host.isGameLevel(p.level()) || editor.worksIn(p.level())));
	}

	/**
	 * Refuse de défaire (ou refaire) un geste de Valcombe hors du monde où sa pile travaille : un repère se défait dans
	 * le monde de la partie, une pose de structure là où travaille l'éditeur. Sans cela, un opérateur dans un atelier
	 * retoucherait les repères de la partie à distance, ou l'éditeur oublierait son historique en changeant de monde.
	 */
	private void checkPlace(ServerPlayer p, Stack stack) {
		if (stack == Stack.MARKERS && !host.isGameLevel(p.level()) && !inMapWorkshop(p)) {
			throw new GameException("les repères se défont là où ils ont été posés : le monde de la partie ou l'atelier de la carte "
				+ "(Menu J › Mondes).");
		}
		if (stack == Stack.MARKERS && inMapWorkshop(p) && ValcombeMod.mapWorkshop().markerUndoDepth(p.level()) == 0) {
			throw new GameException("les derniers repères modifiés sont ceux d'une autre carte : ils se défont dans son monde.");
		}
		if (stack == Stack.MARKERS && host.isGameLevel(p.level()) && host.markerUndoDepth() == 0) {
			throw new GameException("les derniers repères modifiés sont ceux d'un atelier carte : ils se défont dans son monde.");
		}
		if (stack == Stack.STRUCTURES && !editor.worksIn(p.level())) {
			throw new GameException("les structures se défont dans le monde où elles ont été posées.");
		}
	}

	public int undoable(ServerPlayer p) {
		return journal.undoable(p.getUUID(), valcombeHere(p));
	}

	public int redoable(ServerPlayer p) {
		return journal.redoable(p.getUUID(), valcombeHere(p));
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
			List<Journal.Entry> run = journal.lastRun(p.getUUID(), valcombeHere(p), left, false);
			if (run.isEmpty()) {
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
					case MARKERS -> inMapWorkshop(p) ? ValcombeMod.mapWorkshop().undoMarker(p) : host.undoMarker();
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
			List<Journal.Entry> run = journal.lastRun(p.getUUID(), valcombeHere(p), left, true);
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
