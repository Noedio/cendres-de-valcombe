package fr.valcombe.mc.worlds;

import fr.valcombe.rules.GameException;

import java.util.function.BiPredicate;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * <b>Le point unique</b> qui décide si un joueur peut construire dans un monde de Valcombe : enregistrer une
 * parcelle de l'atelier structures, poser, tourner, supprimer, et demain tout ce que les ateliers permettront.
 * Chaque outil des ateliers passe par {@link #canBuild} (ou {@link #require}) au lieu de tester lui-même les droits.
 * <ul>
 * <li>Les opérateurs construisent partout.</li>
 * <li>Dans un atelier ({@link WorldKind#workshop()}), un joueur invité par un opérateur construit aussi : c'est la
 * règle des invitations, que le module des invitations branche par {@link #setGuests} (rien d'autre à changer ici
 * ni chez les appelants).</li>
 * <li>Ailleurs (hub, mondes de partie), les autres joueurs ne construisent pas avec les outils de Valcombe.</li>
 * </ul>
 * Sans Minecraft lancé pour le calcul lui-même : les tests JUnit vérifient la règle avec {@link #decide}.
 */
public final class BuildRights {
	/** Les invités d'un atelier : ce joueur a-t-il été invité à construire dans ce monde ? Personne par défaut. */
	private static volatile BiPredicate<ServerPlayer, WorldEntry> guests = (player, world) -> false;

	private BuildRights() {
	}

	/**
	 * Branche la règle des invitations (null : plus d'invités). Elle n'est demandée que pour les ateliers, et
	 * jamais pour un opérateur.
	 */
	public static void setGuests(BiPredicate<ServerPlayer, WorldEntry> rule) {
		guests = rule == null ? (player, world) -> false : rule;
	}

	/** Ce joueur est-il opérateur (niveau des commandes de jeu) ? */
	public static boolean operator(ServerPlayer player) {
		return player != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	/**
	 * Ce joueur peut-il construire dans ce monde de Valcombe ? world : sa fiche (null : le monde principal ou un
	 * monde qui n'est pas à Valcombe, où seuls les opérateurs construisent).
	 */
	public static boolean canBuild(ServerPlayer player, WorldEntry world) {
		if (player == null) {
			return false;
		}
		boolean op = operator(player);
		return decide(op, world == null ? null : world.sorte(), () -> guests.test(player, world));
	}

	/** La règle elle-même : opérateur, ou invité dans un atelier. */
	static boolean decide(boolean operator, WorldKind kind, java.util.function.BooleanSupplier invited) {
		if (operator) {
			return true;
		}
		return kind != null && kind.workshop() && invited.getAsBoolean();
	}

	/** Comme {@link #canBuild}, mais une {@link GameException} (message pour le joueur) si ce n'est pas permis. */
	public static void require(ServerPlayer player, WorldEntry world) {
		if (!canBuild(player, world)) {
			String where = world == null ? "ici" : "dans « " + world.nom() + " »";
			throw new GameException("Construire " + where + " est réservé aux opérateurs et à leurs invités.");
		}
	}
}
