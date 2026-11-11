using System.Diagnostics;
using System.IO;
using System.IO.Compression;

namespace Valcombe.Launcher.Services;

/// <summary>
/// Rapport de plantage en un clic : journaux du jeu, derniers rapports de plantage et réglages
/// du launcher, rassemblés dans une archive sur le Bureau (sans aucun jeton de connexion).
/// </summary>
public static class CrashReport
{
    public static string Create()
    {
        string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        string zip = Path.Combine(desktop, $"Valcombe-rapport-{DateTime.Now:yyyyMMdd-HHmm}.zip");
        if (File.Exists(zip))
        {
            File.Delete(zip);
        }
        using var archive = ZipFile.Open(zip, ZipArchiveMode.Create);
        int added = 0;
        foreach (string gameDir in new[] { Engine.GameDir, Paths.GameDir }.Distinct())
        {
            string label = gameDir == Engine.GameDir ? "jeu" : "prism";
            added += Add(archive, Path.Combine(gameDir, "logs", "latest.log"), $"{label}/latest.log");
            added += Add(archive, Path.Combine(gameDir, "logs", "sortie-jeu.log"), $"{label}/sortie-jeu.log");
            string logs = Path.Combine(gameDir, "logs");
            if (Directory.Exists(logs))
            {
                foreach (var f in new DirectoryInfo(logs).GetFiles("hs_err_pid*.log").OrderByDescending(f => f.LastWriteTime).Take(2))
                {
                    added += Add(archive, f.FullName, $"{label}/{f.Name}");
                }
            }
            string crashes = Path.Combine(gameDir, "crash-reports");
            if (Directory.Exists(crashes))
            {
                foreach (var f in new DirectoryInfo(crashes).GetFiles("*.txt").OrderByDescending(f => f.LastWriteTime).Take(3))
                {
                    added += Add(archive, f.FullName, $"{label}/crash-reports/{f.Name}");
                }
            }
            added += Add(archive, Path.Combine(gameDir, "options.txt"), $"{label}/options.txt");
        }
        // Réglages du launcher (aucun secret : le jeton Microsoft est dans un autre fichier, chiffré)
        added += Add(archive, Paths.ConfigFile, "launcher.json");
        var info = archive.CreateEntry("systeme.txt");
        using (var w = new StreamWriter(info.Open()))
        {
            w.WriteLine($"Launcher : {typeof(CrashReport).Assembly.GetName().Version}");
            w.WriteLine($"Windows : {Environment.OSVersion}");
            w.WriteLine($"Processeurs : {Environment.ProcessorCount}");
            w.WriteLine($"Date : {DateTime.Now:yyyy-MM-dd HH:mm}");
            w.WriteLine($"Fichiers joints : {added}");
        }
        return zip;
    }

    private static int Add(ZipArchive archive, string file, string name)
    {
        if (!File.Exists(file))
        {
            return 0;
        }
        // Le jeu garde latest.log ouvert : on le lit en partage.
        using var src = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        using var dst = archive.CreateEntry(name, CompressionLevel.Optimal).Open();
        src.CopyTo(dst);
        return 1;
    }

    /// <summary>Montre l'archive dans l'Explorateur.</summary>
    public static void Reveal(string zip)
    {
        Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{zip}\"") { UseShellExecute = true });
    }
}
