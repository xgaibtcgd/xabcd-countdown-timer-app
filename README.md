# Morning Mission v0.6 — Mockup-Fidelity Adventure Build

This build specifically fixes the v0.5 mismatch between the approved mockup and the running Buddy Adventure screen.

## v0.6 visual changes
- Buddy Adventure is now edge-to-edge instead of inside a giant white panel.
- Large animated buddy centered in the illustrated world.
- Collectibles appear in a floating ribbon near the top, like the approved mockup.
- Large goal/reward integrated at the right side of the scene.
- Floating current-task card and large green I DID IT button at the bottom.
- Large rounded countdown card and playful Buddy Adventure title.
- Timer still freezes the instant the final task is completed.
- All seven buddies, backgrounds, sounds, PIN/Kid Lock, task editor and custom timer remain bundled locally.

## Build
Open `MorningMission-v0.6` in Android Studio and choose **Build > Build App Bundle(s) or APK(s) > Build APK(s)**.

APK: `app/build/outputs/apk/debug/app-debug.apk`

# Morning Mission v0.6 — Visual Fidelity + Buddy Adventure

This is the visual rebuild based on the approved Morning Mission mockups. It keeps the working timer/PIN/Kid Lock behavior but replaces the sparse prototype presentation with illustrated scenery, rounded storybook typography, compact cards, and a full-screen Buddy Adventure during the countdown.

## Main experience
- Setup screen uses bundled storybook sky/meadow artwork instead of a flat background.
- Rounded outlined Morning Mission title treatment, polished buddy artwork, minute bubbles, shadowed timer/routine cards, Start Morning button, and app navigation area.
- Tapping the buddy opens the animated buddy selector.
- Tapping the timer opens an in-app graphical time screen with 1/2/5/10/15/30/60 presets plus a 1–120 minute slider.
- Editable routine presets remain in Grown-Ups.

## Active Buddy Adventure
Pressing Start Morning changes to a dedicated adventure screen. The selected buddy is large and continuously animated, moves along the collectible trail as time passes, and reacts to task completion and low time.

Buddy themes:
- Burger Buddy → mini burgers → picnic
- Queen Bee → honey drops → golden hive
- Pug Pal → pup treats → doghouse
- Splash Buddy → fish → treasure chest
- Sprout Dino → leaves → dino nest
- Cloud Pup → stars → rainbow/cloud goal
- Sweet Kitty → fish treats → gift

Seven themed illustrated backgrounds are bundled under `app/src/main/res/drawable-nodpi/`. All buddy art, backgrounds, sound cues and victory music are local—no Internet permission is needed.

The current task remains visible during the adventure. Completing it triggers a cheer/sound and advances to the next task. Low time changes the animation into the hurry state.

## Completion behavior
**The countdown freezes the instant the final task is completed.** If the child finishes with `3:42`, `3:42` stays displayed while the buddy dances and the victory music/confetti run. Finishing does not unlock Kid Mode; Parent Unlock still requires the PIN.

## Build
1. Extract this ZIP into a new folder.
2. Open the `MorningMission-v0.6` folder in Android Studio.
3. Project: AGP 9.4.0, Gradle 9.6.0, compileSdk 36, minSdk 26, Java 17.
4. If Android Studio asks for a Gradle version, choose **9.6.0**.
5. Build with **Build > Build App Bundle(s) or APK(s) > Build APK(s)**.
6. APK: `app/build/outputs/apk/debug/app-debug.apk`.

The applicationId remains `com.morningmission.app`, so your locally built debug APK should update the previous debug install when signed with the same Android Studio debug key.

## Visual assets
- `buddy_*.png` — seven polished character assets
- `bg_home_storybook.png` — home scenery
- `bg_adventure_*.png` — seven themed adventure environments
- `res/raw/buddy_*_sound.wav` — character sound personalities
- `res/raw/victory.wav` — completion music

The `design/` folder contains the approved visual references for comparison.
