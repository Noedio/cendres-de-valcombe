package fr.valcombe.mc.worlds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.valcombe.mc.SafeFile;
import fr.valcombe.rules.GameException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registre des mondes de Valcombe : les fiches ({@link WorldEntry}) enregistrées dans {@code mondes.json}.
 * <p>
 * Cette classe ne connaît pas Minecraft : elle tient la liste, fabrique les identifiants, vérifie les noms
 * et lit ou écrit le fichier. Le {@link WorldManager} s'en sert et fait le reste (ouvrir, recréer, supprimer
 * les mondes eux-mêmes). Chaque modification est écrite tout de suite, sans jamais laisser un fichier à moitié
 * écrit (copie de secours {@code mondes.json.bak}).
 * <p>
 * Le fichier garde aussi la liste des mondes dont les fichiers restent à effacer ({@code aEffacer}) : une
 * suppression ou une recréation interrompue par un arrêt du serveur est terminée au démarrage suivant.
 * <p>
 * Toutes les méthodes sont sûres entre fils d'exécution (le serveur et l'Atelier web).
 */
public final class WorldRegistry {
	private static final Logger LOG = LoggerFactory.getLogger("valcombe");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Espace de noms des mondes Minecraft de Valcombe : {@code valcombe:<id>}. */
	public static final String NAMESPACE = "valcombe";
	/** Version du format de {@code mondes.json}. */
	public static final int FORMAT = 1;
	/** Identifiant d'un monde : 2 à 30 lettres minuscules sans accents, chiffres ou tirets. */
	public static final String ID = "[a-z0-9-]{2,30}";
	/** Identifiant d'une carte de la bibliothèque (même règle que la bibliothèque). */
	private static final String MAP_ID = "[a-z0-9-]{2,30}";
	/** Mots réservés aux commandes : {@code /vc monde aller principal} mène au monde de la partie. */
	public static final Set<String> RESERVED = Set.of("principal");
	/** Identifiant du monde de la partie principale, créé au premier démarrage (voir WorldManager.ensureMainGame). */
	public static final String MAIN_GAME_ID = "partie-principale";
	/** Nom donné au monde de la partie principale quand il est créé (on peut le renommer ; son identifiant reste). */
	public static final String MAIN_GAME_NAME = "Partie principale";

	/**
	 * Parmi ces fiches, le monde de la partie principale : le monde de partie {@value #MAIN_GAME_ID}, sinon le premier
	 * monde de partie {@code partie-principale-<n>} (créé ainsi quand un dossier sans fiche occupait le nom) ; null
	 * s'il n'y en a pas. Un monde d'une autre sorte qui porterait cet identifiant n'est jamais pris pour lui.
	 */
	public static WorldEntry mainGame(List<WorldEntry> entries) {
		WorldEntry numbered = null;
		for (WorldEntry e : entries) {
			if (e.sorte() != WorldKind.PARTIE) {
				continue;
			}
			if (e.id().equals(MAIN_GAME_ID)) {
				return e;
			}
			if (numbered == null && e.id().matches(java.util.regex.Pattern.quote(MAIN_GAME_ID) + "-\\d+")) {
				numbered = e;
			}
		}
		return numbered;
	}
	public static final int MAX_NAME = 50;
	public static final int MAX_STATE = 500;

	private final Path file;
	private final Clock clock;
	private final Map<String, WorldEntry> entries = new LinkedHashMap<>();
	private final Set<String> wipes = new LinkedHashSet<>();

	/** Registre enregistré dans ce fichier ({@code config/valcombe/mondes.json}) ; rien n'est lu avant {@link #load()}. */
	public WorldRegistry(Path file) {
		this(file, Clock.systemUTC());
	}

	/** Pour les tests : l'horloge qui date les fiches. */
	public WorldRegistry(Path file, Clock clock) {
		this.file = file;
		this.clock = clock;
	}

	public Path file() {
		return file;
	}

	private Instant now() {
		return Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
	}

	// ------------------------------------------------------------------
	// Lecture et écriture de mondes.json
	// ------------------------------------------------------------------

	private static boolean readable(String text) {
		return JsonParser.parseString(text).isJsonObject();
	}

	/**
	 * Relit le fichier (ou sa copie de secours). Un fichier absent donne un registre vide. Un fichier
	 * illisible est mis de côté ({@code mondes.json.illisible}) au lieu d'être écrasé à la prochaine écriture.
	 * Une fiche abîmée (identifiant ou sorte inconnus) est ignorée et signalée dans le journal.
	 */
	public synchronized void load() {
		entries.clear();
		wipes.clear();
		if (!SafeFile.exists(file)) {
			return;
		}
		String text = SafeFile.read(file, WorldRegistry::readable);
		if (text == null) {
			LOG.error("Registre des mondes illisible : {} mis de côté, la liste repart vide.", file);
			try {
				if (Files.exists(file)) {
					Files.copy(file, file.resolveSibling(file.getFileName() + ".illisible"),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException e) {
				LOG.error("Copie du registre illisible impossible", e);
			}
			return;
		}
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		if (root.has("mondes") && root.get("mondes").isJsonArray()) {
			for (JsonElement el : root.getAsJsonArray("mondes")) {
				try {
					WorldEntry e = fromJson(el.getAsJsonObject());
					if (entries.containsKey(e.id())) {
						throw new IllegalArgumentException("identifiant en double");
					}
					entries.put(e.id(), e);
				} catch (RuntimeException ex) {
					LOG.warn("Fiche de monde ignorée dans {} ({}) : {}", file, ex.getMessage(), el);
				}
			}
		}
		if (root.has("aEffacer") && root.get("aEffacer").isJsonArray()) {
			for (JsonElement el : root.getAsJsonArray("aEffacer")) {
				if (el.isJsonPrimitive() && el.getAsString().matches(ID)) {
					wipes.add(el.getAsString());
				}
			}
		}
	}

	private static String string(JsonObject o, String key) {
		JsonElement el = o.get(key);
		return el == null || el.isJsonNull() ? null : el.getAsString();
	}

	private static Instant date(JsonObject o, String key) {
		String s = string(o, key);
		try {
			return s == null ? Instant.EPOCH : Instant.parse(s);
		} catch (DateTimeParseException e) {
			return Instant.EPOCH;
		}
	}

	private static WorldEntry fromJson(JsonObject o) {
		String id = string(o, "id");
		if (!isValidId(id)) {
			throw new IllegalArgumentException("identifiant invalide");
		}
		WorldKind kind = WorldKind.parse(string(o, "sorte"));
		if (kind == null) {
			throw new IllegalArgumentException("sorte inconnue");
		}
		String nom = string(o, "nom");
		if (nom == null || nom.isBlank()) {
			nom = id;
		}
		nom = nom.strip();
		if (nom.length() > MAX_NAME) {
			nom = nom.substring(0, MAX_NAME);
		}
		String carte = string(o, "carte");
		if (carte != null && !carte.matches(MAP_ID)) {
			carte = null;
		}
		String etat = string(o, "etat");
		Instant cree = date(o, "cree");
		Instant modifie = o.has("modifie") ? date(o, "modifie") : cree;
		return new WorldEntry(id, nom, kind, carte, cree, modifie, etat);
	}

	private static JsonObject toJson(WorldEntry e) {
		JsonObject o = new JsonObject();
		o.addProperty("id", e.id());
		o.addProperty("nom", e.nom());
		o.addProperty("sorte", e.sorte().name());
		if (e.carte() != null) {
			o.addProperty("carte", e.carte());
		}
		o.addProperty("cree", e.cree().toString());
		o.addProperty("modifie", e.modifie().toString());
		o.addProperty("etat", e.etat());
		return o;
	}

	/** Le contenu de {@code mondes.json} tel qu'il serait écrit maintenant. */
	synchronized String toJsonText() {
		JsonObject root = new JsonObject();
		root.addProperty("format", FORMAT);
		JsonArray list = new JsonArray();
		for (WorldEntry e : entries.values()) {
			list.add(toJson(e));
		}
		root.add("mondes", list);
		JsonArray pending = new JsonArray();
		wipes.forEach(pending::add);
		root.add("aEffacer", pending);
		return GSON.toJson(root);
	}

	private void save() {
		try {
			SafeFile.write(file, toJsonText());
		} catch (IOException e) {
			LOG.error("Écriture du registre des mondes impossible : " + file, e);
			throw new GameException("Le registre des mondes n'a pas pu être enregistré (" + e.getMessage() + ").");
		}
	}

	// ------------------------------------------------------------------
	// Identifiants et noms
	// ------------------------------------------------------------------

	/** Un identifiant bien formé et non réservé. */
	public static boolean isValidId(String id) {
		return id != null && id.matches(ID) && !RESERVED.contains(id);
	}

	/**
	 * L'identifiant tiré d'un nom : sans accents, en minuscules, les autres signes changés en tirets, coupé à
	 * 30 signes. « Château d'Été » donne {@code chateau-d-ete}. Une chaîne vide si le nom ne garde rien.
	 */
	public static String slug(String name) {
		if (name == null) {
			return "";
		}
		String s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
			.replace("œ", "oe").replace("Œ", "oe").replace("æ", "ae").replace("Æ", "ae")
			.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		if (s.length() > 30) {
			s = s.substring(0, 30).replaceAll("-+$", "");
		}
		return s;
	}

	/** Le nom affiché, nettoyé ; une erreur claire s'il est vide, trop long ou contient des codes de mise en forme. */
	public static String checkName(String name) {
		String n = name == null ? "" : name.strip().replaceAll("\\s+", " ");
		if (n.isEmpty()) {
			throw new GameException("Donnez un nom au monde.");
		}
		if (n.length() > MAX_NAME) {
			throw new GameException("Nom de monde trop long : " + MAX_NAME + " signes au plus.");
		}
		if (n.indexOf('§') >= 0 || n.chars().anyMatch(Character::isISOControl)) {
			throw new GameException("Le nom du monde contient des signes interdits.");
		}
		return n;
	}

	/**
	 * Un identifiant libre tiré du nom (ou de la sorte si le nom ne donne rien) : {@code soiree}, {@code soiree-2}...
	 * Sont pris : les fiches, les effacements en attente, les mots réservés et ceux que {@code taken} refuse.
	 */
	private String freeId(String name, WorldKind kind, Predicate<String> taken) {
		String base = slug(name);
		if (base.length() < 2) {
			base = kind.code();
		}
		String id = base;
		for (int n = 2; entries.containsKey(id) || wipes.contains(id) || RESERVED.contains(id) || taken.test(id); n++) {
			String suffix = "-" + n;
			String head = base.length() + suffix.length() > 30 ? base.substring(0, 30 - suffix.length()).replaceAll("-+$", "") : base;
			id = head + suffix;
		}
		return id;
	}

	// ------------------------------------------------------------------
	// Lecture des fiches
	// ------------------------------------------------------------------

	/** Toutes les fiches : hub d'abord, puis parties, essais et ateliers ; dans une sorte, de la plus ancienne à la plus récente. */
	public synchronized List<WorldEntry> list() {
		List<WorldEntry> all = new ArrayList<>(entries.values());
		all.sort(Comparator.comparing(WorldEntry::sorte).thenComparing(WorldEntry::cree).thenComparing(WorldEntry::id));
		return all;
	}

	/** Les fiches d'une sorte, dans le même ordre que {@link #list()}. */
	public List<WorldEntry> list(WorldKind kind) {
		return list().stream().filter(e -> e.sorte() == kind).toList();
	}

	/** La fiche de ce monde, ou null. */
	public synchronized WorldEntry find(String id) {
		return id == null ? null : entries.get(id.strip().toLowerCase(Locale.ROOT));
	}

	/** La fiche de ce monde ; une erreur à montrer au joueur s'il n'existe pas. */
	public WorldEntry get(String id) {
		WorldEntry e = find(id);
		if (e == null) {
			throw new GameException("Aucun monde « " + id + " ». La liste : /vc monde liste.");
		}
		return e;
	}

	// ------------------------------------------------------------------
	// Modifications (chacune est écrite aussitôt)
	// ------------------------------------------------------------------

	/**
	 * Ajoute une fiche. L'identifiant est tiré du nom et rendu unique ; il n'existe qu'un hub et qu'un atelier
	 * structures. La fiche seule est créée : le monde lui-même est l'affaire du {@link WorldManager}.
	 */
	public WorldEntry create(WorldKind kind, String name) {
		return create(kind, name, id -> false);
	}

	/**
	 * Comme {@link #create(WorldKind, String)}, sans jamais prendre un identifiant que {@code taken} refuse : le
	 * {@link WorldManager} y écarte ceux dont un dossier de monde existe déjà sur le disque (registre illisible mis
	 * de côté, fiche retirée à la main), pour ne jamais effacer ni reprendre un monde qu'il ne connaît pas.
	 */
	public synchronized WorldEntry create(WorldKind kind, String name, Predicate<String> taken) {
		if (kind == null) {
			throw new GameException("Sorte de monde inconnue.");
		}
		String nom = checkName(name);
		if (kind.unique()) {
			for (WorldEntry e : entries.values()) {
				if (e.sorte() == kind) {
					throw new GameException("Il existe déjà un " + kind.label() + " : « " + e.nom() + " » (" + e.id() + ").");
				}
			}
		}
		Instant now = now();
		WorldEntry e = new WorldEntry(freeId(nom, kind, taken), nom, kind, null, now, now, "");
		entries.put(e.id(), e);
		save();
		return e;
	}

	private WorldEntry replace(WorldEntry e) {
		entries.put(e.id(), e);
		save();
		return e;
	}

	/** Change le nom affiché ; l'identifiant (et donc le monde et son dossier) ne change pas. */
	public synchronized WorldEntry rename(String id, String name) {
		return replace(get(id).withNom(checkName(name), now()));
	}

	/** La carte de la bibliothèque que porte ce monde (null : aucune). */
	public synchronized WorldEntry setCarte(String id, String carte) {
		String c = carte == null || carte.isBlank() ? null : carte.strip();
		if (c != null && !c.matches(MAP_ID)) {
			throw new GameException("Identifiant de carte : 2 à 30 lettres minuscules, chiffres ou tirets.");
		}
		return replace(get(id).withCarte(c, now()));
	}

	/** L'état libre du monde (texte court, au choix de qui s'en sert). */
	public synchronized WorldEntry setEtat(String id, String etat) {
		String s = etat == null ? "" : etat.strip();
		if (s.length() > MAX_STATE) {
			throw new GameException("État de monde trop long : " + MAX_STATE + " signes au plus.");
		}
		return replace(get(id).withEtat(s, now()));
	}

	/** Date la fiche de maintenant (après une recréation, par exemple). */
	public synchronized WorldEntry touch(String id) {
		return replace(get(id).touched(now()));
	}

	/** Retire la fiche ; renvoie celle qui était enregistrée. */
	public synchronized WorldEntry remove(String id) {
		WorldEntry e = get(id);
		entries.remove(e.id());
		save();
		return e;
	}

	/** Note que les fichiers de ce monde sont à effacer (fait au plus tard au prochain démarrage). */
	public synchronized void markWipe(String id) {
		if (id != null && id.matches(ID) && wipes.add(id)) {
			save();
		}
	}

	/** Les fichiers de ce monde sont effacés : plus rien à faire. */
	public synchronized void clearWipe(String id) {
		if (wipes.remove(id)) {
			save();
		}
	}

	/** Les mondes dont les fichiers restent à effacer. */
	public synchronized List<String> wipes() {
		return List.copyOf(wipes);
	}
}
