using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using Renci.SshNet;

namespace Valcombe.Launcher.Services.Ssh;

/// <summary>Comment le launcher se présente au serveur SSH.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum SshAuthKind
{
    /// <summary>Mot de passe du compte (et réponses au « clavier interactif » de certains hébergeurs).</summary>
    Password,
    /// <summary>Clé privée (OpenSSH, PuTTY), avec sa phrase de passe éventuelle.</summary>
    PrivateKey,
}

/// <summary>
/// Un serveur loué (VPS) enregistré dans le launcher : comment s'y connecter, et comment y installer Valcombe.
/// Les secrets (mot de passe, clé privée, phrase de passe, mot de passe sudo) sont gardés chiffrés avec DPAPI,
/// comme le jeton Microsoft : ils ne se déchiffrent que sur ce PC, dans cette session Windows.
/// Les ports sont ceux du VPS, une autre machine que ce PC : 25565 (le port standard de Minecraft) y est
/// le bon choix par défaut, et chacun reste réglable.
/// </summary>
public sealed class VpsProfile
{
    private const string Purpose = "valcombe-ssh";

    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    /// <summary>Nom montré à l'hôte (« Mon VPS »).</summary>
    public string Name { get; set; } = "";
    /// <summary>Adresse du VPS : nom (« vps.exemple.fr ») ou IP.</summary>
    public string Host { get; set; } = "";
    public int SshPort { get; set; } = 22;
    /// <summary>Compte SSH : root, ou un compte qui a sudo (« debian », « ubuntu »...).</summary>
    public string User { get; set; } = "root";
    public SshAuthKind Auth { get; set; } = SshAuthKind.Password;

    // --- Secrets, chiffrés (DPAPI, base 64) : passer par les méthodes Set... et Get... ---
    public string PasswordProtected { get; set; } = "";
    public string PrivateKeyProtected { get; set; } = "";
    public string PassphraseProtected { get; set; } = "";
    /// <summary>Mot de passe sudo, si le compte se connecte par clé mais que sudo en demande un.</summary>
    public string SudoPasswordProtected { get; set; } = "";

    // --- Le serveur de Valcombe sur le VPS ---
    public string InstallDir { get; set; } = "/opt/valcombe";
    /// <summary>Compte système dédié qui fait tourner le serveur (jamais root).</summary>
    public string ServiceUser { get; set; } = "valcombe";
    /// <summary>Nom du service systemd (valcombe.service).</summary>
    public string ServiceName { get; set; } = "valcombe";
    public int MinecraftPort { get; set; } = 25565;
    /// <summary>Port UDP de Simple Voice Chat.</summary>
    public int VoicePort { get; set; } = 24454;
    /// <summary>
    /// Port de l'API d'administration (le serveur web du mod) sur le VPS ; 0 : celui du mod (25570 par défaut,
    /// ou celui choisi avec /vc tablette port). Il n'est jamais ouvert au public : le launcher passe par SSH.
    /// </summary>
    public int AdminPort { get; set; }
    public int MemoryMb { get; set; } = 4096;
    /// <summary>L'hôte a lu et accepté le contrat de Minecraft pour ce serveur (obligatoire pour l'installer).</summary>
    public bool EulaAccepted { get; set; }
    /// <summary>Ouvrir les ports du jeu et du chat vocal dans ufw ou firewalld, s'ils sont actifs.</summary>
    public bool OpenFirewall { get; set; } = true;

    // ------------------------------------------------------------------
    // Secrets
    // ------------------------------------------------------------------
    public void SetPassword(string password) => PasswordProtected = Dpapi.ProtectText(password, Purpose);

    public string GetPassword() => Dpapi.UnprotectText(PasswordProtected, Purpose);

    public void SetSudoPassword(string password) => SudoPasswordProtected = Dpapi.ProtectText(password, Purpose);

    /// <summary>Le mot de passe pour sudo : le sien s'il est donné, sinon celui de la connexion.</summary>
    public string GetSudoPassword()
    {
        string sudo = Dpapi.UnprotectText(SudoPasswordProtected, Purpose);
        return sudo.Length > 0 ? sudo : Auth == SshAuthKind.Password ? GetPassword() : "";
    }

    /// <summary>
    /// Enregistre une clé privée (son texte, pas son chemin : le launcher ne dépend plus du fichier), après avoir
    /// vérifié qu'elle se lit avec cette phrase de passe. Lève une erreur lisible sinon.
    /// </summary>
    public void SetPrivateKey(string keyText, string passphrase)
    {
        _ = LoadKey(keyText, passphrase);
        PrivateKeyProtected = Dpapi.ProtectText(keyText, Purpose);
        PassphraseProtected = Dpapi.ProtectText(passphrase, Purpose);
    }

    /// <summary>Lit une clé privée depuis un fichier (id_ed25519, .ppk...) et l'enregistre.</summary>
    public void ImportPrivateKeyFile(string path, string passphrase) => SetPrivateKey(File.ReadAllText(path), passphrase);

    internal PrivateKeyFile GetPrivateKey() =>
        LoadKey(Dpapi.UnprotectText(PrivateKeyProtected, Purpose), Dpapi.UnprotectText(PassphraseProtected, Purpose));

    private static PrivateKeyFile LoadKey(string keyText, string passphrase)
    {
        if (string.IsNullOrWhiteSpace(keyText))
        {
            throw new InvalidOperationException("Aucune clé privée enregistrée pour ce serveur.");
        }
        try
        {
            var stream = new MemoryStream(Encoding.UTF8.GetBytes(keyText));
            return passphrase.Length > 0 ? new PrivateKeyFile(stream, passphrase) : new PrivateKeyFile(stream);
        }
        catch (Exception ex) when (ex is Renci.SshNet.Common.SshException or ArgumentException or InvalidOperationException
                                       or FormatException or NotSupportedException or System.Security.Cryptography.CryptographicException)
        {
            throw new InvalidOperationException(passphrase.Length == 0 && ex.Message.Contains("passphrase", StringComparison.OrdinalIgnoreCase)
                ? "Cette clé privée est protégée : indiquez sa phrase de passe."
                : "Clé privée illisible ou phrase de passe incorrecte (formats acceptés : OpenSSH, PEM, PuTTY).");
        }
    }

    // ------------------------------------------------------------------
    // Vérifications : ces valeurs finissent dans des commandes du serveur, rien n'y passe sans contrôle
    // ------------------------------------------------------------------
    private static readonly Regex HostPattern = new(@"^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$|^[0-9A-Fa-f:.]{2,45}$");
    private static readonly Regex UserPattern = new(@"^[A-Za-z_][A-Za-z0-9_.-]{0,31}$");
    private static readonly Regex AccountPattern = new(@"^[a-z_][a-z0-9_-]{0,31}$");
    private static readonly Regex ServicePattern = new(@"^[a-z0-9][a-z0-9_-]{0,63}$");
    private static readonly Regex DirPattern = new(@"^(/[A-Za-z0-9_][A-Za-z0-9._-]*){2,12}$");

    private static readonly string[] SystemDirs =
        { "/bin", "/boot", "/dev", "/etc", "/lib", "/lib64", "/proc", "/run", "/sbin", "/sys", "/usr", "/tmp" };

    /// <summary>Les fautes de ce profil, en français ; liste vide s'il est bon.</summary>
    public List<string> Problems()
    {
        var list = new List<string>();
        if (string.IsNullOrWhiteSpace(Name) || Name.Length > 40)
        {
            list.Add("Donnez un nom au serveur (40 caractères au plus).");
        }
        if (!HostPattern.IsMatch(Host ?? ""))
        {
            list.Add("Adresse du serveur invalide : un nom (vps.exemple.fr) ou une adresse IP.");
        }
        if (SshPort is < 1 or > 65535)
        {
            list.Add("Port SSH entre 1 et 65535 (22 d'habitude).");
        }
        if (!UserPattern.IsMatch(User ?? ""))
        {
            list.Add("Compte SSH invalide (lettres, chiffres, « _ », « . » et « - »).");
        }
        if (Auth == SshAuthKind.Password && PasswordProtected.Length == 0)
        {
            list.Add("Indiquez le mot de passe SSH.");
        }
        if (Auth == SshAuthKind.PrivateKey && PrivateKeyProtected.Length == 0)
        {
            list.Add("Choisissez la clé privée SSH.");
        }
        if (!DirPattern.IsMatch(InstallDir ?? "") || InstallDir!.Contains("/.") || InstallDir.Contains("..")
            || SystemDirs.Any(d => InstallDir.StartsWith(d + "/", StringComparison.Ordinal)))
        {
            list.Add("Dossier du serveur invalide : un chemin comme /opt/valcombe, hors des dossiers du système.");
        }
        if (!AccountPattern.IsMatch(ServiceUser ?? "") || ServiceUser == "root")
        {
            list.Add("Compte du serveur invalide : minuscules, chiffres, « _ » et « - », jamais root.");
        }
        if (!ServicePattern.IsMatch(ServiceName ?? ""))
        {
            list.Add("Nom du service invalide : minuscules, chiffres, « _ » et « - ».");
        }
        foreach (var (port, label) in new[] { (MinecraftPort, "du jeu"), (VoicePort, "du chat vocal") })
        {
            if (port is < 1024 or > 65535)
            {
                list.Add($"Port {label} entre 1024 et 65535.");
            }
        }
        if (AdminPort != 0 && AdminPort is < 1024 or > 65535)
        {
            list.Add("Port de l'API d'administration entre 1024 et 65535 (ou 0 pour celui du mod).");
        }
        var ports = new[] { MinecraftPort, VoicePort, AdminPort }.Where(p => p != 0).ToList();
        if (ports.Distinct().Count() != ports.Count || ports.Contains(SshPort))
        {
            list.Add("Les ports du jeu, du chat vocal, de l'API et de SSH doivent être tous différents.");
        }
        if (MemoryMb is < 1024 or > 262144)
        {
            list.Add("Mémoire du serveur entre 1 024 et 262 144 Mo.");
        }
        return list;
    }

    /// <summary>Lève une erreur lisible si le profil a une faute.</summary>
    public void Validate()
    {
        var problems = Problems();
        if (problems.Count > 0)
        {
            throw new InvalidOperationException(string.Join(" ", problems));
        }
    }

    /// <summary>« root@vps.exemple.fr » (avec « :2222 » si le port SSH n'est pas 22).</summary>
    public string Display => $"{User}@{Host}" + (SshPort == 22 ? "" : $":{SshPort}");
}

/// <summary>Les serveurs loués enregistrés, dans ValcombeData/serveurs-loues.json (secrets chiffrés).</summary>
public static class VpsProfiles
{
    public static string FilePath => Path.Combine(Paths.DataDir, "serveurs-loues.json");

    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    private sealed class Store
    {
        public List<VpsProfile> Serveurs { get; set; } = new();
    }

    public static List<VpsProfile> Load(string? file = null)
    {
        file ??= FilePath;
        try
        {
            if (File.Exists(file))
            {
                return JsonSerializer.Deserialize<Store>(File.ReadAllText(file), Json)?.Serveurs ?? new();
            }
        }
        catch (JsonException)
        {
            // Fichier abîmé : gardé de côté, on repart d'une liste vide.
            File.Copy(file, file + ".illisible", overwrite: true);
        }
        return new();
    }

    public static void Save(List<VpsProfile> profiles, string? file = null)
    {
        file ??= FilePath;
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        string tmp = file + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(new Store { Serveurs = profiles }, Json));
        File.Move(tmp, file, overwrite: true);
    }

    /// <summary>Ajoute le profil, ou remplace celui de même Id.</summary>
    public static void Upsert(VpsProfile profile, string? file = null)
    {
        var all = Load(file);
        all.RemoveAll(p => p.Id == profile.Id);
        all.Add(profile);
        Save(all, file);
    }

    /// <summary>Oublie un serveur (ses secrets avec lui). Son empreinte d'hôte reste connue tant qu'on ne l'efface pas.</summary>
    public static void Remove(string id, string? file = null)
    {
        var all = Load(file);
        all.RemoveAll(p => p.Id == id);
        Save(all, file);
    }
}
