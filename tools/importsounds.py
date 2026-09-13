"""Bring the produced audio in MorningMission-Animation-Pack-Batch2/raw into res/raw.

The app's forty-six sounds were all synthesised by tools/gensounds.py. Some of them
now have a produced recording to replace them, uploaded under the generator's names
rather than the app's, so this is the translation table -- and, being a table, it is
also the record of which slots are produced and which are still synthesised.

    python3 tools/importsounds.py            # write into app/src/main/res/raw
    python3 tools/importsounds.py --dry-run  # say what it would do

WHY THIS IS A SCRIPT AND NOT A ONE-OFF COPY. Two of the three things it does need
deciding per file rather than once:

RATE. The app's cues are 22050 Hz because a synthesised beep has nothing above that
to lose. A recording might. Measured across the uploads, it splits cleanly and not
the way convention would have guessed: the buddy voices, the fanfares and
task_complete have 0.00% of their energy above 11 kHz and downsample for free, while
task_breakfast has 48% and task_brush_teeth 40% -- they are bright, brushy, sparkly
sounds and halving their bandwidth guts them. So the rate is chosen by measuring the
file, not by a rule.

FORMAT. The two themes arrived as a 12-second wav AND an mp3 of the same audio, 1058
KB against 52 KB. `title_song` plays through MediaPlayer rather than SoundPool
(MainActivity.startTitleMusic), and MediaPlayer reads mp3, so the mp3 is twenty times
smaller for nothing given up. Short cues stay wav: SoundPool decodes them once at
load and a compressed short cue only costs CPU.

Stdlib only, like gensounds.py and checksounds.py beside it.
"""
import array
import math
import os
import shutil
import sys
import wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'MorningMission-Animation-Pack-Batch2', 'raw')
DST = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'raw')

BUDDIES = ('burger', 'bee', 'pug', 'shark', 'dino', 'cloud', 'kitty', 'trike')

# uploaded name -> the app's resource name. Only what actually maps; a slot with no
# recording keeps the one gensounds.py made for it, which is most of the activities.
REPLACE = {}
# Six of the eight. The dino's and the shark's recordings are held back: with them
# in, checksounds cannot tell the dino's tap from the bee's EAT sound, or the shark's
# tap from the kitty's, on any of its five measures -- and where a recording cannot
# be shown to be a different sound from one already in the set, the one already in
# the set stays. Re-prompt those two for something further from a mid-range chirp and
# they can come in with the rest.
HELD_BACK = ('dino', 'shark')
for _k in BUDDIES:
    if _k not in HELD_BACK:
        REPLACE['buddy_%s_sound.wav' % _k] = 'buddy_%s_sound' % _k
REPLACE.update({
    # Five of the twelve activities have a recording. act_wake, act_bath, act_hair,
    # act_wash, act_jacket, act_pet and act_vitamin do not, and stay synthesised.
    'task_brush_teeth.wav': 'act_brush',
    'task_breakfast.wav':   'act_eat',
    'task_get_dressed.wav': 'act_dress',
    'task_backpack.wav':    'act_pack',
    'task_shoes.wav':       'act_shoes',
    'ui_tap.wav':           'ui_tap',
    'reward.wav':           'cue_goal',
    'sparkle.wav':          'cue_milestone',
    # 12 s against the 17.8 s it replaces. Worth listening for whether the shorter
    # loop starts to repeat on a long morning.
    'morning_theme.mp3':    'title_song',
})

# Recordings with no slot yet. Listed so the table stays the whole picture, and so a
# second pass adding them has somewhere to start; not imported by this script.
UNUSED = {
    'buddy_<key>_cheer.wav': 'a new per-character channel: 0.9 s, distinct per buddy',
    'buddy_<key>_hurry.wav': 'ONE sound, not eight -- all eight files are identical',
    'chest_open.wav':        'the chest opening on the countdown screen',
    'chest_wiggle.wav':      'the chest before it opens',
    'star_pop.wav':          'the star coming out of the chest',
    'countdown_zero.wav':    'the timer reaching zero',
    'task_complete.wav':     'a task ticked off',
    'ui_back.wav ui_pause.wav ui_unlock.wav ui_error.wav ui_open_settings.wav':
                             'the PIN pad and the nav bar have call sites for these',
    'calm_theme.mp3':        'a second theme; nothing plays two today',
    'victory.wav victory_theme.*': 'one shared fanfare; the app has eight, one a buddy',
}

KEEP_RATE_ABOVE = 0.01      # fraction of energy above 11 kHz that buys 44.1 kHz
TARGET_RATE = 22050


def read_wav(path):
    with contextlib_closing(wave.open(path, 'rb')) as w:
        if w.getsampwidth() != 2:
            raise SystemExit('%s is %d-bit; this only handles 16' % (path, w.getsampwidth() * 8))
        data = array.array('h')
        data.frombytes(w.readframes(w.getnframes()))
        return data, w.getframerate(), w.getnchannels()


class contextlib_closing:
    """wave objects are context managers in 3.11 but were not always; be explicit."""

    def __init__(self, thing):
        self.thing = thing

    def __enter__(self):
        return self.thing

    def __exit__(self, *exc):
        self.thing.close()


def lowpass(data, rate, cut, taps=63):
    """One windowed-sinc pass. Used both to measure the top end and to remove it."""
    mid = taps // 2
    fc = cut / rate
    kernel = []
    for i in range(taps):
        x = i - mid
        s = 2.0 * fc if x == 0 else math.sin(2.0 * math.pi * fc * x) / (math.pi * x)
        kernel.append(s * (0.54 - 0.46 * math.cos(2 * math.pi * i / (taps - 1))))
    gain = sum(kernel)
    kernel = [k / gain for k in kernel]
    n = len(data)
    out = [0.0] * n
    for i in range(n):
        acc = 0.0
        for j, kv in enumerate(kernel):
            p = i + j - mid
            if 0 <= p < n:
                acc += data[p] * kv
        out[i] = acc
    return out


def energy_above(data, rate, cut):
    """Fraction of the signal's energy above `cut` Hz.

    Measured as what a low-pass throws away, which is a real band energy. The first
    version of this probed forty-eight log-spaced frequencies and summed the ones
    above the cut, and that is not the same quantity at all -- log spacing puts most
    of the probes in the bottom octaves, so a band less than one octave wide came
    back at 2.5% when it holds 48%. It would have halved the rate of every bright
    sound in the set, which is exactly the decision this number exists to make.
    """
    n = len(data)
    if n < 512:
        return 0.0
    lp = lowpass(data, rate, cut)
    total = 0.0
    high = 0.0
    for i in range(n):
        v = float(data[i])
        total += v * v
        d = v - lp[i]
        high += d * d
    return high / total if total else 0.0


def halve(data):
    """44100 -> 22050, low-passed first so nothing folds back as a whistle."""
    # A short windowed-sinc at a quarter of the input rate. Only used on files that
    # measured near-silent up there, so this is insurance rather than the point.
    taps = 33
    mid = taps // 2
    kernel = []
    for i in range(taps):
        x = i - mid
        s = 0.5 if x == 0 else math.sin(math.pi * 0.5 * x) / (math.pi * x)
        kernel.append(s * (0.54 - 0.46 * math.cos(2 * math.pi * i / (taps - 1))))
    gain = sum(kernel)
    out = array.array('h')
    n = len(data)
    for i in range(0, n, 2):
        acc = 0.0
        for j, kv in enumerate(kernel):
            p = i + j - mid
            if 0 <= p < n:
                acc += data[p] * kv
        v = int(round(acc / gain))
        out.append(-32768 if v < -32768 else (32767 if v > 32767 else v))
    return out


def write_wav(path, data, rate):
    with contextlib_closing(wave.open(path, 'wb')) as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(data.tobytes())


def main():
    dry = '--dry-run' in sys.argv
    if not os.path.isdir(SRC):
        raise SystemExit('no upload folder at ' + SRC)
    done = 0
    for name in sorted(REPLACE):
        src = os.path.join(SRC, name)
        if not os.path.exists(src):
            print('  MISSING  %s' % name)
            continue
        slot = REPLACE[name]
        ext = os.path.splitext(name)[1]
        dst = os.path.join(DST, slot + ext)

        if ext != '.wav':
            note = 'copied as %s' % (slot + ext)
            if not dry:
                shutil.copyfile(src, dst)
                for stale in (slot + '.wav',):        # the slot may have been a wav
                    p = os.path.join(DST, stale)
                    if os.path.exists(p):
                        os.remove(p)
                        note += ', removed the old ' + stale
        else:
            data, rate, channels = read_wav(src)
            if channels != 1:
                raise SystemExit('%s is %d-channel; the app is mono' % (name, channels))
            hi = energy_above(data, rate, TARGET_RATE / 2)
            if rate == 2 * TARGET_RATE and hi < KEEP_RATE_ABOVE:
                if not dry:
                    write_wav(dst, halve(data), TARGET_RATE)
                note = 'halved to %d Hz (%.2f%% of its energy was above %d)' \
                       % (TARGET_RATE, hi * 100, TARGET_RATE // 2)
            else:
                if not dry:
                    write_wav(dst, data, rate)
                note = 'kept %d Hz (%.1f%% of its energy is above %d)' \
                       % (rate, hi * 100, TARGET_RATE // 2)
        print('  %-24s -> %-18s %s' % (name, slot, note))
        done += 1
    print('\n%d slot%s %s' % (done, '' if done == 1 else 's',
                              'would be replaced' if dry else 'replaced'))
    print('%d recordings have no slot yet; see UNUSED in this file' % len(UNUSED))


if __name__ == '__main__':
    main()
