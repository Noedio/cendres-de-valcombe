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
    /// Par où passer : <see cref="AccessDirect"/> ou <see cref="AccessSsh"/> ; <see cref="ServerConnections.Open"/>
    /// choisit la connexion d'après ce champ.
    /// </summary>
    public string Access { get; set; } = AccessDirect;

    /// <summary>
    /// Par un tunnel SSH vers un serveur loué : la connexion (adresse, compte, secrets) est celle du profil
    /// <see cref="VpsId"/> de ValcombeData/serveurs-loues.json ; la clé et le port de l'API sont lus sur le serveur.
    /// </summary>
    public const string AccessSsh = "ssh";

    /// <summary>Pour <see cref="AccessSsh"/> : l'identifiant du profil SSH (<see cref="Ssh.VpsProfile.Id"/>).</summary>
    public string VpsId { get; set; } = "";

    /// <summary>Vrai si la clé d'administration est lue sur le serveur (tunnel SSH) et non donnée par l'hôte.</summary>
    [JsonIgnore]
    public bool KeyFromServer => Access == AccessSsh;

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
    /// La connexion vers ce serveur : tunnel SSH (<see cref="SshConnection"/>) pour un serveur loué, accès direct
    /// sinon. Un profil SSH introuvable donne une connexion qui l'explique à chaque essai.
    /// </summary>
    public static IServerConnection Open(RemoteServer server) => server.Access switch
    {
        RemoteServer.AccessSsh => Ssh.VpsProfiles.Load().Find(p => p.Id == server.VpsId) is { } profile
            ? new SshConnection(profile)
            : new FailedConnection($"tunnel SSH vers {server.Host}",
                "Le serveur loué de cette fiche n'est plus enregistré sur ce PC (ValcombeData/serveurs-loues.json) : enregistrez-le de nouveau."),
        _ => new DirectConnection(server.Host, server.ApiPort, server.ReadKey),
    };

    /// <summary>Le serveur de ce PC : l'API répond sur 127.0.0.1, au port de sa tablette, avec la clé de son dossier.</summary>
    public static IServerConnection OpenLocal(int port) => new DirectConnection("127.0.0.1", port, LocalServer.ReadAdminKey);
}
