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

        await SyncModsAsync(release, Paths.ModsDir, progress, repair, ct);
        PrepareOptions(config.Minecraft, Paths.GameDir);
        await File.WriteAllTextAsync(InstalledVersionFile, release.Tag, ct);
        progress.Report(("Prêt à jouer.", 1));
    }

    /// <summary>Télécharge les mods manquants ou modifiés, retire ceux qui ne sont plus dans la version.</summary>
    public static async Task SyncModsAsync(Release release, string modsDir, IProgress<(string, double)> progress,
        bool repair, CancellationToken ct = default)
    {
        var m = release.Manifest;
        Directory.CreateDirectory(modsDir);
        var wanted = new HashSet<string>(m.Mods.Select(x => x.FileName), StringComparer.OrdinalIgnoreCase);
        foreach (string f in Directory.GetFiles(modsDir, "*.jar"))
        {
            if (!wanted.Contains(Path.GetFileName(f)))
            {
                File.Delete(f);
            }
        }
        for (int i = 0; i < m.Mods.Count; i++)
        {
            var mod = m.Mods[i];
            string target = Path.Combine(modsDir, mod.FileName);
            if (File.Exists(target) && !repair && Http.Sha512(target) == mod.Sha512.ToLowerInvariant())
            {
                continue;
            }
            int index = i;
            await Http.DownloadAsync(Releases.Resolve(release, mod), target, mod.Sha512,
                new Progress<double>(p => progress.Report(($"Téléchargement : {mod.Name}", (index + p) / m.Mods.Count))), ct);
        }
    }

    /// <summary>
    /// Avant un lancement : récupère les réglages du joueur (profil ou autre moteur) ; s'il n'en a
    /// aucun (toute première partie), écrit ceux du launcher. Les réglages faits en jeu ne sont
    /// plus jamais écrasés.
    /// </summary>
    public static void PrepareOptions(MinecraftOptions o, string gameDir)
    {
        Profile.Sync(gameDir);
        if (!File.Exists(Path.Combine(gameDir, "options.txt")))
        {
            ApplyOptions(o, gameDir);
            Profile.Sync(gameDir);
        }
    }

    /// <summary>Relit les réglages du jeu (les plus récents) pour les montrer dans le launcher.</summary>
    public static void ReadOptions(MinecraftOptions o)
    {
        string? file = Profile.NewestOptions();
        if (file == null)
        {
            return;
        }
        var map = new Dictionary<string, string>();
        foreach (string line in File.ReadAllLines(file))
        {
            int i = line.IndexOf(':');
            if (i > 0)
            {
                map[line[..i]] = line[(i + 1)..];
            }
        }
        var inv = CultureInfo.InvariantCulture;
        if (map.TryGetValue("renderDistance", out var rd) && int.TryParse(rd, inv, out int r)) o.RenderDistance = r;
        if (map.TryGetValue("guiScale", out var gs) && int.TryParse(gs, inv, out int g)) o.GuiScale = g;
        if (map.TryGetValue("fullscreen", out var fs)) o.Fullscreen = fs == "true";
        if (map.TryGetValue("maxFps", out var mf) && int.TryParse(mf, inv, out int f)) o.MaxFps = f;
        if (map.TryGetValue("fov", out var fv) && double.TryParse(fv, NumberStyles.Float, inv, out double fov))
            o.Fov = (int)Math.Round(70 + fov * 40);
        if (map.TryGetValue("soundCategory_master", out var mv) && double.TryParse(mv, NumberStyles.Float, inv, out double v))
            o.MasterVolume = (int)Math.Round(v * 100);
        if (map.TryGetValue("lang", out var lang) && lang.Length > 0) o.Language = lang;
    }

    /// <summary>Écrit les réglages choisis dans options.txt (instance Prism par défaut), en gardant les autres lignes.</summary>
    public static void ApplyOptions(MinecraftOptions o, string? gameDir = null)
    {
        gameDir ??= Paths.GameDir;
        Directory.CreateDirectory(gameDir);
        string file = Path.Combine(gameDir, "options.txt");
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
