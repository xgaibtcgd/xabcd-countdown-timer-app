# Morning Mission

A morning-routine countdown timer for children. Pick a buddy and a length, press
Start Morning, and work through the routine while an animated buddy travels
through its world collecting things on the way to a goal.

Everything runs on the device. No account, no server, no internet permission.

## Building

Open the project in Android Studio and choose **Build > Build App Bundle(s) or
APK(s) > Build APK(s)**. The APK lands in `app/build/outputs/apk/debug/`.

AGP 9.4.0, Gradle 9.6.0, compileSdk 36, minSdk 26, Java 17. The project has **no
dependencies at all** — not AndroidX, not Material, not Compose. Everything is
drawn with `android.graphics`. Please keep it that way unless there is a
concrete reason not to.

## How it is put together

The whole interface is drawn by one custom `View`. There is one XML layout: none.

| | |
|---|---|
| `MainActivity` | Lifecycle, preferences, sound, kid lock, and the few system dialogs |
| `MorningView` | The frame clock, screen routing, touch dispatch, bitmap lifecycle |
| `Layout` | Where everything goes, on any shape of screen |
| `HitMap` | What is tappable, registered from the same rectangles that are drawn |
| `Engine` | The routine and the countdown, including the completion freeze |
| `Scene` | The illustrated world, drawn in code |
| `Clay` / `Art` / `Icons` | The material language and every graphic that is not a buddy |
| `Anim` / `Particles` | Buddy motion and the celebration |
| `Screen*` | One class per screen |
| `Theme` / `BuddyTheme` | Colour roles, type scale, and the buddy table |

Two conventions are worth knowing before changing anything.

**Geometry is data.** Icons live in `Art` as `float[]` command arrays in a
100x100 box, compiled to `Path` objects once. `android.graphics.Path` is
native-backed and throws off the device, so authoring shapes as data is what
makes them checkable without an emulator — and it is how the design preview
renders the same numbers the app does.

**Draw and touch read the same rectangles.** A screen places its controls in
`layout()` and registers them with the `HitMap` there, never in `draw()`. The
previous version kept two independent copies of every coordinate, and they had
drifted: task rows were drawn at a pitch of 115 and hit-tested at 120, half the
Edit button was unreachable, and the pause button had no touch region at all.

## Checks

```
tools/check.sh
```

There is no Android SDK in every environment this is worked on, so the script
compiles against `org.robolectric:android-all` — a real Android 36 framework jar
from Maven Central — plus a generated `R` stub. That type-checks every framework
call without an SDK or an emulator. It then runs:

- **`tools/SelfTest.java`** — the layout across eight screen shapes, four inset
  combinations, one to twelve tasks and three scroll positions; the countdown's
  freeze contract against a fake clock; every shape's bounds; and that all
  sixteen animation states are distinguishable. Around 410,000 assertions.
- **`tools/regioncheck.py`** — every control is both registered and handled.
- **`tools/allocgate.py`** — nothing allocates in a method that runs per frame.

None of this renders a pixel or links resources. **Build the APK in Android
Studio before shipping.**

## Design preview

`tools/preview/` has two browser previews, for looking at the app without building it.

- `index.html` -- every icon, collectible, goal and glyph, re-tintable across every
  buddy.
- `screens.html` -- all seven screens, across four device shapes.

Both render from data exported out of the app's own classes, so they cannot
quietly drift from the build: `ExportArt` dumps the `float[]` geometry and the
palettes, and `ExportScreens` runs the real `Layout` solver and dumps every
rectangle it produces. Regenerate both with:

```
tools/buildpreview.sh
```

The drawing itself is a port of `Clay`, `Scene` and the `Screen` classes into
Canvas2D. Close, but not the app running -- it has already earned its keep by
surfacing three real defects, but do not treat it as a substitute for the APK.

## Art and type

The app bundles Nunito as `res/font/mm_round` and `mm_round_bold`. The previous
version asked for `sans-serif-rounded`, which is a system family alias: it exists
on Pixel and AOSP but silently falls back to plain Roboto on many manufacturers'
builds, so the rounded lettering the design depends on was never guaranteed on a
customer's device. Nunito is under the SIL Open Font License; the licence is in
`licenses/nunito-OFL.txt` and must stay with the fonts.

`app/src/main/res/drawable-nodpi/buddy_*.png` are the eight characters. They are
720x720 cutouts with real alpha, and all but Cloud Pup share the vertical band
`y[50..669]` — that shared baseline is what makes them swappable, so do not crop
them individually. A new character is fitted to it rather than dropped in at
whatever size it arrived: trim to the alpha bounds, scale the longer side to 620,
and centre in 720. Watch for a source that carries a halo of alpha 1..4 out to the
edge of its canvas -- trimming on `getbbox()` then leaves the character small and
off-centre inside a box of nothing, which is a difference of a few percent that is
invisible in isolation and obvious the moment you switch buddies.

`bg_home_storybook.png` and the eight `bg_adventure_*.png` are the illustrated
worlds. They are 9:16; `Scene.layoutBackdrop` draws them at full width anchored
to the bottom and extends the sky above with the artwork's own top-row colour, so
a 20:9 phone crops nothing. Everything drawn over them -- light shafts, bubbles,
petals, falling leaves, drifting clouds -- is code, as is every icon,
collectible, goal and glyph. `Scene` still contains a complete procedural
environment per buddy, used when no artwork is present and for the celebration,
which has none.

## Sound

Everything in `app/src/main/res/raw/` except `victory.wav` is synthesised by
`tools/gensounds.py` -- pure standard library, no numpy, no binary blobs nobody
can change. Two per buddy plus the title loop:

- `buddy_<key>_sound` is the tap sound, played when you choose the buddy in the
  picker, tick off a task, or poke the buddy on the adventure screen.
- `buddy_<key>_eat` is one bite of a treat, fired three times per treat from
  `Engine.pollBite`, rising in pitch across the three via `SoundPool`'s rate
  argument. They are short by necessity -- the three land inside 1.4 seconds.
- `title_song` is an eighteen-second loop. It is named in `LOOPING`, which
  suppresses the anti-click edge fade every other sound gets -- on a loop that
  fade lands on the seam and pumps the volume down and back up once a lap.

Regenerate with `python3 tools/gensounds.py`. Nothing here can be listened to in
CI, so the property that matters is checked numerically instead: every one of the
sixteen buddy sounds must differ from every other on duration, burst count,
dominant frequency or spectral centroid -- most of all a buddy's eat sound from
its own tap sound, or a poke and a bite become the same noise.

## Reference

`design/` holds the approved mockups. `v0_6_visual_target.png` is the current
target.
