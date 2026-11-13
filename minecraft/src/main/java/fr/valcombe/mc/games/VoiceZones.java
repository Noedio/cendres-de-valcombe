package fr.valcombe.mc.games;

import java.util.Map;
import java.util.UUID;

/**
 * Qui peut entendre qui, au vocal (Simple Voice Chat) : chaque joueur est dans une <b>zone</b>, la partie du monde où il
 * se trouve (l'identifiant de ce monde), ou {@link #OUTSIDE} hors de toute partie (hub, ateliers, monde principal). Une
 * voix ne passe jamais d'une zone à une autre : on n'entend jamais une autre partie, ni le hub depuis une partie, ni
 * l'inverse, même dans un groupe du vocal formé entre joueurs de mondes différents, même en spectateur.
 * <p>
 * Le vocal de proximité, lui, ne franchit déjà pas les mondes ; ces zones arrêtent ce qui les franchit : les groupes, et
 * tout paquet de voix envoyé d'un joueur à un autre. Les zones sont recalculées par {@code VoiceRouter} sur le fil du
 * serveur et lues sur le fil du vocal : l'objet est immuable.
 */
public final class VoiceZones {
	/** La zone de qui n'est dans aucune partie (hub, atelier, monde principal de Minecraft). */
	public static final String OUTSIDE = "";

	/**
	 * La zone des gradins d'une partie : ceux qui la regardent depuis le tableau des parties du hub. Ils s'entendent
	 * entre eux, mais ni les joueurs ni le Conteur ne les entendent, et ils n'entendent pas la partie.
	 */
	public static String gallery(String gameId) {
		return gameId + "#gradins";
	}

	/** Aucun joueur connu : tout le monde est hors des parties. */
	public static final VoiceZones NONE = new VoiceZones(Map.of());

	private final Map<UUID, String> zones;

	/** zones : la zone de chaque joueur connu (identifiant du monde de sa partie, ou {@link #OUTSIDE}). */
	public VoiceZones(Map<UUID, String> zones) {
		this.zones = Map.copyOf(zones);
	}

	/** La zone de ce joueur ; un joueur inconnu (tout juste arrivé) est hors des parties. */
	public String zone(UUID player) {
		String z = player == null ? null : zones.get(player);
		return z == null ? OUTSIDE : z;
	}

	/**
	 * Ce qui parle peut-il être entendu de ce qui écoute ? Oui s'ils sont dans la même zone. Une voix sans joueur (un son
	 * d'un autre greffon du vocal) n'est pas arrêtée.
	 */
	public boolean mayHear(UUID speaker, UUID listener) {
		if (speaker == null || listener == null) {
			return true;
		}
		return zone(speaker).equals(zone(listener));
	}

	/** Le nombre de joueurs connus. */
	public int size() {
		return zones.size();
	}
}
