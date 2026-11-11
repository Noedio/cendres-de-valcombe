package fr.valcombe.client.screen;

import com.google.gson.JsonElement;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.ui.Theme;
import fr.valcombe.client.ui.TokenWidget;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.Team;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Annoncer publiquement un rôle (le sien, ou un bluff) : il s'affiche sous le nom du joueur,
 * au-dessus de son trône, pour que tout le cercle s'en souvienne.
 */
public class ClaimScreen extends BaseScreen {
	public ClaimScreen() {
		super("Annoncer un rôle");
	}

	@Override
	protected int[] panelSize() {
		return new int[] {390, 250};
	}

	private List<Role> roles() {
		List<Role> out = new ArrayList<>();
		var sc = ClientState.obj(view(), "script");
		if (sc != null && sc.has("roles")) {
			for (JsonElement e : sc.getAsJsonArray("roles")) {
				try {
					Role r = Role.valueOf(e.getAsString());
					if (r.active) {
						out.add(r);
					}
				} catch (IllegalArgumentException ignored) {
					// rôle inconnu de cette version du mod
				}
			}
		}
		return out;
	}

	@Override
	protected void build() {
		List<Role> roles = roles();
		for (int i = 0; i < roles.size(); i++) {
			Role r = roles.get(i);
			int x = px + 24 + (i % 12) * 30;
			int y = py + 30 + (i / 12) * 30;
			TokenWidget t = new TokenWidget(x, y, 28, "", r.name(), Team.of(r).name(), true, false, () -> {
				send("vc annoncer " + r.name().toLowerCase());
				onClose();
			});
			t.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
				net.minecraft.network.chat.Component.literal(r.displayName + " — " + r.type.displayName)));
			addRenderableWidget(t);
		}
		button(px + 12, py + ph - 26, 130, "Ne rien annoncer", () -> {
			send("vc annoncer aucun");
			onClose();
		});
		button(px + pw - 92, py + ph - 26, 80, "Retour", () -> minecraft.gui.setScreen(new MainScreen()));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Theme.ink(g, font, "Tout le monde le verra sous ton nom. Bluffer est permis.", px + 14, py + ph - 42, Theme.ASH);
	}
}
