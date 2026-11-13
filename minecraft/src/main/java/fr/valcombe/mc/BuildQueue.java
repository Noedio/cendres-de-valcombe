package fr.valcombe.mc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Construction étalée dans le temps : les blocs d'un chantier sont posés par petits lots à chaque
 * tick (quelques millisecondes), pour que le serveur ne se fige pas pendant qu'on bâtit une carte.
 * Une barre en haut de l'écran montre l'avancement à tous les joueurs.
 */
final class BuildQueue {
	/** Temps de pose par tick, et plafond de blocs réellement changés par tick. */
	private static final long BUDGET_NANOS = 12_000_000L;
	private static final int MAX_CHANGES = 8000;

	/**
	 * Une suite de blocs calculée au fur et à mesure, sans rien garder en mémoire : remettre un terrain à plat,
	 * remettre un instantané. (Une liste de plusieurs millions de positions saturerait la mémoire.)
	 */
	abstract static class Part {
		abstract int size();

		/**
		 * Le bloc de rang i (demandés dans l'ordre) : écrit sa position dans pos et renvoie son état,
		 * ou null s'il n'y a rien à poser à ce rang (case vide d'une structure).
		 */
		abstract BlockState at(int i, BlockPos.MutableBlockPos pos);

		/** Façon de poser les blocs (voir Block.UPDATE_*). */
		int flags() {
			return Block.UPDATE_CLIENTS;
		}
	}

	/** Les blocs à poser, dans l'ordre, puis ce qu'il reste à faire une fois qu'ils sont en place. */
	static final class Plan {
		/** Suites calculées, posées avant les blocs de la liste. */
		final List<Part> first = new ArrayList<>();
		final List<BlockPos> at = new ArrayList<>();
		final List<BlockState> states = new ArrayList<>();
		/** Poses qui demandent des blocs déjà en place (entités de décor, par exemple). */
		final List<Runnable> after = new ArrayList<>();

		void set(BlockPos pos, BlockState state) {
			at.add(pos);
			states.add(state);
		}

		/** Ajoute une suite calculée, posée avant les blocs de la liste. */
		Plan first(Part part) {
			first.add(part);
			return this;
		}

		/** Ajoute un autre plan à la suite de celui-ci. */
		Plan then(Plan next) {
			if (!next.first.isEmpty() && !at.isEmpty()) {
				throw new IllegalStateException("Les suites calculées se posent avant les blocs de la liste.");
			}
			first.addAll(next.first);
			at.addAll(next.at);
			states.addAll(next.states);
			after.addAll(next.after);
			return this;
		}

		/** Blocs de la liste (sans les suites calculées). */
		int size() {
			return at.size();
		}

		/** Tous les blocs du plan. */
		long total() {
			long n = at.size();
			for (Part p : first) {
				n += p.size();
			}
			return n;
		}
	}

	private static final class Job {
		final String label;
		final Plan plan;
		final Runnable done;
		/** Monde du chantier : celui de la partie (sa carte), ou un autre monde de Valcombe (une carte qu'on y pose). */
		final ServerLevel level;
		/** Joueurs qui voient la barre d'avancement. */
		final Predicate<ServerPlayer> audience;
		/** Appelé si le chantier est abandonné avant la fin (monde fermé, recréé ou supprimé, arrêt du serveur), ou null. */
		final Runnable cancelled;
		final ServerBossEvent bar;
		final long total;
		final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		long cursor;
		/** Suite calculée en cours, et rang dans cette suite (ou dans la liste, une fois les suites posées). */
		int part;
		int inPart;

		Job(String label, ServerLevel level, Plan plan, Runnable done, Predicate<ServerPlayer> audience, Runnable cancelled) {
			this.label = label;
			this.level = level;
			this.plan = plan;
			this.done = done;
			this.audience = audience;
			this.cancelled = cancelled;
			this.total = plan.total();
			this.bar = new ServerBossEvent(java.util.UUID.randomUUID(), Component.literal(label), BossEvent.BossBarColor.GREEN,
				BossEvent.BossBarOverlay.PROGRESS);
		}
	}

	private final Deque<Job> jobs = new ArrayDeque<>();

	/** Un chantier est-il en cours ? */
	boolean busy() {
		return !jobs.isEmpty();
	}

	/**
	 * Met un chantier en file dans ce monde ; done est appelé quand tout est posé. Seuls les joueurs que retient
	 * audience voient la barre. Si le monde quitte le serveur avant la fin (recréé, supprimé), ou si le serveur
	 * s'arrête, le chantier est abandonné et cancelled appelé (s'il n'est pas null).
	 */
	void add(String label, ServerLevel level, Plan plan, Runnable done, Predicate<ServerPlayer> audience, Runnable cancelled) {
		java.util.Objects.requireNonNull(level, "un chantier se fait dans un monde donné");
		jobs.add(new Job(label, level, plan, done, audience, cancelled));
	}

	/**
	 * Un chantier en cours ou en attente : son libellé (celui de la barre), son monde et son avancement (0 à 1 ; 0 pour
	 * un chantier qui attend son tour). Pour les écrans qui suivent les chantiers (l'Atelier en jeu).
	 */
	record Progress(String label, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> level, float done) {
	}

	/** Les chantiers de la file, le chantier en cours d'abord. */
	List<Progress> progress() {
		List<Progress> out = new ArrayList<>();
		for (Job j : jobs) {
			out.add(new Progress(j.label, j.level.dimension(), j.total == 0 ? 0f : Math.min(1f, (float) ((double) j.cursor / j.total))));
		}
		return out;
	}

	/** Un chantier est-il en cours ou en attente dans ce monde ? */
	boolean busy(ServerLevel level) {
		return jobs.stream().anyMatch(j -> j.level == level);
	}

	/** Abandonne les chantiers de ce monde (il va être remis à neuf ou supprimé) ; renvoie s'il y en avait. */
	boolean cancel(ServerLevel level) {
		List<Job> gone = jobs.stream().filter(j -> j.level == level).toList();
		jobs.removeAll(gone);
		for (Job j : gone) {
			drop(j);
		}
		return !gone.isEmpty();
	}

	/** Abandonne les chantiers en cours (arrêt du serveur). */
	void clear() {
		List<Job> gone = new ArrayList<>(jobs);
		jobs.clear();
		for (Job j : gone) {
			drop(j);
		}
	}

	/** Retire la barre d'un chantier abandonné et prévient qui l'attendait. */
	private static void drop(Job j) {
		j.bar.removeAllPlayers();
		if (j.cancelled != null) {
			try {
				j.cancelled.run();
			} catch (RuntimeException e) {
				ValcombeMod.LOG.error("Erreur à l'abandon du chantier « " + j.label + " »", e);
			}
		}
	}

	/** À chaque tick : pose un lot de blocs du chantier en cours. */
	void tick(MinecraftServer server) {
		Job job = jobs.peek();
		if (job == null || server == null) {
			return;
		}
		if (server.getLevel(job.level.dimension()) != job.level) {
			// Le monde a quitté le serveur (remis à neuf, supprimé) : on n'écrit pas dans un monde fermé.
			jobs.poll();
			drop(job);
			return;
		}
		ServerLevel level = job.level;
		long start = System.nanoTime();
		int changed = 0;
		long total = job.total;
		List<Part> parts = job.plan.first;
		while (job.cursor < total && changed < MAX_CHANGES) {
			BlockPos pos;
			BlockState state;
			int flags = Block.UPDATE_CLIENTS;
			if (job.part < parts.size()) {
				Part part = parts.get(job.part);
				if (job.inPart >= part.size()) {
					job.part++;
					job.inPart = 0;
					continue;
				}
				state = part.at(job.inPart++, job.pos);
				pos = job.pos;
				flags = part.flags();
			} else {
				pos = job.plan.at.get(job.inPart);
				state = job.plan.states.get(job.inPart++);
			}
			job.cursor++;
			// Un bloc déjà en place n'est pas reposé : reconstruire au même endroit va beaucoup plus vite.
			if (state != null && level.getBlockState(pos) != state) {
				level.setBlock(pos, state, flags);
				changed++;
			}
			if ((job.cursor & 255) == 0 && System.nanoTime() - start > BUDGET_NANOS) {
				break;
			}
		}
		job.bar.setProgress(total == 0 ? 1f : Math.min(1f, (float) ((double) job.cursor / total)));
		if (server.getTickCount() % 20 == 0 || job.cursor >= total) {
			job.bar.setName(Component.literal(job.label + " : " + (total == 0 ? 100 : 100L * job.cursor / total) + " %"));
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (job.audience.test(p)) {
					job.bar.addPlayer(p);
				} else {
					job.bar.removePlayer(p); // parti dans un autre monde en cours de chantier
				}
			}
		}
		if (job.cursor < total) {
			return;
		}
		jobs.poll();
		job.bar.removeAllPlayers();
		try {
			job.plan.after.forEach(Runnable::run);
			if (job.done != null) {
				job.done.run();
			}
		} catch (RuntimeException e) {
			ValcombeMod.LOG.error("Erreur à la fin du chantier « " + job.label + " »", e);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (!job.audience.test(p)) {
					continue;
				}
				p.sendSystemMessage(Component.literal("[Valcombe] Le chantier « " + job.label + " » s'est terminé sur une erreur : "
					+ e.getMessage()));
			}
		}
	}
}
