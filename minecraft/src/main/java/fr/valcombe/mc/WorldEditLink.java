package fr.valcombe.mc;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Ce que Valcombe demande à WorldEdit. Seule {@link WorldEditBridge} touche à l'API de WorldEdit (chargée seulement si
 * le mod est là) ; sans WorldEdit, {@link #NONE} répond « rien » partout et le reste du mod marche quand même.
 */
interface WorldEditLink {
	/**
	 * La sélection WorldEdit d'un joueur, telle que Valcombe la montre et l'enregistre.
	 *
	 * @param level  le monde de la sélection
	 * @param shape  sa forme : {@code cuboide}, {@code polygone}, {@code ellipsoide}, {@code cylindre}, {@code sphere},
	 *               {@code enveloppe} (convexe) ; une forme autre que le pavé est montrée par sa boîte englobante
	 * @param min    coin le plus bas de sa boîte englobante (null tant qu'elle n'est pas complète)
	 * @param max    coin le plus haut de sa boîte englobante (null tant qu'elle n'est pas complète)
	 * @param first  premier point posé (clic gauche de la baguette, //pos1), ou null
	 * @param second second point (clic droit, //pos2), ou null ; seulement pour un pavé
	 * @param volume nombre de blocs de la sélection (0 si elle n'est pas complète)
	 * @param inside vrai pour une case de la boîte englobante qui est dans la sélection (toujours vrai pour un pavé)
	 */
	record Selection(ServerLevel level, String shape, BlockPos min, BlockPos max, BlockPos first, BlockPos second, long volume,
			java.util.function.Predicate<BlockPos> inside) {
		/** La boîte est-elle complète (deux coins pour un pavé, assez de points pour les autres formes) ? */
		boolean complete() {
			return min != null && max != null;
		}

		int width() {
			return max.getX() - min.getX() + 1;
		}

		int height() {
			return max.getY() - min.getY() + 1;
		}

		int length() {
			return max.getZ() - min.getZ() + 1;
		}
	}

	/** Branche ce lien au démarrage du serveur (réglages de WorldEdit, écouteurs) ; rien sans WorldEdit. */
	void start(MinecraftServer server);

	/** Remet les réglages que Valcombe donne à WorldEdit (sa baguette), au cas où sa configuration a été relue. */
	void applySettings();

	/** WorldEdit est-il là ? */
	boolean present();

	/** Ce mot (sans la barre de tête de la commande) est-il une commande de WorldEdit ? ({@code /set} pour //set.) */
	boolean isCommand(String root);

	/** La sélection de ce joueur, ou null s'il n'en a pas commencé (ou sans WorldEdit). */
	Selection selection(ServerPlayer p);

	/**
	 * Les modifications WorldEdit de ce joueur qu'on peut encore annuler, de la plus ancienne à la plus récente : des
	 * objets que l'on ne fait que comparer (par identité) pour savoir lesquelles sont nouvelles. Liste vide sans WorldEdit.
	 */
	List<Object> history(ServerPlayer p);

	/** Annule la dernière modification WorldEdit de ce joueur ; la phrase à lui dire, ou null s'il n'y avait rien. */
	String undo(ServerPlayer p);

	/** Rétablit la dernière modification WorldEdit annulée de ce joueur ; la phrase à lui dire, ou null s'il n'y avait rien. */
	String redo(ServerPlayer p);

	/** Sans WorldEdit : aucune commande, aucune sélection, rien à annuler. */
	WorldEditLink NONE = new WorldEditLink() {
		@Override
		public void start(MinecraftServer server) {
		}

		@Override
		public void applySettings() {
		}

		@Override
		public boolean present() {
			return false;
		}

		@Override
		public boolean isCommand(String root) {
			return false;
		}

		@Override
		public Selection selection(ServerPlayer p) {
			return null;
		}

		@Override
		public List<Object> history(ServerPlayer p) {
			return List.of();
		}

		@Override
		public String undo(ServerPlayer p) {
			return null;
		}

		@Override
		public String redo(ServerPlayer p) {
			return null;
		}
	};
}
