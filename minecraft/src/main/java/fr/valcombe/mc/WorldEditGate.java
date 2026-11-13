package fr.valcombe.mc;

import fr.valcombe.mc.worlds.BuildAccess;
import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.net.ValcombeNet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.fabricmc.fabric.api.permission.v1.PermissionEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * WorldEdit limité aux ateliers, et ce que Valcombe lui ajoute. Les règles (qui, où) sont celles de {@link BuildAccess} ;
 * cette classe les applique à WorldEdit et à la vie dans les ateliers.
 *
 * <h2>Le point d'accroche dans WorldEdit</h2>
 * WorldEdit pour Fabric demande chacune de ses permissions ({@code worldedit.selection.pos}, {@code worldedit.region.set}…)
 * à l'API de permissions de Fabric ({@code ServerPlayer.checkPermission}) avant tout le reste : c'est la même question
 * pour ses commandes (et l'arbre des commandes envoyé au joueur), sa baguette, ses outils et brosses, sa navigation.
 * {@link #onPermission} y répond :
 * <ul>
 * <li>là où WorldEdit n'est pas permis (hub, mondes de partie, monde principal de Minecraft, atelier où l'on n'est pas invité) : <b>non</b>,
 * pour tout le monde, opérateurs compris ;</li>
 * <li>dans un atelier, pour un opérateur : on laisse WorldEdit décider (niveau d'opérateur, ou LuckPerms s'il est là) ;</li>
 * <li>dans un atelier, pour un invité qui n'est pas opérateur : <b>oui</b>, sauf ce qui touche au serveur et non au
 * monde ({@link #GUEST_DENIED} : recharger WorldEdit, changer de monde, scripts, instantanés, effacer des fichiers…).</li>
 * </ul>
 * L'arbre des commandes est renvoyé au joueur quand il change de monde et quand ses invitations changent : il ne voit
 * les commandes de WorldEdit que là où elles servent. Une commande de WorldEdit tapée ailleurs reçoit une phrase claire
 * ({@link #refuseCommand}, appelée par {@code CommandsMixin} avant l'exécution), et un clic avec la baguette aussi
 * ({@link #wandRefused}).
 *
 * <h2>Dans les ateliers</h2>
 * <ul>
 * <li>Qui y construit passe en mode créatif à l'arrivée et retrouve son mode d'avant en sortant (un invité qui n'est
 * pas opérateur revient en aventure s'il n'y a rien à retrouver, par exemple après un redémarrage).</li>
 * <li>Il reçoit la baguette de construction ({@link ValcombeUi#wandItem()}), rendue en sortant. Une baguette « nue »
 * donnée par //wand prend son apparence en moins d'une seconde.</li>
 * <li>Un invité qui n'est pas opérateur laisse son inventaire à l'entrée ({@link WorkshopInventories}) : ce qu'il prend
 * en créatif reste dans l'atelier, il retrouve le sien en sortant, même après un redémarrage.</li>
 * <li>Un joueur qui n'a plus le droit d'y être (invitation retirée) est renvoyé au hub.</li>
 * <li>Sa sélection WorldEdit est envoyée au mod client, qui la dessine en 3D avec ses dimensions
 * ({@link ValcombeNet.SelectionPayload}).</li>
 * </ul>
 */
public final class WorldEditGate {
	/**
	 * Permissions de WorldEdit jamais accordées à un invité qui n'est pas opérateur (le chemin, et tout ce qui est
	 * dessous) : elles agissent sur le serveur ou ses fichiers, pas sur l'atelier, ou sur le travail des autres.
	 * <p>
	 * Une commande de WorldEdit est permise dès que l'une de ses permissions l'est : //schem save demande
	 * {@code clipboard.save} <b>ou</b> {@code schematic.save}, les deux sont donc refusées (de même pour le partage, qui
	 * envoie la copie sur un service extérieur). {@code history.undo.other} : //undo d'un autre joueur, qui défait ses
	 * modifications, y compris dans un atelier où l'invité n'est pas. {@code regen} recrée des tronçons du monde par son
	 * générateur, {@code watchdog} touche à la surveillance du serveur.
	 */
	static final List<String> GUEST_DENIED = List.of("reload", "report", "debugpaste", "trace", "world", "scripting", "snapshots",
		"snapshot", "schematic.delete", "schematic.save", "schematic.share", "clipboard.save", "clipboard.share", "delchunks",
		"regen", "watchdog", "history.undo.other", "history.redo.other", "limit.unrestricted", "timeout.unrestricted", "anyblock",
		"override");

	private static volatile WorldEditGate current;

	private final MinecraftServer server;
	private final BuildAccess access;
	private final WorldEditLink link;
	private final SharedUndo undo;
	/** Mode de jeu d'avant l'atelier, rendu en sortant. */
	private final Map<UUID, GameType> modes = new HashMap<>();
	/** Joueurs entrés dans un atelier où ils construisent (pour leur rendre leur mode en sortant). */
	private final Set<UUID> inside = new HashSet<>();
	/** La dernière sélection envoyée à chaque joueur (sa signature). */
	private final Map<UUID, String> sent = new HashMap<>();
	/** Dernier rappel « WorldEdit ne sert que dans les ateliers » après un clic avec la baguette (une fois toutes les 2 s). */
	private final Map<UUID, Integer> hinted = new HashMap<>();
	/** Les inventaires des invités, mis de côté le temps de l'atelier. */
	private final WorkshopInventories bags;
	private int ticks;

	WorldEditGate(MinecraftServer server, BuildAccess access, WorldEditLink link, SharedUndo undo) {
		this.server = server;
		this.access = access;
		this.link = link;
		this.undo = undo;
		this.bags = new WorkshopInventories(WorkshopInventories.dirIn(server.getServerDirectory()));
	}

	/** Le lien vers WorldEdit : {@link WorldEditBridge} si le mod est là, sinon {@link WorldEditLink#NONE}. */
	static WorldEditLink loadLink() {
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("worldedit")) {
			return WorldEditLink.NONE;
		}
		try {
			// Chargée par son nom : sans WorldEdit, aucune de ses classes n'est jamais demandée.
			return (WorldEditLink) Class.forName("fr.valcombe.mc.WorldEditBridge").getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException | LinkageError e) {
			ValcombeMod.LOG.error("[valcombe] WorldEdit est là, mais son API ne répond pas comme prévu : baguette, sélection "
				+ "et « annuler » commun désactivés", e);
			return WorldEditLink.NONE;
		}
	}

	/** Le portier du serveur en marche, ou null. */
	public static WorldEditGate current() {
		return current;
	}

	static void install(WorldEditGate gate) {
		current = gate;
	}

	WorldEditLink link() {
		return link;
	}

	public SharedUndo undo() {
		return undo;
	}

	// ------------------------------------------------------------------
	// Permissions de WorldEdit
	// ------------------------------------------------------------------

	/** À appeler une fois, au chargement du mod, après {@link ToolPriority#install()} (dont le refus passe d'abord). */
	static void installPermissions() {
		PermissionEvents.ON_REQUEST.register(WorldEditGate::onPermission);
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("fabric-permissions-api-v0")) {
			// Avec l'API de permissions de lucko (LuckPerms…), WorldEdit lui pose ses questions, pas à celle de Fabric.
			ValcombeMod.LOG.warn("[valcombe] fabric-permissions-api-v0 est installé : WorldEdit demande ses permissions à "
				+ "LuckPerms (ou au mod qui l'apporte), plus à Valcombe. Ses commandes et la baguette restent refusées hors des "
				+ "ateliers, mais les outils et brosses, et ce que peuvent les invités, suivent alors les réglages de LuckPerms.");
		}
	}

	/** Le chemin d'une permission est-il refusé aux invités (lui ou ce qui est au-dessus de lui dans la liste) ? */
	static boolean deniedToGuests(String path) {
		for (String d : GUEST_DENIED) {
			if (path.equals(d) || path.startsWith(d + ".")) {
				return true;
			}
		}
		return false;
	}

	private static <T> T onPermission(PermissionContext context, PermissionNode<T> node) {
		if (!"worldedit".equals(node.key().getNamespace())) {
			return null;
		}
		BuildAccess access = BuildAccess.current();
		if (access == null || !(context.orElse(PermissionContext.ENTITY, null) instanceof ServerPlayer p)) {
			return null; // console, blocs de commande, ou serveur pas encore prêt : WorldEdit décide
		}
		if (!access.canUseWorldEdit(p)) {
			return node.cast(Boolean.FALSE);
		}
		if (BuildAccess.operator(p)) {
			return null;
		}
		return node.cast(!deniedToGuests(node.key().getPath()));
	}

	// ------------------------------------------------------------------
	// Refus clairs
	// ------------------------------------------------------------------

	/**
	 * Une commande que ce joueur va lancer : si c'est une commande de WorldEdit là où il ne peut pas s'en servir, la
	 * phrase qui le lui dit (et la commande n'est pas lancée) ; sinon null. La commande est telle que Minecraft l'analyse,
	 * sans la barre de tête : « //set stone » arrive ici en « /set stone ».
	 */
	public static Component refuseCommand(CommandSourceStack source, String command) {
		WorldEditGate g = current;
		ServerPlayer p = source.getPlayer();
		if (g == null || p == null || command == null) {
			return null;
		}
		int space = command.indexOf(' ');
		String root = space < 0 ? command : command.substring(0, space);
		if (!g.link.isCommand(root) || g.access.canUseWorldEdit(p)) {
			return null;
		}
		return Component.literal(g.access.refusal(p)).withStyle(ChatFormatting.RED);
	}

	/**
	 * Un clic avec la baguette de construction : là où WorldEdit n'est pas permis, le joueur l'apprend (une fois
	 * toutes les deux secondes, au-dessus de sa barre d'objets) et le clic ne fait rien d'autre. Vrai si refusé.
	 */
	public static boolean wandRefused(ServerPlayer p) {
		WorldEditGate g = current;
		BuildAccess access = BuildAccess.current();
		if (g == null || access == null || access.canUseWorldEdit(p)) {
			return false;
		}
		Integer last = g.hinted.get(p.getUUID());
		if (last == null || g.ticks - last >= 40) {
			g.hinted.put(p.getUUID(), g.ticks);
			p.sendSystemMessage(Component.literal(access.refusal(p)).withStyle(ChatFormatting.GOLD), true);
		}
		return true;
	}

	// ------------------------------------------------------------------
	// Entrer dans un atelier, en sortir
	// ------------------------------------------------------------------

	/** Après un changement de monde : mode de jeu, baguette, arbre des commandes, sélection montrée. */
	public void onLevelChange(ServerPlayer p, ServerLevel from, ServerLevel to) {
		refresh(p, true);
	}

	/** Un joueur arrive sur le serveur : son identifiant est noté sur les invitations faites à son nom. */
	public void onJoin(ServerPlayer p) {
		access.invitations().noteJoin(p.getUUID(), p.getScoreboardName());
		refresh(p, false);
	}

	public void onDisconnect(ServerPlayer p) {
		sent.remove(p.getUUID());
		hinted.remove(p.getUUID());
	}

	/**
	 * Remet ce joueur d'accord avec ses droits là où il est : il entre dans l'atelier (mode créatif, baguette), en sort
	 * (mode d'avant), ou en est renvoyé s'il n'a plus à y être. L'arbre des commandes lui est renvoyé.
	 */
	public void refresh(ServerPlayer p, boolean arrived) {
		UUID id = p.getUUID();
		WorldEntry here = access.entryOf(p.level());
		boolean workshop = access.inWorkshop(p);
		if (workshop) {
			boolean first = inside.add(id);
			GameType mode = p.gameMode.getGameModeForPlayer();
			// Un invité laisse son inventaire à l'entrée : ce qu'il prend en créatif reste dans l'atelier.
			boolean stashed = first && !BuildAccess.operator(p) && bags.stash(p, mode);
			if (stashed) {
				p.getInventory().add(ValcombeUi.menuItem());
			}
			if (mode != GameType.CREATIVE) {
				modes.putIfAbsent(id, mode);
				p.setGameMode(GameType.CREATIVE);
			}
			boolean gave = giveWand(p);
			if (first || arrived) {
				p.sendSystemMessage(Gui.plain("« " + here.nom() + " » : tu y construis en mode créatif, avec WorldEdit. "
					+ (gave ? "Baguette de construction en main : " : "Baguette de construction : ")
					+ "clic gauche, premier coin ; clic droit, second coin ; puis //set, //walls, //copy, //paste… "
					+ "Annuler : /vc annuler.", ChatFormatting.GREEN));
			}
			if (stashed) {
				p.sendSystemMessage(Gui.plain("Ton inventaire est mis de côté : tu le retrouves en sortant de l'atelier, et ce que tu "
					+ "y prends reste ici.", ChatFormatting.GRAY));
			}
		} else if (inside.remove(id) | bags.holds(id)) {
			// Sortie de l'atelier (ou retour après un redémarrage, inventaire encore de côté).
			GameType kept = bags.restore(p);
			GameType before = modes.remove(id);
			if (before == null) {
				before = kept;
			}
			if (before != null) {
				p.setGameMode(before);
			} else if (!BuildAccess.operator(p) && p.gameMode.getGameModeForPlayer() == GameType.CREATIVE) {
				p.setGameMode(GameType.ADVENTURE);
			}
			takeWand(p);
		}
		if (here != null && here.sorte().workshop() && !workshop) {
			// Dans un atelier sans y avoir droit (invitation retirée) : retour au hub.
			var travel = ValcombeMod.travel();
			if (travel != null) {
				p.sendSystemMessage(Gui.plain(access.refusal(p) + " Retour au hub.", ChatFormatting.GOLD));
				travel.evacuate(p, here);
				return; // le changement de monde rappellera refresh
			}
		}
		sent.remove(id); // la sélection sera renvoyée (ou effacée) au prochain passage
		server.getCommands().sendCommands(p);
	}

	/** Les invitations ont changé : chaque joueur connecté est remis d'accord avec ses droits. */
	public void invitationsChanged() {
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
			refresh(p, false);
		}
	}

	/** Donne la baguette de construction à qui ne l'a pas ; vrai si elle vient d'être donnée. */
	static boolean giveWand(ServerPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (ValcombeUi.isWand(inv.getItem(i))) {
				return false;
			}
		}
		inv.add(ValcombeUi.wandItem());
		return true;
	}

	private static void takeWand(ServerPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (ValcombeUi.isWand(inv.getItem(i))) {
				inv.setItem(i, ItemStack.EMPTY);
			}
		}
	}

	/** Une baguette « nue » (celle de //wand, ou prise dans l'inventaire créatif) prend l'apparence de celle de Valcombe. */
	private static void dressWands(ServerPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(ValcombeUi.wandBase()) && s.get(DataComponents.CUSTOM_NAME) == null) {
				ItemStack w = ValcombeUi.wandItem();
				w.setCount(s.getCount());
				inv.setItem(i, w);
			}
		}
	}

	// ------------------------------------------------------------------
	// À chaque tick
	// ------------------------------------------------------------------

	void tick() {
		ticks++;
		undo.sync();
		if (ticks % 4 == 0) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				sendSelection(p);
			}
		}
		if (ticks % 20 == 0) {
			for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
				boolean workshop = access.inWorkshop(p);
				WorldEntry here = access.entryOf(p.level());
				boolean intruder = !workshop && here != null && here.sorte().workshop();
				if (workshop != inside.contains(p.getUUID()) || intruder) {
					// Droits changés sans passer par une commande (opérateur retiré, fichier modifié), ou arrivé sans y avoir droit.
					refresh(p, false);
				} else if (workshop) {
					dressWands(p);
				}
			}
		}
		if (ticks % 200 == 0) {
			link.applySettings();
		}
	}

	private static int[] xyz(BlockPos p) {
		return p == null ? new int[0] : new int[] {p.getX(), p.getY(), p.getZ()};
	}

	/** Ce qu'on montre de la sélection de ce joueur, là où il est. */
	ValcombeNet.SelectionPayload selectionFor(ServerPlayer p) {
		if (!access.canUseWorldEdit(p)) {
			return ValcombeNet.SelectionPayload.NONE;
		}
		WorldEditLink.Selection s = link.selection(p);
		if (s == null || s.level() != p.level()) {
			return ValcombeNet.SelectionPayload.NONE;
		}
		int[] box = s.complete() ? new int[] {s.min().getX(), s.min().getY(), s.min().getZ(), s.max().getX(), s.max().getY(),
			s.max().getZ()} : new int[0];
		return new ValcombeNet.SelectionPayload(s.shape(), box, xyz(s.first()), xyz(s.second()), s.volume());
	}

	private void sendSelection(ServerPlayer p) {
		if (!ViewSync.hasClientMod(p)) {
			return;
		}
		ValcombeNet.SelectionPayload payload = selectionFor(p);
		String sig = payload.signature();
		if (!sig.equals(sent.get(p.getUUID()))) {
			sent.put(p.getUUID(), sig);
			ServerPlayNetworking.send(p, payload);
		}
	}
}
