package fr.valcombe.client.screen;

import fr.valcombe.client.ui.Theme;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Menu de l'outil « Carte : main » : ce qu'on peut faire de l'objet visé (trône, maison, salle, centre ou
 * structure posée). Chaque bouton envoie /vc objet, puis l'écran se ferme pour laisser voir le résultat.
 */
public class ObjectScreen extends BaseScreen {
	private final String type;
	private final int index;
	private final String label;

	private record Action(String text, String verb, boolean closes) {
	}

	public ObjectScreen(String type, int index, String label) {
		super(label);
		this.type = type;
		this.index = index;
		this.label = label;
	}

	@Override
	protected int[] panelSize() {
		return new int[] {260, 150};
	}

	private List<Action> actions() {
		List<Action> out = new ArrayList<>();
		out.add(new Action("Déplacer", "deplacer", true));
		switch (type) {
			case "trone" -> {
				out.add(new Action("⟲ Tourner à gauche", "gauche", false));
				out.add(new Action("⟳ Tourner à droite", "droite", false));
				out.add(new Action("Regarder le centre", "centre", false));
			}
			case "structure" -> {
				out.add(new Action("⟳ Tourner d'un quart", "tourner", true));
				out.add(new Action("Retourner (miroir)", "retourner", true));
			}
			default -> {
			}
		}
		if (!type.equals("centre")) {
			out.add(new Action(type.equals("structure") ? "Retirer (le terrain revient)" : "Retirer", "retirer", true));
		}
		return out;
	}

	@Override
	protected void build() {
		List<Action> list = actions();
		int w = pw - 28;
		int y = py + 28;
		for (Action a : list) {
			button(px + 14, y, w, a.text(), () -> {
				send("vc objet " + type + " " + index + " " + a.verb());
				if (a.closes()) {
					onClose();
				}
			});
			y += 20;
		}
		button(px + pw / 2 - 40, py + ph - 24, 80, "Fermer", this::onClose);
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		String hint = switch (type) {
			case "trone" -> "L'aiguille du vote suit l'ordre des trônes.";
			case "structure" -> "Posée depuis le catalogue.";
			default -> "Repère de la carte.";
		};
		Theme.ink(g, font, hint, px + 14, py + 12, Theme.ASH);
	}
}
