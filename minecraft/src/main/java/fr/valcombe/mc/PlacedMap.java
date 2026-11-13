package fr.valcombe.mc;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Une carte de la bibliothèque posée dans un monde : ce qu'en garde le jeu pour s'en servir (repères en coordonnées
 * du monde, réglages du village, emprise). C'est ce que renvoie MapPlacer.place, et ce qu'il retient pour chaque
 * monde de Valcombe (fichier valcombe-carte.json dans le dossier du monde : il disparaît quand le monde est remis à neuf).
 *
 * @param mapId     identifiant de la carte dans la bibliothèque
 * @param nom       nom de la carte
 * @param dimension monde où elle est posée (null : pas encore posée, ou pour un calcul)
 * @param marks     repères, en coordonnées du monde
 * @param genere    village généré (ses trônes sculptés sont des entités, reposées avec la carte)
 * @param theme     thème du village
 * @param places    nombre de places prévu par le générateur
 * @param taille    taille du village généré (0 petite, 1 normale, 2 grande)
 * @param maisons   village généré avec ses maisons
 * @param salle     village généré avec sa salle d'attente
 * @param rayon     demi-largeur de l'emprise, en blocs, autour du centre
 * @param hauteur   hauteur de l'emprise, les MapStore.BELOW rangées sous la place comprises
 */
public record PlacedMap(String mapId, String nom, ResourceKey<Level> dimension, MapMarks marks, boolean genere, String theme,
		int places, int taille, boolean maisons, boolean salle, int rayon, int hauteur) {

	/** La carte de cette fiche, sa place posée en center dans le monde dimension. */
	public static PlacedMap of(MapStore.MapDef d, ResourceKey<Level> dimension, BlockPos center) {
		return new PlacedMap(d.id, d.nom, dimension, MapMarks.of(d, center), d.genere, d.theme, d.places, d.taille, d.maisons, d.salle,
			d.rayon, d.height());
	}

	/** Le centre de la place (le bloc du sol). */
	public BlockPos center() {
		return marks.center();
	}

	/** Coin le plus bas de l'emprise : là où se pose le premier bloc de la structure. */
	public BlockPos min() {
		return center().offset(-rayon, -MapStore.BELOW, -rayon);
	}

	/** Coin le plus haut de l'emprise (compris). */
	public BlockPos max() {
		return center().offset(rayon, hauteur - MapStore.BELOW - 1, rayon);
	}

	/** Ce bloc est-il dans l'emprise de la carte ? */
	public boolean contains(BlockPos p) {
		BlockPos a = min();
		BlockPos b = max();
		return p.getX() >= a.getX() && p.getX() <= b.getX() && p.getY() >= a.getY() && p.getY() <= b.getY()
			&& p.getZ() >= a.getZ() && p.getZ() <= b.getZ();
	}

	/** La même carte, sous un autre identifiant et un autre nom (une capture rangée dans la bibliothèque). */
	public PlacedMap renamed(String id, String name) {
		return new PlacedMap(id, name, dimension, marks, genere, theme, places, taille, maisons, salle, rayon, hauteur);
	}

	/** La même carte, avec une autre emprise (rayon et hauteur). */
	public PlacedMap resized(int newRayon, int newHauteur) {
		return new PlacedMap(mapId, nom, dimension, marks, genere, theme, places, taille, maisons, salle, newRayon, newHauteur);
	}

	/** La fiche de bibliothèque de cette carte : réglages, emprise, repères relatifs au centre. */
	public MapStore.MapDef toDef() {
		MapStore.MapDef d = new MapStore.MapDef();
		d.id = mapId;
		d.nom = nom;
		d.genere = genere;
		d.theme = theme;
		d.places = places;
		d.taille = taille;
		d.maisons = maisons;
		d.salle = salle;
		d.rayon = rayon;
		d.hauteur = hauteur;
		marks.writeTo(d);
		return d;
	}

	/**
	 * Le village de jeu de cette carte, à sa place dans le monde (trônes, maisons, salle de convocation, orientations,
	 * nom dans la bibliothèque) : ce dont la partie se servira quand elle aura son propre monde.
	 */
	public Village toVillage() {
		MapStore.MapDef d = toDef();
		Village v = Village.restored(center(), genere, theme, places, taille, maisons, salle, d.sieges, d.logis, d.convocation);
		v.setLibrary(mapId, nom);
		v.setRelSeatYaws(d.orientations);
		return v;
	}

	/** Les repères relatifs au centre, dans l'ordre : trônes, puis maisons, puis salle de convocation (pour comparer deux poses). */
	public List<int[]> relativeMarks() {
		List<int[]> out = new ArrayList<>();
		marks.seats().forEach(s -> out.add(marks.rel(s.pos())));
		marks.houses().forEach(h -> out.add(marks.rel(h)));
		if (marks.convocation() != null) {
			out.add(marks.rel(marks.convocation()));
		}
		return out;
	}
}
