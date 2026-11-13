"""Génère les textures originales du mod (aucune dépendance externe).

Lancer depuis le dossier minecraft :  python tools/make_assets.py
(puis tools/make_models.py et tools/make_tools.py, qui reprennent cette palette et ces emblèmes).

Dessins en haute résolution (moteur tools/dessin.py) : les coordonnées restent
celles des anciennes textures (32 unités pour les jetons et les emblèmes, 16 pour
la lune, le soleil et le sablier, 12 pour les marqueurs) et le rendu se fait à
128 px (96 px pour les marqueurs), anticrénelé, avec biseaux, reflets et ombres.
Chaque texture d'interface reçoit un .png.mcmeta qui demande un filtrage lisse,
puisqu'elle est dessinée à l'écran plus petite que sa taille réelle.

Les fonds et cadres d'interface (parchemin, cadre, boutons) gardent leur ancien
dessin et ne sont redessinés qu'avec l'option --cadres : la boîte à outils
d'interface haute résolution les remplace.
"""
import math
import random
import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dessin as D  # noqa: E402  (moteur haute résolution)

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

# Tailles réelles des textures d'interface en haute résolution, en pixels.
# Le code d'interface les reprend (fr.valcombe.client.ui.Theme : TOKEN_TEX, ICON_TEX, HUD_TEX, MARKER_TEX).
ICONE = 128     # emblèmes de rôles : 32 unités × 4
JETON = 128     # disques de siège et calques (linceul, sélection, survol) : 32 unités × 4
HUD = 128       # lune, soleil, sablier : 16 unités × 8
MARQUEUR = 96   # marqueurs du grimoire : 12 unités × 8


class Img:
    """Ancienne toile pixel par pixel (1 unité = 1 pixel), gardée pour les cadres et boutons."""

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


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3])


def smooth(a, b, v):
    """Transition douce de 0 (v <= a) à 1 (v >= b)."""
    if v <= a:
        return 0.0
    if v >= b:
        return 1.0
    t = (v - a) / (b - a)
    return t * t * (3 - 2 * t)


def save(img, name, lisse=True):
    """Écrit une texture d'interface haute résolution (avec son .mcmeta de filtrage lisse)."""
    img.enregistrer(OUT / name, lisse=lisse)


# ---------------------------------------------------------------------------
# Fonds et cadres (ancien dessin, redessinés seulement avec --cadres)
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
    """Cadre 9 tranches 48x48 : bois sombre veiné, double liseré bronze en relief, coins à clous dorés."""
    img = Img(48, 48)
    noise_fill(img, WINE, 7, 3)
    rnd = random.Random(31)
    for y in range(48):  # veines du bois
        v = math.sin(y * 0.9 + rnd.random()) * 0.08
        for x in range(48):
            img.put(x, y, shade(WINE, 0.8 + v) + (90,))
    for y in range(6, 42):
        for x in range(6, 42):
            img.px[y][x] = [0, 0, 0, 0]
    for i in range(48):
        for t in (0, 47):
            img.put(i, t, INK)
            img.put(t, i, INK)
        img.put(i, 1, shade(WINE, 1.5))  # lumière en haut / à gauche
        img.put(1, i, shade(WINE, 1.5))
        img.put(i, 46, shade(WINE, 0.55))
        img.put(46, i, shade(WINE, 0.55))
        img.put(i, 3, GOLD)
        img.put(3, i, GOLD)
        img.put(i, 4, BRONZE_DARK)
        img.put(4, i, BRONZE_DARK)
        img.put(i, 43, BRONZE_DARK)
        img.put(43, i, BRONZE_DARK)
        img.put(i, 44, BRONZE)
        img.put(44, i, BRONZE)
        img.put(i, 5, INK + (160,))  # ombre intérieure
        img.put(5, i, INK + (160,))
    for cx, cy in ((4, 4), (43, 4), (4, 43), (43, 43)):
        img.disc(cx + 0.5, cy + 0.5, 3.4, INK)
        img.disc(cx + 0.5, cy + 0.5, 2.8, BRONZE)
        img.disc(cx, cy, 1.4, GOLD)
        img.put(cx - 1, cy - 1, WHITE + (200,))
    img.save("panel.png")


def button(name, face, rim):
    """Bouton 9 tranches 64x20 : cire bombée (dégradé), liseré métal, relief lumière / ombre."""
    img = Img(64, 20)
    rnd = random.Random(zlib.crc32(name.encode("utf-8")) % 1000)  # graine stable d'un lancement à l'autre
    for y in range(20):
        f = 1.28 - 0.5 * (y / 19)  # plus clair en haut, plus sombre en bas
        for x in range(64):
            n = rnd.uniform(-0.04, 0.04)
            img.px[y][x] = list(shade(face, f + n)) + [255]
    for x in range(64):
        img.put(x, 0, INK)
        img.put(x, 19, INK)
        img.put(x, 1, rim)
        img.put(x, 2, WHITE + (70,))
        img.put(x, 18, shade(rim, 0.55))
        img.put(x, 17, INK + (70,))
    for y in range(20):
        img.put(0, y, INK)
        img.put(63, y, INK)
        img.put(1, y, rim)
        img.put(62, y, shade(rim, 0.55))
    for cx, cy in ((0, 0), (63, 0), (0, 19), (63, 19)):
        img.px[cy][cx] = [0, 0, 0, 0]
    img.save(name)


# ---------------------------------------------------------------------------
# Jetons de siège (128 px) et calques posés dessus
# ---------------------------------------------------------------------------
def seat_disc(name, rim):
    """Jeton de siège : anneau de métal bombé, brossé et éclairé d'en haut à gauche,
    ombre de l'anneau sur un disque de parchemin grenu, filets d'encre."""
    img = D.Toile(32, 32, JETON / 32)
    s = img.s
    grain = D.Bruit(11)
    lx, ly = D.LUMIERE

    def f(x, y):
        dx, dy = x - 16, y - 16
        d = math.hypot(dx, dy)
        a = (15.5 - d) * s + 0.5
        if a <= 0:
            return None
        a = min(1.0, a)
        light = (dx * lx + dy * ly) / (d + 1e-6)
        if d >= 12:
            # Anneau : bombé (plus clair vers le milieu), brossé (stries suivant le tour), reflet net
            t = (d - 12) / 3.5
            bulge = 1 - abs(t - 0.45) * 1.3
            ang = math.atan2(dy, dx)
            brosse = grain(ang * 40, d * 0.6) * 0.08 - 0.04
            c = shade(rim, 0.72 + 0.22 * bulge + 0.3 * light + brosse)
            spec = max(0.0, light - 0.5) * 2 * max(0.0, 1 - abs(t - 0.4) / 0.25)
            return D.melange(c, WHITE, min(0.8, spec * 0.7)) + (a,)
        # Parchemin : grain, vignettage, ombre portée de l'anneau en haut à gauche
        v = 1 - 0.16 * (d / 12) ** 2 + 0.05 * grain.fbm(x * 0.9, y * 0.9)
        ombre = max(0.0, light) * smooth(9.0, 12.0, d) * 0.28
        return shade(PARCH, v - ombre) + (1.0,)
    img.peindre(f)
    img.ring(16, 16, 15.3, 0.9, INK)
    img.ring(16, 16, 12, 1.0, shade(rim, 0.45))
    img.ring(16, 16, 10.2, 0.5, PARCH_DARK + (150,))
    for k in range(24):  # petits points d'ornement sur le filet intérieur
        a = 2 * math.pi * (k + 0.5) / 24
        img.disc(16 + 10.9 * math.cos(a), 16 + 10.9 * math.sin(a), 0.22, PARCH_DARK + (170,))
    save(img, name)


def overlays():
    # Linceul (mort) : voile sombre plus dense au bord, croix d'os en relief
    img = D.Toile(32, 32, JETON / 32, relief=0.9, biseau=1.0)

    def voile(x, y):
        d = math.hypot(x - 16, y - 16)
        return 20, 20, 26, 150 + 50 * (d / 15) ** 2
    img.disc(16, 16, 15, voile, relief=0, degrade=0)
    img.ring(16, 16, 14.6, 0.8, (60, 58, 66, 200), relief=0)
    img.line(9.5, 9.5, 22.5, 22.5, (214, 210, 200, 225), 2.2)
    img.line(22.5, 9.5, 9.5, 22.5, (214, 210, 200, 225), 2.2)
    save(img, "token_dead.png")

    # Sélection : anneau d'or ciselé, avec un halo doré
    img = D.Toile(32, 32, JETON / 32, relief=1.0, biseau=0.9)
    img.ring(16, 16, 14.6, 2.2, GOLD)
    img.lueur(0.8, 0.55, (255, 220, 120))
    for k in range(8):
        a = 2 * math.pi * k / 8
        img.disc(16 + 14.6 * math.cos(a), 16 + 14.6 * math.sin(a), 0.55, WHITE + (220,))
    save(img, "token_selected.png")

    # Survol : anneau clair et doux
    img = D.Toile(32, 32, JETON / 32, relief=0.6, biseau=0.8)
    img.ring(16, 16, 15, 1.9, WHITE + (205,))
    img.lueur(0.6, 0.35, WHITE)
    save(img, "token_hover.png")


def markers():
    """Petits marqueurs posés sur les jetons du grimoire (12 unités, rendus en 96 px)."""
    def save_marker(name, draw):
        img = D.Toile(12, 12, MARQUEUR / 12, relief=1.0, biseau=0.6, degrade=0.8)
        img.disc(6, 6, 5.6, INK, relief=0.6, biseau=1.0)
        img.ring(6, 6, 5.1, 0.45, BRONZE)
        draw(img)
        save(img, "marker_" + name + ".png")

    def poison(i):
        vert = (90, 170, 70)
        i.composee(D.union(D.disque(6, 7.1, 2.9), D.rect(5.1, 2.6, 6.9, 5)), vert)
        i.rect(4.8, 2.0, 7.2, 3.0, BRONZE)  # bouchon
        i.disc(5.0, 6.3, 0.6, WHITE + (200,))
        i.disc(6.9, 8.0, 0.4, (200, 240, 170))

    def protect(i):
        i.poly([(3, 3), (9, 3), (9, 7), (6, 10), (3, 7)], (200, 200, 220))
        i.line(6, 3.8, 6, 8.6, GOLD, 0.6)
        i.line(4, 5.4, 8, 5.4, GOLD, 0.6)

    def drunk(i):
        i.composee(D.difference(D.disque(6, 6, 3.5), D.disque(7.6, 5, 3)), GOLD)
        i.etincelle(8.3, 8.2, 1.0, WHITE)

    def herring(i):
        i.poly([(2, 6), (6, 3), (9, 6), (11, 4), (11, 8), (9, 6), (6, 9)], EVIL)
        i.disc(4.4, 5.6, 0.45, WHITE)
        i.line(6.5, 4.5, 6.5, 7.5, shade(EVIL, 0.6), 0.3)

    def master(i):
        i.composee(D.union(D.disque(6, 4.2, 1.9), D.poly([(4.6, 10), (5.2, 6), (6.8, 6), (7.4, 10)])), GOLD)
        i.poly([(4.5, 2.5), (4.7, 1.0), (5.4, 1.8), (6, 0.9), (6.6, 1.8), (7.3, 1.0), (7.5, 2.5)], WHITE)  # petite couronne

    def ghost(i):
        corps = D.union(D.disque(6, 5, 3.5), D.rect(2.5, 5, 9.5, 9))
        for x in (3.6, 6, 8.4):  # bas ondulé
            corps = D.difference(corps, D.disque(x, 9.6, 0.9))
        i.composee(corps, WHITE)
        i.disc(4.9, 5.0, 0.6, INK)
        i.disc(7.1, 5.0, 0.6, INK)

    save_marker("poison", poison)
    save_marker("protect", protect)
    save_marker("drunk", drunk)
    save_marker("herring", herring)
    save_marker("master", master)
    save_marker("ghost", ghost)


def emblems():
    """Lune, soleil, sablier de l'affichage en jeu (16 unités, rendus en 128 px)."""
    def toile():
        return D.Toile(16, 16, HUD / 16, relief=1.0, biseau=0.8, degrade=1.0)

    # Lune : croissant d'argent, cratères, halo froid
    img = toile()
    pale = (210, 215, 235)
    img.composee(D.difference(D.disque(8, 8, 6.5), D.disque(10.5, 6.5, 5.5)), pale)
    for x, y, r in ((4.2, 9.5, 0.8), (6.5, 12.6, 0.6), (3.6, 6.4, 0.45)):
        img.disc(x, y, r, shade(pale, 0.82), creux=True, relief=0.8)
    img.lueur(1.0, 0.3, (170, 190, 255))
    save(img, "moon.png")

    # Soleil : rayons effilés, disque d'or bombé, cœur plus chaud
    img = toile()
    for k in range(8):
        a = k * math.pi / 4
        c, s_ = math.cos(a), math.sin(a)
        img.poly([(8 + 4.2 * c - 1.1 * s_, 8 + 4.2 * s_ + 1.1 * c), (8 + 7.6 * c, 8 + 7.6 * s_),
                  (8 + 4.2 * c + 1.1 * s_, 8 + 4.2 * s_ - 1.1 * c)], GOLD)
    for k in range(8):
        a = k * math.pi / 4 + math.pi / 8
        img.line(8 + 4.5 * math.cos(a), 8 + 4.5 * math.sin(a), 8 + 6 * math.cos(a), 8 + 6 * math.sin(a), shade(GOLD, 0.85), 0.6)

    def coeur(x, y):
        return D.melange((250, 214, 110), (214, 140, 50), min(1.0, math.hypot(x - 8, y - 8) / 4.2))
    img.disc(8, 8, 4.2, coeur)
    img.lueur(0.9, 0.35, (255, 210, 120))
    save(img, "sun.png")

    # Sablier : montants de bois, verre, sable qui coule
    img = toile()
    verre = (200, 220, 230, 110)
    img.poly([(4, 2.6), (12, 2.6), (8.7, 8), (12, 13.4), (4, 13.4), (7.3, 8)], verre, relief=0.6)
    img.poly([(5.6, 4.6), (10.4, 4.6), (8, 7.6)], PARCH)                      # sable en haut
    img.poly([(8, 9.6), (11, 13), (5, 13)], PARCH)                            # tas en bas
    img.line(8, 7.4, 8, 12, shade(PARCH, 0.9), 0.35, relief=0)                # filet de sable
    img.line(4.6, 3.4, 7.4, 7.4, WHITE + (120,), 0.35, relief=0)              # reflet du verre
    img.rect(2.6, 1.2, 13.4, 2.8, BRONZE)
    img.rect(2.6, 13.2, 13.4, 14.8, BRONZE)
    img.line(3.4, 2.6, 3.4, 13.4, BRONZE_DARK, 0.8)
    img.line(12.6, 2.6, 12.6, 13.4, BRONZE_DARK, 0.8)
    img.ombre(0.25, 0.4, 0.8, 0.4)
    save(img, "hourglass.png")


# ---------------------------------------------------------------------------
# Pictogrammes des rôles (32 unités, encre sur parchemin, centrés ; rendus en 128 px)
# Les formes en PARCH sont dessinées en creux (le parchemin vu à travers l'encre).
# ---------------------------------------------------------------------------
def toile_icone(taille=ICONE):
    return D.Toile(32, 32, taille / 32, relief=1.0, biseau=1.3, degrade=1.0, creux=(PARCH, PARCH_DARK))


def dessiner_icone(nom, taille=ICONE):
    """Rend l'emblème `nom` (rôle ou bibliothèque) en taille × taille pixels, avec ses détails et son ombre."""
    draw = ICONS.get(nom) or EMBLEMS[nom]
    img = toile_icone(taille)
    draw(img, INK)
    if nom in DETAILS:
        DETAILS[nom](img, INK)
    img.ombre()
    return img


def icon(name):
    save(dessiner_icone(name), "roles/" + name + ".png")


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
    "chapelain": lambda i, c: (i.composee(D.union(D.rect(14, 4, 18, 28), D.rect(8, 10, 24, 14)), c), i.ring(16, 12, 7, 1, GOLD)),
    "fauconnier": lambda i, c: (i.disc(15, 16, 6, c), i.disc(21, 12, 3.5, c), i.poly([(24, 11), (29, 12), (24, 14)], GOLD),
                                i.poly([(9, 14), (3, 6), (14, 11)], c), i.poly([(10, 19), (4, 27), (15, 21)], c), i.disc(22, 11, 0.9, PARCH)),
    "pelerine": lambda i, c: (i.line(16, 28, 16, 14, c, 1.8), i.poly([(16, 4), (19, 12), (16, 16), (13, 12)], WINE),
                              i.poly([(16, 15), (8, 9), (10, 16)], WINE), i.poly([(16, 15), (24, 9), (22, 16)], WINE),
                              i.ring(16, 11, 7.5, 0.8, c)),
    "arbaletrier": lambda i, c: (i.line(5, 9, 27, 9, c, 2.2), i.line(16, 6, 16, 28, c, 2.2),
                                 i.line(5, 9, 16, 19, BRONZE, 1), i.line(27, 9, 16, 19, BRONZE, 1),
                                 i.poly([(16, 2), (19, 7), (13, 7)], c)),
    "templier": lambda i, c: (i.poly([(6, 5), (26, 5), (26, 15), (16, 28), (6, 15)], c),
                              i.composee(D.union(D.rect(15, 8, 17, 22), D.rect(10, 12, 22, 14)), BLOOD)),
    "chatelain": lambda i, c: (i.poly([(5, 24), (5, 10), (11, 16), (16, 7), (21, 16), (27, 10), (27, 24)], GOLD),
                               i.rect(5, 23, 27, 27, c), [i.disc(x, y, 1.5, BLOOD) for x, y in ((5, 9), (16, 6), (27, 9))]),
    "ecuyer": lambda i, c: i.composee(D.union(D.anneau(10, 16, 5, 2.5), D.rect(14, 15, 28, 18), D.rect(22, 18, 24, 22),
                                              D.rect(26, 18, 28, 21)), c),
    "somnambule": lambda i, c: (i.disc(13, 17, 9, c), i.cut(18, 13, 8),
                                i.chemin([(20, 5), (26, 5), (20, 11), (26, 11)], c, 1.3)),
    "excommunie": lambda i, c: (i.poly([(16, 4), (26, 12), (25, 28), (7, 28), (6, 12)], c), i.disc(16, 16, 5, INK, creux=True),
                                i.disc(14, 16, 1, EVIL), i.disc(18, 16, 1, EVIL)),
    "martyr": lambda i, c: (i.ring(16, 7, 7, 1.6, GOLD), i.disc(16, 15, 4.5, c), i.poly([(9, 28), (11, 20), (21, 20), (23, 28)], c)),
    "alchimiste": lambda i, c: (i.composee(D.union(D.rect(13, 4, 19, 12), D.poly([(13, 11), (19, 11), (26, 26), (6, 26)])), c),
                                i.poly([(10, 19), (22, 19), (25, 25), (7, 25)], (90, 170, 70)), i.disc(14, 22, 1.2, WHITE)),
    "mouchard": lambda i, c: (i.poly([(3, 12), (13, 10), (16, 13), (19, 10), (29, 12), (26, 21), (18, 20), (16, 17), (14, 20), (6, 21)], c),
                              i.disc(10, 15, 2, PARCH), i.disc(22, 15, 2, PARCH)),
    "heritiere": lambda i, c: (i.ring(16, 19, 7, 2.6, GOLD), i.poly([(16, 5), (21, 11), (16, 15), (11, 11)], BLOOD)),
    "margrave": lambda i, c: (i.composee(D.union(D.trait(16, 5, 16, 27, 1.8), D.trait(6, 9, 26, 9, 1.8), D.rect(10, 26, 22, 28)), c),
                              i.poly([(6, 9), (2, 17), (10, 17)], BRONZE), i.poly([(26, 9), (22, 17), (30, 17)], BRONZE)),
    "sonneur": lambda i, c: (i.composee(D.union(D.poly([(16, 4), (21, 8), (23, 20), (26, 24), (6, 24), (9, 20), (11, 8)]),
                                                D.rect(15, 2, 17, 5)), c),
                             i.disc(16, 26.5, 2.5, c), i.line(5, 12, 2, 10, c, 1.2),
                             i.line(27, 12, 30, 10, c, 1.2), i.line(4, 17, 1, 17, c, 1.2), i.line(28, 17, 31, 17, c, 1.2)),
    "cartographe": lambda i, c: (i.ring(16, 16, 11, 1.6, c), i.poly([(16, 4), (18.5, 16), (16, 28), (13.5, 16)], c),
                                 i.poly([(4, 16), (16, 13.5), (28, 16), (16, 18.5)], c), i.disc(16, 16, 2.2, PARCH),
                                 i.poly([(16, 4), (18.5, 16), (13.5, 16)], BLOOD)),
    "faussaire": lambda i, c: (i.poly([(25, 3), (29, 5), (13, 22), (10, 23), (11, 20)], c), i.line(10, 23, 6, 28, c, 1.4),
                               i.disc(9, 25, 5, BLOOD), i.disc(9, 25, 3, (180, 50, 50)), i.line(20, 10, 24, 6, PARCH, 1)),
    "voyageur": lambda i, c: (i.line(6, 28, 24, 6, BRONZE_DARK, 2.2), i.disc(24, 10, 6.5, WINE), i.disc(22, 8, 1.8, (170, 70, 90)),
                              i.line(21, 4, 27, 4, c, 1.4), i.poly([(3, 30), (9, 30), (6, 26)], c)),
    "goule": lambda i, c: (i.composee(D.union(D.disque(16, 14, 9), D.rect(11, 20, 21, 27)), WHITE),
                           i.disc(12.5, 14, 2.5, INK, creux=True), i.disc(19.5, 14, 2.5, INK, creux=True),
                           i.poly([(16, 17), (17.5, 20), (14.5, 20)], INK, creux=True),
                           [i.rect(12 + 3 * k, 24, 13 + 3 * k, 27, INK, creux=True) for k in range(3)],
                           i.poly([(8, 9), (4, 2), (11, 6)], BLOOD), i.poly([(24, 9), (28, 2), (21, 6)], BLOOD)),
}


# Bibliothèque d'emblèmes pour les rôles créés dans l'Atelier (32 unités, mêmes couleurs)
EMBLEMS = {
    "e_etoile": lambda i, c: (i.poly([(16, 3), (19, 12), (29, 12), (21, 18), (24, 28), (16, 22), (8, 28), (11, 18), (3, 12), (13, 12)], GOLD),
                              i.disc(16, 15, 2, c)),
    "e_lune": lambda i, c: (i.composee(D.difference(D.disque(15, 16, 11), D.disque(20, 12, 9.5)), c),
                            i.disc(25, 23, 1.2, GOLD), i.disc(23, 7, 0.9, GOLD)),
    "e_soleil": lambda i, c: ([i.line(16 + 9 * math.cos(k * 0.785), 16 + 9 * math.sin(k * 0.785),
                                      16 + 14 * math.cos(k * 0.785), 16 + 14 * math.sin(k * 0.785), GOLD, 2)
                               for k in range(8)], i.disc(16, 16, 7, GOLD), i.disc(16, 16, 4.5, c)),
    "e_cle": lambda i, c: i.composee(D.union(D.anneau(10, 10, 5.5, 2.4), D.trait(14, 14, 27, 27, 2.6), D.trait(22, 22, 25, 19, 2.2),
                                             D.trait(25, 25, 28, 22, 2.2)), c),
    "e_plume": lambda i, c: (i.poly([(26, 3), (29, 6), (14, 24), (9, 25), (10, 20)], c), i.line(10, 24, 4, 30, c, 1.4),
                             i.line(25, 6, 12, 22, PARCH, 0.9)),
    "e_oeil": lambda i, c: (i.poly([(2, 16), (9, 9), (23, 9), (30, 16), (23, 23), (9, 23)], c), i.disc(16, 16, 5.5, PARCH),
                            i.disc(16, 16, 3, GOOD), i.disc(16, 16, 1.3, c)),
    "e_coeur": lambda i, c: (i.composee(D.union(D.disque(11, 12, 6), D.disque(21, 12, 6), D.poly([(5.5, 14), (26.5, 14), (16, 28)])), BLOOD),
                             i.disc(9, 10, 1.5, (220, 120, 120))),
    "e_epee": lambda i, c: (i.poly([(16, 2), (18.5, 6), (18.5, 21), (13.5, 21), (13.5, 6)], (200, 200, 210)),
                            i.rect(8, 21, 24, 24, c), i.rect(14, 24, 18, 29, c), i.disc(16, 30, 2, GOLD)),
    "e_bouclier": lambda i, c: (i.poly([(5, 5), (27, 5), (27, 15), (16, 29), (5, 15)], c), i.poly([(8, 8), (24, 8), (24, 15), (16, 25), (8, 15)], GOOD),
                                i.rect(15, 8, 17, 24, GOLD)),
    "e_couronne": lambda i, c: (i.poly([(4, 24), (4, 9), (10, 16), (16, 6), (22, 16), (28, 9), (28, 24)], GOLD), i.rect(4, 23, 28, 27, c),
                                [i.disc(x, 19, 1.4, BLOOD) for x in (10, 16, 22)]),
    "e_livre": lambda i, c: (i.rect(6, 5, 26, 28, c), i.rect(8, 7, 24, 26, WINE), i.rect(24, 7, 27, 26, PARCH),
                             i.line(11, 12, 21, 12, GOLD, 1.4), i.line(11, 16, 21, 16, GOLD, 1)),
    "e_fiole": lambda i, c: (i.rect(13, 3, 19, 6, BRONZE), i.composee(D.union(D.rect(14, 5.5, 18, 12), D.disque(16, 20, 8)), c),
                             i.disc(16, 20, 6.5, PARCH), i.disc(16, 22, 5, (120, 70, 170)), i.disc(14, 19, 1.2, WHITE)),
    "e_rose": lambda i, c: (i.line(16, 30, 16, 16, (60, 110, 60), 1.8), i.poly([(16, 24), (22, 20), (19, 26)], (60, 110, 60)),
                            i.disc(16, 11, 7, BLOOD), i.ring(16, 11, 4, 1.2, (100, 15, 20)), i.disc(16, 11, 1.5, (100, 15, 20))),
    "e_cloche": lambda i, c: (i.poly([(16, 4), (22, 9), (24, 21), (28, 25), (4, 25), (8, 21), (10, 9)], BRONZE), i.rect(15, 2, 17, 5, c),
                              i.disc(16, 27.5, 2.5, c), i.line(10, 21, 22, 21, BRONZE_DARK, 1)),
    "e_lanterne": lambda i, c: (i.ring(16, 5, 3, 1.4, c), i.rect(9, 8, 23, 10, c), i.rect(10, 10, 22, 25, c), i.rect(12, 12, 20, 23, GOLD),
                                i.disc(16, 17, 2.5, WHITE), i.rect(9, 25, 23, 27, c)),
    "e_masque": lambda i, c: (i.poly([(3, 10), (13, 9), (16, 12), (19, 9), (29, 10), (27, 20), (19, 21), (16, 18), (13, 21), (5, 20)], WINE),
                              i.disc(10, 14, 2.3, PARCH), i.disc(22, 14, 2.3, PARCH), i.line(29, 10, 31, 4, GOLD, 1.2)),
    "e_ancre": lambda i, c: (i.composee(D.union(D.difference(D.anneau(16, 16, 11, 2.2), D.disque(16, 11, 11)), D.anneau(16, 6, 3, 1.6),
                                                D.trait(16, 9, 16, 27, 2.2), D.trait(10, 13, 22, 13, 2),
                                                D.poly([(3, 19), (8, 21), (4, 24)]), D.poly([(29, 19), (24, 21), (28, 24)])), c)),
    "e_corbeau": lambda i, c: (i.composee(D.union(D.disque(14, 18, 8), D.disque(20, 11, 5), D.poly([(6, 22), (2, 29), (12, 25)])), c),
                               i.poly([(24, 10), (31, 13), (24, 14)], BRONZE_DARK), i.disc(21, 10, 1.2, GOLD)),
    "e_serpent": lambda i, c: (i.composee(D.union(D.difference(D.anneau(12, 11, 6, 3), D.disque(17, 11, 4)),
                                                  D.difference(D.anneau(20, 21, 6, 3), D.disque(15, 21, 4)),
                                                  D.disque(24, 26, 2.2)), (70, 120, 60)),
                               i.disc(25, 25.5, 0.7, BLOOD)),
    "e_main": lambda i, c: i.composee(D.union(D.rect(9, 15, 23, 28), *[D.rect(9 + 4 * k, 6 + (k % 2) * 2 - (k == 1) * 2, 12 + 4 * k, 16)
                                                                         for k in range(3)],
                                              D.poly([(6, 17), (9, 15), (9, 22)]), D.rect(21, 9, 23, 16)), c),
}


# Détails ajoutés par-dessus les formes de base : ils n'étaient pas lisibles en 32 px.
def _d_alchimiste(i, c):
    i.line(10.4, 19.2, 21.6, 19.2, (150, 215, 120), 0.5, relief=0)       # surface du liquide
    i.disc(17.5, 21.2, 0.8, WHITE + (210,))
    i.disc(16.2, 17.3, 0.55, WHITE + (190,))
    i.disc(19.8, 23.2, 0.5, WHITE + (170,))
    i.rect(12, 3, 20, 4.8, c)                                            # col évasé


def _d_aubergiste(i, c):
    for x, y, r in ((10, 7.4, 1.7), (13.6, 6.8, 1.9), (17.4, 7.0, 1.7), (20.6, 7.6, 1.4)):  # mousse
        i.disc(x, y, r, WHITE)
    for x, y, r in ((13, 19.5, 0.55), (16, 21.5, 0.65), (17.6, 17.4, 0.45), (14.4, 16.6, 0.4)):  # bulles
        i.disc(x, y, r, (236, 206, 140), relief=0.6)


def _d_astrologue(i, c):
    i.etincelle(6, 5, 1.8, GOLD)
    i.etincelle(27, 24, 1.4, GOLD)
    i.etincelle(26.5, 4.5, 1.0, GOLD)


def _d_cartographe(i, c):
    for k in range(16):  # graduations de la boussole
        a = k * math.pi / 8
        r0 = 12.2 if k % 2 == 0 else 12.4
        i.line(16 + r0 * math.cos(a), 16 + r0 * math.sin(a), 16 + 13.6 * math.cos(a), 16 + 13.6 * math.sin(a), c, 0.6)


def _d_chatelain(i, c):
    for x, col in ((10, GOOD), (16, BLOOD), (22, GOOD)):
        i.disc(x, 25, 1.0, col)
    i.line(6, 21.5, 26, 21.5, shade(GOLD, 0.75), 0.4, relief=0)


def _d_excommunie(i, c):
    for x in (14, 18):
        i.halo(x, 16, 2.4, EVIL, 0.5)
        i.disc(x, 16, 0.4, (255, 180, 160))


def _d_fauconnier(i, c):
    i.chemin([(4.8, 7.6), (9, 11), (12.6, 11.6)], PARCH_DARK, 0.45)
    i.chemin([(5.6, 25.2), (10, 20.6), (13.6, 20.6)], PARCH_DARK, 0.45)


def _d_faussaire(i, c):
    i.ring(9, 25, 3.9, 0.45, (110, 16, 18), relief=0.5)
    i.poly([(9, 22.6), (10.2, 25), (9, 27.4), (7.8, 25)], (120, 24, 26), relief=0.8)


def _d_goule(i, c):
    i.chemin([(18.5, 5.4), (17.4, 8), (18.8, 9.6), (18.2, 11)], shade(WHITE, 0.6), 0.45, relief=0)
    i.chemin([(9, 17.5), (10.6, 19.2)], shade(WHITE, 0.6), 0.4, relief=0)


def _d_heritiere(i, c):
    i.poly([(16, 6.2), (19.2, 10.6), (16, 11.2)], WHITE + (110,), relief=0)
    i.line(16, 5, 16, 15, (110, 16, 18), 0.35, relief=0)


def _d_inquisiteur(i, c):
    i.ring(16, 16, 4.1, 0.5, GOOD, relief=0.4)
    i.disc(17.2, 14.8, 0.8, WHITE)


def _d_margrave(i, c):
    i.disc(16, 9, 1.5, GOLD)
    i.disc(16, 4.6, 1.1, GOLD)


def _d_martyr(i, c):
    for k in range(7):  # rayons de l'auréole
        a = math.pi + k * math.pi / 6
        i.line(16 + 8.6 * math.cos(a), 7 + 8.6 * math.sin(a), 16 + 10.4 * math.cos(a), 7 + 10.4 * math.sin(a), GOLD, 0.7)


def _d_mouchard(i, c):
    i.disc(10.7, 14.3, 0.5, WHITE + (200,))
    i.disc(22.7, 14.3, 0.5, WHITE + (200,))


def _d_sonneur(i, c):
    i.line(9.6, 20.6, 22.4, 20.6, BRONZE, 0.8, relief=0.6)
    i.line(10.6, 9.4, 21.4, 9.4, BRONZE, 0.6, relief=0.6)


def _d_templier(i, c):
    i.chemin([(7.5, 6.5), (24.5, 6.5), (24.5, 14.5), (16, 25.6), (7.5, 14.5), (7.5, 6.5)], BRONZE, 0.7, relief=0.6)


def _d_veilleuse(i, c):
    i.halo(16, 9.5, 6, (255, 220, 130), 0.55)
    i.poly([(16, 4), (19, 10), (16, 13), (13, 10)], GOLD)
    i.disc(16, 10, 1.5, WHITE)
    i.poly([(12.6, 14), (15, 14), (14.2, 17)], shade(INK, 1.8), relief=0.5)  # coulure de cire


def _d_voyageur(i, c):
    i.line(19.6, 12.6, 28.4, 7.4, (120, 40, 56), 0.6, relief=0)
    i.line(20.4, 15.2, 27.0, 11.6, (120, 40, 56), 0.5, relief=0)


def _d_e_bouclier(i, c):
    i.disc(16, 13.5, 2.2, GOLD)


def _d_e_corbeau(i, c):
    # Aile repliée : deux rangs de plumes à peine plus clairs que l'encre
    i.chemin([(8.4, 15.2), (12.4, 20.4), (18.4, 21.4)], (78, 62, 66), 0.45, relief=0)
    i.chemin([(10.4, 14.6), (13.6, 18.4), (17.8, 19.0)], (66, 52, 56), 0.35, relief=0)


def _d_e_epee(i, c):
    i.line(16, 6, 16, 19.6, (150, 150, 168), 0.7, relief=0)
    i.disc(8.8, 22.5, 0.9, GOLD)
    i.disc(23.2, 22.5, 0.9, GOLD)


def _d_e_etoile(i, c):
    i.etincelle(26, 4.5, 1.5, GOLD)


def _d_e_fiole(i, c):
    i.disc(18, 23.4, 0.6, WHITE + (200,))
    i.disc(15.6, 24.4, 0.45, WHITE + (180,))


def _d_e_lanterne(i, c):
    i.halo(16, 17, 5.5, WHITE, 0.45)
    for x in (14, 18):
        i.line(x, 12, x, 23, shade(GOLD, 0.6), 0.4, relief=0)


def _d_e_livre(i, c):
    for x in (24.8, 25.6, 26.4):
        i.line(x, 7.6, x, 25.4, PARCH_DARK, 0.25, relief=0)


def _d_e_lune(i, c):
    i.disc(9, 19, 1.4, c, creux=True)
    i.disc(13, 24, 0.9, c, creux=True)


def _d_e_oeil(i, c):
    i.disc(17.2, 14.8, 0.9, WHITE)


def _d_e_plume(i, c):
    # Barbes : fines stries entre le rachis et le bord de la plume, inclinées vers la pointe
    for k in range(6):
        t = 0.18 + 0.11 * k
        x, y = 25 - 13 * t, 6 + 16 * t
        i.line(x + 0.5, y + 0.06, x + 2.2, y + 0.26, PARCH_DARK + (150,), 0.25, relief=0)


def _d_e_rose(i, c):
    i.line(16.6, 24, 21, 21, (40, 80, 40), 0.35, relief=0)
    i.disc(13, 8.6, 1.0, (200, 80, 80))


def _d_e_serpent(i, c):
    i.line(25.8, 27, 27.6, 28.6, BLOOD, 0.35, relief=0)


DETAILS = {
    "alchimiste": _d_alchimiste, "aubergiste": _d_aubergiste, "astrologue": _d_astrologue, "cartographe": _d_cartographe,
    "chatelain": _d_chatelain, "excommunie": _d_excommunie, "fauconnier": _d_fauconnier, "faussaire": _d_faussaire,
    "goule": _d_goule, "heritiere": _d_heritiere, "inquisiteur": _d_inquisiteur, "margrave": _d_margrave,
    "martyr": _d_martyr, "mouchard": _d_mouchard, "sonneur": _d_sonneur, "templier": _d_templier,
    "veilleuse": _d_veilleuse, "voyageur": _d_voyageur,
    "e_bouclier": _d_e_bouclier, "e_corbeau": _d_e_corbeau, "e_epee": _d_e_epee, "e_etoile": _d_e_etoile,
    "e_fiole": _d_e_fiole, "e_lanterne": _d_e_lanterne, "e_livre": _d_e_livre, "e_lune": _d_e_lune,
    "e_oeil": _d_e_oeil, "e_plume": _d_e_plume, "e_rose": _d_e_rose, "e_serpent": _d_e_serpent,
}


def main():
    if "--cadres" in sys.argv:
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
    for name in ICONS:
        icon(name)
    for name in EMBLEMS:
        icon(name)
    print(f"{len(list(OUT.rglob('*.png')))} textures d'interface dans {OUT}")


if __name__ == "__main__":
    main()
