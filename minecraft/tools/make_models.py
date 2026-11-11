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
S = 2  # les jetons sont dessinés en 64 × 64 (coordonnées ci-dessous en 32 × 32, multipliées par S)
N = 32 * S


def lit(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3])


def token_base(face, ring, inner):
    """Jeton 64 × 64 : anneau de métal bombé éclairé d'en haut à gauche, liseré, intérieur ombré."""
    img = A.Img(N, N)
    cx = cy = N / 2
    r_out, r_in = 15.6 * S, 12.6 * S
    for y in range(N):
        for x in range(N):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            if d > r_out + 0.5:
                continue
            a = min(1.0, r_out + 0.5 - d)
            if d >= r_in:
                # Anneau : normale radiale, bombé (plus clair vers le milieu de l'anneau)
                t = (d - r_in) / (r_out - r_in)
                bulge = 1 - abs(t - 0.45) * 1.3
                light = (-dx - dy) / (d * 1.4142 + 1e-6)
                f = 0.7 + 0.25 * bulge + 0.3 * light
                img.put(x, y, lit(ring, f), a)
                if 0.35 < t < 0.55 and light > 0.55:
                    img.put(x, y, A.WHITE, 0.35 * (light - 0.55) / 0.45)
            else:
                # Intérieur : léger vignettage, et ombre portée de l'anneau en haut à gauche
                v = 1 - 0.18 * (d / r_in) ** 2
                shadow = max(0.0, (dx + dy) / (d * 1.4142 + 1e-6)) if d > r_in - 3 * S else 0
                img.put(x, y, lit(inner, v - 0.12 * (1 - shadow) * (d > r_in - 2.5 * S)))
    img.ring(cx, cy, r_in, 1.2, A.INK)
    img.ring(cx, cy, r_out, 1.0, lit(ring, 0.45))
    for k in range(12):  # rivets en relief
        ang = 2 * math.pi * k / 12
        rx, ry = cx + 14.1 * S * math.cos(ang), cy + 14.1 * S * math.sin(ang)
        img.disc(rx, ry, 1.5 * S * 0.6, lit(face, 0.55))
        img.disc(rx - 0.4, ry - 0.4, 1.2 * S * 0.5, face)
        img.put(rx - 1, ry - 1, A.WHITE, 0.7)
    return img


def sc(*v):
    return [c * S for c in v]


def crest():
    """Dos des jetons : la tour de Valcombe sur fond lie-de-vin."""
    img = token_base(A.GOLD, A.BRONZE, A.WINE)
    img.rect(*sc(13, 9, 19, 23), A.GOLD)
    img.rect(*sc(11, 21, 21, 23), A.GOLD)
    for x in (12, 15, 18):
        img.rect(*sc(x, 7, x + 2, 10), A.GOLD)
    img.rect(*sc(15, 14, 17, 18), A.WINE)
    img.disc(16 * S, 13 * S, 1.2 * S, A.WINE)
    for y in range(9 * S, 23 * S):  # ombrage de la tour
        img.put(18 * S + 1, y, lit(A.GOLD, 0.7))
        img.put(13 * S, y, lit(A.GOLD, 1.2))
    return img


def dead():
    img = token_base(A.ASH, GREY_DARK, GREY)
    img.disc(16 * S, 14 * S, 6 * S, A.WHITE)  # crâne
    img.rect(*sc(13, 18, 19, 22), A.WHITE)
    img.disc(13.5 * S, 14 * S, 1.8 * S, A.INK)
    img.disc(18.5 * S, 14 * S, 1.8 * S, A.INK)
    img.poly([(16 * S, 16 * S), (15 * S, 18 * S), (17 * S, 18 * S)], A.INK)
    for x in (14, 16, 18):
        img.rect(*sc(x, 20, x + 1, 22), A.INK)
    return img


def ghost():
    img = token_base(A.GOLD, GREY_DARK, GREY)
    img.rect(*sc(14, 13, 18, 24), A.PARCH)  # cierge
    img.rect(*sc(14, 23, 18, 24), A.PARCH_DARK)
    img.rect(*sc(15, 11, 17, 13), A.INK)
    img.disc(16 * S, 9 * S, 2.4 * S, (240, 170, 60))
    img.disc(16 * S, 9.6 * S, 1.2 * S, (255, 235, 160))
    return img


def edge():
    """Tranche du jeton : métal moleté (stries), plus clair au centre."""
    img = A.Img(16, 16)
    for y in range(16):
        for x in range(16):
            f = 0.85 + 0.25 * (1 - abs(y - 7.5) / 8) + (0.12 if x % 2 == 0 else -0.05)
            img.px[y][x] = list(lit(A.BRONZE, f)) + [255]
    return img


def role_teams():
    teams = {}
    for m in re.finditer(r"^\s*([A-Z_]+)\(\"[^\"]*\",\s*RoleType\.([A-Z]+)", ROLE_JAVA.read_text(encoding="utf-8"), re.M):
        teams[m.group(1).lower()] = "evil" if m.group(2) in ("SBIRE", "DEMON") else "good"
    return teams


def role_face(icon_path, team):
    """Pictogramme du rôle réduit avec lissage (moyenne de zones) au centre du jeton 64 × 64."""
    ring = A.EVIL if team == "evil" else A.GOOD
    img = token_base(A.GOLD, ring, A.PARCH)
    icon = load(icon_path)
    size = 40 * S // 2  # 40 px sur 64
    off = (N - size) / 2
    k = icon.w / size
    for y in range(size):
        for x in range(size):
            acc = [0.0, 0.0, 0.0, 0.0]
            n = 0
            for sy in range(int(y * k), max(int(y * k) + 1, int((y + 1) * k))):
                for sx in range(int(x * k), max(int(x * k) + 1, int((x + 1) * k))):
                    c = icon.px[min(sy, icon.h - 1)][min(sx, icon.w - 1)]
                    al = c[3] / 255
                    for i in range(3):
                        acc[i] += c[i] * al
                    acc[3] += al
                    n += 1
            if acc[3] <= 0:
                continue
            col = [acc[i] / acc[3] for i in range(3)]
            px, py = x + off, y + off
            if math.hypot(px + 0.5 - N / 2, py + 0.5 - N / 2) < 12.4 * S:
                img.put(px + 1, py + 1, A.INK, 0.25 * acc[3] / n)  # ombre portée
                img.put(px, py, col, acc[3] / n)
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
                "east": face("#edge", (0, 0, 1.5, 16)), "west": face("#edge", (0, 0, 1.5, 16)),
                "up": face("#edge", (0, 0, 16, 1.5)), "down": face("#edge", (0, 0, 16, 1.5)),
            },
        }],
    }


def throne_model():
    """Trône sculpté ; le dossier est du côté +Z (sud). Pieds tournés, coussin de velours,
    accoudoirs à volutes, haut dossier encadré d'or, montants à pommeaux et fronton."""
    wood, dark, trim, vel = "#wood", "#dark", "#trim", "#cushion"
    el = [
        # Pieds tournés et sabots dorés
        box([1.5, 0, 1.5], [3.5, 7, 3.5], dark), box([12.5, 0, 1.5], [14.5, 7, 3.5], dark),
        box([1.5, 0, 12.5], [3.5, 7, 14.5], dark), box([12.5, 0, 12.5], [14.5, 7, 14.5], dark),
        box([1, 0, 1], [4, 1, 4], trim), box([12, 0, 1], [15, 1, 4], trim),
        box([1, 0, 12], [4, 1, 15], trim), box([12, 0, 12], [15, 1, 15], trim),
        # Traverses basses
        box([3.5, 2, 2], [12.5, 3, 3], wood), box([3.5, 2, 13], [12.5, 3, 14], wood),
        # Assise et coussin de velours (qui déborde un peu)
        box([1, 7, 1], [15, 9, 15], wood),
        box([0.8, 8.6, 0.8], [15.2, 9, 1.4], trim),
        box([2, 9, 1.5], [14, 10.6, 13], vel),
        # Montants du dossier et pommeaux
        box([1, 9, 12.5], [3, 29, 15], dark), box([13, 9, 12.5], [15, 29, 15], dark),
        box([0.5, 29, 12], [3.5, 31, 15.5], trim), box([12.5, 29, 12], [15.5, 31, 15.5], trim),
        # Dossier : panneau, cadre doré, velours capitonné
        box([3, 10, 13.5], [13, 28, 14.5], wood),
        box([3.5, 11, 13], [12.5, 27, 13.5], trim),
        box([4, 11.5, 12.6], [12, 26.5, 13], vel),
        # Fronton en gradins et losange doré
        box([4, 28, 13], [12, 29.5, 15], wood), box([5.5, 29.5, 13], [10.5, 31, 15], wood),
        box([7, 31, 13.2], [9, 32, 14.8], trim),
        box([7.2, 27.3, 12.4], [8.8, 28.9, 12.7], trim),
        # Accoudoirs et volutes
        box([1, 13, 3], [3, 14.5, 12.5], dark), box([13, 13, 3], [15, 14.5, 12.5], dark),
        box([0.6, 9, 1.2], [3.4, 15.5, 3.6], wood), box([12.6, 9, 1.2], [15.4, 15.5, 3.6], wood),
        box([0.4, 15.5, 1], [3.6, 16.3, 3.8], trim), box([12.4, 15.5, 1], [15.6, 16.3, 3.8], trim),
    ]
    return {
        "textures": {"wood": "minecraft:block/dark_oak_planks", "dark": "minecraft:block/stripped_dark_oak_log",
                     "trim": "minecraft:block/gold_block", "cushion": "minecraft:block/red_wool",
                     "particle": "minecraft:block/dark_oak_planks"},
        "elements": el,
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



# ---------------------------------------------------------------------------
# Objets du jeu (en main), dessinés en 32 × 32 (deux fois la définition des objets de Minecraft)
# ---------------------------------------------------------------------------
LEATHER = (92, 38, 30)
BRASS = (201, 160, 72)
WOOD = (104, 70, 42)
PAGE = (236, 222, 188)


def outline(img, color=A.INK):
    """Contour sombre d'un pixel autour de la silhouette (lisibilité, style des objets du jeu)."""
    mask = [[img.px[y][x][3] > 40 for x in range(img.w)] for y in range(img.h)]
    for y in range(img.h):
        for x in range(img.w):
            if mask[y][x]:
                continue
            near = any(0 <= x + dx < img.w and 0 <= y + dy < img.h and mask[y + dy][x + dx]
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if near:
                img.px[y][x] = list(color) + [255]
    return img


def quill():
    """Plume d'accusation : plume d'oie blanche, barbes grises, pointe trempée d'encre rouge."""
    img = A.Img(32, 32)
    # Barbes (de part et d'autre du rachis, en diagonale bas-gauche → haut-droite)
    for t in range(0, 100):
        u = t / 100
        cx, cy = 7 + 21 * u, 25 - 21 * u
        width = 5.5 * math.sin(math.pi * min(1, u * 1.15)) if u > 0.18 else 0
        for k in range(1, int(width) + 1):
            shade = 1.0 - 0.06 * k + (0.08 if (t // 6) % 2 else 0)
            img.put(cx - k * 0.7, cy - k * 0.7, lit(A.WHITE, shade))
            img.put(cx + k * 0.7, cy + k * 0.7, lit((214, 210, 204), shade))
    img.line(4, 28, 28, 4, (236, 226, 200), 1.2)  # rachis
    img.line(3, 29, 7, 25, (150, 24, 24), 1.6)     # pointe à l'encre rouge
    img.put(3, 29, (90, 10, 10))
    return outline(img)


def staff():
    """Bâton du Conteur : bois sculpté, bagues de laiton, couronne tenant une pierre d'âme lumineuse."""
    img = A.Img(32, 32)
    img.line(4, 29, 22, 11, lit(WOOD, 0.8), 3.0)
    img.line(4, 28, 21, 11, WOOD, 1.6)
    for u in (0.3, 0.62):  # bagues de laiton
        x, y = 4 + 18 * u, 29 - 18 * u
        img.disc(x, y, 1.9, BRASS)
        img.put(x - 1, y - 1, A.WHITE, 0.6)
    # Couronne et pierre
    for a in (-0.9, 0, 0.9):
        img.line(22, 10, 22 + 5 * math.cos(a - 0.8), 10 - 5 * math.sin(a + 0.8), BRASS, 1.3)
    img.disc(25.5, 6.5, 3.6, (60, 170, 190))
    img.disc(25.5, 6.5, 2.6, (120, 225, 235))
    img.disc(24.6, 5.4, 1.0, A.WHITE)
    return outline(img)


def square():
    """Équerre du bâtisseur : laiton gradué et fil à plomb."""
    img = A.Img(32, 32)
    img.rect(5, 4, 9, 28, BRASS)
    img.rect(5, 24, 28, 28, BRASS)
    img.rect(5, 4, 6, 28, A.WHITE + (90,))
    img.rect(5, 24, 28, 25, A.WHITE + (90,))
    for y in range(7, 23, 3):
        img.rect(8, y, 9, y + 1, lit(BRASS, 0.5))
    for x in range(10, 27, 3):
        img.rect(x, 27, x + 1, 28, lit(BRASS, 0.5))
    img.line(20, 6, 20, 17, (200, 200, 200), 0.8)
    img.disc(20, 19, 2.2, (150, 150, 160))
    img.put(19, 18, A.WHITE)
    return outline(img)


def leather():
    img = A.Img(32, 32)
    import random
    rnd = random.Random(5)
    for y in range(32):
        for x in range(32):
            img.px[y][x] = list(lit(LEATHER, 0.9 + rnd.uniform(-0.08, 0.08))) + [255]
    for i in range(32):  # filets dorés à la bordure
        for t in (2, 29):
            img.put(i, t, BRASS, 0.8)
            img.put(t, i, BRASS, 0.8)
    # Tour de Valcombe dorée au centre de la couverture
    img.rect(14, 9, 18, 21, BRASS)
    img.rect(12, 19, 20, 21, BRASS)
    for x in (13, 15, 17):
        img.rect(x, 7, x + 2, 10, BRASS)
    img.rect(15, 13, 17, 16, LEATHER)
    return img


def pages():
    img = A.Img(32, 32)
    for y in range(32):
        for x in range(32):
            img.px[y][x] = list(lit(PAGE, 0.95 if y % 2 else 0.82)) + [255]
    return img


def clasp():
    img = A.Img(16, 16)
    for y in range(16):
        for x in range(16):
            img.px[y][x] = list(lit(BRASS, 1.1 - 0.4 * y / 16)) + [255]
    return img


def grimoire_model():
    """Grimoire de poche (menu du jeu) : couverture de cuir, tranche de pages, fermoir de laiton."""
    tex = {"cover": "valcombe:item/objets/cuir", "pages": "valcombe:item/objets/pages",
           "clasp": "valcombe:item/objets/fermoir", "particle": "valcombe:item/objets/cuir"}
    el = [
        {"from": [3, 1, 6.5], "to": [13, 15, 9.5], "faces": {
            "north": face("#cover"), "south": face("#cover"), "west": face("#cover", (0, 0, 3, 16)),
            "east": face("#pages", (0, 0, 3, 16)), "up": face("#pages", (0, 0, 16, 3)), "down": face("#pages", (0, 0, 16, 3))}},
        box([12.5, 6.5, 6], [14, 9.5, 10], "#clasp"),
    ]
    display = {
        "gui": {"rotation": [20, -35, 0], "scale": [1.05, 1.05, 1.05]},
        "ground": {"translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
        "fixed": {"scale": [0.9, 0.9, 0.9]},
        "thirdperson_righthand": {"rotation": [0, -90, 20], "translation": [0, 1, -1], "scale": [0.5, 0.5, 0.5]},
        "firstperson_righthand": {"rotation": [0, -110, 15], "translation": [2, 2, 0], "scale": [0.55, 0.55, 0.55]},
        "firstperson_lefthand": {"rotation": [0, 70, -15], "translation": [2, 2, 0], "scale": [0.55, 0.55, 0.55]},
    }
    return {"textures": tex, "elements": el, "display": display}


def flat_item(texture, handheld=False):
    return {"parent": "minecraft:item/handheld" if handheld else "minecraft:item/generated",
            "textures": {"layer0": texture}}


def write_objects():
    obj = TEX.parent / "objets"
    save(quill(), obj / "plume.png")
    save(staff(), obj / "baton.png")
    save(square(), obj / "equerre.png")
    save(leather(), obj / "cuir.png")
    save(pages(), obj / "pages.png")
    save(clasp(), obj / "fermoir.png")
    write_item("plume", flat_item("valcombe:item/objets/plume", handheld=True))
    write_item("baton", flat_item("valcombe:item/objets/baton", handheld=True))
    write_item("equerre", flat_item("valcombe:item/objets/equerre", handheld=True))
    write_item("grimoire", grimoire_model())


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
        # Icône plate du rôle, pour les menus en coffre (le jeton 3D se lit mal de face dans un inventaire)
        write_item(f"icone_{role}", flat_item(f"valcombe:item/scene/role_{role}"))
        # Rôles créés : n'importe quel emblème, aux couleurs de n'importe quel camp
        for team, suffix in (("good", "bien"), ("evil", "mal")):
            save(role_face(icon, team), TEX / f"role_{role}_{suffix}.png")
            write_item(f"jeton_{role}_{suffix}", token_model(f"valcombe:item/scene/role_{role}_{suffix}", back))
            write_item(f"icone_{role}_{suffix}", flat_item(f"valcombe:item/scene/role_{role}_{suffix}"))
    write_item("trone", throne_model())
    write_item("lutrin", lectern_model())
    write_hats()
    write_objects()
    print(f"{len(teams)} rôles, modèles écrits dans {ASSETS}")


if __name__ == "__main__":
    main()
