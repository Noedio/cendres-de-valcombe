using System.Diagnostics;
using System.IO;
using System.Text;

namespace Valcombe.Launcher.Services.Ssh;

/// <summary>
/// Un « serveur » simulé sur ce PC, pour vérifier la couche SSH sans VPS (ValcombeLauncher.exe --verifier-vps) :
/// les commandes passent au bash de Git pour Windows, l'installation est jouée à blanc dans un dossier qui tient
/// lieu de « / », et un dossier de faux outils (uname, systemctl, journalctl...) répond comme un Debian.
/// Jamais de tunnel : rien ne doit toucher aux ports de ce PC.
/// </summary>
public sealed class LocalBashShell : IRemoteShell
{
    private readonly string _bash;
    private readonly string? _stubDir;

    /// <param name="bash">bash.exe de Git pour Windows (<see cref="FindBash"/>).</param>
    /// <param name="sandbox">Dossier Windows qui tient lieu de « / » pour l'installation à blanc.</param>
    /// <param name="stubDir">Dossier Windows de faux outils, placé en tête du PATH ; null : aucun.</param>
    public LocalBashShell(string bash, string sandbox, string? stubDir)
    {
        _bash = bash;
        DryRunRoot = ToBashPath(sandbox);
        _stubDir = stubDir == null ? null : ToBashPath(stubDir);
    }

    public string Label => "bash local (installation à blanc)";

    public RootAccess Root => RootAccess.Root;

    public string? DryRunRoot { get; }

    /// <summary>Le bash de Git pour Windows, ou null (jamais celui de WSL, qui viserait une autre machine).</summary>
    public static string? FindBash()
    {
        foreach (string root in new[]
        {
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs"),
        })
        {
            string candidate = Path.Combine(root, "Git", "bin", "bash.exe");
            if (root.Length > 0 && File.Exists(candidate))
            {
                return candidate;
            }
        }
        return null;
    }

    /// <summary>« C:\Users\x » devient « /c/Users/x », la forme que comprend le bash de Git.</summary>
    public static string ToBashPath(string windowsPath)
    {
        string full = Path.GetFullPath(windowsPath).TrimEnd('\\');
        return full.Length >= 2 && full[1] == ':'
            ? "/" + char.ToLowerInvariant(full[0]) + full[2..].Replace('\\', '/')
            : full.Replace('\\', '/');
    }

    public async Task<RemoteResult> RunAsync(string command, bool asRoot = false, Action<string>? onLine = null,
        TimeSpan? timeout = null, CancellationToken ct = default)
    {
        string full = (_stubDir != null ? $"export PATH={Shell.Quote(_stubDir)}:\"$PATH\"; " : "") + command;
        return await RunBashAsync(full, null, onLine, timeout ?? TimeSpan.FromMinutes(10), ct);
    }

    public async Task UploadAsync(string remotePath, byte[] content, CancellationToken ct = default)
    {
        var r = await RunBashAsync("cat > " + Shell.Quote(remotePath), content, null, TimeSpan.FromMinutes(1), ct);
        if (!r.Ok)
        {
            throw new InvalidOperationException($"Envoi impossible ({remotePath}) : {r.Message}");
        }
    }

    public Task<IServerConnection> OpenTunnelAsync(int remotePort, string key, CancellationToken ct = default) =>
        throw new NotSupportedException("Pas de tunnel dans l'installation à blanc.");

    private async Task<RemoteResult> RunBashAsync(string command, byte[]? stdin, Action<string>? onLine, TimeSpan timeout,
        CancellationToken ct)
    {
        var psi = new ProcessStartInfo(_bash)
        {
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        psi.ArgumentList.Add("-c");
        psi.ArgumentList.Add(command);
        using var p = Process.Start(psi) ?? throw new InvalidOperationException("bash n'a pas démarré.");
        var output = new StringBuilder();
        var readOut = Task.Run(async () =>
        {
            string? line;
            while ((line = await p.StandardOutput.ReadLineAsync()) != null)
            {
                output.Append(line).Append('\n');
                onLine?.Invoke(line);
            }
        });
        var readErr = p.StandardError.ReadToEndAsync();
        if (stdin != null)
        {
            await p.StandardInput.BaseStream.WriteAsync(stdin, ct);
        }
        p.StandardInput.Close();
        using var limit = CancellationTokenSource.CreateLinkedTokenSource(ct);
        limit.CancelAfter(timeout);
        try
        {
            await p.WaitForExitAsync(limit.Token);
        }
        catch (OperationCanceledException)
        {
            p.Kill(entireProcessTree: true);
            if (ct.IsCancellationRequested)
            {
                throw;
            }
            throw new TimeoutException($"bash n'a pas fini à temps ({timeout.TotalSeconds:0} s).");
        }
        await readOut;
        return new RemoteResult(p.ExitCode, output.ToString(), await readErr);
    }

    public void Dispose()
    {
    }
}
