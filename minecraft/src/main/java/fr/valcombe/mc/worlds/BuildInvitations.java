package fr.valcombe.mc.worlds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import fr.valcombe.mc.SafeFile;
import fr.valcombe.rules.GameException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Les invitations à construire dans les ateliers, et le réglage « WorldEdit dans les parties d'essai ».
 * <p>
 * Un opérateur invite un joueur dans un atelier (carte ou structures), ou dans tous ({@link #ALL}) : l'invité voit cet
 * atelier depuis le hub, y va, y construit en mode créatif et avec WorldEdit, comme un opérateur. Ce qui décide qui
 * construit où est {@link BuildAccess} ; cette classe ne fait que tenir la liste.
 * <p>
 * Fichier : {@code config/valcombe/ateliers.json}, écrit sans jamais rester à moitié (copie de secours à côté).
 * <pre>
 * {
 *   "worldEditEssais": false,
 *   "invitations": [
 *     {"joueur": "Alice", "uuid": "…", "monde": "atelier-carte-1", "par": "Yoann", "le": "2026-11-13T10:00:00Z"},
 *     {"joueur": "Bob", "uuid": "…", "monde": "*", "par": "Yoann", "le": "…"}
 *   ]
 * }
 * </pre>
 * Un invité est reconnu par son identifiant de compte (uuid) quand on le connaît, sinon par son nom (sans souci des
 * majuscules) : on peut inviter un joueur qui n'est pas connecté, son identifiant est noté à sa première venue.
 * <p>
 * Les méthodes sont appelées sur le fil du serveur (commandes, voyages, API d'administration par onServer).
 */
public final class BuildInvitations {
	/** « Tous les ateliers », dans le champ {@code monde} d'une invitation. */
	public static final String ALL = "*";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/** Un nom de joueur Minecraft : 3 à 16 lettres, chiffres ou soulignés (on tolère 1 à 16 pour les comptes hors ligne). */
	private static final String NAME = "[A-Za-z0-9_]{1,16}";

	/**
	 * Une invitation.
	 *
	 * @param joueur nom du joueur, tel qu'écrit à l'invitation ou vu à sa dernière venue
	 * @param uuid   identifiant de son compte, ou null s'il n'est pas encore venu
	 * @param monde  identifiant de l'atelier, ou {@link #ALL}
	 * @param par    l'opérateur qui l'a invité
	 * @param le     date de l'invitation
	 */
	public record Invite(String joueur, String uuid, String monde, String par, Instant le) {
		/** Cette invitation désigne-t-elle ce joueur ? Par son identifiant s'il est connu des deux côtés, sinon par son nom. */
		public boolean is(UUID id, String name) {
			if (uuid != null && id != null) {
				return uuid.equals(id.toString());
			}
			return name != null && joueur.equalsIgnoreCase(name);
		}

		/** Vaut-elle pour cet atelier ? */
		public boolean covers(String world) {
			return ALL.equals(monde) || monde.equals(world);
		}
	}

	/** Forme du fichier. */
	private static final class Data {
		boolean worldEditEssais;
		List<Stored> invitations = new ArrayList<>();
	}

	private static final class Stored {
		String joueur;
		String uuid;
		String monde;
		String par;
		String le;
	}

	private final Path file;
	private final List<Invite> invites = new ArrayList<>();
	private boolean worldEditInTrials;

	/** Liste enregistrée dans ce fichier ; rien n'est lu avant {@link #load()}. */
	public BuildInvitations(Path file) {
		this.file = file;
	}

	/** Le fichier de ce serveur : {@code config/valcombe/ateliers.json}. */
	public static Path fileIn(Path serverDir) {
		return serverDir.resolve("config").resolve("valcombe").resolve("ateliers.json");
	}

	public Path file() {
		return file;
	}

	// ------------------------------------------------------------------
	// Lecture, écriture
	// ------------------------------------------------------------------

	/** Relit le fichier (ou sa copie de secours) ; un fichier absent ou illisible donne une liste vide. */
	public synchronized void load() {
		invites.clear();
		worldEditInTrials = false;
		String text = SafeFile.read(file, t -> {
			try {
				return JsonParser.parseString(t).isJsonObject();
			} catch (RuntimeException e) {
				return false;
			}
		});
		if (text == null) {
			return;
		}
		Data d = GSON.fromJson(text, Data.class);
		if (d == null) {
			return;
		}
		worldEditInTrials = d.worldEditEssais;
		if (d.invitations != null) {
			for (Stored s : d.invitations) {
				if (s == null || s.joueur == null || !s.joueur.matches(NAME) || s.monde == null || s.monde.isBlank()) {
					continue; // ligne abîmée ou écrite à la main de travers : ignorée
				}
				UUID id = null;
				try {
					id = s.uuid == null || s.uuid.isBlank() ? null : UUID.fromString(s.uuid.strip());
				} catch (IllegalArgumentException e) {
					// identifiant illisible : le joueur sera reconnu par son nom, puis son identifiant noté à sa venue
				}
				String uuid = id == null ? null : id.toString();
				Instant when;
				try {
					when = s.le == null ? Instant.EPOCH : Instant.parse(s.le);
				} catch (RuntimeException e) {
					when = Instant.EPOCH;
				}
				Invite i = new Invite(s.joueur, uuid, s.monde, s.par == null ? "" : s.par, when);
				if (find(id, i.joueur(), i.monde()) == null) {
					invites.add(i);
				}
			}
		}
	}

	private void save() {
		Data d = new Data();
		d.worldEditEssais = worldEditInTrials;
		for (Invite i : invites) {
			Stored s = new Stored();
			s.joueur = i.joueur();
			s.uuid = i.uuid();
			s.monde = i.monde();
			s.par = i.par();
			s.le = i.le().toString();
			d.invitations.add(s);
		}
		try {
			SafeFile.write(file, GSON.toJson(d));
		} catch (IOException e) {
			throw new GameException("Invitations non enregistrées : " + e.getMessage());
		}
	}

	// ------------------------------------------------------------------
	// Inviter, retirer
	// ------------------------------------------------------------------

	private Invite find(UUID id, String name, String world) {
		for (Invite i : invites) {
			if (i.is(id, name) && i.monde().equals(world)) {
				return i;
			}
		}
		return null;
	}

	/**
	 * Invite ce joueur à construire dans cet atelier (ou dans tous : {@link #ALL}). Une invitation déjà là est gardée
	 * (son identifiant de compte complété s'il manquait).
	 *
	 * @param id    identifiant de compte du joueur s'il est connu (connecté, ou déjà venu), sinon null
	 * @param world identifiant de l'atelier, déjà vérifié par l'appelant, ou {@link #ALL}
	 * @param by    l'opérateur qui invite
	 * @return vrai si l'invitation est nouvelle
	 */
	public synchronized boolean invite(String name, UUID id, String world, String by, Instant now) {
		checkName(name);
		Invite old = find(id, name, world);
		if (old != null) {
			if (old.uuid() == null && id != null) {
				invites.set(invites.indexOf(old), new Invite(name, id.toString(), old.monde(), old.par(), old.le()));
				save();
			}
			return false;
		}
		invites.add(new Invite(name, id == null ? null : id.toString(), world, by == null ? "" : by, now));
		save();
		return true;
	}

	/**
	 * Retire l'invitation de ce joueur pour cet atelier ({@link #ALL} : l'invitation à tous les ateliers), ou toutes
	 * ses invitations (world null).
	 *
	 * @return le nombre d'invitations retirées
	 */
	public synchronized int remove(String name, UUID id, String world) {
		int before = invites.size();
		invites.removeIf(i -> i.is(id, name) && (world == null || i.monde().equals(world)));
		int removed = before - invites.size();
		if (removed > 0) {
			save();
		}
		return removed;
	}

	/** Un atelier supprimé : ses invitations partent avec lui. */
	public synchronized void forgetWorld(String world) {
		if (invites.removeIf(i -> i.monde().equals(world))) {
			save();
		}
	}

	/**
	 * Un joueur arrive : son identifiant de compte est noté sur les invitations faites à son nom, et son nom actuel
	 * sur celles faites à son identifiant (il a pu en changer). Vrai si quelque chose a changé.
	 */
	public synchronized boolean noteJoin(UUID id, String name) {
		boolean changed = false;
		for (int k = 0; k < invites.size(); k++) {
			Invite i = invites.get(k);
			boolean byName = i.uuid() == null && i.joueur().equalsIgnoreCase(name);
			boolean renamed = i.uuid() != null && i.uuid().equals(id.toString()) && !i.joueur().equals(name);
			if (byName || renamed) {
				invites.set(k, new Invite(name, id.toString(), i.monde(), i.par(), i.le()));
				changed = true;
			}
		}
		if (changed) {
			save();
		}
		return changed;
	}

	// ------------------------------------------------------------------
	// Lectures
	// ------------------------------------------------------------------

	/** Ce joueur est-il invité dans cet atelier (par une invitation à cet atelier ou à tous) ? */
	public synchronized boolean invited(UUID id, String name, String world) {
		for (Invite i : invites) {
			if (i.is(id, name) && i.covers(world)) {
				return true;
			}
		}
		return false;
	}

	/** Ce joueur a-t-il au moins une invitation ? (Les commandes de construction lui sont alors proposées.) */
	public synchronized boolean invitedSomewhere(UUID id, String name) {
		for (Invite i : invites) {
			if (i.is(id, name)) {
				return true;
			}
		}
		return false;
	}

	/** Les invités de cet atelier (ceux de tous les ateliers compris), dans l'ordre des invitations, sans doublon. */
	public synchronized List<String> guests(String world) {
		Set<String> names = new LinkedHashSet<>();
		for (Invite i : invites) {
			if (i.covers(world)) {
				names.add(i.joueur());
			}
		}
		return List.copyOf(names);
	}

	/** Toutes les invitations, dans l'ordre où elles ont été faites. */
	public synchronized List<Invite> list() {
		return List.copyOf(invites);
	}

	/** WorldEdit est-il permis aux opérateurs dans les parties d'essai ? (Non par défaut.) */
	public synchronized boolean worldEditInTrials() {
		return worldEditInTrials;
	}

	public synchronized void setWorldEditInTrials(boolean on) {
		if (worldEditInTrials != on) {
			worldEditInTrials = on;
			save();
		}
	}

	/** Refuse ce qui ne peut pas être un nom de joueur Minecraft. */
	public static void checkName(String name) {
		if (name == null || !name.matches(NAME)) {
			throw new GameException("« " + name + " » n'est pas un nom de joueur Minecraft (lettres, chiffres et _, 16 au plus).");
		}
	}

	/** « Alice » ou « Alice, Bob et Chloé ». */
	public static String names(List<String> names) {
		if (names.isEmpty()) {
			return "personne";
		}
		if (names.size() == 1) {
			return names.get(0);
		}
		return String.join(", ", names.subList(0, names.size() - 1)) + " et " + names.get(names.size() - 1);
	}
}
