package fr.valcombe.mc.worlds;

import fr.valcombe.rules.GameException;

import java.util.function.Supplier;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

/**
 * <b>Le point unique « peut construire ici »</b> : qui casse et pose des blocs, et qui se sert de WorldEdit, dans
 * quel monde. Tout ce qui construit passe par ici (WorldEdit, outils de repères et structures du catalogue dans les
 * ateliers, sélection vers structure, « annuler » commun) ; une règle de construction ne s'écrit nulle part ailleurs.
 *
 * <h2>Les règles</h2>
 * <table>
 * <caption>Qui construit où</caption>
 * <tr><th>Monde</th><th>Casser et poser des blocs</th><th>WorldEdit</th></tr>
 * <tr><td>Atelier carte, atelier structures</td><td>opérateurs et invités de cet atelier (en mode créatif, donné
 * à l'arrivée)</td><td>opérateurs et invités de cet atelier</td></tr>
 * <tr><td>Partie d'essai</td><td>opérateur en mode créatif</td><td>opérateurs, si le réglage
 * {@link BuildInvitations#worldEditInTrials()} est ouvert ({@code /vc atelier worldedit-essais oui})</td></tr>
 * <tr><td>Hub, monde de partie (« Partie principale » comme les autres), monde principal de Minecraft</td><td>opérateur en mode créatif (comme
 * avant)</td><td>personne</td></tr>
 * </table>
 * Les invitations (voir {@link BuildInvitations}) ne valent que pour les ateliers : un invité ne construit jamais dans
 * le hub ni dans une partie. Un invité voit aussi, depuis le hub, les ateliers où il est invité ({@link #canSee}).
 *
 * <h2>S'en servir</h2>
 * {@link #current()} donne l'instance du serveur en marche (null avant son démarrage, ou sans les mondes de Valcombe :
 * on retombe alors sur {@link #rights} avec un monde inconnu). Les méthodes sont faites pour le fil du serveur :
 * <ul>
 * <li>{@link #canBuild(ServerPlayer)} : ce joueur peut-il casser et poser des blocs là où il est ?</li>
 * <li>{@link #canUseWorldEdit(ServerPlayer)} : peut-il se servir de WorldEdit là où il est ?</li>
 * <li>{@link #inWorkshop(ServerPlayer)} : est-il dans un atelier où il construit (mode créatif, baguette) ?</li>
 * <li>{@link #refusal(ServerPlayer)} : la phrase à lui dire quand c'est non.</li>
 * </ul>
 * La décision elle-même est {@link #rights(WorldKind, boolean, boolean, boolean, boolean)}, sans état, vérifiée par les
 * tests JUnit.
 */
public final class BuildAccess {
	/**
	 * Ce qu'un joueur peut faire dans un monde.
	 *
	 * @param build     casser et poser des blocs, poser des structures et des repères
	 * @param worldEdit se servir de WorldEdit (commandes, baguette, outils)
	 * @param workshop  c'est un atelier où il construit : il y reçoit le mode créatif et la baguette
	 */
	public record Rights(boolean build, boolean worldEdit, boolean workshop) {
		public static final Rights NONE = new Rights(false, false, false);
	}

	private static volatile BuildAccess current;

	private final Supplier<WorldManager> worlds;
	private final BuildInvitations invitations;

	/**
	 * @param worlds      le gestionnaire de mondes (null tant qu'il n'est pas prêt, ou s'il n'a pas démarré)
	 * @param invitations les invitations de ce serveur, déjà lues
	 */
	public BuildAccess(Supplier<WorldManager> worlds, BuildInvitations invitations) {
		this.worlds = worlds;
		this.invitations = invitations;
	}

	/** L'accès du serveur en marche, ou null. */
	public static BuildAccess current() {
		return current;
	}

	/** Branche (ou débranche, avec null) l'accès du serveur : au démarrage et à l'arrêt. */
	public static void install(BuildAccess access) {
		current = access;
	}

	public BuildInvitations invitations() {
		return invitations;
	}

	// ------------------------------------------------------------------
	// La règle
	// ------------------------------------------------------------------

	/**
	 * Ce que permet un monde de cette sorte.
	 *
	 * @param kind              la sorte du monde, ou null pour un monde qui n'est pas de Valcombe (le monde principal de Minecraft)
	 * @param operator          le joueur est opérateur
	 * @param creative          il est en mode créatif
	 * @param invited           il est invité dans ce monde (n'a de sens que pour un atelier)
	 * @param worldEditInTrials WorldEdit est ouvert aux opérateurs dans les parties d'essai
	 */
	public static Rights rights(WorldKind kind, boolean operator, boolean creative, boolean invited, boolean worldEditInTrials) {
		if (kind != null && kind.workshop()) {
			boolean ok = operator || invited;
			return new Rights(ok, ok, ok);
		}
		boolean hands = operator && creative;
		if (kind == WorldKind.ESSAI) {
			return new Rights(hands, operator && worldEditInTrials, false);
		}
		// Hub, mondes de partie, monde principal de Minecraft : comme avant, l'opérateur en créatif seulement, et jamais WorldEdit.
		return new Rights(hands, false, false);
	}

	/** Un opérateur du serveur (niveau des commandes de jeu, celui de /gamemode). */
	public static boolean operator(ServerPlayer p) {
		return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	/** La fiche du monde de Valcombe de ce niveau, ou null (monde principal de Minecraft, Nether, End, ou mondes indisponibles). */
	public WorldEntry entryOf(Level level) {
		WorldManager w = worlds.get();
		return w == null || level == null ? null : w.entryOf(level);
	}

	/** Ce joueur est-il invité dans ce monde ? (Faux pour tout ce qui n'est pas un atelier.) */
	public boolean invited(ServerPlayer p, WorldEntry e) {
		return e != null && e.sorte().workshop() && invitations.invited(p.getUUID(), p.getScoreboardName(), e.id());
	}

	/** Ce que ce joueur peut faire dans ce monde (null : un monde hors Valcombe, comme le monde principal de Minecraft). */
	public Rights rights(ServerPlayer p, WorldEntry e) {
		boolean creative = p.gameMode.getGameModeForPlayer() == GameType.CREATIVE;
		return rights(e == null ? null : e.sorte(), operator(p), creative, invited(p, e), invitations.worldEditInTrials());
	}

	/** Ce que ce joueur peut faire là où il est. */
	public Rights rights(ServerPlayer p) {
		return rights(p, entryOf(p.level()));
	}

	// ------------------------------------------------------------------
	// Les questions qu'on pose
	// ------------------------------------------------------------------

	/** Ce joueur peut-il casser et poser des blocs là où il est ? */
	public boolean canBuild(ServerPlayer p) {
		return rights(p).build();
	}

	/** Ce joueur peut-il se servir de WorldEdit là où il est ? */
	public boolean canUseWorldEdit(ServerPlayer p) {
		return rights(p).worldEdit();
	}

	/** Ce joueur est-il dans un atelier où il construit ? (Mode créatif et baguette de construction.) */
	public boolean inWorkshop(ServerPlayer p) {
		return rights(p).workshop();
	}

	/**
	 * Ce joueur peut-il construire dans ce monde ? C'est la question des outils d'un atelier (parcelles de l'atelier
	 * structures, pupitres) : opérateurs et invités de l'atelier ; ailleurs, la règle générale ({@link #rights}). Un
	 * atelier pas encore créé (null), ou un serveur sans accès installé : les opérateurs seulement.
	 */
	public static boolean mayBuildIn(ServerPlayer p, WorldEntry e) {
		if (p == null) {
			return false;
		}
		BuildAccess a = current;
		if (e == null || a == null) {
			return operator(p);
		}
		return a.rights(p, e).build();
	}

	/** Comme {@link #mayBuildIn}, mais une {@link GameException} (la phrase pour le joueur) si ce n'est pas permis. */
	public static void requireBuildIn(ServerPlayer p, WorldEntry e) {
		if (!mayBuildIn(p, e)) {
			String where = e == null ? "ici" : "dans « " + e.nom() + " »";
			throw new GameException("Construire " + where + " est réservé aux opérateurs et à leurs invités"
				+ (e != null && e.sorte().workshop() ? " : un opérateur peut t'y inviter (/vc atelier inviter)." : "."));
		}
	}

	/**
	 * Ce joueur peut-il casser <b>et poser</b> des blocs dans ce monde ? La même règle pour les deux gestes : dans un monde
	 * de Valcombe, {@link #canBuild} ; ailleurs (monde principal de Minecraft) ou sans accès installé, celle de Minecraft.
	 * C'est ce que vérifient la casse (événement de Fabric) et la pose (ItemStack.useOn, seaux : voir les mixins
	 * {@code PlaceGuardMixin} et {@code BucketGuardMixin}).
	 */
	public static boolean mayModify(ServerPlayer p, Level level) {
		BuildAccess a = current;
		return a == null || a.entryOf(level) == null || a.canBuild(p);
	}

	/** Dernière fois qu'on a dit à un joueur qu'il ne pose rien ici (nanosecondes), pour ne le dire qu'une fois toutes les 2 s. */
	private static final java.util.Map<java.util.UUID, Long> HINTED = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Une pose refusée : le joueur l'apprend au-dessus de sa barre d'objets (au plus une fois toutes les deux secondes), et
	 * son inventaire lui est renvoyé (son jeu avait déjà retiré le bloc de sa main).
	 */
	public static void placeRefused(ServerPlayer p) {
		long now = System.nanoTime();
		Long last = HINTED.get(p.getUUID());
		if (last == null || now - last > 2_000_000_000L) {
			HINTED.put(p.getUUID(), now);
			BuildAccess a = current;
			String why = a == null ? null : a.buildRefusal(p);
			if (why != null) {
				p.sendSystemMessage(net.minecraft.network.chat.Component.literal(why).withStyle(net.minecraft.ChatFormatting.GOLD), true);
			}
		}
		p.containerMenu.sendAllDataToRemote();
	}

	/** Ce joueur a-t-il au moins une invitation ? Les commandes de construction lui sont alors proposées partout. */
	public boolean guestSomewhere(ServerPlayer p) {
		return invitations.invitedSomewhere(p.getUUID(), p.getScoreboardName());
	}

	/**
	 * Ce joueur voit-il ce monde dans le menu « Mondes », les portes du hub et {@code /vc monde aller} ? Les opérateurs
	 * voient tout ; les autres, le hub et les mondes de partie, plus les ateliers où ils sont invités.
	 */
	public boolean canSee(ServerPlayer p, WorldEntry e) {
		return e != null && (operator(p) || e.sorte().openToAll() || invited(p, e));
	}

	/** Pourquoi ce joueur ne peut pas se servir de WorldEdit là où il est (la phrase à lui dire), ou null s'il le peut. */
	public String refusal(ServerPlayer p) {
		WorldEntry e = entryOf(p.level());
		Rights r = rights(p, e);
		if (r.worldEdit()) {
			return null;
		}
		String go = " Menu J › Mondes pour y aller.";
		if (e == null) {
			return "WorldEdit ne sert que dans les ateliers (carte ou structures) : pas ici, hors des mondes de Valcombe." + go;
		}
		return switch (e.sorte()) {
			case HUB -> "WorldEdit ne sert que dans les ateliers (carte ou structures) : au hub, on ne construit pas." + go;
			case PARTIE -> "WorldEdit ne sert que dans les ateliers, pas dans un monde de partie : une carte se prépare dans "
				+ "un atelier carte." + go;
			case ESSAI -> operator(p)
				? "WorldEdit n'est pas ouvert dans les parties d'essai. Pour l'ouvrir aux opérateurs : /vc atelier worldedit-essais oui."
				: "WorldEdit ne sert que dans les ateliers : pas dans une partie d'essai." + go;
			case ATELIER_CARTE, ATELIER_STRUCTURES -> "Tu n'es pas invité à construire dans « " + e.nom()
				+ " » : un opérateur peut t'inviter (/vc atelier inviter " + p.getScoreboardName() + " " + e.id() + ").";
		};
	}

	/** Pourquoi ce joueur ne peut pas construire là où il est, ou null s'il le peut. */
	public String buildRefusal(ServerPlayer p) {
		WorldEntry e = entryOf(p.level());
		if (rights(p, e).build()) {
			return null;
		}
		if (e != null && e.sorte().workshop()) {
			return refusal(p);
		}
		return "On ne construit ici qu'en opérateur, en mode créatif. Les constructions se préparent dans les ateliers "
			+ "(Menu J › Mondes).";
	}
}
