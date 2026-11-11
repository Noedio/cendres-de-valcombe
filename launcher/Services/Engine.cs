using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text.Json.Nodes;

namespace Valcombe.Launcher.Services;

/// <summary>
/// Notre propre moteur de lancement : télécharge Minecraft (jeu, bibliothèques, ressources),
/// le Java officiel de Mojang et Fabric, puis lance le jeu directement sur le serveur.
/// Tout est vérifié par empreinte et rangé dans ValcombeData/jeu.
/// </summary>
public sealed class Engine
{
    private const string VersionManifest = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private const string JavaRuntimes =
        "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    public static string Root => Path.Combine(Paths.DataDir, "jeu");
    private static string Libraries => Path.Combine(Root, "libraries");
    private static string Assets => Path.Combine(Root, "assets");
    private static string Versions => Path.Combine(Root, "versions");
    private static string Runtimes => Path.Combine(Root, "runtime");
    public static string GameDir => Path.Combine(Root, "instance");
    public static string ModsDir => Path.Combine(GameDir, "mods");

    /// <summary>Plan de lancement : ce qu'il faut télécharger et la ligne de commande.</summary>
    public sealed class Plan
    {
        public string MinecraftVersion = "";
        public string FabricId = "";
        public string MainClass = "";
        public string AssetIndex = "";
        public string JavaComponent = "";
        public string JavaExe = "";
        public readonly List<(string Url, string Path, string? Sha1)> Files = new();
        public readonly List<string> Classpath = new();
        public JsonArray VanillaGameArgs = new();
        public JsonArray VanillaJvmArgs = new();
        public JsonArray FabricGameArgs = new();
        public JsonArray FabricJvmArgs = new();
    }

    private static async Task<JsonNode> GetJsonAsync(string url, CancellationToken ct) =>
        JsonNode.Parse(await Http.Client.GetStringAsync(url, ct))!;

    // ------------------------------------------------------------------
    // Règles (bibliothèques et arguments réservés à certains systèmes)
    // ------------------------------------------------------------------
    private static bool Allowed(JsonNode? rules, bool quickPlay)
    {
        if (rules is not JsonArray list)
        {
            return true;
        }
        bool allowed = false;
        foreach (var rule in list)
        {
            bool match = true;
            if (rule!["os"] is JsonObject os)
            {
                string? name = os["name"]?.GetValue<string>();
                string? arch = os["arch"]?.GetValue<string>();
                match &= name == null || name == "windows";
                match &= arch == null || arch == "x86_64" || (arch == "x86" && false);
            }
            if (rule["features"] is JsonObject features)
            {
                foreach (var (key, value) in features)
                {
                    bool have = key == "is_quick_play_multiplayer" && quickPlay;
                    match &= value!.GetValue<bool>() == have;
                }
            }
            if (match)
            {
                allowed = rule["action"]!.GetValue<string>() == "allow";
            }
        }
        return allowed;
    }

    private static string MavenPath(string coords)
    {
        string[] p = coords.Split(':');
        string classifier = p.Length > 3 ? "-" + p[3] : "";
        return $"{p[0].Replace('.', '/')}/{p[1]}/{p[2]}/{p[1]}-{p[2]}{classifier}.jar";
    }

    private static string LibraryKey(string coords)
    {
        string[] p = coords.Split(':');
        return p.Length > 3 ? $"{p[0]}:{p[1]}:{p[3]}" : $"{p[0]}:{p[1]}";
    }

    // ------------------------------------------------------------------
    // Préparation
    // ------------------------------------------------------------------
    public static async Task<Plan> PrepareAsync(string mcVersion, string loaderVersion, CancellationToken ct = default)
    {
        var plan = new Plan { MinecraftVersion = mcVersion };
        var manifest = await GetJsonAsync(VersionManifest, ct);
        string? versionUrl = manifest["versions"]!.AsArray()
            .FirstOrDefault(v => v!["id"]!.GetValue<string>() == mcVersion)?["url"]?.GetValue<string>();
        if (versionUrl == null)
        {
            throw new InvalidOperationException($"Version de Minecraft inconnue : {mcVersion}");
        }
        var version = await GetJsonAsync(versionUrl, ct);

        // Jeu
        var client = version["downloads"]!["client"]!;
        string clientJar = Path.Combine(Versions, mcVersion, mcVersion + ".jar");
        plan.Files.Add((client["url"]!.GetValue<string>(), clientJar, client["sha1"]!.GetValue<string>()));

        // Fabric (ses bibliothèques passent avant celles du jeu en cas de doublon)
        var fabric = await GetJsonAsync(
            $"https://meta.fabricmc.net/v2/versions/loader/{mcVersion}/{loaderVersion}/profile/json", ct);
        plan.FabricId = fabric["id"]!.GetValue<string>();
        plan.MainClass = fabric["mainClass"]!.GetValue<string>();
        var seen = new HashSet<string>();
        foreach (var lib in fabric["libraries"]!.AsArray())
        {
            string name = lib!["name"]!.GetValue<string>();
            string rel = MavenPath(name);
            string path = Path.Combine(Libraries, rel);
            string baseUrl = lib["url"]?.GetValue<string>() ?? "https://maven.fabricmc.net/";
            plan.Files.Add((baseUrl.TrimEnd('/') + "/" + rel, path, lib["sha1"]?.GetValue<string>()));
            plan.Classpath.Add(path);
            seen.Add(LibraryKey(name));
        }
        foreach (var lib in version["libraries"]!.AsArray())
        {
            if (!Allowed(lib!["rules"], false))
            {
                continue;
            }
            string name = lib["name"]!.GetValue<string>();
            var artifact = lib["downloads"]?["artifact"];
            if (artifact == null || !seen.Add(LibraryKey(name)))
            {
                continue;
            }
            string path = Path.Combine(Libraries, artifact["path"]!.GetValue<string>());
            plan.Files.Add((artifact["url"]!.GetValue<string>(), path, artifact["sha1"]?.GetValue<string>()));
            plan.Classpath.Add(path);
        }
        plan.Classpath.Add(clientJar);

        // Ressources (sons, textures, langues)
        var assetIndex = version["assetIndex"]!;
        plan.AssetIndex = assetIndex["id"]!.GetValue<string>();
        string indexPath = Path.Combine(Assets, "indexes", plan.AssetIndex + ".json");
        await Http.DownloadAsync(assetIndex["url"]!.GetValue<string>(), indexPath, null, null, ct);
        var objects = JsonNode.Parse(await File.ReadAllTextAsync(indexPath, ct))!["objects"]!.AsObject();
        foreach (var (_, obj) in objects)
        {
            string hash = obj!["hash"]!.GetValue<string>();
            string sub = hash[..2] + "/" + hash;
            plan.Files.Add(("https://resources.download.minecraft.net/" + sub, Path.Combine(Assets, "objects", sub), hash));
        }

        // Java officiel de Mojang
        plan.JavaComponent = version["javaVersion"]?["component"]?.GetValue<string>() ?? "java-runtime-delta";
        var runtimes = await GetJsonAsync(JavaRuntimes, ct);
        var entry = runtimes["windows-x64"]?[plan.JavaComponent]?.AsArray().FirstOrDefault()
            ?? throw new InvalidOperationException($"Java {plan.JavaComponent} indisponible pour Windows.");
        var javaFiles = (await GetJsonAsync(entry["manifest"]!["url"]!.GetValue<string>(), ct))["files"]!.AsObject();
        string runtimeDir = Path.Combine(Runtimes, plan.JavaComponent);
        foreach (var (rel, f) in javaFiles)
        {
            if (f!["type"]!.GetValue<string>() == "file")
            {
                var raw = f["downloads"]!["raw"]!;
                plan.Files.Add((raw["url"]!.GetValue<string>(), Path.Combine(runtimeDir, rel), raw["sha1"]!.GetValue<string>()));
            }
        }
        plan.JavaExe = Path.Combine(runtimeDir, "bin", "javaw.exe");

        plan.VanillaGameArgs = version["arguments"]?["game"]?.AsArray() ?? new JsonArray();
        plan.VanillaJvmArgs = version["arguments"]?["jvm"]?.AsArray() ?? new JsonArray();
        plan.FabricGameArgs = fabric["arguments"]?["game"]?.AsArray() ?? new JsonArray();
        plan.FabricJvmArgs = fabric["arguments"]?["jvm"]?.AsArray() ?? new JsonArray();
        return plan;
    }

    // ------------------------------------------------------------------
    // Téléchargement (en parallèle, empreintes vérifiées)
    // ------------------------------------------------------------------
    private static bool Valid(string path, string? sha1)
    {
        if (!File.Exists(path))
        {
            return false;
        }
        if (sha1 == null)
        {
            return true;
        }
        using var s = File.OpenRead(path);
        return Convert.ToHexString(SHA1.HashData(s)).Equals(sha1, StringComparison.OrdinalIgnoreCase);
    }

    public static async Task DownloadAsync(Plan plan, IProgress<(string, double)> progress, CancellationToken ct = default)
    {
        var todo = plan.Files.Where(f => !Valid(f.Path, f.Sha1)).ToList();
        int done = 0;
        await Parallel.ForEachAsync(todo, new ParallelOptions { MaxDegreeOfParallelism = 12, CancellationToken = ct },
            async (f, token) =>
            {
                await Http.DownloadAsync(f.Url, f.Path, null, null, token);
                if (!Valid(f.Path, f.Sha1))
                {
                    File.Delete(f.Path);
                    throw new InvalidDataException("Fichier corrompu : " + Path.GetFileName(f.Path));
                }
                int n = Interlocked.Increment(ref done);
                if (n % 25 == 0 || n == todo.Count)
                {
                    string speed = Http.SpeedText();
                    progress.Report(($"Téléchargement du jeu : {n} / {todo.Count} fichiers" + (speed.Length > 0 ? $" ({speed})" : ""),
                        (double)n / todo.Count));
                }
            });
    }

    // ------------------------------------------------------------------
    // Lancement
    // ------------------------------------------------------------------
    /// <summary>Construit la ligne de commande (le jeton n'est jamais écrit sur le disque).</summary>
    public static List<string> BuildArguments(Plan plan, MinecraftAccount account, string clientId, int maxMemoryMb, string server)
    {
        bool quickPlay = !string.IsNullOrWhiteSpace(server);
        var vars = new Dictionary<string, string>
        {
            ["auth_player_name"] = account.Name,
            ["version_name"] = plan.FabricId,
            ["game_directory"] = GameDir,
            ["assets_root"] = Assets,
            ["assets_index_name"] = plan.AssetIndex,
            ["auth_uuid"] = account.Uuid,
            ["auth_access_token"] = account.AccessToken,
            ["clientid"] = clientId,
            ["auth_xuid"] = account.Xuid,
            ["user_type"] = "msa",
            ["version_type"] = "release",
            ["natives_directory"] = Path.Combine(Versions, plan.MinecraftVersion, "natives"),
            ["launcher_name"] = "valcombe",
            ["launcher_version"] = "0.2",
            ["classpath"] = string.Join(";", plan.Classpath),
            ["classpath_separator"] = ";",
            ["library_directory"] = Libraries,
            ["quickPlayMultiplayer"] = server.Trim(),
        };
        string Fill(string s)
        {
            foreach (var (k, v) in vars)
            {
                s = s.Replace("${" + k + "}", v);
            }
            return s;
        }
        IEnumerable<string> Expand(JsonArray args)
        {
            foreach (var a in args)
            {
                if (a is JsonValue v)
                {
                    yield return Fill(v.GetValue<string>());
                }
                else if (a is JsonObject o && Allowed(o["rules"], quickPlay))
                {
                    if (o["value"] is JsonArray many)
                    {
                        foreach (var x in many)
                        {
                            yield return Fill(x!.GetValue<string>());
                        }
                    }
                    else
                    {
                        yield return Fill(o["value"]!.GetValue<string>());
                    }
                }
            }
        }
        var result = new List<string> { $"-Xmx{maxMemoryMb}m", "-Xms1024m" };
        result.AddRange(Expand(plan.VanillaJvmArgs));
        result.AddRange(Expand(plan.FabricJvmArgs));
        result.Add(plan.MainClass);
        var game = Expand(plan.VanillaGameArgs).Concat(Expand(plan.FabricGameArgs)).ToList();
        // On retire les paires dont la valeur n'a pas pu être remplie (option inconnue de ce launcher).
        for (int i = 0; i < game.Count; i++)
        {
            bool unresolved = game[i].Contains("${");
            if (unresolved)
            {
                if (i > 0 && game[i - 1].StartsWith("--"))
                {
                    game.RemoveAt(i - 1);
                    i--;
                }
                game.RemoveAt(i);
                i--;
            }
        }
        result.AddRange(game);
        return result;
    }

    public static Process Launch(Plan plan, List<string> args)
    {
        Directory.CreateDirectory(GameDir);
        var psi = new ProcessStartInfo(plan.JavaExe) { WorkingDirectory = GameDir, UseShellExecute = false };
        foreach (string a in args)
        {
            psi.ArgumentList.Add(a);
        }
        return Process.Start(psi) ?? throw new InvalidOperationException("Java n'a pas démarré.");
    }
}
