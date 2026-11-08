using System.Globalization;
using System.IO;
using System.Text;
using System.Text.Json;

namespace Valcombe.Launcher.Services;

/// <summary>L'instance Prism « valcombe » : version du jeu, Fabric, mods, options.</summary>
public static class Instance
{
    public static string InstalledVersionFile => Path.Combine(Paths.InstanceDir, "valcombe-version.txt");

    public static string? InstalledVersion =>
        File.Exists(InstalledVersionFile) ? File.ReadAllText(InstalledVersionFile).Trim() : null;

    /// <summary>Crée ou met à jour l'instance pour la version publiée.</summary>
    public static async Task SyncAsync(Release release, LauncherConfig config, IProgress<(string, double)> progress,
        bool repair, CancellationToken ct = default)
    {
        var m = release.Manifest;
        Directory.CreateDirectory(Paths.ModsDir);

        // Description de l'instance pour Prism
        var cfg = new StringBuilder()
            .AppendLine("[General]")
            .AppendLine("ConfigVersion=1.2")
            .AppendLine("InstanceType=OneSix")
            .AppendLine("name=Les Cendres de Valcombe")
            .AppendLine("iconKey=default")
            .AppendLine("OverrideMemory=true")
            .AppendLine("MinMemAlloc=1024")
            .AppendLine($"MaxMemAlloc={config.MaxMemoryMb}")
            .AppendLine("OverrideJavaArgs=false");
        await File.WriteAllTextAsync(Path.Combine(Paths.InstanceDir, "instance.cfg"), cfg.ToString(), ct);
        var pack = new
        {
            formatVersion = 1,
            components = new object[]
            {
                new { uid = "net.minecraft", version = m.Minecraft, important = true },
                new { uid = "net.fabricmc.fabric-loader", version = m.Loader },
            },
        };
        await File.WriteAllTextAsync(Path.Combine(Paths.InstanceDir, "mmc-pack.json"),
            JsonSerializer.Serialize(pack, new JsonSerializerOptions { WriteIndented = true }), ct);

        // Mods : on télécharge ce qui manque ou a changé, on retire ce qui n'est plus dans la version.
        var wanted = new HashSet<string>(m.Mods.Select(x => x.FileName), StringComparer.OrdinalIgnoreCase);
        foreach (string f in Directory.GetFiles(Paths.ModsDir, "*.jar"))
        {
            if (!wanted.Contains(Path.GetFileName(f)))
            {
                File.Delete(f);
            }
        }
        for (int i = 0; i < m.Mods.Count; i++)
        {
            var mod = m.Mods[i];
            string target = Path.Combine(Paths.ModsDir, mod.FileName);
            bool ok = File.Exists(target) && !repair && Http.Sha512(target) == mod.Sha512.ToLowerInvariant();
            if (ok)
            {
                continue;
            }
            int index = i;
            await Http.DownloadAsync(Releases.Resolve(release, mod), target, mod.Sha512,
                new Progress<double>(p => progress.Report(($"Téléchargement : {mod.Name}", (index + p) / m.Mods.Count))), ct);
        }
        ApplyOptions(config.Minecraft);
        await File.WriteAllTextAsync(InstalledVersionFile, release.Tag, ct);
        progress.Report(("Prêt à jouer.", 1));
    }

    /// <summary>Écrit les réglages choisis dans options.txt, en gardant les autres lignes du joueur.</summary>
    public static void ApplyOptions(MinecraftOptions o)
    {
        Directory.CreateDirectory(Paths.GameDir);
        string file = Path.Combine(Paths.GameDir, "options.txt");
        var lines = File.Exists(file) ? File.ReadAllLines(file).ToList() : new List<string>();
        var values = new Dictionary<string, string>
        {
            ["renderDistance"] = o.RenderDistance.ToString(CultureInfo.InvariantCulture),
            ["guiScale"] = o.GuiScale.ToString(CultureInfo.InvariantCulture),
            ["fullscreen"] = o.Fullscreen ? "true" : "false",
            ["maxFps"] = o.MaxFps.ToString(CultureInfo.InvariantCulture),
            ["fov"] = ((o.Fov - 70) / 40.0).ToString("0.0###", CultureInfo.InvariantCulture),
            ["soundCategory_master"] = (o.MasterVolume / 100.0).ToString("0.0#", CultureInfo.InvariantCulture),
            ["lang"] = o.Language,
        };
        foreach (var (key, value) in values)
        {
            int idx = lines.FindIndex(l => l.StartsWith(key + ":", StringComparison.Ordinal));
            string line = key + ":" + value;
            if (idx >= 0)
            {
                lines[idx] = line;
            }
            else
            {
                lines.Add(line);
            }
        }
        File.WriteAllLines(file, lines);
    }

    public static void Repair()
    {
        if (Directory.Exists(Paths.ModsDir))
        {
            Directory.Delete(Paths.ModsDir, true);
        }
        if (File.Exists(InstalledVersionFile))
        {
            File.Delete(InstalledVersionFile);
        }
    }
}
