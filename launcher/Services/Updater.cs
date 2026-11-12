using System.Diagnostics;
using System.IO;
using System.Reflection;

namespace Valcombe.Launcher.Services;

/// <summary>
/// Mise à jour automatique du launcher : si la dernière version publiée annonce un launcher
/// plus récent, on le télécharge (empreinte vérifiée), on prend sa place et on redémarre.
/// Windows autorise à renommer un exe en cours d'exécution : l'ancien devient « .old.exe »
/// et il est supprimé au démarrage suivant.
/// </summary>
public static class Updater
{
    public static Version Current => Assembly.GetExecutingAssembly().GetName().Version ?? new Version(0, 0);

    private static string ExePath => Environment.ProcessPath
        ?? Path.Combine(AppContext.BaseDirectory, "ValcombeLauncher.exe");

    private static string OldPath => Path.ChangeExtension(ExePath, ".old.exe");

    /// <summary>Supprime l'ancien exe laissé par une mise à jour précédente.</summary>
    public static void CleanUp()
    {
        for (int i = 0; i < 10 && File.Exists(OldPath); i++)
        {
            try
            {
                File.Delete(OldPath);
            }
            catch (IOException)
            {
                Thread.Sleep(300); // l'ancien processus finit de se fermer
            }
            catch (UnauthorizedAccessException)
            {
                Thread.Sleep(300);
            }
        }
    }

    /// <summary>Le launcher annoncé est-il plus récent que celui-ci ?</summary>
    public static bool IsNewer(LauncherInfo? info) =>
        info != null && Version.TryParse(info.Version, out var v) && v > Current;

    /// <summary>Télécharge et installe la nouvelle version, puis la relance. Renvoie false si rien n'a été fait.</summary>
    public static async Task<bool> UpdateAsync(Release release, IProgress<(string, double)> progress, CancellationToken ct = default)
    {
        var info = release.Manifest.Launcher;
        if (!IsNewer(info) || !release.Assets.TryGetValue(info!.FileName, out var url))
        {
            return false;
        }
        string next = Path.ChangeExtension(ExePath, ".update.exe");
        await Http.DownloadTrustedAsync(url, next, info.Sha512,
            new Progress<double>(p => progress.Report(($"Mise à jour du launcher vers la version {info.Version}…", p))), ct);
        CleanUp();
        File.Move(ExePath, OldPath, overwrite: true);
        File.Move(next, ExePath);
        Process.Start(new ProcessStartInfo(ExePath) { WorkingDirectory = AppContext.BaseDirectory, UseShellExecute = false });
        return true;
    }
}
