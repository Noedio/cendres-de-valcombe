"""Synthétise les sons originaux de Valcombe (aucun échantillon extérieur).

Lancer depuis le dossier minecraft :  python tools/make_sounds.py
Dépendances (outil de développement seulement) :  python -m pip install --user soundfile numpy

Sortie : src/client/resources/assets/valcombe/sounds/*.ogg et sounds.json.
Tout est mono (les sons sont placés dans le monde) et en 32 kHz pour rester léger.
"""
import json
from pathlib import Path

import numpy as np
import soundfile as sf

RATE = 32000
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/client/resources/assets/valcombe/sounds"
RNG = np.random.default_rng(1347)


def t(seconds):
    return np.arange(int(seconds * RATE)) / RATE


def env(n, attack=0.005, decay=1.0):
    x = np.arange(n) / RATE
    a = np.clip(x / max(attack, 1e-4), 0, 1)
    return a * np.exp(-x / decay)


def lowpass(x, cutoff):
    """Filtre passe-bas simple à un pôle."""
    a = np.exp(-2 * np.pi * cutoff / RATE)
    y = np.empty_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc
        y[i] = acc
    return y


def norm(x, peak=0.85):
    m = np.max(np.abs(x)) or 1
    return x / m * peak


def fade(x, inn=0.02, out=0.3):
    n = len(x)
    i, o = int(inn * RATE), int(out * RATE)
    x = x.copy()
    if i:
        x[:i] *= np.linspace(0, 1, i)
    if o:
        x[-o:] *= np.linspace(1, 0, o)
    return x


def bell(freq, seconds, decay):
    """Cloche : partiels inharmoniques qui s'éteignent à des vitesses différentes."""
    x = t(seconds)
    out = np.zeros_like(x)
    for ratio, amp, d in [(0.5, 0.5, 1.6), (1.0, 1.0, 1.0), (1.19, 0.6, 0.8), (1.5, 0.45, 0.6),
                          (2.0, 0.5, 0.5), (2.52, 0.3, 0.35), (3.0, 0.22, 0.3), (4.07, 0.12, 0.2)]:
        beat = 1 + 0.002 * np.sin(2 * np.pi * 1.3 * x)
        out += amp * np.sin(2 * np.pi * freq * ratio * x * beat) * np.exp(-x / (decay * d))
    strike = RNG.normal(0, 1, len(x)) * np.exp(-x / 0.01) * 0.3
    return norm(out + strike)


def organ_chord(freqs, seconds, decay=3.0):
    x = t(seconds)
    out = np.zeros_like(x)
    for f in freqs:
        for h, a in [(1, 1.0), (2, 0.5), (3, 0.25), (4, 0.12)]:
            out += a * np.sin(2 * np.pi * f * h * x + RNG.uniform(0, 6.28))
    e = np.clip(x / 0.08, 0, 1) * np.exp(-x / decay)
    return out * e


def sequence(parts):
    """Assemble des morceaux (début en secondes, signal)."""
    end = max(start + len(sig) / RATE for start, sig in parts)
    out = np.zeros(int(end * RATE) + 1)
    for start, sig in parts:
        i = int(start * RATE)
        out[i:i + len(sig)] += sig
    return out


def note(name):
    names = {"C": -9, "D": -7, "E": -5, "F": -4, "G": -2, "A": 0, "B": 2}
    base = names[name[0]]
    if "#" in name:
        base += 1
    if "b" in name[1:]:
        base -= 1
    octave = int(name[-1])
    return 440 * 2 ** ((base + 12 * (octave - 4)) / 12)


def chord(*names):
    return [note(n) for n in names]


# ---------------------------------------------------------------------------
# Les sons
# ---------------------------------------------------------------------------
def cloche():
    return fade(bell(520, 4.0, 1.4), 0, 0.5)


def glas():
    a = bell(196, 7.0, 2.6)
    return fade(sequence([(0, a), (2.6, a * 0.8)]), 0, 1.0)


def tic():
    x = t(0.12)
    click = RNG.normal(0, 1, len(x)) * np.exp(-x / 0.004)
    wood = np.sin(2 * np.pi * 1800 * x) * np.exp(-x / 0.015) * 0.6 + np.sin(2 * np.pi * 900 * x) * np.exp(-x / 0.03) * 0.4
    return norm(lowpass(click, 4000) + wood, 0.7)


def reveil():
    parts = []
    for k, n in enumerate(["E5", "G5", "B5", "E6"]):
        parts.append((k * 0.12, bell(note(n), 1.6, 0.6) * (0.9 - 0.12 * k)))
    return fade(norm(sequence(parts), 0.6), 0, 0.4)


def parchemin():
    x = t(0.5)
    n = RNG.normal(0, 1, len(x))
    rustle = lowpass(n, 2500) - lowpass(n, 500)
    e = np.exp(-((x - 0.12) ** 2) / 0.003) + 0.6 * np.exp(-((x - 0.3) ** 2) / 0.002)
    return norm(rustle * e, 0.6)


def craquement():
    """Bois qui grince : le gibet se dresse."""
    x = t(1.6)
    f = 70 + 25 * np.sin(2 * np.pi * 0.7 * x)
    phase = np.cumsum(2 * np.pi * f / RATE)
    saw = 2 * ((phase / (2 * np.pi)) % 1) - 1
    grain = (RNG.random(len(x)) < 0.02) * RNG.normal(0, 1, len(x))
    body = lowpass(saw * (0.6 + 0.4 * np.sin(2 * np.pi * 11 * x)) + grain * 2, 900)
    return fade(norm(body, 0.7), 0.05, 0.4)


def victoire_bien():
    seq = [(0.0, chord("C4", "E4", "G4")), (0.7, chord("F4", "A4", "C5")), (1.4, chord("G4", "B4", "D5")),
           (2.1, chord("C4", "E4", "G4", "C5"))]
    parts = [(s, organ_chord(c, 4.0 if k == 3 else 1.2, 2.5 if k == 3 else 0.9)) for k, (s, c) in enumerate(seq)]
    parts.append((2.1, bell(note("C6"), 3.0, 1.0) * 0.4))
    return fade(norm(sequence(parts)), 0, 1.2)


def victoire_mal():
    seq = [(0.0, chord("A3", "C4", "E4")), (0.8, chord("F3", "A3", "C4")), (1.6, chord("E3", "G#3", "B3")),
           (2.4, chord("A2", "A3", "C4", "E4"))]
    parts = [(s, organ_chord(c, 4.5 if k == 3 else 1.3, 3.0 if k == 3 else 1.0)) for k, (s, c) in enumerate(seq)]
    parts.append((2.4, bell(note("A2"), 4.0, 2.0) * 0.5))
    return fade(norm(sequence(parts)), 0, 1.5)


def vent(seconds, brightness):
    x = t(seconds)
    n = RNG.normal(0, 1, len(x))
    w = lowpass(n, brightness)
    gust = 0.55 + 0.45 * np.sin(2 * np.pi * 0.07 * x + 1) * np.sin(2 * np.pi * 0.031 * x)
    return w * gust


def ambiance_nuit():
    seconds = 24
    out = norm(vent(seconds, 260), 0.22)
    x = t(seconds)
    # Grillons : chants brefs et aigus
    for start in np.arange(1.0, seconds - 1, 4.5):
        y = t(0.35)
        chirp = np.sin(2 * np.pi * 4300 * y) * (np.sin(2 * np.pi * 38 * y) > 0) * np.exp(-y / 0.2) * 0.025
        i = int((start + RNG.uniform(0, 0.6)) * RATE)
        out[i:i + len(chirp)] += chirp[: len(out) - i]
    # Une chouette, au loin
    for start in (13.0,):
        y = t(1.4)
        f = 380 - 40 * y
        hoot = np.sin(np.cumsum(2 * np.pi * f / RATE)) * np.exp(-((y - 0.3) ** 2) / 0.02) * 0.05
        hoot += np.sin(np.cumsum(2 * np.pi * (f - 20) / RATE)) * np.exp(-((y - 0.95) ** 2) / 0.04) * 0.05
        i = int(start * RATE)
        out[i:i + len(hoot)] += hoot
    return crossloop(out)


def ambiance_jour():
    seconds = 24
    out = norm(vent(seconds, 600), 0.18)
    for start in np.arange(0.8, seconds - 1, 2.3):
        y = t(0.5)
        f = 2600 + 900 * np.sin(2 * np.pi * RNG.uniform(6, 11) * y)
        song = np.sin(np.cumsum(2 * np.pi * f / RATE)) * np.exp(-y / 0.18) * 0.06
        i = int((start + RNG.uniform(0, 0.9)) * RATE)
        out[i:i + len(song)] += song[: len(out) - i]
    return crossloop(out)


def crossloop(x, overlap=1.5):
    """Boucle sans claquement : la fin se fond dans le début."""
    n = int(overlap * RATE)
    head, body, tail = x[:n], x[n:-n], x[-n:]
    ramp = np.linspace(0, 1, n)
    return norm(np.concatenate([body, tail * (1 - ramp) + head * ramp]), 0.5)


SOUNDS = {
    "cloche": (cloche, False),
    "glas": (glas, False),
    "tic": (tic, False),
    "reveil": (reveil, False),
    "parchemin": (parchemin, False),
    "craquement": (craquement, False),
    "victoire_bien": (victoire_bien, False),
    "victoire_mal": (victoire_mal, False),
    "ambiance_nuit": (ambiance_nuit, True),
    "ambiance_jour": (ambiance_jour, True),
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    index = {}
    for name, (make, stream) in SOUNDS.items():
        data = make().astype(np.float32)
        # Écriture par morceaux : d'un seul bloc, l'encodeur Vorbis de libsndfile déborde sa pile sous Windows.
        with sf.SoundFile(OUT / f"{name}.ogg", "w", RATE, 1 if data.ndim == 1 else data.shape[1],
                          format="OGG", subtype="VORBIS") as f:
            for i in range(0, len(data), 4096):
                f.write(data[i:i + 4096])
        entry = {"name": f"valcombe:{name}"}
        if stream:
            entry["stream"] = True
        index[name] = {"sounds": [entry], "subtitle": f"subtitles.valcombe.{name}"}
        print(f"  {name}.ogg : {len(data) / RATE:.1f} s")
    (OUT.parent / "sounds.json").write_text(json.dumps(index, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
