using System.IO;

namespace Valcombe.Launcher.Services;

/// <summary>
/// Reprend les touches du Minecraft habituel du joueur (launcher officiel, CurseForge, Modrinth,
/// Prism, autres launchers) : le réglage le plus récent trouvé sur le PC. Les touches propres à
/// Valcombe (J, H, G, K) restent ; une touche habituelle qui tomberait dessus est laissée libre.
/// </summary>
public static class Keys
{
    private const string Unbound = "key.keyboard.unknown";

    /// <summary>Le options.txt du Minecraft habituel (le plus récent avec des touches), ou null.</summary>
    public static string? FindUsual()
    {
        string appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var ours = new[] { Engine.Root, Paths.DataDir, Profile.Dir }
            .Select(p => Path.GetFullPath(p).TrimEnd('\\') + "\\").ToList();
        var files = new List<string> { Path.Combine(appData, ".minecraft", "options.txt") };
        foreach (string root in new[]
        {
            Path.Combine(home, "curseforge", "minecraft", "Instances"),
            Path.Combine(appData, "ModrinthApp", "profiles"),
            Path.Combine(appData, "com.modrinth.theseus", "profiles"),
            Path.Combine(appData, "PrismLauncher", "instances"),
            Path.Combine(appData, ".tlauncher"),
            appData,
        })
        {
            files.AddRange(Search(root, root == appData ? 3 : 4));
        }
        return files.Distinct(StringComparer.OrdinalIgnoreCase)
            .Where(File.Exists)
            .Where(f => !ours.Any(o => Path.GetFullPath(f).StartsWith(o, StringComparison.OrdinalIgnoreCase)))
            .Where(f => Read(f).Count >= 30)
            .OrderByDescending(File.GetLastWriteTimeUtc)
            .FirstOrDefault();
    }

    private static IEnumerable<string> Search(string dir, int depth)
    {
        if (depth < 0 || !Directory.Exists(dir))
        {
            yield break;
        }
        string here = Path.Combine(dir, "options.txt");
        if (File.Exists(here))
        {
            yield return here;
        }
        string[] subs;
        try
        {
            subs = Directory.GetDirectories(dir);
        }
        catch (Exception)
        {
            yield break;
        }
        foreach (string sub in subs)
        {
            string name = Path.GetFileName(sub);
            // On ne descend pas dans les dossiers lourds ou sans rapport.
            if (name is "assets" or "libraries" or "versions" or "mods" or "saves" or "logs" or "resourcepacks"
                or "shaderpacks" or "screenshots" or "crash-reports" or "Microsoft" or "Mozilla" or "Code" or "npm")
            {
                continue;
            }
            foreach (string f in Search(sub, depth - 1))
            {
                yield return f;
            }
        }
    }

    private static Dictionary<string, string> Read(string file)
    {
        var map = new Dictionary<string, string>();
        try
        {
            foreach (string line in File.ReadLines(file))
            {
                int i = line.IndexOf(':');
                if (line.StartsWith("key_", StringComparison.Ordinal) && i > 0)
                {
                    map[line[..i]] = line[(i + 1)..];
                }
            }
        }
        catch (IOException)
        {
        }
        return map;
    }

    /// <summary>
    /// Recopie les touches habituelles dans le profil et les dossiers de jeu de Valcombe.
    /// Renvoie les changements (« action : ancienne → nouvelle »).
    /// </summary>
    public static List<string> Import(string usualFile)
    {
        var usual = Read(usualFile);
        var changes = new List<string>();
        var places = new[] { Profile.Dir }.Concat(Profile.GameDirs)
            .Select(d => Path.Combine(d, "options.txt")).Where(File.Exists).ToList();
        foreach (string file in places)
        {
            var lines = File.ReadAllLines(file).ToList();
            var mine = Read(file);
            var valcombe = mine.Where(e => e.Key.StartsWith("key_key.valcombe.", StringComparison.Ordinal) && e.Value != Unbound)
                .Select(e => e.Value).ToHashSet();
            if (valcombe.Count == 0)
            {
                // Toute première partie : les touches de Valcombe n'y sont pas encore, ce sont celles d'origine.
                valcombe = new HashSet<string> { "key.keyboard.j", "key.keyboard.h", "key.keyboard.g", "key.keyboard.k" };
            }
            // Touches absentes (toute première partie) : on les ajoute, Minecraft complètera le reste.
            foreach (var (key, value) in usual)
            {
                if (!mine.ContainsKey(key) && key.StartsWith("key_key.", StringComparison.Ordinal)
                    && !key.StartsWith("key_key.valcombe.", StringComparison.Ordinal))
                {
                    lines.Add(key + ":" + value);
                    mine[key] = value;
                }
            }
            for (int i = 0; i < lines.Count; i++)
            {
                int c = lines[i].IndexOf(':');
                if (!lines[i].StartsWith("key_", StringComparison.Ordinal) || c < 0)
                {
                    continue;
                }
                string key = lines[i][..c];
                string old = lines[i][(c + 1)..];
                if (key.StartsWith("key_key.valcombe.", StringComparison.Ordinal) || !usual.TryGetValue(key, out string? value))
                {
                    continue;
                }
                if (valcombe.Contains(value))
                {
                    value = Unbound; // déjà prise par une touche de Valcombe
                }
                if (value != old)
                {
                    lines[i] = key + ":" + value;
                    string change = $"{key["key_key.".Length..]} : {Pretty(old)} → {Pretty(value)}";
                    if (!changes.Contains(change))
                    {
                        changes.Add(change);
                    }
                }
            }
            File.WriteAllLines(file, lines);
        }
        return changes;
    }

    private static string Pretty(string key) => key == Unbound ? "aucune"
        : key.Replace("key.keyboard.", "").Replace("key.mouse.", "souris ").ToUpperInvariant();
}
