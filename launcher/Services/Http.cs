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
        using (var resp = await Client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct))
        {
            resp.EnsureSuccessStatusCode();
            long total = resp.Content.Headers.ContentLength ?? -1;
            await using var input = await resp.Content.ReadAsStreamAsync(ct);
            await using var output = File.Create(tmp);
            var buffer = new byte[81920];
            long done = 0;
            int n;
            while ((n = await input.ReadAsync(buffer, ct)) > 0)
            {
                await output.WriteAsync(buffer.AsMemory(0, n), ct);
                done += n;
                if (total > 0)
                {
                    progress?.Report((double)done / total);
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
