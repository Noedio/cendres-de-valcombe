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

    private Process? _process;
    private readonly object _gate = new();
    private readonly List<string> _players = new();
    private readonly LinkedList<string> _log = new();

    public State Current { get; private set; } = State.Stopped;
    /// <summary>Port de l'Atelier annoncé par le mod (0 tant qu'il n'est pas ouvert).</summary>
    public int AtelierPort { get; private set; }
    /// <summary>Lien secret de l'Atelier, donné au mod au démarrage.</summary>
    public string AtelierToken { get; private set; } = "";
    public int Port { get; private set; } = 25565;
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

    private void AddLog(string line)
    {
        lock (_gate)
        {
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
            var installers = JsonNode.Parse(await Http.Client.GetStringAsync("https://meta.fabricmc.net/v2/versions/installer", ct))!.AsArray();
            string installer = (installers.FirstOrDefault(i => i!["stable"]?.GetValue<bool>() == true) ?? installers[0])!["version"]!.GetValue<string>();
            await Http.DownloadAsync(
                $"https://meta.fabricmc.net/v2/versions/loader/{manifest.Minecraft}/{manifest.Loader}/{installer}/server/jar",
                LauncherJar, null, new Progress<double>(p => progress.Report(("Serveur : téléchargement de Fabric…", p))), ct);
            File.WriteAllText(StampFile, stamp);
        }

        // Mods du serveur : tous ceux du manifeste marqués « both » (Fabric API, Simple Voice Chat, WorldEdit,
        // Valcombe...), jamais les mods graphiques des joueurs. Rien n'est écrit ici : un mod ajouté au manifeste
        // par tools/make_modpack.py arrive tout seul. Fantasy est incluse dans le jar de Valcombe.
        var mods = manifest.Mods.Where(m => m.Side.Length > 0 ? m.Side != "client" : ServerModNames.Contains(m.Name)).ToList();
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

    /// <summary>Réglages de départ (monde plat et vide, comptes officiels) ; un fichier existant n'est que retouché.</summary>
    private static void WriteProperties(LauncherConfig config)
    {
        var values = new Dictionary<string, string>();
        var order = new List<string>();
        if (File.Exists(PropertiesFile))
        {
            foreach (string line in File.ReadAllLines(PropertiesFile))
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
        }
        void Put(string k, string v)
        {
            if (!values.ContainsKey(k))
            {
                order.Add(k);
            }
            values[k] = v;
        }
        Put("server-port", config.HostPort.ToString());
        Put("white-list", config.HostGuestsOnly ? "true" : "false");
        Put("enforce-whitelist", config.HostGuestsOnly ? "true" : "false");
        File.WriteAllLines(PropertiesFile, order.Distinct().Select(k => k + "=" + values[k]));
    }

    // ------------------------------------------------------------------
    // Démarrage et arrêt
    // ------------------------------------------------------------------
    /// <summary>
    /// Démarre le serveur. hostName : pseudo Minecraft de l'hôte (il devient opérateur), ou chaîne vide.
    /// Le contrat de Minecraft doit avoir été accepté par l'hôte dans le launcher.
    /// </summary>
    public async Task StartAsync(Release release, LauncherConfig config, string hostName, IProgress<(string, double)> progress,
        CancellationToken ct = default)
    {
        if (Current != State.Stopped)
        {
            return;
        }
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

            lock (_gate)
            {
                _players.Clear();
                _log.Clear();
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
                if (Current != State.Stopping && code != 0 && LastError.Length == 0)
                {
                    LastError = "Le serveur s'est arrêté tout seul : lisez la fin de son journal ci-dessous.";
                }
                _process = null;
                AtelierPort = 0;
                lock (_gate)
                {
                    _players.Clear();
                }
                AddLog($"[launcher] Serveur arrêté (code {code}).");
                Set(State.Stopped);
            };
            p.BeginOutputReadLine();
            p.BeginErrorReadLine();
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
