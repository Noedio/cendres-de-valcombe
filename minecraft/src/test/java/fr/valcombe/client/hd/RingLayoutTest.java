package fr.valcombe.client.hd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Le cercle des sièges (RingLayout) : jetons et plaques de noms dans la zone, sans aucun recouvrement, de 5 à 20
 * joueurs, avec des noms ordinaires, des noms de 16 caractères (le maximum de Minecraft) et les plus larges possibles,
 * dans les zones réelles des écrans (menu J, pupitre, désignation d'un joueur, vote, grimoire) et aux échelles
 * d'interface 1 à 4. Les noms sont mesurés avec la vraie police des noms (Alegreya Sans, taille 9), comme le jeu les
 * mesure : à chaque échelle, la taille déclarée la plus proche (FontLadder) ramenée à la taille voulue.
 */
class RingLayoutTest {
	private static final String FONT = "assets/valcombe/font/hd/alegreya_sans.ttf";
	private static final double SIZE = 9;
	private static final double LABEL_H = SIZE * FontLadder.LINE;
	private static Font font;
	private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

	/** Noms ordinaires, de longueurs variées (dont un de 16 caractères). */
	private static final String[] NAMES = {"Aubépine", "Bastien", "Clémence", "Dorian", "Éloïse", "Faustin",
		"MaximilienDeTour", "Gaspard", "Héloïse", "Isidore", "Joséphine", "Kilian", "Léontine", "Martin", "Noémie",
		"Octave", "Pernelle", "Quentin", "Rosalie", "Sidonie"};
	/** Noms de 16 caractères. */
	private static final String[] LONG = {"MaximilienDeTour", "xX_Dragon_Xx1234", "Bartholomew_1987", "AuroreDesCendres",
		"Le_Vieux_Grimoir", "SuperMinecrafter", "Clementine_Lucas", "DorianLeMagnific", "Eloise_Valcombe1", "FaustinDuMoulin1",
		"GaspardBellerose", "HortenseDeLaTour", "IsidoreLeSombre1", "JosephineDuPuits", "LeandreAuxBois12", "MorganeLaBrumeuse",
		"NathanaelDuBourg", "Quentin_Lejeune1", "Rosalie_Fontaine", "SidonieLaRousse1"};
	/** Les plus larges noms possibles. */
	private static final String[] WIDE = {"WWWWWWWWWWWWWWWW", "MMMMMMMMMMMMMMMM", "mmmmmmmmmmmmmmmm", "WMWMWMWMWMWMWMWM"};

	/** Zones des cercles dans une interface de 427 x 240 (unités), comme les écrans les calculent. */
	private record Zone(String name, double x, double y, double w, double h, int max, int min, boolean inside, double wide) {
		RingLayout.Spec spec(RingLayout.Measure m) {
			RingLayout.Spec s = new RingLayout.Spec(x, y, w, h, LABEL_H, m).tokens(max, min).aspect(wide, 1.25);
			return inside ? s : s.outsideOnly();
		}
	}

	private static final Zone[] ZONES = {
		new Zone("menu J", 172, 30, 237, 106, 22, 10, true, 2.6),
		new Zone("pupitre", 20, 82, 227, 130, 26, 14, true, 1.8),
		new Zone("pupitre avec note", 20, 95, 227, 117, 26, 14, true, 1.8),
		new Zone("désigner un joueur", 61, 26, 304, 178, 26, 14, true, 1.8),
		new Zone("vote", 71, 40, 284, 150, 26, 14, false, 1.8),
		new Zone("choix de nuit", 71, 26, 284, 160, 26, 14, false, 1.8),
		new Zone("grimoire du Mouchard", 71, 26, 284, 160, 26, 14, true, 1.8),
	};

	@BeforeAll
	static void loadFont() throws Exception {
		System.setProperty("java.awt.headless", "true");
		Path file = Path.of("src/client/resources", FONT);
		try (InputStream in = Files.exists(file) ? Files.newInputStream(file) : RingLayoutTest.class.getResourceAsStream("/" + FONT)) {
			font = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(100f);
		}
	}

	/** Largeur en unités à l'échelle donnée : la police déclarée la plus proche, ramenée à la taille voulue. */
	private static RingLayout.Measure measure(int scale) {
		Map<String, Double> cache = new java.util.concurrent.ConcurrentHashMap<>();
		float pixels = (float) (SIZE * scale);
		int declared = FontLadder.pick(FontLadder.Face.TEXTE, pixels);
		return text -> cache.computeIfAbsent(text, t -> {
			double atDeclared = Math.ceil(font.getStringBounds(t, FRC).getWidth() / 100.0 * declared);
			return Math.ceil(atDeclared * pixels / declared) / scale;
		});
	}

	private static List<String> names(String[] set, int n) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(set[i % set.length]);
		}
		return out;
	}

	/** Tout ce qui doit être vrai d'une mise en page, quelle qu'elle soit. */
	private static void checkLayout(String where, RingLayout.Result r, Zone z, List<String> names, RingLayout.Measure m) {
		List<RingLayout.Seat> seats = r.seats();
		assertEquals(names.size(), seats.size(), where + " : un siège par joueur");
		RingLayout.Box zone = new RingLayout.Box(z.x(), z.y(), z.w(), z.h());
		double half = r.size() / 2.0;
		assertTrue(r.size() >= z.min() && r.size() <= z.max(), where + " : taille de jeton " + r.size());
		if (!z.inside()) {
			assertNotEquals(RingLayout.Labels.ALTERNATE_INSIDE, r.labels(), where + " : noms dedans alors que le centre est pris");
		}
		for (int i = 0; i < seats.size(); i++) {
			RingLayout.Seat s = seats.get(i);
			String at = where + ", siège " + i + " (" + s.name() + ")";
			assertEquals(names.get(i), s.name(), at + " : ordre des sièges");
			assertTrue(new RingLayout.Box(s.cx() - half, s.cy() - half, r.size(), r.size()).inside(zone), at + " : jeton hors de la zone");
			for (int j = i + 1; j < seats.size(); j++) {
				RingLayout.Seat o = seats.get(j);
				assertTrue(Math.hypot(s.cx() - o.cx(), s.cy() - o.cy()) >= r.size() + 2, at + " : jeton qui touche celui du siège " + j);
			}
			if (s.plate() == null) {
				assertEquals("", s.shown(), at + " : nom caché sans plaque");
				continue;
			}
			RingLayout.Box p = s.plate();
			assertTrue(p.inside(zone), at + " : plaque hors de la zone " + p);
			assertTrue(s.shown().equals(s.name()) || s.shown().endsWith("…")
				&& s.name().startsWith(s.shown().substring(0, s.shown().length() - 1).stripTrailing()), at + " : « " + s.shown() + " »");
			assertTrue(m.width(s.shown()) + 6 <= p.w() + 1e-6, at + " : le texte déborde de sa plaque");
			assertTrue(p.distanceTo(s.cx(), s.cy()) >= half, at + " : la plaque recouvre son jeton");
			for (int j = 0; j < seats.size(); j++) {
				if (j == i) {
					continue;
				}
				RingLayout.Seat o = seats.get(j);
				assertTrue(p.distanceTo(o.cx(), o.cy()) >= half + 0.99, at + " : plaque sur le jeton du siège " + j);
				if (o.plate() != null) {
					RingLayout.Box q = o.plate();
					boolean overlap = p.x() < q.right() && q.x() < p.right() && p.y() < q.bottom() && q.y() < p.bottom();
					assertTrue(!overlap, at + " : plaque sur celle du siège " + j + " (" + p + " / " + q + ")");
				}
			}
		}
	}

	@Test
	void siegesEgalementEspacesLeLongDuBord() {
		for (int n = 5; n <= 20; n++) {
			double[] a = RingLayout.spread(n, 90, 45);
			assertEquals(n, a.length);
			assertEquals(-Math.PI / 2, a[0], 1e-9, "le premier siège est en haut");
			double min = Double.MAX_VALUE;
			double max = 0;
			for (int i = 0; i < n; i++) {
				double end = i + 1 < n ? a[i + 1] : a[0] + 2 * Math.PI;
				assertTrue(end > a[i], "sens horaire, sans retour en arrière");
				double len = 0;
				for (int k = 0; k < 400; k++) {
					double t0 = a[i] + (end - a[i]) * k / 400;
					double t1 = a[i] + (end - a[i]) * (k + 1) / 400;
					len += Math.hypot(90 * (Math.cos(t1) - Math.cos(t0)), 45 * (Math.sin(t1) - Math.sin(t0)));
				}
				min = Math.min(min, len);
				max = Math.max(max, len);
			}
			assertTrue(max / min < 1.02, n + " sièges : arcs de " + min + " à " + max);
			assertTrue(90 * Math.cos(a[n / 4]) > 0, "le deuxième quart est à droite (sens horaire à l'écran)");
		}
	}

	/** Un cas de la grille : zone, échelle, jeu de noms, nombre de joueurs. */
	private record Case(Zone zone, int scale, String[] set, int n) {
	}

	@Test
	void aucunRecouvrementNiDebordementDe5A20Joueurs() {
		List<Case> cases = new ArrayList<>();
		for (Zone z : ZONES) {
			for (int n = 5; n <= 20; n++) {
				// Toutes les tailles de 5 à 20 à l'échelle 2 ; un pas de deux aux autres échelles ; les noms les plus
				// larges possibles à l'échelle 2.
				for (int scale = 1; scale <= 4; scale++) {
					if (scale == 2 || n % 2 == 1 || n == 20) {
						cases.add(new Case(z, scale, NAMES, n));
						cases.add(new Case(z, scale, LONG, n));
					}
				}
				cases.add(new Case(z, 2, WIDE, n));
			}
		}
		Map<Integer, RingLayout.Measure> measures = new HashMap<>();
		for (int s = 1; s <= 4; s++) {
			measures.put(s, measure(s));
		}
		// Les cas sont indépendants : en parallèle, la grille reste rapide.
		cases.parallelStream().forEach(c -> {
			RingLayout.Measure m = measures.get(c.scale());
			List<String> names = names(c.set(), c.n());
			RingLayout.Result r = RingLayout.layout(names, c.zone().spec(m));
			checkLayout(c.zone().name() + ", échelle " + c.scale() + ", " + c.n() + " joueurs (" + c.set()[0] + "...)", r, c.zone(),
				names, m);
		});
	}

	@Test
	void lesNomsOrdinairesSeLisent() {
		RingLayout.Measure m = measure(2);
		for (Zone z : ZONES) {
			// Jusqu'à 8 joueurs, tous les noms ordinaires se lisent en entier ; à 13, presque tous ; à 20, aucun n'est caché.
			for (int n : new int[] {5, 8}) {
				RingLayout.Result r = RingLayout.layout(names(NAMES, n), z.spec(m));
				assertTrue(r.shownRatio() > 0.9, z.name() + ", " + n + " joueurs : " + Math.round(100 * r.shownRatio()) + " % des lettres");
				assertEquals(0, r.hidden(), z.name() + ", " + n + " joueurs : noms cachés");
			}
			RingLayout.Result r13 = RingLayout.layout(names(NAMES, 13), z.spec(m));
			assertTrue(r13.shownRatio() > 0.8, z.name() + ", 13 joueurs : " + Math.round(100 * r13.shownRatio()) + " % des lettres");
			assertEquals(0, r13.hidden(), z.name() + ", 13 joueurs : noms cachés");
			RingLayout.Result r20 = RingLayout.layout(names(NAMES, 20), z.spec(m));
			assertTrue(r20.shownRatio() > 0.6, z.name() + ", 20 joueurs : " + Math.round(100 * r20.shownRatio()) + " % des lettres");
		}
	}

	@Test
	void lesJetonsGardentUneTailleLisible() {
		RingLayout.Measure m = measure(2);
		Zone pupitre = ZONES[1];
		assertTrue(RingLayout.layout(names(NAMES, 5), pupitre.spec(m)).size() >= 24, "5 joueurs : grands jetons");
		assertTrue(RingLayout.layout(names(NAMES, 13), pupitre.spec(m)).size() >= 18, "13 joueurs : jetons d'au moins 18");
		assertTrue(RingLayout.layout(names(NAMES, 20), pupitre.spec(m)).size() >= 14, "20 joueurs : jetons d'au moins 14");
		// Plus de place (interface de 640 x 360) : jetons au moins aussi grands, autant de lettres ou plus.
		Zone large = new Zone("pupitre large", 20, 90, 330, 220, 26, 14, true, 1.8);
		RingLayout.Result small = RingLayout.layout(names(LONG, 13), pupitre.spec(m));
		RingLayout.Result big = RingLayout.layout(names(LONG, 13), large.spec(m));
		assertTrue(big.shownRatio() >= small.shownRatio(), "plus de place, plus de lettres");
	}

	@Test
	void memeEntreeMemeMiseEnPage() {
		RingLayout.Measure m = measure(3);
		List<String> names = names(LONG, 17);
		assertEquals(RingLayout.layout(names, ZONES[0].spec(m)), RingLayout.layout(names, ZONES[0].spec(m)));
	}

	@Test
	void casLimites() {
		RingLayout.Measure m = measure(2);
		assertTrue(RingLayout.layout(List.of(), ZONES[1].spec(m)).seats().isEmpty(), "aucun joueur");
		// Un nom vide (siège sans joueur) n'a pas de plaque ; les noms de moins de quatre lettres ne se coupent pas.
		RingLayout.Result r = RingLayout.layout(List.of("", "Bob", "Al", "Ève", "Zoé"), ZONES[1].spec(m));
		assertEquals(null, r.seats().get(0).plate());
		for (int i = 1; i < 5; i++) {
			assertEquals(r.seats().get(i).name(), r.seats().get(i).shown());
		}
		// Une zone minuscule : les plus petits jetons, sans nom, mais jamais hors de la zone ni l'un sur l'autre.
		Zone tiny = new Zone("minuscule", 0, 0, 90, 60, 26, 14, true, 1.8);
		List<String> names = names(LONG, 8);
		checkLayout("zone minuscule", RingLayout.layout(names, tiny.spec(m)), tiny, names, m);
	}
}
