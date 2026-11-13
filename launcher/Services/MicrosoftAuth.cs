using System.IO;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Valcombe.Launcher.Services;

/// <summary>Compte Minecraft prêt à lancer le jeu.</summary>
public sealed record MinecraftAccount(string Name, string Uuid, string AccessToken, string Xuid);

/// <summary>
/// Connexion Microsoft intégrée (nécessite un ID d'application Azure validé par Microsoft) :
/// code à saisir sur microsoft.com/link, puis Xbox Live, XSTS et services Minecraft.
/// Le jeton de renouvellement est gardé chiffré (DPAPI Windows) pour ne pas se reconnecter.
/// </summary>
public sealed class MicrosoftAuth
{
    private const string Scope = "XboxLive.signin offline_access";
    private const string Authority = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
    private readonly string _clientId;

    public MicrosoftAuth(string clientId)
    {
        _clientId = clientId.Trim();
    }

    private static string TokenFile => Path.Combine(Paths.DataDir, "compte.bin");

    public static bool HasSavedAccount => File.Exists(TokenFile);

    public static void SignOut()
    {
        if (File.Exists(TokenFile))
        {
            File.Delete(TokenFile);
        }
    }

    /// <summary>Code à afficher au joueur pendant la connexion.</summary>
    public sealed record DeviceCode(string UserCode, string VerificationUri, string Message);

    // ------------------------------------------------------------------
    // Connexion par code
    // ------------------------------------------------------------------
    public async Task<MinecraftAccount> SignInAsync(Action<DeviceCode> showCode, CancellationToken ct)
    {
        var start = await PostFormAsync($"{Authority}/devicecode", new() { ["client_id"] = _clientId, ["scope"] = Scope }, ct);
        string deviceCode = start["device_code"]!.GetValue<string>();
        int interval = start["interval"]?.GetValue<int>() ?? 5;
        showCode(new DeviceCode(start["user_code"]!.GetValue<string>(), start["verification_uri"]!.GetValue<string>(),
            start["message"]?.GetValue<string>() ?? ""));
        while (true)
        {
            await Task.Delay(TimeSpan.FromSeconds(interval), ct);
            var resp = await Http.Client.PostAsync($"{Authority}/token", new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["grant_type"] = "urn:ietf:params:oauth:grant-type:device_code",
                ["client_id"] = _clientId,
                ["device_code"] = deviceCode,
            }), ct);
            var json = JsonNode.Parse(await resp.Content.ReadAsStringAsync(ct))!;
            if (resp.IsSuccessStatusCode)
            {
                return await FinishAsync(json, ct);
            }
            string error = json["error"]?.GetValue<string>() ?? "";
            if (error == "authorization_pending")
            {
                continue;
            }
            if (error == "slow_down")
            {
                interval += 5;
                continue;
            }
            throw new InvalidOperationException(error == "expired_token" ? "Le code a expiré, recommencez." : "Connexion refusée : " + error);
        }
    }

    /// <summary>Reconnexion silencieuse avec le jeton enregistré (null s'il n'y en a pas ou s'il a expiré).</summary>
    public async Task<MinecraftAccount?> RefreshAsync(CancellationToken ct)
    {
        if (!HasSavedAccount)
        {
            return null;
        }
        string refresh = Encoding.UTF8.GetString(Dpapi.Unprotect(await File.ReadAllBytesAsync(TokenFile, ct)));
        var resp = await Http.Client.PostAsync($"{Authority}/token", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["grant_type"] = "refresh_token",
            ["client_id"] = _clientId,
            ["refresh_token"] = refresh,
            ["scope"] = Scope,
        }), ct);
        if (!resp.IsSuccessStatusCode)
        {
            SignOut();
            return null;
        }
        return await FinishAsync(JsonNode.Parse(await resp.Content.ReadAsStringAsync(ct))!, ct);
    }

    // ------------------------------------------------------------------
    // Chaîne Xbox → Minecraft
    // ------------------------------------------------------------------
    private async Task<MinecraftAccount> FinishAsync(JsonNode msToken, CancellationToken ct)
    {
        string accessToken = msToken["access_token"]!.GetValue<string>();
        string? refresh = msToken["refresh_token"]?.GetValue<string>();
        if (refresh != null)
        {
            Directory.CreateDirectory(Paths.DataDir);
            await File.WriteAllBytesAsync(TokenFile, Dpapi.Protect(Encoding.UTF8.GetBytes(refresh)), ct);
        }

        var xbl = await PostJsonAsync("https://user.auth.xboxlive.com/user/authenticate", new JsonObject
        {
            ["Properties"] = new JsonObject
            {
                ["AuthMethod"] = "RPS",
                ["SiteName"] = "user.auth.xboxlive.com",
                ["RpsTicket"] = "d=" + accessToken,
            },
            ["RelyingParty"] = "http://auth.xboxlive.com",
            ["TokenType"] = "JWT",
        }, ct);
        string xblToken = xbl["Token"]!.GetValue<string>();

        var xsts = await PostJsonAsync("https://xsts.auth.xboxlive.com/xsts/authorize", new JsonObject
        {
            ["Properties"] = new JsonObject { ["SandboxId"] = "RETAIL", ["UserTokens"] = new JsonArray(xblToken) },
            ["RelyingParty"] = "rp://api.minecraftservices.com/",
            ["TokenType"] = "JWT",
        }, ct, xstsErrors: true);
        string xstsToken = xsts["Token"]!.GetValue<string>();
        string uhs = xsts["DisplayClaims"]!["xui"]![0]!["uhs"]!.GetValue<string>();

        var mc = await PostJsonAsync("https://api.minecraftservices.com/authentication/login_with_xbox",
            new JsonObject { ["identityToken"] = $"XBL3.0 x={uhs};{xstsToken}" }, ct);
        string mcToken = mc["access_token"]!.GetValue<string>();

        var profileReq = new HttpRequestMessage(HttpMethod.Get, "https://api.minecraftservices.com/minecraft/profile");
        profileReq.Headers.Authorization = new("Bearer", mcToken);
        var profileResp = await Http.Client.SendAsync(profileReq, ct);
        if (!profileResp.IsSuccessStatusCode)
        {
            throw new InvalidOperationException("Ce compte Microsoft ne possède pas Minecraft Java Edition.");
        }
        var profile = JsonNode.Parse(await profileResp.Content.ReadAsStringAsync(ct))!;
        return new MinecraftAccount(profile["name"]!.GetValue<string>(), profile["id"]!.GetValue<string>(), mcToken, "0");
    }

    private static async Task<JsonNode> PostFormAsync(string url, Dictionary<string, string> form, CancellationToken ct)
    {
        var resp = await Http.Client.PostAsync(url, new FormUrlEncodedContent(form), ct);
        string body = await resp.Content.ReadAsStringAsync(ct);
        if (!resp.IsSuccessStatusCode)
        {
            throw new InvalidOperationException("Microsoft a refusé la demande. L'ID d'application est-il correct et validé ?");
        }
        return JsonNode.Parse(body)!;
    }

    private static async Task<JsonNode> PostJsonAsync(string url, JsonObject body, CancellationToken ct, bool xstsErrors = false)
    {
        var req = new HttpRequestMessage(HttpMethod.Post, url) { Content = JsonContent.Create(body) };
        req.Headers.Accept.ParseAdd("application/json");
        var resp = await Http.Client.SendAsync(req, ct);
        string text = await resp.Content.ReadAsStringAsync(ct);
        if (!resp.IsSuccessStatusCode)
        {
            if (xstsErrors && text.Contains("2148916233"))
            {
                throw new InvalidOperationException("Ce compte Microsoft n'a pas de profil Xbox : créez-le sur xbox.com, puis réessayez.");
            }
            if (xstsErrors && text.Contains("2148916238"))
            {
                throw new InvalidOperationException("Compte enfant : il doit être ajouté à une famille Microsoft par un adulte.");
            }
            throw new InvalidOperationException($"Connexion impossible ({(int)resp.StatusCode}) sur {new Uri(url).Host}.");
        }
        return JsonNode.Parse(text)!;
    }

    // Le chiffrement local (DPAPI, lié à la session Windows) est dans Dpapi.cs : il sert aussi aux clés
    // d'administration des serveurs distants.
}
