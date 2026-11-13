package fr.valcombe.mc;

import fr.valcombe.backup.BackupContents;
import fr.valcombe.backup.BackupKind;
import fr.valcombe.backup.BackupManifest;
import fr.valcombe.backup.BackupName;
import fr.valcombe.backup.BackupSettings;
import fr.valcombe.backup.BackupStore;
import fr.valcombe.backup.BackupWriter;
import fr.valcombe.backup.RestoreMarker;
import fr.valcombe.backup.Restorer;
import fr.valcombe.rules.GameException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Gestionnaire des sauvegardes du serveur : archives zip horodatées dans {@code backups/} (voir
 * {@link fr.valcombe.backup.BackupContents} pour ce qu'elles contiennent), automatiques, manuelles, et restauration
 * au démarrage suivant.
 *
 * <p><b>Une sauvegarde cohérente sans geler le jeu</b>, en trois temps :
 * <ol>
 *   <li>sur le fil du serveur : la partie en cours est enregistrée, puis tout est écrit sur le disque
 *   ({@code saveEverything}, comme {@code save-all flush}), puis l'écriture des mondes est suspendue (comme
 *   {@code save-off}) : plus aucun fichier de monde ne change pendant la copie. Ce temps-là est court ;</li>
 *   <li>sur un fil à part, de faible priorité : la copie dans l'archive, pendant que le jeu continue ;</li>
 *   <li>de retour sur le fil du serveur : l'écriture des mondes reprend (comme {@code save-on}), chaque monde
 *   retrouvant exactement l'état d'écriture qu'il avait avant (un {@code save-off} tapé par un opérateur est
 *   respecté). Puis les plus anciennes sauvegardes automatiques sont effacées (rotation).</li>
 * </ol>
 * Une seule sauvegarde à la fois. Si le serveur s'arrête pendant une copie, elle est abandonnée proprement (rien
 * n'est perdu : Minecraft rétablit l'écriture des mondes avant de les enregistrer à l'arrêt), puis la sauvegarde
 * d'arrêt la remplace.
 *
 * <p><b>Automatique</b> (serveur dédié seulement) : à intervalle réglable après la dernière sauvegarde (6 heures par
 * défaut) et à l'arrêt du serveur. Une sauvegarde automatique qui tombe pendant une partie en cours attend la fin
 * de la partie, sauf réglage contraire ({@code pendantPartie}) : l'écriture des mondes sur le disque et la copie
 * peuvent provoquer un à-coup, et on ne veut pas d'à-coup au milieu d'un vote. Elle n'attend jamais plus de
 * {@link #MAX_POSTPONE} : une partie laissée ouverte des jours ne prive pas le serveur de sauvegardes. Les réglages
 * sont dans {@code config/valcombe/sauvegardes.json} ({@link BackupSettings}).
 *
 * <p><b>Restauration</b> : on ne remplace pas les fichiers d'un serveur qui tourne. {@link #scheduleRestore} pose
 * une demande ({@link RestoreMarker}), appliquée au démarrage suivant par {@link #restoreAtStartup}, avant
 * l'ouverture du monde. Le redémarrage passe par le drapeau de l'API d'administration (POST /admin/redemarrer, ou
 * POST /admin/sauvegardes/restaurer avec {@code redemarrer}). Seulement sur un serveur dédié : une partie solo
 * ouvre son monde depuis l'écran du jeu, avant que le mod puisse y toucher.
 */
public final class BackupService {
	/** Une sauvegarde automatique reportée par une partie en cours n'attend jamais plus que cela. */
	public static final Duration MAX_POSTPONE = Duration.ofHours(24);
	/** Fréquence à laquelle on regarde si une sauvegarde automatique est due. */
	private static final long CHECK_SECONDS = 30;
	/** Place gardée libre en plus de la taille de ce qu'on copie. */
	private static final long MARGIN = 64L * 1024 * 1024;
	/** Temps laissé à une copie en cours pour s'arrêter quand le serveur s'arrête. */
	private static final long STOP_WAIT_SECONDS = 30;

	/**
	 * Une sauvegarde en cours. Ses compteurs sont lus par l'API pendant que le fil de copie les écrit.
	 */
	public static final class Job {
		public final String name;
		public final BackupKind kind;
		public final String by;
		public final Instant startedAt;
		/** attente, enregistrement, copie, rotation */
		volatile String phase = "attente";
		volatile int files;
		volatile int filesTotal;
		volatile long bytes;
		volatile long bytesTotal;
		volatile boolean cancelled;
		/** État d'écriture de chaque monde avant la sauvegarde, rétabli après. */
		final Map<ServerLevel, Boolean> noSaveBefore = new IdentityHashMap<>();

		Job(String name, BackupKind kind, String by, Instant startedAt) {
			this.name = name;
			this.kind = kind;
			this.by = by;
			this.startedAt = startedAt;
		}

		/** attente, enregistrement, copie, rotation */
		public String phase() {
			return phase;
		}

		/** Avancement de la copie, en pour cent des octets à copier. */
		public int percent() {
			long total = bytesTotal;
			return total <= 0 ? 0 : (int) Math.min(100, 100 * bytes / total);
		}

		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("nom", name);
			m.put("sorte", kind.id());
			m.put("par", by);
			m.put("debut", startedAt.toString());
			m.put("phase", phase);
			m.put("fichiers", files);
			m.put("fichiersTotal", filesTotal);
			m.put("octets", bytes);
			m.put("octetsTotal", bytesTotal);
			m.put("pourcent", percent());
			return m;
		}
	}

	/**
	 * Résultat d'une sauvegarde.
	 *
	 * @param name     archive
	 * @param kind     sorte
	 * @param ok       réussie
	 * @param message  ce qui s'est passé, en français
	 * @param size     taille de l'archive (0 en cas d'échec)
	 * @param files    fichiers copiés
	 * @param duration durée
	 * @param at       fin
	 * @param deleted  anciennes sauvegardes effacées par la rotation
	 */
	public record Outcome(String name, BackupKind kind, boolean ok, String message, long size, int files, Duration duration,
			Instant at, List<String> deleted) {
		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("nom", name);
			m.put("sorte", kind.id());
			m.put("ok", ok);
			m.put("message", message);
			m.put("octets", size);
			m.put("fichiers", files);
			m.put("dureeSecondes", Math.round(duration.toMillis() / 100.0) / 10.0);
			m.put("fin", at.toString());
			m.put("effacees", deleted);
			return m;
		}
	}

	private final MinecraftServer server;
	private final Path serverDir;
	private final Path valcombeDir;
	private final BackupStore store;
	private final BackupSettings settings;
	private final String modVersion;
	private final ZoneId zone = ZoneId.systemDefault();
	private final Clock clock = Clock.systemUTC();
	private final Instant startedAt = Instant.now();
	/** Enregistre la partie en cours avant une sauvegarde (fil du serveur). */
	private final Runnable saveGame;
	/** Une partie est-elle en cours ? (fil du serveur) */
	private final BooleanSupplier gameInProgress;
	private final AtomicReference<Job> running = new AtomicReference<>();
	private volatile Outcome last;
	/** Depuis quand une sauvegarde automatique attend la fin d'une partie (null : elle n'attend pas). */
	private volatile Instant postponedSince;
	private volatile boolean stopping;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "valcombe-sauvegarde");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY); // la copie passe après le jeu
		return t;
	});
	private ScheduledExecutorService checker;
	/** Dossier du monde, relevé au démarrage. */
	private volatile Path world;

	public BackupService(MinecraftServer server, String modVersion, Runnable saveGame, BooleanSupplier gameInProgress) {
		this.server = server;
		this.serverDir = server.getServerDirectory().toAbsolutePath().normalize();
		this.valcombeDir = serverDir.resolve("config").resolve(BackupContents.VALCOMBE);
		this.store = new BackupStore(serverDir.resolve(BackupStore.DIR_NAME));
		this.settings = new BackupSettings(valcombeDir);
		this.modVersion = modVersion;
		this.saveGame = saveGame;
		this.gameInProgress = gameInProgress;
	}

	// ------------------------------------------------------------------
	// Démarrage et arrêt
	// ------------------------------------------------------------------

	/** Lit les réglages, efface les archives laissées à moitié écrites et lance la surveillance (serveur dédié). */
	public void start() {
		worldDir(); // relevé maintenant, pendant que la sauvegarde du monde est ouverte
		try {
			settings.load();
		} catch (IOException e) {
			ValcombeMod.LOG.warn("Réglages des sauvegardes illisibles ou impossibles à écrire ({}) : réglages par défaut", e.getMessage());
		}
		try {
			Files.createDirectories(store.dir());
			int partials = store.cleanPartials();
			if (partials > 0) {
				ValcombeMod.LOG.info("[valcombe] {} sauvegarde(s) interrompue(s) effacée(s) du dossier {}", partials, BackupStore.DIR_NAME);
			}
		} catch (IOException e) {
			ValcombeMod.LOG.warn("Dossier des sauvegardes inaccessible : {}", e.getMessage());
		}
		if (server.isDedicatedServer()) {
			BackupSettings.Values v = settings.get();
			ValcombeMod.LOG.info("[valcombe] Sauvegardes {} dans {}/ : {}", v.actives() ? "automatiques" : "automatiques désactivées,",
				BackupStore.DIR_NAME, v.actives() ? describe(v) : "seulement à la demande (/vc sauvegarde maintenant)");
			checker = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "valcombe-sauvegardes-horloge");
				t.setDaemon(true);
				return t;
			});
			checker.scheduleWithFixedDelay(this::checkDue, CHECK_SECONDS, CHECK_SECONDS, TimeUnit.SECONDS);
		}
	}

	/**
	 * Arrêt du serveur (avant que Minecraft n'enregistre les mondes) : une copie en cours est abandonnée, et on attend
	 * qu'elle ait lâché ses fichiers.
	 */
	public void stopping() {
		stopping = true;
		if (checker != null) {
			checker.shutdownNow();
		}
		Job j = running.get();
		if (j != null) {
			j.cancelled = true;
			ValcombeMod.LOG.info("[valcombe] Arrêt du serveur : la sauvegarde {} en cours est abandonnée", j.name);
		}
		worker.shutdown();
		try {
			if (!worker.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
				ValcombeMod.LOG.warn("La copie de la sauvegarde ne s'est pas arrêtée à temps");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (j != null) {
			// Une sauvegarde encore en attente du fil du serveur ne passera plus : elle est terminée ici.
			running.compareAndSet(j, null);
		}
	}

	/**
	 * Serveur arrêté, mondes enregistrés et fermés : la sauvegarde d'arrêt, sur ce fil (le processus attend
	 * qu'elle soit finie). Pas de sauvegarde d'arrêt quand une restauration est prévue : la sauvegarde de sécurité
	 * faite au démarrage suivant contiendra exactement le même état.
	 */
	public void stopped() {
		BackupSettings.Values v = settings.get();
		if (!server.isDedicatedServer() || !v.actives() || !v.aLArret()) {
			return;
		}
		if (RestoreMarker.present(valcombeDir)) {
			ValcombeMod.LOG.info("[valcombe] Pas de sauvegarde d'arrêt : une restauration est prévue au prochain démarrage.");
			return;
		}
		Job job = new Job(store.newName(BackupKind.ARRET, now()).fileName(), BackupKind.ARRET, "arrêt du serveur", Instant.now());
		ValcombeMod.LOG.info("[valcombe] Sauvegarde d'arrêt {}...", job.name);
		Outcome o = copy(job, worldDir(), false);
		log(o);
	}

	// ------------------------------------------------------------------
	// Sauvegarde
	// ------------------------------------------------------------------

	/**
	 * Lance une sauvegarde. Rend la main tout de suite : la sauvegarde se fait au tick suivant (enregistrement) puis
	 * sur un fil à part (copie). {@code done} est appelé à la fin, sur le fil du serveur si possible.
	 *
	 * @throws GameException si une sauvegarde est déjà en cours ou si le serveur s'arrête
	 */
	public Job start(BackupKind kind, String by, Consumer<Outcome> done) {
		Job job;
		synchronized (this) {
			if (stopping || !server.isRunning()) {
				throw new GameException("Le serveur s'arrête : pas de nouvelle sauvegarde.");
			}
			Job current = running.get();
			if (current != null) {
				throw new GameException("Une sauvegarde est déjà en cours (" + current.name + ") : attendez qu'elle se termine.");
			}
			job = new Job(store.newName(kind, now()).fileName(), kind, by, Instant.now());
			running.set(job);
		}
		ValcombeMod.LOG.info("[valcombe] Sauvegarde {} demandée par {}", job.name, by);
		server.execute(() -> prepare(job, done));
		return job;
	}

	/** Premier temps, sur le fil du serveur : tout écrire sur le disque, puis suspendre l'écriture des mondes. */
	private void prepare(Job job, Consumer<Outcome> done) {
		if (stopping || job.cancelled) {
			finish(job, failure(job, "Sauvegarde abandonnée : le serveur s'arrête."), done);
			return;
		}
		job.phase = "enregistrement";
		try {
			saveGame.run();
		} catch (RuntimeException e) {
			ValcombeMod.LOG.warn("Partie en cours non enregistrée avant la sauvegarde", e);
		}
		try {
			// Comme « save-all flush » : tout est écrit et le disque a fini d'écrire.
			server.saveEverything(true, true, true);
			// Comme « save-off » : plus rien ne s'écrit dans les mondes pendant la copie (le jeu, lui, continue).
			for (ServerLevel level : server.getAllLevels()) {
				job.noSaveBefore.put(level, level.noSave);
				level.noSave = true;
			}
		} catch (RuntimeException e) {
			ValcombeMod.LOG.error("Sauvegarde {} : enregistrement des mondes impossible", job.name, e);
			resumeWriting(job);
			finish(job, failure(job, "Sauvegarde impossible : les mondes n'ont pas pu être enregistrés (" + e.getMessage() + ")."), done);
			return;
		}
		Path world = worldDir();
		try {
			worker.execute(() -> {
				Outcome o = copy(job, world, true);
				// Troisième temps, sur le fil du serveur : l'écriture des mondes reprend.
				if (!stopping && server.isRunning()) {
					server.execute(() -> {
						resumeWriting(job);
						finish(job, o, done);
					});
				} else {
					finish(job, o, null); // Minecraft rétablit lui-même l'écriture des mondes à l'arrêt
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException e) {
			resumeWriting(job);
			finish(job, failure(job, "Sauvegarde abandonnée : le serveur s'arrête."), done);
		}
	}

	/** Comme « save-on », mais chaque monde retrouve exactement son état d'avant. */
	private void resumeWriting(Job job) {
		for (Map.Entry<ServerLevel, Boolean> e : job.noSaveBefore.entrySet()) {
			e.getKey().noSave = e.getValue();
		}
		job.noSaveBefore.clear();
	}

	/** Deuxième temps : la copie dans l'archive, puis la rotation. Ne lève jamais. */
	private Outcome copy(Job job, Path world, boolean live) {
		job.phase = "copie";
		Instant t0 = Instant.now();
		try {
			BackupContents.Scan scan = BackupContents.scan(serverDir, world);
			job.filesTotal = scan.entries().size();
			job.bytesTotal = scan.bytes();
			long free = store.freeSpace();
			if (free >= 0 && free < scan.bytes() + MARGIN) {
				return failure(job, "Sauvegarde impossible : place insuffisante sur le disque (" + mb(scan.bytes() + MARGIN)
					+ " nécessaires, " + mb(free) + " libres). Effacez d'anciennes sauvegardes ou libérez de la place.");
			}
			BackupManifest m = new BackupManifest();
			BackupName name = BackupName.parse(job.name).orElseThrow();
			m.nom = job.name;
			m.sorte = job.kind.id();
			m.date = name.at(zone).toOffsetDateTime().toString();
			m.par = job.by;
			m.versionMod = modVersion;
			m.minecraft = server.getServerVersion();
			m.dossierMonde = world.getFileName() == null ? "" : world.getFileName().toString();
			m.mondes = BackupManifest.worldNames(valcombeDir.resolve("mondes.json"));
			BackupWriter.Result r = BackupWriter.write(store.resolve(job.name), scan, m, (f, b) -> {
				job.files = f;
				job.bytes = b;
			}, () -> job.cancelled || (live && stopping));
			job.phase = "rotation";
			List<String> deleted = List.of();
			try {
				deleted = store.rotate(settings.get().garder());
			} catch (IOException e) {
				ValcombeMod.LOG.warn("Rotation des sauvegardes impossible : {}", e.getMessage());
			}
			String skipped = r.skipped().isEmpty() ? "" : " " + r.skipped().size() + " fichier(s) laissé(s) de côté (voir le journal).";
			if (!r.skipped().isEmpty()) {
				ValcombeMod.LOG.warn("Sauvegarde {} : fichiers laissés de côté : {}", job.name, r.skipped());
			}
			return new Outcome(job.name, job.kind, true, "Sauvegarde " + job.name + " terminée : " + mb(r.size()) + ", "
				+ r.files() + " fichiers, en " + seconds(Duration.between(t0, Instant.now())) + "." + skipped
				+ (deleted.isEmpty() ? "" : " " + deleted.size() + " ancienne(s) effacée(s)."),
				r.size(), r.files(), Duration.between(job.startedAt, Instant.now()), Instant.now(), deleted);
		} catch (BackupWriter.Cancelled e) {
			return failure(job, "Sauvegarde " + job.name + " abandonnée : le serveur s'arrête.");
		} catch (IOException | RuntimeException e) {
			ValcombeMod.LOG.error("Sauvegarde {} impossible", job.name, e);
			return failure(job, "Sauvegarde " + job.name + " impossible : " + e.getMessage());
		}
	}

	private Outcome failure(Job job, String message) {
		return new Outcome(job.name, job.kind, false, message, 0, job.files, Duration.between(job.startedAt, Instant.now()),
			Instant.now(), List.of());
	}

	private void finish(Job job, Outcome o, Consumer<Outcome> done) {
		last = o; // d'abord le résultat : qui voit la sauvegarde finie voit aussi son résultat
		running.compareAndSet(job, null);
		log(o);
		if (done != null) {
			try {
				done.accept(o);
			} catch (RuntimeException e) {
				ValcombeMod.LOG.warn("Fin de sauvegarde : message non transmis", e);
			}
		}
	}

	private static void log(Outcome o) {
		if (o.ok()) {
			ValcombeMod.LOG.info("[valcombe] {}", o.message());
		} else {
			ValcombeMod.LOG.warn("[valcombe] {}", o.message());
		}
	}

	// ------------------------------------------------------------------
	// Sauvegardes automatiques
	// ------------------------------------------------------------------

	/** Sur le fil de l'horloge : une sauvegarde automatique est-elle due ? */
	private void checkDue() {
		try {
			BackupSettings.Values v = settings.get();
			if (stopping || !v.actives() || running.get() != null) {
				return;
			}
			Instant due = nextAutomatic(v);
			if (due == null || Instant.now().isBefore(due)) {
				return;
			}
			server.execute(() -> {
				if (stopping || running.get() != null) {
					return;
				}
				if (!v.pendantPartie() && gameInProgress.getAsBoolean()) {
					Instant since = postponedSince;
					if (since == null) {
						postponedSince = Instant.now();
						ValcombeMod.LOG.info("[valcombe] Sauvegarde automatique reportée à la fin de la partie en cours.");
						return;
					}
					if (Duration.between(since, Instant.now()).compareTo(MAX_POSTPONE) < 0) {
						return;
					}
					ValcombeMod.LOG.info("[valcombe] Sauvegarde automatique reportée depuis {} h : elle se fait malgré la partie en cours.",
						MAX_POSTPONE.toHours());
				}
				postponedSince = null;
				try {
					start(BackupKind.AUTO, "automatique", null);
				} catch (GameException e) {
					// une autre sauvegarde vient de commencer : celle-ci attendra le prochain coup d'œil
				}
			});
		} catch (RuntimeException e) {
			ValcombeMod.LOG.warn("Sauvegardes automatiques : erreur", e);
		}
	}

	/**
	 * Moment de la prochaine sauvegarde automatique : l'intervalle après la dernière sauvegarde, quelle qu'elle soit
	 * (ou après le démarrage s'il n'y en a aucune). Null si elles sont désactivées ou hors serveur dédié.
	 */
	public Instant nextAutomatic(BackupSettings.Values v) {
		if (!v.actives() || !server.isDedicatedServer()) {
			return null;
		}
		Instant from = startedAt;
		try {
			Optional<BackupStore.Backup> latest = store.latest();
			if (latest.isPresent()) {
				from = latest.get().parsed().at(zone).toInstant();
			}
		} catch (IOException e) {
			// dossier illisible : on compte depuis le démarrage
		}
		return from.plus(Duration.ofMinutes(v.intervalleMinutes()));
	}

	/** Une sauvegarde automatique attend-elle la fin d'une partie ? */
	public boolean postponed() {
		return postponedSince != null;
	}

	// ------------------------------------------------------------------
	// Restauration
	// ------------------------------------------------------------------

	/**
	 * Prévoit la restauration de cette sauvegarde au prochain démarrage (serveur dédié seulement). Ne redémarre pas
	 * le serveur : voir POST /admin/redemarrer.
	 */
	public String scheduleRestore(String name, String by) throws IOException {
		if (!server.isDedicatedServer()) {
			throw new GameException("La restauration ne se fait que sur un serveur dédié : une partie solo ouvre son monde "
				+ "avant que Valcombe puisse le remplacer. Pour un monde solo, fermez-le et déballez l'archive à la main.");
		}
		Path archive;
		try {
			archive = store.resolve(name);
		} catch (IllegalArgumentException e) {
			throw new GameException(e.getMessage());
		}
		if (!Files.isRegularFile(archive)) {
			throw new GameException("Aucune sauvegarde ne s'appelle « " + name + " » : /vc sauvegarde liste");
		}
		try {
			BackupManifest.read(archive);
		} catch (IOException e) {
			throw new GameException("Sauvegarde « " + name + " » illisible : " + e.getMessage() + ".");
		}
		Path world = worldDir();
		String relative = world.startsWith(serverDir) ? serverDir.relativize(world).toString().replace('\\', '/') : null;
		RestoreMarker.write(valcombeDir, new RestoreMarker.Request(name, Instant.now(), by, relative));
		ValcombeMod.LOG.info("[valcombe] Restauration de {} prévue au prochain démarrage (demandée par {})", name, by);
		return "Restauration de « " + name + " » prévue au prochain démarrage du serveur. L'état actuel sera d'abord sauvegardé.";
	}

	/** Annule la restauration prévue ; vrai s'il y en avait une. */
	public boolean cancelRestore() throws IOException {
		boolean had = RestoreMarker.clear(valcombeDir);
		if (had) {
			ValcombeMod.LOG.info("[valcombe] Restauration prévue annulée");
		}
		return had;
	}

	/** La restauration prévue au prochain démarrage, s'il y en a une. */
	public Optional<RestoreMarker.Request> pendingRestore() {
		try {
			return RestoreMarker.read(valcombeDir);
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	/**
	 * Applique la restauration demandée, s'il y en a une. Appelé très tôt au démarrage d'un serveur dédié (à
	 * l'initialisation du mod, avant que Minecraft ne lise server.properties et n'ouvre le monde).
	 */
	public static void restoreAtStartup(Path serverDir, String modVersion) {
		Path dir = serverDir.toAbsolutePath().normalize();
		if (!RestoreMarker.present(dir.resolve("config").resolve(BackupContents.VALCOMBE))) {
			return;
		}
		try {
			new Restorer(dir, new BackupStore(dir.resolve(BackupStore.DIR_NAME)), Clock.systemUTC(), ZoneId.systemDefault(), modVersion)
				.applyPending();
		} catch (RuntimeException e) {
			ValcombeMod.LOG.error("[valcombe] Restauration impossible", e);
		}
	}

	// ------------------------------------------------------------------
	// Lectures pour les commandes et l'API
	// ------------------------------------------------------------------

	public BackupStore store() {
		return store;
	}

	public BackupSettings settings() {
		return settings;
	}

	/** La sauvegarde en cours, ou null. */
	public Job running() {
		return running.get();
	}

	/** La dernière sauvegarde terminée depuis le démarrage (réussie ou non), ou null. */
	public Outcome last() {
		return last;
	}

	/** La restauration est-elle possible ici (serveur dédié) ? */
	public boolean canRestore() {
		return server.isDedicatedServer();
	}

	/**
	 * Le dossier du monde du serveur. Relevé au démarrage, tant que la sauvegarde du monde est ouverte : la
	 * sauvegarde d'arrêt se fait après sa fermeture.
	 */
	public Path worldDir() {
		Path w = world;
		if (w == null) {
			w = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			world = w;
		}
		return w;
	}

	/** Efface une sauvegarde ; faux si elle n'existait pas. */
	public boolean delete(String name, String by) throws IOException {
		try {
			boolean done = store.delete(name);
			if (done) {
				ValcombeMod.LOG.info("[valcombe] Sauvegarde {} effacée par {}", name, by);
			}
			return done;
		} catch (IllegalArgumentException e) {
			throw new GameException(e.getMessage());
		}
	}

	/** Réglages en une phrase (« toutes les 6 h et à l'arrêt, 10 gardées »). */
	public static String describe(BackupSettings.Values v) {
		int m = v.intervalleMinutes();
		String every = m % 60 == 0 ? (m / 60) + " h" : m + " min";
		return "toutes les " + every + (v.aLArret() ? " et à l'arrêt" : "") + ", " + v.garder() + " gardée(s)"
			+ (v.pendantPartie() ? ", même pendant une partie" : ", jamais pendant une partie en cours");
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock.withZone(zone));
	}

	public ZoneId zone() {
		return zone;
	}

	/** Taille lisible : « 812 Ko », « 41,3 Mo », « 2,07 Go ». */
	public static String mb(long bytes) {
		if (bytes < 1024 * 1024) {
			return Math.max(1, (bytes + 1023) / 1024) + " Ko";
		}
		double mo = bytes / (1024.0 * 1024.0);
		if (mo < 1024) {
			return String.format(java.util.Locale.FRANCE, "%.1f Mo", mo);
		}
		return String.format(java.util.Locale.FRANCE, "%.2f Go", mo / 1024);
	}

	private static String seconds(Duration d) {
		long s = d.toSeconds();
		return s < 60 ? Math.max(1, s) + " s" : (s / 60) + " min " + (s % 60) + " s";
	}
}
