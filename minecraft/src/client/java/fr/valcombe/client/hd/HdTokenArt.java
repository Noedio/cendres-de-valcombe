package fr.valcombe.client.hd;

import fr.valcombe.client.ClientConfig;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.rules.Role;

import java.util.Locale;

/**
 * Jeton dessiné (pas un widget) : le même dessin que HdToken, pour les jetons qui ne se cliquent pas ou que l'écran
 * gère lui-même (carte de rôle, fiches au survol, carte qui tombe sur l'affichage en jeu). Remplace Theme.token
 * dans un contexte Hd : position et taille en pixels réels.
 */
public final class HdTokenArt {
	private HdTokenArt() {
	}

	/**
	 * Dessine un jeton de size pixels dont le coin haut gauche est en (x, y) : ombre, disque du camp, pictogramme du
	 * rôle (roleId : nom du rôle ou d'un pictogramme, null pour un jeton fermé), linceul s'il est mort, marque du mode
	 * daltonien, anneau doré s'il est choisi ou anneau de survol.
	 */
	public static void draw(Hd hd, int x, int y, int size, String roleId, String team, boolean alive, boolean selected,
			boolean hovered) {
		drawWithAlpha(hd, x, y, size, roleId, team, alive, selected, hovered, 0xFF);
	}

	/** Même dessin, rendu plus ou moins transparent (alpha de 0 à 255) : pour un jeton qui apparaît ou s'efface. */
	public static void drawWithAlpha(Hd hd, int x, int y, int size, String roleId, String team, boolean alive,
			boolean selected, boolean hovered, int alpha) {
		if (size <= 0 || alpha <= 0) {
			return;
		}
		int tint = Math.min(255, alpha) << 24 | 0xFFFFFF;
		int cx = x + size / 2;
		int cy = y + size / 2;
		int sh = Math.round(size * 1.4f);
		hd.image(HdSprites.OMBRE_RONDE, cx - sh / 2, cy - sh / 2 + hd.u(1), sh, sh, (Math.min(255, alpha) * 0xB0 / 255) << 24 | 0xFFFFFF);
		String disc = team == null ? "token_neutral" : team.equals("EVIL") ? "token_evil" : "token_good";
		hd.image(Theme.tex(disc), x, y, size, size, tint);
		if (roleId != null) {
			int is = size * 22 / 32;
			Role r = Role.parse(roleId);
			String icon = r != null ? r.icon : roleId.toLowerCase(Locale.ROOT);
			hd.image(Theme.tex("roles/" + icon), cx - is / 2, cy - is / 2, is, is, tint);
		}
		if (!alive) {
			hd.image(Theme.tex("token_dead"), x, y, size, size, tint);
		}
		if (team != null && ClientConfig.get().daltonien && size >= hd.u(16)) {
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
			hd.image(HdSprites.ANNEAU_CHOISI, x - m, y - m, size + 2 * m, size + 2 * m, tint);
		} else if (hovered) {
			hd.image(HdSprites.ANNEAU_SURVOL, x - m, y - m, size + 2 * m, size + 2 * m, tint);
		}
	}
}
