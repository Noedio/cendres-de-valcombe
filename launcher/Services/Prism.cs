using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Valcombe.Launcher.Services;

/// <summary>
/// Prism Launcher, en version portable, rangé dans ValcombeData/prism. Il gère la connexion
/// au compte Microsoft (gardée en mémoire après la première fois) et le lancement du jeu.
/// </summary>
public static class Prism
{
    private sealed class GhAsset
    {
        [JsonPropertyName("name")] public string Name { get; set; } = "";
        [JsonPropertyName("browser_download_url")] public string Url { get; set; } = "";
    }

    private sealed class GhRelease
    {
        [JsonPropertyName("tag_name")] public string Tag { get; set; } = "";
        [JsonPropertyName("assets")] public List<GhAsset> Assets { get; set; } = new();
    }

    public static bool IsInstalled => File.Exists(Paths.PrismExe);

    /// <summary>Télécharge la dernière version portable officielle de Prism (GitHub PrismLauncher).</summary>
    public static async Task InstallAsync(IProgress<(string, double)> progress, CancellationToken ct = default)
    {
        progress.Report(("Recherche de Prism Launcher…", 0));
        string json = await Http.Client.GetStringAsync(
            "https://api.github.com/repos/PrismLauncher/PrismLauncher/releases/latest", ct);
        var rel = JsonSerializer.Deserialize<GhRelease>(json) ?? throw new InvalidOperationException("Réponse GitHub illisible.");
        var asset = rel.Assets.FirstOrDefault(a =>
                a.Name.Contains("Windows-MSVC-Portable", StringComparison.OrdinalIgnoreCase)
                && a.Name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)
                && !a.Name.Contains("arm64", StringComparison.OrdinalIgnoreCase))
            ?? throw new InvalidOperationException("Aucune version portable de Prism pour Windows n'a été trouvée.");
        string zip = Path.Combine(Paths.CacheDir, asset.Name);
        await Http.DownloadAsync(asset.Url, zip, null,
            new Progress<double>(p => progress.Report(($"Téléchargement de Prism Launcher {rel.Tag}…", p))), ct);
        progress.Report(("Installation de Prism Launcher…", 1));
        string tmp = Paths.PrismDir + ".tmp";
        if (Directory.Exists(tmp))
        {
            Directory.Delete(tmp, true);
        }
        ZipFile.ExtractToDirectory(zip, tmp);
        // L'archive peut contenir un dossier racine : on remonte le contenu.
        string root = File.Exists(Path.Combine(tmp, "prismlauncher.exe")) ? tmp
            : Directory.GetDirectories(tmp).First(d => File.Exists(Path.Combine(d, "prismlauncher.exe")));
        Directory.CreateDirectory(Paths.PrismDir);
        foreach (string f in Directory.GetFiles(root, "*", SearchOption.AllDirectories))
        {
            string dest = Path.Combine(Paths.PrismDir, Path.GetRelativePath(root, f));
            Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
            File.Copy(f, dest, true);
        }
        Directory.Delete(tmp, true);
        File.WriteAllText(Path.Combine(Paths.PrismDir, "portable.txt"), "");
        WriteDefaults();
    }

    /// <summary>Réglages de départ de Prism : français, Java téléchargé automatiquement.</summary>
    private static void WriteDefaults()
    {
        string cfg = Path.Combine(Paths.PrismDir, "prismlauncher.cfg");
        if (File.Exists(cfg))
        {
            return;
        }
        File.WriteAllText(cfg, string.Join("\n",
            "Language=fr",
            "AutomaticJavaDownload=true",
            "AutomaticJavaSwitch=true",
            "CloseAfterLaunch=true",
            "ShowConsole=false",
            ""));
    }

    /// <summary>Ouvre Prism normalement (pour ajouter ou gérer le compte Microsoft).</summary>
    public static Process? OpenAccounts()
    {
        return Process.Start(new ProcessStartInfo(Paths.PrismExe) { WorkingDirectory = Paths.PrismDir, UseShellExecute = false });
    }

    /// <summary>Nom du profil Minecraft enregistré dans Prism, ou null.</summary>
    public static string? AccountName()
    {
        string accounts = Path.Combine(Paths.PrismDir, "accounts.json");
        if (!File.Exists(accounts))
        {
            return null;
        }
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(accounts));
            if (!doc.RootElement.TryGetProperty("accounts", out var list))
            {
                return null;
            }
            foreach (var a in list.EnumerateArray())
            {
                if (a.TryGetProperty("profile", out var prof) && prof.TryGetProperty("name", out var n))
                {
                    return n.GetString();
                }
            }
        }
        catch (JsonException)
        {
        }
        return null;
    }

    /// <summary>
    /// Connexion guidée : ouvre Prism, attend qu'un compte apparaisse, puis referme Prism.
    /// Renvoie le nom du compte, ou null si la fenêtre a été fermée sans connexion.
    /// </summary>
    public static async Task<string?> ConnectAsync(CancellationToken ct = default)
    {
        using var proc = OpenAccounts();
        while (!ct.IsCancellationRequested)
        {
            await Task.Delay(1500, ct);
            string? name = HasAccount() ? AccountName() ?? "compte Microsoft" : null;
            if (name != null)
            {
                await Task.Delay(1500, ct); // laisser Prism finir d'écrire
                try
                {
                    if (proc is { HasExited: false })
                    {
                        proc.CloseMainWindow();
                        if (!proc.WaitForExit(3000))
                        {
                            proc.Kill();
                        }
                    }
                }
                catch (InvalidOperationException)
                {
                }
                return name;
            }
            if (proc == null || proc.HasExited)
            {
                return null;
            }
        }
        return null;
    }

    /// <summary>Indique si un compte Microsoft est enregistré dans Prism.</summary>
    public static bool HasAccount()
    {
        string accounts = Path.Combine(Paths.PrismDir, "accounts.json");
        if (!File.Exists(accounts))
        {
            return false;
        }
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(accounts));
            return doc.RootElement.TryGetProperty("accounts", out var list) && list.GetArrayLength() > 0;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    /// <summary>Lance l'instance Valcombe et rejoint le serveur.</summary>
    public static void Launch(string server)
    {
        var psi = new ProcessStartInfo(Paths.PrismExe) { WorkingDirectory = Paths.PrismDir, UseShellExecute = false };
        psi.ArgumentList.Add("--launch");
        psi.ArgumentList.Add(Paths.InstanceId);
        if (!string.IsNullOrWhiteSpace(server))
        {
            psi.ArgumentList.Add("--server");
            psi.ArgumentList.Add(server.Trim());
        }
        Process.Start(psi);
    }
}
