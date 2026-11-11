using System.IO;

namespace Valcombe.Launcher.Services;

/// <summary>
/// Raccourcis du launcher : il est portable (un seul exe, sans installation), donc Windows ne le
/// range nulle part tout seul. Au démarrage, il met à jour son raccourci du menu Démarrer (qui suit
/// l'exe s'il a été déplacé) et crée une fois celui du Bureau.
/// </summary>
public static class Shortcuts
{
    public const string Name = "Les Cendres de Valcombe";

    private static string ExePath => Environment.ProcessPath ?? Path.Combine(AppContext.BaseDirectory, "ValcombeLauncher.exe");

    public static string StartMenuLink => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.Programs), Name + ".lnk");

    public static string DesktopLink => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), Name + ".lnk");

    /// <summary>Menu Démarrer à jour ; Bureau la première fois seulement (on respecte un raccourci supprimé).</summary>
    public static void Ensure(LauncherConfig config)
    {
        if (!config.Shortcuts)
        {
            return;
        }
        try
        {
            Create(StartMenuLink);
            if (!config.DesktopShortcutDone)
            {
                Create(DesktopLink);
                config.DesktopShortcutDone = true;
                config.Save();
            }
        }
        catch (Exception)
        {
            // Pas de raccourci (droits, Bureau redirigé…) : le launcher marche quand même.
        }
    }

    /// <summary>Retire les raccourcis (case décochée dans la personnalisation).</summary>
    public static void Remove()
    {
        foreach (string link in new[] { StartMenuLink, DesktopLink })
        {
            try
            {
                if (File.Exists(link))
                {
                    File.Delete(link);
                }
            }
            catch (IOException)
            {
            }
            catch (UnauthorizedAccessException)
            {
            }
        }
    }

    private static void Create(string link)
    {
        string exe = ExePath;
        Directory.CreateDirectory(Path.GetDirectoryName(link)!);
        Type shellType = Type.GetTypeFromProgID("WScript.Shell") ?? throw new InvalidOperationException("WScript.Shell absent");
        dynamic shell = Activator.CreateInstance(shellType)!;
        dynamic lnk = shell.CreateShortcut(link);
        // Rien à refaire si le raccourci pointe déjà vers cet exe.
        if (File.Exists(link) && string.Equals((string)lnk.TargetPath, exe, StringComparison.OrdinalIgnoreCase))
        {
            return;
        }
        lnk.TargetPath = exe;
        lnk.WorkingDirectory = Path.GetDirectoryName(exe);
        lnk.IconLocation = exe + ",0";
        lnk.Description = "Launcher des Cendres de Valcombe";
        lnk.Save();
    }
}
