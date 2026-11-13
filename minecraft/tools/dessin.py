"""Moteur de dessin haute résolution des textures de Valcombe (aucune dépendance externe).

Les scripts make_*.py décrivent leurs dessins dans un petit espace de coordonnées
(16 ou 32 unités de côté, comme les anciennes textures) ; la Toile les rend à
l'échelle voulue (128 px de côté pour les jetons, emblèmes et objets) :

- chaque forme est une fonction de distance signée (disque, anneau, rectangle,
  trait, polygone) : les bords sont anticrénelés exactement, à toute échelle ;
- chaque forme reçoit un relief : biseau éclairé d'en haut à gauche, dégradé de
  volume ; les formes « en creux » (le parchemin vu à travers l'encre) sont
  éclairées à l'envers, comme gravées ;
- des effets d'ensemble : ombre portée floue, contour, grain de matière.

Les couleurs sont des tuples (r, g, b) ou (r, g, b, a) en 0 à 255, comme dans
make_assets.py. L'image est gardée en alpha prémultiplié (pas de franges sombres
sur les bords transparents) et écrite en PNG RGBA 8 bits sans compression avec perte.
"""
import json
import math
import random
import struct
import zlib
from pathlib import Path

# Direction vers la lumière (en haut, un peu à gauche), normalisée.
LUMIERE = (-0.6, -0.8)


def _sign(v):
    return 1.0 if v >= 0 else -1.0


# ---------------------------------------------------------------------------
# Fonctions de distance signée : renvoient (distance, normale x, normale y),
# en unités logiques ; distance négative à l'intérieur, normale tournée vers
# l'extérieur de la forme.
# ---------------------------------------------------------------------------
def sdf_disque(cx, cy, r):
    def f(x, y):
        dx, dy = x - cx, y - cy
        n = math.hypot(dx, dy)
        if n < 1e-9:
            return -r, 0.0, 0.0
        return n - r, dx / n, dy / n
    return f


def sdf_anneau(cx, cy, r, largeur):
    demi = largeur / 2

    def f(x, y):
        dx, dy = x - cx, y - cy
        n = math.hypot(dx, dy)
        if n < 1e-9:
            return r - demi, 0.0, 0.0
        s = _sign(n - r)
        return abs(n - r) - demi, s * dx / n, s * dy / n
    return f


def sdf_rect(x0, y0, x1, y1):
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    hx, hy = abs(x1 - x0) / 2, abs(y1 - y0) / 2

    def f(x, y):
        px, py = x - cx, y - cy
        qx, qy = abs(px) - hx, abs(py) - hy
        if qx > 0 or qy > 0:
            ox, oy = max(qx, 0.0), max(qy, 0.0)
            n = math.hypot(ox, oy)
            return n, _sign(px) * ox / n, _sign(py) * oy / n
        if qx > qy:
            return qx, _sign(px), 0.0
        return qy, 0.0, _sign(py)
    return f


def sdf_trait(x0, y0, x1, y1, rayon):
    bx, by = x1 - x0, y1 - y0
    bb = bx * bx + by * by or 1e-9

    def f(x, y):
        ax, ay = x - x0, y - y0
        h = (ax * bx + ay * by) / bb
        h = 0.0 if h < 0 else 1.0 if h > 1 else h
        qx, qy = ax - bx * h, ay - by * h
        n = math.hypot(qx, qy)
        if n < 1e-9:
            return -rayon, 0.0, 0.0
        return n - rayon, qx / n, qy / n
    return f


def sdf_poly(pts):
    aretes = []
    for i in range(len(pts)):
        (xa, ya), (xb, yb) = pts[i], pts[(i + 1) % len(pts)]
        ex, ey = xb - xa, yb - ya
        aretes.append((xa, ya, xb, yb, ex, ey, 1.0 / (ex * ex + ey * ey or 1e-9)))

    def f(x, y):
        best = 1e18
        bx = by = 0.0
        inside = False
        for xa, ya, xb, yb, ex, ey, inv in aretes:
            wx, wy = x - xa, y - ya
            h = (wx * ex + wy * ey) * inv
            h = 0.0 if h < 0 else 1.0 if h > 1 else h
            qx, qy = wx - ex * h, wy - ey * h
            d2 = qx * qx + qy * qy
            if d2 < best:
                best, bx, by = d2, qx, qy
            # Règle pair-impair (rayon horizontal vers la droite)
            if (ya <= y) != (yb <= y) and x < xa + (y - ya) * ex / (ey or 1e-9):
                inside = not inside
        n = math.sqrt(best)
        s = -1.0 if inside else 1.0
        if n < 1e-9:
            return 0.0, 0.0, 0.0
        # (bx, by) va du bord vers le point : vers l'extérieur dehors, vers l'intérieur dedans
        return s * n, s * bx / n, s * by / n
    return f


# ---------------------------------------------------------------------------
# Formes composées : chaque fonction renvoie (distance signée, boîte englobante),
# à combiner avec union() et difference() puis à remplir avec Toile.composee().
# Une forme composée reçoit un seul biseau (pas de couture entre ses morceaux).
# ---------------------------------------------------------------------------
def disque(cx, cy, r):
    return sdf_disque(cx, cy, r), (cx - r, cy - r, cx + r, cy + r)


def anneau(cx, cy, r, largeur):
    e = r + largeur / 2
    return sdf_anneau(cx, cy, r, largeur), (cx - e, cy - e, cx + e, cy + e)


def rect(x0, y0, x1, y1):
    return sdf_rect(x0, y0, x1, y1), (min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))


def trait(x0, y0, x1, y1, w=1.5):
    r = w / 2
    return sdf_trait(x0, y0, x1, y1, r), (min(x0, x1) - r, min(y0, y1) - r, max(x0, x1) + r, max(y0, y1) + r)


def poly(pts):
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    return sdf_poly(pts), (min(xs), min(ys), max(xs), max(ys))


def union(*formes):
    fs = [f for f, _ in formes]

    def f(x, y):
        best = None
        for g in fs:
            v = g(x, y)
            if best is None or v[0] < best[0]:
                best = v
        return best
    return f, (min(b[0] for _, b in formes), min(b[1] for _, b in formes),
               max(b[2] for _, b in formes), max(b[3] for _, b in formes))


def difference(a, b):
    """La forme a privée de la forme b (le bord creusé garde une normale vers l'extérieur de a moins b)."""
    fa, fb = a[0], b[0]

    def f(x, y):
        da, ax, ay = fa(x, y)
        db, bx, by = fb(x, y)
        if da >= -db:
            return da, ax, ay
        return -db, -bx, -by
    return f, a[1]


# ---------------------------------------------------------------------------
# Bruit de valeur lissé (grain du parchemin, du cuir, du bois, du feutre)
# ---------------------------------------------------------------------------
class Bruit:
    """Bruit de valeur 2D lissé, périodique de période `periode` cases, reproductible (graine)."""

    def __init__(self, graine, periode=64):
        rnd = random.Random(graine)
        self.p = periode
        self.v = [[rnd.random() for _ in range(periode)] for _ in range(periode)]

    def __call__(self, x, y):
        p = self.p
        xi, yi = math.floor(x), math.floor(y)
        fx, fy = x - xi, y - yi
        fx = fx * fx * (3 - 2 * fx)
        fy = fy * fy * (3 - 2 * fy)
        x0, y0 = xi % p, yi % p
        x1, y1 = (x0 + 1) % p, (y0 + 1) % p
        v = self.v
        a = v[y0][x0] + (v[y0][x1] - v[y0][x0]) * fx
        b = v[y1][x0] + (v[y1][x1] - v[y1][x0]) * fx
        return a + (b - a) * fy

    def fbm(self, x, y, octaves=3):
        """Somme de plusieurs octaves, ramenée entre -1 et 1."""
        tot, amp, norme = 0.0, 1.0, 0.0
        for _ in range(octaves):
            tot += (self(x, y) * 2 - 1) * amp
            norme += amp
            x, y, amp = x * 2.03, y * 2.03, amp * 0.5
        return tot / norme


def melange(a, b, t):
    """Couleur intermédiaire entre a et b (t = 0 : a, t = 1 : b)."""
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def teinte(c, f):
    """Couleur assombrie (f < 1) ou éclaircie (f > 1), bornée."""
    return tuple(max(0, min(255, int(v * f))) for v in c[:3])


# ---------------------------------------------------------------------------
# La toile
# ---------------------------------------------------------------------------
class Toile:
    """Image RGBA haute résolution dessinée en coordonnées logiques.

    largeur, hauteur : taille logique (16 ou 32 en général) ; echelle : pixels par unité.
    relief, biseau, degrade : réglages par défaut du modelé des formes (0 = à plat).
    creux : couleurs dessinées « en creux » (relief inversé).
    """

    def __init__(self, largeur, hauteur, echelle=1, relief=0.0, biseau=1.0, degrade=0.0, creux=()):
        self.lw, self.lh = largeur, hauteur
        self.s = echelle
        self.w, self.h = int(round(largeur * echelle)), int(round(hauteur * echelle))
        n = self.w * self.h
        # Alpha prémultiplié : R, G, B déjà multipliés par A (A entre 0 et 1)
        self.R = [0.0] * n
        self.G = [0.0] * n
        self.B = [0.0] * n
        self.A = [0.0] * n
        self.relief, self.biseau, self.degrade = relief, biseau, degrade
        self.creux = {tuple(c[:3]) for c in creux}

    def copie(self):
        """Toile identique et indépendante (pour réutiliser un fond coûteux à calculer)."""
        t = Toile(self.lw, self.lh, self.s, self.relief, self.biseau, self.degrade)
        t.creux = set(self.creux)
        t.R, t.G, t.B, t.A = self.R[:], self.G[:], self.B[:], self.A[:]
        return t

    # -- bas niveau -----------------------------------------------------------
    def pixel(self, px, py, c, a=1.0):
        """Pose une couleur sur le pixel (px, py) de l'image, par-dessus (alpha a entre 0 et 1)."""
        if 0 <= px < self.w and 0 <= py < self.h and a > 0:
            a *= (c[3] / 255) if len(c) > 3 else 1.0
            i = py * self.w + px
            k = 1 - a
            self.R[i] = c[0] * a + self.R[i] * k
            self.G[i] = c[1] * a + self.G[i] * k
            self.B[i] = c[2] * a + self.B[i] * k
            self.A[i] = a + self.A[i] * k

    def lire(self, px, py):
        """Couleur (r, g, b, a) du pixel, alpha entre 0 et 1, couleur non prémultipliée."""
        i = py * self.w + px
        a = self.A[i]
        if a <= 0:
            return 0.0, 0.0, 0.0, 0.0
        return self.R[i] / a, self.G[i] / a, self.B[i] / a, a

    def peindre(self, f, boite=None):
        """Applique un « shader » : f(x, y) en coordonnées logiques renvoie None ou (r, g, b, a),
        a entre 0 et 1, posé par-dessus le pixel."""
        x0, y0, x1, y1 = self._pixels(boite)
        inv = 1.0 / self.s
        for py in range(y0, y1):
            y = (py + 0.5) * inv
            for px in range(x0, x1):
                c = f((px + 0.5) * inv, y)
                if c is not None and c[3] > 0:
                    self.pixel(px, py, c[:3], c[3])

    def _pixels(self, boite):
        if boite is None:
            return 0, 0, self.w, self.h
        x0, y0, x1, y1 = boite
        s = self.s
        return (max(0, int(math.floor(x0 * s)) - 1), max(0, int(math.floor(y0 * s)) - 1),
                min(self.w, int(math.ceil(x1 * s)) + 2), min(self.h, int(math.ceil(y1 * s)) + 2))

    # -- remplissage d'une forme ----------------------------------------------
    def forme(self, sdf, boite, couleur, relief=None, biseau=None, degrade=None, creux=None, alpha=1.0):
        """Remplit la forme décrite par sa distance signée, avec anticrénelage et modelé.

        couleur : tuple, ou fonction (x, y) -> tuple pour un remplissage variable.
        relief : force du biseau éclairé (0 = à plat) ; biseau : largeur du biseau (unités logiques) ;
        degrade : dégradé de volume sur toute la forme ; creux : relief inversé (gravure).
        """
        s = self.s
        relief = self.relief if relief is None else relief
        biseau = self.biseau if biseau is None else biseau
        degrade = self.degrade if degrade is None else degrade
        variable = callable(couleur)
        if creux is None:
            creux = (not variable) and tuple(couleur[:3]) in self.creux
        sens = -1.0 if creux else 1.0
        bx0, by0, bx1, by1 = boite
        cx, cy = (bx0 + bx1) / 2, (by0 + by1) / 2
        rayon = max(bx1 - bx0, by1 - by0, 1e-6) / 2
        lx, ly = LUMIERE
        x0, y0, x1, y1 = self._pixels(boite)
        inv = 1.0 / s
        W, R, G, B, A = self.w, self.R, self.G, self.B, self.A
        if not variable:
            cr, cg, cb = couleur[0], couleur[1], couleur[2]
            ca = (couleur[3] / 255 if len(couleur) > 3 else 1.0) * alpha
        for py in range(y0, y1):
            y = (py + 0.5) * inv
            row = py * W
            for px in range(x0, x1):
                x = (px + 0.5) * inv
                d, gx, gy = sdf(x, y)
                cov = 0.5 - d * s
                if cov <= 0:
                    continue
                if cov > 1:
                    cov = 1.0
                if variable:
                    col = couleur(x, y)
                    if col is None:
                        continue
                    cr, cg, cb = col[0], col[1], col[2]
                    ca = (col[3] / 255 if len(col) > 3 else 1.0) * alpha
                h = 0.0
                if relief:
                    t = -d / biseau if d < 0 else 0.0
                    if t < 1:
                        e = (1 - t) * (1 - t)
                        face = gx * lx + gy * ly
                        h = relief * sens * e * face * 0.7
                        # Reflet : une fine ligne brillante un peu en retrait des bords tournés vers la lumière
                        if face > 0 and sens > 0 and 0.1 < t < 0.7:
                            b = 1 - abs(t - 0.35) / 0.3
                            if b > 0:
                                h += relief * 0.45 * b * face * face * face
                if degrade:
                    h += degrade * sens * 0.16 * ((x - cx) * lx + (y - cy) * ly) / rayon
                if h > 0:
                    if h > 0.7:
                        h = 0.7
                    r, g, b = cr + (255 - cr) * h * 0.85, cg + (250 - cg) * h * 0.85, cb + (235 - cb) * h * 0.85
                elif h < 0:
                    if h < -0.7:
                        h = -0.7
                    r, g, b = cr * (1 + h), cg * (1 + h), cb * (1 + h)
                else:
                    r, g, b = cr, cg, cb
                a = ca * cov
                i = row + px
                k = 1 - a
                R[i] = r * a + R[i] * k
                G[i] = g * a + G[i] * k
                B[i] = b * a + B[i] * k
                A[i] = a + A[i] * k

    # -- primitives (mêmes noms et arguments que make_assets.Img) --------------
    def disc(self, cx, cy, r, c, soft=None, **k):
        self.forme(sdf_disque(cx, cy, r), (cx - r, cy - r, cx + r, cy + r), c, **k)

    def ring(self, cx, cy, r, width, c, **k):
        e = r + width / 2
        self.forme(sdf_anneau(cx, cy, r, width), (cx - e, cy - e, cx + e, cy + e), c, **k)

    def rect(self, x0, y0, x1, y1, c, **k):
        self.forme(sdf_rect(x0, y0, x1, y1), (min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)), c, **k)

    def line(self, x0, y0, x1, y1, c, w=1.5, **k):
        r = w / 2
        self.forme(sdf_trait(x0, y0, x1, y1, r),
                   (min(x0, x1) - r, min(y0, y1) - r, max(x0, x1) + r, max(y0, y1) + r), c, **k)

    def poly(self, pts, c, **k):
        xs, ys = [p[0] for p in pts], [p[1] for p in pts]
        self.forme(sdf_poly(pts), (min(xs), min(ys), max(xs), max(ys)), c, **k)

    def put(self, x, y, c, a=1.0, **k):
        """Une case d'une unité logique (l'ancien pixel)."""
        self.rect(x, y, x + 1, y + 1, c, alpha=a, **k)

    def ellipse(self, cx, cy, rx, ry, c, **k):
        """Ellipse (approchée par un polygone fin, assez précis pour l'anticrénelage)."""
        n = 48
        self.poly([(cx + rx * math.cos(2 * math.pi * i / n), cy + ry * math.sin(2 * math.pi * i / n)) for i in range(n)], c, **k)

    def arc(self, cx, cy, r, a0, a1, c, w=1.0, **k):
        """Arc de cercle épais, de l'angle a0 à a1 (radians), en petits traits."""
        n = max(6, int(abs(a1 - a0) * r * 2))
        pts = [(cx + r * math.cos(a0 + (a1 - a0) * i / n), cy + r * math.sin(a0 + (a1 - a0) * i / n)) for i in range(n + 1)]
        self.chemin(pts, c, w, **k)

    def chemin(self, pts, c, w=1.0, **k):
        """Ligne brisée d'épaisseur w (une seule forme : pas de surépaisseur aux jonctions)."""
        r = w / 2
        traits = [sdf_trait(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], r) for i in range(len(pts) - 1)]

        def f(x, y):
            best = None
            for t in traits:
                v = t(x, y)
                if best is None or v[0] < best[0]:
                    best = v
            return best
        xs, ys = [p[0] for p in pts], [p[1] for p in pts]
        self.forme(f, (min(xs) - r, min(ys) - r, max(xs) + r, max(ys) + r), c, **k)

    def composee(self, forme, c, **k):
        """Remplit une forme composée (voir union et difference)."""
        self.forme(forme[0], forme[1], c, **k)

    def halo(self, cx, cy, r, c, force=0.6):
        """Lueur ronde et douce (sans bord), de la couleur c, la plus forte au centre."""
        def couleur(x, y):
            t = 1 - math.hypot(x - cx, y - cy) / r
            if t <= 0:
                return None
            return c[0], c[1], c[2], 255 * force * t * t
        self.forme(sdf_disque(cx, cy, r), (cx - r, cy - r, cx + r, cy + r), couleur, relief=0, degrade=0)

    def etincelle(self, cx, cy, r, c, **k):
        """Petite étoile à quatre branches (scintillement)."""
        e = r * 0.28
        self.poly([(cx, cy - r), (cx + e, cy - e), (cx + r, cy), (cx + e, cy + e),
                   (cx, cy + r), (cx - e, cy + e), (cx - r, cy), (cx - e, cy - e)], c, **k)

    def cut(self, cx, cy, r):
        """Efface un disque (rend les pixels transparents, bord anticrénelé)."""
        self.effacer(sdf_disque(cx, cy, r), (cx - r, cy - r, cx + r, cy + r))

    def effacer(self, sdf, boite):
        x0, y0, x1, y1 = self._pixels(boite)
        s, inv, W = self.s, 1.0 / self.s, self.w
        for py in range(y0, y1):
            y = (py + 0.5) * inv
            for px in range(x0, x1):
                cov = 0.5 - sdf((px + 0.5) * inv, y)[0] * s
                if cov <= 0:
                    continue
                k = 0.0 if cov >= 1 else 1 - cov
                i = py * W + px
                self.R[i] *= k
                self.G[i] *= k
                self.B[i] *= k
                self.A[i] *= k

    # -- effets d'ensemble -----------------------------------------------------
    def _flou(self, v, rayon, passes=3):
        """Flou en boîte séparable (passes successives : proche d'un flou gaussien)."""
        W, H = self.w, self.h
        r = max(1, int(round(rayon)))
        for _ in range(passes):
            out = [0.0] * (W * H)
            for y in range(H):
                row = y * W
                acc = 0.0
                for x in range(-r, r + 1):
                    acc += v[row + min(W - 1, max(0, x))]
                for x in range(W):
                    out[row + x] = acc / (2 * r + 1)
                    acc += v[row + min(W - 1, x + r + 1)] - v[row + max(0, x - r)]
            v = out
            out = [0.0] * (W * H)
            for x in range(W):
                acc = 0.0
                for y in range(-r, r + 1):
                    acc += v[min(H - 1, max(0, y)) * W + x]
                for y in range(H):
                    out[y * W + x] = acc / (2 * r + 1)
                    acc += v[min(H - 1, y + r + 1) * W + x] - v[max(0, y - r) * W + x]
            v = out
        return v

    def _dessous(self, couche, couleur):
        """Place une couche d'alpha (couleur unie) sous l'image actuelle."""
        cr, cg, cb = couleur[:3]
        for i in range(self.w * self.h):
            a2 = couche[i]
            if a2 <= 0:
                continue
            k = 1 - self.A[i]
            self.R[i] += cr * a2 * k
            self.G[i] += cg * a2 * k
            self.B[i] += cb * a2 * k
            self.A[i] += a2 * k

    def ombre(self, dx=0.35, dy=0.6, flou=1.4, opacite=0.4, couleur=(27, 18, 20)):
        """Ombre portée floue sous tout le dessin (décalage et flou en unités logiques)."""
        W, H = self.w, self.h
        ox, oy = int(round(dx * self.s)), int(round(dy * self.s))
        dec = [0.0] * (W * H)
        for y in range(H):
            sy = y - oy
            if 0 <= sy < H:
                for x in range(W):
                    sx = x - ox
                    if 0 <= sx < W:
                        dec[y * W + x] = self.A[sy * W + sx]
        dec = self._flou(dec, flou * self.s / 2)
        self._dessous([v * opacite for v in dec], couleur)

    def lueur(self, rayon=1.0, opacite=0.6, couleur=(255, 230, 150)):
        """Halo lumineux flou autour du dessin (sous le dessin)."""
        halo = self._flou(self.A[:], rayon * self.s / 2)
        self._dessous([min(1.0, v * opacite * 1.6) for v in halo], couleur)

    def contour(self, epaisseur=0.5, couleur=(27, 18, 20), opacite=1.0):
        """Contour anticrénelé autour de la silhouette (épaisseur en unités logiques)."""
        W, H = self.w, self.h
        rp = epaisseur * self.s
        n = int(math.ceil(rp)) + 1
        voisins = []
        for dy in range(-n, n + 1):
            for dx in range(-n, n + 1):
                f = min(1.0, rp + 0.5 - math.hypot(dx, dy))
                if f > 0 and (dx or dy):
                    voisins.append((dx, dy, f))
        A = self.A
        couche = [0.0] * (W * H)
        for y in range(H):
            for x in range(W):
                m = 0.0
                for dx, dy, f in voisins:
                    sx, sy = x + dx, y + dy
                    if 0 <= sx < W and 0 <= sy < H:
                        v = A[sy * W + sx] * f
                        if v > m:
                            m = v
                            if m >= 1:
                                break
                couche[y * W + x] = m * opacite
        self._dessous(couche, couleur)

    def grain(self, graine=1, force=0.08, frequence=0.5, octaves=3, boite=None):
        """Grain de matière : module la luminosité des pixels déjà peints (bruit lissé, fréquence par unité)."""
        bruit = Bruit(graine)
        x0, y0, x1, y1 = self._pixels(boite)
        inv = 1.0 / self.s
        for py in range(y0, y1):
            for px in range(x0, x1):
                i = py * self.w + px
                if self.A[i] <= 0:
                    continue
                f = 1 + force * bruit.fbm((px + 0.5) * inv * frequence, (py + 0.5) * inv * frequence, octaves)
                self.R[i] = min(self.A[i] * 255, self.R[i] * f)
                self.G[i] = min(self.A[i] * 255, self.G[i] * f)
                self.B[i] = min(self.A[i] * 255, self.B[i] * f)

    def coller(self, autre, ox, oy, masque=None):
        """Pose une autre toile par-dessus, coin haut gauche au pixel (ox, oy) ;
        masque(px, py) en pixels de cette toile renvoie un facteur d'opacité entre 0 et 1."""
        for y in range(autre.h):
            ty = oy + y
            if not 0 <= ty < self.h:
                continue
            for x in range(autre.w):
                tx = ox + x
                if not 0 <= tx < self.w:
                    continue
                j = y * autre.w + x
                a = autre.A[j]
                if a <= 0:
                    continue
                m = 1.0 if masque is None else masque(tx, ty)
                if m <= 0:
                    continue
                i = ty * self.w + tx
                k = 1 - a * m
                self.R[i] = autre.R[j] * m + self.R[i] * k
                self.G[i] = autre.G[j] * m + self.G[i] * k
                self.B[i] = autre.B[j] * m + self.B[i] * k
                self.A[i] = a * m + self.A[i] * k

    # -- écriture --------------------------------------------------------------
    def png(self):
        rows = []
        for y in range(self.h):
            row = bytearray(b"\x00")
            for x in range(self.w):
                i = y * self.w + x
                a = self.A[i]
                if a <= 0.0005:
                    row += b"\x00\x00\x00\x00"
                    continue
                row += bytes((min(255, int(self.R[i] / a + 0.5)), min(255, int(self.G[i] / a + 0.5)),
                              min(255, int(self.B[i] / a + 0.5)), min(255, int(a * 255 + 0.5))))
            rows.append(bytes(row))

        def chunk(t, d):
            return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

        return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b""))

    def enregistrer(self, chemin, lisse=False):
        """Écrit le PNG. lisse=True ajoute le fichier .mcmeta qui demande à Minecraft un filtrage
        linéaire (pour les textures d'interface dessinées plus petites que leur taille réelle)."""
        chemin = Path(chemin)
        chemin.parent.mkdir(parents=True, exist_ok=True)
        chemin.write_bytes(self.png())
        meta = chemin.with_name(chemin.name + ".mcmeta")
        if lisse:
            meta.write_bytes((json.dumps({"texture": {"blur": True, "clamp": True}}, indent=1) + "\n").encode("utf-8"))
        elif meta.exists():
            meta.unlink()
