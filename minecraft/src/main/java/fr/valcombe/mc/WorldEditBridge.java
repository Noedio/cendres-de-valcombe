package fr.valcombe.mc;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.selector.ConvexPolyhedralRegionSelector;
import com.sk89q.worldedit.regions.selector.CylinderRegionSelector;
import com.sk89q.worldedit.regions.selector.EllipsoidRegionSelector;
import com.sk89q.worldedit.regions.selector.Polygonal2DRegionSelector;
import com.sk89q.worldedit.regions.selector.SphereRegionSelector;
import com.sk89q.worldedit.world.World;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Le seul endroit qui parle à l'API de WorldEdit (compilée avec {@code worldedit-core} et l'adaptateur Fabric, voir
 * build.gradle). Cette classe n'est chargée que si le mod WorldEdit est là ({@link WorldEditGate}).
 * <ul>
 * <li><b>Baguette</b> : le réglage {@code wand-item} de WorldEdit devient l'objet de la baguette de construction de
 * Valcombe ({@link ValcombeUi#WAND_ID}). Il est remis après chaque rechargement de la configuration de WorldEdit
 * ({@link #applySettings()}, appelé régulièrement), sans toucher au fichier {@code worldedit.properties}.</li>
 * <li><b>Sélection</b> : lue dans la session WorldEdit du joueur, sans jamais la changer (demander le sélecteur d'un
 * autre monde que celui de la sélection l'effacerait : on ne le demande que pour celui-là).</li>
 * <li><b>Historique</b> : WorldEdit ne donne pas la liste des modifications qu'on peut annuler. On la lit dans sa
 * session ({@code history} et {@code historyPointer}, champs privés de {@code LocalSession}, les mêmes depuis
 * WorldEdit 6) pour ranger ses modifications avec celles de Valcombe dans l'« annuler » commun ({@link SharedUndo}).
 * Si une version de WorldEdit les renommait, la liste reste vide et le journal du serveur le dit : l'« annuler »
 * commun annule alors les gestes de Valcombe, et //undo ceux de WorldEdit.</li>
 * <li><b>Annuler et rétablir</b> : par l'API de la session ({@code undo}, {@code redo}), comme //undo et //redo.</li>
 * </ul>
 */
final class WorldEditBridge implements WorldEditLink {
	/** Le mot de WorldEdit pour l'objet de sa baguette : celui de la baguette de construction de Valcombe. */
	static final String WAND = ValcombeUi.WAND_ID;

	private MinecraftServer server;
	private Field historyField;
	private Field pointerField;
	private boolean historyReadable = true;

	@Override
	public void start(MinecraftServer server) {
		this.server = server;
		try {
			historyField = LocalSession.class.getDeclaredField("history");
			pointerField = LocalSession.class.getDeclaredField("historyPointer");
			historyField.setAccessible(true);
			pointerField.setAccessible(true);
		} catch (ReflectiveOperationException | RuntimeException e) {
			historyReadable = false;
			ValcombeMod.LOG.warn("[valcombe] Historique de WorldEdit illisible ({}) : /vc annuler annulera les gestes de "
				+ "Valcombe, //undo ceux de WorldEdit.", e.toString());
		}
		applySettings();
	}

	/** Remet la baguette de Valcombe comme baguette de WorldEdit (un //reload relit la configuration de WorldEdit). */
	@Override
	public void applySettings() {
		try {
			var config = WorldEdit.getInstance().getConfiguration();
			if (config != null && !WAND.equals(config.wandItem)) {
				config.wandItem = WAND;
			}
		} catch (RuntimeException e) {
			// WorldEdit pas encore prêt : on réessaiera au prochain passage.
		}
	}

	@Override
	public boolean present() {
		return true;
	}

	/**
	 * Les commandes de WorldEdit sont inscrites dans l'arbre des commandes de Minecraft par son adaptateur : on les
	 * reconnaît à ce que leur exécution est une classe de WorldEdit.
	 */
	@Override
	public boolean isCommand(String root) {
		if (server == null || root == null || root.isEmpty()) {
			return false;
		}
		var node = server.getCommands().getDispatcher().getRoot().getChild(root);
		if (node == null || node.getCommand() == null) {
			return false;
		}
		return node.getCommand().getClass().getName().startsWith("com.sk89q.worldedit.");
	}

	private static Player actor(ServerPlayer p) {
		return FabricAdapter.get().fromNativePlayer(p);
	}

	private static LocalSession sessionIfPresent(ServerPlayer p) {
		return WorldEdit.getInstance().getSessionManager().getIfPresent(actor(p));
	}

	private static BlockPos pos(BlockVector3 v) {
		return new BlockPos(v.x(), v.y(), v.z());
	}

	private static String shape(RegionSelector s) {
		if (s instanceof SphereRegionSelector) {
			return "sphere";
		}
		if (s instanceof EllipsoidRegionSelector) {
			return "ellipsoide";
		}
		if (s instanceof Polygonal2DRegionSelector) {
			return "polygone";
		}
		if (s instanceof CylinderRegionSelector) {
			return "cylindre";
		}
		if (s instanceof ConvexPolyhedralRegionSelector) {
			return "enveloppe";
		}
		return "cuboide";
	}

	@Override
	public Selection selection(ServerPlayer p) {
		LocalSession session = sessionIfPresent(p);
		if (session == null) {
			return null;
		}
		World world = session.getSelectionWorld();
		if (world == null) {
			return null;
		}
		ServerLevel level = FabricAdapter.get().toNativeWorld(world);
		if (level == null) {
			return null;
		}
		RegionSelector selector = session.getRegionSelector(world); // le monde de la sélection : rien n'est effacé
		BlockPos first = null;
		try {
			first = pos(selector.getPrimaryPosition());
		} catch (IncompleteRegionException e) {
			// pas encore de premier point
		}
		BlockPos min = null;
		BlockPos max = null;
		BlockPos second = null;
		long volume = 0;
		Predicate<BlockPos> inside = b -> true;
		try {
			Region r = selector.getRegion();
			min = pos(r.getMinimumPoint());
			max = pos(r.getMaximumPoint());
			volume = r.getVolume();
			if (r instanceof CuboidRegion c) {
				second = pos(c.getPos2());
			} else {
				// Une autre forme que le pavé : sa boîte englobante a des cases hors de la sélection.
				Region frozen = r.clone();
				inside = b -> frozen.contains(BlockVector3.at(b.getX(), b.getY(), b.getZ()));
			}
		} catch (IncompleteRegionException e) {
			// sélection commencée, pas encore complète
		}
		if (first == null && min == null) {
			return null;
		}
		return new Selection(level, shape(selector), min, max, first, second, volume, inside);
	}

	@Override
	public List<Object> history(ServerPlayer p) {
		if (!historyReadable) {
			return List.of();
		}
		LocalSession session = sessionIfPresent(p);
		if (session == null) {
			return List.of();
		}
		try {
			Object h = historyField.get(session);
			int pointer = pointerField.getInt(session);
			if (!(h instanceof Collection<?> all)) {
				return List.of();
			}
			List<Object> done = new ArrayList<>(Math.max(0, pointer));
			int i = 0;
			for (Object o : all) {
				if (i++ >= pointer) {
					break;
				}
				done.add(o);
			}
			return done;
		} catch (ReflectiveOperationException | RuntimeException e) {
			historyReadable = false;
			ValcombeMod.LOG.warn("[valcombe] Historique de WorldEdit illisible : {}", e.toString());
			return List.of();
		}
	}

	private static String blocks(int n) {
		return n + (n > 1 ? " blocs" : " bloc");
	}

	@Override
	public String undo(ServerPlayer p) {
		Player actor = actor(p);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);
		EditSession undone = session.undo(session.getBlockBag(actor), actor);
		if (undone == null) {
			return null;
		}
		WorldEdit.getInstance().flushBlockBag(actor, undone);
		return "modification WorldEdit (" + blocks(undone.getChangeSet().size()) + ")";
	}

	@Override
	public String redo(ServerPlayer p) {
		Player actor = actor(p);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);
		EditSession redone = session.redo(session.getBlockBag(actor), actor);
		if (redone == null) {
			return null;
		}
		WorldEdit.getInstance().flushBlockBag(actor, redone);
		return "modification WorldEdit (" + blocks(redone.getChangeSet().size()) + ")";
	}
}
