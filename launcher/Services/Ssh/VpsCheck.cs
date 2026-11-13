using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Valcombe.Launcher.Services.Ssh;

/// <summary>
/// Vérifications de la couche SSH sans fenêtre, en ligne de commande :
/// <list type="bullet">
/// <item><c>ValcombeLauncher.exe --verifier-vps rapport.txt [--sans-telechargement]</c> : contrôles internes
/// (profils, chiffrement, empreintes d'hôte, plan d'installation de la dernière version publiée) puis installation
/// à blanc, mise à jour et pilotage du service par le vrai script, joués par le bash de Git pour Windows dans un
/// dossier temporaire (faux systemctl, faux journalctl). Aucun VPS, aucun port ouvert.</item>
/// <item><c>ValcombeLauncher.exe --essai-vps rapport.txt --hote vps.exemple.fr --utilisateur root
/// (--cle id_ed25519 [--phrase-env VAR] | --mot-de-passe-env VAR) [--port 22] [--empreinte SHA256:...]
/// [--installer --contrat-accepte] [--demarrer]</c> : essai sur un vrai VPS (lecture seule sans --installer).
/// Le profil d'essai n'est pas enregistré ; l'empreinte acceptée l'est, comme dans le launcher.</item>
/// </list>
/// </summary>
public static class VpsCheck
{
    private sealed class Report
    {
        public readonly List<string> Lines = new();
        public int Checks;
        public int Failures;

        public void Note(string text) => Lines.Add(text);

        public bool Check(bool ok, string what, string? detail = null)
        {
            Checks++;
            if (!ok)
            {
                Failures++;
            }
            Lines.Add((ok ? "OK      " : "ÉCHEC   ") + what + (detail != null ? " : " + detail : ""));
            return ok;
        }

        public void Throws(Action action, string what, string? mustContain = null)
        {
            try
            {
                action();
                Check(false, what, "aucune erreur levée");
            }
            catch (Exception ex)
            {
                Check(mustContain == null || ex.Message.Contains(mustContain, StringComparison.Ordinal), what, ex.Message);
            }
        }

        public async Task ThrowsAsync(Func<Task> action, string what, string? mustContain = null)
        {
            try
            {
                await action();
                Check(false, what, "aucune erreur levée");
            }
            catch (Exception ex)
            {
                Check(mustContain == null || ex.Message.Contains(mustContain, StringComparison.Ordinal), what, ex.Message);
            }
        }
    }

    private static VpsProfile SampleProfile()
    {
        var p = new VpsProfile { Name = "Essai", Host = "vps.exemple.fr", User = "root", EulaAccepted = true };
        p.SetPassword("mot-de-passe-d'essai");
        return p;
    }

    // ------------------------------------------------------------------
    // --verifier-vps
    // ------------------------------------------------------------------
    public static async Task VerifyAsync(string reportFile, LauncherConfig config, bool download)
    {
        var r = new Report();
        r.Note($"Vérification de la couche SSH du launcher, {DateTime.Now:yyyy-MM-dd HH:mm}");
        string work = Path.Combine(Path.GetTempPath(), "valcombe-verif-vps-" + Convert.ToHexString(RandomNumberGenerator.GetBytes(4)).ToLowerInvariant());
        Directory.CreateDirectory(work);
        try
        {
            r.Note("");
            r.Note("== Contrôles internes ==");
            CheckProfiles(r);
            CheckSecrets(r);
            CheckKnownHosts(r, Path.Combine(work, "hotes.json"));
            CheckSystemParsing(r);
            CheckProperties(r);

            r.Note("");
            r.Note("== Plan d'installation de la dernière version publiée ==");
            var release = await Releases.LatestAsync(config.Repository);
            r.Note($"Version {release.Tag} : Minecraft {release.Manifest.Minecraft}, Fabric {release.Manifest.Loader}");
            var profile = SampleProfile();
            var files = new List<PreparedFile> { new("server.properties", "server.properties", "640", "server-port=25565\n") };
            var x86 = await VpsPlan.BuildAsync(release, profile, "x86_64", files);
            r.Check(x86.JavaSource == "Mojang" && x86.JavaFileCount > 50, "Plan x86_64 : Java de Mojang",
                $"{x86.JavaName}, {x86.JavaFileCount} fichiers, {x86.Mods.Count} mods, {x86.DownloadBytes / 1048576} Mo");
            r.Check(x86.Mods.Count == LocalServer.ServerMods(release.Manifest).Count && x86.Mods.All(m => m.Side != "client"),
                "Plan : les mods du serveur local, sans les mods graphiques", string.Join(", ", x86.Mods.Select(m => m.Name)));
            var arm = await VpsPlan.BuildAsync(release, profile, "aarch64", files);
            r.Check(arm.JavaSource == "Adoptium" && arm.Text.Contains("java-archive\t"), "Plan aarch64 : Java d'Adoptium", arm.JavaName);
            await r.ThrowsAsync(() => VpsPlan.BuildAsync(release, profile, "riscv64", files), "Plan refusé pour un processeur inconnu", "riscv64");
            await r.ThrowsAsync(() => VpsPlan.BuildAsync(release, profile, "x86_64",
                    new[] { new PreparedFile("../etc/passwd", "x", "644", "") }),
                "Plan refusé pour un fichier de réglages hors du dossier", "Chemin refusé");

            r.Note("");
            r.Note("== Installation à blanc par le vrai script (bash de Git pour Windows) ==");
            string? bash = LocalBashShell.FindBash();
            if (bash == null)
            {
                r.Check(false, "bash de Git pour Windows", "introuvable : installation à blanc impossible sur ce PC");
            }
            else
            {
                await DryRunAsync(r, bash, work, release, download);
            }
        }
        catch (Exception ex)
        {
            r.Check(false, "Vérification interrompue", ex.ToString());
        }
        r.Note("");
        r.Note($"Résultat : {r.Checks} vérifications, {r.Failures} échec(s).");
        if (r.Failures == 0)
        {
            TryDelete(work);
        }
        else
        {
            r.Note("Dossier de l'installation à blanc gardé pour examen : " + work);
        }
        await File.WriteAllLinesAsync(reportFile, r.Lines);
    }

    private static void TryDelete(string dir)
    {
        try
        {
            Directory.Delete(dir, true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Un fichier encore ouvert : le dossier temporaire sera nettoyé par Windows.
        }
    }

    private static void CheckProfiles(Report r)
    {
        var p = SampleProfile();
        r.Check(p.Problems().Count == 0, "Profil par défaut valide", string.Join(" ", p.Problems()));
        r.Check(p.MinecraftPort == 25565 && p.VoicePort == 24454 && p.AdminPort == 0 && p.InstallDir == "/opt/valcombe",
            "Ports et dossier par défaut du VPS (25565, 24454, API du mod)");
        foreach (string dir in new[] { "/", "/opt", "/etc/valcombe", "/opt/../etc", "/opt/val combe", "opt/valcombe", "/opt/valcombe/", "/usr/local/x", "/opt/a;rm -rf" })
        {
            var bad = SampleProfile();
            bad.InstallDir = dir;
            r.Check(bad.Problems().Count > 0, $"Dossier refusé : « {dir} »");
        }
        foreach (string dir in new[] { "/srv/valcombe", "/home/jeu/valcombe", "/var/lib/valcombe" })
        {
            var good = SampleProfile();
            good.InstallDir = dir;
            r.Check(good.Problems().Count == 0, $"Dossier accepté : « {dir} »", string.Join(" ", good.Problems()));
        }
        var root = SampleProfile();
        root.ServiceUser = "root";
        r.Check(root.Problems().Count > 0, "Compte du serveur root refusé");
        var injection = SampleProfile();
        injection.ServiceName = "valcombe;reboot";
        r.Check(injection.Problems().Count > 0, "Nom de service avec « ; » refusé");
        var same = SampleProfile();
        same.VoicePort = 25565;
        r.Check(same.Problems().Count > 0, "Ports identiques refusés");
        var low = SampleProfile();
        low.MinecraftPort = 80;
        r.Check(low.Problems().Count > 0, "Port du jeu sous 1024 refusé");
        var host = SampleProfile();
        host.Host = "vps.exemple.fr; rm -rf /";
        r.Check(host.Problems().Count > 0, "Adresse avec une commande refusée");
        var nokey = SampleProfile();
        nokey.Auth = SshAuthKind.PrivateKey;
        r.Check(nokey.Problems().Count > 0, "Connexion par clé sans clé refusée");
    }

    private static void CheckSecrets(Report r)
    {
        var p = SampleProfile();
        r.Check(p.GetPassword() == "mot-de-passe-d'essai" && !p.PasswordProtected.Contains("mot-de-passe"),
            "Mot de passe chiffré (DPAPI) puis relu");
        r.Check(p.GetSudoPassword() == "mot-de-passe-d'essai", "Sans mot de passe sudo : celui de la connexion");
        p.SetSudoPassword("autre");
        r.Check(p.GetSudoPassword() == "autre", "Mot de passe sudo à part");
        string json = System.Text.Json.JsonSerializer.Serialize(p);
        r.Check(!json.Contains("mot-de-passe-d'essai") && !json.Contains("autre\""), "Aucun secret en clair dans le JSON du profil");
        r.Throws(() => Dpapi.UnprotectText(p.PasswordProtected, "autre-usage"), "Un secret ne se déchiffre pas pour un autre usage", "illisible");
        // Le jeton Microsoft est chiffré sans entropie, comme avant le déplacement de Dpapi.
        byte[] raw = Encoding.UTF8.GetBytes("jeton");
        r.Check(Encoding.UTF8.GetString(Dpapi.Unprotect(Dpapi.Protect(raw))) == "jeton", "Chiffrement sans entropie (jeton Microsoft) inchangé");

        using var rsa = RSA.Create(2048);
        var withKey = SampleProfile();
        withKey.Auth = SshAuthKind.PrivateKey;
        try
        {
            withKey.SetPrivateKey(rsa.ExportRSAPrivateKeyPem(), "");
            r.Check(withKey.GetPrivateKey() != null && !withKey.PrivateKeyProtected.Contains("PRIVATE KEY"), "Clé privée RSA lue, gardée chiffrée");
        }
        catch (Exception ex)
        {
            r.Check(false, "Clé privée RSA lue, gardée chiffrée", ex.Message);
        }
        r.Throws(() => withKey.SetPrivateKey("ceci n'est pas une clé", ""), "Clé illisible refusée", "Clé privée illisible");
        string encrypted = rsa.ExportEncryptedPkcs8PrivateKeyPem("bonne-phrase",
            new PbeParameters(PbeEncryptionAlgorithm.Aes256Cbc, HashAlgorithmName.SHA256, 100000));
        try
        {
            withKey.SetPrivateKey(encrypted, "bonne-phrase");
            r.Check(true, "Clé chiffrée lue avec sa phrase de passe");
            r.Throws(() => withKey.SetPrivateKey(encrypted, "mauvaise"), "Clé chiffrée refusée avec une mauvaise phrase de passe");
        }
        catch (Exception ex)
        {
            r.Note("        (clé PKCS#8 chiffrée non lue par SSH.NET : " + ex.Message + " ; les clés OpenSSH et PuTTY restent prises en charge)");
        }
    }

    private static void CheckKnownHosts(Report r, string file)
    {
        var hosts = new KnownHosts(file);
        const string Fp = "ohD8VZEXGWo6Ez8GSEJQ9WpafgLFsOfLOtGGQCQo6Og";
        r.Check(hosts.Check("VPS.exemple.fr", 22, Fp) == KnownHosts.Verdict.Unknown, "Empreinte : premier contact inconnu");
        hosts.Trust("VPS.exemple.fr", 22, "ssh-ed25519", Fp);
        r.Check(new KnownHosts(file).Check("vps.exemple.fr", 22, "SHA256:" + Fp) == KnownHosts.Verdict.Known,
            "Empreinte retenue et relue (nom sans casse, préfixe SHA256:)");
        r.Check(hosts.Check("vps.exemple.fr", 22, "AAAA" + Fp[4..]) == KnownHosts.Verdict.Changed, "Clé changée : refusée");
        r.Check(hosts.Check("vps.exemple.fr", 2222, Fp) == KnownHosts.Verdict.Unknown, "Autre port : autre serveur");
        var unknown = new SshHostKeyUnknownException("vps.exemple.fr", 22, "ssh-ed25519", Fp);
        r.Check(unknown.Fingerprint == "SHA256:" + Fp && unknown.Message.Contains(unknown.Fingerprint), "Premier contact : l'empreinte est rendue à l'interface");
        hosts.Forget("vps.exemple.fr", 22);
        r.Check(hosts.Check("vps.exemple.fr", 22, Fp) == KnownHosts.Verdict.Unknown, "Empreinte oubliée");
    }

    private static void CheckSystemParsing(Report r)
    {
        var p = SampleProfile();
        const string Debian = "noyau=Linux\narch=x86_64\ndistrib=debian\ndistrib_nom=Debian GNU/Linux 12 (bookworm)\ndistrib_version=12\n"
            + "distrib_famille=\nsystemd=oui\ncurl=oui\nwget=non\nbash=oui\nmemoire_ko=8000000\ndisque_ko=40000000\n";
        var ok = VpsSystemInfo.Parse(Debian, RootAccess.Root, p);
        r.Check(ok.CanInstall && ok.Warnings.Count == 0 && ok.MemoryMb > 7000, "Système : Debian 12 x86_64 accepté", ok.Summary);
        var arm = VpsSystemInfo.Parse(Debian.Replace("x86_64", "aarch64"), RootAccess.SudoWithoutPassword, p);
        r.Check(arm.CanInstall, "Système : Linux ARM (aarch64) accepté");
        var win = VpsSystemInfo.Parse("noyau=MINGW64_NT-10.0\narch=x86_64\nsystemd=non\nbash=oui\n", RootAccess.Root, p);
        r.Check(!win.CanInstall, "Système : autre que Linux refusé", string.Join(" ", win.Problems));
        var nosd = VpsSystemInfo.Parse(Debian.Replace("systemd=oui", "systemd=non"), RootAccess.Root, p);
        r.Check(!nosd.CanInstall, "Système : sans systemd refusé");
        var noroot = VpsSystemInfo.Parse(Debian, RootAccess.None, p);
        r.Check(!noroot.CanInstall, "Système : compte sans droits d'administration refusé");
        var small = VpsSystemInfo.Parse(Debian.Replace("memoire_ko=8000000", "memoire_ko=2000000").Replace("disque_ko=40000000", "disque_ko=900000"), RootAccess.Root, p);
        r.Check(small.CanInstall && small.Warnings.Count == 2, "Système : peu de mémoire et de disque signalés", string.Join(" ", small.Warnings));
    }

    private static void CheckProperties(Report r)
    {
        var fresh = LocalServer.MergeProperties(null, new[] { ("server-port", "25600") }, new[] { ("white-list", "true") });
        r.Check(fresh.Contains("server-port=25600") && fresh.Contains("white-list=true") && fresh.Contains(@"level-type=minecraft\:flat"),
            "server.properties neuf : monde plat, port, liste blanche");
        var kept = LocalServer.MergeProperties(new[] { "#Minecraft", "max-players=16", "server-port=25565", "white-list=false" },
            new[] { ("server-port", "25600") }, new[] { ("white-list", "true") });
        r.Check(kept.SequenceEqual(new[] { "max-players=16", "server-port=25600", "white-list=false" }),
            "server.properties existant : seuls les réglages du launcher changent", string.Join(" | ", kept));
    }

    // ------------------------------------------------------------------
    // Installation à blanc
    // ------------------------------------------------------------------
    private static string WriteStub(string dir, string name, string text)
    {
        string path = Path.Combine(dir, name);
        File.WriteAllText(path, text.Replace("\r\n", "\n"), new UTF8Encoding(false));
        return path;
    }

    private static async Task DryRunAsync(Report r, string bash, string work, Release release, bool download)
    {
        string sandbox = Path.Combine(work, "racine");
        string stubs = Path.Combine(work, "outils");
        Directory.CreateDirectory(Path.Combine(sandbox, "etc"));
        Directory.CreateDirectory(Path.Combine(sandbox, "run", "systemd", "system"));
        Directory.CreateDirectory(Path.Combine(sandbox, "opt"));
        Directory.CreateDirectory(stubs);
        File.WriteAllText(Path.Combine(sandbox, "etc", "os-release"),
            "PRETTY_NAME=\"Debian GNU/Linux 12 (bookworm)\"\nNAME=\"Debian GNU/Linux\"\nVERSION_ID=\"12\"\nID=debian\n");
        string root = LocalBashShell.ToBashPath(sandbox);
        string stubDir = LocalBashShell.ToBashPath(stubs);
        WriteStub(stubs, "uname", "#!/bin/sh\ncase \"$1\" in -s) echo Linux ;; -m) echo x86_64 ;; *) echo Linux ;; esac\n");
        WriteStub(stubs, "systemctl", $$"""
            #!/bin/sh
            # Faux systemctl de la vérification : retient l'état du service dans un fichier.
            echo "systemctl $*" >> "{{stubDir}}/appels.log"
            etat="{{stubDir}}/etat"
            [ -f "$etat" ] || echo inactive > "$etat"
            case "$1" in
              show)
                if [ -f "{{root}}/etc/systemd/system/$2" ]; then echo LoadState=loaded; echo UnitFileState=enabled; else echo LoadState=not-found; echo UnitFileState=; fi
                echo "ActiveState=$(cat "$etat")"; echo SubState=running
                echo "ExecMainStartTimestamp=Fri 2026-11-13 09:12:44 UTC"; echo NRestarts=0 ;;
              start|restart) echo active > "$etat" ;;
              stop) echo inactive > "$etat" ;;
              is-active) cat "$etat" ;;
            esac
            """);
        WriteStub(stubs, "ss", $$"""
            #!/bin/sh
            # Faux ss : le port du jeu écoute quand le faux service tourne.
            echo "State  Recv-Q Send-Q Local Address:Port Peer Address:Port"
            if [ "$(cat "{{stubDir}}/etat" 2>/dev/null)" = active ]; then echo "LISTEN 0      50     *:25565            *:*"; fi
            """);
        WriteStub(stubs, "journalctl", $$"""
            #!/bin/sh
            echo "journalctl $*" >> "{{stubDir}}/appels.log"
            echo "2026-11-13T09:12:44+0000 vps demarrer.sh[812]: [09:12:44] [Server thread/INFO]: Starting minecraft server version 26.3"
            echo "2026-11-13T09:13:30+0000 vps demarrer.sh[812]: [09:13:30] [Server thread/INFO]: API d'administration prête sur le port 25570"
            echo "2026-11-13T09:13:31+0000 vps demarrer.sh[812]: [09:13:31] [Server thread/INFO]: Done (46.2s)! For help, type \"help\""
            """);

        using var shell = new LocalBashShell(bash, sandbox, stubs);
        var quoteTest = "a'b\"c$d`e\\f g;h";
        var echo = await shell.RunAsync("printf '%s' " + Shell.Quote(quoteTest));
        r.Check(echo.Output.TrimEnd('\n') == quoteTest,"Valeurs protégées dans les commandes (apostrophes, $, `, ;)", echo.Output);
        await shell.UploadAsync("/tmp/valcombe-syntaxe-envoi.sh", Encoding.UTF8.GetBytes(VpsServer.ScriptText()));
        var parse = await shell.RunAsync("bash -n /tmp/valcombe-syntaxe-envoi.sh; s=$?; rm -f /tmp/valcombe-syntaxe-envoi.sh; exit $s");
        r.Check(parse.Ok && !VpsServer.ScriptText().Contains('\r'), "Script embarqué : syntaxe bash correcte, fins de ligne Unix", parse.Message);

        var profile = SampleProfile();
        using var server = new VpsServer(profile, shell);
        var system = await server.DetectAsync();
        r.Check(system.CanInstall && system.Kernel == "Linux" && system.DistroId == "debian" && system.Systemd,
            "Détection du système (faux Debian)", system.Summary + (system.Warnings.Count > 0 ? " ; " + string.Join(" ", system.Warnings) : ""));

        var status0 = await server.StatusAsync();
        r.Check(!status0.Installed && status0.Text == "Pas installé", "État avant installation : pas installé");
        await r.ThrowsAsync(() => server.StartAsync(), "Démarrer avant d'installer : refusé", "pas installé");
        var noEula = SampleProfile();
        noEula.EulaAccepted = false;
        await r.ThrowsAsync(() => new VpsServer(noEula, shell).InstallAsync(release), "Installer sans contrat accepté : refusé", "contrat");

        // Première installation.
        const string CountStages = "ls -d /tmp/valcombe-installation.* 2>/dev/null | wc -l";
        string stagesBefore = (await shell.RunAsync(CountStages)).Output.Trim();
        var steps = new List<string>();
        var progress = new Progress<(string, double)>(p => steps.Add(p.Item1));
        var clock = System.Diagnostics.Stopwatch.StartNew();
        var first = await server.InstallAsync(release, progress, download);
        r.Check(first.Changed, "Première installation", $"{clock.Elapsed.TotalSeconds:0} s, « {first.Summary} »");
        r.Note("        Journal de l'installation :");
        foreach (string line in first.Log.Where(l => !l.StartsWith("[à blanc] chown", StringComparison.Ordinal)).Take(40))
        {
            r.Note("          " + line);
        }
        string dir = Path.Combine(sandbox, "opt", "valcombe");
        string unit = Path.Combine(sandbox, "etc", "systemd", "system", "valcombe.service");
        r.Check(File.Exists(unit), "Service systemd écrit");
        string unitText = File.Exists(unit) ? File.ReadAllText(unit) : "";
        string javaName = (await Engine.JavaRequirementAsync(release.Manifest.Minecraft)).Component;
        r.Check(unitText.Contains("User=valcombe") && unitText.Contains("ExecStart=/opt/valcombe/demarrer.sh")
                && unitText.Contains($"Environment=VALCOMBE_JAVA=/opt/valcombe/java/{javaName}/bin/java")
                && unitText.Contains("Environment=VALCOMBE_RAM=4096M") && unitText.Contains("ProtectHome=true"),
            "Service : compte dédié, Java installé, mémoire, protections");
        string props = File.Exists(Path.Combine(dir, "server.properties")) ? File.ReadAllText(Path.Combine(dir, "server.properties")) : "";
        r.Check(props.Contains("server-port=25565\n") && props.Contains("white-list=true") && props.Contains("enforce-whitelist=true")
                && !props.Contains('\r'), "server.properties : port, liste blanche, fins de ligne Unix");
        r.Check(File.Exists(Path.Combine(dir, "eula.txt")) && File.ReadAllText(Path.Combine(dir, "eula.txt")).Contains("eula=true"), "Contrat écrit (eula.txt)");
        string start = File.Exists(Path.Combine(dir, "demarrer.sh")) ? File.ReadAllText(Path.Combine(dir, "demarrer.sh")) : "";
        r.Check(start.Contains("config/valcombe/redemarrer") && start.Contains("VALCOMBE_JAVA"), "demarrer.sh : relance après /admin/redemarrer");
        string stamp = File.Exists(Path.Combine(dir, "valcombe-installe.txt")) ? File.ReadAllText(Path.Combine(dir, "valcombe-installe.txt")) : "";
        r.Check(stamp.Contains("version=" + release.Tag), "Version installée notée", stamp.Replace("\n", " "));
        r.Check(first.Log.Any(l => l.Contains("[à blanc] useradd --system")) && first.Log.Any(l => l.Contains("[à blanc] systemctl enable valcombe")),
            "Compte dédié et activation du service demandés (affichés à blanc)");
        r.Check(first.Log.Any(l => l.StartsWith("[à blanc] runuser -u valcombe -- bash install-valcombe.sh --phase-compte", StringComparison.Ordinal))
                && !first.Log.Any(l => l.StartsWith("[à blanc] chown -R root", StringComparison.Ordinal)),
            "Fichiers du serveur écrits par le compte du serveur (runuser), pas par root");
        r.Check(steps.Count >= 6 && steps.Any(s => s.Contains("Mods du serveur")), $"Avancement remonté au launcher ({steps.Count} étapes)");
        var leftovers = await shell.RunAsync(CountStages);
        r.Check(leftovers.Output.Trim() == stagesBefore, "Dossier d'envoi effacé après l'installation");

        var mods = LocalServer.ServerMods(release.Manifest);
        if (download)
        {
            string java = Path.Combine(dir, "java", javaName, "bin", "java");
            r.Check(File.Exists(java), "Java de Mojang pour Linux téléchargé et vérifié (SHA-1)", java);
            r.Check(File.Exists(Path.Combine(dir, "java", javaName, "lib", "libjli.so")) || Directory.Exists(Path.Combine(dir, "java", javaName, "lib")),
                "Bibliothèques de Java en place");
            byte[] head = File.Exists(Path.Combine(dir, "fabric-server-launch.jar")) ? File.ReadAllBytes(Path.Combine(dir, "fabric-server-launch.jar"))[..2] : Array.Empty<byte>();
            r.Check(head.SequenceEqual("PK"u8.ToArray()), "Lanceur Fabric téléchargé (archive Java)");
            foreach (var mod in mods)
            {
                string f = Path.Combine(dir, "mods", mod.FileName);
                r.Check(File.Exists(f) && Http.Sha512(f) == mod.Sha512.ToLowerInvariant(), $"Mod {mod.Name} vérifié (SHA-512)");
            }

            // Relancée telle quelle, l'installation ne change rien.
            var second = await server.InstallAsync(release, null, true);
            r.Check(!second.Changed, "Installation relancée : rien à refaire (idempotente)", second.Log.LastOrDefault());

            // Réglage changé entre-temps (par l'API d'administration, par exemple) : gardé.
            string propsFile = Path.Combine(dir, "server.properties");
            File.WriteAllText(propsFile, File.ReadAllText(propsFile) + "max-players=16\n");
            // Un mod abîmé et un vieux mod oublié : réparés.
            string victim = Path.Combine(dir, "mods", mods[0].FileName);
            File.WriteAllBytes(victim, Encoding.UTF8.GetBytes("abîmé"));
            File.WriteAllText(Path.Combine(dir, "mods", "ancien-mod-1.0.jar"), "vieux");
            File.WriteAllText(Path.Combine(stubs, "etat"), "active\n");
            var third = await server.UpdateAsync(release);
            r.Check(third.Changed && third.Restarted, "Mise à jour d'un serveur en marche : réparée puis redémarrée");
            r.Check(Http.Sha512(victim) == mods[0].Sha512.ToLowerInvariant(), "Mod abîmé retéléchargé");
            r.Check(!File.Exists(Path.Combine(dir, "mods", "ancien-mod-1.0.jar")), "Mod absent de la version retiré");
            r.Check(File.ReadAllText(propsFile).Contains("max-players=16"), "Réglage changé sur le serveur gardé");
            var fourth = await server.UpdateAsync(release);
            r.Check(!fourth.Changed && !fourth.Restarted, "Mise à jour sans changement : pas de redémarrage");
        }
        else
        {
            r.Note("        (sans téléchargement : Java, Fabric et mods seulement annoncés, idempotence non vérifiée)");
            r.Check(first.Log.Count(l => l.StartsWith("[sans téléchargement]", StringComparison.Ordinal)) >= mods.Count + 2,
                "Téléchargements annoncés (Java, Fabric, mods)");
        }

        // Ports et API choisis dans le launcher.
        var custom = SampleProfile();
        custom.AdminPort = 25571;
        custom.VoicePort = 24460;
        custom.MinecraftPort = 25600;
        using (var customServer = new VpsServer(custom, shell))
        {
            Directory.CreateDirectory(Path.Combine(dir, "config"));
            File.WriteAllText(Path.Combine(dir, "config", "valcombe-communaute.json"), "{\"discordWebhook\": \"\", \"tabletPort\": 25570, \"welcome\": \"Salut\"}");
            var prepared = await customServer.PrepareFilesAsync();
            var community = prepared.FirstOrDefault(f => f.StagedName == "valcombe-communaute.json");
            r.Check(community != null && community.Content.Contains("\"tabletPort\": 25571") && community.Content.Contains("\"welcome\": \"Salut\""),
                "Port de l'API choisi : écrit dans la configuration du mod, le reste gardé");
            var voice = prepared.FirstOrDefault(f => f.StagedName == "voicechat-server.properties");
            r.Check(voice != null && voice.Content.Contains("port=24460"), "Port du chat vocal choisi : écrit pour Simple Voice Chat");
            r.Check(prepared.First(f => f.StagedName == "server.properties").Content.Contains("server-port=25600"), "Port du jeu choisi : écrit dans server.properties");
        }

        // Service.
        File.WriteAllText(Path.Combine(stubs, "etat"), "inactive\n");
        File.WriteAllText(Path.Combine(stubs, "appels.log"), "");
        await server.StartAsync();
        var running = await server.StatusAsync();
        r.Check(running.Installed && running.Active && running.Listening && running.Text == "En marche"
                && running.Version == release.Tag && running.Enabled,
            "Démarrer : service actif, port du jeu ouvert", $"{running.Text}, version {running.Version}, depuis {running.Since:yyyy-MM-dd HH:mm}");
        await server.RestartAsync();
        await server.StopAsync();
        var stopped = await server.StatusAsync();
        r.Check(!stopped.Active && stopped.Text == "Arrêté", "Arrêter : service arrêté");
        string calls = File.ReadAllText(Path.Combine(stubs, "appels.log"));
        r.Check(calls.Contains("systemctl start valcombe.service") && calls.Contains("systemctl restart valcombe.service")
                && calls.Contains("systemctl stop valcombe.service"), "Commandes systemd envoyées", calls.Replace("\n", " | ").Trim());
        var log = await server.ReadLogAsync(50);
        r.Check(log.Count == 3 && log[2].Contains("Done ("), "Journal du service lu (journalctl)", log.LastOrDefault());
        var followed = new List<string>();
        await server.FollowLogAsync(l => followed.Add(l));
        r.Check(followed.Count == 3 && File.ReadAllText(Path.Combine(stubs, "appels.log")).Contains("-f -n 50"), "Journal suivi en direct");

        // API d'administration.
        await r.ThrowsAsync(() => server.ReadAdminKeyAsync(), "Clé d'administration absente : message clair", "démarrez le serveur");
        Directory.CreateDirectory(Path.Combine(dir, "config", "valcombe"));
        string key = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
        File.WriteAllText(Path.Combine(dir, "config", "valcombe", "admin.json"), $"{{\"key\": \"{key}\", \"adminLocalOnly\": true, \"enabled\": true}}");
        r.Check(await server.ReadAdminKeyAsync() == key, "Clé d'administration lue (config/valcombe/admin.json)");
        r.Check(await server.ReadAdminPortAsync() == 25570, "Port de l'API lu dans la configuration du mod");
        File.WriteAllText(Path.Combine(dir, "config", "valcombe", "admin.json"), $"{{\"key\": \"{key}\", \"enabled\": false}}");
        await r.ThrowsAsync(() => server.ReadAdminKeyAsync(), "API coupée : message clair", "coupée");
        File.WriteAllText(Path.Combine(dir, "config", "valcombe", "admin.json"), $"{{\"key\": \"{key}\"}}");
        await r.ThrowsAsync(() => server.OpenAdminTunnelAsync(), "Pas de tunnel à blanc (aucun port de ce PC touché)", "Pas de tunnel");

        // Plans falsifiés : le script refuse avant de toucher à quoi que ce soit.
        await ForgedPlansAsync(r, shell, root, work);
    }

    private static async Task ForgedPlansAsync(Report r, LocalBashShell shell, string root, string work)
    {
        string stage = Path.Combine(work, "envoi-falsifie");
        Directory.CreateDirectory(stage);
        File.WriteAllText(Path.Combine(stage, VpsServer.ScriptName), VpsServer.ScriptText(), new UTF8Encoding(false));
        string sha1 = new('a', 40);
        string sha512 = new('b', 128);
        string Base(string dossier = "/opt/essai-falsifie") => string.Join('\n',
            "valcombe-plan\t1", $"reglage\tDOSSIER\t{dossier}", "reglage\tCOMPTE\tvalcombe", "reglage\tSERVICE\tvalcombe",
            "reglage\tPORT_JEU\t25565", "reglage\tPORT_VOIX\t24454", "reglage\tMEMOIRE_MO\t2048", "reglage\tPARE_FEU\tnon",
            "reglage\tVERSION\tv1", "reglage\tMINECRAFT\t26.3", "reglage\tCHARGEUR\t0.20.1", "reglage\tJAVA\tjava-runtime-epsilon",
            $"java-fichier\t{sha1}\tx\tbin/java\thttps://piston-data.mojang.com/v1/objects/{sha1}/java",
            "fabric\thttps://meta.fabricmc.net/v2/versions/loader/26.3/0.20.1/1.1.0/server/jar",
            $"mod\t{sha512}\tvalcombe.jar\thttps://github.com/exemple/valcombe/releases/download/v1/valcombe.jar") + "\n";
        var cases = new (string Name, string Plan, string Expect)[]
        {
            ("adresse d'un hôte inconnu", Base().Replace("https://github.com/exemple", "https://exemple.invalid/x"), "adresse de mod refusée"),
            ("adresse en http", Base().Replace("https://meta.fabricmc.net", "http://meta.fabricmc.net"), "adresse de Fabric refusée"),
            ("chemin qui sort du dossier de Java", Base().Replace("\tbin/java\t", "\t../../etc/cron.d/x\t"), "chemin de fichier de Java refusé"),
            ("nom de mod avec un dossier", Base().Replace("\tvalcombe.jar\t", "\t../valcombe.jar\t"), "nom de mod refusé"),
            ("dossier du système", Base("/etc/valcombe"), "dossier du système"),
            ("dossier /opt seul", Base("/opt"), "Dossier du serveur refusé"),
            ("compte root", Base().Replace("COMPTE\tvalcombe", "COMPTE\troot"), "Compte du serveur refusé"),
            ("commande dans le nom du service", Base().Replace("SERVICE\tvalcombe", "SERVICE\tvalcombe;reboot"), "Nom de service refusé"),
            ("empreinte tronquée", Base().Replace(sha512, sha512[..100]), "empreinte SHA-512 invalide"),
            ("entrée inconnue", Base() + "commande\trm -rf /\n", "entrée inconnue"),
            ("format futur", Base().Replace("valcombe-plan\t1", "valcombe-plan\t2"), "Format de plan inconnu"),
        };
        foreach (var (name, plan, expect) in cases)
        {
            File.WriteAllText(Path.Combine(stage, "plan.tsv"), plan, new UTF8Encoding(false));
            string s = LocalBashShell.ToBashPath(stage);
            var result = await shell.RunAsync($"bash {Shell.Quote(s + "/" + VpsServer.ScriptName)} --plan {Shell.Quote(s + "/plan.tsv")} "
                + $"--a-blanc --racine {Shell.Quote(root)} --sans-telechargement");
            string error = result.Output.Split('\n').FirstOrDefault(l => l.StartsWith("@@erreur", StringComparison.Ordinal)) ?? "";
            r.Check(!result.Ok && error.Contains(expect, StringComparison.Ordinal) && !Directory.Exists(Path.Combine(work, "racine", "opt", "essai-falsifie")),
                $"Plan falsifié refusé ({name})", error);
        }
        // La phase du compte du serveur seule : elle remplit le dossier du serveur, mais ne touche ni au service
        // ni au reste du système, et rend 10 quand quelque chose a changé.
        string accountRoot = Path.Combine(work, "racine-compte");
        Directory.CreateDirectory(accountRoot);
        File.WriteAllText(Path.Combine(stage, "plan.tsv"), Base("/opt/essai-compte"), new UTF8Encoding(false));
        var accountPhase = await shell.RunAsync($"bash {Shell.Quote(LocalBashShell.ToBashPath(stage) + "/" + VpsServer.ScriptName)} --phase-compte "
            + $"--plan {Shell.Quote(LocalBashShell.ToBashPath(stage) + "/plan.tsv")} --a-blanc "
            + $"--racine {Shell.Quote(LocalBashShell.ToBashPath(accountRoot))} --sans-telechargement");
        r.Check(accountPhase.ExitCode == 10 && File.Exists(Path.Combine(accountRoot, "opt", "essai-compte", "demarrer.sh"))
                && !Directory.Exists(Path.Combine(accountRoot, "etc")) && !accountPhase.Output.Contains("@@fini"),
            "Phase du compte seule : dossier du serveur rempli, service et système intouchés", $"code {accountPhase.ExitCode}");

        var notRoot = await shell.RunAsync($"bash {Shell.Quote(LocalBashShell.ToBashPath(stage) + "/" + VpsServer.ScriptName)} "
            + $"--plan {Shell.Quote(LocalBashShell.ToBashPath(stage) + "/plan.tsv")} --racine /tmp");
        r.Check(!notRoot.Ok && notRoot.Output.Contains("@@erreur"), "--racine sans --a-blanc refusé",
            notRoot.Output.Split('\n').FirstOrDefault(l => l.StartsWith("@@erreur", StringComparison.Ordinal)));
    }

    // ------------------------------------------------------------------
    // --essai-vps : un vrai serveur
    // ------------------------------------------------------------------
    public static async Task TrialAsync(string reportFile, string[] args, LauncherConfig config)
    {
        var r = new Report();
        string? Arg(string name)
        {
            int i = Array.IndexOf(args, name);
            return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
        }
        void Note(string text) => r.Note($"{DateTime.Now:HH:mm:ss} {text}");
        try
        {
            var profile = new VpsProfile
            {
                Name = "Essai",
                Host = Arg("--hote") ?? throw new ArgumentException("--hote manquant"),
                User = Arg("--utilisateur") ?? "root",
                SshPort = int.TryParse(Arg("--port"), out int port) ? port : 22,
                EulaAccepted = args.Contains("--contrat-accepte"),
            };
            if (Arg("--cle") is string keyFile)
            {
                profile.Auth = SshAuthKind.PrivateKey;
                profile.ImportPrivateKeyFile(keyFile, Environment.GetEnvironmentVariable(Arg("--phrase-env") ?? "") ?? "");
            }
            else
            {
                profile.SetPassword(Environment.GetEnvironmentVariable(Arg("--mot-de-passe-env") ?? "")
                    ?? throw new ArgumentException("--cle ou --mot-de-passe-env manquant"));
            }
            VpsServer server;
            try
            {
                server = await VpsServer.ConnectAsync(profile, Arg("--empreinte"));
            }
            catch (SshHostKeyUnknownException ex)
            {
                Note(ex.Message);
                Note($"Pour l'accepter, relancez avec : --empreinte {ex.Fingerprint}");
                await File.WriteAllLinesAsync(reportFile, r.Lines);
                return;
            }
            using (server)
            {
                Note($"Connecté à {server.Shell.Label}, droits : {server.Shell.Root}");
                var system = await server.DetectAsync();
                Note("Système : " + system.Summary);
                system.Problems.ForEach(p => Note("Bloquant : " + p));
                system.Warnings.ForEach(w => Note("Attention : " + w));
                if (args.Contains("--installer"))
                {
                    var release = await Releases.LatestAsync(config.Repository);
                    var result = await server.UpdateAsync(release, new Progress<(string, double)>(p => Note($"{p.Item2:P0} {p.Item1}")));
                    Note($"Installation : changé = {result.Changed}, redémarré = {result.Restarted}, {result.Summary}");
                    result.Log.ToList().ForEach(l => r.Note("          " + l));
                }
                if (args.Contains("--demarrer"))
                {
                    await server.StartAsync();
                    for (int i = 0; i < 90 && !(await server.StatusAsync()).Listening; i++)
                    {
                        await Task.Delay(2000);
                    }
                }
                var status = await server.StatusAsync();
                Note($"Service : {status.Text} ({status.ActiveState}/{status.SubState}), version {status.Version}, port du jeu ouvert : {status.Listening}");
                Note("Fin du journal :");
                (await server.ReadLogAsync(30)).ToList().ForEach(l => r.Note("          " + l));
                if (status.Active)
                {
                    using var api = await server.OpenAdminTunnelAsync();
                    Note($"Tunnel ouvert : {api.BaseAddress} ({api.Label})");
                    using var request = new HttpRequestMessage(HttpMethod.Get, new Uri(api.BaseAddress, "admin/etat"));
                    request.Headers.Add(IAdminTunnel.KeyHeader, api.Key);
                    using var answer = await Http.Client.SendAsync(request);
                    string body = await answer.Content.ReadAsStringAsync();
                    Note($"GET /admin/etat : {(int)answer.StatusCode}, {body[..Math.Min(300, body.Length)]}");
                }
            }
        }
        catch (Exception ex)
        {
            Note("ERREUR : " + ex);
        }
        await File.WriteAllLinesAsync(reportFile, r.Lines);
    }
}
