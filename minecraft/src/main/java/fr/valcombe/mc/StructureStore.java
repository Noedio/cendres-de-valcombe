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
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/**
 * Catalogue de structures à poser sur une carte : celles que le mod fournit (bâties par le code du
 * village généré) et celles de l'utilisateur (sélection enregistrée, fichier importé, pack reçu, parcelle de
 * l'atelier structures). Fichiers, dans config/valcombe/creations/structures :
 * <ul>
 * <li>{@code <id>.json} et {@code <id>.nbt} : les structures de l'utilisateur (format des structures de Minecraft,
 * lisible par un bloc de structure) ;</li>
 * <li>{@code surcharges/<id>.json} et {@code surcharges/<id>.nbt} : une structure fournie modifiée dans l'atelier
 * structures. Elle remplace l'originale <b>partout</b> : village généré (voir {@link Village#setPieces}), éditeur
 * de carte, Atelier web et en jeu, cartes qui la posent. « Revenir à l'originale » ({@link #revert}) la retire ;</li>
 * <li>{@code corbeille/} : ce qu'on supprime ou qu'on abandonne (structure de l'utilisateur supprimée, surcharge
 * retirée) n'est jamais effacé : les fichiers y sont déplacés, datés, et se remettent à la main en cas de regret.</li>
 * </ul>
 *
 * <h2>API publique (pour les autres parties du mod)</h2>
 * <ul>
 * <li>Lire : {@link #list()}, {@link #def(String)}, {@link #ids(boolean)}, {@link #exists(String)},
 * {@link #isBuiltin(String)}, {@link #isOverridden(String)}, {@link #signature(String)}.</li>
 * <li>Enregistrer une construction d'un monde : {@link #saveFromWorld} (structure de l'utilisateur) et
 * {@link #overrideFromWorld} (structure fournie modifiée). C'est l'entrée de « Sélection WorldEdit → structure du
 * catalogue » et de l'atelier structures.</li>
 * <li>Revenir à l'originale : {@link #revert(String)}. Renommer, choisir le repère, supprimer : {@link #rename},
 * {@link #setMark}, {@link #delete}.</li>
 * </ul>
 * Fil du serveur pour tout ce qui lit ou écrit des blocs.
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
	/** Dossier des structures fournies modifiées, et dossier de la corbeille (sous le dossier des structures). */
	static final String OVERRIDES = "surcharges";
	static final String TRASH = "corbeille";
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

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
		/** Fournie par le mod : ni supprimable, ni renommable ; elle se modifie dans l'atelier structures. */
		public boolean fournie;
		/** Fournie, et modifiée dans l'atelier structures : c'est la version modifiée qui sert partout. */
		public boolean modifiee;
		/** Date de la modification (« 2026-11-13 21:04 »), pour une structure fournie modifiée ; null sinon. */
		public String modifieeLe;

		/** Une copie de cette fiche (les fiches des structures fournies ne sont jamais changées sur place). */
		Def copy() {
			Def d = new Def();
			d.id = id;
			d.nom = nom;
			d.categorie = categorie;
			d.repere = repere;
			d.taille = taille.clone();
			d.ancre = ancre.clone();
			d.blocs = blocs;
			d.fournie = fournie;
			d.modifiee = modifiee;
			d.modifieeLe = modifieeLe;
			return d;
		}
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

	/** Les structures fournies par identifiant (sans bâtir leurs blocs : utilisable sans Minecraft lancé). */
	private static final Map<String, Builtin> BUILTIN_BY_ID = new LinkedHashMap<>();

	static {
		for (Builtin b : BUILTINS) {
			BUILTIN_BY_ID.put(b.id(), b);
		}
	}

	/** Morceaux du village bâtis de la même façon quel que soit le thème (ni la palette ni le thème n'y entrent). */
	private static final List<String> THEME_FREE = List.of("etal", "simples", "pavillon", "banc");

	private final Path dir;
	private MinecraftServer server;
	/** Structures fournies, bâties à la première demande (il faut les blocs du jeu, donc un serveur démarré). */
	private Map<String, Def> builtinDefs;
	private final Map<String, Clip> builtinClips = new LinkedHashMap<>();
	/** Fiches de l'utilisateur, relues du disque à la première demande. */
	private Map<String, Def> user;
	/** Fiches des structures fournies modifiées (surcharges), relues du disque à la première demande. */
	private Map<String, Def> overrides;
	/** Blocs des surcharges déjà lus : le village en demande plusieurs fois les mêmes pendant qu'il se bâtit. */
	private final Map<String, Clip> overrideClips = new HashMap<>();
	/** La dernière structure de l'utilisateur lue : l'aperçu et la pose la redemandent coup sur coup. */
	private String lastId;
	private Clip lastClip;

	public StructureStore(Path creationsDir) {
		this.dir = creationsDir.resolve("structures");
	}

	/**
	 * Branche le catalogue sur le serveur. Le village généré lui demande dès lors, pour chacun de ses morceaux, s'il
	 * a été modifié dans l'atelier structures (voir {@link #piece}).
	 */
	public void setServer(MinecraftServer server) {
		this.server = server;
		Village.setPieces(server == null ? null : this::piece);
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
								&& Files.exists(dir.resolve(d.id + ".nbt")) && !BUILTIN_BY_ID.containsKey(d.id)) {
								d.fournie = false;
								d.modifiee = false;
								d.modifieeLe = null;
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

	/** Les fiches des structures fournies modifiées : seulement celles dont les deux fichiers sont là. */
	private Map<String, Def> overrides() {
		if (overrides == null) {
			Map<String, Def> defs = new LinkedHashMap<>();
			Path in = dir.resolve(OVERRIDES);
			if (Files.isDirectory(in)) {
				try (Stream<Path> s = Files.list(in)) {
					for (Path f : s.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
						try {
							Def d = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Def.class);
							if (d != null && d.id != null && BUILTIN_BY_ID.containsKey(d.id) && f.getFileName().toString().equals(d.id + ".json")
								&& Files.exists(in.resolve(d.id + ".nbt"))) {
								defs.put(d.id, checked(d));
							}
						} catch (IOException | RuntimeException e) {
							ValcombeMod.LOG.warn("Structure fournie modifiée illisible {}", f, e);
						}
					}
				} catch (IOException e) {
					ValcombeMod.LOG.warn("Dossier des structures modifiées illisible {}", in, e);
				}
			}
			overrides = defs;
		}
		return overrides;
	}

	/** Relit le dossier (des fichiers y ont été déposés ou retirés à la main). */
	public void reload() {
		user = null;
		overrides = null;
		overrideClips.clear();
		lastId = null;
		lastClip = null;
	}

	/** La fiche telle qu'on la montre : pour une structure fournie modifiée, la taille, l'ancre et les blocs de sa version modifiée. */
	private Def effective(Def original) {
		Def o = overrides().get(original.id);
		if (o == null) {
			return original;
		}
		Def d = original.copy();
		d.taille = o.taille.clone();
		d.ancre = o.ancre.clone();
		d.blocs = o.blocs;
		d.modifiee = true;
		d.modifieeLe = o.modifieeLe;
		return d;
	}

	/** Tout le catalogue : les structures fournies dans leur ordre, puis celles de l'utilisateur par nom. */
	public List<Def> list() {
		List<Def> out = new ArrayList<>();
		for (Def d : builtins().values()) {
			out.add(effective(d));
		}
		List<Def> mine = new ArrayList<>(user().values());
		mine.sort(Comparator.comparing(d -> d.nom.toLowerCase(Locale.ROOT)));
		out.addAll(mine);
		return out;
	}

	/** Identifiants du catalogue (pour les suggestions des commandes) ; mineOnly : seulement ceux de l'utilisateur. */
	public List<String> ids(boolean mineOnly) {
		List<String> out = new ArrayList<>();
		if (!mineOnly) {
			out.addAll(BUILTIN_BY_ID.keySet());
		}
		out.addAll(user().keySet());
		return out;
	}

	/** Cet identifiant est-il au catalogue (fournie ou de l'utilisateur) ? */
	public boolean exists(String id) {
		return id != null && (BUILTIN_BY_ID.containsKey(id) || id.matches(ID) && user().containsKey(id));
	}

	/** Cet identifiant est-il celui d'une structure fournie par le mod ? (Sans Minecraft lancé.) */
	public static boolean isBuiltin(String id) {
		return id != null && BUILTIN_BY_ID.containsKey(id);
	}

	/** Cette structure fournie a-t-elle été modifiée dans l'atelier structures (une surcharge la remplace) ? */
	public boolean isOverridden(String id) {
		return isBuiltin(id) && overrides().containsKey(id);
	}

	public Def def(String id) {
		checkId(id);
		Def d = builtins().get(id);
		if (d != null) {
			return effective(d);
		}
		d = user().get(id);
		if (d == null) {
			throw new GameException("Structure introuvable : " + id + ". Voir le catalogue : /vc structure liste.");
		}
		return d;
	}

	/** La fiche d'origine d'une structure fournie, même modifiée ; une GameException pour une autre. */
	public Def originalDef(String id) {
		Def d = isBuiltin(id) ? builtins().get(id) : null;
		if (d == null) {
			throw new GameException("« " + id + " » n'est pas une structure fournie par le mod.");
		}
		return d;
	}

	/**
	 * Une empreinte de la version actuelle d'une structure : elle change à chaque enregistrement, modification ou
	 * retour à l'originale (taille et date des fichiers). Vide si la structure n'existe pas. L'atelier structures s'en
	 * sert pour voir qu'une structure a changé ailleurs (éditeur, Atelier web, pack reçu).
	 */
	public String signature(String id) {
		if (isBuiltin(id)) {
			return isOverridden(id) ? "surcharge:" + fileStamp(dir.resolve(OVERRIDES).resolve(id + ".nbt")) : "fournie";
		}
		if (id == null || !id.matches(ID) || !user().containsKey(id)) {
			return "";
		}
		return "perso:" + fileStamp(dir.resolve(id + ".nbt"));
	}

	private static String fileStamp(Path f) {
		try {
			return Files.size(f) + ":" + Files.getLastModifiedTime(f).toMillis();
		} catch (IOException e) {
			return "?";
		}
	}

	/** Les blocs d'une structure du catalogue (pour une structure fournie modifiée : sa version modifiée). */
	Clip clip(String id) {
		Def d = def(id);
		if (d.fournie) {
			return d.modifiee ? overrideClip(id) : builtinClips.get(id);
		}
		if (id.equals(lastId)) {
			return lastClip;
		}
		Clip clip = readFile(dir.resolve(id + ".nbt"), d.nom);
		lastId = id;
		lastClip = clip;
		return clip;
	}

	/** Les blocs d'origine d'une structure fournie, même modifiée. */
	Clip originalClip(String id) {
		originalDef(id);
		return builtinClips.get(id);
	}

	private Clip overrideClip(String id) {
		Clip c = overrideClips.get(id);
		if (c == null) {
			c = readFile(dir.resolve(OVERRIDES).resolve(id + ".nbt"), BUILTIN_BY_ID.get(id).nom() + " (modifiée)");
			overrideClips.put(id, c);
		}
		return c;
	}

	private Clip readFile(Path f, String nom) {
		try {
			if (Files.size(f) > MAX_BYTES) {
				throw new GameException("Structure « " + nom + " » : fichier trop gros.");
			}
			return read(Files.readAllBytes(f));
		} catch (IOException e) {
			throw new GameException("Structure « " + nom + " » illisible : " + e.getMessage());
		}
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
	// Les morceaux du village généré
	// ------------------------------------------------------------------
	/**
	 * La structure fournie qui correspond à un morceau du village généré (sorte, thème, variante), ou null. Dans
	 * l'ordre : la même sorte, le même thème et la même variante ; la même sorte et le même thème ; et, pour une sorte
	 * qui n'a qu'une structure fournie (la salle de convocation, le pavillon...) ou qui se bâtit pareil dans tous les
	 * thèmes (les étals...), celle-là. Exemple : la maison numéro 3 d'un village du monastère (variante 1) prend la
	 * « Cellule du monastère ».
	 */
	static String builtinFor(String kind, String theme, int variant) {
		boolean themeFree = THEME_FREE.contains(kind);
		for (Builtin b : BUILTINS) {
			if (b.kind().equals(kind) && (themeFree || b.theme().equals(theme)) && b.variant() == variant) {
				return b.id();
			}
		}
		for (Builtin b : BUILTINS) {
			if (b.kind().equals(kind) && (themeFree || b.theme().equals(theme))) {
				return b.id();
			}
		}
		List<Builtin> same = BUILTINS.stream().filter(b -> b.kind().equals(kind)).toList();
		return same.size() == 1 ? same.get(0).id() : null;
	}

	/**
	 * Ce que le village généré bâtit à la place d'un de ses morceaux : la version modifiée de la structure fournie
	 * qui lui correspond (voir {@link #builtinFor}), ou null s'il garde le sien. Une surcharge illisible est
	 * signalée dans le journal, et le village garde son morceau d'origine.
	 */
	Village.Piece piece(String kind, String theme, int variant) {
		String id = builtinFor(kind, theme, variant);
		if (id == null || !isOverridden(id)) {
			return null;
		}
		try {
			Def d = overrides().get(id);
			return new Village.Piece(overrideClip(id), d.ancre[0], d.ancre[1], d.ancre[2]);
		} catch (RuntimeException e) {
			ValcombeMod.LOG.warn("Structure modifiée « {} » illisible : le village garde l'originale", id, e);
			return null;
		}
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

	private static void requireEditable(String id) {
		checkId(id);
		Builtin b = BUILTIN_BY_ID.get(id);
		if (b != null) {
			throw new GameException("« " + b.nom() + " » est fournie avec le mod : elle se modifie dans l'atelier structures "
				+ "(/vc structures atelier). Pour une structure à vous, choisissez un autre identifiant.");
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

	/**
	 * Enregistre la version modifiée d'une structure fournie : elle la remplace partout (village généré, éditeur,
	 * Atelier web, cartes qui la posent) jusqu'à {@link #revert}. ancre : la case des pieds (voir {@link Def#ancre}) ;
	 * null : le milieu de la base. Son nom, son rayon et son repère restent ceux de l'originale.
	 */
	String saveOverride(String id, Clip clip, BlockPos ancre) {
		Def original = originalDef(id);
		checkSize(clip.width, clip.height, clip.length);
		Def d = original.copy();
		describe(d, clip);
		d.ancre = ancre == null ? null : new int[] {ancre.getX(), ancre.getY(), ancre.getZ()};
		checked(d);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			NbtIo.writeCompressed(clip.toStructure(), out);
		} catch (IOException e) {
			throw new GameException("Enregistrement impossible : " + e.getMessage());
		}
		writeOverride(d, out.toByteArray());
		overrideClips.put(id, clip);
		return "« " + original.nom + " » modifiée (" + clip.dims() + ", " + d.blocs + " blocs) : cette version remplace l'originale "
			+ "partout, village généré compris. « Revenir à l'originale » la restaure.";
	}

	/**
	 * Écrit les fichiers d'une structure fournie modifiée (la fiche et ses blocs, déjà au format des structures de
	 * Minecraft). Logique de fichiers seule : utilisable sans Minecraft lancé.
	 */
	void writeOverride(Def meta, byte[] nbt) {
		if (!isBuiltin(meta.id)) {
			throw new GameException("« " + meta.id + " » n'est pas une structure fournie par le mod.");
		}
		Def d = meta.copy();
		d.fournie = true;
		d.modifiee = true;
		d.modifieeLe = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
		checked(d);
		Path in = dir.resolve(OVERRIDES);
		try {
			Files.createDirectories(in);
			// Les blocs d'abord, la fiche ensuite : une fiche n'est lue que si ses blocs sont là.
			Path tmp = in.resolve(d.id + ".nbt.tmp");
			Files.write(tmp, nbt);
			Files.move(tmp, in.resolve(d.id + ".nbt"), StandardCopyOption.REPLACE_EXISTING);
			Files.writeString(in.resolve(d.id + ".json"), GSON.toJson(d), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new GameException("Enregistrement impossible : " + e.getMessage());
		}
		overrides().put(d.id, d);
		overrideClips.remove(d.id);
	}

	/**
	 * Revenir à l'originale : la version modifiée d'une structure fournie est retirée (ses fichiers vont à la
	 * corbeille), l'originale sert de nouveau partout.
	 */
	public String revert(String id) {
		Builtin b = BUILTIN_BY_ID.get(id);
		if (b == null) {
			throw new GameException("« " + id + " » n'est pas une structure fournie : il n'y a pas d'originale à retrouver.");
		}
		if (!isOverridden(id)) {
			throw new GameException("« " + b.nom() + " » n'a pas été modifiée : c'est déjà l'originale.");
		}
		Path in = dir.resolve(OVERRIDES);
		toTrash(in.resolve(id + ".json"), in.resolve(id + ".nbt"));
		overrides().remove(id);
		overrideClips.remove(id);
		return "« " + b.nom() + " » est revenue à l'originale, partout. La version modifiée est gardée dans la corbeille.";
	}

	/** Déplace ces fichiers dans la corbeille, datés (rien n'est jamais effacé). */
	private void toTrash(Path... files) {
		Path trash = dir.resolve(TRASH);
		String stamp = LocalDateTime.now().format(STAMP);
		try {
			Files.createDirectories(trash);
			for (Path f : files) {
				if (Files.exists(f)) {
					Files.move(f, trash.resolve(stamp + "-" + f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} catch (IOException e) {
			throw new GameException("Déplacement dans la corbeille impossible : " + e.getMessage());
		}
	}

	/**
	 * Garde dans la corbeille une copie datée de la version actuelle d'une structure, qui reste en place : l'atelier
	 * structures s'en sert avant d'enregistrer une parcelle par-dessus une version changée ailleurs. Renvoie faux s'il
	 * n'y a rien à copier (structure fournie jamais modifiée, structure inconnue).
	 */
	public boolean backup(String id) {
		Path json;
		Path nbt;
		if (isBuiltin(id)) {
			if (!isOverridden(id)) {
				return false;
			}
			json = dir.resolve(OVERRIDES).resolve(id + ".json");
			nbt = dir.resolve(OVERRIDES).resolve(id + ".nbt");
		} else {
			checkId(id);
			json = dir.resolve(id + ".json");
			nbt = dir.resolve(id + ".nbt");
		}
		if (!Files.exists(nbt)) {
			return false;
		}
		Path trash = dir.resolve(TRASH);
		String stamp = LocalDateTime.now().format(STAMP);
		try {
			Files.createDirectories(trash);
			for (Path f : List.of(json, nbt)) {
				if (Files.exists(f)) {
					Files.copy(f, trash.resolve(stamp + "-" + f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} catch (IOException e) {
			throw new GameException("Copie dans la corbeille impossible : " + e.getMessage());
		}
		return true;
	}

	/** Le dossier de la corbeille des structures (pour le dire à l'opérateur et pour les tests). */
	public Path trashDir() {
		return dir.resolve(TRASH);
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

	/** Retire une structure de l'utilisateur du catalogue ; ses fichiers vont à la corbeille. */
	public String delete(String id) {
		Def d = mine(id);
		toTrash(dir.resolve(id + ".json"), dir.resolve(id + ".nbt"));
		user().remove(id);
		if (id.equals(lastId)) {
			lastId = null;
			lastClip = null;
		}
		return "Structure « " + d.nom + " » supprimée du catalogue (gardée dans la corbeille).";
	}

	// ------------------------------------------------------------------
	// Enregistrer une construction d'un monde (API publique)
	// ------------------------------------------------------------------
	/** La boîte [a, b] de ce monde, bornes comprises, avec le contenu des blocs sans rien de dangereux. */
	private static Clip captureBox(ServerLevel level, BlockPos a, BlockPos b) {
		BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		int w = max.getX() - min.getX() + 1;
		int h = max.getY() - min.getY() + 1;
		int l = max.getZ() - min.getZ() + 1;
		checkSize(w, h, l);
		return safe(Clip.capture(level, min, w, h, l));
	}

	/** Le même volume, sans les données des blocs de commande et sans commandes au clic (ce qui part dans un fichier). */
	static Clip safe(Clip clip) {
		Map<Integer, CompoundTag> data = new HashMap<>();
		clip.data.forEach((i, tag) -> {
			CompoundTag t = Clip.safeData(clip.palette.get(clip.cells[i]), tag);
			if (t != null) {
				data.put(i, t);
			}
		});
		return new Clip(clip.width, clip.height, clip.length, clip.palette, clip.cells, data);
	}

	/** Les pieds relatifs au coin le plus bas de la boîte [a, b], ou null s'ils n'y sont pas (une case au-dessus permise). */
	private static BlockPos feetIn(BlockPos a, BlockPos b, BlockPos feet) {
		if (feet == null) {
			return null;
		}
		int x0 = Math.min(a.getX(), b.getX());
		int y0 = Math.min(a.getY(), b.getY());
		int z0 = Math.min(a.getZ(), b.getZ());
		boolean in = feet.getX() >= x0 && feet.getX() <= Math.max(a.getX(), b.getX()) && feet.getZ() >= z0
			&& feet.getZ() <= Math.max(a.getZ(), b.getZ()) && feet.getY() >= y0 && feet.getY() <= Math.max(a.getY(), b.getY()) + 1;
		return in ? feet.offset(-x0, -y0, -z0) : null;
	}

	/**
	 * Range dans le catalogue, comme structure de l'utilisateur, ce que contient la boîte [a, b] de ce monde (bornes
	 * comprises, dans n'importe quel ordre). Remplace la structure de même identifiant. Fil du serveur.
	 *
	 * @param id     identifiant (2 à 30 lettres minuscules, chiffres ou tirets) ; jamais celui d'une structure fournie
	 *               (pour celles-là : {@link #overrideFromWorld})
	 * @param nom    nom affiché (vide : l'identifiant)
	 * @param feet   où se tiendront les pieds de celui qui pose la structure (position du monde), ou null : le milieu de
	 *               la base. Hors de la boîte, il est ignoré.
	 * @param repere « maison », « salle » ou « » (aucun)
	 * @return le message à montrer au joueur
	 */
	public String saveFromWorld(ServerLevel level, BlockPos a, BlockPos b, String id, String nom, BlockPos feet, String repere) {
		requireEditable(id);
		String r = repere == null || repere.equals("aucun") ? "" : repere;
		if (!MARKS.contains(r)) {
			throw new GameException("Repère : maison, salle ou aucun.");
		}
		return save(id, nom, captureBox(level, a, b), feetIn(a, b, feet), r);
	}

	/**
	 * Enregistre ce que contient la boîte [a, b] de ce monde comme version modifiée d'une structure fournie : elle la
	 * remplace partout jusqu'à {@link #revert}. Mêmes paramètres que {@link #saveFromWorld}. Fil du serveur.
	 */
	public String overrideFromWorld(ServerLevel level, BlockPos a, BlockPos b, String id, BlockPos feet) {
		originalDef(id);
		return saveOverride(id, captureBox(level, a, b), feetIn(a, b, feet));
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
	 * Un identifiant libre tiré d'un nom (« Tour d'essai » → « tour-d-essai »), numéroté s'il est déjà pris par une
	 * structure du catalogue ou par taken (« tour-d-essai-2 »).
	 */
	public String freeId(String name, java.util.function.Predicate<String> taken) {
		String base = slug(name);
		String id = base;
		String root = base.length() > 26 ? base.substring(0, 26).replaceAll("-+$", "") : base;
		for (int n = 2; exists(id) || taken.test(id); n++) {
			id = root + "-" + n;
		}
		return id;
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
			for (int n = 2; BUILTIN_BY_ID.containsKey(useId) || user().containsKey(useId); n++) {
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
