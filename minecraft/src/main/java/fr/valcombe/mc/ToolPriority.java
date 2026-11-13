package fr.valcombe.mc;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.fabricmc.fabric.api.permission.v1.PermissionEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

/**
 * Un outil de Valcombe en main ne déclenche que Valcombe, même avec WorldEdit installé.
 *
 * <p>WorldEdit réagit aux clics avec deux objets de Minecraft : sa baguette de sélection (la baguette de construction
 * de Valcombe, qui est à lui : voir WorldEditGate) et la boussole (sa baguette de navigation, qui téléporte). La
 * boussole est aussi l'objet de base de l'outil de carte « Carte : centre de la place ». Deux protections, sans
 * dépendre de WorldEdit :
 * <ul>
 *   <li>les écouteurs de clic de Valcombe sont inscrits dans une phase qui passe avant la phase par défaut, celle où
 *   WorldEdit (et tout autre mod) inscrit les siens : un outil de Valcombe consomme son clic avant eux ;</li>
 *   <li>le clic gauche dans le vide n'est pas un événement de Fabric (WorldEdit le lit dans les paquets du jeu) : on
 *   refuse donc à WorldEdit, par l'API de permissions de Fabric qu'il interroge, ses deux actions de navigation par
 *   objet tant qu'un outil de carte est en main. Ses commandes (/jumpto, /thru, //pos1...) restent permises.</li>
 * </ul>
 * La baguette de construction n'est pas un outil de carte : ses clics vont à WorldEdit, là où il est permis.
 */
final class ToolPriority {
	/** Phase des écouteurs de clic de Valcombe, avant {@link Event#DEFAULT_PHASE}. */
	static final Identifier PHASE = Identifier.fromNamespaceAndPath("valcombe", "outils");

	/**
	 * Permissions de WorldEdit refusées avec un outil de carte en main : la boussole qui téléporte là où l'on vise
	 * (clic droit) ou de l'autre côté du mur (clic gauche). WorldEdit les nomme « worldedit.navigation.jumpto.tool »
	 * et « worldedit.navigation.thru.tool », que l'API de Fabric reçoit sous cette forme.
	 */
	static final Set<Identifier> WORLDEDIT_ITEM_ACTIONS = Set.of(
		Identifier.fromNamespaceAndPath("worldedit", "navigation.jumpto.tool"),
		Identifier.fromNamespaceAndPath("worldedit", "navigation.thru.tool"));

	private ToolPriority() {
	}

	/** À appeler une fois, avant d'inscrire les écouteurs de clic de Valcombe dans {@link #PHASE}. */
	static void install() {
		UseItemCallback.EVENT.addPhaseOrdering(PHASE, Event.DEFAULT_PHASE);
		UseBlockCallback.EVENT.addPhaseOrdering(PHASE, Event.DEFAULT_PHASE);
		AttackBlockCallback.EVENT.addPhaseOrdering(PHASE, Event.DEFAULT_PHASE);
		// Avant tout écouteur : le joueur qui clique est l'auteur des gestes de construction que le clic va faire (voir
		// SharedUndo.acting). Rien n'est consommé ici.
		UseItemCallback.EVENT.addPhaseOrdering(ACTOR, PHASE);
		UseBlockCallback.EVENT.addPhaseOrdering(ACTOR, PHASE);
		AttackBlockCallback.EVENT.addPhaseOrdering(ACTOR, PHASE);
		UseEntityCallback.EVENT.addPhaseOrdering(ACTOR, Event.DEFAULT_PHASE);
		AttackEntityCallback.EVENT.addPhaseOrdering(ACTOR, Event.DEFAULT_PHASE);
		UseItemCallback.EVENT.register(ACTOR, (player, level, hand) -> acting(player));
		UseBlockCallback.EVENT.register(ACTOR, (player, level, hand, hit) -> acting(player));
		AttackBlockCallback.EVENT.register(ACTOR, (player, level, hand, pos, direction) -> acting(player));
		UseEntityCallback.EVENT.register(ACTOR, (player, level, hand, entity, hit) -> acting(player));
		AttackEntityCallback.EVENT.register(ACTOR, (player, level, hand, entity, hit) -> acting(player));
		PermissionEvents.ON_REQUEST.register(ToolPriority::onPermissionRequest);
	}

	/** Phase où l'auteur d'un clic est noté, avant toutes les autres. */
	static final Identifier ACTOR = Identifier.fromNamespaceAndPath("valcombe", "auteur");

	private static net.minecraft.world.InteractionResult acting(net.minecraft.world.entity.player.Player player) {
		if (player instanceof ServerPlayer sp) {
			SharedUndo.acting(sp);
		}
		return net.minecraft.world.InteractionResult.PASS;
	}

	/** Refuse les actions par objet de WorldEdit à qui tient un outil de carte ; sinon ne se prononce pas (null). */
	private static <T> T onPermissionRequest(PermissionContext context, PermissionNode<T> node) {
		if (!WORLDEDIT_ITEM_ACTIONS.contains(node.key())) {
			return null;
		}
		if (context.orElse(PermissionContext.ENTITY, null) instanceof ServerPlayer player
			&& ValcombeUi.mapTool(player.getMainHandItem()) != null) {
			return node.cast(Boolean.FALSE);
		}
		return null;
	}
}
