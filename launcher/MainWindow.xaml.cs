using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Valcombe.Launcher.Services;

namespace Valcombe.Launcher;

public partial class MainWindow : Window
{
    private readonly LauncherConfig _config = LauncherConfig.Load();
    private Release? _release;
    private bool _loading = true;
    private readonly string? _captureDir;

    private static readonly (string Name, string Accent, string Hot)[] Accents =
    {
        ("Lie-de-vin", "#5A1E2A", "#8E1B1B"),
        ("Nuit", "#1F2A44", "#34507A"),
        ("Forêt", "#24402C", "#3A6B45"),
        ("Bronze", "#6B4E24", "#9A7136"),
    };

    private static readonly string[] Backgrounds = { "Parchemin", "Nocturne" };

    public MainWindow()
    {
        InitializeComponent();
        string[] args = Environment.GetCommandLineArgs();
        int i = Array.IndexOf(args, "--capture");
        _captureDir = i >= 0 && i + 1 < args.Length ? args[i + 1] : null;

        VersionText.Text = "Launcher " + Assembly.GetExecutingAssembly().GetName().Version?.ToString(3);
        Updater.CleanUp();
        BuildLookChoices();
        LoadControls();
        ApplyLook();
        RefreshAccount();
        _loading = false;
        Loaded += async (_, _) =>
        {
            if (_captureDir != null)
            {
                await CaptureAsync(_captureDir);
                return;
            }
            int check = Array.IndexOf(args, "--verifier-moteur");
            if (check >= 0 && check + 1 < args.Length)
            {
                await CheckEngineAsync(args[check + 1]);
                return;
            }
            await RefreshReleaseAsync();
        };
    }

    // ------------------------------------------------------------------
    // Fenêtre
    // ------------------------------------------------------------------
    private void DragWindow(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton == MouseButton.Left)
        {
            DragMove();
        }
    }

    private void Minimize(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    private void CloseWindow(object sender, RoutedEventArgs e) => Close();

    private void Navigate(object sender, RoutedEventArgs e)
    {
        if (sender is RadioButton rb && rb.Tag is string page)
        {
            ShowPage(page);
        }
    }

    private void ShowPage(string page)
    {
        if (PageHome == null)
        {
            return;
        }
        PageHome.Visibility = page == "Home" ? Visibility.Visible : Visibility.Collapsed;
        PageGame.Visibility = page == "Game" ? Visibility.Visible : Visibility.Collapsed;
        PageLook.Visibility = page == "Look" ? Visibility.Visible : Visibility.Collapsed;
        PageSettings.Visibility = page == "Settings" ? Visibility.Visible : Visibility.Collapsed;
        PageAccount.Visibility = page == "Account" ? Visibility.Visible : Visibility.Collapsed;
        if (page == "Account")
        {
            RefreshAccount();
        }
    }

    private void SetStatus(string text, double progress)
    {
        StatusText.Text = text;
        Progress.Value = progress;
    }

    // ------------------------------------------------------------------
    // Réglages ↔ contrôles
    // ------------------------------------------------------------------
    private void LoadControls()
    {
        var o = _config.Minecraft;
        RenderSlider.Value = o.RenderDistance;
        FovSlider.Value = o.Fov;
        FpsSlider.Value = o.MaxFps;
        VolumeSlider.Value = o.MasterVolume;
        GuiScaleBox.SelectedIndex = Math.Clamp(o.GuiScale, 0, 4);
        LangBox.SelectedIndex = o.Language == "en_us" ? 1 : 0;
        FullscreenBox.IsChecked = o.Fullscreen;
        RepoBox.Text = _config.Repository;
        ClientIdBox.Text = _config.MicrosoftClientId;
        ServerBox.Text = _config.Server;
        MemorySlider.Value = _config.MaxMemoryMb / 1024;
        CloseOnLaunchBox.IsChecked = _config.CloseOnLaunch;
        UpdateLabels();
    }

    private void UpdateLabels()
    {
        RenderLabel.Text = $"Distance d'affichage : {(int)RenderSlider.Value} tronçons";
        FovLabel.Text = $"Champ de vision : {(int)FovSlider.Value}°";
        FpsLabel.Text = FpsSlider.Value >= 260 ? "Images par seconde : illimitées" : $"Images par seconde : {(int)FpsSlider.Value}";
        VolumeLabel.Text = $"Volume général : {(int)VolumeSlider.Value} %";
        MemoryLabel.Text = $"Mémoire allouée au jeu : {(int)MemorySlider.Value} Go";
    }

    private void OptionChanged(object sender, RoutedEventArgs e)
    {
        if (_loading)
        {
            return;
        }
        var o = _config.Minecraft;
        o.RenderDistance = (int)RenderSlider.Value;
        o.Fov = (int)FovSlider.Value;
        o.MaxFps = (int)FpsSlider.Value;
        o.MasterVolume = (int)VolumeSlider.Value;
        o.GuiScale = Math.Max(0, GuiScaleBox.SelectedIndex);
        o.Language = (LangBox.SelectedItem as ComboBoxItem)?.Tag as string ?? "fr_fr";
        o.Fullscreen = FullscreenBox.IsChecked == true;
        UpdateLabels();
        _config.Save();
        if (Directory.Exists(Paths.GameDir))
        {
            Instance.ApplyOptions(o);
        }
    }

    private void SettingsChanged(object sender, RoutedEventArgs e)
    {
        if (_loading)
        {
            return;
        }
        bool repoChanged = _config.Repository != RepoBox.Text.Trim();
        _config.Repository = RepoBox.Text.Trim();
        _config.Server = ServerBox.Text.Trim();
        _config.MicrosoftClientId = ClientIdBox.Text.Trim();
        _config.MaxMemoryMb = (int)MemorySlider.Value * 1024;
        UpdateLabels();
        _config.Save();
        if (repoChanged)
        {
            _ = RefreshReleaseAsync();
        }
        UpdateServerText();
    }

    // ------------------------------------------------------------------
    // Personnalisation
    // ------------------------------------------------------------------
    private void BuildLookChoices()
    {
        foreach (var a in Accents)
        {
            var rb = new RadioButton { Content = a.Name, GroupName = "accent", Tag = a.Name, IsChecked = a.Name == _config.Accent };
            rb.Checked += LookChanged;
            AccentPanel.Children.Add(rb);
        }
        foreach (string b in Backgrounds)
        {
            var rb = new RadioButton { Content = b, GroupName = "bg", Tag = b, IsChecked = b == _config.Background };
            rb.Checked += LookChanged;
            BackgroundPanel.Children.Add(rb);
        }
    }

    private void LookChanged(object sender, RoutedEventArgs e)
    {
        if (_loading)
        {
            return;
        }
        if (sender is RadioButton { GroupName: "accent", Tag: string accent })
        {
            _config.Accent = accent;
        }
        if (sender is RadioButton { GroupName: "bg", Tag: string bg })
        {
            _config.Background = bg;
        }
        _config.CloseOnLaunch = CloseOnLaunchBox.IsChecked == true;
        _config.Save();
        ApplyLook();
    }

    private void ApplyLook()
    {
        var a = Accents.FirstOrDefault(x => x.Name == _config.Accent);
        if (a.Name == null)
        {
            a = Accents[0];
        }
        Application.Current.Resources["Accent"] = new SolidColorBrush((Color)ColorConverter.ConvertFromString(a.Accent));
        Application.Current.Resources["AccentHot"] = new SolidColorBrush((Color)ColorConverter.ConvertFromString(a.Hot));
        Color top = (Color)ColorConverter.ConvertFromString(a.Accent);
        Backdrop.Background = _config.Background == "Nocturne"
            ? new LinearGradientBrush(Color.FromRgb(10, 8, 18), Color.FromRgb(30, 22, 40), 90)
            : new LinearGradientBrush(Color.FromArgb(255, (byte)(top.R / 2), (byte)(top.G / 2), (byte)(top.B / 2)),
                Color.FromRgb(20, 12, 16), 90);
    }

    // ------------------------------------------------------------------
    // Version publiée, nouvelles, compte
    // ------------------------------------------------------------------
    private async Task RefreshReleaseAsync()
    {
        if (string.IsNullOrWhiteSpace(_config.Repository))
        {
            NewsList.ItemsSource = new[]
            {
                new NewsItem { Title = "Bienvenue", Text = "Indiquez le dépôt GitHub du mod dans les Réglages pour recevoir les mises à jour et les nouvelles." },
            };
            UpdateServerText();
            return;
        }
        try
        {
            SetStatus("Recherche de la dernière version…", 0);
            _release = await Releases.LatestAsync(_config.Repository);
            if (Updater.IsNewer(_release.Manifest.Launcher))
            {
                PlayButton.IsEnabled = false;
                if (await Updater.UpdateAsync(_release, new Progress<(string, double)>(p => SetStatus(p.Item1, p.Item2))))
                {
                    Close(); // la nouvelle version vient de démarrer
                    return;
                }
                PlayButton.IsEnabled = true;
            }
            var news = _release.Manifest.News.ToList();
            if (!string.IsNullOrWhiteSpace(_release.Notes))
            {
                news.Insert(0, new NewsItem { Title = "Version " + _release.Tag, Text = _release.Notes.Trim() });
            }
            NewsList.ItemsSource = news;
            string installed = Instance.InstalledVersion ?? "aucune";
            SetStatus(installed == _release.Tag ? $"Version {_release.Tag} installée. Prêt."
                : $"Version {_release.Tag} disponible (installée : {installed}).", 0);
        }
        catch (Exception ex)
        {
            SetStatus("Impossible de joindre GitHub : " + ex.Message, 0);
        }
        UpdateServerText();
    }

    private string ServerAddress =>
        !string.IsNullOrWhiteSpace(_config.Server) ? _config.Server : _release?.Manifest.Server ?? "";

    private void UpdateServerText()
    {
        ServerText.Text = string.IsNullOrWhiteSpace(ServerAddress) ? "Non renseigné (Réglages)." : ServerAddress;
    }

    private bool Integrated => !string.IsNullOrWhiteSpace(_config.MicrosoftClientId);

    private void RefreshAccount()
    {
        SignOutButton.Visibility = Integrated && MicrosoftAuth.HasSavedAccount ? Visibility.Visible : Visibility.Collapsed;
        if (Integrated)
        {
            string t = MicrosoftAuth.HasSavedAccount ? "Compte Microsoft enregistré (connexion intégrée)." : "Aucun compte : connectez-vous.";
            AccountText.Text = t;
            AccountShort.Text = t;
            return;
        }
        string? name = Prism.IsInstalled && Prism.HasAccount() ? Prism.AccountName() : null;
        string text = !Prism.IsInstalled
            ? "Pas encore connecté : la connexion vous sera proposée au premier clic sur JOUER."
            : name != null ? $"Connecté : {name}." : "Aucun compte enregistré.";
        AccountText.Text = text;
        AccountShort.Text = text;
    }

    /// <summary>Connexion intégrée : affiche le code, ouvre la page Microsoft, garde le compte.</summary>
    private async Task<MinecraftAccount> SignInAsync()
    {
        var auth = new MicrosoftAuth(_config.MicrosoftClientId);
        var saved = await auth.RefreshAsync(CancellationToken.None);
        if (saved != null)
        {
            return saved;
        }
        var account = await auth.SignInAsync(code =>
        {
            CodeBox.Text = code.UserCode;
            CodeBox.Visibility = Visibility.Visible;
            Clipboard.SetText(code.UserCode);
            SetStatus($"Saisissez le code {code.UserCode} (déjà copié) sur la page Microsoft qui s'ouvre.", 0);
            Process.Start(new ProcessStartInfo(code.VerificationUri) { UseShellExecute = true });
        }, CancellationToken.None);
        CodeBox.Visibility = Visibility.Collapsed;
        RefreshAccount();
        return account;
    }

    private void SignOut(object sender, RoutedEventArgs e)
    {
        MicrosoftAuth.SignOut();
        RefreshAccount();
        SetStatus("Déconnecté.", 0);
    }

    private async void ManageAccount(object sender, RoutedEventArgs e)
    {
        if (Integrated)
        {
            try
            {
                var a = await SignInAsync();
                SetStatus($"Connecté : {a.Name}.", 0);
            }
            catch (Exception ex)
            {
                SetStatus("Erreur : " + ex.Message, 0);
            }
            return;
        }
        try
        {
            if (!Prism.IsInstalled)
            {
                await Prism.InstallAsync(new Progress<(string, double)>(p => SetStatus(p.Item1, p.Item2)));
            }
            SetStatus("Fenêtre de connexion ouverte : Comptes → Ajouter Microsoft, puis suivez les étapes.", 0);
            string? who = await Prism.ConnectAsync();
            SetStatus(who != null ? $"Connecté : {who}." : "Connexion annulée.", 0);
        }
        catch (Exception ex)
        {
            SetStatus("Erreur : " + ex.Message, 0);
        }
    }

    private void OpenFolder(object sender, RoutedEventArgs e)
    {
        Directory.CreateDirectory(Paths.DataDir);
        Process.Start(new ProcessStartInfo("explorer.exe", Paths.DataDir));
    }

    private void RepairInstall(object sender, RoutedEventArgs e)
    {
        Instance.Repair();
        SetStatus("Installation réinitialisée : les mods seront retéléchargés au prochain lancement.", 0);
    }

    // ------------------------------------------------------------------
    // Jouer
    // ------------------------------------------------------------------
    private async void Play(object sender, RoutedEventArgs e)
    {
        PlayButton.IsEnabled = false;
        var progress = new Progress<(string, double)>(p => SetStatus(p.Item1, p.Item2));
        try
        {
            if (Integrated)
            {
                await PlayWithEngineAsync(progress);
                return;
            }
            if (!Prism.IsInstalled)
            {
                await Prism.InstallAsync(progress);
            }
            if (_release == null && !string.IsNullOrWhiteSpace(_config.Repository))
            {
                await RefreshReleaseAsync();
            }
            if (_release != null && Instance.InstalledVersion != _release.Tag)
            {
                await Instance.SyncAsync(_release, _config, progress, repair: false);
            }
            else if (Instance.InstalledVersion == null)
            {
                throw new InvalidOperationException("Aucune version installée : renseignez le dépôt GitHub dans les Réglages.");
            }
            Instance.ApplyOptions(_config.Minecraft);
            if (!Prism.HasAccount())
            {
                SetStatus("Première connexion : dans la fenêtre qui s'ouvre, Comptes → Ajouter Microsoft, puis suivez les étapes. "
                    + "Elle se refermera toute seule.", 0);
                string? who = await Prism.ConnectAsync();
                if (who == null)
                {
                    SetStatus("Connexion annulée.", 0);
                    return;
                }
                SetStatus($"Connecté : {who}.", 0);
                RefreshAccount();
            }
            SetStatus("Lancement de Minecraft…", 1);
            Prism.Launch(ServerAddress);
            if (_config.CloseOnLaunch)
            {
                await Task.Delay(1500);
                Close();
            }
        }
        catch (Exception ex)
        {
            SetStatus("Erreur : " + ex.Message, 0);
        }
        finally
        {
            PlayButton.IsEnabled = true;
            RefreshAccount();
        }
    }

    /// <summary>Notre moteur : connexion intégrée, téléchargements, lancement direct (sans Prism).</summary>
    private async Task PlayWithEngineAsync(IProgress<(string, double)> progress)
    {
        if (_release == null)
        {
            await RefreshReleaseAsync();
        }
        var release = _release ?? throw new InvalidOperationException("Version introuvable : vérifiez le dépôt GitHub.");
        var account = await SignInAsync();
        SetStatus($"Connecté : {account.Name}. Préparation du jeu…", 0);
        var plan = await Engine.PrepareAsync(release.Manifest.Minecraft, release.Manifest.Loader);
        await Engine.DownloadAsync(plan, progress);
        await Instance.SyncModsAsync(release, Engine.ModsDir, progress, repair: false);
        Instance.ApplyOptions(_config.Minecraft, Engine.GameDir);
        SetStatus("Lancement de Minecraft…", 1);
        Engine.Launch(plan, Engine.BuildArguments(plan, account, _config.MicrosoftClientId, _config.MaxMemoryMb, ServerAddress));
        if (_config.CloseOnLaunch)
        {
            await Task.Delay(1500);
            Close();
        }
    }

    /// <summary>Vérification à blanc du moteur : ValcombeLauncher.exe --verifier-moteur rapport.txt</summary>
    private async Task CheckEngineAsync(string report)
    {
        var lines = new List<string>();
        try
        {
            var plan = await Engine.PrepareAsync("26.3", "0.20.1");
            var fake = new MinecraftAccount("Essai", "00000000000000000000000000000000", "JETON-FICTIF", "0");
            var args = Engine.BuildArguments(plan, fake, "ID-FICTIF", 4096, "localhost:25565");
            lines.Add($"Minecraft {plan.MinecraftVersion}, Fabric {plan.FabricId}, classe principale {plan.MainClass}");
            lines.Add($"Java : {plan.JavaComponent} → {plan.JavaExe}");
            lines.Add($"Ressources : index {plan.AssetIndex}");
            lines.Add($"Fichiers à télécharger : {plan.Files.Count} ; classpath : {plan.Classpath.Count} entrées");
            lines.Add("Arguments (classpath abrégé) :");
            lines.AddRange(args.Select(a => a.Length > 160 ? a[..160] + "…" : a));
        }
        catch (Exception ex)
        {
            lines.Add("ERREUR : " + ex);
        }
        await File.WriteAllLinesAsync(report, lines);
        Close();
    }

    // ------------------------------------------------------------------
    // Captures d'écran (vérification sans réseau) : ValcombeLauncher.exe --capture <dossier>
    // ------------------------------------------------------------------
    private async Task CaptureAsync(string dir)
    {
        Directory.CreateDirectory(dir);
        NewsList.ItemsSource = new[]
        {
            new NewsItem { Title = "Le village est ouvert", Date = "8 nov.", Text = "La place de Valcombe et ses seize maisons vous attendent. Première partie vendredi soir." },
            new NewsItem { Title = "Version 0.1.0", Date = "8 nov.", Text = "Écrans sur mesure, Conteur humain, éditeur de scripts et vocal coupé la nuit." },
        };
        ServerText.Text = "valcombe.exemple.fr";
        foreach (string page in new[] { "Home", "Game", "Look", "Settings", "Account" })
        {
            ShowPage(page);
            await Task.Delay(150);
            UpdateLayout();
            var bmp = new RenderTargetBitmap((int)ActualWidth, (int)ActualHeight, 96, 96, PixelFormats.Pbgra32);
            bmp.Render(this);
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(bmp));
            await using var fs = File.Create(Path.Combine(dir, $"launcher-{page}.png"));
            enc.Save(fs);
        }
        Close();
    }
}
