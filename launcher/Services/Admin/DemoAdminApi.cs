using System.Net.Http;
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
    }

    /// <summary>Port de la tablette du serveur fictif (le port du jeu ne peut pas être le même).</summary>
    public int TabletPort { get; set; } = AdminClient.DefaultPort;

    /// <summary>Faux après POST /admin/arreter : le serveur fictif répond alors 503 à tout.</summary>
    public bool Online { get; private set; } = true;

    /// <summary>Imite un serveur distant réglé par défaut (adminLocalOnly) : toute requête reçoit 403.</summary>
    public bool RefuseRemote { get; init; }

    /// <summary>Répond à une requête comme le ferait le mod.</summary>
    public AdminReply Handle(string method, string pathAndQuery, string? body, string? key)
    {
        lock (_gate)
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
            if (!Online)
            {
                return Error(503, "Le serveur s'arrête.");
            }
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

    private static AdminReply Ok(JsonObject o, int status = 200) => new(status, o.ToJsonString());

    private static AdminReply Error(int status, string message, TimeSpan? retry = null) =>
        new(status, new JsonObject { ["erreur"] = message }.ToJsonString(), retry);

    private JsonObject State() => new()
    {
        ["api"] = 1,
        ["version"] = "0.14.0",
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
        ["mondes"] = new JsonArray(
            World("hub", "Le hub", "hub", "charge", 1),
            World("partie-principale", "Partie principale", "partie", "charge", 4),
            World("essai-1", "Essai du jeudi", "essai", "decharge", 0),
            World("atelier-carte-moulin", "Le Moulin (carte)", "atelier-carte", "decharge", 0),
            World("atelier-structures", "Atelier structures", "atelier-structures", "remise-a-neuf", 0)),
        ["redemarrageNecessaire"] = _port != _savedPort,
    };

    private static JsonObject World(string id, string name, string kind, string state, int players) => new()
    {
        ["id"] = id, ["nom"] = name, ["sorte"] = kind, ["etat"] = state, ["joueurs"] = players,
    };

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
            // Le serveur « revient » aussitôt : une nouvelle instance, et des numéros de console qui repartent de 1.
            _instance = Guid.NewGuid().ToString();
            _console.Clear();
            _seq = 0;
            _port = _savedPort;
            SeedConsole();
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

    public ValueTask DisposeAsync() => ValueTask.CompletedTask;
}
