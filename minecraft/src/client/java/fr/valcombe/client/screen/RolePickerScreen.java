package fr.valcombe.client.screen;

import fr.valcombe.client.ui.Theme;
import fr.valcombe.client.ui.TokenWidget;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleType;
import fr.valcombe.rules.Team;

import java.util.Arrays;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Choix d'un rôle pour un joueur. Pour le Somnambule, un second choix demande
 * le Citadin qu'il croira être (chosen = SOMNAMBULE).
 */
public class RolePickerScreen extends BaseScreen {
	private final String name;
	private final Role chosen;

	public RolePickerScreen(String name, Role chosen) {
		super(chosen == null ? "Rôle de " + name : name + " croit être…");
		this.name = name;
		this.chosen = chosen;
	}

	@Override
	protected int[] panelSize() {
		return new int[] {390, 250};
	}

	@Override
	protected void build() {
		List<Role> roles = chosen == null ? Role.all() : Role.ofType(RoleType.CITADIN);
		for (int i = 0; i < roles.size(); i++) {
			Role r = roles.get(i);
			int x = px + 24 + (i % 12) * 30;
			int y = py + 30 + (i / 12) * 30;
			TokenWidget t = new TokenWidget(x, y, 28, "", r.name(), Team.of(r).name(), true, false, () -> pick(r));
			t.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
				net.minecraft.network.chat.Component.literal(r.displayName + " — " + r.type.displayName)));
			addRenderableWidget(t);
		}
		button(px + pw - 92, py + ph - 26, 80, "Retour", () -> minecraft.gui.setScreen(new SeatScreen(name)));
	}

	private void pick(Role r) {
		if (chosen == null && r == Role.SOMNAMBULE) {
			minecraft.gui.setScreen(new RolePickerScreen(name, Role.SOMNAMBULE));
			return;
		}
		// Avant la première nuit : retouche de la distribution ; ensuite : changement en cours de partie.
		String verb = fr.valcombe.client.ClientState.phase().equals("READY") ? "vc attribuer " : "vc changer ";
		String cmd = chosen == null
			? verb + name + " " + r.name().toLowerCase()
			: verb + name + " somnambule " + r.name().toLowerCase();
		send(cmd);
		minecraft.gui.setScreen(new SeatScreen(name));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (chosen != null) {
			Theme.ink(g, font, "Le Somnambule croit être un Citadin absent.", px + 14, py + ph - 22, Theme.ASH);
		}
	}
}
