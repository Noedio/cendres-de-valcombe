package fr.valcombe.client.screen;

import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu de l'outil « Carte : main », en haute résolution : ce qu'on peut faire de l'objet visé (trône, maison, salle,
 * centre ou structure posée). Chaque bouton envoie /vc objet, puis l'écran se ferme pour laisser voir le résultat
 * (sauf les quarts de tour d'un trône, qu'on enchaîne). Le panneau prend la hauteur de ses boutons.
 */
public class ObjectScreen extends HdScreen {
	/** Pas entre deux boutons (unités ; un bouton fait 18 de haut). */
	private static final int PITCH = 22;

	private final String type;
	private final int index;

	private record Action(String text, String verb, boolean closes, String tip) {
	}

	public ObjectScreen(String type, int index, String label) {
		super(label);
		this.type = type;
		this.index = index;
	}

	@Override
	protected int[] panelSize() {
		// Phrase d'aide, boutons, puis Fermer.
		return new int[] {260, 40 + actions().size() * PITCH + 30};
	}

	private List<Action> actions() {
		List<Action> out = new ArrayList<>();
		out.add(new Action("Déplacer", "deplacer", true, "La reprendre en main pour la poser ailleurs."));
		switch (type) {
			case "trone" -> {
				out.add(new Action("Tourner à gauche", "gauche", false, "Un huitième de tour vers la gauche."));
				out.add(new Action("Tourner à droite", "droite", false, "Un huitième de tour vers la droite."));
				out.add(new Action("Regarder le centre", "centre", false, "Le trône se tourne vers le centre de la place."));
			}
			case "structure" -> {
				out.add(new Action("Tourner d'un quart", "tourner", true, "Un quart de tour dans le sens des aiguilles d'une montre."));
				out.add(new Action("Retourner (miroir)", "retourner", true, "La même structure, vue dans un miroir."));
			}
			default -> {
			}
		}
		if (!type.equals("centre")) {
			out.add(new Action(type.equals("structure") ? "Retirer (le terrain revient)" : "Retirer", "retirer", true,
				type.equals("structure") ? "Le terrain d'avant la pose revient." : "Le repère quitte la carte."));
		}
		return out;
	}

	/** La phrase d'aide sous le titre. */
	private String hint() {
		return switch (type) {
			case "trone" -> "L'aiguille du vote suit l'ordre des trônes.";
			case "structure" -> "Posée depuis le catalogue.";
			default -> "Repère de la carte.";
		};
	}

	@Override
	protected void build() {
		int w = pw - 28;
		int y = py + 34;
		for (Action a : actions()) {
			button(px + 14, y, w, a.text(), () -> {
				send("vc objet " + type + " " + index + " " + a.verb());
				if (a.closes()) {
					onClose();
				}
			}).tooltip(a.tip());
			y += PITCH;
		}
		button(px + pw / 2 - 45, py + ph - 26, 90, "Fermer", this::onClose);
	}

	@Override
	protected void drawHd(Hd hd) {
		hd.textCentered(HdStyle.TEXTE, hint(), hd.px(px + pw / 2), hd.px(py + 15), Hd.INK_SOFT);
	}
}
