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

/// <summary>Un serveur enregistré par le joueur.</summary>
public sealed class SavedServer
{
    public string Name { get; set; } = "";
    public string Address { get; set; } = "";
}

/// <summary>Réglages du launcher, enregistrés à côté de l'exe (mode portable).</summary>
public sealed class LauncherConfig
{
    /// <summary>Dépôt GitHub qui publie les versions du mod (« propriétaire/nom »).</summary>
    public string Repository { get; set; } = "Noedio/cendres-de-valcombe";
    /// <summary>Adresse du serveur ; vide = celle annoncée par la version publiée.</summary>
    public string Server { get; set; } = "";
    /// <summary>Autres serveurs enregistrés (le serveur annoncé par la version publiée est toujours proposé).</summary>
    public List<SavedServer> Servers { get; set; } = new();
    /// <summary>ID de l'application Azure « Valcombe Launcher », validée par Microsoft (identifiant public).</summary>
    public const string DefaultClientId = "572985ad-7515-4b67-838c-2361d2c33adc";

    /// <summary>ID d'application Azure : active la connexion intégrée et notre moteur (vide = celui de Valcombe).</summary>
    public string MicrosoftClientId { get; set; } = DefaultClientId;
    public int MaxMemoryMb { get; set; } = 4096;
    public bool CloseOnLaunch { get; set; } = true;
    /// <summary>Raccourcis dans le menu Démarrer et sur le Bureau (le launcher est portable).</summary>
    public bool Shortcuts { get; set; } = true;
    /// <summary>Le raccourci du Bureau a déjà été créé une fois (on ne le recrée pas s'il a été supprimé).</summary>
    public bool DesktopShortcutDone { get; set; }
    /// <summary>Textures haute définition (pack 64x) pour tout le jeu.</summary>
    public bool HdTextures { get; set; } = true;
    /// <summary>Ombres, lumières et eau réalistes (shaders) : demande une carte graphique récente.</summary>
    public bool Shaders { get; set; } = true;
    // --- Héberger sur ce PC ---
    /// <summary>L'hôte a lu et accepté le contrat de Minecraft (obligatoire pour démarrer un serveur).</summary>
    public bool HostEulaAccepted { get; set; }
    public int HostPort { get; set; } = 25565;
    public int HostMemoryMb { get; set; } = 3072;
    /// <summary>Seuls les invités de la liste peuvent entrer.</summary>
    public bool HostGuestsOnly { get; set; } = true;
    public List<string> HostGuests { get; set; } = new();
    /// <summary>Pseudo Minecraft de l'hôte (il devient opérateur du serveur) ; rempli à la connexion.</summary>
    public string HostName { get; set; } = "";

    /// <summary>Braises animées sur le fond du launcher.</summary>
    public bool Embers { get; set; } = true;
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
