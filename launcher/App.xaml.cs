using System.Windows;
using Valcombe.Launcher.Services;
using Valcombe.Launcher.Services.Ssh;

namespace Valcombe.Launcher;

public partial class App : Application
{
    /// <summary>
    /// Les vérifications du serveur loué se font sans fenêtre : la fenêtre principale n'est pas créée (elle
    /// rangerait sinon le launcher dans le menu Démarrer), la vérification écrit son rapport puis le launcher se ferme.
    ///   ValcombeLauncher.exe --verifier-vps rapport.txt [--sans-telechargement]
    ///   ValcombeLauncher.exe --essai-vps rapport.txt --hote ... (voir Services/Ssh/VpsCheck.cs)
    /// </summary>
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        string[] args = e.Args;
        int verify = Array.IndexOf(args, "--verifier-vps");
        int trial = Array.IndexOf(args, "--essai-vps");
        string? report = verify >= 0 && verify + 1 < args.Length ? args[verify + 1]
            : trial >= 0 && trial + 1 < args.Length ? args[trial + 1] : null;
        if (report == null)
        {
            return;
        }
        // La fenêtre de StartupUri est chargée par une « navigation » de démarrage : l'annuler suffit à ne pas la créer.
        Navigating += (_, nav) => nav.Cancel = true;
        ShutdownMode = ShutdownMode.OnExplicitShutdown;
        Dispatcher.InvokeAsync(async () =>
        {
            try
            {
                var config = LauncherConfig.Load();
                if (verify >= 0)
                {
                    await VpsCheck.VerifyAsync(report, config, download: !args.Contains("--sans-telechargement"));
                }
                else
                {
                    await VpsCheck.TrialAsync(report, args, config);
                }
            }
            catch (Exception ex)
            {
                await System.IO.File.WriteAllTextAsync(report, "ERREUR : " + ex);
            }
            finally
            {
                Shutdown();
            }
        });
    }
}
