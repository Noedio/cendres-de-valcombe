using System.IO;
using System.Text.RegularExpressions;
using Valcombe.Launcher.Services.Admin;

namespace Valcombe.Launcher.Services.Ssh;

/// <summary>
/// Un serveur loué vu par le parcours d'enregistrement, une fois la connexion SSH ouverte : ce qu'il faut pour
/// examiner le système, installer Valcombe, démarrer le service et joindre l'API. Réalisé par SSH
/// (<see cref="SshVpsConnector"/>) et, pour les captures et les vérifications, par un serveur fictif
/// (<see cref="DemoVpsConnector"/>).
/// </summary>
public interface IVpsSession : IDisposable
{
    /// <summary>« debian@vps.exemple.fr ».</summary>
    string Label { get; }

    /// <summary>Droits d'administration du compte, établis à la connexion.</summary>
    RootAccess Root { get; }

    Task<VpsSystemInfo> DetectAsync(CancellationToken ct);

    Task<VpsServiceStatus> StatusAsync(CancellationToken ct);

    Task<VpsInstallResult> InstallAsync(Release release, IProgress<(string, double)> progress, IProgress<string> log, CancellationToken ct);

    Task StartAsync(CancellationToken ct);

    /// <summary>Un accès à l'API d'administration par le tunnel SSH (le serveur doit tourner).</summary>
    IServerConnection OpenAdmin();
}

/// <summary>Ouvre la session SSH d'un profil ; au premier contact, lève <see cref="SshHostKeyUnknownException"/>.</summary>
public interface IVpsConnector
{
    Task<IVpsSession> ConnectAsync(VpsProfile profile, string? acceptFingerprint, CancellationToken ct);

    /// <summary>Les empreintes d'hôte confirmées (ValcombeData/ssh-hotes-connus.json, ou un fichier à part pour les essais).</summary>
    KnownHosts KnownHosts { get; }
}

/// <summary>Le vrai serveur loué, par SSH (<see cref="VpsServer"/>).</summary>
public sealed class SshVpsConnector : IVpsConnector
{
    public KnownHosts KnownHosts { get; } = new();

    public async Task<IVpsSession> ConnectAsync(VpsProfile profile, string? acceptFingerprint, CancellationToken ct)
    {
        // Hors du fil de l'interface : la clé privée se déchiffre avant la première attente.
        var server = await Task.Run(() => VpsServer.ConnectAsync(profile, acceptFingerprint, ct), ct);
        return new Session(server);
    }

    private sealed class Session : IVpsSession
    {
        private readonly VpsServer _server;

        public Session(VpsServer server)
        {
            _server = server;
        }

        public string Label => _server.Shell.Label;

        public RootAccess Root => _server.Shell.Root;

        public Task<VpsSystemInfo> DetectAsync(CancellationToken ct) => _server.DetectAsync(ct);

        public Task<VpsServiceStatus> StatusAsync(CancellationToken ct) => _server.StatusAsync(ct);

        public Task<VpsInstallResult> InstallAsync(Release release, IProgress<(string, double)> progress, IProgress<string> log, CancellationToken ct) =>
            _server.InstallAsync(release, progress, true, ct, log);

        public Task StartAsync(CancellationToken ct) => _server.StartAsync(ct);

        public IServerConnection OpenAdmin() => new SshConnection(_server.Profile);

        public void Dispose() => _server.Dispose();
    }
}

/// <summary>Ce qu'a donné une tentative de connexion du parcours.</summary>
public enum VpsConnectResult
{
    Connected,
    /// <summary>Premier contact : l'empreinte est à confirmer (<see cref="VpsSetup.PendingKey"/>).</summary>
    UnknownKey,
    /// <summary>La clé du serveur a changé : refus net (<see cref="VpsSetup.PendingKey"/>).</summary>
    ChangedKey,
    /// <summary>Adresse, compte, mot de passe ou clé : <see cref="VpsSetup.Error"/> dit quoi.</summary>
    Failed,
}

/// <summary>
/// Le parcours d'enregistrement d'un serveur loué, sans interface : la page Serveur (MainWindow.Vps.cs) n'en
/// montre que les étapes, et les vérifications (<c>--verifier-vps</c>) le jouent de bout en bout avec un serveur
/// fictif. Adresse et compte, connexion avec confirmation de l'empreinte, examen du système, installation (contrat
/// de Minecraft accepté), démarrage et attente de l'API, premier opérateur et liste blanche, puis la fiche.
/// En modification, les secrets laissés vides sont gardés.
/// </summary>
public sealed class VpsSetup : IDisposable
{
    private readonly IVpsConnector _connector;

    /// <param name="original">La fiche modifiée, ou null pour un nouveau serveur.</param>
    public VpsSetup(IVpsConnector connector, VpsProfile? original = null)
    {
        _connector = connector;
        Original = original;
        Profile = original != null ? Clone(original) : new VpsProfile();
    }

    /// <summary>Le profil en cours : il ne touche au fichier des serveurs loués qu'à l'enregistrement (<see cref="Save"/>).</summary>
    public VpsProfile Profile { get; }

    public VpsProfile? Original { get; }

    public IVpsSession? Session { get; private set; }

    public VpsSystemInfo? SystemInfo { get; private set; }

    public VpsServiceStatus? Status { get; private set; }

    /// <summary>L'empreinte à confirmer ou refusée, après <see cref="ConnectAsync"/>.</summary>
    public SshHostKeyException? PendingKey { get; private set; }

    public string Error { get; private set; } = "";

    private static VpsProfile Clone(VpsProfile p) =>
        System.Text.Json.JsonSerializer.Deserialize<VpsProfile>(System.Text.Json.JsonSerializer.Serialize(p))!;

    // ------------------------------------------------------------------
    // Étape 1 : adresse
    // ------------------------------------------------------------------
    /// <summary>
    /// Nom, adresse et port SSH. L'adresse est nettoyée de ce qu'on colle souvent avec (« ssh://root@... »,
    /// « vps.exemple.fr:2222 ») ; un compte collé devant l'adresse est rendu dans <paramref name="user"/>.
    /// </summary>
    public List<string> SetAddress(string name, string host, string port, out string? user)
    {
        user = null;
        var problems = new List<string>();
        host = host.Trim();
        foreach (string prefix in new[] { "ssh://", "sftp://" })
        {
            if (host.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            {
                host = host[prefix.Length..];
            }
        }
        host = host.TrimEnd('/');
        int at = host.LastIndexOf('@');
        if (at > 0)
        {
            user = host[..at];
            host = host[(at + 1)..];
        }
        string portText = port.Trim();
        if (!host.StartsWith('[') && host.Count(c => c == ':') == 1)
        {
            // « vps.exemple.fr:2222 » : le port collé à l'adresse.
            portText = host[(host.IndexOf(':') + 1)..];
            host = host[..host.IndexOf(':')];
        }
        if (host.StartsWith('[') && host.Contains(']'))
        {
            host = host[1..host.IndexOf(']')];
        }
        Profile.Name = name.Trim().Length > 0 ? name.Trim() : host;
        Profile.Host = host;
        if (!int.TryParse(portText, out int p) || p is < 1 or > 65535)
        {
            problems.Add("Port SSH : un nombre de 1 à 65535 (22 d'habitude).");
        }
        else
        {
            Profile.SshPort = p;
        }
        if (Profile.Name.Length > 40)
        {
            problems.Add("Nom : 40 caractères au plus.");
        }
        if (host.Length == 0)
        {
            problems.Add("Indiquez l'adresse du serveur : un nom (vps.exemple.fr) ou une adresse IP, telle que votre hébergeur la donne.");
        }
        else if (!Regex.IsMatch(host, @"^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$|^[0-9A-Fa-f:.]{2,45}$"))
        {
            problems.Add($"Adresse invalide : « {host} ». Un nom (vps.exemple.fr) ou une adresse IP, sans espace ni chemin.");
        }
        return problems;
    }

    // ------------------------------------------------------------------
    // Étape 2 : compte
    // ------------------------------------------------------------------
    /// <summary>
    /// Compte et secrets (chiffrés aussitôt avec DPAPI). En modification, un champ secret laissé vide garde la
    /// valeur enregistrée. Une clé privée est lue et vérifiée avec sa phrase de passe (hors du fil de l'interface :
    /// une phrase de passe peut demander une seconde de calcul).
    /// </summary>
    public async Task<List<string>> SetAccountAsync(string user, SshAuthKind auth, string password, string? keyFile, string passphrase,
        string sudoPassword)
    {
        var problems = new List<string>();
        user = user.Trim();
        if (!Regex.IsMatch(user, @"^[A-Za-z_][A-Za-z0-9_.-]{0,31}$"))
        {
            problems.Add("Compte SSH invalide : par exemple root, debian ou ubuntu (lettres, chiffres, « _ », « . » et « - »).");
        }
        Profile.User = user;
        bool sameAuth = Original != null && Original.Auth == auth;
        Profile.Auth = auth;
        if (auth == SshAuthKind.Password)
        {
            if (password.Length > 0)
            {
                Profile.SetPassword(password);
            }
            else if (!(sameAuth && Original!.PasswordProtected.Length > 0))
            {
                problems.Add("Indiquez le mot de passe du compte.");
            }
            Profile.PrivateKeyProtected = "";
            Profile.PassphraseProtected = "";
        }
        else
        {
            if (!string.IsNullOrWhiteSpace(keyFile))
            {
                try
                {
                    string text = await File.ReadAllTextAsync(keyFile);
                    await Task.Run(() => Profile.SetPrivateKey(text, passphrase));
                }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
                {
                    problems.Add($"Clé privée illisible : {ex.Message}");
                }
                catch (InvalidOperationException ex)
                {
                    problems.Add(ex.Message);
                }
            }
            else if (sameAuth && Original!.PrivateKeyProtected.Length > 0)
            {
                if (passphrase.Length > 0)
                {
                    // Nouvelle phrase de passe pour la même clé : vérifiée avant d'être gardée.
                    try
                    {
                        string text = Dpapi.UnprotectText(Original.PrivateKeyProtected, "valcombe-ssh");
                        await Task.Run(() => Profile.SetPrivateKey(text, passphrase));
                    }
                    catch (InvalidOperationException ex)
                    {
                        problems.Add(ex.Message);
                    }
                }
            }
            else
            {
                problems.Add("Choisissez le fichier de la clé privée (id_ed25519, id_rsa, ou .ppk de PuTTY).");
            }
            Profile.PasswordProtected = "";
        }
        if (sudoPassword.Length > 0)
        {
            Profile.SetSudoPassword(sudoPassword);
        }
        return problems;
    }

    // ------------------------------------------------------------------
    // Étape 3 : connexion et empreinte
    // ------------------------------------------------------------------
    /// <summary>
    /// Se connecte. Au premier contact, rien n'est accepté sans <paramref name="acceptFingerprint"/> : le résultat
    /// est <see cref="VpsConnectResult.UnknownKey"/> et <see cref="PendingKey"/> porte l'empreinte à montrer.
    /// Une clé changée donne <see cref="VpsConnectResult.ChangedKey"/>, quoi qu'on passe.
    /// </summary>
    public async Task<VpsConnectResult> ConnectAsync(string? acceptFingerprint, CancellationToken ct)
    {
        Error = "";
        PendingKey = null;
        Session?.Dispose();
        Session = null;
        var problems = Profile.Problems();
        if (problems.Count > 0)
        {
            Error = string.Join(" ", problems);
            return VpsConnectResult.Failed;
        }
        try
        {
            Session = await _connector.ConnectAsync(Profile, acceptFingerprint, ct);
            return VpsConnectResult.Connected;
        }
        catch (SshHostKeyUnknownException ex)
        {
            PendingKey = ex;
            return VpsConnectResult.UnknownKey;
        }
        catch (SshHostKeyChangedException ex)
        {
            PendingKey = ex;
            Error = ex.Message;
            return VpsConnectResult.ChangedKey;
        }
        catch (Exception ex) when (ex is InvalidOperationException or TimeoutException or Renci.SshNet.Common.SshException
                                       or System.Net.Sockets.SocketException)
        {
            Error = ex.Message;
            return VpsConnectResult.Failed;
        }
    }

    /// <summary>L'empreinte retenue pour cette adresse, si elle est déjà connue (rien à confirmer).</summary>
    public KnownHost? KnownKey => _connector.KnownHosts.Find(Profile.Host, Profile.SshPort);

    /// <summary>Oublie l'ancienne empreinte (serveur réinstallé) : la prochaine connexion redemandera une confirmation.</summary>
    public void ForgetKey() => _connector.KnownHosts.Forget(Profile.Host, Profile.SshPort);

    // ------------------------------------------------------------------
    // Étape 4 : système
    // ------------------------------------------------------------------
    /// <summary>Examine le système et l'état du service (installé ? quelle version ? en marche ?).</summary>
    public async Task ExamineAsync(CancellationToken ct)
    {
        var session = Session ?? throw new InvalidOperationException("Pas de connexion au serveur.");
        SystemInfo = await session.DetectAsync(ct);
        Status = await session.StatusAsync(ct);
    }

    /// <summary>
    /// Pourquoi le compte n'a pas les droits d'administration, en clair : root, sudo sans mot de passe, ou sudo avec
    /// celui donné ; null s'il les a.
    /// </summary>
    public string? RootProblem => Session?.Root != RootAccess.None ? null
        : Profile.Auth == SshAuthKind.PrivateKey && Profile.SudoPasswordProtected.Length == 0
            ? $"Le compte {Profile.User} se connecte par clé, mais sudo lui demande un mot de passe : indiquez son mot de passe sudo, ou connectez-vous en root."
            : $"sudo refuse les droits d'administration au compte {Profile.User} (mot de passe sudo faux, ou compte hors du groupe sudo). "
                + "Indiquez le bon mot de passe sudo, ou connectez-vous en root.";

    /// <summary>
    /// Une mémoire pour Java : 4 Go (assez pour une soirée de quinze joueurs, et plus ne ferait qu'allonger les pauses
    /// du ramasse-miettes), moins sur une petite machine, où le système garde toujours 1 Go.
    /// </summary>
    public static int SuggestedMemoryMb(long machineMb) =>
        machineMb <= 0 ? 4096 : (int)Math.Clamp((machineMb - 1024) / 512 * 512, 1024, 4096);

    /// <summary>« v0.14.0 » et « 0.14.0 » sont la même version.</summary>
    public static string Normalize(string version) => version.Trim().TrimStart('v', 'V');

    /// <summary>Valcombe est absent, ou pas à la version publiée : l'étape d'installation s'impose.</summary>
    public bool NeedsInstall(Release? latest) => Status is not { Installed: true }
        || string.IsNullOrEmpty(Status.Version)
        || (latest != null && Normalize(Status.Version) != Normalize(latest.Tag));

    // ------------------------------------------------------------------
    // Étape 5 : installation, démarrage, API
    // ------------------------------------------------------------------
    public async Task<VpsInstallResult> InstallAsync(Release release, IProgress<(string, double)> progress, IProgress<string> log,
        CancellationToken ct)
    {
        var session = Session ?? throw new InvalidOperationException("Pas de connexion au serveur.");
        if (!Profile.EulaAccepted)
        {
            throw new InvalidOperationException("Lisez et acceptez le contrat de Minecraft (EULA) avant d'installer.");
        }
        var result = await session.InstallAsync(release, progress, log, ct);
        Status = await session.StatusAsync(ct);
        return result;
    }

    /// <summary>
    /// Démarre le service s'il le faut, attend que Minecraft écoute (le premier démarrage crée le monde : une à
    /// trois minutes), puis que l'API d'administration réponde par le tunnel. Rend l'accès à l'API, prêt.
    /// </summary>
    public async Task<IServerConnection> StartAndReachAsync(IProgress<string> progress, CancellationToken ct, TimeSpan? limit = null)
    {
        var session = Session ?? throw new InvalidOperationException("Pas de connexion au serveur.");
        var deadline = DateTime.UtcNow + (limit ?? TimeSpan.FromMinutes(6));
        Status = await session.StatusAsync(ct);
        if (!Status.Installed)
        {
            throw new InvalidOperationException("Valcombe n'est pas installé sur ce serveur.");
        }
        if (!Status.Active)
        {
            progress.Report("Démarrage du service valcombe…");
            await session.StartAsync(ct);
        }
        var clock = global::System.Diagnostics.Stopwatch.StartNew();
        while (true)
        {
            Status = await session.StatusAsync(ct);
            if (Status.Listening)
            {
                break;
            }
            if (Status.Failed)
            {
                throw new InvalidOperationException("Le serveur s'est arrêté en démarrant : son journal (journalctl -u valcombe) dit pourquoi.");
            }
            if (DateTime.UtcNow > deadline)
            {
                throw new TimeoutException("Minecraft n'a pas fini de démarrer à temps. Il continue peut-être : la fiche du serveur le dira.");
            }
            progress.Report($"Premier démarrage : Minecraft prépare le monde ({(int)clock.Elapsed.TotalSeconds} s, une à trois minutes la première fois)…");
            await Task.Delay(2000, ct);
        }
        progress.Report("Minecraft est prêt. Connexion à l'API d'administration par le tunnel SSH…");
        string last = "";
        while (true)
        {
            var conn = session.OpenAdmin();
            try
            {
                using var once = CancellationTokenSource.CreateLinkedTokenSource(ct);
                once.CancelAfter(TimeSpan.FromSeconds(20));
                await new AdminClient(conn).GetStateAsync(once.Token);
                return conn;
            }
            catch (AdminException ex) when (!ex.StopsPolling || ex.Status == 404)
            {
                last = ex.Message;
                await conn.DisposeAsync();
                if (ex.Status == 404)
                {
                    throw new InvalidOperationException("Le serveur répond, mais sans API d'administration : " + ex.Message);
                }
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                last = "l'API n'a pas répondu à temps";
                await conn.DisposeAsync();
            }
            catch
            {
                await conn.DisposeAsync();
                throw;
            }
            if (DateTime.UtcNow > deadline)
            {
                throw new TimeoutException("L'API d'administration ne répond pas : " + last);
            }
            progress.Report("L'API d'administration s'ouvre…");
            await Task.Delay(2000, ct);
        }
    }

    // ------------------------------------------------------------------
    // Étape 6 : joueurs
    // ------------------------------------------------------------------
    /// <summary>
    /// Des pseudos séparés par des virgules, des espaces ou des retours à la ligne : ceux qui sont valables (sans
    /// doublon, casse ignorée) et ceux qui ne le sont pas.
    /// </summary>
    public static (List<string> Names, List<string> Bad) ParseNames(string text)
    {
        var names = new List<string>();
        var bad = new List<string>();
        foreach (string raw in Regex.Split(text ?? "", @"[\s,;]+"))
        {
            string n = raw.Trim();
            if (n.Length == 0)
            {
                continue;
            }
            if (!AdminClient.IsPlayerName(n))
            {
                bad.Add(n);
            }
            else if (!names.Contains(n, StringComparer.OrdinalIgnoreCase))
            {
                names.Add(n);
            }
        }
        return (names, bad);
    }

    /// <summary>
    /// La demande de POST /admin/reglages du premier passage : l'opérateur (aussi sur la liste blanche, sinon il ne
    /// pourrait pas entrer), la liste blanche, et son interrupteur.
    /// </summary>
    public static AdminSettingsChange PlayersChange(string op, IEnumerable<string> whitelist, bool whitelistOn)
    {
        var change = new AdminSettingsChange { WhitelistActive = whitelistOn };
        op = op.Trim();
        if (op.Length > 0)
        {
            change.OperatorsAdd.Add(op);
        }
        foreach (string n in (op.Length > 0 ? new[] { op } : Array.Empty<string>()).Concat(whitelist))
        {
            if (!change.WhitelistAdd.Contains(n, StringComparer.OrdinalIgnoreCase))
            {
                change.WhitelistAdd.Add(n);
            }
        }
        return change;
    }

    // ------------------------------------------------------------------
    // Fiche
    // ------------------------------------------------------------------
    /// <summary>
    /// Enregistre le profil (secrets chiffrés) dans <paramref name="profilesFile"/> (ValcombeData/serveurs-loues.json
    /// par défaut) et rend la fiche de la page Serveur, ajoutée ou mise à jour dans <paramref name="fiches"/>.
    /// </summary>
    public RemoteServer Save(List<RemoteServer> fiches, string? profilesFile = null)
    {
        Profile.Validate();
        VpsProfiles.Upsert(Profile, profilesFile);
        var fiche = fiches.FirstOrDefault(f => f.Access == RemoteServer.AccessSsh && f.VpsId == Profile.Id);
        if (fiche == null)
        {
            fiche = new RemoteServer { Access = RemoteServer.AccessSsh, VpsId = Profile.Id };
            fiches.Add(fiche);
        }
        fiche.Name = Profile.Name;
        fiche.Host = Profile.Host;
        fiche.ApiPort = Profile.AdminPort > 0 ? Profile.AdminPort : VpsServer.DefaultAdminPort;
        fiche.ProtectedKey = ""; // la clé est lue sur le serveur à chaque connexion
        return fiche;
    }

    /// <summary>
    /// Retire une fiche de serveur loué : la fiche, son profil et ses secrets ; son empreinte aussi, si aucun autre
    /// serveur enregistré n'a la même adresse (une nouvelle fiche la redemandera).
    /// </summary>
    public static void Remove(RemoteServer fiche, List<RemoteServer> fiches, KnownHosts knownHosts, string? profilesFile = null)
    {
        fiches.RemoveAll(f => f.Id == fiche.Id);
        var profiles = VpsProfiles.Load(profilesFile);
        var profile = profiles.FirstOrDefault(p => p.Id == fiche.VpsId);
        if (profile == null)
        {
            return;
        }
        VpsProfiles.Remove(profile.Id, profilesFile);
        bool shared = profiles.Any(p => p.Id != profile.Id && KnownHosts.Key(p.Host, p.SshPort) == KnownHosts.Key(profile.Host, profile.SshPort));
        if (!shared)
        {
            knownHosts.Forget(profile.Host, profile.SshPort);
        }
    }

    public void Dispose()
    {
        Session?.Dispose();
        Session = null;
    }
}
