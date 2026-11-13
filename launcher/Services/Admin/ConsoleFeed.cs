using System.Text.RegularExpressions;

namespace Valcombe.Launcher.Services.Admin;

/// <summary>
/// Suivi en direct de la console d'un serveur : chaque lecture demande « ce qui suit le dernier numéro vu »
/// (GET /admin/console?depuis=n). Quand le serveur a redémarré (champ <c>instance</c> changé), les numéros
/// repartent de 1 : le suivi oublie le sien et relit les dernières lignes.
/// </summary>
public sealed class ConsoleFeed
{
    /// <summary>Lignes demandées à la première lecture.</summary>
    public const int FirstRead = 200;

    /// <summary>Pages lues d'affilée au plus quand le serveur dit qu'il en reste (« plusLoin »).</summary>
    private const int MaxPages = 5;

    private string? _instance;
    private long? _next;

    /// <summary>Ce qu'une lecture rapporte.</summary>
    /// <param name="Lines">Lignes nouvelles, de la plus ancienne à la plus récente.</param>
    /// <param name="Reset">Les lignes déjà affichées sont à oublier : première lecture, ou serveur redémarré.</param>
    /// <param name="Lost">Lignes écrasées sur le serveur avant d'avoir été lues (lectures trop espacées).</param>
    public sealed record Batch(IReadOnlyList<AdminConsoleLine> Lines, bool Reset, long Lost);

    /// <summary>Une ligne du journal du processus : « [12:34:56] [Server thread/INFO]: texte » (Minecraft) ou « … (Logger) texte » (Fabric).</summary>
    private static readonly Regex ProcessLine = new(@"^\[(\d\d:\d\d:\d\d)\] \[[^\]]*?/(\w+)\](?::| \([^)]*\)) ?(.*)$", RegexOptions.Compiled);

    /// <summary>
    /// Découpe une ligne écrite par le processus du serveur (quand le serveur de ce PC n'a pas d'API) :
    /// heure, niveau et texte, ou null pour une ligne d'une autre forme (suite d'une trace d'erreur, ligne du launcher).
    /// </summary>
    public static (string Time, string Level, string Text)? ParseProcessLine(string line)
    {
        var m = ProcessLine.Match(line);
        return m.Success ? (m.Groups[1].Value, m.Groups[2].Value, m.Groups[3].Value) : null;
    }

    /// <summary>Repartir de zéro (autre serveur choisi, connexion refaite).</summary>
    public void Forget()
    {
        _instance = null;
        _next = null;
    }

    public async Task<Batch> PollAsync(AdminClient client, CancellationToken ct)
    {
        bool reset = _next == null;
        var lines = new List<AdminConsoleLine>();
        long lost = 0;
        for (int page = 0; page < MaxPages; page++)
        {
            var p = await client.GetConsoleAsync(_next, _next == null ? FirstRead : null, ct);
            if (_next != null && _instance != null && p.Instance != _instance)
            {
                // Serveur redémarré : nos numéros ne veulent plus rien dire, on relit les dernières lignes.
                _instance = p.Instance;
                _next = null;
                reset = true;
                lines.Clear();
                lost = 0;
                continue;
            }
            _instance = p.Instance;
            lines.AddRange(p.Lignes);
            lost += p.Perdues;
            _next = p.Suivant;
            if (!p.PlusLoin)
            {
                break;
            }
        }
        return new Batch(lines, reset, lost);
    }
}
