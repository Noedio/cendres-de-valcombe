package fr.valcombe.mc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fr.valcombe.rules.Distribution;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.RoleDef;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Ce que l'Atelier montre des cartes et des structures : une seule source pour la page web
 * (Atelier) et pour l'écran en jeu (ViewSync). Les actions, elles, sont les méthodes de McHost,
 * MapStore, MapEditor, StructureStore et Session, appelées telles quelles par les routes de la
 * page et par les commandes /vc que lance l'écran.
 * <p>
 * La « carte actuelle » est celle de la partie que vise l'opérateur (celle du monde où il se tient, sinon sa table
 * d'attache ou la partie principale, voir {@link fr.valcombe.mc.games.Games#forPlayer}) : jamais « la seule » partie.
 */
public final class Workshop {
	private final CreationStore creations;
	private final MapStore maps;
	private final StructureStore structures;
	private final MapEditor editor;
	private final MinecraftServer server;

	Workshop(MinecraftServer server, CreationStore creations, MapStore maps, StructureStore structures, MapEditor editor) {
		this.server = server;
		this.creations = creations;
		this.maps = maps;
		this.structures = structures;
		this.editor = editor;
	}

	/** La partie que vise cet opérateur (voir la classe), ou null sans parties. */
	static fr.valcombe.mc.games.GameRoom roomOf(ServerPlayer p) {
		return fr.valcombe.mc.games.Games.forPlayer(p);
	}

	// ------------------------------------------------------------------
	// Liste de contrôle d'une carte
	// ------------------------------------------------------------------
	/** Une ligne de la liste de contrôle : ce qui va (ok) ou ce qui manque. */
	public record Check(boolean ok, String texte) {
	}

	/**
	 * Ce qu'il faut à une carte pour qu'on y joue : repères relatifs au centre de la place ([est, hauteur, sud]),
	 * rayon de la zone enregistrée. La même liste sert au plan de la page web et à l'écran en jeu.
	 */
	public static List<Check> checks(List<int[]> seats, List<int[]> houses, int[] room, int radius) {
		List<Check> out = new ArrayList<>();
		int n = seats.size();
		out.add(new Check(n >= Distribution.MIN_PLAYERS, n + " trône(s) : " + (n >= Distribution.MIN_PLAYERS
			? "jusqu'à " + Math.min(Distribution.MAX_PLAYERS, n) + " joueurs ont chacun le leur"
			: "il en faut au moins " + Distribution.MIN_PLAYERS)));
		int h = houses.size();
		out.add(new Check(h == 0 || h >= n, h == 0 ? "Aucune maison : la nuit, chacun reste sur son trône"
			: h + " maison(s)" + (h >= n ? "" : " : moins que de trônes, certains dormiront à plusieurs")));
		out.add(new Check(room != null, room != null ? "Salle de convocation posée : le Conteur y reçoit chaque joueur à l'écart"
			: "Pas de salle de convocation : le Conteur remettra les rôles sur place, en vocal privé"));
		long far = seats.stream().filter(s -> Math.max(Math.abs(s[0]), Math.abs(s[2])) > radius).count();
		out.add(new Check(far == 0, far == 0 ? "Tous les trônes sont dans la zone enregistrée"
			: far + " trône(s) hors de la zone enregistrée"));
		boolean twice = false;
		for (int i = 0; i < n && !twice; i++) {
			for (int j = i + 1; j < n && !twice; j++) {
				twice = Math.hypot(seats.get(i)[0] - seats.get(j)[0], seats.get(i)[2] - seats.get(j)[2]) < 1.5;
			}
		}
		out.add(new Check(!twice, twice ? "Deux trônes sont posés presque au même endroit" : "Aucun trône en double"));
		return out;
	}

	// ------------------------------------------------------------------
	// Cartes
	// ------------------------------------------------------------------
	/** « 13/11 à 14h05 » : la date d'une modification, telle que l'Atelier en jeu la montre. */
	private static final java.time.format.DateTimeFormatter WHEN = java.time.format.DateTimeFormatter
		.ofPattern("dd/MM/yyyy 'à' HH'h'mm", java.util.Locale.FRENCH).withZone(java.time.ZoneId.systemDefault());

	/** Les cartes de la bibliothèque ; plan : avec leurs repères et leur liste de contrôle (plan de la page web). */
	public List<Map<String, Object>> library(boolean plan) {
		List<Map<String, Object>> out = new ArrayList<>();
		MapWorkshop ateliers = ValcombeMod.mapWorkshop();
		for (MapStore.MapDef d : maps.list()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", d.id);
			m.put("nom", d.nom);
			m.put("genere", d.genere);
			m.put("theme", d.theme);
			m.put("sieges", d.sieges.size());
			m.put("maisons", d.logis.size());
			// L'atelier carte où l'on travaille sur elle, s'il est ouvert (un seul par carte) : « Ouvrir » y mène.
			fr.valcombe.mc.worlds.WorldEntry open = ateliers == null ? null : ateliers.atelierOf(d.id);
			m.put("atelier", open == null ? null : open.id());
			m.put("atelierNom", open == null ? null : open.nom());
			// Pour la fiche de l'Atelier en jeu : taille, ce qui manque pour jouer, date, empreinte de la vignette.
			m.put("cote", 2 * d.rayon + 1);
			m.put("hauteur", d.height());
			m.put("places", d.places);
			m.put("salle", d.convocation != null);
			MapTally t = new MapTally(d.sieges.size(), Math.max(d.sieges.size(), d.genere ? d.places : 0), d.logis.size(), d.convocation != null);
			m.put("jouable", t.playable());
			m.put("verdict", t.verdict());
			List<Map<String, Object>> missing = new ArrayList<>();
			for (MapTally.Line l : t.missing()) {
				missing.add(Map.of("bloquant", l.blocking(), "texte", l.text()));
			}
			m.put("manques", missing);
			long at = maps.modifiedAt(d.id);
			m.put("modifieeLe", at);
			m.put("modifieeTexte", at <= 0 ? "" : WHEN.format(java.time.Instant.ofEpochMilli(at)));
			m.put("vignette", maps.stamp(d.id));
			m.put("versions", maps.versions().list(d.id).size());
			m.put("octets", maps.bytes(d.id));
			if (plan) {
				// Repères pour le plan vu de dessus : [est, hauteur, sud] depuis le centre de la place.
				m.put("rayon", d.rayon);
				Map<String, Object> p = new LinkedHashMap<>();
				p.put("sieges", d.sieges);
				p.put("maisons", d.logis);
				p.put("salle", d.convocation);
				m.put("plan", p);
				m.put("controle", checks(d.sieges, d.logis, d.convocation, d.rayon));
			}
			out.add(m);
		}
		return out;
	}

	/** La carte en service dans la partie de cet hôte, ou null ; plan : avec ses repères et les joueurs de son monde. */
	public Map<String, Object> current(McHost host, boolean plan) {
		Village v = host == null ? null : host.village();
		if (v == null) {
			return null;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		// D'où vient la carte : celle de la bibliothèque qu'on a chargée ou enregistrée, et si elle a changé depuis.
		m.put("id", v.libraryId());
		m.put("nom", v.libraryName());
		m.put("modifiee", v.modified());
		m.put("genere", v.isGenerated());
		m.put("theme", v.theme());
		m.put("sieges", v.seatCount());
		m.put("maisons", v.houseCount());
		m.put("salle", v.convocation() != null);
		int radius = Math.min(Village.MAX_EXTENT, v.extent());
		m.put("rayon", radius);
		m.put("controle", checks(v.relSeats(), v.relHouses(), v.relConvocation(), radius));
		if (plan) {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("sieges", v.relSeats());
			p.put("maisons", v.relHouses());
			p.put("salle", v.relConvocation());
			// Les joueurs du monde de la partie, pour se repérer : [nom, est, sud] depuis le centre de la place. Ceux qui
			// sont ailleurs (hub, atelier) n'y sont pas : leurs coordonnées n'ont rien à voir avec ce plan.
			List<List<Object>> here = new ArrayList<>();
			for (ServerPlayer p2 : server.getPlayerList().getPlayers()) {
				if (!host.inGameWorld(p2)) {
					continue;
				}
				here.add(List.of(p2.getScoreboardName(), p2.getBlockX() - v.center().getX(), p2.getBlockZ() - v.center().getZ()));
			}
			p.put("joueurs", here);
			m.put("plan", p);
		}
		return m;
	}

	/** Ce que la carte actuelle est, en une phrase (page web, écran en jeu). */
	public static String describe(Map<String, Object> cur) {
		if (cur == null) {
			return "Aucune carte sur le serveur.";
		}
		String nom = (String) cur.get("nom");
		boolean modified = (Boolean) cur.get("modifiee");
		String origin = !nom.isEmpty() ? "« " + nom + " »" + (modified ? ", retouchée depuis" : "")
			: (Boolean) cur.get("genere") ? "village généré (" + cur.get("theme") + ")" + (modified ? ", retouché" : "")
			: "faite à la main, pas encore enregistrée";
		return "Carte actuelle : " + origin + ". " + cur.get("sieges") + " trônes, " + cur.get("maisons") + " maisons.";
	}

	/** La carte actuelle en quelques mots, pour une ligne de liste (la fiche d'une partie de l'Atelier web). */
	public static String brief(Map<String, Object> cur) {
		if (cur == null) {
			return "Aucune carte sur le serveur.";
		}
		String nom = (String) cur.get("nom");
		return (!nom.isEmpty() ? "« " + nom + " »" : (Boolean) cur.get("genere") ? "Village généré" : "Carte faite à la main")
			+ ((Boolean) cur.get("modifiee") && !nom.isEmpty() ? ", retouchée" : "") + " · " + cur.get("sieges") + " trônes · "
			+ cur.get("maisons") + " maisons";
	}

	// ------------------------------------------------------------------
	// Écran en jeu
	// ------------------------------------------------------------------
	/** Ce que l'écran « Atelier » du jeu affiche à cet opérateur (joint à sa vue de la partie). */
	public Map<String, Object> gameView(ServerPlayer p) {
		Map<String, Object> out = new LinkedHashMap<>();
		fr.valcombe.mc.games.GameRoom room = roomOf(p);
		McHost host = room == null ? null : room.host();
		Map<String, Object> cur = current(host, false);
		out.put("carte", cur);
		out.put("cartes", library(false));
		out.put("chantier", host != null && host.busy());
		out.put("essai", room != null && room.session().trialPending());
		out.put("partie", room == null ? null : room.name()); // la partie dont c'est la carte actuelle
		out.put("partieId", room == null ? null : room.id());
		out.put("editeur", editor.state(p.getScoreboardName()));
		out.put("structures", structureList());
		StructureWorkshop sw = ValcombeMod.structureWorkshop();
		out.put("atelierStructures", sw != null && sw.entry() != null); // l'atelier structures existe (déjà disposé)
		out.put("dansAtelierStructures", sw != null && sw.isWorkshop(p.level()));
		out.put("tailles", StructureWorkshop.SIZES);
		out.put("roles", roleList());
		out.put("scenarios", scenarioList());
		out.put("packs", packs());
		out.put("themes", Village.THEMES);
		out.put("tronesMin", Village.MIN_SEATS);
		out.put("tronesMax", Village.MAX_SEATS);
		out.put("parties", gameWorlds(p));
		// « Nouvelle carte » › importer : les fichiers déposés dans le dossier d'import du serveur.
		out.put("fichiers", structures.importable());
		out.put("dossierImport", inbox());
		List<Map<String, Object>> bin = new ArrayList<>();
		for (MapTrash.Entry e : maps.trashed()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", e.id());
			m.put("carte", e.mapId());
			m.put("nom", e.nom());
			m.put("date", WHEN.format(e.date()));
			m.put("trones", e.seats());
			m.put("maisons", e.houses());
			bin.add(m);
		}
		out.put("corbeille", bin);
		out.put("chantiers", works());
		Reply reply = replies.get(p.getScoreboardName());
		if (reply != null) {
			out.put("reponse", Map.of("n", reply.n(), "texte", reply.text(), "erreur", reply.error()));
		}
		return out;
	}

	/**
	 * Les mondes de partie où l'on peut envoyer une carte (la partie principale d'abord), pour l'Atelier en jeu : ce
	 * qu'ils portent, s'ils reçoivent déjà une carte, si une partie s'y joue.
	 */
	List<Map<String, Object>> gameWorlds(ServerPlayer viewer) {
		MapWorkshop w = ValcombeMod.mapWorkshop();
		if (w == null) {
			return List.of();
		}
		MapSender sender = ValcombeMod.sender();
		fr.valcombe.mc.games.GameRoom main = fr.valcombe.mc.games.Games.main();
		fr.valcombe.mc.games.GameRoom mine = viewer == null ? null : roomOf(viewer);
		List<Map<String, Object>> out = new ArrayList<>();
		for (fr.valcombe.mc.worlds.WorldEntry e : w.parties()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", e.id());
			m.put("nom", e.nom());
			m.put("sorte", e.sorte().label());
			m.put("principale", main != null && main.id().equals(e.id()));
			m.put("carte", e.carte());
			String mapName = null;
			if (e.carte() != null) {
				mapName = maps.list().stream().filter(d -> d.id.equals(e.carte())).map(d -> d.nom).findFirst().orElse(null);
			}
			m.put("carteNom", mapName);
			m.put("envoi", sender == null ? null : sender.sendingTo(e.id()));
			fr.valcombe.mc.games.GameRoom room = fr.valcombe.mc.games.Games.byWorld(e.id());
			m.put("enCours", room != null && room.session().game() != null);
			var level = server.getLevel(fr.valcombe.mc.worlds.WorldManager.key(e.id()));
			m.put("joueurs", level == null ? 0 : level.players().size());
			m.put("vignette", Vignettes.stampOf("partie:" + e.id()));
			// Pour la fiche d'une partie dans l'Atelier : son état (celui du tableau des parties), sa carte en place (d'où
			// elle vient, si elle a été retouchée), son scénario, si c'est la partie que vise l'opérateur (où ses gestes de
			// carte s'appliquent : repères, enregistrer).
			m.put("fiche", room == null ? null : room.card().view());
			m.put("actuelle", room == null ? null : current(room.host(), false));
			m.put("chantier", room != null && room.host().busy());
			m.put("scenario", room == null || room.session().scenario() == null ? null : room.session().scenario().nom);
			m.put("scenarioId", room == null || room.session().scenario() == null ? null : room.session().scenario().id);
			m.put("visee", room != null && room == mine);
			m.put("ici", room != null && viewer != null && room.here(viewer));
			out.add(m);
		}
		return out;
	}

	// ------------------------------------------------------------------
	// Structures, rôles, scénarios et packs, pour l'Atelier en jeu
	// ------------------------------------------------------------------

	/** Le catalogue des structures, chacune avec l'empreinte de sa vignette et ce que l'atelier structures en sait. */
	private List<Map<String, Object>> structureList() {
		StructureWorkshop sw = ValcombeMod.structureWorkshop();
		java.util.Set<String> plots = new java.util.HashSet<>();
		if (sw != null) {
			for (StructureWorkshop.ParcelInfo pi : sw.parcels()) {
				plots.add(pi.id());
			}
		}
		List<Map<String, Object>> list = new ArrayList<>();
		for (StructureStore.Def d : structures.list()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", d.id);
			m.put("nom", d.nom);
			m.put("categorie", d.categorie);
			m.put("taille", d.taille);
			m.put("blocs", d.blocs);
			m.put("repere", d.repere);
			m.put("fournie", d.fournie);
			m.put("modifiee", d.modifiee); // fournie, modifiée dans l'atelier structures
			m.put("modifieeLe", d.modifieeLe);
			m.put("parcelle", plots.contains(d.id));
			m.put("vignette", Vignettes.stampOf("structure:" + d.id));
			list.add(m);
		}
		return list;
	}

	/** Un rôle tel qu'un jeton le montre : son nom, son emblème (pictogramme), s'il est du mal. */
	private Map<String, Object> token(String roleId) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", roleId);
		RoleDef mine = creations.roles().stream().filter(d -> d.id.equals(roleId)).findFirst().orElse(null);
		if (mine != null) {
			m.put("nom", mine.nom);
			m.put("embleme", mine.embleme);
			m.put("mal", mine.type().evil());
			m.put("cree", true);
			return m;
		}
		fr.valcombe.rules.Role r = fr.valcombe.rules.Role.parse(roleId);
		m.put("nom", r == null ? roleId : r.displayName);
		m.put("embleme", r == null ? roleId : roleId.toLowerCase(java.util.Locale.ROOT));
		m.put("mal", r != null && r.type != null && r.type.evil());
		m.put("cree", false);
		m.put("inconnu", r == null);
		return m;
	}

	/** Les rôles créés : leur jeton, leur texte, leurs réveils, et les scénarios qui les citent. */
	private List<Map<String, Object>> roleList() {
		List<Map<String, Object>> out = new ArrayList<>();
		List<fr.valcombe.session.ScenarioDef> scenarios = creations.scenarios();
		for (RoleDef d : creations.roles()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", d.id);
			m.put("nom", d.nom);
			m.put("camp", d.camp);
			m.put("campTexte", d.type().displayName);
			m.put("mal", d.type().evil());
			m.put("embleme", d.embleme);
			m.put("texte", d.texte == null ? "" : d.texte);
			m.put("premiereNuit", wake(d.premiereNuit));
			m.put("autresNuits", wake(d.autresNuits));
			m.put("scenarios", scenarios.stream().filter(s -> s.roles != null && s.roles.contains(d.id)).map(s -> s.nom).toList());
			out.add(m);
		}
		return out;
	}

	/** « jamais », « au début », « après le Moine » : un réveil en clair. */
	static String wake(String when) {
		if (when == null || when.isBlank() || when.equals("jamais")) {
			return "ne se réveille pas";
		}
		if (when.equals("debut")) {
			return "au début de la nuit";
		}
		if (when.equals("fin")) {
			return "à la fin de la nuit";
		}
		int colon = when.indexOf(':');
		if (colon > 0) {
			String other = when.substring(colon + 1);
			fr.valcombe.rules.Role r = fr.valcombe.rules.Role.parse(other);
			String name = r == null ? other : r.displayName;
			return (when.startsWith("avant") ? "juste avant " : "juste après ") + name;
		}
		return when;
	}

	/** Les scénarios créés : jetons de leurs rôles, carte, nombres de joueurs, parties où ils sont choisis. */
	private List<Map<String, Object>> scenarioList() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (fr.valcombe.session.ScenarioDef d : creations.scenarios()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", d.id);
			m.put("nom", d.nom);
			m.put("description", d.description == null ? "" : d.description);
			List<Map<String, Object>> tokens = new ArrayList<>();
			for (String r : d.roles == null ? List.<String>of() : d.roles) {
				tokens.add(token(r));
			}
			m.put("roles", tokens);
			String carte = d.carte == null ? "" : d.carte;
			m.put("carte", carte);
			m.put("carteNom", carte.isEmpty() ? "" : maps.list().stream().filter(x -> x.id.equals(carte)).map(x -> x.nom).findFirst().orElse(""));
			m.put("carteManque", !carte.isEmpty() && !maps.exists(carte));
			List<Integer> n = d.playable();
			m.put("joueurs", n.isEmpty() ? "" : n.get(0) + " à " + n.get(n.size() - 1));
			m.put("manquants", d.missingRoles());
			List<String> active = new ArrayList<>();
			for (fr.valcombe.mc.games.GameRoom r : fr.valcombe.mc.games.Games.all()) {
				if (r.session().scenario() != null && r.session().scenario().id.equals(d.id)) {
					active.add(r.name());
				}
			}
			m.put("choisiDans", active);
			out.add(m);
		}
		return out;
	}

	/** Les dossiers où l'Atelier en jeu cherche des packs .valcombe : le dossier d'import, puis celui des partages. */
	private List<java.nio.file.Path> packDirs() {
		return List.of(java.nio.file.Path.of(inbox()), creations.dir().resolve("partages"));
	}

	/** Les packs .valcombe déposés sur le serveur (dossier d'import, puis partages), les plus récents d'abord. */
	List<Map<String, Object>> packs() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (java.nio.file.Path dir : packDirs()) {
			if (!java.nio.file.Files.isDirectory(dir)) {
				continue;
			}
			try (var s = java.nio.file.Files.list(dir)) {
				for (java.nio.file.Path f : s.filter(java.nio.file.Files::isRegularFile)
						.filter(x -> x.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".valcombe")).toList()) {
					Map<String, Object> m = new LinkedHashMap<>();
					long at = java.nio.file.Files.getLastModifiedTime(f).toMillis();
					m.put("fichier", f.getFileName().toString());
					m.put("dossier", dir.equals(packDirs().get(0)) ? "import" : "partages");
					m.put("octets", java.nio.file.Files.size(f));
					m.put("date", at);
					m.put("dateTexte", WHEN.format(java.time.Instant.ofEpochMilli(at)));
					out.add(m);
				}
			} catch (IOException e) {
				ValcombeMod.LOG.warn("Dossier des packs illisible {}", dir, e);
			}
		}
		out.sort((a, b) -> Long.compare((Long) b.get("date"), (Long) a.get("date")));
		return out.size() > 60 ? out.subList(0, 60) : out;
	}

	/** Importe un pack .valcombe déposé sur le serveur (dossier d'import, sinon partages) : rôles, scénarios, cartes, structures. */
	public String importPack(String fileName) {
		String name = fileName == null ? "" : fileName.strip();
		if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")
			|| !name.toLowerCase(java.util.Locale.ROOT).endsWith(".valcombe")) {
			throw new GameException("Fichier de pack : le nom d'un fichier .valcombe déposé dans " + inbox() + ".");
		}
		for (java.nio.file.Path dir : packDirs()) {
			java.nio.file.Path f = dir.resolve(name);
			if (java.nio.file.Files.isRegularFile(f)) {
				try {
					if (java.nio.file.Files.size(f) > 64L * 1024 * 1024) {
						throw new GameException("Pack trop gros (64 Mo au plus).");
					}
					return "Pack « " + name + " » : " + creations.importPack(java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
				} catch (IOException e) {
					throw new GameException("Pack illisible : " + e.getMessage());
				}
			}
		}
		throw new GameException("Pack introuvable : déposez « " + name + " » dans " + inbox() + ", puis réessayez.");
	}

	private String inboxPath;

	/** Le dossier d'import du serveur (créé au premier appel). */
	private String inbox() {
		if (inboxPath == null) {
			inboxPath = structures.inboxPath();
		}
		return inboxPath;
	}

	/** Les poses de cartes en cours ou en attente (monde, libellé, avancement de 0 à 1), pour la barre de l'Atelier en jeu. */
	private static List<Map<String, Object>> works() {
		MapPlacer placer = ValcombeMod.placer();
		if (placer == null) {
			return List.of();
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (BuildQueue.Progress pr : placer.progress()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("texte", pr.label());
			m.put("monde", pr.level().identifier().getPath());
			m.put("avance", Math.round(pr.done() * 1000) / 1000.0);
			out.add(m);
		}
		return out;
	}

	/**
	 * « Exporter » : la carte dans un fichier .valcombe du dossier des partages du serveur ({@code creations/partages}),
	 * celui qu'importe l'Atelier web d'un autre serveur.
	 */
	public String exportMap(String id) {
		MapStore.MapDef d = maps.list().stream().filter(x -> x.id.equals(id)).findFirst()
			.orElseThrow(() -> new GameException("Carte introuvable : " + id + "."));
		String json = creations.exportPack(List.of(), List.of(), List.of(id));
		java.nio.file.Path dir = creations.dir().resolve("partages");
		String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(java.time.ZoneId.systemDefault())
			.format(java.time.Instant.now());
		java.nio.file.Path file = dir.resolve("carte-" + d.id + "-" + stamp + ".valcombe");
		try {
			java.nio.file.Files.createDirectories(dir);
			java.nio.file.Files.writeString(file, json, java.nio.charset.StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new GameException("Export impossible : " + e.getMessage());
		}
		return "Carte « " + d.nom + " » exportée : " + file.toAbsolutePath() + " (" + Math.max(1, file.toFile().length() / 1024)
			+ " Ko). Ce fichier s'importe sur un autre serveur par la page web de l'Atelier (Partage).";
	}

	// ------------------------------------------------------------------
	// Réponse du serveur à la dernière action de l'écran en jeu
	// ------------------------------------------------------------------
	/** Ce que le serveur a répondu à la dernière commande de l'Atelier lancée depuis l'écran : il la montre sous ses boutons. */
	private record Reply(int n, String text, boolean error) {
	}

	private final Map<String, Reply> replies = new java.util.HashMap<>();
	private int replyCount;

	/** La commande de l'écran vient de répondre (le chat, lui, est caché par l'écran). */
	void reply(String player, String text, boolean error) {
		if (!text.isBlank()) {
			replies.put(player, new Reply(++replyCount, text.length() > 300 ? text.substring(0, 300) + "…" : text, error));
		}
	}

	void forget(String player) {
		replies.remove(player);
	}

	/**
	 * Ce qui, s'il change, demande de refaire tout de suite la vue de l'Atelier de ce joueur : une réponse à une
	 * commande, un chantier qui commence ou finit, ce qu'il a en main dans l'éditeur. Le reste se rafraîchit chaque seconde.
	 */
	int changeKey(ServerPlayer p) {
		fr.valcombe.mc.games.GameRoom room = roomOf(p);
		return java.util.Objects.hash(replyCount, room == null ? null : room.id(), room != null && room.host().busy(),
			editor.state(p.getScoreboardName()));
	}

	/** Les commandes dont l'écran de l'Atelier affiche la réponse. */
	static boolean shows(String command) {
		return command.startsWith("vc carte ") || command.startsWith("vc structure ") || command.startsWith("vc structures ")
			|| command.equals("vc annuler") || command.startsWith("vc annuler ") || command.equals("vc retablir")
			|| command.startsWith("vc retablir ") || command.equals("vc baguette")
			|| command.startsWith("vc atelier ") || command.startsWith("vc scenario ") || command.startsWith("vc partie ");
	}

	// ------------------------------------------------------------------
	// Nombres reçus de la page web
	// ------------------------------------------------------------------
	/** Un nombre entier attendu dans une requête : absent, vide ou hors bornes, il donne un message en clair. */
	public static int number(JsonObject o, String key, String label, int min, int max) {
		JsonElement e = o == null ? null : o.get(key);
		int n;
		try {
			if (e == null || !e.isJsonPrimitive()) {
				throw new NumberFormatException();
			}
			n = e.getAsInt();
		} catch (RuntimeException ex) {
			throw new GameException(label + " : indiquez un nombre de " + min + " à " + max + ".");
		}
		if (n < min || n > max) {
			throw new GameException(label + " : de " + min + " à " + max + ".");
		}
		return n;
	}
}
