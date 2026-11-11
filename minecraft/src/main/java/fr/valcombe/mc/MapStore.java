package fr.valcombe.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import fr.valcombe.rules.GameException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Bibliothèque de cartes : chaque carte est une structure Minecraft (les blocs, salle d'attente
 * comprise) et une fiche de repères (trônes, maisons, réglages) relatifs au centre de la place.
 * Fichiers : config/valcombe/creations/cartes/<id>.json et <id>.nbt.
 */
public final class MapStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/** Hauteur enregistrée : 4 blocs sous la place, 24 au-dessus. */
	private static final int BELOW = 4;
	private static final int HEIGHT = 28;
	/** Taille maximale d'une carte partagée (structure compressée). */
	private static final int MAX_BYTES = 24 * 1024 * 1024;

	/** Fiche d'une carte. */
	public static final class MapDef {
		public String id = "";
		public String nom = "";
		public boolean genere;
		public String theme = "gothique";
		public int places = Village.SEATS;
		public int taille = 1;
		public boolean maisons = true;
		public boolean salle;
		/** Demi-largeur de la zone enregistrée. */
		public int rayon;
		public List<int[]> sieges = new ArrayList<>();
		public List<int[]> logis = new ArrayList<>();
	}

	private final Path dir;
	private final McHost host;
	private MinecraftServer server;

	public MapStore(Path creationsDir, McHost host) {
		this.dir = creationsDir.resolve("cartes");
		this.host = host;
	}

	public void setServer(MinecraftServer server) {
		this.server = server;
	}

	public boolean exists(String id) {
		return id != null && Files.exists(dir.resolve(id + ".json")) && Files.exists(dir.resolve(id + ".nbt"));
	}

	public List<MapDef> list() {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		List<MapDef> out = new ArrayList<>();
		try (Stream<Path> s = Files.list(dir)) {
			for (Path f : s.filter(p -> p.toString().endsWith(".json")).toList()) {
				try {
					MapDef d = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), MapDef.class);
					if (d != null && exists(d.id)) {
						out.add(d);
					}
				} catch (IOException | RuntimeException e) {
					ValcombeMod.LOG.warn("Carte illisible {}", f, e);
				}
			}
		} catch (IOException e) {
			return List.of();
		}
		out.sort(Comparator.comparing(d -> d.nom.toLowerCase()));
		return out;
	}

	private MapDef def(String id) {
		try {
			return GSON.fromJson(Files.readString(dir.resolve(id + ".json"), StandardCharsets.UTF_8), MapDef.class);
		} catch (IOException | RuntimeException e) {
			throw new GameException("Carte introuvable : " + id + ".");
		}
	}

	private static void checkId(String id) {
		if (id == null || !id.matches("[a-z0-9-]{2,30}")) {
			throw new GameException("Identifiant de carte : 2 à 30 lettres minuscules, chiffres ou tirets.");
		}
	}

	// ------------------------------------------------------------------
	// Enregistrer la carte actuelle
	// ------------------------------------------------------------------
	public String saveCurrent(String id, String nom) {
		checkId(id);
		Village v = host.village();
		if (v == null) {
			throw new GameException("Aucune carte à enregistrer : construisez ou générez d'abord un village.");
		}
		if (v.seatCount() < 5) {
			throw new GameException("La carte n'a que " + v.seatCount() + " trône(s) : il en faut au moins 5.");
		}
		ServerLevel level = server.overworld();
		int r = v.extent();
		BlockPos c = v.center();
		BlockPos corner = c.offset(-r, -BELOW, -r);
		StructureTemplate t = new StructureTemplate();
		t.fillFromWorld(level, corner, new Vec3i(2 * r + 1, HEIGHT, 2 * r + 1), false, List.of(Blocks.STRUCTURE_VOID));
		MapDef d = new MapDef();
		d.id = id;
		d.nom = nom == null || nom.isBlank() ? id : nom.trim();
		d.genere = v.isGenerated();
		d.theme = v.theme();
		d.places = v.seatTotal();
		d.taille = v.size();
		d.maisons = v.withHouses();
		d.salle = v.hasHall();
		d.rayon = r;
		d.sieges = v.relSeats();
		d.logis = v.relHouses();
		try {
			Files.createDirectories(dir);
			NbtIo.writeCompressed(t.save(new CompoundTag()), dir.resolve(id + ".nbt"));
			Files.writeString(dir.resolve(id + ".json"), GSON.toJson(d), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new GameException("Enregistrement impossible : " + e.getMessage());
		}
		return "Carte « " + d.nom + " » enregistrée (" + d.sieges.size() + " trônes, " + d.logis.size() + " maisons).";
	}

	// ------------------------------------------------------------------
	// Charger une carte à la place de l'actuelle
	// ------------------------------------------------------------------
	public String load(String id) {
		if (ValcombeMod.session().game() != null) {
			throw new GameException("On change de carte entre deux parties.");
		}
		MapDef d = def(id);
		CompoundTag tag;
		try {
			tag = NbtIo.readCompressed(dir.resolve(id + ".nbt"), NbtAccounter.unlimitedHeap());
		} catch (IOException e) {
			throw new GameException("Structure de la carte illisible : " + e.getMessage());
		}
		ServerLevel level = server.overworld();
		StructureTemplate t = new StructureTemplate();
		t.load(server.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
		Village old = host.village();
		BlockPos c = old != null ? old.center() : host.defaultCenter();
		// L'ancienne carte peut déborder de la nouvelle : on rase d'abord la plus grande des deux.
		int clear = Math.max(d.rayon, old == null ? 0 : old.extent());
		host.clearArea(c, clear, BELOW, HEIGHT);
		BlockPos corner = c.offset(-d.rayon, -BELOW, -d.rayon);
		t.placeInWorld(level, corner, corner, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS);
		Village v = Village.restored(c, d.genere, d.theme, d.places, d.taille, d.maisons, d.salle, d.sieges, d.logis);
		host.useVillage(v);
		return "Carte « " + d.nom + " » chargée.";
	}

	public String delete(String id) {
		MapDef d = def(id);
		try {
			Files.deleteIfExists(dir.resolve(id + ".json"));
			Files.deleteIfExists(dir.resolve(id + ".nbt"));
		} catch (IOException e) {
			throw new GameException("Suppression impossible : " + e.getMessage());
		}
		return "Carte « " + d.nom + " » supprimée de la bibliothèque.";
	}

	// ------------------------------------------------------------------
	// Partage (dans un fichier .valcombe)
	// ------------------------------------------------------------------
	public JsonObject exportMap(String id) {
		if (!exists(id)) {
			return null;
		}
		try {
			JsonObject o = GSON.toJsonTree(def(id)).getAsJsonObject();
			o.addProperty("structure", Base64.getEncoder().encodeToString(Files.readAllBytes(dir.resolve(id + ".nbt"))));
			return o;
		} catch (IOException e) {
			return null;
		}
	}

	public String importMap(JsonObject o) {
		MapDef d = GSON.fromJson(o, MapDef.class);
		checkId(d.id);
		if (!o.has("structure")) {
			throw new GameException("Carte « " + d.nom + " » sans structure.");
		}
		byte[] nbt = Base64.getDecoder().decode(o.get("structure").getAsString());
		if (nbt.length > MAX_BYTES) {
			throw new GameException("Carte « " + d.nom + " » trop grande.");
		}
		try {
			// Vérifie que la structure se lit avant de l'enregistrer.
			NbtIo.readCompressed(new ByteArrayInputStream(nbt), NbtAccounter.unlimitedHeap());
			Files.createDirectories(dir);
			Files.write(dir.resolve(d.id + ".nbt"), nbt);
			Files.writeString(dir.resolve(d.id + ".json"), GSON.toJson(d), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			throw new GameException("Carte « " + d.nom + " » illisible.");
		}
		return d.nom;
	}

	/** Taille du fichier de structure (pour l'Atelier). */
	public long bytes(String id) {
		try {
			return Files.size(dir.resolve(id + ".nbt"));
		} catch (IOException e) {
			return 0;
		}
	}

	static byte[] compress(CompoundTag tag) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		NbtIo.writeCompressed(tag, out);
		return out.toByteArray();
	}
}
