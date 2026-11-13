using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Valcombe.Launcher.Services;
using Valcombe.Launcher.Services.Admin;
using Valcombe.Launcher.Services.Ssh;

namespace Valcombe.Launcher;

/// <summary>
/// Page Serveur : enregistrer un serveur loué (VPS) par un parcours guidé, et ce que le launcher sait faire d'un
/// serveur qu'il a installé (le démarrer, le mettre à jour).
///
/// Le parcours (<see cref="VpsSetup"/>, sans interface) : adresse et port SSH, compte et secret (mot de passe ou
/// clé privée), première connexion avec confirmation de l'empreinte de la clé du serveur (une clé changée est
/// refusée net), examen du système, installation de Valcombe (contrat de Minecraft accepté explicitement), premier
/// démarrage jusqu'à ce que l'API réponde par le tunnel, puis premier opérateur et liste blanche. La fiche apparaît
/// alors dans la liste des serveurs de la page. Tout ce qui touche au réseau est attendu sans bloquer l'interface,
/// et « Annuler » arrête ce qui est en cours.
/// </summary>
public partial class MainWindow
{
    private static readonly string[] VpsStepNames = { "Adresse", "Compte", "Empreinte", "Système", "Installation", "Joueurs" };
    private const int VpsDone = 6;

    private bool _vpsOpen;
    private VpsSetup? _vps;
    private IVpsConnector? _vpsConnector;
    private int _vpsStep;
    private bool _vpsBusy;
    private CancellationTokenSource? _vpsCts;
    /// <summary>La fiche modifiée (null pour un nouveau serveur).</summary>
    private RemoteServer? _vpsEditing;
    /// <summary>La fiche enregistrée par ce parcours (dès que l'API a répondu).</summary>
    private RemoteServer? _vpsFiche;
    /// <summary>L'accès à l'API ouvert à la fin de l'installation, pour la liste blanche et les opérateurs.</summary>
    private IServerConnection? _vpsAdmin;
    private bool _vpsInstalling;
    private bool _vpsInstallFailed;
    private readonly ObservableCollection<string> _vpsLog = new();
    /// <summary>Ce que l'étape des joueurs a réglé, pour le redire à la fin.</summary>
    private string _vpsPlayersSummary = "";

    // --- Serveur installé par le launcher (tunnel SSH) : version, démarrage, mise à jour ---
    private VpsServiceStatus? _srvManaged;
    private DateTime _srvManagedAt = DateTime.MinValue;
    private bool _srvManagedReading;
    private bool _srvUpdating;
    private DateTime? _srvStartingSince;
    private DemoManagedConnection? _srvDemoManaged;

    /// <summary>Pour les captures : un fichier d'empreintes et de profils à part, jamais ceux du joueur.</summary>
    private string CaptureVpsDir => Path.Combine(Path.GetTempPath(), "valcombe-apercu-vps");

    private string? VpsProfilesFile => _captureDir != null ? Path.Combine(CaptureVpsDir, "serveurs-loues.json") : null;

    private IVpsConnector VpsConnector => _vpsConnector ??= _captureDir != null
        ? new DemoVpsConnector(new KnownHosts(Path.Combine(CaptureVpsDir, "ssh-hotes-connus.json"))) { StepDelay = TimeSpan.FromMilliseconds(250) }
        : new SshVpsConnector();

    // ------------------------------------------------------------------
    // Ouvrir, fermer
    // ------------------------------------------------------------------
    /// <summary>« Ajouter un serveur » : le choix entre un serveur loué (parcours guidé) et l'accès direct.</summary>
    private void VpsOpenChoice()
    {
        VpsClose(keepSelection: true);
        _vpsOpen = true;
        VpsChoice.Visibility = Visibility.Visible;
        VpsWizard.Visibility = Visibility.Collapsed;
        SrvShowTab();
        VpsPanel.ScrollToTop();
    }

    private void VpsChooseDirect(object sender, RoutedEventArgs e)
    {
        VpsClose(keepSelection: true);
        SrvAddDirect();
    }

    private void VpsStartNew(object sender, RoutedEventArgs e) => VpsBegin(null, null);

    /// <summary>Ouvre le parcours, pour un nouveau serveur ou pour modifier la fiche <paramref name="fiche"/>.</summary>
    private void VpsBegin(RemoteServer? fiche, VpsProfile? profile)
    {
        VpsClose(keepSelection: true);
        _vpsOpen = true;
        _vpsEditing = fiche;
        _vps = new VpsSetup(VpsConnector, profile);
        var p = _vps.Profile;
        VpsChoice.Visibility = Visibility.Collapsed;
        VpsWizard.Visibility = Visibility.Visible;
        VpsTitle.Text = fiche == null ? "Enregistrer un serveur loué" : "Modifier « " + fiche.Name + " »";
        VpsName.Text = fiche?.Name ?? "";
        VpsHost.Text = p.Host;
        VpsSshPort.Text = p.SshPort.ToString(Fr);
        VpsUser.Text = p.User;
        VpsAuthPassword.IsChecked = p.Auth == SshAuthKind.Password;
        VpsAuthKey.IsChecked = p.Auth == SshAuthKind.PrivateKey;
        VpsPassword.Password = "";
        VpsPassphrase.Password = "";
        VpsSudo.Password = "";
        VpsKeyFile.Text = "";
        VpsSudoToggle.IsChecked = p.SudoPasswordProtected.Length > 0;
        VpsSudo.Visibility = VpsSudoToggle.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        bool edit = fiche != null;
        VpsPasswordHint.Text = edit && p.PasswordProtected.Length > 0 ? "Laissez vide pour garder le mot de passe enregistré." : "Le mot de passe du compte, celui de la connexion SSH.";
        VpsKeyHint.Text = edit && p.PrivateKeyProtected.Length > 0
            ? "Une clé est enregistrée : choisissez-en une autre seulement pour la remplacer. Le launcher garde son texte (chiffré), pas son chemin."
            : "Le launcher lit la clé et la garde chiffrée : le fichier peut ensuite changer de place. Sa clé publique doit être dans ~/.ssh/authorized_keys sur le serveur.";
        VpsEula.IsChecked = p.EulaAccepted;
        VpsFirewall.IsChecked = p.OpenFirewall;
        VpsGamePort.Text = p.MinecraftPort.ToString(Fr);
        VpsVoicePort.Text = p.VoicePort.ToString(Fr);
        VpsOp.Text = _config.HostName;
        VpsWhitelist.Text = string.Join(", ", _config.HostGuests);
        VpsWhitelistOn.IsChecked = true;
        VpsPlayersResults.Children.Clear();
        VpsRemove.Visibility = edit ? Visibility.Visible : Visibility.Collapsed;
        VpsLog.ItemsSource = _vpsLog;
        _vpsLog.Clear();
        VpsGo(0);
        SrvShowTab();
        VpsPanel.ScrollToTop();
        VpsName.Focus();
    }

    /// <summary>Ferme le parcours : la connexion SSH est fermée, une opération en cours est arrêtée.</summary>
    private void VpsClose(bool keepSelection)
    {
        _vpsCts?.Cancel();
        _vpsCts = null;
        _vps?.Dispose();
        _vps = null;
        if (_vpsAdmin != null)
        {
            _ = _vpsAdmin.DisposeAsync().AsTask();
            _vpsAdmin = null;
        }
        _vpsBusy = false;
        _vpsInstalling = false;
        _vpsInstallFailed = false;
        _vpsOpen = false;
        var fiche = _vpsFiche;
        _vpsFiche = null;
        _vpsEditing = null;
        if (VpsPanel != null)
        {
            SrvShowTab();
        }
        if (fiche != null && !keepSelection)
        {
            // La fiche enregistrée par le parcours devient le serveur choisi.
            _config.AdminTarget = fiche.Id;
            SaveConfig();
            RefreshSrvPicker();
            _srvFieldsFilled = false;
            SrvEnsureConnection();
            _ = SrvPollAsync(force: true);
        }
    }

    private void VpsCancel(object sender, RoutedEventArgs e)
    {
        if (_vpsBusy && _vpsCts != null)
        {
            if (_vpsInstalling && MessageBox.Show(this, "Arrêter l'installation en cours ?\n\nLe serveur peut rester à moitié installé : relancer l'installation "
                    + "reprendra là où elle s'est arrêtée, sans rien retélécharger d'inutile.", "Les Cendres de Valcombe",
                    MessageBoxButton.OKCancel, MessageBoxImage.Warning) != MessageBoxResult.OK)
            {
                return;
            }
            _vpsCts.Cancel();
            return;
        }
        VpsClose(keepSelection: _vpsFiche == null);
    }

    // ------------------------------------------------------------------
    // Étapes
    // ------------------------------------------------------------------
    /// <summary>Montre l'étape <paramref name="step"/> (0 à 5, <see cref="VpsDone"/> pour la fin) et règle les boutons.</summary>
    private void VpsGo(int step)
    {
        _vpsStep = step;
        var panels = new FrameworkElement[] { VpsStepAddress, VpsStepAccount, VpsStepKey, VpsStepSystem, VpsStepInstall, VpsStepPlayers, VpsStepDone };
        for (int i = 0; i < panels.Length; i++)
        {
            panels[i].Visibility = i == step ? Visibility.Visible : Visibility.Collapsed;
        }
        VpsMessageBox.Visibility = Visibility.Collapsed;
        VpsStepCount.Text = step < VpsDone ? $"Étape {step + 1} sur {VpsStepNames.Length}" : "Terminé";
        VpsRenderSteps();
        VpsRenderButtons();
        VpsPanel.ScrollToTop();
    }

    /// <summary>La frise des étapes : faites (coche), en cours (doré), à venir (gris).</summary>
    private void VpsRenderSteps()
    {
        VpsSteps.Children.Clear();
        for (int i = 0; i < VpsStepNames.Length; i++)
        {
            bool done = i < _vpsStep;
            bool current = i == _vpsStep;
            var line = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            var dot = new Border
            {
                Width = 22, Height = 22, CornerRadius = new CornerRadius(11), Margin = new Thickness(0, 0, 8, 0),
                Background = done ? new SolidColorBrush(Color.FromArgb(0x40, 0x6F, 0xC2, 0x7A)) : current ? Res("Accent") : new SolidColorBrush(Color.FromArgb(0x14, 0xFF, 0xFF, 0xFF)),
                BorderBrush = done ? Res("Good") : current ? Res("Gold") : Res("FieldLine"), BorderThickness = new Thickness(1),
                Child = new TextBlock
                {
                    Text = done ? "\uE73E" : (i + 1).ToString(Fr), FontFamily = done ? (FontFamily)FindResource("Icons") : (FontFamily)FindResource("Body"),
                    FontSize = done ? 10 : 11.5, FontWeight = FontWeights.SemiBold, Foreground = done ? Res("Good") : current ? Res("Ink") : Res("Ash"),
                    HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
                },
            };
            line.Children.Add(dot);
            line.Children.Add(new TextBlock
            {
                Text = VpsStepNames[i], FontSize = 12.5, VerticalAlignment = VerticalAlignment.Center,
                Foreground = current ? Res("Gold") : done ? Res("Parch") : Res("Ash"), FontWeight = current ? FontWeights.SemiBold : FontWeights.Normal,
            });
            VpsSteps.Children.Add(line);
        }
    }

    /// <summary>Les boutons du bas selon l'étape et ce qui est en cours.</summary>
    private void VpsRenderButtons()
    {
        string primary = "";
        string? secondary = null;
        bool back = false;
        bool edit = _vpsEditing != null;
        var v = _vps;
        switch (_vpsStep)
        {
            case 0:
                primary = "Continuer";
                break;
            case 1:
                primary = "Se connecter";
                back = true;
                break;
            case 2:
                primary = v?.PendingKey is SshHostKeyUnknownException ? "Les empreintes sont identiques : accepter" : "";
                back = true;
                break;
            case 3:
                back = true;
                if (v?.SystemInfo == null)
                {
                    primary = "Examiner le serveur";
                }
                else if (!v.SystemInfo.CanInstall && v.Status is not { Installed: true })
                {
                    primary = "";
                }
                else if (edit)
                {
                    primary = "Enregistrer";
                    secondary = v.NeedsInstall(VpsKnownRelease) && v.SystemInfo.CanInstall ? "Installer ou mettre à jour" : null;
                }
                else if (v.NeedsInstall(VpsKnownRelease) && v.SystemInfo.CanInstall)
                {
                    primary = "Continuer";
                    secondary = v.Status is { Installed: true } ? "Garder la version installée" : null;
                }
                else
                {
                    primary = v.Status is { Active: true } ? "Continuer" : "Démarrer et continuer";
                }
                break;
            case 4:
                primary = _vpsInstallFailed ? "Réessayer l'installation" : "Installer Valcombe " + (VpsKnownRelease?.Tag ?? "");
                back = !_vpsInstalling;
                break;
            case 5:
                primary = "Appliquer et terminer";
                secondary = "Passer cette étape";
                break;
            default:
                primary = "Ouvrir la fiche du serveur";
                break;
        }
        VpsPrimaryText.Text = _vpsBusy ? VpsBusyLabel() : primary;
        VpsPrimary.Visibility = primary.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        VpsPrimary.IsEnabled = !_vpsBusy && (_vpsStep != 4 || VpsEula.IsChecked == true);
        VpsSecondary.Content = secondary;
        VpsSecondary.Visibility = secondary != null ? Visibility.Visible : Visibility.Collapsed;
        VpsSecondary.IsEnabled = !_vpsBusy;
        VpsBack.Visibility = back ? Visibility.Visible : Visibility.Collapsed;
        VpsBack.IsEnabled = !_vpsBusy;
        VpsCancelButton.Content = _vpsBusy ? "Arrêter" : _vpsStep == VpsDone ? "Fermer" : "Annuler";
        VpsRemove.IsEnabled = !_vpsBusy;
    }

    private string VpsBusyLabel() => _vpsStep switch
    {
        1 or 2 => "Connexion…",
        3 => "Examen…",
        4 => "Installation…",
        5 => "Envoi…",
        _ => "Patience…",
    };

    /// <summary>Un message sous l'étape : « erreur » (rouge), « attente » (doré) ou « ok » (vert).</summary>
    private void VpsSay(string text, string kind)
    {
        VpsMessage.Text = text;
        (VpsMessageIcon.Text, VpsMessageIcon.Foreground, VpsMessageBox.Background, VpsMessageBox.BorderBrush) = kind switch
        {
            "erreur" => ("\uE7BA", Res("Bad"), (Brush)new SolidColorBrush(Color.FromArgb(0x22, 0xD8, 0x60, 0x5A)), (Brush)new SolidColorBrush(Color.FromArgb(0x80, 0xD8, 0x60, 0x5A))),
            "ok" => ("\uE73E", Res("Good"), new SolidColorBrush(Color.FromArgb(0x1C, 0x6F, 0xC2, 0x7A)), new SolidColorBrush(Color.FromArgb(0x66, 0x6F, 0xC2, 0x7A))),
            _ => ("\uE916", Res("Gold"), new SolidColorBrush(Color.FromArgb(0x1A, 0xE2, 0xBD, 0x63)), new SolidColorBrush(Color.FromArgb(0x55, 0xE2, 0xBD, 0x63))),
        };
        VpsMessageBox.Visibility = text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    /// <summary>Lance une opération longue : boutons figés (sauf « Arrêter »), message d'attente, erreurs montrées.</summary>
    private async Task VpsRunAsync(string waiting, Func<CancellationToken, Task> work)
    {
        if (_vpsBusy)
        {
            return;
        }
        var cts = new CancellationTokenSource();
        _vpsCts = cts;
        _vpsBusy = true;
        VpsSay(waiting, "attente");
        VpsRenderButtons();
        try
        {
            await work(cts.Token);
        }
        catch (OperationCanceledException) when (cts.IsCancellationRequested)
        {
            if (_vpsOpen)
            {
                VpsSay("Arrêté.", "erreur");
            }
        }
        catch (Exception ex) when (ex is InvalidOperationException or TimeoutException or AdminException or IOException
                                       or Renci.SshNet.Common.SshException or System.Net.Sockets.SocketException or System.Net.Http.HttpRequestException)
        {
            if (_vpsOpen)
            {
                VpsSay(ex.Message, "erreur");
            }
        }
        catch (Exception ex) when (_vpsOpen)
        {
            // Rien ne doit faire tomber le launcher depuis ce parcours : l'imprévu est montré tel quel.
            VpsSay("Erreur inattendue : " + ex.Message, "erreur");
        }
        finally
        {
            if (_vpsCts == cts)
            {
                _vpsCts = null;
                _vpsBusy = false;
            }
            cts.Dispose();
            if (_vpsOpen)
            {
                VpsRenderButtons();
            }
        }
    }

    private async void VpsPrimaryClick(object sender, RoutedEventArgs e)
    {
        switch (_vpsStep)
        {
            case 0:
                VpsAddressNext();
                break;
            case 1:
                await VpsConnectAsync(null);
                break;
            case 2:
                await VpsConnectAsync(_vps?.PendingKey?.Fingerprint);
                break;
            case 3:
                await VpsSystemNextAsync(primary: true);
                break;
            case 4:
                await VpsInstallAsync();
                break;
            case 5:
                await VpsApplyPlayersAsync();
                break;
            default:
                VpsClose(keepSelection: false);
                break;
        }
    }

    private async void VpsSecondaryClick(object sender, RoutedEventArgs e)
    {
        switch (_vpsStep)
        {
            case 3:
                await VpsSystemNextAsync(primary: false);
                break;
            case 5:
                VpsFinish();
                break;
        }
    }

    private void VpsBackClick(object sender, RoutedEventArgs e)
    {
        if (_vpsBusy)
        {
            return;
        }
        // Revenir avant la connexion la referme : adresse, compte ou clé ont pu changer.
        int target = _vpsStep switch { 1 => 0, 2 or 3 => 1, 4 => 3, _ => _vpsStep };
        if (target <= 1)
        {
            _vps?.Dispose();
        }
        VpsGo(target);
    }

    // ------------------------------------------------------------------
    // 1. Adresse
    // ------------------------------------------------------------------
    private void VpsAddressNext()
    {
        var problems = _vps!.SetAddress(VpsName.Text, VpsHost.Text, VpsSshPort.Text, out string? user);
        if (problems.Count > 0)
        {
            VpsSay(string.Join(" ", problems), "erreur");
            return;
        }
        VpsHost.Text = _vps.Profile.Host;
        VpsSshPort.Text = _vps.Profile.SshPort.ToString(Fr);
        if (user != null)
        {
            VpsUser.Text = user; // « root@vps.exemple.fr » collé : le compte va à sa place
        }
        if (VpsName.Text.Trim().Length == 0)
        {
            VpsName.Text = _vps.Profile.Name;
        }
        VpsGo(1);
        VpsUser.Focus();
    }

    // ------------------------------------------------------------------
    // 2. Compte
    // ------------------------------------------------------------------
    private void VpsAuthChanged(object sender, RoutedEventArgs e)
    {
        if (VpsKeyPanel == null)
        {
            return;
        }
        bool key = VpsAuthKey.IsChecked == true;
        VpsKeyPanel.Visibility = key ? Visibility.Visible : Visibility.Collapsed;
        VpsPasswordPanel.Visibility = key ? Visibility.Collapsed : Visibility.Visible;
    }

    private void VpsSudoToggled(object sender, RoutedEventArgs e) =>
        VpsSudo.Visibility = VpsSudoToggle.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;

    private void VpsPickKey(object sender, RoutedEventArgs e)
    {
        string ssh = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".ssh");
        var dialog = new Microsoft.Win32.OpenFileDialog
        {
            Title = "Choisir la clé privée SSH",
            Filter = "Clés privées (id_*, *.pem, *.ppk, *.key)|id_*;*.pem;*.ppk;*.key|Tous les fichiers (*.*)|*.*",
            InitialDirectory = Directory.Exists(ssh) ? ssh : Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            CheckFileExists = true,
        };
        if (dialog.ShowDialog(this) != true)
        {
            return;
        }
        if (dialog.FileName.EndsWith(".pub", StringComparison.OrdinalIgnoreCase))
        {
            VpsSay("C'est la clé publique (.pub) : choisissez le fichier de la clé privée, le même nom sans « .pub ».", "erreur");
            return;
        }
        VpsKeyFile.Text = dialog.FileName;
        VpsMessageBox.Visibility = Visibility.Collapsed;
    }

    /// <summary>Enregistre le compte, puis se connecte (avec l'empreinte confirmée, s'il y en a une).</summary>
    private async Task VpsConnectAsync(string? fingerprint)
    {
        var v = _vps!;
        await VpsRunAsync(fingerprint == null ? $"Connexion à {VpsUser.Text.Trim()}@{v.Profile.Host}…" : "Connexion avec l'empreinte acceptée…", async ct =>
        {
            if (_vpsStep == 1)
            {
                var problems = await v.SetAccountAsync(VpsUser.Text, VpsAuthKey.IsChecked == true ? SshAuthKind.PrivateKey : SshAuthKind.Password,
                    VpsPassword.Password, VpsKeyFile.Text.Length > 0 ? VpsKeyFile.Text : null, VpsPassphrase.Password,
                    VpsSudoToggle.IsChecked == true ? VpsSudo.Password : "");
                if (problems.Count > 0)
                {
                    VpsSay(string.Join(" ", problems), "erreur");
                    return;
                }
            }
            var result = await v.ConnectAsync(fingerprint, ct);
            if (!_vpsOpen)
            {
                return;
            }
            switch (result)
            {
                case VpsConnectResult.Connected:
                    VpsPassword.Password = "";
                    VpsPassphrase.Password = "";
                    VpsSudo.Password = "";
                    VpsGo(3);
                    await VpsExamineAsync(ct);
                    break;
                case VpsConnectResult.UnknownKey:
                    VpsShowKey();
                    break;
                case VpsConnectResult.ChangedKey:
                    VpsShowKey();
                    break;
                default:
                    // Adresse, port ou compte : on revient là où se corrige la faute.
                    if (_vpsStep == 2)
                    {
                        VpsGo(1);
                    }
                    VpsSay(v.Error, "erreur");
                    break;
            }
        });
    }

    // ------------------------------------------------------------------
    // 3. Empreinte
    // ------------------------------------------------------------------
    private void VpsShowKey()
    {
        var key = _vps!.PendingKey!;
        VpsGo(2);
        bool changed = key is SshHostKeyChangedException;
        VpsKeyNew.Visibility = changed ? Visibility.Collapsed : Visibility.Visible;
        VpsKeyChanged.Visibility = changed ? Visibility.Visible : Visibility.Collapsed;
        VpsFingerprint.Text = key.Fingerprint;
        VpsKeyAlgo.Text = $"Clé {key.Algorithm} de {key.Host}" + (key.Port == 22 ? "" : $" (port {key.Port})");
        if (key is SshHostKeyChangedException c)
        {
            var known = _vps.KnownKey;
            VpsKeyChangedText.Text = $"Retenue{(known != null ? " le " + known.AcceptedOn.ToLocalTime().ToString("d MMM yyyy", Fr) : "")} : {c.Expected}\n"
                + $"Présentée aujourd'hui : {c.Fingerprint}";
        }
        VpsRenderButtons();
    }

    private void VpsCopyFingerprint(object sender, RoutedEventArgs e)
    {
        Clipboard.SetText(VpsFingerprint.Text);
        SetStatus("Empreinte copiée.", 0);
    }

    private void VpsForgetKey(object sender, RoutedEventArgs e)
    {
        var v = _vps;
        if (v == null || MessageBox.Show(this, $"Oublier l'empreinte retenue pour {v.Profile.Host} ?\n\nÀ ne faire que si vous avez réinstallé ce serveur "
                + "(ou changé de serveur à cette adresse). La connexion suivante vous montrera la nouvelle empreinte, à comparer à celle de l'hébergeur "
                + "avant de l'accepter.", "Les Cendres de Valcombe", MessageBoxButton.OKCancel, MessageBoxImage.Warning) != MessageBoxResult.OK)
        {
            return;
        }
        v.ForgetKey();
        _ = VpsConnectAsync(null);
    }

    // ------------------------------------------------------------------
    // 4. Système
    // ------------------------------------------------------------------
    private async Task VpsExamineAsync(CancellationToken ct)
    {
        var v = _vps!;
        VpsSay("Examen du système du serveur…", "attente");
        await v.ExamineAsync(ct);
        VpsRenderSystem();
    }

    private Release? VpsKnownRelease => _release ?? (_captureDir != null ? CaptureRelease : null);

    private static readonly Release CaptureRelease = new("v0.14.0", "", new Dictionary<string, string>(),
        new PackManifest { Version = "0.14.0", Minecraft = "26.3", Loader = "0.17.3" });

    /// <summary>La dernière version publiée : celle déjà lue au lancement, sinon lue maintenant.</summary>
    private async Task<Release> VpsReleaseAsync(CancellationToken ct)
    {
        if (VpsKnownRelease is { } known)
        {
            return known;
        }
        try
        {
            _release = await Releases.LatestAsync(_config.Repository, ct);
            return _release;
        }
        catch (Exception ex) when (ex is System.Net.Http.HttpRequestException or IOException or System.Text.Json.JsonException)
        {
            throw new InvalidOperationException("Impossible de lire la dernière version publiée de Valcombe : " + ex.Message);
        }
    }

    private void VpsRenderSystem()
    {
        var v = _vps!;
        var s = v.SystemInfo;
        var st = v.Status;
        VpsSystemList.Children.Clear();
        if (s == null)
        {
            return;
        }
        var latest = VpsKnownRelease;
        string root = v.Session?.Root switch
        {
            RootAccess.Root => "root",
            RootAccess.SudoWithoutPassword => "sudo, sans mot de passe",
            RootAccess.SudoWithPassword => "sudo, avec mot de passe",
            _ => "refusés",
        };
        string arch = VpsPlan.NormalizeArch(s.Arch);
        VpsCheckRow(s.Kernel == "Linux" ? "ok" : "non", "Système", s.DistroName.Length > 0 ? s.DistroName : s.Kernel);
        VpsCheckRow(arch is "x86_64" or "aarch64" ? "ok" : "non", "Processeur", s.Arch);
        VpsCheckRow(s.Systemd ? "ok" : "non", "Service systemd", s.Systemd ? "présent" : "absent");
        VpsCheckRow(v.Session?.Root != RootAccess.None ? "ok" : "non", "Droits d'administration", root + " (compte " + v.Profile.User + ")");
        VpsCheckRow(s.MemoryMb >= 2560 ? "ok" : "attention", "Mémoire", (s.MemoryMb / 1024.0).ToString("0.0", Fr) + " Go"
            + (s.MemoryMb < 2560 ? " : juste pour Minecraft (3 Go ou plus conseillés)" : ""));
        if (s.FreeDiskMb >= 0)
        {
            VpsCheckRow(s.FreeDiskMb >= 2048 ? "ok" : "attention", "Disque libre", (s.FreeDiskMb / 1024.0).ToString("0.0", Fr) + " Go"
                + (s.FreeDiskMb < 2048 ? " : 2 Go au moins conseillés" : ""));
        }
        if (st is { Installed: true })
        {
            bool outdated = latest != null && VpsSetup.Normalize(st.Version) != VpsSetup.Normalize(latest.Tag);
            VpsCheckRow(outdated ? "attention" : "ok", "Valcombe", $"{(st.Version.Length > 0 ? st.Version : "version inconnue")}, {st.Text.ToLowerInvariant()}"
                + (outdated ? $" : la version {latest!.Tag} est publiée" : ""));
        }
        else
        {
            VpsCheckRow("info", "Valcombe", "pas encore installé" + (latest != null ? $" (version publiée : {latest.Tag})" : ""));
        }
        foreach (string w in s.Warnings.Where(w => !w.StartsWith("Mémoire du serveur", StringComparison.Ordinal)))
        {
            VpsCheckRow("attention", "", w);
        }
        bool sudo = v.RootProblem != null;
        VpsSudoRetry.Visibility = sudo ? Visibility.Visible : Visibility.Collapsed;
        VpsSystemIntro.Text = $"Connecté à {v.Session?.Label ?? v.Profile.Display}" + (v.KnownKey is { } k ? $", empreinte confirmée le {k.AcceptedOn.ToLocalTime():d MMM yyyy}." : ".")
            + " Voici ce que le launcher a trouvé sur le serveur.";
        if (sudo)
        {
            VpsSay(v.RootProblem!, "erreur");
        }
        else if (!s.CanInstall && st is not { Installed: true })
        {
            VpsSay("Valcombe ne peut pas être installé sur ce serveur : " + string.Join(" ", s.Problems), "erreur");
        }
        else if (_vpsEditing != null)
        {
            VpsSay("Connexion réussie. « Enregistrer » garde ces changements dans la fiche.", "ok");
        }
        else
        {
            VpsSay(st is { Installed: true } && !v.NeedsInstall(latest) ? "Valcombe est déjà installé et à jour : il reste à le démarrer et à dire qui y entre."
                : "Tout est prêt pour installer Valcombe.", "ok");
        }
        VpsRenderButtons();
    }

    /// <summary>Une ligne de l'examen : « ok », « attention », « non » ou « info ».</summary>
    private void VpsCheckRow(string verdict, string label, string value)
    {
        var row = new Grid { Margin = new Thickness(0, 3, 0, 3) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(26) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(label.Length > 0 ? 190 : 0) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        (string glyph, Brush color) = verdict switch
        {
            "ok" => ("\uE73E", Res("Good")),
            "non" => ("\uE711", Res("Bad")),
            "info" => ("\uE946", Res("Gold")),
            _ => ("\uE7BA", Res("Gold")),
        };
        row.Children.Add(new TextBlock { Text = glyph, FontFamily = (FontFamily)FindResource("Icons"), FontSize = 12, Foreground = color, Margin = new Thickness(0, 3, 0, 0) });
        var name = new TextBlock { Text = label, FontSize = 13, Foreground = Res("Ash") };
        Grid.SetColumn(name, 1);
        row.Children.Add(name);
        var text = new TextBlock { Text = value, FontSize = 13, Foreground = verdict == "non" ? Res("Bad") : Res("Ink"), TextWrapping = TextWrapping.Wrap };
        Grid.SetColumn(text, 2);
        row.Children.Add(text);
        VpsSystemList.Children.Add(row);
    }

    private async void VpsRetrySudo(object sender, RoutedEventArgs e)
    {
        var v = _vps;
        if (v == null || VpsSudoRetryBox.Password.Length == 0)
        {
            VpsSay("Indiquez le mot de passe que sudo demande à ce compte.", "erreur");
            return;
        }
        v.Profile.SetSudoPassword(VpsSudoRetryBox.Password);
        VpsSudoRetryBox.Password = "";
        await VpsRunAsync("Nouvelle connexion avec le mot de passe sudo…", async ct =>
        {
            if (await v.ConnectAsync(null, ct) != VpsConnectResult.Connected)
            {
                VpsSay(v.Error, "erreur");
                return;
            }
            await VpsExamineAsync(ct);
        });
    }

    /// <summary>
    /// Suite de l'examen : l'installation si elle s'impose (ou si l'hôte la choisit), sinon le démarrage et l'API ;
    /// en modification, « Enregistrer » garde la fiche.
    /// </summary>
    private async Task VpsSystemNextAsync(bool primary)
    {
        var v = _vps!;
        if (v.SystemInfo == null)
        {
            await VpsRunAsync("Examen du système du serveur…", VpsExamineAsync);
            return;
        }
        bool needs = v.NeedsInstall(VpsKnownRelease) && v.SystemInfo.CanInstall;
        if (_vpsEditing != null && primary)
        {
            VpsSaveFiche();
            VpsFinish();
            return;
        }
        if ((needs && primary) || (_vpsEditing != null && !primary))
        {
            VpsEnterInstall();
            return;
        }
        await VpsRunAsync("Démarrage du serveur…", async ct =>
        {
            await VpsReachAsync(ct);
        });
    }

    // ------------------------------------------------------------------
    // 5. Installation
    // ------------------------------------------------------------------
    private void VpsEnterInstall()
    {
        var v = _vps!;
        var p = v.Profile;
        long machine = v.SystemInfo?.MemoryMb ?? 0;
        double maxGb = machine > 0 ? Math.Clamp(Math.Floor((machine - 512) / 512.0) / 2, 1, 16) : 16;
        VpsMemory.Maximum = maxGb;
        int suggested = _vpsEditing == null ? VpsSetup.SuggestedMemoryMb(machine) : p.MemoryMb;
        VpsMemory.Value = Math.Clamp(suggested / 1024.0, 1, maxGb);
        VpsMemoryChanged(this, new RoutedPropertyChangedEventArgs<double>(0, VpsMemory.Value));
        VpsInstallDir.Text = p.InstallDir;
        VpsServiceUser.Text = p.ServiceUser;
        VpsServiceName.Text = p.ServiceName;
        VpsAdminPort.Text = p.AdminPort > 0 ? p.AdminPort.ToString(Fr) : "";
        // Un serveur déjà installé garde son dossier, son compte et son service : ils ne se changent pas après coup.
        bool update = v.Status is { Installed: true };
        VpsInstallDir.IsEnabled = VpsServiceUser.IsEnabled = VpsServiceName.IsEnabled = !update;
        VpsAdvanced.Visibility = Visibility.Collapsed;
        VpsAdvancedToggle.Content = "Options avancées";
        var latest = VpsKnownRelease;
        VpsInstallIntro.Text = (update ? $"Le launcher met Valcombe à jour sur le serveur" : "Le launcher installe sur le serveur")
            + " : un compte système dédié (le jeu ne tourne jamais en root), Java, Fabric, les mods de la version publiée"
            + (latest != null ? $" ({latest.Tag})" : "") + ", et le service valcombe. Comptez quelques minutes.";
        VpsInstallOptions.Visibility = Visibility.Visible;
        VpsProgressBox.Visibility = Visibility.Collapsed;
        _vpsInstallFailed = false;
        VpsGo(4);
    }

    private void VpsMemoryChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (VpsMemoryLabel == null || VpsMemory == null || VpsMemoryHint == null)
        {
            return; // pendant le chargement de la fenêtre
        }
        VpsMemoryLabel.Text = "Mémoire pour Minecraft : " + VpsMemory.Value.ToString("0.#", Fr) + " Go";
        long machine = _vps?.SystemInfo?.MemoryMb ?? 0;
        VpsMemoryHint.Text = machine > 0
            ? $"Le serveur a {(machine / 1024.0).ToString("0.0", Fr)} Go : il en faut au moins 1 Go pour le système lui-même. 3 à 4 Go suffisent pour une soirée de 15 joueurs."
            : "3 à 4 Go suffisent pour une soirée de 15 joueurs.";
    }

    private void VpsEulaClicked(object sender, RoutedEventArgs e) => VpsRenderButtons();

    private void VpsToggleAdvanced(object sender, RoutedEventArgs e)
    {
        bool show = VpsAdvanced.Visibility != Visibility.Visible;
        VpsAdvanced.Visibility = show ? Visibility.Visible : Visibility.Collapsed;
        VpsAdvancedToggle.Content = show ? "Masquer les options avancées" : "Options avancées";
    }

    private void VpsOpenEula(object sender, System.Windows.Navigation.RequestNavigateEventArgs e)
    {
        Process.Start(new ProcessStartInfo(e.Uri.AbsoluteUri) { UseShellExecute = true });
        e.Handled = true;
    }

    private async Task VpsInstallAsync()
    {
        var v = _vps!;
        var p = v.Profile;
        if (VpsEula.IsChecked != true)
        {
            VpsSay("Cochez l'acceptation du contrat de Minecraft (EULA) : sans elle, le serveur ne démarre pas.", "erreur");
            return;
        }
        if (!int.TryParse(VpsGamePort.Text.Trim(), out int game) || !int.TryParse(VpsVoicePort.Text.Trim(), out int voice))
        {
            VpsSay("Ports : des nombres de 1024 à 65535.", "erreur");
            return;
        }
        int admin = 0;
        if (VpsAdminPort.Text.Trim().Length > 0 && !int.TryParse(VpsAdminPort.Text.Trim(), out admin))
        {
            VpsSay("Port de l'API : un nombre de 1024 à 65535, ou vide pour celui du mod.", "erreur");
            return;
        }
        p.MinecraftPort = game;
        p.VoicePort = voice;
        p.AdminPort = admin;
        p.InstallDir = VpsInstallDir.Text.Trim();
        p.ServiceUser = VpsServiceUser.Text.Trim();
        p.ServiceName = VpsServiceName.Text.Trim();
        p.MemoryMb = (int)(VpsMemory.Value * 1024);
        p.OpenFirewall = VpsFirewall.IsChecked == true;
        p.EulaAccepted = true;
        var problems = p.Problems();
        if (problems.Count > 0)
        {
            VpsSay(string.Join(" ", problems), "erreur");
            return;
        }
        _vpsInstalling = true;
        _vpsInstallFailed = false;
        _vpsLog.Clear();
        VpsInstallOptions.Visibility = Visibility.Collapsed;
        VpsProgressBox.Visibility = Visibility.Visible;
        VpsProgress.Value = 0;
        VpsProgressPercent.Text = "";
        VpsProgressText.Text = "Préparation de l'installation…";
        VpsProgressDetail.Text = "Le serveur télécharge lui-même Java, Fabric et les mods, depuis leurs sources officielles, et vérifie chaque fichier.";
        await VpsRunAsync("Installation en cours : vous pouvez laisser cette page, elle continue.", async ct =>
        {
            var release = await VpsReleaseAsync(ct);
            var progress = new Progress<(string Text, double P)>(x =>
            {
                VpsProgressText.Text = x.Text.Replace("Serveur loué : ", "");
                if (x.P >= 0)
                {
                    VpsProgress.Value = x.P;
                    VpsProgressPercent.Text = (int)(x.P * 100) + " %";
                }
            });
            var log = new Progress<string>(line =>
            {
                _vpsLog.Add(line);
                if (VpsLog.Items.Count > 0)
                {
                    VpsLog.ScrollIntoView(VpsLog.Items[^1]);
                }
            });
            try
            {
                var result = await v.InstallAsync(release, progress, log, ct);
                VpsProgress.Value = 1;
                VpsProgressPercent.Text = "100 %";
                VpsProgressText.Text = result.Changed ? $"Valcombe {release.Tag} installé." : $"Valcombe {release.Tag} était déjà en place.";
                _vpsInstalling = false;
                await VpsReachAsync(ct);
            }
            catch
            {
                _vpsInstalling = false;
                _vpsInstallFailed = true;
                VpsProgressText.Text = "L'installation s'est arrêtée.";
                VpsProgressPercent.Text = "";
                VpsProgressDetail.Text = "Le journal ci-dessous dit où. Corrigez si besoin, puis réessayez : l'installation reprend là où elle s'est arrêtée.";
                throw;
            }
        });
        _vpsInstalling = false;
        VpsRenderButtons();
    }

    /// <summary>Démarre le serveur s'il le faut, attend l'API, enregistre la fiche, puis passe aux joueurs.</summary>
    private async Task VpsReachAsync(CancellationToken ct)
    {
        var v = _vps!;
        var progress = new Progress<string>(text =>
        {
            VpsSay(text, "attente");
            if (_vpsStep == 4)
            {
                VpsProgressDetail.Text = text;
            }
        });
        // La fiche est enregistrée avant l'attente : un premier démarrage très long ne fait pas perdre le serveur installé.
        VpsSaveFiche();
        IServerConnection admin;
        try
        {
            admin = await v.StartAndReachAsync(progress, ct);
        }
        catch (Exception ex) when (ex is TimeoutException or InvalidOperationException or AdminException)
        {
            throw new InvalidOperationException(ex.Message + $" La fiche « {v.Profile.Name} » est enregistrée : fermez ce parcours, elle dira quand le serveur répond.", ex);
        }
        if (_vpsAdmin != null)
        {
            await _vpsAdmin.DisposeAsync();
        }
        _vpsAdmin = admin;
        VpsPlayersResults.Children.Clear();
        VpsGo(5);
        VpsSay($"« {v.Profile.Name} » est en ligne, et sa fiche est enregistrée.", "ok");
    }

    // ------------------------------------------------------------------
    // 6. Joueurs, et la fiche
    // ------------------------------------------------------------------
    private async Task VpsApplyPlayersAsync()
    {
        string op = VpsOp.Text.Trim();
        if (op.Length > 0 && !AdminClient.IsPlayerName(op))
        {
            VpsSay($"« {op} » n'est pas un pseudo Minecraft : lettres sans accent, chiffres et « _ », 16 caractères au plus.", "erreur");
            return;
        }
        var (names, bad) = VpsSetup.ParseNames(VpsWhitelist.Text);
        if (bad.Count > 0)
        {
            VpsSay("Pseudos invalides : " + string.Join(", ", bad.Select(b => "« " + b + " »"))
                + ". Un pseudo Minecraft : lettres sans accent, chiffres et « _ », 16 caractères au plus.", "erreur");
            return;
        }
        bool on = VpsWhitelistOn.IsChecked == true;
        if (on && op.Length == 0 && names.Count == 0)
        {
            VpsSay("La liste blanche est vide : personne ne pourrait entrer. Indiquez au moins votre pseudo, ou décochez la liste blanche.", "erreur");
            return;
        }
        var admin = _vpsAdmin;
        if (admin == null)
        {
            VpsFinish();
            return;
        }
        await VpsRunAsync("Envoi au serveur (les pseudos inconnus du serveur sont cherchés chez Mojang)…", async ct =>
        {
            var result = await new AdminClient(admin).ChangeSettingsAsync(VpsSetup.PlayersChange(op, names, on), ct);
            VpsPlayersResults.Children.Clear();
            foreach (var step in result.Resultats)
            {
                var row = new DockPanel { Margin = new Thickness(0, 2, 0, 2) };
                var mark = new TextBlock
                {
                    Text = step.Ok ? "\uE73E" : "\uE711", FontFamily = (FontFamily)FindResource("Icons"), FontSize = 12,
                    Foreground = step.Ok ? Res("Good") : Res("Bad"), Margin = new Thickness(0, 3, 10, 0), VerticalAlignment = VerticalAlignment.Top,
                };
                DockPanel.SetDock(mark, Dock.Left);
                row.Children.Add(mark);
                row.Children.Add(new TextBlock { Text = step.Message, Style = (Style)FindResource("Hint"), Margin = new Thickness(0), Foreground = Res("Parch") });
                VpsPlayersResults.Children.Add(row);
            }
            if (result.Ok)
            {
                var list = result.Reglages?.ListeBlanche;
                _vpsPlayersSummary = (op.Length > 0 ? $" Opérateur : {op}." : "")
                    + (list == null ? "" : list.Active
                        ? $" Liste blanche en service : {(list.Joueurs.Count == 0 ? "personne" : string.Join(", ", list.Joueurs))}."
                        : " Liste blanche hors service : tout le monde peut entrer.");
                if (op.Length > 0 && _config.HostName.Length == 0 && _captureDir == null)
                {
                    _config.HostName = op;
                    SaveConfig();
                }
                VpsFinish();
            }
            else
            {
                VpsSay("Certains points n'ont pas abouti (détail ci-dessus) : corrigez et appliquez de nouveau, ou passez cette étape.", "erreur");
            }
        });
    }

    /// <summary>Enregistre le profil SSH (secrets chiffrés) et la fiche de la page Serveur.</summary>
    private void VpsSaveFiche()
    {
        var v = _vps!;
        if (_captureDir != null)
        {
            Directory.CreateDirectory(CaptureVpsDir);
        }
        var fiche = v.Save(_config.RemoteServers, VpsProfilesFile);
        if (_vpsEditing != null)
        {
            fiche.Id = _vpsEditing.Id;
        }
        _vpsFiche = fiche;
        SaveConfig();
        RefreshSrvPicker();
    }

    private void VpsFinish()
    {
        var v = _vps!;
        var p = v.Profile;
        if (_vpsFiche == null)
        {
            VpsSaveFiche();
        }
        VpsDoneTitle.Text = _vpsEditing != null ? $"« {p.Name} » est à jour." : $"« {p.Name} » est prêt.";
        VpsDoneText.Text = _vpsEditing != null
            ? "Les changements sont enregistrés dans sa fiche. Les secrets restent chiffrés sur ce PC."
            : "Sa fiche est dans la liste des serveurs de la page : état, console, réglages, sauvegardes et mises à jour passent par le tunnel SSH, chiffré. "
                + "S'il s'arrête, le bouton Démarrer le relance d'ici." + _vpsPlayersSummary;
        _vpsPlayersSummary = "";
        VpsAddress.Text = p.Host + (p.MinecraftPort == 25565 ? "" : ":" + p.MinecraftPort);
        VpsGo(VpsDone);
        VpsSay("", "ok");
    }

    private void VpsCopyAddress(object sender, RoutedEventArgs e)
    {
        Clipboard.SetText(VpsAddress.Text);
        SetStatus("Adresse copiée : " + VpsAddress.Text, 0);
    }

    /// <summary>« Modifier » sur une fiche de serveur loué : le parcours, prérempli, secrets gardés si laissés vides.</summary>
    private void VpsOpenEdit(RemoteServer fiche)
    {
        VpsProfile? profile = _captureDir != null
            ? VpsProfiles.Load(VpsProfilesFile).Find(x => x.Id == fiche.VpsId) ?? CaptureProfile(fiche)
            : VpsProfiles.Load().Find(x => x.Id == fiche.VpsId);
        if (profile == null)
        {
            // Profil perdu (fichier effacé) : on repart d'un profil neuf à la même adresse, la fiche est gardée.
            profile = new VpsProfile { Name = fiche.Name, Host = fiche.Host };
            fiche.VpsId = profile.Id;
        }
        VpsBegin(fiche, profile);
    }

    /// <summary>Le profil fictif de la fiche « VPS de la guilde » des captures (compte debian, mot de passe enregistré).</summary>
    private static VpsProfile CaptureProfile(RemoteServer fiche)
    {
        var p = new VpsProfile { Id = fiche.VpsId, Name = fiche.Name, Host = fiche.Host, User = "debian", EulaAccepted = true };
        p.SetPassword("mot-de-passe-des-captures");
        return p;
    }

    private void VpsRemoveClick(object sender, RoutedEventArgs e)
    {
        var fiche = _vpsEditing;
        if (fiche == null || MessageBox.Show(this, $"Retirer « {fiche.Name} » de la liste ?\n\nSa fiche, son compte SSH et ses secrets (mot de passe, clé) "
                + "sont effacés de ce PC, ainsi que l'empreinte retenue de sa clé. Le serveur lui-même n'est pas touché : Valcombe y reste installé et y tourne.",
                "Les Cendres de Valcombe", MessageBoxButton.OKCancel, MessageBoxImage.Question) != MessageBoxResult.OK)
        {
            return;
        }
        VpsSetup.Remove(fiche, _config.RemoteServers, VpsConnector.KnownHosts, VpsProfilesFile);
        _config.AdminTarget = LocalTarget;
        SaveConfig();
        VpsClose(keepSelection: true);
        RefreshSrvPicker();
        SetStatus($"Serveur « {fiche.Name} » retiré : ses secrets sont effacés de ce PC.", 0);
        _srvFieldsFilled = false;
        _ = SrvPollAsync(force: true);
    }

    // ------------------------------------------------------------------
    // Serveur installé par le launcher : démarrage suivi, version, mise à jour
    // ------------------------------------------------------------------
    private void SrvManagedReset()
    {
        _srvManaged = null;
        _srvManagedAt = DateTime.MinValue;
    }

    private IManagedServer? Managed => !IsLocalTarget ? _srvConn as IManagedServer : null;

    /// <summary>Complète l'état du serveur loué : démarrage en cours, service arrêté ou en panne, mise à jour disponible.</summary>
    private void SrvRenderManaged(AdminState? st)
    {
        var managed = Managed;
        if (managed == null)
        {
            SrvUpdateButton.Visibility = Visibility.Collapsed;
            return;
        }
        // La version installée et l'état du service, lus par SSH : une fois par connexion, et de nouveau quand
        // l'API ne répond plus (le service est-il arrêté ? en panne ?).
        bool stale = _srvManaged == null ? DateTime.UtcNow - _srvManagedAt > TimeSpan.FromSeconds(30)
            : _srvError?.Status == 0 && DateTime.UtcNow - _srvManagedAt > TimeSpan.FromSeconds(20);
        if (!_srvManagedReading && !_srvUpdating && stale)
        {
            _ = SrvReadManagedAsync(managed);
        }
        if (_srvStartingSince is { } since)
        {
            if (st != null || DateTime.UtcNow - since > TimeSpan.FromMinutes(5))
            {
                _srvStartingSince = null;
            }
            else if (_srvError?.Status == 0)
            {
                SrvTitle.Text = "Démarrage du serveur…";
                SrvDot.Fill = Res("Gold");
                SrvDetail.Text = $"Minecraft démarre sur {managed.Where} ({AdminBackupText.Span(DateTime.UtcNow - since)}) : une à deux minutes. "
                    + "La page se reconnecte d'elle-même.";
                SrvStartButton.Visibility = Visibility.Collapsed;
                SrvRetryButton.Visibility = Visibility.Collapsed;
            }
        }
        else if (_srvError?.Status == 0 && _srvStopping.Length == 0 && _srvManaged is { } service)
        {
            if (service.Failed)
            {
                SrvTitle.Text = "Serveur en panne";
                SrvDot.Fill = Res("Bad");
                SrvDetail.Text = $"Le service valcombe s'est arrêté en erreur sur {managed.Where} : son journal (journalctl -u valcombe) dit pourquoi. "
                    + "Démarrer le relance.";
            }
            else if (!service.Active && service.Installed)
            {
                SrvTitle.Text = "Serveur arrêté";
                SrvDot.Fill = Grey;
                SrvDetail.Text = $"Le service valcombe est arrêté sur {managed.Where}. Démarrez-le d'ici : Minecraft met une à deux minutes à ouvrir.";
                SrvRetryButton.Visibility = Visibility.Collapsed; // rien à réessayer : la page voit dès qu'il revient
            }
            else if (!service.Installed)
            {
                SrvTitle.Text = "Valcombe absent";
                SrvDot.Fill = Res("Bad");
                SrvDetail.Text = $"Le service valcombe n'existe pas sur {managed.Where} : réinstallez-le (bouton Modifier, puis « Installer ou mettre à jour »).";
                SrvStartButton.Visibility = Visibility.Collapsed;
            }
        }
        var latest = VpsKnownRelease;
        bool outdated = _srvManaged is { Installed: true } s && s.Version.Length > 0 && latest != null
            && VpsSetup.Normalize(s.Version) != VpsSetup.Normalize(latest.Tag);
        SrvUpdateButton.Visibility = outdated || _srvUpdating ? Visibility.Visible : Visibility.Collapsed;
        SrvUpdateButton.IsEnabled = !_srvUpdating && _srvStopping.Length == 0;
        SrvUpdateText.Text = _srvUpdating ? "Mise à jour…" : "Mettre à jour (" + latest?.Tag + ")";
        SrvUpdateButton.ToolTip = outdated ? $"Installée : {_srvManaged!.Version}. Publiée : {latest!.Tag}." : null;
        if (outdated && st != null && _srvStopping.Length == 0)
        {
            SrvDetail.Text += $" · {latest!.Tag} est publiée";
        }
    }

    private async Task SrvReadManagedAsync(IManagedServer managed)
    {
        _srvManagedReading = true;
        int gen = _srvGen;
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(40));
            var status = await managed.ServiceStatusAsync(cts.Token);
            if (gen == _srvGen)
            {
                _srvManaged = status;
            }
        }
        catch (Exception ex) when (ex is AdminException or OperationCanceledException)
        {
            // Par SSH non plus : l'API, elle, dit déjà pourquoi le serveur ne répond pas.
        }
        finally
        {
            _srvManagedReading = false;
            _srvManagedAt = DateTime.UtcNow;
            if (gen == _srvGen)
            {
                SrvRender();
            }
        }
    }

    /// <summary>Démarrage d'un serveur loué arrêté : la page le suit jusqu'à ce que l'API réponde.</summary>
    private void SrvStartedRemote()
    {
        _srvStartingSince = DateTime.UtcNow;
        _srvManaged = null;
        _srvManagedAt = DateTime.UtcNow;
        _srvStopping = "";
    }

    private async void SrvUpdate(object sender, RoutedEventArgs e)
    {
        var managed = Managed;
        if (managed == null || _srvUpdating)
        {
            return;
        }
        Release release;
        try
        {
            release = await VpsReleaseAsync(CancellationToken.None);
        }
        catch (InvalidOperationException ex)
        {
            SetStatus(ex.Message, 0);
            return;
        }
        int players = ConnectedPlayers;
        bool running = _srvState != null;
        if (MessageBox.Show(this, $"Mettre {TargetName} à jour vers Valcombe {release.Tag} ?\n\n"
                + $"Le serveur télécharge Java, Fabric et les mods de cette version (installée : {_srvManaged?.Version ?? "inconnue"}), "
                + "les vérifie, puis " + (running ? "redémarre pour les prendre en compte : la partie en cours est enregistrée et reprend après. "
                    + (players > 0 ? $"{players} joueur(s) connecté(s) seront déconnectés une à deux minutes." : "")
                    : "les prend en compte à son prochain démarrage.")
                + "\n\nPensez à mettre à jour le jeu des joueurs (le launcher le fait à leur prochain lancement).",
                "Les Cendres de Valcombe", MessageBoxButton.OKCancel, MessageBoxImage.Question) != MessageBoxResult.OK)
        {
            return;
        }
        _srvUpdating = true;
        SrvRender();
        try
        {
            var result = await managed.UpdateAsync(release, new Progress<(string, double)>(p => SetStatus(p.Item1, p.Item2)), CancellationToken.None);
            SetStatus(!result.Changed ? $"{TargetName} était déjà à jour ({release.Tag})."
                : result.Restarted ? $"{TargetName} mis à jour vers {release.Tag} : il redémarre."
                : $"{TargetName} mis à jour vers {release.Tag}.", result.Changed ? 1 : 0);
            if (result.Restarted)
            {
                _srvStopping = "redemarrage";
                _srvStoppingSawDown = false;
                _srvStoppingSince = DateTime.UtcNow;
            }
        }
        catch (Exception ex) when (ex is AdminException or InvalidOperationException or TimeoutException or IOException)
        {
            SetStatus("Mise à jour impossible : " + ex.Message, 0);
            MessageBox.Show(this, "La mise à jour n'a pas abouti :\n\n" + ex.Message + "\n\nRelancer la mise à jour reprend là où elle s'est arrêtée.",
                "Les Cendres de Valcombe", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        finally
        {
            _srvUpdating = false;
            _srvManaged = null;
            _srvManagedAt = DateTime.MinValue;
            SrvRender();
        }
    }

    // ------------------------------------------------------------------
    // Captures (--capture) : chaque étape du parcours, avec le serveur loué fictif
    // ------------------------------------------------------------------
    private async Task CaptureVpsAsync(string dir)
    {
        try
        {
            Directory.Delete(CaptureVpsDir, true);
        }
        catch (DirectoryNotFoundException)
        {
        }
        Directory.CreateDirectory(CaptureVpsDir);
        var demo = (DemoVpsConnector)VpsConnector;
        async Task Shot(string name, int delay = 250)
        {
            await Task.Delay(delay);
            await SnapshotAsync(dir, name);
        }
        async Task Until(Func<bool> done, string what)
        {
            for (int i = 0; i < 200 && !done(); i++)
            {
                await Task.Delay(50);
            }
            if (!done())
            {
                throw new InvalidOperationException("Parcours du serveur loué : " + what + " n'est pas arrivé.");
            }
        }

        VpsOpenChoice();
        await Shot("Server-vps-choix");

        VpsStartNew(this, new RoutedEventArgs());
        VpsName.Text = "Serveur du vendredi";
        VpsHost.Text = "debian@vendredi.exemple.fr";
        await Shot("Server-vps-1-adresse");
        VpsPrimaryClick(this, new RoutedEventArgs());
        await Until(() => _vpsStep == 1, "l'étape du compte");
        VpsAuthKey.IsChecked = true;
        VpsKeyFile.Text = @"C:\Users\lea\.ssh\id_ed25519";
        VpsPassphrase.Password = "phrase";
        await Shot("Server-vps-2-compte");

        // Le compte d'essai se connecte par mot de passe (la clé ci-dessus n'existe pas sur ce PC).
        VpsAuthPassword.IsChecked = true;
        VpsPassword.Password = "mauvais";
        VpsPrimaryClick(this, new RoutedEventArgs());
        await Until(() => !_vpsBusy && _vpsStep == 2, "la demande d'empreinte");
        await Shot("Server-vps-3-empreinte");
        VpsPrimaryClick(this, new RoutedEventArgs());
        await Until(() => !_vpsBusy && _vpsStep == 1 && VpsMessageBox.Visibility == Visibility.Visible, "le refus du mot de passe");
        await Shot("Server-vps-2-mot-de-passe-refuse");
        VpsPassword.Password = "bon-mot-de-passe";
        VpsPrimaryClick(this, new RoutedEventArgs());
        await Until(() => !_vpsBusy && _vpsStep == 3 && _vps?.SystemInfo != null, "l'examen du système");
        await Shot("Server-vps-4-systeme");

        demo.StepDelay = TimeSpan.FromMilliseconds(900);
        VpsPrimaryClick(this, new RoutedEventArgs());
        await Until(() => _vpsStep == 4, "l'étape d'installation");
        VpsEula.IsChecked = true;
        VpsRenderButtons();
        await Shot("Server-vps-5-installation");
        VpsPrimaryClick(this, new RoutedEventArgs());
        await Until(() => _vpsLog.Count >= 5, "le journal de l'installation");
        await Shot("Server-vps-5-installation-en-cours", 100);
        demo.StepDelay = TimeSpan.FromMilliseconds(120);
        await Until(() => !_vpsBusy && _vpsStep == 5, "l'étape des joueurs");
        VpsOp.Text = "Yoann";
        VpsWhitelist.Text = "Lea, Hugo, Ines\nMalo";
        await Shot("Server-vps-6-joueurs");
        VpsPrimaryClick(this, new RoutedEventArgs());
        await Until(() => !_vpsBusy && _vpsStep == VpsDone, "la fin du parcours");
        await Shot("Server-vps-7-pret");
        var whitelist = await new AdminClient(new DemoConnection(demo.Api!, "apercu")).GetSettingsAsync(CancellationToken.None);
        if (!whitelist.ListeBlanche.Joueurs.Contains("Malo") || whitelist.Operateurs.All(o => o.Nom != "Yoann")
            || !_config.RemoteServers.Any(r => r.Access == RemoteServer.AccessSsh && r.Name == "Serveur du vendredi" && r.Host == "vendredi.exemple.fr"))
        {
            throw new InvalidOperationException("Parcours du serveur loué : opérateur, liste blanche ou fiche manquants à la fin.");
        }
        VpsPrimaryClick(this, new RoutedEventArgs()); // ouvre la fiche

        // Une clé changée : refus net.
        VpsStartNew(this, new RoutedEventArgs());
        VpsName.Text = "Ancien serveur";
        VpsHost.Text = "vps-reinstalle.exemple.fr";
        VpsPrimaryClick(this, new RoutedEventArgs());
        await Until(() => _vpsStep == 1, "le compte");
        VpsPassword.Password = "bon-mot-de-passe";
        VpsPrimaryClick(this, new RoutedEventArgs());
        await Until(() => !_vpsBusy && _vpsStep == 2 && VpsKeyChanged.Visibility == Visibility.Visible, "le refus de la clé changée");
        await Shot("Server-vps-3-empreinte-changee");

        // Un compte sans sudo : l'examen le dit, et demande le mot de passe sudo.
        VpsStartNew(this, new RoutedEventArgs());
        VpsHost.Text = "vendredi.exemple.fr";
        VpsPrimaryClick(this, new RoutedEventArgs());
        await Until(() => _vpsStep == 1, "le compte");
        VpsUser.Text = "sanssudo";
        VpsAuthKey.IsChecked = true;
        VpsAuthPassword.IsChecked = true;
        VpsPassword.Password = "bon-mot-de-passe";
        VpsPrimaryClick(this, new RoutedEventArgs());
        await Until(() => !_vpsBusy && _vpsStep == 3 && _vps?.SystemInfo != null, "l'examen sans sudo");
        await Shot("Server-vps-4-sudo-refuse");
        VpsClose(keepSelection: true);

        // Modifier une fiche de serveur loué : le parcours prérempli, les secrets gardés s'ils restent vides.
        _config.AdminTarget = "apercu-vps";
        RefreshSrvPicker();
        SrvEdit(this, new RoutedEventArgs());
        VpsPrimaryClick(this, new RoutedEventArgs());
        await Until(() => _vpsStep == 1, "le compte de la fiche modifiée");
        await Shot("Server-vps-modifier");
        VpsClose(keepSelection: true);
    }
}
