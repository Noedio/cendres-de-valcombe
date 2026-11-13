using System.Globalization;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Valcombe.Launcher.Services.Admin;

// Les sauvegardes du serveur, telles que docs/ADMIN-API.md (section « Les sauvegardes ») les décrit. Comme pour
// AdminModels.cs : champs JSON en français lus sans tenir compte de la casse, champ inconnu ignoré, champ absent
// laissé à sa valeur par défaut.

/// <summary>GET /admin/sauvegardes.</summary>
public sealed class AdminBackups
{
    public string Dossier { get; set; } = "backups";
    /// <summary>De la plus récente à la plus ancienne.</summary>
    public List<AdminBackup> Sauvegardes { get; set; } = new();
    public int Nombre { get; set; }
    public long OctetsTotal { get; set; }
    /// <summary>Place libre sur le disque des sauvegardes, en octets (-1 si inconnue).</summary>
    public long EspaceLibre { get; set; } = -1;
    public AdminBackupJob? EnCours { get; set; }
    public AdminBackupOutcome? Derniere { get; set; }
    public string? ProchaineAuto { get; set; }
    /// <summary>La sauvegarde automatique attend la fin de la partie en cours.</summary>
    public bool Reportee { get; set; }
    /// <summary>Faux en solo : la restauration ne se fait que sur un serveur dédié.</summary>
    public bool RestaurationPossible { get; set; } = true;
    public AdminRestorePlan? RestaurationPrevue { get; set; }
    public AdminRestoreReport? DerniereRestauration { get; set; }
    public AdminBackupSettingsValues Reglages { get; set; } = new();
}

/// <summary>Une archive du dossier backups/ du serveur.</summary>
public sealed class AdminBackup
{
    /// <summary>« valcombe-2026-11-13_15-12-44-auto.zip » : l'identifiant de la sauvegarde partout.</summary>
    public string Nom { get; set; } = "";
    /// <summary><c>auto</c>, <c>arret</c>, <c>manuelle</c> ou <c>avant-restauration</c>.</summary>
    public string Sorte { get; set; } = "";
    /// <summary>Moment de la sauvegarde, avec le décalage horaire du serveur.</summary>
    public string Date { get; set; } = "";
    public long Octets { get; set; }
    /// <summary>La rotation peut l'effacer (toutes les sortes sauf les manuelles).</summary>
    public bool Rotation { get; set; }
    public bool Lisible { get; set; } = true;
    public string? Erreur { get; set; }
    public string? Par { get; set; }
    public string? VersionMod { get; set; }
    public int Fichiers { get; set; }
    public long OctetsDecompresses { get; set; }
    public List<string> Mondes { get; set; } = new();

    /// <summary>Le moment de la sauvegarde, ou null si la date est illisible.</summary>
    public DateTimeOffset? When => AdminBackupText.ParseDate(Date);

    /// <summary>La sorte, en mots.</summary>
    public string SorteTexte => AdminBackupText.Kind(Sorte);
}

/// <summary>La sauvegarde en cours (champ <c>enCours</c>).</summary>
public sealed class AdminBackupJob
{
    public string Nom { get; set; } = "";
    public string Sorte { get; set; } = "";
    public string Par { get; set; } = "";
    public string Debut { get; set; } = "";
    /// <summary><c>attente</c>, <c>enregistrement</c>, <c>copie</c>, <c>rotation</c>.</summary>
    public string Phase { get; set; } = "";
    public int Fichiers { get; set; }
    public int FichiersTotal { get; set; }
    public long Octets { get; set; }
    public long OctetsTotal { get; set; }
    public int Pourcent { get; set; }

    /// <summary>La phase, en mots (« Copie des fichiers »).</summary>
    public string PhaseTexte => Phase switch
    {
        "attente" => "Préparation",
        "enregistrement" => "Enregistrement des mondes sur le disque",
        "copie" => "Copie des fichiers dans l'archive",
        "rotation" => "Rangement des anciennes sauvegardes",
        _ => Phase,
    };
}

/// <summary>La dernière sauvegarde terminée depuis le démarrage du serveur, réussie ou non (champ <c>derniere</c>).</summary>
public sealed class AdminBackupOutcome
{
    public string Nom { get; set; } = "";
    public string Sorte { get; set; } = "";
    public bool Ok { get; set; }
    /// <summary>À montrer tel quel.</summary>
    public string Message { get; set; } = "";
    public long Octets { get; set; }
    public int Fichiers { get; set; }
    public double DureeSecondes { get; set; }
    public string Fin { get; set; } = "";
    public List<string> Effacees { get; set; } = new();
}

/// <summary>La restauration prévue au prochain démarrage (champ <c>restaurationPrevue</c>).</summary>
public sealed class AdminRestorePlan
{
    public string Nom { get; set; } = "";
    public string DemandeLe { get; set; } = "";
    public string Par { get; set; } = "";
}

/// <summary>Le compte rendu de la dernière restauration (backups/derniere-restauration.json).</summary>
public sealed class AdminRestoreReport
{
    public string Nom { get; set; } = "";
    public string DemandeLe { get; set; } = "";
    public string FaiteLe { get; set; } = "";
    public bool Ok { get; set; }
    public string Message { get; set; } = "";
    /// <summary>L'archive « avant-restauration » qui permet de revenir en arrière, ou null.</summary>
    public string? Securite { get; set; }
}

/// <summary>Les réglages des sauvegardes automatiques (config/valcombe/sauvegardes.json).</summary>
public sealed class AdminBackupSettingsValues
{
    public bool Actives { get; set; } = true;
    public int IntervalleMinutes { get; set; } = 360;
    /// <summary>Champ JSON <c>aLArret</c>.</summary>
    public bool ALArret { get; set; } = true;
    public int Garder { get; set; } = 10;
    public bool PendantPartie { get; set; }
}

/// <summary>GET /admin/sauvegardes/reglages, et la réponse au POST (avec <c>ok</c> et <c>message</c>).</summary>
public sealed class AdminBackupSettings
{
    public bool Ok { get; set; }
    public string Message { get; set; } = "";
    public AdminBackupSettingsValues Reglages { get; set; } = new();
    /// <summary>« intervalleMinutes » : [15, 10080], « garder » : [1, 500].</summary>
    public Dictionary<string, int[]> Bornes { get; set; } = new();
    public AdminBackupSettingsValues ParDefaut { get; set; } = new();
    /// <summary>Faux en solo : les réglages sont gardés, mais seules les sauvegardes manuelles se font.</summary>
    public bool AutomatiquesPossibles { get; set; } = true;
    public string? ProchaineAuto { get; set; }

    public (int Min, int Max) IntervalBounds => Bound("intervalleMinutes", 15, 10080);

    public (int Min, int Max) KeepBounds => Bound("garder", 1, 500);

    private (int, int) Bound(string name, int min, int max) =>
        Bornes.TryGetValue(name, out var b) && b.Length == 2 && b[0] <= b[1] ? (b[0], b[1]) : (min, max);
}

/// <summary>Réponse de POST /admin/sauvegardes : la sauvegarde manuelle est lancée.</summary>
public sealed class AdminBackupStarted
{
    public bool Ok { get; set; }
    public string Message { get; set; } = "";
    public string Nom { get; set; } = "";
    public AdminBackupJob? EnCours { get; set; }
}

/// <summary>Réponse de POST /admin/sauvegardes/restaurer.</summary>
public sealed class AdminRestoreResult
{
    public bool Ok { get; set; }
    public string Nom { get; set; } = "";
    /// <summary>Vrai si le serveur s'arrête pour appliquer la restauration (le drapeau de redémarrage est posé).</summary>
    public bool Redemarrage { get; set; }
    public string Message { get; set; } = "";
    public string? Drapeau { get; set; }
}

/// <summary>Réponse simple : <c>{"ok": true, "message": "..."}</c> (effacement, annulation d'une restauration).</summary>
public sealed class AdminMessage
{
    public bool Ok { get; set; }
    public string Message { get; set; } = "";
    /// <summary>DELETE /admin/sauvegardes/restaurer : une restauration était bien prévue.</summary>
    public bool Annulee { get; set; }
}

/// <summary>Une demande de POST /admin/sauvegardes/reglages : seuls les champs renseignés partent.</summary>
public sealed class AdminBackupSettingsChange
{
    public bool? Actives { get; set; }
    public int? IntervalleMinutes { get; set; }
    public bool? ALArret { get; set; }
    public int? Garder { get; set; }
    public bool? PendantPartie { get; set; }

    public bool IsEmpty => Actives == null && IntervalleMinutes == null && ALArret == null && Garder == null && PendantPartie == null;

    /// <summary>Ce qui diffère entre <paramref name="current"/> et <paramref name="wanted"/>.</summary>
    public static AdminBackupSettingsChange Between(AdminBackupSettingsValues current, AdminBackupSettingsValues wanted) => new()
    {
        Actives = current.Actives != wanted.Actives ? wanted.Actives : null,
        IntervalleMinutes = current.IntervalleMinutes != wanted.IntervalleMinutes ? wanted.IntervalleMinutes : null,
        ALArret = current.ALArret != wanted.ALArret ? wanted.ALArret : null,
        Garder = current.Garder != wanted.Garder ? wanted.Garder : null,
        PendantPartie = current.PendantPartie != wanted.PendantPartie ? wanted.PendantPartie : null,
    };

    public string ToJson()
    {
        var o = new JsonObject();
        if (Actives != null)
        {
            o["actives"] = Actives.Value;
        }
        if (IntervalleMinutes != null)
        {
            o["intervalleMinutes"] = IntervalleMinutes.Value;
        }
        if (ALArret != null)
        {
            o["aLArret"] = ALArret.Value;
        }
        if (Garder != null)
        {
            o["garder"] = Garder.Value;
        }
        if (PendantPartie != null)
        {
            o["pendantPartie"] = PendantPartie.Value;
        }
        return o.ToJsonString();
    }
}

/// <summary>Les mots et les formats des sauvegardes, partagés par la page et les vérifications.</summary>
public static class AdminBackupText
{
    private static readonly CultureInfo Fr = CultureInfo.GetCultureInfo("fr-FR");

    /// <summary>
    /// Le modèle exact d'un nom de sauvegarde (le mod refuse tout autre nom) : il protège aussi le chemin
    /// /admin/sauvegardes/&lt;nom&gt; et le fichier enregistré sur ce PC.
    /// </summary>
    public static readonly Regex NamePattern =
        new(@"^valcombe-\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}-(auto|arret|manuelle|avant-restauration)(-\d{1,4})?\.zip$", RegexOptions.Compiled);

    public static bool IsName(string name) => NamePattern.IsMatch(name);

    /// <summary>La sorte d'une sauvegarde, en mots.</summary>
    public static string Kind(string kind) => kind switch
    {
        "auto" => "Automatique",
        "arret" => "À l'arrêt",
        "manuelle" => "Manuelle",
        "avant-restauration" => "Avant restauration",
        _ => kind,
    };

    public static DateTimeOffset? ParseDate(string? text) =>
        DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var d) ? d : null;

    /// <summary>« 46,0 Mo », « 1,2 Go », « 812 Ko ».</summary>
    public static string Size(long bytes) => bytes switch
    {
        < 0 => "?",
        < 1024 => bytes + " o",
        < 1024L * 1024 => (bytes / 1024.0).ToString("0", Fr) + " Ko",
        < 1024L * 1024 * 1024 => (bytes / 1048576.0).ToString(bytes < 10L * 1048576 ? "0.0" : "0", Fr) + " Mo",
        _ => (bytes / 1073741824.0).ToString("0.0", Fr) + " Go",
    };

    /// <summary>
    /// Un moment, à l'heure de ce PC : « aujourd'hui à 15:12 », « hier à 09:12 », « mardi 10 nov. à 21:40 »
    /// (moins d'une semaine), « 13 oct. 2026 à 09:12 ».
    /// </summary>
    public static string When(DateTimeOffset at, DateTime? now = null)
    {
        var local = at.ToLocalTime().DateTime;
        var today = (now ?? DateTime.Now).Date;
        string hour = local.ToString("HH:mm", Fr);
        int days = (today - local.Date).Days;
        return days switch
        {
            0 => "aujourd'hui à " + hour,
            1 => "hier à " + hour,
            > 1 and < 7 => local.ToString("dddd d MMM", Fr) + " à " + hour,
            _ => local.ToString(local.Year == today.Year ? "d MMM" : "d MMM yyyy", Fr) + " à " + hour,
        };
    }

    /// <summary>« 3 h 12 min », « 45 min », « 2 j 4 h » : le temps écoulé depuis un moment (ou jusqu'à).</summary>
    public static string Span(TimeSpan span)
    {
        span = span.Duration();
        return span.TotalMinutes < 1 ? "moins d'une minute"
            : span.TotalHours < 1 ? $"{(int)span.TotalMinutes} min"
            : span.TotalDays < 1 ? $"{(int)span.TotalHours} h {span.Minutes:00}"
            : $"{(int)span.TotalDays} j {span.Hours} h";
    }

    /// <summary>« toutes les 6 heures », « toutes les 45 minutes », « une fois par jour ».</summary>
    public static string Interval(int minutes) => minutes switch
    {
        1440 => "une fois par jour",
        10080 => "une fois par semaine",
        _ when minutes % 1440 == 0 => $"tous les {minutes / 1440} jours",
        60 => "toutes les heures",
        _ when minutes % 60 == 0 => $"toutes les {minutes / 60} heures",
        _ when minutes > 60 => $"toutes les {minutes / 60} h {minutes % 60:00}",
        _ => $"toutes les {minutes} minutes",
    };
}
