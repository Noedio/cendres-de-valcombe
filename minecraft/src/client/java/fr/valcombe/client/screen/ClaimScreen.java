package fr.valcombe.client.screen;

import com.google.gson.JsonElement;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdToken;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.Team;

import java.util.ArrayList;
import java.util.List;

/**
 * Annoncer publiquement un rôle (le sien, ou un bluff) : il s'affiche sous le nom du joueur,
 * au-dessus de son trône, pour que tout le cercle s'en souvienne.
 */
public class ClaimScreen extends HdScreen {
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
		// Les jetons du script, douze par rangée ; le nom et le type du rôle en infobulle.
		for (int i = 0; i < roles.size(); i++) {
			Role r = roles.get(i);
			int x = px + 24 + (i % 12) * 30;
			int y = py + 30 + (i / 12) * 30;
			token(new HdToken(x, y, 28, "", r.name(), Team.of(r).name(), true, false, () -> {
				send("vc annoncer " + r.name().toLowerCase());
				onClose();
			}).tooltip(r.displayName + ", " + r.type.displayName));
		}
		button(px + 12, py + ph - 26, 130, "Ne rien annoncer", () -> {
			send("vc annoncer aucun");
			onClose();
		});
		button(px + pw - 92, py + ph - 26, 80, "Retour", () -> minecraft.gui.setScreen(new MainScreen()));
	}

	@Override
	protected void drawHd(Hd hd) {
		hd.text(HdStyle.TEXTE.size(10), "Tout le monde le verra sous ton nom. Bluffer est permis.", hd.px(px + 14),
			hd.px(py + ph - 45), Hd.INK_SOFT);
	}
}
