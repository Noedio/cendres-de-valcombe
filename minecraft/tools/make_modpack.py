"""Fabrique le modpack et le dossier serveur des Cendres de Valcombe.

Lancer depuis le dossier minecraft, après ./gradlew build :
    python tools/make_modpack.py [--serveur=adresse:port]

Produit dans dist/ :
  - Valcombe-<version>.mrpack : à importer dans Modrinth App ou Prism Launcher.
    Les mods publiés (Fabric API, Simple Voice Chat) y sont référencés par leur
    adresse Modrinth et leurs empreintes ; le mod Valcombe est inclus tel quel.
  - serveur-valcombe/ : les mods du serveur, des réglages de départ, un script de lancement.
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


def props():
    out = {}
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.strip().startswith("#"):
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def modrinth_file(project, game_version, version_number):
    """Fichier principal d'une version Modrinth : adresse, empreintes, taille."""
    query = urllib.parse.urlencode({
        "game_versions": json.dumps([game_version]),
        "loaders": json.dumps(["fabric"]),
    })
    req = urllib.request.Request(f"{API}/project/{project}/version?{query}", headers={"User-Agent": USER_AGENT})
    versions = json.load(urllib.request.urlopen(req, timeout=30))
    for v in versions:
        if v["version_number"] == version_number:
            f = next((x for x in v["files"] if x.get("primary")), v["files"][0])
            return {"url": f["url"], "sha1": f["hashes"]["sha1"], "sha512": f["hashes"]["sha512"],
                    "size": f["size"], "filename": f["filename"]}
    raise SystemExit(f"Version {version_number} introuvable pour {project} sur Modrinth.")


def gradle_cache_jar(name_part):
    cache = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"
    for p in cache.rglob(f"*{name_part}*.jar"):
        if "sources" not in p.name:
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

    mods = {
        "Fabric API": modrinth_file("fabric-api", mc, p["fabric_api_version"]),
        "Simple Voice Chat": modrinth_file("simple-voice-chat", mc, p["voicechat_version"]),
    }

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
            {"name": label, "filename": m["filename"], "url": m["url"], "sha512": m["sha512"], "size": m["size"]}
            for label, m in mods.items()
        ] + [{
            "name": "Les Cendres de Valcombe",
            "filename": jar.name,
            "url": "asset:" + jar.name,
            "sha512": hashlib.sha512(jar.read_bytes()).hexdigest(),
            "size": jar.stat().st_size,
        }],
        "news": news,
    }
    (DIST / "valcombe-pack.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    shutil.copy2(jar, DIST / jar.name)
    print(f"Manifeste du launcher : {DIST / 'valcombe-pack.json'}")
    print(f"À joindre à la version GitHub : valcombe-pack.json, {jar.name}, {pack.name}")

    # --- Dossier serveur ---------------------------------------------------
    server = DIST / "serveur-valcombe"
    (server / "mods").mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar, server / "mods" / jar.name)
    for label, m in mods.items():
        cached = gradle_cache_jar(m["filename"].replace(".jar", "").replace("voicechat-", "simple-voice-chat-"))
        if cached is None:
            cached = gradle_cache_jar(m["filename"].replace(".jar", ""))
        target = server / "mods" / m["filename"]
        if cached and sha1(cached) == m["sha1"]:
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
            "spawn-protection=0\n",
            encoding="utf-8")
    (server / "demarrer.bat").write_text(
        "@echo off\r\n"
        "rem Lance le serveur. Il faut Java 25 et le fichier fabric-server-launch.jar (voir INSTALLATION.md).\r\n"
        "java -Xmx4G -jar fabric-server-launch.jar nogui\r\n"
        "pause\r\n", encoding="utf-8")
    print(f"Serveur : {server}")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.URLError as e:
        sys.exit(f"Modrinth injoignable : {e}")
