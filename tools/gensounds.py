#!/usr/bin/env python3
"""Synthesises the buddies' tap sounds.

The seven that shipped were quarter-second synthetic blips -- pleasant enough, but a
bee and a pug made much the same noise, and none of them sounded like the animal on
the card. These are cartoon impressions instead: a bee that buzzes, a pug that barks,
a kitty that meows, a shark that splashes, a dino that growls, a cloud pup that chimes
and a burger buddy that chomps.

Everything is built from oscillators, noise and a couple of hand-written filters, so
the sounds are reproducible and tweakable rather than binary blobs nobody can change.
Pure standard library: numpy is not available in the sandbox this was written in, and
at 22 kHz for under a second the loops are quick enough not to care.

    python3 tools/gensounds.py [output-dir]

Defaults to app/src/main/res/raw. Run it again after editing any recipe below.
"""
import array
import math
import os
import random
import struct
import sys
import wave

RATE = 22050


# ----------------------------------------------------------------- building blocks

def envelope(n, attack, decay, hold=0.0):
    """Attack-hold-decay in seconds, as a list of gains."""
    a = max(1, int(attack * RATE))
    h = int(hold * RATE)
    d = max(1, int(decay * RATE))
    out = []
    for i in range(n):
        if i < a:
            g = i / a
        elif i < a + h:
            g = 1.0
        else:
            k = (i - a - h) / d
            g = math.exp(-3.2 * k) if k < 1.0 else 0.0
        out.append(g)
    return out


def lerp(a, b, t):
    return a + (b - a) * t


def sweep(points, n):
    """A value per sample, interpolated through (position 0..1, value) points."""
    out = []
    for i in range(n):
        p = i / max(1, n - 1)
        for k in range(len(points) - 1):
            p0, v0 = points[k]
            p1, v1 = points[k + 1]
            if p <= p1 or k == len(points) - 2:
                span = max(1e-6, p1 - p0)
                out.append(lerp(v0, v1, min(1.0, max(0.0, (p - p0) / span))))
                break
    return out


def osc(freqs, shape="sine", detune=0.0):
    """One sample per entry in freqs, phase-accumulated so sweeps stay continuous."""
    out = []
    phase = 0.0
    for f in freqs:
        phase += (f + detune) / RATE
        phase -= math.floor(phase)
        if shape == "sine":
            out.append(math.sin(2 * math.pi * phase))
        elif shape == "saw":
            out.append(2.0 * phase - 1.0)
        elif shape == "square":
            out.append(1.0 if phase < 0.5 else -1.0)
        else:  # triangle
            out.append(4.0 * abs(phase - 0.5) - 1.0)
    return out


def noise(n, seed):
    rng = random.Random(seed)
    return [rng.uniform(-1.0, 1.0) for _ in range(n)]


def lowpass(sig, cutoffs):
    """One-pole low-pass with a per-sample cutoff in Hz."""
    out = []
    y = 0.0
    for i, x in enumerate(sig):
        fc = cutoffs[i] if isinstance(cutoffs, list) else cutoffs
        a = 1.0 - math.exp(-2 * math.pi * max(20.0, fc) / RATE)
        y += a * (x - y)
        out.append(y)
    return out


def highpass(sig, cutoff):
    out = []
    y = 0.0
    for x in sig:
        a = 1.0 - math.exp(-2 * math.pi * max(20.0, cutoff) / RATE)
        y += a * (x - y)
        out.append(x - y)
    return out


def resonator(sig, centres, q=12.0):
    """Two-pole band-pass, for the vowel formants a meow is made of."""
    out = []
    y1 = y2 = 0.0
    for i, x in enumerate(sig):
        fc = centres[i] if isinstance(centres, list) else centres
        w = 2 * math.pi * fc / RATE
        r = math.exp(-w / (2 * q))
        a = (1 - r * r) * 0.5
        y = a * x + 2 * r * math.cos(w) * y1 - r * r * y2
        y2, y1 = y1, y
        out.append(y)
    return out


def mix(*layers):
    n = max(len(l) for l in layers)
    out = [0.0] * n
    for layer in layers:
        for i, v in enumerate(layer):
            out[i] += v
    return out


def apply_env(sig, env):
    return [s * e for s, e in zip(sig, env)]


def normalise(sig, peak=0.82):
    hi = max(1e-9, max(abs(s) for s in sig))
    gain = peak / hi
    # A short fade at each end so nothing clicks when SoundPool starts or stops it.
    fade = int(0.006 * RATE)
    out = []
    n = len(sig)
    for i, s in enumerate(sig):
        g = gain
        if i < fade:
            g *= i / fade
        if i > n - fade:
            g *= max(0.0, (n - i) / fade)
        out.append(max(-1.0, min(1.0, s * g)))
    return out


def write_wav(path, sig):
    data = array.array("h", (int(s * 32767) for s in normalise(sig)))
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data.tobytes())
    return len(data)


def seconds(x):
    return int(x * RATE)


# ------------------------------------------------------------------- the buddies

def bee():
    """A buzz: a saw that wobbles in pitch, chopped by wingbeat tremolo."""
    n = seconds(0.62)
    base = sweep([(0, 165), (0.15, 205), (0.7, 190), (1, 150)], n)
    vibrato = [b + 11 * math.sin(2 * math.pi * 13 * i / RATE) for i, b in enumerate(base)]
    tone = osc(vibrato, "saw")
    tone = lowpass(tone, 2400)
    wing = [0.55 + 0.45 * (0.5 + 0.5 * math.sin(2 * math.pi * 48 * i / RATE))
            for i in range(n)]
    body = [t * w for t, w in zip(tone, wing)]
    return apply_env(body, envelope(n, 0.05, 0.30, 0.20))


def pug():
    """Two barks: a noisy burst over a tone that drops fast."""
    def bark(dur, top, bottom, seed):
        n = seconds(dur)
        f = sweep([(0, top), (0.25, bottom), (1, bottom * 0.72)], n)
        voice = osc(f, "saw")
        rasp = lowpass(noise(n, seed), sweep([(0, 3200), (1, 900)], n))
        body = [0.72 * v + 0.5 * r for v, r in zip(voice, rasp)]
        body = resonator(body, sweep([(0, 900), (1, 620)], n), q=4.0)
        return apply_env(body, envelope(n, 0.004, 0.11, 0.02))

    first = bark(0.20, 300, 190, 11)
    second = bark(0.24, 265, 165, 29)
    gap = [0.0] * seconds(0.09)
    return first + gap + second


def kitty():
    """A meow: one voice, two formants sweeping through 'me' into 'ow'."""
    n = seconds(0.60)
    pitch = sweep([(0, 430), (0.18, 640), (0.55, 590), (1, 360)], n)
    voice = osc(pitch, "saw")
    f1 = resonator(voice, sweep([(0, 750), (0.3, 900), (1, 520)], n), q=9.0)
    f2 = resonator(voice, sweep([(0, 2100), (0.3, 1750), (1, 1050)], n), q=11.0)
    body = [0.9 * a + 0.55 * b for a, b in zip(f1, f2)]
    return apply_env(body, envelope(n, 0.05, 0.22, 0.26))


def shark():
    """A splash: a bright noise burst collapsing into a low bloop and bubbles."""
    n = seconds(0.34)
    spray = lowpass(noise(n, 5), sweep([(0, 7000), (1, 700)], n))
    spray = apply_env(spray, envelope(n, 0.003, 0.16, 0.02))
    bloop = apply_env(osc(sweep([(0, 260), (1, 90)], n), "sine"),
                      envelope(n, 0.008, 0.13, 0.0))
    out = mix([s * 0.8 for s in spray], [b * 0.7 for b in bloop])

    # A few bubbles rising after it.
    for k, (delay, freq) in enumerate([(0.20, 620), (0.28, 780), (0.36, 500)]):
        m = seconds(0.09)
        bub = apply_env(osc(sweep([(0, freq), (1, freq * 1.7)], m), "sine"),
                        envelope(m, 0.004, 0.05, 0.0))
        start = seconds(delay)
        if start + m > len(out):
            out += [0.0] * (start + m - len(out))
        for i, v in enumerate(bub):
            out[start + i] += v * 0.32
    return out


def dino():
    """A growl: a low rough saw with a rising roar and grit over the top."""
    n = seconds(0.70)
    pitch = sweep([(0, 78), (0.25, 132), (0.6, 118), (1, 70)], n)
    voice = osc(pitch, "saw")
    grit = [0.5 + 0.5 * math.sin(2 * math.pi * 31 * i / RATE) for i in range(n)]
    rough = [v * (0.55 + 0.45 * g) for v, g in zip(voice, grit)]
    breath = lowpass(noise(n, 77), sweep([(0, 1700), (1, 600)], n))
    body = [0.85 * r + 0.28 * b for r, b in zip(rough, breath)]
    body = resonator(body, sweep([(0, 420), (1, 300)], n), q=3.0)
    return apply_env(body, envelope(n, 0.03, 0.30, 0.24))


def cloud():
    """A chime: three inharmonic partials, the way a small bell rings."""
    n = seconds(0.85)
    out = [0.0] * n
    for ratio, gain, decay in ((1.0, 1.0, 0.42), (2.76, 0.5, 0.28), (5.40, 0.22, 0.17)):
        partial = apply_env(osc([880 * ratio] * n, "sine"), envelope(n, 0.002, decay))
        for i, v in enumerate(partial):
            out[i] += v * gain
    strike = apply_env(lowpass(noise(seconds(0.03), 3), 5000),
                       envelope(seconds(0.03), 0.001, 0.02))
    for i, v in enumerate(strike):
        out[i] += v * 0.35
    return out


def burger():
    """A chomp: two soft thuds with a crunch on each."""
    def chomp(dur, freq, seed):
        n = seconds(dur)
        thud = apply_env(osc(sweep([(0, freq), (1, freq * 0.55)], n), "sine"),
                         envelope(n, 0.004, 0.07, 0.0))
        crunch = lowpass(noise(n, seed), sweep([(0, 2600), (1, 500)], n))
        crunch = apply_env(crunch, envelope(n, 0.002, 0.045, 0.0))
        return [0.8 * t + 0.45 * c for t, c in zip(thud, crunch)]

    return chomp(0.13, 210, 19) + [0.0] * seconds(0.05) + chomp(0.16, 170, 41)


SOUNDS = {
    "buddy_bee_sound": bee,
    "buddy_pug_sound": pug,
    "buddy_kitty_sound": kitty,
    "buddy_shark_sound": shark,
    "buddy_dino_sound": dino,
    "buddy_cloud_sound": cloud,
    "buddy_burger_sound": burger,
}


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        here, "..", "app", "src", "main", "res", "raw")
    out_dir = os.path.abspath(out_dir)
    os.makedirs(out_dir, exist_ok=True)
    for name, make in sorted(SOUNDS.items()):
        path = os.path.join(out_dir, name + ".wav")
        samples = write_wav(path, make())
        print("%-22s %5d samples  %5.2fs  %6d bytes"
              % (name, samples, samples / RATE, os.path.getsize(path)))


if __name__ == "__main__":
    main()
