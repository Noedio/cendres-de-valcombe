using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Valcombe.Launcher.Services.Admin;

/// <summary>
/// Vérification du client de l'API d'administration, sans Minecraft : <c>ValcombeLauncher.exe --verifier-admin rapport.txt</c>.
///
/// Un vrai serveur HTTP est ouvert sur ce PC (localhost, port libre tiré par le système, jamais un port connu),
/// il répond avec <see cref="DemoAdminApi"/> ; le launcher lui parle par <see cref="DirectConnection"/>, comme à un
/// vrai serveur. Chaque point du contrat est essayé, refus compris. Le rapport dit OK ou ÉCHEC pour chacun.
/// </summary>
public static class AdminSelfCheck
{
    public static async Task<List<string>> RunAsync()
    {
        var lines = new List<string>();
        int failures = 0;
        void Check(bool ok, string what)
        {
            lines.Add((ok ? "OK     " : "ÉCHEC  ") + what);
            if (!ok)
            {
                failures++;
            }
        }

        var api = new DemoAdminApi();
        int port = FreePort();
        using var listener = new HttpListener();
        listener.Prefixes.Add($"http://localhost:{port}/");
        listener.Start();
        using var stop = new CancellationTokenSource();
        var serving = Task.Run(() => ServeAsync(listener, api, stop.Token));
        lines.Add($"Serveur fictif sur localhost:{port}");
        try
        {
            await using var conn = new DirectConnection("localhost", port, () => DemoAdminApi.Key);
            var client = new AdminClient(conn);
            var ct = CancellationToken.None;

            var state = await client.GetStateAsync(ct);
            Check(state.Api == AdminClient.ContractVersion && state.Version.Length > 0 && state.DureeSecondes > 0,
                $"GET /admin/etat : contrat {state.Api}, mod {state.Version}, en ligne depuis {state.DureeSecondes} s");
            Check(state.Mondes.Count == 5 && state.Mondes[0].Sorte == "hub" && state.Memoire.MaxMo > 0,
                $"Mondes ({state.Mondes.Count}) et mémoire ({state.Memoire.UtiliseeMo}/{state.Memoire.MaxMo} Mo) lus");

            var players = await client.GetPlayersAsync(ct);
            Check(players.Connectes == players.Joueurs.Count && players.Joueurs.Any(p => p.Operateur),
                $"GET /admin/joueurs : {players.Connectes} connectés, dont un opérateur");

            var feed = new ConsoleFeed();
            var first = await feed.PollAsync(client, ct);
            Check(first.Reset && first.Lines.Count == 20, $"Console, première lecture : {first.Lines.Count} lignes");
            var none = await feed.PollAsync(client, ct);
            Check(!none.Reset && none.Lines.Count == 0, "Console, lecture suivante sans rien de neuf : 0 ligne");

            var said = await client.RunCommandAsync("/say Bonsoir", ct);
            Check(said.Ok && said.Sortie.SequenceEqual(new[] { "[Server] Bonsoir" }), "POST /admin/commande « say » : réussie, sortie rendue");
            var after = await feed.PollAsync(client, ct);
            Check(!after.Reset && after.Lines.Count == 2 && after.Lines[^1].Texte.Contains("Bonsoir"),
                $"Console en direct : {after.Lines.Count} lignes nouvelles après la commande");
            var unknown = await client.RunCommandAsync("tp partout", ct);
            Check(!unknown.Ok && unknown.Sortie.Count == 1, "Commande inconnue : ok = faux, message rendu");

            var settings = await client.GetSettingsAsync(ct);
            Check(settings.ListeBlanche.Active && settings.Operateurs.Count == 1 && settings.CanChange("port"),
                $"GET /admin/reglages : liste blanche de {settings.ListeBlanche.Joueurs.Count}, {settings.Operateurs.Count} opérateur");
            var change = new AdminSettingsChange { Welcome = "Bienvenue au bourg", MaxPlayers = 16 };
            change.WhitelistAdd.Add("Zoe");
            change.WhitelistAdd.Add("Zzqx");
            var applied = await client.ChangeSettingsAsync(change, ct);
            Check(!applied.Ok && applied.Resultats.Count == 4 && applied.Resultats.Count(r => r.Ok) == 3
                && applied.Reglages?.JoueursMax == 16 && applied.Reglages.ListeBlanche.Joueurs.Contains("Zoe"),
                "POST /admin/reglages : appliqué point par point, le compte inconnu seul refusé");
            var portChange = new AdminSettingsChange { Port = 25600 };
            var port2 = await client.ChangeSettingsAsync(portChange, ct);
            Check(port2.RedemarrageNecessaire && port2.Reglages?.EnAttente.Contains("port") == true,
                "Port changé : redémarrage nécessaire signalé");
            var bad = new AdminSettingsChange { Welcome = new string('x', 61) };
            bad.OperatorsAdd.Add("pas un nom");
            var refused = await Expect(() => client.ChangeSettingsAsync(bad, ct));
            Check(refused?.Status == 400 && refused.Message.Contains("Nom de joueur invalide"),
                "Demande fautive refusée en entier (400), avec toutes ses fautes");

            var restart = await client.RestartAsync(ct);
            Check(restart.Ok && restart.Drapeau == "config/valcombe/redemarrer", "POST /admin/redemarrer : accepté (202), drapeau posé");
            var reborn = await feed.PollAsync(client, ct);
            Check(reborn.Reset && reborn.Lines.Count > 0 && reborn.Lines[0].N == 1,
                "Console après redémarrage : nouvelle instance reconnue, lecture reprise au début");

            var notFound = await Expect(() => conn.SendAsync(System.Net.Http.HttpMethod.Get, "/admin/inconnu", null, ct)
                .ContinueWith(t => AdminClient.Read<AdminState>(t.Result), ct));
            Check(notFound?.Status == 404 && notFound.StopsPolling, "Point d'accès inconnu : 404, la page cesse d'insister");

            var stopped = await client.StopAsync(ct);
            Check(stopped.Ok && stopped.Drapeau == null, "POST /admin/arreter : accepté (202), sans drapeau");
            var down = await Expect(() => client.GetStateAsync(ct));
            Check(down?.Status == 503 && !down.StopsPolling, "Serveur qui s'arrête : 503, la page réessaiera");

            // En dernier : après cinq clés fausses, le serveur bloque toutes les requêtes de cette adresse.
            await using (var wrong = new DirectConnection("localhost", port, () => "mauvaise_cle_0000000000000000000000000000"))
            {
                var wrongClient = new AdminClient(wrong);
                var denied = await Expect(() => wrongClient.GetStateAsync(ct));
                Check(denied?.Status == 401 && denied.StopsPolling && denied.Message.Contains("/vc admin cle"),
                    "Mauvaise clé : 401, message qui dit où retrouver la bonne");
                for (int i = 0; i < 4; i++)
                {
                    await Expect(() => wrongClient.GetStateAsync(ct));
                }
                var blocked = await Expect(() => wrongClient.GetStateAsync(ct));
                Check(blocked?.Status == 429 && blocked.RetryAfter == TimeSpan.FromSeconds(30),
                    $"Cinq clés fausses : 429, nouvel essai dans {blocked?.RetryAfter?.TotalSeconds} s");
            }

            await using (var nobody = new DirectConnection("127.0.0.1", FreePort(), () => DemoAdminApi.Key))
            {
                var gone = await Expect(() => new AdminClient(nobody).GetStateAsync(ct));
                Check(gone?.Status == 0 && gone.Message.Contains("Rien ne répond"), "Port fermé : « Rien ne répond », sans planter");
            }

            string secret = DemoAdminApi.Key;
            string sealedKey = Dpapi.ProtectText(secret);
            Check(!sealedKey.Contains(secret) && Dpapi.UnprotectText(sealedKey) == secret && Dpapi.UnprotectText("abîmé") == null,
                "Clé chiffrée avec DPAPI : relue sur ce PC, illisible en clair, un chiffré abîmé donne null");
            string salted = Dpapi.ProtectText(secret, "valcombe-ssh");
            Check(Dpapi.UnprotectText(salted, "valcombe-ssh") == secret && Dpapi.UnprotectText(salted) == null,
                "DPAPI salé (secrets SSH) à côté du DPAPI sans sel (clés d'administration) : chacun ne se relit que par sa voie");

            // Tunnel SSH : la fiche d'un serveur loué passe par SshConnection ; sans son profil, une explication.
            var orphan = new RemoteServer { Name = "Loué", Host = "vps.exemple.fr", Access = RemoteServer.AccessSsh, VpsId = Guid.NewGuid().ToString("N") };
            await using (var orphanConn = ServerConnections.Open(orphan))
            {
                var lost = await Expect(() => new AdminClient(orphanConn).GetStateAsync(ct));
                Check(orphan.KeyFromServer && orphanConn is FailedConnection && orphanConn.Description.Contains("tunnel SSH")
                    && lost?.Status == 0 && lost.Message.Contains("serveurs-loues.json"),
                    "Serveur loué sans profil SSH : connexion qui l'explique, sans planter ni demander de clé");
            }
            var profile = new Ssh.VpsProfile { Name = "Essai", Host = "vps.exemple.fr", User = "debian" };
            await using (var ssh = new SshConnection(profile))
            {
                Check(ssh.CanStartServer && ssh.Description == "tunnel SSH vers debian@vps.exemple.fr",
                    "Tunnel SSH : décrit par son compte et son hôte, sait démarrer le serveur arrêté");
            }
            var vanilla = ConsoleFeed.ParseProcessLine("[21:04:13] [Server thread/WARN]: Can't keep up!");
            var fabric = ConsoleFeed.ParseProcessLine("[21:04:13] [main/INFO] (FabricLoader) Loading 9 mods");
            Check(vanilla == ("21:04:13", "WARN", "Can't keep up!") && fabric == ("21:04:13", "INFO", "Loading 9 mods")
                && ConsoleFeed.ParseProcessLine("\tat java.lang.Thread.run(Thread.java:1583)") == null,
                "Journal du serveur de ce PC (sans API) : lignes de Minecraft et de Fabric découpées");
            Check(AdminClient.IsKey(DemoAdminApi.Key) && !AdminClient.IsKey("courte") && AdminClient.IsPlayerName("Lea_2")
                && !AdminClient.IsPlayerName("Léa"), "Formats : clé et pseudo vérifiés avant l'envoi");
        }
        catch (Exception ex)
        {
            failures++;
            lines.Add("ÉCHEC  Exception : " + ex);
        }
        finally
        {
            stop.Cancel();
            listener.Stop();
            try
            {
                await serving;
            }
            catch (Exception)
            {
                // le serveur fictif s'arrête en lâchant sa dernière attente
            }
        }
        lines.Add(failures == 0 ? "RÉSULTAT : tout est bon." : $"RÉSULTAT : {failures} échec(s).");
        return lines;
    }

    private static async Task<AdminException?> Expect(Func<Task> call)
    {
        try
        {
            await call();
            return null;
        }
        catch (AdminException ex)
        {
            return ex;
        }
        catch (AggregateException ex) when (ex.InnerException is AdminException inner)
        {
            return inner;
        }
    }

    /// <summary>Un port libre tiré par le système (plage éphémère : jamais 25565, 25570 ni 24454).</summary>
    private static int FreePort()
    {
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        int port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return port;
    }

    private static async Task ServeAsync(HttpListener listener, DemoAdminApi api, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            HttpListenerContext ctx;
            try
            {
                ctx = await listener.GetContextAsync();
            }
            catch (Exception ex) when (ex is HttpListenerException or ObjectDisposedException or InvalidOperationException)
            {
                return;
            }
            string? body = null;
            if (ctx.Request.HasEntityBody)
            {
                using var reader = new StreamReader(ctx.Request.InputStream, Encoding.UTF8);
                body = await reader.ReadToEndAsync(ct);
            }
            var reply = api.Handle(ctx.Request.HttpMethod, ctx.Request.RawUrl ?? "/", body, ctx.Request.Headers[DirectConnection.KeyHeader]);
            ctx.Response.StatusCode = reply.Status;
            ctx.Response.ContentType = "application/json; charset=utf-8";
            if (reply.RetryAfter is { } wait)
            {
                ctx.Response.Headers["Retry-After"] = ((int)wait.TotalSeconds).ToString();
            }
            byte[] bytes = Encoding.UTF8.GetBytes(reply.Body);
            ctx.Response.ContentLength64 = bytes.Length;
            await ctx.Response.OutputStream.WriteAsync(bytes, ct);
            ctx.Response.Close();
        }
    }
}
