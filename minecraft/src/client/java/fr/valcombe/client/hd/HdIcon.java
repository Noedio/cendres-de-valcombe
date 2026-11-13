package fr.valcombe.client.hd;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Icône : une image du jeu (pictogramme de rôle, emblème, marqueur) à n'importe quelle taille, réduite avec ses
 * mipmaps et agrandie sans flou pour le pixel art. Avec une action, elle se clique (halo au survol) ; avec une
 * infobulle, elle s'explique. Pour une icône dessinée au fil d'un écran, Hd.image suffit.
 */
public class HdIcon extends AbstractWidget implements HdTooltipped {
	private final Identifier texture;
	private final Runnable action;
	private String tooltip;
	private int tint = -1;

	/** Icône carrée de size unités en (x, y) ; action peut être nulle (icône seulement décorative). */
	public HdIcon(int x, int y, int size, Identifier texture, String narration, Runnable action) {
		super(x, y, size, size, Component.literal(narration));
		this.texture = texture;
		this.action = action;
		this.active = action != null;
	}

	/** Une texture de l'interface de Valcombe (textures/gui/&lt;chemin&gt;.png). */
	public static Identifier gui(String path) {
		return Identifier.fromNamespaceAndPath("valcombe", "textures/gui/" + path + ".png");
	}

	public HdIcon tooltip(String text) {
		this.tooltip = text;
		return this;
	}

	/** Teinte (ARGB) appliquée à l'image, -1 pour aucune. */
	public HdIcon tint(int argb) {
		this.tint = argb;
		return this;
	}

	@Override
	public String hdTooltip() {
		return tooltip;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (action == null) {
			return false;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		action.run();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (action != null && isFocused() && event.isSelection()) {
			action.run();
			return true;
		}
		return false;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		try (Hd hd = Hd.begin(g)) {
			int x = hd.px(getX());
			int y = hd.px(getY());
			int s = hd.px(getWidth());
			if (action != null && isHoveredOrFocused()) {
				int m = hd.u(2);
				hd.image(HdSprites.ANNEAU_SURVOL, x - m, y - m, s + 2 * m, s + 2 * m);
			}
			hd.image(texture, x, y, s, s, tint);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, getMessage());
	}
}
