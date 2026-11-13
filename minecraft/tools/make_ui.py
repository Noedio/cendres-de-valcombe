"""Génère la boîte à outils d'interface haute résolution (aucune dépendance externe).

Lancer depuis le dossier minecraft :
    python tools/make_ui.py            textures d'interface et polices déclarées (font/hd/*.json)
    python tools/make_ui.py --polices  télécharge d'abord les polices libres (google/fonts, commit figé,
                                       empreintes SHA-256 vérifiées), puis fait comme ci-dessus

Les textures sont dessinées ici, à 8 pixels par unité d'interface (une unité = un pixel de l'interface
à l'échelle 1) : cadres, boutons, champs, listes, onglets, infobulles, interrupteurs, anneaux de jeton.
Le jeu les réduit lui-même (chaîne de mipmaps, filtrage lisse) à l'échelle d'interface du joueur :
elles restent nettes de l'échelle 1 à l'échelle 8 et au-delà.

Les polices sont des fichiers TTF sous licence OFL (Cinzel pour les titres, Alegreya Sans pour le
texte). Minecraft dessine une police TTF à une seule taille en pixels : on en déclare donc une échelle
de tailles (font/hd/<face>_<taille>.json) et le jeu prend la plus proche de la taille voulue en pixels
réels. Le décalage vertical de chaque taille centre la hauteur des capitales dans la ligne, pour que
le texte tombe au milieu des boutons quelle que soit la police.

Les jetons, emblèmes et pictogrammes de rôle ne sont PAS produits ici (voir make_assets.py et
make_models.py) : la boîte à outils les dessine tels quels, à n'importe quelle taille.
"""
import hashlib
import json
import math
import struct
import sys
import urllib.request
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/client/resources/assets/valcombe"
OUT_TEX = ASSETS / "textures/gui/hd"
OUT_FONT = ASSETS / "font/hd"

# ---------------------------------------------------------------------------
# Polices
# ---------------------------------------------------------------------------
GOOGLE_FONTS_COMMIT = "3e5667764ccfea32ce0c3d58a3a4756de890adc1"
# (fichier dans le mod, chemin dans google/fonts, empreinte SHA-256)
FONT_FILES = [
    ("cinzel.ttf", "ofl/cinzel/Cinzel[wght].ttf",
     "f4d83d34d1f6c741193e4acf4b3dff9531e5a67b6aa65228d00a7db72a4e0f34"),
    ("licence_cinzel_ofl.txt", "ofl/cinzel/OFL.txt",
     "f2b3029aba64c378bf0963b62945eee15e564fe4330b934c8f2eb058282b5e83"),
    ("alegreya_sans.ttf", "ofl/alegreyasans/AlegreyaSans-Regular.ttf",
     "8fab634196007afca839f1e5a6fb300976daff55d8528b590ef032f01b14ea10"),
    ("alegreya_sans_medium.ttf", "ofl/alegreyasans/AlegreyaSans-Medium.ttf",
     "4b89fe7804fd1485ec2757795a53ffdb66e1206dd56f844c2d72b3c944815b43"),
    ("licence_alegreya_sans_ofl.txt", "ofl/alegreyasans/OFL.txt",
     "0677891e6a143f297350d260ad766ad33bfc18ed5fa4f213acf648d6b597ec1a"),
]

# Faces : nom -> (fichier TTF, plus petite et plus grande taille déclarées, en pixels réels).
# À garder identique à fr.valcombe.client.hd.FontLadder (un test JUnit le vérifie).
FACES = {
    "titre": ("cinzel.ttf", 12, 142),
    "texte": ("alegreya_sans.ttf", 8, 108),
    "fort": ("alegreya_sans_medium.ttf", 8, 108),
}
# Échelle des tailles (pixels réels) : serrée en petit, environ 15 % d'écart au-delà.
LADDER = [8, 9, 10, 11, 12, 13, 15, 17, 19, 22, 25, 28, 32, 36, 41, 47, 54, 62, 71, 82, 94, 108, 124, 142]
# Hauteur de ligne en multiple de la taille (FontLadder.LINE).
LINE = 1.3
# Minecraft place la ligne de base d'une police à 7 unités sous le haut du texte.
MC_BASELINE = 7


def jround(x):
    """Arrondi comme Math.round de Java (au demi supérieur), pour tomber sur les mêmes valeurs."""
    return int(math.floor(x + 0.5))


def line_height(size):
    return jround(size * LINE)


def ttf_metrics(path):
    """Unités par cadratin et hauteur des capitales, lues dans les tables head et OS/2."""
    data = path.read_bytes()
    num = struct.unpack(">H", data[4:6])[0]
    tables = {}
    for i in range(num):
        tag, _, off, length = struct.unpack(">4sIII", data[12 + 16 * i:28 + 16 * i])
        tables[tag.decode("latin-1")] = data[off:off + length]
    upm = struct.unpack(">H", tables["head"][18:20])[0]
    os2 = tables["OS/2"]
    version = struct.unpack(">H", os2[0:2])[0]
    if version >= 2:
        cap = struct.unpack(">h", os2[88:90])[0]
    else:  # vieille table : on prend 70 % du cadratin
        cap = int(upm * 0.7)
    return upm, cap


def baseline(size, cap_ratio):
    """Ligne de base (pixels sous le haut de la ligne) qui centre les capitales dans la ligne."""
    return jround((line_height(size) + cap_ratio * size) / 2)


def download_fonts():
    OUT_FONT.mkdir(parents=True, exist_ok=True)
    base = "https://raw.githubusercontent.com/google/fonts/" + GOOGLE_FONTS_COMMIT + "/"
    for name, src, sha in FONT_FILES:
        url = base + src.replace("[", "%5B").replace("]", "%5D")
        with urllib.request.urlopen(url, timeout=60) as r:
            data = r.read()
        got = hashlib.sha256(data).hexdigest()
        if got != sha:
            raise SystemExit("Empreinte inattendue pour " + src + " : " + got)
        (OUT_FONT / name).write_bytes(data)
        print("  téléchargé", name, len(data), "octets")


def check_fonts():
    for name, src, sha in FONT_FILES:
        path = OUT_FONT / name
        if not path.exists():
            raise SystemExit("Police absente : " + str(path) + " (lancer avec --polices)")
        if hashlib.sha256(path.read_bytes()).hexdigest() != sha:
            raise SystemExit("Empreinte inattendue pour " + name + " (lancer avec --polices)")


def write_font_definitions():
    for old in OUT_FONT.glob("*.json"):
        old.unlink()
    for face, (ttf, lo, hi) in FACES.items():
        upm, cap = ttf_metrics(OUT_FONT / ttf)
        ratio = cap / upm
        count = 0
        for size in LADDER:
            if size < lo or size > hi:
                continue
            shift = baseline(size, ratio) - MC_BASELINE
            definition = {"providers": [
                {"type": "ttf", "file": "valcombe:hd/" + ttf, "size": size, "oversample": 1.0,
                 "shift": [0.0, float(shift)]},
                # Un caractère absent de la police (flèches, symboles) vient de la police de Minecraft.
                {"type": "reference", "id": "minecraft:default"},
            ]}
            (OUT_FONT / (face + "_" + str(size) + ".json")).write_text(
                json.dumps(definition, indent=2) + "\n", encoding="utf-8", newline="\n")
            count += 1
        print("  police", face, ":", count, "tailles, capitales à", round(ratio, 3), "du cadratin")


# ---------------------------------------------------------------------------
# Dessin : toile en flottants, formes par distance signée (bords lissés au pixel près)
# ---------------------------------------------------------------------------
D = 8  # pixels de texture par unité d'interface

INK = (27, 18, 20)
WINE = (90, 30, 42)
WINE_LIGHT = (128, 42, 56)
WINE_DARK = (58, 18, 28)
BLOOD = (150, 34, 34)
BLOOD_DARK = (98, 22, 26)
BRONZE = (176, 141, 87)
BRONZE_LIGHT = (222, 190, 128)
BRONZE_DARK = (104, 78, 44)
GOLD = (217, 180, 90)
GOLD_LIGHT = (246, 222, 150)
PARCH = (232, 217, 181)
PARCH_LIGHT = (244, 234, 206)
PARCH_DARK = (196, 174, 130)
ASH = (122, 112, 108)
ASH_DARK = (70, 62, 62)


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


class Canvas:
    """Image RGBA en flottants (0..1), alpha non prémultiplié."""

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[0.0, 0.0, 0.0, 0.0] for _ in range(w * h)]

    def over(self, x, y, rgb, a):
        if a <= 0:
            return
        p = self.px[y * self.w + x]
        a = min(1.0, a)
        out = a + p[3] * (1 - a)
        for i in range(3):
            c = rgb[i] / 255.0
            p[i] = (c * a + p[i] * p[3] * (1 - a)) / out if out > 0 else 0.0
        p[3] = out

    def shape(self, sdf, color, alpha=1.0, box=None):
        """Remplit la forme (distance signée en pixels, négative dedans) ; color : (r,g,b) ou f(x, y)."""
        x0, y0, x1, y1 = box if box else (0, 0, self.w, self.h)
        x0, y0 = max(0, int(x0) - 1), max(0, int(y0) - 1)
        x1, y1 = min(self.w, int(math.ceil(x1)) + 1), min(self.h, int(math.ceil(y1)) + 1)
        for y in range(y0, y1):
            cy = y + 0.5
            for x in range(x0, x1):
                cx = x + 0.5
                d = sdf(cx, cy)
                cov = 0.5 - d
                if cov <= 0:
                    continue
                c = color(cx, cy) if callable(color) else color
                a = alpha(cx, cy) if callable(alpha) else alpha
                self.over(x, y, c, min(1.0, cov) * a)

    def glow(self, sdf, width, rgb, alpha, inside=True):
        """Ombre ou lueur qui s'estompe sur width pixels, à l'intérieur (inside) ou à l'extérieur de la forme."""
        for y in range(self.h):
            cy = y + 0.5
            for x in range(self.w):
                d = sdf(x + 0.5, cy)
                t = -d if inside else d
                if t < -0.5 or t > width:
                    continue
                f = 1 - max(0.0, t) / width
                self.over(x, y, rgb, alpha * f * f * min(1.0, t + 0.5))

    def bleed(self):
        """Donne aux pixels transparents la couleur de leurs voisins (pas de liseré sombre une fois filtré)."""
        w, h = self.w, self.h
        known = [p[3] > 0.004 for p in self.px]
        frontier = True
        while frontier:
            frontier = False
            updates = []
            for y in range(h):
                for x in range(w):
                    i = y * w + x
                    if known[i]:
                        continue
                    acc = [0.0, 0.0, 0.0]
                    n = 0
                    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        nx, ny = x + dx, y + dy
                        if 0 <= nx < w and 0 <= ny < h and known[ny * w + nx]:
                            q = self.px[ny * w + nx]
                            acc[0] += q[0]
                            acc[1] += q[1]
                            acc[2] += q[2]
                            n += 1
                    if n:
                        updates.append((i, [acc[0] / n, acc[1] / n, acc[2] / n]))
            for i, rgb in updates:
                p = self.px[i]
                p[0], p[1], p[2] = rgb
                known[i] = True
                frontier = True

    def save(self, name):
        self.bleed()
        rows = []
        for y in range(self.h):
            row = bytearray(b"\x00")
            for x in range(self.w):
                p = self.px[y * self.w + x]
                row += bytes(max(0, min(255, jround(v * 255))) for v in p)
            rows.append(bytes(row))

        def chunk(t, d):
            return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

        data = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b""))
        OUT_TEX.mkdir(parents=True, exist_ok=True)
        (OUT_TEX / (name + ".png")).write_bytes(data)
        print("  texture", name, self.w, "x", self.h)


def rrect(x0, y0, x1, y1, r):
    """Distance signée à un rectangle aux coins arrondis (rayon r)."""
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    hx, hy = (x1 - x0) / 2 - r, (y1 - y0) / 2 - r

    def f(x, y):
        qx, qy = abs(x - cx) - hx, abs(y - cy) - hy
        return math.hypot(max(qx, 0), max(qy, 0)) + min(max(qx, qy), 0) - r
    return f


def rrect_top(x0, y0, x1, y1, r):
    """Rectangle arrondi en haut seulement (onglet) : le bas est droit et déborde."""
    inner = rrect(x0, y0, x1, y1 + r * 2, r)

    def f(x, y):
        return max(inner(x, y), y - y1)
    return f


def circle(cx, cy, r):
    return lambda x, y: math.hypot(x - cx, y - cy) - r


def ring_sdf(cx, cy, r, w):
    return lambda x, y: abs(math.hypot(x - cx, y - cy) - r) - w / 2


def diamond(cx, cy, r):
    return lambda x, y: (abs(x - cx) + abs(y - cy) - r) / math.sqrt(2)


def minus(a, b):
    return lambda x, y: max(a(x, y), -b(x, y))


def vgrad(y0, y1, top, bottom):
    return lambda x, y: mix(top, bottom, (y - y0) / max(1, y1 - y0))


def hash2(ix, iy, seed):
    n = (ix * 374761393 + iy * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def periodic_noise(x, y, period, cells, seed):
    """Bruit de valeur lissé, périodique sur period pixels (raccords invisibles en mosaïque)."""
    s = cells / period
    fx, fy = x * s, y * s
    ix, iy = int(math.floor(fx)), int(math.floor(fy))
    tx, ty = smooth(fx - ix), smooth(fy - iy)

    def h(i, j):
        return hash2(i % cells, j % cells, seed)
    a = h(ix, iy) + (h(ix + 1, iy) - h(ix, iy)) * tx
    b = h(ix, iy + 1) + (h(ix + 1, iy + 1) - h(ix, iy + 1)) * tx
    return a + (b - a) * ty


def fbm(x, y, period, seed, octaves=4, cells=4):
    total, amp, norm = 0.0, 1.0, 0.0
    for o in range(octaves):
        total += amp * periodic_noise(x, y, period, cells * (2 ** o), seed + o * 17)
        norm += amp
        amp *= 0.5
    return total / norm


# ---------------------------------------------------------------------------
# Textures
# ---------------------------------------------------------------------------
def parchment():
    """Parchemin en mosaïque : 64 x 64 unités à 4 pixels par unité (le grain n'a pas besoin de plus)."""
    n = 256
    cv = Canvas(n, n)
    for y in range(n):
        for x in range(n):
            m = fbm(x, y, n, 11, octaves=5, cells=3)          # marbrures larges
            f = periodic_noise(x * 0.6, y * 6.0, n, 64, 23)    # fibres horizontales fines
            g = hash2(x, y, 5)                                 # grain
            t = (m - 0.5) * 1.6
            c = mix(PARCH, PARCH_DARK, 0.2 + t * 0.4) if t > 0 else mix(PARCH, PARCH_LIGHT, -t * 0.7)
            k = 1 + (f - 0.5) * 0.035 + (g - 0.5) * 0.03
            cv.over(x, y, tuple(v * k for v in c), 1.0)
    # quelques taches d'encre et d'humidité, très pâles
    for i in range(7):
        cx, cy = hash2(i, 1, 3) * n, hash2(i, 2, 3) * n
        r = 8 + hash2(i, 3, 3) * 26
        for ox in (-n, 0, n):
            for oy in (-n, 0, n):
                cv.shape(circle(cx + ox, cy + oy, r), PARCH_DARK,
                         alpha=lambda x, y, cx=cx + ox, cy=cy + oy, r=r: 0.10 * (1 - min(1, math.hypot(x - cx, y - cy) / r)),
                         box=(cx + ox - r, cy + oy - r, cx + ox + r, cy + oy + r))
    cv.save("parchemin")


def frame():
    """Cadre de panneau : 32 x 32 unités, bords de 10 unités ; le centre reste transparent."""
    u = D
    n = 32 * u
    cv = Canvas(n, n)
    outer = rrect(0, 0, n, n, 2.5 * u)
    # ombre intérieure portée sur le parchemin (de 5 à 11 unités du bord)
    inner_edge = rrect(5 * u, 5 * u, n - 5 * u, n - 5 * u, 1 * u)
    cv.glow(inner_edge, 5 * u, (60, 36, 22), 0.42, inside=True)
    # bois sombre lie-de-vin, en relief : clair en haut à gauche, sombre en bas à droite
    wood = minus(outer, rrect(4.25 * u, 4.25 * u, n - 4.25 * u, n - 4.25 * u, 1 * u))

    def wood_color(x, y):
        edge = min(x, y, n - x, n - y) / u            # 0 au bord, 4 vers l'intérieur
        lit = 0.5 + 0.5 * math.cos(min(edge, 4) / 4 * math.pi)  # bombé
        side = 1.0 if (x + y) < n else 0.0           # moitié haute-gauche plus éclairée
        base = mix(WINE_DARK, WINE_LIGHT, 0.18 + 0.32 * lit * (0.55 + 0.45 * side))
        return base
    cv.shape(wood, wood_color)
    cv.shape(minus(outer, rrect(0.5 * u, 0.5 * u, n - 0.5 * u, n - 0.5 * u, 2 * u)), INK)
    # filet de bronze
    fillet = minus(rrect(3.25 * u, 3.25 * u, n - 3.25 * u, n - 3.25 * u, 1.4 * u),
                   rrect(4.4 * u, 4.4 * u, n - 4.4 * u, n - 4.4 * u, 0.9 * u))
    cv.shape(fillet, lambda x, y: mix(BRONZE_LIGHT, BRONZE_DARK, 0.25 + 0.5 * ((x + y) / (2 * n))))
    # liseré d'encre puis fil d'or contre le parchemin
    cv.shape(minus(rrect(4.4 * u, 4.4 * u, n - 4.4 * u, n - 4.4 * u, 0.9 * u),
                   rrect(4.9 * u, 4.9 * u, n - 4.9 * u, n - 4.9 * u, 0.8 * u)), INK, alpha=0.85)
    cv.shape(minus(rrect(5.4 * u, 5.4 * u, n - 5.4 * u, n - 5.4 * u, 0.7 * u),
                   rrect(5.75 * u, 5.75 * u, n - 5.75 * u, n - 5.75 * u, 0.6 * u)), GOLD, alpha=0.55)
    # écoinçons : plaque de bronze en équerre, rivet doré, petit losange
    bar_h = rrect(1.2 * u, 1.2 * u, 8.2 * u, 3.8 * u, 1.1 * u)  # coin haut-gauche ; les autres en miroir
    bar_v = rrect(1.2 * u, 1.2 * u, 3.8 * u, 8.2 * u, 1.1 * u)
    for sx in (0, 1):
        for sy in (0, 1):
            def P(px, py, sx=sx, sy=sy):
                return (n - px if sx else px, n - py if sy else py)

            def plate(x, y, sx=sx, sy=sy):
                lx, ly = (n - x if sx else x), (n - y if sy else y)
                return min(bar_h(lx, ly), bar_v(lx, ly))
            cv.glow(plate, 0.9 * u, INK, 0.55, inside=False)
            cv.shape(plate, lambda x, y: mix(BRONZE_LIGHT, BRONZE_DARK, 0.15 + 0.7 * ((x + y) / (2 * n))))
            cx, cy = P(2.5 * u, 2.5 * u)
            cv.shape(circle(cx, cy, 1.05 * u), INK, alpha=0.6, box=(cx - 2 * u, cy - 2 * u, cx + 2 * u, cy + 2 * u))
            cv.shape(circle(cx, cy, 0.85 * u), lambda x, y, cx=cx, cy=cy: mix(GOLD_LIGHT, BRONZE_DARK, 0.5 + (x - cx + y - cy) / (2.4 * u)),
                     box=(cx - 2 * u, cy - 2 * u, cx + 2 * u, cy + 2 * u))
            for (qx, qy) in (P(6.6 * u, 2.5 * u), P(2.5 * u, 6.6 * u)):
                cv.shape(diamond(qx, qy, 0.75 * u), GOLD, box=(qx - u, qy - u, qx + u, qy + u))
    cv.save("cadre")


def button(name, top, bottom, rim_light, rim_dark, gloss):
    """Bouton à la cire : 32 x 20 unités, bords de 5 unités (coins arrondis de 3 unités)."""
    u = D
    w, h = 32 * u, 20 * u
    cv = Canvas(w, h)
    body = rrect(0, 0, w, h - 0.75 * u, 3 * u)
    lip = rrect(0, 0.75 * u, w, h, 3 * u)  # tranche du bouton, sous le corps
    cv.shape(lip, mix(INK, bottom, 0.25))
    cv.shape(body, INK)
    cv.shape(rrect(0.5 * u, 0.5 * u, w - 0.5 * u, h - 1.25 * u, 2.6 * u), vgrad(0, h, rim_light, rim_dark))
    face = rrect(1.5 * u, 1.5 * u, w - 1.5 * u, h - 2.25 * u, 1.8 * u)
    cv.shape(face, vgrad(1.5 * u, h - 2.25 * u, top, bottom))
    # reflet de cire en haut, ombre intérieure en bas
    cv.shape(rrect(2.2 * u, 1.9 * u, w - 2.2 * u, 5.2 * u, 1.4 * u), (255, 240, 220),
             alpha=lambda x, y: gloss * (1 - (y - 1.9 * u) / (3.3 * u)))
    cv.glow(face, 1.2 * u, INK, 0.25, inside=True)
    cv.save(name)


def text_field(name, rim, rim_alpha, glow):
    """Champ de saisie : 32 x 16 unités, bords de 4 unités ; encre presque opaque, cadre de bronze."""
    u = D
    w, h = 32 * u, 16 * u
    cv = Canvas(w, h)
    outer = rrect(0, 0, w, h, 2.2 * u)
    if glow:
        cv.glow(outer, 1.0 * u, GOLD, 0.35, inside=True)
    cv.shape(outer, rim, alpha=rim_alpha)
    inner = rrect(0.85 * u, 0.85 * u, w - 0.85 * u, h - 0.85 * u, 1.5 * u)
    cv.shape(inner, vgrad(0, h, (22, 14, 16), (40, 27, 28)), alpha=0.94)
    cv.glow(inner, 1.6 * u, (0, 0, 0), 0.45, inside=True)
    cv.save(name)


def list_well():
    """Fond de liste : 32 x 32 unités, bords de 4 unités ; parchemin creusé, filet de bronze."""
    u = D
    n = 32 * u
    cv = Canvas(n, n)
    outer = rrect(0, 0, n, n, 2 * u)
    cv.shape(outer, BRONZE_DARK, alpha=0.9)
    inner = rrect(0.6 * u, 0.6 * u, n - 0.6 * u, n - 0.6 * u, 1.5 * u)
    cv.shape(inner, (214, 195, 154), alpha=0.92)
    cv.glow(inner, 2.2 * u, (70, 45, 25), 0.38, inside=True)
    cv.save("liste")


def scrollbar():
    """Ascenseur de liste : rail et curseur, 8 x 16 unités, bords de 4 unités."""
    u = D
    w, h = 8 * u, 16 * u
    rail = Canvas(w, h)
    rail.shape(rrect(1.5 * u, 0, w - 1.5 * u, h, 2.5 * u), INK, alpha=0.28)
    rail.save("ascenseur_rail")
    for name, light, dark in (("ascenseur", BRONZE_LIGHT, BRONZE_DARK), ("ascenseur_survol", GOLD_LIGHT, BRONZE)):
        cv = Canvas(w, h)
        body = rrect(1 * u, 0, w - 1 * u, h, 3 * u)
        cv.shape(body, INK, alpha=0.8)
        cv.shape(rrect(1.5 * u, 0.5 * u, w - 1.5 * u, h - 0.5 * u, 2.5 * u),
                 lambda x, y, l=light, d=dark: mix(l, d, 0.15 + 0.85 * (x / w)))
        cv.save(name)


def tabs():
    """Onglets : 32 x 16 unités, bords de 5 unités (haut arrondi, bas ouvert sur le contenu)."""
    u = D
    w, h = 32 * u, 16 * u
    styles = {
        "onglet": ((96, 34, 46), (64, 20, 30), BRONZE_DARK, 0.12),
        "onglet_survol": ((136, 44, 54), (92, 26, 34), BRONZE, 0.18),
        "onglet_actif": (PARCH_LIGHT, (226, 210, 172), BRONZE, 0.35),
    }
    for name, (top, bottom, rim, gloss) in styles.items():
        cv = Canvas(w, h)
        shape = rrect_top(0, 0, w, h, 3 * u)
        cv.shape(shape, INK, alpha=0.9)
        cv.shape(rrect_top(0.6 * u, 0.6 * u, w - 0.6 * u, h, 2.5 * u), rim)
        cv.shape(rrect_top(1.4 * u, 1.4 * u, w - 1.4 * u, h, 1.8 * u), vgrad(1.4 * u, h, top, bottom))
        cv.shape(rrect_top(2 * u, 1.8 * u, w - 2 * u, 4.5 * u, 1.3 * u), (255, 245, 225),
                 alpha=lambda x, y, g=gloss: g * (1 - (y - 1.8 * u) / (2.7 * u)))
        cv.save(name)


def tooltip():
    """Infobulle : 16 x 16 unités, bords de 5 unités ; encre, fil d'or, filet de bronze."""
    u = D
    n = 16 * u
    cv = Canvas(n, n)
    outer = rrect(0, 0, n, n, 2.5 * u)
    cv.shape(outer, (18, 11, 13), alpha=0.97)
    cv.shape(minus(rrect(0.5 * u, 0.5 * u, n - 0.5 * u, n - 0.5 * u, 2 * u),
                   rrect(1.25 * u, 1.25 * u, n - 1.25 * u, n - 1.25 * u, 1.5 * u)), GOLD)
    cv.shape(minus(rrect(2 * u, 2 * u, n - 2 * u, n - 2 * u, 1.1 * u),
                   rrect(2.4 * u, 2.4 * u, n - 2.4 * u, n - 2.4 * u, 0.9 * u)), BRONZE, alpha=0.45)
    cv.save("infobulle")


def banner():
    """Bandeau de titre : 64 x 16 unités, bords de 12 unités à gauche et à droite, extrémités en queue d'aronde."""
    u = D
    w, h = 64 * u, 16 * u
    cv = Canvas(w, h)
    notch = 3.2 * u

    def ribbon(x, y):
        # bande pleine, entaillée en V à chaque bout
        d = max(abs(y - h / 2) - (h / 2 - 1.0 * u), abs(x - w / 2) - (w / 2 - 0.2 * u))
        cut_l = (notch - x) - abs(y - h / 2) * (notch / (h / 2))
        cut_r = (x - (w - notch)) - abs(y - h / 2) * (notch / (h / 2))
        return max(d, cut_l * 0.7, cut_r * 0.7)
    cv.glow(ribbon, 1.2 * u, INK, 0.45, inside=False)
    cv.shape(ribbon, INK)

    def inner(x, y):
        return ribbon(x, y) + 0.7 * u
    cv.shape(inner, lambda x, y: mix(BRONZE_LIGHT, BRONZE_DARK, (y - u) / (h - 2 * u)))

    def face(x, y):
        return ribbon(x, y) + 1.6 * u
    cv.shape(face, vgrad(2 * u, h - 2 * u, WINE_LIGHT, WINE_DARK))
    cv.shape(lambda x, y: max(face(x, y), y - 5.2 * u), (255, 235, 215),
             alpha=lambda x, y: 0.16 * (1 - (y - 2.6 * u) / (2.6 * u)))
    # fils d'or le long de la bande
    for yy in (3.0 * u, h - 3.0 * u):
        cv.shape(lambda x, y, yy=yy: max(abs(y - yy) - 0.18 * u, face(x, y) + 1.4 * u), GOLD, alpha=0.75)
    cv.save("bandeau")


def switch():
    """Interrupteur : rail 20 x 10 unités (bords de 5) et pastille 10 x 10 unités."""
    u = D
    w, h = 20 * u, 10 * u
    for name, top, bottom, rim in (("interrupteur_non", (40, 28, 30), (64, 50, 50), BRONZE_DARK),
                                   ("interrupteur_oui", GOLD_LIGHT, (184, 140, 62), INK)):
        cv = Canvas(w, h)
        outer = rrect(0, 0, w, h, h / 2)
        cv.shape(outer, rim)
        inner = rrect(0.8 * u, 0.8 * u, w - 0.8 * u, h - 0.8 * u, h / 2 - 0.8 * u)
        cv.shape(inner, vgrad(0, h, top, bottom))
        cv.glow(inner, 1.4 * u, INK, 0.45, inside=True)
        cv.save(name)
    n = 10 * u
    cv = Canvas(n, n)
    c = n / 2
    cv.shape(circle(c, c + 0.4 * u, 4.6 * u), INK, alpha=0.55)
    cv.shape(circle(c, c, 4.3 * u), lambda x, y: mix(PARCH_LIGHT, BRONZE, 0.1 + 0.9 * smooth((x + y - 1.1 * u) / (2 * n - 2.2 * u))))
    cv.shape(ring_sdf(c, c, 3.95 * u, 0.6 * u), BRONZE_DARK, alpha=0.7)
    cv.shape(circle(c - 1.3 * u, c - 1.4 * u, 1.3 * u), (255, 250, 236), alpha=0.5)
    cv.save("pastille")


def token_rings():
    """Anneaux de jeton (sélection et survol) et ombre portée, 32 x 32 unités."""
    u = D
    n = 32 * u
    c = n / 2
    sel = Canvas(n, n)
    sel.glow(circle(c, c, 15.0 * u), 1.0 * u, GOLD_LIGHT, 0.55, inside=False)
    sel.shape(ring_sdf(c, c, 15.0 * u, 1.6 * u), lambda x, y: mix(GOLD_LIGHT, BRONZE_DARK, 0.2 + 0.6 * ((x + y) / (2 * n))))
    sel.shape(ring_sdf(c, c, 15.0 * u, 0.3 * u), (255, 246, 210), alpha=0.6)
    sel.save("anneau_choisi")
    hov = Canvas(n, n)
    hov.shape(ring_sdf(c, c, 15.2 * u, 1.0 * u), PARCH_LIGHT, alpha=0.85)
    hov.glow(circle(c, c, 15.7 * u), 0.8 * u, PARCH_LIGHT, 0.35, inside=False)
    hov.save("anneau_survol")
    shadow = Canvas(n, n)
    shadow.glow(circle(c, c, 11.5 * u), 4.5 * u, (0, 0, 0), 0.6, inside=False)
    shadow.shape(circle(c, c, 11.5 * u), (0, 0, 0), alpha=0.6)
    shadow.save("ombre_ronde")


def ornaments():
    """Losange d'ornement (séparateurs, puces) : 8 x 8 unités."""
    u = D
    n = 8 * u
    c = n / 2
    cv = Canvas(n, n)
    cv.shape(diamond(c, c, 3.6 * u), INK, alpha=0.7)
    cv.shape(diamond(c, c, 3.0 * u), lambda x, y: mix(GOLD_LIGHT, BRONZE_DARK, (x + y) / (2 * n)))
    cv.shape(diamond(c, c, 1.1 * u), WINE)
    cv.save("losange")


def main():
    if "--polices" in sys.argv:
        print("Polices (google/fonts @ " + GOOGLE_FONTS_COMMIT[:10] + ")")
        download_fonts()
    check_fonts()
    print("Polices déclarées")
    write_font_definitions()
    print("Textures d'interface")
    parchment()
    frame()
    button("bouton", WINE_LIGHT, WINE_DARK, BRONZE_LIGHT, BRONZE_DARK, 0.20)
    button("bouton_survol", (176, 48, 46), BLOOD_DARK, GOLD_LIGHT, BRONZE, 0.24)
    button("bouton_inactif", (98, 84, 84), (64, 54, 56), ASH, ASH_DARK, 0.08)
    text_field("champ", BRONZE_DARK, 1.0, False)
    text_field("champ_actif", GOLD, 1.0, True)
    list_well()
    scrollbar()
    tabs()
    tooltip()
    banner()
    switch()
    token_rings()
    ornaments()


if __name__ == "__main__":
    main()
