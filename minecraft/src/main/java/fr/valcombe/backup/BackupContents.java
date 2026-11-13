package fr.valcombe.backup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ce que contient une sauvegarde, et donc ce qu'une restauration remplace. Une archive a trois parties :
 * <ul>
 *   <li>{@code monde/} : le dossier du monde du serveur ({@code world/} d'ordinaire), avec <b>tous les mondes de
 *   Valcombe</b>, puisque les mondes de la bibliothèque Fantasy (hub, parties, ateliers) y sont rangés, dans
 *   {@code dimensions/valcombe/<id>} ; le verrou {@code session.lock} n'y est pas ;</li>
 *   <li>{@code config/valcombe/} : créations de l'Atelier, cartes de la bibliothèque, structures, registre des
 *   mondes ({@code mondes.json})... ;</li>
 *   <li>{@code config/valcombe-*} : l'état du jeu rangé à côté (partie en cours, fiche du village posé, instantané
 *   de la carte, statistiques, scripts).</li>
 * </ul>
 *
 * <p><b>Jamais dans une archive</b> (et jamais touchés par une restauration) :
 * <ul>
 *   <li>{@code config/valcombe/admin.json}, la clé d'administration. Une archive se télécharge et se copie sur
 *   d'autres machines : elle ne doit jamais porter un secret qui donne tout pouvoir sur le serveur. La chiffrer ne
 *   servirait à rien (la clé de chiffrement serait sur le même serveur), et restaurer une ancienne clé couperait le
 *   launcher qui pilote le serveur au moment même où il en a besoin ;</li>
 *   <li>{@code config/valcombe-communaute.json} : le webhook Discord (un secret, lui aussi), le port de la tablette
 *   et le message d'accueil, des réglages du serveur et non de l'état du jeu ;</li>
 *   <li>{@code config/valcombe/sauvegardes.json} (les réglages des sauvegardes : une restauration ne doit pas
 *   rallumer ou éteindre les sauvegardes automatiques), la demande de restauration, le drapeau de redémarrage, le
 *   lien d'ouverture de l'Atelier pour l'hôte ({@code valcombe-hote.json}) et les fichiers en cours d'écriture
 *   ({@code .tmp}).</li>
 * </ul>
 * Les réglages de Minecraft ({@code server.properties}, liste blanche, opérateurs) restent eux aussi hors des
 * archives : restaurer les mondes ne doit pas changer qui entre sur le serveur ni qui le commande.
 */
public final class BackupContents {
	/** Préfixe du dossier du monde dans l'archive. */
	public static final String WORLD = "monde/";
	/** Préfixe des réglages et créations de Valcombe dans l'archive. */
	public static final String CONFIG = "config/";
	/** Dossier de Valcombe dans {@code config/}. */
	public static final String VALCOMBE = "valcombe";
	/** Préfixe des fichiers de Valcombe rangés directement dans {@code config/}. */
	public static final String CONFIG_FILE_PREFIX = "valcombe-";
	/** Fichiers de {@code config/valcombe/} jamais archivés (avec leurs variantes .tmp, .bak, .illisible...). */
	public static final Set<String> PRIVATE_IN_VALCOMBE = Set.of("admin.json", "sauvegardes.json", "restaurer.json", "redemarrer");
	/** Fichiers de {@code config/} jamais archivés (avec leurs variantes). */
	public static final Set<String> PRIVATE_IN_CONFIG = Set.of("valcombe-communaute.json", "valcombe-hote.json");
	/** Verrou du monde, tenu par le serveur qui tourne. */
	public static final String WORLD_LOCK = "session.lock";

	/** Un fichier à archiver : son chemin dans l'archive, le fichier, sa taille et sa date au moment du relevé. */
	public record Entry(String name, Path file, long size, FileTime modified) {
	}

	/**
	 * Relevé des fichiers à archiver.
	 *
	 * @param entries fichiers, dans l'ordre de l'archive
	 * @param bytes   taille totale
	 * @param skipped fichiers ou dossiers illisibles, laissés de côté (avec la raison)
	 */
	public record Scan(List<Entry> entries, long bytes, List<String> skipped) {
	}

	private BackupContents() {
	}

	/**
	 * Relève les fichiers à archiver.
	 *
	 * @param serverDir dossier du serveur (celui de {@code config/})
	 * @param worldDir  dossier du monde ; absent : l'archive n'aura pas de monde
	 */
	public static Scan scan(Path serverDir, Path worldDir) throws IOException {
		List<Entry> entries = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		if (worldDir != null && Files.isDirectory(worldDir)) {
			walk(worldDir, WORLD, entries, skipped);
		}
		Path config = serverDir.resolve("config");
		Path valcombe = config.resolve(VALCOMBE);
		if (Files.isDirectory(valcombe, LinkOption.NOFOLLOW_LINKS)) {
			walk(valcombe, CONFIG + VALCOMBE + "/", entries, skipped);
		}
		if (Files.isDirectory(config)) {
			List<Path> tops = new ArrayList<>();
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(config, p -> configTop(p.getFileName().toString()))) {
				ds.forEach(tops::add);
			}
			Collections.sort(tops);
			for (Path p : tops) {
				String name = p.getFileName().toString();
				if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
					walk(p, CONFIG + name + "/", entries, skipped);
				} else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
					BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
					entries.add(new Entry(CONFIG + name, p, a.size(), a.lastModifiedTime()));
				}
			}
		}
		long bytes = 0;
		for (Entry e : entries) {
			bytes += e.size();
		}
		return new Scan(List.copyOf(entries), bytes, List.copyOf(skipped));
	}

	/** Parcourt un dossier sans suivre les liens ; les fichiers sont ajoutés dans un ordre stable (trié). */
	private static void walk(Path root, String prefix, List<Entry> out, List<String> skipped) throws IOException {
		List<Entry> found = new ArrayList<>();
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (attrs.isRegularFile()) {
					String name = prefix + relative(root, file);
					if (managed(name)) {
						found.add(new Entry(name, file, attrs.size(), attrs.lastModifiedTime()));
					}
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException e) {
				skipped.add(prefix + relative(root, file) + " (" + e.getClass().getSimpleName() + ")");
				return FileVisitResult.CONTINUE;
			}
		});
		found.sort((a, b) -> a.name().compareTo(b.name()));
		out.addAll(found);
	}

	/** Chemin relatif avec des barres obliques, quel que soit le système. */
	static String relative(Path root, Path file) {
		StringBuilder sb = new StringBuilder();
		for (Path part : root.relativize(file)) {
			if (!sb.isEmpty()) {
				sb.append('/');
			}
			sb.append(part);
		}
		return sb.toString();
	}

	/** Un fichier ou dossier de {@code config/} qui appartient à Valcombe et va dans les archives ? */
	public static boolean configTop(String name) {
		return name.startsWith(CONFIG_FILE_PREFIX) && name.length() > CONFIG_FILE_PREFIX.length()
			&& !privateName(name, PRIVATE_IN_CONFIG) && !temporary(name);
	}

	/**
	 * Ce chemin d'archive fait-il partie d'une sauvegarde ? Vrai pour les fichiers du monde (sauf son verrou) et ceux
	 * de Valcombe dans {@code config/}, sauf les fichiers privés et les fichiers en cours d'écriture. Une
	 * restauration ne pose que ces chemins-là, et n'efface que ceux-là.
	 */
	public static boolean managed(String name) {
		if (!safe(name)) {
			return false;
		}
		if (name.startsWith(WORLD)) {
			String rest = name.substring(WORLD.length());
			return !rest.isEmpty() && !rest.equals(WORLD_LOCK);
		}
		if (!name.startsWith(CONFIG)) {
			return false;
		}
		String rest = name.substring(CONFIG.length());
		int slash = rest.indexOf('/');
		String top = slash < 0 ? rest : rest.substring(0, slash);
		String last = rest.substring(rest.lastIndexOf('/') + 1);
		if (temporary(last)) {
			return false;
		}
		if (top.equals(VALCOMBE)) {
			if (slash < 0) {
				return false; // le dossier lui-même
			}
			String inside = rest.substring(slash + 1);
			int s2 = inside.indexOf('/');
			String first = s2 < 0 ? inside : inside.substring(0, s2);
			return !privateName(first, PRIVATE_IN_VALCOMBE);
		}
		return configTop(top);
	}

	/**
	 * Un chemin d'archive sans danger : relatif, avec des barres obliques, sans segment vide, « . » ni « .. », sans
	 * lecteur Windows, contre-oblique ni caractère de contrôle. Une archive abîmée ou fabriquée ne peut ainsi rien
	 * écrire hors du dossier où on la déballe.
	 */
	public static boolean safe(String name) {
		if (name == null || name.isEmpty() || name.length() > 1024 || name.startsWith("/")) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == '\\' || c == ':' || Character.isISOControl(c)) {
				return false;
			}
		}
		for (String part : name.split("/", -1)) {
			if (part.isEmpty() || part.equals(".") || part.equals("..")) {
				return false;
			}
		}
		return true;
	}

	/** Ce nom est-il l'un de ces fichiers privés, ou l'une de ses variantes (.tmp, .bak, .illisible...) ? */
	private static boolean privateName(String name, Set<String> privates) {
		String lower = name.toLowerCase(Locale.ROOT);
		for (String p : privates) {
			if (lower.equals(p) || lower.startsWith(p + ".")) {
				return true;
			}
		}
		return false;
	}

	/** Fichier en cours d'écriture (écrit à côté puis mis en place d'un coup). */
	private static boolean temporary(String name) {
		return name.toLowerCase(Locale.ROOT).endsWith(".tmp");
	}
}
