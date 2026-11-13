package fr.valcombe.client.hd;

/**
 * Défilement vertical d'un contenu plus haut que sa fenêtre : position bornée, taille et place du curseur de
 * l'ascenseur, glisser du curseur. Les longueurs sont dans l'unité qu'on veut (unités d'interface pour les
 * listes). Aucune dépendance à Minecraft : testé par JUnit.
 */
public final class Scroll {
	private double content;
	private double viewport;
	private double offset;

	/** Longueur minimale du curseur, en fraction du rail (pour qu'il reste saisissable). */
	private static final double MIN_THUMB = 0.12;

	public Scroll() {
	}

	/** Fixe la hauteur du contenu et de la fenêtre, puis borne la position. */
	public Scroll resize(double contentLength, double viewportLength) {
		this.content = Math.max(0, contentLength);
		this.viewport = Math.max(0, viewportLength);
		this.offset = clamp(offset);
		return this;
	}

	public double offset() {
		return offset;
	}

	/** Position la plus basse possible. */
	public double max() {
		return Math.max(0, content - viewport);
	}

	/** Vrai si le contenu dépasse : l'ascenseur s'affiche. */
	public boolean scrollable() {
		return content > viewport + 1e-6;
	}

	public void setOffset(double value) {
		offset = clamp(value);
	}

	/** Fait défiler de delta (positif : vers le bas) ; vrai si la position a changé. */
	public boolean scrollBy(double delta) {
		double before = offset;
		offset = clamp(offset + delta);
		return offset != before;
	}

	/** Amène la tranche [top, bottom] du contenu dans la fenêtre, en bougeant le moins possible. */
	public void reveal(double top, double bottom) {
		if (top < offset) {
			offset = clamp(top);
		} else if (bottom > offset + viewport) {
			offset = clamp(bottom - viewport);
		}
	}

	/** Longueur du curseur pour un rail de cette longueur. */
	public double thumbLength(double track) {
		if (!scrollable()) {
			return track;
		}
		return Math.min(track, Math.max(track * MIN_THUMB, track * viewport / content));
	}

	/** Position du haut du curseur sur un rail de cette longueur. */
	public double thumbStart(double track) {
		double free = track - thumbLength(track);
		return max() <= 0 ? 0 : free * offset / max();
	}

	/** Place le contenu pour que le haut du curseur soit à thumbTop sur le rail (glisser du curseur). */
	public void dragThumbTo(double thumbTop, double track) {
		double free = track - thumbLength(track);
		setOffset(free <= 0 ? 0 : thumbTop / free * max());
	}

	private double clamp(double v) {
		return Math.max(0, Math.min(max(), v));
	}
}
