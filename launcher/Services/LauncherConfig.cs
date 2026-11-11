using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Valcombe.Launcher.Services;

/// <summary>Réglages Minecraft appliqués à l'instance (fichier options.txt).</summary>
public sealed class MinecraftOptions
{
    public int RenderDistance { get; set; } = 10;
    public int GuiScale { get; set; } = 0;
    public bool Fullscreen { get; set; }
    public int MaxFps { get; set; } = 120;
    public int Fov { get; set; } = 70;
    public int MasterVolume { get; set; } = 100;
    public string Language { get; set; } = "fr_fr";
}

/// <summary>Réglages du launcher, enregistrés à côté de l'exe (mode portable).</summary>
public sealed class LauncherConfig
{
    /// <summary>Dépôt GitHub qui publie les versions du mod (« propriétaire/nom »).</summary>
    public string Repository { get; set; } = "Noedio/cendres-de-valcombe";
    /// <summary>Adresse du serveur ; vide = celle annoncée par la version publiée.</summary>
    public string Server { get; set; } = "";
    /// <summary>ID de l'application Azure « Valcombe Launcher », validée par Microsoft (identifiant public).</summary>
    public const string DefaultClientId = "572985ad-7515-4b67-838c-2361d2c33adc";

    /// <summary>ID d'application Azure : active la connexion intégrée et notre moteur (vide = celui de Valcombe).</summary>
    public string MicrosoftClientId { get; set; } = DefaultClientId;
    public int MaxMemoryMb { get; set; } = 4096;
    public bool CloseOnLaunch { get; set; } = true;
    public string Accent { get; set; } = "Lie-de-vin";
    public string Background { get; set; } = "Parchemin";
    public MinecraftOptions Minecraft { get; set; } = new();

    private static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
    };

    public static LauncherConfig Load()
    {
        try
        {
            if (File.Exists(Paths.ConfigFile))
            {
                var cfg = JsonSerializer.Deserialize<LauncherConfig>(File.ReadAllText(Paths.ConfigFile), Json) ?? new();
                // Les launchers 0.2.0 ont enregistré un champ vide : on passe à la connexion intégrée.
                if (string.IsNullOrWhiteSpace(cfg.MicrosoftClientId))
                {
                    cfg.MicrosoftClientId = DefaultClientId;
                }
                return cfg;
            }
        }
        catch (Exception)
        {
            // Fichier abîmé : on repart des réglages par défaut.
        }
        return new LauncherConfig();
    }

    public void Save()
    {
        Directory.CreateDirectory(Paths.DataDir);
        File.WriteAllText(Paths.ConfigFile, JsonSerializer.Serialize(this, Json));
    }
}
