using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;

namespace Valcombe.Launcher.Services;

/// <summary>Téléchargements avec progression et vérification d'empreinte.</summary>
public static class Http
{
    public static readonly HttpClient Client = Create();

    private static HttpClient Create()
    {
        var c = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        c.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("ValcombeLauncher", "0.1"));
        return c;
    }

    private static long _bytes;
    private static readonly System.Diagnostics.Stopwatch Clock = System.Diagnostics.Stopwatch.StartNew();
    private static long _mark;
    private static long _markBytes;
    private static double _speed;

    /// <summary>Octets reçus depuis le démarrage (tous téléchargements confondus).</summary>
    public static long Bytes => Interlocked.Read(ref _bytes);

    /// <summary>Vitesse récente, en octets par seconde (moyenne glissante).</summary>
    public static double Speed
    {
        get
        {
            long now = Clock.ElapsedMilliseconds;
            long elapsed = now - Interlocked.Read(ref _mark);
            if (elapsed >= 700)
            {
                long b = Bytes;
                double instant = (b - Interlocked.Exchange(ref _markBytes, b)) * 1000.0 / elapsed;
                Interlocked.Exchange(ref _mark, now);
                _speed = _speed <= 0 ? instant : _speed * 0.6 + instant * 0.4;
            }
            return _speed;
        }
    }

    /// <summary>« 3,2 Mo/s » ou chaîne vide si rien ne passe.</summary>
    public static string SpeedText()
    {
        double s = Speed;
        return s < 1024 ? "" : s >= 1024 * 1024 ? $"{s / 1024 / 1024:0.0} Mo/s" : $"{s / 1024:0} Ko/s";
    }

    /// <summary>« reste 12 s » d'après la vitesse actuelle, ou chaîne vide.</summary>
    public static string RemainingText(long remainingBytes)
    {
        double s = Speed;
        if (s < 1024 || remainingBytes <= 0)
        {
            return "";
        }
        double sec = remainingBytes / s;
        return sec < 60 ? $"reste {Math.Ceiling(sec)} s" : $"reste {Math.Ceiling(sec / 60)} min";
    }

    public static string Sha512(string file)
    {
        using var s = File.OpenRead(file);
        return Convert.ToHexString(SHA512.HashData(s)).ToLowerInvariant();
    }

    /// <summary>Télécharge vers un fichier temporaire, vérifie l'empreinte, puis remplace la cible.</summary>
    public static async Task DownloadAsync(string url, string target, string? sha512, IProgress<double>? progress,
        CancellationToken ct = default)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(target)!);
        string tmp = target + ".part";
        // Reprise : un fichier .part laissé par une coupure est complété au lieu d'être recommencé.
        long already = File.Exists(tmp) ? new FileInfo(tmp).Length : 0;
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        if (already > 0)
        {
            request.Headers.Range = new RangeHeaderValue(already, null);
        }
        using (var resp = await Client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct))
        {
            if (already > 0 && resp.StatusCode == System.Net.HttpStatusCode.RequestedRangeNotSatisfiable)
            {
                already = 0; // le fichier partiel est déjà complet ou périmé : on le vérifiera
            }
            else
            {
                resp.EnsureSuccessStatusCode();
                bool resumed = already > 0 && resp.StatusCode == System.Net.HttpStatusCode.PartialContent;
                if (!resumed)
                {
                    already = 0;
                }
                long total = (resp.Content.Headers.ContentLength ?? -1) + (resumed ? already : 0);
                await using var input = await resp.Content.ReadAsStreamAsync(ct);
                await using var output = new FileStream(tmp, resumed ? FileMode.Append : FileMode.Create, FileAccess.Write);
                var buffer = new byte[81920];
                long done = already;
                int n;
                while ((n = await input.ReadAsync(buffer, ct)) > 0)
                {
                    await output.WriteAsync(buffer.AsMemory(0, n), ct);
                    done += n;
                    Interlocked.Add(ref _bytes, n);
                    if (total > 0)
                    {
                        progress?.Report((double)done / total);
                    }
                }
            }
        }
        if (!string.IsNullOrEmpty(sha512) && !string.Equals(Sha512(tmp), sha512, StringComparison.OrdinalIgnoreCase))
        {
            File.Delete(tmp);
            throw new InvalidDataException($"Fichier corrompu (empreinte incorrecte) : {Path.GetFileName(target)}");
        }
        File.Move(tmp, target, overwrite: true);
    }
}
