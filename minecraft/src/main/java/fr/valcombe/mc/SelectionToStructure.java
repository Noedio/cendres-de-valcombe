package fr.valcombe.mc;

import fr.valcombe.mc.worlds.BuildAccess;
import fr.valcombe.rules.GameException;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * « Sélection → structure du catalogue » : la sélection WorldEdit d'un joueur devient une structure du catalogue, qu'on
 * pose ensuite comme les autres (outil « Carte : structure », atelier structures, cartes). {@code /vc structures
 * depuis-selection <id> <nom> [catégorie]}, le bouton « Enregistrer la sélection » de l'Atelier (écran en jeu et page
 * web) et l'ancienne {@code /vc structure enregistrer} passent tous par ici.
 * <ul>
 * <li>Là où l'on peut se servir de WorldEdit seulement ({@link BuildAccess}) : les opérateurs et les invités, dans
 * les ateliers.</li>
 * <li>La boîte de la sélection est copiée, contenu des coffres compris (sans les données des blocs de commande, comme
 * pour un fichier importé). Une sélection d'une autre forme que le pavé (polygone, ellipsoïde, cylindre…) ne garde que
 * ses propres cases : le reste de sa boîte reste vide, la pose n'y touchera pas.</li>
 * <li>Le joueur qui se tient dans la sélection fixe l'ancre : là où il est, c'est ce qui se trouvera sur le bloc visé à
 * la pose ; sinon, le milieu de la base.</li>
 * <li>L'enregistrement passe par l'API publique du catalogue ({@code StructureStore.save}), qui en garde les contrôles
 * (identifiant, taille, nombre de structures).</li>
 * </ul>
 */
final class SelectionToStructure {
	/** Les rayons du catalogue. */
	static final List<String> CATEGORIES = List.of("perso", "maisons", "place", "decor");

	private SelectionToStructure() {
	}

	/**
	 * Enregistre la sélection WorldEdit de ce joueur dans le catalogue.
	 *
	 * @param categorie rayon voulu, ou null ; vérifié, et rangé avec la structure quand le catalogue le permet
	 * @param repere    repère posé avec elle : {@code maison}, {@code salle}, ou vide
	 */
	static String save(WorldEditLink link, StructureStore store, ServerPlayer p, String id, String nom, String categorie, String repere) {
		BuildAccess access = BuildAccess.current();
		if (access == null || !access.canUseWorldEdit(p)) {
			throw new GameException(access == null ? "La construction n'est pas disponible sur ce serveur."
				: "On enregistre une sélection là où on se sert de WorldEdit. " + access.refusal(p));
		}
		String cat = categorie == null || categorie.isBlank() ? "perso" : categorie.strip().toLowerCase(Locale.ROOT);
		if (!CATEGORIES.contains(cat)) {
			throw new GameException("Catégorie inconnue : « " + categorie + " » (" + String.join(", ", CATEGORIES) + ").");
		}
		String r = repere == null || repere.equals("aucun") ? "" : repere;
		if (!StructureStore.MARKS.contains(r)) {
			throw new GameException("Repère : maison, salle ou aucun.");
		}
		WorldEditLink.Selection s = link.selection(p);
		if (s == null || !s.complete()) {
			throw new GameException("Aucune sélection WorldEdit complète : posez ses deux coins avec la baguette de construction "
				+ "(clic gauche, clic droit) ou //pos1 et //pos2.");
		}
		if (s.level() != p.level()) {
			throw new GameException("Votre sélection est dans un autre monde : sélectionnez la construction là où vous êtes.");
		}
		StructureStore.checkSize(s.width(), s.height(), s.length());
		Clip clip = Clip.capture(s.level(), s.min(), s.width(), s.height(), s.length());
		// Ce qui part dans le catalogue suit les mêmes règles que ce qui vient d'un fichier : pas de données de blocs de
		// commande ; et les cases hors d'une sélection qui n'est pas un pavé restent vides.
		int[] cells = clip.cells.clone();
		Map<Integer, CompoundTag> safe = new HashMap<>();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int i = 0;
		boolean shaped = !"cuboide".equals(s.shape());
		for (int y = 0; y < clip.height; y++) {
			for (int z = 0; z < clip.length; z++) {
				for (int x = 0; x < clip.width; x++, i++) {
					if (shaped && !s.inside().test(m.set(s.min().getX() + x, s.min().getY() + y, s.min().getZ() + z))) {
						cells[i] = Clip.EMPTY;
						continue;
					}
					CompoundTag tag = clip.data.get(i);
					if (tag != null) {
						CompoundTag t = Clip.safeData(clip.palette.get(clip.cells[i]), tag);
						if (t != null) {
							safe.put(i, t);
						}
					}
				}
			}
		}
		Clip stored = new Clip(clip.width, clip.height, clip.length, clip.palette, cells, safe);
		BlockPos feet = p.blockPosition();
		boolean inside = feet.getX() >= s.min().getX() && feet.getX() <= s.max().getX() && feet.getZ() >= s.min().getZ()
			&& feet.getZ() <= s.max().getZ() && feet.getY() >= s.min().getY() && feet.getY() <= s.max().getY() + 1;
		String saved = store.save(id, nom, stored, inside ? feet.subtract(s.min()) : null, r);
		StringBuilder out = new StringBuilder(saved);
		out.append(inside ? " Elle sera posée par rapport à l'endroit où vous vous tenez." : " Elle sera posée par le milieu de sa base.");
		if (shaped) {
			out.append(" Sélection « ").append(s.shape()).append(" » : seules ses cases sont gardées.");
		}
		if (!cat.equals("perso")) {
			// Le catalogue range les structures de l'utilisateur dans « Vos structures » : le rayon voulu est dit, pas gardé.
			out.append(" Elle est rangée dans « Vos structures » (le rayon « ").append(cat)
				.append(" » est réservé aux structures fournies pour l'instant).");
		}
		return out.toString();
	}
}
