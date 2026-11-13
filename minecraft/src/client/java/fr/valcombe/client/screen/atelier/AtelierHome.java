package fr.valcombe.client.screen.atelier;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.Vignettes;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdFonts;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.screen.PartiesScreen;

import java.util.Comparator;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/**
 * L'accueil de l'Atelier en jeu : quatre grandes entrées, chacune avec son illustration, ce qu'elle contient en un
 * coup d'œil, et ce qu'on y fait au survol.
 * <ul>
 * <li><b>Cartes</b> : la bibliothèque en vignettes, les fiches, « Nouvelle carte », les ateliers carte ouverts
 * ({@link MapsPage}).</li>
 * <li><b>Structures</b>, <b>Parties</b>, <b>Rôles et scénarios</b> : voir {@link #structures()}, {@link #parties()},
 * {@link #roles()}.</li>
 * </ul>
 * En bas : « Page web » (le lien de l'Atelier web, dans le chat) et « Fermer ».
 */
public class AtelierHome extends AtelierPage {
	public static final String CARTES = "Cartes";
	public static final String STRUCTURES = "Structures";
	public static final String PARTIES = "Parties";
	public static final String ROLES = "Rôles et scénarios";

	@Override
	protected List<Crumb> trail() {
		return List.of(new Crumb("Atelier", null));
	}

	@Override
	protected int footerReserve() {
		return 84 + 6 + 64;
	}

	// ------------------------------------------------------------------
	// Où mène chaque entrée
	// ------------------------------------------------------------------

	/** L'entrée « Structures » : le catalogue en vignettes ({@link StructuresPage}). */
	public static Screen structures() {
		return new StructuresPage();
	}

	/** L'entrée « Parties » : les parties du serveur et leurs cartes ({@link PartiesPage}). */
	public static Screen parties() {
		return new PartiesPage();
	}

	/** L'entrée « Rôles et scénarios » : scénarios, rôles créés, packs ({@link RolesPage}). */
	public static Screen roles() {
		return new RolesPage();
	}

	// ------------------------------------------------------------------
	// Ce que chaque entrée contient, en une ligne
	// ------------------------------------------------------------------

	private static String plural(int n, String one, String many) {
		return n + " " + (n > 1 ? many : one);
	}

	static String mapsLine(JsonObject a) {
		List<JsonObject> maps = list(a, "cartes");
		if (maps.isEmpty()) {
			return "Bibliothèque vide : créez la première";
		}
		long open = maps.stream().filter(m -> !str(m, "atelier").isEmpty()).count();
		return plural(maps.size(), "carte", "cartes") + (open > 0 ? " · " + open + " en atelier" : "");
	}

	static String structuresLine(JsonObject a) {
		List<JsonObject> all = list(a, "structures");
		long mine = all.stream().filter(s -> !ClientState.bool(s, "fournie")).count();
		return plural(all.size(), "structure", "structures") + (mine > 0 ? " · " + mine + " à vous" : "");
	}

	static String partiesLine(JsonObject a) {
		List<JsonObject> all = list(a, "parties");
		long playing = all.stream().filter(p -> ClientState.bool(p, "enCours")).count();
		return all.isEmpty() ? "Aucune partie" : plural(all.size(), "partie", "parties") + (playing > 0 ? " · " + playing + " en cours" : "");
	}

	static String rolesLine(JsonObject a) {
		int roles = list(a, "roles").size();
		int scenarios = list(a, "scenarios").size();
		return plural(roles, "rôle créé", "rôles créés") + " · " + plural(scenarios, "scénario", "scénarios");
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	@Override
	protected void buildPage() {
		JsonObject a = data();
		int x = left();
		int y = top() + 2;
		int w = right() - left();
		int h = bottom() - top() - 6;
		// Quatre de front quand la place est large et basse, sinon deux rangées de deux.
		boolean row = w >= h * 2.3;
		int cols = row ? 4 : 2;
		int rows = row ? 1 : 2;
		int gap = 8;
		int tw = (w - (cols - 1) * gap) / cols;
		int th = (h - (rows - 1) * gap) / rows;
		String[][] tiles = {
			{CARTES, mapsLine(a), "La bibliothèque en vignettes, les fiches des cartes, « Nouvelle carte » et les ateliers carte ouverts."},
			{STRUCTURES, structuresLine(a), "Le catalogue en vignettes (maisons, place, décor, les vôtres), « Nouvelle structure », "
				+ "les gestes de WorldEdit dans les ateliers."},
			{PARTIES, partiesLine(a), "Les parties du serveur et leurs cartes : y aller, leur envoyer une carte, enregistrer la leur, "
				+ "les suspendre ; créer une partie d'essai."},
			{ROLES, rolesLine(a), "Vos scénarios et vos rôles créés, en jetons : choisir pour une partie, essayer, importer un pack."},
		};
		for (int i = 0; i < tiles.length; i++) {
			int tx = x + (i % cols) * (tw + gap);
			int ty = y + (i / cols) * (th + gap);
			int index = i;
			Tile t = new Tile(tx, ty, tw, th, tiles[i][0], tiles[i][1], index, () -> go(switch (index) {
				case 0 -> new MapsPage();
				case 1 -> structures();
				case 2 -> parties();
				default -> roles();
			}));
			t.tooltip(tiles[i][2]);
			addRenderableWidget(t);
		}
		int by = py + ph - 24;
		button(right() - 64, by, 64, "Fermer", this::onClose);
		button(right() - 64 - 6 - 84, by, 84, "Page web", () -> {
			run("vc atelier", "Lien de la page web");
			onClose();
		}).tooltip("Le lien de l'Atelier web, dans le chat : rôles et scénarios s'y écrivent en entier.");
	}

	// ------------------------------------------------------------------
	// Une entrée
	// ------------------------------------------------------------------

	/** Le nom d'une entrée mis en page : son style (taille adaptée) et ses lignes (une ou deux, jamais coupées). */
	public record TitleFit(HdStyle style, List<String> lines) {
	}

	/** Tailles du nom d'une entrée, de la plus grande à la plus petite. */
	private static final float[] TITLE_SIZES = {12, 11, 10.5f, 10, 9.5f, 9};

	/**
	 * Le nom d'une entrée dans cette largeur (pixels réels, à cette échelle), sans jamais le couper : sur une ligne à la
	 * plus grande taille qui tient (jusqu'à 10) ; sinon sur deux lignes, à la plus grande taille où chacune tient ; en
	 * dernier recours, à la plus petite taille, sur autant de lignes qu'il faut. narrow : une entrée étroite commence
	 * à 10.
	 */
	public static TitleFit titleFit(String title, int width, int scale, boolean narrow) {
		float start = narrow ? 10 : 12;
		for (float size : TITLE_SIZES) {
			if (size > start || size < 10) {
				continue;
			}
			HdStyle st = HdStyle.SECTION.size(size);
			if (HdFonts.width(st, scale, title) <= width) {
				return new TitleFit(st, List.of(title));
			}
		}
		for (float size : TITLE_SIZES) {
			if (size > start) {
				continue;
			}
			HdStyle st = HdStyle.SECTION.size(size);
			List<String> lines = HdFonts.wrap(st, scale, title, width);
			if (lines.size() <= 2 && lines.stream().allMatch(l -> HdFonts.width(st, scale, l) <= width)) {
				return new TitleFit(st, lines);
			}
		}
		HdStyle st = HdStyle.SECTION.size(TITLE_SIZES[TITLE_SIZES.length - 1]);
		return new TitleFit(st, HdFonts.wrap(st, scale, title, width));
	}

	/** Une grande entrée de l'accueil : illustration, nom, ce qu'elle contient ; dorée au survol. */
	public static final class Tile extends HdButton {
		private final String line;
		private final int art;

		Tile(int x, int y, int w, int h, String title, String line, int art, Runnable action) {
			super(x, y, w, h, title, action);
			this.line = line;
			this.art = art;
		}

		/** Le nom de l'entrée tel qu'il est dessiné à cette échelle (pour les tests en jeu). */
		public TitleFit titleAt(int scale) {
			int w = getWidth() * scale;
			int pad = 4 * scale;
			return titleFit(getMessage().getString(), w - 2 * pad - 4 * scale, scale, w < 110 * scale);
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
			try (Hd hd = Hd.begin(g)) {
				int x = hd.px(getX());
				int y = hd.px(getY());
				int w = hd.px(getWidth());
				int h = hd.px(getHeight());
				boolean hot = isHoveredOrFocused();
				// Le carton de l'entrée, puis son illustration en haut, son nom et sa ligne en bas.
				hd.inset(x, y, w, h);
				int pad = hd.u(4);
				// Le nom n'est jamais coupé : plus petit, ou sur deux lignes (voir titleFit).
				TitleFit fit = titleFit(getMessage().getString(), w - 2 * pad - hd.u(4), hd.scale(), w < hd.u(110));
				HdStyle titleStyle = fit.style();
				HdStyle lineStyle = HdStyle.PETIT;
				int titleH = hd.lineHeight(titleStyle) * fit.lines().size();
				int textH = titleH + hd.lineHeight(lineStyle) * (w < hd.u(110) ? 2 : 1) + hd.u(8);
				int ax = x + pad;
				int ay = y + pad;
				int aw = w - 2 * pad;
				int ah = h - 2 * pad - textH;
				if (ah > hd.u(10)) {
					hd.clip(ax, ay, ax + aw, ay + ah);
					paint(hd, ax, ay, aw, ah, hot);
					hd.unclip();
					AtelierArt.frameArt(hd, ax, ay, aw, ah);
				}
				int ty = y + h - textH + hd.u(2);
				for (int i = 0; i < fit.lines().size(); i++) {
					hd.text(titleStyle, fit.lines().get(i), x + pad + hd.u(2), ty + i * hd.lineHeight(titleStyle), hot ? Hd.WINE : Hd.INK);
				}
				int ly = ty + titleH + hd.u(1);
				hd.paragraph(lineStyle, line, x + pad + hd.u(2), ly, w - 2 * pad - hd.u(4), Hd.INK_SOFT, y + h - pad - ly);
				if (hot) {
					hd.frame(x, y, w, h, Math.max(1, hd.u(1)), 0xFFD9B45A);
					hd.frame(x - hd.u(1), y - hd.u(1), w + 2 * hd.u(1), h + 2 * hd.u(1), Math.max(1, hd.u(0.75)), 0x60D9B45A);
				}
			}
		}

		private void paint(Hd hd, int x, int y, int w, int h, boolean hot) {
			switch (art) {
				case 0 -> {
					// La plus récente carte de la bibliothèque, sinon un parchemin de carte au trésor.
					JsonObject latest = list(data(), "cartes").stream().max(Comparator.comparingLong(m -> lng(m, "modifieeLe"))).orElse(null);
					AtelierArt.mapScroll(hd, x, y, w, h);
					if (latest != null) {
						int side = Math.min(w, h) - hd.u(8);
						int sx = x + (w - side) / 2;
						int sy = y + (h - side) / 2;
						hd.fill(sx - hd.u(2), sy - hd.u(2), sx + side + hd.u(2), sy + side + hd.u(2), 0xFF4A2E1C);
						Vignettes.draw(hd, "carte:" + str(latest, "id"), str(latest, "vignette"), sx, sy, side, side);
					}
				}
				case 1 -> {
					// Une structure du catalogue vue de trois quarts (la dernière des vôtres, sinon la maison gothique) sur la parcelle peinte.
					AtelierArt.structures(hd, x, y, w, h, hot);
					List<JsonObject> all = list(data(), "structures");
					JsonObject shown = all.stream().filter(s -> !ClientState.bool(s, "fournie")).reduce((a, b) -> b)
						.orElse(all.stream().filter(s -> "maison-gothique".equals(str(s, "id"))).findFirst().orElse(null));
					if (shown != null && Vignettes.ready("structure:" + str(shown, "id"), str(shown, "vignette"))) {
						int side = Math.min(w, h) - hd.u(8);
						int sx = x + (w - side) / 2;
						int sy = y + (h - side) / 2;
						hd.fill(sx - hd.u(2), sy - hd.u(2), sx + side + hd.u(2), sy + side + hd.u(2), 0xFF4A2E1C);
						Vignettes.draw(hd, "structure:" + str(shown, "id"), str(shown, "vignette"), sx, sy, side, side);
					} else if (shown != null) {
						Vignettes.get("structure:" + str(shown, "id"), str(shown, "vignette")); // demandée : elle paraîtra à la place du dessin
					}
				}
				case 2 -> {
					JsonObject g = new JsonObject();
					g.addProperty("id", "atelier-accueil");
					g.addProperty("statut", "en-cours");
					g.addProperty("phase", hot ? "jour" : "nuit");
					g.addProperty("places", 9);
					g.addProperty("joueurs", 6);
					g.addProperty("principale", false);
					PartiesScreen.vignette(hd, g, x, y, w, h);
				}
				default -> AtelierArt.roles(hd, x, y, w, h, hot);
			}
		}
	}
}
