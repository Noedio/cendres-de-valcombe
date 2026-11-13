using System.Text.Json.Serialization;

namespace Valcombe.Launcher.Services.Admin;

/// <summary>
/// Un serveur distant enregistré sur la page Serveur, pour l'administrer depuis ce PC.
/// Gardé dans launcher.json ; la clé d'administration y est chiffrée avec DPAPI (comme le jeton Microsoft) :
/// le fichier copié sur un autre PC, ou lu par un autre compte Windows, ne la livre pas.
/// </summary>
public sealed class RemoteServer
{
    /// <summary>Accès direct par l'adresse (réseau privé, VPN, ou adminLocalOnly à false).</summary>
    public const string AccessDirect = "direct";

    /// <summary>Identifiant stable, pour retrouver le serveur choisi au prochain lancement.</summary>
    public string Id { get; set; } = Guid.NewGuid().ToString("N")[..12];

    /// <summary>Nom affiché (« VPS de la guilde »).</summary>
    public string Name { get; set; } = "";

    /// <summary>Nom ou adresse IP du serveur, sans port.</summary>
    public string Host { get; set; } = "";

    /// <summary>Port de l'API : le port de la tablette du serveur (25570 sauf s'il a été changé).</summary>
    public int ApiPort { get; set; } = AdminClient.DefaultPort;

    /// <summary>Clé d'administration chiffrée (DPAPI, en base 64), ou vide si elle n'a pas été donnée.</summary>
    public string ProtectedKey { get; set; } = "";

    /// <summary>
    /// Par où passer : <see cref="AccessDirect"/> aujourd'hui. Le tunnel SSH (Services/Ssh) ajoutera sa valeur
    /// et ses propres réglages ; <see cref="ServerConnections.Open"/> choisit la connexion d'après ce champ.
    /// </summary>
    public string Access { get; set; } = AccessDirect;

    /// <summary>La clé en clair, lue au moment de la requête ; null si absente ou illisible sur ce PC.</summary>
    public string? ReadKey() => Dpapi.UnprotectText(ProtectedKey);

    public void SetKey(string key) => ProtectedKey = Dpapi.ProtectText(key.Trim());

    /// <summary>« vps.exemple.fr:25570 ».</summary>
    [JsonIgnore]
    public string Endpoint => $"{(Host.Contains(':') ? "[" + Host + "]" : Host)}:{ApiPort}";
}

/// <summary>Fabrique la connexion qui convient à un serveur enregistré.</summary>
public static class ServerConnections
{
    /// <summary>
    /// La connexion vers ce serveur. Le tunnel SSH viendra s'ajouter ici (<c>"ssh" =&gt; new SshConnection(...)</c>),
    /// sans rien changer à la page.
    /// </summary>
    public static IServerConnection Open(RemoteServer server) => server.Access switch
    {
        _ => new DirectConnection(server.Host, server.ApiPort, server.ReadKey),
    };

    /// <summary>Le serveur de ce PC : l'API répond sur 127.0.0.1, au port de sa tablette, avec la clé de son dossier.</summary>
    public static IServerConnection OpenLocal(int port) => new DirectConnection("127.0.0.1", port, LocalServer.ReadAdminKey);
}
