using System.IO;

namespace Valcombe.Launcher.Services;

/// <summary>Tout est rangé à côté de l'exe : le launcher reste portable (clé USB, dossier partagé…).</summary>
public static class Paths
{
    public static string ExeDir => AppContext.BaseDirectory;
    public static string DataDir => Path.Combine(ExeDir, "ValcombeData");
    public static string ConfigFile => Path.Combine(DataDir, "launcher.json");
    public static string CacheDir => Path.Combine(DataDir, "cache");
    public static string PrismDir => Path.Combine(DataDir, "prism");
    public static string PrismExe => Path.Combine(PrismDir, "prismlauncher.exe");
    public const string InstanceId = "valcombe";
    public static string InstanceDir => Path.Combine(PrismDir, "instances", InstanceId);
    public static string GameDir => Path.Combine(InstanceDir, "minecraft");
    public static string ModsDir => Path.Combine(GameDir, "mods");
}
