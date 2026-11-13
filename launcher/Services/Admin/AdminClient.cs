using System.Net.Http;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Valcombe.Launcher.Services.Admin;

/// <summary>
/// Les points d'accès de l'API d'administration (docs/ADMIN-API.md, contrat version 1), au-dessus d'une
/// <see cref="IServerConnection"/>. Chaque appel rend la réponse lue, ou lève <see cref="AdminException"/>
/// avec le message du serveur (champ <c>erreur</c>) ou, à défaut, une explication du statut.
/// </summary>
public sealed class AdminClient
{
    /// <summary>Version du contrat que ce launcher sait lire.</summary>
    public const int ContractVersion = 1;

    /// <summary>Port de l'API quand le serveur ne l'a pas changé (le port de la tablette, <c>tabletPort</c>).</summary>
    public const int DefaultPort = 25570;

    private static readonly JsonSerializerOptions Json = new() { PropertyNameCaseInsensitive = true };
    private static readonly Regex PlayerName = new("^[A-Za-z0-9_]{1,16}$", RegexOptions.Compiled);
    private static readonly Regex KeyFormat = new("^[A-Za-z0-9_-]{32,128}$", RegexOptions.Compiled);

    public AdminClient(IServerConnection connection)
    {
        Connection = connection;
    }

    public IServerConnection Connection { get; }

    /// <summary>Un pseudo Minecraft tel que l'API l'accepte : lettres sans accent, chiffres et « _ », 16 au plus.</summary>
    public static bool IsPlayerName(string name) => PlayerName.IsMatch(name);

    /// <summary>Une clé d'administration bien formée : 32 à 128 caractères parmi A-Z a-z 0-9 _ -.</summary>
    public static bool IsKey(string key) => KeyFormat.IsMatch(key);

    public Task<AdminState> GetStateAsync(CancellationToken ct) => GetAsync<AdminState>("/admin/etat", ct);

    public Task<AdminPlayers> GetPlayersAsync(CancellationToken ct) => GetAsync<AdminPlayers>("/admin/joueurs", ct);

    /// <summary>Lignes de la console : les dernières si <paramref name="after"/> est null, sinon celles qui suivent ce numéro.</summary>
    public Task<AdminConsolePage> GetConsoleAsync(long? after, int? max, CancellationToken ct)
    {
        var query = new List<string>();
        if (after != null)
        {
            query.Add("depuis=" + after.Value);
        }
        if (max != null)
        {
            query.Add("max=" + Math.Clamp(max.Value, 1, 1000));
        }
        return GetAsync<AdminConsolePage>("/admin/console" + (query.Count > 0 ? "?" + string.Join('&', query) : ""), ct);
    }

    /// <summary>Exécute une commande comme la console du serveur (la barre oblique du début est facultative).</summary>
    public Task<AdminCommandResult> RunCommandAsync(string command, CancellationToken ct)
    {
        string body = new JsonObject { ["commande"] = command.Trim() }.ToJsonString();
        return PostAsync<AdminCommandResult>("/admin/commande", body, ct);
    }

    public Task<AdminSettings> GetSettingsAsync(CancellationToken ct) => GetAsync<AdminSettings>("/admin/reglages", ct);

    public Task<AdminSettingsResult> ChangeSettingsAsync(AdminSettingsChange change, CancellationToken ct)
    {
        if (change.IsEmpty)
        {
            throw new ArgumentException("Aucun réglage à changer.", nameof(change));
        }
        return PostAsync<AdminSettingsResult>("/admin/reglages", change.ToJson(), ct);
    }

    /// <summary>Arrêt propre ; le serveur s'arrête juste après la réponse.</summary>
    public Task<AdminStopResult> StopAsync(CancellationToken ct) => PostAsync<AdminStopResult>("/admin/arreter", "{}", ct);

    /// <summary>Arrêt propre avec le drapeau de redémarrage : celui qui a lancé le serveur le relance.</summary>
    public Task<AdminStopResult> RestartAsync(CancellationToken ct) => PostAsync<AdminStopResult>("/admin/redemarrer", "{}", ct);

    private async Task<T> GetAsync<T>(string path, CancellationToken ct) where T : new()
    {
        await Connection.ConnectAsync(ct);
        return Read<T>(await Connection.SendAsync(HttpMethod.Get, path, null, ct));
    }

    private async Task<T> PostAsync<T>(string path, string json, CancellationToken ct) where T : new()
    {
        await Connection.ConnectAsync(ct);
        return Read<T>(await Connection.SendAsync(HttpMethod.Post, path, json, ct));
    }

    /// <summary>Lit une réponse réussie, ou lève l'erreur qu'elle porte.</summary>
    public static T Read<T>(AdminReply reply) where T : new()
    {
        if (reply.Status is 200 or 202)
        {
            try
            {
                return JsonSerializer.Deserialize<T>(reply.Body, Json) ?? new T();
            }
            catch (JsonException ex)
            {
                throw new AdminException(reply.Status, "Réponse illisible : ce serveur ne parle pas l'API de Valcombe.", inner: ex);
            }
        }
        throw new AdminException(reply.Status, Explain(reply), reply.RetryAfter);
    }

    /// <summary>Le message à montrer pour une réponse en erreur : celui du serveur s'il y en a un, sinon le sens du statut.</summary>
    public static string Explain(AdminReply reply)
    {
        string? server = null;
        try
        {
            if (JsonNode.Parse(reply.Body) is JsonObject o && o["erreur"] is JsonValue v && v.TryGetValue(out string? text))
            {
                server = text;
            }
        }
        catch (JsonException)
        {
        }
        const string keyHint = "Vérifiez-la : /vc admin cle, dans la console du serveur, la réaffiche.";
        const string addressHint = "Derrière un réseau privé ou un VPN, vous pouvez aussi mettre adminLocalOnly à false "
            + "dans config/valcombe/admin.json, sur le serveur.";
        string meaning = reply.Status switch
        {
            401 => "Clé d'administration refusée. " + keyHint,
            403 => "Le serveur n'accepte l'API que depuis sa propre machine (adminLocalOnly) : depuis un autre PC, passez par "
                + "un tunnel SSH. " + addressHint,
            404 => "Pas d'API d'administration ici : serveur sans Valcombe 0.14 ou plus récent, API désactivée "
                + "(enabled: false dans config/valcombe/admin.json), ou mauvais port.",
            409 => "Impossible dans l'état actuel du serveur.",
            413 => "Demande trop grosse.",
            429 => "Trop d'essais ratés avec une mauvaise clé : le serveur bloque ce PC un moment.",
            503 => "Le serveur démarre ou s'arrête : réessayez dans un instant.",
            504 => "Le serveur n'a pas répondu à temps (très occupé).",
            >= 500 => "Erreur interne du serveur : voir son journal.",
            _ => $"Réponse inattendue du serveur ({reply.Status}).",
        };
        // Le serveur a toujours le dernier mot ; quelques statuts gagnent à être expliqués en plus.
        if (server == null)
        {
            return meaning;
        }
        return reply.Status switch
        {
            401 => server + " " + keyHint,
            403 => server + " " + addressHint,
            _ => server, // 429 : le serveur dit déjà combien de temps attendre

        };
    }
}
