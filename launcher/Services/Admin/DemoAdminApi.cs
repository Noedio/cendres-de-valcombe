using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Valcombe.Launcher.Services.Admin;

/// <summary>
/// Un serveur fictif qui répond comme l'API d'administration du mod (docs/ADMIN-API.md), sans Minecraft.
/// Il sert aux captures de la page Serveur (<c>--capture</c>, par <see cref="DemoConnection"/>) et à la
/// vérification du client (<c>--verifier-admin</c>, derrière un vrai serveur HTTP local).
/// Il imite aussi les refus : clé fausse (401), blocage après cinq essais ratés (429), demande fautive (400),
/// chemin inconnu (404), mauvaise méthode (405), serveur arrêté (503).
/// </summary>
public sealed class DemoAdminApi
{
    /// <summary>La clé de ce serveur fictif.</summary>
    public const string Key = "demo_7f3a9c0e5b2d4f6a8c1e3b5d7f9a0c2e4b6d8f0a";

    private static readonly Regex PlayerName = new("^[A-Za-z0-9_]{1,16}$");
    private readonly object _gate = new();
    private readonly DateTime _startedAt = DateTime.UtcNow.AddSeconds(-(2 * 3600 + 14 * 60 + 37));
    private readonly List<AdminConsoleLine> _console = new();
    private readonly List<(string Name, bool Op, string World, int Ping)> _players = new()
    {
        ("Hugo", false, "valcombe:partie-principale", 41),
        ("Ines", false, "valcombe:partie-principale", 63),
        ("Lea", false, "valcombe:partie-principale", 28),
        ("Malo", false, "valcombe:hub", 112),
        ("Yoann", true, "valcombe:partie-principale", 12),
    };
    private readonly List<string> _whitelist = new() { "Hugo", "Ines", "Lea", "Malo", "Nora", "Yoann" };
    private readonly List<string> _ops = new() { "Yoann" };
    private string _instance = Guid.NewGuid().ToString();
    private long _seq;
    private int _badKeys;
    private bool _whitelistOn = true;
    private string _welcome = "";
    private string _evening = "vendredi 21 h";
    private int _maxPlayers = 20;
    private int _port = 25565;
    private int _savedPort = 25565;

    public DemoAdminApi()
    {
        SeedConsole();
        SeedBackups();
    }

    /// <summary>Durée d'une sauvegarde manuelle du serveur fictif (la page la suit phase par phase).</summary>
    public TimeSpan BackupDuration { get; set; } = TimeSpan.FromSeconds(5);

    /// <summary>Après un redémarrage, le serveur fictif répond 503 pendant ce temps (comme un vrai qui démarre).</summary>
    public TimeSpan RestartDowntime { get; set; } = TimeSpan.Zero;

    /// <summary>Nombre de redémarrages (par l'API ou pour une restauration) : les vérifications le comptent.</summary>
    public int Restarts { get; private set; }

    /// <summary>Port de la tablette du serveur fictif (le port du jeu ne peut pas être le même).</summary>
    public int TabletPort { get; set; } = AdminClient.DefaultPort;

    /// <summary>Version du mod que le serveur fictif annonce (GET /admin/etat).</summary>
    public string Version { get; set; } = "0.14.0";

    /// <summary>Faux après POST /admin/arreter : le serveur fictif répond alors 503 à tout.</summary>
    public bool Online { get; private set; } = true;

    /// <summary>Un serveur tout juste installé : personne de connecté, liste blanche et opérateurs vides, aucune sauvegarde.</summary>
    public static DemoAdminApi Fresh()
    {
        var api = new DemoAdminApi();
        api._players.Clear();
        api._whitelist.Clear();
        api._ops.Clear();
        api._backups.Clear();
        api._evening = "";
        // Seule la partie principale, salon ouvert : rien ne s'y joue encore.
        api._games.RemoveAll(g => g.Trial);
        foreach (var g in api._games)
        {
            (g.Status, g.Phase, g.Number, g.Lobby, g.Seated, g.Storyteller, g.Present, g.Watchers) = ("ouverte", null, 0, 0, 0, null, 0, 0);
            g.Places = 16;
        }
        return api;
    }

    /// <summary>Les archives du serveur fictif comme un dossier backups/ les montre (serveur de ce PC arrêté, pour les captures).</summary>
    public List<AdminBackup> DiskListing()
    {
        lock (_gate)
        {
            return _backups.Select(b => new AdminBackup
            {
                Nom = b.Name, Sorte = b.Kind, Date = b.At.ToString("O"), Octets = b.Bytes, Rotation = b.Kind != "manuelle",
            }).ToList();
        }
    }

    /// <summary>Démarre le serveur fictif arrêté (ce que fait systemd pour un serveur loué).</summary>
    public void Start()
    {
        lock (_gate)
        {
            if (!Online)
            {
                Online = true;
                Restart();
            }
        }
    }

    /// <summary>Arrête le serveur fictif sans passer par l'API (un serveur loué éteint, pour les captures).</summary>
    public void Halt()
    {
        lock (_gate)
        {
            Online = false;
        }
    }

    /// <summary>Imite un serveur distant réglé par défaut (adminLocalOnly) : toute requête reçoit 403.</summary>
    public bool RefuseRemote { get; init; }

    /// <summary>Répond à une requête comme le ferait le mod.</summary>
    public AdminReply Handle(string method, string pathAndQuery, string? body, string? key)
    {
        lock (_gate)
        {
            if (Gate(key) is { } refused)
            {
                return refused;
            }
            AdvanceBackup();
            string path = pathAndQuery;
            var query = new Dictionary<string, string>();
            int q = path.IndexOf('?');
            if (q >= 0)
            {
                foreach (string part in path[(q + 1)..].Split('&', StringSplitOptions.RemoveEmptyEntries))
                {
                    int eq = part.IndexOf('=');
                    query[eq < 0 ? part : part[..eq]] = eq < 0 ? "" : Uri.UnescapeDataString(part[(eq + 1)..]);
                }
                path = path[..q];
            }
            if (path == "/admin/sauvegardes" || path.StartsWith("/admin/sauvegardes/", StringComparison.Ordinal))
            {
                return Backups(method, path == "/admin/sauvegardes" ? "" : path["/admin/sauvegardes/".Length..], body);
            }
            if (path == "/admin/parties" || path.StartsWith("/admin/parties/", StringComparison.Ordinal))
            {
                return Games(method, path, body);
            }
            string? expected = path switch
            {
                "/admin/etat" or "/admin/joueurs" or "/admin/console" => "GET",
                "/admin/commande" or "/admin/arreter" or "/admin/redemarrer" => "POST",
                "/admin/reglages" => method,
                _ => null,
            };
            if (expected == null)
            {
                return Error(404, "Point d'accès inconnu : " + path);
            }
            if (expected != method)
            {
                return Error(405, $"Méthode {method} refusée : {expected} attendu.");
            }
            JsonObject? request = null;
            if (method == "POST")
            {
                try
                {
                    request = string.IsNullOrWhiteSpace(body) ? new JsonObject() : JsonNode.Parse(body) as JsonObject;
                }
                catch (JsonException)
                {
                }
                if (request == null)
                {
                    return Error(400, "JSON invalide : un objet est attendu.");
                }
            }
            try
            {
                return path switch
                {
                    "/admin/etat" => Ok(State()),
                    "/admin/joueurs" => Ok(Players()),
                    "/admin/console" => Console(query),
                    "/admin/commande" => Command(request!),
                    "/admin/reglages" when method == "GET" => Ok(Settings()),
                    "/admin/reglages" => ChangeSettings(request!),
                    "/admin/arreter" => Stop(restart: false),
                    _ => Stop(restart: true),
                };
            }
            catch (Exception ex) when (ex is InvalidOperationException or FormatException)
            {
                // Une valeur du mauvais type (texte au lieu d'un nombre…) : le mod répond 400, nous aussi.
                return Error(400, "Valeur invalide : " + ex.Message);
            }
        }
    }

    /// <summary>Les refus qui précèdent tout (adresse, blocage, clé, serveur arrêté), comme dans le mod ; null si la requête passe.</summary>
    private AdminReply? Gate(string? key)
    {
        if (RefuseRemote)
        {
            return Error(403, "L'API d'administration n'accepte que les requêtes de cette machine (adminLocalOnly) : "
                + "depuis un autre PC, passez par un tunnel SSH.");
        }
        if (_badKeys >= 5)
        {
            return Error(429, "Trop d'essais avec une mauvaise clé : réessayez dans 30 s.", TimeSpan.FromSeconds(30));
        }
        if (key == null)
        {
            return Error(401, "Clé d'administration absente (en-tête X-Valcombe-Admin).");
        }
        if (key != Key)
        {
            _badKeys++;
            return Error(401, "Clé d'administration refusée.");
        }
        _badKeys = 0;
        if (!Online || DateTime.UtcNow < _downUntil)
        {
            return Error(503, Online ? "Le serveur démarre." : "Le serveur s'arrête.");
        }
        return null;
    }

    private static AdminReply Ok(JsonObject o, int status = 200) => new(status, o.ToJsonString());

    private static AdminReply Error(int status, string message, TimeSpan? retry = null) =>
        new(status, new JsonObject { ["erreur"] = message }.ToJsonString(), retry);

    private JsonObject State() => new()
    {
        ["api"] = 1,
        ["version"] = Version,
        ["minecraft"] = "26.3",
        ["dedie"] = true,
        ["demarreLe"] = _startedAt.ToString("O"),
        ["dureeSecondes"] = (long)(DateTime.UtcNow - _startedAt).TotalSeconds,
        ["tps"] = 19.8,
        ["tpsCible"] = 20.0,
        ["msParTick"] = 7.42,
        ["enVeille"] = false,
        ["memoire"] = new JsonObject { ["utiliseeMo"] = 1846, ["reserveeMo"] = 2560, ["maxMo"] = 3072 },
        ["joueurs"] = new JsonObject { ["connectes"] = _players.Count, ["max"] = _maxPlayers },
        ["messageAccueil"] = _welcome.Length > 0 ? _welcome : "Les Cendres de Valcombe",
        ["prochaineSoiree"] = _evening,
        ["motd"] = $"Prochaine soirée : {_evening}\nPartie en cours · « Le Bal masqué »",
        ["mondes"] = Worlds(),
        ["parties"] = new JsonArray(_games.Select(g => (JsonNode)GameJson(g)).ToArray()),
        ["redemarrageNecessaire"] = _port != _savedPort,
    };

    /// <summary>Les mondes : le hub, un monde par partie (avec l'état de sa partie), puis les ateliers.</summary>
    private JsonArray Worlds()
    {
        var list = new JsonArray { World("hub", "Le hub", "hub", "charge", 1) };
        foreach (var g in _games)
        {
            var w = World(g.Id, g.Name, g.Trial ? "essai" : "partie", g.Present > 0 || !g.Trial ? "charge" : "decharge", g.Present);
            w["partie"] = new JsonObject { ["statut"] = g.Status, ["etat"] = g.StateLine };
            list.Add(w);
        }
        list.Add(World("atelier-carte-moulin", "Le Moulin (carte)", "atelier-carte", "decharge", 0));
        list.Add(World("atelier-structures", "Atelier structures", "atelier-structures", "remise-a-neuf", 0));
        return list;
    }

    private static JsonObject World(string id, string name, string kind, string state, int players) => new()
    {
        ["id"] = id, ["nom"] = name, ["sorte"] = kind, ["etat"] = state, ["joueurs"] = players,
    };

    // ------------------------------------------------------------------
    // Parties
    // ------------------------------------------------------------------
    /// <summary>Une partie du serveur fictif, avec ce que le tableau des parties en montre.</summary>
    private sealed class DemoGame
    {
        public string Id = "";
        public string Name = "";
        public bool Trial;
        /// <summary><c>ouverte</c>, <c>en-cours</c>, <c>en-pause</c>, <c>suspendue</c>.</summary>
        public string Status = "ouverte";
        /// <summary><c>night</c>, <c>day</c>, <c>setup</c>, ou null au salon.</summary>
        public string? Phase;
        public int Number;
        public int Lobby;
        public int Seated;
        public int Places = 16;
        public string? Storyteller;
        public int Present;
        public int Watchers;

        public bool Launched => Status != "ouverte";

        public string PhaseLine => Phase switch
        {
            "night" => "Nuit " + Number,
            "day" => "Jour " + Number,
            "setup" => "Mise en place",
            _ => "Salon ouvert",
        };

        public string StateLine => Status switch
        {
            "suspendue" => "en suspens (" + PhaseLine.ToLowerInvariant() + ")",
            "en-pause" => PhaseLine.ToLowerInvariant() + ", en pause",
            "en-cours" => PhaseLine.ToLowerInvariant(),
            _ => Lobby == 0 ? "salon ouvert" : Lobby + " inscrit(s)",
        };

        public string StatusText => Status switch
        {
            "suspendue" => "En suspens",
            "en-pause" => "En pause",
            "en-cours" => "En cours",
            _ => "Ouverte",
        };
    }

    private readonly List<DemoGame> _games = new()
    {
        new DemoGame
        {
            Id = "partie-principale", Name = "Partie principale", Status = "en-cours", Phase = "night", Number = 2, Lobby = 12, Seated = 12,
            Places = 12, Storyteller = "Yoann", Present = 4, Watchers = 1,
        },
        new DemoGame { Id = "essai-1", Name = "Essai du jeudi", Trial = true, Lobby = 3, Places = 16 },
        new DemoGame
        {
            Id = "essai-moulin", Name = "Répétition du Moulin", Trial = true, Status = "suspendue", Phase = "day", Number = 3, Lobby = 9,
            Seated = 9, Places = 10, Storyteller = "Lea",
        },
    };

    private static JsonObject GameJson(DemoGame g) => new()
    {
        ["id"] = g.Id, ["nom"] = g.Name, ["sorte"] = g.Trial ? "essai" : "partie", ["principale"] = !g.Trial, ["etat"] = g.StateLine,
        ["enCours"] = g.Launched, ["enPause"] = g.Status is "en-pause" or "suspendue", ["suspendue"] = g.Status == "suspendue",
        ["phase"] = g.Phase, ["inscrits"] = g.Lobby, ["assis"] = g.Launched ? g.Seated : 0, ["conteur"] = g.Storyteller,
        ["joueurs"] = g.Present, ["statut"] = g.Status, ["statutTexte"] = g.StatusText, ["phaseTexte"] = g.PhaseLine,
        ["numero"] = g.Launched && g.Phase is "night" or "day" ? g.Number : 0, ["participants"] = g.Launched ? g.Seated : g.Lobby,
        ["places"] = g.Places, ["spectateurs"] = g.Watchers,
    };

    /// <summary>L'identifiant tiré d'un nom, comme le mod : minuscules sans accents, tirets, 30 caractères au plus.</summary>
    private static string Slug(string name)
    {
        string flat = new string(name.Normalize(NormalizationForm.FormD)
            .Where(c => System.Globalization.CharUnicodeInfo.GetUnicodeCategory(c) != System.Globalization.UnicodeCategory.NonSpacingMark)
            .ToArray()).ToLowerInvariant();
        var s = new StringBuilder();
        foreach (char c in flat)
        {
            s.Append(c is >= 'a' and <= 'z' or >= '0' and <= '9' ? c : '-');
        }
        string slug = Regex.Replace(s.ToString(), "-+", "-").Trim('-');
        slug = slug.Length > 30 ? slug[..30].TrimEnd('-') : slug;
        return slug.Length < 2 ? "essai" : slug;
    }

    /// <summary>/admin/parties et ses sous-chemins, comme le mod.</summary>
    private AdminReply Games(string method, string path, string? body)
    {
        string? expected = path switch
        {
            "/admin/parties" => method is "GET" or "POST" ? method : "GET, POST",
            "/admin/parties/supprimer" or "/admin/parties/suspendre" or "/admin/parties/reprendre" => "POST",
            _ => null,
        };
        if (expected == null)
        {
            return Error(404, "Point d'accès inconnu : " + path);
        }
        if (expected != method)
        {
            return Error(405, $"Méthode {method} refusée ici : {expected} attendu.");
        }
        if (method == "GET")
        {
            return Ok(new JsonObject
            {
                ["parties"] = new JsonArray(_games.Select(g => (JsonNode)GameJson(g)).ToArray()),
                ["enCours"] = _games.Count(g => g.Launched),
                ["principale"] = _games.FirstOrDefault(g => !g.Trial)?.Id,
                ["disponible"] = true,
            });
        }
        JsonObject? request = null;
        try
        {
            request = string.IsNullOrWhiteSpace(body) ? new JsonObject() : JsonNode.Parse(body) as JsonObject;
        }
        catch (JsonException)
        {
        }
        if (request == null)
        {
            return Error(400, "JSON invalide : un objet est attendu.");
        }
        string? Text(string key) => request[key] is JsonValue v && v.TryGetValue(out string? t) && t.Trim().Length > 0 ? t.Trim() : null;
        if (path == "/admin/parties")
        {
            string? name = Text("nom");
            if (name == null)
            {
                return Error(400, "Champ « nom » attendu.");
            }
            if (name.Length > 40)
            {
                return Error(400, "Nom trop long : 40 caractères au plus.");
            }
            string id = Slug(name);
            for (int i = 2; _games.Any(g => g.Id == id); i++)
            {
                id = Slug(name) + "-" + i;
            }
            var created = new DemoGame { Id = id, Name = name, Trial = true, Places = 16 };
            _games.Add(created);
            Log("INFO", "valcombe", $"Partie d'essai « {name} » créée ({id}).");
            return Ok(new JsonObject
            {
                ["partie"] = GameJson(created),
                ["message"] = $"Partie d'essai « {name} » créée ({id}). Son village est en route. Y aller : /vc partie aller {id}.",
            });
        }
        string? wanted = Text("partie");
        if (wanted == null)
        {
            return Error(400, "Champ « partie » attendu.");
        }
        var game = _games.FirstOrDefault(g => g.Id == wanted);
        if (game == null)
        {
            return Error(404, $"Aucune partie dans le monde « {wanted} ».");
        }
        switch (path)
        {
            case "/admin/parties/supprimer":
                if (!game.Trial)
                {
                    return Error(409, $"« {game.Name} » est le monde de la partie principale : il ne se supprime pas. /vc monde recreer {game.Id} le remet à neuf, plat et vide.");
                }
                if (game.Launched)
                {
                    return Error(409, $"Une partie se joue dans « {game.Name} » : arrêtez-la d'abord (/vc arreter), puis supprimez-le.");
                }
                _games.Remove(game);
                Log("INFO", "valcombe", $"Partie fermée : « {game.Id} ».");
                return Ok(new JsonObject { ["ok"] = true, ["message"] = $"Partie « {game.Name} » supprimée : ses joueurs vont au hub, son monde est effacé." });
            case "/admin/parties/suspendre":
                if (!game.Launched)
                {
                    return Error(409, "Aucune partie en cours.");
                }
                if (game.Status == "suspendue")
                {
                    return Error(409, "La partie est déjà en suspens. Pour la reprendre : /vc partie reprendre.");
                }
                int sent = game.Present;
                game.Status = "suspendue";
                game.Present = 0;
                game.Watchers = 0;
                Log("INFO", "valcombe", $"Partie « {game.Name} » mise en suspens.");
                return Changed(game, $"Partie mise en suspens : horloge arrêtée, {sent} joueur(s) renvoyé(s) au hub. Pour la reprendre : /vc partie reprendre.");
            default:
                if (game.Status != "suspendue")
                {
                    return Error(409, "La partie n'est pas en suspens.");
                }
                game.Status = "en-cours";
                Log("INFO", "valcombe", $"Partie « {game.Name} » reprise.");
                return Changed(game, "Partie reprise : les joueurs sont rappelés (lien [Y aller] dans le chat) et retrouvent leur place en arrivant. "
                    + "Elle repart dès que tout le monde est là, une minute au plus.");
        }
    }

    private static AdminReply Changed(DemoGame g, string message)
    {
        var o = GameJson(g);
        o["message"] = message;
        return Ok(o);
    }

    private JsonObject Players() => new()
    {
        ["connectes"] = _players.Count,
        ["max"] = _maxPlayers,
        ["joueurs"] = new JsonArray(_players.OrderBy(p => p.Name, StringComparer.OrdinalIgnoreCase).Select(p => (JsonNode)new JsonObject
        {
            ["nom"] = p.Name,
            ["uuid"] = Guid.NewGuid().ToString(),
            ["operateur"] = _ops.Contains(p.Name, StringComparer.OrdinalIgnoreCase),
            ["monde"] = p.World,
            ["ping"] = p.Ping,
            ["modeDeJeu"] = "adventure",
        }).ToArray()),
    };

    private AdminReply Console(Dictionary<string, string> query)
    {
        int max = query.TryGetValue("max", out string? m) && int.TryParse(m, out int mm) ? Math.Clamp(mm, 1, 1000) : 0;
        IEnumerable<AdminConsoleLine> lines;
        if (query.TryGetValue("depuis", out string? d) && long.TryParse(d, out long after) && after <= _seq)
        {
            lines = _console.Where(l => l.N > after).Take(max > 0 ? max : 500);
        }
        else
        {
            lines = _console.TakeLast(max > 0 ? max : 200);
        }
        var page = lines.ToList();
        return Ok(new JsonObject
        {
            ["instance"] = _instance,
            ["lignes"] = new JsonArray(page.Select(l => (JsonNode)new JsonObject
            {
                ["n"] = l.N, ["t"] = l.T, ["niveau"] = l.Niveau, ["fil"] = l.Fil, ["source"] = l.Source, ["texte"] = l.Texte,
            }).ToArray()),
            ["suivant"] = page.Count > 0 ? page[^1].N : _seq,
            ["premiere"] = _console.Count > 0 ? _console[0].N : 0,
            ["perdues"] = 0,
            ["plusLoin"] = page.Count > 0 && page[^1].N < _seq,
        });
    }

    private AdminReply Command(JsonObject request)
    {
        if (request.Count != 1 || request["commande"] is not JsonValue v || !v.TryGetValue(out string? command)
            || string.IsNullOrWhiteSpace(command) || command.Contains('\n') || command.Length > 2000)
        {
            return Error(400, "Une seule commande est attendue, sur une ligne : {\"commande\": \"…\"}.");
        }
        command = command.Trim().TrimStart('/');
        string verb = command.Split(' ')[0];
        string rest = command.Length > verb.Length ? command[(verb.Length + 1)..] : "";
        (bool ok, int result, string[] output) = verb switch
        {
            "list" => (true, _players.Count, new[]
            {
                $"Il y a {_players.Count} joueurs connectés sur {_maxPlayers} : {string.Join(", ", _players.Select(p => p.Name))}",
            }),
            "say" when rest.Length > 0 => (true, 1, new[] { "[Server] " + rest }),
            "save-all" => (true, 1, new[] { "Sauvegarde du monde…", "Monde sauvegardé" }),
            "kick" when rest.Length > 0 => _players.RemoveAll(p => p.Name.Equals(rest.Split(' ')[0], StringComparison.OrdinalIgnoreCase)) > 0
                ? (true, 1, new[] { $"{rest.Split(' ')[0]} a été expulsé : Expulsé par un opérateur" })
                : (false, 0, new[] { "Aucun joueur n'a été trouvé" }),
            "vc" => (true, 1, new[] { "[valcombe] Salon ouvert : 5 joueurs, partie « Le Bal masqué »." }),
            _ => (false, 0, new[] { $"Commande inconnue ou incomplète : « {command} »" }),
        };
        Log("INFO", "valcombe", $"[admin] 127.0.0.1 : /{command}");
        foreach (string line in output)
        {
            Log(ok ? "INFO" : "WARN", "valcombe", "[admin] " + line);
        }
        return Ok(new JsonObject
        {
            ["ok"] = ok, ["resultat"] = result, ["sortie"] = new JsonArray(output.Select(o => (JsonNode)JsonValue.Create(o)!).ToArray()),
        });
    }

    private JsonObject Settings() => new()
    {
        ["listeBlanche"] = new JsonObject
        {
            ["active"] = _whitelistOn,
            ["imposee"] = _whitelistOn,
            ["joueurs"] = new JsonArray(_whitelist.Order(StringComparer.OrdinalIgnoreCase).Select(n => (JsonNode)JsonValue.Create(n)!).ToArray()),
        },
        ["operateurs"] = new JsonArray(_ops.Select(n => (JsonNode)new JsonObject { ["nom"] = n, ["uuid"] = Guid.NewGuid().ToString(), ["niveau"] = 4 }).ToArray()),
        ["messageAccueil"] = _welcome,
        ["messageAccueilParDefaut"] = "Les Cendres de Valcombe",
        ["prochaineSoiree"] = _evening,
        ["joueursMax"] = _maxPlayers,
        ["port"] = _port,
        ["portEnregistre"] = _savedPort,
        ["modifiables"] = new JsonArray("listeBlanche", "operateurs", "messageAccueil", "prochaineSoiree", "joueursMax", "port"),
        ["redemarrageNecessaire"] = _port != _savedPort,
        ["enAttente"] = _port != _savedPort ? new JsonArray("port") : new JsonArray(),
    };

    private AdminReply ChangeSettings(JsonObject r)
    {
        // Validation d'abord, comme le mod : une seule faute et rien ne change.
        var errors = new List<string>();
        var known = new[] { "listeBlanche", "operateurs", "messageAccueil", "prochaineSoiree", "joueursMax", "port" };
        foreach (var field in r)
        {
            if (!known.Contains(field.Key))
            {
                errors.Add("Champ inconnu : " + field.Key + ".");
            }
        }
        if (r.Count == 0)
        {
            errors.Add("Aucun réglage à changer.");
        }
        List<string> Names(JsonNode? parent, string field)
        {
            var list = new List<string>();
            if (parent?[field] is JsonArray a)
            {
                foreach (var n in a)
                {
                    string name = n?.GetValue<string>() ?? "";
                    if (!PlayerName.IsMatch(name))
                    {
                        errors.Add($"Nom de joueur invalide : « {name} ».");
                    }
                    list.Add(name);
                }
            }
            return list;
        }
        var wlAdd = Names(r["listeBlanche"], "ajouter");
        var wlRemove = Names(r["listeBlanche"], "retirer");
        var opAdd = Names(r["operateurs"], "ajouter");
        var opRemove = Names(r["operateurs"], "retirer");
        string? welcome = r["messageAccueil"]?.GetValue<string>();
        string? evening = r["prochaineSoiree"]?.GetValue<string>();
        foreach (string? text in new[] { welcome, evening })
        {
            if (text != null && (text.Length > 60 || text.Contains('§') || text.Contains('\n')))
            {
                errors.Add("Message trop long ou invalide : une ligne, 60 caractères au plus, sans « § ».");
            }
        }
        int? maxPlayers = r["joueursMax"]?.GetValue<int>();
        if (maxPlayers is < 1 or > 1000)
        {
            errors.Add("joueursMax : un nombre de 1 à 1000.");
        }
        int? port = r["port"]?.GetValue<int>();
        if (port is < 1024 or > 65535 || port == TabletPort)
        {
            errors.Add("port : un nombre de 1024 à 65535, différent du port de la tablette.");
        }
        if (errors.Count > 0)
        {
            return Error(400, string.Join(" ", errors));
        }
        var results = new JsonArray();
        void Step(string setting, JsonNode? value, bool ok, string message) =>
            results.Add(new JsonObject { ["reglage"] = setting, ["valeur"] = value, ["ok"] = ok, ["message"] = message });
        if (r["listeBlanche"]?["active"] is JsonValue av)
        {
            _whitelistOn = av.GetValue<bool>();
            Step("listeBlanche.active", _whitelistOn, true, _whitelistOn ? "Liste blanche activée." : "Liste blanche désactivée.");
        }
        foreach (string n in wlAdd)
        {
            bool unknown = n.StartsWith("Zz", StringComparison.Ordinal);
            if (!unknown && !_whitelist.Contains(n, StringComparer.OrdinalIgnoreCase))
            {
                _whitelist.Add(n);
            }
            Step("listeBlanche.ajouter", n, !unknown, unknown ? $"Joueur inconnu : aucun compte Minecraft ne s'appelle « {n} »."
                : $"« {n} » ajouté à la liste blanche.");
        }
        foreach (string n in wlRemove)
        {
            _whitelist.RemoveAll(x => x.Equals(n, StringComparison.OrdinalIgnoreCase));
            Step("listeBlanche.retirer", n, true, $"« {n} » retiré de la liste blanche.");
        }
        foreach (string n in opAdd)
        {
            if (!_ops.Contains(n, StringComparer.OrdinalIgnoreCase))
            {
                _ops.Add(n);
            }
            Step("operateurs.ajouter", n, true, $"« {n} » est opérateur.");
        }
        foreach (string n in opRemove)
        {
            _ops.RemoveAll(x => x.Equals(n, StringComparison.OrdinalIgnoreCase));
            Step("operateurs.retirer", n, true, $"« {n} » n'est plus opérateur.");
        }
        if (welcome != null)
        {
            _welcome = welcome;
            Step("messageAccueil", welcome, true, welcome.Length == 0 ? "Message d'accueil par défaut." : $"Message d'accueil : « {welcome} ».");
        }
        if (evening != null)
        {
            _evening = evening;
            Step("prochaineSoiree", evening, true, evening.Length == 0 ? "Annonce de soirée effacée." : $"Prochaine soirée : {evening}.");
        }
        if (maxPlayers != null)
        {
            _maxPlayers = maxPlayers.Value;
            Step("joueursMax", maxPlayers.Value, true, $"Joueurs au plus : {_maxPlayers}.");
        }
        if (port != null)
        {
            _savedPort = port.Value;
            Step("port", port.Value, true, $"Port enregistré : {port}. Il sera pris en compte au prochain redémarrage du serveur.");
        }
        bool allOk = results.All(x => x!["ok"]!.GetValue<bool>());
        return Ok(new JsonObject
        {
            ["ok"] = allOk, ["resultats"] = results, ["redemarrageNecessaire"] = _port != _savedPort, ["reglages"] = Settings(),
        });
    }

    private AdminReply Stop(bool restart)
    {
        Log("INFO", "valcombe", restart ? "[admin] Redémarrage demandé par 127.0.0.1" : "[admin] Arrêt demandé par 127.0.0.1");
        if (restart)
        {
            Restart();
        }
        else
        {
            Online = false;
        }
        return Ok(new JsonObject
        {
            ["ok"] = true,
            ["message"] = restart ? "Redémarrage : le serveur s'arrête proprement, puis celui qui l'a lancé le relance." : "Arrêt du serveur en cours.",
            ["drapeau"] = restart ? "config/valcombe/redemarrer" : null,
        }, 202);
    }

    /// <summary>Le serveur « revient » aussitôt : une nouvelle instance, des numéros de console qui repartent de 1, et la restauration prévue appliquée.</summary>
    private void Restart()
    {
        Restarts++;
        _instance = Guid.NewGuid().ToString();
        _console.Clear();
        _seq = 0;
        _port = _savedPort;
        _job = null; // une sauvegarde en cours est abandonnée par l'arrêt
        SeedConsole();
        if (_restorePlan is { } plan)
        {
            ApplyRestore(plan.Name, plan.At);
        }
        _downUntil = DateTime.UtcNow + RestartDowntime;
    }

    // ------------------------------------------------------------------
    // Sauvegardes
    // ------------------------------------------------------------------
    private sealed class DemoBackup
    {
        public string Name = "";
        public string Kind = "";
        public DateTimeOffset At;
        public long Bytes;
        public bool Readable = true;
        public string By = "automatique";
        public int Files = 1873;
        public long Unpacked = 61_400_211;
    }

    private static readonly string[] DemoWorlds = { "Le hub", "Partie principale", "Essai du jeudi", "Le Moulin (carte)", "Atelier structures" };
    private const long FreeSpace = 81_604_378_624;
    private readonly List<DemoBackup> _backups = new();
    private (string Name, DateTime Start, string By)? _job;
    private JsonObject? _lastOutcome;
    private (string Name, DateTime At, string By)? _restorePlan;
    private JsonObject? _lastRestore;
    private bool _autoOn = true;
    private int _interval = 360;
    private bool _atStop = true;
    private int _keep = 10;
    private bool _duringGame;
    private DateTime _downUntil = DateTime.MinValue;

    private static string BackupName(DateTimeOffset at, string kind) => $"valcombe-{at:yyyy-MM-dd_HH-mm-ss}-{kind}.zip";

    private string AddBackup(DateTimeOffset at, string kind, long bytes, string by, bool readable = true)
    {
        at = new DateTimeOffset(at.Year, at.Month, at.Day, at.Hour, at.Minute, at.Second, at.Offset);
        string name = BackupName(at, kind);
        for (int n = 2; _backups.Any(b => b.Name == name); n++)
        {
            name = BackupName(at, kind)[..^4] + "-" + n + ".zip";
        }
        _backups.Add(new DemoBackup { Name = name, Kind = kind, At = at, Bytes = bytes, By = by, Readable = readable });
        _backups.Sort((a, b) => b.At.CompareTo(a.At));
        return name;
    }

    /// <summary>Une semaine de sauvegardes, comme sur un serveur qui tourne depuis un moment, dont une archive abîmée.</summary>
    private void SeedBackups()
    {
        var now = DateTimeOffset.Now;
        AddBackup(now.AddHours(-1).AddMinutes(-12), "auto", 48_213_301, "automatique");
        AddBackup(now.AddHours(-7).AddMinutes(-12), "auto", 47_902_118, "automatique");
        AddBackup(now.AddHours(-15).AddMinutes(-31), "arret", 47_655_870, "arrêt du serveur");
        AddBackup(now.AddHours(-21).AddMinutes(-12), "auto", 46_120_442, "automatique");
        AddBackup(now.AddDays(-2).AddHours(-1).AddMinutes(-5), "manuelle", 45_877_013, "Yoann (en jeu)");
        AddBackup(now.AddDays(-4).AddHours(-3), "avant-restauration", 44_310_552, "restauration");
        AddBackup(now.AddDays(-6).AddHours(-2), "auto", 12_582_912, "automatique", readable: false);
    }

    private JsonObject? JobJson()
    {
        if (_job is not { } job)
        {
            return null;
        }
        double f = Math.Clamp((DateTime.UtcNow - job.Start).TotalSeconds / Math.Max(0.1, BackupDuration.TotalSeconds), 0, 1);
        string phase = f < 0.08 ? "attente" : f < 0.2 ? "enregistrement" : f < 0.92 ? "copie" : "rotation";
        int pct = phase switch { "attente" or "enregistrement" => 0, "copie" => (int)((f - 0.2) / 0.72 * 100), _ => 100 };
        return new JsonObject
        {
            ["nom"] = job.Name,
            ["sorte"] = "manuelle",
            ["par"] = job.By,
            ["debut"] = job.Start.ToString("O"),
            ["phase"] = phase,
            ["fichiers"] = 1873 * pct / 100,
            ["fichiersTotal"] = 1873,
            ["octets"] = 61_400_211L * pct / 100,
            ["octetsTotal"] = 61_400_211L,
            ["pourcent"] = pct,
        };
    }

    /// <summary>La sauvegarde en cours se termine une fois sa durée écoulée.</summary>
    private void AdvanceBackup()
    {
        if (_job is not { } job || DateTime.UtcNow - job.Start < BackupDuration)
        {
            return;
        }
        _job = null;
        var at = new DateTimeOffset(job.Start.ToLocalTime());
        string name = AddBackup(at, "manuelle", 48_602_775, job.By);
        _lastOutcome = new JsonObject
        {
            ["nom"] = name, ["sorte"] = "manuelle", ["ok"] = true,
            ["message"] = $"Sauvegarde {name} terminée : 46,4 Mo, 1873 fichiers, en {BackupDuration.TotalSeconds:0} s.",
            ["octets"] = 48_602_775, ["fichiers"] = 1873, ["dureeSecondes"] = Math.Round(BackupDuration.TotalSeconds, 1),
            ["fin"] = DateTime.UtcNow.ToString("O"), ["effacees"] = new JsonArray(),
        };
        Log("INFO", "valcombe", "[valcombe] " + _lastOutcome["message"]!.GetValue<string>());
    }

    private JsonObject BackupValues() => new()
    {
        ["actives"] = _autoOn, ["intervalleMinutes"] = _interval, ["aLArret"] = _atStop, ["garder"] = _keep, ["pendantPartie"] = _duringGame,
    };

    private string? NextAuto() => _autoOn && _backups.Count > 0
        ? _backups.Max(b => b.At).ToUniversalTime().AddMinutes(_interval).UtcDateTime.ToString("yyyy-MM-ddTHH:mm:ssZ")
        : null;

    private JsonObject BackupList() => new()
    {
        ["dossier"] = "backups",
        ["sauvegardes"] = new JsonArray(_backups.Select(b =>
        {
            var o = new JsonObject
            {
                ["nom"] = b.Name, ["sorte"] = b.Kind, ["date"] = b.At.ToString("yyyy-MM-ddTHH:mm:sszzz"), ["octets"] = b.Bytes,
                ["rotation"] = b.Kind != "manuelle", ["lisible"] = b.Readable,
            };
            if (b.Readable)
            {
                o["par"] = b.By;
                o["versionMod"] = "0.14.0";
                o["fichiers"] = b.Files;
                o["octetsDecompresses"] = b.Unpacked;
                o["mondes"] = new JsonArray(DemoWorlds.Select(w => (JsonNode)JsonValue.Create(w)!).ToArray());
            }
            else
            {
                o["erreur"] = "Archive abîmée : fin de l'archive introuvable (copie interrompue ?).";
            }
            return (JsonNode)o;
        }).ToArray()),
        ["nombre"] = _backups.Count,
        ["octetsTotal"] = _backups.Sum(b => b.Bytes),
        ["espaceLibre"] = FreeSpace,
        ["enCours"] = JobJson(),
        ["derniere"] = _lastOutcome?.DeepClone(),
        ["prochaineAuto"] = NextAuto(),
        ["reportee"] = false,
        ["restaurationPossible"] = true,
        ["restaurationPrevue"] = _restorePlan is { } plan
            ? new JsonObject { ["nom"] = plan.Name, ["demandeLe"] = plan.At.ToString("O"), ["par"] = plan.By }
            : null,
        ["derniereRestauration"] = _lastRestore?.DeepClone(),
        ["reglages"] = BackupValues(),
    };

    private JsonObject BackupSettingsJson() => new()
    {
        ["reglages"] = BackupValues(),
        ["bornes"] = new JsonObject { ["intervalleMinutes"] = new JsonArray(15, 10080), ["garder"] = new JsonArray(1, 500) },
        ["parDefaut"] = new JsonObject
        {
            ["actives"] = true, ["intervalleMinutes"] = 360, ["aLArret"] = true, ["garder"] = 10, ["pendantPartie"] = false,
        },
        ["automatiquesPossibles"] = true,
        ["prochaineAuto"] = NextAuto(),
    };

    private AdminReply Backups(string method, string sub, string? body)
    {
        JsonObject? request = null;
        if (method == "POST")
        {
            try
            {
                request = string.IsNullOrWhiteSpace(body) ? new JsonObject() : JsonNode.Parse(body) as JsonObject;
            }
            catch (JsonException)
            {
            }
            if (request == null)
            {
                return Error(400, "JSON invalide : un objet est attendu.");
            }
        }
        switch (sub)
        {
            case "":
                if (method == "GET")
                {
                    return Ok(BackupList());
                }
                if (method != "POST")
                {
                    return Error(405, $"Méthode {method} refusée : GET, POST attendu.");
                }
                if (request!.Count > 0)
                {
                    return Error(400, $"Aucun champ attendu ici (« {request.First().Key} » reçu).");
                }
                if (_job is { } running)
                {
                    return Error(409, $"Une sauvegarde est déjà en cours ({running.Name}) : attendez qu'elle se termine.");
                }
                var start = DateTime.UtcNow;
                string name = BackupName(new DateTimeOffset(start.ToLocalTime()), "manuelle");
                _job = (name, start, "API d'administration (127.0.0.1)");
                Log("INFO", "valcombe", $"[valcombe] Sauvegarde {name} demandée par l'API d'administration (127.0.0.1)");
                return Ok(new JsonObject
                {
                    ["ok"] = true,
                    ["message"] = $"Sauvegarde {name} lancée : suivez-la dans GET /admin/sauvegardes (enCours).",
                    ["nom"] = name,
                    ["enCours"] = JobJson(),
                }, 202);

            case "reglages":
                if (method == "GET")
                {
                    return Ok(BackupSettingsJson());
                }
                if (method != "POST")
                {
                    return Error(405, $"Méthode {method} refusée : GET, POST attendu.");
                }
                return ChangeBackupSettings(request!);

            case "restaurer":
                if (method == "DELETE")
                {
                    bool had = _restorePlan != null;
                    _restorePlan = null;
                    return Ok(new JsonObject
                    {
                        ["ok"] = true, ["annulee"] = had, ["message"] = had ? "Restauration prévue annulée." : "Aucune restauration n'était prévue.",
                    });
                }
                if (method != "POST")
                {
                    return Error(405, $"Méthode {method} refusée : POST, DELETE attendu.");
                }
                return Restore(request!);

            default:
                if (!AdminBackupText.IsName(sub))
                {
                    return Error(404, "Point d'accès inconnu : /admin/sauvegardes/" + sub);
                }
                var backup = _backups.FirstOrDefault(b => b.Name == sub);
                if (method == "DELETE")
                {
                    if (backup == null)
                    {
                        return Error(404, $"Aucune sauvegarde ne s'appelle « {sub} ».");
                    }
                    if (_restorePlan?.Name == sub)
                    {
                        return Error(409, $"La restauration de « {sub} » est prévue au prochain démarrage : annulez-la d'abord.");
                    }
                    _backups.Remove(backup);
                    Log("INFO", "valcombe", $"[valcombe] Sauvegarde {sub} effacée par l'API d'administration (127.0.0.1)");
                    return Ok(new JsonObject { ["ok"] = true, ["message"] = $"Sauvegarde « {sub} » effacée." });
                }
                if (method != "GET")
                {
                    return Error(405, $"Méthode {method} refusée : GET, DELETE attendu.");
                }
                // Une archive est binaire : le serveur fictif la rend par TryDownload, pas en JSON.
                return backup == null ? Error(404, $"Aucune sauvegarde ne s'appelle « {sub} ».")
                    : Error(501, "Le serveur fictif rend les archives par TryDownload.");
        }
    }

    private AdminReply ChangeBackupSettings(JsonObject r)
    {
        var errors = new List<string>();
        var known = new[] { "actives", "intervalleMinutes", "aLArret", "garder", "pendantPartie" };
        foreach (var field in r)
        {
            if (!known.Contains(field.Key))
            {
                errors.Add($"champ inconnu « {field.Key} »");
            }
        }
        if (r.Count == 0)
        {
            errors.Add("aucun réglage à changer");
        }
        bool? Flag(string name)
        {
            if (r[name] is not { } v)
            {
                return null;
            }
            if (v.GetValueKind() is JsonValueKind.True or JsonValueKind.False)
            {
                return v.GetValue<bool>();
            }
            errors.Add($"« {name} » : vrai ou faux attendu (true / false)");
            return null;
        }
        int? Number(string name, int min, int max)
        {
            if (r[name] is not { } v)
            {
                return null;
            }
            if (v.GetValueKind() == JsonValueKind.Number && v.AsValue().TryGetValue(out int n) && n >= min && n <= max)
            {
                return n;
            }
            errors.Add($"« {name} » : un nombre entier de {min} à {max}");
            return null;
        }
        bool? on = Flag("actives");
        bool? atStop = Flag("aLArret");
        bool? during = Flag("pendantPartie");
        int? interval = Number("intervalleMinutes", 15, 10080);
        int? keep = Number("garder", 1, 500);
        if (errors.Count > 0)
        {
            return Error(400, "Réglages refusés : " + string.Join(" ; ", errors) + ".");
        }
        _autoOn = on ?? _autoOn;
        _atStop = atStop ?? _atStop;
        _duringGame = during ?? _duringGame;
        _interval = interval ?? _interval;
        _keep = keep ?? _keep;
        var o = new JsonObject
        {
            ["ok"] = true,
            ["message"] = _autoOn ? $"Sauvegardes automatiques : {AdminBackupText.Interval(_interval)}, {_keep} gardées" + (_atStop ? ", et à chaque arrêt." : ".")
                : "Sauvegardes automatiques désactivées : seulement à la demande.",
        };
        foreach (var field in BackupSettingsJson())
        {
            o[field.Key] = field.Value?.DeepClone();
        }
        return Ok(o);
    }

    private AdminReply Restore(JsonObject r)
    {
        var errors = new List<string>();
        foreach (var field in r)
        {
            if (field.Key is not ("nom" or "redemarrer"))
            {
                errors.Add($"champ inconnu « {field.Key} »");
            }
        }
        string? name = r["nom"] is JsonValue nv && nv.TryGetValue(out string? s) ? s : null;
        if (name == null)
        {
            errors.Add("« nom » : nom de la sauvegarde attendu (texte)");
        }
        else if (!AdminBackupText.IsName(name))
        {
            errors.Add($"« nom » : « {name} » n'est pas un nom de sauvegarde (valcombe-AAAA-MM-JJ_HH-MM-SS-sorte.zip)");
        }
        bool restart = false;
        if (r["redemarrer"] is { } rv)
        {
            if (rv.GetValueKind() is JsonValueKind.True or JsonValueKind.False)
            {
                restart = rv.GetValue<bool>();
            }
            else
            {
                errors.Add("« redemarrer » : vrai ou faux attendu (true / false)");
            }
        }
        if (errors.Count > 0)
        {
            return Error(400, "Restauration refusée : " + string.Join(" ; ", errors) + ".");
        }
        var backup = _backups.FirstOrDefault(b => b.Name == name);
        if (backup == null)
        {
            return Error(404, $"Aucune sauvegarde ne s'appelle « {name} ».");
        }
        if (!backup.Readable)
        {
            return Error(409, $"« {name} » est illisible (archive abîmée) : elle ne peut pas être restaurée.");
        }
        _restorePlan = (name!, DateTime.UtcNow, "API d'administration (127.0.0.1)");
        string message = $"Restauration de « {name} » prévue au prochain démarrage du serveur. L'état actuel sera d'abord sauvegardé.";
        Log("INFO", "valcombe", "[admin] " + message);
        if (!restart)
        {
            return Ok(new JsonObject
            {
                ["ok"] = true, ["nom"] = name, ["redemarrage"] = false,
                ["message"] = message + " Redémarrez le serveur pour l'appliquer (POST /admin/redemarrer).", ["drapeau"] = null,
            });
        }
        Restart();
        return Ok(new JsonObject
        {
            ["ok"] = true, ["nom"] = name, ["redemarrage"] = true,
            ["message"] = message + " Le serveur s'arrête proprement, puis celui qui l'a lancé le relance.",
            ["drapeau"] = "config/valcombe/redemarrer",
        }, 202);
    }

    /// <summary>Au « démarrage » : l'état actuel est sauvegardé (avant-restauration), puis l'archive prend sa place.</summary>
    private void ApplyRestore(string name, DateTime askedAt)
    {
        _restorePlan = null;
        string safety = AddBackup(DateTimeOffset.Now, "avant-restauration", 48_710_004, "restauration");
        _lastRestore = new JsonObject
        {
            ["nom"] = name,
            ["demandeLe"] = askedAt.ToString("O"),
            ["faiteLe"] = DateTime.UtcNow.ToString("O"),
            ["ok"] = true,
            ["message"] = $"Sauvegarde « {name} » restaurée. L'état d'avant est gardé dans « {safety} ».",
            ["securite"] = safety,
        };
        Log("INFO", "valcombe", "[valcombe] " + _lastRestore["message"]!.GetValue<string>());
    }

    /// <summary>
    /// GET /admin/sauvegardes/&lt;nom&gt; : vrai si c'est un téléchargement d'archive. <paramref name="archive"/> porte
    /// alors un vrai zip (avec sa fiche, en dernière entrée, comme le mod l'écrit), ou <paramref name="error"/> le refus.
    /// Une archive abîmée est rendue telle quelle : des octets qui ne forment pas un zip.
    /// </summary>
    public bool TryDownload(string pathAndQuery, string? key, out byte[]? archive, out AdminReply? error)
    {
        archive = null;
        error = null;
        string path = pathAndQuery.Split('?')[0];
        const string Prefix = "/admin/sauvegardes/";
        if (!path.StartsWith(Prefix, StringComparison.Ordinal) || !AdminBackupText.IsName(path[Prefix.Length..]))
        {
            return false;
        }
        string name = path[Prefix.Length..];
        lock (_gate)
        {
            error = Gate(key);
            if (error != null)
            {
                return true;
            }
            var backup = _backups.FirstOrDefault(b => b.Name == name);
            if (backup == null)
            {
                error = Error(404, $"Aucune sauvegarde ne s'appelle « {name} ».");
                return true;
            }
            Log("INFO", "valcombe", $"[admin] Téléchargement de la sauvegarde {name} (127.0.0.1)");
            archive = backup.Readable ? BuildArchive(backup) : Encoding.UTF8.GetBytes(new string('?', 4096));
            return true;
        }
    }

    /// <summary>Une petite archive de démonstration : quelques fichiers d'un monde, puis la fiche.</summary>
    private static byte[] BuildArchive(DemoBackup backup)
    {
        using var memory = new MemoryStream();
        using (var zip = new ZipArchive(memory, ZipArchiveMode.Create, leaveOpen: true))
        {
            var random = new Random(backup.Name.GetHashCode());
            var files = new List<string> { "monde/level.dat", "config/valcombe/mondes.json" };
            for (int i = 0; i < 6; i++)
            {
                files.Add($"monde/dimensions/valcombe/partie-principale/region/r.{i}.0.mca");
            }
            foreach (string f in files)
            {
                var entry = zip.CreateEntry(f, CompressionLevel.Fastest);
                using var s = entry.Open();
                byte[] data = new byte[f.EndsWith(".mca") ? 96 * 1024 : 512];
                random.NextBytes(data);
                s.Write(data);
            }
            var manifest = zip.CreateEntry(BackupArchive.ManifestName);
            using (var s = new StreamWriter(manifest.Open()))
            {
                s.Write(new JsonObject
                {
                    ["sorte"] = backup.Kind, ["date"] = backup.At.ToString("O"), ["par"] = backup.By, ["versionMod"] = "0.14.0",
                    ["minecraft"] = "26.3", ["fichiers"] = files.Count,
                    ["mondes"] = new JsonArray(DemoWorlds.Select(w => (JsonNode)JsonValue.Create(w)!).ToArray()),
                }.ToJsonString());
            }
        }
        return memory.ToArray();
    }

    private void Log(string level, string source, string text) => _console.Add(new AdminConsoleLine
    {
        N = ++_seq, T = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), Niveau = level, Fil = "Server thread", Source = source, Texte = text,
    });

    private void SeedConsole()
    {
        var lines = new (string Level, string Source, string Text)[]
        {
            ("INFO", "net.minecraft.server.MinecraftServer", "Starting minecraft server version 26.3"),
            ("INFO", "valcombe", "[valcombe] Les Cendres de Valcombe 0.14.0 : 112 rôles, 9 scénarios"),
            ("INFO", "valcombe", "[valcombe] Mondes : hub, partie-principale, essai-1, atelier-carte-moulin, atelier-structures"),
            ("INFO", "net.minecraft.server.MinecraftServer", "Preparing level \"world\""),
            ("INFO", "valcombe", $"[valcombe] API d'administration prête sur le port {TabletPort} (requêtes de cette machine seulement). Clé : /vc admin cle"),
            ("INFO", "net.minecraft.server.MinecraftServer", "Done (6.412s)! For help, type \"help\""),
            ("INFO", "de.maxhenkel.voicechat.Voicechat", "[voicechat] Voice chat server started at port 24454"),
            ("INFO", "net.minecraft.server.MinecraftServer", "Yoann joined the game"),
            ("INFO", "net.minecraft.server.MinecraftServer", "Lea joined the game"),
            ("INFO", "net.minecraft.server.MinecraftServer", "Hugo joined the game"),
            ("INFO", "valcombe", "[valcombe] Partie « Le Bal masqué » : Yoann est le Conteur"),
            ("INFO", "net.minecraft.server.MinecraftServer", "Ines joined the game"),
            ("INFO", "net.minecraft.server.MinecraftServer", "Malo joined the game"),
            ("WARN", "net.minecraft.server.MinecraftServer", "Can't keep up! Is the server overloaded? Running 2104ms or 42 ticks behind"),
            ("INFO", "valcombe", "[valcombe] Nuit 1 : les rôles de la première nuit se réveillent"),
            ("INFO", "valcombe", "[valcombe] Jour 1 : la cloche sonne, le débat commence"),
            ("INFO", "net.minecraft.server.MinecraftServer", "<Lea> je suis la Veilleuse, et j'ai vu Hugo"),
            ("INFO", "net.minecraft.server.MinecraftServer", "<Hugo> c'est faux !"),
            ("ERROR", "valcombe", "[valcombe] Carte « Le Moulin » : un trône sans maison, il est ignoré"),
            ("INFO", "valcombe", "[valcombe] Accusation : Lea accuse Hugo"),
        };
        long t = DateTimeOffset.UtcNow.AddMinutes(-40).ToUnixTimeMilliseconds();
        foreach (var (level, source, text) in lines)
        {
            t += 97_000;
            _console.Add(new AdminConsoleLine { N = ++_seq, T = t, Niveau = level, Fil = "Server thread", Source = source, Texte = text });
        }
    }
}

/// <summary>Connexion au serveur fictif, sans réseau : pour les captures de la page Serveur.</summary>
public sealed class DemoConnection : IServerConnection
{
    private readonly DemoAdminApi _api;
    private readonly string? _key;

    public DemoConnection(DemoAdminApi api, string description, string? key = DemoAdminApi.Key)
    {
        _api = api;
        _key = key;
        Description = description;
    }

    public string Description { get; }

    public bool CanStartServer => false;

    public Task ConnectAsync(CancellationToken ct) => Task.CompletedTask;

    public Task StartServerAsync(IProgress<(string, double)> progress, CancellationToken ct) => Task.CompletedTask;

    public Task<AdminReply> SendAsync(HttpMethod method, string path, string? json, CancellationToken ct) =>
        Task.FromResult(_api.Handle(method.Method, path, json, _key));

    /// <summary>L'archive du serveur fictif, envoyée par morceaux comme sur un vrai réseau (la page montre l'avancement).</summary>
    public async Task<AdminReply> DownloadAsync(string path, Stream destination, IProgress<(long Done, long? Total)>? progress,
        CancellationToken ct)
    {
        if (!_api.TryDownload(path, _key, out byte[]? archive, out AdminReply? error))
        {
            return _api.Handle("GET", path, null, _key);
        }
        if (error != null)
        {
            return error;
        }
        long done = 0;
        byte[] bytes = archive!;
        foreach (var chunk in bytes.Chunk(64 * 1024))
        {
            await destination.WriteAsync(chunk, ct);
            done += chunk.Length;
            progress?.Report((done, bytes.Length));
            await Task.Delay(20, ct);
        }
        return new AdminReply(200, "");
    }

    public ValueTask DisposeAsync() => ValueTask.CompletedTask;
}

/// <summary>
/// Un serveur loué fictif, pour les captures : l'API du serveur fictif, plus ce que le tunnel SSH sait faire
/// (démarrer le serveur arrêté, lire la version installée, mettre à jour), sans réseau.
/// </summary>
public sealed class DemoManagedConnection : IServerConnection, IManagedServer
{
    private readonly DemoAdminApi _api;
    private readonly DemoConnection _inner;

    public DemoManagedConnection(DemoAdminApi api, string where, string installed)
    {
        _api = api;
        _inner = new DemoConnection(api, "tunnel SSH vers " + where);
        Where = where;
        Installed = installed;
    }

    /// <summary>Le serveur fictif derrière le tunnel (les captures l'arrêtent et le démarrent).</summary>
    public DemoAdminApi Api => _api;

    /// <summary>Version de Valcombe installée sur le serveur fictif (valcombe-installe.txt).</summary>
    public string Installed { get; private set; }

    public string Description => _inner.Description;

    public string Where { get; }

    public bool CanStartServer => true;

    /// <summary>Comme le vrai tunnel : un serveur arrêté ne répond pas du tout (statut 0), il ne dit pas 503.</summary>
    public Task ConnectAsync(CancellationToken ct) => _api.Online ? _inner.ConnectAsync(ct)
        : throw new AdminException(0, $"Le serveur de Valcombe ne répond pas sur {Where.Split('@')[^1]} : il est arrêté, ou en train de démarrer.");

    public Task<AdminReply> SendAsync(HttpMethod method, string path, string? json, CancellationToken ct) => _inner.SendAsync(method, path, json, ct);

    public Task<AdminReply> DownloadAsync(string path, Stream destination, IProgress<(long Done, long? Total)>? progress, CancellationToken ct) =>
        _inner.DownloadAsync(path, destination, progress, ct);

    public async Task StartServerAsync(IProgress<(string, double)> progress, CancellationToken ct)
    {
        progress.Report(($"Démarrage du serveur sur {Where}…", -1));
        await Task.Delay(300, ct);
        _api.Start();
        progress.Report(($"Serveur démarré sur {Where} : l'API répondra dans un instant.", 0));
    }

    public Task<Ssh.VpsServiceStatus> ServiceStatusAsync(CancellationToken ct) => Task.FromResult(new Ssh.VpsServiceStatus
    {
        Installed = true,
        ActiveState = _api.Online ? "active" : "inactive",
        SubState = _api.Online ? "running" : "dead",
        Enabled = true,
        Listening = _api.Online,
        Version = Installed,
        Minecraft = "26.3",
    });

    public async Task<Ssh.VpsInstallResult> UpdateAsync(Release release, IProgress<(string, double)> progress, CancellationToken ct)
    {
        foreach (var (text, p) in new[] { ("Java", 0.2), ("Fabric", 0.4), ("Mods du serveur", 0.7), ("Service systemd", 0.95) })
        {
            progress.Report(("Serveur loué : " + text, p));
            await Task.Delay(150, ct);
        }
        bool changed = Installed != release.Tag;
        Installed = release.Tag;
        return new Ssh.VpsInstallResult { Changed = changed, Restarted = changed && _api.Online, Version = release.Tag, Summary = "Valcombe " + release.Tag };
    }

    public ValueTask DisposeAsync() => _inner.DisposeAsync();
}
