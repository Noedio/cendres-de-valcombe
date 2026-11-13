using System.IO;
using System.IO.Compression;
using System.Text.Json.Nodes;

namespace Valcombe.Launcher.Services.Admin;

/// <summary>
/// Une archive de sauvegarde téléchargée sur ce PC, contrôlée avant d'être donnée à l'hôte : un zip qui s'ouvre,
/// et la fiche <c>valcombe-sauvegarde.json</c> que le mod écrit en dernière entrée (docs/ADMIN-API.md, « Ce qu'une
/// archive contient »). Une archive sans fiche lisible ne serait pas restaurée par le mod : autant le dire tout de suite.
/// </summary>
public static class BackupArchive
{
    public const string ManifestName = "valcombe-sauvegarde.json";

    /// <summary>Ce que le contrôle a trouvé.</summary>
    public sealed record Check(bool Ok, string Message, int Entries, int DeclaredFiles, IReadOnlyList<string> Worlds);

    /// <summary>Ouvre l'archive et lit sa fiche ; ne lève pas, le verdict est dans <see cref="Check.Ok"/>.</summary>
    public static Check Verify(string file)
    {
        try
        {
            using var zip = ZipFile.OpenRead(file);
            var manifest = zip.GetEntry(ManifestName);
            if (manifest == null)
            {
                return new Check(false, "L'archive s'ouvre, mais sa fiche (valcombe-sauvegarde.json) manque : ce n'est pas une sauvegarde de Valcombe complète.",
                    zip.Entries.Count, 0, Array.Empty<string>());
            }
            JsonNode? json;
            using (var reader = new StreamReader(manifest.Open()))
            {
                json = JsonNode.Parse(reader.ReadToEnd());
            }
            int files = json?["fichiers"]?.GetValue<int>() ?? 0;
            var worlds = (json?["mondes"] as JsonArray)?.Select(n => n?.GetValue<string>() ?? "").Where(n => n.Length > 0).ToList()
                ?? new List<string>();
            // Les fichiers déclarés par la fiche sont tous là (elle ne se compte pas elle-même).
            int present = zip.Entries.Count(e => e.FullName != ManifestName && !e.FullName.EndsWith('/'));
            if (files > 0 && present < files)
            {
                return new Check(false, $"Archive incomplète : {present} fichiers sur les {files} que sa fiche annonce.", zip.Entries.Count, files, worlds);
            }
            return new Check(true, $"Archive vérifiée : {present} fichiers" + (worlds.Count > 0 ? $", {worlds.Count} monde{(worlds.Count > 1 ? "s" : "")}" : "") + ".",
                zip.Entries.Count, files, worlds);
        }
        catch (Exception ex) when (ex is InvalidDataException or IOException or System.Text.Json.JsonException
                                       or InvalidOperationException or FormatException or UnauthorizedAccessException)
        {
            return new Check(false, "Archive illisible : " + ex.Message, 0, 0, Array.Empty<string>());
        }
    }
}
