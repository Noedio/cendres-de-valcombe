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
        void Check(bool ok, string what, string? detail = null)
        {
            lines.Add((ok ? "OK     " : "ÉCHEC  ") + what + (detail != null ? " : " + detail : ""));
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
            Check(state.Mondes.Count == 6 && state.Mondes[0].Sorte == "hub" && state.Memoire.MaxMo > 0,
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

            await CheckGamesAsync(client, conn, Check, ct);
            await CheckBackupsAsync(client, api, Check, ct);

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

    /// <summary>
    /// Les parties, par le vrai client HTTP : le tableau (et l'état de chaque partie dans /admin/etat), la création d'une
    /// partie d'essai, la mise en suspens et la reprise, la suppression, et les refus (nom manquant, partie principale,
    /// partie lancée, partie inconnue, identifiant hors du modèle).
    /// </summary>
    private static async Task CheckGamesAsync(AdminClient client, IServerConnection conn, Checker Check, CancellationToken ct)
    {
        var board = await client.GetGamesAsync(ct);
        Check(board.Disponible && board.Parties.Count == 3 && board.Principale == "partie-principale" && board.EnCours == 2
                && board.Parties[0].Principale && board.Parties[0].Status == "en-cours" && board.Parties[0].Places == 12
                && board.Parties[1].Status == "ouverte" && board.Parties[1].JoueursTexte == "3 inscrits sur 16 places"
                && board.Parties[2].Status == "suspendue" && board.Parties[2].StatusText == "En suspens",
            $"GET /admin/parties : {board.Parties.Count} parties, la principale en cours, un essai ouvert, un en suspens");
        Check(board.Parties[0].JoueursTexte == "12 joueurs sur 12 places" && board.Parties[0].PhaseTexte == "Nuit 2"
                && board.Parties[0].Conteur == "Yoann" && board.Parties[0].Spectateurs == 1 && board.Parties[0].SorteTexte == "Partie principale",
            "Fiche d'une partie : phase, joueurs et places, Conteur, spectateurs");
        var state = await client.GetStateAsync(ct);
        Check(state.Parties.Count == 3 && state.Mondes.First(w => w.Id == "partie-principale").Partie?.Statut == "en-cours"
                && state.Mondes.First(w => w.Sorte == "hub").Partie == null,
            "GET /admin/etat : les parties, et l'état de la partie de chaque monde de partie");
        // Un serveur plus ancien n'envoie pas le statut : il se déduit des anciens champs.
        var old = new AdminGame { EnCours = true, EnPause = true, Assis = 7, Places = 0 };
        Check(old.Status == "en-pause" && old.StatusText == "En pause" && old.JoueursTexte == "7 joueurs",
            "Fiche d'un serveur plus ancien : statut déduit");

        var created = await client.CreateGameAsync("  Essai du samedi ", ct);
        Check(created.Partie?.Id == "essai-du-samedi" && created.Partie.Status == "ouverte" && created.Message.Contains("créée"),
            $"POST /admin/parties : partie d'essai créée ({created.Partie?.Id})");
        Check((await client.GetGamesAsync(ct)).Parties.Count == 4, "Elle paraît au tableau");
        var noName = await Expect(() => conn.SendAsync(System.Net.Http.HttpMethod.Post, "/admin/parties", "{}", ct)
            .ContinueWith(t => AdminClient.Read<AdminGameCreated>(t.Result), ct));
        Check(noName?.Status == 400 && noName.Message.Contains("nom"), "Créer sans nom : refusé (400)");
        bool refusedLocally = false;
        try
        {
            await client.CreateGameAsync(new string('x', AdminClient.MaxGameName + 1), ct);
        }
        catch (ArgumentException)
        {
            refusedLocally = true;
        }
        Check(refusedLocally && AdminClient.IsGameId("essai-1") && !AdminClient.IsGameId("../hub") && !AdminClient.IsGameId("Essai"),
            "Nom trop long et identifiant hors du modèle : refusés avant l'envoi");

        var suspended = await client.SuspendGameAsync("partie-principale", ct);
        Check(suspended.Suspendue && suspended.Status == "suspendue" && suspended.Message.Contains("suspens"),
            "POST /admin/parties/suspendre : la partie principale en suspens");
        var twice = await Expect(() => client.SuspendGameAsync("partie-principale", ct));
        Check(twice?.Status == 409, "Déjà en suspens : refusé (409)");
        var resumed = await client.ResumeGameAsync("partie-principale", ct);
        Check(!resumed.Suspendue && resumed.Status == "en-cours" && resumed.Message.Contains("rappelés"), "POST /admin/parties/reprendre : reprise");
        var notSuspended = await Expect(() => client.ResumeGameAsync("essai-1", ct));
        Check(notSuspended?.Status == 409, "Reprendre une partie qui n'est pas en suspens : refusé (409)");

        var main = await Expect(() => client.DeleteGameAsync("partie-principale", ct));
        Check(main?.Status == 409 && main.Message.Contains("ne se supprime pas"), "La partie principale ne se supprime pas (409)");
        var launched = await Expect(() => client.DeleteGameAsync("essai-moulin", ct));
        Check(launched?.Status == 409 && launched.Message.Contains("arrêtez-la"), "Une partie lancée, même en suspens, ne se supprime pas (409)");
        var unknown = await Expect(() => client.DeleteGameAsync("aucune", ct));
        Check(unknown?.Status == 404, "Partie inconnue : 404");
        var deleted = await client.DeleteGameAsync("essai-du-samedi", ct);
        Check(deleted.Ok && deleted.Message.Contains("supprimée") && (await client.GetGamesAsync(ct)).Parties.Count == 3,
            "POST /admin/parties/supprimer : la partie d'essai et son monde supprimés");
    }

    /// <summary>
    /// Les sauvegardes, par le vrai client HTTP : liste, réglages (refus compris), sauvegarde suivie jusqu'au bout,
    /// téléchargement contrôlé (zip, fiche, taille), restauration prévue, annulée, puis faite avec redémarrage.
    /// </summary>
    private static async Task CheckBackupsAsync(AdminClient client, DemoAdminApi api, Checker Check, CancellationToken ct)
    {
        api.BackupDuration = TimeSpan.FromSeconds(1.2);
        var list = await client.GetBackupsAsync(ct);
        Check(list.Nombre == 7 && list.Sauvegardes.Count == 7 && list.Sauvegardes.Count(b => !b.Lisible) == 1
                && list.Sauvegardes.Zip(list.Sauvegardes.Skip(1)).All(p => p.First.When >= p.Second.When)
                && list.RestaurationPossible && list.EnCours == null && list.EspaceLibre > 0,
            $"GET /admin/sauvegardes : {list.Nombre} archives, de la plus récente à la plus ancienne, une illisible signalée");
        var manual = list.Sauvegardes.First(b => b.Sorte == "manuelle");
        Check(!manual.Rotation && manual.Mondes.Count == 5 && manual.When != null && manual.SorteTexte == "Manuelle",
            "Archive manuelle : jamais effacée par la rotation, mondes et date lus");

        var settings = await client.GetBackupSettingsAsync(ct);
        Check(settings.IntervalBounds == (15, 10080) && settings.KeepBounds == (1, 500) && settings.Reglages.Actives
                && settings.Reglages.ALArret && settings.ParDefaut.IntervalleMinutes == 360,
            "GET /admin/sauvegardes/reglages : réglages, bornes et valeurs par défaut (champ aLArret compris)");
        var badSettings = await Expect(() => client.ChangeBackupSettingsAsync(new AdminBackupSettingsChange { Garder = 0, IntervalleMinutes = 5 }, ct));
        Check(badSettings?.Status == 400 && badSettings.Message.Contains("garder") && badSettings.Message.Contains("intervalleMinutes"),
            "Réglages hors bornes refusés en entier (400), avec toutes leurs fautes");
        var change = AdminBackupSettingsChange.Between(settings.Reglages,
            new AdminBackupSettingsValues { Actives = true, IntervalleMinutes = 180, ALArret = false, Garder = 20, PendantPartie = false });
        Check(change.ToJson() == "{\"intervalleMinutes\":180,\"aLArret\":false,\"garder\":20}", "Seuls les réglages changés partent", change.ToJson());
        var changed = await client.ChangeBackupSettingsAsync(change, ct);
        Check(changed.Ok && changed.Reglages.IntervalleMinutes == 180 && changed.Reglages.Garder == 20 && !changed.Reglages.ALArret
                && changed.Message.Contains("toutes les 3 heures"),
            "POST /admin/sauvegardes/reglages : appliqué, message rendu", changed.Message);

        var started = await client.StartBackupAsync(ct);
        Check(started.Ok && AdminBackupText.IsName(started.Nom) && started.EnCours != null, "POST /admin/sauvegardes : lancée (202), suivie par son nom", started.Nom);
        var twice = await Expect(() => client.StartBackupAsync(ct));
        Check(twice?.Status == 409, "Une seconde sauvegarde pendant la première : refusée (409)");
        var phases = new HashSet<string>();
        AdminBackups during;
        var clock = System.Diagnostics.Stopwatch.StartNew();
        do
        {
            during = await client.GetBackupsAsync(ct);
            if (during.EnCours != null)
            {
                phases.Add(during.EnCours.Phase);
                await Task.Delay(100, ct);
            }
        }
        while (during.EnCours != null && clock.Elapsed < TimeSpan.FromSeconds(10));
        Check(during.EnCours == null && during.Derniere is { Ok: true } && during.Derniere.Nom == started.Nom && during.Nombre == 8
                && phases.Contains("copie"),
            $"Sauvegarde suivie jusqu'au bout (phases : {string.Join(", ", phases)}), puis rangée dans la liste", during.Derniere?.Message);

        // Téléchargement, au fil de l'eau, puis contrôle de l'archive comme la page le fait.
        string folder = Path.Combine(Path.GetTempPath(), "valcombe-verif-admin-" + Guid.NewGuid().ToString("N")[..8]);
        Directory.CreateDirectory(folder);
        try
        {
            string file = Path.Combine(folder, started.Nom);
            long reported = 0;
            await using (var fs = File.Create(file))
            {
                await client.DownloadBackupAsync(started.Nom, fs, new Inline<(long Done, long? Total)>(p => reported = p.Done), ct);
            }
            var verdict = BackupArchive.Verify(file);
            Check(verdict.Ok && verdict.Worlds.Count == 5 && reported == new FileInfo(file).Length,
                "GET /admin/sauvegardes/<nom> : archive reçue en entier, zip et fiche contrôlés", verdict.Message);
            string damaged = list.Sauvegardes.First(b => !b.Lisible).Nom;
            string damagedFile = Path.Combine(folder, damaged);
            await using (var fs = File.Create(damagedFile))
            {
                await client.DownloadBackupAsync(damaged, fs, null, ct);
            }
            Check(!BackupArchive.Verify(damagedFile).Ok, "Archive abîmée téléchargée : le contrôle la refuse", BackupArchive.Verify(damagedFile).Message);
        }
        finally
        {
            Directory.Delete(folder, true);
        }
        var missing = await Expect(() => client.DownloadBackupAsync("valcombe-2001-01-01_00-00-00-auto.zip", Stream.Null, null, ct));
        Check(missing?.Status == 404 && missing.Message.Contains("Aucune sauvegarde"), "Archive inconnue : 404, sans rien écrire");
        bool refusedName;
        try
        {
            await client.DownloadBackupAsync("../admin.json", Stream.Null, null, ct);
            refusedName = false;
        }
        catch (ArgumentException)
        {
            refusedName = true;
        }
        Check(refusedName && !AdminBackupText.IsName("valcombe-2026-11-13_15-12-44-auto.zip/../x") && AdminBackupText.IsName("valcombe-2026-11-13_15-12-44-manuelle-2.zip"),
            "Nom hors modèle refusé avant l'envoi (chemin de l'API et du fichier protégés)");

        // Restauration : prévue, protégée de l'effacement, annulée, puis faite avec redémarrage.
        var target = during.Sauvegardes.First(b => b.Sorte == "auto" && b.Lisible);
        var planned = await client.RestoreAsync(target.Nom, restart: false, ct);
        var withPlan = await client.GetBackupsAsync(ct);
        Check(planned.Ok && !planned.Redemarrage && withPlan.RestaurationPrevue?.Nom == target.Nom, "Restauration prévue au prochain démarrage (200)");
        var locked = await Expect(() => client.DeleteBackupAsync(target.Nom, ct));
        Check(locked?.Status == 409, "Archive dont la restauration est prévue : effacement refusé (409)");
        var cancelled = await client.CancelRestoreAsync(ct);
        Check(cancelled.Annulee && (await client.GetBackupsAsync(ct)).RestaurationPrevue == null, "Restauration prévue annulée");
        var unreadable = await Expect(() => client.RestoreAsync(list.Sauvegardes.First(b => !b.Lisible).Nom, true, ct));
        Check(unreadable?.Status == 409, "Archive illisible : restauration refusée (409)");
        int restartsBefore = api.Restarts;
        var asked = DateTime.UtcNow;
        var restored = await client.RestoreAsync(target.Nom, restart: true, ct);
        var after = await client.GetBackupsAsync(ct);
        var report = after.DerniereRestauration;
        Check(restored.Redemarrage && restored.Drapeau != null && api.Restarts == restartsBefore + 1 && report is { Ok: true } && report.Nom == target.Nom
                && AdminBackupText.ParseDate(report.FaiteLe)?.UtcDateTime >= asked.AddSeconds(-1)
                && report.Securite != null && after.Sauvegardes.Any(b => b.Nom == report.Securite && b.Sorte == "avant-restauration"),
            "Restauration avec redémarrage (202) : compte rendu lu au retour, archive de sécurité présente", report?.Message);
        var deleted = await client.DeleteBackupAsync(manual.Nom, ct);
        Check(deleted.Ok && (await client.GetBackupsAsync(ct)).Sauvegardes.All(b => b.Nom != manual.Nom), "DELETE /admin/sauvegardes/<nom> : effacée");

        Check(AdminBackupText.Interval(360) == "toutes les 6 heures" && AdminBackupText.Interval(1440) == "une fois par jour"
                && AdminBackupText.Interval(90) == "toutes les 1 h 30" && AdminBackupText.Size(48_213_301) == "46 Mo"
                && AdminBackupText.Size(5_000_000) == "4,8 Mo" && AdminBackupText.When(DateTimeOffset.Now.AddDays(-1), DateTime.Now).StartsWith("hier à"),
            "Textes des sauvegardes : fréquence, taille et date en français");
    }

    /// <summary>Note une vérification : OK ou ÉCHEC, ce qui est vérifié, et un détail facultatif.</summary>
    private delegate void Checker(bool ok, string what, string? detail = null);

    /// <summary>Un suivi d'avancement appelé tout de suite, sur le fil qui rapporte (Progress&lt;T&gt; passerait par l'interface).</summary>
    private sealed class Inline<T> : IProgress<T>
    {
        private readonly Action<T> _report;

        public Inline(Action<T> report)
        {
            _report = report;
        }

        public void Report(T value) => _report(value);
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
            string? key = ctx.Request.Headers[DirectConnection.KeyHeader];
            // Une archive se télécharge comme le mod l'envoie : application/zip, taille exacte annoncée.
            byte[]? archive = null;
            AdminReply? refused = null;
            if (ctx.Request.HttpMethod == "GET" && api.TryDownload(ctx.Request.RawUrl ?? "/", key, out archive, out refused) && archive != null)
            {
                ctx.Response.StatusCode = 200;
                ctx.Response.ContentType = "application/zip";
                ctx.Response.ContentLength64 = archive.Length;
                await ctx.Response.OutputStream.WriteAsync(archive, ct);
                ctx.Response.Close();
                continue;
            }
            var reply = refused ?? api.Handle(ctx.Request.HttpMethod, ctx.Request.RawUrl ?? "/", body, key);
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
