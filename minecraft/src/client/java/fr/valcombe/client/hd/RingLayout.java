package fr.valcombe.client.hd;

import java.util.ArrayList;
import java.util.List;

/**
 * Mise en page d'un cercle de sièges : les jetons sur une ellipse qui épouse une zone, et le nom de chaque joueur
 * sur une plaque qui ne touche ni une autre plaque, ni un autre jeton, ni le bord de la zone.
 *
 * <p>Pour chaque taille de jeton (de la plus grande à la plus petite) et chaque façon de poser les noms, le calcul :
 * <ol>
 * <li>pose les sièges également espacés le long du bord de l'ellipse (spread), le premier en haut ;</li>
 * <li>pose chaque plaque le long de la normale à l'ellipse, collée à son jeton, vers l'extérieur (ou, une fois sur
 * deux, vers l'intérieur, ou un rang plus loin) ;</li>
 * <li>rétrécit et recentre l'ellipse jusqu'à ce que jetons et plaques tiennent dans la zone ; si les noms entiers ne
 * laissent plus assez de place aux jetons, il les raccourcit tous à une même largeur ;</li>
 * <li>raccourcit (« … ») la plus large de deux plaques qui se touchent, ou une plaque qui touche un autre jeton, un
 * caractère à la fois, puis rend des caractères à celles qui ont de nouveau la place. En dernier recours seulement,
 * un nom est caché (il reste dans l'infobulle du jeton) : rien ne se chevauche jamais.</li>
 * </ol>
 * La meilleure mise en page est celle qui montre le plus de lettres, puis les plus grands jetons, puis les noms à
 * l'extérieur. Calcul pur, en unités d'interface (fractionnaires pour les plaques), sans Minecraft : la largeur des
 * textes est donnée par la fonction de mesure (vérifié par RingLayoutTest).
 */
public final class RingLayout {
	/** Points de la courbe pour répartir les sièges à longueur d'arc égale. */
	private static final int ARC_STEPS = 256;
	/** Répartitions déjà calculées pendant une mise en page (nombre de sièges et rapport des rayons). */
	private static final ThreadLocal<java.util.Map<Long, double[]>> SPREADS = ThreadLocal.withInitial(java.util.HashMap::new);
	/** Plus petit nombre de caractères gardés devant « … » (un nom plus court reste entier). */
	private static final int MIN_CHARS = 3;
	private static final String ELLIPSIS = "…";

	private RingLayout() {
	}

	/** Un rectangle en unités d'interface. */
	public record Box(double x, double y, double w, double h) {
		public double right() {
			return x + w;
		}

		public double bottom() {
			return y + h;
		}

		/** Les deux rectangles sont-ils à moins de gap l'un de l'autre ? */
		public boolean near(Box o, double gap) {
			return x < o.right() + gap && o.x < right() + gap && y < o.bottom() + gap && o.y < bottom() + gap;
		}

		public boolean contains(double px, double py) {
			return px >= x && px < right() && py >= y && py < bottom();
		}

		/** Distance d'un point au rectangle (0 dedans). */
		public double distanceTo(double px, double py) {
			double dx = Math.max(Math.max(x - px, 0), px - right());
			double dy = Math.max(Math.max(y - py, 0), py - bottom());
			return Math.hypot(dx, dy);
		}

		/** Dans la zone, à un centième d'unité près. */
		public boolean inside(Box zone) {
			double e = 0.01;
			return x >= zone.x - e && y >= zone.y - e && right() <= zone.right() + e && bottom() <= zone.bottom() + e;
		}
	}

	/**
	 * Un siège : centre du jeton (unités entières), nom entier, nom montré (raccourci par « … », ou vide s'il est
	 * caché), plaque du nom (null s'il est caché) et angle sur l'ellipse (0 à droite, sens horaire à l'écran).
	 */
	public record Seat(int cx, int cy, String name, String shown, Box plate, double angle) {
		/** Le nom montré n'est pas le nom entier (raccourci ou caché) : l'infobulle le donne en entier. */
		public boolean cut() {
			return !shown.equals(name);
		}
	}

	/** Façon de poser les noms. */
	public enum Labels {
		/** Tous vers l'extérieur. */
		OUTSIDE(0),
		/** Un sur deux vers l'intérieur du cercle (les voisins du haut et du bas ne se disputent plus la place). */
		ALTERNATE_INSIDE(3),
		/** Un sur deux un rang plus loin vers l'extérieur. */
		ALTERNATE_FAR(4);

		/** Préférence (retirée du score) : à place égale, les noms vont dehors. */
		final double penalty;

		Labels(double penalty) {
			this.penalty = penalty;
		}
	}

	/** Le cercle posé : taille des jetons, ellipse (centre et rayons, unités) et sièges dans l'ordre. */
	public record Result(int size, double cx, double cy, double rx, double ry, Labels labels, List<Seat> seats) {
		/** Part des lettres des noms montrée (1 : tous les noms en entier). */
		public double shownRatio() {
			int all = 0;
			int shown = 0;
			for (Seat s : seats) {
				all += s.name().length();
				shown += s.cut() ? Math.max(0, s.shown().length() - 1) : s.name().length();
			}
			return all == 0 ? 1 : shown / (double) all;
		}

		/** Nombre de noms cachés. */
		public long hidden() {
			return seats.stream().filter(s -> s.plate() == null && !s.name().isEmpty()).count();
		}
	}

	/** Largeur d'un texte, en unités d'interface. */
	@FunctionalInterface
	public interface Measure {
		double width(String text);
	}

	/** Ce que l'on demande au cercle. */
	public static final class Spec {
		final Box zone;
		final Measure measure;
		final double labelHeight;
		int sizeMax = 26;
		int sizeMin = 14;
		/** Marge du texte dans sa plaque, de chaque côté. */
		double pad = 3;
		/** Écart entre un jeton et sa plaque. */
		double labelGap = 1.5;
		/** Écart minimal entre deux plaques. */
		double plateGap = 1;
		/** Écart minimal entre une plaque et un autre jeton (les marqueurs dépassent du disque d'une unité et demie). */
		double tokenClear = 2;
		/** Noms vers l'intérieur permis (le centre du cercle ne porte rien d'autre). */
		boolean inside = true;
		/** Rapport largeur / hauteur de l'ellipse : un ovale franc, qui garde l'allure d'une table. */
		double maxWide = 1.8;
		double maxTall = 1.25;

		/**
		 * @param x, y, w, h  zone du cercle, noms compris (unités)
		 * @param labelHeight hauteur d'une plaque de nom (unités)
		 * @param measure     largeur d'un texte de nom (unités)
		 */
		public Spec(double x, double y, double w, double h, double labelHeight, Measure measure) {
			this.zone = new Box(x, y, w, h);
			this.labelHeight = labelHeight;
			this.measure = measure;
		}

		/** Tailles de jeton essayées (unités), de max à min par pas de 2. */
		public Spec tokens(int max, int min) {
			this.sizeMax = max;
			this.sizeMin = Math.min(max, min);
			return this;
		}

		/** Rapport largeur / hauteur permis à l'ellipse, dans un sens et dans l'autre. */
		public Spec aspect(double wide, double tall) {
			this.maxWide = wide;
			this.maxTall = tall;
			return this;
		}

		/** Interdit les noms vers l'intérieur (le centre du cercle porte un texte, une aiguille...). */
		public Spec outsideOnly() {
			this.inside = false;
			return this;
		}

		public Box zone() {
			return zone;
		}
	}

	// ------------------------------------------------------------------
	// Répartition le long de l'ellipse
	// ------------------------------------------------------------------
	/**
	 * Angles de n sièges espacés également le long du bord de l'ellipse, le premier en haut, dans le sens horaire.
	 * À pas d'angle égal, les sièges se serreraient sur les côtés d'une ellipse plus large que haute : à longueur
	 * d'arc égale, l'écart est le même partout et les jetons peuvent être plus grands.
	 */
	public static double[] spread(int n, double rx, double ry) {
		double[] len = new double[ARC_STEPS + 1];
		double lx = 0;
		double ly = -ry;
		for (int k = 1; k <= ARC_STEPS; k++) {
			double a = -Math.PI / 2 + 2 * Math.PI * k / ARC_STEPS;
			double ex = rx * Math.cos(a);
			double ey = ry * Math.sin(a);
			len[k] = len[k - 1] + Math.hypot(ex - lx, ey - ly);
			lx = ex;
			ly = ey;
		}
		double[] out = new double[Math.max(0, n)];
		int k = 0;
		for (int i = 0; i < n; i++) {
			double target = len[ARC_STEPS] * i / n;
			while (k < ARC_STEPS - 1 && len[k + 1] < target) {
				k++;
			}
			double span = len[k + 1] - len[k];
			double frac = span <= 0 ? 0 : (target - len[k]) / span;
			out[i] = -Math.PI / 2 + 2 * Math.PI * (k + frac) / ARC_STEPS;
		}
		return out;
	}

	// ------------------------------------------------------------------
	// Noms : variantes de plus en plus courtes
	// ------------------------------------------------------------------
	/** Les formes d'un nom, de la plus courte (MIN_CHARS lettres et « … ») au nom entier, et leurs largeurs. */
	private record Variants(String name, String[] texts, double[] widths) {
		static Variants of(String name, Measure m) {
			List<String> texts = new ArrayList<>();
			List<Double> widths = new ArrayList<>();
			double full = name.isEmpty() ? 0 : m.width(name);
			int len = name.codePointCount(0, name.length());
			double last = -1;
			for (int k = MIN_CHARS; k < len; k++) {
				String head = name.substring(0, name.offsetByCodePoints(0, k)).stripTrailing() + ELLIPSIS;
				double w = m.width(head);
				// Seules les formes plus étroites que le nom entier, et de plus en plus larges, servent.
				if (w < full && w > last) {
					texts.add(head);
					widths.add(w);
					last = w;
				}
			}
			texts.add(name);
			widths.add(full);
			double[] ws = new double[widths.size()];
			for (int i = 0; i < ws.length; i++) {
				ws[i] = widths.get(i);
			}
			return new Variants(name, texts.toArray(new String[0]), ws);
		}

		int full() {
			return texts.length - 1;
		}

		/** La plus longue forme qui tient dans cette largeur (0 au moins). */
		int fitting(double max) {
			for (int i = texts.length - 1; i > 0; i--) {
				if (widths[i] <= max) {
					return i;
				}
			}
			return 0;
		}
	}

	// ------------------------------------------------------------------
	// Une tentative : taille de jeton et façon de poser les noms fixées
	// ------------------------------------------------------------------
	private static final class Attempt {
		final Spec spec;
		final Variants[] names;
		final int n;
		final int size;
		final Labels labels;
		/** Forme montrée de chaque nom (-1 : caché). */
		final int[] level;
		double cx;
		double cy;
		double rx;
		double ry;
		double[] angles;
		final int[] tx;
		final int[] ty;
		final Box[] plates;

		Attempt(Spec spec, Variants[] names, int size, Labels labels) {
			this.spec = spec;
			this.names = names;
			this.n = names.length;
			this.size = size;
			this.labels = labels;
			this.level = new int[n];
			for (int i = 0; i < n; i++) {
				level[i] = names[i].name().isEmpty() ? -1 : names[i].full();
			}
			this.tx = new int[n];
			this.ty = new int[n];
			this.plates = new Box[n];
			this.side = new int[n];
			for (int i = 0; i < n; i++) {
				side[i] = i % 2 == 0 ? OUT : labels == Labels.ALTERNATE_INSIDE ? IN : labels == Labels.ALTERNATE_FAR ? FAR : OUT;
			}
		}

		/** Côté de chaque plaque : dehors, dedans, ou dehors un rang plus loin. */
		static final int OUT = 0;
		static final int IN = 1;
		static final int FAR = 2;
		final int[] side;

		/** Le nom du siège i va-t-il vers l'intérieur ? */
		boolean inward(int i) {
			return side[i] == IN;
		}

		boolean far(int i) {
			return side[i] == FAR;
		}

		/**
		 * Essaie de poser la plaque i (à sa forme en cours) d'un autre côté que le sien, où elle ne touche rien. Vrai si
		 * elle y est restée ; sinon tout est remis comme avant.
		 */
		boolean flip(int i) {
			int was = side[i];
			for (int s : new int[] {OUT, IN, FAR}) {
				if (s == was || s == IN && !spec.inside || level[i] < 0) {
					continue;
				}
				side[i] = s;
				plates[i] = plate(i);
				if (conflict(i) == NONE) {
					return true;
				}
			}
			side[i] = was;
			plates[i] = plate(i);
			return false;
		}

		/** Pose les jetons sur l'ellipse en cours et toutes les plaques. */
		void placeAll() {
			// Les angles ne dépendent que du rapport des rayons : arrondi au millième, il sert d'une tentative à l'autre.
			long ratio = Math.round(ry / Math.max(1e-6, rx) * 4000);
			angles = SPREADS.get().computeIfAbsent(n * 1_000_000L + ratio, k -> spread(n, 1, ratio / 4000.0));
			for (int i = 0; i < n; i++) {
				tx[i] = (int) Math.round(cx + rx * Math.cos(angles[i]));
				ty[i] = (int) Math.round(cy + ry * Math.sin(angles[i]));
			}
			for (int i = 0; i < n; i++) {
				plates[i] = plate(i);
			}
		}

		/**
		 * La plaque du siège i à sa forme en cours : le long de la normale à l'ellipse, aussi près du jeton que
		 * possible sans le toucher (recherche par dichotomie de la distance le long de la normale).
		 */
		Box plate(int i) {
			if (level[i] < 0) {
				return null;
			}
			double w = names[i].widths()[level[i]] + 2 * spec.pad;
			double h = spec.labelHeight;
			double a = angles[i];
			double nx = Math.cos(a) / Math.max(1e-6, rx);
			double ny = Math.sin(a) / Math.max(1e-6, ry);
			double norm = Math.hypot(nx, ny);
			nx /= norm;
			ny /= norm;
			if (inward(i)) {
				nx = -nx;
				ny = -ny;
			}
			double clear = size / 2.0 + spec.labelGap + (far(i) ? h + spec.plateGap : 0);
			double px = tx[i];
			double py = ty[i];
			double lo = 0;
			double hi = clear + w + h;
			if (at(px, py, nx, ny, w, h, lo).distanceTo(px, py) < clear) {
				for (int k = 0; k < 24; k++) {
					double mid = (lo + hi) / 2;
					if (at(px, py, nx, ny, w, h, mid).distanceTo(px, py) >= clear) {
						hi = mid;
					} else {
						lo = mid;
					}
				}
				lo = hi;
			}
			return at(px, py, nx, ny, w, h, lo);
		}

		/** Plaque de w x h poussée de t le long de la normale (nx, ny), décalée de sa demi-taille du même côté. */
		private static Box at(double px, double py, double nx, double ny, double w, double h, double t) {
			double mx = px + nx * t + nx * w / 2;
			double my = py + ny * t + ny * h / 2;
			return new Box(mx - w / 2, my - h / 2, w, h);
		}

		Box token(int i) {
			return new Box(tx[i] - size / 2.0, ty[i] - size / 2.0, size, size);
		}

		/** Garde un ovale franc (on ne fait que réduire un des rayons). */
		void clampAspect() {
			rx = Math.min(rx, ry * spec.maxWide);
			ry = Math.min(ry, rx * spec.maxTall);
		}

		/**
		 * Ellipse la plus grande dont jetons et plaques tiennent dans la zone, recentrée si un côté déborde et
		 * l'autre a de la place. Faux si elle devient trop petite pour porter des jetons.
		 */
		boolean fit() {
			Box z = spec.zone;
			cx = z.x() + z.w() / 2;
			cy = z.y() + z.h() / 2;
			rx = (z.w() - size) / 2;
			ry = (z.h() - size) / 2;
			clampAspect();
			for (int iter = 0; iter < 40; iter++) {
				if (rx < size || ry < size * 0.75) {
					return false;
				}
				placeAll();
				double minX = Double.MAX_VALUE;
				double minY = Double.MAX_VALUE;
				double maxX = -Double.MAX_VALUE;
				double maxY = -Double.MAX_VALUE;
				for (int i = 0; i < n; i++) {
					Box t = token(i);
					minX = Math.min(minX, t.x());
					minY = Math.min(minY, t.y());
					maxX = Math.max(maxX, t.right());
					maxY = Math.max(maxY, t.bottom());
					Box p = plates[i];
					if (p != null) {
						minX = Math.min(minX, p.x());
						minY = Math.min(minY, p.y());
						maxX = Math.max(maxX, p.right());
						maxY = Math.max(maxY, p.bottom());
					}
				}
				double oL = z.x() - minX;
				double oR = maxX - z.right();
				double oT = z.y() - minY;
				double oB = maxY - z.bottom();
				if (oL <= 0.01 && oR <= 0.01 && oT <= 0.01 && oB <= 0.01) {
					return true;
				}
				// Recentrer d'abord : un côté déborde, l'autre a de la place.
				double sx = oL > 0 && oR < 0 ? Math.min(oL, -oR) : oR > 0 && oL < 0 ? -Math.min(oR, -oL) : 0;
				double sy = oT > 0 && oB < 0 ? Math.min(oT, -oB) : oB > 0 && oT < 0 ? -Math.min(oB, -oT) : 0;
				cx += sx;
				cy += sy;
				oL -= sx;
				oR += sx;
				oT -= sy;
				oB += sy;
				double shrinkX = Math.max(0, Math.max(oL, oR));
				double shrinkY = Math.max(0, Math.max(oT, oB));
				rx -= shrinkX > 0 ? shrinkX + 0.25 : 0;
				ry -= shrinkY > 0 ? shrinkY + 0.25 : 0;
				clampAspect();
			}
			return false;
		}

		/** Les jetons ont-ils tous leur place (aucun ne frôle un autre) ? */
		boolean tokensApart() {
			double min = size + Math.max(2, size * 0.15);
			for (int i = 0; i < n; i++) {
				for (int j = i + 1; j < n; j++) {
					if (Math.hypot(tx[i] - tx[j], ty[i] - ty[j]) < min) {
						return false;
					}
				}
			}
			return true;
		}

		/** Pas de conflit. */
		static final int NONE = -1;
		/** La plaque sort de la zone. */
		static final int ZONE = -2;

		/**
		 * Ce que touche la plaque i : NONE, ZONE, j (le jeton du siège j) ou -3 - j (la plaque du siège j).
		 */
		int conflict(int i) {
			Box p = plates[i];
			if (p == null) {
				return NONE;
			}
			if (!p.inside(spec.zone)) {
				return ZONE;
			}
			for (int j = 0; j < n; j++) {
				if (j == i) {
					continue;
				}
				if (p.distanceTo(tx[j], ty[j]) < size / 2.0 + spec.tokenClear) {
					return j;
				}
				Box q = plates[j];
				if (q != null && p.near(q, spec.plateGap)) {
					return -3 - j;
				}
			}
			return NONE;
		}

		/** Le nom i raccourci d'un cran (caché en dernier recours). */
		void shrink(int i) {
			level[i] = Math.max(-1, level[i] - 1);
			plates[i] = plate(i);
		}

		/**
		 * Raccourcit les noms jusqu'à ce qu'aucune plaque n'en touche une autre ni un autre jeton ni le bord : de
		 * deux plaques qui se touchent, la plus large cède ; une plaque contre un jeton ou le bord cède seule. Vrai si
		 * un nom a changé.
		 */
		boolean resolve() {
			boolean changed = false;
			boolean[] victims = new boolean[n];
			// Par passes : toutes les plaques en conflit cèdent d'un cran à la fois (growBack rendra ensuite les lettres
			// prises en trop).
			for (int guard = 0; guard < 200; guard++) {
				java.util.Arrays.fill(victims, false);
				boolean any = false;
				for (int i = 0; i < n; i++) {
					int c = conflict(i);
					if (c == ZONE || c >= 0) {
						victims[i] = true;
						any = true;
					} else if (c <= -3) {
						int j = -3 - c;
						victims[plates[i].w() >= plates[j].w() ? i : j] = true;
						any = true;
					}
				}
				if (!any) {
					return changed;
				}
				for (int i = 0; i < n; i++) {
					// Une plaque qui trouve sa place de l'autre côté de son jeton garde son nom entier.
					if (victims[i] && conflict(i) != NONE && !flip(i)) {
						shrink(i);
					}
				}
				changed = true;
			}
			return changed;
		}

		/**
		 * Rend des caractères aux noms raccourcis qui ont de nouveau la place : un caractère à la fois, à tour de rôle,
		 * les plus coupés d'abord à chaque tour (la place libre se partage au lieu d'aller au premier venu).
		 */
		void growBack() {
			Integer[] order = new Integer[n];
			for (boolean grew = true; grew;) {
				grew = false;
				for (int i = 0; i < n; i++) {
					order[i] = i;
				}
				java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(this::shownPart));
				for (int i : order) {
					if (level[i] >= names[i].full() || names[i].name().isEmpty()) {
						continue;
					}
					level[i]++;
					plates[i] = plate(i);
					if (conflict(i) == NONE || flip(i)) {
						grew = true;
					} else {
						level[i]--;
						plates[i] = plate(i);
					}
				}
			}
		}

		/** Part du nom i montrée (0 : caché, 1 : entier). */
		private double shownPart(int i) {
			Variants v = names[i];
			return level[i] < 0 ? 0 : v.widths()[level[i]] / Math.max(1e-6, v.widths()[v.full()]);
		}

		/** Tout tient : jetons écartés et dans la zone, aucune plaque en conflit. */
		boolean valid() {
			if (!tokensApart()) {
				return false;
			}
			for (int i = 0; i < n; i++) {
				if (!token(i).inside(spec.zone) || conflict(i) != NONE) {
					return false;
				}
			}
			return true;
		}

		/** Raccourcit d'emblée tous les noms à cette largeur (les longs noms des côtés ne volent plus la place du cercle). */
		void cap(double width) {
			for (int i = 0; i < n; i++) {
				if (level[i] >= 0) {
					level[i] = Math.min(level[i], names[i].fitting(width));
				}
			}
		}

		/** Déroulé complet ; faux si les jetons de cette taille ne tiennent pas. */
		boolean run() {
			// 1. L'ellipse avec les noms (entiers, ou à la largeur imposée) ; si les jetons n'y ont plus leur place, tous
			// les noms sont raccourcis à une même largeur, de plus en plus petite.
			double cap = Double.MAX_VALUE;
			double widest = 0;
			for (int i = 0; i < n; i++) {
				if (level[i] >= 0) {
					widest = Math.max(widest, names[i].widths()[level[i]]);
				}
			}
			while (!(fit() && tokensApart())) {
				cap = Math.min(cap, widest) * 0.88;
				boolean shorter = false;
				for (int i = 0; i < n; i++) {
					if (level[i] < 0) {
						continue;
					}
					int l = names[i].fitting(cap);
					if (l < level[i]) {
						level[i] = l;
						shorter = true;
					}
				}
				if (!shorter) {
					// Même les noms les plus courts prennent la place des jetons : on les cache tous, puis on rendra
					// ceux qui retrouvent de la place.
					boolean any = false;
					for (int i = 0; i < n; i++) {
						any |= level[i] >= 0;
						level[i] = -1;
					}
					if (!any) {
						return false;
					}
				}
			}
			// 2. Les plaques qui se touchent cèdent ; l'ellipse reprend la place libérée ; puis on recommence.
			for (int round = 0; round < 3; round++) {
				if (!resolve()) {
					break;
				}
				double[] keep = {cx, cy, rx, ry};
				if (!(fit() && tokensApart())) {
					cx = keep[0];
					cy = keep[1];
					rx = keep[2];
					ry = keep[3];
					placeAll();
				}
			}
			resolve();
			growBack();
			return valid();
		}

		Result result() {
			List<Seat> seats = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				String shown = level[i] < 0 ? "" : names[i].texts()[level[i]];
				seats.add(new Seat(tx[i], ty[i], names[i].name(), shown, plates[i], angles[i]));
			}
			return new Result(size, cx, cy, rx, ry, labels, List.copyOf(seats));
		}
	}

	/** Poids d'une unité de taille de jeton face à une lettre de nom (score : 100 points pour tous les noms entiers). */
	private static final double SIZE_WEIGHT = 1.5;
	/** Largeurs imposées d'emblée aux noms, en part du plus large : aucune, puis de plus en plus courtes. */
	private static final double[] CAPS = {1, 0.75, 0.55};

	/** Note d'une mise en page : les lettres montrées, puis la taille des jetons, puis les noms dehors. */
	private static double score(Result r) {
		return 100 * r.shownRatio() - 25 * r.hidden() + SIZE_WEIGHT * r.size() - r.labels().penalty;
	}

	/**
	 * Le cercle de ces joueurs (dans l'ordre des sièges) dans la zone demandée. Toujours une mise en page : si même
	 * les plus petits jetons ne tiennent pas, ils sont posés au plus petit sans nom (les noms restent aux infobulles).
	 */
	public static Result layout(List<String> names, Spec spec) {
		SPREADS.get().clear();
		int n = names.size();
		Variants[] vs = new Variants[n];
		for (int i = 0; i < n; i++) {
			vs[i] = Variants.of(names.get(i) == null ? "" : names.get(i), spec.measure);
		}
		if (n == 0) {
			Box z = spec.zone;
			return new Result(spec.sizeMax, z.x() + z.w() / 2, z.y() + z.h() / 2, 0, 0, Labels.OUTSIDE, List.of());
		}
		Result best = null;
		double bestScore = -Double.MAX_VALUE;
		List<Labels> ways = new ArrayList<>(List.of(Labels.OUTSIDE, Labels.ALTERNATE_FAR));
		if (spec.inside && n >= 4) {
			ways.add(1, Labels.ALTERNATE_INSIDE);
		}
		double widest = 0;
		for (Variants v : vs) {
			widest = Math.max(widest, v.widths()[v.full()]);
		}
		for (int size = spec.sizeMax; size >= spec.sizeMin; size -= 2) {
			// Une taille plus petite ne peut pas faire mieux que tous les noms entiers à cette taille.
			if (best != null && bestScore >= 100 + SIZE_WEIGHT * size) {
				break;
			}
			for (Labels way : ways) {
				for (double c : CAPS) {
					Attempt a = new Attempt(spec, vs, size, way);
					if (c < 1) {
						a.cap(widest * c);
					}
					if (a.run()) {
						Result r = a.result();
						double s = score(r);
						if (s > bestScore) {
							best = r;
							bestScore = s;
						}
					}
				}
			}
		}
		if (best == null) {
			best = fallback(spec, vs);
		}
		return best;
	}

	/**
	 * Dernier recours (zone bien trop petite) : des jetons plus petits encore, sans nom, sur la plus grande ellipse
	 * de la zone, jusqu'à ce qu'ils ne se touchent plus.
	 */
	private static Result fallback(Spec spec, Variants[] vs) {
		Attempt last = null;
		for (int size = spec.sizeMin; size >= 4; size -= 2) {
			Attempt a = new Attempt(spec, vs, size, Labels.OUTSIDE);
			java.util.Arrays.fill(a.level, -1);
			if (!a.fit()) {
				Box z = spec.zone;
				a.cx = z.x() + z.w() / 2;
				a.cy = z.y() + z.h() / 2;
				a.rx = Math.max(0, (z.w() - size) / 2);
				a.ry = Math.max(0, (z.h() - size) / 2);
				a.placeAll();
			}
			last = a;
			if (a.tokensApart()) {
				break;
			}
		}
		return last.result();
	}
}
