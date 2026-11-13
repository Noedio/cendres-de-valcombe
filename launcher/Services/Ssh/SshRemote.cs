using System.IO;
using System.Net.Sockets;
using System.Text;
using Renci.SshNet;
using Renci.SshNet.Common;

namespace Valcombe.Launcher.Services.Ssh;

/// <summary>
/// Une connexion SSH à un serveur loué (bibliothèque SSH.NET) : commandes, envoi de fichiers par SFTP, tunnels.
/// La clé du serveur est vérifiée à chaque connexion (<see cref="KnownHosts"/>), avant toute authentification.
/// </summary>
public sealed class SshRemote : IRemoteShell
{
    /// <summary>Ports du PC qu'un tunnel ne doit jamais prendre (le serveur personnel de l'hôte peut les utiliser).</summary>
    private static readonly int[] ForbiddenLocalPorts = { 25565, 25570, 24454 };

    private readonly VpsProfile _profile;
    private readonly SshClient _ssh;
    private readonly string _fingerprint;
    private readonly string _sudoPassword;
    private SftpClient? _sftp;
    private readonly SemaphoreSlim _sftpGate = new(1, 1);

    private SshRemote(VpsProfile profile, SshClient ssh, string fingerprint, string sudoPassword)
    {
        _profile = profile;
        _ssh = ssh;
        _fingerprint = fingerprint;
        _sudoPassword = sudoPassword;
    }

    public string Label => _profile.Display;

    public RootAccess Root { get; private set; } = RootAccess.None;

    public string? DryRunRoot => null;

    /// <summary>Empreinte de la clé du serveur, « SHA256:... ».</summary>
    public string Fingerprint => _fingerprint;

    public bool IsConnected => _ssh.IsConnected;

    // ------------------------------------------------------------------
    // Connexion
    // ------------------------------------------------------------------
    private static ConnectionInfo CreateConnectionInfo(VpsProfile p)
    {
        var methods = new List<AuthenticationMethod>();
        if (p.Auth == SshAuthKind.PrivateKey)
        {
            methods.Add(new PrivateKeyAuthenticationMethod(p.User, p.GetPrivateKey()));
        }
        else
        {
            string password = p.GetPassword();
            methods.Add(new PasswordAuthenticationMethod(p.User, password));
            // Certains hébergeurs n'acceptent le mot de passe que par le « clavier interactif ».
            var keyboard = new KeyboardInteractiveAuthenticationMethod(p.User);
            keyboard.AuthenticationPrompt += (_, e) =>
            {
                foreach (var prompt in e.Prompts)
                {
                    prompt.Response = password;
                }
            };
            methods.Add(keyboard);
        }
        return new ConnectionInfo(p.Host.Trim(), p.SshPort, p.User, methods.ToArray())
        {
            Timeout = TimeSpan.FromSeconds(20),
            Encoding = Encoding.UTF8,
        };
    }

    /// <summary>
    /// Se connecte au serveur. Au premier contact, la connexion s'arrête sur <see cref="SshHostKeyUnknownException"/>
    /// (qui porte l'empreinte) : l'interface la fait confirmer, puis rappelle avec <paramref name="acceptFingerprint"/>
    /// égale à cette empreinte, qui est alors retenue. Une clé différente de celle retenue lève
    /// <see cref="SshHostKeyChangedException"/>, toujours. Les autres échecs donnent un message lisible.
    /// </summary>
    public static async Task<SshRemote> ConnectAsync(VpsProfile profile, string? acceptFingerprint = null,
        KnownHosts? knownHosts = null, CancellationToken ct = default)
    {
        profile.Validate();
        knownHosts ??= new KnownHosts();
        string host = profile.Host.Trim();
        SshHostKeyException? refused = null;
        string seen = "";
        string algorithm = "";
        var ssh = new SshClient(CreateConnectionInfo(profile)) { KeepAliveInterval = TimeSpan.FromSeconds(30) };
        ssh.HostKeyReceived += (_, e) =>
        {
            seen = KnownHosts.Format(e.FingerPrintSHA256);
            algorithm = e.HostKeyName;
            switch (knownHosts.Check(host, profile.SshPort, seen))
            {
                case KnownHosts.Verdict.Known:
                    e.CanTrust = true;
                    break;
                case KnownHosts.Verdict.Changed:
                    refused = new SshHostKeyChangedException(host, profile.SshPort, algorithm, seen,
                        knownHosts.Find(host, profile.SshPort)!.Fingerprint);
                    e.CanTrust = false;
                    break;
                default:
                    // Premier contact : seule l'empreinte confirmée par l'hôte est acceptée.
                    if (acceptFingerprint != null && KnownHosts.Format(acceptFingerprint.Trim()) == seen)
                    {
                        e.CanTrust = true;
                    }
                    else
                    {
                        refused = new SshHostKeyUnknownException(host, profile.SshPort, algorithm, seen);
                        e.CanTrust = false;
                    }
                    break;
            }
        };
        try
        {
            await ssh.ConnectAsync(ct);
        }
        catch (Exception ex)
        {
            ssh.Dispose();
            if (refused != null)
            {
                throw refused;
            }
            if (ex is OperationCanceledException)
            {
                throw;
            }
            throw Explain(ex, profile);
        }
        if (knownHosts.Check(host, profile.SshPort, seen) == KnownHosts.Verdict.Unknown)
        {
            knownHosts.Trust(host, profile.SshPort, algorithm, seen);
        }
        var remote = new SshRemote(profile, ssh, seen, profile.GetSudoPassword());
        try
        {
            remote.Root = await remote.DetectRootAsync(ct);
        }
        catch
        {
            remote.Dispose();
            throw;
        }
        return remote;
    }

    /// <summary>Un message en français pour chaque façon dont une connexion SSH échoue.</summary>
    internal static Exception Explain(Exception ex, VpsProfile p)
    {
        string where = $"{p.Host}" + (p.SshPort == 22 ? "" : $" (port {p.SshPort})");
        string text = ex switch
        {
            SshAuthenticationException => $"{where} a refusé la connexion de {p.User} : "
                + (p.Auth == SshAuthKind.Password ? "mot de passe incorrect, ou connexion par mot de passe désactivée sur le serveur."
                    : "clé refusée (la clé publique est-elle dans ~/.ssh/authorized_keys sur le serveur ?)."),
            SocketException { SocketErrorCode: SocketError.HostNotFound or SocketError.NoData or SocketError.TryAgain }
                => $"Adresse inconnue : {p.Host}. Vérifiez son orthographe.",
            SocketException { SocketErrorCode: SocketError.ConnectionRefused }
                => $"{where} refuse la connexion SSH : le port SSH est-il le bon ?",
            SocketException or SshOperationTimeoutException or TimeoutException
                => $"{where} ne répond pas : vérifiez l'adresse, le port SSH, et que le serveur est allumé.",
            ProxyException => $"Connexion impossible par le proxy : {ex.Message}",
            SshConnectionException => $"Connexion SSH à {where} interrompue : {ex.Message}",
            _ => $"Connexion SSH à {where} impossible : {ex.Message}",
        };
        return new InvalidOperationException(text, ex);
    }

    private async Task<RootAccess> DetectRootAsync(CancellationToken ct)
    {
        var id = await ExecAsync("id -u", null, null, TimeSpan.FromSeconds(30), ct);
        if (id.Output.Trim() == "0")
        {
            return RootAccess.Root;
        }
        if ((await ExecAsync("sudo -n true", null, null, TimeSpan.FromSeconds(30), ct)).Ok)
        {
            return RootAccess.SudoWithoutPassword;
        }
        if (_sudoPassword.Length > 0
            && (await ExecAsync("sudo -k -S -p '' true", _sudoPassword + "\n", null, TimeSpan.FromSeconds(30), ct)).Ok)
        {
            return RootAccess.SudoWithPassword;
        }
        return RootAccess.None;
    }

    // ------------------------------------------------------------------
    // Commandes
    // ------------------------------------------------------------------
    public Task<RemoteResult> RunAsync(string command, bool asRoot = false, Action<string>? onLine = null,
        TimeSpan? timeout = null, CancellationToken ct = default)
    {
        string text = "bash -c " + Shell.Quote(command);
        string? stdin = null;
        if (asRoot)
        {
            switch (Root)
            {
                case RootAccess.Root:
                    break;
                case RootAccess.SudoWithoutPassword:
                    text = "sudo -n -- " + text;
                    break;
                case RootAccess.SudoWithPassword:
                    // -k : sudo redemande toujours le mot de passe, qui est donc toujours lu (jamais laissé à la commande).
                    text = "sudo -k -S -p '' -- " + text;
                    stdin = _sudoPassword + "\n";
                    break;
                default:
                    throw new InvalidOperationException($"Le compte {_profile.User} n'a pas les droits d'administration sur {_profile.Host} : "
                        + "connectez-vous en root, ou avec un compte qui a sudo (indiquez alors son mot de passe sudo).");
            }
        }
        return ExecAsync(text, stdin, onLine, timeout, ct);
    }

    private async Task<RemoteResult> ExecAsync(string text, string? stdin, Action<string>? onLine, TimeSpan? timeout,
        CancellationToken ct)
    {
        using var cmd = _ssh.CreateCommand(text, Encoding.UTF8);
        cmd.CommandTimeout = timeout ?? TimeSpan.FromMinutes(2);
        var run = cmd.ExecuteAsync(ct);
        // L'entrée standard est toujours refermée : une commande qui la lirait ne resterait pas suspendue.
        using (var input = cmd.CreateInputStream())
        {
            if (stdin != null)
            {
                await input.WriteAsync(Encoding.UTF8.GetBytes(stdin), ct);
            }
        }
        var output = new StringBuilder();
        // Les flux de SSH.NET se lisent en bloquant, jusqu'à la fin de la commande : un fil pour chacun.
        var readOut = Task.Run(() =>
        {
            using var reader = new StreamReader(cmd.OutputStream, Encoding.UTF8);
            string? line;
            while ((line = reader.ReadLine()) != null)
            {
                output.Append(line).Append('\n');
                onLine?.Invoke(line);
            }
        });
        var readErr = Task.Run(() => new StreamReader(cmd.ExtendedOutputStream, Encoding.UTF8).ReadToEnd());
        try
        {
            await run;
        }
        catch (SshOperationTimeoutException ex)
        {
            throw new TimeoutException($"Le serveur n'a pas fini à temps ({cmd.CommandTimeout.TotalSeconds:0} s).", ex);
        }
        catch (SshConnectionException ex)
        {
            throw new InvalidOperationException($"Connexion SSH perdue avec {_profile.Host} : {ex.Message}", ex);
        }
        await readOut;
        string error = await readErr;
        return new RemoteResult(cmd.ExitStatus ?? -1, output.ToString(), error);
    }

    // ------------------------------------------------------------------
    // Fichiers (SFTP)
    // ------------------------------------------------------------------
    public async Task UploadAsync(string remotePath, byte[] content, CancellationToken ct = default)
    {
        await _sftpGate.WaitAsync(ct);
        try
        {
            if (_sftp is not { IsConnected: true })
            {
                _sftp?.Dispose();
                // SFTP ouvre sa propre connexion : la clé du serveur doit être exactement celle déjà vérifiée.
                var sftp = new SftpClient(CreateConnectionInfo(_profile));
                sftp.HostKeyReceived += (_, e) => e.CanTrust = KnownHosts.Format(e.FingerPrintSHA256) == _fingerprint;
                try
                {
                    await sftp.ConnectAsync(ct);
                }
                catch (Exception ex)
                {
                    sftp.Dispose();
                    if (ex is OperationCanceledException)
                    {
                        throw;
                    }
                    throw Explain(ex, _profile);
                }
                _sftp = sftp;
            }
            using var stream = new MemoryStream(content);
            await _sftp.UploadFileAsync(stream, remotePath, ct);
        }
        catch (SftpPermissionDeniedException ex)
        {
            throw new InvalidOperationException($"Envoi refusé par le serveur ({remotePath}) : {ex.Message}", ex);
        }
        finally
        {
            _sftpGate.Release();
        }
    }

    // ------------------------------------------------------------------
    // Tunnel
    // ------------------------------------------------------------------
    public Task<IServerConnection> OpenTunnelAsync(int remotePort, string key, CancellationToken ct = default) => Task.Run<IServerConnection>(() =>
    {
        for (int attempt = 0; attempt < 5; attempt++)
        {
            ct.ThrowIfCancellationRequested();
            // Port 0 : Windows choisit un port libre, que SSH.NET reporte dans BoundPort une fois l'écoute ouverte.
            var forward = new ForwardedPortLocal("127.0.0.1", 0, "127.0.0.1", (uint)remotePort);
            _ssh.AddForwardedPort(forward);
            forward.Start();
            if (forward.BoundPort == 0 || ForbiddenLocalPorts.Contains((int)forward.BoundPort))
            {
                forward.Stop();
                _ssh.RemoveForwardedPort(forward);
                forward.Dispose();
                continue;
            }
            return new SshTunnel(_ssh, forward, key, $"{_profile.Host} (tunnel SSH)");
        }
        throw new InvalidOperationException("Impossible d'ouvrir un tunnel : aucun port libre sur ce PC.");
    }, ct);

    public void Dispose()
    {
        _sftp?.Dispose();
        _ssh.Dispose();
        _sftpGate.Dispose();
    }
}

/// <summary>
/// Accès à l'API d'administration d'un serveur loué par un tunnel SSH : les requêtes vers
/// http://127.0.0.1:&lt;port local&gt;/ arrivent au serveur comme si elles venaient de la machine elle-même
/// (l'API n'accepte que celles-là), chiffrées par SSH en chemin.
/// </summary>
public sealed class SshTunnel : IServerConnection
{
    private readonly SshClient _ssh;
    private readonly ForwardedPortLocal _forward;
    private bool _disposed;

    internal SshTunnel(SshClient ssh, ForwardedPortLocal forward, string key, string label)
    {
        _ssh = ssh;
        _forward = forward;
        Key = key;
        Label = label;
        BaseAddress = new Uri($"http://127.0.0.1:{forward.BoundPort}/");
        _forward.Exception += (_, e) => LastError = e.Exception.Message;
    }

    public Uri BaseAddress { get; }

    public string Key { get; }

    public string Label { get; }

    public bool IsOpen => !_disposed && _forward.IsStarted && _ssh.IsConnected;

    /// <summary>Dernière erreur du tunnel (une requête qui n'a pas pu passer), ou null.</summary>
    public string? LastError { get; private set; }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;
        try
        {
            if (_forward.IsStarted)
            {
                _forward.Stop();
            }
            _ssh.RemoveForwardedPort(_forward);
        }
        catch (ObjectDisposedException)
        {
            // La connexion SSH est déjà fermée : le tunnel l'est avec elle.
        }
        _forward.Dispose();
    }
}
