#!/usr/bin/env python3
"""Synthesises every sound the app makes except the victory jingle.

The seven that shipped were quarter-second synthetic blips -- pleasant enough, but a
bee and a pug made much the same noise, and none of them sounded like the animal on
the card. These are cartoon impressions instead: a bee that buzzes, a pug that barks,
a kitty that meows, a shark that splashes, a dino that growls, a cloud pup that chimes,
a burger buddy that chomps and a trike that snorts.

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


def normalise(sig, peak=0.82, loop=False):
    hi = max(1e-9, max(abs(s) for s in sig))
    gain = peak / hi
    # A short fade at each end so nothing clicks when SoundPool starts or stops it.
    # Never on a looping track: that fade would land on the seam and pump the volume
    # down and back up once every time round.
    fade = 0 if loop else int(0.006 * RATE)
    out = []
    n = len(sig)
    for i, s in enumerate(sig):
        g = gain
        if fade:
            if i < fade:
                g *= i / fade
            if i > n - fade:
                g *= max(0.0, (n - i) / fade)
        out.append(max(-1.0, min(1.0, s * g)))
    return out


def write_wav(path, sig, loop=False):
    data = array.array("h", (int(s * 32767) for s in normalise(sig, loop=loop)))
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


def trike():
    """A snort: two puffs of air through a big nose, over a low rumble.

    Kept away from the dino's growl on purpose -- they are the two reptiles and they
    sit next to each other in the picker. The growl is voiced and long; this is mostly
    unvoiced air, and the rumble beneath it stays below the growl's range.
    """
    def puff(dur, centre, seed, bright):
        n = seconds(dur)
        air = lowpass(noise(n, seed), sweep([(0, bright), (1, bright * 0.25)], n))
        air = resonator(air, sweep([(0, centre), (1, centre * 0.6)], n), q=5.0)
        return apply_env(air, envelope(n, 0.006, 0.09, 0.01))

    n = seconds(0.52)
    rumble = lowpass(apply_env(osc(sweep([(0, 96), (0.4, 78), (1, 62)], n), "saw"),
                               envelope(n, 0.02, 0.26, 0.08)), 320)
    out = [r * 0.45 for r in rumble]
    # In then out, and the second one harder -- a snort that starts loud and tails off
    # is a sigh. The gap between them has to survive the rumble underneath, which is
    # why the quiet puff comes first.
    for delay, dur, centre, seed, bright, gain in ((0.00, 0.15, 620, 61, 4200, 0.85),
                                                   (0.21, 0.21, 470, 83, 3400, 1.25)):
        p = puff(dur, centre, seed, bright)
        start = seconds(delay)
        if start + len(p) > len(out):
            out += [0.0] * (start + len(p) - len(out))
        for i, v in enumerate(p):
            out[start + i] += v * gain
    return out


# --------------------------------------------------------------------- eating
#
# One per buddy, fired three times per treat as Engine.pollBite reports each bite.
# Two constraints shape all of them. They have to be SHORT, because the three land
# inside 1.4 seconds and a tail that outlives its bite turns the set into mush. And
# each has to be clearly not that buddy's TAP sound, or a poke and a bite become the
# same noise -- which matters most for the burger, whose tap sound is already a chomp.
#
# They are per buddy rather than one shared crunch because every other eating cue in
# this app already is: the munch word is YUM / BUZZ / NOM / CHOMP / MUNCH / SPARKLE /
# PURR / CRUNCH and the feast move is eight distinct motions. One generic crunch
# behind all of that would be the only part that did not know which animal it was.


def _crunch(dur, low, bright, seed, grains=7):
    """A crunch: a scatter of tiny band-limited grains under one fast envelope.

    Crunching is not a tone with an envelope on it, it is a lot of very short events
    close together -- which is why a single filtered noise burst reads as a "shh" and
    this reads as something breaking.
    """
    n = seconds(dur)
    rng = random.Random(seed)
    out = [0.0] * n
    for g in range(grains):
        at = int(rng.uniform(0.0, 0.55) * n)
        m = min(n - at, seconds(0.02 + rng.random() * 0.03))
        if m <= 2:
            continue
        grain = lowpass(noise(m, seed * 31 + g), rng.uniform(low, bright))
        grain = apply_env(grain, envelope(m, 0.001, 0.012))
        amp = rng.uniform(0.5, 1.0)
        for i, v in enumerate(grain):
            out[at + i] += v * amp
    return apply_env(out, envelope(n, 0.002, dur * 0.55))


def eat_burger():
    """Bun and lettuce: bright and papery, with only a hint of thud under it.

    The thud started an octave lower and half again as loud, which put the whole sound
    at 130 Hz -- the same place as the bone, and measurably the same sound. Bread is
    not the bone; the difference is that the bone has a body and this does not.
    """
    n = seconds(0.22)
    thud = apply_env(osc(sweep([(0, 260), (1, 150)], n), "sine"), envelope(n, 0.003, 0.05))
    return mix(_crunch(0.22, 1600, 5200, 7), [t * 0.28 for t in thud])


def eat_bee():
    """A honey sip: wet air rising, with a pop at the end of it."""
    n = seconds(0.26)
    air = resonator(lowpass(noise(n, 21), sweep([(0, 900), (1, 2600)], n)),
                    sweep([(0, 480), (1, 1500)], n), q=7.0)
    pop = apply_env(osc(sweep([(0, 300), (1, 900)], n), "sine"),
                    envelope(n, 0.02, 0.10, 0.04))
    return mix([a * 0.9 for a in air], [p * 0.5 for p in pop])


def eat_pug():
    """A bone: the hardest crunch in the set, low and gritty."""
    n = seconds(0.24)
    body = lowpass(apply_env(osc(sweep([(0, 120), (1, 70)], n), "square"),
                             envelope(n, 0.002, 0.05)), 500)
    return mix(_crunch(0.24, 240, 1600, 13, grains=9), [b * 0.62 for b in body])


def eat_shark():
    """A wet chomp: a jaw snap and a gulp."""
    n = seconds(0.24)
    snap = apply_env(lowpass(noise(n, 33), sweep([(0, 5200), (1, 700)], n)),
                     envelope(n, 0.002, 0.035))
    gulp = apply_env(osc(sweep([(0, 420), (1, 130)], n), "sine"),
                     envelope(n, 0.01, 0.09, 0.01))
    return mix([s * 0.85 for s in snap], [g * 0.7 for g in gulp])


def eat_dino():
    """A leaf: dry rustle, all high air and no body at all."""
    n = seconds(0.26)
    rustle = highpass(noise(n, 47), 1800)
    flutter = [0.45 + 0.55 * (0.5 + 0.5 * math.sin(2 * math.pi * 34 * i / RATE))
               for i in range(n)]
    return apply_env([r * f for r, f in zip(rustle, flutter)],
                     envelope(n, 0.006, 0.10, 0.03))


def eat_cloud():
    """A star: it does not get chewed, it twinkles out."""
    n = seconds(0.30)
    out = [0.0] * n
    for k, f in enumerate((1568, 2093, 2637)):
        m = seconds(0.16)
        at = seconds(0.04 * k)
        tone = apply_env(osc([f] * m, "sine"), envelope(m, 0.002, 0.09))
        for i, v in enumerate(tone):
            if at + i < n:
                out[at + i] += v * (0.9 - 0.2 * k)
    return out


def eat_kitty():
    """A nibble: two tiny high ticks and nothing else."""
    def tick(dur, f, seed):
        n = seconds(dur)
        tone = apply_env(osc(sweep([(0, f), (1, f * 0.7)], n), "triangle"),
                         envelope(n, 0.002, 0.03))
        grit = apply_env(highpass(noise(n, seed), 2600), envelope(n, 0.001, 0.02))
        return [0.8 * a + 0.4 * b for a, b in zip(tone, grit)]

    return tick(0.07, 1400, 5) + [0.0] * seconds(0.05) + tick(0.08, 1150, 9)


def eat_trike():
    """A melon: a bright crack, then juice."""
    n = seconds(0.28)
    juice = apply_env(lowpass(noise(n, 59), sweep([(0, 2400), (1, 500)], n)),
                      envelope(n, 0.03, 0.14, 0.02))
    return mix(_crunch(0.16, 1400, 5200, 3, grains=5), [j * 0.55 for j in juice])


# ------------------------------------------------------------------- interface
#
# Until now the app made almost no sound when you touched it: eight tap sounds, eight
# eating crunches, a victory jingle and the title loop, and every button, chip, row
# and screen change silent. For a five-year-old that is the difference between a toy
# and a form.
#
# These are the ones that fire most often in the whole app, so they are the ones that
# can turn into a headache. Short, soft, and pitched well clear of the buddy sounds so
# a tap never competes with the character answering it.


def ui_tap():
    """A soft wooden pock. Every chip, row and card."""
    n = seconds(0.09)
    body = resonator(noise(n, 101), sweep([(0, 1500), (1, 900)], n), q=7.0)
    click = apply_env(osc(sweep([(0, 900), (1, 480)], n), "sine"),
                      envelope(n, 0.001, 0.028))
    return mix(apply_env(body, envelope(n, 0.001, 0.05)), [c * 0.55 for c in click])


def ui_confirm():
    """Two notes up a fourth. The big green button, and nothing else."""
    n = seconds(0.26)
    out = [0.0] * n
    for k, (delay, freq) in enumerate(((0.00, 784), (0.07, 1047))):
        m = seconds(0.17)
        at = seconds(delay)
        tone = apply_env(osc([freq] * m, "triangle"), envelope(m, 0.003, 0.10))
        for i, v in enumerate(tone):
            if at + i < n:
                out[at + i] += v * (0.8 - 0.15 * k)
    return out


def ui_page():
    """A page turning: a short band of air sweeping downward."""
    n = seconds(0.20)
    air = lowpass(noise(n, 137), sweep([(0, 5200), (1, 900)], n))
    air = highpass(air, 700)
    return apply_env(air, envelope(n, 0.02, 0.11, 0.01))


# --------------------------------------------------------------- goal and clock


def cue_goal():
    """The goal opening: a rising three-note flourish with a shimmer on top."""
    n = seconds(0.62)
    out = [0.0] * n
    for k, freq in enumerate((523, 659, 880)):
        m = seconds(0.34)
        at = seconds(0.09 * k)
        tone = apply_env(osc([freq] * m, "triangle"), envelope(m, 0.004, 0.20))
        shimmer = apply_env(osc([freq * 2.01] * m, "sine"), envelope(m, 0.004, 0.12))
        for i in range(m):
            if at + i < n:
                out[at + i] += tone[i] * 0.75 + shimmer[i] * 0.22
    return out


def cue_milestone():
    """Halfway: two soft bell partials, once. Easy to miss, which is the point."""
    n = seconds(0.55)
    out = [0.0] * n
    for ratio, gain, decay in ((1.0, 1.0, 0.30), (2.76, 0.34, 0.16)):
        partial = apply_env(osc([1175 * ratio] * n, "sine"), envelope(n, 0.003, decay))
        for i, v in enumerate(partial):
            out[i] += v * gain
    return out


def cue_tick():
    """The last ten seconds. A dry click, no pitch to speak of, nothing ominous."""
    n = seconds(0.06)
    body = resonator(noise(n, 211), 2400, q=14.0)
    return apply_env(body, envelope(n, 0.001, 0.03))


# ------------------------------------------------------------------- reactions


def poke_giggle():
    """Three rising chirps. The second poke."""
    out = []
    for k, freq in enumerate((620, 760, 910)):
        m = seconds(0.07)
        tone = osc(sweep([(0, freq), (1, freq * 1.25)], m), "triangle")
        out += apply_env(tone, envelope(m, 0.004, 0.035)) + [0.0] * seconds(0.02)
    return out


def poke_squeak():
    """A rubber-toy squeak: one steep rise and fall through a narrow formant."""
    n = seconds(0.16)
    pitch = sweep([(0, 700), (0.45, 1650), (1, 820)], n)
    voice = resonator(osc(pitch, "saw"), sweep([(0, 1200), (1, 1900)], n), q=9.0)
    return apply_env(voice, envelope(n, 0.006, 0.07, 0.02))


def poke_spin():
    """The third poke, when the buddy commits: air past a turning body."""
    n = seconds(0.34)
    air = lowpass(noise(n, 173), sweep([(0, 300), (0.4, 1400), (1, 260)], n))
    air = highpass(air, 130)
    swell = [0.25 + 0.75 * math.sin(math.pi * i / n) for i in range(n)]
    return apply_env([a * s for a, s in zip(air, swell)], envelope(n, 0.05, 0.16, 0.06))


# ------------------------------------------------------------------ activities
#
# One per Art.ACT_*, in that order, played when a task becomes the active one -- the
# cue for what to do next, which is more use to a child who cannot read the task name
# than a cue for what was just finished. Completion already plays the buddy's own
# sound, and two cues on one event is mud.


def _scrub(dur, low, high, rate, seed):
    """Back-and-forth friction: noise under a tremolo at brushing speed."""
    n = seconds(dur)
    air = lowpass(noise(n, seed), sweep([(0, high), (1, low)], n))
    air = highpass(air, low * 0.5)
    # Deep, so the strokes genuinely separate. At a shallower depth the gaps never
    # dropped far enough to read as strokes at all and a toothbrush measured as one
    # continuous hiss -- the same shape as a zip, which is what it then collided with.
    strokes = [0.06 + 0.94 * abs(math.sin(math.pi * rate * i / RATE)) for i in range(n)]
    # Held, then decaying. With a bare attack-decay the envelope was down to 8% before
    # the second stroke arrived, so every scrub was one fading hiss however deep the
    # tremolo -- audibly wrong for a toothbrush, and measurably the same shape as a zip.
    return apply_env([a * s for a, s in zip(air, strokes)],
                     envelope(n, 0.02, dur * 0.35, dur * 0.5))


def act_wake():
    """An alarm: two short chirps on one pitch."""
    out = []
    for _ in range(2):
        m = seconds(0.08)
        out += apply_env(osc([1320] * m, "square"), envelope(m, 0.003, 0.04))
        out += [0.0] * seconds(0.05)
    return [v * 0.6 for v in out]


def act_bath():
    """A flush: a wide band of water falling in pitch."""
    n = seconds(0.42)
    water = lowpass(noise(n, 301), sweep([(0, 3600), (1, 700)], n))
    gurgle = [0.7 + 0.3 * math.sin(2 * math.pi * 7 * i / RATE) for i in range(n)]
    return apply_env([w * g for w, g in zip(water, gurgle)], envelope(n, 0.04, 0.20, 0.08))


def act_dress():
    """A zip: a fast rattle rising in pitch."""
    n = seconds(0.26)
    teeth = [0.0] * n
    rng = random.Random(7)
    step = seconds(0.006)
    for at in range(0, n - step, step):
        grain = apply_env(noise(step, rng.randrange(9999)), envelope(step, 0.0005, 0.004))
        for i, v in enumerate(grain):
            teeth[at + i] += v
    teeth = resonator(teeth, sweep([(0, 2400), (1, 5600)], n), q=6.0)
    teeth = highpass(teeth, 1400)
    return apply_env(teeth, envelope(n, 0.01, 0.12, 0.06))


def act_eat():
    """Cereal: a spoon on china, then a crunch."""
    n = seconds(0.30)
    ping = apply_env(osc([2093] * n, "sine"), envelope(n, 0.002, 0.06))
    return mix([p * 0.45 for p in ping], _crunch(0.30, 800, 3000, 23, grains=8))


def act_brush():
    """Brushing teeth: fast, bright friction."""
    return _scrub(0.42, 900, 5200, 6.5, 311)


def act_hair():
    """A comb: slower friction, and lower, through hair rather than enamel."""
    return _scrub(0.40, 400, 2200, 3.0, 317)


def act_wash():
    """A tap running into a basin."""
    n = seconds(0.40)
    stream = lowpass(noise(n, 331), sweep([(0, 2200), (1, 1500)], n))
    stream = highpass(stream, 800)
    drops = [0.0] * n
    for delay, freq in ((0.12, 1500), (0.24, 1900), (0.33, 1250)):
        m = seconds(0.05)
        at = seconds(delay)
        drop = apply_env(osc(sweep([(0, freq), (1, freq * 2.1)], m), "sine"),
                         envelope(m, 0.002, 0.026))
        for i, v in enumerate(drop):
            drops[at + i] += v * 0.35
    return mix(apply_env(stream, envelope(n, 0.05, 0.22, 0.06)), drops)


def act_shoes():
    """Two soft footfalls on a wooden floor."""
    def step(dur, freq, seed):
        m = seconds(dur)
        thud = apply_env(osc(sweep([(0, freq), (1, freq * 0.6)], m), "sine"),
                         envelope(m, 0.002, 0.05))
        tap = apply_env(lowpass(noise(m, seed), 2600), envelope(m, 0.001, 0.02))
        return [0.85 * t + 0.4 * k for t, k in zip(thud, tap)]

    return step(0.13, 190, 41) + [0.0] * seconds(0.08) + step(0.15, 160, 43)


def act_pack():
    """A buckle: a hard plastic click, twice, close together."""
    def click(seed):
        m = seconds(0.05)
        body = resonator(noise(m, seed), 2900, q=11.0)
        return apply_env(body, envelope(m, 0.001, 0.022))

    return click(53) + [0.0] * seconds(0.035) + click(59)


def act_jacket():
    """A coat going on: a soft rustle of fabric, with a press-stud at the end."""
    n = seconds(0.34)
    cloth = highpass(noise(n, 401), 1600)
    folds = [0.3 + 0.7 * abs(math.sin(math.pi * 2.2 * i / RATE)) for i in range(n)]
    body = apply_env([c * f for c, f in zip(cloth, folds)], envelope(n, 0.03, 0.16, 0.04))
    stud = [0.0] * n
    at = seconds(0.24)
    snap = apply_env(resonator(noise(seconds(0.04), 409), 2200, q=10.0),
                     envelope(seconds(0.04), 0.001, 0.018))
    for i, v in enumerate(snap):
        if at + i < n:
            stud[at + i] += v * 0.8
    return mix(body, stud)


def act_pet():
    """A collar bell: two small inharmonic partials, jingled twice."""
    out = [0.0] * seconds(0.36)
    for delay in (0.0, 0.09):
        m = seconds(0.22)
        at = seconds(delay)
        for ratio, gain in ((1.0, 1.0), (2.41, 0.45)):
            partial = apply_env(osc([2637 * ratio] * m, "sine"), envelope(m, 0.002, 0.09))
            for i, v in enumerate(partial):
                if at + i < len(out):
                    out[at + i] += v * gain * 0.55
    return out


def act_vitamin():
    """A pill bottle: a handful of hard little grains shaken once."""
    n = seconds(0.34)
    out = [0.0] * n
    rng = random.Random(67)
    for _ in range(26):
        at = int(rng.uniform(0.0, 0.82) * n)
        m = min(n - at, seconds(0.012))
        if m <= 2:
            continue
        grain = resonator(noise(m, rng.randrange(9999)),
                          rng.uniform(1100, 2600), q=9.0)
        grain = apply_env(grain, envelope(m, 0.0008, 0.006))
        for i, v in enumerate(grain):
            out[at + i] += v * rng.uniform(0.5, 1.0)
    return apply_env(out, envelope(n, 0.002, 0.14))


# --------------------------------------------------------------------- the song
#
# A loop for the title screen. Music box over a plucked bass and a soft shaker, in
# C major at a walking tempo -- the point is that it can run for ten minutes behind a
# child choosing a buddy without anyone in the house wanting it to stop.
#
# The tune is its own: an eight-bar phrase over I-IV-ii-V-I-vi-IV/V-I, with the melody
# sitting on chord tones. It resolves on the downbeat of bar eight and then turns
# around on the dominant, which is what carries the ear back to the top -- an earlier
# draft simply held the last note for the whole bar and left two beats of silence at
# the loop point, so the music appeared to stop and start every eighteen seconds.
# The tail past the loop is folded back over the opening, so the seam is inaudible.

BPM = 108
BEAT = 60.0 / BPM

# Semitones above middle C, or None for a rest.
NOTES = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def pitch(name):
    """'E5' -> hertz. Octave 4 holds middle C."""
    if name is None:
        return None
    step = NOTES[name[0]]
    octave = int(name[1])
    return 261.625565 * (2.0 ** ((step + (octave - 4) * 12) / 12.0))


# (note, beats). Two bars per line.
MELODY = [
    ("C5", 1), ("E5", 1), ("G5", 1), ("E5", 1),      # I
    ("F5", 1), ("E5", 1), ("D5", 2),                 # IV
    ("D5", 1), ("F5", 1), ("A5", 1), ("F5", 1),      # ii
    ("G5", 1), ("F5", 1), ("E5", 2),                 # V
    ("E5", 1), ("G5", 1), ("C6", 1), ("G5", 1),      # I
    ("A5", 1), ("G5", 1), ("E5", 2),                 # vi
    ("F5", 1), ("E5", 1), ("D5", 1), ("G4", 1),      # IV then V
    ("C5", 2), ("G4", 1), ("B4", 1),                 # I, then a turnaround home
]

# One chord a bar, under everything. Every other voice here is plucked, so without
# this the loop thinned to near silence at the end of each two-bar phrase, three times
# a lap -- a texture that reads as the music cutting out rather than breathing.
CHORDS = [
    (["C4", "E4", "G4"], 4),   (["F3", "A3", "C4"], 4),
    (["D4", "F4", "A4"], 4),   (["G3", "B3", "D4"], 4),
    (["C4", "E4", "G4"], 4),   (["A3", "C4", "E4"], 4),
    (["F3", "A3", "C4"], 2),   (["G3", "B3", "D4"], 2),
    (["C4", "E4", "G4"], 2),   (["G3", "B3", "D4"], 2),
]

BASS = [
    ("C3", 2), ("G3", 2), ("F3", 2), ("C4", 2),
    ("D3", 2), ("A3", 2), ("G3", 2), ("D4", 2),
    ("C3", 2), ("G3", 2), ("A3", 2), ("E4", 2),
    ("F3", 2), ("G3", 2), ("C3", 2), ("G3", 2),
]


def musicbox(freq, dur):
    """A struck-metal ping: a few harmonics, each decaying faster than the last."""
    n = seconds(dur)
    out = [0.0] * n
    for mult, gain, decay in ((1.0, 1.0, 0.62), (2.0, 0.34, 0.30),
                              (4.01, 0.13, 0.16), (6.0, 0.05, 0.10)):
        v = apply_env(osc([freq * mult] * n, "sine"), envelope(n, 0.003, decay * dur))
        for i, x in enumerate(v):
            out[i] += x * gain
    return out


def pluck(freq, dur):
    n = seconds(dur)
    body = osc([freq] * n, "triangle")
    body = lowpass(body, sweep([(0, 1800), (1, 500)], n))
    return apply_env(body, envelope(n, 0.006, 0.34 * dur))


def pad(freq, dur):
    """A soft held tone: two slightly detuned triangles, filtered down and eased in."""
    n = seconds(dur)
    a = osc([freq] * n, "triangle")
    b = osc([freq] * n, "triangle", detune=0.6)
    body = lowpass([0.5 * (x + y) for x, y in zip(a, b)], 1100)
    # Held, not plucked: a long hold and a short release. Shaped as a decay it faded
    # out well before its own bar was over and did nothing for the gaps it is here for.
    return apply_env(body, envelope(n, 0.16, 0.30, dur * 0.72))


def shaker(dur, seed):
    n = seconds(dur)
    return apply_env(highpass(noise(n, seed), 4200), envelope(n, 0.002, 0.045))


def place(track, start, layer, gain):
    end = start + len(layer)
    if end > len(track):
        track.extend([0.0] * (end - len(track)))
    for i, v in enumerate(layer):
        track[start + i] += v * gain


def title_song():
    total_beats = sum(d for _, d in MELODY)
    length = seconds(total_beats * BEAT)
    # Room past the loop point for the final note's tail, folded back in below.
    track = [0.0] * (length + seconds(1.2))

    at = 0.0
    for name, beats in MELODY:
        f = pitch(name)
        if f is not None:
            # Ring into the next note rather than stopping dead on it.
            place(track, seconds(at * BEAT), musicbox(f, beats * BEAT + 0.45), 0.5)
        at += beats

    at = 0.0
    for names, beats in CHORDS:
        for name in names:
            place(track, seconds(at * BEAT), pad(pitch(name), beats * BEAT + 0.3), 0.13)
        at += beats

    at = 0.0
    for name, beats in BASS:
        place(track, seconds(at * BEAT), pluck(pitch(name), beats * BEAT * 0.9), 0.42)
        at += beats

    # Shaker on the offbeats, a touch softer on the weak ones.
    half = 0
    while half < total_beats * 2:
        gain = 0.16 if half % 4 == 2 else 0.09
        place(track, seconds(half * BEAT * 0.5), shaker(0.09, 700 + half), gain)
        half += 1

    # Fold the tail back over the start so the loop seam is continuous.
    body = track[:length]
    for i, v in enumerate(track[length:]):
        if i < len(body):
            body[i] += v
    return body


#: Tracks MediaPlayer plays on repeat, which must not carry an edge fade.
LOOPING = {"title_song"}

SOUNDS = {
    "title_song": title_song,
    "buddy_bee_sound": bee,
    "buddy_pug_sound": pug,
    "buddy_kitty_sound": kitty,
    "buddy_shark_sound": shark,
    "buddy_dino_sound": dino,
    "buddy_cloud_sound": cloud,
    "buddy_burger_sound": burger,
    "buddy_trike_sound": trike,
    "buddy_burger_eat": eat_burger,
    "buddy_bee_eat": eat_bee,
    "buddy_pug_eat": eat_pug,
    "buddy_shark_eat": eat_shark,
    "buddy_dino_eat": eat_dino,
    "buddy_cloud_eat": eat_cloud,
    "buddy_kitty_eat": eat_kitty,
    "buddy_trike_eat": eat_trike,

    "ui_tap": ui_tap,
    "ui_confirm": ui_confirm,
    "ui_page": ui_page,

    "cue_goal": cue_goal,
    "cue_milestone": cue_milestone,
    "cue_tick": cue_tick,

    "poke_giggle": poke_giggle,
    "poke_squeak": poke_squeak,
    "poke_spin": poke_spin,

    # In Art.ACT_* order. The names are what MainActivity looks them up by.
    "act_wake": act_wake,
    "act_bath": act_bath,
    "act_dress": act_dress,
    "act_eat": act_eat,
    "act_brush": act_brush,
    "act_hair": act_hair,
    "act_wash": act_wash,
    "act_shoes": act_shoes,
    "act_pack": act_pack,
    "act_jacket": act_jacket,
    "act_pet": act_pet,
    "act_vitamin": act_vitamin,
}


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        here, "..", "app", "src", "main", "res", "raw")
    out_dir = os.path.abspath(out_dir)
    os.makedirs(out_dir, exist_ok=True)
    for name, make in sorted(SOUNDS.items()):
        path = os.path.join(out_dir, name + ".wav")
        samples = write_wav(path, make(), loop=name in LOOPING)
        print("%-22s %5d samples  %5.2fs  %6d bytes"
              % (name, samples, samples / RATE, os.path.getsize(path)))


if __name__ == "__main__":
    main()
