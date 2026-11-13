namespace Valcombe.Launcher.Services.Ssh;

/// <summary>Résultat d'une commande sur le serveur.</summary>
public sealed record RemoteResult(int ExitCode, string Output, string Error)
{
    public bool Ok => ExitCode == 0;

    /// <summary>Le message à montrer quand la commande a échoué : sa sortie d'erreur, sinon sa sortie.</summary>
    public string Message => (Error.Trim().Length > 0 ? Error : Output).Trim();
}

/// <summary>Comment le compte SSH obtient les droits d'administration.</summary>
public enum RootAccess
{
    /// <summary>Connecté en root.</summary>
    Root,
    /// <summary>sudo sans mot de passe (NOPASSWD, cas des images cloud « debian », « ubuntu »).</summary>
    SudoWithoutPassword,
    /// <summary>sudo avec le mot de passe du compte (donné par l'entrée standard, jamais sur la ligne de commande).</summary>
    SudoWithPassword,
    /// <summary>Aucun droit d'administration : on ne peut pas installer.</summary>
    None,
}

/// <summary>
/// Le serveur vu par le launcher : des commandes, des fichiers envoyés, un tunnel. Réalisé par SSH
/// (<see cref="SshRemote"/>) et, pour les vérifications sur ce PC, par un bash local qui joue l'installation
/// à blanc dans un dossier (<see cref="LocalBashShell"/>).
/// </summary>
public interface IRemoteShell : IDisposable
{
    /// <summary>« root@vps.exemple.fr »...</summary>
    string Label { get; }

    /// <summary>Droits d'administration du compte, établis à la connexion.</summary>
    RootAccess Root { get; }

    /// <summary>
    /// Pour l'installation à blanc : le dossier qui tient lieu de « / » (chemin vu par bash). Null sur un vrai serveur.
    /// </summary>
    string? DryRunRoot { get; }

    /// <summary>
    /// Lance une commande bash. <paramref name="asRoot"/> : avec les droits d'administration (sudo au besoin).
    /// <paramref name="onLine"/> reçoit chaque ligne de la sortie dès qu'elle arrive (installation, journal).
    /// </summary>
    Task<RemoteResult> RunAsync(string command, bool asRoot = false, Action<string>? onLine = null,
        TimeSpan? timeout = null, CancellationToken ct = default);

    /// <summary>Envoie un fichier (SFTP sur un vrai serveur). Le dossier doit exister.</summary>
    Task UploadAsync(string remotePath, byte[] content, CancellationToken ct = default);

    /// <summary>
    /// Ouvre un tunnel d'un port libre de ce PC (choisi par Windows, jamais 25565, 25570 ni 24454) vers
    /// 127.0.0.1:<paramref name="remotePort"/> sur le serveur.
    /// </summary>
    Task<IAdminTunnel> OpenTunnelAsync(int remotePort, string key, CancellationToken ct = default);
}

/// <summary>Écriture sûre de valeurs dans une commande bash.</summary>
public static class Shell
{
    /// <summary>Entre apostrophes : rien n'y est interprété (une apostrophe devient '\'').</summary>
    public static string Quote(string value) => "'" + value.Replace("'", "'\\''") + "'";
}
