package fr.valcombe.backup;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nom d'une archive de sauvegarde : {@code valcombe-AAAA-MM-JJ_HH-MM-SS-<sorte>.zip}, à l'heure du serveur, par
 * exemple {@code valcombe-2026-11-13_09-12-44-auto.zip}. Deux archives de la même sorte nées dans la même seconde
 * prennent un numéro : {@code valcombe-2026-11-13_09-12-44-manuelle-2.zip}.
 *
 * <p>Ce nom est aussi l'identifiant d'une sauvegarde dans les commandes et dans l'API d'administration. Il est
 * vérifié strictement ({@link #parse(String)}) avant de toucher au moindre fichier : un nom qui ne suit pas ce
 * modèle exact (barre oblique, « .. », autre extension...) est refusé, ce qui ferme la porte à tout chemin qui
 * sortirait du dossier des sauvegardes.
 *
 * @param time moment (à la seconde, heure locale du serveur)
 * @param kind sorte
 * @param seq  1 pour la première archive de cette seconde et de cette sorte, puis 2, 3...
 */
public record BackupName(LocalDateTime time, BackupKind kind, int seq) implements Comparable<BackupName> {
	public static final String PREFIX = "valcombe-";
	public static final String EXTENSION = ".zip";
	/** Numéro le plus grand accepté pour départager des archives de la même seconde. */
	public static final int MAX_SEQ = 999;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss", Locale.ROOT)
		.withResolverStyle(ResolverStyle.STRICT);
	private static final Pattern PATTERN = Pattern.compile(
		"valcombe-(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})-(auto|arret|manuelle|avant-restauration)(?:-([2-9]|[1-9]\\d{1,2}))?\\.zip");
	private static final Comparator<BackupName> ORDER = Comparator.comparing(BackupName::time)
		.thenComparingInt(BackupName::seq).thenComparing(BackupName::kind);

	public BackupName {
		if (time == null || kind == null) {
			throw new IllegalArgumentException("moment et sorte attendus");
		}
		if (seq < 1 || seq > MAX_SEQ) {
			throw new IllegalArgumentException("numéro hors bornes : " + seq);
		}
		time = time.truncatedTo(ChronoUnit.SECONDS);
	}

	/** Le nom du fichier. */
	public String fileName() {
		return PREFIX + STAMP.format(time) + "-" + kind.id() + (seq > 1 ? "-" + seq : "") + EXTENSION;
	}

	/** Le moment de la sauvegarde, avec le décalage horaire de cette zone à ce moment-là. */
	public ZonedDateTime at(ZoneId zone) {
		return time.atZone(zone);
	}

	/** Lit un nom d'archive ; vide s'il ne suit pas exactement le modèle (ou si sa date n'existe pas). */
	public static Optional<BackupName> parse(String fileName) {
		if (fileName == null || fileName.length() > 64) {
			return Optional.empty();
		}
		Matcher m = PATTERN.matcher(fileName);
		if (!m.matches()) {
			return Optional.empty();
		}
		try {
			LocalDateTime time = LocalDateTime.parse(m.group(1), STAMP);
			int seq = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));
			return Optional.of(new BackupName(time, BackupKind.fromId(m.group(2)), seq));
		} catch (DateTimeParseException | IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/** Ce nom est-il celui d'une archive de sauvegarde valable ? */
	public static boolean valid(String fileName) {
		return parse(fileName).isPresent();
	}

	/** Du plus ancien au plus récent. */
	@Override
	public int compareTo(BackupName o) {
		return ORDER.compare(this, o);
	}

	@Override
	public String toString() {
		return fileName();
	}
}
