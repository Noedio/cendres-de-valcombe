package fr.valcombe.client.screen.atelier;

import com.google.gson.JsonObject;

import fr.valcombe.client.ClientState;
import fr.valcombe.client.hd.Hd;
import fr.valcombe.client.hd.HdStyle;
import fr.valcombe.client.hd.HdTokenArt;

import java.util.ArrayList;
import java.util.List;

/**
 * Atelier › Rôles et scénarios › (un rôle créé) : sa fiche. À gauche, son jeton en grand sur le velours ; à droite, son
 * camp, son pouvoir, ses réveils, les scénarios qui le citent. Il s'écrit et se modifie sur la page web de l'Atelier
 * (« Modifier sur la page web ») ; ici, il se supprime (confirmé).
 */
public class RoleSheetPage extends AtelierPage {
	public static final String EDIT = "Modifier sur la page web";
	public static final String DELETE = "Supprimer";
	public static final String DELETE_CONFIRM = "Supprimer le rôle";
	public static final String CANCEL = "Annuler";

	private static String lastId;
	private static boolean confirming;
	private static String leaving;

	private final String id;
	private boolean leave;

	public RoleSheetPage(String id) {
		this.id = id;
		if (!id.equals(lastId)) {
			lastId = id;
			confirming = false;
		}
	}

	/** La fiche ouverte depuis la liste : elle commence sur ses actions (pas sur une confirmation laissée en route). */
	public static RoleSheetPage visit(String id) {
		lastId = null;
		return new RoleSheetPage(id);
	}

	public String roleId() {
		return id;
	}

	private JsonObject role() {
		return RolesPage.role(id);
	}

	@Override
	protected List<Crumb> trail() {
		JsonObject r = role();
		List<Crumb> t = new ArrayList<>(List.of(new Crumb("Atelier", AtelierHome::new), new Crumb(AtelierHome.ROLES, () -> new RolesPage(1))));
		t.add(new Crumb(r == null ? id : str(r, "nom"), null));
		return t;
	}

	@Override
	protected boolean cancelPending() {
		boolean was = confirming;
		confirming = false;
		return was;
	}

	@Override
	public void tick() {
		super.tick();
		if (leave) {
			leave = false;
			leaving = null;
			go(new RolesPage(1));
		}
	}

	private int artW() {
		return Math.max(80, Math.min(bottom() - top() - 4, (right() - left()) * 38 / 100));
	}

	private int colX() {
		return left() + artW() + 14;
	}

	private int colW() {
		return right() - colX();
	}

	@Override
	protected void buildPage() {
		JsonObject r = role();
		if (r == null) {
			if (id.equals(leaving)) {
				leave = true;
			} else {
				button(left(), top() + 40, 160, "Retour aux rôles", () -> go(new RolesPage(1))).primary();
			}
			return;
		}
		int x = colX();
		int w = colW();
		int half = (w - 6) / 2;
		int by = bottom() - 4 - 18;
		if (confirming) {
			button(x, by, half, DELETE_CONFIRM, () -> {
				confirming = false;
				leaving = id;
				run("vc atelier supprimer role " + id, "Suppression du rôle");
			}).tooltip("Il disparaît du serveur et des scénarios qui le citaient.");
			button(x + half + 6, by, w - half - 6, CANCEL, () -> {
				confirming = false;
				rebuildSoon();
			});
			return;
		}
		button(x, by - 22, w, EDIT, () -> {
			run("vc atelier", "Lien de la page web");
			onClose();
		}).primary().tooltip("Son pouvoir, ses réveils, ses jetons de rappel s'écrivent sur la page web : le lien arrive dans le chat.");
		button(x, by, w, DELETE, () -> {
			confirming = true;
			rebuildSoon();
		}).tooltip("Le supprimer du serveur, après confirmation.");
	}

	@Override
	protected void drawPage(Hd hd) {
		JsonObject r = role();
		if (r == null) {
			hd.paragraph(HdStyle.TEXTE, id.equals(leaving) ? "Suppression…" : "Ce rôle n'est plus sur le serveur.",
				hd.px(left()), hd.px(top() + 6), hd.px(right() - left()), Hd.INK_SOFT);
			return;
		}
		// Le jeton en grand, sur le velours encadré d'or.
		int ax = hd.px(left());
		int ay = hd.px(top() + 2);
		int aw = hd.px(artW());
		int ah = hd.px(bottom() - 4) - ay;
		AtelierKit.velvet(hd, ax, ay, aw, ah, id.hashCode());
		hd.frame(ax - hd.u(2), ay - hd.u(2), aw + hd.u(4), ah + hd.u(4), Math.max(1, hd.u(0.5)), 0xFFD9B45A);
		int size = Math.min(aw, ah) * 7 / 10;
		HdTokenArt.draw(hd, ax + (aw - size) / 2, ay + (ah - size) / 2, size, str(r, "embleme"), AtelierKit.team(r), true, false, false);
		int x = hd.px(colX());
		int w = hd.px(colW());
		int y = ay;
		HdStyle title = HdStyle.SECTION.size(13);
		hd.text(title, hd.ellipsize(title, str(r, "nom"), w), x, y, Hd.WINE);
		y += hd.lineHeight(title) + hd.u(1);
		HdStyle cs = HdStyle.PETIT.size(8.5f);
		int cx = x + AtelierArt.chip(hd, cs, str(r, "campTexte"), x, y, AtelierKit.campColor(str(r, "camp"))) + hd.u(3);
		String side = ClientState.bool(r, "mal") ? "Le mal" : "Le bien";
		AtelierArt.chip(hd, cs, side, cx, y, ClientState.bool(r, "mal") ? 0xFF6E1E28 : 0xFF2F5C8A);
		y += AtelierArt.chipHeight(hd, cs) + hd.u(4);
		int limit = hd.px(bottom() - 4 - 18 - (confirming ? 0 : 22)) - hd.u(5);
		List<String> in = words(r, "scenarios");
		if (confirming) {
			hd.paragraph(HdStyle.TEXTE, "Supprimer le rôle « " + str(r, "nom") + " » ? Il disparaît du serveur" + (in.isEmpty() ? "."
				: " et des scénarios qui le citent (« " + String.join(" », « ", in) + " »)."), x, y, w, AtelierKit.WARN, limit - y);
			return;
		}
		String text = str(r, "texte");
		if (!text.isEmpty()) {
			y += hd.paragraph(HdStyle.TEXTE, text, x, y, w, Hd.INK, Math.min(limit - y, hd.lineHeight(HdStyle.TEXTE) * 4)) + hd.u(4);
		}
		List<String[]> rows = new ArrayList<>();
		rows.add(new String[] {"Première nuit", str(r, "premiereNuit")});
		rows.add(new String[] {"Autres nuits", str(r, "autresNuits")});
		rows.add(new String[] {"Scénarios", in.isEmpty() ? "aucun ne le cite" : String.join(", ", in)});
		rows.add(new String[] {"Identifiant", "« " + id + " »"});
		AtelierKit.details(hd, rows, x, y, w, limit);
	}
}
