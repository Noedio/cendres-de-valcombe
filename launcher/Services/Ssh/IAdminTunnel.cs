namespace Valcombe.Launcher.Services.Ssh;

/// <summary>
/// Un tunnel ouvert vers l'API d'administration d'un serveur loué (contrat : docs/ADMIN-API.md) : une adresse
/// locale (<see cref="BaseAddress"/>) qui mène, par SSH, à l'API du serveur, et la clé lue sur le serveur. Les
/// requêtes y partent avec la clé dans l'en-tête <see cref="KeyHeader"/> ; Dispose ferme le tunnel.
/// La page Serveur ne le voit pas directement : <see cref="Admin.SshConnection"/> l'enveloppe dans une
/// <see cref="Admin.IServerConnection"/>, comme l'accès direct.
/// </summary>
public interface IAdminTunnel : IDisposable
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
