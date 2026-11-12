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
		/** Salle de convocation (où le Conteur reçoit un joueur à l'écart), ou null. */
		public int[] convocation;
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
		return id != null && id.matches(ID) && Files.exists(dir.resolve(id + ".json")) && Files.exists(dir.resolve(id + ".nbt"));
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
		checkId(id); // jamais de chemin fabriqué avec un identifiant libre
		try {
			return GSON.fromJson(Files.readString(dir.resolve(id + ".json"), StandardCharsets.UTF_8), MapDef.class);
		} catch (IOException | RuntimeException e) {
			throw new GameException("Carte introuvable : " + id + ".");
		}
	}

	private static final String ID = "[a-z0-9-]{2,30}";
	/** Plus grande carte acceptée : demi-largeur en blocs, et nombre de repères. */
	private static final int MAX_RADIUS = 200;
	private static final int MAX_MARKERS = 40;
	/** Mémoire accordée à la lecture d'une structure (une archive piégée ne peut pas en demander plus). */
	private static final long MAX_NBT = 256L * 1024 * 1024;

	/** Une fiche lue dans un fichier partagé doit rester dans des bornes raisonnables. */
	private static void checkDef(MapDef d) {
		checkId(d.id);
		if (d.nom == null || d.nom.isBlank() || d.nom.length() > 50) {
			throw new GameException("Carte « " + d.id + " » : nom absent ou trop long.");
		}
		if (d.rayon < 4 || d.rayon > MAX_RADIUS) {
			throw new GameException("Carte « " + d.nom + " » : taille hors limites (" + MAX_RADIUS + " blocs de rayon au plus).");
		}
		if (d.sieges == null || d.logis == null || d.sieges.size() > MAX_MARKERS || d.logis.size() > MAX_MARKERS) {
			throw new GameException("Carte « " + d.nom + " » : " + MAX_MARKERS + " trônes et " + MAX_MARKERS + " maisons au plus.");
		}
		List<int[]> room = d.convocation == null ? List.of() : java.util.Collections.singletonList(d.convocation);
		for (List<int[]> marks : List.of(d.sieges, d.logis, room)) {
			for (int[] a : marks) {
				if (a == null || a.length != 3 || Math.abs(a[0]) > d.rayon + 8 || Math.abs(a[2]) > d.rayon + 8 || Math.abs(a[1]) > 64) {
					throw new GameException("Carte « " + d.nom + " » : un repère sort de la carte.");
				}
			}
		}
	}

	private static void checkId(String id) {
		if (id == null || !id.matches(ID)) {
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
		d.convocation = v.relConvocation();
		try {
			Files.createDirectories(dir);
			Files.write(dir.resolve(id + ".png"), host.topView()); // l'aperçu que l'Atelier met sous le plan
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
		checkDef(d);
		if (host.busy()) {
			throw new GameException("Une construction est déjà en cours : attendez la fin de la barre de progression.");
		}
		CompoundTag tag;
		try {
			tag = NbtIo.readCompressed(dir.resolve(id + ".nbt"), NbtAccounter.create(MAX_NBT));
		} catch (IOException | RuntimeException e) {
			throw new GameException("Structure de la carte illisible : " + e.getMessage());
		}
		ServerLevel level = server.overworld();
		StructureTemplate t = new StructureTemplate();
		t.load(server.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
		Village old = host.village();
		BlockPos c = old != null ? old.center() : host.defaultCenter();
		// L'ancienne carte peut déborder de la nouvelle : on rase d'abord la plus grande des deux.
		int clear = Math.max(d.rayon, old == null ? 0 : old.extent());
		BlockPos corner = c.offset(-d.rayon, -BELOW, -d.rayon);
		// Le terrain est rasé par petits lots (le serveur reste fluide), puis la carte est posée.
		host.startBuild("Chargement de « " + d.nom + " »", host.clearPlan(c, clear, BELOW, HEIGHT), () -> {
			t.placeInWorld(level, corner, corner, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS);
			host.useVillage(Village.restored(c, d.genere, d.theme, d.places, d.taille, d.maisons, d.salle, d.sieges, d.logis, d.convocation));
			host.announceAll("Carte « " + d.nom + " » chargée.");
		});
		return "Chargement de la carte « " + d.nom + " » lancé : suivez la barre en haut de l'écran.";
	}

	public String delete(String id) {
		MapDef d = def(id);
		try {
			Files.deleteIfExists(dir.resolve(id + ".json"));
			Files.deleteIfExists(dir.resolve(id + ".nbt"));
			Files.deleteIfExists(dir.resolve(id + ".png"));
		} catch (IOException e) {
			throw new GameException("Suppression impossible : " + e.getMessage());
		}
		return "Carte « " + d.nom + " » supprimée de la bibliothèque.";
	}

	/** Change le nom affiché d'une carte (son identifiant, lui, ne change pas : les scénarios s'y réfèrent). */
	public String rename(String id, String nom) {
		MapDef d = def(id);
		if (nom == null || nom.isBlank() || nom.trim().length() > 50) {
			throw new GameException("Nom de carte : 1 à 50 caractères.");
		}
		String old = d.nom;
		d.nom = nom.trim();
		try {
			Files.writeString(dir.resolve(id + ".json"), GSON.toJson(d), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new GameException("Renommage impossible : " + e.getMessage());
		}
		return "Carte « " + old + " » renommée « " + d.nom + " ».";
	}

	/** Copie une carte sous un nouvel identifiant, pour en faire une variante. */
	public String duplicate(String id, String newId, String nom) {
		MapDef d = def(id);
		checkId(newId);
		if (Files.exists(dir.resolve(newId + ".json"))) {
			throw new GameException("Une carte porte déjà l'identifiant « " + newId + " ».");
		}
		d.id = newId;
		d.nom = nom == null || nom.isBlank() ? d.nom + " (copie)" : nom.trim();
		if (d.nom.length() > 50) {
			d.nom = d.nom.substring(0, 50);
		}
		try {
			Files.copy(dir.resolve(id + ".nbt"), dir.resolve(newId + ".nbt"));
			if (Files.exists(dir.resolve(id + ".png"))) {
				Files.copy(dir.resolve(id + ".png"), dir.resolve(newId + ".png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			Files.writeString(dir.resolve(newId + ".json"), GSON.toJson(d), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new GameException("Copie impossible : " + e.getMessage());
		}
		return "Carte copiée : « " + d.nom + " ».";
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
			byte[] png = preview(id);
			if (png != null) {
				o.addProperty("apercu", Base64.getEncoder().encodeToString(png)); // la vue de dessus voyage avec la carte
			}
			return o;
		} catch (IOException e) {
			return null;
		}
	}

	public String importMap(JsonObject o) {
		MapDef d = GSON.fromJson(o, MapDef.class);
		checkDef(d);
		if (!o.has("structure")) {
			throw new GameException("Carte « " + d.nom + " » sans structure.");
		}
		byte[] nbt = Base64.getDecoder().decode(o.get("structure").getAsString());
		if (nbt.length > MAX_BYTES) {
			throw new GameException("Carte « " + d.nom + " » trop grande.");
		}
		try {
			// Vérifie que la structure se lit avant de l'enregistrer.
			NbtIo.readCompressed(new ByteArrayInputStream(nbt), NbtAccounter.create(MAX_NBT));
			Files.createDirectories(dir);
			Files.write(dir.resolve(d.id + ".nbt"), nbt);
			Files.writeString(dir.resolve(d.id + ".json"), GSON.toJson(d), StandardCharsets.UTF_8);
			Files.deleteIfExists(dir.resolve(d.id + ".png"));
			byte[] png = checkedPreview(o);
			if (png != null) {
				Files.write(dir.resolve(d.id + ".png"), png);
			}
		} catch (IOException | RuntimeException e) {
			throw new GameException("Carte « " + d.nom + " » illisible.");
		}
		return d.nom;
	}

	/** L'aperçu joint à une carte partagée, s'il y en a un et que c'est bien une petite image PNG ; sinon null. */
	private static byte[] checkedPreview(JsonObject o) {
		if (!o.has("apercu")) {
			return null;
		}
		try {
			byte[] png = Base64.getDecoder().decode(o.get("apercu").getAsString());
			if (png.length > 2 * 1024 * 1024) {
				return null;
			}
			try (var in = javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
				var readers = javax.imageio.ImageIO.getImageReadersByFormatName("png");
				if (!readers.hasNext()) {
					return null;
				}
				var reader = readers.next();
				try {
					// Seulement l'en-tête : on ne décode pas une image dont la taille annoncée serait démesurée.
					reader.setInput(in);
					int side = 2 * MAX_RADIUS + 1;
					return reader.getWidth(0) <= side && reader.getHeight(0) <= side ? png : null;
				} finally {
					reader.dispose();
				}
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Aperçu vu de dessus enregistré avec la carte (image PNG), ou null pour une carte d'avant cette version. */
	public byte[] preview(String id) {
		checkId(id);
		try {
			Path f = dir.resolve(id + ".png");
			return Files.exists(f) ? Files.readAllBytes(f) : null;
		} catch (IOException e) {
			return null;
		}
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
