package fr.valcombe.client.hd;

import fr.valcombe.client.ClientConfig;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * Jeton d'un joueur du cercle, en haute résolution : ombre portée, disque du camp, pictogramme du rôle, linceul
 * s'il est mort, anneau doré s'il est choisi, nom sur une plaque d'encre, marqueurs. Même usage que TokenWidget
 * (centre et taille en unités d'interface) ; les images viennent des textures du jeu, à n'importe quelle définition.
 */
public class HdToken extends AbstractButton implements HdTooltipped {
	/**
	 * Nom d'un mort sur sa plaque d'encre : une cendre claire. La cendre des textes sur parchemin (Hd.ASH) ne se
	 * lisait pas sur la plaque sombre (contraste de 1,7 sur parchemin) ; celle-ci atteint 4,5 tout en restant éteinte.
	 */
	static final int DEAD_LABEL = 0xFFB8AEB0;
	/** Décalage (degrés) de chaque marqueur le long du bord, depuis le haut à droite. */
	private static final int[] MARKER_STEPS = {0, 32, -32, 64, -64};

	/** Taille d'un marqueur (unités) sur un jeton de cette taille. */
	static double markerSize(int tokenSize) {
		return Math.max(5.5, Math.min(10, tokenSize * 0.42));
	}

	private final String name;
	private final String roleId;
	private final String team;
	private final boolean alive;
	private final boolean selected;
	private final Runnable action;
	private final List<String> markers = new ArrayList<>();
	private String caption;
	private String tooltip;
	/** Où mettre le nom : 0 dessous, 1 dessus, 2 à gauche, 3 à droite. */
	private int labelSide;
	/** Plaque du nom posée par le cercle (RingLayout), en unités ; null : nom placé par labelSide. */
	private RingLayout.Box plate;
	/** Nom montré sur la plaque posée (raccourci, ou vide s'il est caché) et nom entier. */
	private String plateText;
	private boolean ringPlaced;
	private boolean plateCut;

	public HdToken(int cx, int cy, int size, String name, String roleId, String team, boolean alive, boolean selected,
			Runnable action) {
		super(cx - size / 2, cy - size / 2, size, size, Component.literal(name));
		this.name = name;
		this.roleId = roleId;
		this.team = team;
		this.alive = alive;
		this.selected = selected;
		this.action = action;
	}

	public HdToken marker(String m) {
		markers.add(m);
		return this;
	}

	public HdToken labelSide(int side) {
		this.labelSide = side;
		return this;
	}

	/** Côté du nom selon la place sur le cercle (angle en radians, 0 = à droite, sens horaire à l'écran). */
	public HdToken labelOutward(double angle) {
		double c = Math.cos(angle);
		double sn = Math.sin(angle);
		return labelSide(Math.abs(c) > 0.8 ? (c < 0 ? 2 : 3) : sn < 0 ? 1 : 0);
	}

	/**
	 * Le nom tel que le cercle l'a posé (RingLayout) : sur sa plaque, raccourci par « … » s'il le faut, ou caché.
	 * Survoler ou cliquer la plaque revient à survoler ou cliquer le jeton ; un nom raccourci se lit en entier dans
	 * l'infobulle.
	 */
	public HdToken seat(RingLayout.Seat seat) {
		this.ringPlaced = true;
		this.plate = seat.plate();
		this.plateText = seat.shown();
		this.plateCut = seat.cut();
		return this;
	}

	/** Plaque du nom posée par le cercle, en unités (null : aucune, ou nom placé à l'ancienne). */
	public RingLayout.Box plateBounds() {
		return plate;
	}

	/** Le jeton a-t-il été posé par un cercle (RingLayout) ? */
	public boolean onRing() {
		return ringPlaced;
	}

	/** Le nom montré sous le jeton (celui de la plaque s'il a été posé par le cercle). */
	public String shownName() {
		return ringPlaced ? plateText : name;
	}

	public HdToken caption(String c) {
		this.caption = c;
		return this;
	}

	public HdToken tooltip(String text) {
		this.tooltip = text;
		return this;
	}

	@Override
	public String hdTooltip() {
		// Un nom raccourci ou caché se lit en entier au survol.
		return tooltip != null ? tooltip : plateCut ? name : null;
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return super.isMouseOver(mouseX, mouseY) || isActive() && visible && plate != null && plate.contains(mouseX, mouseY);
	}

	/** Lance l'action du jeton, comme un clic. */
	public void press() {
		if (action != null) {
			action.run();
		}
	}

	@Override
	public void onPress(InputWithModifiers input) {
		press();
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (plate != null && !isHovered) {
			// La plaque du nom fait partie du jeton : la survoler montre l'anneau et l'infobulle.
			isHovered = plate.contains(mouseX, mouseY);
		}
		try (Hd hd = Hd.begin(g)) {
			int x = hd.px(getX());
			int y = hd.px(getY());
			int size = hd.px(getWidth());
			int cx = x + size / 2;
			int cy = y + size / 2;
			boolean hover = action != null && isHoveredOrFocused();
			// ombre portée, un peu plus large que le jeton et décalée vers le bas
			int sh = Math.round(size * 1.4f);
			hd.image(HdSprites.OMBRE_RONDE, cx - sh / 2, cy - sh / 2 + hd.u(1), sh, sh, 0xB0FFFFFF);
			String disc = team == null ? "token_neutral" : team.equals("EVIL") ? "token_evil" : "token_good";
			hd.image(Theme.tex(disc), x, y, size, size, -1);
			if (roleId != null) {
				int is = size * 22 / 32;
				Role r = Role.parse(roleId);
				String icon = r != null ? r.icon : roleId.toLowerCase(Locale.ROOT);
				hd.image(Theme.tex("roles/" + icon), cx - is / 2, cy - is / 2, is, is, -1);
			}
			if (!alive) {
				hd.image(Theme.tex("token_dead"), x, y, size, size, -1);
			}
			if (team != null && ClientConfig.get().daltonien && getWidth() >= 16) {
				// Mode daltonien : un triangle pour le mal, un rond pour le bien, en plus de la couleur.
				int b = hd.u(9);
				int bx = x + size - b;
				hd.disc(bx + b / 2, y + b / 2, b / 2, 0xE01B1214);
				if (team.equals("EVIL")) {
					hd.triangle(bx + b / 2, y + hd.u(2), hd.u(6), hd.u(5), 0xFFFF8080);
				} else {
					hd.disc(bx + b / 2, y + b / 2, hd.u(2.5), 0xFFA0C8FF);
				}
			}
			int m = Math.max(hd.u(1), size / 10);
			if (selected) {
				hd.image(HdSprites.ANNEAU_CHOISI, x - m, y - m, size + 2 * m, size + 2 * m);
			} else if (hover) {
				hd.image(HdSprites.ANNEAU_SURVOL, x - m, y - m, size + 2 * m, size + 2 * m);
			}
			drawLabel(hd, x, y, size, cx, cy);
			// Marqueurs posés sur le bord du jeton, en haut à droite puis de part et d'autre : ils ne dépassent du disque
			// que d'une unité et demie au plus, et ne mordent donc jamais sur la plaque d'un voisin.
			double msU = markerSize(getWidth());
			int ms = hd.u(msU);
			double reach = getWidth() / 2.0 - msU * 0.35;
			for (int i = 0; i < markers.size(); i++) {
				double a = Math.toRadians(-45 + MARKER_STEPS[i % MARKER_STEPS.length]);
				int mx = cx + hd.u(reach * Math.cos(a));
				int my = cy + hd.u(reach * Math.sin(a));
				hd.image(Theme.tex("marker_" + markers.get(i)), mx - ms / 2, my - ms / 2, ms, ms, -1);
			}
		}
	}

	private void drawLabel(Hd hd, int x, int y, int size, int cx, int cy) {
		if (ringPlaced) {
			drawPlate(hd);
			return;
		}
		if (name.isEmpty()) {
			return;
		}
		HdStyle st = HdStyle.PETIT;
		String label = hd.ellipsize(st, name, hd.u(70));
		int tw = hd.width(st, label);
		int lh = hd.lineHeight(st);
		int padX = hd.u(3);
		int lx = switch (labelSide) {
			case 2 -> x - hd.u(3) - tw / 2 - padX;
			case 3 -> x + size + hd.u(3) + tw / 2 + padX;
			default -> cx;
		};
		int ly = switch (labelSide) {
			case 1 -> y - lh - hd.u(1);
			case 2, 3 -> cy - lh / 2;
			default -> y + size + hd.u(1);
		};
		hd.fill(lx - tw / 2 - padX, ly, lx + tw / 2 + padX, ly + lh, 0xC01B1214);
		hd.text(st, label, lx - tw / 2, ly, alive ? Hd.PARCH : DEAD_LABEL);
		if (caption != null) {
			hd.textCentered(st, caption, cx, y + size + lh + hd.u(2), Hd.GOLD);
		}
	}

	/** La plaque posée par le cercle : fond d'encre, nom centré, liseré doré quand le jeton est survolé. */
	private void drawPlate(Hd hd) {
		if (plate == null || plateText.isEmpty()) {
			return;
		}
		HdStyle st = SeatRing.LABEL;
		int x0 = hd.u(plate.x());
		int y0 = hd.u(plate.y());
		int x1 = hd.u(plate.right());
		int y1 = hd.u(plate.bottom());
		boolean hover = isHovered();
		hd.fill(x0, y0, x1, y1, hover ? 0xE02A1A1E : 0xC01B1214);
		if (hover) {
			hd.frame(x0, y0, x1 - x0, y1 - y0, Math.max(1, hd.u(0.5)), Hd.GOLD);
		}
		int tw = hd.width(st, plateText);
		hd.text(st, plateText, (x0 + x1) / 2 - tw / 2, y0 + (y1 - y0 - hd.lineHeight(st)) / 2, alive ? Hd.PARCH : DEAD_LABEL);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
