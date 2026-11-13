"""Dessine les objets d'outils de l'éditeur de carte (16 x 16) et leurs modèles d'objet.

Lancer depuis le dossier minecraft :  python tools/make_tools.py
Écrit textures/item/outils/<nom>.png, models/item/outil_<nom>.json et items/outil_<nom>.json.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_assets as A  # noqa: E402  (même toile, même palette)

ROOT = Path(__file__).resolve().parent.parent / "src/client/resources/assets/valcombe"
A.OUT = ROOT / "textures/item"

DARK = (40, 28, 30)
STONE = (150, 146, 140)
STONE_DARK = (98, 94, 90)
WOOD = (122, 84, 50)
WOOD_DARK = (84, 56, 32)
GREEN = (76, 132, 72)
RED = (190, 52, 46)
BLUE = (70, 120, 190)


def trone(i):
    # Dossier haut et pointu, accoudoirs, coussin rouge
    i.rect(4, 2, 12, 11, A.WINE)
    i.poly([(4, 2), (8, 0), (12, 2)], A.GOLD)
    i.rect(5, 3, 11, 10, A.BLOOD)
    i.rect(2, 9, 14, 12, A.GOLD)
    i.rect(3, 12, 5, 16, A.BRONZE_DARK)
    i.rect(11, 12, 13, 16, A.BRONZE_DARK)
    i.rect(4, 10, 12, 11, A.BLOOD)


def maison(i):
    i.poly([(1, 8), (8, 1), (15, 8)], A.WINE)
    i.rect(3, 8, 13, 15, A.PARCH_DARK)
    i.rect(7, 10, 10, 15, WOOD_DARK)
    i.rect(4, 9, 6, 11, BLUE)
    i.rect(1, 15, 15, 16, DARK)


def centre(i):
    # Rose des vents
    i.ring(8, 8, 6.5, 1.2, A.BRONZE)
    i.poly([(8, 0), (9.5, 8), (8, 16), (6.5, 8)], A.GOLD)
    i.poly([(0, 8), (8, 6.5), (16, 8), (8, 9.5)], A.BRONZE_DARK)
    i.poly([(8, 0), (9.5, 8), (6.5, 8)], RED)
    i.disc(8, 8, 1.4, DARK)


def salle(i):
    # Porte voûtée de la salle de convocation
    i.rect(2, 4, 14, 16, STONE)
    i.disc(8, 6, 6, STONE)
    i.rect(5, 7, 11, 16, WOOD)
    i.disc(8, 7, 3, WOOD)
    i.rect(8, 8, 9, 16, WOOD_DARK)
    i.put(10, 11, A.GOLD)


def gomme(i):
    i.poly([(1, 11), (9, 3), (15, 9), (7, 17)], (232, 132, 150))
    i.poly([(1, 11), (5, 7), (11, 13), (7, 17)], (245, 240, 230))
    i.line(5, 7, 11, 13, DARK, 1.0)


def selection(i):
    # Équerre et coins de sélection en pointillés
    for x in range(1, 16, 3):
        i.put(x, 1, A.GOLD)
        i.put(x, 14, A.GOLD)
    for y in range(1, 16, 3):
        i.put(1, y, A.GOLD)
        i.put(14, y, A.GOLD)
    i.rect(1, 1, 4, 2, A.GOLD)
    i.rect(1, 1, 2, 4, A.GOLD)
    i.rect(12, 14, 15, 15, A.GOLD)
    i.rect(14, 12, 15, 15, A.GOLD)
    i.line(4, 12, 12, 4, WOOD, 2.0)
    i.poly([(11, 3), (14, 2), (13, 5)], STONE)


def structure(i):
    # Trois blocs empilés
    def block(x, y, top, side):
        i.poly([(x, y + 2), (x + 4, y), (x + 8, y + 2), (x + 4, y + 4)], top)
        i.poly([(x, y + 2), (x + 4, y + 4), (x + 4, y + 9), (x, y + 7)], side)
        i.poly([(x + 4, y + 4), (x + 8, y + 2), (x + 8, y + 7), (x + 4, y + 9)], A.shade(side, 0.75))
    block(0, 6, (200, 120, 90), (160, 84, 60))
    block(8, 6, STONE, STONE_DARK)
    block(4, 1, GREEN, (60, 104, 58))


def main_(i):
    # Main gantée qui saisit
    i.rect(5, 6, 12, 13, A.PARCH_DARK)
    for k, x in enumerate((5, 7, 9, 11)):
        i.rect(x, 2 + (k % 2), x + 2, 7, A.PARCH_DARK)
        i.put(x, 2 + (k % 2), A.PARCH)
    i.poly([(5, 8), (2, 6), (1, 7), (5, 11)], A.PARCH_DARK)
    i.rect(5, 13, 12, 16, A.WINE)
    i.line(6, 9, 11, 9, A.shade(A.PARCH_DARK, 0.7), 0.8)


TOOLS = {"trone": trone, "maison": maison, "centre": centre, "salle": salle, "gomme": gomme,
         "selection": selection, "structure": structure, "main": main_}


def main():
    for name, draw in TOOLS.items():
        img = A.Img(16, 16)
        draw(img)
        img.save(f"outils/{name}.png")
        model = {"parent": "minecraft:item/handheld", "textures": {"layer0": f"valcombe:item/outils/{name}"}}
        (ROOT / "models/item").mkdir(parents=True, exist_ok=True)
        (ROOT / f"models/item/outil_{name}.json").write_text(json.dumps(model, indent=1) + "\n", encoding="utf-8")
        item = {"model": {"type": "minecraft:model", "model": f"valcombe:item/outil_{name}"}}
        (ROOT / f"items/outil_{name}.json").write_text(json.dumps(item, indent=1) + "\n", encoding="utf-8")
    print(f"{len(TOOLS)} outils dessinés dans {A.OUT / 'outils'}")


if __name__ == "__main__":
    main()
