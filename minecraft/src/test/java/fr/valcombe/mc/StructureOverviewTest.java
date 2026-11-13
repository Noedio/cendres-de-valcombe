package fr.valcombe.mc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * La vue d'arrivée dans l'atelier structures : en hauteur, au-dessus de l'accueil, le regard plongeant sur les rangées,
 * et toutes les parcelles dans le champ (un écran 16:9 voit environ 50° de part et d'autre du regard).
 */
class StructureOverviewTest {
	/** Écart (en degrés, de -180 à 180) entre la direction du regard et celle d'un point, vu de dessus. */
	private static double offAxis(StructurePlots.View v, double x, double z) {
		double toPoint = Math.toDegrees(Math.atan2(-(x - v.x()), z - v.z()));
		double d = toPoint - v.yaw();
		while (d > 180) {
			d -= 360;
		}
		while (d < -180) {
			d += 360;
		}
		return d;
	}

	private static StructurePlots.Layout layout(int count, int rayon) {
		StructurePlots.Layout l = new StructurePlots.Layout();
		List<StructurePlots.Need> needs = new ArrayList<>();
		String[] cats = {"maisons", "place", "decor", "perso"};
		for (int i = 0; i < count; i++) {
			needs.add(new StructurePlots.Need("s" + i, cats[i % 4], rayon + i % 3, 2, 16));
		}
		StructurePlots.allocate(l, needs);
		return l;
	}

	private static void check(StructurePlots.Layout l, String what, boolean nearWelcome) {
		StructurePlots.View v = StructurePlots.overview(l);
		assertTrue(v.height() >= 14 && v.height() <= 36, what + " : hauteur " + v.height());
		assertTrue(v.pitch() >= 25 && v.pitch() <= 55, what + " : regard " + v.pitch());
		// Au nord-ouest de l'accueil (0, 3) ; pour un atelier de taille courante, sans en être loin : c'est de là qu'on le
		// découvre. Un très grand atelier recule le point de vue, pour tout voir quand même.
		assertTrue(v.x() < 0 && v.z() < StructurePlots.FIRST_Z, what + " : " + v);
		assertTrue(!nearWelcome || Math.hypot(v.x(), v.z() - 3) < 40, what + " : loin de l'accueil, " + v);
		// Regard vers le sud-est, là où s'étendent les rangées.
		assertTrue(v.yaw() < 0 && v.yaw() > -90, what + " : orientation " + v.yaw());
		for (StructurePlots.Plot p : l.parcelles) {
			for (double[] corner : new double[][] {{p.west(), p.bande}, {p.east(), p.bande}, {p.west(), p.z + p.rayon + 1},
				{p.east(), p.z + p.rayon + 1}}) {
				double off = offAxis(v, corner[0], corner[1]);
				assertTrue(Math.abs(off) <= 52, what + " : la parcelle " + p.id + " sort du champ (" + Math.round(off) + "°)");
			}
			// Et de haut en bas, comme Minecraft projette l'image (champ vertical de 70°, écran 16:9) : le centre de chaque
			// parcelle, au sol, est dans l'image (vérifié aussi en jeu, par StructuresWorkshopGameTest).
			double[] eye = {v.x(), v.height() + 1.62, v.z()};
			double yaw = Math.toRadians(v.yaw());
			double pitch = Math.toRadians(v.pitch());
			double[] f = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
			double[] r = {-Math.cos(yaw), 0, -Math.sin(yaw)};
			double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
			double[] c = {p.x + 0.5 - eye[0], -eye[1], p.z + 0.5 - eye[2]};
			double ahead = c[0] * f[0] + c[1] * f[1] + c[2] * f[2];
			double side = c[0] * r[0] + c[1] * r[1] + c[2] * r[2];
			double high = c[0] * u[0] + c[1] * u[1] + c[2] * u[2];
			double tanV = Math.tan(Math.toRadians(35));
			assertTrue(ahead > 0 && Math.abs(high) / ahead <= tanV && Math.abs(side) / ahead <= tanV * 16 / 9,
				what + " : le centre de la parcelle " + p.id + " sort de l'image (" + Math.round(Math.toDegrees(Math.atan2(high, ahead)))
					+ "° en hauteur, " + Math.round(Math.toDegrees(Math.atan2(side, ahead))) + "° de côté)");
		}
	}

	@Test
	void toutesLesParcellesEnVue() {
		check(new StructurePlots.Layout(), "atelier vide", true);
		check(layout(4, 5), "quelques parcelles", true);
		check(layout(26, 6), "le catalogue fourni", true);
		check(layout(80, 8), "un grand catalogue", false);
	}
}
