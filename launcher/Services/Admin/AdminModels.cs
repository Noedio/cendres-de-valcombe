using System.Text.Json;
using System.Text.Json.Nodes;

namespace Valcombe.Launcher.Services.Admin;

// Les réponses de l'API d'administration, telles que docs/ADMIN-API.md les décrit. Les noms de champs JSON sont
// en français ; ils sont lus sans tenir compte de la casse (« dureeSecondes » → DureeSecondes). Un champ
// inconnu est ignoré, un champ absent garde sa valeur par défaut : le contrat peut s'enrichir sans rien casser.

/// <summary>GET /admin/etat.</summary>
public sealed class AdminState
{
    public int Api { get; set; }
    public string Version { get; set; } = "";
    public string Minecraft { get; set; } = "";
    public bool Dedie { get; set; }
    public string? DemarreLe { get; set; }
    public long DureeSecondes { get; set; }
    public double Tps { get; set; }
    public double TpsCible { get; set; } = 20;
    public double MsParTick { get; set; }
    public bool EnVeille { get; set; }
    public AdminMemory Memoire { get; set; } = new();
    public AdminPlayerCount Joueurs { get; set; } = new();
    public string MessageAccueil { get; set; } = "";
    public string? ProchaineSoiree { get; set; }
    public string Motd { get; set; } = "";
    public List<AdminWorld> Mondes { get; set; } = new();
    /// <summary>Les parties du serveur, une par monde de partie, la principale d'abord.</summary>
    public List<AdminGame> Parties { get; set; } = new();
    public bool RedemarrageNecessaire { get; set; }
}

public sealed class AdminMemory
{
    public long UtiliseeMo { get; set; }
    public long ReserveeMo { get; set; }
    public long MaxMo { get; set; }
}

public sealed class AdminPlayerCount
{
    public int Connectes { get; set; }
    public int Max { get; set; }
}

/// <summary>Un monde de Valcombe (config/valcombe/mondes.json), dans GET /admin/etat.</summary>
public sealed class AdminWorld
{
    public string Id { get; set; } = "";
    public string Nom { get; set; } = "";
    /// <summary><c>hub</c>, <c>partie</c>, <c>essai</c>, <c>atelier-carte</c>, <c>atelier-structures</c>.</summary>
    public string Sorte { get; set; } = "";
    /// <summary><c>charge</c>, <c>remise-a-neuf</c>, <c>decharge</c>.</summary>
    public string Etat { get; set; } = "";
    public int Joueurs { get; set; }
    /// <summary>Un monde de partie : l'état de sa partie (null pour les autres mondes, ou un serveur plus ancien).</summary>
    public AdminWorldGame? Partie { get; set; }

    /// <summary>La sorte, en mots.</summary>
    public string SorteTexte => Sorte switch
    {
        "hub" => "Hub",
        "partie" => "Partie",
        "essai" => "Partie d'essai",
        "atelier-carte" => "Atelier carte",
        "atelier-structures" => "Atelier structures",
        _ => Sorte,
    };

    /// <summary>L'état, en mots.</summary>
    public string EtatTexte => Etat switch
    {
        "charge" => "Ouvert",
        "remise-a-neuf" => "Remise à neuf",
        "decharge" => "Fermé",
        _ => Etat,
    };
}

/// <summary>GET /admin/joueurs.</summary>
public sealed class AdminPlayers
{
    public int Connectes { get; set; }
    public int Max { get; set; }
    public List<AdminPlayer> Joueurs { get; set; } = new();
}

public sealed class AdminPlayer
{
    public string Nom { get; set; } = "";
    public string Uuid { get; set; } = "";
    public bool Operateur { get; set; }
    public string Monde { get; set; } = "";
    public int Ping { get; set; } = -1;
    public string ModeDeJeu { get; set; } = "";
}

/// <summary>GET /admin/console.</summary>
public sealed class AdminConsolePage
{
    public string Instance { get; set; } = "";
    public List<AdminConsoleLine> Lignes { get; set; } = new();
    public long Suivant { get; set; }
    public long Premiere { get; set; }
    public long Perdues { get; set; }
    public bool PlusLoin { get; set; }
}

public sealed class AdminConsoleLine
{
    public long N { get; set; }
    /// <summary>Moment, en millisecondes depuis 1970 (UTC).</summary>
    public long T { get; set; }
    public string Niveau { get; set; } = "INFO";
    public string Fil { get; set; } = "";
    public string Source { get; set; } = "";
    public string Texte { get; set; } = "";
}

/// <summary>Réponse de POST /admin/commande.</summary>
public sealed class AdminCommandResult
{
    public bool Ok { get; set; }
    public int Resultat { get; set; }
    public List<string> Sortie { get; set; } = new();
}

/// <summary>GET /admin/reglages.</summary>
public sealed class AdminSettings
{
    public AdminWhitelist ListeBlanche { get; set; } = new();
    public List<AdminOperator> Operateurs { get; set; } = new();
    public string MessageAccueil { get; set; } = "";
    public string MessageAccueilParDefaut { get; set; } = "";
    public string? ProchaineSoiree { get; set; }
    public int JoueursMax { get; set; }
    public int Port { get; set; }
    public int PortEnregistre { get; set; }
    public List<string> Modifiables { get; set; } = new();
    public bool RedemarrageNecessaire { get; set; }
    public List<string> EnAttente { get; set; } = new();

    /// <summary>Ce réglage peut être changé sur ce serveur (joueursMax et port : serveur dédié seulement).</summary>
    public bool CanChange(string name) => Modifiables.Contains(name, StringComparer.Ordinal);
}

public sealed class AdminWhitelist
{
    public bool Active { get; set; }
    public bool Imposee { get; set; }
    public List<string> Joueurs { get; set; } = new();
}

public sealed class AdminOperator
{
    public string Nom { get; set; } = "";
    public string Uuid { get; set; } = "";
    public int Niveau { get; set; }
}

/// <summary>Réponse de POST /admin/reglages : chaque point demandé, réussi ou non.</summary>
public sealed class AdminSettingsResult
{
    public bool Ok { get; set; }
    public List<AdminSettingStep> Resultats { get; set; } = new();
    public bool RedemarrageNecessaire { get; set; }
    public AdminSettings? Reglages { get; set; }
}

public sealed class AdminSettingStep
{
    public string Reglage { get; set; } = "";
    public JsonElement Valeur { get; set; }
    public bool Ok { get; set; }
    public string Message { get; set; } = "";
}

/// <summary>Réponse de POST /admin/arreter et /admin/redemarrer.</summary>
public sealed class AdminStopResult
{
    public bool Ok { get; set; }
    public string Message { get; set; } = "";
    public string? Drapeau { get; set; }
}

/// <summary>
/// Une demande de POST /admin/reglages : seuls les champs renseignés partent. Le serveur valide tout avant
/// d'appliquer quoi que ce soit (une faute et rien ne change).
/// </summary>
public sealed class AdminSettingsChange
{
    public bool? WhitelistActive { get; set; }
    public List<string> WhitelistAdd { get; } = new();
    public List<string> WhitelistRemove { get; } = new();
    public List<string> OperatorsAdd { get; } = new();
    public List<string> OperatorsRemove { get; } = new();
    /// <summary>Une ligne de 60 caractères au plus ; <c>""</c> revient au message par défaut.</summary>
    public string? Welcome { get; set; }
    /// <summary>Même règle ; <c>""</c> efface l'annonce.</summary>
    public string? NextEvening { get; set; }
    public int? MaxPlayers { get; set; }
    public int? Port { get; set; }

    public bool IsEmpty => WhitelistActive == null && WhitelistAdd.Count == 0 && WhitelistRemove.Count == 0
        && OperatorsAdd.Count == 0 && OperatorsRemove.Count == 0 && Welcome == null && NextEvening == null
        && MaxPlayers == null && Port == null;

    public string ToJson()
    {
        var o = new JsonObject();
        if (WhitelistActive != null || WhitelistAdd.Count > 0 || WhitelistRemove.Count > 0)
        {
            var wl = new JsonObject();
            if (WhitelistActive != null)
            {
                wl["active"] = WhitelistActive.Value;
            }
            AddNames(wl, "ajouter", WhitelistAdd);
            AddNames(wl, "retirer", WhitelistRemove);
            o["listeBlanche"] = wl;
        }
        if (OperatorsAdd.Count > 0 || OperatorsRemove.Count > 0)
        {
            var ops = new JsonObject();
            AddNames(ops, "ajouter", OperatorsAdd);
            AddNames(ops, "retirer", OperatorsRemove);
            o["operateurs"] = ops;
        }
        if (Welcome != null)
        {
            o["messageAccueil"] = Welcome;
        }
        if (NextEvening != null)
        {
            o["prochaineSoiree"] = NextEvening;
        }
        if (MaxPlayers != null)
        {
            o["joueursMax"] = MaxPlayers.Value;
        }
        if (Port != null)
        {
            o["port"] = Port.Value;
        }
        return o.ToJsonString();
    }

    private static void AddNames(JsonObject target, string field, List<string> names)
    {
        if (names.Count > 0)
        {
            target[field] = new JsonArray(names.Select(n => (JsonNode?)JsonValue.Create(n)).ToArray());
        }
    }
}

/// <summary>L'état de la partie d'un monde de partie, dans GET /admin/etat (<c>mondes[].partie</c>).</summary>
public sealed class AdminWorldGame
{
    /// <summary><c>ouverte</c>, <c>en-cours</c>, <c>en-pause</c>, <c>suspendue</c>.</summary>
    public string Statut { get; set; } = "";
    /// <summary>Ce qui s'y passe, en clair (« nuit 2 », « 3 inscrit(s) »).</summary>
    public string Etat { get; set; } = "";
}

/// <summary>
/// Une partie du serveur (GET /admin/parties, et <c>parties</c> de GET /admin/etat) : sa fiche telle que le tableau des
/// parties du hub la montre.
/// </summary>
public class AdminGame
{
    public string Id { get; set; } = "";
    public string Nom { get; set; } = "";
    /// <summary><c>partie</c> ou <c>essai</c>.</summary>
    public string Sorte { get; set; } = "";
    public bool Principale { get; set; }
    /// <summary>Ce qui s'y passe, en clair (« salon ouvert », « nuit 1 », « en suspens (jour 2) »).</summary>
    public string Etat { get; set; } = "";
    public bool EnCours { get; set; }
    public bool EnPause { get; set; }
    public bool Suspendue { get; set; }
    /// <summary><c>setup</c>, <c>ready</c>, <c>night</c>, <c>day</c>, <c>ended</c>, ou null au salon.</summary>
    public string? Phase { get; set; }
    public int Inscrits { get; set; }
    public int Assis { get; set; }
    public string? Conteur { get; set; }
    /// <summary>Joueurs présents dans son monde.</summary>
    public int Joueurs { get; set; }
    /// <summary><c>ouverte</c>, <c>en-cours</c>, <c>en-pause</c>, <c>suspendue</c> (vide : serveur plus ancien, voir <see cref="Status"/>).</summary>
    public string Statut { get; set; } = "";
    public string StatutTexte { get; set; } = "";
    public string PhaseTexte { get; set; } = "";
    public int Numero { get; set; }
    /// <summary>Assis si la partie est lancée, sinon inscrits.</summary>
    public int Participants { get; set; }
    public int Places { get; set; }
    public int Spectateurs { get; set; }

    /// <summary>Le statut, déduit des anciens champs pour un serveur qui ne l'envoie pas encore.</summary>
    public string Status => Statut.Length > 0 ? Statut
        : Suspendue ? "suspendue" : EnCours ? (EnPause ? "en-pause" : "en-cours") : "ouverte";

    /// <summary>Le statut, en mots.</summary>
    public string StatusText => StatutTexte.Length > 0 ? StatutTexte : Status switch
    {
        "suspendue" => "En suspens",
        "en-pause" => "En pause",
        "en-cours" => "En cours",
        _ => "Ouverte",
    };

    /// <summary>« Partie principale », « Partie d'essai ».</summary>
    public string SorteTexte => Principale ? "Partie principale" : Sorte == "essai" ? "Partie d'essai" : "Partie";

    /// <summary>Une partie lancée (en suspens comprise) : elle ne se supprime pas.</summary>
    public bool Lancee => Status != "ouverte";

    /// <summary>« 7 joueurs sur 12 places », « 3 inscrits sur 16 places », « Personne d'inscrit, 16 places ».</summary>
    public string JoueursTexte
    {
        get
        {
            int n = Participants > 0 || Statut.Length > 0 ? Participants : EnCours ? Assis : Inscrits;
            string places = Places > 0 ? $"{Places} place{(Places > 1 ? "s" : "")}" : "";
            if (Lancee)
            {
                return $"{n} joueur{(n > 1 ? "s" : "")}" + (places.Length > 0 ? " sur " + places : "");
            }
            if (n == 0)
            {
                return "Personne d'inscrit" + (places.Length > 0 ? ", " + places : "");
            }
            return $"{n} inscrit{(n > 1 ? "s" : "")}" + (places.Length > 0 ? " sur " + places : "");
        }
    }
}

/// <summary>GET /admin/parties.</summary>
public sealed class AdminGames
{
    public List<AdminGame> Parties { get; set; } = new();
    public int EnCours { get; set; }
    public string? Principale { get; set; }
    public bool Disponible { get; set; } = true;
}

/// <summary>Réponse de POST /admin/parties/suspendre et /reprendre : la partie après le geste, et le message du serveur.</summary>
public sealed class AdminGameChanged : AdminGame
{
    public string Message { get; set; } = "";
}

/// <summary>Réponse de POST /admin/parties : la partie d'essai créée, et le message du serveur.</summary>
public sealed class AdminGameCreated
{
    public AdminGame? Partie { get; set; }
    public string Message { get; set; } = "";
}
