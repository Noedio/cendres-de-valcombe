"""Dessine les objets d'outils de l'éditeur de carte et leurs modèles d'objet.

Lancer depuis le dossier minecraft :  python tools/make_tools.py
Écrit textures/item/outils/<nom>.png (128 px : 16 unités × 8, moteur tools/dessin.py),
models/item/outil_<nom>.json et items/outil_<nom>.json.
"""
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dessin as D  # noqa: E402  (moteur haute résolution)
import make_assets as A  # noqa: E402  (même palette)

ROOT = Path(__file__).resolve().parent.parent / "src/client/resources/assets/valcombe"
TEX = ROOT / "textures/item"
TAILLE = 128  # 16 unités × 8

DARK = (40, 28, 30)
STONE = (150, 146, 140)
STONE_DARK = (98, 94, 90)
WOOD = (122, 84, 50)
WOOD_DARK = (84, 56, 32)
GREEN = (76, 132, 72)
RED = (190, 52, 46)
BLUE = (70, 120, 190)
GLASS = (150, 200, 230)


def trone(i):
    # Haut dossier pointu à fleuron d'or, velours capitonné, accoudoirs dorés, pieds tournés
    i.rect(3.4, 12, 5.2, 15.8, A.BRONZE_DARK)
    i.rect(10.8, 12, 12.6, 15.8, A.BRONZE_DARK)
    i.composee(D.union(D.rect(4, 2.4, 12, 11), D.poly([(4, 2.6), (8, 0.4), (12, 2.6)])), A.WINE)
    i.composee(D.union(D.rect(5, 3.4, 11, 10), D.poly([(5, 3.5), (8, 1.6), (11, 3.5)])), A.BLOOD)
    for x, y in ((6.5, 5), (9.5, 5), (8, 7), (6.5, 8.6), (9.5, 8.6)):  # boutons du capiton
        i.disc(x, y, 0.42, A.GOLD)
    i.disc(8, 0.7, 0.7, A.GOLD)                                           # fleuron
    i.rect(2, 9.2, 14, 12, A.GOLD)                                        # assise et accoudoirs
    i.rect(4, 10, 12, 11.2, A.BLOOD, relief=0.6)                          # coussin
    i.disc(2.4, 9.6, 0.8, A.GOLD)
    i.disc(13.6, 9.6, 0.8, A.GOLD)


def maison(i):
    # Toit de tuiles, colombages, porte, fenêtre éclairée, cheminée
    i.rect(10.6, 1.6, 12.4, 6, STONE_DARK)
    i.rect(3, 7.6, 13, 15, A.PARCH_DARK)
    for x in (3.6, 12.4):
        i.line(x, 8, x, 15, WOOD_DARK, 0.7, relief=0.4)
    i.line(3.4, 11, 12.6, 11, WOOD_DARK, 0.6, relief=0.4)
    i.line(4, 8.4, 6.4, 10.8, WOOD_DARK, 0.5, relief=0.4)
    i.poly([(0.6, 8.4), (8, 1), (15.4, 8.4), (13.6, 8.4), (8, 2.8), (2.4, 8.4)], A.BLOOD)
    i.poly([(2.4, 8.4), (8, 2.8), (13.6, 8.4)], A.WINE)
    for k in range(1, 4):  # rangs de tuiles
        y = 2.8 + 1.4 * k
        dx = 1.4 * k
        i.line(8 - dx + 0.3, y, 8 + dx - 0.3, y, A.shade(A.WINE, 0.7), 0.3, relief=0)
    i.composee(D.union(D.rect(7, 10.4, 10, 15), D.disque(8.5, 10.6, 1.5)), WOOD_DARK)
    i.line(8.5, 9.6, 8.5, 15, A.shade(WOOD_DARK, 0.7), 0.25, relief=0)
    i.disc(9.4, 12.8, 0.3, A.GOLD)
    i.rect(4, 9, 6.2, 11.2, (255, 214, 120))                              # fenêtre éclairée
    i.line(5.1, 9, 5.1, 11.2, WOOD_DARK, 0.3, relief=0)
    i.line(4, 10.1, 6.2, 10.1, WOOD_DARK, 0.3, relief=0)
    i.rect(0.8, 15, 15.2, 16, DARK)


def centre(i):
    # Rose des vents : anneau gradué, étoile à huit branches, nord rouge
    i.ring(8, 8, 6.5, 1.2, A.BRONZE)
    for k in range(4):
        a = 0.785 + k * 1.5708
        c, s = math.cos(a), math.sin(a)
        i.poly([(8 + 5.2 * c, 8 + 5.2 * s), (8 + 1.1 * s, 8 - 1.1 * c), (8 - 1.1 * s, 8 + 1.1 * c)], A.BRONZE_DARK)
    i.poly([(8, 0), (9.5, 8), (8, 16), (6.5, 8)], A.GOLD)
    i.poly([(0, 8), (8, 6.5), (16, 8), (8, 9.5)], A.GOLD)
    i.poly([(8, 0), (9.5, 8), (6.5, 8)], RED)
    i.disc(8, 8, 1.4, DARK)
    i.disc(7.7, 7.7, 0.45, A.WHITE + (200,))


def salle(i):
    # Porte voûtée de pierre appareillée, battant de planches cloutées, anneau d'or
    i.composee(D.union(D.rect(2, 6, 14, 16), D.disque(8, 6, 6)), STONE)
    for y in (9, 12.5):
        i.line(2.2, y, 4.8, y, STONE_DARK, 0.25, relief=0)
        i.line(11.2, y, 13.8, y, STONE_DARK, 0.25, relief=0)
    for k in range(5):  # claveaux de l'arc
        a = math.pi * (1.1 + 0.2 * k)
        c, s = math.cos(a), math.sin(a)
        i.line(8 + 3.3 * c, 7 + 3.3 * s, 8 + 5.8 * c, 6.2 + 5.8 * s, STONE_DARK, 0.25, relief=0)
    i.composee(D.union(D.rect(5, 7, 11, 16), D.disque(8, 7, 3)), WOOD)
    for x in (6.5, 8, 9.5):
        i.line(x, 4.6 if x == 8 else 5.0, x, 16, WOOD_DARK, 0.3, relief=0)
    for y in (9, 13.5):
        i.line(5.2, y, 10.8, y, A.shade(WOOD_DARK, 0.8), 0.5, relief=0.5)
        for x in (5.8, 10.2):
            i.disc(x, y, 0.25, STONE_DARK)
    i.ring(9.9, 11.2, 0.75, 0.35, A.GOLD)


def gomme(i):
    # Gomme : caoutchouc rose, fourreau de papier bleu, miettes
    # (un peu remontée par rapport à l'ancienne image, dont la pointe sortait de la case)
    i.poly([(1.2, 10), (8.8, 2.4), (14.8, 8.4), (7.2, 15.6)], (232, 132, 150))
    i.poly([(4.5, 6.7), (7.2, 4.0), (13.0, 9.8), (10.3, 12.5)], (90, 140, 200))
    i.line(6.1, 5.4, 11.6, 10.9, (220, 230, 245), 0.3, relief=0)
    i.poly([(1.2, 10), (4.5, 6.7), (10.3, 12.5), (7.2, 15.6)], (245, 240, 230))
    for x, y, r in ((1.4, 13.6, 0.45), (2.8, 14.8, 0.35), (0.7, 12.3, 0.3)):
        i.disc(x, y, r, (232, 160, 170))


def baguette(i):
    # Baguette de construction (la baguette de WorldEdit dans les ateliers) : manche d'ébène cerclé d'or, et au bout
    # un cube de sélection en fil d'or, ses deux coins marqués (rouge le premier, bleu le second), un cœur d'ambre
    # qui luit et deux étincelles.
    cube = [(9.2, 3.2), (12.6, 1.6), (15.2, 3.0), (11.8, 4.6)]           # face du dessus, en perspective
    bas = [(x, y + 3.6) for x, y in cube]
    i.halo(12.2, 5.0, 4.6, (255, 196, 92), force=0.55)
    i.poly([cube[0], cube[3], bas[3], bas[0]], A.shade(A.GOLD, 0.35) + (110,), relief=0)   # faces à peine teintées
    i.poly([cube[3], cube[2], bas[2], bas[3]], A.shade(A.GOLD, 0.25) + (90,), relief=0)
    i.poly(cube, A.shade(A.GOLD, 0.5) + (120,), relief=0)
    i.disc(12.2, 4.8, 1.25, (255, 214, 120), relief=0.4)                  # cœur d'ambre
    i.disc(11.9, 4.5, 0.45, A.WHITE + (220,), relief=0)
    for a, b in ((0, 1), (1, 2), (2, 3), (3, 0)):                         # arêtes en fil d'or
        i.line(cube[a][0], cube[a][1], cube[b][0], cube[b][1], A.GOLD, 0.45, relief=0.3)
        i.line(cube[a][0], cube[a][1], bas[a][0], bas[a][1], A.GOLD, 0.45, relief=0.3)
    for a, b in ((0, 3), (3, 2)):
        i.line(bas[a][0], bas[a][1], bas[b][0], bas[b][1], A.GOLD, 0.45, relief=0.3)
    i.line(1.6, 14.4, 9.6, 6.4, (38, 26, 28), 2.0)                        # manche d'ébène
    i.line(2.1, 13.5, 9.1, 6.5, A.shade((38, 26, 28), 2.4), 0.45, relief=0)
    for t in (0.18, 0.62, 0.92):                                          # bagues d'or
        x, y = 1.6 + 8 * t, 14.4 - 8 * t
        i.line(x - 0.75, y - 0.75, x + 0.75, y + 0.75, A.GOLD, 0.85, relief=0.6)
    i.disc(1.4, 14.6, 0.95, A.BRONZE, relief=0.6)                         # pommeau
    i.disc(bas[0][0], bas[0][1], 0.8, RED, relief=0.5)                    # coin 1, au bout du manche
    i.disc(cube[2][0], cube[2][1], 0.8, BLUE, relief=0.5)                 # coin 2
    i.etincelle(4.6, 3.6, 1.3, (255, 236, 170), relief=0)
    i.etincelle(14.6, 10.4, 0.9, (255, 236, 170), relief=0)


def structure(i):
    # Trois blocs empilés en perspective : brique, pierre, herbe
    def block(x, y, top, side, motif):
        i.poly([(x, y + 2), (x + 4, y), (x + 8, y + 2), (x + 4, y + 4)], top)
        i.poly([(x, y + 2), (x + 4, y + 4), (x + 4, y + 9), (x, y + 7)], side, relief=0.6)
        i.poly([(x + 4, y + 4), (x + 8, y + 2), (x + 8, y + 7), (x + 4, y + 9)], A.shade(side, 0.75), relief=0.6)
        motif(x, y)

    def briques(x, y):
        for k in (1, 2, 3, 4):
            i.line(x + 0.2, y + 2 + k * 1.2, x + 3.8, y + 4 + k * 1.2, A.shade((160, 84, 60), 0.6), 0.22, relief=0)
            i.line(x + 4.2, y + 4 + k * 1.2, x + 7.8, y + 2 + k * 1.2, A.shade((160, 84, 60), 0.45), 0.22, relief=0)

    def pierre(x, y):
        i.line(x + 0.2, y + 4.6, x + 3.8, y + 6.6, A.shade(STONE_DARK, 0.7), 0.22, relief=0)
        i.line(x + 4.2, y + 6.6, x + 7.8, y + 4.6, A.shade(STONE_DARK, 0.55), 0.22, relief=0)
        i.line(x + 2, y + 2.4, x + 5.4, y + 1.4, A.shade(STONE, 0.8), 0.2, relief=0)

    def herbe(x, y):
        for k in range(5):
            xx = x + 0.6 + k * 0.7
            i.line(xx, y + 2.4 + k * 0.35, xx, y + 3.4 + k * 0.35, (60, 104, 58), 0.3, relief=0)
        i.line(x + 0.2, y + 3.2, x + 4, y + 5.1, (60, 104, 58), 0.6, relief=0)
        i.line(x + 4, y + 5.1, x + 7.8, y + 3.2, (48, 84, 46), 0.6, relief=0)

    block(0, 6, (200, 120, 90), (160, 84, 60), briques)
    block(8, 6, STONE, STONE_DARK, pierre)
    block(4, 1, (110, 170, 90), (120, 90, 64), herbe)


def main_(i):
    # Main gantée qui saisit : doigts séparés, pouce, manchette lie-de-vin bordée d'or
    peau = A.PARCH_DARK
    doigts = [D.rect(x + 0.1, 2 + (k % 2), x + 1.9, 7) for k, x in enumerate((5, 7, 9, 11))]
    i.composee(D.union(D.rect(5, 6, 12, 13), *doigts, D.poly([(5, 8), (2, 6), (1, 7), (5, 11.5)])), peau)
    for k, x in enumerate((5, 7, 9, 11)):
        i.disc(x + 1, 2.8 + (k % 2), 0.45, A.PARCH, relief=0.4)          # bout des doigts
        if k:
            i.line(x, 3 + (k % 2), x, 7.5, A.shade(peau, 0.7), 0.25, relief=0)
    i.line(6, 9.4, 11, 9.4, A.shade(peau, 0.7), 0.5, relief=0)
    i.rect(4.8, 13, 12.2, 16, A.WINE)
    i.line(4.8, 13.3, 12.2, 13.3, A.GOLD, 0.5)


TOOLS = {"trone": trone, "maison": maison, "centre": centre, "salle": salle, "gomme": gomme,
         "baguette": baguette, "structure": structure, "main": main_}


def dessiner(name):
    """Rend l'outil en 128 px : formes modelées, puis un fin contour sombre (lisible dans la barre d'objets)."""
    img = D.Toile(16, 16, TAILLE / 16, relief=1.0, biseau=0.7, degrade=0.8)
    TOOLS[name](img)
    img.contour(0.3, DARK, 0.9)
    return img


def main():
    for name in TOOLS:
        dessiner(name).enregistrer(TEX / f"outils/{name}.png")
        model = {"parent": "minecraft:item/handheld", "textures": {"layer0": f"valcombe:item/outils/{name}"}}
        (ROOT / "models/item").mkdir(parents=True, exist_ok=True)
        (ROOT / f"models/item/outil_{name}.json").write_text(json.dumps(model, indent=1) + "\n", encoding="utf-8")
        item = {"model": {"type": "minecraft:model", "model": f"valcombe:item/outil_{name}"}}
        (ROOT / f"items/outil_{name}.json").write_text(json.dumps(item, indent=1) + "\n", encoding="utf-8")
    print(f"{len(TOOLS)} outils dessinés dans {TEX / 'outils'}")


if __name__ == "__main__":
    main()
