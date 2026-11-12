using System.Diagnostics;
using System.IO;
using System.Net.Http;
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
    /// <summary>Le serveur de Valcombe sur ce PC (page Héberger, et l'Atelier).</summary>
    private readonly LocalServer _server = new();
    private bool _hostDirty;
    private bool _openAtelierWhenReady;
    private bool _playWhenReady;
    private LocalServer.State? _shownHostState;
    /// <summary>Adresse imposée pour le prochain JOUER (« Jouer sur mon serveur »).</summary>
    private string? _playAddress;

    private static readonly (string Name, string Accent, string Hot)[] Accents =
    {
        ("Lie-de-vin", "#7A2433", "#A3303F"),
        ("Nuit", "#2A3A62", "#3D5A94"),
        ("Forêt", "#2C5238", "#3F7A50"),
        ("Bronze", "#7A5A2A", "#A67C3C"),
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
        // Le serveur local écrit hors du fil de l'interface : on rafraîchit la page au plus quatre fois par seconde.
        _server.Changed += () => _hostDirty = true;
        var hostTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
        hostTimer.Tick += (_, _) =>
        {
            if (_hostDirty)
            {
                _hostDirty = false;
                RefreshHost();
            }
        };
        hostTimer.Start();
        RefreshHost();
        Closing += OnClosing;
        SizeChanged += (_, _) => BuildEmbers();
        var statusTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(30) };
        statusTimer.Tick += async (_, _) => await RefreshServerStatusAsync();
        statusTimer.Start();
        Loaded += async (_, _) =>
        {
            BuildEmbers();
            if (_captureDir != null)
            {
                await CaptureAsync(_captureDir);
                return;
            }
            // Le launcher est portable : il se range lui-même dans le menu Démarrer (et une fois sur le Bureau).
            // Pas pendant une vérification : un exe d'essai ne doit jamais détourner les raccourcis du joueur.
            if (!args.Any(a => a is "--verifier-moteur" or "--verifier-serveur" or "--essai-serveur"))
            {
                Shortcuts.Ensure(_config);
            }
            int trial = Array.IndexOf(args, "--essai-serveur");
            if (trial >= 0 && trial + 1 < args.Length)
            {
                int local = Array.IndexOf(args, "--mod-local");
                LocalServer.ModOverride = local >= 0 && local + 1 < args.Length ? args[local + 1] : null;
                await TrialServerAsync(args[trial + 1]);
                return;
            }
            int serverCheck = Array.IndexOf(args, "--verifier-serveur");
            if (serverCheck >= 0 && serverCheck + 1 < args.Length)
            {
                await CheckServerAsync(args[serverCheck + 1]);
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
        var pages = new (string Name, FrameworkElement View)[]
        {
            ("Home", PageHome), ("Host", PageHost), ("Atelier", PageAtelier), ("Guide", PageGuide), ("Carnet", PageCarnet),
            ("Game", PageGame), ("Look", PageLook), ("Settings", PageSettings), ("Account", PageAccount),
        };
        foreach (var (name, view) in pages)
        {
            bool show = name == page;
            if (show && view.Visibility != Visibility.Visible)
            {
                // La page entre en fondu.
                view.BeginAnimation(OpacityProperty, new System.Windows.Media.Animation.DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(180)));
            }
            view.Visibility = show ? Visibility.Visible : Visibility.Collapsed;
        }
        // L'illustration reste vive sur l'accueil ; ailleurs, un voile la fait reculer derrière le texte.
        Dim.BeginAnimation(OpacityProperty, new System.Windows.Media.Animation.DoubleAnimation(page == "Home" ? 0 : 0.5,
            TimeSpan.FromMilliseconds(250)));
        if (page == "Carnet")
        {
            LoadCarnet();
        }
        if (page == "Account")
        {
            RefreshAccount();
        }
        if (page == "Host" || page == "Atelier")
        {
            _shownHostState = null;
            RefreshHost();
        }
    }

    private void GoHost(object sender, RoutedEventArgs e) => NavHost.IsChecked = true;

    private void GoAtelier(object sender, RoutedEventArgs e) => NavAtelier.IsChecked = true;

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
        // Les réglages faits en jeu sont la référence : on les récupère (profil durable) avant de les afficher.
        try
        {
            Profile.Sync();
            Instance.ReadOptions(_config.Minecraft);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
        }
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
        ShortcutsBox.IsChecked = _config.Shortcuts;
        HdBox.IsChecked = _config.HdTextures;
        ShadersBox.IsChecked = _config.Shaders;
        EmbersBox.IsChecked = _config.Embers;
        HostEulaBox.IsChecked = _config.HostEulaAccepted;
        HostGuestsOnlyBox.IsChecked = _config.HostGuestsOnly;
        HostNameBox.Text = _config.HostName;
        HostPortBox.Text = _config.HostPort.ToString();
        HostMemorySlider.Value = Math.Clamp(_config.HostMemoryMb / 1024, 2, 8);
        UpdateLabels();
    }

    private void UpdateLabels()
    {
        RenderLabel.Text = $"Distance d'affichage : {(int)RenderSlider.Value} tronçons";
        FovLabel.Text = $"Champ de vision : {(int)FovSlider.Value}°";
        FpsLabel.Text = FpsSlider.Value >= 260 ? "Images par seconde : illimitées" : $"Images par seconde : {(int)FpsSlider.Value}";
        VolumeLabel.Text = $"Volume général : {(int)VolumeSlider.Value} %";
        MemoryLabel.Text = $"Mémoire allouée au jeu : {(int)MemorySlider.Value} Go";
        HostMemoryLabel.Text = $"Mémoire du serveur : {(int)HostMemorySlider.Value} Go";
        int total = TotalMemoryGb();
        if (total > 0)
        {
            int rec = RecommendedMemoryGb(total);
            MemoryHint.Text = $"Cet ordinateur a {total} Go de mémoire : {rec} Go conseillés pour le jeu."
                + ((int)MemorySlider.Value > total * 3 / 4 ? " Attention : réglage trop élevé, Windows risque de ralentir." : "");
        }
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
        // Changé dans le launcher : écrit partout (jeux installés et profil), le reste du fichier est gardé.
        Instance.ApplyOptions(o, Profile.Dir);
        foreach (string dir in Profile.GameDirs.Where(Directory.Exists))
        {
            Instance.ApplyOptions(o, dir);
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
            // « Parchemin » est le nom enregistré depuis les premières versions : c'est aujourd'hui l'ambiance de braise.
            var rb = new RadioButton { Content = b == "Parchemin" ? "Braise" : b, GroupName = "bg", Tag = b, IsChecked = b == _config.Background };
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
        _config.HdTextures = HdBox.IsChecked == true;
        _config.Shaders = ShadersBox.IsChecked == true;
        _config.Embers = EmbersBox.IsChecked == true;
        BuildEmbers();
        bool shortcuts = ShortcutsBox.IsChecked == true;
        if (shortcuts != _config.Shortcuts)
        {
            _config.Shortcuts = shortcuts;
            _config.DesktopShortcutDone = false;
            if (shortcuts)
            {
                Shortcuts.Ensure(_config);
            }
            else
            {
                Shortcuts.Remove();
            }
        }
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
        // Un voile teinté sur l'illustration : aux couleurs de l'accent (braise), ou bleu nuit.
        Color top = (Color)ColorConverter.ConvertFromString(a.Accent);
        Backdrop.Background = _config.Background == "Nocturne"
            ? new LinearGradientBrush(Color.FromArgb(150, 10, 16, 44), Color.FromArgb(235, 8, 8, 20), 90)
            : new LinearGradientBrush(Color.FromArgb(70, (byte)(top.R / 2), (byte)(top.G / 2), (byte)(top.B / 2)),
                Color.FromArgb(225, 15, 10, 14), 90);
    }

    private static readonly Random EmberRandom = new(7);

    /// <summary>Des braises montent lentement devant l'illustration (désactivable dans Apparence).</summary>
    private void BuildEmbers()
    {
        Embers.Children.Clear();
        if (!_config.Embers || ActualWidth <= 0)
        {
            return;
        }
        double w = ActualWidth;
        double h = ActualHeight;
        for (int i = 0; i < 28; i++)
        {
            double size = 2 + EmberRandom.NextDouble() * 3;
            var dot = new System.Windows.Shapes.Ellipse
            {
                Width = size,
                Height = size,
                Fill = new SolidColorBrush(Color.FromRgb(255, (byte)(130 + EmberRandom.Next(70)), 60)),
                Opacity = 0,
                Effect = new System.Windows.Media.Effects.BlurEffect { Radius = 1.5 },
            };
            Canvas.SetLeft(dot, 232 + EmberRandom.NextDouble() * Math.Max(1, w - 232));
            var move = new TranslateTransform();
            dot.RenderTransform = move;
            Embers.Children.Add(dot);
            double seconds = 10 + EmberRandom.NextDouble() * 14;
            var start = TimeSpan.FromSeconds(-EmberRandom.NextDouble() * seconds);
            move.BeginAnimation(TranslateTransform.YProperty, new System.Windows.Media.Animation.DoubleAnimation(h + 10, h * 0.15, TimeSpan.FromSeconds(seconds))
            {
                RepeatBehavior = System.Windows.Media.Animation.RepeatBehavior.Forever, BeginTime = start,
            });
            move.BeginAnimation(TranslateTransform.XProperty, new System.Windows.Media.Animation.DoubleAnimation(-18, 18, TimeSpan.FromSeconds(seconds / 3))
            {
                RepeatBehavior = System.Windows.Media.Animation.RepeatBehavior.Forever, AutoReverse = true, BeginTime = start,
            });
            var fade = new System.Windows.Media.Animation.DoubleAnimationUsingKeyFrames
            {
                Duration = TimeSpan.FromSeconds(seconds), RepeatBehavior = System.Windows.Media.Animation.RepeatBehavior.Forever, BeginTime = start,
            };
            fade.KeyFrames.Add(new System.Windows.Media.Animation.LinearDoubleKeyFrame(0, System.Windows.Media.Animation.KeyTime.FromPercent(0)));
            fade.KeyFrames.Add(new System.Windows.Media.Animation.LinearDoubleKeyFrame(0.85, System.Windows.Media.Animation.KeyTime.FromPercent(0.2)));
            fade.KeyFrames.Add(new System.Windows.Media.Animation.LinearDoubleKeyFrame(0.5, System.Windows.Media.Animation.KeyTime.FromPercent(0.7)));
            fade.KeyFrames.Add(new System.Windows.Media.Animation.LinearDoubleKeyFrame(0, System.Windows.Media.Animation.KeyTime.FromPercent(1)));
            dot.BeginAnimation(OpacityProperty, fade);
        }
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
                news.Insert(0, new NewsItem { Title = "Version " + _release.Tag, Text = PlainText(_release.Notes) });
            }
            NewsList.ItemsSource = news;
            RefreshServerPicker();
            string installed = (Integrated ? Instance.EngineVersion : Instance.InstalledVersion) ?? "aucune";
            if (installed != _release.Tag && Integrated && _captureDir == null)
            {
                // Le launcher rattrape la version publiée tout de suite, en arrière-plan : JOUER sera immédiat.
                PlayButton.IsEnabled = false;
                try
                {
                    await Instance.SyncEngineAsync(_release, new Progress<(string, double)>(p => SetStatus(p.Item1, p.Item2)));
                    installed = _release.Tag;
                }
                catch (Exception ex)
                {
                    SetStatus($"Mise à jour du jeu reportée au prochain JOUER : {ex.Message}", 0);
                    PlayButton.IsEnabled = true;
                    UpdateServerText();
                    return;
                }
                PlayButton.IsEnabled = true;
            }
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

    /// <summary>Tant que le serveur tourne sur ce PC, le launcher reste ouvert : le fermer arrêterait la soirée.</summary>
    private bool CloseAfterLaunch => _config.CloseOnLaunch && _server.Current == LocalServer.State.Stopped;

    private bool _pickerLoading;

    /// <summary>Liste des serveurs sur l'accueil : celui de la version publiée, puis les serveurs enregistrés.</summary>
    private void RefreshServerPicker()
    {
        _pickerLoading = true;
        ServerPicker.Items.Clear();
        string official = _release?.Manifest.Server ?? "";
        ServerPicker.Items.Add(new ComboBoxItem
        {
            Content = string.IsNullOrWhiteSpace(official) ? "Serveur de la version publiée" : $"Valcombe ({official})",
            Tag = "",
        });
        foreach (var sv in _config.Servers)
        {
            ServerPicker.Items.Add(new ComboBoxItem { Content = $"{sv.Name} ({sv.Address})", Tag = sv.Address });
        }
        // Une adresse saisie à la main dans les réglages reste proposée.
        bool known = string.IsNullOrWhiteSpace(_config.Server) || _config.Servers.Any(x => x.Address == _config.Server);
        if (!known)
        {
            ServerPicker.Items.Add(new ComboBoxItem { Content = _config.Server, Tag = _config.Server });
        }
        foreach (ComboBoxItem item in ServerPicker.Items)
        {
            if ((string)item.Tag == _config.Server)
            {
                ServerPicker.SelectedItem = item;
            }
        }
        SavedServersList.Items.Clear();
        foreach (var sv in _config.Servers)
        {
            SavedServersList.Items.Add(new ListBoxItem { Content = $"{sv.Name} — {sv.Address}", Tag = sv.Address });
        }
        _pickerLoading = false;
    }

    private void ServerPicked(object sender, SelectionChangedEventArgs e)
    {
        if (_pickerLoading || ServerPicker.SelectedItem is not ComboBoxItem item)
        {
            return;
        }
        _config.Server = (string)item.Tag;
        ServerBox.Text = _config.Server;
        _config.Save();
        UpdateServerText();
    }

    private void AddServer(object sender, RoutedEventArgs e)
    {
        string address = NewServerAddress.Text.Trim();
        if (address.Length == 0)
        {
            SetStatus("Indiquez l'adresse du serveur à enregistrer.", 0);
            return;
        }
        string name = NewServerName.Text.Trim();
        if (name.Length == 0)
        {
            name = address;
        }
        _config.Servers.RemoveAll(x => x.Address == address);
        _config.Servers.Add(new SavedServer { Name = name, Address = address });
        _config.Save();
        NewServerName.Text = "";
        NewServerAddress.Text = "";
        RefreshServerPicker();
        SetStatus($"Serveur « {name} » enregistré.", 0);
    }

    private void RemoveServer(object sender, RoutedEventArgs e)
    {
        if (SavedServersList.SelectedItem is not ListBoxItem item)
        {
            return;
        }
        string address = (string)item.Tag;
        _config.Servers.RemoveAll(x => x.Address == address);
        if (_config.Server == address)
        {
            _config.Server = "";
            ServerBox.Text = "";
        }
        _config.Save();
        RefreshServerPicker();
        UpdateServerText();
    }

    private void ImportKeys(object sender, RoutedEventArgs e)
    {
        string? usual = Keys.FindUsual();
        if (usual == null)
        {
            KeysHint.Text = "Aucun autre Minecraft trouvé sur ce PC : réglez vos touches en jeu (Options › Commandes), elles seront gardées.";
            return;
        }
        Profile.Sync();
        var changes = Keys.Import(usual);
        KeysHint.Text = changes.Count == 0
            ? $"Vos touches sont déjà celles de votre Minecraft habituel ({usual})."
            : $"Touches reprises de {usual} :\n" + string.Join("\n", changes.Take(12))
              + (changes.Count > 12 ? $"\n… et {changes.Count - 12} autres." : "") + "\nAppliqué au prochain lancement.";
    }

    private void MakeCrashReport(object sender, RoutedEventArgs e)
    {
        try
        {
            string zip = CrashReport.Create();
            SetStatus("Rapport enregistré sur le Bureau : " + Path.GetFileName(zip), 0);
            CrashReport.Reveal(zip);
        }
        catch (Exception ex)
        {
            SetStatus("Rapport impossible : " + ex.Message, 0);
        }
    }

    private void UpdateServerText()
    {
        if (!_pickerLoading && ServerPicker.Items.Count == 0)
        {
            RefreshServerPicker();
        }
        ServerText.Text = string.IsNullOrWhiteSpace(ServerAddress) ? "Non renseigné (Réglages)." : ServerAddress;
        _ = RefreshServerStatusAsync();
    }

    /// <summary>Interroge le serveur : en ligne ? combien de joueurs ? (rafraîchi toutes les 30 secondes)</summary>
    private async Task RefreshServerStatusAsync()
    {
        string address = ServerAddress;
        if (string.IsNullOrWhiteSpace(address) || _captureDir != null)
        {
            return;
        }
        var st = await ServerStatus.PingAsync(address);
        if (address != ServerAddress)
        {
            return;
        }
        ServerDot.Fill = new SolidColorBrush(st.Online ? Color.FromRgb(0x5F, 0xB8, 0x5A) : Color.FromRgb(0xB0, 0x40, 0x3A));
        ShowMotd(st.Motd);
        ServerText.Text = !st.Online
            ? $"{address} — hors ligne"
            : $"{address} — en ligne, {st.Players}/{st.Max} joueur(s)"
                + (st.Names.Count > 0 ? Environment.NewLine + string.Join(", ", st.Names) : "");
    }

    /// <summary>Description du serveur : la première ligne annonce la prochaine soirée, la seconde le salon.</summary>
    private void ShowMotd(string motd)
    {
        var lines = (motd ?? "").Replace("\r", "").Split('\n', StringSplitOptions.RemoveEmptyEntries);
        bool evening = lines.Length > 0 && lines[0].StartsWith("Prochaine soirée", StringComparison.Ordinal);
        SoireeBox.Visibility = evening || lines.Length > 1 ? Visibility.Visible : Visibility.Collapsed;
        SoireeText.Text = string.Join(Environment.NewLine, evening ? lines : lines.Skip(1));
    }

    /// <summary>Le carnet du joueur : écrit par le mod à chaque passage au salon, lu ici.</summary>
    private void LoadCarnet()
    {
        CarnetStats.Children.Clear();
        string? file = new[] { Profile.Dir }.Concat(Profile.GameDirs)
            .Select(d => Path.Combine(d, "valcombe-carnet.json"))
            .Where(File.Exists)
            .OrderByDescending(File.GetLastWriteTimeUtc)
            .FirstOrDefault();
        if (file == null)
        {
            CarnetHint.Text = "Votre carnet se remplit après votre première partie sur un serveur Valcombe : "
                + "parties, victoires, camps et rôles joués.";
            CarnetTitle.Text = "";
            CarnetRoles.Text = "";
            return;
        }
        try
        {
            using var doc = System.Text.Json.JsonDocument.Parse(File.ReadAllText(file));
            var r = doc.RootElement;
            int Get(string k) => r.TryGetProperty(k, out var v) && v.TryGetInt32(out int n) ? n : 0;
            string Str(string k) => r.TryGetProperty(k, out var v) ? v.GetString() ?? "" : "";
            CarnetHint.Text = $"{Str("joueur")} sur {Str("serveur")} — mis à jour le {Str("misAJour")}.";
            string titre = Str("titre");
            CarnetTitle.Text = titre.Length > 0 ? $"« {titre} »" : "";
            int games = Get("parties");
            int wins = Get("victoires");
            void Stat(string label, string value)
            {
                var sp = new StackPanel { Margin = new Thickness(0, 0, 12, 12) };
                sp.Children.Add(new TextBlock { Text = value, FontSize = 22, Foreground = (Brush)FindResource("Ink"),
                    FontFamily = (FontFamily)FindResource("Title") });
                sp.Children.Add(new TextBlock { Text = label, Style = (Style)FindResource("Hint") });
                CarnetStats.Children.Add(sp);
            }
            Stat("parties", games.ToString());
            Stat("victoires", games == 0 ? "0" : $"{wins} ({100 * wins / games} %)");
            Stat("survivant en fin de partie", Get("survivant").ToString());
            Stat("dans le bien", $"{Get("bien")} ({Get("victoiresBien")} gagnées)");
            Stat("dans le mal", $"{Get("mal")} ({Get("victoiresMal")} gagnées)");
            Stat("parties menées en Conteur", Get("conteur").ToString());
            var roles = new List<(string, int)>();
            if (r.TryGetProperty("roles", out var rs) && rs.ValueKind == System.Text.Json.JsonValueKind.Object)
            {
                foreach (var p in rs.EnumerateObject())
                {
                    roles.Add((p.Name, p.Value.TryGetInt32(out int n) ? n : 0));
                }
            }
            CarnetRoles.Text = roles.Count == 0 ? "Aucun pour l'instant."
                : string.Join(", ", roles.OrderByDescending(x => x.Item2).Select(x => $"{x.Item1} ×{x.Item2}"));
        }
        catch (Exception ex) when (ex is IOException or System.Text.Json.JsonException)
        {
            CarnetHint.Text = "Carnet illisible : il sera réécrit à votre prochaine visite au salon.";
        }
    }

    /// <summary>Mémoire de l'ordinateur, en Go (0 si inconnue).</summary>
    private static int TotalMemoryGb()
    {
        long bytes = GC.GetGCMemoryInfo().TotalAvailableMemoryBytes;
        return bytes <= 0 ? 0 : (int)Math.Round(bytes / 1024.0 / 1024 / 1024);
    }

    /// <summary>Mémoire conseillée pour Minecraft avec le mod et le vocal : jamais plus de la moitié de l'ordinateur.</summary>
    private static int RecommendedMemoryGb(int total) =>
        Math.Max(2, Math.Min(total / 2, total <= 8 ? 3 : total <= 16 ? 4 : 6));

    /// <summary>Préréglages de performance : quelques curseurs du launcher et des options avancées de Minecraft.</summary>
    private void ApplyPreset(object sender, RoutedEventArgs e)
    {
        string preset = (sender as Button)?.Tag as string ?? "equilibre";
        (int render, int fps, Dictionary<string, string> extra, string label) = preset switch
        {
            "petite" => (6, 60, new Dictionary<string, string>
            {
                ["simulationDistance"] = "5", ["particles"] = "2", ["entityShadows"] = "false",
                ["biomeBlendRadius"] = "0", ["mipmapLevels"] = "0",
            }, "Petite machine"),
            "haute" => (16, 240, new Dictionary<string, string>
            {
                ["simulationDistance"] = "12", ["particles"] = "0", ["entityShadows"] = "true",
                ["biomeBlendRadius"] = "5", ["mipmapLevels"] = "4",
            }, "Haute qualité"),
            _ => (10, 120, new Dictionary<string, string>
            {
                ["simulationDistance"] = "8", ["particles"] = "1", ["entityShadows"] = "true",
                ["biomeBlendRadius"] = "2", ["mipmapLevels"] = "4",
            }, "Équilibré"),
        };
        RenderSlider.Value = render;
        FpsSlider.Value = fps;
        OptionChanged(sender, e);
        foreach (string dir in new[] { Profile.Dir }.Concat(Profile.GameDirs.Where(Directory.Exists)))
        {
            Instance.ApplyValues(extra, dir);
        }
        PresetHint.Text = $"Préréglage « {label} » appliqué. Il prendra effet au prochain lancement du jeu.";
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
            RememberHost(saved);
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
        RememberHost(account);
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
            Instance.PrepareOptions(_config.Minecraft, Paths.GameDir);
            if (_release != null)
            {
                await Instance.SyncExtrasAsync(_release, Paths.GameDir, _config, progress);
            }
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
            Prism.Launch(_playAddress ?? ServerAddress);
            if (CloseAfterLaunch)
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
            _playAddress = null;
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
        await Instance.SyncEngineAsync(release, progress);
        Instance.PrepareOptions(_config.Minecraft, Engine.GameDir);
        await Instance.SyncExtrasAsync(release, Engine.GameDir, _config, progress);
        SetStatus("Lancement de Minecraft…", 1);
        var game = Engine.Launch(plan, Engine.BuildArguments(plan, account, _config.MicrosoftClientId, _config.MaxMemoryMb,
            _playAddress ?? ServerAddress));
        if (!CloseAfterLaunch)
        {
            // Le jeu s'arrête brutalement : on propose le rapport de plantage.
            game.EnableRaisingEvents = true;
            game.Exited += (_, _) => Dispatcher.Invoke(() =>
            {
                Profile.Sync(); // les réglages faits pendant la partie partent dans le profil
                if (game.ExitCode != 0
                    && MessageBox.Show(this, "Le jeu s'est arrêté brutalement. Préparer un rapport de plantage à envoyer à l'hôte ?",
                        "Les Cendres de Valcombe", MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes)
                {
                    MakeCrashReport(this, new RoutedEventArgs());
                }
            });
        }
        if (CloseAfterLaunch)
        {
            await Task.Delay(1500);
            Close();
        }
    }

    /// <summary>Notes de version (Markdown de GitHub) en texte simple : titres, gras et puces lisibles.</summary>
    private static string PlainText(string markdown)
    {
        var lines = new List<string>();
        foreach (string raw in markdown.Replace("\r", "").Split('\n'))
        {
            string line = raw.Trim();
            line = System.Text.RegularExpressions.Regex.Replace(line, @"^#+\s*", "");
            line = line.Replace("**", "").Replace("`", "");
            if (line.StartsWith("- "))
            {
                line = "• " + line[2..];
            }
            lines.Add(line);
        }
        return System.Text.RegularExpressions.Regex.Replace(string.Join("\n", lines).Trim(), "\n{3,}", "\n\n");
    }

    /// <summary>
    /// Vérification de l'installation du serveur local, sans le démarrer : ValcombeLauncher.exe --verifier-serveur rapport.txt
    /// (Java, lanceur Fabric et mods du serveur sont téléchargés dans ValcombeData/serveur.)
    /// </summary>
    private async Task CheckServerAsync(string report)
    {
        var lines = new List<string>();
        try
        {
            var release = await Releases.LatestAsync(_config.Repository);
            string java = await _server.InstallAsync(release, new Progress<(string, double)>(p => SetStatus(p.Item1, p.Item2)));
            lines.Add($"Version {release.Tag} — Minecraft {release.Manifest.Minecraft}, Fabric {release.Manifest.Loader}");
            lines.Add($"Java : {java} ({(File.Exists(java) ? "présent" : "ABSENT")})");
            string jar = Path.Combine(LocalServer.Dir, "fabric-server-launch.jar");
            lines.Add($"Lanceur Fabric : {(File.Exists(jar) ? new FileInfo(jar).Length + " octets" : "ABSENT")}");
            lines.Add("Mods du serveur :");
            lines.AddRange(Directory.GetFiles(Path.Combine(LocalServer.Dir, "mods"), "*.jar").Select(f => "  " + Path.GetFileName(f)));
            lines.Add("Adresses locales : " + string.Join(", ", LocalServer.LocalAddresses(_config.HostPort)));
        }
        catch (Exception ex)
        {
            lines.Add("ERREUR : " + ex);
        }
        await File.WriteAllLinesAsync(report, lines);
        Close();
    }

    /// <summary>
    /// Essai complet du serveur local : ValcombeLauncher.exe --essai-serveur rapport.txt [--mod-local mod.jar]
    /// Il démarre le serveur, attend qu'il soit prêt, interroge l'Atelier avec le lien de l'hôte, puis l'arrête.
    /// Le contrat de Minecraft doit déjà avoir été accepté dans le launcher.
    /// </summary>
    private async Task TrialServerAsync(string report)
    {
        var lines = new List<string>();
        void Note(string text) => lines.Add($"{DateTime.Now:HH:mm:ss} {text}");
        try
        {
            if (!_config.HostEulaAccepted)
            {
                throw new InvalidOperationException("Contrat de Minecraft non accepté dans le launcher (page Héberger).");
            }
            var release = await Releases.LatestAsync(_config.Repository);
            Note($"Version {release.Tag}" + (LocalServer.ModOverride != null ? $" avec le mod local {Path.GetFileName(LocalServer.ModOverride)}" : ""));
            _config.HostGuests = new List<string> { "InviteEssai" };
            await _server.StartAsync(release, _config, "Hote_Essai", new Progress<(string, double)>(p => SetStatus(p.Item1, p.Item2)));
            Note("Processus démarré, état : " + _server.Current);
            var clock = Stopwatch.StartNew();
            while (_server.Current is LocalServer.State.Starting or LocalServer.State.Preparing && clock.Elapsed < TimeSpan.FromMinutes(6))
            {
                await Task.Delay(500);
            }
            Note($"État après {clock.Elapsed.TotalSeconds:0} s : {_server.Current}" + (_server.LastError.Length > 0 ? " — " + _server.LastError : ""));
            if (_server.Current == LocalServer.State.Running)
            {
                for (int i = 0; i < 40 && _server.AtelierPort == 0; i++)
                {
                    await Task.Delay(250);
                }
                Note("Port de l'Atelier annoncé par le mod : " + _server.AtelierPort);
                var status = await ServerStatus.PingAsync(_config.HostPort == 25565 ? "localhost" : "localhost:" + _config.HostPort);
                Note($"Réponse du serveur de jeu : en ligne = {status.Online}, {status.Players}/{status.Max} joueurs, « {status.Motd} »");
                if (_server.AtelierUrl != null)
                {
                    using var ok = new HttpRequestMessage(HttpMethod.Get, $"http://localhost:{_server.AtelierPort}/api/atelier/donnees");
                    ok.Headers.Add("X-Valcombe-Jeton", _server.AtelierToken);
                    using var answer = await Http.Client.SendAsync(ok);
                    string body = await answer.Content.ReadAsStringAsync();
                    Note($"Atelier avec le lien de l'hôte : {(int)answer.StatusCode}, {body.Length} caractères, commence par {body[..Math.Min(60, body.Length)]}");
                    using var bad = new HttpRequestMessage(HttpMethod.Get, $"http://localhost:{_server.AtelierPort}/api/atelier/donnees");
                    bad.Headers.Add("X-Valcombe-Jeton", "0000");
                    using var refused = await Http.Client.SendAsync(bad);
                    Note("Atelier avec un mauvais lien : " + (int)refused.StatusCode);
                    using var page = await Http.Client.GetAsync($"http://localhost:{_server.AtelierPort}/atelier");
                    Note("Page de l'Atelier : " + (int)page.StatusCode);
                }
                _server.Send("whitelist list");
                _server.Send("list");
                await Task.Delay(1500);
                Note("Arrêt demandé");
                clock.Restart();
                await _server.StopAsync();
                Note($"État après l'arrêt ({clock.Elapsed.TotalSeconds:0} s) : {_server.Current}");
            }
            lines.Add("--- Fin du journal du serveur ---");
            lines.AddRange(_server.LogText.Split(Environment.NewLine).TakeLast(28));
        }
        catch (Exception ex)
        {
            lines.Add("ERREUR : " + ex);
        }
        if (_server.Current != LocalServer.State.Stopped)
        {
            _server.StopNow();
        }
        await File.WriteAllLinesAsync(report, lines);
        Closing -= OnClosing;
        Close();
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
    // Héberger une partie sur ce PC, et ouvrir l'Atelier
    // ------------------------------------------------------------------
    /// <summary>Le pseudo de l'hôte est retenu à sa première connexion : il sera opérateur de son serveur.</summary>
    private void RememberHost(MinecraftAccount account)
    {
        if (string.IsNullOrWhiteSpace(_config.HostName) && !string.IsNullOrWhiteSpace(account.Name))
        {
            _config.HostName = account.Name;
            _config.Save();
            HostNameBox.Text = account.Name;
        }
    }

    private void HostSettingsChanged(object sender, RoutedEventArgs e)
    {
        if (_loading)
        {
            return;
        }
        _config.HostEulaAccepted = HostEulaBox.IsChecked == true;
        _config.HostGuestsOnly = HostGuestsOnlyBox.IsChecked == true;
        string name = HostNameBox.Text.Trim();
        _config.HostName = System.Text.RegularExpressions.Regex.IsMatch(name, "^[A-Za-z0-9_]{1,16}$") ? name : "";
        _config.HostPort = int.TryParse(HostPortBox.Text.Trim(), out int port) && port is >= 1024 and <= 65535 ? port : 25565;
        HostPortBox.Text = _config.HostPort.ToString();
        _config.HostMemoryMb = (int)HostMemorySlider.Value * 1024;
        UpdateLabels();
        _config.Save();
        // Appliqué tout de suite si le serveur tourne ; sinon au prochain démarrage.
        if (sender == HostGuestsOnlyBox && _server.Current == LocalServer.State.Running)
        {
            _server.Send(_config.HostGuestsOnly ? "whitelist on" : "whitelist off");
        }
        _shownHostState = null;
        RefreshHost();
    }

    private void GuestKey(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            AddGuest(sender, e);
        }
    }

    private void AddGuest(object sender, RoutedEventArgs e)
    {
        string name = HostGuestBox.Text.Trim();
        if (!System.Text.RegularExpressions.Regex.IsMatch(name, "^[A-Za-z0-9_]{3,16}$"))
        {
            SetStatus("Un pseudo Minecraft fait 3 à 16 caractères : lettres sans accent, chiffres et « _ ».", 0);
            return;
        }
        if (!_config.HostGuests.Contains(name, StringComparer.OrdinalIgnoreCase))
        {
            _config.HostGuests.Add(name);
            _config.Save();
            _server.Send("whitelist add " + name);
        }
        HostGuestBox.Text = "";
        BuildGuests();
    }

    private void BuildGuests()
    {
        HostGuestsPanel.Children.Clear();
        foreach (string guest in _config.HostGuests)
        {
            string name = guest;
            var remove = new Button
            {
                Content = name + "   ✕", Style = (Style)FindResource("Ghost"), Margin = new Thickness(0, 0, 8, 8), Padding = new Thickness(12, 5, 12, 5),
                ToolTip = "Retirer " + name + " des invités",
            };
            remove.Click += (_, _) =>
            {
                _config.HostGuests.Remove(name);
                _config.Save();
                _server.Send("whitelist remove " + name);
                BuildGuests();
            };
            HostGuestsPanel.Children.Add(remove);
        }
        if (_config.HostGuests.Count == 0)
        {
            HostGuestsPanel.Children.Add(new TextBlock { Text = "Aucun invité pour l'instant.", Style = (Style)FindResource("Hint") });
        }
    }

    /// <summary>Adresses à donner aux amis du même réseau, chacune avec son bouton « Copier ».</summary>
    private void BuildAddresses()
    {
        HostAddresses.Children.Clear();
        var list = _captureDir != null ? new List<string> { "192.168.1.24" } : LocalServer.LocalAddresses(_config.HostPort);
        if (list.Count == 0)
        {
            HostAddresses.Children.Add(new TextBlock { Text = "Ce PC n'est relié à aucun réseau.", Style = (Style)FindResource("Hint") });
        }
        foreach (string address in list.Take(3))
        {
            var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 8) };
            row.Children.Add(new TextBox { Text = address, IsReadOnly = true, Width = 220, FontFamily = new FontFamily("Consolas"), FontSize = 14 });
            var copy = new Button { Content = "Copier", Style = (Style)FindResource("Ghost"), Margin = new Thickness(10, 0, 0, 0) };
            copy.Click += (_, _) =>
            {
                Clipboard.SetText(address);
                SetStatus($"Adresse {address} copiée : envoyez-la à vos amis.", 0);
            };
            row.Children.Add(copy);
            HostAddresses.Children.Add(row);
        }
    }

    /// <summary>Met la page Héberger et la page Atelier à jour : état, joueurs, journal, boutons.</summary>
    private void RefreshHost()
    {
        var st = _captureDir != null ? LocalServer.State.Running : _server.Current;
        var players = _captureDir != null ? new List<string> { "Yoann", "Lea", "Hugo" } : _server.Players.ToList();
        (string title, Brush dot) = st switch
        {
            LocalServer.State.Running => ("Serveur en ligne", (Brush)FindResource("Good")),
            LocalServer.State.Preparing => ("Installation du serveur…", (Brush)FindResource("Gold")),
            LocalServer.State.Starting => ("Démarrage du serveur…", (Brush)FindResource("Gold")),
            LocalServer.State.Stopping => ("Arrêt du serveur…", (Brush)FindResource("Gold")),
            _ => ("Serveur arrêté", new SolidColorBrush(Color.FromRgb(0x8A, 0x80, 0x80))),
        };
        HostStateText.Text = title;
        HostDot.Fill = dot;
        NavHostDot.Visibility = st == LocalServer.State.Running ? Visibility.Visible : Visibility.Collapsed;
        HostDetail.Text = st switch
        {
            LocalServer.State.Running => (players.Count == 0 ? "Personne n'est encore connecté. Donnez l'adresse ci-dessous à vos amis."
                : $"{players.Count} joueur(s) connecté(s) : {string.Join(", ", players)}")
                + (_server.Notice.Length > 0 ? Environment.NewLine + _server.Notice : ""),
            LocalServer.State.Preparing => "Téléchargement de Java, de Fabric et des mods du serveur : une seule fois.",
            LocalServer.State.Starting => "La première fois, le serveur télécharge Minecraft et crée le monde : comptez une à deux minutes.",
            LocalServer.State.Stopping => "Le monde est enregistré…",
            _ => _server.LastError.Length > 0 ? _server.LastError
                : _server.IsInstalled ? "Prêt à démarrer." : "Le serveur sera installé au premier démarrage (environ 250 Mo).",
        };
        HostStartButton.Content = st switch
        {
            LocalServer.State.Stopped => "Démarrer le serveur",
            LocalServer.State.Running => "Arrêter le serveur",
            LocalServer.State.Stopping => "Arrêt…",
            _ => "Démarrage…",
        };
        HostStartButton.IsEnabled = st is LocalServer.State.Stopped or LocalServer.State.Running;
        string? url = _captureDir != null ? "http://localhost:25570/atelier" : _server.AtelierUrl;
        AtelierCopyButton.IsEnabled = url != null;
        AtelierOpenButton.Content = url != null ? "Ouvrir l'Atelier" : st == LocalServer.State.Stopped ? "Démarrer et ouvrir l'Atelier" : "Ouverture…";
        AtelierOpenButton.IsEnabled = st is LocalServer.State.Stopped or LocalServer.State.Running;
        AtelierStateText.Text = url != null ? "L'Atelier est ouvert sur ce PC. Le serveur tourne : vos amis peuvent aussi vous rejoindre (page Héberger)."
            : st == LocalServer.State.Stopped
                ? (_config.HostEulaAccepted ? "Le serveur de ce PC démarre, puis l'Atelier s'ouvre dans votre navigateur."
                    : "Avant la première ouverture, acceptez le contrat de Minecraft sur la page « Héberger une partie ».")
                : "Le serveur démarre : l'Atelier s'ouvrira dès qu'il sera prêt.";
        if (!string.Equals(HostLog.Text, _server.LogText, StringComparison.Ordinal))
        {
            HostLog.Text = _server.LogText;
            HostLog.ScrollToEnd();
        }
        if (_shownHostState != st)
        {
            _shownHostState = st;
            BuildAddresses();
            BuildGuests();
        }
        if (st == LocalServer.State.Stopped)
        {
            _openAtelierWhenReady = false;
            _playWhenReady = false;
        }
        if (_openAtelierWhenReady && _server.AtelierUrl != null)
        {
            _openAtelierWhenReady = false;
            OpenInBrowser(_server.AtelierUrl);
            SetStatus("L'Atelier est ouvert dans votre navigateur.", 0);
        }
        if (_playWhenReady && st == LocalServer.State.Running && _captureDir == null)
        {
            _playWhenReady = false;
            PlayOnHost();
        }
    }

    private static void OpenInBrowser(string url) => Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });

    /// <summary>Démarre le serveur de ce PC s'il ne tourne pas. Renvoie false si ce n'est pas possible.</summary>
    private async Task<bool> StartServerAsync()
    {
        if (_server.Current != LocalServer.State.Stopped)
        {
            return true;
        }
        if (!_config.HostEulaAccepted)
        {
            NavHost.IsChecked = true;
            SetStatus("Avant d'héberger, lisez et acceptez le contrat de Minecraft (case sous le bouton Démarrer).", 0);
            return false;
        }
        if (_release == null)
        {
            await RefreshReleaseAsync();
        }
        if (_release == null)
        {
            SetStatus("Version introuvable : vérifiez votre connexion à Internet.", 0);
            return false;
        }
        // Le pseudo de l'hôte, s'il s'est déjà connecté : pas de fenêtre de connexion pour héberger.
        if (string.IsNullOrWhiteSpace(_config.HostName) && Integrated && MicrosoftAuth.HasSavedAccount)
        {
            try
            {
                var account = await new MicrosoftAuth(_config.MicrosoftClientId).RefreshAsync(CancellationToken.None);
                if (account != null)
                {
                    RememberHost(account);
                }
            }
            catch (Exception)
            {
                // sans réseau ou compte expiré : l'hôte saisira son pseudo
            }
        }
        try
        {
            await _server.StartAsync(_release, _config, _config.HostName, new Progress<(string, double)>(p => SetStatus(p.Item1, p.Item2)));
            return true;
        }
        catch (Exception ex)
        {
            SetStatus("Serveur : " + ex.Message, 0);
            return false;
        }
    }

    private async void HostToggle(object sender, RoutedEventArgs e)
    {
        if (_server.Current == LocalServer.State.Stopped)
        {
            await StartServerAsync();
            return;
        }
        int players = _server.Players.Count;
        if (players > 0 && MessageBox.Show(this, $"{players} joueur(s) sont connectés. Arrêter le serveur met fin à la soirée (la partie en cours est enregistrée).",
                "Les Cendres de Valcombe", MessageBoxButton.OKCancel, MessageBoxImage.Warning) != MessageBoxResult.OK)
        {
            return;
        }
        SetStatus("Arrêt du serveur…", 0);
        await _server.StopAsync();
        SetStatus("Serveur arrêté. Le monde et vos créations sont enregistrés.", 0);
    }

    /// <summary>Jouer sur son propre serveur : il est démarré si besoin, puis le jeu s'y connecte.</summary>
    private async void HostPlay(object sender, RoutedEventArgs e)
    {
        if (_server.Current == LocalServer.State.Running)
        {
            PlayOnHost();
            return;
        }
        _playWhenReady = true;
        if (!await StartServerAsync())
        {
            _playWhenReady = false;
            return;
        }
        SetStatus("Le jeu se lancera dès que le serveur sera prêt…", 0);
    }

    private void PlayOnHost()
    {
        if (!PlayButton.IsEnabled)
        {
            return; // un lancement est déjà en cours
        }
        _playAddress = _config.HostPort == 25565 ? "localhost" : "localhost:" + _config.HostPort;
        Play(this, new RoutedEventArgs());
    }

    private async void OpenAtelier(object sender, RoutedEventArgs e)
    {
        if (_server.AtelierUrl != null)
        {
            OpenInBrowser(_server.AtelierUrl);
            return;
        }
        if (_server.Current == LocalServer.State.Running)
        {
            SetStatus("Ce serveur a démarré sans le lien de l'Atelier : arrêtez-le puis redémarrez-le depuis cette page.", 0);
            return;
        }
        _openAtelierWhenReady = true;
        if (!await StartServerAsync())
        {
            _openAtelierWhenReady = false;
            return;
        }
        SetStatus("L'Atelier s'ouvrira dès que le serveur sera prêt…", 0);
    }

    private void CopyAtelierLink(object sender, RoutedEventArgs e)
    {
        if (_server.AtelierUrl != null)
        {
            Clipboard.SetText(_server.AtelierUrl);
            SetStatus("Lien de l'Atelier copié. Ne le partagez pas : il donne tous les droits sur l'Atelier.", 0);
        }
    }

    private void OpenEula(object sender, RoutedEventArgs e) => OpenInBrowser("https://aka.ms/MinecraftEULA");

    private void OpenHostFolder(object sender, RoutedEventArgs e)
    {
        Directory.CreateDirectory(LocalServer.Dir);
        Process.Start(new ProcessStartInfo("explorer.exe", LocalServer.Dir));
    }

    private void CommandKey(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            SendHostCommand(sender, e);
        }
    }

    private void SendHostCommand(object sender, RoutedEventArgs e)
    {
        string command = HostCommandBox.Text.Trim().TrimStart('/');
        if (command.Length == 0)
        {
            return;
        }
        if (_server.Current != LocalServer.State.Running)
        {
            SetStatus("Le serveur ne tourne pas.", 0);
            return;
        }
        _server.Send(command);
        HostCommandBox.Text = "";
    }

    /// <summary>L'adresse publique de la box, à donner aux amis une fois les ports ouverts.</summary>
    private async void ShowPublicAddress(object sender, RoutedEventArgs e)
    {
        HostPublicText.Text = "Recherche…";
        try
        {
            string ip = (await Http.Client.GetStringAsync("https://api.ipify.org")).Trim();
            if (!System.Net.IPAddress.TryParse(ip, out _))
            {
                throw new InvalidOperationException("réponse illisible");
            }
            Clipboard.SetText(_config.HostPort == 25565 ? ip : $"{ip}:{_config.HostPort}");
            HostPublicText.Text = $"Votre adresse publique : {ip}" + (_config.HostPort == 25565 ? "" : $":{_config.HostPort}")
                + " (copiée). Elle ne mène à ce PC qu'une fois les ports ouverts sur la box, et peut changer d'un jour à l'autre.";
        }
        catch (Exception ex)
        {
            HostPublicText.Text = "Adresse publique introuvable : " + ex.Message;
        }
    }

    /// <summary>Fermer le launcher arrête le serveur de ce PC : on le dit, et on laisse le monde s'enregistrer.</summary>
    private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (_server.Current == LocalServer.State.Stopped)
        {
            return;
        }
        if (MessageBox.Show(this, "Le serveur de Valcombe tourne sur ce PC. Fermer le launcher l'arrête : la soirée s'interrompt pour tous "
                + "(la partie en cours est enregistrée).", "Les Cendres de Valcombe", MessageBoxButton.OKCancel, MessageBoxImage.Warning)
            != MessageBoxResult.OK)
        {
            e.Cancel = true;
            return;
        }
        SetStatus("Arrêt du serveur…", 0);
        _server.StopNow();
    }

    private static IEnumerable<RadioButton> FindNav(DependencyObject parent)
    {
        for (int i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is RadioButton rb && rb.Tag is string)
            {
                yield return rb;
            }
            foreach (var r in FindNav(child))
            {
                yield return r;
            }
        }
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
        _config.Servers = new List<SavedServer>
        {
            new() { Name = "Serveur de Léa", Address = "lea.exemple.fr" },
            new() { Name = "Soirée du vendredi", Address = "vendredi.exemple.fr:25570" },
        };
        RefreshServerPicker();
        ServerText.Text = "valcombe.exemple.fr — en ligne, 7/20 joueur(s)" + Environment.NewLine + "Léa, Hugo, Inès, Malo";
        ServerDot.Fill = new SolidColorBrush(Color.FromRgb(0x5F, 0xB8, 0x5A));
        ShowMotd("Prochaine soirée : vendredi 21 h\n5 inscrit(s) au salon · « Le Bal masqué »");
        string demo = Path.Combine(Path.GetTempPath(), "valcombe-apercu-carnet");
        Profile.DirOverride = Path.Combine(Path.GetTempPath(), "valcombe-apercu-profil");
        Directory.CreateDirectory(demo);
        File.WriteAllText(Path.Combine(demo, "valcombe-carnet.json"), "{\"joueur\":\"Léa\",\"parties\":12,\"victoires\":7,"
            + "\"bien\":8,\"mal\":4,\"victoiresBien\":5,\"victoiresMal\":2,\"survivant\":6,\"conteur\":1,"
            + "\"titre\":\"Pilier de Valcombe\",\"roles\":{\"La Veilleuse\":3,\"La Goule\":2,\"Le Greffier\":2,"
            + "\"Le Templier\":1},\"serveur\":\"valcombe.exemple.fr\",\"misAJour\":\"2026-11-11\"}");
        Profile.GameDirsOverride = new[] { demo };
        _config.HostGuests = new List<string> { "Lea", "Hugo", "Ines" };
        _config.HostName = "Yoann";
        HostNameBox.Text = "Yoann";
        foreach (string page in new[] { "Home", "Host", "Atelier", "Guide", "Carnet", "Game", "Look", "Settings", "Account" })
        {
            foreach (var rb in FindNav(this))
            {
                if ((string)rb.Tag == page)
                {
                    rb.IsChecked = true;
                }
            }
            ShowPage(page);
            await Task.Delay(450);
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
