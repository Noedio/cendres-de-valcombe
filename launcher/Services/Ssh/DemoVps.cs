using System.Security.Cryptography;
using System.Text;
using Valcombe.Launcher.Services.Admin;

namespace Valcombe.Launcher.Services.Ssh;

/// <summary>
/// Un serveur loué fictif, sans réseau, pour les captures du parcours (<c>--capture</c>) et sa vérification
/// (<c>--verifier-vps</c>). Il se comporte comme un VPS Debian neuf : empreinte à confirmer au premier contact,
/// Valcombe absent, installation par étapes avec son journal, démarrage, puis l'API du serveur fictif
/// (<see cref="DemoAdminApi"/>). Quelques adresses et comptes jouent les pannes :
/// <list type="bullet">
/// <item>une adresse qui contient « injoignable » : le serveur ne répond pas ;</item>
/// <item>une adresse qui contient « reinstalle » : la clé du serveur a changé depuis la dernière fois ;</item>
/// <item>le mot de passe « mauvais » : connexion refusée ;</item>
/// <item>le compte « sanssudo » : connecté, mais sans droits d'administration ;</item>
/// <item>une adresse qui contient « installe » : Valcombe y est déjà, à une version plus ancienne.</item>
/// </list>
/// </summary>
public sealed class DemoVpsConnector : IVpsConnector
{
    public DemoVpsConnector(KnownHosts knownHosts)
    {
        KnownHosts = knownHosts;
    }

    public KnownHosts KnownHosts { get; }

    private readonly HashSet<string> _seeded = new();

    /// <summary>Durée de chaque étape de l'installation fictive (les captures l'allongent pour la saisir en route).</summary>
    public TimeSpan StepDelay { get; set; } = TimeSpan.FromMilliseconds(60);

    /// <summary>L'API du dernier serveur fictif ouvert (les vérifications regardent ce qu'il a reçu).</summary>
    public DemoAdminApi? Api { get; private set; }

    /// <summary>L'empreinte « SHA256:... » du serveur fictif à cette adresse (toujours la même pour une adresse).</summary>
    public static string FingerprintOf(string host, int port) =>
        "SHA256:" + Convert.ToBase64String(SHA256.HashData(Encoding.UTF8.GetBytes($"{host.ToLowerInvariant()}:{port}"))).TrimEnd('=');

    public async Task<IVpsSession> ConnectAsync(VpsProfile profile, string? acceptFingerprint, CancellationToken ct)
    {
        await Task.Delay(StepDelay, ct);
        string host = profile.Host.Trim();
        string where = host + (profile.SshPort == 22 ? "" : $" (port {profile.SshPort})");
        if (host.Contains("injoignable", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException($"{where} ne répond pas : vérifiez l'adresse, le port SSH, et que le serveur est allumé.");
        }
        string seen = FingerprintOf(host, profile.SshPort);
        if (host.Contains("reinstalle", StringComparison.OrdinalIgnoreCase) && _seeded.Add(KnownHosts.Key(host, profile.SshPort)))
        {
            // Ce serveur a été « réinstallé » : le launcher connaissait son ancienne clé (une seule fois : oubliée, elle le reste).
            KnownHosts.Trust(host, profile.SshPort, "ssh-ed25519", FingerprintOf("ancienne-cle." + host, profile.SshPort));
        }
        switch (KnownHosts.Check(host, profile.SshPort, seen))
        {
            case KnownHosts.Verdict.Changed:
                throw new SshHostKeyChangedException(host, profile.SshPort, "ssh-ed25519", seen, KnownHosts.Find(host, profile.SshPort)!.Fingerprint);
            case KnownHosts.Verdict.Unknown:
                if (acceptFingerprint == null || KnownHosts.Format(acceptFingerprint.Trim()) != seen)
                {
                    throw new SshHostKeyUnknownException(host, profile.SshPort, "ssh-ed25519", seen);
                }
                KnownHosts.Trust(host, profile.SshPort, "ssh-ed25519", seen);
                break;
        }
        if (profile.Auth == SshAuthKind.Password && profile.GetPassword() == "mauvais")
        {
            throw new InvalidOperationException($"{where} a refusé la connexion de {profile.User} : "
                + "mot de passe incorrect, ou connexion par mot de passe désactivée sur le serveur.");
        }
        var root = profile.User == "root" ? RootAccess.Root
            : profile.User == "sanssudo" ? RootAccess.None
            : profile.SudoPasswordProtected.Length > 0 || profile.Auth == SshAuthKind.Password ? RootAccess.SudoWithPassword
            : RootAccess.SudoWithoutPassword;
        Api = DemoAdminApi.Fresh();
        Api.Halt();
        string installed = host.Contains("installe", StringComparison.OrdinalIgnoreCase) && !host.Contains("reinstalle", StringComparison.OrdinalIgnoreCase)
            ? "v0.13.1" : "";
        return new Session(this, profile, root, Api, installed);
    }

    private sealed class Session : IVpsSession
    {
        private readonly DemoVpsConnector _owner;
        private readonly VpsProfile _profile;
        private readonly DemoAdminApi _api;
        private string _installed;
        private DateTime? _startedAt;

        public Session(DemoVpsConnector owner, VpsProfile profile, RootAccess root, DemoAdminApi api, string installed)
        {
            _owner = owner;
            _profile = profile;
            Root = root;
            _api = api;
            _installed = installed;
        }

        public string Label => _profile.Display;

        public RootAccess Root { get; }

        public async Task<VpsSystemInfo> DetectAsync(CancellationToken ct)
        {
            await Task.Delay(_owner.StepDelay, ct);
            const string Debian = "noyau=Linux\narch=x86_64\ndistrib=debian\ndistrib_nom=Debian GNU/Linux 12 (bookworm)\ndistrib_version=12\n"
                + "distrib_famille=\nsystemd=oui\ncurl=oui\nwget=oui\nbash=oui\nmemoire_ko=8039812\ndisque_ko=78643200\n";
            return VpsSystemInfo.Parse(Debian, Root, _profile);
        }

        public Task<VpsServiceStatus> StatusAsync(CancellationToken ct)
        {
            bool running = _startedAt != null;
            // Minecraft écoute un moment après le démarrage du service (le monde se crée).
            bool listening = running && DateTime.UtcNow - _startedAt!.Value > _owner.StepDelay * 3;
            if (listening && !_api.Online)
            {
                _api.Start();
            }
            return Task.FromResult(new VpsServiceStatus
            {
                Installed = _installed.Length > 0,
                ActiveState = running ? "active" : "inactive",
                SubState = running ? "running" : "dead",
                Enabled = _installed.Length > 0,
                Listening = listening,
                Version = _installed,
                Minecraft = _installed.Length > 0 ? "26.3" : "",
                Since = _startedAt,
            });
        }

        public async Task<VpsInstallResult> InstallAsync(Release release, IProgress<(string, double)> progress, IProgress<string> log, CancellationToken ct)
        {
            if (Root == RootAccess.None)
            {
                throw new InvalidOperationException($"Le compte {_profile.User} n'a pas les droits d'administration sur {_profile.Host}.");
            }
            var steps = new (int Pct, string Step, string[] Lines)[]
            {
                (4, "Compte du serveur", new[] { "Compte système « valcombe » créé (sans connexion possible)." }),
                (10, "Paquets du système", new[] { "Paquets à jour : curl, ca-certificates, tar." }),
                (38, "Java 25 pour Linux (Mojang)", new[] { "Java runtime « java-runtime-epsilon » : 312 fichiers téléchargés et vérifiés (SHA-1)." }),
                (52, "Fabric", new[] { "Lanceur Fabric du serveur téléchargé (Minecraft 26.3)." }),
                (80, "Mods du serveur", new[]
                {
                    "Fabric API vérifié (SHA-512).", "Les Cendres de Valcombe " + release.Tag + " vérifié (SHA-512).",
                    "Simple Voice Chat vérifié (SHA-512).", "WorldEdit vérifié (SHA-512).",
                }),
                (90, "Réglages", new[] { "server.properties : port 25565, liste blanche imposée.", "eula.txt : contrat accepté dans le launcher." }),
                (96, "Service systemd", new[] { "valcombe.service écrit et activé au démarrage de la machine." }),
                (100, "Pare-feu", new[] { "ufw : ports 25565/tcp et 24454/udp ouverts." }),
            };
            progress.Report(("Serveur loué : examen du système…", 0.01));
            foreach (var (pct, step, lines) in steps)
            {
                await Task.Delay(_owner.StepDelay, ct);
                progress.Report(("Serveur loué : " + step, 0.06 + 0.94 * pct / 100.0));
                foreach (string line in lines)
                {
                    log.Report(line);
                }
            }
            bool changed = _installed != release.Tag;
            _installed = release.Tag;
            return new VpsInstallResult
            {
                Changed = changed, Version = release.Tag, Summary = $"Valcombe {release.Tag} installé dans {_profile.InstallDir}.",
                Log = steps.SelectMany(s => s.Lines).ToList(),
            };
        }

        public async Task StartAsync(CancellationToken ct)
        {
            await Task.Delay(_owner.StepDelay, ct);
            if (_installed.Length == 0)
            {
                throw new InvalidOperationException("Le serveur n'est pas installé sur ce VPS : installez-le d'abord.");
            }
            _startedAt ??= DateTime.UtcNow;
        }

        public IServerConnection OpenAdmin() => new DemoManagedConnection(_api, _profile.Display, _installed);

        public void Dispose()
        {
        }
    }
}
