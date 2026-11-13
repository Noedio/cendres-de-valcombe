using System.Runtime.InteropServices;
using System.Text;

namespace Valcombe.Launcher.Services;

/// <summary>
/// Chiffrement local de Windows (DPAPI), lié à la session Windows de l'utilisateur : ce qui est chiffré ici
/// ne se déchiffre que sur ce PC, avec ce compte Windows. Il garde le jeton Microsoft (compte.bin) et les clés
/// d'administration des serveurs distants (page Serveur).
/// </summary>
public static class Dpapi
{
    [StructLayout(LayoutKind.Sequential)]
    private struct Blob
    {
        public int Size;
        public IntPtr Data;
    }

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptProtectData(ref Blob input, string? desc, IntPtr entropy, IntPtr reserved,
        IntPtr prompt, int flags, ref Blob output);

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptUnprotectData(ref Blob input, IntPtr desc, IntPtr entropy, IntPtr reserved,
        IntPtr prompt, int flags, ref Blob output);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr mem);

    private static byte[] Run(byte[] data, bool protect)
    {
        var input = new Blob { Size = data.Length, Data = Marshal.AllocHGlobal(Math.Max(1, data.Length)) };
        Marshal.Copy(data, 0, input.Data, data.Length);
        var output = new Blob();
        try
        {
            bool ok = protect
                ? CryptProtectData(ref input, "Valcombe", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 0, ref output)
                : CryptUnprotectData(ref input, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 0, ref output);
            if (!ok)
            {
                throw new InvalidOperationException("Chiffrement Windows indisponible.");
            }
            var result = new byte[output.Size];
            Marshal.Copy(output.Data, result, 0, output.Size);
            return result;
        }
        finally
        {
            Marshal.FreeHGlobal(input.Data);
            if (output.Data != IntPtr.Zero)
            {
                LocalFree(output.Data);
            }
        }
    }

    public static byte[] Protect(byte[] d) => Run(d, true);

    public static byte[] Unprotect(byte[] d) => Run(d, false);

    /// <summary>Un texte chiffré, en base 64 (pour un fichier JSON).</summary>
    public static string ProtectText(string text) => Convert.ToBase64String(Protect(Encoding.UTF8.GetBytes(text)));

    /// <summary>Le texte d'origine, ou null si le chiffré est abîmé ou vient d'un autre PC ou d'un autre compte Windows.</summary>
    public static string? UnprotectText(string? base64)
    {
        if (string.IsNullOrEmpty(base64))
        {
            return null;
        }
        try
        {
            return Encoding.UTF8.GetString(Unprotect(Convert.FromBase64String(base64)));
        }
        catch (Exception ex) when (ex is FormatException or InvalidOperationException)
        {
            return null;
        }
    }
}
