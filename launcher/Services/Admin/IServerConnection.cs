using System.Net.Http;

namespace Valcombe.Launcher.Services.Admin;

/// <summary>
/// Un chemin vers l'API d'administration d'un serveur Valcombe (docs/ADMIN-API.md).
///
/// La page Serveur ne parle qu'à cette interface, au travers d'<see cref="AdminClient"/>. Deux façons de faire :
/// <list type="bullet">
/// <item><see cref="DirectConnection"/> : requêtes HTTP envoyées tout droit à une adresse (le serveur de ce PC,
/// ou un serveur distant joignable par un réseau privé ou un VPN, avec <c>adminLocalOnly</c> à <c>false</c>).</item>
/// <item>Le tunnel SSH (ticket à part, dossier Services/Ssh) : il ouvrira le tunnel dans <see cref="ConnectAsync"/>,
/// puis enverra les requêtes à <c>localhost</c> par ce tunnel, le serveur les recevant comme venues de lui-même.
/// Il pourra aussi démarrer un serveur arrêté (<see cref="CanStartServer"/>).</item>
/// </list>
/// Des requêtes peuvent se croiser (une commande envoyée pendant l'interrogation régulière de la page) : une
/// implémentation doit le supporter. La page ferme la connexion (<see cref="IAsyncDisposable.DisposeAsync"/>) quand
/// un autre serveur est choisi ; une requête encore en route peut alors échouer, la page l'ignore.
/// </summary>
public interface IServerConnection : IAsyncDisposable
{
    /// <summary>Par où passe la connexion, à montrer tel quel (« 127.0.0.1:25570 », « tunnel SSH vers vps.exemple.fr »).</summary>
    string Description { get; }

    /// <summary>
    /// Prépare l'accès avant une série de requêtes : rien pour l'accès direct, ouvrir (ou rouvrir) le tunnel pour SSH.
    /// Appelé souvent : doit revenir aussitôt quand tout est déjà prêt. Lève <see cref="AdminException"/> si
    /// l'accès est impossible (statut 0, message lisible).
    /// </summary>
    Task ConnectAsync(CancellationToken ct);

    /// <summary>
    /// Envoie une requête à l'API et rend la réponse telle quelle, sans lever pour un statut d'erreur HTTP
    /// (401, 429… : c'est <see cref="AdminClient"/> qui les traduit).
    /// </summary>
    /// <param name="method">GET ou POST.</param>
    /// <param name="path">Chemin et requête, commençant par <c>/admin/</c> (« /admin/console?depuis=12 »).</param>
    /// <param name="json">Corps JSON d'un POST, ou null.</param>
    /// <exception cref="AdminException">Serveur injoignable ou délai dépassé (statut 0).</exception>
    Task<AdminReply> SendAsync(HttpMethod method, string path, string? json, CancellationToken ct);

    /// <summary>
    /// Cette connexion sait démarrer le serveur quand il est arrêté (l'API, elle, s'arrête avec lui).
    /// Faux pour l'accès direct ; le tunnel SSH le pourra. Le serveur de ce PC est démarré par la page elle-même.
    /// </summary>
    bool CanStartServer { get; }

    /// <summary>Démarre le serveur arrêté (seulement si <see cref="CanStartServer"/>).</summary>
    Task StartServerAsync(IProgress<(string, double)> progress, CancellationToken ct);
}

/// <summary>Réponse brute de l'API : statut HTTP, corps, et délai d'attente demandé par un 429.</summary>
public sealed record AdminReply(int Status, string Body, TimeSpan? RetryAfter = null);

/// <summary>
/// Erreur de l'API d'administration, avec un message en français à montrer tel quel.
/// <see cref="Status"/> vaut 0 quand le serveur n'a pas répondu (injoignable, délai dépassé).
/// </summary>
public sealed class AdminException : Exception
{
    public AdminException(int status, string message, TimeSpan? retryAfter = null, Exception? inner = null)
        : base(message, inner)
    {
        Status = status;
        RetryAfter = retryAfter;
    }

    public int Status { get; }

    /// <summary>Pour un 429 : attendre ce délai avant toute nouvelle requête.</summary>
    public TimeSpan? RetryAfter { get; }

    /// <summary>
    /// Insister ne servirait à rien, ou aggraverait les choses : clé refusée (chaque essai raté rapproche d'un
    /// blocage), adresse refusée, API absente. La page cesse alors d'interroger le serveur jusqu'à un nouvel essai.
    /// </summary>
    public bool StopsPolling => Status is 401 or 403 or 404;
}
