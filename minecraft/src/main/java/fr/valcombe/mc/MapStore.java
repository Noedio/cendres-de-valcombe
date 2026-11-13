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
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Bibliothèque de cartes : chaque carte est une structure Minecraft (les blocs, salle d'attente
 * comprise) et une fiche de repères (trônes, maisons, réglages) relatifs au centre de la place.
 * Fichiers : config/valcombe/creations/cartes/<id>.json et <id>.nbt.
 */
public final class MapStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/** Hauteur enregistrée : 4 blocs sous la place, et au moins 24 au-dessus (davantage si la carte monte plus haut). */
	static final int BELOW = 4;
	static final int HEIGHT = 28;
	/** Plus grande hauteur enregistrée : celle qu'on rase pour retirer une carte du monde (voir McHost.clearHeight). */
	static final int MAX_HEIGHT = SchematicImporter.MAX_HEIGHT + 10;
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
		/** Hauteur de la zone enregistrée, les 4 blocs sous la place compris (0 : carte d'avant ce champ, 28 blocs). */
		public int hauteur;

		/** Hauteur réelle de la zone enregistrée (28 blocs pour une carte d'avant le champ « hauteur »). */
		public int height() {
			return hauteur > 0 ? hauteur : HEIGHT;
		}
		public List<int[]> sieges = new ArrayList<>();
		public List<int[]> logis = new ArrayList<>();
		/** Trônes tournés à la main : {dx, dy, dz, angle} (absent : tous regardent le centre). */
		public List<double[]> orientations;
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

	/** La dernière lecture du dossier : l'écran en jeu et la page web redemandent la liste plusieurs fois par seconde. */
	private List<MapDef> listed;
	private long listedAt;

	/** La bibliothèque vient de changer : la prochaine liste relit le dossier. */
	private void changed() {
		listed = null;
	}

	public synchronized List<MapDef> list() {
		// Relue au plus toutes les trois secondes : un fichier déposé à la main finit tout de même par apparaître.
		if (listed == null || System.currentTimeMillis() - listedAt > 3000) {
			listed = List.copyOf(read());
			listedAt = System.currentTimeMillis();
		}
		return listed;
	}

	private List<MapDef> read() {
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

	/** La fiche de cette carte, relue sur le disque et contrôlée (voir checkDef) ; une GameException sinon. */
	MapDef checkedDef(String id) {
		MapDef d = def(id);
		checkDef(d);
		return d;
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
	static final int MAX_RADIUS = Village.MAX_EXTENT;
	private static final int MAX_MARKERS = 40;
	/** Mémoire accordée à la lecture d'une structure (une archive piégée ne peut pas en demander plus). */
	private static final long MAX_NBT = 256L * 1024 * 1024;

	/**
	 * Une fiche doit rester dans des bornes raisonnables : les mêmes à l'enregistrement, au chargement et à
	 * l'import d'un fichier partagé, pour qu'une carte enregistrée puisse toujours être rechargée.
	 */
	static void checkDef(MapDef d) {
		checkId(d.id);
		if (d.nom == null || d.nom.isBlank() || d.nom.length() > 50) {
			throw new GameException("Carte « " + d.id + " » : nom absent ou trop long.");
		}
		if (d.rayon < 4 || d.rayon > MAX_RADIUS) {
			throw new GameException("Carte « " + d.nom + " » : taille hors limites (" + MAX_RADIUS + " blocs de rayon au plus).");
		}
		if (d.hauteur != 0 && (d.hauteur < HEIGHT || d.hauteur > MAX_HEIGHT)) {
			throw new GameException("Carte « " + d.nom + " » : hauteur hors limites (" + MAX_HEIGHT + " blocs au plus).");
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

	static void checkId(String id) {
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
		if (host.busy()) {
			throw new GameException("Une construction est en cours : enregistrez la carte à la fin de la barre de progression.");
		}
		boolean replaced = exists(id);
		MapDef d = write(v, id, nom);
		v.setLibrary(d.id, d.nom);
		host.mapSaved(v);
		return "Carte « " + d.nom + " » enregistrée (" + d.sieges.size() + " trônes, " + d.logis.size() + " maisons)"
			+ (replaced ? " : elle remplace la version déjà rangée sous l'identifiant « " + id + " »." : ".");
	}

	/**
	 * Sauvegarde automatique d'une carte retouchée à la main, juste avant qu'une autre ne la remplace :
	 * elle va dans la bibliothèque telle quelle (même inachevée), sous un nom daté. Renvoie ce nom.
	 */
	String backup(Village v) {
		var now = java.time.LocalDateTime.now();
		String base = "sauvegarde-" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		String id = base;
		for (int n = 2; Files.exists(dir.resolve(id + ".json")); n++) {
			id = base + "-" + n;
		}
		String nom = "Sauvegarde du " + now.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH'h'mm"));
		String written = write(v, id, nom).nom;
		java.util.Map<String, String> names = new java.util.HashMap<>();
		for (MapDef d : list()) {
			names.put(d.id, d.nom);
		}
		for (String old : staleBackups(names)) {
			try {
				delete(old);
			} catch (GameException e) {
				// Une vieille sauvegarde qui ne part pas n'empêche pas de changer de carte.
			}
		}
		return written;
	}

	/** Sauvegardes automatiques gardées : au-delà, les plus anciennes laissent la place. */
	static final int KEPT_BACKUPS = 5;

	/**
	 * Parmi les cartes (identifiant → nom), les sauvegardes automatiques à supprimer : les plus anciennes au-delà
	 * de KEPT_BACKUPS. Une sauvegarde que l'on a renommée est une carte que l'on veut garder : elle ne compte pas.
	 */
	static List<String> staleBackups(java.util.Map<String, String> names) {
		List<String> auto = new ArrayList<>();
		for (var e : names.entrySet()) {
			if (e.getKey().startsWith("sauvegarde-") && e.getValue() != null && e.getValue().startsWith("Sauvegarde du ")) {
				auto.add(e.getKey());
			}
		}
		auto.sort(null); // l'identifiant porte la date : l'ordre alphabétique est celui du temps
		return auto.size() <= KEPT_BACKUPS ? List.of() : new ArrayList<>(auto.subList(0, auto.size() - KEPT_BACKUPS));
	}

	/** Écrit la carte v (blocs, repères, aperçu) dans la bibliothèque. */
	private MapDef write(Village v, String id, String nom) {
		if (v.farthest() > MAX_RADIUS) {
			throw new GameException("Un repère est à plus de " + (MAX_RADIUS - 8) + " blocs de la place : rapprochez-le ou retirez-le "
				+ "avant d'enregistrer la carte.");
		}
		ServerLevel level = server.overworld();
		int r = v.extent();
		BlockPos c = v.center();
		BlockPos corner = c.offset(-r, -BELOW, -r);
		// Toute la hauteur de la carte : une construction importée ou bâtie à la main dépasse souvent les 24 blocs
		// d'un village, et ce qui ne serait pas enregistré ici serait perdu quand une autre carte la remplace.
		int height = Math.max(HEIGHT, Math.min(MAX_HEIGHT, host.clearHeight(v)));
		Clip clip = Clip.capture(level, corner, 2 * r + 1, height, 2 * r + 1);
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
		d.hauteur = height;
		d.sieges = v.relSeats();
		d.logis = v.relHouses();
		d.orientations = v.relSeatYaws();
		d.convocation = v.relConvocation();
		checkDef(d); // avant de calculer l'aperçu : une carte qu'on ne pourrait pas recharger n'est pas enregistrée
		return writeMap(d, clip, host.topView(v));
	}

	/**
	 * Écrit une carte dans la bibliothèque : sa fiche d, ses blocs (le volume capturé autour de la place, coin le plus
	 * bas BELOW blocs sous le centre) et son aperçu vu de dessus (png, ou null). Sert à l'enregistrement de la carte
	 * du monde principal comme à la capture d'une carte posée dans un monde de Valcombe (voir MapPlacer). La fiche est
	 * contrôlée avant toute écriture : une carte qu'on ne pourrait pas recharger n'est pas enregistrée.
	 */
	MapDef writeMap(MapDef d, Clip clip, byte[] png) {
		checkDef(d);
		int side = 2 * d.rayon + 1;
		if (clip.width != side || clip.length != side || clip.height != d.height()) {
			throw new IllegalArgumentException("Volume " + clip.dims() + " différent de l'emprise de la fiche");
		}
		stripAir(clip);
		changed();
		try {
			Files.createDirectories(dir);
			if (png != null) {
				Files.write(dir.resolve(d.id + ".png"), png); // l'aperçu que l'Atelier met sous le plan
			} else {
				Files.deleteIfExists(dir.resolve(d.id + ".png"));
			}
			NbtIo.writeCompressed(clip.toStructure(), dir.resolve(d.id + ".nbt"));
			Files.writeString(dir.resolve(d.id + ".json"), GSON.toJson(d), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new GameException("Enregistrement impossible : " + e.getMessage());
		}
		return d;
	}

	/**
	 * L'air au-dessus du sol n'est pas écrit : le terrain est rasé avant qu'une carte soit posée (fichier bien plus
	 * léger). Les BELOW rangées du sous-sol et celle de la place, elles, sont gardées entières.
	 */
	static void stripAir(Clip clip) {
		for (int i = clip.width * clip.length * (BELOW + 1); i < clip.cells.length; i++) {
			if (clip.cells[i] != Clip.EMPTY && clip.palette.get(clip.cells[i]).isAir()) {
				clip.cells[i] = Clip.EMPTY;
			}
		}
	}

	/**
	 * Les blocs d'une carte de la bibliothèque, relus case par case dans l'emprise que sa fiche annonce : un fichier
	 * fabriqué ne peut ni poser un bloc hors de la carte, ni créer d'entités, ni glisser de commande dans un bloc
	 * (voir Clip). Le coin le plus bas du volume se pose BELOW blocs sous le centre de la place, à -rayon en x et en z.
	 */
	Clip readClip(MapDef d) {
		try {
			CompoundTag tag = NbtIo.readCompressed(dir.resolve(d.id + ".nbt"), NbtAccounter.create(MAX_NBT));
			return Clip.read(tag, server.registryAccess().lookupOrThrow(Registries.BLOCK), 2 * d.rayon + 1, d.height(), 2 * d.rayon + 1);
		} catch (IOException | RuntimeException e) {
			throw new GameException("Structure de la carte illisible : " + e.getMessage());
		}
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
		host.requireFree();
		Clip clip = readClip(d);
		ServerLevel level = server.overworld();
		BlockPos c = host.mapCenter();
		BlockPos corner = c.offset(-d.rayon, -BELOW, -d.rayon);
		// L'ancienne carte est retirée, le terrain rasé, puis la carte posée : le tout par petits lots (le serveur reste fluide).
		BuildQueue.Plan build = new BuildQueue.Plan().first(host.clearPart(c, d.rayon, BELOW, d.height()))
			.then(clip.plan(level, corner, false, false));
		String saved = host.replaceMap("Chargement de « " + d.nom + " »", build,
			true, () -> {
				Village v = Village.restored(c, d.genere, d.theme, d.places, d.taille, d.maisons, d.salle, d.sieges, d.logis, d.convocation);
				v.setLibrary(d.id, d.nom);
				v.setRelSeatYaws(d.orientations);
				v.placeThrones(server);
				host.useVillage(v);
				host.announceAll("Carte « " + d.nom + " » chargée.");
			});
		return "Chargement de la carte « " + d.nom + " » lancé." + McHost.PROGRESS + saved;
	}

	public String delete(String id) {
		MapDef d = def(id);
		changed();
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
		changed();
		Village v = host.village();
		if (v != null && id.equals(v.libraryId())) {
			v.setLibrary(id, d.nom); // la carte actuelle porte ce nom
			v.save(server);
		}
		try {
			Files.writeString(dir.resolve(id + ".json"), GSON.toJson(d), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new GameException("Renommage impossible : " + e.getMessage());
		}
		return "Carte « " + old + " » renommée « " + d.nom + " ».";
	}

	/** Identifiant libre pour une copie : « port-copie », puis « port-copie-2 »… */
	private String copyId(String id) {
		String base = (id.length() > 22 ? id.substring(0, 22) : id) + "-copie";
		String out = base;
		for (int n = 2; Files.exists(dir.resolve(out + ".json")); n++) {
			out = base + "-" + n;
		}
		return out;
	}

	/**
	 * Copie une carte sous un nouvel identifiant, pour en faire une variante. newId et nom sont facultatifs :
	 * sans eux, la copie s'appelle « <nom> (copie) » sous un identifiant libre.
	 */
	public String duplicate(String id, String newId, String nom) {
		MapDef d = def(id);
		if (newId == null || newId.isBlank()) {
			newId = copyId(id);
		}
		checkId(newId);
		if (Files.exists(dir.resolve(newId + ".json"))) {
			throw new GameException("Une carte porte déjà l'identifiant « " + newId + " ».");
		}
		changed();
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
		return "Carte copiée : « " + d.nom + " » (identifiant « " + newId + " »).";
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
			// Vérifie avant d'enregistrer que la structure se lit et qu'elle tient dans l'emprise annoncée par la fiche :
			// aucun bloc ne doit y être déclaré hors de ses dimensions.
			Clip.parse(NbtIo.readCompressed(new ByteArrayInputStream(nbt), NbtAccounter.create(MAX_NBT)),
				2 * d.rayon + 1, d.height(), 2 * d.rayon + 1);
		} catch (GameException e) {
			throw new GameException("Carte « " + d.nom + " » refusée : " + e.getMessage());
		} catch (IOException | RuntimeException e) {
			throw new GameException("Carte « " + d.nom + " » illisible.");
		}
		changed();
		try {
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
