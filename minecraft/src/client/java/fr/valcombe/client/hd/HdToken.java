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
		return tooltip;
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
			int ms = hd.u(10);
			for (int i = 0; i < markers.size(); i++) {
				hd.image(Theme.tex("marker_" + markers.get(i)), x + size - hd.u(8) - i * hd.u(9), y - hd.u(3), ms, ms, -1);
			}
		}
	}

	private void drawLabel(Hd hd, int x, int y, int size, int cx, int cy) {
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
		hd.text(st, label, lx - tw / 2, ly, alive ? Hd.PARCH : Hd.ASH);
		if (caption != null) {
			hd.textCentered(st, caption, cx, y + size + lh + hd.u(2), Hd.GOLD);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
