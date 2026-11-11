using System.Text.Json;
using System.Text.Json.Serialization;

namespace Valcombe.Launcher.Services;

/// <summary>Un mod à installer dans l'instance.</summary>
public sealed class PackMod
{
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("filename")] public string FileName { get; set; } = "";
    /// <summary>Adresse de téléchargement, ou « asset:nom » pour un fichier joint à la version GitHub.</summary>
    [JsonPropertyName("url")] public string Url { get; set; } = "";
    [JsonPropertyName("sha512")] public string Sha512 { get; set; } = "";
    [JsonPropertyName("size")] public long Size { get; set; }
    /// <summary>Pour les « extras » : dossier du jeu (resourcepacks, shaderpacks).</summary>
    [JsonPropertyName("folder")] public string Folder { get; set; } = "";
    /// <summary>Pour les « extras » : réglage qui les active (« hd » : textures, « shaders » : ombres et lumières).</summary>
    [JsonPropertyName("group")] public string Group { get; set; } = "";
}

public sealed class NewsItem
{
    [JsonPropertyName("date")] public string Date { get; set; } = "";
    [JsonPropertyName("title")] public string Title { get; set; } = "";
    [JsonPropertyName("text")] public string Text { get; set; } = "";
}

/// <summary>Launcher annoncé par la version publiée (mise à jour automatique).</summary>
public sealed class LauncherInfo
{
    [JsonPropertyName("version")] public string Version { get; set; } = "";
    [JsonPropertyName("filename")] public string FileName { get; set; } = "ValcombeLauncher.exe";
    [JsonPropertyName("sha512")] public string Sha512 { get; set; } = "";
    [JsonPropertyName("size")] public long Size { get; set; }
}

/// <summary>Manifeste joint à chaque version : versions de jeu, mods, serveur, nouvelles.</summary>
public sealed class PackManifest
{
    [JsonPropertyName("version")] public string Version { get; set; } = "";
    [JsonPropertyName("minecraft")] public string Minecraft { get; set; } = "";
    [JsonPropertyName("loader")] public string Loader { get; set; } = "";
    [JsonPropertyName("server")] public string Server { get; set; } = "";
    [JsonPropertyName("mods")] public List<PackMod> Mods { get; set; } = new();
    /// <summary>Packs de textures et de shaders (hors du dossier mods, ignorés des anciens launchers).</summary>
    [JsonPropertyName("extras")] public List<PackMod> Extras { get; set; } = new();
    [JsonPropertyName("news")] public List<NewsItem> News { get; set; } = new();
    [JsonPropertyName("launcher")] public LauncherInfo? Launcher { get; set; }
}

/// <summary>La dernière version publiée sur GitHub et son manifeste.</summary>
public sealed record Release(string Tag, string Notes, Dictionary<string, string> Assets, PackManifest Manifest);

public static class Releases
{
    public const string ManifestName = "valcombe-pack.json";

    private sealed class GhAsset
    {
        [JsonPropertyName("name")] public string Name { get; set; } = "";
        [JsonPropertyName("browser_download_url")] public string Url { get; set; } = "";
    }

    private sealed class GhRelease
    {
        [JsonPropertyName("tag_name")] public string Tag { get; set; } = "";
        [JsonPropertyName("body")] public string? Body { get; set; }
        [JsonPropertyName("assets")] public List<GhAsset> Assets { get; set; } = new();
    }

    public static async Task<Release> LatestAsync(string repository, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(repository) || !repository.Contains('/'))
        {
            throw new InvalidOperationException("Indiquez le dépôt GitHub du mod dans les Réglages (propriétaire/nom).");
        }
        string json = await Http.Client.GetStringAsync($"https://api.github.com/repos/{repository.Trim()}/releases/latest", ct);
        var rel = JsonSerializer.Deserialize<GhRelease>(json) ?? throw new InvalidOperationException("Réponse GitHub illisible.");
        var assets = rel.Assets.ToDictionary(a => a.Name, a => a.Url);
        if (!assets.TryGetValue(ManifestName, out var manifestUrl))
        {
            throw new InvalidOperationException($"La version {rel.Tag} ne contient pas {ManifestName}.");
        }
        var manifest = JsonSerializer.Deserialize<PackManifest>(await Http.Client.GetStringAsync(manifestUrl, ct))
            ?? throw new InvalidOperationException("Manifeste illisible.");
        return new Release(rel.Tag, rel.Body ?? "", assets, manifest);
    }

    /// <summary>Adresse réelle d'un mod (résout « asset:nom »).</summary>
    public static string Resolve(Release release, PackMod mod)
    {
        if (mod.Url.StartsWith("asset:", StringComparison.Ordinal))
        {
            string name = mod.Url["asset:".Length..];
            return release.Assets.TryGetValue(name, out var u) ? u
                : throw new InvalidOperationException($"Fichier {name} absent de la version {release.Tag}.");
        }
        return mod.Url;
    }
}
