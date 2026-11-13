using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Valcombe.Launcher.Services;
using Valcombe.Launcher.Services.Admin;

namespace Valcombe.Launcher;

/// <summary>
/// Page « Serveur » : piloter le serveur de ce PC (celui que le launcher installe et démarre) ou un serveur
/// distant enregistré, par l'API d'administration du mod (docs/ADMIN-API.md).
///
/// La page interroge le serveur choisi une fois par seconde tant qu'elle est affichée : l'état et les joueurs
/// toutes les trois secondes, la console à chaque fois sur l'onglet Console. Elle ne parle qu'à une
/// <see cref="IServerConnection"/> : l'accès direct aujourd'hui, le tunnel SSH demain, sans rien changer ici.
///
/// Quand le serveur de ce PC tourne une version du mod sans API, la page se rabat sur ce que le launcher
/// sait déjà : le journal du processus, les joueurs vus dans ce journal, et les commandes envoyées sur son entrée.
/// </summary>
public partial class MainWindow
{
    private const string LocalTarget = "local";
    private const int ConsoleMax = 1000;
    /// <summary>Valeur d'une tuile tant que le serveur n'a rien dit (un trait simple : pas de tiret cadratin à l'écran).</summary>
    private const string Unknown = "-";
    private static readonly CultureInfo Fr = CultureInfo.GetCultureInfo("fr-FR");

    /// <summary>Une ligne de la console telle que la page l'affiche.</summary>
    public sealed class ConsoleEntry
    {
        public string Time { get; init; } = "";
        public string Text { get; init; } = "";
        public Brush Brush { get; init; } = Brushes.White;
    }

    private readonly ObservableCollection<ConsoleEntry> _srvConsole = new();
    private ConsoleFeed _srvFeed = new();
    private IServerConnection? _srvConn;
    private AdminClient? _srvClient;
    /// <summary>Ce qui identifie la connexion en place : quand il change, la connexion est refaite.</summary>
    private string _srvConnId = "";
    /// <summary>Change à chaque nouvelle connexion : une réponse arrivée pour l'ancienne est ignorée.</summary>
    private int _srvGen;
    private bool _srvBusy;
    private int _srvTick;
    /// <summary>Pas de requête avant ce moment (serveur injoignable, ou blocage 429).</summary>
    private DateTime _srvQuietUntil = DateTime.MinValue;
    /// <summary>Clé refusée, adresse refusée, API absente : plus de requête jusqu'à « Réessayer ».</summary>
    private bool _srvHalted;
    private AdminException? _srvError;
    private AdminState? _srvState;
    private AdminPlayers? _srvPlayers;
    private AdminSettings? _srvSettings;
    private bool _srvSettingsStale = true;
    /// <summary>Les champs des réglages montrent les valeurs du serveur (remplis une fois, puis laissés à l'hôte).</summary>
    private bool _srvFieldsFilled;
    private bool _srvFilling;
    /// <summary>Clé d'un serveur distant absente ou illisible sur ce compte Windows (« », « manquante », « illisible »).</summary>
    private string _srvKeyProblem = "";
    /// <summary>Arrêt ou redémarrage demandé à un serveur distant : il va disparaître un moment.</summary>
    private string _srvStopping = "";
    private bool _srvStoppingSawDown;
    private DateTime _srvStoppingSince;
    private long _srvLocalLogSeq;
    private bool _srvLocalLog;
    private readonly List<string> _srvHistory = new();
    private int _srvHistoryPos;
    private string _srvTab = "apercu";
    private RemoteServer? _srvEditing;
    private bool _srvFormOpen;
    private bool _srvPickerLoading;
    private DemoAdminApi? _srvDemo;
    private DemoAdminApi? _srvDemoRemote;
    private readonly TextBlock[] _srvTileValue = new TextBlock[5];
    private readonly TextBlock[] _srvTileSub = new TextBlock[5];
    private readonly ProgressBar _srvMemoryBar = new() { Margin = new Thickness(0, 8, 0, 0), Maximum = 1, Height = 4 };
    private string _srvPlayersShown = "\0";
    private string _srvWorldsShown = "\0";

    // ------------------------------------------------------------------
    // Mise en place
    // ------------------------------------------------------------------
    private void InitServerPage()
    {
        SrvConsoleList.ItemsSource = _srvConsole;
        string[] labels = { "VERSION", "EN LIGNE DEPUIS", "TICKS PAR SECONDE", "MÉMOIRE", "JOUEURS" };
        for (int i = 0; i < labels.Length; i++)
        {
            var panel = new StackPanel();
            panel.Children.Add(new TextBlock { Text = labels[i], FontSize = 10.5, FontWeight = FontWeights.SemiBold, Foreground = new SolidColorBrush(Color.FromRgb(0x8C, 0x7F, 0x74)) });
            _srvTileValue[i] = new TextBlock
            {
                Text = Unknown, FontFamily = (FontFamily)FindResource("Title"), FontSize = 22, Foreground = (Brush)FindResource("Ink"),
                Margin = new Thickness(0, 4, 0, 0), TextTrimming = TextTrimming.CharacterEllipsis,
            };
            _srvTileSub[i] = new TextBlock { Style = (Style)FindResource("Hint"), Margin = new Thickness(0, 2, 0, 0), TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis };
            panel.Children.Add(_srvTileValue[i]);
            panel.Children.Add(_srvTileSub[i]);
            if (i == 3)
            {
                panel.Children.Add(_srvMemoryBar);
            }
            SrvTiles.Children.Add(new Border { Style = (Style)FindResource("Tile"), Child = panel });
        }
        RefreshSrvPicker();
        SrvFillFields();
        // Une interrogation par seconde, seulement quand la page est à l'écran.
        var timer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        timer.Tick += (_, _) =>
        {
            if (PageServer.Visibility == Visibility.Visible && WindowState != WindowState.Minimized)
            {
                _ = SrvPollAsync();
            }
        };
        timer.Start();
    }

    /// <summary>La page vient de s'afficher : réglages de ce PC à jour, et interrogation tout de suite.</summary>
    private void SrvOnShown()
    {
        if (IsLocalTarget && !_srvFormOpen)
        {
            _srvFieldsFilled = false;
            SrvFillFields();
        }
        _srvSettingsStale = true;
        _ = SrvPollAsync(force: true);
    }

    private RemoteServer? SelectedRemote => _config.RemoteServers.FirstOrDefault(r => r.Id == _config.AdminTarget);

    /// <summary>Pour les captures : montrer le serveur de ce PC arrêté plutôt qu'en ligne.</summary>
    private bool _srvCaptureStopped;

    /// <summary>L'état du serveur de ce PC tel que la page le montre (fictif pendant les captures).</summary>
    private LocalServer.State LocalState => _captureDir == null ? _server.Current
        : _srvCaptureStopped ? LocalServer.State.Stopped : LocalServer.State.Running;

    private bool IsLocalTarget => SelectedRemote == null;

    private string TargetName => SelectedRemote?.Name ?? "le serveur de ce PC";

    private void RefreshSrvPicker()
    {
        _srvPickerLoading = true;
        SrvPicker.Items.Clear();
        SrvPicker.Items.Add(new ComboBoxItem { Content = "Ce PC (le serveur hébergé ici)", Tag = LocalTarget });
        foreach (var r in _config.RemoteServers)
        {
            SrvPicker.Items.Add(new ComboBoxItem { Content = $"{r.Name}  ·  {r.Endpoint}", Tag = r.Id });
        }
        if (SelectedRemote == null)
        {
            _config.AdminTarget = LocalTarget;
        }
        foreach (ComboBoxItem item in SrvPicker.Items)
        {
            if ((string)item.Tag == _config.AdminTarget)
            {
                SrvPicker.SelectedItem = item;
            }
        }
        SrvEditButton.Visibility = IsLocalTarget ? Visibility.Collapsed : Visibility.Visible;
        _srvPickerLoading = false;
    }

    private void SrvPicked(object sender, SelectionChangedEventArgs e)
    {
        if (_srvPickerLoading || SrvPicker.SelectedItem is not ComboBoxItem item)
        {
            return;
        }
        _config.AdminTarget = (string)item.Tag;
        SaveConfig();
        SrvEditButton.Visibility = IsLocalTarget ? Visibility.Collapsed : Visibility.Visible;
        SrvCloseForm();
        SrvResultsBox.Visibility = Visibility.Collapsed;
        SrvCommandResult.Visibility = Visibility.Collapsed;
        _srvFieldsFilled = false;
        SrvEnsureConnection();
        SrvFillFields();
        SrvRender();
        _ = SrvPollAsync(force: true);
    }

    /// <summary>Les captures d'écran ne touchent jamais aux réglages enregistrés du joueur.</summary>
    private void SaveConfig()
    {
        if (_captureDir == null)
        {
            _config.Save();
        }
    }

    // ------------------------------------------------------------------
    // Connexion et interrogation
    // ------------------------------------------------------------------
    /// <summary>Met en place la connexion qui correspond au serveur choisi et à son état (refaite seulement si besoin).</summary>
    private void SrvEnsureConnection()
    {
        string id;
        Func<IServerConnection?> open = () => null;
        var remote = SelectedRemote;
        if (remote == null)
        {
            if (_captureDir != null && _srvCaptureStopped)
            {
                id = "local:apercu-arrete";
            }
            else if (_captureDir != null)
            {
                id = "local:apercu";
                open = () => new DemoConnection(_srvDemo ??= new DemoAdminApi(), "127.0.0.1:" + AdminClient.DefaultPort);
            }
            else if (_server.Current == LocalServer.State.Running && _server.AdminPort > 0)
            {
                int port = _server.AdminPort;
                id = $"local:{port}:{_server.Starts}";
                open = () => ServerConnections.OpenLocal(port);
            }
            else
            {
                // Arrêté, en train de démarrer, ou mod sans API : la page suit le journal du processus.
                id = $"local:sans-api:{_server.Starts}";
            }
        }
        else
        {
            id = $"distant:{remote.Id}:{remote.Access}:{remote.Host}:{remote.ApiPort}:{remote.ProtectedKey}";
            open = () =>
            {
                if (_captureDir != null)
                {
                    return new DemoConnection(_srvDemoRemote ??= new DemoAdminApi { RefuseRemote = true }, remote.Endpoint);
                }
                // La clé est déchiffrée une fois ici pour savoir si elle se lit sur ce compte Windows ;
                // la connexion la redéchiffre à chaque requête, elle ne reste pas en clair en mémoire.
                if (remote.ReadKey() == null)
                {
                    _srvKeyProblem = remote.ProtectedKey.Length == 0 ? "manquante" : "illisible";
                    return null;
                }
                return ServerConnections.Open(remote);
            };
        }
        if (id == _srvConnId)
        {
            return;
        }
        var old = _srvConn;
        if (old != null)
        {
            _ = old.DisposeAsync().AsTask();
        }
        _srvKeyProblem = "";
        _srvConnId = id;
        _srvGen++;
        _srvConn = open();
        _srvClient = _srvConn == null ? null : new AdminClient(_srvConn);
        _srvFeed = new ConsoleFeed();
        _srvConsole.Clear();
        _srvLocalLog = false;
        _srvLocalLogSeq = 0;
        _srvState = null;
        _srvPlayers = null;
        _srvSettings = null;
        _srvSettingsStale = true;
        _srvError = null;
        _srvHalted = false;
        _srvQuietUntil = DateTime.MinValue;
        _srvTick = 0;
        _srvStopping = "";
        _srvPlayersShown = "\0";
        _srvWorldsShown = "\0";
        if (!_srvFormOpen)
        {
            _srvFieldsFilled = false;
            SrvFillFields();
            SrvShowSettings();
        }
    }

    /// <summary>
    /// Une interrogation : état et joueurs (une fois sur trois), réglages (s'ils sont à relire), console.
    /// Une seule à la fois ; <paramref name="force"/> passe outre la pause qui suit une erreur.
    /// </summary>
    private async Task SrvPollAsync(bool force = false)
    {
        if (_srvBusy)
        {
            return;
        }
        _srvBusy = true;
        try
        {
            SrvEnsureConnection();
            var client = _srvClient;
            if (client == null)
            {
                SrvPollLocalLog();
                return;
            }
            if (!force && (_srvHalted || DateTime.UtcNow < _srvQuietUntil))
            {
                return;
            }
            int gen = _srvGen;
            var feed = _srvFeed;
            bool full = force || _srvState == null || _srvTick % 3 == 0;
            bool readConsole = full || _srvTab == "console";
            bool readSettings = _srvSettingsStale || (_srvTab == "reglages" && _srvTick % 15 == 0);
            _srvTick++;
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(15));
            try
            {
                AdminState? state = null;
                AdminPlayers? players = null;
                AdminSettings? settings = null;
                ConsoleFeed.Batch? batch = null;
                if (full)
                {
                    state = await client.GetStateAsync(cts.Token);
                    players = await client.GetPlayersAsync(cts.Token);
                }
                if (readSettings)
                {
                    settings = await client.GetSettingsAsync(cts.Token);
                }
                if (readConsole)
                {
                    batch = await feed.PollAsync(client, cts.Token);
                }
                if (gen != _srvGen)
                {
                    return; // autre serveur choisi pendant la requête
                }
                if (state != null)
                {
                    _srvState = state;
                    _srvPlayers = players;
                    SrvStoppingProgress(up: true);
                }
                if (settings != null)
                {
                    _srvSettings = settings;
                    _srvSettingsStale = false;
                    SrvShowSettings();
                }
                if (batch != null)
                {
                    SrvAppendConsole(batch);
                }
                _srvError = null;
                _srvHalted = false;
            }
            catch (AdminException ex) when (gen == _srvGen)
            {
                SrvFailed(ex);
            }
            catch (OperationCanceledException) when (gen == _srvGen)
            {
                SrvFailed(new AdminException(0, $"{client.Connection.Description} ne répond pas (délai dépassé)."));
            }
            catch (Exception) when (gen != _srvGen)
            {
                // la connexion a été remplacée (et fermée) pendant la requête : rien à montrer
            }
            catch (Exception ex)
            {
                // Tout le reste (réponse inattendue…) : affiché, et la page réessaie après une pause.
                SrvFailed(new AdminException(0, "Erreur inattendue : " + ex.Message, inner: ex));
            }
        }
        finally
        {
            _srvBusy = false;
            SrvRender();
        }
    }

    private void SrvFailed(AdminException ex)
    {
        _srvError = ex;
        if (ex.StopsPolling)
        {
            // Insister avec une clé refusée finirait par bloquer ce PC : on attend « Réessayer ».
            _srvHalted = true;
        }
        else
        {
            _srvQuietUntil = DateTime.UtcNow + (ex.RetryAfter ?? TimeSpan.FromSeconds(ex.Status == 0 ? 4 : 2));
        }
        if (ex.Status is 0 or 503)
        {
            _srvState = null;
            _srvPlayers = null;
            SrvStoppingProgress(up: false);
        }
    }

    /// <summary>Suit un arrêt ou un redémarrage demandé à un serveur distant : il disparaît, puis revient (ou non).</summary>
    private void SrvStoppingProgress(bool up)
    {
        if (_srvStopping.Length == 0)
        {
            return;
        }
        if (!up)
        {
            _srvStoppingSawDown = true;
            return;
        }
        if (_srvStoppingSawDown || DateTime.UtcNow - _srvStoppingSince > TimeSpan.FromMinutes(3))
        {
            if (_srvStopping == "redemarrage" && _srvStoppingSawDown)
            {
                SetStatus($"{TargetName} a redémarré.", 0);
            }
            _srvStopping = "";
        }
    }

    /// <summary>Serveur de ce PC sans API : la console montre le journal du processus, tel que le launcher le lit.</summary>
    private void SrvPollLocalLog()
    {
        if (!IsLocalTarget)
        {
            return;
        }
        var (last, lines) = _server.LogSince(_srvLocalLogSeq);
        bool stick = SrvConsoleAtBottom();
        if (!_srvLocalLog)
        {
            _srvConsole.Clear();
            _srvLocalLog = true;
        }
        _srvLocalLogSeq = last;
        foreach (string raw in lines)
        {
            _srvConsole.Add(ConsoleFeed.ParseProcessLine(raw) is { } l
                ? Entry(l.Time, l.Level, l.Text)
                : new ConsoleEntry { Text = raw, Brush = (Brush)FindResource("Ash") });
        }
        SrvTrimConsole(stick);
    }

    private ConsoleEntry Entry(string time, string level, string text) => new()
    {
        Time = time,
        Text = level is "INFO" or "" ? text : $"[{level}] {text}",
        Brush = level switch
        {
            "WARN" => (Brush)FindResource("Gold"),
            "ERROR" or "FATAL" => (Brush)FindResource("Bad"),
            _ => (Brush)FindResource("Parch"),
        },
    };

    private void SrvAppendConsole(ConsoleFeed.Batch batch)
    {
        bool stick = SrvConsoleAtBottom();
        if (batch.Reset)
        {
            _srvConsole.Clear();
        }
        if (batch.Lost > 0)
        {
            _srvConsole.Add(new ConsoleEntry
            {
                Text = $"… {batch.Lost} ligne(s) perdue(s) : la console a défilé plus vite que la page ne la lisait.",
                Brush = (Brush)FindResource("Ash"),
            });
        }
        foreach (var l in batch.Lines)
        {
            string time = DateTimeOffset.FromUnixTimeMilliseconds(l.T).ToLocalTime().ToString("HH:mm:ss", Fr);
            _srvConsole.Add(Entry(time, l.Niveau, l.Texte));
        }
        SrvTrimConsole(stick);
    }

    private void SrvTrimConsole(bool stick)
    {
        while (_srvConsole.Count > ConsoleMax)
        {
            _srvConsole.RemoveAt(0);
        }
        if (stick)
        {
            SrvConsoleScroll()?.ScrollToEnd();
        }
    }

    private ScrollViewer? SrvConsoleScroll()
    {
        SrvConsoleList.ApplyTemplate();
        return SrvConsoleList.Template.FindName("Scroll", SrvConsoleList) as ScrollViewer;
    }

    /// <summary>La console défile toute seule tant que l'hôte n'est pas remonté lire plus haut.</summary>
    private bool SrvConsoleAtBottom()
    {
        var sv = SrvConsoleScroll();
        return sv == null || sv.ScrollableHeight <= 0 || sv.VerticalOffset >= sv.ScrollableHeight - 4;
    }

    // ------------------------------------------------------------------
    // Affichage
    // ------------------------------------------------------------------
    private Brush Res(string key) => (Brush)FindResource(key);

    private static readonly Brush Grey = new SolidColorBrush(Color.FromRgb(0x8A, 0x80, 0x80));

    /// <summary>Met toute la page à jour d'après ce qu'on sait du serveur choisi.</summary>
    private void SrvRender()
    {
        if (SrvTitle == null)
        {
            return;
        }
        var st = _srvError == null ? _srvState : null;
        string title;
        string detail;
        Brush dot;
        bool canStart = false, starting = false, canStop = false, retry = false;
        string startText = "Démarrer";
        if (IsLocalTarget)
        {
            switch (LocalState)
            {
                case LocalServer.State.Stopped:
                    title = "Serveur arrêté";
                    dot = Grey;
                    detail = _server.LastError.Length > 0 ? _server.LastError
                        : _server.IsInstalled ? "Prêt à démarrer. Vos amis le rejoignent avec les adresses de la page Héberger."
                        : "Le serveur sera installé au premier démarrage (environ 250 Mo).";
                    canStart = true;
                    break;
                case LocalServer.State.Preparing:
                    (title, dot, detail, starting, startText) = ("Installation du serveur…", Res("Gold"),
                        "Téléchargement de Java, de Fabric et des mods du serveur : une seule fois.", true, "Démarrage…");
                    break;
                case LocalServer.State.Starting:
                    (title, dot, detail, starting, startText) = ("Démarrage du serveur…", Res("Gold"),
                        "La première fois, le serveur télécharge Minecraft et crée le monde : comptez une à deux minutes.", true, "Démarrage…");
                    break;
                case LocalServer.State.Stopping:
                    (title, dot, detail, starting, startText) = ("Arrêt du serveur…", Res("Gold"), "Le monde et la partie en cours sont enregistrés…", true, "Arrêt…");
                    break;
                default:
                    canStop = true;
                    title = "Serveur en ligne";
                    if (st != null)
                    {
                        dot = Res("Good");
                        detail = Describe(st) + " · API sur " + _srvConn!.Description;
                    }
                    else if (_srvClient == null)
                    {
                        dot = Res("Good");
                        detail = _server.AdminProblem.Length > 0
                            ? $"API d'administration {_server.AdminProblem.TrimEnd('.')}. La page suit le journal du serveur et lui envoie vos commandes."
                            : DateTime.UtcNow - _server.RunningSince < TimeSpan.FromSeconds(20)
                                ? "Ouverture de l'API d'administration…"
                                : "Cette version du mod n'a pas encore d'API d'administration (Valcombe 0.14) : la page suit le journal "
                                    + "du serveur et lui envoie vos commandes ; mondes et réglages viendront avec elle.";
                    }
                    else if (_srvError != null)
                    {
                        dot = Res("Gold");
                        detail = "API d'administration : " + _srvError.Message;
                        retry = _srvHalted;
                    }
                    else
                    {
                        dot = Res("Good");
                        detail = "Connexion à l'API d'administration…";
                    }
                    break;
            }
        }
        else
        {
            string where = _srvConn?.Description ?? SelectedRemote!.Endpoint;
            canStart = _srvConn?.CanStartServer == true && _srvError?.Status == 0;
            if (_srvConn == null)
            {
                (title, dot) = ("Clé d'administration manquante", Res("Bad"));
                detail = _srvKeyProblem == "illisible"
                    ? "La clé enregistrée ne se lit pas avec ce compte Windows (elle a été chiffrée ailleurs) : donnez-la de nouveau (bouton Modifier)."
                    : "Donnez la clé d'administration de ce serveur (bouton Modifier) : /vc admin cle, dans sa console, l'affiche.";
            }
            else if (st != null)
            {
                canStop = _srvStopping.Length == 0;
                (title, dot) = _srvStopping switch
                {
                    "arret" => ("Arrêt en cours…", Res("Gold")),
                    "redemarrage" => ("Redémarrage en cours…", Res("Gold")),
                    _ => ("En ligne", Res("Good")),
                };
                detail = Describe(st) + " · par " + where;
            }
            else if (_srvError == null)
            {
                (title, dot, detail) = ("Connexion…", Res("Gold"), "Connexion à " + where + "…");
            }
            else
            {
                retry = true;
                detail = _srvError.Message;
                (title, dot) = _srvError.Status switch
                {
                    0 when _srvStopping == "arret" => ("Serveur arrêté", Grey),
                    0 or 503 when _srvStopping == "redemarrage" => ("Redémarrage en cours…", Res("Gold")),
                    0 => ("Injoignable", Res("Bad")),
                    401 => ("Clé refusée", Res("Bad")),
                    403 => ("Accès refusé par le serveur", Res("Bad")),
                    404 => ("API d'administration introuvable", Res("Bad")),
                    429 => ("Accès bloqué un moment", Res("Gold")),
                    503 => ("Démarrage ou arrêt en cours…", Res("Gold")),
                    _ => ("Erreur du serveur", Res("Bad")),
                };
                if (_srvError.Status == 0 && _srvStopping == "arret")
                {
                    detail = "Il ne se relance pas depuis le launcher : depuis son hébergeur, ou par SSH (bientôt dans le launcher).";
                }
                else if (_srvError.Status == 0 && !_srvHalted)
                {
                    detail += " Nouvel essai dans quelques secondes.";
                }
            }
        }
        SrvTitle.Text = title;
        SrvDot.Fill = dot;
        SrvDetail.Text = detail;
        SrvStartButton.Visibility = canStart || starting ? Visibility.Visible : Visibility.Collapsed;
        SrvStartButton.IsEnabled = canStart;
        SrvStartText.Text = startText;
        SrvStopButton.Visibility = canStop ? Visibility.Visible : Visibility.Collapsed;
        SrvRestartButton.Visibility = canStop ? Visibility.Visible : Visibility.Collapsed;
        SrvRetryButton.Visibility = retry ? Visibility.Visible : Visibility.Collapsed;
        SrvBackupFolderButton.Visibility = IsLocalTarget ? Visibility.Visible : Visibility.Collapsed;
        SrvRenderTiles(st);
        SrvRenderPlayers(st);
        SrvRenderWorlds(st);
        SrvRenderSettingsAvailability();
        SrvConsoleInfo.Text = _srvClient != null
            ? (_srvError == null ? $"Console en direct, par l'API d'administration ({_srvClient.Connection.Description})." : "Console : " + _srvError.Message)
            : IsLocalTarget ? "Journal du serveur de ce PC, tel que le launcher le reçoit."
            : "La console s'affiche une fois le serveur joint.";
    }

    private static string Describe(AdminState st)
    {
        string text = $"Valcombe {st.Version} · Minecraft {st.Minecraft}";
        if (st.EnVeille)
        {
            text += " · en veille (personne depuis une minute)";
        }
        if (st.RedemarrageNecessaire)
        {
            text += " · un réglage attend le prochain redémarrage";
        }
        if (st.Api > AdminClient.ContractVersion)
        {
            text += $" · API plus récente que ce launcher (version {st.Api}) : mettez-le à jour";
        }
        return text;
    }

    private static string FormatDuration(long s) => s switch
    {
        < 60 => $"{s} s",
        < 3600 => $"{s / 60} min",
        < 86400 => $"{s / 3600} h {s % 3600 / 60:00}",
        _ => $"{s / 86400} j {s % 86400 / 3600} h",
    };

    private static string FormatMo(long mo) => mo >= 1024 ? (mo / 1024.0).ToString("0.0", Fr) + " Go" : mo + " Mo";

    private void SrvRenderTiles(AdminState? st)
    {
        string[] values = { Unknown, Unknown, Unknown, Unknown, Unknown };
        string[] subs = { "", "", "", "", "" };
        Brush tps = Res("Ink");
        double memory = 0;
        if (st != null)
        {
            values[0] = st.Version;
            subs[0] = "Minecraft " + st.Minecraft;
            values[1] = FormatDuration(st.DureeSecondes);
            subs[1] = DateTimeOffset.TryParse(st.DemarreLe, Fr, DateTimeStyles.AssumeUniversal, out var since)
                ? "démarré " + (since.ToLocalTime().Date == DateTime.Today ? "à " + since.ToLocalTime().ToString("HH:mm", Fr)
                    : "le " + since.ToLocalTime().ToString("d MMM 'à' HH:mm", Fr))
                : "";
            values[2] = st.Tps.ToString("0.0", Fr);
            subs[2] = st.EnVeille ? "en veille" : st.MsParTick.ToString("0.0", Fr) + " ms par tick";
            tps = st.Tps >= st.TpsCible * 0.95 ? Res("Good") : st.Tps >= st.TpsCible * 0.75 ? Res("Gold") : Res("Bad");
            values[3] = FormatMo(st.Memoire.UtiliseeMo);
            subs[3] = "sur " + FormatMo(st.Memoire.MaxMo);
            memory = st.Memoire.MaxMo > 0 ? (double)st.Memoire.UtiliseeMo / st.Memoire.MaxMo : 0;
            values[4] = $"{st.Joueurs.Connectes} / {st.Joueurs.Max}";
            subs[4] = st.Joueurs.Connectes == 0 ? "personne" : "connectés";
        }
        else if (IsLocalTarget && _srvClient == null && LocalState == LocalServer.State.Running)
        {
            values[4] = _server.Players.Count.ToString(Fr);
            subs[4] = "connectés";
        }
        for (int i = 0; i < 5; i++)
        {
            _srvTileValue[i].Text = values[i];
            _srvTileSub[i].Text = subs[i];
        }
        _srvTileValue[2].Foreground = tps;
        _srvMemoryBar.Value = memory;
        _srvMemoryBar.Foreground = memory > 0.9 ? Res("Bad") : Res("Gold");
        _srvMemoryBar.Visibility = st != null ? Visibility.Visible : Visibility.Hidden;
    }

    /// <summary>Le nom d'un monde Minecraft (« valcombe:partie-principale ») tel que le gestionnaire de mondes le nomme.</summary>
    private static string WorldName(string id, AdminState? st)
    {
        string shortId = id.Contains(':') ? id[(id.IndexOf(':') + 1)..] : id;
        var world = st?.Mondes.FirstOrDefault(w => w.Id == shortId || w.Id == id);
        return world?.Nom ?? id switch
        {
            "minecraft:overworld" => "Monde principal",
            "minecraft:the_nether" => "Nether",
            "minecraft:the_end" => "End",
            _ => shortId,
        };
    }

    private void SrvRenderPlayers(AdminState? st)
    {
        bool api = st != null && _srvPlayers != null;
        var players = api ? _srvPlayers!.Joueurs.Select(p => (p.Nom, p.Operateur, Where: $"{WorldName(p.Monde, st)} · {(p.Ping >= 0 ? p.Ping + " ms" : "ping inconnu")}")).ToList()
            : IsLocalTarget && _srvClient == null && LocalState == LocalServer.State.Running
                ? _server.Players.Select(n => (Nom: n, Operateur: false, Where: "")).ToList()
                : null;
        string signature = players == null ? "rien:" + _srvConnId + (_srvError != null) : string.Join("|", players.Select(p => $"{p.Nom}/{p.Operateur}/{p.Where}"));
        if (signature == _srvPlayersShown)
        {
            return;
        }
        _srvPlayersShown = signature;
        SrvPlayersTitle.Text = players is { Count: > 0 } ? $"Joueurs connectés ({players.Count})" : "Joueurs connectés";
        SrvPlayersList.Children.Clear();
        if (players == null || players.Count == 0)
        {
            SrvPlayersList.Children.Add(new TextBlock
            {
                Style = (Style)FindResource("Hint"),
                Text = players != null ? "Personne n'est connecté."
                    : IsLocalTarget && LocalState == LocalServer.State.Stopped ? "Le serveur est arrêté."
                    : "Les joueurs s'affichent une fois le serveur joint.",
            });
            return;
        }
        foreach (var p in players)
        {
            string name = p.Nom;
            var row = new Grid { Margin = new Thickness(0, 0, 0, 8) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var who = new StackPanel();
            var line = new StackPanel { Orientation = Orientation.Horizontal };
            line.Children.Add(new TextBlock { Text = name, FontSize = 14, FontWeight = FontWeights.SemiBold, Foreground = Res("Ink"), VerticalAlignment = VerticalAlignment.Center });
            if (p.Operateur)
            {
                line.Children.Add(new Border
                {
                    Style = (Style)FindResource("Badge"), Margin = new Thickness(8, 0, 0, 0),
                    Child = new TextBlock { Text = "opérateur", FontSize = 11, Foreground = Res("Gold") },
                });
            }
            who.Children.Add(line);
            if (p.Where.Length > 0)
            {
                who.Children.Add(new TextBlock { Text = p.Where, Style = (Style)FindResource("Hint"), Margin = new Thickness(0, 1, 0, 0) });
            }
            row.Children.Add(who);
            var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            var op = SmallButton(p.Operateur ? "Retirer les droits" : "Opérateur",
                p.Operateur ? $"Retirer à {name} ses droits d'opérateur" : $"Nommer {name} opérateur");
            bool isOp = p.Operateur;
            op.Click += async (_, _) => await SrvSetOperatorAsync(name, !isOp);
            var kick = SmallButton("Expulser", $"Renvoyer {name} du serveur (il peut revenir s'il est sur la liste blanche)");
            kick.Click += async (_, _) => await SrvKickAsync(name);
            actions.Children.Add(op);
            actions.Children.Add(kick);
            Grid.SetColumn(actions, 1);
            row.Children.Add(actions);
            SrvPlayersList.Children.Add(row);
        }
    }

    private Button SmallButton(string text, string tip) => new()
    {
        Content = text, Style = (Style)FindResource("Ghost"), Padding = new Thickness(10, 4, 10, 4), FontSize = 12,
        Margin = new Thickness(6, 0, 0, 0), ToolTip = tip,
    };

    private void SrvRenderWorlds(AdminState? st)
    {
        string signature = st == null ? "rien:" + _srvConnId + (_srvClient == null)
            : string.Join("|", st.Mondes.Select(w => $"{w.Id}/{w.Nom}/{w.Etat}/{w.Joueurs}"));
        if (signature == _srvWorldsShown)
        {
            return;
        }
        _srvWorldsShown = signature;
        SrvWorldsList.Children.Clear();
        if (st == null || st.Mondes.Count == 0)
        {
            SrvWorldsList.Children.Add(new TextBlock
            {
                Style = (Style)FindResource("Hint"),
                Text = st != null ? "Aucun monde de Valcombe n'est encore inscrit sur ce serveur."
                    : IsLocalTarget && _srvClient == null && LocalState == LocalServer.State.Running
                        ? "Les mondes s'affichent avec l'API d'administration (Valcombe 0.14 ou plus récent)."
                        : "Les mondes s'affichent une fois le serveur joint.",
            });
            return;
        }
        foreach (var w in st.Mondes)
        {
            var row = new Grid { Margin = new Thickness(0, 0, 0, 8) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var name = new StackPanel();
            name.Children.Add(new TextBlock { Text = w.Nom.Length > 0 ? w.Nom : w.Id, FontSize = 14, Foreground = Res("Ink"), TextTrimming = TextTrimming.CharacterEllipsis });
            name.Children.Add(new TextBlock { Text = w.SorteTexte, Style = (Style)FindResource("Hint"), Margin = new Thickness(0, 1, 0, 0) });
            row.Children.Add(name);
            var state = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            Brush color = w.Etat switch { "charge" => Res("Good"), "remise-a-neuf" => Res("Gold"), _ => Grey };
            state.Children.Add(new System.Windows.Shapes.Ellipse { Width = 8, Height = 8, Fill = color, Margin = new Thickness(0, 1, 7, 0), VerticalAlignment = VerticalAlignment.Center });
            state.Children.Add(new TextBlock
            {
                Text = w.EtatTexte + (w.Joueurs > 0 ? $" · {w.Joueurs} joueur{(w.Joueurs > 1 ? "s" : "")}" : ""),
                FontSize = 12.5, Foreground = Res("Parch"), VerticalAlignment = VerticalAlignment.Center,
            });
            Grid.SetColumn(state, 1);
            row.Children.Add(state);
            SrvWorldsList.Children.Add(row);
        }
    }

    // ------------------------------------------------------------------
    // Onglets
    // ------------------------------------------------------------------
    private void SrvTabChanged(object sender, RoutedEventArgs e)
    {
        if (sender is RadioButton { Tag: string tab })
        {
            _srvTab = tab;
        }
        if (SrvFormPanel == null)
        {
            return; // pendant le chargement de la fenêtre
        }
        SrvShowTab();
        if (_srvTab == "console")
        {
            SrvConsoleScroll()?.ScrollToEnd();
        }
        if (_srvTab == "reglages")
        {
            _srvSettingsStale = true;
        }
        if (PageServer.Visibility == Visibility.Visible)
        {
            _ = SrvPollAsync(force: _srvTab != "sauvegardes");
        }
    }

    private void SrvShowTab()
    {
        bool form = _srvFormOpen;
        SrvStateCard.Visibility = form ? Visibility.Collapsed : Visibility.Visible;
        SrvTabs.Visibility = form ? Visibility.Collapsed : Visibility.Visible;
        SrvFormPanel.Visibility = form ? Visibility.Visible : Visibility.Collapsed;
        SrvOverview.Visibility = !form && _srvTab == "apercu" ? Visibility.Visible : Visibility.Collapsed;
        SrvConsolePanel.Visibility = !form && _srvTab == "console" ? Visibility.Visible : Visibility.Collapsed;
        SrvSettingsPanel.Visibility = !form && _srvTab == "reglages" ? Visibility.Visible : Visibility.Collapsed;
        SrvBackupsPanel.Visibility = !form && _srvTab == "sauvegardes" ? Visibility.Visible : Visibility.Collapsed;
    }

    // ------------------------------------------------------------------
    // Démarrer, arrêter, redémarrer
    // ------------------------------------------------------------------
    private int ConnectedPlayers => _srvState?.Joueurs.Connectes ?? (IsLocalTarget ? _server.Players.Count : 0);

    private async void SrvStart(object sender, RoutedEventArgs e)
    {
        if (IsLocalTarget)
        {
            // Exactement comme la page Héberger : contrat accepté, pseudo de l'hôte, installation au besoin.
            await StartServerAsync();
            return;
        }
        if (_srvConn is { CanStartServer: true } conn)
        {
            try
            {
                await conn.StartServerAsync(new Progress<(string, double)>(p => SetStatus(p.Item1, p.Item2)), CancellationToken.None);
                _srvHalted = false;
                _srvQuietUntil = DateTime.MinValue;
            }
            catch (Exception ex)
            {
                SetStatus("Démarrage impossible : " + ex.Message, 0);
            }
        }
    }

    private async void SrvStop(object sender, RoutedEventArgs e)
    {
        if (IsLocalTarget)
        {
            if (_server.Current == LocalServer.State.Running)
            {
                HostToggle(sender, e); // même arrêt que la page Héberger (avec la même mise en garde)
            }
            return;
        }
        int players = ConnectedPlayers;
        if (MessageBox.Show(this, $"Arrêter {TargetName} ?"
                + (players > 0 ? $" {players} joueur(s) sont connectés : la soirée s'interrompt pour eux." : "")
                + "\n\nLe monde et la partie en cours sont enregistrés. Un serveur distant arrêté ne se relance pas depuis le launcher : "
                + "relancez-le depuis son hébergeur (ou par SSH, bientôt dans le launcher).",
                "Les Cendres de Valcombe", MessageBoxButton.OKCancel, MessageBoxImage.Warning) != MessageBoxResult.OK)
        {
            return;
        }
        await SrvStopOrRestartAsync(restart: false);
    }

    private async void SrvRestart(object sender, RoutedEventArgs e)
    {
        int players = ConnectedPlayers;
        string warning = players > 0 ? $"{players} joueur(s) sont connectés : ils seront déconnectés le temps de la relance (une à deux minutes). " : "";
        if (!IsLocalTarget)
        {
            warning += "\n\nLe serveur revient tout seul s'il a été lancé par demarrer.sh, demarrer.bat ou le service valcombe ; "
                + "sinon, il faudra le relancer vous-même.";
        }
        if ((players > 0 || !IsLocalTarget) && MessageBox.Show(this, $"Redémarrer {TargetName} ? {warning}\n\nLe monde et la partie en cours sont enregistrés.",
                "Les Cendres de Valcombe", MessageBoxButton.OKCancel, MessageBoxImage.Warning) != MessageBoxResult.OK)
        {
            return;
        }
        if (IsLocalTarget && (_srvClient == null || _srvState == null))
        {
            // Sans API : le launcher arrête son serveur et le relance lui-même.
            SetStatus("Redémarrage du serveur…", 0);
            await _server.StopAsync();
            await StartServerAsync();
            return;
        }
        // Avec l'API : arrêt propre et drapeau de redémarrage ; pour ce PC, le launcher voit le drapeau et relance.
        await SrvStopOrRestartAsync(restart: true);
    }

    private async Task SrvStopOrRestartAsync(bool restart)
    {
        var client = _srvClient;
        if (client == null)
        {
            return;
        }
        try
        {
            var r = restart ? await client.RestartAsync(CancellationToken.None) : await client.StopAsync(CancellationToken.None);
            SetStatus(r.Message.Length > 0 ? r.Message : restart ? "Redémarrage demandé." : "Arrêt demandé.", 0);
            if (!IsLocalTarget)
            {
                _srvStopping = restart ? "redemarrage" : "arret";
                _srvStoppingSawDown = false;
                _srvStoppingSince = DateTime.UtcNow;
                _srvQuietUntil = DateTime.UtcNow.AddSeconds(2);
            }
        }
        catch (AdminException ex)
        {
            SetStatus((restart ? "Redémarrage" : "Arrêt") + " refusé : " + ex.Message, 0);
        }
        SrvRender();
    }

    private void SrvRetry(object sender, RoutedEventArgs e)
    {
        _srvHalted = false;
        _srvQuietUntil = DateTime.MinValue;
        _ = SrvPollAsync(force: true);
    }

    // ------------------------------------------------------------------
    // Joueurs
    // ------------------------------------------------------------------
    private async Task SrvKickAsync(string name)
    {
        if (MessageBox.Show(this, $"Expulser {name} du serveur ?", "Les Cendres de Valcombe", MessageBoxButton.OKCancel,
                MessageBoxImage.Question) != MessageBoxResult.OK)
        {
            return;
        }
        await SrvRunCommandAsync($"kick {name} Expulsé par l'hôte", quiet: true);
        _srvTick = 0; // état et joueurs relus à la prochaine seconde
    }

    private async Task SrvSetOperatorAsync(string name, bool op)
    {
        if (_srvClient == null)
        {
            await SrvRunCommandAsync((op ? "op " : "deop ") + name, quiet: true);
            return;
        }
        var change = new AdminSettingsChange();
        (op ? change.OperatorsAdd : change.OperatorsRemove).Add(name);
        await SrvApplyAsync(change);
        _srvTick = 0;
    }

    // ------------------------------------------------------------------
    // Console et commandes
    // ------------------------------------------------------------------
    private void SrvCommandKey(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            SrvSendCommand(sender, e);
            e.Handled = true;
        }
        else if (e.Key is Key.Up or Key.Down && _srvHistory.Count > 0)
        {
            _srvHistoryPos = Math.Clamp(_srvHistoryPos + (e.Key == Key.Up ? -1 : 1), 0, _srvHistory.Count);
            SrvCommandBox.Text = _srvHistoryPos < _srvHistory.Count ? _srvHistory[_srvHistoryPos] : "";
            SrvCommandBox.CaretIndex = SrvCommandBox.Text.Length;
            e.Handled = true;
        }
    }

    private async void SrvSendCommand(object sender, RoutedEventArgs e)
    {
        string command = SrvCommandBox.Text.Trim().TrimStart('/');
        if (command.Length == 0)
        {
            return;
        }
        if (_srvHistory.Count == 0 || _srvHistory[^1] != command)
        {
            _srvHistory.Add(command);
        }
        _srvHistoryPos = _srvHistory.Count;
        SrvCommandBox.Text = "";
        await SrvRunCommandAsync(command, quiet: false);
    }

    /// <summary>Envoie une commande : par l'API (avec sa réponse), ou sur l'entrée du serveur de ce PC s'il n'a pas d'API.</summary>
    private async Task SrvRunCommandAsync(string command, bool quiet)
    {
        void Show(string text, bool ok)
        {
            if (quiet)
            {
                SetStatus(text.Replace('\n', ' '), 0);
                return;
            }
            SrvCommandResult.Text = text;
            SrvCommandResult.Foreground = ok ? Res("Ash") : Res("Bad");
            SrvCommandResult.Visibility = Visibility.Visible;
        }
        if (_srvClient != null && _srvError?.StopsPolling != true)
        {
            try
            {
                var r = await _srvClient.RunCommandAsync(command, CancellationToken.None);
                Show(r.Sortie.Count > 0 ? string.Join("\n", r.Sortie.Take(12)) + (r.Sortie.Count > 12 ? $"\n… et {r.Sortie.Count - 12} ligne(s) de plus dans la console." : "")
                    : r.Ok ? $"/{command} : fait." : $"/{command} : échec.", r.Ok);
            }
            catch (AdminException ex)
            {
                Show(ex.Message, false);
            }
            _ = SrvPollAsync();
            return;
        }
        if (IsLocalTarget && _server.Current == LocalServer.State.Running)
        {
            _server.Send(command);
            Show($"/{command} : envoyée au serveur ; sa réponse s'affiche dans la console.", true);
            return;
        }
        Show("Le serveur ne répond pas : la commande n'est pas partie.", false);
    }

    private void SrvConsoleKey(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.C && Keyboard.Modifiers.HasFlag(ModifierKeys.Control))
        {
            SrvCopyConsole(sender, e);
            e.Handled = true;
        }
    }

    private void SrvCopyConsole(object sender, RoutedEventArgs e)
    {
        var lines = (SrvConsoleList.SelectedItems.Count > 0 ? SrvConsoleList.SelectedItems.Cast<ConsoleEntry>().OrderBy(_srvConsole.IndexOf)
            : _srvConsole.AsEnumerable()).Select(l => (l.Time.Length > 0 ? l.Time + " " : "") + l.Text).ToList();
        if (lines.Count == 0)
        {
            return;
        }
        Clipboard.SetText(string.Join(Environment.NewLine, lines));
        SetStatus($"{lines.Count} ligne(s) de la console copiée(s).", 0);
    }

    private void SrvClearConsole(object sender, RoutedEventArgs e) => _srvConsole.Clear();

    // ------------------------------------------------------------------
    // Réglages
    // ------------------------------------------------------------------
    /// <summary>Ce qui se règle selon le serveur et son état : par l'API, et (ce PC seulement) le port et la mémoire.</summary>
    private void SrvRenderSettingsAvailability()
    {
        var s = _srvError == null ? _srvSettings : null;
        bool api = _srvClient != null && s != null;
        bool local = IsLocalTarget;
        SrvAccessBox.IsEnabled = api;
        SrvAccessHint.Visibility = api ? Visibility.Collapsed : Visibility.Visible;
        SrvAccessHint.Text = local && _srvClient == null && LocalState == LocalServer.State.Running
            ? "Cette version du mod n'a pas encore d'API d'administration : les invités se règlent sur la page Héberger."
            : local ? "Démarrez le serveur pour changer la liste blanche et les opérateurs. Les invités de la page Héberger y sont inscrits à chaque démarrage."
            : "Le serveur doit être joint pour changer ces réglages.";
        SrvWelcomeBox.IsEnabled = api && s!.CanChange("messageAccueil");
        SrvEveningBox.IsEnabled = api && s!.CanChange("prochaineSoiree");
        SrvMaxPlayersBox.IsEnabled = api && s!.CanChange("joueursMax");
        SrvPortBox.IsEnabled = local || (api && s!.CanChange("port"));
        SrvMemoryBox.Visibility = local ? Visibility.Visible : Visibility.Collapsed;
        SrvSaveButton.IsEnabled = local || api;
    }

    /// <summary>Après une lecture des réglages : listes et interrupteur à jour, champs remplis une première fois.</summary>
    private void SrvShowSettings()
    {
        var s = _srvSettings;
        _srvFilling = true;
        SrvWhitelistBox.IsChecked = s?.ListeBlanche.Active == true;
        _srvFilling = false;
        SrvChips(SrvWhitelistPanel, s?.ListeBlanche.Joueurs, "Personne sur la liste blanche.", name =>
        {
            var change = new AdminSettingsChange();
            change.WhitelistRemove.Add(name);
            return change;
        }, "Retirer {0} de la liste blanche");
        SrvChips(SrvOpsPanel, s?.Operateurs.Select(o => o.Nom).ToList(), "Aucun opérateur.", name =>
        {
            var change = new AdminSettingsChange();
            change.OperatorsRemove.Add(name);
            return change;
        }, "Retirer à {0} ses droits d'opérateur");
        if (!_srvFieldsFilled)
        {
            SrvFillFields();
        }
        SrvRenderSettingsAvailability();
    }

    private void SrvChips(WrapPanel panel, List<string>? names, string empty, Func<string, AdminSettingsChange> remove, string tip)
    {
        panel.Children.Clear();
        if (names == null)
        {
            return;
        }
        foreach (string n in names.Order(StringComparer.OrdinalIgnoreCase))
        {
            string name = n;
            var chip = new Button
            {
                Content = name + "   ✕", Style = (Style)FindResource("Ghost"), Margin = new Thickness(0, 0, 8, 8),
                Padding = new Thickness(12, 5, 12, 5), ToolTip = string.Format(Fr, tip, name),
            };
            chip.Click += async (_, _) => await SrvApplyAsync(remove(name));
            panel.Children.Add(chip);
        }
        if (names.Count == 0)
        {
            panel.Children.Add(new TextBlock { Text = empty, Style = (Style)FindResource("Hint") });
        }
    }

    /// <summary>Remplit les champs avec les valeurs du serveur (et, pour ce PC, celles du launcher).</summary>
    private void SrvFillFields()
    {
        var s = _srvSettings;
        bool local = IsLocalTarget;
        _srvFilling = true;
        SrvWelcomeBox.Text = s?.MessageAccueil ?? "";
        SrvWelcomeHint.Text = "La première ligne dans la liste des serveurs de Minecraft, quand aucune soirée n'est annoncée. Vide : « "
            + (s?.MessageAccueilParDefaut is { Length: > 0 } d ? d : "Les Cendres de Valcombe") + " ».";
        SrvEveningBox.Text = s?.ProchaineSoiree ?? "";
        SrvMaxPlayersBox.Text = s != null ? s.JoueursMax.ToString(Fr) : "";
        SrvPortBox.Text = local ? _config.HostPort.ToString(Fr) : s != null ? s.PortEnregistre.ToString(Fr) : "";
        SrvMemorySlider.Value = Math.Clamp(_config.HostMemoryMb / 1024, 2, 8);
        SrvMemoryLabel.Text = $"Mémoire du serveur : {(int)SrvMemorySlider.Value} Go";
        SrvSettingsNote.Text = local
            ? "Le port du jeu et la mémoire servent au prochain démarrage du serveur de ce PC (les mêmes que sur la page Héberger)."
            : s is { RedemarrageNecessaire: true } ? $"Port en service : {s.Port}. Le port enregistré ({s.PortEnregistre}) servira au prochain redémarrage."
            : "Un nouveau port ne sert qu'au prochain redémarrage du serveur.";
        _srvFilling = false;
        _srvFieldsFilled = s != null;
    }

    private void SrvMemoryChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (SrvMemoryLabel != null)
        {
            SrvMemoryLabel.Text = $"Mémoire du serveur : {(int)SrvMemorySlider.Value} Go";
        }
    }

    private void SrvHideResults(object sender, RoutedEventArgs e) => SrvResultsBox.Visibility = Visibility.Collapsed;

    private void SrvResetSettings(object sender, RoutedEventArgs e)
    {
        _srvFieldsFilled = false;
        SrvFillFields();
        SrvResultsBox.Visibility = Visibility.Collapsed;
    }

    private async void SrvWhitelistToggled(object sender, RoutedEventArgs e)
    {
        if (_srvFilling)
        {
            return;
        }
        var change = new AdminSettingsChange { WhitelistActive = SrvWhitelistBox.IsChecked == true };
        await SrvApplyAsync(change);
    }

    private void SrvWhitelistKey(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            SrvWhitelistAdd(sender, e);
        }
    }

    private void SrvOpKey(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            SrvOpAdd(sender, e);
        }
    }

    private async void SrvWhitelistAdd(object sender, RoutedEventArgs e)
    {
        string name = SrvWhitelistName.Text.Trim();
        if (!SrvCheckName(name))
        {
            return;
        }
        var change = new AdminSettingsChange();
        change.WhitelistAdd.Add(name);
        if (await SrvApplyAsync(change))
        {
            SrvWhitelistName.Text = "";
        }
    }

    private async void SrvOpAdd(object sender, RoutedEventArgs e)
    {
        string name = SrvOpName.Text.Trim();
        if (!SrvCheckName(name))
        {
            return;
        }
        var change = new AdminSettingsChange();
        change.OperatorsAdd.Add(name);
        if (await SrvApplyAsync(change))
        {
            SrvOpName.Text = "";
        }
    }

    private bool SrvCheckName(string name)
    {
        if (AdminClient.IsPlayerName(name))
        {
            return true;
        }
        SrvShowResults(new[] { (false, "Un pseudo Minecraft : lettres sans accent, chiffres et « _ », 16 caractères au plus.") });
        return false;
    }

    private async void SrvSaveSettings(object sender, RoutedEventArgs e)
    {
        var messages = new List<(bool Ok, string Text)>();
        var s = _srvError == null ? _srvSettings : null;
        bool local = IsLocalTarget;
        var change = new AdminSettingsChange();
        int? port = null;
        if (SrvPortBox.IsEnabled)
        {
            if (!int.TryParse(SrvPortBox.Text.Trim(), out int p) || p is < 1024 or > 65535)
            {
                messages.Add((false, "Port du jeu : un nombre de 1024 à 65535."));
            }
            else
            {
                port = p;
            }
        }
        if (s != null && _srvClient != null)
        {
            string welcome = SrvWelcomeBox.Text.Trim();
            string evening = SrvEveningBox.Text.Trim();
            foreach (var (label, text) in new[] { ("Message d'accueil", welcome), ("Prochaine soirée", evening) })
            {
                if (text.Contains('§'))
                {
                    messages.Add((false, $"{label} : le caractère « § » n'est pas permis."));
                }
            }
            if (SrvWelcomeBox.IsEnabled && welcome != s.MessageAccueil)
            {
                change.Welcome = welcome;
            }
            if (SrvEveningBox.IsEnabled && evening != (s.ProchaineSoiree ?? ""))
            {
                change.NextEvening = evening;
            }
            if (SrvMaxPlayersBox.IsEnabled)
            {
                if (!int.TryParse(SrvMaxPlayersBox.Text.Trim(), out int max) || max is < 1 or > 1000)
                {
                    messages.Add((false, "Joueurs au plus : un nombre de 1 à 1000."));
                }
                else if (max != s.JoueursMax)
                {
                    change.MaxPlayers = max;
                }
            }
            // Le port du serveur de ce PC appartient au launcher, qui l'écrit à chaque démarrage.
            if (!local && port != null && port != s.PortEnregistre)
            {
                change.Port = port;
            }
        }
        if (messages.Any(m => !m.Ok))
        {
            SrvShowResults(messages);
            return;
        }
        if (local)
        {
            int memory = (int)SrvMemorySlider.Value * 1024;
            if ((port != null && port != _config.HostPort) || memory != _config.HostMemoryMb)
            {
                _config.HostPort = port ?? _config.HostPort;
                _config.HostMemoryMb = memory;
                SaveConfig();
                // La page Héberger montre les mêmes réglages.
                bool loading = _loading;
                _loading = true;
                HostPortBox.Text = _config.HostPort.ToString(Fr);
                HostMemorySlider.Value = memory / 1024;
                _loading = loading;
                UpdateLabels();
                messages.Add((true, _server.Current == LocalServer.State.Stopped
                    ? "Port du jeu et mémoire enregistrés : ils serviront au prochain démarrage."
                    : "Port du jeu et mémoire enregistrés : ils serviront au prochain démarrage (redémarrez le serveur pour les appliquer)."));
            }
        }
        if (!change.IsEmpty)
        {
            var result = await SrvApplyAsync(change, messages);
            if (result)
            {
                _srvFieldsFilled = false;
                SrvFillFields();
            }
            return;
        }
        if (messages.Count == 0)
        {
            messages.Add((true, "Rien à enregistrer : aucun réglage n'a changé."));
        }
        SrvShowResults(messages);
    }

    /// <summary>
    /// Envoie un changement de réglages et montre le résultat point par point. Pour ce PC, la liste blanche
    /// est aussi reportée dans les invités de la page Héberger, inscrits à chaque démarrage.
    /// Vrai si la demande a été acceptée (même si un point a échoué).
    /// </summary>
    private async Task<bool> SrvApplyAsync(AdminSettingsChange change, List<(bool Ok, string Text)>? before = null)
    {
        var messages = before ?? new List<(bool Ok, string Text)>();
        var client = _srvClient;
        if (client == null || _srvSettings == null)
        {
            messages.Add((false, "Le serveur doit être en ligne et joint pour changer ce réglage."));
            SrvShowResults(messages);
            return false;
        }
        int gen = _srvGen;
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(45));
            var r = await client.ChangeSettingsAsync(change, cts.Token);
            if (gen != _srvGen)
            {
                return false;
            }
            messages.AddRange(r.Resultats.Select(x => (x.Ok, x.Message)));
            if (r.Reglages != null)
            {
                _srvSettings = r.Reglages;
            }
            else
            {
                _srvSettingsStale = true;
            }
            SrvSyncLocalGuests(r);
            SrvShowResults(messages);
            SrvShowSettings();
            _srvTick = 0;
            return true;
        }
        catch (AdminException ex) when (gen == _srvGen)
        {
            if (ex.Status == 504)
            {
                // La demande reste prévue sur le serveur : relire avant de la renvoyer.
                _srvSettingsStale = true;
            }
            messages.Add((false, ex.Message));
            SrvShowResults(messages);
            SrvShowSettings(); // l'interrupteur et les listes reviennent à ce que le serveur a vraiment
            return false;
        }
        catch (Exception) when (gen != _srvGen)
        {
            return false;
        }
    }

    /// <summary>Ce PC : la liste blanche changée ici est reportée dans les invités du launcher (page Héberger).</summary>
    private void SrvSyncLocalGuests(AdminSettingsResult r)
    {
        if (!IsLocalTarget || _captureDir != null)
        {
            return;
        }
        bool changed = false;
        foreach (var step in r.Resultats.Where(x => x.Ok))
        {
            string value = step.Valeur.ValueKind == System.Text.Json.JsonValueKind.String ? step.Valeur.GetString() ?? "" : "";
            switch (step.Reglage)
            {
                case "listeBlanche.ajouter" when value.Length > 0 && !value.Equals(_config.HostName, StringComparison.OrdinalIgnoreCase)
                                                && !_config.HostGuests.Contains(value, StringComparer.OrdinalIgnoreCase):
                    _config.HostGuests.Add(value);
                    changed = true;
                    break;
                case "listeBlanche.retirer" when value.Length > 0:
                    changed |= _config.HostGuests.RemoveAll(g => g.Equals(value, StringComparison.OrdinalIgnoreCase)) > 0;
                    break;
                case "listeBlanche.active" when step.Valeur.ValueKind is System.Text.Json.JsonValueKind.True or System.Text.Json.JsonValueKind.False:
                    _config.HostGuestsOnly = step.Valeur.GetBoolean();
                    bool loading = _loading;
                    _loading = true;
                    HostGuestsOnlyBox.IsChecked = _config.HostGuestsOnly;
                    _loading = loading;
                    changed = true;
                    break;
            }
        }
        if (changed)
        {
            SaveConfig();
            BuildGuests();
        }
    }

    private void SrvShowResults(IEnumerable<(bool Ok, string Text)> results)
    {
        SrvResults.Children.Clear();
        foreach (var (ok, text) in results)
        {
            var row = new DockPanel { Margin = new Thickness(0, 2, 0, 2) };
            var mark = new TextBlock
            {
                Text = ok ? "\uE73E" : "\uE711", FontFamily = (FontFamily)FindResource("Icons"), FontSize = 12,
                Foreground = ok ? Res("Good") : Res("Bad"), Margin = new Thickness(0, 3, 10, 0), VerticalAlignment = VerticalAlignment.Top,
            };
            DockPanel.SetDock(mark, Dock.Left);
            row.Children.Add(mark);
            row.Children.Add(new TextBlock { Text = text, Style = (Style)FindResource("Hint"), Margin = new Thickness(0), Foreground = Res("Parch") });
            SrvResults.Children.Add(row);
        }
        SrvResultsBox.Visibility = SrvResults.Children.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        var first = results.FirstOrDefault();
        if (first.Text != null && _srvTab != "reglages")
        {
            SetStatus(first.Text, 0);
        }
    }

    // ------------------------------------------------------------------
    // Serveurs distants : ajouter, modifier, tester, retirer
    // ------------------------------------------------------------------
    private void SrvAdd(object sender, RoutedEventArgs e)
    {
        _srvEditing = null;
        SrvFormTitle.Text = "Ajouter un serveur distant";
        SrvFormName.Text = "";
        SrvFormHost.Text = "";
        SrvFormPort.Text = AdminClient.DefaultPort.ToString(Fr);
        SrvFormKey.Password = "";
        SrvFormKeyHint.Text = "Elle s'affiche dans la console du serveur à son premier démarrage, puis avec /vc admin cle. "
            + "Elle est gardée chiffrée sur ce PC (DPAPI de Windows) : seul votre compte Windows peut la relire.";
        SrvFormRemoveButton.Visibility = Visibility.Collapsed;
        SrvOpenForm();
    }

    private void SrvEdit(object sender, RoutedEventArgs e)
    {
        var r = SelectedRemote;
        if (r == null)
        {
            return;
        }
        _srvEditing = r;
        SrvFormTitle.Text = "Modifier « " + r.Name + " »";
        SrvFormName.Text = r.Name;
        SrvFormHost.Text = r.Host;
        SrvFormPort.Text = r.ApiPort.ToString(Fr);
        SrvFormKey.Password = "";
        SrvFormKeyHint.Text = (r.ProtectedKey.Length > 0 ? "Laissez vide pour garder la clé enregistrée. " : "Aucune clé enregistrée pour l'instant. ")
            + "Elle est gardée chiffrée sur ce PC (DPAPI de Windows) : seul votre compte Windows peut la relire.";
        SrvFormRemoveButton.Visibility = Visibility.Visible;
        SrvOpenForm();
    }

    private void SrvOpenForm()
    {
        _srvFormOpen = true;
        SrvFormResult.Text = "";
        SrvShowTab();
        SrvFormName.Focus();
    }

    private void SrvCloseForm()
    {
        _srvFormOpen = false;
        SrvShowTab();
    }

    private void SrvFormCancel(object sender, RoutedEventArgs e) => SrvCloseForm();

    /// <summary>Lit le formulaire : un serveur prêt à enregistrer (sans toucher à la liste), ou une erreur à montrer.</summary>
    private (RemoteServer? Server, string? Key, string? Error) SrvReadForm()
    {
        string host = SrvFormHost.Text.Trim();
        foreach (string prefix in new[] { "http://", "https://" })
        {
            if (host.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            {
                host = host[prefix.Length..];
            }
        }
        host = host.TrimEnd('/');
        if (host.StartsWith('[') && host.EndsWith(']'))
        {
            host = host[1..^1]; // adresse IPv6 entre crochets
        }
        if (host.Length == 0 || host.Any(c => char.IsWhiteSpace(c) || c is '/' or '@' or '?' or '#'))
        {
            return (null, null, "Indiquez l'adresse du serveur : un nom (vps.exemple.fr) ou une adresse IP.");
        }
        if (host.Count(c => c == ':') == 1)
        {
            return (null, null, "Indiquez l'adresse sans le port (« vps.exemple.fr ») : le port de l'API va dans la case de droite.");
        }
        if (!int.TryParse(SrvFormPort.Text.Trim(), out int port) || port is < 1 or > 65535)
        {
            return (null, null, $"Port de l'API : un nombre de 1 à 65535 ({AdminClient.DefaultPort} si le serveur ne l'a pas changé).");
        }
        string key = SrvFormKey.Password.Trim();
        if (key.Length > 0 && !AdminClient.IsKey(key))
        {
            return (null, null, "Cette clé n'a pas la bonne forme : 32 à 128 caractères, lettres sans accent, chiffres, « _ » et « - ». "
                + "Copiez-la telle que /vc admin cle l'affiche.");
        }
        if (key.Length == 0 && _srvEditing?.ReadKey() == null)
        {
            return (null, null, "Donnez la clé d'administration du serveur.");
        }
        string name = SrvFormName.Text.Trim();
        var server = new RemoteServer
        {
            Id = _srvEditing?.Id ?? Guid.NewGuid().ToString("N")[..12],
            Name = name.Length > 0 ? name : host,
            Host = host,
            ApiPort = port,
            ProtectedKey = _srvEditing?.ProtectedKey ?? "",
            Access = _srvEditing?.Access ?? RemoteServer.AccessDirect,
        };
        if (key.Length > 0)
        {
            server.SetKey(key);
        }
        return (server, key, null);
    }

    private void SrvFormSave(object sender, RoutedEventArgs e)
    {
        var (server, _, error) = SrvReadForm();
        if (server == null)
        {
            SrvFormResult.Text = error;
            SrvFormResult.Foreground = Res("Bad");
            return;
        }
        int index = _config.RemoteServers.FindIndex(r => r.Id == server.Id);
        if (index >= 0)
        {
            _config.RemoteServers[index] = server;
        }
        else
        {
            _config.RemoteServers.Add(server);
        }
        _config.AdminTarget = server.Id;
        SaveConfig();
        RefreshSrvPicker();
        SrvCloseForm();
        SetStatus($"Serveur « {server.Name} » enregistré.", 0);
        _srvFieldsFilled = false;
        _ = SrvPollAsync(force: true);
    }

    private async void SrvFormTest(object sender, RoutedEventArgs e)
    {
        var (server, _, error) = SrvReadForm();
        if (server == null)
        {
            SrvFormResult.Text = error;
            SrvFormResult.Foreground = Res("Bad");
            return;
        }
        SrvFormTestButton.IsEnabled = false;
        SrvFormResult.Text = $"Connexion à {server.Endpoint}…";
        SrvFormResult.Foreground = Res("Ash");
        try
        {
            await using var conn = ServerConnections.Open(server);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(12));
            var st = await new AdminClient(conn).GetStateAsync(cts.Token);
            SrvFormResult.Text = $"Connexion réussie : Valcombe {st.Version}, Minecraft {st.Minecraft}, "
                + $"{st.Joueurs.Connectes} joueur(s) connecté(s) sur {st.Joueurs.Max}.";
            SrvFormResult.Foreground = Res("Good");
        }
        catch (AdminException ex)
        {
            SrvFormResult.Text = ex.Message;
            SrvFormResult.Foreground = Res("Bad");
        }
        catch (OperationCanceledException)
        {
            SrvFormResult.Text = $"{server.Endpoint} ne répond pas (délai dépassé).";
            SrvFormResult.Foreground = Res("Bad");
        }
        finally
        {
            SrvFormTestButton.IsEnabled = true;
        }
    }

    private void SrvFormRemove(object sender, RoutedEventArgs e)
    {
        var r = _srvEditing;
        if (r == null || MessageBox.Show(this, $"Retirer « {r.Name} » de la liste ?\n\nSa clé, gardée sur ce PC, est effacée. Le serveur lui-même n'est pas touché.",
                "Les Cendres de Valcombe", MessageBoxButton.OKCancel, MessageBoxImage.Question) != MessageBoxResult.OK)
        {
            return;
        }
        _config.RemoteServers.RemoveAll(x => x.Id == r.Id);
        _config.AdminTarget = LocalTarget;
        SaveConfig();
        RefreshSrvPicker();
        SrvCloseForm();
        SetStatus($"Serveur « {r.Name} » retiré.", 0);
        _srvFieldsFilled = false;
        _ = SrvPollAsync(force: true);
    }

    // ------------------------------------------------------------------
    // Captures (--capture) : la page avec un serveur fictif, onglet par onglet
    // ------------------------------------------------------------------
    private async Task CaptureServerPageAsync(string dir)
    {
        async Task Tab(string tab)
        {
            foreach (var rb in SrvTabs.Children.OfType<RadioButton>())
            {
                rb.IsChecked = (string)rb.Tag == tab;
            }
            await SrvPollAsync(force: true);
            await Task.Delay(300);
        }
        _config.AdminTarget = LocalTarget;
        RefreshSrvPicker();
        // Ce PC, serveur arrêté : le bouton Démarrer, et le port et la mémoire qui restent réglables.
        _srvCaptureStopped = true;
        await SrvPollAsync(force: true);
        await Task.Delay(300);
        await SnapshotAsync(dir, "Server-arrete");
        _srvCaptureStopped = false;
        await SrvPollAsync(force: true);
        await Task.Delay(300);
        await SnapshotAsync(dir, "Server");

        await Tab("console");
        SrvCommandBox.Text = "list";
        SrvSendCommand(this, new RoutedEventArgs());
        await Task.Delay(200);
        await SrvPollAsync(force: true);
        await Task.Delay(300);
        await SnapshotAsync(dir, "Server-console");

        await Tab("reglages");
        SrvWhitelistName.Text = "Zoe";
        SrvWhitelistAdd(this, new RoutedEventArgs());
        await Task.Delay(300);
        await SnapshotAsync(dir, "Server-reglages");

        await Tab("sauvegardes");
        await SnapshotAsync(dir, "Server-sauvegardes");

        await Tab("apercu");
        SrvPicker.SelectedIndex = 1;
        await SrvPollAsync(force: true);
        await Task.Delay(300);
        await SnapshotAsync(dir, "Server-distant");

        SrvAdd(this, new RoutedEventArgs());
        SrvFormName.Text = "Serveur de Léa";
        SrvFormHost.Text = "lea.exemple.fr";
        SrvFormKey.Password = DemoAdminApi.Key;
        await Task.Delay(300);
        await SnapshotAsync(dir, "Server-ajout");
        // Enregistrer (en mémoire seulement : les captures n'écrivent pas launcher.json) : la clé passe par DPAPI,
        // le serveur est ajouté à la liste et choisi.
        SrvFormSave(this, new RoutedEventArgs());
        await SrvPollAsync(force: true);
        if (SelectedRemote?.Name != "Serveur de Léa" || SelectedRemote.ReadKey() != DemoAdminApi.Key || SrvPicker.Items.Count != 3)
        {
            throw new InvalidOperationException("Page Serveur : l'enregistrement d'un serveur distant a échoué.");
        }
    }
}
