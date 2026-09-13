#!/usr/bin/env python3
"""Synthesises the app's sounds, for the slots that have no recording.

It used to make all of them, victory fanfares included -- the docstring said
"except the victory jingle" long after that stopped being true. Now roughly a
third of res/raw is produced audio brought in by tools/importsounds.py, whose
table is the record of which slot is which. Running this script overwrites
every name in SOUNDS below, so re-run importsounds.py after it to put the
recordings back.

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
import contextlib
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


def write_wav(path, sig, loop=False, rate=None, peak=0.82):
    data = array.array("h", (int(s * 32767) for s in normalise(sig, peak=peak, loop=loop)))
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate or RATE)
        w.writeframes(data.tobytes())
    return len(data)


@contextlib.contextmanager
def at_rate(rate):
    """Renders at a different sample rate for the duration of the block.

    Every helper in this file reads the module-level RATE, which is the right shape
    for thirty-seven sounds that all want the same one. The victory fanfares are the
    exception: they carry drums, and a cymbal with nothing above 11 kHz is most of
    what makes synthesised percussion sound cheap. Swapping the global for the length
    of a render is blunt, but it is a single-threaded build script and the honest
    alternative -- threading a rate argument through twenty functions -- would make
    every recipe in the file noisier to read for the sake of eight of them.
    """
    global RATE
    was = RATE
    RATE = rate
    try:
        yield
    finally:
        RATE = was


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


# ============================================================ the victory kit
#
# Everything below exists for the eight Mission Complete fanfares, and none of it
# existed before them: until now the loudest thing in this file was a shaker.
#
# The sound being replaced measured like this -- 90% of its energy in one octave
# around 1 kHz, nothing at all below 350 Hz or above 1.7 kHz, peaking at 38% of full
# scale. One monophonic beep line. What follows is the other three quarters of a
# record: something underneath it, something hitting, and somewhere for it to happen.


def unit(sig):
    """Scales to a peak of 1, so a gain in an arrangement means what it says.

    Every instrument below ends with this. Raw, they came out anywhere between 0.13
    (tambourine) and 2.13 (twang), which would have made every gain in the eight
    arrangements a per-instrument fudge factor rather than a level.
    """
    high = max((abs(v) for v in sig), default=0.0)
    return list(sig) if high < 1e-9 else [v / high for v in sig]


# ------------------------------------------------------------------ percussion

def kick(dur=0.28, top=150.0, bottom=48.0, click=0.5):
    """A pitch drop is what a kick drum is. The click on top is the beater."""
    n = seconds(dur)
    body = osc(sweep([(0, top), (0.12, bottom * 1.4), (1, bottom)], n), "sine")
    body = apply_env(body, envelope(n, 0.002, dur * 0.55))
    beater = apply_env(lowpass(noise(n, 811), 3200), envelope(n, 0.0005, 0.008))
    return unit([b + k * click * 0.35 for b, k in zip(body, beater)])


def snare(dur=0.20, tone=190.0, snap=1.0, brushed=False):
    """Two detuned tuned heads under a band of noise. Brushed swaps crack for wash."""
    n = seconds(dur)
    head = mix(osc([tone] * n, "sine"), [0.7 * v for v in osc([tone * 1.48] * n, "sine")])
    head = apply_env(head, envelope(n, 0.001, dur * 0.30))
    wires = noise(n, 823)
    wires = highpass(lowpass(wires, 7200 if not brushed else 4200), 900)
    wires = apply_env(wires, envelope(n, 0.02 if brushed else 0.001,
                                      dur * (0.85 if brushed else 0.45)))
    return unit([0.55 * h + snap * (0.5 if brushed else 0.85) * w
                 for h, w in zip(head, wires)])


def tom(dur=0.34, top=190.0, bottom=95.0):
    """A kick with a longer, shallower fall and some skin on it."""
    n = seconds(dur)
    body = apply_env(osc(sweep([(0, top), (1, bottom)], n), "sine"),
                     envelope(n, 0.002, dur * 0.6))
    skin = apply_env(lowpass(noise(n, 829), 2400), envelope(n, 0.001, 0.02))
    return unit([b + s * 0.22 for b, s in zip(body, skin)])


def hat(dur=0.05, open_=False):
    """Metal, so: noise squeezed high and hard, long only when it is open."""
    n = seconds(dur)
    metal = highpass(noise(n, 839), 7000)
    return unit(apply_env(metal, envelope(n, 0.0004, dur * (0.9 if open_ else 0.35))))


def crash(dur=1.6, seed=853, bright=1.0):
    """A cymbal: a wide noise wash with a few resonances riding it, decaying slowly."""
    n = seconds(dur)
    wash = highpass(noise(n, seed), 2600 * bright)
    wash = apply_env(wash, envelope(n, 0.004, dur * 0.55))
    out = list(wash)
    for f, gain in ((3100, 0.30), (4700, 0.22), (6900, 0.16)):
        ring = apply_env(resonator(noise(n, seed + int(f)), f * bright, q=26.0),
                         envelope(n, 0.003, dur * 0.7))
        for i, v in enumerate(ring):
            out[i] += v * gain
    return unit(out)


def gong(dur=2.2, base=110.0):
    """Low and inharmonic, with the bloom a struck sheet of metal has."""
    n = seconds(dur)
    out = [0.0] * n
    for ratio, gain, decay in ((1.0, 1.0, 0.9), (1.62, 0.6, 0.7), (2.34, 0.42, 0.5),
                               (3.71, 0.26, 0.34), (5.18, 0.15, 0.22)):
        partial = apply_env(osc([base * ratio] * n, "sine"), envelope(n, 0.02, dur * decay))
        for i, v in enumerate(partial):
            out[i] += v * gain
    strike = apply_env(lowpass(noise(seconds(0.08), 857), 3000),
                       envelope(seconds(0.08), 0.001, 0.04))
    for i, v in enumerate(strike):
        out[i] += v * 0.5
    return unit(out)


def clap(dur=0.24):
    """Three fast slaps and a room tail. One burst reads as a snare, not as hands."""
    n = seconds(dur)
    out = [0.0] * n
    for k, delay in enumerate((0.0, 0.012, 0.026)):
        m = seconds(0.03)
        at = seconds(delay)
        slap = apply_env(highpass(lowpass(noise(m, 863 + k), 5200), 1100),
                         envelope(m, 0.0004, 0.012))
        for i, v in enumerate(slap):
            out[at + i] += v * (1.0 - 0.15 * k)
    tail = apply_env(highpass(lowpass(noise(n, 877), 4200), 1400),
                     envelope(n, 0.03, dur * 0.7))
    return unit([o + t * 0.35 for o, t in zip(out, tail)])


def woodblock(dur=0.09, freq=1150.0):
    """Dry, pitched and over immediately."""
    n = seconds(dur)
    body = resonator(noise(n, 881), freq, q=22.0)
    tone = osc([freq] * n, "sine")
    return unit(apply_env([0.7 * b + 0.5 * t for b, t in zip(body, tone)],
                          envelope(n, 0.0006, dur * 0.35)))


def tambourine(dur=0.22):
    """A handful of jingles, which is a scatter of tiny bright grains, not one hit."""
    n = seconds(dur)
    out = [0.0] * n
    rng = random.Random(887)
    for _ in range(14):
        at = int(rng.uniform(0.0, 0.30) * n)
        m = min(n - at, seconds(0.02))
        if m <= 2:
            continue
        jingle = apply_env(resonator(noise(m, rng.randrange(9999)),
                                     rng.uniform(5200, 9500), q=18.0),
                           envelope(m, 0.0005, 0.010))
        for i, v in enumerate(jingle):
            out[at + i] += v * rng.uniform(0.5, 1.0)
    return unit(apply_env(out, envelope(n, 0.002, dur * 0.5)))


# --------------------------------------------------------------- melodic voices

def brass(freq, dur, detune=7.0, bite=1.0):
    """Detuned saws opening through a filter. The bend on the attack is the lip."""
    n = seconds(dur)
    bend = sweep([(0, freq * 0.985), (0.05, freq), (1, freq)], n)
    stack = mix(osc(bend, "saw"),
                osc(bend, "saw", detune=detune),
                [0.7 * v for v in osc(bend, "saw", detune=-detune * 0.6)])
    body = lowpass(stack, sweep([(0, freq * 2.0),
                                 (0.09, freq * 7.0 * bite),
                                 (1, freq * 3.2)], n))
    return unit(apply_env(body, envelope(n, 0.012, dur * 0.35, dur * 0.45)))


def square_lead(freq, dur, duty=0.5, vibrato=0.0):
    """Chiptune. A pulse, optionally wobbled, with the hard edges left on."""
    n = seconds(dur)
    freqs = [freq] * n
    if vibrato > 0.0:
        freqs = [f * (1.0 + vibrato * 0.012 * math.sin(2 * math.pi * 6.5 * i / RATE))
                 for i, f in enumerate(freqs)]
    out = []
    phase = 0.0
    for f in freqs:
        phase += f / RATE
        phase -= math.floor(phase)
        out.append(1.0 if phase < duty else -1.0)
    return unit(apply_env(out, envelope(n, 0.004, dur * 0.30, dur * 0.5)))


def glock(freq, dur):
    """Struck metal bar: bright, a touch inharmonic, and gone quickly."""
    n = seconds(dur)
    out = [0.0] * n
    for ratio, gain, decay in ((1.0, 1.0, 0.5), (2.76, 0.45, 0.22),
                               (5.40, 0.20, 0.12), (8.93, 0.08, 0.07)):
        partial = apply_env(osc([freq * ratio] * n, "sine"), envelope(n, 0.001, dur * decay))
        for i, v in enumerate(partial):
            out[i] += v * gain
    return unit(out)


def marimba(freq, dur):
    """Wood, so the fourth harmonic and almost nothing else, with a soft mallet."""
    n = seconds(dur)
    body = apply_env(osc([freq] * n, "sine"), envelope(n, 0.004, dur * 0.42))
    fourth = apply_env(osc([freq * 4.0] * n, "sine"), envelope(n, 0.003, dur * 0.13))
    mallet = apply_env(lowpass(noise(n, 907), 2200), envelope(n, 0.0008, 0.012))
    return unit([b + f * 0.28 + m * 0.18 for b, f, m in zip(body, fourth, mallet)])


def twang(freq, dur, tremolo=11.0):
    """Surf guitar: a saw through a resonant filter, picked fast."""
    n = seconds(dur)
    body = osc([freq] * n, "saw")
    body = resonator(body, freq * 2.6, q=3.0)
    body = mix(body, [0.5 * v for v in lowpass(osc([freq] * n, "saw"), freq * 5)])
    if tremolo > 0.0:
        body = [v * (0.45 + 0.55 * abs(math.sin(math.pi * tremolo * i / RATE)))
                for i, v in enumerate(body)]
    return unit(apply_env(body, envelope(n, 0.004, dur * 0.4, dur * 0.35)))


def upright(freq, dur):
    """A plucked bass with body: fundamental, a fifth of second harmonic, finger noise."""
    n = seconds(dur)
    body = mix(osc([freq] * n, "triangle"),
               [0.4 * v for v in osc([freq * 2] * n, "sine")])
    body = lowpass(body, sweep([(0, freq * 9), (1, freq * 3)], n))
    finger = apply_env(lowpass(noise(n, 911), 1600), envelope(n, 0.0008, 0.010))
    return unit([b + f * 0.12 for b, f in
                 zip(apply_env(body, envelope(n, 0.005, dur * 0.5)), finger)])


# ----------------------------------------------------------------------- space
#
# The single biggest difference between "a synthesiser" and "a recording" that can be
# had without samples. Everything above is dry and happens at the listener's ear;
# these put it in a room.

def reverb(sig, room=0.82, damp=0.28, wet=0.30):
    """Schroeder: four parallel combs into two allpass sections."""
    n = len(sig)
    out = [0.0] * n
    for delay_s, tweak in ((0.0297, 0.0), (0.0371, 0.013), (0.0411, -0.011), (0.0437, 0.007)):
        delay = max(1, int((delay_s + tweak * 0.1) * RATE))
        buf = [0.0] * delay
        index = 0
        store = 0.0
        for i, x in enumerate(sig):
            y = buf[index]
            store = y * (1.0 - damp) + store * damp
            buf[index] = x + store * room
            index = index + 1 if index + 1 < delay else 0
            out[i] += y * 0.25
    for delay_s, feedback in ((0.0051, 0.5), (0.0126, 0.5)):
        delay = max(1, int(delay_s * RATE))
        buf = [0.0] * delay
        index = 0
        for i, x in enumerate(out):
            y = buf[index]
            buf[index] = x + y * feedback
            out[i] = y - x
            index = index + 1 if index + 1 < delay else 0
    return [d + w * wet for d, w in zip(sig, out)]


def slapback(sig, time=0.11, feedback=0.28, mix_level=0.30):
    """One short repeat feeding itself. Half of what makes a surf guitar surf."""
    n = len(sig)
    delay = max(1, int(time * RATE))
    out = list(sig)
    for i in range(delay, n):
        out[i] += out[i - delay] * feedback
    return [d + (w - d) * mix_level for d, w in zip(sig, out)]


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
    """'E5' -> hertz, and 'Eb3' and 'F#5' too. Octave 4 holds middle C.

    Accidentals were added for the victory fanfares: eight pieces in eight keys do not
    all fit in C major, and the bee's is in E flat.
    """
    if name is None:
        return None
    step = NOTES[name[0]]
    rest = name[1:]
    if rest and rest[0] in "#b":
        step += 1 if rest[0] == "#" else -1
        rest = rest[1:]
    octave = int(rest)
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


# ======================================================== the victory fanfares
#
# One per character, and eight genuinely different pieces rather than one tune in
# eight keys: a child who switches buddies should get a different celebration, not a
# transposition. What they share is a shape -- impact, hook, answer, final hit ringing
# out -- which is what keeps eight styles sounding like one app.
#
# Each runs about four and a half seconds. That is not arbitrary: MorningView routes
# to the Complete screen at 1200ms and never touches the player, so a fanfare runs to
# its natural end, and the confetti it plays under lives 2.4 to 4.2 seconds. Audio and
# streamers stop together.
#
# All eight render at 44100 (see at_rate) because they carry cymbals.


def fanfare(beats, bpm):
    """A blank track long enough for the piece plus its tail, and its beat length."""
    beat = 60.0 / bpm
    return [0.0] * seconds(beats * beat + 2.4), beat


def ring_out(sig, floor=0.006, cap=5.5, fade=0.08):
    """Cuts the track where it stops being audible, and never lets it outstay.

    The tail allowance above is deliberately generous, because a gong and a long
    reverb need somewhere to go and running out of track mid-decay is a click. What
    is left over is digital silence that still costs a hundred kilobytes a file. This
    finds the last sample above the floor, adds a short fade so the cut cannot click,
    and caps the whole thing -- the cloud pup's reverb and the trike's gong would
    otherwise ring for seven seconds under a screen whose confetti died at four.
    """
    high = max((abs(v) for v in sig), default=0.0)
    if high < 1e-9:
        return sig
    limit = high * floor
    end = len(sig)
    while end > 1 and abs(sig[end - 1]) < limit:
        end -= 1
    end = min(end + seconds(fade), len(sig), seconds(cap))
    out = list(sig[:end])
    tail = min(seconds(fade), len(out))
    for i in range(tail):
        out[len(out) - tail + i] *= 1.0 - i / tail
    return out


def chord(track, at, names, voice, dur, gain, spread=0.0):
    """Stacks one chord, optionally rolled."""
    for k, name in enumerate(names):
        place(track, seconds(at + k * spread), voice(pitch(name), dur), gain)


def victory_burger():
    """Arcade. The 'you win' everyone already knows, in C, with the edges left on."""
    track, beat = fanfare(12, 168)

    # Impact.
    place(track, 0, crash(1.5), 0.42)
    place(track, 0, kick(0.30, 170, 50), 0.85)

    # The hook: a run up the tonic triad into a held top note, answered a step down.
    hook = [("G4", 0.5), ("C5", 0.5), ("E5", 0.5), ("G5", 0.5),
            ("C6", 2.0), (None, 0.0),
            ("A5", 0.5), ("G5", 0.5), ("E5", 0.5), ("C5", 0.5),
            ("G5", 1.0), ("C6", 3.0)]
    at = 0.0
    for name, length in hook:
        if name:
            place(track, seconds(at * beat),
                  square_lead(pitch(name), length * beat * 0.95, 0.5, 0.6), 0.46)
        at += length

    # A triplet arpeggio underneath, which is the texture that says arcade.
    arp = ["C4", "E4", "G4", "C5", "G4", "E4"]
    for i in range(36):
        place(track, seconds(i * beat / 3.0),
              square_lead(pitch(arp[i % len(arp)]), beat / 3.0 * 0.8, 0.25), 0.075)

    # Bass, kick and snare on the square: no hats, this kit is three channels.
    for i, name in enumerate(["C2", "C2", "G2", "G2", "A2", "A2", "F2", "F2",
                              "C2", "G2", "C2", "C2"]):
        place(track, seconds(i * beat), square_lead(pitch(name), beat * 0.85, 0.5), 0.13)
    for i in range(12):
        place(track, seconds(i * beat), kick(0.24, 160, 48), 0.38)
        place(track, seconds((i + 0.5) * beat), snare(0.16, 210), 0.44)

    # Final hit.
    end = seconds(11 * beat)
    chord(track, 11 * beat, ["C4", "E4", "G4", "C5"], lambda f, d: square_lead(f, d, 0.5),
          1.6, 0.16)
    place(track, end, crash(1.9), 0.40)
    place(track, end, kick(0.34, 180, 46), 0.9)
    return ring_out(reverb(track, room=0.72, damp=0.36, wet=0.16))


def victory_bee():
    """Fast swing. Busy, light and slightly hurried, which is the bee all over."""
    track, beat = fanfare(14, 190)
    swing = 0.64                                   # where the offbeat eighth lands

    place(track, 0, crash(1.3, bright=1.15), 0.30)

    # Walking bass, one to the beat, straight quarters under a swung top.
    walk = ["Eb2", "G2", "Bb2", "C3", "Db3", "C3", "Bb2", "G2",
            "Eb2", "F2", "G2", "Ab2", "Bb2", "Eb3"]
    for i, name in enumerate(walk):
        place(track, seconds(i * beat), upright(pitch(name), beat * 0.92), 0.26)

    # Brushed snare on two and four, hats swung.
    for i in range(14):
        if i % 2 == 1:
            place(track, seconds(i * beat), snare(0.22, 200, 0.8, brushed=True), 0.34)
        place(track, seconds(i * beat), hat(0.05), 0.26)
        place(track, seconds((i + swing) * beat), hat(0.05), 0.19)
    for i in (0, 4, 8, 12):
        place(track, seconds(i * beat), kick(0.22, 150, 52), 0.42)

    # Muted brass stabs on the swung offbeats, and a triplet hook over the top.
    for i, names in ((1, ["G4", "Bb4", "Db5"]), (3, ["F4", "Ab4", "C5"]),
                     (5, ["Eb4", "G4", "Bb4"]), (9, ["G4", "Bb4", "Db5"]),
                     (11, ["F4", "Ab4", "C5"])):
        chord(track, (i + swing) * beat, names, lambda f, d: brass(f, d, 5.0, 0.7),
              beat * 0.5, 0.11)

    hook = [(6.0, "Bb4"), (6.33, "C5"), (6.66, "D5"), (7.0, "Eb5"), (7.5, "G5"),
            (8.0, "F5"), (8.5, "Eb5"),
            (12.0, "Bb4"), (12.33, "D5"), (12.66, "F5"), (13.0, "Bb5")]
    for at, name in hook:
        length = 0.9 if at in (7.5, 13.0) else 0.30
        place(track, seconds(at * beat), brass(pitch(name), length * beat + 0.05, 8.0), 0.40)

    end = seconds(13 * beat)
    chord(track, 13 * beat, ["Eb3", "G3", "Bb3", "D4"], lambda f, d: brass(f, d, 7.0), 1.3, 0.15)
    place(track, end, crash(1.7, bright=1.15), 0.34)
    return ring_out(reverb(track, room=0.70, damp=0.40, wet=0.18))


def victory_pug():
    """Marching band. Proud, square, and entirely certain of itself."""
    track, beat = fanfare(9, 120)

    # A snare roll into the downbeat is the whole introduction.
    for i in range(16):
        at = -0.0 + i * beat / 8.0
        if at >= 0:
            place(track, seconds(at), snare(0.09, 230, 0.7), 0.16 + 0.30 * (i / 16.0))
    place(track, seconds(beat * 2), crash(1.8), 0.40)

    # Bass drum on one and three, snare backbeat, the march underneath everything.
    for i in range(9):
        if i % 2 == 0:
            place(track, seconds(i * beat), kick(0.32, 130, 44), 0.34)
        else:
            place(track, seconds(i * beat), snare(0.18, 240, 1.0), 0.78)
        place(track, seconds((i + 0.5) * beat), snare(0.10, 240, 0.5), 0.34)
        place(track, seconds((i + 0.25) * beat), hat(0.05), 0.16)
        place(track, seconds((i + 0.75) * beat), hat(0.05), 0.16)

    # The fanfare itself: a bugle call on the tonic triad, dotted, then held.
    call = [(2.0, "D4", 0.75), (2.75, "G4", 0.25), (3.0, "B4", 0.75), (3.75, "D5", 0.25),
            (4.0, "G5", 1.5), (5.5, "D5", 0.5),
            (6.0, "B4", 0.5), (6.5, "D5", 0.5), (7.0, "G5", 2.0)]
    for at, name, length in call:
        place(track, seconds(at * beat), brass(pitch(name), length * beat + 0.12, 9.0, 1.15),
              0.46)
        # A third below, in parallel, the way a second cornet doubles a first.
        below = {"D4": "B3", "G4": "D4", "B4": "G4", "D5": "B4", "G5": "D5"}[name]
        place(track, seconds(at * beat), brass(pitch(below), length * beat + 0.12, 9.0), 0.24)

    for i, name in enumerate(["G2", "D3", "G2", "D3", "G2", "C3", "D3", "D3", "G2"]):
        place(track, seconds(i * beat), brass(pitch(name), beat * 0.42, 4.0, 0.6), 0.16)

    end = seconds(7 * beat)
    place(track, end, crash(2.2), 0.44)
    place(track, end, kick(0.36, 140, 42), 0.5)
    return ring_out(reverb(track, room=0.86, damp=0.22, wet=0.30))


def victory_shark():
    """Surf rock. Wet, twangy, and rolling in like the thing it is."""
    track, beat = fanfare(12, 160)

    # Tom roll in, then the backbeat.
    for i in range(8):
        place(track, seconds(i * beat / 4.0), tom(0.16, 220 - i * 12, 110 - i * 5),
              0.20 + 0.30 * (i / 8.0))
    place(track, seconds(2 * beat), crash(1.0, bright=0.85), 0.30)

    for i in range(2, 12):
        place(track, seconds(i * beat), kick(0.26, 150, 46) if i % 2 == 0
              else snare(0.19, 200), 0.34 if i % 2 == 0 else 0.68)
        place(track, seconds((i + 0.25) * beat), hat(0.05), 0.20)
        place(track, seconds((i + 0.5) * beat), hat(0.06), 0.42)
        place(track, seconds((i + 0.75) * beat), hat(0.05), 0.20)

    # The lick: a descending run on the A mixolydian shape, tremolo-picked.
    lick = [(2.0, "E5", 0.5), (2.5, "C#5", 0.5), (3.0, "A4", 0.5), (3.5, "E4", 0.5),
            (4.0, "F#4", 1.0), (5.0, "A4", 1.0),
            (6.0, "E5", 0.5), (6.5, "D5", 0.5), (7.0, "C#5", 0.5), (7.5, "B4", 0.5),
            (8.0, "A4", 2.0),
            (10.0, "C#5", 0.5), (10.5, "E5", 0.5), (11.0, "A5", 1.6)]
    lead = [0.0] * len(track)
    for at, name, length in lick:
        place(lead, seconds(at * beat), twang(pitch(name), length * beat + 0.10, 9.0), 0.62)
    lead = slapback(lead, time=beat / 2.0, feedback=0.24, mix_level=0.22)
    for i, v in enumerate(lead):
        track[i] += v

    for i, name in enumerate(["A2", "A2", "A2", "A2", "D2", "D2", "E2", "E2",
                              "A2", "A2", "E2", "A2"]):
        place(track, seconds(i * beat), upright(pitch(name), beat * 0.95), 0.22)

    end = seconds(11 * beat)
    chord(track, 11 * beat, ["A3", "C#4", "E4", "A4"], lambda f, d: twang(f, d, 15.0), 1.5, 0.16)
    place(track, end, crash(2.1, bright=0.8), 0.38)
    return ring_out(reverb(track, room=0.84, damp=0.26, wet=0.26))


def victory_dino():
    """Ska. Everything lands on the offbeat, which is what makes it bounce."""
    track, beat = fanfare(11, 150)

    place(track, 0, crash(1.2), 0.28)

    # Chord stabs on every 'and', short and clipped. This is the whole genre.
    stabs = [["D4", "F#4", "A4"], ["D4", "F#4", "A4"], ["G3", "B3", "D4"], ["G3", "B3", "D4"],
             ["A3", "C#4", "E4"], ["A3", "C#4", "E4"], ["D4", "F#4", "A4"], ["D4", "F#4", "A4"],
             ["G3", "B3", "D4"], ["A3", "C#4", "E4"], ["D4", "F#4", "A4"]]
    for i, names in enumerate(stabs):
        chord(track, (i + 0.5) * beat, names, lambda f, d: brass(f, d, 6.0, 0.8),
              beat * 0.28, 0.20)

    for i, name in enumerate(["D2", "A2", "G2", "D3", "A2", "E3", "D2", "A2",
                              "G2", "A2", "D2"]):
        place(track, seconds(i * beat), upright(pitch(name), beat * 0.8), 0.22)

    for i in range(11):
        place(track, seconds(i * beat), kick(0.24, 145, 47), 0.32)
        place(track, seconds((i + 0.5) * beat), woodblock(0.08, 1250), 0.34)
        if i % 2 == 1:
            place(track, seconds(i * beat), snare(0.17, 215), 0.34)

    hook = [(2.0, "D5", 0.5), (2.5, "F#5", 0.5), (3.0, "A5", 1.0),
            (4.0, "G5", 0.5), (4.5, "F#5", 0.5), (5.0, "E5", 1.0),
            (6.0, "D5", 0.5), (6.5, "E5", 0.5), (7.0, "F#5", 0.5), (7.5, "A5", 0.5),
            (8.0, "D6", 2.0)]
    for at, name, length in hook:
        place(track, seconds(at * beat), marimba(pitch(name), length * beat + 0.25), 0.62)

    end = seconds(10 * beat)
    chord(track, 10 * beat, ["D4", "F#4", "A4", "D5"], marimba, 1.4, 0.26)
    place(track, end, crash(1.6), 0.34)
    place(track, end, woodblock(0.09, 1250), 0.28)
    return ring_out(reverb(track, room=0.74, damp=0.34, wet=0.20))


def victory_cloud():
    """Music box and shimmer. The only one of the eight that floats rather than hits."""
    track, beat = fanfare(10, 132)

    place(track, 0, crash(2.6, bright=1.2), 0.16)   # more shimmer than crash

    # A rising arpeggio through F, then the tune sitting on top of it.
    arp = ["F4", "A4", "C5", "F5", "A5", "C6"]
    for i, name in enumerate(arp):
        place(track, seconds(i * beat / 3.0), glock(pitch(name), 1.1), 0.34)

    tune = [(2.0, "F5", 1.0), (3.0, "A5", 0.5), (3.5, "G5", 0.5), (4.0, "C6", 1.5),
            (5.5, "A5", 0.5), (6.0, "G5", 1.0), (7.0, "A5", 0.5), (7.5, "C6", 0.5),
            (8.0, "F6", 2.0)]
    for at, name, length in tune:
        place(track, seconds(at * beat), musicbox(pitch(name), length * beat + 0.7), 0.70)

    # A pad holding the harmony together, and a soft heartbeat under it.
    for at, names, length in ((0.0, ["F3", "A3", "C4"], 4.0),
                              (4.0, ["Bb3", "D4", "F4"], 2.0),
                              (6.0, ["C4", "E4", "G4"], 2.0),
                              (8.0, ["F3", "A3", "C4"], 2.4)):
        chord(track, at * beat, names, pad, length * beat + 0.5, 0.05)
    for i in range(10):
        place(track, seconds(i * beat), kick(0.30, 120, 44), 0.40)
        place(track, seconds((i + 0.5) * beat), shaker(0.09, 400 + i), 0.40)
        place(track, seconds(i * beat), hat(0.05), 0.22)
        place(track, seconds((i + 0.25) * beat), hat(0.05), 0.13)
        place(track, seconds((i + 0.75) * beat), hat(0.05), 0.13)
        if i % 2 == 1:
            place(track, seconds(i * beat), clap(0.22), 0.26)

    # Sparkles: high glock grains scattered over the second half.
    rng = random.Random(4242)
    for _ in range(14):
        at = rng.uniform(3.0, 9.5) * beat
        name = rng.choice(["C6", "F6", "A6", "C7"])
        place(track, seconds(at), glock(pitch(name), 0.55), rng.uniform(0.14, 0.26))

    chord(track, 8 * beat, ["F4", "A4", "C5", "F5"], musicbox, 2.6, 0.30)
    return ring_out(reverb(track, room=0.93, damp=0.14, wet=0.52))


def victory_kitty():
    """Sparkly pop. Small, quick and pleased with itself."""
    track, beat = fanfare(13, 176)

    place(track, 0, crash(1.2, bright=1.2), 0.26)
    place(track, 0, tambourine(0.3), 0.30)

    # Pizzicato bass on the beat, claps on two and four, tambourine on the eighths.
    for i, name in enumerate(["Bb2", "Bb2", "F2", "F2", "Eb2", "Eb2", "F2", "F2",
                              "Bb2", "G2", "Eb2", "F2", "Bb2"]):
        place(track, seconds(i * beat), pluck(pitch(name), beat * 0.75), 0.26)
    for i in range(13):
        place(track, seconds(i * beat), kick(0.22, 155, 50), 0.32)
        if i % 2 == 1:
            place(track, seconds(i * beat), clap(0.24), 0.40)
        place(track, seconds((i + 0.5) * beat), tambourine(0.16), 0.16)

    hook = [(1.0, "Bb4", 0.5), (1.5, "D5", 0.5), (2.0, "F5", 0.5), (2.5, "Bb5", 1.5),
            (4.0, "A5", 0.5), (4.5, "G5", 0.5), (5.0, "F5", 1.0),
            (6.0, "G5", 0.5), (6.5, "A5", 0.5), (7.0, "Bb5", 1.5),
            (9.0, "F5", 0.5), (9.5, "A5", 0.5), (10.0, "Bb5", 0.5), (10.5, "D6", 1.5)]
    for at, name, length in hook:
        place(track, seconds(at * beat), glock(pitch(name), length * beat + 0.35), 0.58)

    end = seconds(12 * beat)
    chord(track, 12 * beat, ["Bb4", "D5", "F5", "Bb5"], glock, 1.6, 0.22)
    place(track, end, crash(1.6, bright=1.25), 0.32)
    place(track, end, tambourine(0.34), 0.32)
    return ring_out(reverb(track, room=0.78, damp=0.30, wet=0.26))


def victory_trike():
    """Taiko. Heavy, slow, and it earns its major chord at the very end."""
    track, beat = fanfare(10, 126)

    place(track, 0, gong(2.8, 96), 0.44)
    place(track, 0, crash(1.1, bright=0.9), 0.30)

    # The pattern: a big low tom on the beat with a double before every other one.
    for i in range(10):
        place(track, seconds(i * beat), tom(0.42, 165, 74), 0.62)
        if i % 2 == 1:
            place(track, seconds((i - 0.33) * beat), tom(0.26, 175, 82), 0.42)
            place(track, seconds((i - 0.17) * beat), tom(0.26, 175, 82), 0.42)
        place(track, seconds((i + 0.5) * beat), tom(0.24, 210, 105), 0.30)
        # A shime -- the small high drum a taiko ensemble keeps time on. Without
        # something up here the piece had two transients in five seconds and half of
        # its energy inside one octave of low mid.
        for half in (0.0, 0.5):
            place(track, seconds((i + half) * beat), woodblock(0.07, 1900), 0.34)
        place(track, seconds((i + 0.5) * beat), hat(0.06), 0.34)

    # A drone, so the whole thing has a floor.
    place(track, 0, upright(pitch("E1"), 6.4), 0.12)
    place(track, seconds(6 * beat), upright(pitch("E1"), 4.0), 0.12)

    # Power chords: root and fifth, no third, until the last one -- which takes the
    # major third and turns nine seconds of E minor into a win.
    for at, root, fifth, length in ((0.0, "E3", "B3", 2.0), (2.0, "G3", "D4", 2.0),
                                    (4.0, "A3", "E4", 2.0), (6.0, "B3", "F#4", 2.0)):
        place(track, seconds(at * beat), brass(pitch(root), length * beat + 0.2, 10.0, 1.2), 0.05)
        place(track, seconds(at * beat), brass(pitch(fifth), length * beat + 0.2, 10.0), 0.035)

    # A line an octave above the chords. Without it the whole piece sat inside one
    # octave of low mid -- 56% of its energy between 160 and 320 Hz -- which reads as
    # a wall rather than a climb, and no amount of tom does that job.
    horn = [(0.5, "B4", 1.5), (2.0, "D5", 1.0), (3.0, "B4", 1.0),
            (4.0, "E5", 1.5), (5.5, "D5", 0.5),
            (6.0, "F#5", 1.0), (7.0, "B5", 1.0)]
    for at, name, length in horn:
        # Phrased, not held. Run end to end this line never let go of the transient
        # band, so the drums under it modulated only two to one and the whole piece
        # measured as one continuous event. Brass players breathe.
        place(track, seconds(at * beat), brass(pitch(name), length * beat * 0.62 + 0.12,
                                               12.0, 1.35), 0.52)

    end = 8 * beat
    for name, gain in (("E3", 0.14), ("B3", 0.11), ("G#4", 0.15), ("E5", 0.15)):
        place(track, seconds(end), brass(pitch(name), 2.2, 11.0, 1.3), gain)
    place(track, seconds(end), gong(3.0, 96), 0.50)
    place(track, seconds(end), tom(0.5, 160, 58), 0.9)
    place(track, seconds(end), crash(2.4, bright=0.95), 0.44)
    return ring_out(reverb(track, room=0.82, damp=0.24, wet=0.22))


#: Tracks MediaPlayer plays on repeat, which must not carry an edge fade.
LOOPING = {"title_song"}

#: Rendered at 44100 rather than the 22050 everything else uses, and normalised
#: hotter. These are the only sounds in the app with cymbals on them, and a crash
#: with nothing above 11 kHz is most of what makes synthesised percussion sound
#: cheap. The file they replace was 44100 too, so this is not a new cost.
HIFI_RATE = 44100
HIFI = {
    "victory_burger", "victory_bee", "victory_pug", "victory_shark",
    "victory_dino", "victory_cloud", "victory_kitty", "victory_trike",
}

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

    # One per character, in BuddyTheme order. Eight styles, not one tune in eight
    # keys -- see the comment above victory_burger.
    "victory_burger": victory_burger,   # arcade
    "victory_bee": victory_bee,         # fast swing
    "victory_pug": victory_pug,         # marching band
    "victory_shark": victory_shark,     # surf rock
    "victory_dino": victory_dino,       # ska
    "victory_cloud": victory_cloud,     # music box
    "victory_kitty": victory_kitty,     # sparkly pop
    "victory_trike": victory_trike,     # taiko
}


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        here, "..", "app", "src", "main", "res", "raw")
    out_dir = os.path.abspath(out_dir)
    os.makedirs(out_dir, exist_ok=True)
    for name, make in sorted(SOUNDS.items()):
        path = os.path.join(out_dir, name + ".wav")
        rate = HIFI_RATE if name in HIFI else RATE
        with at_rate(rate):
            samples = write_wav(path, make(), loop=name in LOOPING, rate=rate,
                                peak=0.94 if name in HIFI else 0.82)
        print("%-22s %5d samples  %5.2fs  %6d bytes  %d Hz"
              % (name, samples, samples / rate, os.path.getsize(path), rate))


if __name__ == "__main__":
    main()
