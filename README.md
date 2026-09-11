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
| `Theme` / `BuddyTheme` | Colour roles, type scale, and the seven buddies |

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

`tools/preview/` renders every icon, collectible, goal and glyph in a browser,
re-tintable across all seven buddies, from the geometry exported by
`tools/ExportArt.java`. Regenerate with:

```
java -cp tools/.cache/android-all.jar:tools/.cache/classes \
     com.morningmission.app.ExportArt tools/preview/art.json
```

## Art

`app/src/main/res/drawable-nodpi/buddy_*.png` are the seven characters. They are
720x720 cutouts with real alpha, and six of the seven share the vertical band
`y[50..669]` — that shared baseline is what makes them swappable, so do not crop
them individually.

Everything else is drawn in code. The background PNGs that used to ship were
flat placeholder fills and have been removed.

## Reference

`design/` holds the approved mockups. `v0_6_visual_target.png` is the current
target.
