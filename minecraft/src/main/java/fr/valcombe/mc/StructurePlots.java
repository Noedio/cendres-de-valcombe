package fr.valcombe.mc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Disposition des parcelles de l'atelier structures : un calcul pur (sans Minecraft), vérifié par
 * {@code StructurePlotsTest}.
 * <p>
 * Chaque structure a sa <b>parcelle</b> : un carré centré sur son origine (la colonne où se tiendraient les pieds de
 * celui qui la pose), assez grand pour qu'on la tourne sur place et qu'on l'agrandisse un peu. Les parcelles sont
 * alignées en <b>rangées</b> (bandes est-ouest), une rangée par catégorie (maisons et salles, pour la place, décor,
 * vos structures), dans cet ordre du nord au sud. Toutes les parcelles d'une rangée ont leur centre sur la même ligne ;
 * une allée longe le nord de chaque rangée, avec le pupitre de chaque parcelle.
 * <p>
 * <b>Rien ne bouge jamais</b> : la position de chaque parcelle est enregistrée ; une structure nouvelle prend la
 * première place libre de sa rangée (un trou laissé par une structure supprimée, ou le bout de la rangée), sinon une
 * nouvelle rangée s'ouvre au sud de toutes les autres.
 *
 * <pre>
 *        allée  [P]            [P]          [P]          (P : pupitre, à z0 - 2)
 *  z0 →  ######### ...  ############# ...  #######       (bordure nord de la rangée)
 *        #.......#      #...........#      #.....#
 *        #...o...#      #.....o.....#      #..o..#       (o : origine, sur la ligne du centre de la rangée)
 *        #.......#      #...........#      #.....#
 *        #########      #############      #######
 * </pre>
 */
final class StructurePlots {
	/** Bord ouest de toutes les rangées, et bordure nord de la première. */
	static final int X0 = -12;
	static final int FIRST_Z = 10;
	/** Herbe entre deux parcelles voisines d'une rangée. */
	static final int GAP = 3;
	/** De la bordure sud d'une rangée à la bordure nord de la suivante (l'allée de 4 blocs y passe). */
	static final int ALLEY = 6;
	/** Longueur d'une rangée (de X0 à la bordure est de sa dernière parcelle) avant d'en ouvrir une autre. */
	static final int MAX_ROW = 150;
	/** Marge autour d'une structure, dans sa parcelle ; et le plus petit rayon d'une parcelle. */
	static final int MARGIN = 3;
	static final int MIN_RAYON = 4;
	/** Le plus grand rayon (parcelle de 81 × 81, la plus grande structure du catalogue ne dépassant pas 128 de côté). */
	static final int MAX_RAYON = 40;
	/** Ordre des rangées, du nord au sud ; une catégorie inconnue vient ensuite, par ordre alphabétique. */
	static final List<String> ORDER = List.of("maisons", "place", "decor", "perso");

	private StructurePlots() {
	}

	/** Une rangée : sa catégorie, sa bordure nord (z0) et le rayon de ses plus grandes parcelles. */
	static final class Band {
		String categorie = "";
		int z0;
		int rayon;

		Band() {
		}

		Band(String categorie, int z0, int rayon) {
			this.categorie = categorie;
			this.z0 = z0;
			this.rayon = rayon;
		}

		/** La ligne des centres. */
		int centerZ() {
			return z0 + 1 + rayon;
		}

		/** La bordure sud. */
		int southZ() {
			return z0 + 2 * rayon + 2;
		}
	}

	/** Une parcelle, telle qu'enregistrée avec le monde de l'atelier. */
	static final class Plot {
		/** Identifiant de la structure (ou de celle qui naîtra au premier enregistrement d'une parcelle neuve). */
		String id = "";
		/** Nom d'une parcelle neuve, pas encore enregistrée (les autres prennent celui du catalogue). */
		String nom = "";
		String categorie = "";
		/** Origine : le centre de la parcelle (x, z), sur le sol plat. */
		int x;
		int z;
		/** Demi-côté de l'intérieur : l'intérieur va de x - rayon à x + rayon ; la bordure l'entoure. */
		int rayon;
		/** Profondeur sous le sol plat comprise dans la parcelle, et hauteur au-dessus. */
		int bas;
		int haut;
		/** La bordure nord de sa rangée (l'allée et le pupitre sont juste au nord). */
		int bande;
		/** Parcelle neuve : la structure n'existe pas encore dans le catalogue. */
		boolean brouillon;
		/** Empreinte (StructureStore.signature) de la version posée ou enregistrée ici la dernière fois. */
		String signature = "";
		/**
		 * La case des pieds dans la version posée ou enregistrée ici la dernière fois (ses blocs sont gardés à côté de
		 * la disposition) ; null : la parcelle a été posée vide. C'est la référence des « modifications non enregistrées ».
		 */
		int[] ancre;
		/** La structure a changé ailleurs pendant que la parcelle avait des modifications : rien n'a été écrasé. */
		boolean conflit;

		/** La bordure ouest et la bordure est. */
		int west() {
			return x - rayon - 1;
		}

		int east() {
			return x + rayon + 1;
		}

		/** Le pupitre : dans l'allée, face à l'origine. */
		int lecternZ() {
			return bande - 2;
		}

		/** Ce point (x, z) est-il sur la parcelle, sa bordure ou le bout d'allée devant elle ? */
		boolean covers(int px, int pz) {
			return px >= west() && px <= east() && pz >= bande - 4 && pz <= z + rayon + 1;
		}

		/** Ce point est-il à l'intérieur (hors bordure) ? */
		boolean inside(int px, int pz) {
			return Math.abs(px - x) <= rayon && Math.abs(pz - z) <= rayon;
		}
	}

	/** Toute la disposition : les rangées et les parcelles. */
	static final class Layout {
		int version = 1;
		List<Band> bandes = new ArrayList<>();
		List<Plot> parcelles = new ArrayList<>();

		Plot plot(String id) {
			for (Plot p : parcelles) {
				if (p.id.equals(id)) {
					return p;
				}
			}
			return null;
		}

		/** La parcelle (avec sa bordure et son bout d'allée) où se trouve ce point, ou null. */
		Plot at(int x, int z) {
			Plot best = null;
			for (Plot p : parcelles) {
				if (p.covers(x, z) && (best == null || p.inside(x, z))) {
					best = p;
				}
			}
			return best;
		}

		/** La parcelle dont le pupitre est en (x, z), ou null. */
		Plot lecternAt(int x, int z) {
			for (Plot p : parcelles) {
				if (p.x == x && p.lecternZ() == z) {
					return p;
				}
			}
			return null;
		}

		Band band(int z0) {
			for (Band b : bandes) {
				if (b.z0 == z0) {
					return b;
				}
			}
			return null;
		}

		/** Les parcelles dans l'ordre de lecture : rangée par rangée du nord au sud, d'ouest en est. */
		List<Plot> ordered() {
			List<Plot> out = new ArrayList<>(parcelles);
			out.sort(Comparator.comparingInt((Plot p) -> p.bande).thenComparingInt(p -> p.x));
			return out;
		}
	}

	/** Ce qu'une structure demande : sa catégorie et la taille de sa parcelle. */
	record Need(String id, String categorie, int rayon, int bas, int haut) {
	}

	/**
	 * Le rayon d'une parcelle pour une structure de largeur w (est-ouest) et de profondeur l (nord-sud) dont les pieds
	 * sont dans la case (ax, az) : le plus grand écart entre l'origine et un bord, plus la marge. La parcelle reste
	 * carrée et centrée sur l'origine : la structure y tourne sur place.
	 */
	static int rayonFor(int w, int l, int ax, int az) {
		int reach = Math.max(Math.max(ax, w - 1 - ax), Math.max(az, l - 1 - az));
		return Math.min(MAX_RAYON, Math.max(MIN_RAYON, reach + MARGIN));
	}

	/** Rang d'une catégorie dans l'ordre des rangées. */
	private static int rank(String categorie) {
		int i = ORDER.indexOf(categorie);
		return i >= 0 ? i : ORDER.size();
	}

	/**
	 * Place ces structures sans toucher aux parcelles déjà là. Chaque structure prend la première place libre d'une
	 * rangée de sa catégorie assez large (trou ou bout de rangée) ; celles qui ne tiennent nulle part ouvrent une
	 * rangée neuve au sud de toutes les autres, aussi large que la plus grande d'entre elles. Les parcelles créées sont
	 * ajoutées à la disposition et renvoyées, dans l'ordre des demandes.
	 */
	static List<Plot> allocate(Layout layout, List<Need> needs) {
		Map<String, List<Need>> byCategory = new LinkedHashMap<>();
		List<Need> sorted = new ArrayList<>(needs);
		sorted.sort(Comparator.comparingInt((Need n) -> rank(n.categorie())).thenComparing(n -> rank(n.categorie()) < ORDER.size()
			? "" : n.categorie()));
		for (Need n : sorted) {
			byCategory.computeIfAbsent(n.categorie(), k -> new ArrayList<>()).add(n);
		}
		Map<String, Plot> made = new LinkedHashMap<>();
		for (Map.Entry<String, List<Need>> e : byCategory.entrySet()) {
			List<Need> left = new ArrayList<>();
			for (Need n : e.getValue()) {
				Plot p = tryPlace(layout, n);
				if (p == null) {
					left.add(n);
				} else {
					made.put(n.id(), p);
				}
			}
			while (!left.isEmpty()) {
				int r = left.stream().mapToInt(Need::rayon).max().orElse(MIN_RAYON);
				Band b = new Band(e.getKey(), nextBandZ(layout), clampRayon(r));
				layout.bandes.add(b);
				List<Need> still = new ArrayList<>();
				for (Need n : left) {
					Plot p = placeIn(layout, b, n);
					if (p == null) {
						still.add(n);
					} else {
						made.put(n.id(), p);
					}
				}
				if (still.size() == left.size()) {
					throw new IllegalStateException("Parcelle impossible à placer : " + still.get(0).id());
				}
				left = still;
			}
		}
		List<Plot> out = new ArrayList<>();
		for (Need n : needs) {
			out.add(made.get(n.id()));
		}
		return out;
	}

	private static int clampRayon(int r) {
		return Math.min(MAX_RAYON, Math.max(MIN_RAYON, r));
	}

	/** La bordure nord d'une rangée neuve : au sud de toutes les autres. */
	private static int nextBandZ(Layout layout) {
		int z = FIRST_Z;
		for (Band b : layout.bandes) {
			z = Math.max(z, b.southZ() + 1 + ALLEY);
		}
		return z;
	}

	/** Dans une rangée existante de sa catégorie, la première où elle tient ; null sinon. */
	private static Plot tryPlace(Layout layout, Need n) {
		List<Band> bands = new ArrayList<>(layout.bandes);
		bands.sort(Comparator.comparingInt(b -> b.z0));
		for (Band b : bands) {
			if (b.categorie.equals(n.categorie()) && b.rayon >= n.rayon()) {
				Plot p = placeIn(layout, b, n);
				if (p != null) {
					return p;
				}
			}
		}
		return null;
	}

	/** La place la plus à l'ouest de cette rangée où la parcelle tient sans toucher ses voisines ; null s'il n'y en a pas. */
	static Plot placeIn(Layout layout, Band b, Need n) {
		if (n.rayon() > b.rayon) {
			return null;
		}
		int span = 2 * n.rayon() + 3; // bordures comprises
		List<Plot> row = new ArrayList<>();
		for (Plot p : layout.parcelles) {
			if (p.bande == b.z0) {
				row.add(p);
			}
		}
		row.sort(Comparator.comparingInt(Plot::west));
		List<Integer> candidates = new ArrayList<>();
		candidates.add(X0);
		for (Plot p : row) {
			candidates.add(p.east() + GAP + 1);
		}
		for (int west : candidates) {
			int east = west + span - 1;
			if (east > X0 + MAX_ROW - 1 && !(row.isEmpty() && west == X0)) {
				continue;
			}
			boolean free = true;
			for (Plot p : row) {
				if (west <= p.east() + GAP && east >= p.west() - GAP) {
					free = false;
					break;
				}
			}
			if (free) {
				Plot p = new Plot();
				p.id = n.id();
				p.categorie = n.categorie();
				p.rayon = n.rayon();
				p.bas = n.bas();
				p.haut = n.haut();
				p.x = west + 1 + n.rayon();
				p.z = b.centerZ();
				p.bande = b.z0;
				layout.parcelles.add(p);
				return p;
			}
		}
		return null;
	}

	/** Retire une parcelle de la disposition (sa place se libère pour une autre). */
	static boolean remove(Layout layout, String id) {
		return layout.parcelles.removeIf(p -> p.id.equals(id));
	}
}
