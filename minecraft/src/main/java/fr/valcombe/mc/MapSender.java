package fr.valcombe.mc;

import fr.valcombe.mc.worlds.WorldEntry;
import fr.valcombe.mc.worlds.WorldKind;
import fr.valcombe.mc.worlds.WorldLookup;
import fr.valcombe.mc.worlds.WorldManager;
import fr.valcombe.rules.GameException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * « Envoyer à la partie » : la seule façon de changer la carte d'un monde de partie. Une carte de la bibliothèque,
 * un village généré, une construction importée, une carte vide à compléter, ou rien du tout (on joue autour du
 * Conteur) : tout passe par la même opération sûre.
 * <ol>
 * <li>Refus en clair si une partie se joue dans ce monde, si un chantier ou une remise en état y est en cours, ou si
 * une autre carte y est déjà en route. Ce qu'on envoie est relu et contrôlé avant que rien ne bouge.</li>
 * <li>La carte actuelle, si elle a été retouchée à la main sans être enregistrée, est d'abord rangée dans la
 * bibliothèque (« Sauvegarde du … ») ; si c'est impossible, on ne touche à rien.</li>
 * <li>Les joueurs présents sont renvoyés au hub, et le monde est remis à neuf ({@link WorldManager#recreate}) : ses
 * fichiers sont effacés, il revient plat et vide. Il ne peut donc rester aucun bloc ni aucun décor d'une ancienne
 * carte, quelle que soit sa taille ou ce qu'on a bâti autour : c'est la fin du rasage « bâti » ou « carré ».</li>
 * <li>La carte est posée au centre du sol plat ({@link MapPlacer}), par petits lots : une barre suit l'avancement
 * pour les joueurs sortis du monde et pour celui qui l'envoie, où qu'ils soient.</li>
 * <li>Ses repères (trônes, maisons, centre, salle de convocation) deviennent le village de la partie de ce monde
 * ({@link PlacedMap#toVillage()}, puis {@link McHost#adopt}). Chacun reçoit un message, avec un lien pour y retourner.</li>
 * </ol>
 * Chaque monde de partie porte ainsi sa propre carte ; la bibliothèque n'a plus de « carte posée » commune. Le monde
 * visé est une donnée : la partie du serveur aujourd'hui, et demain n'importe quel monde de partie, chacun avec
 * son hôte ({@code hosts}). Un monde de partie sans hôte reçoit tout de même sa carte et la fiche de son village,
 * rangée dans son dossier : la partie qui s'y liera la trouvera.
 * <p>
 * Un envoi coupé par l'arrêt du serveur laisse sa marque dans le dossier du monde : au démarrage, la partie le dit
 * aux opérateurs et ne bâtit rien par-dessus.
 * <p>
 * Tout se fait sur le fil du serveur.
 */
public final class MapSender {
	/**
	 * Fichier d'un monde de partie vidé exprès (« Vider le monde ») : au démarrage, la partie n'y bâtit pas le village
	 * de Valcombe, on y joue sans carte. Il part avec le monde à la prochaine remise à neuf.
	 */
	static final String EMPTY = "valcombe-sans-carte.txt";
	/** Marque d'un envoi en cours, dans le dossier du monde : la même que celle des chantiers de la partie (McHost). */
	static final String MARK = "valcombe-chantier.txt";

	/** Ce qu'on envoie à un monde de partie. */
	public sealed interface Source permits Library, Generated, Imported, Blank, Nothing {
		/** Ce qu'on envoie, pour les messages et la barre : « la carte « Port » », « un village généré »... */
		String label();
	}

	/** Une carte de la bibliothèque, fiche et blocs déjà relus et contrôlés. */
	public record Library(MapStore.MapDef def, Clip clip) implements Source {
		@Override
		public String label() {
			return "la carte « " + def.nom + " »";
		}
	}

	/** Un village de Valcombe généré sur le sol plat neuf. */
	public record Generated(String theme, int seats, int size, boolean houses) implements Source {
		@Override
		public String label() {
			return "un village généré (" + seats + " trônes, thème " + theme + ")";
		}
	}

	/** Une construction importée (.schem, .litematic, .nbt), carte à compléter : trônes, maisons, centre. */
	public record Imported(String fileName, Clip clip) implements Source {
		@Override
		public String label() {
			return "la construction « " + fileName + " »";
		}
	}

	/** Une carte vide, sans repères, au centre du sol plat : on la bâtit et on y pose les repères à la main. */
	public record Blank() implements Source {
		@Override
		public String label() {
			return "une carte vide";
		}
	}

	/** Rien : le monde reste plat et vide, et l'on joue autour du Conteur. */
	public record Nothing() implements Source {
		@Override
		public String label() {
			return "un monde vide (sans carte)";
		}
	}

	/** Un envoi en cours vers un monde : ce qu'on envoie, et qui suit la barre (joueurs sortis du monde, demandeur). */
	private record Sending(Source source, WorldEntry entry, McHost host, Set<UUID> watchers) {
		String label() {
			return source.label();
		}
	}

	private final MinecraftServer server;
	private final WorldManager worlds;
	private final MapPlacer placer;
	private final MapStore maps;
	/** L'hôte de la partie qui se joue dans ce monde, ou null s'il n'y en a pas (pas encore). */
	private final Function<String, McHost> hosts;
	/** Le monde de partie visé quand on n'en nomme aucun : celui de la partie du serveur. */
	private final Supplier<String> defaultWorld;
	/** Les envois en cours, par identifiant de monde. */
	private final Map<String, Sending> sending = new LinkedHashMap<>();

	public MapSender(MinecraftServer server, WorldManager worlds, MapPlacer placer, MapStore maps,
			Function<String, McHost> hosts, Supplier<String> defaultWorld) {
		this.server = server;
		this.worlds = worlds;
		this.placer = placer;
		this.maps = maps;
		this.hosts = hosts;
		this.defaultWorld = defaultWorld;
	}

	private void requireServerThread() {
		if (!server.isSameThread()) {
			throw new IllegalStateException("Les cartes s'envoient sur le fil du serveur.");
		}
	}

	/** Une carte est-elle en route vers ce monde ? */
	public boolean busy(String worldId) {
		return sending.containsKey(worldId);
	}

	/** Ce qui est en route vers ce monde (« la carte « Port » »), ou null. */
	public String sendingTo(String worldId) {
		Sending s = sending.get(worldId);
		return s == null ? null : s.label();
	}

	// ------------------------------------------------------------------
	// Ce qu'on envoie
	// ------------------------------------------------------------------

	/** Envoie la carte mapId de la bibliothèque au monde de partie world (null : celui de la partie du serveur). */
	public String sendMap(String mapId, String world, ServerPlayer by) {
		requireServerThread();
		WorldEntry e = target(world);
		MapStore.MapDef d = maps.checkedDef(mapId);
		// Les blocs sont relus avant que rien ne bouge : une carte abîmée ne laisse pas un monde vidé pour rien.
		return send(new Library(d, maps.readClip(d)), e, by);
	}

	/** Envoie un village de Valcombe généré : trônes (5 à 20), taille (0 petite, 1 normale, 2 grande), thème, maisons. */
	public String sendVillage(int seats, int size, String theme, boolean houses, String world, ServerPlayer by) {
		requireServerThread();
		WorldEntry e = target(world);
		return send(village(seats, size, theme, houses), e, by);
	}

	/**
	 * Le village généré demandé, contrôlé avant que rien ne bouge : trônes de Village.MIN_SEATS à Village.MAX_SEATS,
	 * taille 0 à 2, thème connu (vide : gothique ; majuscules et espaces autour ignorés).
	 */
	static Generated village(int seats, int size, String theme, boolean houses) {
		if (seats < Village.MIN_SEATS || seats > Village.MAX_SEATS) {
			throw new GameException("Trônes : de " + Village.MIN_SEATS + " à " + Village.MAX_SEATS + ".");
		}
		if (size < 0 || size > 2) {
			throw new GameException("Taille : petite, normale ou grande.");
		}
		String t = theme == null || theme.isBlank() ? "gothique" : theme.strip().toLowerCase(java.util.Locale.ROOT);
		if (!Village.THEMES.contains(t)) {
			throw new GameException("Thème inconnu : " + theme + " (" + String.join(", ", Village.THEMES) + ").");
		}
		return new Generated(t, seats, size, houses);
	}

	/** Une carte s'envoie aux mondes de partie (vraies parties et parties d'essai), jamais au hub ni à un atelier. */
	static boolean accepts(WorldKind kind) {
		return kind == WorldKind.PARTIE || kind == WorldKind.ESSAI;
	}

	/** Envoie une construction importée (contenu d'un fichier .schem, .litematic ou .nbt), à compléter de repères. */
	public String sendImport(String fileName, byte[] data, String world, ServerPlayer by) {
		requireServerThread();
		WorldEntry e = target(world);
		Clip clip = SchematicImporter.read(fileName, data, server.registryAccess().lookupOrThrow(
			net.minecraft.core.registries.Registries.BLOCK));
		return send(new Imported(fileName, clip), e, by);
	}

	/**
	 * Envoie une construction désignée par son nom : un fichier du dossier d'import (.schem, .litematic, .nbt), ou une
	 * structure du jeu (enregistrée avec un bloc de structure). Même chemin que l'import de l'Atelier web.
	 */
	public String sendStructure(String name, String world, ServerPlayer by) {
		requireServerThread();
		if (ValcombeMod.structures().importable().contains(name)) {
			return sendImport(name, ValcombeMod.structures().inboxFile(name), world, by);
		}
		var id = net.minecraft.resources.Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
		if (id == null) {
			throw new GameException("Nom de fichier ou de structure invalide : " + name);
		}
		var template = server.getStructureTemplateManager().get(id)
			.orElseThrow(() -> new GameException("Construction introuvable : " + name
				+ ". Déposez un fichier .schem, .litematic ou .nbt dans " + ValcombeMod.structures().inboxPath()
				+ ", ou enregistrez-la dans le monde avec un bloc de structure."));
		byte[] data;
		try {
			// La structure du jeu suit le même chemin qu'un fichier : relue case par case, sans entités ni données à risque.
			data = MapStore.compress(template.save(new net.minecraft.nbt.CompoundTag()));
		} catch (IOException ex) {
			throw new GameException("Structure illisible : " + ex.getMessage());
		}
		String file = id.getPath().substring(id.getPath().lastIndexOf('/') + 1) + ".nbt";
		return sendImport(file, data, world, by);
	}

	/** Envoie une carte vide (sans repères) au centre d'un monde neuf : on la bâtit et on y pose les repères à la main. */
	public String sendBlank(String world, ServerPlayer by) {
		requireServerThread();
		return send(new Blank(), target(world), by);
	}

	/** Vide le monde de partie : il est remis à neuf et reste plat et vide ; on y joue autour du Conteur. */
	public String clear(String world, ServerPlayer by) {
		requireServerThread();
		return send(new Nothing(), target(world), by);
	}

	/** Le monde de partie que désigne text (identifiant ou nom, même approché), ou celui de la partie du serveur. */
	WorldEntry target(String text) {
		WorldEntry e;
		if (text == null || text.isBlank()) {
			String id = defaultWorld.get();
			if (id == null) {
				throw new GameException("Le monde de la partie n'est pas disponible sur ce serveur (voir son journal).");
			}
			e = worlds.get(id);
		} else {
			e = WorldLookup.resolve(worlds.list(), text);
		}
		if (!accepts(e.sorte())) {
			throw new GameException("« " + e.nom() + " » est un " + e.sorte().label() + " : une carte s'envoie à un monde de partie "
				+ "(partie ou partie d'essai, /vc monde liste). Dans un atelier, posez-la : /vc carte poser <id> " + e.id() + ".");
		}
		return e;
	}

	// ------------------------------------------------------------------
	// L'envoi
	// ------------------------------------------------------------------

	/** Le centre de la place sur le sol plat d'un monde neuf : là où l'on arrive en entrant dans ce monde. */
	static BlockPos center() {
		return new BlockPos(0, WorldManager.SURFACE_Y, 0);
	}

	/**
	 * Lance l'envoi de src au monde e : contrôles, sauvegarde de la carte retouchée, remise à neuf, pose, adoption.
	 * Renvoie le message pour celui qui l'a demandé ; la suite arrive par messages et par la barre.
	 */
	String send(Source src, WorldEntry e, ServerPlayer by) {
		requireServerThread();
		String id = e.id();
		Sending already = sending.get(id);
		if (already != null) {
			throw new GameException("« " + e.nom() + " » reçoit déjà " + already.label() + " : attendez la fin de la barre.");
		}
		McHost host = hosts.apply(id);
		if (host != null) {
			if (host.gameRunning()) {
				throw new GameException("Une partie se joue dans « " + e.nom() + " » : on change de carte entre deux parties. "
					+ "Arrêtez-la d'abord (/vc arreter).");
			}
			host.requireFree();
		}
		if (worlds.busy(id)) {
			throw new GameException("Le monde « " + e.nom() + " » est en cours de remise à neuf : réessayez dans un instant.");
		}
		ServerLevel old = worlds.isOpen(id) ? server.getLevel(WorldManager.key(id)) : null;
		if (old != null && placer.busy(old)) {
			throw new GameException("Une carte est en train d'être posée dans « " + e.nom() + " » : attendez la fin de la barre.");
		}
		// La carte actuelle, retouchée à la main sans être enregistrée, n'est jamais perdue : elle part d'abord dans
		// la bibliothèque, et si c'est impossible on ne touche à rien.
		String saved = "";
		Village current = host == null ? null : host.village();
		if (current != null && current.modified()) {
			try {
				saved = " L'ancienne carte, retouchée à la main, est sauvegardée dans la bibliothèque : « " + maps.backup(current) + " ».";
				host.mapSaved(current);
			} catch (GameException ex) {
				throw new GameException("Rien n'est envoyé : la carte actuelle de « " + e.nom() + " » a été retouchée à la main et sa "
					+ "sauvegarde automatique a échoué (" + ex.getMessage() + "). Enregistrez-la d'abord : /vc carte enregistrer <id> <nom>.");
			}
		}
		// Ceux qui sont dans le monde en sortent (au hub) : ils suivent la barre, et pourront revenir à la fin.
		Set<UUID> watchers = new HashSet<>();
		if (old != null) {
			old.players().forEach(p -> watchers.add(p.getUUID()));
		}
		if (by != null) {
			watchers.add(by.getUUID());
		}
		Sending s = new Sending(src, e, host, watchers);
		sending.put(id, s);
		if (host != null) {
			host.setSending(src.label());
		}
		CompletableFuture<ServerLevel> fresh;
		try {
			fresh = worlds.recreate(id);
		} catch (RuntimeException ex) {
			sending.remove(id);
			if (host != null) {
				host.setSending(null);
			}
			throw ex;
		}
		ValcombeMod.LOG.info("Envoi de {} à « {} » : remise à neuf du monde.", src.label(), e.nom());
		tell(s, Component.literal("[Valcombe] « " + e.nom() + " » reçoit " + src.label() + " : le monde est remis à neuf, plat et vide, "
			+ "puis " + (src instanceof Nothing ? "il reste tel quel." : "la carte y est posée. Une barre suit l'avancement.")
			+ " Vous pourrez y retourner à la fin.").withStyle(ChatFormatting.GOLD), by);
		fresh.whenComplete((level, error) -> {
			if (error != null) {
				fail(s, error);
			} else {
				placeOn(s, level);
			}
		});
		return "Envoi de " + src.label() + " à « " + e.nom() + " » lancé : le monde est remis à neuf, puis "
			+ (src instanceof Nothing ? "laissé plat et vide." : "la carte y est posée." + McHost.PROGRESS) + saved;
	}

	/** Le monde est neuf : on y pose ce qu'on envoie. */
	private void placeOn(Sending s, ServerLevel level) {
		try {
			String id = s.entry().id();
			mark(id, s.label());
			worlds.setCarte(id, null); // le monde neuf ne porte rien ; la pose d'une carte de la bibliothèque le notera
			BlockPos c = center();
			Predicate<ServerPlayer> watch = p -> s.watchers().contains(p.getUUID());
			switch (s.source()) {
				case Library l -> {
					// Le village de la pose porte les trônes sculptés qu'elle a reposés : c'est lui que la partie adopte.
					MapPlacer.Placement placement = placer.place(l.def(), l.clip(), level, c, watch);
					placement.done().whenComplete((placed, error) -> {
						if (error != null) {
							fail(s, error);
						} else {
							finish(s, level, placement.village());
						}
					});
				}
				case Generated g -> {
					Village v = Village.generated(c, g.theme(), g.seats(), g.size(), g.houses());
					placer.build("Construction du village dans « " + s.entry().nom() + " »", level, v.planBuild(level), c, v.extent(), watch)
						.whenComplete((ok, error) -> {
							if (error != null) {
								fail(s, error);
							} else {
								finish(s, level, v);
							}
						});
				}
				case Imported i -> {
					BlockPos corner = c.offset(-i.clip().width / 2, 0, -i.clip().length / 2);
					int radius = Math.max(i.clip().width, i.clip().length) / 2 + 2;
					placer.build("Import de « " + i.fileName() + " » dans « " + s.entry().nom() + " »", level,
						i.clip().plan(level, corner, false, false), c, radius, watch).whenComplete((ok, error) -> {
							if (error != null) {
								fail(s, error);
							} else {
								finish(s, level, Village.custom(c));
							}
						});
				}
				case Blank b -> finish(s, level, Village.custom(c));
				case Nothing n -> finish(s, level, null);
			}
		} catch (RuntimeException ex) {
			fail(s, ex);
		}
	}

	/** Tout est posé : la carte devient le village de la partie de ce monde (v), ou le monde reste vide (v null). */
	private void finish(Sending s, ServerLevel level, Village v) {
		String id = s.entry().id();
		McHost host = s.host();
		try {
			if (v == null) {
				write(id, EMPTY, "Monde vidé exprès : on y joue sans carte.");
			}
			if (host != null) {
				host.setSending(null);
				if (v != null) {
					host.adopt(v);
				}
			} else if (v != null) {
				// Pas encore de partie liée à ce monde : la fiche de son village l'attend dans son dossier. Un village qu'on
				// vient de générer reçoit ici sa salle de convocation et sa salle d'attente (une carte de la bibliothèque les
				// porte déjà dans ses blocs).
				if (s.source() instanceof Generated) {
					v.buildConvocation(level);
					v.buildHall(level);
				}
				v.save(worlds.folder(id).resolve(Village.FILE));
			}
			mark(id, null);
		} catch (RuntimeException ex) {
			fail(s, ex);
			return;
		} finally {
			sending.remove(id);
			if (host != null) {
				host.setSending(null);
			}
		}
		ValcombeMod.LOG.info("Envoi de {} à « {} » terminé.", s.label(), s.entry().nom());
		String what = v == null ? "« " + s.entry().nom() + " » est vide : on y jouera autour du Conteur."
			: (s.source() instanceof Library l ? "La carte « " + l.def().nom + " »" : s.source() instanceof Generated ? "Le village"
				: s.source() instanceof Imported i ? "La construction « " + i.fileName() + " »" : "La carte vide")
				+ " est en place dans « " + s.entry().nom() + " » : " + counts(v)
				+ (v.seatCount() < MapPlacer.MIN_SEATS ? " Posez les trônes et les maisons (mode construction), puis enregistrez-la." : "");
		tell(s, Component.literal("[Valcombe] " + what + " ").withStyle(ChatFormatting.GREEN).append(goBack(s.entry())), null);
	}

	/** L'envoi a échoué : on le dit, et le monde est laissé tel quel (la marque reste, le démarrage le signalera). */
	private void fail(Sending s, Throwable t) {
		String id = s.entry().id();
		if (sending.get(id) == s) {
			sending.remove(id);
		}
		if (s.host() != null) {
			s.host().setSending(null);
		}
		Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
		String why = c instanceof GameException ? c.getMessage() : "erreur interne : " + c.getMessage();
		if (!(c instanceof GameException)) {
			ValcombeMod.LOG.error("Envoi de " + s.label() + " à « " + s.entry().nom() + " » en échec", c);
		} else {
			ValcombeMod.LOG.warn("Envoi de {} à « {} » en échec : {}", s.label(), s.entry().nom(), why);
		}
		tell(s, Component.literal("[Valcombe] L'envoi de " + s.label() + " à « " + s.entry().nom() + " » a échoué : " + why
			+ " Le monde est peut-être incomplet : envoyez-y de nouveau une carte.").withStyle(ChatFormatting.RED), null);
	}

	/** « 16 trônes, 16 maisons, salle de convocation. » */
	static String counts(Village v) {
		int seats = v.seatCount();
		int houses = v.houseCount();
		return seats + (seats > 1 ? " trônes, " : " trône, ") + houses + (houses > 1 ? " maisons" : " maison")
			+ (v.convocation() != null ? ", salle de convocation." : ".");
	}

	/** Le lien pour retourner dans ce monde (la même commande que le menu « Mondes »). */
	private static MutableComponent goBack(WorldEntry e) {
		String cmd = "/vc monde aller " + e.id();
		return Component.literal("[Y aller]").withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
			.withStyle(st -> st.withClickEvent(new ClickEvent.RunCommand(cmd))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Retourner dans « " + e.nom() + " »"))));
	}

	/** Un message à ceux qui suivent l'envoi (joueurs sortis du monde, demandeur), sauf except. */
	private void tell(Sending s, Component text, ServerPlayer except) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (s.watchers().contains(p.getUUID()) && p != except) {
				p.sendSystemMessage(text);
			}
		}
	}

	/** Écrit (ou efface, text null) la marque d'envoi en cours dans le dossier du monde. */
	private void mark(String id, String text) {
		if (text == null) {
			try {
				Files.deleteIfExists(worlds.folder(id).resolve(MARK));
			} catch (IOException ex) {
				ValcombeMod.LOG.warn("Marque d'envoi non effacée", ex);
			}
			return;
		}
		write(id, MARK, "Envoi de " + text);
	}

	private void write(String id, String name, String text) {
		try {
			Path f = worlds.folder(id).resolve(name);
			Files.createDirectories(f.getParent());
			Files.writeString(f, text);
		} catch (IOException ex) {
			ValcombeMod.LOG.warn("Fichier {} du monde {} non écrit", name, id, ex);
		}
	}

	/** À l'arrêt du serveur : les envois en cours sont oubliés (leurs poses échouent d'elles-mêmes). */
	public void stop() {
		for (Sending s : List.copyOf(sending.values())) {
			if (s.host() != null) {
				s.host().setSending(null);
			}
		}
		sending.clear();
	}
}
