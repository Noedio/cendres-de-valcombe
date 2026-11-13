package fr.valcombe.backup;

/**
 * Sorte d'une sauvegarde : elle se lit dans le nom de l'archive ({@code valcombe-2026-11-13_09-12-44-auto.zip}) et
 * décide si l'archive peut être effacée par la rotation (les plus anciennes au-delà du nombre gardé).
 */
public enum BackupKind {
	/** Faite toute seule, à intervalle régulier. */
	AUTO("auto", "automatique", true),
	/** Faite à l'arrêt du serveur. */
	ARRET("arret", "à l'arrêt du serveur", true),
	/** Demandée par une personne (commande ou API) : jamais effacée par la rotation, seulement à la main. */
	MANUELLE("manuelle", "manuelle", false),
	/** L'état du serveur juste avant une restauration : de quoi revenir en arrière. */
	AVANT_RESTAURATION("avant-restauration", "avant une restauration", true);

	private final String id;
	private final String label;
	private final boolean rotated;

	BackupKind(String id, String label, boolean rotated) {
		this.id = id;
		this.label = label;
		this.rotated = rotated;
	}

	/** Le mot qui la désigne dans le nom des archives et dans l'API (« auto », « manuelle »...). */
	public String id() {
		return id;
	}

	/** Libellé pour les messages (« automatique », « à l'arrêt du serveur »...). */
	public String label() {
		return label;
	}

	/** La rotation peut-elle effacer les archives de cette sorte ? */
	public boolean rotated() {
		return rotated;
	}

	/** La sorte désignée par ce mot, ou null. */
	public static BackupKind fromId(String id) {
		for (BackupKind k : values()) {
			if (k.id.equals(id)) {
				return k;
			}
		}
		return null;
	}
}
