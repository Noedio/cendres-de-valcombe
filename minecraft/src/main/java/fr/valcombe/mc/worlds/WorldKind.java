package fr.valcombe.mc.worlds;

import java.util.Locale;

/**
 * Sorte d'un monde de Valcombe. Elle décide de ses règles (heure figée, météo) et de ce qu'on y fait.
 * Le nom de la constante est celui écrit dans {@code mondes.json} : ne pas en renommer une.
 */
public enum WorldKind {
	/** L'accueil commun, où l'on choisit sa partie. Un seul hub. */
	HUB("hub", "hub", true, false),
	/** Le monde d'une vraie partie : la carte y est posée sur un sol plat neuf. */
	PARTIE("partie", "partie", false, false),
	/** Une partie d'essai, pour répéter une carte ou un scénario sans toucher à la vraie. */
	ESSAI("essai", "partie d'essai", false, false),
	/** Atelier d'une carte de la bibliothèque : on y construit, la carte n'est enregistrée qu'à la demande. */
	ATELIER_CARTE("atelier-carte", "atelier carte", false, true),
	/** L'atelier des structures : toutes les structures posées sur des parcelles. Un seul. */
	ATELIER_STRUCTURES("atelier-structures", "atelier structures", true, true);

	private final String code;
	private final String label;
	private final boolean unique;
	private final boolean workshop;

	WorldKind(String code, String label, boolean unique, boolean workshop) {
		this.code = code;
		this.label = label;
		this.unique = unique;
		this.workshop = workshop;
	}

	/** Le mot tapé dans les commandes : {@code /vc monde creer atelier-carte ...}. */
	public String code() {
		return code;
	}

	/** Le nom montré aux joueurs, en minuscules : « partie d'essai ». */
	public String label() {
		return label;
	}

	/** Une seule fiche de cette sorte peut exister (le hub, l'atelier structures). */
	public boolean unique() {
		return unique;
	}

	/**
	 * Monde d'atelier : midi fixe (l'horloge du monde est arrêtée) et jamais de pluie ni d'orage,
	 * pour construire dans une lumière toujours la même.
	 */
	public boolean workshop() {
		return workshop;
	}

	/**
	 * La sorte écrite par un joueur ou lue dans un fichier : le code des commandes ({@code atelier-carte}),
	 * le nom de la constante ({@code ATELIER_CARTE}) ou un mot court ({@code carte}, {@code structures}),
	 * sans tenir compte des majuscules ni des tirets ; null si rien ne correspond.
	 */
	public static WorldKind parse(String text) {
		if (text == null) {
			return null;
		}
		String t = text.strip().toLowerCase(Locale.ROOT).replace('_', '-');
		for (WorldKind k : values()) {
			if (k.code.equals(t) || k.name().toLowerCase(Locale.ROOT).replace('_', '-').equals(t)) {
				return k;
			}
		}
		return switch (t) {
			case "carte" -> ATELIER_CARTE;
			case "structures" -> ATELIER_STRUCTURES;
			case "accueil" -> HUB;
			default -> null;
		};
	}
}
