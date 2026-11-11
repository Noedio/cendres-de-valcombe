using System.IO;

namespace Valcombe.Launcher.Services;

/// <summary>
/// Les réglages du joueur (touches, graphismes, son, micro du vocal, liste de serveurs) sont
/// gardés en double dans %APPDATA%\Valcombe\profil. Ainsi ils survivent à une réinstallation,
/// à un launcher déplacé ou téléchargé dans un autre dossier, et au passage d'un moteur de jeu
/// à l'autre (moteur intégré ou Prism) : à chaque synchronisation, la copie la plus récente gagne.
/// </summary>
public static class Profile
{
    public static string Dir => DirOverride ?? Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Valcombe", "profil");

    /// <summary>Autre emplacement du profil (essais).</summary>
    public static string? DirOverride { get; set; }

    /// <summary>Autres dossiers de jeu (essais) ; null = moteur intégré et Prism.</summary>
    public static string[]? GameDirsOverride { get; set; }

    /// <summary>Fichiers suivis, relatifs au dossier du jeu.</summary>
    private static readonly string[] Files = { "options.txt", "servers.dat" };

    /// <summary>Dossiers suivis en entier (réglages du vocal : micro, volumes…).</summary>
    private static readonly string[] Folders = { Path.Combine("config", "voicechat") };

    /// <summary>Dossiers de jeu connus : le moteur intégré et l'instance Prism.</summary>
    public static IEnumerable<string> GameDirs => GameDirsOverride ?? new[] { Engine.GameDir, Paths.GameDir }.Distinct();

    /// <summary>
    /// Met à jour chaque copie avec la plus récente, entre le profil et les dossiers de jeu.
    /// <paramref name="target"/> (le dossier du jeu qu'on va lancer) est créé s'il n'existe pas.
    /// Renvoie le nombre de fichiers recopiés.
    /// </summary>
    public static int Sync(string? target = null)
    {
        if (target != null)
        {
            Directory.CreateDirectory(target);
        }
        var places = new List<string> { Dir };
        places.AddRange(GameDirs.Where(d => Directory.Exists(d) || d == target));
        int copied = 0;
        foreach (string rel in TrackedFiles(places))
        {
            string? newest = null;
            DateTime newestTime = DateTime.MinValue;
            foreach (string place in places)
            {
                string f = Path.Combine(place, rel);
                if (File.Exists(f) && File.GetLastWriteTimeUtc(f) > newestTime)
                {
                    newest = f;
                    newestTime = File.GetLastWriteTimeUtc(f);
                }
            }
            if (newest == null)
            {
                continue;
            }
            foreach (string place in places)
            {
                string f = Path.Combine(place, rel);
                if (f == newest || (File.Exists(f) && File.GetLastWriteTimeUtc(f) >= newestTime))
                {
                    continue;
                }
                try
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(f)!);
                    File.Copy(newest, f, true);
                    File.SetLastWriteTimeUtc(f, newestTime);
                    copied++;
                }
                catch (IOException)
                {
                    // Fichier ouvert par un jeu en cours : il sera recopié la prochaine fois.
                }
                catch (UnauthorizedAccessException)
                {
                }
            }
        }
        return copied;
    }

    /// <summary>Les fichiers suivis présents dans l'une des copies (chemins relatifs).</summary>
    private static IEnumerable<string> TrackedFiles(List<string> places)
    {
        var all = new HashSet<string>(Files, StringComparer.OrdinalIgnoreCase);
        foreach (string place in places)
        {
            foreach (string folder in Folders)
            {
                string dir = Path.Combine(place, folder);
                if (!Directory.Exists(dir))
                {
                    continue;
                }
                foreach (string f in Directory.GetFiles(dir, "*", SearchOption.AllDirectories))
                {
                    if (new FileInfo(f).Length < 1_000_000)
                    {
                        all.Add(Path.GetRelativePath(place, f));
                    }
                }
            }
        }
        return all;
    }

    /// <summary>Le options.txt le plus récent (profil ou dossiers de jeu), ou null.</summary>
    public static string? NewestOptions()
    {
        return new[] { Dir }.Concat(GameDirs)
            .Select(d => Path.Combine(d, "options.txt"))
            .Where(File.Exists)
            .OrderByDescending(File.GetLastWriteTimeUtc)
            .FirstOrDefault();
    }
}
