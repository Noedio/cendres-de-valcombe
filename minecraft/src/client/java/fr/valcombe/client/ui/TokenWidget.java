package fr.valcombe.client.ui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** Jeton d'un joueur du cercle (cliquable ou non), avec son nom et ses marqueurs. */
public class TokenWidget extends AbstractButton {
	private final String name;
	private final String roleId;
	private final String team;
	private final boolean alive;
	private final boolean selected;
	private final Runnable action;
	private final List<String> markers = new ArrayList<>();
	private String caption;

	public TokenWidget(int cx, int cy, int size, String name, String roleId, String team, boolean alive,
			boolean selected, Runnable action) {
		super(cx - size / 2, cy - size / 2, size, size, Component.literal(name));
		this.name = name;
		this.roleId = roleId;
		this.team = team;
		this.alive = alive;
		this.selected = selected;
		this.action = action;
	}

	public TokenWidget marker(String m) {
		markers.add(m);
		return this;
	}

	public TokenWidget caption(String c) {
		this.caption = c;
		return this;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		if (action != null) {
			action.run();
		}
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int cx = getX() + getWidth() / 2;
		int cy = getY() + getHeight() / 2;
		Theme.token(g, cx, cy, getWidth(), roleId, team, alive, selected, action != null && isHovered());
		var font = Minecraft.getInstance().font;
		if (name.isEmpty()) {
			return;
		}
		String label = name.length() > 12 ? name.substring(0, 11) + "…" : name;
		int tw = font.width(label);
		g.fill(cx - tw / 2 - 2, getY() + getHeight() + 1, cx + tw / 2 + 2, getY() + getHeight() + 11, 0xB01B1214);
		g.centeredText(font, label, cx, getY() + getHeight() + 2, alive ? Theme.PARCH : Theme.ASH);
		if (caption != null) {
			g.centeredText(font, caption, cx, getY() + getHeight() + 12, Theme.GOLD);
		}
		for (int i = 0; i < markers.size(); i++) {
			Theme.marker(g, markers.get(i), getX() + getWidth() - 8 - i * 9, getY() - 3);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
