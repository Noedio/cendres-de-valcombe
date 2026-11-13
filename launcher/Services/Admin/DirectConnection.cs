using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Text;

namespace Valcombe.Launcher.Services.Admin;

/// <summary>
/// Accès direct à l'API d'administration, en HTTP, à une adresse et un port.
///
/// Sert pour le serveur de ce PC (127.0.0.1) et pour un serveur distant joignable sans tunnel. L'API parle
/// HTTP sans chiffrement : par défaut, le serveur n'accepte que les requêtes de sa propre machine
/// (<c>adminLocalOnly</c>), et un serveur distant répond alors 403, ce que la page explique.
/// </summary>
public sealed class DirectConnection : IServerConnection
{
    /// <summary>En-tête qui porte la clé (docs/ADMIN-API.md).</summary>
    public const string KeyHeader = "X-Valcombe-Admin";

    private readonly Func<string?> _key;
    private readonly HttpClient _http;
    private readonly Uri _base;

    /// <param name="host">Nom ou adresse du serveur (une adresse IPv6 est acceptée telle quelle).</param>
    /// <param name="port">Port de l'API, celui de la tablette du serveur (<c>tabletPort</c>).</param>
    /// <param name="key">Donne la clé à chaque requête (relue à chaque fois : elle peut changer), ou null si inconnue.</param>
    public DirectConnection(string host, int port, Func<string?> key)
    {
        _key = key;
        _base = new UriBuilder(Uri.UriSchemeHttp, host.Trim(), port).Uri;
        // Un client à part : délai propre (une commande peut prendre 30 s côté serveur), et pas de proxy du
        // système, qui n'a rien à faire entre ce PC et un serveur local ou privé.
        _http = new HttpClient(new SocketsHttpHandler
        {
            UseProxy = false,
            ConnectTimeout = TimeSpan.FromSeconds(5),
            PooledConnectionLifetime = TimeSpan.FromMinutes(2),
        })
        {
            Timeout = TimeSpan.FromSeconds(40),
        };
        _http.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("ValcombeLauncher", "0.1"));
        Description = $"{(host.Contains(':') ? "[" + host + "]" : host)}:{port}";
    }

    public string Description { get; }

    public bool CanStartServer => false;

    public Task ConnectAsync(CancellationToken ct) => Task.CompletedTask;

    public Task StartServerAsync(IProgress<(string, double)> progress, CancellationToken ct) =>
        throw new NotSupportedException("L'accès direct ne sait pas démarrer un serveur arrêté.");

    public async Task<AdminReply> SendAsync(HttpMethod method, string path, string? json, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(method, new Uri(_base, path));
        string? key = _key();
        if (!string.IsNullOrEmpty(key))
        {
            request.Headers.Add(KeyHeader, key);
        }
        if (json != null)
        {
            request.Content = new StringContent(json, Encoding.UTF8, "application/json");
        }
        try
        {
            using var response = await _http.SendAsync(request, ct);
            string body = await response.Content.ReadAsStringAsync(ct);
            TimeSpan? retry = response.Headers.RetryAfter?.Delta;
            return new AdminReply((int)response.StatusCode, body, retry);
        }
        catch (HttpRequestException ex)
        {
            throw new AdminException(0, Unreachable(ex), inner: ex);
        }
        catch (TaskCanceledException ex) when (!ct.IsCancellationRequested)
        {
            throw new AdminException(0, $"{Description} ne répond pas (délai dépassé).", inner: ex);
        }
    }

    /// <summary>Un message clair pour les pannes de réseau les plus courantes.</summary>
    private string Unreachable(HttpRequestException ex)
    {
        var socket = ex.InnerException as SocketException ?? ex.InnerException?.InnerException as SocketException;
        return socket?.SocketErrorCode switch
        {
            SocketError.ConnectionRefused => $"Rien ne répond sur {Description} : serveur arrêté, ou API sur un autre port.",
            SocketError.HostNotFound or SocketError.NoData => $"Adresse introuvable : {_base.Host}.",
            SocketError.TimedOut => $"{Description} ne répond pas (pare-feu, ou serveur hors ligne).",
            SocketError.NetworkUnreachable or SocketError.HostUnreachable => $"{Description} est hors de portée de ce PC.",
            _ => $"{Description} injoignable : {ex.Message}",
        };
    }

    public ValueTask DisposeAsync()
    {
        _http.Dispose();
        return ValueTask.CompletedTask;
    }
}
