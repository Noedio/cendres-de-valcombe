package fr.valcombe.client.screen.atelier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdButton;
import fr.valcombe.client.hd.HdFonts;
import fr.valcombe.client.hd.HdGlyphs;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.screen.MainScreen;
import fr.valcombe.client.screen.WorldsScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;

/**
 * Une page de l'Atelier en jeu : le cadre commun à toutes ses pages.
 * <ul>
 * <li><b>En haut</b>, le fil d'Ariane (« Atelier › Cartes › Port de Valcombe ») : chaque étape est un lien vers sa page ;
 * à droite, l'avancement de la pose de carte en cours, s'il y en a une.</li>
 * <li><b>Au milieu</b>, la page ({@link #buildPage()}, {@link #drawPage(Hd)}), entre {@link #top()} et {@link #bottom()}.</li>
 * <li><b>En bas</b>, « Retour » (la page d'avant dans le fil), et le retour du serveur à la dernière action : en cours
 * (« Envoi au serveur… »), réussie (coche verte) ou refusée (croix rouge, le texte du refus).</li>
 * </ul>
 * Chaque action passe par {@link #run(String, String)} : une commande {@code /vc}, vérifiée par le serveur comme si
 * elle était tapée, dont la réponse revient dans la vue ({@code atelier.reponse}). L'Atelier est réservé aux
 * opérateurs : sans la vue {@code atelier}, la page dit pourquoi et mène aux mondes (les invités y construisent).
 * <p>
 * Pages : {@link AtelierHome} (accueil, quatre entrées) ; {@link MapsPage} (cartes), {@link MapSheetPage} (fiche d'une
 * carte), {@link NewMapPage} (nouvelle carte, en trois étapes) ; {@link StructuresPage} (catalogue et Construire),
 * {@link StructureSheetPage}, {@link NewStructurePage} ; {@link PartiesPage}, {@link PartySheetPage} ; {@link RolesPage}
 * (scénarios, rôles créés, packs), {@link ScenarioSheetPage}, {@link RoleSheetPage}. Échap défait d'abord ce qui est en
 * cours sur la page ({@link #cancelPending()}), puis ferme l'Atelier.
 */
public abstract class AtelierPage extends HdScreen {
	/** Marge des côtés, en unités. */
	protected static final int SIDE = 12;
	/** Hauteur du fil d'Ariane (le contenu commence en dessous). */
	protected static final int HEAD = 28;
	/** Hauteur du pied de page (« Retour » et retour du serveur). */
	protected static final int FOOT = 30;

	/** Numéro de la dernière réponse du serveur à l'ouverture de l'Atelier : seules les suivantes s'affichent. */
	private static int seenReply = Integer.MAX_VALUE;
	/** Une action envoyée, en attente de sa réponse : le numéro de réponse d'alors, le moment, ce qu'on fait. */
	private static int pendingFrom = -1;
	private static long pendingSince;
	private static String pendingWhat;

	/** Une étape du fil d'Ariane : son nom, et la page où elle mène (null : la page en cours). */
	public record Crumb(String label, Supplier<Screen> go) {
	}

	protected AtelierPage() {
		super("Atelier de Valcombe");
	}

	/** L'Atelier s'ouvre (menu J, commande, raccourci) : on repart de l'accueil, sans les réponses d'avant. */
	public static AtelierHome open() {
		opening();
		return new AtelierHome();
	}

	/** Les réponses du serveur reçues jusqu'ici ne s'afficheront pas (ouverture de l'Atelier). */
	public static void opening() {
		seenReply = replyNumber();
		pendingFrom = -1;
	}

	@Override
	protected int[] panelSize() {
		// Le plus grand des écrans de Valcombe : il se réduit à la fenêtre (BaseScreen), jusqu'à 427 × 240.
		return new int[] {720, 420};
	}

	/** Ce que le serveur envoie pour l'Atelier (opérateurs seulement), ou null. */
	public static JsonObject data() {
		return ClientState.obj(view(), "atelier");
	}

	/** Les ateliers carte ouverts et la bibliothèque, vus du menu « Mondes » (opérateurs). */
	static JsonObject workshops() {
		return ClientState.obj(view(), "ateliersCarte");
	}

	static List<JsonObject> list(JsonObject o, String key) {
		return objects(o, key);
	}

	static List<String> words(JsonObject o, String key) {
		return strings(o, key);
	}

	static String str(JsonObject o, String key) {
		String s = o == null ? null : ClientState.str(o, key);
		return s == null ? "" : s;
	}

	static long lng(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0;
	}

	// ------------------------------------------------------------------
	// Ce que chaque page donne
	// ------------------------------------------------------------------

	/** Le fil d'Ariane, de l'accueil à cette page (la dernière étape est la page elle-même). */
	protected abstract List<Crumb> trail();

	/** Les widgets de la page (appelé seulement pour un opérateur). */
	protected abstract void buildPage();

	/** Le dessin de la page, sous ses widgets (pixels réels). */
	protected void drawPage(Hd hd) {
	}

	/**
	 * Échap annule d'abord ce que la page a en cours (une confirmation, une saisie, une étape d'un parcours) : la page le
	 * défait et renvoie vrai. Sans rien en cours (faux), Échap ferme l'Atelier.
	 */
	protected boolean cancelPending() {
		return false;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (event.input() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE && cancelPending()) {
			rebuildSoon();
			return true;
		}
		return super.keyPressed(event);
	}

	/** Largeur réservée à droite du pied de page pour les boutons de la page, en unités. */
	protected int footerReserve() {
		return 0;
	}

	/** La page où mène « Retour » : l'étape d'avant dans le fil, ou le menu J depuis l'accueil. */
	protected Screen parent() {
		List<Crumb> t = trail();
		return t.size() >= 2 && t.get(t.size() - 2).go() != null ? t.get(t.size() - 2).go().get() : new MainScreen();
	}

	/** Haut et bas de la zone de la page, en unités. */
	protected int top() {
		return py + HEAD;
	}

	protected int bottom() {
		return py + ph - FOOT;
	}

	protected int left() {
		return px + SIDE;
	}

	protected int right() {
		return px + pw - SIDE;
	}

	protected void go(Screen s) {
		minecraft.gui.setScreen(s);
	}

	/**
	 * Lance une commande de l'Atelier ; what (« Copie de la carte ») s'affiche en attendant la réponse du serveur, qui
	 * la remplace dans le pied de page.
	 */
	protected void run(String command, String what) {
		pendingFrom = replyNumber();
		pendingSince = Util.getMillis();
		pendingWhat = what;
		send(command);
	}

	/** Une action attend-elle encore la réponse du serveur ? */
	protected static boolean pending() {
		return pendingFrom >= 0 && replyNumber() <= pendingFrom && Util.getMillis() - pendingSince < 8000;
	}

	private static int replyNumber() {
		return ClientState.num(ClientState.obj(data(), "reponse"), "n", 0);
	}

	/** La dernière réponse du serveur à montrer (reçue depuis l'ouverture de l'Atelier), ou null. */
	public static JsonObject reply() {
		JsonObject r = ClientState.obj(data(), "reponse");
		return r != null && ClientState.num(r, "n", 0) > seenReply ? r : null;
	}

	/** Mesure d'un texte en unités d'interface (pour placer les widgets dans build()). */
	protected static int units(HdStyle style, String text) {
		int scale = Hd.guiScale();
		return (int) Math.ceil(HdFonts.width(style, scale, text) / (double) scale);
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	@Override
	protected final void build() {
		buildTrail();
		button(px + SIDE, py + ph - 24, 74, "Retour", () -> go(parent()));
		if (data() == null) {
			button(px + pw / 2 - 60, top() + 60, 120, "Mondes", () -> go(new WorldsScreen()))
				.tooltip("Les mondes où vous pouvez aller, dont les ateliers où un opérateur vous a invité.");
			return;
		}
		buildPage();
	}

	/** Le fil d'Ariane : un lien par étape d'avant ; la page en cours est écrite (dans drawHd). */
	private void buildTrail() {
		List<Crumb> t = trail();
		int x = px + SIDE;
		int y = py + 9;
		for (int i = 0; i < t.size() - 1; i++) {
			Crumb c = t.get(i);
			int w = units(LinkButton.STYLE, c.label()) + 4;
			addRenderableWidget(new LinkButton(x, y, w, 14, c.label(), () -> go(c.go().get())));
			x += w + 12;
		}
	}

	/** Où commence le nom de la page en cours dans le fil (unités). */
	private int trailEnd() {
		List<Crumb> t = trail();
		int x = px + SIDE;
		for (int i = 0; i < t.size() - 1; i++) {
			x += units(LinkButton.STYLE, t.get(i).label()) + 4 + 12;
		}
		return x;
	}

	// ------------------------------------------------------------------
	// Dessin
	// ------------------------------------------------------------------

	@Override
	protected final void drawHd(Hd hd) {
		drawTrail(hd);
		if (data() == null) {
			int x = hd.px(left() + 30);
			int w = hd.px(right() - left() - 60);
			int y = hd.px(top() + 4);
			hd.text(HdStyle.SECTION, "Réservé aux opérateurs", x, y, Hd.WINE);
			hd.paragraph(HdStyle.TEXTE, "L'Atelier (cartes, structures, parties, rôles et scénarios) est réservé aux opérateurs du "
				+ "serveur. Un opérateur peut vous inviter à construire dans l'un de ses ateliers : vous le trouverez alors dans « Mondes ».",
				x, y + hd.lineHeight(HdStyle.SECTION) + hd.u(4), w, Hd.INK_SOFT, hd.u(48));
		} else {
			drawPage(hd);
		}
		drawFooter(hd);
	}

	private void drawTrail(Hd hd) {
		List<Crumb> t = trail();
		int y = hd.px(py + 9);
		int h = hd.px(14);
		// Les chevrons entre les liens.
		int x = px + SIDE;
		for (int i = 0; i < t.size() - 1; i++) {
			x += units(LinkButton.STYLE, t.get(i).label()) + 4;
			int cx = hd.px(x + 6);
			int cy = y + h / 2;
			int s = hd.u(2.5);
			int th = Math.max(1, hd.u(0.8));
			HdGlyphs.line(hd, cx - s / 2.0, cy - s, cx + s / 2.0, cy, th, Hd.BRONZE);
			HdGlyphs.line(hd, cx + s / 2.0, cy, cx - s / 2.0, cy + s, th, Hd.BRONZE);
			x += 12;
		}
		// La page en cours, puis à droite la pose de carte en cours.
		int progressW = drawProgress(hd);
		int start = hd.px(trailEnd());
		int room = hd.px(right()) - progressW - start - hd.u(progressW > 0 ? 10 : 0);
		String here = t.isEmpty() ? "" : t.get(t.size() - 1).label();
		HdStyle st = HdStyle.SECTION.size(11);
		hd.textMiddle(st, hd.ellipsize(st, here, Math.max(hd.u(20), room)), start, y, h, Hd.WINE);
		hd.separator(hd.px(px + SIDE), hd.px(py + HEAD - 4), hd.px(pw - 2 * SIDE));
	}

	/** L'avancement de la première pose de carte en cours, en haut à droite ; renvoie la largeur prise (pixels réels). */
	private int drawProgress(Hd hd) {
		List<JsonObject> works = objects(data(), "chantiers");
		if (works.isEmpty()) {
			return 0;
		}
		JsonObject w = works.get(0);
		double done = ClientState.dbl(w, "avance", 0);
		int width = hd.u(Math.min(190, pw / 3));
		int x = hd.px(right()) - width;
		int y = hd.px(py + 8);
		HdStyle st = HdStyle.PETIT;
		String label = str(w, "texte") + " · " + Math.round(done * 100) + " %" + (works.size() > 1 ? " (+" + (works.size() - 1) + ")" : "");
		hd.text(st, hd.ellipsize(st, label, width), x, y, Hd.INK_SOFT);
		int by = y + hd.lineHeight(st) + hd.u(1);
		int bh = Math.max(2, hd.u(3));
		hd.fill(x, by, x + width, by + bh, 0x60301E14);
		hd.fill(x, by, x + (int) Math.round(width * Math.max(0, Math.min(1, done))), by + bh, 0xFFD9B45A);
		return width;
	}

	/** Le retour du serveur à la dernière action, à droite de « Retour ». */
	private void drawFooter(Hd hd) {
		pendingTooltip = null; // le fond est dessiné avant les widgets : l'infobulle, elle, passe après eux
		int x = hd.px(px + SIDE + 74 + 10);
		int xe = hd.px(px + pw - SIDE - footerReserve()) - hd.u(footerReserve() > 0 ? 8 : 0);
		int y = hd.px(py + ph - 24);
		int h = hd.px(18);
		int w = xe - x;
		if (w < hd.u(30)) {
			return;
		}
		HdStyle st = HdStyle.PETIT.size(9.5f);
		int icon = hd.u(8);
		if (pending()) {
			// Trois points qui tournent : le serveur travaille.
			long t = Util.getMillis() / 160;
			for (int i = 0; i < 3; i++) {
				int a = (int) ((t + i) % 3) == 0 ? 0xFF : 0x70;
				hd.disc(x + hd.u(2) + i * hd.u(3.5), y + h / 2, Math.max(1, hd.u(1.2)), a << 24 | 0x6E4F22);
			}
			hd.textMiddle(st, hd.ellipsize(st, pendingWhat + "…", w - icon - hd.u(6)), x + icon + hd.u(6), y, h, Hd.INK_SOFT);
			return;
		}
		JsonObject r = reply();
		if (r == null) {
			return;
		}
		boolean error = ClientState.bool(r, "erreur");
		int color = error ? 0xFFA0262E : 0xFF2F6B2A;
		int s = hd.u(7);
		int iy = y + (h - s) / 2;
		if (error) {
			HdGlyphs.cross(hd, x, iy, s, color, 1f);
		} else {
			HdGlyphs.check(hd, x, iy, s, color);
		}
		String text = str(r, "texte");
		List<String> lines = hd.wrap(st, text, w - icon - hd.u(6));
		int lh = hd.lineHeight(st);
		int shown = Math.min(2, lines.size());
		int ty = y + (h - shown * lh) / 2;
		for (int i = 0; i < shown; i++) {
			String line = lines.get(i);
			if (i == shown - 1 && lines.size() > shown) {
				line = hd.ellipsize(st, line + "…", w - icon - hd.u(6));
			}
			hd.text(st, line, x + icon + hd.u(6), ty + i * lh, error ? 0xFF7A1E24 : Hd.INK);
		}
		// Le texte entier au survol, s'il a été raccourci.
		if (lines.size() > shown && hd.hovered(x, y, w, h)) {
			pendingTooltip = text;
		}
	}

	/** Le texte entier d'une réponse raccourcie, montré au-dessus de tout. */
	private String pendingTooltip;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		if (pendingTooltip != null) {
			g.nextStratum();
			try (Hd hd = Hd.begin(g)) {
				hd.tooltip(pendingTooltip, hd.mouseX(), hd.mouseY());
			}
		}
	}

	// ------------------------------------------------------------------
	// Le lien d'une étape du fil d'Ariane
	// ------------------------------------------------------------------

	/** Un lien du fil d'Ariane : texte bronze, souligné au survol et au focus du clavier. */
	static final class LinkButton extends HdButton {
		static final HdStyle STYLE = HdStyle.FORT.size(10.5f);

		LinkButton(int x, int y, int w, int h, String label, Runnable action) {
			super(x, y, w, h, label, action);
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
			try (Hd hd = Hd.begin(g)) {
				int x = hd.px(getX());
				int y = hd.px(getY());
				int h = hd.px(getHeight());
				boolean hot = isHoveredOrFocused();
				int color = hot ? Hd.WINE : Hd.BRONZE_INK;
				int tw = hd.width(STYLE, getMessage().getString());
				hd.textMiddle(STYLE, getMessage().getString(), x + hd.u(2), y, h, color);
				if (hot) {
					int uy = y + (h + hd.lineHeight(STYLE)) / 2 - hd.u(1);
					hd.fill(x + hd.u(2), uy, x + hd.u(2) + tw, uy + Math.max(1, hd.u(0.6)), color);
				}
			}
		}
	}

	/** Trouve la page de l'Atelier ouverte (pour les tests en jeu), ou null. */
	public static AtelierPage current() {
		return Minecraft.getInstance().gui.screen() instanceof AtelierPage p ? p : null;
	}

	/** Les libellés des étapes du fil d'Ariane de cette page (pour les tests en jeu). */
	public List<String> trailLabels() {
		List<String> out = new ArrayList<>();
		for (Crumb c : trail()) {
			out.add(c.label());
		}
		return out;
	}
}
