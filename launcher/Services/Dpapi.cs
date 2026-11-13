using System.Runtime.InteropServices;
using System.Text;

namespace Valcombe.Launcher.Services;

/// <summary>
/// Chiffrement local des secrets (DPAPI de Windows, lié à la session de l'utilisateur) : le jeton Microsoft,
/// les mots de passe et clés SSH des serveurs loués. Un fichier copié sur un autre PC ou un autre compte
/// Windows ne se déchiffre pas.
/// </summary>
internal static class Dpapi
{
    [StructLayout(LayoutKind.Sequential)]
    private struct Blob
    {
        public int Size;
        public IntPtr Data;
    }

    // Deux formes de chaque appel : sans entropie (pointeur nul, comme le jeton Microsoft l'a toujours été)
    // et avec entropie (un « sel » propre à chaque usage, pour qu'un secret ne se déchiffre pas à la place d'un autre).
    [DllImport("crypt32.dll", EntryPoint = "CryptProtectData", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptProtectData(ref Blob input, string? desc, IntPtr entropy, IntPtr reserved,
        IntPtr prompt, int flags, ref Blob output);

    [DllImport("crypt32.dll", EntryPoint = "CryptProtectData", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptProtectDataSalted(ref Blob input, string? desc, ref Blob entropy, IntPtr reserved,
        IntPtr prompt, int flags, ref Blob output);

    [DllImport("crypt32.dll", EntryPoint = "CryptUnprotectData", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptUnprotectData(ref Blob input, IntPtr desc, IntPtr entropy, IntPtr reserved,
        IntPtr prompt, int flags, ref Blob output);

    [DllImport("crypt32.dll", EntryPoint = "CryptUnprotectData", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptUnprotectDataSalted(ref Blob input, IntPtr desc, ref Blob entropy, IntPtr reserved,
        IntPtr prompt, int flags, ref Blob output);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr mem);

    private static Blob Alloc(byte[] data)
    {
        var blob = new Blob { Size = data.Length, Data = Marshal.AllocHGlobal(Math.Max(1, data.Length)) };
        Marshal.Copy(data, 0, blob.Data, data.Length);
        return blob;
    }

    private static byte[] Run(byte[] data, byte[]? entropy, bool protect)
    {
        var input = Alloc(data);
        bool salted = entropy is { Length: > 0 };
        var extra = salted ? Alloc(entropy!) : new Blob();
        var output = new Blob();
        try
        {
            bool ok = (protect, salted) switch
            {
                (true, false) => CryptProtectData(ref input, "Valcombe", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 0, ref output),
                (true, true) => CryptProtectDataSalted(ref input, "Valcombe", ref extra, IntPtr.Zero, IntPtr.Zero, 0, ref output),
                (false, false) => CryptUnprotectData(ref input, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 0, ref output),
                (false, true) => CryptUnprotectDataSalted(ref input, IntPtr.Zero, ref extra, IntPtr.Zero, IntPtr.Zero, 0, ref output),
            };
            if (!ok)
            {
                throw new InvalidOperationException(protect
                    ? "Chiffrement Windows indisponible."
                    : "Secret illisible : il a été enregistré sur un autre PC ou par un autre compte Windows.");
            }
            var result = new byte[output.Size];
            Marshal.Copy(output.Data, result, 0, output.Size);
            return result;
        }
        finally
        {
            Marshal.FreeHGlobal(input.Data);
            if (extra.Data != IntPtr.Zero)
            {
                Marshal.FreeHGlobal(extra.Data);
            }
            if (output.Data != IntPtr.Zero)
            {
                LocalFree(output.Data);
            }
        }
    }

    public static byte[] Protect(byte[] d, byte[]? entropy = null) => Run(d, entropy, true);

    public static byte[] Unprotect(byte[] d, byte[]? entropy = null) => Run(d, entropy, false);

    /// <summary>Chiffre un texte et le rend en base 64, pour un fichier JSON. Texte vide : chaîne vide.</summary>
    public static string ProtectText(string text, string purpose) =>
        text.Length == 0 ? "" : Convert.ToBase64String(Protect(Encoding.UTF8.GetBytes(text), Encoding.UTF8.GetBytes(purpose)));

    /// <summary>Inverse de <see cref="ProtectText"/>. Chaîne vide : texte vide.</summary>
    public static string UnprotectText(string base64, string purpose) =>
        base64.Length == 0 ? "" : Encoding.UTF8.GetString(Unprotect(Convert.FromBase64String(base64), Encoding.UTF8.GetBytes(purpose)));
}
