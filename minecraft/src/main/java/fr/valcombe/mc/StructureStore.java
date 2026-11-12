package fr.valcombe.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import fr.valcombe.rules.GameException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;

/**
 * Catalogue de structures à poser sur une carte : celles que le mod fournit (bâties par le code du
 * village généré, jamais écrites sur le disque) et celles de l'utilisateur (sélection enregistrée,
 * fichier importé, pack reçu). Fichiers : config/valcombe/creations/structures/<id>.json et <id>.nbt
 * (format des structures de Minecraft, lisible par un bloc de structure).
 */
public final class StructureStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String ID = "[a-z0-9-]{2,30}";
	/** Plus grande structure du catalogue : côtés, hauteur, et nombre de cases (celui d'une opération de l'éditeur). */
	public static final int MAX_SIDE = 128;
	public static final int MAX_HEIGHT = 128;
	public static final int MAX_VOLUME = MapEditor.MAX_VOLUME;
	/** Taille maximale d'un fichier de structure (compressé), et mémoire accordée à sa lecture. */
	static final int MAX_BYTES = 4 * 1024 * 1024;
	/** Taille maximale d'un fichier du dossier d'import (une carte entière peut y être déposée). */
	private static final long MAX_INBOX_BYTES = 8L * MAX_BYTES;
	private static final long MAX_NBT = 64L * 1024 * 1024;
	/** Nombre de structures de l'utilisateur. */
	static final int MAX_COUNT = 300;
	public static final List<String> MARKS = List.of("", "maison", "salle");

	/** Fiche d'une structure. */
	public static final class Def {
		public String id = "";
		public String nom = "";
		/** Rayon du catalogue : « maisons », « place », « decor », ou « perso » pour celles de l'utilisateur. */
		public String categorie = "perso";
		/** Repère posé avec elle : « maison » (là où l'on dort), « salle » (salle de convocation), ou rien. */
		public String repere = "";
		/** Largeur (est-ouest), hauteur, profondeur (nord-sud). */
		public int[] taille = new int[3];
		/** La case de la structure où se trouvent les pieds de celui qui se tiendrait sur le bloc visé. */
		public int[] ancre = new int[3];
		/** Nombre de blocs posés (l'air compte, pas les cases vides). */
		public int blocs;
		/** Fournie par le mod : ni modifiable, ni supprimable. */
		public boolean fournie;
	}

	/** Une structure fournie : quel morceau du village, dans quel thème. */
	private record Builtin(String id, String nom, String categorie, String repere, String kind, String theme, int variant) {
	}

	private static final List<Builtin> BUILTINS = List.of(
		new Builtin("maison-gothique", "Maison gothique", "maisons", "maison", "maison", "gothique", 0),
		new Builtin("maison-gothique-claire", "Maison gothique en pierre claire", "maisons", "maison", "maison", "gothique", 1),
		new Builtin("maison-monastere", "Cellule du monastère", "maisons", "maison", "maison", "monastere", 0),
		new Builtin("maison-port", "Maison du port", "maisons", "maison", "maison", "port", 0),
		new Builtin("salle-convocation", "Salle de convocation", "maisons", "salle", "salle", "gothique", 0),
		new Builtin("pavillon", "Pavillon de la salle d'attente", "place", "", "pavillon", "gothique", 0),
		new Builtin("puits", "Puits couvert et sa cloche", "place", "", "monument", "gothique", 0),
		new Builtin("cloitre", "Jardin de cloître", "place", "", "monument", "monastere", 0),
		new Builtin("fanal", "Fanal du port", "place", "", "monument", "port", 0),
		new Builtin("fontaine", "Fontaine", "place", "", "fontaine", "gothique", 0),
		new Builtin("estrade", "Estrade et son pupitre", "place", "", "estrade", "gothique", 0),
		new Builtin("arche", "Arche", "place", "", "arche", "gothique", 0),
		new Builtin("etal-rouge", "Étal de marché rouge", "decor", "", "etal", "gothique", 0),
		new Builtin("etal-bleu", "Étal de marché bleu", "decor", "", "etal", "gothique", 2),
		new Builtin("etal-vert", "Étal de marché vert", "decor", "", "etal", "gothique", 4),
		new Builtin("arbre-sapin", "Sapin", "decor", "", "arbre", "gothique", 0),
		new Builtin("arbre-bouleau", "Bouleau", "decor", "", "arbre", "monastere", 0),
		new Builtin("arbre-chene", "Chêne", "decor", "", "arbre", "port", 0),
		new Builtin("lampadaire", "Lampadaire", "decor", "", "lampadaire", "gothique", 0),
		new Builtin("lampadaire-ames", "Lampadaire à flamme bleue", "decor", "", "lampadaire", "monastere", 0),
		new Builtin("banc", "Banc", "decor", "", "banc", "gothique", 0),
		new Builtin("carre-de-simples", "Carré de simples", "decor", "", "simples", "monastere", 0),
		new Builtin("tonneaux", "Tonneaux et poteau d'amarrage", "decor", "", "tonneaux", "port", 0));

	private final Path dir;
	private MinecraftServer server;
	/** Structures fournies, bâties à la première demande (il faut les blocs du jeu, donc un serveur démarré). */
	private Map<String, Def> builtinDefs;
	private final Map<String, Clip> builtinClips = new LinkedHashMap<>();
	/** Fiches de l'utilisateur, relues du disque à la première demande. */
	private Map<String, Def> user;
	/** La dernière structure de l'utilisateur lue : l'aperçu et la pose la redemandent coup sur coup. */
	private String lastId;
	private Clip lastClip;

	public StructureStore(Path creationsDir) {
		this.dir = creationsDir.resolve("structures");
	}

	public void setServer(MinecraftServer server) {
		this.server = server;
	}

	private HolderLookup.RegistryLookup<Block> blocks() {
		return server.registryAccess().lookupOrThrow(Registries.BLOCK);
	}

	// ------------------------------------------------------------------
	// Lire le catalogue
	// ------------------------------------------------------------------
	private Map<String, Def> builtins() {
		if (builtinDefs == null) {
			Map<String, Def> defs = new LinkedHashMap<>();
			for (Builtin b : BUILTINS) {
				Clip.Cut cut = Clip.of(Village.prefab(b.kind(), b.theme(), b.variant()));
				Def d = new Def();
				d.id = b.id();
				d.nom = b.nom();
				d.categorie = b.categorie();
				d.repere = b.repere();
				d.fournie = true;
				describe(d, cut.clip());
				// Dans le plan d'un morceau du village, les pieds sont en (0, 1, 0).
				d.ancre = new int[] {-cut.min().getX(), 1 - cut.min().getY(), -cut.min().getZ()};
				defs.put(d.id, d);
				builtinClips.put(d.id, cut.clip());
			}
			builtinDefs = defs;
		}
		return builtinDefs;
	}

	private static void describe(Def d, Clip clip) {
		d.taille = new int[] {clip.width, clip.height, clip.length};
		d.blocs = clip.count();
	}

	private Map<String, Def> user() {
		if (user == null) {
			Map<String, Def> defs = new LinkedHashMap<>();
			if (Files.isDirectory(dir)) {
				try (Stream<Path> s = Files.list(dir)) {
					for (Path f : s.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
						try {
							Def d = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Def.class);
							if (d != null && d.id != null && d.id.matches(ID) && f.getFileName().toString().equals(d.id + ".json")
								&& Files.exists(dir.resolve(d.id + ".nbt")) && !builtins().containsKey(d.id)) {
								d.fournie = false;
								d.categorie = "perso";
								defs.put(d.id, checked(d));
							}
						} catch (IOException | RuntimeException e) {
							ValcombeMod.LOG.warn("Structure illisible {}", f, e);
						}
					}
				} catch (IOException e) {
					ValcombeMod.LOG.warn("Dossier des structures illisible {}", dir, e);
				}
			}
			user = defs;
		}
		return user;
	}

	/** Relit le dossier (des fichiers y ont été déposés à la main). */
	public void reload() {
		user = null;
		lastId = null;
		lastClip = null;
	}

	/** Tout le catalogue : les structures fournies dans leur ordre, puis celles de l'utilisateur par nom. */
	public List<Def> list() {
		List<Def> out = new ArrayList<>(builtins().values());
		List<Def> mine = new ArrayList<>(user().values());
		mine.sort(Comparator.comparing(d -> d.nom.toLowerCase(Locale.ROOT)));
		out.addAll(mine);
		return out;
	}

	/** Identifiants du catalogue (pour les suggestions des commandes) ; mineOnly : seulement ceux de l'utilisateur. */
	public List<String> ids(boolean mineOnly) {
		List<String> out = new ArrayList<>();
		if (!mineOnly) {
			out.addAll(builtins().keySet());
		}
		out.addAll(user().keySet());
		return out;
	}

	public Def def(String id) {
		checkId(id);
		Def d = builtins().get(id);
		if (d == null) {
			d = user().get(id);
		}
		if (d == null) {
			throw new GameException("Structure introuvable : " + id + ". Voir le catalogue : /vc structure liste.");
		}
		return d;
	}

	/** Les blocs d'une structure du catalogue. */
	Clip clip(String id) {
		Def d = def(id);
		if (d.fournie) {
			return builtinClips.get(id);
		}
		if (id.equals(lastId)) {
			return lastClip;
		}
		Clip clip;
		try {
			Path f = dir.resolve(id + ".nbt");
			if (Files.size(f) > MAX_BYTES) {
				throw new GameException("Structure « " + d.nom + " » : fichier trop gros.");
			}
			clip = read(Files.readAllBytes(f));
		} catch (IOException e) {
			throw new GameException("Structure « " + d.nom + " » illisible : " + e.getMessage());
		}
		lastId = id;
		lastClip = clip;
		return clip;
	}

	/** Lit une structure dans les bornes du catalogue ; un fichier qui en sort est refusé. */
	private Clip read(byte[] nbt) {
		CompoundTag tag;
		try {
			tag = NbtIo.readCompressed(new ByteArrayInputStream(nbt), NbtAccounter.create(MAX_NBT));
		} catch (IOException | RuntimeException e) {
			throw new GameException("Structure illisible : ce n'est pas un fichier de structure Minecraft.");
		}
		Clip clip = Clip.read(tag, blocks(), MAX_SIDE, MAX_HEIGHT, MAX_SIDE);
		checkSize(clip.width, clip.height, clip.length);
		return clip;
	}

	// ------------------------------------------------------------------
	// Contrôles
	// ------------------------------------------------------------------
	private static void checkId(String id) {
		if (id == null || !id.matches(ID)) {
			throw new GameException("Identifiant de structure : 2 à 30 lettres minuscules, chiffres ou tirets.");
		}
	}

	/** Une structure du catalogue reste d'une taille qu'on pose sans figer le serveur. */
	static void checkSize(int width, int height, int length) {
		if (width <= 0 || height <= 0 || length <= 0) {
			throw new GameException("Structure vide.");
		}
		if (width > MAX_SIDE || length > MAX_SIDE || height > MAX_HEIGHT || (long) width * height * length > MAX_VOLUME) {
			throw new GameException("Structure trop grande (" + width + " × " + height + " × " + length + ") : " + MAX_SIDE + " × "
				+ MAX_HEIGHT + " × " + MAX_SIDE + " et " + MAX_VOLUME + " blocs au plus.");
		}
	}

	/** Une fiche venue d'un fichier ou d'un pack : nom, repère et ancre ramenés dans des bornes raisonnables. */
	static Def checked(Def d) {
		checkId(d.id);
		if (d.nom == null || d.nom.isBlank()) {
			d.nom = d.id;
		}
		d.nom = d.nom.trim();
		if (d.nom.length() > 50) {
			throw new GameException("Structure « " + d.id + " » : nom trop long (50 caractères au plus).");
		}
		if (d.repere == null || !MARKS.contains(d.repere)) {
			d.repere = "";
		}
		if (d.taille == null || d.taille.length != 3) {
			d.taille = new int[3];
		}
		// Une ancre absente ou hors de la structure : le milieu de sa base.
		if (d.ancre == null || d.ancre.length != 3 || d.ancre[0] < 0 || d.ancre[0] >= Math.max(1, d.taille[0]) || d.ancre[2] < 0
			|| d.ancre[2] >= Math.max(1, d.taille[2]) || d.ancre[1] < 0 || d.ancre[1] > d.taille[1]) {
			d.ancre = new int[] {d.taille[0] / 2, 0, d.taille[2] / 2};
		}
		return d;
	}

	private void requireEditable(String id) {
		checkId(id);
		if (builtins().containsKey(id)) {
			throw new GameException("« " + builtins().get(id).nom + " » est fournie avec le mod : elle ne se modifie pas. "
				+ "Choisissez un autre identifiant.");
		}
	}

	// ------------------------------------------------------------------
	// Enregistrer, renommer, supprimer
	// ------------------------------------------------------------------
	/**
	 * Range une structure dans le catalogue (ou remplace celle de même identifiant). ancre : la case où
	 * se tiendront les pieds de celui qui se trouve sur le bloc visé ; null : le milieu de la base.
	 */
	String save(String id, String nom, Clip clip, BlockPos ancre, String repere) {
		requireEditable(id);
		checkSize(clip.width, clip.height, clip.length);
		boolean existed = user().containsKey(id);
		if (!existed && user().size() >= MAX_COUNT) {
			throw new GameException("Le catalogue compte déjà " + MAX_COUNT + " structures : supprimez-en avant d'en ajouter.");
		}
		Def d = new Def();
		d.id = id;
		d.nom = nom == null || nom.isBlank() ? id : nom;
		d.repere = repere;
		describe(d, clip);
		d.ancre = ancre == null ? null : new int[] {ancre.getX(), ancre.getY(), ancre.getZ()};
		checked(d);
		try {
			Files.createDirectories(dir);
			NbtIo.writeCompressed(clip.toStructure(), dir.resolve(id + ".nbt"));
			Files.writeString(dir.resolve(id + ".json"), GSON.toJson(d), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new GameException("Enregistrement impossible : " + e.getMessage());
		}
		user().put(id, d);
		lastId = id;
		lastClip = clip;
		return "Structure « " + d.nom + " » " + (existed ? "remplacée" : "ajoutée au catalogue") + " (" + clip.dims() + ", " + d.blocs
			+ " blocs).";
	}

	private Def mine(String id) {
		requireEditable(id);
		Def d = user().get(id);
		if (d == null) {
			throw new GameException("Structure introuvable : " + id + ".");
		}
		return d;
	}

	private void writeDef(Def d) {
		try {
			Files.writeString(dir.resolve(d.id + ".json"), GSON.toJson(d), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new GameException("Enregistrement impossible : " + e.getMessage());
		}
	}

	public String rename(String id, String nom) {
		Def d = mine(id);
		if (nom == null || nom.isBlank() || nom.trim().length() > 50) {
			throw new GameException("Nom de structure : 1 à 50 caractères.");
		}
		String old = d.nom;
		d.nom = nom.trim();
		writeDef(d);
		return "Structure « " + old + " » renommée « " + d.nom + " ».";
	}

	/** Choisit le repère posé avec la structure : « maison », « salle », ou « aucun ». */
	public String setMark(String id, String repere) {
		Def d = mine(id);
		String r = repere == null || repere.equals("aucun") ? "" : repere;
		if (!MARKS.contains(r)) {
			throw new GameException("Repère : maison, salle ou aucun.");
		}
		d.repere = r;
		writeDef(d);
		return r.isEmpty() ? "« " + d.nom + " » ne pose plus de repère."
			: "« " + d.nom + " » posera son repère de " + (r.equals("maison") ? "maison" : "salle de convocation") + ".";
	}

	public String delete(String id) {
		Def d = mine(id);
		try {
			Files.deleteIfExists(dir.resolve(id + ".json"));
			Files.deleteIfExists(dir.resolve(id + ".nbt"));
		} catch (IOException e) {
			throw new GameException("Suppression impossible : " + e.getMessage());
		}
		user().remove(id);
		if (id.equals(lastId)) {
			lastId = null;
			lastClip = null;
		}
		return "Structure « " + d.nom + " » supprimée du catalogue.";
	}

	// ------------------------------------------------------------------
	// Import d'un fichier (.schem, .litematic, .nbt) comme structure
	// ------------------------------------------------------------------
	/** Un identifiant tiré d'un nom de fichier ou d'un nom libre : « Ma Tour.schem » → « ma-tour ». */
	static String slug(String name) {
		String base = name.replaceAll("\\.[A-Za-z]+$", "");
		String s = java.text.Normalizer.normalize(base, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
			.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		if (s.length() > 30) {
			s = s.substring(0, 30).replaceAll("-+$", "");
		}
		return s.length() < 2 ? "structure" : s;
	}

	/**
	 * Ajoute au catalogue une construction faite ailleurs, sans toucher à la carte. id et nom peuvent
	 * être vides : ils sont alors tirés du nom du fichier.
	 */
	public String importFile(String fileName, byte[] data, String id, String nom) {
		if (data.length > 8 * MAX_BYTES) {
			throw new GameException("Fichier trop gros pour une structure du catalogue.");
		}
		String name = Path.of(fileName.replace('\\', '/')).getFileName().toString();
		Clip clip = SchematicImporter.read(name, data, blocks());
		String useId = id == null || id.isBlank() ? slug(name) : id.trim().toLowerCase(Locale.ROOT);
		if (id == null || id.isBlank()) {
			// Un identifiant tiré du nom du fichier ne remplace jamais une structure déjà rangée : il reçoit un numéro.
			String base = useId.length() > 26 ? useId.substring(0, 26) : useId;
			for (int n = 2; builtins().containsKey(useId) || user().containsKey(useId); n++) {
				useId = base + "-" + n;
			}
		}
		String useNom = nom == null || nom.isBlank() ? name.replaceAll("\\.[A-Za-z]+$", "") : nom;
		if (useNom.length() > 50) {
			useNom = useNom.substring(0, 50);
		}
		return save(useId, useNom, clip, null, "");
	}

	/** Dossier où déposer des fichiers à importer depuis le jeu : config/valcombe/creations/import. */
	private Path inbox() {
		return dir.resolveSibling("import");
	}

	/** Les fichiers de construction déposés dans le dossier d'import. */
	public List<String> importable() {
		Path in = inbox();
		if (!Files.isDirectory(in)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(in)) {
			return s.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
				.filter(n -> n.toLowerCase(Locale.ROOT).matches(".*\\.(schem|litematic|nbt)")).sorted().limit(100).toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/** Le dossier d'import, tel qu'on le montre à l'opérateur ; il est créé s'il manque. */
	public String inboxPath() {
		try {
			Files.createDirectories(inbox());
		} catch (IOException e) {
			ValcombeMod.LOG.warn("Dossier d'import non créé", e);
		}
		return inbox().toAbsolutePath().toString();
	}

	/** Le contenu d'un fichier déposé dans le dossier d'import (et seulement là), pour en faire une structure ou une carte. */
	public byte[] inboxFile(String fileName) {
		if (!importable().contains(fileName)) {
			throw new GameException("Fichier introuvable : déposez un .schem, .litematic ou .nbt dans " + inboxPath()
				+ ", puis relancez la commande.");
		}
		try {
			Path f = inbox().resolve(fileName);
			if (Files.size(f) > MAX_INBOX_BYTES) {
				throw new GameException("Fichier trop gros (" + MAX_INBOX_BYTES / (1024 * 1024) + " Mo au plus).");
			}
			return Files.readAllBytes(f);
		} catch (IOException e) {
			throw new GameException("Fichier illisible : " + e.getMessage());
		}
	}

	/** Importe comme structure un fichier déposé dans le dossier d'import. */
	public String importFromFolder(String fileName) {
		return importFile(fileName, inboxFile(fileName), null, null);
	}

	// ------------------------------------------------------------------
	// Partage (dans un fichier .valcombe)
	// ------------------------------------------------------------------
	public JsonObject exportStructure(String id) {
		Def d = id != null && id.matches(ID) ? user().get(id) : null;
		if (d == null) {
			return null; // les structures fournies n'ont pas à voyager : chacun les a
		}
		try {
			JsonObject o = GSON.toJsonTree(d).getAsJsonObject();
			o.addProperty("structure", Base64.getEncoder().encodeToString(Files.readAllBytes(dir.resolve(id + ".nbt"))));
			return o;
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Reçoit une structure d'un pack. Elle est relue case par case puis réécrite : ce qui est rangé dans
	 * le catalogue ne contient ni bloc hors de ses dimensions, ni entité, ni commande cachée.
	 */
	public String importStructure(JsonObject o) {
		Def d = checked(GSON.fromJson(o, Def.class));
		if (!o.has("structure")) {
			throw new GameException("Structure « " + d.nom + " » sans blocs.");
		}
		byte[] nbt;
		try {
			nbt = Base64.getDecoder().decode(o.get("structure").getAsString());
		} catch (RuntimeException e) {
			throw new GameException("Structure « " + d.nom + " » illisible.");
		}
		if (nbt.length > MAX_BYTES) {
			throw new GameException("Structure « " + d.nom + " » trop grosse.");
		}
		Clip clip;
		try {
			clip = read(nbt);
		} catch (GameException e) {
			throw new GameException("Structure « " + d.nom + " » refusée : " + e.getMessage());
		}
		d.taille = new int[] {clip.width, clip.height, clip.length};
		checked(d); // l'ancre est jugée sur la taille réelle, pas sur celle que la fiche annonçait
		save(d.id, d.nom, clip, new BlockPos(d.ancre[0], d.ancre[1], d.ancre[2]), d.repere);
		return d.nom;
	}
}
