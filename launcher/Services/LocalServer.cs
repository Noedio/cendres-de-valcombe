using System.Diagnostics;
using System.IO;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Valcombe.Launcher.Services;

/// <summary>
/// Le serveur de Valcombe sur le PC de l'hôte : le launcher l'installe (Java, Fabric, mods), le démarre,
/// suit sa sortie et l'arrête. Il sert à héberger une soirée, et à ouvrir l'Atelier sans rien installer.
/// Tout est rangé dans ValcombeData/serveur.
/// </summary>
public sealed class LocalServer
{
    public enum State { Stopped, Preparing, Starting, Running, Stopping }

    /// <summary>Mods que le serveur charge quand le manifeste ne dit pas à qui ils sont destinés (versions avant la 0.8).</summary>
    private static readonly string[] ServerModNames = { "Fabric API", "Simple Voice Chat", "Les Cendres de Valcombe" };

    public static string Dir => Path.Combine(Paths.DataDir, "serveur");
    private static string ModsDir => Path.Combine(Dir, "mods");
    private static string LauncherJar => Path.Combine(Dir, "fabric-server-launch.jar");
    private static string StampFile => Path.Combine(Dir, "valcombe-serveur.txt");
    private static string PropertiesFile => Path.Combine(Dir, "server.properties");
    private static string HostLinkFile => Path.Combine(Dir, "config", "valcombe-hote.json");
    /// <summary>Clé de l'API d'administration, tirée par le mod au premier démarrage (docs/ADMIN-API.md).</summary>
    private static string AdminKeyFile => Path.Combine(Dir, "config", "valcombe", "admin.json");
    /// <summary>Drapeau de redémarrage posé par POST /admin/redemarrer : à sa vue, celui qui a lancé le serveur le relance.</summary>
    private static string RestartFlagFile => Path.Combine(Dir, "config", "valcombe", "redemarrer");

    private Process? _process;
    /// <summary>De quoi relancer le serveur à l'identique quand il demande à redémarrer.</summary>
    private (Release Release, LauncherConfig Config, string HostName, IProgress<(string, double)> Progress)? _lastStart;
    private readonly object _gate = new();
    private readonly List<string> _players = new();
    private readonly LinkedList<string> _log = new();

    public State Current { get; private set; } = State.Stopped;
    /// <summary>Port de l'Atelier annoncé par le mod (0 tant qu'il n'est pas ouvert).</summary>
    public int AtelierPort { get; private set; }
    /// <summary>Lien secret de l'Atelier, donné au mod au démarrage.</summary>
    public string AtelierToken { get; private set; } = "";
    public int Port { get; private set; } = 25565;
    /// <summary>
    /// Port de l'API d'administration annoncé par le mod dans son journal (0 tant qu'elle n'est pas ouverte, ou
    /// avec une version du mod qui ne l'a pas encore).
    /// </summary>
    public int AdminPort { get; private set; }
    /// <summary>Pourquoi l'API d'administration ne s'est pas ouverte, d'après le journal du mod (port pris, API coupée), ou chaîne vide.</summary>
    public string AdminProblem { get; private set; } = "";
    /// <summary>Moment où le serveur a été prêt (l'API s'annonce un instant après).</summary>
    public DateTime RunningSince { get; private set; }
    /// <summary>Nombre de démarrages faits par ce launcher, relances comprises (la page Serveur s'y recale).</summary>
    public int Starts { get; private set; }
    /// <summary>Dernière erreur lisible (port occupé, contrat non accepté…), ou chaîne vide.</summary>
    public string LastError { get; private set; } = "";

    /// <summary>Remarque à montrer à l'hôte pendant que le serveur tourne (pseudo d'invité introuvable…), ou chaîne vide.</summary>
    public string Notice { get; private set; } = "";

    /// <summary>L'état, les joueurs ou le journal ont changé (appelé hors du fil de l'interface).</summary>
    public event Action? Changed;

    public IReadOnlyList<string> Players
    {
        get
        {
            lock (_gate)
            {
                return _players.ToList();
            }
        }
    }

    /// <summary>Les dernières lignes écrites par le serveur.</summary>
    public string LogText
    {
        get
        {
            lock (_gate)
            {
                return string.Join(Environment.NewLine, _log);
            }
        }
    }

    public bool IsInstalled => File.Exists(LauncherJar) && File.Exists(StampFile);

    /// <summary>
    /// La clé d'administration du serveur de ce PC, lue dans son dossier (champ <c>key</c> de
    /// config/valcombe/admin.json), ou null tant que le mod ne l'a pas tirée. Relue à chaque appel :
    /// <c>/vc admin nouvellecle</c> la change pendant que le serveur tourne.
    /// </summary>
    public static string? ReadAdminKey()
    {
        try
        {
            if (!File.Exists(AdminKeyFile))
            {
                return null;
            }
            using var doc = JsonDocument.Parse(File.ReadAllText(AdminKeyFile));
            return doc.RootElement.TryGetProperty("key", out var k) && k.ValueKind == JsonValueKind.String ? k.GetString() : null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException)
        {
            return null;
        }
    }

    /// <summary>
    /// Pour les essais du développeur seulement (--mod-local) : ce fichier remplace le mod publié,
    /// afin d'essayer un mod pas encore publié avec le serveur local.
    /// </summary>
    public static string? ModOverride { get; set; }

    /// <summary>Lien de l'Atelier sur ce PC, ou null tant que le serveur ne l'a pas ouvert.</summary>
    public string? AtelierUrl => Current == State.Running && AtelierPort > 0 && AtelierToken.Length > 0
        ? $"http://localhost:{AtelierPort}/atelier#{AtelierToken}" : null;

    /// <summary>Adresses à donner aux amis du même réseau (Wi-Fi, box) : « 192.168.1.20:25565 ».</summary>
    public static List<string> LocalAddresses(int port)
    {
        var list = new List<string>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                {
                    continue;
                }
                // Une interface sans passerelle (réseau virtuel d'une machine virtuelle) ne mène pas aux amis.
                var props = ni.GetIPProperties();
                bool routed = props.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork);
                foreach (var a in props.UnicastAddresses)
                {
                    if (a.Address.AddressFamily == AddressFamily.InterNetwork)
                    {
                        string text = port == 25565 ? a.Address.ToString() : $"{a.Address}:{port}";
                        if (routed)
                        {
                            list.Insert(0, text);
                        }
                        else
                        {
                            list.Add(text);
                        }
                    }
                }
            }
        }
        catch (NetworkInformationException)
        {
        }
        return list.Distinct().ToList();
    }

    private void Set(State state)
    {
        Current = state;
        Changed?.Invoke();
    }

    /// <summary>
    /// Les lignes du journal écrites après la ligne numéro <paramref name="after"/> (numéros croissants depuis
    /// le lancement du launcher), et le numéro de la dernière. Sert à la console de la page Serveur quand l'API
    /// du mod n'est pas (encore) là.
    /// </summary>
    public (long Last, List<string> Lines) LogSince(long after)
    {
        lock (_gate)
        {
            long first = _logTotal - _log.Count + 1;
            return (_logTotal, _log.Skip((int)Math.Clamp(after - first + 1, 0, _log.Count)).ToList());
        }
    }

    private long _logTotal;

    private void AddLog(string line)
    {
        lock (_gate)
        {
            _logTotal++;
            _log.AddLast(line);
            while (_log.Count > 400)
            {
                _log.RemoveFirst();
            }
        }
    }

    // ------------------------------------------------------------------
    // Installation
    // ------------------------------------------------------------------
    /// <summary>Installe ou met à jour le serveur : Java, lanceur Fabric, mods du serveur, réglages de départ.</summary>
    public async Task<string> InstallAsync(Release release, IProgress<(string, double)> progress, CancellationToken ct = default)
    {
        Directory.CreateDirectory(ModsDir);
        var manifest = release.Manifest;

        // Java officiel de Mojang (le même que pour le jeu : il n'est téléchargé qu'une fois).
        progress.Report(("Serveur : préparation de Java…", 0));
        var java = await Engine.PrepareJavaAsync(manifest.Minecraft, ct);
        await Engine.DownloadAsync(java, progress, ct);

        // Lanceur du serveur Fabric, pour cette version de Minecraft et du chargeur.
        string stamp = $"{manifest.Minecraft} {manifest.Loader}";
        if (!File.Exists(LauncherJar) || !File.Exists(StampFile) || File.ReadAllText(StampFile).Trim() != stamp)
        {
            progress.Report(("Serveur : téléchargement de Fabric…", 0));
            await Http.DownloadAsync(await FabricServerJarUrlAsync(manifest, ct),
                LauncherJar, null, new Progress<double>(p => progress.Report(("Serveur : téléchargement de Fabric…", p))), ct);
            File.WriteAllText(StampFile, stamp);
        }

        var mods = ServerMods(manifest);
        var keep = mods.Select(m => m.FileName).ToHashSet(StringComparer.OrdinalIgnoreCase);
        foreach (string old in Directory.GetFiles(ModsDir, "*.jar"))
        {
            if (!keep.Contains(Path.GetFileName(old)))
            {
                File.Delete(old);
            }
        }
        for (int i = 0; i < mods.Count; i++)
        {
            var mod = mods[i];
            if (mod.FileName != Path.GetFileName(mod.FileName))
            {
                throw new InvalidDataException("Nom de fichier refusé dans le manifeste : " + mod.FileName);
            }
            string target = Path.Combine(ModsDir, mod.FileName);
            if (File.Exists(target) && string.Equals(Http.Sha512(target), mod.Sha512, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }
            int index = i;
            await Http.DownloadTrustedAsync(Releases.Resolve(release, mod), target, mod.Sha512,
                new Progress<double>(p => progress.Report(($"Serveur : {mod.Name}…", (index + p) / mods.Count))), ct);
        }
        progress.Report(("Serveur installé.", 1));
        return java.JavaExe;
    }

    /// <summary>
    /// Mods du serveur : tous ceux du manifeste marqués « both » (Fabric API, Simple Voice Chat, WorldEdit,
    /// Valcombe...), jamais les mods graphiques des joueurs. Rien n'est écrit ici : un mod ajouté au manifeste
    /// par tools/make_modpack.py arrive tout seul. Fantasy est incluse dans le jar de Valcombe.
    /// Partagé avec l'installation sur un serveur loué (Ssh/VpsPlan).
    /// </summary>
    public static List<PackMod> ServerMods(PackManifest manifest) =>
        manifest.Mods.Where(m => m.Side.Length > 0 ? m.Side != "client" : ServerModNames.Contains(m.Name)).ToList();

    /// <summary>
    /// Adresse du lanceur de serveur Fabric pour cette version de Minecraft et du chargeur, avec le dernier
    /// installateur stable. Partagée avec l'installation sur un serveur loué (Ssh/VpsPlan).
    /// </summary>
    public static async Task<string> FabricServerJarUrlAsync(PackManifest manifest, CancellationToken ct = default)
    {
        var installers = JsonNode.Parse(await Http.Client.GetStringAsync("https://meta.fabricmc.net/v2/versions/installer", ct))!.AsArray();
        string installer = (installers.FirstOrDefault(i => i!["stable"]?.GetValue<bool>() == true) ?? installers[0])!["version"]!.GetValue<string>();
        return $"https://meta.fabricmc.net/v2/versions/loader/{manifest.Minecraft}/{manifest.Loader}/{installer}/server/jar";
    }

    /// <summary>Réglages de départ (monde plat et vide, comptes officiels) ; un fichier existant n'est que retouché.</summary>
    private static void WriteProperties(LauncherConfig config)
    {
        string[]? existing = File.Exists(PropertiesFile) ? File.ReadAllLines(PropertiesFile) : null;
        File.WriteAllLines(PropertiesFile, MergeProperties(existing, new[]
        {
            ("server-port", config.HostPort.ToString()),
            ("white-list", config.HostGuestsOnly ? "true" : "false"),
            ("enforce-whitelist", config.HostGuestsOnly ? "true" : "false"),
        }));
    }

    /// <summary>
    /// Fusionne des réglages dans un server.properties : les lignes existantes gardent leur ordre et leurs valeurs,
    /// sauf celles de <paramref name="set"/>. Sans fichier (null), on part des réglages de Valcombe (monde plat
    /// et vide, comptes officiels), complétés par <paramref name="newDefaults"/>. Partagé avec le serveur loué.
    /// </summary>
    public static List<string> MergeProperties(IEnumerable<string>? existing, IEnumerable<(string Key, string Value)> set,
        IEnumerable<(string Key, string Value)>? newDefaults = null)
    {
        var values = new Dictionary<string, string>();
        var order = new List<string>();
        if (existing != null)
        {
            foreach (string line in existing)
            {
                int eq = line.IndexOf('=');
                if (eq > 0 && !line.StartsWith('#'))
                {
                    string key = line[..eq];
                    values[key] = line[(eq + 1)..];
                    order.Add(key);
                }
            }
        }
        else
        {
            // Monde plat sans créatures (biome du vide). Les « : » sont échappés comme l'exige le format.
            void Start(string k, string v)
            {
                values[k] = v;
                order.Add(k);
            }
            Start("motd", "Les Cendres de Valcombe");
            Start("online-mode", "true");
            Start("difficulty", "peaceful");
            Start("gamemode", "adventure");
            Start("pvp", "false");
            Start("spawn-protection", "0");
            Start("generate-structures", "false");
            Start("level-type", @"minecraft\:flat");
            Start("generator-settings", @"{""layers""\:[{""block""\:""minecraft\:bedrock"",""height""\:1},"
                + @"{""block""\:""minecraft\:stone"",""height""\:3},{""block""\:""minecraft\:grass_block"",""height""\:1}],"
                + @"""biome""\:""minecraft\:the_void"",""features""\:false,""lakes""\:false,""structure_overrides""\:[]}");
            foreach (var (k, v) in newDefaults ?? Enumerable.Empty<(string, string)>())
            {
                Start(k, v);
            }
        }
        foreach (var (k, v) in set)
        {
            if (!values.ContainsKey(k))
            {
                order.Add(k);
            }
            values[k] = v;
        }
        return order.Distinct().Select(k => k + "=" + values[k]).ToList();
    }

    // ------------------------------------------------------------------
    // Démarrage et arrêt
    // ------------------------------------------------------------------
    /// <summary>
    /// Démarre le serveur. hostName : pseudo Minecraft de l'hôte (il devient opérateur), ou chaîne vide.
    /// Le contrat de Minecraft doit avoir été accepté par l'hôte dans le launcher.
    /// </summary>
    public Task StartAsync(Release release, LauncherConfig config, string hostName, IProgress<(string, double)> progress,
        CancellationToken ct = default) => StartAsync(release, config, hostName, progress, relaunch: false, ct);

    /// <param name="relaunch">Relance demandée par le serveur (drapeau de redémarrage) : le journal est gardé.</param>
    private async Task StartAsync(Release release, LauncherConfig config, string hostName, IProgress<(string, double)> progress,
        bool relaunch, CancellationToken ct)
    {
        if (Current != State.Stopped)
        {
            return;
        }
        _lastStart = (release, config, hostName, progress);
        if (!config.HostEulaAccepted)
        {
            throw new InvalidOperationException("Avant d'héberger, lisez et acceptez le contrat de Minecraft (page Héberger).");
        }
        LastError = "";
        Notice = "";
        Set(State.Preparing);
        try
        {
            string javaw = await InstallAsync(release, progress, ct);
            string java = Path.Combine(Path.GetDirectoryName(javaw)!, "java.exe");
            if (ModOverride != null)
            {
                foreach (string old in Directory.GetFiles(ModsDir, "valcombe-*.jar"))
                {
                    File.Delete(old);
                }
                File.Copy(ModOverride, Path.Combine(ModsDir, Path.GetFileName(ModOverride)), overwrite: true);
            }
            Port = config.HostPort;
            if (PortBusy(Port))
            {
                throw new InvalidOperationException($"Le port {Port} est déjà utilisé sur ce PC : un autre serveur tourne peut-être déjà. "
                    + "Arrêtez-le, ou changez le port dans les réglages du serveur.");
            }
            WriteProperties(config);
            // L'hôte a accepté le contrat dans le launcher : c'est ce que ce fichier enregistre pour le serveur.
            File.WriteAllText(Path.Combine(Dir, "eula.txt"), "# Contrat accepté par l'hôte dans le launcher Valcombe (https://aka.ms/MinecraftEULA)\neula=true\n");

            // Lien de l'Atelier pour l'hôte : un secret neuf à chaque démarrage, lu puis effacé par le mod.
            AtelierToken = Convert.ToHexString(RandomNumberGenerator.GetBytes(24)).ToLowerInvariant();
            AtelierPort = 0;
            string owner = Regex.IsMatch(hostName, "^[A-Za-z0-9_]{1,16}$") ? hostName : "Hote";
            Directory.CreateDirectory(Path.GetDirectoryName(HostLinkFile)!);
            File.WriteAllText(HostLinkFile, JsonSerializer.Serialize(new Dictionary<string, string> { ["nom"] = owner, ["jeton"] = AtelierToken }));

            AdminPort = 0;
            AdminProblem = "";
            lock (_gate)
            {
                _players.Clear();
                if (!relaunch)
                {
                    _log.Clear();
                }
            }
            var psi = new ProcessStartInfo(File.Exists(java) ? java : javaw)
            {
                WorkingDirectory = Dir,
                UseShellExecute = false,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8,
            };
            foreach (string a in new[]
            {
                $"-Xmx{config.HostMemoryMb}M", "-Xms512M", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-jar", "fabric-server-launch.jar", "nogui",
            })
            {
                psi.ArgumentList.Add(a);
            }
            var p = Process.Start(psi) ?? throw new InvalidOperationException("Java n'a pas démarré.");
            _process = p;
            p.EnableRaisingEvents = true;
            p.OutputDataReceived += (_, e) => OnLine(e.Data, config, owner == "Hote" ? "" : owner);
            p.ErrorDataReceived += (_, e) => OnLine(e.Data, config, "");
            p.Exited += (_, _) =>
            {
                int code = p.ExitCode;
                // Arrêt demandé par le launcher (bouton, fermeture) : jamais de relance, même si un drapeau traîne.
                bool wanted = Current == State.Stopping;
                bool restart = TakeRestartFlag();
                if (!wanted && !restart && code != 0 && LastError.Length == 0)
                {
                    LastError = "Le serveur s'est arrêté tout seul : lisez la fin de son journal ci-dessous.";
                }
                _process = null;
                AtelierPort = 0;
                AdminPort = 0;
                lock (_gate)
                {
                    _players.Clear();
                }
                AddLog($"[launcher] Serveur arrêté (code {code}).");
                Set(State.Stopped);
                if (restart && !wanted)
                {
                    _relaunchPending = true;
                    _ = RelaunchAsync();
                }
            };
            p.BeginOutputReadLine();
            p.BeginErrorReadLine();
            Starts++;
            Set(State.Starting);
            progress.Report(("Démarrage du serveur… (la première fois, il télécharge Minecraft et crée le monde)", 0));
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
            Set(State.Stopped);
            throw;
        }
    }

    /// <summary>
    /// Règle du drapeau de redémarrage (docs/ADMIN-API.md) : le serveur s'est arrêté après POST /admin/redemarrer.
    /// Le drapeau est effacé dans tous les cas ; vrai s'il était posé.
    /// </summary>
    private static bool TakeRestartFlag()
    {
        try
        {
            if (!File.Exists(RestartFlagFile))
            {
                return false;
            }
            File.Delete(RestartFlagFile);
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Un drapeau qu'on ne peut pas effacer relancerait le serveur en boucle : on ne relance pas.
            return false;
        }
    }

    /// <summary>Relance le serveur avec les réglages de son dernier démarrage, une fois son port libéré.</summary>
    private async Task RelaunchAsync()
    {
        if (_lastStart is not { } last)
        {
            return;
        }
        AddLog("[launcher] Redémarrage demandé par le serveur : relance…");
        Changed?.Invoke();
        try
        {
            // Le système peut garder le port quelques instants après la fin du processus.
            for (int i = 0; i < 15 && _relaunchPending && PortBusy(last.Config.HostPort); i++)
            {
                await Task.Delay(1000);
            }
            if (!_relaunchPending)
            {
                // Arrêt demandé, ou launcher fermé, pendant l'attente : surtout ne pas relancer un serveur orphelin.
                AddLog("[launcher] Relance annulée.");
                Changed?.Invoke();
                return;
            }
            _relaunchPending = false;
            await StartAsync(last.Release, last.Config, last.HostName, last.Progress, relaunch: true, CancellationToken.None);
        }
        catch (Exception ex)
        {
            _relaunchPending = false;
            LastError = "Relance du serveur impossible : " + ex.Message;
            Changed?.Invoke();
        }
    }

    /// <summary>Une relance demandée par le serveur (drapeau de redémarrage) attend que son port se libère.</summary>
    public bool Relaunching => _relaunchPending;

    /// <summary>
    /// Renonce à une relance en attente : arrêt demandé par l'hôte, ou fermeture du launcher (un serveur relancé
    /// juste après la fermeture tournerait sans personne pour l'arrêter).
    /// </summary>
    public void CancelRelaunch() => _relaunchPending = false;

    private volatile bool _relaunchPending;

    private static bool PortBusy(int port)
    {
        try
        {
            var l = new TcpListener(System.Net.IPAddress.Any, port);
            l.Start();
            l.Stop();
            return false;
        }
        catch (SocketException)
        {
            return true;
        }
    }

    private static readonly Regex Joined = new(@"\]: (\w{1,16}) joined the game", RegexOptions.Compiled);
    private static readonly Regex Left = new(@"\]: (\w{1,16}) left the game", RegexOptions.Compiled);
    private static readonly Regex Atelier = new(@"\[valcombe\] Atelier de l'h.te ouvert sur le port (\d+)", RegexOptions.Compiled);
    private static readonly Regex AdminReady = new(@"\[valcombe\] API d'administration pr.te sur le port (\d+)", RegexOptions.Compiled);
    private static readonly Regex AdminDown = new(@"API d'administration (indisponible : .*|d.sactiv.e.*)$", RegexOptions.Compiled);

    private void OnLine(string? line, LauncherConfig config, string hostName)
    {
        if (line == null)
        {
            return;
        }
        AddLog(line);
        var m = Atelier.Match(line);
        if (m.Success)
        {
            AtelierPort = int.Parse(m.Groups[1].Value);
        }
        if ((m = AdminReady.Match(line)).Success)
        {
            AdminPort = int.Parse(m.Groups[1].Value);
        }
        else if ((m = AdminDown.Match(line)).Success)
        {
            AdminProblem = m.Groups[1].Value.Trim();
        }
        if ((m = Joined.Match(line)).Success)
        {
            lock (_gate)
            {
                if (!_players.Contains(m.Groups[1].Value))
                {
                    _players.Add(m.Groups[1].Value);
                }
            }
        }
        else if ((m = Left.Match(line)).Success)
        {
            lock (_gate)
            {
                _players.Remove(m.Groups[1].Value);
            }
        }
        if (line.Contains("That player does not exist", StringComparison.Ordinal))
        {
            // Un pseudo donné au serveur (hôte ou invité) n'existe pas chez Minecraft : sans cela, il ne pourrait pas entrer.
            Notice = "Un pseudo n'existe pas chez Minecraft : vérifiez l'orthographe de votre pseudo et de ceux de vos invités.";
        }
        if (line.Contains("FAILED TO BIND TO PORT", StringComparison.OrdinalIgnoreCase))
        {
            LastError = $"Le port {Port} est déjà utilisé sur ce PC.";
        }
        if (Current == State.Starting && line.Contains("Done (") && line.Contains("For help"))
        {
            // Le serveur est prêt : l'hôte devient opérateur, et les invités sont inscrits sur la liste.
            if (hostName.Length > 0)
            {
                Send("op " + hostName);
                if (config.HostGuestsOnly)
                {
                    Send("whitelist add " + hostName);
                }
            }
            if (config.HostGuestsOnly)
            {
                foreach (string guest in config.HostGuests)
                {
                    Send("whitelist add " + guest);
                }
            }
            RunningSince = DateTime.UtcNow;
            Set(State.Running);
            return;
        }
        Changed?.Invoke();
    }

    /// <summary>Envoie une commande à la console du serveur (sans « / »).</summary>
    public void Send(string command)
    {
        var p = _process;
        if (p == null || p.HasExited)
        {
            return;
        }
        try
        {
            p.StandardInput.WriteLine(command);
            p.StandardInput.Flush();
        }
        catch (IOException)
        {
        }
    }

    /// <summary>Arrête proprement le serveur (le monde est enregistré) ; s'il ne répond pas, il est fermé de force.</summary>
    public async Task StopAsync()
    {
        CancelRelaunch();
        var p = _process;
        if (p == null || p.HasExited)
        {
            Set(State.Stopped);
            return;
        }
        Set(State.Stopping);
        Send("stop");
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(40));
        try
        {
            await p.WaitForExitAsync(cts.Token);
        }
        catch (OperationCanceledException)
        {
            try
            {
                p.Kill(entireProcessTree: true);
            }
            catch (InvalidOperationException)
            {
            }
        }
    }

    /// <summary>Arrêt immédiat à la fermeture du launcher : on laisse au serveur le temps d'enregistrer le monde.</summary>
    public void StopNow()
    {
        CancelRelaunch();
        var p = _process;
        if (p == null || p.HasExited)
        {
            return;
        }
        Current = State.Stopping;
        Send("stop");
        if (!p.WaitForExit(25000))
        {
            try
            {
                p.Kill(entireProcessTree: true);
            }
            catch (InvalidOperationException)
            {
            }
        }
    }
}
