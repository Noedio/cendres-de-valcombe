package fr.valcombe.client.screen;

import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdScreen;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdToken;
import fr.valcombe.rules.Role;
import fr.valcombe.rules.RoleType;
import fr.valcombe.rules.Team;

import java.util.List;

/**
 * Choix d'un rôle pour un joueur. Pour le Somnambule, un second choix demande
 * le Citadin qu'il croira être (chosen = SOMNAMBULE).
 */
public class RolePickerScreen extends HdScreen {
	private final String name;
	private final Role chosen;
	/** Ouvert depuis l'écran « Distribution » (on y revient), sinon depuis la fiche du joueur. */
	private final boolean fromDistribution;

	public RolePickerScreen(String name, Role chosen) {
		this(name, chosen, false);
	}

	public RolePickerScreen(String name, Role chosen, boolean fromDistribution) {
		super(chosen == null ? "Rôle de " + name : name + " croit être…");
		this.name = name;
		this.chosen = chosen;
		this.fromDistribution = fromDistribution;
	}

	private static boolean lobby() {
		return fr.valcombe.client.ClientState.phase().equals("LOBBY");
	}

	private BaseScreen back() {
		return fromDistribution ? new DistributionScreen() : new SeatScreen(name);
	}

	@Override
	protected int[] panelSize() {
		return new int[] {390, 250};
	}

	@Override
	protected void build() {
		List<Role> roles = chosen == null ? Role.all() : Role.ofType(RoleType.CITADIN);
		if (lobby()) {
			// Au salon, seuls les rôles du script peuvent être imposés.
			List<String> script = strings(fr.valcombe.client.ClientState.obj(view(), "script"), "roles");
			roles = roles.stream().filter(r -> chosen != null || script.contains(r.name())).toList();
		}
		// Douze jetons par rangée ; le nom et le type du rôle en infobulle.
		for (int i = 0; i < roles.size(); i++) {
			Role r = roles.get(i);
			int x = px + 24 + (i % 12) * 30;
			int y = py + 30 + (i / 12) * 30;
			token(new HdToken(x, y, 28, "", r.name(), Team.of(r).name(), true, false, () -> pick(r))
				.tooltip(r.displayName + ", " + r.type.displayName));
		}
		button(px + pw - 92, py + ph - 26, 80, "Retour", () -> minecraft.gui.setScreen(back()));
	}

	private void pick(Role r) {
		if (chosen == null && r == Role.SOMNAMBULE) {
			minecraft.gui.setScreen(new RolePickerScreen(name, Role.SOMNAMBULE, fromDistribution));
			return;
		}
		// Au salon : rôle imposé ; avant la première nuit : retouche de la distribution ; ensuite : changement en cours de partie.
		String verb = lobby() ? "vc imposer " : fr.valcombe.client.ClientState.phase().equals("READY") ? "vc attribuer " : "vc changer ";
		String cmd = chosen == null
			? verb + name + " " + r.name().toLowerCase()
			: verb + name + " somnambule " + r.name().toLowerCase();
		send(cmd);
		minecraft.gui.setScreen(back());
	}

	@Override
	protected void drawHd(Hd hd) {
		if (chosen != null) {
			hd.textMiddle(HdStyle.TEXTE.size(10), "Le Somnambule croit être un Citadin absent.", hd.px(px + 14), hd.px(py + ph - 26),
				hd.px(18), Hd.INK_SOFT);
		}
	}
}
