namespace Valcombe.Launcher.Services;

/// <summary>
/// Un accès à l'API d'administration d'un serveur de Valcombe (contrat : docs/ADMIN-API.md), quel que soit le
/// chemin : tunnel SSH vers un serveur loué (Ssh/SshTunnel), serveur de ce PC... La page Serveur ne connaît
/// que cette interface : elle envoie ses requêtes à <see cref="BaseAddress"/> avec la clé dans l'en-tête
/// <see cref="KeyHeader"/>, et ferme l'accès (Dispose) quand elle n'en a plus besoin.
/// </summary>
public interface IServerConnection : IDisposable
{
    /// <summary>En-tête HTTP qui porte la clé d'administration.</summary>
    public const string KeyHeader = "X-Valcombe-Admin";

    /// <summary>Adresse à laquelle ajouter « admin/etat », « admin/console »... (toujours terminée par « / »).</summary>
    Uri BaseAddress { get; }

    /// <summary>Clé d'administration du serveur (config/valcombe/admin.json, champ « key »).</summary>
    string Key { get; }

    /// <summary>Ce qu'on montre à l'hôte : « mon-vps.fr (tunnel SSH) »...</summary>
    string Label { get; }

    /// <summary>Faux dès que l'accès est coupé (connexion SSH perdue, Dispose) : il faut alors en rouvrir un.</summary>
    bool IsOpen { get; }
}
