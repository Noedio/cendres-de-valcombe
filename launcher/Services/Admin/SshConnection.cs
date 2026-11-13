using System.Net.Http;
using Valcombe.Launcher.Services.Ssh;

namespace Valcombe.Launcher.Services.Admin;

/// <summary>
/// Accès à l'API d'administration d'un serveur loué par un tunnel SSH (Services/Ssh) : la page Serveur s'en sert
/// comme de l'accès direct.
///
/// <see cref="ConnectAsync"/> ouvre la connexion SSH (profil de ValcombeData/serveurs-loues.json, secrets chiffrés),
/// lit la clé d'administration et le port de l'API sur le serveur (l'hôte n'a rien à copier), puis ouvre le tunnel ;
/// les requêtes partent ensuite vers ce tunnel comme vers une adresse locale (<see cref="DirectConnection"/>), et le
/// serveur les reçoit comme venues de lui-même. Un tunnel coupé (connexion perdue, serveur redémarré) est rouvert à
/// l'appel suivant. Le serveur arrêté se démarre par systemd (<see cref="CanStartServer"/>).
///
/// L'empreinte de la clé du serveur doit avoir été confirmée avant (premier contact, voir
/// <see cref="SshRemote.ConnectAsync"/>) : sinon la connexion échoue avec un message qui la donne, et n'est plus
/// retentée tant que la page ne rouvre pas l'accès (une empreinte ne se confirme pas en insistant).
/// </summary>
public sealed class SshConnection : IServerConnection
{
    private readonly VpsProfile _profile;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private VpsServer? _vps;
    private IAdminTunnel? _tunnel;
    private DirectConnection? _direct;
    /// <summary>Refus définitif (empreinte inconnue ou changée) : redonné tel quel sans retenter.</summary>
    private AdminException? _refused;
    private bool _disposed;

    public SshConnection(VpsProfile profile)
    {
        _profile = profile;
        Description = $"tunnel SSH vers {profile.Display}";
    }

    public string Description { get; }

    public bool CanStartServer => true;

    public async Task ConnectAsync(CancellationToken ct)
    {
        if (IsReady)
        {
            return;
        }
        await _gate.WaitAsync(ct);
        try
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (IsReady)
            {
                return;
            }
            if (_refused != null)
            {
                throw _refused;
            }
            CloseTunnel();
            var vps = await ConnectVpsAsync(ct);
            try
            {
                _tunnel = await vps.OpenAdminTunnelAsync(ct);
            }
            catch (InvalidOperationException ex)
            {
                // Clé d'administration absente (serveur jamais démarré), API coupée, aucun port libre...
                throw new AdminException(0, ex.Message, inner: ex);
            }
            var tunnel = _tunnel;
            _direct = new DirectConnection("127.0.0.1", tunnel.BaseAddress.Port, () => tunnel.Key);
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<AdminReply> SendAsync(HttpMethod method, string path, string? json, CancellationToken ct)
    {
        var direct = _direct ?? throw new AdminException(0, $"Le {Description} n'est pas ouvert.");
        try
        {
            return await direct.SendAsync(method, path, json, ct);
        }
        catch (AdminException ex) when (ex.Status == 0)
        {
            // Le tunnel répond par une erreur de réseau quand la connexion SSH est tombée, ou quand le serveur ne
            // tourne plus derrière : un message qui parle du serveur loué, et le tunnel rouvert à l'appel suivant.
            bool lost = _tunnel is not { IsOpen: true };
            if (lost)
            {
                await _gate.WaitAsync(CancellationToken.None);
                try
                {
                    CloseTunnel();
                }
                finally
                {
                    _gate.Release();
                }
            }
            throw new AdminException(0, lost
                ? $"Connexion SSH à {_profile.Display} perdue : elle sera rouverte."
                : $"Le serveur de Valcombe ne répond pas sur {_profile.Host} : il est arrêté, ou en train de démarrer.", inner: ex);
        }
    }

    public async Task StartServerAsync(IProgress<(string, double)> progress, CancellationToken ct)
    {
        await _gate.WaitAsync(ct);
        try
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_refused != null)
            {
                throw _refused;
            }
            progress.Report(($"Démarrage du serveur sur {_profile.Host}…", -1));
            var vps = await ConnectVpsAsync(ct);
            try
            {
                await vps.StartAsync(ct);
            }
            catch (InvalidOperationException ex)
            {
                throw new AdminException(0, ex.Message, inner: ex);
            }
            // L'API du serveur qui démarre ne répond qu'au bout de quelques dizaines de secondes : le tunnel sera
            // rouvert (avec la clé et le port relus) à la prochaine requête de la page.
            CloseTunnel();
            progress.Report(($"Serveur démarré sur {_profile.Host} : l'API répondra dans un instant.", 0));
        }
        finally
        {
            _gate.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        await _gate.WaitAsync();
        try
        {
            if (_disposed)
            {
                return;
            }
            _disposed = true;
            CloseTunnel();
            _vps?.Dispose();
            _vps = null;
        }
        finally
        {
            _gate.Release();
        }
    }

    private bool IsReady => !_disposed && _direct != null && _tunnel is { IsOpen: true };

    /// <summary>La connexion SSH, ouverte ou rouverte (appelé sous <see cref="_gate"/>).</summary>
    private async Task<VpsServer> ConnectVpsAsync(CancellationToken ct)
    {
        if (_vps is { Shell: SshRemote { IsConnected: true } })
        {
            return _vps;
        }
        _vps?.Dispose();
        _vps = null;
        try
        {
            _vps = await VpsServer.ConnectAsync(_profile, null, ct);
            return _vps;
        }
        catch (SshHostKeyException ex)
        {
            // Empreinte jamais confirmée, ou clé du serveur changée : rien ne sert de réessayer.
            _refused = new AdminException(0, ex.Message, inner: ex);
            throw _refused;
        }
        catch (Exception ex) when (ex is InvalidOperationException or Renci.SshNet.Common.SshException
                                       or System.Net.Sockets.SocketException or TimeoutException)
        {
            throw new AdminException(0, $"Connexion SSH à {_profile.Display} impossible : {ex.Message}", inner: ex);
        }
    }

    /// <summary>Ferme le tunnel et le client qui y menait (appelé sous <see cref="_gate"/>).</summary>
    private void CloseTunnel()
    {
        var direct = _direct;
        _direct = null;
        if (direct != null)
        {
            _ = direct.DisposeAsync().AsTask();
        }
        _tunnel?.Dispose();
        _tunnel = null;
    }
}

/// <summary>
/// Une connexion impossible à établir (fiche incomplète, profil disparu) : chaque essai lève une
/// <see cref="AdminException"/> qui porte l'explication, que la page montre comme toute panne d'accès.
/// </summary>
public sealed class FailedConnection : IServerConnection
{
    private readonly string _why;

    public FailedConnection(string description, string why)
    {
        Description = description;
        _why = why;
    }

    public string Description { get; }

    public bool CanStartServer => false;

    public Task ConnectAsync(CancellationToken ct) => throw new AdminException(0, _why);

    public Task<AdminReply> SendAsync(HttpMethod method, string path, string? json, CancellationToken ct) =>
        throw new AdminException(0, _why);

    public Task StartServerAsync(IProgress<(string, double)> progress, CancellationToken ct) =>
        throw new NotSupportedException(_why);

    public ValueTask DisposeAsync() => ValueTask.CompletedTask;
}
