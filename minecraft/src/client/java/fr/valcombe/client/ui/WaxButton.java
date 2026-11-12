package fr.valcombe.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** Bouton à la cire : fond lie-de-vin, liseré bronze, rouge sang au survol. */
public class WaxButton extends AbstractButton {
	private final Runnable action;

	public WaxButton(int x, int y, int w, int h, String label, Runnable action) {
		super(x, y, w, h, Component.literal(label));
		this.action = action;
	}

	public WaxButton disabled() {
		this.active = false;
		return this;
	}

	/** Lance l'action du bouton, comme un clic (le test en jeu s'en sert pour passer par les vrais boutons). */
	public void press() {
		if (active) {
			action.run();
		}
	}

	@Override
	public void onPress(InputWithModifiers input) {
		action.run();
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		String tex = !active ? "button_disabled" : isHoveredOrFocused() ? "button_hover" : "button";
		Theme.nineSlice(g, Theme.tex(tex), getX(), getY(), getWidth(), getHeight(), 64, 20, 3);
		var font = Minecraft.getInstance().font;
		int color = active ? Theme.PARCH : 0xFFB0A8A0;
		// Un libellé trop long est raccourci (« … ») au lieu de déborder du bouton.
		String label = getMessage().getString();
		if (font.width(label) > getWidth() - 8) {
			label = font.plainSubstrByWidth(label, getWidth() - 8 - font.width("…")) + "…";
		}
		g.centeredText(font, label, getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, color);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
