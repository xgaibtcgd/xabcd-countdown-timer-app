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


def describe(path):
    sig, rate = load(path)
    probes = [f for f in PROBES if f < rate / 6]
    mags = [goertzel(sig[::3], rate / 3, f) for f in probes]
    total = sum(mags) or 1e-9
    centroid = sum(m * f for m, f in zip(mags, probes)) / total
    return (len(sig) / rate, bursts(sig, rate),
            probes[mags.index(max(mags))], centroid)


def distinct(a, b):
    return (abs(math.log(a[2] / b[2])) > 0.35
            or abs(a[1] - b[1]) >= 1
            or abs(math.log(a[3] / b[3])) > 0.30
            or abs(a[0] - b[0]) > 0.20)


#: Everything else in res/raw is not ours to compare: the title loop is eighteen
#: seconds of music and the victory jingle is not generated at all.
FAMILIES = ("buddy", "act", "ui", "cue", "poke")


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
    failures = []
    for family, names in groups.items():
        for n in names:
            stats[n] = describe(os.path.join(raw, n + ".wav"))
        for i, a in enumerate(names):
            for b in names[i + 1:]:
                if not distinct(stats[a], stats[b]):
                    failures.append((family, a, b))

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

    print("==> %d generated sounds (%s), distinct within every family"
          % (total, ", ".join("%s %d" % (f, len(groups[f])) for f in FAMILIES)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
