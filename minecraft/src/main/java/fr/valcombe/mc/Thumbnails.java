package fr.valcombe.mc;

/**
 * Les vignettes des cartes et des structures (images PNG), pour les listes de l'Atelier web et de l'Atelier en jeu.
 * <p>
 * Le serveur peut en produire de vraies (un rendu des blocs) : il les donne en installant sa propre source avec
 * {@link #install}. Tant qu'aucune n'est installée, {@link #current} rend la source de repli
 * ({@link ThumbnailFallback}) : l'aperçu vu de dessus enregistré avec la carte, sinon un plan dessiné d'après ses
 * repères ; pour une structure, un volume en perspective à ses proportions. Une source installée peut ne pas savoir
 * faire une vignette (null) : la vignette de repli la remplace alors.
 * <p>
 * Toutes les méthodes s'appellent sur le fil du serveur.
 */
public interface Thumbnails {
	/** La vignette de la carte de la bibliothèque id (PNG), ou null si cette source n'en a pas. */
	byte[] map(String id);

	/** La vignette de la structure du catalogue id (PNG), ou null si cette source n'en a pas. */
	byte[] structure(String id);

	/** Les deux sortes de vignettes, telles que les nomme l'adresse de la page (« carte », « structure »). */
	enum Kind {
		CARTE, STRUCTURE;

		/** « carte » ou « structure » ; null pour autre chose. */
		static Kind of(String text) {
			return switch (text == null ? "" : text) {
				case "carte" -> CARTE;
				case "structure" -> STRUCTURE;
				default -> null;
			};
		}
	}

	/** La vignette de cette sorte : celle de la source, sinon null. */
	default byte[] of(Kind kind, String id) {
		return kind == Kind.CARTE ? map(id) : structure(id);
	}

	/** Installe la source des vignettes produites par le serveur (null : revenir à la seule source de repli). */
	static void install(Thumbnails source) {
		Holder.installed = source;
	}

	/** La source installée, ou null. */
	static Thumbnails installed() {
		return Holder.installed;
	}

	/**
	 * La vignette demandée : celle de la source installée si elle en a une, sinon celle de repli (fallback). Null si la
	 * carte ou la structure n'existe pas.
	 */
	static byte[] get(Kind kind, String id, Thumbnails fallback) {
		Thumbnails source = Holder.installed;
		byte[] png = null;
		if (source != null) {
			try {
				png = source.of(kind, id);
			} catch (RuntimeException e) {
				Holder.LOG.warn("Vignette {} {} : la source installée a échoué, vignette de repli", kind, id, e);
			}
		}
		return png != null ? png : fallback == null ? null : fallback.of(kind, id);
	}

	/** Où la source installée est gardée (une interface n'a pas de champ modifiable à elle). */
	final class Holder {
		private static volatile Thumbnails installed;
		private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("valcombe");

		private Holder() {
		}
	}
}
