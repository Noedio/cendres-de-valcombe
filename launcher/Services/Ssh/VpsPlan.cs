using System.IO;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Valcombe.Launcher.Services.Ssh;

/// <summary>Un fichier de réglages préparé par le launcher, que le script place dans le dossier du serveur.</summary>
/// <param name="RelativePath">Chemin dans le dossier du serveur (« server.properties », « config/... »).</param>
/// <param name="StagedName">Nom du fichier dans l'envoi.</param>
/// <param name="Mode">Droits Unix en octal (« 640 »).</param>
/// <param name="Content">Contenu (UTF-8, fins de ligne Unix).</param>
public sealed record PreparedFile(string RelativePath, string StagedName, string Mode, string Content);

/// <summary>
/// Le plan d'installation envoyé au VPS avec install-valcombe.sh (valcombe-plan.tsv) : ce que le serveur doit
/// contenir pour la dernière version publiée. Il est calculé ici avec la même logique que le serveur local
/// (LocalServer : mods du serveur, lanceur Fabric ; Engine : Java de Mojang), puis le script n'a plus qu'à
/// télécharger et vérifier. Une ligne par entrée, champs séparés par des tabulations :
/// <code>
/// valcombe-plan   1
/// reglage         DOSSIER|COMPTE|SERVICE|PORT_JEU|PORT_VOIX|MEMOIRE_MO|PARE_FEU|VERSION|MINECRAFT|CHARGEUR|JAVA   valeur
/// java-rep        chemin
/// java-fichier    sha1   x|-   chemin   adresse        (Java de Mojang, fichier par fichier)
/// java-lien       chemin   cible
/// java-archive    sha256   adresse                     (Java d'Adoptium, une archive, pour les machines ARM)
/// fabric          adresse
/// mod             sha512   fichier.jar   adresse
/// fichier         chemin   nom-dans-l-envoi   droits
/// </code>
/// </summary>
public sealed class VpsPlan
{
    public const int Format = 1;

    /// <summary>Hôtes d'où le VPS télécharge (le script garde la même liste).</summary>
    public static readonly string[] Hosts =
        { "piston-data.mojang.com", "meta.fabricmc.net", "github.com", "objects.githubusercontent.com", "cdn.modrinth.com" };

    private static readonly Regex Segment = new(@"^[A-Za-z0-9._+@-]+$");
    private static readonly Regex JarName = new(@"^[A-Za-z0-9._+-]+\.jar$");
    private static readonly Regex Hex = new("^[0-9a-fA-F]+$");
    private static readonly Regex SafeUrl = new(@"^https://([A-Za-z0-9.-]+)/[A-Za-z0-9._~%/+=:@?&,-]*$");

    public string Text { get; }
    /// <summary>Dossier du Java dans java/ (composant de Mojang, ou « temurin-25-jre »).</summary>
    public string JavaName { get; }
    /// <summary>« Mojang » ou « Adoptium ».</summary>
    public string JavaSource { get; }
    public int JavaFileCount { get; }
    public IReadOnlyList<PackMod> Mods { get; }
    /// <summary>Taille de tout ce qui pourrait être téléchargé (Java, mods ; sans Fabric), en octets.</summary>
    public long DownloadBytes { get; }

    private VpsPlan(string text, string javaName, string javaSource, int javaFiles, IReadOnlyList<PackMod> mods, long bytes)
    {
        Text = text;
        JavaName = javaName;
        JavaSource = javaSource;
        JavaFileCount = javaFiles;
        Mods = mods;
        DownloadBytes = bytes;
    }

    /// <summary>
    /// Prépare le plan pour une version publiée, un profil de serveur, l'architecture du VPS (« x86_64 », « aarch64 »)
    /// et les fichiers de réglages préparés.
    /// </summary>
    public static async Task<VpsPlan> BuildAsync(Release release, VpsProfile profile, string arch,
        IReadOnlyList<PreparedFile> files, CancellationToken ct = default)
    {
        profile.Validate();
        var manifest = release.Manifest;
        var sb = new StringBuilder();
        void Line(params string[] fields)
        {
            foreach (string f in fields)
            {
                if (f.Length == 0 || f.Any(c => c is '\t' or '\n' or '\r'))
                {
                    throw new InvalidDataException("Valeur refusée dans le plan d'installation : " + string.Join(" ", fields));
                }
            }
            sb.Append(string.Join('\t', fields)).Append('\n');
        }

        Line("valcombe-plan", Format.ToString());
        Line("reglage", "DOSSIER", profile.InstallDir);
        Line("reglage", "COMPTE", profile.ServiceUser);
        Line("reglage", "SERVICE", profile.ServiceName);
        Line("reglage", "PORT_JEU", profile.MinecraftPort.ToString());
        Line("reglage", "PORT_VOIX", profile.VoicePort.ToString());
        Line("reglage", "MEMOIRE_MO", profile.MemoryMb.ToString());
        Line("reglage", "PARE_FEU", profile.OpenFirewall ? "oui" : "non");
        Line("reglage", "VERSION", Clean(release.Tag, "version"));
        Line("reglage", "MINECRAFT", Clean(manifest.Minecraft, "version de Minecraft"));
        Line("reglage", "CHARGEUR", Clean(manifest.Loader, "version de Fabric"));

        // Java : celui de Mojang (le même que sur le PC des joueurs) sur les machines x86 ; Mojang n'en publie pas
        // pour Linux ARM, où l'on prend celui d'Adoptium (Eclipse Temurin), de la même version majeure.
        var (component, major) = await Engine.JavaRequirementAsync(manifest.Minecraft, ct);
        string javaName;
        string javaSource;
        int javaFiles = 0;
        long bytes = 0;
        switch (NormalizeArch(arch))
        {
            case "x86_64":
            {
                javaName = Clean(component, "Java");
                javaSource = "Mojang";
                Line("reglage", "JAVA", javaName);
                var entries = await Engine.JavaRuntimeFilesAsync("linux", component, ct)
                    ?? throw new InvalidOperationException($"Mojang ne publie pas {component} pour Linux.");
                foreach (var (rel, entry) in entries.OrderBy(e => e.Key, StringComparer.Ordinal))
                {
                    CheckRelative(rel);
                    switch (entry!["type"]?.GetValue<string>())
                    {
                        case "directory":
                            Line("java-rep", rel);
                            break;
                        case "file":
                        {
                            var raw = entry["downloads"]!["raw"]!;
                            string sha1 = raw["sha1"]!.GetValue<string>();
                            string url = raw["url"]!.GetValue<string>();
                            CheckHash(sha1, 40);
                            CheckUrl(url);
                            Line("java-fichier", sha1.ToLowerInvariant(), entry["executable"]?.GetValue<bool>() == true ? "x" : "-", rel, url);
                            bytes += raw["size"]?.GetValue<long>() ?? 0;
                            javaFiles++;
                            break;
                        }
                        case "link":
                        {
                            string target = entry["target"]!.GetValue<string>();
                            CheckLink(rel, target);
                            Line("java-lien", rel, target);
                            break;
                        }
                    }
                }
                break;
            }
            case "aarch64":
            {
                javaName = $"temurin-{major}-jre";
                javaSource = "Adoptium";
                Line("reglage", "JAVA", javaName);
                var (url, sha256, size) = await AdoptiumAsync(major, "aarch64", ct);
                Line("java-archive", sha256, url);
                bytes += size;
                javaFiles = 1;
                break;
            }
            default:
                throw new InvalidOperationException($"Processeur du serveur non pris en charge : {arch} (x86_64 ou aarch64 attendus).");
        }

        // Lanceur du serveur Fabric : la même adresse que pour le serveur local.
        string fabric = await LocalServer.FabricServerJarUrlAsync(manifest, ct);
        CheckUrl(fabric);
        Line("fabric", fabric);

        // Mods du serveur : la même liste que le serveur local, vérifiée par la même empreinte SHA-512.
        var mods = LocalServer.ServerMods(manifest);
        if (mods.Count == 0)
        {
            throw new InvalidDataException($"La version {release.Tag} n'a aucun mod de serveur.");
        }
        foreach (var mod in mods)
        {
            if (!JarName.IsMatch(mod.FileName))
            {
                throw new InvalidDataException("Nom de fichier refusé dans le manifeste : " + mod.FileName);
            }
            if (!Http.IsSha512(mod.Sha512))
            {
                throw new InvalidDataException($"Empreinte absente ou invalide dans le manifeste : {mod.FileName}");
            }
            string url = Releases.Resolve(release, mod);
            if (!Http.IsTrustedUrl(url))
            {
                throw new InvalidDataException($"Adresse de téléchargement refusée : {mod.FileName}");
            }
            CheckUrl(url);
            Line("mod", mod.Sha512.ToLowerInvariant(), mod.FileName, url);
            bytes += mod.Size;
        }

        foreach (var file in files)
        {
            CheckRelative(file.RelativePath);
            if (!Segment.IsMatch(file.StagedName) || !Regex.IsMatch(file.Mode, "^[0-7]{3}$"))
            {
                throw new InvalidDataException("Fichier préparé refusé : " + file.StagedName);
            }
            Line("fichier", file.RelativePath, file.StagedName, file.Mode);
        }
        return new VpsPlan(sb.ToString(), javaName, javaSource, javaFiles, mods, bytes);
    }

    /// <summary>« x86_64 », « aarch64 », ou la valeur donnée telle quelle si elle n'est pas reconnue.</summary>
    public static string NormalizeArch(string arch) => arch.Trim() switch
    {
        "x86_64" or "amd64" or "x64" => "x86_64",
        "aarch64" or "arm64" or "armv8l" => "aarch64",
        var other => other,
    };

    /// <summary>La dernière JRE Temurin de cette version majeure pour Linux (adresse, SHA-256, taille).</summary>
    private static async Task<(string Url, string Sha256, long Size)> AdoptiumAsync(int major, string arch, CancellationToken ct)
    {
        string api = $"https://api.adoptium.net/v3/assets/latest/{major}/hotspot?architecture={arch}&image_type=jre&os=linux&vendor=eclipse";
        var list = JsonNode.Parse(await Http.Client.GetStringAsync(api, ct))?.AsArray();
        var package = list?.FirstOrDefault()?["binary"]?["package"]
            ?? throw new InvalidOperationException($"Adoptium ne publie pas de Java {major} pour Linux {arch}.");
        string url = package["link"]!.GetValue<string>();
        string sha256 = package["checksum"]!.GetValue<string>().ToLowerInvariant();
        CheckUrl(url);
        CheckHash(sha256, 64);
        return (url, sha256, package["size"]?.GetValue<long>() ?? 0);
    }

    private static string Clean(string value, string what)
    {
        if (!Regex.IsMatch(value, "^[A-Za-z0-9._+-]{1,40}$"))
        {
            throw new InvalidDataException($"Valeur refusée ({what}) : {value}");
        }
        return value;
    }

    private static void CheckRelative(string rel)
    {
        var parts = rel.Split('/');
        if (rel.Length == 0 || parts.Any(p => p is "" or "." or ".." || !Segment.IsMatch(p)))
        {
            throw new InvalidDataException("Chemin refusé dans le plan d'installation : " + rel);
        }
    }

    /// <summary>Un lien de Java doit rester dans le dossier de Java.</summary>
    private static void CheckLink(string rel, string target)
    {
        if (target.StartsWith('/') || !Regex.IsMatch(target, @"^[A-Za-z0-9._+@/-]+$"))
        {
            throw new InvalidDataException("Cible de lien refusée : " + target);
        }
        var stack = new Stack<string>(rel.Split('/').SkipLast(1));
        foreach (string part in target.Split('/'))
        {
            if (part == "..")
            {
                if (stack.Count == 0)
                {
                    throw new InvalidDataException("Lien qui sort du dossier de Java : " + rel);
                }
                stack.Pop();
            }
            else if (part is not ("" or "."))
            {
                stack.Push(part);
            }
        }
    }

    private static void CheckHash(string hash, int length)
    {
        if (hash.Length != length || !Hex.IsMatch(hash))
        {
            throw new InvalidDataException("Empreinte invalide dans le plan d'installation : " + hash);
        }
    }

    private static void CheckUrl(string url)
    {
        var m = SafeUrl.Match(url);
        if (!m.Success || !Hosts.Contains(m.Groups[1].Value, StringComparer.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("Adresse de téléchargement refusée : " + url);
        }
    }
}
