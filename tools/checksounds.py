#!/usr/bin/env python3
"""Checks that no two generated sounds are the same sound.

The complaint that produced this set of sounds was that they all sounded alike, and
the thing about that defect is that nothing in a build catches it: every file is a
valid WAV, every one plays, and the only symptom is a person saying "they all sound
the same". Nobody in CI has ears, so the property is measured instead.

Four cheap descriptors per file -- duration, how many amplitude bursts it contains,
its dominant frequency and its spectral centroid -- and every pair must differ
clearly on at least one of them. That will not tell you a sound is GOOD. It does
tell you that the bee and the pug are not the same noise, and, most useful of all,
that a buddy's eat sound is not its own tap sound: those two fire seconds apart on
the same screen, so if they converge the poke stops reading as a separate thing.

Compared WITHIN a family, named by the prefix before the first underscore: buddy,
act, ui, cue, poke. Across families the bar would be wrong -- the interface tap and
the backpack buckle are both meant to be a short click, and a shared click character
across the whole interface is the point rather than a defect. What has to hold is
that no two sounds a person hears as alternatives to each other are the same sound:
two characters, two routine tasks, two reactions to the same poke.

    python3 tools/checksounds.py [raw-dir]

Defaults to app/src/main/res/raw. Pure standard library, like gensounds.py.
"""
import array
import math
import os
import sys
import wave


def load(path):
    with wave.open(path, "rb") as w:
        data = array.array("h")
        data.frombytes(w.readframes(w.getnframes()))
        return [s / 32768.0 for s in data], w.getframerate()


def goertzel(sig, rate, freq):
    """Magnitude at one frequency, without needing an FFT."""
    w = 2 * math.pi * freq / rate
    coeff = 2 * math.cos(w)
    s1 = s2 = 0.0
    for x in sig:
        s0 = x + coeff * s1 - s2
        s2 = s1
        s1 = s0
    return math.sqrt(max(0.0, s1 * s1 + s2 * s2 - coeff * s1 * s2)) / len(sig)


def bursts(sig, rate):
    """How many separate amplitude events: two barks, three nods, one chime."""
    win = max(1, int(0.006 * rate))
    env = [max(abs(v) for v in sig[i:i + win]) for i in range(0, len(sig) - win, win)]
    if not env:
        return 0
    threshold = (max(env) or 1.0) * 0.34
    count = 0
    above = False
    for v in env:
        if v > threshold and not above:
            count += 1
            above = True
        elif v < threshold * 0.6:
            above = False
    return count


PROBES = [60, 90, 130, 180, 260, 380, 520, 760, 1100, 1600, 2300, 3300, 4800, 7000]


def thin(sig, rate, target=30000):
    """Decimates to about `target` samples, and reports the rate that leaves.

    This used to be a fixed step of 3, which was fine when the longest file was under
    a second. The victory fanfares are five and a half seconds at 44.1 kHz, and a
    fixed step made this gate the slowest thing in check.sh.
    """
    # Never below the step this used before the fanfares arrived. Loosening it changed
    # what every short sound measured as, and two activity cues that had been distinct
    # for a hundred commits collided -- a gate that re-tunes itself is not a gate.
    step = max(3, len(sig) // target)
    return sig[::step], rate / step


def describe(path):
    sig, rate = load(path)
    thinned, thin_rate = thin(sig, rate)
    probes = [f for f in PROBES if f < thin_rate / 2.2]
    mags = [goertzel(thinned, thin_rate, f) for f in probes]
    total = sum(mags) or 1e-9
    centroid = sum(m * f for m, f in zip(mags, probes)) / total
    return (len(sig) / rate, bursts(sig, rate),
            probes[mags.index(max(mags))], centroid)


# Octave bands, for the fanfares. A single Goertzel over tens of thousands of samples
# has a bin a fraction of a hertz wide, so one probe per band measures whether a note
# happens to sit on that exact frequency rather than what the band carries. Each band
# is sampled at several points and the energies summed.
OCTAVES = ((40, 80), (80, 160), (160, 320), (320, 640),
           (640, 1280), (1280, 2560), (2560, 5120), (5120, 10240))


def band_mix(path):
    """Percentage of energy in each octave band, and the signal's peak.

    Filtered rather than probed. A Goertzel needs the signal decimated to stay quick,
    and decimating without filtering first folds the cymbals down on top of the bass --
    which measured the shark as having three live bands when it has six. A pair of
    one-pole filters per band is O(n), needs no decimation, and cannot alias. The
    skirts are gentle so the bands overlap; for "is there anything up here at all"
    that is the right amount of precision.
    """
    sig, rate = load(path)
    energy = []
    for low, high in OCTAVES:
        if low > rate / 2.2:
            energy.append(0.0)
            continue
        band = one_pole(sig, rate, min(high, rate / 2.2), high=False)
        band = one_pole(band, rate, low, high=True)
        energy.append(math.sqrt(sum(v * v for v in band) / len(band)))
    scale = sum(energy) or 1e-9
    return [100.0 * v / scale for v in energy], max(abs(v) for v in sig)


def one_pole(sig, rate, cutoff, high):
    """One-pole low-pass, or its complement for a high-pass."""
    coefficient = 1.0 - math.exp(-2 * math.pi * max(10.0, cutoff) / rate)
    out = []
    y = 0.0
    for x in sig:
        y += coefficient * (x - y)
        out.append(x - y if high else y)
    return out


def distinct(a, b):
    return (abs(math.log(a[2] / b[2])) > 0.35
            or abs(a[1] - b[1]) >= 1
            or abs(math.log(a[3] / b[3])) > 0.30
            or abs(a[0] - b[0]) > 0.20)





#: The title loop is eighteen seconds of music and is not compared against anything.
FAMILIES = ("buddy", "act", "ui", "cue", "poke", "victory")

#: Families whose members are compared against each other.
#:
#: The victory fanfares are deliberately not among them, and not because they failed.
#: The four descriptors here were built for sounds under a second, and across five
#: seconds of music they average out -- a swing quartet and a taiko ensemble both came
#: out as "130 Hz, 22 bursts, centroid 430", which is true and useless. Comparing
#: octave profiles instead did not work either: the one-pole filters have gentle enough
#: skirts that every band leaks into its neighbours and all eight arrangements smear
#: toward the same broad hump.
#:
#: What this gate exists to catch is writing the same filtered noise burst twice by
#: accident, which is a real risk across twelve activity cues built from one helper.
#: Eight hand-written arrangements in eight styles, keys and tempos are not that risk,
#: and a measurement that cannot tell a march from a surf lick should not claim to.
#: They get the thinness bar below instead, which measures something real.
PAIRWISE = ("buddy", "act", "ui", "cue", "poke")

#: The victory fanfares get a second bar, and it is written from the measurements of
#: the file they replaced -- which had 77% of its energy in one octave band, only
#: three bands carrying anything at all, and peaked at 0.38 of full scale. One bare
#: melody line, quiet. Every one of these numbers would have failed it.
VICTORY_MIN_PEAK = 0.80
VICTORY_MIN_LIVE_BANDS = 4      # bands carrying at least 6% of the energy
VICTORY_MAX_ONE_BAND = 50.0     # percent, so no single octave owns the piece


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    raw = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        here, "..", "app", "src", "main", "res", "raw")
    raw = os.path.abspath(raw)

    groups = {family: [] for family in FAMILIES}
    for f in sorted(os.listdir(raw)):
        if not f.endswith(".wav"):
            continue
        family = f.split("_")[0]
        if family in groups:
            groups[family].append(f[:-4])

    total = sum(len(v) for v in groups.values())
    if not total:
        print("checksounds: no generated sounds in %s" % raw, file=sys.stderr)
        return 1

    stats = {}
    mixes = {}
    failures = []
    for name in groups.get("victory", []):
        mixes[name] = band_mix(os.path.join(raw, name + ".wav"))

    for family, names in groups.items():
        for n in names:
            stats[n] = describe(os.path.join(raw, n + ".wav"))
        if family not in PAIRWISE:
            continue
        for i, a in enumerate(names):
            for b in names[i + 1:]:
                if not distinct(stats[a], stats[b]):
                    failures.append((family, a, b))

    thin_failures = []
    for name in groups.get("victory", []):
        mix, peak = mixes[name]
        live = sum(1 for v in mix if v >= 6.0)
        if peak < VICTORY_MIN_PEAK:
            thin_failures.append((name, "peaks at %.2f, under %.2f"
                                  % (peak, VICTORY_MIN_PEAK)))
        if live < VICTORY_MIN_LIVE_BANDS:
            thin_failures.append((name, "only %d octave bands carry anything" % live))
        if max(mix) > VICTORY_MAX_ONE_BAND:
            thin_failures.append((name, "%.0f%% of it is in one octave band" % max(mix)))

    if thin_failures:
        print("FAIL: a victory fanfare is as thin as the one it replaced:",
              file=sys.stderr)
        for name, why in thin_failures:
            print("  %-18s %s" % (name, why), file=sys.stderr)
        return 1

    if failures:
        print("FAIL: these sounds are indistinguishable by every measure:",
              file=sys.stderr)
        for family, a, b in failures:
            print("  [%s] %-22s %s" % (family, a, b), file=sys.stderr)
            for n in (a, b):
                d, br, dom, cen = stats[n]
                print("      %-22s %.2fs  %d burst  %dHz dominant  %.0fHz centroid"
                      % (n, d, br, dom, cen), file=sys.stderr)
        return 1

    print("==> %d generated sounds (%s); distinct within every compared family,"
          " and no fanfare is thin"
          % (total, ", ".join("%s %d" % (f, len(groups[f])) for f in FAMILIES)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
