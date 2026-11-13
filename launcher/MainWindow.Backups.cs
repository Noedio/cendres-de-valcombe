using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using Valcombe.Launcher.Services;
using Valcombe.Launcher.Services.Admin;

namespace Valcombe.Launcher;

/// <summary>
/// Onglet « Sauvegardes » de la page Serveur : les archives du serveur choisi (docs/ADMIN-API.md, « Les
/// sauvegardes »), une sauvegarde à la demande suivie phase par phase, le téléchargement d'une archive sur ce PC
/// (contrôlée à l'arrivée), la restauration avec une confirmation qui dit ce qui sera perdu, son suivi à travers le
/// redémarrage, et les réglages des sauvegardes automatiques.
///
/// La lecture se fait dans l'interrogation de la page (<see cref="SrvPollAsync"/>) : toutes les cinq secondes sur cet
/// onglet, chaque seconde pendant une sauvegarde, et quel que soit l'onglet pendant une restauration (pour dire ce
/// qu'elle a donné dès que le serveur revient). Le serveur de ce PC arrêté montre les archives de son dossier.
/// </summary>
public partial class MainWindow
{
    private AdminBackups? _bk;
    private AdminBackupSettings? _bkSettings;
    private bool _bkStale = true;
    private bool _bkSettingsStale = true;
    /// <summary>Ce serveur ne connaît pas les sauvegardes (API plus ancienne) : dit une fois, sans bloquer la page.</summary>
    private string? _bkUnsupported;
    private string _bkShown = "\0";
    private bool _bkStarting;
    private bool _bkFilling;
    /// <summary>Les champs des réglages ont été changés par l'hôte : ils ne sont plus remplacés par ce que lit la page.</summary>
    private bool _bkDirty;
    private string _bkOutcomeSeen = "";
    /// <summary>La sauvegarde dont la confirmation de restauration est affichée.</summary>
    private AdminBackup? _bkConfirm;
    /// <summary>Restauration avec redémarrage en cours : le serveur, l'archive, le moment de la demande.</summary>
    private (string Target, string Name, DateTime At)? _bkRestoring;
    private readonly Dictionary<string, CancellationTokenSource> _bkDownloads = new();
    private readonly Dictionary<string, (ProgressBar Bar, TextBlock Text)> _bkDownloadRows = new();
    private Action? _bkNoticeAction;
    private List<AdminBackup>? _bkDisk;
    private DateTime _bkDiskReadAt;

    private static readonly int[] BkIntervals = { 15, 30, 60, 120, 180, 360, 720, 1440, 2880, 10080 };

    /// <summary>Nouvelle connexion : tout ce qu'on savait des sauvegardes est relu (une restauration suivie, elle, continue).</summary>
    private void BkReset()
    {
        _bk = null;
        _bkSettings = null;
        _bkStale = true;
        _bkSettingsStale = true;
        _bkUnsupported = null;
        _bkShown = "\0";
        _bkDirty = false;
        _bkConfirm = null;
        _bkDisk = null;
        // La connexion des téléchargements en cours se ferme : ils s'arrêtent proprement (le « .partiel » est effacé)
        // au lieu d'échouer sur une connexion fermée.
        foreach (var running in _bkDownloads.Values)
        {
            running.Cancel();
        }
        if (BkConfirm != null)
        {
            BkConfirm.Visibility = Visibility.Collapsed;
        }
    }

    /// <summary>Ce que l'interrogation doit lire cette fois-ci.</summary>
    private (bool List, bool Settings) BkWanted(bool force)
    {
        bool tab = _srvTab == "sauvegardes" && PageServer.Visibility == Visibility.Visible;
        bool restoring = _bkRestoring is { } r && r.Target == _config.AdminTarget;
        bool list = _bkUnsupported == null && ((tab && (force || _bkStale || _bk?.EnCours != null || _srvTick % 5 == 0)) || restoring);
        return (list, _bkUnsupported == null && tab && _bkSettingsStale);
    }

    /// <summary>Lit les sauvegardes ; une API sans sauvegardes (404) est dite sur l'onglet, sans arrêter la page.</summary>
    private async Task<(AdminBackups? List, AdminBackupSettings? Settings)> BkReadAsync(AdminClient client, bool list, bool settings,
        CancellationToken ct)
    {
        try
        {
            return (list ? await client.GetBackupsAsync(ct) : null, settings ? await client.GetBackupSettingsAsync(ct) : null);
        }
        catch (AdminException ex) when (ex.Status is 404 or 405)
        {
            _bkUnsupported = "Ce serveur ne connaît pas encore les sauvegardes du launcher : mettez-le à jour (Valcombe 0.14 ou plus récent). "
                + "Réponse du serveur : " + ex.Message;
            return (null, null);
        }
    }

    /// <summary>Après une lecture réussie : nouvelles données, fin d'une sauvegarde ou d'une restauration à annoncer.</summary>
    private void BkReceived(AdminBackups? list, AdminBackupSettings? settings)
    {
        if (settings != null)
        {
            _bkSettings = settings;
            _bkSettingsStale = false;
        }
        if (list == null)
        {
            return;
        }
        var before = _bk;
        _bk = list;
        _bkStale = false;
        // Une sauvegarde vient de finir (ou a échoué) : on le dit, une seule fois.
        if (list.Derniere is { } last && list.EnCours == null)
        {
            string id = last.Nom + "|" + last.Fin;
            bool fresh = before?.EnCours != null || (_bkOutcomeSeen.Length > 0 && id != _bkOutcomeSeen) || (!last.Ok && _bkOutcomeSeen != id);
            if (fresh && _bkOutcomeSeen != id)
            {
                BkNotify(last.Message, last.Ok ? "ok" : "erreur");
                if (_srvTab != "sauvegardes")
                {
                    SetStatus(last.Message, 0);
                }
            }
            _bkOutcomeSeen = id;
        }
        // Une restauration suivie : le serveur est revenu et en a rendu compte.
        if (_bkRestoring is { } r && r.Target == _config.AdminTarget)
        {
            var report = list.DerniereRestauration;
            var done = AdminBackupText.ParseDate(report?.FaiteLe);
            if (report != null && report.Nom == r.Name && done != null && done.Value.UtcDateTime >= r.At.AddMinutes(-2))
            {
                // Le bandeau « Dernière restauration » dit la suite (et offre le retour en arrière) : l'avis d'attente s'efface.
                _bkRestoring = null;
                BkNotice.Visibility = Visibility.Collapsed;
                SetStatus(report.Ok ? "Restauration faite : " + BkLabel(report.Nom) + "." : "Restauration manquée : " + report.Message, 0);
            }
            else if (DateTime.UtcNow - r.At > TimeSpan.FromMinutes(10))
            {
                _bkRestoring = null;
                BkNotify("Le serveur n'a pas rendu compte de la restauration en dix minutes : sa console dit ce qui s'est passé.", "erreur");
            }
        }
        if (!_bkDirty)
        {
            BkFillSettings();
        }
    }

    // ------------------------------------------------------------------
    // Affichage
    // ------------------------------------------------------------------
    /// <summary>« la sauvegarde d'aujourd'hui à 15:12 » : une archive, en mots, à partir de son nom.</summary>
    private string BkLabel(string name)
    {
        var b = _bk?.Sauvegardes.FirstOrDefault(x => x.Nom == name);
        var when = b?.When ?? ParseBackupName(name)?.When;
        if (when == null)
        {
            return "« " + name + " »";
        }
        string text = AdminBackupText.When(when.Value);
        return "la sauvegarde " + (text.StartsWith("aujourd'hui", StringComparison.Ordinal) || text.StartsWith("hier", StringComparison.Ordinal)
            ? "d'" + text : "du " + text);
    }

    /// <summary>Met l'onglet à jour (appelé à chaque rendu de la page ; la liste n'est refaite que si elle a changé).</summary>
    private void BkRender()
    {
        if (BkList == null)
        {
            return;
        }
        var b = _srvError == null && _srvClient != null ? _bk : null;
        bool api = b != null;
        bool localStopped = IsLocalTarget && _srvClient == null;
        var rows = api ? b!.Sauvegardes : localStopped ? BkDiskBackups() : null;
        bool busy = api && (b!.EnCours != null || _bkStarting);
        bool restoring = _bkRestoring is { } r && r.Target == _config.AdminTarget;

        // Résumé
        if (api)
        {
            long total = b!.OctetsTotal;
            string summary = b.Nombre == 0 ? "Aucune archive pour l'instant."
                : $"{b.Nombre} archive{(b.Nombre > 1 ? "s" : "")}, {AdminBackupText.Size(total)} en tout";
            if (b.EspaceLibre >= 0)
            {
                summary += $" · {AdminBackupText.Size(b.EspaceLibre)} libres sur le disque du serveur";
            }
            BkSummary.Text = summary + ".";
            BkSummary.Foreground = Res("Ash");
        }
        else
        {
            BkSummary.Text = _bkUnsupported
                ?? (localStopped ? (LocalState == LocalServer.State.Stopped
                        ? "Le serveur de ce PC est arrêté : voici les archives de son dossier. Démarrez-le pour sauvegarder ou restaurer."
                        : "Le serveur de ce PC démarre : les sauvegardes s'affichent dès que son API répond.")
                    : _srvError != null ? "Le serveur ne répond pas : " + _srvError.Message
                    : "Les sauvegardes s'affichent une fois le serveur joint.");
            BkSummary.Foreground = _bkUnsupported != null ? Res("Gold") : Res("Ash");
        }
        BkNowButton.IsEnabled = api && !busy && !restoring && _srvStopping.Length == 0;
        BkNowText.Text = busy ? "Sauvegarde en cours…" : "Sauvegarder maintenant";
        SrvBackupFolderButton.Visibility = IsLocalTarget ? Visibility.Visible : Visibility.Collapsed;

        // Sauvegarde en cours
        var job = api ? b!.EnCours : null;
        BkJob.Visibility = job != null || (_bkStarting && api) ? Visibility.Visible : Visibility.Collapsed;
        if (job != null)
        {
            BkJobTitle.Text = job.PhaseTexte + "…";
            BkJobPercent.Text = job.Phase == "copie" ? job.Pourcent + " %" : "";
            BkJobBar.IsIndeterminate = false;
            BkJobBar.Value = job.Phase switch { "copie" => job.Pourcent, "rotation" => 100, _ => 2 };
            var started = AdminBackupText.ParseDate(job.Debut);
            BkJobDetail.Text = (job.FichiersTotal > 0
                    ? $"{job.Fichiers:N0} / {job.FichiersTotal:N0} fichiers · {AdminBackupText.Size(job.Octets)} sur {AdminBackupText.Size(job.OctetsTotal)}"
                    : "Le jeu continue pendant la copie.")
                + (started != null ? " · depuis " + AdminBackupText.Span(DateTimeOffset.UtcNow - started.Value) : "")
                + (job.Par.Length == 0 ? "" : job.Par.StartsWith("API d'administration", StringComparison.Ordinal)
                    ? " · demandée depuis le launcher" : " · demandée par " + job.Par);
        }
        else if (_bkStarting)
        {
            BkJobTitle.Text = "Lancement de la sauvegarde…";
            BkJobPercent.Text = "";
            BkJobBar.Value = 0;
            BkJobDetail.Text = "Le serveur enregistre les mondes, puis les copie dans une archive sans arrêter le jeu.";
        }

        // Restauration prévue, restauration suivie, dernière restauration
        var plan = api ? b!.RestaurationPrevue : null;
        BkPlanned.Visibility = plan != null || restoring ? Visibility.Visible : Visibility.Collapsed;
        if (restoring)
        {
            BkPlannedTitle.Text = "Restauration en cours : " + BkLabel(_bkRestoring!.Value.Name);
            BkPlannedText.Text = "Le serveur s'est arrêté proprement. À son redémarrage, il sauvegarde l'état actuel, puis met l'archive en place. "
                + "Cette page dira ce que la restauration a donné dès que le serveur répondra.";
            BkPlannedRestart.Visibility = Visibility.Collapsed;
            ((FrameworkElement)BkPlannedRestart.Parent).Visibility = Visibility.Collapsed;
        }
        else if (plan != null)
        {
            var asked = AdminBackupText.ParseDate(plan.DemandeLe);
            BkPlannedTitle.Text = "Restauration prévue au prochain démarrage : " + BkLabel(plan.Nom);
            BkPlannedText.Text = "Demandée " + (asked != null ? AdminBackupText.When(asked.Value) : "")
                + (plan.Par.Length > 0 ? " par " + plan.Par : "")
                + ". Rien n'a encore changé : le serveur la met en place à son prochain démarrage, après avoir sauvegardé l'état actuel.";
            BkPlannedRestart.Visibility = Visibility.Visible;
            ((FrameworkElement)BkPlannedRestart.Parent).Visibility = Visibility.Visible;
        }
        var report = api ? b!.DerniereRestauration : null;
        var reportAt = AdminBackupText.ParseDate(report?.FaiteLe);
        bool showReport = report != null && !restoring && reportAt != null && DateTimeOffset.UtcNow - reportAt.Value < TimeSpan.FromDays(7);
        BkRestored.Visibility = showReport ? Visibility.Visible : Visibility.Collapsed;
        if (showReport)
        {
            BkRestoredIcon.Text = report!.Ok ? "" : "";
            BkRestoredIcon.Foreground = report.Ok ? Res("Good") : Res("Bad");
            BkRestoredTitle.Text = (report.Ok ? "Dernière restauration, " : "Restauration manquée, ") + AdminBackupText.When(reportAt!.Value) + " : "
                + BkLabel(report.Nom);
            BkRestoredText.Text = report.Message;
            bool canUndo = report.Ok && report.Securite != null && b!.Sauvegardes.Any(x => x.Nom == report.Securite && x.Lisible)
                && b.RestaurationPossible && plan == null;
            BkUndoButton.Visibility = canUndo ? Visibility.Visible : Visibility.Collapsed;
            BkUndoButton.IsEnabled = canUndo && !busy;
        }

        // Réglages
        BkNext.Text = !api ? "" : b!.Reglages.Actives && b.ProchaineAuto is { } next && AdminBackupText.ParseDate(next) is { } at
            ? (b.Reportee ? "Prochaine : après la partie en cours." : at <= DateTimeOffset.UtcNow ? "Prochaine : dans un instant."
                : $"Prochaine dans {AdminBackupText.Span(at - DateTimeOffset.UtcNow)} ({AdminBackupText.When(at)}).")
            : b.Reglages.Actives ? "" : "Désactivées : seulement à la demande.";
        bool settings = api && _bkSettings != null;
        // Sans le serveur, pas de réglages à montrer : des champs vides feraient croire à des sauvegardes éteintes.
        BkSettingsFields.Visibility = settings ? Visibility.Visible : Visibility.Collapsed;
        foreach (var control in new Control[] { BkIntervalBox, BkKeepBox, BkAtStopBox, BkDuringGameBox })
        {
            control.IsEnabled = BkAutoBox.IsChecked == true;
        }
        BkSaveButton.IsEnabled = settings && _bkDirty;
        BkResetButton.IsEnabled = settings && _bkDirty;
        BkSettingsNote.Visibility = settings && !_bkSettings!.AutomatiquesPossibles || !settings ? Visibility.Visible : Visibility.Collapsed;
        BkSettingsNote.Text = !settings
            ? (localStopped ? "Elles se règlent ici quand le serveur tourne : fréquence, nombre d'archives gardées, sauvegarde à l'arrêt."
                : api ? "Lecture des réglages…" : "Elles se règlent ici une fois le serveur joint.")
            : "Partie solo : seules les sauvegardes à la demande se font. Ces réglages serviront sur un serveur dédié.";

        // Liste
        string signature = (rows == null ? "rien:" + _bkUnsupported : string.Join("|", rows.Select(x => $"{x.Nom}/{x.Octets}/{x.Lisible}")))
            + $"#{api}/{busy}/{restoring}/{plan?.Nom}/{b?.RestaurationPossible}/{_bkDownloads.Count}/{DateTime.Now:yyyyMMddHH}";
        if (signature != _bkShown)
        {
            _bkShown = signature;
            BkBuildList(rows, api, busy || restoring, plan?.Nom, b?.RestaurationPossible ?? false);
        }
    }

    private void BkBuildList(List<AdminBackup>? rows, bool api, bool busy, string? planned, bool canRestore)
    {
        BkList.Children.Clear();
        _bkDownloadRows.Clear();
        if (rows == null)
        {
            return;
        }
        if (rows.Count == 0)
        {
            BkList.Children.Add(new TextBlock
            {
                Style = (Style)FindResource("Hint"),
                Text = api ? "Aucune sauvegarde sur ce serveur pour l'instant. Faites la première maintenant : elle prend quelques secondes, sans arrêter le jeu."
                    : "Aucune archive dans le dossier des sauvegardes de ce PC.",
            });
            return;
        }
        for (int i = 0; i < rows.Count; i++)
        {
            BkList.Children.Add(BkRow(rows[i], api, busy, planned, canRestore, last: i == rows.Count - 1));
        }
    }

    private static string KindGlyph(string kind) => kind switch
    {
        "auto" => "",
        "arret" => "",
        "manuelle" => "",
        "avant-restauration" => "",
        _ => "",
    };

    /// <summary>Une archive : quand, quelle sorte, sa taille et ce qu'elle contient, et ce qu'on peut en faire.</summary>
    private FrameworkElement BkRow(AdminBackup x, bool api, bool busy, string? planned, bool canRestore, bool last)
    {
        string name = x.Nom;
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var icon = new TextBlock
        {
            Text = x.Lisible ? KindGlyph(x.Sorte) : "", FontFamily = (FontFamily)FindResource("Icons"), FontSize = 16,
            Foreground = !x.Lisible ? Res("Bad") : x.Sorte == "manuelle" ? Res("Gold") : Res("ParchDark"),
            Margin = new Thickness(2, 3, 14, 0), VerticalAlignment = VerticalAlignment.Top, Width = 18,
        };
        grid.Children.Add(icon);

        var text = new StackPanel();
        var title = new TextBlock { FontSize = 14, Foreground = Res("Ink"), TextTrimming = TextTrimming.CharacterEllipsis };
        title.Inlines.Add(new Run(x.When is { } w ? Capitalize(AdminBackupText.When(w)) : name));
        title.Inlines.Add(new Run("   " + x.SorteTexte) { FontSize = 12, Foreground = x.Sorte == "manuelle" ? Res("Gold") : Res("Ash") });
        text.Children.Add(title);
        string detail;
        Brush detailBrush = Res("Ash");
        if (!x.Lisible)
        {
            detail = AdminBackupText.Size(x.Octets) + " · " + (x.Erreur ?? "Archive illisible.");
            detailBrush = Res("Bad");
        }
        else
        {
            var parts = new List<string> { AdminBackupText.Size(x.Octets) };
            if (x.Mondes.Count > 0)
            {
                parts.Add($"{x.Mondes.Count} monde{(x.Mondes.Count > 1 ? "s" : "")}");
            }
            if (x.Sorte == "manuelle" && !string.IsNullOrEmpty(x.Par))
            {
                // « API d'administration (127.0.0.1) » : c'est ce launcher (ou un autre outil) qui l'a demandée.
                parts.Add(x.Par.StartsWith("API d'administration", StringComparison.Ordinal) ? "depuis le launcher" : "par " + x.Par);
            }
            if (!x.Rotation && api)
            {
                parts.Add("gardée");
            }
            detail = string.Join(" · ", parts);
        }
        if (planned == name)
        {
            detail = "Restauration prévue au prochain démarrage · " + detail;
            detailBrush = Res("Gold");
        }
        text.Children.Add(new TextBlock
        {
            Text = detail, Style = (Style)FindResource("Hint"), Margin = new Thickness(0, 1, 0, 0), Foreground = detailBrush,
            TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis,
        });
        if (_bkDownloads.ContainsKey(name))
        {
            var bar = new ProgressBar { Maximum = 1, Height = 4, Margin = new Thickness(0, 6, 0, 2) };
            var info = new TextBlock { Style = (Style)FindResource("Hint"), Margin = new Thickness(0), Text = "Téléchargement…" };
            text.Children.Add(bar);
            text.Children.Add(info);
            _bkDownloadRows[name] = (bar, info);
        }
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        grid.ToolTip = BkTooltip(x);

        var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(10, 0, 0, 0) };
        if (api)
        {
            if (_bkDownloads.TryGetValue(name, out var running))
            {
                var stop = BkIconButton("", "Arrêter le téléchargement");
                stop.Click += (_, _) => running.Cancel();
                actions.Children.Add(stop);
            }
            else
            {
                var download = BkIconButton("", "Télécharger cette archive sur ce PC");
                download.Click += async (_, _) => await BkDownloadAsync(x);
                actions.Children.Add(download);
            }
            var restore = SmallButton("Restaurer", !x.Lisible ? "Archive illisible : elle ne peut pas être restaurée."
                : !canRestore ? "La restauration ne se fait que sur un serveur dédié."
                : "Remettre le serveur dans l'état de cette sauvegarde (une confirmation suit)");
            restore.IsEnabled = x.Lisible && canRestore && !busy;
            ToolTipService.SetShowOnDisabled(restore, true);
            restore.Click += (_, _) => BkAskRestore(x, undo: false);
            actions.Children.Add(restore);
            var delete = BkIconButton("", planned == name ? "Sa restauration est prévue : annulez-la d'abord." : "Effacer cette sauvegarde");
            delete.Foreground = Res("Bad");
            delete.IsEnabled = planned != name && !busy;
            ToolTipService.SetShowOnDisabled(delete, true);
            delete.Click += async (_, _) => await BkDeleteAsync(x);
            actions.Children.Add(delete);
        }
        else
        {
            // Serveur de ce PC arrêté : l'archive se montre dans l'Explorateur ; restaurer demande le serveur en marche.
            var show = SmallButton("Afficher", "Montrer cette archive dans l'Explorateur");
            show.Click += (_, _) => Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{Path.Combine(LocalServer.Dir, "backups", name)}\""));
            actions.Children.Add(show);
        }
        Grid.SetColumn(actions, 2);
        grid.Children.Add(actions);
        return new Border
        {
            Child = grid, Padding = new Thickness(0, 9, 0, 9),
            BorderBrush = new SolidColorBrush(Color.FromArgb(0x26, 0xE2, 0xBD, 0x63)), BorderThickness = new Thickness(0, 0, 0, last ? 0 : 1),
        };
    }

    private static string Capitalize(string text) => text.Length == 0 ? text : char.ToUpper(text[0]) + text[1..];

    private Button BkIconButton(string glyph, string tip)
    {
        var b = SmallButton("", tip);
        b.Content = new TextBlock { Text = glyph, FontFamily = (FontFamily)FindResource("Icons"), FontSize = 12, Margin = new Thickness(0, 2, 0, 1) };
        b.Padding = new Thickness(9, 4, 9, 4);
        ToolTipService.SetShowOnDisabled(b, true);
        return b;
    }

    private static string BkTooltip(AdminBackup x)
    {
        var lines = new List<string> { x.Nom };
        if (x.Lisible)
        {
            if (x.Fichiers > 0)
            {
                lines.Add($"{x.Fichiers:N0} fichiers, {AdminBackupText.Size(x.OctetsDecompresses)} une fois déballés");
            }
            if (!string.IsNullOrEmpty(x.VersionMod))
            {
                lines.Add("Valcombe " + x.VersionMod + (string.IsNullOrEmpty(x.Par) ? "" : " · " + x.Par));
            }
            if (x.Mondes.Count > 0)
            {
                lines.Add("Mondes : " + string.Join(", ", x.Mondes));
            }
            lines.Add(x.Rotation ? "Effacée par la rotation quand elle devient trop ancienne." : "Jamais effacée par la rotation.");
        }
        return string.Join("\n", lines);
    }

    /// <summary>Le serveur de ce PC arrêté : les archives de son dossier backups/, relues toutes les cinq secondes au plus.</summary>
    private List<AdminBackup> BkDiskBackups()
    {
        if (_captureDir != null)
        {
            return new DemoAdminApi().DiskListing();
        }
        if (_bkDisk != null && DateTime.UtcNow - _bkDiskReadAt < TimeSpan.FromSeconds(5))
        {
            return _bkDisk;
        }
        var list = new List<AdminBackup>();
        try
        {
            string dir = Path.Combine(LocalServer.Dir, "backups");
            if (Directory.Exists(dir))
            {
                foreach (var file in new DirectoryInfo(dir).EnumerateFiles("valcombe-*.zip"))
                {
                    if (ParseBackupName(file.Name) is { } parsed)
                    {
                        list.Add(new AdminBackup
                        {
                            Nom = file.Name, Sorte = parsed.Kind, Date = parsed.When.ToString("O"), Octets = file.Length,
                            Rotation = parsed.Kind != "manuelle",
                        });
                    }
                }
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Dossier illisible : la liste reste vide, le bouton du dossier permet d'aller voir.
        }
        _bkDisk = list.OrderByDescending(x => x.Date, StringComparer.Ordinal).ToList();
        _bkDiskReadAt = DateTime.UtcNow;
        return _bkDisk;
    }

    /// <summary>« valcombe-2026-11-13_15-12-44-auto.zip » : le moment (heure de ce PC, celle du serveur local) et la sorte.</summary>
    private static (DateTimeOffset When, string Kind)? ParseBackupName(string name)
    {
        var m = System.Text.RegularExpressions.Regex.Match(name,
            @"^valcombe-(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})-(auto|arret|manuelle|avant-restauration)(-\d+)?\.zip$");
        if (!m.Success)
        {
            return null;
        }
        int N(int g) => int.Parse(m.Groups[g].Value);
        try
        {
            var local = new DateTime(N(1), N(2), N(3), N(4), N(5), N(6), DateTimeKind.Local);
            return (new DateTimeOffset(local), m.Groups[7].Value);
        }
        catch (ArgumentOutOfRangeException)
        {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Avis
    // ------------------------------------------------------------------
    /// <summary>Un avis en haut de l'onglet : <paramref name="kind"/> vaut « ok », « erreur » ou « info ».</summary>
    private void BkNotify(string text, string kind, string? action = null, Action? onAction = null)
    {
        BkNoticeText.Text = text;
        (BkNoticeIcon.Text, BkNoticeIcon.Foreground) = kind switch
        {
            "ok" => ("", Res("Good")),
            "erreur" => ("", Res("Bad")),
            _ => ("", Res("Gold")),
        };
        BkNoticeAction.Content = action;
        BkNoticeAction.Visibility = action != null ? Visibility.Visible : Visibility.Collapsed;
        _bkNoticeAction = onAction;
        BkNotice.Visibility = Visibility.Visible;
    }

    private void BkHideNotice(object sender, RoutedEventArgs e) => BkNotice.Visibility = Visibility.Collapsed;

    private void BkNoticeActionClick(object sender, RoutedEventArgs e) => _bkNoticeAction?.Invoke();

    private void BkOpenFolder(object sender, RoutedEventArgs e)
    {
        string dir = Path.Combine(LocalServer.Dir, "backups");
        Directory.CreateDirectory(dir);
        Process.Start(new ProcessStartInfo("explorer.exe", dir));
    }

    // ------------------------------------------------------------------
    // Sauvegarder maintenant
    // ------------------------------------------------------------------
    private async void BkBackupNow(object sender, RoutedEventArgs e)
    {
        var client = _srvClient;
        if (client == null || _bk == null || _bkStarting)
        {
            return;
        }
        int gen = _srvGen;
        _bkStarting = true;
        BkNotice.Visibility = Visibility.Collapsed;
        BkRender();
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(30));
            var started = await client.StartBackupAsync(cts.Token);
            if (gen == _srvGen && _bk != null)
            {
                _bk.EnCours = started.EnCours ?? new AdminBackupJob { Nom = started.Nom, Phase = "attente" };
                _bkOutcomeSeen = "";
            }
        }
        catch (AdminException ex) when (gen == _srvGen)
        {
            BkNotify("Sauvegarde refusée : " + ex.Message, "erreur");
        }
        catch (OperationCanceledException) when (gen == _srvGen)
        {
            BkNotify("Le serveur n'a pas répondu à temps : la sauvegarde est peut-être partie, la liste le dira.", "erreur");
        }
        catch (Exception ex)
        {
            // Connexion remplacée (et fermée) pendant la requête, ou imprévu : jamais d'exception hors d'un async void,
            // elle fermerait le launcher.
            if (gen == _srvGen)
            {
                BkNotify("Sauvegarde impossible : " + ex.Message, "erreur");
            }
        }
        finally
        {
            _bkStarting = false;
            _bkStale = true;
            BkRender();
        }
    }

    // ------------------------------------------------------------------
    // Télécharger
    // ------------------------------------------------------------------
    private async Task BkDownloadAsync(AdminBackup x)
    {
        var client = _srvClient;
        if (client == null || _bkDownloads.ContainsKey(x.Nom))
        {
            return;
        }
        string downloads = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
        var dialog = new Microsoft.Win32.SaveFileDialog
        {
            Title = "Enregistrer la sauvegarde",
            FileName = x.Nom,
            DefaultExt = ".zip",
            Filter = "Archive de sauvegarde (*.zip)|*.zip",
            InitialDirectory = Directory.Exists(downloads) ? downloads : Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
            OverwritePrompt = true,
            AddExtension = true,
        };
        if (dialog.ShowDialog(this) != true)
        {
            return;
        }
        await BkDownloadToAsync(client, x, dialog.FileName);
    }

    /// <summary>
    /// Télécharge l'archive dans un fichier « .partiel » à côté de la cible, la contrôle, puis lui donne son nom :
    /// une archive coupée en route ne prend jamais la place d'un fichier.
    /// </summary>
    private async Task<bool> BkDownloadToAsync(AdminClient client, AdminBackup x, string target)
    {
        string name = x.Nom;
        string partial = target + ".partiel";
        var cts = new CancellationTokenSource();
        _bkDownloads[name] = cts;
        _bkShown = "\0";
        BkRender();
        var progress = new Progress<(long Done, long? Total)>(p =>
        {
            long total = p.Total ?? x.Octets;
            double f = total > 0 ? Math.Clamp((double)p.Done / total, 0, 1) : 0;
            string text = $"{AdminBackupText.Size(p.Done)} sur {AdminBackupText.Size(total)}";
            if (_bkDownloadRows.TryGetValue(name, out var row))
            {
                row.Bar.Value = f;
                row.Text.Text = "Téléchargement : " + text;
            }
            SetStatus("Téléchargement de " + BkLabel(name) + " : " + text, f);
        });
        bool ok = false;
        try
        {
            await using (var file = new FileStream(partial, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16, useAsync: true))
            {
                await client.DownloadBackupAsync(name, file, progress, cts.Token);
            }
            var check = await Task.Run(() => BackupArchive.Verify(partial));
            File.Move(partial, target, overwrite: true);
            long size = new FileInfo(target).Length;
            ok = check.Ok;
            string where = Path.GetFileName(target) + " (" + AdminBackupText.Size(size) + "), dans " + Path.GetDirectoryName(target);
            BkNotify(check.Ok ? $"Archive enregistrée : {where}. {check.Message} Gardez-la hors du serveur : elle vous protège d'une panne de celui-ci."
                    : $"Archive enregistrée ({where}), mais elle ne passe pas le contrôle : {check.Message}",
                check.Ok ? "ok" : "erreur", "Afficher", () => Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{target}\"")));
            SetStatus(check.Ok ? "Archive téléchargée et vérifiée." : "Archive téléchargée, mais abîmée.", check.Ok ? 1 : 0);
        }
        catch (Exception ex)
        {
            // Tout est attrapé : appelé depuis un clic (async void), une exception qui filerait fermerait le launcher.
            TryDeleteFile(partial);
            bool cancelled = ex is OperationCanceledException;
            BkNotify(cancelled ? "Téléchargement arrêté : rien n'a été gardé." : "Téléchargement impossible : " + ex.Message, cancelled ? "info" : "erreur");
            SetStatus(cancelled ? "Téléchargement arrêté." : "Téléchargement impossible.", 0);
        }
        finally
        {
            _bkDownloads.Remove(name);
            cts.Dispose();
            _bkShown = "\0";
            BkRender();
        }
        return ok;
    }

    private static void TryDeleteFile(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Un fichier encore ouvert par l'antivirus : il restera sous son nom « .partiel », sans tromper personne.
        }
    }

    // ------------------------------------------------------------------
    // Restaurer
    // ------------------------------------------------------------------
    /// <summary>Montre la confirmation : ce qui sera perdu, ce qui est gardé, ce qui ne change pas, et le redémarrage.</summary>
    private void BkAskRestore(AdminBackup x, bool undo)
    {
        _bkConfirm = x;
        BkNotice.Visibility = Visibility.Collapsed;
        var when = x.When;
        BkConfirmTitle.Text = undo ? "Revenir à l'état d'avant la dernière restauration ?"
            : "Restaurer " + BkLabel(x.Nom) + " ?";
        BkConfirmPoints.Children.Clear();
        string since = when != null ? $"depuis {AdminBackupText.When(when.Value)} (il y a {AdminBackupText.Span(DateTimeOffset.UtcNow - when.Value)})" : "depuis cette sauvegarde";
        int players = ConnectedPlayers;
        BkPoint("", Res("Bad"), "Perdu",
            $"tout ce qui a changé dans les mondes et les créations {since} : parties jouées, cartes, structures et travaux de l'Atelier reviennent à l'état de l'archive.");
        BkPoint("", Res("Good"), "Gardé à part",
            "avant de restaurer, le serveur sauvegarde l'état actuel (archive « Avant restauration ») : vous pourrez y revenir d'un clic.");
        string relaunch = IsLocalTarget ? "le launcher le relance lui-même"
            : SelectedRemote?.KeyFromServer == true ? "le service valcombe le relance tout seul"
            : "il revient tout seul s'il a été lancé par demarrer.sh, demarrer.bat ou le service valcombe ; sinon, relancez-le vous-même";
        BkPoint("", Res("Gold"), "Redémarrage",
            $"le serveur s'arrête proprement, met l'archive en place, puis redémarre ({relaunch}). "
            + (players > 0 ? $"{players} joueur{(players > 1 ? "s" : "")} connecté{(players > 1 ? "s" : "")} {(players > 1 ? "seront déconnectés" : "sera déconnecté")} une à deux minutes."
                : "Personne n'est connecté en ce moment."));
        BkPoint("", Res("Ash"), "Inchangés",
            "la liste blanche, les opérateurs, server.properties, la clé d'administration et ces réglages de sauvegarde.");
        BkConfirmRestart.IsEnabled = true;
        BkConfirmLater.IsEnabled = true;
        BkConfirm.Visibility = Visibility.Visible;
        SrvBackupsPanel.ScrollToTop();
    }

    private void BkPoint(string glyph, Brush color, string label, string text)
    {
        var row = new DockPanel { Margin = new Thickness(0, 3, 0, 3) };
        var icon = new TextBlock
        {
            Text = glyph, FontFamily = (FontFamily)FindResource("Icons"), FontSize = 12, Foreground = color,
            Margin = new Thickness(0, 3, 10, 0), VerticalAlignment = VerticalAlignment.Top, Width = 14,
        };
        DockPanel.SetDock(icon, Dock.Left);
        row.Children.Add(icon);
        var body = new TextBlock { TextWrapping = TextWrapping.Wrap, FontSize = 13, LineHeight = 19, Foreground = Res("Parch") };
        body.Inlines.Add(new Run(label + " : ") { FontWeight = FontWeights.SemiBold, Foreground = Res("Ink") });
        body.Inlines.Add(new Run(text));
        row.Children.Add(body);
        BkConfirmPoints.Children.Add(row);
    }

    private void BkCancelConfirm(object sender, RoutedEventArgs e)
    {
        _bkConfirm = null;
        BkConfirm.Visibility = Visibility.Collapsed;
    }

    private async void BkConfirmRestore(object sender, RoutedEventArgs e)
    {
        var x = _bkConfirm;
        var client = _srvClient;
        if (x == null || client == null)
        {
            return;
        }
        bool restart = (sender as FrameworkElement)?.Tag as string == "redemarrer";
        BkConfirmRestart.IsEnabled = false;
        BkConfirmLater.IsEnabled = false;
        int gen = _srvGen;
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(40));
            var r = await client.RestoreAsync(x.Nom, restart, cts.Token);
            if (gen != _srvGen)
            {
                return;
            }
            _bkConfirm = null;
            BkConfirm.Visibility = Visibility.Collapsed;
            if (r.Redemarrage)
            {
                BkFollowRestore(x.Nom, DateTime.UtcNow);
                BkNotify("Restauration lancée : le serveur s'arrête proprement, met " + BkLabel(x.Nom) + " en place, puis redémarre. "
                    + "Cette page dira ce qu'elle a donné.", "info");
            }
            else
            {
                BkNotify(r.Message, "ok");
            }
        }
        catch (AdminException ex) when (gen == _srvGen)
        {
            BkConfirmRestart.IsEnabled = true;
            BkConfirmLater.IsEnabled = true;
            BkNotify("Restauration refusée : " + ex.Message, "erreur");
        }
        catch (OperationCanceledException) when (gen == _srvGen)
        {
            BkConfirmRestart.IsEnabled = true;
            BkConfirmLater.IsEnabled = true;
            BkNotify("Le serveur n'a pas répondu à temps : la restauration est peut-être prévue, la liste le dira.", "erreur");
        }
        catch (Exception ex)
        {
            // Connexion remplacée pendant la requête, ou imprévu : rien ne sort d'un async void.
            if (gen == _srvGen)
            {
                BkConfirmRestart.IsEnabled = true;
                BkConfirmLater.IsEnabled = true;
                BkNotify("Restauration impossible : " + ex.Message, "erreur");
            }
        }
        _bkStale = true;
        BkRender();
    }

    /// <summary>Le serveur redémarre pour restaurer : la page le suit, et dira ce qu'a donné la restauration.</summary>
    private void BkFollowRestore(string name, DateTime askedAt)
    {
        _bkRestoring = (_config.AdminTarget, name, askedAt);
        if (!IsLocalTarget)
        {
            _srvStopping = "redemarrage";
            _srvStoppingSawDown = false;
            _srvStoppingSince = DateTime.UtcNow;
            _srvQuietUntil = DateTime.UtcNow.AddSeconds(2);
        }
        SetStatus("Restauration : le serveur redémarre…", 0);
    }

    private async void BkRestartForRestore(object sender, RoutedEventArgs e)
    {
        var plan = _bk?.RestaurationPrevue;
        var client = _srvClient;
        if (plan == null || client == null)
        {
            return;
        }
        int players = ConnectedPlayers;
        if (players > 0 && MessageBox.Show(this, $"Redémarrer {TargetName} pour restaurer {BkLabel(plan.Nom)} ?\n\n{players} joueur(s) sont connectés : "
                + "ils seront déconnectés une à deux minutes.", "Les Cendres de Valcombe", MessageBoxButton.OKCancel, MessageBoxImage.Warning) != MessageBoxResult.OK)
        {
            return;
        }
        try
        {
            var r = await client.RestartAsync(CancellationToken.None);
            BkFollowRestore(plan.Nom, (AdminBackupText.ParseDate(plan.DemandeLe)?.UtcDateTime ?? DateTime.UtcNow));
            BkNotify(r.Message.Length > 0 ? r.Message : "Redémarrage demandé.", "info");
        }
        catch (Exception ex)
        {
            BkNotify((ex is AdminException ? "Redémarrage refusé : " : "Redémarrage impossible : ") + ex.Message, "erreur");
        }
        BkRender();
    }

    private async void BkCancelRestore(object sender, RoutedEventArgs e)
    {
        var client = _srvClient;
        if (client == null)
        {
            return;
        }
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20));
            var r = await client.CancelRestoreAsync(cts.Token);
            if (_bk != null)
            {
                _bk.RestaurationPrevue = null;
            }
            BkNotify(r.Message, "ok");
        }
        catch (Exception ex)
        {
            BkNotify("Annulation impossible : " + ex.Message, "erreur");
        }
        _bkStale = true;
        BkRender();
    }

    private void BkUndoRestore(object sender, RoutedEventArgs e)
    {
        var safety = _bk?.DerniereRestauration?.Securite;
        var x = safety == null ? null : _bk!.Sauvegardes.FirstOrDefault(b => b.Nom == safety);
        if (x != null)
        {
            BkAskRestore(x, undo: true);
        }
    }

    // ------------------------------------------------------------------
    // Effacer
    // ------------------------------------------------------------------
    private async Task BkDeleteAsync(AdminBackup x)
    {
        var client = _srvClient;
        if (client == null)
        {
            return;
        }
        string extra = x.Sorte == "manuelle" ? "\n\nC'est une sauvegarde manuelle : la rotation ne l'aurait jamais effacée." : "";
        if (MessageBox.Show(this, $"Effacer définitivement {BkLabel(x.Nom)} ({AdminBackupText.Size(x.Octets)}) ?\n\nElle est retirée du serveur et ne pourra pas être récupérée.{extra}",
                "Les Cendres de Valcombe", MessageBoxButton.OKCancel, MessageBoxImage.Warning) != MessageBoxResult.OK)
        {
            return;
        }
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20));
            var r = await client.DeleteBackupAsync(x.Nom, cts.Token);
            _bk?.Sauvegardes.RemoveAll(b => b.Nom == x.Nom);
            BkNotify(r.Message, "ok");
        }
        catch (Exception ex)
        {
            BkNotify("Effacement impossible : " + ex.Message, "erreur");
        }
        _bkStale = true;
        BkRender();
    }

    // ------------------------------------------------------------------
    // Réglages des sauvegardes automatiques
    // ------------------------------------------------------------------
    /// <summary>Remplit les champs avec les réglages du serveur (sauf si l'hôte est en train de les changer).</summary>
    private void BkFillSettings()
    {
        var values = _bkSettings?.Reglages ?? _bk?.Reglages;
        _bkFilling = true;
        try
        {
            BkAutoBox.IsChecked = values?.Actives ?? false;
            BkAtStopBox.IsChecked = values?.ALArret ?? false;
            BkDuringGameBox.IsChecked = values?.PendantPartie ?? false;
            BkKeepBox.Text = values != null ? values.Garder.ToString(Fr) : "";
            var (min, max) = _bkSettings?.IntervalBounds ?? (15, 10080);
            var choices = BkIntervals.Where(m => m >= min && m <= max).ToList();
            if (values != null && !choices.Contains(values.IntervalleMinutes))
            {
                choices.Add(values.IntervalleMinutes);
                choices.Sort();
            }
            BkIntervalBox.Items.Clear();
            int defaultInterval = _bkSettings?.ParDefaut.IntervalleMinutes ?? 360;
            foreach (int m in choices)
            {
                var item = new ComboBoxItem
                {
                    Content = Capitalize(AdminBackupText.Interval(m)) + (m == defaultInterval ? " (par défaut)" : ""), Tag = m,
                };
                BkIntervalBox.Items.Add(item);
                if (values?.IntervalleMinutes == m)
                {
                    BkIntervalBox.SelectedItem = item;
                }
            }
            var (keepMin, keepMax) = _bkSettings?.KeepBounds ?? (1, 500);
            BkKeepBox.ToolTip = $"De {keepMin} à {keepMax}";
        }
        finally
        {
            _bkFilling = false;
        }
    }

    private void BkSettingsEdited(object sender, RoutedEventArgs e)
    {
        if (_bkFilling || BkSaveButton == null)
        {
            return;
        }
        _bkDirty = true;
        BkSettingsResult.Visibility = Visibility.Collapsed;
        BkRender();
    }

    private void BkResetSettings(object sender, RoutedEventArgs e)
    {
        _bkDirty = false;
        BkFillSettings();
        BkSettingsResult.Visibility = Visibility.Collapsed;
        BkRender();
    }

    private async void BkSaveSettings(object sender, RoutedEventArgs e)
    {
        var client = _srvClient;
        var current = _bkSettings?.Reglages;
        if (client == null || current == null)
        {
            return;
        }
        var (keepMin, keepMax) = _bkSettings!.KeepBounds;
        if (!int.TryParse(BkKeepBox.Text.Trim(), out int keep) || keep < keepMin || keep > keepMax)
        {
            BkSettingsMessage($"Archives gardées : un nombre de {keepMin} à {keepMax}.", false);
            return;
        }
        var wanted = new AdminBackupSettingsValues
        {
            Actives = BkAutoBox.IsChecked == true,
            IntervalleMinutes = (BkIntervalBox.SelectedItem as ComboBoxItem)?.Tag as int? ?? current.IntervalleMinutes,
            ALArret = BkAtStopBox.IsChecked == true,
            Garder = keep,
            PendantPartie = BkDuringGameBox.IsChecked == true,
        };
        var change = AdminBackupSettingsChange.Between(current, wanted);
        if (change.IsEmpty)
        {
            _bkDirty = false;
            BkSettingsMessage("Rien à enregistrer : aucun réglage n'a changé.", true);
            BkRender();
            return;
        }
        BkSaveButton.IsEnabled = false;
        int gen = _srvGen;
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(30));
            var r = await client.ChangeBackupSettingsAsync(change, cts.Token);
            if (gen != _srvGen)
            {
                return;
            }
            _bkSettings = r;
            if (_bk != null)
            {
                _bk.Reglages = r.Reglages;
                _bk.ProchaineAuto = r.ProchaineAuto;
            }
            _bkDirty = false;
            BkFillSettings();
            BkSettingsMessage(r.Message.Length > 0 ? r.Message : "Réglages enregistrés.", true);
            if (keep < current.Garder)
            {
                BkSettingsResult.Text += " Le nouveau nombre d'archives s'applique à la prochaine sauvegarde.";
            }
        }
        catch (Exception ex)
        {
            // Connexion remplacée pendant la requête (rien à dire), ou refus du serveur.
            if (gen == _srvGen)
            {
                BkSettingsMessage(ex.Message, false);
            }
        }
        BkRender();
    }

    private void BkSettingsMessage(string text, bool ok)
    {
        BkSettingsResult.Text = text;
        BkSettingsResult.Foreground = ok ? Res("Good") : Res("Bad");
        BkSettingsResult.Visibility = Visibility.Visible;
    }
}
