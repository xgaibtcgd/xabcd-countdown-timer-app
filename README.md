# Morning Mission

A morning-routine countdown timer for children. Pick a buddy and a length, press
Start Morning, and work through the routine while an animated buddy travels
through its world collecting things on the way to a treasure chest.

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
  freeze contract against a fake clock; every shape's bounds; the adventure
  route, for every character and six durations, at each of those layouts; and
  that all sixteen animation states are distinguishable. Around 23 million
  assertions.
- **`tools/regioncheck.py`** — every control is both registered and handled.
- **`tools/allocgate.py`** — nothing allocates in a method that runs per frame.
- **`tools/routeproof.cjs`** (run by `tools/buildpreview.sh`) — the preview's
  copy of the route generator produces paths identical to `Route.java`.

None of this renders a pixel or links resources. **Build the APK in Android
Studio before shipping.**

## Design preview

`tools/preview/` has two browser previews, for looking at the app without building it.

- `index.html` -- every icon, collectible and glyph, re-tintable across every buddy,
  plus the chest's opening frames.
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
collectible and glyph. `Scene` still contains a complete procedural environment
per buddy, used when no artwork is present and for the celebration, which has
none.

Queen Bee is the first character built from parts rather than one flat bitmap:
`buddy_bee_part_{head,torso,arm_l,arm_r,wings}.png`, placed by the table in
`Rig.java`. The parts are drawn INSIDE the outer transform `drawSprite` already
applies, so the bob, squash, contact shadow and the one-shot feast and poke moves
keep working untouched, and the other seven take exactly the path they took
before. It costs less than what it replaces: five parts trimmed to their own
alpha bounds are 1.19 MB decoded against 2.07 MB for the flat sprite.

What a rig buys is lag — the wings trailing the body rather than moving with it.
`Anim.solve` is a pure function of time, so "where the body was a beat ago" is
the same call at `t - Anim.RIG_LAG`, with no history to keep and nothing to reset
when the screen changes. The wings beat by squashing toward their root rather
than rotating, because they arrived as one bitmap of a pair and rotating that
see-saws it.

The eight `buddy_<key>_cheer.png` are the same characters with their arms up, on
the Complete screen. A rigged buddy skips them and poses its own arms instead. They are fitted to the walking sprite's own framing rather
than to their own bounding box -- a cheer is a different shape from a stand, so
normalising by bounds makes a character change size the moment it celebrates.
The placement was solved by maximising silhouette overlap against the walking
sprite (0.80-0.94 IoU on the eight), which lands head on head and body on body
whatever the limbs are doing. Anything that sits ON the character rather than on
its frame -- the crown -- is anchored to `MorningView.topFraction`, since the
frames are padded by different amounts per pose.

All eight characters are rigged. Each has five parts -- signature, torso, two
arms, head -- placed by a fitter rather than by hand: the head is located in the
shipped flat sprite by masked normalised cross-correlation, which fixes the
scale for every part (the sheets draw all of a character's pieces at one scale;
measured on the bee, the sheet's torso/head width ratio is 1.017 and the
hand-authored rig's is 1.017), and the rest of the body is then nudged part by
part until the assembly matches the sprite IN COLOUR, pixel by pixel.

Colour, not silhouette. Silhouette overlap was the obvious objective and is
actively misleading: an arm folded flat against the belly has the same
silhouette as no arm at all, so it rewards hiding every limb inside the torso --
which it did, on all eight at once, and the scores went up while the bee lost
its arms. Final silhouette agreement with the sprite each rig replaces runs
0.76 to 0.91; the bee's hand-authored rig, the only one anybody eyeballed into
place, scores 0.77.

Two things vary per character and the rig carries both. Only a wing squashes
toward its root (`signatureSquash`) -- a tail that shortened and lengthened on
the beat would read as broken. Only Burger Buddy's signature part draws in front
of the body (`signatureInFront`), because it is the cheeseburger being held, not
a limb. The buddy picker deliberately still draws flat sprites: it lays out all
eight at once and the rig bitmap cache holds one character's parts.

`prize_chest_0..4.png` and `prize_star.png` are the finale. The chest is the
last collectible and the destination at once, shared by all eight characters
where each used to have its own goal drawn from `float[]` geometry -- a basket,
a hive, a volcano. It is not eaten: the buddy arrives, the chest swings open
through its five frames, a star climbs out, and the buddy dances for the rest of
the morning (`Engine.atPrize`, `Engine.PRIZE_LEAD_MS`). The frames are
registered on the chest's own base, so the box holds still and only the lid
moves.

## The route

`Route.java` decides where the treats go and how the buddy gets to them. The
morning's collectibles are laid out on a grid and the buddy threads a winding
path between them, so all twenty-three of an hour's honey drops are on screen at
once and the chest is somewhere new every morning.

The path is a random Hamiltonian path over the grid, sampled by **backbite** --
start from a serpentine, then repeatedly take the free end, find a grid
neighbour of it elsewhere on the path, and reverse the run between them. Every
move leaves another Hamiltonian path, so there is no failure case and no retry
loop; it costs about 5µs. Consecutive waypoints are grid neighbours and no cell
is visited twice, which is why **the path cannot cross itself** -- a property of
how it is built, and asserted directly rather than tested for crossings.

The near end is pinned: only the far end is ever backbitten, so the journey
always sets off from the front-left corner and it is the chest that moves.
Seeded from the clock in `Engine.start`/`reset`, so it holds still all morning
and changes the next.

The grid is shaped per character, off `temperament.hover`. Flyers and swimmers
get most of the scene and no depth scaling. Walkers get a band from the ground
up to their own backdrop's horizon, as deep as it will take (rows may overlap --
that is what a receding field looks like) with the back rows drawn smaller.
`tools/preview/route.js` reproduces the generator exactly for the design
preview, which `tools/routeproof.cjs` checks against real paths exported from
`Route.java` itself.

## Sound

Everything in `app/src/main/res/raw/` is synthesised by `tools/gensounds.py` --
pure standard library, no numpy, no samples, no binary blobs nobody can change.
Forty-five cues in six families, plus the title loop:

- `buddy_<key>_sound` -- the tap sound, played when you choose the buddy in the
  picker, tick off a task, or poke the buddy on the adventure screen.
- `buddy_<key>_eat` -- one bite of a treat, fired three times per treat from
  `Engine.pollBite`, rising in pitch across the three via `SoundPool`'s rate
  argument. Short by necessity: the three land inside 1.4 seconds.
- `act_*` -- one per `Art.ACT_*`, played when a task becomes the ACTIVE one
  rather than when it is finished. A toothbrush sound says brush your teeth to a
  child who cannot yet read "Brush Teeth".
- `ui_tap` / `ui_confirm` / `ui_page` -- every chip and row, the one primary
  button on a screen, and a change of screen. `Screen.tapSound(id, data)`
  decides which, defaulting to `UI_TAP` so a new control cannot ship silent.
- `cue_goal` / `cue_milestone` / `cue_tick` -- the goal opening, the halfway
  mark, and one tick per second through the last ten. The last two are edge
  detected by `Engine.pollMilestone()` and `Engine.pollTick()`, called from the
  frame loop beside `pollTimeUp()`, never as a side effect of a draw.
- `poke_*` -- the escalating reaction to being poked.
- `victory_<key>` -- the Mission Complete fanfare, one per character and eight
  genuinely different pieces: arcade, swing, marching band, surf, ska, music
  box, sparkly pop and taiko. These are the only sounds rendered at 44.1 kHz
  (see `HIFI` and `at_rate`), because they are the only ones with cymbals on
  them and a crash with nothing above 11 kHz is most of what makes synthesised
  percussion sound cheap. About five and a half seconds each, which is what the
  confetti lasts.
- `title_song` is an eighteen-second loop. It is named in `LOOPING`, which
  suppresses the anti-click edge fade every other sound gets -- on a loop that
  fade lands on the seam and pumps the volume down and back up once a lap.

Resource tables live in `Sounds.java` rather than in `MainActivity`, so
SelfTest can hold `Sounds.ACTIVITY` exactly as long as `Art.ACT_COUNT`.

The victory fanfares replaced a single hand-authored `victory.wav` that every
character shared. It is worth knowing what it measured, because the gate below
is written from it: 90% of its energy in one octave around 1 kHz, nothing at all
below 350 Hz or above 1.7 kHz, and a peak of 0.38. One bare melody line, quiet.

Regenerate with `python3 tools/gensounds.py`. Nothing here can be listened to in
CI, so the properties that matter are checked numerically by
`tools/checksounds.py`, which runs in `check.sh`:

- **Distinctness**, within each compared family: every pair must differ on
  duration, burst count, dominant frequency or spectral centroid. Across
  families the bar would be wrong -- the interface tap and the backpack buckle
  are both meant to be a short click. What has to hold is that no two sounds a
  person hears as alternatives are the same sound.
- **Not thin**, for the fanfares: a peak above 0.8, at least four octave bands
  carrying real energy, and no single band owning more than half the piece.
  Every one of those would have failed the file they replaced.

The fanfares are deliberately left out of the distinctness comparison, and not
because they failed it. Those four descriptors were built for sounds under a
second; across five seconds of music they average out, and a swing quartet and
a taiko ensemble both measure as "130 Hz, 22 bursts, centroid 430" -- true and
useless. A measurement that cannot tell a march from a surf lick should not
claim to.

## Reference

`design/` holds the approved mockups. `v0_6_visual_target.png` is the current
target.
