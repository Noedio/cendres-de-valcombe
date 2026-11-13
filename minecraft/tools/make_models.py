"""Génère les modèles 3D du village (jetons, trône, lutrin) et leurs textures originales.

Lancer depuis le dossier minecraft :  python tools/make_models.py
(après tools/make_assets.py : les faces des jetons reprennent ses emblèmes de rôles,
redessinés à la taille voulue plutôt que réduits).

Sortie dans src/client/resources/assets/valcombe :
  items/<nom>.json           définitions d'objets (composant minecraft:item_model)
  models/item/<nom>.json     modèles 3D
  textures/item/scene/*.png  textures des jetons et des coiffes des morts
  textures/item/objets/*.png textures des objets du jeu (plume, bâton, équerre, grimoire)
Toutes les textures sont dessinées en 128 px (moteur tools/dessin.py).
Les entités d'affichage du serveur portent ces modèles : rien à installer en plus du mod.
"""
import json
import math
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dessin as D  # noqa: E402  (moteur haute résolution)
import make_assets as A  # noqa: E402  (palette et emblèmes)

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/client/resources/assets/valcombe"
TEX = ASSETS / "textures/item/scene"
ROLE_ICONS = ASSETS / "textures/gui/roles"
ROLE_JAVA = ROOT / "src/main/java/fr/valcombe/rules/Role.java"

GREY = (120, 116, 118)
GREY_DARK = (70, 66, 68)

TAILLE = 128  # côté des textures, en pixels


def save(img, path):
    img.enregistrer(path)


def lit(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3])


# ---------------------------------------------------------------------------
# Textures des jetons (32 unités, rendues en 128 px)
# ---------------------------------------------------------------------------
S = TAILLE / 32  # pixels par unité


def toile_jeton():
    return D.Toile(32, 32, S, relief=1.0, biseau=1.0, degrade=0.8, creux=(A.INK,))


_FONDS = {}


def token_base(face, ring, inner):
    """Jeton : anneau de métal bombé et brossé éclairé d'en haut à gauche, liseré,
    intérieur ombré par l'anneau, douze rivets en relief. Calculé une fois par jeu de couleurs."""
    cle = (face, ring, inner)
    if cle not in _FONDS:
        img = toile_jeton()
        r_out, r_in = 15.6, 12.6
        grain = D.Bruit(23)
        lx, ly = D.LUMIERE

        def f(x, y):
            dx, dy = x - 16, y - 16
            d = math.hypot(dx, dy)
            a = (r_out - d) * S + 0.5
            if a <= 0:
                return None
            a = min(1.0, a)
            light = (dx * lx + dy * ly) / (d + 1e-6)
            if d >= r_in:
                # Anneau : normale radiale, bombé (plus clair vers le milieu de l'anneau), stries du tour
                t = (d - r_in) / (r_out - r_in)
                bulge = 1 - abs(t - 0.45) * 1.3
                brosse = grain(math.atan2(dy, dx) * 40, d * 0.6) * 0.08 - 0.04
                c = lit(ring, 0.7 + 0.25 * bulge + 0.3 * light + brosse)
                spec = max(0.0, light - 0.5) * 2 * max(0.0, 1 - abs(t - 0.42) / 0.25)
                return D.melange(c, A.WHITE, min(0.8, spec * 0.7)) + (a,)
            # Intérieur : léger vignettage et grain, ombre portée de l'anneau en haut à gauche
            v = 1 - 0.18 * (d / r_in) ** 2 + 0.04 * grain.fbm(x * 0.8, y * 0.8)
            ombre = max(0.0, light) * A.smooth(r_in - 3, r_in, d) * 0.3
            return lit(inner, v - ombre) + (1.0,)
        img.peindre(f)
        img.ring(16, 16, r_in, 0.4, A.INK, relief=0)
        img.ring(16, 16, r_out - 0.15, 0.3, lit(ring, 0.45), relief=0)
        for k in range(12):  # rivets en relief
            ang = 2 * math.pi * k / 12
            rx, ry = 16 + 14.1 * math.cos(ang), 16 + 14.1 * math.sin(ang)
            img.disc(rx + 0.12, ry + 0.18, 0.95, lit(face, 0.4) + (150,), relief=0)
            img.disc(rx, ry, 0.85, face, biseau=0.6)
        _FONDS[cle] = img
    return _FONDS[cle].copie()


def crest():
    """Dos des jetons : la tour de Valcombe, ciselée d'or, sur fond lie-de-vin."""
    img = token_base(A.GOLD, A.BRONZE, A.WINE)
    for x, y, r in ((8.5, 11, 1.1), (23.5, 11, 1.1), (9.5, 18.5, 0.7), (22.5, 18.5, 0.7)):
        img.etincelle(x, y, r, A.GOLD, relief=0.6)
    tour = D.union(D.rect(13, 9, 19, 23), D.rect(11, 21, 21, 23.4),
                   *[D.rect(x, 7, x + 2, 10) for x in (12, 15, 18)], D.rect(12, 9.4, 20, 10.4))
    img.composee(tour, A.GOLD)
    for y in (12.5, 16, 19.5):  # assises de pierre
        img.line(13.4, y, 18.6, y, lit(A.GOLD, 0.7), 0.25, relief=0)
    img.composee(D.union(D.rect(15, 15, 17, 18), D.disque(16, 15, 1)), A.WINE, creux=True)  # fenêtre
    img.composee(D.union(D.rect(15, 20.6, 17, 23.4), D.disque(16, 20.6, 1)), A.WINE, creux=True)  # porte
    img.disc(16, 12.2, 0.8, A.WINE, creux=True)
    return img


def dead():
    img = token_base(A.ASH, GREY_DARK, GREY)
    img.composee(D.union(D.disque(16, 14, 6), D.rect(13, 17, 19, 22)), A.WHITE)  # crâne
    img.disc(13.5, 14, 1.8, A.INK)
    img.disc(18.5, 14, 1.8, A.INK)
    img.poly([(16, 16), (15, 18), (17, 18)], A.INK)
    for x in (14, 16, 18):
        img.rect(x, 20, x + 1, 22, A.INK)
    img.chemin([(17.6, 8.3), (16.9, 10.2), (17.8, 11.4)], lit(A.WHITE, 0.62), 0.25, relief=0)  # fêlure
    img.ombre(0.3, 0.45, 0.8, 0.35)
    return img


def ghost():
    img = token_base(A.GOLD, GREY_DARK, GREY)
    img.rect(14, 13, 18, 24, A.PARCH)  # cierge
    img.rect(14, 22.6, 18, 24, A.PARCH_DARK)
    img.poly([(14, 13), (15.2, 13), (15, 16.4), (14.4, 16.8), (14, 15.6)], lit(A.PARCH, 1.06))  # coulure
    img.rect(15.6, 11, 16.4, 13, A.INK)  # mèche
    img.halo(16, 9, 4.8, (255, 200, 110), 0.55)
    img.poly([(16, 5.2), (17.9, 8.8), (17.2, 10.8), (16, 11.4), (14.8, 10.8), (14.1, 8.8)], (240, 160, 60), relief=0.5)
    img.poly([(16, 7.4), (17, 9.4), (16, 10.8), (15, 9.4)], (255, 236, 170), relief=0)
    return img


def edge():
    """Tranche du jeton : bronze moleté (stries régulières), plus clair au centre."""
    img = D.Toile(16, 16, TAILLE / 16)

    def f(x, y):
        strie = math.sin(x * math.pi)  # une strie toutes les deux unités
        v = 0.85 + 0.25 * (1 - abs(y - 8) / 8.5) + 0.09 * strie + (0.06 if strie > 0.8 else 0)
        return lit(A.BRONZE, v) + (1.0,)
    img.peindre(f)
    return img


def role_teams():
    teams = {}
    for m in re.finditer(r"^\s*([A-Z_]+)\(\"[^\"]*\",\s*RoleType\.([A-Z]+)", ROLE_JAVA.read_text(encoding="utf-8"), re.M):
        teams[m.group(1).lower()] = "evil" if m.group(2) in ("SBIRE", "DEMON") else "good"
    return teams


_EMBLEMES = {}


def role_face(role, team):
    """Emblème du rôle redessiné en 80 px (40 unités sur 64) au centre du jeton, aux couleurs du camp."""
    ring = A.EVIL if team == "evil" else A.GOOD
    img = token_base(A.GOLD, ring, A.PARCH)
    taille = TAILLE * 40 // 64
    if role not in _EMBLEMES:
        _EMBLEMES[role] = A.dessiner_icone(role, taille)
    off = (TAILLE - taille) // 2
    rayon = 12.4 * S

    def masque(px, py):
        return min(1.0, rayon - math.hypot(px + 0.5 - TAILLE / 2, py + 0.5 - TAILLE / 2) + 0.5)
    img.coller(_EMBLEMES[role], off, off, masque)
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
def matiere(color, relief=0.08, fin=0.05, graine=1, frequence=0.35, fibres=None):
    """Matière unie en 128 px (16 unités) : marbrures douces (bruit lissé) et grain fin.
    fibres : (angle, force) ajoute des fibres orientées (feutre, tissu)."""
    img = D.Toile(16, 16, TAILLE / 16)
    doux, grain = D.Bruit(graine), D.Bruit(graine + 100)

    def f(x, y):
        v = 1 + relief * doux.fbm(x * frequence, y * frequence) + fin * grain.fbm(x * 4, y * 4, 2)
        if fibres:
            ang, force = fibres
            u = x * math.cos(ang) + y * math.sin(ang)
            w = -x * math.sin(ang) + y * math.cos(ang)
            v += force * (grain(u * 1.2, w * 9) * 2 - 1)
        return lit(color, v) + (1.0,)
    img.peindre(f)
    return img


def ribbon():
    """Ruban d'âme : soie turquoise à reflets horizontaux et trame fine."""
    img = matiere((92, 196, 206), 0.06, 0.03, 2, 0.3, (0.0, 0.05))

    def f(x, y):
        reflet = max(0.0, math.sin(y * math.pi / 8)) ** 6
        return 230, 255, 255, 0.35 * reflet
    img.peindre(f)
    return img


def flame():
    """Flamme : cœur clair et jaune, bords orangés, frémissement."""
    img = D.Toile(16, 16, TAILLE / 16)
    b = D.Bruit(5)

    def f(x, y):
        t = min(1.0, abs(x - 8) / 8 + 0.15 * b.fbm(x * 0.5, y * 0.8))
        c = D.melange((255, 244, 190), (236, 120, 40), t)
        return lit(c, 1 + 0.12 * (1 - y / 16)) + (1.0,)
    img.peindre(f)
    return img


def skull_face():
    """Face du crâne : os marbré, orbites creuses à lueur d'âme, cavité nasale, dents."""
    img = matiere((226, 219, 196), 0.07, 0.05, 3)
    img.relief, img.biseau, img.degrade = 0.9, 0.6, 0.0
    sombre = (40, 30, 34)
    for cx in (4.5, 11.5):
        img.poly([(cx - 2, 6), (cx + 2, 6), (cx + 1.8, 7.6), (cx + 0.6, 9), (cx - 0.6, 9), (cx - 1.8, 7.6)], sombre, creux=True)
        img.halo(cx, 7.4, 2.1, (110, 220, 230), 0.9)
        img.disc(cx, 7.4, 0.55, (190, 250, 255), relief=0)
    img.poly([(8, 9), (9, 10.9), (8, 10.5), (7, 10.9)], (90, 76, 70), creux=True)
    img.rect(3.8, 11.8, 12.2, 13.2, sombre, creux=True)
    for x in range(4, 12):  # dents
        img.rect(x + 0.12, 11.9, x + 0.88, 13.1, (214, 206, 184), relief=0.5)
    img.chemin([(10.4, 1.2), (9.6, 3), (10.6, 4.3)], lit((226, 219, 196), 0.65), 0.2, relief=0)
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
    save(matiere((34, 30, 38), 0.1, 0.06, 1, 0.3, (0.7, 0.12)), TEX / "feutre.png")
    save(ribbon(), TEX / "ruban.png")
    save(matiere((236, 228, 206), 0.04, 0.02, 4, 0.25), TEX / "cire.png")
    save(flame(), TEX / "flamme.png")
    save(matiere((214, 206, 184), 0.07, 0.06, 6), TEX / "os.png")
    save(skull_face(), TEX / "crane.png")
    write_item("chapeau_mort", hat_model(False))
    write_item("chapeau_mort_vote", hat_model(True))
    write_item("crane_mort", skull_model(False))
    write_item("crane_mort_vote", skull_model(True))


# ---------------------------------------------------------------------------
# Objets du jeu (en main), dessinés en 32 unités et rendus en 128 px
# ---------------------------------------------------------------------------
LEATHER = (92, 38, 30)
BRASS = (201, 160, 72)
WOOD = (104, 70, 42)
PAGE = (236, 222, 188)


def toile_objet():
    return D.Toile(32, 32, TAILLE / 32, relief=1.0, biseau=1.2, degrade=0.8)


def outline(img, color=A.INK):
    """Contour sombre autour de la silhouette (lisibilité, style des objets du jeu)."""
    img.contour(0.7, color)
    return img


def quill():
    """Plume d'accusation : plume d'oie blanche aux barbes fines, rachis, bec fendu trempé d'encre rouge."""
    img = toile_objet()
    # Vexille (de part et d'autre du rachis, en diagonale bas-gauche vers haut-droite) : un seul contour
    gauche, droite = [], []
    for k in range(21):
        u = 0.18 + 0.82 * k / 20
        cx, cy = 7 + 21 * u, 25 - 21 * u
        w = 5.5 * math.sin(math.pi * min(1.0, u * 1.15))
        gauche.append((cx - w * 0.7, cy - w * 0.7))
        droite.append((cx + w * 0.62, cy + w * 0.62))
    contour = gauche + [(28.6, 3.4)] + droite[::-1]
    img.poly(contour, A.WHITE, relief=0.6)
    img.poly([(10.78, 21.22)] + droite + [(28.6, 3.4)], (214, 210, 204), relief=0)  # côté ombré

    def plumes(x, y):
        # Barbes : fines stries obliques
        v = math.sin((x - y) * math.pi * 0.8)
        return (180, 176, 170, 70 * max(0.0, v))
    img.poly(contour, plumes, relief=0, degrade=0)
    for u in (0.42, 0.66):  # deux encoches entre les barbes, côté lumière
        cx, cy = 7 + 21 * u, 25 - 21 * u
        img.effacer(*D.poly([(cx - 4.2, cy - 4.2), (cx - 2.2, cy - 3.2), (cx - 0.8, cy - 0.4)]))
    img.line(4, 28, 28, 4, (236, 226, 200), 1.1)    # rachis
    img.line(3, 29, 7.4, 24.6, (150, 24, 24), 1.6)  # bec trempé d'encre rouge
    img.line(3.4, 28.6, 5.4, 26.6, (90, 10, 10), 0.3, relief=0)
    img.disc(2.6, 29.4, 0.7, (90, 10, 10))
    return outline(img)


def staff():
    """Bâton du Conteur : bois veiné, bagues de laiton, couronne tenant une pierre d'âme lumineuse."""
    img = toile_objet()
    img.line(4, 29, 22, 11, lit(WOOD, 0.9), 2.8)
    img.line(4.6, 27.8, 21.2, 11.2, lit(WOOD, 1.25), 0.5, relief=0)    # veine claire
    img.line(5.4, 28.6, 22, 12, lit(WOOD, 0.7), 0.35, relief=0)         # veine sombre
    for u in (0.3, 0.62):  # bagues de laiton
        x, y = 4 + 18 * u, 29 - 18 * u
        img.line(x - 1.2, y - 1.2, x + 1.2, y + 1.2, BRASS, 1.6)
    for a in (-0.9, 0, 0.9):  # griffes de la couronne
        img.line(22, 10, 22 + 5 * math.cos(a - 0.8), 10 - 5 * math.sin(a + 0.8), BRASS, 1.3)
    img.disc(21.6, 10.4, 1.4, BRASS)
    img.halo(25.5, 6.5, 6, (140, 235, 245), 0.55)
    img.disc(25.5, 6.5, 3.6, (60, 170, 190))
    img.disc(25.5, 6.5, 2.6, (120, 225, 235), relief=0.4)
    img.etincelle(24.4, 5.3, 1.4, A.WHITE, relief=0)
    return outline(img)


def square():
    """Équerre du bâtisseur : laiton gradué, vis d'assemblage et fil à plomb."""
    img = toile_objet()
    img.composee(D.union(D.rect(5, 4, 9, 28), D.rect(5, 24, 28, 28)), BRASS)
    for k, y in enumerate(range(7, 23, 2)):
        img.line(8.9 - (1.6 if k % 2 == 0 else 0.9), y, 8.9, y, lit(BRASS, 0.5), 0.3, relief=0)
    for k, x in enumerate(range(10, 27, 2)):
        img.line(x, 27.9 - (1.6 if k % 2 == 0 else 0.9), x, 27.9, lit(BRASS, 0.5), 0.3, relief=0)
    img.disc(7, 26, 0.9, lit(BRASS, 0.8))
    img.line(6.4, 26, 7.6, 26, lit(BRASS, 0.45), 0.25, relief=0)
    img.line(20, 6, 20, 17, (200, 200, 200), 0.5, relief=0)
    img.poly([(20, 16.4), (21.8, 19), (20, 22), (18.2, 19)], (150, 150, 160))
    img.disc(20, 6, 0.6, lit(BRASS, 0.8))
    return outline(img)


def leather():
    """Couverture du grimoire : cuir grenu, double filet doré, tour de Valcombe et fleurons aux coins."""
    img = D.Toile(32, 32, TAILLE / 32, relief=1.0, biseau=0.8, degrade=0.5)
    grain, pores = D.Bruit(5), D.Bruit(55)

    def cuir(x, y):
        v = 0.9 + 0.06 * grain.fbm(x * 0.4, y * 0.4) + 0.05 * (pores(x * 3, y * 3) * 2 - 1)
        v -= 0.12 * max(abs(x - 16), abs(y - 16)) ** 4 / 16 ** 4  # bords usés plus sombres
        return lit(LEATHER, v) + (1.0,)
    img.peindre(cuir)
    for r in (2.3, 3.4):
        img.chemin([(r, r), (32 - r, r), (32 - r, 32 - r), (r, 32 - r), (r, r)], BRASS + (220,), 0.4 if r < 3 else 0.25, relief=0.5)
    for cx, cy in ((5, 5), (27, 5), (5, 27), (27, 27)):
        img.etincelle(cx, cy, 1.4, BRASS, relief=0.6)
    tour = D.union(D.rect(14, 9, 18, 21), D.rect(12, 19, 20, 21), *[D.rect(x, 7, x + 2, 10) for x in (13, 15, 17)])
    img.composee(tour, BRASS)
    img.composee(D.union(D.rect(15, 13.4, 17, 16), D.disque(16, 13.4, 1)), LEATHER, creux=True)
    return img


def pages():
    """Tranche des pages : feuillets serrés, légèrement irréguliers."""
    img = D.Toile(32, 32, TAILLE / 32)
    b = D.Bruit(9)

    def f(x, y):
        feuillet = 0.5 + 0.5 * math.cos(y * math.pi * 2)  # un feuillet par unité
        v = 0.82 + 0.13 * feuillet + 0.03 * b.fbm(x * 0.3, y * 2)
        return lit(PAGE, v) + (1.0,)
    img.peindre(f)
    return img


def clasp():
    """Fermoir de laiton : dégradé bombé, filet gravé et rivet."""
    img = D.Toile(16, 16, TAILLE / 16, relief=0.8, biseau=1.0)

    def f(x, y):
        return lit(BRASS, 1.1 - 0.4 * y / 16 + 0.08 * math.sin(x * 0.4)) + (1.0,)
    img.peindre(f)
    img.chemin([(1.5, 1.5), (14.5, 1.5), (14.5, 14.5), (1.5, 14.5), (1.5, 1.5)], lit(BRASS, 0.55), 0.5, creux=True)
    img.disc(8, 8, 2.2, lit(BRASS, 1.05))
    img.disc(8, 8, 0.9, lit(BRASS, 0.7), creux=True)
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
        save(role_face(role, teams.get(role, "good")), TEX / f"role_{role}.png")
        write_item(f"jeton_{role}", token_model(f"valcombe:item/scene/role_{role}", back))
        # Icône plate du rôle, pour les menus en coffre (le jeton 3D se lit mal de face dans un inventaire)
        write_item(f"icone_{role}", flat_item(f"valcombe:item/scene/role_{role}"))
        # Rôles créés : n'importe quel emblème, aux couleurs de n'importe quel camp
        for team, suffix in (("good", "bien"), ("evil", "mal")):
            save(role_face(role, team), TEX / f"role_{role}_{suffix}.png")
            write_item(f"jeton_{role}_{suffix}", token_model(f"valcombe:item/scene/role_{role}_{suffix}", back))
            write_item(f"icone_{role}_{suffix}", flat_item(f"valcombe:item/scene/role_{role}_{suffix}"))
    write_item("trone", throne_model())
    write_item("lutrin", lectern_model())
    write_hats()
    write_objects()
    print(f"{len(teams)} rôles, modèles écrits dans {ASSETS}")


if __name__ == "__main__":
    main()
