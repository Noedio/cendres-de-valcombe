"""Fabrique le modpack et le dossier serveur des Cendres de Valcombe.

Lancer depuis le dossier minecraft, après ./gradlew build :
    python tools/make_modpack.py [--serveur=adresse:port]

Produit dans dist/ :
  - Valcombe-<version>.mrpack : à importer dans Modrinth App ou Prism Launcher.
    Les mods publiés (Fabric API, Simple Voice Chat, WorldEdit) y sont référencés par leur
    adresse Modrinth et leurs empreintes ; le mod Valcombe est inclus tel quel.
  - valcombe-pack.json : le manifeste lu par le launcher (mods des joueurs et du serveur qu'il installe).
  - serveur-valcombe/ : les mods du serveur, des réglages de départ, un script de lancement.

Fantasy (la bibliothèque qui crée les mondes pendant que le serveur tourne) est incluse dans le jar
du mod Valcombe : elle n'apparaît ni dans le modpack, ni dans le manifeste, ni dans le dossier serveur.
"""
import hashlib
import json
import shutil
import sys
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
API = "https://api.modrinth.com/v2"
USER_AGENT = "valcombe-modpack-tool"

# Monde plat et vide (biome du vide : aucune créature n'y apparaît). Les « : » sont échappés
# comme l'exige le format server.properties.
FLAT_WORLD = (
    r"level-type=minecraft\:flat" "\n"
    r'generator-settings={"layers"\:[{"block"\:"minecraft\:bedrock","height"\:1},'
    r'{"block"\:"minecraft\:stone","height"\:3},{"block"\:"minecraft\:grass_block","height"\:1}],'
    r'"biome"\:"minecraft\:the_void","features"\:false,"lakes"\:false,"structure_overrides"\:[]}' "\n"
)


def props():
    out = {}
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.strip().startswith("#"):
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


# Graphismes, côté joueurs seulement (jamais sur le serveur) : fluidité, shaders, lumière tenue en main,
# blocs sans jointures, rendu allégé. Versions vérifiées pour Minecraft 26.3.
CLIENT_MODS = {
    "Sodium": ("sodium", "mc26.3-0.9.2-fabric"),
    "Iris (shaders)": ("iris", "1.11.7+26.3-fabric"),
    "LambDynamicLights": ("lambdynamiclights", "4.13.0+26.3"),
    "Continuity": ("continuity", "3.0.1+26.3"),
    "EntityCulling": ("entityculling", "1.11.3"),
}
# Packs installés par le launcher dans resourcepacks/ et shaderpacks/, activés selon les réglages du joueur.
EXTRAS = {
    "Textures Faithful 64x": ("faithful-64x", "Release-15-Wilderness-Bound-Drop", "resourcepacks", "hd"),
    "Shaders Complementary Reimagined": ("complementary-reimagined", "r5.9.3", "shaderpacks", "shaders"),
}


def modrinth_file(project, game_version, version_number, loader="fabric"):
    """Fichier principal d'une version Modrinth : adresse, empreintes, taille."""
    q = {"game_versions": json.dumps([game_version])}
    if loader:
        q["loaders"] = json.dumps([loader])
    query = urllib.parse.urlencode(q)
    req = urllib.request.Request(f"{API}/project/{project}/version?{query}", headers={"User-Agent": USER_AGENT})
    versions = json.load(urllib.request.urlopen(req, timeout=30))
    for v in versions:
        if v["version_number"] == version_number:
            f = next((x for x in v["files"] if x.get("primary")), v["files"][0])
            return {"url": f["url"], "sha1": f["hashes"]["sha1"], "sha512": f["hashes"]["sha512"],
                    "size": f["size"], "filename": f["filename"]}
    raise SystemExit(f"Version {version_number} introuvable pour {project} sur Modrinth.")


def gradle_cache_jar(expected_sha1):
    """Le jar du cache Gradle dont l'empreinte SHA-1 est celle publiée sur Modrinth, ou None.

    Gradle range chaque fichier téléchargé dans <groupe>/<module>/<version>/<sha1>/ : on le retrouve donc
    par son empreinte, quel que soit son nom (le dépôt Maven de Modrinth et le CDN ne nomment pas les
    fichiers pareil, par exemple worldedit-7.4.6.jar et worldedit-mod-7.4.6.jar). L'empreinte est
    recalculée avant de répondre.
    """
    cache = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"
    for p in cache.glob(f"*/*/*/{expected_sha1}/*.jar"):
        if not p.name.endswith("-sources.jar") and sha1(p) == expected_sha1:
            return p
    return None


def sha1(path):
    return hashlib.sha1(path.read_bytes()).hexdigest()


def main():
    p = props()
    mc = p["minecraft_version"]
    version = p["version"]
    jar = ROOT / "build" / "libs" / f"valcombe-{version}.jar"
    if not jar.exists():
        raise SystemExit(f"{jar} est introuvable : lancez d'abord ./gradlew build.")
    DIST.mkdir(exist_ok=True)

    # Mods des joueurs et du serveur (« both » dans le manifeste) : le launcher les installe des deux côtés.
    # WorldEdit sert à construire dans les ateliers ; il est réservé aux opérateurs par défaut, et le mod
    # Valcombe lui retire la navigation par objet quand un outil de carte est en main (ToolPriority).
    mods = {
        "Fabric API": modrinth_file("fabric-api", mc, p["fabric_api_version"]),
        "Simple Voice Chat": modrinth_file("simple-voice-chat", mc, p["voicechat_version"]),
        "WorldEdit": modrinth_file("worldedit", mc, p["worldedit_version"]),
    }
    client_mods = {label: modrinth_file(slug, mc, ver) for label, (slug, ver) in CLIENT_MODS.items()}
    extras = {}
    for label, (slug, ver, folder, group) in EXTRAS.items():
        extras[label] = dict(modrinth_file(slug, mc, ver, loader=None), folder=folder, group=group)

    # --- Modpack (.mrpack) -------------------------------------------------
    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": version,
        "name": "Les Cendres de Valcombe",
        "summary": "Jeu de déduction sociale à Conteur, entre amis.",
        "files": [
            {
                "path": f"mods/{m['filename']}",
                "hashes": {"sha1": m["sha1"], "sha512": m["sha512"]},
                "env": {"client": "required", "server": "required"},
                "downloads": [m["url"]],
                "fileSize": m["size"],
            }
            for m in mods.values()
        ] + [
            {
                "path": f"mods/{m['filename']}",
                "hashes": {"sha1": m["sha1"], "sha512": m["sha512"]},
                "env": {"client": "required", "server": "unsupported"},
                "downloads": [m["url"]],
                "fileSize": m["size"],
            }
            for m in client_mods.values()
        ] + [
            {
                "path": f"{m['folder']}/{m['filename']}",
                "hashes": {"sha1": m["sha1"], "sha512": m["sha512"]},
                "env": {"client": "optional", "server": "unsupported"},
                "downloads": [m["url"]],
                "fileSize": m["size"],
            }
            for m in extras.values()
        ],
        "dependencies": {"minecraft": mc, "fabric-loader": p["loader_version"]},
    }
    pack = DIST / f"Valcombe-{version}.mrpack"
    with zipfile.ZipFile(pack, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("modrinth.index.json", json.dumps(index, indent=2, ensure_ascii=False))
        z.write(jar, f"overrides/mods/{jar.name}")
    print(f"Modpack : {pack}")

    # --- Manifeste lu par le launcher Valcombe (joint à la version GitHub) --
    news_file = ROOT / "news.json"
    news = json.loads(news_file.read_text(encoding="utf-8")) if news_file.exists() else []
    server = next((a.split("=", 1)[1] for a in sys.argv[1:] if a.startswith("--serveur=")), "")
    manifest = {
        "version": version,
        "minecraft": mc,
        "loader": p["loader_version"],
        "server": server,
        "mods": [
            {"name": label, "filename": m["filename"], "url": m["url"], "sha512": m["sha512"], "size": m["size"],
             # « client » : graphismes, jamais installés sur un serveur (hébergement depuis le launcher).
             "side": "client" if label in client_mods else "both"}
            for label, m in list(mods.items()) + list(client_mods.items())
        ] + [{
            "side": "both",
            "name": "Les Cendres de Valcombe",
            "filename": jar.name,
            "url": "asset:" + jar.name,
            "sha512": hashlib.sha512(jar.read_bytes()).hexdigest(),
            "size": jar.stat().st_size,
        }],
        "extras": [
            {"name": label, "filename": m["filename"], "url": m["url"], "sha512": m["sha512"], "size": m["size"],
             "folder": m["folder"], "group": m["group"]}
            for label, m in extras.items()
        ],
        "news": news,
    }
    # Launcher annoncé (mise à jour automatique) : l'exe publié doit être joint à la version.
    launcher_exe = ROOT.parent / "launcher" / "publish" / "ValcombeLauncher.exe"
    csproj = (ROOT.parent / "launcher" / "ValcombeLauncher.csproj").read_text(encoding="utf-8")
    if launcher_exe.exists():
        launcher_version = csproj.split("<Version>")[1].split("</Version>")[0]
        manifest["launcher"] = {
            "version": launcher_version,
            "filename": launcher_exe.name,
            "sha512": hashlib.sha512(launcher_exe.read_bytes()).hexdigest(),
            "size": launcher_exe.stat().st_size,
        }
        print(f"Launcher annoncé : {launcher_version}")
    (DIST / "valcombe-pack.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    shutil.copy2(jar, DIST / jar.name)
    print(f"Manifeste du launcher : {DIST / 'valcombe-pack.json'}")
    print(f"À joindre à la version GitHub : valcombe-pack.json, {jar.name}, {pack.name}")

    # --- Dossier serveur ---------------------------------------------------
    server = DIST / "serveur-valcombe"
    (server / "mods").mkdir(parents=True, exist_ok=True)
    # Une seule version de chaque mod : on retire les anciens fichiers.
    keep = {jar.name} | {m["filename"] for m in mods.values()}
    for old in (server / "mods").glob("*.jar"):
        if old.name not in keep:
            old.unlink()
    shutil.copy2(jar, server / "mods" / jar.name)
    # Les mods publiés sont dans le cache Gradle (build.gradle les charge pour les lancements de développement).
    for label, m in mods.items():
        cached = gradle_cache_jar(m["sha1"])
        target = server / "mods" / m["filename"]
        if cached:
            shutil.copy2(cached, target)
            print(f"  {label} : copié depuis le cache Gradle (empreinte vérifiée)")
        else:
            print(f"  {label} : à télécharger vous-même → {m['url']}")
    props_file = server / "server.properties"
    if not props_file.exists():
        props_file.write_text(
            "motd=Les Cendres de Valcombe\n"
            "white-list=true\n"
            "enforce-whitelist=true\n"
            "online-mode=true\n"
            "difficulty=peaceful\n"
            "gamemode=adventure\n"
            "pvp=false\n"
            "spawn-protection=0\n"
            "generate-structures=false\n"
            + FLAT_WORLD,
            encoding="utf-8")
    (server / "demarrer.bat").write_text(
        "@echo off\r\n"
        "rem Lance le serveur. Il faut Java 25 et le fichier fabric-server-launch.jar (voir INSTALLATION.md).\r\n"
        "java -Xmx4G -jar fabric-server-launch.jar nogui\r\n"
        "pause\r\n", encoding="utf-8")
    # Serveur loué sous Linux (voir HEBERGEMENT.md) : lancement, service systemd, sauvegardes.
    linux = {
        "demarrer.sh": (
            "#!/bin/sh\n"
            "# Lance le serveur (Java 25 et fabric-server-launch.jar requis, voir HEBERGEMENT.md).\n"
            "cd \"$(dirname \"$0\")\"\n"
            "exec java -Xms1G -Xmx${VALCOMBE_RAM:-4G} -jar fabric-server-launch.jar nogui\n"),
        "sauvegarder.sh": (
            "#!/bin/sh\n"
            "# Sauvegarde le monde et les réglages de Valcombe ; garde les 14 dernières archives.\n"
            "set -e\n"
            "cd \"$(dirname \"$0\")\"\n"
            "mkdir -p sauvegardes\n"
            "tar -czf \"sauvegardes/valcombe-$(date +%Y-%m-%d_%H%M).tar.gz\" world config server.properties whitelist.json ops.json 2>/dev/null || true\n"
            "ls -1t sauvegardes/valcombe-*.tar.gz | tail -n +15 | xargs -r rm --\n"),
        "valcombe.service": (
            "[Unit]\n"
            "Description=Serveur Minecraft Les Cendres de Valcombe\n"
            "After=network-online.target\n"
            "\n"
            "[Service]\n"
            "User=valcombe\n"
            "WorkingDirectory=/opt/valcombe\n"
            "ExecStart=/opt/valcombe/demarrer.sh\n"
            "Restart=on-failure\n"
            "RestartSec=10\n"
            "\n"
            "[Install]\n"
            "WantedBy=multi-user.target\n"),
    }
    for name, text in linux.items():
        with open(server / name, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
    print(f"Serveur : {server}")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.URLError as e:
        sys.exit(f"Modrinth injoignable : {e}")
