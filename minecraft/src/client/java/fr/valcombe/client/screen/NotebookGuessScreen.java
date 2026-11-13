package fr.valcombe.client.screen;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdText;
import fr.valcombe.rules.Role;

import java.util.ArrayList;
import java.util.List;

/** Carnet : choisir le rôle que l'on prête à un joueur, parmi les rôles du script en cours. */
public class NotebookGuessScreen extends HdScreen {
	private final String name;

	public NotebookGuessScreen(String name) {
		super("Rôle prêté à " + name);
		this.name = name;
	}

	@Override
	protected int[] panelSize() {
		return new int[] {420, 250};
	}

	private static List<Role> roles() {
		List<Role> out = new ArrayList<>();
		JsonObject script = ClientState.obj(view(), "script");
		for (String id : strings(script, "roles")) {
			Role r = NotebookScreen.roleOf(id);
			if (r != null) {
				out.add(r);
			}
		}
		out.sort((a, b) -> a.type != b.type ? a.type.compareTo(b.type) : a.displayName.compareTo(b.displayName));
		return out;
	}

	@Override
	protected void build() {
		List<Role> roles = roles();
		int cols = 4;
		int bw = (pw - 28 - (cols - 1) * 4) / cols;
		int rows = Math.max(1, (ph - 70) / 20);
		// Un bouton par rôle du script, quatre par rangée ; le rôle déjà prêté est grisé.
		for (int i = 0; i < roles.size() && i < cols * rows; i++) {
			Role r = roles.get(i);
			int x = px + 14 + (i % cols) * (bw + 4);
			int y = py + 28 + (i / cols) * 20;
			var b = button(x, y, bw, r.displayName, () -> pick(r.name()));
			if (r.name().equals(NotebookScreen.page(name).guess)) {
				b.disabled();
			}
		}
		button(px + 14, py + ph - 26, 120, "Aucun", () -> pick(null));
		button(px + pw - 94, py + ph - 26, 80, "Retour", () -> minecraft.gui.setScreen(new NotebookScreen()));
	}

	private void pick(String role) {
		NotebookScreen.page(name).guess = role;
		NotebookScreen.save();
		minecraft.gui.setScreen(new NotebookScreen());
	}

	@Override
	protected void drawHd(Hd hd) {
		HdText.line(hd, HdText.SERRE, "Quel rôle penses-tu que " + name + " a vraiment ?", hd.px(px + 14), hd.px(py + 11),
			hd.px(pw - 28), Hd.INK_SOFT);
	}
}
