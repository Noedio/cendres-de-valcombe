"""Dessine l'illustration de fond du launcher : Valcombe la nuit, son beffroi, la lune et les braises.

Lancer depuis le dossier launcher :
    python tools/make_hero.py

Produit Assets/hero.png (1600 x 900). L'image est originale et entièrement dessinée par ce script :
on peut la régénérer, ou changer la graine pour un autre village.
"""
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

W, H = 1600, 900
S = 2  # dessiné en double taille puis réduit : contours lisses
OUT = Path(__file__).resolve().parent.parent / "Assets" / "hero.png"
rnd = random.Random(1412)


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def gradient(size, stops):
    """Dégradé vertical ; stops : [(position 0..1, couleur)]."""
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        t = y / (h - 1)
        for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
            if p0 <= t <= p1:
                c = lerp(c0, c1, (t - p0) / max(1e-6, p1 - p0))
                break
        else:
            c = stops[-1][1]
        for x in range(w):
            px[x, y] = c
    return img


def glow(size, center, radius, color, strength):
    """Halo doux (lune, fenêtres, horizon)."""
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    cx, cy = center
    d.ellipse([cx - radius, cy - radius, cx + radius, cy + radius], fill=color + (strength,))
    return layer.filter(ImageFilter.GaussianBlur(radius * 0.55))


def house(d, x, base, w, h, roof, color, windows, lit):
    """Une maison en silhouette : murs, toit pentu, cheminée, fenêtres éclairées."""
    d.rectangle([x, base - h, x + w, base], fill=color)
    d.polygon([(x - w * 0.08, base - h), (x + w / 2, base - h - roof), (x + w * 1.08, base - h)], fill=color)
    if rnd.random() < 0.6:
        cx = x + w * rnd.uniform(0.6, 0.8)
        d.rectangle([cx, base - h - roof * 0.75, cx + w * 0.1, base - h - roof * 0.1], fill=color)
    for wx, wy in windows:
        if rnd.random() < lit:
            ww, wh = w * 0.13, h * 0.2
            px, py = x + w * wx, base - h * wy
            lights.append((px + ww / 2, py + wh / 2, max(ww, wh)))
            d.rectangle([px, py, px + ww, py + wh], fill=(232, 170, 78))
            d.line([px + ww / 2, py, px + ww / 2, py + wh], fill=color, width=max(1, S))


def belfry(d, x, base, w, h, color):
    """Le beffroi : fût, étage de la cloche, flèche, cadran."""
    d.rectangle([x, base - h, x + w, base], fill=color)
    d.rectangle([x - w * 0.12, base - h - w * 0.5, x + w * 1.12, base - h], fill=color)
    # Baies de la cloche
    for k in range(2):
        bx = x + w * (0.12 + 0.5 * k)
        d.rectangle([bx, base - h - w * 0.4, bx + w * 0.26, base - h - w * 0.08], fill=(26, 14, 22))
        d.pieslice([bx, base - h - w * 0.53, bx + w * 0.26, base - h - w * 0.27], 180, 360, fill=(26, 14, 22))
    # Flèche et girouette
    top = base - h - w * 0.5
    d.polygon([(x - w * 0.16, top), (x + w / 2, top - w * 1.9), (x + w * 1.16, top)], fill=color)
    d.line([x + w / 2, top - w * 1.9, x + w / 2, top - w * 2.25], fill=color, width=max(2, S * 2))
    # Cadran doré
    cx, cy, r = x + w / 2, base - h * 0.78, w * 0.3
    lights.append((cx, cy, r * 2.2))
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(217, 180, 90))
    d.ellipse([cx - r * 0.86, cy - r * 0.86, cx + r * 0.86, cy + r * 0.86], fill=(245, 226, 170))
    for k in range(12):
        a = math.radians(k * 30)
        d.line([cx + math.sin(a) * r * 0.7, cy - math.cos(a) * r * 0.7, cx + math.sin(a) * r * 0.82, cy - math.cos(a) * r * 0.82],
               fill=(60, 30, 26), width=max(1, S))
    # Aiguilles : minuit moins cinq
    d.line([cx, cy, cx + math.sin(math.radians(-30)) * r * 0.7, cy - math.cos(math.radians(-30)) * r * 0.7], fill=(60, 20, 26), width=max(2, S * 2))
    d.line([cx, cy, cx, cy - r * 0.48], fill=(60, 20, 26), width=max(2, S * 3))
    # Porte
    d.rectangle([x + w * 0.36, base - h * 0.16, x + w * 0.64, base], fill=(26, 14, 22))


lights = []
size = (W * S, H * S)

# Ciel : nuit profonde en haut, lueur de braise à l'horizon
img = gradient(size, [(0, (9, 7, 18)), (0.34, (24, 13, 34)), (0.58, (62, 22, 40)), (0.70, (112, 44, 46)), (1, (20, 10, 14))]).convert("RGBA")

# Étoiles
d = ImageDraw.Draw(img)
for _ in range(360):
    x, y = rnd.uniform(0, size[0]), rnd.uniform(0, size[1] * 0.5)
    r = rnd.choice([1, 1, 1, 2, 2, 3]) * S * 0.5
    a = int(rnd.uniform(70, 220) * (1 - y / (size[1] * 0.55)))
    d.ellipse([x - r, y - r, x + r, y + r], fill=(232, 220, 200, max(0, a)))

# Lune et son halo
moon = (int(size[0] * 0.80), int(size[1] * 0.20))
img.alpha_composite(glow(size, moon, 300 * S, (240, 214, 170), 70))
img.alpha_composite(glow(size, moon, 120 * S, (255, 236, 200), 150))
d = ImageDraw.Draw(img)
mr = 54 * S
d.ellipse([moon[0] - mr, moon[1] - mr, moon[0] + mr, moon[1] + mr], fill=(246, 232, 200))
for cx, cy, r in [(-0.3, -0.2, 0.2), (0.25, 0.1, 0.14), (-0.05, 0.4, 0.11), (0.35, -0.35, 0.09)]:
    d.ellipse([moon[0] + (cx - r) * mr, moon[1] + (cy - r) * mr, moon[0] + (cx + r) * mr, moon[1] + (cy + r) * mr], fill=(226, 206, 170))

# Nuages effilochés devant la lune
clouds = Image.new("RGBA", size, (0, 0, 0, 0))
cd = ImageDraw.Draw(clouds)
for _ in range(26):
    x, y = rnd.uniform(0, size[0]), rnd.uniform(size[1] * 0.08, size[1] * 0.42)
    w, h = rnd.uniform(220, 620) * S, rnd.uniform(14, 34) * S
    cd.ellipse([x - w / 2, y - h / 2, x + w / 2, y + h / 2], fill=(40, 22, 44, 120))
img.alpha_composite(clouds.filter(ImageFilter.GaussianBlur(16 * S)))

# Collines lointaines
d = ImageDraw.Draw(img)
horizon = size[1] * 0.66
for depth, color in [(0, (52, 22, 40)), (1, (36, 16, 30))]:
    pts = [(0, size[1])]
    phase = rnd.uniform(0, 6)
    for x in range(0, size[0] + 40, 40):
        y = horizon + depth * 26 * S - (math.sin(x / (310.0 * S) + phase) * 34 + math.sin(x / (97.0 * S) + phase * 2) * 12) * S
        pts.append((x, y))
    pts.append((size[0], size[1]))
    d.polygon(pts, fill=color)

# Lueur du village derrière les toits
img.alpha_composite(glow(size, (int(size[0] * 0.42), int(horizon + 60 * S)), 520 * S, (190, 80, 50), 60))

# Rangée du fond : petites maisons serrées
d = ImageDraw.Draw(img)
x = -40 * S
while x < size[0]:
    w = rnd.uniform(70, 120) * S
    house(d, x, horizon + 78 * S, w, rnd.uniform(46, 84) * S, rnd.uniform(34, 60) * S, (28, 13, 24),
          [(0.2, 0.62), (0.62, 0.62)], 0.42)
    x += w * rnd.uniform(0.82, 1.0)

# Le beffroi, un peu à gauche du centre
belfry(d, size[0] * 0.385, horizon + 150 * S, 78 * S, 330 * S, (16, 8, 14))

# Rangée de devant : grandes maisons à colombages
x = -60 * S
while x < size[0]:
    w = rnd.uniform(120, 190) * S
    if abs(x + w / 2 - size[0] * 0.41) < 120 * S:
        x += 90 * S  # on laisse le pied du beffroi dégagé
        continue
    house(d, x, horizon + 170 * S, w, rnd.uniform(78, 128) * S, rnd.uniform(58, 96) * S, (14, 7, 12),
          [(0.14, 0.72), (0.44, 0.72), (0.74, 0.72), (0.3, 0.36), (0.6, 0.36)], 0.5)
    x += w * rnd.uniform(0.9, 1.1)

# Halos des fenêtres et du cadran
halo = Image.new("RGBA", size, (0, 0, 0, 0))
for lx, ly, lr in lights:
    halo.alpha_composite(glow(size, (int(lx), int(ly)), int(lr * 2.6), (240, 160, 70), 70))
img.alpha_composite(halo)

# Brume au pied des maisons, puis le sol qui se fond dans le noir (le texte se lit dessus)
mist = Image.new("RGBA", size, (0, 0, 0, 0))
md = ImageDraw.Draw(mist)
for _ in range(40):
    x, y = rnd.uniform(0, size[0]), rnd.uniform(horizon + 120 * S, horizon + 230 * S)
    w, h = rnd.uniform(300, 760) * S, rnd.uniform(26, 60) * S
    md.ellipse([x - w / 2, y - h / 2, x + w / 2, y + h / 2], fill=(150, 90, 96, 46))
img.alpha_composite(mist.filter(ImageFilter.GaussianBlur(26 * S)))
fade = Image.new("RGBA", size, (0, 0, 0, 0))
fd = ImageDraw.Draw(fade)
start = int(horizon + 150 * S)
for y in range(start, size[1]):
    t = (y - start) / (size[1] - start)
    fd.line([0, y, size[0], y], fill=(14, 8, 12, int(255 * min(1, t * 1.5))))
img.alpha_composite(fade)

# Quelques braises qui montent
d = ImageDraw.Draw(img)
embers = Image.new("RGBA", size, (0, 0, 0, 0))
ed = ImageDraw.Draw(embers)
for _ in range(90):
    x, y = rnd.uniform(0, size[0]), rnd.uniform(size[1] * 0.3, size[1] * 0.9)
    r = rnd.uniform(1.2, 3.2) * S
    ed.ellipse([x - r, y - r, x + r, y + r], fill=(255, rnd.randint(120, 190), 60, rnd.randint(90, 220)))
img.alpha_composite(embers.filter(ImageFilter.GaussianBlur(1.2 * S)))

OUT.parent.mkdir(exist_ok=True)
img.convert("RGB").resize((W, H), Image.LANCZOS).save(OUT, optimize=True)
print("Illustration :", OUT, f"({OUT.stat().st_size // 1024} Ko)")
