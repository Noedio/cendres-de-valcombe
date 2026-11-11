"""Génère les textures originales du mod (aucune dépendance externe).

Lancer depuis le dossier minecraft :  python tools/make_assets.py
Toutes les images sont dessinées ici : palette gothique, parchemin, cadres,
boutons à la cire, jetons de rôle et pictogrammes des 22 rôles.
"""
import math
import random
import struct
import zlib
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "src/client/resources/assets/valcombe/textures/gui"

# Palette
INK = (27, 18, 20)
WINE = (90, 30, 42)
BLOOD = (142, 27, 27)
BRONZE = (176, 141, 87)
BRONZE_DARK = (110, 84, 50)
PARCH = (232, 217, 181)
PARCH_DARK = (190, 168, 125)
ASH = (107, 100, 102)
GOOD = (63, 111, 168)
EVIL = (168, 50, 50)
GOLD = (217, 180, 90)
WHITE = (245, 240, 230)


class Img:
    def __init__(self, w, h, fill=(0, 0, 0, 0)):
        self.w, self.h = w, h
        self.px = [[list(fill) for _ in range(w)] for _ in range(h)]

    def put(self, x, y, c, a=1.0):
        x, y = int(x), int(y)
        if 0 <= x < self.w and 0 <= y < self.h:
            dst = self.px[y][x]
            ca = (c[3] / 255 if len(c) > 3 else 1.0) * a
            for i in range(3):
                dst[i] = int(dst[i] * (1 - ca) + c[i] * ca)
            dst[3] = int(min(255, dst[3] + (255 - dst[3]) * ca))

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1):
            for x in range(x0, x1):
                self.put(x, y, c)

    def disc(self, cx, cy, r, c, soft=1.0):
        for y in range(int(cy - r - 2), int(cy + r + 3)):
            for x in range(int(cx - r - 2), int(cx + r + 3)):
                d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                if d <= r - soft / 2:
                    self.put(x, y, c)
                elif d <= r + soft / 2:
                    self.put(x, y, c, (r + soft / 2 - d) / soft)

    def cut(self, cx, cy, r):
        """Efface un disque (rend les pixels transparents)."""
        for y in range(self.h):
            for x in range(self.w):
                if math.hypot(x + 0.5 - cx, y + 0.5 - cy) < r:
                    self.px[y][x] = [0, 0, 0, 0]

    def ring(self, cx, cy, r, width, c):
        for y in range(int(cy - r - 2), int(cy + r + 3)):
            for x in range(int(cx - r - 2), int(cx + r + 3)):
                d = abs(math.hypot(x + 0.5 - cx, y + 0.5 - cy) - r)
                if d <= width / 2:
                    self.put(x, y, c, min(1.0, width / 2 - d + 0.5))

    def line(self, x0, y0, x1, y1, c, w=1.5):
        n = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
        for i in range(n + 1):
            t = i / n
            self.disc(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, w / 2, c, soft=0.8)

    def poly(self, pts, c):
        ys = [p[1] for p in pts]
        for y in range(int(min(ys)), int(max(ys)) + 1):
            xs = []
            for i in range(len(pts)):
                (xa, ya), (xb, yb) = pts[i], pts[(i + 1) % len(pts)]
                if (ya <= y + 0.5 < yb) or (yb <= y + 0.5 < ya):
                    xs.append(xa + (y + 0.5 - ya) * (xb - xa) / (yb - ya))
            xs.sort()
            for k in range(0, len(xs) - 1, 2):
                for x in range(int(round(xs[k])), int(round(xs[k + 1]))):
                    self.put(x, y, c)

    def save(self, name):
        raw = b"".join(b"\x00" + bytes(v for px in row for v in px) for row in self.px)

        def chunk(t, d):
            return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

        data = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
        path = OUT / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


def noise_fill(img, base, spread, seed, x0=0, y0=0, x1=None, y1=None):
    rnd = random.Random(seed)
    x1 = img.w if x1 is None else x1
    y1 = img.h if y1 is None else y1
    for y in range(y0, y1):
        for x in range(x0, x1):
            n = rnd.randint(-spread, spread)
            img.px[y][x] = [max(0, min(255, base[i] + n)) for i in range(3)] + [255]


# ---------------------------------------------------------------------------
# Fonds et cadres
# ---------------------------------------------------------------------------
def parchment():
    img = Img(256, 256)
    noise_fill(img, PARCH, 10, 1)
    rnd = random.Random(2)
    for _ in range(40):  # taches et fibres
        x, y, r = rnd.randint(0, 255), rnd.randint(0, 255), rnd.randint(4, 18)
        img.disc(x, y, r, PARCH_DARK + (40,))
    for y in range(256):  # vignettage
        for x in range(256):
            d = math.hypot(x - 128, y - 128) / 181
            img.put(x, y, (120, 90, 50), max(0.0, d - 0.55) * 0.9)
    img.save("parchment.png")


def panel():
    """Cadre 9 tranches 48x48 : bois sombre, liseré bronze, coins rivetés, intérieur transparent."""
    img = Img(48, 48)
    noise_fill(img, WINE, 8, 3)
    img.rect(5, 5, 43, 43, (0, 0, 0, 0))
    for y in range(5, 43):
        for x in range(5, 43):
            img.px[y][x] = [0, 0, 0, 0]
    for i in range(48):
        for t in (0, 47):
            img.put(i, t, INK)
            img.put(t, i, INK)
        for t in (3, 44):
            img.put(i, t, BRONZE)
            img.put(t, i, BRONZE)
    for cx, cy in ((4, 4), (43, 4), (4, 43), (43, 43)):
        img.disc(cx + 0.5, cy + 0.5, 3, BRONZE)
        img.disc(cx, cy, 1.2, GOLD)
    img.save("panel.png")


def button(name, face, rim):
    """Bouton 9 tranches 64x20."""
    img = Img(64, 20)
    noise_fill(img, face, 6, hash(name) % 1000)
    for x in range(64):
        img.put(x, 0, rim)
        img.put(x, 19, INK)
        img.put(x, 1, WHITE + (40,))
    for y in range(20):
        img.put(0, y, rim)
        img.put(63, y, INK)
    img.save(name)


def seat_disc(name, rim):
    """Jeton de siège 32x32 : disque parchemin, anneau de camp."""
    img = Img(32, 32)
    img.disc(16, 16, 15, INK)
    img.disc(16, 16, 14, rim)
    img.disc(16, 16, 12, PARCH)
    img.ring(16, 16, 10.5, 0.8, PARCH_DARK)
    img.save(name)


def overlays():
    img = Img(32, 32)  # linceul (mort)
    img.disc(16, 16, 15, (20, 20, 24, 170))
    img.line(9, 9, 23, 23, (200, 200, 200, 200), 2)
    img.line(23, 9, 9, 23, (200, 200, 200, 200), 2)
    img.save("token_dead.png")
    img = Img(32, 32)  # sélection
    img.ring(16, 16, 15, 2.5, GOLD + (255,))
    img.save("token_selected.png")
    img = Img(32, 32)  # survol
    img.ring(16, 16, 15, 2.0, WHITE + (200,))
    img.save("token_hover.png")


def markers():
    """Petits marqueurs 12x12 posés sur les jetons du grimoire."""
    def save(name, draw):
        img = Img(12, 12)
        img.disc(6, 6, 5.5, INK)
        draw(img)
        img.save("marker_" + name + ".png")
    save("poison", lambda i: (i.disc(6, 7, 3, (90, 170, 70)), i.rect(5, 2, 7, 5, (90, 170, 70))))
    save("protect", lambda i: i.poly([(3, 3), (9, 3), (9, 7), (6, 10), (3, 7)], (200, 200, 220)))
    save("drunk", lambda i: (i.disc(6, 6, 3.5, GOLD), i.disc(7.5, 5, 3, INK)))
    save("herring", lambda i: i.poly([(2, 6), (6, 3), (9, 6), (11, 4), (11, 8), (9, 6), (6, 9)], EVIL))
    save("master", lambda i: (i.disc(6, 4, 2, GOLD), i.rect(5, 5, 7, 10, GOLD)))
    save("ghost", lambda i: (i.disc(6, 5, 3.5, WHITE), i.rect(3, 5, 10, 9, WHITE)))


def emblems():
    """Lune, soleil, sablier (HUD), 16x16."""
    img = Img(16, 16)
    img.disc(8, 8, 6.5, (210, 215, 235))
    img.disc(10.5, 6.5, 5.5, (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            if math.hypot(x + 0.5 - 10.5, y + 0.5 - 6.5) < 5.5:
                img.px[y][x] = [0, 0, 0, 0]
    img.save("moon.png")
    img = Img(16, 16)
    for k in range(8):
        a = k * math.pi / 4
        img.line(8 + 4 * math.cos(a), 8 + 4 * math.sin(a), 8 + 7 * math.cos(a), 8 + 7 * math.sin(a), GOLD, 1.3)
    img.disc(8, 8, 4, GOLD)
    img.save("sun.png")
    img = Img(16, 16)
    img.poly([(3, 2), (13, 2), (8, 8)], BRONZE)
    img.poly([(8, 8), (13, 14), (3, 14)], BRONZE)
    img.poly([(5, 3), (11, 3), (8, 6)], PARCH)
    img.poly([(8, 10), (11, 13), (5, 13)], PARCH)
    img.save("hourglass.png")


# ---------------------------------------------------------------------------
# Pictogrammes des rôles (32x32, encre sur parchemin, centrés)
# ---------------------------------------------------------------------------
def icon(name, draw, color=INK):
    img = Img(32, 32)
    draw(img, color)
    img.save("roles/" + name + ".png")


ICONS = {
    "commere": lambda i, c: (i.disc(16, 13, 9, c), i.disc(16, 13, 7, PARCH), i.poly([(11, 19), (16, 20), (9, 26)], c),
                             [i.disc(12 + 4 * k, 13, 1.3, c) for k in range(3)]),
    "greffier": lambda i, c: (i.poly([(4, 9), (15, 11), (15, 25), (4, 23)], c), i.poly([(17, 11), (28, 9), (28, 23), (17, 25)], c),
                              [i.line(6, 13 + 3 * k, 13, 14 + 3 * k, PARCH, 1) for k in range(3)],
                              [i.line(19, 14 + 3 * k, 26, 13 + 3 * k, PARCH, 1) for k in range(3)]),
    "inquisiteur": lambda i, c: (i.poly([(3, 16), (10, 10), (22, 10), (29, 16), (22, 22), (10, 22)], c),
                                 i.disc(16, 16, 5.5, PARCH), i.disc(16, 16, 3, c)),
    "aubergiste": lambda i, c: (i.rect(9, 9, 21, 26, c), i.rect(11, 11, 19, 24, PARCH), i.rect(11, 15, 19, 24, BRONZE),
                                i.ring(22, 17, 4, 2, c), i.rect(8, 7, 22, 10, WHITE)),
    "veilleuse": lambda i, c: (i.rect(13, 14, 19, 27, c), i.rect(10, 26, 22, 28, c),
                               i.poly([(16, 4), (19, 10), (16, 13), (13, 10)], GOLD), i.disc(16, 10, 1.5, WHITE)),
    "astrologue": lambda i, c: (i.poly([(16, 3), (18.5, 12), (28, 12), (20.5, 18), (23, 27), (16, 21.5), (9, 27), (11.5, 18), (4, 12), (13.5, 12)], c),
                                i.disc(16, 15, 2, GOLD)),
    "embaumeur": lambda i, c: (i.poly([(11, 3), (21, 3), (25, 10), (21, 29), (11, 29), (7, 10)], c),
                               i.line(16, 9, 16, 19, PARCH, 1.6), i.line(12.5, 12, 19.5, 12, PARCH, 1.6)),
    "chapelain": lambda i, c: (i.rect(14, 4, 18, 28, c), i.rect(8, 10, 24, 14, c), i.ring(16, 12, 7, 1, GOLD)),
    "fauconnier": lambda i, c: (i.disc(15, 16, 6, c), i.disc(21, 12, 3.5, c), i.poly([(24, 11), (29, 12), (24, 14)], GOLD),
                                i.poly([(9, 14), (3, 6), (14, 11)], c), i.poly([(10, 19), (4, 27), (15, 21)], c), i.disc(22, 11, 0.9, PARCH)),
    "pelerine": lambda i, c: (i.line(16, 28, 16, 14, c, 1.8), i.poly([(16, 4), (19, 12), (16, 16), (13, 12)], WINE),
                              i.poly([(16, 15), (8, 9), (10, 16)], WINE), i.poly([(16, 15), (24, 9), (22, 16)], WINE),
                              i.ring(16, 11, 7.5, 0.8, c)),
    "arbaletrier": lambda i, c: (i.line(5, 9, 27, 9, c, 2.2), i.line(16, 6, 16, 28, c, 2.2),
                                 i.line(5, 9, 16, 19, BRONZE, 1), i.line(27, 9, 16, 19, BRONZE, 1),
                                 i.poly([(16, 2), (19, 7), (13, 7)], c)),
    "templier": lambda i, c: (i.poly([(6, 5), (26, 5), (26, 15), (16, 28), (6, 15)], c),
                              i.rect(15, 8, 17, 22, BLOOD), i.rect(10, 12, 22, 14, BLOOD)),
    "chatelain": lambda i, c: (i.poly([(5, 24), (5, 10), (11, 16), (16, 7), (21, 16), (27, 10), (27, 24)], GOLD),
                               i.rect(5, 23, 27, 27, c), [i.disc(x, y, 1.5, BLOOD) for x, y in ((5, 9), (16, 6), (27, 9))]),
    "ecuyer": lambda i, c: (i.ring(10, 16, 5, 2.5, c), i.rect(14, 15, 28, 18, c), i.rect(22, 18, 24, 22, c), i.rect(26, 18, 28, 21, c)),
    "somnambule": lambda i, c: (i.disc(13, 17, 9, c), i.cut(18, 13, 8),
                                i.line(20, 5, 26, 5, c, 1.3), i.line(26, 5, 20, 11, c, 1.3), i.line(20, 11, 26, 11, c, 1.3)),
    "excommunie": lambda i, c: (i.poly([(16, 4), (26, 12), (25, 28), (7, 28), (6, 12)], c), i.disc(16, 16, 5, INK),
                                i.disc(14, 16, 1, EVIL), i.disc(18, 16, 1, EVIL)),
    "martyr": lambda i, c: (i.ring(16, 7, 7, 1.6, GOLD), i.disc(16, 15, 4.5, c), i.poly([(9, 28), (11, 20), (21, 20), (23, 28)], c)),
    "alchimiste": lambda i, c: (i.rect(13, 4, 19, 12, c), i.poly([(13, 11), (19, 11), (26, 26), (6, 26)], c),
                                i.poly([(10, 19), (22, 19), (25, 25), (7, 25)], (90, 170, 70)), i.disc(14, 22, 1.2, WHITE)),
    "mouchard": lambda i, c: (i.poly([(3, 12), (13, 10), (16, 13), (19, 10), (29, 12), (26, 21), (18, 20), (16, 17), (14, 20), (6, 21)], c),
                              i.disc(10, 15, 2, PARCH), i.disc(22, 15, 2, PARCH)),
    "heritiere": lambda i, c: (i.ring(16, 19, 7, 2.6, GOLD), i.poly([(16, 5), (21, 11), (16, 15), (11, 11)], BLOOD)),
    "margrave": lambda i, c: (i.line(16, 5, 16, 27, c, 1.8), i.line(6, 9, 26, 9, c, 1.8), i.rect(10, 26, 22, 28, c),
                              i.poly([(6, 9), (2, 17), (10, 17)], BRONZE), i.poly([(26, 9), (22, 17), (30, 17)], BRONZE)),
    "sonneur": lambda i, c: (i.poly([(16, 4), (21, 8), (23, 20), (26, 24), (6, 24), (9, 20), (11, 8)], c),
                             i.rect(15, 2, 17, 5, c), i.disc(16, 26.5, 2.5, c), i.line(5, 12, 2, 10, c, 1.2),
                             i.line(27, 12, 30, 10, c, 1.2), i.line(4, 17, 1, 17, c, 1.2), i.line(28, 17, 31, 17, c, 1.2)),
    "cartographe": lambda i, c: (i.ring(16, 16, 11, 1.6, c), i.poly([(16, 4), (18.5, 16), (16, 28), (13.5, 16)], c),
                                 i.poly([(4, 16), (16, 13.5), (28, 16), (16, 18.5)], c), i.disc(16, 16, 2.2, PARCH),
                                 i.poly([(16, 4), (18.5, 16), (13.5, 16)], BLOOD)),
    "faussaire": lambda i, c: (i.poly([(25, 3), (29, 5), (13, 22), (10, 23), (11, 20)], c), i.line(10, 23, 6, 28, c, 1.4),
                               i.disc(9, 25, 5, BLOOD), i.disc(9, 25, 3, (180, 50, 50)), i.line(20, 10, 24, 6, PARCH, 1)),
    "voyageur": lambda i, c: (i.line(6, 28, 24, 6, BRONZE_DARK, 2.2), i.disc(24, 10, 6.5, WINE), i.disc(22, 8, 1.8, (170, 70, 90)),
                              i.line(21, 4, 27, 4, c, 1.4), i.poly([(3, 30), (9, 30), (6, 26)], c)),
    "goule": lambda i, c: (i.disc(16, 14, 9, WHITE), i.rect(11, 20, 21, 27, WHITE), i.disc(12.5, 14, 2.5, INK), i.disc(19.5, 14, 2.5, INK),
                           i.poly([(16, 17), (17.5, 20), (14.5, 20)], INK), [i.rect(12 + 3 * k, 24, 13 + 3 * k, 27, INK) for k in range(3)],
                           i.poly([(8, 9), (4, 2), (11, 6)], BLOOD), i.poly([(24, 9), (28, 2), (21, 6)], BLOOD)),
}


# Bibliothèque d'emblèmes pour les rôles créés dans l'Atelier (32x32, mêmes couleurs)
EMBLEMS = {
    "e_etoile": lambda i, c: (i.poly([(16, 3), (19, 12), (29, 12), (21, 18), (24, 28), (16, 22), (8, 28), (11, 18), (3, 12), (13, 12)], GOLD),
                              i.disc(16, 15, 2, c)),
    "e_lune": lambda i, c: (i.disc(15, 16, 11, c), i.cut(20, 12, 9.5), i.disc(25, 23, 1.2, GOLD), i.disc(23, 7, 0.9, GOLD)),
    "e_soleil": lambda i, c: ([i.line(16 + 9 * __import__("math").cos(k * 0.785), 16 + 9 * __import__("math").sin(k * 0.785),
                                      16 + 14 * __import__("math").cos(k * 0.785), 16 + 14 * __import__("math").sin(k * 0.785), GOLD, 2)
                               for k in range(8)], i.disc(16, 16, 7, GOLD), i.disc(16, 16, 4.5, c)),
    "e_cle": lambda i, c: (i.ring(10, 10, 5.5, 2.4, c), i.line(14, 14, 27, 27, c, 2.6), i.line(22, 22, 25, 19, c, 2.2),
                           i.line(25, 25, 28, 22, c, 2.2)),
    "e_plume": lambda i, c: (i.poly([(26, 3), (29, 6), (14, 24), (9, 25), (10, 20)], c), i.line(10, 24, 4, 30, c, 1.4),
                             i.line(25, 6, 12, 22, PARCH, 0.9)),
    "e_oeil": lambda i, c: (i.poly([(2, 16), (9, 9), (23, 9), (30, 16), (23, 23), (9, 23)], c), i.disc(16, 16, 5.5, PARCH),
                            i.disc(16, 16, 3, GOOD), i.disc(16, 16, 1.3, c)),
    "e_coeur": lambda i, c: (i.disc(11, 12, 6, BLOOD), i.disc(21, 12, 6, BLOOD), i.poly([(5.5, 14), (26.5, 14), (16, 28)], BLOOD),
                             i.disc(9, 10, 1.5, (220, 120, 120))),
    "e_epee": lambda i, c: (i.poly([(16, 2), (18.5, 6), (18.5, 21), (13.5, 21), (13.5, 6)], (200, 200, 210)),
                            i.rect(8, 21, 24, 24, c), i.rect(14, 24, 18, 29, c), i.disc(16, 30, 2, GOLD)),
    "e_bouclier": lambda i, c: (i.poly([(5, 5), (27, 5), (27, 15), (16, 29), (5, 15)], c), i.poly([(8, 8), (24, 8), (24, 15), (16, 25), (8, 15)], GOOD),
                                i.rect(15, 8, 17, 24, GOLD)),
    "e_couronne": lambda i, c: (i.poly([(4, 24), (4, 9), (10, 16), (16, 6), (22, 16), (28, 9), (28, 24)], GOLD), i.rect(4, 23, 28, 27, c),
                                [i.disc(x, 19, 1.4, BLOOD) for x in (10, 16, 22)]),
    "e_livre": lambda i, c: (i.rect(6, 5, 26, 28, c), i.rect(8, 7, 24, 26, WINE), i.rect(24, 7, 27, 26, PARCH),
                             i.line(11, 12, 21, 12, GOLD, 1.4), i.line(11, 16, 21, 16, GOLD, 1)),
    "e_fiole": lambda i, c: (i.rect(13, 3, 19, 6, BRONZE), i.rect(14, 6, 18, 12, c), i.disc(16, 20, 8, c), i.disc(16, 20, 6.5, PARCH),
                             i.disc(16, 22, 5, (120, 70, 170)), i.disc(14, 19, 1.2, WHITE)),
    "e_rose": lambda i, c: (i.line(16, 30, 16, 16, (60, 110, 60), 1.8), i.poly([(16, 24), (22, 20), (19, 26)], (60, 110, 60)),
                            i.disc(16, 11, 7, BLOOD), i.ring(16, 11, 4, 1.2, (100, 15, 20)), i.disc(16, 11, 1.5, (100, 15, 20))),
    "e_cloche": lambda i, c: (i.poly([(16, 4), (22, 9), (24, 21), (28, 25), (4, 25), (8, 21), (10, 9)], BRONZE), i.rect(15, 2, 17, 5, c),
                              i.disc(16, 27.5, 2.5, c), i.line(10, 21, 22, 21, BRONZE_DARK, 1)),
    "e_lanterne": lambda i, c: (i.ring(16, 5, 3, 1.4, c), i.rect(9, 8, 23, 10, c), i.rect(10, 10, 22, 25, c), i.rect(12, 12, 20, 23, GOLD),
                                i.disc(16, 17, 2.5, WHITE), i.rect(9, 25, 23, 27, c)),
    "e_masque": lambda i, c: (i.poly([(3, 10), (13, 9), (16, 12), (19, 9), (29, 10), (27, 20), (19, 21), (16, 18), (13, 21), (5, 20)], WINE),
                              i.disc(10, 14, 2.3, PARCH), i.disc(22, 14, 2.3, PARCH), i.line(29, 10, 31, 4, GOLD, 1.2)),
    "e_ancre": lambda i, c: (i.ring(16, 16, 11, 2.2, c), i.cut(16, 11, 11), i.ring(16, 6, 3, 1.6, c),
                             i.line(16, 9, 16, 27, c, 2.2), i.line(10, 13, 22, 13, c, 2),
                             i.poly([(3, 19), (8, 21), (4, 24)], c), i.poly([(29, 19), (24, 21), (28, 24)], c)),
    "e_corbeau": lambda i, c: (i.disc(14, 18, 8, c), i.disc(20, 11, 5, c), i.poly([(24, 10), (31, 13), (24, 14)], BRONZE_DARK),
                               i.disc(21, 10, 1.2, GOLD), i.poly([(6, 22), (2, 29), (12, 25)], c)),
    "e_serpent": lambda i, c: (i.ring(12, 11, 6, 3, (70, 120, 60)), i.cut(17, 11, 4), i.ring(20, 21, 6, 3, (70, 120, 60)), i.cut(15, 21, 4),
                               i.disc(24, 26, 2.2, (70, 120, 60)), i.disc(25, 25.5, 0.7, BLOOD)),
    "e_main": lambda i, c: (i.rect(9, 15, 23, 28, c), [i.rect(9 + 4 * k, 6 + (k % 2) * 2 - (k == 1) * 2, 12 + 4 * k, 16, c) for k in range(3)],
                            i.poly([(6, 17), (9, 15), (9, 22)], c), i.rect(21, 9, 23, 16, c)),
}


def main():
    parchment()
    panel()
    button("button.png", WINE, BRONZE)
    button("button_hover.png", BLOOD, GOLD)
    button("button_disabled.png", ASH, (80, 76, 78))
    seat_disc("token_good.png", GOOD)
    seat_disc("token_evil.png", EVIL)
    seat_disc("token_neutral.png", BRONZE)
    overlays()
    markers()
    emblems()
    for name, draw in ICONS.items():
        icon(name, draw)
    for name, draw in EMBLEMS.items():
        icon(name, draw)
    print(f"{len(list(OUT.rglob('*.png')))} textures générées dans {OUT}")


if __name__ == "__main__":
    main()
