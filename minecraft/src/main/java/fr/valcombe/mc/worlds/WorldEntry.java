package fr.valcombe.mc.worlds;

import java.time.Instant;

/**
 * Fiche d'un monde de Valcombe, telle qu'enregistrée dans {@code config/valcombe/mondes.json}.
 * Une fiche ne change pas : le registre en donne une nouvelle à chaque modification.
 *
 * @param id      identifiant court, sans accents ({@code [a-z0-9-]}, 2 à 30 signes). Il nomme le monde
 *                Minecraft {@code valcombe:<id>} et son dossier : il ne change jamais, même quand on renomme.
 * @param nom     nom affiché aux joueurs (1 à 50 signes)
 * @param sorte   hub, partie, essai, atelier carte ou atelier structures
 * @param carte   identifiant de la carte de la bibliothèque que porte ce monde, ou null
 * @param cree    date de création de la fiche
 * @param modifie date de la dernière modification (nom, carte, état, recréation)
 * @param etat    état libre, à l'usage des autres parties du mod (« en cours », « en pause »...) ; jamais null
 */
public record WorldEntry(String id, String nom, WorldKind sorte, String carte, Instant cree, Instant modifie, String etat) {
	public WorldEntry {
		etat = etat == null ? "" : etat;
	}

	/** Le nom du monde Minecraft : {@code valcombe:<id>}. */
	public String dimension() {
		return WorldRegistry.NAMESPACE + ":" + id;
	}

	WorldEntry withNom(String nom, Instant now) {
		return new WorldEntry(id, nom, sorte, carte, cree, now, etat);
	}

	WorldEntry withCarte(String carte, Instant now) {
		return new WorldEntry(id, nom, sorte, carte, cree, now, etat);
	}

	WorldEntry withEtat(String etat, Instant now) {
		return new WorldEntry(id, nom, sorte, carte, cree, now, etat);
	}

	WorldEntry touched(Instant now) {
		return new WorldEntry(id, nom, sorte, carte, cree, now, etat);
	}
}
