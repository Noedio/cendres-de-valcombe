package fr.valcombe.mc;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fr.valcombe.rules.Distribution;
import fr.valcombe.rules.GameException;
import fr.valcombe.rules.RoleDef;
import fr.valcombe.session.Session;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
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
 */
public final class Workshop {
	private static final Gson GSON = new Gson();
	/** Côté de l'aperçu envoyé à l'écran en jeu, en cases (chacune y est dessinée en deux pixels). */
	static final int PREVIEW_SIDE = 48;

	private final Session session;
	private final CreationStore creations;
	private final MapStore maps;
	private final McHost host;
	private final StructureStore structures;
	private final MapEditor editor;
	private final MinecraftServer server;

	Workshop(MinecraftServer server, Session session, CreationStore creations, MapStore maps, McHost host,
			StructureStore structures, MapEditor editor) {
		this.server = server;
		this.session = session;
		this.creations = creations;
		this.maps = maps;
		this.host = host;
		this.structures = structures;
		this.editor = editor;
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
	/** Les cartes de la bibliothèque ; plan : avec leurs repères et leur liste de contrôle (plan de la page web). */
	public List<Map<String, Object>> library(boolean plan) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (MapStore.MapDef d : maps.list()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", d.id);
			m.put("nom", d.nom);
			m.put("genere", d.genere);
			m.put("theme", d.theme);
			m.put("sieges", d.sieges.size());
			m.put("maisons", d.logis.size());
			if (plan) {
				m.put("octets", maps.bytes(d.id));
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

	/** La carte actuelle sur le serveur, ou null ; plan : avec ses repères et les joueurs connectés. */
	public Map<String, Object> current(boolean plan) {
		Village v = host.village();
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
			// Les joueurs connectés, pour se repérer : [nom, est, sud] depuis le centre de la place.
			List<List<Object>> here = new ArrayList<>();
			for (ServerPlayer p2 : server.getPlayerList().getPlayers()) {
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

	/** La même chose en bref, pour la ligne du haut de l'écran en jeu. */
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
		Map<String, Object> cur = current(false);
		out.put("carte", cur);
		out.put("resume", describe(cur));
		out.put("bref", brief(cur));
		out.put("cartes", library(false));
		out.put("chantier", host.busy());
		out.put("essai", session.trialPending());
		out.put("editeur", editor.state(p.getScoreboardName()));
		List<Map<String, Object>> list = new ArrayList<>();
		for (StructureStore.Def d : structures.list()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", d.id);
			m.put("nom", d.nom);
			m.put("categorie", d.categorie);
			m.put("taille", d.taille);
			m.put("repere", d.repere);
			m.put("fournie", d.fournie);
			list.add(m);
		}
		out.put("structures", list);
		List<Map<String, Object>> roles = new ArrayList<>();
		for (RoleDef d : creations.roles()) {
			roles.add(Map.of("id", d.id, "nom", d.nom, "camp", d.camp));
		}
		out.put("roles", roles);
		out.put("themes", Village.THEMES);
		out.put("tronesMin", Village.MIN_SEATS);
		out.put("tronesMax", Village.MAX_SEATS);
		Reply reply = replies.get(p.getScoreboardName());
		if (reply != null) {
			out.put("reponse", Map.of("n", reply.n(), "texte", reply.text(), "erreur", reply.error()));
		}
		return out;
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
		return java.util.Objects.hash(replyCount, host.busy(), editor.state(p.getScoreboardName()));
	}

	/** Les commandes dont l'écran de l'Atelier affiche la réponse. */
	static boolean shows(String command) {
		return command.startsWith("vc carte ") || command.startsWith("vc structure ") || command.startsWith("vc structures ")
			|| command.equals("vc annuler") || command.startsWith("vc annuler ") || command.equals("vc retablir")
			|| command.startsWith("vc retablir ") || command.equals("vc baguette")
			|| command.startsWith("vc atelier ") || command.startsWith("vc scenario ");
	}

	// ------------------------------------------------------------------
	// Aperçu vu de dessus, pour l'écran en jeu
	// ------------------------------------------------------------------
	/**
	 * L'aperçu d'une carte, réduit à quelques dizaines de cases : couleurs vues de dessus (trois octets par
	 * case, en base 64) et repères. id vide ou « actuelle » : la carte actuelle.
	 */
	public String preview(String id) {
		boolean currentMap = id == null || id.isBlank() || id.equals("actuelle");
		byte[] png;
		int radius;
		List<int[]> seats;
		List<int[]> houses;
		int[] room;
		String name;
		if (currentMap) {
			Village v = host.village();
			if (v == null) {
				throw new GameException("Aucune carte sur le serveur.");
			}
			png = host.topView(v);
			radius = Math.min(Village.MAX_EXTENT, v.extent());
			seats = v.relSeats();
			houses = v.relHouses();
			room = v.relConvocation();
			name = v.libraryName();
		} else {
			MapStore.MapDef d = maps.list().stream().filter(x -> x.id.equals(id)).findFirst()
				.orElseThrow(() -> new GameException("Carte introuvable : " + id + "."));
			png = maps.preview(id);
			radius = d.rayon;
			seats = d.sieges;
			houses = d.logis;
			room = d.convocation;
			name = d.nom;
		}
		JsonObject o = new JsonObject();
		o.addProperty("id", currentMap ? "" : id);
		o.addProperty("nom", name);
		o.addProperty("rayon", radius);
		o.add("sieges", flat(seats));
		o.add("maisons", flat(houses));
		o.add("salle", room == null ? null : GSON.toJsonTree(new int[] {room[0], room[2]}));
		if (png != null) {
			try {
				var img = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
				if (img != null) {
					o.addProperty("cote", PREVIEW_SIDE);
					o.addProperty("pixels", Base64.getEncoder().encodeToString(shrink(img.getWidth(), img.getHeight(),
						img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth()), PREVIEW_SIDE)));
				}
			} catch (IOException | RuntimeException e) {
				ValcombeMod.LOG.warn("Aperçu de la carte {} illisible", id, e);
			}
		}
		return o.toString();
	}

	/** [est, sud] de chaque repère. */
	private static JsonElement flat(List<int[]> marks) {
		List<int[]> out = new ArrayList<>();
		for (int[] a : marks) {
			out.add(new int[] {a[0], a[2]});
		}
		return GSON.toJsonTree(out);
	}

	/** Réduit une image (une couleur par pixel) à side × side cases : la moyenne de chaque case, en trois octets. */
	static byte[] shrink(int width, int height, int[] rgb, int side) {
		byte[] out = new byte[side * side * 3];
		for (int cy = 0; cy < side; cy++) {
			int y0 = cy * height / side;
			int y1 = Math.max(y0 + 1, (cy + 1) * height / side);
			for (int cx = 0; cx < side; cx++) {
				int x0 = cx * width / side;
				int x1 = Math.max(x0 + 1, (cx + 1) * width / side);
				long r = 0;
				long g = 0;
				long b = 0;
				int n = 0;
				for (int y = y0; y < y1 && y < height; y++) {
					for (int x = x0; x < x1 && x < width; x++) {
						int c = rgb[y * width + x];
						r += c >> 16 & 255;
						g += c >> 8 & 255;
						b += c & 255;
						n++;
					}
				}
				int i = (cy * side + cx) * 3;
				if (n > 0) {
					out[i] = (byte) (r / n);
					out[i + 1] = (byte) (g / n);
					out[i + 2] = (byte) (b / n);
				}
			}
		}
		return out;
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
