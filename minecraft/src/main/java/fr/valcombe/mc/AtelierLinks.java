package fr.valcombe.mc;

import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldRegistry;

import java.util.List;
import java.util.function.Predicate;

/**
 * Le lien entre les ateliers carte et les cartes de la bibliothèque : chaque monde {@link WorldKind#ATELIER_CARTE}
 * porte, dans sa fiche du registre des mondes ({@link WorldEntry#carte()}), l'identifiant de la carte sur laquelle on y
 * travaille. <b>Un seul atelier par carte</b> : ouvrir une carte déjà en atelier mène à cet atelier.
 * <p>
 * Sans Minecraft : vérifié par les tests JUnit.
 */
public final class AtelierLinks {
	/** Identifiant d'une carte de la bibliothèque (même règle que MapStore). */
	static final String MAP_ID = "[a-z0-9-]{2,30}";

	private AtelierLinks() {
	}

	/** L'atelier carte qui porte cette carte, ou null (seuls les ateliers carte comptent : une partie la porte aussi). */
	public static WorldEntry atelierOf(List<WorldEntry> worlds, String mapId) {
		if (mapId == null) {
			return null;
		}
		for (WorldEntry e : worlds) {
			if (e.sorte() == WorldKind.ATELIER_CARTE && mapId.equals(e.carte())) {
				return e;
			}
		}
		return null;
	}

	/** Les ateliers carte, du plus ancien au plus récent. */
	public static List<WorldEntry> ateliers(List<WorldEntry> worlds) {
		return worlds.stream().filter(e -> e.sorte() == WorldKind.ATELIER_CARTE).toList();
	}

	/**
	 * Un identifiant de carte libre tiré de ce nom : sans accents, en minuscules, 30 signes au plus (« Port des Brumes »
	 * donne {@code port-des-brumes}, puis {@code port-des-brumes-2}...). Pris : ce que {@code taken} refuse (une carte de
	 * la bibliothèque, une carte d'un autre atelier). Un nom qui ne garde rien donne {@code carte}.
	 */
	public static String freeMapId(String name, Predicate<String> taken) {
		String base = WorldRegistry.slug(name);
		if (base.length() < 2) {
			base = "carte";
		}
		String id = base;
		for (int n = 2; taken.test(id); n++) {
			String suffix = "-" + n;
			String head = base.length() + suffix.length() > 30 ? base.substring(0, 30 - suffix.length()).replaceAll("-+$", "") : base;
			id = head + suffix;
		}
		return id;
	}

	/** Le nom d'une carte : 1 à 50 signes, sans code de mise en forme ; null s'il ne convient pas. */
	public static String cleanName(String name) {
		String n = name == null ? "" : name.strip().replaceAll("\\s+", " ");
		if (n.isEmpty() || n.length() > 50 || n.indexOf('§') >= 0 || n.chars().anyMatch(Character::isISOControl)) {
			return null;
		}
		return n;
	}
}
