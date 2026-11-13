using System.IO;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Valcombe.Launcher.Services.Ssh;

/// <summary>Ce que le launcher sait du système d'un VPS (détecté par <see cref="VpsServer.DetectAsync"/>).</summary>
public sealed class VpsSystemInfo
{
    /// <summary>« Linux »...</summary>
    public string Kernel { get; init; } = "";
    /// <summary>« x86_64 », « aarch64 »...</summary>
    public string Arch { get; init; } = "";
    /// <summary>« debian », « ubuntu »...</summary>
    public string DistroId { get; init; } = "";
    /// <summary>Famille (ID_LIKE de /etc/os-release) : « debian », « rhel fedora »...</summary>
    public string DistroFamily { get; init; } = "";
    /// <summary>« Debian GNU/Linux 12 (bookworm) »...</summary>
    public string DistroName { get; init; } = "";
    public string DistroVersion { get; init; } = "";
    public bool Systemd { get; init; }
    public bool Curl { get; init; }
    public bool Wget { get; init; }
    public long MemoryMb { get; init; }
    /// <summary>Place libre là où le serveur sera installé, en Mo (-1 si inconnue).</summary>
    public long FreeDiskMb { get; init; } = -1;
    public RootAccess Root { get; init; }
    /// <summary>Ce qui empêche l'installation (vide si rien).</summary>
    public List<string> Problems { get; } = new();
    /// <summary>Ce qui mérite d'être signalé sans bloquer.</summary>
    public List<string> Warnings { get; } = new();

    public bool CanInstall => Problems.Count == 0;

    /// <summary>« Debian GNU/Linux 12 (bookworm), x86_64, 3,8 Go de mémoire »...</summary>
    public string Summary =>
        $"{(DistroName.Length > 0 ? DistroName : Kernel)}, {Arch}, {MemoryMb / 1024.0:0.0} Go de mémoire"
        + (FreeDiskMb >= 0 ? $", {FreeDiskMb / 1024.0:0.0} Go libres" : "");

    /// <summary>Lit la sortie « clé=valeur » de la commande de détection et juge le système.</summary>
    public static VpsSystemInfo Parse(string output, RootAccess root, VpsProfile profile)
    {
        var v = new Dictionary<string, string>();
        foreach (string line in output.Split('\n'))
        {
            int eq = line.IndexOf('=');
            if (eq > 0)
            {
                v[line[..eq].Trim()] = line[(eq + 1)..].Trim();
            }
        }
        string Get(string k) => v.TryGetValue(k, out var s) ? s : "";
        long Number(string k) => long.TryParse(Get(k), out long n) ? n : -1;
        var info = new VpsSystemInfo
        {
            Kernel = Get("noyau"),
            Arch = Get("arch"),
            DistroId = Get("distrib"),
            DistroFamily = Get("distrib_famille"),
            DistroName = Get("distrib_nom"),
            DistroVersion = Get("distrib_version"),
            Systemd = Get("systemd") == "oui",
            Curl = Get("curl") == "oui",
            Wget = Get("wget") == "oui",
            MemoryMb = Math.Max(0, Number("memoire_ko") / 1024),
            FreeDiskMb = Number("disque_ko") < 0 ? -1 : Number("disque_ko") / 1024,
            Root = root,
        };
        if (info.Kernel != "Linux")
        {
            info.Problems.Add($"Ce serveur n'est pas sous Linux ({(info.Kernel.Length > 0 ? info.Kernel : "système inconnu")}).");
        }
        if (VpsPlan.NormalizeArch(info.Arch) is not ("x86_64" or "aarch64"))
        {
            info.Problems.Add($"Processeur non pris en charge : {info.Arch} (x86_64 ou aarch64 attendus).");
        }
        if (!info.Systemd)
        {
            info.Problems.Add("Ce serveur n'utilise pas systemd : le service de Valcombe ne peut pas y être installé.");
        }
        if (Get("bash") != "oui")
        {
            info.Problems.Add("bash est absent du serveur.");
        }
        if (root == RootAccess.None)
        {
            info.Problems.Add($"Le compte {profile.User} n'a pas les droits d'administration (root ou sudo).");
        }
        bool debianLike = info.DistroId is "debian" or "ubuntu" || info.DistroFamily.Contains("debian");
        if (!debianLike && info.Kernel == "Linux")
        {
            info.Warnings.Add($"Systèmes prévus : Debian et Ubuntu. {(info.DistroName.Length > 0 ? info.DistroName : "Celui-ci")} devrait convenir, sans garantie.");
        }
        if (!info.Curl && !info.Wget)
        {
            info.Warnings.Add("Ni curl ni wget : l'installation installera curl.");
        }
        if (info.MemoryMb > 0 && info.MemoryMb < profile.MemoryMb + 512)
        {
            info.Warnings.Add($"Mémoire du serveur : {info.MemoryMb} Mo, pour {profile.MemoryMb} Mo demandés à Java. "
                + "Baissez la mémoire du serveur dans le launcher, ou prenez une offre plus grande.");
        }
        if (info.FreeDiskMb is >= 0 and < 2048)
        {
            info.Warnings.Add($"Peu de place libre sur le disque : {info.FreeDiskMb} Mo (2 Go conseillés au moins).");
        }
        return info;
    }
}

/// <summary>État du service sur le VPS.</summary>
public sealed class VpsServiceStatus
{
    /// <summary>Le service existe (le serveur est installé).</summary>
    public bool Installed { get; init; }
    /// <summary>« active », « inactive », « failed », « activating », « deactivating »...</summary>
    public string ActiveState { get; init; } = "";
    public string SubState { get; init; } = "";
    public bool Active => ActiveState is "active" or "activating" or "reloading";
    public bool Failed => ActiveState == "failed";
    /// <summary>Activé au démarrage de la machine.</summary>
    public bool Enabled { get; init; }
    public DateTime? Since { get; init; }
    public int Restarts { get; init; }
    /// <summary>Le port du jeu écoute : Minecraft a fini de démarrer.</summary>
    public bool Listening { get; init; }
    /// <summary>Version de Valcombe installée (valcombe-installe.txt), ou chaîne vide.</summary>
    public string Version { get; init; } = "";
    public string Minecraft { get; init; } = "";

    /// <summary>« En marche (joignable) », « Arrêté »...</summary>
    public string Text => !Installed ? "Pas installé"
        : Failed ? "En panne"
        : ActiveState == "activating" ? "Démarrage"
        : ActiveState == "deactivating" ? "Arrêt en cours"
        : Active ? (Listening ? "En marche" : "Démarrage de Minecraft")
        : "Arrêté";
}

/// <summary>Résultat d'une installation ou d'une mise à jour.</summary>
public sealed class VpsInstallResult
{
    public bool Changed { get; init; }
    public bool Restarted { get; set; }
    public string Version { get; init; } = "";
    public string Summary { get; init; } = "";
    public IReadOnlyList<string> Log { get; init; } = Array.Empty<string>();
}

/// <summary>
/// Le serveur de Valcombe sur un VPS, piloté par SSH : détecter le système, installer et mettre à jour (Java,
/// Fabric, mods de la dernière version publiée, par install-valcombe.sh envoyé en SFTP), démarrer, arrêter et
/// redémarrer le service systemd, lire son journal, et ouvrir un tunnel vers l'API d'administration
/// (<see cref="OpenAdminTunnelAsync"/>) pour tout le reste. Sans interface : la page Serveur l'appelle.
/// </summary>
public sealed class VpsServer : IDisposable
{
    public const string ScriptName = "install-valcombe.sh";
    /// <summary>Port de l'API quand le mod n'en a pas d'autre (Community.tabletPort, côté mod).</summary>
    public const int DefaultAdminPort = 25570;
    /// <summary>Port par défaut de Simple Voice Chat.</summary>
    public const int DefaultVoicePort = 24454;

    private readonly IRemoteShell _shell;

    public VpsServer(VpsProfile profile, IRemoteShell shell)
    {
        profile.Validate();
        Profile = profile;
        _shell = shell;
    }

    public VpsProfile Profile { get; }

    public IRemoteShell Shell => _shell;

    /// <summary>
    /// Se connecte au VPS du profil. Premier contact : <see cref="SshHostKeyUnknownException"/>, à faire confirmer,
    /// puis rappeler avec l'empreinte confirmée. Voir <see cref="SshRemote.ConnectAsync"/>.
    /// </summary>
    public static async Task<VpsServer> ConnectAsync(VpsProfile profile, string? acceptFingerprint = null,
        CancellationToken ct = default) =>
        new(profile, await SshRemote.ConnectAsync(profile, acceptFingerprint, null, ct));

    /// <summary>Un chemin du serveur, sous le dossier racine de l'installation à blanc s'il y en a une.</summary>
    private string OnServer(string path) => (_shell.DryRunRoot ?? "") + path;

    private string Dir => OnServer(Profile.InstallDir);

    private static string Q(string s) => Ssh.Shell.Quote(s);

    // ------------------------------------------------------------------
    // Système
    // ------------------------------------------------------------------
    /// <summary>Le système du VPS : Linux, processeur, distribution, systemd, mémoire, disque, droits.</summary>
    public async Task<VpsSystemInfo> DetectAsync(CancellationToken ct = default)
    {
        // os-release est lu dans un sous-shell : ses variables ne débordent pas sur le reste.
        string script = string.Join('\n',
            "echo \"noyau=$(uname -s)\"",
            "echo \"arch=$(uname -m)\"",
            $"( . {Q(OnServer("/etc/os-release"))} 2>/dev/null; echo \"distrib=${{ID:-}}\"; echo \"distrib_nom=${{PRETTY_NAME:-}}\"; "
                + "echo \"distrib_version=${VERSION_ID:-}\"; echo \"distrib_famille=${ID_LIKE:-}\" )",
            $"if [ -d {Q(OnServer("/run/systemd/system"))} ]; then echo systemd=oui; else echo systemd=non; fi",
            "for o in curl wget bash; do if command -v $o >/dev/null 2>&1; then echo $o=oui; else echo $o=non; fi; done",
            "echo \"memoire_ko=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null)\"",
            $"p={Q(Dir)}; while [ ! -d \"$p\" ] && [ \"$p\" != / ]; do p=$(dirname \"$p\"); done",
            "echo \"disque_ko=$(df -Pk \"$p\" 2>/dev/null | awk 'NR==2 {print $4}')\"");
        var r = await _shell.RunAsync(script, ct: ct);
        return VpsSystemInfo.Parse(r.Output, _shell.Root, Profile);
    }

    // ------------------------------------------------------------------
    // Installation et mise à jour
    // ------------------------------------------------------------------
    /// <summary>Le script d'installation, tel qu'il est dans le dépôt (ressource du launcher), en fins de ligne Unix.</summary>
    public static string ScriptText()
    {
        using var stream = typeof(VpsServer).Assembly.GetManifestResourceStream(ScriptName)
            ?? throw new InvalidOperationException("Script d'installation absent du launcher.");
        using var reader = new StreamReader(stream, Encoding.UTF8);
        return reader.ReadToEnd().Replace("\r\n", "\n");
    }

    /// <summary>
    /// Contenu d'un fichier du dossier du serveur, ou null s'il n'existe pas. Il est lu avec le compte du serveur,
    /// jamais en root : ce dossier lui appartient, et un lien symbolique qu'il y aurait posé ferait sinon lire
    /// (puis recopier dans ses propres réglages, pour server.properties) un fichier réservé à root.
    /// </summary>
    private async Task<string?> ReadServerFileAsync(string path, CancellationToken ct)
    {
        string read = $"if [ -f {Q(path)} ]; then cat -- {Q(path)}; else exit 3; fi";
        string account = Q(Profile.ServiceUser);
        // Sans le compte (premier passage), rien dans ce dossier ne vient de lui : root peut lire.
        string command = _shell.DryRunRoot != null ? read
            : $"if id -u {account} >/dev/null 2>&1; then exec runuser -u {account} -- sh -c {Q(read)}; else {read}; fi";
        var r = await _shell.RunAsync(command, asRoot: true, ct: ct);
        return r.ExitCode == 3 ? null : r.Ok ? r.Output : throw new InvalidOperationException($"Lecture de {path} impossible : {r.Message}");
    }

    private static string[]? Lines(string? text) =>
        text?.Replace("\r\n", "\n").Split('\n', StringSplitOptions.RemoveEmptyEntries);

    /// <summary>
    /// Les fichiers de réglages à placer sur le serveur, préparés ici à partir de ceux qui y sont déjà : rien de ce
    /// que l'hôte ou l'API d'administration ont changé n'est perdu, seuls les réglages du launcher sont posés.
    /// </summary>
    public async Task<List<PreparedFile>> PrepareFilesAsync(CancellationToken ct = default)
    {
        var files = new List<PreparedFile>();
        // server.properties : la même fusion que pour le serveur local ; un nouveau serveur loué n'accepte que
        // les joueurs de sa liste blanche (on les ajoute ensuite par l'API d'administration).
        var properties = LocalServer.MergeProperties(Lines(await ReadServerFileAsync($"{Dir}/server.properties", ct)),
            new[] { ("server-port", Profile.MinecraftPort.ToString()) },
            new[] { ("white-list", "true"), ("enforce-whitelist", "true") });
        files.Add(new PreparedFile("server.properties", "server.properties", "640", string.Join('\n', properties) + "\n"));

        // Le contrat de Minecraft, accepté par l'hôte dans le launcher.
        files.Add(new PreparedFile("eula.txt", "eula.txt", "644",
            "# Contrat accepté par l'hôte dans le launcher Valcombe (https://aka.ms/MinecraftEULA)\neula=true\n"));

        // Port de l'API d'administration (serveur web du mod), seulement s'il est choisi dans le launcher.
        if (Profile.AdminPort > 0)
        {
            string? text = await ReadServerFileAsync($"{Dir}/config/valcombe-communaute.json", ct);
            JsonObject community;
            try
            {
                community = text != null ? JsonNode.Parse(text)?.AsObject() ?? new JsonObject() : new JsonObject();
            }
            catch (System.Text.Json.JsonException)
            {
                throw new InvalidOperationException("config/valcombe-communaute.json est illisible sur le serveur : corrigez-le ou effacez-le.");
            }
            community["tabletPort"] = Profile.AdminPort;
            files.Add(new PreparedFile("config/valcombe-communaute.json", "valcombe-communaute.json", "640",
                community.ToJsonString(new System.Text.Json.JsonSerializerOptions { WriteIndented = true }) + "\n"));
        }

        // Port de Simple Voice Chat : le fichier du mod est retouché s'il existe, créé s'il faut un autre port.
        string voicePath = $"{Dir}/config/voicechat/voicechat-server.properties";
        var voice = Lines(await ReadServerFileAsync(voicePath, ct))?.ToList();
        if (voice != null || Profile.VoicePort != DefaultVoicePort)
        {
            voice ??= new List<string>();
            int at = voice.FindIndex(l => l.StartsWith("port=", StringComparison.Ordinal));
            if (at >= 0)
            {
                voice[at] = $"port={Profile.VoicePort}";
            }
            else
            {
                voice.Add($"port={Profile.VoicePort}");
            }
            files.Add(new PreparedFile("config/voicechat/voicechat-server.properties", "voicechat-server.properties", "640",
                string.Join('\n', voice) + "\n"));
        }
        return files;
    }

    /// <summary>
    /// Installe le serveur, ou le met à jour vers <paramref name="release"/> : compte dédié, Java, Fabric, mods,
    /// réglages, service systemd (activé, pas démarré), pare-feu. Sans effet sur un serveur déjà à jour.
    /// Le serveur en marche n'est pas redémarré (voir <see cref="UpdateAsync"/>).
    /// <paramref name="log"/> reçoit chaque ligne du journal de l'installation dès qu'elle arrive (le parcours du
    /// launcher la montre en direct) ; le journal entier est aussi rendu à la fin.
    /// </summary>
    public async Task<VpsInstallResult> InstallAsync(Release release, IProgress<(string, double)>? progress = null,
        bool download = true, CancellationToken ct = default, IProgress<string>? log = null)
    {
        void Report(string text, double p) => progress?.Report((text, p));
        if (!Profile.EulaAccepted)
        {
            throw new InvalidOperationException("Avant d'installer, lisez et acceptez le contrat de Minecraft (https://aka.ms/MinecraftEULA) pour ce serveur.");
        }
        Report("Serveur loué : examen du système…", 0.01);
        var system = await DetectAsync(ct);
        if (!system.CanInstall)
        {
            throw new InvalidOperationException(string.Join(" ", system.Problems));
        }

        Report("Serveur loué : préparation de l'installation…", 0.03);
        var files = await PrepareFilesAsync(ct);
        var plan = await VpsPlan.BuildAsync(release, Profile, system.Arch, files, ct);

        // Dossier d'envoi temporaire, privé (mktemp -d le crée en 700) ; effacé quoi qu'il arrive.
        var stage = await _shell.RunAsync("mktemp -d /tmp/valcombe-installation.XXXXXX", ct: ct);
        string dir = stage.Output.Trim();
        if (!stage.Ok || !Regex.IsMatch(dir, @"^/[A-Za-z0-9._/-]+$"))
        {
            throw new InvalidOperationException("Impossible de créer un dossier temporaire sur le serveur : " + stage.Message);
        }
        var lines = new List<string>();
        bool changed = false;
        string finished = "";
        string error = "";
        try
        {
            Report("Serveur loué : envoi du script d'installation…", 0.05);
            await _shell.UploadAsync($"{dir}/{ScriptName}", Encoding.UTF8.GetBytes(ScriptText()), ct);
            await _shell.UploadAsync($"{dir}/valcombe-plan.tsv", Encoding.UTF8.GetBytes(plan.Text), ct);
            foreach (var f in files)
            {
                await _shell.UploadAsync($"{dir}/{f.StagedName}", Encoding.UTF8.GetBytes(f.Content.Replace("\r\n", "\n")), ct);
            }

            string command = $"bash {Q($"{dir}/{ScriptName}")} --plan {Q($"{dir}/valcombe-plan.tsv")}";
            if (_shell.DryRunRoot != null)
            {
                command += $" --a-blanc --racine {Q(_shell.DryRunRoot)}";
            }
            if (!download)
            {
                command += " --sans-telechargement";
            }
            var result = await _shell.RunAsync(command, asRoot: true, onLine: line =>
            {
                if (line.StartsWith("@@etape ", StringComparison.Ordinal))
                {
                    var parts = line.Split(' ', 3);
                    if (parts.Length == 3 && int.TryParse(parts[1], out int pct))
                    {
                        Report("Serveur loué : " + parts[2], 0.06 + 0.94 * Math.Clamp(pct, 0, 100) / 100.0);
                    }
                }
                else if (line.StartsWith("@@change ", StringComparison.Ordinal))
                {
                    changed = line.EndsWith("oui", StringComparison.Ordinal);
                }
                else if (line.StartsWith("@@fini ", StringComparison.Ordinal))
                {
                    finished = line[7..];
                }
                else if (line.StartsWith("@@erreur ", StringComparison.Ordinal))
                {
                    error = line[9..];
                }
                else
                {
                    lock (lines)
                    {
                        lines.Add(line);
                    }
                    log?.Report(line);
                }
            }, timeout: TimeSpan.FromMinutes(30), ct: ct);
            if (!result.Ok || finished.Length == 0)
            {
                throw new InvalidOperationException("Installation sur le serveur loué impossible : "
                    + (error.Length > 0 ? error : result.Message.Length > 0 ? result.Message : $"code {result.ExitCode}"));
            }
        }
        finally
        {
            try
            {
                await _shell.RunAsync($"rm -rf {Q(dir)}", asRoot: true, ct: CancellationToken.None);
            }
            catch (Exception)
            {
                // Le dossier temporaire sera effacé par le système ; l'erreur d'installation, elle, compte.
            }
        }
        Report("Serveur loué : installé.", 1);
        return new VpsInstallResult { Changed = changed, Version = release.Tag, Summary = finished, Log = lines };
    }

    /// <summary>
    /// Met le serveur à jour vers <paramref name="release"/>, puis le redémarre s'il tournait et que quelque chose a
    /// changé (une partie en cours est enregistrée et reprend après le redémarrage).
    /// </summary>
    public async Task<VpsInstallResult> UpdateAsync(Release release, IProgress<(string, double)>? progress = null,
        CancellationToken ct = default)
    {
        var before = await StatusAsync(ct);
        var result = await InstallAsync(release, progress, true, ct: ct);
        if (result.Changed && before.Active)
        {
            progress?.Report(("Serveur loué : redémarrage pour la nouvelle version…", 1));
            await RestartAsync(ct);
            result.Restarted = true;
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Service
    // ------------------------------------------------------------------
    private string Unit => Profile.ServiceName + ".service";

    /// <summary>État du service, version installée, et si le port du jeu écoute.</summary>
    public async Task<VpsServiceStatus> StatusAsync(CancellationToken ct = default)
    {
        string script = string.Join('\n',
            $"systemctl show {Q(Unit)} --no-pager --property=LoadState,ActiveState,SubState,UnitFileState,ExecMainStartTimestamp,NRestarts",
            "echo '@@installe'",
            $"cat {Q(Dir + "/valcombe-installe.txt")} 2>/dev/null || true",
            "echo '@@ecoute'",
            // Une ligne LISTEN pour le port du jeu : Minecraft a fini de démarrer (l'en-tête de ss ne contient pas LISTEN).
            $"if ss -ltn 'sport = :{Profile.MinecraftPort}' 2>/dev/null | grep -q LISTEN; then echo ecoute; fi");
        var r = await _shell.RunAsync(script, asRoot: true, ct: ct);
        var values = new Dictionary<string, string>();
        string section = "";
        bool listening = false;
        foreach (string line in r.Output.Split('\n'))
        {
            if (line.StartsWith("@@", StringComparison.Ordinal))
            {
                section = line;
                continue;
            }
            if (section == "@@ecoute")
            {
                listening |= line.Trim().Length > 0;
                continue;
            }
            int eq = line.IndexOf('=');
            if (eq > 0)
            {
                values[(section == "@@installe" ? "installe." : "") + line[..eq]] = line[(eq + 1)..].Trim();
            }
        }
        string Get(string k) => values.TryGetValue(k, out var s) ? s : "";
        DateTime? since = null;
        // « Fri 2026-11-13 09:12:44 UTC » : on garde la date et l'heure.
        var m = Regex.Match(Get("ExecMainStartTimestamp"), @"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})");
        if (m.Success && DateTime.TryParse(m.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture,
                System.Globalization.DateTimeStyles.None, out var at))
        {
            since = at;
        }
        return new VpsServiceStatus
        {
            Installed = Get("LoadState") == "loaded",
            ActiveState = Get("ActiveState"),
            SubState = Get("SubState"),
            Enabled = Get("UnitFileState") == "enabled",
            Since = since,
            Restarts = int.TryParse(Get("NRestarts"), out int n) ? n : 0,
            Listening = listening,
            Version = Get("installe.version"),
            Minecraft = Get("installe.minecraft"),
        };
    }

    private async Task SystemctlAsync(string verb, string doing, TimeSpan timeout, CancellationToken ct)
    {
        var status = await StatusAsync(ct);
        if (!status.Installed)
        {
            throw new InvalidOperationException("Le serveur n'est pas installé sur ce VPS : installez-le d'abord.");
        }
        var r = await _shell.RunAsync($"systemctl {verb} {Q(Unit)}", asRoot: true, timeout: timeout, ct: ct);
        if (!r.Ok)
        {
            throw new InvalidOperationException($"Impossible de {doing} le serveur : {r.Message}. Son journal dit pourquoi.");
        }
    }

    /// <summary>Démarre le service (Minecraft met ensuite une à deux minutes à être joignable : voir Listening).</summary>
    public Task StartAsync(CancellationToken ct = default) => SystemctlAsync("start", "démarrer", TimeSpan.FromMinutes(1), ct);

    /// <summary>Arrêt propre : Java enregistre les mondes et la partie en cours (jusqu'à deux minutes).</summary>
    public Task StopAsync(CancellationToken ct = default) => SystemctlAsync("stop", "arrêter", TimeSpan.FromMinutes(3), ct);

    /// <summary>Arrêt propre puis démarrage, par systemd (marche aussi quand l'API ne répond plus).</summary>
    public Task RestartAsync(CancellationToken ct = default) => SystemctlAsync("restart", "redémarrer", TimeSpan.FromMinutes(4), ct);

    // ------------------------------------------------------------------
    // Journal
    // ------------------------------------------------------------------
    /// <summary>Les dernières lignes du journal du service (sortie du serveur, et ce que dit systemd).</summary>
    public async Task<IReadOnlyList<string>> ReadLogAsync(int lines = 200, CancellationToken ct = default)
    {
        lines = Math.Clamp(lines, 1, 5000);
        var r = await _shell.RunAsync($"journalctl -u {Q(Unit)} -n {lines} --no-pager -o short-iso", asRoot: true, ct: ct);
        if (!r.Ok)
        {
            throw new InvalidOperationException("Journal du serveur illisible : " + r.Message);
        }
        return r.Output.Split('\n').Where(l => l.Length > 0).ToList();
    }

    /// <summary>
    /// Suit le journal en direct (les <paramref name="backlog"/> dernières lignes, puis chaque nouvelle) jusqu'à
    /// l'annulation de <paramref name="ct"/>. Pour la console d'un serveur en marche, l'API d'administration
    /// (/admin/console) est plus riche ; ceci sert aussi quand le serveur ne démarre pas.
    /// </summary>
    public async Task FollowLogAsync(Action<string> onLine, int backlog = 50, CancellationToken ct = default)
    {
        try
        {
            await _shell.RunAsync($"journalctl -u {Q(Unit)} -f -n {Math.Clamp(backlog, 0, 1000)} --no-pager -o short-iso",
                asRoot: true, onLine: onLine, timeout: Timeout.InfiniteTimeSpan, ct: ct);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            // Fin du suivi demandée.
        }
    }

    // ------------------------------------------------------------------
    // API d'administration
    // ------------------------------------------------------------------
    /// <summary>La clé d'administration (config/valcombe/admin.json, lisible par root et le compte du serveur seulement).</summary>
    public async Task<string> ReadAdminKeyAsync(CancellationToken ct = default)
    {
        string? text = await ReadServerFileAsync($"{Dir}/config/valcombe/admin.json", ct)
            ?? throw new InvalidOperationException("La clé d'administration n'existe pas encore : démarrez le serveur une première fois.");
        JsonNode? json;
        try
        {
            json = JsonNode.Parse(text);
        }
        catch (System.Text.Json.JsonException)
        {
            throw new InvalidOperationException("config/valcombe/admin.json est illisible sur le serveur : redémarrez-le pour qu'il en refasse un.");
        }
        if (json?["enabled"]?.GetValue<bool>() == false)
        {
            throw new InvalidOperationException("L'API d'administration est coupée sur ce serveur (\"enabled\": false dans config/valcombe/admin.json).");
        }
        string key = json?["key"]?.GetValue<string>() ?? "";
        if (!Regex.IsMatch(key, "^[A-Za-z0-9_-]{32,128}$"))
        {
            throw new InvalidOperationException("Clé d'administration invalide sur le serveur : redémarrez-le pour qu'il en tire une neuve.");
        }
        return key;
    }

    /// <summary>Port de l'API sur le VPS : celui du mod (config/valcombe-communaute.json), sinon celui du profil, sinon 25570.</summary>
    public async Task<int> ReadAdminPortAsync(CancellationToken ct = default)
    {
        string? text = await ReadServerFileAsync($"{Dir}/config/valcombe-communaute.json", ct);
        if (text != null)
        {
            try
            {
                if (JsonNode.Parse(text)?["tabletPort"]?.GetValue<int>() is int port and >= 1024 and <= 65535)
                {
                    return port;
                }
            }
            catch (Exception ex) when (ex is System.Text.Json.JsonException or InvalidOperationException or FormatException)
            {
                // Fichier abîmé : le mod reprendra lui aussi son port par défaut.
            }
        }
        return Profile.AdminPort > 0 ? Profile.AdminPort : DefaultAdminPort;
    }

    /// <summary>
    /// Ouvre un tunnel SSH vers l'API d'administration du serveur : la page Serveur s'en sert pour tout le reste
    /// (état, joueurs, console, commandes, réglages). Le serveur doit tourner. À fermer (Dispose) après usage.
    /// </summary>
    public async Task<IAdminTunnel> OpenAdminTunnelAsync(CancellationToken ct = default)
    {
        string key = await ReadAdminKeyAsync(ct);
        int port = await ReadAdminPortAsync(ct);
        return await _shell.OpenTunnelAsync(port, key, ct);
    }

    public void Dispose() => _shell.Dispose();
}
