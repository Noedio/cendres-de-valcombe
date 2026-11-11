package fr.valcombe.mc;

import fr.valcombe.rules.GameException;
import fr.valcombe.rules.Role;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import net.minecraft.server.MinecraftServer;

/** Scripts enregistrés : un fichier texte par script (un identifiant de rôle par ligne). */
public final class ScriptStore {
	private ScriptStore() {
	}

	private static Path dir(MinecraftServer server) {
		return server.getServerDirectory().resolve("config").resolve("valcombe-scripts");
	}

	private static String clean(String name) {
		String n = name.trim().replaceAll("[^A-Za-z0-9 _-]", "").replace(' ', '_');
		if (n.isEmpty() || n.length() > 40) {
			throw new GameException("Nom de script invalide (lettres, chiffres, espaces, 40 caractères au plus).");
		}
		return n;
	}

	/** Scripts fournis avec le jeu, toujours disponibles. */
	public static final java.util.Map<String, Set<Role>> BUILT_IN = java.util.Map.of(
		"Les Cendres de Valcombe", Role.classic(),
		"Les Brumes de Valcombe", Role.brumes());

	public static List<String> list(MinecraftServer server) {
		List<String> out = new java.util.ArrayList<>(BUILT_IN.keySet().stream().sorted().toList());
		Path d = dir(server);
		if (!Files.isDirectory(d)) {
			return out;
		}
		try (Stream<Path> files = Files.list(d)) {
			files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".txt"))
				.map(n -> n.substring(0, n.length() - 4)).sorted().forEach(out::add);
		} catch (IOException e) {
			// dossier illisible : on garde les scripts fournis
		}
		return out;
	}

	public static void save(MinecraftServer server, String name, Set<Role> roles) {
		try {
			Path d = dir(server);
			Files.createDirectories(d);
			List<String> lines = roles.stream().map(r -> r.name().toLowerCase(Locale.ROOT)).toList();
			Files.write(d.resolve(clean(name) + ".txt"), lines);
		} catch (IOException e) {
			throw new GameException("Impossible d'enregistrer le script : " + e.getMessage());
		}
	}

	public static List<Role> load(MinecraftServer server, String name) {
		if (BUILT_IN.containsKey(name)) {
			return List.copyOf(BUILT_IN.get(name));
		}
		Path f = dir(server).resolve(clean(name) + ".txt");
		if (!Files.exists(f)) {
			throw new GameException("Script introuvable : " + name);
		}
		try {
			List<Role> roles = new ArrayList<>();
			for (String line : Files.readAllLines(f)) {
				if (!line.isBlank()) {
					Role r = Role.parse(line.trim());
					if (r != null) {
						roles.add(r);
					}
				}
			}
			return roles;
		} catch (IOException | IllegalArgumentException e) {
			throw new GameException("Script illisible : " + name);
		}
	}

	public static void delete(MinecraftServer server, String name) {
		try {
			Files.deleteIfExists(dir(server).resolve(clean(name) + ".txt"));
		} catch (IOException e) {
			throw new GameException("Impossible de supprimer le script.");
		}
	}
}
