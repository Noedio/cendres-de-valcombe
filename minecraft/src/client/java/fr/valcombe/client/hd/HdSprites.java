package fr.valcombe.client.hd;

/**
 * Les textures de la boîte à outils (textures/gui/hd/), produites par tools/make_ui.py à 8 pixels par unité
 * d'interface. Chaque texture « 9 tranches » garde ses coins à taille fixe et étire ses bords et son centre :
 * les bords sont donnés ici en pixels de texture. Aucune dépendance à Minecraft : un test JUnit vérifie que
 * ces dimensions correspondent aux images du dépôt.
 */
public final class HdSprites {
	/** Pixels de texture par unité d'interface. */
	public static final int DENSITY = 8;

	/**
	 * Une texture et ses bords « 9 tranches » (gauche, haut, droite, bas), en pixels de texture.
	 *
	 * @param repeat vrai pour une texture en mosaïque (le parchemin), dessinée en répétition
	 */
	public record Sprite(String name, int width, int height, int left, int top, int right, int bottom, int density,
			boolean repeat) {
		/** Chemin de la ressource, dans l'espace valcombe. */
		public String path() {
			return "textures/gui/hd/" + name + ".png";
		}

		/** Bord gauche en unités d'interface. */
		public float leftUnits() {
			return left / (float) density;
		}

		public float topUnits() {
			return top / (float) density;
		}

		public float rightUnits() {
			return right / (float) density;
		}

		public float bottomUnits() {
			return bottom / (float) density;
		}

		/** Largeur de la texture en unités d'interface (taille d'un carreau pour une mosaïque). */
		public float widthUnits() {
			return width / (float) density;
		}

		public float heightUnits() {
			return height / (float) density;
		}
	}

	private static Sprite nine(String name, int wUnits, int hUnits, int border) {
		int b = border * DENSITY;
		return new Sprite(name, wUnits * DENSITY, hUnits * DENSITY, b, b, b, b, DENSITY, false);
	}

	private static Sprite nine(String name, int wUnits, int hUnits, int horizontal, int vertical) {
		int bh = horizontal * DENSITY;
		int bv = vertical * DENSITY;
		return new Sprite(name, wUnits * DENSITY, hUnits * DENSITY, bh, bv, bh, bv, DENSITY, false);
	}

	private static Sprite image(String name, int wUnits, int hUnits) {
		return new Sprite(name, wUnits * DENSITY, hUnits * DENSITY, 0, 0, 0, 0, DENSITY, false);
	}

	/** Parchemin en mosaïque : un carreau de 64 unités, à 4 pixels par unité. */
	public static final Sprite PARCHEMIN = new Sprite("parchemin", 256, 256, 0, 0, 0, 0, 4, true);
	/** Cadre de panneau (bois lie-de-vin, filet de bronze, écoinçons) ; centre transparent. */
	public static final Sprite CADRE = nine("cadre", 32, 32, 10);
	public static final Sprite BOUTON = nine("bouton", 32, 20, 5);
	public static final Sprite BOUTON_SURVOL = nine("bouton_survol", 32, 20, 5);
	public static final Sprite BOUTON_INACTIF = nine("bouton_inactif", 32, 20, 5);
	public static final Sprite CHAMP = nine("champ", 32, 16, 4);
	public static final Sprite CHAMP_ACTIF = nine("champ_actif", 32, 16, 4);
	public static final Sprite LISTE = nine("liste", 32, 32, 4);
	public static final Sprite ASCENSEUR_RAIL = nine("ascenseur_rail", 8, 16, 4);
	public static final Sprite ASCENSEUR = nine("ascenseur", 8, 16, 4);
	public static final Sprite ASCENSEUR_SURVOL = nine("ascenseur_survol", 8, 16, 4);
	public static final Sprite ONGLET = nine("onglet", 32, 16, 5);
	public static final Sprite ONGLET_SURVOL = nine("onglet_survol", 32, 16, 5);
	public static final Sprite ONGLET_ACTIF = nine("onglet_actif", 32, 16, 5);
	public static final Sprite INFOBULLE = nine("infobulle", 16, 16, 5);
	/** Bandeau de titre en queue d'aronde, dessiné à sa hauteur de 16 unités. */
	public static final Sprite BANDEAU = nine("bandeau", 64, 16, 12, 8);
	public static final Sprite INTERRUPTEUR_NON = nine("interrupteur_non", 20, 10, 5);
	public static final Sprite INTERRUPTEUR_OUI = nine("interrupteur_oui", 20, 10, 5);
	public static final Sprite PASTILLE = image("pastille", 10, 10);
	public static final Sprite ANNEAU_CHOISI = image("anneau_choisi", 32, 32);
	public static final Sprite ANNEAU_SURVOL = image("anneau_survol", 32, 32);
	public static final Sprite OMBRE_RONDE = image("ombre_ronde", 32, 32);
	public static final Sprite LOSANGE = image("losange", 8, 8);

	/** Toutes les textures, pour les vérifications. */
	public static final Sprite[] ALL = {PARCHEMIN, CADRE, BOUTON, BOUTON_SURVOL, BOUTON_INACTIF, CHAMP, CHAMP_ACTIF, LISTE,
		ASCENSEUR_RAIL, ASCENSEUR, ASCENSEUR_SURVOL, ONGLET, ONGLET_SURVOL, ONGLET_ACTIF, INFOBULLE, BANDEAU,
		INTERRUPTEUR_NON, INTERRUPTEUR_OUI, PASTILLE, ANNEAU_CHOISI, ANNEAU_SURVOL, OMBRE_RONDE, LOSANGE};

	private HdSprites() {
	}
}
