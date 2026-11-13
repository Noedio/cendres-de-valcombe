using System.IO;
using System.Text.Json;

namespace Valcombe.Launcher.Services.Ssh;

/// <summary>Empreinte de la clé d'un serveur SSH, telle que le launcher l'a acceptée.</summary>
public sealed class KnownHost
{
    /// <summary>« vps.exemple.fr:22 » (nom en minuscules).</summary>
    public string Address { get; set; } = "";
    /// <summary>Algorithme de la clé (« ssh-ed25519 »...).</summary>
    public string Algorithm { get; set; } = "";
    /// <summary>Empreinte SHA-256, au format d'OpenSSH : « SHA256:ohD8VZEXGWo6... ».</summary>
    public string Fingerprint { get; set; } = "";
    public DateTime AcceptedOn { get; set; }
}

/// <summary>
/// Les serveurs SSH dont l'hôte a confirmé l'empreinte (ValcombeData/ssh-hotes-connus.json), comme le fichier
/// known_hosts d'OpenSSH : au premier contact, la connexion s'arrête et l'interface fait confirmer l'empreinte ;
/// ensuite, une clé différente est refusée (serveur réinstallé... ou quelqu'un qui se fait passer pour lui).
/// </summary>
public sealed class KnownHosts
{
    public enum Verdict { Known, Unknown, Changed }

    private readonly string _file;
    private readonly object _gate = new();
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    public KnownHosts(string? file = null)
    {
        _file = file ?? Path.Combine(Paths.DataDir, "ssh-hotes-connus.json");
    }

    public static string Key(string host, int port) => $"{host.Trim().ToLowerInvariant()}:{port}";

    /// <summary>« SHA256:... » à partir de l'empreinte en base 64 que donne SSH.NET.</summary>
    public static string Format(string sha256Base64) =>
        sha256Base64.StartsWith("SHA256:", StringComparison.Ordinal) ? sha256Base64 : "SHA256:" + sha256Base64;

    private List<KnownHost> Load()
    {
        try
        {
            if (File.Exists(_file))
            {
                return JsonSerializer.Deserialize<List<KnownHost>>(File.ReadAllText(_file), Json) ?? new();
            }
        }
        catch (JsonException)
        {
            // Un fichier abîmé ne doit pas faire accepter n'importe quelle clé : on le garde de côté, et chaque
            // serveur redemandera une confirmation.
            File.Copy(_file, _file + ".illisible", overwrite: true);
        }
        return new();
    }

    private void Save(List<KnownHost> hosts)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_file)!);
        string tmp = _file + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(hosts, Json));
        File.Move(tmp, _file, overwrite: true);
    }

    public KnownHost? Find(string host, int port)
    {
        lock (_gate)
        {
            string key = Key(host, port);
            return Load().FirstOrDefault(h => h.Address == key);
        }
    }

    /// <summary>Compare la clé présentée par le serveur à celle qu'on connaît.</summary>
    public Verdict Check(string host, int port, string fingerprint)
    {
        var known = Find(host, port);
        if (known == null)
        {
            return Verdict.Unknown;
        }
        return string.Equals(known.Fingerprint, Format(fingerprint), StringComparison.Ordinal) ? Verdict.Known : Verdict.Changed;
    }

    /// <summary>Retient l'empreinte confirmée par l'hôte (remplace celle d'avant pour cette adresse).</summary>
    public void Trust(string host, int port, string algorithm, string fingerprint)
    {
        lock (_gate)
        {
            var hosts = Load();
            string key = Key(host, port);
            hosts.RemoveAll(h => h.Address == key);
            hosts.Add(new KnownHost { Address = key, Algorithm = algorithm, Fingerprint = Format(fingerprint), AcceptedOn = DateTime.UtcNow });
            Save(hosts);
        }
    }

    /// <summary>Oublie l'empreinte d'un serveur (après sa réinstallation) : le prochain contact la redemandera.</summary>
    public void Forget(string host, int port)
    {
        lock (_gate)
        {
            var hosts = Load();
            if (hosts.RemoveAll(h => h.Address == Key(host, port)) > 0)
            {
                Save(hosts);
            }
        }
    }
}

/// <summary>La clé du serveur SSH n'est pas (ou plus) celle qu'on connaît : la connexion n'a pas eu lieu.</summary>
public abstract class SshHostKeyException : Exception
{
    protected SshHostKeyException(string message, string host, int port, string algorithm, string fingerprint) : base(message)
    {
        Host = host;
        Port = port;
        Algorithm = algorithm;
        Fingerprint = KnownHosts.Format(fingerprint);
    }

    public string Host { get; }
    public int Port { get; }
    public string Algorithm { get; }
    /// <summary>Empreinte présentée par le serveur, « SHA256:... ».</summary>
    public string Fingerprint { get; }
}

/// <summary>
/// Premier contact : l'interface montre <see cref="SshHostKeyException.Fingerprint"/> à l'hôte (qui la compare à
/// celle de son hébergeur), puis rappelle la connexion avec cette empreinte pour l'accepter.
/// </summary>
public sealed class SshHostKeyUnknownException : SshHostKeyException
{
    public SshHostKeyUnknownException(string host, int port, string algorithm, string fingerprint)
        : base($"Première connexion à {host} : vérifiez que l'empreinte de sa clé ({algorithm}) est bien "
            + $"{KnownHosts.Format(fingerprint)} (votre hébergeur l'indique, ou « ssh-keygen -lf » sur le serveur), puis acceptez-la.",
            host, port, algorithm, fingerprint)
    {
    }
}

/// <summary>La clé a changé depuis la dernière fois : refusé, sans exception possible depuis l'interface.</summary>
public sealed class SshHostKeyChangedException : SshHostKeyException
{
    public SshHostKeyChangedException(string host, int port, string algorithm, string fingerprint, string expected)
        : base($"Connexion refusée : la clé de {host} a changé ({KnownHosts.Format(fingerprint)} au lieu de {expected}). "
            + "Si vous venez de réinstaller ce serveur, oubliez son ancienne empreinte dans le launcher ; sinon, "
            + "quelqu'un se fait peut-être passer pour lui.", host, port, algorithm, fingerprint)
    {
        Expected = expected;
    }

    /// <summary>Empreinte connue, « SHA256:... ».</summary>
    public string Expected { get; }
}
