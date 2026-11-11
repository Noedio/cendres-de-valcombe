"""Génère les modèles 3D du village (jetons, trône, lutrin) et leurs textures originales.

Lancer depuis le dossier minecraft :  python tools/make_models.py
(après tools/make_assets.py, dont il reprend les pictogrammes des rôles).

Sortie dans src/client/resources/assets/valcombe :
  items/<nom>.json           définitions d'objets (composant minecraft:item_model)
  models/item/<nom>.json     modèles 3D
  textures/item/scene/*.png  textures des jetons
Les entités d'affichage du serveur portent ces modèles : rien à installer en plus du mod.
"""
import json
import math
import re
import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_assets as A  # noqa: E402  (palette et outil de dessin)

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/client/resources/assets/valcombe"
TEX = ASSETS / "textures/item/scene"
ROLE_ICONS = ASSETS / "textures/gui/roles"
ROLE_JAVA = ROOT / "src/main/java/fr/valcombe/rules/Role.java"

GREY = (120, 116, 118)
GREY_DARK = (70, 66, 68)


def save(img, path):
    raw = b"".join(b"\x00" + bytes(v for px in row for v in px) for row in img.px)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    data = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", img.w, img.h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def load(path):
    """Lit un PNG RGBA 8 bits écrit par make_assets (lignes sans filtre)."""
    data = path.read_bytes()
    w, h = struct.unpack(">II", data[16:24])
    pos, idat = 8, b""
    while pos < len(data):
        n = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        if kind == b"IDAT":
            idat += data[pos + 8:pos + 8 + n]
        pos += 12 + n
    raw = zlib.decompress(idat)
    img = A.Img(w, h)
    stride = w * 4 + 1
    for y in range(h):
        row = raw[y * stride:(y + 1) * stride]
        if row[0] != 0:
            raise ValueError(f"{path.name} : filtre PNG {row[0]} non pris en charge")
        for x in range(w):
            img.px[y][x] = list(row[1 + x * 4:5 + x * 4])
    return img


# ---------------------------------------------------------------------------
# Textures des jetons (32 × 32)
# ---------------------------------------------------------------------------
def token_base(face, ring, inner):
    img = A.Img(32, 32)
    img.disc(16, 16, 15.5, ring)
    img.disc(16, 16, 13, inner)
    img.ring(16, 16, 13.5, 1, A.INK)
    for k in range(12):  # rivets
        a = 2 * math.pi * k / 12
        img.disc(16 + 14.3 * math.cos(a), 16 + 14.3 * math.sin(a), 0.8, face)
    return img


def crest():
    """Dos des jetons : la tour de Valcombe sur fond lie-de-vin."""
    img = token_base(A.GOLD, A.BRONZE, A.WINE)
    img.rect(13, 9, 19, 23, A.GOLD)
    img.rect(11, 21, 21, 23, A.GOLD)
    for x in (12, 15, 18):
        img.rect(x, 7, x + 2, 10, A.GOLD)
    img.rect(15, 14, 17, 18, A.WINE)
    img.disc(16, 13, 1.2, A.WINE)
    return img


def dead():
    img = token_base(A.ASH, GREY_DARK, GREY)
    img.disc(16, 14, 6, A.WHITE)  # crâne
    img.rect(13, 18, 19, 22, A.WHITE)
    img.disc(13.5, 14, 1.8, A.INK)
    img.disc(18.5, 14, 1.8, A.INK)
    img.poly([(16, 16), (15, 18), (17, 18)], A.INK)
    for x in (14, 16, 18):
        img.rect(x, 20, x + 1, 22, A.INK)
    return img


def ghost():
    img = token_base(A.GOLD, GREY_DARK, GREY)
    img.rect(14, 13, 18, 24, A.PARCH)  # cierge
    img.rect(14, 23, 18, 24, A.PARCH_DARK)
    img.rect(15, 11, 17, 13, A.INK)
    img.disc(16, 9, 2.4, (240, 170, 60))
    img.disc(16, 9.6, 1.2, (255, 235, 160))
    return img


def edge():
    img = A.Img(16, 16)
    A.noise_fill(img, A.BRONZE_DARK, 12, 7)
    return img


def role_teams():
    teams = {}
    for m in re.finditer(r"^\s*([A-Z_]+)\(\"[^\"]*\",\s*RoleType\.([A-Z]+)", ROLE_JAVA.read_text(encoding="utf-8"), re.M):
        teams[m.group(1).lower()] = "evil" if m.group(2) in ("SBIRE", "DEMON") else "good"
    return teams


def role_face(icon_path, team):
    ring = A.EVIL if team == "evil" else A.GOOD
    img = token_base(A.GOLD, ring, A.PARCH)
    icon = load(icon_path)
    # Pictogramme réduit au centre (24 × 24 sur 32 × 32)
    for y in range(32):
        for x in range(32):
            sx, sy = int((x - 4) * 32 / 24), int((y - 4) * 32 / 24)
            if 4 <= x < 28 and 4 <= y < 28 and 0 <= sx < 32 and 0 <= sy < 32:
                c = icon.px[sy][sx]
                if c[3] > 0 and math.hypot(x + 0.5 - 16, y + 0.5 - 16) < 12.5:
                    img.put(x, y, c[:3], c[3] / 255)
    return img


# ---------------------------------------------------------------------------
# Modèles
# ---------------------------------------------------------------------------
def face(tex, uv=(0, 0, 16, 16)):
    return {"texture": tex, "uv": list(uv)}


def box(frm, to, tex, faces=("north", "south", "east", "west", "up", "down"), uv=None):
    return {"from": frm, "to": to, "faces": {f: face(tex, uv or (0, 0, 16, 16)) for f in faces}}


def token_model(front, back):
    """Disque plat : face avant, dos à la tour, tranche bronze."""
    return {
        "textures": {"front": front, "back": back, "edge": "valcombe:item/scene/tranche", "particle": back},
        "elements": [{
            "from": [0, 0, 7.5], "to": [16, 16, 8.5],
            "faces": {
                # Une entité d'affichage tournée vers le joueur lui montre sa face nord.
                "north": face("#front"), "south": face("#back"),
                "east": face("#edge", (0, 0, 1, 16)), "west": face("#edge", (0, 0, 1, 16)),
                "up": face("#edge", (0, 0, 16, 1)), "down": face("#edge", (0, 0, 16, 1)),
            },
        }],
    }


def throne_model():
    """Trône sculpté ; le dossier est du côté +Z (sud)."""
    wood = "#wood"
    return {
        "textures": {"wood": "minecraft:block/dark_oak_planks", "trim": "minecraft:block/gold_block",
                     "cushion": "minecraft:block/red_wool", "particle": "minecraft:block/dark_oak_planks"},
        "elements": [
            box([2, 0, 2], [4, 7, 4], wood), box([12, 0, 2], [14, 7, 4], wood),
            box([2, 0, 12], [4, 7, 14], wood), box([12, 0, 12], [14, 7, 14], wood),
            box([1, 7, 1], [15, 9, 15], wood),
            box([2, 9, 2], [14, 10, 13], "#cushion"),
            box([1, 9, 13], [15, 26, 15], wood),
            box([3, 11, 12.5], [13, 24, 13], "#cushion", faces=("north",)),
            box([0.5, 26, 12.5], [15.5, 27, 15.5], "#trim"),
            box([7, 27, 13], [9, 30, 15], "#trim"),
            box([1, 9, 1], [3, 14, 13], wood), box([13, 9, 1], [15, 14, 13], wood),
            box([0.5, 14, 0.5], [3.5, 15, 3.5], "#trim"), box([12.5, 14, 0.5], [15.5, 15, 3.5], "#trim"),
        ],
    }


def lectern_model():
    """Lutrin portant un grand livre ouvert ; le lecteur se tient côté +Z."""
    return {
        "textures": {"wood": "minecraft:block/dark_oak_planks", "page": "minecraft:block/white_wool",
                     "cover": "minecraft:block/red_wool", "trim": "minecraft:block/gold_block",
                     "particle": "minecraft:block/dark_oak_planks"},
        "elements": [
            box([4, 0, 4], [12, 2, 12], "#wood"),
            box([7, 2, 7], [9, 14, 9], "#wood"),
            {"from": [2, 14, 3], "to": [14, 15, 13], "rotation": {"angle": 22.5, "axis": "x", "origin": [8, 14, 8]},
             "faces": {f: face("#cover") for f in ("north", "south", "east", "west", "up", "down")}},
            {"from": [2.5, 15, 3.5], "to": [7.8, 16, 12.5], "rotation": {"angle": 22.5, "axis": "x", "origin": [8, 14, 8]},
             "faces": {f: face("#page") for f in ("north", "south", "east", "west", "up", "down")}},
            {"from": [8.2, 15, 3.5], "to": [13.5, 16, 12.5], "rotation": {"angle": 22.5, "axis": "x", "origin": [8, 14, 8]},
             "faces": {f: face("#page") for f in ("north", "south", "east", "west", "up", "down")}},
            {"from": [7.8, 15, 3.5], "to": [8.2, 16.2, 12.5], "rotation": {"angle": 22.5, "axis": "x", "origin": [8, 14, 8]},
             "faces": {f: face("#trim") for f in ("north", "south", "east", "west", "up", "down")}},
        ],
    }


def write_item(name, model):
    model_path = ASSETS / "models/item" / f"{name}.json"
    model_path.parent.mkdir(parents=True, exist_ok=True)
    model_path.write_text(json.dumps(model, indent=1), encoding="utf-8")
    item_path = ASSETS / "items" / f"{name}.json"
    item_path.parent.mkdir(parents=True, exist_ok=True)
    item_path.write_text(json.dumps({"model": {"type": "minecraft:model", "model": f"valcombe:item/{name}"}}, indent=1),
                         encoding="utf-8")


# ---------------------------------------------------------------------------
# Coiffes des morts (portées sur la tête)
# ---------------------------------------------------------------------------
# Sur la tête, l'objet est réduit à 0,625 et centré sur la tête : la tête occupe
# les coordonnées 1,6 à 14,4 du modèle. La face « north » est vue de face.
def solid(color, noise=10, seed=1):
    import random
    rnd = random.Random(seed)
    img = A.Img(16, 16)
    for y in range(16):
        for x in range(16):
            d = rnd.randint(-noise, noise)
            img.px[y][x] = [max(0, min(255, c + d)) for c in color] + [255]
    return img


def skull_face():
    img = solid((226, 219, 196), 8, 3)
    dark = [40, 30, 34, 255]
    for x, y in [(3, 6), (4, 6), (5, 6), (3, 7), (4, 7), (5, 7), (4, 8),
                 (10, 6), (11, 6), (12, 6), (10, 7), (11, 7), (12, 7), (11, 8)]:
        img.px[y][x] = dark[:]
    for x, y in [(7, 9), (8, 9), (7, 10), (8, 10)]:
        img.px[y][x] = [90, 76, 70, 255]
    for x in range(4, 12):
        img.px[12][x] = dark[:] if x % 2 == 0 else [200, 192, 170, 255]
    # Lueur d'âme au fond des orbites
    img.px[7][4] = [110, 220, 230, 255]
    img.px[7][11] = [110, 220, 230, 255]
    return img


def hat_model(candle):
    tex = {"felt": "valcombe:item/scene/feutre", "band": "valcombe:item/scene/ruban",
           "wax": "valcombe:item/scene/cire", "flame": "valcombe:item/scene/flamme",
           "particle": "valcombe:item/scene/feutre"}
    el = [
        box([-0.5, 14.2, -0.5], [16.5, 15.2, 16.5], "#felt"),          # bord large
        box([3, 15.2, 3], [13, 22.5, 13], "#felt"),                    # calotte
        box([2.8, 15.2, 2.8], [13.2, 16.6, 13.2], "#band"),            # ruban d'âme
    ]
    if candle:
        el += [box([7, 22.5, 7], [9, 25.5, 9], "#wax"), box([7.5, 25.5, 7.5], [8.5, 27, 8.5], "#flame")]
    return {"textures": tex, "elements": el}


def skull_model(candle):
    tex = {"bone": "valcombe:item/scene/os", "face": "valcombe:item/scene/crane",
           "wax": "valcombe:item/scene/cire", "flame": "valcombe:item/scene/flamme",
           "particle": "valcombe:item/scene/os"}
    el = [{"from": [1, 1, 1], "to": [15, 15, 15],
           "faces": {"north": face("#face"), "south": face("#bone"), "east": face("#bone"),
                     "west": face("#bone"), "up": face("#bone"), "down": face("#bone")}},
          box([3, -1, 1], [13, 1, 6], "#bone")]                           # mâchoire
    if candle:
        el += [box([7, 15, 7], [9, 18, 9], "#wax"), box([7.5, 18, 7.5], [8.5, 19.5, 8.5], "#flame")]
    return {"textures": tex, "elements": el}


def write_hats():
    save(solid((34, 30, 38), 6, 1), TEX / "feutre.png")
    save(solid((92, 196, 206), 14, 2), TEX / "ruban.png")
    save(solid((236, 228, 206), 6, 4), TEX / "cire.png")
    save(solid((255, 190, 70), 30, 5), TEX / "flamme.png")
    save(solid((214, 206, 184), 10, 6), TEX / "os.png")
    save(skull_face(), TEX / "crane.png")
    write_item("chapeau_mort", hat_model(False))
    write_item("chapeau_mort_vote", hat_model(True))
    write_item("crane_mort", skull_model(False))
    write_item("crane_mort_vote", skull_model(True))


def main():
    save(crest(), TEX / "dos.png")
    save(dead(), TEX / "mort.png")
    save(ghost(), TEX / "fantome.png")
    save(edge(), TEX / "tranche.png")
    back = "valcombe:item/scene/dos"
    write_item("jeton_vivant", token_model(back, back))
    write_item("jeton_mort", token_model("valcombe:item/scene/mort", back))
    write_item("jeton_fantome", token_model("valcombe:item/scene/fantome", back))
    teams = role_teams()
    for icon in sorted(ROLE_ICONS.glob("*.png")):
        role = icon.stem
        save(role_face(icon, teams.get(role, "good")), TEX / f"role_{role}.png")
        write_item(f"jeton_{role}", token_model(f"valcombe:item/scene/role_{role}", back))
        # Rôles créés : n'importe quel emblème, aux couleurs de n'importe quel camp
        for team, suffix in (("good", "bien"), ("evil", "mal")):
            save(role_face(icon, team), TEX / f"role_{role}_{suffix}.png")
            write_item(f"jeton_{role}_{suffix}", token_model(f"valcombe:item/scene/role_{role}_{suffix}", back))
    write_item("trone", throne_model())
    write_item("lutrin", lectern_model())
    write_hats()
    print(f"{len(teams)} rôles, modèles écrits dans {ASSETS}")


if __name__ == "__main__":
    main()
