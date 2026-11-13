using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using Valcombe.Launcher.Services;
using Valcombe.Launcher.Services.Admin;

namespace Valcombe.Launcher;

/// <summary>
/// Onglet « Parties » de la page Serveur : le tableau des parties du serveur choisi (docs/ADMIN-API.md, « Les parties »),
/// comme le hub le montre aux joueurs. Chaque partie dit son état (ouverte, en cours, en pause, en suspens), où elle en
/// est, ses joueurs et ses places, son Conteur, qui est dans son monde et qui la regarde ; on la met en suspens ou on la
/// reprend d'ici, on supprime une partie d'essai (avec confirmation), et on en crée une nouvelle.
///
/// Tout passe par l'API d'administration : le serveur de ce PC comme un serveur loué (tunnel SSH) ou distant. La lecture
/// se fait dans l'interrogation de la page (<see cref="SrvPollAsync"/>), toutes les deux secondes sur cet onglet.
/// </summary>
public partial class MainWindow
{
    private AdminGames? _gm;
    private bool _gmStale = true;
    /// <summary>Ce serveur ne connaît pas encore le tableau des parties (API plus ancienne) : dit sur l'onglet.</summary>
    private string? _gmUnsupported;
    private string _gmShown = "\0";
    /// <summary>Un geste en cours (création, suspens, suppression) : les boutons attendent sa réponse.</summary>
    private bool _gmBusy;
    /// <summary>La partie dont la suppression attend d'être confirmée.</summary>
    private AdminGame? _gmConfirm;

    /// <summary>Nouvelle connexion : ce qu'on savait des parties est relu.</summary>
    private void GmReset()
    {
        _gm = null;
        _gmStale = true;
        _gmUnsupported = null;
        _gmShown = "\0";
        _gmConfirm = null;
        if (GmConfirm != null)
        {
            GmConfirm.Visibility = Visibility.Collapsed;
            GmNotice.Visibility = Visibility.Collapsed;
        }
    }

    /// <summary>Faut-il lire le tableau cette fois-ci ? Sur l'onglet : toutes les deux secondes, ou tout de suite s'il a changé.</summary>
    private bool GmWanted(bool force)
    {
        bool tab = _srvTab == "parties" && PageServer.Visibility == Visibility.Visible;
        return tab && _gmUnsupported == null && (force || _gmStale || _srvTick % 2 == 0);
    }

    /// <summary>Lit le tableau ; une API sans parties (404) est dite sur l'onglet, sans arrêter la page.</summary>
    private async Task<AdminGames?> GmReadAsync(AdminClient client, CancellationToken ct)
    {
        try
        {
            return await client.GetGamesAsync(ct);
        }
        catch (AdminException ex) when (ex.Status is 404 or 405)
        {
            _gmUnsupported = "Ce serveur ne connaît pas encore le tableau des parties : mettez-le à jour (Valcombe 0.14 ou plus récent). "
                + "Réponse du serveur : " + ex.Message;
            return null;
        }
    }

    private void GmReceived(AdminGames? games)
    {
        if (games == null)
        {
            return;
        }
        _gm = games;
        _gmStale = false;
    }

    // ------------------------------------------------------------------
    // Affichage
    // ------------------------------------------------------------------
    /// <summary>La couleur d'un état : vert pour une partie ouverte, rouge vif en cours, or en pause, gris bleuté en suspens.</summary>
    private Brush GmColor(string status) => status switch
    {
        "en-cours" => new SolidColorBrush(Color.FromRgb(0xE0, 0x6B, 0x5E)),
        "en-pause" => Res("Gold"),
        "suspendue" => new SolidColorBrush(Color.FromRgb(0x8F, 0xB4, 0xC4)),
        _ => Res("Good"),
    };

    /// <summary>Le pictogramme d'un état (Segoe MDL2) : la lune ou le soleil d'une partie en cours, la pause, le cadenas du suspens.</summary>
    private static string GmGlyph(AdminGame g) => g.Status switch
    {
        "en-cours" => g.Phase == "night" ? "" : g.Phase == "day" ? "" : "",
        "en-pause" => "",
        "suspendue" => "",
        _ => "",
    };

    /// <summary>Met l'onglet à jour (appelé à chaque rendu de la page ; la liste n'est refaite que si elle a changé).</summary>
    private void GmRender()
    {
        if (GmList == null)
        {
            return;
        }
        var g = _srvError == null && _srvClient != null ? _gm : null;
        bool api = g != null;
        bool localStopped = IsLocalTarget && _srvClient == null;
        if (api)
        {
            int n = g!.Parties.Count;
            int trials = g.Parties.Count(p => !p.Principale);
            GmSummary.Text = !g.Disponible ? "Les mondes de Valcombe n'ont pas pu démarrer sur ce serveur : voir sa console."
                : n == 0 ? "Aucune partie sur ce serveur."
                : $"{n} partie{(n > 1 ? "s" : "")}" + (trials > 0 ? $" dont {trials} d'essai" : "")
                    + (g.EnCours > 0 ? $", {g.EnCours} lancée{(g.EnCours > 1 ? "s" : "")}" : ", aucune lancée") + ".";
            GmSummary.Foreground = Res("Ash");
        }
        else
        {
            GmSummary.Text = _gmUnsupported
                ?? (localStopped ? (LocalState == LocalServer.State.Stopped
                        ? "Le serveur de ce PC est arrêté : démarrez-le pour voir ses parties."
                        : "Le serveur de ce PC démarre : ses parties s'affichent dès que son API répond.")
                    : _srvError != null ? "Le serveur ne répond pas : " + _srvError.Message
                    : "Les parties s'affichent une fois le serveur joint.");
            GmSummary.Foreground = _gmUnsupported != null ? Res("Gold") : Res("Ash");
        }
        bool canCreate = api && g!.Disponible && !_gmBusy && _srvStopping.Length == 0;
        GmCreateButton.IsEnabled = canCreate;
        GmNameBox.IsEnabled = api && g!.Disponible;
        GmCreateText.Text = _gmBusy ? "Un instant…" : "Créer la partie";

        string signature = !api ? "rien:" + _srvConnId + GmSummary.Text
            : _gmBusy + "|" + string.Join("|", g!.Parties.Select(p => $"{p.Id}/{p.Nom}/{p.Status}/{p.PhaseTexte}/{p.Participants}/{p.Places}/"
                + $"{p.Conteur}/{p.Joueurs}/{p.Spectateurs}/{p.Etat}"));
        if (signature == _gmShown)
        {
            return;
        }
        _gmShown = signature;
        GmList.Children.Clear();
        if (!api)
        {
            return;
        }
        for (int i = 0; i < g!.Parties.Count; i++)
        {
            GmList.Children.Add(GmRow(g.Parties[i], last: i == g.Parties.Count - 1));
        }
    }

    /// <summary>Une partie : son état, où elle en est, ses places, et ce qu'on peut en faire d'ici.</summary>
    private FrameworkElement GmRow(AdminGame x, bool last)
    {
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        Brush color = GmColor(x.Status);
        // Le pictogramme de l'état, dans un disque teinté.
        var badge = new Border
        {
            Width = 34, Height = 34, CornerRadius = new CornerRadius(17), Margin = new Thickness(0, 2, 14, 0), VerticalAlignment = VerticalAlignment.Top,
            Background = new SolidColorBrush(Color.FromArgb(0x26, ((SolidColorBrush)color).Color.R, ((SolidColorBrush)color).Color.G, ((SolidColorBrush)color).Color.B)),
            BorderBrush = color, BorderThickness = new Thickness(1),
            Child = new TextBlock
            {
                Text = GmGlyph(x), FontFamily = (FontFamily)FindResource("Icons"), FontSize = 15, Foreground = color,
                HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            },
        };
        grid.Children.Add(badge);

        var text = new StackPanel();
        // Le nom, l'état et la nuit passent à la ligne plutôt que d'être coupés : « En suspens · Nuit 3 » se lit en entier
        // même à côté de l'étiquette « essai » et d'un nom long.
        var title = new TextBlock { FontSize = 14.5, Foreground = Res("Ink"), TextWrapping = TextWrapping.Wrap };
        title.Inlines.Add(new Run(x.Nom.Length > 0 ? x.Nom : x.Id) { FontWeight = FontWeights.SemiBold });
        title.Inlines.Add(new Run("   " + x.StatusText) { FontSize = 12.5, Foreground = color, FontWeight = FontWeights.SemiBold });
        if (x.Status != "ouverte" && x.PhaseTexte.Length > 0)
        {
            title.Inlines.Add(new Run(" · " + x.PhaseTexte) { FontSize = 12.5, Foreground = Res("Parch") });
        }
        // À droite du nom, sa sorte en étiquette : dorée pour la partie principale, cendre pour une partie d'essai.
        var head = new DockPanel { LastChildFill = true };
        var kind = new Border
        {
            Style = (Style)FindResource("Badge"), Margin = new Thickness(10, 0, 0, 0),
            Background = x.Principale ? new SolidColorBrush(Color.FromArgb(0x26, 0xE2, 0xBD, 0x63)) : new SolidColorBrush(Color.FromArgb(0x1A, 0xAF, 0xA0, 0x90)),
            BorderBrush = x.Principale ? new SolidColorBrush(Color.FromArgb(0x66, 0xE2, 0xBD, 0x63)) : new SolidColorBrush(Color.FromArgb(0x55, 0xAF, 0xA0, 0x90)),
            Child = new TextBlock { Text = x.Principale ? "principale" : "essai", FontSize = 11, Foreground = x.Principale ? Res("Gold") : Res("Ash") },
        };
        DockPanel.SetDock(kind, Dock.Right);
        head.Children.Add(kind);
        head.Children.Add(title);
        text.Children.Add(head);
        var parts = new List<string> { x.JoueursTexte, string.IsNullOrEmpty(x.Conteur) ? "Conteur automatique" : "Conteur : " + x.Conteur };
        parts.Add(x.Joueurs == 0 ? "personne dans son monde" : $"{x.Joueurs} dans son monde");
        if (x.Spectateurs > 0)
        {
            parts.Add($"{x.Spectateurs} spectateur{(x.Spectateurs > 1 ? "s" : "")}");
        }
        text.Children.Add(new TextBlock
        {
            Text = string.Join(" · ", parts), Style = (Style)FindResource("Hint"), Margin = new Thickness(0, 1, 0, 0),
            TextWrapping = TextWrapping.Wrap,
        });
        // Les places : une jauge fine, dorée pour ce qui est pris.
        if (x.Places > 0)
        {
            var bar = new ProgressBar
            {
                Maximum = x.Places, Value = Math.Min(x.Places, x.Participants), Height = 4, Margin = new Thickness(0, 6, 0, 0),
                MaxWidth = 260, HorizontalAlignment = HorizontalAlignment.Left, Width = 260,
                ToolTip = x.JoueursTexte,
            };
            text.Children.Add(bar);
        }
        if (x.Status == "suspendue")
        {
            text.Children.Add(new TextBlock
            {
                Text = "Horloge arrêtée, joueurs au hub : elle reprendra où elle en est, ses joueurs rappelés à leur place.",
                Style = (Style)FindResource("Hint"), Foreground = GmColor("suspendue"), Margin = new Thickness(0, 5, 0, 0),
            });
        }
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        grid.ToolTip = $"{x.Nom} ({x.Id}) : {x.Etat}";

        var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(10, 0, 0, 0) };
        bool running = x.Status is "en-cours" or "en-pause";
        if (x.Status == "suspendue")
        {
            var resume = SmallButton("Reprendre", "Rappeler ses joueurs : ils retrouvent leur place, et elle repart quand tous sont là (une minute au plus).");
            resume.IsEnabled = !_gmBusy;
            resume.Click += async (_, _) => await GmSuspendAsync(x, suspend: false);
            actions.Children.Add(resume);
        }
        else if (running)
        {
            // Seule une partie lancée se met en suspens : une partie ouverte n'a rien à geler.
            var suspend = SmallButton("Suspendre", "Geler son horloge et envoyer ses joueurs au hub : elle reprendra où elle en est, même après un redémarrage.");
            suspend.IsEnabled = !_gmBusy;
            suspend.Click += async (_, _) => await GmSuspendAsync(x, suspend: true);
            actions.Children.Add(suspend);
        }
        if (!x.Principale)
        {
            var delete = BkIconButton("", x.Lancee ? "Une partie lancée ne se supprime pas, même en suspens : arrêtez-la d'abord (/vc arreter, dans son monde)."
                : "Supprimer cette partie d'essai et son monde (une confirmation suit)");
            delete.Foreground = Res("Bad");
            delete.IsEnabled = !x.Lancee && !_gmBusy;
            delete.Click += (_, _) => GmAskDelete(x);
            actions.Children.Add(delete);
        }
        Grid.SetColumn(actions, 2);
        grid.Children.Add(actions);
        return new Border
        {
            Child = grid, Padding = new Thickness(0, 10, 0, 10),
            BorderBrush = new SolidColorBrush(Color.FromArgb(0x26, 0xE2, 0xBD, 0x63)), BorderThickness = new Thickness(0, 0, 0, last ? 0 : 1),
        };
    }

    // ------------------------------------------------------------------
    // Avis
    // ------------------------------------------------------------------
    /// <summary>Un avis en haut de l'onglet : <paramref name="ok"/> faux pour un refus du serveur.</summary>
    private void GmNotify(string text, bool ok)
    {
        GmNoticeText.Text = text;
        (GmNoticeIcon.Text, GmNoticeIcon.Foreground) = ok ? ("", Res("Good")) : ("", Res("Bad"));
        GmNotice.Visibility = Visibility.Visible;
    }

    private void GmHideNotice(object sender, RoutedEventArgs e) => GmNotice.Visibility = Visibility.Collapsed;

    // ------------------------------------------------------------------
    // Gestes
    // ------------------------------------------------------------------
    /// <summary>Un geste sur le serveur : une seule à la fois, la réponse en avis, puis le tableau relu.</summary>
    private async Task GmActAsync(Func<AdminClient, CancellationToken, Task<string>> act)
    {
        var client = _srvClient;
        if (client == null || _gmBusy)
        {
            return;
        }
        _gmBusy = true;
        _gmShown = "\0";
        GmRender();
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20));
            string said = await act(client, cts.Token);
            GmNotify(said, ok: true);
        }
        catch (AdminException ex)
        {
            GmNotify(ex.Message, ok: false);
        }
        catch (ArgumentException ex)
        {
            GmNotify(ex.Message, ok: false);
        }
        catch (OperationCanceledException)
        {
            GmNotify("Le serveur n'a pas répondu à temps : relisez le tableau dans un instant.", ok: false);
        }
        catch (Exception ex)
        {
            // Connexion coupée ou remplacée pendant la requête, ou imprévu : jamais d'exception hors d'un async void, elle
            // fermerait le launcher.
            GmNotify("Le serveur n'a pas pu être joint : " + ex.Message, ok: false);
        }
        finally
        {
            _gmBusy = false;
            _gmStale = true;
            _gmShown = "\0";
            await SrvPollAsync(force: true);
        }
    }

    private async Task GmSuspendAsync(AdminGame x, bool suspend)
    {
        await GmActAsync(async (client, ct) =>
        {
            var changed = suspend ? await client.SuspendGameAsync(x.Id, ct) : await client.ResumeGameAsync(x.Id, ct);
            return changed.Message.Length > 0 ? changed.Message : $"« {x.Nom} » : {changed.StatusText.ToLowerInvariant()}.";
        });
    }

    private async void GmCreate(object sender, RoutedEventArgs e)
    {
        string name = GmNameBox.Text.Trim();
        if (name.Length == 0)
        {
            GmNotify("Donnez un nom à la partie d'essai (par exemple : Essai du mardi).", ok: false);
            GmNameBox.Focus();
            return;
        }
        await GmActAsync(async (client, ct) =>
        {
            var created = await client.CreateGameAsync(name, ct);
            GmNameBox.Text = "";
            return created.Message;
        });
    }

    private void GmNameKey(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            GmCreate(sender, e);
        }
    }

    /// <summary>Demande la confirmation d'une suppression : ce qui disparaît, et que c'est sans retour.</summary>
    private void GmAskDelete(AdminGame x)
    {
        _gmConfirm = x;
        GmConfirmTitle.Text = $"Supprimer « {x.Nom} » ?";
        GmConfirmText.Text = $"La partie d'essai et son monde ({x.Id}) sont effacés : sa carte, ce qui y a été construit, sa sauvegarde. "
            + (x.Joueurs > 0 ? $"Les {x.Joueurs} joueur{(x.Joueurs > 1 ? "s" : "")} qui s'y trouvent vont au hub. " : "")
            + "On ne revient pas en arrière, sauf par une sauvegarde du serveur.";
        GmConfirm.Visibility = Visibility.Visible;
        SrvGamesPanel.ScrollToTop();
    }

    private void GmCancelDelete(object sender, RoutedEventArgs e)
    {
        _gmConfirm = null;
        GmConfirm.Visibility = Visibility.Collapsed;
    }

    private async void GmConfirmDelete(object sender, RoutedEventArgs e)
    {
        var x = _gmConfirm;
        _gmConfirm = null;
        GmConfirm.Visibility = Visibility.Collapsed;
        if (x == null)
        {
            return;
        }
        await GmActAsync(async (client, ct) => (await client.DeleteGameAsync(x.Id, ct)).Message);
    }
}
